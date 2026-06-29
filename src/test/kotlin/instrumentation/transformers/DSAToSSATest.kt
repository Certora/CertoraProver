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
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.tacexprutil.TACExprFreeVarsCollector

/**
 * Tests for [DSAToSSA].
 *
 * The pass expects the input to already be in DSA form. It merges multi-block writes to a map variable into a single
 * ITE-driven assignment in the common successor; non-map, non-reachability variables must remain DSA afterwards
 * (verified by [DSAChecker.checkFinalDSA]).
 */
class DSAToSSATest : TACBuilderAuxiliaries() {

    private val m = wordMapVar("m")
    private val mSrcA = wordMapVar("mSrcA")
    private val mSrcB = wordMapVar("mSrcB")
    private val mOther = wordMapVar("mOther")
    private val mOtherSrcA = wordMapVar("mOtherSrcA")
    private val mOtherSrcB = wordMapVar("mOtherSrcB")

    /**
     * Diamond pattern: block 0 branches to blocks 2 and 3 which each write to `m`, converging at block 1. We expect
     * `m`'s writes to disappear from blocks 2/3 and a single merged ITE assignment to appear at the top of block 1,
     * preceded by exactly one havoc per predecessor reachability variable.
     */
    @Test
    fun mergesTwoWritesIntoIte() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(mSrcA)
            havoc(mSrcB)
            jumpCond(x)
            jump(2) {
                m assign mSrcA.asSym()
                jump(1) {
                    nop
                }
            }
            jump(3) {
                m assign mSrcB.asSym()
                jump(1)
            }
        }

        val rewritten = DSAToSSA.rewrite(prog.code)

        // No block 2/3 still has a map write to `m`.
        for (block in rewritten.code.values) {
            for (cmd in block) {
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.lhs == m) {
                    // The only remaining assignment to `m` must be the merged ITE in block 1.
                    assertEquals(true, cmd.rhs is TACExpr.TernaryExp.Ite, "remaining assignment to m should be an ITE")
                }
            }
        }

        // Exactly one ITE assignment to `m` in the rewritten program.
        val mAssignments = rewritten.code.values.flatten().filter {
            it is TACCmd.Simple.AssigningCmd.AssignExpCmd && it.lhs == m
        }
        assertEquals(1, mAssignments.size, "expected exactly one merged assignment to m")

        // 3 input havocs (x, mSrcA, mSrcB) + 2 new reachability-var havocs (one per predecessor of block 1).
        val havocs = rewritten.code.values.flatten().filterIsInstance<TACCmd.Simple.AssigningCmd.AssignHavocCmd>()
        assertEquals(5, havocs.size)
        // No havoced variable is havoced twice.
        assertEquals(havocs.size, havocs.map { it.lhs }.toSet().size)
    }

    /**
     * Two distinct maps `m` and `mOther` are both written in the same pair of predecessor blocks. Each predecessor
     * therefore needs only a single havoc of its reachability variable - one per (successor, reach-var) pair, not one
     * per (map, write). This verifies the dedup that [DSAToSSA] performs via its `toHavoc` multimap.
     */
    @Test
    fun deduplicatesHavocsAcrossMaps() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(mSrcA)
            havoc(mSrcB)
            havoc(mOtherSrcA)
            havoc(mOtherSrcB)
            jumpCond(x)
            jump(2) {
                m assign mSrcA.asSym()
                mOther assign mOtherSrcA.asSym()
                jump(1) {
                    nop
                }
            }
            jump(3) {
                m assign mSrcB.asSym()
                mOther assign mOtherSrcB.asSym()
                jump(1)
            }
        }

        val rewritten = DSAToSSA.rewrite(prog.code)

        // 5 input havocs (x, mSrcA, mSrcB, mOtherSrcA, mOtherSrcB) + 2 new reachability-var havocs (one per
        // predecessor of block 1). Without dedup we'd see 4 reachability-var havocs (2 maps x 2 predecessors).
        val havocs = rewritten.code.values.flatten().filterIsInstance<TACCmd.Simple.AssigningCmd.AssignHavocCmd>()
        assertEquals(7, havocs.size)
        assertEquals(havocs.size, havocs.map { it.lhs }.toSet().size, "no variable should be havoced twice")
    }

    /**
     * Regression test for a former DSA hazard: a write whose RHS uses a variable defined on only one incoming path
     * (e.g. block 2 writes `m = store(mSrcA, a, a)` after defining `a`, block 3 writes `m = mSrcB`). The naive
     * implementation moved the RHS verbatim into the successor's merged ITE, leaving `a` referenced in a block where
     * it wasn't defined on every path - violating DSA. The fix introduces a per-predecessor temp: each `m = rhs` is
     * rewritten to `tmp_i = rhs` in its original block, and the successor uses `tmp_i`. This keeps `rhs`'s free vars
     * confined to their original block.
     */
    @Test
    fun rhsFreeVarsStayInOriginalBlock() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(mSrcA)
            havoc(mSrcB)
            jumpCond(x)
            jump(2) {
                a assign 5
                m assign Store(mSrcA.asSym(), aS, v = aS)
                jump(1) {
                    nop
                }
            }
            jump(3) {
                m assign mSrcB.asSym()
                jump(1)
            }
        }

        // Should succeed (no IllegalStateException from checkDSAfinal).
        val rewritten = DSAToSSA.rewrite(prog.code)

        // The merged assignment to `m` in the successor should reference only the per-predecessor temps - no `a`.
        val mMergedRhs = rewritten.code.values.flatten()
            .filterIsInstance<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
            .single { it.lhs == m }
            .rhs
        val freeVars = TACExprFreeVarsCollector.getFreeVars(mMergedRhs)
        assertEquals(false, a in freeVars, "merged ITE in successor must not reference `a` directly")
    }
}
