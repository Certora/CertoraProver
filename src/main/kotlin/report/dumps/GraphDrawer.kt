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

package report.dumps
import config.Config
import config.Config.IsGenerateDotFiles
import datastructures.NonEmptyList
import datastructures.stdcollections.*
import kotlinx.coroutines.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import log.*
import org.apache.commons.lang3.SystemUtils
import parallel.Scheduler
import parallel.ParallelPool.Companion.runInherit
import report.dumps.CodeMap.Companion.errorColor
import tac.IBlockId
import utils.ArtifactFileUtils
import utils.`impossible!`
import utils.safeCommandArrayExecLines
import vc.data.TACCmd
import vc.data.TACProgram
import java.util.concurrent.ExecutionException

private val logger = Logger(LoggerTypes.COMMON)

/**
 * This file is concerned with the drawing of graph dumps via graphviz
 */

// may align with this but we're not using all these features http://www.graphviz.org/doc/info/lang.html
/**
 * A container for IDs of nodes in dot format.
 * The [id] will be the actual label, but keeping the original NBId (here: IBlockId) [originalNodeId] is useful
 */
data class DotId(val id: String, val originalNodeId: IBlockId)

typealias DotLabel = String

interface DotFormattable {
    fun format(attr: DotAttr) = "\"${attr.name}\"=\"${format()}\""
    fun formatAsKey() = "\"${format()}\""
    fun format(): String
}

enum class DotAttr {
    color,
    fillcolor,
    fontcolor,
    style,
    label,
    id,
    penwidth
    ;

    fun format(v: DotFormattable) = v.format(this)
    fun format(l: List<DotFormattable>) = object : DotFormattable {
        override fun format(): String = l.joinToString(",")
    }.format(this)

    fun format(v: String) = object : DotFormattable {
        override fun format(): String = v
    }.format(this)
}

/**
 * must be taken from SVG scheme https://www.graphviz.org/doc/info/colors.html#svg
 */
enum class DotColor : DotFormattable {

    lightgray, // d3d3d3
    crimson,
    violet,
    orangered,
    darkturquoise,
    lawngreen,
    gold,
    darkorange,
    maroon,
    pink,
    limegreen,
    green,
    red,
    yellow,
    orange,
    brown,
    greenyellow,
    antiquewhite,
    black,
    white,
    purple,

    // difficulties - from light to dark
    plum1,
    plum2,
    plum3,
    purple1,
    purple2,
    purple3,
    purple4,

    //unsat core
    chartreuse3, //in unsat core "#66cd00"
    indianred2, //not in unsat core "#fcd700"
    ;

    override fun format() = this.name

    val asDotColorList: DotColorList get() = DotColorList(this)
}

/**
 * Dot colorList, as described in `https://graphviz.org/docs/attr-types/colorList/`.
 * Note that the "weight" feature isn't implemented here, yet, should be easy to add when needed.
 */
class DotColorList(val colors: NonEmptyList<DotColor>): DotFormattable {
    constructor(vararg color: DotColor) : this(
        color.toList().let {
            if (it.isEmpty()) {
                NonEmptyList(errorColor)
            } else {
                NonEmptyList(it.first(), it.drop(1))
            }
        }
    )
    constructor(color: DotColor) : this(NonEmptyList(color))

    override fun format(): String {
        /**
         * if we ever want the "weighted" style, it goes like this:
         *  i.e. colors can get a weight, as a float, which is appended with a `;`
         *  this would give everything the same weight:
         *   ```
         *   val evenSplit = 1F / colors.size
         *   return colors.joinToString(separator = ";${evenSplit}:") { it.format() }
         *   ```
         */
        return colors.joinToString(separator = ":") { it.format() }
    }

    /** Used for creating gradients in html code (inline css or something..); returns a pair of colors in string-form.
     * Ignores any colors but the first two. If there is only one color, uses that as first and second element in
     * the pair.*/
    val asCommaSeparatedColorPair: String get() {
        val first = colors.head
        val second = if (colors.size > 1) {
            colors.tail.first()
        } else {
            colors.head
        }
        return "$first, $second"
    }

}

enum class DotStyle : DotFormattable {
    bold,
    solid,
    dashed,
    filled
    ;

    override fun format() = this.name
}

