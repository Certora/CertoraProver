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

package rules.genericrulecheckers

import config.ReportTypes
import log.*
import rules.RuleCheckResult
import rules.RuleChecker
import scene.IScene
import scene.ITACMethod
import scene.MethodAttribute
import utils.Range
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.genericrulegenerators.InstrumentingBuiltInRuleGenerator
import tac.DumpTime
import vc.data.CoreTACProgram
import verifier.ChainedMethodTransformers
import verifier.ContractUtils
import verifier.MethodToCoreTACTransformer
import java.math.BigInteger

private val logger = Logger(LoggerTypes.GENERIC_RULE)

abstract class InstrumentingBuiltinRuleChecker<T: InstrumentingBuiltInRuleGenerator> :
    BuiltInRuleCustomChecker<T>() {

    // suspicious that currentContractId == m.getContainingContract().instanceId always, and thus API can be simplified
    abstract fun instrumentingTransform(
        scene: IScene,
        currentContractId: BigInteger,
        range: Range,
        m: ITACMethod
    ): CoreTACProgram

    private fun addCheckToContract(scene: IScene,
                                   currentContractId: BigInteger,
                                   range: Range
    ): IScene{
        val newScene = scene.fork(scene.forkInfo)
        newScene.mapContractMethodsInPlace("${eId}_transform") { _, method ->
            if (method.getContainingContract().instanceId != currentContractId) {
                // we only want to instrument the contract we care about
                return@mapContractMethodsInPlace
            }

            var tacProgram = method.code as CoreTACProgram
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                tacProgram,
                ReportTypes.GENERIC_RULE,
                StaticArtifactLocation.Outputs,
                DumpTime.PRE_TRANSFORM
            )

            if (method.attribute is MethodAttribute.Unique.Fallback){
                logger.debug{"In fallback ${method.name}"}
            }

            if (method.attribute !is MethodAttribute.Unique.Constructor) {
                logger.debug { "transform ${method.name}" }
                ContractUtils.transformMethod(
                    method,
                    ChainedMethodTransformers(
                        datastructures.stdcollections.listOf(
                            // uniquify
                            MethodToCoreTACTransformer(ReportTypes.GENERIC_RULE) { m: ITACMethod ->
                                instrumentingTransform(scene, currentContractId, range, m)
                            }
                        )
                    )
                )
                tacProgram = method.code as CoreTACProgram
                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    tacProgram,
                    ReportTypes.GENERIC_RULE,
                    StaticArtifactLocation.Outputs,
                    DumpTime.POST_TRANSFORM
                )

                logger.debug { "processed method: ${method.name}" }
            }
        }
        return newScene
    }

    override suspend fun doCheck(
        ruleChecker: RuleChecker,
        rule: IRule
    ): RuleCheckResult {
        check(rule is CVLSingleRule)
        val scene = ruleChecker.scene
        logger.debug { "Instrumenting with $eId checks" }
        val currentContractId = scene.getContract(ruleChecker.contractName).instanceId
        logger.debug { "currentContract doCheck parameter $currentContractId" }
        val newScene = addCheckToContract(
            scene,
            currentContractId,
            rule.range
        )
        logger.info { "Checking the CVLSingleRule ${rule.declarationId}" }
        // same as [ruleChecker] but with a new scene
        val newRuleChecker = RuleChecker(
            scene = newScene,
            cvl = ruleChecker.cvl,
            contractName = ruleChecker.contractName,
            reporter = ruleChecker.reporter,
            treeViewReporter = ruleChecker.treeViewReporter,
            summaryMonitor = ruleChecker.summaryMonitor
        )
        return newRuleChecker.singleRuleCheck(rule)
    }
}
