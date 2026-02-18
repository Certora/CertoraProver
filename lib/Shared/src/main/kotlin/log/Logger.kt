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

package log

import config.Config
import config.DevMode
import mu.KotlinLogging
import utils.*
import java.io.OutputStreamWriter

/**
 * Logger topics.
 * A logger can be enabled for debugging with `-Dverbose.TOPIC_REAL_NAME`
 * A logger can be set to a particular level with `-Dlevel.TOPIC_REAL_NAME=LEVEL` where `LEVEL` is either `error`, `warn`,
 * `info`, `debug` or `trace`.
 * `-Dverbose.TOPIC_REAL_NAME` is the same as `-Dlevel.TOPIC_REAL_NAME=debug`.
 * A configuration of `level` takes precedence and overrides `verbose` if both are defined for a topic.
 *
 * A logger can be marked for CFG reporting of all report types matching it with `-Dtopic.TOPIC_REAL_NAME`.
 *
 * The `TOPIC_REAL_NAME` is received by converting the topic name to lower case and changing the underscore (`_`) to dot (`.`).
 */
enum class LoggerTypes : LoggerName {
    ALWAYS,
    ALLOC,
    COMMON,
    INSTRUMENTATION,
    //java [-Dlevel.tac.to.lexpr.coverter=<trace>|-Dverbose.tac.to.lexpr.coverter]  -jar  <emv.jar> <jar args>
    // python --javaArgs \"-Dlevel.tac.to.lexpr.coverter=<trace>\" ...
    TACEXPR,
    TAC_TO_LEXPR_CONVERTER,
    DECOMPILER,
    BMC,
    PRUNING,
    CONSTANT_PROPAGATION,
    ABSTRACT_INTERPRETATION,
    OPTIMIZE,
    SIGNED_DETECTOR,
    NO_UNDEROVERFLOW_UNDER_APPROX,
    DIAMOND_SIMPLIFIER,
    UNUSED_ASSIGNMENTS_OPT,
    FUNCTION_BUILDER,
    PER_FUNCTION_SIMPLIFICATION,
    NORMALIZER,
    INLINER,
    SUMMARIZATION,
    SETUP_HELPERS,
    VC_TO_SMT_CONVERTER,
    PARSER,
    POINTS_TO,
    TIMES,
    GENERIC_RULE,
    SPLIT,
    AUTOCONFIG,
    PARALLEL_SPLITTER,
    SLAVE,
    SPEC,
    CACHE,
    CVL_TYPE_CHECKER,
    UI,
    ALIAS_ANALYSIS,
    INITIALIZATION,
    BIT_BLAST,
    LOOP_ANALYSIS,
    EXT_CALL_ANALYSIS,
    INTERNAL_TYPE_CHECKER,
    DSA,
    CEX_ANALYSER,
    PATTERN,
    REQUIRE_INVARIANT,
    STORAGE_ANALYSIS,
    STORAGE_SPLITTING,
    TIMEOUT_REPORTING,
    SKEY_DETECTION,
    HOOK_INSTRUMENTATION,
    STRONG_INVARIANT_INLINER,
    WHOLE_CONTRACT_TRANSFORMATION,
    ABI_ENCODER,
    ABI_DECODER,
    ABI,
    CALL_STACK,
    TREEVIEW_REPORTER,
    GROUNDING,
    SOLVERS,
    TAC_INTERPRETER,
    SMT_ARRAY_GENERATOR,
    SMT_MATH_AXIOMS,
    HEURISTICAL_FOLDING,
    MUTATION_TESTER,
    RUN_REPRO,
    DEPS_GRAPH,
    GLOBAL_INLINER,
    BYTEMAP_CIF,
    TERNARY_SIMPLIFIER,
    PATTERN_REWRITER,
    INTERVALS_SIMPLIFIER,
    BYTEMAP_SCALARIZER,
    BYTEMAP_INLINER,
    INIT_MAPS,
    BOOL_OPTIMIZER,
    NEGATION_NORMALIZER,
    PROPAGATOR_SIMPLIFIER,
    EVENT_STREAM,
    LEXPRESSION,
    SMT_CEGAR,
    SMT_CEXDIVERSIFIER,
    SMT_TIMEOUT,
    MUS_ENUMERATION,
    UNIQUE_SUCCESSOR_REMOVER,
    SMT_VARIABLE_INLINER,
    TACSPLITTER,
    CALLTRACE,
    COUNTEREXAMPLEMODEL,
    CALLTRACE_STORAGE,
    REPORT_UTILS,
    SBF,
    SBF_BACKWARD_SCALAR_ANALYSIS,
    SBF_CPI,
    SBF_GLOBAL_VAR_ANALYSIS,
    SBF_MEMORY_ANALYSIS,
    SBF_MOD_REF_ANALYSIS,
    SBF_PTA_UNIFY,
    SBF_PTA_UNIFY2,
    SBF_PTA_COLLAPSES,
    SBF_PTA_CELL_RECONSTRUCT,
    SBF_PTA_JOIN,
    SBF_PTA_LEQ,
    SBF_PTA_MEM_TRANSFER,
    SBF_SCALAR_ANALYSIS,
    SBF_SCALAR_WITH_PREDS_ANALYSIS,
    SBF_LIVENESS,
    SBF_DEBUG_ANNOTATOR,
    SBF_MEMCPY_PROMOTION,
    WASM,
    WASM_ANALYSIS,
    CVLR,
    CONTRACT_CREATION,
    VM_INTEROP,
    QUANTIFIERS,
    TWOSTAGE,
    FOUNDRY,
    DEBUG_SYMBOLS,
    EQUIVALENCE,
    OVERFLOW_PATTERN_REWRITER,
    BOUNDED_MODEL_CHECKER,
    REQUIRE_REASON_CHECKER,
    MOVE,
    DEDUPLICATOR,
    VERIFICATION_FLOW
    ;
}

