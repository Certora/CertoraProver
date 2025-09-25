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

package rules

import analysis.CmdPointer
import analysis.EnvFreeMethodAnalysis
import analysis.EnvfreeInfo
import bridge.NamedContractIdentifier
import cli.SanityValues
import com.certora.collect.*
import config.Config
import config.Config.MultiAssertCheck
import config.DestructiveOptimizationsModeEnum
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import datastructures.stdcollections.*
import diagnostics.*
import instrumentation.transformers.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import log.*
import parallel.coroutines.parallelMapOrdered
import report.*
import report.callresolution.CallResolutionTable
import report.callresolution.CallResolutionTableBase
import rules.RuleSplitter.getTopoSortedAssertsWithMeta
import rules.dpgraph.DPResult
import rules.dpgraph.SanityRulesDependencies
import rules.genericrulecheckers.BuiltInRuleCustomChecker
import rules.sanity.*
import rules.sanity.SanityCheckResultOrdinal.Companion.toDefaultSanityCheckResultOrdinal
import scene.IScene
import solver.SolverResult
import spec.CVL
import spec.cvlast.*
import spec.genericrulegenerators.BuiltInRuleId
import spec.rules.*
import statistics.SDCollectorFactory
import statistics.recordAny
import tac.DumpTime
import testing.TacPipelineDebuggers.oneStateInvariant
import utils.*
import vc.data.*
import vc.data.TACMeta.SATISFY_ID


private val logger = Logger(LoggerTypes.GENERIC_RULE)
private val loggerTimes = Logger(LoggerTypes.TIMES)

