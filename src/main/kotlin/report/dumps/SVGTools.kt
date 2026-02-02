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

import log.Logger
import log.LoggerTypes
import solver.CounterexampleModel
import tac.CallId
import tac.Edge
import tac.NBId
import vc.data.TACExpr
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

@Suppress("NAME_SHADOWING")
fun addAnchorsToSVGGraphAndWrapInDiv(svg: String, id: CallId): String {
    return addSvgId(svg, id).let { svg ->
        addRegularNodeLinks(svg).let { svg ->
            addInterproceduralLinks(svg).let { svg ->

                val visibility = if (id == MAIN_GRAPH_ID) {
                    "visible"
                } else {
                    "hidden"
                }

                // TODO: Not here?
                val putInDiv =
"""
<div id="svgOf$id" style="position:absolute;left:40%;top:0;overflow:scroll;height:90%;visibility:${visibility}">$svg</div>
"""

                putInDiv
            }
        }
    }

}

private fun addSvgId(svg: String, id: CallId): String {
    val pattern = Regex("<svg ")
    return svg.replace(pattern, "<svg id=\"theSVG${if (id != MAIN_GRAPH_ID) id else ""}\" ")
}


// serious crimes against string_ops going on here ..
private fun addInterproceduralLinks(svg: String): String {
    // regex adapted from this stack overflow: https://stackoverflow.com/a/1732454
    val patternCalls = "<text (.*)>([0-9]+): ([^<]+)</text>"
    val regexCalls = Regex(patternCalls)
    val new = svg.replace(regexCalls) { matchRes ->
        val textAttr = matchRes.destructured.component1()
        val num = matchRes.destructured.component2()
        val funcName = matchRes.destructured.component3()
        "<a href=\"#\" onclick=\"toggleSVG('${num}'); return false;\"><text $textAttr>${num}: ${funcName}</text></a>"
    }
    return new
}

private fun addRegularNodeLinks(svg: String): String {
    val pattern = "<text (.*)>([0-9_]+)</text>" // unfortunately, we need to annotate the nodes contents with anchors
    val regex = Regex(pattern)
    val new = svg.replace(regex) { matchRes ->
        val textAttr = matchRes.destructured.component1()
        val blockNum = matchRes.destructured.component2()
        "<a href=\"#\" onclick=\"highlightAnchor('$blockNum'); return false;\"><text $textAttr>$blockNum</text></a>"
    }
    return new
}


fun markBadBlocksInDot(
    dot: DotDigraph,
    badBlocks: Set<NBId>,
    edges: Map<Edge, List<TACExpr>>,
    cexModel: CounterexampleModel,
) : DotDigraph {

    fun getValOfCondExp(cond : TACExpr) : Boolean? {
        return when (cond) {
            is TACExpr.Sym.Const -> {
                cond.s.value != BigInteger.ZERO
            }
            is TACExpr.Sym.Var -> {
                cexModel.valueAsBoolean(cond.s).leftOrNull() == true
            }
            is TACExpr.UnaryExp.LNot -> {
                getValOfCondExp(cond.o)?.let {
                    !it
                }
            }
            else -> {
                logger.warn { "Unexpected expression $cond" }
                true
            }
        }
    }

    fun getValOfCondExpList(conds : List<TACExpr>) : Boolean? {
        val firstCond = conds.firstOrNull()
        return if (firstCond == null) {
            true
        } else {
            getValOfCondExp(firstCond)
        }
    }

    return dot.copy(
        nodes = dot.nodes.map { n ->
           if (cexModel.tacAssignments.isNotEmpty() && n.originalNodeId in badBlocks) {
               n.copy(color=DotColor.brown)
           } else {
               n
           }
        },
        edges = dot.edges.map { e ->
            val cond = edges.entries.find { it.key.src==e.src.originalNodeId && it.key.trg==e.trg.originalNodeId }?.value
            val res = cond?.let {getValOfCondExpList(it)} ?: true
            if (cexModel.tacAssignments.isNotEmpty() && e.src.originalNodeId in badBlocks && e.trg.originalNodeId in badBlocks && res) {
                e.copy(color=DotColor.brown)
            } else {
                e
            }
        }
    )
}
