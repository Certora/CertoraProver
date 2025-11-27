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

import org.jetbrains.annotations.TestOnly
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


data class SetOfFiniteIntervals(private val intervals: List<FiniteInterval>) {
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
                alreadyInserted = true
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

    fun getSingleton() = intervals.singleOrNull()

    fun includes(x: FiniteInterval): Boolean {
        return intervals.any{i -> i.includes(x)}
    }

    fun intersects(x: FiniteInterval): Boolean {
        return intervals.any{i -> i.intersection(x) != null}
    }

    @TestOnly
    fun contains(x: FiniteInterval): Boolean {
        return intervals.contains(x)
    }
}