class RuleChecker(
    val scene: IScene,
    val contractName: NamedContractIdentifier,
    val cvl: CVL,
    val reporter: OutputReporter,
    val treeViewReporter: TreeViewReporter,
    val summaryMonitor: SummaryMonitor?
) {

    @Treapable
    @KSerializable
    data class CmdPointerList(val ptrs: List<CmdPointer>): AmbiSerializable {
        constructor(vararg ptrs: CmdPointer): this(listOf(*ptrs))
    }

    /**
     * Compute and Merge assert splits ([compiledSubRuleList]) for [originalRule] into a single RuleCheckResult
     *
     * @return a [Parallel<RuleCheckResul>] that combines the results of all the asserts
     */
    private suspend fun computeAndMergeAssertResults(
        originalRule: IRule,
        compiledSubRuleList: List<CompiledRule<CVLSingleRule>>
    ): RuleCheckResult = coroutineScope {
        val ruleCheckResults = compiledSubRuleList.parallelMapOrdered { _, compiledCVLRule ->
            val rule = compiledCVLRule.rule

            /*
              Indicate that every task from this point forward should check for cancellation, and fail ASAP.

              This means that some code will continue to run while we collate our results. So it goes. The 1 minute
              shutdown timer bounds how long we can drag on execution.
             */
            try {
                reporter.signalStart(rule)
                treeViewReporter.signalStart(compiledCVLRule.rule)

                SDCollectorFactory.collector().recordAny("${TimeSinceStart()}", "startTime", compiledCVLRule.tac.name)
                @OptIn(Config.DestructiveOptimizationsOption::class)
                val res = if (Config.GetCallResolutionOnly.get()) {
                    val origProgWithAssertIdMeta = CompiledRule.addAssertIDMetaToAsserts(compiledCVLRule.tac, rule)
                    val callResolutionTable = CallResolutionTable.Factory(origProgWithAssertIdMeta, scene, rule).tableBase()
                    val message = "Only dumping call resolution (-${Config.GetCallResolutionOnly.name} was set)"
                    RuleCheckResult.Skipped(rule, listOf(RuleAlertReport.Info(message)), callResolutionTable)
                } else {
                    when (Config.DestructiveOptimizationsMode.get()) {
                        DestructiveOptimizationsModeEnum.TWOSTAGE,
                        DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> twoStageDestructiveOptimizationsCheck(
                            scene,
                            compiledCVLRule
                        )

                        DestructiveOptimizationsModeEnum.ENABLE ->
                            CompiledRule.create(
                                compiledCVLRule.rule,
                                compiledCVLRule.tac.withDestructiveOptimizations(true),
                                compiledCVLRule.liveStatsReporter
                            ).check(scene.toIdentifiers())

                        DestructiveOptimizationsModeEnum.DISABLE -> compiledCVLRule.check(scene.toIdentifiers())
                    }
                        .toCheckResult(scene, compiledCVLRule)
                        .getOrElse { RuleCheckResult.Error(compiledCVLRule.rule, it) }
                }

                SDCollectorFactory.collector().recordAny("${TimeSinceStart()}", "finishTime", compiledCVLRule.tac.name)
                res
            } catch (e: Exception) {
                RuleCheckResult.Error(
                    rule,
                    CertoraException.fromExceptionWithRuleName(e, rule.declarationId),
                )
            }.also {
                treeViewReporter.signalEnd(compiledCVLRule.rule, it)
                StatusReporter.addResults(it)
                ConsoleReporter.addResults(it)
            }
        }

        // If there is just 1 assert in the rule, or the rule is not being split
        // (e.g., as done for "sanity check" rules), simply return the result
        val ruleCheckResult = ruleCheckResults.singleOrNull()
        // Note that a multi-assert with just one assert should still be wrapped in a RuleCheckResult.Multi
        // (this happens with satisfy - where we omit the autogenerated asserts that are always added to assert-based rules, but not to satisfy-based rules)
        if (ruleCheckResult != null && ruleCheckResult.rule.ruleType !is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule) {
            return@coroutineScope ruleCheckResult
        }

        if (Config.HashingBoundDetectionMode.get()) {
            reportMinimalHashingBound(ruleCheckResults, originalRule.ruleIdentifier.displayName)
        }

        // MultiAssert case. This can be either from multiassert mode or from the presence of satisfies.
        check(
            ((ruleCheckResults.count { it.rule.ruleType is SpecType.Single.FromUser } == 1
                || ruleCheckResults.count { it.rule.ruleType is SpecType.Single.BuiltIn
                && (it.rule.ruleType as SpecType.Single.BuiltIn).birId == BuiltInRuleId.sanity } == 1) &&
                ruleCheckResults.count { it.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SatisfySpecFile } == ruleCheckResults.size - 1) ||
                ruleCheckResults.all { it.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule }) {
            "Expected at this point to have only MultiAssertSubRules, or a single FromUser/BuiltinSanity rule with SatisfySubRules, got" +
                ruleCheckResults.filter { it.rule.ruleType !is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule }
                    .joinToString(separator = ", ") {
                        "ruleId=${it.rule.declarationId};ruleType=${it.rule.ruleType}"
                    }
        }
        // Form a multi result [RuleCheckResult.Multi] in case there are multiple asserts
        RuleCheckResult.Multi(
            originalRule,
            ruleCheckResults,
            RuleCheckResult.MultiResultType.SPLIT_ASSERTS
        ).also {
            StatusReporter.addResults(it)
            ConsoleReporter.addResults(it)
        }
    }

    private fun reportMinimalHashingBound(ruleCheckResults: List<RuleCheckResult.Leaf>, ruleName: String) {
        val lengths = ruleCheckResults.filter { it.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.HashingBoundCheck }.mapNotNull { res ->
            res.getAllFlattened().map { it.checkResult }.filterIsInstance<RuleCheckResult.Single.WithCounterExamples>().mapNotNull{
                it.ruleCheckInfo.examples.first().minHashingBoundNeeded
            }.maxOrNull()
        }

        Logger.always("bounds found for $ruleName: $lengths", respectQuiet = true)

        val message = if (lengths.isEmpty()) {
            "Failed to compute minimal hashing bound for $ruleName"
        } else {
            val maxReasonableBound = 100000.toBigInteger()
            val suggestedBound = lengths.partition { it <= maxReasonableBound }.let { (lower, higher) ->
                if (lower.isEmpty()) {
                    higher.min()
                } else {
                    lower.max()
                }
            }
            val warning = if (suggestedBound < lengths.max()) {
                " Warning: some parts of the control-flow requires a higher bound of ${lengths.max()} - use this bound " +
                    "to get a better coverage, however, note that it might slow down the prover. "
            } else {
                ""
            }
            "The suggested minimal hashing bound for $ruleName is $suggestedBound.$warning"
        }
        Logger.always(message, respectQuiet = true)
        CVTAlertReporter.reportAlert(CVTAlertType.GENERAL, CVTAlertSeverity.INFO, jumpToDefinition = null, message = message, hint = null)
    }

    suspend fun singleRuleCheck(rule: CVLSingleRule): RuleCheckResult {
        logger.info { "Checking single rule ${rule.declarationId}" }

        if (rule.ruleType is SpecType.Single.SkippedMissingOptionalMethod) {
            val ruleType = rule.ruleType as SpecType.Single.SkippedMissingOptionalMethod
            return RuleCheckResult.Skipped(
                rule,
                RuleAlertReport.Warning("Rule ${rule.declarationId} cannot be compiled since '${ruleType.missingMethod}' does " +
                    "not exist in checked contract '${ruleType.contractName}'"),
            ).also {
                //Make sure to report skipping to the treeview reporter.
                treeViewReporter.signalEnd(rule, it) }
        }

        val ruleCompileStart = System.currentTimeMillis()
        val codesToCheck = CompiledRule.subRules(scene, cvl, rule).getOrElse { e ->
            Logger.always("Failed to compile rule ${rule.declarationId} due to $e", respectQuiet = false)
            return RuleCheckResult.Error(
                rule,
                CertoraException.fromExceptionWithRuleName(e, rule.declarationId),
            ).also {
                //Make sure to report the failure to the treeview reporter
                treeViewReporter.signalEnd(rule, it) }
        }
        val ruleCompileEnd = System.currentTimeMillis()

        loggerTimes.info { "Compiling rule end in ${(ruleCompileEnd - ruleCompileStart) / 1000}s" }
        return compiledSingleRuleCheck(rule, codesToCheck)
    }

    suspend fun compiledSingleRuleCheck(rule: CVLSingleRule, codesToCheck: List<ICheckableTAC>): RuleCheckResult {
        /**
         * Split asserts in [compiledSubRule] into separate rules unless [MultiAssertCheck] is false.
         *
         * @param compiledSubRule: the original rule to split; it is assumed that it has exactly one checkableTAC
         * @return list of rules, one for each assert
         */
        fun splitRuleOnAsserts(compiledSubRule: CompiledRule<CVLSingleRule>): List<CompiledRule<CVLSingleRule>> {
            val tacProg = compiledSubRule.tac
            val satisfyPtrs = tacProg.analysisCache.graph.commands.filter {
                it.cmd.meta.containsKey(SATISFY_ID)
            }.mapToSet { it.ptr }
            val topoSortedAssertsWithMeta: List<RuleSplitter.AssertCmdWithMeta> by lazy {
                getTopoSortedAssertsWithMeta(tacProg)
            }
            val cleanTopoSortedAsserts by lazy {
                topoSortedAssertsWithMeta.filterNot { it.cmd.meta.containsKey(SATISFY_ID) }
            }
            val userDefinedAssertsWithMeta: List<RuleSplitter.AssertCmdWithMeta.UserDefined> by lazy {
                cleanTopoSortedAsserts.filterIsInstance<RuleSplitter.AssertCmdWithMeta.UserDefined>()
            }

            val allHashingBoundAsserts: Set<RuleSplitter.AssertCmdWithMeta> by lazy {
                cleanTopoSortedAsserts.filterToSet { it.cmd.meta.containsKey(TACMeta.HASHING_BOUND_ASSERT) }
            }
            val hashingBoundPtrs: Map<CmdPointer, RuleSplitter.AssertCmdWithMeta> by lazy {
                allHashingBoundAsserts.associateBy { it.ptr }
            }
            // the asserts that are not dominated by (a set of) other hashing bound asserts.
            // it is sufficient to process these, since by processing these, we handle also those that precede them
            val lastHashingBoundAsserts: Set<RuleSplitter.AssertCmdWithMeta> by lazy {
                buildSet {
                    val visited = topoSortedAssertsWithMeta.map { it.ptr }.toMutableSet()
                    val queue = arrayDequeOf(visited)
                    queue.consume { ptr ->
                        if (ptr in hashingBoundPtrs) {
                            add(hashingBoundPtrs[ptr]!!)
                        } else {
                            tacProg.analysisCache.graph.pred(ptr).forEach {
                                if (visited.add(it)) {
                                    queue += it
                                }
                            }
                        }
                    }
                }
            }

            val compiledPerAssertSubRules = mutableListOf<CompiledRule<CVLSingleRule>>()

            // Creating all satisfy rules
            if (satisfyPtrs.isNotEmpty()) {
                val userDefinedSatisfyAsserts = topoSortedAssertsWithMeta.filter { it.cmd.meta.containsKey(SATISFY_ID) }
                    .filterIsInstance<RuleSplitter.AssertCmdWithMeta.UserDefined>()
                check(userDefinedSatisfyAsserts.isNotEmpty()) {
                    "Satisfy assert should exist in the presence of satisfy commands" }
                for (assertion in userDefinedSatisfyAsserts) {
                    compiledPerAssertSubRules.add(
                        RuleSplitter.generateSubRuleForSatisfy(
                            compiledSubRule,
                            tacProg,
                            assertion,
                            cleanTopoSortedAsserts,
                            satisfyPtrs,
                            treeViewReporter,
                        )
                    )
                }
            }

            val initPatchingProgram = {
                val patchingProgram = tacProg.toPatchingProgram()
                for (p in satisfyPtrs) {
                    patchingProgram.replaceCommand(p, listOf(TACCmd.Simple.NopCmd))
                }
                patchingProgram
            }

            fun generateSubRules() {
                // We have multiple assert statements (at least one is user-defined): split on those
                val lastAutoGenAssertOrNull: RuleSplitter.AssertCmdWithMeta.AutoGenerated? =
                    cleanTopoSortedAsserts.filterIsInstance<RuleSplitter.AssertCmdWithMeta.AutoGenerated>().lastOrNull()

                // Generate a compiled-rule for each assert statement that was specified in the spec;
                // transform each assert that precedes the assert under consideration into an assume statement
                userDefinedAssertsWithMeta
                    .forEach { userDefinedAssert ->
                        // Also creates satisfy rules
                        compiledPerAssertSubRules.add(
                            RuleSplitter.generateSubRuleForUserDefinedAssert(
                                compiledSubRule,
                                tacProg,
                                initPatchingProgram(),
                                userDefinedAssert,
                                cleanTopoSortedAsserts,
                                treeViewReporter,
                            )
                        )
                    }

                // Generate subrules for individual last hashing bound asserts in the program.
                // See [generateSubRuleForHashingBoundAssert] for details on how this works
                if (Config.HashingBoundDetectionMode.get()) {
                    val nonHashingBoundAsserts = cleanTopoSortedAsserts.filter { it !in allHashingBoundAsserts}
                    lastHashingBoundAsserts.forEach { lastHashingBoundAssert ->
                        compiledPerAssertSubRules.add(
                            RuleSplitter.generateSubRuleForHashingBoundAssert(
                                compiledSubRule,
                                tacProg,
                                initPatchingProgram(),
                                lastHashingBoundAssert,
                                nonHashingBoundAsserts,
                                allHashingBoundAsserts.filter { it != lastHashingBoundAssert },
                                treeViewReporter,
                            )
                        )
                    }
                }

                // Generate a compiled-rule that includes all the auto-generated asserts
                // Transform all user-specified assert statements that precede the last auto-generated assert, into assumes
                // Keep all auto-generated asserts in place
                lastAutoGenAssertOrNull?.let { lastAutoGenAssert ->
                    compiledPerAssertSubRules.add(
                        RuleSplitter.generateSubRuleForLastAutoGeneratedAssert(
                            compiledSubRule,
                            tacProg,
                            initPatchingProgram(),
                            lastAutoGenAssert,
                            cleanTopoSortedAsserts,
                            treeViewReporter,
                        )
                    )
                }
            }

            if (Config.HashingBoundDetectionMode.get()
                || (MultiAssertCheck.get() && cleanTopoSortedAsserts.isNotEmpty() && userDefinedAssertsWithMeta.isNotEmpty())
            ) {
                generateSubRules()
            } else if (compiledPerAssertSubRules.isNotEmpty() && userDefinedAssertsWithMeta.isNotEmpty()) {
                // We are not in multiassert, but have satisfy rules
                // Update the main rule to ignore satisfy
                val ruleName = "Assertions"
                val mainRule = RuleSplitter.newMultiAssertSubRuleOf(
                    compiledSubRule,
                    initPatchingProgram().toCode(tacProg),
                    ruleName,
                    SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertsOnly(compiledSubRule.rule),
                    false,
                    treeViewReporter,
                    Range.Empty()
                )
                compiledPerAssertSubRules += mainRule
            }

            // Register all new rules (may be empty)
            compiledPerAssertSubRules.forEach {
                treeViewReporter.registerSubruleOf(it.rule,compiledSubRule.rule)
            }

            return if (compiledPerAssertSubRules.isEmpty()) {
                listOf(compiledSubRule)
            } else {
                compiledPerAssertSubRules
            }
        }

        /**
         * Handles all [SpecCallSummary]s and ghosts' hooks, and then splits [CompiledRule] into a list of [CompiledRule]s.
         * The splitting is done according to the assert commands found in [rule] and
         * in addition, according to assert commands that may be introduced after inlining [SpecCallSummary]s and ghost hooks' code.
         *
         * Some attributes of [CompiledRule.rule] (e.g., whether [CompiledRule.rule] is a sanity rule)
         * may affect the splitting strategy that will be applied
         *
         * @return An [Result] where [Result.success] holds the splitting result
         */
        fun handleSummariesGhostHooksAndGeneratePerAssertRules(
            rule: CVLSingleRule,
            tac: CoreTACProgram,
            parentRule: CVLSingleRule?
        ): Result<List<CompiledRule<CVLSingleRule>>> = runCatching {
            // this is the real-real preoptimized
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                tac,
                ReportTypes.PREINSTRUMENTED_RULE,
                StaticArtifactLocation.Outputs,
                DumpTime.AGNOSTIC
            )
            oneStateInvariant(tac, ReportTypes.PREINSTRUMENTED_RULE)

            val instrumentedTac = InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(tac, scene, cvl, rule, summaryMonitor)
            CompiledRule.create(
                codeToCheck = instrumentedTac,
                rule = rule,
                liveStatsReporter = treeViewReporter.liveStatsReporter
            )

            // Before splitting on asserts, we must handle all summaries and ghosts' hooks
            // as those may introduce new assert commands
        }.map { compiledSubRuleWithAppliedSummaries ->

            if (rule.ruleGenerationMeta.sanity == SingleRuleGenerationMeta.Sanity.BASIC_SANITY) {
                // Skip splitting on asserts for sanity rules
                listOf(compiledSubRuleWithAppliedSummaries)
            } else {
                // Satisfy rules are always checked in multi-assert mode
                splitRuleOnAsserts(compiledSubRuleWithAppliedSummaries)
            }
        }.onFailure { e ->
            val parentRuleStr =
                parentRule?.declarationId?.takeIf { parentRule.ruleGenerationMeta is SingleRuleGenerationMeta.WithMethodInstantiations }
            val ruleStr = listOfNotNull(parentRuleStr, rule.declarationId).joinToString(separator = OUTPUT_NAME_DELIMITER)
            Logger.alwaysError("Had an exception while verifying the rule '$ruleStr':", e)
        }.recoverCatching {
            // need to wrap the exception with a CertoraException - so attempt to recover and immediately throw
            // a wrapped exception
            throw CertoraException.fromExceptionWithRuleName(it, rule.declarationId)
        }

        /**
         * Sanity rule's metadata.
         * @property ruleSanitySuffix describes what is being sanity-checked.
         * @property loc displays the CVLLocation of what is being sanity checked.
         */
        class SanityRuleMetaData(val ruleSanitySuffix: String, val loc: String)


        /**
         * Returns [SanityRuleMetaData], based on the SpecType [ruleType],
         * which is guaranteed to be [GeneratedFromBasicRule].
         */
        fun getRuleTypeMetaData(ruleType: SpecType.Single.GeneratedFromBasicRule.SanityRule): SanityRuleMetaData = when (ruleType) {
            is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertTautologyCheck -> {
                SanityRuleMetaData(GenerateRulesForAssertions.GenerateRulesForAssertTautologyChecks.sanityRuleName, ruleType.assertCVLCmd.getSuffixDecId())
            }
            is SpecType.Single.GeneratedFromBasicRule.SanityRule.TrivialInvariantCheck -> {
                SanityRuleMetaData(GenerateRulesForAssertions.GenerateRulesForTrivialInvariantCheck.sanityRuleName, "_postcondition")
            }
            is SpecType.Single.GeneratedFromBasicRule.SanityRule.RedundantRequireCheck -> {
                SanityRuleMetaData(GenerateRulesForRedundantRequiresCheck.sanityRuleName, ruleType.assumeCVLCmd.getAssumeSuffixDecId())
            }
            is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck -> {
                SanityRuleMetaData(GenerateRulesForVacuityCheck.sanityRuleName, "")
            }
            is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck -> {
                SanityRuleMetaData(
                    ruleType.sanityRuleName,
                    ruleType.assertCVLCmd.getSuffixDecId()
                )
            }
        }

        /**
         * Given the [RuleCheckResult] [originalBaseResult] and the [SanityCheckResultOrdinal] of [updatedBaseRule],
         * determines what final result will be attached to [updatedBaseRule].
         */
        fun getFinalBaseResult(
            originalBaseResult: RuleCheckResult,
            updatedBaseRule: IRule,
            sanityResult: SanityCheckResultOrdinal
        ): RuleCheckResult {
            return when(originalBaseResult){
                is RuleCheckResult.Error -> originalBaseResult.copy(rule = updatedBaseRule)
                is RuleCheckResult.Single.Basic  -> originalBaseResult.copy(rule = updatedBaseRule)
                is RuleCheckResult.Single.WithCounterExamples  -> originalBaseResult.copy(rule = updatedBaseRule)
                is RuleCheckResult.Skipped -> originalBaseResult.copy(rule = updatedBaseRule)
                is RuleCheckResult.Multi -> originalBaseResult.copy(rule = updatedBaseRule)
            }.let {
                when(sanityResult){
                    SanityCheckResultOrdinal.FAILED -> it.copyWithResult(SolverResult.SANITY_FAIL)
                    SanityCheckResultOrdinal.ERROR -> RuleCheckResult.Error(
                        updatedBaseRule,
                        CertoraException(
                            CertoraErrorType.NO_SANITY_RESULTS,
                            "No sanity results for ${updatedBaseRule.declarationId}"
                        ),
                    )

                    SanityCheckResultOrdinal.PASSED,
                    SanityCheckResultOrdinal.TIMEOUT,
                    SanityCheckResultOrdinal.UNKNOWN -> it
                }
            }
        }

        // it's actually all or nothing: if one has non-empty methodParameterInstantiation, then it should be the case for all the others too.
        val parametricRule = codesToCheck.any { it.methodParameterInstantiation.isNotEmpty() }
        val multiRule = parametricRule ||
            // it's actually all or nothing: all are [CheckableTACWithSanity] or all are not.
            codesToCheck.any { it is CheckableTACWithSanity }


        if (multiRule) {

            // If this multi-rule has instantiations of methods from non-primary-contract contracts, we want to prepend
            // the contract name to the rule. Check whether this is the case.
            val hasMethodInstFromNonPrimaryContract = codesToCheck.any { (_, methodMatch, _) ->
                methodMatch.values.any { it.contractName != contractName.name }
            }

            /* Generate contract-specific parent-rules (they will be parents of the instantiated rules, and children
            of the original rule) */
            val methodMatchToContractRule = buildMap {
                if (hasMethodInstFromNonPrimaryContract) {
                    //Iterate over the map twice to avoid duplicated creation of identifiers...
                    val ruleDeclarationIdToRule = codesToCheck.mapToSet{ checkableTAC ->
                        val methodParamInst = checkableTAC.methodParameterInstantiation
                        // we sort for consistency of the generated string
                        methodParamInst.values.map { it.contractName }.sorted().joinToString(separator = "-")
                    }.associateWith { x -> rule.copy(ruleType = SpecType.Group.ContractRuleType(x),
                        ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier(x))   }
                    codesToCheck.forEach { checkableTAC ->
                        val methodParamInst = checkableTAC.methodParameterInstantiation
                        // we sort for consistency of the generated string
                        val s = methodParamInst.values.map { it.contractName }.sorted().joinToString(separator = "-")
                        put(methodParamInst, ruleDeclarationIdToRule[s]!!)
                    }
                }
            }

            val contractRules: Set<IRule> = methodMatchToContractRule.values.toSet()

            // each contract-rule is reported exactly once
            contractRules.forEach {
                treeViewReporter.registerSubruleOf(it, rule)
            }

            // a mutable list of sanity-rules' checkableTACs extracted out of [CheckableTACWithSanity]s
            val sanityCheckableTACs = mutableListOf<CheckableTAC>()

            fun createCompiledRule(
                newSingleRule: CVLSingleRule,
                subCode: CoreTACProgram
            ): CompiledRule<CVLSingleRule> {
                return CompiledRule.create(
                    newSingleRule,
                    subCode.copy(name = newSingleRule.ruleIdentifier.toString()),
                    treeViewReporter.liveStatsReporter
                )
            }

            // create a map of compiled rules for sanity rules
            val compiledBaseSubRules = codesToCheck.map { checkableTAC ->
                val subCode = checkableTAC.tac
                val methodMatch = checkableTAC.methodParameterInstantiation
                val currRule = checkableTAC.subRule

                val newSingleRule = if (methodMatch.isNotEmpty()) {
                    val sanity = currRule.ruleGenerationMeta.sanity
                    val (methodInstsNames, declarationId) = methodMatch.toRuleName(hasMethodInstFromNonPrimaryContract)
                    val parentRule = if (hasMethodInstFromNonPrimaryContract) {
                        methodMatchToContractRule[methodMatch] ?: throw IllegalStateException("no $methodMatch in $methodMatchToContractRule")
                    } else {
                        // no middlemen, the original rule is the direct parent of the instantiation
                        rule
                    }
                    val methodRule = currRule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                            sanity,
                            methodMatch.range(),
                            methodInstsNames,
                        ),
                        ruleIdentifier = parentRule.ruleIdentifier.freshDerivedIdentifier(declarationId)
                    )
                    treeViewReporter.registerSubruleOf(methodRule, parentRule)

                    methodRule
                }
                else {

                    /* If it's not a parametric rule, then it's a base rule with some sanity checks
                    (see CompiledRule::staticRules). */
                    check(currRule.ruleGenerationMeta.sanity == SingleRuleGenerationMeta.Sanity.PRE_SANITY_CHECK) {
                        "Expected a base rule with some sanity checks"
                    }
                    currRule
                }

                if (checkableTAC is CheckableTACWithSanity) {
                    // fill [sanityCheckableTACs] with the sanity checks of the current base rule
                    checkableTAC.sanityChecks.forEach { sanityCheck ->
                        /* Add [sanityCheck] with an updated originating rule.
                        The originating rule is used to match later between a base rule and its sanity rules.
                        This matching occurs inside [SanityCheckUtils.matchingSanityRule]. */
                        val sanitySubRule = sanityCheck.subRule.copy(
                            ruleType = sanityCheck.subRule.narrowType<SpecType.Single.GeneratedFromBasicRule>().ruleType.copyWithOriginalRule(newOriginalRule = newSingleRule))

                        sanityCheckableTACs.add(sanityCheck.copy(
                            subRule = sanitySubRule)
                        )
                        }
                    }


                createCompiledRule(newSingleRule, subCode)
            }

            // create a map of compiled rules for sanity rules
            val compiledSubRulesSanity = sanityCheckableTACs.map { (subCode, methodMatch ,currRule) ->
                val sanityRuleType: SpecType.Single.GeneratedFromBasicRule.SanityRule =
                    currRule.narrowType<SpecType.Single.GeneratedFromBasicRule.SanityRule>().ruleType
                val sanityRuleMetaData = getRuleTypeMetaData(sanityRuleType)
                val ruleName = "${sanityRuleMetaData.ruleSanitySuffix}${sanityRuleMetaData.loc}"
                val matchingOrigRes = currRule.ruleType.getOriginatingRule()
                val sanityRuleIdentifier = matchingOrigRes!!.ruleIdentifier.freshDerivedIdentifier(ruleName)

                val newSingleSanityRule = if (methodMatch.isNotEmpty()) {
                    val (methodInstsNames, _) = methodMatch.toRuleName(hasMethodInstFromNonPrimaryContract)
                    currRule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                            currRule.ruleGenerationMeta.sanity,
                            currRule.range,
                            methodInstsNames,
                        ),
                        ruleIdentifier = sanityRuleIdentifier
                    )
                } else {
                    /* sanity rules have [SingleRuleGenerationMeta.Sanity.BASIC_SANITY] (see CompiledRule::compileSanityCheckSubRules). */
                    check(currRule.ruleGenerationMeta.sanity == SingleRuleGenerationMeta.Sanity.BASIC_SANITY) {
                        "sanity rules should have BASIC_SANITY rule generation meta"
                    }
                    currRule.copy(ruleIdentifier = sanityRuleIdentifier)
                }

                require(newSingleSanityRule.ruleType is SpecType.Single.GeneratedFromBasicRule){
                    "The derived sanity rule does not hold information from which original rule it has been derived from."
                }

                treeViewReporter.registerSubruleOf(newSingleSanityRule, matchingOrigRes)

                createCompiledRule(
                    newSingleSanityRule,
                    subCode,
                )
            }

            // a flattened list of all the checkableTACs, both for sanity and base rules
            val compiledSubRules = compiledBaseSubRules + compiledSubRulesSanity

            /**
             * Checks the compiled rule [compiledSubRule] and returns the result.
             */
            suspend fun evalRule(compiledSubRule: CompiledRule<CVLSingleRule>): RuleCheckResult {
                throttle.withPermit {
                    StatusReporter.registerSubrule(compiledSubRule.rule)

                    return inCodeAsync(compiledSubRule.tac) {
                        val result = handleSummariesGhostHooksAndGeneratePerAssertRules(
                            compiledSubRule.rule, compiledSubRule.tac, rule
                        ).fold(
                            onSuccess = {
                                computeAndMergeAssertResults(compiledSubRule.rule, it)
                            },
                            onFailure = {
                                RuleCheckResult.Error(compiledSubRule.rule, it).also { treeViewReporter.signalEnd(compiledSubRule.rule, it) }
                            }
                        )

                        // Report the check result as a soon as it's available
                        reporter.addResults(result)
                        result
                    }
                }
            }

            /**
             * Given a list containing the base rules and sanity-checks rules generated from a rule, computes a single
             * [RuleCheckResult] for the rule.
             */
            fun reduceResults(allDPResults: List<SanityDPResult>): RuleCheckResult {
                require(allDPResults.all { it.result.rule is CVLSingleRule }) {
                    "Must get only Single rules, got ${allDPResults.mapNotNull {
                        if (it.result.rule !is CVLSingleRule) {
                            it.result
                        } else {
                            null
                        }
                    }}"
                }

                val (results, completedResult) = allDPResults.partition {
                    (it.result.rule as CVLSingleRule).ruleGenerationMeta.sanity != SingleRuleGenerationMeta.Sanity.DONE
                }
                check(completedResult.isEmpty()) { "Unexpected to have completed sanity results here, got: $completedResult" }
                val allResults = results.map { it.result }

                results.filter { it.computationalTyp == DPResult.ComputationalType.CONCLUDED }.forEach{
                    treeViewReporter.signalSkip(it.result.rule)
                }
                val isWithMethodInstantiations =
                    allDPResults.any {
                        (it.result.rule as CVLSingleRule).ruleGenerationMeta is
                            SingleRuleGenerationMeta.WithMethodInstantiations
                    }

                return if (Config.DoSanityChecksForRules.get() == SanityValues.NONE) { // no sanity check
                    check(allResults.none { it.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule }){ "Running in none sanity mode, did not expect to have any sanity rules, got $allResults " }
                    if (!isWithMethodInstantiations) {
                        // single non-parametric rule, so there should be a single result
                        allResults.singleOrNull()
                            ?: throw IllegalStateException("Got no base result for ${rule.declarationId}")
                    } else {
                        RuleCheckResult.Multi(
                            rule, // TODO: this is not necessarily correct, for example, it might be the case that a contract-rule is the actual parent-rule: CERT-4077
                            allResults,
                            RuleCheckResult.MultiResultType.PARAMETRIC
                        )
                    }
                } else {
                    val (sanityResults, baseResults) = allResults.partition { it.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule }
                    val baseResultsUpdatedWithSanity = baseResults.map { originalBaseResult ->
                        val baseRuleMeta = (originalBaseResult.rule as CVLSingleRule).ruleGenerationMeta
                        val updatedBaseRule = (originalBaseResult.rule as CVLSingleRule).copy(ruleGenerationMeta = baseRuleMeta.updateSanity(SingleRuleGenerationMeta.Sanity.DONE))

                        /**
                         * In the case we are solving a parametric rule, the set of sanity rules also contain results from other parametric rule instantiations,
                         *  so filter them out here.
                         */
                        val matchingSanityRulesForBase = sanityResults
                            .filter { (it.rule.ruleType as? SpecType.Single.GeneratedFromBasicRule.SanityRule)?.originalRule == originalBaseResult.rule }

                        /**
                         * Compute the final result of sanity by joining all the results of the sanity rules. If any reports SANITY_FAIL, the overall result is
                         * SANITY_FAIL. See [rules.sanity.SanityCheckResultOrdinal.join]
                         */
                        val sanityResultForBase = matchingSanityRulesForBase.map {
                            when (it) {
                                is RuleCheckResult.Single -> it.toDefaultSanityCheckResultOrdinal()
                                is RuleCheckResult.Skipped -> SanityCheckResultOrdinal.PASSED
                                is RuleCheckResult.Error -> SanityCheckResultOrdinal.ERROR
                                is RuleCheckResult.Multi -> `impossible!`
                            }
                        }.fold(SanityCheckResultOrdinal.PASSED) { acc, curr -> acc join curr }
                        getFinalBaseResult(originalBaseResult, updatedBaseRule, sanityResultForBase)
                    }

                    if (isWithMethodInstantiations) {
                        RuleCheckResult.Multi(
                            rule, // TODO: this is not necessarily correct, for example, it might be the case that a contract-rule is the actual parent-rule: CERT-4077
                            baseResultsUpdatedWithSanity,
                            RuleCheckResult.MultiResultType.PARAMETRIC
                        )
                    } else {
                        baseResultsUpdatedWithSanity.first()
                    }
                }
            }

            return SanityRulesDependencies
                .generate(compiledSubRules)
                .resultComputation(
                    ::evalRule,
                    ::reduceResults
                ) { r: CompiledRule<CVLSingleRule> ->
                    "${rule.declarationId} ${
                        if (rule.parentIdentifier != null) {
                            "of ${rule.parentIdentifier}"
                        } else {
                            ""
                        }
                    } of type ${r.rule.ruleType}"
                }

        } else if (codesToCheck.size == 1) {
            val tac = codesToCheck.single().tac
            return inCodeAsync(tac) {
                handleSummariesGhostHooksAndGeneratePerAssertRules(rule, tac, parentRule = null).fold(
                    onSuccess = { subRules ->
                        computeAndMergeAssertResults(rule, subRules).also { result: RuleCheckResult ->
                            // Report the check result as a soon as it's available
                            reporter.addResults(result)
                        }
                    },
                    onFailure = { err ->
                        RuleCheckResult.Error(
                            rule = rule,
                            error = CertoraException.fromExceptionWithRuleName(err, rule.declarationId),
                        ).also { treeViewReporter.signalEnd(rule, it) }
                    }
                )
            }
        } else {
            val result = when {
                rule.ruleType is SpecType.Single.InvariantCheck.GenericPreservedInductionStep -> Result.success(
                    RuleAlertReport.Warning(
                        "The generic preserved block for the invariant ${rule.declarationId}" +
                            " does not apply to any method"
                    )
                )
                Config.IgnoreViewFunctionsInParametricRules.get() -> Result.success(
                    RuleAlertReport.Warning(
                        "The rule ${rule.declarationId} was run with flag 'ignoreViewFunctions', " +
                            " and could not find any applicable methods"
                    )
                )
                ((rule.ruleType is SpecType.Single.BuiltIn) &&
                    BuiltInRuleCustomChecker.fromBirType(rule.ruleType as SpecType.Single.BuiltIn).functionSetCanBeEmpty) -> {
                    // Non-payable functions are filtered out so codeList is empty.
                    Result.success(RuleAlertReport.Info("No payable functions in the contract"))
                }

                else -> throw IllegalStateException(
                    "Reached the default case in compiledSingleRuleCheck, even though " +
                        "all cases should have already been accounted for."
                )
            }.fold(
                onSuccess = { ruleAlertReport -> RuleCheckResult.Skipped(rule, ruleAlertReport) },
                onFailure = {
                    RuleCheckResult.Error(
                        rule,
                        CertoraException.fromExceptionWithRuleName(it, rule.declarationId)
                    )
                }
            )
            treeViewReporter.signalEnd(rule, result)
            return result
        }
    }

    private fun checkStaticEnvfreedom(rule: StaticRule): RuleCheckResult.Leaf {
        val staticEnvFreeRuleType =
            (rule.ruleType as? SpecType.Single.EnvFree.Static) ?: throw UnsupportedOperationException(
                "Expected to find a static rule whose ruleType is SpecType.Single.EnvFree.Static, but got ${rule.ruleType} (rule: $rule)"
            )
        val funcId = staticEnvFreeRuleType.contractFunction

        val contractName = staticEnvFreeRuleType.contractFunction.methodSignature.qualifiedMethodName.host

        val contractId: ContractReference = contractName

        val contractNameOfFunc = this.cvl.getContractNameFromContractId(contractId)
            ?: return RuleCheckResult.Error(
                rule,
                AssertionError("There is no contract for id  $contractId  in functions imported in spec file"),
            )
        val importedFunc = cvl.importedFuncs[cvl.getContractInstance(contractNameOfFunc)]
            ?.find { it.methodSignature.matchesContractAndParams(funcId.methodSignature) }
            ?: return RuleCheckResult.Error(
                rule,
                IllegalStateException("There is no imported function named $funcId in functions imported in spec file"),
            )
        if (!importedFunc.annotation.envFree) {
            return RuleCheckResult.Error(
                rule,
                AssertionError("Imported function named $funcId is not even declared envfree"),
            )
        }

        val startTime = System.currentTimeMillis()
        val hashId = importedFunc.getSighash() ?: error("No sighash for $importedFunc")
        val program = scene.getContractOrNull(contractNameOfFunc)
            ?.getMethodBySigHash(hashId)
            ?: return RuleCheckResult.Error(
                rule,
                AssertionError("Function $funcId does not exist in the code that we check (${this.contractName})"), // TODO: should be a spec mismatch error, not assertion error
            )

        // Remove unused assignments which might cause envfree analysis to generate false positives
        val code = program.code as CoreTACProgram
        val progWithoutUnusedAssignments = removeUnusedAssignments(
            code = code,
            expensive = true,
            isErasable = FilteringFunctions.NoFilterExceptRevertManagment::isErasable,
            isTypechecked = true
        )

        val envfreeness = EnvFreeMethodAnalysis.analyzeMethodForEnvfree(progWithoutUnusedAssignments)
        val endTime = System.currentTimeMillis()
        return if (!envfreeness.envfree) {
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                progWithoutUnusedAssignments,
                ReportTypes.ENVFREE,
                StaticArtifactLocation.Outputs,
                DumpTime.AGNOSTIC
            )
            RuleCheckResult.Single.Basic(
                rule = rule,
                result = SolverResult.SAT,
                verifyTime = VerifyTime.WithInterval(
                    startTime,
                    endTime
                ),
                ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                    failureResultMeta = listOf(RuleCheckResult.RuleFailureMeta.StaticCheck(
                        when (envfreeness) {
                            EnvfreeInfo.EnvFree -> `impossible!`
                            EnvfreeInfo.Payable -> "Specification marks method $funcId as 'envfree' but the method does not revert when msg.value > 0."
                            is EnvfreeInfo.PropertyAccess -> "Specification marks method $funcId as 'envfree' but the method uses the following restricted environment properties ${envfreeness.accessedProperties}"
                        }
                    )),
                    dumpGraphLink = null,
                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                    isSolverResultFromCache = IsFromCache.INAPPLICABLE
                ),
                ruleAlerts = emptyList(),
                callResolutionTable = CallResolutionTableBase.Empty,
            )
        } else {
            RuleCheckResult.Single.Basic(
                rule = rule,
                result = SolverResult.UNSAT,
                verifyTime = VerifyTime.WithInterval(
                    startTime,
                    endTime
                ),
                ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                    details = Result.success("Envfree method $funcId is OK"),
                    dumpGraphLink = null,
                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                    isSolverResultFromCache = IsFromCache.INAPPLICABLE
                ),
                ruleAlerts = emptyList(),
                callResolutionTable = CallResolutionTableBase.Empty
            )
        }
    }

    private fun staticRuleCheck(rule: StaticRule): RuleCheckResult {
        return when (rule.ruleType) {
            is SpecType.Single.EnvFree.Static -> {
                checkStaticEnvfreedom(rule).also { treeViewReporter.signalEnd(rule, it) }
            }
            else -> {
                throw AssertionError("Rule type ${rule.ruleType} not supported for static rule check")
            }
        }
    }

    private suspend fun handleAllSubRules(
        parentRule: GroupRule
    ): RuleCheckResult = coroutineScope {

        val ruleCheckResults = parentRule.rules.parallelMapOrdered { _, childRule ->
            treeViewReporter.registerSubruleOf(childRule, parentRule)
            check(childRule)
        }

        RuleCheckResult.Multi(
            parentRule,
            ruleCheckResults,
            RuleCheckResult.MultiResultType.PARAMETRIC
        )
    }


    fun codesToCheck(): Result<List<ICheckableTAC>> =
        runCatching { cvl.rules.flatMap { codesToCheck(it).getOrThrow() } }

    /**
     * Compile [rule] into a [CheckableTAC] list
     **/
    fun codesToCheck(rule: ICVLRule): Result<List<ICheckableTAC>> = runCatching {
        when (rule) {
            is GroupRule -> rule.rules
                    .sortedBy { it.declarationId + it.parentIdentifier }
                    .flatMap {
                        subrule ->
                        treeViewReporter.registerSubruleOf(subrule, rule);
                        codesToCheck(subrule).getOrThrow() }
            is CVLSingleRule -> CompiledRule.subRules(scene, cvl, rule).getOrThrow()
            is DynamicGroupRule, is AssertRule, is StaticRule -> emptyList()
        }
    }


    suspend fun check(rule: ICVLRule): RuleCheckResult {
        val builtinCustomChecker = (rule.ruleType as? SpecType.Single.BuiltIn)?.let {
            BuiltInRuleCustomChecker.fromBirType(it)
        }
        // We have a builtin rule with a custom checker
        return builtinCustomChecker?.check(this, rule)
            ?: when (rule) {
                is DynamicGroupRule -> throw UnsupportedOperationException("Cannot handle dynamic rules, but got $rule")
                is AssertRule -> throw UnsupportedOperationException("Cannot handle AssertRules, but got $rule")
                is CVLSingleRule -> singleRuleCheck(rule)
                is GroupRule -> this.handleAllSubRules(rule)
                is StaticRule -> staticRuleCheck(rule)
            }
    }

    companion object {
        private val throttle = Semaphore(Config.MaxConcurrentRules.get())
    }
}
