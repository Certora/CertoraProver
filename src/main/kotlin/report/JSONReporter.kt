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

import config.ConfigType
import log.ArtifactManagerFactory
import rules.RuleCheckResult
import scene.IScene
import solver.SolverResult
import spec.CVLTestGenerator
import spec.rules.IRule
import spec.cvlast.SpecType
import utils.ArtifactFileUtils

open class JSONReporter(private val conf: ConfigType<String>) : OutputReporter {

    init {
        ArtifactManagerFactory().registerArtifact(conf)
    }

    override fun getOutFile() = ArtifactManagerFactory().getRegisteredArtifactPath(conf)

    val allResultsList = mutableSetOf<RuleCheckResult>()

    override fun resultFilter(result: RuleCheckResult): Boolean = !matchResultsForSubRulesAndSanity(result)

    override fun addResults(results: RuleCheckResult) {
        synchronized(this) {
            allResultsList.add(results)
        }
    }

    override fun signalStart(rule: IRule) {}

    private val TAB: String = "\t"

    private fun getMethodResult(
        solverResult: SolverResult,
        groupedResult: Map<SolverResult, List<RuleCheckResult>>,
        numTabs: Int,
        expectedVCResultIsSat: Boolean
    ): String {
        val tabString = TAB.repeat(numTabs)
        val keyInGrouped = solverResult.toJSONRepresentation(expectedVCResultIsSat)
        val funcs = groupedResult.getOrDefault(solverResult, listOf())
            .map { "\"${it.rule.declarationId}\"" }
            .sorted()
        val funcsString = if (funcs.isNotEmpty()) {
            funcs.joinToString(separator = ",\n$tabString$TAB", prefix = "[\n$tabString$TAB", postfix = "\n$tabString]")
        } else {
            "[]"
        }
        return "$tabString\"$keyInGrouped\": $funcsString"
    }

    private fun getKeyvalueExp(key: String, value: String, tabString: String): String =
        "$tabString\"$key\": \"$value\""

    private fun List<String>.toJsonCollectionString(numTabs: Int): String {
        val tabString = TAB.repeat(numTabs)
        if (this.isEmpty()) {
            return "{}"
        }
        return this.sorted().joinToString(",\n", prefix = "{\n", postfix = "\n$tabString}")
    }

    private fun getResultForJson(result: RuleCheckResult): SolverResult {
        return when (result) {
            is RuleCheckResult.Single -> result.result
            is RuleCheckResult.Error -> SolverResult.UNKNOWN
            else -> throw AssertionError("Not supporting getting result for result $result")
        }
    }

    private fun jsonifyResult(result: RuleCheckResult, numTabs: Int): String {
        val tabString = TAB.repeat(numTabs)

        return when (result) {
            is RuleCheckResult.Single -> {
                val key = if (result.rule.ruleType != SpecType.Single.BMC) {
                    result.rule.declarationId
                } else {
                    result.rule.ruleIdentifier.toString()
                }
                val value = result.result.toJSONRepresentation(
                    result.rule.isSatisfyRule)
                getKeyvalueExp(key, value, tabString)
            }
            is RuleCheckResult.Multi -> {
                // if the result is of type [MultiResultType.SPLIT_ASSERTS], summarize it
                // and return as a Single result
                if (result.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS) {
                    val key = result.rule.declarationId
                    val value = result.computeFinalResult().toJSONRepresentation(result.rule.isSatisfyRule)
                    return getKeyvalueExp(key, value, tabString)
                }

                // in case the [MultiResultType.SPLIT_ASSERTS] result is WITHIN a
                // [MultiResultType.PARAMETRIC] result, summarize the SPLIT_ASSERT
                // result and continue jsonifying the PARAMETRIC result
                val summarisedAsserts = result.results.map {
                    if (it is RuleCheckResult.Multi && it.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS) {
                        it.computeMultiSummary()
                    } else {
                        it
                    }
                }

                val filterWithoutSkipped = summarisedAsserts.filterNot { it is RuleCheckResult.Skipped }
                if (filterWithoutSkipped.any { it is RuleCheckResult.Multi }) {
                    return "$tabString\"${result.rule.declarationId}\": " + filterWithoutSkipped.map { subResult ->
                        jsonifyResult(subResult, numTabs + 1)
                    }.toJsonCollectionString(numTabs)
                }

                val groupedResults = filterWithoutSkipped
                    .groupBy { getResultForJson(it) }
                //.mapValues { it.value.map { res -> res as RuleCheckResult.Single } }

                val valueResultString =
                    listOf(SolverResult.UNSAT, SolverResult.SAT, SolverResult.UNKNOWN, SolverResult.TIMEOUT, SolverResult.SANITY_FAIL)
                        .map { solverResult ->
                            getMethodResult(solverResult, groupedResults, numTabs + 1, result.rule.isSatisfyRule)
                        }.toJsonCollectionString(numTabs)

                "$tabString\"${result.rule.declarationId}\": $valueResultString"
            }
            is RuleCheckResult.Skipped -> ""
            is RuleCheckResult.Error -> getKeyvalueExp(
                result.rule.declarationId,
                SolverResult.UNKNOWN.toJSONRepresentation(),
                tabString
            )
        }
    }

