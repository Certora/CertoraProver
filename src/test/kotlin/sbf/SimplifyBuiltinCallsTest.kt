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
import sbf.disassembler.*
import org.junit.jupiter.api.*

class SimplifyBuiltinCallsTest {
    @Test
    fun test01() {
    println("====== TEST 1 =======")
        /**
         *   r1 := r10 - 24
         *   r2 := r10 - 104
         *   r3 := 32
         *   call memcmp
         **/

        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(24UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(104UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(4UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Mem(Deref(4, r10, -4), r2, true))
        b1.add(SbfInstruction.Exit())

        println("Before transformation\n$cfg")
        simplifyMemoryIntrinsics(cfg)
        println("After transformation\n$cfg")
    }

    @Test
    fun test02() {
        println("====== TEST 2 =======")
        /**
         *   r1 := r10 - 24
         *   r2 := r10 - 104
         *   r3 := 32
         *   r4 := r10 - 4
         *   call sol_memcmp_
         *   r2 := *(u32 *)(r10 -4)
         **/

        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(24UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(104UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(4UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Mem(Deref(4, r10, -4), r2, true))
        b1.add(SbfInstruction.Exit())

        println("Before transformation\n$cfg")
        simplifyMemoryIntrinsics(cfg)
        println("After transformation\n$cfg")
    }

    @Test
    fun test03() {
        println("====== TEST 3 =======")
        /**
         *   r1 := r10 - 24
         *   r2 := r10 - 104
         *   r3 := 32
         *   r4 := r10 - 4
         *   call sol_memcmp_
         *   r2 := *(u32 *)(r10 -4)
         *   r1 := r10 - 24
         *   r2 := r10 - 104
         *   r3 := 32
         *   r4 := r10 - 4
         *   call sol_memcmp_
         *   r2 := *(u32 *)(r10 -4)
         **/

        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(24UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(104UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(4UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Mem(Deref(4, r10, -4), r2, true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(24UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(104UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r4, Value.Imm(4UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(32UL), true))
        b1.add(SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP))
        b1.add(SbfInstruction.Mem(Deref(4, r10, -4), r2, true))

        b1.add(SbfInstruction.Exit())

        println("Before transformation\n$cfg")
        simplifyMemoryIntrinsics(cfg)
        println("After transformation\n$cfg")
    }
}
