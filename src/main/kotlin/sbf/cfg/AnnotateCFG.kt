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
import sbf.domains.AbstractDomain
import sbf.domains.InstructionListener

/**
 *  Modify *IN-PLACE* the CFG to annotate instruction operands with types.
 *  This annotation is mostly useful for debugging and pretty-printing, but
 *  it can be also convenient to communicate types between different analyses.
 **/
fun <T: AbstractDomain<T>> annotateCFGWithTypes(
    cfg: MutableSbfCFG,
    preMap: (Label) -> T?,
    getType: (Value, T) -> SbfRegisterType?) {

    fun annotateCondWithTypes(cond: Condition, types: T): Condition {
        return cond.copy(typedLeft = cond.typedLeft.copy(type = getType(cond.left, types)),
                         typedRight = cond.typedRight.copy(type = getType(cond.right, types)))
    }

    fun annotateMemWithTypes(inst: SbfInstruction.Mem, pre: T, post: T): SbfInstruction.Mem {
        return if (inst.isLoad) {
            val valueType = getType(inst.value, post)
            val baseType =  if ((inst.value as Value.Reg) == inst.access.base) {
                getType(inst.access.base, pre)
            } else {
                // we use "post" in case the memory load recast baseReg (e.g., number -> ptr)
                getType(inst.access.base, post)
            }
            inst.copy(typedValue = inst.typedValue.copy(type = valueType),
                      access = inst.access.copy(typedBase = inst.access.typedBase.copy(type = baseType)))
        } else {
            inst.copy(typedValue = inst.typedValue.copy(type = getType(inst.value, pre)),
                      access = inst.access.copy(typedBase = inst.access.typedBase.copy(type = getType(inst.access.base, post))))
        }
    }

    fun annotateBinOpWithTypes(inst: SbfInstruction.Bin, pre: T, post: T): SbfInstruction.Bin {
        return inst.copy(
            typedRhs = inst.typedRhs.copy(type = getType(inst.v, pre)),
            preDstType = getType(inst.dst, pre),
            postDstType = getType(inst.dst, post)
        )
    }

    fun annotateUnOpWithTypes(inst: SbfInstruction.Un, pre: T, post: T): SbfInstruction.Un {
        return inst.copy(preDstType = getType(inst.dst, pre), postDstType = getType(inst.dst, post))
    }

    fun annotateInstWithTypes(inst: SbfInstruction, pre: T, post: T): SbfInstruction {
        if (pre.isBottom()) {
            return inst
        }
        return when (inst) {
            is SbfInstruction.Assume -> {
                inst.copy(cond = annotateCondWithTypes(inst.cond, pre))
            }
            is SbfInstruction.Assert -> {
                inst.copy(cond = annotateCondWithTypes(inst.cond, pre))
            }
            is SbfInstruction.Bin -> {
                annotateBinOpWithTypes(inst, pre, post)
            }
            is SbfInstruction.Un -> {
                annotateUnOpWithTypes(inst, pre, post)
            }
            is SbfInstruction.Jump -> {
                if (inst is SbfInstruction.Jump.ConditionalJump) {
                    inst.copy(cond = annotateCondWithTypes(inst.cond, pre))
                } else {
                    inst
                }
            }
            is SbfInstruction.Mem -> {
                annotateMemWithTypes(inst, pre, post)
            }
            else -> {
                inst
            }
        }
    }

    class AnnotateWithTypesListener(val newInsts: ArrayList<SbfInstruction>): InstructionListener<T> {
        override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: T) {}
        override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: T) {}
        override fun instructionEvent(locInst: LocatedSbfInstruction, pre: T, post: T) {
            newInsts.add(annotateInstWithTypes(locInst.inst, pre, post))
        }
    }

    class AnnotateBasicBlockWithTypes: DfsVisitMutableAction {
        override fun applyBeforeChildren(b: MutableSbfBasicBlock) {
            val blockAbsVal = preMap(b.getLabel())
            if (blockAbsVal == null || blockAbsVal.isBottom()) {
                return
            }
            val newInsts = ArrayList<SbfInstruction>()
            val listener = AnnotateWithTypesListener(newInsts)
            blockAbsVal.analyze(b, listener)
            // REVISIT: in-place modification of the CFG
            b.replaceInstructions(newInsts)
        }
        override fun applyAfterChildren(b: MutableSbfBasicBlock) {}
        override fun skipChildren(b: SbfBasicBlock): Boolean { return false}
    }

    val vis = AnnotateBasicBlockWithTypes()
    dfsVisit(cfg.getMutableEntry(), vis)
}
