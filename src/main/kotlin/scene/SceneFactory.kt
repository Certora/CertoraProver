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

package scene

import analysis.icfg.InternalCVLSummarizer
import bridge.ContractInstanceInSDC
import cache.CacheManager
import cache.CachePolicy
import cache.VerifyingCacheManager
import config.Config
import scene.loader.StandardProverContractLoader
import scene.source.DegenerateContractSource
import scene.source.IContractSourceFull
import scene.source.withCache
import spec.CVL
import java.math.BigInteger

object SceneFactory: ISceneFactory {
    override fun getScene(source: IContractSource, loader: IContractLoader) : IScene =
            if (Config.getIsUseCache()) {
                val manager = CacheManager(Config.CacheDirName.get())
                @Suppress("DEPRECATION") // toUpperCase
                CachingScene(cacheManager = manager, source = source.withCache(),
                        cachePolicy = Config.CachePolicyType.get().let(String::toUpperCase).let(CachePolicy.Standard::valueOf).policy, loader = loader)
            } else if (Config.VerifyCache.get()) {
                CachingScene(cacheManager = VerifyingCacheManager, source = object : ICacheAwareSource, IContractSource by source {
                    override val baseCacheKey: BigInteger
                        get() = BigInteger.ZERO
                }, cachePolicy = CachePolicy.Standard.ALWAYS.policy, loader = loader)
            } else {
                Scene(source = source, contractLoader = loader)
            }

    fun getScene(source: IContractSource, sceneType: SceneType, cvl: CVL?) = when (sceneType) {
        SceneType.PROVER -> getScene(source.withQuery(cvl), StandardProverContractLoader.withQuery(cvl))
    }

    // default scene is for Prover
    fun getScene(source: IContractSource) = getScene(source, StandardProverContractLoader)

    // no caching, because this is such as simple object
    fun getCVLScene(source: IContractSource) : ICVLScene {
        val contracts = source.instances().map { srcSdc ->
            object : IContractWithSource, ICVLContractClass {
                override val name: String
                    get() = srcSdc.name
                override val instanceId: BigInteger
                    get() = srcSdc.address
                override val src: ContractInstanceInSDC
                    get() = srcSdc
            }
        }
        return object : ICVLScene {
            override fun getContracts(): List<ICVLContractClass> = contracts
        }
    }

    // used for Solana, Soroban and Move flow
    val EMPTY_SCENE = SceneFactory.getScene(DegenerateContractSource("empty scene"))
}

private fun IContractSource.withQuery(cvl: CVL?): IContractSource {
    return if(cvl == null) {
        this
    } else if(this is IContractSourceFull) {
        object : IContractSourceFull by this@withQuery, ICacheAwareSource {
            override val baseCacheKey: BigInteger
                get() = InternalCVLSummarizer.EarlySummarization.computeSummaryDigest(cvl)
        }
    } else {
        object : ICacheAwareSource, IContractSource by this@withQuery {
            override val baseCacheKey: BigInteger
                get() = InternalCVLSummarizer.EarlySummarization.computeSummaryDigest(cvl)

        }
    }
}


