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

@file:UseSerializers(DurationAsIso8601Serializer::class) // Note: can be removed with Kotlin 1.7.20?
package verifier

import datastructures.stdcollections.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import solver.SolverInfo
import statistics.SDCollector
import statistics.SDCollectorFactory
import statistics.recordAny
import utils.*
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import java.io.Serializable as JavaSerializable

// Semantic versioning for the statistics scheme.
private const val VERSION = "0.1.4"

class SMTStatsHolder(var smt: SMTStatistics)

class SMTStatsResource(val statistics: SMTStatsHolder, val collector: SDCollector) : Closeable {

    override fun close() {
        collector.recordAny(SMTStatsJavaSerializerWrapper(statistics.smt), tag = vcSpaceString, key = smtSpaceString)
    }

    companion object {
        const val smtSpaceString = "SMT"
        const val vcSpaceString = "VCSpace"
        const val solverVersionString = "solverVersion"
    }
}

// Note: can be removed with Kotlin 1.7.20?
internal object DurationAsIso8601Serializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CustomDurationSerializer", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toIsoString())
    override fun deserialize(decoder: Decoder): Duration = Duration.parseIsoString(decoder.decodeString())
}

@Serializable
data class SingleResultStatistic(
    val satResult: String,
    val logic: String,
    val solverConfigurationName: String,
    val solverName: String,
    val arguments: String,
    val solvingTime: Duration,
    val smtGenerationTime: Duration? = null,
    val vcGenerationTime: Duration? = null,
    val learnedClauseParsingTime: Duration? = null,
    val learnedClauseInputNum: Int? = null,
    val learnedClauseUsableNum: Int? = null,
    val learnedClauseOutputNum: Int? = null,
    val execTimeToStartSinceJarStart: TimeSinceStart? = null,
    val execThreadName: String? = null,
)

@Serializable
data class RaceStatistic(
    /** default timeout for individual solvers */
    val timeout: Duration?,
    val raceDescription: String?,
    val solvingTime: Duration?,
    val timeToStartSinceJarStart: TimeSinceStart?,
    /** Statistics for single SMT result, including any preprocessor solvers */
    val singleResults: List<SingleResultStatistic> = listOf(),
)

@Serializable
data class CEGARStatistic(
    val queryName: String,
    val type: String, // TODO: or enum?
    val solverName: String,
    val solverBinary: String,
    val commandLine: String,
    val result: String,
    val completion: Int,
    val duration: Duration,
    val cexSize: Int = 0,
    val cChooser: String = "",// TODO: or enum?
)

@Serializable
sealed class SolvingEventStatistic : HasKSerializable

@Serializable
@SerialName("RaceList")
data class RaceListStatistic(
    /** Statistics for all races (that run solvers in parallel) */
    val races: List<RaceStatistic>,
    /** Number of usable learned clauses produced by the solving approaches */
    val numUsableLearnedClausesOutput: Int,
    /** Number of all learned clauses produced by the solving approaches */
    val numAllLearnedClausesOutput: Int,
) : SolvingEventStatistic()

@Serializable
@SerialName("CEGARList")
data class CEGARListStatistic(
    /** Statistics for CEGAR runs */
    val cegars: List<CEGARStatistic>
) : SolvingEventStatistic()

/** Statistics for the counterexample prettification */
@Serializable
data class PrettifierStatistics(
    val numOfPrettifications: Int = 0,
    val totalTime: Duration = Duration.ZERO,
)

/** Statistics for the counterexample diversifier */
@Serializable
data class DiversifierStatistics(
    /** Number of targets for block heuristic */
    val targetCountBlocks: Int? = null,
    /** Number of targets for user-assert heuristic */
    val targetCountUserAsserts: Int? = null,
    /** Number of targets for auto-assert heuristic */
    val targetCountAutoAsserts: Int? = null,
    /** Number of targets for zero-split heuristic */
    val targetCountZeroSplit: Int? = null,
    /** Number of checks issued to the solver */
    val checkCount: Int = 0,
    /** Number of counterexamples found */
    val cexCount: Int = 0,
    /** Total time for generating the targets, should be negigible */
    val totalTimeTargetGeneration: Duration = Duration.ZERO,
    /** Total time for the solver checks */
    val totalTimeChecks: Duration = Duration.ZERO,
    /** Total time for evaluating the targets, should be negligible */
    val totalTimeTargetEval: Duration = Duration.ZERO,
)

@Serializable
@SerialName("Resplit")
data class ResplitStatistic(
    val resplitTime: Duration
) : SolvingEventStatistic()

@Serializable
data class SplitStatistic(
    /** The location of the split in the split tree.  -1 indicates a failed split */
    val address: String,
    /** splitName exactly as it is in Split<CoreTACProgram>.name. Also referred to as vcName */
    val splitName: String,
    val finalResult: String? = null,
    val timeout: Duration? = null,
    val smtSolvingWallTime: Duration? = null,
    val timeToSolveSinceJarStart: TimeSinceStart,
    val vcGenerationTime: Duration? = null,
    val prettifyModelTime: Duration? = null,
    val prettifierStatistics: PrettifierStatistics? = null,
    val diversifierStatistics: DiversifierStatistics? = null,
    /** Features present in the query (e.g., non-linear arithmetic) */
    val vcFeatures: List<String>,
    /** Statistics for approaches employed in solving (races & CEGAR runs) */
    val solvingEvent: SolvingEventStatistic,

    )

@Serializable
data class NameObject (
    /** Name of the rule */
    val baseName: String,
    /** Name of the contract */
    val contractNames: List<String>? = null,
    /** Rule type (vacuity, soundness, ...) */
    val ruleType: String? = null,
)

@Serializable
data class SMTStatistics private constructor(
    val version: String,
    val name: NameObject,
    val totalSmtSolvingTime: Duration,
    val totalSplits: Int,
    val startTime: TimeSinceStart,
    val finishTime: TimeSinceStart,
    /** Statistics for each split, including the root instance */
    val splits: List<SplitStatistic>,
) : HasKSerializable {
    constructor(
        name: NameObject,
        totalSmtSolvingTime: Duration = 0.milliseconds,
        totalSplits: Int = 0,
        startTime: TimeSinceStart = TimeSinceStart(),
        finishTime: TimeSinceStart = TimeSinceStart(),
        splits: List<SplitStatistic> = listOf(),
        ) : this(
            VERSION,
            name,
            totalSmtSolvingTime,
            totalSplits,
            startTime,
            finishTime,
            splits,

       )
}

@Serializable
data class SolverVersion(val name: String, val exeName: String, val version: String?) : HasKSerializable

class SMTStatsJavaSerializerWrapper(private val toSerialize: SMTStatistics): JavaSerializable {
    override fun toString(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(toSerialize)
    }
}

class SolverVersionJavaSerializerWrapper(private val toSerialize: SolverVersion): JavaSerializable {
    override fun toString(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(toSerialize)
    }
}

fun logSmtSolvers() {
    val collector = SDCollectorFactory.collector()
    SolverInfo.allSolverInfos.map { solver ->
        collector.recordAny(
            SolverVersionJavaSerializerWrapper(
                SolverVersion(
                    solver.key,
                    solver.value.defaultCommand,
                    solver.value.getSolverVersionStringOrNull()
                )
            ), tag = SMTStatsResource.solverVersionString, key = SMTStatsResource.smtSpaceString
        )
    }
}
