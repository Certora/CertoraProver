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

package instrumentation.transformers

import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.icfg.CallGraphBuilder
import analysis.icfg.Summarizer
import analysis.icfg.Summarizer.resolveCandidates
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalArgSort
import analysis.maybeAnnotation
import analysis.maybeNarrow
import analysis.pta.ABICodeFinder
import analysis.pta.abi.ABIAnnotator.Companion.REGION_END
import analysis.pta.abi.ABIAnnotator.Companion.REGION_START
import analysis.pta.abi.ABIEncodeComplete
import analysis.pta.abi.EncodeOutput
import analysis.pta.abi.SerializationRangeMarker
import analysis.snarrowOrNull
import analysis.worklist.SimpleWorklist
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import config.Config
import config.ReportTypes
import log.*
import scene.IScene
import spec.CVL
import spec.CVLInvocationCompiler
import spec.cvlast.*
import tac.NBId
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import verifier.ChainedMethodTransformers
import verifier.ContractUtils
import verifier.CoreToCoreTransformer
import java.util.stream.Collectors

/**
 * Collection of related ABI optimizations (executed by [run]) and the [gcABI] utility function.
 *
 * In particular, we remove calldata serialization which is provably never read by inlining or summarization,
 * and remove (in-memory) ABI encodes that are likewise provably never read. See [run] for the exact steps
 */
object ABIOptimizations {
    /**
     * Aggressively optimizes the ABI usage of the scene
     * 1. remove opcode summaries that observe abi buffers
     * 2. Remove references to "dead" calldata (see [rewriteCalldataInput])
     * 3. Remove dead encodes (to calldata or memory)
     */
    fun run(scene: IScene, cvlQuery: CVL?) {
        // only run the opcode removal if there are no hooks that need these opcodes
        if(cvlQuery?.hooks?.any { hook ->
                hook.pattern is CVLHookPattern.Opcode && (hook.pattern is CVLHookPattern.LogHookPattern || hook.pattern is CVLHookPattern.CallHookPattern)
            } != true) {
            scene.mapContractMethods("remove_call_summaries") { _, m ->
                val chain = ChainedMethodTransformers(
                    listOf(
                        CoreToCoreTransformer(ReportTypes.REMOVE_OPCODE_SUMMARIES) { c ->
                            /**
                             * Remove all Opcode summaries that observe the input buffers. If we did not remove them, then the
                             * result of the encoding process will be artifically kept "alive" by these summaries. Instead of
                             * trying to create some "dummy" or "partial" version, we simply remove the summaries.
                             */
                            c.parallelLtacStream().filter {
                                it.cmd is TACCmd.Simple.SummaryCmd && (it.cmd.summ is TACCmd.EVM.CallOpcodeSummary || it.cmd.summ is TACCmd.EVM.LogCmdSummary)
                            }.patchForEach(c, check = true) {
                                replaceCommand(it.ptr, listOf(TACCmd.Simple.NopCmd))
                            }
                        }.lift()
                    )
                )
                ContractUtils.transformMethod(m, chain).code
            }
        }
        if(cvlQuery != null) {
            scene.mapContractMethods("remove_unused_encodes") { _, m ->
                val encodesToGC = mutableSetOf<Int>()
                val chain = ChainedMethodTransformers(listOf(
                    CoreToCoreTransformer(ReportTypes.ABI_INPUT_MUNGING) { c ->
                        // munges away the inputs to operations which don't use the in-memory buffers
                        // if these inputs have an encode associated with them
                        // it is recorded in encodeToGC to remove in the next step
                        rewriteCalldataInput(scene, c, encodesToGC, cvlQuery)
                    }.lift(),
                    CoreToCoreTransformer(ReportTypes.ABI_ENCODE_REMOVAL) { c ->
                        // remove the encodes that we found in the previous step
                        gcEncodes(c, encodesToGC)
                    }.lift(),
                    CoreToCoreTransformer(ReportTypes.ABI_REMOVE_DEAD_ENCODES) { c ->
                        // Now find any other encodes that are dead, and iteratively remove those
                        removeUnusedEncodes(c)
                    }.lift()
                ))
                ContractUtils.transformMethod(m, chain).code
            }
        }
    }

