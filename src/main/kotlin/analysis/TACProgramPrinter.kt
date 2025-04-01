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

package analysis

import algorithms.topologicalOrderOrNull
import analysis.TACProgramPrinter.Companion.standard
import rules.TWOSTAGE_META_VARORIGIN
import tac.MetaKey
import tac.MetaMap.Companion.nothing
import tac.NBId
import utils.Color.Companion.bBlack
import utils.Color.Companion.bCyan
import utils.Color.Companion.bGreenBg
import utils.Color.Companion.bMagenta
import utils.Color.Companion.bRed
import utils.Color.Companion.black
import utils.Color.Companion.blue
import utils.Color.Companion.cyan
import utils.Color.Companion.green
import utils.Color.Companion.redBg
import utils.Color.Companion.whiteBg
import utils.Color.Companion.yellow
import utils.Color.Companion.yellowBg
import utils.containsAny
import utils.letIf
import vc.data.*
import vc.data.TACMeta.CVL_EXP
import vc.data.tacexprutil.TACExprUtils.contains
import wasm.impCfg.WASM_BYTECODE_ADDRESS

private typealias CmdFilter = (LTACCmd) -> Boolean

/**
 * A tool to print a TAC program for debugging purposes.
 *
 * Can filter cmds to show, filter for which of them to show the meta map,
 * can highlight some of the commands, filter which of the meta-keys to show, and can add more lines with information
 * for cmds.
 *
 * For example see how [standard] creates an instance of this class. A useful use case is to show the lines that have
 * changes in a patching program interleaved with the original program. This is currently only implemented in
 * [ConcurrentPatchingProgram].
 */
class TACProgramPrinter {
    private val dontShowCmds = mutableListOf<CmdFilter>()
    private fun shouldShowCmd(lcmd: LTACCmd) = !dontShowCmds.any { it(lcmd) }

    private val showMetaFor = mutableListOf<CmdFilter>()
    private fun shouldShowMetaFor(lcmd: LTACCmd) = showMetaFor.any { it(lcmd) }

    private val highlight = mutableListOf<CmdFilter>()
    private fun shouldHighlight(lcmd: LTACCmd) = highlight.any { it(lcmd) }

    private val dontShowMeta = mutableListOf<(MetaKey<*>) -> Boolean>()
    private fun shouldShowMeta(k: MetaKey<*>) = !dontShowMeta.any { it(k) }

    private val extraLines = mutableListOf<(LTACCmd) -> List<String>>()
    private fun extraLines(lcmd: LTACCmd) = extraLines.flatMap { it(lcmd) }

    private val extraLhsInfo = mutableListOf<(CmdPointer) -> String>()
    private fun extraLhsInfo(lcmd: LTACCmd) = extraLhsInfo.map { it(lcmd.ptr) }

    private val extraBlockInfo = mutableListOf<(NBId) -> String>()
    private fun extraBlockInfo(nbid: NBId) = extraBlockInfo.map { it(nbid) }


    fun dontShowCmds(f: CmdFilter) =
        this.also { dontShowCmds += f }

    inline fun <reified T : TACCmd.Simple> dontShowCmds() =
        dontShowCmds { it.cmd is T }

    fun showMetaFor(f: CmdFilter) =
        this.also { showMetaFor += f }

    fun highlight(f: CmdFilter) =
        this.also { highlight += f }

    fun dontShowMeta(f: (MetaKey<*>) -> Boolean) =
        this.also { dontShowMeta += f }

    fun dontShowMeta(k: MetaKey<*>) =
        dontShowMeta { it == k }

    fun extraLines(f: (LTACCmd) -> List<String>) =
        this.also { extraLines += f }

    fun extraLhsInfo(f: (CmdPointer) -> String) =
        this.also { extraLhsInfo += f }

    fun extraBlockInfo(f: (NBId) -> String) =
        this.also { extraBlockInfo += f }

    companion object {
        fun standard() = TACProgramPrinter()
            .dontShowCmds<TACCmd.Simple.JumpdestCmd>()
            .dontShowCmds<TACCmd.Simple.LabelCmd>()
            .dontShowCmds<TACCmd.Simple.NopCmd>()
            .dontShowCmds { it.cmd is TACCmd.Simple.AnnotationCmd &&
                (it.cmd.getFreeVarsOfRhs().isEmpty() || it.cmd.annot.v is AssertSnippet<*>)
            }
            .dontShowMeta(CVL_EXP)
            .dontShowMeta(WASM_BYTECODE_ADDRESS)
            .dontShowMeta(META_INFO_KEY)
            .dontShowMeta(TWOSTAGE_META_VARORIGIN)
            .dontShowMeta(TACMeta.CVL_RANGE)
            .dontShowMeta(MetaKey<Int>("non-canonical-meta"))
            .dontShowMeta(MetaKey<Int>("non-canonical-message"))
            .dontShowMeta(MetaKey<Int>("non-canonical-annotation"))
            .showMetaFor { true }


        inline fun <reified T : TACExpr> expMarker(cmd: TACCmd.Simple) =
            cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                cmd.rhs.contains { it is T }

        /** This highlights the whole command. Highlighting only the var needs too much work... */
        fun symbolHighLighter(chosen: Set<TACSymbol>) = { cmd: TACCmd.Simple ->
            chosen.containsAny(cmd.freeVars())
        }
    }

