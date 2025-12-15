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

package sbf.domains

import sbf.cfg.*
import sbf.disassembler.GlobalVariables
import sbf.disassembler.Label

/**
 * Generic interface of an abstract domain used for analysis of SBF programs
 **/
interface AbstractDomain<T> {
    fun isBottom(): Boolean
    fun isTop(): Boolean
    fun setToBottom()
    fun setToTop()
    // left and right are used only for debugging purposes, so they are not mandatory
    fun join(other: T, left: Label? = null, right: Label? = null): T
    // left and right are used only for debugging purposes, so they are not mandatory
    fun widen(other: T, b: Label? = null): T
    // left and right are used only for debugging purposes, so they are not mandatory
    fun lessOrEqual(other: T, left: Label? = null, right: Label? = null): Boolean
    fun forget(reg: Value.Reg)
    fun analyze(b: SbfBasicBlock,
                globals: GlobalVariables,
                memSummaries: MemorySummaries,
                listener: InstructionListener<T> = DefaultInstructionListener()): T
    /**
     * Non-standard operation.
     *
     * The goal of this operation is to allow changing this into a different abstract state but with the
     * same concretization, producing a more precise result when joining with other.
     * This is useful for "syntactic" domains that lack of a canonical representation, so it is common that two
     * different abstract states can have the same concretization.
     *
     * This operation uses other to find an alternative but equivalent (same concretization) representation of this
     * that is syntactically "closer" to other.
     *
     * @params this: modified in-place
     * @params other: immutable
     *
     * The default implementation does nothing.
    **/
    fun pseudoCanonicalize(other: T) {}

    fun deepCopy(): T
    override fun toString(): String
}

/**
 *  Abstract states are stored at the level of basic block.
 *  However, we need sometimes to know the abstract state that holds before or after a given instruction.
 *  We cannot store abstract states per instruction because it is very expensive in terms of memory consumption.
 *  Instead, we reconstruct those abstract states whenever needed.
 *  Note that this reconstruction only happens inside the basic block, so it shouldn't be computationally
 *  expensive.
 **/
interface InstructionListener<T> {
    // @pre holds before @inst is called
    fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: T)
    // @post holds after @inst is called
    fun instructionEventAfter(locInst: LocatedSbfInstruction, post: T)
    // pre (post) holds before (after) inst is called
    fun instructionEvent(locInst: LocatedSbfInstruction, pre: T, post: T)
}

class DefaultInstructionListener<T>: InstructionListener<T> {
    override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: T){}
    override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: T){}
    override fun instructionEvent(locInst: LocatedSbfInstruction, pre: T, post: T){}
}


interface ScalarValueProvider<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    fun getAsScalarValue(value: Value): ScalarValue<TNum, TOffset>
    fun getStackContent(offset: Long, width: Byte): ScalarValue<TNum, TOffset>
}
