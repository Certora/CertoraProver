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

@file:Suppress("MissingPackageDeclaration") // `main` is in default package

import annotations.PollutesGlobalState
import awshelpers.sqs.sqsSendErrorTracker
import bridge.CertoraConf
import bridge.NamedContractIdentifier
import certora.CVTVersion
import cli.Ecosystem
import cli.SanityValues
import config.*
import config.Config.BytecodeFiles
import config.Config.CustomBuildScript
import config.Config.DoSanityChecksForRules
import config.Config.SpecFile
import config.Config.getSourcesSubdirInInternal
import config.component.EventConfig
import datastructures.stdcollections.*
import dependencyinjection.setupDependencyInjection
import diagnostics.JavaFlightRecorder
import event.CacheEvent
import event.CvtEvent
import event.RunMetadata
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import log.*
import move.MoveVerifier
import org.apache.commons.cli.UnrecognizedOptionException
import os.dumpSystemConfig
import parallel.coroutines.establishMainCoroutineScope
import parallel.coroutines.parallelMapOrdered
import report.*
import rules.CompiledRule
import rules.IsFromCache
import rules.RuleCheckResult
import rules.VerifyTime
import rules.sanity.TACSanityChecks
import scene.*
import scene.source.*
import smt.BackendStrategyEnum
import smt.CoverageInfoEnum
import smt.PrettifyCEXEnum
import smt.UseLIAEnum
import spec.converters.EVMMoveSemantics
import spec.cvlast.*
import spec.cvlast.typedescriptors.theSemantics
import spec.rules.EcosystemAgnosticRule
import statistics.RunIDFactory
import statistics.SDCollectorFactory
import statistics.startResourceUsageCollector
import tac.DumpTime
import utils.*
import utils.ArtifactFileUtils.getRelativeFileName
import vc.data.CoreTACProgram
import verifier.*
import verifier.mus.UnsatCoreAnalysis
import wasm.WasmEntryPoint
import wasm.host.soroban.SorobanHost
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ExecutionException
import kotlin.collections.listOf
import kotlin.collections.toList
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.sequences.toSet
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val logger = Logger(LoggerTypes.COMMON)

