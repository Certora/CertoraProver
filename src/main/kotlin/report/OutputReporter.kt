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

import log.*
import rules.RuleCheckResult
import scene.IScene
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import spec.cvlast.SpecType
import spec.rules.EcosystemAgnosticRule

private val logger = Logger(LoggerTypes.COMMON)

interface OutputReporter {
    fun getOutFile(): String
    fun addResults(results: RuleCheckResult)
    fun toFile(scene: IScene)
    fun hotUpdate(scene: IScene) // option to update the output with a notification 'live'
    fun signalStart(rule: IRule) // signal rule starting to run
    fun resultFilter(result: RuleCheckResult): Boolean // allow reporter to filter results
    fun signalStartFilter(rule: IRule): Boolean =
        true // allow reporter to filter signaling of rules starting to run

    fun feedReporter(resultsList: List<RuleCheckResult>, scene: IScene) =
        synchronized(this) {
            try {
                resultsList
                    .filter(::resultFilter)
                    .forEach { result ->
                        addResults(
                            result
                        )
                    }
                hotUpdate(scene)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to add results and hot update" }
            }
        }
}

/**
 * match either:
 * parametric rules ("sub rules")
 * sub-rules of an invariant
 * sanity rules before they're merged and finalized
 * sanity rules of Solana / Soroban
 */
val matchResultsForSubRulesAndSanity = { result: RuleCheckResult ->
    val rule = result.rule
    (rule is EcosystemAgnosticRule && rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule ||
    rule is CVLSingleRule &&
        (result.rule.ruleType is SpecType.Single.InvariantCheck ||
            rule.ruleGenerationMeta is SingleRuleGenerationMeta.WithMethodInstantiations ||
            (rule.ruleGenerationMeta is SingleRuleGenerationMeta.WithSanity &&
                rule.ruleGenerationMeta.sanity != SingleRuleGenerationMeta.Sanity.DISABLED_SANITY_CHECK &&
                rule.ruleGenerationMeta.sanity != SingleRuleGenerationMeta.Sanity.DONE)))
}

class ReporterContainer(private val reporters: List<OutputReporter>): OutputReporter {
    override fun getOutFile(): String {
        error("Reporter container can't return an output file, query each reporter independently")
    }

    override fun signalStart(rule: IRule) {
        reporters.forEach { reporter ->
            if (reporter.signalStartFilter(rule)) {
                reporter.signalStart(rule)
            }
        }
    }

    override fun resultFilter(result: RuleCheckResult): Boolean = true

    override fun addResults(results: RuleCheckResult) {
        reporters.forEach { reporter ->
            if (reporter.resultFilter(results)) {
                reporter.addResults(results)
            }
        }
    }

    override fun toFile(scene: IScene) {
        reporters.forEach { r ->
            try {
                r.toFile(scene)
            } catch (e: Exception) {
                Logger.alwaysError("Failed to output results of reporter ${r.javaClass.simpleName}", e)
            }
        }
    }

    override fun hotUpdate(scene: IScene) {
        reporters.forEach { r ->
            r.hotUpdate(scene)
        }
    }

}
