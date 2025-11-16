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

class VecTest : MoveTestFixture() {
    override val loadStdlib = true
    override val bytecodeVersion get() = 7

    @Test
    fun `single element`() {
        addMoveSource("""
            $testModule
            public fun test(n: u32) {
                let v = vector[n];
                cvlm_assert(vector::length(&v) == 1);
                cvlm_assert(*vector::borrow(&v, 0) == n);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `two elements`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                let v = vector[a, b];
                cvlm_assert(vector::length(&v) == 2);
                cvlm_assert(*vector::borrow(&v, 0) == a);
                cvlm_assert(*vector::borrow(&v, 1) == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `empty`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = vector::empty<u32>();
                cvlm_assert(vector::length(&v) == 0);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `constant`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let v = vector[1, 2, 3, 4];
                cvlm_assert(vector::length(&v) == 4);
                cvlm_assert(*vector::borrow(&v, 0) == 1);
                cvlm_assert(*vector::borrow(&v, 1) == 2);
                cvlm_assert(*vector::borrow(&v, 2) == 3);
                cvlm_assert(*vector::borrow(&v, 3) == 4);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `bounds check`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32, c: u32) {
                let mut v = vector[a, b, c];
                let r = vector::borrow_mut(&mut v, 3);
                *r = 4;
                cvlm_assert(true);
            }
        """.trimIndent())
        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `push_back`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                let mut v = vector[a];
                vector::push_back(&mut v, b);
                cvlm_assert(vector::length(&v) == 2);
                cvlm_assert(*vector::borrow(&v, 0) == a);
                cvlm_assert(*vector::borrow(&v, 1) == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `pop_back`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                let mut v = vector[a, b];
                let r = vector::pop_back(&mut v);
                cvlm_assert(r == b);
                cvlm_assert(vector::length(&v) == 1);
                cvlm_assert(*vector::borrow(&v, 0) == a);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `pop_back empty`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32) {
                let mut v = vector[a];
                cvlm_assert(vector::length(&v) == 1);
                vector::pop_back(&mut v);
                cvlm_assert(vector::length(&v) == 0);
                vector::pop_back(&mut v);
                cvlm_assert(true);
            }
        """.trimIndent())
        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `single element in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(n: u32) {
                let s = S { x: 42, v: vector[n] };
                cvlm_assert(s.x == 42);
                cvlm_assert(vector::length(&s.v) == 1);
                cvlm_assert(*vector::borrow(&s.v, 0) == n);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `two elements in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(a: u32, b: u32) {
                let s = S { x: 99, v: vector[a, b] };
                cvlm_assert(s.x == 99);
                cvlm_assert(vector::length(&s.v) == 2);
                cvlm_assert(*vector::borrow(&s.v, 0) == a);
                cvlm_assert(*vector::borrow(&s.v, 1) == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `bounds check in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(a: u32, b: u32, c: u32) {
                let mut s = S { x: 7, v: vector[a, b, c] };
                let r = vector::borrow_mut(&mut s.v, 3);
                *r = 4;
                cvlm_assert(s.x == 7);
                cvlm_assert(true);
            }
        """.trimIndent())
        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `push_back in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(a: u32, b: u32) {
                let mut s = S { x: 123, v: vector[a] };
                vector::push_back(&mut s.v, b);
                cvlm_assert(s.x == 123);
                cvlm_assert(vector::length(&s.v) == 2);
                cvlm_assert(*vector::borrow(&s.v, 0) == a);
                cvlm_assert(*vector::borrow(&s.v, 1) == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `pop_back in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(a: u32, b: u32) {
                let mut s = S { x: 55, v: vector[a, b] };
                let r = vector::pop_back(&mut s.v);
                cvlm_assert(s.x == 55);
                cvlm_assert(r == b);
                cvlm_assert(vector::length(&s.v) == 1);
                cvlm_assert(*vector::borrow(&s.v, 0) == a);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `pop_back empty in struct`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                v: vector<u32>
            }
            public fun test(a: u32) {
                let mut s = S { x: 0, v: vector[a] };
                cvlm_assert(s.x == 0);
                cvlm_assert(vector::length(&s.v) == 1);
                vector::pop_back(&mut s.v);
                cvlm_assert(vector::length(&s.v) == 0);
                vector::pop_back(&mut s.v);
                cvlm_assert(true);
            }
        """.trimIndent())
        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `vector of structs`() {
        addMoveSource("""
            $testModule
            public struct S has copy, drop {
                x: u32,
                y: u32
            }
            public fun test(a: u32, b: u32) {
                let mut v = vector[S { x: a, y: b }, S { x: b, y: a }];
                cvlm_assert(vector::length(&v) == 2);
                cvlm_assert(vector::borrow(&v, 0).x == a);
                cvlm_assert(vector::borrow(&v, 0).y == b);
                cvlm_assert(vector::borrow(&v, 1).x == b);
                cvlm_assert(vector::borrow(&v, 1).y == a);
                vector::push_back(&mut v, S { x: 100, y: 200 });
                cvlm_assert(vector::length(&v) == 3);
                cvlm_assert(vector::borrow(&v, 2).x == 100);
                cvlm_assert(vector::borrow(&v, 2).y == 200);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `vector of enums`() {
        addMoveSource("""
            $testModule
            public enum E has copy, drop {
                A { x: u32, y: u32 },
                B { x: u16, y: u16 },
                C { x: u8, y: vector<u8> }
            }
            public fun test(a: u32, b: u16, c: u8) {
                let v = vector[
                    E::A { x: a, y: a },
                    E::B { x: b, y: b },
                    E::C { x: c, y: vector[0, 1, 2] }
                ];
                cvlm_assert(v.length() == 3);
                cvlm_assert(match (v[0]) {
                    E::A { x: n, y: m } => n == a && m == a,
                    _ => false
                });
                cvlm_assert(match (v[1]) {
                    E::B { x: n, y: m } => n == b && m == b,
                    _ => false
                });
                cvlm_assert(match (v[2]) {
                    E::C { x: n, y: m } => n == c && m.length() == 3 && m[0] == 0 && m[1] == 1 && m[2] == 2 && m == vector[0, 1, 2],
                    _ => false
                });
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `vector of vectors`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                let mut v = vector[vector[a, b], vector[b, a]];
                cvlm_assert(vector::length(&v) == 2);
                cvlm_assert(vector::length(vector::borrow(&v, 0)) == 2);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 0), 0) == a);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 0), 1) == b);
                cvlm_assert(vector::length(vector::borrow(&v, 1)) == 2);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 1), 0) == b);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 1), 1) == a);
                vector::push_back(&mut v, vector[b, b]);
                cvlm_assert(vector::length(&v) == 3);
                cvlm_assert(vector::length(vector::borrow(&v, 2)) == 2);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 2), 0) == b);
                cvlm_assert(*vector::borrow(vector::borrow(&v, 2), 1) == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `swap`() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                let mut v = vector[123, a, 456, b, 789];
                vector::swap(&mut v, 1, 3);
                cvlm_assert(vector::length(&v) == 5);
                cvlm_assert(*vector::borrow(&v, 1) == b);
                cvlm_assert(*vector::borrow(&v, 3) == a);
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
