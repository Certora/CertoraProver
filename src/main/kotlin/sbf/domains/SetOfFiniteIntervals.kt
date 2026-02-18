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

import datastructures.stdcollections.*

/**
 * Simple class to represent set of closed intervals.
 * This class does not provide an API to do interval arithmetic.
 * Instead, the API focuses on union/intersection of intervals.
 *
 * Note that we don't use IntVal to avoid creating unnecessary BigInteger instances.
 */
data class FiniteInterval(val l: Long, val u: Long) {
    companion object {
        fun mkInterval(l: Long, size: Long) = FiniteInterval(l, l+size-1)
    }

    override fun toString(): String = "[$l,$u]"

    fun size(): ULong {
        check(u >= l) {"FiniteInterval is not supposed to wrap around"}
        return (u-l).toULong() + 1UL
    }

    fun add(x: Long) = FiniteInterval(l+x, u+x)

    // i1 and i2 are closed intervals
    fun lessThan(other: FiniteInterval): Boolean {
        val u1 = this.u
        val l2 = other.l
        return u1 < l2
    }

    // i1 and i2 are closed intervals
    fun overlap(other: FiniteInterval): Boolean {
        return !lessThan(other) && !other.lessThan(this)
    }

    fun includes(other: FiniteInterval): Boolean {
        return (other.l in l..u) && (other.u in l..u)
    }

    /** The length of the returned list is 0, 1, or 2 **/
    fun difference(other: FiniteInterval): List<FiniteInterval> {
        if (!overlap(other)) {
            return listOf(this)
        }

        if (this == other) {
            return listOf()
        }

        return if (other.l <= this.l) {
            listOf(FiniteInterval(other.u+1, this.u))
        } else if (other.u >= this.u) {
            listOf(FiniteInterval(this.l, other.l-1))
        } else {
            check(this.includes(other))
            listOf(FiniteInterval(this.l, other.l-1), FiniteInterval(other.u+1, this.u))
        }
    }

    fun union(other: FiniteInterval): FiniteInterval? {
       return if (overlap(other) ||
                  this.u+1 == other.l ||
                  other.u+1 == this.l) {
           val lower = kotlin.math.min(this.l, other.l)
           val upper = kotlin.math.max(this.u, other.u)
           FiniteInterval(lower, upper)
       } else {
           null
       }
    }

    fun intersection(other: FiniteInterval): FiniteInterval? {
        return if (!this.overlap(other)) {
            null
        } else {
            val lower = kotlin.math.max(this.l, other.l)
            val upper = kotlin.math.min(this.u, other.u)
            FiniteInterval(lower, upper)
        }
    }

}


data class SetOfFiniteIntervals(val intervals: List<FiniteInterval>) {
    /// Class invariants:
    //  1. for all i in {0,...,intervals.size-1}:: intervals[i].u < intervals[i+1].l
    //  2. There is no consecutive intervals.
    //     If two intervals i1 and i2 are consecutive (i.e., i1.u+1 == i2.l or i2.u+1 == i1.l) then they merged together.

    init {
        checkInvariants()
    }

    companion object {
        fun new() = SetOfFiniteIntervals(listOf())
    }

    private fun checkInvariants() {
        if (intervals.size > 1) {
            for ((i, cur) in intervals.withIndex()) {
                if (i < intervals.size - 1) {
                    val next = intervals[i+1]
                    check(cur.u < next.l) {"SetOfFiniteIntervals: invariant #1 on $this does not hold"}
                    check(cur.u+1 != next.l) {"SetOfFiniteIntervals: invariant #2 on $this does not hold"}
                }
            }
        }
    }

    override fun toString() = intervals.toString()

    fun size() = intervals.size

    fun intersection(other: SetOfFiniteIntervals): SetOfFiniteIntervals {
        val res = mutableListOf<FiniteInterval>()
        for (i in intervals) {
            for (j in other.intervals) {
                if (i.u < j.l) {
                    break // because intervals are sorted
                }
                val x = i.intersection(j)
                if (x != null) {
                    res.add(x)
                }
            }
        }

        return SetOfFiniteIntervals(res)
    }

