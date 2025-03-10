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
import analysis.numeric.maxUint
import analysis.opt.intervals.ExtBig.Companion.MaxUInt
import analysis.opt.intervals.ExtBig.Companion.One
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Interval.Companion.IZero
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFull256
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import analysis.opt.intervals.IntervalsCalculator.Companion.printIntervals
import analysis.opt.intervals.IntervalsCalculator.Spot
import analysis.split.Ternary.Companion.bwNot
import evm.MAX_EVM_UINT256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import utils.Color.Companion.blue
import utils.Color.Companion.green
import utils.Color.Companion.redBg
import utils.Color.Companion.white
import utils.Color.Companion.yellow
import utils.ModZm.Companion.lowOnes
import utils.ModZm.Companion.to2s
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACSymbol
import vc.data.asTACExpr
import java.math.BigInteger
import analysis.opt.intervals.Interval.Companion as I
import analysis.opt.intervals.Intervals.Companion as S

class IntervalsCalculatorTest : TACBuilderAuxiliaries() {


    private fun printOut(prog: TACProgramBuilder.BuiltTACProgram) {
        val g = prog.code.analysisCache.graph

        val ic = IntervalsCalculator(prog.code, preserve = { false })
        val h = ic.hyperGraphForTest()

        println("\n\n${"EDGES".redBg}\n------------")
        println(h.edgesString())

        println("\n\n${"MAP".redBg}\n------------")
        for (spot in h.vertices) {
            val name = when (spot) {
                is Spot.Aux -> "Aux"
                is Spot.Expr -> "${spot.ptr.yellow}-EXPR-${spot.expr}"
                is Spot.Lhs -> "${spot.ptr.blue}-${g.toCommand(spot.ptr).getLhs().white}"
            }
            println("$name := ${h.get(spot).green}")
        }

        println("\n\n${"GRAPH".redBg}\n------------")
        ic.g.vertices.forEach { b ->
            val succs = ic.g.successors[b]
            val x = ic.erasedFrom[b]
                .takeIf {
                    g.elab(b).commands.lastIndex != it
                }
                ?.let { "[..$it]" }
                .orEmpty()
            println("$b$x := $succs")
        }

        println("\n")

        printIntervals(prog.code)
    }

    interface AssertIt
    data class Rhs(val v: TACSymbol.Var, val where: String, val expected: Intervals) : AssertIt
    data class Lhs(val where: String, val expected: Intervals) : AssertIt

    private fun TACProgramBuilder.BuiltTACProgram.assertStuff(
        // who, where (rhs), expected:
        vararg toCheck: AssertIt,
    ) {
        val ic = IntervalsCalculator(code, preserve = { false }, calcJumpPostConditions = true)
        toCheck.forEach {
            when (it) {
                is Lhs -> {
                    val (where, i) = it
                    assertEquals(i, ic.getLhs(ptr(where)))
                }

                is Rhs -> {
                    val (v, where, i) = it
                    assertEquals(i, ic.getS(ptr(where), v))
                }
            }
        }
    }


    @Test
    fun testBasic() {
        val prog = TACProgramBuilder {
            havoc(a)
            label("label")
            x assign Gt(aS, 0.asTACExpr)
            assume(x)
            assert(False)
        }
        prog.assertStuff(
            Rhs(a, "label", S(One, MaxUInt))
        )
    }

