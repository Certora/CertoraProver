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

package analysis.dataflow

import analysis.CmdPointer
import analysis.TACCommandGraph
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.AnalysisCache
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import kotlin.collections.removeLast

class OnDemandUseAnalysis private constructor(private val graph: TACCommandGraph) : IUseAnalysis {
    companion object : AnalysisCache.Key<TACCommandGraph, OnDemandUseAnalysis> {
        override fun createCached(graph: TACCommandGraph) = OnDemandUseAnalysis(graph)
    }

    // Accessing these early prevents some problematic lazy initialization recursion; see Lazy.kt
    private val lva = graph.cache.lva
    private val variableLookup = graph.cache[VariableLookupComputation]

    override fun useSitesAfter(v: TACSymbol.Var, pointer: CmdPointer): Set<CmdPointer> {
        val useSites = mutableSetOf<CmdPointer>()
        for (b in graph.iterateBlock(pointer)) {
            if(b.cmd.usesVar(v)) {
                useSites.add(b.ptr)
            }
            if(b.cmd is TACCmd.Simple.AssigningCmd && b.cmd.lhs == v) {
                return useSites
            }
        }
        val visited = mutableSetOf<NBId>()
        for(succ in graph.succ(pointer.block)) {
            transit(v, succ, visited, useSites, 0)
        }
        return useSites
    }

    private tailrec fun transitHeap(v: TACSymbol.Var, visited: MutableSet<NBId>, useSites: MutableSet<CmdPointer>, worklist: MutableList<NBId>) {
        if(worklist.isEmpty()) {
            return
        }
        val block = worklist.removeLast()
        val nxt = transitInternal(v, block, visited, useSites)
        worklist.addAll(nxt.orEmpty())
        return transitHeap(v, visited, useSites, worklist)
    }

    private fun transitInternal(
        v: TACSymbol.Var,
        blockIdentifier: NBId,
        visited: MutableSet<NBId>,
        useSites: MutableSet<CmdPointer>,
    ) : Set<NBId>? {
        if(blockIdentifier in visited) {
            return null
        }
        visited.add(blockIdentifier)
        val cmds = graph.elab(blockIdentifier).commands
        if(!lva.isLiveBefore(cmds.first().ptr, v)) {
            return null
        }
        if(variableLookup[blockIdentifier]!!.contains(v)) {
            for(c in cmds) {
                if(c.cmd.usesVar(v)) {
                    useSites.add(c.ptr)
                }
                if(c.cmd is TACCmd.Simple.AssigningCmd && c.cmd.lhs == v) {
                    return null
                }
            }
        }
        return graph.succ(blockIdentifier)
    }

    private fun transit(v: TACSymbol.Var, blockIdentifier: NBId, visited: MutableSet<NBId>, useSites: MutableSet<CmdPointer>, depth: Int) {
        if(depth > 100) {
            return transitHeap(v, visited, useSites, mutableListOf(blockIdentifier))
        }
        val next = transitInternal(v, blockIdentifier, visited, useSites) ?: return
        for(s in next) {
            transit(v, s, visited, useSites, depth + 1)
        }
    }
}

/**
 * A use analysis that only looks at the block where the variable is defined.
 * The caller is responsible for checking other blocks.
 */
class SameBlockUseAnalysis(private val graph: TACCommandGraph) : IUseAnalysis {
    override fun useSitesAfter(v: TACSymbol.Var, pointer: CmdPointer): Set<CmdPointer> {
        val useSites = mutableSetOf<CmdPointer>()
        for (b in graph.iterateBlock(pointer)) {
            if (b.cmd.usesVar(v)) {
                useSites.add(b.ptr)
            }
            if (b.cmd is TACCmd.Simple.AssigningCmd && b.cmd.lhs == v) {
                return useSites
            }
        }
        return useSites
    }
}

/**
 * A use analysis that for a variable and a pointer finds a single use of it,
 * and an empty set if there are more than 1 or 0 uses.
 * It does so by first finding a single potential block where this single use exists,
 * and then making sure there is only one use within the block.
 * It relies on the code being loop-free and the topological sorting of the graph provided.
 */
class SingleUseAnalysis(private val graph: TACCommandGraph, private val topoSort: List<NBId>) : IUseAnalysis {
    private val blockToVars = graph.blockToVars
    private val blockToTopoSort = topoSort.mapIndexed { idx, b -> b to idx }.toMap()
    private val sameBlockUse = SameBlockUseAnalysis(graph)
    private val varToLastBlock: Map<TACSymbol.Var, Int> = mutableMapOf<TACSymbol.Var, Int>().also { vToB ->
        topoSort.forEachIndexed { blockPos, block ->
            blockToVars[block]?.forEach { v ->
                vToB[v] = blockPos
            }
        }
    }

    override fun useSitesAfter(v: TACSymbol.Var, pointer: CmdPointer): Set<CmdPointer> {
        var foundBlock: NBId? = null
        val lastBlockPos = varToLastBlock[v]
        if (lastBlockPos != null) {
            val firstBlockPos = blockToTopoSort[pointer.block] ?: error("pointer's block not found in topoSort")
            for (i in firstBlockPos..lastBlockPos) {
                val block = topoSort[i]
                if (v in blockToVars[block]!!) {
                    if (foundBlock != null) {
                        // Found more than one use
                        return emptySet()
                    }
                    foundBlock = block
                }
            }
        }

        if (foundBlock == null) {
            // Didn't find a use
            return emptySet()
        }

        val usesInBlock = if (pointer.block == foundBlock) {
            sameBlockUse.useSitesAfter(v, pointer)
        } else {
            sameBlockUse.useSitesAfter(v, CmdPointer(foundBlock, 0))
        }

        return usesInBlock.takeIf { it.size == 1 } ?: emptySet()
    }
}


/**
 * Returns all the use sites of a variable after a point, but will also return them if this variable was reassigned
 * on the path.
 * It is generally cheaper than normal use analysis, especially if `reachability` is already in the cache.
 */
class OverApproxUseAnalysis(val code: CoreTACProgram) : IUseAnalysis {
    private val varUses = buildMultiMap {
        code.analysisCache.graph.commands.forEach { (ptr, cmd) ->
            cmd.getFreeVarsOfRhs().forEach {
                add(it, ptr)
            }
        }
    }
    private val reachability = code.analysisCache.reachability

    override fun useSitesAfter(v: TACSymbol.Var, pointer: CmdPointer) =
        varUses[v]?.filterToSet {
            if (it.block == pointer.block) {
                it.pos > pointer.pos
            } else {
                it.block in reachability[pointer.block].orEmpty()
            }
        }.orEmpty()

    fun useSitesAtOrAfter(v: TACSymbol.Var, b: NBId) =
        varUses[v]?.filterToSet {
            it.block in reachability[b].orEmpty()
        }.orEmpty()
}
