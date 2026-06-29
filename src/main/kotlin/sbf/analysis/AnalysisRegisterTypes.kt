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

package sbf.analysis

import datastructures.stdcollections.*
import sbf.cfg.*
import sbf.disassembler.Label
import sbf.disassembler.SbfRegister
import sbf.domains.*

/**
 * Configuration for controlling what analysis results to cache.
 */
data class AnalysisCacheOptions(
    /**
     * Predicate to filter which instructions should have their abstract state cached.
     * These abstract states hold **before** the execution of instruction.
     */
    val abstractStateFilter: (LocatedSbfInstruction) -> Boolean = { false }
) {
    companion object {
        /** Cache everything */
        val ALL = AnalysisCacheOptions(
            abstractStateFilter = { true }
        )

        /** Cache only types, no abstract states */
        val TYPES_ONLY = AnalysisCacheOptions(
            abstractStateFilter = { false }
        )

        /** Cache abstract states only at assume instructions */
        fun typesAndAssumes() = AnalysisCacheOptions(
            abstractStateFilter = { it.inst is SbfInstruction.Assume }
        )

        /** Cache abstract states only at specific instruction types */
        fun typesAndStatesAt(predicate: (LocatedSbfInstruction) -> Boolean) =
            AnalysisCacheOptions(
                abstractStateFilter = predicate
            )
    }
}

/**
 * Provides register type information derived from a scalar domain.
 *
 * Computes and caches register types and optionally abstract states at each
 * program instruction based on the results of the underlying analysis.
 *
 * @param D The scalar abstract domain
 * @param analysis The scalar analysis containing invariants at entry/exit of each basic block
 * @param options Configuration for what analysis results to cache
 */
class AnalysisRegisterTypes<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
    val analysis: IAnalysis<D>,
    private val options: AnalysisCacheOptions = AnalysisCacheOptions.TYPES_ONLY
): IRegisterTypes<TNum, TOffset>
where D: AbstractDomain<D>,
      D: ScalarValueProvider<TNum, TOffset> {

    /**
     * Type cache: map any non-written register (included non-read by `i`) to its type
     **/
    private val types: MutableMap<LocatedSbfInstruction, Map<SbfRegister, SbfType<TNum, TOffset>>> = mutableMapOf()
    /**
     * Type cache: map any written register to its type
     **/
    private val typesWriteRegs: MutableMap<LocatedSbfInstruction, Map<SbfRegister, SbfType<TNum, TOffset>>> = mutableMapOf()
    /**
     * State cache: maps instruction to the abstract state that holds before the execution of the instruction.
     * Only populated for instructions matching the filter in [options].
     **/
    private val states: MutableMap<LocatedSbfInstruction, D> = mutableMapOf()

    /**
     * Tracks which basic blocks have been processed to avoid redundant computation.
     * Once a block is processed, all its instructions will have their types and states extracted.
     */
    private val processedBlocks: MutableSet<Label> = mutableSetOf()

    private val allRegisters = SbfRegister.entries.map{ r -> Value.Reg(r)}.toSet()

    private val typeAndStateExtractor = TypeAndStateExtractor()


    override fun typeAtInstruction(
        locInst: LocatedSbfInstruction,
        r: Value.Reg,
        isWritten: Boolean
    ): SbfType<TNum, TOffset> {
        val inst = locInst.inst
        check(!isWritten || r in inst.writeRegister) { "Register $r not written by $inst" }

        processInvariants(locInst)

        val types = if (!isWritten) { types } else { typesWriteRegs }
        return types[locInst]?.get(r.r) ?: SbfType.top()
    }

    /**
     * Returns the cached abstract state before execution of [locInst], if available.
     *
     * @return The abstract state if cached according to [options], null otherwise
     */
    fun getAbstractState(locInst: LocatedSbfInstruction): D? {
        processInvariants(locInst)
        return states[locInst]
    }


    /** Generate the invariant at each instruction and calls [typeAndStateExtractor] to process it **/
    private fun processInvariants(locInst: LocatedSbfInstruction) {
        val blockLabel = locInst.label
        if (!processedBlocks.add(blockLabel)) {
            return
        }
        val absVal = checkNotNull(analysis.getPre(blockLabel))
        val bb = checkNotNull(analysis.getCFG().getBlock(blockLabel))
        absVal.analyze(bb, typeAndStateExtractor)
    }

    /**
     * Listener that extracts and caches type information from abstract states.
     * Optionally, it also caches abstract states.
     *
     * For each instruction, we extract types from both pre- and post- states:
     * - Pre-state: types for all read registers before the instruction executes
     * - Post-state: types for all written registers
     */
    private inner class TypeAndStateExtractor : InstructionListener<D> {

        private fun getAsScalarValue(inst: SbfInstruction, state: D, reg: Value.Reg): ScalarValue<TNum, TOffset> {
            return when (inst) {
                is SbfInstruction.Mem -> {
                    if (inst.access.base == reg) {
                        state.getAsScalarValueWithNumToPtrCast(reg)
                    } else {
                        state.getAsScalarValue(reg)
                    }
                }
                else -> state.getAsScalarValue(reg)
            }
        }

        override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: D) {
            typesWriteRegs[locInst] = locInst.inst.writeRegister.map{ r->r.r }.associateWith { r ->
                post.getAsScalarValue(Value.Reg(r)).type()
            }.filterValues { type ->
                !type.isTop()
            }
        }

        override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: D) {
            // -- Update types using the pre-state
            val inst = locInst.inst
            val usedRegisters = inst.readRegisters + inst.writeRegister
            types[locInst] = allRegisters.map{ r->r.r }.associateWith { r ->
                getAsScalarValue(inst, pre, Value.Reg(r)).type()
            }.filter { (r, type) ->
                // we don't keep an entry if the register is top and not used by the instruction
                Value.Reg(r) in usedRegisters || !type.isTop()
            }

            if (options.abstractStateFilter(locInst)) {
                states[locInst] = pre
            }
        }

        override fun instructionEvent(locInst: LocatedSbfInstruction, pre: D, post: D) = Unit
    }
}
