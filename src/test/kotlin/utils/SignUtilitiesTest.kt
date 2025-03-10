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

package utils

import config.Config
import evm.twoToThe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import utils.ModZm.Companion.signExtend
import utils.SignUtilities.from2sComplement
import utils.SignUtilities.maxSignedValueOfBitwidth
import utils.SignUtilities.maxUnsignedValueOfBitwidth
import utils.SignUtilities.minSignedValueOfBitwidth
import utils.SignUtilities.to2sComplement
import java.math.BigInteger
import java.util.*

class SignUtilitiesTest {

    private val bitWidth = Config.VMConfig.registerBitwidth
    private val twosComplement =
        BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")
    private val negativeOne = BigInteger("-1")

    @Test
    fun testTwosComplementRepresentationForCurrentVM() {

        //Check two's complement
        val tcOne = negativeOne.to2sComplement()
        assertTrue(tcOne > BigInteger.ZERO)
        assertEquals(twosComplement, tcOne)

        //Check a positive number
        assertEquals(BigInteger.ONE, BigInteger.ONE.to2sComplement())

        //Error State
        val tooLarge = BigInteger(bitWidth + 1, Random(1))
        assertThrows<IllegalArgumentException> {
            tooLarge
                .to2sComplement()
        }.message?.contains("converting $tooLarge from mathint to 2s-complement form, but it's not in range.")
            ?.let { assertTrue(it) }

    }

    @Test
    fun testArbitraryPrecisionIntegerRepresentationFromCurrentVM() {
        val positive = BigInteger.ONE.from2sComplement()
        assertEquals(positive, positive.from2sComplement())

        val negative = BigInteger.TWO.pow(bitWidth) + negativeOne
        assertEquals(negativeOne, negative.from2sComplement())

    }

    @Test
    fun testMinSignedValueOfBitwidth() {
        //Happy Path
        assertEquals(negativeOne, minSignedValueOfBitwidth(1))


        //Error Check
        assertThrows<IllegalArgumentException> { minSignedValueOfBitwidth(0) }
            .message?.contains("Value out of bounds for bitwidth:")?.let { assertTrue(it) }
    }

    @Test
    fun testMaxSignedValueOfBitwidth() {
        //Happy Path
        assertEquals(BigInteger.ZERO, maxSignedValueOfBitwidth(1))

        //Error Check
        assertThrows<IllegalArgumentException> { maxSignedValueOfBitwidth(0) }
            .message?.contains("Value out of bounds for bitwidth:")?.let { assertTrue(it) }
    }

    @Test
    fun testMaxUnsignedValueOfBitwidth() {
        //Happy Path
        assertEquals(BigInteger.ONE, maxUnsignedValueOfBitwidth(1))

        //Error Check
        assertThrows<IllegalArgumentException> { maxUnsignedValueOfBitwidth(-1) }
            .message?.contains("Value out of bounds for bitwidth:")?.let { assertTrue(it) }
    }

    @Test
    fun testSignExtend() {
        fun test(src: Long, srcbw: Int, tgtbw: Int) {
            val source = BigInteger.valueOf(src)
            val source2 = source.to2sComplement(srcbw)
            if (src >= 0) {
                assertEquals(source, source2) { "2s complement for positive" }
            } else {
                assertEquals(twoToThe(srcbw) + source, source2)  { "2s complement for negative" }
            }
            val target2 = source2.signExtend(srcbw, tgtbw)
            if (src >= 0) {
                assertEquals(source2, target2) { "sign extend for positive" }
            } else {
                assertEquals((twoToThe(tgtbw) - 1) - (twoToThe(srcbw) - 1) + source2, target2) { "sign extend for negative" }
            }
            val target = target2.from2sComplement(tgtbw)
            assertEquals(source, target)
        }

        (-4L..3L).forEach { test(it, 3, 8) }
    }

}
