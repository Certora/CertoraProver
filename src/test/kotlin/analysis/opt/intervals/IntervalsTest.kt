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

package analysis.opt.intervals

import analysis.opt.intervals.ExtBig.Companion.MaxUInt
import analysis.opt.intervals.ExtBig.Companion.One
import analysis.opt.intervals.ExtBig.Companion.TwoTo256
import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.Inf
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Intervals.Companion.S2To256
import analysis.opt.intervals.Intervals.Companion.SEmpty
import analysis.opt.intervals.Intervals.Companion.SmaxUint
import analysis.opt.intervals.Intervals.Companion.mulMod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import analysis.opt.intervals.Interval.Companion as I
import analysis.opt.intervals.Intervals.Companion as S

class IntervalsTest {

    @Test
    fun testMulMod() {
        assertEquals(
            S(Zero, Zero, TwoTo256 - 100, MaxUInt),
            mulMod(S(0, 100), SmaxUint /* -1 */, S2To256)
        )
    }

    @Test
    fun testDiv() {
        assertEquals(S(3, 9), S(11, 19) / S(2, 3))
        assertEquals(S(4, 9), S(11, 19) exactDiv S(2, 3))

        assertEquals(S(-9, -3), S(-19, -11) / S(2, 3))
        assertEquals(S(-9, -4), S(-19, -11) exactDiv S(2, 3))

        assertEquals(S(-9, 5), S(-19, 11) / S(2, 3))
        assertEquals(S(-9, 5), S(-19, 11) exactDiv S(2, 3))

        assertEquals(S(3.asExtBig, Inf), S(11.asExtBig, Inf) / S(2, 3))
        assertEquals(S(4.asExtBig, Inf), S(11.asExtBig, Inf) exactDiv S(2, 3))

        assertEquals(S(Zero, Inf), S(11.asExtBig, Inf) / S(5.asExtBig, Inf))
        assertEquals(S(One, Inf), S(11.asExtBig, Inf) exactDiv S(5.asExtBig, Inf))

        assertEquals(S(MInf, 10.asExtBig), S((-31).asExtBig, Inf) / S(MInf, (-3).asExtBig))
        assertEquals(S(MInf, 10.asExtBig), S((-31).asExtBig, Inf) / S(MInf, (-3).asExtBig))
    }

    @Test
    fun testPow2() {
        assertEquals(16.asExtBig, 4.asExtBig.pow2limited())
    }


    @Test
    fun testIntersect() {
        assertEquals(
            S(201, 1000),
            S(201, 1000) intersect S(0, 40, 100, 2000)
        )
        assertEquals(
            S(10, 40, 50, 100),
            S(10, 100) intersect S(0, 40, 50, 100)
        )
        assertEquals(
            SEmpty,
            S(10, 20, 30, 40) intersect S(0, 3, 23, 25, 50, 50)
        )
    }

    @Test
    fun testContainedIn() {
        assert(S(201, 1000) containedIn S(0, 40, 100, 2000))
        assert(S(0, 20) containedIn S(0, 40, 100, 2000))
        assertFalse(S(0, 41) containedIn S(0, 40, 100, 2000))
    }

    @Test
    fun testBwOrXor() {
        assertEquals(
            I(0xf0, 0xff),
            I(0xf0, 0xf1) bwOr I(0xee)
        )
        assertEquals(
            I(0, 0xff),
            I(0xf0, 0xf1) bwXor I(0xee)
        )
    }

    @Test
    fun testEvmMod() {
        assertEquals(S(2), I(12) evmSignedMod I(5))
        assertEquals(S(-2), I(-12) evmSignedMod I(5))
        assertEquals(S(2), I(12) evmSignedMod I(5))
        assertEquals(S(-2), I(-12) evmSignedMod I(5))

        assertEquals(S(-3, 5), I(-3, 5) evmSignedMod I(10))
        assertEquals(S(-3, 5), I(-3, 8) evmSignedMod I(6))
        assertEquals(S(0, 2, 8, 9), I(8, 12) evmSignedMod I(10))
        assertEquals(-S(0, 2, 8, 9), I(-12, -8) evmSignedMod I(-10))
    }

    @Test
    fun testCvlMod() {
        assertEquals(S(2), I(12) cvlMod I(5))
        assertEquals(S(3), I(-12) cvlMod I(5))
        assertEquals(S(2), I(12) cvlMod I(5))
        assertEquals(S(3), I(-12) cvlMod I(5))

        assertEquals(S(0, 5, 7, 9), I(-3, 5) cvlMod I(10))
        assertEquals(S(0, 5), I(-3, 8) cvlMod I(6))
        assertEquals(S(0, 2, 8, 9), I(8, 12) cvlMod I(10))
        assertEquals(S(0, 2, 8, 9), I(-12, -8) cvlMod I(-10))
    }

    @Test
    fun testBWAnd() {
        assertEquals(I(1, 255), I(1, 255) bwAnd I(255))
    }

}
