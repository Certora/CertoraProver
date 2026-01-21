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

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACCommandGraph
import datastructures.stdcollections.singleOrNull
import utils.generateSequenceAfter
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol

object SimpleAssignmentChain {
    /**
     * Go backwards from [ptr] in the assignment chain starting at [v], looking for the first command and current variable
     * that is equivalent to [v] (via the assignment chain) where [lookFor] returns true.
     * The checking stops if the block has more than one predecessor.
     */
    fun lookIn(
        g: TACCommandGraph,
        ptr: CmdPointer,
        v: TACSymbol.Var,
        lookFor: (LTACCmd, TACSymbol.Var) -> Boolean
    ): CmdPointer? {
        var currentV = v
        return generateSequenceAfter(ptr) { g.pred(it).singleOrNull() }
            .firstOrNull {
                val lcmd = g.elab(it)
                val cmd = lcmd.cmd
                val lhs = cmd.getLhs()
                when {
                    lookFor(lcmd, currentV) -> true
                    lhs != currentV -> false
                    cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.Sym.Var -> {
                        currentV = cmd.rhs.s
                        false
                    }

                    else -> return null
                }
            }
    }
}
