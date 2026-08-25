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
import vc.data.*
import vc.data.tacexprutil.subs
import java.math.BigInteger

class PatternRewriterTest : TACBuilderAuxiliaries() {


    private fun checkStat(prog: TACProgramBuilder.BuiltTACProgram, stat: String, count: Int = 1,
                          patterns : PatternRewriter.() -> List<PatternHandler> = PatternRewriter::basicPatternsList) {
        val stats = PatternRewriter.rewriteStats(prog.code, patterns)
//        TACProgramPrinter.standard().print(PatternRewriter.rewrite(prog.code))
        assertEquals(count, stats[stat])
    }

    /** The values of all constants in the rewritten program. */
    private fun rewrittenConsts(
        prog: TACProgramBuilder.BuiltTACProgram,
        patterns: PatternRewriter.() -> List<PatternHandler> = PatternRewriter::basicPatternsList
    ): Set<BigInteger> =
        PatternRewriter.rewrite(prog.code, patterns).ltacStream().toList()
            .mapNotNull { it.cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd }
            .flatMap { cmd -> cmd.rhs.subs.mapNotNull { (it as? TACExpr.Sym.Const)?.s?.value }.toList() }
            .toSet()

    /** The constants appearing in the rewritten rhs of the assignments to [lhs]. */
    private fun rewrittenConstsOf(
        prog: TACProgramBuilder.BuiltTACProgram, lhs: TACSymbol.Var,
        patterns: PatternRewriter.() -> List<PatternHandler> = PatternRewriter::basicPatternsList
    ): Set<TACSymbol.Const> =
        PatternRewriter.rewrite(prog.code, patterns).ltacStream().toList()
            .mapNotNull { (it.cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.takeIf { c -> c.lhs == lhs } }
            .flatMap { cmd -> cmd.rhs.subs.mapNotNull { (it as? TACExpr.Sym.Const)?.s }.toList() }
            .toSet()

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
     * `(A - B) * ite(A > B, 1, 0)`  ~~>  `ite(A > B, A intSub B, 0)`
     */
    @Test
    fun testZeroFloorSub() {
        checkStat(TACProgramBuilder {
            x assign Gt(aS, bS)
            d assign Ite(xS, One, Zero)
            c assign Sub(aS, bS)
            e assign Mul(dS, cS)
        }, "zeroFloorSub")

        // the `lt(y, x)` spelling the comparison normalizers produce, and the other operand order
        checkStat(TACProgramBuilder {
            x assign Lt(bS, aS)
            d assign Ite(xS, One, Zero)
            c assign Sub(aS, bS)
            e assign Mul(cS, dS)
        }, "zeroFloorSub")
    }

    /** The comparison must be over the subtraction's own operands. */
    @Test
    fun testZeroFloorSubMismatchedOperands() {
        checkStat(TACProgramBuilder {
            x assign Gt(aS, gS)
            d assign Ite(xS, One, Zero)
            c assign Sub(aS, bS)
            e assign Mul(dS, cS)
        }, "zeroFloorSub", count = 0)
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

    @Test
    fun testFixedPointMultiply() {
        // Mirrors the pattern:
        //   I_hi = (X >> 14) *int Y
        //   R_lo = safeMathNarrow(0x4000000000000 *int X) & mask64
        //   I_prod = Y *int R_lo
        //   result = (safeMathNarrow(I_prod) >> 0x40) +int safeMathNarrow(I_hi)
        // Should simplify to: (X *int Y) /int 2^14
        val mask64 = BigInteger("ffffffffffffffff", 16).asTACExpr
        val c2pow50 = BigInteger("4000000000000", 16).asTACExpr
        val prog = TACProgramBuilder {
            // high part
            c assign ShiftRightLogical(aS, 0xe.asTACExpr)          // c = a >> 14
            i assign IntMul(cS, bS)                                 // i = c *int b = (a >> 14) *int b
            d assign safeMathNarrow(iS, Tag.Bit256)                 // d = safeMathNarrow(i)
            // low part
            j assign IntMul(c2pow50, aS)                            // j = 0x4000000000000 *int a
            e assign safeMathNarrow(jS, Tag.Bit256)                 // e = safeMathNarrow(j)
            f assign BWAnd(eS, mask64)                              // f = e & mask64
            k assign IntMul(bS, fS)                                 // k = b *int f
            g assign safeMathNarrow(kS, Tag.Bit256)                 // g = safeMathNarrow(k)
            h assign ShiftRightLogical(gS, 0x40.asTACExpr)          // h = g >> 0x40
            // merge
            s assign IntAdd(hS, dS)                                 // s = h +int d
        }
        checkStat(prog, "fixed-point-multiply", 1, PatternRewriter::solanaPatternsList)
    }

    @Test
    fun testConstMulSplit() {
        // Mirrors the pattern:
        //   low  = safeMathNarrow(0x4000 *int X) & mask64
        //   high = 0x10000000000000000 *int (X >> 0x32)
        //   result = safeMathNarrow(high) +int low
        // Should simplify to: X *int 0x4000
        val mask64 = BigInteger("ffffffffffffffff", 16).asTACExpr
        val c0x4000 = BigInteger("4000", 16).asTACExpr
        val c2pow64 = BigInteger.ONE.shiftLeft(64).asTACExpr
        val prog = TACProgramBuilder {
            // low part
            i assign IntMul(c0x4000, aS)                                // i = 0x4000 *int a
            b assign safeMathNarrow(iS, Tag.Bit256)                     // b = safeMathNarrow(i)
            c assign BWAnd(bS, mask64)                                  // c = b & mask64
            // high part
            d assign ShiftRightLogical(aS, 0x32.asTACExpr)              // d = a >> 0x32
            j assign IntMul(c2pow64, dS)                                // j = 2^64 *int d
            e assign safeMathNarrow(jS, Tag.Bit256)                     // e = safeMathNarrow(j)
            // merge
            s assign IntAdd(eS, cS)                                     // s = e +int c
        }
        checkStat(prog, "const-mul-split", 1, PatternRewriter::solanaPatternsList)
    }

    @Test
    fun testFixedPointMultiply2() {
        // safeMathNarrow(X *int safeMathNarrow(C *int Y)) >> K
        // where C=2^50, K=64 → result = safeMathNarrow((X *int Y) /int 2^14)
        val c2pow50 = BigInteger("4000000000000", 16).asTACExpr
        val prog = TACProgramBuilder {
            i assign IntMul(c2pow50, aS)                               // i = 2^50 *int a
            b assign safeMathNarrow(iS, Tag.Bit256)                    // b = safeMathNarrow(i)
            j assign IntMul(aS, bS)                                    // j = a *int b
            d assign safeMathNarrow(jS, Tag.Bit256)                    // d = safeMathNarrow(j)
            e assign ShiftRightLogical(dS, 0x40.asTACExpr)             // e = d >> 64
        }
        checkStat(prog, "fixed-point-multiply-2", 1, PatternRewriter::solanaPatternsList)
    }

    /**
     * `a <= const / a` ~~> `a <= sqrt(const)`, and its negation `a > const / a` ~~> `a > sqrt(const)`.
     * `sqrt(const)` really is the threshold here, also when `const` is not a perfect square.
     */
    @Test
    fun testSqrtByDiv1and2() {
        val leProg = TACProgramBuilder {
            b assign Div(8.asTACExpr, aS)
            x assign Le(aS, bS)
        }
        checkStat(leProg, "sqrtByDiv1")
        Assertions.assertTrue(BigInteger.TWO in rewrittenConsts(leProg))

        val gtProg = TACProgramBuilder {
            b assign Div(8.asTACExpr, aS)
            x assign Gt(aS, bS)
        }
        checkStat(gtProg, "sqrtByDiv2")
        Assertions.assertTrue(BigInteger.TWO in rewrittenConsts(gtProg))
    }

    /**
     * The threshold of [sqrtByDivThreshold] is the smallest `k` with `k * (k + 1) > const`, i.e., the
     * smallest `a` for which `a >= const / a` holds under floor division. It equals `sqrt(const)` only
     * when `const` is a perfect square.
     */
    @Test
    fun testSqrtByDivThreshold() {
        for ((const, threshold) in listOf(0 to 1, 1 to 1, 2 to 2, 5 to 2, 6 to 3, 8 to 3, 9 to 3, 12 to 4)) {
            assertEquals(threshold.toBigInteger(), sqrtByDivThreshold(const.toBigInteger()))
        }
    }

    /**
     * `a >= const / a` ~~> `a == 0 || a >= k`, and its negation `a < const / a` ~~> `a != 0 && a < k`.
     * For `const = 8` the threshold is 3 and not `sqrt(8) = 2` -- `2 >= 8 / 2 = 4` does not hold.
     */
    @Test
    fun testSqrtByDiv3and4() {
        val geProg = TACProgramBuilder {
            b assign Div(8.asTACExpr, aS)
            x assign Ge(aS, bS)
        }
        checkStat(geProg, "sqrtByDiv3")
        rewrittenConsts(geProg).let {
            Assertions.assertTrue(3.toBigInteger() in it)
            Assertions.assertFalse(BigInteger.TWO in it)
        }

        val ltProg = TACProgramBuilder {
            b assign Div(8.asTACExpr, aS)
            x assign Lt(aS, bS)
        }
        checkStat(ltProg, "sqrtByDiv4")
        rewrittenConsts(ltProg).let {
            Assertions.assertTrue(3.toBigInteger() in it)
            Assertions.assertFalse(BigInteger.TWO in it)
        }
    }

    /** `0 - a == const` ~~> `a == 0 - const`, where the negation of `const` is mod 2^256. */
    @Test
    fun testVyperZeroMinus() {
        for ((const, negated) in listOf(BigInteger.ZERO to BigInteger.ZERO, 5.toBigInteger() to MAX_UINT - 4.toBigInteger())) {
            val prog = TACProgramBuilder {
                b assign Sub(Zero, aS)
                x assign Eq(bS, const.asTACExpr)
            }
            checkStat(prog, "vyper-zero-minus", patterns = PatternRewriter::earlyPatternsList)
            assertEquals(
                setOf(TACSymbol.Const(negated, Tag.Bit256)),
                rewrittenConstsOf(prog, x, PatternRewriter::earlyPatternsList)
            )
        }
    }

}
