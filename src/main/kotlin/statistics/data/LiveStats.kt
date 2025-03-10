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

package statistics.data

import config.Config
import datastructures.stdcollections.*
import report.ConcreteLiveStatsReporter
import report.LiveCheckInfoNode
import report.dumps.CallGraphInfo
import statistics.data.SingleDifficultyStats.TacProgramStats.Companion.toTreeView
import utils.*
import verifier.RuleAndSplitIdentifier
import verifier.splits.SplitAddress
import java.util.concurrent.atomic.AtomicInteger

enum class TopLevelLiveStats {
    globalPathCount,
    globalNonlinearity,
    globalLoops,
    pathCountHotspots,
    nonlinearityHotspots,
    perCallStats,
    ;
}

interface LiveStats {
    val ruleAndSplitId: RuleAndSplitIdentifier

    /** A chronological identifier for all [LiveStats] so we can always be certain we're displaying the most recent
     * one. */
    val compareId: Int
    companion object {
        val compareCounter = AtomicInteger()

        const val highPathCountLink =
            "https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html#dealing-with-a-high-path-count"
        const val highNonlinearOpsLink =
            "https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html#high-nonlinear-op-count"
        const val highMemComplexityLink =
            "https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html#high-memory-complexity"

        /**
         * Transforms some stats as we collected them into an entry in the TreeView-jsons that will then be displayed
         * in the "live updates" section in the html report.
         *
         * Two sections, currently:
         *  - Global info
         *  - Per-external-call info
         *
         *  upcoming:
         *   - progress info (Attempted x splits covering y % of program or so)
         *   - path count hotspots
         *   - nonlinearity hotspots
         *
         *  [callGraphInfo] is used for the per-call stats only, so the others should work without, in a pinch.
         */
        fun List<LiveStats>.toTreeViewFormat(
            updateCounter: Int,
            callGraphInfo: CallGraphInfo?,
        ): List<LiveCheckInfoNode> {
            // ignore stats pertaining to splits (for now)
            val noSplits = this.filter { it.ruleAndSplitId.splitAddress == SplitAddress.Root }

            val progressInfo = noSplits.filterIsInstance<LiveStatsProgressInfo>().maxByOrNull { it.compareId }
            val tacProgramStats = noSplits.filterIsInstance<SingleDifficultyStats.TacProgramStats>()
            val lExpToSmtLibstats = noSplits.filterIsInstance<SingleDifficultyStats.LExpToSmtLibstats>()
            val arrayGeneratorStats =
                lExpToSmtLibstats.mapNotNull { it.arrayGeneratorStats }.emptyOrSingleOrPickFirst()

            val progStats = tacProgramStats.toTreeView(callGraphInfo = callGraphInfo)

            // this list looks a bit odd, but fwiw the visible order of the live stats nodes is defined here, so I'm
            // accepting for now that it looks a bit pedestrian ...
            val nodes = listOfNotNull(
                if (ConcreteLiveStatsReporter.DISPLAY_UPDATE_COUNTER) {
                    LiveCheckInfoNode.keyValue(
                        label = "updateCounter",
                        value = updateCounter.toString()
                    )
                } else {
                    null
                },
                progressInfo?.toTreeViewFormat(),
                progStats[TopLevelLiveStats.globalPathCount],
                progStats[TopLevelLiveStats.globalNonlinearity],
                arrayGeneratorStats?.toTreeViewFormat(),
                progStats[TopLevelLiveStats.globalLoops],
                progStats[TopLevelLiveStats.pathCountHotspots],
                progStats[TopLevelLiveStats.nonlinearityHotspots],
                progStats[TopLevelLiveStats.perCallStats],
            )

            nodes.annotateUIds(0)

            return nodes
        }
    }
}

@KSerializable
sealed interface LiveStatsProgressInfo : LiveStats {

    /** For usage in the rules tab (as opposed to the live stats tab where similar info is present) */
    val splitProgressPercentage: Int?

    fun Double.toPercent() = (this * 100).toInt()

    companion object {
        private const val prefix = "verification phase:"
    }

    fun toTreeViewFormat(): LiveCheckInfoNode

    data class StaticAnalysis(
        override val ruleAndSplitId: RuleAndSplitIdentifier,
    ) : LiveStatsProgressInfo {

        override val compareId: Int = LiveStats.compareCounter.incrementAndGet()

        override fun toTreeViewFormat(): LiveCheckInfoNode =
            LiveCheckInfoNode.onlyLabel(
                label = "$prefix static analysis"
            )

        override val splitProgressPercentage: Int
            get() = 0 // we're showing 0 when the rule is "RUNNING" yet splitting hasn't started
    }

    fun splitProgressString( numProblemsAttempted: Int, totalWeight: Double) =
        "attempted $numProblemsAttempted splits, proved a total weighing ${totalWeight.toPercent()} %"

    data class Finished(
        override val ruleAndSplitId: RuleAndSplitIdentifier,
        val numProblemsAttempted: Int,
        val totalWeight: Double,
        val isTimeout: Boolean,
    ) : LiveStatsProgressInfo {

        override val compareId: Int = LiveStats.compareCounter.incrementAndGet()

        // showing nothing when the rule has finished running (or a placeholder, that's up to UI), unless it's a timeout
        // in that case  we show "how far we got" via the percentage
        override val splitProgressPercentage: Int?
        get() = if (isTimeout) { totalWeight.toPercent() } else { null }

        override fun toTreeViewFormat(): LiveCheckInfoNode =
            LiveCheckInfoNode.onlyLabel(
                label = "$prefix finished checking rule (${splitProgressString(numProblemsAttempted, totalWeight)})"
            )
    }

    data class SplittingInProgress(
        override val ruleAndSplitId: RuleAndSplitIdentifier,
        val numProblemsAttempted: Int,
        val totalWeight: Double,
        val reachedSplitDepth: Int,
    ) : LiveStatsProgressInfo {

        override val compareId: Int = LiveStats.compareCounter.incrementAndGet()

        override val splitProgressPercentage: Int
            get() = totalWeight.toPercent()

        override fun toTreeViewFormat(): LiveCheckInfoNode =
            LiveCheckInfoNode.onlyLabel(
                label = listOf(
                    prefix,
                    "splits attempted (solved or split again): $numProblemsAttempted",
                    "total weight of splits proved: $splitProgressPercentage %",
                    "splitting depth reached: $reachedSplitDepth",
                    "configured max. splitting depth: ${Config.Smt.SplittingDepth}"
                ).joinToString(separator = " \n ")
            )
    }
}

/**
 * set the uIds in liveCheckInformation according to preorder, starting with the [ctrIn] (inclusive).
 *
 * The visualization in our reports relies on every node to have a unique identifier.
 * This is used e.g. for the tracking points there.
 *
 * (For that reason, these uIds need to be ascending with respect to the preorder of the
 * tree of [LiveCheckInfoNode]s (as defined by [children]).
 * These only need to be unique for each single-rule, not across the whole `treeViewX.json`.)
 * [uId] needs to be set before [LiveCheckInfoNode.treeViewRepBuilder] is invoked
 * for the first time, i.e., before things are dumped to the `treeViewX.json` file.
 */
private fun List<LiveCheckInfoNode>.annotateUIds(ctrIn: Int): Int {
    var ctr = ctrIn
    this.forEach {
        it.uId = ctr++
        ctr = it.children.annotateUIds(ctr)
    }
    return ctr
}