@PollutesGlobalState
fun main(args: Array<String>) {
    /** do this first. Also make sure that [TimeSinceStart.epoch] is initialized */
    val startOfProcess = TimeSinceStart()
    /**
     * Set the "vtable" for the move used expected by [spec.cvlast.typedescriptors.EVMTypeDescriptor]. We do this
     * here because the implementation of [EVMMoveSemantics] use the full TACSymbol/TACCmd type definitions available
     * in *this* module, not the stubs available in Shared (which does *not* have access to the TACSymbol stuff)
     */
    theSemantics = EVMMoveSemantics

    var finalResult = FinalResult.NONE

    // Some helpful debug prints
    val path = System.getProperty("user.dir")
    logger.info { "Working in $path" }
    val envpath = System.getenv("PATH")
    logger.info { "System PATH: $envpath" }
    val libpath = System.getProperty("java.library.path")
    logger.info { "Java lib path: $libpath" }

    CommandLineParser.registerConfigurations()
    Config.MainJarRun.set(true)

    // Initialization of "Ping" printer and a monitor thread for long running graphviz (dot) processes
    val timePing = TimePing(Config.PingFrequency.get().seconds, startTime = startOfProcess)
    var timeChecker: TimePing? = null

    val longProcessKiller = LongProcessKiller()
    longProcessKiller.start()
    val startTime = System.currentTimeMillis()
    try {
        CommandLineParser.setExecNameAndDirectory()
        ConfigType.WithArtifacts.set(
            if (Config.AvoidAnyOutput.get()) { // xxx avoid-any-output is obsolete and should be removed and lead to significant simplifications. will be done separately
                ArtifactManagerFactory.WithArtifactMode.WithoutArtifacts
            } else {
                ArtifactManagerFactory.WithArtifactMode.WithArtifacts
            }
        )
        CommandLineParser.processArgsAndConfig(args)
        // setup configs based on some of the above read options, that are "configurators" of more than one config
        autoConfig()

        // dump args for convenience (we won't dump bad args here, because we first must update the config to have
        // artifacts such as this one)
        dumpArgs(args)

        startResourceUsageCollector()
        JavaFlightRecorder.start()

        // Only after everything is configured can we read the metadata file that is necessary to
        // construct a CVT Event
        val metadataFileName = Config.prependInternalDir(CERTORA_METADATA_FILE_PATH)
        val metadataFile = File(metadataFileName).takeIf(File::isFile)

        // Allow top-level coroutines to start after this point
        establishMainCoroutineScope(exitTimeout = Config.ShutdownTimeout.get().seconds) { mainCoroutineScope ->
            // Start the CVTAlertsReporter
            CVTAlertReporter().init()
            initCVTSystemEventsReporter()

            if (EventConfig.EnableEventReporting.get() && metadataFile != null) {
                reportCacheDirStats(isStart = true)
                val metadata = metadataFile.readText().let(RunMetadata::deserializeFromJson)
                CvtEvent.CvtStartEvent(metadata).emit(CVTSystemEventsReporter)
            }

            // initialize writer for Results.txt
            OutPrinter.init()
            // writes cvt_version.json - helps debug a run to know which version (expressed as a git hash) a run was done against.
            dumpVersionFile()
            // initialize dependency injection
            setupDependencyInjection()
            // Write system info to the log
            dumpSystemConfig()
            // start tracking CVT's run time for the stats (statsdata.json).
            RunIDFactory.runId().reportRunStart()
            // start the Ping-er thread
            timePing.start()

            // define and start the global timeout checker thread
            timeChecker = Config.actualGlobalTimeout().takeIf { it > 0 }?.let { to ->
                TimePing(10.seconds, false, to.seconds)
            }?.also {
                val thisThread = Thread.currentThread()
                it.setUncaughtExceptionHandler { _, e ->
                    if (e is TimeCheckException) {
                        Logger.always("Global timeout reached, hard stopping everything", respectQuiet = false)

                        OutPrinter.dumpThreads("timeout.soft.threads.txt")

                        CVTAlertReporter.reportAlert(
                            CVTAlertType.OUT_OF_RESOURCES,
                            CVTAlertSeverity.ERROR,
                            null,
                            "Reached global timeout. Hard stopping. If you have tried to verify multiple rules" +
                                " and the Prover did not finish the computation for some of them, we suggest to run the " +
                                "Prover for the individual unfinished rules separately.",
                            null,
                            CheckedUrl.TIMEOUT_PREVENTION,
                        )

                        Logger.always("Interrupting main thread", respectQuiet = false)
                        thisThread.interrupt()

                        Logger.always("Canceling background tasks", respectQuiet = false)
                        mainCoroutineScope.cancel()

                        // we have exactly one minute to cleanly exit, otherwise we are hard killing
                        Timer(true).schedule(object : TimerTask() {
                            override fun run() {
                                Logger.always("Did not clean up cleanly, hard stopping", respectQuiet = false)
                                OutPrinter.dumpThreads("timeout.hard.threads.txt")
                                exitProcess(1)
                            }
                        }, 60L * 1000)
                    } else {
                        throw e
                    }
                }
            }
            timeChecker?.start()

            // check to see if CVT gets a contract file directly, and stores in a variable
            val fileName = ConfigType.MainFileName.getOrNull()

            // if the environment has a CERTORA dir, will print some messages.
            // TODO: This should be changed to check if we're not in cloud, cloud may have $CERTORA defined too?
            if (Config.isRunningInLocalEnv()) {
                Logger.always(
                    "Reports in ${ArtifactManagerFactory().mainReportsDir.absolutePath}",
                    respectQuiet = true
                )
            }

            // a hook (currently unused) that allows to run any build script from within the JAR
            runBuildScript()

            // grab the main certoraRun artifacts that are used in 99.999% of runs: .certora_build.json, .certora_verify.json
            // and the metadata file (for debuggability)
            val buildFileName = Config.prependInternalDir(CERTORA_BUILD_FILE_PATH)
            val verificationFileName = Config.prependInternalDir(CERTORA_VERIFY_FILE_PATH)

            val cvtStopEventBuilder = CvtEvent.CvtStopEvent.Builder()

            Config.warnIfSetupPhaseFlagsEnabled()

            // Log the SMT solver versions in the environment
            logSmtSolvers()

            /* Different flows and how we choose:
            1. Bytecode mode takes precedence over existence of Certora script files
            2. Certora scripts if exist are used if no bytecode was given
            3. If no cache, then if there's no provided file, take files generated by certora scripts, if available
            4. If there's a file provided:
                4.1. Check TAC
                4.2. Check if providing a spec file
                4.3. Otherwise, treat solidity file / hex file with the asserts that it may contain
            */
            when {
                fileName == null && BytecodeFiles.getOrNull() != null &&
                    SpecFile.getOrNull() != null -> handleBytecodeFlow(BytecodeFiles.get(), SpecFile.get())

                fileName == null && Config.MoveModulePath.getOrNull() != null -> handleMoveFlow()

                fileName == null && isCertoraScriptFlow(buildFileName, verificationFileName) -> {
                    val cfgFileNames = getFilesInSourcesDir()
                    val ruleCheckResults = handleCertoraScriptFlow(
                        buildFileName,
                        verificationFileName,
                        metadataFileName,
                        cfgFileNames
                    )
                    cvtStopEventBuilder.addRuleCheckResultsStatsOf(ruleCheckResults)
                    if (ruleCheckResults.anyDiagnosticErrors()) {
                        finalResult = FinalResult.DIAGNOSTIC_ERROR
                    }
                }

                fileName != null -> when {
                    ArtifactFileUtils.isTAC(fileName) -> handleTACFlow(fileName)
                    ArtifactFileUtils.isSolana(fileName) -> {
                        val (_, ruleCheckResults) = handleSolanaFlow(fileName)
                        if (ruleCheckResults.anyDiagnosticErrors()) {
                            finalResult = FinalResult.DIAGNOSTIC_ERROR
                        }
                    }
                    ArtifactFileUtils.isWasm(fileName) -> {
                        val ruleCheckResults = handleSorobanFlow(fileName)
                        if (ruleCheckResults.anyDiagnosticErrors()) {
                            finalResult = FinalResult.DIAGNOSTIC_ERROR
                        }
                    }
                    SpecFile.getOrNull() != null -> handleCVLFlow(fileName, SpecFile.get())
                    else -> throw IllegalArgumentException("Unknown file type for $fileName")
                }

                else -> {
                    if (BytecodeFiles.getOrNull() != null && BytecodeFiles.get().isNotEmpty()) {
                        Logger.alwaysError("In bytecode mode, must specify a spec file with ${SpecFile.name}")
                    } else {
                        Logger.alwaysError("Must provide a file to work on, or to make sure $buildFileName and $verificationFileName exist (relative from working directory ${path})")
                    }
                    finalResult = FinalResult.ERROR
                }
            }

            if (finalResult != FinalResult.ERROR && finalResult != FinalResult.DIAGNOSTIC_ERROR) {
                finalResult = FinalResult.SUCCESS
            }
            reportCacheDirStats(isStart = false)
            val stopTime = System.currentTimeMillis()
            val runDuration = stopTime - startTime
            cvtStopEventBuilder.toEvent(runDuration)
                .emit(CVTSystemEventsReporter)
        }
    } catch (t: UnrecognizedOptionException) {
        Logger.alwaysError("Failed to parse arguments to underlying Prover process: \"${t.option}\"", t)
        finalResult = FinalResult.ERROR
    } catch (_: TimeCheckException) {
        finalResult = FinalResult.ERROR
    } catch (t: CertoraException) {
        t.message?.let { Logger.regression { it } } // allow to check regression on cvt-killing exceptions
        CVTAlertReporter.reportAlert(CVTAlertType.GENERAL, CVTAlertSeverity.ERROR, null, t.msg, null, throwable = t)
        finalResult = FinalResult.ERROR
    } catch (t: ExceptionInInitializerError) {
        t.message?.let { Logger.regression { it } } // allow to check regression on cvt-killing exceptions
        Logger.alwaysError("Encountered exception ${t.message} while running: ${args.joinToString(" ")}", t)
        Logger.alwaysError("Originally from ${t.exception.message} at\n${t.exception.stackTraceToString()}")
        finalResult = FinalResult.ERROR
    } catch (t: ExecutionException) {
        t.message?.let { Logger.regression { it } } // allow to check regression on cvt-killing exceptions
        val cause = t.cause
        // certora exceptions are user-meaningful, thus reported via the alert mechanism.
        if (cause is CertoraException) {
            CVTAlertReporter.reportAlert(CVTAlertType.GENERAL, CVTAlertSeverity.ERROR, null, cause.msg, null)
        }
        Logger.alwaysError("Encountered exception ${t.message} while running: ${args.joinToString(" ")}", t)
        finalResult = FinalResult.ERROR
    } catch (t: Exception) {
        t.message?.let { Logger.regression { it } } // allow to check regression on cvt-killing exceptions
        Logger.alwaysError("Encountered exception ${t.message} while running: ${args.joinToString(" ")}", t)
        val isEarlyCrash = TimeSinceStart().d.inWholeSeconds <= 10
        CVTAlertReporter.reportAlert(CVTAlertType.GENERAL, CVTAlertSeverity.ERROR, null,
            "An internal Prover error occurred, please ${
                if (isEarlyCrash) {
                    "double-check the provided configuration or command-line arguments"
                } else {
                    "contact support"
                }
            }, and see low-level crash details below:",
            t.message ?: "N/A"
        )
        finalResult = FinalResult.ERROR
    } finally {
        // Interrupt all thread pools
        longProcessKiller.interruptThread()
        timePing.interruptThread()
        timeChecker?.interruptThread()
        CVTAlertReporter().close()
        // always output stats, even if erroneous
        RunIDFactory.runId().reportRunEnd()
        // collect run id for stats
        SDCollectorFactory.collector().collectRunId()
        // to file - as defined in config
        SDCollectorFactory.collector().toFile()
        // if we defined a non-default prefix, we still want a copy in the default, because automatic systems use it
        if (Config.OutputJSONStatsDataFilePrefix.get() != Config.OutputJSONStatsDataFilePrefix.default) {
            SDCollectorFactory.collector().toFile(Config.getDefaultJSONStatsDataOutputFile())
        }
        //log statistics about individual splits of rules
        val splitStatsFile = "${ArtifactManagerFactory().mainReportsDir}${File.separator}splitStatsdata.json"
        SDCollectorFactory.splitStatsCollector().toFile(splitStatsFile)

        if (!DevMode.isDevMode()) {
            sqsSendErrorTracker.logErrorsSummary()
        }
    }
    try {
        // from this point forward, must no longer update stats and alerts, they are written already

        // more prints suitable for local mode only. In cloud it shouldn't show up
        if (Config.isRunningInLocalEnv()) {
            Logger.always(
                "Reports in file://${ArtifactManagerFactory().mainReportsDir.absolutePath}",
                respectQuiet = true
            )
            Logger.always("Final report in ${HTMLReporter.getHTMLReportFileURI() ?: "N/A"}", respectQuiet = true)
        }

        // Open the final HTML report in browser if in local run and the option wasn't disabled.
        if (!HTMLReporter.isDisabledPopup() && Config.isRunningInLocalEnv()) {
            HTMLReporter.open()
        }

        finalResult = checkTreeViewState(finalResult)

        Notifications.showNotification(
            "Certora Prover completed",
            if (HTMLReporter.isDisabledPopup()) {
                "Click to open report in browser"
            } else {
                "If no browser pop-up, refer to console"
            },
            finalResult
        )
    } catch (t: Exception) {
        // If Logger.always throws an exception, we enter into an infinite loop...
        // also want to make sure we finish the tasks
        System.err.println("Error when finalizing: $t")
        t.printStackTrace()
    }

    exitProcess(finalResult.getExitCode())
}

