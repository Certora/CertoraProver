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
import analysis.split.SplitRewriter
import datastructures.stdcollections.*
import utils.Range
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import vc.data.*
import java.math.BigInteger


/**
 * Helps insert storage snippets into the given [SimplePatchingProgram]. Behavior depends on whether storage analysis
 * succeeded or failed.
 */
sealed class StorageSnippetInserter(protected val patcher: SimplePatchingProgram, protected val contId: BigInteger) {
    /**
     * @param loc holds the storage location, one way or another (see below)
     *
     * How [loc] is used:
     *  - if there is [TACMeta.DISPLAY_PATHS] present, we create a regular [SnippetCmd.EVMSnippetCmd.StorageSnippet],
     *  - if there is [TACMeta.STABLE_STORAGE_PATH] present, we create a [SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithPath],
     *  - if neither of these metas is present, but [loc] is non-null, we use it for a [SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym],
     *  - if [loc] is null, we don't insert a snippet at all.
     *
     *  [value] is a symbol containing the value that is stored or loaded
     */
    abstract fun createStorageSnippet(
        ptr: CmdPointer,
        newCmdsIndex: Int,
        loadCmd: TACCmd.Simple.AssigningCmd?,
        loc: TACSymbol?,
        value: TACSymbol?,
        storageType: EVMTypeDescriptor.EVMValueType?,
        range: Range.Range?,
    )

    /** [StorageSnippetInserter] works on a list of changes coming from [SplitRewriter], each change is represented by
     * a [CmdPointer] and a list of [TACCmd.Simple]s. This method signals each new change we're processing.
     * This is relevant for [StorageAnalysisSuccess] only, because only that control flow uses the new [TACCmd.Simple]s,
     * while the others retain the commands in the original non-split program and discard the ones from the changes map.
     */
    abstract fun nextChange(ptr: CmdPointer, newCmds: List<TACCmd.Simple>)

    protected fun rawStorageAccessSnippet(
        loadCmd: TACCmd.Simple.AssigningCmd?,
        loc: TACSymbol?,
        value: TACSymbol?,
        storageType: EVMTypeDescriptor.EVMValueType?,
        range: Range.Range?,
    ): SnippetCmd.EVMSnippetCmd.RawStorageAccess? {
        if (loc == null) {
            return null
        }
        val isLoad = loadCmd != null
        if (loc is TACSymbol.Var) {
            val path = loc.meta[TACMeta.STABLE_STORAGE_PATH]
            if (path != null) {
                return SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithPath(isLoad, path, contId, value, storageType, range)
            }
        }
        return SnippetCmd.EVMSnippetCmd.RawStorageAccess.WithLocSym(isLoad, loc, contId, value, storageType, range)
    }

    /** Called when all changes have been registered to this object, will apply those changes to [patcher]. */
    abstract fun applyChanges()

