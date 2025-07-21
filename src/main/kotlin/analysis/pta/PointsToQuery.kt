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

package analysis.pta

import algorithms.dominates
import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.*
import analysis.alloc.AllocationAnalysis
import analysis.loop.LoopInterpolation
import analysis.numeric.*
import analysis.numeric.linear.TermMatching
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.TermMatching.matchesAny
import analysis.numeric.linear.implies
import analysis.pta.RevertAnalysis.RevertReason
import analysis.pta.abi.*
import analysis.worklist.SimpleWorklist
import com.certora.collect.*
import datastructures.stdcollections.*
import decompiler.BLOCK_SOURCE_INFO
import evm.ABI_SIZE_BOUND
import evm.EVM_WORD_SIZE
import evm.MAX_EVM_INT256
import evm.MAX_EVM_UINT256
import instrumentation.transformers.DSA_BLOCK_END
import instrumentation.transformers.DSA_BLOCK_START
import log.Logger
import log.LoggerTypes
import tac.MetaKey
import tac.NBId
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.POINTS_TO)

sealed class PointsToQuery<out T> {
    abstract fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph) : T
}

class GetterHashValid(val where: CmdPointer) : PointsToQuery<Boolean>() {
    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): Boolean {
        return where.block !in pta.invalidSummaries
    }
}

class IsProbablyNonZero(val where: CmdPointer, val sym: TACSymbol) : PointsToQuery<Boolean>() {
    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): Boolean {
        return when(sym) {
            is TACSymbol.Const -> sym.value > BigInteger.ZERO
            is TACSymbol.Var -> pta.results[where]?.let {
                it.boundsAnalysis[sym]?.let {
                    it.tryResolveAsInt() as? QualifiedInt
                }?.x?.isDefinitelyGreaterThan(BigInteger.ZERO) == true || it.invariants matchesAny {
                    sym `=` v("other") + k("_") {
                        it > BigInteger.ZERO
                    }
                } != null
            } == true
        }
    }
}

class CopyLoopValid(
    private val v: LoopCopyAnalysis.LoopCopySummary,
    private val where: CmdPointer
) : PointsToQuery<CopyLoopValid.CopyLoopNodes?>() {
    sealed interface CopySource {
        object Other : CopySource
        object EmptyArray : CopySource
        data class Fields(val field: IndexedWritableSet) : CopySource
    }

    data class CopyLoopNodes(
        val srcNodes: CopySource,
        val destNodes: IndexedWritableSet
    )

    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): CopyLoopNodes? {
        if(where.block in pta.invalidSummaries) {
            return null
        }
        return pta.results[where]?.let { st ->
            val pm = pta.validateSummary(st, v) as? PointsToAnalysis.LoopSummaryClassification.Valid ?: return@let null
            CopyLoopNodes(
                destNodes = pts.fieldNodesAt(where, pm.output).let {
                    it as? IndexedWritableSet
                } ?: return@let null,
                srcNodes = when(pm.input) {
                    is PointsToAnalysis.PointerType.ElementPointer -> {
                        if(st.pointsToState.store[pm.input.v]?.let {
                                it is Pointer.ArrayElemStart && it.v.isEmpty()
                            } == true || v.lenVars.any {
                                st.boundsAnalysis[it]?.expectInt()?.x?.mustEqual(BigInteger.ZERO) == true
                            }) {
                            CopySource.EmptyArray
                        } else {
                            (pts.fieldNodesAt(where, pm.input.v) as? IndexedWritableSet)?.let(CopySource::Fields) ?: return@let null
                        }
                    }
                    PointsToAnalysis.PointerType.Other -> {
                        CopySource.Other
                    }

                    PointsToAnalysis.PointerType.EmptyArray -> CopySource.EmptyArray
                }
            )
        }
    }
}

class NumericApproximation(private val v: CmdPointer, private val x: TACSymbol) : PointsToQuery<UIntApprox<*>?>() {
    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): UIntApprox<*>? {
        if(x !is TACSymbol.Var) {
            check(x is TACSymbol.Const) {
                "broken symbol hierarchy"
            }
            return IntValue.Constant(x.value)
        }
        return pta.results[v]?.boundsAnalysis?.get(x)?.let { it.tryResolve() as? QualifiedInt }?.x
    }
}

class ConstantValue(private val v: CmdPointer, private val x: TACSymbol) : PointsToQuery<BigInteger?>() {
    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): BigInteger? {
        if(x !is TACSymbol.Var) {
            check(x is TACSymbol.Const) {
                "broken symbol hierarchy"
            }
            return x.value
        }
        return pta.results[v]?.boundsAnalysis?.get(x)?.let {
            it.tryResolve() as? QualifiedInt
        }?.x?.takeIf { it.isConstant }?.c
    }
}

class QueryInvariants(private val v: CmdPointer, private val builder: TermMatching.Builder.() -> TermMatching.Builder.Term) : PointsToQuery<Collection<TermMatching.Match>?>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Collection<TermMatching.Match>? {
        return pta.results[v]?.invariants?.let { inv ->
            inv matches builder
        }
    }

}

/**
 * Find allocations that are always part of the allocation of a parent object, i.e., objects
 * allocated with abstract address L unconditionally flow into a field of an initializating
 * object at address L'
 */
object NestedAllocations : PointsToQuery<Collection<NestedAllocations.NestedAllocationPop>>() {
    /**
     * When we finish allocating [poppedAddress], the result of the allocation
     * is immediately written into the object initializing with address [targetAddress]
     */
    data class NestedAllocationPop(
        val popLocation: CmdPointer,
        val poppedAddress: AllocationAnalysis.AbstractLocation,
        val initWrite: CmdPointer,
        val targetAddress: AllocationAnalysis.AbstractLocation
    )

    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Collection<NestedAllocationPop> {
        return graph.commands.filter {
            /*
               Find all pop allocations
             */
            it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.check(POP_ALLOCATION) { _ -> true }
        }.mapNotNull outer@{
            /*
              This is more of a sanity check: if we are about to pop an allocation and there is nothing on the allocation
              stack, something has (likely) gone terribly wrong.
             */
            val statAt = pta.results[it.ptr]?.takeIf {
                it.pointsToState.h.allocStack.isNotEmpty()
            } ?: return@outer null
            /*
             What is the address of the object that is done allocating
             */
            val stackTop = statAt.pointsToState.h.allocStack.last()
            val lva = graph.cache.lva.liveVariablesAfter(it.ptr)
            val uniqueLive = statAt.pointsToState.store.filter { (_, v) ->
                v is InitializationPointer && v.initAddr == stackTop
            }.entries.singleOrNull { (k, _) ->
                // there should only be one variable still live (because we are going to write it into an initializing object)
                k in lva
            }?.takeIf { (_, v) ->
                // and this single live object should be a "base" pointer
                (v is InitializationPointer.BlockInitPointer && v.offset == BigInteger.ZERO) ||
                        (v is InitializationPointer.LengthFieldInit && !v.mayAdded)
            }?.key ?: return@outer null
            // this single base pointer should be written immediately into an initialization write
            val use = graph.cache.use.useSitesAfter(uniqueLive, it.ptr)
                .singleOrNull()?.let(graph::elab)
                ?.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()
                ?.takeIf {
                    it.cmd.base == TACKeyword.MEMORY.toVar() && it.cmd.loc is TACSymbol.Var && it.cmd.value == uniqueLive
                } ?: return@outer null
            /*
              Get the address that we are writing into (if we aren't writing into an initialization pointer, then we are
              out
             */
            val base = pta.results[use.ptr]?.pointsToState?.store?.get(use.cmd.loc as TACSymbol.Var)?.let {
                it as? InitializationPointer
            } ?: return@outer null
            NestedAllocationPop(
                popLocation = it.ptr,
                poppedAddress = stackTop,
                initWrite = use.ptr,
                targetAddress = base.initAddr
            )
        }.toList()
    }
}

/**
 * Find all writes involved in the initializaation of the addresses in [addr]
 */
class InitializationWrites(private val addr: Collection<AllocationAnalysis.AbstractLocation>) : PointsToQuery<Collection<LTACCmd>>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Collection<LTACCmd> {
        /**
        Is the write to the location to [key] at point [it] in the program a write to one of the addresses in [addr]?
         */
        fun isInitTarget(it: LTACCmdView<*>, key: TACSymbol): Boolean {
            /* easy case, key is a variable obviously tracked as an initialization pointer */
            return pta.results[it.ptr]?.pointsToState?.store?.get(key)?.let {
                it as? InitializationPointer
            }?.initAddr?.let { it in addr } == true || /* special case: we are writing 0 to the end of the array segment (
            the identity of which is tracked by a qualified int) */ pta.results[it.ptr]?.boundsAnalysis?.get(key)?.let {
                it.tryResolve() as? QualifiedInt
            }?.qual?.any { q ->
                q is IntQualifier.EndOfArraySegment && pta.results[it.ptr]?.pointsToState?.store?.get(q.arrayVar)?.let {
                    it as? InitializationPointer
                }?.initAddr?.let { it in addr } == true
            } == true
        }
        /*
         First, find all byte stores that are 1) targeting memory, and 2) whose location is an initializing address
         */
        val toRet = graph.commands.filterTo(mutableSetOf()) {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf {
                it.cmd.base == TACKeyword.MEMORY.toVar()
            }?.let {
                val key = it.cmd.loc
                isInitTarget(it, key)
            } == true
        }
        /*
         * Next find bytelongcopies that target the initailizing object. Include the longcopy into the mcopy buffer if necessary
         */
        for (longC in graph.commands.mapNotNull { it.maybeNarrow<TACCmd.Simple.ByteLongCopy>() }) {
            if(longC.cmd.dstBase != TACKeyword.MEMORY.toVar()) {
                continue
            }
            if(isInitTarget(longC, longC.cmd.dstOffset)) {
                toRet.add(longC.wrapped)
                if(TACMeta.MCOPY_BUFFER in longC.cmd.srcBase.meta) {
                    graph.commands.filterTo(toRet) {
                        it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.cmd?.dstBase == longC.cmd.srcBase
                    }
                }
            }
        }
        /*
           Now the kinda gross part: find all copy loops where the output pointer is one of the initializing addresses
           (again according to the logic above)
         */
        graph.commands.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.SummaryCmd>()?.takeIf {
                val cmd = it.cmd
                cmd.summ is LoopCopyAnalysis.LoopCopySummary && (pts.query(
                    CopyLoopValid(
                        where = it.ptr,
                        v = cmd.summ
                    )
                ) != null) && cmd.summ.outPtr.any { v ->
                    isInitTarget(it, v)
                }
            }
        }.filter {
            (it.cmd.summ as LoopCopyAnalysis.LoopCopySummary).summarizedBlocks.none {
                graph.elab(it).commands.any {
                    it.cmd.getFreeVarsOfRhs().contains(TACKeyword.MEM64.toVar())
                }
            }
        }.forEach {
            /*
             * Now just grab every write in the blocks summarized by the loop summary, and say it is an initialization write
             * Q) is this sound?
             * A) prooooooobably? The loop copy analysis only gives output on size 1 arrays, which means that copies over
             * complex arrays won't do anything. Further, the non-aliasing constraints generated by the loop *should*
             * cause the summarization to fail unless any other writes involves tacM0x40, but we rule out those
             * cases (rather bluntly) above
             */
            val where = it.cmd.summ as LoopCopyAnalysis.LoopCopySummary
            where.summarizedBlocks.flatMapTo(toRet) {
                graph.elab(it).commands.filter {
                    it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                }
            }
        }

        graph.commands.filter {
            it.snarrowOrNull<InitAnnotation.FourByteWriteSummary>()?.let { summ ->
                isInitTarget(it.narrow<TACCmd.Simple.SummaryCmd>(), summ.base)
            } == true
        }.forEach {
            toRet.add(it)
            it.snarrowOrNull<InitAnnotation.FourByteWriteSummary>()!!.summarized.let(graph::elab).commands.filterTo(toRet) {
                it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
            }
        }

        return toRet
    }
}

