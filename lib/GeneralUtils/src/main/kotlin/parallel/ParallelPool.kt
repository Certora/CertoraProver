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

package parallel

import datastructures.stdcollections.*
import log.*
import utils.*
import java.io.Closeable
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

private val logger = Logger(GeneralUtilsLoggerTypes.PARALLEL)


class ParallelPool private constructor(forkJoinPool: ForkJoinPool, internal val ioPool: ExecutorService): Closeable {

    private class PoolFactory : ForkJoinPool.ForkJoinWorkerThreadFactory {
        lateinit var parallelPool: ParallelPool
        override fun newThread(pool: ForkJoinPool?): ForkJoinWorkerThread =ParallelPoolWorkerThread(parallelPool, pool!!)
    }

    class ParallelPoolWorkerThread(val parallelPool: ParallelPool, pool: ForkJoinPool) : ForkJoinWorkerThread(pool)

    private constructor(workerThreads: Int, poolFactory: PoolFactory) : this(ForkJoinPool(
            workerThreads,
            poolFactory,
            null,
            false,
            0,
            workerThreads + 256,
            workerThreads,
            { _: ForkJoinPool -> true },
            60,
            TimeUnit.SECONDS
    ), Executors.newCachedThreadPool()) {
        poolFactory.parallelPool = this
    }

    constructor(workerThreads: Int = System.getProperty("cvt.default.parallelism")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()) : this(
            workerThreads, PoolFactory()
    )

    internal val workerThreadPool = forkJoinPool

    private inner class SimpleScheduler {
        fun resolve(v: Parallel<*>) {
            val depQueue = mutableListOf(v)
            val execQueue = mutableListOf<Parallel<*>>()
            while(depQueue.isNotEmpty() || execQueue.isNotEmpty()) {
                while(depQueue.isNotEmpty()) {
                    val j = depQueue.removeLast()
                    execQueue.add(j)
                    depQueue.addAll(j.deps)
                }
                while(execQueue.isNotEmpty()) {
                    val exec = execQueue.removeLast()
                    check(exec.runnable)
                    val res = exec.queueSchedule(ioPool)
                    check(exec.done() || res.isNotEmpty())
                    depQueue.addAll(res)
                    if(depQueue.isNotEmpty()) {
                        break
                    }
                }
            }
        }
    }


    private inner class SchedulingTask(
        private val v: Parallel<*>,
        private val cancelFlag: Boolean,
        val nextTask: SchedulingTask? = null,
        private val inQueue : Boolean = false
    ) : RecursiveTask<Unit>() {

        private fun checkCancellation() {
            if(cancelFlag && ForkJoinTask.inForkJoinPool() && ForkJoinTask.getPool().isShutdown) {
                throw TimeCheckException()
            }
        }

        /** used for preventing the rerunning of the same task twice in the `TaskQueue` case */
        private val started = AtomicBoolean(false)

        /**
         * Entry point, we should only have this called once (via [ForkJoinTask.fork]).
         *
         * Invoking this function roughly corresponds to calling [Parallel.queueSchedule]. Recall however
         * that when [Parallel.queueSchedule] completes that does *not* mean the [Parallel] itself is done,
         * and the same caveat applies to this method.
         */
        public override fun compute() {
            checkCancellation()
            //  We know we are now running, so we can submit the next task to the queue.
            nextTask?.fork()
            computeWithoutFork()
        }

        /** returns true if this ends up actually running, and false if it was already started by another thread. */
        fun computeWithoutFork() : Boolean {
            checkCancellation()

            if (inQueue && started.getAndSet(true)) {
                // When a `TaskQueue` is running, each task is run twice - once by the fork and once by the main thread.
                // Only one of these needs to actually run.
                return false
            }

            /*
             * Are we runnable? I.e., have all of our dependencies completed?
             */
            if(!v.runnable) {
                val deps = v.deps
                /*
                 * If we have empty dependencies, then we are being scheduled multiple times. That's a no-no.
                 */
                check(deps.isNotEmpty())


                invokeAll(
                    deps.map { SchedulingTask(it, cancelFlag) }
                )
            }
            checkCancellation()
            val d = v.queueSchedule(ioPool)
            if(d.isNotEmpty()) {
                checkCancellation()
                try {
                    invokeAll(
                        d.map { SchedulingTask(it, cancelFlag || v is CancellableJob) }
                    )
                } catch(t: Throwable) {
                    if(v is CancellableJob) {
                        v.signalError(t)
                    } else {
                        throw t
                    }
                }
            }
            check(v.done())
            return true
        }

    }