/**
 * The main logger class that should be used wherever possible.
 * It will always print errors and warnings with stacktraces to the results file, and the messages to regression.
 */
class KotlinLoggingLogger(val type: LoggerName) : Logger() {

    // determine the level of the log
    init {
        val levelProp = type.toLevelProp()
        val level = System.getProperty(levelProp)
        val verboseProp = type.toVerboseProp()

        val levelString = if (level != null) {
            level
        } else if (System.getProperty(verboseProp) != null) {
            LoggerLevels.DEBUG.name
        } else {
            null
        }

        if (levelString != null) {
            System.setProperty("$slf4jPrefix$type", levelString)
            if (System.getProperty(type.toTopicProp()) == null) {
                System.setProperty(type.toTopicProp(), "1")
            }
        }
    }

    private val consoleLogExceptions = DevMode.isDevMode()

    val logger = KotlinLogging.logger(type.toString())

    override val isEnabled get() = Config.isEnabledLogger(type)
    override val isDebugEnabled get() = logger.isDebugEnabled
    override val isTraceEnabled get() = logger.isTraceEnabled
    override val isErrorEnabled get() = logger.isErrorEnabled
    override val isWarnEnabled get() = logger.isWarnEnabled
    override val isInfoEnabled get() = logger.isInfoEnabled

    private fun formatMessage(level: LoggerLevels, msg: () -> Any) =
        "[${Thread.currentThread().name}] ${level.name} $type - ${msg()}"

    private fun outPrintLog(t: Throwable?, msg: () -> Any, level: LoggerLevels) {
        OutPrinter.print(t, formatMessage(level, msg))
    }

    override fun error(t: Throwable?, msg: () -> Any) {
        // always print errors and stacktraces to file
        outPrintLog(t, msg, LoggerLevels.ERROR)

        logger.error(t?.takeIf { consoleLogExceptions }, msg)

        // keep regression print of error message
        regression(msg)
        t?.message?.let { regression { it } }
    }

