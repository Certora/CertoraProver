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
 * Abstract domain interface for static analysis of SBF programs.
 *
 * All operations are pure (non-mutating). They return new instances rather than
 * modifying the receiver.
 *
 * @param T The concrete type of the abstract domain implementation
 */
interface AbstractDomain<T> {
    /** Returns true if `this` represents the bottom element (empty set of concrete states) */
    fun isBottom(): Boolean

    /** Returns true if `this` represents the top element (all possible concrete states) */
    fun isTop(): Boolean

    /**
     * Returns the join (least upper bound) of `this` and [other].
     *
     * @param other The abstract state to join with
     * @param left Debug label for the left operand (optional)
     * @param right Debug label for the right operand (optional)
     */
    fun join(other: T, left: Label? = null, right: Label? = null): T

    /**
     * Returns the widening of `this` and [other].
     *
     * @param other The abstract state to widen with
     * @param b Debug label for the widening point (optional)
     */
    fun widen(other: T, b: Label? = null): T

    /**
     * Returns true if `this` is less than or equal to [other] in the abstract domain ordering.
     *
     * @param other The abstract state to compare against
     * @param left Debug label for the left operand (optional)
     * @param right Debug label for the right operand (optional)
     */
    fun lessOrEqual(other: T, left: Label? = null, right: Label? = null): Boolean

    /**
     * Returns an equivalent representation of this that is syntactically closer to [other].
     *
     * This non-standard operation is useful for syntactic domains that lack canonical
     * representations. Two syntactically different abstract states may represent the same
     * set of concrete states (same concretization). By finding a representation closer to
     * [other], subsequent join operations can produce more precise results.
     *
     * @param other The target abstract state to align with
     * @return An equivalent abstract state syntactically closer to [other]
     */
    fun pseudoCanonicalize(other: T): T

    /**
     * Returns the abstract state resulting from analyzing block [b].
     *
     * Applies the transfer function for each instruction in [b] sequentially,
     * optionally notifying [listener] before and after each instruction.
     *
     * @param b The basic block to analyze
     * @param listener Callback for instruction processing events
     */
    fun analyze(
        b: SbfBasicBlock,
        listener: InstructionListener<T> = DefaultInstructionListener()
    ): T

    /**
     * Returns a new abstract state with all information about [regs] removed.
     *
     * Each register in [regs] is set to top in the returned state,
     * effectively losing all tracked properties for those registers.
     *
     * @param regs The list of registers to forget
     */
    fun forget(regs: Iterable<Value.Reg>): T

    /**
     * Returns a deep copy of this abstract state.
     *
     * Required to support immutable semantics in fixpoint algorithms.
     */
    fun deepCopy(): T

    override fun toString(): String
}

interface MutableAbstractDomain<T>: AbstractDomain<T> {
    fun setToBottom()
    fun forget(reg: Value.Reg)
}


/** Global state used by abstract transfer functions **/
data class GlobalState(
    val globals: GlobalVariables,
    val memSummaries: MemorySummaries
)

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

/** Operations to query scalar values from a scalar domain  **/
interface ScalarValueProvider<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    fun getAsScalarValue(value: Value): ScalarValue<TNum, TOffset>
    fun getStackContent(offset: Long, width: Byte): ScalarValue<TNum, TOffset>
    fun getTypeFac(): ISbfTypeFactory<TNum, TOffset>
}

/**
 * Operations to update a scalar domain.
 * These operations allow other domains to refine a scalar domain.
 **/
interface MutableScalarValueUpdater<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    fun setScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>)
    fun setStackContent(offset: Long, width: Byte, value: ScalarValue<TNum, TOffset>)
}


/** Special operations that [MemoryDomain] needs from the scalar domain **/
interface MemoryDomainScalarOps<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    /**
     * This function returns the scalar value for [reg] similar to `getAsScalarValue`.
     * However, if the scalar value is a number then it tries to cast it to a pointer
     * in cases where that number is a known pointer address.
     */
    fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg): ScalarValue<TNum, TOffset>
}

data class StackLocation(val offset: Long, val width: Byte) {
    override fun toString() = "*Stack_${offset}_$width"
}

interface StackLocationQuery {
    /**
     * Returns the stack location from which [reg] was loaded, if known.
     */
    fun getStackSource(reg: Value.Reg): StackLocation?
}
