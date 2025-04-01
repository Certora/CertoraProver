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

package report

import config.Config.AsciiTableMultiplier
import de.vandermeer.asciitable.AT_Context
import de.vandermeer.asciitable.AT_Renderer
import de.vandermeer.asciitable.AsciiTable
import de.vandermeer.asciitable.CWC_FixedWidth
import de.vandermeer.asciithemes.TA_Grid
import de.vandermeer.asciithemes.TA_GridConfig
import de.vandermeer.skb.interfaces.transformers.textformat.TextAlignment
import log.*
import rules.RuleCheckResult
import rules.RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample
import scene.IScene
import solver.SolverResult
import spec.CVLTestGenerator
import spec.rules.IRule

object ConsoleReporter : OutputReporter {
    var finalVerificationResult : FinalResult = FinalResult.NONE

    override fun getOutFile(): String {
        throw AssertionError("not implemented")
    }

    override fun signalStart(rule: IRule) {
        Logger.always(
            "Starting to run on rule ${rule.ruleIdentifier}", respectQuiet = true
        )
    }

    override fun resultFilter(result: RuleCheckResult): Boolean = !matchResultsForSubRulesAndSanity(result)

    override fun addResults(results: RuleCheckResult) {
        synchronized(this) {
            rawResults.add(results)
        }
    }

    private data class Row(
        val ruleId: String,
        val resultString: String,
        val time: String,
        val description: String,
        val localVars: String
    )

    private fun yieldTables(results: List<RuleCheckResult>, name: String): List<String> {
        val tables = mutableListOf<String>()

        val columnSizes = listOf(30 + 10, 15 - 2, 10, 60, 50)
        val correctedColumnSizes = columnSizes.map { desiredSize -> desiredSize * AsciiTableMultiplier.get() / 100 }
        val table = AsciiTable()
        val columnWidths = CWC_FixedWidth()
        correctedColumnSizes.forEach { width -> columnWidths.add(width) }
        val renderer = AT_Renderer.create()
        renderer.cwc = columnWidths
        table.renderer = renderer
        table.addRule()
        table.addRow("Rule name", "Verified", "Time (sec)", "Description", "Local vars")
        table.addRule()
        results
            .filter { it !is RuleCheckResult.Skipped }
            .forEach { result ->
                val normalizedResult = when (result) {
                    is RuleCheckResult.Multi -> {
                        tables.addAll(yieldTables(result.results, result.getTableNameForReport()))
                        result.computeMultiSummary()
                    }
                    else -> result
                }

                val ruleId = normalizedResult.rule.declarationId

                val row = when (normalizedResult) {
                    is RuleCheckResult.Multi -> {
                        throw AssertionError("Must summarize $normalizedResult before fetching the result")
                    }
                    is RuleCheckResult.Skipped -> {
                        throw AssertionError("Should have skipped this result $normalizedResult")
                    }
                    is RuleCheckResult.Single -> {
                        val firstData = normalizedResult.firstData
                        Row(
                            ruleId = ruleId,
                            resultString = SMTResultInterpreter.getResultString(normalizedResult.result, normalizedResult.rule),
                            time = normalizedResult.verifyTime.timeSeconds.toString(),
                            description = (if (firstData.failureResultMeta.isNotEmpty()) {
                                "Assert message: ${CVLTestGenerator.getAssertionMessage(firstData.failureResultMeta, normalizedResult.isSatisfyResult())}"
                            } else ""),
                            localVars = formatLocalAssignmentsForReport(firstData as? CounterExample)
                        )
                    }
                    is RuleCheckResult.Error -> {
                        Row(
                            ruleId = ruleId,
                            resultString = SMTResultInterpreter.getResultString(SolverResult.UNKNOWN, normalizedResult.rule),
                            time = "?",
                            description = normalizedResult.ruleAlerts.asList.map { it.reason }.toString(),
                            localVars = ""
                        )
                    }
                }

                table.addRow(
                    row.ruleId,
                    row.resultString,
                    row.time,
                    row.description,
                    row.localVars
                )
            }
        table.addRule()
        table.setTextAlignment(TextAlignment.LEFT) // need to set after adding the rows...
        val ctx = AT_Context()
        val grid = TA_Grid.create("bashComptaible")
        grid.addCharacterMap(TA_GridConfig.RULESET_NORMAL, ' ', '-', '|', '*', '*', '*', '*', '|', '|', '|', '-', '-')
        ctx.setGrid(grid)

        tables.add(
            "Results for $name:" +
                    System.lineSeparator() +
                    table.renderer.render(
                        table.getRawContent(),
                        table.getColNumber(),
                        ctx,
                        200
                    ) + System.lineSeparator()
        )
        return tables
    }

    private fun showFailures() {
        val failures = StringBuilder()

        rawResults
            .filter { it !is RuleCheckResult.Skipped }
            .map { result ->
                if (result is RuleCheckResult.Multi) {
                    result.computeMultiSummary()
                } else {
                    result
                }
            }
            .forEach { result ->
                val ruleResult = when (result) {
                    is RuleCheckResult.Single -> result.result
                    is RuleCheckResult.Multi -> throw AssertionError("Should have summarized this Multi result into Single ($result)")
                    is RuleCheckResult.Skipped -> throw AssertionError("Should have skipped this result $result")
                    is RuleCheckResult.Error -> SolverResult.UNKNOWN
                }

                val description = when (result) {
                    is RuleCheckResult.Single ->  {
                        val firstData = result.firstData
                        (if (firstData.failureResultMeta.isNotEmpty()) {
                            "Assert message: ${CVLTestGenerator.getAssertionMessage(firstData.failureResultMeta, result.isSatisfyResult())}"
                        } else { "" }) +
                            "\n${firstData.details.getOrNull().orEmpty()}"
                    }
                    is RuleCheckResult.Multi -> throw AssertionError("Must summarize $result before fetching the description")
                    is RuleCheckResult.Skipped -> throw AssertionError("Should have skipped this result $result")
                    is RuleCheckResult.Error -> result.ruleAlerts.asList.map { it.reason }.toString()
                }

                // If this is a rule that contains Satisfy commands,
                // the desired verification condition is "SAT" rather
                // than UNSAT
                fun isResultGood(ruleResult : SolverResult) =
                    if (result.rule.isSatisfyRule) {
                        ruleResult == SolverResult.SAT
                    } else {
                        ruleResult == SolverResult.UNSAT
                    }

                if (!isResultGood(ruleResult)) {
                    failures.appendLine("Failed on ${result.rule.declarationId}:\n$description")
                }
            }

        if (failures.isNotBlank()) {
            finalVerificationResult = FinalResult.FAIL
            Logger.always("Failures summary:", respectQuiet = true)
            Logger.always(failures.toString(), respectQuiet = true)
        } else {
            finalVerificationResult = FinalResult.SUCCESS
        }
    }

    // prints just to console
    override fun toFile(scene: IScene) {
        val tables = yieldTables(rawResults.toList(), "all")
        Logger.always(tables.joinToString("\n"), respectQuiet = true)
        showFailures()
    }

    override fun hotUpdate(scene: IScene) {}

    private val rawResults = mutableSetOf<RuleCheckResult>()

}