/**
 * Common code for analyzing revert conditions
 */
private interface RevertConditionalAnalysis {
    companion object {
        /**
         * Why "reduced"? In general the decoder and int domain don't shared information bidirectionally: the
         * qualified int domain doesn't try to become more precise based off of the information
         * in the decoder analysis. However, if we know something is an offset into a calldata array (via
         * the decoder analysis) or an addition to one such value, then we can provide an upper bound that is
         * the max evm signed int.
         *
         * Why do we know that this upper bound is valid? I.... forget TODO(jtoman): remember
         */
        private fun PointsToDomain.extractReducedInt(q: TACSymbol.Var) : QualifiedInt? {
            /*
               (Lazily) see if q is equal to a value that is a known index into the calldata buffer, or
               if it is equal to 31 plus such a variable. Why + 31? because we need to account for the f---ing annoying
               pattern of checking whether there is at least one word remaining for a read at q by checking if
               q + 31 < calldatasize
             */
            val isDecodeOffset by lazy {
                this.decoderState.entries.any { (k, v) ->
                    v is DecoderAnalysis.AbstractDomain.BufferIndices && v.bufferVar == TACKeyword.CALLDATA.toVar() &&
                            (k == q || this.invariants implies {
                                !q `=` !k + 31
                            })
                }
            }
            return this.boundsAnalysis[q]?.let {
                it.tryResolve() as? QualifiedInt
            }?.let {
                /*
                  Refine the upper bound if we are a decode offset as defined above
                 */
                if(it.x.getUpperBound() > MAX_EVM_INT256 && isDecodeOffset) {
                    it.copy(
                        x = it.x.updateBounds(ub = MAX_EVM_INT256, lb = null)
                    )
                } else {
                    it
                }
            }
        }

        protected val pathConditionComputation =
            object : QualifierPropagationComputation<QualifiedInt, PointsToAnalysis, Nothing?, IntQualifier>() {

                override fun extractValue(
                    op1: TACSymbol.Var,
                    s: PointsToAnalysis,
                    w: Nothing?,
                    l: LTACCmd
                ): QualifiedInt? {
                    return s.results[l.ptr]?.extractReducedInt(op1)
                }
            }
    }

    /**
     * If [b] is a block that conditional goes to a revert blcok and a non-revert block, get the path conditions
     * when the code doesn't revert, i.e., the negation of the condition that would case the code to revert
     */
    fun getNonRevertCondition(b: NBId, graph: TACCommandGraph, pta: PointsToAnalysis) : Pair<NBId, Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>>? {
        val (revert, noRevert) = graph.pathConditionsOf(b).entries.partition {
            it.key in graph.cache.revertBlocks
        }
        /*
          Not a revert + non-revert branch
         */
        if(revert.size != 1 || noRevert.size != 1) {
            return null
        }
        /**
         * `p` is the pathcondition, i.e., the condition that must hold when going to the non-reverting block (`tgt`)
         * the path condition is just v = 0 or v != 0, from that we extract more complicated path information (the
         * `PathInformation` values returned from this function
         */
        val (tgt, p) = noRevert.single()
        val where = graph.elab(b).commands.last()
        val st = pta.results[where.ptr] ?: return null
        fun TACCommandGraph.PathCondition.ConditionalOn.extractAV() = st.extractReducedInt(this.v)
        /**
         * The actual path information computation is entirely handled by the [pathConditionComputation]
         * field, see that class' documentation (it's not bad!)
         */
        return tgt `to?` when(p) {
            is TACCommandGraph.PathCondition.EqZero -> pathConditionComputation.propagateFalse(
                v = p.v,
                av = p.extractAV() ?: return null,
                l = where,
                s = pta,
                w = null
            )
            is TACCommandGraph.PathCondition.NonZero -> pathConditionComputation.propagateTrue(
                v = p.v,
                av = p.extractAV() ?: return null,
                l = where,
                s = pta,
                w = null
            )
            else -> return null /* should be impossible */
        }
    }
}

/**
 * Extract from the abstract domain the points where the decoder analysis has determined decoding is complete, and
 * the value being decoded and their source.
 */
object DecodePoints : PointsToQuery<Map<CmdPointer, ABIDecodeComplete>>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Map<CmdPointer, ABIDecodeComplete> {
        /*
           Find the points where allocations are popped
         */
        return graph.commands.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                it.cmd.check(POP_ALLOCATION) { true }
            }
        }.mapNotNull { annot ->
            /*
              "Step" the allocation via the decoder analysis, if the current state is consistent with a decode
              being complete, it will return a non-null result, which we record and return
             */
            annot.ptr `to?` pta.results[annot.ptr]?.let { res ->
                pta.decoderAnalysis.popAllocation(
                    s = res,
                    decoderState = res.decoderState
                ).second?.let {
                    val type = it.outputs.monadicMap {
                        pta.pointerAnalysis.getHeapType(
                            it, pState = res
                        )
                    }?.uniqueOrNull() ?: run {
                        logger.warn { "Non unique-or-null type at ${annot.ptr}"}
                        return@mapNotNull null
                    }

                    /*
                       BufferDecodeResult (the type of it) is very much an "internal" representation, so we
                       translate it into the friendlier, external representation of ABIDecodeComplete
                     */
                    ABIDecodeComplete(
                        output = it.outputs,
                        fieldPointers = it.fieldPointers,
                        sourcePath = it.sourcePath,
                        sourceBuffer = it.sourceBuffer,
                        decodedType = type,
                        id = Allocator.getFreshId(Allocator.Id.ABI),
                        fieldRelations = null
                    )
                }
            }
        }.toMap()
    }
}

/**
 * Similar to [DecodePoints], but find [ABIEncodeComplete] instances
 */
object EncodePoints : PointsToQuery<Map<CmdPointer, ABIEncodeComplete>>() {

    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Map<CmdPointer, ABIEncodeComplete> {
        /*
          The encoder analysis actually records the encode complete points (because they do *not* correspond to
          pop allocations nicely the way that the decodes do).

          However, these points could be recorded in one branch, but then invalidated at some point during the fixpoint
          computation, so we verify the results by recomputing the encoded buffer contents.
         */
        return pta.encoderAnalysis.encodeCompletePoints.mapNotNull { kv ->
            pta.results[kv.key]?.let { st ->
                val enc = st.encoderState.encoding ?: return@mapNotNull null
                enc.varRoots.takeIf {
                    it.isNotEmpty()
                }?.let {
                    /**
                     Like the [analysis.pta.abi.DecoderAnalysis.BufferDecodeResult], the [analysis.pta.abi.EncoderAnalysis.TopLevelWrite]
                     value is very internal and has a lot of "internal" analysis state that shouldn't be exposed. We
                     translate it into a nicer value via the [IEncoderAnalysis.toEncodedValue] method.
                     */
                    val topLevelValues = canonicalTopLevelValues(
                        it, pta, st
                    ) ?: run {
                        logger.debug { "Failed to compute top level values for encode $it at ${kv.key}"}
                        return@mapNotNull null
                    }

                    kv.key to ABIEncodeComplete(
                        buffer = EncodedBuffer(topLevelValues),
                        target = kv.value.encoded,
                        id = Allocator.getFreshId(Allocator.Id.ABI),
                        alignment = enc.rootAlignment ?: return@mapNotNull null // very odd
                    )
                }
            }
        }.toMap()
    }

    /**
     * Try to aggregate the individual top level writes into larger structures. This is achieved
     * by finding sequences of writes that are all rooted at the same location but are fields of
     * some larger structure -- effectively reconstructing this structure.
     */
    private fun canonicalTopLevelValues(
        roots: Map<BigInteger, EncoderAnalysis.TopLevelWrite>,
        pta: PointsToAnalysis,
        st: PointsToDomain
    ): Map<BigInteger, TopLevelValue>? {
        /**
         * For each TLW, pick the biggest possible structure the write could be a part of (according to the encoder analysis). Then try and
         * grab sequential TLWs to see if we complete the structure
         */
        val typedPaths = roots.mapValues {
            pta.encoderAnalysis.toEncodedValue(it.value, st).let { v ->
                (v as? TopLevelValue.Path)?.let { p ->
                    p.copy(ty = p.ty.recursiveResolution())
                } ?: v
            } to pta.encoderAnalysis.rootTypes(it.value, st)
        }.toSortedMap()

        val topLevelValues = mutableMapOf<BigInteger, TopLevelValue>()
        val remainingOffsets = typedPaths.keys.toMutableList()

        /*
            Our top level writes (i.e. the encoded object paths) may look like

            ...
            off_i |-> [ Field(f1, Field(f2, Root(v)), Field(f1, Root(v')), ... ]
            off_j |-> [ Field(f1', (..., Root(v')), ... ]
            ...

           We greedily start at the least offset in this map, let this be `o`.

           (1)
           Next, pick the 'largest' (first) object path (see [rootTypes]), and
           grab the largest sequence of remaining top level writes with an object path
           with the same root.

           (2)
           This will result in something like

           o |-> Field(fi, Root(x))
           o + sizeof(Field(fi, Root(x)) |-> Field(fj, Field(fk, Root(x)))
           o + sizeof(Field(fi, Root(x)) + ... |-> ...

           (3)
           Next, we check that all of the fields we have collected (i.e., o |-> _ mappings above)
           correspond to all of the fields required by the type of `x`. In other words, do all these
           writes correspond to an encoding of the fields of `x`? If so, remove all of these
           from the remaining offsets. If not, go back to (1) and try the next smallest object path.

           Note that in the worst case, each write is a singleton write of some primitive.
         */
        while (remainingOffsets.isNotEmpty()) {
            val currentOffset = remainingOffsets.first()
            val (tlvConst, rootTypePaths) = typedPaths[currentOffset]!!
            if (tlvConst is TopLevelValue.Constant) {
                topLevelValues[currentOffset] = tlvConst
                remainingOffsets.removeFirst()
                continue
            }

            if (rootTypePaths == null) {
                return null
            }

            /* search the paths in order, i.e. try smaller and smaller paths
               as described in (1) above */
            val (tlv, numUsed) = rootTypePaths.firstNotNullOfOrNull { (ty, path) ->
                if (path is ObjectPathGen.Root) {
                    // We're done, this write contains everything
                    // It's a bug if this returns null
                    val rootPath = typedPaths[currentOffset]?.let { (tlv, _) ->
                        tlv to 1
                    }
                    check(rootPath != null) {
                        "$currentOffset not in $typedPaths"
                    }
                    return@firstNotNullOfOrNull rootPath
                }

                val root = path.root
                // compute (2) as described above, i.e. pick sequential paths
                // that all have the same root
                val sequentialMatches = typedPaths.entries.filter {
                    currentOffset <= it.key
                }.map { (_, info) ->
                    info.first `to?` info.second?.singleOrNull {
                        it.second.root == root && it.second !is ObjectPathGen.Root
                    }?.second
                }.takeWhile {
                    it != null
                }.filterNotNull()

                /* This is the check at (3) described above */
                if (covers(ty, sequentialMatches.map { it.first.ty to it.second })) {
                    val tlv = TopLevelValue.Path(
                        setOf(ObjectPathGen.Root(root)), ty, null
                    )

                    tlv to sequentialMatches.size
                } else {
                    null
                }
            } ?: run {
                // This is most likely a bug, since the last (ty, path) should just be a root
                logger.debug {
                    "Failed to cover $typedPaths at $currentOffset. Current TLVs=$topLevelValues, remainingOffsets=$remainingOffsets"
                }
                return null
            }

            topLevelValues[currentOffset] = tlv

            repeat(numUsed) {
                remainingOffsets.removeFirst()
            }
        }

        return topLevelValues
    }


