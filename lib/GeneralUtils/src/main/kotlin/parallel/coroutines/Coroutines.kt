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

@file:Suppress("SuspendFunWithCoroutineScopeReceiver") // We are defining explicit scoping operators.
package parallel.coroutines

import datastructures.*
import datastructures.stdcollections.*
import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import parallel.ParallelPool
import utils.*

/**
    Runs a coroutine as a "compute" job in the ParallelPool.
 */
suspend fun <T> ParallelPool.compute(block: suspend CoroutineScope.() -> T): T {
    checkCanRunParallelCoroutines()
    return withContext(workerThreadPool.asCoroutineDispatcher(), block)
}

/**
    Runs a coroutine as a "compute" job in the ParallelPool.
 */
suspend fun <T> ParallelPool.Companion.compute(
    spawnPolicy: ParallelPool.SpawnPolicy = ParallelPool.SpawnPolicy.New(),
    block: suspend CoroutineScope.() -> T
) = ParallelPool.inherit(spawnPolicy) { it.compute(block) }

/**
    Runs a coroutine as an I/O job in the ParallelPool. Do not use this for compute-intensive work, as it will
    oversubscribe CPU cores.  Instead, use [rpc] (for compute-intensive work in another process), or [compute] (for
    compute-intensive work in this process).
 */
suspend fun <T> ParallelPool.io(block: suspend CoroutineScope.() -> T): T {
    return withContext(ioPool.asCoroutineDispatcher(), block)
}

/**
    Runs a coroutine as an I/O job in the ParallelPool. Do not use this for compute-intensive work, as it will
    oversubscribe CPU cores.  Instead, use [rpc] (for compute-intensive work in another process), or [compute] (for
    compute-intensive work in this process).
 */
suspend fun <T> ParallelPool.Companion.io(
    spawnPolicy: ParallelPool.SpawnPolicy = ParallelPool.SpawnPolicy.New(),
    block: suspend CoroutineScope.() -> T
) = ParallelPool.inherit(spawnPolicy) { it.io(block) }


/**
    Runs a coroutine as an "rpc" job in the ParallelPool. This is intended for compute-intensive work in another
    process.  Blocks a worker thread for the duration of the coroutine, to avoid oversubscribing CPU cores, while
    running the coroutine on an I/O thread, so that we won't get extra worker threads if the RPC job blocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> ParallelPool.rpc(block: suspend CoroutineScope.() -> T): T =
    // Claim a worker thread
    compute {
        // Start the coroutine on an io thread
        val job = async(ioPool.asCoroutineDispatcher().limitedParallelism(1), block = block)

        // Block the worker thread until the IO completes.  Note that kotlinx.coroutines.runBlocking does not cause the
        // ForkJoinPool to add threads, which is exactly the behavior we want here.
        @Suppress("ForbiddenMethodCall")
        runBlocking { job.await() }
    }

/**
    Applies [transform] to each element in parallel, starting the transforms in order.
 */
context(ParallelPool)
suspend fun <T, R> Iterable<T>.parallelMapOrdered(
    concurrencyLimit: Int = Int.MAX_VALUE,
    transform: suspend CoroutineScope.(Int, T) -> R
): List<R> = coroutineScope {
    checkCanRunParallelCoroutines()

    val semaphore = Semaphore(concurrencyLimit)

    // Transform the next element, queueing the transforms of any additional elements.  We do this one element at a
    // time, to ensure that the transforms are given the opportunity to start in order.  (Note that the ForkJoinPool
    // does not guarantee FIFO ordering of tasks, so we can't simply queue all of the transforms at once.)
    suspend fun transformRemaining(iterator: Iterator<T>, index: Int): PersistentStack<R> = when {
        !iterator.hasNext() -> persistentStackOf()
        else -> {
            val (next, r) = semaphore.withPermit {
                val t = iterator.next()
                val next = async { transformRemaining(iterator, index + 1) }
                val r = transform(index, t)
                next to r
            }
            next.await().push(r)
        }
    }
    transformRemaining(iterator(), 0).toList()
}

/**
    Applies [transform] to each element in parallel, starting the transforms in order.
 */
suspend fun <T, R> Iterable<T>.parallelMapOrdered(
    concurrencyLimit: Int = Int.MAX_VALUE,
    spawnPolicy: ParallelPool.SpawnPolicy = ParallelPool.SpawnPolicy.New(),
    transform: suspend CoroutineScope.(Int, T) -> R
) = ParallelPool.inherit(spawnPolicy) { pool ->
    with(pool) {
        parallelMapOrdered(concurrencyLimit, transform)
    }
}

/**
    Returns a [Deferred<T>] which lazily executes [initializer] when awaited.

    [initializer] runs in the ParallelPool's worker thread pool.  It runs under a standalone [CoroutineScope], which
    means that there is no way to cancel it (since it has no parent scope).
 */
fun <T> ParallelPool.lazy(initializer: suspend () -> T): Deferred<T> =
    CoroutineScope(workerThreadPool.asCoroutineDispatcher()).async(start = CoroutineStart.LAZY) {
        initializer()
    }

/**
    Returns a [Deferred<T>] which lazily executes [initializer] when awaited.

    [initializer] runs in the ParallelPool's worker thread pool.  It runs under a standalone [CoroutineScope], which
    means that there is no way to cancel it (since it has no parent scope).
 */
fun <T> ParallelPool.Companion.lazy(
    spawnPolicy: ParallelPool.SpawnPolicy = ParallelPool.SpawnPolicy.GLOBAL,
    initializer: suspend () -> T
) = ParallelPool.inherit(spawnPolicy) { it.lazy(initializer) }


/** See `onThisThread` */
private val canRunParallelCoroutines = ThreadLocal.withInitial { true }

/** See `onThisThread` */
fun checkCanRunParallelCoroutines() = check(canRunParallelCoroutines.get()) { "Cannot leave this thread from inside onThisThread" }

/**
    Runs a coroutine on the current thread, blocking until it completes.

    The coroutine is prohibited from launching parallel "compute" work in the ParallelPool, to avoid deadlocks. Instead,
    work is confined to the calling thread (or it may move to the I/O pool).  [checkCanRunParallelCoroutines] can be
    used to check if the current thread is allowed to launch parallel work.
 */
fun <T> onThisThread(block: suspend CoroutineScope.() -> T): T {
    val prev = canRunParallelCoroutines.get()
    canRunParallelCoroutines.set(false)
    try {
        @Suppress("ForbiddenMethodCall")
        return runBlocking(block = block)
    } finally {
        canRunParallelCoroutines.set(prev)
    }
}
