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
import org.junit.jupiter.api.*
import sbf.disassembler.Label
import sbf.disassembler.SbfRegister

class TACSplitOrMergeCellsTest {

    /**
     * ```
     *   // Two narrow writes merged into a wider read
     *   *(u32 *)(r10 - 64) := 26
     *   *(u32 *)(r10 - 60) := -2147483648
     *   assert(*(u64 *)(r10 - 64) == -9223372036854775782)
     * ```
     */
    @Test
    fun test1() {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test1")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(64UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(60UL), true))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 0), Value.Imm(26UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 0), Value.Imm((-2147483648).toULong()), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r3, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm((-9223372036854775782).toULong()))))
        b1.add(SbfInstruction.Exit())

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * ```
     *   // Two narrow writes merged into a wider read
     *   *(u32 *)(r10 - 64) := 26
     *   *(u32 *)(r10 - 60) := nondet()
     *   assert(*(u64 *)(r10 - 64) == 26)
     * ```
     */
    @Test
    fun test2() {
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test1")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        cfg.setExit(b1)
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(64UL), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(60UL), true))
        b1.add(SbfInstruction.Call("CVT_nondet_u32"))
        b1.add(SbfInstruction.Mem(Deref(4, r1, 0), Value.Imm(26UL), false))
        b1.add(SbfInstruction.Mem(Deref(4, r2, 0), r0, false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r3, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(26UL))))
        b1.add(SbfInstruction.Exit())

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(false, verify(tacProg))
    }

}
