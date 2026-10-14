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

package config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import smt.BackendStrategyEnum
import smt.SolverPortfolioConflict
import smt.UseLIAEnum
import smt.solverPortfolioConflict

class SolverPortfolioConfigTest {

    @Test
    fun bitVectorSolvingLeavesIntArithmeticSolversOut() {
        ConfigScope(Config.Smt.UseBV, true).use {
            assertTrue(Config.Smt.UseBV.get())
            assertEquals(UseLIAEnum.NONE, Config.Smt.UseLIA.get())
            assertFalse(Config.Smt.UseNIA.get())
        }
    }

    /** Asking for bit-vector solvers and a single race is a portfolio of bit-vector solvers, and consistent. */
    @Test
    fun bitVectorSolversCanRaceAlone() {
        ConfigScope(Config.Smt.UseBV, true)
            .extend(Config.Smt.BackendStrategy, BackendStrategyEnum.SINGLE_RACE)
            .use {
                assertEquals(BackendStrategyEnum.SINGLE_RACE, Config.Smt.BackendStrategy.get())
                assertNull(
                    solverPortfolioConflict(
                        Config.Smt.BackendStrategy.get(),
                        Config.Smt.UseBV.get(),
                        Config.Smt.UseLIA.get(),
                        Config.Smt.UseNIA.get(),
                    )
                )
            }
    }

    /** Explicitly requested NIA solvers stay in the portfolio, so the conflict with the bit-vector solvers stands. */
    @Test
    fun explicitIntArithmeticSolversConflictWithBitVectorSolvers() {
        ConfigScope(Config.Smt.UseBV, true).extend(Config.Smt.UseNIA, true).use {
            assertTrue(Config.Smt.UseNIA.get())
            assertEquals(
                SolverPortfolioConflict.BV_WITH_INT_ARITHMETIC,
                solverPortfolioConflict(
                    BackendStrategyEnum.SINGLE_RACE,
                    Config.Smt.UseBV.get(),
                    Config.Smt.UseLIA.get(),
                    Config.Smt.UseNIA.get(),
                )
            )
        }
    }

    @Test
    fun intArithmeticSolversAreOnByDefault() {
        assertTrue(Config.Smt.UseLIA.isDefault())
        assertTrue(Config.Smt.UseNIA.isDefault())
        assertFalse(Config.Smt.UseBV.get())
        assertEquals(UseLIAEnum.WITH_VERIFIER, Config.Smt.UseLIA.get())
        assertTrue(Config.Smt.UseNIA.get())
    }
}
