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

package vc.data

import algorithms.SimpleDominanceAnalysis
import algorithms.dominates
import algorithms.findRoots
import allocator.Allocator
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.TACExprWithRequiredCmdsAndDecls
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import report.dumps.CmdPtrMapping
import scene.PatchableProgram
import tac.*
import utils.*
import vc.data.tacexprutil.TACExprUtils
import vc.data.tacexprutil.tempVar
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.collections.component1
import kotlin.collections.component2


private val logger = Logger(LoggerTypes.COMMON)

/**
A mutable program data structure for "heavyweight" modifications to TAC Code. This allows:
 * Splitting blocks
 * Adding new blocks
 * Replacing commands with entirely new lists of commands

The CmdPointer class is central; it is used to uniquely identify
the locations of splits, which commands are to be replaced. The CmdPointers accepted by this class are pointers into
the *original* block graph. You are *not* expected to compute the changes to a commands "coordinates" based on other mutations.
In other words, this class takes care to compute the new locations of a commands coordinates that may change due to
graph mutations; i.e., mutations *cannot* invalidate pointers.

Note that this means replacements within replaced code are not possible; they do not appear in the original graph and therefore
have no coordinates. This is intentional, and supporting this would almost certainly cause bugs.
 */
open class PatchingTACProgram<T : TACCmd> protected constructor(
    val originalCode: Map<NBId, List<T>>,
    val origBlockGraph: BlockGraph,

    // can't be made protected because of replaceCommand(CmdPointer,CoreTACProgram) below
    val blockgraph: MutableReversibleDigraph<NBId>,
    val procedures: MutableSet<Procedure>,

    val name: String,
    var root: NBId? = null,
) : PatchableProgram {
    constructor(originalCode: Map<NBId, List<T>>,
                origBlockGraph: BlockGraph,
                name: String, root: NBId? = null): this(originalCode, origBlockGraph, MutableReversibleDigraph<NBId>(origBlockGraph), mutableSetOf(), name, root)

    /**
        Represents a point from the original program that can be patched.

        These are mutable, and can be looked up in via multiple routes.  It's thus important that PatchPoint have
        reference-equality semantics (no `equals` method), and we rely on this to optimize the data structures below.
      */
    private class PatchPoint<T : TACCmd>(
        val origCmd: T,
        var block: NBId,
        val origBlock: NBId? = null,
        val origPos: Int = -1, // Using -1 instead of null here makes this a primitive int, which takes less heap space
        var replacement: List<T>? = null,
        var removed: Boolean = false
    )

    // "linked" because tests rely on the order
    private val blocks = LinkedArrayHashMap<NBId, List<PatchPoint<T>>>()

    /** Maps original locations to [PatchPoint]s */
    private val originalPP = ArrayHashMap<NBId, List<PatchPoint<T>>>()
    private fun o2pp(p: CmdPointer) = originalPP[p.block]?.getOrNull(p.pos)?.takeIf { !it.removed }

    init {
        originalCode.forEachEntry { (nbid, cmds) ->
            val pp = cmds.mapIndexed { i, cmd -> PatchPoint(cmd, nbid, origBlock = nbid, origPos = i) }
            blocks[nbid] = pp
            originalPP[nbid] = pp
        }
    }

    val origBlockGraphReversed: Map<NBId, TreapSet<NBId>> by lazy {
        val map = ArrayHashMap<NBId, TreapSet<NBId>>()
        origBlockGraph.forEachEntry { (pred, succs) ->
            succs.forEach { succ ->
                map[succ] = map[succ].orEmpty() + pred
            }
        }
        map
    }

    protected val openBlocks = mutableSetOf<NBId>()
    protected val newVarDecls = mutableSetOf<TACSymbol.Var>()
    protected val replacedVarDecls = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
    protected val newUfs = mutableSetOf<FunctionInScope.UF>()
    protected val ufsToDrop = mutableSetOf<FunctionInScope.UF>()
    protected val replacedScalarUfs = mutableMapOf<FunctionInScope.UF, FunctionInScope.UF>()
    protected val replacedUfsAsTACExprs = mutableMapOf<TACExpr.Sym.Var, TACExpr.Sym.Var>()
    protected val newUninterpretedSorts = mutableSetOf<Tag.UserDefined.UninterpretedSort>()
    protected val newUserDefinedTags = mutableMapOf<String, Tag.UserDefined>()

    protected var replacedUfAxioms: UfAxioms? = null

    /** Replace the [UfAxioms] in the old program with the given ones. */
    fun replaceUfAxioms(ufAxioms: UfAxioms) {
        replacedUfAxioms = ufAxioms
    }

    /**
     * Used to reset [TACProgram.entryBlockId] in the event the head might have changed.
     */
    private fun forceRootRecomputation() {
        root = null
    }


    /**
     * Assuming [start] and [endInclusive] are within the same block, returns a pair of a block which contains
     * exactly the commands between [start] and [endInclusive] and includes the successors of [endInclusive].
     *
     * If [start] and [endInclusive] are the entirety of some block b, then that block id is returned unchanged, along
     * with its successors. If [start] is the beginning of b, then all commands after [endInclusive] are split into
     * a separate block, b's successors are updated to be the singleton set of this new block, and b is returned
     * along with this singleton set
     *
     * If [endInclusive] is is the end of the block, then the block is again split, but the id returned is the id of
     * the newly created block, along with b's original successors. Otherwise the block is split twice.
     */
    fun splitBlockRange(start: CmdPointer, endInclusive: CmdPointer): Pair<NBId, TreapSet<NBId>> {
        val startReloc = o2pp(start) ?: throw IllegalArgumentException("$start is not a pointer in the original graph")
        val endReloc = o2pp(endInclusive) ?: throw IllegalArgumentException("$endInclusive is not a pointer in the original graph")
        val startBlock = startReloc.block
        val endBlock = endReloc.block

        if (startBlock != endBlock) {
            throw IllegalArgumentException("$start ($startBlock) and $endInclusive ($endBlock) are in different blocks, cannot split range")
        }
        val containingBlock =
            blocks[startBlock] ?: throw IllegalStateException("Invariant broken: $startBlock has no code")
        val endPos = containingBlock.indexOfLast {
            it == endReloc
        }.takeIf { it >= 0 }
            ?: throw IllegalStateException("Invariant broken, $endInclusive ($endReloc) has $startBlock as parent block, but doesn't appear within it")
        val startPos = containingBlock.indexOfLast {
            it == startReloc
        }.takeIf { it >= 0 }
            ?: throw IllegalStateException("Invariant broken, $start ($startReloc) has $startBlock as parent block, but doesn't appear within it")

        if (startPos > endPos) {
            throw IllegalArgumentException("$start appears after ($startPos vs $endPos) position of $endInclusive in block $startBlock")
        }

        if (endPos == containingBlock.lastIndex && startPos == 0) {
            return startBlock to blockgraph[startBlock].orEmpty()
        }

        val predecessorBlock = if (startPos == 0) {
            null
        } else {
            containingBlock.subList(0, startPos)
        }

        val tailBlock = if (endPos == containingBlock.lastIndex) {
            null
        } else {
            containingBlock.subList(endPos + 1, containingBlock.size)
        }

        val splitBlock = containingBlock.subList(startPos, endPos + 1)

        val startBlockSucc = blockgraph[startBlock].orEmpty()

        if (predecessorBlock == null) {
            check(tailBlock != null)
            setBlockContents(startBlock, splitBlock)
            val tailId = deriveNewBlock(startBlock)
            blockgraph[tailId] = startBlockSucc
            blockgraph[startBlock] = treapSetOf(tailId)
            setBlockContents(tailId, tailBlock)
            remapBlock(startBlock, listOf(startBlock, tailId))
            return startBlock to treapSetOf(tailId)
        }
        val splitBlockId = deriveNewBlock(startBlock)
        setBlockContents(splitBlockId, splitBlock)
        setBlockContents(startBlock, predecessorBlock)
        blockgraph[startBlock] = treapSetOf(splitBlockId)
        if (tailBlock == null) {
            blockgraph[splitBlockId] = startBlockSucc
            remapBlock(startBlock, listOf(startBlock, splitBlockId))
            return splitBlockId to startBlockSucc
        }

        val tailBlockId = deriveNewBlock(startBlock)

        blockgraph[splitBlockId] = treapSetOf(tailBlockId)
        blockgraph[tailBlockId] = startBlockSucc
        setBlockContents(tailBlockId, tailBlock)
        remapBlock(startBlock, listOf(startBlock, splitBlockId, tailBlockId))
        return splitBlockId to treapSetOf(tailBlockId)
    }

    private fun setBlockContents(blockId: NBId, blockCommands: List<PatchPoint<T>>) {
        blocks[blockId] = blockCommands
        blockCommands.forEach { it.block = blockId }
    }

    /**
     * The old block [oldBlock] has been decomposed into [newTail]
     */
    open protected fun remapBlock(oldBlock: NBId, newTail: List<NBId>) { }

    /**
     * All paths to [oldBlock] have been rerouted to [newBlock]
     */
    open protected fun rerouteBlock(oldBlock: NBId, newBlock: NBId) { }

    /**
     * Split the block containing [p], returning a new block that contains all the code that comes after
     * [p] (i.e., the new block does NOT contain [p]). As the graphs do not support empty blocks, [p] cannot be the
     * last command in a block, UNLESS the block containing [p] has a single successor, in which case that successor
     * is returned.
     *
     * You should therefore *not* assume that all control flow to the block returned from this function originates from [p].
     */
    fun splitBlockAfter(p: CmdPointer): NBId {
        val (currBlock, cmds, blkIdx) = splitCommon(p)

        val currSuccs = blockgraph[currBlock].orEmpty()

        if (blkIdx == cmds.lastIndex) {
            if (currSuccs.size == 1) {
                return currSuccs.first()
            } else if(currSuccs.isEmpty()) {
                val newId = deriveNewBlock(currBlock)
                setBlockContents(newId, listOf())
                blockgraph[currBlock] = treapSetOf(newId)
                return newId
            }
            throw IllegalArgumentException("Cannot split last block $currBlock containing $p as it has at least two successors")
        }

        // otherwise, we split
        val rem = cmds.subList(0, blkIdx + 1)
        val newBlock = cmds.subList(blkIdx + 1, cmds.size)
        check(rem.isNotEmpty() && newBlock.isNotEmpty())

        val newId = deriveNewBlock(currBlock)

        setBlockContents(currBlock, rem)
        require(newId !in blocks) {
            "our newly created block clashes with an existing one ($newId) -- this might mean that the existing one " +
                "was created by using `freshCopy` without using the correct `Allocator.Id.BLOCK_FRESH_COPY`." }
        setBlockContents(newId, newBlock)

        blockgraph[newId] = blockgraph[currBlock]!!
        blockgraph[currBlock] = treapSetOf(newId)
        remapBlock(currBlock, listOf(currBlock, newId))

        forceRootRecomputation()

        return newId
    }

    private fun splitCommon(p: CmdPointer) : Triple<NBId, List<PatchPoint<T>>, Int> {
        val pp = o2pp(p) ?: throw IllegalArgumentException("$p is not a valid pointer in the graph")
        if (pp.replacement != null) {
            throw IllegalArgumentException("$p is no longer in the graph (it has been replaced with ${pp.replacement}")
        }
        val currBlock = pp.block
        val cmds = blocks[currBlock]
            ?: throw IllegalStateException("Block structure broken")

        val blkIdx = cmds.indexOf(pp).takeIf { it != -1 }
            ?: throw IllegalStateException("invariant broken: $p not actually in pointed to block")
        return Triple(currBlock, cmds, blkIdx)
    }

    /**
     * Returns a (potentially new) block ID that contains exactly the commands denoted by [p] and all of its successors.
     */
    fun splitBlockBefore(p: CmdPointer) : NBId {
        val (currBlock, cmds, blkIdx) = splitCommon(p)
        if(blkIdx == 0) {
            return currBlock
        }

        val successors = blockgraph[currBlock].orEmpty()

        // otherwise we split
        val newId = deriveNewBlock(currBlock)
        val rem = cmds.subList(0, blkIdx)
        val newBlock = cmds.subList(blkIdx, cmds.size)
        setBlockContents(currBlock, rem)
        setBlockContents(newId, blockCommands = newBlock)
        blockgraph[newId] = successors
        blockgraph[currBlock] = treapSetOf(newId)
        remapBlock(currBlock, listOf(currBlock, newId))

        forceRootRecomputation()

        return newId
    }

    /**
     * Add the list of commands [new] before the command at [p].
     */
    fun addBefore(p: CmdPointer, new: List<T>) {
        val cmd = originalCode[p.block]?.get(p.pos)?.let { mutableListOf(it) }
            ?: throw IllegalArgumentException("$p is not in this program")
        cmd.addAll(0, new)
        replaceCommand(p, cmd)
    }

    /**
     * Add the list of commands [new] after the command at [p].
     */
    fun addAfter(p: CmdPointer, new: List<T>) {
        val cmd = originalCode[p.block]?.get(p.pos)?.let { mutableListOf(it) }
            ?: throw IllegalArgumentException("$p is not in this program")
        cmd.addAll(new)
        replaceCommand(p, cmd)
    }

    /**
     * Replaces [p] with the (potentially empty) list of commands in [new].
     *
     * If [p] is not the last command in the program, [new] may not contain any jump commands,
     * and [succ] must be null.
     *
     * Otherwise, [new] is first consulted to see if the last command is a jumping command. If so, then successors
     * of the block containing [p] is patched with those successors. If [succ] is also specified, it *must* be consistent
     * with the inferred successors. If there is no jumping command within [new], then [succ] is used as the blocks
     * new successors. Finally, if [succ] is null then the existing successors are used *as is*.
     *
     * Use caution!
     *
     */
    fun replaceCommand(p: CmdPointer, new: List<T>, succ: TreapSet<NBId>? = null) {
        val pp = o2pp(p)
            ?: throw IllegalArgumentException("$p is not in this program")
        val currentBlock = pp.block
        val blockCode = blocks[currentBlock]
            ?: throw IllegalStateException("$currentBlock is in a bad state")
        if (pp.replacement != null) {
            // NB: I'd rather have this be a `require`, but then tons of our tests break.
            //   jira issue on the topic: https://certora.atlassian.net/browse/CER-1474
            logger.warn {
                "there already is a replacement registered in this PatchingProgram " +
                        "for pointer \"$p\", only one replacement is allowed per pointer"
            }
        }
        pp.replacement = new
        if (blockCode.isEmpty()) {
            throw IllegalStateException("$p ($pp) should be in $currentBlock but it is empty")
        }
        val position = blockCode.lastIndexOf(pp).takeIf { it >= 0 }
            ?: throw IllegalStateException("$p ($pp) thinks it is in $currentBlock but is not in the code area")
        val impliedSucc = new.lastOrNull()?.let {
            getSuccessors(it)
        }
        if (position != blockCode.lastIndex) {
            val cond = impliedSucc == succ && succ == null
            if (!cond) {
                throw IllegalArgumentException(
                    "Error while replacing $p: " +
                            "Cannot specify successor information within a block (last index is ${blockCode.lastIndex}, " +
                            "current position $position), but got implied successors $impliedSucc and specified " +
                            "successors $succ. Trying to replace pointer with $new"
                )
            }
            // Since the replacement is not to the last command in a block,
            // then you aren't updating the successors,
            // so the function is done at that point
            return
        }
        if (impliedSucc != null && succ != null) {
            if (impliedSucc != succ) {
                throw IllegalArgumentException("$succ is non-null but does not match implicit targets ($impliedSucc) in $new")
            }
        }
        val successors = impliedSucc ?: (succ ?: blockgraph[currentBlock]!!)

        //If the successor block is open, don't do any consistency checks on it now.
        check(successors.all {
            it in blocks || it in openBlocks
        }) { "Blocks do not contain new successors of $currentBlock: ${successors.filter { it !in blocks || it !in openBlocks }}" }

        blockgraph[currentBlock] = successors
    }

    fun addRequiredDecls(reqDecl: CommandWithRequiredDecls<T>) {
        addVarDecls(reqDecl.varDecls)
    }

    fun addRequiredDecls(reqDecl: TACExprWithRequiredCmdsAndDecls<TACCmd.Spec>) {
        addVarDecls(reqDecl.declsToAdd)
    }

    fun addVarDecls(varDecls: Collection<TACSymbol.Var>) {
        newVarDecls.addAll(varDecls)
    }

    fun addVarDecl(varDecl: TACSymbol.Var?) {
        varDecl?.let{newVarDecls.add(it)}
    }

    fun tmpVar(name : String, tag : Tag, meta : MetaMap = MetaMap()) =
        tempVar(name, tag, meta).also(::addVarDecl)

    fun replaceVarDecl(old: TACSymbol.Var, new: TACSymbol.Var) {
        check(replacedVarDecls[old].let { it == null || it == new })
        { "Cannot replace one var decl with two different var decls." }
        replacedVarDecls[old] = new
    }

    fun addUf(uf: FunctionInScope.UF) {
        check(uf !in replacedScalarUfs.keys) { "collision between addUf($uf) and replacedUfs" }
        newUfs.add(uf)
    }

    /**
     * Replace a scalar UF (aka. a nullary ghost, aka. a scalar ghost variable).
     * The [PatchingTACProgram] will also take care of replacing the occurrences of the UF / variable in the ghost
     * axioms during [toCode]. Occurrences in the program body have to be replaced by the user of [PatchingTACProgram]
     * however.
     * Note that there is no fundamental reason to only replace scalar UFs, but that's the only use case now, and the
     * interface for non-scalar UF's will also differ from this one, since they don't have associated
     * [TACExpr.Sym.Var]s.
     */
    fun replaceScalarUf(
        old: FunctionInScope.UF,
        new: FunctionInScope.UF,
        oldAsSym: TACExpr.Sym.Var,
        newAsSym: TACExpr.Sym.Var,
    ) {
        val replaced = replacedScalarUfs.put(old, new)
        check(replaced == null || replaced == new) {
            "overwrote replacement -- shouldn't happen; old: $old new: $new replaces: $replaced"
        }
        replacedUfsAsTACExprs[oldAsSym] = newAsSym
    }

    /**
     * When we have replaced scalar Ufs via [replaceScalarUf], we need to update the axioms accordingly, since they
     * might contain them. This method does that automatically (it's called in [toCode]).
     * Note that, while this is automatic, updating the occurrences in regular program source is the responsibility of
     * the user.
     * This uses the fields [replacedScalarUfs] and [replacedUfsAsTACExprs] to do the replacements in the mapping keys
     * and the expression bodies respectively.
     *
     */
    protected fun updateAxiomsWrtReplacedUfs(oldAxioms: UfAxioms): UfAxioms {
        return oldAxioms.mapTo { fisToAxioms ->
            UfAxioms(
                fisToAxioms.entries.associate { (fis, axioms) ->
                    val newFis = replacedScalarUfs[fis] ?: fis
                    val newAxioms = axioms.map { axiom ->
                        TACAxiom(
                            TACExprUtils.SubstitutorVar(replacedUfsAsTACExprs)
                                .transform(axiom.exp)
                        )
                    }
                    newFis to newAxioms
                }
            )
        }
    }

    fun dropUf(uf: FunctionInScope.UF) {
        check(uf !in replacedScalarUfs.values) { "collision between dropUf($uf) and replacedUfs" }
        ufsToDrop.add(uf)
    }

    fun addUninterpretedSort(uninterpretedSort: Tag.UserDefined.UninterpretedSort) {
        newUninterpretedSorts.add(uninterpretedSort)
    }

    private fun patchGraph(p: NBId, succ: TreapSet<NBId>) {
        assert(blockgraph.keys.containsAll(succ))
        blockgraph[p] = succ
    }

    /**
     * workflow:
     * 1. [createOpenBlockFrom] off of a parent
     * 2. [replaceCommand] in the parent to jump to the new NBId
     * 3. [populateBlock] to populate the newBlock, calling [createOpenBlockFrom] as needed
     *
     * Use this code with  discretion, calling [toCode] before you're done will result in malformed graphs
     */
    fun createOpenBlockFrom(parent: NBId): NBId {
        forceRootRecomputation()
        return deriveNewBlock(parent).also {
            openBlocks.add(it)
        }
    }

    /**
     * Manages the addition of new code to an [openBlock].
     * When complete, the block will be "sealed" and successor consistency checks are run.
     *
     */
    fun populateBlock(openBlock: NBId, code: List<T>, succ: TreapSet<NBId>? = null) {
        if(openBlock !in openBlocks) {
            throw IllegalArgumentException("$openBlock is not a pre-allocated block")
        }

        val s = getSuccessors(code.last())
        if(s == null && succ == null) {
            throw IllegalArgumentException("No successors (inferred or explicit) for open block $openBlock")
        }
        if(succ != null && s != null) {
            if(succ.toSet() != s) {
                throw IllegalArgumentException("Inconsistent implicit ($s) and explicit ($succ) successors for open block $openBlock")
            }
        }
        val successors = succ ?: s!!

        check(successors.all {
            it in blocks || it in openBlocks
        }) { "Blocks nor OpenBlocks do not contain successors of $openBlock: ${successors.filter { it !in blocks }}" }

        //If the code is valid, add the block to the graph
        addCodeToGraph(openBlock, code)
        blockgraph[openBlock] = successors
        //At this point the block should be well-formed so remove it from outstandings
        openBlocks.remove(openBlock)
    }

     /**
     * Add a block with id [base] whose contents are [d]. The blocks successors
     * are inferred from the last command of [d].
     */
    fun addBlock(base: NBId, d: List<T>): NBId {
        val succs = d.last().let { getSuccessors(it) }
        return addBlock(base, d, succs.orEmpty())
    }

    private fun addBlock(base: NBId, d: List<T>, succ: TreapSet<NBId>): NBId {
        if (succ.any {
                it !in blocks
            }) {
            throw java.lang.IllegalArgumentException("New block derived from $base points out of graph with successors $succ")
        }
        val newId = deriveNewBlock(base)
        patchGraph(newId, succ)
        addCodeToGraph(newId, d)
        forceRootRecomputation()
        return newId
    }

    interface CommandRemapper<T> {
        fun isJumpCommand(c: T): Boolean
        fun remapSuccessors(c: T, remapper: (NBId) -> NBId): T
    }

    companion object {
        val SIMPLE = object : CommandRemapper<TACCmd.Simple> {
            override fun isJumpCommand(c: TACCmd.Simple) =
                when (c) {
                    is TACCmd.Simple.JumpiCmd,
                    is TACCmd.Simple.JumpCmd -> true
                    is TACCmd.Simple.SummaryCmd -> c.summ is ConditionalBlockSummary
                    else -> false
                }

            override fun remapSuccessors(c: TACCmd.Simple, remapper: (NBId) -> NBId): TACCmd.Simple {
                check(isJumpCommand(c))
                return when (c) {
                    is TACCmd.Simple.JumpiCmd -> {
                        val dst = remapper(c.dst)
                        val elseDst = remapper(c.elseDst)
                        if (dst == elseDst) {
                            TACCmd.Simple.JumpCmd(dst, c.meta)
                        } else {
                            c.copy(
                                dst = remapper(c.dst),
                                elseDst = remapper(c.elseDst)
                            )
                        }
                    }
                    is TACCmd.Simple.JumpCmd -> {
                        c.withDst(remapper(c.dst))
                    }
                    is TACCmd.Simple.SummaryCmd -> {
                        if (c.summ is ConditionalBlockSummary) {
                            c.copy(summ = c.summ.remapBlocks(remapper))
                        } else {
                            c
                        }
                    }
                    else -> c
                }
            }
        }
    }

    /**
     * As with [replace] but [f] takes only the command to be replaced.
     */
    fun replace(p: CmdPointer, f: (T) -> List<T>) {
        val cmd = getCommand(p)
        replaceCommand(p, f(cmd))
    }

    /**
     * Replace the command at [p] with the result of the function [f] ([p], `cmd`)
     * where `cmd` is the command at [p]. Internally this calls [replaceCommand] but does
     * not provide a mechanism for specifying successors of the new command. It is expected
     * clients will generally use this to replace jumping commands with jumping commands
     * and non-jumping commands with non-jumping commands.
     */
    fun replace(p: CmdPointer, f: (CmdPointer, T) -> List<T>) {
        val cmd = getCommand(p)
        replaceCommand(p, f(p, cmd))
    }

    private fun getCommand(p: CmdPointer): T {
        val pp = o2pp(p) ?: throw IllegalArgumentException("Failed to find $p in graph")
        return pp.origCmd
    }

    /**
     * Update the command at pointer [p] with the single command given by [f](cmd(p))
     */
    fun update(p: CmdPointer, f: (T) -> T) {
        replaceCommand(p, listOf(f(getCommand(p))))
    }

    fun update(p: CmdPointer, newCmd : T) {
        replaceCommand(p, listOf(newCmd))
    }

    fun delete(p: CmdPointer) {
        replaceCommand(p, emptyList())
    }

    private fun rewriteSuccessor(b: NBId, remap: CommandRemapper<T>, remapper: (NBId) -> NBId) {
        val blockCmds = blocks[b] ?: throw IllegalArgumentException("Block $b is not in the graph")
        val final = blockCmds.last()
        blockgraph[b] = blockgraph[b].orEmpty().updateElements {
            val new = remapper(it)
            if (new !in blockgraph && new !in openBlocks) {
                throw IllegalArgumentException("Trying to replace $it with $new, but $new not in graph")
            }
            new
        }
        final.replacement = final.replacement?.let { replacementCmd ->
            val finalCmd = replacementCmd.last()
            if (!remap.isJumpCommand(finalCmd)) {
                return
            }
            replacementCmd.dropLast(1) + remap.remapSuccessors(finalCmd, remapper)
        } ?: run {
            if (!remap.isJumpCommand(final.origCmd)) {
                return
            }
            listOf(remap.remapSuccessors(final.origCmd, remapper))
        }
    }

    internal fun addCodeToGraph(x: NBId, code: List<T>) {
        blocks[x] = code.map {
            PatchPoint(it, x)
        }
    }

    /**
     * Reroute all jumps from the predecessors of [x] to a new block whose contents are [g],
     * and whose successors are [succ] (assuming [g] does not contain an implicit jump).
     * Optionally, provide a [predFilter] in order to reroute only some of [x]'s predecessors.
     *
     * The remapping of predecessor jumps is accomplished with [r]
     */
    fun reroutePredecessorsTo(x: NBId, g: List<T>, succ: TreapSet<NBId>?, r: CommandRemapper<T>, predFilter: ((NBId) -> Boolean) = { true }) {
        val pred = getPredecessors(x).filter(predFilter)
        val newId = deriveNewBlock(x)
        addCodeToGraph(newId, g)
        val succs = getSuccessors(g.last()) ?: succ.orEmpty()
        blockgraph[newId] = succs
        for (p in pred) {
            rewriteSuccessor(p, r) {
                if (it == x) {
                    newId
                } else {
                    it
                }
            }
        }
    }

    internal fun getPredecessors(x: NBId): TreapSet<NBId> {
        return blockgraph.asReversed().get(x).orEmpty()
    }

    internal fun getSuccessors(x: NBId): TreapSet<NBId> {
        return blockgraph[x] ?: treapSetOf()
    }

    internal fun getRoots() = findRoots(blockgraph)

    /**
     * Reroute all predecessors of [x] to instead jump to [newTarget] using the remapper [r].
     * Optionally, provide a [predFilter] in order to reroute only some of [x]'s predecessors.
     *
     * [x] is *not* necessarily removed from the graph.
     */
    fun reroutePredecessorsTo(x: NBId, newTarget: NBId, r: CommandRemapper<T>, predFilter: (NBId) -> Boolean = { true }) {
        getPredecessors(x).filter(predFilter).forEach {
            rewriteSuccessor(it, r) {
                if (it == x) {
                    newTarget
                } else {
                    it
                }
            }
        }
    }

    protected fun deriveNewBlock(currBlock: NBId) = currBlock.copy(freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY))

    private fun getSuccessors(it: T): TreapSet<NBId>? =
        when (it) {
            is TACCmd.Simple.JumpCmd -> treapSetOf(it.dst)
            is TACCmd.Simple.JumpiCmd -> treapSetOf(it.dst, it.elseDst)
            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.RevertCmd -> null
            is TACCmd.Simple.SummaryCmd -> if(it.summ is ConditionalBlockSummary) { it.summ.successors } else { null }
            else -> null
        }

    fun forEachOriginal(f: (CmdPointer, T) -> Unit) =
        originalCode.forEach { (id, cmds) ->
            cmds.forEachIndexed { idx, cmd ->
                f(CmdPointer(id, idx), cmd)
            }
        }

    open fun toCode(empty: T? = null): Pair<BlockNodes<T>, BlockGraph> {
        if(deferredDropBlocks.isNotEmpty()) {
            removeSubgraph(deferredDropBlocks)
        }
        if (openBlocks.isNotEmpty()) {
            throw IllegalStateException("cannot finalize program patch if there are open blocks: $openBlocks")
        }

        check(blocks.keys == blockgraph.keys) { "Block structure mismatch" }

        val newCode: Map<NBId, List<T>> = blocks.mapValues { (_, pps) ->
            ArrayList<T>(pps.size).also { l ->
                for (pp in pps) {
                    val replacement = pp.replacement
                    if (replacement != null) {
                        l.addAll(replacement)
                    } else {
                        l.add(pp.origCmd)
                    }
                }
            }.takeIf { it.isNotEmpty() }
            ?: listOf(empty ?: error("Empty block but no replacement element provided"))
        }

        return newCode to blockgraph.forward
    }

    /**
     * Assuming block [b] has a single successor, removes it and re-routes its incoming edges to the single successor.
     * [b]'s commands are removed - this method does *not* merge [b]'s commands with the successor
     */
    fun removeBlockWithSingleSuccessor(b: NBId, remap: CommandRemapper<T>) {
        // get original successor to route to
        val succ = (blockgraph[b] ?: throw IllegalArgumentException("$b not in graph")).singleOrNull()
                ?: throw IllegalArgumentException("$b should have a single successor, but got ${blockgraph[b]}")
        // get preds to modify their edges
        // not efficient, but can have multiple dummy jump blocks in a row, so must work on latest graph
        val preds = getPredecessors(b)


        // update preds
        preds.forEach { pred ->
            val remapSucces = { it: NBId ->
                if (it == b) {
                    succ
                } else {
                    it
                }
            }
            rewriteSuccessor(pred, remap, remapSucces)
        }

        removeBlock(b)

    }

    protected fun buildNewVars(symbolTable: TACSymbolTable, ufs: Set<FunctionInScope.UF>): TreapSet<TACSymbol.Var> {
        var newVars = symbolTable.vars
        newVars -= replacedVarDecls.keys
        newVars += replacedVarDecls.values
        newVars += newVarDecls

        // add tags for the uninterpreted functions to the type scope; but only if they're not explicitly replaced -- then they already have a tag
        val newTagsSmtReps = newVars.mapToSet { it.smtRep }
        ufs.forEach { uf ->
            if (uf.name !in newTagsSmtReps) {
                newVars += TACSymbol.Var(uf.name, uf.asTag)
            }
        }

        return newVars
    }

    fun <R : TACProgram<T>> toCode(base: R, empty: T? = null): R {
        val (newCode, newBlockgraph) = toCode(empty)
        /*check(newCode.values.all { it.all { c -> c is TACCmd.Simple } })
        { "Cannot convert patching TAC Program with non simple commands to a CoreTACProgram" }*/
        newBlockgraph.filter {
            it.value.any { outer ->
                !(outer in newBlockgraph && outer in newCode)
            }
        }.let { succsNotInCodeOrGraph ->
            check(succsNotInCodeOrGraph.isEmpty()) { "blockgraph and new code do not match: ${succsNotInCodeOrGraph}" }
        }
        val newProcedures = procedures.toList()

        val newUfs = base.symbolTable.uninterpretedFunctions().mapToSet { replacedScalarUfs[it] ?: it } + newUfs - ufsToDrop

        val newVars = buildNewVars(base.symbolTable, newUfs)

        val newTACSymbolTable = TACSymbolTable(
            userDefinedTypes = base.symbolTable.userDefinedTypes + newUninterpretedSorts,
            vars = newVars,
            uninterpretedFunctions = newUfs,
            globalScope = base.symbolTable.globalScope
        )
        return when (base) {
            is CoreTACProgram -> {
                val newUfAxioms = updateAxiomsWrtReplacedUfs((replacedUfAxioms ?: base.ufAxioms))
                base.copy(
                    code = newCode.uncheckedAs<BlockNodes<TACCmd.Simple>>(),
                    blockgraph = newBlockgraph,
                    procedures = base.procedures + newProcedures,
                    symbolTable = newTACSymbolTable,
                    instrumentationTAC = base.instrumentationTAC.copy(ufAxioms = newUfAxioms),
                    entryBlock = root,
                ).uncheckedAs<R>()
            }
            is EVMTACProgram -> {
                check(newProcedures.isEmpty()) { "Procedures not supported in EVMTACProgram" }
                base.copy(
                    code = newCode,
                    blockgraph = newBlockgraph,
                    symbolTable = newTACSymbolTable,
                    entryBlock = root,
                ).uncheckedAs<R>()
            }
            is CVLTACProgram -> {
                base.copy(
                    code = newCode.uncheckedAs<BlockNodes<TACCmd.Spec>>(),
                    blockgraph = newBlockgraph,
                    symbolTable = newTACSymbolTable
                ).uncheckedAs<R>()
            }
            else -> base.copyWith(
                code = newCode,
                blockgraph = newBlockgraph,
                symbolTable = newTACSymbolTable,
                procedures = (base as? IProcedural)?.procedures.orEmpty() + newProcedures,
            ).uncheckedAs<R>()
        }
    }

    /**
     * Remove a block [b] provided no blocks reference it. This process is not transitive.
     */
    fun removeBlock(b: NBId) {
        if (!blockgraph.asReversed().get(b).isNullOrEmpty()) {
            throw java.lang.IllegalArgumentException("Found predecessor of $b, cannot remove")
        }
        removeBlockInternal(b)
    }

    /**
     * Removes all blocks in [blocks] provided no blocks outside of [blocks] reference it.
     * This process is not transitive
     */
    fun removeBlocks(blocks: Collection<NBId>) {
        val predecessorMap = blockgraph.asReversed()
        if(!blocks.all {
                val preds = predecessorMap.get(it)
                preds == null || preds.all {
                    it in blocks
                }
            }) {
            throw IllegalArgumentException("$blocks cannot be safely removed from the graph")
        }
        for(b in blocks) {
            removeBlockInternal(b)
        }
    }

    private fun removeBlockInternal(b: NBId) {
        blockgraph.remove(b)
        blocks[b]?.forEach {
            it.removed = true
        }
        blocks.remove(b)
    }

    fun isBlockStillInGraph(b: NBId): Boolean = b in blockgraph

    private val deferredDropBlocks = mutableSetOf<NBId>()

    /**
     * Schedule blocks for removal when the patching program is materialized. This is helpful if it is complicated/impossible
     * to prevent mutations in blocks that have been removed; this allows (pointless) mutations in a block that is ultimately
     * scheduled for removal.
     *
     * NB that the behavior of this function w.r.t. block *splitting* might be confusing; if you split a block and then
     * remove the original ID via this method, the successor split will still remain in the graph.
     */
    fun deferRemoveSubgraph(b: Set<NBId>) {
        deferredDropBlocks.addAll(b)
    }

    fun removeSubgraph(b: Set<NBId>) {
        val toRemove = ArrayHashSet<NBId>(blockgraph.keys)
        val work = ArrayDeque<Set<NBId>>()
        work.addLast(getRoots())
        while (work.isNotEmpty()) {
            val next = work.removeLast()
            next.forEach {
                if (it !in b) {
                    if (toRemove.remove(it)) {
                        val succ = blockgraph[it]
                        if (succ != null && succ.isNotEmpty()) {
                            work.add(succ)
                        }
                    }
                }
            }
        }
        toRemove.forEach {
            removeBlockInternal(it)
        }
    }

    /**
     * Will drop all the subgraphs dominated by [droppedTargets] and re-routing their predecessors to [chosenTarget].
     */
    fun consolidateEdges(chosenTarget: NBId, droppedTargets: Collection<NBId>, remap: CommandRemapper<T>) : List<Pair<NBId, NBId>> {
        val remapSuccs =  { it: NBId ->
            if (it in droppedTargets) {
                chosenTarget
            } else {
                it
            }
        }
        val addedEdges = mutableListOf<Pair<NBId, NBId>>()
        for(dropped in droppedTargets) {
            rerouteBlock(dropped, chosenTarget)
        }
        for ((k, l) in blockgraph) {
            if (l.containsAny(droppedTargets)) {
                rewriteSuccessor(k, remap, remapSuccs)
                addedEdges.add(k to chosenTarget)
            }
        }
        val childrenOfDroppedTargets = mutableSetOf<NBId>()
        droppedTargets.flatMapTo(childrenOfDroppedTargets) { blockgraph[it] ?: throw IllegalStateException("Null successors for $it while consolidating edges of $name: consolidating to $chosenTarget and dropping $droppedTargets") }
        removeSubgraph(droppedTargets.toSet())
        removeSubgraph(childrenOfDroppedTargets
            // if there are nodes whose parents are only in droppedTargets, they can be removed (if not removed already)
            .filter { it in blocks }
            // check that *all* children's parents are in droppedTargets - otherwise do not remove those
            .filter { child ->
                droppedTargets.containsAll(
                    blockgraph.filter { parentAndChildren -> child in parentAndChildren.value }
                        .keys
                )
            }
            .toSet()
        )
        return addedEdges
    }

    /**
     * Insert the commands [after] after the position [pos]. [pos] may not designate a jumping command, as
     * all commands inserted would be dead.
     */
    fun insertAfter(pos: CmdPointer, after: List<T>) {
        val cmd = getCommand(pos)
        if (cmd is TACCmd.Simple.JumpCmd || cmd is TACCmd.Simple.JumpiCmd || (cmd is TACCmd.Simple.SummaryCmd && cmd.summ is ConditionalBlockSummary)) {
            throw IllegalArgumentException("Cannot add after jump command $cmd at position $pos")
        }
        this.replaceCommand(pos, listOf(cmd) + after)
    }



    /**
     * @param start start removing from here, inclusive
     * @param end end removing here, inclusive
     */
    fun removeCommandSequence(block: NBId, start: Int, end: Int) {
        require(start < end) { "start of sequence must be lower index than end of it" }
        for (pos in (start ..  end).reversed()) {
            replaceCommand(CmdPointer(block, pos), emptyList())
        }
    }


    /**
     * Replace the specified command with a new sub-CFG.
     *
     * @param firstCommandToReplace first command that should be removed, inserting a jump to the subgraph entry
     *   (i.e. [newGraphEntry])
     * @param lastCommandToReplace last command that should be removed, this methods inserts a jump from the sub graph
     *   exit (i.e. [newGraphExit]) to the command right after this one
     * @param newBlockGraph block graph edges to be added -- the [NBId]s used here must all be fresh
     * @param newCodeBlocks code for the fresh blocks
     * @param blocksToRemove if [firstCommandToReplace] and [lastCommandToReplace] are not in the same block, there
     *   must be a subgraph that connects the two ([firstCommandToReplace] must dominate [lastCommandToReplace]); this
     *   field connects the nodes that this method must remove in order to remove that subgraph
     * @param newGraphEntry the entry node of the new subgraph (must occur in [newBlockGraph])
     * @param newGraphExit the exit node of the new subgraph (must occur in [newBlockGraph])
     * @param subGraphMapping mapping from [CmdPointer]s in the old program to [CmdPointer]s in the [newCodeBlocks],
     *   used  to update [mapping]
     * @param mapping continuously updated mapping from locations in the original to locations in the transformed one;
     *   this mapping is applied to [firstCommandToReplace] and to [lastCommandToReplace] in order for repeated
     *   applications of [replaceSubgraph] on the same program to "connect"; it is also updated here for later
     *   [replaceSubgraph] actions, as well, once the whole transformation is done to provide a "translation" between
     *   the two programs if needed.
     */
    fun replaceSubgraph(
        firstCommandToReplace: CmdPointer,
        lastCommandToReplace: CmdPointer,
        newBlockGraph: LinkedArrayHashMapReader<NBId, TreapSet<NBId>>,
        newCodeBlocks: Map<NBId, List<T>>,
        blocksToRemove: Set<NBId>,
        newGraphEntry: NBId,
        newGraphExit: NBId,
        subGraphMapping: CmdPtrMapping,
        mapping: CmdPtrMapping, // this is updated (and read)
    ) {
        fun requireChecks() {
            require(firstCommandToReplace.block in originalCode.keys &&
                lastCommandToReplace.block in originalCode.keys) {
                "block must occur in the original program"
            }
            val newNodesInGraph = newBlockGraph.keys + newBlockGraph.values.flatten()
            require(!newNodesInGraph.containsAny(this.blockgraph.keys)) {
                "the block ids for the new subgraph must not intersect with the existing block ids"
            }
            require(newCodeBlocks.keys.containsAll(newNodesInGraph)) { "graph must not mention edges we have no code for" }
            require(newGraphEntry in newCodeBlocks.keys) { "entry must be in the new graph" }
            require(newGraphExit in newCodeBlocks.keys) { "exit must be in the new graph" }
        }
        requireChecks()

        val startPoint = mapping[firstCommandToReplace]
        var endPoint = mapping[lastCommandToReplace]

        // step 1: remove commands that exist in new graph already, split block if needed
        if (startPoint.block == endPoint.block) {
            /*
             * if start and end block are the same, split the block in two
             * - make a new block
             * - fill it with the commands after `end`
             * - delete the redundant commands from the new block (since we cut at the start position)
             */

            if (startPoint.pos == 0) {
                throw UnsupportedOperationException("internal function call at the very beginning of a block; need " +
                    "to implement (in AddInternalFunctions)")
            }

            // remove the commands from the new block that we've already mirrored in the new CFG
            removeCommandSequence(startPoint.block, startPoint.pos, endPoint.pos)

            // split up the block in two
            val newBlock = splitBlockAfter(startPoint - 1)

            // update the mapping according to the command-removing and the block-splitting
            mapping.splitBlock(
                firstPosInNewBlockOrig = firstCommandToReplace,
                origBlockLen = this.originalCode[startPoint.block]!!.size,
                firstPosInNewBlock = startPoint,
                newBlock = newBlock,
                nrCmdsRemoved = endPoint.pos - startPoint.pos + 1,
            )

            endPoint = CmdPointer(newBlock, 0)
        } else {
            /*
             * start and end block are not the same, so already have nodes in the new subgraph for the other part and
             * don't need to split any more; we just remove the commands that appear in the new subgraph
             */
            removeCommandSequence(startPoint.block, startPoint.pos, originalCode[startPoint.block]!!.size - 1)
            removeCommandSequence(endPoint.block, 0, endPoint.pos)

            // update [mapping]
            // - shift the commands in the block that we're exiting into by the number of removed commands
            // (- remove the commands that will come from the inserted subgraph's mapping, just to tidy up, otherwise
            //  they'd be overwritten when merging that mapping; the shift will automatically do that since it drops
            //  entries that would get a negative [CmdPointer.pos]. )
            val nrOfRemovedCommands = endPoint.pos + 1
            mapping.shiftCommands(endPoint.block, -nrOfRemovedCommands)
        }

        // delete the edges going out from startPoint and entering entry point
        blockgraph[startPoint.block] = treapSetOf()
        blockgraph.asReversed()[endPoint.block]?.toSet()?.forEach { endPointPred ->
            blockgraph[endPointPred]?.let {
                blockgraph[endPointPred] = it.remove(endPoint.block)
            }
        }

        // remove the redundant blocks (for which we have new copies, this set was created with intelligence by the caller, so we can use it as is..)
        removeBlocks(blocksToRemove)

        // step 2: add the subgraph's contents (edges and commands) to this program
        newBlockGraph.forEachEntry { (node, succs) ->
            blockgraph[node] = succs
        }
        newCodeBlocks.forEachEntry { (block, code) ->
            addCodeToGraph(block, code)
        }

        // step 3: connect [block] to the subgraph's entry and  the subgraph's exit to `newBlock`
        blockgraph[startPoint.block] = treapSetOf(newGraphEntry)
        blockgraph[newGraphExit] = treapSetOf(endPoint.block)

        // last step: add the mapping for the new blocks to the overall mapping
        mapping.merge(subGraphMapping)
    }

    override fun toCode(base: ICoreTACProgram): ICoreTACProgram {
        return this.uncheckedAs<PatchingTACProgram<TACCmd.Simple>>().toCode(base as CoreTACProgram, empty = null)
    }
}

