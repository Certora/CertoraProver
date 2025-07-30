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

@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package report.globalstate

import analysis.CmdPointer
import analysis.storage.DisplayPath
import analysis.storage.InstantiatedDisplayPath
import datastructures.stdcollections.*
import log.*
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.FormatterType.Companion.toValueFormatterType
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.CVLKeywords
import tac.NBId
import vc.data.SnippetCmd.CVLSnippetCmd.InlinedHook
import vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet
import vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet
import vc.data.TACCmd.Simple.AssigningCmd.*
import vc.data.TACSymbol
import vc.data.state.TACValue
import java.math.BigInteger

internal val logger = Logger(LoggerTypes.CALLTRACE_STORAGE)

private typealias ContractStorage = MutableMap<InstantiatedDisplayPath, DisplaySymbolWrapper>
private typealias AllStorage = MutableMap<BigInteger, ContractStorage>

/**
 * A unit of the [CallTrace]. Represents the storage during the flow of the CounterExample TACProgram chosen by the SMT.
 */
internal class StorageState(
    private val seqGen: SequenceGenerator,
    private val model: CounterexampleModel,
    private val scene: ISceneIdentifiers,
    private val formatter: CallTraceValueFormatter,
    private val variablesState: VariablesState
) : DebugAdapterVariableState{
    /** Continuously updated snapshot of the storage, including [TACValue]s and [ComputationalTypes]. */
    private var storageMap: AllStorage = mutableMapOf()

    /** A registry for all the storage locations that are read or written across all contracts in the given call trace.
     *
     * This has the same type as [storageMap], but here the [TACValue]s are never filled in, in the
     * [DisplaySymbolWrapper]s, nor is [ComputationalTypes] updated, etc.
     *
     * Note that this is initialized once for each call trace. (This initialization happens when initializing
     * [GlobalState], which initializes  [StorageState]; there is only one of these two object per call trace, and
     * they're created only once.) */
    private val allStoragePaths: AllStorage = initializeAllStoragePaths()

    /** Follows whole-storage assignments in the call trace; when storage is assigned, we use this to fill the map.
     *
     * (why not fill it up from the program again?
     *   Perhaps to inherit read/write locations from the rhs storage?.. also because this is used for CVL level
     *   whole-storage handling -- we don't allow pointwise updates to these explicit storage symbols)
     *
     * XXX: try whether we can change [String] to [TACSymbol.Var] here ..*/
    private val assignmentsSnapshots: MutableMap<String, AllStorage> = mutableMapOf()

    /** Similar to [assignmentsSnapshots], but updates are triggered by different Snippets.. */
    private val invocationBackupSnapshots: MutableMap<Int, AllStorage> = mutableMapOf()

    private val changedSinceLastPrint: MutableMap<BigInteger, MutableSet<InstantiatedDisplayPath>> = mutableMapOf()
    private val allChangesSinceStart: MutableMap<BigInteger, MutableSet<InstantiatedDisplayPath>> = mutableMapOf()
    private var printCounter = 0 // counter for UI purpose

    init {
        for (contractAddr in allStoragePaths.keys) {
            findStorageIncarnation(contractAddr, zeroPtr)
            changedSinceLastPrint[contractAddr] = mutableSetOf()
            allChangesSinceStart[contractAddr] = mutableSetOf()
        }
    }

    fun handleStorageLocalChanges(snippetCmd: StorageSnippet) {
        when (snippetCmd) {
            is StorageSnippet.DirectStorageHavoc -> {
                val idp = toInstantiatedDisplayPath(snippetCmd.displayPath)

                /**
                 * the [TACSymbol.Var] corresponding to a direct storage havoc will not actually appear in
                 * an [AssignHavocCmd] in the generated TAC, as that variable is only indirectly havoced.
                 * thus, [VariablesState] will consider it to be [ComputationalTypes.HAVOC_DEPENDENT].
                 * we manually set it here based on the snippet data.
                 */
                val updatedType = ComputationalTypes.HAVOC

                val dsw = toDSW(snippetCmd, updatedType)

                storageMap
                    .getOrPut(snippetCmd.contractInstance, ::mutableMapOf)
                    .set(idp, dsw)

                setChanged(idp, snippetCmd.contractInstance)
            }

            is StorageSnippet.StoreSnippet -> {
                val idp = toInstantiatedDisplayPath(snippetCmd.displayPath)

                val valueSym = snippetCmd.value

                val updatedType = when (valueSym) {
                    is TACSymbol.Const -> ComputationalTypes.CONCRETE
                    is TACSymbol.Var -> variablesState.computationalTypeForRHS(valueSym)
                }

                val dsw = toDSW(snippetCmd, updatedType)

                storageMap
                    .getOrPut(snippetCmd.contractInstance, ::mutableMapOf)
                    .set(idp, dsw)

                setChanged(idp, snippetCmd.contractInstance)

                /**
                 * note the order here, the DSW computation above uses the data _before_ the update.
                 *
                 * XXX: it would be better if this was handled directly by [VariablesState]
                 */
                if (valueSym is TACSymbol.Var) {
                    variablesState[valueSym] = dsw
                }
            }

            is StorageSnippet.LoadSnippet,
            is StorageSnippet.DirectStorageLoad -> { }
        }
    }

    private fun createStorageCopy(srcStorage: AllStorage) : AllStorage {
        val res : AllStorage = mutableMapOf()
        srcStorage.forEachEntry { (addr, contractStorage) ->
            res[addr] = contractStorage.toMutableMap()
        }
        return res
    }

    private fun setChanged(idp: InstantiatedDisplayPath, contract: BigInteger) {
        changedSinceLastPrint.getOrPut(contract, ::mutableSetOf).add(idp)
        allChangesSinceStart.getOrPut(contract, ::mutableSetOf).add(idp)
    }

    private fun toDSW(snippetCmd: StorageSnippet, computationalType: ComputationalTypes): DisplaySymbolWrapper {
        return DisplaySymbolWrapper(
            name = snippetCmd.displayPath.toDisplayString(formatter, model),
            value = model.valueAsTACValue(snippetCmd.value),
            computationalType,
            formatterType = snippetCmd.storageType?.toValueFormatterType(),
            range = snippetCmd.range,
        )
    }

    private fun toUninitializedDSW(snippetCmd: StorageSnippet): DisplaySymbolWrapper {
        return DisplaySymbolWrapper(
            name = snippetCmd.displayPath.toDisplayString(formatter, model),
            value = TACValue.Uninitialized,
            computationalType = ComputationalTypes.DONT_CARE,
            formatterType = snippetCmd.storageType?.toValueFormatterType(),
            range = snippetCmd.range,
        )
    }

    fun handleStorageGlobalChanges(snippetCmd: StorageGlobalChangeSnippet, blockId: NBId, pos: Int) {
        when (snippetCmd) {
            is StorageGlobalChangeSnippet.StorageTakeSnapshot -> {
                if (snippetCmd.rhs == null || snippetCmd.rhs.namePrefix == CVLKeywords.lastStorage.keyword) {
                    assignmentsSnapshots[snippetCmd.lhs.namePrefix] = createStorageCopy(storageMap)
                } else {
                    assignmentsSnapshots[snippetCmd.lhs.namePrefix] =
                        assignmentsSnapshots[snippetCmd.rhs.namePrefix]?.let { createStorageCopy(it) }
                            ?: throw IllegalStateException("Failed to find the storage snapshot of ${snippetCmd.rhs} when handling its assignment to ${snippetCmd.lhs}")
                }
            }
            is StorageGlobalChangeSnippet.StorageRestoreSnapshot -> {
                storageMap = assignmentsSnapshots[snippetCmd.name.namePrefix]?.let { createStorageCopy(it) }
                    ?: throw IllegalStateException("Failed to find the storage snapshot of ${snippetCmd.name} when restoring.")
            }
            is StorageGlobalChangeSnippet.StorageBackupPoint -> {
                invocationBackupSnapshots[snippetCmd.calleeTxId] = createStorageCopy(storageMap)
            }
            is StorageGlobalChangeSnippet.StorageRevert -> {
                storageMap = invocationBackupSnapshots[snippetCmd.calleeTxId]?.let { createStorageCopy(it) }
                    ?: throw IllegalStateException("Failed to revert the storage to state before call #${snippetCmd.calleeTxId}.")
            }
            is StorageGlobalChangeSnippet.StorageHavocContract -> {
                val ptr = CmdPointer(blockId, pos + 1)
                findStorageIncarnation(snippetCmd.addr, ptr)
            }
            is StorageGlobalChangeSnippet.StorageResetContract -> {
                storageMap[snippetCmd.addr]?.replaceAll { _, v -> v.copy(value = TACValue.valueOf(0), computationalType = ComputationalTypes.CONCRETE) }
            }
        }
    }

    private fun toInstantiatedDisplayPath(displayPath: DisplayPath) : InstantiatedDisplayPath{
        /**
         * [InstantiatedDisplayPath.ArrayAccess]'s index and [InstantiatedDisplayPath.MapAccess]'s key are [TACSymbol.Const],
         * so we currently don't support case when the [DisplayPath] contains an uninterpreted function.
         */
        return try {
            displayPath.instantiateTACSymbols(model)
        } catch (e: UnsupportedOperationException) {
            val displayString = displayPath.toDisplayString(formatter, model)
            logger.info(e) { "Failed to instantiate $displayString" }
            InstantiatedDisplayPath.Root(displayString.flatten())
        }
    }

    /**
     * Initializes local variable [allStoragePaths].
     */
    private fun initializeAllStoragePaths(): AllStorage {
        val allStoragePaths: AllStorage = mutableMapOf()

        val storageSnippets = seqGen
            .snippets()
            .filterIsInstance<StorageSnippet>()

        for (snippetCmd in storageSnippets) {
            val idp = toInstantiatedDisplayPath(snippetCmd.displayPath)
            val dsw = toUninitializedDSW(snippetCmd)

            allStoragePaths
                .getOrPut(snippetCmd.contractInstance, ::mutableMapOf)
                .put(idp, dsw)
        }

        return allStoragePaths
    }

    /**
     * Updates [storageMap] for [contract] with liveness (*) information.
     *
     * This needs a special pass after each (storage havoc? or really each display of a storage state??) because
     * in order to show the correct liveness information (i.e. "DONT CARE" labels) in a given display of storage, we
     * need to "look into the future" to see whether a given storage location is read before being overwritten.
     *
     * (*) note that our notion of liveness is a bit modified -- every read counts for us, not just a read that flows
     *    into something that is live. This makes some sense because we have optimizations that throw away dead
     *    assignments, so they shouldn't be in the call trace anyway, by and large.
     *
     * If there are no storage accesses between [startPtr] and the next storage reset, the [DisplaySymbolWrapper] is
     * taken over from the corresponding entry in [allStoragePaths].
     *
     * Find the values of all storage locations in [contract] that are read or written between [startPtr] and the next
     * full storage havoc of [contract].
     * If the first operation on a storage location after this position is load, it has the value assigned by the model.
     * If the first operation is store, or it isn't used until the next havoc / end of program, the value is DONT_CARE.
     * The program is split between positions where the storage is havoced.
     */
    private fun findStorageIncarnation(contract: BigInteger, startPtr: CmdPointer) {
        val contractStorage: ContractStorage = mutableMapOf()

        val snippetsUntilHavoc = seqGen
            .snippets(startPtr)
            .takeWhile { it !is StorageGlobalChangeSnippet.StorageHavocContract || it.addr != contract }

        /**
         * a storage hook makes its value observable, in the sense that it exposes
         * the value in that storage location prior to the hook invocation.
         * this means that a hook accessing a storage location would affect
         * the analysis here.
         *
         * [InlinedHook] doesn't have enough data to generate a complete
         * [DisplaySymbolWrapper] on its own.
         * instead, we keep the relevant data from the hook snippet
         * and use it later when we see a [StorageSnippet].
         */
        val idpToStorageHookValue: MutableMap<InstantiatedDisplayPath, TACValue> = mutableMapOf()

        for (snippetCmd in snippetsUntilHavoc) {
            when {
                snippetCmd is StorageSnippet && snippetCmd.contractInstance == contract -> {
                    val idp = toInstantiatedDisplayPath(snippetCmd.displayPath)

                    if (idp !in contractStorage) {
                        contractStorage[idp] = when(snippetCmd) {
                            is StorageSnippet.DirectStorageLoad,
                            is StorageSnippet.DirectStorageHavoc,
                            is StorageSnippet.LoadSnippet -> toDSW(snippetCmd, ComputationalTypes.HAVOC)

                            is StorageSnippet.StoreSnippet -> {
                                idpToStorageHookValue[idp]
                                    ?.let { storageHookValue -> toDSW(snippetCmd, ComputationalTypes.HAVOC).copy(value = storageHookValue) }
                                    ?: toUninitializedDSW(snippetCmd)
                            }
                        }
                    }
                }

                snippetCmd is InlinedHook -> {
                    val idp = snippetCmd.displayPath?.let(::toInstantiatedDisplayPath) ?: continue

                    val previousValue = snippetCmd
                        .previousStorageHookValue()
                        ?.let(model::instantiate)
                        ?: continue

                    idpToStorageHookValue[idp] = previousValue
                }

                else -> { }
            }
        }

        // Fill every display path we don't use in current incarnation with DONT_CARE.
        // incarnations are either from start until end / first havoc, or from havoc to next havoc / end.
        allStoragePaths[contract]?.forEachEntry { (idp, dsw) ->
            if (idp !in contractStorage) {
                contractStorage[idp] = dsw
            }
        }

        storageMap[contract] = contractStorage
    }

    /**
     * [storageToShowSym] is used for whole-storage comparisons -- since we can't update a storage variable pointwise in
     *  CVL, we can look up the corresponding state in [assignmentsSnapshots].
     */
    fun addStorageStateToCallTrace(currCallInstance: CallInstance.ScopeInstance, storageToShowSym: TACSymbol.Var?) {
        printCounter++ // counter for UI purpose.

        val storageToPrint = if (storageToShowSym != null) {
            assignmentsSnapshots[storageToShowSym.namePrefix] ?: throw IllegalStateException("Unknown storage ${storageToShowSym.namePrefix}.")
        } else {
            storageMap
        }

        val storageCallInstance = CallInstance.StorageTitleInstance("Storage State", printCounter)

        storageToPrint.toSortedMap().forEachEntry { (contractAddr, contractStorage) ->
            if (contractStorage.isEmpty()) {
                return@forEachEntry
            }

            val contractName = scene.getContract(contractAddr).name
            val contractCallInstance = CallInstance.StorageTitleInstance(contractName)
            storageCallInstance.addChild(contractCallInstance)

            contractStorage.toSortedMap().forEachEntry { (symbolName, symbolWrapper) ->
                val nameStr = symbolWrapper.name
                val valueStr = CallTraceValue.DSW(symbolWrapper)

                val changedSincePrev = changedSinceLastPrint[contractAddr]?.contains(symbolName) ?: false
                val changedSinceStart = allChangesSinceStart[contractAddr]?.contains(symbolName) ?: false

                val value = CallInstance.StorageValue(nameStr, valueStr)

                val symbolCallInstance = CallInstance.StorageStateValueInstance(
                    symbolWrapper.computationalType.callEndStatus,
                    symbolWrapper.range,
                    value,
                    changedSincePrev,
                    changedSinceStart,
                    contractAddr,
                    formatter
                )

                contractCallInstance.addChild(symbolCallInstance)

                Logger.regression { "StorageState: $contractName.${symbolCallInstance.name} - ${symbolCallInstance.status}" }
            }
            changedSinceLastPrint[contractAddr]?.clear()
        }

        if (storageCallInstance.children.isNotEmpty()) {
            currCallInstance.addChild(storageCallInstance)
        }
    }

    override fun toInstantiateDisplayWithValue(): Map<InstantiatedDisplayPath, TACValue> {
        return storageMap.flatMap { e ->
            e.value.map { InstantiatedDisplayPath.Root(scene.getContract(e.key).name, it.key) to (it.value.value ?: TACValue.Uninitialized)}
        }.toMap()
    }
}
