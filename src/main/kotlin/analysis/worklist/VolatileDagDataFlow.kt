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

import algorithms.topologicalOrder
import com.certora.collect.*
import datastructures.MultiMap
import datastructures.stdcollections.*
import utils.SimpleCounterMap

/**
 * Goes backwards in the DAG [g], calculating data for each vertex according to the data in its successors.
 * The data disappears as soon as its not needed anymore, so [calc] should have some side effect which
 * is actually what we want.
 */
fun <@Treapable V, D> volatileDagDataFlow(g: MultiMap<V, V>, calc: (V, List<D>) -> D) =
    volatileDagDataFlow(g.keys, g::get, calc)

/**
 * Iterates backwards over the DAG given by [g] and performs calculation [calc].
 * (For more details, see also doc for [volatileDagDataFlow] (right above).)
 * [nodes] are the starting points of the iteration; they must be a superset of the sources of graph [g] (like for the
 * [topologicalOrder] function that is used here).
 */
fun <@Treapable V, D> volatileDagDataFlow(nodes: Set<V>, g: (V) -> Set<V>?, calc: (V, List<D>) -> D) {
    val order = topologicalOrder(nodes, g) // sinks first

    // the number of predecessors each node has.
    val predCount = SimpleCounterMap<V>().apply {
        for (u in order) {
            // for each `u`, add 1 to the count of each of its successors.
            g(u)?.forEach(::plusOne)
        }
    }

    val data = mutableMapOf<V, D>()

    for (u in order) {
        val calculated = calc(u, g(u).orEmpty().mapNotNull { data[it] })
        data[u] = calculated
        g(u)?.forEach { v ->
            predCount.minusOne(v)
            if (predCount[v] == 0) {
                // if `u` was the last predecessor of `v` that was processed, then `v`'s data is not needed
                // anymore, and can be removed.
                data -= v
            }
        }
    }
}


/**
 * For each node, the sum using [folder] of the [value] of all its descendants (including itself)
 * [nodes] should be a super set of the source nodes of this dag.
 */
fun <@Treapable T, D> MultiMap<T, T>.dagRunningFold(value : (T) -> D, folder : (D, D) -> D, nodes : Set<T> = keys) =
    mutableMapOf<T, D>().also { result ->
        topologicalOrder(nodes, this::get).forEach { v ->
            result[v] = get(v).orEmpty().map { result[it]!! }.fold(value(v), folder)
        }
    }