    override fun warn(t: Throwable?, msg: () -> Any) {
        // always print warnings and stacktraces to file
        outPrintLog(t, msg, LoggerLevels.WARN)

        logger.warn(t?.takeIf { consoleLogExceptions }, msg)

        // keep regression print of warnings
        regression(msg)
        t?.message?.let { regression { it } }
    }

    override fun info(t: Throwable?, msg: () -> Any) {
        if (logger.isInfoEnabled) {
            outPrintLog(t, msg, LoggerLevels.INFO)
        }
        logger.info(t?.takeIf { consoleLogExceptions }, msg)
    }

    override fun debug(t: Throwable?, msg: () -> Any) {
        if (logger.isDebugEnabled) {
            outPrintLog(t, msg, LoggerLevels.DEBUG)
        }
        logger.debug(t?.takeIf { consoleLogExceptions }, msg)
    }

    override fun trace(msg: () -> Any) {
        if (logger.isTraceEnabled) {
            outPrintLog(null, msg, LoggerLevels.TRACE)
        }
        logger.trace(msg)
    }

    companion object {
        private const val slf4jPrefix = "org.slf4j.simpleLogger.log."
    }
}

/**
 * Reports the code location of something that happened such as a successful call to signalStart.
 */
fun Logger.reportOnEventInCode(eventInCodeName: String) {
    if (isTraceEnabled) {
        try {
            throw object : Exception(eventInCodeName) {}
        } catch (e: Exception) {
            debug(e) { "convenience stack trace for $eventInCodeName" }
        }
    }
}


// utility logger, just so it's always easy to call errors/warnings even without a logger object
private val alwaysLogger = KotlinLoggingLogger(LoggerTypes.ALWAYS)

fun Logger.Companion.always(s: String, respectQuiet: Boolean) {
    val beQuiet = respectQuiet && Config.QuietMode.get()
    OutPrinter.print(s, toStdout = !beQuiet)
    alwaysLogger.info { s }
}

/**
    * Prints to what we currently call "Results.txt", or the main log file.
    * Useful when we want to log some important event that shouldn't be presented to the user via the console.
    */
fun Logger.Companion.toLogFile(s: String) {
    OutPrinter.print(s)
    alwaysLogger.info { s }
}

/**
    * Prints an error to out file, to stdout via alwaysLogger, and to regression
    */
fun Logger.Companion.alwaysError(s: String) {
    alwaysError(s, null)
}

fun Logger.Companion.alwaysError(s: String, t: Throwable?) {
    OutPrinter.printErrorToScreen(s)
    alwaysLogger.error(t, s)
}

fun Logger.Companion.alwaysWarn(s: String, t: Throwable?) {
    OutPrinter.printWarningToScreen(s)
    alwaysLogger.warn(t) { s }
}

fun Logger.Companion.alwaysWarn(s: String) {
    alwaysWarn(s, null)
}

private val regressionFile: OutputStreamWriter? by lazy {
    if (Config.RegressionTest.get()) {
        ArtifactManagerFactory().apply {
            registerArtifact(Config.OutputRegressionFile)
        }.getArtifactHandle(Config.OutputRegressionFile)
    } else {
        null
    }
}

// lazy string builder for optimization - to allow for more expensive regression testing
fun Logger.Companion.regression(str: () -> Any) {
    regressionFile?.let { file ->
        synchronized(this) {
            file.appendLine(str().toString())
            file.flush()
        }
    }
}

fun Logger.Companion.regressionPrinter(printer: ((() -> Any) -> Unit) -> Unit) {
    regressionFile?.let { file ->
        printer { msg ->
            val toPrint = msg()
            synchronized(this) {
                file.appendLine(toPrint.toString())
                file.flush()
            }
        }
    }
}

fun <T> T?.warnIfNull(logger: Logger, f: () -> String) : T? {
    if (this == null) { logger.warn(f) }
    return this
}
