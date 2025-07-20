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

package verifier

import algorithms.getReachable1
import algorithms.topologicalOrder
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.SimpleCmdsWithDecls
import analysis.TACProgramPrinter
import com.certora.collect.*
import datastructures.MultiMap
import datastructures.add
import datastructures.stdcollections.*
import datastructures.toMutableMultiMap
import tac.NBId
import utils.*
import utils.Color.Companion.green
import vc.data.*


/**
 * A simple wrapper for [SimplePatchingProgram] which supports good debug printing and easy erasing of blocks
 * via setting of a blocks new successors.
 *
 *  1. automatically removes all unreachable blocks.
 *  2. fixes annotations if an assert was erased.
 *  3. fixes leino block vars when their blocks were erased.
 */
class PatchingProgramWrapper(private val code: CoreTACProgram) {
    private val graph = code.analysisCache.graph
    private val replacements = mutableMapOf<CmdPointer, List<TACCmd.Simple>>()
    private val succs = mutableMapOf<NBId, TreapSet<NBId>>()
    private val root = graph.rootBlocks.single().id
    private val replacedVars = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
    private val newVars = mutableSetOf<TACSymbol.Var>()
    private var eraseAll: Boolean = false

    fun replaceVarDefinition(oldVar : TACSymbol.Var, newVar : TACSymbol.Var) {
        val previous = replacedVars.put(oldVar, newVar)
        check(previous == null || previous == newVar) {
            "Replacing the same variable $oldVar with different new variables : $previous, $newVar"
        }
    }

    fun replace(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        replacements[where] = newCmds
    }

    fun replace(where: CmdPointer, newCmd: TACCmd.Simple) =
        replace(where, listOf(newCmd))

    fun replace(where: CmdPointer, newCmds: SimpleCmdsWithDecls) =
        replace(where, newCmds.cmds).also {
            newVars += newCmds.varDecls
        }

    fun delete(where: CmdPointer) =
        replace(where, emptyList())

    fun deleteAll(lcmds : Sequence<LTACCmd>) {
        for (lcmd in lcmds) {
            delete(lcmd.ptr)
        }
    }

    fun toCode(handleLeinoVars: Boolean = false) =
        toPatchingProgram(handleLeinoVars).toCode(code)

    fun toCode(newName : String, handleLeinoVars: Boolean = false) =
        toPatchingProgram(handleLeinoVars, newName).toCode(code)

    fun addBefore(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        replacements[where] = newCmds +
            (replacements[where] ?: listOf(graph.toCommand(where)))
    }

    fun addBefore(where: CmdPointer, newCmd : TACCmd.Simple) =
        addBefore(where, listOf(newCmd))

    fun addAfter(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        replacements[where] =
            (replacements[where] ?: listOf(graph.toCommand(where))) + newCmds
    }

    fun addAfter(where: CmdPointer, newCmd : TACCmd.Simple) =
        addAfter(where, listOf(newCmd))


    /** It's the responsibility of the caller to make sure this is consistent with changes in jump commands */
    private fun setSuccs(nbid: NBId, nexts: TreapSet<NBId>) {
        succs[nbid] = nexts
    }

    fun jumpiTojump(src: NBId, dst: NBId) {
        val (ptr, cmd) = graph.elab(src).commands.last()
        require(cmd is TACCmd.Simple.JumpiCmd)
        val assumeCmd = when {
            cmd.dst == dst -> TACCmd.Simple.AssumeCmd(cmd.cond, "jumpiTojump")
            cmd.elseDst == dst -> TACCmd.Simple.AssumeNotCmd(cmd.cond)
            else -> error("$dst is not one of the destinations of $cmd")
        }
        replace(
            ptr,
            listOf(
                assumeCmd,
                TACCmd.Simple.JumpCmd(dst, meta = cmd.meta)
            )
        )
        setSuccs(src, treapSetOf(dst))
    }


    private fun reachedBlocks() =
        if (eraseAll) {
            setOf(root)
        } else {
            getReachable1(root) {
                succs[it] ?: graph.succ(it)
            }
        }

    fun eraseAll() {
        eraseAll = true
    }