/** Sanity checks on tree view state on lockdown, including comparing it with [FinalResult]. */
private fun checkTreeViewState(finalResult: FinalResult): FinalResult {
    if (!Config.getUseVerificationResultsForExitCode() || Config.BoundedModelChecking.getOrNull() != null) {
        // we're in test configuration that might clash with this check (exit codes can be bogus then) -- not checking
        // or we are in BMC mode in which the reporting works differently as we manually construct the tree.
        return finalResult
    }

    val stillRunning = TreeViewReporter.instance?.topLevelRulesStillRunning()
    if (stillRunning?.isNotEmpty() == true) {
        val msg = "We are shutting down, but some rules are still registered as `isRunning`:\n" +
            stillRunning.joinToString(separator = "\n") { it.second }
        if(Config.TestMode.get()){
            throw IllegalStateException(msg)
        }
        Logger.alwaysWarn(msg)
    }

    val treeViewViolationOrErrors = TreeViewReporter.instance?.topLevelRulesWithViolationOrErrorOrRunning()
    if (treeViewViolationOrErrors?.isNotEmpty() == true) {
        return FinalResult.FAIL
    }
    return finalResult
}

/**
 * dumps the args used by the java process. This allows:
 * 1. a local run will run from the same directory run before: emv-.../inputs/run.sh
 * 2. a remote run will omit the -buildDirectory and run from the inputs/ folder of zip output
 */
