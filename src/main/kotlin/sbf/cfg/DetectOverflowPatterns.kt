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

/**
 * We search for code patterns that correspond, for instance, to Rust `checked_add` or `saturating_add`:
 *  ```
 *   z = x + y
 *   if x > z then ...
 * ```
 *
 * If the pattern is found, then we mark the addition and the condition instructions with the
 * following metadata:
 *
 *  ```
 *   z = x + y /*safe_math*/
  *  if x > z  /*promoted add.overflow check: z >= 2^64*/ then ...
 * ```
 *
 * The metadata is only used during TAC encoding.
 */
fun detectOverflowPatterns(cfg: MutableSbfCFG) {
    // normalize select to make simpler overflow detection
    cfg.getMutableBlocks().values.forEach { bb ->
        bb.getLocatedInstructions().forEach { locInst ->
            normalizeSelect(bb, locInst)
        }
    }

    cfg.getMutableBlocks().values.forEach { bb ->
        bb.getLocatedInstructions().forEach { locInst ->
            detectAddU64OverflowPattern(cfg, locInst)?.let {
                annotateOverflowPattern(it)
            }
            detectAddU128ByOneOverflowPattern(cfg, locInst)?.let {
                annotateOverflowPattern(it)
            }
        }
    }
}

private data class OverflowPattern(
    /**
     * Basic block where the overflow pattern is found.
     */
    val basicBlock: MutableSbfBasicBlock,
    /**
     * Instructions to be annotated with [SbfMeta.SAFE_MATH]
     **/
    val safeMathInsts: List<LocatedSbfInstruction>,
    /**
     * Instruction to be annotated with [SbfMeta.PROMOTED_OVERFLOW_CHECK]
     * This instruction is either a select or jump.
     **/
    val overflowCheckInst: LocatedSbfInstruction,
    /**
     * Variable that is checked for overflow in [overflowCheckInst]
     */
    val overflowCheckVar: Value.Reg
) {
    init {
        check(safeMathInsts.all { it.label == basicBlock.getLabel()})
        check(overflowCheckInst.label == basicBlock.getLabel())
        check(overflowCheckInst.inst is SbfInstruction.Select || overflowCheckInst.inst is SbfInstruction.Jump.ConditionalJump)
    }
}

/**
 *  It searches for one of these two patterns:
 *
 *  ```
 *  r3 = r4
 *  r3 = r3 + r2
 *  r3 = r3 - 1 // optional
 *  r2 = select(r4 ugt r3, 1, 0)
 *  ```
 *  or
 *  ```
 *  r3 = r4
 *  r3 = r3 + r2
 *  r3 = r3 - 1 // optional
 *  if (r4 ugt r3)
 *  ```
 *
 *  For simplicity, all instructions must be in the same block.
 */
private fun detectAddU64OverflowPattern(cfg: MutableSbfCFG, locInst: LocatedSbfInstruction): OverflowPattern? {

    // 1. We start from the instruction r3 = r3 + r2 in the above example
    val addInst = locInst.inst
    if (addInst !is SbfInstruction.Bin || addInst.op != BinOp.ADD) {
        return null
    }

    val label = locInst.label
    val bb = cfg.getMutableBlock(label)
    check(bb != null)

    // We try to match assignment (r3 = r4 in the above example)
    val assignLocInst = findDefinitionIntraBlock(bb, addInst.dst, locInst.pos) ?: return null
    val assignInst = assignLocInst.inst
    if (assignInst !is SbfInstruction.Bin || assignInst.op != BinOp.MOV) {
        return null
    }

    val op1 = assignInst.v
    if (op1 !is Value.Reg) {
        return null
    }

    val op2 = addInst.v
    val safeMathInst = mutableListOf(locInst)
    var (overflowVar, pos) = addInst.dst to locInst.pos
    var nextUseLocInst = findNextUseIntraBlock(bb, overflowVar, pos+1)

    // 2. We try to match (optionally) r3 = r3 - 1 in the above example
    // Note that we match any instruction r = r + k or r = r - k where k is an immediate value.
    if (nextUseLocInst != null) {
        val nextUseInst = nextUseLocInst.inst
        if (nextUseInst is SbfInstruction.Bin &&
            (nextUseInst.op == BinOp.SUB || nextUseInst.op == BinOp.ADD) &&
            (nextUseInst.v is Value.Imm)) {

            overflowVar = nextUseInst.dst
            pos = nextUseLocInst.pos
            safeMathInst.add(nextUseLocInst)
            nextUseLocInst = findNextUseIntraBlock(bb, overflowVar, pos+1)
        }
    }

    // 3. Finally, we try to match the `select` or the `jump` instruction in the above example
    val selectOrJumpLocInst = nextUseLocInst ?: return null

    return when (val selectOrJumpInst = selectOrJumpLocInst.inst) {
            is SbfInstruction.Select -> {
                if (isAddOverflowCondition(selectOrJumpInst.cond, overflowVar, op1, op2,
                        bb, assignLocInst.pos, pos, selectOrJumpLocInst.pos)) {
                    OverflowPattern(bb, safeMathInst, selectOrJumpLocInst, overflowVar)
                } else {
                    null
                }
            }
            is  SbfInstruction.Jump.ConditionalJump -> {
                if (isAddOverflowCondition(selectOrJumpInst.cond, overflowVar, op1, op2,
                        bb, assignLocInst.pos, pos, selectOrJumpLocInst.pos)) {
                    OverflowPattern(bb, safeMathInst, selectOrJumpLocInst, overflowVar)
                } else {
                    null
                }
            }
            else -> {
                null
            }
        }
}

/**
 *  It searches for the pattern created from `x.checked_add(1)` where `x` is u128:
 *
 *  - `r6` is the low 64-bits of `x`
 *  - `r7` is the high 64-bits of `x`
 *  - `r1` is the carry of `r6+1`
 *
 *  ```
 *  r6 = r6 + 1   // add one to the low bits
 *  r1 = select(r6 == 0, 1, 0) // compute the carry
 *  r7 = r7 + r1  // add the carry to the high bits
 *  ...
 *  r2 = r6
 *  r2 = r2 or r7
 *  assume(r2 != 0)   // this test if `x.checked_add(1) != 0` which means no overflow
 * ```
 *
 * Again, all instructions must be in the same block.
 */
private fun detectAddU128ByOneOverflowPattern(cfg: MutableSbfCFG, locInst: LocatedSbfInstruction): OverflowPattern? {
    // 1. We start from the instruction r6 = r6 + 1 in the above example
    val addOneInst = locInst.inst
    if (addOneInst !is SbfInstruction.Bin || addOneInst.op != BinOp.ADD || !isOne(addOneInst.v)) {
        return null
    }

    val label = locInst.label
    val bb = cfg.getMutableBlock(label)
    check(bb != null)

    val safeMathInst = mutableListOf(locInst)
    val (overflowVar, pos) = addOneInst.dst to locInst.pos

    // 2. We try to match the `select` in the above example
    var nextLocInst = findNextUseIntraBlock(bb, overflowVar, pos+1)
    if (nextLocInst == null) {
        return null
    }

    val selectLocInst = nextLocInst
    val overflowPattern = when (val selectInst = selectLocInst.inst) {
        is SbfInstruction.Select -> {
            if (isEqualZeroCondition(selectInst.cond, overflowVar)) {
                nextLocInst = findNextUseIntraBlock(bb, selectInst.dst, selectLocInst.pos+1)
                OverflowPattern(bb, safeMathInst, selectLocInst, overflowVar)
            } else {
                null
            }
        }
        else -> null
    } ?: return null


    // 3. We try to match the next use of the select instruction with another add instruction
    val addInst = nextLocInst.inst
    return if (addInst is SbfInstruction.Bin  && addInst.op == BinOp.ADD) {
        overflowPattern
    } else {
        null
    }
}

private fun annotateOverflowPattern(overflowPattern: OverflowPattern) {
    val safeMathInsts = overflowPattern.safeMathInsts
    val bb = overflowPattern.basicBlock
    val overflowInst = overflowPattern.overflowCheckInst
    val overflowVar = overflowPattern.overflowCheckVar

    safeMathInsts.forEach { addSafeMathAnnot(bb, it) }
    addOverflowAnnot(bb, overflowInst, overflowVar)
}

/**
 * This function returns true if [cond] does an overflow check:
 *
 * ```
 * z = x + y
 * if (x > z) or (z < x) ...
 * ```
 *
 * ```
 * z = x + y
 * if (y > z) or (z < y) ...
 * ```
 *
 * In addition, we need to make sure that [x], [y], and [z] are not redefined before the overflow condition.
 * [z] cannot be redefined otherwise `isAddOverflowCondition` wouldn't be called.
 * We do check for [x] and [y].
 */
private fun isAddOverflowCondition(cond: Condition, z: Value.Reg, x: Value.Reg, y: Value,
                                   bb: SbfBasicBlock, xPos: Int, yPos: Int, condPos: Int): Boolean {


    fun isNotRedefined(reg: Value.Reg, from:Int) = !isRedefined(bb, reg, from, condPos)
    // We don't use directly equals from Condition because Condition has some other optional parameters
    fun matches(condX: Condition, condY: Condition) =
            (condX.op == condY.op && condX.left == condY.left && condX.right == condY.right)

    return if ((matches(cond, Condition(CondOp.GT, x, z)) ||
                matches(cond, Condition(CondOp.LT, z, x))) &&
               isNotRedefined(x, xPos)) {
        true
    } else {
            y is Value.Reg && (matches(cond, Condition(CondOp.GT, y, z)) ||
                               matches(cond, Condition(CondOp.LT, z, y))) &&
            isNotRedefined(y, yPos)
    }
}

private fun isOne(v: Value) = v is Value.Imm && v.v == 1UL
private fun isZero(v: Value) = v is Value.Imm && v.v == 0UL

/** Return true iff [cond] is [x] `== 0` **/
private fun isEqualZeroCondition(cond: Condition, x: Value.Reg):Boolean {
    if (cond.op != CondOp.EQ) {
        return false
    }
    val leftOp = cond.left
    val rightOp = cond.right
    if (leftOp == x) {
        return isZero(rightOp)
    }
    if (rightOp == x) {
        return isZero(leftOp)
    }
    return false
}

private fun addSafeMathAnnot(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Bin && (inst.op == BinOp.ADD || inst.op == BinOp.SUB)) {
        val newMetaData = inst.metaData.plus(Pair(SbfMeta.SAFE_MATH, ""))
        bb.replaceInstruction(locInst.pos, inst.copy(metaData = newMetaData))
    }
}

private fun addOverflowAnnot(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction, overflowVar: Value.Reg) {
    when (val inst = locInst.inst) {
        is SbfInstruction.Select -> {
            val newMetaData = inst.metaData
                .plus(
                    Pair(
                        SbfMeta.PROMOTED_OVERFLOW_CHECK,
                        Condition(CondOp.GT, overflowVar, Value.Imm(ULong.MAX_VALUE))
                    )
                )
            bb.replaceInstruction(locInst.pos, inst.copy(metaData = newMetaData))
        }
        is SbfInstruction.Jump.ConditionalJump -> {
            val newMetaData = inst.metaData
                .plus(
                    Pair(
                        SbfMeta.PROMOTED_OVERFLOW_CHECK,
                        Condition(CondOp.GT, overflowVar, Value.Imm(ULong.MAX_VALUE))
                    )
                )
            bb.replaceInstruction(locInst.pos, inst.copy(metaData = newMetaData))

        }
        else -> {}
    }
}

/** Replace `select(cond, 0, 1)` with `select(neg(cond), 1, 0)` **/
private fun normalizeSelect(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Select) {
        val trueV = inst.trueVal
        val falseV = inst.falseVal
        if (trueV is Value.Imm && falseV is Value.Imm) {
            if (trueV.v == 0UL && falseV.v == 1UL) {
                val normSelect = inst.copy(cond = inst.cond.negate(), trueVal = falseV, falseVal = trueV)
                bb.replaceInstruction(locInst.pos, normSelect)
            }
        }
    }
}

private fun findDefinitionIntraBlock(bb: SbfBasicBlock, x: Value.Reg, pos: Int): LocatedSbfInstruction? {
    val locInst = findDefinitionInterBlock(bb, x, pos) ?: return null
    return if (bb.getLabel() == locInst.label) {
        locInst
    } else {
        null
    }
}

private fun findNextUseIntraBlock(bb: SbfBasicBlock, x: Value.Reg, pos: Int): LocatedSbfInstruction? {
    val locInst = getNextUseInterBlock(bb, pos, x) ?: return null
    return if (bb.getLabel() == locInst.label) {
        locInst
    } else {
        null
    }
}