    /** Fixes assert annotations */
    private fun updateAssertSnippetsOf(where: CmdPointer) {
        val oldAssert = graph.toCommand(where)
        require(oldAssert is TACCmd.Simple.AssertCmd)

        val (start, end) = assertAnnotations(code, where) ?: return

        require( // annotation shouldn't really be touched, but they may be erased by "accident".
            replacements[start.ptr].isNullOrEmpty() && replacements[end.ptr].isNullOrEmpty()
        )

        val newCmds = replacements[where]
        if (newCmds == null) {
            // if the assert wasn't changed, neither should the annotations.
            replacements -= end.ptr
            replacements -= start.ptr
            return
        }

        val newAssert = newCmds
            .filterIsInstance<TACCmd.Simple.AssertCmd>()
            .also {
                check(it.size <= 1) {
                    "don't know how to handle the replacement of an assert with more than one assert"
                }
            }
            .singleOrNull()

        if (newAssert == null) {
            // if the assert was erased, then so should the annotations.
            delete(start.ptr)
            delete(end.ptr)
        } else {
            // if it was changed, then the start annotation should change, and the end should remain as is.
            start.cmd.mapCond(newAssert.o)
                ?.let { replace(start.ptr, it) }
                ?: run { replacements -= start.ptr }
            replacements -= end.ptr
        }
    }

    /**
     * Specific variables related to blocks via the Leino encoding must be removed if the block is removed
     */
    private class LeinoVarRemapper(val erased: Set<NBId>) {
        private fun isObsoleteLeinoVar(t: TACSymbol) =
            (t is TACSymbol.Var && t.meta[TACMeta.LEINO_REACH_VAR] in erased)

        private val remapper =
            object : DefaultTACCmdMapper() {
                override fun mapSymbol(t: TACSymbol): TACSymbol {
                    return if (isObsoleteLeinoVar(t)) {
                        TACSymbol.False
                    } else {
                        t
                    }
                }
            }

        fun shouldRemap(cmd: TACCmd.Simple) =
            cmd.getFreeVarsOfRhs().any(::isObsoleteLeinoVar)

        fun remap(cmd: TACCmd.Simple) =
            remapper.map(cmd)
    }


    private fun toPatchingProgram(handleLeinoVars: Boolean, newName: String = code.name): SimplePatchingProgram {
        val patcher = code.toPatchingProgram(newName)
        val reachedBlocks by lazy { reachedBlocks() }
        val erased = graph.blockIds.toSet() - reachedBlocks
        patcher.addVarDecls(newVars)

        /** must be the last step, because the jump commands must be update first */
        fun eraseBlocks() =
            topologicalOrder(graph.blockSucc)
                .filter { it !in reachedBlocks }
                .asReversed()
                .forEach(patcher::removeBlock)

        if (eraseAll) {
            graph.rootBlocks.forEach { (nbid, cmds) ->
                for (i in 0..<cmds.lastIndex) {
                    patcher.delete(CmdPointer(nbid, i))
                }
                patcher.replaceCommand(CmdPointer(nbid, cmds.lastIndex), emptyList(), treapSetOf())
            }
            eraseBlocks()
            return patcher
        }

        val remapper by lazy { LeinoVarRemapper(erased) }

        fun remap(cmds: List<TACCmd.Simple>) =
            if (handleLeinoVars) {
                cmds.map { remapper.remap(it) }
            } else {
                cmds
            }

        // done first because it can interfere with `fakeReplace` (as it can remove commands from `replacements`)
        reachedBlocks.forEach { b ->
            graph.lcmdSequence(b)
                .filter { it.cmd is TACCmd.Simple.AssertCmd }
                .forEach { updateAssertSnippetsOf(it.ptr) }
        }

        replacedVars.forEachEntry { (old, new) ->
            patcher.replaceVarDecl(old, new)
        }

        /** used to mark commands for processing later on */
        fun fakeReplace(ptr: CmdPointer) {
            if (ptr !in replacements) {
                replacements[ptr] = listOf(graph.toCommand(ptr))
            }
        }

        // first mark anything that needs leino var remapping.
        if (handleLeinoVars) {
            for (nbid in reachedBlocks) {
                graph.lcmdSequence(nbid)
                    .filter { (_, cmd) -> remapper.shouldRemap(cmd) }
                    .forEach { (ptr, _) -> fakeReplace(ptr) }
            }
        }

        // then the implicit jumps (there is no actual command, but there is a successor) that were changed
        succs.keys
            .filter { it in reachedBlocks }
            .forEach { fakeReplace(graph.elab(it).commands.last().ptr) }

        // debugPrinter().print(code)

        replacements
            .filter { (ptr, _) -> ptr.block in reachedBlocks }
            .forEachEntry { (ptr, newCmds) ->
                val (nbid, pos) = ptr
                val succs = runIf(pos == graph.elab(nbid).commands.lastIndex) {
                    if (newCmds.isEmpty()) {
                        treapSetOf()
                    } else {
                        succs[nbid] ?: graph.succ(nbid)
                    }
                }
                patcher.replaceCommand(ptr, remap(newCmds), succs)
            }

        eraseBlocks()
        return patcher
    }


