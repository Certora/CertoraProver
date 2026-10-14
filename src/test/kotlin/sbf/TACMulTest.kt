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
import sbf.disassembler.GlobalVariables
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class TACMulTest {

    /** `5 x 7` **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test01(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test1") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 100)
                    r2 = 5
                    r3 = 0
                    r4 = 7
                    r5 = 0
                    "__multi3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 35UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }
            }

            cfg.normalize()
            cfg.verify(true)

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** -5 x 7 **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test02(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("test2") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 100)
                    r2 = -5
                    r3 = 0
                    r4 = 7
                    r5 = 0
                    "__multi3"()
                    r7 = r1[0]
                    r8 = r1[8]

                    // At this point, r7 is the low 64-bits and r8 is the high 64-bits of 128-bit multiplication.
                    // r7 = 0xffff_ffff_ffff_ffdd
                    // r6 = 0x0000_0000_0000_0006

                    // This assertion does not hold due to prover's use of 256-bit arithmetic.
                    // Hack to avoid -35 to be signed extended to 256 bits by the prover.
                    r1 = -35
                    "CVT_mask_64"()
                    assert(CondOp.EQ(r7, r0))
                    // This assertion is expected to be verified
                    assert(CondOp.EQ(r8, 6UL))
                    exit()
                }
            }

            cfg.normalize()
            cfg.verify(true)

            println("$cfg")

            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /**
     * Build, lower and verify a CFG that calls `__muloti4`.
     *
     * The CFG is built in the shape the SBF ABI produces (with the high half of the second operand
     * and the overflow pointer spilled to the bottom of the frame), so it has to go through
     * [lowerMuloti4Calls] like it would in the real front-end.
     */
    private fun verifyMuloti4(sound: Boolean, mathInt: Boolean, cfg: MutableSbfCFG, expected: Boolean = true) {
        cfg.normalize()
        cfg.verify(true)
        lowerMuloti4Calls(cfg, GlobalVariables(DefaultElfFileView))
        cfg.verify(true)

        println("$cfg")
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound)
            .extend(SolanaConfig.UseTACMathInt, mathInt)
            .use {
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(expected, verify(tacProg))
            }
    }

    /**
     * Set up a call site for `__muloti4` in the shape the ABI produces: `r1` points at the 16-byte
     * result buffer at `r10-40`, `r2`/`r3` hold the halves of the first operand, `r4` the low half
     * of the second one, and the frame carries [yHigh] together with a pointer to the overflow flag,
     * which lives at `r10-24`.
     */
    private fun SbfTestDSL.BlockBuilderScope.callMuloti4(xLow: Any, xHigh: Any, yLow: Any, yHigh: Any) {
        r1 = r10
        BinOp.SUB(r1, 24)
        r10[-4088] = r1    // spilled argument: pointer to the overflow flag
        r10[-4096] = yHigh // spilled argument: high 64 bits of the second operand
        r1 = r10
        BinOp.SUB(r1, 40)
        r2 = xLow
        r3 = xHigh
        r4 = yLow
        r5 = r10
        "__muloti4"()
    }

    /** `5 x 7` **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test03(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(0) {
                callMuloti4(xLow = 5, xHigh = 0, yLow = 7, yHigh = 0)
                r2 = r1[0]
                r3 = r1[8]
                r4 = r10[-24]
                assert(CondOp.EQ(r2, 35UL))
                assert(CondOp.EQ(r3, 0UL))
                assert(CondOp.EQ(r4, 0UL)) // no overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** `i128::MIN x 2` overflows **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test04(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                // r2 and r3 when interpreted as a single 128-bit number is -2^127
                callMuloti4(xLow = 0, xHigh = 0x8000_0000_0000_0000UL, yLow = 2, yHigh = 0)
                r4 = r10[-24]
                assert(CondOp.EQ(r4, 1UL)) // overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** `i128::MIN x -1` overflows **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test05(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(0) {
                callMuloti4(xLow = 0, xHigh = 0x8000_0000_0000_0000UL, yLow = -1, yHigh = -1)
                r4 = r10[-24]
                assert(CondOp.EQ(r4, 1UL)) // overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** `(2^124 -1) x 2` **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test06(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test6") {
            bb(0) {
                // 0FFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF x 2 = 1FFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFE
                callMuloti4(
                    xLow = 0xFFFF_FFFF_FFFF_FFFFUL,
                    xHigh = 0x0FFF_FFFF_FFFF_FFFFUL,
                    yLow = 2,
                    yHigh = 0
                )
                r2 = r1[0]
                r3 = r1[8]
                r4 = r10[-24]

                // hack to avoid FFFF_FFFF_FFFF_FFFE being internally signed extended to 256 by the prover
                r1 = 18446744073709551614UL
                "CVT_mask_64"()
                assert(CondOp.EQ(r2, r0))
                assert(CondOp.EQ(r3, 2305843009213693951UL))
                assert(CondOp.EQ(r4, 0UL)) // no overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** `(2^64 -1) x 2` **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test07(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test7") {
            bb(0) {
                // 0xFFFF_FFFF_FFFF_FFFF x 0x2 = 0x1_FFFF_FFFF_FFFF_FFFE
                callMuloti4(xLow = -1, xHigh = 0, yLow = 2, yHigh = 0)
                r6 = r1[0]
                r7 = r1[8]
                r8 = r10[-24]

                // Hack to avoid the prover to signed extension to 256 bits.
                r1 = -2
                "CVT_mask_64"()
                assert(CondOp.EQ(r6, r0))
                assert(CondOp.EQ(r7, 1UL))
                assert(CondOp.EQ(r8, 0UL)) // no overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** `-1 x 2` **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test08(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test8") {
            bb(0) {
                callMuloti4(xLow = -1, xHigh = -1, yLow = 2, yHigh = 0)
                r6 = r1[0]
                r7 = r1[8]
                r8 = r10[-24]

                // Hack to avoid the prover to signed extension to 256 bits.
                r1 = -2
                "CVT_mask_64"()
                assert(CondOp.EQ(r6, r0))
                r1 = -1
                "CVT_mask_64"()
                assert(CondOp.EQ(r7, r0))
                assert(CondOp.EQ(r8, 0UL)) // no overflow
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg)
    }

    /** A wrong expectation is refuted, so the other `__muloti4` tests do not hold vacuously **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun test09(sound: Boolean, mathInt: Boolean) {
        val cfg = SbfTestDSL.makeCFG("test9") {
            bb(0) {
                callMuloti4(xLow = 5, xHigh = 0, yLow = 7, yHigh = 0)
                r2 = r1[0]
                assert(CondOp.EQ(r2, 36UL))
                exit()
            }
        }
        verifyMuloti4(sound, mathInt, cfg, expected = false)
    }
}
