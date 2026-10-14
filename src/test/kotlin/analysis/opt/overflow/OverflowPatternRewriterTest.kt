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

package analysis.opt.overflow

import analysis.opt.overflow.OverflowPatternRewriter.Companion.overflowMeta
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACProgramBuilder
import vc.data.asTACExpr

class OverflowPatternRewriterTest : TACBuilderAuxiliaries() {

    /**
     * Two subtractions, in one block, both guarded by the same overflow check. Both of them match, but rewriting one
     * of them takes over the block's jump and points it at a newly added block, so rewriting the second one too
     * would leave the block added for the first with nothing jumping to it. So only one is rewritten.
     */
    @Test
    fun twoOperationsShareOneCheck() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            y assign Le(aS, 0xff.asTACExpr)
            assume(y)
            z assign Le(bS, 0xff.asTACExpr)
            assume(z)
            c assign Sub(aS, bS)
            d assign Sub(aS, bS)
            x assign Le(bS, aS)
            jumpCond(x)
            jump(1) {
                assert(False)
            }
            jump(2) {
                addCmd(TACCmd.Simple.AnnotationCmd(TACMeta.REVERT_PATH))
                assert(False)
            }
        }

        val rewritten = OverflowPatternRewriter(prog.code).go()

        assertEquals(
            1,
            rewritten.analysisCache.graph.commands.count { overflowMeta in it.cmd.meta },
            "expected exactly one of the two subtractions to be rewritten"
        )
    }

    /**
     * Two subtractions in two different blocks, each with its own check, sharing only the revert block. Both are
     * rewritten - nothing here overlaps.
     */
    @Test
    fun twoIndependentChecks() {
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(b)
            y assign Le(aS, 0xff.asTACExpr)
            assume(y)
            z assign Le(bS, 0xff.asTACExpr)
            assume(z)
            c assign Sub(aS, bS)
            x assign Le(bS, aS)
            jumpCond(x)
            jump(1) {
                nop
                jump(2) {
                    e assign Sub(aS, bS)
                    u assign Le(bS, aS)
                    jumpCond(u)
                    jump(3) {
                        assert(False)
                    }
                    jump(4)
                }
            }
            jump(4) {
                addCmd(TACCmd.Simple.AnnotationCmd(TACMeta.REVERT_PATH))
                assert(False)
            }
        }

        val rewritten = OverflowPatternRewriter(prog.code).go()

        assertEquals(
            2,
            rewritten.analysisCache.graph.commands.count { overflowMeta in it.cmd.meta },
            "expected both subtractions to be rewritten"
        )
    }
}