/**
 * Replace the command [p] with the graph in [code]. The block containing [p] is split, and [p]
 * is then replaced with a jump into the root of the graph in [code]. The sinks of [code] are then
 * extended to jump to the block generated by the split command [p]. In case [p] is the last cmd of a
 * block with no successors, [code] will be appended to the block of [p]. As with [PatchingTACProgram.splitBlockAfter],
 * [p] may not be the last command of a block with multiple successors.
 */
fun <T : TACCmd> PatchingTACProgram<T>.replaceCommand(p: CmdPointer, code: TACProgram<T>) {
    val retPoint = splitBlockAfter(p)
    val root = this.addCode(code, retPoint)
    replaceCommand(p, listOf(TACCmd.Simple.JumpCmd(root)).uncheckedAs(), treapSetOf(root))
}

/**
 * Same as [replaceCommand], but has additional [preamble] to add to the program before [code].
 */
fun <T : TACCmd> PatchingTACProgram<T>.replaceCommand(
    p: CmdPointer,
    preamble: List<T>,
    code: TACProgram<T>
) {
    val retPoint = splitBlockAfter(p)
    val root = this.addCode(code, retPoint)
    replaceCommand(p, preamble + TACCmd.Simple.JumpCmd(root).uncheckedAs<T>(), treapSetOf(root))
}

