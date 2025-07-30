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

package report.globalstate

import analysis.CmdPointer
import analysis.storage.InstantiatedDisplayPath
import datastructures.stdcollections.*
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import solver.CounterexampleModel
import spec.CVLKeywords
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import tac.NBId
import vc.data.*
import vc.data.state.TACValue

typealias Balances = MutableMap<CallTraceValue, TACValue>
typealias ImmutableBalances = Map<CallTraceValue, TACValue>

fun Balances.toImmutable() = this.toMap()

/**
 * A unit of the [CallTrace]. Represents the balances during the flow of the CounterExample TACProgram chosen by the SMT.
 */
internal class BalancesState(
    private val seqGen: SequenceGenerator,
    private val model: CounterexampleModel,
    private val formatter: CallTraceValueFormatter
) : DebugAdapterVariableState{
    private var printCounter = 0 // counter for UI purpose

    private val balances: Balances = mutableMapOf()
    private val allBalances: MutableSet<CallTraceValue> = mutableSetOf()
    private val assignmentsSnapshots: MutableMap<String, ImmutableBalances> = mutableMapOf()
    private val invocationBackupSnapshots: MutableMap<Int, ImmutableBalances> = mutableMapOf()

    init {
        findAllBalances()
        findBalanceIncarnation(zeroPtr)
    }

    private fun toUIAddressString(addr: TACSymbol) =
        CallTraceValue.evmCtfValueOrUnknown(model.valueAsTACValue(addr), EVMTypeDescriptor.address)

    fun handleBalanceSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet) {
        val addrStr = toUIAddressString(snippetCmd.addr)
        balances[addrStr] = model.valueAsTACValue(snippetCmd.balance) ?: TACValue.Uninitialized
    }

    fun handleTransferSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.TransferSnippet) {
        val sender = toUIAddressString(snippetCmd.srcAccountInfo.addr)
        val receiver = toUIAddressString(snippetCmd.trgAccountInfo.addr)
        balances[sender] = model.valueAsTACValue(snippetCmd.srcAccountInfo.new) ?: TACValue.Uninitialized
        balances[receiver] = model.valueAsTACValue(snippetCmd.trgAccountInfo.new) ?: TACValue.Uninitialized
    }

    fun handleHavocBalance(blockId: NBId, pos: Int) {
        val startPtr = CmdPointer(blockId, pos + 1)
        findBalanceIncarnation(startPtr)
    }

    private fun populateBalances(balancesSnapshot: ImmutableBalances) {
        balances.clear()
        balances.putAll(balancesSnapshot)
    }

    fun handleStorageGlobalChanges(snippetCmd: SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet) {
        when (snippetCmd) {
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot -> {
                if (snippetCmd.rhs == null || snippetCmd.rhs.namePrefix == CVLKeywords.lastStorage.keyword) {
                    assignmentsSnapshots[snippetCmd.lhs.namePrefix] = balances.toImmutable()
                } else {
                    assignmentsSnapshots[snippetCmd.lhs.namePrefix] = assignmentsSnapshots[snippetCmd.rhs.namePrefix]
                            ?: throw IllegalStateException("Failed to find the storage snapshot of ${snippetCmd.rhs} when handling its assignment to ${snippetCmd.lhs}")
                }
            }
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRestoreSnapshot -> {
                assignmentsSnapshots[snippetCmd.name.namePrefix]?.let { populateBalances(it) }
                    ?: throw IllegalStateException("Failed to find the storage snapshot of ${snippetCmd.name} when restoring.")
            }
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageBackupPoint -> {
                invocationBackupSnapshots[snippetCmd.calleeTxId] = balances.toImmutable()
            }
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRevert -> {
                invocationBackupSnapshots[snippetCmd.calleeTxId]?.let { populateBalances(it) }
                    ?: throw IllegalStateException("Failed to revert the storage to state before call #${snippetCmd.calleeTxId}.")
            }
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageHavocContract,
            is SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageResetContract -> {
            }
        }
    }

    private fun findAllBalances() {
        for (snippetCmd in seqGen.snippets()) {
            when (snippetCmd) {
                is SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet -> {
                    val addrStr = toUIAddressString(snippetCmd.addr)
                    allBalances.add(addrStr)
                }
                is SnippetCmd.EVMSnippetCmd.TransferSnippet -> {
                    val sender = toUIAddressString(snippetCmd.srcAccountInfo.addr)
                    val receiver = toUIAddressString(snippetCmd.trgAccountInfo.addr)
                    allBalances.add(sender)
                    allBalances.add(receiver)
                }
                else -> continue
            }
        }
    }

    private fun insertBalanceIfAbsent(addressSym: TACSymbol, balanceSym: TACSymbol) {
        val addr = toUIAddressString(addressSym)
        if (addr !in balances) {
            balances[addr] = model.valueAsTACValue(balanceSym) ?: TACValue.Uninitialized
        }
    }

    /**
     * Find the values of all balances at position [startPtr].
     * The program is split between positions where the balances are havoced.
     */
    private fun findBalanceIncarnation(startPtr: CmdPointer) {
        balances.clear()

        for (snippetCmd in seqGen.snippets(startPtr)) {
            when (snippetCmd) {
                is SnippetCmd.EVMSnippetCmd.HavocBalanceSnippet -> {
                    break
                }
                is SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet -> {
                    insertBalanceIfAbsent(snippetCmd.addr, snippetCmd.balance)
                }
                is SnippetCmd.EVMSnippetCmd.TransferSnippet -> {
                    insertBalanceIfAbsent(snippetCmd.srcAccountInfo.addr, snippetCmd.srcAccountInfo.old)
                    insertBalanceIfAbsent(snippetCmd.trgAccountInfo.addr, snippetCmd.trgAccountInfo.old)
                }
                else -> continue
            }
        }

        // Fill balance of every address we don't see in current incarnation with TACValue.Uninitialized.
        // incarnations are either from start until end / first havoc, or from havoc to next havoc / end.
        allBalances.forEach { addr ->
            if (addr !in balances) {
                balances[addr] = TACValue.Uninitialized
            }
        }
    }

    fun addBalanceStateToCallTrace(currCallInstance: CallInstance.ScopeInstance, storageToShowSym: TACSymbol.Var?) {
        printCounter++ // counter for UI purpose.

        val storageToPrint = if (storageToShowSym != null) {
            assignmentsSnapshots[storageToShowSym.namePrefix] ?: throw IllegalStateException("Unknown storage ${storageToShowSym.namePrefix}.")
        } else {
            balances
        }

        val balancesCallInstance = CallInstance.StorageTitleInstance("Balances", printCounter)

        storageToPrint
            .mapKeys { (addr, _) -> addr.toSarif(formatter, "balance").flatten() }
            .toSortedMap()
            .forEachEntry { (addrUIString, value) ->
                val valueStr = CallTraceValue.EVMType(value, EVMTypeDescriptor.UIntK(256))
                balancesCallInstance.addChild(CallInstance.BalanceValueInstance(addrUIString, valueStr, formatter))
            }

        if (balancesCallInstance.children.isNotEmpty()) {
            currCallInstance.addChild(balancesCallInstance)
        }
    }
    override fun toInstantiateDisplayWithValue(): Map<InstantiatedDisplayPath, TACValue> {
        return balances.mapKeys {
            InstantiatedDisplayPath.Root(it.key.toSarif(formatter, "").prettyPrint())
        }
    }

}
