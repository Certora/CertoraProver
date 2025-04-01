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

package verifier.parallel

import config.Config.MediumSplitTimeout
import config.Config.ParallelSplittingStepSize
import config.LocalSettings
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import log.*
import scene.ISceneIdentifiers
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.cmdprocessor.SmtFormulaCheckerResultWithChecker
import smtlibutils.data.ProcessDifficulties
import smtlibutils.data.SatResult
import solver.SolverResult
import spec.rules.IRule
import statistics.SDCollectorFactory
import statistics.ScalarKeyValueStats
import statistics.data.TACProgramMetadata
import statistics.toSDFeatureKey
import vc.data.CoreTACProgram
import vc.gen.LExpVC
import verifier.LExpVcCheckerConfig
import verifier.UniqueSuccessorRemover
import verifier.Verifier
import verifier.postprocessResult
import verifier.splits.SplitTree
import verifier.autoconfig.AutoConfigManager
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

private val logger = Logger(LoggerTypes.TACSPLITTER)

/**
 * Holds all the important information of a (partial) result within the [SplitManager]. This includes the result itself,
 * as well as everything needed to get to a [Verifier.VerifierResult], perform counter example postprocessing and
 * produce statistics.
 */
data class PartialResult(
    val checkerResult: SmtFormulaCheckerResultWithChecker,
    val postprocessor: UniqueSuccessorRemover.ModelPostprocessor,
    val config: LExpVcCheckerConfig,
    val vc: LExpVC,
    val elapsed: Duration,
) {
    fun isDefinitiveResult() = when (checkerResult.result.satResult) {
        SatResult.UNSAT -> true
        SatResult.SAT -> true
        else -> false
    }

    fun isSAT() = checkerResult.result.satResult == SatResult.SAT
}

/**
 * The main class for the parallel splitting approach. See [solve] for details on how it proceeds.
 */
