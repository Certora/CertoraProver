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

class NoAbortRuleTest : MoveTestFixture() {
    @Test
    fun `trivial no_abort_rule passes`() {
        addMoveSource("""
            module test_addr::test;
            public fun cvlm_manifest() {
                cvlm::manifest::no_abort_rule(b"test");
            }
            public fun test() {
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `aborting no_abort_rule fails`() {
        addMoveSource("""
            module test_addr::test;
            public fun cvlm_manifest() {
                cvlm::manifest::no_abort_rule(b"test");
            }
            public fun test() {
                abort
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `no_abort_rule passes if Move assert passes`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assume;
            public fun cvlm_manifest() {
                cvlm::manifest::no_abort_rule(b"test");
            }
            public fun test(a: u64, b: u64) {
                cvlm_assume(a == b);
                assert!(a == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `no_abort_rule fails if Move assert fails`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assume;
            public fun cvlm_manifest() {
                cvlm::manifest::no_abort_rule(b"test");
            }
            public fun test(a: u64, b: u64) {
                cvlm_assume(a == b);
                assert!(a != b);
            }
        """.trimIndent())
        assertFalse(verify())
    }

}
