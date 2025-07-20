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
import caching.WithMemento
import datastructures.stdcollections.*
import disassembler.DisassembledEVMBytecode
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler.compute
import parallel.forkEvery
import parallel.pcompute
import tac.ICoreTACProgram
import tac.IStorageInfo
import tac.ITACSymbol
import tac.TACStorageLayout
import java.io.Serializable
import java.math.BigInteger

typealias ContractId = BigInteger

/**
 * A view of the IContractClass Identifiers:
 * - name
 * - address
 * - Its methods identifiers
 * Does not include: TAC-program, Forking or map-in-place
 **/
interface IContractClassIdentifiers: Serializable, ICVLContractClass {
    override val instanceId: ContractId
    val methods: Map<BigInteger?, IMethodIdentifiers>
    val wholeContractMethod: IMethodIdentifiers?
    val constructorMethod: IMethodIdentifiers?

    fun getMethodBySigHash(sig: BigInteger): IMethodIdentifiers?

    fun getWholeContract(): IMethodIdentifiers? = getMethodByUniqueAttribute(MethodAttribute.Unique.Whole)
    fun getConstructor(): IMethodIdentifiers? = getMethodByUniqueAttribute(MethodAttribute.Unique.Constructor)
    fun getMethods(): List<IMethodIdentifiers> = getDeclaredMethods().plus(listOfNotNull(getWholeContract()))

    fun getMethodByUniqueAttribute(attr: MethodAttribute.Unique): IMethodIdentifiers? = when (attr) {
        MethodAttribute.Unique.Whole -> wholeContractMethod
        MethodAttribute.Unique.Fallback -> methods.values.filter { it.attribute == attr }.let { fallbacks ->
            check(fallbacks.size <= 1) { "Can't have more than 1 fallback method" }
            fallbacks.firstOrNull()
        }
        MethodAttribute.Unique.Constructor -> constructorMethod
    }

    fun getDeclaredMethods(): Collection<IMethodIdentifiers>
    fun getStorageLayout(): TACStorageLayout?

    fun getMethodOrFallback(sig: BigInteger?) = if (sig != null) {
        getMethodBySigHash(sig)
    } else {
        getMethodByUniqueAttribute(MethodAttribute.Unique.Fallback)
    }
}

interface ICVLContractClass {
    val name: String
    val instanceId: BigInteger
    val instanceIdIsStaticAddress: Boolean get() = false
}


/**
 * A minimal copy of [IContractClassIdentifiers] that is guaranteed to be immutable, and lightweight to Serialize
 **/
data class ContractClassIdentifiers(
    override val instanceId: BigInteger,
    override val name: String,
    override val methods: Map<BigInteger?, MethodIdentifiers>,
    override val wholeContractMethod: MethodIdentifiers?,
    override val constructorMethod: MethodIdentifiers?,
    val storage: TACStorageLayout?
) :
    IContractClassIdentifiers {
    override fun getMethodBySigHash(sig: BigInteger): IMethodIdentifiers? = methods[sig]

    override fun getDeclaredMethods(): Collection<IMethodIdentifiers> = methods.values + listOfNotNull(constructorMethod)

    override fun getStorageLayout(): TACStorageLayout? = storage
}

interface IContractClass : WithMemento<IContractClass.ContractMemento>, IContractClassIdentifiers {
    override val methods: Map<BigInteger?, ITACMethod>
    override val wholeContractMethod: ITACMethod?
    override val constructorMethod: ITACMethod?

    class ContractMemento(
        val methods: Map<BigInteger?, ICoreTACProgram>,
        val constructorCode: ITACMethod?,
        val storageInfo: IStorageInfo,
        val transientStorageInfo: IStorageInfo,
        val allocState: Allocator.Memento
    ) : Serializable

    fun toIdentifiers() = ContractClassIdentifiers(
        instanceId = instanceId,
        name = name,
        wholeContractMethod = getMethodByUniqueAttribute(MethodAttribute.Unique.Whole)?.toIdentifiers(),
        constructorMethod = getConstructor()?.toIdentifiers(),
        methods = getMethods().asSequence().map { it.toIdentifiers() }.associateBy { it.sigHash?.n },
        storage = this.getStorageLayout()
    )

