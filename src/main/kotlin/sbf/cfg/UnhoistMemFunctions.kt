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

import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.callgraph.CVTFunction
import datastructures.stdcollections.*
import sbf.callgraph.CVTCore

/**
 *  Unhoist memcpy and memcmp instructions so that the pointer analysis does not lose too much precision
 *  during joins.
 *
 *  Perform the following transformation
 *
 *  ```
 *  pred1:
 *     goto b
 *  pred2:
 *     goto b
 *
 *  b:
 *     ...
 *     memcpy
 *     continuation
 *  ```
 *
 *  into
 *
 *  ```
 *  pred1:
 *       goto b
 *  pred2:
 *       goto b'
 *  b:
 *      ...
 *      memcpy
 *      goto continuation
 *  b':
 *      ...
 *      memcpy
 *      goto continuation
 *  ```
 *
 * Note that this transformation seems more than a join splitting rather than actual unhoisting.
 * However, after simplifying the CFG the result is equivalent to unhoisting (move instructions to their predecessors).
 **/
fun unhoistMemFunctions(cfg: MutableSbfCFG) {
    val worklist = arrayListOf<Pair<MutableSbfBasicBlock, Int>>()
    // We first try to identify memcpy instruction that should be "unhoist".
    // We do that by checking either memcpy's arguments r1 or r2 is not re-assigned in the block
    // where memcpy is located. This a good hint to do the split.
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            var isR1Written = false
            var isR2Written = false
            for (locInst in b.getLocatedInstructions()) {
                val inst = locInst.inst
                if (inst.writeRegister.contains(Value.Reg(SbfRegister.R1_ARG))) {
                    isR1Written = true
                }
                if (inst.writeRegister.contains(Value.Reg(SbfRegister.R2_ARG))) {
                    isR2Written = true
                }
                if (isR1Written && isR2Written) {
                    // If both are written in the same block, then unhoisting the memcpy will not help
                    break
                }
                if (inst is SbfInstruction.Call &&
                    (CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.SAVE_SCRATCH_REGISTERS)  ||
                        CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.RESTORE_SCRATCH_REGISTERS))) {
                    // We don't want to unhoist these instructions, we bail out here.
                    break
                }

                if (inst is SbfInstruction.Call && (inst.name == "sol_memcpy_" || inst.name == "sol_memcmp_")) {
                    // Added metadata to the instruction
                    val newMetaData = if (inst.name == "sol_memcpy_") {
                        inst.metaData.plus(Pair(SbfMeta.UNHOISTED_MEMCPY, ""))
                    } else {
                        inst.metaData.plus(Pair(SbfMeta.UNHOISTED_MEMCMP, ""))
                    }
                    val newMemcpyInst = inst.copy(metaData = newMetaData)
                    b.replaceInstruction(locInst, newMemcpyInst)
                    worklist.add(Pair(b, locInst.pos))
                }
            }
        }
    }

    doUnhoist(cfg, worklist)
}

/** Unhoist set of instructions that correspond to promoted memcpy (PromoteStoresToMemcpy) **/
fun unhoistPromotedMemcpy(cfg: MutableSbfCFG) {
    fun isStartPromotedMemcpy(inst: SbfInstruction): Boolean {
        return inst is SbfInstruction.Call &&
            CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.SAVE_SCRATCH_REGISTERS) &&
            inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null
    }

    fun isEndPromotedMemcpy(inst: SbfInstruction): Boolean {
        return inst is SbfInstruction.Call &&
            CVTFunction.from(inst.name) == CVTFunction.Core(CVTCore.RESTORE_SCRATCH_REGISTERS) &&
            inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null
    }

    val worklist = arrayListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            var isPromotedMemcpy = false
            for ((i, inst) in b.getInstructions().withIndex()) {
                if (isStartPromotedMemcpy(inst)) {
                    isPromotedMemcpy = true
                    continue
                } else if (isEndPromotedMemcpy(inst)) {
                    isPromotedMemcpy = false
                    // Added metadata to the CVT_restore_scratch_registers instruction
                    val newMetaData = inst.metaData.plus(Pair(SbfMeta.UNHOISTED_MEMCPY, ""))
                    check(inst is SbfInstruction.Call)
                    val newInst = inst.copy(metaData = newMetaData)
                    b.replaceInstruction(i, newInst)
                    worklist.add(Pair(b, i))
                    continue
                } else if (isPromotedMemcpy) {
                    continue
                } else {
                    // we unhoist until the first instruction that it's not part of a promoted memcpy is found
                    break
                }
            }
        }
    }

    doUnhoist(cfg, worklist)
}

/** Do the actual program transformation **/
private fun doUnhoist(cfg: MutableSbfCFG, worklist: ArrayList<Pair<MutableSbfBasicBlock, Int>>) {
    while (worklist.isNotEmpty()) {
        val (block, index) = worklist.removeLast()
        // the memcpy instruction is the last non-terminator instruction in block.
        cfg.splitBlock(block.getLabel(), index)
        // copy needed because the next loop will modify predecessors
        val preds = block.getMutablePreds().toList()
        // we left untouched the first predecessor
        for (pred in preds.drop(1)) {
            val copyOfBlock = copyInstsAndSuccs(cfg, block)
            pred.removeSucc(block)
            pred.addSucc(copyOfBlock)
            pred.replaceJumpTargets(mapOf(Pair(block.getLabel(), copyOfBlock.getLabel())))
        }
    }
}

/**
 * Return a fresh basic block with b's instructions and successors
 */
private fun copyInstsAndSuccs(cfg: MutableSbfCFG, b: MutableSbfBasicBlock): MutableSbfBasicBlock {
    val newB = cfg.getOrInsertBlock(Label.fresh())
    for (inst in b.getInstructions()) {
        newB.add(inst.copyInst())
    }
    for (succ in b.getMutableSuccs()) {
        newB.addSucc(succ)
    }
    return newB
}