    fun <T> run(f: Scheduler.() -> Parallel<T>) : T {
        val x = Scheduler.f()
        return run(x)
    }

    fun <T> run(x: Parallel<T>): T {
        if (x is CompleteJob) {
            return x.result
        }

        fun exceptional(submit: ForkJoinTask<*>, e: Exception) {
            logger.warn(e) { "Failed to run task $x" }
            // signal cancellable tasks to halt immediately
            workerThreadPool.shutdown()
            // stop the RPC thread pool for long running computations (read: Z3 jobs)
            ioPool.shutdownNow()
            // await the finish
            submit.get()
            // clean up the pool
            this.close()
        }

        return if(System.getProperty("cvt.simple.parallel") != null) {
            SimpleScheduler().resolve(x)
            x.result
        } else {
            val submit = workerThreadPool.submit(SchedulingTask(x, false))
            try {
                submit.get()
            } catch(i: InterruptedException) {
                exceptional(submit, i)
            }
            return x.result

        }
    }

    override fun close() {
        workerThreadPool.shutdownNow()
        workerThreadPool.awaitQuiescence(10L, TimeUnit.MILLISECONDS)
        ioPool.shutdownNow()
        scopeMap.values.forEach { it.close() }
    }

    sealed class SpawnPolicy {
        object GLOBAL : SpawnPolicy()
        object FAIL : SpawnPolicy()
        data class New(val f: () -> ParallelPool) : SpawnPolicy() {
            companion object {
                operator fun invoke() = New { ->
                    ParallelPool()
                }
            }
        }
    }

    companion object {
        fun isPoolCancelled() = Thread.interrupted() || (Thread.currentThread() as? ForkJoinWorkerThread)?.pool?.isShutdown == true

        val globalPool by lazy {
            ParallelPool(
                    forkJoinPool = ForkJoinPool.commonPool(),
                    ioPool = ThreadPoolExecutor(
                            1, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
                            ThreadFactory {
                                Executors.defaultThreadFactory().newThread(it).also {
                                    it.isDaemon = true
                                }
                            }
                    ) as ExecutorService
                )
        }

        /**
         * [poolF] inherits the current thread's pool if the current thread is a [ParallelPoolWorkerThread],
         * otherwise uses the pool specified via the [spawnPolicy].
         */
        inline fun <T> inherit(spawnPolicy: SpawnPolicy, poolF: (ParallelPool) -> T) : T {
            val t = Thread.currentThread()
            return if(t is ParallelPoolWorkerThread) {
                poolF(t.parallelPool)
            } else {
                when(spawnPolicy) {
                    SpawnPolicy.GLOBAL -> poolF(globalPool)
                    SpawnPolicy.FAIL -> throw IllegalStateException("Expected that this call to runInherit occurred within a parallel computation; it is not")
                    is SpawnPolicy.New -> spawnPolicy.f().use(poolF)
                }
            }
        }

        /**
         * Runs the [task] in the current thread's pool if it is a [ParallelPoolWorkerThread],
         * otherwise uses the pool specified via the [spawnPolicy].
         */
        fun <T> runInherit(task: Parallel<T>, spawnPolicy: SpawnPolicy): T {
            val poolF = { pool: ParallelPool ->
                pool.run(task)
            }
            return inherit(spawnPolicy, poolF)
        }

        @JvmName("runInheritT")
        // We need the above annotation to prevent the two [runInherit] declarations from having the same
        // JVM signature
        fun <T> Parallel<T>.runInherit(policy: SpawnPolicy = SpawnPolicy.New()): T = runInherit(this, policy)

        fun fromExecutor(executor: Executor) = ((executor as? ForkJoinPool)?.getFactory() as? PoolFactory)?.parallelPool

        private val GLOBAL_TIMERS_THREAD_COUNT = System.getProperty("cvt.timer.threads")?.toIntOrNull() ?: 1
        private val SCOPED_ALLOC_CLOSE_DELAY = System.getProperty("cvt.scoped.alloc.close.delay")?.toLongOrNull() ?: 10

        private val globalTimers = Executors.newScheduledThreadPool(GLOBAL_TIMERS_THREAD_COUNT) {
            Executors.defaultThreadFactory().newThread(it).also { thread ->
                thread.isDaemon = true
            }
        }

        fun <T: Closeable, R, U> allocInScope(k: ResourceKey<T, U>, mk: (U) -> T, f: (T) -> R) : R {
            val t = Thread.currentThread()
            if(t !is ParallelPoolWorkerThread) {
                return mk(k.args).use {
                    f(it)
                }
            }
            val pool = t.parallelPool

            val entry = pool.scopeMap.computeIfAbsent(k) {
                ScopeMapEntry { mk(k.args) }
            }.uncheckedAs<ScopeMapEntry<T>>()

            return entry.use(f)
        }

        /**
         * Run the call-back [f] with a closeable, reusable results of type [T], potentially reusing an existing instance used
         * in a different thread. Clients must only use the resource passed into [f] within the body of [f], no guarantees
         * are made about the liveness of the object after [f] completes.
         *
         * If this code is called from outside a Parallel pool worker, it is equivalent to: `mk(args).use { f(it) }`.
         *
         * Inside a ParallelPool, if no resource of type [T], with [args] as one of the constructor arguments, is associated with
         * the parallel pool then a new instance is created via [mk] and [args]. Otherwise, the (singleton) extant
         * resource associated with the pool is re-used.
         * The resource created by [mk] may or may not be closed after [f] completes, depending on the existence of
         * other users of the resource in other threads.
         * However, provided the resource is only ever accessed within [f], it is guaranteed that the resource will be closed
         * before the parallel pool is closed.
         *
         * Resources are disambiguated by their Java class and the provided constructor parameters [args],
         * so calling this function while a resource of the same
         * type and constructor parameters is alive will re-use that resource, even if it was created with a different implementation of [mk], i.e.,
         * with different options, constructor parameters other than [args] etc.
         *
         * It also should go without saying that the resource produced by [mk] must be thread-safe for any sort of sane behavior.
         */
        inline fun <reified T: Closeable, R, U> allocInScope(args: U, noinline mk: (U) -> T, noinline f: (T) -> R) : R = allocInScope(ResourceKey(T::class.java, args), mk, f)
        inline fun <reified T:Closeable, R> allocInScope(noinline  mk: (Nothing?) -> T, noinline  f: (T) -> R) : R = allocInScope(null, mk, f)
    }

