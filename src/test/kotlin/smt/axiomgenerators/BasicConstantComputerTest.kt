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

package smt.axiomgenerators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import smt.HashingScheme
import smt.solverscript.LExpressionFactory
import tac.Tag
import vc.data.LExpression

internal class BasicConstantComputerTest {

    private val lxf = LExpressionFactory()
    private val legacyGapSize = HashingScheme.defaultLegacyGapSize
    private val constantComputer = ConstantComputerWithHashSimplifications(lxf, legacyGapSize)

    private fun check2(expected: LExpressionFactory.() -> LExpression, actual: LExpressionFactory.() -> LExpression) =
        assertEquals(
            lxf.expected(),
            constantComputer.evalRec(lxf.actual())
        )

    @Test
    fun oldBug() {
        // constantComputerWithHashSimplifications had a bug when simplifying equality of two additions.
        val x = LExpression.Identifier("X", Tag.Int)
        val expr2 = lxf { litInt(1) intAdd (x intMul litInt(3)) }
        val expr1 = lxf { litInt(2) intAdd (x intMul litInt(2)) }
        val expr = lxf { expr1 eq expr2 }
        val simplified = constantComputer.evalRec(expr)

        assertFalse(simplified == lxf.litBool(false))
    }

    @Test
    fun signExtendTest() {
        val x = LExpression.Identifier("X", Tag.Bit256)

        check2(
            { litInt(0x3f) },
            { litInt(0x3f) signExt 0 }
        )
        check2(
            { litInt("115792089237316195423570985008687907853269984665640564039457584007913129639935") },
            { litInt(0xff) signExt 0 }
        )
        check2(
            { litInt("115792089237316195423570985008687907853269984665640564039457584007913129636095") },
            { litInt(0x11f0ff) signExt 1 }
        )
        check2(
            { x signExt 3 },
            { (x signExt 3) signExt 5 }
        )
        check2(
            { x signExt 2 },
            { (x signExt 3) signExt 2 }
        )
        check2(
            { x signExt 1 },
            { (lit(0xffff, Tag.Bit256) bitwiseAnd x) signExt 1 }
        )
    }

    val a = lxf.const("a", Tag.Bool, null)
    val b = lxf.const("b", Tag.Bool, null)
    val c = lxf.const("c", Tag.Bool, null)
    val x = lxf.const("x", Tag.Int, null)
    val y = lxf.const("y", Tag.Int, null)
    val z = lxf.const("z", Tag.Int, null)

    @Test
    fun implSimplifiers() {
        check2(
            { (a or b) implies c },
            { (a implies c) and (b implies c) }
        )
        check2(
            { (a and b) implies c },
            { a implies (b implies c) }
        )
    }

    @Test
    fun plusMinusNormalForm() {
        check2(
            { x },
            { (x + x) - x }
        )
        check2(
            { intAdd(litInt(160), x, y) - z },
            { intAdd(x, y, ZERO, ZERO, litInt(320)) - (z + 160) },
        )
        check2(
            { x - intAdd(litInt(3), 2 * y, z) },
            { x + (1 - y) + (2 - (z + x + 10)) + (x - (y - 4)) }
        )
        check2(
            { (0 - ((2 * y) + x)) },
            { (4 * x) - ((2 * y) + (5 * x)) }
        )
        check2(
            { litInt(5) },
            { intAdd(4 * x, 4 * y, 5 - (4 * (x + y))) }
        )
        check2(
            { ZERO },
            { (x - 3) + (3 - x)}
        )
        check2(
            { 5 * (x * y) },
            { intMul(litInt(3), x, y) + intMul(x, y, litInt(2)) }
        )
    }
}
