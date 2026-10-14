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
import java.math.BigInteger


/**
 * A simple wrapper for [SimplePatchingProgram] which supports good debug printing and easy erasing of blocks
 * via setting of a blocks new successors.
 *
 *  1. erases whatever follows a constant-false assume, and the block's successors (see [cutAfterAssumeFalse]).
 *  2. automatically removes all unreachable blocks.
 *  3. when asked to (see [toCode]), and under cone-of-influence optimizations, erases whatever follows the last
 *     assert of a leaf block, and leaf blocks with no assert at all (see [cutAfterLastAsserts]).
 *  4. fixes annotations if an assert was erased.
 *  5. fixes leino block vars when their blocks were erased.
 *
 * Edits are recorded as replacements of the original commands and as new successor sets, and the program they
 * describe is the *current* program (see [current], [currentSuccs]); everything that erases code is a [cut] of a
 * block at some position of its current commands.
 */
class PatchingProgramWrapper(private val code: CoreTACProgram) {
    private val graph = code.analysisCache.graph
    private val replacements = mutableMapOf<CmdPointer, List<TACCmd.Simple>>()
    private val succs = mutableMapOf<NBId, TreapSet<NBId>>()
    private val root = graph.rootBlocks.single().id
    private val newVars = mutableSetOf<TACSymbol.Var>()

    /** the commands currently standing at [ptr]: its replacement, or the original command */
    private fun current(ptr: CmdPointer): List<TACCmd.Simple> =
        replacements[ptr] ?: listOf(graph.toCommand(ptr))

    /** the current commands of block [b] */
    private fun current(b: NBId): List<TACCmd.Simple> =
        graph.lcmdSequence(b).flatMap { current(it.ptr) }.toList()

    /** the current successors of block [b] */
    private fun currentSuccs(b: NBId): TreapSet<NBId> =
        succs[b] ?: graph.succ(b)

    /** the pointer holding the last current command of [b], or null if nothing is left of it */
    private fun currentLastPtr(b: NBId): CmdPointer? =
        graph.lcmdSequence(b, reverse = true).firstOrNull { current(it.ptr).isNotEmpty() }?.ptr

    /** It's the responsibility of the caller to make sure this is consistent with changes in jump commands */
    private fun setSuccs(nbid: NBId, nexts: TreapSet<NBId>) {
        succs[nbid] = nexts
    }

