/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package optimizer

import analysis.*
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StepResult
import analysis.worklist.WorklistIteration
import com.certora.collect.*
import datastructures.stdcollections.*
import instrumentation.StoragePackedLengthSummarizer
import tac.NBId
import utils.*
import vc.data.*
import verifier.BlockMerger

/**
 * Finds two decodes of a string length (as determined by
 * [instrumentation.StoragePackedLengthSummarizer.StorageLengthReadSummary]) that *must* yield the same decoded value;
 * that is, the underlying "index" (aka, identity) of the string being decoded is the same, and there have been
 * no intervening updates to storage. If this is the case, then, under certain conditions, we can replace the
 * entire decode operation with the already computed result of the decode (if it still exists in some register).
 *
 * This is all necessary because the solidity compiler generates the following:
 * ```
 * l = decode(i)
 * ptr = fp;
 * fp = fp + 32 + l;
 * l2 = decode(i)
 * if l2 == 0 then ... else ...
 * ```
 */
object RedundantStorageReadOptimizer {
    private sealed class StorageRead {
        data class LengthDecode(override val index: TACSymbol) : StorageRead()
        data class RawSlot(override val index: TACSymbol) : StorageRead()
        abstract val index: TACSymbol
    }

    private data class Rewrite(
        val lengthVar: TACSymbol.Var,
        val rawSlotVar: TACSymbol.Var?
    )

