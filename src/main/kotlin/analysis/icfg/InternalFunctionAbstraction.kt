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

package analysis.icfg

import analysis.ip.InternalFunctionExitAnnot
import analysis.ip.InternalFunctionStartAnnot
import tac.MetaKey
import vc.data.TACSymbol

/**
 * An interface describing how to recognize internal function start/end locations, and how to extract and process
 * those internal functions argument/return data. The start and ends of functions are identified by annotations
 * with the metakey [startMeta] and [endMeta].
 *
 * In particular, the type of internal function starts, [START] provides some information about it's argument encoded by the type
 * [ARG_DATA], which is extracted from [START] via [projectArgs].
 *
 * [END] contains information about the return data locations, expressed as [RET_DATA].
 * However, [RET_DATA] can be decomposed into a list of [TACSymbol.Var] and a "shape" object.
 * This decomposition is implemented by [decomposeReturnInfo]; internally it should extract [RET_DATA] from
 * [END] and then decompose it into this pair representation. NB that there is no operation to project [RET_DATA]
 * from [END] directly as it is actually not needed. [recomposeReturnInfo] can take this list of [TACSymbol.Var]
 * and the [RET_SHAPE] and recompose it into its [RET_DATA].
 *
 * In other words, suppose there was a `projectReturn` function with signature `(END) -> RET_DATA`. Then the following identity
 * must hold for all E:
 * ```
 * projectRet(E) == recomposeReturnInfo(*decomposeReturnInfo(E))
 * ```
 * where `*` represents variadic expansion of the tuple returned by [decomposeReturnInfo] into argument positions.
 *
 * Further, it is expected (but not checked) that if `decomposeReturnInfo(E) = (L, S)`, for any `L'` where
 * `|L| = |L'|`, `recomposeReturnInfo(L', S)` should succeed. In other words, recomposing return shape information
 * with a different list of variables with the same length should yield a valid [RET_DATA]. Intuitively, this operation
 * should "swap out" the variables in `L` with the variables in `L'`, and is used by the [GenericInternalSummarizer] to
 * remap and canonicalize return variables without understanding the exact shape of return data.
 */
interface InternalFunctionAbstraction<START : InternalFunctionStartAnnot, END : InternalFunctionExitAnnot, ARG_DATA, RET_SHAPE, RET_DATA> {
    // Abstract annotation access
    val startMeta: MetaKey<START>
    val endMeta: MetaKey<END>

    // Abstract argument decomposition/recomposition
    fun projectArgs(annotation: START): ARG_DATA

    // Abstract return value decomposition/recomposition
    fun decomposeReturnInfo(annotation: END): Pair<List<TACSymbol.Var>, RET_SHAPE>
    fun recomposeReturnInfo(vars: List<TACSymbol.Var>, shape: RET_SHAPE): RET_DATA
}
