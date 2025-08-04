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

package decompiler

import annotation.*
import bridge.ContractInstanceInSDC
import config.*import extension.*
import kotlin.io.*
import kotlin.streams.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import loaders.WithResourceFile
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.*
import org.junit.jupiter.api.parallel.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*
import utils.*
import kotlin.time.Duration
import java.nio.file.*

/**
   Decompiles the sample EVM bytecode from resources under decompiler/examples/
 */
class DecompilerTest : WithResourceFile {
    @KSerializable
    data class Bytecode(val address: String, val DeployedCode: String = "", val ContractCreationCode: String = "")

    @ParameterizedTest
    @MethodSource("getContractInstances")
    @Execution(ExecutionMode.CONCURRENT)
    @Timeout(10 * 60)
    fun decompile(instance: ContractInstanceInSDC) {
        val disassembled = Disassembler.disassembleRuntimeBytecode(instance)
        try {
            Decompiler.decompileEVM(disassembled, instance)
        } catch (e: CertoraException) {
            // ignore these (sometimes expected) errors
        }
    }

    companion object {
        fun loadResourceFile(path: String) =
            DecompilerTest::class.java.classLoader.getResourceAsStream(path).bufferedReader().use {
                it.readText()
            }

        /**
          In normal test runs, we just use the relatively small set of examples from the resources.
         */
        fun getTestExamples() = loadResourceFile("decompiler/examples").lines().asSequence().mapNotNull { filename ->
            filename.takeUnless { it.isBlank() }?.let { it to loadResourceFile("decompiler/examples/$it") }
        }

        /**
          To run a more comprehensive test, set the environment variable CERTORA_DECOMPILER_EXAMPLES to a local path
          containing a set of JSON files.  For example, you might point this to a local clone of
          https://github.com/Certora/EthereumBytecodeDB/tree/master/1Sep2022
         */
        fun getExternalExamples() =
            System.getenv("CERTORA_DECOMPILER_EXAMPLES").also { println(it)}?.let {
                Files.walk(Path.of(it)).asSequence()
                .filter { it.fileName.toString().let { it.startsWith("example_") && it.endsWith(".json") }}
                .map { it.fileName.toString() to Files.readString(it) }
            }.orEmpty()

        @JvmStatic
        fun getContractInstances() =
            (getTestExamples() + getExternalExamples()).mapNotNull { (filename, file) ->
                try {
                    Json.decodeFromString<Bytecode>(file)
                } catch (e: Exception) {
                    println("Error parsing $filename: $e")
                    null
                }?.let { bytecode ->
                    ContractInstanceInSDC(
                        name = "",
                        file = filename,
                        address = bytecode.address.removePrefix("0x").toBigInteger(16),
                        is_static_address = false,
                        original_file = filename,
                        lang = bridge.SourceLanguage.Unknown,
                        methods = listOf(),
                        bytecode = bytecode.DeployedCode.removePrefix("0x"),
                        constructorBytecode = "",
                        srcmap = "",
                        varmap = "",
                        storageLayout = bridge.StorageLayout(),
                        transientStorageLayout = bridge.StorageLayout(),
                        srclist = mapOf(),
                        immutables = listOf(),
                        solidityTypes = setOf(),
                        sourceBytes = null,
                        internalFunctionHarnesses = mapOf(),
                    )
                }
            }
            .asIterable()
    }
}
