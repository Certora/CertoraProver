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

package verifier

import config.Config
import config.ConfigScope
import loaders.WithTACSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.cmdprocessor.withNullChecker
import utils.mapToSet
import verifier.autoconfig.AutoConfigManager
import verifier.splits.SplitTree

class ParallelSplitterResultsTest : WithTACSource {

    /** The two children of the split tree's root, i.e. a pair of splits that a race would be run on. */
    private fun twoSplits(name: String): List<SplitTree.Node> {
        val prog = loadTACProgramResource("vc/wp/Rule-Invertibility.tac", name = "dummy")
        val tree = SplitTree(prog, Config.Smt.SplittingDepth)
        val splitManager = AutoConfigManager(name)
        return tree.rootNode.let { it.split(it.generate(splitManager)) }
    }

    private fun inconclusive(split: SplitTree.Node) = ParallelSplitter.ParallelRacerResult.Basic(
        subProblem = split,
        result = SmtFormulaCheckerResult.Single.Cancelled().withNullChecker(),
        postprocessor = null,
    )

    /**
     * A race runs several solvers per split, so it produces several results per split. Whichever way the race
     * ends, the caller gets exactly one result per split back.
     */
    @Test
    fun collapsesSeveralSolverResultsIntoOnePerSplit() = ConfigScope(Config.EnableSplitting, true).use {
        val splits = twoSplits("collapsesSeveralSolverResultsIntoOnePerSplit")
        assertEquals(2, splits.size)

        val perSolver = splits.flatMap { split -> List(3) { inconclusive(split) } }

        val collapsed = ParallelSplitter.oneResultPerSplit(splits, perSolver)

        assertEquals(splits.size, collapsed.size)
        assertEquals(splits.mapToSet { it }, collapsed.mapToSet { it.subProblem })
    }

    /**
     * Why the collapse matters: a split whose result comes back inconclusive is split again, and a node can only
     * be split once — its children have fixed addresses and the split tree holds one node per address.
     */
    @Test
    fun splittingTheSameNodeTwiceIsRejected() {
        ConfigScope(Config.EnableSplitting, true).use {
            val splitManager = AutoConfigManager("splittingTheSameNodeTwiceIsRejected")
            val prog = loadTACProgramResource("vc/wp/Rule-Invertibility.tac", name = "dummy")
            val tree = SplitTree(prog, Config.Smt.SplittingDepth)
            val node = tree.rootNode
            val tac = node.generate(splitManager)

            node.split(tac)
            assertThrows<IllegalStateException> { node.split(tac) }
        }
    }
}
