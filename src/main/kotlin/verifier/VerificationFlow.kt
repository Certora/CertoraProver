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

import config.Config
import config.DestructiveOptimizationsModeEnum
import datastructures.stdcollections.listOf
import diagnostics.annotateCallStackAsync
import org.jetbrains.annotations.TestOnly
import report.ReporterContainer
import report.TreeViewReporter
import rules.CompiledRule
import rules.CompiledRule.Companion.mapCheckResult
import rules.IsFromCache
import rules.ResultAndTime
import rules.RuleCheckResult
import rules.TwoStageRound
import rules.VerifyTime
import rules.twoStageDestructiveOptimizationsCheck
import sbf.tac.TACMultiAssert
import scene.IScene
import spec.rules.SingleRule
import utils.CertoraException
import utils.uncheckedAs
import vc.data.CoreTACProgram
import vc.data.withDestructiveOptimizations
import java.io.Closeable
import datastructures.stdcollections.*
import instrumentation.transformers.SnippetRemover
import log.Logger
import log.LoggerTypes
import parallel.coroutines.parallelMapOrdered
import report.ConsoleReporter
import scene.SceneFactory
import spec.rules.EcosystemAgnosticRule
import spec.rules.IRule
import utils.coRunCatching
import utils.`impossible!`

private val logger = Logger(LoggerTypes.VERIFICATION_FLOW)

/**
 * The successful result of [VerificationFlow.buildRules] - couples a pair of
 * a rule and the compiled code of the rule
 */
interface EncodedRule<R : SingleRule> {
    val rule: R
    val code: CoreTACProgram
}
/**
 * An exception that was thrown in [VerificationFlow.buildRules] when encoding the [rule]
 * into TAC.
 */
class RuleEncodingException(
    val rule: IRule,
    val exception: CertoraException
) : Exception("In $rule: ${exception.message}")

/**
 * Defines the complete verification pipeline for the Certora Prover.
 *
 * This abstract class orchestrates the end-to-end verification process: transforming input into
 * verification rules, optimizing TAC programs, executing verification via the solver, and
 * managing result reporting. It supports multiple verification modes including single-stage
 * and two-stage destructive optimizations, and handles dynamic rule generation (e.g., sanity
 * checks) based on verification results.
 *
 * The verification flow processes rules through a queue-based system, building a tree of rules
 * and subrules while tracking progress and results via reporters.
 *
 * Type parameter:
 * - R: The specific rule type extending SingleRule - typically a EcosystemAgnosticRule
 */
abstract class VerificationFlow<R : SingleRule> : Closeable {
    /**
     * The name that appears in the rule report below the job status (Executed, Problem, Running...)
     * Note that this string is also used in the rule pages as the "main contract" that
     * the job is associated to. Changing this name also impacts the rules page.
     */
    abstract val webReportMainContractName: String?
    private val reporterContainer: ReporterContainer = ReporterContainer(listOf(ConsoleReporter))
    private val scene: IScene = SceneFactory.EMPTY_SCENE
    private val treeViewReporter: TreeViewReporter = TreeViewReporter(webReportMainContractName ?: this::class.simpleName.orEmpty(), "")

    // Interface methods below

    /**
     * Transforms input into a list of encoded rules (rule + TAC program pairs) ready for verification.
     * TAC transformations applicable to both two-stage rounds should already be applied.
     *
     * The returned results can also be non-top-level rules, such as the cvlr based vacuity check. For these
     * derived rules it's important for reporting that the returned rule type is
     * [spec.cvlast.SpecType.Single.GeneratedFromBasicRule]
     */
    abstract suspend fun buildRules(): Collection<Result<EncodedRule<R>>>

    /**
     * A callback to generate additional rules based on rules that have been completed
     * The typical use case is the vacuity check, that will be triggered on the SAT result of the base rule.
     *
     * Note 1: Rules shall be generated but not executed by this method.
     * Note 2: All rules returned will be scheduled for execution, this can cause indefinite loops, for instance,
     * if this method returns the rule [completedEncodedRule] itself - or if there are cycles in the scheduling
     * graph induced by the rules generated.
     */
    abstract fun buildContinuationRules(
        completedEncodedRule: EncodedRule<R>,
        completedResult: RuleCheckResult.Leaf
    ): List<Result<EncodedRule<R>>>

    /**
     * Applies round-specific optimizations and transformations to the TAC program.
     * Consider performance implications when choosing optimizations: Transformations
     * that can be done in encodeToTAC should be implemented there and not in optimize.
     *
     * In two stage mode optimize is called twice. The first time with [stage] equals
     * to [TwoStageRound.FIRST_ROUND] the second time with [TwoStageRound.FIRST_ROUND].
     *
     * For none two stage modes, [stage] is [TwoStageRound.ROUNDLESS].
     */
    abstract fun finalizeForRound(rule: R, code: CoreTACProgram, stage: TwoStageRound): CoreTACProgram

