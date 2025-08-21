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

class GhostTest : MoveTestFixture() {
    @Test
    fun `ghost variable`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost(): &mut u32;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost() = 1234;
                cvlm_assert(*ghost() == 1234);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic ghost variable`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost<T: copy>(): &mut T;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost<u32>() = 1234;
                *ghost<u64>() = 5678;
                cvlm_assert(*ghost<u32>() == 1234);
                cvlm_assert(*ghost<u64>() == 5678);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost mapping one numeric param`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost(x: u32): &mut u32;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost(1) = 1234;
                *ghost(2) = 5678;
                cvlm_assert(*ghost(1) == 1234);
                cvlm_assert(*ghost(2) == 5678);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic ghost mapping one numeric param`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost<T: copy>(x: u32): &mut T;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost<u32>(1) = 12;
                *ghost<u32>(2) = 34;
                *ghost<u64>(1) = 56;
                *ghost<u64>(2) = 78;
                cvlm_assert(*ghost<u32>(1) == 12);
                cvlm_assert(*ghost<u32>(2) == 34);
                cvlm_assert(*ghost<u64>(1) == 56);
                cvlm_assert(*ghost<u64>(2) == 78);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost mapping two numeric params`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost(x: u32, y: u32): &mut u32;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost(1, 1) = 12;
                *ghost(1, 2) = 34;
                *ghost(2, 1) = 56;
                *ghost(2, 2) = 78;
                cvlm_assert(*ghost(1, 1) == 12);
                cvlm_assert(*ghost(1, 2) == 34);
                cvlm_assert(*ghost(2, 1) == 56);
                cvlm_assert(*ghost(2, 2) == 78);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost mapping two numeric params struct value`() {
        addMoveSource("""
            module 0::ghosts;
            public struct GhostValue {
                x: u32,
                y: u32,
            }
            public fun x(self: &mut GhostValue): &mut u32 { &mut self.x }
            public fun y(self: &mut GhostValue): &mut u32 { &mut self.y }
            public native fun ghost(x: u32, y: u32): &mut GhostValue;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost(1, 1).x() = 12;
                *ghost(1, 1).y() = 34;
                *ghost(2, 1).x() = 56;
                *ghost(2, 1).y() = 78;
                cvlm_assert(ghost(1, 1).x() == 12);
                cvlm_assert(ghost(1, 1).y() == 34);
                cvlm_assert(ghost(2, 1).x() == 56);
                cvlm_assert(ghost(2, 1).y() == 78);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `generic ghost mapping two numeric params`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost<T: copy>(x: u32, y: u32): &mut T;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                *ghost<u32>(1, 1) = 12;
                *ghost<u32>(1, 2) = 34;
                *ghost<u64>(2, 1) = 56;
                *ghost<u64>(2, 2) = 78;
                cvlm_assert(*ghost<u32>(1, 1) == 12);
                cvlm_assert(*ghost<u32>(1, 2) == 34);
                cvlm_assert(*ghost<u64>(2, 1) == 56);
                cvlm_assert(*ghost<u64>(2, 2) == 78);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost mapping struct param`() {
        addMoveSource("""
            module 0::ghosts;
            public struct GhostKey {
                x: u32,
                y: u32,
            }
            public fun ghost_key(x: u32, y: u32): GhostKey { GhostKey { x, y } }
            public native fun ghost(key: GhostKey): &mut u32;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::{ghost, ghost_key};
            fun test() {
                *ghost(ghost_key(1, 1)) = 12;
                *ghost(ghost_key(1, 2)) = 34;
                *ghost(ghost_key(2, 1)) = 56;
                *ghost(ghost_key(2, 2)) = 78;
                cvlm_assert(*ghost(ghost_key(1, 1)) == 12);
                cvlm_assert(*ghost(ghost_key(1, 2)) == 34);
                cvlm_assert(*ghost(ghost_key(2, 1)) == 56);
                cvlm_assert(*ghost(ghost_key(2, 2)) == 78);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ghost mapping ref param`() {
        addMoveSource("""
            module 0::ghosts;
            public native fun ghost(key: &u32): &mut u32;
            public fun cvlm_manifest() {
                cvlm::manifest::ghost(b"ghost");
            }
        """.trimIndent())
        addMoveSource("""
            $testModule
            use 0::ghosts::ghost;
            fun test() {
                let key = 1;
                *ghost(&key) = 12;
                cvlm_assert(*ghost(&key) == 12);
            }
        """.trimIndent())
        assertTrue(verify())
    }
}
