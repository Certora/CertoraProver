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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen.fuzzer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import datastructures.stdcollections.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import prefixgen.Compiler
import utils.*

/**
 * Runs a forge fuzz test in the [workingDirectory].
 * By convention, the tests are expected to live in [testDirName],
 * and this runner follows that convention.
 */
class FuzzRunner(private val workingDirectory: Path) {
    private val testDirName = "test"
    private val testDir = workingDirectory.resolve(testDirName)

    /**
     * Used to model the JSON returned by Foundry. Top level is a map from
     * the test name to the [SingleTestResult]. Despite the name of this class, the key in the map
     * is actually `fileName:TestName`
     */
    @KSerializable
    private data class FileTestResult(
        @SerialName("test_results")
        val testResults: Map<String, SingleTestResult>
    )

    /**
     * A single test result has a [status] and a nullable/optional [counterexample]
     */
    @KSerializable
    private data class SingleTestResult(
        val status: String,
        val counterexample: FuzzTestCEXWrapper?
    )

    /**
     * Each counterexample wraps another object with just the field "Single" (which
     * we call [data] in the kotlin repr).
     */
    @KSerializable
    private data class FuzzTestCEXWrapper(
        @SerialName("Single")
        val data: FuzzTestCEX
    )

    /**
     * That "Single" field actually holds the raw, hex representation of the calldata that gave the CEX
     */
    @KSerializable
    private data class FuzzTestCEX(
        val calldata: String
    )


    companion object {
        /**
         * There are other fields in the data returned by foundry which we don't care about, so
         * tell our deserializer to ignore the unknown keys.
         */
        val fuzzReader = Json {
            ignoreUnknownKeys = true
        }
    }

    /**
     * Exceptions if something goes wrong.
     */
    class InvalidSolidityTestException(val errorStream: String) : RuntimeException("Fuzz test compilation failed")
    class InvalidForgeOutputException(val stdout: String, val exitCode: Int, cause: Throwable) : RuntimeException(
        "Invalid fuzz test output", cause
    )

    /**
     * Runs the [testFiles], which are Solidity source files containing foundry fuzz tests,
     * returning a [Result] mapping the original path names
     * to the calldata counterexample or null if there was no CEX found.
     *
     * NB: any existing tests in [testDirName] are deleted, and then [testFiles] are copied
     * into this directory. The basenames of all of the tests in [testFiles] must be unique;
     * as this is usually called with all files from a specific directory, we don't
     * bother actually checking this precondition.
     */
    fun fuzzTests(testFiles: Sequence<Path>) : Result<Map<Path, String?>> {
        testDir.listDirectoryEntries("*.sol").forEach(Files::delete)
        val expected = mutableMapOf<String, Path>()
        for(p in testFiles) {
            val baseName = p.fileName
            expected["$testDirName/$baseName:${Compiler.fuzzContractName}"] = p
            Files.copy(p, testDir.resolve(baseName))
        }
        return runCatching {
            val p = ProcessBuilder().command(
                "forge", "test", "--json", "--mt", Compiler.fuzzFunctionName
            ).directory(workingDirectory.toFile()).start()
            val res = p.waitFor()

            val errStream = p.errorReader().readText()
            // no better way to detect this AFAICT
            @Suppress("ForbiddenMethodCall")
            if(errStream.contains("Compilation failed")) {
                throw InvalidSolidityTestException(
                    p.inputReader().readText()
                )
            }

            val rawOuput = p.inputReader().readText()

            val parsed = try {
                fuzzReader.decodeFromString<Map<String, FileTestResult>>(rawOuput)
            } catch(t: SerializationException) {
                throw InvalidForgeOutputException(rawOuput, res, t)
            } catch(t: IllegalArgumentException) {
                throw InvalidForgeOutputException(rawOuput, res, t)
            }
            val toRet = mutableMapOf<Path, String?>()
            for((t, perFileRes) in parsed) {
                val sourcePath = expected[t] ?: continue
                toRet[sourcePath] = perFileRes.testResults.entries.singleOrNull()?.value?.counterexample?.data?.calldata?.let {
                    // sanity check for parsing string data
                    @Suppress("ForbiddenMethodCall")
                    check(it.startsWith("0x"))
                    it.substring(2)
                }
            }
            toRet
        }
    }
}
