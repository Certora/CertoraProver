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

package sbf

import sbf.domains.*
import org.junit.jupiter.api.*

class IntervalMapTest {
    private fun intervalMerger(c1: ConstantSet, c2: ConstantSet): ConstantSet {
        return c1.join(c2)
    }
    private fun mkConstant(v: Long) = ConstantSet(v, 20UL)

    // insert with merging semantics and remove
    @Test
    fun test01() {

        val m0 = unionIntervalMap<ConstantSet>()
        val m1 = m0.insert(0,10, mkConstant(1), ::intervalMerger)
        val m2 = m1.insert(15,30, mkConstant(2), ::intervalMerger)
        val m3 = m2.insert(40,60, mkConstant(3), ::intervalMerger)
        println("Initially $m3")
        Assertions.assertEquals(true, m3.size() == 3)

        Assertions.assertEquals(true, m3.get(5) == mkConstant(1))
        Assertions.assertEquals(true, m3.get(35) == null)
        Assertions.assertEquals(true, m3.get(30) == mkConstant(2))
        Assertions.assertEquals(true, m3.get(40) == mkConstant(3))
        Assertions.assertEquals(true, m3.get(50) == mkConstant(3))

        val m4 = m3.insert(7, 20,  mkConstant(4), ::intervalMerger)
        println("After inserting [7,20] with merging semantics: $m4")
        Assertions.assertEquals(true, m4.size() == 2) // [0,10] and [15,30] are merged and [7,20] is included

        val m5 = m3.insert(35, 45,  mkConstant(4), ::intervalMerger)
        println("After inserting [35,45] with merging semantics: $m5")
        Assertions.assertEquals(true, m5.size() == 3) // [35,45] and [40,60] are merged


        val m6 = m3.insert(7, 20,  mkConstant(4))
        println("After inserting [7,20] with removing semantics: $m6")
        Assertions.assertEquals(true, m6.size() == 2) // [0,10] and [15,30] are removed

        val m7 = m3.remove(20, 24, IntervalMap.RemoveMode.SPLIT)
        println("After removing [20,24] with split semantics: $m7")
        Assertions.assertEquals(true, m7.size() == 4) // [15,30] is split into [15,19] and [25,30]

        val m8 = m3.remove(25, 35, IntervalMap.RemoveMode.SPLIT)
        println("After removing [25,35] with split semantics: $m8")
        Assertions.assertEquals(true, m8.size() == 3) // [15,30] is split into [15,24]

        val m9 = unionIntervalMap<ConstantSet>().insert(12, 27,mkConstant(1), ::intervalMerger)
        println("$m9")
        Assertions.assertEquals(true, m9.size() == 1)
        val m10 = m9.remove(12, 19, IntervalMap.RemoveMode.SPLIT)
        println("After removing [12,19]: $m10")
        Assertions.assertEquals(true, m10.size() == 1)
        val m11 = m10.remove(20, 27, IntervalMap.RemoveMode.SPLIT)
        println("After removing [20,27]: $m11")
        Assertions.assertEquals(true, m11.size() == 0)

        val m12 = m3.remove(5, 45, IntervalMap.RemoveMode.SPLIT)
        println("After removing [5,45] with split semantics: $m12")
        //Assertions.assertEquals(true, m7.size() == 4) // [15,30] is split into [15,19] and [25,30]

    }


    // Regression: `remove(start, end, SPLIT)` must reach intervals whose key is
    // strictly greater than `start` but still <= end.  The previous implementation
    // seeded with `floorEntry(start)` only; when the floor entry was null or did not
    // reach into `[start, end]`, the loop bailed out and missed overlapping intervals
    // whose key was in `(start, end]`.
    @Test
    fun `remove SPLIT reaches intervals whose key is between start and end`() {
        // Case 1 (the original bug report): no floor entry at all.
        // Map = {[3904, 3911] -> 1}, remove(3889, 3906, SPLIT)
        // Expected: {[3907, 3911] -> 1}
        val m1 = unionIntervalMap<ConstantSet>().insert(3904, 3911, mkConstant(1))
        val m1After = m1.remove(3889, 3906, IntervalMap.RemoveMode.SPLIT)
        println("After remove(3889, 3906, SPLIT) on $m1 -> $m1After")
        Assertions.assertEquals(true, m1After.size() == 1)
        Assertions.assertEquals(true, m1After.get(3906) == null)
        Assertions.assertEquals(true, m1After.get(3907) == mkConstant(1))
        Assertions.assertEquals(true, m1After.get(3911) == mkConstant(1))

        // Case 2: floor entry exists but does not reach into [start, end].
        // Map = {[0, 5] -> 1, [10, 20] -> 2}, remove(7, 15, SPLIT)
        // Expected: {[0, 5] -> 1, [16, 20] -> 2}
        val m2 = unionIntervalMap<ConstantSet>()
            .insert(0, 5, mkConstant(1))
            .insert(10, 20, mkConstant(2))
        val m2After = m2.remove(7, 15, IntervalMap.RemoveMode.SPLIT)
        println("After remove(7, 15, SPLIT) on $m2 -> $m2After")
        Assertions.assertEquals(true, m2After.size() == 2)
        Assertions.assertEquals(true, m2After.get(3) == mkConstant(1))
        Assertions.assertEquals(true, m2After.get(12) == null)
        Assertions.assertEquals(true, m2After.get(16) == mkConstant(2))
        Assertions.assertEquals(true, m2After.get(20) == mkConstant(2))

        // Case 3: removal range spans several intervals, none of which has key <= start.
        // Map = {[10, 12] -> 1, [20, 22] -> 2, [30, 32] -> 3}, remove(5, 25, SPLIT)
        // Expected: {[30, 32] -> 3}
        val m3 = unionIntervalMap<ConstantSet>()
            .insert(10, 12, mkConstant(1))
            .insert(20, 22, mkConstant(2))
            .insert(30, 32, mkConstant(3))
        val m3After = m3.remove(5, 25, IntervalMap.RemoveMode.SPLIT)
        println("After remove(5, 25, SPLIT) on $m3 -> $m3After")
        Assertions.assertEquals(true, m3After.size() == 1)
        Assertions.assertEquals(true, m3After.get(11) == null)
        Assertions.assertEquals(true, m3After.get(21) == null)
        Assertions.assertEquals(true, m3After.get(31) == mkConstant(3))

        // Case 4: NO_SPLIT mode with the same shape as Case 1.
        val m4 = unionIntervalMap<ConstantSet>().insert(3904, 3911, mkConstant(1))
        val m4After = m4.remove(3889, 3906, IntervalMap.RemoveMode.NO_SPLIT)
        println("After remove(3889, 3906, NO_SPLIT) on $m4 -> $m4After")
        Assertions.assertEquals(true, m4After.size() == 0)
    }


    // Insert with removing semantics
    @Test
    fun test02() {
        val m0 = unionIntervalMap<ConstantSet>()
        val m1 = m0.insert(0,10, mkConstant(1)).
                    insert(11,16, mkConstant(2))
        println("Initially $m1")

        val m2 = m1.insert(11, 20,  mkConstant(4))
        println("After inserting [11,20] with removing semantics: $m2")
        Assertions.assertEquals(true, m2.contains(11, 20) != null)
        // This is true because the interval [11,20] fully contains [11,16]
        Assertions.assertEquals(true, m2.contains(11, 16) != null)
        Assertions.assertEquals(true, m2.size() == 2)
    }


}