fun <T : TACCmd> PatchingTACProgram<T>.replaceCommand(
    p: CmdPointer,
    preamble: CommandWithRequiredDecls<T>,
    code: TACProgram<T>
) {
    addRequiredDecls(preamble)
    replaceCommand(p, preamble.cmds, code)
}


fun <T : TACCmd> PatchingTACProgram<T>.addCode(code: TACProgram<T>, retPoint: NBId) : NBId {
    val leaves = code.getEndingBlocks()
    val sink = Allocator.getNBId().copy(calleeIdx = retPoint.calleeIdx) // UI reasons to update calleeIdx
    val root = code.getStartingBlock()
    code.code.forEach { b ->
        if (b.key in leaves) {
            if(b.value.last().isHalting()) {
                addCodeToGraph(b.key, b.value)
                blockgraph[b.key] = treapSetOf()
                return@forEach
            }
            addCodeToGraph(b.key, b.value.plus(TACCmd.Simple.JumpCmd(sink)).uncheckedAs())
            blockgraph[b.key] = treapSetOf(sink)
        } else {
            addCodeToGraph(b.key, b.value)
            blockgraph[b.key] = code.blockgraph[b.key]!!
        }
    }
    addCodeToGraph(sink, listOf(TACCmd.Simple.JumpCmd(retPoint)).uncheckedAs())
    blockgraph[sink] = treapSetOf(retPoint)

    addVarDecls(code.symbolTable.vars)
    if(code is IProcedural) {
        procedures.addAll(code.procedures)
    }
    return root
}

