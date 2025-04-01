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

import annotations.PollutesGlobalState
import analysis.opt.intervals.IntervalsRewriter
import config.Config
import config.Config.EnableResplitting

import config.LocalSettings
import config.Config.UnderApproxStartDepth
import config.ReportTypes
import datastructures.EnumSet
import datastructures.nonEmptyListOf
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import log.*
import org.jetbrains.annotations.TestOnly
import report.*
import report.dumps.TACStatistics
import report.dumps.generateCodeMap
import report.dumps.generateUnsolvedSplitCodeMap
import rules.FindReachabilityFailureSource
import scene.ISceneIdentifiers
import smt.CoverageInfoEnum
import smt.solverscript.LExpressionFactory
import smt.solverscript.VcFeature
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.data.ProcessDifficulties
import smtlibutils.data.SatResult
import smtlibutils.statistics.LearnedClausesFilterStatistics
import smtlibutils.statistics.PreExecutionStatistics
import solver.ConfigStatistics
import solver.SMTCounterexampleModel
import solver.SolverResult
import spec.cvlast.*
import spec.genericrulegenerators.BuiltInRuleId
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import statistics.*
import statistics.data.*
import tac.DumpTime
import testing.TacPipelineDebuggers.oneStateInvariant
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.redBg
import utils.Color.Companion.yellow
import vc.data.*
import vc.gen.LExpVC
import vc.gen.LeinoWP
import verifier.ContractUtils.recordSingleTransformationStats
import verifier.LExpVCStatsLogger.Companion.resultToRaceStatistics
import verifier.autoconfig.AutoConfigManager
import verifier.mus.UnsatCoreAnalysis
import verifier.mus.UnsatCoreInputData
import verifier.parallel.SplitManager
import verifier.parallel.verifierResultFromCheckerResult
import verifier.splits.*
import verifier.splits.SplitAddress.Block
import java.io.PrintStream
import java.lang.Integer.max
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private val logger = Logger(LoggerTypes.SOLVERS)
private val loggerTimes = Logger(LoggerTypes.TIMES)
private val splitLogger = Logger(LoggerTypes.SPLIT)
private val autoconfigLogger = Logger(LoggerTypes.AUTOCONFIG)

private val warningForParallelAndCoverageEmitted = AtomicBoolean(false)

