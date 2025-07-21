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

import config.Config
import config.ConfigType
import config.LocalSettings
import datastructures.EnumSet
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import smt.*
import smt.solverscript.SmtTheory
import smt.solverscript.VcFeature
import solver.ArithmeticOperations
import solver.SolverChoice
import solver.SolverConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger(LoggerTypes.SOLVERS)

/**
 * Holds configuration for [LExpVcChecker] specific to counterexample
 * postprocessing.
 */
data class LExpVcPostprocessingConfig(
    val postProcessCEXTimeout: Duration,
    val postProcessCEXSingleCheckTimeout: Duration,
    val prettifyCEX: PrettifyCEXEnum,
    val prettifyCEXVariables: List<String>,
    val multipleCEX: MultipleCEXStrategyEnum,
) {
    /**
     * Indicates whether we will do any kind of postprocessing on
     * counterexamples, prettification or diversification.
     */
    val doCEXPostProcessing: Boolean
        get() = prettifyCEX != PrettifyCEXEnum.NONE || multipleCEX != MultipleCEXStrategyEnum.NONE
}

/**
 * Holds configuration information for an instance of [LExpVcChecker].
 * The main benefit of this is to introduce a decoupling layer between the options in [Config] and [LExpVcChecker].
 * This allows us to e.g. process the options and/or create our own configurations.
 */