    /**
     * Restricts [code] to the block graph [g] (which should be a subgraph of [code]'s graph).
     * Leaf blocks are expected to contain asserts, and whatever is after that assert is removed here.
     * [blocks] is the set of blocks we want to keep (default is all keys of [g], but leaves may not appear in [g],
     * and so we can specify them in [blocks]).
     *
     * If [assumeFalseOnDroppedLeaves] is true then blocks that should be erased but have a predecessor that shouldn't be
     * erased are instead replaced with a block that only has an `assume false` statement. This way the branching
     * remains intact, which is needed for overflow pattern detection in the "withRevert" case.
     *
     * Note that the set of blocks we will get when actually using this patching program may be smaller, because going
     * through [toPatchingProgram] (which we must do) first calculates the set of reachable blocks.
     */
    fun limitTACProgramTo(
        g: MultiMap<NBId, NBId>,
        blocks: Set<NBId> = g.keys,
        assumeFalseOnDroppedLeaves: Boolean = false
    ) {
        if (blocks.isEmpty()) {
            eraseAll()
            return
        }
        val origG = code.analysisCache.graph

        fun processBlock(g: MultiMap<NBId, NBId>, b : NBId) {
            val succs = g[b].orEmpty()
            when (succs.size) {
                0 -> {
                    // If we're doing cone-of-influence optimizations, or if some block was erased anyway (eg. it had an
                    // `assume false`), we search backwards for a non-trivial assert and remove all code after it.
                    if (code.coiOptimizations || origG.succ(b).isNotEmpty()) {
                        val lastAssert =
                            origG.blockCmdsBackwardSeq(b).firstOrNull { (ptr, cmd) ->
                                (replacements[ptr] ?: listOf(cmd)).any { isNonTrivialAssert(it) }
                            } ?: error("No assert in leaf block $b, so why is it not erased?")
                        val (block, eraseFrom) = lastAssert.ptr + 1
                        deleteAll(graph.lcmdSequence(block, low = eraseFrom))
                        setSuccs(block, treapSetOf())
                    } else {
                        setSuccs(b, treapSetOf())
                    }
                }

                1 -> if (origG.succ(b).size == 2) {
                    jumpiTojump(b, succs.single())
                }
            }
        }

        if (assumeFalseOnDroppedLeaves) {
            val droppedLeaves = blocks.flatMapToSet { graph.succ(it) } - blocks
            for (b in droppedLeaves) {
                deleteAll(graph.lcmdSequence(b))
                replacements[CmdPointer(b, 0)] = listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "deadLeaf"))
            }
            val augmentedG = g.toMutableMultiMap()
            droppedLeaves.forEach { leaf ->
                graph.pred(leaf).forEach {
                    augmentedG.add(it, leaf)
                }
            }
            blocks.forEach { processBlock(augmentedG, it) }
            droppedLeaves.forEach { setSuccs(it, treapSetOf()) }
        } else {
            blocks.forEach { processBlock(g, it) }
        }
    }


    fun debugPrinter(): TACProgramPrinter {
        val reachable = reachedBlocks()
        return TACProgramPrinter.standard()
            .extraLines { (ptr, cmd) ->
                when (val newCmds = replacements[ptr]) {
                    null -> emptyList()
                    emptyList<TACCmd>() -> listOf("DELETED".green)
                    else -> {
                        if (newCmds.first() == cmd) {
                            listOf("<original cmd> +".green) + newCmds.drop(1).map { it.toStringNoMeta().green }
                        } else {
                            newCmds.map { it.toStringNoMeta().green }
                        }
                    }
                }
            }
            .extraBlockInfo {
                runIf(it !in reachable || eraseAll) { "ERASED" }.orEmpty()
            }
    }


}
