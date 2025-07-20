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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACKeyword
import vc.data.tacexprutil.ExprUnfolder.Companion.unfold

internal class ExprUnfolderTest : TACBuilderAuxiliaries() {

    private fun newVar(tag: Tag) = TACKeyword.TMP(tag, "!test").toUnique("!")

    @Test
    fun simpleTest() {
        val orig = txf { BWXOr(BWAnd(BWNot(aS), bS), BWOr(cS, aS)) }

        val (expr, cmds) = unfold(orig, varGenerator = ::newVar)
        fun rhs(index : Int) = (cmds[index] as AssignExpCmd).rhs
        fun lhs(index : Int) = (cmds[index] as AssignExpCmd).lhs.asSym()
        assertEquals(txf { BWNot(aS) }, rhs(0))
        assertEquals(txf { BWAnd(lhs(0), bS) }, rhs(1))
        assertEquals(txf { BWOr(cS, aS) }, rhs(2))
        assertEquals(txf { BWXOr(lhs(1), lhs(2)) }, expr)
    }
}
