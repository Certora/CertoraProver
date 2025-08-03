/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.bytemaps

import evm.EVM_MOD_GROUP256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.isVar

class BytemapInlinerTest : TACBuilderAuxiliaries() {

    /** Checks that the [ScalarizerCalculator] detects the bases we expect it to detect */
    private fun TACProgramBuilder.BuiltTACProgram.checkRhs(label : String, expected : TACExprFactTypeChecked.() -> TACExpr) {
        val newProg = BytemapInliner.go(code, { true }, cheap = false)
        val assignD = newProg.analysisCache.graph.toCommand(ptr(label))
        assertEquals(
            TACExprFactTypeChecked(TACSymbolTable.empty()).expected(),
            (assignD as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.rhs
        )
    }

    @Test
    fun constant() {
        val prog = TACProgramBuilder {
            b assign Add(aS, 3.asTACExpr)
            label("query")
            c assign Sub(bS, aS)
            assert(False)
        }
        prog.checkRhs("query") { 3.asTACExpr }
    }

    @Test
    fun constant2() {
        val prog = TACProgramBuilder {
            b assign Add(aS, 3.asTACExpr)
            bMap1[b] assign b
            label("c")
            c assign bMap1[b]
            label("d")
            d assign Sub(cS, aS)
            assert(False)
        }
        prog.checkRhs("c") { bS }
        //prog.checkRhs("d") { 3.asTACExpr }
    }

    @Test
    fun simpleVar() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 3.asTACExpr)
            label("query")
            c assign Sub(bS, 3.asTACExpr)
            assert(False)
        }
        prog.checkRhs("query") { aS }
    }

    @Test
    fun basic() {
        val prog = TACProgramBuilder {
            bMap1[0x12a] assign b
            bMap2 assign bMap1
            label("query")
            c assign bMap2[0x12a]
            assert(False)
        }
        prog.checkRhs("query") { bS }
    }

    @Test
    fun basic2() {
        val prog = TACProgramBuilder {
            b assign Add(aS, One)
            bMap1[0x12a] assign b
            label("query")
            c assign bMap1[0x12a]
            assert(False)
        }
        prog.checkRhs("query") { bS }
    }


    @Test
    fun mapDefinitionTest() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), One, Tag.ByteMap)
            label("query")
            c assign bMap1[bS]
            assert(False)
        }
        prog.checkRhs("query") { One }
    }

    @Test
    fun longStoreTest() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), One, Tag.ByteMap)
            bMap2 assign mapDef(listOf(aS), 2.asTACExpr, Tag.ByteMap)
            bMap2[25] assign 3
            bMap3 assign TACExpr.LongStore(
                dstMap = bMap1.asSym(),
                dstOffset = 10.asTACExpr,
                srcMap = bMap2.asSym(),
                srcOffset = 20.asTACExpr,
                length = 30.asTACExpr
            )
            label("query1")
            b assign bMap3[2]
            label("query2")
            b assign bMap3[9]
            label("query3")
            b assign bMap3[10]
            label("query4")
            b assign bMap3[39]
            label("query5")
            b assign bMap3[40]
            label("query6")
            b assign bMap3[50]
            label("query7")
            b assign bMap3[15]

            assert(False)
        }
        prog.checkRhs("query1") { 1.asTACExpr }
        prog.checkRhs("query2") { 1.asTACExpr }
        prog.checkRhs("query3") { 2.asTACExpr }
        prog.checkRhs("query4") { 2.asTACExpr }
        prog.checkRhs("query5") { 1.asTACExpr }
        prog.checkRhs("query6") { 1.asTACExpr }
        prog.checkRhs("query7") { 3.asTACExpr }
    }


    @Test
    fun controlFlow() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), Zero, Tag.ByteMap)
            jump(1) {
                c assign bMap1[1]
                jump(3) {
                    label("query")
                    d assign Add(cS, One)
                    assert(False)
                }
            }
            jump(2) {
                c assign bMap1[3]
                jump(3)
            }
        }
        prog.checkRhs("query") { One }
    }

    @Test
    fun dontMiss() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), Zero, Tag.ByteMap)
            bMap1[0x100] assign 3
            assumeExp(LAnd(Ge(iS, Zero), Le(iS, 0xfffff.asTACExpr)))
            c assign Add(0x200.asTACExpr, safeMathNarrow(IntMul(32.asTACExpr, iS), Tag.Bit256))
            label("query")
            d assign bMap1[c]
            assert(False)
        }
        prog.checkRhs("query") { Zero }
    }

    @Test
    fun overriddenDef() {
        val prog = TACProgramBuilder {
            jump(1) {
                a assign 1
                jump(3) {
                    bMap1[100] assign a
                    a assign 4
                    label("query")
                    b assign bMap1[100]
                    assert(False)
                }
            }
            jump(2) {
                a assign 2
                jump(3)
            }
        }
        val newProg = BytemapInliner.go(prog.code, { true }, cheap = false)
        val assignD = newProg.analysisCache.graph.toCommand(prog.ptr("query"))
        assertTrue(assignD is TACCmd.Simple.AssigningCmd.AssignExpCmd && assignD.rhs.isVar)
    }

    @Test
    fun intStuff() {
        val prog = TACProgramBuilder {
            assumeExp(LAnd(Ge(iS, Zero), Le(iS, 0xfffff.asTACExpr)))
            b assign safeMathNarrow(iS, Tag.Bit256)
            label("query")
            c assign b
            assert(False)
        }
        prog.checkRhs("query") { bS }
    }

    @Test
    fun intStuff2() {
        val prog = TACProgramBuilder {
            i assign IntAdd(aS, bS)
            assumeExp(noAddOverflow(aS, bS))
            label("query")
            c assign safeMathNarrow(iS, Tag.Bit256)
            assert(False)
        }
        // just checking that this didn't crash.
        prog.checkRhs("query") { safeMathNarrow(iS, Tag.Bit256) }
    }


    @Test
    fun understandBranching() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(cS), Zero, Tag.ByteMap)
            jump(1) {
                a assign 200
                bMap1[100] assign 1
                // if `a assign 200` was here we should still get it, but it's harder...
                jump(3) {
                    label("query")
                    e assign bMap1[aS]
                    assert(False)
                }
            }
            jump(2) {
                a assign 100
                bMap1[200] assign 2
                jump(3)
            }
        }
        prog.checkRhs("query") { Zero }
    }

    @Test
    fun bug1() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(cS), Zero, Tag.ByteMap)
            a assign 200
            jump(1) {
                bMap1[100] assign 1
                a assign 100
                jump(2) {
                    label("query")
                    e assign bMap1[aS]
                    assert(False)
                }
            }
            jump(2)
        }
        prog.checkRhs("query") { Select(bMap1.asSym(), aS) }
    }

    @Test
    fun bug2() {
        val prog = TACProgramBuilder {
            havoc(bMap1)
            c assign Mul(aS, 32.asTACExpr)
            a assign bMap1[100]
            label("query")
            b assign Mul(aS, 32.asTACExpr)
            assert(False)
        }
        prog.checkRhs("query") { Mul(aS, 32.asTACExpr) }
    }

    @Test
    fun calculateConsts() {
        val prog = TACProgramBuilder {
            a assign b
            c assign Sub(aS, bS)
            label("query")
            d assign TACExpr.BinOp.Exponent(3.asTACExpr, cS)
            assert(False)
        }
        prog.checkRhs("query") { 1.asTACExpr }
    }

    @Test
    fun calculateConstsBug() {
        val prog = TACProgramBuilder {
            i assign IntMod(aS, EVM_MOD_GROUP256.asTACExpr)
            label("query")
            b assign safeMathNarrow(iS, Tag.Bit256)
            assert(False)
        }
        prog.checkRhs("query") { safeMathNarrow(iS, Tag.Bit256) }
    }


}
