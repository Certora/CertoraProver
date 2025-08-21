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

class StructTest : MoveTestFixture() {
    @Test
    fun `single u32 field`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                field: u32
            }
            public fun test(val: u32) {
                let s = S { field: val };
                cvlm_assert(s.field == val)
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `single u32 field wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                field: u32
            }
            public struct Outer has drop {
                x: u32,
                s: S
            }
            public fun test(x: u32, val: u32) {
                let s = S { field: val };
                let o = Outer { x: x, s: s };
                cvlm_assert(o.x == x);
                cvlm_assert(o.s.field == val);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `two u32 fields`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                a: u32,
                b: u32
            }
            public fun test(a: u32, b: u32) {
                let s = S { a: a, b: b };
                cvlm_assert(s.a == a);
                cvlm_assert(s.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `two u32 fields wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                a: u32,
                b: u32
            }
            public struct Outer has drop {
                x: u32,
                s: S
            }
            public fun test(x: u32, a: u32, b: u32) {
                let s = S { a: a, b: b };
                let o = Outer { x: x, s: s };
                cvlm_assert(o.x == x);
                cvlm_assert(o.s.a == a);
                cvlm_assert(o.s.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nested struct`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test(a: u32, b: u32, n: u32) {
                let s = S { a: a, b: b };
                let t = T { s: s, n: n };
                cvlm_assert(t.s.a == a);
                cvlm_assert(t.s.b == b);
                cvlm_assert(t.n == n);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nested struct constant`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test() {
                let t = T { s: S { a: 1, b: 2 }, n: 3 };
                cvlm_assert(t.s.a == 1);
                cvlm_assert(t.s.b == 2);
                cvlm_assert(t.n == 3);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `nested struct wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public struct Outer has drop {
                x: u32,
                t: T
            }
            public fun test(x: u32, a: u32, b: u32, n: u32) {
                let s = S { a: a, b: b };
                let t = T { s: s, n: n };
                let o = Outer { x: x, t: t };
                cvlm_assert(o.x == x);
                cvlm_assert(o.t.s.a == a);
                cvlm_assert(o.t.s.b == b);
                cvlm_assert(o.t.n == n);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `read nested struct`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test(a: u32, b: u32, n: u32) {
                let s = S { a: a, b: b };
                let t = T { s: s, n: n };
                let s2 = t.s;
                cvlm_assert(s2.a == a);
                cvlm_assert(s2.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `read nested struct wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public struct Outer has drop {
                x: u32,
                t: T
            }
            public fun test(x: u32, a: u32, b: u32, n: u32) {
                let s = S { a: a, b: b };
                let t = T { s: s, n: n };
                let o = Outer { x: x, t: t };
                let s2 = o.t.s;
                cvlm_assert(o.x == x);
                cvlm_assert(s2.a == a);
                cvlm_assert(s2.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write nested struct fields`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let mut t = T { s: s, n: 0x13 };
                let s2 = &mut t.s;
                s2.a = 0x21;
                s2.b = 0x22;
                cvlm_assert(s.a == 0x11);
                cvlm_assert(s.b == 0x12);
                cvlm_assert(t.s.a == 0x21);
                cvlm_assert(t.s.b == 0x22);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write nested struct fields wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public struct Outer has drop {
                x: u32,
                t: T
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let t = T { s: s, n: 0x13 };
                let mut o = Outer { x: 0x10, t: t };
                let s2 = &mut o.t.s;
                s2.a = 0x21;
                s2.b = 0x22;
                cvlm_assert(o.x == 0x10);
                cvlm_assert(s.a == 0x11);
                cvlm_assert(s.b == 0x12);
                cvlm_assert(o.t.s.a == 0x21);
                cvlm_assert(o.t.s.b == 0x22);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write nested struct`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let mut t = T { s: s, n: 0x13 };
                let s2 = &mut t.s;
                *s2 = S { a: 0x31, b: 0x32 };
                cvlm_assert(s.a == 0x11);
                cvlm_assert(s.b == 0x12);
                cvlm_assert(t.s.a == 0x31);
                cvlm_assert(t.s.b == 0x32);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write nested struct wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public struct Outer has drop {
                x: u32,
                t: T
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let t = T { s: s, n: 0x13 };
                let mut o = Outer { x: 0x10, t: t };
                let s2 = &mut o.t.s;
                *s2 = S { a: 0x31, b: 0x32 };
                cvlm_assert(o.x == 0x10);
                cvlm_assert(s.a == 0x11);
                cvlm_assert(s.b == 0x12);
                cvlm_assert(o.t.s.a == 0x31);
                cvlm_assert(o.t.s.b == 0x32);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `unpack simple struct`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let S { a, b } = s;
                cvlm_assert(a == 0x11);
                cvlm_assert(b == 0x12);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `unpack simple struct wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct Outer has drop, copy {
                x: u32,
                s: S
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let o = Outer { x: 0x10, s: s };
                let Outer { x, s: S { a, b } } = o;
                cvlm_assert(x == 0x10);
                cvlm_assert(a == 0x11);
                cvlm_assert(b == 0x12);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `unpack nested struct`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop {
                s: S,
                n: u32
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let t = T { s: s, n: 0x13 };
                let T { s: S { a, b }, n } = t;
                cvlm_assert(a == 0x11);
                cvlm_assert(b == 0x12);
                cvlm_assert(n == 0x13);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `unpack nested struct wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: u32,
                b: u32
            }
            public struct T has drop, copy {
                s: S,
                n: u32
            }
            public struct Outer has drop, copy {
                x: u32,
                t: T
            }
            public fun test() {
                let s = S { a: 0x11, b: 0x12 };
                let t = T { s: s, n: 0x13 };
                let o = Outer { x: 0x10, t: t };
                let Outer { x, t: T { s: S { a, b }, n } } = o;
                cvlm_assert(x == 0x10);
                cvlm_assert(a == 0x11);
                cvlm_assert(b == 0x12);
                cvlm_assert(n == 0x13);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `read bool fields`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: bool,
                b: bool
            }
            public fun test(a: bool, b: bool) {
                let s = S { a: a, b: b };
                cvlm_assert(s.a == a);
                cvlm_assert(s.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `read bool fields wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: bool,
                b: bool
            }
            public struct Outer has drop, copy {
                x: u32,
                s: S
            }
            public fun test(x: u32, a: bool, b: bool) {
                let s = S { a: a, b: b };
                let o = Outer { x: x, s: s };
                cvlm_assert(o.x == x);
                cvlm_assert(o.s.a == a);
                cvlm_assert(o.s.b == b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write bool fields`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: bool,
                b: bool
            }
            public fun test() {
                let mut s = S { a: false, b: false };
                cvlm_assert(!s.a);
                cvlm_assert(!s.b);
                s.b = true;
                cvlm_assert(!s.a);
                cvlm_assert(s.b);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `write bool fields wrapped`() {
        addMoveSource("""
            $testModule
            public struct S has drop, copy {
                a: bool,
                b: bool
            }
            public struct Outer has drop, copy {
                x: u32,
                s: S
            }
            public fun test() {
                let s = S { a: false, b: false };
                let mut o = Outer { x: 0x10, s: s };
                cvlm_assert(!o.s.a);
                cvlm_assert(!o.s.b);
                o.s.b = true;
                cvlm_assert(!o.s.a);
                cvlm_assert(o.s.b);
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