fun <T: TACCmd> PatchingTACProgram<T>.addBlock(base: NBId, d: CommandWithRequiredDecls<T>): NBId {
    addRequiredDecls(d)
    return addBlock(base, d.cmds)
}

fun PatchingTACProgram<TACCmd.Simple>.consolidateEdges(chosenTarget: NBId, droppedTargets: List<NBId>) =
    consolidateEdges(chosenTarget, droppedTargets, PatchingTACProgram.SIMPLE)

/**
 * See [PatchingTACProgram.reroutePredecessorsTo] for details
 */
fun PatchingTACProgram<TACCmd.Simple>.reroutePredecessorsTo(x: NBId, g: List<TACCmd.Simple>, succ: TreapSet<NBId>? = null) =
    reroutePredecessorsTo(x, g, succ, PatchingTACProgram.SIMPLE)

/**
 * See [PatchingTACProgram.reroutePredecessorsTo] for details
 */
fun PatchingTACProgram<TACCmd.Simple>.reroutePredecessorsTo(x: NBId, newTarget: NBId) =
    reroutePredecessorsTo(x, newTarget, PatchingTACProgram.SIMPLE)

fun <T: TACCmd> PatchingTACProgram<T>.freshTemp(t: Tag, suffix: String = "", callId: CallId = TACSymbol.Var.DEFAULT_INDEX): TACSymbol.Var {
    val tmp = TACKeyword.TMP(
        tag = t,
        suffix = suffix
    ).at(callId)
    this.addVarDecl(tmp)
    return tmp
}

