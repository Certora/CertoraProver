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

@file:Suppress("RedundantUnitExpression")
@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)

package analysis.alloc

import analysis.*
import analysis.dataflow.IGlobalValueNumbering.Companion.findCopiesAt
import analysis.dataflow.IMustEqualsAnalysis
import analysis.dataflow.LiveVariableAnalysis
import analysis.dataflow.OnDemandMustEqualsAnalysis
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.Logger
import log.LoggerTypes
import parallel.ParallelPool
import parallel.Scheduler
import tac.NBId
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.*
import analysis.PatternMatcher.Pattern as P


private val logger = Logger(LoggerTypes.ALLOC)

data class AllocationInformation(
    val abstractAllocations: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    val scratchReads: Map<CmdPointer, Optional<BigInteger>>,
    val failureMarkers: Set<CmdPointer>,
    val initialFreePointerValue: BigInteger?
) {
    companion object {
        val EMPTY_CONCRETE = AllocationInformation(
            abstractAllocations = mapOf(),
            scratchReads = mapOf(),
            failureMarkers = setOf(),
            initialFreePointerValue = null,
        )
    }
}

internal val lower32BitMask = BigInteger(
"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe0",
16
)

object AllocationAnalysis {

    fun <T> PatternDSL.roundUp(f: () -> PatternDSL.PatternBuilder<T>) = ((f() + 0x1f()).commute.first and lower32BitMask()).commute.first

    enum class FakeAllocSort {
        ECRECOVER,
        NULL;
    }

    interface WithElementSize {
        fun getElementSize(): BigInteger
    }

    @KSerializable
    @Treapable
    sealed class Alloc : AmbiSerializable, TransformableVarEntity<Alloc> {
        @KSerializable
        data class ConstBlock(val sz: BigInteger) : Alloc()

        @KSerializable
        data class DynamicBlock(val eSz: BigInteger, val elemSym: Pair<CmdPointer, TACSymbol.Var>) : Alloc(),
            WithElementSize {
            override fun getElementSize(): BigInteger = eSz
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Alloc =
                copy(elemSym = elemSym.first to f(elemSym.second))
        }

        @KSerializable
        data class PackedByteArray(val finalWrite: CmdPointer) : Alloc(), WithElementSize {
            override fun getElementSize(): BigInteger = BigInteger.ONE
        }

        @KSerializable
        @Treapable
        data class StorageUnpack(val slotRead: Pair<CmdPointer, SizeReadSort>) : Alloc(), WithElementSize {
            override fun getElementSize(): BigInteger = BigInteger.ONE

            @KSerializable
            @Treapable
            sealed class SizeReadSort : AmbiSerializable, TransformableVarEntity<SizeReadSort> {
                @KSerializable
                data class WordLoad(val indexSym: TACSymbol) : SizeReadSort() {
                    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): SizeReadSort = copy(
                        indexSym = when (indexSym) {
                            is TACSymbol.Var -> f(indexSym)
                            is TACSymbol.Const -> indexSym
                        }
                    )
                }

