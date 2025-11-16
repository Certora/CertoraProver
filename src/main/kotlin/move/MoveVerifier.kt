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

import bridge.*
import config.*
import datastructures.stdcollections.*
import kotlin.io.path.*
import log.*
import parallel.coroutines.*
import report.*
import rules.*
import rules.CompiledRule.Companion.mapCheckResult
import scene.*
import scene.source.*
import spec.cvlast.*
import spec.cvlast.typechecker.*
import spec.genericrulegenerators.*
import spec.rules.*
import tac.*
import utils.*
import vc.data.*
import verifier.*
import verifier.mus.*

class MoveVerifier {
    private val modulePath = Config.MoveModulePath.get()
    private val moveScene = MoveScene(Path(modulePath))
    private val cvlScene = SceneFactory.getScene(DegenerateContractSource(modulePath))
    private val reporterContainer = ReporterContainer(listOf(ConsoleReporter))
    private val treeView = TreeViewReporter("MoveMainProgram", "", cvlScene)

    /**
        The entrypoint for verification of Move projects
    */
    suspend fun verify() {
        // See notes in `MoveMemory`
        if (Config.Smt.UseBV.get()) {
            throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Precise bitwise operations are not supported for Move verification."
            )
        }

        val concurrencyLimit = Config.MaxConcurrentRules.get()

        val rules = moveScene
            .getSelectedCvlmRules()
            .resultOrExitProcess(1, CVLError::printErrors)
            .sortedBy { it.ruleInstanceName }
            .parallelMapOrdered(concurrencyLimit) { _, rule ->
                val compiled = MoveToTAC.compileRule(rule, moveScene)
                EcosystemAgnosticRule(
                    ruleIdentifier = RuleIdentifier.freshIdentifier(compiled.rule.ruleInstanceName),
                    ruleType = when (compiled.rule) {
                        is CvlmRule.UserRule -> SpecType.Single.FromUser.SpecFile
                        is CvlmRule.TargetSanity -> SpecType.Single.BuiltIn(BuiltInRuleId.sanity)
                    },
                    isSatisfyRule = compiled.isSatisfy
                ) to compiled
            }

        treeView.buildRuleTree(rules.map { (rule, _) -> rule })

        rules.consuming().parallelMapOrdered(concurrencyLimit) { _, (rule, compiled) ->
            treeView.signalStart(rule)

            val coretac = compiled.toCoreTAC(moveScene)

            @OptIn(Config.DestructiveOptimizationsOption::class)
            val res = when (Config.DestructiveOptimizationsMode.get()) {
                DestructiveOptimizationsModeEnum.DISABLE -> {
                    verifyTAC(rule, coretac)
                }
                DestructiveOptimizationsModeEnum.ENABLE -> {
                    verifyTAC(rule, coretac.withDestructiveOptimizations(true))
                }
                DestructiveOptimizationsModeEnum.TWOSTAGE, DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> {
                    twoStageDestructiveOptimizationsCheck(rule, rule.ruleIdentifier.displayName, coretac) {
                        verifyTAC(rule, it)
                    }
                }
            }

            res.toCheckResult(cvlScene, rule).mapCatching { checkResult ->
                reporterContainer.addResults(checkResult)
                treeView.signalEnd(rule, checkResult)
                reporterContainer.hotUpdate(cvlScene)
            }
        }

        reporterContainer.toFile(cvlScene)
    }

    suspend fun verifyTAC(rule: EcosystemAgnosticRule, tac: CoreTACProgram): CompiledRule.CompileRuleCheckResult {
        return runCatching {
            val startTime = System.currentTimeMillis()
            val optimized = MoveToTAC.optimize(tac)
            val joinedResult = Verifier.JoinedResult(
                TACVerifier.verify(cvlScene, optimized, treeView.liveStatsReporter, rule)
            )
            val endTime = System.currentTimeMillis()
            ResultAndTime(joinedResult, VerifyTime.WithInterval(startTime, endTime))
        }.mapCheckResult(
            ruleName = rule.ruleIdentifier.displayName,
            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
        )
    }
}
