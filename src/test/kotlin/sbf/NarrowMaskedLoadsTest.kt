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

class NarrowMaskedLoadsTest {

    private fun hasOnlyLoadsOfWidth(cfg: SbfCFG, width: Short): Boolean {
        for (b in cfg.getBlocks().values) {
            if (b.getInstructions().any { inst ->
                    inst is SbfInstruction.Mem && inst.isLoad && inst.access.width != width
                }
            ) {
                return false
            }
        }
        return true
    }

    private fun hasMaskInst(cfg: SbfCFG): Boolean {
        for (b in cfg.getBlocks().values) {
            if (b.getInstructions().any { inst -> inst is SbfInstruction.Bin && inst.op == BinOp.AND}) {
                return true
            }
        }
        return false
    }

    /**
     *  ```
     *   r1 := *(u64 *) (r10 + -56)
     *   r1 = r1 and 255
     *   if (r1 == 0) ...
     *  ```
     *  should be transformed into
     *  ```
     *   r1 := *(u8 *) (r10 + -56)
     *   if (r1 == 0) ...
     *  ```
     **/
    @Test
    fun `simple load of 8 bytes masked by 0xFF`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                BinOp.AND(r1, 255)
                br(CondOp.NE(r1,0), 1, 2)
            }
            bb(1) {
                goto (3)
            }
            bb(2) {
                goto (3)
            }
            bb(3) {
                exit()
            }
         }


        println("Before\n$cfg")
        narrowMaskedLoads(cfg) { true }
        println("After\n$cfg")
        Assertions.assertEquals(false, hasMaskInst(cfg))
        Assertions.assertEquals(true, hasOnlyLoadsOfWidth(cfg, 1))
    }


    /**
     * ```
     *   r1 := *(u64 *) (r10 + -56)
     *   r1 = r1 and 4294967295
     *   if (r1 == 0) ...
     * ```
     * should be transformed into
     *
     * ```
     *   r1 := *(u32 *) (r10 + -56)
     *   if (r1 == 0) ...
     * ```
     **/
    @Test
    fun `simple load of 8 bytes masked by 0xFFFF_FFFF`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                BinOp.AND(r1, 4294967295)
                br(CondOp.NE(r1,0), 1, 2)
            }
            bb(1) {
                goto (3)
            }
            bb(2) {
                goto (3)
            }
            bb(3) {
                exit()
            }
        }


        println("Before\n$cfg")
        narrowMaskedLoads(cfg) { true }
        println("After\n$cfg")
        Assertions.assertEquals(false, hasMaskInst(cfg))
        Assertions.assertEquals(true, hasOnlyLoadsOfWidth(cfg, 4))
    }

    /**
     * ```
     *   r1 = *(u64 *) (r10 -56)
     *   r1 += 1
     *   r1 = r1 and 255
     *   if (r1 == 0)  { ... } else { ... }
     * ```
     * should not be transformed.
     **/
    @Test
    fun `load of 8 bytes followed by updated and then masked by 0xFF`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                BinOp.ADD(r1, 1)
                BinOp.AND(r1, 255)
                br(CondOp.NE(r1,0), 1, 2)
            }
            bb(1) {
                goto (3)
            }
            bb(2) {
                goto (3)
            }
            bb(3) {
                exit()
            }
        }


        println("Before\n$cfg")
        narrowMaskedLoads(cfg) { true }
        println("After\n$cfg")
        Assertions.assertEquals(true, hasMaskInst(cfg))
        Assertions.assertEquals(true, hasOnlyLoadsOfWidth(cfg, 8))
    }

    /**
     * ```
     *   r1 = *(u64 *) (r10 + -56)
     *   r1 = r1 and 65535
     *   r2 = *(u64 *) (r10 -256)
     *   r2 = r2 and 65535
     *   r3 = r1 or r2
     *   if (r3 == 0) ...
     * ```
     *
     * should be transformed into:
     *
     * ```
     *   r1 = *(u16 *) (r10 + -56)
     *   r2 = *(u16 *) (r10 -256)
     *   r3 = r1 or r2
     *   if (r3 == 0) ...
     * ```
     **/
    @Test
    fun `two loads of 8 bytes masked by 0xFFFF`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                BinOp.AND(r1, 65535)
                r2 = r10[-256]
                BinOp.AND(r2, 65535)
                r3 = r1
                BinOp.OR(r3, r2)
                br(CondOp.NE(r3,0), 1, 2)
            }
            bb(1) {
                goto (3)
            }
            bb(2) {
                goto (3)
            }
            bb(3) {
                exit()
            }
        }


        println("Before\n$cfg")
        narrowMaskedLoads(cfg) { true }
        println("After\n$cfg")
        Assertions.assertEquals(false, hasMaskInst(cfg))
        Assertions.assertEquals(true, hasOnlyLoadsOfWidth(cfg, 2))
    }

}
