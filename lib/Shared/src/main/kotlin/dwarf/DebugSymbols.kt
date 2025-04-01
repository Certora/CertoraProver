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

import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

object DebugSymbolLoader {
    /**
     * This method calls in a new process our own extension of gimli (https://github.com/gimli-rs/gimli). The extension uses
     * Gimli to parse the DWARF information from the file and serializes the required information to JSON.
     *
     * After calling the extension, this method then deserializes the JSON to the data structure [DebugSymbols]
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun generate(file: File, demangleNames: Boolean): DebugSymbols? {
        val cmd = listOf(
            "Gimli-DWARF-JSONDump",
            "-i",
            file.absolutePath,
        ) + if (demangleNames) {
            listOf("-d")
        } else {
            listOf()
        }
        debugSymbolsLogger.info { "Running command to generate DWARF debug information ${cmd.joinToString(" ")}" }

        val pb = ProcessBuilder(cmd)
        val jsonDwarfDump = pb.start()
        val debugSymbols =
            Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromStream<List<CompilationUnit>>(
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

        if (debugSymbols.isEmpty()) {
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.DIAGNOSABILITY,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = null,
                message = "No debug symbols are found in the file. The call trace will contain partial information only. " + "Please ensure to have the build options `strip = false` and `debug = 2` set in your Cargo.toml.",
                hint = null
            )
            return null
        }
        return DebugSymbols(debugSymbols)
    }
}


open class DebugSymbols(val compilationUnits: List<CompilationUnit>) {
    val childToParent: Map<DWARFTreeNode, DWARFTreeNode> = buildMap {
        compilationUnits.forEach { cu ->
            cu.dwarf_node.postOrderTraversal(parent = null) { curr, parent, _ ->
                parent?.let { p -> this[curr] = p }
            }
        }
    }

    private val allNodes: Set<DWARFTreeNode> = buildSet {
        compilationUnits.forEach { cu ->
            cu.dwarf_node.postOrderTraversal(parent = null) { curr, _, _ ->
                this.add(curr)
            }
        }
    }

    fun getMethods(): List<DWARFTreeNode> =
        allNodes.filter { it.dwarf_node_tag == DwTag.INLINED_SUBROUTINE || it.dwarf_node_tag == DwTag.SUBPROGRAMM }

    /**
     * DWARF information delivers information from instruction address to line and column of a file
     *
     * cb2 soroban-examples/account/src/lib.rs:119:26
     * cb9 soroban-examples/account/src/lib.rs:225:13
     * cc7 soroban-examples/account/src/lib.rs:0:13
     * cc7 end-sequence
     *
     * This information must be read as follows: The instruction at address cb2 starts at 119:25.
     * All addresses until cb9 refer to the same sequence of characters that ends at 225:13.
     *
     * Note: We expand the information (at the cost of memory) to simply lookup, i.e. we have the whole range as keys (cb2, cb3, cb4,...cb8)
     */
    private val instructionAddrToLineNumberInfo = run {
        compilationUnits.flatMap { it.line_number_info }.filter { it.address != null }.sortedBy { it.address!!.toInt() }
            .zipWithNext().flatMap { pair ->
                val start = pair.first
                val end = pair.second
                (start.address!!.toInt()..<end.address!!.toInt()).map { addr -> addr to pair.first }
            }.toMap()
    }

    open fun lookUpLineNumberInfo(addr: Int): LineNumberInfo? {
        return instructionAddrToLineNumberInfo[addr]
    }

}


@KSerializable
data class CompilationUnit(
    val dwarf_node: DWARFTreeNode, val address: String, val line_number_info: List<LineNumberInfo>
)

@Treapable
@KSerializable
data class LineNumberInfo(
    val address: Long? = null, val file_path: String? = null, val col: Long? = null, val line: Long? = null
) {
    /**
     * This function returns a [Range.Range] indicating which file and line:col information
     * this [DWARFTreeNode] belongs to.
     *
     * Note: It's rarely possible to resolve more than the original line number from the debug information.
     * I.e. we cannot restore column information. As a fallback this method currently highlights
     * the first three characters of the line.
     */
    fun getRange(): Range {
        // Therefore, we require that the line number is strictly positive.
        if (file_path == null || line == null) {
            return Range.Empty("No file information provided in the debug information")
        }

        // We must substract -1 from the line number as the DWARF debug information is 1-based, but we use 0-based source positions.
        val col = if (col == null) {
            0
        } else {
            col - 1
        }
        return Range.Range(
            file_path,
            SourcePosition((line - 1).toUInt(), col.toUInt()),
            SourcePosition((line - 1).toUInt(), (col + 3).toUInt())
        )
    }
}