    private fun ObjectPath.fields(): List<BigInteger> {
        return when (this) {
            is ObjectPathGen.Field -> listOf(this.offset) + this.parent.fields()
            else -> listOf()
        }
    }

    /**
     * Remove the prefix of [remainingWrites] (if possible) such that this prefix
     * - writes to the offset path (see below) denoted by the sequence of offsets in [offsets]
     * - as a whole writes a value of type [expectedType]
     * [offsets] is a representation of the current "cursor" where we are writing [expectedType],
     * which is how we check that the writes of a given type are made to the correct location in the target
     * structure.
     *
     * Ex:
     *   if our outermost type is an OffsetMap(0: Int, 32: OffsetMap(0: Int, 32: Int)),
     *   then [0] is the path to the first field of the outermost struct, [0, 32] is the path to the first Int
     *   in the innermost struct., etc.
     */
    private fun consumeWrites(
        offsets: List<BigInteger>,
        expectedType: HeapType,
        remainingWrites: MutableList<Pair<HeapType, ObjectPath>>
    ): Boolean {
        if (remainingWrites.isEmpty()) {
            return false
        }

        val (nextType, nextPath) = remainingWrites.first()
        if (nextType == expectedType && nextPath.fields() == offsets) {
            remainingWrites.removeFirst()
            return true
        } else if (expectedType is HeapType.OffsetMap) {
            for ((off, ty) in expectedType.fieldTypes) {
                if (!consumeWrites(listOf(off) + offsets, ty, remainingWrites)) {
                    return false
                }
            }
            return true
        } else {
            return false
        }
    }

    private fun covers(expectedType: HeapType, topLevelWritePaths: List<Pair<HeapType, ObjectPath>>): Boolean {
        val writesToConsume = topLevelWritePaths.toMutableList()
        return consumeWrites(listOf(), expectedType, writesToConsume) && writesToConsume.isEmpty()
    }
}



/**
 * The actual revert analysis, which returns a set of [RevertReason] instances that describe
 * reverts that correspond to known patterns related to ABI decoding/encoding, and the set of commands
 * involved *exclusively* in determining whether to revert.
 */
object RevertAnalysis : PointsToQuery<Collection<RevertReason>>(), RevertConditionalAnalysis {
    /**
     * Each instance describes at least the [revertBlock] that reverts
     * the translation, and the [checkCommands] that are involved in computing the revert condition.
     * The identity of the subclass of [RevertReason] provides a description of the sort of revert (although
     * these are mostly for debugging purposes and aren't used anywhere)
     */
    sealed class RevertReason {
        data class DecodedArrayOverflow(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId
        ) : RevertReason()

        /**
         * Revert if the free pointer overflowed
         */
        data class FreePointerOverflow(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId,
            val allocOf: AllocationAnalysis.AbstractLocation
        ) : RevertReason()

        /**
         * A value `x & 0xfff...fff != x`, where [checkSymbol] is `x`
         * and which is (presumed) to be in the range given by `0xfff...fff`
         */
        data class DataValidationFailure(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId,
            val checkSymbol: TACSymbol.Var,
            val otherUse: Set<CmdPointer>
        ) : RevertReason()

        data class EnumValidationFailure(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId,
            val checkSymbol: TACSymbol.Var
        ) : RevertReason()

        /**
         * A "dynamic" offset in the encoded buffer is "too big", i.e., it is larger than [ABI_SIZE_BOUND]
         */
        data class CalldataOffsetOverflow(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId
        ) : RevertReason()

        /**
         * The same, but for an in-memory buffer
         */
        data class EncodedArrayOverflow(
            override val checkCommands: Collection<CmdPointer>,
            override val revertBlock: NBId
        ) : RevertReason()

        abstract val checkCommands: Collection<CmdPointer>
        abstract val revertBlock: NBId
    }

    /**
     * A candidate to check for one of the revert pattenrs above:
     * [where] is a jump command at the end of commands in [block],
     * which jumps to either to a reverting block [revertBlock],
     * and a non-reverting block [happyPath]. When jumping to [happyPath],
     * the conditions represented in [path] are induced by the path condition
     */
    private data class RevertCandidate(
        val where: LTACCmdView<TACCmd.Simple.JumpiCmd>,
        val path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>,
        val block: TACBlock,
        val happyPath: NBId,
        val revertBlock: NBId
    )

    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Collection<RevertReason> {
        /* For each block, get the revert condition, and then represent that
        information in a RevertCandidate
         */
        return graph.blocks.mapNotNull {
            getNonRevertCondition(it.id, graph, pta)?.let { (happyPath, path) ->
                RevertCandidate(
                    where = it.commands.last().narrow(),
                    block = it,
                    happyPath = happyPath,
                    path = path,
                    revertBlock = graph.succ(it.id).single {
                        it != happyPath
                    }
                )
            }
        }.mapNotNull {
            /*
               For each revert candidate, try to determine if one of the "patterns" matches. First one
               to return non-null is used (they should never overlap)
             */
            isCalldataOverflow(it, graph, pta) ?: isValueCleanRevert(it, graph, pta) ?: isFreePointerOverflow(
                it,
                graph,
                pta
            ) ?: isEncodedArrayOverflow(
                it, graph, pta
            ) ?: isOffsetOverflow(
                it, graph, pta
            ) ?: isEnumValidationRevert(it, graph) ?: isDecodedArrayOverflow(it, graph, pta)
        }
    }

    private fun isEnumValidationRevert(
        revertCandidate: RevertCandidate,
        graph: TACCommandGraph
    ): RevertReason? {
        return revertCandidate.path.entries.singleOrNull { (_, v) ->
            v.any {
                it is PathInformation.StrictUpperBound && it.num != null && it.num < BigInteger.TWO.pow(8)
            }
        }?.let { (k, _) ->
            RevertReason.EnumValidationFailure(
                revertBlock = revertCandidate.revertBlock,
                checkCommands = computeExclusiveCheckCommands(revertCandidate.where, graph),
                checkSymbol = k
            )
        }
    }

    private fun isOffsetOverflow(
        revertCandidate: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ): RevertReason? {
        /*
         Look to see if the non-reverting path must ensure that a dynamic offset (k) must have
         an upper bound of ABI_SIZE_BOUND; if so, then the revert was because the offset was too big
         */
        return if (revertCandidate.path.entries.any { (k, v) ->
                v.any {
                    it is PathInformation.UpperBound && it.num != null && it.num!! <= ABI_SIZE_BOUND
                } && pta.isDynamicOffset(k, revertCandidate.where.wrapped)
            }) {
            RevertReason.CalldataOffsetOverflow(
                checkCommands = computeExclusiveCheckCommands(revertCandidate.where, graph),
                revertBlock = revertCandidate.revertBlock
            )
        } else {
            null
        }
    }

    /*
     * Is the length of an array in the encoded buffer "too big" (according to some definition invented by the
     * solidity devs)
     */
    private fun isEncodedArrayOverflow(
        it: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ): RevertReason? {
        if (it.path.entries.any { (k, p) ->
                (pta.results[it.where.ptr]?.boundsAnalysis?.get(k)?.let {
                    it as? QualifiedInt
                }?.qual?.any {
                    it is IntQualifier.LengthOfCalldataArray
                } == true || pta.results[it.where.ptr]?.decoderState?.qualifiers?.get(k)?.let {
                    it as? DecoderAnalysis.AbstractDomain.ReadFrom
                }?.index?.let {
                    it as? TACSymbol.Var
                }?.let { ind ->
                    pta.results[it.where.ptr]!!.decoderState.qualifiers.get(ind) is DecoderAnalysis.AbstractDomain.BufferIndices.DynamicStart
                } == true) && p.any {
                    /*
                      In newer versions of solidity the length restriction is actually max_int / word_size
                     */
                    it is PathInformation.UpperBound && it.num != null && (it.num!! <= ABI_SIZE_BOUND ||
                            it.num!! <= (MAX_EVM_UINT256 / EVM_WORD_SIZE))
                }
            }) {
            return RevertReason.EncodedArrayOverflow(
                revertBlock = it.revertBlock,
                checkCommands = computeExclusiveCheckCommands(it.where, graph = graph)
            )
        } else {
            return null
        }
    }

