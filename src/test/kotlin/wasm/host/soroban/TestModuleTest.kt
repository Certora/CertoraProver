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

package wasm.host.soroban

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import wasm.*
import wasm.host.soroban.SorobanImport.TestModule
import wasm.wat.*

class TestModuleTest : SorobanTestFixture() {
    @Test
    fun dummy0() {
        assertTrue(verifyWasm {
            certoraAssert(TestModule.dummy0() eq VoidVal)
        })
    }

    @Test
    fun protocol_gated_dummy() {
        assertTrue(verifyWasm {
            certoraAssert(TestModule.protocol_gated_dummy() eq VoidVal)
        })
    }
}
