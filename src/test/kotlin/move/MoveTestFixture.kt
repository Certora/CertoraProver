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

package move

import annotations.*
import com.certora.sui.MoveBuildModule
import com.dylibso.chicory.log.*
import com.dylibso.chicory.runtime.*
import com.dylibso.chicory.wasi.*
import com.dylibso.chicory.wasm.*
import config.*
import java.io.*
import java.math.BigInteger
import java.nio.file.*
import java.util.zip.GZIPInputStream
import kotlin.io.*
import kotlin.io.path.*
import kotlinx.coroutines.*
import log.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.*
import report.*
import scene.*
import scene.source.*
import smt.*
import spec.cvlast.*
import spec.rules.*
import testutils.*
import utils.*
import verifier.*

abstract class MoveTestFixture() {
    open val loadStdlib: Boolean get() = false
    open val additionalDependencies: List<Path> get() = emptyList()
    open val additionalAddresses: Map<String, BigInteger> get() = emptyMap()

    @TempDir
    protected lateinit var moveDir: Path

    companion object {
        /**
            Shared Move source code to access the CVT built-in functions.
         */
        private val cvlmSource = """
            module cvlm::asserts {
                public native fun cvlm_assert(cond: bool);
                public native fun cvlm_assert_msg(cond: bool, msg: vector<u8>);
                public native fun cvlm_assume(cond: bool);
                public native fun cvlm_assume_msg(cond: bool, msg: vector<u8>);
            }
            module cvlm::nondet {
                public native fun nondet<T>(): T;
            }
            module cvlm::manifest {
                public native fun rule(ruleFunName: vector<u8>);
                public native fun summary(
                    summaryFunName: vector<u8>,
                    summarizedFunAddr: address,
                    summarizedFunModule: vector<u8>,
                    summarizedFunName: vector<u8>
                );
                public native fun ghost(ghostFunName: vector<u8>);
                public native fun hash(hashFunName: vector<u8>);
                public native fun shadow(shadowFunName: vector<u8>);
                public native fun field_access(accessorFunName: vector<u8>, fieldName: vector<u8>);
            }
            module cvlm::ghost {
                public native fun ghost_write<T>(ref: &mut T, value: T);
                public native fun ghost_read<T>(ref: &T): T;
            }
            module cvlm::conversions {
                public native fun u256_to_address(n: u256): address;
            }
            module cvlm::math_int {
                public native struct MathInt has copy, drop;
                public native fun from_u256(value: u256): MathInt;
                public native fun add(a: MathInt, b: MathInt): MathInt;
                public native fun sub(a: MathInt, b: MathInt): MathInt;
                public native fun mul(a: MathInt, b: MathInt): MathInt;
                public native fun div(a: MathInt, b: MathInt): MathInt;
                public native fun mod(a: MathInt, b: MathInt): MathInt;
                public native fun lt(a: MathInt, b: MathInt): bool;
                public native fun le(a: MathInt, b: MathInt): bool;
                public native fun gt(a: MathInt, b: MathInt): bool;
                public native fun ge(a: MathInt, b: MathInt): bool;
            }
        """.trimIndent()

        @JvmStatic
        protected val testModule = """
            #[allow(unused_use, unused_function)]
            module test_addr::test;
            use cvlm::asserts::{cvlm_assert, cvlm_assert_msg, cvlm_assume, cvlm_assume_msg};
            use cvlm::nondet::nondet;
            use cvlm::ghost::{ghost_write, ghost_read};
            use cvlm::conversions::u256_to_address;
            use cvlm::math_int;
            fun cvlm_manifest() {
                cvlm::manifest::rule(b"test");
            }
        """.trimIndent()

        @JvmStatic
        protected val testModuleAddr = 123

        @JvmStatic
        protected val testModuleName = MoveModuleName(testModuleAddr, "test")

        val moveStdlibPath = Paths.get(MoveTestFixture::class.java.getResource("stdlib").toURI())

        /**
            Utility to get a Move expression for the maximum integer value of a given size in bits.
         */
        @JvmStatic
        protected fun maxInt(size: Int) = "0x${"ff".repeat(size / 8)}u$size"
    }

    /**
        Adds the given Move source to [moveDir], for later compilation.
     */
    protected fun addMoveSource(source: String) {
        moveDir.createDirectories()
        createTempFile(moveDir, "test", ".move").toFile().writeText(source)
    }


