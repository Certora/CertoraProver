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
import datastructures.stdcollections.listOf
import rules.RuleCheckResult
import rules.TwoStageRound
import rules.sanity.TACSanityChecks
import sbf.tac.solanaOptimize
import spec.rules.EcosystemAgnosticRule
import vc.data.CoreTACProgram
import verifier.EncodedRule
import verifier.VerificationFlow


data class SolanaEncodedRule(override val rule: EcosystemAgnosticRule, override val code: CoreTACProgram) : EncodedRule<EcosystemAgnosticRule>
typealias SolanaEncodeResult = Result<SolanaEncodedRule>

class SolanaVerificationFlow(
    val elfFile: String
) : VerificationFlow<EcosystemAgnosticRule>() {
    override val webReportMainContractName: String = "SolanaMainProgram"

    override suspend fun buildRules(): Collection<SolanaEncodeResult> {
        return solanaSbfToTAC(elfFile)
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
        return if(!completedEncodedRule.rule.isGenerated){
            TACSanityChecks(vacuityCheckLevel = SanityValues.ADVANCED).generateRules(completedEncodedRule.rule, completedEncodedRule.code, completedResult)
        } else {
            listOf()
        }
    }

    override fun finalizeForRound(
        rule: EcosystemAgnosticRule,
        code: CoreTACProgram,
        stage: TwoStageRound
    ): CoreTACProgram {
        return when(stage){
            TwoStageRound.ROUNDLESS -> solanaOptimize(code, rule)
            TwoStageRound.FIRST_ROUND -> solanaOptimize(code, rule)
            TwoStageRound.SECOND_ROUND -> code
        }
    }
}