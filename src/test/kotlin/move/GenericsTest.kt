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

class GenericsTest : MoveTestFixture() {
    override val loadStdlib = true

    @Test
    fun `simple generic function`() {
        addMoveSource("""
            $testModule
            fun foo<T>(x: T): vector<T> {
                vector[x]
            }

            public fun test() {
                let v = foo(42);
                cvlm_assert(vector::length(&v) == 1);
                cvlm_assert(*vector::borrow(&v, 0) == 42);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic struct`() {
        addMoveSource("""
            $testModule
            public struct Foo<T: copy + drop> has copy, drop {
                x: T
            }
            public fun test() {
                let f = Foo<u64> { x: 42 };
                cvlm_assert(f.x == 42);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic struct and function`() {
        addMoveSource("""
            $testModule
            public struct Foo<T: copy + drop> has copy, drop {
                x: T
            }
            fun foo<T: copy + drop>(f: Foo<T>): T {
                f.x
            }
            public fun test() {
                let f = Foo<u64> { x: 42 };
                cvlm_assert(foo(f) == 42);
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
