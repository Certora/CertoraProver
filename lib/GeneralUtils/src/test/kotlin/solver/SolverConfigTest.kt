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

package solver

import ksp.dynamicconversion.excludedDynamicConversionAllowed
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class SolverConfigTest {
    @Test
    fun testBasicUsage() {
        // Tests that the basic interfaces work as expected
        assertThrows(ConfigurationException::class.java) { parseOne("cvc5", SolverConfig.Converter()) }
        assertEquals(parseOne("cvc5:bv", SolverConfig.Converter()), SolverConfig.cvc5.bv)
        assertThrows(ConfigurationException::class.java) { parseOne("[cvc5]", SolverConfig.Converter()) }
        assertThrows(ConfigurationException::class.java) { parseOne("[cvc5:bv]", SolverConfig.Converter()) }

        assertEquals(parseList("cvc5", SolverConfig.Converter()), SolverConfig.cvc5.values)
        assertEquals(parseList("cvc5:bv", SolverConfig.Converter()), listOf(SolverConfig.cvc5.bv))
        assertEquals(parseList("[cvc5]", SolverConfig.Converter()), SolverConfig.cvc5.values)
        assertEquals(parseList("[cvc5:bv]", SolverConfig.Converter()), listOf(SolverConfig.cvc5.bv))

        assertThrows(ConfigurationException::class.java) { SolverConfig.cvc5() }
        assertEquals(SolverConfig.cvc5.default, SolverConfig.cvc5(SolverConfig.cvc5.defaultName))
    }

    @Test
    fun testDefaultConfigs() {
        // Basic sanity of default configs
        assertEquals(SolverConfig.altergo.default.solverInfo, AltErgoSolverInfo)
        assertEquals(SolverConfig.bitwuzla.default.solverInfo, BitwuzlaSolverInfo)
        assertEquals(SolverConfig.cvc4.default.solverInfo, CVC4SolverInfo)
        assertEquals(SolverConfig.cvc5.default.solverInfo, CVC5SolverInfo)
        assertEquals(SolverConfig.smtinterpol.default.solverInfo, SmtInterpolSolverInfo)
        assertEquals(SolverConfig.vampire.default.solverInfo, VampireSolverInfo)
        assertEquals(SolverConfig.yices.default.solverInfo, YicesSolverInfo)
        assertEquals(SolverConfig.z3.default.solverInfo, Z3SolverInfo)
    }

    @Test
    fun testZ3Configs() {
        // Some more details on z3 configs, just as an example
        assertEquals(SolverConfig.z3.def, SolverConfig.z3("def"))
        assertTrue(SolverConfig.z3.def in SolverConfig.z3.values)
        assertNotEquals(SolverConfig.z3.default, SolverConfig.z3.eq1)
    }

    @Test
    fun testSolverConfigIndex() {
        // Check default configs for all solvers
        assertEquals(SolverConfig["altergo", "def"], SolverConfig.altergo("def"))
        assertEquals(SolverConfig["bitwuzla", "def"], SolverConfig.bitwuzla("def"))
        assertEquals(SolverConfig["cvc4", "def"], SolverConfig.cvc4("def"))
        assertEquals(SolverConfig["cvc5", "def"], SolverConfig.cvc5("def"))
        assertEquals(SolverConfig["cvc5", "nonlin"], SolverConfig.cvc5.nonlin)
        assertEquals(SolverConfig["vampire", "def"], SolverConfig.vampire("def"))
        assertEquals(SolverConfig["yices", "def"], SolverConfig.yices("def"))
        assertEquals(SolverConfig["z3", "def"], SolverConfig.z3("def"))
        assertEquals(SolverConfig["z3", "eq1"], SolverConfig.z3("eq1"))
    }
    @Test
    fun testSolverConfigInvoke() {
        // Check default configs for all solvers
        assertEquals(SolverConfig("altergo:def"), SolverConfig.altergo.default)
        assertEquals(SolverConfig("bitwuzla:def"), SolverConfig.bitwuzla.default)
        assertEquals(SolverConfig("cvc4:def"), SolverConfig.cvc4.default)
        assertEquals(SolverConfig("cvc5:def"), SolverConfig.cvc5.default)
        assertEquals(SolverConfig("cvc5:nonlin"), SolverConfig.cvc5.nonlin)
        assertEquals(SolverConfig("vampire:def"), SolverConfig.vampire.default)
        assertEquals(SolverConfig("yices:def"), SolverConfig.yices.default)
        assertEquals(SolverConfig("z3:def"), SolverConfig.z3.default)
        assertEquals(SolverConfig("z3:eq1"), SolverConfig.z3.eq1)
    }

    @Test
    fun testCustomBinaryDynamicConversionGatedByEnv() {
        // customBinary is @ExcludeFromDynamicConversion: settable via the `name{...}` syntax only in
        // dev/CI runs; in normal runs the override is ignored (no error).
        val cfg = parseOne("z3:def{customBinary=somebin}", SolverConfig.Converter())
        if (excludedDynamicConversionAllowed()) {
            assertEquals("somebin", cfg.customBinary)
        } else {
            assertNull(cfg.customBinary)
            assertEquals(SolverConfig.z3.def, cfg)
        }
    }

    @Test
    fun testExcludedDynamicConversionGate() {
        assertFalse(excludedDynamicConversionAllowed(null, null))
        assertTrue(excludedDynamicConversionAllowed("1", null))
        assertTrue(excludedDynamicConversionAllowed(null, "true"))
        assertTrue(excludedDynamicConversionAllowed("x", "y"))
    }

    @Test
    fun testDynamicConversionStillWorksForAllowedFields() {
        // Non-excluded fields are still settable via the dynamic conversion path.
        assertEquals(4, parseOne("z3:def{randomSeed=4}", SolverConfig.Converter()).randomSeed)
        assertEquals(
            listOf("--foo"),
            parseOne("z3:def{clOptions=[--foo]}", SolverConfig.Converter()).clOptions
        )
    }

    @Test
    fun testCustomBinarySettableFromNamedCopy() {
        // The regular named-argument copy(...) is unaffected: trusted code can still set it.
        assertEquals("somebin", SolverConfig.cvc5.def.copy(customBinary = "somebin").customBinary)
    }
}
