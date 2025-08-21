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

class SummaryTest : MoveTestFixture() {
    override val loadStdlib = true

    @Test
    fun `summary`() {
        addMoveSource("""
            $testModule
            fun foo(n: u32): u32 {
                n + 42
            }
            public fun test() {
                cvlm_assert(foo(10) == 12);
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::summaries;
            fun foo_summary(n: u32): u32 {
                n + 2
            }
            fun cvlm_manifest() {
                cvlm::manifest::summary(b"foo_summary", @test_addr, b"test", b"foo")
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic summary`() {
        addMoveSource("""
            $testModule
            fun foo<T : copy>(t: T): vector<T> {
                vector[t, t]
            }
            public fun test() {
                let v = foo<u32>(10);
                cvlm_assert(v.length() == 1);
                cvlm_assert(v[0] == 10);
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::summaries;
            fun foo_summary<T : copy>(t: T): vector<T> {
                vector[t]
            }
            fun cvlm_manifest() {
                cvlm::manifest::summary(b"foo_summary", @test_addr, b"test", b"foo")
            }
        """.trimIndent())
        assertTrue(verify())
    }


    @Test
    fun `ghost summary same args same result`() {
        addMoveSource("""
            $testModule
            native fun foo(a: u64, b: u64): u64;
            public fun test() {
                cvlm_assert(foo(10, 20) == foo(10, 20));
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::summaries;
            native fun foo_summary(a: u64, b: u64): u64;
            fun cvlm_manifest() {
                cvlm::manifest::summary(b"foo_summary", @test_addr, b"test", b"foo");
                cvlm::manifest::ghost(b"foo_summary");
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost summary different args maybe different result`() {
        addMoveSource("""
            $testModule
            native fun foo(a: u64, b: u64): u64;
            public fun test() {
                cvlm_assert(foo(10, 20) == foo(10, 21));
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::summaries;
            native fun foo_summary(a: u64, b: u64): u64;
            fun cvlm_manifest() {
                cvlm::manifest::summary(b"foo_summary", @test_addr, b"test", b"foo");
                cvlm::manifest::ghost(b"foo_summary");
            }
        """.trimIndent())
        assertFalse(verify())
    }
}
