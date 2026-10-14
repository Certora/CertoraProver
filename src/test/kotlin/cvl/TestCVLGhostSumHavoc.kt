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

package cvl

import infra.CVLFlow
import org.junit.jupiter.api.Test

/**
 * `havoc g assuming ...` compiles references to the pre-havoc state of `g` against an `_old` copy of `g`.
 * A `sum`/`usum` over that copy is served by an `_old` copy of the sum ghost, so these specs have to
 * compile all the way to TAC.
 */
class TestCVLGhostSumHavoc {

    private val contract = """
        contract test {
            uint256 public total;
        }
    """.trimIndent()

    private fun compile(cvlText: String) {
        val flow = CVLFlow()
        val res = flow.getProverQueryWithScene(
            contract = contract,
            solc = "solc8.13",
            withOptimize = false,
            cvlText = cvlText,
        )
        res.errorOrNull()?.let { error(it.toString()) }
        flow.transformResultsToTACs(res)
    }

    @Test
    fun usumOverPreHavocState() {
        compile(
            """
            ghost mapping(uint128 => mathint) bucket;

            rule havocKeepsSum {
                havoc bucket assuming (usum uint128 k. bucket@old[k]) == 0;
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun sumOverBothStates() {
        compile(
            """
            ghost mapping(uint128 => mathint) bucket;

            rule havocKeepsSum {
                havoc bucket assuming (sum uint128 k. bucket@new[k]) == (sum uint128 k. bucket@old[k]);
                assert true;
            }
            """.trimIndent()
        )
    }

    /** the sum ghost of a partially summed mapping is itself a mapping, and so is its `_old` copy */
    @Test
    fun sumOverPreHavocStateWithNonSummedIndex() {
        compile(
            """
            ghost mapping(uint128 => mapping(uint128 => mathint)) buckets;

            rule havocKeepsSumPerRow {
                uint128 row;
                havoc buckets assuming (sum uint128 k. buckets@new[row][k]) == (sum uint128 k. buckets@old[row][k]);
                assert true;
            }
            """.trimIndent()
        )
    }
}
