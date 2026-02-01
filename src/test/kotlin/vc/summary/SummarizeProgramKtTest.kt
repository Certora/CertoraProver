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

package vc.summary

import analysis.TACCommandGraph
import loaders.WithTACSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import tac.Tag
import testing.ttl.TACMockLanguage
import vc.data.TACCmd
import vc.data.TACSymbol

internal class SummarizeProgramKtTest : WithTACSource {

    private fun constructProjectAndPruneSummaryComputer(prog: TACCommandGraph): ComputeTACSummaryProjectAndPrune =
        ComputeTACSummaryProjectAndPrune(
            setOf(TACSymbol.Var("res", Tag.Bit256)),
            prog.sinks,
            ComputeTACSummaryProjectAndPrune.Settings(trackAssumes = true, markReturnedVarForTracking = false)
        )

    @Test
    fun test01() {
        val prog = TACCommandGraph(this.loadTACProgramResource("vc/summary/test01.tac"))
        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                ComputeTACSummaryTransFormula()
                // TODO: reactivate full summary features
//            ComputeTACSummaryProjectAndPrune(
//                setOf(TACSymbol.Var("trackedVar", Tag.Bit256)),
//                prog.sinks,
//                ComputeTACSummaryProjectAndPrune.Settings(trackAssumes = true, markReturnedVarForTracking = false)
//            )
            )
        }
        // summary should be something like "trackedVar = false"

        assertTrue(true)
    }

    @Test
    fun test02() {
        val prog = TACCommandGraph(
            this.loadTACProgramResource("vc/summary/test02.tac")
        )
        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                ComputeTACSummaryProjectAndPrune(
                    setOf(TACSymbol.Var("trackedVar", Tag.Bool)),
                    prog.sinks,
                    ComputeTACSummaryProjectAndPrune.Settings(trackAssumes = true, markReturnedVarForTracking = false)
                )
            )
        }
        // summary should be something like "trackedVar = true"

        assertTrue(true)
    }

    @Test
    fun test03() {
        val prog = TACCommandGraph(
            this.loadTACProgramResource("vc/summary/test03.tac")
        )
        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                ComputeTACSummaryProjectAndPrune(
                    setOf(TACSymbol.Var("trackedVar", Tag.Bool)),
                    prog.sinks,
                    ComputeTACSummaryProjectAndPrune.Settings(trackAssumes = true, markReturnedVarForTracking = false)
                )
            )
        }
        // summary should be something like "trackedVar = true \/ trackedVar = false" (plus path conditions, since trackAssumes is set)

        assertTrue(true)
    }

    @Test
    fun testMapi01() {
        val prog = TACCommandGraph(
            this.loadTACProgramResource("vc/summary/mapi-1.tac")
        )
        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                constructProjectAndPruneSummaryComputer(prog)
            )
        }
        assertTrue(true)

    }

    @Test
    fun testMapi02() {
        val prog = TACCommandGraph(
            this.loadTACProgramResource("vc/summary/mapi-2.tac")
        )
        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                constructProjectAndPruneSummaryComputer(prog)
            )
        }
        assertTrue(true)
    }


    @Test
    fun testSmallProg01() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACMockLanguage.make {
            L1024 = "0x20"
            `if`(L(1023)) {
                L1022 = "0x30"
            } `else` {
                L1022 = "0x40"
            }
            L1021 = "0x50"
        }

        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(
                prog,
                summaryComputer()
            )
        }
        // summary should be something like "trackedVar = false"

        assertTrue(true)
    }


    @Test
    fun testSmallProg02() {
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACCommandGraph(this.loadTACProgramResource("vc/summary/tfTest01.tac"))

        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(prog, summaryComputer())
        }
        // this test is about the assertions in the code -- so it succeeds if it reaches the end without errors
        assertTrue(true)
    }

    @Test
    fun testSmallProg03() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog1 = TACMockLanguage.make {
            L1024 = "0x20"
            L1022 = L1023
        }

        val prog2 = TACMockLanguage.make {
            L1021 = L1023
        }

        val summary1 =
            SummarizeProgram.summarizeTAC(prog1, summaryComputer())

        val summary2 =
            SummarizeProgram.summarizeTAC(prog2, summaryComputer())

        assertDoesNotThrow {
            summaryComputer().sequentialComposition(summary1, summary2)
        }

        // this test is about the assertions in the code -- so it succeeds if it reaches the end without errors
        assertTrue(true)
    }

    /**
     * Example for "overlapping" outVars in sequentially composed transformulas
     *   -- i.e. when there is an x such that x in l.outVars and x in r.outVars, but not x in r.inVars
     * */
    @Test
    fun testSmallProg04() {
//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }
        val prog1 = TACMockLanguage.make {
            L1024 = "0x20"
            L1024 = "0x30"
        }

        assertDoesNotThrow {
            SummarizeProgram.summarizeTAC(prog1, summaryComputer())
        }

        // this test is about the assertions in the code -- so it succeeds if it reaches the end without errors
        assertTrue(true)
    }

    /**
     * Example for "overlapping" inVars in sequentially composed transformulas
     *   -- i.e. when there is an x such that x in l.inVars and x in r.inVars, but not x in l.outVars
     * */
    @Test
    fun testSmallProg05() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog1 = TACMockLanguage.make {
            L1023 = "0x20"
            L1024 = L1023
        }
        val prog2 = TACMockLanguage.make {
            L1024 = L1023
        }

        val summary1 =
            SummarizeProgram.summarizeTAC(prog1, summaryComputer())
        val summary2 =
            SummarizeProgram.summarizeTAC(prog2, summaryComputer())
        val summary = summaryComputer().sequentialComposition(summary1, summary2)

        assertTrue(summary.transFormula.inVars.keys.map { it.sym }.isEmpty())

        assertTrue(
            summary.transFormula.outVars.keys.map { it.sym }.toSet().containsAll(
                prog1.commands.map { it.cmd }.filterIsInstance<TACCmd.Simple.AssigningCmd>().map { it.lhs }.toSet() +
                        prog2.commands.map { it.cmd }.filterIsInstance<TACCmd.Simple.AssigningCmd>().map { it.lhs }
                            .toSet()
            )
        ) { "outVars in summary transformula should match the assigned vars in the program" }

        assertTrue(true)
    }

    /** only outvars no invars */
    @Test
    fun testSmallProg06() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACMockLanguage.make {
            L1024 = "0x20"
            L1023 = "0x30"
            L1022 = "0x40"
            L1021 = "0x50"
        }

        val summary =
            SummarizeProgram.summarizeTAC(prog, summaryComputer())
        // summary should be something like "trackedVar = false"

        assertTrue(summary.transFormula.inVars.keys.map { it.sym }.isEmpty())

        assertTrue(assignedVarsSubsetEqOutVars(summary, prog))
        { "all assigned vars in the program must be outVars" }

        assertTrue(true)
    }

    /**  outvars and one invar */
    @Test
    fun testSmallProg07() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACMockLanguage.make {
            L1024 = "0x20"
            L1023 = L1024
            L1022 = L1023
            L1021 = L1022
        }

        val summary = SummarizeProgram.summarizeTAC(
            prog,
            summaryComputer()
        )
        // summary should be something like "trackedVar = false"

        assertTrue(summary.transFormula.inVars.keys.map { it.sym }.isEmpty())

        assertTrue(assignedVarsSubsetEqOutVars(summary, prog))
        { "all assigned vars in the program must be outVars" }

        assertTrue(true)
    }

    @Test
    fun testSmallProg08() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACMockLanguage.make {
            L1024 = "L1024 + L1023"
            L1024 = "L1024 + L1023"
        }

        val summary = SummarizeProgram.summarizeTAC(
            prog,
            summaryComputer()
        )
        // summary should be something like "trackedVar = false"

        assertTrue(
            summary.transFormula.inVars.keys.map { it.sym }.toSet() ==
                    setOf(
                        TACSymbol.Var("L1023", Tag.Bit256),
                        TACSymbol.Var("L1024", Tag.Bit256)
                    )
        )
        assertTrue(assignedVarsSubsetEqOutVars(summary, prog))
        { "all assigned vars in the program must be outVars" }

        assertTrue(true)
    }

    private fun assignedVarsSubsetEqOutVars(
        summary: ComputeTACSummaryTransFormula.TACSummaryTransFormula,
        prog: TACCommandGraph
    ) = summary.transFormula.outVars.keys.map { it.sym }.toSet().containsAll(
        prog.commands.map { it.cmd }.filterIsInstance<TACCmd.Simple.AssigningCmd>().map { it.lhs }.toSet()
    )

    @Test
    fun testSmallProg09() {

//        val summaryComputer = { constructApplyDERComputer() }
        val summaryComputer = { ComputeTACSummaryTransFormula() }

        val prog = TACMockLanguage.make {
            L1024 = `*`
            L1024 = `*`
        }

        val summary = SummarizeProgram.summarizeTAC(
            prog,
            summaryComputer()
        )
        // summary should be something like "trackedVar = false"

        assertTrue(
            summary.transFormula.outVars.keys.map { it.sym }.toSet() == setOf(
                TACSymbol.Var(
                    "L1024",
                    Tag.Bit256
                )
            )
        )
        assertTrue(summary.transFormula.outVars.keys.map { it.sym }.toSet() ==
                prog.commands.map { it.cmd }.filterIsInstance<TACCmd.Simple.AssigningCmd>().map { it.lhs }.toSet()
        ) { "outVars in summary transformula should match the assigned vars in the program" }

        assertTrue(true)
    }

    @Test
    fun testSmallProg10() {
        val summaryComputer = { ComputeTACSummaryTransFormula() }
        val prog = TACMockLanguage.make {
            L1024 = `*`
            L1024 = L1024
        }
        val summary = SummarizeProgram.summarizeTAC(prog, summaryComputer())

        // inVars: {}
        // auxVars: { L1024' }
        // outVars: { (L1024 -> L1024'') }

        assertTrue(summary.transFormula.inVars.keys.map { it.sym }.isEmpty())
        assertTrue(summary.transFormula.auxVars.size == 1)
        assertTrue(summary.transFormula.outVars.keys.map { it.sym }.toSet() ==
                prog.commands.map { it.cmd }.filterIsInstance<TACCmd.Simple.AssigningCmd>().map { it.lhs }.toSet()
        ) { "outVars in summary transformula should match the assigned vars in the program" }
        assertTrue(true)
    }
}

