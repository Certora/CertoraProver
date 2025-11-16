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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.host.soroban

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import wasm.certoraAssert
import wasm.certoraAssume
import wasm.host.soroban.SorobanImport.Context
import wasm.host.soroban.SorobanImport.Crypto
import wasm.wat.eq
import wasm.wat.i64
import wasm.wat.invoke
import wasm.wat.ne
import wasm.wat.watFunc

class CryptoModuleTest : SorobanTestFixture() {
    @Test
    fun hash_is_function() {
        val test = watFunc("test", i64, i64) { b1, b2 ->
            val eqInput = Context.obj_cmp(b1, b2)
            certoraAssume(eqInput eq i64(0))

            val hash1 = Crypto.keccak256(b1)
            val hash2 = Crypto.keccak256(b2)

            val eqOutput = Context.obj_cmp(hash1, hash2)

            certoraAssert(eqOutput eq i64(0))
        }

        Assertions.assertTrue(
            verifyWasm(test, "test", assumeNoTraps = true, optimize = true)
        )
    }

    @Test
    fun hash_injective() {
        val test = watFunc("test", i64, i64) { b1, b2 ->
            val eqInput = Context.obj_cmp(b1, b2)
            certoraAssume(eqInput ne i64(0))

            val hash1 = Crypto.keccak256(b1)
            val hash2 = Crypto.keccak256(b2)

            val eqOutput = Context.obj_cmp(hash1, hash2)

            certoraAssert(eqOutput ne i64(0))
        }

        Assertions.assertTrue(
            verifyWasm(test, "test", assumeNoTraps = true, optimize = true)
        )
    }
}
