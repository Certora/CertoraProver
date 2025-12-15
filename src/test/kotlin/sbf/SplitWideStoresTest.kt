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
import org.junit.jupiter.api.*

private val globals = GlobalVariables(DefaultElfFileView)

class SplitWideStoresTest {
    private fun checkNoWideStores(cfg: SbfCFG): Boolean {
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                if (inst is SbfInstruction.Mem && !inst.isLoad) {
                    if (inst.access.width == 8.toShort()) {
                        return false
                    }
                }
            }
        }
        return true
    }

    @Test
    fun test01() {
        /*
           b0:
              if(*) goto b1 else b2
           b1:
              r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := 42949672960
    	      goto b3
    	   b2:
    	      r2 := r10
    	      r2 := r2 - 200
    	      *(u32 *) (r2 + 0) := 24
    	      goto b3
    	   b3:
    	      r3 := r10
    	      r3 := r3 - 200
    	      r0 := *(u32 *) (r3 + 0)
    	      exit

    	   The store at b1 should be replaced with:
              	*(u32 *) (r2 + 0) :=  0 /* hint.optimized_wide_store */
    	        *(u32 *) (r2 + 4) := 10  /* hint.optimized_wide_store */

    	   Note that 42949672960 has the binary representation of 36 bits 10100....0
    	   The first 32 bits will be 1010 which in decimal is 10 and the second 32 bits are all zeros.

         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")

        val l0 = Label.Address(1)
        val l1 = Label.Address(2)
        val l2 = Label.Address(3)
        val l3 = Label.Address(4)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b3)


        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l2))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(42949672960UL), false))
        b1.add(SbfInstruction.Jump.UnconditionalJump(l2))

        b2.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b2.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b2.add(SbfInstruction.Mem(Deref(4, r2, 0), Value.Imm(24UL), false))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l2))

        b3.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b3.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b3.add(SbfInstruction.Mem(Deref(4, r3, 0), r0, true))
        b3.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)

        val memSummaries = MemorySummaries()
        splitWideStores(cfg, globals, memSummaries)
        println("After $cfg")
        Assertions.assertEquals(true, checkNoWideStores(cfg))
    }

    @Test
    fun test02() {
        /*
           b0:
              if(*) goto b1 else b2
           b1:
              r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := 42949672960
    	      goto b5
    	   b2:
    	      goto b3
           b3
    	      r2 := r10
    	      r2 := r2 - 200
    	      *(u32 *) (r2 + 0) := 24
    	      goto b4
           b4:
              goto b5
    	   b5:
    	      r3 := r10
    	      r3 := r3 - 200
    	      r0 := *(u32 *) (r3 + 0)
    	      exit

    	   The store at b1 should be replaced with:
              	*(u32 *) (r2 + 0) := 0   /* hint.optimized_wide_store */
    	        *(u32 *) (r2 + 4) := 10  /* hint.optimized_wide_store */

         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")

        val l0 = Label.Address(0)
        val l1 = Label.Address(1)
        val l2 = Label.Address(2)
        val l3 = Label.Address(3)
        val l4 = Label.Address(4)
        val l5 = Label.Address(5)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b2)
        b1.addSucc(b5)
        b2.addSucc(b3)
        b3.addSucc(b4)
        b4.addSucc(b5)


        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l2))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(42949672960UL), false))
        b1.add(SbfInstruction.Jump.UnconditionalJump(l5))

        b2.add(SbfInstruction.Jump.UnconditionalJump(l3))


        b3.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b3.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b3.add(SbfInstruction.Mem(Deref(4, r2, 0), Value.Imm(24UL), false))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))

        b4.add(SbfInstruction.Jump.UnconditionalJump(l5))

        b5.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b5.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b5.add(SbfInstruction.Mem(Deref(4, r3, 0), r0, true))
        b5.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)

        val memSummaries = MemorySummaries()
        splitWideStores(cfg, globals, memSummaries)
        println("After $cfg")
        Assertions.assertEquals(true, checkNoWideStores(cfg))
    }

    @Test
    fun test03() {
        /*
           b0:
              if(*) goto b1 else b2
           b2:
              if(*) goto b3 else b6
           b3:
              if(*) goto b4 else b5
           b1:
              r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := 42949672960
    	      goto b8
           b4:
    	      r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := 42949672960
    	      goto b7
           b5
    	      r2 := r10
    	      r2 := r2 - 200
    	      *(u32 *) (r2 + 0) := 24
    	      goto b7
          b6
    	      r2 := r10
    	      r2 := r2 - 200
    	      *(u64 *) (r2 + 0) := 42949672960
    	      goto b7
          b7:
              goto b8
    	  b8:
    	      r3 := r10
    	      r3 := r3 - 200
    	      r0 := *(u32 *) (r3 + 0)
    	      exit

    	   The store at b1, b4, and b6 should be replaced with:
              	*(u32 *) (r2 + 0) := 0   /* hint.optimized_wide_store */
    	        *(u32 *) (r2 + 4) := 10  /* hint.optimized_wide_store */
         */
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")

        val l0 = Label.Address(0)
        val l1 = Label.Address(1)
        val l2 = Label.Address(2)
        val l3 = Label.Address(3)
        val l4 = Label.Address(4)
        val l5 = Label.Address(5)
        val l6 = Label.Address(6)
        val l7 = Label.Address(7)
        val l8 = Label.Address(8)
        val b0 = cfg.getOrInsertBlock(l0)
        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        val b5 = cfg.getOrInsertBlock(l5)
        val b6 = cfg.getOrInsertBlock(l6)
        val b7 = cfg.getOrInsertBlock(l7)
        val b8 = cfg.getOrInsertBlock(l8)
        cfg.setEntry(b0)
        b0.addSucc(b1)
        b0.addSucc(b2)
        b1.addSucc(b8)
        b2.addSucc(b3)
        b2.addSucc(b6)
        b3.addSucc(b4)
        b3.addSucc(b5)
        b4.addSucc(b7)
        b5.addSucc(b7)
        b6.addSucc(b7)
        b7.addSucc(b8)

        b0.add(SbfInstruction.Havoc(r2))
        b0.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l1, l2))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(42949672960UL), false))
        b1.add(SbfInstruction.Jump.UnconditionalJump(l8))

        b2.add(SbfInstruction.Havoc(r2))
        b2.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l3, l6))

        b3.add(SbfInstruction.Havoc(r2))
        b3.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l4, l5))

        b4.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b4.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b4.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(42949672960UL), false))
        b4.add(SbfInstruction.Jump.UnconditionalJump(l7))

        b5.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b5.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b5.add(SbfInstruction.Mem(Deref(4, r2, 0), Value.Imm(24UL), false))
        b5.add(SbfInstruction.Jump.UnconditionalJump(l7))

        b6.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b6.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b6.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(42949672960UL), false))
        b6.add(SbfInstruction.Jump.UnconditionalJump(l7))

        b7.add(SbfInstruction.Jump.UnconditionalJump(l8))

        b8.add(SbfInstruction.Bin(BinOp.MOV, r3, r10, true))
        b8.add(SbfInstruction.Bin(BinOp.SUB, r3, Value.Imm(200UL), true))
        b8.add(SbfInstruction.Mem(Deref(4, r3, 0), r0, true))
        b8.add(SbfInstruction.Exit())

        cfg.normalize()
        println("Before $cfg")
        cfg.verify(true)

        val memSummaries = MemorySummaries()
        splitWideStores(cfg, globals, memSummaries)
        println("After $cfg")
        Assertions.assertEquals(true, checkNoWideStores(cfg))
    }
}
