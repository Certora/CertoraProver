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

import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class SimplifyByteSwapInstsTest {

    private fun countBE64(cfg: SbfCFG): Int =
        cfg.getBlocks().values.sumOf { b ->
            b.getInstructions().count { it is SbfInstruction.Un && it.op == UnOp.BE64 }
        }

    /**
     * Basic example from the [simplifyByteSwapInsts] doc comment.
     *
     * ```
     * r1 = 42
     * r2 = 7
     * r1 = be64(r1)
     * r2 = be64(r2)
     * if (r1 == r2) goto 1 else goto 2
     * ```
     * Both BE64 instructions should be removed.
     */
    @Test
    fun `both be64 removed before eq comparison`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 42
                r2 = 7
                UnOp.BE64(r1)
                UnOp.BE64(r2)
                br(CondOp.EQ(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(0, countBE64(cfg))
    }

    /**
     * Same pattern but with a NE condition.
     *
     * ```
     * r1 = be64(r1)
     * r2 = be64(r2)
     * if (r1 != r2) goto 1 else goto 2
     * ```
     * Both BE64 instructions should be removed.
     */
    @Test
    fun `both be64 removed before ne comparison`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 1
                r2 = 2
                UnOp.BE64(r1)
                UnOp.BE64(r2)
                br(CondOp.NE(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(0, countBE64(cfg))
    }

    /**
     * Swaps and comparison are separated by unrelated instructions.
     *
     * ```
     * r1 = be64(r1)
     * r3 = r4 + 1        // unrelated
     * r2 = be64(r2)
     * r5 = r6 + 1        // unrelated
     * if (r1 == r2) goto 1 else goto 2
     * ```
     * Both BE64 instructions should still be removed because r1 and r2 are
     * not used by the intermediate instructions.
     */
    @Test
    fun `both be64 removed with unrelated instructions in between`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 10
                r2 = 20
                UnOp.BE64(r1)
                BinOp.ADD(r3, 1)   // unrelated - doesn't use r1 or r2
                UnOp.BE64(r2)
                BinOp.ADD(r5, 1)   // unrelated - doesn't use r1 or r2
                br(CondOp.EQ(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(0, countBE64(cfg))
    }

    /**
     * r1 is used after its BE64 definition, so the transformation must not fire.
     *
     * ```
     * r1 = be64(r1)
     * r3 = r1 + 1        // r1 used here
     * r2 = be64(r2)
     * if (r1 == r2) goto 1 else goto 2
     * ```
     */
    @Test
    fun `not simplified when left reg is used between swap and comparison`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 10
                r2 = 20
                UnOp.BE64(r1)
                BinOp.ADD(r3, r1)  // r1 used here
                UnOp.BE64(r2)
                br(CondOp.EQ(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(2, countBE64(cfg))
    }

    /**
     * Only one operand has a BE64 definition; the other does not.
     *
     * ```
     * r1 = be64(r1)
     * r2 = 99            // plain assignment, not a swap
     * if (r1 == r2) goto 1 else goto 2
     * ```
     * Nothing should be removed.
     */
    @Test
    fun `not simplified when only one operand has be64`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 10
                r2 = 20
                UnOp.BE64(r1)
                r2 = 99            // plain MOV, not a byte swap
                br(CondOp.EQ(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(1, countBE64(cfg))
    }

    /**
     * Ordering comparison (GT); transformation must not fire.
     *
     * ```
     * r1 = be64(r1)
     * r2 = be64(r2)
     * if (r1 > r2) goto 1 else goto 2
     * ```
     */
    @Test
    fun `not simplified for ordering comparison`() {
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(0) {
                r1 = 10
                r2 = 20
                UnOp.BE64(r1)
                UnOp.BE64(r2)
                br(CondOp.GT(r1, r2), 1, 2)
            }
            bb(1) { exit() }
            bb(2) { exit() }
        }

        println("Before\n$cfg")
        simplifyByteSwapInsts(cfg)
        println("After\n$cfg")
        Assertions.assertEquals(2, countBE64(cfg))
    }
}
