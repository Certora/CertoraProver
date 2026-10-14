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
import config.Config
import config.LocalSettings
import log.*
import solver.*
import vc.data.CoreTACProgram
import java.lang.management.ManagementFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import datastructures.stdcollections.*
import smt.BackendStrategyEnum
import smt.UseLIAEnum

class TimeoutCracker private constructor(val tacProgram: CoreTACProgram) {

    /**
     * Functions returns remaining time for a run of Timeout Cracker.
     * 1) If global timeout is not set by a user, the Solver Timeout is returned without further postprocessing.
     * 2) If global timeout is set and solver timeout is not, we take global timeout and further postprocess it, see 4)
     * 3) If global timeout and solver timeout are both set, the smaller of two is chosen and further processed.
     * 4) The already elapsed time is subtracted from the number from 2 or 3 and returned.
     */
    private fun getRemainingTime(localSettings: LocalSettings = LocalSettings()): Duration {
        val globalTimeout = Config.actualGlobalTimeout(localSettings.globalTimeout.inWholeSeconds.toInt(),
            localSettings.userGlobalTimeout.inWholeSeconds.toInt())
        if (globalTimeout == 0) {
            Logger.always("Solver timeout chosen", true)
            return localSettings.solverTimeout
        }

        val timeout = if (Config.SolverTimeout.isDefault()) {
            Logger.always("Solver timeout is default, choosing global timeout", true)
            globalTimeout.seconds
        } else {
            Logger.always("Min of global and solver timeout", true)
            localSettings.solverTimeout.coerceAtMost(globalTimeout.seconds)
        }

        val startTimeMillis = ManagementFactory.getRuntimeMXBean().startTime
        val timeRemainingSeconds =
            (timeout.inWholeSeconds - ((System.currentTimeMillis() - startTimeMillis) / 1000)).toInt()
        Logger.always(
            "Timeout: ${timeout.inWholeSeconds} and spent time " +
                "${(System.currentTimeMillis() - startTimeMillis) / 1000}", true
        )

        /** give some time to shutdown before hard timeout hits */
        val pad = 20
        val timeoutSeconds = timeRemainingSeconds - pad
        if (timeoutSeconds < 0) {
            return 1.seconds
        }
        return timeoutSeconds.seconds
    }

    private val localSettingsPortfolio = listOf<(Duration) -> LocalSettings>(
        // Parallel splitter, depth 10, z3:lia1, yices:def
        { timePerSplit ->
            LocalSettings(
                depth = 10, parallelSplittingTimelimit = timePerSplit, parallelSplitting = true,
                solverProgramChoice = SolverChoice(
                    listOf(
                        SolverConfig.z3.lia1,
                        SolverConfig.yices.def
                    )
                ),
                parallelSplitterLIASolvers = SolverChoice(listOf(SolverConfig.z3.lia1, SolverConfig.yices.def)),
                parallelSplitterNIASolvers = SolverChoice(listOf(SolverConfig.z3.def, SolverConfig.yices.def))
            )
        },

        // initDepth 3, depth 10
        { timePerSplit ->
            LocalSettings(
                depth = 10, solverTimeout = timePerSplit, userGlobalTimeout = timePerSplit,
                initialSplitDepth = 3
            )
        },

        // Skip linear solvers. Try only nonlinear arithmetic - should solve OOM cases caused by LIA blow up.
        { timePerSplit ->
            LocalSettings(
                nonLinearArithmetic = true, solverTimeout = timePerSplit,
                userGlobalTimeout = timePerSplit, useLIA = UseLIAEnum.NONE,
                backendStrategy = BackendStrategyEnum.SINGLE_RACE
            )
        },

        // Skip linear solvers and leverage probabilistic nature of smt solving by using portfolio of z3
        // with random seeds. Focus on solving nonlinear cases without splitting.
        { timePerSplit ->
            LocalSettings(
                nonLinearArithmetic = true, useLIA = UseLIAEnum.NONE, depth = 0,
                backendStrategy = BackendStrategyEnum.SINGLE_RACE,
                solverTimeout = timePerSplit,
                solverProgramChoice = SolverChoice(
                    (1..10).map {
                        SolverConfig.z3.def.copy(
                            variantName = "${SolverConfig.z3.def.variantName}S${it}",
                            clOptions = SolverConfig.z3.def.clOptions + Z3SolverInfo.getOptionForRandomSeed(it),
                            canBeSkipped = { _, _ -> false },
                        )
                    }
                )
            )
        },

        // Skip linear solvers
        { timePerSplit ->
            LocalSettings(
                nonLinearArithmetic = true, useLIA = UseLIAEnum.NONE, depth = 0,
                backendStrategy = BackendStrategyEnum.SINGLE_RACE,
                solverTimeout = timePerSplit,
                solverProgramChoice = SolverChoice(
                    (1..10).map {
                        SolverConfig.cvc5.nonlin.copy(
                            variantName = "${SolverConfig.cvc5.nonlin.variantName}S${it}",
                            clOptions = SolverConfig.cvc5.nonlin.clOptions + CVC5SolverInfo.getOptionForRandomSeed(it),
                            canBeSkipped = { _, _ -> false },
                        )
                    }

                )
            )
        }
    )

