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

package verifier.mus

import algorithms.compactGraph
import analysis.LTACCmd
import datastructures.reverse
import datastructures.stdcollections.*
import report.calltrace.CVLReportLabel
import report.dumps.CodeMap
import tac.NBId
import utils.Color.Companion.bBlack
import utils.Color.Companion.bCyan
import utils.Color.Companion.bMagenta
import utils.Color.Companion.bRed
import utils.Color.Companion.blue
import utils.Color.Companion.cyan
import utils.Color.Companion.green
import utils.Color.Companion.greenBg
import vc.data.*
import vc.data.TACMeta

/**
 * Simple, stable printer for unsat core TAC programs.
 *
 * This printer has a minimal, predictable format designed specifically for unsat core analysis.
 * Data collection happens in the constructor, and formatting happens in print().
 *
 * Format:
 * ```
 * === BlockId (call:FunctionName)
 * [in UC] pos: command
 *   meta: value
 * [context] pos: command
 *
 * CFG Edges:
 *   target <- {source1,source2}
 * ```
 *
 * @param originalTac The original TAC program
 * @param codeMap The code map containing the unsat core and CFG edges
 * @param contextLength Number of commands before each UC command to show as context
 */
class UnsatCorePrinter(
    private val originalTac: CoreTACProgram,
    private val codeMap: CodeMap,
    private val contextLength: Int = 5
) {

    /**
     * Cmd-level meta keys relevant for source mapping.
     * These are all attached to TACCmd.Simple, not to variables.
     */
    private val relevantCmdMetaKeys = setOf(
        TACMeta.CVL_LABEL_START,             // cmd: CVL label/section name
        TACMeta.CVL_USER_DEFINED_ASSERT,     // cmd: assert comes directly from a CVL `assert`
        TACMeta.CVL_USER_DEFINED_ASSUME,     // cmd: assume comes from a CVL `require`
        TACMeta.IS_STORAGE_ACCESS,           // cmd: this command reads/writes contract storage
        TACMeta.IS_TRANSIENT_STORAGE_ACCESS, // cmd: this command reads/writes transient storage
        TACMeta.STORAGE_PATHS,               // cmd: non-indexed storage path (e.g. myMapping[key])
        TACMeta.CVL_ASSUME_INVARIANT_CMD_START, // cmd: which invariant this assume enforces
        TACMeta.ASSERT_ID,                   // cmd: unique id for assert commands
    )

    private data class CommandToPrint(
        val lcmd: LTACCmd,
        val isInUc: Boolean,
    )

    private data class BlockToPrint(
        val nbid: NBId,
        val callName: String,
        val commands: List<CommandToPrint>
    )

    // Pre-computed data
    private val blocksToPrint: List<BlockToPrint>
    private val compactedGraph: Map<NBId, Set<NBId>>

    init {
        val ucPositions = originalTac.ltacStream()
            .filter { it.cmd in codeMap.unsatCore }
            .toList()
            .groupBy({ it.ptr.block }, { it.ptr.pos })
            .mapValues { (_, positions) -> positions.toSet() }

        val fullAdjList = codeMap.edges.keys.groupBy({ it.src }, { it.trg })
        compactedGraph = compactGraph(ucPositions.keys.toSet(), fullAdjList::get)

        // Calculate positions to show (UC + context)
        val positionsToShow = ucPositions.mapValues { (_, positions) ->
            buildSet {
                positions.forEach { ucPos ->
                    for (i in maxOf(0, ucPos - contextLength)..ucPos) {
                        add(i)
                    }
                }
            }
        }

        // Helper to determine if a command should be skipped
        fun skipCmd(lcmd: LTACCmd, pos: Int, nbid: NBId): Boolean {
            // Skip if position not in range to show
            if (pos !in positionsToShow[nbid].orEmpty()) {
                return true
            }

            // Filter out NopCmd
            if (lcmd.cmd is TACCmd.Simple.NopCmd) {
                return true
            }

            // Filter out non-useful AnnotationCmds
            if (lcmd.cmd is TACCmd.Simple.AnnotationCmd) {
                // Filter out cvl.label.end (just integer markers)
                if (lcmd.cmd.annot.k == TACMeta.CVL_LABEL_END) {
                    return true
                }
                // Filter out CVLReportLabel.Message with empty string
                val annotValue = lcmd.cmd.annot.v
                if (annotValue is CVLReportLabel.Message && annotValue.s.isBlank()) {
                    return true
                }
            }

            return false
        }

        // Collect blocks and commands to print
        blocksToPrint = ucPositions.keys.sortedBy { it.toString() }.mapNotNull blockLoop@{ nbid ->
            val block = originalTac.analysisCache.graph.elab(nbid)
            val callId = nbid.calleeIdx
            val callName = codeMap.callGraphInfo.callIdToName[callId] ?: "unknown"

            val commands = block.commands.mapNotNull commandLoop@{ lcmd ->
                val pos = lcmd.ptr.pos
                if (skipCmd(lcmd, pos, nbid)) {
                    return@commandLoop null
                }

                val isInUc = pos in ucPositions[nbid].orEmpty()

                CommandToPrint(lcmd, isInUc)
            }

            if (commands.isEmpty()) {
                null
            } else {
                BlockToPrint(nbid, callName, commands)
            }
        }
    }

    /**
     * Prints the unsat core with optional color formatting.
     *
     * @param enableColors Whether to include ANSI color codes in the output
     */
    fun print(enableColors: Boolean = true): String {
        // Conditional color extension functions
        fun String.cyanIf() = if (enableColors) { this.cyan } else { this }
        fun String.bCyanIf() = if (enableColors) { this.bCyan } else { this }
        fun String.blueIf() = if (enableColors) { this.blue } else { this }
        fun String.greenIf() = if (enableColors) { this.green } else { this }
        fun String.bMagentaIf() = if (enableColors) { this.bMagenta } else { this }
        fun String.bRedIf() = if (enableColors) { this.bRed } else { this }
        fun String.greenBgBlackIf() = if (enableColors) { this.greenBg.bBlack } else { this }

        // Format a TAC command for display
        fun formatCommand(cmd: TACCmd.Simple): String {
            return when (cmd) {
                is TACCmd.Simple.JumpCmd ->
                    "Jump ${cmd.dst.toString().bCyanIf()}"

                is TACCmd.Simple.JumpiCmd ->
                    "If ${cmd.cond.toString().greenIf()} then ${cmd.dst.toString().bCyanIf()} else ${cmd.elseDst.toString().bCyanIf()}"

                is TACCmd.Simple.AnnotationCmd ->
                    "Annotation ${cmd.annot.k} := ${cmd.annot.v}".cyanIf()

                is TACCmd.Simple.AssigningCmd.WordLoad ->
                    "${cmd.lhs} := ${"${cmd.base}[${cmd.loc}]".blueIf()}"

                is TACCmd.Simple.AssigningCmd.ByteLoad ->
                    "${cmd.lhs} := ${"${cmd.base}[${cmd.loc}]".cyanIf()}"

                is TACCmd.Simple.WordStore ->
                    "${"${cmd.base}[${cmd.loc}]".blueIf()} := ${cmd.value}"

                is TACCmd.Simple.AssigningCmd.ByteStore ->
                    "${"${cmd.base}[${cmd.loc}]".cyanIf()} := ${cmd.value}"

                is TACCmd.Simple.ByteLongCopy ->
                    "${"${cmd.dstBase}[${cmd.dstOffset}..]".cyanIf()} := ${cmd.srcBase}[${cmd.srcOffset}, length=${cmd.length}]"

                is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                    "${cmd.lhs} := ${cmd.rhs}"

                is TACCmd.Simple.Assume ->
                    "ASSUME ${cmd.condExpr}".bMagentaIf() + if(cmd is TACCmd.Simple.AssumeCmd){"[${cmd.msg}]"} else {""}

                is TACCmd.Simple.AssertCmd ->
                    "ASSERT ${cmd.o}".bRedIf() + " [${cmd.msg}]"

                else -> cmd.toStringNoMeta()
            }
        }

        val sb = StringBuilder()

        // Print header
        sb.appendLine()
        sb.appendLine("=".repeat(80))
        sb.appendLine("= UNSAT CORE: ${originalTac.name}")
        sb.appendLine("=".repeat(80))
        sb.appendLine()

        // Print pre-computed blocks
        for (blockData in blocksToPrint) {
            sb.appendLine("=== ${blockData.nbid} (call:${blockData.callName})")

            for (cmdData in blockData.commands) {
                val prefix = if (cmdData.isInUc) { "[in UC]" } else { "[context]" }

                // Format command
                var cmdStr = formatCommand(cmdData.lcmd.cmd)

                // Truncate context commands if too long
                if (!cmdData.isInUc && cmdStr.length > 200) {
                    val prefixPart = cmdStr.take(80)
                    val suffixPart = cmdStr.takeLast(80)
                    cmdStr = "$prefixPart...truncated...$suffixPart"
                }

                val sourceInfo = cmdData.lcmd.cmd.sourceOrCVLRange?.let { "($it)" }.orEmpty()
                val cmdLine = "$prefix ${cmdData.lcmd.ptr.pos}: $cmdStr $sourceInfo".trim()
                val coloredLine = if (cmdData.isInUc) { cmdLine.greenBgBlackIf() } else { cmdLine }
                sb.appendLine(coloredLine)

                // Print cmd-level metas useful for source mapping
                for ((key, value) in cmdData.lcmd.cmd.meta.map) {
                    if (key in relevantCmdMetaKeys) {
                        sb.appendLine("  [cmd] $key: $value")
                    }
                }

                // Print variable-level metas for variables referenced in this command
                // (display names, CVL types, storage paths, contract names)
                for (v in cmdData.lcmd.cmd.freeVars()) {
                    val parts = buildList {
                        v.meta[TACMeta.CVL_DISPLAY_NAME]?.let { add("display=$it") }
                        v.meta.find(TACMeta.CVL_STRUCT_PATH)?.let { add("structPath=$it") }
                        v.meta.find(TACMeta.DISPLAY_PATHS)?.let { add("storagePaths=$it") }
                        v.meta.find(TACMeta.STABLE_STORAGE_PATH)?.let { add("stableStoragePath=$it") }
                        v.meta.find(TACMeta.CONTRACT_ADDR_KEY_NAME)?.let { add("contract=$it") }
                        if (v.meta[TACMeta.CVL_VAR] == true) {
                            add("isCvlVar=true")
                        }
                    }
                    if (parts.isNotEmpty()) {
                        sb.appendLine("  [var] $v: ${parts.joinToString(", ")}")
                    }
                }
            }

            sb.appendLine()
        }

        // Print pre-computed CFG edges
        sb.appendLine("CFG Edges (compacted to UC blocks):")
        if (compactedGraph.isEmpty()) {
            sb.appendLine("  (none)")
        } else {
            // Group by target (in-edges) rather than source (out-edges) for space efficiency,
            // as in-degree is typically higher than out-degree in CFGs
            for ((target, sources) in reverse(compactedGraph)) {
                sb.appendLine("  $target <- {${sources.joinToString(",")}}")
            }
        }

        return sb.toString()
    }
}
