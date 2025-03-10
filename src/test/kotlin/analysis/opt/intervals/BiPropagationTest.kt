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

import analysis.numeric.MAX_UINT
import analysis.opt.intervals.ExtBig.Companion.MaxUInt
import analysis.opt.intervals.ExtBig.Companion.One
import analysis.opt.intervals.ExtBig.Companion.TwoTo256
import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.Inf
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Intervals.Companion.S2To256
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFull
import analysis.opt.intervals.Intervals.Companion.SFull256
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_UINT256
import evm.MIN_EVM_INT256_2S_COMPLEMENT
import evm.twoToThe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import utils.*
import analysis.opt.intervals.Intervals.Companion as S

class BiPropagationTest {

    @Test
    fun mul() {
        val result = BiPropagation.mult(
            lhs = S(0, 100),
            x = S(2, 100),
            y = S(3, 100)
        )
        assertEquals(
            listOf(S(6, 100), S(2, 33), S(3, 50)),
            result
        )
    }

    @Test
    fun mul2() {
        val result = BiPropagation.mult(
            lhs = SFull,
            x = S(100),
            y = S(0, 99)
        )
        assertEquals(
            listOf(S(0, 9900), S(100), S(0, 99)),
            result
        )
    }

    @Test
    fun mulBug() {
        val result = BiPropagation.mult(
            lhs = S(100),
            x = S(1, 30),
            y = SFull
        ).let(BiPropagation::mult)

        // this needs `mult` to run twice to get the right answer...

        assertEquals(
            listOf(
                S(100),
                S(1, 25),
                S(4, 100)
            ),
            result
        )
    }

    @Test
    fun div1() {
        val result = BiPropagation.div(
            lhs = S(100),
            x = SFull,
            y = S(10, 20)
        )
        assertEquals(
            listOf(
                S(100),
                S(1000, 2019),
                S(10, 20)
            ),
            result
        )
    }

    @Test
    fun div2() {
        val result = BiPropagation.div(
            lhs = S(0, 3),
            x = S(100),
            y = SFull.delete(0)
        )
        assertEquals(
            listOf(
                S(0, 3),
                S(100, 100),
                S(MInf, (-101).asExtBig, 26.asExtBig, Inf)
            ),
            result
        )
    }

    @Test
    fun div3() {
        val result = BiPropagation.div(
            lhs = S(-2, 3),
            x = S(100),
            y = SFull.delete(0)
        )
        assertEquals(
            listOf(
                S(-2, 3),
                S(100, 100),
                S(MInf, (-34).asExtBig, 26.asExtBig, Inf)
            ),
            result
        )
    }

    @Test
    fun div4() {
        val result = BiPropagation.div(
            lhs = SFull256,
            x = S(3),
            y = S(4)
        )
        assertEquals(
            listOf(
                S(0),
                S(3),
                S(4)
            ),
            result
        )
    }

    @Test
    fun div5() {
        val result = BiPropagation.div(
            lhs = S(0),
            x = S(SFull),
            y = S(3)
        )
        assertEquals(
            listOf(
                S(0),
                S(-2, 2),
                S(3)
            ),
            result
        )
    }


    @Test
    fun le1() {
        val result = BiPropagation.le(
            lhs = STrue,
            x = S((-10).asExtBig, 10.asExtBig, 20.asExtBig, Inf),
            y = S(-20, -15, 25, 50)
        )
        assertEquals(
            listOf(
                STrue,
                S(-10, 10, 20, 50),
                S(25, 50)
            ),
            result
        )
    }


    @Test
    fun sLe1() {
        val result = BiPropagation.sLe(
            lhs = STrue,
            x = S(Zero, 100.asExtBig, MaxUInt, MaxUInt), // -1 -> 100
            y = S(25, 50),
            Tag.Bit256
        )
        assertEquals(
            listOf(
                STrue,
                S(Zero, 50.asExtBig, MaxUInt, MaxUInt), // -1 -> 50,
                S(25, 50)
            ),
            result
        )
    }

    @Test
    fun mulMod() {
        val result = BiPropagation.mulMod(
            lhs = S(EVM_MOD_GROUP256 - 200, EVM_MOD_GROUP256 - 50), // [-200, -50]
            x = S(MAX_EVM_UINT256), // -1
            y = S(0, 100),
            m = S2To256
        )
        assertEquals(
            listOf(
                S(EVM_MOD_GROUP256 - 100, EVM_MOD_GROUP256 - 50),
                S(MAX_EVM_UINT256),
                S(0, 100), // TODO : this should be S(50, 100)
                S2To256
            ),
            result
        )
    }

