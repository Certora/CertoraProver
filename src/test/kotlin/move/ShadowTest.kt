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

class ShadowTest : MoveTestFixture() {
    @Test
    fun `shadow variable`() {
        addMoveSource("""
            module 0::types;
            public struct S has copy, drop {
                n: u64
            }
            public fun s(n: u64): S {
                S { n }
            }
            public fun n(s: &S): u64 {
                s.n
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::shadows;
            use 0::types::S;
            use cvlm::nondet::nondet;
            fun cvlm_manifest() {
                cvlm::manifest::shadow(b"shadow");
                cvlm::manifest::summary(b"s", @0, b"types", b"s");
                cvlm::manifest::summary(b"n", @0, b"types", b"n");
            }
            public struct Shadow has copy, drop {
                n: u64,
                m: u64
            }
            native fun shadow(s: &S): &mut Shadow;
            fun s(n: u64): S {
                let s = nondet<S>();
                let shadow = shadow(&s);
                shadow.n = 1;
                shadow.m = n;
                s
            }
            public fun n(s: &S): u64 {
                let shadow = shadow(s);
                shadow.n + shadow.m
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::types::{s, n};
            fun test() {
                let s = s(42);
                cvlm_assert(s.n() == 43);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic shadow mapping`() {
        addMoveSource("""
            #[allow(unused_function)]
            module 0::shadows;
            use cvlm::nondet::nondet;
            fun cvlm_manifest() {
                cvlm::manifest::shadow(b"shadow_map");
            }
            public native struct Map<K: copy+drop, V: copy+drop> has copy, drop;
            native fun shadow_map<K: copy+drop, V: copy+drop>(self: &Map<K, V>, k: K): &mut V;
            public fun map<K: copy+drop, V: copy+drop>(): Map<K, V> { nondet() }
            public fun borrow<K: copy+drop, V: copy+drop>(self: &Map<K, V>, k: K): &V { shadow_map(self, k) }
            public fun borrow_mut<K: copy+drop, V: copy+drop>(self: &mut Map<K, V>, k: K): &mut V { shadow_map(self, k) }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::shadows::{borrow, borrow_mut, map};
            fun test() {
                let mut m1 = map<u64, u64>();
                *borrow_mut(&mut m1, 1) = 42;
                cvlm_assert(*borrow(&m1, 1) == 42);
                let mut m2 = m1;
                *borrow_mut(&mut m2, 1) = 82;
                *borrow_mut(&mut m2, 2) = 83;
                cvlm_assert(*borrow(&m1, 1) == 42);
                cvlm_assert(borrow(&m2, 1) == 82);
                cvlm_assert(borrow(&m2, 2) == 83);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `shadow mapping multiple parameters`() {
        addMoveSource("""
            #[allow(unused_function)]
            module 0::shadows;
            use cvlm::nondet::nondet;
            fun cvlm_manifest() {
                cvlm::manifest::shadow(b"shadow_map");
            }
            public native struct Map has copy, drop;
            native fun shadow_map(self: &Map, a: u32, b: u32): &mut u32;
            public fun map(): Map { nondet() }
            public fun borrow(self: &Map, a: u32, b: u32): &u32 { shadow_map(self, a, b) }
            public fun borrow_mut(self: &mut Map, a: u32, b: u32): &mut u32 { shadow_map(self, a, b) }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::shadows::{borrow, borrow_mut, map};
            fun test() {
                let mut m1 = map();
                *borrow_mut(&mut m1, 1, 1) = 42;
                *borrow_mut(&mut m1, 1, 2) = 43;
                cvlm_assert(borrow(&m1, 1, 1) == 42);
                cvlm_assert(borrow(&m1, 1, 2) == 43);
                let mut m2 = m1;
                *borrow_mut(&mut m2, 1, 2) = 82;
                *borrow_mut(&mut m2, 2, 2) = 83;
                cvlm_assert(borrow(&m1, 1, 1) == 42);
                cvlm_assert(borrow(&m1, 1, 2) == 43);
                cvlm_assert(borrow(&m2, 1, 2) == 82);
                cvlm_assert(borrow(&m2, 2, 2) == 83);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `shadow variable equality`() {
        // Sui summaries depend on shadow variables (parameterless mappings) being comparable and hashable
        addMoveSource("""
            module 0::types;
            public struct S has copy, drop {
                n: u64
            }
            public fun s(n: u64): S {
                S { n }
            }
            public fun n(s: &S): u64 {
                s.n
            }
        """.trimIndent())
        addMoveSource("""
            #[allow(unused_function)]
            module 0::shadows;
            use 0::types::S;
            use cvlm::nondet::nondet;
            fun cvlm_manifest() {
                cvlm::manifest::shadow(b"shadow");
                cvlm::manifest::summary(b"s", @0, b"types", b"s");
                cvlm::manifest::summary(b"n", @0, b"types", b"n");
                cvlm::manifest::hash(b"hash");
            }
            public struct Shadow has copy, drop {
                n: u64,
                m: u64
            }
            native fun shadow(s: &S): &mut Shadow;
            public native fun hash(s: &S): u256;
            fun s(n: u64): S {
                let s = nondet<S>();
                let shadow = shadow(&s);
                shadow.n = 1;
                shadow.m = n;
                s
            }
            public fun n(s: &S): u64 {
                let shadow = shadow(s);
                shadow.n + shadow.m
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::types::{s, n};
            use 0::shadows::hash;
            fun test() {
                let s1 = s(42);
                let s2 = s(43);
                let s3 = s(42);
                cvlm_assert(s1 == s3);
                cvlm_assert(s1 != s2);
                cvlm_assert(hash(&s1) == hash(&s3));
                cvlm_assert(hash(&s1) != hash(&s2));
            }
        """.trimIndent())
        assertTrue(verify())
    }

}
