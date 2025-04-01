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

package report.dumps

import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.CmdPointer
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFuncExitAnnotation
import analysis.ip.InternalFuncStartAnnotation
import analysis.worklist.volatileDagDataFlow
import aws.smithy.kotlin.runtime.util.*
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import log.*
import sbf.tac.SBF_INLINED_FUNCTION_END
import sbf.tac.SBF_INLINED_FUNCTION_START
import sbf.tac.SbfInlinedFuncEndAnnotation
import sbf.tac.SbfInlinedFuncStartAnnotation
import tac.CallId
import tac.NBId
import utils.*
import vc.data.*
import vc.data.ProcedureId.*
import verifier.CodeAnalysis


private val logger = Logger(LoggerTypes.UI) // giving logger type UI since (so far) this is only used in CodeMap context

/**
 * Transforms a given [CoreTACProgram] such that internal calls are made explicit via graph structure, i.e., the
 * [NBId.calleeIdx] field has separate ids for internal calls, in addition to the external ones.
 * While for EVM it is clear what internal and external calls are, for Solana each call is considered an internal
 * call. The algorithm assumes that the outermost call is an external call for both flows.
 *
 * Practical considerations:
 *  - The external calls (summary applications also count as such) are already made explicit in our tac programs through
 *    the [BlockIdentifier.calleeIdx] entries. After this transformation, internal calls also get their own calleeIdxs.
 *
 *  - This means we need to break up blocks, since before this transformation internal calls could happen within one
 *    block, but since `calleeIdx` is part of the [BlockIdentifier], we can only be in one context per block.
 *
 *  - In the presence of summaries, and in contrast to "plain solidity" afaiu, internal and external calls can alternate
 *    arbitrarily, i.e., there can be an external call inside an internal one, etc.
 */
object AddInternalFunctions {

    data class TACProgWithIntFuncs(val prog: CoreTACProgram, val blockMapping: CmdPtrMapping) {
        companion object {
            fun unchanged(prog: CoreTACProgram) =
                TACProgWithIntFuncs(prog, CmdPtrMapping(prog.code))
        }
    }

    /** In Solana there is only one external artificial procedure, which is this one. */
    val solanaEntryPoint = Solana("main")

    fun CoreTACProgram.alreadyHasInternalFunctions() =
        this.procedures.any { it.procedureId is ProcedureId.Internal }

    /**
     * Calls [addInternalFunctionIdxs] transformation.
     * Catches, then logs and swallows exceptions, falling back to [inputProg] in case.
     * Also measures time using [CodeAnalysis] infra.
     */
    fun addInternalFunctionIdxsDontThrow(inputProg: CoreTACProgram): TACProgWithIntFuncs =
        if (inputProg.alreadyHasInternalFunctions()) {
            logger.info {
                "prog ${inputProg.name} already has internal functions, not running the transformation again (that " +
                    "this is called twice on a program is odd, but might happen e.g. when some debug-auto dumping of " +
                    "a tac is triggered on a program with internal functions"
            }
            TACProgWithIntFuncs.unchanged(inputProg)
        } else {
            try {
                CodeAnalysis(
                    analysisName = "AddInternalFunctions",
                    analyzer = { p: CoreTACProgram -> addInternalFunctionIdxs(p) },
                    successCriteria = { true }
                ).runAnalysis(inputProg)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.warn(e) {
                    "failed to expose internal functions for CodeMap of program \"${inputProg.name}\" (continuing " +
                        "with external calls only)"
                }
                TACProgWithIntFuncs.unchanged(inputProg)
            }
        }

