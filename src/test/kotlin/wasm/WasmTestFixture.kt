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

package wasm

import analysis.maybeAnnotation
import annotations.PollutesGlobalState
import config.*
import kotlinx.coroutines.runBlocking
import log.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.*
import parallel.coroutines.*
import report.*
import scene.*
import scene.source.*
import smt.SmtDumpEnum
import spec.cvlast.*
import spec.rules.AssertRule
import testutils.TestLogger
import utils.*
import vc.data.*
import verifier.*
import wasm.host.*
import wasm.impCfg.*
import wasm.ir.*
import wasm.wat.*
import java.nio.file.*
import java.util.stream.Collectors

val certoraAssert = WatImport("env", "CERTORA_assert_c", listOf(i32))
val certoraAssume = WatImport("env", "CERTORA_assume_c", listOf(i32))

abstract class WasmTestFixture {
    @TempDir
    private lateinit var tempDir: Path

    abstract val host: WasmHost

    val wasmLogger = TestLogger(LoggerTypes.WASM)

    @OptIn(PollutesGlobalState::class) // This function is for debugging only
    fun maybeEnableReportGeneration(): Boolean {
        if (wasmLogger.isDebugEnabled) {
            CommandLineParser.setExecNameAndDirectory()
            ConfigType.WithArtifacts.set(log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts)
            Config.Smt.DumpAll.set(SmtDumpEnum.TOFILE)
            Config.ShouldDeleteSMTFiles.set(false)
            return true
        }
        return false
    }

    /**
        Verifies the provides wasm rule using the provided host.
    */
    fun verifyWasm(
        wat: String,
        entry: String,
        assumeNoTraps: Boolean = false,
        allowUnresolvedCalls: Boolean = false,
        recursionLimit: Int = 3,
        recursionLimitIsError: Boolean = false,
        optimize: Boolean = true
    ): Boolean {
        val report = maybeEnableReportGeneration()
        var res = false
        ConfigScope(Config.TrapAsAssert, !assumeNoTraps)
        .extend(Config.QuietMode, true)
        .extend(Config.RecursionErrorAsAssertInAllCases, recursionLimitIsError)
        .extend(Config.RecursionEntryLimit, recursionLimit)
        .use {
            val wasmFile = watToWasm(wat)
            val program = WasmEntryPoint.wasmToTAC(wasmFile.toFile(), setOf(entry), host, optimize).single().code

            if (!allowUnresolvedCalls) {
                assertEquals(setOf<WasmId>(), program.findUnresolveCalls()) {
                    "Unresolved calls found in program"
                }
            }

            val scene = SceneFactory.getScene(DegenerateContractSource("dummyScene"))

            val vRes = runBlocking {
                TACVerifier.verify(scene, program, DummyLiveStatsReporter)
            }
            val joinedRes = Verifier.JoinedResult(vRes)

            // Fake rule to allow report generation
            val rule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
                AssertRule(RuleIdentifier.freshIdentifier(program.name), false, program.name, scope)
            }
            joinedRes.reportOutput(rule)
            res = joinedRes.finalResult.isSuccess()

            if (report) {
                ArtifactManagerFactory().registerArtifact("test.wat") { handle ->
                    ArtifactFileUtils.getWriterForFile(handle, overwrite = true, append = false).use { w ->
                        w.append(wat)
                    }
                }
            }
        }
        return res
    }

    fun verifyWasm(assumeNoTraps: Boolean = false, allowUnresolvedCalls: Boolean = false, buildFunc: WatFuncBuilder.() -> Unit): Boolean {
        return verifyWasm(
            watFunc("entry", buildFunc),
            "entry",
            assumeNoTraps = assumeNoTraps,
            allowUnresolvedCalls = allowUnresolvedCalls
        )
    }


    /**
        Taken a wat String as input generates a wasm file using wat2wasm.
        Returns the Path to the generated wasm file.
     */
    fun watToWasm(wat: String): Path {
        if (!this::tempDir.isInitialized) {
            tempDir = Files.createTempDirectory("wasm")
        }
        val input = tempDir.resolve("input.wat")
        input.toFile().writeText(wat)

        val wasm = tempDir.resolve("output.wasm")
        val wat2wasm = ProcessBuilder("wat2wasm", "$input", "--debug-names", "--output=$wasm").start()
        if (wat2wasm.waitFor() != 0) {
            val errorText = String(wat2wasm.getErrorStream().use { it.readAllBytes() })
            throw IllegalArgumentException("wat2wasm failed: $errorText")
        }
        return wasm
    }


    /**
        Checks that the program has no unresolved wasm calls. This does *not* check inter-contract calls!
    */
    fun CoreTACProgram.findUnresolveCalls() = parallelLtacStream().mapNotNull {
        it.maybeAnnotation(WASM_UNRESOLVED_CALL)
    }.collect(Collectors.toSet())
}
