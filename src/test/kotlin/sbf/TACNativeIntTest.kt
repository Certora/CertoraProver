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

package sbf

import config.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*
import sbf.support.UnknownStackPointerError
import sbf.tac.TACTranslationError

class TACNativeIntTest {

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test1") {
                bb(0) {
                    r1 = 3
                    r2 = 2
                    "CVT_nativeint_u64_div_ceil"()
                    r1 = r0
                    r2 = 2
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test2(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test2") {
                bb(0) {
                    r1 = 3
                    r2 = 2
                    "CVT_nativeint_u64_mul"()
                    r1 = r0
                    r2 = 6
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test3(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test3") {
                bb(0) {
                    r1 = 3
                    r2 = 2
                    r3 = 4
                    "CVT_nativeint_u64_muldiv"()
                    r1 = r0
                    r2 = 1
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test4(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test4") {
                bb(0) {
                    r1 = 3
                    r2 = 2
                    r3 = 4
                    "CVT_nativeint_u64_muldiv_ceil"()
                    r1 = r0
                    r2 = 2
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test5(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test5") {
                bb(0) {
                    r1 = 29
                    r2 = 10
                    "CVT_nativeint_u64_sub"()
                    r1 = r0
                    r2 = 19
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(42, 32) == 42 returns positive value with sign bit 0 is unchanged`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 42
                    r2 = 32
                    "CVT_nativeint_u64_sext"()
                    r1 = r0
                    r2 = 42
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(0xFF, 8) == neg(1)`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 255  // 0xFF
                    r2 = 8
                    "CVT_nativeint_u64_sext"()
                    r3 = r0   // save -1 (256-bit)
                    r1 = 1
                    "CVT_nativeint_u64_neg"()
                    // r0 = neg(1) = -1 (256-bit)
                    r2 = r0
                    r1 = r3
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `neg(0) == 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 0
                    "CVT_nativeint_u64_neg"()
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `neg(5) + 5 == 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 5
                    "CVT_nativeint_u64_neg"()
                    r1 = r0   // r1 = -5 (256-bit)
                    r2 = 5
                    "CVT_nativeint_u64_add"()
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(0x1_80000000, 32) slt 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 6442450944L  // 0x1_8000_0000, lower 32 bits = 0x8000_0000 = 2^31
                    r2 = 32
                    "CVT_nativeint_u64_sext"() // r0 = 2^256 - 2^31 =  0xFFFF...FFFF_8000_0000 (negative number)
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_slt"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(0x1_80000000, 32) + 2^31 == 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 6442450944L  // 0x1_8000_0000, lower 32 bits = 0x8000_0000 = 2^31
                    r2 = 32
                    "CVT_nativeint_u64_sext"()  // r0 = 2^256 -2^31
                    r1 = r0
                    r2 = 2147483648L            // 2^31
                    "CVT_nativeint_u64_add"()   // r0 = 2^256 -2^31 + 2^31 == 0 (wraparound)
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(-5 in 64-bit, 64) + 5 == 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = -5L  // 0xFFFF_FFFF_FFFF_FFFB: negative in 64-bit (bit 63 set)
                    r2 = 64
                    "CVT_nativeint_u64_sext"()  // r0 = 0xFFFF...FFFF_FFFF_FFFF_FFFB = 2^256 - 5
                    r1 = r0
                    r2 = 5
                    "CVT_nativeint_u64_add"()   // r0 = -5 + 5 == 0
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `sext(-5 in 64-bit, 128) + 5 == 0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            // This test relies on the unsound behavior of automatically sign-extending constant values
            Assumptions.assumeFalse(SolanaConfig.TACSoundSignedMath.get())

            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = -5L  // 0xFFFF_FFFF_FFFF_FFFB: negative in 64-bit
                    r2 = 128
                    "CVT_nativeint_u64_sext"()  // r0 = 0xFFFF...FFFF_FFFF_FFFF_FFFB = 2^256 - 5
                    r1 = r0
                    r2 = 5
                    "CVT_nativeint_u64_add"()   // r0 = -5 + 5 == 0
                    r1 = r0
                    r2 = 0
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `into_u128(5) stores low=5 and high=0`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 32)   // stack pointer for output: *(r1+0)=low, *(r1+8)=high
                    r2 = 5              // nativeint value
                    "CVT_nativeint_u64_into_u128"()
                    r2 = r1[0]          // low
                    r3 = r1[8]          // high
                    assert(CondOp.EQ(r2, 5UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `into_u128(from_u128(3, 7)) stores low=3 and high=7`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    // Build nativeint(7 * 2^64 + 3) from low=3, high=7
                    r1 = 3
                    r2 = 7
                    "CVT_nativeint_u64_from_u128"()
                    // r0 = nativeint(7 * 2^64 + 3); save it in r4
                    r4 = r0
                    // Set up stack pointer for output and pass the nativeint in r2
                    r1 = r10
                    BinOp.SUB(r1, 32)
                    r2 = r4
                    "CVT_nativeint_u64_into_u128"()
                    r2 = r1[0]   // low
                    r3 = r1[8]   // high
                    assert(CondOp.EQ(r2, 3UL))
                    assert(CondOp.EQ(r3, 7UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `from_u256(0, 0, 0, 1) == 2^192`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 0
                    r2 = 1
                    "CVT_nativeint_u64_from_u128"()
                    r6 = r0             // 2^64
                    r1 = r6
                    r2 = r6
                    "CVT_nativeint_u64_mul"()
                    r1 = r0
                    r2 = r6
                    "CVT_nativeint_u64_mul"()
                    r7 = r0             // 2^192
                    r1 = 0
                    r2 = 0
                    r3 = 0
                    r4 = 1
                    "CVT_nativeint_u64_from_u256"()
                    r1 = r0
                    r2 = r7
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `from_u256(0, 0, 0, u64_MAX) == u64_MAX times 2^192`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r8 = -1L            // u64::MAX
                    r1 = r8
                    r2 = 0
                    "CVT_nativeint_u64_from_u128"()
                    r9 = r0             // u64::MAX
                    r1 = 0
                    r2 = 1
                    "CVT_nativeint_u64_from_u128"()
                    r6 = r0             // 2^64
                    r1 = r6
                    r2 = r6
                    "CVT_nativeint_u64_mul"()
                    r1 = r0
                    r2 = r6
                    "CVT_nativeint_u64_mul"()
                    r1 = r0
                    r2 = r9
                    "CVT_nativeint_u64_mul"()
                    r7 = r0             // u64::MAX * 2^192
                    r1 = 0
                    r2 = 0
                    r3 = 0
                    r4 = r8
                    "CVT_nativeint_u64_from_u256"()
                    r1 = r0
                    r2 = r7
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `from_u256(1, 2, 3, 4) == from_u128(1, 2) + from_u128(3, 4) times 2^128`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 0
                    r2 = 1
                    "CVT_nativeint_u64_from_u128"()
                    r1 = r0
                    r2 = r0
                    "CVT_nativeint_u64_mul"()
                    r6 = r0             // 2^128
                    r1 = 3
                    r2 = 4
                    "CVT_nativeint_u64_from_u128"()
                    r1 = r0
                    r2 = r6
                    "CVT_nativeint_u64_mul"()
                    r7 = r0             // 4 * 2^192 + 3 * 2^128
                    r1 = 1
                    r2 = 2
                    "CVT_nativeint_u64_from_u128"()
                    r1 = r0
                    r2 = r7
                    "CVT_nativeint_u64_add"()
                    r7 = r0             // 4 * 2^192 + 3 * 2^128 + 2 * 2^64 + 1
                    r1 = 1
                    r2 = 2
                    r3 = 3
                    r4 = 4
                    "CVT_nativeint_u64_from_u256"()
                    r1 = r0
                    r2 = r7
                    "CVT_nativeint_u64_eq"()
                    assert(CondOp.EQ(r0, 1))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `unsupported bitwidth should throw an exception`(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 0L
                    r2 = 15
                    "CVT_nativeint_u64_sext"()
                    exit()
                }
            }

            println("$cfg")
            expectException<TACTranslationError> {
                toTAC(cfg)
            }
        }
    }
}
