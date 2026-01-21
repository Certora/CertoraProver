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
import sbf.disassembler.SbfRegister
import sbf.domains.*

/**
 *  Normalize the memory access at [base]+[offset] at [locatedInst] as an offset with respect to r10.
 *  Otherwise, it returns null.
 **/
fun <D, TNum, TOffset>
    normalizeStackAccess(
    locatedInst: LocatedSbfInstruction,
    base: Value.Reg, offset: Long,
    types: AnalysisRegisterTypes<D, TNum, TOffset>
): Long? where
    TNum: INumValue<TNum>,
    TOffset: IOffset<TOffset>,
    D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    val regType = types.typeAtInstruction(locatedInst, base.r)
    return if (regType is SbfType.PointerType.Stack) {
        val regOffset = regType.offset.toLongOrNull() ?: return null
        val derefOffset = regOffset + offset
        val r10 = SbfRegister.R10_STACK_POINTER
        val r10Type = types.typeAtInstruction(locatedInst, r10)
        check(r10Type is SbfType.PointerType.Stack) { "normalizeStackAccess: scalar analysis lost track of r10 at $locatedInst (1)" }
        val stackPtr = r10Type.offset.toLongOrNull()
        check(stackPtr != null) { "normalizeStackAccess: scalar analysis lost track of r10 at $locatedInst (2)" }
        -(stackPtr - derefOffset)
    } else {
        null
    }
}

/** Find the definition (as the index in [b]) of [reg] in [b]. Return -1 if no found **/
fun findDefinition(b: SbfBasicBlock, reg: Value.Reg): Int {
    for ((i, inst) in b.getInstructions().asReversed().withIndex()) {
        if (inst.writeRegister.contains(reg)) {
            return b.getInstructions().size - i - 1
        }
    }
    return -1
}


/**
 * Starting from position [startPos] (exclusive) at block [b] finds the definition of [reg]
 * That definition can be located in some ancestor *as long as* the definition's block dominates [b],
 * there is no CFG diamonds in between, and the distance between the definition's block and [b] is
 * not greater than [maxNumLevelsUp].
 */
fun findDefinitionInterBlock(b: SbfBasicBlock, reg: Value.Reg, startPos: Int = b.numOfInstructions() - 1, maxNumLevelsUp:Int = 10): LocatedSbfInstruction? {
    var curB = b
    var n = 0
    while (n < maxNumLevelsUp) {
        for ((i, inst) in curB.getInstructions().asReversed().withIndex()) {
            if (n == 0 && (curB.getInstructions().size - i - 1) >= startPos) {
                continue
            }
            if (inst.writeRegister.contains(reg)) {
                return LocatedSbfInstruction(curB.getLabel(), curB.getInstructions().size - i - 1, inst)
            }
        }
        if (curB.getPreds().size == 1) {
            curB = curB.getPreds().first()
        } else {
            return null
        }
        n++
    }
    return null
}

/** Return true if [reg] is used between [from] (exclusive) and [to] (exclusive) **/
fun isUsed(bb: SbfBasicBlock, reg: Value.Reg, from: Int, to:Int): Boolean {
    return bb.getInstructions().subList(from+1, to).any { inst ->
        inst.readRegisters.contains(reg)
    }
}

/** Return true if [reg] is redefined between [from] (exclusive) and [to] (exclusive) **/
fun isRedefined(bb: SbfBasicBlock, reg: Value.Reg, from: Int, to:Int): Boolean {
    return bb.getInstructions().subList(from+1, to).any { inst ->
        inst.writeRegister.contains(reg)
    }
}

/** Return the next instruction that uses [reg] starting from position [start] in block [bb]
 *  if the use's block post-dominates [bb], there is no CFG diamonds between [bb] and use's block, and the distance
 *  between [bb] and use's block is not greater than [maxNumLevelsDown].
 **/
fun getNextUseInterBlock(bb: SbfBasicBlock, start: Int, reg: Value.Reg, maxNumLevelsDown: Int = 10): LocatedSbfInstruction? {
    var curB = bb
    var n = 0
    while (n < maxNumLevelsDown) {
        for (locInst in curB.getLocatedInstructions()) {
            if (curB == bb && locInst.pos < start) { // to make sure we start looking at position start
                continue
            } else {
                val inst = locInst.inst
                if (inst.readRegisters.contains(reg)) {
                    // reg is used
                    return locInst
                }

                if (inst.writeRegister.contains(reg)) {
                    // reg is redefined
                    return null
                }
            }
        }
        if (curB.getSuccs().size == 1) {
            curB = curB.getSuccs().first()
        } else {
            return null
        }
        n++
    }
    return null
}

