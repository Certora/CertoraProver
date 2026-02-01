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

import algorithms.SCCGraphInfo
import datastructures.stdcollections.*
import com.certora.collect.*
import analysis.*
import log.*
import sbf.cfg.*
import sbf.disassembler.*
import org.jetbrains.annotations.TestOnly
import sbf.callgraph.*
import sbf.support.timeIt


private val logger = Logger(LoggerTypes.SBF_LIVENESS)
typealias LiveRegisters = Set<Value.Reg>

/**
 * [registers]: set of live registers
 * [liveScratchRegsAfterFunction]: The key is the ID associated to `cvt_restore_scratch_registers` instruction,
 * and the value is the set of live scratch registers at that time.
 * This set will be used by cvt_save_scratch_registers function with same ID.
 */
data class LiveState(val registers: LiveRegisters,
                     val liveScratchRegsAfterFunction: Map<ULong, LiveRegisters>) {
    companion object {
        val bottom = LiveState(registers = treapSetOf(), liveScratchRegsAfterFunction = treapMapOf())

        val lattice = object : JoinLattice<LiveState> {
            override fun join(x: LiveState, y: LiveState): LiveState {
                return LiveState(x.registers + y.registers,
                    x.liveScratchRegsAfterFunction  + y.liveScratchRegsAfterFunction)
            }

            override fun equiv(x: LiveState, y: LiveState): Boolean =
                x.registers == y.registers && x.liveScratchRegsAfterFunction == y.liveScratchRegsAfterFunction
        }
    }
}