    @Test
    fun testBasic2() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Lt(aS, 1000.asTACExpr)
            assume(x)
            label("label")
            b assign Mul(100.asTACExpr, aS)
            nop
            assert(False)
        }
        prog.assertStuff(
            Rhs(a, "label", S(0, 999)),
            Lhs("label", S(0, 99_900))
        )
    }

    @Test
    fun test0() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign LAnd(Gt(aS, 0.asTACExpr), Lt(aS, 10.asTACExpr))
            assume(x)
            label("label")
            b assign Div(20.asTACExpr, aS)
            y assign Lt(bS, 5.asTACExpr)
            assume(y)
            label("d")
            d assign Mul(100.asTACExpr, aS)
            assert(False)
        }
        prog.assertStuff(
            Rhs(a, "label", S(5, 9)),
            Lhs("label", S(2, 4)),
            Lhs("d", S(500, 900))
        )
    }

    @Test
    fun testAdd() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Gt(aS, 0.asTACExpr)
            assume(x)
            label("label")
            b assign Add(aS, 100.asTACExpr)
            assert(False)
        }
        prog.assertStuff(
            Rhs(a, "label", SFull256.delete(0)),
            Lhs("label", SFull256.delete(100)),
        )
    }


    @Test
    fun testMulOverflow1() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Gt(aS, 0.asTACExpr)
            assume(x)
            y assign Gt(Div(0xffff.asTACExpr, aS), 0x56.asTACExpr)
            assume(y)
            d assign Mul(aS, 0x56.asTACExpr)
            label("label")
            z assign Lt(dS, 0xffff.asTACExpr)
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", STrue),
        )
    }

    @Test
    fun testSignExtend() {
        val prog = TACProgramBuilder {
            label("label")
            havoc(a)
            b assign SignExtend(1.asTACExpr, aS)
            assumeExp(Eq(aS, bS))
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(BigInteger.ZERO, lowOnes(15), bwNot(lowOnes(15)), maxUint))
        )
    }

    @Test
    fun testBwAnd() {
        val prog = TACProgramBuilder {
            havoc(a)
            label("label")
            b assign BWAnd(0xffff.asTACExpr, aS)
            assumeExp(Eq(aS, bS))
            assert(False)
        }
        prog.assertStuff(
            Rhs(a, "label", S(BigInteger.ZERO, lowOnes(16)))
        )
    }

    @Test
    fun testNegativeMul() {
        val prog = TACProgramBuilder {
            havoc(a)
            label("label")
            // this is actually -1 * a
            b assign Mul(MAX_EVM_UINT256.asTACExpr, aS)
            assumeExp(Le(aS, 10.asTACExpr))
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(IZero, I((-10).to2s(), MAX_EVM_UINT256)))
        )
    }


    @Test
    fun testRepeatedAssignment() {
        val prog = TACProgramBuilder {
            label("label1")
            x assign Lt(1.asTACExpr, aS)
            label("label2")
            x assign LNot(xS)
            assume(x)
            assert(False)
        }
        prog.assertStuff(
            Lhs("label1", SFalse),
            Rhs(x, "label2", SFalse),
            Lhs("label2", STrue)
        )
    }

    @Test
    fun testShiftRightLogical() {
        val prog = TACProgramBuilder {
            label("label")
            b assign ShiftRightLogical(aS, 2.asTACExpr)
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(BigInteger.ZERO, lowOnes(254))),
        )
    }

    @Test
    fun testShiftLeft() {
        val prog = TACProgramBuilder {
            assumeExp(Le(aS, 100.asTACExpr))
            label("label")
            b assign ShiftLeft(aS, 4.asTACExpr)
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(0, 1600))
        )
    }

    @Test
    fun testWrap() {
        val prog = TACProgramBuilder {
            label("label")
            a assign twosWrap(iS)
            assumeExp(
                LAnd(Gt(iS, (-100).asTACExpr), Lt(iS, 100.asTACExpr)),
            )
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(I(0, 99), I((-99).to2s(), MAX_EVM_UINT256)))
        )
    }

    @Test
    fun testUnwrap() {
        val prog = TACProgramBuilder {
            label("label")
            i assign twosUnwrap(aS)
            assumeExp(
                LOr(
                    LAnd(Ge(aS, Zero), Lt(aS, 100.asTACExpr)),
                    LAnd(Gt(aS, (-100).to2s().asTACExpr), Lt(aS, EVM_MOD_GROUP))
                )
            )
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(-99, 99))
        )
    }


    @Test
    fun testShiftRight() {
        val prog = TACProgramBuilder {
            label("label")
            a assign ShiftRightLogical(MAX_EVM_UINT256.asTACExpr, bS)
            assumeExp(LOr(Eq(bS, 1.asTACExpr), Eq(bS, 2.asTACExpr)))
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(lowOnes(254), lowOnes(255)))
        )
    }


    @Test
    fun testIte() {
        val prog = TACProgramBuilder {
            label("label")
            y assign Ite(xS, xS, zS)
            assume(y)
            label("label2")
            c assign Ite(yS, aS, bS)
            assumeExp(Lt(aS, 100.asTACExpr))
            assert(False)
        }
        prog.assertStuff(
            Rhs(x, "label", SFullBool),
            Rhs(z, "label", SFullBool),
            Lhs("label", STrue),
            Rhs(a, "label2", S(0, 99))
        )
    }

    @Test
    fun testIte2() {
        val prog = TACProgramBuilder {
            label("label")
            c assign Ite(xS, aS, bS)
            assumeExp(Lt(aS, 100.asTACExpr))
            assumeExp(Gt(bS, 100.asTACExpr))
            assumeExp(Gt(cS, 200.asTACExpr))
            assert(False)
        }

        prog.assertStuff(
            Lhs("label", S(201.toBigInteger(), MAX_UINT)),
            Rhs(a, "label", S(0, 99)),
            Rhs(b, "label", S(201.toBigInteger(), MAX_UINT)),
            Rhs(x, "label", SFalse)
        )
    }

    @Test
    fun testNoConvergence() {
        val prog = TACProgramBuilder {
            assumeExp(Eq(bS, Add(aS, 1.asTACExpr)))
            assumeExp(Eq(aS, Add(bS, 1.asTACExpr)))
            label("label")
            c assign dS
            assert(False)
        }
        // just checks a and b don't cause up to loop endlessly.
        prog.assertStuff(
            Lhs("label", SFull256)
        )
    }

    @Test
    fun testAssumeExpHandler() {
        val prog = TACProgramBuilder {
            label("label")
            assumeExp(
                LOr(
                    Eq(iS, 1.asTACExpr),
                    Lt(iS, (-100).asTACExpr),
                    LAnd(Ge(100.asTACExpr, iS), Le(50.asTACExpr, iS))
                )
            )
            assert(False)
        }
        prog.assertStuff(
            Rhs(i, "label", S(I(MInf, (-101).asExtBig), I(1), I(50, 100)))
        )
    }

    @Test
    fun testBackwardSignextend() {
        val prog = TACProgramBuilder {
            label("label")
            havoc(a)
            assumeExp(Le(aS, 127.asTACExpr))
            b assign SignExtend(Zero, aS)
            assumeExp(Lt(bS, 10.asTACExpr))
            assert(False)
        }
        prog.assertStuff(
            Lhs("label", S(0, 9))
        )
    }

    @Test
    fun testJumpCondUnderstanding() {
        val prog = TACProgramBuilder {
            x assign Lt(aS, 100.asTACExpr)
            jumpCond(x)
            jump(1) {
                y assign Ge(aS, 100.asTACExpr)
                label("label1")
                assert(y)
            }
            jump(2) {
                z assign LOr(xS, Lt(aS, 100.asTACExpr))
                label("label2")
                assert(z)
            }
        }
        prog.assertStuff(
            Rhs(y, "label1", SFalse),
            Rhs(z, "label2", SFalse)
        )
    }

    @Test
    fun testJumpCondUnderstanding2() {
        val prog = TACProgramBuilder {
            jump(1) {
                x assign Eq(aS, One)
                jumpCond(x)
                jump(2) {
                    y assign Eq(aS, One)
                    label("label")
                    assert(y)
                }
                jump(3) {
                    assert(False)
                }
            }
            jump(2)
        }
        prog.assertStuff(
            Rhs(y, "label", SFullBool),
        )
    }

    @Test
    fun testJumpCondUnderstanding3() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
            }
            jump(2) {
                x assign True
                label("label")
                z assign x
                assert(y)
            }
        }
        prog.assertStuff(
            Lhs("label", STrue),
        )
    }

    @Test
    fun testJumpCondReassignment() {
        val prog = TACProgramBuilder {
            x assign Eq(aS, 0.asTACExpr)
            a assign b
            jumpCond(x)
            jump(1) {
                label("label")
                y assign Eq(aS, 0.asTACExpr)
                assert(y)
            }
            jump(2) {
                assert(False)
            }
        }
        prog.assertStuff(
            Lhs("label", SFullBool),
        )
    }

}