private fun dumpArgs(args: Array<String>) {
    logger.info { "Running with arguments: ${args.joinToString(" ")}" }
    val runFile = "rerun.sh"
    ArtifactManagerFactory().registerArtifact(runFile, StaticArtifactLocation.Input) { name ->
        val file = ArtifactFileUtils.getWriterForFile(name, true)
        try {
            file.use {
                it.append("java -jar \$CERTORA/emv.jar ${args.joinToString(" ")}")
            }
        } catch (_: IOException) {
            logger.error { "Failed to dump run.sh file for repro" }
        }
    }
}


fun reportCacheDirStats(isStart: Boolean) {
    Paths.get(Config.CacheDirName.get()).also {
        if (!it.isDirectory()) {
            CacheEvent.NumCacheFiles(0, isStart).emit(CVTSystemEventsReporter)
            CacheEvent.CacheDirSize(0, isStart).emit(CVTSystemEventsReporter)
        } else {
            CacheEvent.NumCacheFiles(ArtifactFileUtils.numFiles(it), isStart).emit(CVTSystemEventsReporter)
            CacheEvent.CacheDirSize(ArtifactFileUtils.dirSize(it), isStart).emit(CVTSystemEventsReporter)
        }
    }
}

private val prettyPrinterJson = Json { prettyPrint = true }

