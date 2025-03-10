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

import config.Config
import config.ConfigScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import report.DummyLiveStatsReporter
import scene.IScene
import smt.CounterExampleDiversifier
import smt.MultipleCEXStrategyEnum
import solver.SolverConfig
import solver.SolverResult
import vc.data.state.TACValue
import verifier.TACVerifier
import verifier.Verifier

/**
 * Tests that check some of the conversions that happen during [TACExpr.toLExpression].
 */
class ToLExpressionTest : TACBuilderAuxiliaries() {
    private val mockScene = mockk<IScene>()
    init { every { mockScene.getContracts() } returns listOf() }

    val iaConfig = ConfigScope()

    @OptIn(Config.Smt.PreciseBitwiseOpsOption::class)
    val bvConfig = ConfigScope()
        .extend(Config.Smt.PreciseBitwiseOps, true)
        .extend(Config.SolverProgramChoice, arrayOf(SolverConfig.bitwuzla.default))


    /**
     * div by zero and variations; note that semantics follows what's implemented in [EVMOps]. That file also has
     * references to the Ethereum implementation. (Might revisit when looking at these for other, non-EVM, pipelines.)
     */

    /** Check that [TACExpr.BinOp.IntDiv] and [TACExpr.BinOp.IntMod] will return a nondeterministic value when dividing
     * by 0. */
    @Test
    fun intDivAndIntModByZeroNondet() {
        val mod: TACExprFact.(TACExpr, TACExpr) -> TACExpr = { o1, o2 -> TACExpr.BinOp.IntMod(o1, o2) }
        val div: TACExprFact.(TACExpr, TACExpr) -> TACExpr = { o1, o2 -> TACExpr.BinOp.IntDiv(o1, o2) }
        for (operator in setOf(mod, div)) {
            for (baseScope in setOf(iaConfig, bvConfig)) {
                println(" -- running config $baseScope --")
                runBlocking {
                    baseScope.extend(CounterExampleDiversifier::targetGenerator) { ctp, original ->
                        splitZeroTargets(ctp, original)
                    }.extend(Config.MultipleCEXStrategy, MultipleCEXStrategyEnum.BASIC).use {
                        val magicNumber = 123456789
                        val ctp = TACProgramBuilder {
                            i assign operator(jS, kS)
                            // what we really want to express that for any n, `satisfy i == n` is not violated, this
                            // formula, `i != <magic number>` is an approximation ..
                            u assign LOr(LNot(Eq(kS, Zero)), LNot(Eq(iS, number(magicNumber))))
                            assert(u)
                        }.code
                        val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
                        println("got result ${res.finalResult}")
                        assertEquals(SolverResult.SAT, res.finalResult)
                        println(res.examplesInfo.first().model)
                        assertEquals(TACValue.Companion.valueOf(0), res.examplesInfo.first().model.tacAssignments[k])
                        assertEquals(
                            TACValue.Companion.valueOf(magicNumber),
                            res.examplesInfo.first().model.tacAssignments[i]
                        )
                    }
                }
            }
        }
    }

    /** Check that Div, Mod, SMod by 0 return 0. */
    @Test
    fun divByZeroIsZero() {
        val mod: TACExprFact.(TACExpr, TACExpr) -> TACExpr = { o1, o2 -> TACExpr.BinOp.Mod(o1, o2) }
        val smod: TACExprFact.(TACExpr, TACExpr) -> TACExpr = { o1, o2 -> TACExpr.BinOp.SMod(o1, o2) }
        val div: TACExprFact.(TACExpr, TACExpr) -> TACExpr = { o1, o2 -> TACExpr.BinOp.Div(o1, o2) }
        for (operator in setOf(mod, smod, div)) {
            for (baseScope in setOf(iaConfig, bvConfig)) {
                println(" -- running config $baseScope --")
                runBlocking {
                    baseScope.extend(CounterExampleDiversifier::targetGenerator) { ctp, original ->
                        splitZeroTargets(ctp, original)
                    }.extend(Config.MultipleCEXStrategy, MultipleCEXStrategyEnum.BASIC).use {
                        val ctp = TACProgramBuilder {
                            a assign Div(bS, cS)
                            // what we really want to express that for any n, `satisfy i == n` is not violated, this is an approximation ..
                            u assign LOr(LNot(Eq(cS, Zero)), Eq(aS, Zero))
                            assert(u)
                        }.code
                        val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
                        println("got result ${res.finalResult}")
                        dbgWrongSat(res)
                        assertEquals(SolverResult.UNSAT, res.finalResult)
                    }
                }
            }
        }
    }

    private fun dbgWrongSat(res: Verifier.VerifierResult) {
        if (res.finalResult == SolverResult.SAT) {
            println("unexpectedly got model:")
            println(res.examplesInfo.firstOrNull()?.model)
        }
    }
}
