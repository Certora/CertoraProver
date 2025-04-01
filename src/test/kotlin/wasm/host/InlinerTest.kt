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

package wasm.host

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import wasm.WasmTestFixture
import wasm.certoraAssert
import wasm.certoraAssume

class InlinerTest: WasmTestFixture() {
    override val host = NullHost

    @Test
    fun indirectWithWrongTypes() {

        fun wat(provable: Boolean) = """
            (module ${"\$indirect"}
              ${certoraAssume.import}
              ${certoraAssert.import}
              (func ${"\$f0"} (param i32) (result i32)
                local.get 0
                i32.const 1
                i32.add)
              (func ${"\$f1"} (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add)
              (func ${"\$main"} (export "main") (param i32 i32 i32)
                local.get 1
                i32.const 0
                i32.eq
                local.get 1
                i32.const 1
                i32.eq
                i32.or
                call $certoraAssume
                local.get 0
                local.get 1
                call_indirect (param i32) (result i32)
                local.get 0
                i32.const 1
                i32.add
                ${if (provable) { "i32.eq" } else { "i32.ne" }}
                call $certoraAssert)
              (table ${"\$table"} funcref (elem ${"$"}f0 ${"$"}f1)))
            """
        // Can't prove the false property
        Assertions.assertFalse(
            verifyWasm(wat(false), "main", assumeNoTraps = true)
        )
        // Can't prove the false property
        Assertions.assertFalse(
            verifyWasm(wat(false), "main", assumeNoTraps = false)
        )
        // Can't prove the trap is avoided
        Assertions.assertFalse(
            verifyWasm(wat(true), "main", assumeNoTraps = false)
        )
        // Assuming no traps we can prove the true property
        Assertions.assertTrue(
            verifyWasm(wat(true), "main", assumeNoTraps = true)
        )
    }
    @Test
    fun indirectRecursive() {
        fun wat(provable: Boolean) = """
        (module ${"$"}indirect
          (type (;0;) (func (param i32 i32 i32) (result i32)))
          (type (;1;) (func (param i32 i32 i32)))
          ${certoraAssert.import}
          (func ${"$"}f1 (type 0) (param i32 i32 i32) (result i32)
            local.get 0
            local.get 1
            local.get 2
            local.get 2
            call_indirect (type 0))
          (func ${"$"}f2 (type 0) (param i32 i32 i32) (result i32)
            local.get 0)
          (func ${"$"}main (type 1) (param i32 i32 i32)
            local.get 0
            local.get 1
            local.get 2
            local.get 2
            call_indirect (type 0)
            i32.const ${if (provable) { 1 } else { 0 }}
            call $certoraAssert
            return)
          (table ${"$"}table funcref (elem ${"$"}f1 ${"$"}f2))
          (export "main" (func ${"$"}main))
        )
        """.trimIndent()

        // Can't prove the false property
        Assertions.assertFalse(
            verifyWasm(wat(false), "main", assumeNoTraps = true, recursionLimit = 1, recursionLimitIsError = true)
        )
        // Can't prove the true property AND the recursion limit
        Assertions.assertFalse(
            verifyWasm(wat(true), "main", assumeNoTraps = true, recursionLimit = 1, recursionLimitIsError = true)
        )
        // Can't prove the false property (even assuming recursion limit)
        Assertions.assertFalse(
            verifyWasm(wat(false), "main", assumeNoTraps = true, recursionLimit = 1, recursionLimitIsError = false)
        )
        Assertions.assertTrue(
            verifyWasm(wat(true), "main", assumeNoTraps = true, recursionLimit = 1, recursionLimitIsError = false)
        )
    }

    @Test
    fun recursiveCall() {
        fun wat(assertValue: Int) = """
        (module ${"$"}indirect
          ${certoraAssert.import}
          (func ${"$"}f1 (param i32 i32) (result i32)
            block
                local.get 0
                local.get 1
                i32.ge_s
                br_if 0
                i32.const 1
                local.get 0
                i32.add
                local.get 1
                call ${"$"}f1
                return
            end
            local.get 0)
          (func ${"$"}main (export "main") (param i32)
            i32.const 0
            i32.const 3
            call ${"$"}f1
            i32.const ${assertValue}
            i32.eq
            call $certoraAssert
            return)
        )
        """
        val provable = 3
        val notProvable = 4

        Assertions.assertFalse(verifyWasm(wat(provable), "main", assumeNoTraps = true, recursionLimit = 2, recursionLimitIsError = true))
        Assertions.assertFalse(verifyWasm(wat(provable), "main", assumeNoTraps = false, recursionLimit = 2, recursionLimitIsError = true))

        // If we _assume_ the recursion assertion with insufficient inlining, the true property should be provable
        Assertions.assertTrue(verifyWasm(wat(provable), "main", assumeNoTraps = true, recursionLimit = 2, recursionLimitIsError = false))
        // If we _assume_ the recursion assertion with insufficient inlining, the false property should be provable
        Assertions.assertTrue(verifyWasm(wat(notProvable), "main", assumeNoTraps = true, recursionLimit = 2, recursionLimitIsError = false))

        // If we _assume_ the recursion assertion with sufficient inlining, the true property should be provable
        Assertions.assertTrue(verifyWasm(wat(provable), "main", assumeNoTraps = true, recursionLimit = 3, recursionLimitIsError = false))
        // If we _assume_ the recursion assertion with sufficient inlining, the false property should not be provable
        Assertions.assertFalse(verifyWasm(wat(notProvable), "main", assumeNoTraps = true, recursionLimit = 3, recursionLimitIsError = false))
    }


}
