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

import com.certora.collect.*
import datastructures.stdcollections.*

data class TopologicalOrderException(val msg: String, val cycleIn: String) : Exception(msg)

/**
 * A tool for simulating the run of a simple recursive function, but without recursion. It imitates running f([start]),
 * where:
 *
 * ```
 * fun f(n : T) {
 *    if (!pre(n))
 *       return
 *    for (t in nexts(n))
 *       f(t)
 *    post(n)
 * }
 * ```
 *
 * Implementation-wise, this function holds a stack of pairs: a node, and its still unprocessed children. Only when all
 * of its children are processed, does a node leave the stack. This gives us the above DFS like behavior.
 */
inline fun <T> deRecurse(
    start: T,
    pre: (T) -> Boolean,
    post: (T) -> Unit,
    nexts: (T) -> Iterator<T>?
) {
    if (!pre(start)) {
        return
    }
    val stack = ArrayDeque<Pair<T, Iterator<T>?>>()
    stack.addLast(start to nexts(start))
    while (stack.isNotEmpty()) {
        val (node, childrenToProcess) = stack.last()
        if (childrenToProcess?.hasNext() == true) {
            val child = childrenToProcess.next()
            if (pre(child)) {
                stack.addLast(child to nexts(child))
            }
        } else {
            stack.removeLast()
            post(node)
        }
    }
}


/**
 * Returns the nodes of a DAG in topological order, sinks first. Throws [TopologicalOrderException] if there is a cycle.
 * [nodes] must be a superset of the sources of the graph.
 *
 * Implementation is according to [https://en.wikipedia.org/wiki/Topological_sorting#Depth-first_search], except
 * [deRecurse] is used to make it serial.
 */
inline fun <@Treapable T> topologicalOrder(
    nodes: Collection<T>,
    nexts: (T) -> Collection<T>?,
    ignoreSelfLoops: Boolean = false
): List<T> {
    val seen = mutableSetOf<T>()
    val currentPath = mutableSetOf<T>() // to check for cycles
    val result = mutableListOf<T>()

    for (node in nodes) {
        deRecurse(
            pre = { n ->
                if (n in seen) {
                    false
                } else if (!currentPath.add(n)) {
                    throw TopologicalOrderException("Found a cycle in our graph at node \"$n\"", n.toString())
                } else {
                    true
                }
            },
            nexts = { n ->
                nexts(n)?.let { nxts ->
                    if (!ignoreSelfLoops) {
                        nxts.iterator()
                    } else {
                        nxts.asSequence().filter { it != n }.iterator()
                    }
                }
            },

            post = { n ->
                seen += n
                currentPath -= n
                result += n
            },
            start = node
        )
    }
    return result
}

fun <@Treapable T> topologicalOrder(m: Map<T, Set<T>>, ignoreSelfLoops: Boolean = false) =
    topologicalOrder(m.keys, m::get, ignoreSelfLoops)

fun <@Treapable T> topologicalOrder(root : T, m : Map<T, Set<T>>, ignoreSelfLoops: Boolean = false) =
    topologicalOrder(listOf(root), m::get, ignoreSelfLoops)

/** returns null if there is a loop */
fun <@Treapable T> topologicalOrderOrNull(m: Map<T, Set<T>>, ignoreSelfLoops: Boolean = false) =
    try {
        topologicalOrder(m, ignoreSelfLoops)
    } catch (_: TopologicalOrderException) {
        null
    }

/**
 * Computes the length of the longest path in the given graph.
 * Note that the longest path itself should be easy to compute from [maxDistanceFromSink], in case we ever need it.
 *
 * @param topOrder Nodes in topological order; sinks first (as provided by [topologicalOrder]).
 * @param nexts the graph, given as a successor relation
 */
private class LongestPath<T>(val topOrder: List<T>, val nexts: Map<T, Collection<T>>) {
    private val maxDistanceFromSink = mutableMapOf<T, Int>()

    private var computed = false

    /**
     * The length of the longest path in the graph given by [nexts]
     * (implementation note: this would always be `maxDistance[topOrder.last()]`, -if- there was only one root)
     */
    val maxDistance: Int get() {
        if (topOrder.isEmpty()) {
            return 0
        }
        computeMaxDistances()
        return maxDistanceFromSink.values.max()
    }

    private fun computeMaxDistances() {
        if (computed) { return }
        for (node in topOrder) {
            val successors = nexts[node]
            if (successors.isNullOrEmpty()) {
                // node is a sink
                maxDistanceFromSink[node] = 0
            } else {
                maxDistanceFromSink[node] =
                    successors.maxOf { maxDistanceFromSink[it]!! } + 1
            }
        }
        computed = true
    }
}

fun <T> longestPathLength(topOrder: List<T>, nexts: Map<T, Collection<T>>): Int =
    LongestPath(topOrder, nexts).maxDistance


/**
 * When reversed, it's pretty useful for fixed point calculations, as most of the time most of the predecessors of a
 * vertex are before him in the order.
 */
fun <V> postOrder(start: Collection<V>, nexts: (V) -> Collection<V>?): List<V> {
    val list = mutableListOf<V>()
    val visited = kotlin.collections.mutableSetOf<V>()
    for (s in start) {
        deRecurse(
            start = s,
            pre = { visited.add(it) },
            nexts = { nexts(it)?.iterator() },
            post = { list += it },
        )
    }
    return list
}

fun <V> postOrder(start : Collection<V>, g : Map<V, Set<V>>) = postOrder(start, g::get)


/**
 * runs a DFS preorder like traversal of the graph. The traversal may go through the same vertex twice. It will stop when
 * reaching a loop, or when [nexts] returns null or an empty collection. Here, [nexts] is assumed to do the action of
 * the traversal.
 *
 * Useful when coming from two different sides to a vertex means two different things and we would like to traverse it
 * again, but we don't want to return to the same vertex from itself.
 *
 * In other words, A->B,C  B->C, B->D will traverse D twice (and possibly cause exponential traversal if the diamond
 * pattern continues).
 *
 * But A->B, B->C, C->A, will not traverse A twice.
 *
 * [id] should give an identity to vertices that will be used to decide if there is a loop, instead of using [V]'s
 * natural equality check.
 */
fun <V, @Treapable I> treeLikeDFS(start: V, id: (V) -> I , nexts: (V) -> Collection<V>?) {
    val currentPath = mutableSetOf<I>() // to check for cycles
    deRecurse(
        start = start,
        pre = { currentPath.add(id(it)) }, // if `add` returns false, we don't go deeper into recursion: it's a loop.
        nexts = { nexts(it)?.iterator() },
        post = { currentPath -= id(it) }
    )
}

fun <@Treapable V> treeLikeDFS(start: V, nexts: (V) -> Collection<V>?) =
    treeLikeDFS(start, { it }, nexts)