data class LExpVcCheckerConfig(
    val vcName: String,
    val backendStrategy: BackendStrategyEnum,
    val useBV: Boolean,
    val useLIA: UseLIAEnum,
    val useNIA: Boolean,
    val bvSolvers: SolverChoice,
    val liaSolvers: SolverChoice,
    val niaSolvers: SolverChoice,
    val configSolverChoice: SolverChoice,
    val skipDelayedSolvers: Boolean,
    val solverWaitingRoundsBeforeSkip: Int,
    val adaptiveUseBitVectorTheory: Boolean,
    val partialNIASolvers: SolverChoice,
    val partialNIASelectorDepth: Int,
    val configLiaBeforeBv: Boolean,
    val hashingScheme: HashingScheme,
    val configMemLimitBytes: Long?,
    val incrementalMode: Boolean,
    val dumpSmtFiles: Boolean,
    val postprocessingConfig: LExpVcPostprocessingConfig,
    val skipCallsToSolver: Boolean,
    val simulateTimeout: Boolean,
    val verifyWith: List<ConstraintChooser>,
    val useZ3WithPreprocessor: Boolean,
    val z3PreprocessorTimeout: Duration,
    val niaIsImprecise: Boolean,
    val cegarConfig: CEGARConfig,
    val parallelSplitterLIASolvers: SolverChoice,
    val parallelSplitterNIASolvers: SolverChoice,
    val CEGARConstraintChooser: ConstraintChooser,
    val CEGARSolvers: Int,
    val CEGARExactSolvers: Int,
    val CEGARPlusNIA: Boolean,
    val CEGARNIARelaxations: Int,
    val CEGARModelDiff: Boolean,
    val CEGARModelDiffThreshold: Int,
    val CEGARLearn: Boolean,
    val usedFeatures: Collection<VcFeature>,
) {
    // TODO: Add a command line parameter
    val constrainedSolversTimeLimitSec : Long = 15L

    companion object {
        private const val oneMeg = 1024 * 1024L

        /**
         * For the given [targetTheory] and [timeout] chooses optimal list of solvers.
         */
        private fun getDefaultSolvers(targetTheory: ArithmeticOperations, timeout: Duration): SolverChoice {
            return when (targetTheory) {
                ArithmeticOperations.BitVector -> {
                    SolverChoice.BvCommonAvailableSolversWithClOptions
                }
                ArithmeticOperations.LinearOnly -> {
                    if (timeout <= SolverConfig.lowTimeoutBound) {
                        SolverChoice.LIASolversSmallTimeout
                    } else if (timeout <= SolverConfig.highTimeoutBound) {
                        SolverChoice.LIASolversMediumTimeout
                    } else {
                        SolverChoice.LIASolversLargeTimeout
                    }
                }
                ArithmeticOperations.NonLinear -> {
                    if (timeout <= SolverConfig.lowTimeoutBound) {
                        SolverChoice.NIASolversSmallTimeout
                    } else if (timeout <= SolverConfig.highTimeoutBound) {
                        SolverChoice.NIASolversMediumTimeout
                    } else {
                        SolverChoice.NIASolversLargeTimeout
                    }
                }
                ArithmeticOperations.Any -> {
                    SolverChoice.AllCommonAvailableSolversWithClOptions
                }
                ArithmeticOperations.None -> {
                    SolverChoice(listOf())
                }
            }
        }

        /**
         * Builds a [SolverChoice] based on a command line option [solvers], the [targetTheory] and a [timeout].
         * This method combines the list of solvers from a theory-specific command line option given as [solvers]
         * (smt_LIASolvers, smt_NIASolvers, smt_BVSolvers, or smt_niaInLia), the generic command line option
         * [Config.SolverProgramChoice], whether these shall be used to filter or to override based on
         * [Config.OverrideSolvers], and the target theory. In some cases, specifically for niaInLia, we don't want
         * to fall back on our predefined solvers from [getDefaultSolvers], but instead return an empty list. This is
         * triggered by setting [emptyByDefault] to true.
         * It proceeds as follows:
         * 1) if [solvers] is specified: return the predefined list, filtered or overridden by [solvers]
         * 2) if [Config.SolverProgramChoice] is specified:
         *  2a) if [emptyByDefault]: return empty list
         *  2b) otherwise: : return the predefined list, filtered or overridden by [Config.SolverProgramChoice]
         * 3) if [emptyByDefault] return empty list, otherwise the predefined list
         */
        private fun usersSolversOrDefault(
            solvers: ConfigType.SolverProgramCmdLine,
            timeout: Duration,
            logicFeature: SolverConfig.LogicFeatures,
            solverChoiceDefault: Boolean,
            emptyByDefault: Boolean = false,
            overrideSolvers: Boolean = Config.Smt.OverrideSolvers.get(),
            solverProgramChoice: List<SolverConfig> = Config.SolverProgramChoice.get().toList()
        ): SolverChoice {
            val targetTheory = logicFeature.arithmeticOperations
            fun filterSolverConfigs(predefined: SolverChoice, userConfigs: Collection<SolverConfig>) = SolverChoice(
                if (overrideSolvers) {
                    userConfigs.also {
                        logger.info { "Use solvers for  ($targetTheory, $timeout): $it (by override)" }
                    }
                } else {
                    (predefined.filter { userConfigs.contains(it) } + userConfigs.filter {
                        !predefined.contains(it) && it.qualifiesFor(logicFeature)
                    }).also {
                        logger.info { "Use solvers for ($targetTheory, $timeout): $it" }
                    }
                }
            )

            val defaultSolvers = { getDefaultSolvers(targetTheory, timeout) }
            return if (!solvers.isDefault()) {
                // Case when specialized flag for the given theory was chosen
                filterSolverConfigs(defaultSolvers(), solvers.get().toList())
            } else if (!solverChoiceDefault) {
                // Case when specialized flag for the given theory wasn't entered but -s flag was
                if (emptyByDefault) {
                    SolverChoice(listOf())
                } else {
                    filterSolverConfigs(defaultSolvers(), solverProgramChoice)
                }
            } else { // no flag was used, use the predefined list
                if (emptyByDefault) {
                    SolverChoice(listOf())
                } else {
                    defaultSolvers()
                }
            }
        }

        fun filterByHashingScheme(
            candidates: SolverChoice,
            hashingScheme: HashingScheme,
            usedFeatures: Collection<VcFeature>
        ): SolverChoice {
            return when (hashingScheme) {
                HashingScheme.Datatypes -> SolverChoice(candidates
                    .solverInfoClOptions.filterTo(mutableSetOf()) {
                        it.solverInfo.supportsLogicFeatures(
                            SolverConfig.LogicFeatures(
                                ArithmeticOperations.Any,
                                usesDatatypes = true,
                                usesQuantifiers = VcFeature.Quantification in usedFeatures
                            )
                        )
                    }
                )

                else -> candidates
            }
        }

        fun fromGlobalConfig(
            vcName: String,
            usedFeatures: EnumSet<VcFeature>,
            timeout: Duration,
            containsStorageComparisons: Boolean? = null
        ): LExpVcCheckerConfig {
            val backendStrategy = Config.Smt.BackendStrategy.get()
            val useLIA = Config.Smt.UseLIA.get()
            val useNIA = Config.Smt.UseNIA.get()
            val useBV = Config.Smt.UseBV.get()

            val skipDelayedSolvers = Config.Smt.SkipDelayedSolvers.get()

            val solverWaitingRoundsBeforeSkip = Config.Smt.SolverWaitingRoundsBeforeSkip.get()

            val configLiaBeforeBv = Config.LiaBeforeBv.get()

            val hashingScheme = Config.HashingScheme.get().let { scheme ->
                when {
                    containsStorageComparisons == null -> {
                        logger.warn {
                            "Calling `fromGlobalConfig` without a parameter for `containsStorageComparison`."
                        }
                        scheme
                    }

                    containsStorageComparisons && scheme == HashingScheme.PlainInjectivity -> {
                        logger.warn {
                            "In $vcName, ${HashingScheme.PlainInjectivity.CONFIG_KEYWORD} changed to " +
                                "${HashingScheme.Datatypes.CONFIG_KEYWORD} because of the presence of storage comparisons."
                        }
                        HashingScheme.Datatypes
                    }

                    else -> scheme
                }
            }

            val configSolverChoice = filterByHashingScheme(SolverChoice(Config.SolverProgramChoice.get().toList()), hashingScheme, usedFeatures)
            check(configSolverChoice.isNotEmpty()) { "No solvers found when trying to check a rule ($vcName)." }
            LExpVcChecker.logSolverChoice(configSolverChoice, SolverChoice.AllCommonAvailableSolversWithClOptions)

            // determine solver memlimit (in bytes)
            val configMemLimitBytes: Long? = (Config.SolverMemLimit.get() * oneMeg).let { solverMemLimit ->
                if (solverMemLimit > 0) {
                    // limit was explicitly set, use it
                    solverMemLimit
                } else {
                    val availableRam = Config.AvailableRam.get() * oneMeg
                    if (availableRam > 0) {
                        // limit was not explicitly set, but available ram info is given -- compute the limit
                        val maxHeapSpace = Runtime.getRuntime().maxMemory()
                        val workers = System.getProperty("cvt.default.parallelism")?.toIntOrNull()
                            ?: Runtime.getRuntime().availableProcessors()
                        val limitPre = java.lang.Long.max(0, (availableRam - maxHeapSpace) / workers)
                        // leave 10% of RAM to the OS, at least 1G
                        val osAmount = java.lang.Long.max((limitPre * 0.1).toLong(), 1024 * oneMeg)
                        val limit = limitPre - osAmount
                        logger.info { "Setting per-solver memory limit to $limit bytes." }
                        logger.info {
                            "  Details: RAM: $availableRam ; Java max heap space: $maxHeapSpace ; " +
                                "#workers: $workers ; RAM left for OS: $osAmount"
                        }
                        limit
                    } else {
                        // null means "no limit"
                        null
                    }
                }
            }

            val incrementalMode =
                Config.prettifyCEX.get() != PrettifyCEXEnum.NONE ||
                    Config.MultipleCEXStrategy.get() != MultipleCEXStrategyEnum.NONE

            val dumpSmtFiles = !Config.ShouldDeleteSMTFiles.get()

            val postProcessCEXTimeout = Config.PostProcessCEXTimeoutSeconds.get().seconds

            val postProcessCEXSingleCheckTimeout = Config.PostProcessCEXSingleCheckTimeout.get().seconds

            val prettifyCEXVariables = Config.PrettifyCEXVariables.get()

            val prettifyCEX = Config.prettifyCEX.get()

            val multipleCEX = Config.MultipleCEXStrategy.get()

            val skipCallsToSolver = Config.SkipCallsToSolver.get()

            val simulateTimeout = Config.SimulateTimeout.get()

            val verifyWith = listOf(
                ConstraintChooser.TakeAll,
                ConstraintChooser.BoolsAndManyMore,
                ConstraintChooser.FewBoolsAndManyMore,
                ConstraintChooser.JustBools,
            )

            val useZ3WithPreprocessor = Config.UseZ3WithPreprocessor.get()

            val z3PreprocessorTimeout = Config.Z3PreprocessorTimeout.get().seconds

            val niaSolvers = usersSolversOrDefault(
                Config.NIAsolvers,
                timeout,
                SolverConfig.LogicFeatures(
                    SmtTheory.ArithmeticOperations.NonLinear.toSolverConfigArithOps(),
                    hashingScheme is HashingScheme.Datatypes,
                    VcFeature.Quantification in usedFeatures
                ),
                solverChoiceDefault = Config.SolverProgramChoice.isDefault()
            )

            val liaSolvers = usersSolversOrDefault(
                Config.LIAsolvers,
                timeout,
                SolverConfig.LogicFeatures(
                    SmtTheory.ArithmeticOperations.LinearOnly.toSolverConfigArithOps(),
                    hashingScheme is HashingScheme.Datatypes,
                    VcFeature.Quantification in usedFeatures
                ),
                solverChoiceDefault = Config.SolverProgramChoice.isDefault()
            )

            val bvSolvers = filterByHashingScheme(usersSolversOrDefault(
                    Config.BVsolvers,
                    timeout,
                    SolverConfig.LogicFeatures(
                        SmtTheory.ArithmeticOperations.BitVector.toSolverConfigArithOps(),
                        hashingScheme is HashingScheme.Datatypes,
                        VcFeature.Quantification in usedFeatures
                    ),
                    solverChoiceDefault = Config.SolverProgramChoice.isDefault()
                ), hashingScheme, usedFeatures
            )


            val parallelSplitterLIASolvers = Config.ParallelSplitterLIASolvers.get()

            val parallelSplitterNIASolvers = Config.ParallelSplitterNIASolvers.get()

            val partialNIASolvers = SolverChoice(Config.PartialNIASolvers.get().toList())

            val partialNIASelectorDepth = Config.PartialNIASelectorDepth.get()

            val cegarConfig = Config.cegarConfig.get()

            val CEGARPlusNIA = Config.CEGARPlusNIA.get()

            val CEGARNIARelaxations = Config.CEGARNIARelaxations.get()

            val CEGARModelDiff = Config.CEGARModelDiff.get()

            val CEGARModelDiffThreshold = Config.CEGARModelDiffThreshold.get()

            val CEGARLearn = Config.CEGARLearn.get()

            val CEGARConstraintChooser = ConstraintChooser.fromConstraintChooserEnum(Config.CEGARConstraintChooser.get())

            val CEGARSolvers = Config.CEGARSolvers.get()

            val CEGARExactSolvers = Config.CEGARExactSolvers.get()

            return LExpVcCheckerConfig(
                vcName = vcName,
                backendStrategy = backendStrategy,
                useBV = useBV,
                useLIA = useLIA,
                useNIA = useNIA,
                bvSolvers = bvSolvers,
                liaSolvers = liaSolvers,
                niaSolvers = niaSolvers,
                configSolverChoice = configSolverChoice,
                skipDelayedSolvers = skipDelayedSolvers,
                solverWaitingRoundsBeforeSkip = solverWaitingRoundsBeforeSkip,
                adaptiveUseBitVectorTheory = useBV,
                partialNIASolvers = partialNIASolvers,
                partialNIASelectorDepth = partialNIASelectorDepth,
                configLiaBeforeBv = configLiaBeforeBv,
                hashingScheme = hashingScheme,
                configMemLimitBytes = configMemLimitBytes,
                incrementalMode = incrementalMode,
                dumpSmtFiles = dumpSmtFiles,
                postprocessingConfig = LExpVcPostprocessingConfig(
                    prettifyCEX = prettifyCEX,
                    postProcessCEXTimeout = postProcessCEXTimeout,
                    postProcessCEXSingleCheckTimeout = postProcessCEXSingleCheckTimeout,
                    prettifyCEXVariables = prettifyCEXVariables.toList(),
                    multipleCEX = multipleCEX,
                ),
                skipCallsToSolver = skipCallsToSolver,
                simulateTimeout = simulateTimeout,
                verifyWith = verifyWith,
                useZ3WithPreprocessor = useZ3WithPreprocessor,
                z3PreprocessorTimeout = z3PreprocessorTimeout,
                cegarConfig = cegarConfig,
                CEGARConstraintChooser = CEGARConstraintChooser,
                CEGARSolvers = CEGARSolvers,
                CEGARExactSolvers = CEGARExactSolvers,
                CEGARPlusNIA = CEGARPlusNIA,
                CEGARNIARelaxations = CEGARNIARelaxations,
                CEGARModelDiff = CEGARModelDiff,
                CEGARModelDiffThreshold = CEGARModelDiffThreshold,
                CEGARLearn = CEGARLearn,
                niaIsImprecise = useBV,
                parallelSplitterNIASolvers = SolverChoice(parallelSplitterNIASolvers.toList()),
                parallelSplitterLIASolvers = SolverChoice(parallelSplitterLIASolvers.toList()),
                usedFeatures = usedFeatures,
            )
        }

        fun fromGlobalConfigWithTACConfig(
            vcName: String,
            usedFeatures: EnumSet<VcFeature>,
            containsStorageComparisons: Boolean,
            timeout: Duration,
            tacVerifierSettings: TACVerifierConfig.TACVerifierSettings?
        ): LExpVcCheckerConfig {

            val globalConfig = fromGlobalConfig(
                vcName, usedFeatures, timeout, containsStorageComparisons
            )
            if (tacVerifierSettings == null) {
                return globalConfig
            }

            return globalConfig.copy(
                backendStrategy = BackendStrategyEnum.SINGLE_RACE,   //we're using a modified configuration,
                useLIA = tacVerifierSettings.useLIA ?: globalConfig.useLIA,
                useNIA = tacVerifierSettings.useNIA ?: globalConfig.useNIA,
                liaSolvers = tacVerifierSettings.liaSolvers ?: globalConfig.liaSolvers,
                niaSolvers = tacVerifierSettings.niaSolvers ?: globalConfig.niaSolvers,
                configSolverChoice = tacVerifierSettings.configSolverChoice ?: globalConfig.configSolverChoice,
            )
        }

        fun fromLocalSettings(
            vcName: String,
            usedFeatures: EnumSet<VcFeature>,
            containsStorageComparisons: Boolean,
            timeout: Duration,
            localSettings: LocalSettings,
            tacVerifierSettings: TACVerifierConfig.TACVerifierSettings? = null,
        ): LExpVcCheckerConfig {
            val config = if (tacVerifierSettings == null) {
                fromGlobalConfig(vcName, usedFeatures, timeout, containsStorageComparisons)
            } else {
                fromGlobalConfigWithTACConfig(vcName, usedFeatures, containsStorageComparisons, timeout, tacVerifierSettings)
            }

            val configSolverChoice = filterByHashingScheme(SolverChoice(Config.SolverProgramChoice.get().toList()),
                config.hashingScheme, usedFeatures)

            val niaSolvers = usersSolversOrDefault(
                Config.NIAsolvers,
                timeout,
                SolverConfig.LogicFeatures(
                    SmtTheory.ArithmeticOperations.NonLinear.toSolverConfigArithOps(),
                    config.hashingScheme is HashingScheme.Datatypes,
                    VcFeature.Quantification in usedFeatures
                ),
                overrideSolvers = localSettings.overrideSolvers,
                solverProgramChoice = localSettings.solverProgramChoice.toList(),
                solverChoiceDefault = localSettings.solverProgramChoiceDefault
            )

            val liaSolvers = usersSolversOrDefault(
                Config.LIAsolvers,
                timeout,
                SolverConfig.LogicFeatures(
                    SmtTheory.ArithmeticOperations.LinearOnly.toSolverConfigArithOps(),
                    config.hashingScheme is HashingScheme.Datatypes,
                    VcFeature.Quantification in usedFeatures
                ),
                overrideSolvers = localSettings.overrideSolvers,
                solverProgramChoice = localSettings.solverProgramChoice.toList(),
                solverChoiceDefault = localSettings.solverProgramChoiceDefault
            )

            return config.copy(
                liaSolvers = liaSolvers,
                niaSolvers = niaSolvers,
                configSolverChoice = configSolverChoice,
                parallelSplitterLIASolvers = localSettings.parallelSplitterLIASolvers,
                parallelSplitterNIASolvers = localSettings.parallelSplitterNIASolvers,
                useNIA = localSettings.nonLinearArithmetic,
                useLIA = localSettings.useLIA,
                backendStrategy = localSettings.backendStrategy
            )
        }
    }
}

