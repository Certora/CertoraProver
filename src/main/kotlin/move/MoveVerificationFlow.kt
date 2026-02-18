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

package move

import cli.SanityValues
import config.*
import datastructures.stdcollections.*
import diagnostics.*
import parallel.coroutines.parallelMapOrdered
import kotlin.io.path.*
import rules.*
import rules.sanity.TACSanityChecks
import spec.cvlast.typechecker.*
import spec.rules.*
import utils.*
import vc.data.*
import verifier.*
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

data class MoveEncodedRule(override val rule: EcosystemAgnosticRule, override val code: CoreTACProgram) : EncodedRule<EcosystemAgnosticRule>
typealias MoveEncodeResult = Result<MoveEncodedRule>

abstract class MoveVerificationFlow : VerificationFlow<EcosystemAgnosticRule>() {
    override val webReportMainContractName: String = "MoveMainProgram"
    abstract val moveScene: MoveScene

    override suspend fun buildRules(): Collection<MoveEncodeResult> {
        /*
            The Move prover currently does not support precise bitwise operation mode, due to all of the [Tag.Int] math
            done in [MemoryLayout].  If we need this in the future, we will also need to update [MemoryLayout] to
            constrain the maximum composed layout size to fit in 256 bits.  This will require (among other things) a
            different "ghost array" implementation.

            For now, we simply fail if this is enabled.
         */
        if (Config.Smt.UseBV.get()) {
            throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Precise bitwise operations are not supported for Move verification."
            )
        }

        val concurrencyLimit = Config.MaxConcurrentRules.get()
        return moveScene
            .getSelectedCvlmRules()
            .resultOrExitProcess(1, CVLError::printErrors)
            .sortedBy { it.ruleInstanceName }
            .parallelMapOrdered(concurrencyLimit) { _, rule ->
                annotateCallStack("rule.${rule.ruleInstanceName}") {
                    try {
                        val compiled = MoveToTAC.compileRule(rule, moveScene)
                        success(
                            MoveEncodedRule(
                                rule = rule.toEcosystemAgnosticRule(compiled.isSatisfy),
                                code = compiled.toCoreTAC(moveScene)
                            )
                        )
                    } catch (e: CertoraException){
                        failure(RuleEncodingException(rule.toEcosystemAgnosticRule(false /* We don't know better here */), e))
                    }
                }
            }
    }

    override fun buildContinuationRules(
        completedEncodedRule: EncodedRule<EcosystemAgnosticRule>,
        completedResult: RuleCheckResult.Leaf
    ): List<Result<EncodedRule<EcosystemAgnosticRule>>> {
        return if (!completedEncodedRule.rule.isGenerated) {
            TACSanityChecks(vacuityCheckLevel = SanityValues.BASIC).generateRules(completedEncodedRule.rule, completedEncodedRule.code, completedResult)
        } else {
            listOf()
        }
    }

    override fun finalizeForRound(
        rule: EcosystemAgnosticRule,
        code: CoreTACProgram,
        stage: TwoStageRound
    ): CoreTACProgram {
        /**
         * We always run [MoveToTAC.optimize] here, also in the case of [stage] being [TwoStageRound.SECOND_ROUND].
         * this means, the optimizations run twice (once for the first and once for the second round).
         *
         * This may cause performance slowdowns in case [MoveToTAC.optimize] executes a TAC transformation that
         * is slow.
         */
        return MoveToTAC.optimize(code)
    }
}
