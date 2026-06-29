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
import datastructures.stdcollections.*
import statistics.toSDFeatureKey
import utils.*
import vc.data.*

class AllVariablesDefAnalysis(val graph: TACCommandGraph) : IAllVariablesDefAnalysis {
    private val def = graph.cache.def
    private val boundVarDefinitions: MutableMap<TACSymbol.Var, CmdPointer> = mutableMapOf()

    init {
        recordDefAnalysisPreprocessing("allVars".toSDFeatureKey()) {
            mapBoundVariablesToDefined()
        }
    }

    private fun addBoundVariables(v: TACSymbol.Var, ptr: CmdPointer) {
        if (boundVarDefinitions.containsKey(v)) {
            throw IllegalStateException("Variable $v is already defined as bound variable in ${boundVarDefinitions[v]}.")
        }

        boundVarDefinitions[v] = ptr
    }

    private fun iterateExpression(expr: TACExpr, ptr: CmdPointer) {
        if (expr is TACExpr.QuantifiedFormula) {
            for (qVar in expr.quantifiedVars) {
                addBoundVariables(qVar, ptr)
            }
        }

        if (expr is TACExpr.MapDefinition) {
            for ((s) in expr.defParams) {
                addBoundVariables(s, ptr)
            }
        }

        for (exp in expr.getOperands()) {
            iterateExpression(exp, ptr)
        }
    }

    private fun mapBoundVariablesToDefined() {
        for (block in graph.blocks) {
            for (command in block.commands) {
                val assignment = command.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>() ?: continue
                iterateExpression(assignment.cmd.rhs, assignment.ptr)
            }
        }
    }

    override fun defSitesOf(v: TACSymbol.Var, pointer: CmdPointer): Set<CmdPointer> {
        return boundVarDefinitions[v]?.let { setOf(it) } ?: def.defSitesOf(v, pointer)
    }
}
