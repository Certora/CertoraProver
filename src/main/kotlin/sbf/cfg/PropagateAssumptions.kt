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

import sbf.analysis.AnalysisRegisterTypes
import sbf.domains.*

/**
 * This pass adds annotations `EQUALITY_REG_AND_STACK` in the CFG to propagate certain equalities.
 *
 * This pass would not be needed if scalar domain would be relational.
 */
fun <D, TNum, TOffset> propagateAssumptions(
    cfg: MutableSbfCFG,
    registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>
) where TNum: INumValue<TNum>,
        TOffset: IOffset<TOffset>,
        D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    for (bb in cfg.getMutableBlocks().values) {
        val firstLocInst = bb.getLocatedInstructions().firstOrNull()
        check(firstLocInst != null) { "CFG should not have empty blocks" }
        val assumeInst = firstLocInst.inst
        if (assumeInst is SbfInstruction.Assume) { // We assume that is a lowered assume by lowerBranchesIntoAssume
            val predsBB = bb.getPreds()
            if (predsBB.size == 1) {
                val predBB = predsBB.first()
                val terminatorInst = predBB.getTerminator()
                if (terminatorInst is SbfInstruction.Jump.ConditionalJump) {
                    val cond = terminatorInst.cond
                    val reg = getRegFromUnaryConditionOrNull(cond)
                    if (reg != null) {
                        val index = findDefinition(predBB, reg)
                        if (index >= 0) {
                            val def = predBB.getInstruction(index)
                            if (def is SbfInstruction.Mem && def.isLoad) {
                                val defLocInst = LocatedSbfInstruction(predBB.getLabel(), index, def)
                                val stackMeta = resolveStackAccessOrNull(defLocInst, registerTypes)
                                if (stackMeta != null) {
                                    if (noInterference(predBB, def = index, use = predBB.getInstructions().size - 1)) {
                                        addAnnotation(bb, firstLocInst, reg, stackMeta)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * We need to prove that between definition and use, *sp(4000) has not been updated
 * r1 := *sp(4000) <-- definition
 * ...
 * if (r1 == 0)    <-- use
 **/
private fun noInterference(bb: SbfBasicBlock, def: Int, use:Int): Boolean {
    // kotlin sublist: from inclusive, to exclusive
    for (inst in bb.getInstructions().subList(def+1, use)) {
        if (inst is SbfInstruction.Mem) {
            return false
        }
    }
    return true
}


private fun getRegFromUnaryConditionOrNull(cond: Condition): Value.Reg? {
    return if (cond.right is Value.Imm) {
        cond.left
    } else {
        null
    }
}

private fun <D, TNum, TOffset> resolveStackAccessOrNull(
    locatedInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>): StackContentMeta?
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val inst = locatedInst.inst
    check(inst is SbfInstruction.Mem) { "normalizeLoad expects a memory instruction instead of $inst" }
    val base = inst.access.base
    val width = inst.access.width
    val regType = types.typeAtInstruction(locatedInst, base)
    return if (regType is SbfType.PointerType.Stack) {
        val offset = regType.offset.toLongOrNull()
        if (offset != null) {
            StackContentMeta(offset + inst.access.offset, width)
        } else {
            null
        }
    } else {
        null
    }
}

private fun addAnnotation(bb: MutableSbfBasicBlock, locInst: LocatedSbfInstruction, reg: Value.Reg, stackContent: StackContentMeta) {
    val inst = locInst.inst
    if (inst is SbfInstruction.Assume) {
        val newMetaData = inst.metaData.plus(Pair(SbfMeta.EQUALITY_REG_AND_STACK, Pair(reg, stackContent)))
        val newInst = inst.copy(metaData = newMetaData)
        bb.replaceInstruction(locInst.pos, newInst)
    }
}