    /**
     * Finds all (in memory) encodes that are never used, and then remove this serialization code.
     * This process is iterative, as the result of an encode can itself be re-encoded into some other
     * in-memory buffer.
     *
     * We expect this to most useful when identifying an in-memory buffer that is directly used as the input to a
     * call command. Ideally we'd find instances where such an encoded buffer is first copied to scratch.
     */
    private fun removeUnusedEncodes(c: CoreTACProgram): CoreTACProgram {
        fun findUnusedEncodes(progIt: CoreTACProgram) : Set<Int> {
            val live = progIt.analysisCache.lva
            val use = progIt.analysisCache.use
            val graph = progIt.analysisCache.graph
            return progIt.parallelLtacStream().mapNotNull {
                it `to?` it.maybeAnnotation(ABIEncodeComplete.META_KEY)?.takeIf {
                    it.target is EncodeOutput.Alloc
                }
            }.filter { (where, out) ->
                val targetVars = (out.target as EncodeOutput.Alloc).data.filter {
                    live.isLiveAfter(where.ptr, it)
                }
                /*
                 If none of the variables containing the output of the allocation are live, remove the encode
                 */
                if(targetVars.isEmpty()) {
                    return@filter true
                }
                /*
                 * Otherwise, it's possible that the result of the encode is used in some other assignment to a variable that
                 * is itself not used. See if this is the case iteratively.
                 */
                object : VisitingWorklistIteration<Pair<CmdPointer, TACSymbol.Var>, Unit, Boolean>() {
                    override fun process(it: Pair<CmdPointer, TACSymbol.Var>): StepResult<Pair<CmdPointer, TACSymbol.Var>, Unit, Boolean> {
                        val uses = use.useSitesAfter(it.second, it.first)
                        if(uses.isEmpty()) {
                            return cont(listOf())
                        }
                        val toCont = mutableListOf<Pair<CmdPointer, TACSymbol.Var>>()
                        for(u in uses) {
                            val useSite = graph.elab(u)
                            /*
                             * If this is a non-trivial use, then conservatively assume this encode is used.
                             */
                            if(useSite.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || useSite.cmd.rhs !is TACExpr.Sym.Var) {
                                return halt(false)
                            }
                            /*
                             * Otherwise, see if the variable now being used is likewise unused.
                             */
                            toCont.add(u to useSite.cmd.lhs)
                        }
                        return cont(toCont)
                    }

                    /*
                     * getting here indicates that we exhausted the worklist without finding a non-trivial use,
                     * so we return true, indicating the encode is *not* used.
                     */
                    override fun reduce(results: List<Unit>): Boolean {
                        return true
                    }

                }.submit(targetVars.map {
                    where.ptr to it
                })
            }.map { (_, it) ->
                it.id
            }.collect(Collectors.toSet())
        }
        var progIt = c
        while(true) {
            val toChange = findUnusedEncodes(progIt)
            if(toChange.isEmpty()) {
                return progIt
            }
            /*
             * Garbage collect the encodes, and rerun.
             */
            progIt = gcEncodes(progIt, toChange)
        }
    }

    // XXX(jtoman): copy pasted from inliner
    private fun splitStart(patch: PatchingTACProgram<TACCmd.Simple>, where: CmdPointer) : NBId {
        /*
          Split block at leaves the command passed to it *outside* of the block that was generated. But this
          is the first command we want to remove, so just turn it into a nop, it'll be removed later.
         */
        val newBlock = patch.splitBlockAfter(where)
        patch.replaceCommand(where, listOf(TACCmd.Simple.NopCmd))
        return newBlock
    }

    private fun splitEnd(patch: PatchingTACProgram<TACCmd.Simple>, where: CmdPointer) : NBId {
        return patch.splitBlockAfter(where)
    }


    private fun removeABIRegion(
        start: CmdPointer,
        end: CmdPointer,
        patch: SimplePatchingProgram
    ) {
        val decodeSucc = splitEnd(patch, end)
        val decodeStart = splitStart(patch, start)
        patch.consolidateEdges(decodeSucc, listOf(decodeStart))
    }

    private fun setRegionSource(
        start: CmdPointer,
        end: CmdPointer,
        regionId: Int,
        p: SimplePatchingProgram,
        sources: Set<ABICodeFinder.Source>
    ) {
        p.replace(start) { c ->
            listOf(
                TACCmd.Simple.AnnotationCmd(REGION_START, SerializationRangeMarker(id = regionId, sources = sources), meta = c.meta)
            )
        }
        p.replace(end) { c ->
            listOf(
                TACCmd.Simple.AnnotationCmd(REGION_END, SerializationRangeMarker(id = regionId, sources = sources), meta = c.meta)
            )
        }
    }

