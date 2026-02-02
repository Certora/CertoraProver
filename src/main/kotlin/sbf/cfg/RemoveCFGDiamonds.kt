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
        if (removedBlocks.contains(block.getLabel())) {
            continue
        }

        val lastInstIdx = block.getInstructions().lastIndex
        if (lastInstIdx < 0) {
            // This shouldn't happen but we skip the block anyway
            continue
        }
        val lastInst = block.getInstructions()[lastInstIdx]
        if (lastInst.isTerminator()) {
            if (lastInst is SbfInstruction.Jump.ConditionalJump) {
                val cond = lastInst.cond
                val trueSucc = lastInst.target
                val falseSucc = lastInst.falseTarget
                val metadata = lastInst.metaData
                check(falseSucc != null) {"conditional jump $lastInst without one of the successors"}

                if (!removeDiamondOfThree(cfg, block, cond, trueSucc, falseSucc, metadata, removedBlocks)) {
                    if (!removeDiamondOfThree(cfg, block, cond.negate(), falseSucc, trueSucc, metadata, removedBlocks)) {
                        if (!removeDiamondOfFour(cfg, block, cond, trueSucc, falseSucc, metadata, removedBlocks)) {
                            removeDiamondOfFour(cfg, block, cond.negate(), falseSucc, trueSucc, metadata, removedBlocks)
                        }
                    }
                }
            }
        }

        // Post-optimization: simplify new select instructions
        // Important for other optimizations such as simplifyBools
        simplifySelect(block)
    }

    // Post-optimization: remove dead definitions
    // Important for lowering select into assume
    removeUselessDefinitions(cfg)
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

/**
 * Return the pair (lhs, rhs) if [inst] is of the form `lhs = rhs`. Otherwise, it returns null.
 */
private fun matchAssign(inst: SbfInstruction): Pair<Value.Reg, Value>? {
    if (inst is SbfInstruction.Bin) {
        if (inst.op == BinOp.MOV) {
            return inst.dst to inst.v
        }
    }
    return null
}

