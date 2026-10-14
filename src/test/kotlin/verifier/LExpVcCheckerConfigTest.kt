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

import cli.SolverProgramListConverter
import config.Config
import config.ConfigScope
import datastructures.enumSetOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import solver.SolverChoice
import solver.SolverConfig
import solver.Z3SolverInfo
import kotlin.time.Duration

class LExpVcCheckerConfigTest {

    val doOverride = ConfigScope(Config.Smt.OverrideSolvers, true)
    val dontOverride = ConfigScope(Config.Smt.OverrideSolvers, false)
    val allZ3 = ConfigScope(Config.SolverProgramChoice, SolverConfig.z3.values.toTypedArray())
    val onlyZ3Default = ConfigScope(Config.SolverProgramChoice, arrayOf(SolverConfig.z3.default))

    fun getConfig(timeout: Duration) = LExpVcCheckerConfig.fromGlobalConfig(
        "test",
        enumSetOf(),
        timeout
    )

    @Test
    fun solverConfigNoCLITest() {
        fun doChecks(expectedSolvers: SolverChoice? = null, filter: (SolverChoice) -> SolverChoice = { it }) {

            getConfig(SolverConfig.lowTimeoutBound.div(2)).let { config ->
                assertEquals(expectedSolvers ?: filter(SolverChoice.BvCommonAvailableSolversWithClOptions), config.bvSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.LIASolversSmallTimeout), config.liaSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.NIASolversSmallTimeout), config.niaSolvers)
            }
            getConfig((SolverConfig.lowTimeoutBound + SolverConfig.highTimeoutBound).div(2)).let { config ->
                assertEquals(expectedSolvers ?: filter(SolverChoice.BvCommonAvailableSolversWithClOptions), config.bvSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.LIASolversMediumTimeout), config.liaSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.NIASolversMediumTimeout), config.niaSolvers)
            }
            getConfig(SolverConfig.highTimeoutBound.times(2)).let { config ->
                assertEquals(expectedSolvers ?: filter(SolverChoice.BvCommonAvailableSolversWithClOptions), config.bvSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.LIASolversLargeTimeout), config.liaSolvers)
                assertEquals(expectedSolvers ?: filter(SolverChoice.NIASolversLargeTimeout), config.niaSolvers)
            }
        }

        dontOverride.use {
            doChecks()
        }
        doOverride.use {
            doChecks()
        }
        (doOverride + allZ3).use {
            doChecks(expectedSolvers = SolverChoice(SolverConfig.z3.values))
        }
        (doOverride + onlyZ3Default).use {
            doChecks(expectedSolvers = SolverChoice(listOf(SolverConfig.z3.default)))
        }
    }

    @Test
    fun solverConfigWithSpecificCLITest() {
        val configPre = getConfig(SolverConfig.lowTimeoutBound.div(2))
        assertTrue(SolverConfig.z3.def in configPre.niaSolvers)

        val configsLIA = SolverProgramListConverter.convert("[z3:def,cvc5:nonlin,smtinterpol]")
        val configsNIA = SolverProgramListConverter.convert("[cvc4:nonlin,cvc5:def]")
        ConfigScope(Config.LIAsolvers, configsLIA).extend(Config.NIAsolvers, configsNIA).use {
            val config = getConfig(SolverConfig.lowTimeoutBound.div(2))

            assertTrue(SolverConfig.cvc5.nonlin !in config.liaSolvers)
            assertTrue(config.liaSolvers.size <= configsLIA.size)
            config.liaSolvers.forEach { assertTrue(it in SolverConfig.z3.values || it in SolverConfig.smtinterpol.values) }
            config.liaSolvers.forEach { assertTrue(it in SolverChoice.LIASolversSmallTimeout || it in SolverConfig.smtinterpol.values) }

            assertTrue(SolverConfig.cvc4.nonlin in config.niaSolvers)
            assertTrue(config.niaSolvers.size <= configsNIA.size)
            assertTrue(SolverConfig.z3.def !in config.niaSolvers)
            config.niaSolvers.forEach { assertTrue(it in SolverConfig.cvc5.values || it == SolverConfig.cvc4.nonlin) }
            config.niaSolvers.forEach { assertTrue(it in SolverChoice.NIASolversSmallTimeout) }
        }
    }

    @Test
    fun solverConfigWithGenericCLITest() {
        val configs = SolverProgramListConverter.convert("[smtinterpol, z3,cvc5]")
        ConfigScope(Config.SolverProgramChoice, configs).use {
            val config = getConfig(SolverConfig.lowTimeoutBound.div(2))

            assertTrue(SolverConfig.cvc4.lin !in config.liaSolvers)
            assertTrue(config.liaSolvers.size <= configs.size)
            assertTrue(SolverConfig.smtinterpol.values.first() in config.liaSolvers)

            config.liaSolvers.forEach {
                assertTrue(
                    (it in SolverConfig.z3.values || it in SolverConfig.cvc5.values
                        || it in SolverConfig.smtinterpol.values) && it !in SolverConfig.cvc4.values
                )
            }
            config.liaSolvers.forEach {
                assertTrue(
                    it in SolverChoice.LIASolversMediumTimeout || it in SolverConfig.smtinterpol.values)
            }

            assertTrue(SolverConfig.cvc4.nonlin !in config.niaSolvers)
            assertTrue(config.niaSolvers.size <= configs.size)
            config.niaSolvers.forEach {
                assertTrue(
                    (it in SolverConfig.z3.values || it in  SolverConfig.cvc5.values) &&
                        it !in SolverConfig.cvc4.values && it !in SolverConfig.smtinterpol.values
                )
            }
            config.niaSolvers.forEach { assertTrue(it in SolverChoice.NIASolversMediumTimeout) }
        }

        (doOverride + ConfigScope(Config.SolverProgramChoice, configs)).use {
            val config = getConfig(SolverConfig.lowTimeoutBound.div(2))
            assertTrue(config.liaSolvers.first() == SolverConfig.smtinterpol.values.first())
            assertTrue(config.liaSolvers.last() in SolverConfig.cvc5.values)
        }
    }

    @Test
    fun solverConfigWithBothCLITest() {
        val configs = SolverProgramListConverter.convert("[z3:def,cvc5]")
        val configsLIA = SolverProgramListConverter.convert("[z3,cvc4:lin,smtinterpol]")
        ConfigScope(Config.SolverProgramChoice, configs).extend(Config.LIAsolvers, configsLIA).use {
            val config = getConfig(SolverConfig.lowTimeoutBound.div(2))

            assertTrue(SolverConfig.cvc4.lin in config.liaSolvers)
            assertTrue(SolverConfig.cvc5.lin !in config.liaSolvers)
            // - 1 is for smtinterpol which is not presented in the predefined lists
            assertTrue(config.liaSolvers.size - 1 <= configs.size)
            config.liaSolvers.forEach {
                assertTrue(
                    (it in SolverConfig.z3.values || it == SolverConfig.cvc4.lin
                        || it in SolverConfig.smtinterpol.values)
                )
            }
            config.liaSolvers.forEach { assertTrue(it in SolverChoice.LIASolversLargeTimeout || it in SolverConfig.smtinterpol.values) }

            assertTrue(SolverConfig.cvc4.nonlin !in config.niaSolvers)
            assertTrue(SolverConfig.cvc5.nonlin in config.niaSolvers)
            assertTrue(config.niaSolvers.size <= configs.size)
            config.niaSolvers.forEach { assertTrue(it in SolverConfig.cvc5.values || it == SolverConfig.z3.def) }
            config.niaSolvers.forEach { assertTrue(it in SolverChoice.NIASolversLargeTimeout) }
        }
    }
}
