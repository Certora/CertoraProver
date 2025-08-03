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

package analysis.numeric.simplequalifiedint

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.numeric.AbstractStatementInterpreter
import analysis.numeric.BoundedQIntPropagationSemantics
import analysis.numeric.SimpleIntQualifier
import com.certora.collect.minus
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol

class UnreachableState(val s: String, val loc: CmdPointer) : Exception(s)

open class StatementInterpreter<W>(
    val pathSemantics: BoundedQIntPropagationSemantics<SimpleQualifiedInt, SimpleIntQualifier, SimpleQualifiedIntState, W>,
    val expressionInterpreter: SimpleQualifiedIntExpressionInterpreter<W>
): AbstractStatementInterpreter<SimpleQualifiedIntState, W>() {
        override fun forget(lhs: TACSymbol.Var, toStep: SimpleQualifiedIntState, input: SimpleQualifiedIntState, whole: W, l: LTACCmd) =
            toStep.copy(toStep.s - lhs)

        override fun stepCommand(l: LTACCmd, toStep: SimpleQualifiedIntState, input: SimpleQualifiedIntState, whole: W): SimpleQualifiedIntState {
            return super.stepCommand(l, toStep, input, whole).let { s ->
                when (val cmd = l.cmd) {
                    is TACCmd.Simple.AssumeCmd -> {
                        if (cmd.cond !is TACSymbol.Var) {
                            return s
                        }
                        pathSemantics.propagateTrue(cmd.cond, s, whole, l) ?:
                        throw UnreachableState("Unreachable after assuming condition at $l", l.ptr)
                    }

                    else -> {
                        s
                    }
                }
            }
        }

        override fun stepExpression(
            lhs: TACSymbol.Var,
            rhs: TACExpr,
            toStep: SimpleQualifiedIntState,
            input: SimpleQualifiedIntState,
            whole: W,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ) = expressionInterpreter.stepExpression(lhs, rhs, toStep, input, whole, l)
}
