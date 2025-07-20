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

package analysis.opt.intervals

import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import log.*
import org.jetbrains.annotations.TestOnly
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.green
import utils.Color.Companion.yellow

private val logger = Logger(LoggerTypes.INTERVALS_SIMPLIFIER)

/**
 * A fancy name for something pretty simple.
 *
 * The vertices (of type [V]) have values (of type [T]) attached to them. Each [Edge] holds a "transformation" on
 * the edge's vertices (can be more than 2 vertices, hence hyper-graph), that can change the values of these vertices.
 *
 * [State.fixedPoint] runs these edge transformations until reaching a fixed point.
 *
 * [defaultValue] gives the value of a vertex if it was never assigned before.
 * [normalize] is run before setting a new value, it takes the old and the new values of a vertex (after a
 * transformation), and returns the actual value to save.
 *
 * We can improve efficiency a bit by allowing edges to say if they surely didn't change a vertex.
 */
class HyperGraphFixedPointCalculator<V, T>(
    private val defaultValue: (V) -> T,
    /**
     * Activated before saving a value related to a vertex. It takes the vertex, the old value, and the new calculated
     * value. It returns the actual value to save.
     * There are a few use cases for this, but one is to avoid saving values that are too complex, and so [normalize]
     * would simplify them before actually saving them.
     */
    private val normalize: (V, T, T) -> T,
    private val maxFactor: Int = 4
) {
    private inner class Edge(
        val vertices: List<V>,
        val func: (List<T>) -> List<T>,
        val name: String
    ) {
        override fun toString() = name
    }

    private val vertexToEdges = mutableMultiMapOf<V, Edge>()
    private val allEdges = mutableSetOf<Edge>()

    fun addEdge(vertices: List<V>, func: (List<T>) -> List<T>, name: String) {
        val e = Edge(vertices, func, name)
        allEdges += e
        for (v in vertices) {
            vertexToEdges.add(v, e)
        }
    }

    fun addEdge(v1: V, v2: V, func: (T, T) -> List<T>, name: String) {
        addEdge(listOf(v1, v2), { l -> func(l[0], l[1]) }, name)
    }

    inner class State(private val vals: MutableMap<V, T> = mutableMapOf()) {
        fun get(v: V) = vals[v] ?: defaultValue(v)
        fun getOrNull(v: V) = vals[v]

        fun set(v: V, t: T) {
            vals[v] = normalize(v, get(v), t)
        }

        val vertices: Set<V> = vals.keys

        fun duplicate() = State(vals.toMutableMap())

        /**
         * [startWith] is the set of vertices we consider "changed" when we start, i.e., any edge that contains them
         * should be run. If this is null, then all edges should be run.
         */
        fun fixedPoint(startWith: Collection<V>? = null) {
            if (allEdges.isEmpty()) {
                return
            }
            val queue: MutableSet<Edge> = startWith
                ?.flatMapToSet { vertexToEdges[it].orEmpty() }
                ?: allEdges.toMutableSet()

            var count = 0
            val maxCount = allEdges.size * maxFactor
            while (queue.isNotEmpty()) {
                if (++count > maxCount) {
                    logger.warn {
                        "Factor of $maxFactor exceeded. Stopping fixed point computation"
                    }
                    break
                }
                val e = queue.first()
                val olds = e.vertices.map(::get)
                val news = e.func(olds)
                logger.trace {
                    "${e.green}\n" +
                        zip(e.vertices, olds, news).joinToString("\n") { (v, o, n) ->
                            "   ${v.yellow} : ${
                                "$o -> $n".letIf(o != n) {
                                    it.blue
                                }
                            }"
                        }
                }
                for ((v, old, new) in zip(e.vertices, olds, news)) {
                    set(v, new)
                    if (get(v) != old) {
                        queue += vertexToEdges[v].orEmpty()
                    }
                }
                queue -= e
            }
            logger.debug {
                "edgeCalculations = $count, edges = ${allEdges.size}, factor = ${count / allEdges.size}"
            }
        }

        @TestOnly
        fun edgesString() =
            allEdges.joinToString("\n") { e ->
                "$e : ${e.vertices}"
            }
    }

}