    fun add(itv: FiniteInterval): SetOfFiniteIntervals {
        val out = ArrayList<FiniteInterval>()
        var alreadyInserted = false
        var x = itv
        for (i in intervals.indices) {
            val cur = intervals[i]
            if (x.u < cur.l) {
                out.add(x)
                out.addAll(intervals.subList(i, intervals.size))
                alreadyInserted = true
                break
            } else if (x.l > cur.u) {
                out.add(cur)
            } else if (x == cur) {
                // Note that x = intervals[i]. Thus, this will add x and the rest of intervals[i+1,...]
                out.addAll(intervals.subList(i, intervals.size))
                alreadyInserted = true
                break
            } else { // overlap cases
                if (x.includes(cur)) {
                    continue
                } else {
                    x = x.union(cur)!! // we know cannot be null because x and cur overlap
                }
            }
        }

        if (!alreadyInserted) {
            out.add(x)
        }

        // At this point intervals is sorted and without overlaps
        // Here, we merge all the consecutive intervals (if any).
        var i = 0
        while (i < out.size - 1) { // intervals.size can change at each iteration. that's okay.
            val cur  = out[i]
            val next = out[i+1]
            if (cur.u+1 == next.l) {
                out[i] = cur.union(next)!! // union won't return null
                out.removeAt(i+1)
            } else {
                i++
            }
        }

        return SetOfFiniteIntervals(out)
    }

    fun join(other: SetOfFiniteIntervals): SetOfFiniteIntervals =
        other.intervals.fold(this) { acc, i -> acc.add(i) }

    fun remove(itv: FiniteInterval): SetOfFiniteIntervals {
        val out = ArrayList<FiniteInterval>()

        for (cur in intervals) {
            // Case 1: No overlap - keep the interval as-is
            if (cur.u < itv.l || cur.l > itv.u) {
                out.add(cur)
            }
            // Case 2: itv completely covers cur - skip it (remove entirely)
            else if (itv.includes(cur)) {
                continue
            }
            // Case 3: Partial overlap - split or trim
            else {
                // cur partially overlaps with itv
                // We need to keep the parts of cur that don't overlap with itv

                // Left part: if cur starts before itv
                if (cur.l < itv.l) {
                    out.add(FiniteInterval(cur.l, minOf(cur.u, itv.l - 1)))
                }

                // Right part: if cur ends after itv
                if (cur.u > itv.u) {
                    out.add(FiniteInterval(maxOf(cur.l, itv.u + 1), cur.u))
                }
            }
        }

        return SetOfFiniteIntervals(out)
    }

    /**
     * Returns true if every interval in this set is included in some interval from [other].
     **/
    fun included(other: SetOfFiniteIntervals): Boolean {
        if (intervals.isEmpty()) {
            return true
        }
        if (other.intervals.isEmpty()) {
            return false
        }

        var j = 0
        for (i in intervals) {
            // Advance j to find a candidate interval in other that might include i
            while (j < other.intervals.size && other.intervals[j].u < i.l) {
                j++
            }

            // No more intervals in other to check
            if (j >= other.intervals.size) {
                return false
            }

            // Check if current interval from other includes i
            if (!other.intervals[j].includes(i)) {
                return false
            }
        }

        return true
    }

    /**
     * Returns a new SetOfFiniteIntervals containing all intervals from this set that intersect
     * with at least one interval from [other].
     */
    fun filterIntersecting(other: SetOfFiniteIntervals): SetOfFiniteIntervals {
        if (intervals.isEmpty() || other.intervals.isEmpty()) {
            return SetOfFiniteIntervals(emptyList())
        }

        val out = mutableListOf<FiniteInterval>()
        var j = 0

        for (i in intervals.indices) {
            val thisInterval = intervals[i]

            // Advance j to the first interval in other that might intersect with thisInterval
            while (j < other.intervals.size && other.intervals[j].u < thisInterval.l) {
                j++
            }

            // Check if thisInterval  intersects with any interval in other starting from j
            var found = false
            var k = j
            while (k < other.intervals.size && other.intervals[k].l <= thisInterval.u) {
                if (thisInterval.intersection(other.intervals[k]) != null) {
                    found = true
                    break
                }
                k++
            }

            if (found) {
                out.add(thisInterval)
            }
        }

        return SetOfFiniteIntervals(out)
    }


    fun getSingleton() = intervals.singleOrNull()

    fun includes(x: FiniteInterval): Boolean {
        return intervals.any{i -> i.includes(x)}
    }

    fun intersects(x: FiniteInterval): Boolean {
        return intervals.any{i -> i.intersection(x) != null}
    }
}
