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

import datastructures.stdcollections.*
import kotlinx.serialization.Contextual
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import utils.Range
import utils.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

private val debugSymbolsLogger = Logger(LoggerTypes.DEBUG_SYMBOLS)

/**
 * The [DebugInfoReader] object provides utilities for extracting debug information from ELF files compiled with DWARF data.
 *
 * Specifically, it enables:
 * - Retrieving inlined call stack information for a list of bytecode addresses.
 * - Locating the source range of a function based on its mangled name.
 */
object DebugInfoReader: Closeable {
    /**
     * Path to the ELF file that has the debug information.
     */
    private var elfFile: String? = null
    private var llvmSymbolizer: LlvmSymbolizer? = null

    /**
     * Sets the ELF file to which the subsequent queries will refer to.
     */
    fun init(elfFile: String) {
        debugSymbolsLogger.info { "Inlined frames information extractor initialized." }
        this.elfFile = elfFile
        this.llvmSymbolizer = LlvmSymbolizer(elfFile)
    }

    /**
     * Returns the location of a function. The name of the function must be *mangled* to avoid ambiguity.
     * Returns a range only if the function exists in the sources directory.
     * Requires that the [init] method has been called on this object, otherwise throws an exception.
     */
    @Suppress("ForbiddenMethodCall")
    fun findFunctionRangeInSourcesDir(mangledName: String): Range.Range? {
        val elfFile = requireNotNull(this.elfFile) { "DebugInfoReader uninitialized" }

        // Prepare the command.
        val cmd = mutableListOf(
            "llvm-dwarfdump",
            "--name",
            mangledName,
            elfFile,
        )

        debugSymbolsLogger.info {
            "Running command to get range of function '$mangledName': ${cmd.joinToString(" ")}"
        }

        val pb = ProcessBuilder(cmd)
        val llvmDwarfDumpProcess = pb.start()
        val llvmDwarfDumpStdout = llvmDwarfDumpProcess.inputStream.bufferedReader().use { it.readText() }
        debugSymbolsLogger.info {
            "llvm-dwarfdump process stdout: $llvmDwarfDumpStdout"
        }

        if (llvmDwarfDumpProcess.waitFor() != 0) {
            val errorText = String(llvmDwarfDumpProcess.errorStream.use { it.readAllBytes() })
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.DIAGNOSABILITY,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = null,
                message = "Failed to read range of function '$mangledName' - proceeding without debug information.",
                hint = null
            )
            debugSymbolsLogger.warn { "Failed to read range of function '$mangledName' - proceeding without debug information, reason $errorText" }
            return null
        }

        // Regex to extract the source file name from a DWARF attribute line like:
        // DW_AT_decl_file ("src/module/file.rs")
        // This regex matches the string within double quotes after DW_AT_decl_file,
        // ensuring it captures only non-quote characters as the file path.
        val fileRegex = Regex("""DW_AT_decl_file\s+\("([^"]+)"\)""")

        // Regex to extract the line number where a symbol is declared from a line like:
        // DW_AT_decl_line (42)
        // It captures the integer value inside the parentheses.
        val lineRegex = Regex("""DW_AT_decl_line\s+\((\d+)\)""")

        // Regex to extract the column number (if available) from a line like:
        // DW_AT_decl_column (12)
        // It captures the integer value inside the parentheses.
        val columnRegex = Regex("""DW_AT_decl_column\s+\((\d+)\)""")

        val fileMatches = fileRegex.findAll(llvmDwarfDumpStdout).toList()
        val lineMatches = lineRegex.findAll(llvmDwarfDumpStdout).toList()
        val columnMatches = columnRegex.findAll(llvmDwarfDumpStdout).toList()

        if (fileMatches.size != 1 || lineMatches.size != 1) {
            debugSymbolsLogger.warn { "Warning: Unexpected number of file (${fileMatches.size}) or line (${lineMatches.size}) matches in DWARF dump. Proceeding without debug information." }
            return null
        }

        val file = fileMatches.first().groupValues[1]
        val line = lineMatches.first().groupValues[1].toUInt() - 1U

        val column = if (columnMatches.size == 1) {
            columnMatches.first().groupValues[1].toUInt() - 1U
        } else {
            if (columnMatches.size > 1) {
                debugSymbolsLogger.warn { "Warning: Multiple column matches (${columnMatches.size}) found, using the first one." }
                columnMatches.first().groupValues[1].toUInt() - 1U
            } else {
                // Column information is not essential: if we have the file and the line number, we can just use the
                // first column as a heuristic.
                0U
            }
        }

