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
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class NondetTest : MoveTestFixture() {
    override val loadStdlib = true

    @Test
    fun `nondet vector length in range`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = nondet<vector<u64>>();
                cvlm_assert(v.length() <= ${maxInt(64)});
                cvlm_assert(v.length() >= 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `nondet vector element in range`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test(i: u64) {
                let v = nondet<vector<u$size>>();
                cvlm_assert(v[i] <= ${maxInt(size)});
                cvlm_assert(v[i] >= 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `nondet struct field in range`(size: Int) {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                f: u$size
            }
            public fun test() {
                let s = nondet<S>();
                cvlm_assert(s.f <= ${maxInt(size)});
                cvlm_assert(s.f >= 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `nondet vector of struct field in range`(size: Int) {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                f: u$size
            }
            public fun test(i: u64) {
                let v = nondet<vector<S>>();
                cvlm_assert(v[i].f <= ${maxInt(size)});
                cvlm_assert(v[i].f >= 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nondet vector copies are equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = nondet<vector<u64>>();
                let v2 = v1;
                cvlm_assert(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nondet vectors might be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = nondet<vector<u64>>();
                let v2 = nondet<vector<u64>>();
                cvlm_assert(v1 != v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `nondet vectors might not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = nondet<vector<u64>>();
                let v2 = nondet<vector<u64>>();
                cvlm_assert(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }
}
