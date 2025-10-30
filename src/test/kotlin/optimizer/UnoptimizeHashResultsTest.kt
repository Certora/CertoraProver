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

package optimizer

import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.maybeNarrow
import com.certora.collect.treapSetOf
import compiler.applyKeccak
import datastructures.LinkedArrayHashMap
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.BlockIdentifier
import tac.NBId
import tac.Tag
import tac.generation.assign
import utils.interpret
import utils.permutations
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACKeyword
import vc.data.TACSymbol
import vc.data.TACSymbolTable
import vc.data.asTACExpr
import vc.data.asTACSymbol
import vc.data.tacexprutil.asConstOrNull
import java.math.BigInteger

class UnoptimizeHashResultsTest {
    /*
     *  MEM0 := const value
     *  x := hash(MEM0 value)
     *  y := z + x
     *  ... storage[y] ...
     */
    val x = TACKeyword.TMP_DETERMINISTIC(tag = Tag.Bit256, suffix = "x")
    val loc = TACKeyword.TMP_DETERMINISTIC(tag = Tag.Bit256, suffix = "!loc")
    val i = TACKeyword.TMP_DETERMINISTIC(tag = Tag.Bit256, suffix = "i")
    val t = TACKeyword.TMP_DETERMINISTIC(tag = Tag.Bit256, suffix = "t")


    val entry = BlockIdentifier(0,0,0,0,0,0)
    val empty = CoreTACProgram(
        code = mapOf<NBId, List<TACCmd.Simple>>(
            entry to listOf(TACCmd.Simple.ReturnCmd(0.asTACSymbol(), 0.asTACSymbol()))
        ),
        blockgraph = LinkedArrayHashMap(mapOf(entry to treapSetOf())),
        name = "test",
        symbolTable = TACSymbolTable(TACKeyword.MEMORY.toVar(), x, loc, i, t),
        procedures = setOf(),
        check = true,
        entryBlock = entry,
    )

    @Test
    fun testArrayNoOffset() {
        val tac = mergeMany(
//            assignHavoc(i),
            assign(TACKeyword.MEM0.toVar()) { 0x1234.asTACExpr },
            assign(x) { applyKeccak(0x1234.toBigInteger()).asTACExpr },
            assign(loc) { x add i },
            CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = t,
                    loc = loc,
                    base = TACKeyword.STORAGE.toVar(),
                )
            )
        ).merge(t, TACKeyword.STORAGE.toVar())

        runTest(tac, mapOf(i to 42.toBigInteger()))
    }

    @Test
    fun testArrayConstOffset() {
        val permutations = listOf<TACExpr>(x.asSym(), i.asSym(), 0x1.asTACExpr).permutations()

        for ((a, b, c) in permutations) {
            val tac = mergeMany(
//                assignHavoc(i),
                assign(TACKeyword.MEM0.toVar()) { 0x1234.asTACExpr },
                assign(x) { applyKeccak(0x1234.toBigInteger()).asTACExpr },
                assign(loc) { a add b add c },
                CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.WordLoad(
                        lhs = t,
                        loc = loc,
                        base = TACKeyword.STORAGE.toVar(),
                    )
                )
            ).merge(t, TACKeyword.STORAGE.toVar())

            runTest(tac, mapOf(i to 42.toBigInteger()))
        }
    }

    private fun runTest(tac: CommandWithRequiredDecls<TACCmd.Simple>, initial: Map<TACSymbol.Var, BigInteger>) {
        val test = empty.patching {
            it.addBefore(CmdPointer(entry, 0), tac.cmds)
            it.addVarDecls(tac.varDecls)
        }

        val done = UnoptimizeHashResults(test).doWork()

        // Make sure we've actually inserted a hash command
        val match = done.ltacStream().filter {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd>()?.cmd?.let {
                it.args.singleOrNull()?.asConstOrNull == 0x1234.toBigInteger()
            } == true
        }.toList()

        Assertions.assertTrue(match.isNotEmpty())

        val noXform = initial.toMutableMap()
        val xForm = initial.toMutableMap()
        test.analysisCache.graph.elab(entry)
            .commands
            .takeWhile { it.cmd !is TACCmd.Simple.AssigningCmd.WordLoad }
            .interpret(noXform)
        done.analysisCache.graph.elab(entry)
            .commands
            .takeWhile { it.cmd !is TACCmd.Simple.AssigningCmd.WordLoad }
            .interpret(xForm)

        // Check that the xform is meaning preserving
        Assertions.assertEquals(noXform[loc]!!, xForm[loc]!!)
    }

}
