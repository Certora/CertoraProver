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

import datastructures.stdcollections.*


inline fun <T, R> computeCrossProduct(l1: List<T>, l2: List<T>, joiner: (T, T) -> R): List<R> {
    val m = mutableListOf<R>()
    l1.forEach { e1 ->
        l2.forEach { e2 ->
            m += joiner(e1, e2)
        }
    }
    return m
}

/**
 * Generates all pairs of elements in the list [l], where the ordering of the pairs doesn't matter,
 * that is `(l[1], l[2])` is considered equivalent to `(l[2], l[1])`
 */
inline fun <T, R> computeCommutativeCrossProduct(l: List<T>, skipSelfPairing: Boolean = false, crossinline joiner: (T, T) -> R) : Sequence<R> {
    return sequence<R> {
        var i = 0
        while(i < l.size) {
            val curr = l[i]
            var j = if(skipSelfPairing) {
                i + 1
            } else {
                i
            }
            while(j < l.size) {
                yield(joiner(curr, l[j]))
                j++
            }
            i++
        }
    }
}

/**
 * Best explained by an example. Given [[a, b], [1], [2, 3]], will generate:
 *    [[a, 1, 2], [a, 1, 3], [b, 1, 2], [b, 1, 3]]
 */
fun <T> cartesianProduct(sets: Iterable<Iterable<T>>): List<List<T>> =
    sets.fold(listOf(listOf())) { acc, set ->
        acc.flatMap { list -> set.map { element -> list + element } }
    }

fun <T> cartesianProduct(vararg sets : Iterable<T>): List<List<T>> =
    cartesianProduct(sets.toList())

/**
 * Returns all a list of all "subsets" of [universe].
 *
 * the subsets are not really sets, because if [universe] contains duplicates, then these may will also have duplicates.
 */
fun <T> powerSetOf(universe : List<T>) : List<List<T>> {
    if (universe.isEmpty()) {
        return listOf(emptyList())
    }
    val shorts = powerSetOf(universe.subList(1, universe.size))
    return shorts + shorts.map { it + universe[0] }
}


/**
 * A trivial implementation, which returns all sets of sets from [subs] whose union is [universe] and
 * they are minimal, in the sense that any subset will not cover [universe]. Each such minimal union is given by
 * a list of indices to [subs].
 *
 * Note that [subs] may have repetitions, and the same set appearing twice will be considered separately.
 */
fun <T> minimalCoveringSets(universe : Set<T>, subs : List<Set<T>>): List<List<Int>> {

    fun covers(s : List<Set<T>>) =
        s.flatten().toSet() == universe

    fun isMinimal(s : List<Set<T>>) =
        s.indices.all { !covers(s.filterIndexed { i, _ -> i != it }) }

    return powerSetOf(List(subs.size) { it })
        .filter { subset ->
            val setList = subset.map { subs[it] }
            covers(setList) && isMinimal(setList)
        }
}

