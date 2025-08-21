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
import utils.Range
import vc.data.CoreTACProgram

private val logger = Logger(LoggerTypes.CEX_ANALYSER)

/**
 * Analyses the given [model] of [code], and then holds some fields with information for call trace purposes.
 */
class CounterExampleAnalyser(
    cexId: Int,
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
    val imprecisions: Map<CmdPointer, Pair<String, Range?>> = CounterExampleImprecisionDetector(cex).check() - unneeded

    private val firstAlertText = imprecisions.entries.firstOrNull()?.let { (ptr, msg) ->
        "${cex.g.toCommand(ptr).sourceOrCVLRange} : $msg"
    }

    /**
     * Alerts summarizing [imprecisions]. Currently we alert at most once, even if there are many imprecisions.
     */
    val alerts: List<RuleAlertReport.Warning> =
        when (imprecisions.size) {
            0 -> emptyList()
            1 -> listOf(
                RuleAlertReport.Warning(
                    "Detected one imprecision in counter example $cexId: $firstAlertText"
                )
            )

            else -> listOf(
                RuleAlertReport.Warning(
                    "Detected ${imprecisions.size} imprecisions in counter example $cexId. First one is: $firstAlertText"
                )
            )
        }

    init {
        Logger.regression {
            "Found ${imprecisions.size} imprecisions"
        }
        logger.debug {
            "Generated alerts : $alerts"
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
                            ?: listOf()
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