    /**
     * Main transformation.
     *
     * Takes a [CoreTACProgram] and returns a [CoreTACProgram] along with a [CmdPtrMapping] that relates the original
     * program and the new one's code locations. This mapping is useful for [UnsolvedSplitInfo], which is computed on
     * the input program; it is also important for the second step of our algorithm.
     *
     * The transformation proceeds in two steps:
     *  1. Iterate over the program and build sub graphs that should be replaced.
     *  2. Replace the sub graphs in the original program.
     *
     * Note: I had realized earlier that there can be external calls inside internal ones, maybe I hadn't designed the
     * algorithm as it is right now, with the first step building sub graphs that replace parts of the original program
     * in a second step (using [PatchingProgram]s); it might well be easier "treat it all as one big sub graph", and
     * just build one new program while iterating. This would e.g. save the whole [PatchingTACProgram.replaceSubgraph]
     * method that I wrote for this. Also, there would be less weight on [CmdPtrMapping]
     * This could be a refactor we might still want to do (or not).
     */
    fun addInternalFunctionIdxs(inputProg: CoreTACProgram): TACProgWithIntFuncs {
        val graph = inputProg.analysisCache.graph

        // start x end -> subgraph
        // (occasions when we jump from external to internal)
        // there are also jumps from internal to internal --> they're handled in InternalFuncSubgraph
        val replacements: MutableMap<Pair<CmdPointer, CmdPointer>, InternalFuncSubgraph> =
            mutableMapOf()

        var currSubGraphBuilder : InternalFuncSubgraph.Builder? = null


        volatileDagDataFlow(
             graph.code.keys,
             graph::pred
        ) { block, predResults: List<BlockAndCallStack> ->
            // handle incoming edges
            var callStack =
                if (predResults.isEmpty()) {
                    // root: it is assumed to be an external function call both for EVM and Solana.
                    listOf(CallStackEntry.ExtFunc(block.calleeIdx))
                } else if (currSubGraphBuilder == null) {
                    // edge from external to external -- we're leaving this part of the CFG unchanged -- doing nothing

                    // (remember: every edge can be a transition between external functions, it can be
                    //   - a return, and a different return from each predecessor
                    //   - a call)
                    // in the return case:
                    //   just popping everything from the call stack until the top is the current external function
                    // in the call case:
                    //   push the current function on top
                    // (we know that we're not inside an internal function)
                    predResults.first().callStack.popUntilExtOrPush(CallStackEntry.ExtFunc(block.calleeIdx)).also {
                        check(!it.hasInternal()) { "internal functions on call stack, but not currently building a sub graph" }
                    }
                } else {
                    // add edge(s) inside the current sub graph we're building
                    currSubGraphBuilder!!.addEdges(predResults, block)
                }


            // handle transitions in the current block
            inputProg.code[block]?.forEachIndexed { idx, cmd ->
                val cmdPointer = CmdPointer(block, idx)
                val insideInternalCall = callStack.hasInternal()
                if (cmd is TACCmd.Simple.AnnotationCmd && cmd.isIntFuncStart()) {
                    val func = cmd.toIntFuncStart()

                    if (!insideInternalCall) {
                        val externalProcedure = when (func) {
                            is CallStackEntry.IntFuncStart.EVM ->
                                inputProg.procedures.find { it.callId == block.calleeIdx } // XXX nicer way of lookup?
                                    ?: error("failed to find procedure id for index ${block.calleeIdx} in current program (but it should be there)")
                            is CallStackEntry.IntFuncStart.Solana ->
                                // As a convention, in Solana there is only one external artificial procedure.
                                Procedure(callId = 0, procedureId = solanaEntryPoint)
                        }
                        // calling from an externally called function invocation to an internal one start a "big" new subgraph
                        currSubGraphBuilder =
                            InternalFuncSubgraph.Builder(
                                internalCallStart = cmdPointer,
                                startCmd = cmd,
                                externalProcedure = externalProcedure
                            )
                    } else {
                        // calling from an internally called function invocation to an internal one
                        currSubGraphBuilder!!.enterInternalCall(cmdPointer, func, cmd)
                    }

                    callStack = callStack + func
                } else if (cmd is TACCmd.Simple.AnnotationCmd && cmd.isIntFuncEnd()) {
                    val func = cmd.toIntFuncEnd()

                    callStack = callStack.popInternal(func)

                    if (!callStack.hasInternal()) {
                        // exiting from an internal call to an external one --> finish the "big" new sub graph
                        val subGraph = currSubGraphBuilder!!.finish(cmdPointer, cmd)
                        currSubGraphBuilder = null
                        replacements[subGraph.internalCallStart to subGraph.internalCallEnd] = subGraph
                    } else {
                        // exiting from an internal call to an internal one
                        if(inputProg.code[cmdPointer.block]?.getOrNull(cmdPointer.pos + 1) == null) {
                            throw UnsupportedOperationException("internal return is the last command in a block, " +
                                "we're not yet supporting this case for AddInternalFunctions")
                        }
                        currSubGraphBuilder!!.exitInternalCall(callStack, cmdPointer, cmd)
                    }
                } else {
                    if (!insideInternalCall) {
                        // transition in code in an external invocation --> nothing to do
                    } else {
                        // regular transition inside an internal function --> update subgraph
                        currSubGraphBuilder!!.addCmd(cmdPointer, cmd)
                    }
                }
            }

            BlockAndCallStack(block, callStack)
        }

        var currentProg = inputProg

        val mapping = CmdPtrMapping(inputProg.code)

        // this is needed when we need to split up the same block in the original program more than once

        replacements.forEachEntry { (_, subgraph) ->
            val patchingProgram = currentProg.toPatchingProgram(inputProg.name + "_internal_functions")

            patchingProgram.procedures.addAll(subgraph.newProcedures)

            patchingProgram.replaceSubgraph(
                subgraph.internalCallStart,
                subgraph.internalCallEnd,
                subgraph.newBlockGraph,
                subgraph.newCodeBlocks,
                subgraph.blocksToRemove,
                subgraph.newGraphEntry,
                subgraph.newGraphExit,
                subgraph.cmdPtrMapping,
                mapping,
            )
            currentProg = patchingProgram.toCode(inputProg)
        }

        return TACProgWithIntFuncs(currentProg, mapping)
    }
}