/**
 * This data structure is a 1:1 mapping of what DWARF considers a "node" in its tree.
 * I.e. a node in the DWARF tree contains a list of children, plus many optional attributes. The type is defined by the attributes [dwarf_node_tag]
 * As the attributes are optional, we initialize all the values with null as default.
 *
 * For most of the fields of this data class, there is a corresponding DW_AT_<FIELD_NAME> attribute in DWARF. For details on what this attribute refers to
 * see https://dwarfstd.org/doc/DWARF5.pdf
 *
 * This allows us to keep the rust program that extract DWARF info (scripts/Gimli-DWARF-JSONDump/src/main.rs) minimal and don't introduce any additional
 * types before dumping it to JSON. As we don't transform the node in the rust program, it also means the information that end up in Kotlin, matches
 * the information we see when running llvm-dwarfdump. This should help debugging the debug information.
 */
@KSerializable
data class DWARFTreeNode(
    val children: List<DWARFTreeNode>, val dwarf_node_tag: DwTag = DwTag.NONE,

    /**
     * The low program counter value for which this debug information is valid for. Low and high pc values mark the range of addresses for
     * which the information that this [DWARFTreeNode] represent is valid for.
     *
     * Please note, in the case of WASM, this value is relative to the start of the code section offset. I.e. one must still add this offset to get valid addresses.
     */
    val low_pc_value: Long? = null,

    /**
     * The high program counter value for which this debug information is valid for. Low and high pc values mark the range of addresses for
     * which the information that this [DWARFTreeNode] represent is valid for.
     *
     * Please note, in the case of WASM, this value is relative to the start of the code section offset. I.e. one must still add this offset to get valid addresses.
     */
    val high_pc_value: Long? = null,

    /**
     * The depth of this node in the tree.
     */
    val depth: Int,

    /**
     * DW_AT_linkage_name of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val linkage_name: String? = null,

    /**
     * DW_AT_name of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val name: String? = null,

    /**
     * DW_AT_decl_line of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val decl_line: Int? = null,

    /**
     * DW_AT_decl_file of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val decl_file: String? = null,

    /**
     * DW_AT_call_line of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val call_line: Int? = null,

    /**
     * DW_AT_call_file of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val call_file: String? = null,

    /**
     * DW_AT_column of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val column: Int? = null,

    /**
     * DW_AT_type_name of DWARF, see https://dwarfstd.org/doc/DWARF5.pdf
     */
    val type_name: String? = null
) {

    fun getVariables(): List<DWARFTreeNode> {
        return this.children.filter { node ->
            node.dwarf_node_tag == DwTag.FORMAL_PARAMETER || node.dwarf_node_tag == DwTag.VARIABLE
        }
    }

    fun postOrderTraversal(
        depth: Int = 0, parent: DWARFTreeNode? = null, callback: (DWARFTreeNode, DWARFTreeNode?, Int) -> Unit
    ) {
        children.forEach { it.postOrderTraversal(depth + 1, this, callback) }
        callback(this, parent, depth)
    }

    fun hasName(): Boolean {
        return name != null || linkage_name != null
    }

    fun hasHighPcValue(): Boolean {
        return high_pc_value != null
    }

    fun hasLowPcValue(): Boolean {
        return low_pc_value != null
    }

    fun asName(): String {
        return if (!hasName()) {
            "<Unknown>${hashCode()}"
        } else {
            linkage_name ?: name!!
        }
    }

    /**
     * This function returns a [Range.Range] indicating which file and line:col information
     * this [DWARFTreeNode] belongs to.
     *
     * Note: It's rarely possible to resolve more than the original line number from the debug information.
     * I.e. we cannot restore column information. As a fallback this method currently highlights
     * the first three characters of the line.
     */
    fun getRange(): Range.Range? {
        if (decl_file == null && call_file == null) {
            return null
        }

        val file = decl_file.takeIf { it != null } ?: call_file!!
        val line = decl_line.takeIf { it != null } ?: call_line
        return line?.let { l ->
            Range.Range(
                file, SourcePosition((l - 1).toUInt(), (0).toUInt()), SourcePosition((l - 1).toUInt(), (3).toUInt())
            )
        }
    }

    override fun toString(): String {
        return "Name: ${
            asName()
        }, Type: ${dwarf_node_tag} Depth: ${depth} File: (${call_file ?: decl_file}) Line: ${
            if (call_line != null) {
                call_line
            } else {
                decl_line
            }
        }"
    }
}

@Serializable
enum class DwTag {
    /**
     * This NONE value is the default and all unhandled cases of serialization.
     * As we currently don't require, for instance, DW_TAG_structure_type or DW_TAG_array_type, both of them will map to NONE
     */
    NONE,

    @SerialName("DW_TAG_subprogram")
    SUBPROGRAMM,

    @SerialName("DW_TAG_inlined_subroutine")
    INLINED_SUBROUTINE,

    @SerialName("DW_TAG_compile_unit")
    COMPILE_UNIT,

    @SerialName("DW_TAG_namespace")
    NAMESPACE,

    @SerialName("DW_TAG_variable")
    VARIABLE,

    @SerialName("DW_TAG_formal_parameter")
    FORMAL_PARAMETER
}