    /**
     * Functions reset options which have impact on solving since we don't want them influence the configurations
     * from portfolio of TimeoutCracker.
     */
    @PollutesGlobalState
    private fun resetRelevantConfigOptions() {
        Config.ParallelSplitting.reset()
        Config.ParallelSplittingTimelimit.reset()
        Config.ParallelSplitterLIASolvers.reset()
        Config.ParallelSplitterNIASolvers.reset()
        Config.ParallelSplittingInitialDepth.reset()
        Config.OldParallelSplitting.reset()
        Config.Smt.BackendStrategy.reset()
        Config.Smt.InitialSplitDepth.reset()
        Config.Smt.easyLIA.reset()
        Config.NIAsolvers.reset()
        Config.LIAsolvers.reset()
        Config.SolverProgramChoice.reset()
        Config.GroundQuantifiers.reset()
        @OptIn(Config.DestructiveOptimizationsOption::class)
        Config.DestructiveOptimizationsMode.reset()
        Config.DontStopAtFirstSplitTimeout.reset()
        Config.LowSplitTimeout.reset()
        Config.MediumSplitTimeout.reset()
        Config.TinySplitTimeout.reset()
        Config.BVsolvers.reset()
        Config.NumOfParallelisedSplits.reset()
        Config.Smt.UseBV.reset()
        Config.Smt.UseLIA.reset()
        Config.Smt.UseNIA.reset()
        Config.SolverTimeout.reset()
    }

