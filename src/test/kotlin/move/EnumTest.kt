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

class EnumTest : MoveTestFixture() {
    override val bytecodeVersion get() = 7

    @Test
    fun `simple enum`() {
        addMoveSource("""
            $testModule
            public enum E { A, B, C }
            public fun test(val: u32) {
                let e = match (val % 3) {
                    0 => E::A,
                    1 => E::B,
                    _ => E::C
                };
                let v = match (e) {
                    E::A => 0,
                    E::B => 1,
                    E::C => 2
                };
                cvlm_assert(v == val % 3);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `one field`() {
        addMoveSource("""
            $testModule
            public enum E {
                A { x: u32 },
                B { y: u16 },
                C { z: u8 }
            }
            public fun test(a: u32, b: u16, c: u8, sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: a },
                    1 => E::B { y: b },
                    _ => E::C { z: c }
                };
                match (e) {
                    E::A { x: n } => cvlm_assert(n == a),
                    E::B { y: n } => cvlm_assert(n == b),
                    E::C { z: n } => cvlm_assert(n == c)
                };
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `one field const value`() {
        addMoveSource("""
            $testModule
            public enum E {
                A { x: u32 },
                B { y: u16 },
                C { z: u8 }
            }
            public fun test(sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: 1 },
                    1 => E::B { y: 2 },
                    _ => E::C { z: 3 }
                };
                match (e) {
                    E::A { x: n } => cvlm_assert(n == 1),
                    E::B { y: n } => cvlm_assert(n == 2),
                    E::C { z: n } => cvlm_assert(n == 3)
                };
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `struct field`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                val: u32,
            }
            public enum E has drop, copy {
                A { x: u32, s: S },
                B { s: S, x: u32 },
            }
            fun extract(e: &E): (u32, u32) {
                match (e) {
                    E::A { x: n, s: s } => (*n, s.val),
                    E::B { s: s, x: n } => (*n, s.val),
                }
            }
            public fun test(a: u32, b: u32, sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: a, s: S { val: b } },
                    _ => E::B { s: S { val: b }, x: a },
                };
                let (x, v) = extract(&e);
                cvlm_assert(x == a);
                cvlm_assert(v == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `vector field`() {
        addMoveSource("""
            $testModule
            public enum E {
                A { x: u32, v: vector<u32> },
                B { v: vector<u32>, x: u32 },
            }
            public fun test(a: u32, b: u32, sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: a, v: vector[b] },
                    _ => E::B { v: vector[b], x: a },
                };
                let (x, v) = match (e) {
                    E::A { x: n, v: m } => (n, m),
                    E::B { v: m, x: n } => (n, m),
                };
                cvlm_assert(x == a);
                cvlm_assert(v.length() == 1);
                cvlm_assert(v[0] == b);
            }
        """.trimIndent())
        assertTrue(verify(loadStdlib = true))
    }

    @Test
    fun `generic enum`() {
        addMoveSource("""
            $testModule
            public enum E<T, U> {
                A { x: T },
                B { y: U },
            }
            public fun test(a: u32, b: vector<u8>, sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: a },
                    _ => E::B { y: b },
                };
                match (e) {
                    E::A { x: n } => cvlm_assert(n == a),
                    E::B { y: n } => cvlm_assert(n == b),
                };
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic enum from generic func`() {
        addMoveSource("""
            $testModule
            public enum E<T: drop + copy, U: drop + copy> {
                A { x: T },
                B { y: U },
            }
            public fun test<T: drop + copy, U: drop + copy>(a: T, b: U, sel: u8) {
                let e = match (sel) {
                    0 => E::A { x: a },
                    _ => E::B { y: b },
                };
                match (e) {
                    E::A { x: n } => cvlm_assert(n == a),
                    E::B { y: n } => cvlm_assert(n == b),
                };
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `simple enum equal`() {
        addMoveSource("""
            $testModule
            public enum E has drop { A, B, C }
            public fun test() {
                cvlm_assert(E::A == E::A);
                cvlm_assert(E::B == E::B);
                cvlm_assert(E::C == E::C);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `simple enum not equal`() {
        addMoveSource("""
            $testModule
            public enum E has drop { A, B, C }
            public fun test() {
                cvlm_assert(E::A == E::B);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `enum with fields equal`() {
        addMoveSource("""
            $testModule
            public enum E has drop {
                A { x: u32, y: u16 },
                B { z: u8 },
            }
            public fun test(a: u32, b: u16, c: u8) {
                let e1 = E::A { x: a, y: b };
                let e2 = E::A { x: a, y: b };
                cvlm_assert(e1 == e2);

                let e3 = E::B { z: c };
                let e4 = E::B { z: c };
                cvlm_assert(e3 == e4);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `enum with fields not equal`() {
        addMoveSource("""
            $testModule
            public enum E has drop {
                A { x: u32, y: u16 },
                B { z: u8 },
            }
            public fun test(a: u32, aa: u32, b: u16) {
                let e1 = E::A { x: a, y: b };
                let e2 = E::A { x: aa, y: b };
                cvlm_assert(e1 == e2);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `nondet enum`() {
        addMoveSource("""
            $testModule
            public enum EnumB<T> {
                A { x: u8 }, B { y: u16, z: T }
            }
            public fun test(e1: EnumB<u16>) {
                let v = match (e1) {
                    EnumB::A { x } => {
                        cvlm_assume_msg(x == 2, b"x must be 2");
                        x as u16
                    },
                    EnumB::B { y: y, z: z } => {
                        cvlm_assume_msg(z == y * 2, b"z must be double of y");
                        z / y
                    }
                };
                cvlm_assert(v == 2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nondet enum no vacuity`() {
        addMoveSource("""
            $testModule
            public enum E has drop { A, B }
            public fun test(e: E) {
                let v = match (e) {
                    E::A => 1,
                    E::B => 2,
                };
                cvlm_assert(v == 3);
            }
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `hash of enum`() {
        addMoveSource("""
            module 0::hashes;
            public enum E has copy, drop {
                A { x: u32 },
                B { x: u32, y: u32 },
                C
            }
            public fun a(x: u32): E { E::A { x } }
            public fun b(x: u32, y: u32): E { E::B { x, y } }
            public fun c(): E { E::C }
            public native fun hash(e: E): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{hash, a, b, c};
            public fun test() {
                cvlm_assert(hash(a(1)) == hash(a(1)));
                cvlm_assert(hash(b(1, 2)) == hash(b(1, 2)));
                cvlm_assert(hash(c()) == hash(c()));
                cvlm_assert(hash(a(1)) != hash(a(2)));
                cvlm_assert(hash(b(1, 2)) != hash(b(2, 1)));
                cvlm_assert(hash(a(1)) != hash(b(1, 2)));
                cvlm_assert(hash(a(1)) != hash(c()));
                cvlm_assert(hash(b(1, 2)) != hash(c()));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of nested enum`() {
        addMoveSource("""
            module 0::hashes;
            public enum Inner has copy, drop {
                A { x: u32 },
                B { y: vector<u8> }
            }
            public struct S has copy, drop {
                inner: Inner,
                z: u32
            }
            public fun a(x: u32, z: u32): S { S { inner: Inner::A { x }, z } }
            public fun b(y: vector<u8>, z: u32): S { S { inner: Inner::B { y }, z } }
            public native fun hash(s: S): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{hash, a, b};
            public fun test() {
                cvlm_assert(hash(a(1, 2)) == hash(a(1, 2)));
                cvlm_assert(hash(b(b"hello", 2)) == hash(b(b"hello", 2)));
                cvlm_assert(hash(a(1, 2)) != hash(a(1, 3)));
                cvlm_assert(hash(a(1, 2)) != hash(a(2, 2)));
                cvlm_assert(hash(a(1, 2)) != hash(b(b"hello", 2)));
                cvlm_assert(hash(b(b"hello", 2)) != hash(b(b"world", 2)));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of enum depends on variant of nondet value`() {
        addMoveSource("""
            module 0::hashes;
            public enum E has copy, drop {
                A { x: u32 },
                B { x: u32 }
            }
            public fun x(e: &E): u32 {
                match (e) {
                    E::A { x: n } => *n,
                    E::B { x: n } => *n
                }
            }
            public fun is_a(e: &E): bool {
                match (e) {
                    E::A { x: _ } => true,
                    E::B { x: _ } => false
                }
            }
            public fun a(x: u32): E { E::A { x } }
            public native fun hash(e: E): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{E, hash, a, x, is_a};
            public fun test(e: E) {
                cvlm_assert((hash(e) == hash(a(x(&e)))) == is_a(&e));
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
