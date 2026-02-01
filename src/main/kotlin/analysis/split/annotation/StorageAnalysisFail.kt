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

package analysis.split.annotation

import analysis.CmdPointer
import analysis.LTACSymbol
import analysis.TACCommandGraph
import analysis.split.SplitRewriter
import analysis.storage.DisplayPath
import analysis.storage.singleDisplayPathOrNull
import datastructures.stdcollections.*
import log.*
import utils.Range
import spec.cvlast.typedescriptors.EVMTypeDescriptor.EVMValueType
import tac.NBId
import vc.data.*
import vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)
typealias SymToLTACSym = MutableMap<TACSymbol, LTACSymbol>


/**
 * Utility interface for creating and adding EVM storage snippets to [TACProgram]s,
 * when the storage analysis fails.
 *
 * See [StorageSnippetInserter]. This case works under the assumption that we are _not_ using the "changes" coming from
 * [SplitRewriter], so the snippets inserted into the original tac program directly (via [patcher]) (in contrast to
 * [StorageAnalysisSuccess].
 */
sealed class StorageAnalysisFail(patcher: SimplePatchingProgram, contId: BigInteger)
    : StorageSnippetInserter(patcher, contId) {
    /** Maps [CmdPointer] to a list of new [TACCmd]s to be inserted via [patcher] in [applyChanges]. */
    private val cmdPtrToNewCmds: MutableMap<CmdPointer, MutableList<TACCmd.Simple>> = mutableMapOf()

    override fun nextChange(ptr: CmdPointer, newCmds: List<TACCmd.Simple>) {
        /* nop */
    }

    protected fun addEVMStorageSnippetCmd(
        where: CmdPointer,
        snippetCmd: SnippetCmd.EVMSnippetCmd?
    ) {
        if (snippetCmd != null) {
            cmdPtrToNewCmds.computeIfAbsent(where) { mutableListOf() }
                .add(0, snippetCmd.toAnnotation())
        }
    }

    override fun applyChanges() {
        cmdPtrToNewCmds.entries.forEach { (cmdPtr, newCmds) ->
            patcher.insertAfter(cmdPtr, newCmds)
        }
    }
}

/**
 * [patcher] is the patching TACProgram of the [TACProgram] mentioned above.
 * [changes] are the changes built by the [SplitRewriter] to the [TACProgram] above.
 * [contId] is the instanceId of the contract where the underlined method is located in.
 * [mapBlockToSimpleAssign] is a graph built using [graph] and [changes], in the preprocessing step
 * in [StorageAnalysisFailFactory].
 *
 * [varsFromChangesWontBeDeclared] contains symbols that occur in `changes` but won't be declared since storage analysis
 *          failed -- we cannot use them in the snippets
 */
