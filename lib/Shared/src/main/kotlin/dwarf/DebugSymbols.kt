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

import annotations.PollutesGlobalState
import com.certora.collect.*
import config.Config
import config.DebugAdapterProtocolMode
import datastructures.stdcollections.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import utils.Range
import utils.*
import java.io.File


private val debugSymbolsLogger = Logger(LoggerTypes.DEBUG_SYMBOLS)
private val sbfLogger = Logger(LoggerTypes.SBF)

object DebugSymbolLoader {
    /**
     * This method calls in a new process our own extension of gimli (https://github.com/gimli-rs/gimli). The extension uses
     * Gimli to parse the DWARF information from the file and serializes the required information to JSON.
     *
     * After calling the extension, this method then deserializes the JSON to the data structure [DebugSymbols]
     */
    @OptIn(ExperimentalSerializationApi::class, PollutesGlobalState::class)
    fun generate(file: File, demangleNames: Boolean): DebugSymbols? {
        val start = System.currentTimeMillis()
        val cmd = listOf(
            "Gimli-DWARF-JSONDump",
            "-i",
            file.absolutePath,
        ) + if (demangleNames) {
            listOf("-d")
        } else {
            listOf()
        } + if (Config.CallTraceDebugAdapterProtocol.get() == DebugAdapterProtocolMode.VARIABLES) {
            listOf("-v")
        } else {
            listOf()
        }
        debugSymbolsLogger.info { "Running command to generate DWARF debug information ${cmd.joinToString(" ")}" }

        val pb = ProcessBuilder(cmd)
        val jsonDwarfDump = pb.start()
        val debugSymbols =
            Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromStream<DWARFDebugInformation>(
                jsonDwarfDump.inputStream
            )
        if (jsonDwarfDump.waitFor() != 0) {
            val errorText = String(jsonDwarfDump.errorStream.use { it.readAllBytes() })
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.DIAGNOSABILITY,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = null,
                message = "Failed to generate JSON dump of DWARF debug information - proceeding without debug information.",
                hint = null
            )
            debugSymbolsLogger.warn { "Failed to generate JSON dump of DWARF debug information - proceeding without debug information, reason ${errorText}" }
            return null
        }

        if (debugSymbols.compilation_units.isEmpty()) {
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.DIAGNOSABILITY,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = null,
                message = "No debug symbols are found in the file. The call trace will contain partial information only. " + "Please ensure to have the build options `strip = false` and `debug = 2` set in your Cargo.toml.",
                hint = null
            )
            return null
        }
        val end = System.currentTimeMillis()
        val parsingErrors = debugSymbols.parsing_errors
        if (parsingErrors.isNotEmpty()) {
            debugSymbolsLogger.debug { "Encountered the ${parsingErrors.size} parsing errors while parsing DWARF file.\n" + parsingErrors.joinToString("\n") }
        }
        RustType.intializeRegistry(debugSymbols.type_nodes)
        sbfLogger.info { "Loading debug symbols took ${(end - start) / 1000}s" }
        return DebugSymbols(debugSymbols)
    }
}



@KSerializable
data class DWARFDebugInformation(
    val type_nodes: Map<RustTypeId, RustType> = mapOf(),
    val compilation_units: List<CompilationUnit> = listOf(),
    val parsing_errors: List<String> = listOf()
)

class DebugSymbols(debugInfo: DWARFDebugInformation) {
    val compilationUnits = debugInfo.compilation_units

    fun lookUpLineNumberInfo(addr: ULong): LineNumberInfo? {
        val matchingCompilationUnit = this.compilationUnits.filter { it.isInRanges(addr) };
        check(matchingCompilationUnit.size <= 1) { "Found ${matchingCompilationUnit.size} compilation units matching the address ${addr}, ranges: ${matchingCompilationUnit.mapIndexed { idx, it -> "CU($idx): ${it.ranges}" }}" }
        return matchingCompilationUnit.firstOrNull()?.lookUpLineNumberInfo(addr)
    }

    fun getMethodByNameAndAddress(name: String, addr: ULong): Subprogram? {
        val res = this.compilationUnits.mapNotNull { it.getSubprogramByName(name) }.filter { it.isInRanges(addr) }
        if (res.isEmpty()) {
            return null
        }
        check(res.size == 1) { "Expecting to find exactly one method with the name {i}" }
        return res.firstOrNull()
    }
}


@KSerializable
data class CompilationUnit(
    val ranges: List<AddressRange>,
    val line_number_info: List<LineNumberInfo>,
    val subprograms: List<Subprogram>,
    val parsing_errors: List<String>
) {
    private val subprogramsByLinkageName = subprograms.associateBy { it.linkage_name }
    private val subprogramsByMethodName = subprograms.associateBy { it.method_name }

    private val instructionAddrToLineNumberInfo = run {
        this.line_number_info
            .filter { it.address != null }
            // Filtering out line or column numbers that are zero, these lead to jumpiness in the debugger
            .filter { it.col != 0L && it.line != 0L }
            .sortedBy { it.address }
            .map { it.address!!.toULong() to it }
            .toMap()
    }

    fun lookUpLineNumberInfo(addr: ULong): LineNumberInfo? {
        return instructionAddrToLineNumberInfo[addr]
    }

    fun isInRanges(addr: ULong): Boolean {
        return this.ranges.any { it.inRange(addr) }
    }

    /**
     * Returns the subprogram with the matching [name].
     * [name] can be the mangled or the demangled method name.
     */
    fun getSubprogramByName(name: String): Subprogram? {
        return subprogramsByLinkageName[name] ?: subprogramsByMethodName[name]
    }
}

