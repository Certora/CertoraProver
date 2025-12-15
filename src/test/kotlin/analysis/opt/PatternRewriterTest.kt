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

import analysis.TACProgramPrinter
import analysis.numeric.MAX_UINT
import analysis.opt.PatternRewriter.PatternHandler
import analysis.opt.intervals.IntervalsRewriter
import instrumentation.transformers.FilteringFunctions
import instrumentation.transformers.optimizeAssignments
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import sbf.tac.solanaPatternsList
import tac.Tag
import utils.ModZm.Companion.lowOnes
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.asTACExpr
import java.math.BigInteger

class PatternRewriterTest : TACBuilderAuxiliaries() {


    private fun checkStat(prog: TACProgramBuilder.BuiltTACProgram, stat: String, count: Int = 1,
                          patterns : PatternRewriter.() -> List<PatternHandler> = PatternRewriter::basicPatternsList) {
        val stats = PatternRewriter.rewriteStats(prog.code, patterns)
//        TACProgramPrinter.standard().print(PatternRewriter.rewrite(prog.code))
        assertEquals(count, stats[stat])
    }

    /**
     * Tests the pattern rewrite:
     *    `ite(cond, a xor b, 0) xor b` ==> `ite(cond, a, b)`
     * Specifically here, a = 1, and the condition is `b > 1`.
     */
    @Test
    fun test1() {
        val prog = TACProgramBuilder {
            e assign BWXOr(1.asTACExpr, bS)
            x assign Gt(bS, 1.asTACExpr)
            c assign Ite(xS, eS, 0.asTACExpr)
            d assign BWXOr(cS, 1.asTACExpr)
        }
        checkStat(prog, "xor1")
    }


    /**
     * Tests the pattern rewrite:
     *    `ite(cond, a xor b, 0) xor b` ==> `ite(cond, a, b)`
     */
    @Test
    fun test1_1() {
        val prog = TACProgramBuilder {
            c assign BWXOr(aS, bS)
            d assign Ite(xS, cS, 0.asTACExpr)
            e assign BWXOr(dS, aS)
        }
        checkStat(prog, "xor1")
    }

    /**
     * Tests the pattern rewrite:
     *   `x xor const1 == const2` ==> `x == (const1 xor const2)`
     * where const1 = 132 and const2 = 15.
     */
    @Test
    fun test2() {
        val prog = TACProgramBuilder {
            b assign BWXOr(132.asTACExpr, aS)
            x assign Eq(bS, 15.asTACExpr)
        }
        checkStat(prog, "xor2")
    }

    /**
     * Tests the pattern rewrite:
     *   `x & 0xffff == x` ==> `x <= 0xffff`
     */
    @Test
    fun test3() {
        val prog = TACProgramBuilder {
            b assign BWAnd(aS, 0xffff.asTACExpr)
            x assign Eq(aS, bS)
        }
        checkStat(prog, "maskBoundCheck", patterns = PatternRewriter::earlyPatternsList)
    }

    /**
     * Tests the pattern rewrite:
     *   `x lt 0 => x != 0`
     */
    @Test
    fun test4() {
        val prog = TACProgramBuilder {
            x assign Lt(Zero, aS)
            y assign Gt(aS, Zero)
        }
        checkStat(prog, "nonEq", 2)
    }


    @Test
    fun testXor3() {
        val prog = TACProgramBuilder {
            b assign BWXOr(1234.asTACExpr, aS)
            c assign Ite(xS, bS, Zero)
            y assign Eq(cS, 12.asTACExpr)
        }
        checkStat(prog, "xor3", 1)
    }


    @Test
    fun testNotNot() {
        val prog = TACProgramBuilder {
            y assign LNot(xS)
            z assign LNot(yS)
        }
        checkStat(prog, "not-not", 1)
    }


    @Test
    fun testBwNotBwNot() {
        val prog = TACProgramBuilder {
            b assign BWNot(aS)
            c assign BWNot(bS)
        }
        checkStat(prog, "bwnot-bwnot", 1)
    }

    @Test
    fun testMulShr() {
        val prog = TACProgramBuilder {
            b assign ShiftRightLogical(aS, 0x40.asTACExpr)
            i assign IntMul(bS, BigInteger("10000000000000000", 16).asTACExpr)
        }
        checkStat(prog, "mul-shr", 1, PatternRewriter::solanaPatternsList)
    }

    @Test
    fun testComplementMasks() {
        val prog = TACProgramBuilder {
            b assign BWAnd(aS, lowOnes(10).asTACExpr)
            c assign BWAnd(aS, (MAX_UINT - lowOnes(10)).asTACExpr)
            i assign IntAdd(bS, cS)
        }
        checkStat(prog, "complement-masks", 1, PatternRewriter::solanaPatternsList)
    }

    @Test
    fun testRedundantNarrow() {
        val prog = TACProgramBuilder {
            j assign safeMathNarrow(iS, Tag.Bit256)
            b assign safeMathNarrow(jS, Tag.Bit256)
        }
        checkStat(prog, "redundant-narrow2", 1, PatternRewriter::solanaPatternsList)
    }

    @Test
    fun testSolanaMulByConst() {
        val prog = TACProgramBuilder {
            val mask = BigInteger("ffffffffffffffff", 16).asTACExpr
            val ll = bv256Var("l")
            val final = intVar("final")

            b assign BWAnd(aS, mask)
            c assign ShiftRightLogical(aS, 0x40.asTACExpr)
            i assign IntMul(cS, 0x2710.asTACExpr)
            d assign safeMathNarrow(iS, Tag.Bit256)
            j assign IntMul(bS, 0x2710.asTACExpr)
            e assign safeMathNarrow(jS, Tag.Bit256)
            f assign BWAnd(eS,mask)
            g assign ShiftRightLogical(eS, 0x40.asTACExpr)
            k assign IntAdd(gS, dS)
            assumeExp(LAnd(Ge(kS, 0.asTACExpr), Le(kS, mask)))
            h assign safeMathNarrow(kS, Tag.Bit256)
            s assign IntMul(hS, BigInteger("10000000000000000", 16).asTACExpr)
            ll assign safeMathNarrow(sS, Tag.Bit256)
            final assign IntAdd(ll.asSym(), fS)
            x assign Ge(final.asSym(), 1.asTACExpr)
            assert(x)
        }
        val newCode = PatternRewriter.rewrite(prog.code, PatternRewriter::solanaPatternsList, repeat = 100)
            .let { IntervalsRewriter.rewrite(it, 2, false) }
            .let { optimizeAssignments(it, FilteringFunctions.NoFilter) }
        // this actually simplifies to:
        //    0: ASSUME Le(a:bv256 0x68db8bac710cb295e9e1b089a0275)
        //    1: tacTmp!t11!12:int := IntMul(a:bv256 0x2710)
        //    2: final:int := Apply(safe_math_narrow_bv256:bif tacTmp!t11!12:int)
        //    3: x:bool := Ge(final:int 0x1)
        //    4: ASSERT x:bool
        // but we just check that all bw-ands and shift-rights are gone.
        for ((_, cmd) in newCode.ltacStream()) {
            for (e in cmd.subExprs()) {
                Assertions.assertFalse {
                    e is TACExpr.BinOp.BWAnd || e is TACExpr.BinOp.ShiftRightLogical
                }
            }
        }
    }


}
