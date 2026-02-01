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
import analysis.EthereumVariables
import analysis.icfg.CallGraphBuilder
import config.OUTPUT_NAME_DELIMITER
import datastructures.stdcollections.*
import instrumentation.transformers.CodeRemapper
import tac.ICoreTACProgram
import tac.TACBasicMeta
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

interface IDynamicScene : IScene {
    fun cloneContract(contract: IContractClass) : IClonedContract
    fun cloneContract(address: BigInteger) : IClonedContract = cloneContract(getContract(address))

    class RemapperState(
        val idRemapper : MutableMap<Pair<Any, Int>, Int> = mutableMapOf(),
        val origStorageRemapper : MutableMap<TACSymbol.Var, TACSymbol.Var> = mutableMapOf(),
        val storage: Map<TACSymbol.Var, TACSymbol.Var>,
        val srcId: BigInteger,
        val tgtId: BigInteger,
        val newName: String,
        val srcName: String,
        val vars: MutableSet<TACSymbol.Var>
    )

    companion object {
        val cloneRemapper = object : CodeRemapper<RemapperState>(
            blockRemapper = { blk, callIdx, _, _ ->
                blk.copy(calleeIdx = callIdx(blk.calleeIdx))
            },
            idRemapper = IdRemapperGenerator.generatorFor(RemapperState::idRemapper),
            callIndexStrategy = { _, idx, fresh ->
                if(idx == TACSymbol.Var.DEFAULT_INDEX) {
                    idx
                } else {
                    fresh(idx)
                }
            },
            variableMapper = { st, t ->
                val storageMapping = st.storage
                val baseRemap = if(t in storageMapping) {
                    storageMapping[t]!!
                } else if(t.meta.find(TACMeta.ORIGINAL_STORAGE_KEY) == st.srcId) {
                    synchronized(st.origStorageRemapper) {
                        // ${st.tgtId}!${allocator.Allocator.getAndIncStorageCounter()}
                        st.origStorageRemapper.computeIfAbsent(t) {
                            TACSymbol.Var(
                                vc.data.TACKeyword.ORIGINAL_STORAGE.extendName("!${st.tgtId}!${allocator.Allocator.getAndIncStorageCounter()}"),
                                tag = t.tag,
                                meta = t.meta + (TACMeta.ORIGINAL_STORAGE_KEY to st.tgtId)
                            )
                        }
                    }
                } else if(t.meta.containsKey(TACBasicMeta.IS_IMMUTABLE) && t.meta.find(TACBasicMeta.IMMUTABLE_OWNER) == st.srcName) {
                    TACKeyword.IMMUTABLE(
                        t.tag, name = t.meta.find(TACBasicMeta.IMMUTABLE_NAME)!!, owningContract = st.newName
                    )
                } else if(t.meta.find(TACMeta.CODEDATA_KEY) == st.srcId && TACMeta.NO_CODE_REWRITE !in t.meta) {
                    if (t.meta.containsKey(TACMeta.CONSTRUCTOR_CODE_KEY)) {
                        EthereumVariables.getConstructorCodeData(st.tgtId)
                    } else {
                        EthereumVariables.getCodeData(st.tgtId)
                    }
                } else if(t.meta.find(TACMeta.CODESIZE_KEY) == st.srcId) {
                    if(t.meta.containsKey(TACMeta.CONSTRUCTOR_CODE_KEY)) {
                        EthereumVariables.getConstructorCodeDataSize(st.tgtId)
                    } else {
                        EthereumVariables.getCodeDataSize(st.tgtId)
                    }
                } else  {
                    t
                }
                st.vars.add(baseRemap)
                baseRemap
            }
        ) {
            override fun mapSummary(state: RemapperState, summary: TACSummary): TACSummary {
                return if (summary is CallSummary) {
                    summary.copy(
                        callTarget = summary.callTarget.mapToSet { it.sanitize(state) },
                        callConvention = summary.callConvention.remapCalledContracts {
                            it.sanitize(state)
                        }
                    )
                } else {
                    summary
                }
            }
        }

        private fun CallGraphBuilder.CalledContract.sanitize(st: RemapperState): CallGraphBuilder.CalledContract {
            return when(this) {
                /*
                  Is this right?
                 */
                is CallGraphBuilder.CalledContract.CreatedReference.Resolved,
                is CallGraphBuilder.CalledContract.CreatedReference.Unresolved -> CallGraphBuilder.CalledContract.Unresolved
                is CallGraphBuilder.CalledContract.FullyResolved.ImmutableReference,
                is CallGraphBuilder.CalledContract.FullyResolved.StorageLink -> CallGraphBuilder.CalledContract.Unresolved
                is CallGraphBuilder.CalledContract.FullyResolved.SelfLink -> CallGraphBuilder.CalledContract.FullyResolved.SelfLink(st.tgtId)
                is CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress,
                is CallGraphBuilder.CalledContract.Invalidated,
                is CallGraphBuilder.CalledContract.SymbolicInput,
                is CallGraphBuilder.CalledContract.SymbolicOutput,
                is CallGraphBuilder.CalledContract.InternalFunctionSummaryOutput,
                is CallGraphBuilder.CalledContract.Unresolved,
                is CallGraphBuilder.CalledContract.UnresolvedRead -> this
            }
        }

    }

    fun getFreshInstanceId() = getContracts().maxOfOrNull {
        it.instanceId
    }?.add(BigInteger.ONE) ?: BigInteger("ce4604a0000000000000000000000000", 16)

