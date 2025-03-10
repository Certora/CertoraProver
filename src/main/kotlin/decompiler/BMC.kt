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

package decompiler
import allocator.Allocator
import analysis.*
import analysis.icfg.CallGraphBuilder
import analysis.icfg.InlinedMethodCallStack
import analysis.icfg.Inliner
import analysis.pta.ABICodeFinder
import analysis.pta.LoopCopyAnalysis
import analysis.pta.abi.ABIAnnotator
import analysis.pta.abi.SerializationRangeMarker
import com.certora.collect.*
import config.Config
import config.Config.IsAssumeUnwindCondForLoops
import datastructures.stdcollections.*
import instrumentation.transformers.CodeRemapper
import instrumentation.transformers.CodeRemapper.BlockRemappingStrategy
import instrumentation.transformers.CodeRemapper.CallIndexStrategy
import instrumentation.transformers.CodeRemapper.IdRemapperGenerator.Companion.forId
import log.*
import tac.CallId
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACMeta.END_LOOP
import vc.data.TACMeta.START_LOOP
import java.io.Serializable
import java.math.BigInteger
import java.util.stream.Collectors
import java.util.stream.Stream

private val logger = Logger(LoggerTypes.BMC)

/**
 * UNROLL_CONST: perform UNROLL_CONST rounds of loop unrolling. meaning the loop head appear UNROLL_CONST+1 times
 */
class BMCRunner(@Suppress("PrivatePropertyName") private val UNROLL_CONST : Int, private var code: CoreTACProgram) {

    class DanglingAllocatorIdException(msg: String, val seq: Allocator.Id, val id: Int) : IllegalStateException(msg)

    companion object {
        private val callIndexStrat : CallIndexStrategy<WithLoopIndex> =
            CallIndexStrategy { state, callId, fresh ->
                /*
                 * Do not remap the root call index or the call id of the loop head (which we assume to be the
                 * id of the method body that the loop occurs within)
                 */
                if (callId == NBId.ROOT_CALL_ID || callId == state.loopIndex) {
                    callId
                } else {
                    fresh(callId)
                }
            }

        private val idRemapper = CodeRemapper.IdRemapperGenerator<WithIdRemapping> { state ->
            { it, id, gen ->
                if(it to id in state.remappableIds) {
                    state.idMap.getOrPut(it to id, gen)
                } else {
                    id
                }
            }
        }

        private val blockMappingStrat = BlockRemappingStrategy<BMCState> { id, _, ctxt, state ->
            if(ctxt == BlockRemappingStrategy.RemappingContext.SUCCESSOR && id == state.nextHead.first) {
                state.nextHead.second
            } else {
                state.blockMap[id] ?: id
            }
        }

        private val bmcRemapper = CodeRemapper(
            blockRemapper = blockMappingStrat,
            callIndexStrategy = callIndexStrat,
            idRemapper = idRemapper,
            variableMapper = { state, v -> state.freshVars.add(v); v }
        )

        private data class ValidatorState(
            val abiRemap: Map<Int, Set<Int>>,
            val deadOutsideLoop: Set<Pair<Allocator.Id, Int>>,
            val errorMessageOnValidationFailure: (Allocator.Id, Int) -> String
        )

        private val codeValidator = object : CodeRemapper<ValidatorState>(
            blockRemapper = BlockRemappingStrategy { bid, _, _, _ -> bid },
            callIndexStrategy = CallIndexStrategy { state, callIndex, _ ->
                if((Allocator.Id.CALL_ID to callIndex) in state.deadOutsideLoop) {
                    throw DanglingAllocatorIdException(
                        state.errorMessageOnValidationFailure(
                            Allocator.Id.CALL_ID,
                            callIndex
                        ), Allocator.Id.CALL_ID, callIndex
                    )
                }
                callIndex
            },
            variableMapper = { _, v -> v },
            idRemapper = { state ->
                { which, curr, _ ->
                    if(which to curr in state.deadOutsideLoop) {
                        throw DanglingAllocatorIdException(state.errorMessageOnValidationFailure(which as Allocator.Id, curr), which, curr)
                    }
                    curr
                }
            }
        ) {
            override fun <@Treapable T : Serializable> mapAnnotation(state: ValidatorState, k: TACCmd.Simple.AnnotationCmd.Annotation<T>): T {
                if(k.k == ABIAnnotator.REGION_START || k.k == ABIAnnotator.REGION_END) {
                    check(k.v is SerializationRangeMarker)
                    @Suppress("unchecked_cast")
                    return k.v.copy(
                        sources = k.v.sources.flatMapTo(mutableSetOf()) {
                            if(it.id in state.abiRemap) {
                                val n = state.abiRemap[it.id]!!
                                val gen = when(it) {
                                    is ABICodeFinder.Source.Decode -> ABICodeFinder.Source::Decode
                                    is ABICodeFinder.Source.Direct -> ABICodeFinder.Source::Direct
                                    is ABICodeFinder.Source.Encode -> ABICodeFinder.Source::Encode
                                }
                                n.map(gen)
                            } else {
                                listOf(it)
                            }
                        }
                    ) as T
                } else {
                    return k.v
                }
            }
        }

        fun unwindingCondMsg() = "Unwinding condition in a loop. " +
            "We recommend to run with ${IsAssumeUnwindCondForLoops.userFacingName()}, " +
            "or increase ${Config.LoopUnrollConstant.userFacingName()} to a value higher than ${Config.LoopUnrollConstant.get()}. " +
            "Please consult our documentation to be aware of the consequences on soundness when setting this flag."
    }