    fun optimizeReads(c: CoreTACProgram) : CoreTACProgram {
        val state = mutableMapOf<NBId, TreapMap<TACSymbol.Var, StorageRead>>()
        for(b in c.analysisCache.graph.rootBlocks) {
            state[b.id] = treapMapOf()
        }
        val graph = c.analysisCache.graph
        val replacements = mutableMapOf<CmdPointer, Rewrite>()

        /**
         * Given a command that holds [instrumentation.StoragePackedLengthSummarizer.StorageLengthReadSummary], determine
         * whether the decoded length is used immediately to allocate a string.
         */
        val success = object : WorklistIteration<NBId, Unit, Boolean>() {
            override val scheduler: IWorklistScheduler<NBId> = c.analysisCache.naturalBlockScheduler

            override fun process(it: NBId): StepResult<NBId, Unit, Boolean> {
                var st = state[it]!!.builder()
                val knownIndices = mutableSetOf<TACSymbol.Var>()
                st.mapNotNullTo(knownIndices) {
                    it.value.index as? TACSymbol.Var
                }

                val block = graph.elab(it)

                for(lc in block.commands) {
                    /**
                     * Clear all our state if we see any mutation of storage, this is a very coare grained "may-aliasing"
                     * check (everything can alias)
                     */
                    if(lc.cmd is TACCmd.Simple.WordStore && lc.cmd.base.meta.find(TACMeta.STORAGE_KEY) != null) {
                        st.clear()
                    } else if(lc.cmd is TACCmd.Simple.AssigningCmd && lc.cmd.lhs.meta.find(TACMeta.STORAGE_KEY) != null) {
                        st.clear()
                    } else if(lc.cmd is TACCmd.Simple.AssigningCmd) {
                        /*
                         * Otherwise, kill the state related to the lhs.
                         */
                        if(lc.cmd.lhs in st || lc.cmd.lhs in knownIndices) {
                            st = st.build().updateValues { k: TACSymbol.Var, storageRead: StorageRead ->
                                if(k == lc.cmd.lhs || storageRead.index == lc.cmd.lhs) {
                                    null
                                } else {
                                    storageRead
                                }
                            }.builder()
                            knownIndices.remove(lc.cmd.lhs)
                        }
                    }
                }
                val work = mutableListOf<NBId>()
                for((dst, cond) in graph.pathConditionsOf(it)) {
                    if(dst in graph.cache.revertBlocks) {
                        continue
                    }
                    val s = if(cond is PathCondition.Summary && cond.s is StoragePackedLengthSummarizer.StorageLengthReadSummary) {
                        val finalCmd = block.commands.last()
                        check(finalCmd.snarrowOrNull<StoragePackedLengthSummarizer.StorageLengthReadSummary>() == cond.s) {
                            "Impossible path condition, have summary condition: ${cond.s} but the final command of the block is $finalCmd"
                        }
                        if(dst == cond.s.originalBlockStart) {
                            continue
                        }
                        check(dst == cond.s.skipTarget) {
                            "Unexpected destination"
                        }
                        val decodeIndex =  getDecodedIndex(cond.s) ?: return StepResult.StopWith(false)
                        // is there something in our state already?
                        val summ = cond.s
                        val existingIdx = st.findEntry { _, z ->
                            z is StorageRead.LengthDecode && z.index == decodeIndex
                        }
                        val existingRawSlot = if(summ.storageSlotVar != null) {
                            st.findEntry { _, z ->
                                z is StorageRead.RawSlot && z.index == decodeIndex
                            }
                        } else { null }
                        val killed = st.build().updateValues { p: TACSymbol.Var, read: StorageRead ->
                            if(p !in summ.modifiedVars && read.index !in summ.modifiedVars) {
                                read
                            } else {
                                null
                            }
                        }
                        if(existingIdx != null && (summ.storageSlotVar == null || existingRawSlot != null)) {
                            replacements[finalCmd.ptr] = Rewrite(
                                lengthVar = existingIdx.first,
                                rawSlotVar = existingRawSlot?.first // this is non-null if it has to be by the check above
                            )
                            killed
                        } else {
                            replacements.remove(finalCmd.ptr)
                            // now record this is a fresh alias for later redundant reads
                            if(summ.outputVar != decodeIndex && summ.storageSlotVar != decodeIndex) {
                                val withNewBindings = killed.builder()
                                withNewBindings[summ.outputVar] = StorageRead.LengthDecode(decodeIndex)
                                if(summ.storageSlotVar != null) {
                                    withNewBindings[summ.storageSlotVar] = StorageRead.RawSlot(decodeIndex)
                                }
                                withNewBindings.build()
                            } else {
                                killed
                            }
                        }
                    } else {
                        st.build()
                    }
                    if(dst !in state) {
                        state[dst] = s
                        work.add(dst)
                    } else {
                        val curr = state[dst]!!
                        val join = curr.pointwiseBinopOrNull(s) { a, b ->
                            a.takeIf { it == b }
                        }
                        if(join != curr) {
                            state[dst] = join
                            work.add(dst)
                        }
                    }
                }
                return this.cont(work)
            }

            override fun reduce(results: List<Unit>) : Boolean {
                return true
            }
        }.submit(graph.rootBlocks.map { it.id })
        if(!success) {
            return c
        }
        if(replacements.isEmpty()) {
            return c
        }
        return c.patching { p ->
            for((where, r) in replacements) {
                val origSummary = graph.elab(where).snarrowOrNull<StoragePackedLengthSummarizer.StorageLengthReadSummary>()!!
                val newHead = p.splitBlockBefore(where)
                p.consolidateEdges(origSummary.skipTarget, listOf(newHead))
                val toAdd = mutableListOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = origSummary.outputVar,
                        rhs = r.lengthVar
                    )
                )
                if(r.rawSlotVar != null && origSummary.storageSlotVar != null) {
                    toAdd.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = origSummary.storageSlotVar,
                        rhs = r.rawSlotVar
                    ))
                }
                p.addBefore(graph.elab(origSummary.skipTarget).commands.first().ptr, toAdd)
            }
        }.let(BlockMerger::mergeBlocks)
    }


    private fun getDecodedIndex(s: StoragePackedLengthSummarizer.StorageLengthReadSummary) : TACSymbol? {
        return when(s.readSort) {
            is StoragePackedLengthSummarizer.SizeReadSort.WordLoad -> s.readSort.indexSym
            is StoragePackedLengthSummarizer.SizeReadSort.UnpackRead -> s.readSort.read.meta.find(TACMeta.SCALARIZATION_SORT)?.let {
                it as? ScalarizationSort.Split
            }?.idx?.asTACSymbol()
        }
    }
}
