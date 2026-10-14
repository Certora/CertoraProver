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
import sbf.disassembler.Label
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

/**
 * The overflow checks recognized by [detectOverflowPatterns] are translated by keeping the addition untruncated so
 * that the check itself can compare it against 2^64-1, and by truncating the result once the check has been made.
 *
 * These tests check that the encoding still agrees with the 64-bit semantics of the original code: `x` and `y` are
 * arbitrary 64-bit values, so `x >u x+y` holds exactly when the sum wrapped around.
 */
class TACOverflowPromotionTest {

    /** `x = nondet_u64(); y = nondet_u64(); z = x; z += y` in the shape [detectOverflowPatterns] expects **/
    private fun SbfTestDSL.BlockBuilderScope.checkedAdd() {
        "CVT_nondet_u64"()
        r1 = r0
        "CVT_nondet_u64"()
        r2 = r0
        r3 = r1
        BinOp.ADD(r3, r2)
    }

    private fun verifyOverflowCheck(sound: Boolean, mathInt: Boolean, cfg: MutableSbfCFG, expected: Boolean = true) {
        cfg.normalize()
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("$cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(0))?.getInstructions()?.any {
                it.metaData.getVal(SbfMeta.SAFE_MATH) != null
            })

        ConfigScope(SolanaConfig.TACSoundSignedMath, sound)
            .extend(SolanaConfig.UseTACMathInt, mathInt)
            .use {
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(expected, verify(tacProg))
            }
    }

    /** `select(x >u x+y, 1, 0) == 1` implies that the sum wrapped around **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `overflowing select wraps the sum`(sound: Boolean, mathInt: Boolean) {
        verifyOverflowCheck(sound, mathInt, SbfTestDSL.makeCFG("test") {
            bb(0) {
                checkedAdd()
                select(r4, CondOp.GT(r1, r3), 1, 0)
                assume(CondOp.EQ(r4, 1))
                assert(CondOp.LT(r3, r1))
                exit()
            }
        })
    }

    /** `select(x >u x+y, 1, 0) == 0` implies that the sum did not wrap around **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `non-overflowing select keeps the sum`(sound: Boolean, mathInt: Boolean) {
        verifyOverflowCheck(sound, mathInt, SbfTestDSL.makeCFG("test") {
            bb(0) {
                checkedAdd()
                select(r4, CondOp.GT(r1, r3), 1, 0)
                assume(CondOp.EQ(r4, 0))
                assert(CondOp.GE(r3, r2))
                exit()
            }
        })
    }

    /** The overflow branch must remain reachable: the check cannot be vacuously false **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `an overflow can be detected`(sound: Boolean, mathInt: Boolean) {
        verifyOverflowCheck(sound, mathInt, SbfTestDSL.makeCFG("test") {
            bb(0) {
                checkedAdd()
                select(r4, CondOp.GT(r1, r3), 1, 0)
                assume(CondOp.EQ(r4, 1))
                assert(CondOp.EQ(r4, 0))
                exit()
            }
        }, expected = false)
    }

    /** Same, but the check is a conditional jump instead of a select **/
    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `both branches of an overflow check see the truncated sum`(sound: Boolean, mathInt: Boolean) {
        verifyOverflowCheck(sound, mathInt, SbfTestDSL.makeCFG("test") {
            bb(0) {
                checkedAdd()
                br(CondOp.GT(r1, r3), 1, 2)
            }
            bb(1) {
                assert(CondOp.LT(r3, r1))
                goto(3)
            }
            bb(2) {
                assert(CondOp.GE(r3, r2))
                goto(3)
            }
            bb(3) {
                exit()
            }
        })
    }
}