fun <T: TACCmd> PatchingTACProgram<T>.replaceCommand(p: CmdPointer, repl: CommandWithRequiredDecls<T>) {
    this.replaceCommand(p, repl.cmds)
    this.addVarDecls(repl.varDecls)
}

fun <T: TACCmd> PatchingTACProgram<T>.addBefore(where: CmdPointer, what: CommandWithRequiredDecls<T>) {
    this.addBefore(where, what.cmds)
    this.addVarDecls(what.varDecls)
}

fun <T: TACCmd> PatchingTACProgram<T>.addAfter(where: CmdPointer, what: CommandWithRequiredDecls<T>) {
    this.addAfter(where, what.cmds)
    this.addVarDecls(what.varDecls)
}

/**
 * A results of [moveCommandsBetweenTo].
 *
 * @param insertedBlockStartId The new block id of the block that will follow directly [targetPos] after the move.
 * @param insertedBlockEndId The new block id of the last block of the move. It will be the predecessor of [targetBlockIdAfterInsert]
 * @param targetBlockIdAfterInsert The new block id of the block containing the successors statements of [targetPos].
 * [targetBlockIdAfterInsert] will be the new successor of [insertedBlockEndId]
 */
data class MoveCommandResult(val insertedBlockStartId: NBId, val insertedBlockEndId: NBId, val targetBlockIdAfterInsert: NBId)

