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

import datastructures.stdcollections.*
import diagnostics.*
import disassembler.DisassembledEVMBytecode
import log.*
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler
import parallel.forkEvery
import parallel.pcompute
import tac.ICoreTACProgram
import tac.IStorageInfo
import tac.TACStorageLayout
import vc.data.TACSymbol
import java.io.Serializable
import java.math.BigInteger

/**
A contract class "backed" by a map of sighashes to methods.

 NB: (alex:) don't mess with these field types unless you're certain -- they will be serialized
 */
abstract class MapBackedContractClass(
    final override val instanceId : BigInteger,
    override val instanceIdIsStaticAddress: Boolean,
    override val bytecode: DisassembledEVMBytecode?,
    override val constructorBytecode: DisassembledEVMBytecode?,
    final override val name: String,
    private val storageLayout: TACStorageLayout?,
    private val transientStorageLayout: TACStorageLayout?
) : IContractClass, Serializable, IMutableStorageInfo, IMutableTransientStorageInfo {
    abstract override val methods: Map<BigInteger?, ITACMethod>
    abstract override val wholeContractMethod : ITACMethod?
    abstract override val constructorMethod : ITACMethod

    protected abstract var storageInfoField : IStorageInfo
    protected abstract var transientStorageInfoField : IStorageInfo
    override val addressSym: TACSymbol.Var = createAddressVar(instanceId, name)

    override val storage: IStorageInfo
        get() = storageInfoField

    override val transientStorage: IStorageInfo
        get() = transientStorageInfoField

    override fun getMethods(): List<ITACMethod> = methods.values + listOfNotNull(wholeContractMethod)

    override fun getWholeContract(): ITACMethod? {
        return this.wholeContractMethod
    }

    override fun getConstructor(): ITACMethod {
        return this.constructorMethod
    }

    override fun getMethodBySigHash(sig: BigInteger): ITACMethod? = methods[sig]

    override fun getStorageLayout(): TACStorageLayout? {
        return this.storageLayout
    }

    override fun getTransientStorageLayout(): TACStorageLayout? {
        return this.transientStorageLayout
    }

    override fun getDeclaredMethods(): Collection<ITACMethod> = methods.values + constructorMethod

    override fun mapMethods(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram) {
        val newMethodsCodes = if(sort == IScene.MapSort.PARALLEL) {
            methods.entries.forkEvery { (sig, m) ->
                Scheduler.compute {
                    inCode(m) {
                        sig to p(scene, m)
                    }
                }
            }.pcompute().runInherit().toMap()
        } else {
            methods.entries.map { (sig, m) ->
                sig to p(scene, m)
            }.toMap()
        }

        // simple set after done
        methods.forEach { e ->
            @Suppress("DEPRECATION")
            e.value.updateCode(newMethodsCodes[e.key]!!)
        }
        check(checkSighash(methods)) { "Sighash mapping is invalid" }
    }

    override fun setStorageInfo(s: IStorageInfo) {
        this.storageInfoField = s
    }

    override fun setTransientStorageInfo(s: IStorageInfo) {
        this.transientStorageInfoField = s
    }

    fun checkSighash(methods: Map<BigInteger?, ITACMethod>): Boolean {
        val nonMatching = methods.filterNot { (desiredSighash, method) ->
            method.sigHash?.n?.let { concreteSighash -> concreteSighash == desiredSighash } ?: true
        }
        if (nonMatching.isNotEmpty()) {
            Logger.alwaysError("Contract $name contains methods whose keys are not aligned with their sighashes: ${nonMatching.map { it.key to it.value.soliditySignature }}")
        }
        return nonMatching.isEmpty()
    }

    override fun toString(): String {
        return "Contract(${this.name}@0x${instanceId.toString(16)})"
    }

    private fun createAddressVar(id : BigInteger, name : String) : TACSymbol.Var = TACSymbol.Var.contractVar(id, name)
}
