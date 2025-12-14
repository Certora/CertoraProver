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

class HashTest : MoveTestFixture() {
    @Test
    fun `hash of one argument`() {
        addMoveSource("""
            module 0::hashes;
            public native fun hash(n: u32): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::hash;
            public fun test() {
                cvlm_assert(hash(1) == hash(1));
                cvlm_assert(hash(2) == hash(2));
                cvlm_assert(hash(1) != hash(2));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of two arguments`() {
        addMoveSource("""
            module 0::hashes;
            public native fun hash(n1: u32, n2: u32): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::hash;
            public fun test() {
                cvlm_assert(hash(1, 2) == hash(1, 2));
                cvlm_assert(hash(2, 1) == hash(2, 1));
                cvlm_assert(hash(1, 2) != hash(2, 1));
                cvlm_assert(hash(1, 2) != hash(2, 2));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of one struct argument`() {
        addMoveSource("""
            module 0::hashes;
            public struct S has copy, drop {
                x: u32,
                y: u32,
            }
            public fun s(x: u32, y: u32): S { S { x, y } }
            public native fun hash(s: S): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{hash, s};
            public fun test() {
                cvlm_assert(hash(s(1, 2)) == hash(s(1, 2)));
                cvlm_assert(hash(s(1, 1)) == hash(s(1, 1)));
                cvlm_assert(hash(s(2, 2)) == hash(s(2, 2)));
                cvlm_assert(hash(s(1, 2)) != hash(s(2, 2)));
                cvlm_assert(hash(s(1, 2)) != hash(s(2, 1)));
                cvlm_assert(hash(s(1, 2)) != hash(s(2, 2)));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of one struct argument with one field`() {
        addMoveSource("""
            module 0::hashes;
            public struct S has copy, drop {
                x: u32,
            }
            public fun s(x: u32): S { S { x } }
            public native fun hash(s: S): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{hash, s};
            public fun test() {
                cvlm_assert(hash(s(1)) == hash(s(1)));
                cvlm_assert(hash(s(2)) == hash(s(2)));
                cvlm_assert(hash(s(1)) != hash(s(2)));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic hash`() {
        addMoveSource("""
            module 0::hashes;
            public native fun hash<T: copy + drop>(x: T): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::hash;
            public fun test() {
                cvlm_assert(hash(1 as u8) == hash(1 as u8));
                cvlm_assert(hash(1 as u16) != hash(1 as u8));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of strings`() {
        addMoveSource("""
            module 0::hashes;
            public native fun hash(s: vector<u8>): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::hash;
            public fun test() {
                cvlm_assert(hash(b"hello") == hash(b"hello"));
                cvlm_assert(hash(b"world") == hash(b"world"));
                cvlm_assert(hash(b"hello") != hash(b"world"));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hash of structs with strings`() {
        addMoveSource("""
            module 0::hashes;
            public struct S has copy, drop {
                x: u32,
                y: vector<u8>,
            }
            public fun s(x: u32, y: vector<u8>): S { S { x, y } }
            public native fun hash(s: S): u256;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::{hash, s};
            public fun test() {
                cvlm_assert(hash(s(1, b"hello")) == hash(s(1, b"hello")));
                cvlm_assert(hash(s(2, b"world")) == hash(s(2, b"world")));
                cvlm_assert(hash(s(1, b"hello")) != hash(s(2, b"hello")));
                cvlm_assert(hash(s(1, b"hello")) != hash(s(1, b"world")));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `hashing nested nondet types`() {
        addMoveSource("""
            module 0::hashes;
            public fun cvlm_manifest() {
                cvlm::manifest::hash(b"hash");
            }
            public native fun hash<T>(): u256;
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::hashes::hash;
            public struct S<T> {
                inner: T
            }
            public fun test<T>() {
                cvlm_assert((hash<T>() == hash<u64>()) == (hash<S<T>>() == hash<S<u64>>()));
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
