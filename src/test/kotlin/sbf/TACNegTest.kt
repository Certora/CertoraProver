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
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import org.junit.jupiter.api.*


class TACNegTest {
    /** 64-bits unsigned division **/
    @Test
    fun test1() {
        /**
          r2 := -9223372036854775808
          r3 := -5
          r4 := -9223372036854775807
          r2 := neg(r2)
          r3 := neg(r3)
          r4 := neg(r4)
          assert(r2 == -9223372036854775808)
          assert(r3 == 5)
          assert(9223372036854775807)
         */
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r4 = Value.Reg(SbfRegister.R4_ARG)
        val r5 = Value.Reg(SbfRegister.R5_ARG)
        val cfg = MutableSbfCFG("test1")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)
        cfg.setExit(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, Value.Imm(Long.MIN_VALUE.toULong()), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm((-5L).toULong()), true))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, Value.Imm(Long.MIN_VALUE.toULong() + 1U), true))
        b1.add(SbfInstruction.Un(UnOp.NEG, r2))
        b1.add(SbfInstruction.Un(UnOp.NEG, r3))
        b1.add(SbfInstruction.Un(UnOp.NEG, r4))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r5, Value.Imm(Long.MIN_VALUE.toULong()), true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r2, r5)))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(5UL))))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r5, Value.Imm(9223372036854775807UL), true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r4, r5)))
        b1.add(SbfInstruction.Exit())

        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


}