/**
 * A "long copy", in the spirit of [TACExpr.LongStore], but for pointers to sections of tac programs.
 * There were thoughts to use this more widely to implement a [CmdPtrMapping] with a smaller footprint, but I decided
 * to wait with that until we have performance data.
 * Now it just helps with one operation.
 */
data class CmdPtrLongCpy(
    val oldBlock: NBId,
    val oldStartPos: Int,
    val newBlock: NBId,
    val newStartPos: Int,
    val len: Int
) {
    init {
        require(len > 0) {
            "can't create with len <= 0"
        }
    }

    /** ptr lies in the "old" range */
    operator fun contains(ptr: CmdPointer) =
        ptr.block == oldBlock && ptr.pos in oldStartPos until oldStartPos + len

    fun exec(old: CmdPointer): CmdPointer {
        require(old in this) { "did not find the to-be-updated position in the mapping; position: $old ; mapping: $this" }
        return CmdPointer(newBlock, old.pos - oldStartPos + newStartPos)
    }

    /** aka this' copy domain is a superset of other's copy domain */
    fun containsAll(other: CmdPtrLongCpy): Boolean =
        oldBlock == other.oldBlock &&
            oldStartPos <= other.oldStartPos &&
            oldStartPos + len >= other.oldStartPos + other.len
}

class CmdPtrMapping(private val backing: MutableMap<CmdPointer, CmdPointer>) {

    companion object {
        operator fun invoke() = CmdPtrMapping(mutableMapOf())
        operator fun <T> invoke(code: Map<NBId, List<T>>) =
            CmdPtrMapping(
                mutableMapOf<CmdPointer, CmdPointer>().apply {
                    code.entries.forEach { (block, cmds) ->
                        cmds.indices.forEach { pos ->
                            val ptr = CmdPointer(block, pos)
                            put(ptr, ptr)
                        }
                    }
                }
            )

        operator fun invoke(longCopies: List<CmdPtrLongCpy>) =
            CmdPtrMapping(mutableMapOf()).apply {
                longCopies.forEach { add(it) }
            }
    }
    val size: Int
        get() = backing.size

    val domain: Set<CmdPointer>
        get() = backing.keys
    val range: Collection<CmdPointer>
        get() = backing.values

    fun add(item: CmdPtrLongCpy) {
        for (i in 0 until item.len) {
            require(CmdPointer(item.oldBlock, item.oldStartPos + i) !in backing) { "expecting this method to only add fresh domain items" }
            backing[CmdPointer(item.oldBlock, item.oldStartPos + i)] = CmdPointer(item.newBlock, item.newStartPos + i)
        }
    }

    operator fun get(old: NBId): Set<NBId> = backing.entries.mapNotNullToSet { (o, n) ->
        if (o.block == old) {
            n.block
        } else {
            null
        }
    }

    operator fun get(old: CmdPointer): CmdPointer = backing[old] ?: error("not found: $old")

