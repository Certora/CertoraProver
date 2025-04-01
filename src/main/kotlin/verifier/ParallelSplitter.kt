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

import analysis.TACCommandGraph
import config.Config
import config.LocalSettings
import datastructures.NonEmptyList
import datastructures.stdcollections.*
import log.*
import parallel.*
import parallel.coroutines.*
import smt.HashingScheme
import smt.LExpressionToSmtLib
import smt.LExpressionToSmtLibScene
import smt.solverscript.LExpressionFactory
import smt.solverscript.VcFeature
import smtlibutils.cmdprocessor.*
import smtlibutils.data.FactorySmtScript
import smtlibutils.data.SatResult
import smtlibutils.statistics.*
import solver.*
import spec.rules.IRule
import statistics.data.TACProgramMetadata
import utils.*
import vc.data.CoreTACProgram
import vc.gen.LExpVC
import vc.gen.LeinoWP
import verifier.autoconfig.AutoConfigManager
import verifier.mus.UnsatCoreInputData
import verifier.splits.SplitAddress
import verifier.splits.SplitTree
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private val splitLogger = Logger(LoggerTypes.PARALLEL_SPLITTER)


class ParallelSplitter(
    val tacProgram: CoreTACProgram,
    val rootTacProgramMetadata: TACProgramMetadata,
    val rule: IRule?,
    val localSettings: LocalSettings,
    private val autoConfigManager: AutoConfigManager,
) {

    private val satSplits = mutableListOf<ParallelRacerResult>()
    private val unsatSplits = mutableListOf<ParallelRacerResult>()
    private val unknownSplits = mutableListOf<ParallelRacerResult>()
    private val unsatCoreSplitsData = mutableMapOf<SplitAddress, UnsatCoreInputData>()
    fun getUnsatCoreSplitsData() = unsatCoreSplitsData.toMap()
    private val startTime = getCurrentTime()


    sealed interface ParallelRacerResult {
        val subProblem: SplitTree.Node
        val result: SmtFormulaCheckerResultWithChecker
        val postprocessor: UniqueSuccessorRemover.ModelPostprocessor?

        data class Basic(
            override val subProblem: SplitTree.Node,
            override val result: SmtFormulaCheckerResultWithChecker,
            override val postprocessor: UniqueSuccessorRemover.ModelPostprocessor?,
        ) : ParallelRacerResult

        data class Full(
            override val subProblem: SplitTree.Node,
            override val result: SmtFormulaCheckerResultWithChecker,
            override val postprocessor: UniqueSuccessorRemover.ModelPostprocessor?,
            val collapsedTacGraph: TACCommandGraph,
            val query: SmtFormulaCheckerQuery,
            val solverConfig: SolverConfig,
        ) : ParallelRacerResult {
            fun generateTAC(autoConfigManager: AutoConfigManager): CoreTACProgram = subProblem.generate(autoConfigManager)
        }
    }

    private suspend fun checkSubProblems(splitsToSolve: List<SplitTree.Node>, timeout: Duration): List<ParallelRacerResult> {

        fun generateQuery(setup: LExpToSmtSetupInfo, lxf: LExpressionFactory, name: String, vc: LExpVC) =
            LExpressionToSmtLib(
                name,
                LExpressionToSmtLibScene(
                    lxf,
                    setup.createAxiomGeneratorContainer.create(lxf, setup.targetTheory),
                    setup.targetTheory,
                    setup.hashingScheme,
                    setup.createExpNormalizer(lxf),
                ),
                vc
            ).output()

        data class RacerTask(
            val subProblem: SplitTree.Node,
            val executable: Executable,
            val postprocessor: UniqueSuccessorRemover.ModelPostprocessor,
            val collapsedTACProgram: CoreTACProgram,
        )

        /**
         * For each split/subProblem, we generate a set of NIA executables and LIA executables. Furthermore, the
         * LIA executables are extended with a [CEXVerifier], i.e., if the LIA formula is found to be SAT,
         * we use a list of [ConstraintChooser]s within that executable to verify the counter-example.
         * Note that this is a different logic from [LExpVcChecker] where we just collect the counter-example from the
         * initial LIA race and then try to verify it in a subsequent NIA race.
         */
        val executables = splitsToSolve.map { subProblem ->
            val tacProgramToVerify: CoreTACProgram = subProblem.generate(autoConfigManager)

            val lxf = LExpressionFactory()

            val (collapsed, postprocessor) = UniqueSuccessorRemover.removeUniqueSuccessors(tacProgramToVerify)
            val leinoWP = LeinoWP(
                collapsed,
                lxf,
                rootTacProgramMetadata.updateSplitAddress(subProblem.address),
            )
            val vc: LExpVC = leinoWP.generateVCs()

            val niaSetup = LExpToSmtSetupInfo.UFNIAorAUFNIA(
                false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                CreateAxiomGeneratorContainer.Config(
                    HashingScheme.Datatypes,
                    listOf(),
                    CreateAxiomGeneratorContainer.Config.BvOverflowChecks.DontCare
                )
            )

            val liaSetup = LExpToSmtSetupInfo.UFLIAorAUFLIA(
                false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                CreateAxiomGeneratorContainer.Config(
                    HashingScheme.Datatypes,
                    listOf(),
                    CreateAxiomGeneratorContainer.Config.BvOverflowChecks.DontCare
                )
            )

            val baseQueryName = tacProgramToVerify.name + subProblem.address.toString()
            val niaQuery = generateQuery(niaSetup, lxf.copy(), baseQueryName + "NIA", vc)
            val liaQuery = generateQuery(liaSetup, lxf.copy(), baseQueryName + "LIA", vc)

            val script = FactorySmtScript
            val raceDescription = "race_" + tacProgramToVerify.name

            val niaExecutables = Config.ParallelSplitterNIASolvers.get().map { solverConfig ->
                Executable(
                    raceDescription = raceDescription,
                    query = niaQuery,
                    script = script,
                    config = solverConfig.copy(timelimit = timeout),
                    winsRace = { it is SatResult.SAT },
                    interpretation = LExpVcChecker.Interpretation.Standard,
                )
            }

            val liaCexVerifier = CEXVerifier(
                listOf(ConstraintChooser.TakeAll, ConstraintChooser.BoolsAndManyMore),
                SolverConfig.z3.default.copy(timelimit = timeout),
                niaQuery,
                vc,
                script
            )

            val liaExecutables = Config.ParallelSplitterLIASolvers.get().map { solverConfig ->
                Executable(
                    raceDescription = raceDescription,
                    query = liaQuery,
                    script = script,
                    config = solverConfig.copy(timelimit = timeout),
                    winsRace = { it is SatResult.SAT },
                    interpretation = LExpVcChecker.Interpretation.OverApproximation,
                    cexVerifier = liaCexVerifier
                )
            }

            (liaExecutables + niaExecutables).map { RacerTask(subProblem, it, postprocessor, collapsed) }
        }.flatten()


        /**
         * Execute the actual race. Note that an executable that solves its query wins the race only if the result is SAT
         * (i.e., in this case, it interrupts all the other executables and stop the race).
         */
        val resultInfo = measureTimedValue {
            try {
                ParallelPool.inherit(ParallelPool.SpawnPolicy.GLOBAL) {
                    it.rpcRace(
                        executables.map {
                            val executable = it.executable
                            Racer(
                                thunk = { executable.exec(critical = true) },
                                resultOnNoExit = SmtFormulaCheckerResult.Single.Cancelled(
                                    executable.checkerInstance?.solverInstanceInfo,
                                    executable.query.info,
                                    PreExecutionStatistics(executable.config.getConfigStats(), listOf())
                                ).withNullChecker(),
                                timeout = timeout,
                                maximumAllowedDelay = null
                            )
                        }
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return executables.map {
                    ParallelRacerResult.Basic(
                        subProblem = it.subProblem,
                        result = SmtFormulaCheckerResult.OtherException(
                            e,
                            it.executable.checkerInstance,
                            it.executable.query,
                            null,
                            null
                        ).withChecker(it.executable.checkerInstance),
                        postprocessor = it.postprocessor
                    )
                }
            }
        }
        val (_, ress) = resultInfo.value

        val results = ress.zip(executables).map { (res, racerTask) ->
            val makeInterrupt: (SmtFormulaCheckerResult.Companion.InterruptReason) -> SmtFormulaCheckerResultWithChecker =
                { reason: SmtFormulaCheckerResult.Companion.InterruptReason ->
                    racerTask.executable.interrupted(
                        null,
                        reason,
                        (res.res?.result as? SmtFormulaCheckerResult.Single)?.preExecutionStatistics
                            ?: PreExecutionStatistics(ConfigStatistics(), listOf()),
                        ResultStatistics()
                    )
                }
            val result = when (res) {
                is RacerResult.FromJob -> racerTask.executable.interpret(res.res)
                is RacerResult.LostRace -> makeInterrupt(SmtFormulaCheckerResult.Companion.InterruptReason.LOST_RACE)
                is RacerResult.Skipped -> makeInterrupt(SmtFormulaCheckerResult.Companion.InterruptReason.SKIPPED)
                is RacerResult.SkippedDelayed -> makeInterrupt(SmtFormulaCheckerResult.Companion.InterruptReason.SKIPPED_DELAYED)
                is RacerResult.Timeout -> makeInterrupt(SmtFormulaCheckerResult.Companion.InterruptReason.EXTERNAL_TIMEOUT)
            }
            ParallelRacerResult.Full(
                racerTask.subProblem,
                result,
                racerTask.postprocessor,
                collapsedTacGraph = racerTask.collapsedTACProgram.analysisCache.graph,
                query = racerTask.executable.query,
                solverConfig = racerTask.executable.config,
            )
        }

        /** Close the non-SAT solvers. The SAT solvers are kept alive for future CEX postprocessing **/
        results.forEach {
            if (it.result.result.satResult !is SatResult.SAT) {
                it.result.checker?.close()
            }
        }

        /**
         * In the race, we can possibly run multiple executables (read Solvers) per split. Here, we select just one
         * result per split (to propagate it outside the function).
         */
        val mappedResults = mutableMapOf<SplitTree.Node, ParallelRacerResult>().let { mm ->
            results.forEach { res ->
                if (res.subProblem !in mm) {
                    mm[res.subProblem] = res
                    /** ensure we have "a" result **/
                } else if (res.result.result.satResult is SatResult.SAT) {
                    /** we definitely prefer SAT to timeout **/
                    check(mm[res.subProblem]!!.result.result.satResult !is SatResult.UNSAT) { "Got contradicting results from solvers" }
                    /** we need to make sure we close all solvers that are not propagated to the following steps **/
                    if (mm[res.subProblem]!!.result.result.satResult is SatResult.SAT) {
                        mm[res.subProblem]!!.result.checker?.close()
                    }
                    mm[res.subProblem] = res
                } else if (res.result.result.satResult is SatResult.UNSAT) {
                    /** we prefer UNSAT to timeout **/
                    check(mm[res.subProblem]!!.result.result.satResult !is SatResult.SAT) { "Got contradicting results from solvers" }
                    mm[res.subProblem] = res
                } else if (mm[res.subProblem]!!.result.result.satResult !is SatResult.SAT && mm[res.subProblem]!!.result.result.satResult !is SatResult.UNSAT) {
                    /** Currently, this one might not apply. But it would be useful e.g. in case of unsat-underapproximation
                     * or sat-overapproximation results (if we get any) **/
                    mm[res.subProblem] = res
                }
            }
            mm
        }

        check(splitsToSolve.toSet() == mappedResults.keys.toSet()) { "Missing results for some splits" }
        return mappedResults.values.toList()
    }

    /**
     * The main function. Checks, whether the input [tacProgram] satisfies the [rule].
     * Note that this function currently does not return a result. Instead, if fills the lists of [satSplits],
     * [unsatSplits] and [unknownSplits]. The actual [Verifier.VerifierResult] can be then computed/determined
     * via the function [getResult].
     *
     * The underlying algorithm is the following. Initially, we possibly split the [tacProgram] to several
     * subProblems (splits). The number of splits is determined by [localSettings.parallelSplittingInitialDepth]
     * (could be also 0, i.e., in that case we actually do not split immediately). And we store the individual subProblems
     * either to a list of [nonLeavesToSolve] and [leavesToSolve] (a leaf cannot be split anymore, either due to no
     * more branching or reached [Config.SplittingDepth]).
     *
     * The rest of the computation consists of two loops where we first check the nonLeaves and then the leaves.
     * If we timeout for a nonLeaf, we split it again, i.e., the list [nonLeavesToSolve] might be gradually growing.
     * Intuitively, we gradually build the underlying splitting tree and check its "current leaves". On contrary
     * to the logic of [TACVerifier.leinoVCWithSplitting] where we traverse the splitting three in a depth-first-search
     * manner, here we follow more like a breadth-first-search approach ("more like" means that we go wide but not
     * exactly a level by level; see details below).
     *
     */

    private fun reportViolation(res: ParallelRacerResult) {
        satSplits.add(res)
        splitLogger.info {
            "found violation for ${tacProgram.name} in depth: ${res.subProblem.depth}," +
                "split address: ${res.subProblem.address}, and taking " +
                "{${startTime.elapsedNow().inWholeSeconds}} seconds to get there."
        }
    }

    private fun reportHardTimeout(leftToSolve: List<SplitTree.Node>) {
        unknownSplits.addAll(
            leftToSolve.map { subProblem ->
                ParallelRacerResult.Basic(
                    subProblem = subProblem,
                    result = SmtFormulaCheckerResult.Single.Void(
                        "overall timeout reached",
                        null,
                        null,
                        hasTimeout = true
                    ).withNullChecker(),
                    postprocessor = null
                )
            }
        )
        splitLogger.info { "Reach overall timeout with ${unknownSplits.size} unknown splits left to solve." }
    }

    /**
     * Shuffle [splits], and removes and returns [toTake] elements from it.
     */
    private fun pickSplitsToSolve(splits: MutableList<SplitTree.Node>, toTake: Int): List<SplitTree.Node> {
        splits.shuffle(Random(0))
        return splits.takeLast(toTake).also { taken ->
            repeat(taken.size) { splits.removeLast() }
        }
    }

    suspend fun solve() {
        val nonLeavesToSolve = mutableListOf<SplitTree.Node>()
        val leavesToSolve = mutableListOf<SplitTree.Node>()

        /**
         * Gets a collection of problems with their target depths, splits them until reaching that
         * target depth, putting the results in the right set of problems to solve.
         */
        fun splitAndSplit(initialProblems: Collection<Pair<SplitTree.Node, Int>>) {
            val problemQueue = arrayDequeOf(initialProblems)
            problemQueue.consume { (subProblem, targetDepth) ->

                when {
                    !subProblem.probablySplittable -> leavesToSolve += subProblem
                    subProblem.depth == targetDepth -> nonLeavesToSolve += subProblem
                    else -> {
                        check(subProblem.depth < targetDepth) { "missed the target splitting depth" }
                        val subProblemTac = subProblem.generate(autoConfigManager)
                        if (!subProblem.splittable) {
                            leavesToSolve += subProblem
                        } else {
                            subProblem.split(subProblemTac)
                                .let { (a, b) ->
                                    problemQueue.addLast(a to targetDepth)
                                    problemQueue.addLast(b to targetDepth)
                                }
                        }
                    }
                }
            }
        }

        /** This initial fill of [nonLeavesToSolve] and [leavesToSolve]. Both these can be later extended (we further
         * split subProblems where we timeout).
         */
        val splitTree = SplitTree(tacProgram, localSettings.depth)
        splitAndSplit(
            listOf(splitTree.rootNode to localSettings.parallelSplittingInitialDepth)
        )

        /**
         * For the non-leaf subProblems, we currently use just 10 seconds timelimit (per individual SMT query/executuable).
         * Could be possibly configured in the future or we could re-use [Config.MediumSplitTimeout] (which is by default
         * also 10).
         *
         * We also set an [overallTimeout]. If we reach this, we stop the computation with UNKNOWN result.
         */
        val perRaceTimeout = 10.seconds
        val overallTimeout = localSettings.parallelSplittingTimelimit
        var totalWeight = 0.0

        fun markUnsatSplit(unsatRes: ParallelRacerResult) {
            if (unsatRes.result.result.satResult is SatResult.UNSAT) {
                totalWeight += unsatRes.subProblem.weight
            }
            unsatSplits.add(unsatRes)
            (unsatRes as? ParallelRacerResult.Full)?.let {
                unsatCoreSplitsData[unsatRes.subProblem.address] = UnsatCoreInputData(
                    tacCommandGraph = unsatRes.collapsedTacGraph,
                    tac = unsatRes.generateTAC(autoConfigManager),
                    query = unsatRes.query,
                    solverConfig = unsatRes.solverConfig,
                    originalCheckDuration = it.result.result.representativeResult.resultStats?.solverWallRuntime
                )
            } ?: splitLogger.warn { "got an unsat result without the ParallelRacerResult.Full type" }

        }

        while (nonLeavesToSolve.isNotEmpty()) {
            if (startTime.elapsedNow() > overallTimeout) {
                return reportHardTimeout(nonLeavesToSolve)
            }

            splitLogger.info {
                "${tacProgram.name} non-leaves to solve: ${nonLeavesToSolve.size}, " +
                    "leaves: ${leavesToSolve.size}, elapsed time: ${startTime.elapsedNow()}"
            }

            val toCheck = pickSplitsToSolve(nonLeavesToSolve, Config.NumOfParallelisedSplits.get())
            val results = checkSubProblems(toCheck, perRaceTimeout)
            val needFurtherSplitting = buildList {
                results.forEach {
                    when (it.result.result.satResult) {
                        is SatResult.SAT -> return reportViolation(it)
                        is SatResult.UNSAT -> markUnsatSplit(it)
                        is SatResult.UNKNOWN -> add(it.subProblem to (it.subProblem.depth + 2))
                    }
                }
            }
            splitAndSplit(needFurtherSplitting)

            Logger.always(
                "Proved ${unsatSplits.size} splits weighting ${totalWeight * 100}% for ${tacProgram.name}",
                true
            )

            splitLogger.info { "${tacProgram.name}, timeouted splits in the last round: ${needFurtherSplitting.size}" }
        }

        while (leavesToSolve.isNotEmpty()) {
            if (startTime.elapsedNow() > overallTimeout) {
                return reportHardTimeout(leavesToSolve)
            }

            splitLogger.info {
                "${tacProgram.name} leaves to solve: ${leavesToSolve.size}, elapsed time: ${
                    startTime
                        .elapsedNow()
                }"
            }
            val toCheck = pickSplitsToSolve(leavesToSolve, Config.NumOfParallelisedSplits.get())
            val results = checkSubProblems(toCheck, localSettings.solverTimeout)

            results.forEach {
                when (it.result.result.satResult) {
                    is SatResult.SAT -> {
                        return reportViolation(it)
                    }

                    is SatResult.UNSAT -> {
                        markUnsatSplit(it)
                    }

                    is SatResult.UNKNOWN -> {
                        unknownSplits.add(it)
                        if (!localSettings.dontStopAtFirstSplitTimeout) {
                            return
                        }
                    }
                }
            }
            Logger.always(
                "Proved ${unsatSplits.size} splits weighting ${totalWeight * 100}% for ${tacProgram.name}",
                true
            )
        }
    }

    fun getResult(): SplitterResult {

        /**
         * If we got at least one SAT, the overall result is SAT. Else, if there is an unknown splits left,
         * the overall result if UNKNOWN. Finally, if there are no unknown splits, the final result is UNSAT.
         */
        val results = (satSplits.ifEmpty { unknownSplits.ifEmpty { unsatSplits } })

        /**
         * We pick just one representative result.
         */
        val represenativeResult = results[0]
        val checkerResults = NonEmptyList(represenativeResult.result.result)

        return SplitterResult(
            tacProgramToVerify = represenativeResult.subProblem.generate(autoConfigManager),
            results = checkerResults,
            elapsedTimeSeconds = startTime.elapsedNow().inWholeSeconds,
            postprocessor = represenativeResult.postprocessor
        )
    }
}

data class SplitterResult(
    val tacProgramToVerify: CoreTACProgram,
    val results: NonEmptyList<SmtFormulaCheckerResult>,
    val elapsedTimeSeconds: Long,
    val postprocessor: UniqueSuccessorRemover.ModelPostprocessor?
)
