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

class FieldAccessTest : MoveTestFixture() {
    @Test
    fun `field access`() {
        addMoveSource("""
            $testModule
            use 0::access::{getX, getY};
            public struct Foo has copy, drop {
                x: u64,
                y: u64,
            }
            public fun test() {
                let f = Foo { x: 42, y: 43 };
                cvlm_assert(getX(&f) == 42);
                cvlm_assert(getY(&f) == 43);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
                cvlm::manifest::field_access(b"getY", b"y");
            }
            public native fun getX<T>(f: &T): &u64;
            public native fun getY<T>(f: &T): &u64;
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `field access one field`() {
        addMoveSource("""
            $testModule
            use 0::access::getX;
            public struct Foo has copy, drop {
                x: u64,
            }
            public fun test() {
                let f = Foo { x: 42 };
                cvlm_assert(getX(&f) == 42);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
            }
            public native fun getX<T>(f: &T): &u64;
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `field access on non-struct fails`() {
        addMoveSource("""
            $testModule
            use 0::access::getX;
            public fun test() {
                let x = 42;
                getX(&x);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
            }
            public native fun getX<T>(f: &T): &u64;
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `field access on nondet type fails`() {
        addMoveSource("""
            $testModule
            use 0::access::getX;
            public fun test<T>(t: &T) {
                getX(t);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
            }
            public native fun getX<T>(f: &T): &u64;
        """.trimIndent())
        assertFalse(verify())
    }

    @Test
    fun `conditional field access on struct passes`() {
        addMoveSource("""
            $testModule
            use 0::access::getX;
            public struct Foo has copy, drop {
                x: u64,
            }
            fun maybeGetX<T>(f: &T): u64 {
                if (cvlm::nondet::is_nondet_type<T>()) {
                    0
                } else {
                    *getX(f)
                }
            }
            public fun test() {
                let f = Foo { x: 42 };
                cvlm_assert(maybeGetX(&f) == 42);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
            }
            public native fun getX<T>(f: &T): &u64;
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional field access on nondet type passes`() {
        addMoveSource("""
            $testModule
            use 0::access::getX;
            fun maybeGetX<T>(f: &T): u64 {
                if (cvlm::nondet::is_nondet_type<T>()) {
                    0
                } else {
                    *getX(f)
                }
            }
            public fun test<T>(t: &T) {
                cvlm_assert(maybeGetX(t) == 0);
            }
        """.trimIndent())
        addMoveSource("""
            module 0::access;
            public fun cvlm_manifest() {
                cvlm::manifest::field_access(b"getX", b"x");
            }
            public native fun getX<T>(f: &T): &u64;
        """.trimIndent())
        assertTrue(verify())
    }
}
