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
import tac.CallId
import tac.MetaMap
import tac.NBId
import tac.Tag
import vc.data.*
import verifier.equivalence.data.CallableProgram
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.summarization.PureFunctionExtraction
import datastructures.stdcollections.*

class InternalFunctionGenerator<R: PureFunctionExtraction.CallingConvention<R, *, *>>(
    queryContext: EquivalenceQueryContext
) : AbstractRuleGeneration<R>(queryContext) {
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
        callingConv: R,
        context: ProgramContext<T>,
        callId: CallId
    ): CoreTACProgram {
        return inlined
    }

    override fun <T : MethodMarker> generateInput(
        p: CallableProgram<T, R>,
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
        binding.extend(p.callingInfo.bindSymbolicArgs(callId, ::argumentVar))
        return binding.toCommandWithRequiredDecls()
    }
}
