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

import allocator.Allocator
import bridge.ContractInstanceInSDC
import bridge.ImmutableReference
import bridge.NamedContractIdentifier
import cache.*
import datastructures.stdcollections.*
import decompiler.Disassembler
import log.Logger
import log.LoggerTypes
import parallel.ParallelPool
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler.compute
import parallel.forkEvery
import parallel.pcompute
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import tac.ICoreTACProgram
import tac.StorageUniverse
import utils.*
import java.io.ObjectOutputStream
import java.math.BigInteger
import kotlin.reflect.KProperty

private val logger = Logger(LoggerTypes.CACHE)

/*
    The cache state represents the "history" of the scene's transformations. Upon each mapMethod call the cache key
    is combined with the transformation id to give a (unique-ish) id of the entire transformation chain leading up
    to that point, seeded with a hash of all contract inputs (something something block chain).

    Classes extending this should use care to update cacheState on calls to mapContractMethods (although the
    default implementation provided by mapWithCache will handle this for you).
 */
abstract class AbstractCachingScene(
    protected var cacheState: CacheKey,
    protected val cacheManager: ICacheManager,
    protected val cachePolicy: CachePolicy,
    protected var history: TransformationHistory,
    addressResolution: Map<BigInteger, BigInteger>
) : IDynamicScene, ExtendableScene {
    /**
     Resolve aliased addresses (as determined by [ICacheAwareSource.aliases] to a distinct representative address
     */
    protected val addressResolution: MutableMap<BigInteger, BigInteger> = addressResolution.toMutableMap()

    protected abstract val contractByInstanceIdBacking: MutableMap<BigInteger, IContractClass>

    @Suppress("LeakingThis")
    private val stateManager = cacheManager.getObjectStateManager(cacheState, obj = this, save = {
        this.contractByInstanceIdBacking.mapValues { (_, c) -> c.saveState() }
    }, restore = { upd ->
        for ((addr, mem) in upd) {
            (this.contractByInstanceIdBacking[addr]
                ?: throw IllegalStateException("Broken cache: no contract for addr")).restore(mem)
        }
        CVTAlertReporter.reportAlert(
            CVTAlertType.CACHE,
            CVTAlertSeverity.INFO,
            null,
            "Contract preprocessing step fetched from cache.",
            null
        )
    })

    protected val contractByInstanceId: MutableMap<BigInteger, IContractClass> by object {
        /* To access [contractByInstanceIdBacking], one need to invoke [stateManager.force()].
        Thus, it is ensured that [contractByInstanceIdBacking] is up-to-date before being accessed. */
        operator fun getValue(scene: AbstractCachingScene, prop: KProperty<*>): MutableMap<BigInteger, IContractClass> {
            stateManager.force()
            return contractByInstanceIdBacking
        }
    }

    private val nameToInstanceId: MutableMap<String, BigInteger> by lazy {
        contractByInstanceId.entries.associateTo(mutableMapOf()) {
            it.value.name to it.key
        }
    }

    private val allContracts : List<IContractClass> get() = contractByInstanceId.values.toList()

    override fun getContractOrNull(s: NamedContractIdentifier): IContractClass? {
        return nameToInstanceId[s.name]?.let(contractByInstanceId::get)
    }

    override fun getContract(s: NamedContractIdentifier): IContractClass {
        return getContractOrNull(s) ?: throw SceneQueryException("Contract with name $s is not in scene")
    }

    override fun getContractOrNull(a: BigInteger): IContractClass? {
        return addressResolution[a]?.let { contractByInstanceId[it] }
    }

    override fun getContract(a: BigInteger): IContractClass {
        return getContractOrNull(a) ?: throw SceneQueryException("Contract with address $a is not in the scene")
    }

    override fun getMethods(sigHash: BigInteger): List<ITACMethod> {
        val toReturn = mutableListOf<ITACMethod>()
        for(c in allContracts) {
            toReturn.add(c.getMethodBySigHash(sigHash) ?: continue)
        }
        return toReturn
    }

    override fun hasMethod(contractId: BigInteger, sigHash: BigInteger): Boolean =
            getContractOrNull(contractId)?.getMethodBySigHash(sigHash) != null

    override fun hasMethod(contractName: NamedContractIdentifier, sigHash: BigInteger): Boolean =
            getContractOrNull(contractName)?.getMethodBySigHash(sigHash) != null

    override fun getStorageUniverse(): StorageUniverse = contractByInstanceId.values.associate {
        it.instanceId to it.storage
    }

    override fun getTransientStorageUniverse(): StorageUniverse = contractByInstanceId.values.associate {
        it.instanceId to it.transientStorage
    }

    override fun getContracts(): List<IContractClass> = allContracts

    override fun extendContractWith(target: NamedContractIdentifier, m: List<TACMethod>) {
        val loc = getContract(target).instanceId
        val toExt = contractByInstanceId[loc] ?: error("$loc should be in the mapping")
        contractByInstanceId[loc] = toExt.extendWith(m)
    }

    private fun <T> mapWithCache(mapSort: IScene.MapSort, transformId: String, cb: IContractClass.(IScene, T) -> Unit, p: T) {
        history = TransformationHistory.Transform(transformId, history)
        val ext = cacheState.extend(transformId)
        if(!cachePolicy.useCache(history)) {
            stateManager.force()
            for(c in contractByInstanceId.values) {
                c.cb(this, p)
            }
        } else {
            mapWithCacheManager(mapSort, ext, cb, p)
        }
        cacheState = ext
    }

    override fun mapScene(transformId: String, p: () -> Unit) {
        history = TransformationHistory.Transform(transformId, history)
        val ext = cacheState.extend(transformId)
        if(!cachePolicy.useCache(history)) {
            p()
        } else {
            stateManager.transform(ext, p)
        }
        cacheState = ext
    }

    private fun <T> mapWithCacheManager(mapSort: IScene.MapSort, ext: CacheKey, cb: IContractClass.(IScene, T) -> Unit, p: T) {
        stateManager.transform(ext) {
            if(mapSort == IScene.MapSort.PARALLEL) {
                contractByInstanceId.values.forkEvery {
                    compute {
                        it.cb(this, p)
                    }
                }.pcompute().runInherit(ParallelPool.SpawnPolicy.GLOBAL)
            } else {
                for (c in contractByInstanceId.values) {
                    c.cb(this, p)
                }
            }
        }
    }

    override fun mapContractMethodsInPlace(sort: IScene.MapSort, transformId: String, p: (IScene, ITACMethod) -> Unit) {
        this.mapWithCache(sort, transformId,  { scene, e ->
            this.mapMethodsInPlace(sort, scene, e)
        }, p)
    }

    override fun mapContractMethods(
        sort: IScene.MapSort,
        transformId: String,
        p: (IScene, ITACMethod) -> ICoreTACProgram
    ) {
        this.mapWithCache(sort, transformId, {scene, e ->
             this.mapMethods(sort, scene, e)
         }, p)
    }

    override fun mapContractsInPlace(sort: IScene.MapSort, transformId: String, p: (IScene, IContractClass) -> Unit) {
        this.mapWithCache(sort, transformId, { scene, _ ->
            p(scene, this)
        }, Unit)
    }

    override fun getPrecompiledContracts(): List<IContractClass> {
        return getPrecompiled(getContractUniverse())
    }

    override fun cloneContract(contract: IContractClass): IClonedContract {
        val transform = TransformationHistory.Clone(contract.instanceId, prev = this.history)

        val cacheExt = this.cacheState.extend("clone!${contract.instanceId}")
        val tgtAddr = getFreshInstanceId()
        val cloned = if(cachePolicy.useCache(transform)) {
            cacheManager.withCache(cacheExt, missing = {
                contract.cloneTo(tgtAddr)
            }, toCache = {
                 it to Allocator.saveState()
            }, fromCache = {
                Allocator.restore(it.second)
                it.first
            }, toResult = {
                it
            })
        } else {
            contract.cloneTo(tgtAddr)
        }
        addressResolution[tgtAddr] = tgtAddr
        nameToInstanceId[cloned.name] = tgtAddr
        contractByInstanceId[tgtAddr] = cloned
        history = transform
        cacheState = cacheExt
        return cloned
    }
}

