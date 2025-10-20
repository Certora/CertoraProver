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

package analysis.opt

import algorithms.*
import analysis.*
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import com.certora.collect.*
import config.*
import datastructures.*
import datastructures.stdcollections.*
import log.*
import tac.*
import utils.*
import vc.data.*

private val logger = Logger(LoggerTypes.DEDUPLICATOR)

/**
    Removes semantically duplicate subgraphs from the program.
    */
object Deduplicator {
    fun deduplicateBlocks(c: CoreTACProgram): CoreTACProgram {
        val graph = c.analysisCache.graph
        val patch = c.toPatchingProgram()
        deduplicateBlocks(graph, patch, PatchingTACProgram.SIMPLE, getActiveInternalCalls(graph))
        return patch.toCodeNoTypeCheck(c)
    }

    fun <T : TACCmd> deduplicateBlocks(
        graph: GenericTACCommandGraph<T, *, *>,
        patch: PatchingTACProgram<T>,
        remapper: PatchingTACProgram.CommandRemapper<T>,
        activeCalls: Map<NBId, PersistentStack<Int>> = graph.blockIds.associateWith { persistentStackOf() }
    ) {
        // Successors are ordered!
        val successors = mutableMapOf<NBId, List<NBId>>()

        val blockLabels = graph.blockIds.associate { block ->
            val body = graph.code[block] ?: error("Block $block not found in code")
            val succ = body.flatMap {
                when (it) {
                    is TACCmd.Simple.JumpCmd -> listOf(it.dst)
                    is TACCmd.Simple.JumpiCmd -> listOf(it.dst, it.elseDst)
                    else -> listOf()
                }
            }
            if (succ.isEmpty()) {
                // Fallthrough case
                successors[block] = graph.succ(block).toList()
            } else {
                check(succ.toSet() == graph.succ(block)) {
                    "In ${graph.name}: $block: successors don't match body commands: ${graph.succ(block)} vs $succ"
                }
                successors[block] = succ
            }

            block to DedupBlockLabel(block, activeCalls[block], body)
        }

        val labeledGraph = LabeledOrderedDigraph(blockLabels, successors)
        val representativeBlocks = labeledGraph.findIsomorphicSubgraphs(maxIterations = Config.MaxDedupIterations.get())

        representativeBlocks.forEachEntry { (block, rep) ->
            if (rep != block) {
                logger.debug { "Merging duplicate blocks: $block -> $rep" }
                patch.reroutePredecessorsTo(block, rep, remapper)
                patch.removeBlock(block)
            }
        }
    }


    /**
        Block label for deduplication.  This should include everything semantically important about the block itself
        that we want to be represented in the resulting (trimmed) program.
     */
    private data class DedupBlockLabel private constructor(
        private val origStartPc: Int,
        private val topOfStackValue: Int,
        private val activeCalls: PersistentStack<Int>?,
        private val missingActiveCallsToken: MissingActiveCallsToken?,
        private val body: List<TACCmd>
    ) {
        /**
            Just a token to uniquify the label if we couldn't find a valid call stack for this block.  This is just to
            ensure we don't merge blocks that are actually part of different internal function calls. (The internal
            summarizer might be able to identify calls we can't.)
         */
        private class MissingActiveCallsToken

        companion object {
            operator fun invoke(
                nbid: NBId,
                activeCalls: PersistentStack<Int>?,
                body: List<TACCmd>
            ) = DedupBlockLabel(
                origStartPc = nbid.origStartPc,
                topOfStackValue = nbid.topOfStackValue,
                activeCalls = activeCalls,
                missingActiveCallsToken = runIf(activeCalls == null) { MissingActiveCallsToken() },
                body = normalizeBody(body)
            )

            private fun normalizeBody(body: List<TACCmd>) = body.mapNotNull {
                when (it) {
                    // Jumpdests are not semantically important
                    is TACCmd.Simple.JumpdestCmd -> null
                    // Jump destinations are not needed in the body label; they're covered by the graph edges
                    is TACCmd.Simple.JumpCmd -> it.copy(dst = StartBlock)
                    is TACCmd.Simple.JumpiCmd -> it.copy(dst = StartBlock, elseDst = StartBlock)
                    else -> it
                }?.let {
                    // Allow merging of blocks with different TACMetaInfo (e.g. jump types, etc.)
                    (it as? TACCmd.Simple)?.withMeta(it.meta - META_INFO_KEY) ?: it
                }
            }
        }
    }


    /**
        Gets the set of internal call IDs that are active at entry to each block.
     */
    private fun getActiveInternalCalls(graph: TACCommandGraph): Map<NBId, PersistentStack<Int>> {
        val results = object : TACBlockDataflowAnalysis<Result<PersistentStack<Int>>>(
            graph = graph,
            bottom = Result.success(persistentStackOf()),
            direction = Direction.FORWARD,
            lattice = JoinLattice.ofJoin { aRes, bRes ->
                aRes.mapCatching { a ->
                    val b = bRes.getOrThrow()
                    check(a == b) { "In ${graph.name}: Active internal calls mismatch: $a vs $b" }
                    a
                }
            }
        ) {
            override fun transform(inState: Result<PersistentStack<Int>>, block: TACBlock): Result<PersistentStack<Int>> =
                inState.mapCatching { inCalls ->
                    inCalls.mutate { calls ->
                        block.commands.forEach { lcmd ->
                            lcmd.maybeAnnotation(INTERNAL_FUNC_START)?.let { start ->
                                calls.push(start.id)
                            }
                            lcmd.maybeAnnotation(INTERNAL_FUNC_EXIT)?.let { end ->
                                check(end.id == calls.top) {
                                    "In ${graph.name}: Exiting the wrong function: $lcmd vs $calls"
                                }
                                calls.pop()
                            }
                        }
                    }
                }
            init {
                runAnalysis()
            }
        }.blockIn

        var warned = false
        return results.mapNotNull { (block, result) ->
            (block `to?` result.getOrNull()) ?: run {
                if (block in graph.cache.revertBlocks || RevertBlockAnalysis.mustReachRevert(block, graph)) {
                    // revert blocks can be freely combined
                    block to persistentStackOf()
                } else {
                    if (!warned) {
                        logger.warn { "Internal call mismatches in ${graph.name}" }
                        warned = true
                    }
                    logger.debug(result.exceptionOrNull()!!) { "In ${graph.name} no internal calls for $block" }
                    null
                }
            }
        }.toMap()
    }
}
