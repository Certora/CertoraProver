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

package move

import datastructures.stdcollections.*
import allocator.Allocator
import analysis.CmdPointer
import com.certora.collect.treapSetOf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.NBId
import tac.Tag
import vc.data.BlockGraph
import vc.data.TACCmd
import vc.data.TACKeyword
import vc.data.TACSymbol
import vc.data.TACSymbolTable
import vc.data.asTACExpr
import java.math.BigInteger

class MoveLiveVariableAnalysisTest {

    private fun NBId.pos(p: Int): CmdPointer = CmdPointer(block = this, pos = p)

    private fun loc(i: Int, t: Tag) = TACSymbol.Var("l_${i}", tag = t)
    private fun ref(i: Int, t: MoveType.Value) = TACSymbol.Var("ptr_${i}", tag = MoveTag.Ref(t))
    private fun structName(mod: String, nm: String) = MoveStructName(MoveModuleName(0, mod), nm)

    @Test
    fun testPrimLocLive() {
        val l0 = loc(0, Tag.Bit256)
        val r0 = ref(0, MoveType.U256)
        val tmp = TACKeyword.TMP(Tag.Bit256)
        val x = TACKeyword.TMP(Tag.Bit256)

        val block = listOf(
            TACCmd.Move.BorrowLocCmd(r0, l0),
            //l0 live here
            TACCmd.Move.ReadRefCmd(x, r0),
            // r0 live here
            TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, 1234.asTACExpr),
            // r0 live here
            TACCmd.Move.WriteRefCmd(r0, tmp),
        )

        val b = Allocator.getNBId()
        val graph = MoveTACCommandGraph(
            blockGraph = BlockGraph(b to treapSetOf()),
            code = mapOf(b to block),
            symbolTable = TACSymbolTable(l0, r0, tmp, x)
        )
        val lva = graph.cache.lva
        Assertions.assertTrue(l0 in lva.liveVariablesBefore(b.pos(1)))
        Assertions.assertTrue(l0 in lva.liveVariablesBefore(b.pos(2)))
        Assertions.assertTrue(r0 in lva.liveVariablesBefore(b.pos(3)))
    }

    @Test
    fun testVecLocLive() {
        val l0 = loc(0, MoveTag.Vec(MoveType.U256))
        val r0 = ref(0, MoveType.Vector(MoveType.U256))
        val r1 = ref(0, MoveType.U256)
        val idx = TACKeyword.TMP(Tag.Bit256)
        val tmp = TACKeyword.TMP(Tag.Bit256)
        val x = TACKeyword.TMP(Tag.Bit256)

        val block = listOf(
            TACCmd.Move.BorrowLocCmd(r0, l0),
            TACCmd.Move.VecBorrowCmd(r1, r0, idx),
            TACCmd.Move.ReadRefCmd(x, r1),
            TACCmd.Move.VecPopBackCmd(tmp, r0),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, 1234.asTACExpr),
            TACCmd.Move.VecPushBackCmd(r0, tmp),
            TACCmd.Move.VecPackCmd(l0, listOf())
        )

        val b = Allocator.getNBId()
        val graph = MoveTACCommandGraph(
            blockGraph = BlockGraph(b to treapSetOf()),
            code = mapOf(b to block),
            symbolTable = TACSymbolTable(l0, r0, r1, idx, tmp, x)
        )
        val lva = graph.cache.lva
        for (i in (1..5)) {
           Assertions.assertTrue(lva.isLiveBefore(b.pos(i), l0))
        }
    }

    @Test
    fun testStructLive() {
        val ty = MoveType.Struct(
            structName("test", "s"),
            listOf(),
            listOf(
                MoveType.Struct.Field("x", MoveType.U256),
                MoveType.Struct.Field("y", MoveType.U256)
            )
        )
        val l0 = loc(0, MoveTag.Struct(ty))
        val r0 = ref(0, ty)
        val r1 = ref(0, MoveType.U256)
        val idx = TACKeyword.TMP(Tag.Bit256)
        val tmp = TACKeyword.TMP(Tag.Bit256)
        val x = TACKeyword.TMP(Tag.Bit256)

        val block = listOf(
            TACCmd.Move.BorrowLocCmd(r0, l0),
            TACCmd.Move.BorrowFieldCmd(r1, r0, 1),
            // l0, r0 live here
            TACCmd.Move.ReadRefCmd(x, r1),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, 1234.asTACExpr),
            // l0, r0 live here
            TACCmd.Move.WriteRefCmd(r1, tmp),
            TACCmd.Move.PackStructCmd(l0, listOf(x, x))
        )

        val b = Allocator.getNBId()
        val graph = MoveTACCommandGraph(
            blockGraph = BlockGraph(b to treapSetOf()),
            code = mapOf(b to block),
            symbolTable = TACSymbolTable(l0, r0, r1, idx, tmp, x)
        )
        val lva = graph.cache.lva
        for (i in 1..4) {
            Assertions.assertTrue(lva.isLiveBefore(b.pos(i), l0))
        }
    }
}
