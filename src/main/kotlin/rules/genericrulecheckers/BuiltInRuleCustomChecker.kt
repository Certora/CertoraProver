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

import analysis.icfg.Inliner
import config.Config
import datastructures.stdcollections.*
import log.*
import report.*
import report.callresolution.CallResolutionTableBase
import rules.CompiledRule
import rules.IsFromCache
import rules.RuleCheckResult
import solver.SolverResult
import spec.genericrulegenerators.BuiltInRuleGenerator
import spec.genericrulegenerators.BuiltInRuleId
import utils.CertoraException
import utils.mapNotNull
import vc.data.CallSummary
import vc.data.TACCallType
import vc.data.TACCmd
import java.util.stream.Collectors
import rules.*
import spec.cvlast.*
import spec.genericrulegenerators.HasDelegateCalls
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta


private val logger = Logger(LoggerTypes.COMMON)
private val loggerTimes = Logger(LoggerTypes.TIMES)

/**
 * A custom checker of an [IRule] whose [IRule.ruleType] is [SpecType.Single.BuiltIn].
 * It expects to check a builtin rule whose [BuiltInRuleGenerator] is of type [G] and [BuiltInRuleGenerator.eId] equals [eId].
 * If a builtin rule does not have a custom checker defined, it would be checked using the default checker in [rules.RuleChecker].
 */
sealed class BuiltInRuleCustomChecker<G : BuiltInRuleGenerator> {

    abstract val eId: BuiltInRuleId
    open val functionSetCanBeEmpty = false

    /**
     * Checks [rule].
     */
    suspend fun check(
        ruleChecker: RuleChecker,
        rule: IRule
    ): RuleCheckResult {
        check(rule.ruleType is SpecType.Single.BuiltIn) {
            "Expected to check a builtin rule, but got a rule whose ruleType is ${rule.ruleType} (rule: ${rule.declarationId})"
        }

        return doCheck(ruleChecker, rule)
    }

    protected abstract suspend fun doCheck(
        ruleChecker: RuleChecker,
        rule: IRule
    ): RuleCheckResult

    companion object {
        /**
         * Returns null if there is no custom checker for the given [birType].
         * If there is no custom checker, the default checker in [rules.RuleChecker] will be used instead.
         */
        fun fromBirType(birType: SpecType.Single.BuiltIn): BuiltInRuleCustomChecker<*> =
            when (birType.birId) {
                BuiltInRuleId.hasDelegateCalls -> {
                    HasDelegateCallsChecker
                }
                BuiltInRuleId.trustedMethods -> {
                    TrustedMethodChecker()
                }
                BuiltInRuleId.safeCasting -> {
                    require(Config.SafeCastingBuiltin.get()) {
                        "The safeCasting rule can't run unless ${Config.SafeCastingBuiltin.pythonName} is set to true " +
                            "on the command line (or conf file)"
                    }
                    SafeCastingChecker
                }
                BuiltInRuleId.msgValueInLoopRule -> {
                    MsgValueInLoopChecker
                }

                BuiltInRuleId.deepSanity ->
                    DeepSanityChecker

                BuiltInRuleId.viewReentrancy -> {
                    ViewReentrancyChecker
                }

                BuiltInRuleId.sanity ->
                    SanityChecker

                BuiltInRuleId.verifyFoundryFuzzTests,
                BuiltInRuleId.verifyFoundryFuzzTestsNoRevert -> FoundryFuzzTestsChecker(birType.birId)
            }
    }

    data object HasDelegateCallsChecker :
        BuiltInRuleCustomChecker<HasDelegateCalls>() {

        override val eId: BuiltInRuleId
            get() = BuiltInRuleId.hasDelegateCalls

        override suspend fun doCheck(
            ruleChecker: RuleChecker,
            rule: IRule,
        ): RuleCheckResult {
            check(rule is CVLSingleRule)
            logger.info { "Checking StaticRuleWithCode ${rule.declarationId}" }
            val ruleCompileStart = System.currentTimeMillis()
            return CompiledRule.staticRules(ruleChecker.scene, ruleChecker.cvl, rule).mapCatching { codesToCheck ->
                val ruleCompileEnd = System.currentTimeMillis()
                loggerTimes.info { "Compiling the builtin rule ${rule.declarationId} end in ${(ruleCompileEnd - ruleCompileStart) / 1000}s" }
                check(codesToCheck.isNotEmpty()) { "Codes to check list in ${rule.declarationId} is empty" }
                val rulesCodesAndMethodNames = codesToCheck.map { (currCode, currMethodInst) ->
                    val methodName = currMethodInst.values.singleOrNull()?.toExternalABIName()
                        ?: throw Exception("Expected the compiled builtin rule ${rule.declarationId} to contain one method parameter, but got $currMethodInst")
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
                    val delegateCalls = currCode.parallelLtacStream().mapNotNull { (_, cmd) ->
                        val callSummary =
                            if (cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.v is Inliner.CallStack.PushRecord) {
                                cmd.annot.v.summary
                            } else if (cmd is TACCmd.Simple.SummaryCmd && cmd.summ is CallSummary) {
                                cmd.summ
                            } else {
                                null
                            }
                        callSummary?.takeIf { it.callType == TACCallType.CALLCODE || it.callType == TACCallType.DELEGATE }
                    }.collect(Collectors.toList())

                    val endTime = System.currentTimeMillis()

                    if (delegateCalls.isEmpty()) {
                        RuleCheckResult.Single.Basic(
                            rule = newRule,
                            result = SolverResult.UNSAT,
                            VerifyTime.WithInterval(startTime, endTime),
                            ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                                details = Result.success("The method $methodName does not perform any delegate calls"),
                                dumpGraphLink = null,
                                isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                                isSolverResultFromCache = IsFromCache.INAPPLICABLE,

                            ),
                            ruleAlerts = emptyList(),
                            callResolutionTable = CallResolutionTableBase.Empty,
                        )
                    } else {
                        RuleCheckResult.Single.Basic(
                            rule = newRule,
                            result = SolverResult.SAT,
                            VerifyTime.WithInterval(startTime, endTime),
                            ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                                failureResultMeta = listOf(RuleCheckResult.RuleFailureMeta.StaticCheck("The method $methodName performs delegate calls")),
                                dumpGraphLink = null,
                                isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                                isSolverResultFromCache = IsFromCache.INAPPLICABLE
                            ),
                            ruleAlerts = emptyList(),
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

}
