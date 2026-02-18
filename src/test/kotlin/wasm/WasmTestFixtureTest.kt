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

package wasm

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import verifier.EncodedRule
import wasm.host.NullHost
import wasm.ir.*
import wasm.wat.*

class WasmTestFixtureTest : WasmTestFixture() {
    override val host = NullHost

    private val testmodule = "\$testmodule"
    private val funcName = "\$fnm"

    @Test
    fun compileWAT() {
        val dummy0 = "\$dummy0"
        val wat =
            """
            (module
                (func $dummy0 (import "t" "_") (result i64))
                (func (export "dummy") (param i64) (result i64)
                    call $dummy0
                    i64.const 0
                    i64.or
                )
            )
            """

        watToWasm(wat)
    }

    @Test
    fun compileInvalidWAT() {
        val dummy0 = "\$dummy0"
        val wat =
            """
            (module
                (funk $dummy0 (import "t" "_") (result i64))
                (func (export "dummy") (param i64) (result i64)
                    call $dummy0
                    i64.const 0
                    i64.or
                )
            )
            """.trimIndent()

        val e = assertThrows<IllegalArgumentException> { watToWasm(wat) }

        assertTrue("unexpected token \"funk\"" in e.message!!, "Unexpected error message: ${e.message}")
    }

    @Test
    fun unresolvedCalls() {
        val fake = WatImport("fake", "fake", result = i64)
        val fake2 = WatImport("fake2", "fake2", result = i64)
        val paramName = "\$p1"
        val wat =
            """
            (module $testmodule
                ${fake.import}
                ${fake2.import}
                ${certoraAssert.import}
                (func $funcName (export "entry") (param $paramName i64) (result i64)
                    i32.const 0
                    call $certoraAssert
                    call $fake
                    call $fake2
                    return
                )
            )
            """

        val wasm = watToWasm(wat);
        val program = WasmEntryPoint.wasmToTAC(wasm.toFile(), setOf("entry"), NullHost, optimize = false).single()
        val code = program.let { it.getOrNull() ?: error("wasmToTAC did not produce a rule as expected") }.code

        assertEquals(setOf(WasmName("\$fake.fake"), WasmName("\$fake2.fake2")), code.findUnresolveCalls())
    }

    @Test
    fun proverFailure() {
        val paramName = "\$p1"
        val wat =
            """
            (module $testmodule
                ${certoraAssert.import}
                (func $funcName (export "entry") (param $paramName i64) (result i64)
                    i32.const 0
                    call $certoraAssert
                    i64.const 0
                )
            )
            """

        assertFalse(verifyWasm(wat, "entry"))
    }

    @Test
    fun proverSuccess() {
        val paramName = "\$p1"
        val wat =
            """
            (module $testmodule
                ${certoraAssert.import}
                (func $funcName (export "entry") (param $paramName i64) (result i64)
                    i32.const 1
                    call $certoraAssert
                    i64.const 0
                )
            )
            """

        assertTrue(verifyWasm(wat, "entry"))
    }

    @Test
    fun traps() {
        val wat = watFunc("entry", i64) { arg ->
            block {
                branchIf(arg eq i64(0))
                +"unreachable"
            }
            certoraAssert(i32(1))
        }
        assertFalse(verifyWasm(wat, "entry"))
        assertFalse(verifyWasm(wat, "entry", assumeNoTraps = false))
        assertTrue(verifyWasm(wat, "entry", assumeNoTraps = true))
    }
}
