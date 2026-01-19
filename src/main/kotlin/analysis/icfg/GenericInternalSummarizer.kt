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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.icfg

import algorithms.transitiveClosure
import analysis.*
import analysis.dataflow.GlobalValueNumbering
import analysis.dataflow.IGlobalValueNumbering
import analysis.ip.*
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import datastructures.stdcollections.*
import log.*
import spec.cvlast.QualifiedMethodSignature
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach

private val logger = Logger(LoggerTypes.SUMMARIZATION)

/**
 * A function that applies a summary in place, by operating on the provided patching program.
 */
typealias SummaryApplicator = (SimplePatchingProgram) -> Unit

/**
 * Generic version of InternalSummarizer that is parameterized over the type of internal function
 * start/end annotations and their argument/return value representations.
 *
 * [K] is the type of "summary selection", that is, a key which identifies which
 * summary to apply, [S] is the type of the selected summary.
 * [START] is the internal function start annotation type
 * [END] is the internal function end annotation type
 * [ARG_DATA] is the concrete representation of function arguments
 * [RET_SHAPE] describes the shape/structure of return values
 * [RET_DATA] is the concrete representation of return values
 *
 * See [InternalFunctionAbstraction] for a description of the roles of the ARG/RET type variables.
 */
abstract class GenericInternalSummarizer<K, S,
    START : InternalFunctionStartAnnot,
    END : InternalFunctionExitAnnot,
    ARG_DATA,
    RET_SHAPE,
    RET_DATA> : InternalFunctionAbstraction<START, END, ARG_DATA, RET_SHAPE, RET_DATA> {

    data class InternalFunction(val start: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
                                        val exits: Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>>)

    companion object {
        fun <START : InternalFunctionStartAnnot> getFunction(
            exitFinder: Summarization.ExitFinder,
            start: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
            startMeta: MetaKey<START>
        ): InternalFunction {
            val annotation = start.cmd.maybeAnnotation(startMeta)!!
            return InternalFunction(start, exitFinder.getExits(annotation.id, start.ptr))
        }
    }

    private fun LTACCmd.toFuncStart() = this.maybeAnnotation(startMeta)
    private fun LTACCmdView<TACCmd.Simple.AnnotationCmd>.toFuncStart() = this.cmd.maybeAnnotation(startMeta)

    /**
     * Here begins infrastructure for determining which summaries should be applied.
     */

    /**
     * We apply summaries in two situations: when we have a materialized, inlined call to a function,
     * or an explicit internal call summary. This class represents those two cases.
     */
    private sealed class NodeType {
        /**
         * No unique ID for the internal call summary, so just use the cmd pointer of the location as a unique key.
         */
        data class ExplicitSummary(
            val summaryLocation: CmdPointer
        ) : NodeType()

        /**
         * In this case, the unique ID of the internal function start annotation serves a unique key.
         *
         * The reason for using this will be clear later, when we have to generate [InlinedCall] instances that are subsumed
         * by callers.
         */
        data class InlinedCall(
            val id: Int,
        ) : NodeType()
    }

    /**
     * The summary selected to apply to some body of an internal function. [summaryKey] of type [K]
     * is the identify of the summary selected, and [selectedSummary] of type [S] is the
     * summary itself.
     */
    data class SummarySelection<out K, out S>(
        val summaryKey: K,
        val selectedSummary: S
    )

    /**
     * A summarization type, and the summary signature and summary itself.
     *
     * Q: Why not have three fields
     * A: we were already passing around this pair everywhere.
     */
    private data class SummaryPayload<K, S>(
        val summaryType: NodeType,
        val specCallSummToInternalSummSig: SummarySelection<K, S>
    )

    /**
     * "entry" in our static, internal call graph. Every node could have a summary attached ([specCallSummToInternalSummSig]),
     * a location where the entry "starts" ([where]) and a [entryType] indicating whether it is an explicit summary
     * or an inlined call.
     */
    private sealed class StaticEntry<K, S> {
        abstract val specCallSummToInternalSummSig: SummarySelection<K, S>?
        abstract val where: CmdPointer
        abstract val entryType: NodeType
    }

    /**
     * A node corresponding to an inlined callee, which can have direct [callees] and explicit summaries that appear
     * within its body [explicitSummaries]. The [id] is same that is used for
     * [NodeType.InlinedCall], and is the value of the [InternalFunctionStartAnnot.id]
     * field that generated this.
     */
    private data class FunctionNode<K, S>(
        override val specCallSummToInternalSummSig: SummarySelection<K, S>?,
        override val where: CmdPointer,
        val explicitSummaries: Set<NodeType.ExplicitSummary>,
        val callees: Set<Int>,
        val id: Int
    ) : StaticEntry<K, S>() {
        override val entryType: NodeType
            get() = NodeType.InlinedCall(id)
    }


    /**
     * An explicit summary node. Note that [specCallSummToInternalSummSig] is nullable, we fully expect this to not be the case,
     * but I am not willing to encode an assumption that the set of internal summary signatures being applied is *always*
     * the same.
     */
    private data class SummaryNode<K, S>(
        override val specCallSummToInternalSummSig: SummarySelection<K, S>?,
        override val where: CmdPointer
    ) : StaticEntry<K, S>() {
        override val entryType: NodeType
            get() = NodeType.ExplicitSummary(summaryLocation = where)
    }

    /**
     * Recursively instrument all internal summaries. the recursion could occur if an internal summary is
     * an expression summary which contains a call to some contract function which in turn calls an internal
     * function that needs sumarization.
     *
     * Note that this is expected to be called by the public APIs exposed by the subclasses of this class.
     * [codeModified] - set to true if some internal summary was already instrumented
     * @return if there were any internal summaries to instrument, returns [code] with
     * those instrumentation paired with `true`, else returns the original code paired with [codeModified]
     */
    protected fun summarizeInternalFunctionLoop(
        code: CoreTACProgram,
        codeModified: Boolean
    ): Pair<CoreTACProgram, Boolean> {
        /**
         * All of the code that follows is to find the summaries which need to be applied, and then which summaries should
         * NOT be applied because the application site is within a function body that is itself being summarized.
         *
         * NB: this does NOT assume that an [InternalCallSummary] must have a summary attached, nor does it
         * assume that a function replaced with an [InternalCallSummary] cannot later be discovered to appear within a
         * function that is being summarized.
         *
         * As it happens, both of those assumptions are true, FOR NOW, but these sort of assumptions have bitten us before:
         * I'd rather be defensive.
         */

        /**
         * A map from internal function ids to the immediate callees.
         */
        val callRelation = mutableMapOf<Int, Set<Int>>()

        /**
         * What [InternalCallSummary] instances appear within which internal function bodies.
         */
        val containsSummary =
            mutableMapOf<Int, Set<NodeType.ExplicitSummary>>()

        /**
         * Map from command pointers, to the [SummaryPayload] that should be applied. Note that the synthetic element
         * that occurs at the command pointer in question depends on the [SummaryPayload.summaryType];
         * if it is an [NodeType.ExplicitSummary] then it will be an instance of [vc.data.TACCmd.Simple.SummaryCmd],
         * if it is an [NodeType.InlinedCall] then it will be an instance of the internal function start annotation.
         *
         * NB that the pointer here is duplicated in the [SummaryPayload.summaryType] field if the summary type is an [NodeType.ExplicitSummary].
         * This was apparently unavoidable.
         */
        val toSummarize = mutableMapOf<CmdPointer, SummaryPayload<K, S>>()

        /**
         * Finally, the set of nodes that have been "killed" by an outer summary application, so summaries attached to them (if any) should
         * not be applied.
         */
        val subsumedByOuterSummary = mutableSetOf<NodeType>()

        val exitFinder = object : Summarization.ExitFinder(code) {
            override fun calleeStarted(cmd: TACCmd.Simple.AnnotationCmd): Int? {
                return cmd.maybeAnnotation(startMeta)?.id
            }

            override fun calleeExited(cmd: TACCmd.Simple.AnnotationCmd): Int? {
                return cmd.maybeAnnotation(endMeta)?.id
            }

        }

        /**
         * Find all function starts and explicit internal call summaries.
         */
        code.parallelLtacStream().filter {
            it.toFuncStart() != null || it.snarrowOrNull<InternalCallSummary>() != null
        }.map {
            /**
             * Get the signature, and use this to find a matching summarization.
             */
            val currentMethodSig = if(it.toFuncStart() != null) {
                it.toFuncStart()!!.which
            } else {
                it.snarrowOrNull<InternalCallSummary>()!!.methodSignature
            }

            val specCallSummToInternalSummSig = selectSummary(currentMethodSig)

            /**
             * In this case, we have a "leaf" in our call graph (really a tree, given inlining). Immediately return with the SummaryNode.
             */
            if(it.snarrowOrNull<InternalCallSummary>() != null) {
                return@map SummaryNode(
                    specCallSummToInternalSummSig = specCallSummToInternalSummSig,
                    where = it.ptr
                )
            }


            /**
             * Otherwise, compute the immediate callees of this inlined function (which we know we must be processing now)
             * and the immediately appearing explicit summaries.
             */
            val currentCallIdx = it.ptr.block.calleeIdx
            val currStart = it.maybeAnnotation(startMeta)!!
            val (immediateCallees, inlinedSummaries) = object : VisitingWorklistIteration<CmdPointer, NodeType, Pair<Set<Int>, Set<NodeType.ExplicitSummary>>>() {
                override fun process(it: CmdPointer): StepResult<CmdPointer, NodeType, Pair<Set<Int>, Set<NodeType.ExplicitSummary>>> {
                    val lc = code.analysisCache.graph.elab(it)
                    val start = lc.maybeAnnotation(startMeta)
                    val end = lc.maybeAnnotation(endMeta)
                    if(lc.cmd is TACCmd.Simple.SummaryCmd && lc.cmd.summ is ReturnSummary && lc.cmd.summ.ret is TACCmd.Simple.RevertCmd) {
                        return this.cont(listOf())
                    }
                    if(lc.cmd.maybeAnnotation(Inliner.CallStack.STACK_POP)?.calleeId == currentCallIdx) {
                        return this.cont(listOf())
                    }
                    if(start != null) {
                        val next = exitFinder.getExits(start.id, it).flatMap {
                            code.analysisCache.graph.succ(it.ptr)
                        }
                        return StepResult.Ok(
                            next = next,
                            result = listOf(NodeType.InlinedCall(start.id))
                        )
                    } else if(end != null) {
                        /*
                         * If this is a mismatch in a revert block ignore this mismatch,
                         * solidity loves to share revert blocks...
                         */
                        if (currStart.id != end.id && (
                            lc.ptr.block in code.analysisCache.revertBlocks ||
                            RevertBlockAnalysis.mustReachRevert(lc.ptr.block, code.analysisCache.graph)
                        )) {
                            return this.cont(listOf())
                        }
                        check(currStart.id == end.id) {
                            "Incoherent graph, hit ${end.id} @ $it, expecting to find end for $currStart"
                        }
                        return this.cont(listOf())
                    } else if(lc.cmd is TACCmd.Simple.SummaryCmd && lc.cmd.summ is InternalCallSummary) {
                        return StepResult.Ok(
                            next = code.analysisCache.graph.succ(it),
                            result = listOf(
                                NodeType.ExplicitSummary(
                                    summaryLocation = it
                                )
                            )
                        )
                    } else {
                        return this.cont(code.analysisCache.graph.succ(it))
                    }
                }

                override fun reduce(results: List<NodeType>): Pair<Set<Int>, Set<NodeType.ExplicitSummary>> {
                    val inlinedCallees = mutableSetOf<Int>()
                    val explicitSummaries =
                        mutableSetOf<NodeType.ExplicitSummary>()
                    for(r in results) {
                        when(r) {
                            is NodeType.ExplicitSummary -> explicitSummaries.add(r)
                            is NodeType.InlinedCall -> inlinedCallees.add(r.id)
                        }
                    }
                    return inlinedCallees to explicitSummaries
                }
            }.submit(code.analysisCache.graph.succ(it.ptr))
            /**
             * Return a node that captures the summary information, immediate callees, and the explicit summaries.
             */
            FunctionNode(
                id = currStart.id,
                callees = immediateCallees,
                explicitSummaries = inlinedSummaries,
                specCallSummToInternalSummSig = specCallSummToInternalSummSig,
                where = it.ptr
            )
        }.sequential().forEach { ent ->
            /**
             * Record that, at ent.where, we have a summary to apply of the given type. Note that this summary
             * may not end up being applied if the entry is subsumed by another summary (see below)
             */
            if (ent.specCallSummToInternalSummSig != null && !alreadyHandled(
                    ent.specCallSummToInternalSummSig!!,
                    code.analysisCache.graph.elab(ent.where)
                )) {
                toSummarize[ent.where] = SummaryPayload(
                    ent.entryType,
                    ent.specCallSummToInternalSummSig!!
                )
            }
            /**
             * Update our global graph of "function nodes" to callees and explicit summaries
             */
            if(ent is FunctionNode) {
                callRelation[ent.id] = ent.callees
                containsSummary[ent.id] = ent.explicitSummaries
            }
        }
        if (toSummarize.isEmpty()) {
            return code to codeModified
        }

        val transitiveCallRelation = transitiveClosure(callRelation, false)

        /**
         * Go over all summaries we found...
         */
        toSummarize.forEachEntry { (_, ent) ->
            /**
             * And for each summary which was applied to a function...
             */
            if(ent.summaryType is NodeType.InlinedCall) {
                /**
                 * Mark all explicit summaries as having been subsumed
                 */
                containsSummary[ent.summaryType.id].orEmpty().let(subsumedByOuterSummary::addAll)
                /**
                 * Mark all transitive, inlined, callees as having been subsumed
                 */
                transitiveCallRelation[ent.summaryType.id].orEmpty().mapTo(subsumedByOuterSummary) {
                    NodeType.InlinedCall(it)
                }
                /**
                 * Mark all explicit summaries that appear within the transitive callees as having also been subsumed.
                 */
                transitiveCallRelation[ent.summaryType.id].orEmpty().flatMapTo(subsumedByOuterSummary) {
                    containsSummary[it].orEmpty()
                }
            }
        }

        val gvn by lazy {
            GlobalValueNumbering(
                graph = code.analysisCache.graph,
                followIdentities = true
            )
        }

        /**
         * In parallel, for each (non-subsumed) summary, compute the suspended computation which will effect the summarization
         */
        val intermediateCode = toSummarize.entries.parallelStream().filter { (_, payload) ->
            payload.summaryType !in subsumedByOuterSummary
        }.map { (where, payload) ->
            /**
             * The type of this summary indicates the expected program element we expect to find at [where]
             */
            when(payload.summaryType) {
                is NodeType.ExplicitSummary -> {
                    handleExplicitSummary(
                        where = where,
                        explicit = code.analysisCache.graph.elab(where).snarrowOrNull<InternalCallSummary>()!!,
                        selectedSummary = payload.specCallSummToInternalSummSig,
                        enclosingProgram = code
                    )
                }
                is NodeType.InlinedCall -> {
                    val func = getFunction(exitFinder, code.analysisCache.graph.elab(where).narrow(), startMeta)
                    handleInlinedSummaryApplication(
                        function = func,
                        selectedSummary = payload.specCallSummToInternalSummSig,
                        intermediateCode = code,
                        gvn = gvn,
                    )
                }
            }
        }.patchForEach(code, check = true) {
            it(this)
        }
        return summarizeInternalFunctionLoop(intermediateCode, true)
    }

    /**
     * This version is a little bit more complicated, as we have to compute the confluence variables in the case of multiple
     * return sites.
     */
    private fun handleInlinedSummaryApplication(
        function: InternalFunction,
        selectedSummary: SummarySelection<K, S>,
        intermediateCode: CoreTACProgram,
        gvn: IGlobalValueNumbering,
    ): (SimplePatchingProgram) -> Unit {
        val callSite = function.start.toFuncStart()!!
        val functionId = callSite.which

        val (exitsite, suffix, rets) = this.handleFunctionExits(function, intermediateCode, gvn).leftOrElse { msg ->
            Logger.alwaysError(msg)
            throw IllegalStateException(msg)
        }

        val argData = projectArgs(callSite)

        return { patching: SimplePatchingProgram ->
            val remove = patching.splitBlockAfter(function.start.ptr)
            val exitBlock = when(exitsite) {
                is ExitPointType.ConfluenceBlock -> exitsite.block
                /**
                 * [ExitPointType.SimpleCommand.ptr] is the location of the exit annotation,
                 * so split AFTER it.
                 */
                is ExitPointType.SimpleCommand -> patching.splitBlockAfter(exitsite.ptr)
            }

            val summaryCmds =
                generateSummary(
                    /**
                     * This allows us to keep args looking non-null, and only throw if it turns out we couldn't compute it
                     * AND it was necessary (for EXP summaries)
                     *
                     * (a previous implementation would throw if args was null AND we we were using an expression summary.
                     * That check was here, in this function, and then we'd non-null assert in the [generateSummary]
                     * function, confident our caller had ensured it would be non-null for us.
                     *
                     * This is more explicit, and better)
                     */
                    object : GenericInternalFunctionStartInfo<ARG_DATA> {
                        override val methodSignature: QualifiedMethodSignature
                            get() = functionId
                        override val callSiteSrc: TACMetaInfo?
                            get() = callSite.callSiteSrc
                        override val calleeSrc: TACMetaInfo?
                            get() = callSite.calleeSrc
                        override val args: ARG_DATA
                            get() = argData

                    },
                    selectedSummary,
                    function.start.ptr,
                    rets,
                    intermediateCode
                ).appendToSinks(suffix)
            patching.replaceCommand(function.start.ptr, summaryCmds)

            patching.consolidateEdges(exitBlock, listOf(remove))


            logger.info {
                "Summarizing internal function $functionId with ${selectedSummary.selectedSummary} " +
                    "just before $exitsite in ${intermediateCode.name}"
            }
        }
    }


    /**
     * The various types of exits seen for an internal function.
     */
    private sealed interface ExitPointType {
        /**
         * There is a single, unique exit point. [ptr] is the location of the exit annotation,
         * splitting *after* this point will give the rest of the program after function exit.
         */
        data class SimpleCommand(val ptr: CmdPointer) : ExitPointType

        /**
         * There are multiple exit points, which have a single, unique confluence point [block]. The predecessor
         * blocks of [block] that contain the exit points *only* contain code for the exit points.
         * The start of [block] gives the rest of the program after function exit.
         */
        data class ConfluenceBlock(val block: NBId) : ExitPointType
    }

    private data class InternalFunctionExitData<FUNC_RET>(
        // what kind of exit point is this?
        val exitPointType: ExitPointType,
        // suffix code that does remapping required by confluence DSA
        val suffixCode: CommandWithRequiredDecls<TACCmd.Simple>,
        // the information about the function retun symbols
        val exitInfo: FUNC_RET
    )

    /**
     * Given a function body [function] in [intermediateCode], where the single, principle location the
     * function ends and the variables which hold its output, as represented by [InternalFunctionExitData]
     * or a string desribing why this proces failed.
     */
    private fun handleFunctionExits(
        function: InternalFunction,
        intermediateCode: CoreTACProgram,
        gvn: IGlobalValueNumbering
    ) : Either<InternalFunctionExitData<RET_DATA>, String> {
        val callSite = function.start.toFuncStart()!!
        val functionId = callSite.which

        val g = intermediateCode.analysisCache.graph

        val fakeNames = mutableMapOf<Int, TACSymbol.Var>()
        fun generateFakeReturnName(i: Int, f: TACSymbol.Var) : TACSymbol.Var {
            return fakeNames.computeIfAbsent(i) {
                TACKeyword.TMP(tag = f.tag, suffix = "!$i")
            }
        }


        val exitPoint = when(function.exits.size) {
            0 -> {
                val msg = "$functionId contains no non-reverting paths and may not be summarized"
                return msg.toRight()
            }
            1 -> {
                val exit = function.exits.single()
                ExitPointType.SimpleCommand(exit.ptr)
            }
            else -> {
                val confluence = function.exits.monadicMap {
                    g.succ(it.ptr.block).singleOrNull()
                }?.uniqueOrNull() ?: return "$functionId contains multiple exits and there is no apparent confluence point".toRight()
                ExitPointType.ConfluenceBlock(confluence)
            }
        }
        val isSingletonExit = exitPoint is ExitPointType.SimpleCommand

        /**
         * The intermediate result of this analysis is an [Either]. The [Either.Left] variant is used to record written variables discovered within the
         * the body of the function. The [Either.Right] is used to record exit variable aliases discovered on all flows out of a function.
         * That is, for every path out of a function, we expect to see an [Either.Right] that maps [TACSymbol.Var] to the ordinal of the return
         * value it holds (null/unmapped indicates a variable does not hold a return value). In the process function we merge these maps to infer
         * the principle variable that holds the return value of the function.
         */
        return object : VisitingWorklistIteration<CmdPointer, Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>, Either<InternalFunctionExitData<RET_DATA>, String>>() {
            private fun haltWithError(msg: String) = this.halt(msg.toRight())

            private val exitRevertBlockCache = mutableMapOf<NBId, Boolean>()

            private fun isExitRevertBlock(b: NBId) : Boolean = exitRevertBlockCache.getOrPut(b) {
                val exitBlocks = function.exits.mapToSet {
                    it.ptr.block
                }
                g.cache.reachability.get(b)?.containsAny(exitBlocks) == true
            }

            override fun process(it: CmdPointer): StepResult<CmdPointer, Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>, Either<InternalFunctionExitData<RET_DATA>, String>> {
                if(it.block in g.cache.revertBlocks && !isExitRevertBlock(it.block)) {
                    return this.cont(listOf())
                }
                val lc = g.elab(it)
                var commandResults = treapListOf<Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>>()
                if(lc.cmd is TACCmd.Simple.AssigningCmd && lc.cmd.lhs.tag != Tag.ByteMap) {
                    commandResults += lc.cmd.lhs.toLeft()
                }
                val exit = lc.maybeAnnotation(endMeta)?.takeIf {
                    it.id == callSite.id
                } ?: return StepResult.Ok(
                    next = g.succ(it),
                    result = commandResults
                )
                val accum = treapMapBuilderOf<TACSymbol.Var, Int>()
                val (exitVars, _) = decomposeReturnInfo(exit)
                for((i, v) in exitVars.withIndex()) {
                    val sym = if(!g.cache.lva.isLiveAfter(it, v) && !isSingletonExit) {
                        generateFakeReturnName(i, v)
                    } else {
                        v
                    }
                    accum[sym] = i
                }
                val exitBlock = when(exitPoint) {
                    is ExitPointType.SimpleCommand -> {
                        if(exitPoint.ptr != it) {
                            return this.haltWithError("Internal error: found exit at a point that is not the exit point?")
                        }
                        return this.result(commandResults + accum.toRight())
                    }
                    is ExitPointType.ConfluenceBlock -> exitPoint.block
                }
                var iter = g.succ(it).singleOrNull() ?: return this.haltWithError("At exit $it from $functionId do not have straightline code?")
                while(iter.block != exitBlock) {
                    val lcIter = g.elab(iter)
                    if(lcIter.maybeAnnotation(startMeta) != null || lcIter.maybeAnnotation(endMeta) != null) {
                        return this.haltWithError("At $lcIter reached from exit $it from $functionId have another function stack operation")
                    }
                    if(lcIter.cmd is TACCmd.Simple.AssigningCmd) {
                        accum.remove(lcIter.cmd.lhs)
                        commandResults += lcIter.cmd.lhs.toLeft()
                        val key = lcIter.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs?.let {
                            it as? TACExpr.Sym.Var
                        }?.s?.let(accum::get)
                        if(key != null) {
                            accum[lcIter.cmd.lhs] = key
                        }
                    }
                    iter = g.succ(iter).singleOrNull() ?: return this.haltWithError("At point $lcIter reached from exit $it from $functionId, have branching")
                }
                return this.result(commandResults + accum.toRight())
            }

            override fun reduce(results: List<Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>>): Either<InternalFunctionExitData<RET_DATA>, String> {
                val (writtenVars, exitStates) = results.partitionMap { it }
                // alias source is the location from which we should find aliases for written variables that are not exit vars
                val (livenessCheck, aliasSource) = when(exitPoint) {
                    is ExitPointType.SimpleCommand -> {
                        { v: TACSymbol.Var ->
                            g.cache.lva.isLiveAfter(exitPoint.ptr, v)
                        } to exitPoint.ptr
                    }
                    is ExitPointType.ConfluenceBlock -> {
                        { v: TACSymbol.Var ->
                            g.cache.lva.isLiveBefore(CmdPointer(exitPoint.block, 0), v)
                        } to CmdPointer(exitPoint.block, 0)
                    }
                }
                val exitRepresentative = function.exits.first().wrapped.maybeAnnotation(endMeta)!!
                val (exitRepVars, exitRepShape) = decomposeReturnInfo(exitRepresentative)
                val exitSize = exitRepVars.size
                val exits = mutableListOf<TACSymbol.Var>()
                for(ind in 0 until exitSize) {
                    val members = exitStates.map { m ->
                        m.keysMatching { _, i -> i == ind }.toTreapSet()
                    }
                    // we do this check to accomodate the "dead" dummy exit variable we generate
                    val singleChoice = members.uniqueOrNull()?.singleOrNull()
                    if(singleChoice != null) {
                        exits.add(singleChoice)
                        continue
                    }
                    // but if there is a consistent *LIVE* variable (due to remapping) pick that one
                    val withLivenessFiltering = members.map { cand ->
                        cand.filter(livenessCheck)
                    }
                    val singleLiveChoice = withLivenessFiltering.uniqueOrNull()?.singleOrNull()
                    if(singleLiveChoice != null) {
                        exits.add(singleLiveChoice)
                        continue
                    }
                    // okay, now that we have a bunch of live variables that are still candidates, see if there's one
                    // common one across all branches
                    var cand = withLivenessFiltering.first().toTreapSet()
                    for(other in withLivenessFiltering.subList(1, withLivenessFiltering.size)) {
                        cand = cand intersect other
                    }
                    val uniqueCandidate = cand.singleOrNull() ?: return "Could not infer principle return variable for output position $ind for $functionId".toRight()
                    exits.add(uniqueCandidate)
                }
                val suffix = MutableCommandWithRequiredDecls<TACCmd.Simple>()
                for(v in writtenVars) {
                    if(!livenessCheck(v)) {
                        continue
                    }
                    if(v in exits) {
                        continue
                    }
                    val ent = v.meta.find(TACSymbol.Var.KEYWORD_ENTRY)
                    // ignore keyword entries (tacM etc.) that are written within the summarized body.
                    // however, for some reason all stack variables are also annotated with the keyword entry "L"
                    // for "stack height" so explicitly ignore that...
                    if(ent != null && ent.maybeTACKeywordOrdinal != TACKeyword.STACK_HEIGHT.ordinal) {
                        continue
                    }
                    // is there an alias that exists at the entrance to the function? If so, use that
                    val aliases = gvn.findCopiesAt(function.start.ptr, aliasSource to v)
                    if(aliases.isEmpty()) {
                        return "Variable $v was written within $functionId but could not find a new value for it outside the function".toRight()
                    }
                    val alias = aliases.first()
                    suffix.extend(alias)
                    suffix.extend(v)
                    suffix.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = v, rhs = alias.asSym()))
                }
                val completePayload = InternalFunctionExitData(
                    exitPointType = exitPoint,
                    exitInfo = recomposeReturnInfo(exits, exitRepShape),
                    suffixCode = suffix.toCommandWithRequiredDecls()
                )
                return completePayload.toLeft()
            }
        }.submit(listOf(function.start.ptr))
    }

    /**
     * Generic interface for internal function start information that doesn't hardcode argument types.
     */
    interface GenericInternalFunctionStartInfo<ARG_DATA> {
        val methodSignature: QualifiedMethodSignature
        val args: ARG_DATA
        val callSiteSrc: TACMetaInfo?
        val calleeSrc: TACMetaInfo?
    }

    /**
     * Called to handle an [explicit] [InternalCallSummary] command encountered in the [enclosingProgram] at
     * the location [where], for which [selectedSummary] was selected. The returned [SummaryApplicator]
     * is expected to apply the summary specified in [selectedSummary] to the provided patching program.
     */
    abstract protected fun handleExplicitSummary(
        where: CmdPointer,
        explicit: InternalCallSummary,
        selectedSummary: SummarySelection<K, S>,
        enclosingProgram: CoreTACProgram
    ): SummaryApplicator

    /**
     * Called to handle a function whose body has *not* yet been replaced. The function
     * starts at [functionStart] which argument information provided in [internalFunctionStartInfo],
     * and whose return variables and exit location are specified by [rets].
     *
     * NB that this function should *not* modify [intermediateCode], but rather return
     * the [CoreTACProgram] that represents the summary body, which is placed into [intermediateCode]
     * at the point of the summary during summary application.
     */
    abstract protected fun generateSummary(
        internalFunctionStartInfo: GenericInternalFunctionStartInfo<ARG_DATA>,
        selectedSummary: SummarySelection<K, S>,
        functionStart: CmdPointer,
        rets: RET_DATA,
        intermediateCode: CoreTACProgram
    ): CoreTACProgram

    /**
     * Given a call to an internal function with signature [sig], return the summary
     * selected to apply, or null if no summary applies.
     */
    abstract protected fun selectSummary(
        sig: QualifiedMethodSignature
    ) : SummarySelection<K, S>?

    /**
     * A hook used to indicate that a summary selection should be skipped for handling.
     */
    protected abstract fun alreadyHandled(
        summarySelection: SummarySelection<K, S>,
        where: LTACCmd
    ) : Boolean
}
