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
import sbf.domains.AbstractDomain
import sbf.domains.InstructionListener

/**
 * Retrieves SbfTypes from [analysis]
 */
class AnalysisRegisterTypes<D: AbstractDomain<D>>(
    val analysis: IAnalysis<D>
): IRegisterTypes {
    private val types: MutableMap<LocatedSbfInstruction, Map<SbfRegister, SbfType>> = mutableMapOf()
    private val listener = TypeListener()

    override fun typeAtInstruction(i: LocatedSbfInstruction, r: SbfRegister): SbfType {
        val block = i.label
        if (i !in types) {
            val absVal = analysis.getPre(block)
            check(absVal != null) {
                "Missing block $block in memory analysis"
            }

            val bb = analysis.getCFG().getBlock(block)
            check(bb != null) {
                "Missing block $block in cfg"
            }


            absVal.analyze(bb, analysis.getGlobalVariableMap(), analysis.getMemorySummaries(), listener)
        }

        return types[i]?.get(r) ?: SbfType.Top
    }

    /**
     * To determine the type of a register `r` at a given instruction, we try to be as precise as possible.
     * It's possible that in dst := dst `op` src, if src != dst, the type of src in the post state is
     * more precise than in the pre state if the instruction called **`castNumToPtr`**.
     * So first we record the types of the register file in the pre-state, and update any
     * relevant registers with their type in the post-state.
     */
    private inner class TypeListener : InstructionListener<D> {
        override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: D) {
            val inst = locInst.inst
            if (inst is SbfInstruction.Mem) {
                // We use the post-state to update all non-written registers
                val written = (inst as? WriteRegister)?.writeRegister ?: setOf()
                // the "before" callback will have already executed
                types[locInst] = types[locInst]!!.mapValues { (r, ty) ->
                    val regVal = Value.Reg(r)
                    post.getValue(regVal).get().takeUnless { regVal in written } ?: ty
                }
            } else if (inst is SbfInstruction.Call) {
                val calltraceFn = CVTCalltrace.from(inst.name)
                if (calltraceFn != null) {
                    val strings = calltraceFn.strings.map { it.string.r }
                    // We use the post-state to update only string registers
                    types[locInst] = types[locInst]!!.mapValues { (r, ty) ->
                        val regVal = Value.Reg(r)
                        post.getValue(regVal).get().takeUnless { !strings.contains(r) } ?: ty
                    }
                }
            }
        }

        override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: D) {
            val readRegisters = locInst.inst.readRegisters.toMutableSet()
            readRegisters.add(Value.Reg(SbfRegister.R10_STACK_POINTER))
            types[locInst] = readRegisters.map{r->r.r}.associateWith { r ->
                pre.getValue(Value.Reg(r)).get()
            }
        }

        override fun instructionEvent(locInst: LocatedSbfInstruction, pre: D, post: D) = Unit
    }
}