@Treapable
@KSerializable
data class DWARFOperationsList(
    val operations: List<DWARFOperation>, val range: AddressRange
)

@Treapable
@KSerializable
data class LineNumberInfo(
    val address: Long? = null, val file_path: String? = null, val col: Long? = null, val line: Long? = null
) {
    /**
     * This function returns a [Range.Range] translating the line number information to our Range.
     *
     * Note: It's rarely possible to resolve more than the original line number from the debug information.
     * I.e. we cannot restore column information. As a fallback this method currently highlights
     * the first three characters of the line.
     */
    fun asRange(): Range.Range? {
        if (file_path == null || line == null) {
            return null
        }

        // Subtracting 1 here from line and col. SourcePosition operates 0-based,
        // while DWARF debug information is 1-based
        val zeroBasedColumn = col?.letIf(col > 0) { it - 1 } ?: 0
        return Range.Range(
            file_path,
            SourcePosition((line - 1).toUInt(), zeroBasedColumn.toUInt()),
            SourcePosition((line - 1).toUInt(), (zeroBasedColumn + 3).toUInt())
        )
    }
}

@KSerializable
@Treapable
data class AddressRange(val start: ULong, val end: ULong) {
    fun inRange(addr: ULong): Boolean {
        return start <= addr && addr < end
    }

    override fun toString(): String {
        return "[$start, $end) // [0x${start.toString(16)}, 0x${end.toString(16)})"
    }
}

@Serializable
data class SourceRange(
    /**
     * The file path for the range
     */
    val file_path: String,

    /**
     * The 0-based line number for the range
     */
    val line: ULong,

    /**
     * An optional column number for the range.
     */
    val col: ULong? = null,
) {
    init {
        check(line >= 1UL) { "Offset in DWARF debug information is 1-based." }
    }

    fun asRange(): Range.Range {
        // Subtracting 1 here from line and col. SourcePosition operates 0-based,
        // while DWARF debug information is 1-based
        val zeroBasedColum = col?.let { (it - 1UL).toUInt() } ?: 0U
        return Range.Range(
            file_path,
            SourcePosition((line - 1UL).toUInt(), zeroBasedColum),
            SourcePosition(line.toUInt(), 0U));
    }
}

//Note that this class is _not_ a data class as we want to compare it via the object identity hashCode and equals
@Serializable
class Subprogram(
    val method_name: String,
    val linkage_name: String, //The mangled name of the function
    val variables: List<Variable>,
    val decl_range: SourceRange,
    val address_ranges: List<AddressRange>,
    val inlined_methods: List<InlinedMethod>
) : DwarfMethod {
    override fun getDeclRange(): Range.Range {
        return decl_range.asRange()
    }

    override fun isInRanges(addr: ULong): Boolean {
        return address_ranges.any { it.inRange(addr) }
    }

    override fun getVars(): List<Variable> = this.variables

    override fun getDepth(): ULong {
        return 0UL
    }

    override fun getMethodName(): String {
        return this.method_name
    }
}

sealed interface DwarfMethod {
    fun getDeclRange(): Range.Range
    fun isInRanges(addr: ULong): Boolean
    fun getVars(): List<Variable>
    fun getDepth(): ULong
    fun getMethodName(): String
}

//Note that this class is _not_ a data class as we want to compare it via the object identity hashCode and equals
@Serializable
class InlinedMethod(
    val inline_depth: ULong,
    val call_site_range: SourceRange,
    val method_name: String,
    val variables: List<Variable>,
    val decl_range: SourceRange,
    val address_ranges: List<AddressRange>
) : DwarfMethod {
    fun getRange(): Range.Range {
        return this.call_site_range.asRange()
    }

    override fun getDeclRange(): Range.Range {
        return this.decl_range.asRange()
    }

    override fun isInRanges(addr: ULong): Boolean {
        return address_ranges.any { it.inRange(addr) }
    }

    override fun getVars(): List<Variable> = this.variables

    override fun getDepth(): ULong {
        return this.inline_depth
    }

    override fun getMethodName(): String {
        return this.method_name
    }
}

@Serializable
data class Variable(
    /**
     * The bytecode address range where this variable information is valid for.
     */
    val address_ranges: List<AddressRange>,

    /**
     * encode the list of debug information in which register etc the variable is currently maintained.
     */
    val register_locations: List<DWARFOperationsList> = emptyList(),

    /**
     * The name of the variable
     */
    val var_name: String,

    /**
     * The actual type of the variable
     */
    private val var_type_id: RustTypeId,
) {
    fun getType() = RustType.resolve(var_type_id)
}
