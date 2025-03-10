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

import datastructures.stdcollections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import log.*
import parallel.*
import parallel.coroutines.*
import smt.*
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.VcFeature
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import smt.solverscript.functionsymbols.TheoryFunctionSymbol
import smtlibutils.cmdprocessor.*
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult.Companion.InterruptReason
import smtlibutils.data.*
import smtlibutils.statistics.*
import solver.*
import statistics.*
import statistics.data.DifficultyStatsCollector
import utils.*
import vc.data.*
import vc.gen.LExpVC
import verifier.CreateAxiomGeneratorContainer.Config
import verifier.CreateAxiomGeneratorContainer.Config.*
import verifier.cegar.CEGARCoordinator
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = Logger(LoggerTypes.SOLVERS)

/**
 * Checks a verification condition (VC, [LExpVC]).
 *
 * This entails
 *  - configuring and executing axiomatization (done in [LExpressionToSmtLib])
 *    - configuring includes setting up the right target theory (BV, NIA, etc), picking the correct axiom
 *      generators
 *  - configuring, orchestrating and running smt checkers ([SmtFormulaChecker])
 *    - configuring includes picking which terms to query the model for
 *    - orchestration includes picking solvers, handling of approximations (LIA, CEGAR, etc)
 *  - post processing and interpreting the solver outputs
 *    - post processing includes model refinement (for prettier models)
 *    - interpreting includes transforming a "sat" answer to the solver into an "unknown" answer wrt the VC in case of
 *       an overapproximating formula
 *
 * @param extraAxioms a way to pass some extra [SmtExp]s that are to be asserted in the VC smt script (used for proof
 *      artifacts)
 */
