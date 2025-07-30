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

package analysis.dataflow

import analysis.*
import tac.NBId
import vc.data.AnalysisCache
import vc.data.TACSymbol
import java.util.stream.Collectors
import java.util.stream.Stream

object VariableLookupComputation : AnalysisCache.Key<TACCommandGraph, Map<NBId, Set<TACSymbol.Var>>> {
    override fun createCached(graph: TACCommandGraph) = compute(
        graph.blocks.stream().flatMap {
            it.commands.stream()
        }
    )

    fun compute(s: Stream<LTACCmd>) : Map<NBId, Set<TACSymbol.Var>> {
        return s.map {
            val vars = mutableSetOf<TACSymbol.Var>()
            it.cmd.getLhs()?.let(vars::add)
            vars.addAll(it.cmd.getFreeVarsOfRhs())
            it.ptr.block to vars
        }.collect(Collectors.groupingBy({ it.first }, Collectors.flatMapping({
            it.second.stream()
        }, Collectors.toSet())))
    }
}
