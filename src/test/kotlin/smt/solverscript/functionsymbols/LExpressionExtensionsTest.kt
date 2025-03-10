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

package smt.solverscript.functionsymbols

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import smt.solverscript.LExpressionFactory
import tac.Tag
import vc.data.LExpression

class LExpressionExtensionsTest {
    val lxf = LExpressionFactory()
    val x = LExpression.Identifier("x", Tag.Bit256)
    val y = LExpression.Identifier("y", Tag.Bit256)
    val z = LExpression.Identifier("z", Tag.Bit256)

    val l13 = lxf.lit(13, Tag.Bit256)


    @Test
    fun substituteQuantified() {
        val f1 = lxf { eq(x, y) and exists(x) { eq(x, y) } }
        Assertions.assertEquals(
            lxf { eq(l13, y) and exists(x) { eq(x, y) } },
            f1.substituteQuantified(lxf, mapOf(x to l13))
        )
        val f2 = lxf { eq(x, y) and forall(x) { eq(x, y) } }
        Assertions.assertEquals(
            lxf { eq(l13, y) and forall(x) { eq(x, y) } },
            f2.substituteQuantified(lxf, mapOf(x to l13))
        )
        val f3 = lxf { eq(x, y) and forall(x) { eq(x, y) } or (z lt x) }
        Assertions.assertEquals(
            lxf { eq(l13, y) and forall(x) { eq(x, y) } or (z lt l13) },
            f3.substituteQuantified(lxf, mapOf(x to l13))
        )
        // x is quantified everywhere -- no change
        val f4 = lxf { exists(x) { eq(x, y) and forall(x) { eq(x, y) } or (z lt x) } }
        Assertions.assertEquals(f4, f4.substituteQuantified(lxf, mapOf(x to l13)))

        val f5 = lxf { exists(y) { eq(x, y) and forall(x) { eq(x, y) } or (z lt x) } }
        Assertions.assertEquals(
            lxf { exists(y) { eq(l13, y) and forall(x) { eq(x, y) } or (z lt l13) } },
            f5.substituteQuantified(lxf, mapOf(x to l13))
        )
    }

}