    private fun isFreePointerOverflow(
        revert: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ): RevertReason? {
        val (nxtFreePointer, forAddress) = revert.path.entries.mapNotNull { (k, d) ->
            k `to?` d.mapNotNull {
                /*
                 Find the (unique) abstract address, the "base" pointer for which is a lower bound for k
                 */
                (it as? PathInformation.LowerBound)?.sym?.let {
                    pta.results[revert.where.ptr]?.pointsToState?.store?.get(it) as? InitializationPointer
                }?.takeIf {
                    (it is InitializationPointer.LengthFieldInit && !it.mayAdded) || (it is InitializationPointer.BlockInitPointer && it.offset == BigInteger.ZERO)
                }?.initAddr
            }.singleOrNull().takeIf { _ ->
                /*
                   assuming there is such a pointer, only take k if we also have a proof that k <= 0xfffffffffff
                 */
                d.any {
                    it is PathInformation.UpperBound && it.num != null && it.num!! <= ABI_SIZE_BOUND
                }
            }
        }.singleOrNull() ?: return null
        /*
          We have that nxtFreePointer has a lower bound of an initialization pointer (with address forAddress), and that nxtFreePointer has an upperbound of 2^32.

          Let's see that nxtFreePointer is indeed our next free pointer...

          This check is *very* syntactic and will break if the structure of free pointer updates changes. So we complain loudly if we don't see what we expect
         */
        if (graph.elab(revert.happyPath).commands.firstOrNull {
                it.cmd is TACCmd.Simple.AssigningCmd
            }?.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.lhs == TACKeyword.MEM64.toVar()
            }?.cmd?.rhs != nxtFreePointer.asSym()) {
            logger.warn {
                "When analyzing $revert, we expected the happy path to start with an update of the free pointer, failing..."
            }
            return null
        }
        return RevertReason.FreePointerOverflow(
            allocOf = forAddress,
            checkCommands = computeExclusiveCheckCommands(revert.where, graph),
            revertBlock = revert.revertBlock
        )
    }

    private fun isValueCleanRevert(
        revert: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ): RevertReason? {
        val d = revert.path.entries.singleOrNull { (k, which) ->
            which.any {
                it is PathInformation.StrictEquality && it.sym != null && (pta.results[revert.where.ptr]?.boundsAnalysis?.get(
                    it.sym
                )?.let {
                    it as? QualifiedInt
                }?.qual?.any {
                    /*
                       What in the world is that condition var nonsense? this is how solidity validates a boolean, that
                       is it checks that k == v where v := k != 0 (which can only be true if k in {0, 1})
                     */
                    (it is IntQualifier.MaskedOf && it.op == k) || (it is IntQualifier.ConditionVar &&
                            it.condition == ConditionQualifier.Condition.NEQ &&
                            ((it.op1 == k && it.op2 == BigInteger.ZERO.asTACSymbol()) ||
                                    (it.op2 == k && it.op1 == BigInteger.ZERO.asTACSymbol())))
                } == true || it.sym == k) // also solidity loves to check if x == x, just in case (for uint256)
            }
        }?.key ?: return null
        return RevertReason.DataValidationFailure(
            revertBlock = revert.revertBlock,
            checkCommands = computeExclusiveCheckCommands(where = revert.where, graph = graph),
            checkSymbol = d,
            otherUse = setOf()
        )
    }

    private fun isDecodedArrayOverflow(
        revert: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ) : RevertReason? {
        fun isEndOfArraySegment(s: TACSymbol.Var) = pta.results[revert.where.ptr]?.boundsAnalysis?.get(s)?.let {
            it as? QualifiedInt
        }?.qual?.any {
            it is IntQualifier.EndOfArraySegment
        } == true
        if(revert.path.any { (_, p) ->
            p.any {
                (it is PathInformation.UpperBound && it.sym != null && isEndOfArraySegment(it.sym!!)) ||
                    (it is PathInformation.StrictSignedUpperBound && isEndOfArraySegment(it.sym))
            }
        }) {
            return RevertReason.DecodedArrayOverflow(
                checkCommands = computeExclusiveCheckCommands(
                    where = revert.where,
                    graph = graph
                ),
                revertBlock = revert.revertBlock
            )
        } else {
            return null
        }
    }

    /**
     * This is the big one: are we trying to overflow the calldata buffer?
     */
    private fun isCalldataOverflow(
        it: RevertCandidate,
        graph: TACCommandGraph,
        pta: PointsToAnalysis
    ): RevertReason? {
        val st = pta.results[it.where.ptr] ?: return null
        /*
          All variables that are known to correspond to calldata
         */
        val callDataSize by lazy {
            st.boundsAnalysis.keysMatching { _, intDomain ->
                intDomain is QualifiedInt && intDomain.qual.any {
                    it is IntQualifier.CalldataSize || it is IntQualifier.EndOfArraySegment
                }
            }.toTreapSet()
        }

        /**
         * Is this expression a subtraction from calldata size, matches:
         * CDS - t,  ((CDS - t) - t), etc.
         */
        fun isSubtractFromCalldataSize(o: TACExpr): Boolean {
            if (o !is TACExpr.BinOp.Sub) {
                return false
            }
            if (o.o1 is TACExpr.Sym.Var) {
                return o.o1.s in callDataSize || o.o1.equivSym(TACKeyword.CALLDATA)
            } else if (o.o1 is TACExpr.BinOp.Sub) {
                return isSubtractFromCalldataSize(o.o1)
            } else {
                return false
            }
        }

        val isCalldataSizeRevert = it.path.any { (k, pathInfo) ->
            pathInfo.any {
                /* does the non-revert path imply that a variable is strictly less than the calldata size? If so,
                    then we are reverting if we have gone past the calldata size
                 */
                (it is PathInformation.UpperBound && it.sym != null && it.sym!! in callDataSize) ||
                        /* sigh... Does the non-revert path imply that variable k has a non-zero lower bound, and...
                         */
                        (it is PathInformation.LowerBound && it.num != null && it.num!! > BigInteger.ZERO && st.invariants.any { le ->
                            /* Is that variable known to appear in an equation where:
                               callDataSize = k + x + y ....

                               Then the revert branch implies there is LESS THAN some constant amount between the term x + y ...
                               and callDataSize (i.e., there isn't enough "room" left in the calldata buffer)
                            */
                            le.relates(k) && callDataSize.any { cds ->
                                le.relates(cds) && le.definingIntEquationFor(cds)?.let {
                                    it as? TACExpr.Vec.Add
                                }?.takeIf {
                                    it.ls.all { it is TACExpr.Sym }
                                }?.ls?.contains(k.asSym()) == true
                            }
                        }) ||
                        /*
                           do we have that x >= e, where x = calldata - t, then revert condition is likely
                           a bounds check

                           N.B. (and XXX: jtoman) this is an underapproximate check because the check generated by the compiler
                           is, in a word, wrong. see the bug disclosure
                         */
                        ((it is PathInformation.LowerBound || it is PathInformation.WeakSignedLowerBound || it is PathInformation.StrictSignedLowerBound) && st.invariants.any { le ->
                            le.relates(k) && le.relatesAny(callDataSize) && le.definingIntEquationFor(k)?.let {
                                isSubtractFromCalldataSize(it)
                            } == true
                        })
            }
        }
        if (!isCalldataSizeRevert) {
            return null
        }
        return RevertReason.CalldataOffsetOverflow(
            checkCommands = computeExclusiveCheckCommands(it.where, graph),
            revertBlock = it.revertBlock
        )
    }

    /**
     * Compute the set of (intra-block) commands starting from [where] that are used *exclusively* for generating the
     * condition variable being switched on
     */
    private fun computeExclusiveCheckCommands(
        where: LTACCmdView<TACCmd.Simple.JumpiCmd>,
        graph: TACCommandGraph
    ): Collection<CmdPointer> {
        val block = graph.elab(where.ptr.block)
        val seed = where.cmd.cond as TACSymbol.Var
        val lva = graph.cache.lva.liveVariablesAfter(where.ptr)
        /**
         * We're already done: the conditional variable itself is used after the jump, so by definition,
         * all commands used to compute that variable are used outside of the jump
         */
        if (seed in lva) {
            return listOf(where.ptr)
        }
        val st = mutableSetOf(seed)
        val toDelete = mutableSetOf(where.ptr)
        // iterate backwards
        for (lc in block.commands.dropLast(1).reversed()) {
            /*
              unclear what this is, but any variables we're collected so far are being used for *something* else
             */
            if (lc.cmd !is TACCmd.Simple.AssigningCmd) {
                st.removeAll(lc.cmd.getFreeVarsOfRhs())
                continue
            }
            val lhs = lc.cmd.lhs
            /**
             * lhs is being used for something else (where not being in st is an over-approximation for "being
             * used for something else"), therefore all variables used to compute lhs are also now used for something
             * else
             */
            if (lhs !in st) {
                st.removeAll(lc.cmd.getFreeVarsOfRhs())
            } else if (lhs in lva) {
                // lhs is not yet known being used for something else (it is in the state) but it is live after
                // the conditional jump, so it too is disqualified
                st.remove(lhs)
                st.removeAll(lc.cmd.getFreeVarsOfRhs())
            } else {
                /*
                   This variable was never used in a computation that is live after
                   the check, and it never used in anything else besides computing those variables in st
                 */
                toDelete.add(lc.ptr)
                st.remove(lhs)
                st.addAll(lc.cmd.getFreeVarsOfRhs().filter {
                    it !in lva
                })
            }
            if (st.isEmpty()) {
                break
            }
        }
        return toDelete
    }
}

/**
 * The result returned from the [SerializationCodeFinder]. The type parameter [U] ascribes to each command/block
 * found in the result the set of seeds that (exclusively) use those commands/blocks; i.e. which endode/decode/direct read
 *
 * The type parameter for [U] is similar
 */
data class GroupedCodeSearchResult<U>(
    val foundCommands: Map<CmdPointer, Set<U>>,
    val foundBlocks: Map<NBId, Set<U>>,
    val boundary: Collection<BoundaryInformation<U>>
)

/**
 * The [start] and [end] (exclusive) of commands that are involved in the decoding/encoding/reads in [group]
 */
data class BoundaryInformation<U>(
    val start: CmdPointer,
    val end: CmdPointer,
    val group: Set<U>
)


private val commonIgnoredAnnots: Set<MetaKey<*>> = setOf(POP_ALLOCATION, TACMeta.SNIPPET, BLOCK_SOURCE_INFO)


/**
 * Reusable component to finding and grouping together code that is (exclusively) for the purposes
 * of some specific computation (called seeds). As the name suggests,
 * its primary use case is finding code used (exclusively) in serialization/deserialization (the seeds
 * in this case are memory/calldata reads/writes involved in the serialization/deserialization). In other words,
 * this interface facilitates backward program slices for serialization/deserialization code.
 *
 * If, however, we implemented a basic backwards slicing scheme using only the reads & writes directly
 * involved in (de)serialization, we would omit large swaths of code that are conceptually part of those
 * (de)serialization processes, but do not *directly* influence those processes. The best example are revert conditions:
 * the solidity compiler will generate checks that validate calldata encoding (reverting if a violation if found).
 * The code that performs this validation doesn't actually produce a value that flows to a write/read of the (de)serialization
 * but is related nonetheless. The impact of these reverts (and other code patterns like them) also cut the slice
 * short: the slice is guaranteed to only have code that is *only* related to (de)serialization, the use of values in the
 * revert conditions et al. will lead the slice to pessimistically stop early.
 *
 * To explain how we get around this, we have to first discuss the basic slicing scheme. We start with a set of commands
 * (the seed command) P and find the definition sites of all variables used in P. For each definition site, if
 * all of its uses are in P, that definition site is itself added to P. This process continues until P stabilizes. Given this
 * sketch, the problem of revert conditions is hopefully clearer: the use of a variable defined in command L
 * in code that checks a revert condition will disqualify L from being included in P.
 *
 * To address this, we introduce the notion of "unused groups": sets of commands that _might_ be related to a (de)serialization
 * process and should not be considered "first class" uses.
 * When considering the use sites of a definition L, any use sites in an unused group are not considered
 * disqualifying for the purposes of including L in P. Further, if L *should* be included in P, then all commands in
 * the unused group mentioning the variable defined by L are also included in P.
 *
 * We justify this choice as follows: the goal of this class is to find all serialization and deserialization code
 * and completely replace it with direct argument passing. Thus, code related to the validation of (de)serialization
 * is pointless provided the rest of the (de)serialization is being replaced. In other words, validation code should
 * be included with (and removed alongside) more directly related code.
 *
 * With that said, [T] are the types of "unused groups": each command can be associated with at most one unused group.
 * If a command in the unused group is found to be a use site of a definition L being included in P, then the the
 * entirety of the group [T] should be included in P. To that end implementers must provide for instances of type [T]
 * methods [commands] and [blocks] which give the blocks and commands in that group.
 *
 * The type parameter [U] serves the same purpose as in [GroupedCodeSearchResult], it provides a way to run this search
 * for multiple seeds (i.e., serializations or deserialization) in parallel, and find code that is part of multiple
 * slices
 */
private interface SerializationCodeFinder<T, U> {

