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

package event

import config.component.EventConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import log.*
import report.EventEnvironmentVars
import rules.RuleCheckResult
import solver.SolverResult
import spec.rules.IRule
import utils.KSerializable


private val logger = Logger(LoggerTypes.EVENT_STREAM)

/**
 * A base event for all concrete Cvt events.
 */
@Suppress("MustHaveKSerializable") // Work-around for CERT-4335.  We dont't serialize this type directly, so this is ok.
sealed class CvtEvent : EventBase<EventTopic.CVT>() {

    override val eventTopic: EventTopic.CVT
        get() = EventTopic.CVT

    /**
     * The first event output by CVT.
     *
     * @property metadata the [RunMetadata] from which the start event params are extracted
     * @property userId user id
     * @property storageLink the S3 link to this run's related files
     * @property tags additional user-defined tags, see: [EventConfig.UserDefinedEventTags]
     *
     */
    @KSerializable(with = CvtStartEventSerializer::class)
    data class CvtStartEvent(
        val metadata: RunMetadata
    ) : CvtEvent() {
        val storageLink = EventEnvironmentVars.outputUrl
        val tags = EventConfig.UserDefinedEventTags.getOrNull().orEmpty()

        override val eventTypeId: String
            get() = "CvtStartEvent"
    }

    object CvtStartEventSerializer : KSerializer<CvtStartEvent> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CvtStartEvent") {
            element("jobId", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("userId", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("storageLink", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("tags", SetSerializer(String.serializer()).descriptor)
            element("repository", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("branch", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("revision", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("filesPrefix", PrimitiveSerialDescriptor("contentString", PrimitiveKind.STRING))
            element("spec", ListSerializer(String.serializer()).descriptor)
            element("verified", String.serializer().descriptor)
        }

        override fun deserialize(decoder: Decoder): CvtStartEvent {
            throw NotImplementedError("CvtStartEvent is not expected to be deserialized")
        }

        override fun serialize(encoder: Encoder, value: CvtStartEvent) {

            val composite = encoder.beginStructure(descriptor)
            composite.encodeStringElement(descriptor, 0, value.jobId.value)
            composite.encodeStringElement(descriptor, 1, value.userId.value)
            composite.encodeStringElement(descriptor, 2, value.storageLink.value)
            composite.encodeSerializableElement(descriptor, 3, SetSerializer(String.serializer()), value.tags)

            val conf = RunMetadataConf.fromUntypedMap(value.metadata.conf)

            composite.encodeStringElement(descriptor, 4, value.metadata.origin)
            composite.encodeStringElement(descriptor, 5, value.metadata.branch)
            composite.encodeStringElement(descriptor, 6, value.metadata.revision)
            composite.encodeStringElement(descriptor, 7, value.metadata.cwdRelative)
            composite.encodeSerializableElement(descriptor, 8, ListSerializer(String.serializer()), conf.spec)
            composite.encodeStringElement(descriptor, 9, conf.verified)
            composite.endStructure(descriptor)
        }
    }

    /**
     * The last event output by CVT.
     * @property duration the length of time in milliseconds that the Prover has executed.
     */
    @KSerializable(with = CvtStopEventSerializer::class)
    class CvtStopEvent private constructor(
        val duration: Long,
        val ruleCheckResultsStats: RuleCheckResultsStats?
    ) : CvtEvent() {
        class RuleCheckResultsStats private constructor(
            val numVerified: Int,
            val numViolated: Int,
            val numTimeout: Int,
            val numError: Int
        ) {
            override fun toString() = "RuleCheckResultsStats: " +
                "numTotal = $numTotal; numVerified = $numVerified; numViolated = $numViolated; " +
                "numTimeout = $numTimeout; numError = $numError"

            init {
                logger.debug(::toString)
            }
            val numTotal: Int
                get() = numVerified + numViolated + numTimeout + numError
            companion object {
                context(ClassSerialDescriptorBuilder, CvtStopEventSerializer)
                fun addStatsDescriptors() {
                    element(
                        "verifiedRules",
                        PrimitiveSerialDescriptor("verifiedRulesAsInt", PrimitiveKind.INT).nullable
                    )
                    element(
                        "violatedRules",
                        PrimitiveSerialDescriptor("violatedRulesAsInt", PrimitiveKind.INT).nullable
                    )
                    element(
                        "timeoutRules",
                        PrimitiveSerialDescriptor("timeoutRulesAsInt", PrimitiveKind.INT).nullable
                    )
                    element(
                        "errorRules",
                        PrimitiveSerialDescriptor("errorRulesAsInt", PrimitiveKind.INT).nullable
                    )
                    element(
                        "allRules",
                        PrimitiveSerialDescriptor("allRulesAsInt", PrimitiveKind.INT).nullable
                    )
                }

                /**
                 * If statistics are not available (i.e., [ruleCheckResultsStats] is null), the event includes all
                 * the features but all have null values. The goal is to have clear distinction between cases where
                 * (1) stats are available, but all features have 0 values, and (2) stats are unavailable.
                 *
                 * @return The index of the next element to encode in the serial descriptor ([descriptor]).
                 */
                context(CvtStopEventSerializer)
                @OptIn(ExperimentalSerializationApi::class)
                fun encodeStats(
                    ruleCheckResultsStats: RuleCheckResultsStats?,
                    composite: CompositeEncoder,
                    baseIndex: Int
                ): Int {
                    composite.encodeNullableSerializableElement(
                        descriptor,
                        baseIndex,
                        Int.serializer(),
                        ruleCheckResultsStats?.numVerified
                    )
                    composite.encodeNullableSerializableElement(
                        descriptor,
                        baseIndex + 1,
                        Int.serializer(),
                        ruleCheckResultsStats?.numViolated
                    )
                    composite.encodeNullableSerializableElement(
                        descriptor,
                        baseIndex + 2,
                        Int.serializer(),
                        ruleCheckResultsStats?.numTimeout
                    )
                    composite.encodeNullableSerializableElement(
                        descriptor,
                        baseIndex + 3,
                        Int.serializer(),
                        ruleCheckResultsStats?.numError
                    )
                    composite.encodeNullableSerializableElement(
                        descriptor,
                        baseIndex + 4,
                        Int.serializer(),
                        ruleCheckResultsStats?.numTotal
                    )
                    return baseIndex + 5
                }
            }
            class Builder {
                /**
                 * This class allows [getCounterOf] to return a reference to a counter
                 * (i.e., an instance of this class) whose value (i.e., [Counter.value]) should be incremented.
                 */
                private class Counter(var value: Int = 0) {
                    fun increment() {
                        value++
                    }

                    override fun toString(): String = value.toString()
                }

                private val verifiedCnt = Counter()
                private val violatedCnt = Counter()
                private val timeoutCnt = Counter()
                private val errorCnt = Counter()

                /**
                 * Returns the counter property of this [Builder] associated with the specified [solverResult] and [rule].
                 */
                private fun getCounterOf(solverResult: SolverResult, rule: IRule): Counter =
                    when (solverResult) {
                        SolverResult.SAT -> {
                            if (rule.isSatisfyRule) {
                                // For Satisfy rules we consider SAT as VERIFIED
                                verifiedCnt
                            } else {
                                violatedCnt
                            }
                        }

                        SolverResult.UNSAT -> {
                            if (rule.isSatisfyRule) {
                                // For Satisfy rules we consider UNSAT as VIOLATED
                                violatedCnt
                            } else {
                                verifiedCnt
                            }
                        }

                        SolverResult.UNKNOWN -> {
                            errorCnt
                        }

                        SolverResult.TIMEOUT -> {
                            timeoutCnt
                        }

                        SolverResult.SANITY_FAIL -> {
                            // We consider sanity-failures as "rule-violations"
                            violatedCnt
                        }
                    }

                fun addStatsOf(ruleCheckResults: List<RuleCheckResult>) {
                    ruleCheckResults.forEach {
                        if (it.rule.ruleType.isCounted()) {
                            val cnt = when (it) {
                                is RuleCheckResult.Single -> {
                                    getCounterOf(it.result, it.rule)
                                }

                                is RuleCheckResult.Error -> {
                                    errorCnt
                                }

                                is RuleCheckResult.Multi -> {
                                    getCounterOf(it.computeFinalResult(), it.rule)
                                }

                                is RuleCheckResult.Skipped -> { /* We ignore skipped rules (those may occur due to --rule flag) */
                                    null
                                }
                            }

                            cnt?.increment()
                        }
                    }
                }

                fun toStats(): RuleCheckResultsStats = RuleCheckResultsStats(
                    numVerified = verifiedCnt.value,
                    numViolated = violatedCnt.value,
                    numTimeout = timeoutCnt.value,
                    numError = errorCnt.value
                )

            }
        }

        /**
         * Use this [Builder] to create a new [CvtStopEvent].
         * It allows one to optionally add rule results' statistics to the event via [addRuleCheckResultsStatsOf].
         */
        class Builder {

            private lateinit var ruleResultsStatsBuilder: RuleCheckResultsStats.Builder
            private val ruleCheckResultsStats: RuleCheckResultsStats?
                get() = if (::ruleResultsStatsBuilder.isInitialized) {
                    ruleResultsStatsBuilder.toStats()
                } else {
                    null
                }

            fun addRuleCheckResultsStatsOf(ruleCheckResults: List<RuleCheckResult>) {
                if (!(::ruleResultsStatsBuilder.isInitialized)) {
                    ruleResultsStatsBuilder = RuleCheckResultsStats.Builder()
                }

                check(::ruleResultsStatsBuilder.isInitialized) {
                    "Expected the RuleCheckResultsStats builder to be initialized at this stage"
                }

                ruleResultsStatsBuilder.addStatsOf(ruleCheckResults)

            }

            /**
             * @param runDuration The duration of the Prover run in milliseconds
             */
            fun toEvent(runDuration: Long): CvtStopEvent = CvtStopEvent(
                duration = runDuration,
                ruleCheckResultsStats = ruleCheckResultsStats
            ).also {
                Logger.toLogFile(it.toString())
            }

        }

        override fun toString(): String {
            return "Duration ${duration}, $ruleCheckResultsStats"
        }

        override val eventTypeId: String
            get() = "CvtStopEvent"
    }

    object CvtStopEventSerializer : KSerializer<CvtStopEvent> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CvtStopEvent") {
            element("jobId", PrimitiveSerialDescriptor("jobIdAsString", PrimitiveKind.STRING))
            element("userId", PrimitiveSerialDescriptor("userIdAsString", PrimitiveKind.STRING))
            element("duration", PrimitiveSerialDescriptor("durationAsLong", PrimitiveKind.LONG))
            CvtStopEvent.RuleCheckResultsStats.addStatsDescriptors()
        }

        override fun deserialize(decoder: Decoder): CvtStopEvent {
            throw NotImplementedError("CvtStopEvent is not expected to be deserialized")
        }

        override fun serialize(encoder: Encoder, value: CvtStopEvent) {
            val composite = encoder.beginStructure(descriptor)
            composite.encodeStringElement(descriptor, 0, value.jobId.value)
            composite.encodeStringElement(descriptor, 1, value.userId.value)
            composite.encodeLongElement(descriptor, 2, value.duration)
            CvtStopEvent.RuleCheckResultsStats.encodeStats(value.ruleCheckResultsStats, composite, 3)
            composite.endStructure(descriptor)
        }
    }
}
