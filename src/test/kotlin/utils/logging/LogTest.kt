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

package utils.logging

import config.Config
import config.ConfigScope
import log.*
import org.slf4j.LoggerFactoryFriend
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import testutils.logConfigScope
import utils.OutPrinter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter

data class ConsoleLogOutput(val out: String, val err: String)
data class LogOutput(val out: String, val err: String, val file: String)

@Suppress("ForbiddenMethodCall")
private fun String.removeThreadNames() = this
    .replace(Regex("\\[ForkJoinPool-[0-9]+-worker-[0-9]+\\] "), "")
    .replace("[Test worker] ", "")
    .trim()


/**
    Captures logs written to stdout and stderr, removing the thread prefix from each line.
    This is fundamentally not thread-safe.  Thankfully, tests do not run in parallel in a single process.
 */
fun captureConsoleLogs(f: () -> Unit): ConsoleLogOutput {
    val outContent = ByteArrayOutputStream()
    val errContent = ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err

    System.setOut(PrintStream(outContent, true))
    System.setErr(PrintStream(errContent, true))
    try {
        f()
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    return ConsoleLogOutput(
        out = outContent.toString().removeThreadNames(),
        err = errContent.toString().removeThreadNames()
    )
}

/**
    Captures stderr/stdout logs, as in [captureConsoleLogs], and also logs destined for a log file via [OutPrinter]
    This is fundamentally not thread-safe.  Thankfully, tests do not run in parallel in a single process.
 */
fun captureLogs(f: () -> Unit): LogOutput {
    val fileContent = ByteArrayOutputStream()
    val printWriter =  object : PrintWriter(fileContent) {
        val testPrefix = "TEST_LOGGER: "
        override fun append(csq: CharSequence?): PrintWriter {
            return super.append("$testPrefix$csq")
        }

        override fun println(x: Any?) {
            super.println("$testPrefix$x")
            flush()
        }
    }

    OutPrinter.init(printWriter)
    val consoleOutput = try {
        captureConsoleLogs(f)
    } finally {
        OutPrinter.close()
    }

    return LogOutput(
        out = consoleOutput.out,
        err = consoleOutput.err,
        file = fileContent.toString().removeThreadNames()
    )
}

private enum class TestLoggerTypes : LoggerName {
    DUMMY1,
    DUMMY2,
    DUMMY3,
}

class LogTest {
    private fun testLevelsWithTopic(loggerType: LoggerName) {
        val logger = KotlinLoggingLogger(loggerType)

        logger.error { "error" }
        logger.warn { "warn" }
        logger.info { "info" }
        logger.debug { "debug" }
        logger.trace { "trace" }
    }

    @Test
    fun testLevelsCommon() {
        val (out, err) = captureConsoleLogs {
            testLevelsWithTopic(LoggerTypes.COMMON)
        }
        assertEquals("", out)
        assertEquals("ERROR COMMON - error", err)
    }

    @Test
    fun testLevelsCommonWithPrintWriter() {
        val (out, err, file) = captureLogs {
            testLevelsWithTopic(LoggerTypes.COMMON)
        }
        assertEquals("", out)
        assertEquals("ERROR COMMON - error", err)
        assertEquals("TEST_LOGGER: ERROR COMMON - error\nTEST_LOGGER: WARN COMMON - warn", file)
    }

    @Test
    fun testLevelsPointsTo() {
        val (out, err) = captureConsoleLogs {
            testLevelsWithTopic(LoggerTypes.POINTS_TO)
        }
        assertEquals("", out)
        assertEquals("ERROR POINTS_TO - error", err)
    }

    @Test
    fun testLevelsPointsToWithPrintWriter() {
        val (out, err, file) = captureLogs {
            testLevelsWithTopic(LoggerTypes.POINTS_TO)
        }
        assertEquals("", out)
        assertEquals("ERROR POINTS_TO - error", err)
        assertEquals("TEST_LOGGER: ERROR POINTS_TO - error\nTEST_LOGGER: WARN POINTS_TO - warn", file)
    }

    @Test
    fun testLevelsDummy1Enabled() {
        val (out, err) = captureConsoleLogs {
            logConfigScope {
                setLogLevel(TestLoggerTypes.DUMMY1, LoggerLevels.DEBUG)
                testLevelsWithTopic(TestLoggerTypes.DUMMY1)
            }
        }
        assertEquals("", out)
        assertEquals(
            (
                "ERROR DUMMY1 - error\n" +
                "WARN DUMMY1 - warn\n" +
                "INFO DUMMY1 - info\n" +
                "DEBUG DUMMY1 - debug"
            ),
            err
        )
    }

    @Test
    fun testLevelsDummy1EnabledWithPrintWriter() {
        val (out, err, file) = captureLogs {
            logConfigScope {
                setLogLevel(TestLoggerTypes.DUMMY1, LoggerLevels.DEBUG)
                testLevelsWithTopic(TestLoggerTypes.DUMMY1)
            }
        }
        assertEquals("", out)
        assertEquals(
            (
                "ERROR DUMMY1 - error\n" +
                "WARN DUMMY1 - warn\n" +
                "INFO DUMMY1 - info\n" +
                "DEBUG DUMMY1 - debug"
            ),
            err
        )
        assertEquals(
            (
                "TEST_LOGGER: ERROR DUMMY1 - error\n" +
                "TEST_LOGGER: WARN DUMMY1 - warn\n" +
                "TEST_LOGGER: INFO DUMMY1 - info\n" +
                "TEST_LOGGER: DEBUG DUMMY1 - debug"
            ),
            file
        )
    }

    @Test
    fun testLevelsDummy2EnabledTrace() {
        val (out, err) = captureConsoleLogs {
            logConfigScope {
                setLogLevel(TestLoggerTypes.DUMMY2, LoggerLevels.TRACE)
                testLevelsWithTopic(TestLoggerTypes.DUMMY2)
            }
        }
        assertEquals("", out)
        assertEquals(
            (
                "ERROR DUMMY2 - error\n" +
                "WARN DUMMY2 - warn\n" +
                "INFO DUMMY2 - info\n" +
                "DEBUG DUMMY2 - debug\n" +
                "TRACE DUMMY2 - trace"
            ),
            err
        )
    }

    @Test
    fun testLevelsDummy2EnabledTraceWithPrintWrite() {
        val (out, err, file) = captureLogs {
            logConfigScope {
                setLogLevel(TestLoggerTypes.DUMMY2, LoggerLevels.TRACE)
                testLevelsWithTopic(TestLoggerTypes.DUMMY2)
            }
        }
        assertEquals("", out)
        assertEquals(
            (
                "ERROR DUMMY2 - error\n" +
                "WARN DUMMY2 - warn\n" +
                "INFO DUMMY2 - info\n" +
                "DEBUG DUMMY2 - debug\n" +
                "TRACE DUMMY2 - trace"
            ),
            err
        )
        assertEquals(
            (
                "TEST_LOGGER: ERROR DUMMY2 - error\n" +
                "TEST_LOGGER: WARN DUMMY2 - warn\n" +
                "TEST_LOGGER: INFO DUMMY2 - info\n" +
                "TEST_LOGGER: DEBUG DUMMY2 - debug\n" +
                "TEST_LOGGER: TRACE DUMMY2 - trace"
            ),
            file
        )
    }

    @Test
    fun testLevelsDummy3EnabledDummy() {
        val (out, err) = captureConsoleLogs {
            testLevelsWithTopic(TestLoggerTypes.DUMMY3)
        }
        assertEquals("", out)
        assertEquals("ERROR DUMMY3 - error", err)
    }

    @Test
    fun testLevelsDummy3EnabledDummyWithPrintWriter() {
        val (out, err, file) = captureLogs {
            testLevelsWithTopic(TestLoggerTypes.DUMMY3)
        }
        assertEquals("", out)
        assertEquals("ERROR DUMMY3 - error", err)
        assertEquals("TEST_LOGGER: ERROR DUMMY3 - error\nTEST_LOGGER: WARN DUMMY3 - warn", file)
    }

    @Test
    fun testStaticLogger() {
        // NB: because we're running in the unit test framework, the static logging functions will use
        // TestLogger, rather than the CVT logger.  The output will be handled a bit differently as a result.
        val (out, err) = captureConsoleLogs {
            Logger.always("Not quiet", false)
            ConfigScope(Config.QuietMode, true).use {
                Logger.always("Quiet!", true)
            }
            ConfigScope(Config.QuietMode, false).use {
                Logger.alwaysError("error")
                Logger.alwaysWarn("warn")
            }
        }
        assertEquals("Not quiet\nError: error\nWarning: warn", out)
        assertEquals("ERROR ALWAYS - error", err)
    }

    @Test
    fun testErrorWithException() {
        val (out, err) = captureConsoleLogs {
            val logger = KotlinLoggingLogger(LoggerTypes.COMMON)
            logger.error(NullPointerException(), "got an error")
            // must not see stacktrace from a specific logger
        }
        assertEquals("", out)
        assertEquals("ERROR COMMON - got an error", err.lines()[0])
    }

    @Test
    fun testErrorWithExceptionOutPrinter() {
        val (out, err, file) = captureLogs {
            val logger = KotlinLoggingLogger(LoggerTypes.COMMON)
            logger.error(NullPointerException()) { "got an error" }
            // must not see stacktrace from a specific logger
        }
        assertEquals("", out)
        assertEquals("ERROR COMMON - got an error", err.lines()[0])
        val fileLines = file.lines()
        assertTrue(fileLines.size > 2, "Not enough file lines: $fileLines")
        assertEquals("TEST_LOGGER: ERROR COMMON - got an error", fileLines[0])
        assertEquals("TEST_LOGGER: java.lang.NullPointerException", fileLines[1])
    }

    @Test
    fun testWarningWithException() {
        val (out, err) = captureConsoleLogs {
            val logger = KotlinLoggingLogger(LoggerTypes.DECOMPILER)
            logger.warn(IllegalStateException()) { "got a warning" }
            // must not see message or stacktrace from a specific logger
        }
        assertEquals("", out)
        assertEquals("", err)
    }

    @Test
    fun testWarningWithExceptionOutPrinter() {
        val (out, err, file) = captureLogs {
            val logger = KotlinLoggingLogger(LoggerTypes.DECOMPILER)
            logger.warn(IllegalStateException()) { "got a warning" }
            // must not see message or stacktrace from a specific logger
        }
        assertEquals("", out)
        assertEquals("", err)
        val fileLines = file.lines()
        assertTrue(fileLines.size > 2, "Not enough file lines: $fileLines")
        assertEquals("TEST_LOGGER: WARN DECOMPILER - got a warning", fileLines[0])
        assertEquals("TEST_LOGGER: java.lang.IllegalStateException", fileLines[1])
    }
}

