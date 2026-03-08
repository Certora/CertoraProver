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

import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.*

class TACMemcmpTest {
    @Test
    fun test01() {
        // r1 and r2 points to the stack when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r2 := r10
	       r2 := r2 - 200
	       r3 := 32
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 8) := 0
	       *(u64 *) (r1 + 16) := 0
	       *(u64 *) (r1 + 24) := 0
	       *(u64 *) (r2 + 0) := 0
	       *(u64 *) (r2 + 8) := 0
	       *(u64 *) (r2 + 16) := 0
	       *(u64 *) (r2 + 24) := 0
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test1")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(0UL), false))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(0UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        //println("$cfg"}
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test02() {
        // r1 and r2 points to the stack when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r2 := r10
	       r2 := r2 - 200
	       r3 := 32
	       *(u32 *) (r1 + 0) := 0
	       *(u32 *) (r1 + 4) := 0
	       *(u32 *) (r1 + 8) := 0
	       *(u32 *) (r1 + 12) := 0
	       *(u32 *) (r1 + 16) := 0
	       *(u32 *) (r1 + 20) := 0
	       *(u32 *) (r1 + 24) := 0
	       *(u32 *) (r1 + 28) := 0
	       *(u64 *) (r2 + 0) := 0
	       *(u64 *) (r2 + 8) := 0
	       *(u64 *) (r2 + 16) := 0
	       *(u64 *) (r2 + 24) := 0
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test2")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))

        b1.add(SbfInstruction.Mem(Deref(4, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 4), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 12), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 20), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 24), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 28), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(0UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        //println("$cfg"}
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))

        Assertions.assertEquals(true, verify(tacProg))
    }

    // This test passes because of the use of wide bytes in the TAC encoding
    @Test
    fun test03() {
        // r1 and r2 points to the stack when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r2 := r10
	       r2 := r2 - 200
	       r3 := 32
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 8) := 0
	       *(u64 *) (r1 + 16) := 0
	       *(u64 *) (r1 + 24) := 0
	       *(u64 *) (r2 + 0) := 0
	       *(u64 *) (r2 + 8) := 0
	       *(u64 *) (r2 + 16) := 0
	       *(u32 *) (r2 + 24) := 0 <----
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test3")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(0UL), false))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 24), Value.Imm(0UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        //println("$cfg"}
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test04() {
        // r2 points to the stack but r1 points to a different memory region when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r1 := *(u64 *) (r1 + 0)
	       r2 := r10
	       r2 := r2 - 200
	       r3 := 32
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 8) := 0
	       *(u64 *) (r1 + 16) := 0
	       *(u64 *) (r1 + 24) := 0
	       *(u64 *) (r2 + 0) := 0
	       *(u64 *) (r2 + 8) := 0
	       *(u64 *) (r2 + 16) := 0
	       *(u64 *) (r2 + 24) := 0
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test4")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r1, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(0UL), false))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(0UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        //println("$cfg"}
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test05() {
        // both r1 and r2 do not point to the stack when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r1 := *(u64 *) (r1 + 0)
	       r2 := r10
	       r2 := r2 - 200
	       r2 := *(u64 *) (r2 + 0)
	       r3 := 32
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 8) := 0
	       *(u64 *) (r1 + 16) := 0
	       *(u64 *) (r1 + 24) := 0
	       *(u64 *) (r2 + 0) := 0
	       *(u64 *) (r2 + 8) := 0
	       *(u64 *) (r2 + 16) := 0
	       *(u64 *) (r2 + 24) := 0
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test5")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r1, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r2, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(0UL), false))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 8), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 16), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 24), Value.Imm(0UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        //println("$cfg"}
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test06() {
        // both r1 and r2 do not point to the stack when sol_memcmp_ is called
        /*
           r1 := r10
	       r1 := r1 - 100
	       r1 := *(u64 *) (r1 + 0)
	       r2 := r10
	       r2 := r2 - 200
	       r2 := *(u64 *) (r2 + 0)
	       r3 := 32
	       *(u64 *) (r1 + 0) := 1234
	       *(u64 *) (r1 + 8) := 1234
	       *(u64 *) (r1 + 16) := 1234
	       *(u64 *) (r1 + 24) := 1234
	       *(u32 *) (r2 + 0) := 1234
	       *(u32 *) (r2 + 4) := 1234
	       *(u32 *) (r2 + 8) := 1234
	       *(u32 *) (r2 + 12) := 1234
	       *(u32 *) (r2 + 16) := 1234
	       *(u32 *) (r2 + 20) := 1234
	       *(u32 *) (r2 + 24) := 1234
	       *(u32 *) (r2 + 28) := 1234
	       r0 := call sol_memcmp_(r1,r2,r3)
         */
        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test6")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r1, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r2, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 16), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 24), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 0), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 4), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 8), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 12), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 16), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 20), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 24), Value.Imm(1234UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 28), Value.Imm(1234UL), false))

        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        println("$cfg")
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        // IMPORTANT: the prover should not be able to prove this program.
        Assertions.assertEquals(false, verify(tacProg))
    }

    @Test
    fun test07() {
        /*
           r2 := r10
	       r2 := r1 - 100
	       r4 := r2

	       r1 := r10
	       r1:= r1 - 200
	       r5:= r1
           assume(memcmp(r1,r2,32) == 0)

           r1 := r10
	       r1 := r1 - 300
	       r2 := r5
           memcpy(r1, r2, 32)

           r2 := r4
           assert(memcmp(r1,r2,32) == 0)
         */

        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)


        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test7")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)


        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r2, true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r5, r1, true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r0, Value.Imm(0UL))))


        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(300UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r5, true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r4, true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))

    }

    @Test
    fun test08() {
        // Similar to test07 but heap instead of stack.
        // However, the program is not verified as safe.
        //
        // The problem is that two heap-allocated memory gets unified after memcpy
        // Then, the TAC encoding of the mempy first havocs the destination.
        // Since the source and destination are the same bytemap the equalities from memcmp are lost.
        /*
           r2 := r10
	       r2 := r1 - 100
	       r1 := 32
           r0 := alloc
           *(u64 *) (r2 + 0) := r0

	       r2 := r10
	       r2:= r2 - 200
	       r1 := 32
           r0 := alloc
           *(u64 *) (r2 + 0) := r0

	       r1 := r10
	       r1 := r1 - 100
	       r1 := *(u64 *) (r1 + 0)
	       r2 := *(u64 *) (r2 + 0)
	       r3 := 32
           assume(memcmp(r1,r2,r3) == 0)

           r1 := r10
	       r1 := r1 - 300
	       r1 := *(u64 *) (r1 + 0)
           memcpy(r1, r2, r3)

           r2 := r10
	       r2 := r1 - 100
	       r2 := *(u64 *) (r2 + 0)
           assert(memcmp(r1,r2,32) == 0)
         */

        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test8")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)


        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r0, false))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(200UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r0, false))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r1, true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r2, true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r0, Value.Imm(0UL))))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(300UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r1, true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY))


        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r2, true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r0, Value.Imm(0UL))))

        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(false, verify(tacProg))

    }

}