    // Default implementations below

    /**
     * Main verification entry point: encodes input to TAC, solves each rule sequentially,
     * and generates subrules as needed. Builds and updates the rule tree for reporting.
     */
    suspend fun solve(): List<RuleCheckResult.Leaf> {
        val results = mutableListOf<RuleCheckResult.Leaf>()
        fun reportRuleEndResult(rule: IRule, result: RuleCheckResult.Leaf) {
            treeViewReporter.signalEnd(rule, result)
            reporterContainer.addResults(result)
            results.add(result)
            logger.info { "Solving of rule ${rule.ruleIdentifier} completed (result: ${result.javaClass.simpleName})" }
        }

        val encodedRules = buildRules()
        /**
         *  Builds the initial tree of rules - Note that not all rules that are
         *  returned by [buildRules] are top level rules. In Solana, we have
         *  a vacuity check based on cvlr.
         */
        val (successFullyEncodedRules, failures) = encodedRules
            .partition { it.isSuccess }
        val ruleFailures = failures.mapNotNull { res ->
            val exception = res.exceptionOrNull()
            if (exception is RuleEncodingException) {
                val rule = exception.rule
                rule to RuleCheckResult.Error(rule, exception)
            } else {
                logger.warn { "Received an exception that cannot be associated to a specific rule.\n $exception" }
                null
            }
        }

        logger.info { "EncodeToTAC produced ${successFullyEncodedRules.size} successfully encoded rules" }

        /**
         * The rules that have been successfully encoded should now be split further.
         */
        val splitOnMultiAssert = successFullyEncodedRules.map { it.getOrNull()!! }.flatMap {
            splitAsserts(it)
        }
        treeViewReporter.buildRuleTree(splitOnMultiAssert.map { it.rule } + ruleFailures.map { it.first })

        /**
         * Report all failures from encodeToTAC.
         */
        ruleFailures.forEach { res ->
            reportRuleEndResult(res.first, res.second)
        }

        suspend fun solveAndReportRule(encodedRule: EncodedRule<R>){
            annotateCallStackAsync("rule.${encodedRule.rule.ruleIdentifier.displayName}") {
                logger.info { "Starting to solve rule ${encodedRule.rule.ruleIdentifier}" }
                treeViewReporter.signalStart(encodedRule.rule)
                val result = solve(encodedRule)
                val reportedResult = result.fold(
                    onSuccess = { success ->
                        /**
                         * Each rule may spawn new subrules, such as [rules.sanity.TACSanityChecks]
                         * Note however, that we wait for the parent rule to be fully completed.
                         * The sanity rule for instance, should only be spawned when the base rule
                         * result is known to be SAT. Each such "continuation rule" is solved (sequentially!).
                         */
                        buildContinuationRules(encodedRule, success).forEach { subrule ->
                            subrule.fold(
                                onSuccess = { successRule ->
                                    treeViewReporter.registerSubruleOf(successRule.rule, encodedRule.rule)
                                    solveAndReportRule(successRule)
                                },
                                onFailure = {
                                    if (it is RuleEncodingException) {
                                        val rule = it.rule
                                        treeViewReporter.registerSubruleOf(rule, encodedRule.rule)
                                        reportRuleEndResult(rule, RuleCheckResult.Error(rule, it.exception))
                                    } else{
                                        logger.warn { "Received an exception while creating more rules for completed base rule ${encodedRule.rule.ruleIdentifier}" }
                                    }
                                }
                            )
                        }
                        success
                    },
                    onFailure = {
                        RuleCheckResult.Error(encodedRule.rule, it)
                    },
                )
                reportRuleEndResult(encodedRule.rule, reportedResult)
            }
        }

        /**
         * Solve all "top-level" / base rules in parallel. Note that internalSolveRule
         * calls itself internally for each generated [buildContinuationRules]. Subsequent
         * continuation rules are processed sequentially.
         */
        splitOnMultiAssert.parallelMapOrdered { _, encodedRule ->
            solveAndReportRule(encodedRule)
        }
        reporterContainer.toFile(scene)
        return results.toList()
    }