class StorageAnalysisFailWithMeta(
    val graph: TACCommandGraph,
    patcher: SimplePatchingProgram,
    val changes: Map<CmdPointer, List<TACCmd.Simple>>,
    contId: BigInteger,
    val mapBlockToSimpleAssign: Map<NBId, Pair<SymToLTACSym, SymToLTACSym>>,
    val varsFromChangesWontBeDeclared: Set<TACSymbol.Var>,
): StorageAnalysisFail(patcher, contId) {

    /** All the original vars in the program. Used to check that we use only these in display paths */
    private val allVars = graph.symbolTable.vars

    /** returns true if [path] does not refer to variables that are not in the original program */
    private fun isValid(path: DisplayPath): Boolean =
            when (path) {
                is DisplayPath.Root -> true
                is DisplayPath.ArrayAccess -> path.index !is TACSymbol.Var || path.index in allVars
                is DisplayPath.FieldAccess -> true
                is DisplayPath.MapAccess -> path.key !is TACSymbol.Var || path.key in allVars
            } &&
            (path.base == null || isValid(path.base!!))



    /**
     * Given a [CmdPointer] [cmdPointer] from [changes], traversing [changes] in order/reverse order (depends on [goForward]).
     * Looking for a symbol which is a result of a sequence of simple assignments originating from [startingSym], using [mapBlockToSimpleAssign].
     * If a safety check made in the end by the safetyCheck function passes, returning the symbol mentioned above,
     * along with the [CmdPointer] which this symbol is located in. Otherwise, null is returned (we are giving up this storage annotation).
     */
    private fun getValueAndCmdPtr(
        cmdPointer: CmdPointer,
        startingSym: TACSymbol,
        goForward: Boolean
    ): LTACSymbol? {
        var valueAndPtr = LTACSymbol(cmdPointer, startingSym)

        /**
         * Iterates over [symToLTACSym] to find the symbol which accurately represents the value of the storage snippet we would like to create,
         * along with the [CmdPointer] this symbol is located in. A safety check, induced by [condFunc] is done after the iteration.
         * Returns whether the safety check passes.
         */
        fun iterate(symToLTACSym: SymToLTACSym?, condFunc: (TACCmd.Simple) -> Boolean): Boolean {
            while (true) {
                symToLTACSym?.get(valueAndPtr.symbol)?.let {
                    valueAndPtr = it
                } ?: break
            }
            val origCmd = graph.elab(valueAndPtr.ptr).cmd
            if (!condFunc(origCmd)) {
                logger.error { "did not expect to find the symbol ${valueAndPtr.symbol} in the TACCmd $origCmd" }
                return false
            }
            return true
        }

        // effectively for load commands
        if (goForward) {
            if (iterate(mapBlockToSimpleAssign[cmdPointer.block]?.second) {
                    valueAndPtr.symbol is TACSymbol.Const || (it is TACCmd.Simple.AssigningCmd
                        && it.lhs == valueAndPtr.symbol)
                }) {
                return valueAndPtr
            }
        } else {
            // effectively for store commands
            if (iterate(mapBlockToSimpleAssign[cmdPointer.block]?.first) {
                    valueAndPtr.symbol is TACSymbol.Const || (it is TACCmd.Simple.WordStore && it.value == valueAndPtr.symbol)
                        || (it is TACCmd.Simple.AssigningCmd && it.getFreeVarsOfRhs()
                        .contains(valueAndPtr.symbol))
                }) {
                return valueAndPtr
            }
        }
        return null
    }


    /**
     * Creates storage snippets for a PatchingTACProgram we did the storage analysis for.
     * [loadCmd] if not null, indicates that the created storage snippets should be
     * load; otherwise, store snippet.
     */
    override fun createStorageSnippet(
        ptr: CmdPointer,
        newCmdsIndex: Int,
        loadCmd: TACCmd.Simple.AssigningCmd?,
        loc: TACSymbol?,
        value: TACSymbol?,
        storageType: EVMValueType?,
        range: Range.Range?,
    ) {
        val valueOkToUse = value?.takeIf { it !in varsFromChangesWontBeDeclared }
        val locOkToUse = loc?.takeIf { it !in varsFromChangesWontBeDeclared }

        if (locOkToUse !is TACSymbol.Var) {
            // we don't have a display path
            addEVMStorageSnippetCmd(ptr, rawStorageAccessSnippet(contId, loadCmd, locOkToUse, valueOkToUse, storageType, range))
            return
        }

        val disPath: DisplayPath? = locOkToUse.singleDisplayPathOrNull()?.takeIf(::isValid)

        // TODO: CERT-3215
        if (disPath == null) {
            addEVMStorageSnippetCmd(ptr, rawStorageAccessSnippet(contId, loadCmd, locOkToUse, valueOkToUse, storageType, range))
            return
        }

        // I dunno why this is the case, but apparently it is, since it's only breaking in the "raw" cases, when we're
        // generating snippets from commands that are not in the `changes` produced by SplitRewriter..
        check(value != null) { "if we have a display path (here: $disPath) we always expect to have a value, but got null" }

        val (valPtr, symbol) = getValueAndCmdPtr(ptr, value, loadCmd != null) ?: run {
            addEVMStorageSnippetCmd(
                ptr,
                rawStorageAccessSnippet(
                    contId,
                    loadCmd,
                    locOkToUse,
                    valueOkToUse,
                    storageType,
                    range
                )
            )
            return
        }

        val snippetCmd = if (loadCmd != null) {
            StorageSnippet.LoadSnippet(symbol, disPath, storageType, contId, range, loadCmd)
        } else {
            StorageSnippet.StoreSnippet(symbol, disPath, storageType, contId, range)
        }

        addEVMStorageSnippetCmd(valPtr, snippetCmd)
    }

}


