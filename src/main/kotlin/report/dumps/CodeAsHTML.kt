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
    fun generateHTML(codeMap : CodeMap) : String {

        val blocksHtml0 = codeMap.getBlocksHtml()
        val previousMapping = codeMap.getPreviousMapping()
        val callIds = codeMap.callIdNames.keys



        // mostly experimental printing, not willing to touch TACValue now
        fun uninterpretedFunToHTML(uf: TACValue.MappingValue.UninterpretedFunction): String {
            return uf.value.entries.joinToString("<br/>&nbsp;&nbsp;&nbsp;", prefix = "<br/>&nbsp;&nbsp;&nbsp;", postfix = "<br/>") { (kl, v) ->
                "${
                    kl.joinToString(",") {
                        if (it is TACValue.SKey) {
                            skeyValueToHTML(it)
                        } else {
                            it.toString()
                        }
                    }
                } -> ${
                    if (v is TACValue.SKey) {
                        skeyValueToHTML(v)
                    } else {
                        v.toString()
                    }
                }"
            }
        }

        val cex = codeMap.cexModel?.tacAssignments?.
            filterNot {TACMeta.LEINO_OK_VAR in it.key.meta
                || it.key.smtRep.startsWith("L")
                || it.key.smtRep.startsWith(TACKeyword.MEMORY.getName())
                || it.key.smtRep.matches(Regex("${TACKeyword.STORAGE.getName()}_[1-9][0-9]*"))
                || it.key.smtRep.matches(Regex("${TACKeyword.ORIGINAL_STORAGE.getName()}_[1-9][0-9]*"))
                || it.key.smtRep.startsWith("boundaryCalldata")
                || it.key.smtRep.startsWith("sizeCalldata")
                || it.key.smtRep.startsWith("tmpSizeCalldataLimitCheck")
                || it.key.smtRep.startsWith(CVLReservedVariables.certoraInput.name)
            }?.map {
            "${it.key.smtRep}=${
                it.value.let { v ->
                    if (v is TACValue.MappingValue.UninterpretedFunction) {
                        uninterpretedFunToHTML(v)
                    } else if (v is TACValue.SKey) {
                        skeyValueToHTML(v)
                    } else {
                        v
                    }
                }
            }"
        }?.joinToString("<br/>\n").orEmpty()

        // timeoutExplanation is `""` if we are not in the "timeout" case
        val timeoutExplanation = codeMap.colorExplanation?.let { timeoutExplanationBoxHtml(it, codeMap.name) }.orEmpty()

        // blocks must come after SVG for tooltip to be over the svg too
        val htmlString = """
<!DOCTYPE html>
<html>
<head>
    <title>${codeMap.name} - Code Map</title>
    <meta charset="utf-8"/>
    <style>
        .tooltip {
            position: relative;
            display: inline-block;
            border-bottom: 1px dotted black;
        }

        .tooltip .tooltiptext {
            visibility: hidden;
            width: 900px;
            background-color: red;
            color: #fff;
            text-align: center;
            border-radius: 6px;
            padding: 5px 0;

            /* Position the tooltip */
            position: fixed;
            z-index: 100000;
            top: 0;
            left: 25%;
            margin-left: -60px;
        }

        .deflink {
            color: inherit;
        }

        /* By default, tac commands within uc-only are hidden */
        .uc-enabled.cmds-list.uc-only > .tac-cmd {
           display: none;
        }

        /* but override the above (this is a more specific selector) and "unset" the `none` specified above
           end result: only `cmd-in-uc` commands within `uc-only` are displayed */
        .uc-enabled.cmds-list.uc-only > .tac-cmd.cmd-in-uc {
           display: unset;
        }

        /*
           Don't show "close-skipped-lines" warnings
         */
        .uc-enabled.cmds-list.uc-only > .close-skipped-lines-warning {
           display: none;
        }

        /*
           if we have .uc-only, hide all `br` nodes that immediately follow a command that is hidden (i.e., not cmd-in-uc)
        */
        .uc-enabled.cmds-list.uc-only > span.tac-cmd:not(.cmd-in-uc) + br {
           display:none;
        }

        /*
           by default, commands in close-to-uc are hidden
        */
        .uc-enabled.cmds-list.close-to-uc > .tac-cmd {
           display:none;
        }

        /*
          Carve out an exception to the above (this selector is more specific, and thus overrides.
          Commands with cmd-in-uc or cmd-close-to-uc have the display status unset, i.e., `inline` AKA visible
        */
        .uc-enabled.cmds-list.close-to-uc > :is(.cmd-in-uc,.cmd-close-to-uc).tac-cmd {
           display:unset;
        }

        /*
          Like in the uc-only case, hide BR commands that follow a command that is hidden, i.e., NOT cmd-in-uc or cmd-close-to-uc
        */
        .uc-enabled.cmds-list.close-to-uc > span.tac-cmd:not(:is(.cmd-in-uc,.cmd-close-to-uc)) + br {
           display: none;
        }

        /*
           Within close-to-uc, hide the "skipped-lines-warning", close-skipped-lines-lines will show instead
        */
        .uc-enabled.cmds-list.close-to-uc > .skipped-lines-warning {
           display:none;
        }

        /*
          Hide all warning with `.all`
        */
        .uc-enabled.cmds-list.all > :is(.close-skipped-lines-warning,.skipped-lines-warning) {
            display:none;
         }
         /*
           NB: we don't have a case for `uc-enables.cmds-list.all` because by default everything will use the default
           display attribute
         */

        .skipped-lines-warning, .close-skipped-lines-warning {
            background-color: #fffaca;
            display:block;
            padding: 10px 10px 10px 10px;
        }

        button.uc-close {
            background-color: rgb(255, 237, 78);
        }
    </style>
        <script type="text/javascript">
        function showCmd(cmdId) {
            document.getElementById(cmdId).style.display = "block"
        }

        function setDisplayClass(elem, className) {
           var wrapper = elem.closest(".cmds-list");
           wrapper.classList.remove("all", "uc-only", "close-to-uc");
           wrapper.classList.add(className);
        }

        function highlighActiveButton(elem, classNameToHighlight = 'uc-toggle-button', wrapper = '.uc-toggle-buttons') {
            var elems = elem.closest(wrapper).getElementsByClassName(classNameToHighlight);
            for (var i = 0; i < elems.length; ++i) {
                elems[i].style.backgroundColor = 'buttonface';
            }
            elem.style.backgroundColor = 'rgb(255, 237, 78)';
        }

        var showUcCmdsOnly = function() {
            highlighActiveButton(this)
            setDisplayClass(this, "uc-only")
        }

        var showCmdsCloseToUc = function() {
            highlighActiveButton(this)
            setDisplayClass(this, "close-to-uc")
        }


        var showAllCmds = function() {
            highlighActiveButton(this)
            setDisplayClass(this, "all");
        }
    </script>
    <script src="https://certora-web-resources.s3-us-west-2.amazonaws.com/svg-pan-zoom.min.js"></script>
    <script type="text/javascript">
        var highlightedAnchor = null;
        var lastNodeColor = "white";
        var lastStrokeWidth = "2px";
        var lastStrokeDashArray = "none";

        function highlightAnchor(anchorId) {
            var messagesDiv = document.getElementById("messages"); // must be not null
            messagesDiv.innerHTML = "";
            if (highlightedAnchor != null) {
                var lastBlock = document.getElementById("block"+highlightedAnchor);
                var lastEdgeS = document.getElementById("edgeS"+highlightedAnchor);
                var lastEdgeT = document.getElementById("edgeT"+highlightedAnchor);
                var lastEdgeP = document.getElementById("edgeP"+highlightedAnchor);
                var lastNode = document.getElementById(highlightedAnchor);
                if (lastBlock != null) {
                    lastBlock.style.backgroundColor="white";
                    if (lastEdgeS != null) lastEdgeS.style.backgroundColor="white";
                    if (lastEdgeT != null) lastEdgeT.style.backgroundColor="white";
                    if (lastEdgeP != null) lastEdgeP.style.backgroundColor="white";
                } else {
                    //messagesDiv.innerHTML += "block " + highlightedAnchor + " does not exist.<br/>";
                }
                if (lastNode != null) {
                    lastNode.children[1].setAttribute("fill", lastNodeColor);
                    lastNode.children[1].setAttribute("stroke-width", lastStrokeWidth);
                    lastNode.children[1].setAttribute("stroke-dasharray", lastStrokeWidth);
                }
            }
            highlightedAnchor = anchorId;
            var newBlock = document.getElementById("block"+highlightedAnchor);
            var newEdgeS = document.getElementById("edgeS"+highlightedAnchor);
            var newEdgeT = document.getElementById("edgeT"+highlightedAnchor);
            var newEdgeP = document.getElementById("edgeP"+highlightedAnchor);
            var newNode = document.getElementById(highlightedAnchor);
            if (newBlock != null) {
                newBlock.style.backgroundColor="yellow";
                if (newEdgeS != null) newEdgeS.style.backgroundColor="yellow";
                if (newEdgeT != null) newEdgeT.style.backgroundColor="yellow";
                if (newEdgeP != null) newEdgeP.style.backgroundColor="yellow";
            } else {
                messagesDiv.innerHTML += "block " + highlightedAnchor + " does not exist.<br/>";
            }
            if (newNode != null) {
                lastNodeColor = newNode.children[1].getAttribute("fill");
                newNode.children[1].setAttribute("fill", "yellow");
                lastStrokeWidth = newNode.children[1].getAttribute("stroke-width");
                newNode.children[1].setAttribute("stroke-width", "3px");
                lastStrokeWidth = newNode.children[1].getAttribute("stroke-dasharray");
                newNode.children[1].setAttribute("stroke-dasharray", "10,4");
            }
            if (newEdgeS != null) {
                newEdgeS.scrollIntoView();
                //location.href = "#"+newEdgeS.id;
            }
            if (newEdgeP != null) {
                newEdgeP.scrollIntoView();
            }
        }

        function toggleSize() {
            var divCex = document.getElementById("cex");
            if (divCex != null) {
                if (divCex.style.width == "250px") {
                    divCex.style.width = "900px";
                } else {
                    divCex.style.width = "250px";
                }
            }
        }

        var lastSvgEventListener = null;

        var currentSVG = "0";
        function toggleSVG(svgId) {
            var _svgcurrent = document.getElementById("svgOf"+currentSVG);
            var _svgnew = document.getElementById("svgOf"+svgId);
            _svgcurrent.style.visibility = "hidden";
            _svgnew.style.visibility = "visible";

            var _blockscurrent = document.getElementById("blocksAndEdges"+currentSVG);
            var _blocksnew = document.getElementById("blocksAndEdges"+svgId);
            _blockscurrent.style.visibility = "hidden";
            _blocksnew.style.visibility = "visible";

            var _magCurrent = document.getElementById("mag_" + currentSVG);
            var _magNew = document.getElementById("mag_" + svgId);
            _magCurrent.style.visibility = "hidden";
            _magNew.style.visibility = "visible";

            if (currentSVG != "0")
                var _pannedsvgcurrent = document.getElementById("theSVG"+currentSVG)
            else
                var _pannedsvgcurrent = document.getElementById("theSVG");
            if (svgId != "0")
                var svgsuffix = svgId
            else
                var svgsuffix = "";
            var _pannedsvgnew = document.getElementById("theSVG"+svgsuffix);

            if (_pannedsvgcurrent != null) {
                svgPanZoom(_pannedsvgcurrent).destroy();
            }

            if (_pannedsvgnew != null) {
                lastSvgEventListener = svgPanZoom('#theSVG'+svgsuffix, {
                                zoomEnabled: true,
                                controlIconsEnabled: true,
                                fit: true,
                                center: true,
                                // viewportSelector: document.getElementById('theSVG').querySelector('#g4') // this option will make library to misbehave. Viewport should have no transform attribute
                            });
            }

            currentSVG = svgId;
        }

        var highlightedDef = null;
        var highlightedUseCls = null;
        var lastNodeColor = "white";
        function highlightDef(def) {
            var messagesDiv = document.getElementById("messages"); // must be not null
            messagesDiv.innerHTML = "";
            if (highlightedDef != null) {
                var lastDef = document.getElementById(highlightedDef);
                if (lastDef != null) {
                    lastDef.style.backgroundColor = "white";
                }
                var useElements = document.getElementsByClassName(highlightedUseCls);
                for (var i = 0 ; i < useElements.length; i++) {
                    useElements[i].style.backgroundColor = "white";
                }
            }
            highlightedDef = "def_" + def;
            highlightedUseCls = "use_" + def;
            var newDef = document.getElementById(highlightedDef);
            if (newDef != null) {
                newDef.style.backgroundColor = "yellow";
                var useElements = document.getElementsByClassName(highlightedUseCls);
                for (var i = 0 ; i < useElements.length; i++) {
                    useElements[i].style.backgroundColor = "yellow";
                }
            } else {
                messagesDiv.innerHTML += "def " + def + " does not exist in this method.<br/>"
            }
        }

        var highlightedIntFun = null;
        function toggleInternalFun(id) {
            if (highlightedIntFun != null) {
                var lastStart = document.getElementById("intfunStart_"+highlightedIntFun);
                var lastEnd = document.getElementById("intfunEnd_"+highlightedIntFun);
                if (lastStart != null) {
                    lastStart.style.backgroundColor="white";
                }
                if (lastEnd != null) {
                    lastEnd.style.backgroundColor="white";
                }
            }

            highlightedIntFun = id;
            var newStart = document.getElementById("intfunStart_"+highlightedIntFun);
            var newEnd = document.getElementById("intfunEnd_"+highlightedIntFun);
            if (newStart != null) {
                newStart.style.backgroundColor="#ffccff"
            }
            if (newEnd != null) {
                newEnd.style.backgroundColor="#ffccff"
            }
        }
    </script>
    <style>
        html {
            height:100%;
            margin:0;
            padding:0;
        }
        body {
            font-family: monospace;
            height:100%;
            overflow: hidden;
            margin:0;
            padding:0;
        }
        svg { width: 100%; height: auto; }
    </style>
</head>
<body>

    <div style="position:fixed;width:250px;top:0;right:0;color:black;background-color:#e6e6e6;z-index:100;overflow-y: auto;max-height:400px;" id="cex" ondblclick="toggleSize()">
        <div style="display:inline-block;z-index:100;">
        ${if (cex.isNotEmpty()) { "Full variable assignments<br/>" } else { "" }}
        ${if (timeoutExplanation.isNotEmpty()) { "Meanings of node colors:<br/>" } else { "" }}
        <b>Double click to show/hide</b>
        <br/><br/>
        $cex$timeoutExplanation
        </div>
    </div>

    <div style="position:fixed;width:300px;top:420px;right:0;color:blue;background-color:#e6e6e6;z-index:100;overflow-y:auto;max-height:200px;" id="tabs">
        <div style="display:inline-block;z-index:100;">
            Method calls quick lookup:<br/>
            ${methodIndexAsHtml(codeMap)}
        </div>
    </div>

    <div style="position:fixed;bottom:0;left:0;color:white;background-color:red;" id="messages"></div>

    <!-- SVG -->
    ${generateSVGWithId(codeMap.dotMain,MAIN_GRAPH_ID)}
    ${codeMap.subDots.map { generateSVGWithId(it.value,it.key) }.joinToString("\n")}

    <!-- BLOCKS -->
    <div id="blocksAndEdges0" style="position:fixed;height:100%;width:40%;display:inline-block;border:1px">
        $blocksHtml0
    </div>
    ${callIds.filter { it != 0 }.map { id ->
"""
<div id="blocksAndEdges${id}" style="position:fixed;height:100%;width:40%;display:inline-block;border:1px;visibility:hidden;">
${codeMap.blocksToHtml(codeMap.subAsts[id] ?:
CoreTACProgram.empty(""), id.toString())}
</div>
"""
}.joinToString("\n")
}
    <div style="position:fixed;left:40%;top:90%;width:60%;overflow:scroll;height:10%;">
    Predecessor map:<br/>
    $previousMapping
    </div>

    <script>
    // Load tooltips callbacks
    var tooltip_cache = ${codeMap.getToolTipCacheForJS()};

    var tooltips_spans = document.getElementsByClassName('tooltip');
    for (var i=0; i<tooltips_spans.length; ++i) {
        var me = tooltips_spans[i];
        tooltips_spans[i].addEventListener("mouseover", function() {
                for (var j=0; j < this.children.length ; ++j) {
                if (this.children[j].className=="tooltiptext") {
                    var attribs = this.children[j].attributes;
                    if (attribs["tooltipatt"] != null) {
                        var id = attribs["tooltipatt"].value;
                        this.children[j].innerHTML = tooltip_cache[id];
                        this.children[j].style.visibility = "visible";
                    }
                }
            }
        }.bind(me));
        tooltips_spans[i].addEventListener("mouseout", function() {
            for (var j=0; j < this.children.length ; ++j) {
                if (this.children[j].className=="tooltiptext") {
                    this.children[j].innerHTML = "";
                    this.children[j].style.visibility="hidden";
                }
            }
        }.bind(me));
    }


    lastSvgEventListener = svgPanZoom('#theSVG', {
                            zoomEnabled: true,
                            controlIconsEnabled: true,
                            fit: true,
                            center: true,
                            // viewportSelector: document.getElementById('theSVG').querySelector('#g4') // this option will make library to misbehave. Viewport should have no transform attribute
                        });
        ${if (cex.isBlank() && codeMap.colorExplanation == null) "document.getElementById(\"cex\").style.display = \"none\";"
else ""
}
    </script>
</body>
</html>
"""
        return htmlString
    }


    private fun methodIndexAsHtml(codeMap: CodeMap): String {
        val callIdNames = codeMap.callIdNames

        fun getBackgroundStyle(callId: CallId): String {
            val defaultColor = "lightgray"
            return when {
                codeMap.unsatCoreDomain.isNotEmpty() ->
                    if (codeMap.touchesUnsatCore(callId)) {
                        "background-color: ${CodeMap.inUnsatCoreColor}"
                    } else {
                        "background-color: ${CodeMap.notInUnsatCoreColor}"
                    }
                codeMap.countDifficultOps != null || codeMap.timeoutCore.isNotEmpty() ->
                    "background-image:linear-gradient(to right, " +
                        "${codeMap.callNodeTimeoutColor(callId, codeMap.countDifficultOps).asCommaSeparatedColorPair})"
                else ->
                    "background-color: $defaultColor}"
            }
        }

        fun methodIndexEntry(callId: CallId, depth: Int) =
            "<a style=\"${getBackgroundStyle(callId)};\" href=\"#blank${callId}\" " +
                "onclick=\"toggleSVG('${callId}')\">" +
                "${callId}: ${rarrow.repeat(depth)} ${callIdNames[callId]}" +
                "<span id=\"mag_${callId}\" style=\"visibility:${if (callId==0) {"visible"} else {"hidden"}};\">&nbsp;\uD83D\uDD0D</span>" +
            "</a>"

        val topoOrderGraph = topologicalOrderOrNull(codeMap.withInternalFunctions.blockgraph)?.reversed() // reversing as sinks are first
            ?: // not expected, but this code shouldn't fail!
            return  callIdNames.keys.sorted().joinToString("<br/>\n") { methodIndexEntry(it, 0) }

        // This list holds also the nbids corresponding to blocks a callee returns to. e.g. if we have `callId1` calling
        // `callId2`, then in the graph we had `blockWithCallId1`, `blockWithCallId2`, ..., `blockWithCallId1`. The last
        // block is the return block from callId2. So this list keeps this information.
        val orderedCallIds = mutableListOf<CallId>()
        topoOrderGraph.map { it.calleeIdx }.forEach { callId ->
            if (orderedCallIds.isEmpty()) {
                orderedCallIds.add(callId)
            } else if (orderedCallIds.last() != callId) {
                orderedCallIds.add(callId)
            } else {
                // just another block in the same call, skip it.
            }
        }
        val orderedCallIdWithDepth = mutableListOf<Pair<CallId, Int>>()
        var depth = 0
        val seen = mutableSetOf<CallId>()
        orderedCallIds.forEach { n ->
            if (seen.add(n)) {
                orderedCallIdWithDepth.add(n to depth)
                if (depth < 0) {
                    logger.warn { "ordered call ids are not well-nested; " +
                        "orderedCallIds: \"$orderedCallIds\" " +
                        "first nodes with negative depth: \"$n\"; " +
                        "assuming depth 0 for everything " }
                    return  callIdNames.keys.sorted().joinToString("<br/>\n") { methodIndexEntry(it, 0) }
                }
                depth++
            } else {
                // n represents a return to a caller, so decrement depth
                depth--
            }
        }
        return orderedCallIdWithDepth.joinToString("<br/>\n") { (callId, depth) ->
            methodIndexEntry(callId, depth)
        }
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
     *  contains explanations for the timeout-diagnosis variant of the graph
     *   - an explanation of the used colors
     *   - some (few) possibly pertinent statistics, for now: number of paths
     *  we don't expect this to be present (i.e. non-empty) at the same time as the counterexample (`val cex`)
     */
    private fun timeoutExplanationBoxHtml(
        colorExplanation: Map<DotColorList, String>,
        name: String,
    ) =
        (colorExplanationToHtmlList(colorExplanation) +
            "<br>\nDifficulty Summary:\n<br>" +
            difficultyStatsAsText(name)
            ).joinToString("\n<br/>\n")


    private fun colorExplanationToHtmlList(colorExplanation: Map<DotColorList, String>) =
        colorExplanation.entries.mapIndexed { i, (color, explanation) ->
            val firstGradientColor = color.colors.getOrNull(0) ?: CodeMap.errorColor
            val secondGradientColor = color.colors.getOrNull(1) ?: firstGradientColor
"""
<svg height="30" width="40" style="height:35px">
    <defs>
    <linearGradient id="grad$i" x1="0%" y1="0%" x2="100%" y2="0%">
        <stop offset="0%"
        style="stop-color:${firstGradientColor.name};stop-opacity:1" />
        <stop offset="100%"
        style="stop-color:${secondGradientColor.name};stop-opacity:1" />
    </linearGradient>
    </defs>
    <ellipse cx="20" cy="15" rx="20" ry="15" fill="url(#grad$i)" />
Sorry, your browser does not support inline SVG.
</svg> :
$explanation
<br/>
"""
        }
}

