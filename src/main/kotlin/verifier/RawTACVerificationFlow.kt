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

package verifier

import datastructures.stdcollections.listOf
import rules.RuleCheckResult
import rules.TwoStageRound
import spec.rules.EcosystemAgnosticRule
import vc.data.CoreTACProgram
import kotlin.Result.Companion.success

class RawTACVerificationFlow(val encodedRule: EncodedRule<EcosystemAgnosticRule>) : VerificationFlow<EcosystemAgnosticRule>() {
    override val webReportMainContractName: String = "TACMainProgram"

    override suspend fun buildRules(): Collection<Result<EncodedRule<EcosystemAgnosticRule>>> {
        return listOf(success(encodedRule))
    }

    override fun buildContinuationRules(
        completedEncodedRule: EncodedRule<EcosystemAgnosticRule>,
        completedResult: RuleCheckResult.Leaf
    ): List<Result<EncodedRule<EcosystemAgnosticRule>>> {
        return listOf()
    }

    override fun finalizeForRound(
        rule: EcosystemAgnosticRule,
        code: CoreTACProgram,
        stage: TwoStageRound
    ): CoreTACProgram {
        return code
    }
}