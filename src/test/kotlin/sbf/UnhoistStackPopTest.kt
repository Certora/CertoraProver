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

import sbf.callgraph.CVTFunction
import sbf.cfg.*
import sbf.disassembler.*
import org.junit.jupiter.api.*
import sbf.callgraph.CVTCore

class UnhoistStackPopTest {

    private fun getNumOfUnhoistedStackPop(cfg: SbfCFG): UInt {
        var counter = 0U
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                if (inst is SbfInstruction.Call &&
                    CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.RESTORE_SCRATCH_REGISTERS)) {
                    continue
                }
                val metadata = inst.metaData
                if (metadata.getVal(SbfMeta.UNHOISTED_STACK_POP) != null) {
                    counter++
                }
            }
        }
        return counter
    }

    private fun buildStackPush(bb: MutableSbfBasicBlock) {
        bb.add(SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name))
        bb.add(SbfInstruction.Bin(BinOp.ADD, Value.Reg(SbfRegister.R10_STACK_POINTER),
            Value.Imm(SBF_STACK_FRAME_SIZE.toULong()), true))
    }

    private fun buildStackPop(bb: MutableSbfBasicBlock) {
        bb.add(SbfInstruction.Bin(BinOp.SUB, Value.Reg(SbfRegister.R10_STACK_POINTER),
            Value.Imm(SBF_STACK_FRAME_SIZE.toULong()), true))
        bb.add(SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name))
    }

    val globals = GlobalVariables(DefaultElfFileView)

    @Test
    fun test01() {
        /**
         * ```
         *  b1:
         *     save_scratch_registers
         *     r10 += 4096
         *     if(*) goto b2 else b3
         *  b2:
         *     r0 := 1
         *     goto b4
         *  b3:
         *     r0:= 2
         *     goto b4
         *  b3:
         *     r10 -= 4096
         *     restore_scratch_registers
         *     exit
         * ```
         *
         * should be transformed to:
         *
         * ```
         *  b1:
         *     save_scratch_registers
         *     r10 += 4096
         *     if(*) goto b2 else b3
         *  b2:
         *     r0 := 1
         *     r10 -= 4096
         *     goto b4
         *  b3:
         *     r0:= 2
         *     r10 -= 4096
         *     goto b4
         *  b3:
         *     restore_scratch_registers
         *     exit
         * ```
         */

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val cfg = MutableSbfCFG("test1")

        val l1 = Label.Address(1)
        val l2 = Label.Address(2)
        val l3 = Label.Address(3)
        val l4 = Label.Address(4)

        val b1 = cfg.getOrInsertBlock(l1)
        val b2 = cfg.getOrInsertBlock(l2)
        val b3 = cfg.getOrInsertBlock(l3)
        val b4 = cfg.getOrInsertBlock(l4)
        cfg.setEntry(b1)
        b1.addSucc(b2)
        b1.addSucc(b3)
        b2.addSucc(b4)
        b3.addSucc(b4)

        buildStackPush(b1)
        b1.add(SbfInstruction.Havoc(r2))
        b1.add(SbfInstruction.Jump.ConditionalJump(Condition(CondOp.EQ, r2, Value.Imm(0UL)), l2, l3))
        b2.add(SbfInstruction.Bin(BinOp.MOV, r0, Value.Imm(1UL), true))
        b2.add(SbfInstruction.Jump.UnconditionalJump(l4))
        b3.add(SbfInstruction.Bin(BinOp.MOV, r0, Value.Imm(2UL), true))
        b3.add(SbfInstruction.Jump.UnconditionalJump(l4))
        buildStackPop(b4)
        b4.add(SbfInstruction.Exit())
        cfg.normalize()
        println("Before\n$cfg")
        cfg.verify(true)
        unhoistStackPop(cfg, globals)
        println("After\n$cfg")
        Assertions.assertEquals(true,  getNumOfUnhoistedStackPop(cfg) == 2U)
    }

}
