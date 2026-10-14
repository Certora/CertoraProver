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
import vc.data.*
import vc.data.tacexprutil.getFreeVars

class DefinitionalEqualitySubstitutionTest : TACBuilderAuxiliaries() {

    private fun run(p: TACProgramBuilder.BuiltTACProgram) =
        DefinitionalEqualitySubstitution.go(p.code)

    private fun cmds(p: CoreTACProgram) = p.ltacStream().toList().map { it.cmd }
    private fun havocsOf(p: CoreTACProgram, v: TACSymbol.Var) =
        cmds(p).count { it is TACCmd.Simple.AssigningCmd.AssignHavocCmd && it.lhs == v }
    private fun assignsOf(p: CoreTACProgram, v: TACSymbol.Var) =
        cmds(p).count { it is TACCmd.Simple.AssigningCmd.AssignExpCmd && it.lhs == v }

    /**
     * The FOLDED shape `assume Eq(v, e)` -- what a LOADED dump carries, since it has been through
     * `toSimpleSimpleSSAAndFold`, and what external abstraction tooling emits. Matching only the
     * two-command `b := Eq(v,e); assume b` makes the pass a no-op on exactly those programs
     * (measured on morpho8: 39 folded pairs, zero matched).
     */
    @Test
    fun theFoldedAssumeShapeBecomesAnAssignment() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            assumeExp(Eq(iS, jS))
            s assign IntMul(iS, kS)
            x assign Eq(sS, kS)
            assert(x)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        assertEquals(1, assignsOf(out, i), "the folded assume must have become `i := j`")
    }

    /**
     * A mixed-tag equality defines only in the direction [tac.isSubtypeOf] allows: the patched
     * program is type-checked on construction, so `a:bv256 := i:int` does not degrade the result,
     * it fails to COMPILE the rule (boring1, both rules of `teller_accounting_easyRules.spec`).
     * `Tag.Bits` lifts to `Tag.Int`, so the other direction is legal and must still fire.
     */
    @Test
    fun aMixedTagEqualityDefinesOnlyInTheSubtypeDirection() {
        val intoBv = run(TACProgramBuilder {
            havoc(a)
            havoc(i)
            assumeExp(Eq(aS, iS))
            x assign Eq(aS, aS)
            assert(x)
        })
        assertEquals(1, havocsOf(intoBv, a), "the bv256 havoc must survive an int-tagged other side")
        assertEquals(0, assignsOf(intoBv, a), "`a := i` would not type-check and must not be emitted")

        val intoInt = run(TACProgramBuilder {
            havoc(i)
            havoc(a)
            assumeExp(Eq(iS, aS))
            x assign Eq(iS, iS)
            assert(x)
        })
        assertEquals(0, havocsOf(intoInt, i), "bv256 lifts to int, so `i := a` is legal and must fire")
        assertEquals(1, assignsOf(intoInt, i), "`i := a` must be emitted")
    }

    /**
     * The range assume CVL emits for the declaration sits right AFTER the havoc, so the equality
     * does not dominate it. It constrains `i` alone, so it is relocated rather than blocking --
     * on pool1 EVERY candidate looked like this, and a pass requiring dominance of all uses fired
     * zero times.
     */
    @Test
    fun aSelfOnlyAssumeBeforeTheEqualityIsRelocatedNotBlocking() {
        val out = run(TACProgramBuilder {
            havoc(i)
            assumeExp(Le(iS, intConst(100).asSym()))   // the declaration's own bound
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(0, havocsOf(out, i))
        assertEquals(1, assignsOf(out, i))
        // the bound is KEPT -- it is a real constraint on the replacement, never dropped
        val kept = cmds(out).filterIsInstance<TACCmd.Simple.AssumeExpCmd>()
        assertEquals(1, kept.size, "the self-only assume must survive")
        // ... and it now comes after the assignment that defines `i`
        val all = cmds(out)
        val asg = all.indexOfFirst { it is TACCmd.Simple.AssigningCmd.AssignExpCmd && it.lhs == i }
        assertTrue(asg >= 0 && asg < all.indexOf(kept.single()), "the relocated assume must follow the assignment")
    }

    /**
     * Inside the pipeline, `require x == e` is two commands: `b := Eq(x, e)` then `assume b`. It
     * is folded into `AssumeExpCmd Eq(x, e)` only afterwards, by `toSimpleSimpleSSAAndFold` --
     * which is why both spellings have to be matched. Measured on pool1: handling only the folded
     * form fires zero times at every point in `CompiledRule.optimize`.
     */
    @Test
    fun theTwoCommandRequireShapeBecomesAnAssignment() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        assertEquals(1, assignsOf(out, i), "`i := j` must have been inserted")
        // `x` is defined only by the equality and used only by the assume, so the PAIR goes --
        // deleting just the equality would leave the assume reading an undefined `x`
        assertTrue(cmds(out).none { it is TACCmd.Simple.AssumeCmd }, "the assume must go too")
        assertEquals(0, assignsOf(out, x), "and so must the equality that defined its condition")
    }

    /** ... but only when the assume actually follows in the same block. */
    @Test
    fun anUnassumedEqualityIsNotADefinition() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)          // computed, never assumed
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /** A use of the VALUE that the equality does not dominate must block the rewrite. */
    @Test
    fun aRealUseBeforeTheEqualityBlocks() {
        val out = run(TACProgramBuilder {
            havoc(i)
            s assign IntMul(iS, kS)                    // uses i's value before the equality
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /**
     * A self-only assume is relocatable only from a block the equality must be reached THROUGH.
     * Moving one off a sibling arm would put it on the equality's own arm, where it never held --
     * that direction loses counterexamples.
     */
    @Test
    fun aSelfOnlyAssumeOnASiblingArmBlocks() {
        val out = run(TACProgramBuilder {
            havoc(i)
            jumpCond(x)
            jump {
                assumeExp(Le(iS, intConst(100).asSym()))   // holds on THIS arm only
                jump(1)
            }
            jump {
                jump(1)
            }
            jumpDest(1) {
                j assign IntAdd(kS, intConst(1).asSym())
                y assign Eq(iS, jS)
                assume(y)
                s assign IntMul(iS, kS)
                z assign Eq(sS, kS)
                assert(z)
            }
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay -- the bound does not hold on the other arm")
        assertEquals(0, assignsOf(out, i))
    }

    /**
     * The other side does not have to be a variable: it is evaluated exactly where the equality
     * already evaluates it, so nothing moves.
     */
    @Test
    fun anExpressionIsAFineReplacement() {
        val out = run(TACProgramBuilder {
            havoc(i)
            x assign Eq(iS, IntAdd(kS, intConst(1).asSym()))
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        val rhs = cmds(out).filterIsInstance<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
            .single { it.lhs == i }.rhs
        assertTrue(rhs is TACExpr.Vec.IntAdd, "`i := k + 1` must have been inserted, got $rhs")
        assertEquals(setOf(k), rhs.getFreeVars(), "over `k`, not a fresh copy of anything")
    }

    /** `v` on both sides is a constraint, not a definition -- and `Eq(v, v + 1)` has no solution. */
    @Test
    fun aSelfReferentialEqualityIsNotADefinition() {
        val out = run(TACProgramBuilder {
            havoc(i)
            x assign Eq(iS, IntAdd(iS, intConst(1).asSym()))
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /**
     * An assert BETWEEN the equality and its assume blocks the rewrite.
     *
     * `LeinoWP` folds a block backwards: an assume becomes `cond ==> rest`, an assert becomes
     * `cond AND rest`. So an assert is conjoined OUTSIDE the implication of every assume that
     * follows it, and `i` is genuinely free where it stands -- pinning it to `j` in front of the
     * assert would hide violations.
     */
    @Test
    fun anAssertBetweenTheEqualityAndItsAssumeBlocks() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            y assign Eq(iS, kS)
            assert(y)
            assume(x)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /** `assert true` is conjoined as `true`: it has no violation to hide, so it does not block. */
    @Test
    fun aTrivialAssertBetweenTheEqualityAndItsAssumeDoesNotBlock() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assert(TACSymbol.True)
            assume(x)
            y assign Eq(iS, kS)
            assert(y)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        assertEquals(1, assignsOf(out, i))
    }

    /**
     * A self-only assume moves only if we can DECIDE that it holds. `i < i*2` has two occurrences
     * of `i`, so it is not a comparison against constants and `oneVarSolutions` declines it. It
     * could be a contradiction, and moving one of those makes everything it used to kill live.
     */
    @Test
    fun anUndecidableSelfOnlyAssumeBlocks() {
        val out = run(TACProgramBuilder {
            havoc(i)
            assumeExp(Lt(iS, IntMul(iS, intConst(2).asSym())))
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /**
     * ... and one that provably holds never killed its path, so whatever it is carried past --
     * an assert included -- is unaffected, and nothing has to look at what is in between.
     */
    @Test
    fun aSatisfiableSelfOnlyAssumeMovesPastAnAssert() {
        val out = run(TACProgramBuilder {
            havoc(i)
            assumeExp(Le(iS, intConst(100).asSym()))   // `oneVarSolutions` decides this exactly
            y assign Eq(kS, sS)
            assert(y)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            z assign Eq(iS, kS)
            assert(z)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        assertEquals(1, assignsOf(out, i))
    }

    /**
     * A self-only assume from BEFORE the havoc constrains an undefined `i`, and says nothing about
     * the value `i` gets. Relocating it past the new assignment would turn it into a constraint on
     * the replacement -- one the program never made.
     */
    @Test
    fun aSelfOnlyAssumeBeforeTheHavocBlocks() {
        val out = run(TACProgramBuilder {
            assumeExp(Le(iS, intConst(100).asSym()))   // `i` is not defined yet
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            x assign Eq(iS, jS)
            assume(x)
            s assign IntMul(iS, kS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i), "the havoc must stay")
        assertEquals(0, assignsOf(out, i))
    }

    /**
     * `j == k` and then `i == j` are a chain. A single round gets one link -- the winner's variable
     * lands in `eliminated` and blocks the other -- so `go` repeats to a fixpoint.
     */
    @Test
    fun aChainOfEqualitiesResolvesInOneCall() {
        val out = run(TACProgramBuilder {
            havoc(i)
            havoc(j)
            x assign Eq(jS, kS)
            assume(x)
            y assign Eq(iS, jS)
            assume(y)
            s assign IntMul(iS, jS)
            z assign Eq(sS, kS)
            assert(z)
        })
        assertEquals(0, havocsOf(out, i) + havocsOf(out, j), "both havocs must go")
        assertEquals(1, assignsOf(out, i), "`i := j`")
        assertEquals(1, assignsOf(out, j), "`j := k`")
    }

    /**
     * `Eq(v, w)` with a havoc on either side is a candidate twice over, and the two edits would
     * land on one and the same command. Only the first happens; `w` keeps its havoc, and a second
     * run of the pass is what would chain them.
     */
    @Test
    fun onlyOneSideOfAnEqualityBetweenTwoHavocsIsSubstituted() {
        val out = run(TACProgramBuilder {
            havoc(i)
            havoc(j)
            x assign Eq(iS, jS)
            assume(x)
            s assign IntMul(iS, jS)
            y assign Eq(sS, kS)
            assert(y)
        })
        assertEquals(1, havocsOf(out, i) + havocsOf(out, j), "exactly one havoc may go")
        assertEquals(1, assignsOf(out, i) + assignsOf(out, j), "and exactly one assignment replaces it")
    }

    /**
     * Two equalities about one variable: exactly one becomes its definition. The other is left
     * untouched and now relates `j` and `s` -- which is what `i == j` and `i == s` amount to once
     * `i` is one of them. Which one wins is not pinned; nothing depends on it.
     */
    @Test
    fun onlyOneOfTwoEqualitiesBecomesTheDefinition() {
        val out = run(TACProgramBuilder {
            havoc(i)
            j assign IntAdd(kS, intConst(1).asSym())
            s assign IntAdd(kS, intConst(2).asSym())
            x assign Eq(iS, jS)
            assume(x)
            y assign Eq(iS, sS)
            assume(y)
            z assign Eq(iS, kS)
            assert(z)
        })
        assertEquals(0, havocsOf(out, i), "the havoc must be gone")
        assertEquals(1, assignsOf(out, i), "`i := j` must have been inserted")
        val eqs = cmds(out).filterIsInstance<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
            .filter { it.rhs is TACExpr.BinRel.Eq }
        assertEquals(2, eqs.size, "the winner's equality goes with its assume; the rest constrain `j`")
    }
}
