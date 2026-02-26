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

import datastructures.stdcollections.*
import kotlin.collections.removeLast

fun simplifyBools(cfg: MutableSbfCFG) {
    applyTransform(cfg) { replaceOrWithAssume(it) }
}

/** Apply a generic [transform] on each block of [cfg] until no change **/
private fun applyTransform(cfg: MutableSbfCFG, transform: (MutableSbfBasicBlock) -> Boolean) {
    for (b in cfg.getMutableBlocks().values) {
        var change = true
        while (change) {
            change = transform(b)
        }
    }
}

/**
 * This transformation helps other transformations such as `MarkAddWithOverflow`.
 *
 * Replace:
 * ```
 *  r1 := ...
 *  r2 := ...
 *  r2 := r2 or r1
 *  assume(r2 == 0)
 * ```
 * with
 * ```
 *  r1 := ...
 *  r2 := ...
 *  assume(r1 == 0)
 *  assume(r2 == 0)
 * ```
 * and
 * ```
 *  r1 := select(*,0,1)
 *  r2 := select(*,1,0)
 *  r2 := r2 or r1
 *  assume(r2 != 1)
 * ```
 *  with
 *  ```
 *  r1 := select(*,0,1)
 *  r2 := select(*,1,0)
 *  assume(r1 == 0)
 *  assume(r2 == 0)
 * ```
 *
 * Note that this transformation is only applied once per basic block.
 * This is because otherwise it gets complicated to keep adding/removing instructions while iterating over them.
 * Return true iff the transformation is applied.
 */
private fun replaceOrWithAssume(b: MutableSbfBasicBlock): Boolean {
    for (locInst in b.getLocatedInstructions().asReversed()) {
        val inst = locInst.inst
        if (inst is SbfInstruction.Bin && inst.op == BinOp.OR && inst.v is Value.Reg) {
            val left = inst.dst
            val right = inst.v as Value.Reg
            val nextUseLocInst = getNextIntraBlockUse(b, locInst) ?: continue
            val nextUse = nextUseLocInst.inst
            if (nextUse is SbfInstruction.Assume) {
                if (isEqualToZero(nextUse.cond)) /* left == 0 */ {
                    val newAssume = SbfInstruction.Assume(Condition(CondOp.EQ, right, Value.Imm(0UL)),
                                                          inst.metaData.plus(SbfMeta.LOWERED_OR()))
                    // replace the `or` instruction with `assume(right == 0)`
                    b.replaceInstruction(locInst.pos, newAssume)
                    return true
                }
                if (isNotEqualToOne(nextUse.cond) /* left != 1 */ &&
                    isBoolean(locInst, b, left) &&
                    isBoolean(locInst, b, right)) {
                    // since left and right are boolean `assume(left != 1)` is equivalent to `assume(left == 0)`
                    val newAssume1 = SbfInstruction.Assume(Condition(CondOp.EQ, left, Value.Imm(0UL)),
                                                            inst.metaData.plus(SbfMeta.LOWERED_OR()))
                    val newAssume2 = SbfInstruction.Assume(Condition(CondOp.EQ, right, Value.Imm(0UL)),
                                                            inst.metaData.plus(SbfMeta.LOWERED_OR()))
                    // replace `assume(left != 1)` with `assume(left == 0)`
                    b.replaceInstruction(nextUseLocInst.pos, newAssume1)
                    // replace the `or` instruction with `assume(right == 0)`
                    b.replaceInstruction(locInst.pos, newAssume2)
                    return true
                }
            }
        }
    }
    return false
}

/**
 * We cannot ask the scalar analysis because it doesn't keep track of intervals.
 * We traverse transitively (but only within [b]) the def chains of [locInst] operands until all definitions
 * are either select instructions whose operands are 0 or 1 or assignments whose rhs is 0 or 1.
 */
private fun isBoolean(locInst: LocatedSbfInstruction, b: SbfBasicBlock, reg: Value.Reg): Boolean {
    val nextDef = findDefinitionIntraBlock(b, reg, locInst.pos) ?: return false
    val worklist = mutableListOf(nextDef)
    while (worklist.isNotEmpty()) {
        val curLocInst = worklist.removeLast()
        when(val inst = curLocInst.inst) {
            is SbfInstruction.Select -> {
                val tt = inst.trueVal
                val ff = inst.falseVal
                if (!(isZeroOrOne(tt) && isZeroOrOne(ff))) {
                    return false
                }
            }
            is SbfInstruction.Bin -> {
                if (inst.op == BinOp.AND || inst.op == BinOp.OR || inst.op == BinOp.XOR || inst.op == BinOp.MOV) {
                    if (inst.op != BinOp.MOV) {
                        val left = inst.dst
                        val leftDef = findDefinitionIntraBlock(b, left, curLocInst.pos) ?: return false
                        worklist.add(leftDef)
                    }
                    when (val right = inst.v) {
                        is Value.Imm -> {
                            if (!isZeroOrOne(right)) {
                                return false
                            }
                        }
                        is Value.Reg -> {
                            val rightDef = findDefinitionIntraBlock(b, right, curLocInst.pos) ?: return false
                            worklist.add(rightDef)
                        }
                    }
                } else {
                    return false
                }
            }
            else -> {
                return false
            }
        }
    }
    return true
}


private fun isZero(x: Value) = x is Value.Imm && x.v == 0UL
private fun isOne(x: Value) = x is Value.Imm && x.v == 1UL
private fun isZeroOrOne(x: Value) = x is Value.Imm && (x.v == 0UL || x.v == 1UL)
private fun isEqualToZero(cond: Condition) = cond.op == CondOp.EQ && isZero(cond.right)
private fun isNotEqualToOne(cond: Condition) = cond.op == CondOp.NE && isOne(cond.right)

/**
 * Return the next use of [locInst]'s lhs within [b].
 * If [locInst] has more than one use then it will return null
 **/
private fun getNextIntraBlockUse(b: SbfBasicBlock, locInst: LocatedSbfInstruction): LocatedSbfInstruction? {
    val lhs = locInst.inst.writeRegister.singleOrNull() ?: return null
    val nextUseLocInst = getNextUseInterBlock(b, locInst.pos+1, lhs) ?: return null
    if (nextUseLocInst.label != b.getLabel()) {
        // only same block
        return null
    }
    return nextUseLocInst
}

private fun findDefinitionIntraBlock(b: SbfBasicBlock, reg: Value.Reg, pos: Int): LocatedSbfInstruction? {
    val locInst = findDefinitionInterBlock(b, reg, pos)
    return if (locInst?.label == b.getLabel()) {
        locInst
    } else {
        null
    }
}