class LExpVcChecker(
    val timeout: Duration,
    val vc: LExpVC,
    val extraAxioms: List<HasSmtExp>,
    val lxf: LExpressionFactory,
    private val vcGenStopWatch: StopWatch?,
    private val solverStopWatch: StopWatch?,
    private val dumpFilePathFromName: (String) -> String,
    val config: LExpVcCheckerConfig,
    private val ruleName: String = config.vcName,
    val diffStats: DifficultyStatsCollector? = null,
) {

    /** Bundles the data returned from [runSolvers] */
    data class RunSolverResult(
        /** The checker result of the main checkSat call */
        val coreResult: SmtFormulaCheckerResultWithChecker,
        /** The list of results obtained via diversification and prettification, as a lazily executed [Flow]. */
        val diversifiedResults: Flow<SmtFormulaCheckerResult>,
        /** The statistics from the prettification, becoming available once [diversifiedResults] have been consumed. */
        val prettifierStatistics: Deferred<PrettifierStatistics>,
        /** The statistics from the diversifier, becoming available once [diversifiedResults] have been consumed. */
        val diversifierStatistics: Deferred<DiversifierStatistics>,
    )

    companion object {

        private var loggedSolverChoice = false

        /** Specifies how long we wait after the regular solver timeout has elapsed until we kill the solver from the
         * outside. */
        val externalSolverTimeoutPad: Duration = 2.seconds

        val script = FactorySmtScript

        /** Log which solvers were both chosen by the user (or by default) _and_ are available on the machine. */
        fun logSolverChoice(solverChoice: SolverChoice, allAvailableSolvers: SolverChoice) {
            if (loggedSolverChoice) return
            val kvlActualSolverChoice = ScalarKeyValueStats<String>()
            kvlActualSolverChoice.addKeyValue("available_solvers", allAvailableSolvers.toConciseString())
            kvlActualSolverChoice.addKeyValue("used_solvers", solverChoice.toConciseString())
            SDCollectorFactory.collector().collectFeature(kvlActualSolverChoice)
            loggedSolverChoice = true
        }
    }

    val usedFeatures = lxf.getUsedFeatures()

    private val statsLogger = LExpVCStatsLogger()

    private var raceIdCounter = 0
    private fun registerNewRace(
        result: SmtFormulaCheckerResult,
        raceDescription: String,
        runtime: Duration,
        timeout: Duration,
        raceStartTime: TimeSinceStart,
    ) = result.registerToRace(raceIdCounter++, raceDescription, runtime, timeout, raceStartTime)

    private val learnedClausesStats = LearnedClausesFilterStatistics()
    fun getLearnedClausesStats() = learnedClausesStats

    suspend fun generateQuery(setup: LExpToSmtSetupInfo, suffixName: String = ""): SmtFormulaCheckerQuery {
        vcGenStopWatch?.start()
        val localLxf = lxf.copy()
        val query = LExpressionToSmtLib(
            ruleName,
            LExpressionToSmtLibScene(
                localLxf,
                setup.createAxiomGeneratorContainer.create(localLxf, setup.targetTheory),
                setup.targetTheory,
                setup.hashingScheme,
                setup.createExpNormalizer(localLxf),
            ),
            vc,
            diffStats
        ).output()
        vcGenStopWatch?.stop()

        if (config.dumpSmtFiles) {
            PrintingFormulaChecker.printer(
                script,
                dumpFilePathFromName("$ruleName-${query.logic.keyword}$suffixName"),
                CmdProcessor.Options.fromQuery(query, false)
            ).use { it.check(query, PreExecutionStatistics(ConfigStatistics("PrintingFormulaChecker"), listOf())) }
        }
        return query
    }

    /**
     * creates and arranges [Executable] by solver name and then mix (first from each name, second, etc).
     */
    fun generateExecutables(
        raceDescription: String,
        queryToConfigs: List<Pair<SmtFormulaCheckerQuery, List<SolverConfig>>>,
        winsRace: (SatResult) -> Boolean = { it !is SatResult.UNKNOWN },
        interpretation: Interpretation = Interpretation.Standard
    ) =
        queryToConfigs.flatMap { (query, configs) ->
            configs.distinct().map { config ->
                Executable(raceDescription, query, script, config, winsRace, interpretation)
            }
        }
            .groupBy { it.config.fullName }
            .map { it.value }
            .flatZip()

    /**
     * Runs one parallel race between different solver configurations
     */
    private suspend fun raceSolvers(
        raceDescription: String,
        executables: List<Executable>,
        keepWinnerAliveIfSat: Boolean = config.postprocessingConfig.doCEXPostProcessing
    ): SmtFormulaCheckerResultWithChecker {

        /**
         * In case that exact solvers in the race did timeout, we have to check if not-exact ones at least found
         * a counterexample to verify. If so, we set it as the final result of the race.
         * There could be several counterexamples found. Currently, we take the first one only.
         */
        fun findRaceResult(results: List<SmtFormulaCheckerResultWithChecker>): SmtFormulaCheckerResultWithChecker? {
            return results.find { (it.result.satResult as? SatResult.UNKNOWN)
                ?.reason is SatResult.UnknownReason.Overapproximation }
        }

        fun getTimeout(exec : Executable): Duration {
            return (exec.config.timelimit ?: timeout) + externalSolverTimeoutPad
        }

        logger.info {
            "Starting $raceDescription($ruleName), timeout = ${timeout.inWholeSeconds}, " +
                    "order = ${executables.map { it.config.fullName }}"
        }
        if (config.skipCallsToSolver) {
            // TODO: Create a special type of result for this?
            val result = SmtFormulaCheckerResult.NoSolvers(executables.first().query.info, PreExecutionStatistics(ConfigStatistics("NoSolvers"), listOf()))
            registerNewRace(result, raceDescription, 0.milliseconds, 0.milliseconds, TimeSinceStart())
            return result.withNullChecker()
        }
        if (config.simulateTimeout) {
            val result =
                SmtFormulaCheckerResult.Interrupted(
                    e = null,
                    checkerInstance = null,
                    query = executables.first().query,
                    reason = InterruptReason.TIMEOUT,
                    preExecutionStatistics = PreExecutionStatistics(ConfigStatistics("SimulateTimeout"), listOf()),
                    stats = null,
                )
            registerNewRace(result, raceDescription, 0.milliseconds, 0.milliseconds, TimeSinceStart())
            return result.withNullChecker()
        }



        solverStopWatch?.start()

        val raceStartTime = TimeSinceStart()
        val resultInfo = measureTimedValue {
                ParallelPool.inherit(ParallelPool.SpawnPolicy.GLOBAL) {
                    it.rpcRace(
                        executables.map {
                            val timeout = getTimeout(it)

                            /** Based on [SolverConfig.canBeSkipped], some [Executable] can be skipped from the race if there
                             * are no available cores/threads for them to run. In particular, [LExpVcCheckerConfig.solverWaitingRoundsBeforeSkip]
                             * defines the maximal number of "rounds" an executable can wait before it could be skipped,
                             * where by "round" here we mean the timeout for the Executable. So, the maximal waiting time is
                             * [config.solverWaitingRoundsBeforeSkip * timeout.toSeconds() + 1] seconds, where the 1 second is
                             * an extra pad for each round.
                             */
                            val maxAllowedDelayInRace =
                                config.solverWaitingRoundsBeforeSkip * timeout + 1.seconds

                            check(maxAllowedDelayInRace >= 0.seconds) { "Got a negative max waiting time before skip" }
                            val canBeSkipped = config.skipDelayedSolvers && it.config.canBeSkipped(
                                it.query.logic.toSolverConfigLogicFeatures(), timeout
                            )
                            val critical = !canBeSkipped || it === executables.first()
                            Racer(
                                thunk = { it.exec(critical) },
                                resultOnNoExit = SmtFormulaCheckerResult.Single.Cancelled(
                                    it.checkerInstance?.solverInstanceInfo,
                                    it.query.info,
                                    preExecutionStatistics = PreExecutionStatistics(it.config.getConfigStats(), listOf())
                                ).withNullChecker(),
                                timeout = timeout,
                                maximumAllowedDelay = if (canBeSkipped) maxAllowedDelayInRace else null
                            )
                        }
                    )
                }
        }
        var (whoWon, ress) = resultInfo.value
        val solvingTime = resultInfo.duration

        solverStopWatch?.stop()

        // If a result is a result of an approximation, it should be marked as so (no matter if it
        // won this specific race or not)
        val results = ress.zip(executables).map { (res, exe) ->
            val makeInterrupt: (InterruptReason) -> SmtFormulaCheckerResultWithChecker =
                { reason: InterruptReason ->
                    exe.interrupted(
                        null,
                        reason,
                        preExecStats = (res.res?.result as? SmtFormulaCheckerResult.Single)?.preExecutionStatistics
                            ?: PreExecutionStatistics(ConfigStatistics(), listOf()),
                        ResultStatistics(
                            solverWallRuntime = exe.timeUsed?.milliseconds,
                            startTime = exe.execStartTime,
                            threadName = exe.execThreadName
                        )
                    )
                }
            when (res) {
                is RacerResult.FromJob -> exe.interpret(res.res)
                is RacerResult.LostRace -> makeInterrupt(InterruptReason.LOST_RACE)
                is RacerResult.Skipped -> makeInterrupt(InterruptReason.SKIPPED)
                is RacerResult.SkippedDelayed -> makeInterrupt(InterruptReason.SKIPPED_DELAYED)
                is RacerResult.Timeout -> makeInterrupt(InterruptReason.EXTERNAL_TIMEOUT)
            }
        }
        if (whoWon < 0) { // no winner so far
            findRaceResult(results)?.let { whoWon = results.indexOf(it) }
        }

        val (result, checker) = if (whoWon >= 0) {
            SmtFormulaCheckerResult.Agg.LExpVcCheckerResult(
                results[whoWon].result as SmtFormulaCheckerResult.Single,
                results
                    .filterIndexed { index, _ -> index != whoWon }
                    .filterNotNull()
                    .map { it.result as SmtFormulaCheckerResult.Single }
            ) to results[whoWon].checker
        } else {
            SmtFormulaCheckerResult.Agg.LExpVcCheckerResult(
                null, results.filterNotNull().map { it.result as SmtFormulaCheckerResult.Single }
            ) to null
        }

        val finalRes = result.finalRes as? SmtFormulaCheckerResult.Single.Simple
        if (finalRes == null) {
            "No winner"
        } else {
            "Winner = ${finalRes.solverInstanceInfo!!.name}, got = ${finalRes.satResult}"
        }.let {
            logger.info { "$raceDescription($ruleName)[timeout = ${timeout.inWholeSeconds}] $it" }
        }
        results.zip(executables).forEach { (res, exec) ->
            statsLogger.log(exec, timeout, res.result as SmtFormulaCheckerResult.Single)
        }

        if (keepWinnerAliveIfSat && result.satResult is SatResult.SAT) {
            results.closeAllExcept(whoWon)
        } else {
            results.closeAll()
        }
        registerNewRace(result, raceDescription, solvingTime, timeout, raceStartTime)

        return result.withChecker(checker)
    }


    /**
     * Takes a list of pairs, each pair is a [LExpToSmtSetupInfo] and a list of [SolverConfig]. Creates a list
     * of [Executable]s and runs the race
     *
     * Duplicates are removed from the [SolverConfig], and [Executable]s are ordered in a mixed order, according
     * to solver name.
     * TODO: create an order according to real statistics (perhaps per project even).
     */
    private suspend fun raceSetupsAndConfigs(
        raceDescription: String,
        setupToConfigs: List<Pair<LExpToSmtSetupInfo, List<SolverConfig>>>,
        winsRace: (SatResult) -> Boolean = { result -> result !is SatResult.UNKNOWN },
        interpretation: Interpretation = Interpretation.Standard,
        keepWinnerAliveIfSat: Boolean = config.postprocessingConfig.doCEXPostProcessing
    ): SmtFormulaCheckerResultWithChecker {
        check(setupToConfigs.isNotEmpty()) {
            "failed to find a solver configuration that fits the LExpVcChecker configuration"
        }
        val executables = generateExecutables(
            raceDescription,
            setupToConfigs.map { (setup, configs) ->
                generateQuery(setup) to configs
            },
            winsRace,
            interpretation,
        )
        check(executables.isNotEmpty()) {
            "none of the solver configurations fit the query, no `Executable`s were created. " +
                "You probably need to make a different choice of solvers. " +
                "Current choice is \"${config.configSolverChoice}\". " +
                "Solver setups and configs: \"$setupToConfigs\""
        }
        return raceSolvers(raceDescription, executables, keepWinnerAliveIfSat)
    }


    /**
     * Runs the default solvers for [setup]
     */
    private suspend fun simpleRace(
        raceDescription: String,
        setup: LExpToSmtSetupInfo,
        winsRace: (SatResult) -> Boolean = { result -> result !is SatResult.UNKNOWN },
        interpretation: Interpretation = Interpretation.Standard,
        keepWinnerAliveIfSat: Boolean = config.postprocessingConfig.doCEXPostProcessing
    ) = raceSetupsAndConfigs(
        raceDescription,
        listOf(
            setup to getSolverConfigs(
                when (setup.targetTheory.arithmeticOperations) {
                    SmtTheory.ArithmeticOperations.LinearOnly -> config.liaSolvers
                    SmtTheory.ArithmeticOperations.NonLinear -> config.niaSolvers
                    SmtTheory.ArithmeticOperations.BitVector -> config.bvSolvers
                    else -> SolverChoice.AllCommonAvailableSolversWithClOptions
                },
                setup.targetTheory
            )
        ),
        winsRace,
        interpretation,
        keepWinnerAliveIfSat
    )

    suspend fun quickSolve(): SmtFormulaCheckerResultWithChecker {
        val raceDescription = "quick"
        val presentTheories = getPresentTheories()

        fun niaGenerator() = NIAExecutableGenerator(this, raceDescription)

        val runLIA = true
        val runNIA = true
        val runBV = config.useBV

        val executables: List<Executable> = listOfNotNull(
            runIf(runNIA) {
                val solvers = config.parallelSplitterNIASolvers.map {
                    it.copy(name = "NIA-${it.name}", timelimit = timeout, incremental = config.incrementalMode)
                }
                niaGenerator().generateExecutables(presentTheories, solvers)
            },
            runIf(runLIA) {
                val solvers = config.parallelSplitterLIASolvers.map {
                    it.copy(name = "LIA-${it.name}", timelimit = timeout, incremental = config.incrementalMode)
                }
                LIAExecutableGenerator(this, raceDescription).generateExecutables(
                    presentTheories,
                    solvers,
                    cexVerifier = {
                        CEXVerifier(
                            listOf(
                                ConstraintChooser.TakeAll,
                                ConstraintChooser.BoolsAndManyMore
                            ),
                            SolverConfig.z3.default.copy(
                                name = "LIA-verify-${SolverConfig.z3.default.name}",
                                timelimit = timeout,
                                incremental = config.incrementalMode
                            ),
                            niaGenerator().generatedQuery.await(),
                            vc,
                            script
                        )
                    }
                )
            },
            runIf(runBV) {
                val solver = config.bvSolvers.first().let {
                    it.copy(name = "BV-${it.name}", timelimit = timeout, incremental = config.incrementalMode)
                }
                val oflChecks =
                    if (solver.solverInfo.supportsNewSmtLibBvOverflowSymbols) {
                        BvOverflowChecks.NewSmtLib
                    } else {
                        BvOverflowChecks.ViaDefineFun
                    }
                BVExecutableGenerator(this, raceDescription, oflChecks).generateExecutables(presentTheories, listOf(solver))
            },
        ).flatten()
        return raceSolvers(
            raceDescription,
            executables,
            true
        )
    }

    suspend fun runCoreSolvers(): SmtFormulaCheckerResultWithChecker = when(config.backendStrategy) {
        BackendStrategyEnum.ADAPTIVE -> {
            logger.info { "using adaptive solver config" }
            runAdaptive()
        }
        BackendStrategyEnum.CEGAR -> {
            logger.info { "using CEGAR" }
            runCEGAR()
        }
        BackendStrategyEnum.SINGLE_RACE -> {
            logger.info { "using singleRace solver config" }
            runSingleRaceMode()
        }
    }

    /**
     * Run the solvers on [vc] and postprocess the results, returning a [RunSolverResult]. This holds the original
     * result from checking the initial verification condition, a flow that produces the postprocessed results
     * (diversified and prettified, according to the configuration), as well (deferred) statistics about the solving.
     *
     * The usage of a flow allows for callers of this method to be very flexible in which results are used at what point
     * in time: calling `.single()` will only compute a single result; iterating the flow will produce the results
     * asynchronously, allowing e.g. to send them to the user as soon as a single CEX is available.
     * Note that this comes at the price of somewhat unintuitive execution order: the code within `flow { ... }` is only
     * executed when the caller of this method actually "evaluates" the flow. In particular, this method *returns*
     * before this happens. Therefore, the statistics are wrapped in `Deferred` such that (a) the caller can safely
     * check whether there is statistics data available and (b) the flow can put data into the statistics although the
     * method has already returned (and thus the returned statistics variable can no longer be changed directly).
     */
    suspend fun runSolvers(): RunSolverResult {
        val result = runCoreSolvers()

        return postprocessResult(vc, result, config.postprocessingConfig)
    }

    /**
     * Runs only LIA solvers by default. Since the LIA is an overapproximation, any SAT result for these solvers
     * is interpreted as UNKNOWN. It is possible to include also NIA solvers using the -smt_niaInLia flag.
     * The NIA is considered to be an overapproximation when bitvectors are used. In this case, all SAT results
     * from both NIA and LIA are ignored.
     */
    private suspend fun runLiaOverApproximationWithSomeNIAs(axiomGeneratorConfig : Config): SmtFormulaCheckerResultWithChecker {
        val liaSetup = generateSetup(axiomGeneratorConfig, SetupInfoType.UFLIAorAUFLIA)

        val liaExecutables = generateExecutables(
            "LIA_overApproximation",
            listOf(
                generateQuery(liaSetup) to getSolverConfigs(
                    config.liaSolvers,
                    liaSetup.targetTheory
                )
            ),
            interpretation = Interpretation.OverApproximation,
        )

        return raceSolvers("LIA_overApproximation", liaExecutables)
    }

    @Suppress("unused")
    suspend fun runPartialLiaOverApproximation(axiomGeneratorConfig : Config): SmtFormulaCheckerResultWithChecker {
        val selector: (LExpression) -> Boolean = { exp ->
            if (exp !is LExpression.ApplyExpr) true
            else {
                when (exp.f) {
                    is NonSMTInterpretedFunctionSymbol.Vec.Mul,
                    is TheoryFunctionSymbol.Vec.IntMul -> {
                        exp.args.size != 2 || exp.lhs.toString() < exp.rhs.toString()
                    }

                    else -> true
                }
            }
        }
        return simpleRace(
            "PartialLIA_overApproximation",
            generateSetup(axiomGeneratorConfig, SetupInfoType.UFLIAorAUFLIAPartial, selector),
            interpretation = Interpretation.OverApproximation,
            keepWinnerAliveIfSat = false
        )
    }

    private suspend fun runBvRace(axiomGeneratorConfig : Config) : SmtFormulaCheckerResultWithChecker {
        // the user has set the flag [Config.Smt.BitVectorTheory] explicitly (not by default) --> make a BV config
        // this should only happen when there are bit-wise operations present in the input
        logger.info { "${config.vcName}: adaptive solver configuration active; picking UFBV configuration" }

        // When bit-wise operations are present, both NIA and LIA encodings are overapproximation; hence, we return just in the UNSAT case.
        // (Here we race using the integer encoding, not the BV encoding.)
        if (config.configLiaBeforeBv) {
            runLiaOverApproximationWithSomeNIAs(axiomGeneratorConfig).let {
                if (it.result.satResult is SatResult.UNSAT) {
                    return it
                }
            }
        }

        val axiomGeneratorGeneratorConfigBv =
            if (config.hashingScheme is HashingScheme.Legacy)
                axiomGeneratorConfig.copy(hashingScheme = HashingScheme.DefaultBv)
            else
                axiomGeneratorConfig

        if (VcFeature.MulOverflowChecks in usedFeatures) {
            val (haveOflSymbols, dontHaveOflSymbols) =
                config.bvSolvers.partition { it.solverInfo.supportsNewSmtLibBvOverflowSymbols }

            /* There are overflow checks --> distinguish between solvers, make a VC with z3 specific overflow
             * checks and one with smtlib-compatible ones. */
            val withoutOflSymbolsSetup = runIf (
                config.configSolverChoice.any { !it.solverInfo.supportsNewSmtLibBvOverflowSymbols } &&
                    dontHaveOflSymbols.isNotEmpty()
            ) {
                generateSetup(
                    axiomGeneratorGeneratorConfigBv,
                    SetupInfoType.UFBVorAUFBV,
                    overflowChecks = BvOverflowChecks.ViaDefineFun
                )
            }

            val withOflSymbolsSetup = runIf (
                config.configSolverChoice.any { it.solverInfo.supportsNewSmtLibBvOverflowSymbols } &&
                    haveOflSymbols.isNotEmpty()
            ) {
                generateSetup(
                    axiomGeneratorGeneratorConfigBv,
                    SetupInfoType.UFBVorAUFBV,
                    overflowChecks = BvOverflowChecks.NewSmtLib
                )
            }

            return raceSetupsAndConfigs(
                "BV_race_WithOverFlowChecks",
                listOfNotNull(
                    withoutOflSymbolsSetup?.let {
                        it to getSolverConfigs(
                            SolverChoice(dontHaveOflSymbols),
                            withoutOflSymbolsSetup.targetTheory
                        )
                    },
                    withOflSymbolsSetup?.let {
                        it to getSolverConfigs(
                            SolverChoice(haveOflSymbols),
                            withOflSymbolsSetup.targetTheory
                        )
                    }
                )
            )
        } else {
            /* There are no overflow checks no need to distinguish between solvers. */
            return simpleRace(
                "BV_race_noOverflowChecks",
                generateSetup(axiomGeneratorGeneratorConfigBv, SetupInfoType.UFBVorAUFBV,
                    // TODO: check if the VcFeature.OverflowChecks detection is correct; then we could
                    //    pick "DontCare" here; using ViaDefineFun as a safe fallback in case detection failed
                    overflowChecks = BvOverflowChecks.ViaDefineFun)
            )
        }
    }

    fun niaIsPrecise(): Boolean {
        return !config.niaIsImprecise
    }

    private fun getPresentTheories(): HashSet<SetupInfoType> {
        val res: HashSet<SetupInfoType> = hashSetOf()
        if (config.useBV) {
            res.add(SetupInfoType.UFBVorAUFBV)
        }
        if (config.useLIA != UseLIAEnum.NONE) {
            res.add(SetupInfoType.UFLIAorAUFLIA)
        }
        if (config.useNIA) {
            res.add(SetupInfoType.UFNIAorAUFNIA)
        }
        if (config.partialNIASolvers.isNotEmpty()) {
            res.add(SetupInfoType.UFLIAorAUFLIAPartial)
        }
        return res
    }

    private suspend fun runSingleRaceMode(): SmtFormulaCheckerResultWithChecker {
        val raceDescription = "Combined_race"
        val presentTheories = getPresentTheories()

        fun SolverConfig.configure(withTimeout: Boolean = true) = copy(
            incremental = config.postprocessingConfig.doCEXPostProcessing,
            timelimit = if (withTimeout) { timeout } else { timelimit }
        )
        fun SolverChoice.configure(withTimeout: Boolean = true) = map { it.configure(withTimeout) }

        // [NIAExecutableGenerator] shared by verified LIA and NIA racers
        val NIAExec = runIf(config.useLIA == UseLIAEnum.WITH_VERIFIER || config.useNIA) {
             NIAExecutableGenerator(this, raceDescription)
        }

        val executables = listOfNotNull(
            runIf(config.useLIA == UseLIAEnum.WITHOUT_VERIFIER) {
                LIAExecutableGenerator(this, raceDescription, isPrecise = true).generateExecutables(
                    presentTheories,
                    config.liaSolvers.configure()
                )
            },
            runIf(config.useLIA == UseLIAEnum.UNSAT_ONLY) {
                LIAExecutableGenerator(this, raceDescription, isPrecise = false).generateExecutables(
                    presentTheories,
                    config.liaSolvers.configure()
                )
            },
            runIf(config.useLIA == UseLIAEnum.WITH_VERIFIER) {
                val verifierTimeout = minOf(timeout, config.constrainedSolversTimeLimitSec.seconds)
                val verifierConfig = Z3SolverInfo.plainConfig(verifierTimeout).configure(false)
                LIAExecutableGenerator(this, raceDescription).generateExecutables(presentTheories, config.liaSolvers,
                    cexVerifier = { CEXVerifier(config.verifyWith, verifierConfig, NIAExec!!.generatedQuery.await(), vc, script) }
                )
            },
            runIf(config.useNIA) {
                NIAExec!!.generateExecutables(presentTheories, config.niaSolvers.configure())
            },
            runIf(config.useBV) {
                if (VcFeature.MulOverflowChecks in usedFeatures) {
                    val (haveOflSymbols, dontHaveOflSymbols) =
                        config.bvSolvers.partition { it.solverInfo.supportsNewSmtLibBvOverflowSymbols }
                    BVExecutableGenerator(this, raceDescription, BvOverflowChecks.NewSmtLib)
                        .generateExecutables(presentTheories, SolverChoice(haveOflSymbols).configure()) +
                        BVExecutableGenerator(this, raceDescription, BvOverflowChecks.ViaDefineFun)
                            .generateExecutables(presentTheories, SolverChoice(dontHaveOflSymbols).configure())
                } else {
                    BVExecutableGenerator(this, raceDescription, BvOverflowChecks.DontCare)
                        .generateExecutables(presentTheories, config.bvSolvers.configure())
                }
            },
            runIf(!config.partialNIASolvers.isEmpty()) {
                PartialNIAExecutableGenerator(this, raceDescription)
                    .generateExecutables(presentTheories, config.partialNIASolvers.configure())
            },
        )

        // We want to interleave the solvers. Run first from LIA, first from NIA, etc.
        return raceSolvers(raceDescription, executables.flatZip()).also {
            if (it.result.raceInfo == null) {
                logger.info { "Statistics: Unable to group results to a race.  Statistics will be incomplete" }
            }
        }
    }

    private suspend fun runAdaptive(): SmtFormulaCheckerResultWithChecker {
        /* TODO: do some experiments or something to see if this even makes sense, or should be differentiated by VC
            (e.g. when we're runing both LIA and NIA) or so */
        val axiomGeneratorConfig = Config(
            config.hashingScheme,
            extraAxioms,
            // this will be overwritten in case of BV configs when there are overflow checks
            BvOverflowChecks.DontCare
        )
        var liaResult: SmtFormulaCheckerResultWithChecker? = null

        val raceResult =
            if (config.adaptiveUseBitVectorTheory) {
                runBvRace(axiomGeneratorConfig)
            } else { // No BV theory
                logger.info { "${config.vcName}: adaptive solver configuration active; picking UFLIA and UFNIA" }
                liaResult = runLiaOverApproximationWithSomeNIAs(axiomGeneratorConfig)
                when (val satRes = liaResult.result.satResult) {
                    is SatResult.UNSAT ->
                        liaResult

                    is SatResult.SAT ->
                        // The SAT result can only be returned by a NIA solver.
                        liaResult

                    is SatResult.UNKNOWN ->
                        if (satRes.reason is SatResult.UnknownReason.Overapproximation) {
                            liaResult.letIf(config.useLIA == UseLIAEnum.WITH_VERIFIER) {
                                runRaceUsingLiaCounterExample(it.result.getValuesResult!!, axiomGeneratorConfig)
                            }
                        } else {
                            simpleRace(
                                "NIA_race_after_LIA_failed_with_no_answer",
                                generateSetup(axiomGeneratorConfig, SetupInfoType.UFNIAorAUFNIA)
                            )
                        }
                }
            }

        if (raceResult.result.raceInfo == null) {
            logger.info { "Statistics: Unable to group results to a race.  Statistics will be incomplete"}
        }
        return if (raceResult.result is SmtFormulaCheckerResult.Agg.LExpVcCheckerResult &&
            liaResult?.result is SmtFormulaCheckerResult.Agg.LExpVcCheckerResult &&
            liaResult.result != raceResult.result
        ) {
            val mainRaceInfo = raceResult.result.raceInfo
            val liaRaceInfo = liaResult.result.raceInfo
            val rrr = raceResult.result as SmtFormulaCheckerResult.Agg.LExpVcCheckerResult
            rrr.finalRes?.raceInfo = mainRaceInfo
            rrr.otherResults.forEach { it.raceInfo = mainRaceInfo }
            val liaUnknownResults =
                (liaResult.result as SmtFormulaCheckerResult.Agg.LExpVcCheckerResult).let {
                    (it.unknownResults + it.finalRes).filterNotNull()
                }
            liaUnknownResults.forEach { it.raceInfo = liaRaceInfo }
            SmtFormulaCheckerResult.Agg.LExpVcCheckerResult(
                rrr.finalRes,
                rrr.otherResults + liaUnknownResults
            ).withChecker(raceResult.checker)
        } else {
            raceResult
        }

    }
    private fun generateSetup(
        axiomGeneratorConfig : Config,
        type: SetupInfoType, selector :((LExpression) -> Boolean) = { true },
        overflowChecks : BvOverflowChecks =
            BvOverflowChecks.ViaDefineFun) :LExpToSmtSetupInfo {
        return when(type) {
            SetupInfoType.UFBVorAUFBV -> LExpToSmtSetupInfo.UFBVorAUFBV(
                useArrayTheory = false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                axiomGeneratorConfig.copy(overflowChecks = overflowChecks)
            )
            SetupInfoType.UFNIAorAUFNIA -> LExpToSmtSetupInfo.UFNIAorAUFNIA(
                useArrayTheory = false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                axiomGeneratorConfig
            )
            SetupInfoType.UFLIAorAUFLIAPartial -> LExpToSmtSetupInfo.UFLIAorAUFLIAPartial(
                useArrayTheory = false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                axiomGeneratorConfig,
                selector
            )
            SetupInfoType.UFLIAorAUFLIA -> LExpToSmtSetupInfo.UFLIAorAUFLIA(
                useArrayTheory = false,
                VcFeature.Quantification in lxf.getUsedFeatures(),
                axiomGeneratorConfig
            )
        }
    }

    @JvmInline
    value class Interpretation(private val exp: (SmtFormulaCheckerResult.Single.Simple) -> SmtFormulaCheckerResult.Single.Simple) {
        companion object {
            val Standard: Interpretation = Interpretation { it }
            val OverApproximation: Interpretation = Interpretation {
                if (it.satResult is SatResult.SAT) {
                    it.copy(satResult = SatResult.UNKNOWN(SatResult.UnknownReason.Overapproximation(it)))
                } else {
                    it
                }
            }
            val UnderApproximation: Interpretation = Interpretation {
                if (it.satResult is SatResult.UNSAT) {
                    it.copy(satResult = SatResult.UNKNOWN(SatResult.UnknownReason.Underapproximation(it)))
                } else {
                    it
                }
            }
        }

        operator fun invoke(r: SmtFormulaCheckerResult.Single.Simple): SmtFormulaCheckerResult.Single.Simple {
            return exp.invoke(r)
        }
    }

    @Suppress("LocalVariableName")
    private suspend fun runCEGAR(): SmtFormulaCheckerResultWithChecker {
        val axiomGeneratorConfig = Config(
            config.hashingScheme,
            extraAxioms,
            BvOverflowChecks.ViaDefineFun
        )

        //LIA setup (cex generation), this one is gradually updated (we block spurious CEXs)
        val LIAquery =
            generateQuery(generateSetup(axiomGeneratorConfig, SetupInfoType.UFLIAorAUFLIA))

        //NIA setup (cex verification)
        val NIAquery =
            generateQuery(generateSetup(axiomGeneratorConfig, SetupInfoType.UFNIAorAUFNIA))

        return CEGARCoordinator.fromConfig(timeout, config, vc, script, LIAquery, NIAquery).run()
    }

    private suspend fun runRaceUsingLiaCounterExample(
        values: Map<SmtExp, SmtExp>,
        axiomGeneratorConfig: Config,
        useUnconstrainedSolvers: Boolean = true
    ): SmtFormulaCheckerResultWithChecker {
        val raceDescription = "NIA_race_using_LIA_counterexample"
        val setup = generateSetup(axiomGeneratorConfig, SetupInfoType.UFNIAorAUFNIA)
        val query = generateQuery(setup)
        val unconstrained = if (useUnconstrainedSolvers) {
            generateExecutables(
                raceDescription,
                listOf(query to getSolverConfigs(config.niaSolvers, setup.targetTheory)),
            )
        } else {
            emptyList()
        }

        val timeUpperBound = config.constrainedSolversTimeLimitSec.seconds

        val oneSolver: SolverConfig =
            Z3SolverInfo.plainConfig(
                minOf(timeout, timeUpperBound),
                incrementalMode = config.postprocessingConfig.doCEXPostProcessing
            )


        val cg = SmtConstraintsGen(query, vc, script)

        val constrained = config.verifyWith.map { chooser ->
                Executable(
                    raceDescription,
                    query.supplement(cg.getConstraintsFrom(values, chooser)),
                    script,
                    oneSolver.copy(name = "Constrained:${chooser.name}:${oneSolver.name}"),
                    winsRace = { it is SatResult.SAT },
                    interpretation = Interpretation.UnderApproximation,
                )
            }
        // give preference to the constrained executables.
        return raceSolvers(
            "NIA_race_using_LIA_counterexample",
            listOf(constrained, unconstrained).flatZip()
        )
    }

    /**
     * Returns a list of solver configurations according to parameters
     */
    private fun getSolverConfigs(
        solverChoice: SolverChoice,
        targetTheory: SmtTheory,
    ): List<SolverConfig> {
        val configs = mutableListOf<SolverConfig>()


        //adding all default solver configurations (filtered based on [solverChoice])
        configs.addAll(
            SolverConfig.getDefaultSolverConfigs(
                solverChoice,
                targetTheory.toSolverConfigLogicFeatures(),
                timeout,
                config.configMemLimitBytes,
                config.incrementalMode,
            )
        )

        if (solverChoice.isNotEmpty() && configs.isEmpty()) {
            logger.warn { "No solver configs qualify for ${targetTheory.toSolverConfigLogicFeatures()}" }
        }

        //add Z3 with CVC5 as a preprocessor
        if (config.useZ3WithPreprocessor) {
            val cvc5prepConfig = SolverConfig.cvc5.default.copy(
                timelimit = if (config.z3PreprocessorTimeout > timeout - 1.seconds) {
                    timeout - 1.seconds
                } else {
                    config.z3PreprocessorTimeout
                },
                memlimitBytes = config.configMemLimitBytes,
                incremental = config.incrementalMode,
                clOptions = listOf("--output=learned-lits")
            )
            configs.add(
                SolverConfig.z3.default.copy(
                    timelimit = timeout - config.z3PreprocessorTimeout,
                    memlimitBytes = config.configMemLimitBytes,
                    incremental = config.incrementalMode,
                    clOptions = listOf("tactic.solve_eqs.max_occs=4", "tactic.solve_eqs.context_solve=true"),
                    name = "Z3_with_CVC5_preprocessor",
                    preprocessorConfig = cvc5prepConfig,
                ))
            configs.add(
                SolverConfig.z3.default.copy(
                    timelimit = timeout - config.z3PreprocessorTimeout,
                    memlimitBytes = config.configMemLimitBytes,
                    incremental = config.incrementalMode,
                    clOptions = listOf("smt.arith.solver=2", "smt.auto_config=false"),
                    name = "Z3_with_CVC5_preprocessor2",
                    preprocessorConfig = cvc5prepConfig,
            ))
        }

        return configs
    }

    /**
     * For some reason this must be run after `logSolverExceptions`, so it is a separate method.
     */
    fun registerStats() = statsLogger.registerStats()
}
