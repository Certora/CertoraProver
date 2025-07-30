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

package analysis

import vc.data.AnalysisCache
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

class MustBeConstantAnalysis(
    private val graph: TACCommandGraph,
    private val wrapped: NonTrivialDefAnalysis = NonTrivialDefAnalysis(graph)
) : MustBeAnalysis(graph, wrapped) {

    companion object : AnalysisCache.Key<TACCommandGraph, MustBeConstantAnalysis> {
        override fun createCached(graph: TACCommandGraph): MustBeConstantAnalysis {
            return MustBeConstantAnalysis(graph)
        }

    }

    fun mustBeConstantAt(where: CmdPointer, v: TACSymbol.Var): BigInteger? {
        return mustBeAt(where, v, { lc ->
            if (lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lc.cmd.rhs is TACExpr.Sym.Const) {
                lc.cmd.rhs.s.value
            } else {
                null
            }
        }, { a, b ->
            if (a != b) {
                null
            } else {
                a
            }
        })
    }

    fun mustBeConstantAt(where: CmdPointer, v: TACSymbol): BigInteger? {
        return if(v is TACSymbol.Const) {
            v.value
        } else {
            this.mustBeConstantAt(where, v as TACSymbol.Var)
        }
    }
}