private class ForkedCachingScene(
    cacheKey: CacheKey, cacheManager: ICacheManager,
    addressResolution: Map<BigInteger, BigInteger>,
    override val contractByInstanceIdBacking: MutableMap<BigInteger, IContractClass>,
    override val forkInfo: IScene.ForkInfo,
    cachePolicy: CachePolicy,
    history: TransformationHistory,
) : AbstractCachingScene(cacheKey.extend(forkInfo.toString()), cacheManager, cachePolicy, history, addressResolution = addressResolution) {
    override fun fork(forkInfo: IScene.ForkInfo): IScene {
        val mut = mutableMapOf<BigInteger, IContractClass>()
        contractByInstanceId.mapValuesTo(mut) { it.value.fork() }
        return ForkedCachingScene(
            cacheState, cacheManager,
            addressResolution,
            mut,
            forkInfo,
            cachePolicy,
            history
        )
    }
}

private val immutableReferenceComparator =
    Comparator.comparingInt(ImmutableReference::length).thenComparingInt(ImmutableReference::offset)
        .thenComparing(ImmutableReference::varname).thenComparing(ImmutableReference::value)

private fun metadatalessRuntimeBytecode(it: ContractInstanceInSDC) =
    Disassembler.disassembleRuntimeBytecode(it).bytes.toByteArray()

private fun metadatalessConstructorBytecode(it: ContractInstanceInSDC) =
    Disassembler.disassembleConstructorBytecode(it).code.toByteArray()

