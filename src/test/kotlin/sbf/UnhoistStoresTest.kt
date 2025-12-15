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
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

private val globals = GlobalVariables(DefaultElfFileView)

class UnhoistStoresTest {
    private fun getNumOfUnhoistedStores(cfg: SbfCFG): UInt {
        var counter = 0U
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                val metadata = inst.metaData
                if (metadata.getVal(SbfMeta.UNHOISTED_STORE) != null) {
                    counter++
                }
            }
        }
        return counter
    }

    private fun getNumOfSplitWideStores(cfg: SbfCFG): UInt {
        var counter = 0U
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                val metadata = inst.metaData
                if (metadata.getVal(SbfMeta.HINT_OPTIMIZED_WIDE_STORE) != null) {
                    counter++
                }
            }
        }
        return counter
    }

    @Test
    fun test01() {
        /*
           b0:
              if(*) goto b1 else b5
            b1:
              if(*) goto b2 else b3
           b2:
              r1:= 42949672960
              goto b4
           b3:
              r1:= 73014444032
              goto b4
           b4:
              r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := r1  <-- should be unhoisted
              goto b6
    	   b5:
    	      r3 := r10
    	      r3 := r3 - 200
    	      *(u32 *) (r3 + 0) := 24  <- should NOT be unhoisted
    	      goto b6
    	   b6:
    	      r4 := r10
    	      r4 := r4 - 200
    	      r0 := *(u32 *) (r4 + 0)
    	      exit


         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test1")

        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val l4 = Label.Address(5)
        val l5 = Label.Address(6)
        val l6 = Label.Address(7)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        val b6 = cfg.getOrInsertBlock(l6)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b5)
        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)
        b4.addSucc(b6)
        b5.addSucc(b6)


        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l5))

        b1.add(SbfInstruction.Havoc(r2))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l2, l3))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(42949672960UL), true))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(73014444032UL), true))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))


        b4.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b4.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b4.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))
        b4.add(SbfInstruction.Jump.UnconditionalJump(l6))


        b5.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b5.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b5.add(SbfInstruction.Mem(Deref(4, r3, 0), Value.Imm(24UL), false))
        b5.add(SbfInstruction.Jump.UnconditionalJump(l6))

        b6.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b6.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(200UL), true))
        b6.add(SbfInstruction.Mem(Deref(4, r4, 0), r0, true))
        b6.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        unhoistStoresAndLoads(cfg, globals)
        println("After $cfg")
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)
    }

    @Test
    fun test02() {
        // The unhoist of the store at b4 should be done
        /*
           b0:
              if(*) goto b1 else b5
            b1:
              r2 := r10
    	      r2 := r2 - 200
              if(*) goto b2 else b3
           b2:
              r1:= 42949672960
              goto b4
           b3:
              r1:= 73014444032
              goto b4
           b4:
    	      *(u64 *) (r2 + 0) := r1 <-- should be unhoisted to b3 and b2
              goto b6
    	   b5:
    	      r3 := r10
    	      r3 := r3 - 200
    	      *(u32 *) (r3 + 0) := 24
    	      goto b6
    	   b6:
    	      r4 := r10
    	      r4 := r4 - 200
    	      r0 := *(u32 *) (r4 + 0)
    	      exit


         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test2")

        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val l4 = Label.Address(5)
        val l5 = Label.Address(6)
        val l6 = Label.Address(7)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        val b6 = cfg.getOrInsertBlock(l6)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b5)
        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)
        b4.addSucc(b6)
        b5.addSucc(b6)


        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l5))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Havoc(r2))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l2, l3))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(42949672960UL), true))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(73014444032UL), true))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))


        b4.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))
        b4.add(SbfInstruction.Jump.UnconditionalJump(l6))


        b5.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b5.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b5.add(SbfInstruction.Mem(Deref(4, r3, 0), Value.Imm(24UL), false))
        b5.add(SbfInstruction.Jump.UnconditionalJump(l6))

        b6.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b6.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(200UL), true))
        b6.add(SbfInstruction.Mem(Deref(4, r4, 0), r0, true))
        b6.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        unhoistStoresAndLoads(cfg, globals)
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)
        println("After $cfg")
    }

    @Test
    fun test03() {
        // The unhoist of the store at b4 should be done
        /*
           b0:
              if(*) goto b1 else b5
            b1:
              r2 := r10
    	      r2 := r2 - 200
              if(*) goto b2 else b3
           b2:
              r1:= 42949672960
              goto b4
           b3:
              r1:= 73014444032
              goto b4
           b4:
    	      *(u64 *) (r2 + 0) := r1 <-- should be unhoisted to b3 and b2
              goto b6
    	   b5:
    	      r3 := r10
    	      r3 := r3 - 200
    	      *(u32 *) (r3 + 0) := 24
    	      goto b6
    	   b6:
    	      r4 := r10
    	      r4 := r4 - 200
    	      r0 := *(u32 *) (r4 + 0)
    	      exit


         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test3")

        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val l4 = Label.Address(5)
        val l5 = Label.Address(6)
        val l6 = Label.Address(7)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        val b6 = cfg.getOrInsertBlock(l6)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b5)
        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)
        b4.addSucc(b6)
        b5.addSucc(b6)


        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l5))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Havoc(r4))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r4, Value.Imm(0UL)), l2, l3))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(42949672960UL), true))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(73014444032UL), true))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))


        b4.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))
        b4.add(SbfInstruction.Jump.UnconditionalJump(l6))


        b5.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b5.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b5.add(SbfInstruction.Mem(Deref(4, r3, 0), Value.Imm(24UL), false))
        b5.add(SbfInstruction.Jump.UnconditionalJump(l6))

        b6.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b6.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(200UL), true))
        b6.add(SbfInstruction.Mem(Deref(4, r4, 0), r0, true))
        b6.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        unhoistStoresAndLoads(cfg, globals)
        cfg.simplify(globals)
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)
        val memSummaries = MemorySummaries()
        splitWideStores(cfg, globals, memSummaries)
        Assertions.assertEquals(true,  getNumOfSplitWideStores(cfg) == 4U)
        println("After unhoistStores + simplify + splitWideStores:\n$cfg")
    }

    @Test
    fun test04() {
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(0) {
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                r2 = r10[-62]
                goto(3)
            }
            bb(2) {
                r2 = r10[-56]
                goto(3)
            }
            bb(3) {
                r2[0] = 5
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        unhoistStoresAndLoads(cfg, globals)
        cfg.simplify(globals)
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)
        println("After $cfg")
    }

    @Test
    fun test05() {
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(0) {
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                r2 = r10[-62]
                goto(3)
            }
            bb(2) {
                r2 = r10[-56]
                goto(3)
            }
            bb(3) {
                r1[0] = 5
                r3 = r2
                BinOp.ADD(r3, 4)
                r4 = r3
                r5[0] = r4
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        unhoistStoresAndLoads(cfg, globals)
        cfg.simplify(globals)
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)
        println("After $cfg")
    }

    @Test
    fun test06() {
        val cfg = SbfTestDSL.makeCFG("test6") {
            bb(0) {
                br(CondOp.EQ(r3, 0x0), 1, 2)
            }
            bb(1) {
                r2 = 567890
                goto(3)
            }
            bb(2) {
                r2 = 568934
                goto(3)
            }
            bb(3) {
                r2[0] = 5
                exit()
            }
        }
        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)
        val globals = GlobalVariables(DefaultElfFileView,
                                      listOf(SbfGlobalVariable("x", 567890L, 4),
                                             SbfGlobalVariable("y", 568934L, 4))
        )
        unhoistStoresAndLoads(cfg, globals)
        println("After $cfg")
        //cfg.simplify(globals)
        Assertions.assertEquals(true,  getNumOfUnhoistedStores(cfg) == 2U)

    }

}