    private fun jsonifyRuleResults(numTabs: Int): String {
        val tabString = TAB.repeat(numTabs)
        return "$tabString\"rules\": " +
                allResultsList
                    // flatten multi results TODO: For multiple levels - currently will inline just one level (there's no case for that yet)
                    .flatMap { result ->
                        when (result) {
                            is RuleCheckResult.Multi ->
                                // if it's a group with several kinds, at least one a multi, then the kinds will have different names and can be raised one level
                                // TODO: use rule types for more fine-grained control
                                if (result.results.any {
                                        it is RuleCheckResult.Multi &&
                                            // we should not flatten [MultiResultType.SPLIT_ASSERTS] results as we do not wish to
                                            // represent result for each assert rule in the json output.
                                            // Instead, the [MultiResultType.SPLIT_ASSERTS] result is summarized above.
                                            it.resultType != RuleCheckResult.MultiResultType.SPLIT_ASSERTS &&
                                            // Also, don't flatten invariants' subrules, we want to have the rules nested under the
                                            // invariant.
                                            result.rule.ruleType !is SpecType.Group.InvariantCheck
                                    })
                                    result.results
                                else
                                    listOf(
                                        result
                                    )
                            else -> listOf(result)
                        }
                    }
                    .filter { result ->
                        result !is RuleCheckResult.Skipped
                    }
                    .map { result ->
                        val jsonifiedResult = jsonifyResult(result, numTabs + 1)
                        jsonifiedResult
                    }
                    .toJsonCollectionString(numTabs)

    }

    private fun jsonifyResultMessage(result: RuleCheckResult, numTabs: Int): String {
        val tabString = TAB.repeat(numTabs)

        // will add only for single rules
        return if (result is RuleCheckResult.Single) {
            val key = if (result.rule.ruleType != SpecType.Single.BMC) {
                result.rule.declarationId
            } else {
                result.rule.ruleIdentifier.toString()
            }
            val assertMessage = CVLTestGenerator.getAssertionMessage(result.firstData.failureResultMeta, result.isSatisfyResult())
            val removeQuotesMessage =
                assertMessage.replace(Regex("(?<!\\\\)\""), "") // match all quotes '"' without backslash

            if (removeQuotesMessage.isNotBlank()) {
                getKeyvalueExp(key, removeQuotesMessage, tabString)
            } else {
                ""
            }
        } else if (result is RuleCheckResult.Multi &&
                    result.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS){
            jsonifyResultMessage(result.computeMultiSummary(), numTabs)
        } else {
            ""
        }

    }

    private fun jsonifyAssertionMessages(numTabs: Int): String {
        val tabString = TAB.repeat(numTabs)
        return "$tabString\"assertMessages\": " +
                allResultsList
                    .map { result ->
                        val jsonifiedResultAssertMessage = jsonifyResultMessage(result, numTabs + 1)
                        jsonifiedResultAssertMessage
                    }
                    .filter { it.isNotBlank() }
                    .toJsonCollectionString(numTabs)

    }

    override fun toFile(scene: IScene) {
        val jsond = listOf(
            jsonifyRuleResults(1), jsonifyAssertionMessages(1)
        ).toJsonCollectionString(0)
        writeToFile(jsond)
    }

    internal fun writeToFile(jsond: String, extension: String = "") {
        ArtifactManagerFactory().useArtifact("${conf.get()}$extension") { handle ->
            ArtifactFileUtils.getWriterForFile(handle, overwrite = true).use { i ->
                i.append(jsond)
            }
        }

    }

    override fun hotUpdate(scene: IScene) {
        // do nothing
    }

}
