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
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class BytecodeTest : MoveTestFixture() {
    @Test
    fun ifTrue() {
        addMoveSource("""
            $testModule
            public fun test(n: u32) {
                let x;
                let b = n == 0;
                if (b) {
                    x = 1;
                } else {
                    x = 0;
                };
                cvlm_assert(x != n);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun ifFalse() {
        addMoveSource("""
            $testModule
            public fun test(n: u32) {
                let x;
                let b = n == 0;
                if (!b) {
                    x = 0;
                } else {
                    x = 1;
                };
                cvlm_assert(x != n);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun `empty if`() {
        addMoveSource("""
            $testModule
            public fun test(n: u32) {
                if (n == 0) {
                };
                cvlm_assert(true);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun `empty else`() {
        addMoveSource("""
            $testModule
            public fun test(n: u32) {
                if (n == 0) {
                    cvlm_assert(true);
                } else {
                };
                cvlm_assert(true);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun `while loop`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut x = 10;
                let mut i = 0;
                while (i < 3) {
                    x = x + i;
                    i = i + 1;
                };
                cvlm_assert(x == 13);
            }
        """.trimIndent())

        assertTrue(verify(loopIter = 5))
    }


    @Test
    fun `while loop with early exit`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let mut x = 10;
                let mut i = 0;
                while (i < 4) {
                    x = x + i;
                    i = i + 1;
                    if (i == 2) break;
                };
                cvlm_assert(x == 11);
            }
        """.trimIndent())

        assertTrue(verify(loopIter = 5))
    }

    @Test
    fun `call`() {
        addMoveSource("""
            $testModule
            fun function(x: u32): u32 {
                x + 1
            }
            public fun test(n: u32) {
                cvlm_assert(function(n) == n + 1);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `numeric casts`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test(n: u$size) {
                let n128 = (n as u128);
                let n64 = (n as u64);
                let n32 = (n as u32);
                let n16 = (n as u16);
                let n8 = (n as u8);
                cvlm_assert(n128 < (1 << 128));
                cvlm_assert(n64 < (1 << 64));
                cvlm_assert(n32 < (1 << 32));
                cvlm_assert(n16 < (1 << 16));
                cvlm_assert(n8 < (1 << 8));
                cvlm_assert((n8 as u$size) == n);
                cvlm_assert((n16 as u$size) == n);
                cvlm_assert((n32 as u$size) == n);
                cvlm_assert((n64 as u$size) == n);
                cvlm_assert((n128 as u$size) == n);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `numeric cast overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let n256 = (1 << 255);
                let n = (n256 as u$size);
                cvlm_assert(n >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `add`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 11u$size;
                let b = 22u$size;
                cvlm_assert(a + b == 33u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `add overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = ${maxInt(size)};
                let b = 1u$size;
                cvlm_assert(a + b >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `sub`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 33u$size;
                let b = 22u$size;
                cvlm_assert(a - b == 11u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `sub overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0u$size;
                let b = 1u$size;
                cvlm_assert(a - b >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `mul`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 11u$size;
                let b = 3u$size;
                cvlm_assert(a * b == 33u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `mul overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = ${maxInt(size)};
                let b = 2u$size;
                cvlm_assert(a * b >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `div`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 33u$size;
                let b = 3u$size;
                cvlm_assert(a / b == 11u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `div by zero`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 33u$size;
                let b = 0u$size;
                cvlm_assert(a / b >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `mod`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 33u$size;
                let b = 10u$size;
                cvlm_assert(a % b == 3u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `mod by zero`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 33u$size;
                let b = 0u$size;
                cvlm_assert(a % b >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `bitwise and`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x5bu$size;
                let b = 0x30u$size;
                cvlm_assert(a & b == 0x10u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `bitwise or`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x50u$size;
                let b = 0x03u$size;
                cvlm_assert(a | b == 0x53u$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `shift left`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                cvlm_assert(a << 1 == 0xfeu$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128))
    fun `shift left overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a: u$size = 1;
                let b = ${size}u8;
                cvlm_assert((a << b) >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `shift right`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                cvlm_assert(a >> 1 == 0x3fu$size);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128))
    fun `shift right overflow`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a: u$size = 1;
                let b = ${size}u8;
                cvlm_assert((a >> b) >= 0);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `eq`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7fu$size;
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not eq`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `eq struct`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
                m: u32
            }
            public struct Bar has copy, drop {
                n: u32,
                f: Foo,
                m: u32
            }
            public fun test() {
                let a = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                let b = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `eq struct one field`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
            }
            public fun test() {
                let a = Foo { n: 2 };
                let b = Foo { n: 2 };
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `not eq struct`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
                m: u32
            }
            public struct Bar has copy, drop {
                n: u32,
                f: Foo,
                m: u32
            }
            public fun test() {
                let a = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                let b = Bar { n: 1, f: Foo { n: 2, m: 4 }, m: 4 };
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `not eq struct one field`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
            }
            public fun test() {
                let a = Foo { n: 2 };
                let b = Foo { n: 3 };
                cvlm_assert(a == b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `neq`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a != b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not neq`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7fu$size;
                cvlm_assert(a != b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `neq struct`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
                m: u32
            }
            public struct Bar has copy, drop {
                n: u32,
                f: Foo,
                m: u32
            }
            public fun test() {
                let a = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                let b = Bar { n: 1, f: Foo { n: 8, m: 3 }, m: 4 };
                cvlm_assert(a != b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }

    @Test
    fun `not neq struct`() {
        addMoveSource("""
            $testModule
            public struct Foo has copy, drop {
                n: u32,
                m: u32
            }
            public struct Bar has copy, drop {
                n: u32,
                f: Foo,
                m: u32
            }
            public fun test() {
                let a = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                let b = Bar { n: 1, f: Foo { n: 2, m: 3 }, m: 4 };
                cvlm_assert(a != b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `lt`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7eu$size;
                let b = 0x7fu$size;
                cvlm_assert(a < b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not lt`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a < b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `le`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7eu$size;
                let b = 0x7fu$size;
                cvlm_assert(a <= b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not le`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a <= b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `gt`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a > b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not gt`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7eu$size;
                let b = 0x7fu$size;
                cvlm_assert(a > b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `ge`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7fu$size;
                let b = 0x7eu$size;
                cvlm_assert(a >= b);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = false))
    }
    @ParameterizedTest
    @ValueSource(ints = intArrayOf(8, 16, 32, 64, 128, 256))
    fun `not ge`(size: Int) {
        addMoveSource("""
            $testModule
            public fun test() {
                let a = 0x7eu$size;
                let b = 0x7fu$size;
                cvlm_assert(a >= b);
            }
        """.trimIndent())

        assertFalse(verify(assumeNoTraps = false))
    }

    @Test
    fun `abort`() {
        addMoveSource("""
            $testModule
            public fun test(i: u32) {
                assert!(i == 13, 0);
                cvlm_assert(i == 13);
            }
        """.trimIndent())

        assertTrue(verify(assumeNoTraps = true))
    }

    @Test
    fun `constant address`() {
        addMoveSource("""
            $testModule
            public fun test() {
                let addr = @test_addr;
                cvlm_assert(addr == u256_to_address($testModuleAddr));
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun `diamond optimization regression`() {
        // This is a regression test for a bug in the DiamondSimplifier that is easy to reproduce in Move
        addMoveSource("""
            $testModule
            public fun test(a: u64, b: bool) {
                let mut total = 0;
                let mut i = 0;
                let stop = 2;
                while (i < stop) {
                    if (b) {
                        total = total + a;
                    };
                    i = i + 1;
                };
                cvlm_assert(total == 0 || total == a || total == a + a);
            }
        """.trimIndent())

        assertTrue(verify(optimize = true, loopIter = 2))
    }
}
