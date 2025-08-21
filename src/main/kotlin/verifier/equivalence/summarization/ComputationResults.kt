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

import utils.*
import vc.data.TACSummary
import vc.data.TACSymbol
import verifier.equivalence.tracing.BufferTraceInstrumentation

@KSerializable
data class ComputationResults(
    val symbols: List<TACSymbol>
) : ScalarEquivalenceSummary, AmbiSerializable {
    override val sort: BufferTraceInstrumentation.TraceEventSort
        get() = BufferTraceInstrumentation.TraceEventSort.RESULT
    override val asContext: BufferTraceInstrumentation.Context
        get() = BufferTraceInstrumentation.Context.ResultMarker(
            results = symbols
        )
    override val variables: Set<TACSymbol.Var>
        get() = symbols.mapNotNullToSet { it as? TACSymbol.Var }
    override val annotationDesc: String
        get() = "Exit code with results: $symbols"

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TACSummary {
        return ComputationResults(
            symbols.map { sym -> (sym as? TACSymbol.Var)?.let(f) ?: sym }
        )
    }
}