    /**
     * The main function of TimeoutCracker trying a portfolio of Prover configurations to [tacProgram].
     * The algorithm works as follows:
     * 1) Reset all prover options related to the solving.
     * 2) It starts with a fast run on the original [tacProgram] and collects unsolved splits.
     * 3) Foreach unsolved split it tries each configuration from the portfolio (returned by [configPortfolio]).
     */
    @PollutesGlobalState
    suspend fun crack(solveWithSplitting: suspend (CoreTACProgram, LocalSettings) -> Verifier.VerifierResult): Verifier.VerifierResult {
        Logger.always("Entering timeout cracker mode. " + tacProgram.name, true)
        resetRelevantConfigOptions()

        val userGlobalTimeout = 3600
        val depth = 5
        val getNumLeaves = { actualDepth: Int -> Math.pow(2.toDouble(), actualDepth.toDouble()).toInt() } // Number of leaves at depth [depth]
        val firstResult =  solveWithSplitting(tacProgram, LocalSettings(
            nonLinearArithmetic = true, depth = depth,
            solverProgramChoice = SolverChoice(listOf(SolverConfig.z3.def)),
            solverTimeout = (userGlobalTimeout/getNumLeaves(depth)).seconds,
            userGlobalTimeout = userGlobalTimeout.seconds,
            dontStopAtFirstSplitTimeout = true,
            overrideSolvers = true)
        )

        if (firstResult.finalResult == SolverResult.SAT || firstResult.finalResult == SolverResult.UNSAT) {
            return firstResult
        }

        Logger.always("First split timeouted. Starting portfolio of configurations.", true)

        check(firstResult.unsolvedSplitsPrograms != null) {
            "unsolvedSplits are null for ${firstResult.finalResult}"
        }

        val unsolvedSplitsCount = firstResult.unsolvedSplitsPrograms.size
        Logger.always("Starting solving $unsolvedSplitsCount splits", true)
        check(firstResult.finalResult != SolverResult.TIMEOUT || unsolvedSplitsCount != 0) {
            "We should have some unsolved splits"
        }

        val failedToSolve: Set<CoreTACProgram> = firstResult.unsolvedSplitsPrograms
        val solved: MutableSet<CoreTACProgram> = mutableSetOf()

        var splitIndex = 0
        failedToSolve.forEach tac@{ splitTacProgram ->
            Logger.always("Solving one of timeouted splits.", true)

            val remainingTime: Int = getRemainingTime().inWholeSeconds.toInt()
            Logger.always("Timeout cracker remaining time: $remainingTime", true)
            if (remainingTime <= 1) {
                Logger.always("No time remaining, quitting", true)
                return firstResult
            }

            val configNum = 5
            val remainingSplits = unsolvedSplitsCount - splitIndex
            splitIndex += 1

            val timeForOneTask = remainingTime / (configNum * remainingSplits)
            Logger.always(
                "Timeout cracker remaining time for one task (config,split) : $timeForOneTask",
                true
            )
            check(localSettingsPortfolio.size == configNum) { "numConfigs is not same as the actual number of configs" }

            var configIndex = 0
            localSettingsPortfolio.forEach { localConfig ->
                configIndex += 1
                Logger.always(
                    "Trying pair $splitIndex, $configIndex (split,configuration) for ${tacProgram.name}"  +
                        "no. ${(splitIndex-1)*configNum + configIndex} from ${failedToSolve.size * localSettingsPortfolio.size}",
                    true
                )

                val result = solveWithSplitting(splitTacProgram, localConfig(timeForOneTask.seconds))
                when (result.finalResult) {
                    SolverResult.SAT -> {
                        return result
                    }

                    SolverResult.TIMEOUT -> {
                        // Don't know if we want to do something here. Maybe in the next Milestone we can
                        // replace the current split and replace it with its subsplits.
                        Logger.always("Split timeouted. We have ${solved.size}/${failedToSolve.size} splits solved.",
                            true)
                    }

                    SolverResult.UNSAT -> {
                        solved.add(splitTacProgram)
                        Logger.always("Split solved. We have ${solved.size}/${failedToSolve.size} splits solved.",
                            true)
                        return@tac
                    }

                    else -> {
                        assert(false)
                    }
                }
            }
        }

        // All splits from [failedToSolve] were verified -> no violation.
        if (solved.size == failedToSolve.size) {
            // MILESTONE 2: create a reasonable UNSAT result
            return Verifier.VerifierResult.dummyUnsat(tacProgram)
        }

        // MILESTONE 2: need to collect timeouts
        return firstResult
    }

    companion object {
        @PollutesGlobalState
        suspend fun solve(
            tacProgram: CoreTACProgram,
            solveWithSplitting: suspend (CoreTACProgram, LocalSettings) -> Verifier.VerifierResult
        ): Verifier.VerifierResult {
            return TimeoutCracker(tacProgram = tacProgram).crack(solveWithSplitting)
        }
    }
}
