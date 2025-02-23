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
import cli.SanityValues
import config.*
import config.Config.BytecodeFiles
import config.Config.CustomBuildScript
import config.Config.SpecFile
import config.Config.getSourcesSubdirInInternal
import config.component.EventConfig
import dependencyinjection.setupDependencyInjection
import diagnostics.JavaFlightRecorder
import event.CacheEvent
import event.CvtEvent
import event.RunMetadata
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import log.*
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
import spec.converters.EVMMoveSemantics
import spec.cvlast.CVLSingleRule
import spec.cvlast.IRule.Companion.createDummyRule
import spec.cvlast.typedescriptors.theSemantics
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
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
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
                fileName == null && isCertoraScriptFlow(buildFileName, verificationFileName) -> {
                    val cfgFileNames = File(getSourcesSubdirInInternal()).walk().filter {
                        it.isFile}.map {
                        getRelativeFileName(it.toString(), SOURCES_SUBDIR)
                    }.toSet()
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
                    ArtifactFileUtils.isSolana(fileName) -> handleSolanaFlow(fileName)
                    ArtifactFileUtils.isWasm(fileName) -> handleSorobanFlow(fileName)
                    SpecFile.getOrNull() != null -> handleCVLFlow(fileName, SpecFile.get())
                    else -> handleSolidityOrHexFlow(fileName)
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
        Logger.alwaysError(t.message ?: t.toString()) // a certora exception should not have null message
        CVTAlertReporter.reportAlert(CVTAlertType.GENERAL, CVTAlertSeverity.ERROR, null, t.msg, null)
        Logger.devError(t)
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
        // bye bye to all thread pools
        longProcessKiller.bye()
        timePing.bye()
        timeChecker?.bye()
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
        // finalResult determines the exitcode of the java process
        // note that it currently depends on the ConsoleReporter and we may want to change that in the future.
        finalResult =
            if (finalResult == FinalResult.SUCCESS && Config.getUseVerificationResultsForExitCode() && ConsoleReporter.finalVerificationResult != FinalResult.NONE) {
                ConsoleReporter.finalVerificationResult
            } else {
                finalResult
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
private fun checkTreeViewState(finalResultOld: FinalResult): FinalResult {
    if (!Config.getUseVerificationResultsForExitCode()) {
        // we're in test configuration that might clash with this check (exit codes can be bogus then) -- not checking
        return finalResultOld
    }

    val stillRunning = TreeViewReporter.instance?.topLevelRulesStillRunning()
    if (stillRunning?.isNotEmpty() == true) {
        Logger.alwaysWarn("We are shutting down, but some rules are still registered as `isRunning`:\n" +
            stillRunning.joinToString(separator = "\n") { it.second })
    }

    var finalResultNew = finalResultOld
    val treeViewViolationOrErrors = TreeViewReporter.instance?.topLevelRulesWithViolationOrErrorOrRunning()
    if (finalResultNew == FinalResult.SUCCESS && treeViewViolationOrErrors?.isNotEmpty() == true) {
        // Report an error that will be visible in the logs; the "code -1" fakes the infra we have when reporting
        // CertoraException -- the parsing check for the cloudwatch logs is, as of now, `parse @message '* ERROR * code *' as worker,content,errcode`
        Logger.alwaysError("Internally got SUCCESS as a final result when not all rules have been successfully " +
            "verified (according to tree view report), should not happen; changing it to ERROR (code -1)\n" +
            "violated or error rules:\n" +
            treeViewViolationOrErrors.joinToString(separator = "\n") { it.second }
        )
        finalResultNew = FinalResult.ERROR
    }
    return finalResultNew
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
    val (scene, reporter, treeView) = createSceneReporterAndTreeview(fileName)

    // Create a fake rule for the whole program although the program can have more than one assertion.
    // since `satisfy` is not a TAC statement, we handle it as an assert rule
    val rule = createDummyRule(fileName)

    when (val reportType = Config.TacEntryPoint.get()) {
        ReportTypes.PRESOLVER_RULE -> TACVerifier.verifyPresolver(scene, fileName, rule)
        ReportTypes.GENERIC_FLOW -> {
            val parsedTACCode = runInterruptible {
                CoreTACProgram.fromStream(FileInputStream(fileName), ArtifactFileUtils.getBasenameOnly(fileName))
            }
            handleGenericFlow(scene, reporter, treeView, listOf(rule to parsedTACCode))
        }
        ReportTypes.PRESIMPLIFIED_RULE -> TACVerifier.verify(scene, fileName, rule)
        else -> {
            logger.error("Report type \"$reportType\" is not supported as a tac entry point.")
            /* do nothing / just return to CLI */
        }
    }
}


fun createSceneReporterAndTreeview(fileName: String): Triple<IScene, ReporterContainer, TreeViewReporter> {
    val scene = SceneFactory.getScene(DegenerateContractSource(fileName))
    val reporterContainer = ReporterContainer(
        listOf(
            JSONReporter(Config.OutputJSONFile),
            ConsoleReporter,
            StatusReporter
        )
    )

    val treeView = TreeViewReporter(
        null,
        "",
        scene,
    )
    return Triple(scene, reporterContainer, treeView)
}

suspend fun handleGenericFlow(
    scene: IScene,
    reporterContainer: ReporterContainer,
    treeView: TreeViewReporter,
    rules: Iterable<Pair<CVLSingleRule, CoreTACProgram>>
): List<RuleCheckResult.Single> {
    for ((rule, _) in rules) {
        treeView.addTopLevelRule(rule)
    }

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

        if(Config.DoSanityChecksForRules.get() != SanityValues.NONE){
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
            ruleAlerts = null,
        )

        reporterContainer.addResults(rcrs)

        // Signal termination of the fake rule and persist result to TreeView JSON for the web UI to pick it up.
        treeView.signalEnd(rule, rcrs)
        treeView.hotUpdate()
        reporterContainer.hotUpdate(scene)
        rcrs
    }
}


suspend fun handleSorobanFlow(fileName: String) {
    val (scene, reporterContainer, treeView) = createSceneReporterAndTreeview(fileName)
    val wasmRules = WasmEntryPoint.webAssemblyToTAC(
        inputFile = File(fileName),
        selectedRules = Config.WasmEntrypoint.getOrNull().orEmpty(),
        env = SorobanHost,
        optimize = true
    )
    handleGenericFlow(
        scene,
        reporterContainer,
        treeView,
        wasmRules.map {
            createDummyRule(it.code.name, it.isSatisfyRule) to it.code
        }
    )
    reporterContainer.toFile(scene)
}

suspend fun handleSolanaFlow(fileName: String): List<RuleCheckResult.Single> {
    val (scene, reporterContainer, treeView) = createSceneReporterAndTreeview(fileName)
    val solanaRules = sbf.solanaSbfToTAC(fileName)
    val result = handleGenericFlow(
        scene,
        reporterContainer,
        treeView,
        solanaRules.map {
            createDummyRule(it.code.name, it.isSatisfyRule) to it.code
        }
    )
    reporterContainer.toFile(scene)
    return result
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

suspend fun handleSolidityOrHexFlow(fileName: String) {
    val contractSource = getContractFile(fileName)
    val scene = SceneFactory.getScene(contractSource)
    val solidityVerifier = SolidityVerifier(scene) // this is a bit of an odd-one out but let's leave it like this
    solidityVerifier.runVerifierOnFile(fileName)
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
    if (Config.IsCIMode.get()) {
        autoconfCiMode()
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
    ).filter { boolFlag -> boolFlag.getOrNull().orFalse() }

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
