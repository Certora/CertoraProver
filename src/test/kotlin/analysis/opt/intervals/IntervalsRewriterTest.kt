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

import config.Config
import config.ConfigScope
import config.DestructiveOptimizationsModeEnum
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_INT256
import evm.MAX_EVM_UINT256
import evm.twoToThe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import utils.*
import vc.data.*
import kotlin.reflect.KClass

class IntervalsRewriterTest : TACBuilderAuxiliaries() {

    private infix fun KClass<*>.too(count: Int) =
        this.java.canonicalName to count

    private fun TACProgramBuilder.BuiltTACProgram.checkStats(vararg statsToCheck: Pair<String, Int>) {
        val ic = IntervalsRewriter(code, false, preserve = { false }, calcJumpPostConditions = true)
        ic.rewrite()
//        println(ic.debug())
        val stats = ic.statsForTest()
//        println(stats.toString("STATS").yellow)
        for ((name, count) in statsToCheck) {
            assertEquals(count, stats[name])
        }
    }

    @Test
    fun testEraseAll() {
        // tautology!
        val prog = TACProgramBuilder {
            havoc(a)
            b assign BWAnd(aS, 0xffff.asTACExpr)
            x assign Eq(aS, bS)
            assume(x)
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
    }


    @Test
    fun testMask() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign BWAnd(aS, 0xffff.asTACExpr)
            x assign Eq(aS, bS)
            assume(x)
            assert(False)
        }
        prog.checkStats(
            TACExpr.BinOp.BWAnd::class too 1
        )
    }

    @Test
    fun testSignExtend() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign SignExtend(One, aS)
            x assign Eq(aS, bS)
            assume(x)
            assert(z)
        }
        prog.checkStats(
            TACExpr.BinOp.SignExtend::class too 1
        )
    }

    @Test
        /** This one removes the div, and changes the Mul to IntMul */
    fun testMulOverflow() {
        val prog = TACProgramBuilder {
            assert(x)
            jump {
                x assign Gt(aS, Zero)
                assume(x)
                y assign Ge(Div(0xffff.asTACExpr, aS), 0x56.asTACExpr)
                assume(y)
                d assign Mul(aS, 0x56.asTACExpr)
                z assign Le(dS, 0xffff.asTACExpr)
                assert(z)
            }
        }
        prog.checkStats(
            "erased-blocks" to 1
        )
    }

    @Test
    fun testIte() {
        val prog = TACProgramBuilder {
            c assign Ite(xS, aS, bS)
            assumeExp(xS)
            assert(False)
        }
        prog.checkStats(
            TACExpr.TernaryExp.Ite::class too 1
        )
    }

    @Test
    fun testIntAdd() {
        val prog = TACProgramBuilder {
            d assign Add(aS, cS)
            assumeExp(Eq(cS, Zero))
            assert(False)
        }
        prog.checkStats(
            TACExpr.Vec.Add.Binary::class too 1
        )
    }

    @Test
    fun testNotSlt() {
        val prog = TACProgramBuilder {
            assumeExp(LNot(Slt(aS, 32.asTACExpr)))
            b assign a
            x assign LNot(Gt(aS, twoToThe(255).asTACExpr))
            assert(x)
            y assign LNot(Lt(aS, 32.asTACExpr))
            assert(y)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
    }


    @Test
    fun testJump() {
        val prog = TACProgramBuilder {
            assume(x)
            jumpCond(x)
            jump(1) {
                assert(False)
            }
            jump(2) {
                assert(False)
            }
        }
        prog.checkStats(
            "iJump->Jump" to 1,
            "erased-blocks" to 1
        )
    }


    @Test
    fun testDeadBlock() {
        val prog = TACProgramBuilder {
            assume(x)
            jump(1) {
                y assign LNot(xS)
                assume(y)
                assert(z)
            }
            jump(2) {
                assert(z)
            }
        }
        prog.checkStats(
            "iJump->Jump" to 1,
            "erased-blocks" to 1
        )
    }


    @Test
    fun testErasingBlocks() {
        val prog = TACProgramBuilder {
            x assign True
            jump(1) {
                nop
                assumeExp(LNot(xS))
                assert(y)
                jump(3) {
                    nop
                    a assign b
                    assert(z)
                    a assign c
                }
            }
            jump(2) {
                assert(z)
                assumeExp(xS)
                jump(3)
            }
        }
        prog.checkStats(
            "erased-blocks" to 1
        )
    }


    @Test
    fun test9() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Lt(aS, 1000.asTACExpr)
            jumpCond(x)
            jump(1) {
                assumeExp(False)
            }
            jump(2) {
                assert(z)
            }
        }
        prog.checkStats(
            "erased-blocks" to 1
        )
    }

    @Test
    fun testMulMod() {
        val prog = TACProgramBuilder {
            havoc(a)
            x assign Lt(aS, 1000.asTACExpr)
            jumpCond(x)
            jump(1) {
                assumeExp(False)
            }
            jump(2) {
                assert(z)
            }
        }
        prog.checkStats(
            "erased-blocks" to 1
        )
    }

    @Test
    fun testSignedMul() {
        val prog = TACProgramBuilder {
            assumeExp(Lt(aS, 1000.asTACExpr))
            assumeExp(Gt(aS, Zero))
            assumeExp(Gt(bS, (EVM_MOD_GROUP256 - 100).asTACExpr))

            i assign twosUnwrap(aS)
            j assign twosUnwrap(bS)

            c assign twosWrap(iS)
            d assign twosWrap(jS)

            e assign Mul(aS, bS)
            assert(False)
        }
        prog.checkStats(
            "unwrap_twos_complement_256" to 2,
            "wrap_twos_complement_256" to 2,
            "Mul->SignedIntMul" to 1
        )
    }


    @Test
    fun testDontErase() {
        val prog = TACProgramBuilder {
            i assign 5
            assert(xS)
            j assign i
            jump(1) {
                nop
                assert(False)
            }
        }
        prog.checkStats(
            "erased-blocks" to 0
        )
    }

    @Test
    fun testBwor() {
        val prog = TACProgramBuilder {
            b assign BWOr(0xf0.asTACExpr, aS)
            assumeExp(Lt(aS, 0x10.asTACExpr))
            assert(False)
        }
        prog.checkStats(
            TACExpr.BinOp.BWOr::class too 1
        )
        val prog2 = TACProgramBuilder {
            b assign BWOr(0xf0.asTACExpr, aS)
            assumeExp(Lt(aS, 0x20.asTACExpr))
            assert(False)
        }
        prog2.checkStats(
            TACExpr.BinOp.BWOr::class too 0
        )
    }

    @Test
    fun testBwor2() {
        val prog = TACProgramBuilder {
            c assign BWOr(aS, bS)
            assumeExp(Le(cS, 0xff.asTACExpr))
            x assign LAnd(Le(aS, 0xff.asTACExpr), Le(bS, 0xff.asTACExpr))
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
    }

    @Test
    fun testAssumeFalse() {
        val prog = TACProgramBuilder {
            i assign 5
            assumeExp(False)
            x assign Gt(iS, 10.asTACExpr)
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
    }


    @Test
    fun testLastAssert() {
        @OptIn(Config.DestructiveOptimizationsOption::class)
        ConfigScope(Config.DestructiveOptimizationsMode, DestructiveOptimizationsModeEnum.ENABLE).use {
            val prog = TACProgramBuilder {
                y assign Gt(aS, 50.asTACExpr)
                jumpCond(y)
                jump {
                    jump(3) {
                        x assign Lt(aS, 100.asTACExpr)
                        assert(x)
                    }
                }
                jump {
                    jump(3)
                }
            }
            prog.checkStats(
                "erased-blocks" to 1
            )
        }
    }


    @Test
    fun testAddOverflowCheck() {
        val prog = TACProgramBuilder {
            assumeExp(Lt(aS, 1000.asTACExpr))
            assumeExp(Lt(bS, MAX_EVM_INT256.asTACExpr))
            x assign noAddOverflow(aS, bS)
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
        val prog2 = TACProgramBuilder {
            assumeExp(Lt(aS, 1000.asTACExpr))
            assumeExp(Lt(bS, MAX_EVM_UINT256.asTACExpr))
            x assign noAddOverflow(aS, bS)
            assert(x)
        }
        prog2.checkStats(
            "erased-blocks" to 0
        )
    }

    @Test
    fun testMulOverflowCheck() {
        val prog = TACProgramBuilder {
            assumeExp(Lt(aS, 1000.asTACExpr))
            assumeExp(Lt(bS, 1000.asTACExpr))
            x assign noMulOverflow(aS, bS)
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
        val prog2 = TACProgramBuilder {
            assumeExp(Lt(aS, 3.asTACExpr))
            assumeExp(Lt(bS, MAX_EVM_UINT256.asTACExpr))
            x assign noMulOverflow(aS, bS)
            assert(x)
        }
        prog2.checkStats(
            "erased-blocks" to 0
        )
    }

    @Test
    fun testMulOverflowCheckReversed() {
        val prog = TACProgramBuilder {
            assumeExp(Ge(aS, 256.asTACExpr))
            x assign noMulOverflow(aS, bS)
            y assign Lt(bS, (EVM_MOD_GROUP256 / 256.toBigInteger()).asTACExpr)
            assume(x)
            assert(y)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
    }

    @Test
    fun testSMulChecks() {
        val prog = TACProgramBuilder {
            // this means that `a` is an int with (20 + 1) bytes, i.e., int168
            assumeExp(Eq(SignExtend(20.asTACExpr, aS), aS))
            assumeExp(Eq(SignExtend(10.asTACExpr, bS), bS))
            x assign noSMulOverAndUnderflow(aS, bS)
            assert(x)
        }
        prog.checkStats(
            "ALL-GONE" to 1
        )
        val prog2 = TACProgramBuilder {
            assumeExp(Eq(SignExtend(21.asTACExpr, aS), aS))
            assumeExp(Eq(SignExtend(10.asTACExpr, bS), bS))
            x assign noSMulOverAndUnderflow(aS, bS)
            assert(x)
        }
        prog2.checkStats(
            "erased-blocks" to 0
        )
    }

    @Test
    fun testDisjunctiveConstraints() {
        // checks that reading disjunctive ranges works (before `calcOneVarExpression`, the assume would be duplicated.
        val prog = TACProgramBuilder {
            assumeExp(LOr(Le(aS, 20.asTACExpr), Ge(aS, 30.asTACExpr)))
            assert(False)
        }
        val newProg = IntervalsRewriter.rewrite(prog.code, 1, false)
        assertEquals(prog.code.code, newProg.code)
    }

    /** This checks the fix to a bug, where assumes on preserved variables were duplicated */
    @Test
    fun testPreserve() {
        val m40 = TACKeyword.MEM64.toVar()
        val prog = TACProgramBuilder {
            assumeExp(Le(m40.asSym(), 256.asTACExpr))
            assert(False)
        }
        val newProg = IntervalsRewriter.rewrite(prog.code, 1, false)
        assertEquals(2, newProg.ltacStream().count())
    }

    @Test
    fun testPostJump() {
        val prog = TACProgramBuilder {
            x assign Le(aS, 255.asTACExpr)
            jumpCond(x)
            jump(1) {
                b assign BWAnd(aS, 255.asTACExpr)
                z assign Le(bS, aS)
                assert(z)
            }
            jump(2) {
                assert(False)
            }
        }
        prog.checkStats(
            TACExpr.BinOp.BWAnd::class too 1
        )
    }
}

