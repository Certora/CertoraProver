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
import analysis.icfg.*
import bridge.NamedContractIdentifier
import cli.SanityValues
import com.certora.collect.*
import config.*
import config.Config.MultiAssertCheck
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import diagnostics.*
import evm.SighashInt
import instrumentation.transformers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import log.*
import normalizer.AnnotationRemover
import optimizer.Pruner
import parallel.coroutines.*
import report.*
import report.callresolution.CallResolutionTableBase
import rules.RuleSplitter.getTopoSortedAssertsWithMeta
import rules.dpgraph.DPResult
import rules.dpgraph.SanityRulesDependencies
import rules.genericrulecheckers.BuiltInRuleCustomChecker
import rules.sanity.*
import scene.*
import solver.SolverResult
import spec.CVL
import spec.CVLCompiler
import spec.cvlast.*
import spec.genericrulegenerators.BuiltInRuleId
import statistics.SDCollectorFactory
import statistics.recordAny
import tac.DumpTime
import tac.TACBasicMeta
import testing.TacPipelineDebuggers.oneStateInvariant
import utils.*
import vc.data.*
import vc.data.TACMeta.SATISFY_ID
import vc.gen.TACSimpleSimple
import verifier.CoreToCoreTransformer
import java.math.BigInteger


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
        compiledSubRuleList: List<CompiledRule>
    ): RuleCheckResult = coroutineScope {
        val ruleCheckResults = compiledSubRuleList.parallelMapOrdered { _, compiledCVLRule ->
            val rule = compiledCVLRule.rule

            /*
              Indicate that every task from this point forward should check for cancellation, and fail ASAP.

              This means that some code will continue to run while we collate our results. So it goes. The 1 minute
              shutdown timer bounds how long we can drag on execution.
             */
            try {
                reporter.signalStart(rule, originalRule)
                treeViewReporter.signalStart(compiledCVLRule.rule)

                SDCollectorFactory.collector().recordAny("${TimeSinceStart()}", "startTime", compiledCVLRule.tac.name)
                @OptIn(Config.DestructiveOptimizationsOption::class)
                val res = when (Config.DestructiveOptimizationsMode.get()) {
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
                SDCollectorFactory.collector().recordAny("${TimeSinceStart()}", "finishTime", compiledCVLRule.tac.name)
                res
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
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
        if (ruleCheckResult != null && ruleCheckResult.rule.ruleType !is SpecType.Single.MultiAssertSubRule) {
            return@coroutineScope ruleCheckResult
        }

        // MultiAssert case. This can be either from multiassert mode or from the presence of satisfies.
        check(
            ((ruleCheckResults.count { it.rule.ruleType is SpecType.Single.FromUser } == 1
                || ruleCheckResults.count { it.rule.ruleType is SpecType.Single.BuiltIn
                && (it.rule.ruleType as SpecType.Single.BuiltIn).birId == BuiltInRuleId.sanity } == 1) &&
                ruleCheckResults.count { it.rule.ruleType is SpecType.Single.MultiAssertSubRule.SatisfySpecFile } == ruleCheckResults.size - 1) ||
                ruleCheckResults.all { it.rule.ruleType is SpecType.Single.MultiAssertSubRule }) {
            "Expected at this point to have only MultiAssertSubRules, or a single FromUser/BuiltinSanity rule with SatisfySubRules, got" +
                ruleCheckResults.filter { it.rule.ruleType !is SpecType.Single.MultiAssertSubRule }
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
        fun splitRuleOnAsserts(compiledSubRule: CompiledRule): List<CompiledRule> {
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

            val compiledPerAssertSubRules = mutableListOf<CompiledRule>()

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

            if (MultiAssertCheck.get() && cleanTopoSortedAsserts.size > 1 && userDefinedAssertsWithMeta.isNotEmpty()) {
                generateSubRules()
            } else if (compiledPerAssertSubRules.isNotEmpty() && userDefinedAssertsWithMeta.isNotEmpty()) {
                // We are not in multiassert, but have satisfy rules
                // Update the main rule to ignore satisfy
                val ruleName = "Assertions"
                val mainRule = RuleSplitter.newMultiAssertSubRuleOf(
                    compiledSubRule,
                    initPatchingProgram().toCode(tacProg),
                    ruleName,
                    compiledSubRule.rule.ruleType,
                    false,
                    treeViewReporter,
                    CVLRange.Empty()
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
         * Iterates over the [SummaryStack.SummaryStart] annotations commands in [prog],
         * and report them to [summaryMonitor].
         */
        fun feedSummaryMonitor(prog: CoreTACProgram) {
            val topoSortedSummaryStartAnnotCmds = prog.topoSortedSummaryStart()
            topoSortedSummaryStartAnnotCmds.forEach { summStartAnnotCmd ->
                val summStart = summStartAnnotCmd.cmd.annot.v as SummaryStack.SummaryStart
                val appliedSummary = summStart.appliedSummary
                if (appliedSummary is Summarization.AppliedSummary.MethodsBlock) {
                    summaryMonitor?.declareUseOf(appliedSummary)
                }
            }
        }

        /**
        Handles CallSummaries in the code, and updates the summary monitor.
         */
        fun applySummaries(code: CoreTACProgram) =
            Summarization.handleSummaries(
                code,
                scene, cvl,
                CVLCompiler(scene, cvl, code.name, code.symbolTable.globalScope)
            ).also {
                // this is hopefully idempotent
                feedSummaryMonitor(it)
            }


        fun applySummariesAndGhostHooksAndAxiomsTransformations(
            tac: CoreTACProgram, rule: CVLSingleRule
        ): CoreTACProgram =
            CoreTACProgram.Linear(tac)
                .map(CoreToCoreTransformer(ReportTypes.EXTCODECOPY_HANDLE, { code -> ExtcodecopyInstructionHandler.work(code, scene) }))
                .map(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE0) { ctp ->
                    /*
                     * Why go through all this rigamarole? Well, if we have a custom dispatcher implemented in spec
                     * (which consist of if/else cases over the sighash of an instantiated parametric method)
                     * then every call in the branches of these conditionals will be inlined, and summaries processed, etc. etc.
                     * leading to a huge explosion in the size of the static CFG, despite exactly one branch being feasible.
                     *
                     * Thus, we prune early here. Note also that to avoid the fallback case (which doesn't have any single,
                     * concrete sighash we can use in comparisons), we extend the simplifier to understand the "disjointSighash" application
                     * and use that to prune branches.
                     */
                    object : Pruner(ctp) {
                        // If ghosts are assigned in the body of a CVL rule to constants, they might be reassigned as a side effect of other functions.
                        // Therefore we stop at a ghost. Similarly, lastReverted and lastHasThrown are set as a side effect of function calls,
                        // so we don't want to prune paths that depend on them either.
                        override val stopAt: ((TACSymbol.Var) -> Boolean) = {
                            it.meta.containsKey(TACMeta.CVL_GHOST) ||
                                (Config.CvlFunctionRevert.get() &&
                                    (it.meta.containsKey(TACBasicMeta.LAST_REVERTED) || it.meta.containsKey(TACBasicMeta.LAST_HAS_THROWN)))
                        }

                        override val simplifier: ExpressionSimplifier =
                            object : ExpressionSimplifier(
                                ctp.analysisCache.graph
                            ) {
                                context(ExpressionSimplifier.SimplificationContext)
                                override suspend fun simplifyBinExp(
                                    e: TACExpr.BinExp,
                                    ptr: CmdPointer,
                                ): TACExpr {
                                    if (e !is TACExpr.BinRel.Eq) {
                                        return super.simplifyBinExp(e, ptr)
                                    }
                                    if (!e.operandsAreSyms()) {
                                        return super.simplifyBinExp(e, ptr)
                                    }
                                    inPrestate {
                                        val o1 = this.simplify(
                                            e.o1,
                                            ptr = ptr
                                        )
                                        val o2 = this.simplify(
                                            e.o2,
                                            ptr = ptr
                                        )
                                        val o1c = o1.getAsConst()
                                        val o2c = o2.getAsConst()
                                        return if (o1c != null && o2c != null) {
                                            TACSymbol.lift(o1c == o2c).asSym()
                                        } else if (o1c != null && isPlausibleSighash(o1c) && o2 is TACExpr.Sym.Var) {
                                            checkSighashDisjoint(o2, ptr, o1c, stopAt)
                                        } else if (o2c != null && isPlausibleSighash(o2c) && o1 is TACExpr.Sym.Var) {
                                            checkSighashDisjoint(
                                                o1, ptr, o2c, stopAt
                                            )
                                        } else {
                                            TACExpr.BinRel.Eq(o1, o2)
                                        }
                                    }
                                }

                                context(ExpressionSimplifier.SimplificationContext)
                                private suspend fun checkSighashDisjoint(
                                    otherOp: TACExpr.Sym.Var,
                                    ptr: CmdPointer,
                                    o1c: BigInteger,
                                    stopAt: ((TACSymbol.Var) -> Boolean)?
                                ): TACExpr {
                                    return nonTrivialDefAnalysis.getDefAsExpr<TACExpr>(
                                        otherOp.s,
                                        ptr,
                                        stopAt
                                    )?.let {
                                        inPrestate {
                                            this.simplify(it.exp, it.ptr)
                                        }
                                    }?.let {
                                        it as? TACExpr.Apply
                                    }?.takeIf {
                                        (it.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif == TACBuiltInFunction.DisjointSighashes &&
                                            it.ops.size == 1
                                    }?.ops?.single()?.evalAsConst()?.let {
                                        scene.getContractOrNull(it)
                                    }?.getMethods()?.all {
                                        it.sigHash != SighashInt(o1c)
                                    }?.let(TACSymbol.Companion::lift)?.asSym() ?: TACExpr.BinRel.Eq(
                                        o1c.asTACSymbol().asSym(),
                                        otherOp
                                    )
                                }

                                fun isPlausibleSighash(o1c: BigInteger): Boolean {
                                    // Fits in 32 bits and larger than 256
                                    return o1c > BigInteger.TWO.pow(8) && o1c < BigInteger.TWO.pow(32)
                                }
                            }
                    }.prune()
                })
                .map(CoreToCoreTransformer(ReportTypes.APPLIED_SUMMARIES1) { code ->
                    applySummaries(code)
                }).map(CoreToCoreTransformer(ReportTypes.OPAQUE_IDENTITY_REMOVAL_2, AnnotationRemover::removeOpaqueIdentities))
                .map(CoreToCoreTransformer(ReportTypes.SPLIT_STORAGE_VAR_HOISTER) { code ->
                    // for proper hooking over split storage variables in expression such as
                    // tacS!...:bv256 + 1, we need to lift those to tmp := tacS!...:bv256 and then use tmp instead
                    SplitStorageVarsHoister.transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.INLINED_HOOKS) { code ->
                    // inline hooks' codes
                    HookInliner(scene, CVLCompiler(
                        scene, cvl, code.name, code.symbolTable.globalScope
                    )).transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.APPLIED_SUMMARIES2) { code ->
                    // IMPORTANT: hooks will not be instrumented for summaries applied at this stage
                    // (i.e. if the summaries call Solidity)
                    applySummaries(code)
                }).map(CoreToCoreTransformer(ReportTypes.POST_SUMMARIZATION_STORAGE_CLEANUP) { code ->
                    CVLToSimpleCompiler.finalizeStorageMovement(
                        code
                    )
                }).map(CoreToCoreTransformer(ReportTypes.STRONG_INVARIANT_INLINER) { code ->
                    // inlines strong invariants
                    StrongInvariantInliner(scene, CVLCompiler(
                        scene, cvl, code.name, code.symbolTable.globalScope
                    ), rule).transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.REVERT_PATH_GENERATOR) {
                    if (Config.CvlFunctionRevert.get()) {
                        RevertPathGenerator.transform(it)
                    } else {
                        it
                    }
                }).map(CoreToCoreTransformer(ReportTypes.REVERT_MATERIALIZATION) {
                    Inliner.materializeRevertManagement(it, scene)
                }).map(CoreToCoreTransformer(ReportTypes.GHOST_ANNOTATION) { code ->
                    // apply ghost save/restore, which should line up with solidity reverts and CVL storage management
                    // this is implemented outside of summaries, because it's better to be handled after we applied hooks
                    GhostSaveRestoreInstrumenter.transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.FOUNDRY_ANNOTATION) { code ->
                    if (Config.Foundry.get()) {
                        // apply foundry cheatcodes
                        FoundryInstrumenter.transform(code)
                    } else {
                        code
                    }
                }).map(CoreToCoreTransformer(ReportTypes.AXIOM_INLINING) { code ->
                    // Inline axiom expressions into tac so analysis and optimization will be axiom aware.
                    // This has to come after materializing all ghosts, hooks, and summarizations.
                    // In general for correctness, axiom inlining should happen after all code pieces are present.
                    AxiomInliner.transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.GHOST_SUM_INSTRUMENTER) { code ->
                    // Inline sum ghost updates. This must come after axiom inlining because the axioms may have
                    // references to ghosts that are summed. Since we know the sum ghosts don't have any axioms, it's OK
                    // to inline them here.
                    GhostSumInstrumenter(cvl.ghosts).transform(code)
                }).map(CoreToCoreTransformer(ReportTypes.SIMPLE_HASH) { code ->
                    // Apply the simple-simple-ing of AssignSha3Cmd, as it generates some asserts we want to split on
                    TACSimpleSimple.simpleSimpleHashing(code)
                }).map(CoreToCoreTransformer(ReportTypes.OBJECT_PATH_MATCHING) { code ->
                    ObjectPathMatchingTransformer.analysis(code)
                }).map(CoreToCoreTransformer(ReportTypes.ABI_ENCODE_REMOVAL_POST_SUMMARY) { code ->
                    ABIOptimizations.garbageCollectCVLEncoding(code)
                }).ref

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
        ): Result<List<CompiledRule>> = runCatching {
            // this is the real-real preoptimized
            oneStateInvariant(tac, ReportTypes.PREINSTRUMENTED_RULE)
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                tac,
                ReportTypes.PREINSTRUMENTED_RULE,
                StaticArtifactLocation.Outputs,
                DumpTime.AGNOSTIC
            )
            // Before splitting on asserts, we must handle all summaries and ghosts' hooks
            // as those may introduce new assert commands
            applySummariesAndGhostHooksAndAxiomsTransformations(tac, rule)
        }.map {
            val compiledSubRuleWithAppliedSummaries =
                CompiledRule.create(
                    codeToCheck = it,
                    rule = rule,
                    liveStatsReporter = treeViewReporter.liveStatsReporter
                )
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
        fun getRuleTypeMetaData(ruleType: SpecType.Single.GeneratedFromBasicRule): SanityRuleMetaData = when (ruleType) {
            is SpecType.Single.GeneratedFromBasicRule.AssertTautologyCheck -> {
                SanityRuleMetaData(GenerateRulesForAssertions.GenerateRulesForAssertTautologyChecks.sanityRuleName, ruleType.assertCVLCmd.getSuffixDecId())
            }
            is SpecType.Single.GeneratedFromBasicRule.TrivialInvariantCheck -> {
                SanityRuleMetaData(GenerateRulesForAssertions.GenerateRulesForTrivialInvariantCheck.sanityRuleName, "_postcondition")
            }
            is SpecType.Single.GeneratedFromBasicRule.RedundantRequireCheck -> {
                SanityRuleMetaData(GenerateRulesForRedundantRequiresCheck.sanityRuleName, ruleType.assumeCVLCmd.getAssumeSuffixDecId())
            }
            is SpecType.Single.GeneratedFromBasicRule.VacuityCheck -> {
                SanityRuleMetaData(GenerateRulesForVacuityCheck.sanityRuleName, "")
            }
            is SpecType.Single.GeneratedFromBasicRule.AssertionStructureCheck -> {
                SanityRuleMetaData(
                    ruleType.sanityRuleName,
                    ruleType.assertCVLCmd.getSuffixDecId()
                )
            }
        }

        /**
         * Given the [RuleCheckResult] [originalBaseResult] and the computed [SanityCheckResult] of [updatedBaseRule],
         * determines what final result will be attached to [updatedBaseRule].
         */
        fun getFinalBaseResult(
            originalBaseResult: RuleCheckResult,
            updatedBaseRule: IRule,
            sanityResultForBase: SanityCheckResult
        ): RuleCheckResult {
            fun getSingleOrMultiResult(): RuleCheckResult =
                when (originalBaseResult) {
                    is RuleCheckResult.Single -> {
                        // [getSingleOrMultiResult] is called when [SanityResultForBase.SanityCheckResultOrdinal] is not
                        // [FAILED]. In this case we present all the alerts except for ones indicating failure or success of
                        // sanity checks.
                        val ruleAlerts = sanityResultForBase.allAlertsInRange(
                            SanityCheckResultOrdinal.TIMEOUT, SanityCheckResultOrdinal.ERROR
                        ).let { sanityAlertReport ->
                            originalBaseResult.ruleAlerts?.join(sanityAlertReport)
                        }
                        val updatedSolverResult = if (originalBaseResult.result == SolverResult.UNSAT) {
                            // get the sanity-result
                            when (sanityResultForBase.ordinal) {
                                // note that the [SanityCheckResultOrdinal.FAILED] and the [SanityCheckResultOrdinal.ERROR]
                                // cases are unreachable (should have already being treated)
                                SanityCheckResultOrdinal.PASSED -> originalBaseResult.result
                                SanityCheckResultOrdinal.UNKNOWN, SanityCheckResultOrdinal.TIMEOUT,
                                SanityCheckResultOrdinal.FAILED, SanityCheckResultOrdinal.ERROR -> SolverResult.SANITY_FAIL
                            }
                        } else {
                            // get the original result. the sanity-result is expected to be trivially PASSED
                            // since we run sanity-checks only for rules which the prover found to be UNSAT
                            if (sanityResultForBase.ordinal != SanityCheckResultOrdinal.PASSED) {
                                logger.warn { "Expected the sanity checks for the (UNSAT) rule ${originalBaseResult.rule} to trivially pass" }
                            }
                            originalBaseResult.result
                        }
                        // since [base] is Single, asserts are passing
                        // returning single summary
                        when (originalBaseResult) {
                            is RuleCheckResult.Single.Basic -> {
                                RuleCheckResult.Single.Basic(
                                    rule = updatedBaseRule,
                                    result = updatedSolverResult,
                                    verifyTime = originalBaseResult.verifyTime,
                                    ruleCheckInfo = originalBaseResult.ruleCheckInfo,
                                    callResolutionTable = originalBaseResult.callResolutionTable,
                                    ruleAlerts = ruleAlerts,
                                    unsatCoreStats = originalBaseResult.unsatCoreStats
                                )
                            }
                            is RuleCheckResult.Single.WithCounterExamples -> {
                                RuleCheckResult.Single.WithCounterExamples(
                                    rule = updatedBaseRule,
                                    result = updatedSolverResult,
                                    verifyTime = originalBaseResult.verifyTime,
                                    ruleCheckInfo = originalBaseResult.ruleCheckInfo,
                                    callResolutionTable = originalBaseResult.callResolutionTable,
                                    ruleAlerts = ruleAlerts,
                                )
                            }
                        }
                    }
                    is RuleCheckResult.Multi -> {
                        check(
                            originalBaseResult.resultType == RuleCheckResult.MultiResultType.SPLIT_ASSERTS
                        ) {
                            "Got a multi result for the base rule (${
                                originalBaseResult
                            }) whose type is not MultiResultType.SPLIT_ASSERTS"
                        }
                        // [originalBaseResult] is Multi with multi-assert children results.
                        // NOTE: It may still be that all asserts are passing (it's not necessarily the case that some fail)
                        originalBaseResult.copy(
                            rule = updatedBaseRule
                        )
                    }
                    else -> throw IllegalStateException(
                        "Expected the base result ($originalBaseResult) to be either single or multi result"
                    )
                }
            return if (originalBaseResult is RuleCheckResult.Error) {
                RuleCheckResult.Error(
                    rule = updatedBaseRule,
                    ruleAlerts = originalBaseResult.ruleAlerts
                )
            } else {
                /* notice that if the result of the base rule is SAT, no sanity-checks
                will actually run, thus, sanityResultForBase.ordinal will be (trivially)
                SanityCheckResultOrdinal.PASSED */
                when (sanityResultForBase.ordinal) {
                    SanityCheckResultOrdinal.FAILED -> {
                        if (originalBaseResult is RuleCheckResult.Multi) {
                            originalBaseResult.copy(
                                rule = updatedBaseRule,
                                parentSanityResult = sanityResultForBase
                            )
                        } else {
                            RuleCheckResult.Single.Basic(
                                rule = updatedBaseRule,
                                result = SolverResult.SANITY_FAIL,
                                // TODO: should take into account the sanity-rules' times: CERT-4186
                                verifyTime = originalBaseResult.computeVerifyTime(),
                                ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                                    failureResultMeta = emptyList(),
                                    dumpGraphLink = (originalBaseResult as? RuleCheckResult.Single.Basic)?.ruleCheckInfo?.dumpGraphLink,
                                    isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                                    isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                                ),
                                callResolutionTable = (originalBaseResult as? RuleCheckResult.Single.Basic)?.callResolutionTable
                                    ?: CallResolutionTableBase.Empty,
                                // In case of sanity failure of the base we present all the alerts except for ones
                                // indicating passing sanity checks.
                                ruleAlerts = sanityResultForBase.allAlertsInRange(from = SanityCheckResultOrdinal.TIMEOUT),
                                unsatCoreStats = (originalBaseResult as? RuleCheckResult.Single.Basic)?.unsatCoreStats
                            )
                        }
                    }
                    SanityCheckResultOrdinal.ERROR -> {
                        sanityResultForBase.errors.let { alerts ->
                            alerts.toNonEmptyList()?.let { ruleErrorAlerts ->
                                RuleCheckResult.Error(
                                    updatedBaseRule,
                                    RuleAlertReport(ruleErrorAlerts),
                                )
                            } ?: RuleCheckResult.Error(
                                updatedBaseRule,
                                CertoraException(
                                    CertoraErrorType.NO_SANITY_RESULTS,
                                    "No sanity results for ${updatedBaseRule.declarationId}"
                                ),
                            )
                        }
                    }
                    SanityCheckResultOrdinal.PASSED,
                    SanityCheckResultOrdinal.TIMEOUT,
                    SanityCheckResultOrdinal.UNKNOWN -> {
                        getSingleOrMultiResult()
                    }
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
            val hasMethodInstFromNonPrimaryContract = codesToCheck.any { (_, methodMatch, _, _) ->
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

            /**
             * Given [methodMatch], returns a pair, containing a list of the chosen instantiations,
             * and a declaration-id for the corresponding rule, which is a concatenation of the chosen
             * instantiations, separated by [OUTPUT_NAME_DELIMITER].
             */
            fun methodMatchToRuleName(methodMatch: MethodParameterInstantiation): Pair<List<String>, String> {
                val sortedMethodMatch = methodMatch.toSortedMap()
                val methodInstsNames = sortedMethodMatch.map { (_, methodInfo) ->
                    if (hasMethodInstFromNonPrimaryContract) {
                        "${methodInfo.contractName}.${methodInfo}"
                    } else {
                        methodInfo.toString()
                    }
                }
                val declarationId = methodInstsNames.joinToString(separator = OUTPUT_NAME_DELIMITER)
                logger.info {
                    "Sorted method match for rule ${rule.declarationId} instance: ${
                        sortedMethodMatch.mapValues {
                            it.value.toString()
                        }
                    }"
                }
                return methodInstsNames to declarationId
            }

            fun createCompiledRule(
                newSingleRule: CVLSingleRule,
                subCode: CoreTACProgram
            ): CompiledRule {
                return CompiledRule.create(
                    newSingleRule,
                    subCode.copy(name = newSingleRule.ruleIdentifier.toString()),
                    treeViewReporter.liveStatsReporter
                )
            }

            fun ruleWithExpectedSanityGenerationMeta(
                rule: CVLSingleRule,
                sanity: SingleRuleGenerationMeta.Sanity,
                expectedSanity: SingleRuleGenerationMeta.Sanity
            ): CVLSingleRule {
                require(sanity == expectedSanity) {
                    "Expected the SingleRuleGenerationMeta.Sanity of rule ${rule.declarationId} to be [$expectedSanity], got [$sanity]"
                }
                return rule.copy(
                    ruleGenerationMeta = SingleRuleGenerationMeta.WithSanity(sanity)
                )
            }

            // create a map of compiled rules for sanity rules
            val compiledBaseSubRules = codesToCheck.map { checkableTAC ->
                val subCode = checkableTAC.tac
                val methodMatch = checkableTAC.methodParameterInstantiation
                val sanity = checkableTAC.sanity
                val currRule = checkableTAC.subRule

                val newSingleRule = if (methodMatch.isNotEmpty()) {
                    val (methodInstsNames, declarationId) = methodMatchToRuleName(methodMatch)
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

                    ruleWithExpectedSanityGenerationMeta(currRule, sanity, SingleRuleGenerationMeta.Sanity.PRE_SANITY_CHECK)
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
            val compiledSubRulesSanity = sanityCheckableTACs.map { (subCode, methodMatch ,sanity, currRule) ->
                val sanityRuleType: SpecType.Single.GeneratedFromBasicRule =
                    currRule.narrowType<SpecType.Single.GeneratedFromBasicRule>().ruleType
                val sanityRuleMetaData = getRuleTypeMetaData(sanityRuleType)
                val ruleName = "${sanityRuleMetaData.ruleSanitySuffix}${sanityRuleMetaData.loc}"
                val matchingOrigRes = currRule.ruleType.getOriginatingRule()
                val sanityRuleIdentifier = matchingOrigRes!!.ruleIdentifier.freshDerivedIdentifier(ruleName)

                val newSingleSanityRule = if (methodMatch.isNotEmpty()) {
                    val (methodInstsNames, _) = methodMatchToRuleName(methodMatch)
                    currRule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                            sanity,
                            currRule.cvlRange,
                            methodInstsNames,
                        ),
                        ruleIdentifier = sanityRuleIdentifier
                    )
                } else {
                    /* sanity rules have [SingleRuleGenerationMeta.Sanity.BASIC_SANITY] (see CompiledRule::compileSanityCheckSubRules). */
                    ruleWithExpectedSanityGenerationMeta(currRule, sanity, SingleRuleGenerationMeta.Sanity.BASIC_SANITY).copy(ruleIdentifier = sanityRuleIdentifier)
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
            suspend fun evalRule(compiledSubRule: CompiledRule): RuleCheckResult {
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
                val resultsContainer = SanityResultsContainer(results)

                return if (Config.DoSanityChecksForRules.get() == SanityValues.NONE) { // no sanity check
                    if (!isWithMethodInstantiations) {
                        // single non-parametric rule, so there should be a single result
                        resultsContainer.baseResults.singleOrNull()?.result
                            ?: throw IllegalStateException("Got no base result for ${rule.declarationId}")
                    } else {
                        RuleCheckResult.Multi(
                            rule, // TODO: this is not necessarily correct, for example, it might be the case that a contract-rule is the actual parent-rule: CERT-4077
                            allResults,
                            RuleCheckResult.MultiResultType.PARAMETRIC
                        )
                    }
                } else {
                    val enabledSanityChecksViews = resultsContainer.narrow(enabledSanityChecksSorts(allResults))
                    val sanityResultSummarizer = SanityResultSummarizer(enabledSanityChecksViews)
                    val mergedWithSanity = resultsContainer.baseResults.map { originalBaseResult ->
                        val baseRuleMeta = (originalBaseResult.result.rule as CVLSingleRule).ruleGenerationMeta
                        val updatedBaseRule = (originalBaseResult.result.rule as CVLSingleRule).copy(ruleGenerationMeta = baseRuleMeta.updateSanity(SingleRuleGenerationMeta.Sanity.DONE))
                        val sanityResultForBase = sanityResultSummarizer.joinByBase(originalBaseResult)
                        getFinalBaseResult(originalBaseResult.result, updatedBaseRule, sanityResultForBase)
                    }

                    if (isWithMethodInstantiations) {
                        RuleCheckResult.Multi(
                            rule, // TODO: this is not necessarily correct, for example, it might be the case that a contract-rule is the actual parent-rule: CERT-4077
                            mergedWithSanity,
                            RuleCheckResult.MultiResultType.PARAMETRIC,
                            parentSanityResult = sanityResultSummarizer.parentResult
                        )
                    } else {
                        mergedWithSanity.first()
                    }
                }
            }

            return SanityRulesDependencies
                .generate(compiledSubRules)
                .resultComputation(
                    ::evalRule,
                    ::reduceResults
                ) { r ->
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
                ruleAlerts = null,
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
                ruleAlerts = null,
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
    fun codesToCheck(rule: IRule): Result<List<ICheckableTAC>> = runCatching {
        when (rule) {
            is GroupRule -> rule.rules
                    .sortedBy { it.declarationId + it.parentIdentifier }
                    .flatMap {
                        subrule ->
                        treeViewReporter.registerSubruleOf(subrule, rule);
                        codesToCheck(subrule).getOrThrow() }
            is CVLSingleRule -> CompiledRule.subRules(scene, cvl, rule).getOrThrow()
            is AssertRule, is StaticRule -> emptyList()
        }
    }


    suspend fun check(rule: IRule): RuleCheckResult {
        val builtinCustomChecker = (rule.ruleType as? SpecType.Single.BuiltIn)?.let {
            BuiltInRuleCustomChecker.fromBirType(it)
        }
        // We have a builtin rule with a custom checker
        return builtinCustomChecker?.check(this, rule)
            ?: when (rule) {
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
