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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.host.near.opt

import algorithms.SimpleDominanceAnalysis
import analysis.CmdPointer
import analysis.TACCommandGraph
import analysis.maybeAnnotation
import datastructures.stdcollections.intersect
import datastructures.stdcollections.listOf
import datastructures.stdcollections.minus
import datastructures.stdcollections.mutableSetOf
import datastructures.stdcollections.orEmpty
import datastructures.stdcollections.plus
import datastructures.stdcollections.setOf
import datastructures.stdcollections.singleOrNull
import datastructures.stdcollections.toMutableSet
import tac.NBId
import utils.associateWithNotNull
import utils.containsAny
import utils.flatMapToSet
import vc.data.CoreTACProgram
import vc.data.TACCmd
import wasm.impCfg.WASM_INLINED_FUNC_END
import wasm.impCfg.WASM_INLINED_FUNC_START
import wasm.impCfg.WasmInliner
import java.util.concurrent.ConcurrentHashMap

/**
 * Slice out trivial `drop` (as in rust drop) from [ctp]
 *
 * Since we do not model things like `free`, most `drop` code is actually just noise: lots of
 * memory accesses that eventually yield arguments to free (a no-op for us).
 *
 * i.e. something like `R1 = Mem[ptr]; R2 = R1 + 0x20; free(R2)`
 *
 * This pass tries to slice all of this out:
 *
 * 1. Find "drop_in_place" code regions (this is just a heuristic for finding the regions to slice out, so it's safe
 *    to rely on annotations)
 * 2. For each code region (set of blocks roughly, though regions can start mid-block) R,
 *    if:
 *      a) all vars defined in R are exclusively used _within_ R
 *      b) R has no effects (e.g. no memory writes, no summaries)
 *    then:
 *      remove R from the graph
 */
object EliminateTrivialDrops {
    fun transform(ctp: CoreTACProgram): CoreTACProgram {
        val starts = ConcurrentHashMap<Int, CmdPointer>()
        val ends = ConcurrentHashMap<Int, MutableSet<CmdPointer>>()
        ctp.parallelLtacStream().forEach { lcmd ->
            lcmd.maybeAnnotation(WASM_INLINED_FUNC_START)?.takeIf {
                @Suppress("ForbiddenMethodCall")
                WasmInliner.Companion.demangle(it.funcName).contains("drop_in_place")
            }?.id?.let { id ->
                check(!starts.containsKey(id)) { "already saw: ${id} - ${lcmd}" }
                starts[id] = lcmd.ptr
            }
            lcmd.maybeAnnotation(WASM_INLINED_FUNC_END)?.takeIf {
                @Suppress("ForbiddenMethodCall")
                WasmInliner.Companion.demangle(it.funcName).contains("drop_in_place")
            }?.id?.let { id ->
                ends.computeIfAbsent(id) { mutableSetOf() }.add(lcmd.ptr)
            }
        }
        val common = starts.keys.intersect(ends.keys)
        val g = ctp.analysisCache.graph
        val dom = ctp.analysisCache.domination
        val revdom = SimpleDominanceAnalysis(g.toRevBlockGraph())
        val reach = ctp.analysisCache.reachability

        val subgraphs = common.associateWithNotNull { id ->
            // By definition of `common` above, id \in common <=> id \in starts && id \in ends
            val start = starts[id]!!
            val endPoints = ends[id]!!
            val endBlocks = endPoints.map { it.block }

            val endPoint = endPoints.singleOrNull() ?: run {
                // Sometimes we can have multiple end blocks that immediately jump to a common exit block
                endPoints.forEach {
                    if (!g.blockCmdsForwardFrom(it).all {
                            it.cmd is TACCmd.Simple.AnnotationCmd || it.cmd is TACCmd.Simple.JumpCmd
                        }) {
                        return@associateWithNotNull null
                    }
                }
                endPoints.flatMapToSet { g.succ(it.block) }
                    .singleOrNull()
                    ?.takeIf { g.pred(it).all { endBlocks.contains(it) } }
                    ?.let { CmdPointer(it, 0) }
            } ?: return@associateWithNotNull null


            val containedBlocks = setOf(start.block) + endBlocks + g.blockIds.filter {
                dom.dominates(start.block, it) && revdom.dominates(endPoint.block, it)
            }
            val reachableFromStart = (reach[start.block] ?: return@associateWithNotNull null).removeAll(reach[endPoint.block].orEmpty())
            Triple(start, endPoint, containedBlocks.minus(start.block).minus(endPoint.block)).takeIf {
                containedBlocks.containsAll(reachableFromStart)
            }
        }

        // Ordered by inclusion, and if disjoint then ordered by start
        val toDelete = subgraphs.values.mapNotNull { entry ->
            checkSubgraph(g, entry.first, entry.second, entry.third)
        }.sortedWith { d1, d2 ->
            if (d1.interiorBlocks.containsAll(d2.interiorBlocks)) {
                -1
            } else if (d2.interiorBlocks.containsAll(d1.interiorBlocks)) {
                1
            } else {
                d1.start.compareTo(d2.start)
            }
        }

        val deleted = mutableSetOf<NBId>()
        val patching = ctp.toPatchingProgram()
        for ((start, end, body) in toDelete) {
            // Bail on this region if we've already (partially) deleted it
            if (deleted.containsAny(body)) {
                continue
            }
            val remove = body.toMutableSet()

            val dropStart = patching.splitBlockAfter(start)
            val dropEnd = patching.splitBlockBefore(end)

            deleted.add(start.block)
            remove.add(dropStart)

            if (dropEnd != end.block) {
                remove.add(end.block)
                deleted.add(end.block)
            }
            deleted.addAll(body)
            patching.reroutePredecessorsTo(dropStart, dropEnd)
            patching.removeBlocks(remove)
        }

        return patching.toCode(ctp)
    }

    /** A record of a region that should be removed */
    private data class ToDelete(val start: CmdPointer, val endPoints: CmdPointer, val interiorBlocks: Set<NBId>)

    private fun checkSubgraph(graph: TACCommandGraph, start: CmdPointer, end: CmdPointer, interiorBlocks: Set<NBId>): ToDelete? {
        val worklist = listOf(start, end) + interiorBlocks.map { CmdPointer(it, 0) }

        // Check the use is confined to the subgraph
        fun useOk(use: CmdPointer): Boolean =
            (use.block == start.block && start.pos <= use.pos)
                || end.block == use.block && use.pos <= end.pos
                || (use.block in interiorBlocks)

        // Check there are no side effects that we care about
        for (ptr in worklist) {
            for ((p, cmd) in graph.blockCmdsForwardFrom(ptr)) {
                when (cmd) {
                    is TACCmd.Simple.AssigningCmd.ByteStore,
                    is TACCmd.Simple.ByteLongCopy -> {
                        return null
                    }

                    is TACCmd.Simple.AssigningCmd ->  {
                        val uses = graph.cache.use.useSitesAfter(cmd.lhs, p)
                        if (!uses.all { useOk(it) }) {
                            return null
                        }
                    }

                    is TACCmd.Simple.SummaryCmd -> {
                        return null
                    }

                    else -> Unit
                }
            }

        }

        return ToDelete(start, end, interiorBlocks)
    }
}