    /**
     * Update this mapping with the entries of the given mapping.
     */
    fun merge(blockMapping: CmdPtrMapping) {
        // not true, unless we clean up first:.. require(!backing.keys.containsAny(blockMapping.backing.keys)) { "expecting no need for overwriting here" }
        backing.putAll(blockMapping.backing)
    }

    /**
     * Block [splitPoint.block] was split in two, at position [splitPoint.pos].
     * The newly created block is [newBlock].
     * Additionally, [nrCmdsRemoved] have been removed from the new block at the beginning.
     *
     * Graphically:
     *
     *   [splitPoint.block]:
     *  |-----|
     *  |     |
     *  |     | <- [splitPoint]
     *  |     |
     *  |     |
     *  |     |
     *  |-----|
     *
     *  ~~>
     *
     *  1. the mappings at the beginning stay / are restored:
     *   [splitPoint.block]:
     *  |-----|
     *  |     |
     *  |-----| <- [splitPoint]
     *
     *  2. remove mappings for [nrCmdsRemoved] commands after [splitPoint] (or don't insert them again)
     *
     *  3. add new mappings from the end of the old block into [newBlock]
     *     (pre-image starting at [cmdPointer.pos] + [nrCmdsRemoved] till the end of the old block)
     *   [newBlock]:
     *  |-----|
     *  |     |
     *  |-----|
     *
     * @param firstPosInNewBlockOrig location, in the original program, of the first command that will end up in the [newBlock]
     * @param firstPosInNewBlock location, in the intermediate program, of the first command that will end up in the [newBlock]
     */
    fun splitBlock(
        firstPosInNewBlockOrig: CmdPointer,
        origBlockLen: Int,
        firstPosInNewBlock: CmdPointer,
        newBlock: NBId,
        nrCmdsRemoved: Int
    ) {
        // step 2
        for (i in 0 until nrCmdsRemoved) {
            backing.remove(firstPosInNewBlockOrig + i)
        }
        // step 3
        val nrCmdsInNewBlock = origBlockLen - firstPosInNewBlock.pos - nrCmdsRemoved
        for (i in 0 until nrCmdsInNewBlock) {
            // indices pointing to old block
            backing[CmdPointer(firstPosInNewBlockOrig.block, firstPosInNewBlockOrig.pos + nrCmdsRemoved + i)] = CmdPointer(newBlock, i)
        }
    }

    /**
     * For all [CmdPointer]s in the domain that belong to [block], shift all [CmdPointer.pos]'s in their mapping-image
     * by [i] (by adding, so a negative [i] will shift towards the start of the block).
     * Entries that would get a negative index are dropped.
     */
    fun shiftCommands(block: NBId, i: Int) {
        backing.entries.filter { it.key.block == block }.forEach {
            if (it.value.pos + i >= 0) {
                backing.replace(it.key, it.value + i)
            } else {
                backing.remove(it.key)
            }
        }
    }
}

/**
 * A subgraph that has explicit internal calls. We build this while iterating in step 1 of
 * [AddInternalFunctions.addInternalFunctionIdxs], and insert this subgraph into the TAC program in step 2.
 */