/**
 * Create cvt_version.json
 */
private fun dumpVersionFile() {
    if (Config.AvoidAnyOutput.get()) {
        return
    }

    val cvtVersion = CVTVersion.getCurrentCVTVersion()
    if (cvtVersion == null) {
        logger.warn { "Failed to obtain CVT version, not dumping version information." }
        return
    }

    val cvtVersionFileName = "cvt_version.json"

    // allows to save in multiple locations
    fun dumpInLocation(loc: ArtifactLocation) {
        val dumpedAlready = ArtifactManagerFactory().getRegisteredArtifactPathOrNull(cvtVersionFileName)
        if (dumpedAlready != null) { // artifact already saved
            // copy
            ArtifactManagerFactory().copyArtifact(loc, cvtVersionFileName)
            return
        }

        ArtifactManagerFactory().registerArtifact(cvtVersionFileName, loc) { name ->
            val file = ArtifactFileUtils.getWriterForFile(name, true)
            try {
                file.use {
                    it.append(prettyPrinterJson.encodeToString(CVTVersion.serializer(), cvtVersion))
                }
            } catch (_: IOException) {
                logger.error { "Failed to dump CVT version to file" }
            }
        }
    }

    dumpInLocation(StaticArtifactLocation.Input)
    dumpInLocation(StaticArtifactLocation.Reports)
}

/**
 * Returns true if CVT's input should be taken from the certoraRun generated files
 */
fun isCertoraScriptFlow(buildFileName: String, verificationFileName: String): Boolean =
    File(buildFileName).exists() && File(verificationFileName).exists()

/**
 * Starting point for CVT to process certoraRun generated files
 */
suspend fun handleCertoraScriptFlow(
    buildFileName: String, verificationFileName: String, metadataFileName: String,
    cfgFileNames: Set<String>
): List<RuleCheckResult> {
    val contractSource = CertoraBuilderContractSource(CertoraConf.loadBuildConf(Path(buildFileName)))
    val verificationConf = CertoraConf.loadVerificationConf(Path(verificationFileName))
    val specSource = CertoraBuilderSpecSource(verificationConf)

    CertoraConf.copyInputs(buildFileName, verificationFileName, cfgFileNames, metadataFileName)
    return IntegrativeChecker.run(specSource, contractSource)
}

