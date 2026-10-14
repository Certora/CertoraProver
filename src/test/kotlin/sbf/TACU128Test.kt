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

import config.ConfigScope
import cvlr.CvlrFunctions
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class TACU128Test {

    /** 128-bits unsigned ceil division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 104)
                r2 = 3
                r3 = 0
                r4 = 2
                r5 = 0
                CvlrFunctions.CVT_u128_ceil_div()
                r2 = r1[0]
                r3 = r1[8]
                assert(CondOp.EQ(r2, 2UL))
                assert(CondOp.EQ(r3, 0UL))
                exit()
            }
        }


        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits unsigned floor division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test2(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 104)
                r2 = 3
                r3 = 0
                r4 = 2
                r5 = 0
                "__udivti3"()
                r2 = r1[0]
                r3 = r1[8]
                assert(CondOp.EQ(r2, 1UL))
                assert(CondOp.EQ(r3, 0UL))
                exit()
            }
        }


        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less or equal **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test3(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(0) {
                r0 = 42
                r1 = 10
                r2 = 0
                r3 = 20
                r4 = 0
                CvlrFunctions.CVT_u128_leq()
                r1 = r0[8]
                r0 = r0[0] // restore r0
                assert(CondOp.EQ(r1, 1UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }


        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less or equal **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test4(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                r1 = 8
                "__rust_alloc"()
                r1 = r0
                r1[0] = 42
                r0 = r1[0]
                r1 = 10
                r2 = 1
                r3 = 20
                r4 = 0
                CvlrFunctions.CVT_u128_leq()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 0UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }


        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less or equal: high(x)==0, high(y)!=0 → true **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 leq returns true when high(x) is zero and high(y) is non-zero`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 leq returns true when high(x) is zero and high(y) is non-zero") {
            bb(0) {
                r0 = 42
                r1 = 10
                r2 = 0
                r3 = 5
                r4 = 1
                CvlrFunctions.CVT_u128_leq()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 1UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less or equal: both highs non-zero, x > y → false **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 leq returns false when both highs are non-zero and x is greater than y`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 leq returns false when both highs are non-zero and x is greater than y") {
            bb(0) {
                r0 = 42
                r1 = 5
                r2 = 1
                r3 = 20
                r4 = 0
                CvlrFunctions.CVT_u128_leq()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 0UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less or equal: x == y → true **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 leq returns true when x equals y`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 leq returns true when x equals y") {
            bb(0) {
                r0 = 42
                r1 = 10
                r2 = 0
                r3 = 10
                r4 = 0
                CvlrFunctions.CVT_u128_leq()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 1UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less than: 0 < 0 → false **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 lt returns false when x is zero`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 lt returns false when x is zero") {
            bb(0) {
                r0 = 42
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 0
                CvlrFunctions.CVT_u128_lt()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 0UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /** 128-bits less than: low(x)=0 < low(y)=5, both highs zero → true **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 lt returns true when low is non-zero and high is zero`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 lt returns true when low is non-zero and high is zero") {
            bb(0) {
                r0 = 42
                r1 = 0
                r2 = 0
                r3 = 5
                r4 = 0
                CvlrFunctions.CVT_u128_lt()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 1UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /**
     * `x - y` and `x + y`, where the halves of `x` and `y` are the given registers, leaving the halves of the
     * result in `r6` and `r7`, and `u64::MAX` in `r8`.
     *
     * `u64::MAX` is built with `CVT_mask_64` rather than written as a constant, since a constant with its
     * top bit set is sign extended to 256 bits in "lazy masking" mode.
     **/
    private fun SbfTestDSL.BlockBuilderScope.wrappingOp(
        xLow: Any, xHigh: Any, yLow: Any, yHigh: Any, isSub: Boolean
    ) {
        r1 = -1
        "CVT_mask_64"()
        r8 = r0
        r1 = xLow
        r2 = xHigh
        r3 = yLow
        r4 = yHigh
        if (isSub) {
            CvlrFunctions.CVT_u128_wrapping_sub()
        } else {
            CvlrFunctions.CVT_u128_wrapping_add()
        }
        r6 = r0[8]
        r7 = r0[16]
    }

    private fun verifyU128(sound: Boolean, cfg: MutableSbfCFG, expected: Boolean = true) {
        cfg.normalize()
        cfg.verify(true)
        println("$cfg")
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(expected, verify(tacProg))
        }
    }

    /** 128-bits wrapping subtraction: `0 - 1` wraps around to 2^128-1 **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 wrapping sub underflows to u128 MAX`(sound: Boolean) {
        verifyU128(sound, SbfTestDSL.makeCFG("test") {
            bb(0) {
                wrappingOp(xLow = 0, xHigh = 0, yLow = 1, yHigh = 0, isSub = true)
                assert(CondOp.EQ(r6, r8))
                assert(CondOp.EQ(r7, r8))
                exit()
            }
        })
    }

    /** A wrong expectation is refuted, so the underflow above does not hold vacuously **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 wrapping sub underflow is not vacuous`(sound: Boolean) {
        verifyU128(sound, SbfTestDSL.makeCFG("test") {
            bb(0) {
                wrappingOp(xLow = 0, xHigh = 0, yLow = 1, yHigh = 0, isSub = true)
                assert(CondOp.EQ(r7, 12345UL))
                exit()
            }
        }, expected = false)
    }

    /** 128-bits wrapping addition: `u128::MAX + 1` wraps around to 0 **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 wrapping add overflows to zero`(sound: Boolean) {
        verifyU128(sound, SbfTestDSL.makeCFG("test") {
            bb(0) {
                wrappingOp(xLow = r8, xHigh = r8, yLow = 1, yHigh = 0, isSub = false)
                assert(CondOp.EQ(r6, 0UL))
                assert(CondOp.EQ(r7, 0UL))
                exit()
            }
        })
    }

    /** A wrong expectation is refuted, so the overflow above does not hold vacuously **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 wrapping add overflow is not vacuous`(sound: Boolean) {
        verifyU128(sound, SbfTestDSL.makeCFG("test") {
            bb(0) {
                wrappingOp(xLow = r8, xHigh = r8, yLow = 1, yHigh = 0, isSub = false)
                assert(CondOp.EQ(r7, 12345UL))
                exit()
            }
        }, expected = false)
    }

    /** 128-bits less than: high(x)=0 < high(y)=1 → true **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `u128 lt returns true when low is zero and high is non-zero`(sound: Boolean) {
        val cfg = SbfTestDSL.makeCFG("u128 lt returns true when low is zero and high is non-zero") {
            bb(0) {
                r0 = 42
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 1
                CvlrFunctions.CVT_u128_lt ()
                r1 = r0[8]
                r0 = r0[0]
                assert(CondOp.EQ(r1, 1UL))
                assert(CondOp.EQ(r0, 42UL))
                exit()
            }
        }

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            ConfigScope(SolanaConfig.UseTACMathInt, true).use {
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }
}
