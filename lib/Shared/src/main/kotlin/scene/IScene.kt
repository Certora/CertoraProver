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

import bridge.NamedContractIdentifier
import scene.IScene.MapSort.PARALLEL
import scene.IScene.MapSort.SEQUENTIAL
import tac.*
import java.io.Serializable
import java.math.BigInteger
import datastructures.stdcollections.*

/**
 * The Scene is one of the main objects driving the CVT pipeline.
 * The Scene holds information regarding the codes that we check, not including spec information (for now).
 * It keeps a collection of contracts (using [IContractClass]) and methods ([ITACMethod]),
 * that hold both metadata information about the contract (resp. method) and their associated TAC objects.
 * Contracts can be identified using a name (the original contract name as given by the Solidity file name
 * or alias from certoraRun) or using an instanceId (a unique ID that we use to identify [IContractClass]s,
 * but _also_ serves as the "blockchain account identifier" of the contract (this may change in the future) =>
 * ===> instanceId *does not* serve as the "blockchain account identifier" anymore
 *
 * The Scene allows mutations to both contracts and methods.
 * These mutations are useful to implement optimizations over the TAC code.
 * Usually we use [mapContractMethods] or [mapContractMethodsInPlace].
 * Some mutations require modifying all methods "together" i.e. in a synchronized way, e.g. when data from one method
 * can affect another. In these cases we'll use [mapContractsInPlace] or [mapScene].
 *
 * One can save the state of the Scene by making a [fork] of it. The copy from [fork] will be a deep copy,
 * mutations on the copy do not affect the original Scene.
 *
 * Additionally, the Scene exposes info about the [StorageUniverse], i.e. the set of variables that comprise
 * the (persistent) state variables of the contracts.
 * We also keep a [ContractUniverse] and the so-called "precompiled contracts".
 * In Ethereum, precompiled contracts are low-address (0x0-0x9) contracts that are not implemented in EVM
 * but in the underlying VM directly (i.e., go or c++ code that all clients/miners run). Examples of precompiled
 * contracts are SHA3 and other hash functions, and ecrecover (a mechanism for finding the original signer address
 * of a message).
 */
interface IScene : ISceneIdentifiers {
    enum class ForkInfo(private val stringer: String) {
        BASE("base_scene"), CVL("CVL_scene");

        override fun toString(): String = stringer
    }

    val forkInfo: ForkInfo

    override fun getContract(s: NamedContractIdentifier): IContractClass = super.getContract(s) as IContractClass
    override fun getContract(a: BigInteger): IContractClass = super.getContract(a) as IContractClass

    override fun getContractOrNull(s: NamedContractIdentifier): IContractClass?
    override fun getContractOrNull(a: BigInteger): IContractClass?

    override fun getMethod(contractId: BigInteger, sigHash: BigInteger): ITACMethod =
        super.getMethod(contractId, sigHash) as ITACMethod

    override fun getMethod(contractName: NamedContractIdentifier, sigHash: BigInteger): ITACMethod =
        super.getMethod(contractName, sigHash) as ITACMethod

    override fun getMethod(contractName: NamedContractIdentifier, methodAttribute: MethodAttribute.Unique): ITACMethod =
        super.getMethod(contractName, methodAttribute) as ITACMethod

    override fun getMethod(contractId: BigInteger, methodAttribute: MethodAttribute.Unique): ITACMethod =
        super.getMethod(contractId, methodAttribute) as ITACMethod

    override fun getMethods(sigHash: BigInteger): List<ITACMethod>
    fun getMethodImplementors(sigHash: BigInteger) = getMethods(sigHash).map { it.getContainingContract() }

    fun getStorageUniverse(): StorageUniverse
    fun getTransientStorageUniverse(): StorageUniverse

    fun getUnifiedStorage(): UnifiedStorageUniverse {
        val ret = mutableMapOf<BigInteger, UnifiedStorageInfo>()
        val persistentStorage = getStorageUniverse()
        val transientStorage = getTransientStorageUniverse()
        check(persistentStorage.keys == transientStorage.keys) { "Not all contracts have both transient and persistent storage info objects" }
        transientStorage.forEachEntry { (instanceId, transientStorageInfo) ->
            val persistentStorageInfo = persistentStorage[instanceId] ?: throw IllegalStateException("Must have persistent storage info for instance id $instanceId")
            ret.put(instanceId, UnifiedStorageInfo(persistentStorageInfo, transientStorageInfo))
        }
        return ret
    }
    fun getUnifiedStorageInfoViewWithReadTrackers(): UnifiedSingleStorageUniverse {
        val ret = mutableMapOf<BigInteger, IStorageInfo>()
        getUnifiedStorage().forEachEntry { (instanceId, unifiedStorageInfo) ->
            ret[instanceId] = unifiedStorageInfo.persistentStorageInfo.mergeWith(unifiedStorageInfo.transientStorageInfo)
        }
        return ret
    }

    /**
    Warning - do not check for an existence of a contract in this list by the equals() method!
    (this includes the `in` operator)
    To check whether a contract is in this list, compare instanceId instead.
     */
    override fun getContracts(): List<IContractClass>

    /**
     * Specifies a preference for mapping contract/methods in the variants of [mapContractMethods].
     * A request for [PARALLEL] may be ignored, but a request for [SEQUENTIAL] mapping
     * is respected by all implementers.
     */
    enum class MapSort {
        PARALLEL,
        SEQUENTIAL;
    }

    fun mapContractMethodsInPlace(sort: MapSort, transformId: String, p: (IScene, ITACMethod) -> Unit)
    fun mapContractMethods(sort: MapSort, transformId: String, p: (IScene, ITACMethod) -> ICoreTACProgram)