suspend fun handleBytecodeFlow(bytecodeFiles: Set<String>, specFilename: String) {
    val contractSource = MultiBytecodeSource(bytecodeFiles.toList())
    val specSource = CVLSpecSource(specFilename, NamedContractIdentifier(contractSource.mainContract))
    IntegrativeChecker.run(specSource, contractSource)
}

suspend fun handleTACFlow(fileName: String) {
    val (scene, reporter, treeView) = createSceneReporterAndTreeview(fileName, "TACMainProgram")

    // Create a fake rule for the whole program although the program can have more than one assertion.
    // since `satisfy` is not a TAC statement, we handle it as an assert rule
    val rule = EcosystemAgnosticRule(ruleIdentifier = RuleIdentifier.freshIdentifier(fileName), ruleType = SpecType.Single.FromUser.SpecFile)

    when (val reportType = Config.TacEntryPoint.get()) {
        ReportTypes.PRESOLVER_RULE -> TACVerifier.verifyPresolver(scene, fileName, rule)
        ReportTypes.GENERIC_FLOW -> {
            treeView.use {
                val parsedTACCode = runInterruptible {
                    CoreTACProgram.fromStream(FileInputStream(fileName), ArtifactFileUtils.getBasenameOnly(fileName))
                }
                handleGenericFlow(scene, reporter, treeView, listOf(rule to parsedTACCode))
            }
        }

        ReportTypes.PRESIMPLIFIED_RULE -> TACVerifier.verify(scene, fileName, rule)
        else -> {
            logger.error("Report type \"$reportType\" is not supported as a tac entry point.")
            /* do nothing / just return to CLI */
        }
    }
}


fun createSceneReporterAndTreeview(fileName: String, contractName: String): Triple<IScene, ReporterContainer, TreeViewReporter> {
    val scene = SceneFactory.getScene(DegenerateContractSource(fileName))
    val reporterContainer = ReporterContainer(
        listOf(
            ConsoleReporter
        )
    )
    val treeView = TreeViewReporter(
        contractName,
        "",
        scene,
    )
    return Triple(scene, reporterContainer, treeView)
}

suspend fun handleGenericFlow(
    scene: IScene,
    reporterContainer: ReporterContainer,
    treeView: TreeViewReporter,
    rules: Iterable<Pair<EcosystemAgnosticRule, CoreTACProgram>>
): List<RuleCheckResult.Single> {

    // Copy in `inputs` directory the contents of the `.certora_sources` directory.
    val filesInSourceDir = getFilesInSourcesDir()
    CertoraConf.backupFiles(filesInSourceDir)

    treeView.buildRuleTree(rules.map { it.first })

    return rules.parallelMapOrdered { _, (rule, coretac) ->
        ArtifactManagerFactory().dumpCodeArtifacts(
            coretac,
            ReportTypes.GENERIC_FLOW,
            StaticArtifactLocation.Outputs,
            DumpTime.AGNOSTIC
        )

        treeView.signalStart(rule)

        val startTime = System.currentTimeMillis()
        val vRes = TACVerifier.verify(scene, coretac, treeView.liveStatsReporter, rule)
        val endTime = System.currentTimeMillis()


        if (DoSanityChecksForRules.get() != SanityValues.NONE &&
            /* For Solana there are two types of sanity checks: Sanity rules that are created earlier in the pipeline
               and TAC sanity checks that are performed here. We explicitly don't want to run TAC sanity on the sanity rules
               created earlies.
             */
            rule.ruleType !is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck) {
            TACSanityChecks.analyse(scene, rule, coretac, vRes, treeView)
        }

        if (vRes.unsatCoreSplitsData != null) {
            UnsatCoreAnalysis(vRes.unsatCoreSplitsData, coretac).dumpToJsonAndRenderCodemaps()
        }

        val joinedRes = Verifier.JoinedResult(vRes)
        // Print verification results and create a html file with the cex (if applicable)
        joinedRes.reportOutput(rule)

        val rcrs = CompiledRule.generateSingleResult(
            scene = scene,
            rule = rule,
            vResult = joinedRes,
            verifyTime = VerifyTime.WithInterval(startTime, endTime),
            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
            ruleAlerts = emptyList(),
        )

        reporterContainer.addResults(rcrs)

        // Signal termination of the fake rule and persist result to TreeView JSON for the web UI to pick it up.
        treeView.signalEnd(rule, rcrs)
        reporterContainer.hotUpdate(scene)
        rcrs
    }
}

