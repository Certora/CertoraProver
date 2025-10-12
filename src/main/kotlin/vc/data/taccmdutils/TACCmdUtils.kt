/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package vc.data.taccmdutils

import vc.data.TACCmd

object TACCmdUtils {
    val TACCmd.Simple.rhsExpr get() = when(this) {
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> rhs
        is TACCmd.Simple.AssumeExpCmd -> cond
        else -> null
    }
}
