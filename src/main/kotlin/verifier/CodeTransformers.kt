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

package verifier

import config.Config
import config.ReportTypes
import diagnostics.*
import log.*
import scene.ITACMethod
import scene.NamedCode
import statistics.ANALYSIS_SUCCESS_STATS_KEY
import statistics.ElapsedTimeStats
import statistics.recordSuccess
import statistics.toTimeTag
import tac.DebuggableProgram
import tac.DumpTime
import testing.TacPipelineDebuggers.twoStateInvariant
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACProgram
import verifier.ContractUtils.recordAggregatedTransformationStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private val logger = Logger(LoggerTypes.COMMON)
private val loggerTimes = Logger(LoggerTypes.TIMES)

private val transformThrottles = ConcurrentHashMap<ReportTypes, Semaphore>()

/**
 * Code-to-code transformation consists of:
 * - a ReportType we may use for debugging the transformation's output
 * - An actual transformer from source program class [T] to target program class [R]
 */
open class CodeTransformer<T : NamedCode<ReportTypes>, R : DebuggableProgram<ReportTypes>>(
    val reportType: ReportTypes,
    val transformer: (T) -> R
) {
    fun applyTransformer(t: T): R {
        val throttle = transformThrottles.computeIfAbsent(reportType) { Semaphore(Config.concurrencyLimit(reportType)) }
        throttle.withPermit {
            return inTransform(reportType) {
                val tag = reportType.name.toTimeTag()
                val timeElapser = ElapsedTimeStats().startMeasuringTimeOf(tag)
                ArtifactManagerFactory().dumpCodeArtifacts(t, reportType, DumpTime.PRE_TRANSFORM)
                transformer(t).also { transformed ->
                    timeElapser.endMeasuringTimeOf(tag)
                    ArtifactManagerFactory().dumpCodeArtifacts(transformed, reportType, DumpTime.POST_TRANSFORM)
                    if (t is TACProgram<*> && transformed is TACProgram<*>) {
                        twoStateInvariant(t, transformed, reportType)
                    }
                    recordAggregatedTransformationStats(timeElapser, t.myName())
                }
            }
        }

    }
}

open class CodeAnalysis<T: NamedCode<ReportTypes>, R>(
    val analysisName: String,
    val analyzer: (T) -> R,
    val successCriteria: (R) -> Boolean
) {
    fun runAnalysis(t: T): R {
        val tag = analysisName.toTimeTag()
        val timeElapser = ElapsedTimeStats().startMeasuringTimeOf(tag)
        return analyzer(t).also {
            timeElapser.endMeasuringTimeOf(tag)
            recordAggregatedTransformationStats(timeElapser, t.myName())
            // all analysis successes are recorded together under the ANALYSIS_SUCCESS_STATS_KEY key
            recordSuccess(t.myName(), ANALYSIS_SUCCESS_STATS_KEY, analysisName, successCriteria(it))
        }
    }
}

/**
 * A transformer from a [ITACMethod] (which is [CoreTACProgram] + meta info) to [CoreTACProgram]
 */
class MethodToCoreTACTransformer<T: ITACMethod>(reportType: ReportTypes, transformer: (T) -> CoreTACProgram) :
    CodeTransformer<T, CoreTACProgram>(reportType, transformer)

/**
 * TAC-to-TAC transformer, that can be [lift]ed to a Method-to-TAC transformer.
 */
class CoreToCoreTransformer(reportType: ReportTypes, transformer: (CoreTACProgram) -> CoreTACProgram) :
    CodeTransformer<CoreTACProgram, CoreTACProgram>(reportType, transformer) {
    private fun <T: ITACMethod> lift(f: (T) -> CoreTACProgram) =
        MethodToCoreTACTransformer(
                reportType
        ) { x: T ->
            transformer(f(x))
        }

    fun <T: ITACMethod> lift() = lift<T> { it.code as CoreTACProgram }
}

/**
 * A collection of transformers that should be executed sequentially.
 */
abstract class ChainedCodeTransformers<T: CodeTransformer<*, *>>(val l: List<T>) {
    fun getReports() = l.map { it.reportType }
}

class ChainedMethodTransformers<T: ITACMethod>(l: List<MethodToCoreTACTransformer<T>>):
    ChainedCodeTransformers<MethodToCoreTACTransformer<T>>(l)

// TODO: fold this into transformMethod too because there's duplication
class ChainedCoreTransformers(l: List<CoreToCoreTransformer>): ChainedCodeTransformers<CoreToCoreTransformer>(l) {
    fun transform(c: CoreTACProgram): CoreTACProgram {
        logger.info { "Running on ${c.name} the following transformations: ${getReports()}" }

        val tacTransformerTimeStatsRecorder = ElapsedTimeStats()
        unused(tacTransformerTimeStatsRecorder)
        return l.fold(c) { C, t ->
            try {
                loggerTimes.info { "Start Running transform ${t.reportType} on ${c.name}" }
                val start = System.currentTimeMillis()
                t.applyTransformer(C).also {
                    val end = System.currentTimeMillis()
                    loggerTimes.info { "End Running transform ${t.reportType} on ${c.name} ${(end - start) / 1000}s" }

                }
            } catch (e: Exception) {
                throw CertoraException.fromException(e, "Got exception while transforming ${c.name}")
            }
        }
    }
}
