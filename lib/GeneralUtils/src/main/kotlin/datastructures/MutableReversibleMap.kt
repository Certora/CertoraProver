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

import datastructures.stdcollections.toMutableMap

/**
 * A [MutableMap] which supports efficient preimage computation via [keysOf]
 */
class MutableReversibleMap<K, V> private constructor(
    private val map: MutableMap<K, V>,
    private val back: MutableMultiMap<V, K>
) : MutableMap<K, V> by map {

    constructor() : this(mutableMapOf(), mutableMultiMapOf())

    override fun clear() {
        map.clear()
        back.clear()
    }

    override fun remove(key: K): V? {
        val oldV = map.remove(key)
            ?: return null
        back.delete(oldV, key)
        return oldV
    }

    fun removeValue(v : V) {
        back.remove(v)?.forEach { k ->
            map -= k
        }
    }

    override fun putAll(from: Map<out K, V>) {
        from.forEach { (k, v) -> put(k, v) }
    }

    override fun put(key: K, value: V): V? {
        val oldV = map[key]
        if (oldV != null) {
            back.delete(oldV, key)
        }
        back.add(value, key)
        map[key] = value
        return oldV
    }

    override fun containsValue(value: V) = value in back

    fun keysOf(value: V) = back[value].orEmpty()

    operator fun set(key: K, value: V) = put(key, value)

    fun copy() = MutableReversibleMap(map.toMutableMap(), back.toMutableMultiMap())
}
