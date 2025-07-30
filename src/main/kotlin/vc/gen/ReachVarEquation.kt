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
import tac.MetaMap
import tac.NBId
import tac.StartBlock
import vc.data.*
import vc.data.tacexprutil.TACExprFreeVarsCollector

/** Defining equation of a "reach var", a concept from the Leino-style control flow encoding that we use. Reach vars are
 * introduced in `DSAToSSA`. Both types of verification condition that we create, [LeinoLExpVC] and [PathEnumLExpVC],
 * require that these definitions are added to the final formula.
 *
 * @param toLExpAction allows passing an action that should be performed whenever some TAC expression is translated to
 *  an LExpression -- current use case: this updates the "terms of interest", which is easiest to do at translation time
 * */
data class ReachVarEquation(
    val reachIdent: TACExpr.Sym.Var,
    val rhsDef: TACExpr,
    val toLExpAction: (ToLExpression, LExpression) -> Unit,
) : ToLExpression {
    private fun reachIdentLExp(conv: ToLExpression.Conv) =
        conv(reachIdent) as LExpression.Identifier

    fun reachIdentLExp(lxf: LExpressionFactory, symbolTable: TACSymbolTable) =
        ToLExpression(reachIdent, lxf, symbolTable, action = toLExpAction) as LExpression.Identifier

    private fun rhsDefLExp(conv: ToLExpression.Conv): LExpression =
        conv(rhsDef)

    fun rhsDefLExp(lxf: LExpressionFactory, symbolTable: TACSymbolTable): LExpression =
        ToLExpression(rhsDef, lxf, symbolTable, action = toLExpAction)


    init {
        check(reachIdent.s !in TACExprFreeVarsCollector.getFreeVars(rhsDef))
        { "the reach var that is being defined ($reachIdent) must not occur in its definition ($rhsDef), but does so" }
    }

    companion object {

        /**
         * Returns the [ReachVarEquation]s for each basic block in the program [g].
         */
        fun getBlockToReachVarEq(
            g: TACCommandGraph,
            txf: TACExprFactTypeChecked,
            toLExpAction: (ToLExpression, LExpression) -> Unit
        ): MutableMap<NBId, ReachVarEquation> {
            return mutableMapOf<NBId, ReachVarEquation>().let { blockToReachVarEq ->
                g.blocks.map { it.id }
                    .associateWithTo(blockToReachVarEq) { blockIdentifier ->
                        ReachVarEquation(
                            g.pred(blockIdentifier),
                            txf,
                            g.pathConditions,
                            blockIdentifier,
                            toLExpAction,
                        )
                    }
            }
        }

        operator fun invoke(
            predsOfBlock: Set<NBId>,
            txf: TACExprFactTypeChecked,
            pathConditions: Map<NBId, Map<NBId, PathCondition>>,
            blockId: NBId,
            toLExpAction: (ToLExpression, LExpression) -> Unit
        ): ReachVarEquation {
            if (blockId == StartBlock) {
                // the start block is reachable by convention
                return ReachVarEquation(LeinoWP.genReachabilityVar(blockId).asSym(), txf.True, toLExpAction)
            }
            val rhsDef = predsOfBlock.map { pred ->
                val pathCondFromPred =
                    pathConditions[pred]?.let { pathConditionsFromPred ->
                        if (pathConditionsFromPred.size == 1 &&
                            pathConditionsFromPred.values.first() == PathCondition.TRUE &&
                            blockId !in pathConditionsFromPred
                        ) {
                            throw UnsupportedOperationException("Invalid predecessor $pred of $blockId, pred cannot reach the block")
                        } else {
                            pathConditionsFromPred[blockId]
                                ?: error("No path condition for $blockId in $pathConditionsFromPred even though it is not trivially TRUE or FALSE")
                        }
                    } ?: error(
                        "Deeply broken relationship - there should be a path condition for $pred -> $blockId in $pathConditions"
                    )
                val predReachIdent = LeinoWP.genReachabilityVar(pred).asSym()
                when (pathCondFromPred) {
                    PathCondition.TRUE -> {
                        predReachIdent
                    }

                    is PathCondition.EqZero,
                    is PathCondition.NonZero -> {
                        txf.and(predReachIdent, pathCondFromPred.getAsExpression())
                    }

                    is PathCondition.Summary -> throw UnsupportedOperationException(
                        "Cannot handle summary ${pathCondFromPred.s} in SMT"
                    )
                }
            }.let { disjuncts: List<TACExpr> ->
                check(disjuncts.isNotEmpty()) { "block $blockId is not the startBlock, but has no predecessors" }
                txf.or(*disjuncts.toTypedArray())
            }
            return ReachVarEquation(LeinoWP.genReachabilityVar(blockId).asSym(), rhsDef, toLExpAction)
        }
    }

    override fun toLExpression(
        conv: ToLExpression.Conv,
        meta: MetaMap?,
    ): LExpression =
        conv.lxf.eq(
            reachIdentLExp(conv),
            rhsDefLExp(conv),
            meta = meta,
        )
}
