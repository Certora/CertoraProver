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

package verifier.equivalence.summarization

import analysis.CommandWithRequiredDecls
import analysis.MutableCommandWithRequiredDecls
import tac.CallId
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Calling convention used for both Solidity.
 * [args] is the list of symbols which hold the arguments to the functions and [rets] are the symbols
 * that hold the return values.
 */
data class UnifiedCallingConvention(
    val args: List<TACSymbol>,
    val rets: List<TACSymbol.Var>
) : PureFunctionExtraction.CallingConvention<UnifiedCallingConvention, Int, Int> {
    override fun decomposeArgs(): Pair<List<TACSymbol>, Int> {
        return args to args.size
    }

    override fun decomposeRet(): Pair<List<TACSymbol.Var>, Int> {
        return rets to rets.size
    }

    override fun bindOutputs(): Pair<List<TACSymbol.Var>, CommandWithRequiredDecls<TACCmd.Simple>> {
        return rets to CommandWithRequiredDecls()
    }

    override fun bindSymbolicArgs(
        callId: CallId,
        argumentVar: (Int) -> TACSymbol.Var
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val binding = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        var argCounter = 0
        for(a in args) {
            if(a !is TACSymbol.Var) {
                continue
            }
            val input = argumentVar(argCounter++)
            binding.extend(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = a.at(callId),
                    rhs = input
                )
            )
            binding.extend(a.at(callId), input)
        }
        return binding.toCommandWithRequiredDecls()
    }
}
