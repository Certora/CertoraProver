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
import analysis.storage.DisplayPath
import analysis.storage.singleDisplayPathOrNull
import datastructures.stdcollections.*
import utils.Range
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.updateInPlace
import vc.data.*
import vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet
import java.math.BigInteger

/**
 * See [StorageSnippetInserter]. This case works under the assumption that we are using the "changes" coming from
 * [SplitRewriter], so the snippets are "mixed in" with them (in contrast to the [StorageAnalysisFail] cases).
 */
class StorageAnalysisSuccess(patcher: SimplePatchingProgram, contId: BigInteger, val changes: SplitRewriter.Changes)
    : StorageSnippetInserter(patcher, contId) {

    /** internal storage for all changes to be applied in [applyChanges] in the end */
    private val changesWithSnippets = mutableMapOf<CmdPointer, MutableList<TACCmd.Simple>>()

    override fun nextChange(ptr: CmdPointer, newCmds: List<TACCmd.Simple>) {
        changesWithSnippets.computeIfAbsent(ptr) { newCmds.toMutableList() }
    }

    private fun addEVMStorageSnippetCmd(i: Int, ptr: CmdPointer, snippetCmd: SnippetCmd.EVMSnippetCmd?) {
        if (snippetCmd != null) {
            /** (default shouldn't be used in this update since [changesWithSnippets] is initalized) */
            changesWithSnippets.updateInPlace(ptr, default = mutableListOf() ) { cmds ->
                check(cmds.isNotEmpty()) { "expecting that changesWithSnippets has been initialized at this point with a non-empty set of newCmds" }
                cmds.add(i, snippetCmd.toAnnotation())
                cmds
            }
        }
    }

    /**
     * Creates storage snippets for [patcher].
     * [newCmdsWithSnippets] is a mutable list which keeps changes made to the
     * original [TACProgram] of [patcher], along with storage snippets.
     * [loadCmd] if not-null, indicates that the created storage snippets should be
     * load; otherwise, store snippet.
     * [indexToInsert] is the place we want to add the storage snippet at.
     * [newCmdsWithSnippets]. The idea is to place immediately after the storage command
     * the snippet refers to.
     */
    override fun createStorageSnippet(
        ptr: CmdPointer,
        newCmdsIndex: Int,
        loadCmd: TACCmd.Simple.AssigningCmd?,
        loc: TACSymbol?,
        value: TACSymbol?,
        storageType: EVMTypeDescriptor.EVMValueType?,
        range: Range.Range?,
    ) {
        if (loc !is TACSymbol.Var) {
            // we have no display path since there is no meta in this case --> make a raw snippet
            addEVMStorageSnippetCmd(newCmdsIndex, ptr, rawStorageAccessSnippet(loadCmd, loc, value, storageType, range))
            return
        }

        val disPath: DisplayPath? = loc.singleDisplayPathOrNull()

        if (disPath == null) {
            addEVMStorageSnippetCmd(newCmdsIndex, ptr, rawStorageAccessSnippet(loadCmd, loc, value, storageType, range))
            return
        }

        // I dunno why this is the case, but apparently it is, since it's only breaking in the "raw" cases, when we're
        // generating snippets from commands that are not in the `changes` produced by SplitRewriter..
        check(value != null) { "if we have a display path (here: $disPath) we always expect to have a value, but got null" }

        addEVMStorageSnippetCmd(newCmdsIndex, ptr,
            if (loadCmd != null) {
                StorageSnippet.LoadSnippet(
                    value,
                    disPath,
                    storageType,
                    contId,
                    range,
                    StorageSnippet.LoadSnippet.LinkableStorageReadId(loadCmd, disPath),
                )
            } else {
                StorageSnippet.StoreSnippet(
                    value,
                    disPath,
                    storageType,
                    contId,
                    range,
                )
            }
        )
    }

    override fun applyChanges() {
        for ((ptr, newCmds) in changesWithSnippets) {
            patcher.replaceCommand(ptr, newCmds)
            patcher.addVarDecls(changes.newOtherVars)
            patcher.addVarDecls(changes.newStorageVars)
        }
    }
}