fun skeyValueToHTML(skey: TACValue.SKey): String {
    return when (skey) {
        is TACValue.SKey.Basic -> "Basic(${skey.offset})"
        is TACValue.SKey.Node -> "Node(${skey.children.joinToString{skeyValueToHTML(it)}}, ${skey.offset})"
    }
}

private fun difficultyStatsAsText(mName: String): String {
    val sep = "<br/>\n"
    val (ruleLevelStats, _) = allStatsReferringToRule(mName)
    val generalSummary = ruleLevelStats
        .groupBy {
            it::class.java
        }
        .values
        .joinToString(sep) { ppStatsList ->
            ppStatsList.emptyOrSingleOrPickFirst()?.asText.toString()
        }


    val detailedPathStats = ruleLevelStats
        .find { it is PathCountStats }
        ?.let { (it as PathCountStats).asLongText() }

    val detailedNonlinearStats = ruleLevelStats
        .find { it is NonLinearStats }
        ?.let { (it as NonLinearStats).asLongText() }

    return generalSummary +
        detailedPathStats.let { sep.repeat(2) + it } +
        detailedNonlinearStats.let { sep.repeat(2) + it }
}

/**
 * Returns a pair of lists containing respectively the rule level stats and split level stats related to [ruleName].
 * If there are no statistics related to [ruleName], returns two empty lists.
 */
private fun allStatsReferringToRule(ruleName: String) =
    (SDCollectorFactory.collector().read(
        FullyQualifiedSDFeatureKey(
            listOf(
                DifficultyStatsCollector.key.toSDFeatureKey(),
            ),
            GeneralKeyValueStats::class.java.name
        )
    ) as? GeneralKeyValueStats<*>)
        ?.extractedData?.find { sdElement ->
            (sdElement.statsData as? PerRuleDiffStatsJavaSerializerWrapper)?.toSerialize?.name?.baseName == ruleName
        }
        ?.let { sdElement ->
            (sdElement.statsData as PerRuleDiffStatsJavaSerializerWrapper).toSerialize.let {
                it.ruleLevelStats.flatMap { rule -> rule.stats } to it.splitLevelStats.flatMap { split -> split.stats }
            }
        } ?: (listOf<PrettyPrintableStats>() to listOf())

/**
 * This function removes any HTML elements from [this] (apart from formatting HTML tags) and should
 * be used to sanitize any [String] that is passed directly from user input to the TAC dump.
 */
fun String.sanitize(): String {
    val policy: PolicyFactory = Sanitizers.FORMATTING
    return policy.sanitize(this)
}