@SuppressRemapWarning
internal data class InternalFuncSubgraph(
    val internalCallStart: CmdPointer,
    val internalCallEnd: CmdPointer, // inclusive, i.e., this command is also replaced by the sub graph
    val newBlockGraph: BlockGraph,
    val newCodeBlocks: Map<NBId, List<TACCmd.Simple>>,
    val blocksToRemove: Set<NBId>,
    val newGraphEntry: NBId,
    val newGraphExit: NBId,
    val newProcedures: Collection<Procedure>,
    // used to update the global blockMapping;
    // this takes care of the mapping into the new blocks only, it's oblivious to the rest of the graph
    val cmdPtrMapping: CmdPtrMapping,
) {
    class Builder(val internalCallStart: CmdPointer, startCmd: TACCmd.Simple.AnnotationCmd, val externalProcedure: Procedure) {

        private val debug = true

        private val startFunc = run {
            require(startCmd.isIntFuncStart()) {
                "expecting an InternalFuncStartAnnotation or SbfInlinedFuncStartAnnotation in the given AnnotationCmd, got $startCmd"
            }
            startCmd.toIntFuncStart()
        }

        private val blockGraph = MutableBlockGraph()

        private val newProcedures = mutableSetOf<Procedure>()

        fun MutableBlockGraph.addEdge(src: NBId, trg: NBId) {
            blockGraph[src] = getOrPut(src) { treapSetOf() }.add(trg)

            if (debug) {
                val newBlocks = oldPosToNewBlock.values.flatMap { it.values }
                val srcBlock = newBlocks.find { it.newNBId == src }
                (srcBlock?.cmds?.last() as? TACCmd.Simple.JumpCmd)?.let {
                    check(it.dst == trg) { "wrong Jump in new block; block: $srcBlock ; target block: $trg" }
                }
            }
        }

        /**
         * NBId as K1 for faster lookup
         */
        private val oldPosToNewBlock = mutableNestedMapOf<NBId, CmdPointer, NewBlock>()

        /**
         * Retrieve the topologically last of the new blocks that will replace [oldBlock].
         * This works as is, but we might also consider managing these latest new blocks by adding them to
         * [BlockAndCallStack].
         */
        private fun currBlock(oldBlock: NBId) =
            oldPosToNewBlock[oldBlock]?.maxBy { it.key.pos }?.value
                ?: error("failed to look up new block for $oldBlock")

        private val newCalleeIdxs = mutableMapOf<CallStackEntry, CallId>()

        /** callStack-top -> CallId of fresh nodes we're creating */
        private fun getNewCalleeIdx(func: CallStackEntry): CallId =
            newCalleeIdxs.getOrPut(func) {
                when (func) {
                    is CallStackEntry.ExtFunc ->
                        // when an external call is at the top of the call stack (which includes internal and
                        // external calls), we keep that calleeIdx as is for the fresh nodes
                        func.calleeIdx
                    is CallStackEntry.IntFuncStart ->
                        // might be nice to have these visually different, e.g. starting at 1000 or so .. but maybe not important
                        Allocator.getFreshId(Allocator.Id.CALL_ID)
                }
            }


        /**
         * old block x call stack top -> new block
         */
        private val newNBIds = mutableNestedMapOf<CmdPointer, CallStackEntry, NBId>()
        private fun getNewNBId(old: CmdPointer, func: CallStackEntry): NBId {
            return newNBIds.getOrPut(old, func) {
                old.block.copy(
                    calleeIdx = getNewCalleeIdx(func),
                    freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY) // XXX do more elegant somehow?
                )
            }
        }

        /**
         * The new block that our traversal is currently in.
         * start with a stack consisting of [startFunc], and an empty new block
         */
        val entry = NewBlock(
            startFunc,
            internalCallStart,
            getNewNBId(internalCallStart, startFunc)
        ).also { it.cmds.add(startCmd) }


        /** [oldBlockStart] referring to the TAC program before the add internal function transformation -- i.e. the command in the old tac graph that will be the first in this new block */
        inner class NewBlock(
            val func: CallStackEntry,
            val oldBlockStart: CmdPointer,
            val newNBId: NBId
        ) {
            init {
                if (func is CallStackEntry.IntFuncStart) {
                    val procedureId = when (func) {
                        is CallStackEntry.IntFuncStart.EVM ->
                            Internal(func.annot.methodSignature.functionName, externalProcedure)
                        is CallStackEntry.IntFuncStart.Solana ->
                            Solana(func.annot.name)
                    }
                    newProcedures.add(Procedure(newNBId.calleeIdx, procedureId))
                }
                check(oldPosToNewBlock[oldBlockStart.block, oldBlockStart] == null) {
                    "double creation of a new block: $this"
                }
                oldPosToNewBlock.put(oldBlockStart.block, oldBlockStart, this)
            }
            val cmds: MutableList<TACCmd.Simple> = mutableListOf()

            override fun toString(): String {
                return "NewBlock(f=$func, old=$oldBlockStart, new=$newNBId)"
            }
        }

        fun finish(internalCallEnd: CmdPointer, endCmd: TACCmd.Simple.AnnotationCmd): InternalFuncSubgraph {
            check(internalCallEnd.block.calleeIdx == internalCallStart.block.calleeIdx) {
                "calleeIdx in start- and end pointer are not the same (--> calls and returns not well-nested)"
            }

            val currBlock = currBlock(internalCallEnd.block)
            currBlock.cmds.add(endCmd)

            val newGraphEntry = entry.newNBId
            val newGraphExit = currBlock.newNBId

            val allNewBlocks = oldPosToNewBlock.values.flatMap { it.values }

            val blockMapping = CmdPtrMapping().apply {
                allNewBlocks.forEach {
                    add(CmdPtrLongCpy(it.oldBlockStart.block, it.oldBlockStart.pos, it.newNBId, 0, it.cmds.length))
                }
            }

            allNewBlocks.forEach { newBlock ->
                newBlock.cmds.forEachIndexed { i, cmd ->
                    if (i != 0 &&
                        cmd is TACCmd.Simple.AnnotationCmd &&
                        cmd.isIntFuncStart()) {
                        logger.warn { "new block with a func start in the middle, at index $i, newBlock: $newBlock cmd: $cmd " }
                    } else if (i != newBlock.cmds.size - 1 &&
                        cmd is TACCmd.Simple.AnnotationCmd &&
                        cmd.isIntFuncEnd()) {
                        logger.warn { "new block with a func end in the middle, at index $i, newBlock: $newBlock cmd: $cmd " }
                    }
                }
            }

            return InternalFuncSubgraph(
                internalCallStart = internalCallStart,
                internalCallEnd = internalCallEnd,
                newBlockGraph = blockGraph,
                newCodeBlocks = allNewBlocks.associate { it.newNBId to it.cmds },
                // we keep the start and end blocks of the original graph (although we update their code), so we
                // don't include them here
                blocksToRemove = (newNBIds.keys.mapToSet { it.block } - internalCallStart.block) - currBlock.oldBlockStart.block,
                newGraphEntry = newGraphEntry,
                newGraphExit = newGraphExit,
                newProcedures = newProcedures,
                cmdPtrMapping = blockMapping
            )
        }

        fun enterInternalCall(cmdPointer: CmdPointer, func: CallStackEntry.IntFuncStart, startAnnot: TACCmd.Simple.AnnotationCmd) {
            val oldBlock = currBlock(cmdPointer.block)

            val newBlock = NewBlock(func, cmdPointer, getNewNBId(cmdPointer, func))

            // we add the entry command at the start of the entered block
            newBlock.cmds.add(startAnnot)
            blockGraph.addEdge(oldBlock.newNBId, newBlock.newNBId)
        }


        /** Exit from an internal call, not exiting the outermost, thus closing this sub-graph, yet. */
        fun exitInternalCall(callStackPopped: CallStack, exitCmdPtr: CmdPointer, endAnnot: TACCmd.Simple.AnnotationCmd) {
            val currBlock = currBlock(exitCmdPtr.block)
            val srcNBId = currBlock.newNBId
            // we add the exit command at the end of the exited block
            currBlock.cmds.add(endAnnot)

            val callerFunc = (callStackPopped.lastOrNull() ?: error("call stack should not become empty at this point"))
            val firstCmdInNewBlockPtr = exitCmdPtr + 1
            val newBlock = NewBlock(
                callerFunc,
                firstCmdInNewBlockPtr,
                getNewNBId(firstCmdInNewBlockPtr, callerFunc)
            )
            blockGraph.addEdge(srcNBId, newBlock.newNBId)
        }

        fun addCmd(cmdPointer: CmdPointer, cmd: TACCmd.Simple) {
            // Note that `cmd` might be a JumpCmd or JumpiCmd -- in that case their `dst` and `elseDst` entries will
            // need updating; however, since computing the call stack for a new context can be tricky, we don't do it
            // here, and retroactively update the commands in `addEdges`.
            currBlock(cmdPointer.block).cmds.add(cmd)
        }

        /**
         * Register control flow edges inside the sub graph we're building.
         * These edges might keep the same calleeIdx, or they might constitute an external call.
         */
        fun addEdges(predBlocksAndCallStacks: List<BlockAndCallStack>, succBlock: NBId): CallStack {
            val predBlocks = predBlocksAndCallStacks.map { it.block }
            val callStacks = predBlocksAndCallStacks.map { it.callStack }

            val externalCall =
                predBlocks.size == 1 && CallStackEntry.ExtFunc(succBlock.calleeIdx) !in callStacks.first()

            val externalReturn =
                predBlocksAndCallStacks.any {
                    it.block.calleeIdx != succBlock.calleeIdx && CallStackEntry.ExtFunc(succBlock.calleeIdx) in it.callStack
                }

            val newCallStack: CallStack = when {
                externalCall -> {
                    callStacks.first() + CallStackEntry.ExtFunc(succBlock.calleeIdx)
                }
                externalReturn -> {
                    callStacks.greatestCommonSubStack().popExtRetainInt(CallStackEntry.ExtFunc(succBlock.calleeIdx))
                }
                else -> {
                    // no change in external function
                    // in theory, all predecessor call stacks should be the same now due to well-nesting of internal
                    // calls and returns
                    // in practice, we need some robustness, when well-nesting is not maintained; we're going with the
                    // greatest common call stack of all the predecessors
                    callStacks.greatestCommonSubStack()
                }
            }

            val func = newCallStack.last()


            // make new block to go on with
            val currIdNew = getNewNBId(CmdPointer(succBlock, 0), func)

            // now that we have the new block's id, update the Jump[i]Cmds from the pred blocks
            predBlocks.forEach { predBlock ->
                val currBlock = currBlock(predBlock)
                val replacingCmd = when (val lastCmd = currBlock.cmds.removeLast()) {
                    is TACCmd.Simple.JumpiCmd -> {
                        if (succBlock == lastCmd.dst) {
                            lastCmd.copy(dst = currIdNew)
                        } else {
                            check(succBlock == lastCmd.elseDst) { "if not jumping to dst, must be jumping to elsedst" }
                            lastCmd.copy(elseDst = currIdNew)
                        }
                    }
                    is TACCmd.Simple.JumpCmd -> {
                        lastCmd.copy(dst = currIdNew)
                    }
                    else -> lastCmd // unchanged
                }
                currBlock.cmds.add(replacingCmd)
            }

            // add edges from predecessors
            predBlocks.forEach { predBlock ->
                val currBlock = currBlock(predBlock)
                blockGraph.addEdge(currBlock.newNBId, currIdNew)
            }
            NewBlock(func, CmdPointer(succBlock, 0), currIdNew)

            return newCallStack
        }
    }
}