    /**
     * Solves a single encoded rule using the configured destructive optimizations mode
     * (single-stage, two-stage, or two-stage checked) and constructs the call trace
     * as part of the call to toCheckResult
     */
    @OptIn(Config.DestructiveOptimizationsOption::class)
    suspend fun solve(encodedRule: EncodedRule<R>): Result<RuleCheckResult.Leaf> {
        return when (Config.DestructiveOptimizationsMode.get()) {
            DestructiveOptimizationsModeEnum.TWOSTAGE,
            DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> solveTwoStage(encodedRule)

            DestructiveOptimizationsModeEnum.DISABLE -> solveSingleStage(encodedRule.rule, encodedRule.code)
            DestructiveOptimizationsModeEnum.ENABLE -> solveSingleStage(
                rule = encodedRule.rule,
                code = prepareForDestructiveMode(encodedRule.code)
            ).uncheckedAs()

        }.toCheckResult(
            scene,
            encodedRule.rule
        )
    }

    /**
     * First enables destructive optimizations and then applies the snippet remover
     * such that all Snippets binding variables are removed which makes the SMT formulas
     * smaller.
     */
    private fun prepareForDestructiveMode(code: CoreTACProgram): CoreTACProgram {
        return SnippetRemover.rewrite(code.withDestructiveOptimizations(
            true
        ))
    }

    /**
     * Solves an encoded rule in a single stage
     */
    private suspend fun solveSingleStage(rule: R, code: CoreTACProgram): CompiledRule.CompileRuleCheckResult {
        return solve(rule, code, TwoStageRound.ROUNDLESS)
    }

    /**
     * Solves an encoded rule using two-stage verification with destructive optimizations
     * checked between rounds for soundness.
     */
    private suspend fun solveTwoStage(encodedRule: EncodedRule<R>): CompiledRule.CompileRuleCheckResult {
        val rule = encodedRule.rule
        val code = encodedRule.code
        return twoStageDestructiveOptimizationsCheck(
            rule,
            rule.ruleIdentifier.displayName,
            code
        ) { updatedCode, round ->
            /**
             *  In the first round the [updatedCode] is the same as [code] but assignments received
             *  meta from [annotateWithTwoStageMeta()] marking the definition of the variables
             *  also the code is also labeled as destructive optimization.
             *
             *  In the second round, [updatedCode] is the same as [code] but without the destructive
             *  optimization label.
             * */
            // In ROUNDLESS mode, the reporting is equivalent to the logging in tarting solving in solve() above
            logger.debug { "Solving round ${round} of rule ${rule.ruleIdentifier}" }
            when(round){
                TwoStageRound.ROUNDLESS -> `impossible!`
                TwoStageRound.FIRST_ROUND -> solve(rule, SnippetRemover.rewrite(updatedCode), round)
                TwoStageRound.SECOND_ROUND -> solve(rule, updatedCode, round)
            }
        }
    }

    /**
     * Core solving logic: optimizes the TAC program for the given round, invokes the TAC verifier,
     * and returns the verification result with timing information.
     */
    private suspend fun solve(
        rule: R,
        code: CoreTACProgram,
        twoStageRound: TwoStageRound
    ): CompiledRule.CompileRuleCheckResult {
        return coRunCatching {
            val startTime = System.currentTimeMillis()
            val optCoreTac = finalizeForRound(rule, code, twoStageRound)
            val joinedResult = Verifier.JoinedResult(
                TACVerifier.verify(scene, optCoreTac, treeViewReporter.liveStatsReporter, rule)
            )
            val endTime = System.currentTimeMillis()
            ResultAndTime(joinedResult, VerifyTime.WithInterval(startTime, endTime))
        }.mapCheckResult(
            ruleName = rule.ruleIdentifier.displayName,
            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
        )
    }

    override fun close() {
        treeViewReporter.close()
    }

    @TestOnly
    fun getTreeViewReporterForTest(): TreeViewReporter {
        return treeViewReporter
    }

    companion object {
        /**
         * given a single rule and depending on conditions,
         * may split it into multiple asserts, which are children of the original rule
         *
         * Note: This method uses unchecked cast as TACMultiAssert only works with EcosystemAgnosticRule as it
         * requires copy methods on R. I.e. if R is different from EcosystemAgnosticRule split on assert won't work.
         * => As EcosystemAgnosticRule is a fixed data class, we cannot add WasmRule, MoveRule, SolanaRule _and_
         * supporting the multi assert flow.
         */
        @TestOnly
        fun <R : SingleRule> splitAsserts(rule: EncodedRule<R>): List<EncodedRule<R>> {
            if (rule.rule !is EcosystemAgnosticRule) {
                logger.warn { "Proceeding without splitting on multi assert - rule is not of type ${EcosystemAgnosticRule::class.java} " }
                return listOf(rule)
            }
            return (if (!TACMultiAssert.canSplit(rule.uncheckedAs())) {
                listOf(rule)
            } else if (Config.MultiAssertCheck.get()) {
                TACMultiAssert.transformMulti(rule.uncheckedAs())
            } else {
                listOf(TACMultiAssert.transformSingle(rule.uncheckedAs()))
            }).uncheckedAs()
        }
    }
}
