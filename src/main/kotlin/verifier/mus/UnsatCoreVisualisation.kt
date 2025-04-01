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

import config.Config
import datastructures.MultiMap
import datastructures.MutableMultiMap
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import log.*
import report.dumps.sanitize
import spec.rules.IRule
import utils.ArtifactFileUtils
import utils.add
import utils.mapToSet
import java.io.File

class UnsatCoreVisualisation(
    val stats: Map<IRule, UnsatCoresStats>
) {

    /**
     * Represents a particular [line] in a [file] (.spec or .sol in our usecase).
     */
    data class Line(
        val file: String,
        val line: Int
    )

    private val perFileRed = mutableMultiMapOf<String, Int>()
    private val perFileGreen = mutableMultiMapOf<String, Int>()
    private val perFileYellow = mutableMultiMapOf<String, Int>()

    private val fileIds = mutableMapOf<String, Int>()
    private fun idOfFile(filename: String) = fileIds.getOrPut(filename) { fileIds.size }

    /**
     * The main function to build the .html visualisation. The visualisation consists of two panes:
     * - one shows line coverage/highlight on individual .sol and .spec files, and
     * - the other shows detailed break down per individual lines: showing which commands from which rules (do not) touch
     * particular lines.
     * The two panes are connected via hyperlinks, i.e., clicking on a line in one pane leads the user to the corresponding
     * part in the other pane. Also, we highligh the solidity syntax.
     *
     * The syntax highlighting and the line coloring and linking is done via javascript; see [codeHighlighScript].
     * [visualiseCoverage] just prepares the underlying html elements with necessary class/id identifiers.
     */
    fun visualiseCoverage() {
        /**
         * One line in .spec/.sol can be "covered" by multiple TAC commands, especially if the line is a complicated expression.
         * [linesWithTouching] contains lines for which there exist at least a single rule and a single TAC command from that rule
         * that is presented in the unsat core of that rule.
         *
         * [linesWithNotTouching] contains lines for which there exist at least a single rule and a single TAC command from
         * that rule that is *not* presented in the unsat core of that rule.
         *
         * NOTE: the unsat core analysis works only with "assume", "assert" and "assign" commands. For "assumes" and "asserts",
         * the fact that they are in the unsat core simply means that they are needed to prove the rule.
         * For an assign command, if it is not in the unsat core, it means that the value of the lhs does not matter
         * for proving the rule (though we might still need the lhs variable due to control flow).
         */
        val linesWithTouching = mutableSetOf<Line>()
        val linesWithNotTouching = mutableSetOf<Line>()

        /**
         * For each line, holds a set of (un)used commands on that line (i.e. (out)in the unsat cores). Furthermore,
         * the cmds are grouped by the particular rules.
         */
        val usedCmdsPerLine = mutableMapOf<Line, MutableMultiMap<IRule, UnsatCoreCmd>>()
        val unusedCmdsPerLine = mutableMapOf<Line, MutableMultiMap<IRule, UnsatCoreCmd>>()

        stats.forEachEntry { (rule, ruleStats) ->
            ruleStats.usedCmds.forEach { cmd ->
                val line = Line(file = cmd.file, line = cmd.line)
                linesWithTouching.add(line)
                usedCmdsPerLine.add(line, rule, cmd)
            }
            ruleStats.unusedCmds.forEach { cmd ->
                val line = Line(file = cmd.file, line = cmd.line)
                linesWithNotTouching.add(line)
                unusedCmdsPerLine.add(line, rule, cmd)
            }
        }

        /**
         * Lines that contain a TAC command that is presented in an unsat core and a TAC command that is not presented in
         * an unsat core. These could be either different commands or even the same commands (needed and not needed by
         * two different rules in this case).
         */
        val linesPartiallyTouching = linesWithTouching.intersect(linesWithNotTouching)

        /**
         * [perFileRed[file]] contains the set of lines of [file] that do not "touch" any unsat core. That is, these lines
         * are not needed by any relevant rule. (provided that we have mapping for all TAC commands on those lines, which
         * is often not the case (especially in the case of .sol lines)).
         */
        linesWithNotTouching.filter { it !in linesWithTouching }.groupBy { it.file }
            .mapValuesTo(perFileRed) { entry -> entry.value.mapToSet { it.line } }

        /**
         * [perFileGreen[file]] contains the set of lines of [file] that always either do not appear in the TAC(s) or
         * appear there and touch the unsat cores. That is, these lines are "fully" needed by all relevant rules.
         */
        linesWithTouching.filter { it !in linesWithNotTouching }.groupBy { it.file }
            .mapValuesTo(perFileGreen) { entry -> entry.value.mapToSet { it.line } }

        /**
         * [perFileYellow[file]] contains the set of lines of [file] that contain both TAC commands that are touching some
         * unsat cores and not touching some unsat cores. That is, these lines are sometimes needed by the relevant rules.
         */
        linesPartiallyTouching.groupBy { it.file }
            .mapValuesTo(perFileYellow) { entry -> entry.value.mapToSet { it.line } }

        val visPane = buildVisualisationPane(perFileRed, perFileGreen, perFileYellow)
        val ruleMappingPane = buildRuleMappingPane(usedCmdsPerLine, unusedCmdsPerLine)

        mergeToHtml(visPane, ruleMappingPane)
    }

    private fun mergeToHtml(visPane: String, ruleMappingPane: String) {
        val htmlStringBuilder = StringBuilder()
        htmlStringBuilder.append("<!DOCTYPE html>\n")
        htmlStringBuilder.append("<html lang=\"en\">\n")
        htmlStringBuilder.append("<head>\n$head\n</head>\n")
        htmlStringBuilder.append(cssStyles)
        htmlStringBuilder.append("<body>\n")
        htmlStringBuilder.append(lineHighlightCSS())
        htmlStringBuilder.append("<div class=\"wrapper\" style=\"display: flex; \">\n")
        htmlStringBuilder.append(visPane)
        htmlStringBuilder.append(ruleMappingPane)
        htmlStringBuilder.append("</div>\n")
        htmlStringBuilder.append(codeHighlighScript)
        htmlStringBuilder.append("</body>\n")
        htmlStringBuilder.append("</html>")
        dumpToFile(htmlStringBuilder.toString())
    }

    private fun dumpToFile(content: String) {
        ArtifactManagerFactory().registerArtifact(visualisationDumpFile, StaticArtifactLocation.Reports) { name ->
            ArtifactFileUtils.getWriterForFile(name, true).use { it.append(content) }
        }
    }

    /**
     * For commands that represent a function call or contract, the corresponding string we have is the whole
     * function/contract. We do not want to show the whole function/contract, so we keep only the keyword&name.
     * Also, we keep only at most [n] characters in the cmd.
     */
    private fun UnsatCoreCmd.getCmdToPrint(maxLen: Int = 200): String {
        @Suppress("ForbiddenMethodCall")
        val filtered = if (cmd.startsWith("contract ") && "{" in cmd) {
            cmd.take(cmd.indexOf("{"))
        } else if (cmd.startsWith("function ") && "{" in cmd) {
            cmd.take(cmd.indexOf("{"))
        } else {
            cmd
        }
        return filtered.takeIf { it.length <= maxLen } ?: (filtered.take(maxLen) + "...trimmed...").sanitize()
    }

    private fun IRule.getNameToPrint() = this.ruleIdentifier.toString().sanitize()

    private fun buildRuleMappingPane(
        usedCmdsPerLine: Map<Line, MultiMap<IRule, UnsatCoreCmd>>,
        unusedCmdsPerLine: Map<Line, MultiMap<IRule, UnsatCoreCmd>>
    ): String {
        val htmlStringBuilder = StringBuilder()

        /**
         * Itemize the used cmds and rules (for the underlying line). Based on [groupByRules], the itemisation goes either:
         * - rule: ruleA
         *      - value: cmdA
         *      - value: cmdB
         * Or:
         * - value: cmdA
         *      - rule: ruleA
         *      - rule: ruleB
         *
         * The latter grouping is typically more suitable for .sol files because we have a fewer unique cmds and more
         * rules that touch the cmds. And vice versa.
         */
        fun renderCmdsAndRules(
            cmdsPerRule: MultiMap<IRule, UnsatCoreCmd>,
            cmdsClass: String,
            cmdsClassLabel: String,
            groupByRules: Boolean
        ) {
            htmlStringBuilder.append("<div class=$cmdsClass-wrapper>\n")
            htmlStringBuilder.append("<h3 class=\"$cmdsClass\">$cmdsClassLabel</h3>")

            if (groupByRules) {
                cmdsPerRule.forEachEntry { (rule, cmds) ->
                    htmlStringBuilder.append("<h4 class=\"rule-list-ruleName\">rule: ${rule.getNameToPrint()}</h4>\n")
                    htmlStringBuilder.append("<ul class=\"rule-list-ruleName-cmds\">\n")
                    cmds.forEach { cmd ->
                        htmlStringBuilder.append("<li>value: ${cmd.getCmdToPrint()}</li>\n")
                    }
                    htmlStringBuilder.append("</ul>\n")
                }
            } else {
                val rulesPerCmd = mutableMapOf<UnsatCoreCmd, MutableSet<IRule>>().also { s ->
                    cmdsPerRule.forEachEntry { (rule, cmds) ->
                        cmds.forEach { cmd ->
                            (s.getOrPut(cmd) { mutableSetOf() }).add(rule)
                        }
                    }
                }
                rulesPerCmd.forEachEntry { (cmd, rules) ->
                    htmlStringBuilder.append("<h4 class=\"rule-list-ruleName\">value: ${cmd.getCmdToPrint()}</h4>\n")
                    htmlStringBuilder.append("<ul class=\"rule-list-ruleName-cmds\">\n")
                    rules.forEach { rule ->
                        htmlStringBuilder.append("<li>rule: ${rule.getNameToPrint()}</li>\n")
                    }
                    htmlStringBuilder.append("</ul>\n")
                }
            }
            htmlStringBuilder.append("</div>\n")
        }


        htmlStringBuilder.append("<div id=\"rule-mapping-pane\">\n")
        htmlStringBuilder.append("<div id=\"rule-mapping-pane-inner\">\n")
        (usedCmdsPerLine.keys + unusedCmdsPerLine.keys).groupBy { it.file }.forEachEntry { (filename, lines) ->
            htmlStringBuilder.append("<div class=\"rule-mapping-file-block\">")
            htmlStringBuilder.append("<h3>$filename</h3>")
            @Suppress("ForbiddenMethodCall")
            val groupByRules = filename.endsWith(".spec")
            lines.forEach { line ->
                val fileId = idOfFile(filename)
                val lineId = "commands-line-${line.line}-source-file-$fileId"
                val lineLink = "#code-line-${line.line}-source-file-$fileId"
                htmlStringBuilder.append("<div class=\"commands-wrapper\" id=\"$lineId\">\n")
                htmlStringBuilder.append("<h4 class=\"rule-list-ruleName\"><a href=\"$lineLink\">")
                htmlStringBuilder.append("<button>" +
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"16\" viewBox=\"0 -960 960 960\" width=\"16\">" +
                    "<path d=\"M561-240 320-481l241-241 43 43-198 198 198 198-43 43Z\"></path></svg>" +
                    " Line ${line.line}</button>")
                htmlStringBuilder.append("</a></h4>")

                if (usedCmdsPerLine[line] != null && usedCmdsPerLine[line]!!.any { it.value.isNotEmpty() }) {
                    renderCmdsAndRules(usedCmdsPerLine[line]!!, "used-commands", "values that matter", groupByRules)
                }
                if (unusedCmdsPerLine[line] != null && unusedCmdsPerLine[line]!!.any { it.value.isNotEmpty() }) {
                    renderCmdsAndRules(unusedCmdsPerLine[line]!!, "unused-commands", "values that do not matter", groupByRules)
                }
                htmlStringBuilder.append("</div>\n")
            }
            htmlStringBuilder.append("</div>\n")
        }
        htmlStringBuilder.append("</div>\n") // closing rule-mapping-pane
        htmlStringBuilder.append("</div>\n") // closing rule-mapping-pane-inner

        return htmlStringBuilder.toString()
    }

    /**
     * Creates an HTML string with the lines coverage visualisation. In particular, [perFileRed] contains for particular
     * files (.sol or .spec) sets of lines that should be highlighted red, and similarly for [perFileGreen] and
     * [perFileYellow].
     */
    private fun buildVisualisationPane(
        perFileRed: MultiMap<String, Int>,
        perFileGreen: MultiMap<String, Int>,
        perFileYellow: MultiMap<String, Int>
    ): String {

        val htmlStringBuilder = StringBuilder()
        htmlStringBuilder.append("<div id=\"visualisation-pane\">\n")
        htmlStringBuilder.append("<div id=\"visualisation-pane-inner\">\n")

        (perFileRed.keys + perFileGreen.keys + perFileYellow.keys).forEach { file ->
            htmlStringBuilder.append("<h2> Visualisation of $file </h2>\n")
            val fileId = idOfFile(file)
            val highlightedLines = (
                perFileGreen.getOrDefault(file, listOf()) +
                    perFileRed.getOrDefault(file, listOf()) +
                    perFileYellow.getOrDefault(file, listOf())
                ).joinToString(",")

            /**
             * The "data-line" entries will be processed by javascript (a part of [codeHighlighScript]);
             * it will make the lines clickable links to the right-pane and also highlight the lines (yellow/red/green)
             * (see [lineHighlightCSS]).
             */
            htmlStringBuilder.append(
                "<pre id=\"source-file-$fileId\" data-line=\"$highlightedLines\" style=\"margin: 40px 0 60px 0;\" " +
                    "class=\"linkable-line-numbers line-numbers line-highlight\">"
            )
            htmlStringBuilder.append("<code  class=\"linkable-line-numbers line-numbers language-spec\">")
            val certoraSourcesFilepath = ArtifactFileUtils.wrapPathWith(file, Config.getSourcesSubdirInInternal())
            htmlStringBuilder.append(File(certoraSourcesFilepath).readText().sanitize())
            htmlStringBuilder.append("</code></pre>\n") //closing code-block
        }

        htmlStringBuilder.append("</div>\n") // closing visualisation-pane
        htmlStringBuilder.append("</div>\n") //closing visualisation-pane-inner

        return htmlStringBuilder.toString()
    }

    private fun lineHighlightCSS(): String {
        val htmlStringBuilder = StringBuilder()
        htmlStringBuilder.append("<style>")
        listOf(perFileRed to redColor, perFileYellow to yellowColor, perFileGreen to greenColor).forEach { (perFile, color) ->
            perFile.forEachEntry { (filename, lines) ->
                val selectors = lines.map { line ->
                    // The #code-line... elements are generate by the javascript part.
                    "#code-line-$line-source-file-${idOfFile(filename)}-bg"
                }.joinToString(", ")
                htmlStringBuilder.append("$selectors {\nbackground-color: $color;\n }\n")
            }
        }

        htmlStringBuilder.append("</style>")
        return htmlStringBuilder.toString()
    }

    companion object {
        const val yellowColor = "#ffeb3b"
        const val greenColor = "rgba(48, 255, 48, 0.45);"
        const val redColor = "rgba(255, 0, 0, 0.2)"

        const val visualisationDumpFile = "UnsatCoreVisualisation.html"

        private const val prismJS = "var _self=\"undefined\"!=typeof window?window:\"undefined\"!=typeof WorkerGlobalScope&&self instanceof WorkerGlobalScope?self:{},Prism=function(o){var n=/(?:^|\\s)lang(?:uage)?-([\\w-]+)(?=\\s|\$)/i,t=0,e={},j={manual:o.Prism&&o.Prism.manual,disableWorkerMessageHandler:o.Prism&&o.Prism.disableWorkerMessageHandler,util:{encode:function e(t){return t instanceof C?new C(t.type,e(t.content),t.alias):Array.isArray(t)?t.map(e):t.replace(/&/g,\"&amp;\").replace(/</g,\"&lt;\").replace(/\\u00a0/g,\" \")},type:function(e){return Object.prototype.toString.call(e).slice(8,-1)},objId:function(e){return e.__id||Object.defineProperty(e,\"__id\",{value:++t}),e.__id},clone:function n(e,a){var r,t;switch(a=a||{},j.util.type(e)){case\"Object\":if(t=j.util.objId(e),a[t])return a[t];for(var s in r={},a[t]=r,e)e.hasOwnProperty(s)&&(r[s]=n(e[s],a));return r;case\"Array\":return(t=j.util.objId(e),a[t])?a[t]:(r=[],a[t]=r,e.forEach(function(e,t){r[t]=n(e,a)}),r);default:return e}},getLanguage:function(e){for(;e;){var t=n.exec(e.className);if(t)return t[1].toLowerCase();e=e.parentElement}return\"none\"},setLanguage:function(e,t){e.className=e.className.replace(RegExp(n,\"gi\"),\"\"),e.classList.add(\"language-\"+t)},currentScript:function(){if(\"undefined\"==typeof document)return null;if(\"currentScript\"in document)return document.currentScript;try{throw new Error}catch(e){var t=(/at [^(\\r\\n]*\\((.*):[^:]+:[^:]+\\)\$/i.exec(e.stack)||[])[1];if(t){var n,a=document.getElementsByTagName(\"script\");for(n in a)if(a[n].src==t)return a[n]}return null}},isActive:function(e,t,n){for(var a=\"no-\"+t;e;){var r=e.classList;if(r.contains(t))return!0;if(r.contains(a))return!1;e=e.parentElement}return!!n}},languages:{plain:e,plaintext:e,text:e,txt:e,extend:function(e,t){var n,a=j.util.clone(j.languages[e]);for(n in t)a[n]=t[n];return a},insertBefore:function(n,e,t,a){var r,s=(a=a||j.languages)[n],i={};for(r in s)if(s.hasOwnProperty(r)){if(r==e)for(var l in t)t.hasOwnProperty(l)&&(i[l]=t[l]);t.hasOwnProperty(r)||(i[r]=s[r])}var o=a[n];return a[n]=i,j.languages.DFS(j.languages,function(e,t){t===o&&e!=n&&(this[e]=i)}),i},DFS:function e(t,n,a,r){r=r||{};var s,i,l,o=j.util.objId;for(s in t)t.hasOwnProperty(s)&&(n.call(t,s,t[s],a||s),i=t[s],\"Object\"!==(l=j.util.type(i))||r[o(i)]?\"Array\"!==l||r[o(i)]||(r[o(i)]=!0,e(i,n,s,r)):(r[o(i)]=!0,e(i,n,null,r)))}},plugins:{},highlightAll:function(e,t){j.highlightAllUnder(document,e,t)},highlightAllUnder:function(e,t,n){var a={callback:n,container:e,selector:'code[class*=\"language-\"], [class*=\"language-\"] code, code[class*=\"lang-\"], [class*=\"lang-\"] code'};j.hooks.run(\"before-highlightall\",a),a.elements=Array.prototype.slice.apply(a.container.querySelectorAll(a.selector)),j.hooks.run(\"before-all-elements-highlight\",a);for(var r,s=0;r=a.elements[s++];)j.highlightElement(r,!0===t,a.callback)},highlightElement:function(e,t,n){var a=j.util.getLanguage(e),r=j.languages[a],s=(j.util.setLanguage(e,a),e.parentElement);s&&\"pre\"===s.nodeName.toLowerCase()&&j.util.setLanguage(s,a);var i={element:e,language:a,grammar:r,code:e.textContent};function l(e){i.highlightedCode=e,j.hooks.run(\"before-insert\",i),i.element.innerHTML=i.highlightedCode,j.hooks.run(\"after-highlight\",i),j.hooks.run(\"complete\",i),n&&n.call(i.element)}if(j.hooks.run(\"before-sanity-check\",i),(s=i.element.parentElement)&&\"pre\"===s.nodeName.toLowerCase()&&!s.hasAttribute(\"tabindex\")&&s.setAttribute(\"tabindex\",\"0\"),!i.code)return j.hooks.run(\"complete\",i),void(n&&n.call(i.element));j.hooks.run(\"before-highlight\",i),i.grammar?t&&o.Worker?((a=new Worker(j.filename)).onmessage=function(e){l(e.data)},a.postMessage(JSON.stringify({language:i.language,code:i.code,immediateClose:!0}))):l(j.highlight(i.code,i.grammar,i.language)):l(j.util.encode(i.code))},highlight:function(e,t,n){e={code:e,grammar:t,language:n};if(j.hooks.run(\"before-tokenize\",e),e.grammar)return e.tokens=j.tokenize(e.code,e.grammar),j.hooks.run(\"after-tokenize\",e),C.stringify(j.util.encode(e.tokens),e.language);throw new Error('The language \"'+e.language+'\" has no grammar.')},tokenize:function(e,t){var n=t.rest;if(n){for(var a in n)t[a]=n[a];delete t.rest}for(var r=new u,s=(z(r,r.head,e),!function e(t,n,a,r,s,i){for(var l in a)if(a.hasOwnProperty(l)&&a[l]){var o=a[l];o=Array.isArray(o)?o:[o];for(var u=0;u<o.length;++u){if(i&&i.cause==l+\",\"+u)return;for(var g,c=o[u],d=c.inside,p=!!c.lookbehind,m=!!c.greedy,h=c.alias,f=(m&&!c.pattern.global&&(g=c.pattern.toString().match(/[imsuy]*\$/)[0],c.pattern=RegExp(c.pattern.source,g+\"g\")),c.pattern||c),b=r.next,y=s;b!==n.tail&&!(i&&y>=i.reach);y+=b.value.length,b=b.next){var v=b.value;if(n.length>t.length)return;if(!(v instanceof C)){var F,x=1;if(m){if(!(F=L(f,y,t,p))||F.index>=t.length)break;var k=F.index,w=F.index+F[0].length,A=y;for(A+=b.value.length;A<=k;)b=b.next,A+=b.value.length;if(A-=b.value.length,y=A,b.value instanceof C)continue;for(var P=b;P!==n.tail&&(A<w||\"string\"==typeof P.value);P=P.next)x++,A+=P.value.length;x--,v=t.slice(y,A),F.index-=y}else if(!(F=L(f,0,v,p)))continue;var k=F.index,\$=F[0],S=v.slice(0,k),E=v.slice(k+\$.length),v=y+v.length,_=(i&&v>i.reach&&(i.reach=v),b.prev),S=(S&&(_=z(n,_,S),y+=S.length),O(n,_,x),new C(l,d?j.tokenize(\$,d):\$,h,\$));b=z(n,_,S),E&&z(n,b,E),1<x&&(\$={cause:l+\",\"+u,reach:v},e(t,n,a,b.prev,y,\$),i&&\$.reach>i.reach&&(i.reach=\$.reach))}}}}}(e,r,t,r.head,0),r),i=[],l=s.head.next;l!==s.tail;)i.push(l.value),l=l.next;return i},hooks:{all:{},add:function(e,t){var n=j.hooks.all;n[e]=n[e]||[],n[e].push(t)},run:function(e,t){var n=j.hooks.all[e];if(n&&n.length)for(var a,r=0;a=n[r++];)a(t)}},Token:C};function C(e,t,n,a){this.type=e,this.content=t,this.alias=n,this.length=0|(a||\"\").length}function L(e,t,n,a){e.lastIndex=t;t=e.exec(n);return t&&a&&t[1]&&(e=t[1].length,t.index+=e,t[0]=t[0].slice(e)),t}function u(){var e={value:null,prev:null,next:null},t={value:null,prev:e,next:null};e.next=t,this.head=e,this.tail=t,this.length=0}function z(e,t,n){var a=t.next,n={value:n,prev:t,next:a};return t.next=n,a.prev=n,e.length++,n}function O(e,t,n){for(var a=t.next,r=0;r<n&&a!==e.tail;r++)a=a.next;(t.next=a).prev=t,e.length-=r}if(o.Prism=j,C.stringify=function t(e,n){if(\"string\"==typeof e)return e;var a;if(Array.isArray(e))return a=\"\",e.forEach(function(e){a+=t(e,n)}),a;var r,s={type:e.type,content:t(e.content,n),tag:\"span\",classes:[\"token\",e.type],attributes:{},language:n},e=e.alias,i=(e&&(Array.isArray(e)?Array.prototype.push.apply(s.classes,e):s.classes.push(e)),j.hooks.run(\"wrap\",s),\"\");for(r in s.attributes)i+=\" \"+r+'=\"'+(s.attributes[r]||\"\").replace(/\"/g,\"&quot;\")+'\"';return\"<\"+s.tag+' class=\"'+s.classes.join(\" \")+'\"'+i+\">\"+s.content+\"</\"+s.tag+\">\"},!o.document)return o.addEventListener&&(j.disableWorkerMessageHandler||o.addEventListener(\"message\",function(e){var e=JSON.parse(e.data),t=e.language,n=e.code,e=e.immediateClose;o.postMessage(j.highlight(n,j.languages[t],t)),e&&o.close()},!1)),j;var a,e=j.util.currentScript();function r(){j.manual||j.highlightAll()}return e&&(j.filename=e.src,e.hasAttribute(\"data-manual\")&&(j.manual=!0)),j.manual||(\"loading\"===(a=document.readyState)||\"interactive\"===a&&e&&e.defer?document.addEventListener(\"DOMContentLoaded\",r):window.requestAnimationFrame?window.requestAnimationFrame(r):window.setTimeout(r,16)),j}(_self);\"undefined\"!=typeof module&&module.exports&&(module.exports=Prism),\"undefined\"!=typeof global&&(global.Prism=Prism),Prism.languages.markup={comment:{pattern:/<!--(?:(?!<!--)[\\s\\S])*?-->/,greedy:!0},prolog:{pattern:/<\\?[\\s\\S]+?\\?>/,greedy:!0},doctype:{pattern:/<!DOCTYPE(?:[^>\"'[\\]]|\"[^\"]*\"|'[^']*')+(?:\\[(?:[^<\"'\\]]|\"[^\"]*\"|'[^']*'|<(?!!--)|<!--(?:[^-]|-(?!->))*-->)*\\]\\s*)?>/i,greedy:!0,inside:{\"internal-subset\":{pattern:/(^[^\\[]*\\[)[\\s\\S]+(?=\\]>\$)/,lookbehind:!0,greedy:!0,inside:null},string:{pattern:/\"[^\"]*\"|'[^']*'/,greedy:!0},punctuation:/^<!|>\$|[[\\]]/,\"doctype-tag\":/^DOCTYPE/i,name:/[^\\s<>'\"]+/}},cdata:{pattern:/<!\\[CDATA\\[[\\s\\S]*?\\]\\]>/i,greedy:!0},tag:{pattern:/<\\/?(?!\\d)[^\\s>\\/=\$<%]+(?:\\s(?:\\s*[^\\s>\\/=]+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s'\">=]+(?=[\\s>]))|(?=[\\s/>])))+)?\\s*\\/?>/,greedy:!0,inside:{tag:{pattern:/^<\\/?[^\\s>\\/]+/,inside:{punctuation:/^<\\/?/,namespace:/^[^\\s>\\/:]+:/}},\"special-attr\":[],\"attr-value\":{pattern:/=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s'\">=]+)/,inside:{punctuation:[{pattern:/^=/,alias:\"attr-equals\"},/\"|'/]}},punctuation:/\\/?>/,\"attr-name\":{pattern:/[^\\s>\\/]+/,inside:{namespace:/^[^\\s>\\/:]+:/}}}},entity:[{pattern:/&[\\da-z]{1,8};/i,alias:\"named-entity\"},/&#x?[\\da-f]{1,8};/i]},Prism.languages.markup.tag.inside[\"attr-value\"].inside.entity=Prism.languages.markup.entity,Prism.languages.markup.doctype.inside[\"internal-subset\"].inside=Prism.languages.markup,Prism.hooks.add(\"wrap\",function(e){\"entity\"===e.type&&(e.attributes.title=e.content.replace(/&amp;/,\"&\"))}),Object.defineProperty(Prism.languages.markup.tag,\"addInlined\",{value:function(e,t){var n={},n=(n[\"language-\"+t]={pattern:/(^<!\\[CDATA\\[)[\\s\\S]+?(?=\\]\\]>\$)/i,lookbehind:!0,inside:Prism.languages[t]},n.cdata=/^<!\\[CDATA\\[|\\]\\]>\$/i,{\"included-cdata\":{pattern:/<!\\[CDATA\\[[\\s\\S]*?\\]\\]>/i,inside:n}}),t=(n[\"language-\"+t]={pattern:/[\\s\\S]+/,inside:Prism.languages[t]},{});t[e]={pattern:RegExp(/(<__[^>]*>)(?:<!\\[CDATA\\[(?:[^\\]]|\\](?!\\]>))*\\]\\]>|(?!<!\\[CDATA\\[)[\\s\\S])*?(?=<\\/__>)/.source.replace(/__/g,function(){return e}),\"i\"),lookbehind:!0,greedy:!0,inside:n},Prism.languages.insertBefore(\"markup\",\"cdata\",t)}}),Object.defineProperty(Prism.languages.markup.tag,\"addAttribute\",{value:function(e,t){Prism.languages.markup.tag.inside[\"special-attr\"].push({pattern:RegExp(/(^|[\"'\\s])/.source+\"(?:\"+e+\")\"+/\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s'\">=]+(?=[\\s>]))/.source,\"i\"),lookbehind:!0,inside:{\"attr-name\":/^[^\\s=]+/,\"attr-value\":{pattern:/=[\\s\\S]+/,inside:{value:{pattern:/(^=\\s*([\"']|(?![\"'])))\\S[\\s\\S]*(?=\\2\$)/,lookbehind:!0,alias:[t,\"language-\"+t],inside:Prism.languages[t]},punctuation:[{pattern:/^=/,alias:\"attr-equals\"},/\"|'/]}}}})}}),Prism.languages.html=Prism.languages.markup,Prism.languages.mathml=Prism.languages.markup,Prism.languages.svg=Prism.languages.markup,Prism.languages.xml=Prism.languages.extend(\"markup\",{}),Prism.languages.ssml=Prism.languages.xml,Prism.languages.atom=Prism.languages.xml,Prism.languages.rss=Prism.languages.xml,function(e){var t=/(?:\"(?:\\\\(?:\\r\\n|[\\s\\S])|[^\"\\\\\\r\\n])*\"|'(?:\\\\(?:\\r\\n|[\\s\\S])|[^'\\\\\\r\\n])*')/,t=(e.languages.css={comment:/\\/\\*[\\s\\S]*?\\*\\//,atrule:{pattern:/@[\\w-](?:[^;{\\s]|\\s+(?![\\s{]))*(?:;|(?=\\s*\\{))/,inside:{rule:/^@[\\w-]+/,\"selector-function-argument\":{pattern:/(\\bselector\\s*\\(\\s*(?![\\s)]))(?:[^()\\s]|\\s+(?![\\s)])|\\((?:[^()]|\\([^()]*\\))*\\))+(?=\\s*\\))/,lookbehind:!0,alias:\"selector\"},keyword:{pattern:/(^|[^\\w-])(?:and|not|only|or)(?![\\w-])/,lookbehind:!0}}},url:{pattern:RegExp(\"\\\\burl\\\\((?:\"+t.source+\"|\"+/(?:[^\\\\\\r\\n()\"']|\\\\[\\s\\S])*/.source+\")\\\\)\",\"i\"),greedy:!0,inside:{function:/^url/i,punctuation:/^\\(|\\)\$/,string:{pattern:RegExp(\"^\"+t.source+\"\$\"),alias:\"url\"}}},selector:{pattern:RegExp(\"(^|[{}\\\\s])[^{}\\\\s](?:[^{};\\\"'\\\\s]|\\\\s+(?![\\\\s{])|\"+t.source+\")*(?=\\\\s*\\\\{)\"),lookbehind:!0},string:{pattern:t,greedy:!0},property:{pattern:/(^|[^-\\w\\xA0-\\uFFFF])(?!\\s)[-_a-z\\xA0-\\uFFFF](?:(?!\\s)[-\\w\\xA0-\\uFFFF])*(?=\\s*:)/i,lookbehind:!0},important:/!important\\b/i,function:{pattern:/(^|[^-a-z0-9])[-a-z0-9]+(?=\\()/i,lookbehind:!0},punctuation:/[(){};:,]/},e.languages.css.atrule.inside.rest=e.languages.css,e.languages.markup);t&&(t.tag.addInlined(\"style\",\"css\"),t.tag.addAttribute(\"style\",\"css\"))}(Prism),Prism.languages.clike={comment:[{pattern:/(^|[^\\\\])\\/\\*[\\s\\S]*?(?:\\*\\/|\$)/,lookbehind:!0,greedy:!0},{pattern:/(^|[^\\\\:])\\/\\/.*/,lookbehind:!0,greedy:!0}],string:{pattern:/([\"'])(?:\\\\(?:\\r\\n|[\\s\\S])|(?!\\1)[^\\\\\\r\\n])*\\1/,greedy:!0},\"class-name\":{pattern:/(\\b(?:class|extends|implements|instanceof|interface|new|trait)\\s+|\\bcatch\\s+\\()[\\w.\\\\]+/i,lookbehind:!0,inside:{punctuation:/[.\\\\]/}},keyword:/\\b(?:break|catch|continue|do|else|finally|for|function|if|in|instanceof|new|null|return|throw|try|while)\\b/,boolean:/\\b(?:false|true)\\b/,function:/\\b\\w+(?=\\()/,number:/\\b0x[\\da-f]+\\b|(?:\\b\\d+(?:\\.\\d*)?|\\B\\.\\d+)(?:e[+-]?\\d+)?/i,operator:/[<>]=?|[!=]=?=?|--?|\\+\\+?|&&?|\\|\\|?|[?*/~^%]/,punctuation:/[{}[\\];(),.:]/},Prism.languages.javascript=Prism.languages.extend(\"clike\",{\"class-name\":[Prism.languages.clike[\"class-name\"],{pattern:/(^|[^\$\\w\\xA0-\\uFFFF])(?!\\s)[_\$A-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*(?=\\.(?:constructor|prototype))/,lookbehind:!0}],keyword:[{pattern:/((?:^|\\})\\s*)catch\\b/,lookbehind:!0},{pattern:/(^|[^.]|\\.\\.\\.\\s*)\\b(?:as|assert(?=\\s*\\{)|async(?=\\s*(?:function\\b|\\(|[\$\\w\\xA0-\\uFFFF]|\$))|await|break|case|class|const|continue|debugger|default|delete|do|else|enum|export|extends|finally(?=\\s*(?:\\{|\$))|for|from(?=\\s*(?:['\"]|\$))|function|(?:get|set)(?=\\s*(?:[#\\[\$\\w\\xA0-\\uFFFF]|\$))|if|implements|import|in|instanceof|interface|let|new|null|of|package|private|protected|public|return|static|super|switch|this|throw|try|typeof|undefined|var|void|while|with|yield)\\b/,lookbehind:!0}],function:/#?(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*(?=\\s*(?:\\.\\s*(?:apply|bind|call)\\s*)?\\()/,number:{pattern:RegExp(/(^|[^\\w\$])/.source+\"(?:\"+/NaN|Infinity/.source+\"|\"+/0[bB][01]+(?:_[01]+)*n?/.source+\"|\"+/0[oO][0-7]+(?:_[0-7]+)*n?/.source+\"|\"+/0[xX][\\dA-Fa-f]+(?:_[\\dA-Fa-f]+)*n?/.source+\"|\"+/\\d+(?:_\\d+)*n/.source+\"|\"+/(?:\\d+(?:_\\d+)*(?:\\.(?:\\d+(?:_\\d+)*)?)?|\\.\\d+(?:_\\d+)*)(?:[Ee][+-]?\\d+(?:_\\d+)*)?/.source+\")\"+/(?![\\w\$])/.source),lookbehind:!0},operator:/--|\\+\\+|\\*\\*=?|=>|&&=?|\\|\\|=?|[!=]==|<<=?|>>>?=?|[-+*/%&|^!=<>]=?|\\.{3}|\\?\\?=?|\\?\\.?|[~:]/}),Prism.languages.javascript[\"class-name\"][0].pattern=/(\\b(?:class|extends|implements|instanceof|interface|new)\\s+)[\\w.\\\\]+/,Prism.languages.insertBefore(\"javascript\",\"keyword\",{regex:{pattern:RegExp(/((?:^|[^\$\\w\\xA0-\\uFFFF.\"'\\])\\s]|\\b(?:return|yield))\\s*)/.source+/\\//.source+\"(?:\"+/(?:\\[(?:[^\\]\\\\\\r\\n]|\\\\.)*\\]|\\\\.|[^/\\\\\\[\\r\\n])+\\/[dgimyus]{0,7}/.source+\"|\"+/(?:\\[(?:[^[\\]\\\\\\r\\n]|\\\\.|\\[(?:[^[\\]\\\\\\r\\n]|\\\\.|\\[(?:[^[\\]\\\\\\r\\n]|\\\\.)*\\])*\\])*\\]|\\\\.|[^/\\\\\\[\\r\\n])+\\/[dgimyus]{0,7}v[dgimyus]{0,7}/.source+\")\"+/(?=(?:\\s|\\/\\*(?:[^*]|\\*(?!\\/))*\\*\\/)*(?:\$|[\\r\\n,.;:})\\]]|\\/\\/))/.source),lookbehind:!0,greedy:!0,inside:{\"regex-source\":{pattern:/^(\\/)[\\s\\S]+(?=\\/[a-z]*\$)/,lookbehind:!0,alias:\"language-regex\",inside:Prism.languages.regex},\"regex-delimiter\":/^\\/|\\/\$/,\"regex-flags\":/^[a-z]+\$/}},\"function-variable\":{pattern:/#?(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*(?=\\s*[=:]\\s*(?:async\\s*)?(?:\\bfunction\\b|(?:\\((?:[^()]|\\([^()]*\\))*\\)|(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*)\\s*=>))/,alias:\"function\"},parameter:[{pattern:/(function(?:\\s+(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*)?\\s*\\(\\s*)(?!\\s)(?:[^()\\s]|\\s+(?![\\s)])|\\([^()]*\\))+(?=\\s*\\))/,lookbehind:!0,inside:Prism.languages.javascript},{pattern:/(^|[^\$\\w\\xA0-\\uFFFF])(?!\\s)[_\$a-z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*(?=\\s*=>)/i,lookbehind:!0,inside:Prism.languages.javascript},{pattern:/(\\(\\s*)(?!\\s)(?:[^()\\s]|\\s+(?![\\s)])|\\([^()]*\\))+(?=\\s*\\)\\s*=>)/,lookbehind:!0,inside:Prism.languages.javascript},{pattern:/((?:\\b|\\s|^)(?!(?:as|async|await|break|case|catch|class|const|continue|debugger|default|delete|do|else|enum|export|extends|finally|for|from|function|get|if|implements|import|in|instanceof|interface|let|new|null|of|package|private|protected|public|return|set|static|super|switch|this|throw|try|typeof|undefined|var|void|while|with|yield)(?![\$\\w\\xA0-\\uFFFF]))(?:(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*\\s*)\\(\\s*|\\]\\s*\\(\\s*)(?!\\s)(?:[^()\\s]|\\s+(?![\\s)])|\\([^()]*\\))+(?=\\s*\\)\\s*\\{)/,lookbehind:!0,inside:Prism.languages.javascript}],constant:/\\b[A-Z](?:[A-Z_]|\\dx?)*\\b/}),Prism.languages.insertBefore(\"javascript\",\"string\",{hashbang:{pattern:/^#!.*/,greedy:!0,alias:\"comment\"},\"template-string\":{pattern:/`(?:\\\\[\\s\\S]|\\\$\\{(?:[^{}]|\\{(?:[^{}]|\\{[^}]*\\})*\\})+\\}|(?!\\\$\\{)[^\\\\`])*`/,greedy:!0,inside:{\"template-punctuation\":{pattern:/^`|`\$/,alias:\"string\"},interpolation:{pattern:/((?:^|[^\\\\])(?:\\\\{2})*)\\\$\\{(?:[^{}]|\\{(?:[^{}]|\\{[^}]*\\})*\\})+\\}/,lookbehind:!0,inside:{\"interpolation-punctuation\":{pattern:/^\\\$\\{|\\}\$/,alias:\"punctuation\"},rest:Prism.languages.javascript}},string:/[\\s\\S]+/}},\"string-property\":{pattern:/((?:^|[,{])[ \\t]*)([\"'])(?:\\\\(?:\\r\\n|[\\s\\S])|(?!\\2)[^\\\\\\r\\n])*\\2(?=\\s*:)/m,lookbehind:!0,greedy:!0,alias:\"property\"}}),Prism.languages.insertBefore(\"javascript\",\"operator\",{\"literal-property\":{pattern:/((?:^|[,{])[ \\t]*)(?!\\s)[_\$a-zA-Z\\xA0-\\uFFFF](?:(?!\\s)[\$\\w\\xA0-\\uFFFF])*(?=\\s*:)/m,lookbehind:!0,alias:\"property\"}}),Prism.languages.markup&&(Prism.languages.markup.tag.addInlined(\"script\",\"javascript\"),Prism.languages.markup.tag.addAttribute(/on(?:abort|blur|change|click|composition(?:end|start|update)|dblclick|error|focus(?:in|out)?|key(?:down|up)|load|mouse(?:down|enter|leave|move|out|over|up)|reset|resize|scroll|select|slotchange|submit|unload|wheel)/.source,\"javascript\")),Prism.languages.js=Prism.languages.javascript,function(){var o,u,g,c,e;void 0!==Prism&&\"undefined\"!=typeof document&&(Element.prototype.matches||(Element.prototype.matches=Element.prototype.msMatchesSelector||Element.prototype.webkitMatchesSelector),o={js:\"javascript\",py:\"python\",rb:\"ruby\",ps1:\"powershell\",psm1:\"powershell\",sh:\"bash\",bat:\"batch\",h:\"c\",tex:\"latex\"},c=\"pre[data-src]:not([\"+(u=\"data-src-status\")+'=\"loaded\"]):not(['+u+'=\"'+(g=\"loading\")+'\"])',Prism.hooks.add(\"before-highlightall\",function(e){e.selector+=\", \"+c}),Prism.hooks.add(\"before-sanity-check\",function(e){var r,t,n,a,s,i,l=e.element;l.matches(c)&&(e.code=\"\",l.setAttribute(u,g),(r=l.appendChild(document.createElement(\"CODE\"))).textContent=\"Loading…\",t=l.getAttribute(\"data-src\"),\"none\"===(e=e.language)&&(n=(/\\.(\\w+)\$/.exec(t)||[,\"none\"])[1],e=o[n]||n),Prism.util.setLanguage(r,e),Prism.util.setLanguage(l,e),(n=Prism.plugins.autoloader)&&n.loadLanguages(e),n=t,a=function(e){l.setAttribute(u,\"loaded\");var t,n,a=function(e){var t,n;if(e=/^\\s*(\\d+)\\s*(?:(,)\\s*(?:(\\d+)\\s*)?)?\$/.exec(e||\"\"))return t=Number(e[1]),n=e[2],e=e[3],n?e?[t,Number(e)]:[t,void 0]:[t,t]}(l.getAttribute(\"data-range\"));a&&(t=e.split(/\\r\\n?|\\n/g),n=a[0],a=null==a[1]?t.length:a[1],n<0&&(n+=t.length),n=Math.max(0,Math.min(n-1,t.length)),a<0&&(a+=t.length),a=Math.max(0,Math.min(a,t.length)),e=t.slice(n,a).join(\"\\n\"),l.hasAttribute(\"data-start\")||l.setAttribute(\"data-start\",String(n+1))),r.textContent=e,Prism.highlightElement(r)},s=function(e){l.setAttribute(u,\"failed\"),r.textContent=e},(i=new XMLHttpRequest).open(\"GET\",n,!0),i.onreadystatechange=function(){4==i.readyState&&(i.status<400&&i.responseText?a(i.responseText):400<=i.status?s(\"✖ Error \"+i.status+\" while fetching file: \"+i.statusText):s(\"✖ Error: File does not exist or is empty\"))},i.send(null))}),e=!(Prism.plugins.fileHighlight={highlight:function(e){for(var t,n=(e||document).querySelectorAll(c),a=0;t=n[a++];)Prism.highlightElement(t)}}),Prism.fileHighlight=function(){e||(console.warn(\"Prism.fileHighlight is deprecated. Use `Prism.plugins.fileHighlight.highlight` instead.\"),e=!0),Prism.plugins.fileHighlight.highlight.apply(this,arguments)})}();"
        private const val prismSolidityJS = "Prism.languages.solidity=Prism.languages.extend(\"clike\",{\"class-name\":{pattern:/(\\b(?:contract|enum|interface|library|new|struct|using)\\s+)(?!\\d)[\\w\$]+/,lookbehind:!0},keyword:/\\b(?:_|anonymous|as|assembly|assert|break|calldata|case|constant|constructor|continue|contract|default|delete|do|else|emit|enum|event|external|for|from|function|if|import|indexed|inherited|interface|internal|is|let|library|mapping|memory|modifier|new|payable|pragma|private|public|pure|require|returns?|revert|selfdestruct|solidity|storage|struct|suicide|switch|this|throw|using|var|view|while)\\b/,operator:/=>|->|:=|=:|\\*\\*|\\+\\+|--|\\|\\||&&|<<=?|>>=?|[-+*/%^&|<>!=]=?|[~?]/}),Prism.languages.insertBefore(\"solidity\",\"keyword\",{builtin:/\\b(?:address|bool|string|u?int(?:8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?|byte|bytes(?:[1-9]|[12]\\d|3[0-2])?)\\b/}),Prism.languages.insertBefore(\"solidity\",\"number\",{version:{pattern:/([<>]=?|\\^)\\d+\\.\\d+\\.\\d+\\b/,lookbehind:!0,alias:\"number\"}});"
        private const val prismSpecifyJS = "Prism.languages.specify = Prism.languages.extend('clike', {\n" +
            "    'class-name': {\n" +
            "\t\tpattern: /(\\b(?:rule|methods|invariant|definition|hook|ghost|sort|function)\\s+)(?!\\d)[\\w\$]+/,\n" +
            "\t\tlookbehind: true\n" +
            "\t},\n" +
            "\t'keyword': /\\b(?:rule|methods|invariant|definition|hook|ghost|sort|function|if|else|returns?|in|@new|@old|require|assert|requireInvariant|havoc|assuming|envfree|filtered|at|as|using|import|use|builtin|lastReverted|lastHasThrown|lastStorage|preserved|with|Sstore|Sload|offset|STORAGE|KEY|INDEX|axiom|init_state|max_uint|max_uint256|max_uint160|max_address|max_uint128|max_uint96|max_uint64|max_uint32|max_uint16|max_uint8|@withrevert|@norevert|@dontsummarize|fallback|NONDET|CONSTANT|DISPATCHER|ALWAYS|HAVOC|HAVOC_ECF|HAVOC_ALL|PER_CALLEE_CONSTANT|AUTO|forall|exists|call|s?invoke|loop_invariant)\\b/,\n" +
            "\t'operator': /[<>]=?|<?=>|[!=]=?=?|--?|\\+\\+?|&&?|\\|\\|?|[?*/~^%]/\n" +
            "});\n" +
            "\n" +
            "Prism.languages.insertBefore('specify', 'keyword', {\n" +
            "\t'builtin': /\\b(?:env|method|calldataarg|storage|address|bool|string|mathint|u?int(?:8|16|24|32|40|48|56|64|72|80|88|96|104|112|120|128|136|144|152|160|168|176|184|192|200|208|216|224|232|240|248|256)?|byte|bytes(?:[1-9]|[12]\\d|3[0-2])?)\\b/\n" +
            "});\n" +
            "\n" +
            "Prism.languages.spec = Prism.languages.specify;"


        private const val codeHighlighScript = """
    <script>$prismJS</script>
    <script>$prismSolidityJS</script>
    <script>$prismSpecifyJS</script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.20.0/components/prism-solidity.min.js">
    </script>

    <script>
        (function () {

            if (typeof Prism === 'undefined' || typeof document === 'undefined') {
                return;
            }

            /**
             * Plugin name which is used as a class name for <pre> which is activating the plugin
             *
             * @type {string}
             */
            var PLUGIN_NAME = 'line-numbers';

            /**
             * Regular expression used for determining line breaks
             *
             * @type {RegExp}
             */
            var NEW_LINE_EXP = /\n(?!${'$'})/g;


            /**
             * Global exports
             */
            var config = Prism.plugins.lineNumbers = {
                /**
                 * Get node for provided line number
                 *
                 * @param {Element} element pre element
                 * @param {number} number line number
                 * @returns {Element|undefined}
                 */
                getLine: function (element, number) {
                    if (element.tagName !== 'PRE' || !element.classList.contains(PLUGIN_NAME)) {
                        return;
                    }

                    var lineNumberRows = element.querySelector('.line-numbers-rows');
                    if (!lineNumberRows) {
                        return;
                    }
                    var lineNumberStart = parseInt(element.getAttribute('data-start'), 10) || 1;
                    var lineNumberEnd = lineNumberStart + (lineNumberRows.children.length - 1);

                    if (number < lineNumberStart) {
                        number = lineNumberStart;
                    }
                    if (number > lineNumberEnd) {
                        number = lineNumberEnd;
                    }

                    var lineIndex = number - lineNumberStart;
                    return lineNumberRows.children[lineIndex];
                },

                /**
                 * Resizes the line numbers of the given element.
                 *
                 * This function will not add line numbers. It will only resize existing ones.
                 *
                 * @param {HTMLElement} element A `<pre>` element with line numbers.
                 * @returns {void}
                 */
                resize: function (element) {
                    resizeElements([element]);
                },

                /**
                 * Whether the plugin can assume that the units font sizes and margins are not depended on the size of
                 * the current viewport.
                 *
                 * Setting this to `true` will allow the plugin to do certain optimizations for better performance.
                 *
                 * Set this to `false` if you use any of the following CSS units: `vh`, `vw`, `vmin`, `vmax`.
                 *
                 * @type {boolean}
                 */
                assumeViewportIndependence: true
            };

            /**
             * Resizes the given elements.
             *
             * @param {HTMLElement[]} elements
             */
            function resizeElements(elements) {
                elements = elements.filter(function (e) {
                    var codeStyles = getStyles(e);
                    var whiteSpace = codeStyles['white-space'];
                    return whiteSpace === 'pre-wrap' || whiteSpace === 'pre-line';
                });

                if (elements.length == 0) {
                    return;
                }

                var infos = elements.map(function (element) {
                    var codeElement = element.querySelector('code');
                    var lineNumbersWrapper = element.querySelector('.line-numbers-rows');
                    if (!codeElement || !lineNumbersWrapper) {
                        return undefined;
                    }

                    /** @type {HTMLElement} */
                    var lineNumberSizer = element.querySelector('.line-numbers-sizer');
                    var codeLines = codeElement.textContent.split(NEW_LINE_EXP);

                    if (!lineNumberSizer) {
                        lineNumberSizer = document.createElement('span');
                        lineNumberSizer.className = 'line-numbers-sizer';

                        codeElement.appendChild(lineNumberSizer);
                    }

                    lineNumberSizer.innerHTML = '0';
                    lineNumberSizer.style.display = 'block';

                    var oneLinerHeight = lineNumberSizer.getBoundingClientRect().height;
                    lineNumberSizer.innerHTML = '';

                    return {
                        element: element,
                        lines: codeLines,
                        lineHeights: [],
                        oneLinerHeight: oneLinerHeight,
                        sizer: lineNumberSizer,
                    };
                }).filter(Boolean);

                infos.forEach(function (info) {
                    var lineNumberSizer = info.sizer;
                    var lines = info.lines;
                    var lineHeights = info.lineHeights;
                    var oneLinerHeight = info.oneLinerHeight;

                    lineHeights[lines.length - 1] = undefined;
                    lines.forEach(function (line, index) {
                        if (line && line.length > 1) {
                            var e = lineNumberSizer.appendChild(document.createElement('span'));
                            e.style.display = 'block';
                            e.textContent = line;
                        } else {
                            lineHeights[index] = oneLinerHeight;
                        }
                    });
                });

                infos.forEach(function (info) {
                    var lineNumberSizer = info.sizer;
                    var lineHeights = info.lineHeights;

                    var childIndex = 0;
                    for (var i = 0; i < lineHeights.length; i++) {
                        if (lineHeights[i] === undefined) {
                            lineHeights[i] = lineNumberSizer.children[childIndex++].getBoundingClientRect().height;
                        }
                    }
                });

                infos.forEach(function (info) {
                    var lineNumberSizer = info.sizer;
                    var wrapper = info.element.querySelector('.line-numbers-rows');

                    lineNumberSizer.style.display = 'none';
                    lineNumberSizer.innerHTML = '';

                    info.lineHeights.forEach(function (height, lineNumber) {
                        wrapper.children[lineNumber].style.height = height + 'px';
                    });
                });
            }

            /**
             * Returns style declarations for the element
             *
             * @param {Element} element
             */
            function getStyles(element) {
                if (!element) {
                    return null;
                }

                return window.getComputedStyle ? getComputedStyle(element) : (element.currentStyle || null);
            }

            var lastWidth = undefined;
            window.addEventListener('resize', function () {
                if (config.assumeViewportIndependence && lastWidth === window.innerWidth) {
                    return;
                }
                lastWidth = window.innerWidth;

                resizeElements(Array.prototype.slice.call(document.querySelectorAll('pre.' + PLUGIN_NAME)));
            });

            Prism.hooks.add('complete', function (env) {
                if (!env.code) {
                    return;
                }

                var code = /** @type {Element} */ (env.element);
                var pre = /** @type {HTMLElement} */ (code.parentNode);

                // works only for <code> wrapped inside <pre> (not inline)
                if (!pre || !/pre/i.test(pre.nodeName)) {
                    return;
                }

                // Abort if line numbers already exists
                if (code.querySelector('.line-numbers-rows')) {
                    return;
                }

                // only add line numbers if <code> or one of its ancestors has the `line-numbers` class
                if (!Prism.util.isActive(code, PLUGIN_NAME)) {
                    return;
                }

                // Remove the class 'line-numbers' from the <code>
                code.classList.remove(PLUGIN_NAME);
                // Add the class 'line-numbers' to the <pre>
                pre.classList.add(PLUGIN_NAME);

                var match = env.code.match(NEW_LINE_EXP);
                var linesNum = match ? match.length + 1 : 1;
                var lineNumbersWrapper;
                var lines = new Array(linesNum)
                let newLines = ''
                for (let index = 0; index < lines.length; index++) {
                    newLines += `<span></span>`
                }
                lineNumbersWrapper = document.createElement('span');
                lineNumbersWrapper.setAttribute('aria-hidden', 'true');
                lineNumbersWrapper.className = 'line-numbers-rows';
                lineNumbersWrapper.innerHTML = newLines;

                if (pre.hasAttribute('data-start')) {
                    pre.style.counterReset = 'linenumber ' + (parseInt(pre.getAttribute('data-start'), 10) - 1);
                }

                env.element.appendChild(lineNumbersWrapper);

                resizeElements([pre]);

                Prism.hooks.run('line-numbers', env);
            });

            Prism.hooks.add('line-numbers', function (env) {
                env.plugins = env.plugins || {};
                env.plugins.lineNumbers = true;
            });

        }());



        (function () {

            if (typeof Prism === 'undefined' || typeof document === 'undefined' || !document.querySelector) {
                return;
            }

            var LINE_NUMBERS_CLASS = 'line-numbers';
            var LINKABLE_LINE_NUMBERS_CLASS = 'linkable-line-numbers';
            var NEW_LINE_EXP = /\n(?!${'$'})/g;

            /**
             * @param {string} selector
             * @param {ParentNode} [container]
             * @returns {HTMLElement[]}
             */
            function ${'$'}${'$'}(selector, container) {
                return Array.prototype.slice.call((container || document).querySelectorAll(selector));
            }

            /**
             * Returns whether the given element has the given class.
             *
             * @param {Element} element
             * @param {string} className
             * @returns {boolean}
             */
            function hasClass(element, className) {
                return element.classList.contains(className);
            }

            /**
             * Calls the given function.
             *
             * @param {() => any} func
             * @returns {void}
             */
            function callFunction(func) {
                func();
            }

            // Some browsers round the line-height, others don't.
            // We need to test for it to position the elements properly.
            var isLineHeightRounded = (function () {
                var res;
                return function () {
                    if (typeof res === 'undefined') {
                        var d = document.createElement('div');
                        d.style.fontSize = '13px';
                        d.style.lineHeight = '1.5';
                        d.style.padding = '0';
                        d.style.border = '0';
                        d.innerHTML = '&nbsp;<br />&nbsp;';
                        document.body.appendChild(d);
                        // Browsers that round the line-height should have offsetHeight === 38
                        // The others should have 39.
                        res = d.offsetHeight === 38;
                        document.body.removeChild(d);
                    }
                    return res;
                };
            }());

            /**
             * Returns the top offset of the content box of the given parent and the content box of one of its children.
             *
             * @param {HTMLElement} parent
             * @param {HTMLElement} child
             */
            function getContentBoxTopOffset(parent, child) {
                var parentStyle = getComputedStyle(parent);
                var childStyle = getComputedStyle(child);

                /**
                 * Returns the numeric value of the given pixel value.
                 *
                 * @param {string} px
                 */
                function pxToNumber(px) {
                    return +px.substr(0, px.length - 2);
                }

                return child.offsetTop
                    + pxToNumber(childStyle.borderTopWidth)
                    + pxToNumber(childStyle.paddingTop)
                    - pxToNumber(parentStyle.paddingTop);
            }

            /**
             * Returns whether the Line Highlight plugin is active for the given element.
             *
             * If this function returns `false`, do not call `highlightLines` for the given element.
             *
             * @param {HTMLElement | null | undefined} pre
             * @returns {boolean}
             */
            function isActiveFor(pre) {
                if (!pre || !/pre/i.test(pre.nodeName)) {
                    return false;
                }

                if (pre.hasAttribute('data-line')) {
                    return true;
                }

                if (pre.id && Prism.util.isActive(pre, LINKABLE_LINE_NUMBERS_CLASS)) {
                    // Technically, the line numbers plugin is also necessary but this plugin doesn't control the classes of
                    // the line numbers plugin, so we can't assume that they are present.
                    return true;
                }

                return false;
            }

            var scrollIntoView = true;

            Prism.plugins.lineHighlight = {
                /**
                 * Highlights the lines of the given pre.
                 *
                 * This function is split into a DOM measuring and mutate phase to improve performance.
                 * The returned function mutates the DOM when called.
                 *
                 * @param {HTMLElement} pre
                 * @param {string | null} [lines]
                 * @param {string} [classes='']
                 * @returns {() => void}
                 */
                highlightLines: function highlightLines(pre, lines, classes) {
                    lines = typeof lines === 'string' ? lines : (pre.getAttribute('data-line') || '');

                    var ranges = lines.replace(/\s+/g, '').split(',').filter(Boolean);
                    var offset = +pre.getAttribute('data-line-offset') || 0;

                    var parseMethod = isLineHeightRounded() ? parseInt : parseFloat;
                    var lineHeight = parseMethod(getComputedStyle(pre).lineHeight);
                    var hasLineNumbers = Prism.util.isActive(pre, LINE_NUMBERS_CLASS);
                    var codeElement = pre.querySelector('code');
                    var parentElement = hasLineNumbers ? pre : codeElement || pre;
                    var mutateActions = /** @type {(() => void)[]} */ ([]);
                    var lineBreakMatch = codeElement.textContent.match(NEW_LINE_EXP);
                    var numberOfLines = lineBreakMatch ? lineBreakMatch.length + 1 : 1;
                    /**
                     * The top offset between the content box of the <code> element and the content box of the parent element of
                     * the line highlight element (either `<pre>` or `<code>`).
                     *
                     * This offset might not be zero for some themes where the <code> element has a top margin. Some plugins
                     * (or users) might also add element above the <code> element. Because the line highlight is aligned relative
                     * to the <pre> element, we have to take this into account.
                     *
                     * This offset will be 0 if the parent element of the line highlight element is the `<code>` element.
                     */
                    var codePreOffset = !codeElement || parentElement == codeElement ? 0 : getContentBoxTopOffset(pre, codeElement);

                    ranges.forEach(function (currentRange) {
                        var range = currentRange.split('-');

                        var start = +range[0];
                        var end = +range[1] || start;
                        end = Math.min(numberOfLines + offset, end);

                        if (end < start) {
                            return;
                        }

                        /** @type {HTMLElement} */
                        var line = pre.querySelector('.line-highlight[data-range="' + currentRange + '"]') || document.createElement('a');

                        // ***kotlin*** renders the buttons on the right js renders the higlight links with the ids
                        line.href = '#commands-line-'+currentRange+'-'+pre.id
                        line.id = 'code-line-'+currentRange+'-'+pre.id
                        mutateActions.push(function () {
                            line.setAttribute('aria-hidden', 'true');
                            line.setAttribute('data-range', currentRange);
                            line.className = (classes || '') + ' line-highlight';
                        });

                        // if the line-numbers plugin is enabled, then there is no reason for this plugin to display the line numbers
                        if (hasLineNumbers && Prism.plugins.lineNumbers) {
                            var startNode = Prism.plugins.lineNumbers.getLine(pre, start);
                            var endNode = Prism.plugins.lineNumbers.getLine(pre, end);

                            if (startNode) {
                                var top = startNode.offsetTop + codePreOffset + 'px';
                                mutateActions.push(function () {
                                    line.style.top = top;
                                });
                            }

                            if (endNode) {
                                var height = (endNode.offsetTop - startNode.offsetTop) + endNode.offsetHeight + 'px';
                                mutateActions.push(function () {
                                    line.style.height = height;
                                });
                            }
                        } else {
                            mutateActions.push(function () {
                                line.setAttribute('data-start', String(start));

                                if (end > start) {
                                    line.setAttribute('data-end', String(end));
                                }

                                line.style.top = (start - offset - 1) * lineHeight + codePreOffset + 'px';

                                line.textContent = new Array(end - start + 2).join(' \n');
                            });
                        }

                        mutateActions.push(function () {
                            line.style.width = pre.scrollWidth + 'px';
                        });

                        mutateActions.push(function () {
                            // allow this to play nicely with the line-numbers plugin
                            // need to attack to pre as when line-numbers is enabled, the code tag is relatively which screws up the positioning
                            parentElement.appendChild(line);
                            // we want the link (the "line") on top, however, we also want a background color below the code text
                            // hence, we make a copy of the "line", position is lower, and give it the background color (css part)
                            let lineBackground = document.createElement("span");
                            lineBackground.id = line.id + '-bg'
                            lineBackground.className = line.className + '-bg'
                            lineBackground.style.width = line.style.width
                            lineBackground.style.height = line.style.height
                            lineBackground.style.top = line.style.top
                            line.after(lineBackground)
                        });
                    });

                    var id = pre.id;
                    if (hasLineNumbers && Prism.util.isActive(pre, LINKABLE_LINE_NUMBERS_CLASS) && id) {
                        // This implements linkable line numbers. Linkable line numbers use Line Highlight to create a link to a
                        // specific line. For this to work, the pre element has to:
                        //  1) have line numbers,
                        //  2) have the `linkable-line-numbers` class or an ascendant that has that class, and
                        //  3) have an id.

                        if (!hasClass(pre, LINKABLE_LINE_NUMBERS_CLASS)) {
                            // add class to pre
                            mutateActions.push(function () {
                                pre.classList.add(LINKABLE_LINE_NUMBERS_CLASS);
                            });
                        }

                        var start = parseInt(pre.getAttribute('data-start') || '1');

                        // iterate all line number spans
                        ${'$'}${'$'}('.line-numbers-rows > span', pre).forEach(function (lineSpan, i) {
                            var lineNumber = i + start;
                            lineSpan.onclick = function () {
                                var hash = id + '.' + lineNumber;

                                // this will prevent scrolling since the span is obviously in view
                                scrollIntoView = false;
                                location.hash = hash;
                                setTimeout(function () {
                                    scrollIntoView = true;
                                }, 1);
                            };
                        });
                    }

                    return function () {
                        mutateActions.forEach(callFunction);
                    };
                }
            };


            function applyHash() {
                var hash = location.hash.slice(1);

                // Remove pre-existing temporary lines
                ${'$'}${'$'}('.temporary.line-highlight').forEach(function (line) {
                    line.parentNode.removeChild(line);
                });

                var range = (hash.match(/\.([\d,-]+)${'$'}/) || [, ''])[1];

                if (!range || document.getElementById(hash)) {
                    return;
                }

                var id = hash.slice(0, hash.lastIndexOf('.'));
                var pre = document.getElementById(id);

                if (!pre) {
                    return;
                }

                if (!pre.hasAttribute('data-line')) {
                    pre.setAttribute('data-line', '');
                }

                var mutateDom = Prism.plugins.lineHighlight.highlightLines(pre, range, 'temporary ');
                mutateDom();

                if (scrollIntoView) {
                    document.querySelector('.temporary.line-highlight').scrollIntoView();
                }
            }

            var fakeTimer = 0; // Needed to limit the number of times applyHash() runs

            Prism.hooks.add('before-sanity-check', function (env) {
                var pre = env.element.parentElement;
                if (!isActiveFor(pre)) {
                    return;
                }

                /*
                 * Cleanup for other plugins (e.g. autoloader).
                 *
                 * Sometimes <code> blocks are highlighted multiple times. It is necessary
                 * to cleanup any left-over tags, because the whitespace inside of the <div>
                 * tags change the content of the <code> tag.
                 */
                var num = 0;
                ${'$'}${'$'}('.line-highlight', pre).forEach(function (line) {
                    num += line.textContent.length;
                    line.parentNode.removeChild(line);
                });
                // Remove extra whitespace
                if (num && /^(?: \n)+${'$'}/.test(env.code.slice(-num))) {
                    env.code = env.code.slice(0, -num);
                }
            });

            Prism.hooks.add('complete', function completeHook(env) {
                var pre = env.element.parentElement;
                if (!isActiveFor(pre)) {
                    return;
                }

                clearTimeout(fakeTimer);

                var hasLineNumbers = Prism.plugins.lineNumbers;
                var isLineNumbersLoaded = env.plugins && env.plugins.lineNumbers;

                if (hasClass(pre, LINE_NUMBERS_CLASS) && hasLineNumbers && !isLineNumbersLoaded) {
                    Prism.hooks.add('line-numbers', completeHook);
                } else {
                    var mutateDom = Prism.plugins.lineHighlight.highlightLines(pre);
                    mutateDom();
                    fakeTimer = setTimeout(applyHash, 1);
                }
            });

            window.addEventListener('hashchange', applyHash);
            window.addEventListener('resize', function () {
                var actions = ${'$'}${'$'}('pre')
                    .filter(isActiveFor)
                    .map(function (pre) {
                        return Prism.plugins.lineHighlight.highlightLines(pre);
                    });
                actions.forEach(callFunction);
            });

        }());

    </script>
        """

        private const val head = """
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <title>Unsat Core Visualisation</title>
        """

        private const val cssStyles = """
           <style>
    html {
  scroll-behavior: smooth;
}
    body{

    font-family: "Consolas", "Bitstream Vera Sans Mono", "Courier New", Courier, monospace;
        -webkit-font-smoothing: antialiased;
    }
    /* PrismJS 1.29.0
https://prismjs.com/download.html#themes=prism-coy */
    code[class*=language-],
    pre[class*=language-] {
        color: #000;
        background: 0 0;
        font-family: "Consolas", "Bitstream Vera Sans Mono", "Courier New", Courier, monospace;
        font-size: 1em;
        text-align: left;
        white-space: pre-wrap;
        word-spacing: normal;
        word-break: normal;
        word-wrap: normal;
        line-height: 1.5;
        -moz-tab-size: 4;
        -o-tab-size: 4;
        tab-size: 4;
        -webkit-hyphens: none;
        -moz-hyphens: none;
        -ms-hyphens: none;
        hyphens: none
    }

    pre[class*=language-] {

        position: relative;
        margin: 0;
        overflow: visible;
        padding: 0;
    }

    pre[class*=language-]>code {
        position: relative;
        z-index: 2;
        /* // box-shadow: 0 0 0 1px #dfdfdf; */
        border: 1px solid #dfdfdf;
        border-radius: 4px;
        background-color: none;
        background-image: linear-gradient(transparent 50%, rgba(69, 142, 209, .04) 50%);
        background-size: 3em 3em;
        background-origin: content-box;
        background-attachment: local;
        padding: 8px 32px 8px 4em;
        /* // fix for the extra height */
        margin-bottom: -20px;
        margin-top: -20px;
    }

    code[class*=language-] {
        max-height: inherit;
        height: inherit;
        padding: 0 1em;
        display: block;
        overflow: auto
    }

    :not(pre)>code[class*=language-],
    pre[class*=language-] {
        background-color: none;
        -webkit-box-sizing: border-box;
        -moz-box-sizing: border-box;
        box-sizing: border-box;
    }

    :not(pre)>code[class*=language-] {
        position: relative;
        padding: .2em;
        border-radius: 4px !important;
        color: #c92c2c;
        border: 1px solid rgba(0, 0, 0, .1);
        display: inline;
        white-space: normal
    }

    .token.block-comment,
    .token.cdata,
    .token.comment,
    .token.doctype,
    .token.prolog {
        color: #7d8b99
    }

    .token.punctuation {
        color: #5f6364
    }

    .token.boolean,
    .token.constant,
    .token.deleted,
    .token.function-name,
    .token.number,
    .token.property,
    .token.symbol,
    .token.tag {
        color: #c92c2c
    }

    .token.attr-name,
    .token.builtin,
    .token.char,
    .token.function,
    .token.inserted,
    .token.selector,
    .token.string {
        color: #004bff
    }

    .token.entity,
    .token.operator,
    .token.url,
    .token.variable {
        color: #3f51b5;
        background: rgba(255, 255, 255, .5)
    }

    .token.atrule,
    .token.attr-value,
    .token.class-name,
    .token.keyword {
        color: #3f51b5
    }

    .token.important,
    .token.regex {
        color: #e90
    }

    .language-css .token.string,
    .style .token.string {
        color: #a67f59;
        background: rgba(255, 255, 255, .5)
    }

    .token.important {
        font-weight: 400
    }

    .token.bold {
        font-weight: 700
    }

    .token.italic {
        font-style: italic
    }

    .token.entity {
        cursor: help
    }

    .token.namespace {
        opacity: .7
    }



    pre[class*=language-].line-numbers.line-numbers {
        padding-left: 0
    }

    pre[class*=language-].line-numbers.line-numbers .line-numbers-rows {
        left: 0
    }

    pre[class*=language-][data-line] {
        padding-top: 0;
        padding-bottom: 0;
        padding-left: 0
    }

    pre[data-line] code {
        position: relative;
        padding-left: 4em
    }

    pre .line-highlight {
        margin-top: 0
    }

    /* line numbers */
    pre.line-numbers {
        position: relative;
        padding-left: 3.8em;
        counter-reset: linenumber;
    }

    pre.line-numbers>code {
        position: relative;
    }

    .line-numbers .line-numbers-rows {
        position: absolute;
        pointer-events: none;
        top: 0;
        font-size: 100%;
        left: -3.8em;
        width: 3em;
        /* works for line-numbers below 1000 lines */
        letter-spacing: -1px;
        border-right: 1px solid #999;

        -webkit-user-select: none;
        -moz-user-select: none;
        -ms-user-select: none;
        user-select: none;

    }

    .line-numbers-rows>span {
        pointer-events: none;
        display: block;
        counter-increment: linenumber;
    }

    .line-numbers-rows>span:before {
        content: counter(linenumber);
        color: #999;
        display: block;
        padding-right: 0.8em;
        text-align: right;
    }

    a.line-highlight {
        position: absolute;
        z-index: 2;
        opacity: 0;
        max-width: 100%;
    }

    .line-highlight-bg {
        position: absolute;
        z-index: 1;
        background-color: #a9d4a9;
        max-width: 100%;
    }

    span.line-numbers-rows {
        margin-top: 8px;
    }


    /* pane syles */
    .commands-wrapper h4, .commands-wrapper h3 {
        margin: 0;
        margin-bottom: 8px;
    }
    .commands-wrapper h3{
        font-weight: normal;
        font-size: 18px;
        width: fit-content;
    }
    .commands-wrapper h4{
        display: flex;
        align-items: center;
    }
    .used-commands,
    .unused-commands {
        background-color: rgb(176 251 174);
        padding: 2px 4px;
        border-radius: 5px;
    }

    .unused-commands {
        background-color: rgba(255, 0, 0, 0.2);
    }

    ul {
        padding-inline-start: 16px;
    }

    .commands-wrapper {
        padding-bottom: 16px;
        margin-bottom: 16px;
        border-bottom: 1px solid rgba(0, 0, 255, 0.2);
        ;
    }
    .commands-wrapper:target {
        background-color: #ff98001a;
    }
    .rule-list-ruleName-cmds{
        margin-top: 0;
    }
    .used-commands-wrapper{
        border-left: 5px solid rgb(176 251 174);
        padding-left: 8px;
    }
    .unused-commands-wrapper{
        border-left: 5px solid rgba(255, 0, 0, 0.2);
        padding-left: 8px;
    }
    .commands-wrapper button{
        border-color: rgba(0, 0, 255, 0.2);
        background: transparent;
        border-radius: 5px;
        display: inline-flex;
        padding: 4px 8px;
        transition: all ease-in-out 0.2s;
    }
    .commands-wrapper button:hover{
        cursor: pointer;
        background: rgba(0, 0, 255, 0.2);
    }


    /* layout style */

    #visualisation-pane {
    overflow: scroll;
    position: fixed;
    height: 95%;
    width: 55%;
    display: inline-block;
    scroll-behavior: smooth;
}

#visualisation-pane-inner {
    padding: 15px;
    padding-right: 0px;
    margin-right: 20px;
    text-align: justify;
    /* font-family: 'Assistant', sans-serif; */
}

#rule-mapping-pane {
    overflow-y: scroll;
    position: absolute;
    left: 55%;
    top: 0;
    display: block;
    width: 45%;
    height: 95%;
    scroll-behavior: smooth;
}

#rule-mapping-pane-inner {
    padding: 15px;
    padding-left: 30px;
}
</style>
"""
    }
}
