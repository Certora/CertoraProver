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

class GenericRuleTest : MoveTestFixture() {
    @Test
    fun `two type parameters might be the same`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            public native fun hash<T>(): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
                cvlm::manifest::rule(b"test");
            }
            public fun test<A, B>() {
                cvlm_assert(hash<A>() != hash<B>());
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `two type parameters might be different`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            public native fun hash<T>(): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
                cvlm::manifest::rule(b"test");
            }
            public fun test<A, B>() {
                cvlm_assert(hash<A>() == hash<B>());
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `each type parameter is the same as itself`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            public native fun hash<T>(): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
                cvlm::manifest::rule(b"test");
            }
            public fun test<A, B>() {
                cvlm_assert(hash<A>() == hash<A>());
                cvlm_assert(hash<B>() == hash<B>());
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `type parameter might be the same as concrete type`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            public native fun hash<T>(): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
                cvlm::manifest::rule(b"test");
            }
            public fun test<A>() {
                cvlm_assert(hash<A>() != hash<u64>());
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `type parameter might be different from concrete type`() {
        addMoveSource("""
            module test_addr::test;
            use cvlm::asserts::cvlm_assert;
            public native fun hash<T>(): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
                cvlm::manifest::rule(b"test");
            }
            public fun test<A>() {
                cvlm_assert(hash<A>() == hash<u64>());
            }
        """.trimIndent())
        assertFalse(verify())
    }
}