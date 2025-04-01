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

@file:UseSerializers(BigIntegerSerializer::class)

package statistics.data

import algorithms.getReachable
import analysis.controlflow.PathCounter
import com.certora.collect.*
import config.Config
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import log.*
import report.LiveStatsReporter
import report.TreeViewReporter
import report.dumps.CodeMap
import spec.cvlast.RuleIdentifier
import statistics.SDCollector
import statistics.recordAny
import tac.CallId
import tac.NBId.Companion.ROOT_CALL_ID
import utils.*
import vc.data.CoreTACProgram
import vc.data.ProcedureId
import verifier.CodeAnalysis
import verifier.NameObject
import verifier.TACVerifier
import verifier.splits.SplitAddress
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.ConcurrentSkipListSet

private val logger = Logger(LoggerTypes.SMT_TIMEOUT)

/**
 * Created once per rule (i.e. once per [TACVerifier] instance with a `non-null` rule field).
 * Provides methods to register difficulty information. The information is emitted both via the [SDCollector] and
 * [TreeViewReporter] frameworks.
 *
 * Whenever [register] is called, the corresponding data is also reported to [TreeViewReporter] (via
 * [LiveStatsReporter]).
 *
 * When [close] is called, the data collected here is reported to [SDCollector].
 *
 * Note that only persistent difficulty statistics are registered here. For instance progress info that is to be shown
 * in the "Live Statistics" tab (via [TreeViewReporter]), but makes no sense to have in `statsdata.json` (via
 * [SDCollector]) is not registered here, but only in [LiveStatsReporter].
 *
 * @param name identifies the "rule" that the stats are being registered for; we currently have one
 *      instance of this class per rule
 * @param ruleId used as an identifier to register information in tree-view reporter -- would be happy to replace it some
 *      time with a leaner identifier ...
 * @param collector this collector will automatically register the stats here so they end up in `Reports/statsdata.json`
 * @param liveStatsReporter this collector will automatically register the stats with the [LiveStatsReporter], which is
 *          then accessed by [TreeViewReporter] so the html report is updated accordingly.
 *
 */
class DifficultyStatsCollector(
    val name: NameObject,
    val ruleId: RuleIdentifier,
    val codeMap: CodeMap,
    val collector: SDCollector,
    val liveStatsReporter: LiveStatsReporter,
) : Closeable {
    // not sure here yet, but seems like LExpressionToSmtLibInstances that run in parallel might enter things here ..
    private val ruleLevelStats = ConcurrentSkipListSet<SingleDifficultyStats>()
    private val splitLevelStats = ConcurrentSkipListSet<SingleDifficultyStats>()

    private val callGraphInfo get() = codeMap.callGraphInfo

    fun register(stats: SingleDifficultyStats) {
        liveStatsReporter.reportDifficulty(ruleId, callGraphInfo, stats)

        when (stats) {
            is SingleDifficultyStats.TacProgramStats -> when (stats.ruleAndSplitId.splitAddress) {
                SplitAddress.Root -> {
                    logger.debug { "Registering difficulty stats for rule ${name.baseName}:\n" +
                        stats.statsAsText
                    }
                    ruleLevelStats.add(stats)
                }
                else -> {
                    logger.debug {
                        "Registering difficulty stats for split ${stats.ruleAndSplitId.splitAddress}, of " +
                            "rule ${name.baseName}:\n" + stats.statsAsText
                    }
                    splitLevelStats.add(stats)
                }
            }
            is SingleDifficultyStats.LExpToSmtLibstats -> {
                logger.debug {
                    "Registering LExpToSmtLib difficulty stats for rule ${name.baseName}, " +
                        "split ${stats.ruleAndSplitId.splitAddress}:\n" + stats.statsAsText
                }
                splitLevelStats.add(stats)
            }
        }
    }

    override fun close() {
        collector.recordAny(
            PerRuleDiffStatsJavaSerializerWrapper(
                PerRuleDiffStats(
                    name,
                    ruleLevelStats.toList(),
                    splitLevelStats.toList()
                )
            ),
            tag,
            key
        )
    }

   companion object {
       const val tag = "DifficultyHeuristicsTag"
       const val key = "DifficultyHeuristicsKey"
   }
}