class TACVerifier private constructor(
    val scene: ISceneIdentifiers,
    val name: NameObject,
    private val rule: IRule,
) : Verifier() {
    init {
        ArtifactManagerFactory().registerArtifact(Config.OutputJSONFile)
    }

    private val splitsConfigManager: AutoConfigManager = AutoConfigManager(name.baseName)

    val statistics = SMTStatsHolder(SMTStatistics(name))
    private val tacVerifierConfig = TACVerifierConfig()     //loads the autoconfig file if there is one.
    private var difficultyStatsCollector: DifficultyStatsCollector? = null
    private var liveStatsReporter: LiveStatsReporter? = null

    /** Variables logging splitting progress for our progress displays.
     * (Those could live inside [leinoVCWithSplitting] except for our interest in reporting them with
     * [LiveStatsProgressInfo.Finished], which is created in a different scope. Seems ok to me for them to live here,
     * since they're tied to a leaf-rule and a [TACVerifier] is, too.) */
    private var numProblemsAttempted = 0
    private var totalWeight = 0.0

    private fun nameToSolveSummaryKey(name: NameObject): SDFeatureKey = "${name.baseName}_solving_summary".toSDFeatureKey()

    private val isCMDLineSanityRule = when ((rule as? CVLSingleRule)?.ruleGenerationMeta?.sanity) {
        SingleRuleGenerationMeta.Sanity.PRE_SANITY_CHECK,
        SingleRuleGenerationMeta.Sanity.BASIC_SANITY -> true

        SingleRuleGenerationMeta.Sanity.DISABLED_SANITY_CHECK,
        SingleRuleGenerationMeta.Sanity.DONE,
        null -> false
    }
    private val isBuiltinSanityRule = when ((rule.ruleType as? SpecType.Single.BuiltIn)?.birId) {
        BuiltInRuleId.sanity, BuiltInRuleId.deepSanity -> true
        else -> false
    }
    private val rootTacProgramMetadata = run {
        val block = (rule as? CVLSingleRule)?.block
        TACProgramMetadata(
            RuleAndSplitIdentifier.root(rule.ruleIdentifier),
            findReachabilityFailureSourceShouldCheckRule = FindReachabilityFailureSource.shouldCheck(rule),
            cvlVarsInAsserts = (block ?: emptyList()).asSequence()
                .mapNotNull { it as? CVLCmd.Simple.Assert }
                .flatMap { it.exp.getVarsExp() }
                .map { it.id }.toList(),
            cvlVars = block
                ?.flatMap {
                    CVLExpDeclaredSymbolsCollector.getDeclaredSymbols(it)
                        .map { declSym -> declSym.first }
                }.orEmpty(),
            isSanityRule = isCMDLineSanityRule || isBuiltinSanityRule,
        )
    }


    private fun logNumProblemsSolved(numProblemsSolved: Int) {
        statistics.smt = statistics.smt.copy(totalSplits = numProblemsSolved)
        val tacProgramSummaryKey = nameToSolveSummaryKey(statistics.smt.name)

        SDCollectorFactory.collector().collectFeature(
            ScalarKeyValueStats<Int>(tacProgramSummaryKey).addKeyValue("#splits", numProblemsSolved)
        )
    }

    private fun logSplitAndSolveTime(solverStopWatch: MilliTimeElapserStopWatch) {
        val tacProgramSummaryKey = nameToSolveSummaryKey(statistics.smt.name)
        val smtGenSolveTimeRecorder =
            solverStopWatch.timeRecordsOf(tacProgramSummaryKey, "split+solve time".toSDFeatureKey())

        val totalSmtSolvingTime = smtGenSolveTimeRecorder.describeData().values.sum().toInt()
        statistics.smt = statistics.smt.copy(
            totalSmtSolvingTime = totalSmtSolvingTime.milliseconds,
        )

        SDCollectorFactory.collector().collectFeature(smtGenSolveTimeRecorder)
    }

    private fun updateSplitStatistics(splitStats: SplitStatistic) {
        statistics.smt = statistics.smt.copy(splits = statistics.smt.splits + splitStats)
    }

    private fun updateResplitStatistics(
        splitName: String,
        address: SplitAddress,
        resplitTime: Duration,
    ) {
        updateSplitStatistics(
            SplitStatistic(
                address = address.asIntList,
                vcFeatures = listOf(),
                solvingEvent = ResplitStatistic(resplitTime),
                timeToSolveSinceJarStart = TimeSinceStart(),
                splitName = splitName,
            )
        )
    }

    private fun updateSolvedSplitStatistics(
        vcName: String,
        address: SplitAddress,
        timeout: Duration,
        smtSolvingWallTime: Duration,
        vcGenerationTime: Duration,
        checkerResult: SmtFormulaCheckerResult,
        usedFeatures: EnumSet<VcFeature>,
        splitAddressLearnedClausesStats: LearnedClausesFilterStatistics,
        prettifierStatistics: PrettifierStatistics?,
        diversifierStatistics: DiversifierStatistics?,
    ) {
        val races = resultToRaceStatistics(checkerResult) ?: listOf()

        val splitStats = SplitStatistic(
            splitName = vcName,
            address = address.asIntList,
            finalResult = LExpVCStatsLogger.getResultStr(checkerResult.satResult),
            timeout = timeout,
            smtSolvingWallTime = smtSolvingWallTime,
            vcGenerationTime = vcGenerationTime,
            vcFeatures = usedFeatures.map { it.toString() },
            prettifierStatistics = prettifierStatistics,
            diversifierStatistics = diversifierStatistics,
            solvingEvent = RaceListStatistic(
                races = races,
                numAllLearnedClausesOutput = splitAddressLearnedClausesStats.numAllLearnedClauses,
                numUsableLearnedClausesOutput = splitAddressLearnedClausesStats.numUsableLearnedClauses
            ),
            timeToSolveSinceJarStart = TimeSinceStart()
        )
        updateSplitStatistics(splitStats)
    }

    override suspend fun runVerifier(tacObject: CoreTACProgram, liveStatsReporter: LiveStatsReporter): VerifierResult {
        this.liveStatsReporter = liveStatsReporter

        difficultyStatsCollector = DifficultyStatsCollector(
            name = name,
            ruleId = rule.ruleIdentifier,
            codeMap = generateCodeMap(tacObject, tacObject.name),
            collector = SDCollectorFactory.collector(),
            liveStatsReporter = liveStatsReporter
        )

        val start = System.currentTimeMillis()
        val vRes =
            // interestingly, null.use { <block> } does execute <block>, which is what we want here
            difficultyStatsCollector.use {
                SMTStatsResource(statistics, SDCollectorFactory.collector()).use {
                    checkVCOnPresimplified(tacObject)
                }
            }
        val end = System.currentTimeMillis()


        liveStatsReporter.reportProgress(
            rule.ruleIdentifier,
            LiveStatsProgressInfo.Finished(
                rootTacProgramMetadata.ruleAndSplitId,
                numProblemsAttempted,
                totalWeight,
                isTimeout = vRes.finalResult == SolverResult.TIMEOUT,
            )
        )

        loggerTimes.info { "Time for all contracts, processing and verification: ${(end - start) / 1000} seconds" }
        return vRes
    }


    override suspend fun runVerifierOnFile(fileName: String) {
        runVerifierOnFileWithSpecificHandler(fileName, ::checkVCOnPresimplified)
    }

    /**
     * Runs [handler] (a verifier) on a TAC object loaded from [fileName]. At the moment of writing this comment,
     * the [handler] is either [handlePresolver] or [checkVCOnPresimplified].
     * Furthermore, in case of UNSAT or TIMEOUT results, the function dumps either unsolved splits or unsat cores
     * codemaps & related files.
     * Note that in the case of the standard execution on .sol and .spec files via [runVerifier], the unsat cores and
     * unsolved splits data (and many more) are back-propagated in a [Verifier.VerifierResult] object to the rule checker
     * and processed & dumped there. Here, we have no rule, so the workflow is not so involved.
     */
    suspend fun runVerifierOnFileWithSpecificHandler(fileName: String, handler: suspend (CoreTACProgram) -> VerifierResult) {
        val start = System.currentTimeMillis()

        val tacObject = loadTACFromFile(fileName)
        val vRes = SMTStatsResource(statistics, SDCollectorFactory.collector()).use { handler(tacObject) }

        ArtifactManagerFactory().useArtifact(Config.OutputJSONFile.get()) { outputPath ->
            ArtifactFileUtils.getWriterForFile(outputPath, overwrite = true).use { writer ->
                writer.write("{\n\t\"tacResult\": \"${vRes.finalResult.toJSONRepresentation()}\"\n}")
            }
        }
        val end = System.currentTimeMillis()
        loggerTimes.info { "Time for all contracts, processing and verification: ${(end - start) / 1000} seconds" }

        if (vRes.unsolvedSplitsInfo != null) {
            HTMLReporter.dumpCodeMapToHTMLReport(
                generateUnsolvedSplitCodeMap(vRes.unsolvedSplitsInfo),
                ReportTypes.REPORT
            )
        } else if (vRes.unsatCoreSplitsData != null) {
            UnsatCoreAnalysis(vRes.unsatCoreSplitsData, tacObject).dumpToJsonAndRenderCodemaps()
        }
    }

    private suspend fun checkVCOnPresimplified(tacObject: CoreTACProgram): VerifierResult {
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            tacObject,
            ReportTypes.PRESIMPLIFIED_RULE,
            StaticArtifactLocation.Outputs,
            DumpTime.AGNOSTIC
        )
        oneStateInvariant(tacObject, ReportTypes.PRESIMPLIFIED_RULE)

        // returns a Presolver tac
        val finalTac = toSimpleSimpleSSAFoldAndSkeyAnnotate(
            tacObject = tacObject,
            enableHeuristicalFolding = Config.CoverageInfoMode.get() != CoverageInfoEnum.ADVANCED
        ).letIf(
            Config.CoverageInfoMode.get() != CoverageInfoEnum.ADVANCED && Config.intervalsRewriter.get() > 0
        ) {
            IntervalsRewriter.rewrite(
                it,
                repeat = Config.LastIntervalsRewriter.get(),
                handleLeinoVars = true
            )
        }

        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            finalTac,
            ReportTypes.PRESOLVER_RULE,
            StaticArtifactLocation.Outputs,
            DumpTime.AGNOSTIC
        )
        oneStateInvariant(finalTac, ReportTypes.PRESOLVER_RULE)

        return handlePresolver(finalTac)
    }

    suspend fun handlePresolver(finalTac: CoreTACProgram): VerifierResult {
        fun anyAssertCmds(tacToLeinoVCWithSplitting: CoreTACProgram): Boolean =
            tacToLeinoVCWithSplitting.analysisCache.graph.commands.any {
                it.cmd is TACCmd.Simple.AssertCmd
            }

        return if (anyAssertCmds(finalTac)) {
            /**
             * When closed, the config manager dumps statistics about processed splits. We use the close mechanism
             * to also allow proper closing/dumping in case of exceptions (mainly due to the overall prover timelimit).
             */
            statistics.smt = statistics.smt.copy(startTime = TimeSinceStart())
            val res = splitsConfigManager.use {
                @OptIn(PollutesGlobalState::class) // Safe because Config.TimeoutCracker is protected by @PollutesGlobalState
                suspend fun maybeTimeoutCracker() = runIf(Config.TimeoutCracker.get()) {
                    TimeoutCracker.solve(finalTac) { tacProgram, localSettings ->
                        leinoVCWithSplitting(tacProgram, localSettings) }
                }
                maybeTimeoutCracker() ?: leinoVCWithSplitting(finalTac)
            }
            statistics.smt = statistics.smt.copy(finishTime = TimeSinceStart())
            res
        } else {
            VerifierResult.noAssertsInVc(
                tac = finalTac,
                model = SMTCounterexampleModel.Empty,
                elapsedTimeSeconds = 0,
                smtFormulaCheckerResult = SmtFormulaCheckerResult.Single.Other(
                    SatResult.UNSAT,
                    preExecutionStatistics = PreExecutionStatistics(
                        ConfigStatistics("trivial-unsat-in-TAVCerifier"),
                        listOf()
                    ),
                    resultStats = null
                ),
                finalTac.analysisCache.graph,
            )
        }
    }

    private fun VerifierResult.withUnsatCoreDataIfRequested(data: Map<SplitAddress, UnsatCoreInputData>) =
        if (Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE
            && (this.finalResult == SolverResult.UNSAT
                || (this.finalResult != SolverResult.SAT && Config.CoverageInfoForTimeouts.get())
                )
        ) {
            this.copy(unsatCoreSplitsData = data)
        } else {
            this
        }

    private suspend fun leinoVCWithSplitting(
        tacProgram: CoreTACProgram,
        localSettings: LocalSettings = LocalSettings()
    ): VerifierResult {
        reportRuleDifficultyStats()

        if (localSettings.parallelSplitting) {
            if (!warningForParallelAndCoverageEmitted.getAndSet(true) && Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE) {
                CVTAlertReporter.reportAlert(
                    CVTAlertType.GENERAL,
                    CVTAlertSeverity.WARNING,
                    rule.range as? Range.Range,
                    "Coverage information (e.g. for unsat cores) is not supported with -${Config.ParallelSplitting.name} yet.",
                    null
                )
            }
            if (Config.Smt.UseBV.get()) {
                logger.warn { "Parallel Splitter does not support bitvector theory." }
            } else {
                val sm = SplitManager(scene, tacProgram, rootTacProgramMetadata, rule, localSettings, splitsConfigManager)
                try {
                    return sm.solve().first()
                } catch (e: NoSuchElementException) {
                    return VerifierResult(
                        name = tacProgram.name,
                        tac = tacProgram,
                        elapsedTimeSeconds = 0,
                        reachabilityIndicator = null,
                        difficultyInfo = null,
                        examplesInfo = nonEmptyListOf(
                            ExampleInfo(
                                solverResult = SolverResult.UNKNOWN,
                                errorMessage = "parallel splitter failed to produce a result",
                                reason = e.message,
                            )
                        ),
                        unsatCoreSplitsData = null,
                        unsolvedSplitsInfo = null,
                        collapsedTACGraph = tacProgram.analysisCache.graph,
                        unsolvedSplitsPrograms = null
                    )
                }
            }
        }

        if (Config.OldParallelSplitting.get()) {
            if (Config.Smt.UseBV.get()) {
                logger.warn {
                    "Old parallel Splitter (-oldSplitParallel) does not support bitvector theory. " +
                        "Running default solving approach instead."
                }
            } else {
                ParallelSplitter(tacProgram, rootTacProgramMetadata, rule,
                    localSettings = localSettings, splitsConfigManager).let { splitter ->
                    splitter.solve()
                    val splitterResult = splitter.getResult()
                    return verifierResultFromCheckerResult(
                        scene = scene,
                        tacProgramToVerify = tacProgram,
                        tacProgramMetadata = rootTacProgramMetadata,
                        postprocessor = splitterResult.postprocessor,
                        results = splitterResult.results,
                        elapsedTime = splitterResult.elapsedTimeSeconds.seconds,
                        difficultyInfo = null,
                        /** This graph is currently not used - parallel splitting does not support unsat core enumeration. */
                        collapsedTacGraph = tacProgram.analysisCache.graph
                    ).withUnsatCoreDataIfRequested(splitter.getUnsatCoreSplitsData())
                }
            }
        }


        val startSplitTime = System.currentTimeMillis()

        val splittingTimeTag = "Splitting (ms)".toTimeTag()
        val resplittingTimeTag = "Resplitting (ms)".toTimeTag()
        val vcGenTimeTag = "Generating VC (ms)".toTimeTag()
        val solverTimeTag = "SMT solving (ms)".toTimeTag()

        val timeStatsRecorder = ElapsedTimeStats()
        val splitDurationTime = (System.currentTimeMillis() - startSplitTime) / 1000

        /** max depth of any split we have attempted (for this rule) */
        var reachedSplitDepth = 0

        var solveTime = 0L
        var gaveUpOnAnyBranch = false
        var overAllResult: VerifierResult? = null

        //Collect total split time
        recordSingleTransformationStats(timeStatsRecorder, tacProgram.name)

        val unsatCoreSplitsData = mutableMapOf<SplitAddress, UnsatCoreInputData>()
        val unsolvedSplitData = UnsolvedSplitData()
        val solverStopWatch = MilliTimeElapserStopWatch(timeStatsRecorder, solverTimeTag)

        val currentSplitVars = mutableSetOf<TACSymbol.Var>()
        val splitTree = SplitTree(tacProgram, localSettings.depth) { node ->
            if (UnderApproxStartDepth.get() < 0) {
                PivotSplitter(node)
            } else {
                if (node.address.depth < UnderApproxStartDepth.get()) {
                    CascadingSplitter(
                        { PivotSplitter(node) },
                        { NonLinearSplitter(node, currentSplitVars) },
                        { DeciderSplitter(node, currentSplitVars) }
                    )
                } else {
                    CascadingSplitter(
                        { NonLinearSplitter(node, currentSplitVars) },
                        { DeciderSplitter(node, currentSplitVars) }
                    )
                }
            }
        }

        val problemQueue = PriorityQueue(splitComparator).apply {
            add(splitTree.rootNode)
        }

        /**
         * If a problem was really easy to prove UNSAT, then when it comes time to verify the sibling problem,
         * immediately resplit instead, avoiding the solver at all.
         */
        fun shouldImmediatelyResplit(split: SplitTree.Node, autoConfig: TACVerifierConfig.TACVerifierSettings?) =
            split.splittable && (
                (!split.isRoot && split.siblings().all { it.address is Block && it.wasEasilySolved } && EnableResplitting.get()) ||
                    tacVerifierConfig.shouldResplit(split.name) ||
                    autoConfig?.resplit == true ||
                    split.depth < localSettings.initialSplitDepth
                )

        while (problemQueue.isNotEmpty()) {
            val subProblem = problemQueue.poll()
            val subProblemTAC = subProblem.generate(splitsConfigManager)

            // update split stats
            numProblemsAttempted++
            reachedSplitDepth = max(reachedSplitDepth,  subProblem.depth)

            if (numProblemsAttempted % 10 == 0) {
                Logger.always(
                    "Attempted $numProblemsAttempted splits, and proved a total weighing ${totalWeight * 100}% " +
                        "for ${tacProgram.name}",
                    respectQuiet = true
                )
            }

            liveStatsReporter?.reportProgress(
                rule.ruleIdentifier,
                LiveStatsProgressInfo.SplittingInProgress(
                    rootTacProgramMetadata.ruleAndSplitId,
                    numProblemsAttempted,
                    totalWeight,
                    reachedSplitDepth
                )
            )
            val autoConfig = splitsConfigManager.getConfig(subProblem, subProblemTAC)
            if (autoConfig?.knownToBeUnsat == true) {
                totalWeight += subProblem.weight
                autoconfigLogger.info { "AUTOCONFIG Skipping: ${subProblem.name}" }
                if (overAllResult == null) {
                    overAllResult = VerifierResult.dummyUnsat(subProblemTAC)
                }
                continue
            }

            if (shouldImmediatelyResplit(subProblem, autoConfig)) {
                // this both records the time with the `resplittingTimeTag` and returns the time
                val time = timeStatsRecorder.measure(resplittingTimeTag) {
                    measureTimedValue {
                        problemQueue += subProblem.split(subProblemTAC)
                    }
                }

                splitLogger.info {
                    "Immediately resplit $subProblem in ${time.duration}"
                }

                SDCollectorFactory.collector().collectFeature(
                    timeStatsRecorder.takeLastTimeRecords(1, subProblem.name.toSDFeatureKey()),
                )

                // Log resplit to structured logs
                updateResplitStatistics(
                    splitName = subProblem.name,
                    address = subProblem.address,
                    resplitTime = time.duration,
                )
                continue
            }

            ArtifactManagerFactory().dumpCodeArtifacts(subProblemTAC, DumpTime.AGNOSTIC)
            TACStatistics(subProblemTAC).reportToStats(nameToSolveSummaryKey(name))

            /**
             * This encodes the timeout logic for splits. Highly heuristic, and could probably use some more thought.
             */
            val timeout = with(subProblem) {
                when {
                    !splittable -> Config.getSolverTimeout()
                    isRoot -> Config.LowSplitTimeout.get().seconds
                    address is Block -> {
                        val removed = sideScore.blocks
                        val siblingRemoved = siblings().single().sideScore.blocks
                        when {
                            removed == 1 ->
                                Config.TinySplitTimeout.get().seconds

                            removed < 5 && siblingRemoved < 5 || removed > 5 && siblingRemoved > 5 ->
                                Config.MediumSplitTimeout.get().seconds

                            removed < 100 ->
                                Config.LowSplitTimeout.get().seconds

                            else ->
                                Config.MediumSplitTimeout.get().seconds
                        }
                    }

                    else -> Config.LowSplitTimeout.get().seconds
                }
            }

            splitLogger.info {
                val g = subProblemTAC.analysisCache.graph
                val blocks = g.blocks.size
                "Solving ${subProblem.address.fullInfo()} with timeout=${timeout.yellow}, blocks=${blocks.blue}, "
            }

            val (verifierResult, smtFormulaCheckerResult) = verifySingleSmtVc(
                subProblem.lazySub,
                subProblemTAC,
                timeout,
                vcGenStopWatch = MilliTimeElapserStopWatch(timeStatsRecorder, vcGenTimeTag),
                solverStopWatch,
                autoConfig,
                localSettings
            )
            val finalResult = verifierResult.finalResult
            subProblem.setRunInfo(finalResult, verifierResult.elapsedTimeSeconds.seconds, timeout)

            splitLogger.info {
                "Solved $subProblem in time ${verifierResult.elapsedTimeSeconds} with result $finalResult"
                    .letIf(!subProblem.splittable) { it + " Unsplittable".redBg}
            }

            if (finalResult == SolverResult.UNSAT && Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE) {
                val splitAddress = subProblem.address
                val query = when (smtFormulaCheckerResult) {
                    is SmtFormulaCheckerResult.Single -> smtFormulaCheckerResult.query
                    is SmtFormulaCheckerResult.Agg -> smtFormulaCheckerResult.representativeResult.query
                }
                check(query != null) { "Got a null query for $splitAddress" }
                val solverConfig = smtFormulaCheckerResult.solverInstanceInfo?.solverConfig
                check(solverConfig != null) { "Got a null solver config for $splitAddress" }

                val solvingTime = smtFormulaCheckerResult.representativeResult.resultStats?.solverWallRuntime

                unsatCoreSplitsData[splitAddress] = UnsatCoreInputData(
                    tacCommandGraph = verifierResult.collapsedTACGraph,
                    tac = verifierResult.tac,
                    query = query,
                    solverConfig = solverConfig,
                    originalCheckDuration = solvingTime
                )
            }

            if (finalResult == SolverResult.UNSAT) {
                totalWeight += subProblem.weight
            }

            solveTime += verifierResult.elapsedTimeSeconds

            // Collect the time stats of this iteration
            SDCollectorFactory.collector().collectFeature(
                timeStatsRecorder.takeLastTimeRecords(2, subProblem.name.toSDFeatureKey()),
            )

            if (finalResult == SolverResult.TIMEOUT || finalResult == SolverResult.UNKNOWN) {
                if (subProblem.splittable) {
                    timeStatsRecorder.measure(splittingTimeTag) {
                        problemQueue += subProblem.split(subProblemTAC)
                    }
                    continue
                } else {
                    // we dump timeouting leaves.
                    smtFormulaCheckerResult.let { last ->
                        logger.info { "Timed out on leaf ${subProblem.name}; dumping smt and tac files." }
                        SmtFileDumping.dumpFormulaAndOrProgram("splitLeafTimeout", last, subProblemTAC)
                    }
                    unsolvedSplitData.registerFullTimeout(subProblemTAC, smtFormulaCheckerResult)

                    if (localSettings.dontStopAtFirstSplitTimeout && problemQueue.isNotEmpty()) {
                        splitLogger.warn {
                            "Cannot split any more, but solve still times out. Giving up on this branch."
                        }
                        gaveUpOnAnyBranch = true
                        continue
                    } else {
                        // if we stop at first split timeout, OR
                        // if we completed our queue of problems to solve, just return the last
                        // unknown/timeout and break
                        unsolvedSplitData.registerNotYetSolved(problemQueue.map { it.lazySub })
                        overAllResult = verifierResult
                        break
                    }
                }
            }

            if (finalResult == SolverResult.SAT) {
                overAllResult = verifierResult
                break
            }

            if (problemQueue.isEmpty()) {
                overAllResult = verifierResult
            }
        }
        check(overAllResult != null) {
            "should not happen! forgot to set overallResult in some branch?"
        }

        // Collect the sum of solver time stats
        logSplitAndSolveTime(solverStopWatch)

        val underApprox = splitTree.nodeSequence.map { it.address }.any {
            it is SplitAddress.Assumption && it.assumption.isUnderApprox
        }

        if (overAllResult.finalResult == SolverResult.UNSAT && (gaveUpOnAnyBranch || underApprox)) {
            overAllResult = overAllResult.copy(
                examplesInfo = overAllResult.examplesInfo.map {
                    val reason = when {
                        gaveUpOnAnyBranch && underApprox -> "but some branches timeouted, and some were under-approximated."
                        gaveUpOnAnyBranch -> "but gave up on some branches due to timeouts."
                        else -> "but some branches were under-approximated."
                    }
                    it.copy(
                        reason = "Did not find any bugs, $reason",
                        solverResult = SolverResult.UNKNOWN
                    )
                }
            )
        }

        val endSplitTime = System.currentTimeMillis()
        val totalTime = (endSplitTime - startSplitTime) / 1000

        logNumProblemsSolved(numProblemsAttempted)

        log(respectQuiet = true) {
            "Total time for ${name.baseName}: $totalTime seconds ; Split time: $splitDurationTime seconds ;" +
                " Number of problems: $numProblemsAttempted ; Solver time : $solveTime seconds"
        }

        /** Add data necessary to compute the unsat cores if enabled **/
        overAllResult = overAllResult.withUnsatCoreDataIfRequested(unsatCoreSplitsData)

        /** Compute and visualise which parts of the cfg we have not proven correct (due to splits timing out). **/
        if (unsolvedSplitData.fullTimeOutBlocks.isNotEmpty()) {
            overAllResult = overAllResult.copy(
                unsolvedSplitsInfo = unsolvedSplitData.toUnsolvedSplitInfo(tacProgram, overAllResult.finalResult)
            )
            overAllResult = overAllResult.copy(
                unsolvedSplitsPrograms = unsolvedSplitData.unsolvedSplits.map { it.prog }.toSet()
            )
        }

        return overAllResult.copy(
            elapsedTimeSeconds = totalTime,
            tac = tacProgram, // not entirely happy with this -- we should make clearer which tac program is which some time
        )
    }

    private fun reportRuleDifficultyStats() {
        // it's important to pick the tac program that's annotated with internal functions here, since this affects all
        // the default-visible live difficulty stats; the per-split stats with depth > 0 don't get the internal-function
        // treatment right now
        val withInternalFunctions =
            difficultyStatsCollector!!.codeMap.withInternalFunctions as? CoreTACProgram ?: run {
                logger.warn { "failed to return per-rule difficulty stats, due to cast to CoreTACProgram failing (very surprising)" }
                return
            }
        reportSplitDifficultyStats(SplitAddress.Root, withInternalFunctions)
    }

    private fun reportSplitDifficultyStats(subProblemSplitAddress: SplitAddress, subProblemTAC: CoreTACProgram) {
        if (difficultyStatsCollector == null) {
            /** typically this means that [rule] is null, makes no sense to compute difficulty stats then */
            return
        }
        if (subProblemSplitAddress == SplitAddress.Root || Config.TreeViewLiveStatsPerSplit.get()) {
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                val pathCountStats = PathCountStats.computeAndLogTime(
                    difficultyStatsCollector!!.codeMap,
                    computeProcedureHotspots = subProblemSplitAddress == SplitAddress.Root
                )
                val nonLinearStats = NonLinearStats.computeAndLogTime(
                    subProblemTAC,
                    difficultyStatsCollector!!.codeMap.callIdNames,
                    computeProcedureHotspots = subProblemSplitAddress == SplitAddress.Root
                )
                val loopStats = LoopStats.compute(subProblemTAC, difficultyStatsCollector!!.codeMap.callIdNames)
                val stats = SingleDifficultyStats.TacProgramStats(
                    ruleAndSplitId =
                    // the only purpose of this `if` is to not create a lot of identical copies of [RuleAndSplitIdentifier]
                    if (subProblemSplitAddress != SplitAddress.Root) {
                        rootTacProgramMetadata.ruleAndSplitId.copy(splitAddress = subProblemSplitAddress)
                    } else {
                        rootTacProgramMetadata.ruleAndSplitId
                    },
                    pathCountStats = pathCountStats,
                    nonLinearStats = nonLinearStats,
                    loopStats = loopStats
                )
                difficultyStatsCollector!!.register(stats)
            } catch (e: Exception) {
                logger.warn(e) {
                    "got exception when trying to report difficulty stats for program " +
                        "\"${subProblemTAC.name}\", with split address \"$subProblemSplitAddress\", "
                }
            }
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun verifySingleSmtVc(
        subProblem: LazySubProgram,
        tacProgramToVerify: CoreTACProgram,
        timeout: Duration,
        vcGenStopWatch: MilliTimeElapserStopWatch,
        solverStopWatch: MilliTimeElapserStopWatch,
        autoConfig: TACVerifierConfig.TACVerifierSettings?,
        localSettings: LocalSettings
    ): Pair<VerifierResult, SmtFormulaCheckerResult> {

        val lExpressionFactory = LExpressionFactory()

        val vcGenerationResult = measureTimedValue {
            val (collapsed, postprocessor) = UniqueSuccessorRemover.removeUniqueSuccessors(tacProgramToVerify)
            val leinoWP = LeinoWP(
                collapsed,
                lExpressionFactory,
                rootTacProgramMetadata.updateSplitAddress(subProblem.address),
            )
            val vc: LExpVC = leinoWP.generateVCs()
            vc to postprocessor
        }

        val vcGenerationTime = vcGenerationResult.duration
        val (vc, postprocessor) = vcGenerationResult.value

        var prettifierStatistics: PrettifierStatistics? = null
        var diversifierStatistics: DiversifierStatistics? = null
        val solvingResult = measureTimedValue {
            /** name of the rule, including split identifier */
            val vcName = subProblem.name

            val vcCheckerConfig = LExpVcCheckerConfig.fromLocalSettings(
                vcName,
                lExpressionFactory.getUsedFeatures(),
                vc.tacProgram.containsStorageComparisons,
                timeout,
                localSettings,
                autoConfig ?: tacVerifierConfig.getAdaptiveSettingsForVc(vcName)
            )
            val checker = LExpVcChecker(
                timeout,
                vc,
                listOf(),
                lExpressionFactory,
                vcGenStopWatch,
                solverStopWatch,
                { prefix -> ArtifactManagerFactory().getFilePathForSmtQuery(prefix, subdir = null) },
                vcCheckerConfig,
                diffStats = difficultyStatsCollector
            )

            val (originalCheckerResult, prettifiedResults, prettifierStats, diversifierStats) = checker.runSolvers()
            checker.registerStats()
            logger.debug { "Original result: $originalCheckerResult (mostly to avoid warning :-) )" }
            val checkerResults =
                prettifiedResults.toList().toNonEmptyList()
                    ?: error("multi [SmtFormulaCheckerResult] should have at least one result")
            if (checkerResults.size > 1) {
                check(checkerResults.all { it is SmtFormulaCheckerResult.Single && it.satResult == SatResult.SAT })
                { "when multi [SmtFormulaCheckerResult] are produced, all of them are expected to be SAT" }
            }
            if (prettifierStats.isCompleted) {
                prettifierStatistics = prettifierStats.getCompleted()
            } else {
                logger.info { "Prettification did not produce statistics." }
            }
            if (diversifierStats.isCompleted) {
                diversifierStatistics = diversifierStats.getCompleted()
            } else {
                logger.info { "Diversification did not produce statistics." }
            }
            Triple(checkerResults, originalCheckerResult, checker.getLearnedClausesStats())
        }
        val (checkerResults, originalCheckerResult, learnedClausesStats) = solvingResult.value
        val firstCheckerResult = checkerResults.first()

        reportSolverExceptionsAndDumpInterestingSmtFiles(firstCheckerResult)

        updateSolvedSplitStatistics(
            vcName = subProblem.name,
            address = subProblem.address,
            timeout = timeout,
            smtSolvingWallTime = solvingResult.duration,
            vcGenerationTime = vcGenerationTime,
            checkerResult = originalCheckerResult.result,
            usedFeatures = lExpressionFactory.getUsedFeatures(),
            splitAddressLearnedClausesStats = learnedClausesStats,
            prettifierStatistics = prettifierStatistics,
            diversifierStatistics = diversifierStatistics
        )

        reportSplitDifficultyStats(subProblem.address, tacProgramToVerify)

        val processedDiffculties = ProcessDifficulties.processSmtFormulaCheckerResult(firstCheckerResult)
        if (processedDiffculties != null) {
            Logger.regression { "Got difficulties from solver." }
        }

        val verifierResult = verifierResultFromCheckerResult(
            scene,
            tacProgramToVerify,
            rootTacProgramMetadata,
            postprocessor,
            checkerResults,
            solvingResult.duration,
            processedDiffculties,
            vc.vcTacCommandGraph
        )

        splitsConfigManager.registerSplitResult(
            subProblem.address,
            verifierResult,
            originalCheckerResult.result,
            timeout,
            solvingResult.duration
        )

        return Pair(verifierResult, firstCheckerResult)
    }


    /** Log the exceptions that the solvers threw to some artifact for offline consumption.
     * Recurses through aggregate results and checks single results for whether they need dumping or reporting. */
    private fun reportSolverExceptionsAndDumpInterestingSmtFiles(res: SmtFormulaCheckerResult) {
        fun dumpSingleIfNeeded(res: SmtFormulaCheckerResult.Single) {
            when (val satRes = res.satResult) {
                SatResult.SAT,
                SatResult.UNSAT -> Unit

                is SatResult.UNKNOWN -> when {
                    satRes.reason.isTimeout -> Unit
                    satRes.reason.isOom -> logger.warn { "${res.solverInstanceInfo?.solverConfig?.fullName} went out of memory" }
                    else ->
                        satRes.exceptionFromSolver?.let { exception ->
                            ArtifactManagerFactory().let { artifactManager ->
                                val name = "solverException_" +
                                    "${res.solverInstanceInfo?.solverConfig?.fullName ?: "unknownSolver"}_" +
                                    "${solverExceptionArtifactCounter.incrementAndGet()}.txt"
                                artifactManager.registerArtifact(name, StaticArtifactLocation.Reports)
                                val printStream = PrintStream(artifactManager.getArtifactHandleStream(name))
                                printStream.println("-- vJobRes (single): --")
                                printStream.println(res.longToString())
                                printStream.println("-- exception: --")
                                exception.printStackTrace(printStream)
                            }
                        }
                }

                else -> Unit
            }
        }
        res.subResultsFlattened.forEach { dumpSingleIfNeeded(it) }
    }

    companion object {
        private val solverExceptionArtifactCounter = AtomicInteger(0)
        suspend fun verify(
            scene: ISceneIdentifiers,
            tacObject: CoreTACProgram,
            liveStatsReporter: LiveStatsReporter,
            rule: IRule,
        ): VerifierResult =
            TACVerifier(
                scene,
                NameObject(tacObject.name, scene.getContracts().map { it.name }, rule.ruleType.toString()),
                rule,
            ).runVerifier(tacObject, liveStatsReporter)

        @TestOnly
        suspend fun verify(
            scene: ISceneIdentifiers,
            tacObject: CoreTACProgram,
            liveStatsReporter: LiveStatsReporter,
        ): VerifierResult = verify(scene, tacObject, liveStatsReporter, IRule.createDummyRule(tacObject.name))


        suspend fun verify(
            scene: ISceneIdentifiers,
            fileName: String,
            rule: IRule,
        ) = TACVerifier(scene, NameObject(fileName), rule).runVerifierOnFile(fileName)

        suspend fun verifyPresolver(
            scene: ISceneIdentifiers,
            fileName: String,
            rule: IRule,
        ) = TACVerifier(scene, NameObject(fileName), rule).let {
            it.runVerifierOnFileWithSpecificHandler(
                fileName,
                it::handlePresolver
            )
        }

        val CoreTACProgram.containsStorageComparisons
            get() =
                analysisCache.graph.commands
                    .mapNotNull {
                        (it.cmd as? TACCmd.Simple.AnnotationCmd)?.annot
                    }
                    .any { (key, value) ->
                        key == TACMeta.SNIPPET && value is SnippetCmd.CVLSnippetCmd.StorageComparison
                    }
    }
}


/** Identifier for each subprogram that we create via splitting. */
@Serializable
data class RuleAndSplitIdentifier(val ruleId: RuleIdentifier, val splitAddress: SplitAddress) {
    override fun toString(): String = "$ruleId-$splitAddress"
    companion object {
        fun root(ruleId: RuleIdentifier) = RuleAndSplitIdentifier(ruleId, SplitAddress.Root)
        /* when there's no rule, make a dummy one from a name string */
        @TestOnly
        fun dummyRoot(name: String) = root(RuleIdentifier.freshIdentifier(name))
    }
}