/** Contains a block and the call stack at the end of it. */
internal data class BlockAndCallStack(val block: NBId, val callStack: List<CallStackEntry>)

private typealias CallStack = List<CallStackEntry>

private fun CallStack.hasInternal() = this.any { it is CallStackEntry.IntFuncStart }
private fun CallStack.pop() = this.subList(0, this.size - 1)

private fun CallStack.popInternal(exitAnnot: ExitAnnotation): List<CallStackEntry> {
    if ((lastOrNull() as? CallStackEntry.IntFuncStart)?.id() != exitAnnot.id()) {
        logger.warn { "internal function calls/returns not well-nested when exiting outermost internal call" }
        return this
    }
    return pop()
}

/**
 * Given a list of stacks computes the maximum prefix (/"bottom part") that all stacks have in common.
 * We use this one code block has many incoming returning edges.
 * These might be returning from different (external) functions.
 * This also means we "auto-pop" when there haven't been returns for some things further up the stacks.
 *
 * For illustration, a possible case where we use this method:
 * b_proc1    b2_proc2    b3_proc3
 *        \       |        /
 *            b0_proc0
 * In this example, we're (external-) returning from different procedures, all into the same code block.
 * This method accepts the call stacks at [b_proc1, b2_proc2, b3_proc3] and returns a call stack where
 * proc1, proc2, proc3 respectively, and everything above (that might have been there due to
 * non-well-nesting of internal calls/returns) have been "popped".
 *
 * (might implement this more elegantly)
 */
