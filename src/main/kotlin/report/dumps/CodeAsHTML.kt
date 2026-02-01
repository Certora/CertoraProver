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

import algorithms.topologicalOrderOrNull
import config.Config
import datastructures.stdcollections.*
import log.*
import org.owasp.html.PolicyFactory
import org.owasp.html.Sanitizers
import spec.CVLReservedVariables
import statistics.FullyQualifiedSDFeatureKey
import statistics.GeneralKeyValueStats
import statistics.SDCollectorFactory
import statistics.data.*
import statistics.toSDFeatureKey
import tac.CallId
import utils.ArtifactFileUtils
import vc.data.CoreTACProgram
import vc.data.TACKeyword
import vc.data.TACMeta
import vc.data.state.TACValue
import java.io.File


private val logger = Logger(LoggerTypes.UI)

/**
 * Generates an HTML string from a [CodeMap] object.
 */
object DumpGraphHTML {

    // ============================================================================
    // Resource Loading
    // ============================================================================

    private fun loadResource(path: String, fallback: String = ""): String {
        val content = DumpGraphHTML::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()

        if (content == null) {
            logger.warn { "Resource not found: $path - TAC dump visualization may be incomplete" }
            return fallback
        }
        return content
    }

    private val styles: String by utils.lazy {
        loadResource("css/tac-dump.css", "/* CSS resource not found */")
    }
    private val tacDumpJs: String by utils.lazy {
        loadResource("js/tac-dump.js", "// JS resource not found\nfunction initTacDump() {}")
    }
    private val svgPanZoomJs: String by utils.lazy {
        loadResource("js/svg-pan-zoom.min.js", "// svg-pan-zoom not found\nfunction svgPanZoom() { return { destroy: function() {} }; }")
    }

    // ============================================================================
    // JavaScript - Data Injection (generates dynamic data for tac-dump.js)
    // ============================================================================

    private fun generateDataInjectionScript(codeMap: CodeMap, hasCex: Boolean, hasTimeoutExplanation: Boolean): String = buildString {
        // Inject tooltip cache data
        append("var tooltip_cache = ")
        append(codeMap.getToolTipCacheForJS())
        append(";\n")

        // Inject dataflow map data
        append("var dataflowMap = ")
        append(generateDataflowMapJS(codeMap))
        append(";\n")

        // Inject counterexample values map
        append("var cexValues = ")
        append(generateCexValuesJS(codeMap))
        append(";\n")

        // Hide CEX panel if empty
        if (!hasCex && !hasTimeoutExplanation) {
            append("document.getElementById(\"cex\").style.display = \"none\";\n")
        }

        // Initialize the visualization
        append("initTacDump();\n")
    }

