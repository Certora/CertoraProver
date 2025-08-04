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

package vc.gen

import analysis.PathCondition
import analysis.TACCommandGraph
import datastructures.stdcollections.*
import smt.solverscript.LExpressionFactory
import tac.NBId
import tac.Tag
import vc.data.LExpression
import vc.data.ToLExpression
import vc.data.TACSymbolTable
import vc.data.lexpression.META_TOPLVL_PATHCOND
import vc.data.lexpression.MetaTopLevelDefOfPathCondition

/**
 * Simple utility to transform the edge conditions from a TAC program to [LExpression].
 */
class EdgeConditions(
    private val g: TACCommandGraph,
    private val lExprFact: LExpressionFactory,
    private val symbolTable: TACSymbolTable = g.symbolTable,
) {

    /* srcBlock -> (dstBlock -> edgeCondition) */
    private val edgeConditions: MutableMap<NBId, MutableMap<NBId, BlockBody>> =
        // Initialize the edge conditions of each srcBlock with the original path conditions of that srcBlock
        mutableMapOf<NBId, MutableMap<NBId, BlockBody>>().let { outerMutMap ->
            g.blocks.map { it.id }.associateWithTo(outerMutMap) { source ->
                val innerMutMap = mutableMapOf<NBId, BlockBody>()
                g.pathConditionsOf(source).mapValuesTo(innerMutMap) { (target, pathCondition) ->
                    pathCondition.toEdgeCondition(
                        source,
                        target,
                        lExprFact,
                        symbolTable
                    )
                }
                innerMutMap
            }
        }

    private fun pathConditionToId(cond: PathCondition): LExpression.Identifier {
        val pathCondName = when (cond) {
            PathCondition.TRUE -> "TRUE"
            is PathCondition.EqZero -> "eqZero_${cond.v.smtRep}"
            is PathCondition.NonZero -> "nonZero_${cond.v.smtRep}"
            is PathCondition.Summary -> error("This PathCondition.Summary ($cond) should not appear " +
                "at this stage anymore (i.e. during VC generation).")
        }
        return lExprFact.const("path_cond_${pathCondName}", Tag.Bool, null)
    }

    /**
     * Converts a [PathCondition] to an [BlockBody].
     * Since the [PathCondition] does not know which control flow edge it is attached to, we need
     * to pass the source and target of the edge as the parameters [source] and [target].
     */
    private fun PathCondition.toEdgeCondition(
        source: NBId,
        target: NBId,
        lExprFact: LExpressionFactory,
        symbolTable: TACSymbolTable
    ): BlockBody {
        val asLExp = ToLExpression(this, lExprFact, symbolTable, null)
        return if (LeinoWP.topLevelStatementDefinitions) {
            val defId = pathConditionToId(this)
            BlockBody(
                defId, definitions = listOf(
                    lExprFact {
                        (defId eq asLExp).addMeta(META_TOPLVL_PATHCOND, MetaTopLevelDefOfPathCondition(source, target))
                    }
                )
            )
        } else {
            BlockBody(asLExp, definitions = listOf())
        }
    }

    fun edgeConditionsOf(src: NBId): Map<NBId, BlockBody> = edgeConditions[src]!!.mapValues { it.value }
}
