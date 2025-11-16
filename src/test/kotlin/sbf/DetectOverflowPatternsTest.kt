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
import sbf.disassembler.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class DetectOverflowPatternsTest {
    @Test
    fun test01() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r3 = r4
                r5 = 0
                BinOp.ADD(r3, r2)
                br(CondOp.GT(r4, r3), 1, 2)
            }
            bb(1) {
                r5 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(0))?.getTerminator()?.metaData?.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null)
    }

    @Test
    fun test02() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r3 = r4
                r5 = 0
                BinOp.ADD(r3, r2)
                r4 = 1
                br(CondOp.GT(r4, r3), 1, 2)
            }
            bb(1) {
                r5 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(false,
            cfg.getBlock(Label.Address(0))?.getTerminator()?.metaData?.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null)
    }

    @Test
    fun test03() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r3 = r4
                r5 = 0
                BinOp.ADD(r3, r2)
                br(CondOp.EQ(r4, r3), 1, 2)
            }
            bb(1) {
                r5 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(false,
            cfg.getBlock(Label.Address(0))?.getTerminator()?.metaData?.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null)
    }

    @Test
    fun test04() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r4 = 1
                r3 = r4
                r5 = 0
                BinOp.ADD(r3, r2)
                br(CondOp.GT(r4, r3), 1, 2)
            }
            bb(1) {
                r5 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(0))?.getTerminator()?.metaData?.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null)
    }

    @Test
    fun test05() {
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val cfg = MutableSbfCFG("test")
        val l1 = Label.Address(1)
        val b1 = cfg.getOrInsertBlock(l1)
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, r4, true))
        b1.add(SbfInstruction.Bin(BinOp.ADD, r3, r2, true))
        b1.add(SbfInstruction.Select(r2, Condition(CondOp.GT, r4, r3), Value.Imm(1UL), Value.Imm(0UL)))
        b1.add(SbfInstruction.Exit())


        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(1))?.let { it ->
                it.getInstructions().any {
                    it.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null
                }
            })
    }

    @Test
    fun test06() {
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r5 = Value.Reg(SbfRegister.R5_ARG)
        val cfg = MutableSbfCFG("test")
        val l1 = Label.Address(1)
        val b1 = cfg.getOrInsertBlock(l1)
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, r4, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, r5, true))
        b1.add(SbfInstruction.Bin(BinOp.ADD, r3, r2, true))
        b1.add(SbfInstruction.Select(r2, Condition(CondOp.GT, r4, r3), Value.Imm(1UL), Value.Imm(0UL)))
        b1.add(SbfInstruction.Exit())


        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(false,
            cfg.getBlock(Label.Address(1))?.let { it ->
                it.getInstructions().any {
                    it.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null
                }
            })
    }

    @Test
    fun test07() {
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val cfg = MutableSbfCFG("test")
        val l1 = Label.Address(1)
        val b1 = cfg.getOrInsertBlock(l1)
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r3, true))
        b1.add(SbfInstruction.Bin(BinOp.ADD, r2, Value.Imm(9_999UL), true))
        b1.add(SbfInstruction.Select(r2, Condition(CondOp.LE, r3, r2), Value.Imm(0UL), Value.Imm(1UL)))
        b1.add(SbfInstruction.Exit())


        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(1))?.let { it ->
                it.getInstructions().any {
                    it.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null
                }
            })
    }

    @Test
    fun test08() {
        val cfg = SbfTestDSL.makeCFG("test8") {
            bb(0) {
                r3 = r4
                r5 = 0
                BinOp.ADD(r3, r2)
                BinOp.ADD(r3, -1)
                br(CondOp.GT(r4, r3), 1, 2)
            }
            bb(1) {
                r5 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                exit()
            }
        }
        cfg.normalize()
        sbfLogger.warn { "Before $cfg" }
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        sbfLogger.warn { "After $cfg" }
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(0))?.getTerminator()?.metaData?.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null)
    }

    @Test
    fun test09() {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val cfg = MutableSbfCFG("test")
        val l1 = Label.Address(1)
        val b1 = cfg.getOrInsertBlock(l1)
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.ADD, r3, Value.Imm(1UL), true))
        b1.add(SbfInstruction.Select(r1, Condition(CondOp.EQ, r3, Value.Imm(0UL)), Value.Imm(1UL), Value.Imm(0UL)))
        b1.add(SbfInstruction.Bin(BinOp.ADD, r4, r1, true))
        b1.add(SbfInstruction.Exit())


        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        detectOverflowPatterns(cfg)
        println("After $cfg")
        Assertions.assertEquals(true,
            cfg.getBlock(Label.Address(1))?.let { it ->
                it.getInstructions().any {
                    it.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK) != null
                }
            })
    }

}
