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

package sbf.cfg

import sbf.callgraph.CVTCore
import sbf.callgraph.CVTFunction
import sbf.disassembler.SbfRegister

/**
 * Move instruction that pops the stack into its predecessors.
 *
 * [numInstsBeforePop] indicates how far in a block we search for the pop instruction.
 * This argument limits code bloating since each time a pop instruction is folded into its predecessors, all the
 * instructions before the pop instruction will be also copied.
 *
 * This allows to remove dead stack fields in the pointer analysis before performing
 * joins that might cause extra unifications.
 **/
fun unhoistStackPop(cfg: MutableSbfCFG, numInstsBeforePop: Int = 5) {
    val workList = arrayListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            for (locInst in b.getLocatedInstructions()) {
                val i = locInst.pos
                val inst = locInst.inst
                if (i < numInstsBeforePop) {
                    if (inst is SbfInstruction.Call && CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.SAVE_SCRATCH_REGISTERS)) {
                        // we don't want to unhoist this instruction, so we bail out
                        break
                    }
                    if (inst is SbfInstruction.Call && CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.RESTORE_SCRATCH_REGISTERS)) {
                        if (inst.metaData.getVal(SbfMeta.UNHOISTED_STACK_POP) != null) {
                            // If unhoisting already took place we bail out.
                            break
                        }
                        if (i == 0 || !isStackPop(b.getInstruction(i-1))) {
                            // If a stack pop instruction does not precede CVT_restore_scratch_registers then we bail out
                            break
                        }
                        // Added metadata to CVT_restore_scratch registers and the stack pop instructions
                        val prevInst = b.getInstruction(i-1)
                        check(prevInst is SbfInstruction.Bin)
                        val newPrevInst = prevInst.copy(metaData = prevInst.metaData.plus(SbfMeta.UNHOISTED_STACK_POP()))
                        b.replaceInstruction(i-1, newPrevInst)
                        val newInst = inst.copy(metaData = inst.metaData.plus(SbfMeta.UNHOISTED_STACK_POP()))
                        b.replaceInstruction(i, newInst)

                        // We intentionally exclude the call to CVT_restore_scratch_registers from unhoisting
                        workList.add(Pair(b, i))
                        // We only unhoist once per block. Otherwise, the second unhoisting will unhoist a call
                        // to CVT_restore_scratch_registers which we don't want.
                        break
                    }
                } else {
                    break
                }
            }
        }
    }

    // Do the actual unhoisting
    while (workList.isNotEmpty()) {
        val (b, i) = workList.removeLast()
        b.foldIntoPredecessors(i)
    }
}

private fun isStackPop(inst: SbfInstruction): Boolean {
    return if (inst is SbfInstruction.Bin) {
            val lhs = inst.dst
            val rhs = inst.v
            (lhs == Value.Reg(SbfRegister.R10_STACK_POINTER) && inst.op == BinOp.SUB &&
            (rhs is Value.Imm && rhs.v == SBF_STACK_FRAME_SIZE.toULong()))
    } else {
        false
    }
}

