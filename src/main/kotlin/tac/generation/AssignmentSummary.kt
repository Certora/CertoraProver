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

package tac.generation

import analysis.CommandWithRequiredDecls.Companion.withDecls
import datastructures.stdcollections.*
import tac.*
import utils.*
import vc.data.*

/**
    Base class for assigning summaries; takes care of some bookkeeping details.
*/
@KSerializable
abstract class AssignmentSummary : TACSummary, AssigningSummary {
    fun toCmd(meta: MetaMap = MetaMap()) = listOf(TACCmd.Simple.SummaryCmd(this, meta)).withDecls(mustWriteVars + mayWriteVars)

    abstract val inputs: List<TACSymbol>

    override val variables get() = inputs.filterIsInstance<TACSymbol.Var>().toSet()

    // `transformSymbols` requires `as? TACSymbol.Var` checks for any TACSymbol-typed symbols.  Let's make that
    // easier.
    protected class Transformer(val f: (TACSymbol.Var) -> TACSymbol.Var) {
        operator fun invoke(v: TACSymbol.Var) = f(v)
        operator fun invoke(s: TACSymbol) = (s as? TACSymbol.Var)?.let(f) ?: s
    }
    abstract protected fun transformSymbols(f: Transformer): AssignmentSummary
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = transformSymbols(Transformer(f))
}
