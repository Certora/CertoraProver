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

package instrumentation

import com.certora.collect.*
import datastructures.stdcollections.*
import disassembler.DisassembledEVMBytecode
import scene.*
import tac.*
import vc.data.*
import java.io.Serializable
import java.math.BigInteger

/**
 * Creates a ContractClass for a synthetic contract (e.g. Precompiled) that we have no definition for its code.
 * */
fun createContractClassWithoutMethods(name: String, instanceId : BigInteger, address : ITACSymbol, wholeCode: CoreTACProgram): IContractClass {
    return object : IContractClass,Serializable {
        override val addressSym: ITACSymbol
            get() = address
        override val instanceId: BigInteger
            get() = instanceId
        override val bytecode: DisassembledEVMBytecode?
            get() = null
        override val constructorBytecode: DisassembledEVMBytecode?
            get() = null
        override val name: String
            get() = name

        override val methods: Map<BigInteger?, ITACMethod>
            get() = emptyMap()
        override val wholeContractMethod: ITACMethod
            get() = emptyMethod
        override val constructorMethod: ITACMethod
            get() = emptyConstructor

        val emptyMethod = TACMethod(
            name,
            wholeCode,
            this,
            MetaMap(),
            MethodAttribute.Unique.Whole
        )
        val emptyConstructor = TACMethod(
            name,
            createEmptyProgram(name),
            this,
            MetaMap(),
            MethodAttribute.Unique.Constructor
        )

        override fun getDeclaredMethods(): Collection<ITACMethod> {
            return listOf()
        }

        override fun getConstructor(): ITACMethod = emptyConstructor

        override fun mapMethodsInPlace(scene: IScene, p: (IScene, ITACMethod) -> Unit) {
            // nothing is done for synthetic contracts
        }

        override fun mapMethods(sort: IScene.MapSort, scene: IScene, p: (IScene, ITACMethod) -> ICoreTACProgram) {
            // nothing is done for synthetic contracts
        }

        override fun fork(): IContractClass {
            return createContractClassWithoutMethods(name, instanceId, address, wholeContractMethod.fork().code as CoreTACProgram)
        }

        override fun getMethodBySigHash(sig: BigInteger): ITACMethod? = null
        override fun getMethodByUniqueAttribute(attr: MethodAttribute.Unique): ITACMethod? =
            when (attr) {
                MethodAttribute.Unique.Fallback -> null
                MethodAttribute.Unique.Whole -> emptyMethod
                MethodAttribute.Unique.Constructor -> emptyConstructor
            }

        override val storage: StorageInfoWithReadTracker
            get() = StorageInfoWithReadTracker.empty()
        override fun getStorageLayout(): TACStorageLayout? = null
        override fun getTransientStorageLayout(): TACStorageLayout? = null
        override val transientStorage: StorageInfo
            get() = StorageInfo.empty()
    }
}

fun createEmptyProgram(name: String): CoreTACProgram = CoreTACProgram(
    mapOf(StartBlock to listOf(TACCmd.Simple.NopCmd)),
    BlockGraph(StartBlock to treapSetOf()),
    name,
    TACSymbolTable.empty(),
    UfAxioms.empty(),
    IProcedural.empty()
)
