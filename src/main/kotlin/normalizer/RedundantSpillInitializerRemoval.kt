/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package normalizer

import algorithms.dominates
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.LTACCmd
import analysis.maybeNarrow
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import optimizer.isMemoryAccess
import tac.NBId
import utils.*
import vc.data.*
import vc.data.TACExprFactoryExtensions.neq
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.TACExprFactSimple
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*

object RedundantSpillInitializerRemoval {
    private fun LTACCmd.maybeWritesMemory() = (this.cmd is TACCmd.Simple.AssigningCmd.ByteStore && this.cmd.base == TACKeyword.MEMORY.toVar()) ||
        (this.cmd is TACCmd.Simple.LongAccesses && this.cmd.accesses.all {
            it.isWrite && it.base == TACKeyword.MEMORY.toVar()
        })

    private fun LTACCmd.probablyNotOverlapping(c: BigInteger) : Boolean {
        return this.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && (
            this.cmd.base != TACKeyword.MEMORY.toVar() ||
            this.cmd.loc !is TACSymbol.Const ||
            this.cmd.loc.value in (c - 31.toBigInteger() .. c + 31.toBigInteger())
        )
    }

    private enum class StepStatus {
        FAIL,
        NEXT,
        COMPLETE
    }

    private fun step(
        which: Iterable<LTACCmd>,
        target: BigInteger,
        ends: Collection<CmdPointer>,
        out: MutableList<Pair<CmdPointer, TACSymbol.Var>>
    ) : StepStatus {
        for(cmd in which) {
            if(cmd.ptr in ends) {
                return StepStatus.COMPLETE
            }
            if (!cmd.isMemoryAccess()) {
                continue
            }
            if (cmd.maybeWritesMemory()) {
                return StepStatus.FAIL
            }
            if (cmd.probablyNotOverlapping(target)) {
                cmd.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteLoad>()?.takeIf {
                    it.cmd.base == TACKeyword.MEMORY.toVar()
                }?.cmd?.loc?.let { it as? TACSymbol.Var }?.let { toAssume ->
                    out.add(cmd.ptr to toAssume)
                }
            } else {
                return StepStatus.FAIL
            }
        }
        return StepStatus.NEXT
    }

    private data class ProposedRewrite(
        val toDelete: CmdPointer,
        val neqAssumption: BigInteger,
        val assumptionLocs: List<Pair<CmdPointer, TACSymbol.Var>>
    )

    fun removeRedundantSpills(c: CoreTACProgram): CoreTACProgram {
        val spillCandidates = OptimisticSpillRewriter.findSpillCandidates(c).takeIf { it.isNotEmpty() } ?: return c
        val dom = c.analysisCache.domination
        val revertBlocks = c.analysisCache.revertBlocks
        val graph = c.analysisCache.graph
        val rewrites = c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()
        }.mapNotNull {
            val loc = (it.cmd.loc as? TACSymbol.Const)?.value?.takeIf { it in spillCandidates } ?: return@mapNotNull null
            loc to it
        }.collect(Collectors.groupingBy({ it.first }, Collectors.mapping({it.second}, Collectors.toList()))).mapNotNull { (constTarget, l) ->
            val cand = l.singleOrNull { pred ->
                l.all { other ->
                    other.ptr == pred.ptr || dom.dominates(pred.ptr, other.ptr)
                }
            } ?: return@mapNotNull null
            val other = l.filter {
                it.ptr != cand.ptr
            }.mapToSet { it.ptr }
            val assumptions = mutableListOf<Pair<CmdPointer, TACSymbol.Var>>()
            when(step(
                which = graph.iterateBlock(cand.ptr, excludeStart = true),
                target = constTarget,
                ends =  other,
                out = assumptions
            )) {
                StepStatus.COMPLETE -> return@mapNotNull ProposedRewrite(cand.ptr, constTarget, assumptions)
                StepStatus.FAIL -> return@mapNotNull null
                StepStatus.NEXT -> {

                }
            }

            object : VisitingWorklistIteration<NBId, Pair<CmdPointer, TACSymbol.Var>, List<Pair<CmdPointer, TACSymbol.Var>>?>() {
                override fun process(it: NBId): StepResult<NBId, Pair<CmdPointer, TACSymbol.Var>, List<Pair<CmdPointer, TACSymbol.Var>>?> {
                    if(it in revertBlocks) {
                        return this.cont(listOf())
                    }
                    val toReport = mutableListOf<Pair<CmdPointer, TACSymbol.Var>>()
                    when(step(
                        which = graph.elab(it).commands,
                        target = constTarget,
                        out = toReport,
                        ends = other
                    )) {
                        StepStatus.FAIL -> return this.halt(null)
                        StepStatus.COMPLETE -> return StepResult.Ok(
                            next = listOf(),
                            result = toReport
                        )
                        StepStatus.NEXT -> return StepResult.Ok(
                            next = graph.succ(it),
                            result = toReport
                        )
                    }
                }

                override fun reduce(results: List<Pair<CmdPointer, TACSymbol.Var>>): List<Pair<CmdPointer, TACSymbol.Var>>? {
                    return results
                }
            }.submit(graph.succ(cand.ptr.block))?.let {
                ProposedRewrite(cand.ptr, constTarget, assumptions + it)
            }
        }
        if(rewrites.isEmpty()) {
            return c
        }
        val toRemove = mutableSetOf<CmdPointer>()
        val toInsertBefore = mutableMapOf<CmdPointer, CommandWithRequiredDecls<TACCmd.Simple>>()
        for(rewrite in rewrites) {
            if(rewrite.toDelete in toInsertBefore) {
                toInsertBefore.remove(rewrite.toDelete)
            }
            toRemove.add(rewrite.toDelete)
            for(assumption in rewrite.assumptionLocs) {
                val assumptionCode = ExprUnfolder.unfoldPlusOneCmd("spillAssume", TACExprFactSimple {
                    assumption.second neq rewrite.neqAssumption
                }) {
                    TACCmd.Simple.AssertCmd(it.s, "optimistic spill")
                }
                toInsertBefore.merge(assumption.first, assumptionCode) { a, b ->
                    a andThen b
                }
            }
        }
        return c.patching { patcher ->
            for(it in toRemove) {
                patcher.replaceCommand(it, listOf(TACCmd.Simple.NopCmd))
            }
            for((where, before) in toInsertBefore) {
                patcher.addBefore(where, before)
            }
        }
    }
}
