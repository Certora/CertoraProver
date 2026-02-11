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

import sbf.disassembler.*
import datastructures.stdcollections.*

/**
 * Replace CFG diamonds with select instructions.
 * The goal is to reduce the number of basic blocks.
 */
fun removeCFGDiamonds(cfg: MutableSbfCFG) {
    val removedBlocks = mutableSetOf<Label>()
    for (block in cfg.getMutableBlocks().values) {
        if (!removedBlocks.contains(block.getLabel())) {
            removeCFGDiamonds(block, cfg, removedBlocks)
        }
    }

    // Post-optimization: remove dead definitions
    // Important for lowering select into assume
    removeUselessDefinitions(cfg)
}

private fun removeCFGDiamonds(
    block: MutableSbfBasicBlock,
    cfg: MutableSbfCFG,
    removedBlocks: MutableSet<Label>,
    numMaxIterations: Int = 15
) {
    var change = true
    var numIterations = 0
    while (change && numIterations < numMaxIterations) {
        change = false
        numIterations++

        val lastInstIdx = block.getInstructions().lastIndex
        check(lastInstIdx >= 0)

        val lastInst = block.getInstructions()[lastInstIdx]
        if (lastInst.isTerminator() && lastInst is SbfInstruction.Jump.ConditionalJump) {
            val cond = lastInst.cond
            val trueSucc = lastInst.target
            val falseSucc = lastInst.falseTarget
            val metadata = lastInst.metaData
            checkNotNull(falseSucc)
            change =
                    processDiamondOfThree(cfg, block, cond, trueSucc, falseSucc, metadata, removedBlocks) ||
                    processDiamondOfThree(cfg, block, cond.negate(), falseSucc, trueSucc, metadata, removedBlocks) ||
                    processDiamondOfFour(cfg, block, cond, trueSucc, falseSucc, metadata, removedBlocks) ||
                    processDiamondOfFour(cfg, block, cond.negate(), falseSucc, trueSucc, metadata, removedBlocks)
        }
    }

    // Post-optimization: simplify new select instructions
    // Important for other optimizations such as simplifyBools
    simplifySelect(block)
}

/**
 *  Replace true and false values from a select instruction with immediate values if possible.
 *  This transformation does not use intentionally the scalar analysis, so it is limited in scope.
 *
 *  This transformation is important for other transformations such as `simplifyBool`.
 */
private fun simplifySelect(b: MutableSbfBasicBlock) {
    for (locInst in b.getLocatedInstructions()) {
        when(val inst = locInst.inst) {
            is SbfInstruction.Select -> {
                val pos = locInst.pos
                val newTrueVal = (inst.trueVal as? Value.Reg)
                    ?.let { getDefinitionRHS(it, b, pos) as? Value.Imm }
                    ?: inst.trueVal

                val newFalseVal = (inst.falseVal as? Value.Reg)
                    ?.let { getDefinitionRHS(it, b, pos) as? Value.Imm }
                    ?: inst.falseVal

                if (newTrueVal != inst.trueVal || newFalseVal != inst.falseVal) {
                    b.replaceInstruction(pos, inst.copy(
                        trueVal = newTrueVal,
                        falseVal = newFalseVal
                    ))
                }
            }
            else -> {}
        }
    }
}

/**
 *  Best effort to return the RHS of the definition of [reg] if the definition is an assignment.
 *  The function starts from position [start] in block [b].
 **/
private fun getDefinitionRHS(reg: Value.Reg, b: SbfBasicBlock, start: Int): Value? {
    val defLocInst = findDefinitionInterBlock(b, reg, start) ?: return null
    val defInst = defLocInst.inst as? SbfInstruction.Bin ?: return null
    if (defInst.op != BinOp.MOV) {
        return null
    }
    return defInst.v
}

private fun SbfInstruction.isLoweredAssume(): Boolean =
    this is SbfInstruction.Assume && this.metaData.getVal(SbfMeta.LOWERED_ASSUME) != null

private data class Assignment(
    val locInst: LocatedSbfInstruction,
    val lhs: Value.Reg,
    val rhs: Value
) {
    fun pos(): Int = locInst.pos
}

