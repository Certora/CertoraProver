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

package algorithms

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class IsomorphismTest {
    @Test
    fun simple() {
        val graph = mapOf(
            1 to listOf(2, 3),
            2 to listOf(4, 1),
            3 to listOf(4, 1),
            4 to listOf(5)
        )

        val labels = mapOf(
            1 to 1,
            2 to 2,
            3 to 2,
            4 to 4,
            5 to 5
        )

        val representatives = LabeledOrderedDigraph(labels, graph).findIsomorphicSubgraphs(Int.MAX_VALUE)

        assertTrue(representatives[1] == 1, "Expected 1==1 in $representatives")
        assertTrue(representatives[2] == representatives[3], "Expected 2==3 in $representatives")
        assertTrue(representatives[4] == 4, "Expected 4==4 in $representatives")
        assertTrue(representatives[5] == 5, "Expected 5==5 in $representatives")
    }

    @Test
    fun twoEquivLoops() {
        val graph = mapOf(
            1 to listOf(2, 3),
            2 to listOf(22),
            22 to listOf(23),
            23 to listOf(2, 4),
            3 to listOf(33),
            33 to listOf(34),
            34 to listOf(3, 4),
            4 to listOf(5),
        )

        val labels = mapOf(
            1 to 1,
            2 to 2,
            22 to 22,
            23 to 23,
            3 to 2,
            33 to 22,
            34 to 23,
            4 to 4,
            5 to 5
        )

        val representatives = LabeledOrderedDigraph(labels, graph).findIsomorphicSubgraphs(Int.MAX_VALUE)

        assertTrue(representatives[1] == 1, "Expected 1==1 in $representatives")
        assertTrue(representatives[2] == representatives[3], "Expected 2==3 in $representatives")
        assertTrue(representatives[22] == representatives[33], "Expected 22==33 in $representatives")
        assertTrue(representatives[23] == representatives[34], "Expected 23==34 in $representatives")
        assertTrue(representatives[4] == 4, "Expected 4==4 in $representatives")
        assertTrue(representatives[5] == 5, "Expected 5==5 in $representatives")
    }

    @Test
    fun twoLoopsOneDifferentLabel() {
        val graph = mapOf(
            1 to listOf(2, 3),
            2 to listOf(22),
            22 to listOf(23),
            23 to listOf(2, 4),
            3 to listOf(33),
            33 to listOf(34),
            34 to listOf(3, 4),
            4 to listOf(5),
        )

        val labels = mapOf(
            1 to 1,
            2 to 2,
            22 to 22,
            23 to 23,
            3 to 2,
            33 to 22,
            34 to 34,
            4 to 4,
            5 to 5
        )

        val representatives = LabeledOrderedDigraph(labels, graph).findIsomorphicSubgraphs(Int.MAX_VALUE)

        assertTrue(representatives[1] == 1, "Expected 1==1 in $representatives")
        assertTrue(representatives[2] == 2, "Expected 2==3 in $representatives")
        assertTrue(representatives[3] == 3, "Expected 2==3 in $representatives")
        assertTrue(representatives[22] == 22, "Expected 22==22 in $representatives")
        assertTrue(representatives[23] == 23, "Expected 23==23 in $representatives")
        assertTrue(representatives[33] == 33, "Expected 33==33 in $representatives")
        assertTrue(representatives[34] == 34, "Expected 34==34 in $representatives")
        assertTrue(representatives[4] == 4, "Expected 4==4 in $representatives")
        assertTrue(representatives[5] == 5, "Expected 5==5 in $representatives")
    }
}