        val start = SourcePosition(line, column)
        val end = SourcePosition(line + 1U, 0U)
        val range = Range.Range(file, start, end)
        return if (range.fileExistsInSourcesDir()) {
            range
        } else {
            null
        }
    }

    /**
     * Returns the inlined frames for each address, but only the ones that exist in the sources directory.
     */
    fun getInlinedFramesInSourcesDir(addresses: List<ULong>): Map<ULong, List<Range.Range>> {
        return getInlinedFrames(addresses).mapValues { (_, inlinedFrames) ->
            inlinedFrames.filter { it.fileExistsInSourcesDir() }
        }
    }

    /**
     * For each input address, returns the list of inlined frames associated with that address.
     * If an address is not present in the result map, then there is no available debug information for that address.
     *
     * N.B.: the associated list of inlined frames for an address may be empty.
     *
     * The frames are represented as a list of ranges.
     * The frames are ordered: the first one corresponds to the innermost frame (i.e., the actual call site in the
     * source code where the bytecode address maps to), and subsequent frames represent the inner frames (i.e., the
     * chain of inlined calls leading to the innermost call site). The last frame is the outermost frame.
     * Requires that the [init] method has been called on this object, otherwise throws an exception.
     */
    fun getInlinedFrames(addresses: List<ULong>): Map<ULong, List<Range.Range>> {
        val llvmSymbolizer = requireNotNull(this.llvmSymbolizer) { "DebugInfoReader uninitialized" }

        return addresses.associateWith(llvmSymbolizer::translate)
    }

    /**
     * Converts an SBF address from the metadata of the given TAC command to a range.
     * Returns null if the SBF metadata is not present or if it is not possible to resolve the range information.
     * Tries to resolve the inlined frames associated also to previous SBF addresses until [address - windowSize].
     */
    fun sbfAddressToRangeWithHeuristic(
        sbfAddress: ULong,
    ): Range.Range? {
        val windowSize = 80U
        // Consider address, address - 8, address - 16, ..., address - (windowSize + 8)
        val addresses: MutableList<ULong> = mutableListOf()
        var nextAddress = sbfAddress
        // The first condition is to check the absence of underflows.
        while (sbfAddress <= nextAddress && sbfAddress - nextAddress <= windowSize) {
            addresses.add(nextAddress)
            nextAddress -= 8U
        }
        val rangesMap = this.getInlinedFrames(addresses)
        // Iterate over the addresses: address, address - 8, address - 16, ...
        // The first address that is associated with non-null range information will be the returned address.
        for (addr in addresses) {
            rangesMap[addr]?.let { ranges ->
                ranges.firstOrNull { range ->
                    return range
                }
            }
        }
        return null
    }

    override fun close() {
        this.llvmSymbolizer?.close()
    }
}

/** the output of `llvm-symbolizer` in JSON mode, simplified to only the data we care about */
@KSerializable
data class LlvmSymbolizerOutput(
    @Contextual
    val symbol: List<Symbol> = emptyList(),
)

@KSerializable
data class Symbol(
    val column: UInt,
    val discriminator: UInt,
    val fileName: String,
    val functionName: String,
    val line: UInt,
    val startAddress: String,
    val startFileName: String,
    val startLine: UInt
) {
    /**
     * [Symbol] can represent an unknown location in case the line or the column are zero.
     * In case the symbol is an unknown location, returns null.
     */
    fun toRange(): Range.Range? {
        val rangeLineNumber = this.line.checkedMinus(1U) ?: return null
        val rangeColNumber = this.column.checkedMinus(1U) ?: return null
        val sourcePositionStart = SourcePosition(rangeLineNumber, rangeColNumber)
        // Since llvm-symbolizer does not have the end information, we assume that the end is the first character in
        // the next line.
        val sourcePositionEnd = SourcePosition(rangeLineNumber + 1U, 0U)
        return Range.Range(this.fileName, sourcePositionStart, sourcePositionEnd)
    }
}

/** runs `llvm-symbolizer` in interactive mode and communicates with it via pipes */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private class LlvmSymbolizer(elfFile: String): Closeable {
    private val process: Process

    private val deserializer = Json {
        ignoreUnknownKeys = true

        /** rename to titlecase for serialization, since that's what `llvm-symbolizer` uses */
        namingStrategy = JsonNamingStrategy { _, _, serialName ->
            serialName.replaceFirstChar(Char::uppercaseChar)
        }
    }

    private val cache: ConcurrentHashMap<ULong, List<Range.Range>> = ConcurrentHashMap()

    init {
        val cmd = listOf("llvm-symbolizer", "--output-style", "JSON", "--exe", elfFile, "--inlines")
        this.process = ProcessBuilder(cmd).start()
    }

    private fun fetchOutput(addr: ULong): String {
        // from docs: calling these on the same process reuses the reader and writer.
        // thus we don't store them
        val writer = this.process.outputWriter()
        val reader = this.process.inputReader()

        writer.appendLine(addr.toString())
        writer.flush()
        return reader.readLine()
    }

    fun translate(addr: ULong): List<Range.Range> {
        return this.cache.getOrPut(addr) {
            val output = this.fetchOutput(addr)
            val deserialized: LlvmSymbolizerOutput = this.deserializer.decodeFromString(output)

            deserialized.symbol.mapNotNull(Symbol::toRange)
        }
    }

    /** needed because an interactive process would not close itself */
    override fun close() {
        this.process.destroy() // I believe this should also close the reader and writer
    }
}