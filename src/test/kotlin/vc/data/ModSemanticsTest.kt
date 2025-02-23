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

package vc.data

import evm.twoToThe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import report.DummyLiveStatsReporter
import scene.IScene
import smtlibutils.cmdprocessor.CmdProcessor
import smtlibutils.cmdprocessor.InteractingCmdProcessor
import smtlibutils.cmdprocessor.process
import smtlibutils.data.*
import solver.SolverConfig
import solver.SolverResult
import tac.Tag
import utils.*
import verifier.TACVerifier

fun execSMT(cmds: List<Cmd>, expect: List<String>? = null) =
    runBlocking {
        val solver = SolverConfig.z3.def
        InteractingCmdProcessor.fromCommand(
            solver.fullName,
            solver.getCommandline(),
            CmdProcessor.Options.Default.copy(printSuccess = true)
        ).use { cmdProc ->
            cmdProc.process(Cmd.SetOption(":print-success", "true"))
            cmds.flatMap {
                runBlocking {
                    cmdProc.processResult(it).toList()
                }
            }
        }
    }.letIf(expect != null) {
        assertEquals(expect, it)
        it
    }

class SmtFactory {

    val True get() = SmtExp.BoolLiteral.True
    val False get() = SmtExp.BoolLiteral.False

    val bv16 = Sort.bitVecSort(16)

    fun lit(i: Long) = SmtExp.IntLiteral(i.toBigInteger())
    fun bvLit(i: Long, width: Int = bv16.getBitVecSortWidth()) = i.toBigInteger()
        .letIf(i < 0) { twoToThe(width) + it }
        .let { SmtExp.BitvectorLiteral(it, width) }
    fun id(i: String, s: Sort = Sort.IntSort) =
        SmtExp.QualIdentifier(Identifier.Simple(i), null, s)

    fun apply(fs: SmtFunctionSymbol, vararg args: SmtExp) =
        SmtExp.Apply(fs, args.toList(), fs.rank.resultSort)

    fun not(exp: SmtExp) =
        apply(SmtIntpFunctionSymbol.Core.LNot, exp)
    fun and(vararg exp: SmtExp) =
        apply(SmtIntpFunctionSymbol.Core.LAnd, *exp)
    fun or(vararg exp: SmtExp) =
        apply(SmtIntpFunctionSymbol.Core.LOr, *exp)
    infix fun SmtExp.eq(other: SmtExp) =
        apply(SmtIntpFunctionSymbol.Core.Eq(), this, other)

    infix fun SmtExp.lt(other: SmtExp) =
        apply(SmtIntpFunctionSymbol.IRA.Lt(), this, other)
    infix fun SmtExp.mod(other: SmtExp) =
        apply(SmtIntpFunctionSymbol.Ints.Mod, this, other)

    infix fun SmtExp.bvumod(other: SmtExp) =
        apply(SmtIntpFunctionSymbol.BV.BinOp.BvURem(), this, other)
    infix fun SmtExp.bvsmod(other: SmtExp) =
        apply(SmtIntpFunctionSymbol.BV.BinOp.BvSRem(), this, other)

    fun setupNIA(vararg vars: String) = arrayOf(
            Cmd.SetLogic("QF_NIA"),
            *vars.map { Cmd.DeclareFun(it, listOf(), Sort.IntSort) }.toTypedArray()
        )
    fun setupBV(vararg vars: String) = arrayOf(
        Cmd.SetLogic("QF_BV"),
        *vars.map { Cmd.DeclareFun(it, listOf(), bv16) }.toTypedArray()
    )

    companion object {
        operator fun <T> invoke(f: SmtFactory.() -> T) = f(SmtFactory())
    }
}

class ModSemanticsTest : TACBuilderAuxiliaries() {
    private val mockScene = mockk<IScene>()
    init { every { mockScene.getContracts() } returns listOf() }

    val examples_bvumod = listOf<List<Long>>(
        listOf(0,0,0), listOf(0,13,0), listOf(13,0,0), listOf(13,13,0),listOf(12,5,2),
    )
    val examples_bvsmod = listOf<List<Long>>(
        listOf(0,0,0), listOf(0,13,0), listOf(13,0,0), listOf(13,13,0),
        listOf(12,5,2), listOf(12,-5,2), listOf(-12,5,-2), listOf(-12,-5,-2),
    )