private fun List<CallStack>.greatestCommonSubStack(): CallStack {
    val newCallStack = mutableListOf<CallStackEntry>()
    for (i in this.first().indices) {
        if (all { it.size > i && it[i] == first()[i] }) {
            newCallStack.add(first()[i])
        } else {
            // call stacks start to diverge -- break and return current stack
            break
        }
    }
    return newCallStack
}

/** Pop all entries from this [CallStack] until [callStackEntry] is on top; if [callStackEntry] is not present,
 * return this call stack with [callStackEntry] pushed on top.
 * (useful for transitions between external functions; handles both return and call case) */
private fun CallStack.popUntilExtOrPush(callStackEntry: CallStackEntry.ExtFunc): CallStack {
    val newCallStack = this.toMutableList()
    while (newCallStack.top() != callStackEntry) {
        newCallStack.removeLast()
        if (newCallStack.isEmpty()) {
            // [callStackEntry] not found on call stack, we must be in the "call case", pushing onto original call stack
            return this + callStackEntry
        }
    }
    return newCallStack
}


/**
 * Pop entries from the call stack such that
 *  - [extToRetain] and everything below is retained
 *  - every [CallStackEntry.IntFuncStart] between [extToRetain] and the [CallStackEntry.ExtFunc] above it is retained
 *
 *  Note that it might be the case that [extToRetain] is already the top of this [CallStack] (might happen because we
 *  did [greatestCommonSubStack] before calling this), then we return this call stack as is.
 *
 *  Example:
 *   stack: `[0, int1, 1, int2, int4, 2, int5]`
 *   we return from (old calleeIdx) `2` to `1`
 *  Then, we want to keep the full context from which we were calling `2` (coming from `1`),
 *  which is `[0, int1, 1, int2, int4]`
 */
