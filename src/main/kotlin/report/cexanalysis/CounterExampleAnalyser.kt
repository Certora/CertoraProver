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
package report.cexanalysis

import analysis.CmdPointer
import analysis.TACProgramPrinter
import datastructures.stdcollections.*
import log.*
import report.RuleAlertReport
import solver.SMTCounterexampleModel
import utils.Color.Companion.greenBg
import utils.Color.Companion.yellow
import utils.Color.Companion.yellowBg
import utils.Range
import vc.data.CoreTACProgram
import vc.data.TACCmd

private val logger = Logger(LoggerTypes.CEX_ANALYSER)


interface CexAnalysisInfo {
    val ptr : CmdPointer
    val cmd : TACCmd.Simple
    val msg : String
    val range get() = cmd.sourceOrCVLRange as? Range.Range

}

/**
 * Analyses the given [model] of [code], and then holds some fields with information for call trace purposes.
 */
class CounterExampleAnalyser(
    val cexId: Int,
    val code: CoreTACProgram,
    val model: SMTCounterexampleModel,
) {
    private val cex = CounterExampleContext(code, model)

    /**
     * A set of commands that are not needed for the counter example to be a valid one.
     */
    val unneeded: Set<CmdPointer> = CounterExampleConeOfInf(cex).unneededCmdPointers()

    /**
     * A set of commands where there is some imprecision caused by our over-approximated/unsound modeling. For each
     * such command, there is some `msg` hinting at the cause of the problem.
     *
     * Note that [unneeded] commands don't appear here.
     */
    val imprecisions: Map<CmdPointer, CexAnalysisInfo> =
        CounterExampleImprecisionDetector(cex).check()

    val overflows: Map<CmdPointer, CexAnalysisInfo> =
        CounterExampleOverflowDetector(cex).check()

    val imprecisionAlerts = summarize(imprecisions, "imprecision")
    val overflowAlerts = summarize(overflows, "overflow")

    /**
     * Alerts summarizing [imprecisions]/[overflows]. Currently we alert at most once, even if there are many.
     */
    fun summarize(alerts: Map<CmdPointer, CexAnalysisInfo>, name: String): List<RuleAlertReport.Warning> {
        fun text() = alerts.values.firstOrNull()?.let {
            "${cex.g.toCommand(it.ptr).sourceOrCVLRange} : ${it.msg}"
        }
        return when (alerts.size) {
            0 -> emptyList()
            1 -> listOf(
                RuleAlertReport.Warning(
                    "Detected one $name in counter example $cexId: ${text()}"
                )
            )

            else -> listOf(
                RuleAlertReport.Warning(
                    "Detected ${alerts.size} ${name}s in counter example $cexId. First one is: ${text()}"
                )
            )
        }
    }

    init {
        Logger.regression {
            "Found ${imprecisions.size} imprecisions\n" +
                "Found ${overflows.size} overflows"
        }
        logger.debug {
            "Generated imprecision alerts : $imprecisionAlerts\n" +
                "Generated overflow alerts : $overflowAlerts"
        }
        logger.trace {
            if (cex.pathBlocksList != null) {
                val pathBlocksSet = cex.pathBlocksList.toSet()
                TACProgramPrinter.standard()
                    .dontShowBlocks { it !in pathBlocksSet }
                    .dontShowCmds { (ptr, _) -> ptr.block == cex.lastPtr!!.block && ptr.pos > cex.lastPtr.pos }
                    .highlight { it.ptr in unneeded }
                    .extraLines {
                        imprecisions[it.ptr]
                            ?.let { listOf(it.greenBg) }
                            .orEmpty()
                    }
                    .extraLines {
                        overflows[it.ptr]
                            ?.let { listOf(it.yellowBg) }
                            .orEmpty()
                    }
                    .extraLhsInfo { ptr ->
                        cex.g.getLhs(ptr)?.let(cex::invoke)
                            ?.let { "($it)".yellow }.orEmpty()
                    }
                    .print(code)
            }
        }
    }
}
