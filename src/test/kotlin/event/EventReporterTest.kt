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

import annotations.PollutesGlobalState
import awshelpers.sqs.SQSStream
import config.CERTORA_METADATA_FILE_PATH
import datastructures.stdcollections.*
import infra.CertoraBuild
import infra.CertoraBuildKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import parallel.coroutines.establishMainCoroutineScope
import utils.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal class EventReporterTest {

    private val dummyMetadata = RunMetadata(
        rawArgs = listOf(),
        conf = mapOf(),
        origin = "",
        revision = "",
        branch = "",
        cwdRelative = "",
        dirty = false,
        timestamp = Instant.now(),
        cliVersion = "",
        cliPackageName = "",
        pythonVersion = "",
    )

    class TestStringFormatter : FormatterBase<String> {
        override fun format(e: EventBase<*>) : Result<String> = Result.success(e.toString())
    }

    class TestEventStream(val extOutput: MutableList<String>) : EventStreamBase {
        // this function can be called concurrently by multiple threads
        @Synchronized
        override fun sendEvents(evts: List<EventBase<*>>) {
            val stringFormatter = TestStringFormatter()
            evts.forEach { evt ->
                val result: Result<String> = stringFormatter.format(evt)
                result.fold(
                    onSuccess = { output ->
                        extOutput.add(output)
                    },
                    onFailure = {
                        println("TestEventStream1 encountered exception ${it.message} ")
                        throw it
                    }
                )
            }
        }
    }

    @Test
    fun basicTest() {
        // Instantiate streams
        val output1 = mutableListOf<String>()
        val output2 = mutableListOf<String>()

        val eventStream1 = TestEventStream(output1)
        val eventStream2 = TestEventStream(output2)

        val eventStreamMapper = EventStreamMapper(
            // associate events with topics
            mapOf(
                EventTopic.CVT to setOf(Result.success(eventStream1), Result.success(eventStream2))
            )
        )

        // Create an EventReporter
        val eventReporter = EventReporter(eventStreamMapper)
        // Create three events
        val eventA: CvtEvent.CvtStartEvent = CvtEvent.CvtStartEvent(dummyMetadata)
        val eventB: CvtEvent.CvtStartEvent = CvtEvent.CvtStartEvent(dummyMetadata)
        val testDuration = 12L
        val eventC: CvtEvent.CvtStopEvent = CvtEvent.CvtStopEvent.Builder().toEvent(testDuration)


        // Check logical consistency of EventTypeId
        assert(eventA.eventTypeId == CvtEvent.CvtStartEvent::class.java.simpleName)
        assert(eventC.eventTypeId == CvtEvent.CvtStopEvent::class.java.simpleName)

        // eventA and eventB will be sent to Topic1 (mapped to both eventStream1 and eventStream2)
        // eventC will be sent to Topic2 (mapped only to eventStream1)
        eventReporter.reportEvents(listOf(eventA, eventB, eventC))

        assert(output1.size == 3)
        assert(output1[0] == eventA.toString())
        assert(output1[1] == eventB.toString())
        assert(output1[2] == eventC.toString())

        assert(output2.size == 3)
        assert(output2[0] == eventA.toString())
        assert(output2[1] == eventB.toString())
    }

    class TestTimeStamper : TimeStamper {
        private var curTime: Long = 0
        fun incCurrentTime(delta: Long = 1) {
            curTime += delta
        }

        override fun getCurrentTime(): Long = curTime
        override fun getTimeUnits(): TimeUnit = TimeUnit.SECONDS
    }

    class TestBufferedEventStream(
        val extOutput: MutableList<String>, batchSize: Int,
        testTimeStamper: TestTimeStamper
    ) : BufferedEventStream(batchSize, testTimeStamper) {
        @Synchronized
        override fun sendEvents(evts: List<EventBase<*>>) {
            val stringFormatter = TestStringFormatter()
            evts.forEach { evt ->

                val result: Result<String> = stringFormatter.format(evt)
                result.fold(
                    onSuccess = { output ->
                        extOutput.add(output)
                    },
                    onFailure = {
                        println("TestBufferedEventStream encountered exception ${it.message} ")
                        throw it
                    }
                )
            }
        }
    }

    @Test
    fun bufferedStreamTest() {
        val batchSize = 5
        val timeStamper = TestTimeStamper()
        val output: MutableList<String> = mutableListOf()

        val bufEvtStream = TestBufferedEventStream(output, batchSize, timeStamper)

        val eventStreamMapper = EventStreamMapper(
            // associate events with topics
            mapOf(EventTopic.CVT to setOf(Result.success(bufEvtStream)))
        )

        val eventReporter = EventReporter(eventStreamMapper)

        // Report batchSize - 1 events
        for (i in 1 until batchSize) {
            eventReporter.reportEvents(
                listOf(
                    CvtEvent.CvtStartEvent(dummyMetadata)
                )
            )
        }
        // Check that no events were actually sent
        assert(output.size == 0)

        // Report one more event so that there are now batchSize events in total
        eventReporter.reportEvents(
            listOf(
                CvtEvent.CvtStartEvent(dummyMetadata)
            )
        )

        // Check to see that all events are now sent
        assert(output.size == batchSize)

        // Clear the output
        output.clear()

        // Report one single event
        val startEvent = CvtEvent.CvtStartEvent(dummyMetadata)
        eventReporter.reportEvent(startEvent)

        // Check that no event has been sent
        assert(output.size == 0)

        // Simulate the passage of time
        timeStamper.incCurrentTime()

        // Call flushStream to flush out any "old" events
        bufEvtStream.flushStream(1u)

        // Check that the single event was sent
        assert(output.size == 1)
    }

    // Because this test makes use of the SQS service, we don't want it to run on CI, otherwise it could
    // dramatically raise the amount of requests we send, and therefore the cost of using AWS. In order to prevent that,
    // we use @Disabled, which prevents it from running on CI.
    @Disabled
    @PollutesGlobalState // Another reason to not run this in CI: it pollutes the global config
    @Test
    fun sqsStreamTest() {
        // Instantiate stream
        establishMainCoroutineScope {
            runBlocking {
                val eventStreamMapper = EventStreamMapper(
                    // associate the event topic with the stream
                    mapOf(
                        EventTopic.CVT to
                            setOf(SQSStream.fetchSQSStream("testEvents.fifo"))
                    )
                )

                // Create an EventReporter
                val eventReporter = EventReporter(eventStreamMapper)
                // Create two events
                val eventA: CvtEvent.CvtStartEvent = CvtEvent.CvtStartEvent(dummyMetadata)
                // The reason the current time is included in eventB is to make sure that there will always be a
                // difference between event messages in this test, and therefore they won't be removed due to deduplication.
                val testDuration = 12L
                val eventB: CvtEvent.CvtStopEvent = CvtEvent.CvtStopEvent.Builder().toEvent(testDuration)
                // eventA and eventB will be sent to TEST_TOPIC (mapped to eventStream)
                eventReporter.reportEvents(listOf(eventA, eventB))
            }
        }
    }
}

internal class RunMetadataSerializationTest {
    private val confPath = workingDirectory().resolve("src/test/resources/cvl/Ghost/MultiContract/confExample/confExample.conf")

    @Test
    fun serializationSucceeds() {
        val metadataText = CertoraBuild.inTempDir(CertoraBuildKind.EVMBuild(), confPath).use { build ->
            val metadataFile = build.source.buildDir.resolve(CERTORA_METADATA_FILE_PATH)
            require(metadataFile.isRegularFile())
            metadataFile.readText()
        }

        /** attempt to serialize json to [RunMetadata] */
        val metadata = assertDoesNotThrow {
            RunMetadata.deserializeFromJson(metadataText)
        }

        /** attempt to serialize [RunMetadata.conf] to [RunMetadataConf] */
        assertDoesNotThrow {
            RunMetadataConf.fromUntypedMap(metadata.conf)
        }
    }
}
