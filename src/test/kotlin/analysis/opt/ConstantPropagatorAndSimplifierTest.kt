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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.Companion.testProgString
import vc.data.asTACExpr

class ConstantPropagatorAndSimplifierTest : TACBuilderAuxiliaries() {

    /**
     * A constant assignment followed by a havoc of the same variable must NOT leave the old constant tracked:
     * folding `a == 5` to `true` after `havoc a` would be unsound. Regression for the fix that invalidates the
     * tracked constant on any non-[vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd] write to the variable (a
     * havoc is an [vc.data.TACCmd.Simple.AssigningCmd.AssignHavocCmd]).
     */
    @Test
    fun havocInvalidatesTrackedConstant() {
        val prog = TACProgramBuilder {
            a assign 5
            havoc(a)
            x assign Eq(aS, 5.asTACExpr)
            assert(x)
        }
        // `a` is havoced, so `a == 5` cannot be resolved to a constant: the program is unchanged.
        assertEquals(
            testProgString(prog.code),
            testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        )
    }

    /**
     * Algebraic identities that don't hold on the edge cases of the evm/tac semantics: `x / x` is 0 at x = 0, and
     * `1 % x` is 0 for x in {0, 1}. Neither may be folded to a constant while the operands are symbolic.
     */
    @Test
    fun unsoundBitsIdentitiesAreNotSimplified() {
        val prog = TACProgramBuilder {
            b assign Div(aS, aS)
            c assign SDiv(aS, aS)
            d assign Mod(1.asTACExpr, aS)
            e assign SMod(1.asTACExpr, aS)
            assert(x)
        }
        assertEquals(
            testProgString(prog.code),
            testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        )
    }

    /**
     * [vc.data.TACExpr.BinOp.IntDiv] and [vc.data.TACExpr.BinOp.IntMod] by zero are unconstrained in the smt
     * encoding, so folding them to a constant would hide counterexamples.
     */
    @Test
    fun unsoundIntIdentitiesAreNotSimplified() {
        val prog = TACProgramBuilder {
            j assign IntDiv(iS, iS)
            k assign IntMod(iS, iS)
            s assign IntDiv(0.asTACExpr(Tag.Int), iS)
            assert(x)
        }
        assertEquals(
            testProgString(prog.code),
            testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        )
    }

    /**
     * `0 ^ e` is 0 for every positive e, but 1 at e = 0. It can't stay as it is either, because the smt
     * axiomatization of exponentiation doesn't handle a zero base.
     */
    @Test
    fun zeroBaseExponentBecomesAnIte() {
        val prog = TACProgramBuilder {
            b assign Exponent(0.asTACExpr, aS)
            assert(x)
        }
        // the resulting `Ite(a == 0, 1, 0)` is spread over two commands by the expression unfolder, so the exact
        // program can't be built here (the name of the temporary variable is allocator dependent).
        val result = testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        assertTrue(result.contains("Eq(a:bv256 0x0)"), result)
        assertTrue(result.contains("Ite("), result)
    }

    /** The identities that do hold for every value of the symbolic operand must keep firing. */
    @Test
    fun soundIdentitiesAreStillSimplified() {
        val prog = TACProgramBuilder {
            b assign Mod(aS, aS)
            c assign Mod(aS, 1.asTACExpr)
            d assign Exponent(aS, 0.asTACExpr)
            e assign Div(aS, 1.asTACExpr)
            assert(x)
        }
        val expected = TACProgramBuilder {
            b assign 0
            c assign 0
            d assign 1
            e assign aS
            assert(x)
        }
        assertEquals(
            testProgString(expected.code),
            testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        )
    }
}
