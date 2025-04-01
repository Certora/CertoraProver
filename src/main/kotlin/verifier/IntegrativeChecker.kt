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

package verifier

import analysis.CommandWithRequiredDecls
import analysis.constructor.SceneConstructorOracle
import analysis.hash.DisciplinedHashModel
import analysis.icfg.*
import analysis.icfg.Summarization.hasSummaryForResolved
import analysis.ip.*
import analysis.split.StorageSplitter
import analysis.split.StorageTypeBounder
import analysis.storage.*
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import config.*
import config.Config.IsAssumeUnwindCondForLoops
import config.Config.LoopUnrollConstant
import datastructures.LinkedArrayHashMap
import datastructures.stdcollections.*
import icfg.ContractStorageResolver
import instrumentation.ImmutableInstrumenter
import instrumentation.StoragePathAnnotation
import instrumentation.StorageReadInstrumenter
import instrumentation.calls.materializeReturnSymbol
import instrumentation.calls.returnSymbol
import instrumentation.transformers.ABIOptimizations
import instrumentation.transformers.BranchSnippetInstrumenter
import instrumentation.transformers.MemoryPartition
import log.*
import normalizer.MemoryOverlapFixer
import optimizer.Pruner
import optimizer.hashRewrites
import optimizer.rewriteCodedataCopy
import report.*
import report.callresolution.CallResolutionTableBase
import rules.*
import scene.*
import solver.SolverResult
import spec.CVL
import spec.CVLKeywords
import spec.IWithSummaryInfo
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.rules.AssertRule
import spec.rules.IRule
import spec.toVar
import tac.*
import utils.*
import vc.data.*
import verifier.ContractUtils.tacOptimizations
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)


private const val SUMMARY_X = "\u2717"
private const val SUMMARY_CHECK = "\u2714"

object IntegrativeChecker {

