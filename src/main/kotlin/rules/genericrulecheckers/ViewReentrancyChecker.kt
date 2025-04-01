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
import report.StatusReporter
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.ViewReentrancyGenerator
import tac.DumpTime
import utils.CertoraException
import vc.data.taccmdutils.CallAttributes.getUnresolvedCalls
import datastructures.stdcollections.*
import rules.*
import utils.mapNotNullToSet

private val logger = Logger(LoggerTypes.GENERIC_RULE)
private val loggerTimes = Logger(LoggerTypes.TIMES)

/**
 * This checker checks for existence of view reentrancy vulnerability.
 * For that it checks that for every public view function of the main contract, the result of the view function after
 * an unresolved call is either equal to the result of the view function at the start of the rule or to the result of
 * the view function at the end of the rule.
 * There are two ways to check this depending of the flag -exhaustiveViewReentrancy.
 * 1. Exhaustive - add an assert for each pair of view functions that checks the above holds for the two view functions.
 * This checks what we want because assume for a given unresolved call we have the list of all the view functions that
 * are equal to the result at start followed by all functions that are equal at the end, then there is the pair of
 * functions in which one if from the start and one from the end.
 * 2. The default - check the above holds for all view function in the same assert.
 */
data object ViewReentrancyChecker : BuiltInRuleCustomChecker<ViewReentrancyGenerator>() {
    override val eId: BuiltInRuleId
        get() = BuiltInRuleId.viewReentrancy

    /**
     * If the contract contains view functions:
     * For each instantiated compiled rule, instrument the TACProgram for checking view reentrancy.
     * Check the instrumented rule.
     */
    override suspend fun doCheck(
        ruleChecker: RuleChecker,
        rule: IRule,
    ): RuleCheckResult {
        check(rule is CVLSingleRule)
        val scene = ruleChecker.scene
        val contractName = ruleChecker.contractName
        logger.info { "Checking the builtin rule with code ${rule.declarationId}" }
        // get cvl for RuleChecker instance
        val ruleCompileStart = System.currentTimeMillis()
        return CompiledRule.subRules(scene, ruleChecker.cvl, rule).mapCatching { codesToCheck ->
            val ruleCompileEnd = System.currentTimeMillis()
            loggerTimes.info {
                "Compiling the builtin rule ${rule.declarationId} end in" +
                    " ${(ruleCompileEnd - ruleCompileStart) / 1000}s"
            }
            // Note the current contract is not necessarily THIS.
            val publicFunctions = scene.getContract(contractName).getStandardMethods()
            val (viewFunctions, nonViewFunctions) = publicFunctions.partition {
                it.evmExternalMethodInfo?.stateMutability?.isView == true
            }

            check(
                viewFunctions.isEmpty() || nonViewFunctions.isEmpty() ||
                    codesToCheck.isNotEmpty()
            ) {
                "There are view functions and functions to check but Codes to check list in ${rule.declarationId} " +
                    "is empty"
            }
            val checkableTACs = codesToCheck.map { (currCode, currMethodInst, sanity, singleRule) ->
                val methodNames = currMethodInst.values.map { it.toExternalABIName() }
                check(methodNames.isNotEmpty()) {
                    "Expected the compiled builtin rule ${rule.declarationId} " +
                        "to contain method parameters, but got none."
                }

                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    currCode,
                    ReportTypes.GENERIC_RULE,
                    StaticArtifactLocation.Outputs,
                    DumpTime.PRE_TRANSFORM
                )

                val unresolved = getUnresolvedCalls(currCode).toList()
                val contractAddress = scene.getContract(contractName).addressSym
                val viewFunctionSighashes =
                    viewFunctions.mapNotNullToSet { it.sigHash?.n } // mapNotNull to avoid throwing here
                val viewReentrancyInstrumenting = ViewReentrancyInstrumenting(
                    viewFunctionSighashes, contractAddress, contractName, scene, unresolved, currCode
                )

                val injected = viewReentrancyInstrumenting.addInstrumentation()

                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    injected,
                    ReportTypes.GENERIC_RULE,
                    StaticArtifactLocation.Outputs,
                    DumpTime.POST_TRANSFORM
                )
                StatusReporter.registerSubrule(singleRule)
                CheckableTAC(
                    injected,
                    currMethodInst, sanity, singleRule
                )
            }
            val result = ruleChecker.compiledSingleRuleCheck(rule, checkableTACs)
            val reducedResults = (result as? RuleCheckResult.Multi)?.results ?: emptyList()
            RuleCheckResult.Multi(rule, reducedResults, RuleCheckResult.MultiResultType.PARAMETRIC)
        }.getOrElse { e ->
            Logger.always(
                "Failed to compile the builtin rule ${rule.declarationId} due to $e",
                respectQuiet = false
            )
            RuleCheckResult.Error(
                rule,
                CertoraException.fromExceptionWithRuleName(e, rule.declarationId),
            ).also { ruleChecker.treeViewReporter.signalEnd(rule, it) }
        }
    }

    override val functionSetCanBeEmpty = true

}
