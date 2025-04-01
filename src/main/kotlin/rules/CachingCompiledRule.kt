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

package rules

import cache.CacheKey
import cache.ICacheManager
import config.ReportTypes
import datastructures.stdcollections.*
import kotlin.time.measureTimedValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import log.Logger
import log.LoggerTypes
import report.LiveStatsReporter
import rules.IsFromCache.*
import scene.SceneIdentifiers
import solver.SolverResult
import spec.rules.CVLSingleRule
import statistics.IStatsJson
import statistics.IStatsJson.Companion.collect
import statistics.IStatsJson.Companion.totalMs
import statistics.StatsValue
import utils.Denominator
import utils.Numerator
import vc.data.CoreTACProgram
import verifier.Verifier

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Caching status of this compiled rule.
 * Used to calculate the percentage of cache-hits where it's relevant.
 * [HIT] represents a cache-hit.
 * [MISS] represents a cache-miss.
 * [INAPPLICABLE] represents rules we do not want to take into account while evaluating cache statics
 * (such as sanity rules, builtin rules).
 * [DISABLED] represents a state where the cache is disabled.
 */
enum class IsFromCache(val ratio: Pair<Numerator, Denominator>) {
    HIT(1 to 1), MISS(0 to 1), INAPPLICABLE(0 to 0), DISABLED(
        0 to 0
    )
}

/**
 * Extends [CompiledRule] to save intermediate optimized TAC and non SAT results
 */
class CachingCompiledRule(
    rule: CVLSingleRule,
    tac: CoreTACProgram,
    baseCacheKey: String,
    private val nonCanonicalTable: normalizer.NonCanonicalTranslationTable,
    private val cache: ICacheManager,
    liveStatsReporter: LiveStatsReporter,
) :
    CompiledRule(rule, tac, liveStatsReporter) {

    private val timedDigest = measureTimedValue {
        tac.digest()
    }

    private val digest = timedDigest.value
    private val solverResponseKey = CacheKey("solver-response-$baseCacheKey-$digest")
    private val optimizedTACKey = CacheKey("optimize-TAC-$baseCacheKey-$digest")

    private val stats = CompiledRuleStats(
        mutableMapOf(
            CompiledRuleStats.Key.RuleName to StatsValue.Str(tac.name),
            CompiledRuleStats.Key.Digest to StatsValue.Str(digest),
            CompiledRuleStats.Key.DigestMs to StatsValue.Ms(timedDigest),
        )
    )

    @Suppress("SuspendFunSwallowedCancellation") // mapCheckResult deals with CancellationException
    override suspend fun check(scene: SceneIdentifiers, nonLocalOptimizationsOnly: Boolean): CompileRuleCheckResult {
        val checkResult = runCatching(::getCachedResult).transpose()?.mapCatching { response ->
            logger.info { "Compiled rule ${rule.declarationId}, digest: $digest response was cache" }
            dumpPreOptimize()
            val simpleSimpleSSATAC =
                nonCanonicalTable.patch(response.simpleSimpleSSATAC).also(::dumpPostOptimized)
            ResultAndTime(response.replaceTAC(simpleSimpleSSATAC), VerifyTime.None)
            // FIXME: isOptimizedRuleFromCache should probably not be a HIT here. Change this after [CERT-2896] lands
        }?.mapCheckResult(
            isOptimizedRuleFromCache = if(existsCachedTAC()) { IsFromCache.HIT } else { IsFromCache.MISS },
            isSolverResultFromCache = IsFromCache.HIT
        ) ?: super.check(scene, nonLocalOptimizationsOnly)

        stats.totalMs().defaults().collect()

        return checkResult
    }

    override suspend fun optimize(
        scene: SceneIdentifiers,
        tacToCheck: CoreTACProgram,
    ): CoreTACProgram {
        return getCachedTAC()?.let {
            logger.info { "Compiled rule ${rule.declarationId}, digest: $digest optimized-rule was cached" }
            nonCanonicalTable.patch(it)
        } ?: super.optimize(scene, tacToCheck).also { putTAC(it) }
    }

    override suspend fun optimizeNonLocal(
        tacToCheck: CoreTACProgram,
    ): CoreTACProgram {
        return getCachedTAC()?.let {
            logger.info { "Compiled rule ${rule.declarationId}, digest: $digest optimized-rule was cached" }
            nonCanonicalTable.patch(it)
        } ?: super.optimizeNonLocal(tacToCheck).also { putTAC(it) }
    }

    override suspend fun verify(
        scene: SceneIdentifiers,
        tacToCheck: CoreTACProgram
    ): Verifier.JoinedResult {
        return super.verify(scene, tacToCheck).also {
            // cache result only on definitive answer
            val cachedResults =
                it.finalResult == SolverResult.UNSAT || it.finalResult == SolverResult.SAT || it.finalResult == SolverResult.SANITY_FAIL
            // Do not cache results of unsat cores for now (see CERT-5757)
            if (cachedResults && !cache.exists(solverResponseKey)) {
                putResult(it.dropUnsatCoreData())
            }
        }
    }

    private fun getCachedResult() =
        stats.measure(CompiledRuleStats.Key.GetSolverResultMs) {
            cache.get<Verifier.JoinedResult>(solverResponseKey)
        }.also {
            stats.put(CompiledRuleStats.Key.SolverResultCacheUsed, StatsValue.Bool(true))
            stats.put(CompiledRuleStats.Key.HitSolverResult, StatsValue.Bool(it != null))
        }

    private fun putResult(result: Verifier.JoinedResult) =
        stats.measure(CompiledRuleStats.Key.PutSolverResultMs) { cache.put(solverResponseKey, result) }

    private fun getCachedTAC() =
        stats.measure(CompiledRuleStats.Key.GetOptimizeTacMs) { cache.get<CoreTACProgram>(optimizedTACKey) }
            .also {
                stats.put(CompiledRuleStats.Key.OptimizedTACCacheUsed, StatsValue.Bool(true))
                stats.put(CompiledRuleStats.Key.HitOptimizeTAC, StatsValue.Bool(it != null))
            }

    private fun existsCachedTAC() =
        stats.measure(CompiledRuleStats.Key.GetOptimizeTacMs) { cache.exists(optimizedTACKey) }
            .also {
                stats.put(CompiledRuleStats.Key.HitOptimizeTAC, StatsValue.Bool(it))
            }

    private fun putTAC(ctp: CoreTACProgram) =
        stats.measure(CompiledRuleStats.Key.PutOptimizeTacMs) { cache.put(optimizedTACKey, ctp) }

}

