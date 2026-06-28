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
import vc.data.asTACExpr

class DSACheckerTest : TACBuilderAuxiliaries() {

    /**
     * Two sibling def blocks of `a` flow into a join that has a third, non-defining predecessor. The strict
     * "clean diamond" predicate (`pred(succ).size == defBlocks.size`) does NOT hold, but every use of `a` is
     * local to its def block - so the variable never escapes the def blocks and DSA is preserved under the
     * relaxed option.
     */
    @Test
    fun testSiblingDefsWithExtraJoinPredecessorAndAllUsesLocal() {
        val prog = TACProgramBuilder {
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
     * Same shape as the test above, but the join block now reads `a` - a use that lies outside the def blocks.
     * Option (a) fails (not every use is local to a def block) and option (b) fails (join has more predecessors
     * than def blocks), so `a` is flagged as `badDefForm`.
     */
    @Test
    fun testSiblingDefsWithExtraJoinPredecessorAndUseOutsideDefBlocks() {
        val prog = TACProgramBuilder {
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
}
