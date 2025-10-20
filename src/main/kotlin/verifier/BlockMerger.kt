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

package verifier

import datastructures.MutableReversibleDigraph
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import tac.NBId
import utils.uniqueOrNull
import vc.data.*

private val logger = Logger(LoggerTypes.NORMALIZER)

object BlockMerger {
    fun mergeBlocksWithSameSuccessorsAndPC(code: CoreTACProgram): CoreTACProgram {
        val graph = code.analysisCache.graph
        fun nodeToGroup(nbid: NBId) = graph.pred(nbid).let { preds ->
            val groups = preds.groupBy { it.origStartPc to it.stkTop }
            groups.entries.find { predGroupEntry ->
                predGroupEntry.value.size > 1
                        && predGroupEntry.value.map {
                    graph.succ(
                        it
                    )
                }.uniqueOrNull() != null
            }
        }

        val trgToPredsToConsolidate = graph.blocks
            .mapNotNull {
                nodeToGroup(it.id)
            }

        val patch = code.toPatchingProgram()
        val consolidated = mutableSetOf<NBId>()
        trgToPredsToConsolidate.forEach { (succ, preds) ->
            if (preds.any { it in consolidated }) {
                logger.info { "Already consolidated $preds, no need to handle for their successor $succ"}
            } else {
                // TODO @ericeil CERT-1699
                val (leave, remove) = preds.partition { it.topOfStackValue == 0 }
                if (leave.size == 1) {
                    logger.info { "Consolidating edges of $preds to $succ by leaving only $leave" }
                    /**
                     * Instead of using [consolidateEdges], reroute predecessors to the single successor and then remove
                     * the duplicate blocks. This is faster as [consolidateEdges] repeatedly calls [SimpleDominanceAnalysis].
                     */
                    remove.forEach {
                        patch.reroutePredecessorsTo(it, leave.single())
                        patch.removeBlock(it)
                    }
                    consolidated.addAll(preds)
                } else {
                    logger.info { "Cannot select which predecessor to leave in edges from $preds to $succ" }
                }
            }
        }

        return patch.toCodeNoTypeCheck(code)
    }

    fun mergeBlocks(prog: CoreTACProgram): CoreTACProgram {
        return generateMerged(prog).let { (code, graph) ->
            prog.copy(
                code = code,
                blockgraph = graph
            )
        }
    }

    fun mergeBlocks(prog: CVLTACProgram): CVLTACProgram {
        return generateMerged(prog).let { (code, graph) ->
            prog.copy(
                code = code,
                blockgraph = graph
            )
        }
    }

    /*
    detect chains of blocks in the graph that have a single successor.
    as long as the graph contains nodes with a single successor, take one, merge the successor into the predecessor (code + next blocks), and remove the successor
     */
    fun <T: TACCmd> generateMerged(
        c: TACProgram<T>,
        mayMerge: (NBId, NBId) -> Boolean = { _, _ -> true }
    ): Pair<Map<NBId, List<T>>, BlockGraph> {
        val newCode = c.code.toMutableMap()
        val newGraph = MutableReversibleDigraph<NBId>(c.blockgraph)
        val newGraphReversed = newGraph.asReversed()

        fun collectChainOfSingleSuccessors(startFrom: NBId): List<NBId> {
            val chain = mutableListOf<NBId>()
            var current = startFrom
            chain.add(current)
            if (current !in newGraph) {
                throw Exception("Start node $current is not in graph $newGraph")
            }
            while (newGraph[current]!!.size == 1) {
                current = newGraph[current]!!.first()

                // don't add current if it has more predecessors, or if it's a new callee id (readability? TODO)

                if (current.calleeIdx != startFrom.calleeIdx) {
                    break
                }
                val predCount = newGraphReversed[current]!!.size
                check (predCount > 0) { "Current must have at least one predecessor" }
                if (predCount > 1) {
                    break
                }

                if (!mayMerge(chain.last(), current)) {
                    break
                }

                chain.add(current)
                check (current in newGraph) { "Current node $current is not in graph $newGraph" }
            }

            return chain
        }

        // take candidates for chain starters
        val candidates = mutableListOf<Map.Entry<NBId, Set<NBId>>>()
        var changed = true
        while (changed) {
            changed = false
            candidates.addAll(newGraph.filter { it.value.size == 1 }.entries.sortedBy { it.key }.toMutableList())

            while (candidates.isNotEmpty()) {
                logger.info { "Candidates are $candidates" }
                val mergeCandidate = candidates.removeAt(0)

                val chain = collectChainOfSingleSuccessors(mergeCandidate.key)
                //val next = mergeCandidate.value.first()
                val newMergedCode = mutableListOf<T>()

                chain.forAllButLast { b ->
                    if (b !in newCode) {
                        throw Exception("Current chain node $b from $chain not in code")
                    }
                    val code = newCode[b]!!.filterNot { it is TACCmd.Simple.JumpCmd }
                    newMergedCode.addAll(code)
                }

                val last = chain.last()
                newMergedCode.addAll(newCode[last]!!)
                val successors = newGraph[chain.last()]!!

                // remove all but merge candidate
                if (chain.size > 1) {
                    logger.info { "Merged $chain for ${mergeCandidate.key}" }
                    changed = true

                    chain.forAllButFirst {
                        newCode.remove(it)
                        newGraph.remove(it)
                    }
                    candidates.removeAll { chain.containsIgnoringFirst(it.key) }

                    newCode.put(mergeCandidate.key, newMergedCode)
                    newGraph.put(mergeCandidate.key, successors)
                }
            }
        }

        return Pair(newCode, newGraph.forward)
    }

}
