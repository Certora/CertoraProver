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

package sbf.domains

import com.certora.collect.*

/**
 * A disjoint (closed) interval map: [start, end] -> V with lattice operations.
 * In terms of lattice ordering, the more elements, the more precise.
 **/
class IntervalMap<V> (
    private val map: TreapMap<Long, Pair<Long, V>> = treapMapOf() // key=start, value=(end, V)
) {

    /** Return intersection of `this` and `other` **/
    fun join(other: IntervalMap<V>): IntervalMap<V> {
        return IntervalMap(map.merge(other.map) { _, leftVal, rightVal ->
            if (leftVal == rightVal) {
                leftVal
            } else {
                null
            }
        })
    }

    /** Return true if `this` is a superset of `other` **/
    fun lessOrEqual(other: IntervalMap<V>): Boolean {
        val entries = map.zip(other.map)
        for (entry in entries) {
            val leftVal = entry.value.first
            val rightVal = entry.value.second
            check(!(leftVal == null && rightVal == null)) { "cannot compare two null values" }
            if (rightVal != null && leftVal != rightVal) {
                return false
            }
        }
        return true
    }

    private enum class InsertMode {
        /** remove overlapping intervals */
        REMOVE,
        /** merge overlapping intervals */
        MERGE,
    }

    /**
     *  Insert a disjoint interval `[start, end]` -> [value].
     *  - if [overlapMode] == [InsertMode.MERGE] then adjacent or overlapping intervals are merged using the [merger] function.
     *  - if [overlapMode] == [InsertMode.REMOVE] then overlapping intervals are removed.
     */
    private fun insert(start: Long, end: Long, value: V, overlapMode: InsertMode, merger: ((V,V) -> V)?): IntervalMap<V> {
        check(start <= end) { "insert expects start <= end" }
        check(overlapMode != InsertMode.MERGE || merger != null) { "merger function cannot be null" }

        if (map.isEmpty()) {
            return IntervalMap(map.put(start, end to value))
        }

        val toRemove = mutableListOf<Long>()
        var newStart = start
        var newEnd = end
        var newValue = value

        // Iterate over potentially overlapping intervals
        val firstKey = map.firstKey()
        check(firstKey != null)

        for ((s, endAndV) in map.retainAllKeys { it >= firstKey && it <= end }) {
            val (e, v) = endAndV
            if (e < start) { // completely before
                continue
            }
            if (s > end) {   // completely after
                break
            }

            if (overlapMode == InsertMode.MERGE) {
                // Merge adjacent or overlapping intervals
                newStart = minOf(newStart, s)
                newEnd = maxOf(newEnd, e)
                newValue = merger!!(v, newValue)
            }
            toRemove.add(s)
        }

        var outMap = map

        // Remove merged intervals
        for (s in toRemove) {
            outMap = outMap.remove(s)
        }

        // Insert merged interval
        outMap = outMap.put(newStart, newEnd to newValue)

        return IntervalMap(outMap)
    }

    /**
     *  Insert a disjoint interval `[start, end]` -> [value].
     *  Adjacent or overlapping intervals are merged using the [merger] function.
     */
    fun insert(start: Long, end: Long, value: V, merger: (V,V) -> V): IntervalMap<V> {
        return insert(start, end, value, InsertMode.MERGE, merger)
    }

    /**
     *  Insert a disjoint interval `[start, end]` -> [value].
     *  Overlapping intervals are removed.
     */
    fun insert(start: Long, end: Long, value: V): IntervalMap<V> {
        return insert(start, end, value, InsertMode.REMOVE, merger = null)
    }

    /** Lookup the interval that contains [key] and returns its value, or null if no interval contains it. */
    fun get(key: Long): V? {
        val entry = map.floorEntry(key) ?: return null
        val (e, value) = entry.value
        return if (key <= e) {
            value
        } else {
            null
        }
    }

    /** Lookup the interval that fully contains `[start, end]` and returns its value, or null if no interval contains it. **/
    fun contains(start: Long, end: Long): V? {
        check (start <= end) {"get expects start <= end"}
        val entry = map.floorEntry(start) ?: return null
        val (e, value) = entry.value
        return if (start <= e && end <= e) {
            value
        } else {
            null
        }
    }

    enum class RemoveMode {
        /** remove the full interval */
        NO_SPLIT,
        /** split the interval */
        SPLIT,
    }

    /**
     * Remove all intervals `i` intersecting with `[start, end]`.
     * - If [mode] == [RemoveMode.NO_SPLIT] then it does not add any interval.
     * - If [mode] == [RemoveMode.SPLIT] then add the sub-intervals of `i` that do not overlap with `[start, end]`.
     */
    fun remove(start: Long, end:Long, mode: RemoveMode): IntervalMap<V> {
        check(start <= end) {"remove expects start <= end"}

        var entry = map.floorEntry(start)
        var outMap = map
        val toAdd = mutableListOf<Pair<Long, Pair<Long, V>>>()
        val i = FiniteInterval(start, end)
        while (entry != null) {
            val s = entry.key
            val (e, v) = entry.value
            val j = FiniteInterval(s, e)
            if (i.overlap(j)) {
                outMap = outMap.remove(entry.key)
                if (mode == RemoveMode.SPLIT) {
                    if (start > s) {
                        toAdd.add(s to (start - 1 to v))
                    }
                    if (end < e) {
                        toAdd.add(end+1 to (e to v))
                    }
                }
            } else {
                break
            }
            entry = outMap.ceilingEntry(s)
        }
        toAdd.forEach {
            val s = it.first
            val (e, v) = it.second
            outMap = outMap.put(s, e to v)
        }
        return IntervalMap(outMap)
    }

    /** Iterate over all intervals as [FiniteInterval] -> value. */
    fun intervals(): Sequence<Pair<FiniteInterval, V>> =
        map.asSequence().map { (start, endAndV) ->
            val (end, v) = endAndV
            FiniteInterval(start, end) to v
        }

    fun removeAll(pred: (FiniteInterval) -> Boolean): IntervalMap<V> {
        return IntervalMap(map.removeAll { (start, endAndV) ->
            val (end, _) = endAndV
            pred(FiniteInterval(start, end))
        })
    }

    fun size() = map.size

    override fun toString(): String {
        return "{" + intervals().joinToString(separator = ",") { (r, v) -> "$r -> $v" } + "}"
    }
 }