    @Test
    fun pow2() {
        val result = BiPropagation.powOf2Limited(
            lhs = S(1, 100),
            x = SFull256,
            Tag.Bit256
        )
        assertEquals(
            listOf(
                S(1, 100),
                S(0, 6),
            ),
            result
        )
    }

    @Test
    fun pow2_2() {
        val result = BiPropagation.powOf2Limited(
            lhs = S(Zero, TwoTo256),
            x = SFull256,
            Tag.Bit256
        )
        assertEquals(
            listOf(
                S(One, TwoTo256),
                SFull256,
            ),
            result
        )
    }

    @Test
    fun iteTest() {
        val result = BiPropagation.ite(
            lhs = STrue,
            i = SFullBool,
            t = SFullBool,
            e = SFullBool
        )
        assertEquals(
            listOf(STrue, SFullBool, SFullBool, SFullBool),
            result
        )
    }

    @Test
    fun pow2LimitedTest() {
        val result = BiPropagation.powOf2Limited(
            lhs = SFull,
            x = S(4),
            Tag.Bit256
        )
        assertEquals(
            listOf(S(16), S(4)),
            result
        )
    }

    @Test
    fun ite() {
        val result = BiPropagation.ite(
            lhs = S(9, 100),
            i = SFullBool,
            t = S(0, 3),
            e = S(5, 90)
        )
        assertEquals(
            listOf(S(9, 90), SFalse, S(0, 3), S(9, 90)),
            result
        )
    }

    @Test
    fun ite2() {
        val result = BiPropagation.ite(
            lhs = S(200.asExtBig, MaxUInt),
            i = SFullBool,
            t = S(0, 99),
            e = S(101.asExtBig, MaxUInt)
        )
        assertEquals(
            listOf(S(200.asExtBig, MaxUInt), SFalse, S(0, 99), S(200.asExtBig, MaxUInt)),
            result
        )
    }

    @Test
    fun lor() {
        val result = BiPropagation.or(
            listOf(STrue, SFalse, SFullBool)
        )
        assertEquals(
            listOf(STrue, SFalse, STrue),
            result
        )
    }

    @Test
    fun land() {
        val result = BiPropagation.and(
            listOf(SFalse, STrue, SFullBool)
        )
        assertEquals(
            listOf(SFalse, STrue, SFalse),
            result
        )
    }

    @Test
    fun div0() {
        val result = BiPropagation.div(
            SFull256, S(0, 255), S(0)
        )
        assertEquals(
            listOf(SFull256, S(0, 255), S(0)),
            result
        )
    }

    @Test
    fun signExtend() {
        val result = BiPropagation.signExtend(
            S(0, 10), S(0, 127), fromBit = 8, toBit = 256
        )
        assertEquals(
            listOf(S(0, 10), S(0, 10)),
            result
        )
    }

    @Test
    fun signExtend2() {
        val result = BiPropagation.signExtend(
            SFull256, S(0, 2), fromBit = 2, toBit = 256
        )
        assertEquals(
            listOf(S(0, 1) union S(MAX_UINT - 1), S(0, 2)),
            result
        )
    }

    @Test
    fun bwAndBackward() {
        val result = BiPropagation.bwAnd(
            S(1, 255), S(0, 255), S(255)
        )
        assertEquals(
            listOf(S(1, 255), S(1, 255), S(255)),
            result
        )
    }

    @Test
    fun sdivOverflow() {
        val result = BiPropagation.sDiv(
            SFull, S(MIN_EVM_INT256_2S_COMPLEMENT), S(MAX_UINT), Tag.Bit256
        )
        assertEquals(
            listOf(S(MIN_EVM_INT256_2S_COMPLEMENT), S(MIN_EVM_INT256_2S_COMPLEMENT), S(MAX_UINT)),
            result
        )
    }

    @Test
    fun pow2Limited() {
        val result = BiPropagation.powOf2Limited(
            S(Zero, TwoTo256), S(256), Tag.Bit256
        )
        assertEquals(
            listOf(S(TwoTo256), S(256)),
            result
        )
    }

    @Test
    fun pow2Limited2() {
        val result = BiPropagation.powOf2Limited(
            S(Zero, TwoTo256), S(300), Tag.Bit256
        )
        assertEquals(
            listOf(S(TwoTo256), S(300)),
            result
        )
    }

    @Test
    fun mod0() {
        val result = BiPropagation.unsignedMod(
            SFull256, S(10), S(0)
        )
        assertEquals(
            listOf(S(0), S(10), S(0)),
            result
        )
    }

}
