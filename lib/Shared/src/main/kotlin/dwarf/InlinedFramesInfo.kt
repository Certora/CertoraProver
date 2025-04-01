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

package dwarf

import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.SerialName
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import utils.Range
import utils.*
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

private val debugSymbolsLogger = Logger(LoggerTypes.DEBUG_SYMBOLS)

/**
 * The [InlinedFramesInfo] object provides functionality for retrieving inlined call stack information
 * for a list of bytecode addresses from a specified ELF file containing DWARF debug information.
 */
object InlinedFramesInfo {
    /**
     * Path to the ELF file that has the debug information.
     */
    private var elfFile: String? = null

    /**
     * Sets the ELF file to which the subsequent queries will refer to.
     */
    fun init(elfFile: String) {
        debugSymbolsLogger.info { "Inlined frames information extractor initialized." }
        this.elfFile = elfFile
    }

    /**
     * Finds the innermost inlined frame from the provided address that corresponds to an existing file located within
     * the Certora sources directory.
     * Requires that the [init] method has been called on this object, otherwise throws an exception.
     */
    fun findInnermostInlinedFrameInProjectRootDirectory(address: ULong): Range.Range? {
        val inlinedFramesMap = getInlinedFrames(listOf(address))
        return inlinedFramesMap[address]?.let {
            it.firstOrNull { range ->
                val file = File(Config.prependSourcesDir(range.file))
                debugSymbolsLogger.info {
                    "Considering range for file '${range.file}' at ${range.start.lineForIDE}:${range.start.characterForIDE}-${range.end.lineForIDE}:${range.end.characterForIDE}. File '$file' exists: ${file.exists()}"
                }
                // If the range is absolute, we ignore it: it is likely that llvm-symbolizer points to an absolute path
                // to a library function.
                val pathIsRelative = !Paths.get(range.file).isAbsolute
                pathIsRelative && file.exists()
            }
        }
    }

    /**
     * Returns the inlined frames for each address, but only the ones that exist in project files.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun getInlinedFramesInProjectFiles(addresses: List<ULong>): Map<ULong, List<Range.Range>> {
        return getInlinedFrames(addresses).mapValues{ (_, inlinedFrames) ->
            inlinedFrames.filter { range ->
                val file = File(Config.prependSourcesDir(range.file))
                debugSymbolsLogger.info {
                    "Considering range for file '${range.file}' at ${range.start.lineForIDE}:${range.start.characterForIDE}-${range.end.lineForIDE}:${range.end.characterForIDE}. File '$file' exists: ${file.exists()}"
                }
                // If the range is absolute, we ignore it: it is likely that llvm-symbolizer points to an absolute path
                // to a library function.
                val pathIsRelative = !Paths.get(range.file).isAbsolute
                pathIsRelative && file.exists()
            }
        }
    }

    /**
     * For each input address, returns the list of inlined frames associated with that address.
     * If an address is not present in the result map, then there is no available debug information for that address.
     * If an address is present in the result map, its associated list of inlined frames is non-empty.
     * The frames are represented as a list of ranges.
     * The frames are ordered: the first one corresponds to the innermost frame (i.e., the actual call site in the
     * source code where the bytecode address maps to), and subsequent frames represent the inner frames (i.e., the
     * chain of inlined calls leading to the innermost call site). The last frame is the outermost frame.
     * Requires that the [init] method has been called on this object, otherwise throws an exception.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun getInlinedFrames(addresses: List<ULong>): Map<ULong, List<Range.Range>> {
        assert(elfFile != null, { "called getInlinedFrames before initializing the ELF file path" })

        // Prepare the command.
        val cmd = mutableListOf(
            "llvm-symbolizer",
            "--output-style",
            "JSON",
            "--exe",
            elfFile,
            "--inlines" // Print all the inlined callstack
        )
        // Add all the addresses one at the time. Concatenating it into a string does not work in Kotlin to prevent shell injection attacks.
        val hexAddresses = addresses.map { addr -> "0x" + addr.toString(radix = 16).lowercase() }
        hexAddresses.forEach { address -> cmd.add(address) }
        val hexAddressesString = hexAddresses.joinToString(separator = " ")
        debugSymbolsLogger.info {
            "Running command to get addresses $hexAddressesString info: ${cmd.joinToString(" ")}"
        }

        // Execute the command.
        val pb = ProcessBuilder(cmd)
        val llvmSymbolizerProcess = pb.start()
        val llvmSymbolizerProcessStdout = llvmSymbolizerProcess.inputStream.bufferedReader().use { it.readText() }
        debugSymbolsLogger.info {
            "llvm-symbolizer process stdout: $llvmSymbolizerProcessStdout"
        }
        if (llvmSymbolizerProcess.waitFor() != 0) {
            val errorText = String(llvmSymbolizerProcess.errorStream.use { it.readAllBytes() })
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.DIAGNOSABILITY,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = null,
                message = "Failed to generate inlined frames for bytecode addresses $hexAddressesString - proceeding without debug information.",
                hint = null
            )
            debugSymbolsLogger.warn { "Failed to generate inlined frames for bytecode address $hexAddressesString - proceeding without debug information, reason $errorText" }
            return mapOf()
        }

        // Parse the output.
        val llvmSymbolizerOutputList =
            Json { ignoreUnknownKeys = true }.decodeFromString<List<LlvmSymbolizerOutput>>(
                llvmSymbolizerProcessStdout
            )

        // Extract the inlined frames from the output.
        val inlinedFrames: MutableMap<ULong, List<Range.Range>> = mutableMapOf()
        llvmSymbolizerOutputList.forEach { llvmSymbolizerOutput ->
            val resultEntry = llvmSymbolizerOutputToRange(llvmSymbolizerOutput)
            if (resultEntry != null) {
                inlinedFrames[resultEntry.first] = resultEntry.second
            }
        }
        debugSymbolsLogger.info { "Generated inlined frames: $inlinedFrames" }
        return inlinedFrames
    }

    /**
     * Maps the output to a pair (address, range), if the output represents a valid range.
     */
    @Suppress("ForbiddenMethodCall")
    private fun llvmSymbolizerOutputToRange(llvmSymbolizerOutput: LlvmSymbolizerOutput): Pair<ULong, List<Range.Range>>? {
        if (llvmSymbolizerOutput.address == null || llvmSymbolizerOutput.symbol == null) {
            // llvm-symbolizer does not have inlined frames information.
            return null
        } else if (!llvmSymbolizerOutput.address.startsWith("0x")) {
            // This should be unreachable, since llvm-symbolizer returns addresses that start with '0x'.
            // This check is here in case the API changes.
            debugSymbolsLogger.warn { "address '${llvmSymbolizerOutput.address}' does not start with '0x'" }
            return null
        } else {
            val uLongAddress =
                llvmSymbolizerOutput.address.substring(2) // Remove the initial 0x, since [toULongOrNull] assumes 0x is not present
                    .toULongOrNull(radix = 16)
            if (uLongAddress == null) {
                return null
            } else {
                val inlinedFrames = llvmSymbolizerOutput.symbol.mapNotNull { symbol -> symbolToRange(symbol) }
                return if (inlinedFrames.isEmpty()) {
                    // It is possible that no symbol can be converted to a CVL range: in this case we did not resolve
                    // the inlined frames information for the address.
                    null
                } else {
                    Pair(uLongAddress, inlinedFrames)
                }
            }
        }
    }

