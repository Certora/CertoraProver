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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package sbf

import cli.SanityValues
import datastructures.stdcollections.*
import rules.RuleCheckResult
import rules.TwoStageRound
import rules.sanity.TACSanityChecks
import sbf.tac.TACMultiAssert
import sbf.tac.solanaOptimize
import spec.cvlast.RuleIdentifier
import spec.cvlast.SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertsOnly
import spec.cvlast.SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck
import spec.rules.EcosystemAgnosticRule
import utils.isSuccessAnd
import vc.data.CoreTACProgram
import verifier.EncodedRule
import verifier.VerificationFlow

data class SolanaEncodedRule(override val rule: EcosystemAgnosticRule, override val code: CoreTACProgram) : EncodedRule<EcosystemAgnosticRule>
typealias SolanaEncodeResult = Result<SolanaEncodedRule>

class SolanaVerificationFlow(
    elfFile: String
) : VerificationFlow<EcosystemAgnosticRule>() {
    private val rules: List<SolanaEncodeResult> = solanaSbfToTAC(elfFile)

    // a manual impl of associate to ensure no duplicates
    private val baseRuleToVacuityCheck: Map<RuleIdentifier, SolanaEncodedRule> = buildMap {
        val rules = this@SolanaVerificationFlow.rules

        for (result in rules) {
            val encodedRule = result.getOrNull() ?: continue
            val ruleType = encodedRule.rule.ruleType as? VacuityCheck ?: continue
            val baseRuleIdentifier = ruleType.getOriginatingRule().ruleIdentifier

            val old = this.put(baseRuleIdentifier, encodedRule)
            check(old == null) { "for rule: `$baseRuleIdentifier`: found more than one generated vacuity check" }
        }
    }

    private fun nonVacuityRules() = this.rules.filter { result ->
        result.isFailure || result.isSuccessAnd { it.rule.ruleType !is VacuityCheck }
    }

    override val webReportMainContractName: String = "SolanaMainProgram"

    /**
     * note that work here was already done upfront, on class construction
     * (and either way, it was blocking. so no benefit from async)
     */
    override suspend fun buildRules(): List<SolanaEncodeResult> {
        return this.nonVacuityRules()
    }

    /**
     * Called upon termination of the rule [completedEncodedRule] (in results [completedResult]).
     * See details also KDoc in [VerificationFlow.buildContinuationRules]
     *
     * This will trigger the advanced sanity checks, in particular the vacuity check, in case the result was UNSAT.
     */
    override fun buildContinuationRules(
        completedEncodedRule: EncodedRule<EcosystemAgnosticRule>,
        completedResult: RuleCheckResult.Leaf
    ): List<Result<EncodedRule<EcosystemAgnosticRule>>> {
        val rule = completedEncodedRule.rule
        val code = completedEncodedRule.code

        if (rule.isGenerated) {
            return emptyList()
        }

        val tacSanityRules = TACSanityChecks(SanityValues.ADVANCED).generateRules(rule, code, completedResult)

        val vacuityCheck = if (
            completedResult is RuleCheckResult.Single
            && completedResult.result == completedResult.expectedResult()
        ) {
            val ruleIdentifier = if (rule.ruleType is AssertsOnly) {
                rule.ruleIdentifier.parentIdentifier
            } else {
                rule.ruleIdentifier
            }
            baseRuleToVacuityCheck[ruleIdentifier]?.let { Result.success(it) }
        } else {
            null
        }

        return tacSanityRules + listOfNotNull(vacuityCheck)
    }

    override fun finalizeForRound(
        rule: EcosystemAgnosticRule,
        code: CoreTACProgram,
        stage: TwoStageRound
    ): CoreTACProgram {
        val optimized = when(stage){
            TwoStageRound.ROUNDLESS -> solanaOptimize(code, rule)
            TwoStageRound.FIRST_ROUND -> solanaOptimize(code, rule)
            TwoStageRound.SECOND_ROUND -> code
        }
        return TACMultiAssert.assumeProverAsserts(optimized, rule)
    }
}