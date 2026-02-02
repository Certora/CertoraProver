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

@file:Suppress("DEPRECATION") // will be cleared in CVL Rewrite

package analysis

import vc.data.TACSymbol
import analysis.icfg.ExpressionSimplifier
import analysis.opt.ConstantPropagator
import analysis.type.TypeRewriter
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.Tag
import testing.ttl.TACMockLanguage
import utils.*
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import vc.data.*
import java.math.BigInteger

class ExpressionSimplifierTests : TACBuilderAuxiliaries() {
    @Test
    fun constantPropagationTest() {
        val (g, res) = this.analyze {
            L1024 = 1
            L1023 = 2
            tagNext("FINAL")
            L1023 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1023")
        Assertions.assertEquals(BigInteger.valueOf(3).asTACSymbol().asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest2() {
        val (g, res) = this.analyze {
            L1024 = 1
            L1023 = L1024
            tagNext("FINAL")
            L1023 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1023")
        Assertions.assertEquals(BigInteger.TWO.asTACSymbol().asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest3() {
        val (g, res) = this.analyze {
            L1024 = `*`
            L1023 = L1024
            tagNext("FINAL")
            L1023 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1023")
        Assertions.assertEquals(lhs.asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest4() {
        val (g, res) = this.analyze {
            L1024 = `*`
            L1023 = L1024
            tagNext("FINAL")
            L1023 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1024+L1024")
        Assertions.assertEquals(
            lhs.asSym()
            /*TACExpr.Vec.Add(
                TACSymbol.Var.stackVar(1024).asSym(),
                TACSymbol.Var.stackVar(1024).asSym()
            )*/,
            res.simplify(lhs, lastWrite, false)
        )
    }

    @Test
    fun constantPropagationTest5() {
        val (g, res) = this.analyze {
            L1024 = `*`
            L1023 = L1024
            L1024 = `*`
            tagNext("FINAL")
            L1023 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1023")
        Assertions.assertEquals(lhs.asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest6() {
        val (g, res) = this.analyze {
            L1024 = 1
            L1023 = "L1024 + 0x1"
            L1024 = "L1023 > 0x3"
            tagNext("FINAL")
            L1022 = "L1023"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1022")
        Assertions.assertEquals(BigInteger.TWO.asTACSymbol().asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest7() {
        val (g, res) = this.analyze {
            L1024 = 1
            L1023 = "L1024 + 0x1"
            L1024 = "L1023 * 0x3"
            tagNext("FINAL")
            L1022 = "L1023 + L1024"
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1022")
        Assertions.assertEquals(BigInteger.valueOf(8).asTACSymbol().asSym(), res.simplify(lhs, lastWrite, false))
    }

    @Test
    fun constantPropagationTest8() {
        val (g, res) = this.analyze {
            L1021 = `*`
            L1024 = L1021
            L1023 = L1024
            L1024 = `*`
            tagNext("FINAL")
            L1021 = L1023
            L1022 = L1021
        }
        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1022")
        Assertions.assertEquals(TACSymbol.Var.stackVar(1021).asSym(), res.simplify(lhs, lastWrite, false))

        val blocks = g.blocks.map { it.id to it.commands.map { c -> c.cmd } }.toMap()
        ConstantPropagator.propagateConstants(
            CoreTACProgram(
                blocks,
                g.toBlockGraph(),
                "dummy",
                TACSymbolTable.withVars(TACUtils.tagsFromBlocks(blocks)),
                UfAxioms.empty(),
                IProcedural.empty()
            )
        ).let { propagated ->
            Assertions.assertEquals(
                TACSymbol.Var.stackVar(1021).asSym(),
                (propagated.code.entries.first().value.last() as TACCmd.Simple.AssigningCmd.AssignExpCmd).rhs
            )
        }
    }

    @Test
    fun constantPropagationTest9() {
        val (g, res) = this.analyze {
            L1024 = `*`
            L1023 = `*`
            L1024 = "L1023 - L1024"
            L1023 = L1024
            L1022 = "L1024 == 0x0"
            L1024 = "L1022 * 0x8fc"
            L1021 = L1024
            L1024 = L1023
            L1023 = L1021
            tagNext("FINAL")
            L1020 = L1024
        }

        val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
        val lhs = g.elab(lastWrite).cmd.getLhs()
        assertNotNull(lhs, "Should fetch L1020")
        Assertions.assertEquals(TACSymbol.Var.stackVar(1020).asSym(), res.simplify(lhs, lastWrite, false))

        val blocks = g.blocks
            .map {
                it.id to it.commands.map { c ->
                    c.cmd
                }
            }.toMap()
        ConstantPropagator.propagateConstants(
            CoreTACProgram(
                blocks,
                g.toBlockGraph(),
                "dummy",
                TACSymbolTable.withVars(TACUtils.tagsFromBlocks(blocks)),
                UfAxioms.empty(),
                IProcedural.empty(),
                    check = false
            ).let {
                TACTypeChecker.annotateExpressions(it).bind {
                    TypeRewriter.boolify(it).let { TypeRewriter.peepholeReplacements(it) }.lift()
                }.resultOrNull()!!
            }
        ).let { propagated ->
            Assertions.assertEquals(
                TACSymbol.Var.stackVar(1024).asSym(),
                (propagated.code.entries.first().value.last() as TACCmd.Simple.AssigningCmd.AssignExpCmd).rhs
            )
        }
    }
    @Test
    fun exprSimplifierITETest() {
        val tests = listOf(
            "0x1" to 1234,
            "0x0" to 4567,
            "(0x0 == 0x0)" to 1234,
            "(0x1 == 0x0)" to 4567,
            "Ite((0x0 == 0x0) true false)" to 1234,
            "Ite((0x1 == 0x0) true false)" to 4567,
        )
        for ((t, r) in tests) {
            val (g, res) = this.analyze {
                L1021 = 1234
                L1022 = 4567
                L1024 = 0
                L1020 = t
                L1024 = "Ite(L1020:bv256 L1021:bv256 L1022:bv256)"
                L1023 = L1021
                tagNext("FINAL")
                L1020 = L1024
            }

            val lastWrite = TACMockUtils.commandWithTagOrFail(g, "FINAL")
            val lhs = g.elab(lastWrite).cmd.getLhs()
            val expected = r.asTACExpr
            assertNotNull(lhs, "Should fetch $expected")
            Assertions.assertEquals(expected, res.simplify(lhs, lastWrite, false))
        }
    }

    private fun analyze(f: TACMockLanguage.StmtBuilderScope.() -> Unit): Pair<TACCommandGraph, ExpressionSimplifier> {
        val g = TACMockLanguage.make {
            freePointer `=` 0x80
            this.f()
        }
        return g to ExpressionSimplifier(g)
    }

    @Test
    fun stackOverflowBug() {
        val prog = TACProgramBuilder {
            label("label")
            a assign safeMathNarrow(IntAdd(aS, 1.asTACExpr), Tag.Bit256)
        }
        val (ptr, cmd) = prog.lcmd("label")
        ExpressionSimplifier(prog.code.analysisCache.graph).simplify(
            (cmd as TACCmd.Simple.AssigningCmd.AssignExpCmd).rhs,
            ptr,
            false
        )
    }
}
