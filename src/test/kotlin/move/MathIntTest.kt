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

class MathIntTest : MoveTestFixture() {
    @Test
    fun `conversion and equality`() {
        addMoveSource("""
            $testModule
            fun test() {
                let a = math_int::from_u256(5);
                let b = math_int::from_u256(10);
                let c = math_int::from_u256(10);
                cvlm_assert(a != b);
                cvlm_assert(b == c);
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `add`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(5).add(math_int::from_u256(10)) == math_int::from_u256(15));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }


    @Test
    fun `sub`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(15).sub(math_int::from_u256(5)) == math_int::from_u256(10));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `mul`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(3).mul(math_int::from_u256(4)) == math_int::from_u256(12));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `div`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(12).div(math_int::from_u256(4)) == math_int::from_u256(3));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `mod`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(10).mod(math_int::from_u256(3)) == math_int::from_u256(1));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `lt`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(5).lt(math_int::from_u256(10)));
                cvlm_assert(!math_int::from_u256(10).lt(math_int::from_u256(5)));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `le`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(5).le(math_int::from_u256(10)));
                cvlm_assert(math_int::from_u256(10).le(math_int::from_u256(10)));
                cvlm_assert(!math_int::from_u256(10).le(math_int::from_u256(5)));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `gt`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(10).gt(math_int::from_u256(5)));
                cvlm_assert(!math_int::from_u256(5).gt(math_int::from_u256(10)));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `ge`() {
        addMoveSource("""
            $testModule
            fun test() {
                cvlm_assert(math_int::from_u256(10).ge(math_int::from_u256(5)));
                cvlm_assert(math_int::from_u256(10).ge(math_int::from_u256(10)));
                cvlm_assert(!math_int::from_u256(5).ge(math_int::from_u256(10)));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `negative value`() {
        addMoveSource("""
            $testModule
            fun test() {
                let neg = math_int::from_u256(0).sub(math_int::from_u256(5));
                cvlm_assert(neg.lt(math_int::from_u256(0)));
                cvlm_assert(neg.add(math_int::from_u256(5)) == math_int::from_u256(0));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `large value`() {
        addMoveSource("""
            $testModule
            fun test() {
                let u256max = math_int::from_u256(0xFFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF);
                let large = u256max.add(math_int::from_u256(5));
                cvlm_assert(large.gt(u256max));
                cvlm_assert(large.sub(u256max) == math_int::from_u256(5));
            }
        """)
        assertTrue(verify(assumeNoTraps = false))
    }
}
