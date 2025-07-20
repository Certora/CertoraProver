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

class VecEqualityTest : MoveTestFixture() {
    override val loadStdlib = true

    @Test
    fun `empty vectors are equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector::empty<u32>();
                let v2 = vector::empty<u32>();
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `same integer elements are equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[42, 43];
                let v2 = vector[42, 43];
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `different integer elements are not equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[42, 43];
                let v2 = vector[43, 42];
                cvlm_assert!(v1 != v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `same string literal elements are equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[b"hello", b"world"];
                let v2 = vector[b"hello", b"world"];
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }


    @Test
    fun `different string literal elements are not equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[b"hello", b"world"];
                let v2 = vector[b"world", b"hello"];
                cvlm_assert!(v1 != v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `structs with same string literals are equal`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                y: vector<u8>
            }
            public fun test() {
                let s1 = S { x: 1, y: b"hello" };
                let s2 = S { x: 1, y: b"hello" };
                cvlm_assert!(s1 == s2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `structs with different string literals are not equal`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                y: vector<u8>
            }
            public fun test() {
                let s1 = S { x: 1, y: b"hello" };
                let s2 = S { x: 1, y: b"world" };
                cvlm_assert!(s1 != s2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `vector copy is equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[1, 2, 3];
                let v2 = v1;
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `modified vector element may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[1, 2, 3];
                let mut v2 = v1;
                *vector::borrow_mut(&mut v2, 1) = 4;
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `push_back may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[1, 2, 3];
                let mut v2 = v1;
                v2.push_back(4);
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `pop_back may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[1, 2, 3];
                let mut v2 = v1;
                v2.pop_back();
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `push_back of same element is equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v1 = vector[1, 2, 3];
                let mut v2 = v1;
                v1.push_back(4);
                v2.push_back(4);
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `push_back of different element may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v1 = vector[1, 2, 3];
                let mut v2 = v1;
                v1.push_back(4);
                v2.push_back(5);
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `pop_back on same vector is equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v1 = vector[1, 2, 3];
                let mut v2 = v1;
                v1.pop_back();
                v2.pop_back();
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `pop_back on different vectors may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut v1 = vector[1, 2, 3];
                let mut v2 = vector[1, 2, 4];
                v1.pop_back();
                v2.pop_back();
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `copy of vector of struct is equal`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                y: u32
            }
            public fun test() {
                let s1 = vector[S { x: 1, y: 2 }];
                let s2 = s1;
                cvlm_assert!(s1 == s2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `modified field in vector element may not be equal`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                y: u32
            }
            public fun test() {
                let s1 = vector[S { x: 1, y: 2 }];
                let mut s2 = s1;
                vector::borrow_mut(&mut s2, 0).x = 3;
                cvlm_assert!(s1 == s2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `copy of vector of vector is equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[vector[1, 2], vector[3, 4]];
                let v2 = v1;
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `modified element in vector of vector may not be equal`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[vector[1, 2], vector[3, 4]];
                let mut v2 = v1;
                *vector::borrow_mut(vector::borrow_mut(&mut v2, 1), 0) = 6;
                cvlm_assert!(v1 == v2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `modified element in vector of vector does not affect other element equality`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v1 = vector[vector[1, 2], vector[3, 4]];
                let mut v2 = v1;
                *vector::borrow_mut(vector::borrow_mut(&mut v2, 1), 0) = 6;
                cvlm_assert!(vector::borrow(&v1, 0) == vector::borrow(&v2, 0));
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
