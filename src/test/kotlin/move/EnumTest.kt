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
}
