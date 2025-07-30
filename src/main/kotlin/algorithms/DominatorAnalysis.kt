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

package algorithms

import analysis.*
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import instrumentation.transformers.TACDSA
import tac.NBId
import utils.*
import vc.data.AnalysisCache

//
// See "A Simple, Fast Dominance Algorithm," Cooper, Harvey, Kennedy (2001):
//     http://www.hipersoft.rice.edu/grads/publications/dom14.pdf
//
class SimpleDominanceAnalysis<@Treapable T : Any>(g: Map<T, Set<T>>) {
     companion object : AnalysisCache.Key<GenericTACCommandGraph<*,*,*>, SimpleDominanceAnalysis<NBId>> {
         override fun createCached(graph: GenericTACCommandGraph<*,*,*>) = SimpleDominanceAnalysis(graph.toBlockGraph())
    }


    private val nodeCount: Int
    private val nodes: Array<T>
    private val nodeToId: Map<T, Int>
    private val pred: Array<TreapSet.Builder<Int>>
    private val idoms: IntArray

    init {
        //
        // Postorder number nodes
        //
        val roots = findRoots(g)
        val queue = ArrayDeque<T>()
        val preordered = LinkedArrayHashSet<T>()
        queue.addAll(roots)
        preordered.addAll(roots)
        queue.consume {
            g[it]?.forEachElement {
                if (preordered.add(it)) {
                    queue.add(it)
                }
            }
        }

        nodeCount = preordered.size

        val nodesBuilder : Array<T?> = arrayOfNulls<Any>(nodeCount + 1).uncheckedAs()
        val nums = ArrayHashMap<T, Int>()
        preordered.forEachIndexed { i, node ->
            val n = nodeCount - i
            nodesBuilder[n] = node
            nums[node] = n
        }

        nodes = nodesBuilder.uncheckedAs()
        nodeToId = nums

        //
        // Find predecessor numbers
        //
        // We invent a new "analysis root" with all of the graph's actual roots as successors, so that our
        // dominance analysis only sees a single root.
        //
        val analysisRootNum = nodeCount + 1
        pred = Array(nodeCount + 1) { treapSetBuilderOf() }

        g.forEachEntry { (p, ss) ->
            val np = nums[p]!!
            ss.forEachElement { s ->
                pred[nums[s]!!].add(np)
            }
        }
        roots.forEachElement {
            pred[nums[it]!!].add(analysisRootNum)
        }

        //
        // Compute dominators
        //
        idoms = IntArray(analysisRootNum + 1)
        idoms[analysisRootNum] = analysisRootNum

        var changed: Boolean
        do {
            changed = false
            for (b in 1..nodeCount) {
                var newIDom = 0
                pred[b].forEachElement { p ->
                    if (idoms[p] != 0) {
                        if (newIDom == 0) {
                            newIDom = p
                        } else {
                            newIDom = intersect(p, newIDom)
                        }
                    }
                }
                if (idoms[b] != newIDom) {
                    idoms[b] = newIDom
                    changed = true
                }
            }
        } while (changed)
    }

    // See the paper referenced above.
    private fun intersect(b1: Int, b2: Int): Int {
        var finger1 = b1
        var finger2 = b2
        while (finger1 != finger2) {
            while (finger1 < finger2) {
                finger1 = idoms[finger1]
            }
            while (finger2 < finger1) {
                finger2 = idoms[finger2]
            }
        }
        return finger1
    }


    private val T.id get() = nodeToId[this] ?: error("$this does not have an entry in the domination graph")

    private val dominatesRelation by lazy {
        TreeTransitiveClosure((1..nodeCount).associateWith { idoms[it] })
    }

    /**
     * true if [x] dominates [y] (including if [x] == [y])
     */
    fun dominates(x: T, y: T) = dominatesRelation.isAncestor(x.id, y.id)

    @JvmName("dominates1")
    infix fun T.dominates(other: T) = dominates(this, other)

    /** returns the set of all nodes that dominate [v] (including [v]) */
    fun dominatorsOf(v: T): Set<T> =
        dominatesRelation.allAncestors(v.id)
            .mapNotNullToSet { nodes.getOrNull(it) }

    /** returns the set of all nodes dominated by [v] (including [v]) */
    fun dominatedOf(v: T): Set<T> =
        dominatesRelation.allDescendents(v.id).mapToSet { nodes[it] }

    val immediatelyDominatedBy: Map<T, T> by lazy {
        mutableMapOf<T, T>().also { map ->
            for (i in 1..nodeCount) {
                val dominated = nodes[i]
                val dominator = nodes.getOrNull(idoms[i])
                if (dominator != null) {
                    map[dominated] = dominator
                }
            }
        }
    }

    /** a topological order of the domination tree, root first */
    val topologicalOrder : List<T> by lazy {
        topologicalOrder(
            nodes = g.keys,
            nexts = { immediatelyDominatedBy[it]?.let(::listOf) ?: emptyList() }
        )
    }

    val dominanceFrontiers by lazy {
        calcFrontiers(includeSelfDominance = false)
    }

    /**
     * Efficient algorithm for calculating dominance frontiers. The dominance frontier of a node `n` is the nodes where
     * n's dominance stops, i.e., nodes that are not dominated by n, but have a predecessor that is.
     *
     * There is a simple algorithm following the above definition, but it is not very efficient. Instead, For each node
     * v, we look for nodes that v is in their dominance frontier. Those are nodes that dominate one of v's predecessors
     * but do not dominate v itself.
     *
     * If [includeSelfDominance] is on, then a node may be in its own frontier. This is useful in [TACDSA], but to
     * get the standard definition of domination frontiers, [includeSelfDominance] should be false.
     */
    fun calcFrontiers(includeSelfDominance : Boolean): MultiMap<T, T> {
        val indexVersion = mutableMultiMapOf<Int, Int>().also { df ->
            for (n in 1..nodeCount) {
                val preds = pred[n]
                if (preds.size <= 1) {
                    continue
                }
                val bIdom = idoms[n]
                for (p in preds) {
                    var runner = p
                    while (runner != bIdom && (includeSelfDominance || runner != n)) {
                        df.add(runner, n)
                        runner = idoms[runner]
                    }
                }
            }
        }
        return (1..nodeCount).associate { n ->
            nodes[n] to indexVersion[n]?.mapToSet { nodes[it] }.orEmpty()
        }
    }

}

/** true if [x] dominates [y] */
fun SimpleDominanceAnalysis<NBId>.dominates(x : CmdPointer, y : CmdPointer) =
    if (x.block == y.block) {
        x.pos <= y.pos
    } else {
        dominates(x.block, y.block)
    }

/** true if [x] dominates [y] and [x] != [y] */
fun SimpleDominanceAnalysis<NBId>.strictlyDominates(x : CmdPointer, y : CmdPointer) =
    (x != y) && dominates(x, y)

/** true if [x] dominates [y] */
fun SimpleDominanceAnalysis<NBId>.dominates(x : LTACCmd, y : LTACCmd) =
    dominates(x.ptr, y.ptr)

