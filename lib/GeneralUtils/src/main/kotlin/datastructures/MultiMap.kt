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

package datastructures

import com.certora.collect.*
import datastructures.stdcollections.*
import utils.*

/**
 * Map that maps each key to a set of values.
 *
 * Another name for this could be (and once was) `HashRelation`.
 */

typealias MultiMap<K, V> = Map<K, Set<V>>

val <K, V> MultiMap<K, V>.pairs get() =
    entries.flatMapToSet { (l, rs) -> rs.map { l to it } }

val <K, V> MultiMap<K, V>.pairsSeq get() = sequence {
    forEachEntryInline { (k, vs) ->
        for (v in vs) {
            yield(k to v)
        }
    }
}

val <K, V> MultiMap<K, V>.allValues get() =
    values.asSequence().flatten()

fun <K, V> MultiMap<K, V>.mapAllValues(f : (K, V) -> V)  =
    mapValues { (k, set) ->
        set.mapToSet { f(k, it) }
    }


/** returns an emptySet instead of null in the case where the key is not in the map */
fun <K, V> MultiMap<K, V>.getImage(k : K) = this[k].orEmpty()


/**
 * Another name for this could be (and once was) `MutableHashRelation`.
 */
typealias MutableMultiMap<K, V> = MutableMap<K, MutableSet<V>>

fun <K, V> mutableMultiMapOf(): MutableMultiMap<K, V> = mutableMapOf()

/** Adds [v] to the set of values associated with key [k]. Returns true if the element has been added. */
fun <K, V> MutableMultiMap<K, V>.add(k: K, v: V) = computeIfAbsent(k) { mutableSetOf() }.add(v)

/** Adds all elements from [vs] to the set of values associated with key [k] */
fun <K, V> MutableMultiMap<K, V>.addAll(k: K, vs: Collection<V>) =
    computeIfAbsent(k) { mutableSetOf() }.addAll(vs)

/** Removes all entries from the map where the predicate [pred] is true */
fun <K, V> MutableMultiMap<K, V>.removeIf(pred: (K, V) -> Boolean) {
    for (k in keys) {
        this[k]?.removeIf { v -> pred(k, v) }
    }
    entries.removeIf { (_, vs) -> vs.isEmpty() }
}

/** Delete a value [v] from the image of a key [k]. Returns whether this pair was part of this map. */
fun <K, V> MutableMultiMap<K, V>.delete(k: K, v: V): Boolean {
    this[k]?.let { set ->
        if (set.remove(v)) {
            if (set.isEmpty()) {
                this.remove(k)
            }
            return true
        } else {
            return false
        }
    }
    return false
}

fun <K, V> MultiMap<K, V>.toMutableMultiMap() =
    mutableMapOf(
        *this.map { (k, set) -> k to set.toMutableSet() }.toTypedArray()
    )

fun <K, V> reverseMap(original: Map<V, K>): MultiMap<K, V> =
    buildMultiMap {
        original.forEachEntry { (v, k) ->
            add(k, v)
        }
    }

fun<K, V> reverseToMutable(g: MultiMap<K, V>) =
    mutableMultiMapOf<V, K>().apply {
        g.pairs.forEach { (k, v) ->
            add(v, k)
        }
    }

fun<K, V> reverse(g: MultiMap<K, V>): Map<V, Set<K>> = reverseToMutable(g)

fun <K, V> buildMultiMap(builderAction: MutableMultiMap<K, V>.() -> Unit): MultiMap<K, V> =
    mutableMultiMapOf<K, V>().apply(builderAction)


/**
 * An undirected graph.
 *
 * Note that this does not support removal of edges at the moment, it should be easy to add if needed.
 */
class UndirectedGraph<V> {
    private val backing = mutableMultiMapOf<V, V>()

    /** read-only view on the underlying map */
    val backingMap : MultiMap<V, V>
        get() = backing

    fun add(v1: V, v2: V): Boolean {
        val added1 = backing.add(v1, v2)
        val added2 = backing.add(v2, v1)
        return added1 || added2
    }

    operator fun get(v: V): Set<V> =
        backing[v] ?: emptySet()

    val keys get() = backing.keys
}

/**
 * A directed graph with labelled edges.
 *
 * Note that this does not support removal of edges at the moment, it should be easy to add if needed.
 */
class EdgeLabeledGraph<V, L> {
    private val backing = mutableMultiMapOf<V, Pair<L, V>>()

    fun add(v1: V, v2: V, label: L) {
        backing.add(v1, label to v2)
    }

    operator fun get(v: V): Set<Pair<L, V>> =
        backing[v] ?: emptySet()

    val keys get() = backing.keys
}

typealias TreapMultiMap<K, V> = TreapMap<K, TreapSet<V>>

fun <@Treapable K, @Treapable V> TreapMultiMap<K, V>.add(k : K, v : V) =
    put(k, get(k)?.add(v) ?: treapSetOf(v))

fun <@Treapable K, @Treapable V> TreapMultiMap<K, V>.delete(k : K, v : V) =
    get(k)?.remove(v).let {
        if (it.isNullOrEmpty()) {
            remove(k)
        } else {
            put(k, it)
        }
    }


infix fun <K, V> TreapMultiMap<K, V>.treapMultiMapUnion(map2 : TreapMultiMap<K, V>) =
    this.union(map2) { _, set1, set2 ->
        set1.union(set2)
    }

