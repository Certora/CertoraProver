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
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class TACMulTest {

    /** `5 x 7` **/
    @Test
    fun test01() {
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

    /** -5 x 7 **/
    @Test
    fun test02() {
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
        Assertions.assertEquals(true, verify(tacProg, report = true))
    }


    /** `5 x 7` with `-solanaTACMathInt false` **/
    @Test
    fun test03() {
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                r2 = 5
                r3 = 0
                r4 = 7
                r5 = 0
                "__muloti4"()
                r2 = r1[0]
                r3 = r1[8]
                r4 = r1[16]
                assert(CondOp.EQ(r2, 35UL))
                assert(CondOp.EQ(r3, 0UL))
                assert(CondOp.EQ(r4, 0UL))
                exit()
            }
        }

        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        // Expected to fail because we need to enable `-solanaTACMathInt`
        Assertions.assertEquals(false, verify(tacProg))
    }

    /** `5 x 7` with `-solanaTACMathInt true` **/
    @Test
    fun test04() {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                r2 = 5
                r3 = 0
                r4 = 7
                r5 = 0
                "__muloti4"()
                r2 = r1[0]
                r3 = r1[8]
                r4 = r1[16]
                assert(CondOp.EQ(r2, 35UL))
                assert(CondOp.EQ(r3, 0UL))
                assert(CondOp.EQ(r4, 0UL)) // no overflow
                exit()
            }
        }

        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        ConfigScope(SolanaConfig.UseTACMathInt, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /*private fun split128(x: BigInteger): Pair<BigInteger, BigInteger> {
        val low = x.and(BigInteger.TWO.pow(64) - BigInteger.ONE)
        val high = x.shr(64)
        return low to high
    }*/


    /** `2^127 x 2` with `-solanaTACMathInt true` **/
    @Test
    fun test05() {
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                // r2 and r3 when interpreted as a single 128-bit number is 2^127
                r2 = 0
                r3 = 0x8000_0000_0000_0000UL
                r4 = 2
                r5 = 0
                "__muloti4"() // the result is 2^128 which doesn't fit in 128 bits
                r4 = r1[16]
                assert(CondOp.EQ(r4, 1UL)) // overflow
                exit()
            }
        }

        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        ConfigScope(SolanaConfig.UseTACMathInt, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }


    /** `(2^127 -1) x 2` with `-solanaTACMathInt true` **/
    @Test
    fun test06() {
        val cfg = SbfTestDSL.makeCFG("test6") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                // r2 and r3 when interpreted as a single 128-bit number is 2^127 -1
                r2 = 0xFFFF_FFFF_FFFF_FFFFUL
                r3 = 0x0FFF_FFFF_FFFF_FFFFUL
                r4 = 2
                r5 = 0
                "__muloti4"() // 0FFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF x 2 = 1FFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFF_FFFE
                r2 = r1[0]
                r3 = r1[8]
                r4 = r1[16]
                r6 = 18446744073709551614UL

                // hack to avoid FFFF_FFFF_FFFF_FFFE being internally signed extended to 256 by the prover
                r1 = 18446744073709551614UL
                "CVT_mask_64"()
                assert(CondOp.EQ(r2, r0))
                assert(CondOp.EQ(r3, 2305843009213693951UL))
                assert(CondOp.EQ(r4, 0UL)) // no overflow
                exit()
            }
        }

        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        ConfigScope(SolanaConfig.UseTACMathInt, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }


    /** `-1 x 2` with `-solanaTACMathInt true` **/
    @Test
    fun test07() {
        val cfg = SbfTestDSL.makeCFG("test7") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                r2 = -1
                r3 = 0
                r4 = 2
                r5 = 0
                "__muloti4"() // 0xFFFF_FFFF_FFFF_FFFF x 0x2 = 0x1_FFFF_FFFF_FFFF_FFFE
                r6 = r1[0]
                r7 = r1[8]
                r8 = r1[16]

                // HACK to see the values in the html report
                r1 = 345678 // address that looks like a constant string
                r2 = 8      // length of the constant string
                r3 = r6
                "CVT_calltrace_print_u64_3"()
                r3 = r7
                "CVT_calltrace_print_u64_3"()
                r3 = r8
                "CVT_calltrace_print_u64_3"()

                // Hack to avoid the prover to signed extension to 256 bits.
                r1 = -2
                "CVT_mask_64"()
                assert(CondOp.EQ(r6, r0))
                assert(CondOp.EQ(r7, 1UL))
                assert(CondOp.EQ(r8, 0UL)) // no overflow
                exit()
            }
        }

        cfg.normalize()
        cfg.verify(true)

        println("$cfg")
        ConfigScope(SolanaConfig.UseTACMathInt, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg, report = true))
        }
    }
}
