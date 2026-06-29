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

package report.globalstate

import analysis.TACCommandGraph
import datastructures.stdcollections.*
import report.calltrace.CallTrace
import report.calltrace.formatter.FormatterType.Companion.toFormatterType
import report.calltrace.sarif.Sarif
import solver.CounterexampleModel
import tac.NBId
import vc.data.*

/**
 * A unit of the [CallTrace]. Represents the variables during the flow of the CounterExample TACProgram chosen by the SMT.
 */
internal class VariablesState(
    private val model: CounterexampleModel,
    graph: TACCommandGraph,
    reachableBlocks: Set<NBId>,
) {
    private val variableMap: MutableMap<TACSymbol.Var, DisplaySymbolWrapper> = mutableMapOf()

    /**
     * Vars that are only ever assigned in blocks the model did not take. The merge ITE that `DSAToSSA` emits in a
     * common successor references per-predecessor temps `tmp_i` defined in each predecessor; when one predecessor is
     * off the model's execution path (e.g. a reverted branch joined back via catch), that `tmp_i` will not be visited
     * by the on-path walk and thus never registered in [variableMap]. The corresponding reachability flag picks the
     * other arm at runtime, so an off-path operand should not poison the classification of the whole ITE.
     */
    private val definedOnlyOffPath: Set<TACSymbol.Var> = run {
        val onPath = mutableSetOf<TACSymbol.Var>()
        val offPath = mutableSetOf<TACSymbol.Var>()
        graph.commands.forEach { (ptr, cmd) ->
            val lhs = cmd.getLhs() ?: return@forEach
            if (ptr.block in reachableBlocks) {
                onPath += lhs
            } else {
                offPath += lhs
            }
        }
        offPath - onPath
    }

    fun computationalTypeForRHS(rhs: Set<TACSymbol.Var>) : ComputationalTypes = rhs.fold(ComputationalTypes.CONCRETE) { ret, symbol ->
        when(variableMap[symbol]?.computationalType) {
            null -> {
                if (symbol in definedOnlyOffPath) {
                    return@fold ComputationalTypes.HAVOC_DEPENDENT
                } else {
                    return ComputationalTypes.UNKNOWN
                }
            }
            ComputationalTypes.UNKNOWN -> { return ComputationalTypes.UNKNOWN }
            ComputationalTypes.DONT_CARE -> { throw IllegalStateException("Usage of DONT CARE symbol $symbol") }
            ComputationalTypes.HAVOC, ComputationalTypes.HAVOC_DEPENDENT -> { return@fold ComputationalTypes.HAVOC_DEPENDENT }
            ComputationalTypes.CONCRETE -> { return@fold ret }
        }
    }

    fun computationalTypeForRHS(rhs: TACSymbol.Var) = computationalTypeForRHS(setOf(rhs))

    private fun computationalTypeForTACCommand(assign: TACCmd.Simple.AssigningCmd) = when(assign) {
        is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> ComputationalTypes.HAVOC
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> computationalTypeForRHS(assign.getFreeVarsOfRhs())
        else -> ComputationalTypes.UNKNOWN
    }

    fun handleAssignments(stmt: TACCmd.Simple.AssigningCmd) {
        val value = model.valueAsTACValue(stmt.lhs)
        val computationalType = computationalTypeForTACCommand(stmt)

        val formatterType =
            stmt.lhs.meta.find(TACMeta.CVL_TYPE)
            ?.toFormatterType()

        val range = stmt.metaSrcInfo?.getSourceDetails()?.range

        variableMap[stmt.lhs] = DisplaySymbolWrapper(
            Sarif.fromPlainStringUnchecked(stmt.lhs.namePrefix),
            value,
            computationalType,
            formatterType,
            range
        )
    }

    operator fun set(sym: TACSymbol.Var, dsw: DisplaySymbolWrapper) {
        variableMap[sym] = dsw
    }

    operator fun get(sym: TACSymbol.Var) = variableMap[sym]
}