suspend fun handleSorobanFlow(fileName: String): List<RuleCheckResult.Single> {
    val (scene, reporterContainer, treeView) = createSceneReporterAndTreeview(fileName, "SorobanMainProgram")
    treeView.use {
        val wasmRules = WasmEntryPoint.webAssemblyToTAC(
            inputFile = File(fileName),
            selectedRules = Config.WasmEntrypoint.getOrNull().orEmpty(),
            env = SorobanHost,
            optimize = true
        )
        val result = handleGenericFlow(
            scene,
            reporterContainer,
            treeView,
            wasmRules.map { it.rule to it.code }
        )
        reporterContainer.toFile(scene)
        return result
    }
}

suspend fun handleMoveFlow() {
    // Copy in `inputs` directory the contents of the `.certora_sources` directory.
    val filesInSourceDir = getFilesInSourcesDir()
    CertoraConf.backupFiles(filesInSourceDir)

    // And back up the binary modules
    CertoraConf.backupFiles(
        File(Config.MoveModulePath.get()).walk().filter { it.isFile }.map { it.toString() }.toSet()
    )

    MoveVerifier().use {
        it.verify()
    }
}

suspend fun handleSolanaFlow(fileName: String): Pair<TreeViewReporter,List<RuleCheckResult.Single>> {
    val (scene, reporterContainer, treeView) = createSceneReporterAndTreeview(fileName, "SolanaMainProgram")
    treeView.use {
        val solanaRules = sbf.solanaSbfToTAC(fileName)
        val result = handleGenericFlow(
            scene,
            reporterContainer,
            treeView,
            solanaRules.map { it.rule to it.code }
        )
        reporterContainer.toFile(scene)
        return treeView to result
    }
}

fun getContractFile(fileName: String): IContractSource =
    if (ArtifactFileUtils.isSol(fileName)) SolidityContractSource(fileName) else BytecodeContractSource(fileName)

fun getSources(contractFilename: String, specFilename: String) =
    Pair(getContractFile(contractFilename), CVLSpecSource(specFilename, mainContractFromFilename(contractFilename)))

suspend fun handleCVLFlow(contractFilename: String, specFilename: String) {
    val (contractSource, specSource) = getSources(contractFilename, specFilename)
    // consider doing a quick check on cvls before, then build scene, then re-fetch cvl? (e.g. bytecode case)
    IntegrativeChecker.run(specSource, contractSource)
}

fun runBuildScript() {
    CustomBuildScript.get().let { customBuildScript ->
        if (customBuildScript.isNotBlank()) {
            val (exitcode, output) = safeCommandExec(listOf(customBuildScript), "build_script", true, true)
            if (exitcode != 0) {
                logger.error("Failed to run $customBuildScript, returned exit code $exitcode and output $output")
            } else {
                output.split("\n").drop(3).joinToString("\n").let { filteredOutput ->
                    if (filteredOutput.isNotBlank()) {
                        Logger.always("Ran $customBuildScript, output: $filteredOutput", respectQuiet = false)
                    } else {
                        Logger.always("Ran $customBuildScript", respectQuiet = false)
                    }
                }
            }
        }
    }
}

fun getFilesInSourcesDir(): Set<String> {
    return File(getSourcesSubdirInInternal()).walk().filter {
        it.isFile
    }.map {
        getRelativeFileName(it.toString(), SOURCES_SUBDIR)
    }.toSet()
}

/**
 * Sets some configs when -ciMode is true
 */
@PollutesGlobalState
private fun autoconfCiMode() {
    Config.GraphDrawLimit.set(0)
    Config.IsGenerateGraphs.set(false)
    Config.ShouldDeleteSMTFiles.set(true)
    Config.DisablePopup.set(true)
    Config.LowFootprint.set(true)
    Config.PatienceSeconds.set(0)
    Config.IsGenerateDotFiles.set(false)
    Config.QuietMode.set(true)
    Config.ShutdownTimeout.set(0)
    Config.JFR.set(false)
}