    companion object {
        private fun storageType(v: TACSymbol.Var) = v.meta.find(TACMeta.STORAGE_TYPE)


        /**
         * Takes the result of [SplitRewriter], which is a list of changes to the tac program ([changes]).
         *
         * Based on those changes inserts [EVMSnippetCmd.StorageSnippet]s after each storage operation (load or store).
         * This is done on a best-effort basis. If storage analysis succeeded, all the relevant metas should be present.
         * If storage analysis failed, snippets are inserted when there is enough information available, either as meta
         * information, or as a symbol carrying a raw storage address.
         * Note that the changes may be [TACCmd.Simple.WordStore] or [TACCmd.Simple.AssigningCmd.WordLoad], or a simple
         * [TACCmd.Simple.AssigningCmd.AssignExpCmd]. In the former two cases, there's always at least the raw location
         * available, but in the latter we have to rely solely on metas to get information about the storage address.
         *
         * Note also that the commands in [changes] will only be inserted into the tac program if storage analysis succeeded
         * (using [StorageAnalysisSuccess]). Otherwise those changes are discarded, and just the snippets will be inserted into
         * the original tac program ([coreTacProg]).
         */
        fun applyAndAnnotEvmStorageSnippet(
            changes: SplitRewriter.Changes,
            coreTacProg: CoreTACProgram,
            contId: BigInteger,
            isStorageAnalysisFailed: Boolean
        ): SimplePatchingProgram {

            val graph = coreTacProg.analysisCache.graph
            val patcher = coreTacProg.toPatchingProgram()

            val varsDeclaredOnSuccessOnly = changes.newOtherVars + changes.newStorageVars

            val annotator: StorageSnippetInserter = if (!isStorageAnalysisFailed) {
                StorageAnalysisSuccess(patcher, contId, changes)
            } else {
                StorageAnalysisFailFactory.preprocessSimpleAssign(
                    coreTacProg.name,
                    graph,
                    patcher,
                    changes.changes,
                    contId,
                    varsDeclaredOnSuccessOnly,
                )
            }

            for ((ptr, newCmds) in changes.changes) {
                annotator.nextChange(ptr, newCmds)

                for (index in newCmds.size - 1 downTo 0) {
                    /** if a symbol is from [changes] only the [StorageAnalysisSuccess] annotator will declare
                     * it in the program (if we'd still add it, we'd get a type error down the line) */
                    fun takeValue(s: TACSymbol) =
                        when (s) {
                            // do declaration issues --> keep it
                            is TACSymbol.Const -> true
                            is TACSymbol.Var -> when (annotator) {
                                /** [s] will be declared by [annotator] -> keep it */
                                is StorageAnalysisSuccess,
                                    /** [s] might be used to find another value by [StorageAnalysisFailWithMeta]
                                     * --> postpone the check to the annotator
                                     * (architecture itch, perhaps will get better through refactoring) */
                                is StorageAnalysisFailWithMeta ->
                                    true
                                /** value will be added to storage snippets as is -- only take it if it's in the
                                 * original program */
                                is DummyStorageAnalysisFail ->
                                    s !in varsDeclaredOnSuccessOnly
                            }
                        }

                    val currCmd = newCmds[index]
                    val range = currCmd.sourceRange()

                    when (currCmd) {
                        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                            // only taking symbols that have meta we can use here, since in this assigment-of-scalars case here
                            // we don't have a way of getting the raw storage location, like we do in the WordLoad and WordStore
                            // cases
                            fun takeLocSymOnScalarAssignment(s: TACSymbol.Var) =
                                TACMeta.STABLE_STORAGE_PATH in s.meta ||
                                    TACMeta.DISPLAY_PATHS in s.meta

                            val lhs = currCmd.lhs
                            val rhs = currCmd.rhs
                            // load
                            if (rhs is TACExpr.Sym.Var) {
                                annotator.createStorageSnippet(
                                    ptr,
                                    index + 1,
                                    currCmd,
                                    loc = rhs.s.takeIf(::takeLocSymOnScalarAssignment),
                                    lhs.takeIf(::takeValue),
                                    storageType(rhs.s),
                                    range,
                                )
                            }
                            // store
                            if (rhs is TACExpr.Sym) {
                                annotator.createStorageSnippet(
                                    ptr,
                                    index + 1,
                                    loadCmd = null,
                                    loc = lhs.takeIf(::takeLocSymOnScalarAssignment),
                                    rhs.s.takeIf(::takeValue),
                                    storageType(lhs),
                                    range,
                                )
                            }
                        }
                        is TACCmd.Simple.AssigningCmd.WordLoad -> {
                            annotator.createStorageSnippet(
                                ptr,
                                index + 1,
                                currCmd,
                                currCmd.loc,
                                currCmd.lhs.takeIf(::takeValue),
                                storageType(currCmd.base),
                                range,
                            )
                        }
                        is TACCmd.Simple.WordStore -> {
                            annotator.createStorageSnippet(
                                ptr,
                                index + 1,
                                loadCmd = null,
                                currCmd.loc,
                                currCmd.value.takeIf(::takeValue),
                                storageType(currCmd.base),
                                range,
                            )
                        }
                        else -> Unit
                    }
                }
            }

            /** give raw snippets to the loads and stores that do not appear in [changes] */
            graph.commands.filter { (ptr, _) -> ptr !in changes.changes }.forEach { (ptr, cmd) ->
                /** [nextChange] is interesting only in the StorageAnalysisSuccess case -- we initialize the changes-list with
                 * the original load/store, since we'll call patcher.replace.. so here we're basically emulating the
                 * way that [cmd] would have occurred in [changes] without actually differing from the command in the
                 * original program. */
                annotator.nextChange(ptr, mutableListOf(cmd))

                fun takeValueSym(s: TACSymbol) =
                    s is TACSymbol.Const || (s is TACSymbol.Var && s in coreTacProg.symbolTable.tags)

                when (cmd) {
                    is TACCmd.Simple.AssigningCmd.WordLoad ->
                        annotator.createStorageSnippet(
                            ptr,
                            1, /** add right after the single command we registered via the [nextChange] call */
                            cmd,
                            cmd.loc,
                            cmd.lhs.takeIf(::takeValueSym),
                            storageType(cmd.base),
                            cmd.sourceRange(),
                        )
                    is TACCmd.Simple.WordStore ->
                        annotator.createStorageSnippet(
                            ptr,
                            1,
                            loadCmd = null,
                            cmd.loc,
                            cmd.value.takeIf(::takeValueSym),
                            storageType(cmd.base),
                            cmd.sourceRange(),
                        )
                    else -> Unit
                }
            }
            annotator.applyChanges()
            return patcher
        }
    }
}