class DotLabelAttr(val text: String) : DotFormattable {
    @Suppress("ForbiddenMethodCall") // no I'm not going to write a lexer to fix this properly
    private fun String.dotEscape() = this.replace("[\n\\\\\"]".toRegex()) { res: MatchResult ->
        require(res.value.length == 1)
        when(res.value[0]) {
            '\n' -> "\\n"
            '\\' -> "\\\\"
            '"' -> "\\\""
            else -> `impossible!`
        }
    }

    override fun format(): String = "${text.dotEscape()}"
}

class DotToolTip(val text: String): DotFormattable {
    override fun format(): String = "tooltip=\"$text\""
}

data class DotNode(
    val id: DotId,
    val fillcolor: DotColorList,
    val color: DotColor,
    val penwidth: Double,
    val styles: List<DotStyle>,
    val tooltip: DotToolTip?,
    val originalNodeId: IBlockId,
    val label: DotLabelAttr
) : DotFormattable {
    val fontcolor = if (fillcolor == DotColorList(DotColor.purple4)) {
        DotColor.white
    } else {
        DotColor.black
    }
    override fun toString(): String {
        val attributesString = listOfNotNull(
            color.format(DotAttr.color),
            DotAttr.fillcolor.format(fillcolor),
            fontcolor.format(DotAttr.fontcolor),
            DotAttr.id.format(id.id),
            DotAttr.penwidth.format(penwidth.toString()),
            styles.takeIf { it.isNotEmpty() }?.let { DotAttr.style.format(it) },
            tooltip?.format(),
            DotAttr.label.format(label)
        ).joinToString(", ")
        return "${this.formatAsKey()} [$attributesString]"
    }
    override fun format() = id.id
}

data class DotEdge(val src: DotId, val trg: DotId, val label: DotLabel, val color: DotColor) : DotFormattable {
    override fun toString(): String =
        "${this.formatAsKey()} [${DotAttr.label.format(label)},${DotAttr.color.format(color)}]" // no IDs on edges

    override fun formatAsKey(): String = "\"${src.id}\" -> \"${trg.id}\""
    override fun format(): String = "${src.id}t${trg.id}"
}

data class DotDigraph(val name: String, val nodes: List<DotNode>, val edges: List<DotEdge>) {
    override fun toString(): String =
        "digraph \"$name\" { \n" +
                "${nodes.joinToString("\n")}\n" +
                "${edges.joinToString("\n")} " +
                "\n}"

    fun withinGraphvizLimits(): Boolean {
        val numNodes = nodes.size
        val limit = Config.GraphDrawLimit.get()
        return numNodes < limit
    }
}

@Suppress("DEPRECATION")
fun renderGraphToSVGString(name: String, digraph: DotDigraph): String {
    try {
        return Scheduler.rpc(Config.SimpleTaskTimeout.get().seconds) {
            try {
                // let's keep our dot file
                val dotInputString = digraph.toString()
                val dotFilename = ArtifactManagerFactory().fitFileLength("${name}.dot", ".dot")
                if (IsGenerateDotFiles.get()) {
                    ArtifactManagerFactory().writeArtifact(dotFilename, StaticArtifactLocation.Debugs) { dotInputString }
                }
                val dotFile = ArtifactManagerFactory().getRegisteredArtifactPathOrNull("${name}.dot")

                val cmd = listOf(
                    "dot",
                    "-Tsvg"
                )

                // ugh we are appending to the same file sometimes! Warrants a bigger cleanup due to sanitations.
                // For now, appending something random
                val (exitcode, lines) = safeCommandArrayExecLines(
                    cmd.toTypedArray(),
                    "dot_${ArtifactFileUtils.sanitizePath(name)}${Random.nextInt()}.svg",
                    true,
                    false,
                    60,
                    dotInputString,
                    critical = false
                )

                if (exitcode != 0) {
                    throw Exception("dot command failed with code $exitcode for $name")
                }

                lines.dropWhile { !it.startsWith("<svg") } // graphviz adds a bunch of junk at the beginning like <xml and comments
                    .joinToString("\n") +
                        if (dotFile != null) {
                            "\n<!--Based on ${ArtifactFileUtils.getBasenameOnly(dotFile)} -->"
                        } else {
                            ""
                        }
            } catch (interrupt: Exception) {
                logger.info { "Graphviz interrupted: $interrupt" }
                // Try to kill all dot executions - windows
                if (SystemUtils.IS_OS_WINDOWS) {
                    val cmd = "taskkill /F /IM \"dot.exe\" /T"
                    logger.info { "Terminating all active dot processes: $cmd" }
                    @Suppress("DEPRECATION")
                    Runtime.getRuntime().exec(cmd)
                } else if (SystemUtils.IS_OS_LINUX) {
                    val cmd = "kill -f \"dot\""
                    logger.info { "Terminating all active dot processes: $cmd" }
                    @Suppress("DEPRECATION")
                    Runtime.getRuntime().exec(cmd)
                }
                "Graphviz was interrupted: $interrupt"
            }
        }.runInherit()
    } catch (e: Exception) {
        return when (e) {
            is CancellationException,
            is ExecutionException -> {
                logger.info(e) { "Graphviz was cancelled, likely due to a timeout" }
                "Graphviz was cancelled, likely due to a timeout"
            }
            else -> {
                logger.warn(e) { "Failed to run graphviz for $name" }
                "Not available"
            }
        }

    }
}


