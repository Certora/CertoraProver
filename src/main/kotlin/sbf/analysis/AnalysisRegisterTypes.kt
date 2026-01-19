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
import sbf.callgraph.CVTCalltrace
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.domains.*

/**
 * Retrieves SbfTypes from [analysis]
 */
class AnalysisRegisterTypes<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
    val analysis: IAnalysis<D>
): IRegisterTypes<TNum, TOffset>
where D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {
    /** Class invariant: `i in typesReadRegs iff i in typeWriteRegs` **/
    /** For a given instruction `i`, it maps any non-written register (included non-read by `i`) to its type at `i` **/
    private val types: MutableMap<LocatedSbfInstruction, Map<SbfRegister, SbfType<TNum, TOffset>>> = mutableMapOf()
    /** For a given instruction `i`, it maps written registers to their types at `i` **/
    private val typesWriteRegs: MutableMap<LocatedSbfInstruction, Map<SbfRegister, SbfType<TNum, TOffset>>> = mutableMapOf()
    private val allRegisters = SbfRegister.values().map{ r -> Value.Reg(r)}.toSet()
    private val listener = TypeListener()


    private fun collectTypesAtInstruction(i: LocatedSbfInstruction) {
        val block = i.label
        if (i !in types) {
            check (i !in typesWriteRegs)
            val absVal = analysis.getPre(block)
            check(absVal != null) {
                "Missing block $block in analysis"
            }
            val bb = analysis.getCFG().getBlock(block)
            check(bb != null) {
                "Missing block $block in cfg"
            }
            // Analyze the basic block to collect types per instruction
            absVal.analyze(bb, analysis.getGlobalVariableMap(), analysis.getMemorySummaries(), listener)
        }
    }

    override fun typeAtInstruction(i: LocatedSbfInstruction, r: Value.Reg, isWritten: Boolean): SbfType<TNum, TOffset> {
        val inst = i.inst
        check(!isWritten || r in inst.writeRegister) { "Register $r not written by $inst" }

        collectTypesAtInstruction(i)

        val types = if (!isWritten) { types } else { typesWriteRegs }
        return types[i]?.get(r.r) ?: SbfType.top()
    }

    /**
     * To determine the type of a register `r` at a given instruction, we try to be as precise as possible.
     * It's possible that in `dst := dst op src`, if `src != dst`, the type of `src` in the post-state is
     * more precise than in the pre-state if the instruction called **`castNumToPtr`**.
     * So first we record the types of the register file in the pre-state, and update any
     * relevant registers with their type in the post-state.
     */
    private inner class TypeListener : InstructionListener<D> {

        override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: D) {
            // -- Refine types for read registers using post-state
            val inst = locInst.inst
            val regsToRefine = inst.readRegisters - inst.writeRegister
            when (inst) {
                is SbfInstruction.Mem -> {
                    // We use the post-state to update registers in regsToRefine
                    types[locInst] = types[locInst]!!.mapValues { (r, ty) ->
                        when (val v = Value.Reg(r)) {
                            in regsToRefine -> post.getAsScalarValue(v).type()
                            else -> ty
                        }
                    }
                }
                is SbfInstruction.Call -> {
                    CVTCalltrace.from(inst.name)?.let { calltraceFn ->
                        val strings = calltraceFn.strings.map { it.string.r }
                        // We use the post-state to update registers in regsToRefine that are known to contain strings
                        types[locInst] = types[locInst]!!.mapValues { (r, ty) ->
                            val v = Value.Reg(r)
                            when  {
                                v in regsToRefine && v.r in strings -> post.getAsScalarValue(v).type()
                                else -> ty
                            }
                        }
                    }
                }
                else -> {}
            }

            // -- Update types for written registers
            typesWriteRegs[locInst] = locInst.inst.writeRegister.map{ r->r.r }.associateWith { r ->
                post.getAsScalarValue(Value.Reg(r)).type()
            }.filterValues { type ->
                !type.isTop()
            }
        }

        override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: D) {
            // -- Update types using the pre-state
            val usedRegisters = locInst.inst.readRegisters + locInst.inst.writeRegister
            types[locInst] = allRegisters.map{ r->r.r }.associateWith { r ->
                pre.getAsScalarValue(Value.Reg(r)).type()
            }.filter { (r, type) ->
                // we don't keep an entry if the register is top and not used by the instruction
                Value.Reg(r) in usedRegisters || !type.isTop()
            }
        }

        override fun instructionEvent(locInst: LocatedSbfInstruction, pre: D, post: D) = Unit
    }
}