    /**
     * We annotate entry/exit blocks of loops in the following way:
     * -- entry blocks are predecessors of the loop.head which are not part of the loop itself
     * -- exit blocks are successors of any block in the loop.body which are not part of the loop itself.
     * -- we can assume that the labels will be always put in the right place because of the following facts:
     * 1. the loop.head as always at least two entry edges, one from the tail and one from the entry block(s). For
     * this reason we can assume that the label won't be put in the loop.head block but either in the end of the
     * entry blocks (if they don't have other edges) or in a new dedicated block.
     * 2. the blocks inside the loops have always an edge inside the loop itself + other optional edges outside the
     * loop to exit blocks. For this reason we can assume that the label won't be put in the blocks inside the loop
     * but in the start of the exit blocks.
     */
    internal fun annotateLoop(loop: Loop, loopId: Int):
            SimplePatchingProgram {

        // on the last command of loop.head we hope to find the source code of the loop itself
        val headLastCmd = code.getNodeCode(loop.head).last()
        val srcDetails = headLastCmd.metaSrcInfo?.getSourceDetails()
        val loopHeadSource = srcDetails?.let {
            "Loop at ${it.fileName}: line: ${it.lineNumber}: ${
                it.content.lines().firstOrNull()?.escapeQuotes()
            }"
        } ?: "unknown loop source code"
        val patching = code.toPatchingProgram()
        val startCommands = listOf<TACCmd.Simple>(
            SnippetCmd.EVMSnippetCmd.LoopSnippet.StartLoopSnippet(
                loopIndex = loopId,
                loopHeadSource
            ).toAnnotation(code.destructiveOptimizations),
            TACCmd.Simple.AnnotationCmd(START_LOOP, loopId)
        )
        val loopHeadPredecessors = patching.getPredecessors(loop.head)
        check(loopHeadPredecessors.size > 1) { "There should always be at least two predecessors to a loop.head" }
        loopHeadPredecessors.forEach { pred ->
            if (pred !in loop.body) {
                patching.insertAlongEdge(pred, loop.head, startCommands)
            }
        }
        val endCommands = listOf<TACCmd.Simple>(
            SnippetCmd.EVMSnippetCmd.LoopSnippet.EndLoopSnippet(loopId).toAnnotation(code.destructiveOptimizations),
            TACCmd.Simple.AnnotationCmd(END_LOOP)
        )
        loop.body.forEach { node ->
            patching.getSuccessors(node).forEach { succ ->
                if (succ !in loop.body) {
                    patching.insertAlongEdge(node, succ, endCommands)
                }
            }
        }
        code = patching.toCodeNoTypeCheck(code)
        return code.toPatchingProgram()
    }

    private interface WithLoopIndex {
        val loopIndex: CallId
    }

    private interface WithIdRemapping {
        val remappableIds: Set<Pair<Allocator.Id, Int>>
        val idMap: MutableMap<Pair<Any, Int>, Int>

    }

    /**
     * The state for one unrolling of a loop. Do not reuse this state across unrollings
     */
    private class BMCState(
        /**
         * Remapping for block IDs and successors (with the exception of the [nextHead] field, which is specifically
         * for remapping "back jumps").
         *
         * Blocks that do not appear in this map are not remapped.
         */
        val blockMap: Map<NBId, NBId>,
        /**
         * Mutable map for consistent remapping of (key, id) pairs. In practice, the key is always one of [Allocator.Id]
         */
        override val idMap: MutableMap<Pair<Any, Int>, Int>,
        override val loopIndex: CallId,
        /**
         * Only remap those ABI ids that appear in this set: as explained elsewhere, ids that do not appear in this
         * set are assumed to cross loop boundaries.
         */
        override val remappableIds: Set<Pair<Allocator.Id, Int>>,
        /**
         * Record the special "next head" successor when remapping a jump successor that points to the loop head
         * (this cuts the loop)
         */
        val nextHead: Pair<NBId, NBId>,
        /**
         * Record the new variable created by this remapping
         */
        val freshVars: MutableSet<TACSymbol.Var>
    ) : WithLoopIndex, WithIdRemapping

    private fun unroll(patchingIn: SimplePatchingProgram, loop: Loop, loopId: Int) {

        // normalize the blocks for simpler reasoning later
        val patching = appendJumpsToLoopBlocks(loop, patchingIn)

        var unrollCount = calculateUnrollConst(loop)
        val unrollCmds = createUnrollCmds(patching, loop) ?: unwindCondCheck(sym = TACSymbol.False, tagLoopTerminus = loopId).let { sinkCommands ->
            val pops = InlinedMethodCallStack(code.analysisCache.graph).iterateUpStackPushRecords(CmdPointer(loop.head, 0)).map { push ->
                TACCmd.Simple.AnnotationCmd(
                    Inliner.CallStack.STACK_POP,
                    Inliner.CallStack.PopRecord(
                        calleeId = push.callId,
                        callee = push.ref as MethodRef
                    )
                )
            }
            sinkCommands + pops
        }.also {
            // The unroll condition is a 'false', because we couldn't find the loop's exit-condition. In this case
            // we will unroll an extra iteration before asserting/assuming in order to make sure the loop's exit
            // condition is evaluated after the [unrollCount] copies of the loop.
            // This matters when e.g. a loop has a constant number of iterations in the code. In this case if we would only
            // create the exact number of copies as the loop iterates, we'd still end up reaching the assert/assume(false)
            // block because the loop condition wasn't tested after the final update to the loop variable.
            logger.info { "Adding one extra unrolling since found no exit condition for the loop $loopId" }
            unrollCount++
        }

        if (unrollCount == 0) {
            removeLoop(loop, patching, unrollCmds)
            return
        }

        val remappableIds = loop.body.asSequence().flatMap {
            code.analysisCache.graph.elab(it).commands
        }.flatMap {
            val seq = if (it.cmd is TACCmd.Simple.SummaryCmd) {
                MetaSourceFinder.extractSource(null, it.cmd.summ)?.let { sequenceOf(it) } ?: sequenceOf<Pair<Allocator.Id, Int>>()
            } else if (it.cmd is TACCmd.Simple.AnnotationCmd) {
                MetaSourceFinder.extractSource(it.cmd.annot.k, it.cmd.annot.v)?.let {
                    sequenceOf(it)
                } ?: sequenceOf()
            } else {
                sequenceOf()
            }
            it.cmd.meta.map.asSequence().mapNotNull {innerIt ->
                MetaSourceFinder.extractSource(innerIt.key, innerIt.value)
            } + seq
        }.toMutableSet()

        loop.body.filter {
            it.calleeIdx != loop.head.calleeIdx && it.calleeIdx != NBId.ROOT_CALL_ID
        }.mapTo(remappableIds) {
            Allocator.Id.CALL_ID to it.calleeIdx
        }

        /**
         * Those sequence, id pairs whose source (as determined by the source = true flag in a
         * [allocator.GeneratedBy] annotation, lies within the loop.
         * Because we create many copies of this id (after remapping) it should hold that
         * no references to these ids should exist outside of the loop (as this would imply whatever
         * referencing object now is referencing an entity within a specific unrolling, which is probably not what was
         * intended).
         *
         * This map is used in the validation phase, any reference found (via the id remapping process) to ids here cause
         * an exception to be thrown.
         */
        val deadOutsideLoop = mutableSetOf<Pair<Allocator.Id, Int>>()

        /**
         * ... of course the exception to the above are ABI IDs. In that case, we make sure to forward map a reference
         * to a single ID to the (now set) of ids.
         */
        val remapABIOutsideLoop = mutableMapOf<Int, MutableSet<Int>>()

        fun postProcessIds(
            idMap: Map<Pair<Any, Int>, Int>
        ) {
            idMap.asSequence().filter {
                it.key.first == Allocator.Id.CALL_ID
            }.mapNotNull {
                code.procedures.singleOrNull { proc ->
                    proc.callId == it.key.second
                }
            }.mapTo(patching.procedures) {
                it.mapId { k, i, _ ->
                    if(k != Allocator.Id.CALL_ID) {
                        i
                    } else {
                        idMap[(k to i)] ?: i
                    }
                }
            }

            idMap.forEach { (t, u) ->
                val (k,prev) = t
                if(k is Allocator.Id) {
                    if(k == Allocator.Id.ABI) {
                        remapABIOutsideLoop.getOrPut(prev) {
                            mutableSetOf()
                        }.add(u)
                    }
                    deadOutsideLoop.add(k to prev)
                }
            }

        }


        val unrollBlock = run {
            val capture = remappableIds
            class HeadState : WithLoopIndex, WithIdRemapping {
                override val loopIndex: CallId
                    get() = loop.head.calleeIdx
                override val remappableIds: Set<Pair<Allocator.Id, Int>>
                    get() = capture
                override val idMap: MutableMap<Pair<Any, Int>, Int> = mutableMapOf()
            }

            val theState = HeadState()

            val headRemapper = CodeRemapper<HeadState>(
                callIndexStrategy = callIndexStrat,
                idRemapper = idRemapper,
                variableMapper = { _, v ->
                    patching.addVarDecl(v)
                    v
                },
                blockRemapper = { blk, _, _, _ ->
                    blk
                }
            ).commandMapper(theState)

            val unrollBlock = patching.addBlock(
                // if we unroll after inlining, the new assume/assert unwind cond block should
                // still have the same callId as the whole loop
                // setting fresh copy because we really cannot know if we don't just happen to have an origStartPc
                // matching our new block in this call
                Allocator.getNBId().copy(calleeIdx = theState.loopIndex, freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY)),
                unrollCmds.map(headRemapper)
            )
            postProcessIds(theState.idMap)
            unrollBlock
        }

        val tailBlock = loop.tail
        // Modify the tail so that it points to the new unrollBlock instead of head
        patching.reroutePredecessorsTo(loop.head, unrollBlock) {
            it == tailBlock
        }

        // We need to patch all predecessors of loop.head to jump to the new copy of head we will construct later, so
        // save them here. Once the unroll is complete they will be changed to point to the new head.
        val origHeadPreds = patching.getPredecessors(loop.head)

        loop.body.forEach { block ->
            val succs = patching.getSuccessors(block)
            if (block == loop.head) {
                succs.forEach {
                    if (it in loop.body) {
                        // a start of an iteration
                        patching.insertAlongEdge(
                            block,
                            it,
                            listOf(SnippetCmd.EVMSnippetCmd.LoopSnippet.StartIter(unrollCount, loopId).toAnnotation(code.destructiveOptimizations))
                        )
                    }
                }
            } else {
                succs.forEach {
                    if (it !in loop.body) {
                        /* An exit of an iteration, which me occur at the end of the iteration,
                        or as a result of a "sudden" exit (e.g. return/break/continue statements). */
                        patching.insertAlongEdge(
                            block,
                            it,
                            listOf(SnippetCmd.EVMSnippetCmd.LoopSnippet.EndIter(unrollCount, loopId).toAnnotation(code.destructiveOptimizations))
                        )
                    }
                }
            }
        }


        // The patching mechanism is a sensitive to the order in which blocks are added - it is not allowed to add a
        // block before its successors, so use reversed topological order of the blocks to ensure we unroll the loop 'backwards'
        var prevHead = loop.head
        val loopIndex = loop.head.calleeIdx
        val withCall = object : WithLoopIndex {
            override val loopIndex: CallId
                get() = loopIndex
        }

        for (j in 1 until unrollCount) {
            // loopBlocksMap will hold a mapping from original blocks of the loop to the corresponding copies in this
            // unroll iteration. Here's why we need this:
            // Assume b1 has successor b2 (b1 and b2 are in loop.body).
            // We construct a copy of b2, b2'.
            // When creating b1', we need it to have b2' as the successor rather than the original b2, and this map is how we keep track of this.
            // This is the reason that we need to loop over the blocks in reverse topological order - to be sure b2' exists when constructing b1'.

            val idMap = mutableMapOf<Pair<Any, Int>, Int>()
            val loopBlockMap = loop.body.associateWith {
                patching.createOpenBlockFrom(it.copy(calleeIdx = callIndexStrat.remapCallIndex(
                    withCall, it.calleeIdx
                ) {
                    idMap.getOrPut(Allocator.Id.CALL_ID to it) {
                        Allocator.getFreshId(Allocator.Id.CALL_ID)
                    }
                }))
            }
            val state = BMCState(
                loopIndex = loopIndex,
                idMap = idMap,
                remappableIds = remappableIds,
                blockMap = loopBlockMap,
                nextHead = loop.head to prevHead,
                freshVars = mutableSetOf()
            )

            fun remap(nbid: NBId) =
                blockMappingStrat.remapInState(
                    nbid,
                    state = state,
                    ctxt = BlockRemappingStrategy.RemappingContext.SUCCESSOR,
                    remapCallIndex = idRemapper.createRemapper(state).forId(Allocator.Id.CALL_ID)
                )

            val mapper = bmcRemapper.commandMapper(state)
            for ((orig, clone) in loopBlockMap) {
                val block = code.analysisCache.graph.elab(orig)

                val commands = mutableListOf<TACCmd.Simple>()
                block.commands.forEach { (_, cmd) ->
                    commands.add(mapper(cmd))
                }
                val origSuccs = code.analysisCache.graph.succ(orig)
                patching.populateBlock(clone, commands, origSuccs.updateElements { remap(it) })

                if (block.id == loop.head) {
                    origSuccs.forEach {
                        if (it in loop.body) {
                            // a start of an iteration
                            patching.insertAlongEdge(
                                clone,
                                remap(it),
                                listOf(SnippetCmd.EVMSnippetCmd.LoopSnippet.StartIter(unrollCount - j, loopId).toAnnotation(code.destructiveOptimizations))
                            )
                        }
                    }
                }
                else {
                    origSuccs.forEach {
                        if (it !in loop.body) {
                            /* An exit of an iteration, which me occur at the end of the iteration,
                            or as a result of a "sudden" exit (e.g. return/break/continue statements). */
                            patching.insertAlongEdge(
                                clone,
                                remap(it),
                                listOf(SnippetCmd.EVMSnippetCmd.LoopSnippet.EndIter(unrollCount - j, loopId).toAnnotation(code.destructiveOptimizations))
                            )
                        }
                    }
                    if (block.id == tailBlock) {
                        // link the current iteration to the next iteration
                        patching.insertAlongEdge(
                            clone,
                            prevHead,
                            listOf(SnippetCmd.EVMSnippetCmd.LoopSnippet.EndIter(unrollCount - j, loopId).toAnnotation(code.destructiveOptimizations))
                        )
                    }
                }
            }

            prevHead = loopBlockMap[loop.head]!!

            patching.addVarDecls(state.freshVars)

            postProcessIds(state.idMap)
        }

        val state = ValidatorState(
            errorMessageOnValidationFailure = { category, id -> "Dangling id $id from sequence $category in ${code.name} while unrolling ${loop.head}" },
            abiRemap = remapABIOutsideLoop,
            deadOutsideLoop = deadOutsideLoop,
        )
        val mapper = codeValidator.commandMapper(state)

        /**
         * Here we are validating that, outside the ABI ids duplicated by the loop body
         * (handled by the [CodeRemapper.mapAnnotation] function of [codeValidator]),
         * the duplicated IDs within the loop are not referenced anywhere else outside the body.
         */
        val muts = code.parallelLtacStream().filter {
            it.ptr.block !in loop.body
        }.mapNotNull { lc ->
            if(lc.cmd is TACCmd.Simple.AnnotationCmd) {
                /**
                 * remap the annotation, replacing stale ABI references with the (duplicated) new IDs
                 *
                 * nb that we remap ids first, so when the "we aren't using stale IDs" check runs the old
                 * stale references should be gone
                 */
                lc.ptr to mapper(lc.cmd)
            } else {
                /* try to remap, repairing where we can. If we repair, return the repaired command, otherwise return null */
                try {
                    mapper(lc.cmd)
                } catch(e: DanglingAllocatorIdException) {
                    val summCmd = lc.maybeNarrow<TACCmd.Simple.SummaryCmd>() ?: throw e
                    val summ = summCmd.cmd.summ
                    when(summ) {
                        is CallSummary -> {
                            val ct = summ.callTarget.mapToSet { ct ->
                                if((ct is CallGraphBuilder.CalledContract.SymbolicOutput && ct.which == e.id && e.seq == Allocator.Id.CALL_SUMMARIES) ||
                                    (ct is CallGraphBuilder.CalledContract.InternalFunctionSummaryOutput && ct.which == e.id && e.seq == Allocator.Id.INTERNAL_CALL_SUMMARY)) {
                                    CallGraphBuilder.CalledContract.Unresolved
                                } else {
                                    ct
                                }
                            }
                            return@mapNotNull lc.ptr to summCmd.cmd.copy(
                                summ = summ.copy(
                                    callTarget = ct
                                )
                            )
                        }
                        else -> throw e
                    }
                }
                null
            }
        }.collect(Collectors.toList())
        muts.forEach {
            patching.replaceCommand(it.first, listOf(it.second))
        }

        patching.reroutePredecessorsTo(loop.head, prevHead) { it in origHeadPreds }
        code = patching.toCodeNoTypeCheck(code)
    }

    /**
     * Go over all blocks in the [loop], and make sure that they all end with a JUMP/JUMPI command to
     * their successor/s. This will make reasoning about the graph simpler, and help [patching] figure out how to
     * reconstruct the control flow later.
     */
    private fun appendJumpsToLoopBlocks(loop: Loop, patching: SimplePatchingProgram): SimplePatchingProgram {
        for (nb in loop.body) {
            val nbSuccs = patching.getSuccessors(nb)
            if (nbSuccs.isEmpty()) {
                // nb is a revert/return node
                continue
            }
            val nbCode = patching.originalCode[nb] ?: error("Could not find loop body node $nb in original code")
            val lastCmd = nbCode.last()
            if (nbSuccs.size == 1) {
                val succ = nbSuccs.single()
                if (succ in loop.body && lastCmd !is TACCmd.Simple.JumpCmd) {
                    // 'Replace' the last command in the block with a list of that same last command + JUMP
                    patching.replaceCommand(
                        CmdPointer(nb, nbCode.lastIndex),
                        listOf(lastCmd, TACCmd.Simple.JumpCmd(succ))
                    )
                }
            } else {
                check(nbSuccs.size == 2) { "blocks are expected to have 0, 1, or 2 successors, but $nb has ${nbSuccs.size}" }
                check(PatchingTACProgram.SIMPLE.isJumpCommand(lastCmd))
                { "If there are 2 successors, one would expect the last command to be a JUMPI or Summary with the condition to choose between them, but instead there is ${nbCode.last()}" }
            }
        }

        // 'commit' the above changes. This is needed so that when adding more blocks below we could rely on the last
        // cmd always being a JUMP/JUMPI, as stated above.
        code = patching.toCodeNoTypeCheck(code)
        return code.toPatchingProgram()
    }

    /**
     * Remove all blocks in [loop] from the graph, and replace them with a single block containing the [unrollCmds]
     */
    private fun removeLoop(loop: Loop, patching: SimplePatchingProgram, unrollCmds: List<TACCmd.Simple>) {
        val unrollBlock = patching.addBlock(Allocator.getNBId(), unrollCmds)
        patching.reroutePredecessorsTo(loop.head, unrollBlock)
        val origRoots = patching.getRoots()
        loop.sorted { patching.getPredecessors(it) }.forEach { patching.removeBlock(it) }
        var roots = patching.getRoots()
        while (!origRoots.containsAll(roots)) {
            for (nb in roots) {
                if (nb !in origRoots) {
                    patching.removeBlock(nb)
                }
            }
            roots = patching.getRoots()
        }
        code = patching.toCodeNoTypeCheck(code)
    }

    /**
     * [sym] is the symbol to assert/assume to be true. When this function is called for the unwinding when the loop
     * condition variable can't be inferred, [sym] is actually False, indicating that the path that reached here
     * cannot/should not be true.
     *
     * [tagLoopTerminus], if non-null, tags the assert/assume command with the id of the loop for which we are
     * asserting/assuming the unwinding condition. It is expected (but not checked, as this is a private function)
     * that this is only non-null when sym is False.
     */
    private fun unwindCondCheck(sym: TACSymbol, tagLoopTerminus: Int? = null): List<TACCmd.Simple> {
        val terminusTag : (MetaMap) -> MetaMap = {
            if(tagLoopTerminus != null) {
                it + (TACMeta.SYNTHETIC_LOOP_END to tagLoopTerminus)
            } else {
                it
            }
        }
        return if (IsAssumeUnwindCondForLoops.get()) {
            listOf(TACCmd.Simple.AssumeCmd(sym).mapMeta(terminusTag))
        } else {
            listOf(
                SnippetCmd.EVMSnippetCmd.LoopSnippet.AssertUnwindCond(sym ,unwindingCondMsg())
                    .toAnnotation(code.destructiveOptimizations),
                TACCmd.Simple.AssertCmd(sym, unwindingCondMsg()).mapMeta(terminusTag),
                TACCmd.Simple.AnnotationCmd(TACMeta.SCOPE_SNIPPET_END)
            )
        }
    }

    private fun createUnrollCmds(patching: SimplePatchingProgram, loop: Loop): List<TACCmd.Simple>? {
        // Check if head has a loop-exit condition. In this case we assume it's the main condition of the loop, and
        // assume/assert on that instead of unrolling a whole extra time
        /*
          Skip past loop head blocks that are involved in basic reverting checks, this can happen if the loop condition
          involves access a value in calldata.
         */
        var blockIt = loop.head
        while(true) {
            val g = code.analysisCache.graph
            if(g.succ(blockIt).size != 2) {
                return null
            }
            val succs = g.succ(blockIt)
            val (outOfLoop, inLoop) = succs.partition {
                it !in loop.body
            }
            if (outOfLoop.size != 1 || inLoop.size != 1) {
                break
            }
            if (!outOfLoop.all { it in g.cache.revertBlocks }) {
                break
            }
            blockIt = inLoop.single()
        }
        val commands = patching.originalCode[blockIt]!!.toMutableList()
        val lastCmd = commands.removeLast()
        if (lastCmd !is TACCmd.Simple.JumpiCmd) {
            return null
        }
        if (lastCmd.dst in loop.body && lastCmd.elseDst in loop.body) {
            // This is not a loop-exit condition
            return null
        }

        lastCmd.cond.let {
            if (it !is TACSymbol.Const && it.tag !is Tag.Bool) {
                // TODO: Can this really happen? If so - add a test please
                val expr = TACExpr.BinRel.Gt(it.asSym(), TACSymbol.lift(0).asSym())
                commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(it as TACSymbol.Var, expr))
            }

            if (lastCmd.dst in loop.body) {
                val expr = TACExpr.UnaryExp.LNot(it.asSym())
                commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(it as TACSymbol.Var, expr))
            }

            commands.addAll(
                unwindCondCheck(it)
            )
            commands.add(
                TACCmd.Simple.JumpCmd(
                    // jump out of the loop
                    if (lastCmd.dst in loop.body) {
                        lastCmd.elseDst
                    } else {
                        lastCmd.dst
                    }
                )
            )
        }

        return commands
    }

    internal fun getMergedLoops(loops: Set<Loop>): Set<Loop> {
        val loopsByHead = mutableMapOf<NBId, Loop>()
        loops.forEach() { loop ->
            loopsByHead.computeIfPresent(loop.head) { _, oldLoop ->
                Loop(
                    loop.head,
                    loop.body.union(oldLoop.body), oldLoop.tail
                )
            }?: loopsByHead.put(loop.head, loop)
        }
        return loopsByHead.values.toSet()
    }

    /**
     * Consolidates every subset of loops in [loops] that share the same [Loop.head] into
     * a single loop by adding a new single dummy tail block that becomes the successor
     * of all the loops' tails and a predecessor of [Loop.head].
     *
     * Why do we do this?
     *
     * We identify loops in the program (topologically) in terms of back edges, each is an edge from a block (called tail)
     * to a block that dominates it (called head). As a result, we may identify a single loop defined in the
     * contract source as multiple loops at the tac level. This may happen e.g. when there are loops with multiple
     * "exits" induced by continue statements.
     *
     * Identifying multiple loops in such cases, could result in unnecessarily big programs post unrolling.
     * The goal is avoiding this by consolidation of such loops.
     */
    private fun consolidateLoops(loops: Set<Loop>): Set<Loop> {
        fun Set<Loop>.areConsolidated() = this.groupBy { it.head }.values.all { it.size == 1 }
        if (loops.areConsolidated()) {
            return loops
        }
        val loopsByHead = loops.groupBy { it.head }.filter { (_, sharedHeadLoops) -> sharedHeadLoops.size > 1 }
        if (loopsByHead.isEmpty()) {
           throw IllegalStateException("loopsByHead cannot be empty since loops.areConsolidated() returned false")
        }
        val patching = code.toPatchingProgram()
        loopsByHead.forEach { (head, loopsOfHead) ->
            val tails = loopsOfHead.map { it.tail }

            val consolidatedTail =
                patching.addBlock(head, listOf(TACCmd.Simple.NopCmd, TACCmd.Simple.JumpCmd(head)))

            patching.reroutePredecessorsTo(head, consolidatedTail) { it in tails }
        }
        code = patching.toCode(code)
        // Verify that, after patching, all loops are indeed consolidated,
        // i.e., each loop head is the head of a single loop.
        val consolidatedLoops = getNaturalLoops(code.analysisCache.graph)
        check(consolidatedLoops.areConsolidated()) {
            "After consolidation, every head should belong to a single loop"
        }
        return consolidatedLoops
    }
    fun bmcUnroll(): CoreTACProgram {
        // If the code consists of a single block, and we assume block 0 has no loops, then there is nothing to do
        if (code.code.size == 1) {
            return code
        }

        var loops = consolidateLoops(getNaturalLoops(code.analysisCache.graph))
        while (loops.isNotEmpty()) {
            // Always unroll the innermost loops first, which avoids needing to unroll the same inner loop over and over
            // after an outer loop was unrolled.
            val loopHeads = loops.mapToTreapSet { it.head }
            val loopTails = loops.mapToTreapSet { it.tail }
            val innermostLoops = loops.filter {
                (it.body intersect loopHeads).singleOrNull() == it.head
                && (it.body intersect loopTails).singleOrNull() == it.tail
            }
            for (loopToUnrollBasic in innermostLoops) {
                /**
                * If our loop body itself had a loop within it that has a complicated exit condition (such that [createUnrollCmds]
                * returns null) then for that *inner* loop we unroll an extra time, which we wire up to a block that simply ends with
                * an assume/assert false. This assume/assert false is a new *sink* in the control-flow graph (this will matter later).
                *
                * Compare this behavior to when [createUnrollCmds] returns a non-null sequence of commands. In that case, we insert
                * an assumption on the condition variable of the jumpi command we identified as the loop exit condition,
                * and then transform the conditional jump to an unconditional exit from the loop. In other words, we do *not*
                * create a new sink, the assume/assert false command is inserted along the "regular" control flow.
                *
                * Recall all of this discussion is about the unrolling of the inner loop of the loop we are now currently unrolling.
                * After each step of unrolling, we recompute loop bodies. However, in the first case, the (recomputed) loop
                * body of the *outer* loop will *not* contain the final unrolling and assume/assert false sink in the loop body.
                * Why? Recall that we compute loop bodies using "natural loops", i.e., a loop exists if there is a jump to a successor
                * node such that the successor node dominates the source of the jump, a so called "backjump".
                * Then, the "body" of the loop are those blocks reachable by traversing the CFG backwards from the backjump to
                * the head of the loop. However, because the extra unrolling + assume/assert false created a sink, these
                * commands/blocks are *not* reachable from the backjump, and are excluded from the loop body.
                *
                * So, what's the problem? For one, it creates very strange graphs. More pressingly, if the inner loop occurred
                * within an external contract call, that call's id I is invalidated by the unrolling; each copy
                * of the *outer* loop should get a fresh call id. However, this duplication and remapping is only done
                * on the loop body which, as discussed above, does *not* include the extra unrolling + assume/assert false
                * blocks. These blocks *will* contain references to I, which the loop unrolling validator will detect, and
                * fail on.
                *
                * The solution is rather ad-hoc. We tag the extra assume/assert false commands with the [TACMeta.SYNTHETIC_LOOP_END]
                * meta which includes the loop id for which it was introduced. When unrolling a loop L, for every already unrolled
                * loop *head* within L (as indicated by a [TACMeta.START_LOOP] annotation), we look
                * for commands tagged with [TACMeta.SYNTHETIC_LOOP_END] that have a matching loop id. For each such command,
                * we check if there is a path back to the body of L. This path is checked to either revert, or *must* reach the synthetic loop end;
                * in other words, the synthetic loop end command must post-dominate all blocks along the path, modulo
                * reverts. If it does, then the chain of blocks reaching the synthetic loop end are considered to be part of the
                * loop body of L. This is what the code below computes.
                */

                /**
                * Find all loop ids of already unrolled loops within this loop.
                */
                val foundUnrolledLoops = loopToUnrollBasic.body.parallelStream().flatMap {
                    code.analysisCache.graph.elab(it).commands.mapNotNull { ltac ->
                        ltac.maybeAnnotation(START_LOOP)
                    }.stream()
                }.collect(Collectors.toSet())

                /**
                * Find all synthetic loop commands which aren't in the current loop body,
                * but which match the loop id of unrolled loops within the body...
                */
                val extraBlocks = code.parallelLtacStream().filter {
                    it.cmd.meta.get(TACMeta.SYNTHETIC_LOOP_END)?.let {
                        it in foundUnrolledLoops
                    } == true && it.ptr.block !in loopToUnrollBasic.body
                }.flatMap { synthEndCmd ->
                    /**
                    * Now see if we can trace a path back to the loop body we're unrolling. This identifies an "offshoot" branch
                    * that is logically part of the loop body, but isn't detected by the loop body detection
                    */
                    val graph = code.analysisCache.graph
                    var inferredBranch : Pair<NBId, NBId>? = null
                    val worklist = arrayDequeOf(synthEndCmd.ptr.block)
                    val visited = mutableSetOf<NBId>()
                    while(worklist.isNotEmpty()) {
                        val curr = worklist.removeLast()
                        if(!visited.add(curr)) {
                            continue
                        }
                        for(p in graph.pred(curr)) {
                            // we have looping code somehow
                            if(graph.cache.domination.dominates(curr, p)) {
                                return@flatMap Stream.empty()
                            }
                            if(p in loopToUnrollBasic.body) {
                                val edge = p to curr
                                /*
                                Do we have a principle edge that enters our offshoot branch?
                                */
                                if(inferredBranch == null || inferredBranch == edge) {
                                    if(inferredBranch == null) {
                                        inferredBranch = edge
                                    }
                                    continue
                                } else {
                                    // no? Then bail
                                    return@flatMap Stream.empty()
                                }
                            } else {
                                // keep going backwards
                                worklist.add(p)
                            }
                        }
                    }
                    /*
                    If we didn't actually reach the loop body, something has gone wrong.
                    */
                    if(inferredBranch == null) {
                        return@flatMap Stream.empty()
                    }
                    val branchStart = inferredBranch.second
                    val validationWorklist = arrayDequeOf(branchStart)
                    val dom = graph.cache.domination
                    val toRet = mutableSetOf<NBId>()
                    /*
                    we found a principle edge from the loop body. Now verify that all paths starting from this edge
                    must rejoin the loop body or reach the synthetic end block
                    */
                    while(validationWorklist.isNotEmpty()) {
                        val curr = validationWorklist.removeLast()
                        if(!toRet.add(curr)) {
                            continue
                        }
                        if(curr == synthEndCmd.ptr.block) {
                            /*
                            This block (which was added while the BMC is still processing, is expected to be a sink. If it
                            isn't, something has gone *very* wrong.

                            NB: we could check this immediately at the top of this block, but choose to do it here as
                            it fits with the rest of the validation
                            */
                            if(graph.succ(curr).isNotEmpty()) {
                                return@flatMap Stream.empty()
                            }
                        /*
                        AKA this is the dominance frontier of branchStart, and we are rejoining some other control flow.
                        Ensure that the control flow we are rejoining is within the loop. NB this can happen for
                        returns and reverts within the unrolled loop body.
                        */
                        } else if(!dom.dominates(branchStart, curr)) {
                            if(curr !in loopToUnrollBasic.body) {
                                return@flatMap Stream.empty()
                            }
                            continue
                        } else {
                            /*
                            This is more of a sanity check. We are somehow back in the loop body while still having
                            travelled what we *thought* was an "offshoot" branch but to get here we *had* to travel
                            through the offshoot. I can't think of any plausible solidity pattern that could
                            cause this, but let's be explicit in our assumptions.
                            */
                            if(curr in loopToUnrollBasic.body) {
                                return@flatMap Stream.empty()
                            }
                            /*
                            Expand our search
                            */
                            validationWorklist.addAll(graph.succ(curr))
                        }
                    }
                    /*
                    Then our validation is complete, and the blocks we visited during it are *exactly* the blocks we need
                    to add to the loop body.

                    Q: Couldn't you have done something with finding the closest ancestor in the dominance tree that
                    is also in the loop body, and then finding the subtree of that ancestor?
                    A: Maybe! But every time I try to be clever with dominance stuff I screw it up. This is less efficient
                    but I'm more confident it is correct...
                    */
                    toRet.stream()
                }.collect(Collectors.toSet())
                val loopToUnroll = loopToUnrollBasic.copy(body = loopToUnrollBasic.body + extraBlocks)
                val loopId = Allocator.getFreshId(Allocator.Id.LOOP)
                val patching = annotateLoop(loopToUnroll, loopId)
                // We assume that `patching` is built from the current value of `code`.
                unroll(patching, loopToUnroll, loopId)
            }
            loops = getNaturalLoops(code.analysisCache.graph)
        }
        return TACTypeChecker.checkProgram(code)
    }

    private fun calculateUnrollConst(loop: Loop): Int {
        val guessedUnrollConst = guessUnrollConst(loop)?.takeIf { guessedBound ->
            (guessedBound < Config.LoopUnrollBoundGuessingUpperLimit.get().toBigInteger()).also {
                if (!it) {
                    Logger.regression { "Guessed an unroll constant of $guessedBound, but we ignore it as it reaches" +
                        " the limit of ${Config.LoopUnrollBoundGuessingUpperLimit.get()}" }
                }
            }
        }
        Logger.regression { "Guessed an unroll constant for a loop of $guessedUnrollConst, " +
                "while global unroll const is $UNROLL_CONST" }
        return if (guessedUnrollConst != null) {
            if (guessedUnrollConst.isInt()) {
                UNROLL_CONST.coerceAtLeast(guessedUnrollConst.toInt())
            } else {
                Logger.regression { "Found a huge unroll constant $guessedUnrollConst," +
                    " falling back to the pre-set unroll constant $UNROLL_CONST" }
                UNROLL_CONST
            }
        } else if (isCopyLoop(loop)) {
            UNROLL_CONST.coerceAtLeast(Config.CopyLoopUnrollConstant.get())
        } else {
            UNROLL_CONST
        }
    }

    /**
     * Returns true if all definitions of [v] (not just the ones immediately preceding the [origin] ptr) are either const
     * assignments (that are strictly less than [c],), or are Add commands.
     * Otherwise returns false
     * The [visitedDefs] list is needed to avoid infinite recursion because the backedge of the loop hasn't been removed yet
     */
    private fun isMonotoneIncreasing(v: TACSymbol.Var, c: BigInteger, origin: CmdPointer, visitedDefs: MutableList<CmdPointer>): Boolean {
        val g = this.code.analysisCache.graph
        val def = this.code.analysisCache.def
        val nonTrivialDef = NonTrivialDefAnalysis(g, def)


        fun isMonotoneCmd(cmd: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>, sym: TACSymbol.Var): Boolean {
            val assignExp = cmd.cmd

            return when (assignExp.rhs) {
                is TACExpr.Sym.Const -> {
                    // we start with something larger than the condition
                    assignExp.rhs.s.value < c
                }
                is TACExpr.Sym.Var -> {
                    //assigning to a variable, the expression is increasing if the variable is increasing
                   isMonotoneIncreasing(assignExp.rhs.s, c, cmd.ptr, visitedDefs)
                }
                is TACExpr.Vec.Add -> {
                    val pattern =  PatternDSL.build { (Var + Const).commute.toBuilder()}
                    val result = PatternMatcher.compilePattern(g, pattern).queryFrom(cmd).toNullableResult()
                    val variable = result?.first
                    val const = result?.second
                    when (variable)  {
                        sym, is TACSymbol.Var -> {
                            // Great, we're adding to our symbol (x = x + C) or to another variable (x = y + C),
                            // let's check whether we're adding a constant 1, and
                            // that it's monotone increasing until this point as well
                            const == BigInteger.ONE &&
                                    isMonotoneIncreasing(variable, c, cmd.ptr, visitedDefs)
                        }
                        else -> {
                            return false
                        }
                    }
                }
                else -> false
            }
        }

        val defsOfV = nonTrivialDef.nontrivialDef(v, origin)
        return defsOfV.filter { ptr ->
            ptr !in visitedDefs
        }.all{ ptr ->
            visitedDefs.add(ptr)
            val cmd = g.elab(ptr)
            isMonotoneCmd(cmd.maybeNarrow()?: return@all false, v)
        }
    }

    /**
     * Checks if a var [v] monotonically decreases - all of its definitions (not just the ones immediately preceding
     * the [origin] ptr) are either const assignments (that are strictly more than [c]) or are Sub commands.
     * If it's monotonic, returns the initial value of the var, otherwise returns null
     * The [visitedDefs] list is needed to avoid infinite recursion because the backedge of the loop hasn't been removed yet
     */
    private fun isMonotoneDecreasing(v: TACSymbol.Var, c: BigInteger, origin: CmdPointer, visitedDefs: MutableList<CmdPointer>): BigInteger? {
        val g = this.code.analysisCache.graph
        val def = this.code.analysisCache.def
        val nonTrivialDef = NonTrivialDefAnalysis(g, def)

        fun isMonotoneCmd(cmd: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>, sym: TACSymbol.Var): BigInteger? {
            val assignExp = cmd.cmd
            return when (assignExp.rhs) {
                is TACExpr.Sym.Const -> {
                    // we start with something larger than the condition
                    if (assignExp.rhs.s.value > c) {
                        assignExp.rhs.s.value
                    } else {
                        null
                    }
                }
                is TACExpr.Sym.Var -> {
                    //assigning to a variable, the expression is decreasing if the variable is decreasing
                    isMonotoneDecreasing(assignExp.rhs.s, c, cmd.ptr, visitedDefs)
                }
                is TACExpr.BinOp.Sub -> {

                    val pattern =  PatternDSL.build { (Var - Const).toBuilder()}
                    val result = PatternMatcher.compilePattern(g, pattern).queryFrom(cmd).toNullableResult()
                    val variable = result?.first
                    val const = result?.second

                    when (variable) {
                        sym, is TACSymbol.Var -> {
                            // great, we're subtracting from our symbol or from another var (x = y - C).
                            // let's check if we're subtracting a constant of value 1 or a variable
                            if (const == BigInteger.ONE) {
                                // Decreasing by 1, yay!
                                // Verify that it's monotone decreasing until this point as well
                                isMonotoneDecreasing(variable, c, cmd.ptr, visitedDefs)
                            } else {
                                null
                            }
                        }
                        else -> {
                            null
                        }
                    }
                }
                else -> null
            }
        }

        var ret: BigInteger? = null
        val defsOfV = nonTrivialDef.nontrivialDef(v, origin)
        val allMonotonic = defsOfV.filter { ptr ->
            ptr !in visitedDefs
        }.let { allDefs ->
            if (allDefs.isEmpty()) {
                return c
            }
            allDefs.all { ptr ->
                visitedDefs.add(ptr)
                val cmd = g.elab(ptr)
                val b = isMonotoneCmd(cmd.maybeNarrow()?: return@all false, v)
                if (b != null) {
                    if (b == c) {
                        // This def is monotonic, but doesn't provide us with the start condition. Continue searching
                        true
                    } else {
                        check(b > c) { "The initial value should be larger than the constant in the condition: b=$b, c=$c, @$ptr" }
                        // Take the largest constant to be on the safe side. If there are somehow extraneous iterations
                        // they will effectively be unreachable code and optimized out later.
                        ret = (b - c).coerceAtLeast(ret ?: BigInteger.ZERO)
                        true
                    }
                } else {
                    false
                }
            }
        }
        return if (allMonotonic) {
            ret
        } else {
            null
        }
    }

    /**
     * A heuristic for guessing the loop unroll constant.
     * If the loop condition of type x < C (or x > C) for some constant C, and x is only incremented (decremented) then
     * we'll take C (or the initial value of x - C) as the loop count.
     */
    internal fun guessUnrollConst(loop: Loop): BigInteger? {
        val g = this.code.analysisCache.graph
        val def = this.code.analysisCache.def
        val condBlock = g.elab(loop.head)

        // Get the condition used by the head block to determine if to enter the loop or not (if such exists)
        val cond = g.pathConditionsOf(loop.head).filter { it.key in loop.body }.values.singleOrNull() ?: run {
                logger.info { "Could not find the path condition of a single edge entering the loop body" }
                return null
            }

        // Get the TACSymbol.Var that is checked in the condition
        val underlyingVar = when (cond) {
            is TACCommandGraph.PathCondition.EqZero -> cond.v
            is TACCommandGraph.PathCondition.NonZero -> cond.v
            else -> return null
        }

        // Get the CmdPointer that points to the initial definition site of our variable
        val defPtrOfCond = def.defSitesOf(underlyingVar, condBlock.commands.last().ptr)
            .singleOrNull() ?: run {
                logger.info { "Could not find an only def site of the underlying condition var $underlyingVar" }
                return null
            }
        val defCmdOfCond = g.elab(defPtrOfCond)

        if (defCmdOfCond.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            return null
        }

        return when (defCmdOfCond.cmd.rhs) {
            is TACExpr.BinRel -> {
                val o1 = defCmdOfCond.cmd.rhs.o1 as? TACExpr.Sym.Var ?: run {
                    logger.info { "lhs of Lt expression is not a variable ${defCmdOfCond.cmd.rhs.o1}" }
                    return null
                }
                val o2 = defCmdOfCond.cmd.rhs.o2.getAsConst() ?: run {
                    logger.info { "rhs of Lt expression is not a constant, it is ${defCmdOfCond.cmd.rhs.o2}" }
                    return null
                }

                // we have a const o2, which is good, and an o1 which is a variable, but is o1 monotonically increasing/decreasing?
                if (defCmdOfCond.cmd.rhs !is TACExpr.BinRel.Lt && defCmdOfCond.cmd.rhs !is TACExpr.BinRel.Gt &&
                    defCmdOfCond.cmd.rhs !is TACExpr.BinRel.Slt && defCmdOfCond.cmd.rhs !is TACExpr.BinRel.Sgt) {
                    null
                } else if (defCmdOfCond.cmd.rhs is TACExpr.BinRel.Lt || defCmdOfCond.cmd.rhs is TACExpr.BinRel.Slt ) {
                    if (isMonotoneIncreasing(o1.s, o2, defPtrOfCond, mutableListOf())) {
                        o2
                    } else {
                        logger.info { "Assignments to lhs within the loop and before it must adhere to the " +
                                "supported pattern" }
                        null
                    }
                } else { // condition is gt, so need to check monotonically decreasing
                    isMonotoneDecreasing(o1.s, o2, defPtrOfCond, mutableListOf()) ?: run {
                        logger.info { "Assignments to lhs within the loop and before it must adhere to the " +
                                "supported pattern" }
                        null
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Is this [loop] represents a copy-loop generated by the Solidity compiler.
     * For a copy-loop, we would expect to find a predecessor of 'loop.head' which is not in
     * 'loop.body', and will contain a [LoopCopyAnalysis.LoopCopySummary] summary.
     * In addition, due to the (first) insertAlongEdge invocation in the annotateEntryAndExitLoop function,
     * we would expect one of the 3 to hold (described below):
     */
    // I wish we would find a better way to figure it out.
    private fun isCopyLoop(loop: Loop): Boolean {
        val graph = code.analysisCache.graph

        return graph.pred(loop.head).any { block ->
            if (block in loop.body) {
                return@any false
            }

            val tacBlock = graph.elab(block)

            // 1. an insertion in the start of the "out" edge.
            // 2 commands in the predecessor: CopyLoop snippet, LoopCopySummary
            if (tacBlock.commands.size == 2 && isLoopCopySummaryCmd(tacBlock.commands[1].cmd)) {
                return@any true
            }

            // 2. a new block is created "on the edge", where we insert the START_LOOP snippet, and the START_LOOP annotation
            if (tacBlock.commands.size == 2 && isStartLoopAnnotationCmd(tacBlock.commands.last().cmd) && graph.pred(block).size == 1) {
                val pred = graph.elab(graph.pred(block).first())

                // 2 commands: CopyLoop snippet, CopyLoopSummary
                return@any pred.commands.size == 2 && isLoopCopySummaryCmd(pred.commands[1].cmd)
            }

            // 3. an insertion in the end of the "in" edge. thus, we would expect to have 4 commands in the predecessor:
            // the CopyLoop snippet, the LoopCopySummary, the START_LOOP snippet, and the START_LOOP annotation.
            if ((tacBlock.commands.size == 4 && isStartLoopAnnotationCmd(tacBlock.commands.last().cmd)) && isLoopCopySummaryCmd(tacBlock.commands[1].cmd)) {
                return@any true
            }

            return@any false
        }
    }

    private fun isStartLoopAnnotationCmd(cmd: TACCmd.Simple): Boolean = (cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.k == START_LOOP
    private fun isLoopCopySummaryCmd(cmd: TACCmd.Simple): Boolean = (cmd as? TACCmd.Simple.SummaryCmd)?.summ is LoopCopyAnalysis.LoopCopySummary
}