    /**
     * Find all ABI ranges that include an [ABICodeFinder.Source] in [sourcesToRemove], and either:
     * 1. Removes the references to those sources, if the range mentions other sources, or
     * 2. completely removes the range if no other sources are part of that range.
     *
     * The decision to remove/rewrite is made on a per-range basis.
     */
    fun gcABI(
        c: CoreTACProgram,
        sourcesToRemove: Set<ABICodeFinder.Source>
    ) : CoreTACProgram {
        return c.parallelLtacStream().mapNotNull {
            it `to?` it.maybeAnnotation(REGION_START)
        }.filter { (_, range) ->
            range.sources.containsAny(sourcesToRemove)
        }.map { (start, r) ->
            val end = c.ltacStream().filter {
                it.maybeAnnotation(REGION_END)?.id == r.id
            }.findFirst().get().ptr
            if(sourcesToRemove.containsAll(r.sources)) {
                // remove this one
                { p: SimplePatchingProgram ->
                    removeABIRegion(start.ptr, end, patch = p)
                }
            } else {
                { p: SimplePatchingProgram ->
                    setRegionSource(start.ptr, end, regionId = r.id, p, sources = r.sources - sourcesToRemove)
                }
            }
        }.patchForEach(c, true) { thunk ->
            thunk(this)
        }
    }

    private fun gcEncodes(
        c: CoreTACProgram,
        encodesToGC: Set<Int>
     ) : CoreTACProgram {
        val toRemoveSource = encodesToGC.mapToTreapSet {
            ABICodeFinder.Source.Encode(it)
        }
        return gcABI(c, toRemoveSource)
    }

    /**
     * Common interface to describe the use of an ABI buffer
     */
    private interface ABIUser {
        /**
         * Whether the input to this operation should be removed/munged away
         */
        fun shouldRemove(): Boolean

        /**
         * Associated encode id used for this operation
         */
        fun encodeId(): Int?

        /**
         * Symbol used as the offset (in memory) of the input buffer
         */
        fun inputOffsetVar(): TACSymbol.Var?

        /**
         * The symbol used as the length of the input buffer
         */
        fun inputLengthVar(): TACSymbol.Var?
    }

    @Suppress("UnusedPrivateClass")
    private class Log(
        val log: TACCmd.Simple.LogCmd
    ) : ABIUser {
        override fun shouldRemove(): Boolean {
            return this.log.meta.find(TACMeta.LOG_ENCODING_ID) != null
        }

        override fun encodeId(): Int {
            return this.log.meta.find(TACMeta.LOG_ENCODING_ID)!!
        }

        override fun inputOffsetVar(): TACSymbol.Var? {
            return this.log.sourceOffset as? TACSymbol.Var
        }

        override fun inputLengthVar(): TACSymbol.Var? {
            return this.log.length as? TACSymbol.Var
        }
    }

    private class ExternalCallSummary(val summ: CallSummary, val cvlQuery: CVL, val scene: IScene) : ABIUser {
        override fun shouldRemove(): Boolean {
            if(summ.sigResolution.size != 1) {
                return false
            }
            /**
             * If this call has a no arg summary, or there exists *no* possible inlining candidate in the scene, then
             * we know we won't inline something later that needs the input; we can thus remove the encoding and munge the
             * inputs to the call oepration.
             */
            val sigResolution = summ.sigResolution.singleOrNull() ?: return false
            val cannotBeInlined = (Summarizer.getExplicitSummariesForInvocation(
                summaries = cvlQuery.external,
                sigHash = sigResolution,
                hostContractId = summ.callTarget.map { (it as? CallGraphBuilder.CalledContract.FullyResolved)?.contractId }.singleOrNull(),
                nameLookup = {
                    scene.getContractOrNull(it)?.name
                },
                onExactSummaryMiss = {

                }
            ).resolveCandidates()?.specCallSumm?.isNoArgSummary() == true) || scene.getMethodImplementors(sigResolution).isEmpty()
            return cannotBeInlined
        }

        override fun encodeId(): Int? {
            return summ.callConvention.input.encodedArguments?.encodeId
        }

        override fun inputOffsetVar(): TACSymbol.Var? {
            return summ.inOffset as? TACSymbol.Var
        }

        override fun inputLengthVar(): TACSymbol.Var? {
            return summ.inSize as? TACSymbol.Var
        }
    }

