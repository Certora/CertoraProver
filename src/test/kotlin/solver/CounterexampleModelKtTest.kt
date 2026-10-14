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

package solver

import config.Config
import config.ConfigScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import report.DummyLiveStatsReporter
import scene.IScene
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.state.TACValue
import vc.data.state.asSimpleTACState
import vc.data.tacexprutil.evaluators.TACExprInterpreter
import verifier.TACVerifier

class CounterexampleModelKtTest : TACBuilderAuxiliaries() {

    private val mockScene = mockk<IScene>()
    init { every { mockScene.getContracts() } returns listOf() }

    @Suppress("Unused")
    private val z3Scope =
        ConfigScope(Config.SolverProgramChoice, arrayOf(SolverConfig.z3.def))
    private val cvc5Scope =
        ConfigScope(Config.SolverProgramChoice, arrayOf(SolverConfig.cvc5.def))
    @Suppress("Unused")
    private val yicesScope =
        ConfigScope(Config.SolverProgramChoice, arrayOf(SolverConfig.yices.def))
    private val solverChoiceConfigScopes =
        listOfNotNull(
            // z3Scope, // deactivating for now, until z3 gives correct models
            cvc5Scope,
//             yicesScope, // yices does not return models for uninterpreted functions on get-value
        )


    /**
     * Check that model values that contain whole functions (or arrays) are backtranslated correctly.
     */
    @Test
    fun toTacAssignment_MapDefinitions01() {
        runBlocking {
            solverChoiceConfigScopes.forEach { configScope ->
                configScope.use {
                    val m1 = mkBmVar("m1")
                    val m2 = mkBmVar("m2")
                    val m3 = mkBmVar("m3")
                    val ctp = TACProgramBuilder {
                        // m1 = mapdefinition([ x -> 1 ])
                        m1 assign TACExpr.MapDefinition(
                            listOf(bv256Var("x_1").asSym()),
                            number(1),
                            Tag.ByteMap
                        )
                        // m2 = store(m1 2 3)
                        m2 assign Store(m1.asSym(), number(2), v = number(3))
                        // m3 = store(m2 2 4)
                        m3 assign Store(m2.asSym(), number(2), v = number(4))
                        // just so optimizations don't erase everything
                        x assign Eq(m3[a].asTACExpr, Zero)
                        assert(x)
                    }.code
                    val res = TACVerifier.verify(mockScene, ctp, DummyLiveStatsReporter)
                    val modelAsTACState = res.examplesInfo.head.model.asSimpleTACState
                    val selectM2at2 =
                        try {
                            TACExprInterpreter.eval(modelAsTACState, txf { Select(m2.asSym(), number(2)) })
                        } catch (e : TACExprInterpreter.ValueMissingFromStateException) {
                            println("we failed at parsing an UF value")
                            null
                        }
                    val selectM3at2 =
                        try {
                            TACExprInterpreter.eval(modelAsTACState, txf { Select(m3.asSym(), number(2)) })
                        } catch (e: TACExprInterpreter.ValueMissingFromStateException) {
                            println("we failed at parsing an UF value")
                            null
                        }

                    assertEquals(TACValue.valueOf(3), selectM2at2)
                    assertEquals(TACValue.valueOf(4), selectM3at2)
                }
            }
        }
    }


}