    fun T.commands() : Collection<CmdPointer>
    fun Set<T>.commands() : Collection<CmdPointer> = flatMapToSet { it.commands() }

    fun computeClosure(
        seed: Map<CmdPointer, U>,
        graph: TACCommandGraph,
        unusedGroup: Map<CmdPointer, Set<T>>,
        deletedBlockSeed: Map<NBId, Set<U>>,
        stopCutAt: (CmdPointer, U) -> Boolean = { _, _ ->  false },
        ignoredAnnot: Set<MetaKey<*>> = setOf()
    ) : GroupedCodeSearchResult<U> {
        /**
         * The use and def analyses are on-demand, which is bad is you're constantly requerying the def/use sites of a variable.
         * That usually doesn't happen in normal execution of CVT, but it does here, so we build a thin caching layer.
         */
        val useSites = mutableMapOf<Pair<TACSymbol.Var, CmdPointer>, Set<CmdPointer>>()
        fun useSitesOf(v: TACSymbol.Var, p: CmdPointer) = useSites.computeIfAbsent(v to p) {
            graph.cache.use.useSitesAfter(it.first, it.second)
        }
        val defSites = mutableMapOf<Pair<TACSymbol.Var, CmdPointer>, Set<CmdPointer>>()
        fun defSitesOf(v: TACSymbol.Var, p: CmdPointer) = defSites.computeIfAbsent(v to p) {
            graph.cache.def.defSitesOf(it.first, it.second)
        }

        /**
         * The set P mentioned above: the set of commands that are known to be part of SerDe code (and nothing more)
         */
        val P = seed.mapValuesTo(mutableMapOf()) {
            mutableSetOf(it.value)
        }

        /**
         * We also have a way to seeding blocks
         */
        val deletedBlocks = deletedBlockSeed.mapValuesTo(mutableMapOf()) {
            it.value.toMutableSet()
        }
        val loops = getNaturalLoops(graph)
        val deletedLoops = mutableSetOf<NBId>()
        /*
           Technically these blocks don't *have* to be revert blocks, but as currently used, they always are
         */
        val revertBlocks = unusedGroup.flatMapTo(
            mutableSetOf()
        ) { (_, related) ->
            related.blocks() ?: listOf()
        }
        var changed = true
        /**
         * For each block in [this], add the SerDe identifiers to those associated with that block
         * Update changed (and ensure another iteration of the loop) if some of [l] was not already
         * the set for all blocks
         */
        fun Collection<NBId>.markWith(l: Collection<U>) {
            this.forEach {
                changed = deletedBlocks.computeIfAbsent(it) { mutableSetOf() }.addAll(l) || changed
            }
        }

        /**
         * As above, but for command pointers
         */
        fun CmdPointer.markWith(l: Collection<U>) {
            val toProp = l.filter { u ->
                !stopCutAt(this, u)
            }
            if (toProp.isEmpty()) {
                return
            }
            changed = P.computeIfAbsent(this) { mutableSetOf() }.addAll(toProp) || changed
        }

        fun Collection<CmdPointer>.markWith(l: Collection<U>) {
            this.forEach { ptr ->
                ptr.markWith(l)
            }
        }

        fun CmdPointer.markWith(i: U) {
            this.markWith(setOf(i))
        }

        infix fun U.usedAt(ptr: CmdPointer) = P[ptr]?.contains(this) == true

        while(changed) {
            changed = false
            /*
            Why the toList? Well, java (and by extension kotlin) gets really cranky if you mutate a collection you're iterating
            through: so we do snapshot via the toList method
             */
            for((p, usedIn) in P.toList()) {
                val use = graph.elab(p).cmd.getFreeVarsOfRhs()
                use.forEach { v ->
                    /**
                     * Otherwise we end up dragging in a bunch of unrelated code. The reads/writes of the free pointer
                     * are explicitly marked in the seeds (see [DecodeSeedFinder])
                     */
                    if(v == TACKeyword.MEMORY.toVar() || v == TACKeyword.MEM64.toVar()) {
                        return@forEach
                    }
                    val vDef = defSitesOf(v, p)
                    vDef.filter {
                        val vUse = useSitesOf(v, it)
                        /* the definition site it of v has not yet been marked with all of the seeds in usedIn */
                        P[it]?.containsAll(usedIn) != true && vUse.all {
                            /* all use sites are part of *some* (potentially unrelated) SerDe code, or a revert */
                            it in P || it in unusedGroup
                        }
                    }.forEach {
                        /*
                          Any use from this definiton that is part of a revert (or other unused group) seeds that revert into this process
                         */
                        useSitesOf(v, it).mapNotNull {
                            unusedGroup[it]
                        }.forEach { d ->
                            d.commands().markWith(usedIn)
                            d.blocks()?.markWith(usedIn)
                        }
                        it.markWith(usedIn)
                    }
                }
            }
            /*
            So, loops are tricky. The body of the loop can't be marked unless everything (including the iteration variables)
            end up being used directly in the SerDe process. Thus, we can't delete a loop like:
            i = 0
            x = ...
            while(i < someLen) {
               definitely_involved(x)
               i += 1
               x += 32
            }
            We can remove the `definitely_involved` command, but *not* the loop, which would leave a pointless loop
            (and also, likely, cut the slice short).
            Thus we over-approximate "is this loop now dead" by seeing if every command within the loop is:
            1. part of P, or
            2. involved in computing values that are dead past the loop, or
            3. involved in computing values that are always part of P after the loop

            The above gives that the entire loop is just doing (de)serialization work and any other values computed
            are only part of that work, and thus can be safely removed.
             */
            loops.forEach { l ->
                if(l.head in deletedLoops) {
                    return@forEach
                }
                val loopSucc = l.body.flatMap {
                    graph.succ(it)
                }.singleOrNull {
                    it !in l.body && it !in deletedBlocks && it !in revertBlocks
                } ?: return@forEach
                val postLoopPtr = graph.elab(loopSucc).commands.first().ptr
                val postLoopLive = graph.cache.lva.liveVariablesBefore(postLoopPtr)
                /*
                  We only try to remove loops if the code inside is part of just one SerDe process (i.e., all
                  commands included in P have a singleton set as their marked seeds).

                  FIXME(jtoman): Post loop removal, it probably can't
                    (won't?) happen that a command within the loop is *later* marked as part of different
                    SerDe process. It is unclear what we should do if that happens.
                 */
                val singletonId = l.body.asSequence().flatMap {
                    graph.elab(it).commands
                }.mapNotNull {
                    P[it.ptr]
                }.asIterable().monadicMap {
                    it.singleOrNull()
                }?.uniqueOrNull() ?: return@forEach
                if(l.body.all {
                        graph.elab(it).commands.all {
                            it.cmd is TACCmd.Simple.JumpdestCmd ||
                                it.cmd is TACCmd.Simple.LabelCmd ||
                                it.cmd is TACCmd.Simple.NopCmd ||
                                it.cmd is TACCmd.Simple.AnnotationCmd ||
                                (it.cmd is TACCmd.Simple.JumpCmd && it.cmd.dst in l.body) ||
                                singletonId usedAt it.ptr ||
                                it.ptr in unusedGroup ||
                                (it.cmd is TACCmd.Simple.JumpiCmd && listOf(it.cmd.dst, it.cmd.elseDst).all {
                                    it == loopSucc || it in l.body
                                }) ||
                                (it.cmd is TACCmd.Simple.AssigningCmd && (it.cmd.lhs !in postLoopLive || useSitesOf(it.cmd.lhs, postLoopPtr).all {
                                    singletonId usedAt it || it in unusedGroup
                                }))
                        }
                    }) {

                    val idSet = listOf(singletonId)
                    deletedLoops.add(l.head)
                    graph.pred(l.head).singleOrNull() {
                        it !in l.body
                    }?.let {
                        val cmd = graph.elab(it).commands.last()
                        cmd.ptr `to?` graph.elab(it).commands.last().snarrowOrNull<LoopCopyAnalysis.LoopCopySummary>()
                    }?.takeIf {
                        it.second.originalBlockStart == l.head
                    }?.let { (ptr, _) ->
                        ptr.markWith(idSet)
                    }

                    /* we can delete this loop, mark everything inside, including unused groups, as part of the process
                        with singleton id
                     */
                    l.body.map(graph::elab).forEach {
                        for(lc in it.commands) {
                            if(lc.ptr !in P && lc.cmd is TACCmd.Simple.AssigningCmd) {
                                useSitesOf(lc.cmd.lhs, postLoopPtr).mapNotNull {
                                    unusedGroup[it]
                                }.forEach {
                                    it.commands().markWith(idSet)
                                    it.blocks()?.markWith(idSet)
                                }
                            }
                            lc.ptr.markWith(singletonId)
                            unusedGroup[lc.ptr]?.let {
                                it.commands().markWith(idSet)
                                it.blocks()?.markWith(idSet)
                            }
                        }
                    }
                    l.body.markWith(idSet)
                }
            }
        }

        /*
          Oh no, we accidentally marked our initialization of the free pointer. This will break literally everything.
         */
        P.entries.removeIf { (it, _) ->
            graph.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.lhs == TACKeyword.MEM64.toVar()
            }?.cmd?.rhs?.equivSym(0x80.toBigInteger().asTACSymbol()) == true
        }
        /*
          Find all blocks that are marked in P, or which have been explicitly deleted
         */
        val mentionedBlocks = P.keys.mapTo(mutableSetOf(), CmdPointer::block)
        mentionedBlocks.addAll(deletedBlocks.keys)
        /*
          Of those blocks, which are "all" serialization commands?
         */
        val allSerializationBlock = mutableSetOf<NBId>()
        fun isNoiseCommand(ltacCmd: LTACCmd) : Boolean {
            return ltacCmd.cmd is TACCmd.Simple.LabelCmd ||
                    ltacCmd.cmd is TACCmd.Simple.NopCmd ||
                    ltacCmd.cmd is TACCmd.Simple.JumpdestCmd ||
                    (ltacCmd.cmd is TACCmd.Simple.AnnotationCmd &&
                            (ltacCmd.cmd.annot.k == DSA_BLOCK_START ||
                                    ltacCmd.cmd.annot.k == DSA_BLOCK_END ||
                                    ltacCmd.cmd.annot.k in ignoredAnnot))
        }
        /*
          We define an "all serialization block" as being one which is:
          1. explicitly marked as such (in deleted blocks), or
          2. Every command is either:
            a. a jump to a block known to be at least part of the SerDe process
            b. a jumpi command, where both blocks are at least part of the SerDe process
            c. a "noise command", i.e., a command that has no effect on the machine state
            d. marked in P
         */
        mentionedBlocks.filterTo(allSerializationBlock) {
            it in deletedBlocks || graph.elab(it).commands.all {
                it.ptr in P ||
                        (it.cmd is TACCmd.Simple.JumpCmd && it.cmd.dst in mentionedBlocks) ||
                        (it.cmd is TACCmd.Simple.JumpiCmd && it.cmd.dst in mentionedBlocks && it.cmd.elseDst in mentionedBlocks) ||
                        isNoiseCommand(ltacCmd = it)
            }
        }
        /*
           For each all-serialization-block, in the first related
           command within the block, what which SerDe process is that command
           in?
         */
        val allSerializationStart = mutableMapOf<NBId, Set<U>>()
        /* ditto but for the first such command starting from the end */
        val allSerializationEnd = mutableMapOf<NBId, Set<U>>()
        for(b in allSerializationBlock) {
            if(b in deletedBlocks) {
                allSerializationStart[b] = deletedBlocks[b]!!
                allSerializationEnd[b] = deletedBlocks[b]!!
                continue
            }
            /*
              But wait, how do we know that there is such a command? Well, a block is only included
              in allSerializationBlock if it was included in mentioned blocks, and every block in
              mentioned block was either: a) in deletedBlocks (handled above), or b) the block of a command in P
              ergo, at least one command in b must be in P
             */
            val command = graph.elab(b).commands
            val startGroup = command.firstMapped {
                P[it.ptr]
            }!!
            val endGroup = command.reversed().firstMapped {
                P[it.ptr]
            }!!
            allSerializationStart[b] = startGroup
            allSerializationEnd[b] = endGroup
        }
        /**
          If [rel] is either the successor or predecessor relation, see if [st] can definitely reach another command
          with the same set of SerDe seeds as [k] (presumed to be the set of processes associated with [st]). the flow
         to another similar group must be unconditional (no branching)
         */
        fun hasPathToRelated(st: CmdPointer, k: Set<U>, rel: (CmdPointer) -> Collection<CmdPointer>, check: (CmdPointer) -> Boolean = { true }) : Boolean {
            if(!isNoiseCommand(graph.elab(st)) || st in P || !check(st) || k.any { stopCutAt(st, it) }) {
                return false
            }
            var it = rel(st)
            do {
                if(it.all { c -> P[c] == k  }) {
                    return true
                }
                /* if we are going to keep looking, we must have only possible next command */
                val nxt = it.singleOrNull()?.let(graph::elab) ?: return false
                /* If this is not a noise command, then stop, there is some "semantically" important
                   command between st and any other SerDe commands
                 */
                if(!isNoiseCommand(nxt)) {
                    return false
                }
                if(!check(nxt.ptr)) {
                    return false
                }
                if (k.any { stopCutAt(nxt.ptr, it) }) {
                    return false
                }
                /* keep looking */
                it = rel(nxt.ptr)
            } while(it.isNotEmpty())
            return false
        }
        val startPoints = P.filter { (src, u) ->
            /*
               We are trying to find commands whose predecessors are not all part of the same group as [src].
               (hence ! all).
               Q) Couldn't you do any with a negated condition?
               A) maaaaybe!
             */
            !graph.pred(src).all {
                /*
                  What do we define as being "part of the same group". Either the predecessor is marked as u, or
                  the first command reachable from the tail of the block is marked with u (as recorded in allSerializationEnd),
                  or it is not marked, is a noise command, but has a straightly path of noise commands to a command marked with u
                 */
                P[it] == u
                    || (src.block  != it.block && allSerializationEnd[it.block] == u)
                    || hasPathToRelated(it, u, graph::pred)
            }
        }

