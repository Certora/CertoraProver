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

package report.dumps

import analysis.CmdPointer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.asTACExpr

class CountDifficultOpsTest : TACBuilderAuxiliaries() {



    @Test
    fun testStraightLineProg() {
        val ctp = TACProgramBuilder {
            i assign (aS mul aS)  // nonlinearity
            i assign (bS mul bS)  // nonlinearity
            i assign (jS intMul  kS) // nonlinearity
            i assign (jS intPow  kS) // variable exponentiation -- not difficulty, just very imprecise
            i assign (jS intPow  kS) // variable exponentiation -- not difficulty, just very imprecise
            i assign Ite(xS, jS, kS) // case split
            x assign Sge(aS, bS) // case split
        }.code

        val countDifficultOps = CountDifficultOps(ctp)
        assertEquals(1, countDifficultOps.getDifficultBlocks().size)
        assertEquals(5, countDifficultOps.getDifficultCmds().size)
        val blockDifficultyWholeProg = countDifficultOps[countDifficultOps.getDifficultBlocks().first()]!!
        assertTrue(blockDifficultyWholeProg.isDifficult())
        // will have to change whenever the weights change ... -- ok to remove or generalize if it gets annoying ...
        assertEquals(11, blockDifficultyWholeProg.computeDifficultyScore())

        // leaving out first and last command
        val blockDifficultySubgraph = CountDifficultOps.computeDifficultyInSubgraph(
            ctp.analysisCache.graph,
            CmdPointer(ctp.entryBlockId, 1),
            setOf(CmdPointer(ctp.entryBlockId, 7))
        )
        assertTrue(blockDifficultySubgraph.isDifficult())
        // will have to change whenever the weights change ... -- ok to remove or generalize if it gets annoying ...
        assertEquals(8, blockDifficultySubgraph.computeDifficultyScore())
    }

    /** basically the same expressions as in [testStraightLineProg] but (randomly) inlined into each other */
    @Test
    fun testNestedExp() {
        val ctp = TACProgramBuilder {
            x assign Sge(
                safeMathNarrow(
                    Ite(
                        xS,
                        iS,
                        // constant base exponentiation -- has its own weight (basically a case split with a handful of cases..)
                        (2.asTACExpr intPow aS)
                    ),
                    Tag.Bit256
                ),
                safeMathNarrow(
                    // constant exponent exponentiation counts like the multiplications it expands to
                    (aS pow 4.asTACExpr) intMul (aS mul bS),
                    Tag.Bit256
                )
            )
        }.code

        val countDifficultOps = CountDifficultOps(ctp)
        assertEquals(1, countDifficultOps.getDifficultBlocks().size)
        assertEquals(1, countDifficultOps.getDifficultCmds().size)
        val blockDifficultyWholeProg = countDifficultOps[countDifficultOps.getDifficultBlocks().first()]!!
        assertTrue(blockDifficultyWholeProg.isDifficult())
        // will have to change whenever the weights change ... -- ok to remove or generalize if it gets annoying ...
        assertEquals(19, blockDifficultyWholeProg.computeDifficultyScore())
    }
}