    fun IContractClass.cloneTo(tgtId: BigInteger) : IClonedContract {
        val newName = this.name + "\$Clone${Allocator.getFreshId(Allocator.Id.CLONES)}"
        val storageMapping = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
        val srcId = this.instanceId
        val newStorage = mutableMapOf<TACSymbol.Var, TACSymbol.Var?>()
        for((it, readTracker) in (this.storage as StorageInfoWithReadTracker).storageVariables) {
            val storageAddr =
                it.meta.find(TACMeta.STORAGE_KEY) ?: error("Have a storage variable without a declared storage")
            check(storageAddr == this.instanceId) {
                "Storage variable $it declared for ${this.name} doesn't have its address (${this.instanceId}): $storageAddr"
            }
            val newVar = StorageCloning.cloneStorageVarTo(sourceId = this@cloneTo.instanceId, storageVar = it, targetContract = tgtId)
            storageMapping[it] = newVar
            val newRead = readTracker?.let { r ->
                check(it.tag == Tag.WordMap)
                val ret = TACSymbol.Var(
                    namePrefix = "$newVar!readTrack",
                    tag = it.tag,
                    meta = r.meta
                )
                storageMapping[r] = ret
                ret
            }
            newStorage[newVar] = newRead
        }
        val newTransientStorage = mutableSetOf<TACSymbol.Var>()
        // transient storage currently has no read tracker
        (this.transientStorage as StorageInfo).storageVariables.forEach {
            val storageAddr =
                it.meta.find(TACMeta.TRANSIENT_STORAGE_KEY) ?: error("Have a storage variable without a declared storage")
            check(storageAddr == this.instanceId) {
                "Storage variable $it declared for ${this.name} doesn't have its address (${this.instanceId}): $storageAddr"
            }
            val sort = it.meta.find(TACMeta.SCALARIZATION_SORT)
                ?: error("Storage variable $it in ${this.name} does not have storage sort")

            val newVar = when (sort) {
                ScalarizationSort.UnscalarizedBuffer -> {
                    TACSymbol.Var(
                        TACKeyword.TRANSIENT_STORAGE.extendName("!${tgtId.toString(16)}"),
                        tag = Tag.WordMap,
                        meta = it.meta + (TACMeta.TRANSIENT_STORAGE_KEY to tgtId)
                    )
                }
                else -> {
                    // no actual scalarization sort for transient storage
                    throw IllegalStateException("Transient storage variables only support unscalarized buffers now, got $sort")
                }
            }
            newTransientStorage.add(newVar)
        }
        val idRemapper = mutableMapOf<Pair<Any, Int>, Int>()
        val origStorageRemapper = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()

        fun ITACMethod.remap() : IFreeTACMethod {
            val orig = this.code as CoreTACProgram
            val vars = Collections.newSetFromMap(ConcurrentHashMap<TACSymbol.Var, Boolean>())
            val state = RemapperState(
                storage = storageMapping,
                idRemapper = idRemapper,
                newName = newName,
                srcId = srcId,
                tgtId = tgtId,
                srcName = this@cloneTo.name,
                origStorageRemapper = origStorageRemapper,
                vars = vars
            )
            val newProgName = when(this.attribute) {
                MethodAttribute.Unique.Constructor -> "$newName${OUTPUT_NAME_DELIMITER}constructor"
                MethodAttribute.Common -> "$newName${OUTPUT_NAME_DELIMITER}${this.evmExternalMethodInfo}"
                MethodAttribute.Unique.Fallback -> "$newName${OUTPUT_NAME_DELIMITER}fallback"
                MethodAttribute.Unique.Whole -> newName
            }
            val mapper = cloneRemapper.commandMapper(state)
            val remapped = CoreTACProgram(
                blockgraph = orig.blockgraph.entries.associateTo(MutableBlockGraph()) {
                    cloneRemapper.remapBlockId(state, CodeRemapper.BlockRemappingStrategy.RemappingContext.BLOCK_ID, it.key) to it.value.updateElements {
                        cloneRemapper.remapBlockId(state, CodeRemapper.BlockRemappingStrategy.RemappingContext.SUCCESSOR, it)
                    }
                },
                code = orig.code.entries.parallelStream().map {
                    cloneRemapper.remapBlockId(state, CodeRemapper.BlockRemappingStrategy.RemappingContext.BLOCK_ID, it.key) to it.value.map {
                        mapper(it)
                    }
                }.collect(Collectors.toMap({it.first}, { it.second })),
                procedures = orig.procedures.mapTo(mutableSetOf()) { proc ->
                    cloneRemapper.remapProcedure(state = state, proc)
                },
                symbolTable = TACSymbolTable.withVars(vars),
                name = newProgName,
                instrumentationTAC = orig.instrumentationTAC
            )
            return object : IFreeTACMethod by this {
                override val code: ICoreTACProgram
                    get() = remapped
            }
        }
        val regularMethods = mutableMapOf<BigInteger?, IFreeTACMethod>()
        var wholeMethod: IFreeTACMethod? = null
        this.getMethods().forEach {
            if(it.attribute == MethodAttribute.Unique.Whole) {
                wholeMethod = it.remap()
            } else {
                regularMethods[it.sigHash?.n] = it.remap()
            }
        }
        val constructor = this.getConstructor()!!.remap()

        return ClonedContractClass(
            instanceId = tgtId,
            name = newName,
            storageInfo = StorageInfoWithReadTracker(newStorage),
            transientStorageInfo = StorageInfo(newTransientStorage),
            storageLayout = null,
            transientStorageLayout = null,
            methods = regularMethods,
            wholeContractMethod = wholeMethod,
            constructorMethod = constructor,
            sourceContractId = this.instanceId
        )
    }
}