        val preEndPoints = P.filter { (src, u) ->
            // as above, but for successors
            !graph.succ(src).all {
                P[it] == u
                    || (it.block != src.block && allSerializationStart[it.block] == u)
                    || hasPathToRelated(it, u, graph::succ) {
                        // a return like
                        // if (*) return (a, b); (bool, string memory)
                        // return (a', b');
                        // can result in two end points at the same block/position...
                        // see A/H/H2 under test/resources/analysis/abi
                        graph.pred(it).count { pred ->
                            graph.elab(pred).snarrowOrNull<LoopCopyAnalysis.LoopCopySummary>()?.skipTarget != it.block
                        } <= 1
                }
            }
        }

        val endPoints = preEndPoints.mapNotNull { (src, u) ->
            /* for the end points, its useful to find the command that is the beginning of the non-marked region, instead
               of the last command of the region (especially if the last command is a branch command)
             */
            graph.succ(src).singleOrNull {
                !(P[it] == u
                    || (it.block != src.block && allSerializationStart[it.block] == u)
                    || it.block in revertBlocks
                    || hasPathToRelated(it, u, graph::succ) {
                        graph.pred(it).count { pred ->
                            graph.elab(pred).snarrowOrNull<LoopCopyAnalysis.LoopCopySummary>()?.skipTarget != it.block
                        } <= 1
                })
            }?.to(u)
        }.toSet()

        val boundary = graph.cache.domination.let { dom ->
                /* Find the matching end points for each start point (st) */
                startPoints.map { (st, g) ->
                    (st to g) to endPoints.filter { (_, k) -> // find the end points that have the same mark set as st
                        k == g
                    }.singleOrNull { (end, _) ->
                        /*
                          Find the only end point where:
                          1. the start dominates the end,
                          2. no other start point (otherSt) dominates end without also dominating start
                         */
                        dom.dominates(st, end) && startPoints.none { (otherSt, otherG) ->
                            otherG == g && dom.dominates(otherSt, end) && !dom.dominates(otherSt, st)
                        }
                    }?.first
                }
            }.mapNotNull { (fr, toC) ->
                /*
                 is the start/group pair, toC is just the end command
                 */
                BoundaryInformation(
                    start = fr.first,
                    end = toC ?: return@mapNotNull null,
                    group = fr.second
                )
            }.takeIf {
                if (startPoints.size != endPoints.size) {
                    logger.debug {"Boundary Information=[$it.start, $it.end]"}
                    val missingStart = startPoints.filter { sp ->
                        it.all { it.start != sp.key }
                    }
                    val missingEnd = endPoints.filter { ep ->
                        it.all { it.end != ep.first }
                    }
                    logger.debug {"Missing starts = $missingStart"}
                    logger.debug {"Missing ends = $missingEnd"}
                }
                startPoints.size == endPoints.size
            }.orEmpty()
        /*
          And we're done!
         */
        return GroupedCodeSearchResult(
            P, deletedBlocks, boundary
        )
    }

    fun T.blocks(): Collection<NBId>?
    fun Set<T>.blocks(): Collection<NBId>? = flatMapToSet { it.blocks() ?: setOf() }

}

/**
 * Find the set of abstract locations that are definitely involved in the decode completed at [decodePoint]
 */
class DecodeLocations(
    private val decodePoint: CmdPointer,
    private val nestedAllocationPops: Collection<NestedAllocations.NestedAllocationPop>
) : PointsToQuery<Set<AllocationAnalysis.AbstractLocation>>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Set<AllocationAnalysis.AbstractLocation> {
        val toQuery = mutableSetOf<AllocationAnalysis.AbstractLocation>()
        /**
         * Compute for each address, the set of child addresses that are definitely allocated for the purposes
         * of initializing that address
         */
        val initObjectToNestedObjects = nestedAllocationPops.groupBy {
            it.targetAddress
        }
        val decode = pta.results[decodePoint]?.pointsToState?.h?.allocStack?.takeIf {
            it.size == 1
        }?.first() ?: throw IllegalArgumentException("$decodePoint is not a decode pop")
        /**
         * Simple fixpoint saturation, find the closure of addresses that are always allocated to initialize decode
         * (or its own child objects)
         */
        SimpleWorklist.iterateWorklist(listOf(decode)) run@{ it, nxt ->
            if(it in toQuery) {
                return@run
            }
            toQuery.add(it)
            initObjectToNestedObjects[it]?.mapTo(nxt) {
                it.poppedAddress
            }
        }
        return toQuery
    }
}

/**
 * The seeds here, the locations where decoding, encoding are completed, and locations where a direct read occurs
 */
