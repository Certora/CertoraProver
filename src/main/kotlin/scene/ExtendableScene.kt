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

import analysis.EthereumVariables
import bridge.EVMExternalMethodInfo
import bridge.NamedContractIdentifier
import instrumentation.transformers.CodeRemapper
import tac.ICoreTACProgram
import vc.data.CoreTACProgram
import vc.data.TACMeta
import vc.data.TACSymbol
import java.math.BigInteger
import datastructures.stdcollections.*
import utils.*

interface ExtendableScene {
    fun extendContractWith(target: NamedContractIdentifier, m: List<TACMethod>)

    class RemapState(
        val m: MutableMap<Pair<Any, Int>, Int>,
        val targetId: BigInteger,
        val srcId: BigInteger,
        val newVars: MutableSet<TACSymbol.Var>
    )

    companion object {
        val extensionMapper = CodeRemapper<RemapState>(
            blockRemapper = { nbId, _, _, _ -> nbId },
            idRemapper = CodeRemapper.IdRemapperGenerator.generatorFor { it.m },
            callIndexStrategy = { _, callIndex, _ -> callIndex },
            variableMapper = { st, v ->
                fun TACSymbol.Var.andRegister() = this.also { st.newVars.add(it) }
                if(TACMeta.CODESIZE_KEY in v.meta) {
                    check(EthereumVariables.getCodeDataSize(st.srcId) == v)
                    EthereumVariables.getCodeDataSize(st.targetId).andRegister()
                } else if(TACMeta.CODEDATA_KEY in v.meta) {
                    check(EthereumVariables.getCodeData(st.srcId) == v)
                    EthereumVariables.getCodeData(st.srcId).andRegister()
                } else if(TACMeta.STORAGE_KEY in v.meta) {
                    check(EthereumVariables.getStorageInternal(st.srcId) == v)
                    EthereumVariables.getStorageInternal(st.targetId).andRegister()
                } else if(TACMeta.TRANSIENT_STORAGE_KEY in v.meta) {
                    EthereumVariables.getTransientStorageInternal(st.targetId).andRegister()
                } else {
                    v
                }
            }
        )
    }

    fun IContractClass.extendWith(toAdd: List<TACMethod>) : IContractClass {
        val added = mutableMapOf<BigInteger, TACMethod>()
        require(this is IContractWithSource) {
            "Cannot merge into contracts without source, this should always be satisfied!"
        }
        val errors = mutableListOf<String>()
        fun err(s: String) : Nothing? {
            errors.add(s)
            return null
        }
        val ext = toAdd.mapNotNull { m ->
            if(m.sigHash == null) {
                return@mapNotNull err("Cannot merge function without sighash ${m.name} from contract ${m.getContainingContract().name} into ${this.name}")
            }
            val extant = this.getMethodBySigHash(m.sigHash.n)
            if(extant != null) {
                return@mapNotNull err(
                    "Cannot merge function ${m.name} from contract ${m.getContainingContract().name}, " +
                    "it collides with ${extant.name} in ${this.name}. Consider adding this function to the \"exclude\" list"
                )
            }
            val alreadyMerged = added[m.sigHash.n]
            if(alreadyMerged != null) {
                return@mapNotNull err(
                    "Cannot merge ${m.name} from contract ${m.getContainingContract().name} into ${this.name}, " +
                        "we've already merged ${alreadyMerged.name} from ${alreadyMerged.getContainingContract().name} " +
                        "with which it collides. Consider adding one of the implementations to an \"exclude\" list"
                )
            }
            added[m.sigHash.n] = m
            val remapState = RemapState(
                mutableMapOf(),
                srcId = m.getContainingContract().instanceId,
                targetId = this.instanceId,
                newVars = mutableSetOf()
            )
            val mapper = extensionMapper.commandMapper(remapState)
            val srcCode = m.code as CoreTACProgram
            val newCode = srcCode.code.mapValues { (_, cmds) ->
                cmds.map { cmd ->
                    mapper(cmd)
                }
            }
            val newCtp = srcCode.copy(
                code = newCode, symbolTable = srcCode.symbolTable.mergeDecls(remapState.newVars)
            )

            val newName = "${m.name} (from ${m.getContainingContract().name})"
            object : IFreeTACMethod by m {
                override val code: ICoreTACProgram
                    get() = newCtp
                override val name: String
                    get() = newName

                override val evmExternalMethodInfo: EVMExternalMethodInfo?
                    get() = m.evmExternalMethodInfo?.copy(
                        contractName = this@extendWith.name,
                        contractInstanceId = this@extendWith.instanceId
                    )
            }
        }
        if(errors.isNotEmpty()) {
            throw CertoraException(CertoraErrorType.CONTRACT_EXTENSION, errors.joinToString("\n"))
        }
        return ForkedContractClass(
            storageLayout = this.getStorageLayout(),
            transientStorageLayout = this.getTransientStorageLayout(),
            src = this.src,
            methods = this.getStandardMethods() + ext,
            bytecode = this.bytecode,
            constructorBytecode = this.constructorBytecode,
            constructorCode = this.constructorMethod!!,
            storageInfoField = this.storage,
            transientStorageInfoField = this.transientStorage,
            wholeCode = this.wholeContractMethod
        )
    }
}