/**
 * @param callIdToPathCountData pairs of call ids and the corresponding path count data (not a map due to json
 *       serialization)
 * @param procIdToDifficultyScore for each procedure P, this describes how much the path count would be reduced if P's
 *   control flow would be minimal (just enough to do all the calls it does, 1 if there are no calls), on an exponential
 *   scale as a percentage
 */
@Serializable
data class PathCountStats(
    val callIdToPathCountData: List<Pair<CallIdWithName, PathCountData>>,
    val procIdToDifficultyScore: Map<String, Int>,
) : PrettyPrintableStats {
    val rootEntry = callIdToPathCountData.find { it.first.callId == ROOT_CALL_ID }

    val callIdToPathCountDataMap by lazy { callIdToPathCountData.toMap() }

    @Serializable
    data class PathCountData(val localPathCount: BigInteger?, val inlinedPathCount: BigInteger?) {
        val inlinedLog2 get() = inlinedPathCount?.bitLength()

        val asText get() =
            if (inlinedLog2 == null || inlinedLog2!! < 10) {
                "$inlinedPathCount"
            } else {
                "approx. 2^${inlinedLog2}"
            }
    }

    override val asText
        get() =
            "Path count: ${rootEntry?.second?.asText}"

    override val liveStatsSummaryText get() =
        rootEntry?.second?.asText ?: "overall path count missing"

    override val severityGlobal: PrettyPrintableStats.Severity
        get() =
            rootEntry?.second?.let { globalPathCount ->
                val log2 = globalPathCount.inlinedLog2
                when {
                    log2 == null -> PrettyPrintableStats.Severity.UNKNOWN
                    log2 < 20 -> PrettyPrintableStats.Severity.LOW
                    log2 < 100 -> PrettyPrintableStats.Severity.MEDIUM
                    else -> PrettyPrintableStats.Severity.HIGH
                }
            } ?: PrettyPrintableStats.Severity.UNKNOWN

    fun asLongText(lineSeparator: String = "<br/>\n") =
            "Detailed path count info (call id: #local paths, #inlined paths (log2)):$lineSeparator" +
                callIdToPathCountData.joinToString(lineSeparator) { (callId, pathCount) ->
                    "$callId: ${pathCount.localPathCount}, ${pathCount.inlinedPathCount} (${pathCount.inlinedLog2})"
                }

    companion object {

        /** Hardcoded config flag (can expose this via [Config] if needed) for switching on/off the "per procedure
         * path count hotspot list". */
        const val computePathCountHotspots = true

        /** We don't want stats computations to crash a run, so we're swallowing exceptions here -- note this is a
         * severe anti-pattern in most contexts, so this method should stay local and only be used for securing
         * stats computations. */
        private fun <T> swallowExceptionsInStatsComputation(block: () -> T): T? =
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            try {
                block()
            } catch (e: Exception) {
                logger.info(e) { "got exception when generating path count display" }
                null
            }

        /**
         * [computeProcedureHotspots] allows switching the hotspot computations off e.g. for split-subprograms, where
         * it seems overkill
         */
        fun computeAndLogTime(
            codeMap: CodeMap,
            computeProcedureHotspots: Boolean,
        ): PathCountStats =
            CodeAnalysis(
                analysisName = "PathCountStats",
                analyzer = { it: CodeMap -> compute(it, computeProcedureHotspots) },
                successCriteria = { true }
            ).runAnalysis(codeMap)

        private fun compute(
            codeMap: CodeMap,
            computeProcedureHotspots: Boolean,
        ): PathCountStats {
            val callIdToPathCountData = computeCallIdToPathCountData(codeMap)
            return PathCountStats(
                callIdToPathCountData = callIdToPathCountData,
                procIdToDifficultyScore =
                if (computePathCountHotspots && computeProcedureHotspots) {
                    callIdToPathCountData[ROOT_CALL_ID].second.inlinedPathCount?.let { totalPathCount ->
                        (codeMap.withInternalFunctions as? CoreTACProgram)?.let { prog ->
                            computeProcIdToDifficultyScore(prog, totalPathCount)
                        }
                    }.orEmpty().mapKeys { (k, _) -> k.toString() }
                } else {
                    emptyMap()
                }
            )
        }

        private fun computeProcIdToDifficultyScore(
            prog: CoreTACProgram,
            wholeProgPathCount: BigInteger,
        ): Map<ProcedureId, Int> {
            val procedureIdToCallIds = mutableMultiMapOf<ProcedureId, CallId>().apply {
                prog.procedures.forEach { procedure ->
                    add(procedure.procedureId, procedure.callId)
                }
            }

            val graph = prog.analysisCache.graph

            val rootBlockId = graph.rootBlockIds.singleOrNull() ?: run {
                logger.warn {
                    "${prog.name} does not have a single root block (but rather " +
                        "these: ${graph.rootBlocks}), can't compute per-procedure path count contribution"
                }
                return emptyMap()
            }

            return procedureIdToCallIds.entries.associate { (procedureId, callIds) ->
                // count the paths as if the current procedure had only one
                val pathCountProcJumped =
                    PathCounter(graph, callIdsToJump = callIds).pathCounts[rootBlockId] ?: run {
                        logger.warn { "failed to compute path count while 'jumping' procedure $procedureId" }
                        return@associate procedureId to 0
                    }
                /*
                 * (log2(wholeProgramCount) - log2(pathCountProcJumped) /log2(wholeProgramCount)) * 100
                 *
                 * So if path count is 2^n (which how we display path count in other places), this is the
                 * percentage of n that can be reduced by minimizing the control flow of the given procedure.
                 *
                 * (using `bitLength` for log2, like in other places)
                 * (path count can't be 0, and bitLength of 1 is 1, so the division should be safe here)
                 */
                val score =
                    ((wholeProgPathCount.bitLength() - pathCountProcJumped.bitLength()) * 100 /
                        wholeProgPathCount.bitLength())
                Logger.regression {
                    "path count hotspots computation result for ${prog.name}: $procedureId ~~> $score"
                }
                procedureId to score
            }
        }

        fun computeCallIdToPathCountData(codeMap: CodeMap): List<Pair<CallIdWithName, PathCountData>> {
            val callGraph = codeMap.callGraphInfo.callGraph
            return (mapOf(CallIdWithName.Root to codeMap.ast) +
                codeMap.subAsts.mapKeys { (callId, _) ->
                    CallIdWithName(
                        callId,
                        codeMap.callIdNames[callId].orEmpty()
                    )
                })
                .map { (callId, subTacProg) ->
                    val localPathCount =
                        swallowExceptionsInStatsComputation {
                            // count paths in the current sub-cfg
                            subTacProg.analysisCache?.graph?.let { graph ->
                                PathCounter(graph, subTacProg.getStartingBlock()).singlePathCount
                            }
                        }
                    val inlinedPathCount =
                        swallowExceptionsInStatsComputation {
                            // jump everything that's not "below" `callId` in the call graph
                            val toJump =
                                codeMap.callIdNames.keys - getReachable(callId.callId, callGraph)
                            // count in the overall (everything inlined) CFG from exits to entry of the current sub-cfg
                            codeMap.withInternalFunctions.analysisCache?.graph?.let { graph ->
                                PathCounter(graph, subTacProg.getStartingBlock(), toJump).singlePathCount
                            }
                        }
                    callId to PathCountData(localPathCount, inlinedPathCount)
                }.sortedBy { it.first.callId }
        }
    }
}

@Serializable
data class CallIdWithName(val callId: CallId, val name: String): Comparable<CallIdWithName> {
    override fun compareTo(other: CallIdWithName): Int = this.callId.compareTo(other.callId)

    override fun hashCode(): Int = treapHash { it + callId }

    override fun equals(other: Any?): Boolean = other is CallIdWithName && other.callId == this.callId

    override fun toString(): String = "call #$callId: $name"
    companion object {
        val Root = CallIdWithName(ROOT_CALL_ID, "main")
    }
}

/** Only used for [SDCollector] json serialization, together with [PerRuleDiffStatsJavaSerializerWrapper]. */
@Serializable
data class PerRuleDiffStats(
    val name: NameObject,
    val ruleLevelStats: List<SingleDifficultyStats>,
    val splitLevelStats: List<SingleDifficultyStats>,
)

/** Only used for [SDCollector] json serialization, together with [PerRuleDiffStats]. */
@JvmInline
value class PerRuleDiffStatsJavaSerializerWrapper(val toSerialize: PerRuleDiffStats) : java.io.Serializable {
    override fun toString(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(toSerialize)
    }
}
