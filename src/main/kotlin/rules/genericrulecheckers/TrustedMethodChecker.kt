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

import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import log.*
import report.*
import report.callresolution.CallResolutionTableBase
import rules.*
import scene.source.Sighash
import solver.SolverResult
import spec.CVLKeywords
import spec.CVLReservedVariables
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.TrustedMethods
import spec.isWildcard
import utils.*
import vc.data.CallSummary
import vc.data.TACCallType
import vc.data.TACCmd
import java.math.BigInteger
import java.util.stream.Collectors
import kotlin.io.path.Path

@Serializable
data class TrustedMethodCall(val contractAddress: String, val methodSighash: List<Sighash>)

/**
 * This is a builtin rule that can be used via `use builtin rule trustedMethods` in CVL.
 *
 * The rule is a parametric rule that succeeds for the parametric method if all external calls are fully resolved
 * (i.e. sighash and target are known) and are on the defined trusted methods list.
 * Otherwise, the analysis reports a violation for this method and outputs all unresolved calls to the Rule Notification Tab.
 *
 * Please note that the rule fails as soon as there is any call that is unresolved due to a missing target or a missing unresolved sighash,
 * for instance as the deployed contract requires linking.
 */
class TrustedMethodChecker :
        BuiltInRuleCustomChecker<TrustedMethods>() {

    private fun tryLoadTrustedMethodList(): Map<String,Set<String>> {
        val resourceLabel = "trustedMethods:"
        @Suppress("ForbiddenMethodCall")
        val resourcesProvided =
            Config.ResourceFiles.getOrNull()?.filter { it.startsWith(resourceLabel) }?.map { it.removePrefix(resourceLabel) }?.ifEmpty { null }
                ?: error("No input file specified for TrustedMethodChecker, " +
                    "please provide a JSON file by adding \"prover_resource_files\": [${resourceLabel}:<TRUSTED_METHODS_FILE>.json] to your config file.")
        val trustedMethodInputFile = resourcesProvided.first().trim()
        if (resourcesProvided.size > 1) {
            Logger.alwaysWarn("Got more than one resource file for label '$resourceLabel'. Selecting $trustedMethodInputFile")
        }

        val wrappedPath = Path(ArtifactFileUtils.wrapPathWith(trustedMethodInputFile, Config.getSourcesSubdirInInternal())).toFile()
        if (!wrappedPath.exists()) {
            error("Provided trusted method file string is not a file $trustedMethodInputFile")
        }
        return Json.decodeFromString<Map<String,Set<String>>>(wrappedPath.readText())
    }
    private fun Map<String,Set<String>>.validateTrustedCallList(){
        val keysWithEmptySigHashLists = this.filter { it.value.isEmpty() }
        if(keysWithEmptySigHashLists.isNotEmpty()){
            val msg = "The trusted method list is malformed, it contains an empty list of sighashes for the key(s) (${keysWithEmptySigHashLists})."
            CVTAlertReporter.reportAlert(CVTAlertType.CVL,CVTAlertSeverity.ERROR, jumpToDefinition = null, message = msg, hint = null)
            error(msg)
        }
        val invalidSigHashEntries = this.filter { sighash -> !sighash.value.all{it.isWildcard() || it.isFallback() || @Suppress("ForbiddenMethodCall")it.startsWith("0x")} }

        if(invalidSigHashEntries.isNotEmpty()){
            val msg = "The trusted method list is malformed, all entries must be either a wildcard (${CVLKeywords.wildCardExp.keyword}) or ${CVLReservedVariables.certoraFallbackDisplayName} or " +
                "a method sighash starting in 0x, but got ${invalidSigHashEntries}."
            CVTAlertReporter.reportAlert(CVTAlertType.CVL,CVTAlertSeverity.ERROR, jumpToDefinition = null, message = msg, hint = null)
            error(msg)
        }

        val possibleSimplifications = this.filter { e -> e.value.any { it.isWildcard() } && e.value.size > 1 }
        if(possibleSimplifications.isNotEmpty()){
            CVTAlertReporter.reportAlert(CVTAlertType.CVL,CVTAlertSeverity.INFO, jumpToDefinition = null, message = "The trusted method list can be simplified, it contains a wildcard " +
                "for a sighash and explicit sighashes (${possibleSimplifications}).", hint = null)
        }
    }

    private val trustedCallList = tryLoadTrustedMethodList().also { it.validateTrustedCallList()}
    override val eId: BuiltInRuleId
        get() = BuiltInRuleId.trustedMethods


    private fun Sighash.isFallback() = this == CVLReservedVariables.certoraFallbackDisplayName
    private fun String.isWildcardOrEqualsAddress(address: String) = (this.isWildcard() || this.equals(address, ignoreCase = true))
    private fun sigResolutionPrettyPrint(sig: BigInteger?) = sig?.let { "0x${it.toString(16)}" } ?: CVLReservedVariables.certoraFallbackDisplayName
    private fun isTrustedContract(contractAddress: String): Boolean {
        return trustedCallList.keys.any { tC -> tC.isWildcardOrEqualsAddress(contractAddress) }
    }

    private fun isTrustedContractAndSighash(contractAddress: String, sigHash: BigInteger?): Boolean {
        return trustedCallList.any { tM ->
            tM.key.isWildcardOrEqualsAddress(contractAddress)
                    && tM.value.any { it.isWildcard() || sigHash == null && it.isFallback() || sigHash != null && sigHash == BigInteger(it.removePrefix("0x"), 16) }
        };
    }

    override suspend fun doCheck(
        ruleChecker: RuleChecker,
        rule: IRule,
    ): RuleCheckResult {
        check(rule is CVLSingleRule){"Expected an instance of type ${CVLSingleRule::class.java}, but found ${rule}"}
        return CompiledRule.staticRules(ruleChecker.scene, ruleChecker.cvl, rule).mapCatching { codesToCheck ->
            val rulesCodesAndMethodNames = codesToCheck.map { (currCode, currMethodInst) ->
                val methodName = currMethodInst.values.singleOrNull()?.toExternalABIName()
                        ?: error("Expected the compiled builtin rule ${rule.declarationId} to contain one method parameter, but got $currMethodInst")
                val newRule = rule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                                SingleRuleGenerationMeta.Sanity.DISABLED_SANITY_CHECK,
                                currMethodInst.range(),
                                methodName,
                        ),
                        ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier(methodName)
                )

                Triple(newRule, currCode, methodName)
            }
            val results = rulesCodesAndMethodNames.map { (newRule, currCode, methodName) ->

                ruleChecker.reporter.signalStart(newRule)
                ruleChecker.treeViewReporter.registerSubruleOf(newRule, rule)
                ruleChecker.treeViewReporter.signalStart(newRule)
                StatusReporter.registerSubrule(newRule)

                val startTime = System.currentTimeMillis()
                val allCalls = currCode.parallelLtacStream()
                        .mapNotNull { (_, cmd) ->
                            if (cmd is TACCmd.Simple.SummaryCmd && cmd.summ is CallSummary) {
                                cmd.summ
                            } else {
                                null
                            }
                        }
                        .filter { it.callType != TACCallType.STATIC && it.callType != TACCallType.CREATE }
                        .collect(Collectors.toList())


                val ruleAlerts = allCalls.filterNotNull().flatMap { call ->
                    if (call.sigResolution.isEmpty()) {
                        if (isTrustedContract(call.toVar.toSMTRep())) {
                            listOf(RuleAlertReport.Warning("External call with an unknown method sighash - the contract address (${call.toVar.toSMTRep()}) is trusted. See logs for details."))
                        } else {
                            listOf(RuleAlertReport.Warning("External call with an unknown method sighash - the contract address (${call.toVar.toSMTRep()}) is untrusted. See logs for details."))
                        }
                    } else {
                        call.sigResolution.map { sighash ->
                            if (isTrustedContractAndSighash(call.toVar.toSMTRep(), sighash)) {
                                RuleAlertReport.Info("Found a trusted call on contract address ${call.toVar.toSMTRep()} where the method sighash " +
                                    "is resolved to ${sigResolutionPrettyPrint(sighash)}.")
                            } else if (isTrustedContract(call.toVar.toSMTRep())) {
                                RuleAlertReport.Warning("External call with an untrusted method sighash (${sigResolutionPrettyPrint(sighash)}) - " +
                                    "the contract address (${call.toVar.toSMTRep()}) is trusted. See logs for details.")
                            } else {
                                RuleAlertReport.Warning("External call with an untrusted method sighash (${sigResolutionPrettyPrint(sighash)}) and " +
                                    "an untrusted contract address. See logs for details.")
                            }
                        }
                    }.map { it to call }
                }

                ruleAlerts.map {
                    Logger.always("${it.first.msg} Originates from ${it.second}", respectQuiet = false)
                }

                val endTime = System.currentTimeMillis()

                if (ruleAlerts.none { it.first is RuleAlertReport.Warning }) {
                    RuleCheckResult.Single.Basic(
                            rule = newRule,
                            result = SolverResult.UNSAT,
                            VerifyTime.WithInterval(startTime, endTime),
                            ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                                    details = Result.success("The method $methodName does not call any non-trusted method"),
                                    dumpGraphLink = null,
                                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                                    isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                            ),
                            ruleAlerts = RuleAlertReport.flatten(ruleAlerts.map { it.first }),
                            callResolutionTable = CallResolutionTableBase.Empty,
                    )
                } else {
                    RuleCheckResult.Single.Basic(
                            rule = newRule,
                            result = SolverResult.SAT,
                            VerifyTime.WithInterval(startTime, endTime),
                            ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                                    failureResultMeta = listOf(RuleCheckResult.RuleFailureMeta.StaticCheck("The method $methodName does call an untrusted method")),
                                    dumpGraphLink = null,
                                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                                    isSolverResultFromCache = IsFromCache.INAPPLICABLE
                            ),
                            ruleAlerts = RuleAlertReport.flatten(ruleAlerts.map { it.first }),
                            callResolutionTable = CallResolutionTableBase.Empty,
                    )
                }.also { ruleChecker.treeViewReporter.signalEnd(newRule, it) }
            }
            RuleCheckResult.Multi(rule, results, RuleCheckResult.MultiResultType.PARAMETRIC)
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
}