private fun hashContractConfig(outputStream: ObjectOutputStream, it: ContractInstanceInSDC) {
    logger.debug { "chaining ${it.address.toString(16)}" }
    outputStream.writeObject(it.address)

    logger.debug { "chaining ${it.name}" }
    outputStream.writeChars(it.name)

    outputStream.writeObject(metadatalessRuntimeBytecode(it).also {
        logger.debug { "chaining $it" }
    })
    outputStream.writeObject(metadatalessConstructorBytecode(it).also {
        logger.debug { "chaining $it" }
    })

    outputStream.writeObject(it.state.toSortedMap().also {
        logger.debug { "chaining $it" }
    })

    logger.debug { "chaining ${it.srcmap}" }
    outputStream.writeObject(it.srcmap)

    outputStream.writeObject(it.prototypeFor.sorted().also {
        logger.debug { "chaining $it" }
    })
    outputStream.writeObject(it.immutables.sortedWith(immutableReferenceComparator).also {
        logger.debug { "chaining $it" }
    })
    outputStream.writeObject(it.structLinkingInfo.toSortedMap().also {
        logger.debug { "chaining $it" }
    })
}


private fun computeSceneKey(baseCacheKey: BigInteger, instances: List<ContractInstanceInSDC>): String {
    return withStringDigest { outputStream ->
        logger.debug { "chaining $baseCacheKey" }
        outputStream.writeObject(baseCacheKey)
        instances.sortedBy { it.address }.forEach {
            logger.debug { "adding instance ${it.address.toString(16)}" }
            hashContractConfig(outputStream, it)
        }
    }.also { logger.debug { "Scene cache key is $it" } }
}

class CachingScene(
    source: ICacheAwareSource,
    loader: IContractLoader,
    cacheManager: ICacheManager,
    cachePolicy: CachePolicy
) : AbstractCachingScene(
    cacheState = CacheKey(
        computeSceneKey(
            source.baseCacheKey,
            source.instances()
        )
    ).also { logger.debug { "Initial scene cache key is $it" } },
    cacheManager = cacheManager,
    cachePolicy = cachePolicy,
    history = TransformationHistory.InitialLoad,
    addressResolution = run {
        val map = mutableMapOf<BigInteger, BigInteger>()
        source.aliases().forEach {
            check(it.isNotEmpty()) {
                "Empty equivalence class"
            }
            val f = it.first()
            for(b in it) {
                check(b !in map) {
                    "Broken equivalence classes"
                }
                map[b] = f
            }
        }
        getPrecompiled(source.contractUniverse()).forEach { precomp ->
            map.merge(
                precomp.instanceId,
                precomp.instanceId
            ) { old, _ -> error("cannot have aliasing with a precompiled $old") }
        }
        map
    }
) {
    override val contractByInstanceIdBacking: MutableMap<BigInteger, IContractClass> by lazy {
        val map = mutableMapOf<BigInteger, IContractClass>()
        for(l in loadedContracts) {
            map[addressResolution[l.instanceId]!!] = l
        }
        map
    }

    private val loadedContracts: List<IContractClass> by lazy {
        val res = source.instances().forkEvery { handle ->
            compute {
                val key = CacheKey(withStringDigest {
                    logger.debug { "Source base cache key ${source.baseCacheKey.toString(16)}" }
                    it.writeObject(source.baseCacheKey)
                    hashContractConfig(it, handle)
                })
                logger.debug { "Contract cache key for ${handle.name} is $key" }
                val doLoad by lazy {
                    loader.load(handle, object : IPerContractClassCache {
                        override fun <T> load(name: ContractLoad.Component, provider: () -> T): T {
                            return if (cachePolicy.useCache(ContractLoad(handle.name, name))) {
                                cacheManager.withCache(key.extend(name.toString()),
                                    missing = provider,
                                    toCache = {
                                        it to Allocator.saveState()
                                    },
                                    fromCache = { (t, mem) ->
                                        Allocator.restore(mem)
                                        t
                                    },
                                    toResult = {
                                        it
                                    }
                                )
                            } else {
                                provider()
                            }
                        }
                    })
                }
                if (this.cachePolicy.useCache(TransformationHistory.InitialLoad)) {
                    cacheManager.withCache(key, missing = {
                        doLoad
                    }, fromCache = { (c, a) ->
                        Allocator.restore(a)
                        c
                    }, toCache = {
                        it to Allocator.saveState()
                    }, toResult = {
                        it
                    })
                } else {
                    doLoad
                }
            }
        }.pcompute().runInherit()

        res + getPrecompiledContracts()
    }

    override val forkInfo: IScene.ForkInfo = IScene.ForkInfo.BASE

    override fun fork(forkInfo: IScene.ForkInfo): IScene =
        ForkedCachingScene(
            cacheKey = cacheState,
            contractByInstanceIdBacking = contractByInstanceId.toMutableMap(),
            addressResolution = addressResolution,
            cacheManager = cacheManager,
            forkInfo = forkInfo,
            cachePolicy = cachePolicy,
            history = history,
        )
}