/**
 * Moves the commands between [startPos] (exclusive) and [endPos] (inclusive) to come after [targetPos] and rewires
 * the graph such that the control from [startPos] continues after [endPos].
 *
 * Please note that this operation will not move the [startPos] pointer, and control flow after
 * [startPos] will continues at the successors of [endPos]. But [endPos] will be moved and
 * the new successors of [endPos] will be the old successors of [targetPos].
 *
 * It's required that all paths from [startPos] flow through [endPos], and in the reversed control flow graph,
 * all path from [endPos] flow through [startPos]. As otherwise, we'll have a malformed control flow graph. This check
 * is done using the dominance analysis.
 *
 * @param startPos start command (exclusive) after which the control flow will be moved.
 * @param endPos end command of the code to be moved (inclusive)
 * @param targetPos the position all commands between [startPos] and [endPos] should be moved to.
 *
 * Note: This code will _not_ update the callId of the blocks which causes TAC dumps to
 * render incorrectly. Please use method [vc.data.CoreTACProgram.moveCommandsBetweenTo] if this is required.
 */
fun PatchingTACProgram<TACCmd.Simple>.moveCommandsBetweenTo(startPos: CmdPointer, endPos: CmdPointer, targetPos: CmdPointer): MoveCommandResult {
    check(startPos != endPos) { "Expecting start and end position to differ." }
    check(startPos != targetPos) { "Expecting start and target position to differ." }
    check(endPos != targetPos) { "Expecting start and target position to differ." }
    check(endPos.block != targetPos.block)
    check(startPos.block != targetPos.block)
    check(SimpleDominanceAnalysis(blockgraph).dominates(startPos, endPos)) { "Expecting start command to always occur before end command in the graph." }
    check(SimpleDominanceAnalysis(blockgraph.asReversed()).dominates(endPos.block, startPos.block)) { "Expecting end command to always occur before start command in the reversed graph." }

    val targetBlockAfter = this.splitBlockAfter(targetPos)
    val toMoveStartBlockAfter = this.splitBlockAfter(startPos)
    val toMoveEndBlockAfter = this.splitBlockAfter(endPos)

    this.insertAfter(targetPos, listOf(TACCmd.Simple.JumpCmd(toMoveStartBlockAfter)))
    this.insertAfter(endPos, listOf(TACCmd.Simple.JumpCmd(targetBlockAfter)))
    this.insertAfter(startPos, listOf(TACCmd.Simple.JumpCmd(toMoveEndBlockAfter)))

    return MoveCommandResult(toMoveStartBlockAfter, toMoveEndBlockAfter, targetBlockAfter)
}
