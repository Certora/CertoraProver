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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import verifier.autoconfig.AutoConfigManager
import verifier.parallel.SplitScheduler
import verifier.splits.SplitAddress
import verifier.splits.SplitTree

class SplitterHeuristicTest : WithTACSource {
    @Test
    fun testSplitHeuristic() = ConfigScope(Config.EnableSplitting, true).use {
        val prog = loadTACProgramResource("vc/wp/Rule-Invertibility.tac", name = "dummy")

        val tree = SplitTree(prog, Config.Smt.SplittingDepth)
        val splitManager = AutoConfigManager("SplittingTest")
        val all = generateSequence(listOf(tree.rootNode)) { curSplits ->
            curSplits
                .takeIf { it.isNotEmpty() }
                ?.filter { it.getSplittableAnyway(splitManager) }
                ?.flatMap { it.split(it.generate(splitManager)) }
        }.flatten().map { SplitScheduler.WorkInfo(it) }.toList()

        val sorted = all.sorted()

        sorted.windowed(2, 1).forEach { workInfos ->
            assertEquals(2, workInfos.size)
            val a = workInfos[0]
            val b = workInfos[1]

            if (a.split.probablySplittable != b.split.probablySplittable) {
                // prefer splittable
                assertTrue(a.split.splittable && !b.split.splittable)
            } else if (a.split.depth != b.split.depth) {
                // prefer deeper splits
                assertTrue(a.split.depth > b.split.depth)
            } else if (a.split.address.let { it is SplitAddress.Block && it.sibling() == b.split.address }) {
                // if siblings, prefer the one with higher score
                assertTrue(a.split.sideScore >= b.split.sideScore)
            } else {
                // prefer smaller address (reverse lexicographically)
                val aa = a.split.address.asList.dropLast(1)
                val ba = b.split.address.asList.dropLast(1)
                val res = aa.zip(ba).map { (i1, i2) -> i1.compareTo(i2) }.lastOrNull { it != 0 } ?: 0

                assertTrue(res <= 0)
            }
        }

        /* Want to debug?
        fun List<SplitScheduler.WorkInfo>.printall() = forEach {
            println("\t${it.split.name} (splittable = ${it.split.splittable}, address = ${it.split.address}, depth = ${it.split.depth})")
        }
        println("Original list")
        all.printall()
        println("Sorted list")
        sorted.printall()
        */
    }
}
