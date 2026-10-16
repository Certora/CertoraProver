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

package wasm.analysis.intervals

import analysis.CmdPointer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder

class IntervalAnalysisLoopTest : TACBuilderAuxiliaries() {

    /**
     * A loop whose constants include a negative math int (as the Solana math-int encoding of `r -= 1` produces).
     * The negative constant must not be guessed as the modulus of a loop invariant.
     */
    @Test
    fun negativeIntConstantInLoop() {
        val prog = TACProgramBuilder {
            i assign intConst(10).asSym()
            jump(1) {
                x assign Lt(iS, intConst(100).asSym())
                jumpCond(x)
                jump(2) {
                    i assign IntAdd(iS, intConst(-1).asSym())
                    jump(1)
                }
                jump(3) {
                    nop
                }
            }
        }
        val intervals = prog.code.analysisCache[IntervalAnalysis]
        assertNotNull(intervals.inState(CmdPointer(prog.block(2), 0)))
    }
}
