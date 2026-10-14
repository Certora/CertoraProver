/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package analysis.dataflow

import analysis.CmdPointer
import datastructures.stdcollections.*
import instrumentation.transformers.DSAChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.BuiltTACProgram
import vc.data.TACSymbol
import vc.data.asTACExpr

/**
 * Tests for [DsaDefAnalysis]: on programs in the diamond-DSA form it assumes, its table lookups must give the same
 * reaching definitions as [StrictDefAnalysis]'s dataflow.
 */
class DsaDefAnalysisTest : TACBuilderAuxiliaries() {

    /** asserts that the defs of [v] reaching the command labelled [queryPoint] are exactly the labelled [expected] */
    private fun BuiltTACProgram.assertDef(v: TACSymbol.Var, queryPoint: String, vararg expected: String) {
        assertEquals(
            expected.map { ptr(it) }.toSet(),
            code.analysisCache.dsaDef.defSitesOf(v, ptr(queryPoint)),
        )
    }

    @Test
    fun singleDef() {
        val prog = TACProgramBuilder {
            havoc(x)
            b assign 7
            label("def")
            a assign 1
            label("sameBlock")
            c assign Add(aS, bS)
            jump(1) {
                label("laterBlock")
                d assign Add(aS, 1.asTACExpr)
                assert(x)
            }
        }
        assertEquals(setOf(prog.ptr("def")), prog.code.analysisCache.dsaDef.defSitesOf(a))
        prog.assertDef(a, "sameBlock", "def")
        prog.assertDef(a, "laterBlock", "def")
    }

