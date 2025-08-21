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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence

import analysis.CommandWithRequiredDecls
import analysis.MutableCommandWithRequiredDecls
import analysis.ip.InternalArgSort
import tac.CallId
import tac.MetaMap
import tac.NBId
import tac.Tag
import vc.data.*
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.data.CallableProgram
import datastructures.stdcollections.*
import verifier.equivalence.summarization.UnifiedCallingConvention

class UnifiedInternalFunctionGenerator(
    queryContext: EquivalenceQueryContext
) : AbstractRuleGeneration<UnifiedCallingConvention>(queryContext) {

    override fun setupCode(): CommandWithRequiredDecls<TACCmd.Simple> {
        return CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd))
    }

    private fun argumentVar(i: Int) = TACSymbol.Var(
        "scalarPart$i",
        tag = Tag.Bit256,
        callIndex = NBId.ROOT_CALL_ID,
        meta = MetaMap(TACMeta.NO_CALLINDEX)
    )

    override fun <T : MethodMarker> annotateInOut(
        inlined: CoreTACProgram,
        callingConv: UnifiedCallingConvention,
        context: ProgramContext<T>,
        callId: CallId
    ): CoreTACProgram {
        return inlined
    }

    override fun <T : MethodMarker> generateInput(
        p: CallableProgram<T, UnifiedCallingConvention>,
        context: ProgramContext<T>,
        callId: CallId
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val calleeMemory = TACKeyword.MEMORY.toVar(callId)
        val binding = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        binding.extend(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = calleeMemory,
                rhs = TACExpr.MapDefinition(Tag.ByteMap) {
                    TACExpr.Unconstrained(Tag.Bit256)
                }
            )
        ))
        binding.extend(calleeMemory)
        var argCounter = 0
        for(a in p.callingInfo.args) {
            when(a.sort) {
                InternalArgSort.SCALAR -> {
                    if(a.s !is TACSymbol.Var) {
                        continue
                    }
                    val input = argumentVar(argCounter++)
                    binding.extend(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = a.s.at(callId),
                            rhs = input
                        )
                    )
                    binding.extend(a.s.at(callId), input)
                }

                InternalArgSort.CALLDATA_ARRAY_ELEMS,
                InternalArgSort.CALLDATA_ARRAY_LENGTH,
                InternalArgSort.CALLDATA_POINTER -> throw UnsupportedOperationException("Should not be trying to do this")
            }
        }
        return binding.toCommandWithRequiredDecls()
    }
}
