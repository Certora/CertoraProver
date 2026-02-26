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

import datastructures.MultiMap
import datastructures.stdcollections.*
import utils.arrayDequeOf
import utils.consume
import kotlin.collections.listOf
import kotlin.collections.toMutableSet


/**
 * Get all reachable nodes starting with the nodes in [start], in the graph given by its [nexts] function - which for
 * each node should return the nodes it can reach in one step.
 */
inline fun <V> getReachable(start: Iterable<V>, nexts: (V) -> Iterable<V>?): Set<V> {
    val result = start.toMutableSet()
    val queue = arrayDequeOf(result)
    queue.consume { v ->
        nexts(v)
            ?.filter { result.add(it) }
            ?.forEach { queue += it }
    }
    return result
}

// for some reason method overloading doesn't work here so we need the 1 suffix
inline fun <V> getReachable1(start: V, nexts: (V) -> Iterable<V>?): Set<V> =
    getReachable(listOf(start), nexts)

fun <V> getReachable(start: V, g: MultiMap<V, V>): Set<V> =
    getReachable(listOf(start), g::get)

fun <V> getReachable(start: Iterable<V>, g: MultiMap<V, V>): Set<V> =
    getReachable(start, g::get)


/**
 * Returns the set of vertices and the succ relation when restricting [g] to the vertices satisfying [cond] and
 * still reachable from [roots].
 */
fun <V> reachableSubgraph(g: MultiMap<V, V>, roots: Set<V>, cond: (V) -> Boolean): Pair<Set<V>, MultiMap<V, V>> {
    // this duplicates the `getReachable` code for efficiency reasons.
    val vertices = roots.filterTo(mutableSetOf(), cond)
    val queue = arrayDequeOf(vertices)
    val newG = mutableMapOf<V, Set<V>>()
    queue.consume { v ->
        g[v]?.filter(cond)?.let { succs ->
            succs
                .filter { vertices.add(it) }
                .forEach { queue += it }
            newG[v] = succs.toSet()
        }
    }
    return vertices to newG
}

/**
 * unit returning versions of getReachable to make clear when we are using it only for side effects of [nexts]
 */
inline fun <V> traverseBFS(start: Iterable<V>, nexts: (V) -> Iterable<V>?): Unit {
    getReachable(start, nexts)
}

inline fun <V> traverseBFS1(start: V, nexts: (V) -> Iterable<V>?): Unit {
    traverseBFS(listOf(start), nexts)
}

/**
 * Compacts a graph to only include edges between target nodes.
 * Intermediate (non-target) nodes are collapsed, creating direct edges between target nodes.
 *
 * For each target node, finds all other target nodes reachable from it through paths
 * that may traverse non-target intermediate nodes.
 *
 *
 * @param targetNodes Nodes to keep in the compacted graph
 * @param nexts Function returning successors for each node
 * @return MultiMap representing the compacted graph's adjacency list
 */
inline fun <V> compactGraph(targetNodes: Set<V>, crossinline nexts: (V) -> Iterable<V>?): MultiMap<V, V> {
    val result = mutableMapOf<V, Set<V>>()

    for (startNode in targetNodes) {
        // Get immediate successors of this target node
        val immediateSuccessors = nexts(startNode) ?: emptyList()

        // Find all target nodes reachable through non-target intermediate nodes
        val reachableTargets = getReachable(immediateSuccessors) { node ->
            if (node in targetNodes) {
                // Reached a target node - include it but don't traverse further
                null
            } else {
                // Non-target node - traverse through it
                nexts(node)
            }
        }

        // Store all reachable target nodes as successors
        val targets = reachableTargets.filter { it in targetNodes }.toSet()
        if (targets.isNotEmpty()) {
            result[startNode] = targets
        }
    }

    return result
}