    /**
        Runs the Move compiler (move-build).  Compiles all of the .move files in [moveDir], outputting the results to
        the same directory.

        To update the Move compiler, see https://github.com/Certora/sui-move-build-kotlin/blob/main/README.md.
     */
    private fun moveBuild() {
        val wasiOptions = WasiOptions.builder()
            .inheritSystem() // inherit stdin/stdout/stderr
            .withArguments(
                listOf(
                    "move-build",
                    // "--help",
                    "--bytecode-version", "6",
                    "--addresses", "std=0x1",
                    "--addresses", "cvlm=0x${Config.CvlmAddress.get().toString(16)}",
                    "--addresses", "test_addr=0x${testModuleAddr.toString(16)}",
                    "--out-dir", moveDir.toString(),
                    moveDir.toString()
                ) + runIf(loadStdlib) {
                    listOf(moveStdlibPath.toString())
                }.orEmpty() + additionalDependencies.map {
                    it.toString()
                } + additionalAddresses.flatMap { (name, addr) ->
                    listOf("--addresses", "$name=0x${addr.toString(16)}")
                }
            )
            .withDirectory(moveDir.toString(), moveDir)
            .withDirectory(moveStdlibPath.toString(), moveStdlibPath)
            .let {
                additionalDependencies.fold(it) { acc, path -> acc.withDirectory(path.toString(), path) }
            }
            .build()

        WasiPreview1.builder().withOptions(wasiOptions).build().use { wasi ->
            val imports = ImportValues.builder()
                .addFunction(*wasi.toHostFunctions())
                .build()

            Instance.builder(MoveBuildModule.load())
                .withMachineFactory(MoveBuildModule::create)
                .withImportValues(imports)
                .withInitialize(true)
                .withStart(true)
                .build()
        }
    }

    /**
        Builds a [MoveScene] from the sources in [moveDir], using the given [specModule] as the spec.
     */
    private fun buildScene(optimize: Boolean): MoveScene {
        addMoveSource(cvlmSource)
        moveBuild()
        return MoveScene(moveDir, optimize = optimize)
    }

    val moveLogger = TestLogger(LoggerTypes.MOVE)

    @OptIn(PollutesGlobalState::class) // This function is for debugging only
    private fun maybeEnableReportGeneration(): Boolean {
        if (moveLogger.isDebugEnabled) {
            CommandLineParser.setExecNameAndDirectory()
            ConfigType.WithArtifacts.set(log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts)
            Config.Smt.DumpAll.set(SmtDumpEnum.TOFILE)
            Config.ShouldDeleteSMTFiles.set(false)
            return true
        }
        return false
    }

    protected fun compileToMoveTAC(
        assumeNoTraps: Boolean = true,
        recursionLimit: Int = 3,
        recursionLimitIsError: Boolean = false,
        loopIter: Int = 1,
        optimize: Boolean = false
    ): MoveTACProgram {
        maybeEnableReportGeneration()
        ConfigScope(Config.TrapAsAssert, !assumeNoTraps)
            .extend(Config.QuietMode, true)
            .extend(Config.RecursionErrorAsAssertInAllCases, recursionLimitIsError)
            .extend(Config.RecursionEntryLimit, recursionLimit)
            .extend(Config.LoopUnrollConstant, loopIter)
            .use {
                val moveScene = buildScene(optimize)
                val (selected, type) = moveScene.cvlmManifest.selectedRules.singleOrNull() ?: error("Expected exactly one rule")
                check(type == CvlmManifest.RuleType.USER_RULE) {
                    "Expected a user rule, but got $type"
                }
                return MoveToTAC.compileMoveTAC(selected, moveScene) ?: error("Couldn't get MoveTAC for $selected")
            }
    }

    /**
        Verifies the rules in the current set of modules.

        We don't optimize by default, which improves test execution time (since these are all very small programs).
        Tests that are explicitly testing the optimization pass can set the `optimize` parameter to true.
    */
    protected fun verify(
        assumeNoTraps: Boolean = true,
        recursionLimit: Int = 3,
        recursionLimitIsError: Boolean = false,
        loopIter: Int = 1,
        optimize: Boolean = false
    ): Boolean {
        maybeEnableReportGeneration()
        ConfigScope(Config.TrapAsAssert, !assumeNoTraps)
        .extend(Config.QuietMode, true)
        .extend(Config.RecursionErrorAsAssertInAllCases, recursionLimitIsError)
        .extend(Config.RecursionEntryLimit, recursionLimit)
        .extend(Config.LoopUnrollConstant, loopIter)
        .use {
            val moveScene = buildScene(optimize)
            val (_, program) = moveScene.rules.singleOrNull() ?: error("Expected exactly one rule")

            val scene = SceneFactory.getScene(DegenerateContractSource("dummyScene"))

            val vRes = runBlocking {
                TACVerifier.verify(scene, program, DummyLiveStatsReporter)
            }
            val joinedRes = Verifier.JoinedResult(vRes)

            // Fake rule to allow report generation
            val reportRule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
                AssertRule(RuleIdentifier.freshIdentifier(program.name), false, program.name, scope)
            }
            joinedRes.reportOutput(reportRule)
            return joinedRes.finalResult.isSuccess()
        }
    }
}

class MoveTestFixtureTest : MoveTestFixture() {
    @Test
    fun testVerifierSuccess() {
        addMoveSource("""
            $testModule
            public fun test(a: u32) {
                cvlm_assert(a == a);
            }
        """.trimIndent())

        assertTrue(verify())
    }

    @Test
    fun testVerifierFailure() {
        addMoveSource("""
            $testModule
            public fun test(a: u32, b: u32) {
                cvlm_assert(a + b == 12);
            }
        """.trimIndent())

        assertFalse(verify())
    }
}