    /**
     * ResourceKey is the class which helps to disambiguate objects under [allocInScope]. The objects are disambiguated
     * by their java class [klass] and the user specified constructor parameters [args].
     */
    data class ResourceKey<T, U>(
      val klass: Class<T>,
      val args: U
    )

    private val scopeMap = ConcurrentHashMap<ResourceKey<*, *>, ScopeMapEntry<*>>()

    /**
        See [allocInScope].  A ScopeMapEntry holds a resource of type [T], which is created on demand, and closed
        after a delay if it is not used.  All such resources are forcibly closed when the parallel pool is closed.
        */
    private class ScopeMapEntry<T : Closeable>(val mk: () -> T) {
        private var value: T? = null
        private var refCount: Int = 0
        private var closer: ScheduledFuture<*>? = null

        fun <R> use(f: (T) -> R): R {
            synchronized(this) {
                // Do we need to (re)create the resource?
                if (value == null) {
                    value = mk()
                }
                // If we were scheduled to close, cancel that
                closer?.cancel(false)
                closer = null
                // Bump the reference count for the duration of the call to [f]
                refCount++
            }
            try {
                // Call [f] with the resource
                return f(value!!)
            } finally {
                synchronized(this) {
                    // Release the reference count
                    refCount--
                    if (refCount == 0) {
                        // Delay closing the resource, in case we need it again soon
                        closer = globalTimers.schedule(::closeIfUnused, SCOPED_ALLOC_CLOSE_DELAY, TimeUnit.SECONDS)
                    }
                }
            }
        }

        fun closeIfUnused() {
            synchronized(this) {
                if (refCount == 0) {
                    closer?.cancel(false)
                    closer = null
                    value?.close()
                    value = null
                }
            }
        }

        fun close() {
            check(refCount == 0) { "Resource still in use: $value" }
            closeIfUnused()
        }
    }
}
