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

package vc.data

import analysis.CommandWithRequiredDecls
import datastructures.stdcollections.*
import tac.Tag

class PCBinder : OpcodeEnvironmentBinder {
    override fun bindEnvironmentParam(cmd: TACCmd.EVM): Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Simple>> {
        val pcVar = TACKeyword.TMP(Tag.Bit256, "!pc")
        val pcValue = cmd.metaSrcInfo?.pc?.asTACExpr ?: TACExpr.zeroExpr
        return pcVar to CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = pcVar,
                rhs = pcValue
            )
        ), pcVar)
    }
}
