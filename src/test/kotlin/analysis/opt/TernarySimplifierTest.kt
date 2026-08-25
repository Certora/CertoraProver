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

package analysis.opt

import analysis.split.Ternary.Companion.bwNot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.Companion.testProgString
import vc.data.asTACExpr

class TernarySimplifierTest : TACBuilderAuxiliaries() {

    @Test
    fun testSimplifyMask() {
        val prog = TACProgramBuilder {
            b assign Mul(aS, 16.asTACExpr)
            c assign BWAnd(bS, 0xffff0.asTACExpr)
        }
        val expected = TACProgramBuilder {
            b assign Mul(aS, 16.asTACExpr)
            c assign BWAnd(bS, 0xfffff.asTACExpr)
        }
        val simplified = TernarySimplifier.simplify(prog.code, false)
        assertEquals(
            testProgString(expected.code),
            testProgString(simplified)
        )
    }

    /**
     * The Solidity `bytes` allocation stanza: `b = (len + 31) & ~31`, and then the total allocation
     * size is `(0x1f + (0x20 + b)) & ~0x1f`. Since `b` is 32-aligned, the low 5 bits of the second
     * mask's operand are known to be ones, so the mask is just a subtraction:
     *
     * `c & ~0x1f`  ~~>  `c - 0x1f`     (when the low 5 bits of `c` are known ones)
     */
    @Test
    fun testAlignmentMaskBecomesSub() {
        val mask = bwNot(0x1f.toBigInteger()).asTACExpr
        val prog = TACProgramBuilder {
            b assign BWAnd(aS, mask)
            c assign Add(0x1f.asTACExpr, Add(0x20.asTACExpr, bS))
            d assign BWAnd(cS, mask)
        }
        val expected = TACProgramBuilder {
            b assign BWAnd(aS, mask)
            c assign Add(0x1f.asTACExpr, Add(0x20.asTACExpr, bS))
            d assign Sub(cS, 0x1f.asTACExpr)
        }
        val simplified = TernarySimplifier.simplify(prog.code, false)
        assertEquals(
            testProgString(expected.code),
            testProgString(simplified)
        )
    }

    /** Without the alignment of `a`, the low bits of the mask's operand are unknown — no rewrite. */
    @Test
    fun testAlignmentMaskNotRemovedWhenLowBitsUnknown() {
        val prog = TACProgramBuilder {
            c assign Add(0x3f.asTACExpr, aS)
            d assign BWAnd(cS, bwNot(0x1f.toBigInteger()).asTACExpr)
        }
        val simplified = TernarySimplifier.simplify(prog.code, false)
        assertEquals(
            testProgString(prog.code),
            testProgString(simplified)
        )
    }

    /** See the simplification doesn't happen when it shouldn't */
    @Test
    fun testSimplifyMaskSanity() {
        val prog = TACProgramBuilder {
            b assign Mul(aS, 8.asTACExpr)
            c assign BWAnd(bS, 0xffff0.asTACExpr)
        }
        val simplified = TernarySimplifier.simplify(prog.code, false)
        assertEquals(
            testProgString(prog.code),
            testProgString(simplified)
        )
    }

}