    fun mapContractsInPlace(sort: MapSort, transformId: String, p: (IScene, IContractClass) -> Unit)

    fun mapContractMethodsInPlace(transformId: String, p: (IScene, ITACMethod) -> Unit) = mapContractMethodsInPlace(MapSort.SEQUENTIAL, transformId, p)
    fun mapContractMethods(transformId: String, p: (IScene, ITACMethod) -> ICoreTACProgram) = mapContractMethods(MapSort.SEQUENTIAL, transformId, p)

    fun mapContractsInPlace(transformId: String, p: (IScene, IContractClass) -> Unit) = mapContractsInPlace(MapSort.SEQUENTIAL, transformId, p)

    fun mapScene(transformId: String, p: () -> Unit)

    fun fork(forkInfo: ForkInfo): IScene

    fun getNonPrecompiledContracts(): List<IContractClass> = getContracts() - getPrecompiledContracts()


    /**
    Warning - do not check for an existence of a contract in this list by the equals() method!
    (this includes the `in` operator)
    To check whether a contract is in this list, compare InstanceIds instead.
     */
    override fun getPrecompiledContracts(): List<IContractClass>

    fun toIdentifiers() = SceneIdentifiers(
        contracts = getContracts().map { it.toIdentifiers() },
        precompiledContracts = getPrecompiledContracts().map { it.toIdentifiers() }
    )
}

/**
 * A view of the IScene Identifiers: name, sighash, etc..,  for Contracts and methods
 * Does not include: TAC-program, Forking or map-in-place
 **/
interface ISceneIdentifiers: Serializable, ICVLScene {
    fun getContract(s: NamedContractIdentifier): IContractClassIdentifiers =
        getContractOrNull(s) ?: throw SceneQueryException("Asked for non-existing contract $s")
    fun getContract(a: BigInteger): IContractClassIdentifiers =
        getContractOrNull(a) ?: throw SceneQueryException("Asked for non-existing contract with instanceId $a")

    fun getContractOrNull(s: NamedContractIdentifier): IContractClassIdentifiers?

    fun getContractOrNull(a: BigInteger): IContractClassIdentifiers?

    private fun <T, R> thenGetContractProp(
        k: T,
        getter: (T) -> IContractClassIdentifiers?,
        msg: String,
        tr: (IContractClassIdentifiers) -> R?,
    ): R {
        val contr = getter(k) ?: error("No contract $k in scene")
        return tr(contr) ?: error("$msg in contract ${contr.name} in scene")
    }

    fun getMethod(contractId: BigInteger, sigHash: BigInteger): IMethodIdentifiers =
        thenGetContractProp(contractId, this::getContractOrNull, "No method with hash ${sigHash.toString(16)}") {
            it.getMethodBySigHash(sigHash)
        }

    fun getMethod(contractName: NamedContractIdentifier, sigHash: BigInteger): IMethodIdentifiers =
        thenGetContractProp(contractName, this::getContractOrNull, "No method with hash $sigHash") {
            it.getMethodBySigHash(sigHash)
        }

    fun getMethod(
        contractName: NamedContractIdentifier,
        methodAttribute: MethodAttribute.Unique,
    ): IMethodIdentifiers =
        thenGetContractProp(contractName, this::getContractOrNull, "No method with attribute $methodAttribute") {
            it.getMethodByUniqueAttribute(methodAttribute)
        }

    fun getMethod(
        contractId: BigInteger,
        methodAttribute: MethodAttribute.Unique,
    ): IMethodIdentifiers =
        thenGetContractProp(contractId, this::getContractOrNull, "No method with attribute $methodAttribute") {
            it.getMethodByUniqueAttribute(methodAttribute)
        }

    fun getMethods(sigHash: BigInteger): List<IMethodIdentifiers>

    fun hasMethod(contractId: BigInteger, sigHash: BigInteger): Boolean =
        getContractOrNull(contractId)?.getMethodBySigHash(sigHash) != null

    fun hasMethod(contractName: NamedContractIdentifier, sigHash: BigInteger): Boolean =
        getContractOrNull(contractName)?.getMethodBySigHash(sigHash) != null

    override fun getContracts(): List<IContractClassIdentifiers>

    fun getContractUniverse(): ContractUniverse =
        ContractUniverse.ETHEREUM // TODO: handle in cached

    fun getPrecompiledContracts(): List<IContractClassIdentifiers>

    /**
     * Returns all the contracts which contain a method with the sighash [sighash].
     */
    fun getContracts(sighash: BigInteger): List<IContractClassIdentifiers> = getContracts().filter {
        it.getMethods().any { method -> method.sigHash?.n == sighash }
    }
}

interface ICVLScene {
    fun getContracts(): List<ICVLContractClass>
}

/**
 * A minimal copy of [ISceneIdentifiers] that is guaranteed to be immutable, and lightweight to Serialize
 **/
data class SceneIdentifiers(
    private val contracts: List<ContractClassIdentifiers>,
    private val precompiledContracts: List<ContractClassIdentifiers>,
) : ISceneIdentifiers {
    override fun getContractOrNull(s: NamedContractIdentifier): IContractClassIdentifiers? =
        (precompiledContracts.asSequence() + contracts.asSequence()).find { it.name == s.name }

    override fun getContractOrNull(a: BigInteger): IContractClassIdentifiers? = (precompiledContracts.asSequence() + contracts.asSequence()).find { it.instanceId == a }

    override fun getMethods(sigHash: BigInteger): List<IMethodIdentifiers> =
        contracts.mapNotNull { it.getMethodBySigHash(sigHash) }

    override fun getContracts(): List<IContractClassIdentifiers> = contracts

    override fun getPrecompiledContracts(): List<IContractClassIdentifiers> = precompiledContracts
}