/**
 * Set config when the default is not appropriate.
 */
@PollutesGlobalState
private fun autoConfig() {
    setActiveFlow()
    if (Config.IsCIMode.get()) {
        autoconfCiMode()
    }

    if (Config.HashingBoundDetectionMode.get()) {
        Config.CoverageInfoMode.set(CoverageInfoEnum.ADVANCED) //this is used to disable optimisations
        Config.Smt.NumOfUnsatCores.set(0) //this effectively disables the unsat core enumeration itself
        Config.PreciseHashingLengthBound.set(0)
        Config.OptimisticUnboundedHashing.set(false)
        Config.prettifyCEX.set(PrettifyCEXEnum.JOINT) //used to minimise values of the hashing bounds
        Config.EnableSplitting.setIfUnset(false) //disable splitting so that we consider all paths while looking for suitable hashing bounds
        Config.Smt.BackendStrategy.set(BackendStrategyEnum.SINGLE_RACE)
        Config.Smt.UseLIA.set(UseLIAEnum.UNSAT_ONLY) //We don't want to use Constraint Choosers during the CEX prettification
        Config.PrettifyCEXSmallBarriers.set(true) //We expect the hashing bound to be relatively small and hence use small barriers
        Config.PostProcessCEXSingleCheckTimeout.setIfUnset(100)
        Config.PostProcessCEXTimeoutSeconds.setIfUnset(600)
    }

    if(Config.BoundedModelChecking.getOrNull() != null && Config.RequireInvariantsPreRuleSemantics.get()){
        // In BMC mode, we are _not_ using the global require invariant semantics
        Config.RequireInvariantsPreRuleSemantics.set(false)
    }

    if (Config.ActiveEcosystem.getOrNull() == Ecosystem.SUI) {
        @OptIn(Config.DestructiveOptimizationsOption::class)
        Config.DestructiveOptimizationsMode.setIfUnset(DestructiveOptimizationsModeEnum.TWOSTAGE)
    }
}

/** Set the verification flow that is being used for the current verification task. */
@PollutesGlobalState
private fun setActiveFlow() {
    ConfigType.MainFileName.getOrNull().let { fileName ->
        if (fileName != null) {
            when {
                ArtifactFileUtils.isTAC(fileName) && Config.ActiveEcosystem.getOrNull() == null ->
                    // We set it only if this is not set, otherwise we use what was provided with the command line option.
                    Config.ActiveEcosystem.set(Ecosystem.EVM)

                ArtifactFileUtils.isSolana(fileName) -> Config.ActiveEcosystem.set(Ecosystem.SOLANA)
                ArtifactFileUtils.isWasm(fileName) -> Config.ActiveEcosystem.set(Ecosystem.SOROBAN)
                SpecFile.getOrNull() != null -> Config.ActiveEcosystem.set(Ecosystem.EVM)
                else -> Config.ActiveEcosystem.set(Ecosystem.EVM)
            }
        } else if (Config.MoveModulePath.getOrNull() != null) {
            Config.ActiveEcosystem.set(Ecosystem.SUI)
        } else {
            Config.ActiveEcosystem.set(Ecosystem.EVM)
        }
    }
}

/**
 * some config flags that are typically used during the setup phase are expensive,
 * and we typically want to disable them in production.
 * this function warns the user if any of these flags are set.
 *
 * NOTE: this function expects [report.CVTAlertReporter] to have been initialized.
 */
private fun Config.warnIfSetupPhaseFlagsEnabled() {
    val enabledSetupFlags = listOf(
        VerifyCache,
        VerifyTACDumps,
        TestMode,
        CheckRuleDigest,
    ).filter { boolFlag -> boolFlag.getOrNull() == true }

    if (enabledSetupFlags.isNotEmpty()) {
        val names = enabledSetupFlags.joinToString(separator = ", ") { flag -> flag.option.realOpt() }

        CVTAlertReporter.reportAlert(
            CVTAlertType.GENERAL,
            CVTAlertSeverity.WARNING,
            jumpToDefinition = null,
            "The following flags have been enabled: $names. These flags may negatively affect performance",
            hint = "These flags are typically only used during the project setup phase. " +
                "Consider disabling these flags in production, or when running performance-intensive rules."
        )
    }
}