    /**
     * Find instances of encoding (to either Log or CallCore) where the result of the encoding is definitely
     * never used. In the case of Log, this is always the case (as we ensure discarding the opcode hooks). For calls, we need
     * to ensure that the input buffer is never read. This is true if the call is summarized with a summary that doesn't use the
     * arguments (e.g., a NONDET summary, or a function summary which ignores all input arguments; this is determined by [spec.cvlast.SpecCallSummary.isNoArgSummary])
     * or if there is a definite sighash which definitely doesn't exist in the scene. In the latter case, no implementation
     * can be inlined due to dispatch lists, and the lack of a summary means that we will definitely be auto-summarizing the
     * external call.
     *
     * For such encodes, we "munge" the input offset/lengths to read arbitrary memory and no longer reference the encoded buffer;
     * This frees up the encoding for garbage collection, which we record in [encodesToGC]. NB that we don't actually remove
     * the encodes, this pass simply mutates the original log commands/call summaries replace the input offset/length
     * with a havoced variable and, if the offset/length are only used in the log/call, remove those definitions.
     * With this removal, the original encoding for the log/call is now complete unobservable, and makes it possible to garbage
     * collect it later (see [gcEncodes]).
     */
    private fun rewriteCalldataInput(
        scene: IScene,
        c: CoreTACProgram,
        encodesToGC: MutableSet<Int>,
        cvlQuery: CVL
    ) : CoreTACProgram {

        val def = c.analysisCache.def
        val use = c.analysisCache.use
        return c.parallelLtacStream().mapNotNull {
            it `to?` (
                it.cmd.snarrowOrNull<CallSummary>()?.let {
                    ExternalCallSummary(summ = it, scene = scene, cvlQuery = cvlQuery)
                } ?: it.maybeNarrow<TACCmd.Simple.LogCmd>()?.cmd?.let(ABIOptimizations::Log)
            )
        }.mapNotNull map@{ (where, summ) ->
            if(!summ.shouldRemove()) {
                return@map null
            }

            val toSlice = mutableListOf<CmdPointer>()
            val inOffset = summ.inputOffsetVar()
            /*
             In what follows, if the other references to the input offset and size
             symbols are in this summary, remove their definition sites (see below)
             */
            inOffset?.let { iOffs ->
                val d = def.defSitesOf(iOffs, where.ptr).singleOrNull() ?: return@let
                if(use.useSitesAfter(iOffs, d) == setOf(where.ptr)) {
                    toSlice.add(d)
                }
            }
            val inSize = summ.inputLengthVar()
            inSize?.let { iSz ->
                val d = def.defSitesOf(iSz, where.ptr).singleOrNull() ?: return@let
                if(use.useSitesAfter(iSz, d) == setOf(where.ptr)) {
                    toSlice.add(d)
                }
            }
            Triple(where, summ, toSlice)
        }.patchForEach(c, true) { (where, summ, toRemove) ->
            val toInsertBefore = mutableListOf<TACCmd.Simple>()
            /*
               Replace the input offset/size symbols with havoc assignments. These values are provably dead,
               but the interface requires we have something here.
             */
            summ.inputLengthVar()?.let {
                toInsertBefore.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(it))
            }
            summ.inputOffsetVar()?.let {
                toInsertBefore.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(it))
            }
            /*
               If there was an encoding ID associated with this use, mark this encode for removal later
             */
            summ.encodeId()?.let(encodesToGC::add)
            addBefore(where.ptr, toInsertBefore)
            for(t in toRemove) {
                replaceCommand(t, listOf(TACCmd.Simple.NopCmd))
            }
        }
    }

    /**
     * Removes internal function annotations that reference calldata. The references in these annotations artificially
     * show up as an "unknown" use of calldata pointers, preventing the full removal of calldata operations.
     *
     * This is something of a brutal solution, favored for expediency over, e.g., teaching the ABI slicer about
     * these annotations. Further, it's not clear how to rewrite these annotations if we are elliding calldata operations.
     */
    fun removeInternalFunctionParams(c: CoreTACProgram): CoreTACProgram {
        val toRemove = c.parallelLtacStream().mapNotNull {
            it `to?` it.maybeAnnotation(INTERNAL_FUNC_START)
        }.filter { (_, start) ->
            start.args.any {
                it.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS || it.sort == InternalArgSort.CALLDATA_POINTER || it.sort == InternalArgSort.CALLDATA_ARRAY_LENGTH
            }
        }.toList()
        val idsToRemove = toRemove.mapToSet {
            it.second.id
        }
        val exitsToRemove = c.parallelLtacStream().filter {
            it.cmd.maybeAnnotation(INTERNAL_FUNC_EXIT)?.id?.let { id ->
                id in idsToRemove
            } == true
        }.toList()
        return c.patching { p ->
            for((w, _) in toRemove) {
                p.replaceCommand(w.ptr, listOf(TACCmd.Simple.NopCmd))
            }
            exitsToRemove.forEach { lc ->
                p.replaceCommand(lc.ptr, listOf(TACCmd.Simple.NopCmd))
            }
        }
    }

    /**
     * Holder class containing information about encoding performed within CVL. the [startLoc] is the pointer
     * of the [spec.CVLInvocationCompiler.StartSerializationMarker], and [endLoc] is similarly defined for the
     * [spec.CVLInvocationCompiler.EndSerializationMarker].
     *
     * [encodingPtrs] holds all pointers between these cmdpointers, i.e. the command pointers of the encoding process.
     */
    private data class CVLSerializationInfo(
        val startLoc: CmdPointer,
        val endLoc: CmdPointer,
        val encodingPtrs: TreapSet<CmdPointer>
    )

    /**
     * If the only references to calldata for a call from CVL to EVM are in the argument encoding process, then the
     * calldata encoding performed by the CVL -> EVM call is "dead" and can be completely removed. Put another way,
     * we remove the CVL->EVM encoding if calldata isn't even used on the EVM side.
     */
    fun garbageCollectCVLEncoding(code: CoreTACProgram): CoreTACProgram {
        if(!Config.EnableAggressiveABIOptimization.get()) {
            return code
        }
        val graph = code.analysisCache.graph
        /**
         *  Find all CVL encoding code, i.e. those [CmdPointer]s that lie between matching [spec.CVLInvocationCompiler.StartSerializationMarker] and
         *  [spec.CVLInvocationCompiler.EndSerializationMarker] commands.
         */
        val knownCVLCode = code.parallelLtacStream().mapNotNull {
            it `to?` it.maybeAnnotation(CVLInvocationCompiler.StartSerializationMarker.META_KEY)
        }.mapNotNull { (serializationStart, annot) ->
            val seen = treapSetBuilderOf<CmdPointer>()
            lateinit var end : CmdPointer
            SimpleWorklist.iterateWorklist(listOf(serializationStart.ptr)) { curr, nxt ->
                if(!seen.add(curr)) {
                    return@iterateWorklist
                }
                if(graph.elab(curr).maybeAnnotation(CVLInvocationCompiler.EndSerializationMarker.META_KEY)?.id == annot.id) {
                    end = curr
                    return@iterateWorklist
                }
                nxt.addAll(graph.succ(curr))
            }
            annot.callId to CVLSerializationInfo(
                startLoc = serializationStart.ptr,
                endLoc = end,
                encodingPtrs = seen.build()
            )

        }.collect(Collectors.toMap({ it.first }, {it.second }))

        /**
         * See if we can find a reference to calldata in a callee from CVL that isn't in the encoding code.
         * If so, then calldata is indeed used by callee, and cannot be removed.
         */
        val usedCalldata = code.parallelLtacStream().filter {
            knownCVLCode[it.ptr.block.calleeIdx]?.encodingPtrs?.contains(it.ptr) == false
        }.filter {
            it.cmd.getFreeVarsOfRhs().any { v ->
                TACMeta.IS_CALLDATA in v.meta && v.callIndex == it.ptr.block.calleeIdx
            }
        }.map {
            it.ptr.block.calleeIdx
        }.collect(Collectors.toSet())
        val p = code.toPatchingProgram()
        for((i, r) in knownCVLCode) {
            if(i in usedCalldata) {
                continue
            }
            code.procedures.find {
                it.callId == i
            }?.let {
                it.procedureId as? ProcedureId.Standard
            }?.let {
                Logger.regression {
                    "Completely removed calldata references in ${it.contract}.${it.procedureName}"
                }
            }
            /*
             * We're here if the calldata encoding is dead: remove it
             */
            val start = splitStart(p, r.startLoc)
            val end = splitEnd(p, r.endLoc)
            p.consolidateEdges(end, listOf(start))
        }
        return p.toCode(code)
    }
}
