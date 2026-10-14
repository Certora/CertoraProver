/*
 *     The Certora Prover
 *     Copyright (C) 2026 Certora Ltd.
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

package analysis.dataflow

import analysis.CmdPointer
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*
import vc.data.TACProgramBuilder.BuiltTACProgram

/**
 * Tests for [BranchAwareGlobalValueNumbering]. The shapes are the twin towers the analysis exists for: the same
 * method inlined twice gives two diamonds over the same condition value, and both their arms and their joins must
 * come out with equal value numbers, without any name being in scope across them.
 */
class BranchAwareGlobalValueNumberingTest : TACBuilderAuxiliaries() {


    private fun BuiltTACProgram.defsOf(v: TACSymbol.Var): List<CmdPointer> =
        code.analysisCache.graph.commands.filter { it.cmd.getLhs() == v }.map { it.ptr }.toList()

    private fun BuiltTACProgram.defOf(v: TACSymbol.Var): CmdPointer = defsOf(v).single()

    /** The value number of the single definition of [v]. */
    private fun BranchAwareGlobalValueNumbering.vn(prog: BuiltTACProgram, v: TACSymbol.Var) =
        vnOfDef[prog.defOf(v)]!!

    /**
     * The value number [v] has at the end of the program, which is below every join of it — the decision tree,
     * for a variable defined once per arm. Where a read sits matters now that a read inside an arm is one the
     * arm's own definition answers.
     */
    private fun BranchAwareGlobalValueNumbering.endVn(prog: BuiltTACProgram, v: TACSymbol.Var) =
        valueNumberOf(v, prog.code.topoSortFw.last())