    @Test
    fun testSMT_intmod() {
        SmtFactory {
            // IntMod is never negative
            execSMT(
                listOf(
                    *setupNIA("x", "y", "z"),
                    Cmd.Assert(not(id("y") eq lit(0))),
                    Cmd.Assert(id("z") eq (id("x") mod id("y"))),
                    Cmd.Assert(id("z") lt lit(0)),
                    Cmd.CheckSat()
                ),
                listOf("unsat")
            )
        }
    }

    @Test
    fun testSMT_bvsmod() {
        SmtFactory {
            val x = id("x", bv16)
            val y = id("y", bv16)
            val z = id("z", bv16)

            execSMT(
                listOf(
                    *setupBV("x", "y", "z"),
                    Cmd.Assert(not(y eq bvLit(0))),
                    Cmd.Assert(z eq (x bvsmod y)),
                    // for any of the examples, z has a wrong value
                    Cmd.Assert(or(
                        *examples_bvsmod.map { (a,b,c) ->
                            and(x eq bvLit(a), y eq bvLit(b), not(z eq bvLit(c)))
                        }.toTypedArray()
                    )),
                    Cmd.CheckSat()
                ),
                listOf("unsat")
            )
        }
    }

    @Test
    fun testSMT_bvumod() {
        SmtFactory {
            val x = id("x", bv16)
            val y = id("y", bv16)
            val z = id("z", bv16)

            execSMT(
                listOf(
                    *setupBV("x", "y", "z"),
                    Cmd.Assert(not(y eq bvLit(0))),
                    Cmd.Assert(z eq (x bvumod y)),
                    // for any of the examples, z has a wrong value
                    Cmd.Assert(or(
                        *examples_bvumod.map { (a,b,c) ->
                            and(x eq bvLit(a), y eq bvLit(b), not(z eq bvLit(c)))
                        }.toTypedArray()
                    )),
                    Cmd.CheckSat()
                ),
                listOf("unsat")
            )
        }
    }

    @Test
    fun testTAC_intmod() {
        runBlocking {
            val ctp = TACProgramBuilder {
                // k := i % j
                assumeExp(LNot(Eq(j.asSym(), Zero)))
                k assign IntMod(i.asSym(), j.asSym())
                x assign Ge(k.asSym(), Zero)
                assert(x.asSym())
            }.code
            val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
            assert(res.finalResult == SolverResult.UNSAT)
        }
    }

    @Test
    fun testTAC_bvsmod() {
        fun bvLit(i: Long) = i.toBigInteger()
            .letIf(i < 0) { Tag.Bit256.modulus + it }
            .let { TACSymbol.Const(it, Tag.Bit256).asSym() }
        runBlocking {
            examples_bvsmod.forEach { (av, bv, cv) ->
                val ctp = TACProgramBuilder {
                    // c := a % b
                    assumeExp(Eq(a.asSym(), bvLit(av)))
                    assumeExp(Eq(b.asSym(), bvLit(bv)))
                    c assign SMod(a.asSym(), b.asSym())
                    x assign Ge(c.asSym(), bvLit(cv))
                    assert(x.asSym())
                }.code
                val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
                assert(res.finalResult == SolverResult.UNSAT)
            }
        }
    }

    @Test
    fun testTAC_bvumod() {
        fun bvLit(i: Long) = i.toBigInteger()
            .letIf(i < 0) { Tag.Bit256.modulus + it }
            .let { TACSymbol.Const(it, Tag.Bit256).asSym() }
        runBlocking {
            examples_bvumod.forEach { (av, bv, cv) ->
                val ctp = TACProgramBuilder {
                    // c := a % b
                    assumeExp(Eq(a.asSym(), bvLit(av)))
                    assumeExp(Eq(b.asSym(), bvLit(bv)))
                    c assign Mod(a.asSym(), b.asSym())
                    x assign Ge(c.asSym(), bvLit(cv))
                    assert(x.asSym())
                }.code
                val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
                assert(res.finalResult == SolverResult.UNSAT)
            }
        }
    }
}
