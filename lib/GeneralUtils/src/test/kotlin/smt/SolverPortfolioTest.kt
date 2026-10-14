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

package smt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class SolverPortfolioTest {

    private val allLIA = UseLIAEnum.entries
    private val allStrategies = BackendStrategyEnum.entries

    @Test
    fun onlySingleRaceCanConflict() {
        for (strategy in allStrategies - BackendStrategyEnum.SINGLE_RACE) {
            for (useBV in listOf(false, true)) {
                for (useLIA in allLIA) {
                    for (useNIA in listOf(false, true)) {
                        assertNull(
                            solverPortfolioConflict(strategy, useBV, useLIA, useNIA),
                            "$strategy with useBV=$useBV, useLIA=$useLIA, useNIA=$useNIA"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun bitVectorSolversRaceAlone() {
        for (useLIA in allLIA) {
            for (useNIA in listOf(false, true)) {
                val conflict = solverPortfolioConflict(
                    BackendStrategyEnum.SINGLE_RACE, useBV = true, useLIA = useLIA, useNIA = useNIA
                )
                if (useLIA == UseLIAEnum.NONE && !useNIA) {
                    assertNull(conflict, "bit-vector solvers alone")
                } else {
                    assertEquals(
                        if (useLIA == UseLIAEnum.WITHOUT_VERIFIER && useNIA) {
                            SolverPortfolioConflict.NIA_WITH_UNVERIFIED_LIA
                        } else {
                            SolverPortfolioConflict.BV_WITH_INT_ARITHMETIC
                        },
                        conflict,
                        "useLIA=$useLIA, useNIA=$useNIA"
                    )
                }
            }
        }
    }

    @Test
    fun niaNeedsLIAToVerifyItsCounterexamples() {
        assertEquals(
            SolverPortfolioConflict.NIA_WITH_UNVERIFIED_LIA,
            solverPortfolioConflict(
                BackendStrategyEnum.SINGLE_RACE,
                useBV = false,
                useLIA = UseLIAEnum.WITHOUT_VERIFIER,
                useNIA = true
            )
        )
        for (useLIA in allLIA - UseLIAEnum.WITHOUT_VERIFIER) {
            assertNull(
                solverPortfolioConflict(BackendStrategyEnum.SINGLE_RACE, useBV = false, useLIA = useLIA, useNIA = true),
                "useLIA=$useLIA"
            )
        }
        assertNull(
            solverPortfolioConflict(
                BackendStrategyEnum.SINGLE_RACE,
                useBV = false,
                useLIA = UseLIAEnum.WITHOUT_VERIFIER,
                useNIA = false
            )
        )
    }

    /**
     * Asking for bit-vector solving and nothing else is a portfolio the user can ask for, under every strategy: it is
     * the LIA/NIA defaults that have to step aside, not the request that has to be rejected.
     */
    @Test
    fun derivedPortfoliosAreConsistent() {
        for (strategy in allStrategies) {
            for (useBV in listOf(false, true)) {
                assertNull(
                    solverPortfolioConflict(strategy, useBV, defaultUseLIA(useBV), defaultUseNIA(useBV)),
                    "$strategy with useBV=$useBV and derived LIA/NIA settings"
                )
            }
        }
        assertEquals(UseLIAEnum.NONE, defaultUseLIA(useBV = true))
        assertEquals(false, defaultUseNIA(useBV = true))
    }
}
