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

package wasm.analysis.intervals

import datastructures.stdcollections.*
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.TACCommandGraph
import analysis.numeric.*
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntExpressionInterpreter
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntAbstractInterpreter
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntQualifierManager
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import analysis.numeric.simplequalifiedint.StatementInterpreter
import com.certora.collect.*
import evm.MASK_SIZE
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACKeyword
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.asTACSymbol
import wasm.host.soroban.Val
import java.math.BigInteger

class IntervalInterpreter(
    val graph: TACCommandGraph,
): SimpleQualifiedIntAbstractInterpreter<SimpleQualifiedIntState>(SimpleQualifiedIntQualifierManager(graph.cache.gvn)) {
    override fun postStep(stepped: SimpleQualifiedIntState, l: LTACCmd): SimpleQualifiedIntState = stepped

    override fun project(l: LTACCmd, w: SimpleQualifiedIntState): SimpleQualifiedIntState = w

    private val expressionInterpreter = object : SimpleQualifiedIntExpressionInterpreter<SimpleQualifiedIntState>() {

        // Override to handle wasm-specific expressions: in particular
        // soroban vecs/maps store Vals, which are 64 bit values, which we can
        // use as an invariant here.
        override fun stepExpression(
            lhs: TACSymbol.Var,
            rhs: TACExpr,
            toStep: SimpleQualifiedIntState,
            input: SimpleQualifiedIntState,
            whole: SimpleQualifiedIntState,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): SimpleQualifiedIntState {
            return super.stepExpression(lhs, rhs, toStep, input, whole, l).let { s ->
                when (rhs) {
                    is TACExpr.Select -> {
                        val selInfo = rhs.extractMultiDimSelectInfo()
                        if (selInfo.base !is TACExpr.Sym.Var || !selInfo.base.s.meta.containsKey(TACMeta.SOROBAN_ENV)) {
                            return s
                        }
                        when (selInfo.base.s) {
                            // Our host implementation guarantees
                            // these only store vals (which are 64 bits)
                            TACKeyword.SOROBAN_VEC_MAPPINGS.toVar(),
                            TACKeyword.SOROBAN_MAP_MAPPINGS.toVar() -> {
                                val v = IntValue(BigInteger.ZERO, MASK_SIZE(8 * Val.sizeInBytes))
                                toStep.copy(toStep.s.updateEntry(lhs, v) { old, n ->
                                    old?.copy(x = old.x.withLowerBound(n.x.lb).withUpperBound(n.x.ub))
                                        ?: SimpleQualifiedInt(n)
                                })
                            }

                            else -> s
                        }
                    }

                    else -> s
                }
            }
        }
    }

    override val statementInterpreter = object : StatementInterpreter<SimpleQualifiedIntState>(
        expressionInterpreter = expressionInterpreter,
        pathSemantics = pathSemantics
    ) {
        override fun stepCommand(l: LTACCmd, toStep: SimpleQualifiedIntState, input: SimpleQualifiedIntState, whole: SimpleQualifiedIntState): SimpleQualifiedIntState =
            super.stepCommand(l, toStep, input, whole).let { s ->
                when (val cmd = l.cmd) {
                    is TACCmd.Simple.SummaryCmd -> {
                        when (val summ = cmd.summ) {
                            is Val.CheckValid -> {
                                val result = SimpleQualifiedInt(
                                    x = IntValue(BigInteger.ZERO, BigInteger.ONE),
                                    qual = setOf(
                                        SimpleIntQualifier.Condition(
                                            op1 = summ.v,
                                            condition = ConditionQualifier.Condition.LT,
                                            op2 = BigInteger.TWO.pow(8 * Val.sizeInBytes).asTACSymbol(),
                                        )
                                    )
                                )
                                return SimpleQualifiedIntState(s.s + (summ.out to result))
                            }
                        }
                        return s
                    }

                    else -> s
                }
            }
    }
}
