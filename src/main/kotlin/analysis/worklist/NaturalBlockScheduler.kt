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

package analysis.worklist

import algorithms.SimpleDominanceAnalysis
import algorithms.mapTransitiveClosure
import analysis.GenericTACCommandGraph
import analysis.HasDominanceAnalysis
import analysis.getNaturalLoopsGeneric
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.AnalysisCache

//
// Work scheduler based on part of jtoman's dissertation:
// https://homes.cs.washington.edu/~djg/theses/toman_dissertation.pdf#page=189&zoom=100,96,713
//
// However, we do this a little differently from a straightforward implementation of that scheduling algorithm.
// We compute everything in terms of predecessors, rather than successors.  This avoids having to reverse
// the graph to compute dependencies, which are what we're interested in in the end.  For each of a block's predecessors,
// we add a dependency.  If the predecessor is a loop header of a loop containing this block, we add a special "loop header"
// dependency, which we will skip when making scheduling decisions, but which continues the dependency chain upward past
// the loop.  Any dependency from outside of a loop, into the body of the loop, gets replaced with a dependency on the
// loop header.  Finally, we precompute the transitive closures of the nodes in the dependency graph.
//
class NaturalBlockScheduler private constructor(graph: GenericTACCommandGraph<*,*,*>, dom: SimpleDominanceAnalysis<NBId>) : IWorklistScheduler<NBId> {
    companion object {
        fun <G> makeKey()
            where
            G : HasDominanceAnalysis,
            G : GenericTACCommandGraph<*, *, *> = object : AnalysisCache.Key<G, NaturalBlockScheduler> {
            override fun createCached(graph: G): NaturalBlockScheduler = NaturalBlockScheduler(graph, graph.domination)
        }
    }


    private data class DependencyNode(val block: NBId, val isLoopHeaderDependency: Boolean) : Comparable<DependencyNode> {
        override fun compareTo(other: DependencyNode): Int {
            val blockDiff = this.block.compareTo(other.block)
            return when {
                blockDiff != 0 -> blockDiff
                this.isLoopHeaderDependency && !other.isLoopHeaderDependency -> -1
                !this.isLoopHeaderDependency && other.isLoopHeaderDependency -> 1
                else -> 0
            }
        }
    }

    private val dependencies: Map<NBId, TreapSet<NBId>>
    init {
        val l = getNaturalLoopsGeneric(graph, dom)

        // L relation (pg 185)
        val loopToBody = l.flatMap {loop ->
            loop.body.map {
                loop.head to it
            }
        }.groupBy({ it.first }, {it.second}).mapValues { it.value.toSet() }

        // H relation (pg 185)
        val nodeToLoops = l.flatMap { ll ->
            ll.body.map {
                it to ll.head
            }
        }.groupBy({it.first}, {it.second}).mapValues { it.value.toSet() }

        val allNodesSinksFirst = (graph.sinkBlocks.asSequence() + graph.blocks.asSequence()).map { it.id }.toSet()

        fun addDependencies(deps: MutableSet<DependencyNode>, from: NBId, to: NBId) {
            // if this is a dependency on a body of a loop (and not from that same loop), we short-circuit to the head
            // of the loop (which will depend on the rest of the loop).
            val fromLoops = nodeToLoops[from].orEmpty()
            val toLoops = nodeToLoops[to].orEmpty()
            var foundLoops = false
            toLoops.forEach { loop ->
                if (loop !in fromLoops) {
                    deps.add(DependencyNode(loop, isLoopHeaderDependency = false))
                    foundLoops = true
                }
            }
            if (!foundLoops) {
                deps.add(DependencyNode(to, isLoopHeaderDependency = from in loopToBody[to].orEmpty()))
            }
        }

        // Generate the dependency graph
        val dependencyNodes = allNodesSinksFirst.map { DependencyNode(it, false) }.associateWith { node ->
            treapSetBuilderOf<DependencyNode>().also { deps ->
                if (node.isLoopHeaderDependency) {
                    val body = loopToBody[node.block].orEmpty()
                    graph.pred(node.block).forEach { pred ->
                        if (pred !in body) {
                            addDependencies(deps, from = node.block, to = pred)
                        }
                    }
                } else {
                    graph.pred(node.block).forEach { pred ->
                        addDependencies(deps, from = node.block, to = pred)
                    }
                }
            }.build()
        }

        // From the dependency graph, compute the transitive closure of non-loop-header dependencies.
        dependencies = mapTransitiveClosure(dependencyNodes) { deps ->
            deps.mapNotNullToTreapSet { dep ->
                if (dep.isLoopHeaderDependency) { null } else { dep.block }
            }
        }.mapNotNull { (k, v) ->
            if (k.isLoopHeaderDependency) { null } else { k.block to v }
        }.toMap()
    }

    override fun shouldSchedule(v: NBId, work: () -> Set<NBId>) = !work().containsAny(dependencies[v].orEmpty())
}
