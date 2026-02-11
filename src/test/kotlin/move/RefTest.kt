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

class RefTest : MoveTestFixture() {
    @Test
    fun `ref local int write`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut x = 1;
                let r = &mut x;
                *r = 2;
                cvlm_assert(x == 2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ref local conditional write`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool) {
                let mut x = 1;
                let r = &mut x;
                if (cond) {
                    *r = 2;
                } else {
                    *r = 3;
                };
                cvlm_assert(x == 2 || x == 3);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `ref local int read`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let x = 1;
                let r = &x;
                cvlm_assert(*r == 1);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional ref local int read`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool) {
                let x = 1;
                let y = 2;
                let r = if (cond) {
                    &x
                } else {
                    &y
                };
                let z = *r;
                cvlm_assert(z == 1 || z == 2);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional ref local int write`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool) {
                let mut x = 1;
                let mut y = 2;
                let r = if (cond) {
                    &mut x
                } else {
                    &mut y
                };
                *r = 3;
                cvlm_assert((x == 3 && y == 2) || (x == 1 && y == 3));
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional ref local bool read`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool) {
                let x = true;
                let y = false;
                let r = if (cond) {
                    &x
                } else {
                    &y
                };
                let z = *r;
                cvlm_assert(z == cond);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional ref local bool write`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool) {
                let mut x = true;
                let mut y = true;
                let r = if (cond) {
                    &mut x
                } else {
                    &mut y
                };
                *r = false;
                cvlm_assert(x == !cond && y == cond);
            }
        """.trimIndent())
        assertTrue(verify())
    }

    @Test
    fun `conditional ref temp vector`() {
        addMoveSource("""
            $testModule
            public fun test(cond: bool, v1: vector<u64>) {
                cvlm_assume(v1.length() == 1);
                let r = if (cond) { &v1 } else { &vector[2, 3] };
                cvlm_assert(r.length() == 1 || r.length() == 2);
            }
        """.trimIndent())
        assertTrue(verify(loadStdlib = true))
    }
}
