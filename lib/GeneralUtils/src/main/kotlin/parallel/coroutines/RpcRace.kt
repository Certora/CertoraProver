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

package parallel.coroutines

import kotlinx.coroutines.*
import log.*
import parallel.ParallelPool
import utils.*
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

val logger = Logger(GeneralUtilsLoggerTypes.RPCRACE)

sealed interface RaceFinish<out T> {
    val res: T

    /**
        A full finish: this external computation completed with a full, definite result that should be used as the
        result of the whole race job.
     */
    data class Full<out T>(override val res: T) : RaceFinish<T>

    /**
        DisQualified Finish: the computation finished with a result [res] but it is not an interesting result or "true"
        result, e.g., an external solver finished with "unknown" or "timeout"
     */
    data class DQF<out T>(override val res: T) : RaceFinish<T>
}

sealed interface RacerResult<out T> {
    val res: T?

    data class FromJob<out T>(override val res: T) : RacerResult<T>
    data class LostRace<out T>(override val res: T?) : RacerResult<T>
    data class Skipped<out T>(override val res: T?) : RacerResult<T>
    data class SkippedDelayed<out T>(override val res: T?) : RacerResult<T>
    data class Timeout<out T>(override val res: T?) : RacerResult<T>
}

class Racer<T>(
    val thunk: suspend () -> RaceFinish<T>,
    val timeout: Duration?,
    val resultOnNoExit: T? = null,
    val maximumAllowedDelay: Duration? = null,
)

/**
    Runs each of the [racers] until one of them completes with a "full" result, or until all of them complete.  Runs N
    racers concurrently (determined by the ParallelPool parallelism level).  Starts racers in order, so earlier racers
    get priority over later racers.
 */
suspend fun <T> ParallelPool.rpcRace(
    racers: Iterable<Racer<T>>
): Pair<Int, List<RacerResult<T>>> = coroutineScope {

    val raceId = hash { it + TimeSinceStart.epoch.elapsedNow() + Thread.currentThread().threadId() }
    fun log(msg: () -> String) = logger.debug { "[${raceId} ${TimeSinceStart.epoch.elapsedNow()}] ${msg()}" }

    // Mark the start of the first job
    val startTime by utils.lazy { getCurrentTime() }

    // Set up a parent job for the race jobs, so we can cancel them without cancelling the outer scope
    // This parent job in turn gets the current job as parent so that it is cancelled when the current job is cancelled.
    val cancellationGroup = SupervisorJob(this.coroutineContext.job)

    val whoWon = AtomicInteger(-1)
    val cancelTime = AtomicReference<TimeMark?>(null)

    log { "starting rpc race "}
    val results = try {
        racers.parallelMapOrdered { index, it ->
            log { "racer ${index}: ${it}" }
            rpc {
                when {
                    // Were we cancelled before this job started?
                    cancellationGroup.isCancelled -> {
                        log { "racer ${index}: skipped" }
                        RacerResult.Skipped(it.resultOnNoExit)
                    }
                    // Did we have to wait in the queue too long?
                    it.maximumAllowedDelay != null && startTime.elapsedNow() > it.maximumAllowedDelay -> {
                        RacerResult.SkippedDelayed(it.resultOnNoExit)
                    }
                    else -> try {
                        log { "racer ${index}: start" }
                        // Run the job with the requested timeout
                        val finish = withContext(cancellationGroup) {
                            when {
                                it.timeout == null -> it.thunk()
                                else -> withTimeout(it.timeout) { it.thunk() }
                            }
                        }
                        // If this job finished with a "full" result, it won the race! Cancel the rest of the jobs.
                        if (finish is RaceFinish.Full) {
                            log { "racer ${index}: finished with full result, cancelling race" }
                            whoWon.compareAndSet(-1, index)
                            cancelTime.compareAndSet(null, getCurrentTime())
                            cancellationGroup.cancel()
                        }
                        RacerResult.FromJob(finish.res)
                    } catch (_: TimeoutCancellationException) {
                        RacerResult.Timeout(it.resultOnNoExit)
                    } catch (_: ClosedByInterruptException) {
                        RacerResult.Timeout(it.resultOnNoExit)
                    } catch (_: CancellationException) {
                        if ((cancelTime.get()?.elapsedNow() ?: 0.seconds) > 5.seconds) {
                            logger.warn { "Racer was cancelled more than 5 seconds after another solver had a full result: ${cancelTime.get()?.elapsedNow()}. The system is likely under heady load." }
                        }
                        RacerResult.LostRace(it.resultOnNoExit)
                    } catch (t: Throwable) {
                        cancellationGroup.cancel()
                        throw t
                    }.also {
                        log { "racer ${index}: finished with ${it}" }
                    }
                }
            }
        }
    } finally {
        // mark the supervisor job complete, so that its parent job can complete as well
        cancellationGroup.complete()
    }
    log { "race finished" }
    if ((cancelTime.get()?.elapsedNow() ?: 0.seconds) > 5.seconds) {
        logger.warn { "Finishing the rpc race took more than 5 seconds after some solver had a full result: ${cancelTime.get()?.elapsedNow()}. The system is likely under heady load." }

    }
    whoWon.get() to results
}

context(ParallelPool)
suspend fun <T, R> Iterable<T>.rpcRace(
    timeout: Duration? = null,
    maximumAllowedDelay: Duration? = null,
    resultOnNoExit: R? = null,
    f: suspend (T) -> RaceFinish<R>
): Pair<Int, List<RacerResult<R>>> = rpcRace(
    map {
        Racer({ f(it) }, timeout, resultOnNoExit, maximumAllowedDelay)
    }
)
