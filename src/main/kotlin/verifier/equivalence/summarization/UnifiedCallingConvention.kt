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
import analysis.ip.InternalFuncArg
import analysis.ip.InternalFuncRet
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Calling convention used for both Solidity and Vyper (although the Vyper support is currently missing).
 * [args] is the list of symbols which hold the arguments to the functions and [rets] are the symbols
 * that hold the return values. these may, or may not be canonicalized; a non-canonical calling convention
 * is transformed via [withArgsAndReturns] to canonicalize it.
 */
data class UnifiedCallingConvention(
    val args: List<InternalFuncArg>,
    val rets: List<InternalFuncRet>
) : PureFunctionExtraction.CallingConvention<UnifiedCallingConvention> {
    override val argSymbols: List<TACSymbol>
        get() = args.map { it.s }
    override val exitVars: List<TACSymbol.Var>
        get() = rets.map { it.s }

    override fun withArgsAndReturns(args: List<TACSymbol>, rets: List<TACSymbol.Var>): UnifiedCallingConvention {
        require(this.args.size == args.size && this.rets.size == rets.size)
        return UnifiedCallingConvention(
            this.args.zip(args) { a, sym ->
                a.copy(s = sym)
            },
            this.rets.zip(rets) { r, sym ->
                r.copy(s = sym)
            }
        )
    }

    override fun bindOutputs(): Pair<List<TACSymbol.Var>, CommandWithRequiredDecls<TACCmd.Simple>> {
        return rets.map { it.s } to CommandWithRequiredDecls()
    }
}