class DummyStorageAnalysisFail(patcher: SimplePatchingProgram, contId: BigInteger)
    : StorageAnalysisFail(patcher, contId) {
    override fun createStorageSnippet(
        ptr: CmdPointer,
        newCmdsIndex: Int,
        loadCmd: TACCmd.Simple.AssigningCmd?,
        loc: TACSymbol?,
        value: TACSymbol?,
        storageType: EVMValueType?,
        range: Range.Range?
    ) {
        addEVMStorageSnippetCmd(ptr, rawStorageAccessSnippet(contId, loadCmd, loc, value, storageType, range))
    }
}

/**
 * A factory of [StorageAnalysisFail].
 */
object StorageAnalysisFailFactory {

    /**
     * Using [graph] and [changes], traversing the code and for each [NBId], builds a graph (and a reversed version of it)
     * of simple assignments.
     * The idea is to map lhs symbol to rhs symbol (and opposite) for each simple assignment, together with a [CmdPointer] where this
     * simple assignment is located.
     * We do expect to not have a symbol which is a lhs/rhs of more than one simple assignment ([TACSymbol.Const] might be rhs ,multiple
     * times, but cannot be a lhs at all, so we are ok). If it's not the case, an error is logged and a [DummyStorageAnalysisFail] is returned.
     * Otherwise, a [StorageAnalysisFailWithMeta] (with the created graph).
     * [methodName] is the name of the Solidity method's [TACProgram] we are creating storage snippets for.
     */
    fun preprocessSimpleAssign(
        methodName: String,
        graph: TACCommandGraph,
        patcher: SimplePatchingProgram,
        changes: Map<CmdPointer, List<TACCmd.Simple>>,
        contId: BigInteger,
        varsFromChangesWontBeDeclared: Set<TACSymbol.Var>,
    ): StorageAnalysisFail {
        val mapBlockToSimpleAssign =
            mutableMapOf<NBId, Pair<SymToLTACSym, SymToLTACSym>>().also { mapBlockToSimpleAssign ->
                for ((ptr, newCmds) in changes) {
                    val mappings = mapBlockToSimpleAssign.computeIfAbsent(ptr.block) {
                        Pair(mutableMapOf(), mutableMapOf())
                    }
                    newCmds.forEach { newCmd ->
                        // if simple assignment
                        if (newCmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && newCmd.rhs is TACExpr.Sym) {
                            if (mappings.first[newCmd.lhs] != null) {
                                logger.error(
                                    "did not expect the symbol ${newCmd.lhs} to be a lhs of more than one simple assignment." +
                                        " giving up storage annotations for the method $methodName"
                                )
                                return DummyStorageAnalysisFail(patcher, contId)
                            }
                            mappings.first[newCmd.lhs] = LTACSymbol(ptr, newCmd.rhs.s)
                            // enter entry in the reversed map only if [TACSymbol.Var].
                            // [TACSymbol.Const] cannot be a lhs.
                            if (newCmd.rhs.s !is TACSymbol.Const) {
                                mappings.second[newCmd.rhs.s] = LTACSymbol(ptr, newCmd.lhs)
                            }
                        }
                    }
                }
            }

        return StorageAnalysisFailWithMeta(
            graph = graph,
            patcher = patcher,
            changes = changes,
            contId = contId,
            mapBlockToSimpleAssign = mapBlockToSimpleAssign,
            varsFromChangesWontBeDeclared = varsFromChangesWontBeDeclared,
        )
    }
}
