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

import com.certora.collect.*
import datastructures.stdcollections.plus
import datastructures.stdcollections.toSet
import spec.cvlast.QualifiedMethodSignature
import tac.MetaKey
import utils.KSerializable
import vc.data.TACSymbol
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * Summary inserted by [SharedPureSummarization] to include the summarized functions
 * into the call trace. See the [verifier.equivalence.tracing.BufferTraceInstrumentation] for details.
 */
@KSerializable
data class CommonPureInternalFunction(
    val argSymbols: List<TACSymbol.Var>,
    val qualifiedMethodSignature: QualifiedMethodSignature,
    val rets: List<TACSymbol.Var>
) : ScalarEquivalenceSummary {
    override val variables: Set<TACSymbol.Var>
        get() = argSymbols.toSet() + rets
    override val annotationDesc: String
        get() = "Replaced call to common internal function ${qualifiedMethodSignature.qualifiedMethodName}${argSymbols.joinToString(", ", prefix = "(", postfix = ")")}"

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CommonPureInternalFunction {
        return CommonPureInternalFunction(
            argSymbols = argSymbols.map(f),
            qualifiedMethodSignature = qualifiedMethodSignature,
            rets = rets.map(f)
        )
    }

    companion object {
        val ANNOTATION_META = MetaKey<CommonPureInternalFunction>("pure.function.annotation")
    }

    override val sort: BufferTraceInstrumentation.TraceEventSort
        get() = BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL
    override val asContext: BufferTraceInstrumentation.Context
        get() = BufferTraceInstrumentation.Context.InternalCall(
            qualifiedMethodSignature,
            argSymbols.toTreapList()
        )
}