    /**
     * Keeps the first [keep] current commands of [b], erases the rest, and cuts its successors: [b] is a leaf from
     * here on. With `keep = 0` nothing is left of [b].
     */
    private fun cut(b: NBId, keep: Int) {
        var seen = 0
        for ((ptr, _) in graph.lcmdSequence(b)) {
            val cmds = current(ptr)
            when {
                seen >= keep -> if (cmds.isNotEmpty()) {
                    delete(ptr)
                }

                seen + cmds.size > keep -> replacements[ptr] = cmds.take(keep - seen)
            }
            seen += cmds.size
        }
        if (currentSuccs(b).isNotEmpty()) {
            setSuccs(b, treapSetOf())
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

    fun addBefore(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        replacements[where] = newCmds + current(where)
    }

    fun addBefore(where: CmdPointer, newCmd : TACCmd.Simple) =
        addBefore(where, listOf(newCmd))

    fun addAfter(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        replacements[where] = current(where) + newCmds
    }

    fun addAfter(where: CmdPointer, newCmd : TACCmd.Simple) =
        addAfter(where, listOf(newCmd))

    /**
     * With [cutAfterLastAsserts], and under cone-of-influence optimizations, whatever no assert can be reached from is
     * erased (see the private function of that name). It is opt-in because it rewrites conditional jumps into
     * assert-free arms, and `OverflowPatternRewriter` must still see those as they are: only a pass that runs after
     * it should ask for this.
     */
    fun toCode(handleLeinoVars: Boolean = false, cutAfterLastAsserts: Boolean = false) =
        toPatchingProgram(handleLeinoVars, cutAfterLastAsserts).toCode(code)

    fun toCode(newName : String, handleLeinoVars: Boolean = false, cutAfterLastAsserts: Boolean = false) =
        toPatchingProgram(handleLeinoVars, cutAfterLastAsserts, newName).toCode(code)

    /** The conditional jump ending [src] (as it currently stands) becomes an assumption of its branch to [dst]. */
    fun jumpiTojump(src: NBId, dst: NBId) {
        require(currentSuccs(src).isNotEmpty()) { "$src is already a leaf (a sink?), its jump cannot be rewritten" }
        val ptr = currentLastPtr(src) ?: error("Nothing is left of $src")
        val cmds = current(ptr)
        val cmd = cmds.last()
        require(cmd is TACCmd.Simple.JumpiCmd) { "$src does not end with a conditional jump: $cmd" }
        val assumeCmd = when {
            cmd.dst == dst -> TACCmd.Simple.AssumeCmd(cmd.cond, "jumpiTojump")
            cmd.elseDst == dst -> TACCmd.Simple.AssumeNotCmd(cmd.cond)
            else -> error("$dst is not one of the destinations of $cmd")
        }
        replacements[ptr] = cmds.dropLast(1) + assumeCmd + TACCmd.Simple.JumpCmd(dst, meta = cmd.meta)
        setSuccs(src, treapSetOf(dst))
    }

    /**
     * Turns block [b] into a sink: [cmds] (typically an `assume false`) is all that is left of it, and its successors
     * are cut, so whatever only [b] reached becomes unreachable and is erased along with the rest. When [toCode] is
     * asked to cut after the last asserts, a sink with no assert is itself erased, and the jumps into it become
     * assumptions.
     */
    fun replaceBlockWithSink(b: NBId, cmds: List<TACCmd.Simple>) {
        require(cmds.isNotEmpty()) { "A sink block must keep at least one command" }
        cut(b, 0)
        replace(CmdPointer(b, 0), cmds)
    }

    /** Nothing is left of the program but its (empty) root block. */
    fun eraseAll() =
        cut(root, 0)

    private fun reachedBlocks() =
        getReachable1(root, ::currentSuccs)

    /**
     * Erases whatever follows the last non-trivial assert of [b], which becomes a leaf; returns false, leaving [b] as
     * it is, if it has no such assert.
     */
    private fun truncateAfterLastAssert(b: NBId): Boolean {
        val i = current(b).indexOfLast(::isNonTrivialAssert)
        if (i < 0) {
            return false
        }
        cut(b, i + 1)
        return true
    }

    /** Fixes assert annotations */
    private fun updateAssertSnippetsOf(where: CmdPointer) {
        val oldAssert = graph.toCommand(where)
        require(oldAssert is TACCmd.Simple.AssertCmd)

        val (start, end) = assertAnnotations(code, where) ?: return

        val newCmds = replacements[where]
        val newAssert = newCmds
            ?.filterIsInstance<TACCmd.Simple.AssertCmd>()
            ?.also {
                check(it.size <= 1) {
                    "don't know how to handle the replacement of an assert with more than one assert"
                }
            }
            ?.singleOrNull()

        /** annotations shouldn't really be touched, but they may be erased by "accident" (e.g. [cutAfterLastAsserts]) */
        fun requireAnnotationsUntouched() =
            require(replacements[start.ptr].isNullOrEmpty() && replacements[end.ptr].isNullOrEmpty())

        when {
            newCmds == null -> {
                // if the assert wasn't changed, neither should the annotations.
                requireAnnotationsUntouched()
                replacements -= end.ptr
                replacements -= start.ptr
            }

            newAssert == null -> {
                // if the assert was erased, then so should the annotations, wherever they now stand: a sink may have
                // replaced the first command of the block, which can be the start annotation.
                for (annot in listOf(start, end)) {
                    replacements[annot.ptr] = replacements[annot.ptr]?.filter { it != annot.cmd }.orEmpty()
                }
            }

            else -> {
                // if it was changed, then the start annotation should change, and the end should remain as is.
                requireAnnotationsUntouched()
                start.cmd.mapCond(newAssert.o)
                    ?.let { replace(start.ptr, it) }
                    ?: run { replacements -= start.ptr }
                replacements -= end.ptr
            }
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


    /**
     * Whatever follows a constant-false assume is unreachable, whatever the mode: each block is cut right after its
     * first such assume and its successors are dropped. This runs before reachability is computed, so whatever hung
     * only off the dead tail goes with it. The assume itself stays, as the sink; under cone-of-influence
     * optimizations [cutAfterLastAsserts] then trims the block back to the last assert before it, or drops it.
     */
    private fun cutAfterAssumeFalse() {
        fun TACCmd.Simple.isAssumeFalse() =
            this is TACCmd.Simple.Assume && condExpr.evalAsConst() == BigInteger.ZERO

        for (b in graph.blockIds) {
            val i = current(b).indexOfFirst { it.isAssumeFalse() }
            if (i >= 0) {
                cut(b, i + 1)
            }
        }
    }

    /**
     * Under cone-of-influence optimizations only asserts matter, so whatever follows the last non-trivial assert of a
     * leaf block cannot affect the result and is erased, and a leaf block with no such assert is dead altogether: it
     * is dropped from [reached], a conditional jump into it becomes an assumption of the other branch, and a block that
     * thereby loses its last successor becomes a leaf itself and is treated the same way. When this reaches the root,
     * the whole program is erased.
     *
     * A dead leaf is dropped only if every predecessor can be detached from it by editing a plain jump: one that ends
     * with a summary (which carries its own successors) keeps its dead leaf, but emptied (the patcher fills it with a
     * nop).
     *
     * [sinksFirst] is a topological order of the original graph, which is one of the current graph too, as the
     * wrapper only ever removes edges.
     */
    private fun cutAfterLastAsserts(sinksFirst: List<NBId>, reached: MutableSet<NBId>) {
        fun currentPreds(b: NBId) =
            graph.pred(b).filter { it in reached && b in currentSuccs(it) }

        /** can the edge from [p] to a dead successor be removed by editing [p]'s jump? */
        fun canDetach(p: NBId) =
            current(p).lastOrNull() !is TACCmd.Simple.SummaryCmd

        /** [p] lost all its successors: it becomes a leaf, and its jump, if any, goes */
        fun makeLeaf(p: NBId) {
            val cmds = current(p)
            val last = cmds.lastOrNull()
            cut(p, cmds.size - (if (last is TACCmd.Simple.JumpCmd || last is TACCmd.Simple.JumpiCmd) { 1 } else { 0 }))
        }

        for (b in sinksFirst) {
            if (b !in reached) {
                continue
            }
            val all = currentSuccs(b)
            val live = all.filter { it in reached }
            if (live.isNotEmpty()) {
                if (live.size < all.size) {
                    // a conditional jump lost one arm (a dead leaf is dropped only if this is possible, see `canDetach`)
                    jumpiTojump(b, live.single())
                }
                continue
            }
            if (all.isNotEmpty()) {
                makeLeaf(b)
            }
            if (!truncateAfterLastAssert(b)) {
                // nothing of [b] matters; it is dropped too, unless it is the root or a summary jumps into it
                cut(b, 0)
                if (b != root && currentPreds(b).all(::canDetach)) {
                    reached -= b
                }
            }
        }
    }


    private fun toPatchingProgram(
        handleLeinoVars: Boolean,
        cutAfterLastAsserts: Boolean,
        newName: String = code.name
    ): SimplePatchingProgram {
        val patcher = code.toPatchingProgram(newName)
        patcher.addVarDecls(newVars)

        val sinksFirst = topologicalOrder(graph.blockSucc)
        cutAfterAssumeFalse()
        val reachedBlocks = reachedBlocks().toMutableSet()
        if (cutAfterLastAsserts && code.coiOptimizations) {
            cutAfterLastAsserts(sinksFirst, reachedBlocks)
        }
        val erased = graph.blockIds.toSet() - reachedBlocks

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

        replacements
            .filter { (ptr, _) -> ptr.block in reachedBlocks }
            .forEachEntry { (ptr, newCmds) ->
                val (nbid, pos) = ptr
                val newSuccs = runIf(pos == graph.elab(nbid).commands.lastIndex) {
                    if (newCmds.isEmpty()) {
                        treapSetOf()
                    } else {
                        currentSuccs(nbid)
                    }
                }
                patcher.replaceCommand(ptr, remap(newCmds), newSuccs)
            }

        /** the last step, because the jump commands must be updated first */
        sinksFirst
            .filter { it !in reachedBlocks }
            .asReversed()
            .forEach(patcher::removeBlock)
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

        fun processBlock(g: MultiMap<NBId, NBId>, b : NBId) {
            val newSuccs = g[b].orEmpty()
            when (newSuccs.size) {
                0 -> {
                    // If we're doing cone-of-influence optimizations, or if some block was erased anyway (eg. it had an
                    // `assume false`), we search backwards for a non-trivial assert and remove all code after it.
                    if (code.coiOptimizations || graph.succ(b).isNotEmpty()) {
                        check(truncateAfterLastAssert(b)) { "No assert in leaf block $b, so why is it not erased?" }
                    } else {
                        // an original leaf, kept whole: nothing can have given it successors
                        check(currentSuccs(b).isEmpty()) { "Leaf block $b has successors ${currentSuccs(b)}" }
                    }
                }

                1 -> if (graph.succ(b).size == 2) {
                    jumpiTojump(b, newSuccs.single())
                }
            }
        }

        if (assumeFalseOnDroppedLeaves) {
            val droppedLeaves = blocks.flatMapToSet { graph.succ(it) } - blocks
            for (b in droppedLeaves) {
                replaceBlockWithSink(b, listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "deadLeaf")))
            }
            val augmentedG = g.toMutableMultiMap()
            droppedLeaves.forEach { leaf ->
                graph.pred(leaf).forEach {
                    augmentedG.add(it, leaf)
                }
            }
            blocks.forEach { processBlock(augmentedG, it) }
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
                runIf(it !in reachable) { "ERASED" }.orEmpty()
            }
    }


}