    /**
     * A read inside a defining arm is reached by that arm's definition alone — the sibling's cannot reach it. This
     * is the one place the answer depends on where the question is asked.
     */
    @Test
    fun armLocalRead() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump(1) {
                label("thenDef")
                a assign 1
                label("thenRead")
                b assign Add(aS, 1.asTACExpr)
                jump(3)
            }
            jump(2) {
                label("elseDef")
                a assign 2
                jump(3)
            }
            jumpDest(3) {
                label("belowJoin")
                c assign Add(aS, 1.asTACExpr)
                assert(x)
            }
        }
        DSAChecker.checkDSA(prog.code)
        prog.assertDef(a, "thenRead", "thenDef")
        prog.assertDef(a, "belowJoin", "thenDef", "elseDef")
        assertEquals(
            prog.code.analysisCache.strictDef.defSitesOf(a, prog.ptr("thenRead")),
            prog.code.analysisCache.dsaDef.defSitesOf(a, prog.ptr("thenRead")),
        )
    }

    @Test
    fun cleanDiamond() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                label("thenDef")
                c assign 0
                d assign Add(aS, 1.asTACExpr)
                jump(3)
            }
            jump(2) {
                label("elseDef")
                c assign Add(aS, bS)
                jump(3)
            }
            jumpDest(3) {
                label("atJoin")
                e assign Add(cS, 1.asTACExpr)
                jump(4) {
                    label("belowJoin")
                    f assign Add(cS, 2.asTACExpr)
                    assert(x)
                }
            }
        }
        assertEquals(
            setOf(prog.ptr("thenDef"), prog.ptr("elseDef")),
            prog.code.analysisCache.dsaDef.defSitesOf(c),
        )
        // DSA allows `c` to be read only below the join, where every arm's definition reaches it
        prog.assertDef(c, "atJoin", "thenDef", "elseDef")
        prog.assertDef(c, "belowJoin", "thenDef", "elseDef")
    }

    @Test
    fun neverAssigned() {
        val prog = TACProgramBuilder {
            havoc(b)
            havoc(x)
            label("query")
            c assign Add(aS, bS)
            assert(x)
        }
        assertEquals(setOf<CmdPointer>(), prog.code.analysisCache.dsaDef.defSitesOf(a))
        prog.assertDef(a, "query")
    }

    @Test
    fun threeWayJoin() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                label("def1")
                c assign 1
                jump(4)
            }
            jump {
                jumpCond(y)
                jump(2) {
                    label("def2")
                    c assign 2
                    jump(4)
                }
                jump(3) {
                    label("def3")
                    c assign 3
                    jump(4)
                }
            }
            jumpDest(4) {
                label("query")
                e assign Add(cS, 1.asTACExpr)
                assert(x)
            }
        }
        prog.assertDef(c, "query", "def1", "def2", "def3")
    }

    @Test
    fun agreesWithStrictDefAnalysisOnNestedDiamonds() {
        // two towers, each a diamond inside the arm of an outer diamond, joining again below
        val prog = TACProgramBuilder {
            a assign 3
            b assign 4
            x assign Lt(aS, bS)
            jumpCond(x)
            jump {
                y assign Eq(aS, bS)
                jumpCond(y)
                jump(1) {
                    c assign 0
                    jump(3)
                }
                jump(2) {
                    c assign Mul(aS, bS)
                    jump(3)
                }
                jumpDest(3) {
                    e assign Add(cS, 1.asTACExpr)
                    jump(7)
                }
            }
            jump(7)
            jumpDest(7) {
                jumpCond(x)
                jump {
                    z assign Eq(aS, bS)
                    jumpCond(z)
                    jump(4) {
                        d assign 0
                        jump(6)
                    }
                    jump(5) {
                        d assign Mul(aS, bS)
                        jump(6)
                    }
                    jumpDest(6) {
                        f assign Add(dS, 1.asTACExpr)
                        g assign Add(fS, 1.asTACExpr)
                        jump(8)
                    }
                }
                jump(8)
                jumpDest(8) {
                    assert(x)
                }
            }
        }
        // the agreement is claimed only where the precondition holds, so pin that the shape really is DSA
        DSAChecker.checkDSA(prog.code)
        val dsaDef = prog.code.analysisCache.dsaDef
        val strict = prog.code.analysisCache.strictDef
        for ((ptr, cmd) in prog.code.analysisCache.graph.commands) {
            for (v in cmd.getFreeVarsOfRhs()) {
                val strictSites = strict.defSitesOf(v, ptr)
                assertEquals(listOf<CmdPointer?>(), strictSites.filter { it == null }, "$v at $ptr may be undefined")
                assertEquals(strictSites, dsaDef.defSitesOf(v, ptr), "$v at $ptr")
            }
        }
    }

    /**
     * Two arms define `a` and read it themselves, and the join has a third predecessor that does not define it -
     * the shape optimizations past [instrumentation.transformers.DSAToSSA] leave behind. With no read below the
     * join, the join's other predecessors do not matter: every read is one its own arm's definition answers.
     */
    @Test
    fun armLocalReadsAtAnUncleanJoin() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                label("def1")
                a assign 1
                label("use1")
                b assign Add(aS, 1.asTACExpr)
                jump(4)
            }
            jump(2) {
                jumpCond(y)
                jump(3) {
                    label("def2")
                    a assign 2
                    label("use2")
                    c assign Add(aS, 1.asTACExpr)
                    jump(4)
                }
                jump(5) {
                    jump(4)
                }
            }
            jumpDest(4) {
                assert(x)
            }
        }
        DSAChecker.checkDSA(prog.code)
        prog.assertDef(a, "use1", "def1")
        prog.assertDef(a, "use2", "def2")
        val strict = prog.code.analysisCache.strictDef
        assertEquals(setOf(prog.ptr("def1")), strict.defSitesOf(a, prog.ptr("use1")))
        assertEquals(setOf(prog.ptr("def2")), strict.defSitesOf(a, prog.ptr("use2")))
    }

    /**
     * An arm was cut into a leaf and still defines `a`: its definition answers the reads in it, and below the other
     * arm only that arm's definition does.
     */
    @Test
    fun armCutIntoALeaf() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                label("def1")
                a assign 1
                label("use1")
                assumeExp(Eq(aS, 2.asTACExpr))
            }
            jump {
                label("def2")
                a assign 2
                jump(1) {
                    label("use2")
                    assumeExp(Eq(aS, 2.asTACExpr))
                }
            }
        }
        DSAChecker.checkDSA(prog.code)
        prog.assertDef(a, "use1", "def1")
        prog.assertDef(a, "use2", "def2")
    }
}