    fun print(code: CoreTACProgram, header : String? = null): CoreTACProgram {
        @Suppress("ForbiddenMethodCall")
        println(toString(code, header))
        return code
    }

    fun print(code: CoreTACProgram, nbid: NBId): CoreTACProgram {
        @Suppress("ForbiddenMethodCall")
        println(toString(code.analysisCache.graph.elab(nbid), code.analysisCache.graph.succ(nbid)))
        return code
    }

    fun toString(code: CoreTACProgram, header : String?): String {
        val g = code.analysisCache.graph
        val sb = StringBuilder("\n")
        fun line(l: String = "") = sb.appendLine(l)

        val head = code.name.letIf(header != null) {
            "$it   [$header]"
        }
        line()
        line("*".repeat(80).yellowBg.bBlack)
        line("* ${head}${" ".repeat(maxOf(0, 76 - head.length))} *".yellowBg.bBlack)
        line("*".repeat(80).yellowBg.bBlack)
        line()
        line()

        val order = topologicalOrderOrNull(code.blockgraph, ignoreSelfLoops = true)?.reversed()
            ?: code.analysisCache.domination.topologicalOrder
        for (bid in order) {
            sb.append(
                toString(g.elab(bid), g.succ(bid))
            )
        }
        return sb.toString()
    }

    fun toString(block: TACBlock, succ: Set<NBId>): String {
        val sb = StringBuilder()
        fun line(l: String = "") = sb.appendLine(l)

        val bid = block.id
        val cmds = block.commands
        val blockInfo =
            if (extraBlockInfo.isEmpty()) {
                ""
            } else {
                " " + extraBlockInfo(bid).joinToString().bGreenBg.bBlack
            }
        line(bid.whiteBg.black + blockInfo)
        line("----------------------------------------------------------")
        cmds.filter { shouldShowCmd(it) }
            .forEach { sb.append(toString(it)) }
        if (!cmds.lastOrNull()?.cmd.let { it is TACCmd.Simple.JumpCmd || it is TACCmd.Simple.JumpiCmd } &&
            succ.isNotEmpty()) {
            line("Jump to ${succ.joinToString { it.bCyan }}")
        }
        line()
        return sb.toString()
    }

    private fun toString(lcmd: LTACCmd): String {
        val sb = StringBuilder()
        fun line(l: String = "") = sb.appendLine(l)

        val (ptr, cmd) = lcmd

        val lhsInfo =
            if (extraLhsInfo.isEmpty()) {
                ""
            } else {
                extraLhsInfo(lcmd).joinToString().blue + " "
            }

        fun ln(l: Any) {
            line("${ptr.pos}: $l".letIf(shouldHighlight(lcmd)) { it.redBg.bBlack })
            if (shouldShowMetaFor(lcmd)) {
                cmd.meta.map.entries
                    .filter { (k, _) -> shouldShowMeta(k) }
                    .forEach { (k, v) ->
                        if (v == nothing) {
                            line("         $k".yellow)
                        } else {
                            line("         $k := $v".yellow)
                        }
                    }
            }
        }

        fun default() =
            ln("${cmd.toStringNoMeta()} $lhsInfo")

        when (cmd) {
            is TACCmd.Simple.JumpCmd ->
                ln("Jump ${cmd.dst.bCyan}")

            is TACCmd.Simple.JumpiCmd ->
                ln("If ${cmd.cond.green} then ${cmd.dst.bCyan} else ${cmd.elseDst.bCyan}")

            is TACCmd.Simple.AnnotationCmd ->
                if (shouldShowMeta(cmd.annot.k)) {
                    ln("Annotation ${cmd.annot.k} := ${cmd.annot.v}".cyan)
                }

            is TACCmd.Simple.AssigningCmd.WordLoad ->
                ln("${cmd.lhs} := ${"${cmd.base}[${cmd.loc}]".blue}")

            is TACCmd.Simple.AssigningCmd.ByteLoad ->
                ln("${cmd.lhs} := ${"${cmd.base}[${cmd.loc}]".cyan}")

            is TACCmd.Simple.WordStore ->
                ln("${"${cmd.base}[${cmd.loc}]".blue} := ${cmd.value}")

            is TACCmd.Simple.AssigningCmd.ByteStore ->
                ln("${"${cmd.base}[${cmd.loc}]".cyan} := ${cmd.value}")

            is TACCmd.Simple.ByteLongCopy -> with(cmd) {
                ln("${"$dstBase[$dstOffset..]".cyan} := $srcBase[$srcOffset, length=$length]")
            }

            is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                ln("${cmd.lhs} $lhsInfo:= ${cmd.rhs}")

            is TACCmd.Simple.Assume ->
                ln("ASSUME ${cmd.condExpr}".bMagenta)

            is TACCmd.Simple.AssertCmd ->
                ln("ASSERT ${cmd.o}".bRed +" [${cmd.msg}]")

            else -> default()
        }

        for (line in extraLines(lcmd)) {
            line("    $line")
        }

        return sb.toString()
    }
}