    private fun addSinkForAssertions(x: CoreTACProgram): CoreTACProgram {
        val lastHasThrown = CVLKeywords.lastHasThrown.toVar(Tag.Bool)
        val lastHasNotThrown = TACKeyword.TMP(Tag.Bool).toUnique()
        return (if (x.code.isEmpty()) {
            require(x.blockgraph.isEmpty())
            x.copy(
                code = mapOf(Pair(StartBlock, listOf())),
                blockgraph = BlockGraph(StartBlock to treapSetOf())
            )
        } else {
            x
        })
            .prependToBlock0(
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            CVLKeywords.lastHasThrown.toVar(Tag.Bool),
                            TACSymbol.Const(BigInteger.ZERO, Tag.Bool)
                        )
                    ),
                    CVLKeywords.lastHasThrown.toVar()
                )
            )
            .addSinkMainCall(
                CommandWithRequiredDecls<TACCmd.Simple>(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lastHasNotThrown,
                            TACExpr.UnaryExp.LNot(lastHasThrown.asSym())
                        ),
                        TACCmd.Simple.AssertCmd(
                            lastHasNotThrown,
                            "Assertion check"
                        )
                    ),
                    setOf(lastHasNotThrown)
                )
            ).first
    }

    data class AssertionCheck(
        val code: ITACMethod,
        val rule: IRule
    )

    private suspend fun handleAssertions(
        scene: IScene,
        assertions: ProverQuery.AssertionQuery,
        reporter: OutputReporter,
    ): List<RuleCheckResult.Single> {
        if (assertions.contractsToCheckAssert.isNotEmpty()) {
            Logger.always("Has assertion checks.", respectQuiet = false)
            scene.mapContractMethodsInPlace("assert-inline") { _scene, _method ->
                val transformers = ChainedMethodTransformers(
                    listOf(
                        CoreToCoreTransformer(ReportTypes.ASSERTION_SINK, ::addSinkForAssertions).lift(),
                        CoreToCoreTransformer(ReportTypes.MERGED) { p: CoreTACProgram -> BlockMerger.mergeBlocks(p) }.lift(),
                        MethodToCoreTACTransformer(ReportTypes.INLINED) body@{ m: ITACMethod ->
                            val c = m.code as CoreTACProgram
                            /**
                             * The callee analysis in [Summarization] assumes loop free code, but we don't
                             * unroll in library contracts. Thus we skip doing summaries on libraries (we can
                             * think harder about this if someone actually cares).
                             */
                            if((m.getContainingContract() as? IContractWithSource)?.src?.isLibrary == true) {
                                Logger.alwaysWarn("Not running summarization on library contract ${m.getContainingContract().name} in assertion checking mode")
                                return@body c
                            }
                            Summarization.handleSummaries(
                                c,
                                _scene,
                                object : IWithSummaryInfo {
                                    override val internalSummaries: Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>
                                        get() = emptyMap()

                                    override val externalSummaries: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>
                                        get() = emptyMap()
                                    override val unresolvedSummaries: Map<CVL.SummarySignature.External, SpecCallSummary.DispatchList>
                                        get() = emptyMap()
                                },
                                null
                            )
                        },
                        CoreToCoreTransformer(
                            ReportTypes.PATH_OPTIMIZE_FOR_ASSERTIONS_PASS,
                            { c: CoreTACProgram -> Pruner(c).prune() }
                        ).lift()
                    )
                )
                ContractUtils.transformMethodInPlace(_method, transformers)
            }
        }

        val contractNames = scene.getContracts().map { it.name }
        val assertionChecks = mutableListOf<AssertionCheck>()
        assertions.contractsToCheckAssert.forEach { contract ->
            Logger.always("Checking assertions for ${contract}...", respectQuiet = false)
                if (contract.name !in contractNames) {
                    throw Exception("No contract $contract in ${contractNames}")
                }

            val inlinedCodes = scene.getContract(contract)
            inlinedCodes.getDeclaredMethods().forEach { inlinedCode ->
                val funcName = inlinedCode.soliditySignature
                val assertRule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
                    val declId = contract.name + OUTPUT_NAME_DELIMITER + funcName
                    AssertRule(RuleIdentifier.freshIdentifier(declId), true, funcName, scope)
                }
                StatusReporter.registerSubrule(assertRule)
                assertionChecks.add(
                    AssertionCheck(
                        inlinedCode,
                        assertRule
                    )
                )
            }
        }

        // TODO: Run in parallel if we really want...
        val assertionResults = mutableListOf<RuleCheckResult.Single>()
        assertionChecks.forEach { assertionCheck ->
            val inlinedCode = assertionCheck.code
            val assertRule = assertionCheck.rule
            val contract = inlinedCode.getContainingContract().name
            val funcName = inlinedCode.soliditySignature

            //  Add check AssertNot lastHasThrown as sink - added during inlinization

            // adding state links? TODO

            val codeToCheck = inlinedCode.code as CoreTACProgram
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                codeToCheck,
                ReportTypes.SOL_METHOD_WITH_ASSERTS,
                StaticArtifactLocation.Outputs,
                DumpTime.AGNOSTIC
            )
            logger.info { "Processing for assertions:" }

            val startTime = System.currentTimeMillis()
            val vcRes = checkVC(scene, codeToCheck, liveStatsReporter = DummyLiveStatsReporter, assertionCheck.rule)
            val endTime = System.currentTimeMillis()
            val finalResult = vcRes.finalResult
            when (finalResult) {
                SolverResult.SAT -> {
                    Logger.always(
                        "For contract $contract " +
                            "and method ${funcName}, " +
                            "found a violated assertion. See report.", respectQuiet = false
                    )
                }

                SolverResult.UNSAT -> {
                    Logger.always(
                        "For contract $contract " +
                            "and method ${funcName}, " +
                            "proved that all assertions hold.", respectQuiet = false
                    )
                }

                SolverResult.UNKNOWN, SolverResult.TIMEOUT -> {
                    Logger.always(
                        "For contract $contract " +
                            "and method ${funcName}, " +
                            "failed to execute.", respectQuiet = false
                    )
                }

                SolverResult.SANITY_FAIL -> throw IllegalStateException("Unexpected solver result in assert mode: $finalResult")
            }

            assertionResults.add(
                if (finalResult != SolverResult.SAT) {
                    RuleCheckResult.Single.Basic(
                        rule = assertRule,
                        result = finalResult,
                        verifyTime = VerifyTime.WithInterval(startTime, endTime),
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                            dumpGraphLink = null,
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE
                        ),
                        CallResolutionTableBase.Empty,
                        ruleAlerts = null
                    )
                } else {
                    RuleCheckResult.Single.WithCounterExamples(
                        rule = assertRule,
                        result = finalResult,
                        verifyTime = VerifyTime.WithInterval(startTime, endTime),
                        ruleAlerts = null,
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.WithExamplesData(
                            vcRes.examplesInfo.map { exampleInfo ->
                                RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample(
                                    //todo: seems like a bug (might affect the way things are reported in the --assert mode)
                                    failureResultMeta = if (exampleInfo.reason != null) {
                                        listOf(
                                            RuleCheckResult.RuleFailureMeta.ViolatedAssert(
                                                assertRule.ruleIdentifier.derivedAssertIdentifier(exampleInfo.reason),
                                                exampleInfo.reason
                                            )
                                        )
                                    } else {
                                        emptyList()
                                    },
                                    rule = assertRule,
                                    model = exampleInfo.model,
                                    dumpGraphLink = null
                                )
                            },
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE
                        )
                    )
                }
            )
        }

        reporter.feedReporter(assertionResults, scene)
        return assertionResults
    }

    // TODO: Separate verify and result generation, .verify() to return instead of having side-effects on the class
    private suspend fun checkVC(
        scene: ISceneIdentifiers,
        codeToCheck: CoreTACProgram,
        liveStatsReporter: LiveStatsReporter,
        singleRule: IRule,
    ): Verifier.VerifierResult =
        TACVerifier.verify(scene, codeToCheck, liveStatsReporter, singleRule)

    private suspend fun handleCVLs(
        scene: IScene,
        cvlsToCheck: ProverQuery.CVLQuery.Single,
        reporter: OutputReporter,
        treeViewReporter: TreeViewReporter
    ): List<RuleCheckResult> {
        // currently supporting only a single CVL query
        Logger.always("Has CVL checks.", respectQuiet = false)

        val contract = cvlsToCheck.primaryContract
        val cvl = cvlsToCheck.cvl
        if (contract.name !in scene.getContracts().map { it.name }) {
            throw Exception(
                "Did not process contract $contract out of ${scene.getContracts().map { it.name }}"
            ) // baa
        }

        Logger.always("Checking CVL ${cvl.name} for contract $contract", respectQuiet = false)

        if (LoopUnrollConstant.get() == 1) {
            "1 time"
        } else {
            "${LoopUnrollConstant.get()} times"
        }.let { times ->
            if (IsAssumeUnwindCondForLoops.get()) {
                Logger.always("Unrolling loops $times, unwinding condition is assumed", respectQuiet = false)
            } else {
                Logger.always("Unrolling loops $times, unwinding condition is asserted", respectQuiet = false)
            }
        }

        // update autosummary monitor
        AutosummarizedMonitor.reportSummarizedMethods(cvl)

        if (Config.BoundedModelChecking.getOrNull() != null) {
            return BoundedModelChecker(cvl, scene, contract, treeViewReporter, reporter).checkAllInvariants()
        }

        HTMLReporter.expectedNumberOfRules = cvl.rules.flatMap { it.getAllSingleRules() }.size
        val specChecker =
            SpecChecker(
                contract,
                cvl,
                reporter,
                treeViewReporter,
                scene
            )

        return specChecker.checkAll()
    }

    suspend fun run(specSource: ISpecSource, contractSource: IContractSource): List<RuleCheckResult> {
        val cvlScene = SceneFactory.getCVLScene(contractSource)
        var query = specSource.getQuery(contractSource.instances(), cvlScene)
            .resultOrExitProcess(1, CVLError::printErrors)

        val scene = SceneFactory.getScene(contractSource, SceneType.PROVER, (query as? ProverQuery.CVLQuery.Single)?.cvl)
        query = query.copyWithFilteredCVLRules(scene)
        // some prints that we may decide to get rid of in the future
        logSceneInfo(scene)

        // setting up the main object for reporting rule results
        val reporterContainer = setupReporters(query)

        // some special casing we do for each of the types
        ConfigScope(
            ConfigType.CheckNoAddedAssertions,
            ConfigType.CheckNoAddedAssertions.get() ||
                (query is ProverQuery.AssertionQuery && query.contractsToCheckAssert.isEmpty())
        ).use {
            val extensionContractsMapping = ExtensionContractsMapping.fromContracts(contractSource.instances())

            return runWithScene(scene, query, reporterContainer, extensionContractsMapping)
        }
    }

    fun codesToCheck(
        scene: IScene,
        query: ProverQuery.CVLQuery.Single,
        reporter: OutputReporter,
        treeViewReporter: TreeViewReporter
    ): Result<List<ICheckableTAC>> {
        runInitialTransformations(scene, query)
        val forked = scene.fork(IScene.ForkInfo.CVL)
        val (contract, cvl) = query
        return RuleChecker(forked, contract, cvl, reporter, treeViewReporter, summaryMonitor = null).codesToCheck()
    }

    /**
     * A pass on the Scene that prepares it before we integrate the spec.
     *
     * Before inline:
     * - resolve synthetic contract links
     * - optimize revert strings
     * - split out memory
     * - find out about return buffers that can be converted to return symbols
     *
     * Resolve calls
     *
     * Materialize eligible return buffers to return symbols
     *
     * Inline delegatecalls
     *
     * Split storage
     *
     * Run storage analysis
     *
     * Inline all other calls
     *
     * Materialize revert management
     *
     * Unroll
     *
     * Run TAC optimizations
     */
    fun runInitialTransformations(scene: IScene, query: ProverQuery, extensionContractsMapping: ExtensionContractsMapping? = null) {
        val cvlQuery = (query as? ProverQuery.CVLQuery.Single)?.cvl
        fun isAlwaysNondetDelegate(c: CallSummary) = Config.SummarizeExtLibraryCallsAsNonDetPreLinking.get() && c.callType == TACCallType.DELEGATE
        if(!extensionContractsMapping.isNullOrEmpty()) {
            if(scene !is ExtendableScene) {
                throw UnsupportedOperationException("Cannot extend contracts according to $extensionContractsMapping in unextendable scene")
            }
            for((target, extensionInfos) in extensionContractsMapping) {
                val ext = extensionInfos.flatMap { extensionInfo ->
                    val contract = scene.getContract(extensionInfo.extensionContract)

                    contract.getStandardMethods().filter {
                        it.name !in extensionInfo.funcsToExclude && it.attribute != MethodAttribute.Unique.Fallback
                    }.map { it as TACMethod }

                }
                scene.extendContractWith(target, ext)
            }

            // Iterate on the mapping a second time, this time to "delete" the implementation in the extension contracts
            // themselves. This is done so we could catch early if somehow someone called a function of the extension
            // contract directly which is unsupported.
            for((_, extensionInfos) in extensionContractsMapping) {
                extensionInfos.forEach { extensionInfo ->
                    val contract = scene.getContract(extensionInfo.extensionContract)
                    contract.mapMethodsInPlace(scene) { _, m ->
                        val prog = CoreTACProgram(
                            code =  mapOf(StartBlock to listOf(
                                TACCmd.Simple.AssertCmd(TACSymbol.False, "direct call of an extension function")
                            )),
                            blockgraph = LinkedArrayHashMap(mapOf(StartBlock to treapSetOf())),
                            symbolTable = TACSymbolTable.empty(),
                            ufAxioms = UfAxioms.empty(),
                            name = m.code.name,
                            entryBlock = StartBlock,
                            procedures = setOf(),
                            check = true
                        )
                        @Suppress("Deprecation")
                        m.updateCode(prog)
                    }
                }
            }
        }
        val isUnsummarized = if(cvlQuery == null) {
            { c: CallSummary, _: ContractId -> !isAlwaysNondetDelegate(c) }
        } else {
            res@{ s: CallSummary, resolvedCallee: ContractId ->
                !isAlwaysNondetDelegate(s) && !cvlQuery.external.hasSummaryForResolved(
                    scene = scene,
                    calleeID = resolvedCallee,
                    sigHash = s.sigResolution.singleOrNull() ?: return@res true
                )
            }
        }

        Logger.always("Running initial transformations", respectQuiet = false)
        scene.mapContractMethodsInPlace(IScene.MapSort.PARALLEL,"initial_preInline") { theScene, method ->
            ContractUtils.transformMethodInPlace(
                method,
                ChainedMethodTransformers(
                    listOf(
                        // this performs the linking of a library contract address, as
                        // was instrumented by certoraBuild
                        CoreToCoreTransformer(ReportTypes.CONTRACT_LINKING) { c: CoreTACProgram ->
                            ContractLinker.linkContracts(c, theScene)
                        }.lift(),
                        // convert codedata-copy (a bytelongcopy from codedata) to a series of bytestores
                        MethodToCoreTACTransformer(ReportTypes.CODEDATA_CONVERT) { m ->
                            rewriteCodedataCopy(theScene, m)
                        }
                    )
                )
            )
        }

        // annotate the code with TACSummary commands that tell how contracts link to other contracts
        scene.mapContractMethodsInPlace(IScene.MapSort.PARALLEL,"ext_resolution") { theScene, m ->
            ContractUtils.transformMethodInPlace(
                m,
                ChainedMethodTransformers(
                    listOf(
                        MethodToCoreTACTransformer(ReportTypes.EXTCALL_RESOLUTION) { method ->
                            val (analysisResults, pointsTo) = CallGraphBuilder.doSigAndInputAndCalleeAnalysis(method, theScene)
                            val code = method.code as CoreTACProgram
                            // as it is now it won't work. the pointers aren't preserved. but we can collect patches to apply
                            val patch = code.toPatchingProgram()
                            val updatedHashCallCoreCmds = DisciplinedHashModel.applyDisciplinedHashModelOnPatch(patch, code, pointsTo)
                            ExtCallSummarization.annotateCallsAndReturnsWithAnalysisResultsWithPatching(patch, code, analysisResults, updatedHashCallCoreCmds)
                            patch.toCode(code)
                        },
                        // memory overlap fixer interferes with call resolution, so we postpone it
                        CoreToCoreTransformer(ReportTypes.MEMORY_OVERLAP_FIXER) {
                            if (Config.AttemptToFixMemoryOverlaps.get()) {
                                MemoryOverlapFixer.ensureConsistentReads(it)
                            } else {
                                it
                            }
                        }.lift()
                    )
                )
            )
        }

        // hashOptimize after disciplined hash model,
        // make return commands a little easier to parse, by using a return variable symbol instead of a buffer
        scene.mapContractMethodsInPlace(IScene.MapSort.PARALLEL, "simplify_returns") { theScene, method ->
            ContractUtils.transformMethodInPlace(
                method,
                ChainedMethodTransformers(
                    listOf(
                        CoreToCoreTransformer(
                            ReportTypes.RETURN_SYMBOL_OPTIMIZE1,
                            ::returnSymbol
                        ).lift(),
                        MethodToCoreTACTransformer(
                            ReportTypes.HASH_OPTIMIZE
                        ) { m -> hashRewrites(theScene, m) },
                        CoreToCoreTransformer(
                            ReportTypes.RETURN_SYMBOL_OPTIMIZE2,
                            ::materializeReturnSymbol
                        ).lift()
                    )
                )
            )
        }
        // an important optimization for using not one big memory array but many small ones
        scene.mapContractMethodsInPlace(IScene.MapSort.PARALLEL, "partition_memory") { _, method ->
            // won't work on constructors, skip
            if (method.attribute == MethodAttribute.Unique.Constructor) {
                return@mapContractMethodsInPlace
            }
            ContractUtils.transformMethodInPlace(
                method as TACMethod,
                ChainedMethodTransformers(
                    listOf(
                        MethodToCoreTACTransformer(
                            ReportTypes.PARTITION_MEMORY,
                            MemoryPartition::partition
                        )
                    )
                )
            )
        }

        // inlining libraries and delegatecalls
        scene.mapScene("inline_delegates") {
            Inliner.inlineDelegates(scene, isUnsummarized)
        }

        // deal with storage
        runStorageAnalyses(scene)

        // deal with constructors
        if (scene is IDynamicScene && Config.DynamicCreationBound.get() > 0) {
            ContractCreation.extendScene(scene, SceneConstructorOracle(scene))
        }
        scene.mapContractsInPlace("constructor_constraint") { s, contr ->
            contr.getConstructor()?.let {
                ImmutableInstrumenter.instrumentConstants(s, it)
            }
        }

        // inline non-delegatecalls using information retrieved from previous analyses
        scene.mapScene("inline_direct") {
            Inliner.inlineDirectCallsInScene(scene, isUnsummarized)
        }

        if(Config.EnableAggressiveABIOptimization.get()) {
            ABIOptimizations.run(scene, cvlQuery)
        }

        /*
          At this point, any code remaining in library functions are effectively "dead": they can only be called via delegatecalls,
          and after the delegateinlining above, remaining delegate calls *cannot* be inlined. Further,
          library code cannot be called directly from spec. In other words, any library code not already inlined into a
          regular contract code can't be inlined into any code that is reachable from spec. Thus, we can skip any
          optimizations or code transformations that run on library code.
         */
        // loop unrolling
        scene.mapContractMethodsInPlace(IScene.MapSort.PARALLEL,"unroll") { theScene, method ->
            val isLibrary = (method.getContainingContract() as? IContractWithSource)?.src?.isLibrary == true
            if(isLibrary) {
                return@mapContractMethodsInPlace
            }
            ContractUtils.transformMethodInPlace(
                method, ChainedMethodTransformers(
                    listOf(
                        // materialize revert annotations to actual TAC commands that update or restore the state
                        CoreToCoreTransformer(ReportTypes.REVERT_MATERIALIZATION) { prog: CoreTACProgram ->
                            Inliner.materializeRevertManagement(prog, theScene)
                        }.lift(),
                        CoreToCoreTransformer(ReportTypes.UNROLL, ContractUtils::unroll).lift(),
                        // adding branch snippets relies currently on having a topological order on the nodes,
                        // thus requiring it to run after loop unrolling
                        CoreToCoreTransformer(ReportTypes.ADD_BRANCH_SNIPPETS) { p ->
                            BranchSnippetInstrumenter.instrumentBranchSnippets(p)
                        }.lift()
                    )
                )
            )
        }

        // optimizations (mostly peephole but also path pruning, see tacOptimizations())
        scene.mapContractMethodsInPlace("initial_postInline") { _, method ->
            val isLibrary = (method.getContainingContract() as? IContractWithSource)?.src?.isLibrary == true
            if(isLibrary) {
                return@mapContractMethodsInPlace
            }
            ContractUtils.transformMethodInPlace(
                method,
                tacOptimizations()
            )
        }
    }

    private fun runStorageAnalyses(scene: IScene) {
        // add annotations about storage access paths and patterns
        // used for hooks resolution, splitting, and easier linking
        scene.mapContractsInPlace(IScene.MapSort.PARALLEL, "storage_analysis") { theScene, contract ->
            if (Config.EnableStorageAnalysis.get()) {
                contract.mapMethodsInPlace(theScene) { _, method ->
                    ContractUtils.transformMethodInPlace(
                        method = method as TACMethod,
                        transformers = ChainedMethodTransformers(
                            listOfNotNull(
                                MethodToCoreTACTransformer(
                                    ReportTypes.STORAGE_COPY_LOOP_SUMMARIZATION,
                                ) { StorageLoopSummarizer.annotateLoops(it.code as CoreTACProgram) },

                                MethodToCoreTACTransformer<TACMethod>(
                                    ReportTypes.STORAGE_LOOP_PEELER,
                                ) {
                                    val code = it.code as CoreTACProgram
                                    val heuristic = VyperPeelStorageHeuristic(code.analysisCache.graph)
                                    StorageLoopPeeler.peelStorageLoops(code, heuristic)
                                }.takeIf {
                                    Config.PeelVyperStorageLoops.get()
                                },

                                MethodToCoreTACTransformer(
                                    ReportTypes.STORAGE_EVAL_CODEDATA_SLOTS,
                                ) { StorageCodedataSlotAnnotator.annotateCodedataReads(
                                    bytecode = contract.bytecode,
                                    it.code as CoreTACProgram
                                )}
                            )
                        )
                    )
                }
                val res = StorageAnalysis.runAnalysis(contract)
                StoragePathAnnotation.annotateClass(contract, res)

                if (Config.EnabledAggressivePathPruning.get()) {
                    StoragePathPruner.prunePaths(contract, res)
                }

            }
        }

        if (Config.EnableStorageSplitting.get()) {
            scene.mapContractsInPlace(IScene.MapSort.PARALLEL, "storage_splitting") { _, contract ->
                StorageSplitter(contract).splitStorage()
            }
        }
        scene.mapContractsInPlace("storage_splitting") { _, contract ->
            StorageTypeBounder(contract).addBounds()
        }

        scene.mapContractMethodsInPlace("storage_slot_resolution") { _, method ->
            ContractUtils.transformMethodInPlace(
                method as TACMethod,
                ChainedMethodTransformers(
                    listOf(
                        MethodToCoreTACTransformer(
                            ReportTypes.STORAGE_ADDRESS_RESOLUTION
                        ) { m ->
                            ContractStorageResolver.resolve(m)
                        }
                    )
                )
            )
        }
        if (Config.AssumeDeadStorageIsZero.get()) {
            instrumentStorageAssumption(scene)
        }
    }

    private fun instrumentStorageAssumption(scene: IScene) {
        scene.mapContractsInPlace("read_tracking_instrumentation") { s: IScene, c: IContractClass ->
            if (c !is IMutableStorageInfo) {
                return@mapContractsInPlace
            }
            val storageMap = c.storage as StorageInfoWithReadTracker
            check(storageMap.storageVariables.all {
                it.value == null
            })
            val readTracking = storageMap.storageVariables.keys.associateWith {
                if (it.tag == Tag.WordMap) {
                    TACSymbol.Var(
                        namePrefix = "${it.namePrefix}!readTracker",
                        tag = Tag.WordMap,
                        meta = MetaMap(TACMeta.STORAGE_READ_TRACKER)
                    )
                } else {
                    null
                }
            }
            val storageReadInstrumenter = StorageReadInstrumenter(readTracking)
            c.mapMethodsInPlace(s) { _: IScene, m: ITACMethod ->
                ContractUtils.transformMethodInPlace(
                    m as TACMethod,
                    ChainedCoreTransformers(
                        listOf(
                            CoreToCoreTransformer(
                                ReportTypes.READ_TRACKING_INSTRUMENTATION,
                                storageReadInstrumenter::instrument
                            )
                        )
                    )
                )
            }
            c.setStorageInfo(StorageInfoWithReadTracker(readTracking))
        }
    }

    private fun updateStatusReporter(
        query: ProverQuery
    ) {
        (query as? ProverQuery.CVLQuery.Single)?.let { (_, cvl) ->
            val chosenRules = Config.getRuleChoices(cvl.rules.mapToSet { it.declarationId })
            cvl.rules.forEach { rule ->
                // if rule choice is enabled, count rule only if this is the chosen rule
                val shouldCountRule = rule.declarationId in chosenRules
                StatusReporter.register(rule, shouldCountRule)
            }
        }

        StatusReporter.freeze()
    }

    /**
     * If [Config.SceneConstructionOnly] is disabled, returns the check results ([RuleCheckResult]s) of each rule ([IRule]) derived from the given [query];
     * otherwise, as the [query] is not being checked, returns the empty list.
     */
    private suspend fun runWithScene(
        scene: IScene,
        query: ProverQuery,
        reporterContainer: ReporterContainer,
        extensionContractsMapping: ExtensionContractsMapping,
    ): List<RuleCheckResult> {
        val result = if (!Config.SceneConstructionOnly.get()) { // works thanks to logSceneInfo above which triggers the lazy computation
            // run initial transformations, before checking specs or assertions
            runInitialTransformations(scene, query, extensionContractsMapping)
            if(!Config.PreprocessOnly.get()) {

                when (query) {
                    is ProverQuery.AssertionQuery -> handleAssertions(
                        scene.fork(IScene.ForkInfo.ASSERTION),
                        query,
                        reporterContainer,
                    )

                    is ProverQuery.CVLQuery.Single -> handleCVLs(
                        scene.fork(IScene.ForkInfo.CVL),
                        query,
                        reporterContainer,
                        query.cvl.let {
                            TreeViewReporter(
                                it.getContractNameFromContractId(SolidityContract.Current),
                                it.name,
                                scene,
                            )
                        })
                }
            } else {
                Logger.always("Preprocess-only mode enabled", respectQuiet = false)
                emptyList()
            }
        } else {
            Logger.always("Scene construction-only mode enabled", respectQuiet = false)
            emptyList()
        }

        // report results
        if(!Config.SceneConstructionOnly.get() && !Config.PreprocessOnly.get()) {
            reporterContainer.toFile(scene)
        }
        return result
    }

    // sets up the status reporter, tree view reporter and reporter container
    private fun setupReporters(
        query: ProverQuery
    ): ReporterContainer {
        if (Config.BoundedModelChecking.getOrNull() == null) {
            // In BMC mode all rules are created on-the-fly, so we can't call this here because it "freezes" the
            // StatusReporter which prevents adding any new rules.
            updateStatusReporter(query)
        }

        val reporterContainer = ReporterContainer(
            listOf(
                JSONReporter(Config.OutputJSONFile),
                CoinbaseFeaturesReporter(Config.FeaturesJSONFile),
                ConsoleReporter,
                HTMLReporter,
                StatusReporter
            )
        )
        return reporterContainer
    }

    private fun logSceneInfo(scene: IScene) {
        Logger.always("The scene contains ${scene.getContracts().size} contracts:\n" +
            scene.getContracts()
                .map { "${it.name} : 0x${it.instanceId.toString(16)}" }
                .joinToString(",\n"),
            respectQuiet = false
        )
        scene.getContracts().forEach { c ->
            c.getDeclaredMethods().forEach { m ->
                val code = m.code
                if ((code as CoreTACProgram).hasExternalCalls()) {
                    logger.info {
                        "In contract ${c.name} method ${m.name} " +
                            "contains external calls: ${code.externalCallsSources()}"
                    }
                }
                if (code.hasInternalCalls() && Config.ShowInternalFunctions.get()) {
                    val internalStack = internalStackString(code)
                    if (internalStack != null) {
                        Logger.always(
                            "In contract ${c.name} method ${m.name} contains internal calls (order is not significant, summarizable: $SUMMARY_CHECK/$SUMMARY_X):$internalStack",
                            respectQuiet = true
                        )
                    }
                }
            }
        }
        // report on unsummarizable internal functions
        scene.getContracts()
            .mapNotNull { (it as? IContractWithSource)?.src }
            .forEach {
                FunctionFlowAnnotator.reportUnusmmarizableInternalFunctions(it)
            }

    }

    /**
     * Computes a string description illustrating the nesting of internal calls within a contract method
     * and the summarizability of said internal functions
     */
    private fun internalStackString(code: CoreTACProgram): String? {
        val callInfo = mutableMapOf<Int, InternalFuncStartAnnotation>()
        val callTree = mutableMapOf<Int, MutableSet<Int>>()
        val graph = code.analysisCache.graph
        val blk = mutableMapOf<NBId, List<Int>>().also { m ->
            graph.rootBlocks.forEach {
                m[it.id] = listOf()
            }
        }
        val rootCalls = mutableSetOf<Int>()
        val stackLogger = Logger(LoggerTypes.DECOMPILER)
        val res = object : VisitingWorklistIteration<NBId, Unit, Boolean>() {
            override fun process(it: NBId): StepResult<NBId, Unit, Boolean> {
                val stk = blk[it]?.toMutableList() ?: return this.halt(false).also {
                    stackLogger.warn {
                        "No state for block $it in ${code.name}"
                    }
                }
                for (lc in graph.elab(it).commands) {
                    val top = stk.lastOrNull()
                    if (lc.cmd !is TACCmd.Simple.AnnotationCmd) {
                        continue
                    }
                    if (lc.cmd.annot.k == INTERNAL_FUNC_START) {
                        val callStruct = (lc.cmd.annot.v as InternalFuncStartAnnotation)
                        if (callStruct.id in callInfo) {
                            stackLogger.warn {
                                "Duplicate call ${callStruct.id} (${callStruct.methodSignature}) found at ${lc.ptr} in ${code.name}"
                            }
                            return this.halt(false)
                        }
                        callInfo[callStruct.id] = callStruct
                        if (top != null) {
                            callTree.computeIfAbsent(top) {
                                mutableSetOf()
                            }.add(callStruct.id)
                        } else {
                            rootCalls.add(callStruct.id)
                        }
                        stk.add(callStruct.id)
                    } else if (lc.cmd.annot.k == INTERNAL_FUNC_EXIT) {
                        val exitAnnot = lc.cmd.annot.v as InternalFuncExitAnnotation
                        if (exitAnnot.id != top) {
                            stackLogger.warn {
                                "Mismatched stack: at ${lc.ptr} in ${code.name}, exit for ${exitAnnot.id} (${callInfo[exitAnnot.id]?.methodSignature})" +
                                    " but top of stack is $top (${top?.let(callInfo::get)?.methodSignature})"
                            }
                            return this.halt(false)
                        }
                        stk.removeLast()
                    }
                }
                val succ = mutableSetOf<NBId>()
                for (nxt in graph.succ(it)) {
                    if (nxt in blk) {
                        if (blk[nxt] != stk) {
                            if (nxt in graph.cache.revertBlocks) {
                                continue
                            }
                            stackLogger.warn {
                                "On path $it -> $nxt in ${code.name}, mismatched stack: $stk vs existing ${blk[nxt]}"
                            }
                            return this.halt(false)
                        }
                    } else {
                        blk[nxt] = stk.toList()
                        succ.add(nxt)
                    }
                }
                return this.cont(succ)
            }

            override fun reduce(results: List<Unit>): Boolean {
                return true
            }

        }.submit(graph.rootBlocks.map { it.id })
        if (!res) {
            return null
        }

        val seenIds = mutableSetOf<Int>()
        val buff = StringBuilder()

        class BrokenCallTreeException : RuntimeException()

        fun encodeTree(indent: String, id: Int) {
            if (!seenIds.add(id)) {
                stackLogger.warn {
                    "Call to $id found along multiple paths through call tree"
                }
                throw BrokenCallTreeException()
            }
            val callStruct = callInfo[id] ?: run {
                stackLogger.warn {
                    "Call id $id doesn't have a name computed"
                }
                throw BrokenCallTreeException()
            }
            val summary = if (callStruct.isSummarizable()) {
                SUMMARY_CHECK
            } else {
                SUMMARY_X
            }
            buff.append("\n").append(indent).append("+--> ${callStruct.methodSignature} $summary")
            val children = callTree[id] ?: return
            for (c in children) {
                encodeTree("$indent  ", c)
            }
        }
        return try {
            for (rootId in rootCalls) {
                encodeTree("", rootId)
            }
            buff.toString()
        } catch (_: BrokenCallTreeException) {
            null
        }
    }

}
