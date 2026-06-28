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
 * Tag type for the lattice flavor of an [IntervalMap].
 *
 * The mode is a *type-level* parameter on [IntervalMap]: a [Union] map and an [Intersect] map
 * are different types and cannot be mixed in a single [IntervalMap.join] / [IntervalMap.lessOrEqual]
 * call.  The compiler enforces this via the `M : JoinMode` type parameter on [IntervalMap].
 */
sealed interface JoinMode {
    object Union : JoinMode
    object Intersect : JoinMode
}

/**
 * A disjoint (closed) interval map: [start, end] -> V with lattice operations.
 *
 * The type parameter [M] fixes the lattice flavor: use [JoinMode.Union] for a may-style
 * abstraction (the join keeps all intervals from both sides) or [JoinMode.Intersect] for a
 * must-style one (the join keeps only intervals where both sides agree).  Use the factory
 * functions [unionIntervalMap] / [intersectIntervalMap] to construct an empty map.
 **/
class IntervalMap<V, M : JoinMode> internal constructor(
    private val mode: M,
    private val map: TreapMap<Long, Pair<Long, V>> = treapMapOf() // key=start, value=(end, V)
) {

    private fun copy(map: TreapMap<Long, Pair<Long, V>>): IntervalMap<V, M> = IntervalMap(mode, map)

    // The `when` is exhaustive over `JoinMode` (a sealed interface), but the compiler
    // tracks `mode` as the type variable `M`, not as `JoinMode`, and refuses to treat the
    // branches as exhaustive without an `else`.  Up-casting widens the subject's static
    // type so the sealed-interface exhaustiveness check fires.
    // `other`'s mode is intentionally erased: only `this.mode` decides the lattice flavor,
    // and the bodies below only need `other.map`.  Callers are expected to pass an `IntervalMap`
    // with the same mode as `this`; the relaxed parameter type is what lets the factories in
    // PointerDomain (e.g. `newUnmaterializedStack`) hide the choice behind star projection.
    @Suppress("USELESS_CAST")
    fun join(other: IntervalMap<V, *>, merger: (V, V) -> V): IntervalMap<V, M> =
        when (mode as JoinMode) {
            is JoinMode.Union -> joinWithUnion(other, merger)
            is JoinMode.Intersect -> joinWithIntersect(other)
        }

    @Suppress("USELESS_CAST")
    fun lessOrEqual(other: IntervalMap<V, *>): Boolean =
        when (mode as JoinMode) {
            is JoinMode.Union -> lessOrEqualWithUnion(other)
            is JoinMode.Intersect -> lessOrEqualWithIntersect(other)
        }

    private fun joinWithIntersect(other: IntervalMap<V, *>): IntervalMap<V, M> {
        return copy(map.merge(other.map) { _, leftVal, rightVal ->
            // Keep entry only if: start, end, value are equal.
            if (leftVal == rightVal) {
                leftVal
            } else {
                null
            }
        })
    }

    private fun joinWithUnion(other: IntervalMap<V, *>, merger: (V, V) -> V): IntervalMap<V, M> {
        var res = this
        other.map.forEachEntry {
            val (end, v) = it.value
            res = res.insert(it.key, end, v, merger)
        }
        return res
    }


    /** Return true if `this` is a superset of `other` **/
    private fun lessOrEqualWithIntersect(other: IntervalMap<V, *>): Boolean {
        val entries = map.zip(other.map)
        for (entry in entries) {
            // All the entries from [other] must be in [this]
            val leftVal  = entry.value.first
            val rightVal = entry.value.second
            check(!(leftVal == null && rightVal == null)) { "cannot compare two null values" }
            if (rightVal != null && leftVal != rightVal) {
                return false
            }
        }
        return true
    }


    /** Return true if every interval in `this` is included in some interval in `other` and the values match **/
    private fun lessOrEqualWithUnion(other: IntervalMap<V, *>): Boolean {
        for ((leftStart, leftEndAndV) in map) {
            val leftEnd = leftEndAndV.first
            val leftVal = leftEndAndV.second
            val rightVal = other.contains(leftStart, leftEnd) ?: return false
            if (leftVal != rightVal) {
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
    private fun insert(start: Long, end: Long, value: V, overlapMode: InsertMode, merger: ((V,V) -> V)?): IntervalMap<V, M> {
        check(start <= end) { "insert expects start <= end" }
        check(overlapMode != InsertMode.MERGE || merger != null) { "merger function cannot be null" }

        if (map.isEmpty()) {
            return copy(map.put(start, end to value))
        }

        val toRemove = mutableListOf<Long>()
        var newStart = start
        var newEnd = end
        var newValue = value

        // Iterate over potentially overlapping intervals
        val firstKey = map.firstKey()
        check(firstKey != null)

        for ((s, endAndV) in map.retainAllKeys { it in firstKey..end }) {
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

        return copy(outMap)
    }

    /**
     *  Insert a disjoint interval `[start, end]` -> [value].
     *  Adjacent or overlapping intervals are merged using the [merger] function.
     */
    fun insert(start: Long, end: Long, value: V, merger: (V,V) -> V): IntervalMap<V, M> {
        return insert(start, end, value, InsertMode.MERGE, merger)
    }

    /**
     *  Insert a disjoint interval `[start, end]` -> [value].
     *  Overlapping intervals are removed.
     */
    fun insert(start: Long, end: Long, value: V): IntervalMap<V, M> {
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

    /**
     * Lookup any interval in the map that overlaps `[start, end]` and returns its value, or null
     * if no interval overlaps it.
     */
    fun overlap(start: Long, end: Long): V? {
        check(start <= end) { "overlap expects start <= end" }
        // Case 1: an interval `[k, e]` with `k <= start`.  Overlap iff `e >= start`.
        val floor = map.floorEntry(start)
        if (floor != null) {
            val (e, value) = floor.value
            if (e >= start) {
                return value
            }
        }
        // Case 2: an interval `[k, e]` with `k > start`.  Overlap iff `k <= end`.
        val ceiling = map.higherEntry(start)
        if (ceiling != null && ceiling.key <= end) {
            return ceiling.value.second
        }
        return null
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
    fun remove(start: Long, end:Long, mode: RemoveMode): IntervalMap<V, M> {
        check(start <= end) {"remove expects start <= end"}

        var outMap = map
        val toAdd = mutableListOf<Pair<Long, Pair<Long, V>>>()

        // The floor entry at `start` overlaps `[start, end]` only when its `e >= start`.
        // Otherwise (or if no floor entry exists), start from the first entry with key > start;
        // any such entry whose key is `<= end` overlaps the range.
        var entry = map.floorEntry(start)
        if (entry == null || entry.value.first < start) {
            entry = map.ceilingEntry(start)
        }
        while (entry != null && entry.key <= end) {
            val s = entry.key
            val (e, v) = entry.value
            outMap = outMap.remove(s)
            if (mode == RemoveMode.SPLIT) {
                if (start > s) {
                    toAdd.add(s to (start - 1 to v))
                }
                if (end < e) {
                    toAdd.add(end + 1 to (e to v))
                }
            }
            entry = outMap.ceilingEntry(s + 1)
        }
        toAdd.forEach {
            val s = it.first
            val (e, v) = it.second
            outMap = outMap.put(s, e to v)
        }
        return copy(outMap)
    }

    /** Iterate over all intervals as [FiniteInterval] -> value. */
    fun intervals(): Sequence<Pair<FiniteInterval, V>> =
        map.asSequence().map { (start, endAndV) ->
            val (end, v) = endAndV
            FiniteInterval(start, end) to v
        }

    /**
     * Invoke [action] for each interval whose start is in `[rangeStart, rangeEnd]`
     */
    fun forEachInRange(rangeStart: Long, rangeEnd: Long, action: (FiniteInterval, V) -> Unit) {
        var entry = map.ceilingEntry(rangeStart)
        while (entry != null && entry.key <= rangeEnd) {
            val (end, v) = entry.value
            action(FiniteInterval(entry.key, end), v)
            entry = map.higherEntry(entry.key)
        }
    }

    fun removeAll(pred: (FiniteInterval) -> Boolean): IntervalMap<V, M> {
        return copy(map.removeAll { (start, endAndV) ->
            val (end, _) = endAndV
            pred(FiniteInterval(start, end))
        })
    }

    fun size() = map.size

    override fun toString(): String {
        return "{" + intervals().joinToString(separator = ",") { (r, v) -> "$r -> $v" } + "}"
    }
 }

/** Empty [IntervalMap] with [JoinMode.Union] semantics. */
fun <V> unionIntervalMap(): IntervalMap<V, JoinMode.Union> = IntervalMap(JoinMode.Union)

/** Empty [IntervalMap] with [JoinMode.Intersect] semantics. */
fun <V> intersectIntervalMap(): IntervalMap<V, JoinMode.Intersect> = IntervalMap(JoinMode.Intersect)
