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

import config.Config
import config.Config.OutputFinalResultsHTML
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import log.*
import parallel.coroutines.launchMaybeBackground
import report.dumps.CodeMap
import report.dumps.DumpGraphHTML
import rules.RuleCheckResult
import rules.RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample
import scene.IContractWithSource
import scene.IScene
import solver.SolverResult
import spec.CVLTestGenerator
import spec.rules.IRule
import spec.cvlast.SpecType
import utils.ArtifactFileUtils
import utils.CertoraErrorType
import java.awt.Desktop
import java.io.File
import java.io.FileWriter

private val logger = Logger(LoggerTypes.COMMON)

object HTMLReporter : OutputReporter {
    const val PAGETOP_ANCHOR = "PageTop"
    private const val AVAILABLE_CONTRACTS = "AvailableContracts"
    const val THUMB_UP = "&#128077;"
    const val THUMBS_DOWN = "&#128078;"

    data class Row(
        val name: String,
        val result: String,
        val time: String,
        val details: String,
        val localVars: String,
        val parent: String,
        val ruleType: SpecType?,
        val hasMultiAssertTable: Boolean
    ) {
        var cell = "td"

        private fun writeResultCell(s: String): String {
            return writeCell(
                when (s) {
                    "Violated" -> THUMBS_DOWN
                    "Not violated" -> THUMB_UP
                    else -> s
                }
            )
        }

        private fun writeCell(s: String): String {
            return "<$cell>$s</$cell>"
        }

        private fun getTRClass(): String {
            return when (result) {
                "Violated" -> "table-danger"
                "Not violated" -> "table-success"
                else -> "table-warning"
            }
        }



        fun outputRowToWriter(w: FileWriter) {
            if (cell != "td") {
                w.appendLine("<tr class=\"thead-light\">")
            } else {
                w.appendLine("<tr class=\"${getTRClass()}\">")
            }

            val reportFileFromReportsDir = getReportName(name, ruleType)
            val reportToLinkTo = getReportPath(name,ruleType)
            if (File(reportToLinkTo).exists()) {
                w.append(writeCell("<a href='$reportFileFromReportsDir' target='_blank'>$name</a>"))
            } else {
                // try with table name
                val extendedName = "$parent${OUTPUT_NAME_DELIMITER}$name"
                val reportFileFromReportsDir2 = getReportName(extendedName, ruleType)
                val reportToLinkTo2 = getReportPath(extendedName,ruleType)
                if (File(reportToLinkTo2).exists()) {
                    w.append(writeCell("<a href='$reportFileFromReportsDir2' target='_blank'>$name</a>"))
                } else if (cell != "th") {
                    if (hasMultiAssertTable){
                        w.append(writeCell("<a href='#${extendedName}'>$name</a>"))
                    } else {
                        w.append(writeCell("<a href='#${name}'>$name</a>"))
                    }
                } else {
                    w.append(writeCell(name))
                }
            }
            w.append(writeResultCell(result))
            w.append(writeCell(time))
            w.append(writeCell(details.replace("\n", "<br/>\n")))
            w.append(writeCell(localVars))

            w.appendLine("</tr>")
        }
    }

    val Header = Row("Rule name", "Result", "Time (seconds)", "Details", "Local variables", "", null, false)
    val MultiAssertHeader = Row("Assertions", "Result", "Time (seconds)", "Details", "Local variables", "", null, false)

    init {
        Header.cell = "th"
        MultiAssertHeader.cell = "th"
    }

    data class Table(val name: String, val isForInstances: Boolean, val rows: List<Row>, val isMultiAssert: Boolean = false) {
        fun outputTableToWriter(w: FileWriter) {

            if (isForInstances) {
                w.appendLine("<a name='$name'>")
            }

            if (isMultiAssert) {
                w.appendLine("<h3>Assert results for $name:</h3>")
            } else {
                w.appendLine("<h3>Results for $name:</h3>")
            }

            if (isForInstances) {
                w.appendLine("<a href='#${PAGETOP_ANCHOR}'>Go back to top</a>")
                w.appendLine("</a>")
            }

            w.appendLine("<table class=\"table table-bordered table-hover\">")

            if (isMultiAssert) {
                MultiAssertHeader.outputRowToWriter(w)
            } else {
                Header.outputRowToWriter(w)
            }

            rows.forEach { r -> r.outputRowToWriter(w) }

            w.appendLine("</table>")
            w.appendLine("<br/>")

        }
    }

    private val tables = mutableListOf<Table>()

    override fun getOutFile(): String = OutputFinalResultsHTML.get()


    fun dumpCodeMapToHTMLReport(codeMap: CodeMap, dumpGraphLink: String) {
        launchMaybeBackground("html report from CodeMap generation") {
            DumpGraphHTML.writeCodeToHTML(codeMap, dumpGraphLink)
        }
    }