class SplitManager(
    val scene: ISceneIdentifiers,
    val tacProgram: CoreTACProgram,
    val rootTACProgramMetadata: TACProgramMetadata,
    val rule: IRule?,
    val localSettings: LocalSettings,
    private val autoConfigManager: AutoConfigManager,
) {
    @Volatile
    private var foundCEX: Boolean = false
    private val baseDuration: Duration = MediumSplitTimeout.get().seconds
    val scheduler = SplitScheduler()

    /**
     * Store statistics about a particular [PartialResult].
     */
    private fun sendStatistics(name: String, split: SplitTree.Node, results: TimedValue<PartialResult>) {
        results.value.checkerResult.result.subResultsFlattened.forEach { res ->
            val kvl = ScalarKeyValueStats<String>(
                tacProgram.name.toSDFeatureKey(),
                "ParallelSplitter".toSDFeatureKey(),
                "${name}-${split.address}".toSDFeatureKey(),
                "${res.solverInstanceInfo?.name}".toSDFeatureKey()
            )
            res.solverInstanceInfo?.let { sii ->
                kvl.addKeyValue("solver-name", sii.solverConfig.fullName)
                kvl.addKeyValue("solver-binary", sii.solverConfig.solverInfo.defaultCommand)
                kvl.addKeyValue("command-line", sii.solverConfig.getCommandline().joinToString(" "))
            }
            when (val satres = res.satResult) {
                SatResult.SAT -> kvl.addKeyValue("result", "sat")
                SatResult.UNSAT -> kvl.addKeyValue("result", "unsat")
                is SatResult.UNKNOWN -> {
                    kvl.addKeyValue("result", "unknown")
                    kvl.addKeyValue("unknown-reason", satres.reason.toString())
                    satres.infoString?.let { kvl.addKeyValue("unknown-error", it) }
                }
            }
            res.resultStats?.let { stats ->
                kvl.addKeyValue("start", "${stats.startTime}")
                stats.solverCPURuntime?.let { kvl.addKeyValue("cputime", it.toIsoString()) }
                stats.solverWallRuntime?.let { kvl.addKeyValue("walltime", it.toIsoString()) }
            }
            SDCollectorFactory.collector().collectFeature(kvl)
        }
    }

    /**
     * Check a particular split using a provided [check] function. Takes care of measuring the time, logging info and
     * sending statistics.
     */
    private suspend fun doCheck(
        ident: String,
        sub: SplitTree.Node,
        timeout: Duration,
        check: suspend (Duration) -> PartialResult
    ): PartialResult {
        val msg = "$ident check on ${sub.name} with ${timeout}"
        logger.info { msg }
        return measureTimedValue { check(timeout) }.also {
            sendStatistics(ident, sub, it)
            logger.info { "${msg} -> ${it.value.checkerResult.result} after ${it.duration}" }
        }.value.also {
            if (it.isSAT()) {
                foundCEX = true
            }
        }
    }

    /** Do a full portfolio check with full timeout */
    private suspend fun doFullCheck(sub: SplitTree.Node, tac: CoreTACProgram): PartialResult =
        doCheck("full", sub, localSettings.solverTimeout) { timeout ->
            fullCheck(sub, tac, timeout, rootTACProgramMetadata.updateSplitAddress(sub.address), localSettings)
        }

    /** Do a quick check with only one solver */
    private suspend fun doQuickCheck(sub: SplitTree.Node, tac: CoreTACProgram): PartialResult =
        doCheck("quick", sub, baseDuration) { timeout ->
            quickCheck(sub, tac, timeout, rootTACProgramMetadata.updateSplitAddress(sub.address), localSettings)
        } // Maybe recheck full if it fails?

    /** Do a check with a single solver but full timeout */
    private suspend fun doSingleFullCheck(sub: SplitTree.Node, tac: CoreTACProgram): PartialResult =
        doCheck("single-full", sub, localSettings.solverTimeout) { timeout ->
            quickCheck(sub, tac, timeout, rootTACProgramMetadata.updateSplitAddress(sub.address), localSettings)
        }


    /**
     * Main function for recursive solving. Takes a split and does one of the following things:
     * - do a full check if the split can not be split further
     * - re-split immediately if [immediate] is greater than zero
     * - otherwise do a quick check, and then a re-split if the quick checks result was inconclusive
     */
    private suspend fun solveSubproblem(
        sub: SplitTree.Node,
        immediate: Int = 0,
        callback: suspend (PartialResult) -> Unit
    ) {

        suspend fun doSplit(additionalSplits: Int) {
            check(sub.probablySplittable) { "Can not split what has no chance of being split" }
            check(additionalSplits >= 0) { "Number of additional splits can not be negative" }

            fun generateSplits(base: SplitTree.Node, additional: Int): List<SplitTree.Node> {
                if (!base.probablySplittable) {
                    return listOf(base)
                }
                val actualTac = base.generate(autoConfigManager)
                return when {
                    // this first case is the only one in which we will need to eventually regenerate the tac.
                    !base.splittable -> listOf(base)
                    additional == 0 -> base.split(actualTac)
                    else -> base.split(actualTac).flatMap { generateSplits(it, additionalSplits - 1) }
                }
            }

            val splits = generateSplits(sub, additionalSplits)
            logger.info { "split ${sub.name} into ${splits.size} sub-splits" }
            scheduler.runParallel(
                items = splits,
                transform = { solveSubproblem(it, callback = callback) },
                info = { SplitScheduler.WorkInfo(it) }
            ).also {
                logger.info { "splits of ${sub.name} were processed" }
            }
        }

        when {
            foundCEX -> logger.info { "skip ${sub.name} as a CEX was already found" }
            !sub.probablySplittable -> callback(doFullCheck(sub, sub.generate(autoConfigManager)))
            immediate > 0 -> {
                logger.info { "split ${sub.name} (immediate ${immediate}, depth ${sub.depth})" }
                doSplit(immediate - 1)
            }

            else -> {
                val tac = sub.generate(autoConfigManager)
                if (sub.splittable) {
                    val res = doQuickCheck(sub, tac)
                    if (res.isDefinitiveResult()) {
                        callback(res)
                    } else {
                        res.checkerResult.checker?.close()
                        doSplit(ParallelSplittingStepSize.get() - 1)
                    }
                } else {
                    callback(doFullCheck(sub, tac))
                }
            }
        }
    }

    /**
     * Private minimal enum to easily group results from recursive checking. It is somewhat similar to [SatResult] and
     * [SolverResult], but really only a plain enum.
     */
    private enum class SatResultType { SAT, UNSAT, UNKNOWN }

    fun solve(): Flow<Verifier.VerifierResult> = channelFlow {
        logger.info { "solving ${tacProgram.name} with parallel splitting" }
        val splitTree = SplitTree(tacProgram, localSettings.depth)
        val initial = splitTree.rootNode

        logger.debug { "starting initial check" }
        // do a check on the full input.
        val initialDeferred = async {
            doSingleFullCheck(initial, initial.generate(autoConfigManager)).also {
                if (it.isDefinitiveResult()) {
                    scheduler.shutdown()
                    logger.debug { "send result from initial" }
                    // we got a definitive result here. use it and shut down everything else
                    send(it)
                } else {
                    logger.debug { "ignore inconclusive result from single-full for now" }
                }
            }
        }

        logger.debug { "starting recursive check" }
        // do the recursive solving
        val partialResults = mutableListOf<PartialResult>()
        val recursiveDeferred = async {
            solveSubproblem(initial, max(1, localSettings.parallelSplittingInitialDepth)) {
                synchronized(partialResults) {
                    partialResults += it
                }
                if (it.isSAT()) {
                    initialDeferred.cancel()
                    logger.debug { "send result from recursive ${it.config.vcName}" }
                    send(it)
                    scheduler.shutdown()
                } else {
                    logger.debug { "don't send result from recursive ${it.config.vcName}" }
                }
            }
        }

        /**
         * Now wait for recursive solving to finish and go through the split results:
         * - if we have SAT: it was already returned
         * - if we have UNKNOWN: ignore split results
         * - if we have (only) UNSAT in splits: UNSAT
         */
        try {
            logger.debug { "waiting for recursive checks to finish" }
            recursiveDeferred.await()
            val grouped = partialResults.groupBy {
                when (it.checkerResult.result.satResult) {
                    SatResult.SAT -> SatResultType.SAT
                    SatResult.UNSAT -> SatResultType.UNSAT
                    else -> SatResultType.UNKNOWN
                }
            }
            when {
                SatResultType.SAT in grouped -> {}
                SatResultType.UNKNOWN in grouped -> {
                    logger.debug { "recursive solving has unknown, use initial check result" }
                    send(initialDeferred.await())
                }

                SatResultType.UNSAT in grouped -> {
                    logger.debug { "recursive solving shows unsat" }
                    send(grouped[SatResultType.UNSAT]!!.first())
                }
            }
        } catch (_: CancellationException) {
            logger.debug { "recursive solving was cancelled" }
        }
    }.map { pr ->
        logger.debug { "Got result from parallel splitter" }
        val rsr = postprocessResult(pr.vc, pr.checkerResult, pr.config.postprocessingConfig)
        val checkerResults = rsr.diversifiedResults.toList().toNonEmptyList()
            ?: error("multi [SmtFormulaCheckerResult] should have at least one result")
        if (checkerResults.size > 1) {
            check(checkerResults.all { it is SmtFormulaCheckerResult.Single && it.satResult == SatResult.SAT })
            { "when multi [SmtFormulaCheckerResult] are produced, all of them are expected to be SAT" }
        }
        val processedDiffculties = ProcessDifficulties.processSmtFormulaCheckerResult(checkerResults.first())
        if (processedDiffculties != null) {
            Logger.regression { "Got difficulties from solver." }
        }
        verifierResultFromCheckerResult(
            scene,
            tacProgram,
            rootTACProgramMetadata,
            pr.postprocessor,
            checkerResults,
            pr.elapsed,
            processedDiffculties,
            tacProgram.analysisCache.graph
        )
    }
}