private fun CallStack.popExtRetainInt(extToRetain: CallStackEntry.ExtFunc): CallStack {
    if (extToRetain == last()) { return this }
    val i = indexOf(extToRetain)
    if (i == -1) { error("extToRetain not found in call stack; extToRetain: $extToRetain ; call stack: $this") }
    val j =
        this.withIndex().find { (idx, cse) -> idx > i && cse is CallStackEntry.ExtFunc }?.index
            ?: size // might happen, again, due to [greatestCommonSubStack] call before this one
    return subList(0, j)
}

internal sealed interface CallStackEntry {
    /** Internal functions start. */
    sealed interface IntFuncStart : CallStackEntry {
        @JvmInline
        value class EVM(val annot: InternalFuncStartAnnotation) : IntFuncStart

        @JvmInline
        value class Solana(val annot: SbfInlinedFuncStartAnnotation) : IntFuncStart

        fun id() = when (this) {
            is EVM -> this.annot.id
            is Solana -> this.annot.id
        }
    }

    /** External functions. */
    @JvmInline
    value class ExtFunc(val calleeIdx: CallId) : CallStackEntry
}

internal sealed interface ExitAnnotation {
    @JvmInline
    value class EVM(val annot: InternalFuncExitAnnotation) : ExitAnnotation

    @JvmInline
    value class Solana(val annot: SbfInlinedFuncEndAnnotation) : ExitAnnotation

    fun id() = when (this) {
        is EVM -> this.annot.id
        is Solana -> this.annot.id
    }
}

internal fun TACCmd.Simple.AnnotationCmd.isIntFuncStart() =
    this.annot.k == INTERNAL_FUNC_START || this.annot.k == SBF_INLINED_FUNCTION_START

/**
 * Returns the [CallStackEntry.IntFuncStart] for the [TACCmd.Simple.AnnotationCmd].
 * Assumes that it is either [InternalFuncStartAnnotation] or [SbfInlinedFuncStartAnnotation], and throws an exception
 * otherwise.
 */
internal fun TACCmd.Simple.AnnotationCmd.toIntFuncStart() =
    when (this.annot.v) {
        is InternalFuncStartAnnotation -> CallStackEntry.IntFuncStart.EVM(this.annot.v)
        is SbfInlinedFuncStartAnnotation -> CallStackEntry.IntFuncStart.Solana(this.annot.v)
        else -> throw IllegalStateException("Expected InternalFuncStartAnnotation or SbfInlinedFuncStartAnnotation")
    }

internal fun TACCmd.Simple.AnnotationCmd.isIntFuncEnd() =
    this.annot.k == INTERNAL_FUNC_EXIT || this.annot.k == SBF_INLINED_FUNCTION_END

/**
 * Returns the [ExitAnnotation] for the [TACCmd.Simple.AnnotationCmd].
 * Assumes that it is either [InternalFuncExitAnnotation] or [SbfInlinedFuncEndAnnotation], and throws an exception
 * otherwise.
 */
internal fun TACCmd.Simple.AnnotationCmd.toIntFuncEnd() =
    when (this.annot.v) {
        is InternalFuncExitAnnotation -> ExitAnnotation.EVM(this.annot.v)
        is SbfInlinedFuncEndAnnotation -> ExitAnnotation.Solana(this.annot.v)
        else -> throw IllegalStateException("Expected InternalFuncExitAnnotation or SbfInlinedFuncEndAnnotation")
    }
