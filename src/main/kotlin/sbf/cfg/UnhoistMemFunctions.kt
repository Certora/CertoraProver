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
import datastructures.stdcollections.*
import sbf.callgraph.SolanaFunction

data class UnhoistHeuristics(
    // if true then unhoist only takes place if at least one operand is last written in one of the predecessors
    val requireOperandDefinedInPredecessor: Boolean,
    // If number of instructions before the memcpy in the current block is greater than this number the unhoist won't happen
    val maxInstructionsBeforeMemcpy: Int
)


/**
 *  Unhoist memcpy and memcmp instructions so that the pointer/backward analyses do not lose too much precision
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
fun unhoistMemFunctions(
    cfg: MutableSbfCFG,
    heuristics: UnhoistHeuristics = UnhoistHeuristics(false, 10)
) {
    val worklist = mutableListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            findUnhoistMemFunCandidates(b, worklist, heuristics)
        }
    }
    doUnhoist(cfg, worklist)
}

/**
 * Unhoist set of instructions that correspond to promoted memcpy
 * (i.e., memcpy added by `PromoteStoresToMemcpy`)
 **/
fun unhoistPromotedMemcpy(cfg: MutableSbfCFG) {
    val worklist = mutableListOf<Pair<MutableSbfBasicBlock, Int>>()
    for (b in cfg.getMutableBlocks().values) {
        if (b.getPreds().size > 1) {
            findUnhoistPromotedMemcpyCandidates(b, worklist)
        }
    }
    doUnhoist(cfg, worklist)
}

/**
 * The best heuristic to unhoist a memcpy instruction is to run a scalar analysis and check if `r1`/`r2`/`r3`
 * has more than one scalar value. However, [unhoistMemFunctions] can be run before inlining/slicing.
 * Thus, we prefer to rely on syntactic hints [heuristics] to decide whether a memcpy/memcmp instruction should be
 * unhoisted or not.
 *
 * For now, we only care about `memcpy` and `memcmp`.
 */
private fun findUnhoistMemFunCandidates(
    b: MutableSbfBasicBlock,
    worklist: MutableList<Pair<MutableSbfBasicBlock, Int>>,
    heuristics: UnhoistHeuristics
) {

    val operands = listOf(
        Value.Reg(SbfRegister.R1_ARG),
        Value.Reg(SbfRegister.R2_ARG),
        Value.Reg(SbfRegister.R3_ARG)
    )
    val writtenOperands = mutableSetOf<Value.Reg>()

    for ((i, locInst) in b.getLocatedInstructions().withIndex()) {

        if (i >= heuristics.maxInstructionsBeforeMemcpy) {
            // too many instructions to unhoist so we bail out
            break
        }

        val inst = locInst.inst

        if (heuristics.requireOperandDefinedInPredecessor) {
            writtenOperands.addAll(inst.writeRegister.filter { it in operands })
            if (writtenOperands.containsAll(operands)) {
                break  // All operands written in same block, so we bail out.
            }
        }

        if (inst is SbfInstruction.Call) {
            if (inst.isSaveScratchRegisters() || inst.isRestoreScratchRegisters()) {
                // We don't want to unhoist these instructions, we bail out.
                break
            }

            val metadata = when (inst.name) {
                SolanaFunction.SOL_MEMCPY.syscall.name -> SbfMeta.UNHOISTED_MEMCPY()
                SolanaFunction.SOL_MEMCMP.syscall.name -> SbfMeta.UNHOISTED_MEMCMP()
                else -> null
            }

            metadata?.let {
                b.replaceInstruction(locInst, inst.copy(metaData = inst.metaData + it))
                worklist.add(b to locInst.pos)
            }
        }
    }
}

/**
 * It can unhoist multiple **consecutive** promoted memcpy.
 * We do not unhoist `memcpy_zext` and `memcpy_trunc` although we can if needed.
 * **/
private fun findUnhoistPromotedMemcpyCandidates(
    b: MutableSbfBasicBlock,
    worklist: MutableList<Pair<MutableSbfBasicBlock, Int>>
) {
    var insidePromotedMemcpy = false
    for (locInst in b.getLocatedInstructions()) {
        val inst = locInst.inst
        when {
            isStartPromotedMemcpy(inst) -> {
                insidePromotedMemcpy = true
            }
            isEndPromotedMemcpy(inst) -> {
                insidePromotedMemcpy = false
                // Added metadata to the `CVT_restore_scratch_registers` instruction
                check(inst is SbfInstruction.Call)
                val i = locInst.pos
                b.replaceInstruction(i, inst.copy(metaData = inst.metaData + SbfMeta.UNHOISTED_MEMCPY()))
                worklist.add(b to i)
            }
            insidePromotedMemcpy -> {
                // Skip instructions inside promoted memcpy
            }
            else -> {
                // Stop at first instruction that's not part of a promoted memcpy
                break
            }
        }
    }
}

private fun isStartPromotedMemcpy(inst: SbfInstruction) =
        inst.isSaveScratchRegisters()  &&
        inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null


private fun isEndPromotedMemcpy(inst: SbfInstruction) =
        inst.isRestoreScratchRegisters() &&
        inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null

/** Do the actual program transformation **/
private fun doUnhoist(cfg: MutableSbfCFG, worklist: MutableList<Pair<MutableSbfBasicBlock, Int>>) {
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