    /**
     * Escapes a string for safe inclusion in a JavaScript string literal.
     */
    private fun String.escapeForJS(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</", "<\\/")  // Prevent script tag injection

    /**
     * Generates the dataflowMap JavaScript object content.
     * All variable names are escaped to prevent XSS.
     */
    private fun generateDataflowMapJS(codeMap: CodeMap): String = buildString {
        append("{\n")
        val unifiedMap = computeUnifiedDataflowMap(codeMap.subAsts)
        unifiedMap.forEachEntry { (varName, info) ->
            val escapedVarName = varName.escapeForJS()
            val inputs = info.inputs.joinToString(",") { "\"${it.escapeForJS()}\"" }
            val outputs = info.outputs.joinToString(",") { "\"${it.escapeForJS()}\"" }
            append("  \"$escapedVarName\": { inputs: [$inputs], outputs: [$outputs] },\n")
        }
        append("}")
    }

    /**
     * Generates the cexValues JavaScript object mapping variable names to their counter-example values.
     */
    private fun generateCexValuesJS(codeMap: CodeMap): String = buildString {
        append("{\n")
        codeMap.cexModel?.tacAssignments?.forEachEntry { (tacSymbol, tacValue) ->
            val smtRep = tacSymbol.smtRep
            if (TACMeta.LEINO_OK_VAR !in tacSymbol.meta &&
                !smtRep.startsWith("L") &&
                !smtRep.startsWith(TACKeyword.MEMORY.getName()) &&
                !smtRep.matches(Regex("${TACKeyword.STORAGE.getName()}_[1-9][0-9]*")) &&
                !smtRep.matches(Regex("${TACKeyword.ORIGINAL_STORAGE.getName()}_[1-9][0-9]*")) &&
                !smtRep.startsWith("boundaryCalldata") &&
                !smtRep.startsWith("sizeCalldata") &&
                !smtRep.startsWith("tmpSizeCalldataLimitCheck") &&
                !smtRep.startsWith(CVLReservedVariables.certoraInput.name)
            ) {
                val varName = smtRep.escapeForJS()
                val valueStr = tacValue.toString().escapeForJS()
                append("  \"$varName\": \"$valueStr\",\n")
            }
        }
        append("}")
    }

    fun generateHTML(codeMap: CodeMap): String {
        val blocksHtml0 = codeMap.getBlocksHtml()
        val previousMapping = codeMap.getPreviousMapping()
        val callIds = codeMap.callIdNames.keys

        // Helper for formatting uninterpreted functions
        fun uninterpretedFunToHTML(uf: TACValue.MappingValue.UninterpretedFunction): String {
            return uf.value.entries.joinToString(
                "<br/>&nbsp;&nbsp;&nbsp;",
                prefix = "<br/>&nbsp;&nbsp;&nbsp;",
                postfix = "<br/>"
            ) { (kl, v) ->
                "${kl.joinToString(",") { if (it is TACValue.SKey) skeyValueToHTML(it) else it.toString() }} -> " +
                    if (v is TACValue.SKey) skeyValueToHTML(v) else v.toString()
            }
        }

        // Check if we have counterexample values
        val hasCex = codeMap.cexModel?.tacAssignments?.isNotEmpty() == true

        // Timeout explanation (empty if not in timeout case)
        val timeoutExplanation = codeMap.colorExplanation?.let { timeoutExplanationBoxHtml(it, codeMap.name) }.orEmpty()

        // Build HTML using DSL
        return html {
            head {
                title { +codeMap.name; +" - Code Map" }
                meta("charset" to "utf-8")
                style { raw(styles) }
                script { raw(svgPanZoomJs) }
                script { raw(tacDumpJs) }
            }
            body {
                // CEX (counterexample) panel
                div("id" to "cex", "class" to "fixed-panel", "ondblclick" to "toggleSize()") {
                    div {
                        if (hasCex) {
                            +"Full variable assignments"
                            br()
                        }
                        if (timeoutExplanation.isNotEmpty()) {
                            +"Meanings of node colors:"
                            br()
                        }
                        b { +"Double click to show/hide" }
                        br()
                        br()
                        div("id" to "cex-values")
                        raw(timeoutExplanation)
                    }
                }

                // Method calls quick lookup panel
                div("id" to "tabs", "class" to "fixed-panel") {
                    div {
                        +"Method calls quick lookup:"
                        br()
                        raw(methodIndexAsHtml(codeMap))
                    }
                }

                // Messages div (for errors/warnings)
                div("id" to "messages", "class" to "fixed-panel")

                // SVG graphs
                raw(generateSVGWithId(codeMap.dotMain, MAIN_GRAPH_ID))
                codeMap.subDots.forEachEntry { (callId, dotDigraph) ->
                    raw(generateSVGWithId(dotDigraph, callId))
                }

                // Blocks - main
                div("id" to "blocksAndEdges0", "class" to "blocks-container") {
                    raw(blocksHtml0)
                }

                // Blocks - sub-graphs (hidden by default)
                callIds.filter { it != 0 }.forEach { id ->
                    div(
                        "id" to "blocksAndEdges$id",
                        "class" to "blocks-container",
                        "style" to "visibility:hidden;"
                    ) {
                        raw(codeMap.blocksToHtml(codeMap.subAsts[id] ?: CoreTACProgram.empty(""), id.toString()))
                    }
                }

                // Bottom bar with dataflow, successor map, and predecessor map
                div("id" to "bottom-bar-resizer")
                div("id" to "bottom-bar") {
                    // Dataflow section (graph-based visualization)
                    div("id" to "dataflow-section") {
                        div("class" to "section-header") {
                            span("class" to "section-title") { +"Dataflow" }
                            button("id" to "dataflow-clear-btn", "onclick" to "clearDataflowGraph()") { +"Clear" }
                        }
                        div("id" to "dataflow-graph-container") {
                            // SVG with pre-defined arrow markers (avoids recreating them on each edge draw)
                            raw("""<svg id="dataflow-edges">
  <defs>
    <marker id="dataflow-arrow-input" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--dataflow-input-color, #0066cc)"/>
    </marker>
    <marker id="dataflow-arrow-output" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--dataflow-output-color, #00cc66)"/>
    </marker>
    <marker id="dataflow-arrow-neutral" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#888"/>
    </marker>
  </defs>
</svg>""")
                            div("id" to "dataflow-nodes")
                            div("id" to "dataflow-hint") {
                                +"Click a variable to see dataflow"
                            }
                        }
                    }

                    // Successor map section
                    div("id" to "successor-section") {
                        div("class" to "section-title") { +"Successor Map" }
                        // Main successor map (visible by default)
                        div("id" to "successorMap0") {
                            raw(codeMap.getSuccessorMapHtml())
                        }
                        // Sub-graph successor maps (hidden by default)
                        callIds.filter { it != 0 }.forEach { id ->
                            div("id" to "successorMap$id", "style" to "display:none;") {
                                raw(codeMap.getSuccessorMapHtml(id.toString()))
                            }
                        }
                    }

                    // Predecessor map section
                    div("id" to "predecessor-section") {
                        div("class" to "section-title") { +"Predecessor Map" }
                        raw(previousMapping)
                    }
                }

                // Data injection and initialization script
                script {
                    raw(generateDataInjectionScript(codeMap, hasCex, timeoutExplanation.isNotEmpty()))
                }
            }
        }
    }


    private fun methodIndexAsHtml(codeMap: CodeMap): String {
        val callIdNames = codeMap.callIdNames

        fun getBackgroundStyle(callId: CallId): String = when {
            codeMap.unsatCoreDomain.isNotEmpty() -> {
                val color = if (codeMap.touchesUnsatCore(callId)) CodeMap.inUnsatCoreColor else CodeMap.notInUnsatCoreColor
                "background-color: $color"
            }
            codeMap.countDifficultOps != null || codeMap.timeoutCore.isNotEmpty() ->
                "background-image:linear-gradient(to right, ${codeMap.callNodeTimeoutColor(callId, codeMap.countDifficultOps).asCommaSeparatedColorPair})"
            else -> "background-color: lightgray"
        }

        fun methodIndexEntry(callId: CallId, depth: Int): String {
            val visibility = if (callId == 0) "visible" else "hidden"
            val indent = rarrow.repeat(depth)
            return """<a style="${getBackgroundStyle(callId)};" href="#" onclick="toggleSVG('$callId'); event.preventDefault();">$callId: $indent ${callIdNames[callId]}<span id="mag_$callId" style="visibility:$visibility;">&nbsp;&#x1F50D;</span></a>"""
        }

        fun fallbackToSortedList() = callIdNames.keys.sorted().joinToString("<br/>\n") { methodIndexEntry(it, 0) }

        // Reversing topological order as sinks come first
        val topoOrderGraph = topologicalOrderOrNull(codeMap.withInternalFunctions.blockgraph)?.reversed()
            ?: return fallbackToSortedList()

        // Build ordered list of call IDs (deduplicated consecutive entries)
        val orderedCallIds = topoOrderGraph
            .map { it.calleeIdx }
            .fold(mutableListOf<CallId>()) { acc, callId ->
                if (acc.isEmpty() || acc.last() != callId) acc.add(callId)
                acc
            }

        // Calculate depth for each call ID based on nesting
        val seen = mutableSetOf<CallId>()
        var depth = 0
        val orderedCallIdWithDepth = mutableListOf<Pair<CallId, Int>>()

        for (callId in orderedCallIds) {
            if (seen.add(callId)) {
                orderedCallIdWithDepth.add(callId to depth)
                if (depth < 0) {
                    logger.warn {
                        "ordered call ids are not well-nested; " +
                            "orderedCallIds: \"$orderedCallIds\" " +
                            "first nodes with negative depth: \"$callId\"; " +
                            "assuming depth 0 for everything"
                    }
                    return fallbackToSortedList()
                }
                depth++
            } else {
                depth--
            }
        }

        return orderedCallIdWithDepth.joinToString("<br/>\n") { (callId, d) -> methodIndexEntry(callId, d) }
    }

    /* TODO: Add classes to save space
        can load svgs dynamically, see e.g. view-source:https://ariutta.github.io/svg-pan-zoom/demo/dynamic-load.html
    */

    /** [name] is the name of the file to be dumped (including `.html` suffix) */
    fun writeCodeToHTML(codeMap : CodeMap, name : String) {
        if (ArtifactManagerFactory.isEnabled() && Config.isEnabledReport(name)) {
            val nameWithExt = if (File(name).extension != "html") {
                logger.info { "dumping codeMap \"${codeMap.name}\" in html format, but the file name \"$name\" is lacking" +
                    " the `.html` suffix. Appending it and dumping that file." }
                "$name.html"
            } else {
                name
            }
            // make sure reports path exists - this is done in getReportsOutputPath
            val dirString = ArtifactManagerFactory().mainReportsDir
            val truncName = ArtifactManagerFactory().fitFileLength(nameWithExt, ".html") // should already be in bounds, but to be sure ..
            val filename = "${dirString}${File.separator}$truncName"
            val htmlString = generateHTML(codeMap)
            val writer = ArtifactFileUtils.getWriterForFile(filename)
            writer.use {
                it.write(htmlString)
            }
        }
    }

    /**
     * Contains explanations for the timeout-diagnosis variant of the graph:
     * - an explanation of the used colors
     * - some possibly pertinent statistics (e.g., number of paths)
     * Note: This is not expected to be present at the same time as the counterexample.
     */
    private fun timeoutExplanationBoxHtml(colorExplanation: Map<DotColorList, String>, name: String): String =
        buildList {
            addAll(colorExplanationToHtmlList(colorExplanation))
            add("<br>\nDifficulty Summary:\n<br>")
            add(difficultyStatsAsText(name))
        }.joinToString("\n<br/>\n")

    private fun colorExplanationToHtmlList(colorExplanation: Map<DotColorList, String>): List<String> =
        colorExplanation.entries.mapIndexed { index, (color, explanation) ->
            val firstColor = color.colors.getOrNull(0) ?: CodeMap.errorColor
            val secondColor = color.colors.getOrNull(1) ?: firstColor
            """<svg height="30" width="40" style="height:35px"><defs><linearGradient id="grad$index" x1="0%" y1="0%" x2="100%" y2="0%"><stop offset="0%" style="stop-color:${firstColor.name};stop-opacity:1"/><stop offset="100%" style="stop-color:${secondColor.name};stop-opacity:1"/></linearGradient></defs><ellipse cx="20" cy="15" rx="20" ry="15" fill="url(#grad$index)"/>Sorry, your browser does not support inline SVG.</svg> : $explanation<br/>"""
        }
}

fun skeyValueToHTML(skey: TACValue.SKey): String = when (skey) {
    is TACValue.SKey.Basic -> "Basic(${skey.offset})"
    is TACValue.SKey.Node -> "Node(${skey.children.joinToString { skeyValueToHTML(it) }}, ${skey.offset})"
}

private const val HTML_LINE_BREAK = "<br/>"

private fun difficultyStatsAsText(ruleName: String): String {
    val (ruleLevelStats, _) = allStatsReferringToRule(ruleName)

    val generalSummary = ruleLevelStats
        .groupBy { it::class.java }
        .values
        .joinToString(HTML_LINE_BREAK) { statsList ->
            statsList.emptyOrSingleOrPickFirst()?.asText.toString()
        }

    val detailedStats = listOfNotNull(
        ruleLevelStats.filterIsInstance<PathCountStats>().firstOrNull()?.asLongText(),
        ruleLevelStats.filterIsInstance<NonLinearStats>().firstOrNull()?.asLongText()
    ).joinToString(HTML_LINE_BREAK.repeat(2))

    return if (detailedStats.isNotEmpty()) {
        "$generalSummary${HTML_LINE_BREAK.repeat(2)}$detailedStats"
    } else {
        generalSummary
    }
}

/**
 * Returns a pair of lists containing respectively the rule level stats and split level stats related to [ruleName].
 * If there are no statistics related to [ruleName], returns two empty lists.
 */
private fun allStatsReferringToRule(ruleName: String): Pair<List<PrettyPrintableStats>, List<PrettyPrintableStats>> {
    val stats = SDCollectorFactory.collector().read(
        FullyQualifiedSDFeatureKey(
            listOf(DifficultyStatsCollector.key.toSDFeatureKey()),
            GeneralKeyValueStats::class.java.name
        )
    ) as? GeneralKeyValueStats<*> ?: return emptyList<PrettyPrintableStats>() to emptyList()

    val ruleStats = stats.extractedData
        .find { (it.statsData as? PerRuleDiffStatsJavaSerializerWrapper)?.toSerialize?.name?.baseName == ruleName }
        ?.let { (it.statsData as PerRuleDiffStatsJavaSerializerWrapper).toSerialize }
        ?: return emptyList<PrettyPrintableStats>() to emptyList()

    return ruleStats.ruleLevelStats.flatMap { it.stats } to ruleStats.splitLevelStats.flatMap { it.stats }
}

/**
 * This function removes any HTML elements from [this] (apart from formatting HTML tags) and should
 * be used to sanitize any [String] that is passed directly from user input to the TAC dump.
 */
fun String.sanitize(): String {
    val policy: PolicyFactory = Sanitizers.FORMATTING
    return policy.sanitize(this)
}

/**
 * Escapes HTML special characters to prevent XSS and ensure proper rendering.
 * Uses Apache Commons Lang3 for well-tested, comprehensive HTML escaping.
 */
fun String.escapeHTML(): String {
    @Suppress("DEPRECATION")
    return org.apache.commons.lang3.StringEscapeUtils.escapeHtml4(this)
}
