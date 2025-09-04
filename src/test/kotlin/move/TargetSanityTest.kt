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
import utils.*

class TargetSanityTest : MoveTestFixture() {
    @Test
    fun `sanity passes`() {
        addMoveSource("""
            module test_addr::test;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target_sanity();
            }
            public fun target1() {
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::target1-Assertions" to true,
                "$testModuleAddrHex::test::target1-Reached end of function" to true
            ),
            verifyMany()
        )
    }

    @Test
    fun `sanity fails due to vacuity`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::internal_asserts::cvlm_internal_assume;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target_sanity();
            }
            public fun target1(a: u32, b: u32) {
                cvlm_internal_assume(a == b + 1);
                cvlm_internal_assume(a == b + 2);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::target1-Assertions" to true,
                "$testModuleAddrHex::test::target1-Reached end of function" to false
            ),
            verifyMany()
        )
    }

    @Test
    fun `sanity fails due to failed internal assertion`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::internal_asserts::cvlm_internal_assert;
            public fun cvlm_manifest() {
                cvlm::manifest::target(@test_addr, b"test", b"target1");
                cvlm::manifest::target_sanity();
            }
            public fun target1(a: u32, b: u32) {
                cvlm_internal_assert(a == b);
            }
        """.trimIndent())
        assertEquals(
            mapOf(
                "$testModuleAddrHex::test::target1-Assertions" to false,
                "$testModuleAddrHex::test::target1-Reached end of function" to true
            ),
            verifyMany()
        )
    }
}