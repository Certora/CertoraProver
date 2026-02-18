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

package wasm

import cli.SanityValues
import cli.WasmHost
import config.Config
import datastructures.stdcollections.listOf
import rules.RuleCheckResult
import rules.TwoStageRound
import rules.sanity.TACSanityChecks
import spec.rules.EcosystemAgnosticRule
import vc.data.CoreTACProgram
import verifier.EncodedRule
import verifier.VerificationFlow
import wasm.host.NullHost
import wasm.host.near.NEARHost
import wasm.host.soroban.SorobanHost
import java.io.File
import kotlin.collections.orEmpty


data class WasmEncodedRule(override val rule: EcosystemAgnosticRule, override val code: CoreTACProgram) : EncodedRule<EcosystemAgnosticRule>
typealias WasmEncodeResult = Result<WasmEncodedRule>

class WasmVerificationFlow(
    val fileName: String
) : VerificationFlow<EcosystemAgnosticRule>() {
    override val webReportMainContractName: String = "SorobanMainProgram"

    override suspend fun buildRules(): Collection<WasmEncodeResult> {
        val env = when(Config.WASMHostEnv.get()) {
            WasmHost.SOROBAN -> SorobanHost
            WasmHost.NEAR -> NEARHost
            WasmHost.NONE -> NullHost
        }
        return WasmEntryPoint.webAssemblyToTAC(
            inputFile = File(fileName),
            selectedRules = Config.WasmEntrypoint.getOrNull().orEmpty(),
            env = env,
            optimize = true
        )
    }

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
    ): CoreTACProgram{
        // Destructive optimizations need to be moved from [wasm.WasmEntryPoint.wasmProgramToTAC] to here and run
        // when stage == TwoStageRound.FIRST_ROUND or ROUNDLESS.
        return when(stage){
            TwoStageRound.ROUNDLESS -> code
            TwoStageRound.FIRST_ROUND -> code
            TwoStageRound.SECOND_ROUND -> code
        }
    }
}