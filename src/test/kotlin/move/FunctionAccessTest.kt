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

package move

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class FunctionAccessTest : MoveTestFixture() {
    @Test
    fun `function access`() {
        addMoveSource("""
            $testModule
            use 0::access::callFoo;
            public fun test() {
                cvlm_assert(callFoo() == 42);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::function_access(b"callFoo", @0, b"access", b"foo");
            }
            #[allow(unused_function)]
            fun foo(): u64 { 42 }
            public native fun callFoo(): u64;
        """.trimIndent())
        assertTrue(verify())
    }
}