    override fun getWholeContract(): ITACMethod? =
        getMethodByUniqueAttribute(MethodAttribute.Unique.Whole)

    override fun getConstructor(): ITACMethod? = getMethodByUniqueAttribute(MethodAttribute.Unique.Constructor)

    override fun getMethodBySigHash(sig: BigInteger): ITACMethod?
    override fun getMethodByUniqueAttribute(attr: MethodAttribute.Unique): ITACMethod? =
        super.getMethodByUniqueAttribute(attr) as ITACMethod?

    val storage: IStorageInfo
    val transientStorage: IStorageInfo

    override fun getMethods(): List<ITACMethod> = getDeclaredMethods() + listOfNotNull(getWholeContract())

    /**
     * Get all methods explicitly declared within the contract that can be invoked at any point during the
     * contract's lifetime. NB. this includes the constructor
     */
     override fun getDeclaredMethods(): Collection<ITACMethod>

    /**
     * Get all methods explicitly declared within the contract that can be called after deployment,
     * i.e., no constructors
     */
    fun getStandardMethods(): Collection<ITACMethod> = this.getDeclaredMethods().filterNot {
        it.attribute == MethodAttribute.Unique.Constructor
    }

    /**
     *  function [p] is responsible for updating the method (if that's the desired outcome)
     */
    fun mapMethodsInPlace(scene: IScene, p: (IScene, ITACMethod) -> Unit) = mapMethodsInPlace(IScene.MapSort.SEQUENTIAL, scene, p)

    /**
     *  function [p] is responsible for updating the method (if that's the desired outcome)
     */
    fun mapMethodsInPlace(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> Unit) {
        if(sort == IScene.MapSort.PARALLEL) {
            getDeclaredMethods().forkEvery {
                compute {
                    p(scene, it)
                }
            }.pcompute().runInherit()
        } else {
            getDeclaredMethods().forEach {
                p(scene, it)
            }
        }
    }

    fun mapMethods(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram)

    /**
     * function [p] is not responsible for updating the method in place
     */
    fun mapMethods(scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram) = mapMethods(IScene.MapSort.SEQUENTIAL, scene, p)

    fun fork(): IContractClass

    val addressSym: ITACSymbol
    val bytecode: DisassembledEVMBytecode?
    val constructorBytecode: DisassembledEVMBytecode?

    // this is used just as a CVT-internal name, does not have to connect to source language name of the constructor
    val constructorCodeName: String
        get() = "${CONSTRUCTOR}_${name}"

    override fun saveState(): ContractMemento =
        ContractMemento(
            getMethods().associate {
                it.sigHash?.n to it.code
            },
            getConstructor(),
            storage,
            transientStorage,
            Allocator.saveState()
        )

    override fun restore(m: ContractMemento) {
        for(meth in getMethods()) {
            meth.code = m.methods[meth.sigHash?.n] ?: error("Broken cache")
        }
        getConstructor()?.code = m.constructorCode!!.code
        if(this is IMutableStorageInfo) {
            this.setStorageInfo(m.storageInfo)
        } else {
            check(this.storage == m.storageInfo)
        }
        if (this is IMutableTransientStorageInfo) {
            this.setTransientStorageInfo(m.transientStorageInfo)
        } else {
            check(this.transientStorage == m.transientStorageInfo)
        }
        Allocator.restore(m.allocState)
    }

    /**
     * If this contract class comes with source information (e.g. from certoraBuild script), it will return it
     * together with the user specific link (`--link`) information
     */
    fun getContractStateLinks(): ContractWithStateLinkInfo? {
        val contract = (this as? IContractWithSource)?.src ?: return null
        return ContractWithStateLinkInfo(this, contract.state, contract.legacyStructLinking, contract.structLinkingInfo)
    }
}
