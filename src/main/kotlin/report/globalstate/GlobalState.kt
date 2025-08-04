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
import analysis.TACCommandGraph
import analysis.storage.InstantiatedDisplayPath
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.CallTraceValueFormatter
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import tac.NBId
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.state.TACValue


interface DebugAdapterVariableState{
    fun toInstantiateDisplayWithValue(): Map<InstantiatedDisplayPath, TACValue>
}

/**
 * One of the elements of the [CallTrace].
 * Contains the states of storage and balances, ghosts, and more at one point in time during the violating execution
 * of the TAC program that the call trace describes.
 *
 * Note that this class and its contents ([GhostsState] etc.) are mutable. They are updated while we iterate through
 * the counter example execution in [CallTrace.generate], and rendered into [CallInstance]s on demand in each
 * then-current state during that process.
 */
internal class GlobalState(
    blocks: List<NBId>,
    graph: TACCommandGraph,
    model: CounterexampleModel,
    scene: ISceneIdentifiers,
    formatter: CallTraceValueFormatter,
) {
    private val variablesState = VariablesState(model)
    private val seqGen = SequenceGenerator(graph, blocks, model)
    val storageState = StorageState(seqGen, model, scene, formatter, variablesState)
    val balancesState = BalancesState(seqGen, model, formatter)
    val ghostsState = GhostsState(seqGen, model, variablesState)

    fun handleAssignments(stmt: TACCmd.Simple.AssigningCmd) {
        variablesState.handleAssignments(stmt)
    }

    fun handleStorageLocalChanges(snippetCmd: SnippetCmd.EVMSnippetCmd.StorageSnippet) {
        storageState.handleStorageLocalChanges(snippetCmd)
    }

    fun handleStorageGlobalChanges(snippetCmd: SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet, blockId: NBId, pos: Int) {
        storageState.handleStorageGlobalChanges(snippetCmd, blockId, pos)
        balancesState.handleStorageGlobalChanges(snippetCmd)
        ghostsState.handleStorageGlobalChanges(snippetCmd)
    }

    fun handleBalanceSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet) {
        balancesState.handleBalanceSnippet(snippetCmd)
    }

    fun handleTransferSnippet(snippetCmd: SnippetCmd.EVMSnippetCmd.TransferSnippet) {
        balancesState.handleTransferSnippet(snippetCmd)
    }

    fun handleHavocBalance(blockId: NBId, pos: Int) {
        balancesState.handleHavocBalance(blockId, pos + 1)
    }

    fun handleGhostAccessSnippet(snippetCmd: SnippetCmd.CVLSnippetCmd.GhostAccess) {
        ghostsState.handleGhostAccess(snippetCmd)
    }

    fun handleGhostHavoc(cmd: SnippetCmd.CVLSnippetCmd.GhostHavocSnippet, afterHavoc: CmdPointer) {
        ghostsState.handleHavoc(cmd, afterHavoc)
    }

    fun handleAllGhostsHavoc(afterHavoc: CmdPointer) {
        ghostsState.handleAllHavocs(afterHavoc)
    }

    fun computeGlobalState(storageToShowSym: TACSymbol.Var? = null, formatter: CallTraceValueFormatter): CallInstance.StorageTitleInstance {
        val storageName = storageToShowSym?.meta?.get(TACMeta.CVL_DISPLAY_NAME)
        val storageTitle = if (storageName != null) { "$storageName" } else { LABEL_GLOBAL_STATE }
        val globalStateCallInstance = CallInstance.StorageTitleInstance(storageTitle)

        storageState.addStorageStateToCallTrace(globalStateCallInstance, storageToShowSym)
        balancesState.addBalanceStateToCallTrace(globalStateCallInstance, storageToShowSym)
        ghostsState.addGhostsStateToCallTrace(globalStateCallInstance, storageToShowSym, formatter)

        return globalStateCallInstance
    }

    companion object {
        const val LABEL_GLOBAL_STATE = "Global State"
    }
}