    fun dumpCodeMapToHTMLReport(codeMap: CodeMap, reportType: ReportTypes, index: ReportNameIndex = ReportNameIndex.None) {
        val dumpGraphLink = getReportNameByReportType(codeMap.name, reportType, index)
        launchMaybeBackground("html report from CodeMap generation") {
            DumpGraphHTML.writeCodeToHTML(codeMap, dumpGraphLink)
        }
    }

    fun getReportName(
        baseName: String,
        ruleType: SpecType?,
        reportType: ReportTypes = ReportTypes.REPORT,
        reportNameIndex: ReportNameIndex = ReportNameIndex.None,
        withHTMLExtension: Boolean = true,
        customSuffix: String = ""
    ): String =
        when (ruleType) {
            null -> ""
            else -> getReportNameByReportType(baseName, reportType, reportNameIndex, withHTMLExtension, customSuffix)
        }

    fun getReportPath(basename: String, ruleType: SpecType?): String =
        "${ArtifactManagerFactory().mainReportsDir}${File.separator}${getReportName(basename, ruleType)}"

    fun getReportNameMultiCase(
        name: String,
        parent: String,
        ruleType: SpecType?,
        reportType: ReportTypes,
        reportNameIndex: ReportNameIndex = ReportNameIndex.None,
        withHTMLExtension: Boolean = true,
        customSuffix: String = ""
    ): String =
        getReportName(
            "$parent${OUTPUT_NAME_DELIMITER}$name",
            ruleType,
            reportType,
            reportNameIndex,
            withHTMLExtension,
            customSuffix
        )

    /** Wraps the different suffixes we use for our reports. Use [toString] to get the suffix for the report name. */
    interface ReportNameIndex {
        @JvmInline value class Example(val index: Int): ReportNameIndex {
            override fun toString(): String = "${OUTPUT_NAME_DELIMITER}example${index + 1}"
        }
        @JvmInline value class UnsatCore(val index: Int): ReportNameIndex {
            override fun toString(): String = "${OUTPUT_NAME_DELIMITER}unsatCore${index + 1}"
        }
        object None: ReportNameIndex {
            override fun toString(): String = ""
        }
    }

    /**
     * [exampleIndex] is the index of the example we are generating HTML report for.
     * Some generated HTML files contains pure TACPrograms and are not annotated with examples.
     * In such a case, [exampleIndex] is not part of the file's name, and should be null.
     */
    private fun getReportNameByReportType(
        baseName: String,
        reportType: ReportTypes,
        reportNameIndex: ReportNameIndex = ReportNameIndex.None,
        withHTMLExtension: Boolean = true,
        customSuffix: String = "",
    ): String {
        return "${reportType.toFilenamePrefix()}$OUTPUT_NAME_DELIMITER${baseName}$reportNameIndex$customSuffix" +
            ".html".takeIf { withHTMLExtension }.orEmpty()
    }

    private fun getReportPathByReportType(baseName: String, reportType: ReportTypes): String =
        "${ArtifactManagerFactory().mainReportsDir}${File.separator}" +
            getReportNameByReportType(baseName, reportType)

    private fun getDetails(data: RuleCheckResult.Single.RuleCheckInfo.BasicDataContainer, isSatisfy: Boolean): String {
        val assertMessagePart = if (data.failureResultMeta.isNotEmpty()) {
            """
            Assert message: ${CVLTestGenerator.getAssertionMessage(data.failureResultMeta, isSatisfy)}<br/>
        """.trimIndent()
        } else {
            ""
        }

        val detailsPart = if (data.details.getOrNull()?.isNotBlank() == true) {
            """
            ${data.details}<br/>
        """.trimIndent()
        } else {
            ""
        }

        return assertMessagePart+detailsPart
    }

    private fun getRow(singleResult: RuleCheckResult, tableName: String, hasMultiAssertTable: Boolean): Row =
        when (singleResult) {
            is RuleCheckResult.Single ->  {
                val firstData = singleResult.firstData
                Row(
                    singleResult.rule.declarationId,
                    SMTResultInterpreter.getResultString(singleResult.result, singleResult.rule),
                    singleResult.verifyTime.timeSeconds.toString(),
                    getDetails(firstData, singleResult.isSatisfyResult()),
                    formatLocalAssignmentsForReport(firstData as? CounterExample),
                    tableName,
                    singleResult.rule.ruleType,
                    hasMultiAssertTable
                )
            }
            is RuleCheckResult.Error -> Row(
                singleResult.rule.declarationId,
                "Error",
                "?",
                singleResult.ruleAlerts.asList.joinToString(separator = "${System.lineSeparator()}${System.lineSeparator()}") { it.msg },
                "",
                tableName,
                singleResult.rule.ruleType,
                hasMultiAssertTable
            )
            else -> throw AssertionError("Unsupported result $singleResult for getting a row")
        }


