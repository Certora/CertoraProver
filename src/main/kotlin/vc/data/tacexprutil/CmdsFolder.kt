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

package vc.data.tacexprutil

import datastructures.stdcollections.*
import tac.NBId
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.TACExprUtils.SubstitutorVar

object CmdsFolder {


    /**
     * takes [start] as if it is appears after all of [cmds], and creates the expression the resulting
     * from substituting all variables as they are assigned in [cmds].
     *
     * If the folding reaches an assignment that is not a [TACExpr], or there is some double assignment to a
     * variable we care about, this returns null. (the latter case can be fixed, but is not useful at the moment).
     */
    fun fold(start: TACExpr, cmds: List<TACCmd.Simple>, dontFold : Set<TACSymbol.Var> = emptySet()): TACExpr? {
        val varsToResolve = start.getFreeVars().toMutableSet()
        varsToResolve -= dontFold

        val relevantCmds = cmds.asReversed().filter { cmd ->
            if (cmd !is TACCmd.Simple.AssigningCmd || cmd.lhs !in varsToResolve) {
                return@filter false
            }
            if (cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd) {
                return@filter false
            }
            if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                // can't fold other types of assignments
                return null
            }
            varsToResolve -= cmd.lhs
            varsToResolve += cmd.getFreeVarsOfRhs()
            varsToResolve -= dontFold
            true
        }.reversed()

        val exprMap = mutableMapOf<TACExpr.Sym.Var, TACExpr>()
        fun substitute(e : TACExpr) = SubstitutorVar(exprMap).transform(e)

        relevantCmds.forEach { cmd ->
            check(cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd)
            if (exprMap.put(cmd.lhs.asSym(), substitute(cmd.rhs)) != null) {
                // double assignment
                return null
            }
        }
        return substitute(start)
    }

    /** fold according to the commands in [nbid] as if [start] is after all of them */
    fun fold(code: CoreTACProgram, start: TACExpr, nbid: NBId): TACExpr? =
        fold(start, code.code[nbid]!!)
}