/**
 * Returns the first assignment from block [label] if it matches this pattern:
 * ```
 *   assume(...)  /* zero or one assume */
 *   r1 := v1     /* one or more assignments */
 *   r2 := v2
 *   ...
 *   goto gotoLabel
 * ```
 * Returns null if the block doesn't match this pattern or if the goto target
 * doesn't match [gotoLabel].
 */
private fun matchAssignAndGoto(cfg: SbfCFG, label: Label, gotoLabel: Label): Assignment? {
    val block = checkNotNull(cfg.getBlocks()[label])
    val instructions = block.getLocatedInstructions()

    // Verify block terminates with goto to gotoLabel
    val termInst = instructions.last().inst
    if (termInst !is SbfInstruction.Jump.UnconditionalJump || termInst.target != gotoLabel) {
        return null
    }

    // Skip optional assume instruction
    val startIdx = if (instructions.first().inst.isLoweredAssume()) { 1 } else { 0 }

    // Extract instructions between optional assume and terminator
    val middleInsts = instructions.subList(startIdx, instructions.size - 1)

    // Verify all middle instructions are assignments
    if (middleInsts.isEmpty() || !middleInsts.all { it.inst is SbfInstruction.Bin && it.inst.op == BinOp.MOV }) {
        return null
    }

    // Return the first assignment
    return middleInsts.first().let { locInst ->
        val inst = locInst.inst as SbfInstruction.Bin
        Assignment(locInst, inst.dst, inst.v)
    }
}

/**
 * Return true if block [label] is exactly of this form:
 * ```
 *    assume(...) /* zero or one assume */
 *    goto gotoLabel
 * ```
 */
private fun matchGoto(cfg: SbfCFG, label: Label, gotoLabel: Label): Boolean {
    val block = checkNotNull(cfg.getBlocks()[label])
    val instructions = block.getInstructions()

    // Skip Assume
    val startIdx = if (instructions[0].isLoweredAssume()) { 1 } else { 0 }

    val termInst = instructions.drop(startIdx).singleOrNull() ?: return false
    return termInst is SbfInstruction.Jump.UnconditionalJump &&
           termInst.target == gotoLabel
}


private fun isDiamond(cfg: SbfCFG, l1: Label, l2: Label): Label? {
    val b1 = checkNotNull(cfg.getBlock(l1))
    val b2 = checkNotNull(cfg.getBlock(l2))

    val i1 = b1.getTerminator()
    val i2 = b2.getTerminator()
    if (i1 is SbfInstruction.Jump.UnconditionalJump && i2 is SbfInstruction.Jump.UnconditionalJump) {
        if (i1.target == i2.target) {
            return i1.target
        }
    }
    return null
}

/**
 * Transforms a diamond control flow pattern into a select instruction.
 *
 * Before:
 * ```
 *  entry:
 *     r := v1
 *     if (c) goto exitL else falseL
 *  falseL:
 *     r := v2
 *     goto exitL
 *  exitL: ...
 * ```
 *
 * After (when `falseL` contains only the assignment):
 * ```
 *  entry:
 *     r := select(c, v1, v2)
 *     goto exitL
 * ```
 *
 * After (when `falseL` contains additional assignments):
 * ```
 *  entry:
 *     r := select(c, v1, v2)
 *     if (c) goto exitL else falseL
 *  falseL:
 *     // other assignments remain
 *     goto exitL
 *  exitL: ...
 * ```
 *
 * The false block is only removed if it becomes empty after removing the assignment.
 * If the false block contains other assignments, then it is preserved and only the assignment is removed.
 *
 * @param cfg The control flow graph to transform
 * @param entry The entry block containing the conditional branch
 * @param cond The condition being evaluated
 * @param exitL The merge point label where both branches are merged
 * @param falseL The label of the block taken when [cond] evaluates to false
 * @param metadata Metadata to attach to generated instructions
 * @param accBlocksToBeRemoved Accumulator for blocks that can be safely removed
 * @return true if the transformation was applied, false otherwise
 */
private fun processDiamondOfThree(
    cfg: MutableSbfCFG,
    entry: MutableSbfBasicBlock,
    cond: Condition,
    exitL: Label,
    falseL: Label,
    metadata: MetaData,
    accBlocksToBeRemoved: MutableSet<Label>
): Boolean {
    // Process one assignment at a time.
    //
    // We could process all the assignments together.
    // However, when removing an assignment from falseL, all subsequent instruction positions shift.
    // By processing assignments one at a time, the caller can invoke this function
    // repeatedly until all assignments in falseL are converted to select instructions.

    val assignment = matchAssignAndGoto(cfg, falseL, exitL) ?: return false

    val falseBlock = checkNotNull(cfg.getMutableBlock(falseL))

    // false block can be removed if there is no more instructions between `assignment` and the terminator
    val isFalseBlockRedundant = (assignment.pos() == falseBlock.getInstructions().size - 2)

    // Add select instruction in entry block
    val lhs = assignment.lhs
    entry.insertAtEnd(SbfInstruction.Select(lhs, cond, lhs, assignment.rhs))

    // Remove assignment from false block
    falseBlock.removeAt(assignment.pos())

    if (isFalseBlockRedundant) {
        // False block is now empty, so eliminate it entirely
        eliminateFalseBlock(entry, falseBlock, falseL, exitL, accBlocksToBeRemoved, metadata)
    }

    // Otherwise, false block still has other instructions and remains in the CFG
    return true
}

/**
 * Eliminates a redundant false block by redirecting control flow directly to the exit.
 */
private fun eliminateFalseBlock(
    entry: MutableSbfBasicBlock,
    falseBlock: MutableSbfBasicBlock,
    falseL: Label,
    exitL: Label,
    accBlocksToBeRemoved: MutableSet<Label>,
    metadata: MetaData
) {
    // Remove the edge from entry to the false block
    entry.removeSucc(falseBlock)
    accBlocksToBeRemoved.add(falseL)

    // Replace conditional jump with unconditional jump to exit
    entry.replaceInstruction(
        entry.getInstructions().lastIndex,
        SbfInstruction.Jump.UnconditionalJump(exitL, metadata)
    )
    // Note: Edge from entry to exit already exists, so we don't need to add it
}

/**
 * Transforms a diamond control flow pattern with an empty true branch into a select instruction.
 *
 * Before:
 * ```
 *  entry:
 *     r := v1
 *     if (c) goto trueL else falseL
 *  trueL:
 *     goto exitL
 *  falseL:
 *     r := v2
 *     goto exitL
 *  exitL: ...
 * ```
 *
 * After (when falseL contains only the assignment):
 * ```
 *  entry:
 *     r := select(c, v1, v2)
 *     goto exitL
 * ```
 *
 * After (when falseL contains additional assignments):
 * ```
 *  entry:
 *     r := select(c, v1, v2)
 *     if (c) goto trueL else falseL
 *  trueL:
 *     goto exitL
 *  falseL:
 *     // other assignments remain
 *     goto exitL
 *  exitL: ...
 * ```
 *
 * Both intermediate blocks (trueL and falseL) are only removed if falseL becomes empty
 * after removing the assignment. If falseL contains other assignments then both blocks are preserved.
 *
 * @param cfg The control flow graph to transform
 * @param entry The entry block containing the conditional branch
 * @param cond The condition being evaluated
 * @param trueL The label of the block taken when [cond] evaluates to true
 * @param falseL The label of the block taken when [cond] evaluates to false
 * @param metadata Metadata to attach to generated instructions
 * @param accBlocksToBeRemoved Accumulator for blocks that can be safely removed
 * @return true if the transformation was applied, false otherwise
 */
private fun processDiamondOfFour(
    cfg: MutableSbfCFG,
    entry: MutableSbfBasicBlock,
    cond: Condition,
    trueL: Label,
    falseL: Label,
    metadata: MetaData,
    accBlocksToBeRemoved: MutableSet<Label>
): Boolean {
    // See comments in processDiamondOfThree to understand why we process one assignment at the time

    val exitL = isDiamond(cfg, trueL, falseL) ?: return false

    if (!matchGoto(cfg, trueL, exitL)) {
        return false
    }

    val assignment = matchAssignAndGoto(cfg, falseL, exitL) ?: return false

    val falseBlock = checkNotNull(cfg.getMutableBlock(falseL))

    // Intermediate blocks can be removed if there is no more instructions between `assignment`
    // and the terminator
    val canRemoveIntermediateBlocks = (assignment.pos() == falseBlock.getInstructions().size - 2)

    // Add select instruction in entry
    val lhs = assignment.lhs
    entry.insertAtEnd(SbfInstruction.Select(lhs, cond, lhs, assignment.rhs))

    // Remove assignment from false block
    falseBlock.removeAt(assignment.pos())

    if (canRemoveIntermediateBlocks) {
        // Both intermediate blocks are now redundant, so eliminate them
        eliminateIntermediateBlocks(cfg, entry, trueL, falseL, exitL, accBlocksToBeRemoved, metadata)
    }
    return true
}

/**
 * Eliminates redundant intermediate blocks by redirecting control flow directly to the exit.
 *
 * Note: Blocks are marked for removal but not actually deleted to avoid invalidating
 * blocks during iteration. The `simplify` pass will perform the actual deletion later.
 */
private fun eliminateIntermediateBlocks(
    cfg: MutableSbfCFG,
    entry: MutableSbfBasicBlock,
    trueL: Label,
    falseL: Label,
    exitL: Label,
    accBlocksToBeRemoved: MutableSet<Label>,
    metadata: MetaData
) {
    // Remove edges from entry to intermediate blocks and mark them for removal
    for (label in listOf(trueL, falseL)) {
        val block = checkNotNull(cfg.getMutableBlock(label))
        entry.removeSucc(block)
        accBlocksToBeRemoved.add(label)
    }

    // Replace conditional jump with unconditional jump to exit
    entry.replaceInstruction(
        entry.getInstructions().lastIndex,
        SbfInstruction.Jump.UnconditionalJump(exitL, metadata)
    )

    // Add edge from entry to exit
    val exitBlock = checkNotNull(cfg.getMutableBlock(exitL))
    entry.addSucc(exitBlock)
}

private fun MutableSbfBasicBlock.insertAtEnd(inst: SbfInstruction) {
    val lastInstIdx = this.getInstructions().lastIndex
    check(lastInstIdx >= 0)
    this.add(lastInstIdx, inst)
}