    private val rawResults = mutableSetOf<RuleCheckResult>()

    private fun getUpdatedRows(tableName: String, result: RuleCheckResult, hasMultiAssertTable: Boolean): List<Row> {
        val oldRows = tables.find { it.name == tableName }?.rows ?: listOf()
        val rows = oldRows + when (result) {
            is RuleCheckResult.Single -> listOf(
                getRow(result, tableName, hasMultiAssertTable)
            )
            is RuleCheckResult.Multi -> {
                // need to replace multis with summaries
                val summarizedResults =
                    result.results.map { if (it is RuleCheckResult.Multi) {
                        it.computeMultiSummary() to (it.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS)
                    } else {
                        it to false
                    } }

                /*if (result.results.any { it !is RuleCheckResult.Single }) {
                    throw AssertionError("HTML Reporter does not support nesting of group rules at the moment")
                }*/

                summarizedResults
                    .filterNot { it.first is RuleCheckResult.Skipped }
                    .map { getRow(it.first, tableName, it.second) }
            }
            is RuleCheckResult.Skipped -> listOf()
            is RuleCheckResult.Error -> listOf(
                Row(
                    result.rule.declarationId,
                    SMTResultInterpreter.getResultString(SolverResult.UNKNOWN,
                        result.rule),
                    "?",
                    "Had an error in computing the result of the rule: ${result.ruleAlerts.asList.map {
                        if (it.reason?.type == CertoraErrorType.TASK_TIMEOUT) {
                            "Global timeout hit, you may need to retry running Prover on this rule alone"
                        } else {
                            it
                        }
                    }}",
                    "",
                    tableName,
                    result.rule.ruleType,
                    hasMultiAssertTable
                )
            )
        }
        return rows
            .distinct() // take only unique rows
    }

    private fun updateTable(tableName: String, table: Table) {
        // if same table already existed, remove it first
        tables.removeIf { it.name == tableName }
        // add new or updated table
        tables.add(table)
    }

    private fun isMultiResult(results: RuleCheckResult): Boolean {
        return results is RuleCheckResult.Multi && results.getAllSingleRules().count { it.ruleType !is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck } > 1
    }

    private fun processRuleCheckResultWithSideEffects(results: RuleCheckResult) {
        fun isMultiAssertResult(res: RuleCheckResult) =
            when (res) {
                is RuleCheckResult.Multi -> res.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS
                else -> false
            }

        val isMulti =
            isMultiResult(results)

        val parentTableName = if (results.rule.parentIdentifier != null) results.rule.parentIdentifier!!.displayName else "main"

        // when multi, should add (1) a single row to the parent with a summary; (2) a table for the current one; (3) add all nested multis too
        if (isMulti) { // add to parent
            results as RuleCheckResult.Multi // will succeed if isMulti is true
            val summary = results.computeMultiSummary()
            val updatedSummaryRows = getUpdatedRows(parentTableName, summary, isMultiAssertResult(results))
            val table = Table(parentTableName, false, updatedSummaryRows)
            updateTable(parentTableName, table)

            results.results.forEach { subMulti ->
                if (isMultiResult(subMulti)) {
                    processRuleCheckResultWithSideEffects(subMulti)
                }
            }
        }

        // add for current: if not multi, then just add the row to the parent, otherwise a new table
        val tableName = if (!isMulti) parentTableName else results.getTableNameForReport()
        val rows = getUpdatedRows(tableName, results, isMultiAssertResult(results))
        val table = Table(tableName, isMulti, rows, isMultiAssertResult((results)))
        updateTable(tableName, table)
    }

    override fun resultFilter(result: RuleCheckResult): Boolean = !matchResultsForSubRulesAndSanity(result)

    override fun addResults(results: RuleCheckResult) {
        synchronized(this) {
            // first update raw results
            rawResults.add(results)

            // now update HTMLReporter's own table
            processRuleCheckResultWithSideEffects(results)
        }
    }

    override fun toFile(scene: IScene) {
        ArtifactManagerFactory().registerArtifact(OutputFinalResultsHTML) { handle ->
            ArtifactFileUtils.getWriterForFile(handle, overwrite = true, append = false).use { w ->
                w.appendLine("<link rel=\"stylesheet\" href=\"https://maxcdn.bootstrapcdn.com/bootstrap/4.2.1/css/bootstrap.min.css\">")
                w.appendLine("<a name='${PAGETOP_ANCHOR}'><h2>Certora Prover results for contract</h2></a>")
                w.appendLine("<a href='#${AVAILABLE_CONTRACTS}'>Go to available contracts listing</a>")
                tables.sortBy { it.isForInstances } // start with non-instance
                tables.forEach { t -> t.outputTableToWriter(w) }
                printAvailableContracts(w, scene)
            }
        }
    }

    private fun printAvailableContracts(w: FileWriter, scene: IScene) {
        w.appendLine("<a name='${AVAILABLE_CONTRACTS}'><h2>Available contracts</h2></a>")
        w.appendLine("<table class=\"table table-bordered table-hover\">")
        w.appendLine("<tr><th>Name</th><th>Address</th><th>Pre-state</th><th colspan='2'>Methods</th></tr>")
        scene.getContracts().mapNotNull { (it as? IContractWithSource)?.src }.forEach { c ->
            w.appendLine("<tr>")
            w.appendLine("<td>${c.name}</td>")
            w.appendLine("<td>${c.address.toString(16)}</td>")
            w.appendLine("<td>${c.state.mapValues { it.value.toString(16) }}</td>")
            w.appendLine(/* .joinToString("<br/>")*/
                c.methods.map { method ->
                    val name = "${c.name}-${method.getPrettyName()}"
                    val cfgReportFileFromReportsDir = getReportNameByReportType(
                        name,
                        ReportTypes.CFG,
                    )
                    val cfgReportToLinkTo = getReportPathByReportType(name, ReportTypes.CFG)

                    val methodReportHTML = if (File(cfgReportToLinkTo).exists()) {
                        "<a href='$cfgReportFileFromReportsDir' target='_blank'>$method</a>"
                    } else {
                        method.toString()
                    }

                    val optReportFileFromReportsDir = getReportNameByReportType(
                        name,
                        ReportTypes.OPTIMIZE,
                    )
                    val optReportToLinkTo = getReportPathByReportType(name, ReportTypes.OPTIMIZE)

                    val optReportHTML = if (File(optReportToLinkTo).exists()) {
                        "(<a href='$optReportFileFromReportsDir' target='_blank'>Optimized</a>)"
                    } else {
                        ""
                    }

                    Pair(
                        methodReportHTML,
                        optReportHTML
                    )
                }
                    .let { linksPairs ->
                        "<td>" +
                                linksPairs.map { it.first }.joinToString("<br/>") +
                                "</td>" +
                                "<td>" +
                                linksPairs.map { it.second }.joinToString("<br/>") +
                                "</td>"
                    }
            )
            w.appendLine("</tr>")
        }
    }

    fun open() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                val path = getHTMLReportFileURI()
                if (path != null) {
                    val fullPath = File(path).toURI()
                    // Opens the browser asynchronously, but blocks process exit until it's opened (because this isn't a
                    // daemon thread)
                    Thread {
                        Desktop.getDesktop().browse(fullPath)
                    }.start()
                }
            }
        } catch (e: Exception) {
            logger.error(e) {"Failed to open final report file ${OutputFinalResultsHTML.get()}: ${e.message}"}
        }
    }

    fun getHTMLReportFileURI(): String? {
        return ArtifactManagerFactory().getRegisteredArtifactPathOrNull(OutputFinalResultsHTML.get())?.takeIf { f ->
            File(f).exists()
        }
    }

    fun isDisabledPopup(): Boolean {
        // if null, will show popup
        return System.getenv("CERTORA_DISABLE_POPUP") == "1" || Config.DisablePopup.get()
    }

    override fun hotUpdate(scene: IScene) {
        synchronized(this) {
            toFile(scene) // will update the html report
            // work on notification
            val allRules = rawResults.flatMap { result -> result.getAllFlattened() }
            val successfulRulesSoFar = allRules.filter { it.solverResult.isSuccess() }
            val nonSuccessfulRulesSoFar = allRules.filter { !it.solverResult.isSuccess() }
            val success = nonSuccessfulRulesSoFar.isEmpty()
            val msg = if (success) {
                if (successfulRulesSoFar.size == expectedNumberOfRules) {
                    "All rules proven"
                } else {
                    "Proven ${successfulRulesSoFar.size} out of $expectedNumberOfRules rules, ${nonSuccessfulRulesSoFar.size} failed"
                }
            } else {
                val violated = nonSuccessfulRulesSoFar.map { it.ruleDeclarationId }
                "Violated ${violated.size} out of ${allRules.size} so far. " +
                        "Violated rules: ${violated.joinToString(",")}"
            }
            Notifications.showNotification("Updated report", msg, if (success) FinalResult.SUCCESS else FinalResult.FAIL)
        }
    }

    override fun signalStart(rule: IRule) {}

    // This lets us get reliable total number of rules to check
    var expectedNumberOfRules: Int = 0
}