    /**
     * Return the corresponding [Range.Range].
     * [Symbol] can represent an unknown location in case the line or the column are zero.
     * In case the symbol is an unknown location, returns [null].
     */
    private fun symbolToRange(symbol: Symbol): Range.Range? {
        if (symbol.line == 0.toUInt() || symbol.column == 0.toUInt()) {
            return null
        } else {
            val rangeLineNumber = symbol.line - 1.toUInt()
            val rangeColNumber = symbol.column - 1.toUInt()
            val sourcePositionStart = SourcePosition(rangeLineNumber, rangeColNumber)
            // Since llvm-symbolizer does not have the end information, we assume that the end is the first character in
            // the next line.
            val sourcePositionEnd = SourcePosition(rangeLineNumber + 1.toUInt(), 0.toUInt())
            return Range.Range(symbol.fileName, sourcePositionStart, sourcePositionEnd)
        }
    }

}

/**
 * Represents an entry in the output of `llvm-symbolizer`.
 * The JSON output of the command can be directly parsed into a list of [LlvmSymbolizerOutput].
 * If the address is null, then the entry is not valid.
 * If a symbol inside the list of symbols has a line or column number equal to 0, then the tool could not resolve the
 * information about the symbol.
 */
@KSerializable
data class LlvmSymbolizerOutput(
    @SerialName("Address")
    val address: String? = null,
    @SerialName("ModuleName")
    val moduleName: String,
    @SerialName("Symbol")
    val symbol: List<Symbol>? = null,
)

@KSerializable
data class Symbol(
    @SerialName("Column")
    val column: UInt,
    @SerialName("Discriminator")
    val discriminator: UInt,
    @SerialName("FileName")
    val fileName: String,
    @SerialName("FunctionName")
    val functionName: String,
    @SerialName("Line")
    val line: UInt,
    @SerialName("StartAddress")
    val startAddress: String,
    @SerialName("StartFileName")
    val startFileName: String,
    @SerialName("StartLine")
    val startLine: UInt
)
