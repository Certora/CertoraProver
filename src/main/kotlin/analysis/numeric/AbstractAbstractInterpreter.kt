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

package analysis.numeric

import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.PathCondition
import analysis.narrow
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * An abstract starting point for an abstract interpreter. Steps the subset
 * of a whole-analysis state [W] given by [S]
 *
 * The basic implementation fills in the framework of step, which is implemented as:
 * + s' = [project] (w) (where w represents the entire abstract state, and is of type [W])
 * + s'' = [killLHS] (s') (to allow lazily closing abstract domains when information is about to be lost)
 * + s''' = [statementInterpreter]  . [IStatementInterpreter.stepCommand] (s'') (to model the effects of the statement
 * + return [postStep] (s''') (to allow any final cleanups, sanity checks, closing, etc.)
 *
 * In addition, the [propagate] function simply forwards to [IPathSemantics.propagate] in the [pathSemantics]
 *
 * Thus, most of the work is done by the two abstract fields, [statementInterpreter] and [pathSemantics]
 */
abstract class AbstractAbstractInterpreter<in W, S> : IAbstractInterpreter<W, S> {
    abstract val statementInterpreter: IStatementInterpreter<S, W>
    abstract val pathSemantics : IPathSemantics<S, W>

    override fun step(l: LTACCmd, w: W) : S {
        val input = project(l, w)
        val toStep = if(l.cmd is TACCmd.Simple.AssigningCmd) {
            this.killLHS(l.cmd.lhs, input, w, l.narrow())
        } else {
            input
        }
        val stepped = statementInterpreter.stepCommand(l, toStep, input, w)
        return this.postStep(stepped, l)
    }

    abstract fun postStep(stepped: S, l: LTACCmd): S

    protected open fun killLHS(lhs: TACSymbol.Var, s: S, w: W, narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>) : S {
        return s
    }

    abstract fun project(l: LTACCmd, w: W): S

    override fun propagate(l: LTACCmd, w: W, pathCondition: PathCondition): S? {
        val s = project(l, w)
        return pathSemantics.propagate(l, s, w, pathCondition)
    }
}
