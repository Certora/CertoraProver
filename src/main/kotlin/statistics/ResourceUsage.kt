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

package statistics

import datastructures.stdcollections.*
import log.*
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import utils.*
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.concurrent.timer

/**
 * Metric base class. Provides `put()` to store data in the stats collector. Requires implementation of `tick()`, which
 * should store a new value via `put()`.
 */
private abstract class Metric {
    private val collector = SDCollectorFactory.collector()
    protected fun <T: Serializable> put(name: String, v: T) = collector.recordAny(v, name, "resource-usage")
    abstract fun tick()
}

/**
 * Simple metric to store the current time. This is used to correlate all other metrics to a point in time.
 */
private class TimeMetric: Metric() {
    private val start = Instant.now()
    init {
        val jvmStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime)
        put("time-jvm-start", jvmStart.toString())
        put("time-start", start.toString())
    }
    override fun tick() = put("time", java.time.Duration.between(start, Instant.now()).truncatedTo(ChronoUnit.MILLIS).toString())
}

/**
 * Collect some CPU related metrics: the total number of cores, the current CPU usage, the number of running threads and
 * the total number of started threads.
 */
private class CPUMetric: Metric() {
    private val numCores = ManagementFactory.getOperatingSystemMXBean().availableProcessors
    private val osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
    private val threadBean = ManagementFactory.getThreadMXBean()
    init { put("cpu-num-cores", numCores) }
    override fun tick() {
        put("cpu-load", osBean.cpuLoad * numCores)
        put("num-threads", threadBean.threadCount)
        put("num-threads-started", threadBean.totalStartedThreadCount)
    }
}

/**
 * Collect some memory related metrics: the total amount of memory, the initial memory usage, the current system memory
 * usage and the current memory usage of the JVM.
 */
private class MemMetric: Metric() {
    fun Long.toMB() = this / (1024 * 1024)
    private val osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
    private val memBean = ManagementFactory.getMemoryMXBean()
    private val totalMemSize = osBean.totalMemorySize
    private val warnThreshold = minOf(totalMemSize / 128, 64*1024*1024)
    private var inWarningMode = false
    init {
        put("mem-total-mb", totalMemSize.toMB())
        put("mem-initial-mb", (totalMemSize - osBean.freeMemorySize).toMB())
    }
    override fun tick() {
        put("system-mem", (totalMemSize - osBean.freeMemorySize).toMB())
        put("vm-mem", (memBean.heapMemoryUsage.used + memBean.nonHeapMemoryUsage.used).toMB())

        if (!inWarningMode && osBean.freeMemorySize < warnThreshold) {
            inWarningMode = true
            CVTAlertReporter.reportAlert(
                CVTAlertType.OUT_OF_RESOURCES,
                CVTAlertSeverity.WARNING,
                null,
                "Extremely low available memory: ${osBean.freeMemorySize.toMB()}MB out of a total of ${totalMemSize.toMB()}MB are left. The prover likely crashes soon and results will be incomplete.",
                null,
                CheckedUrl.OUT_OF_MEMORY,
            )
        } else if (inWarningMode && osBean.freeMemorySize > warnThreshold * 8) {
            inWarningMode = false
            val msg = "Available memory has recovered: ${osBean.freeMemorySize.toMB()}MB out of a total of ${totalMemSize.toMB()}MB are left."
            Logger.always(msg, false)
        }
    }
}

/**
 * Start the resource usage collection in the background via [timer].
 */
fun startResourceUsageCollector() {
    val metrics = listOf(
        TimeMetric(),
        CPUMetric(),
        MemMetric()
    )
    timer("ResourceUsageCollector", daemon = true, period = 1000) {
        metrics.forEach {
            try {
                it.tick()
            } catch (e: Throwable) {
                Logger.alwaysError("Collecting metric ${it.javaClass.name} failed, its data is possibly out of sync", e)
            }
        }
    }
}