    @Test
    fun identicalDefinitionsShareAValueNumber() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(s)
            havoc(x)
            c assign Add(aS, bS)
            d assign Add(aS, bS)
            e assign Add(aS, cS)
            f assign Add(aS, dS) // reads `d`, which carries `c`'s value: same number as `e` through the representative
            g assign Add(bS, aS)
            assert(x)
        }
        val gvn = prog.gvn()
        assertEquals(gvn.vn(prog, c), gvn.vn(prog, d))
        assertEquals(gvn.vn(prog, e), gvn.vn(prog, f))
        assertNotEquals(gvn.vn(prog, c), gvn.vn(prog, g)) // syntactic: no commutativity
        assertEquals(setOf(prog.defOf(c), prog.defOf(d)), gvn.vnOfDef.keysOf(gvn.vn(prog, c)))
    }

    @Test
    fun copiesAndConstantsAreTheirValue() {
        val prog = TACProgramBuilder {
            havoc(x)
            c assign 5
            d assign c
            e assign 5
            f assign Add(dS, 1.asTACExpr)
            g assign Add(eS, 1.asTACExpr)
            assert(x)
        }
        val gvn = prog.gvn()
        assertEquals(5.asTACExpr, gvn.vn(prog, c))
        assertEquals(gvn.vn(prog, c), gvn.vn(prog, d))
        assertEquals(gvn.vn(prog, d), gvn.vn(prog, e))
        assertEquals(gvn.vn(prog, f), gvn.vn(prog, g))
    }

    @Test
    fun havocsAndUnconstrainedAreLeaves() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(c)
            havoc(d)
            e assign TACExpr.Unconstrained(Tag.Bit256)
            f assign TACExpr.Unconstrained(Tag.Bit256)
            assert(x)
        }
        val gvn = prog.gvn()
        assertNotEquals(gvn.vn(prog, c), gvn.vn(prog, d))
        assertNotEquals(gvn.vn(prog, e), gvn.vn(prog, f))
    }

    /** `if (x) {c := 0} else {c := Mul(a b)}; J1: e := Add(c 1); if (x) {d := 0} else {d := Mul(a b)}; J3: f := Add(d 1)`. */
    private fun twinDiamonds(firstCond: TACSymbol.Var, secondCond: TACSymbol.Var) = TACProgramBuilder {
        havoc(a)
        havoc(b)
        havoc(firstCond)
        if (secondCond != firstCond) {
            havoc(secondCond)
        }
        jumpCond(firstCond)
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
            jumpCond(secondCond)
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
                u assign Eq(eS, fS)
                assert(u)
            }
        }
    }

    @Test
    fun twinJoinsOnOneConditionAreEqual() {
        val prog = twinDiamonds(x, x)
        val gvn = prog.gvn()
        val joinC = gvn.endVn(prog, c)
        val joinD = gvn.endVn(prog, d)
        assertEquals(joinC, joinD)
        // the join is a decision on `x` between the two arms' values
        check(joinC is TACExpr.TernaryExp.Ite && joinC.i == xS && joinC.t == 0.asTACExpr) { "not the expected tree: $joinC" }
        assertEquals(gvn.vn(prog, e), gvn.vn(prog, f))
        // the arms themselves are numbered too, and the else-arms of the twins agree
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[1]], gvn.vnOfDef[prog.defsOf(d)[1]])
        assertNotEquals(gvn.vnOfDef[prog.defsOf(d)[1]], joinD)
    }

    @Test
    fun twinJoinsOnDifferentConditionsAreNot() {
        val prog = twinDiamonds(x, y)
        val gvn = prog.gvn()
        assertNotEquals(gvn.endVn(prog, c), gvn.endVn(prog, d))
        assertNotEquals(gvn.vn(prog, e), gvn.vn(prog, f))
    }

    @Test
    fun conditionsCorrelateByValueNotByName() {
        // `y` and `z` are different variables with the same value; the splits on them are the same split
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            y assign Eq(aS, bS)
            z assign Eq(aS, bS)
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
                    assert(x)
                }
            }
        }
        val gvn = prog.gvn()
        assertEquals(gvn.endVn(prog, c), gvn.endVn(prog, d))
        // and the facts inside the arms are keyed by that value, so the two then-arms carry the same literal
        assertEquals(gvn.facts.getValue(prog.block(1)), gvn.facts.getValue(prog.block(4)))
    }

    @Test
    fun nestedTwinTowersInsideAnOuterDiamond() {
        // The grind1 shape: each tower sits inside the then-arm of an outer diamond on `x`, selects on its own copy
        // of `Eq(a b)`, and joins inside that arm. No name of tower 1 is in scope in tower 3, yet the inner joins
        // and everything computed from them must be equal.
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
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
                        jump(8)
                    }
                }
                jump(8)
                jumpDest(8) {
                    assert(x)
                }
            }
        }
        val gvn = prog.gvn()
        assertEquals(gvn.endVn(prog, c), gvn.endVn(prog, d))
        assertEquals(gvn.vn(prog, e), gvn.vn(prog, f))
        // the outer literal is a fact of the inner join as well and drops out of the arms' guards
        val outer = BranchAwareGlobalValueNumbering.Literal(xS, true)
        assertEquals(setOf(outer), gvn.facts.getValue(prog.block(3)))
        // the inner literal is keyed by `y`'s VALUE, so tower 3's then-arm carries the very same fact
        val inner = BranchAwareGlobalValueNumbering.Literal(gvn.vn(prog, y), true)
        assertEquals(setOf(outer, inner), gvn.facts.getValue(prog.block(1)))
        assertEquals(setOf(outer, inner), gvn.facts.getValue(prog.block(4)))
    }

    /** `if (x) {c := 1} else { if (inner) {c := 2} else {c := 3} }`, all three arms joining at one block. */
    private fun threeWayJoin(inner: TACSymbol.Var, last: Int) = TACProgramBuilder {
        havoc(x)
        havoc(y)
        havoc(z)
        jumpCond(x)
        jump(1) {
            c assign 1
            jump(4)
        }
        jump {
            jumpCond(inner)
            jump(2) {
                c assign 2
                jump(4)
            }
            jump(3) {
                c assign last
                jump(4)
            }
        }
        jumpDest(4) {
            assert(x)
        }
    }

    @Test
    fun threeWayJoinDecidesOnTheOuterConditionFirst() {
        val prog = threeWayJoin(y, 3)
        val gvn = prog.gvn()
        val vn = gvn.endVn(prog, c)
        // the outer decision is on `x`, between the first arm's value and a stand-in for the inner decision
        check(vn is TACExpr.TernaryExp.Ite) { "not a decision: $vn" }
        assertEquals(xS, vn.i)
        assertEquals(1.asTACExpr, vn.t)
        assertTrue(vn.e is TACExpr.Sym.Var) { "an inner decision goes in as a representative: ${vn.e}" }
    }

    @Test
    fun theInnerDecisionIsPartOfTheNumber() {
        // three of the same shape in a row: two deciding inside on `y`, the third on `z`
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            havoc(z)
            jumpCond(x)
            jump(1) {
                c assign 1
                jump(4)
            }
            jump {
                jumpCond(y)
                jump(2) {
                    c assign 2
                    jump(4)
                }
                jump(3) {
                    c assign 3
                    jump(4)
                }
            }
            jumpDest(4) {
                jumpCond(x)
                jump(5) {
                    d assign 1
                    jump(8)
                }
                jump {
                    jumpCond(y)
                    jump(6) {
                        d assign 2
                        jump(8)
                    }
                    jump(7) {
                        d assign 3
                        jump(8)
                    }
                }
                jumpDest(8) {
                    jumpCond(x)
                    jump(9) {
                        e assign 1
                        jump(12)
                    }
                    jump {
                        jumpCond(z)
                        jump(10) {
                            e assign 2
                            jump(12)
                        }
                        jump(11) {
                            e assign 3
                            jump(12)
                        }
                    }
                    jumpDest(12) {
                        assert(x)
                    }
                }
            }
        }
        val gvn = prog.gvn()
        // the stand-in stands for something: same inner decision, same number; a different one, a different number
        assertEquals(gvn.endVn(prog, c), gvn.endVn(prog, d))
        assertNotEquals(gvn.endVn(prog, c), gvn.endVn(prog, e))
    }

    /** The point of the whole thing: an equality between two twin diamonds numbers to `true`. */
    @Test
    fun anEqualityBetweenTwinJoinsIsAlreadyTrue() {
        val prog = twinDiamonds(x, x)
        val gvn = prog.gvn()
        val eq = prog.code.analysisCache.graph.commands
            .first { (_, cmd) -> cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.BinRel.Eq }
        assertEquals(true.asTACExpr, gvn.vnOfDef[eq.ptr])
    }

    /** Arms that compute the same value leave nothing to decide, and the join is numbered as they are. */
    @Test
    fun armsThatAgreeCollapseTheTree() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, bS)
                jump(3)
            }
            jump(2) {
                c assign Add(aS, bS)
                jump(3)
            }
            jumpDest(3) {
                d assign Add(cS, 1.asTACExpr)
                assert(x)
            }
        }
        val gvn = prog.gvn()
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[0]], gvn.endVn(prog, c))
    }

    @Test
    fun aConstantConditionCutsItsDeadArm() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            x assign Eq(0.asTACExpr, 0.asTACExpr)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, bS)
                jump(3)
            }
            jump(2) {
                c assign 0
                jump(3)
            }
            jumpDest(3) {
                d assign Add(cS, 1.asTACExpr)
                assert(x)
            }
        }
        val gvn = prog.gvn()
        // the condition is constantly true, so the else arm is never walked
        assertEquals(null, gvn.facts[prog.block(2)])
        assertEquals(null, gvn.vnOfDef[prog.defsOf(c)[1]])
        // and `c` below the join is the live arm's value, with no decision to make
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[0]], gvn.endVn(prog, c))
    }

    /** A block reached another way does not take on the facts of an edge that was cut. */
    @Test
    fun aCutEdgeContributesNoFacts() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(y)
            x assign Eq(0.asTACExpr, 0.asTACExpr)
            jumpCond(x)
            jump(1) {
                // the else target of the constant branch, and also reachable through block 2
                jumpCond(y)
                jump(2) {
                    c assign Add(aS, bS)
                    jump(3)
                }
                jump(3)
            }
            jumpDest(3) {
                assert(x)
            }
        }
        val gvn = prog.gvn()
        // block 3 is reached from 1 and from 2; the cut edge out of the constant branch adds nothing
        assertEquals(gvn.facts[prog.block(1)], gvn.facts[prog.block(3)])
    }

    @Test
    fun aProgramThatIsNotDsaIsRejected() {
        val prog = TACProgramBuilder {
            havoc(x)
            a assign 1
            a assign 2
            assert(x)
        }
        assertThrows(IllegalStateException::class.java) { prog.gvn() }
    }

    /**
     * A variable defined once per arm and read inside one of them: the read sees that arm's definition, so that is
     * its number there — not the decision tree, which is only what it denotes below the join. Both arms read it, so
     * whichever the walk meets first, one of the two answers would be the tree if the reading block were ignored.
     */
    @Test
    fun anArmLocalReadIsTheArmsOwnValue() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, bS)
                d assign Add(cS, 1.asTACExpr)
                jump(3)
            }
            jump(2) {
                c assign Mul(aS, bS)
                e assign Add(cS, 1.asTACExpr)
                jump(3)
            }
            jumpDest(3) {
                assert(x)
            }
        }
        val gvn = prog.gvn()
        val thenArm = gvn.vnOfDef[prog.defsOf(c)[0]]
        val elseArm = gvn.vnOfDef[prog.defsOf(c)[1]]
        assertEquals(thenArm, gvn.valueNumberOf(c, prog.block(1)))
        assertEquals(elseArm, gvn.valueNumberOf(c, prog.block(2)))
        // and below the join it is the decision between them, which is neither
        val join = gvn.endVn(prog, c)
        assertNotEquals(thenArm, join)
        assertNotEquals(elseArm, join)
    }

    /**
     * The same arm-local reads, at a join with a third predecessor that defines nothing: the shape optimizations
     * past [instrumentation.transformers.DSAToSSA] leave when they drop the definitions nothing read. The program is
     * accepted, and each read is still its own arm's value - the join, which no read sits below, is never asked
     * about.
     */
    @Test
    fun anArmLocalReadAtAnUncleanJoinIsTheArmsOwnValue() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                c assign Add(aS, bS)
                d assign Add(cS, 1.asTACExpr)
                jump(4)
            }
            jump(2) {
                jumpCond(y)
                jump(3) {
                    c assign Mul(aS, bS)
                    e assign Add(cS, 1.asTACExpr)
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
        val gvn = prog.gvn()
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[0]], gvn.valueNumberOf(c, prog.block(1)))
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[1]], gvn.valueNumberOf(c, prog.block(3)))
        assertNotEquals(gvn.vn(prog, d), gvn.vn(prog, e))
    }

    /**
     * An arm was cut into a leaf and still defines `c`: below the other arm, `c` is that arm's value alone, not a
     * decision tree over both, which would need a join that is not there.
     */
    @Test
    fun aLeafArmsDefinitionIsNotPartOfTheValueBelowTheOtherArm() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            jumpCond(x)
            jump {
                c assign Add(aS, bS)
                d assign Add(cS, 1.asTACExpr)
            }
            jump {
                c assign Mul(aS, bS)
                jump(1) {
                    e assign Add(cS, 1.asTACExpr)
                    assert(x)
                }
            }
        }
        val gvn = prog.gvn()
        val onwards = prog.defsOf(c).single { prog.code.analysisCache.graph.succ(it.block).isNotEmpty() }
        assertEquals(gvn.vnOfDef[onwards], gvn.valueNumberOf(c, prog.block(1)))
    }

    /**
     * A bound variable has no definition, so it is numbered as itself - which is sound only because
     * [instrumentation.transformers.UniqueifyBoundVars] has made bound names unique. Two formulas binding the same
     * name over the same body therefore agree, and two binding different names do not, even when they say the same
     * thing: the numbering is not up to renaming.
     */
    @Test
    fun aQuantifiedFormulaIsNumberedThroughItsBoundVariable() {
        val i2 = intVar("i2")
        fun forall(bound: TACSymbol.Var) = TACExpr.QuantifiedFormula(
            isForall = true,
            quantifiedVar = bound,
            body = txf.Le(bound.asSym(), sS),
            tag = Tag.Bool,
        )
        val prog = TACProgramBuilder {
            havoc(s)
            havoc(x)
            u assign forall(i)
            y assign forall(i)
            z assign forall(i2)
            assert(x)
        }
        val gvn = prog.gvn()
        assertEquals(gvn.vn(prog, u), gvn.vn(prog, y))
        assertNotEquals(gvn.vn(prog, u), gvn.vn(prog, z))
    }

    /**
     * An irregular join: `c`'s arms share no key with both polarities, so no decision tree describes it. Arm 3 is
     * reached both when `x` holds and, through block 2, when `x` fails and `y` holds, so its facts keep neither;
     * arm 4 is reached only under `x` false and `y` false. The intersection of the two guards' keys is therefore
     * empty. The shape is still DSA - each arm's only successor is block 5, whose only predecessors are the arms.
     *
     * The arms agree on a value, and that is the answer, which is what the check before [decisionTree] is for:
     * the tree cannot be built here, so without it the number would fall back to `c` standing for itself.
     */
    @Test
    fun anIrregularJoinWhoseArmsAgreeIsStillTheirValue() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                jump(3)
            }
            jump(2) {
                jumpCond(y)
                jump(3)
                jump(4) {
                    c assign Add(aS, bS)
                    jump(5)
                }
            }
            jumpDest(3) {
                c assign Add(aS, bS)
                jump(5)
            }
            jumpDest(5) {
                assert(x)
            }
        }
        val gvn = prog.gvn()
        // the two arms really do share no key, so a tree is out of the question
        val guards = prog.defsOf(c).map { gvn.facts.getValue(it.block) }
        val keysOfArm = guards.map { guard -> guard.map { it.key }.toSet() }
        assertEquals(setOf<TACExpr>(), keysOfArm[0] intersect keysOfArm[1])
        // and the number below the join is the value they agree on, not `c` itself
        assertEquals(gvn.vnOfDef[prog.defsOf(c)[0]], gvn.endVn(prog, c))
        assertNotEquals(cS, gvn.endVn(prog, c))
    }

    private fun BuiltTACProgram.gvn() = BranchAwareGlobalValueNumbering(code)
}
