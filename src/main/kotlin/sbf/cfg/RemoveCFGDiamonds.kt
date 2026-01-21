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
    }
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

/**
 *  Return the definition of [reg] in [block] if:
 *  1) the definition is in [block],
 *  2) the set of defined variables is a singleton
 *  Otherwise, it returns null.
 */
private fun findSingleDefinition(block: SbfBasicBlock, reg: Value.Reg): LocatedSbfInstruction? {
    val defLocInst = findDefinitionInterBlock(block, reg) ?: return null
    if (defLocInst.label != block.getLabel()) {
        return null
    }
    if (defLocInst.inst.writeRegister.singleOrNull() == null) {
        return null
    }
    return defLocInst
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
private fun markDiamondsForRemoval(cfg: MutableSbfCFG,
                                   block: MutableSbfBasicBlock,
                                   selectInst: SbfInstruction.Select,
                                   gotoInst: SbfInstruction.Jump.UnconditionalJump,
                                   blocksToBeRemoved: List<Label>,
                                   accBlocksToBeRemoved: MutableSet<Label>) {
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

/** [l1] must be the block taken if [cond] is evaluated to true **/
private fun removeDiamondOfThree(cfg: MutableSbfCFG,
                         block: MutableSbfBasicBlock, cond: Condition, l1: Label, l2: Label,
                         metadata: MetaData,
                         accBlocksToBeRemoved: MutableSet<Label>): Boolean {
    val done = matchAssignAndGoto(cfg, l2, l1)?.let { (reg, v2) ->
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
         */
        val defLocInst = findSingleDefinition(block, reg)
        if (defLocInst != null) {
            val defInst = defLocInst.inst
            val v1 = if (defInst is SbfInstruction.Bin && defInst.op == BinOp.MOV &&
                         !isUsed(block, reg, defLocInst.pos, block.getInstructions().size)) {
                // small optimization: we can use the rhs of the assignment if
                //    (1) we remove the assignment (see test1) and
                //    (2) the lhs of the assignment is not used in the rest of the block (see test4).
                block.removeAt(defLocInst.pos)
                defInst.v
            } else {
                check(defInst.writeRegister.size == 1)
                defInst.writeRegister.single()
            }
            val selectInst = SbfInstruction.Select(reg, cond, v1, v2)
            val gotoInst = SbfInstruction.Jump.UnconditionalJump(l1, metadata)
            // l2 is not physically removed but logically it's
            markDiamondsForRemoval(cfg, block, selectInst, gotoInst, listOf(l2), accBlocksToBeRemoved)
            true
        } else {
            false
        }
    }
    return done == true
}

/** [l1] must be the block taken if [cond] is evaluated to true **/
private fun removeDiamondOfFour(cfg: MutableSbfCFG,
                                block: MutableSbfBasicBlock, cond: Condition, l1: Label, l2: Label,
                                metadata: MetaData,
                                accBlocksToBeRemoved: MutableSet<Label>): Boolean {
    val l3 = isDiamond(cfg, l1, l2)
    return if (l3 != null) {
        (matchGoto(cfg, l2, l3) && matchAssignAndGoto(cfg, l1, l3)?.let { (reg, v2) ->
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
             */
            val defLocInst = findSingleDefinition(block, reg)
            if (defLocInst != null) {
                val defInst = defLocInst.inst
                val v1 = if (defInst is SbfInstruction.Bin && defInst.op == BinOp.MOV &&
                             !isUsed(block, reg, defLocInst.pos, block.getInstructions().size)) {
                    // small optimization: we can use the rhs of the assignment if
                    //    (1) we remove the assignment (see test1) and
                    //    (2) the lhs of the assignment is not used in the rest of the block (see test4).
                    block.removeAt(defLocInst.pos)
                    defInst.v
                } else {
                    check(defInst.writeRegister.size == 1)
                    defInst.writeRegister.single()
                }
                val selectInst =
                    SbfInstruction.Select(reg, cond, v2, v1)
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
                true
            } else {
                false
            }
        } == true)
    } else {
        false
    }
}
