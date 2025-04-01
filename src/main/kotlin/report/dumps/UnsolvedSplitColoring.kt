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
import config.LocalSettings
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.*
import verifier.TimeoutCoreAnalysis
import java.lang.UnsupportedOperationException


/** Contains all the information about unsolved splits that might be needed to generate a graphical dump for
 * illustrating them. */
data class UnsolvedSplitInfo(
    val fullTimeoutBlocks: Set<NBId>,
    val mediumTimeoutBlocks: Set<NBId>,
    val tacProgram: CoreTACProgram,
    val timeoutCoreInfo: TimeoutCoreAnalysis.TimeoutCoreInfo?
)

/**
 * Given a program [tacProgram], and a set of blocks that are part of unsolved subprograms (i.e. splits that timed out),
 * this will return a CodeMap of [tacProgram] where all the nodes that lie on some unsolved split are colored orange and
 * the others green.
 *
 * More intuitively, the green color means "this node was successfully proven correct", while orange means we timed out
 * when attempting to prove that node correct.
 * A node (program block) being "correct" means that there is no violating execution of [tacProgram] that passes
 * through it.
 * (Note that I chose orange rather than red, because the "unsolved" information is less definite than the "solved"
 *  one -- a node that is orange might be hard to solve, but it might also just lie "on the way" to such a node.)
 */
fun generateUnsolvedSplitCodeMap(unsolvedSplitInfo: UnsolvedSplitInfo,
                                 localSettings: LocalSettings = LocalSettings()): CodeMap {
    val (fullTimeoutBlocks1, notYetProcessedSplitBlocks1, tacProgram, timeoutCoreInfo) = unsolvedSplitInfo

    val name = tacProgram.name

    // this _should_ not be too expensive, but we could measure it in time
    val codeMap = generateCodeMap(tacProgram, name)

    // unsolvedSplitInfo is based on the program without the extra decomposition for internal functions
    //  we need to apply the block mapping accordingly
    val fullTimeoutBlocks =
        codeMap.translateWithInternalFuncs(fullTimeoutBlocks1)

    val notYetProcessedSplitBlocks =
        codeMap.translateWithInternalFuncs(notYetProcessedSplitBlocks1)

    if (timeoutCoreInfo != null && codeMap.withInternalFunctions != tacProgram) {
        throw UnsupportedOperationException("timeout core in conjunction with having internal functions in the " +
            "codemap is not working, yet, need to implement a more detailed `internalFunctionsBlockMapping` for that.")
    }

    /** depends on the naming scheme [callNodeLabel] used within [decomposeCodeToCalls]
     * (copied from CodeMap.addUnsatCoreData()) **/
    val callIdFullNames = codeMap.callIdFullNames

    val justFilled = listOf(DotStyle.filled)
    val filledDashed = listOf(DotStyle.filled, DotStyle.dashed)

    val fullTimeout = { n: DotNode -> n.id.originalNodeId in fullTimeoutBlocks }
    val notYetProcessed = { n: DotNode -> n.id.originalNodeId in notYetProcessedSplitBlocks }
    val inTimeoutCore = { n: DotNode -> timeoutCoreInfo?.timeoutCoreBlocks?.contains(n.originalNodeId) == true }

    val countDifficultOps = (codeMap.withInternalFunctions as? CoreTACProgram)
        ?.let { wif -> CountDifficultOps(wif) }
        ?: error("expected a CoreTACProgram, got ${codeMap.withInternalFunctions} (aborting difficulty computation)")
    val heuristicallyDifficult = { n: DotNode -> countDifficultOps[n.id.originalNodeId]?.isDifficult() == true }

    // note: we'd love to add tooltips here, for a "hover to see the stats" feature, but when using them together with
    //  node "clickability", dot produces nested `<a ...>` html tags, which chrome does not tolerate (the text disappears)

    fun colorDot(dot: DotDigraph): DotDigraph {
        return dot.copy(
            nodes = dot.nodes.map { n ->

                val (color, styles) = if (n.id.id in callIdFullNames) {
                    val callId = callIdFullNames[n.id.id]

                    // using `filledDashed` style here to indicate those special call nodes (maybe we could have a
                    // global convention there?)

                    val color = if (callId == null) {
                        DotColorList(CodeMap.errorColor)
                    } else {
                        codeMap.callNodeTimeoutColor(
                            callId = callId,
                            heuristicAnalysis = countDifficultOps,
                            inTimeoutCore = inTimeoutCore,
                            inFullTimeoutSplit = fullTimeout,
                            inNotYetProcessedSplit = notYetProcessed,
                        )
                    }
                    Pair(color, filledDashed)
                } else if (inTimeoutCore(n) && heuristicallyDifficult(n)) {
                    Pair(
                        CodeMap.inTimeoutCoreAndHeuristicallyDifficultColor,
                        justFilled,
                    )
                } else if (inTimeoutCore(n) && !heuristicallyDifficult(n)) {
                    Pair(CodeMap.inTimeoutCoreAndNotHeuristicallyDifficultColor, justFilled)
                } else if (fullTimeout(n) && heuristicallyDifficult(n)) {
                    Pair(
                        CodeMap.heuristicallyDifficultNotInTimeoutCoreColor,
                        justFilled,
                    )
                } else if (fullTimeout(n)) {
                    Pair(CodeMap.inFullTimeoutSplitColor, justFilled)
                } else if (notYetProcessed(n)) {
                    Pair(CodeMap.notYetProcessedSplitColor, justFilled)
                } else {
                    Pair(CodeMap.provenUnsatColor, justFilled)
                }
                n.copy(fillcolor = color, styles = styles)
            }
        )
    }

    return codeMap.copy(
        name = name,
        dotMain = colorDot(codeMap.dotMain).copy(name = "UnsolvedSplits" + codeMap.dotMain.name),
        subDots = codeMap.subDots.mapValues { colorDot(it.value).copy(name = "UnsolvedSplits" + it.value.name) },
        countDifficultOps = countDifficultOps,
        timeoutCoreInfo = timeoutCoreInfo,
        colorExplanation = mapOf(
            // reactivate when timeout cores work
            // CodeMap.inTimeoutCoreAndNotHeuristicallyDifficultColor to "is difficult according to the solver (lies in timeout core)",
            CodeMap.inFullTimeoutSplitColor to "lies in a split that timed out with the full timeout (${localSettings.solverTimeout} sec)",
            CodeMap.notYetProcessedSplitColor to "lies in a split that has not been processed on the deepest split " +
                "level; a shallower split timed out with the medium timeout (${Config.MediumSplitTimeout.get()} sec)",
            CodeMap.provenUnsatColor to "already proven correct in this run",
            DotColorList(DotColor.lightgray, CodeMap.heuristicallyDifficultColor) to "is heuristically difficult (combines with the other colors)"
        )
    )
}