class ABICodeFinder(
    private val decodes: Map<CmdPointer, ABIDecodeComplete>,
    private val encodes: Map<CmdPointer, ABIEncodeComplete>,
    private val boundChecks: Map<CmdPointer, ABIDirectRead>
) : PointsToQuery<GroupedCodeSearchResult<ABICodeFinder.Source>?>(), SerializationCodeFinder<RelatedCommands, ABICodeFinder.Source> {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): GroupedCodeSearchResult<Source>? {
        val seeds = mutableMapOf<CmdPointer, Source>()
        val deletedBlockSeed = mutableMapOf<NBId, MutableSet<Source>>()
        val nestedAllocationPops = pts.query(NestedAllocations) ?: return null

        val stopCutAt = mutableSetOf<CmdPointer>()
        /*
          Find the commands that are at least known to be part of accomplishing the decode at [k]
          (see DecodeSeedFinder)
         */
        for ((k, dec) in decodes) {
            val (seed, stopCut, block) = pts.query(
                DecodeSeedFinder(
                    decodePoint = k,
                    nestedAllocationPops = nestedAllocationPops,
                    decodeComplete = dec
                )
            ) ?: return null
            for (b in block) {
                deletedBlockSeed.computeIfAbsent(b) {
                    mutableSetOf()
                }.add(Source.Decode(dec.id))
            }
            for (c in seed) {
                if (c in seeds) {
                    return null
                }
                seeds[c] = Source.Decode(dec.id)
            }
            for(stop in stopCut) {
                stopCutAt.add(stop)
            }
        }

        val allocEncodeWritePoints = mutableMapOf<Int, CmdPointer>()

        /*
         As above, but the commands involved in the encode completed at [k] (see EncodeSeedFinder)
         */
        for ((k, enc) in encodes) {
            val seed = pts.query(
                EncodeSeedFinder(
                    encodePoint = k,
                    allEncodeCompletes = encodes.keys,
                    encodeComplete = enc
                )
            ) ?: return null
            for (c in seed.seeds) {
                if (c in seeds) {
                    return null
                }
                seeds[c] = Source.Encode(enc.id)
            }
            for(stopAt in seed.stopCut) {
                stopCutAt.add(stopAt)
            }
            if(seed.allocFreePointerWrite != null) {
                allocEncodeWritePoints[enc.id] = seed.allocFreePointerWrite
                seeds[seed.allocFreePointerWrite] = Source.Encode(enc.id)
            }
        }
        /*
          Direct reads are self-contained in the single command where the read happens
         */
        for ((k, b) in boundChecks) {
            seeds[k] = Source.Direct(b.id)
            b.path.bufferAccessPath.getIndexVars().forEach {
                stopCutAt.addAll(graph.cache.def.defSitesOf(it, k))
            }
        }
        val groups = getUnusedGroups(
            pts,  graph
        ) ?: return null


        /* enc.id |-> enc */
        val encodesById = encodes.values.associateBy { it.id }

        /* enc.id |-> the roots of the encoded object */
        val xsByEncodeId = encodesById.mapValues {(_, enc) ->
            enc.buffer.buffer.values.filterIsInstance<TopLevelValue.Path>().flatMapToSet {
                it.paths.mapNotNull { it.rootVar }
            }
        }

        /* enc.id |-> ptr such that ptr is a DecodeComplete point that ouputs one of the roots encoded by enc */
        val relevantDecodePointsForEncodeId = encodesById.mapValues {
            val xs = xsByEncodeId[it.value.id].orEmpty()
            decodes.filter {
                it.value.output.containsAny(xs)
            }.keys
        }

        /* @return true when [ptr] dominates a decode point that ouputs one of the roots
                   encoded by [u] */
        fun isRelevantDecode(ptr: CmdPointer, u: Source.Encode): Boolean =
            relevantDecodePointsForEncodeId[u.id]?.let { decodes ->
                decodes.any { decode ->
                    graph.cache.domination.dominates(ptr, decode)
                }
            } == true

        /** @return true when the cmd at [ptr] is an assignment to one of the roots of [u] */
        fun isRelevantAssignment(ptr: CmdPointer, u: Source.Encode): Boolean =
            graph.elab(ptr).cmd.getLhs()?.let { lhs ->
                xsByEncodeId[u.id]?.contains(lhs)
            } == true

        val gvn = graph.cache.gvn

        fun isAllocFreePointerRead(ptr: CmdPointer, u: Source.Encode): Boolean {
            val targetPoint = allocEncodeWritePoints[u.id] ?: return false
            val tgt = graph.elab(ptr).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.rhs is TACExpr.Sym.Var
            }?.wrapped?.enarrow<TACExpr.Sym.Var>()?.exp?.s ?: return false
            return tgt in gvn.findCopiesAt(ptr, targetPoint to TACKeyword.MEM64.toVar())
        }

        fun stopCutFor(ptr: CmdPointer, u: Source): Boolean =
            u is Source.Encode && (isRelevantDecode(ptr, u) || isRelevantAssignment(ptr, u) || isAllocFreePointerRead(ptr, u))

        return this.computeClosure(
            seed = seeds,
            graph = graph,
            unusedGroup = groups,
            deletedBlockSeed = deletedBlockSeed,
            ignoredAnnot = commonIgnoredAnnots,
            stopCutAt =  { ptr, u -> ptr in stopCutAt || stopCutFor(ptr, u) }
        )
    }

    /**
     * Identifies the SerDe process a command is involved in
     */
    @KSerializable
    @Treapable
    sealed class Source : AmbiSerializable, UniqueIdEntity<Source> {
        abstract val id: Int

        @KSerializable
        @GenerateRemapper
        data class Decode(@GeneratedBy(Allocator.Id.ABI) override val id: Int) : Source(), RemapperEntity<Source>

        @KSerializable
        @GenerateRemapper
        data class Encode(@GeneratedBy(Allocator.Id.ABI) override val id: Int) : Source(), RemapperEntity<Source>

        @KSerializable
        @GenerateRemapper
        data class Direct(@GeneratedBy(Allocator.Id.ABI) override val id: Int) : Source(), RemapperEntity<Source>
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    override fun RelatedCommands.commands(): Collection<CmdPointer> {
        return this.commands()
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    override fun RelatedCommands.blocks(): Collection<NBId>? {
        return this.blocks()
    }
}

sealed class RelatedCommands {
    abstract fun commands(): Collection<CmdPointer>
    abstract fun blocks(): Collection<NBId>?
    /**
     * A group of commands that are inserted after each loop to provide a compact summarization of the loop behavior.
     * These are totally artificial and can (and should) be deleted assuming the related serialization code can be
     * removed (hence the inclusion in RelatedCommands here)
     */
    data class AssumeGroup(val s: Set<CmdPointer>) : RelatedCommands() {
        override fun commands(): Collection<CmdPointer> {
            return s
        }

        override fun blocks(): Collection<NBId>? {
            return null
        }
    }
    data class DecodeRevert(val r: RevertReason) : RelatedCommands() {
        override fun commands(): Collection<CmdPointer> {
            return r.checkCommands
        }

        override fun blocks(): Collection<NBId> {
            return listOf(r.revertBlock)
        }
    }
}

/**
 * Helper function to get the unused groups to feed into a [SerializationCodeFinder] query
 */
private fun getUnusedGroups(
    pts: IPointsToInformation,
    graph: TACCommandGraph
) : Map<CmdPointer, Set<RelatedCommands>>? {
    val reverts = pts.query(RevertAnalysis) ?: return null
    val groups = mutableMapOf<CmdPointer, MutableSet<RelatedCommands>>()
    for (r in reverts) {
        for (c in r.checkCommands) {
            if (c in groups) {
                return null
            }
            /*
              "explode" the commands and mark for each the revert check they are part of
             */
            groups[c] = mutableSetOf(RelatedCommands.DecodeRevert(r))
        }
    }
    /*
      Find all commands tagged with the LoopInterpolation meta, and group them by their unique id
     */
    val assumeGroups = graph.commands.mapNotNull {
        it `to?` it.cmd.meta.find(LoopInterpolation.ASSUME_GROUP)
    }.groupBy({ it.second }, { it.first.ptr })
    for ((_, group) in assumeGroups) {
        val commandSet = group.toSet()
        for (c in commandSet) {
            if (c in groups) {
                return null
            }
            groups.computeIfAbsent(c) { mutableSetOf() }.add(RelatedCommands.AssumeGroup(commandSet))
        }
    }

    // Close over the initial unused group:
    // If `c` is unused and `fv \in freeVars(c)`,
    // then add a definition of `fv` to the set of unused cmds
    // ONLY WHEN *all* of the uses of that definition of `fv` are themselves
    // unused
    var unusedGroupChanged = true
    while(unusedGroupChanged) {
        unusedGroupChanged = false
        for ((cmd, rcs) in groups.entries.toList()) {
            for (fv in graph.elab(cmd).cmd.getFreeVarsOfRhs()) {

                val fvDefs = graph.cache.def.defSitesOf(fv, cmd)
                fvDefs.filter {
                    groups[it]?.containsAll(rcs) != true
                        && groups.keys.containsAll(graph.cache.use.useSitesAfter(fv, it))
                }.let {
                    it.forEach {
                        groups.computeIfAbsent(it) { mutableSetOf() }.addAll(rcs)
                    }
                    unusedGroupChanged = unusedGroupChanged || it.isNotEmpty()
                }
            }
        }
    }
    return groups
}

data class EncodeSeedData(
    val seeds: Collection<CmdPointer>,
    val stopCut: Collection<CmdPointer>,
    val allocFreePointerWrite: CmdPointer?
)

data class DecodeSeedData(
    val seedCommands: Collection<CmdPointer>,
    val stopCut: Set<CmdPointer>,
    val seedBlocks: Collection<NBId>
)

/**
 * Find all writes that are definitely part of the encoding at [encodePoint] whose encoding is represented
 * in [encodeComplete]
 */
private class EncodeSeedFinder(
    val encodePoint: CmdPointer,
    val allEncodeCompletes: Set<CmdPointer>,
    val encodeComplete: ABIEncodeComplete
) : PointsToQuery<EncodeSeedData>() {

    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): EncodeSeedData {
        return this.computeInternal(
            pta, pts, graph
        ) ?: EncodeSeedData(
            listOf(), listOf(), null
        )
    }

    private fun computeInternal(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): EncodeSeedData? {
        val otherEncodes = allEncodeCompletes - encodePoint
        val primitiveSymbols = encodeComplete.buffer.buffer.entries.flatMap { (_, d) ->
            if(d.ty != HeapType.Int) {
                return@flatMap listOf()
            }
            when(d) {
                is TopLevelValue.Constant -> listOf()
                is TopLevelValue.Path -> d.paths.mapNotNull {
                    ((it as? ObjectPathGen.Root)?.oRoot as? ObjectPathAnalysis.ObjectRoot.V)?.v
                }
            }
        }
        fun Collection<CmdPointer>.cutPrimitiveWrites(freePointerWrite: CmdPointer?) : EncodeSeedData {
            return EncodeSeedData(
                seeds = this,
                stopCut = this.mapNotNull {
                    graph.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf {
                        it.cmd.value in primitiveSymbols
                    }
                }.map { it.ptr } + encodeComplete.buffer.buffer.flatMap {(_, tlv) ->
                    when(tlv) {
                        is TopLevelValue.Constant -> listOf()
                        is TopLevelValue.Path -> tlv.paths.flatMap {
                            when(val root = it.root) {
                                is ObjectPathAnalysis.ObjectRoot.CalldataRoot -> {
                                    root.bp.getIndexVars().flatMap {
                                        graph.cache.def.defSitesOf(it, encodePoint)
                                    }
                                }
                                is ObjectPathAnalysis.ObjectRoot.V -> listOf()
                            }
                        }
                    }
                },
                allocFreePointerWrite = freePointerWrite
            )
        }
        return when (encodeComplete.target) {
            is EncodeOutput.Alloc -> {
                /*
                  If the output of our encoding is an allocated bytes buffer, this is very easy,
                  get the address being allocated, and then find all writes to that address via the InitializationWrites query
                 */
                val initAddress = pta.results[encodePoint]?.pointsToState?.let { pointer ->
                    encodeComplete.target.data.monadicMap {
                        pointer.store[it]?.let {
                            it as? InitializationPointer
                        }?.initAddr
                    }?.uniqueOrNull()
                } ?: return null
                pts.query(InitializationWrites(listOf(initAddress)))?.map {
                    it.ptr
                }?.plus(encodePoint)?.cutPrimitiveWrites(initAddress.nextFPWriteCmd) // include the encode point for completeness (a pop allocation annotation)
            }
            EncodeOutput.Scratch -> {
                /*
                 This is a little bit more complicated. We have to traverse backwards, finding all writes to the scratch
                 pointer
                 */
                val visited = mutableSetOf<CmdPointer>()
                val res = mutableSetOf<CmdPointer>()
                SimpleWorklist.iterateWorklist(listOf(encodePoint)) { p, nxt ->
                    if (p in visited) {
                        return@iterateWorklist
                    }
                    visited.add(p)
                    val pointsToDomain = pta.results[p] ?: return@iterateWorklist
                    val enc = pointsToDomain.encoderState.encoding ?: return@iterateWorklist
                    /*
                    Are we still encoding and targeting the scratch pointer? If not, stop here. Conveniently the encoding analysis
                    somehow knows when to start tracking information for the scratch pointer. I don't remember how or why
                     */
                    if (enc.buffer != ScratchPointer) {
                        return@iterateWorklist
                    }
                    val lc = graph.elab(p)
                    /*
                      Any bytestore or bytelong copy to the scratch pointer during the encoding window is part of the encoding
                     */
                    if (lc.cmd is TACCmd.Simple.AssigningCmd.ByteStore && lc.cmd.base == TACKeyword.MEMORY.toVar() && pointsToDomain.pointsToState.store[lc.cmd.loc] is ScratchPointer) {
                        res.add(p)
                    } else if (lc.cmd is TACCmd.Simple.ByteLongCopy && lc.cmd.dstBase == TACKeyword.MEMORY.toVar() && pointsToDomain.pointsToState.store[lc.cmd.dstOffset] is ScratchPointer) {
                        if(TACMeta.MCOPY_BUFFER in lc.cmd.srcBase.meta) {
                            graph.commands.filter {
                                it.cmd is TACCmd.Simple.ByteLongCopy && it.cmd.dstBase == lc.cmd.srcBase
                            }.mapTo(res) { it.ptr }
                        }
                        res.add(p)
                    // the code that computes the encoded size (the subtraction command) is also part of it
                    } else if (lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lc.cmd.rhs is TACExpr.BinOp.Sub && lc.cmd.rhs.o1AsTACSymbol()
                            .let {
                                pointsToDomain.pointsToState.store[it] == ScratchPointer
                            }
                    ) {
                        res.add(p)
                    // any loop copying to the scratch is part of the encoding
                    } else if (lc.cmd is TACCmd.Simple.SummaryCmd && lc.cmd.summ is LoopCopyAnalysis.LoopCopySummary && lc.ptr.block !in pta.invalidSummaries && lc.cmd.summ.outPtr.any {
                            pointsToDomain.pointsToState.store[it] == ScratchPointer
                        }) {
                        lc.cmd.summ.summarizedBlocks.forEach {
                            graph.elab(it).commands.mapTo(res) { it.ptr }
                        }
                        res.add(p)
                    // any read of the free pointer (as scratch) is part of the encode
                    } else if (lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lc.cmd.rhs.equivSym(TACKeyword.MEM64) && pta.scratchSite[lc.ptr] != null) {
                        if (graph.cache.domination.dominates(lc.ptr, encodePoint)) {
                            res.add(p)
                        }
                    }
                    nxt.addAll(graph.pred(p).filter { predCandidate ->
                        // predCandidate should not dominate another encode that we do not also dominate
                        // \forall encode.
                        //   predCandidate `dom` encode ==> us `dom` encode
                        otherEncodes.all { otherEncode ->
                            !graph.cache.domination.dominates(predCandidate, otherEncode) ||
                                graph.cache.domination.dominates(encodePoint, otherEncode)
                        }
                    })
                }
                res.cutPrimitiveWrites(null)
            }
        }
    }
}

