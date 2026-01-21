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

import analysis.ip.*
import tac.MetaKey
import vc.data.TACSymbol

interface SolidityInternalFunctions : InternalFunctionAbstraction<
    InternalFuncStartAnnotation,
    InternalFuncExitAnnotation,
    List<InternalFuncArg>,
    List<SolidityInternalFunctions.InternalRetShape>,
    List<InternalFuncRet>> {

    data class InternalRetShape(
        val location: InternalFuncValueLocation?,
        val offset: Int
    )

    override val startMeta: MetaKey<InternalFuncStartAnnotation> get() = INTERNAL_FUNC_START
    override val endMeta: MetaKey<InternalFuncExitAnnotation> get() = INTERNAL_FUNC_EXIT

    override fun projectArgs(annotation: InternalFuncStartAnnotation): List<InternalFuncArg> {
        return annotation.args
    }

    override fun decomposeReturnInfo(annotation: InternalFuncExitAnnotation): Pair<List<TACSymbol.Var>, List<InternalRetShape>> {
        return annotation.rets.map {
            it.s to InternalRetShape(it.location, it.offset)
        }.unzip()
    }

    override fun recomposeReturnInfo(vars: List<TACSymbol.Var>, shape: List<InternalRetShape>): List<InternalFuncRet> {
        return vars.zip(shape) { eV, eShape ->
            InternalFuncRet(s = eV, offset = eShape.offset, location = eShape.location)
        }
    }
}