class LivenessAnalysis(graph: SbfCFG) :
    SbfCommandDataflowAnalysis<LiveState>(
        graph,
        LiveState.lattice,
        LiveState.bottom,
        Direction.BACKWARD
    ) {

    // Only needed for an optimization
    private val loopInfo = SCCGraphInfo(graph.getBlocks().values.associate { node ->
        node.getLabel() to node.getSuccs().map { it.getLabel() }.toSet()
    })

    init {
        timeIt("Liveness analysis", logger) {
            runAnalysis()
        }
        logger.debug { "$this" }
    }

    private fun genAndKill(
        inState: LiveState,
        gen: Set<Value.Reg>,
        killed: Collection<Value.Reg>
    ): LiveState {
        return inState.copy(registers = (inState.registers - killed.toSet()) + gen)
    }

    private fun getFunctionId(inst: SbfInstruction.Call): ULong {
        check(inst.isSaveScratchRegisters() || inst.isRestoreScratchRegisters())
        {"Precondition of getFunctionId failed"}
        val id = inst.metaData.getVal(SbfMeta.CALL_ID)
        check(id != null ) {"getFunctionId expects $inst to have IDs"}
        return id
    }
    private fun transformScratchRegisterOp(
        inState: LiveState,
        @Suppress("UNUSED_PARAMETER")
        call: SbfInstruction.Call,
        @Suppress("UNUSED_PARAMETER")
        cmd: LocatedSbfInstruction
    ): LiveState {
        val scratchRegs = setOf(
            Value.Reg(SbfRegister.R6),
            Value.Reg(SbfRegister.R7),
            Value.Reg(SbfRegister.R8),
            Value.Reg(SbfRegister.R9)
        )

        return  if (call.isRestoreScratchRegisters()) {
            // kill scratch registers and remember the live scratch registers at this point
            val newLiveScratchRegsAfterFunction =
                inState.liveScratchRegsAfterFunction + (getFunctionId(call) to inState.registers.intersect(scratchRegs))
            genAndKill(
                inState.copy(
                    liveScratchRegsAfterFunction = newLiveScratchRegsAfterFunction
                ),
                setOf(),
                scratchRegs
            )
        } else {
            // Mark as alive only those scratch registers that are used after the function returns
            val liveScratchRegs = inState.liveScratchRegsAfterFunction.getOrDefault(getFunctionId(call), scratchRegs)
            genAndKill(inState, liveScratchRegs, setOf())
        }
    }

    private fun transformCall(
        inState: LiveState,
        call: SbfInstruction.Call,
        cmd: LocatedSbfInstruction
    ): LiveState {
        return if (call.isSaveScratchRegisters() || call.isRestoreScratchRegisters()) {
            transformScratchRegisterOp(inState, call, cmd)
        } else if (call.isAllocFn()) {
            genAndKill(inState,
                gen = setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)),
                killed = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)))
        } else if (SolanaFunction.from(call.name) != null ||
                   CVTFunction.from(call.name) != null ||
                   CompilerRtFunction.from(call.name) != null) {
            genAndKill(inState, gen = call.readRegisters, killed = call.writeRegister)
        } else {
            // This is very conservative because the metadata KNOWN_ARITY is not available at the time
            // the liveness analysis is executed, so arity will be always 5.

            // Arity is the same as the maximum argument
            val arity = call.metaData.getVal(SbfMeta.KNOWN_ARITY) ?: run {
                SbfRegister.maxArgument.value.toInt()
            }
            // Grab all argument registers <= arity for use as the gen set
            val gen = SbfRegister.funArgRegisters.mapNotNull { r ->
                Value.Reg(r).takeIf {
                    r.value <= arity
                }
            }
            genAndKill(inState, gen.toSet(), setOf())
        }
    }

    override fun transformCmd(inState: LiveState, cmd: LocatedSbfInstruction): LiveState {
        return when(val inst = cmd.inst) {
            is SbfInstruction.Call -> transformCall(inState, inst, cmd)
            // If intra-procedural we assume r0 is always alive
            is SbfInstruction.Exit ->
                inState.copy(registers = inState.registers + setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE)))
            else -> defaultTransformer(inState, cmd)
        }
    }

    private fun defaultTransformer(inState: LiveState, cmd: LocatedSbfInstruction): LiveState {
        val gen = gen(cmd.inst)
        val killed = kill(cmd.inst)

        val inst = cmd.inst

        if (!loopInfo.isInLoop(cmd.label)) {
            if (inst is SbfInstruction.Bin || (inst is SbfInstruction.Mem && inst.isLoad)) {
                // Optimization: we don't add gen set if killed set is not alive
                check(killed.isNotEmpty()) { "Unexpected situation in defaultTransformer with $inst" }
                if (inState.registers.intersect(killed).isEmpty()) {
                    return inState
                }
            }
        }
        return genAndKill(inState, gen, killed)
    }

    private fun gen(i: SbfInstruction): Set<Value.Reg> = i.readRegisters
    private fun kill(i: SbfInstruction): Set<Value.Reg> = i.writeRegister

    /** Map a block to its set of alive registers **before** the block is executed **/
    fun getLiveRegistersAtEntry(): Map<Label, LiveRegisters>  {
        return blockOut.mapValues { it.value.registers }
    }

    /** Map a block to its set of alive registers **after** the block is executed **/
    fun getLiveRegistersAtExit(): Map<Label, LiveRegisters>  {
        return blockIn.mapValues { it.value.registers }
    }

    /**
     *  Return the set of alive register before or after each instruction is executed in block [label].
     *  If [before] is true (false) then the live info is before (after) the instruction
     **/
    fun getLiveRegistersAtInst(label: Label, before: Boolean): Map<LocatedSbfInstruction, LiveRegisters> {
        val res = mutableMapOf<LocatedSbfInstruction, LiveRegisters>()
        val bb = graph.getBlock(label)
        check(bb != null) {"getLiveRegistersAtInst cannot find block $label"}
        for (locInst in bb.getLocatedInstructions()) {
            val liveState = if (before) { cmdOut[locInst] }  else {cmdIn[locInst]}
            if (liveState != null ) {
                res[locInst] = liveState.registers
            }
        }
        return res
    }

    /** [reg] is alive before the execution of basic block [label] **/
    @TestOnly
    fun isAliveAtEntry(reg: Value.Reg, label: Label): Boolean {
        val liveRegisters = getLiveRegistersAtEntry()[label] ?: return false
        return reg in liveRegisters
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("==== Live analysis for ${graph.getName()} ==== \n")
        for (b in graph.getBlocks().values) {
            val liveInst = getLiveRegistersAtInst(b.getLabel(), true)
            sb.append("${b.getLabel()}:\n")
            for (locInst in b.getLocatedInstructions()) {
                val live = liveInst[locInst]
                sb.append("\t$live\n\t${locInst.inst}\n")
            }
        }
        return sb.toString()
    }
}
