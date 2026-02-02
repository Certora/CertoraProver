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

import com.certora.collect.*
import datastructures.stdcollections.*
import analysis.*
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.support.timeIt
import utils.foldFirstOrNull
import utils.pointwiseMerge
import utils.updateInPlace

class FunctionArgumentInference(graph: SbfCFG): IFunctionArgumentInference by FunctionArgumentAnalysis(graph)

private class FunctionArgumentAnalysis(graph: SbfCFG) :
    IFunctionArgumentInference,
    SbfCommandDataflowAnalysis<LiveArgRegisters>(
    graph,
    LiveArgRegisters.lattice,
    LiveArgRegisters.bottom,
    Direction.BACKWARD
) {

    /**
     * Maps each function name to the CVT_save_scratch_registers corresponding to where it has been inlined
     */
    private val visitedInlinedFunctions: MutableMap<String, Set<LocatedSbfInstruction>> = mutableMapOf()

    init {
        timeIt("Function Argument Analysis") {
            runAnalysis()
        }
    }

    override fun inferredArgs(fn: String): Map<Value.Reg, Set<LocatedSbfInstruction>>? {
        return visitedInlinedFunctions[fn]?.let { locations ->
            locations.mapNotNull { cmdIn[it]?.uses }.foldFirstOrNull { lhs, rhs ->
                lhs.pointwiseMerge(rhs) { lUses, rUses -> lUses + rUses }
            }
        }
    }

    override fun liveAtThisCall(i: LocatedSbfInstruction): Set<Value.Reg>? =
        cmdIn[i]?.uses?.keys?.toSet()

    private fun genAndKill(inState: LiveArgRegisters, gen: Collection<Value.Reg>, killed: Collection<Value.Reg>, cmd: LocatedSbfInstruction): LiveArgRegisters {
        val newUses = gen.associateWith { setOf(cmd) }
        return inState.copy(
            uses = (inState.uses - killed.toSet()).pointwiseMerge(newUses) { lhs, rhs -> lhs + rhs }
        )
    }

    private fun transformScratchRegisterOp(inState: LiveArgRegisters, call: SbfInstruction.Call, cmd: LocatedSbfInstruction): LiveArgRegisters {
        val callId = call.metaData.getVal(SbfMeta.CALL_ID)
        val inlinedFunction = call.metaData.getVal(SbfMeta.INLINED_FUNCTION_NAME)
        check(inlinedFunction == null || callId != null)

        return if (inlinedFunction == null || callId == null) {
            inState
        } else if (call.isSaveScratchRegisters()) {
            visitedInlinedFunctions.updateInPlace(inlinedFunction, setOf()) {
                it + cmd
            }
            inState.restore(callId) ?: LiveArgRegisters.bottom
        } else {
            inState.push(callId)
        }
    }

    private fun transformCall(inState: LiveArgRegisters, call: SbfInstruction.Call, cmd: LocatedSbfInstruction): LiveArgRegisters {
        return if (call.isSaveScratchRegisters() || call.isRestoreScratchRegisters()) {
            transformScratchRegisterOp(inState, call, cmd)
        } else if (SolanaFunction.from(call.name) != null ||
                   CVTFunction.from(call.name) != null ||
                   CompilerRtFunction.from(call.name) != null) {
            genAndKill(inState, call.readRegisters, call.writeRegister, cmd)
        } else {
            // Arity is the same as the maximum argument
            val arity = call.metaData.getVal(SbfMeta.KNOWN_ARITY) ?: run {
                /*sbfLogger.warn {
                    "Unknown arity for ${call.name}, argument information may be imprecise"
                }*/
                SbfRegister.maxArgument.value.toInt()
            }

            // Grab all argument registers <= arity for use as the gen set
            val gen = SbfRegister.funArgRegisters.mapNotNull { r ->
                Value.Reg(r).takeIf {
                    r.value <= arity
                }
            }

            genAndKill(inState, gen, setOf(), cmd)
        }
    }

    override fun transformCmd(inState: LiveArgRegisters, cmd: LocatedSbfInstruction): LiveArgRegisters {
        return when(val istr = cmd.inst) {
            is SbfInstruction.Call -> transformCall(inState, istr, cmd)
            else -> defaultTransformer(inState, cmd)
        }
    }

    private fun defaultTransformer(inState: LiveArgRegisters, cmd: LocatedSbfInstruction): LiveArgRegisters {
        val gen = gen(cmd.inst)
        val killed = kill(cmd.inst)
        return genAndKill(inState, gen, killed, cmd)
    }

    private fun gen(i: SbfInstruction): Set<Value.Reg> =
        i.readRegisters.filterTo(mutableSetOf()) { it.r in SbfRegister.funArgRegisters }


    private fun kill(i: SbfInstruction): Set<Value.Reg> =
        i.writeRegister.filterTo(mutableSetOf()) { it.r in SbfRegister.funArgRegisters }

}

/**
 * To manage nested calls, we track an active live set of registers, remembering
 * the visited uses, plus a set of "pushed" live sets. We push a set when we
 * encounter a nested call. E.g. (recall this is a backwards analysis):
 *
 * {{ live = R, pushed = {} }} // Restore the pushed set
 * pushRegisters() // Remember that R was the live set at this location for foo
 * {{ live = R_foo, pushed = {R} }}
 * [[inline(foo)]]
 * {{ live = {}, pushed = {R} }} // push the live set
 * popRegisters()
 * {{ live = R, pushed = {}}}  // <- input flow fact
 *
 *
 * @property uses the live argument registers in the current function
 * @property scopes live argument registers for outer calls we are in the middle of analyzing
 */
private data class LiveArgRegisters(
    val uses: Map<Value.Reg, Set<LocatedSbfInstruction>>,
    val scopes: Map<ULong, PushedScope>,
) {
    init {
        check(uses.keys.all {
            it.r in SbfRegister.funArgRegisters
        })
    }

    fun restore(callId: ULong): LiveArgRegisters? {
        return scopes[callId]?.let {
            this.copy(
                uses = it.uses,
                scopes = scopes - callId
            )
        }
    }

    fun push(callId: ULong): LiveArgRegisters {
        return this.copy(
            uses = treapMapOf(),
            scopes = scopes + (callId to PushedScope(this.uses))
        )
    }

    companion object {
        val bottom = LiveArgRegisters(
            scopes = treapMapOf(),
            uses = treapMapOf(),
        )

        val lattice = object : JoinLattice<LiveArgRegisters> {
            override fun join(x: LiveArgRegisters, y: LiveArgRegisters): LiveArgRegisters {
                val uses = x.uses.pointwiseMerge(y.uses) { lhs, rhs ->
                    lhs + rhs
                }
                val scopes = x.scopes.pointwiseMerge(y.scopes) { lhs, rhs ->
                    lhs.join(rhs)
                }
                return LiveArgRegisters(
                    uses,
                    scopes,
                )
            }

            override fun equiv(x: LiveArgRegisters, y: LiveArgRegisters): Boolean =
                x == y
        }
    }

    data class PushedScope(val uses: Map<Value.Reg, Set<LocatedSbfInstruction>>) {
        fun join(scope: PushedScope): PushedScope {
            return PushedScope(
                uses.pointwiseMerge(scope.uses) { lhs, rhs ->
                    lhs + rhs
                }
            )
        }
    }
}