/**
 * Return the pair (r, v) if block [label] has exactly this form:
 * ```
 *   assume(...) /* this is optional */
 *   r := v
 *   goto gotoLabel
 * ```
 * Otherwise, it returns null.
 */
private fun matchAssignAndGoto(cfg: SbfCFG, label: Label, gotoLabel: Label): Pair<Value.Reg, Value>? {
    val block = cfg.getBlocks()[label]
    check(block != null) {"matchConstantAssignAndGoto cannot find block $label"}

    val insts =
        when (block.getInstructions().size) {
            2 -> { block.getInstructions() }
            3 -> {
                val assumeInst = block.getInstructions().first()
                if (assumeInst is SbfInstruction.Assume && assumeInst.metaData.getVal(SbfMeta.LOWERED_ASSUME) != null) {
                    block.getInstructions().drop(1)
                } else {
                    null
                }
            }
            else -> { null }
        }?: return null

    if (insts.size == 2) {
        val firstInst = insts.first()
        val secondInst = insts.last()
        if (secondInst.isTerminator()) {
            if (secondInst is SbfInstruction.Jump.UnconditionalJump) {
                if (secondInst.target == gotoLabel) {
                    return matchAssign(firstInst)
                }
            }
        }
    }

    return null
}

/**
 * Return true if block [label] is exactly of this form:
 * ```
 *    assume(...) /* this is optional */
 *    goto gotoLabel
 * ```
 */
private fun matchGoto(cfg: SbfCFG, label: Label, gotoLabel: Label): Boolean {
    val block = cfg.getBlocks()[label]
    check(block != null) {"matchGoto cannot find block $label"}

    val insts =
        when (block.getInstructions().size) {
            1 -> { block.getInstructions() }
            2 -> {
                val assumeInst = block.getInstructions().first()
                if (assumeInst is SbfInstruction.Assume && assumeInst.metaData.getVal(SbfMeta.LOWERED_ASSUME) != null) {
                    block.getInstructions().drop(1)
                } else {
                    null
                }
            }
            else -> { null }
        }?: return false

    if (insts.size == 1) {
        val termInst = insts.first()
        if (termInst is SbfInstruction.Jump.UnconditionalJump) {
            return (termInst.target == gotoLabel)
        }
    }
    return false
}


fun isDiamond(cfg: SbfCFG, l1: Label, l2: Label): Label? {
    val b1 = cfg.getBlock(l1)
    check(b1 != null ) {"$l1 not found as a block"}
    val b2 = cfg.getBlock(l2)
    check(b2 != null ) {"$l2 not found as a block"}

    val i1 = b1.getTerminator()
    val i2 = b2.getTerminator()
    if (i1 is SbfInstruction.Jump.UnconditionalJump) {
        if (i2 is SbfInstruction.Jump.UnconditionalJump) {
            if (i1.target == i2.target) {
                return i1.target
            }
        }
    }
    return null
}

/**
 * @param block: is the header of the diamond
 * @param selectInst: is the select instruction inserted at the end of [block] (before terminator)
 * @param gotoInst: new terminator in [block]
 * @param blocksToBeRemoved: blocks of the diamond that will be removed.
 * @param accBlocksToBeRemoved: blocks marked to be removed so far at the level of the whole CFG.
 */
private fun markDiamondsForRemoval(
    cfg: MutableSbfCFG,
    block: MutableSbfBasicBlock,
    selectInst: SbfInstruction.Select,
    gotoInst: SbfInstruction.Jump.UnconditionalJump,
    blocksToBeRemoved: List<Label>,
    accBlocksToBeRemoved: MutableSet<Label>
) {
    // Add selectInst before last instruction
    val lastInstIdx = block.getInstructions().lastIndex
    check(lastInstIdx >= 0)
    block.add(lastInstIdx, selectInst)
    // Replace the conditional jump with an unconditional jump
    block.replaceInstruction(block.getInstructions().lastIndex, gotoInst)
    // Remove the edge between block and diamonds
    // Note that we don't actually remove diamonds to avoid invalidating blocks while we are iterating it.
    // simplify() will do that later.
    for (l in blocksToBeRemoved) {
        val bb = cfg.getMutableBlock(l)
        check(bb != null) { "removeDiamond cannot find block $l" }
        block.removeSucc(bb)
        accBlocksToBeRemoved.add(l)
    }
}

/**
 * Transform
 * ```
 *  L0:
 *     r := v1
 *     if (c) goto L1 else L2
 *  L2:
 *     r := v2
 *     goto L1
 *  L1: ...
 *  ```
 *  into
 *  ```
 *  L0:
 *     r:= select(cond, v1, v2)
 *     goto L1
 *  ```
 *   [l1] must be the block taken if [cond] is evaluated to true
 **/
private fun removeDiamondOfThree(
    cfg: MutableSbfCFG,
    b0: MutableSbfBasicBlock,
    cond: Condition,
    l1: Label,
    l2: Label,
    metadata: MetaData,
    accBlocksToBeRemoved: MutableSet<Label>
): Boolean {
    matchAssignAndGoto(cfg, l2, l1)?.let { (reg, v2) ->
        val selectInst = SbfInstruction.Select(reg, cond, reg, v2)
        val gotoInst = SbfInstruction.Jump.UnconditionalJump(l1, metadata)
        // l2 is not physically removed but logically it's
        markDiamondsForRemoval(
            cfg,
            b0,
            selectInst,
            gotoInst,
            listOf(l2),
            accBlocksToBeRemoved
        )
        return true
    }
    return false
}

/**
 * Transform
 * ```
 *  L0:
 *     r := v1
 *     if (c) goto L1 else L2
 *  L1:
 *     r := v2
 *     goto L3
 *  L2:
 *     goto L3
 *  L3: ...
 *  ```
 *  into
 *  ```
 *   L0:
 *     r:= select(cond, v2, v1)
 *     goto L3
 *  ```
 *  [l1] must be the block taken if [cond] is evaluated to true
 **/
private fun removeDiamondOfFour(
    cfg: MutableSbfCFG,
    block: MutableSbfBasicBlock,
    cond: Condition,
    l1: Label,
    l2: Label,
    metadata: MetaData,
    accBlocksToBeRemoved: MutableSet<Label>
): Boolean {
    val l3 = isDiamond(cfg, l1, l2) ?: return false
    if (!matchGoto(cfg, l2, l3)) {
        return false
    }
    matchAssignAndGoto(cfg, l1, l3)?.let { (reg, v2) ->
        val selectInst =
            SbfInstruction.Select(reg, cond, v2, reg)
        val gotoInst =
            SbfInstruction.Jump.UnconditionalJump(l3, metadata)
        // l1 and l2 are not physically removed, but logically they are
        markDiamondsForRemoval(
            cfg,
            block,
            selectInst,
            gotoInst,
            listOf(l1, l2),
            accBlocksToBeRemoved
        )
        val b3 = cfg.getMutableBlock(l3)
        check(b3 != null)
        block.addSucc(b3)
        return true
    }
    return false
}