private const val PENWIDTH_DEFAULT = 1.0
fun convertProgramGraphToDot(program: TACProgram<*>, name: String): DotDigraph {

    val nodes = mutableListOf<DotNode>()
    val edges = mutableListOf<DotEdge>()

    fun getDotId(n: IBlockId): Pair<DotId, DotLabelAttr> {
        val cmds = program.code[n] ?: listOf()
        return DotId(n.toString(), n)  to if (cmds.size == 1 && cmds.first() is TACCmd.Simple.LabelCmd) {
            val labelCmd = cmds.first() as TACCmd.Simple.LabelCmd
            val msg = labelCmd._msg.let {
                val parallelAssignMsg = "Parallel assignment"
                @Suppress("ForbiddenMethodCall")
                if (parallelAssignMsg in it) {
                    "${parallelAssignMsg}${it.hashCode()}" // trim it, and uniqueness is important
                } else {
                    it
                }
            }
            DotLabelAttr(msg)
        } else {
            DotLabelAttr(n.toString())
        }
    }

    fun getNode(n: IBlockId): DotNode {
        val cmds = program.code[n] ?: emptyList<TACCmd>()
        val defaultFillcolor = DotColor.white
        val (fillcolor, styles) =
            if (cmds.singleOrNull() is TACCmd.Simple.LabelCmd) {
                defaultFillcolor to listOf(DotStyle.dashed)
            } else {
                when (cmds.lastOrNull()) {
                    null -> DotColor.antiquewhite to listOf(DotStyle.solid)
                    is TACCmd.EVM.StopCmd -> defaultFillcolor to listOf(DotStyle.bold)
                    is TACCmd.Simple.RevertCmd -> DotColor.pink to listOf(DotStyle.bold)
                    is TACCmd.EVM.InvalidCmd -> DotColor.red to listOf(DotStyle.bold, DotStyle.filled)
                    is TACCmd.Simple.ReturnCmd -> DotColor.green to listOf(DotStyle.bold)
                    is TACCmd.Simple.ReturnSymCmd -> DotColor.green to listOf(DotStyle.bold)
                    is TACCmd.EVM.SelfdestructCmd -> DotColor.greenyellow to listOf(DotStyle.bold, DotStyle.filled)
                    else -> defaultFillcolor to emptyList()
                }
            }

        val defaultBorderColor = DotColor.black
        val (nodeId, nodeLabel) = getDotId(n)
        return DotNode(nodeId, DotColorList(fillcolor), defaultBorderColor, PENWIDTH_DEFAULT, styles, tooltip = null, n, label = nodeLabel)
    }

    fun getEdgeLabel(src: IBlockId, trg: IBlockId): DotLabel {
        val srcCode = program.code[src]!!
        val lastCmd = srcCode.lastOrNull()
        return if (lastCmd != null && lastCmd is TACCmd.Simple.JumpiCmd) {
            if (lastCmd.dst == trg) {
                "${lastCmd.cond}"
            } else {
                "!${lastCmd.cond}"
            }
        } else {
            ""
        }
    }

    fun getEdge(src: IBlockId, trg: IBlockId): DotEdge {
        val label = getEdgeLabel(src, trg)
        return DotEdge(getDotId(src).first, getDotId(trg).first, label, DotColor.black)
    }

    program.blockgraph.forEach { (n, succs) ->
        if (!program.code.containsKey(n)) {
            logger.error { "Key ${n} does not exist in blocks list" }
        }

        val node = getNode(n)
        nodes.add(node)
        succs.map { succ ->
            val edge = getEdge(n, succ)
            edges.add(edge)
        }
    }

    return DotDigraph(
        name,
        nodes,
        edges
    )
}
