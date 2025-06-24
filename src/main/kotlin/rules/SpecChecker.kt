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

package rules

import bridge.NamedContractIdentifier
import config.Config
import datastructures.stdcollections.*
import log.*
import report.*
import rules.dpgraph.TrivialRuleDependencies
import scene.IScene
import spec.CVL
import spec.cvlast.*
import spec.rules.GroupRule
import spec.rules.ICVLRule
import spec.rules.IRule
import spec.rules.StaticRule
import utils.*
import verifier.mus.UnsatCoreVisualisation
import verifier.mus.UnsatCoresStats

private val logger = Logger(LoggerTypes.COMMON)

class SpecChecker(
    val contractName: NamedContractIdentifier,
    val cvl: CVL,
    private val reporter: OutputReporter,
    private val treeViewReporter: TreeViewReporter,
    val scene : IScene
) {

    /**
     * If we check against a specific rule/method, then we may have summaries that would have been applied unless
     * that rule/method choice. As a result, in such cases, the monitor may not show the summaries' applications of
     * the complete spec, and therefore we disable it.
     * Additionally, when in foundry mode there are many summaries for different cheatcodes, and likely most of them
     * will be unused in a specific test run, so disable it in this case as well.
     */
    private val summaryMonitor = if (!Config.methodsAreFiltered && !Config.areRulesFiltered() && !Config.Foundry.get()) {
        SummaryMonitor(cvl.external, cvl.internal, cvl.unresolvedSummaries)
    } else {
        null
    }

    private val ruleChecker = RuleChecker(scene, contractName, cvl, reporter, treeViewReporter, summaryMonitor)

    suspend fun ICVLRule.check(idx: Int, size: Int): RuleCheckResult {
        if (idx > 0) {
            Logger.always("Checking rule $declarationId ($idx out of $size)...", respectQuiet = true)
        }

        treeViewReporter.addTopLevelRule(this)
        val r = ruleChecker.check(this)

        reporter.feedReporter(listOf(r), scene)

        Logger.always(
            "Result for ${declarationId}: ${r.consolePrint(this.isSatisfyRule)}",
            respectQuiet = true
        )

        return r
    }

    /**
     * Check if any envfree checks failed, and if so make sure the user notices this because it may affect the result of other rules.
     */
    private fun checkEnvfreeRuleResults(results: List<RuleCheckResult>) {
        results.forEach { ruleCheckResult ->
            if (ruleCheckResult.rule.ruleType !is SpecType.Group.StaticEnvFree) {
                return@forEach
            }

            check(ruleCheckResult.rule is GroupRule) {
                "envfree static check is supposed to be a group rule"
            }

            ruleCheckResult.getAllFlattened().forEach forEachFlattened@{ res ->
                if (res.isSuccess()) {
                    return@forEachFlattened
                }

                val rule = res.checkResult.rule

                check(rule is StaticRule) {
                    "Each sub-rule of the envfree static check should be, well, static. Got $rule"
                }

                val ruleType = rule.ruleType

                check(ruleType is SpecType.Single.EnvFree.Static) {
                    "Each sub-rule of the envfree static check should have the `Envfree.Static` rule type, got $ruleType"
                }

                val methodInfo = ruleType.contractFunction.getMethodInfo()
                val funcSig = methodInfo.getPrettyName()

                CVTAlertReporter.reportAlert(
                    CVTAlertType.CVL,
                    CVTAlertSeverity.ERROR,
                    methodInfo.sourceSegment,
                    "Function $funcSig was declared `envfree` but depends on the environment. " +
                        "Rules that call this function (without an `env` argument) may produce wrong results, or trigger internal errors.",
                    "Remove the `envfree` annotation from $funcSig",
                    CheckedUrl.ENVFREE_ANNOTATIONS,
                )
            }
        }
    }

    /**
     * Visualises the unsat cores on the .sol and .spec files. Currently, the visualisation is dumped to
     * Reports/UnsatCoreVisualisation.html.
     */
    fun visualiseUnsatCores(results: List<RuleCheckResult>) {
        try {
            val unsatCoresStats = mutableMapOf<IRule,UnsatCoresStats>()
            fun addUcStats(res: RuleCheckResult.Single.Basic) {
                if(res.rule in unsatCoresStats) {
                    logger.warn { "Got multiple unsat core stats entries for the same rule: ${res.rule}" }
                } else if(res.unsatCoreStats != null) {
                    unsatCoresStats[res.rule] = res.unsatCoreStats
                }
            }

            results.forEach { result ->
                result.getAllFlattened().forEach {
                    if(it.checkResult is RuleCheckResult.Single.Basic){
                        addUcStats(it.checkResult)
                    }
                }
            }
            if(unsatCoresStats.isNotEmpty()) {
                UnsatCoreVisualisation(unsatCoresStats).visualiseCoverage()
            }
        } catch (e: Exception) {
            logger.error(e) { "Unsat core visualisation failed with the exception: ${e.message}" }
        }
    }

    suspend fun checkAll(): List<RuleCheckResult> {
        val allRules = cvl.rules
        if (allRules.isEmpty()) {
            CVTAlertReporter.reportAlert(
                CVTAlertType.CVL,
                CVTAlertSeverity.ERROR,
                null,
                "No rules are provided or used in ${cvl.name}.",
                "Define rules or invariants in your spec, use a builtin rule, or use a rule from an imported spec file."
            )
        }
        val ruleChoices = Config.getRuleChoices(allRules.mapToSet { it.declarationId })
        val chosenRules = allRules.filter { it.ruleType is SpecType.Group.StaticEnvFree || it.declarationId in ruleChoices }

        val dependencyGraph = TrivialRuleDependencies.generate(chosenRules)

        logger.info { "Checking rules $allRules" }
        val results = dependencyGraph.resultComputation(
            task = { it.check(chosenRules.indexOf(it) + 1, chosenRules.size) },
            reduce = { it.map { dpResult -> dpResult.result } }
        ).also {
            summaryMonitor?.reportUnusedSumm()
        }

        checkEnvfreeRuleResults(results)

        visualiseUnsatCores(results)

        return results
    }
}