// convert a result of nullable-type into a nullable-result
private fun <T : Any> Result<T?>.transpose() =
    fold(onSuccess = { it?.let(Result.Companion::success) }, onFailure = { Result.failure(it) })


class CompiledRuleStats(override val stats: MutableMap<Key, StatsValue>) :
    IStatsJson<CompiledRuleStats.Key>() {
    @kotlinx.serialization.Serializable
    enum class Key {
        @SerialName("ruleName")
        RuleName,

        @SerialName("solverResultCacheUsed")
        SolverResultCacheUsed,

        @SerialName("hitSolverResult")
        HitSolverResult,

        @SerialName("getSolverResultMs")
        GetSolverResultMs,

        @SerialName("putSolverResultMs")
        PutSolverResultMs,

        @SerialName("optimizedTacCacheUsed")
        OptimizedTACCacheUsed,

        @SerialName("hit")
        HitOptimizeTAC,

        @SerialName("getMs")
        GetOptimizeTacMs,

        @SerialName("putMs")
        PutOptimizeTacMs,

        @SerialName("digest")
        Digest,

        @SerialName("digestMs")
        DigestMs,

        @SerialName("totalMs")
        TotalMs;
    }

    override val keySerializer: KSerializer<Key>
        get() = Key.serializer()
    override val totalMsKey: Key
        get() = Key.TotalMs
    override val collectKey: String = ReportTypes.COMPILED_RULE_CACHE.toString()

    fun defaults() = apply {
        for (key in Key.values().filter { it !in stats }) {
            stats[key] = when (key) {
                Key.RuleName, Key.Digest -> StatsValue.Str("unknown")
                Key.HitSolverResult, Key.HitOptimizeTAC, Key.OptimizedTACCacheUsed, Key.SolverResultCacheUsed -> StatsValue.Bool(false)
                Key.PutSolverResultMs, Key.PutOptimizeTacMs, Key.GetSolverResultMs, Key.GetOptimizeTacMs, Key.DigestMs , Key.TotalMs-> StatsValue.Ms(0)
            }
        }
    }
}