                @KSerializable
                data class UnpackRead(val read: TACSymbol.Var) : SizeReadSort() {
                    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): SizeReadSort =
                        copy(read = f(read))
                }
            }

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Alloc =
                copy(slotRead = slotRead.first to slotRead.second.transformSymbols(f))
        }

        @KSerializable
        data class ConstantStringAlloc(val constLen: BigInteger, val dataCopy: CmdPointer) : Alloc(), WithElementSize {
            override fun getElementSize(): BigInteger = BigInteger.ONE
        }

        // TODO(jtoman): refactor constarrayalloc and dynamic block into a common (sealed) class
        @KSerializable
        data class ConstantArrayAlloc(val eSz: BigInteger, val constSize: BigInteger) : Alloc(), WithElementSize {
            override fun getElementSize(): BigInteger = eSz
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Alloc = this
    }

    // The index of the (pseudo-)write that reaches the read of Mem
    // writeIdx: the increment of the index that follows this read
    // [sort]: The kind of allocation, either DynamicBlock (array) or ConstBlock (struct)
    @KSerializable
    data class AbstractLocation(val prevFPWriteIdx: Int, val nextFPWriteCmd: CmdPointer, val sort: Alloc) :
        AmbiSerializable, TransformableVarEntity<AbstractLocation> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): AbstractLocation =
            copy(sort = sort.transformSymbols(f))
    }

    private val linearScale = PatternDSL.build {
        val lengthScale = (Const * Var).commute.withAction { where, eSz, nSym ->
            Alloc.DynamicBlock(eSz, where.ptr to nSym)
        } `^` (Var shl Const).withAction { where, nSym, sizeScale ->
            Alloc.DynamicBlock(eSz = BigInteger.TWO.pow(sizeScale.intValueExact()), where.ptr to nSym)
        }


        val roundUpScale = (((31() + Var).commute.locSecond / 32()).first * 32()).commute.withAction { d, _ ->
            Alloc.DynamicBlock(BigInteger.ONE, d.first.ptr to d.second)
        }

        fun lenComputation() =
            roundUpScale lor lengthScale lor roundUp {
                Var { v, where -> PatternMatcher.VariableMatch.Match(Alloc.DynamicBlock(BigInteger.ONE, where.ptr to v)) }
            }

        val lengthBumpThenRound = roundUp {
            (lenComputation() + 32()).commute.first
        }

        (!TACKeyword.MEM64.toVar() + lengthBumpThenRound).commute.second `^` commuteThree(!TACKeyword.MEM64.toVar(), 32(), lenComputation(), PatternDSL.CommutativeCombinator.add) { _, _, p ->
            p
        }
    }

    private val constantBlock = PatternDSL.build {
        val const = Const lor roundUp { Const } lor (Const and lower32BitMask()).commute.withAction { sz, _ ->
            sz and lower32BitMask
        }
        (const + !TACKeyword.MEM64.toVar()).commute.withAction { sz, _ -> Alloc.ConstBlock(sz) }
    }

    private val initAssignment = P.FromConstant(extractor = { it ->
        it.value.takeIf {
            it >= 0x80.toBigInteger() && it.mod(EVM_WORD_SIZE) == BigInteger.ZERO
        }
    }, out = { _, v -> v })

    private val singleAddition = PatternDSL.build {
        fun <T> PatternDSL.roundUp(f: () -> PatternDSL.PatternBuilder<T>) : PatternDSL.PatternBuilder<T> {
            return ((f() + 0x3f()).commute.first and lower32BitMask()).commute.first
        }
        fun lenComputation() = roundUp {
            (Var * Const).commute.withAction { where, lsym, sz ->
                Alloc.DynamicBlock(
                    sz, where.ptr to lsym
                )
            } `^` (Var shl Const).withAction { where, lsym, sizeScale ->
                Alloc.DynamicBlock(
                    BigInteger.TWO.pow(sizeScale.intValueExact()),
                    where.ptr to lsym
                )
            }
        } lor roundUp {
            ((Var { v, where ->
                PatternMatcher.VariableMatch.Match(Alloc.DynamicBlock(
                    eSz = BigInteger.ONE,
                    elemSym = where.ptr to v
                ))
            } + 31()).commute.first and lower32BitMask()).commute.first
        } lor roundUp {
            Var { v, where ->
                PatternMatcher.VariableMatch.Match(Alloc.DynamicBlock(
                    BigInteger.ONE, where.ptr to v
                ))
            }
        }
        (!TACKeyword.MEM64.toVar() + lenComputation()).commute.second
    }

    private val creationCodePattern : PatternMatcher.Pattern<Alloc> = PatternDSL.build {
        roundUp {
            ((!TACKeyword.MEM64.toVar() + 32()).commute + Const).commute.withAction { _, sz ->
                Alloc.ConstantArrayAlloc(
                    eSz = BigInteger.ONE, constSize = sz
                )
            }
        }
    }

    val arrayCreationPattern : PatternMatcher.Pattern<Alloc.DynamicBlock> get() = PatternMatcher.Pattern.XOr(
        singleAddition, { it },
        linearScale, { it }
    )


    private val freePointerSubtract = run {
        val fpRead = PatternMatcher.Pattern.FromVar(
                extractor = { w, v ->
                    if(v == TACKeyword.MEM64.toVar()) {
                        PatternMatcher.VariableMatch.Match(w)
                    } else {
                        PatternMatcher.VariableMatch.Continue
                    }
                }
        )
        val patt = PatternMatcher.Pattern.FromConstant.exactly(0x20.toBigInteger())
        PatternMatcher.Pattern.FromBinOp.from(
                t = TACExpr.BinOp.Sub::class.java,
                p1 = fpRead,
                p2 = patt,
                comb = { subLoc, readLoc, _->
                    subLoc to readLoc
                }
        )
    }

    private val fpMatcher = PatternDSL.build {
        TACKeyword.MEM64.toVar().withLocation
    }

    private val anyMatch = P.XOr.orSame(
        constantBlock,
        linearScale,
        singleAddition,
        creationCodePattern
    )

    fun <R> PatternMatcher.compilePatternDirectFPReadsOnly(graph: TACCommandGraph, patt: PatternMatcher.Pattern<R>) =
        compilePattern(graph, patt) {
            // Don't traverse through the FP for this pattern; we're only interested in direct reads of the current FP
            it != TACKeyword.MEM64.toVar()
        }

    private fun runAbstractLocationAnalysis(graph: TACCommandGraph): AllocationInformation? {
        val (blockStartToNumber, writeNumbering) = numberWrites(graph)
        // now check write dominance. This property states that all reads must be dominated by a unique write
        // first, for every command, we compute the SSA number of the write that reaches that command
        val reachingWriteId = computeReachingWrites(graph, blockStartToNumber, writeNumbering)
                ?: run {
            logger.warn { "In ${graph.name}: Failed to compute reaching write ids, found a read before a numbered write" }
            return null
        }

        val failureMarkers = mutableSetOf<CmdPointer>()

        val subtractMatcher = PatternMatcher.compilePattern(graph, freePointerSubtract)

        val subtraction = graph.commands.mapNotNull {
            if(it.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && it.cmd.rhs is TACExpr.BinOp.Sub) {
                subtractMatcher.queryFrom(it.narrow()).toNullableResult()
            } else {
                null
            }
        }

        val fakeAllocWriteLoc = mutableMapOf<CmdPointer, FakeAllocSort>()
        val fakeAllocWriteNumbers = mutableSetOf<Int>()

        val byte32Alloc by lazy {
            PatternMatcher.compilePattern(graph, constantBlock)
        }

        val fakeAllocReads = mutableSetOf<CmdPointer>()
        val scratchOffsets = mutableMapOf<CmdPointer, BigInteger>()

        for((sub, readLoc) in subtraction) {
            if(sub.ptr.block in graph.cache.revertBlocks) {
                continue
            }
            fakeAllocReads.add(readLoc.ptr)
            scratchOffsets.put(readLoc.ptr, 0x20.toBigInteger())
            val reachingWrite =
                reachingWriteId[sub.ptr] ?: (run {
                    logger.error { "In ${graph.name}: Did not find reaching write for free pointer subtraction $sub" }
                    failureMarkers.add(sub.ptr)
                    null
                }) ?: continue
            val l =
                writeNumbering.entries.firstOrNull {
                    it.value == reachingWrite
                } ?: (run {
                logger.error { "In ${graph.name}: Found subtraction $sub with free pointer that does not have definitely dominating write" }
                failureMarkers.add(sub.ptr)
                null
            })?: continue
            // is the definite preceding write an allocation of 32 bytes?
            val lt = graph.elab(l.key).narrow<TACCmd.Simple.AssigningCmd>()
            val r = byte32Alloc.queryFrom(lt)
            if (r !is PatternMatcher.ConstLattice.Match || r.data.sz != 32.toBigInteger()) {
                logger.error { "In ${graph.name}: Preceding alloc $lt was not a 32 byte allocation (for subtraction $sub)" }
                failureMarkers.add(sub.ptr)
                continue
            } else {
                fakeAllocWriteLoc.put(l.key, FakeAllocSort.ECRECOVER)
                fakeAllocWriteNumbers.add(l.value)
            }
        }

        val fpWrites = graph.commands.filter { writeLoc ->
            writeLoc.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.cmd?.lhs == TACKeyword.MEM64.toVar()
        }

        val nullAllocationMatcher = PatternMatcher.compilePattern(graph, fpMatcher)

        fpWrites.filter {
            it.ptr !in fakeAllocWriteLoc
        }.mapNotNull {
            it `to?` nullAllocationMatcher.queryFrom(it.narrow()).toNullableResult()
        }.filter {
            reachingWriteId[it.first.ptr] == reachingWriteId[it.second]
        }.forEach {
            fakeAllocWriteLoc.put(it.first.ptr, FakeAllocSort.NULL)
        }



        val liveVariableAnalysis = graph.cache.lva


        val initWriteMatcher = PatternMatcher.compilePattern(graph, initAssignment)
        val mustRevert = graph.rootBlocks.all {
            it.id in graph.cache.revertBlocks
        }

        val (initWrites, remaining) = graph.commands.filter(::isFreePointerWrite).filter {
            it.ptr !in fakeAllocWriteLoc &&
                // if this function must not revert and this is a block that reverts, bail
                (it.ptr.block !in graph.cache.revertBlocks || mustRevert)
        }.asIterable().partitionMap { cmd ->
            val assignCmd = cmd.narrow<TACCmd.Simple.AssigningCmd>()
            initWriteMatcher.queryFrom(assignCmd).toNullableResult()?.let {
                Either.Left(assignCmd to it)
            } ?: Either.Right(assignCmd)
        }

        val (initWrite, initialFreePointerValue) = initWrites.singleOrNull() ?: run {
            logger.warn { "In ${graph.name}: Found multiple (or zero) initial writes: $initWrites" }
            return null
        }

        val isDominatingInit = graph.scope {
            initWrite.ptr.block.pred().isEmpty() &&
                graph.iterateUntil(initWrite.ptr).all {
                    it.cmd !is TACCmd.Simple.AssigningCmd.ByteStore &&
                        it.cmd !is TACCmd.Simple.AssigningCmd.ByteLoad &&
                        (it.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || it.cmd.lhs != TACKeyword.MEM64.toVar())
                }
        }

        if(!isDominatingInit) {
            logger.warn { "In ${graph.name}: Initial write was not the first read we've seen or is not in the root block" }
            logger.warn { graph.predBlock(initWrite.ptr.block).toString() }
            logger.warn { graph.elab(initWrite.ptr.block).commands.toString() }
            return null
        }

        val (postDom, freeReads, mustByteAlloc, failSites) = ScratchPointerAnalysis.analyze(
                graph, liveVariableAnalysis,
                reachingWriteId,
                scratchOffsets,
                fakeAllocReads,
                fakeAllocWriteLoc,
                initialFreePointerValue
        )

        failureMarkers.addAll(failSites)

        val postDominated = postDom.mapValues {
            check(it.value in writeNumbering)
            writeNumbering[it.value]!!
        }

        for(p in freeReads) {
            if(reachingWriteId[p] in fakeAllocWriteNumbers) {
                scratchOffsets[p] = 0x20.toBigInteger()
            }
        }

        // at this point, all (non-free) reads must be post dominated by a single, unique write. Let us now classify those writes

        val patt = PatternMatcher.compilePatternDirectFPReadsOnly(graph, anyMatch)
        val stringRes = analyzeStrings(graph)
        val (stringAlloc, rem) = remaining
            .partition {
                it.ptr in stringRes
            }
        val constantDynamicPack = mutableMapOf<CmdPointer, Pair<BigInteger, Alloc.PackedByteArray>>()
        val consistentPatt = rem.map {
            val x = patt.queryFrom(it).toNullableResult()?.let { res ->
                if(res is Alloc.ConstBlock) {
                    constantArrayAllocHeuristic(graph, it, res).also { rewrite ->
                        if(rewrite is Alloc.PackedByteArray) {
                            constantDynamicPack[it.ptr] = res.sz to rewrite
                        }
                    }
                } else if(res is Alloc.DynamicBlock && res.eSz != BigInteger.ONE) {
                    val finalWrite = it.cmd.let { it as? TACCmd.Simple.AssigningCmd.AssignExpCmd }?.let {
                        it.rhs as? TACExpr.Sym.Var
                    }?.s
                    if(finalWrite != null && checkPackedBytePenultimateWrite(graph, it.wrapped, me = OnDemandMustEqualsAnalysis(graph), freePointerValue = finalWrite)) {
                        Alloc.PackedByteArray(
                                finalWrite = it.ptr
                        )
                    } else {
                        res
                    }
                } else {
                    res
                }
            }
            it to x
        }

        /*
          Reads that are likely misclassified as scratch uses that are actually part of a constant sized packed allocation.
          In the following:
          v1 = fp
          mem[v1 + 32] = c1
          v2 = fp
          mem[v2 + 64] = c2
          mem[fp] = 64
          fp = fp + 96

          The reads at v1 and v2 are considered scratch, because they do not reach the update of the free pointer.
          However, we catch when we have detected a constant sized, packed array allocation (see above) and then look for
          preceding writes that (likely) were part of this "inlined" initialization. This heuristic is very simple:
          we simply look for all writes that are:
          1. in the same block as the free pointer write
          2. Precede the update of the FP without any intervening updates of the FP
          3. are derived from the same incarnation of the free pointer that is used in the eventual update

          These are then recorded in packedReads. Note that this requires later adjustment of the freeReads (which we are
          "stealing from") and an adjustment of postDom (because these reads weren't technically paired up with a write)
         */
        val packedReads = mutableMapOf<CmdPointer, Alloc.PackedByteArray>()
        for((fpWrite, sz) in constantDynamicPack) {
            val reachingId = reachingWriteId[fpWrite]!!
            val precedingReads = graph.iterateUntil(fpWrite).reversed().takeWhile { !isFreePointerWrite(it) }.filter {
                it.ptr in freeReads
            }
            fun isInitializationWrite(l: LTACCmd, ind: TACSymbol.Var) : Boolean =
                l.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.let {
                    it.cmd.base == TACKeyword.MEMORY.toVar() &&
                            it.cmd.loc == ind &&
                            reachingWriteId[it.ptr] == reachingId &&
                            it.ptr.pos < fpWrite.pos &&
                            fpWrite.block == it.ptr.block
                } ?: false
            for(prc in precedingReads) {
                check(prc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && prc.cmd.rhs.equivSym(TACKeyword.MEM64))
                val use = graph.cache.use.useSitesAfter(prc.cmd.lhs, prc.ptr).singleOrNull()?.let(graph::elab) ?: continue
                if(isInitializationWrite(use, prc.cmd.lhs)) {
                    packedReads[prc.ptr] = sz.second
                } else if(use.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && use.cmd.rhs is TACExpr.Vec && use.cmd.rhs.operandsAreSyms() && use.cmd.rhs.ls.all {
                        it.equivSym(prc.cmd.lhs) || (it is TACExpr.Sym.Const && it.s.value < sz.first)
                    }) {
                    val additionUse = graph.cache.use.useSitesAfter(use.cmd.lhs, use.ptr).singleOrNull()?.let(graph::elab) ?: continue
                    if(isInitializationWrite(additionUse, use.cmd.lhs)) {
                        packedReads[prc.ptr] = sz.second
                    }
                }
            }
        }

        val (simplePatt_, noMatch) = consistentPatt.partition { it.second != null }

        val simplePatt = simplePatt_.map {
            it.first to it.second!!
        }

        // We now check whether the match failures are all byte array allocations
        val byteAllocs = if (noMatch.isEmpty()) {
            setOf()
        } else {
            val failingByteAllocs = checkByteAllocations(graph, noMatch.map { it.first.ptr to writeNumbering[it.first.ptr]!! }.toMap(), liveVariableAnalysis, postDominated)
            if(failingByteAllocs.isNotEmpty()) {
                logger.warn {
                    "In ${graph.name}: Some non-matching allocations (${failingByteAllocs.take(10)}...) did not match the (packed) byte allocation pattern. Marking as failures"
                }
            }
            failureMarkers.addAll(failingByteAllocs)
            noMatch.filter {
                it.first.ptr !in failingByteAllocs
            }.map {
                it.first.ptr
            }.toSet()
        }

        if(!mustByteAlloc.all {
                    it in byteAllocs || graph.elab(it).cmd.meta.containsKey(TACMeta.INLINE_ALLOC)
                }) {
            logger.warn {
                "In ${graph.name}: Assumed that $mustByteAlloc were all byte array allocations, but instead found $byteAllocs"
            }
            for (c in mustByteAlloc) {
                if (c !in byteAllocs) {
                    failureMarkers.add(c)
                }
            }
        }
        val writeTypes = simplePatt.map {
            it.first.ptr to it.second
        }.toMap() + packedReads + byteAllocs.map { it to Alloc.PackedByteArray(it) }.toMap() + stringAlloc.map {
            it.ptr to stringRes[it.ptr]!!
        }

        val scratchWithOffsets = freeReads.filter {
            /* skip scratch reads that have been reclassified as part of a constant-sized packed allocation */
            it !in packedReads
        }.map {
            it to Optional.of(scratchOffsets[it] ?: BigInteger.ZERO)
        }.toMap()

        val locationUniverse = makeAllocLocations(
            reachingWriteId,
            postDominated + packedReads.mapValues { writeNumbering[it.value.finalWrite]!! }, // pair the reclassified scratch reads with the eventual write number
            writeNumbering,
            writeTypes
        )

        return AllocationInformation(
            abstractAllocations = locationUniverse,
            scratchReads = scratchWithOffsets,
            failureMarkers = failureMarkers,
            initialFreePointerValue = initialFreePointerValue,
        )
    }

    fun runAnalysis(graph: TACCommandGraph): AllocationInformation? {
        val concreteAllocAnalysisResult = ConcreteAllocAnalysis.runAnalysis(graph)
        val info = runAbstractLocationAnalysis(graph)
            ?: AllocationInformation.EMPTY_CONCRETE.takeIf { concreteAllocAnalysisResult }
            ?: return null
        return AllocationInformation(
            abstractAllocations = info.abstractAllocations,
            scratchReads = info.scratchReads,
            failureMarkers = info.failureMarkers,
            initialFreePointerValue = info.initialFreePointerValue,
        )
    }

    private fun findPrecedingDefiniteWrite(
        graph: TACCommandGraph,
        failedMatch: LTACCmdView<TACCmd.Simple.AssigningCmd>
    ) : Pair<CmdPointer, LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>>? {
        val finalSym = failedMatch.cmd.let {
            it as? TACCmd.Simple.AssigningCmd.AssignExpCmd
        }?.rhs?.let {
            it as? TACExpr.Sym.Var
        }?.s

        val setAtBlockStart = graph.iterateUntil(failedMatch.ptr).all {
            !isFreePointerWrite(it) && !isFreePointerRead(it) && it.cmd !is TACCmd.Simple.DirectMemoryAccessCmd &&
                it.cmd.getLhs() != finalSym
        }
        /*
          Look in the preceding block. This works around the solidity compiler adding a condition check to make sure they
          aren't overflowing the free pointer :(
         */
        val startPoint = if(setAtBlockStart) {
            // multiple predecessors? no thanks
            val predBlock = graph.pred(failedMatch.ptr.block).singleOrNull()?.takeIf { pred ->
                graph.succ(pred).all {
                    it == failedMatch.ptr.block || it in graph.cache.revertBlocks
                }
            } ?: return null
            graph.elab(predBlock).commands.last().ptr
        } else {
            failedMatch.ptr
        }
        val precedingWrite = graph.iterateUntil(startPoint).reversed().takeWhile { !isFreePointerWrite(it) }.firstMapped {
            if (it.cmd is TACCmd.Simple.AssigningCmd.ByteStore && it.cmd.base == TACKeyword.MEMORY.toVar()) {
                it.narrow<TACCmd.Simple.AssigningCmd.ByteStore>()
            } else {
                null
            }
        }
        return startPoint to (precedingWrite ?: return null)
    }

    private fun precedingDefiniteWriteHeuristic(
        graph: TACCommandGraph,
        fpUpdate: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        data: Alloc.ConstBlock,
    ): Alloc? {
        val (startPoint, precedingWrite) = findPrecedingDefiniteWrite(graph, fpUpdate) ?: return null
        val constantAnalysis = MustBeConstantAnalysis(graph, NonTrivialDefAnalysis(graph))
        // We think this is a constant array alloc when we wrote the size of the array to the (about to be) old value of
        // the free pointer.
        if(precedingWrite.cmd.loc !in graph.cache.gvn.findCopiesAt(precedingWrite.ptr, startPoint to TACKeyword.MEM64.toVar())) {
            return null
        }
        val writtenConst = constantAnalysis.mustBeConstantAt(precedingWrite.ptr, precedingWrite.cmd.value) ?: return data
        if((writtenConst * 0x20.toBigInteger() + 0x20.toBigInteger()) != data.sz) {
            // rounded up block, "true" constant block
            return if((writtenConst + 31.toBigInteger()).andNot(31.toBigInteger()) + EVM_WORD_SIZE == data.sz) {
                Alloc.ConstantArrayAlloc(
                    constSize = writtenConst,
                    eSz = BigInteger.ONE
                )
            } else if(writtenConst + EVM_WORD_SIZE == data.sz) {
                Alloc.PackedByteArray(
                    finalWrite = fpUpdate.ptr
                )
            } else {
                // if we see a write that is *definitely* not a length, return non-null here, short-circuiting later checks
                data
            }
        }
        return Alloc.ConstantArrayAlloc(
            constSize = writtenConst,
            eSz = EVM_WORD_SIZE
        )
    }

    private fun TACCommandGraph.blockCommandsForwardIgnoreRevert(ptr: CmdPointer) : Sequence<LTACCmd> {
        return sequence {
            for(c in this@blockCommandsForwardIgnoreRevert.blockCmdsForwardFrom(ptr)) {
                yield(c)
            }
            val rev = cache.revertBlocks
            var nxt = succ(ptr.block).singleOrNull {
                it !in rev
            }
            val visited = treapSetBuilderOf<NBId>()
            while(nxt != null) {
                if(!visited.add(nxt)) {
                    return@sequence
                }
                elab(nxt).commands.forEach {
                    yield(it)
                }
                nxt = succ(nxt).singleOrNull {
                    it !in rev
                }
            }
        }
    }

    /**
     *  If we have
     *    tacM0x40 = V ([fpUpdate])
     *    ...
     *    tacM[ptr] = sz
     *
     *  And, if
     *    - ptr is actually the value of tacM0x40 *before* storing V
     *    - sz is a constant s.t. sz*32 + 32 = [data.sz]
     *
     *  Then we are probably storing the (constant) size of the array when we write sz,
     *  so we should treat the allocation in question as a const array
     */
    private fun lengthPostWriteHeuristic(
        graph: TACCommandGraph,
        fpUpdate: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        data: Alloc.ConstBlock
    ): Alloc? {
        val byteStore = graph.blockCmdsForwardFrom(fpUpdate.ptr).firstNotNullOfOrNull { c ->
            c.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()
        } ?: return null
        val constValue = MustBeConstantAnalysis(graph, NonTrivialDefAnalysis(graph)).mustBeConstantAt(byteStore.ptr, byteStore.cmd.value) ?: return null
        if(BigInteger.ZERO == constValue || TACKeyword.MEM64.toVar() !in graph.cache.gvn.findCopiesAt(fpUpdate.ptr,byteStore.ptr to byteStore.cmd.loc)) {
            return null
        }
        /*
         * If we are allocing a constant sized byte-sized array (which has not already been caught by the string heuristic, harumph)
         * then we insist on finding a bytelong copy for init.
         *
         * Otherwise we end up with something cursed happening like:
         * ```
         * struct Foo {
         *    uint a;
         *    address whatever;
         * }
         *
         * Foo memory x = Foo{ a: 8, address: ... };
         * ```
         *
         * Where `x` gets classified as a constant alloc (8 rounded up to nearest word + 32 == 64 == sizeOf(Foo) and
         * 8 is written in the first word post alloc...)
         */
        if((constValue + 31.toBigInteger()).andNot(31.toBigInteger()) + EVM_WORD_SIZE == data.sz) {
            val seenInit = graph.succ(byteStore.ptr).singleOrNull()?.let {
                graph.blockCommandsForwardIgnoreRevert(it)
            }?.takeWhile {
                it.cmd.getLhs() != TACKeyword.MEM64.toVar() && it.cmd !is TACCmd.Simple.AssigningCmd.ByteLoad && it.cmd !is TACCmd.Simple.AssigningCmd.ByteStore
            }?.firstOrNull {
                 it.cmd is TACCmd.Simple.ByteLongCopy
            }?.let {
                it.cmd is TACCmd.Simple.ByteLongCopy && it.cmd.length == constValue.asTACSymbol()
            } == true
            // we have extremely strong evidence this is a byte array alloc, namely that we have seen a revert-free
            // path to a long copy of exactly n bytes. choose that interpretation
            if (seenInit) {
                return Alloc.ConstantArrayAlloc(
                    constSize = constValue,
                    eSz = BigInteger.ONE
                )
            }
        }
        if(constValue * EVM_WORD_SIZE + EVM_WORD_SIZE == data.sz) {
            // insist that we don't see any other writes into memory in this block.
            // if we do *not* have this property, this *strongly* suggests we are seeing sequential field
            // initialization, and we should not interpret this as a constant array
            // when can we get multiple successors for a pointer? when it's at the end of the block, meaning
            // that the property above is trivially satisfied.
            val noWritesBeforeBlockEndOrCopy = graph.succ(byteStore.ptr).singleOrNull()?.let {
                graph.blockCmdsForwardFrom(it).takeWhile {
                    it.cmd !is TACCmd.Simple.ByteLongCopy
                }.none {
                    it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                }
            }
            if(noWritesBeforeBlockEndOrCopy != false) {
                return Alloc.ConstantArrayAlloc(
                    constSize = constValue,
                    eSz = EVM_WORD_SIZE
                )
            }
        }
        return null
    }

    private fun packedByteArrayHeuristic(
        graph: TACCommandGraph,
        fpUpdate: LTACCmdView<TACCmd.Simple.AssigningCmd>,
    ): Alloc.PackedByteArray? {
        val finalSym = fpUpdate.cmd.let {
            it as? TACCmd.Simple.AssigningCmd.AssignExpCmd
        }?.rhs?.let {
            it as? TACExpr.Sym.Var
        }?.s ?: return null

        return if (checkPackedBytePenultimateWrite(graph = graph, freePointerValue = finalSym, me = graph.cache.gvn, noMatch = fpUpdate.wrapped)) {
            Alloc.PackedByteArray(
                finalWrite = fpUpdate.ptr
            )
        } else {
            null
        }
    }

    private fun constantArrayAllocHeuristic(
        graph: TACCommandGraph,
        fpUpdate: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        data: Alloc.ConstBlock
    ): Alloc {
        return packedByteArrayHeuristic(graph, fpUpdate)
            ?: precedingDefiniteWriteHeuristic(graph, fpUpdate, data)
            ?: lengthPostWriteHeuristic(graph, fpUpdate, data)
            ?: data
    }

    private fun analyzeStrings(graph: TACCommandGraph) : Map<CmdPointer, Alloc> {
        val storage = Scheduler.compute {
            StringStorageCopyChecker.analyzeStringAllocations(graph).toMutableMap<CmdPointer, Alloc>()
        }
        val constant = Scheduler.compute {
            ConstantStringAlloc.findConstantStringAlloc(graph)
        }
        return storage.parallelBind(constant) { mutStorage, c ->
            for((k, v) in c) {
                mutStorage[k] = v
            }
            complete(mutStorage)
        }.let { work ->
            ParallelPool.runInherit(work, ParallelPool.SpawnPolicy.GLOBAL)
        }
    }

    private fun computeReachingWrites(graph: TACCommandGraph, blockStartToNumber: Map<NBId, Int>, writeNumbering: Map<CmdPointer, Int>): Map<CmdPointer, Int>? {
        return object : VisitingWorklistIteration<Pair<NBId, Int?>, Map<CmdPointer, Int>, Map<CmdPointer, Int>?>() {
            override fun reduce(results: List<Map<CmdPointer, Int>>): Map<CmdPointer, Int> {
                if(results.isEmpty()) {
                    return mapOf()
                }
                val toRet = mutableMapOf<CmdPointer, Int>()
                for(m in results) {
                    toRet.putAll(m)
                }
                return toRet
            }

            override fun process(it: Pair<NBId, Int?>): StepResult<Pair<NBId, Int?>, Map<CmdPointer, Int>, Map<CmdPointer, Int>?> {
                val block = graph.elab(it.first)
                var state = it.second

                val toReturn = mutableMapOf<CmdPointer, Int>()
                for (command in block.commands) {
                    if(state != null) {
                        toReturn[command.ptr] = state
                    }
                    if (isFreePointerRead(command)) {
                        if (state == null) {
                            logger.warn {
                                "In ${graph.name}: Found a read at $command but there was no preceding write id"
                            }
                            return StepResult.StopWith(null)
                        }
                    }
                    if (isFreePointerWrite(command)) {
                        ptaInvariant(command.ptr in writeNumbering) {
                            "FATAL: In ${graph.name}: found a of free pointer at $command which did not have a number in $writeNumbering"
                        }
                        state = writeNumbering[command.ptr]!!
                    }
                }
                val next = graph.succBlock(it.first).map {
                    if(it.id in blockStartToNumber) {
                        it.id to blockStartToNumber[it.id]!!
                    } else {
                        it.id to state
                    }
                }
                return StepResult.Ok(next, listOf(toReturn))
            }
        }.submit(graph.rootBlocks.map { it.id to null })
    }

    private fun numberWrites(graph: TACCommandGraph): Pair<Map<NBId, Int>, Map<CmdPointer, Int>> {
        // use an SSA numbering on mem0x40 for abstract locations in mem
        val dominanceFrontier = graph.cache.domination.dominanceFrontiers
        var numbering = 0
        val blockStartToNumber = mutableMapOf<NBId, Int>()
        val writeNumbering: MutableMap<CmdPointer, Int> = mutableMapOf()
        graph.blocks.forEach {
            it.commands.forEach {
                if (it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs == TACKeyword.MEM64.toVar()) {
                    writeNumbering[it.ptr] = numbering++
                    dominanceFrontier.getOrDefault(it.ptr.block, setOf()).forEach { frontier ->
                        if (frontier !in blockStartToNumber) {
                            blockStartToNumber[frontier] = numbering++
                        }
                    }
                }
            }
        }
        return Pair(blockStartToNumber, writeNumbering)
    }


    /**
      Returns a set of [CmdPointer] for writes that failed to match the pattern
     */
    private fun checkByteAllocations(graph: TACCommandGraph, noMatchWrite: Map<CmdPointer, Int>, liveVariableAnalysis: LiveVariableAnalysis, postDominated: Map<CmdPointer, Int>): Set<CmdPointer> {
        return noMatchWrite.filterNot {
                val postDomReads = postDominated.entries.filter { kv -> kv.value == it.value }.map { it.key }.toSet()
                checkByteAllocations(graph.elab(it.key), liveVariableAnalysis, OnDemandMustEqualsAnalysis(graph), postDomReads, graph)
            }.map {
                it.key
            }.toSet()
    }

    private val fpOrArrayBase = PatternDSL.build {
        !TACKeyword.MEM64.toVar() `^` (EVM_WORD_SIZE() + !TACKeyword.MEM64.toVar()).commute.second
    }


    private fun checkByteAllocations(noMatch: LTACCmd, lv: LiveVariableAnalysis, me: IMustEqualsAnalysis, reads: Set<CmdPointer>, graph: TACCommandGraph): Boolean {
        logger.debug { "Checking byte allocation for $noMatch, reads: $reads" }
        val state = object : FreePointerAnalysis(graph) {
            override fun stop(where: LTACCmd): Boolean =
                where.ptr == noMatch.ptr
        }.doMayMustAnalysis(reads) ?: run {
            logger.warn { "In ${graph.name}: Free pointer analysis found unsafe pointer usage while checking write at $noMatch" }
            logger.warn { "FP reads: $reads" }
            return false
        }


        if(noMatch.ptr !in state) {
            logger.warn {
                "In ${graph.name}: The free pointer analysis never reached the write at $noMatch."
            }
            return false
        }
        val (mayTaintVars, mustTaintVars) = state[noMatch.ptr]!!
        if (noMatch.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || noMatch.cmd.rhs !is TACExpr.Sym.Var || noMatch.cmd.lhs != TACKeyword.MEM64.toVar()) {
            logger.warn {
                "In ${graph.name}: The final write at $noMatch did not match out expected format. It wasn't an assignment, lhs/rhs" +
                    "were wrong"
            }
            return false
        }
        val writtenVal: TACSymbol.Var = noMatch.cmd.rhs.s
        if(writtenVal !in mustTaintVars) {
            logger.warn {
                "In ${graph.name}: The write at $noMatch writes a value $writtenVal which may not be derived from the free pointer"
            }
            return false
        }
        val canBeLivePostAllocationMatcher = PatternMatcher.compilePatternDirectFPReadsOnly(graph, fpOrArrayBase)
        val allowedToBeLive = { v: TACSymbol.Var ->
            canBeLivePostAllocationMatcher.query(v, noMatch) is PatternMatcher.ConstLattice.Match
        }
        val allBase = lv.liveVariablesAfter(noMatch.ptr).intersect(mayTaintVars).all(allowedToBeLive)
        if (!allBase) {
            logger.warn {
                "In ${graph.name}: There exist live variables at the FP write at $noMatch which are not equal to the old FP value"
            }
            logger.warn {
                lv.liveVariablesAfter(noMatch.ptr).intersect(mayTaintVars).filter {
                    !allowedToBeLive(it)
                }.toString()
            }
            return false
        }
        // now check that we have that all reads of the free pointer are either immediately
        return checkPackedBytePenultimateWrite(graph, noMatch, me, writtenVal)
    }

    private fun checkPackedBytePenultimateWrite(graph: TACCommandGraph, noMatch: LTACCmd, me: IMustEqualsAnalysis, freePointerValue: TACSymbol.Var) : Boolean {
        val (startPoint, precedingWrite) = findPrecedingDefiniteWrite(
            graph = graph,
            failedMatch = noMatch.narrow()
        ) ?: return run {
            logger.debug {
                "In ${graph.name}: Failed to find a penultimate write within this block (${noMatch.ptr.block}) for write ${noMatch.ptr}. Did we not write the length?"
            }
            false
        }
        val store: TACCmd.Simple.AssigningCmd.ByteStore = precedingWrite.cmd
        if(store.value !is TACSymbol.Var || store.loc !is TACSymbol.Var || TACKeyword.MEM64.toVar() !in me.equivBefore(startPoint, store.loc)) {
            logger.debug {
                "In ${graph.name}: Penultimate write $precedingWrite did not have the expected shape of arguments"
            }
            logger.debug {
                "${store.value} & ${store.loc}"
            }
            return false
        }
        return isPointerArithmeticLength(graph, startPoint, freePointerValue, precedingWrite)
    }

    /**
     * Detect whether [penultimateWrite] writes a value at the free pointer which defines the length of an array
     * whose end is at [freePointerValue]. That is, if we have:
     *
     * ```
     * mem[v1] = e
     * fp = freePointerValue
     * ```
     *
     * Then is the write of e (which is [penultimateWrite]) the length of an array that is being allocated by
     * assigning [freePointerValue] to `fp`.
     *
     * In other words, does `e == freePointerValue - fp - 32` (modulo some rounding nonsense).
     */
    private fun isPointerArithmeticLength(
        graph: TACCommandGraph,
        startPoint: CmdPointer,
        freePointerValue: TACSymbol.Var,
        penultimateWrite: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>
    ): Boolean {

        /**
         * A value that must equal the free pointer
         */
        fun PatternDSL.fpMatcher() = !TACKeyword.MEM64.toVar()

        /**
         * Match a variable that must be equal to the value that is eventually assigned to the free pointer
         */
        fun PatternDSL.endMatcher() = Var { v, where ->
            if (v in graph.cache.gvn.findCopiesAt(where.ptr, startPoint to freePointerValue)) {
                PatternMatcher.VariableMatch.Match(Unit)
            } else {
                PatternMatcher.VariableMatch.NoMatch
            }
        }

        /**
         * This is the simple pattern, if we have:
         * ```
         * mem[fp] = v
         * ```
         * then see if v is defined as `(freePointerValue - fp) - 32` (associating as necessary)
         */
        val finalWritePattern = PatternDSL.build {
            ((endMatcher() - fpMatcher()) - 32()).withAction {  _, _ -> Unit } `^`
                    ((endMatcher() - 32()) - fpMatcher()).withAction { _, _ -> Unit }
        }
        val toRet = PatternMatcher.compilePattern(graph, finalWritePattern).query(penultimateWrite.cmd.value as TACSymbol.Var, penultimateWrite.wrapped) is PatternMatcher.ConstLattice.Match

        /**
         * There are yet more possible patterns than the simple: `freePointerValue - fp - 32` above. One example is when
         * we have:
         *
         * ```
         * mem[fp] = len
         * freePointerValue = fp + roundUp(32 + len)
         * ```
         *
         * This pattern detects this case by matching against `freePointerValue`
         */
        val alternatePattern = PatternDSL.build {
            (!TACKeyword.MEM64.toVar() + roundUp {
                (EVM_WORD_SIZE() + Var { v, where ->
                    if(v in graph.cache.gvn.findCopiesAt(where.ptr, penultimateWrite.ptr to penultimateWrite.cmd.value as TACSymbol.Var)) {
                        PatternMatcher.VariableMatch.Match(Unit)
                    } else {
                        PatternMatcher.VariableMatch.NoMatch
                    }
                }).commute.first
            }).commute.second
        }
        val newValueIsBumpedLength by lazy {
            PatternMatcher.compilePattern(graph, alternatePattern).query(freePointerValue, graph.elab(startPoint)) is PatternMatcher.ConstLattice.Match
        }

        /**
         * Let's get weirder!
         * We may see the following:
         *
         * ```
         * freePointerValue = fp + roundUp(t - fp)
         * ```
         *
         * That is, [freePointerValue] is defined by rounding up the difference between `t` and the free pointer to the
         * nearest word (aside: if fp is already word aligned, I think basic math tells us this is equivalent to just `roundUp(t)` but
         * don't tell the solidity devs that).
         *
         * Then `t` is the "true" end of the data segment, and the length is computed w.r.t to that location. that is, we need to determine
         * that at
         * ```
         * mem[fp] = l
         * ```
         * that `l = (t - fp) + 32` (associating as necessary).
         *
         * In other words, we need to check whether we have the following:
         * ```
         * freePointerValue = roundUp(t - fp) + fp
         * mem[fp] = t - (fp + 32)
         * ```
         */
        val newValueIsBumpedLengthAlt by lazy {
            val trueEndVarPatt = PatternDSL.build {
                (fpMatcher() + roundUp {
                    (Var { v, where ->
                        PatternMatcher.VariableMatch.Match(where.ptr to v)
                    } - fpMatcher()).first
                }).commute.second
            }

            /**
             * Find `t` in the above, the "true" end point (before rounding up).
             */
            val trueEnd = PatternMatcher.compilePattern(graph, trueEndVarPatt).query(q = freePointerValue, src = graph.elab(startPoint)).toNullableResult() ?: return@lazy false

            /**
             * Now see if we write a length that is computed w.r.t. this t
             */
            val lengthIsCorrect = PatternDSL.build {
                fun trueEndVar() = Var { v, where ->
                    if(v in graph.cache.gvn.findCopiesAt(where.ptr, source = trueEnd)) {
                        PatternMatcher.VariableMatch.Match(Unit)
                    } else {
                        PatternMatcher.VariableMatch.NoMatch
                    }
                }
                (trueEndVar() - (fpMatcher() + EVM_WORD_SIZE()).commute).first `^` ((trueEndVar() - fpMatcher()) - EVM_WORD_SIZE()).second
            }
            PatternMatcher.compilePattern(graph, lengthIsCorrect).query(penultimateWrite.cmd.value as TACSymbol.Var, penultimateWrite.wrapped) is PatternMatcher.ConstLattice.Match
        }

        if (!toRet && !newValueIsBumpedLength && !newValueIsBumpedLengthAlt) {
            logger.debug { "In ${graph.name}: The value written at $penultimateWrite did not match the length pattern, expected final size is $freePointerValue" }
        }
        return toRet || newValueIsBumpedLength || newValueIsBumpedLengthAlt
    }

    private fun makeAllocLocations(reachingWriteId: Map<CmdPointer, Int>,
                                   readPostDom: Map<CmdPointer, Int>,
                                   writeNumbering: Map<CmdPointer, Int>,
                                   writeTypes: Map<CmdPointer, Alloc>) : Map<CmdPointer, AbstractLocation> =
        readPostDom.mapNotNull { kv ->
            val writeLocation = writeNumbering.entries.find { it.value == kv.value }!!.key
            if(writeLocation !in writeTypes) {
                null
            } else {
                val alloc = writeTypes[writeLocation]!!
                kv.key to AbstractLocation(prevFPWriteIdx = reachingWriteId[kv.key]!!, nextFPWriteCmd = writeLocation, sort = alloc)
            }
        }.toMap()
}

internal fun isFreePointerRead(lc: LTACCmd) = TACKeyword.MEM64.toVar() in lc.cmd.getRhs() && !FreePointerAnalysis.isOpaqueEVMOp(lc)
internal fun isFreePointerWrite(nxt: LTACCmd): Boolean =
        nxt.cmd is TACCmd.Simple.AssigningCmd && nxt.cmd.lhs == TACKeyword.MEM64.toVar()
