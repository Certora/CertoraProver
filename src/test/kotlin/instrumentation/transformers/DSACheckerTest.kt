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

package instrumentation.transformers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import analysis.dataflow.DsaDefAnalysis
import vc.data.asTACExpr

class DSACheckerTest : TACBuilderAuxiliaries() {

    /**
     * Two sibling def blocks of `a` flow into a join that has a third, non-defining predecessor, and each arm reads
     * `a` after defining it. The join is not clean, but no use escapes the arms, so `a` is DSA-good. The pipeline
     * produces this past `DSAToSSA` (CERT-10112), and the pre-solver `checkFinalDSA` must accept it.
     */
    @Test
    fun testSiblingDefsWithExtraJoinPredecessorAndAllUsesLocal() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump {
                a assign 1
                assumeExp(Eq(aS, 1.asTACExpr))
                jump(1)
            }
            jump {
                jumpCond(y)
                jump {
                    a assign 2
                    assumeExp(Eq(aS, 2.asTACExpr))
                    jump(1)
                }
                jump {
                    // Third predecessor of the join with no def of `a` - breaks the strict diamond.
                    jump(1)
                }
            }
            jumpDest(1) {
                nop
            }
        }
        assert(DSAChecker.nonDsaVars(prog.code).isEmpty) {
            "expected DSA-good; got ${DSAChecker.nonDsaVars(prog.code)}"
        }
    }

    /**
     * Same shape as the test above, but the join block reads `a` instead of the arms. The join still has more
     * predecessors than `a` has definitions, so `a` is `badDefForm` however it is read.
     */
    @Test
    fun testSiblingDefsWithExtraJoinPredecessorAndUseOutsideDefBlocks() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump {
                a assign 1
                jump(1)
            }
            jump {
                jumpCond(y)
                jump {
                    a assign 2
                    jump(1)
                }
                jump {
                    jump(1)
                }
            }
            jumpDest(1) {
                // Use of `a` outside any def block.
                assumeExp(Eq(aS, 1.asTACExpr))
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).badDefForm)
    }

    /**
     * The unclean join again, with every use inside the arms, but one of them before its arm's definition. With no
     * clean join to fall back on, that is a shape violation.
     */
    @Test
    fun testSiblingDefsWithExtraJoinPredecessorAndArmLocalUseBeforeDef() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump {
                assumeExp(Eq(aS, 1.asTACExpr))
                a assign 1
                jump(1)
            }
            jump {
                jumpCond(y)
                jump {
                    a assign 2
                    assumeExp(Eq(aS, 2.asTACExpr))
                    jump(1)
                }
                jump {
                    jump(1)
                }
            }
            jumpDest(1) {
                nop
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).badDefForm)
    }

    /**
     * One arm was cut into a leaf - after an `assume false` - and still defines `a`, which it reads after defining
     * it. Its definition reaches nothing outside it, so the other arm's is the only one going on, read below it like
     * a single definition.
     */
    @Test
    fun testArmCutIntoALeafIsSetAside() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                a assign 1
                assumeExp(Eq(aS, 2.asTACExpr))
            }
            jump {
                a assign 2
                jump(1) {
                    assumeExp(Eq(aS, 2.asTACExpr))
                }
            }
        }
        assert(DSAChecker.nonDsaVars(prog.code).isEmpty) {
            "expected DSA-good; got ${DSAChecker.nonDsaVars(prog.code)}"
        }
    }

    /** A leaf defining `a` below another definition of it: `a` is assigned twice on that path. */
    @Test
    fun testLeafDefinitionBelowAnotherDefinition() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                a assign 2
                assumeExp(Eq(aS, 2.asTACExpr))
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).badDefForm)
    }

    /**
     * A leaf defining `a` below the join of two arms defining it: on the way through either arm, `a` is assigned
     * twice. The leaf is not dominated by either arm's definition, only reached from both through the join.
     */
    @Test
    fun testLeafDefinitionBelowAJoin() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump {
                a assign 1
                jump(1) {
                    jumpCond(y)
                    jump {
                        a assign 3
                        assumeExp(Eq(aS, 3.asTACExpr))
                    }
                    jump {
                        assumeExp(Eq(aS, 2.asTACExpr))
                    }
                }
            }
            jump {
                a assign 2
                jump(1)
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).badDefForm)
    }

    /** The cut arm reads `a` before its own definition, which no other definition reaches: undefined there. */
    @Test
    fun testLeafReadBeforeItsDefinition() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                assumeExp(Eq(aS, 1.asTACExpr))
                a assign 1
            }
            jump {
                a assign 2
                jump(1) {
                    assumeExp(Eq(aS, 2.asTACExpr))
                }
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).useBeforeDef)
    }

    /** A clean diamond, read below the join: the shape every DSA-dependent analysis relies on. */
    @Test
    fun testCleanDiamondReadBelowTheJoin() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                a assign 1
                jump(1)
            }
            jump {
                a assign 2
                jump(1)
            }
            jumpDest(1) {
                assumeExp(Eq(aS, 1.asTACExpr))
            }
        }
        assert(DSAChecker.nonDsaVars(prog.code).isEmpty) {
            "expected DSA-good; got ${DSAChecker.nonDsaVars(prog.code)}"
        }
    }

    /**
     * The same clean diamond, but one arm reads `a` after defining it. That is allowed: the read sees that arm's
     * definition, which precedes it in the same block. It is what [analysis.opt.intervals.IntervalsRewriter]
     * produces when it carries a fact the join assumed back to the arm that decides it. What it costs is that
     * [DsaDefAnalysis] must answer such a read with that arm's definition alone, not with every arm's.
     */
    @Test
    fun testCleanDiamondWithAnArmLocalUse() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                a assign 1
                assumeExp(Eq(aS, 1.asTACExpr))
                jump(1)
            }
            jump {
                a assign 2
                jump(1)
            }
            jumpDest(1) {
                nop
            }
        }
        assert(DSAChecker.nonDsaVars(prog.code).isEmpty) {
            "expected DSA-good; got ${DSAChecker.nonDsaVars(prog.code)}"
        }
    }

    /** An arm-local read still has to come after that arm's own definition. */
    @Test
    fun testArmLocalUseBeforeTheArmsDefinition() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                assumeExp(Eq(aS, 1.asTACExpr))
                a assign 1
                jump(1)
            }
            jump {
                a assign 2
                jump(1)
            }
            jumpDest(1) {
                nop
            }
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).useBeforeDef)
    }

    /** A variable that is read and never assigned anywhere is undefined at that read. */
    @Test
    fun testReadButNeverAssigned() {
        val prog = TACProgramBuilder {
            havoc(x)
            b assign Add(aS, 1.asTACExpr)
            assert(x)
        }
        assertEquals(setOf(a), DSAChecker.nonDsaVars(prog.code).useBeforeDef)
    }
}