/**
 * Find direct accesses of calldata that are not part of any decode or encode process. For sanity sake,
 * this only finds reads of *definitely* integer fields, we explicitly exclude accesses that are looking
 * at dynamic offsets to later in the buffer.
 *
 * Q) Why don't we need something like this for in-memory decoding buffers?
 * A) Currently, you can only do a full encode of an in-memory buffer: there is no way to access a specific element
 * of a specific array encoded into a bytes buffer.
 *
 * How do we do this?
 * We use the serialization code finder to filter all definitely related reads of calldata, and examine the remaining.
 * Using the evm method type information ingested by the decoder analysis, we then look at the buffer access paths
 * read at these remaining sites and collect those that are definitely accessing an integer typed field.
 */
class DirectReadFinder(
    private val decodes: Map<CmdPointer, ABIDecodeComplete>,
    private val encodes: Map<CmdPointer, ABIEncodeComplete>,
) : SerializationCodeFinder<RelatedCommands, Unit>, PointsToQuery<Map<CmdPointer, ABIDirectRead>>() {

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    override fun RelatedCommands.commands(): Collection<CmdPointer> {
        return this.commands()
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    override fun RelatedCommands.blocks(): Collection<NBId>? {
        return this.blocks()
    }

    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Map<CmdPointer, ABIDirectRead> {
        // all of this up to the call to computeClosure is a simplification of the ABICodeFinder
        val nestedPops = pts.query(NestedAllocations) ?: return emptyMap()
        val seeds = mutableMapOf<CmdPointer, Unit>()
        val blocks = mutableMapOf<NBId, Set<Unit>>()
        val stopCutAt = mutableSetOf<CmdPointer>()

        for((loc, dec) in decodes) {
            val (cmd, stopCut, blk) = pts.query(DecodeSeedFinder(
                decodePoint = loc,
                nestedAllocationPops = nestedPops,
                decodeComplete = dec
            )) ?: return emptyMap()
            cmd.forEach {
                seeds[it] = Unit
            }
            blk.forEach {
                blocks[it] = setOf(Unit)
            }
            stopCutAt.addAll(stopCut)
        }
        for((loc, enc) in encodes) {
            val encodeSeedData = pts.query(
                EncodeSeedFinder(
                    encodeComplete = enc,
                    allEncodeCompletes = encodes.keys,
                    encodePoint = loc
                )
            ) ?: return emptyMap()
            encodeSeedData.seeds.forEach {
                seeds[it] = Unit
            }
            stopCutAt.addAll(encodeSeedData.stopCut)
        }
        val groups = getUnusedGroups(
            pts, graph
        ) ?: return mapOf()
        val code = this.computeClosure(
            seeds,
            deletedBlockSeed = blocks,
            unusedGroup = groups,
            graph = graph,
            ignoredAnnot = commonIgnoredAnnots,
        )
        /*
         * Find byteloads that are not part of the program slice
         */
        return graph.commands.stream().filter {
            it.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && it.cmd.base == TACKeyword.CALLDATA.toVar()
        }.filter {
            it.ptr !in code.foundCommands && it.ptr.block !in code.foundBlocks
        }.filter {
            // we do need some state here though
            it.ptr in pta.results
        }.mapNotNull {
            /*
              get referenced primitive path does the heavy lifting: computing the buffer access path based on the
              index being read, and determining the sort of value being accessed.
             */
            val loc = it.narrow<TACCmd.Simple.AssigningCmd.ByteLoad>().cmd.loc
            val st = pta.results[it.ptr]!!
            it `to?` pta.decoderAnalysis.getReferencedPrimitivePath(
                loc,
                st
            )
        }.filter {
            /*
              Static strides only happen during full decodes, so we don't consider them here

              XXX(jtoman): is that true???
             */
            !hasStaticStride(it.second.bufferAccessPath)
        }.map {
            it.first.ptr to ABIDirectRead(
                id = Allocator.getFreshId(Allocator.Id.ABI),
                output = it.first.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs,
                path = it.second
            )
        }.collect(Collectors.toMap({it.first}, {it.second}))
    }

    tailrec private fun hasStaticStride(v: DecoderAnalysis.BufferAccessPath) : Boolean {
        return when(v) {
            is DecoderAnalysis.BufferAccessPath.ArrayElemOf -> hasStaticStride(v.parent)
            is DecoderAnalysis.BufferAccessPath.Deref -> hasStaticStride(v.parent)
            is DecoderAnalysis.BufferAccessPath.Offset -> hasStaticStride(v.base)
            DecoderAnalysis.BufferAccessPath.Root -> false
            is DecoderAnalysis.BufferAccessPath.StaticStride -> true
        }
    }

}

private fun DecoderAnalysis.BufferAccessPath.getIndexVars() = this.referencedVariables()

/**
 * Find all code that is part of the decoding accomplished at [decodePoint],
 * [nestedAllocationPops] is a side analysis result that should be called outside this class
 * for performance (it is expected this query will be run multiple times)
 */
private class DecodeSeedFinder(
    val decodePoint: CmdPointer,
    val nestedAllocationPops: Collection<NestedAllocations.NestedAllocationPop>,
    val decodeComplete: ABIDecodeComplete
) : PointsToQuery<DecodeSeedData>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): DecodeSeedData {
        return computeInteral(pta, pts, graph) ?: DecodeSeedData(listOf<CmdPointer>(), setOf(), listOf())
    }

    fun computeInteral(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): DecodeSeedData? {
        /**
         * Using the nestedAllocationPops, query DecodeLocations to find all of the abstract locations
         * definitely part of the decode
         */
        val decodeLocations = pts.query(DecodeLocations(decodePoint, nestedAllocationPops)) ?: return null

        /**
         * Find the initialization writes for those locations
         */
        val decodeWrites = pts.query(InitializationWrites(decodeLocations)) ?: return null

        val P = decodeWrites.mapTo(mutableSetOf()) { it.ptr }
        P.add(decodePoint) // add the decode point for good measure
        val deleteBlocks = mutableSetOf<NBId>()

        val stopCutAt = mutableSetOf<CmdPointer>()

        decodeComplete.sourcePath.getIndexVars().forEach {
            graph.cache.def.defSitesOf(it, decodePoint).let(stopCutAt::addAll)
        }

        /**
         * Explicitly ignore commands used in computing the index of buffer access paths
         */

        /**
         * any update of the free pointer during the decode should also be seeded
         */
        decodeLocations.mapTo(P) {
            it.nextFPWriteCmd
        }
        /**
         * Reads of the free pointer that yield an instance of one of the related addresses should also be marked
         * (we don't traverse mem64 by default anymore, so do it explicitly here)
         */
        graph.commands.filter {
            it.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && it.cmd.rhs.equivSym(TACKeyword.MEM64) &&
                    pta.allocSites[it.ptr] in decodeLocations
        }.mapTo(P) {
            it.ptr
        }
        /**
         * As elsewhere, find loop summaries that initialize these arrays
         * Q) aren't these included in the initialization write code now?
         * A) .... uhhhh, maybe??? But this includes more commands (maybe the initialization write
         * finder should include more...)
         *
         * FIXME(jtoman): this probably can (and should?) be removed
         */
        graph.commands.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.SummaryCmd>()
        }.filter {
            val cmd = it.cmd
            cmd.summ is LoopCopyAnalysis.LoopCopySummary && pts.query(CopyLoopValid(
                v = cmd.summ, where = it.ptr
            )) != null
        }.filter { lc ->
            (lc.cmd.summ as LoopCopyAnalysis.LoopCopySummary).outPtr.any {
                pta.results[lc.ptr]?.pointsToState?.store?.get(it)?.let {
                    it as? InitializationPointer.LengthFieldInit
                }?.initAddress?.addr in decodeLocations
            }
        }.forEach {
            (it.cmd.summ as LoopCopyAnalysis.LoopCopySummary).summarizedBlocks.flatMapTo(P) {
                graph.elab(it).commands.map { it.ptr }
            }
            (it.cmd.summ as LoopCopyAnalysis.LoopCopySummary).summarizedBlocks.flatMap {
                graph.succ(it)
            }.filterTo(deleteBlocks) {
                it in graph.cache.revertBlocks
            }
            P.add(it.ptr)
        }
        return DecodeSeedData(P, stopCutAt, deleteBlocks)
    }

}

/**
 * Find those conditional jumps where one branch was NOT taken by the points to analysis, and then return which
 * branch should be statically selected
 */
data object PrunablePaths : PointsToQuery<Collection<Pair<LTACCmdView<TACCmd.Simple.JumpiCmd>, Boolean>>>() {
    override fun compute(
        pta: PointsToAnalysis,
        pts: IPointsToInformation,
        graph: TACCommandGraph
    ): Collection<Pair<LTACCmdView<TACCmd.Simple.JumpiCmd>, Boolean>> {
        return graph.blocks.parallelStream().filter {
            it.commands.last().cmd is TACCmd.Simple.JumpiCmd
        }.mapNotNull {
            val jump = it.commands.last().narrow<TACCmd.Simple.JumpiCmd>()
            val pathConditions = graph.pathConditionsOf(it.id)
            // wut??
            if(pathConditions.size != 2 || pathConditions.any { it.key in graph.cache.revertBlocks }) {
                return@mapNotNull null
            }
            /*
              We only took one branch of the conditional
             */
            val (_, pc) = pathConditions.entries.singleOrNull { (succ, _) ->
                (it.id to succ) in pta.takenEdges
            } ?: return@mapNotNull null
            check(pc is TACCommandGraph.PathCondition.ConditionalOn) {
                "At jumpi command, have successor whose path condition is not a conditional on a variable?"
            }
            when(pc) {
                is TACCommandGraph.PathCondition.EqZero -> {
                    jump to false
                }
                is TACCommandGraph.PathCondition.NonZero -> jump to true
            }
        }.toList()
    }

}

data object LengthUpdateBoundQuery : PointsToQuery<Collection<LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>>>() {
    override fun compute(pta: PointsToAnalysis, pts: IPointsToInformation, graph: TACCommandGraph): Collection<LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>> {
        return pta.pointerAnalysis.needBoundProof.map {
            graph.elab(it).narrow()
        }
    }

}
