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

import sbf.cfg.CondOp
import sbf.domains.*
import org.junit.jupiter.api.*

class ConstantSetTest {

    @Test
    fun test1() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 2UL)
        println("$s")
        Assertions.assertEquals(true, s.isTop())
    }

    @Test
    fun test2() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s")
        Assertions.assertEquals(false, s.isTop())
    }

    @Test
    fun test3() {
        val s1 = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s1")
        val s2 = s1.add(2)
        println("After +2: $s2")
        Assertions.assertEquals(false, s2.isTop())
    }

    @Test
    fun test4() {
        val s = ConstantSet(listOf(1L,2L,3L).map{Constant(it)}.toSet(), 3UL)
        println("$s")
        Assertions.assertEquals(true, s.toLongList().isNotEmpty())
    }

    // filter tests

    @Test
    fun filterEqIntersects() {
        // {1,2,3}.filter(EQ, {2,3,4}) == {2,3}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(listOf(2L, 3L, 4L).map { Constant(it) }.toSet(), 3UL)
        val result = s1.filter(CondOp.EQ, s2)
        Assertions.assertEquals(setOf(2L, 3L), result.toLongList().toSet())
    }

    @Test
    fun filterEqDisjointBecomesBottom() {
        // {1,2}.filter(EQ, {3,4}) == bottom
        val s1 = ConstantSet(listOf(1L, 2L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(listOf(3L, 4L).map { Constant(it) }.toSet(), 3UL)
        val result = s1.filter(CondOp.EQ, s2)
        Assertions.assertTrue(result.isBottom())
    }

    @Test
    fun filterEqSingleton() {
        // {1,2,3}.filter(EQ, {2}) == {2}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = s1.filter(CondOp.EQ, s2)
        Assertions.assertEquals(listOf(2L), result.toLongList())
    }

    @Test
    fun filterLeSmallNKeepsValuesUpToN() {
        // {1,2,3}.filter(LE, {2}) with maxNumDisjuncts=3 → meets with {0,1,2} → {1,2}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = s1.filter(CondOp.LE, s2)
        Assertions.assertEquals(setOf(1L, 2L), result.toLongList().toSet())
    }

    @Test
    fun filterLeSmallNIncludesZero() {
        // {0,1,2,3}.filter(LE, {3}) with maxNumDisjuncts=4 → meets with {0,1,2,3} → {0,1,2,3}
        val s1 = ConstantSet(listOf(0L, 1L, 2L, 3L).map { Constant(it) }.toSet(), 4UL)
        val s2 = ConstantSet(Constant(3L), 4UL)
        val result = s1.filter(CondOp.LE, s2)
        Assertions.assertEquals(setOf(0L, 1L, 2L, 3L), result.toLongList().toSet())
    }

    @Test
    fun filterLtZeroBecomesBottom() {
        // {1,2,3}.filter(LT, {0}) == bottom
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(0L), 3UL)
        val result = s1.filter(CondOp.LT, s2)
        Assertions.assertTrue(result.isBottom())
    }

    @Test
    fun filterLtSmallNKeepsValuesStrictlyBelowN() {
        // {1,2,3}.filter(LT, {3}) with maxNumDisjuncts=3 → meets with {0,1,2} → {1,2}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(3L), 3UL)
        val result = s1.filter(CondOp.LT, s2)
        Assertions.assertEquals(setOf(1L, 2L), result.toLongList().toSet())
    }

    @Test
    fun filterLtOne() {
        // {0,1,2}.filter(LT, {1}) with maxNumDisjuncts=3 → meets with {0} → {0}
        val s1 = ConstantSet(listOf(0L, 1L, 2L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(1L), 3UL)
        val result = s1.filter(CondOp.LT, s2)
        Assertions.assertEquals(listOf(0L), result.toLongList())
    }

    @Test
    fun filterThisBottomReturnsThis() {
        // bottom.filter(EQ, {1,2}) == bottom
        val bottom = ConstantSet.mkBottom(3UL)
        val s2 = ConstantSet(listOf(1L, 2L).map { Constant(it) }.toSet(), 3UL)
        val result = bottom.filter(CondOp.EQ, s2)
        Assertions.assertTrue(result.isBottom())
    }

    @Test
    fun filterOtherBottomReturnsBottom() {
        // {1,2,3}.filter(EQ, bottom) == bottom
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val bottom = ConstantSet.mkBottom(3UL)
        val result = s1.filter(CondOp.EQ, bottom)
        Assertions.assertTrue(result.isBottom())
    }

    @Test
    fun filterThisTopWithOtherTopReturnsTop() {
        // top.filter(GT, top) == top (else branch: isTop() → returns this)
        val top = ConstantSet.mkTop(3UL)
        val result = top.filter(CondOp.GT, top)
        Assertions.assertTrue(result.isTop())
    }

    @Test
    fun filterThisTopWithConcreteOtherReturnsTop() {
        // top.filter(GT, {5}) == top (else branch: isTop() → returns this)
        val top = ConstantSet.mkTop(3UL)
        val s2 = ConstantSet(Constant(5L), 3UL)
        val result = top.filter(CondOp.GT, s2)
        Assertions.assertTrue(result.isTop())
    }

    @Test
    fun filterOtherTopReturnsThis() {
        // {1,2,3}.filter(GT, top) == {1,2,3} (else branch: other.isTop() → returns this)
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val top = ConstantSet.mkTop(3UL)
        val result = s1.filter(CondOp.GT, top)
        Assertions.assertEquals(setOf(1L, 2L, 3L), result.toLongList().toSet())
    }

    @Test
    fun filterNeRemovesMatchingValues() {
        // {1,2,3}.filter(NE, {2}) == {1,3}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = s1.filter(CondOp.NE, s2)
        Assertions.assertEquals(setOf(1L, 3L), result.toLongList().toSet())
    }

    @Test
    fun filterGtKeepsOnlyGreaterValues() {
        // {1,2,3}.filter(GT, {2}) == {3}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = s1.filter(CondOp.GT, s2)
        Assertions.assertEquals(listOf(3L), result.toLongList())
    }

    @Test
    fun filterGtAllFailBecomesBottom() {
        // {1,2}.filter(GT, {3}) == bottom  (none satisfy >3)
        val s1 = ConstantSet(listOf(1L, 2L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(3L), 3UL)
        val result = s1.filter(CondOp.GT, s2)
        Assertions.assertTrue(result.isBottom())
    }

    @Test
    fun filterGeKeepsGreaterOrEqualValues() {
        // {1,2,3}.filter(GE, {2}) == {2,3}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = s1.filter(CondOp.GE, s2)
        Assertions.assertEquals(setOf(2L, 3L), result.toLongList().toSet())
    }

    @Test
    fun filterLeLargeNFallsToElseBranch() {
        // {1,2,3}.filter(LE, {5}) with maxNumDisjuncts=3:
        // n=5 > maxNumDisjuncts=3, falls to else; all elements satisfy <=5, so result == {1,2,3}
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(Constant(5L), 3UL)
        val result = s1.filter(CondOp.LE, s2)
        Assertions.assertEquals(setOf(1L, 2L, 3L), result.toLongList().toSet())
    }

    // Regression tests (CERT-10126): every non-top, non-bottom `ConstantSet` must yield a non-empty
    // `toLongList()`. Clients such as `mustBeNumber` in the scalar domain rely on that invariant, so a
    // set whose size exceeded `maxNumDisjuncts` without being smashed to top used to crash them.

    /** Assert that [s] is normalized, i.e., that it is top, bottom, or has a non-empty list of numbers **/
    private fun assertNormalized(s: ConstantSet) {
        Assertions.assertTrue(
            s.isTop() || s.isBottom() || s.toLongList().isNotEmpty()
        ) { "Non-top, non-bottom ConstantSet $s has an empty toLongList()" }
    }

    @Test
    fun addCrossProductBeyondCapIsTop() {
        // {1,2,3} + {10,20,30} has 9 distinct sums, which is beyond maxNumDisjuncts=3
        val s1 = ConstantSet(listOf(1L, 2L, 3L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(listOf(10L, 20L, 30L).map { Constant(it) }.toSet(), 3UL)
        val result = s1.add(s2)
        assertNormalized(result)
        Assertions.assertTrue(result.isTop())
    }

    @Test
    fun addCrossProductWithinCapIsPreserved() {
        // {1,2} + {10,20} has 4 distinct sums, which still fits within maxNumDisjuncts=4
        val s1 = ConstantSet(listOf(1L, 2L).map { Constant(it) }.toSet(), 4UL)
        val s2 = ConstantSet(listOf(10L, 20L).map { Constant(it) }.toSet(), 4UL)
        val result = s1.add(s2)
        assertNormalized(result)
        Assertions.assertEquals(setOf(11L, 12L, 21L, 22L), result.toLongList().toSet())
    }

    @Test
    fun mulCrossProductBeyondCapIsTop() {
        // {2,3,5} * {7,11,13} has 9 distinct products, which is beyond maxNumDisjuncts=3
        val s1 = ConstantSet(listOf(2L, 3L, 5L).map { Constant(it) }.toSet(), 3UL)
        val s2 = ConstantSet(listOf(7L, 11L, 13L).map { Constant(it) }.toSet(), 3UL)
        val result = s1.mul(s2)
        assertNormalized(result)
        Assertions.assertTrue(result.isTop())
    }

    @Test
    fun filterLeAtCapIsNormalized() {
        // top.filter(LE, {3}) with maxNumDisjuncts=3 cannot represent {0,1,2,3}, so it must be top
        val top = ConstantSet.mkTop(3UL)
        val s2 = ConstantSet(Constant(3L), 3UL)
        val result = top.filter(CondOp.LE, s2)
        assertNormalized(result)
        Assertions.assertTrue(result.isTop())
    }

    @Test
    fun filterLeBelowCapRefinesTop() {
        // top.filter(LE, {2}) with maxNumDisjuncts=3 can represent {0,1,2}
        val top = ConstantSet.mkTop(3UL)
        val s2 = ConstantSet(Constant(2L), 3UL)
        val result = top.filter(CondOp.LE, s2)
        assertNormalized(result)
        Assertions.assertEquals(setOf(0L, 1L, 2L), result.toLongList().toSet())
    }
}
