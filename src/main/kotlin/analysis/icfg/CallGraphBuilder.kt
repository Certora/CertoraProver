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

@file:SuppressRemapWarning
@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.icfg

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import allocator.SuppressRemapWarning
import analysis.*
import analysis.alloc.ReturnBufferAnalysis
import analysis.numeric.ConstSet
import analysis.numeric.IntValue
import analysis.numeric.IntValue.Companion.Constant
import analysis.numeric.IntValue.Companion.Nondet
import analysis.numeric.UIntApprox
import analysis.pta.*
import analysis.pta.abi.*
import analysis.pta.abi.DecoderAnalysis.Companion.sizeInArray
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.*
import instrumentation.calls.ArgNum
import instrumentation.calls.CalldataByteRange
import instrumentation.calls.CalldataEncoding
import instrumentation.transformers.FilteringFunctions
import instrumentation.transformers.TACDSA
import log.*
import org.jetbrains.annotations.TestOnly
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import scene.*
import statistics.*
import tac.MetaKey
import tac.NBId
import tac.TACBasicMeta
import tac.Tag
import utils.*
import vc.data.*
import verifier.CodeAnalysis
import verifier.ContractUtils.recordAggregatedTransformationStats
import java.io.Serializable
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.INLINER)

class CallGraphBuilderFailedException(val msg: String, val where: LTACCmd, t: Throwable? = null) : Exception(msg, t)

private val ADDRESS_MASK = BigInteger.TWO.pow(160) - BigInteger.ONE

private const val CALLED_CONTRACT = "CALLED_CONTRACT"
private const val CALLGRAPH_FULL = "CALLGRAPH_FULL"
private const val POINTSTO = "POINTSTO"
private const val CALLGRAPHBUILDER = "CALLGRAPHBUILDER"


private typealias NodeUpdater = (Map<CallGraphBuilder.ByteRange, CallGraphBuilder.HeapInt>) -> CallGraphBuilder.NodeState

/**
 * A call graph builder. Walks the program graph, tracking constant values at offsets within arrays, with the purpose
 * of discovering the sighash for calls
 */
object CallGraphBuilder {
    @Treapable
    data class ContractStorageRead(@GeneratedBy(Allocator.Id.STORAGE_READ, source = true) val id: Int)
        : Serializable, AllocatorIdEntity<ContractStorageRead> {
        override fun mapId(f: (Allocator.Id, Int) -> Int): ContractStorageRead {
            return ContractStorageRead(id = f(Allocator.Id.STORAGE_READ, id))
        }

        companion object {
            val META_KEY = MetaKey<ContractStorageRead>("tac.call-graph.address-read")
        }
    }

    /**
     * A called contract reference, either symbolic [SymbolicOutput] or [SymbolicInput] or a concrete resolved address
     * [FullyResolved]
     */
    @KSerializable
    @Treapable
    sealed class CalledContract : AmbiSerializable, TransformableVarEntity<CalledContract>, UniqueIdEntity<CalledContract> {

        interface WithStorageReadId {
            val storageReadId: Int
        }

        /**
         * When the resolution of the target contract failed entirely, this is the fallback element
         * indicating that the resolution of the target contract failed.
         */
        @KSerializable
        object Unresolved : CalledContract() {
            private fun readResolve(): Any = Unresolved
            override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                return this
            }
            override fun hashCode() = treapHashObject(this)
        }

        /**
         * In the case the [InterContractCallResolver] fails to find a method with the resolved sighash
         * in the scene, yet the target address was resolved (to [contractId]), the call site will be marked
         * as Invalidated. This is required for the call resolution to display that the contract target address was
         * resolved, yet only the sighash is not found.
         */
        @KSerializable
        data class Invalidated(override val contractId: BigInteger) : WithContractId, CalledContract() {
            override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                return this
            }
        }

        sealed interface WithContractId{
            val contractId: BigInteger
        }

        @KSerializable
        sealed class FullyResolved : WithContractId, CalledContract() {

            @KSerializable
            data class ConstantAddress(
                override val contractId: BigInteger,
            ) : FullyResolved() {
                override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                    return this
                }
            }

            @GenerateRemapper
            @KSerializable
            data class StorageLink(
                override val contractId: BigInteger,
                @GeneratedBy(Allocator.Id.STORAGE_READ) override val storageReadId: Int
            ) : FullyResolved(), WithStorageReadId, RemapperEntity<CalledContract> {
                override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                    return this
                }
            }

            @KSerializable
            data class SelfLink(
                override val contractId: BigInteger
            ) : FullyResolved() {
                override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                    return this
                }
            }

            @KSerializable
            data class ImmutableReference(override val contractId: BigInteger) : FullyResolved() {
                override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                    return this
                }
            }


        }

        /**
         * The address called at this callcore is a (contract) method input argument, held in [inputArg] (if non-null)
         * and read from offset [offset] out of the call buffer
         */
        @KSerializable
        data class SymbolicInput(val inputArg: TACSymbol.Var?, val offset: BigInteger) : CalledContract() {

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CalledContract = copy(inputArg = inputArg?.let(f))

            override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                return this
            }
        }

        /**
         * The address called at this callcore was returned from an external message call, with ID [which] and at
         * offset [offset] within the return buffer.
         */
        @GenerateRemapper
        @KSerializable
        data class SymbolicOutput(@GeneratedBy(Allocator.Id.CALL_SUMMARIES) val which: Int, val offset: BigInteger) : CalledContract(), RemapperEntity<CalledContract>

        @KSerializable
        @GenerateRemapper
        data class InternalFunctionSummaryOutput(@GeneratedBy(Allocator.Id.INTERNAL_CALL_SUMMARY) val which: Int, val ordinal: Int): CalledContract(), RemapperEntity<CalledContract> {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CalledContract {
                return this
            }
        }

        @GenerateRemapper
        @KSerializable
        data class UnresolvedRead(@GeneratedBy(Allocator.Id.STORAGE_READ) override val storageReadId: Int) : CalledContract(), RemapperEntity<CalledContract>, WithStorageReadId

        @KSerializable
        sealed class CreatedReference : CalledContract() {

            /** The call is to newly-created contract with an unknown address */
            @GenerateRemapper
            @KSerializable
            data class Unresolved(@GeneratedBy(Allocator.Id.CONTRACT_CREATION) val createId: Int) : CreatedReference(), RemapperEntity<CalledContract>

            /** The call is to a newly-created contract with address [tgtConntractId]*/
            @KSerializable
            data class Resolved(val tgtConntractId: BigInteger) : CreatedReference() {
                override fun mapId(f: (Any, Int, () -> Int) -> Int): CalledContract {
                    return this
                }
            }
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CalledContract = this
    }

    /**
     * At a specific return site:
     * 1) What is the lower-bound on the size of the return buffer [returnSizeLowerBound]
     * 2) For each 32-byte chunk written in the return buffer, what are the locations where those values were written [returnWrites]
     * 3) If any of the 32-byte chunks in the return buffer are a contract address, what are the addresses? [addressReturn]
     *
     * The offsets in [addressReturn] are expected to be 32-aligned but might not be.
     */
    data class ReturnInformation(
        val returnSizeLowerBound: BigInteger,
        val returnWrites: Map<BigInteger, Set<LTACSymbol>>,
        val addressReturn: Map<BigInteger, CalledContract>
    )

    /**
     * For a call core, what are the size bounds of the input/output buffers?
     *
     * [inputSize]: the size of the input buffer, expressed as an interval of type [IntValue]
     * [outputSize]: the size of the output buffer, expressed as an interval of type [IntValue]
     */
    data class InOutBuffers(val inputSize: IntValue, val outputSize: IntValue)

    private sealed class SigResolutionAnalysisResult {
        data class Full(
            /**
             * At each call core corresponding to a key in the map, the call was resolved to the (usually singleton)
             * set of sighashes
             */
            val sigHash: Map<CmdPointer, Set<BigInteger>>,
            /**
             * At each call core corresponding to a key in the map, the input arguments to the call are laid out
             * as described in the mapped [CallInput] object
             */
            val input: Map<CmdPointer, CallInput>,
            /**
             * At each call core corresponding to a key in the map, the details about the definitions of
             * input arguments to the call are specified in the value set.
             * Importantly, [DecomposedArgVariableWithSourceWrites] contains command pointers and thus should not be included in the final summary.
             */
            val decomposedCallInputWithSourceWrites: Map<CmdPointer, Set<DecomposedArgVariableWithSourceWrites>>,
            /**
             * At each call core corresponding to a key in the map, the called contract (i.e., the to operand) is
             * any element of the (potentially symbolic) [CalledContract] objects
             */
            val callee: Map<CmdPointer, Set<CalledContract>>,
            /**
             * At each return site corresponding to a key in the map, the return buffer is described by the
             * mapped [ReturnInformation] object
             */
            val returnResolution: Map<CmdPointer, ReturnInformation>,
            /**
             * At each read of memory corresponding to a key in the map, the value being read is a return value
             * from a call as described in [ReturnPointer]
             */
            val returnValueReads: Map<CmdPointer, ReturnPointer>,
            /**
             * For each call core corresponding to a key in the map, the input/output buffer sizes are described
             * by the mapped [InOutBuffers] object
             */
            val bufferSizes: Map<CmdPointer, InOutBuffers>,
            /**
             * Each call core corresponding to a key in the map should be given the mapped Id. Unmapped call cores
             * can be given (fresh!) numbers.
             */
            val callCoreNumbering: Map<CmdPointer, Int>,

            /**
             * Associates every storage read at a command pointer that is the callee of an external to a unique numeric ID.
             * After these storage reads at these locations are "resolved" (via the storage analysis),
             * the uses of these reads as callees are then translated into resolved addresses, using, e.g., the linking
             * information in the contract spec.
             *
             * NB that we can't run storage without running inlining delegates, but to fully resolve callees we need storage.
             * So this is how we break that cycle, embed a breadcrumb for later.
             */
            val storageReadNumering: Map<CmdPointer, Int>,

            val constructorCalls: Map<CmdPointer, ConstructorCalls.ConstructorResolution>,
            /**
             * Indicates that the callcore at the given keys makes a call where the sighash comes from a value decoded out of
             * calldata at the given [DecoderAnalysis.BufferAccessPath]. Used later for inter-contract sighash resolution.
             */
            val symbolicSighash: Map<CmdPointer, DecoderAnalysis.BufferAccessPath>,
            val logEncodes: Map<CmdPointer, Int>
        ) : SigResolutionAnalysisResult()

        data class Heuristic(val sigs: Map<CmdPointer, Set<BigInteger>>, val callees: Map<CmdPointer,Set<CalledContract>>) : SigResolutionAnalysisResult()
    }

    sealed class SymbolicAddress {
        /**
         * This, i.e., the value of tacAddress
         */
        data object THIS : SymbolicAddress()

        /**
         * An "address" that was read out of a static, constant slot [number] from offset [offset] at [readLocation].
         */
        data class ConstantSlot(val readLocation: CmdPointer, val number: BigInteger, val offset: BigInteger?) :
            SymbolicAddress()

        /**
         * An address read out of storage at [readLocation]
         */
        data class UnresolvedRead(val readLocation: CmdPointer) : SymbolicAddress()

        data class CallDataInput(val inputArg: TACSymbol.Var?, val offset: BigInteger) : SymbolicAddress()
        data class ReturnData(val callLocation: CmdPointer, val offset: BigInteger) : SymbolicAddress()

        data class CreatedContract(val where: CmdPointer) : SymbolicAddress()

        data class InternalSummaryOutput(val which: Int, val offset: Int) : SymbolicAddress()

        data class ImmutableReference(val address: BigInteger) : SymbolicAddress()
        data class LibraryAddress(val contractId: BigInteger) : SymbolicAddress()
        fun lift() = StorageSet.Set(setOf(this))

    }

    sealed class StorageSet {
        fun join(storageSet: StorageSet): StorageSet {
            return if(this == Nondet || storageSet == Nondet) {
                return Nondet
            } else {
                (this as Set).join(storageSet as Set)
            }
        }

        data object Nondet : StorageSet()
        data class Set(val slots: kotlin.collections.Set<SymbolicAddress>) : StorageSet() {
            constructor(v: SymbolicAddress) : this(kotlin.collections.setOf(v))
            fun join(other: Set) : Set {
                return Set(slots.union(other.slots))
            }
        }
    }

    private fun IntValue.liftToReduced(writeCmdPtrSet : CmdPointerSet = CmdPointerSet.Nondet, storageSet: StorageSet) : HeapInt {
        /* why assume this value is never a return value? Because we will never do that explicitly in tac code, we do it
            automatically when we've established the return code check and returndatasize bounds */
        return if(this.isConstant) {
            HeapInt(consts = ConstSet.Constant(this.c), writeCmdPtrSet = writeCmdPtrSet, storageSet = storageSet, returnVal = null, symbolicValueSource = null)
        } else {
            HeapInt(consts = ConstSet.Nondet, writeCmdPtrSet = writeCmdPtrSet, storageSet = storageSet, returnVal = null, symbolicValueSource = null)
        }
    }

    private fun Map.Entry<ByteRange, HeapInt>.tryIntersect(
        start: BigInteger,
        len: IntValue? = null
    ) : Pair<ByteRange, HeapInt>? = (this.key to this.value).tryIntersect(start, len)

    private fun Pair<ByteRange, HeapInt>.tryIntersect(start: BigInteger, len: IntValue? = null) : Pair<ByteRange, HeapInt>? {
        if(len != null && start + len.ub <= this.first.start) {
            return null
        }
        if(this.first.end < start) {
            return null
        }
        val intersectionRange = ByteRange(start = start, end = len?.takeIf { it.isConstant }?.c?.let {
            (start + it) - BigInteger.ONE
        } ?: this.first.end /* assume that we're getting the whole range, absent other information */)
        return when(val inter = this.first.intersect(intersectionRange)) {
            ByteRange.OverlapEffect.Contains -> this
            ByteRange.OverlapEffect.None -> null
            is ByteRange.OverlapEffect.WithConcreteModel -> {
                val newRange = inter.applyTo(this.first)
                val newVal = HeapInt(
                    consts = inter.applyTo(this.second.consts),
                    writeCmdPtrSet = inter.applyTo(this.second.writeCmdPtrSet),
                    returnVal = null,
                    storageSet = StorageSet.Nondet,
                    symbolicValueSource = null
                )
                newRange to newVal
            }
        }
    }

    /**
     * [end] is *inclusive*
     */
    data class ByteRange(val start: BigInteger, val end: BigInteger) {
        companion object {
            fun BigInteger.withLength(length: BigInteger) = ByteRange(this, this + length - BigInteger.ONE)
        }

        infix fun overlaps(that: ByteRange) : Boolean {
            return this.start <= that.end && this.end >= that.start
        }

        infix fun union(that: ByteRange): ByteRange {
            require(this adjoins that) {
                "Cannot perform disjoint union between $this and $that"
            }
            return ByteRange(this.start.min(that.start), this.end.max(that.end))
        }

        infix fun subsumes(that: ByteRange) = this.start <= that.start && this.end >= that.end

        infix fun adjoins(that: ByteRange) = this overlaps that || this.start == that.end + BigInteger.ONE || this.end + BigInteger.ONE == that.start

        infix fun strictlySubsumes(that: ByteRange) = this subsumes that && this != that



        val size : BigInteger = (end - start) + BigInteger.ONE

        /**
         * Represents the logical effect of overlapping a ByteRange x with another ByteRange y
         * according to the update semantics of the EVM.
         *
         * The name of the constructors indicates the effect y has on x. This effect may depend on
         * whether `y` is overwriting `x` or intersecting `x`.
         */
        sealed interface OverlapEffect {
            sealed interface IntersectEffect : OverlapEffect
            sealed interface OverwriteEffect : OverlapEffect
            sealed interface BothEffect : IntersectEffect, OverwriteEffect
            sealed interface Containment
            sealed interface WithConcreteModel {
                fun applyTo(x: ByteRange) : ByteRange
                fun applyTo(x: BigInteger) : BigInteger
                fun applyTo(cset: CmdPointerSet) = when(cset) {
                    is CmdPointerSet.CSet -> CmdPointerSet.CSet(cset.cs.mapToSet { (where, range)  ->
                        where to this.applyTo(range)
                    })
                    CmdPointerSet.Nondet -> cset
                }
                fun applyTo(hi: HeapInt) = HeapInt(
                    consts = this.applyTo(hi.consts),
                    writeCmdPtrSet = this.applyTo(hi.writeCmdPtrSet),
                    returnVal = null,
                    storageSet = StorageSet.Nondet,
                    symbolicValueSource = null
                )
                fun applyTo(cset: ConstSet) = when(cset) {
                    is ConstSet.Nondet -> cset
                    is ConstSet.C -> ConstSet.C(cset.ks.mapToSet {
                        this.applyTo(it)
                    })
                }
            }

            sealed interface Truncation : WithConcreteModel, BothEffect
            object None : BothEffect

            /**
             * y truncates [amount] bytes from the end of x (viewed as a vector)
             */
            data class TruncateEnd(val amount: BigInteger) : Truncation {
                override fun applyTo(x: ByteRange) : ByteRange {
                    require(x.size > amount) {
                        "Trying to truncate $amount off of $x"
                    }
                    /*
                      If we have [2,5] and we are chopping off two bytes from the end, then the upper most byte will be [2,3]
                     */
                    return x.copy(end = x.end - amount)
                }

                override fun applyTo(x: BigInteger): BigInteger {
                    /*
                     * "end" here means those "later" in the byterange,
                     * so viewed as a bitvector, we truncate the LEAST significant (aka lower) bits
                     * of a value
                     */
                    // chop off `amount` bytes from the constant x. This is done by simply shifting the integer amount * 8 bits to the right
                    return x.shiftRight(amount.toInt() * 8)
                }
            }
            /**
             * y truncates [amount] bytes from the start of x (viewed as a vector)
             */
            data class TruncateStart(val amount: BigInteger, val width: BigInteger) : Truncation {
                override fun applyTo(x: ByteRange): ByteRange {
                    require(x.size > amount)
                    return x.copy(start = x.start + amount)
                }

                /**
                 * As in [TruncateEnd], "start" here means "earlier" in the ByteRange, so we are masking out the
                 * most significant (aka upper) bits of x. For this operation to make any kind of sense, we need to know
                 * the width of the value being truncated. We know this width at the time that the [TruncateStart] object
                 * is created, so we just create the appropriate mask and store it in [mask], to be applied to [x].
                 */
                override fun applyTo(x: BigInteger): BigInteger {
                    return x and (BigInteger.TWO.pow(width.toInt() * 8) - BigInteger.ONE)
                }
            }

            /**
             * y is strictly contained within x, offset from the end of x by [offsetFromEnd] bytes (counted from the beginning of x)
             * for [sz] bytes (NB [sz] == `y`.size
             */
            data class StrictlyContainedWithin(val offsetFromEnd: BigInteger, val sz: BigInteger) : IntersectEffect, WithConcreteModel, Containment {
                fun extractFrom(x: BigInteger): BigInteger {
                    return x.shiftRight(offsetFromEnd.toInt() * 8) and (BigInteger.TWO.pow(sz.toInt() * 8) - BigInteger.ONE)
                }

                /**
                 * The goal of this function is to take apply this translation to a ByteRange, specifically, [x] is expected
                 * to be the ByteRange recorded in the [CmdPointerSet] describing the range of the originally
                 * written value still known to possibly be in the buffer.
                 *
                 * In other words, we have a range a ~ b (position within some ByteBuffer) and we know that (one of) the
                 * writes populating this is the range c ~ d (i.e., [x]) of some value written at some command pointer. It is invariant
                 * that b - a = d - c. We have some other byte range e ~ f which is contained within a ~ b, the effect
                 * of this intersection is represented by this object. That is, [sz] = (f - e) + 1 and [offsetFromEnd] = b - f.
                 *
                 * We want to effectively "shift" the start of [x] by the difference e - a (computing the new end value
                 * for [x] is trivial given [sz]). We can compute this difference `e - a` from the information already
                 * in this class with x.size - ([offsetFromEnd] + [sz])` justified by:
                 * x.size = (d - c) + 1 and expanding the definitions of [offsetFromEnd] and [sz] we get:
                 * `((d - c) + 1) - ((b - f) + ((f - e) + 1)) simplifying we get:
                 *  e - b + (d - c) as (d - c) = (b - a) we substitute and get:
                 *  e - b + (b - a) that is e - a as required.
                 */
                fun narrowRange(x: ByteRange) : ByteRange {
                    val newStart = x.start + (x.size - (offsetFromEnd + sz))
                    return x.copy(
                        start = newStart,
                        end = (newStart + sz) - BigInteger.ONE
                    )
                }

                override fun applyTo(x: ByteRange): ByteRange {
                    return narrowRange(x)
                }

                override fun applyTo(x: BigInteger): BigInteger {
                    return extractFrom(x)
                }
            }

            object Hole : OverwriteEffect

            /**
             * x is entirely contained within y (not necessarily strict)
             */
            object Contains: BothEffect, Containment
        }

        /**
         * computes the effect of computing the intersection of `y` and this (aka `x` in the language
         * of [OverlapEffect])
         */
        fun intersect(y: ByteRange) : OverlapEffect.IntersectEffect {
            if(y.start > this.end || y.end < this.start) {
                return OverlapEffect.None
            }
            if(y.end <= this.end && this.start <= y.start && y.size < this.size) {
                return OverlapEffect.StrictlyContainedWithin(
                    offsetFromEnd = this.end - y.end,
                    sz = y.size
                )
            }
            if(this.end <= y.end && this.start >= y.start) {
                // that is, y completely contains x
                return OverlapEffect.Contains
            }
            if(y.start >= this.start) {
                /**
                 unlike the [overwriteBy] case below, here we are selecting out the least significant bits,
                 AKA, those bits that come "later" in the bitvector. In other words, we are truncating the start
                 of x (aka this). See [overwriteBy] for the proof of this check (do not @ me shelly)
                 */
                check(y.start > this.start && y.end >= this.end)
                val newWidth = (this.end - y.start) + BigInteger.ONE
                val amount = y.start - this.start
                return OverlapEffect.TruncateStart(amount = amount, width = newWidth)
            } else {
                // see below for proof
                check(y.end >= this.start && y.start < this.start)
                // so in this case, we are slicing off the bytes and the "end" of x not included in `y`
                return OverlapEffect.TruncateEnd(amount = this.end - y.end)
            }
        }

        /**
         * Within this function `this` is `x` in the language of [OverlapEffect]
         */
        fun overwriteBy(y: ByteRange) : OverlapEffect.OverwriteEffect {
            if(y.start > this.end || y.end < this.start) {
                return OverlapEffect.None
            }
            if(this.start < y.start && y.end < this.end) {
                return OverlapEffect.Hole
            }
            if(this.end <= y.end && this.start >= y.start) {
                return OverlapEffect.Contains
            }
            if(y.start >= this.start && y.end >= this.end) {
                /**
                 * you can prove with a modification to the formula below that (replace final assert
                 * with `(assert (= other.start this.start))` that y.start must actually be strictly
                 * greater than this.start.
                 */
                /*
                  #verified with SMT
                    (declare-const this.start Int)
                    (declare-const this.end Int)
                    (declare-const other.start Int)
                    (declare-const other.end Int)
                    (define-const other.size Int (- other.end (- other.start 1)))
                    (define-const this.size Int (- this.end (- this.start 1)))

                    (assert (> this.size 0))
                    (assert (> other.size 0))
                    (assert (>= this.start 0))
                    (assert (>= other.start 0))

                    (assert (not (or (> other.start this.end) (< other.end this.start))))
                    (assert (not (and (<= other.end this.end) (<= this.start other.start) (< other.size this.size))))
                    (assert (not (and (<= this.end other.end) (>= this.start other.start))))
                    (assert (and (>= other.start this.start) (not (< other.end this.end))))

                    (assert (not (>= other.end this.end)))
                    (check-sat)
                 */
                check(y.end >= this.end)
                /* calculate the number of upper bytes being truncated here. To avoid the inevitable
                   "oh bob", let's look at a concrete example:
                    [1,4] (5 byte range) being overwritten with [2,4]. Then all of the bytes from 2 up are
                    truncated, 2-4 inclusive is therefore the amount to truncate, symbolically:
                    this.end - other.start - 1
                 */
                return OverlapEffect.TruncateEnd(this.end - (y.start - BigInteger.ONE))
            } else {
                /* also #verified with smt */
                check(y.end >= this.start && y.start <= this.start && y.end < this.end)
                /*
                 * inverse of the above, if we have [4,8] being overwritten by [2,5] then the lower two bytes
                 * are being truncated, aka: 5 - (4 - 1) aka other.end - (this.start - 1)
                 */
                /*
                 * An example:
                 * [4,8] being overwritten by [2,5], we'll have that the new range is [6,8] which has
                 * size (8 - 6) + 1. The 6 term is just other.end + 1 (the byte immediately after the
                 * inclusive end index of other). So, we get:
                 * new_start = other.end + 1, new_width = (end - new_start) + 1
                 * substituting
                 * new_width = (end - (other.end + 1)) + 1 aka end - other.end
                 */
                val newWidth = this.end - y.end
                return OverlapEffect.TruncateStart(y.end - (this.start - BigInteger.ONE), width = newWidth)
            }
        }
    }


    private fun MutableMap<ByteRange, HeapInt>.getAffectedKeys(where: ByteRange): List<ByteRange> {
        return this.keys.filter {
            !(it.end < where.start || it.start > where.end)
        }
    }

    private fun MutableMap<ByteRange, HeapInt>.getAffectedKeys(where: IntValue): List<ByteRange> {
        return this.keys.filter {
            !(it.end < where.getLowerBound() || it.start > where.getUpperBound() + 31.toBigInteger())
        }
    }


    /**
    How do we track non-array contents? Simple, we have a map ([direct]) from [PTANode]s to [HeapInt] that abstract the possible values
    for those nodes.

    How do we track array contents? It's complicated!

    Memory is byte-addressed, but writes are 32-bytes. Solidity will quite often write a 32-byte value with the
    lower 28 bytes cleared, and then write another 32-byte value at offset 4. (This is, in fact, how sighashes
    are encoded in the first 4 bytes...)

    In the above case, what is the abstraction of the 32-byte value starting at 0? It's very hard, and in general impossible,
    to say. However, we *can* provide information about the *4-byte* value starting at 0. Assuming we write a 32-byte value
    at 0, we can precisely model the most significat 4-bytes, and the write to offset 4 is a strong update, then we can
    simply slice off the top 4 bytes and treat the remaining bytes as "havoc". This is purpose of [ByteRange], which
    abstracts a range of bytes (rather than a single index, which has indeterminate length).

    Of course, there's no a priori way to really compute the byte ranges, so the call graph analysis incrementally
    computes the byte range information.

    Each array pointer (of type [PTANode], usually contained within an [IndexedWritableSet])
    is mapped to a map from ByteRanges ([byteAddressed]) to [HeapInt]

    On a *strong* update to a deterministic index i:
    + Find included byte ranges. Remove them from the model.
    + Find overlapping byte ranges. If the byte range's end point is higher than the end point of the written block,
    truncate the block to the end-point of the written block, and update the abstraction with the result of truncating the
    abstract values. If the byte range start point is lower than the written block's range, do the dual.
    + Finally, write the value into the newly defined byte range.

    Note that ranges can only ever be 32 bytes long, and only shrink, it is therefore impossible for a new block to
    be contained completely within an existing block.

    This logic is implemented in [strongUpdate]

    On a *weak* update to a deterministic index:
    + If the byte range being written is exactly within the map, weakly update it's contents
    + Otherwise, havoc all overlapping regions

    This logic is implemented in [weakUpdate].

    In any other case (non-deterministic indices, weak base pointers, etc.) simply havoc overlapping byte ranges.
    An important invariant preserved by the above is that all [ByteRange] keys must always be distinct.

    Joins are equally pessimistic, if the domains of the [ByteRange] map for a array pointer don't agree, simply assume
    havoc
     */
    data class NodeState(
        val direct: Map<PTANode, HeapInt>,
        val byteAddressed: Map<PTANode, TreapMap<ByteRange, HeapInt>>,
        val indexAddressed: Map<PTANode, Map<BigInteger, HeapInt>>,
        val bufferContents: Pair<TACSymbol.Var, TreapMap<ByteRange, HeapInt>>?
    ) {

        fun modelWrite(v: WritablePointsToSet, hi: HeapInt) = modelWrite(v) { _, _, cb -> cb(hi) }

        fun modelWrite(v: WritablePointsToSet, strongestUpdate: (st: NodeState, node: PTANode, update: (HeapInt) -> NodeState) -> NodeState) : NodeState {
            require(v !is IndexedWritableSet) {
                "Cannot use modelWrite on IndexedSets"
            }
            return when(v.strongestUpdate()) {
                WritablePointsToSet.UpdateType.STRONG -> {
                    v.nodes.fold(this) { Curr, node ->
                        strongestUpdate(Curr, node) { c ->
                            Curr.strongUpdate(node, c)
                        }
                    }
                }
                WritablePointsToSet.UpdateType.WEAK -> {
                    v.nodes.fold(this) { Curr, node ->
                        strongestUpdate(Curr, node) { c ->
                            Curr.weakUpdate(node, c)
                        }
                    }
                }
                WritablePointsToSet.UpdateType.INVALID -> this
            }
        }

        fun readValue(base: PTANode) : HeapInt {
            return direct[base] ?: HeapInt.Nondet
        }

        fun readValue(base: IndexedWritableSet.IndexedNode) : HeapInt {
            val x = base.index
            if(!x.isConstant) {
                return HeapInt.Nondet
            }
            return if(base.elementSize == BigInteger.ONE) {
                val br = ByteRange(x.c, x.c + 31.toBigInteger())
                byteAddressed[base.node]?.get(br) ?: HeapInt.Nondet
            } else {
                indexAddressed[base.node]?.get(x.c) ?: HeapInt.Nondet
            }
        }

        fun weakUpdate(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            return if(base.elementSize == BigInteger.ONE) {
                weakUpdateByteRange(base, v)
            } else {
                weakUpdateIndexed(base, v)
            }
        }

        private fun weakUpdateIndexed(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            val where = base.index
            return updateIndexedArrayState(base) {
                if(!where.isConstant) {
                    this.keys.filter {
                        it >= where.lb && it <= where.ub
                    }.forEach {
                        this.remove(it)
                    }
                    return@updateIndexedArrayState
                }
                if(where.c in this) {
                    return@updateIndexedArrayState
                }
                this.merge(where.c, v) { a, b ->
                    a.join(b)
                }
            }
        }

        fun killAffectedNodes(base: IndexedWritableSet) : NodeState {
            return base.indexed.fold(this) { Curr, n ->
                if(n.elementSize == BigInteger.ONE) {
                    Curr.updateByteArrayState(n) {
                        val it = this.iterator()
                        while(it.hasNext()) {
                            val (k, _) = it.next()
                            if(k.start >= n.index.lb || k.end >= n.index.lb) {
                                it.remove()
                            }
                        }
                    }
                } else {
                    Curr.updateIndexedArrayState(n) {
                        val it = this.iterator()
                        while(it.hasNext()) {
                            val (k, _) = it.next()
                            if(k > n.index.lb) {
                                it.remove()
                            }
                        }
                    }
                }
            }
        }

        private fun updateIndexedArrayState(base: IndexedWritableSet.IndexedNode, f: MutableMap<BigInteger, HeapInt>.() -> Unit): NodeState {
            require(base.elementSize == EVM_WORD_SIZE)
            val mut = this.indexAddressed[base.node]?.toMutableMap() ?: mutableMapOf()
            mut.f()
            return this.copy(indexAddressed = indexAddressed + (base.node to mut))
        }

        private fun weakUpdateByteRange(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            val where = base.index
            return this.updateByteArrayState(base) {
                if(!where.isConstant) {
                    this.getAffectedKeys(where).forEach {
                        this[it] = HeapInt.Nondet
                    }
                    return@updateByteArrayState
                }
                val updatedRange = ByteRange(where.c, where.c + 31.toBigInteger())
                if(updatedRange !in this) {
                    this.getAffectedKeys(updatedRange).forEach { this[it] = HeapInt.Nondet }
                    return@updateByteArrayState
                }
                this[updatedRange] = this[updatedRange]!!.join(v)
            }
        }

        private fun strongUpdate(x: PTANode, write: HeapInt): NodeState {
            return this.copy(
                direct = direct + (x to write)
            )
        }

        private fun weakUpdate(x: PTANode, write: HeapInt): NodeState =
            this.copy(direct = this.direct.toMutableMap().let { mut ->
                mut.merge(x, write, HeapInt::join)
                mut
            })

        private fun updateByteArrayState(base: IndexedWritableSet.IndexedNode, f: TreapMap.Builder<ByteRange, HeapInt>.() -> Unit) : NodeState {
            require(base.elementSize == BigInteger.ONE)
            val mut = this.byteAddressed[base.node]?.builder() ?: treapMapBuilderOf()
            mut.f()
            return this.copy(byteAddressed = byteAddressed + (base.node to mut.build()))
        }

        fun strongUpdate(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            return if(base.elementSize == BigInteger.ONE) {
                strongUpdateByte(base, v)
            } else {
                strongUpdateIndexed(base, v)
            }
        }

        private fun strongUpdateIndexed(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            val where = base.index
            return if (!where.isConstant) {
                updateIndexedArrayState(base) {
                    this.keys.filter {
                        it >= where.lb && it <= where.ub
                    }.forEach {
                        this.remove(it)
                    }
                }
            } else {
                updateIndexedArrayState(base) {
                    this[where.c] = v
                }
            }
        }

        /**
         * Generate a callback of type [NodeUpdater] which updates this [NodeState] at [base]. This function will
         * return null if [base] is not suitable for updating, which is currently:
         * 1. [base] does not support (indexed) strong updates
         * 2. [base] does not have hold a singleton [IndexedWritableSet.indexed] node (currently implied by 1, but checked anyway)
         * 3. [base] is not a byte array
         * 4. The [IndexedWritableSet.IndexedNode.index] field of [base] is not a constant
         *
         * If these conditions pass, then this function returns a [NodeUpdater], which is a function that
         * takes the "copy payload", which is a mapping of [ByteRange] to [HeapInt],
         * to apply to `this` (aka [NodeState]) at the (unique) location indicated by [base].
         * The [NodeUpdater] returns the [NodeState] with this update applied.
         *
         * It is expected (but not checked) that the [ByteRange] keys in the copy payload mapping are disjoint; the behavior of this
         * function if this invariant is not respected is "undefined". Further it is expected (but again, not checked)
         * that the copy payload ByteRanges logically start from 0. The [NodeUpdater] returned by this function will
         * translate the byte ranges in the copy payload to the index recorded in [base]. For example, if the copy payload
         * contains a [ByteRange] with the range 32 - 63, and [base] indicates an offset of 4, then the [NodeUpdater]
         * will translate the input byte range to be 36 - 67.
         *
         * [HeapInt] abstractions that already exist in this [NodeState] which overlap with the output location indicated
         * by [base] (i.e., KV mappings associated with the PTA node in [base] and which lie in a byterange that overlaps
         * with the index included [base]) are either completely removed or partially truncated if possible. By default,
         * any key value that falls "past" the index indicated by [base] are removed/truncated. However, if the [optimistic]
         * flag is true, then the "presumed" end of the copy payload is taken to the be the largest [ByteRange.end]
         * value that appears in the copy payload; key values that appear past the end of this are not removed.
         *
         * [where] is expected to be the location which is the "source" of this copy; it is only used for debugging and
         * clarifying error messages.
         */
        fun getCopyWriter(base: IndexedWritableSet, where: LTACCmd, optimistic : Boolean = false) : NodeUpdater? {
            fun err(s: () -> String) : Nothing? {
                logger.warn(s)
                return null
            }
            if(base.offsetUpdate() != WritablePointsToSet.UpdateType.STRONG) {
                return err {
                    "at $where, have non-strong copy output"
                }
            }
            val destNodes = base.indexed.singleOrNull() ?: return err {
                "At $where, have multiple output nodes"
            }
            if(destNodes.elementSize != BigInteger.ONE) {
                return err {
                    "At $where, copy output is not a byte array"
                }
            }
            if(!destNodes.index.isConstant) {
                return err {
                    "At $where, copy output is not to constant index"
                }
            }
            val ind = destNodes.index.c
            return { newValues ->
                val presumedEnd = if(optimistic) {
                    newValues.keys.maxByOrNull { it.end }?.end
                } else {
                    null
                }
                this.updateByteArrayState(destNodes) {
                    val start = this.build()
                    val toMutate = this.entries.mapNotNull {
                        if(it.key.start <= ind && it.key.end >= ind) {
                            (it.key.overwriteBy(ByteRange(ind, it.key.end)) to it)
                        } else if(it.key.start >= ind && (presumedEnd == null || it.key.start <= presumedEnd + ind)) {
                            (null to it)
                        } else {
                            null
                        }
                    }
                    for((eff, kv) in toMutate) {
                        val (oldK, oldV) = kv
                        this.remove(oldK)
                        if(eff is ByteRange.OverlapEffect.Truncation) {
                            val newK = eff.applyTo(oldK)
                            val newV = eff.applyTo(oldV)
                            this[newK] = newV
                        }
                    }
                    val mid = this.build()
                    for((k, v) in newValues) {
                        val newK = ByteRange(
                            start = ind + k.start,
                            end = ind + k.end
                        )
                        check(newK !in this) {
                            "Already have heap int entry, $newK we didn't clean correctly for $start ($ind to $presumedEnd) post clean $mid with $newValues"
                        }
                        this[newK] = v
                    }
                }
            }
        }

        private fun strongUpdateByte(base: IndexedWritableSet.IndexedNode, v: HeapInt): NodeState {
            val where = base.index
            return if (!where.isConstant) {
                updateByteArrayState(base) {
                    val toRemove = mutableListOf<ByteRange>()
                    val toAdd = mutableListOf<Pair<ByteRange, HeapInt>>()
                    this.getAffectedKeys(where).forEach {
                        truncateOrRemove(
                            toRemove = toRemove,
                            toAdd = toAdd,
                            k = it,
                            updateRange = ByteRange(where.lb, where.ub + 31.toBigInteger()),
                            model = this
                        )
                    }
                    toRemove.forEach { this.remove(it) }
                    toAdd.forEach { this[it.first] = it.second }
                }
            } else {
                strongUpdateByte(base, ByteRange(where.c, where.c + 31.toBigInteger()), v)
            }
        }

        /**
         * When writing to [updateRange], update the model of memory byte ranges to their values [model]
         * given the potentially affected byte range key of [k].
         * Updates are collected in [toRemove] and [toAdd].
         */
        private fun truncateOrRemove(
            toRemove: MutableCollection<ByteRange>,
            toAdd: MutableCollection<Pair<ByteRange, HeapInt>>,
            k: ByteRange,
            model: Map<ByteRange, HeapInt>,
            updateRange: ByteRange
        ) {
            when(val overwrite = k.overwriteBy(updateRange)) {
                ByteRange.OverlapEffect.None -> return
                is ByteRange.OverlapEffect.Contains,
                is ByteRange.OverlapEffect.Hole -> {
                    toRemove.add(k)
                    return
                }
                is ByteRange.OverlapEffect.Truncation -> {
                    toRemove.add(k)
                    val newRange = overwrite.applyTo(k)
                    val newHeapVal = model[k]!!.let { currVal ->
                        HeapInt(
                            consts = overwrite.applyTo(currVal.consts),
                            returnVal = null,
                            storageSet = StorageSet.Nondet,
                            writeCmdPtrSet = overwrite.applyTo(currVal.writeCmdPtrSet),
                            symbolicValueSource = currVal.symbolicValueSource
                        )
                    }
                    toAdd.add(newRange to newHeapVal)
                    return
                }
            }
        }

        fun strongUpdateByte(base: IndexedWritableSet.IndexedNode, updateRange: ByteRange, v: HeapInt): NodeState {
            return updateByteArrayState(base) {
                if (updateRange in this) {
                    this[updateRange] = v
                    return@updateByteArrayState
                }
                val toAdd = mutableListOf<Pair<ByteRange, HeapInt>>()
                val toRemove = mutableListOf<ByteRange>()
                for (k in this.keys) {
                    truncateOrRemove(
                        toRemove = toRemove,
                        model = this,
                        updateRange = updateRange,
                        k = k,
                        toAdd = toAdd
                    )
                }
                toRemove.forEach { this.remove(it) }
                toAdd.forEach { this[it.first] = it.second }
                this[updateRange] = v
            }
        }

        fun join(nodes: NodeState): NodeState {
            val byteRet = mutableMapOf<PTANode, TreapMap<ByteRange, HeapInt>>()
            val indexedRet = mutableMapOf<PTANode, Map<BigInteger, HeapInt>>()
            for(k in (nodes.byteAddressed.keys + this.byteAddressed.keys)) {
                if(k !in nodes.byteAddressed || k !in this.byteAddressed) {
                    if (!Config.OptimisticBufferContents.get()) {
                        byteRet[k] = treapMapOf()
                        continue
                    }
                    if(k in nodes.byteAddressed) {
                        byteRet[k] = nodes.byteAddressed[k]!!
                    } else {
                        byteRet[k] = this.byteAddressed[k]!!
                    }
                    continue
                }
                val a = this.byteAddressed[k]!!
                val b = nodes.byteAddressed[k]!!
                byteRet[k] = exactMerge(a, b)
            }
            for(k in (nodes.indexAddressed.keys + this.indexAddressed.keys)) {
                if(k !in nodes.indexAddressed || k !in this.indexAddressed) {
                    indexedRet[k] = mapOf()
                    continue
                }
                val a = this.indexAddressed[k]!!
                val b = nodes.indexAddressed[k]!!
                val joined = mutableMapOf<BigInteger, HeapInt>()
                for(i in a.keys.intersect(b.keys)) {
                    joined[i] = a[i]!!.join(b[i]!!)
                }
                indexedRet[k] = joined
            }
            return NodeState(
                direct = this.direct.pointwiseBinop(nodes.direct, HeapInt.Nondet, HeapInt::join),
                byteAddressed = byteRet,
                indexAddressed = indexedRet,
                bufferContents = null
            )
        }

        /**
         * "project" the subset of the byte addressed node in [i]'s [IndexedWritableSet.IndexedNode.node]
         * that begins from the [IndexedWritableSet.IndexedNode.index]. In other words, if this function returns
         * a Left, then the map returned is mapping of the [ByteRange]s that are associated with the PTA node
         * of [i] starting from [i]'s index with relocated relative to that index. For example, if the index of [i] is
         * 4, and there exists a KV value at the [ByteRange] 4 - 35 with [HeapInt] h, then the map returned by this function
         * will have a mapping from 0 - 31 with that same h.
         *
         * This function can return an [Either.Right] if [i] cannot be projected. This occurs if:
         * 1. [i] is not a byte array (that is, [IndexedWritableSet.IndexedNode.elementSize] != 1)
         * 2. The [IndexedWritableSet.IndexedNode.index] of [i] is not a constant
         *
         * [size] indicates an abstraction of the length to project from [i], if known (NB: [size] is NOT
         * an abstraction of the end point). If [size] is null,
         * then this function will include all entries that intersect with the unbounded range that begins at (constant)
         * index [i]. If [size] is non-null, but *not* a constant, then the function will exclude entries that *definitely*
         * fall outside the range, i.e. their start point is past `i.index + size.ub`. Otherwise, if [size] is constant,
         * then this function will enforce a strict upper bound on the range selected.
         *
         * Key-values that only partially fall within the select range (at the beginning of the "slice" or, if [size] is
         * constant, at the definite end) are truncated as determined by [ByteRange.intersect].
         */
        fun projectFrom(
            i: IndexedWritableSet.IndexedNode,
            size: IntValue?
        ) : Either<TreapMap<ByteRange, HeapInt>, String> {
            val s = this
            if(i.elementSize != BigInteger.ONE) {
                return "Not a byte array, refusing to project".toRight()
            }
            if(!i.index.isConstant) {
                return "Index is not a constant: ${i.index}".toRight()
            }
            val startInd = i.index.c
            /**
             * The empty map is considered to be "oops, all nondet!"
             */
            val src = s.byteAddressed[i.node] ?: return treapMapOf<ByteRange, HeapInt>().toLeft()
            return projectFrom(
                src, startInd, size
            ).toLeft()
        }

        companion object {
            fun projectFrom(
                src: TreapMap<ByteRange, HeapInt>,
                startInd: BigInteger,
                size: IntValue?
            ): TreapMap<ByteRange, HeapInt> {
                val toRet = treapMapBuilderOf<ByteRange, HeapInt>()
                for(kv in src) {
                    val (mK, mV) = kv.tryIntersect(startInd, size) ?: continue
                    check(mK.start >= startInd) {
                        "Intersection broke $kv -> $mK with $startInd"
                    }
                    toRet[ByteRange(
                        mK.start - startInd,
                        mK.end - startInd
                    )] = mV
                }
                return toRet.build()
            }

            /**
             * A simple (i.e., less precise but faster) merge operation on [ByteRange] maps. Disjoint keys are discarded,
             * the values of common keys between [t] and [other] are joined.
             */
            fun simpleMerge(
                t: TreapMap<ByteRange, HeapInt>,
                other: TreapMap<ByteRange, HeapInt>
            ): TreapMap<ByteRange, HeapInt> {
                return t.intersect(other) { _, v1, v2 -> v1.join(v2) }
            }

            /**
             * A more precise but expensive merge between [t1] and [t2]. Common keys are joined and placed into the
             * result map. Keys that exclusive to [t1] are intersected with any keys in [t2], and their interesection
             * is joined and then placed in the result map (and vice versa). For example, the join of:
             * `[(0, 31) -> k1]` and `[(0, 4) -> k2]` (where ki abbreviates `HeapInt(const = [k], ...)`) will yield
             * [(0, 4) -> k2 \/ k1 >> 224]`
             */
            fun exactMerge(
                t1: TreapMap<ByteRange, HeapInt>,
                t2: TreapMap<ByteRange, HeapInt>
            ) : TreapMap<ByteRange, HeapInt> {
                val exclusiveT1Keys = mutableListOf<ByteRange>()
                val exclusiveT2Keys = mutableListOf<ByteRange>()
                val joined = t1.merge(t2) { range, t1Val, t2Val ->
                    if (t1Val == null) {
                        exclusiveT2Keys.add(range)
                        null
                    } else if (t2Val == null) {
                        exclusiveT1Keys.add(range)
                        null
                    } else {
                        t1Val.join(t2Val)
                    }
                }.builder()
                joinDisjointRegions(t1, exclusiveT1Keys, t2, joined, strictContainmentOnly = false)
                joinDisjointRegions(t2, exclusiveT2Keys, t1, joined, strictContainmentOnly = true)
                return joined.build()
            }

            /**
             * Take all [exclusiveKeys] that appear in [a] and find all intersecting kvs in [b], and place the
             * result of joining these intersections in [joined]. If [strictContainmentOnly] is true,
             * then the intersection of a key k in [a] with a key in [b] is only considered if k is strictly contained
             * within some other key k' in [b].
             *
             * This flag is necessary because any exclusive keys in [a] that overlap with exclusive keys in [b] will
             * (mostly) have an inverse relationship. That is, if some k0 in [a] overlaps/intersects with k1, k2 in [b],
             * then we don't need to consider the overlaps k1/k2 have: they will be detected when looking for overlapping
             * keys in [a]. The exception is if k0 is strictly contained within k1. The implementation of [ByteRange.intersect]
             * is "directed", and in this case asking for the intersection of k0 and k1, we will get the answer
             * [ByteRange.OverlapEffect.Contains], that is, k0 is contained within k1 (not necessarily strict). But if we
             * ask for the intersection of k1 and k0, we will get [ByteRange.OverlapEffect.StrictlyContainedWithin].
             *
             * (Q: why are the implementations directed? A: because they tell you want to do to one of the keys involved: truncate
             * high/low bits, etc.)
             */
            private fun joinDisjointRegions(
                a: Map<ByteRange, HeapInt>,
                exclusiveKeys: List<ByteRange>,
                b: Map<ByteRange, HeapInt>,
                joined: TreapMap.Builder<ByteRange, HeapInt>,
                strictContainmentOnly: Boolean
            ) {
                for (k1 in exclusiveKeys) {
                    val v1 = a[k1] ?: error("Could not find $k1 in $a despite a promsie it was exclusive to it")
                    if (v1.consts !is ConstSet.C && v1.writeCmdPtrSet !is CmdPointerSet.CSet) {
                        continue
                    }
                    b.entries.asSequence().mapNotNull { kv ->
                        k1.intersect(kv.key).let {
                            it as? ByteRange.OverlapEffect.WithConcreteModel
                        }?.takeIf {
                            (!strictContainmentOnly || it is ByteRange.OverlapEffect.StrictlyContainedWithin)
                        }?.to(kv)
                    }.forEach { (interModel, kv) ->
                        check(k1 !in joined) {
                            "$k1 is supposed to be exclusive to $a (not in $b) but we found it already in the merging: $joined"
                        }
                        val (k2, v2) = kv
                        check(k2 !in joined) {
                            "This makes no sense: $a, $b: $k2 & $k1"
                        }
                        val newKv = interModel.applyTo(k1)
                        check(newKv !in joined) {
                            "ByteRanges are broken, have intersection between two keys $k1 in $a and $k2 in $b, but it's a common key"
                        }
                        val narrowConst = interModel.applyTo(v1.consts)
                        val narrowWrites = interModel.applyTo(v1.writeCmdPtrSet)
                        val (otherConst, otherWrites) = when(interModel) {
                            is ByteRange.OverlapEffect.StrictlyContainedWithin -> {
                                check(newKv == k2)
                                v2.consts to v2.writeCmdPtrSet
                            }
                            is ByteRange.OverlapEffect.Truncation -> {
                                val inversion = k2.intersect(k1)
                                check(inversion is ByteRange.OverlapEffect.Truncation && inversion.applyTo(k2) == newKv)
                                inversion.applyTo(v2.consts) to inversion.applyTo(v2.writeCmdPtrSet)
                            }
                        }

                        val newVal = HeapInt(
                            consts = narrowConst.join(otherConst),
                            writeCmdPtrSet = narrowWrites.join(otherWrites),
                            storageSet = StorageSet.Nondet,
                            returnVal = null,
                            symbolicValueSource = v2.symbolicValueSource?.takeIf {
                                v1.symbolicValueSource == it
                            }
                        )
                        if(newVal == HeapInt.Nondet) {
                            return@forEach
                        }
                        check(newKv !in joined) {
                            "Odd, have intersection of $k1 by $k2 but it appears in the map already"
                        }
                        joined[newKv] = newVal
                    }
                }
            }

        }

    }

    data class ReturnPointer(val lastCall: CmdPointer, val offset: BigInteger)

    /**
     * Unlike the numeric analysis, we track sets of constants for the heap values. This gives better precision if
     * two call buffers are initialized in two different branches.
     */
    data class HeapInt(
        val consts: ConstSet,
        val writeCmdPtrSet: CmdPointerSet,
        val storageSet: StorageSet,
        val returnVal: ReturnPointer?,
        val symbolicValueSource: DecoderAnalysis.BufferAccessPath?
    ) {
        companion object {
            val Nondet = HeapInt(ConstSet.Nondet, CmdPointerSet.Nondet, StorageSet.Nondet, null, null)
        }

        fun join(other: HeapInt): HeapInt =
            HeapInt(
                consts = this.consts.join(other.consts),
                writeCmdPtrSet = this.writeCmdPtrSet.join(other.writeCmdPtrSet),
                storageSet = this.storageSet.join(other.storageSet),
                returnVal = other.returnVal?.takeIf {
                    it == this.returnVal
                },
                symbolicValueSource = other.symbolicValueSource?.takeIf {
                    it == this.symbolicValueSource
                }
            )
    }

    sealed class ReturnDataState {
        abstract fun join(p: ReturnDataState) : ReturnDataState
        data object None : ReturnDataState() {
            override fun join(p: ReturnDataState) : None {
                return this
            }
        }

        data class LastCall(
            val copyState: CopyState,
            val expectedSize: BigInteger,
            val callLocation : CmdPointer,
            val outputPointer: Set<IndexedWritableSet.IndexedNode>,
        ) : ReturnDataState() {

            enum class CopyState {
                COPIED,
                UNKNOWN,
                UNCOPIED
            }

            override fun join(p: ReturnDataState): ReturnDataState {
                if(p !is LastCall) {
                    check(p is None)
                    return p
                }
                val (thisCopyJoin, pCopyJoin) = if(p.copyState != this.copyState) {
                    this.copy(
                        copyState = CopyState.UNKNOWN
                    ) to p.copy(copyState = CopyState.UNKNOWN)
                } else {
                    this to p
                }
                return if(thisCopyJoin == pCopyJoin) {
                    thisCopyJoin
                } else {
                    None
                }
            }
        }
    }

    /**
     * Reduced product of the heap state and the numeric/offset state. (and more)
     *
     * This does a lot now, should really rename this class
     */
    data class SigHashState(
        val nodes: NodeState,
        val storageSlots: Map<TACSymbol.Var, StorageSet>,
        val returnModel: ReturnDataState,
        val abiState: ABIState
    ) {
        fun forEachNode(pts: IPointsToSet, f: (NodeState, PTANode) -> NodeState) : NodeState {
            return pts.nodes.fold(nodes, f)
        }

        fun writeEachNode(pts: WritablePointsToSet, c: HeapInt) : NodeState {
            return this.nodes.modelWrite(v = pts) { _, _, cb -> cb(c) }
        }

        fun forEachIndexedNode(pts: IndexedWritableSet, f: (NodeState, IndexedWritableSet.IndexedNode) -> NodeState) : NodeState {
            return pts.indexed.fold(nodes, f)
        }

        fun join(other: SigHashState) : SigHashState {
            return SigHashState(
                nodes = nodes.join(other.nodes),
                storageSlots = storageSlots.pointwiseBinop(other.storageSlots, StorageSet.Nondet, StorageSet::join),
                returnModel = this.returnModel.join(other.returnModel),
                abiState = this.abiState.join(other.abiState)
            )
        }
    }

    /**
     * Describes the values encoded into a buffer
     */
    sealed class EncodedValue {
        /**
         * A symbol, described by [paths], with type [ty] and, if in memory, represented by the [aVal] set of
         * points to nodes
         */
        data class Symbol(
            val paths: Set<ObjectPath>,
            override val ty: HeapType,
            val aVal: Set<PTANode>?,
            val fieldPointers: Map<PartitionField, EncodedPartitions>?
        ) : EncodedValue() {
            override fun joinWith(v: EncodedValue): EncodedValue? {
                /*
                  jtoman: replaced some intersection based join which had an unclear interpretation
                 */
                if(v != this) {
                    return null
                }
                return this
            }
        }

        data class Const(val c: BigInteger) : EncodedValue() {
            override val ty: HeapType
                get() = HeapType.Int

            override fun joinWith(v: EncodedValue): EncodedValue? {
                if(v !is Const || v.c != this.c) {
                    return null
                }
                return this
            }
        }

        abstract val ty: HeapType

        /**
         * A summary value means that we no longer know the exact identity/contents (the constant/object paths) within
         * a buffer, but we know its abstract value and type. This can occur if a summary abstract location (i.e.,
         * allocated multiple times) is the target of an encoding process
         */
        fun toSummary() = when(this) {
            is Const -> EncodingSummary(abstractVal = null, ty = ty)
            is Symbol -> EncodingSummary(abstractVal = aVal, ty = ty)
        }

        abstract fun joinWith(v: EncodedValue) : EncodedValue?
    }


    /**
     * A "summary" of an encodded value in a buffer: we know its type and the abstract value that represents it, but not its
     * exact identity
     */
    data class EncodingSummary(
        val ty: HeapType,
        val abstractVal: Set<PTANode>?
    )

    /**
     * The location of the buffer we are decoding from
     */
    @Treapable
    sealed class DecodingSource {
        /**
         * An in-memory array, with abstract value [node] and (if available) stored in variable [sym]
         */
        data class Memory(val node: Set<PTANode>, val sym: TACSymbol.Var?) : DecodingSource()

        /**
         * From the (singleton) calldata buffer
         */
        object Calldata : DecodingSource() { override fun hashCode() = utils.hashObject(this) }
    }

    /**
     * When identifying a decoded object, specifies both the buffer itself (via [source]) and the path within that buffer
     * (via [path])
     */
    @Treapable
    data class DecodingPayload(val source: DecodingSource, val path: DecoderAnalysis.BufferAccessPath) : Serializable

    /**
     * Attached to abstract nodes (by convention, the nodes corresopnding to a bytes array),
     * describing the encoded values within that array
     */
    sealed class EncodingPayload {
        /**
         * We know exactly the encoding: where it was done, and the precise identities of the encoded values (via [EncodedValue]
         */
        data class FullEncode(val where: CmdPointer, val model: Map<BigInteger, EncodedValue>, val encodeId: Int) : EncodingPayload()

        /**
         * We only know the type and shapes of the encoded values. Instrumentation will be required to resolve their precise
         * identities. This can occur when an abstract location can be allocated multiple times, e.g., an abi.encode
         * call within a loop
         *
         * Q) What if [where] is different for the different possible encodings?
         * A) These are all attached to a single PTA node, wich maps one to one to an allocation site; thus in-memory
         * encoded buffer will have exactly one point of allocation (and thus, one point of encoding), ensure
         * [where] is singleton for each PTA node
         *
         * TODO(jtoman): this instrumentation isn't actually done yet, if we hit a call that has Instrument for its
         * encoded buffer, we give up
         */
        data class Instrument(val where: CmdPointer, val model: Map<BigInteger, EncodingSummary>) : EncodingPayload()

        /**
         * The node (likely the scratch pointer) was copied at [where] from [node], that node should be consulted for the
         * encoded contents.
         *
         * FIXME(jtoman): why not just copy the abstract state?
         */
        data class CopyFrom(val where: CmdPointer, val node: PTANode) : EncodingPayload()
    }

    /**
     * [decodeObjects] records that the abstract object in the domain is the result of decoding some path
     * out of a buffer, as represented by an [ObjectPathGen] object rooted at an instance of [DecodingPayload]
     */
    data class ABIState(val encoded: TreapMap<PTANode, EncodingPayload>, val decodeObjects: TreapMap<PTANode, ObjectPathGen<DecodingPayload>>) {
        fun killNode(nodes: Collection<PTANode>): ABIState {
            val s = nodes.toSet()
            val encodeMap = encoded.retainAll { (ptaNode, encodingPayload) ->
                ptaNode !in s && when(encodingPayload) {
                    is EncodingPayload.CopyFrom -> encodingPayload.node !in s
                    is EncodingPayload.FullEncode -> !encodingPayload.model.any { (_, ev) ->
                        ev is EncodedValue.Symbol && (ev.aVal?.containsAny(s) == true)
                    }
                    is EncodingPayload.Instrument -> !encodingPayload.model.any { (_, ev) ->
                        ev.abstractVal?.containsAny(s) == true
                    }
                }
            }

            val decodeMap = decodeObjects.retainAll { (ptaNode, objectPathGen) ->
                ptaNode !in s || when(val src = objectPathGen.root.source) {
                    DecodingSource.Calldata -> false
                    is DecodingSource.Memory -> !src.node.containsAny(s)
                }
            }
            return ABIState(
                encoded = encodeMap, decodeObjects = decodeMap
            )
        }

        fun join(other: ABIState) : ABIState {
            return ABIState(
                encoded = this.encoded.pointwiseBinopOrNull(other.encoded) { a, b ->
                    when(a) {
                        is EncodingPayload.CopyFrom -> if(b != a) { null } else { a }
                        is EncodingPayload.FullEncode -> {
                            if(b is EncodingPayload.FullEncode) {
                                if(b.where != a.where || b.model.keys != a.model.keys || b.encodeId != a.encodeId) {
                                    return@pointwiseBinopOrNull null
                                }
                                /**
                                 * This is effectively a strict join: the join operation on encoded values is
                                 * basically equality
                                 */
                                b.model.entries.monadicMap { (k, v) ->
                                    val v2 = a.model[k]!!
                                    k `to?` v2.joinWith(v)
                                }?.toMap()?.let {
                                    EncodingPayload.FullEncode(b.where, it, b.encodeId)
                                }
                            } else if(b !is EncodingPayload.Instrument) { // TODO(jtoman): implement better join
                                null
                            } else {
                                null
                            }
                        }
                        is EncodingPayload.Instrument -> when(b) {
                            is EncodingPayload.CopyFrom -> null
                            is EncodingPayload.FullEncode -> {
                                /*
                                  This case makes the join operation non-commutative
                                 */
                                if(b.where != a.where || b.model.keys != a.model.keys) {
                                    null
                                } else {
                                    a.takeIf {
                                        it.model == b.model.mapValues { it.value.toSummary() }
                                    }
                                }
                            }
                            is EncodingPayload.Instrument -> a.takeIf {
                                a == b
                            }
                        }
                    }
                },
                decodeObjects = this.decodeObjects.pointwiseBinopOrNull(decodeObjects) { a, b ->
                    a.joinOrNull(b)
                }
            )
        }
    }

    private class Worker(private val m: ITACMethod, private val pta: IPointsToInformation) {
        private val p = m.code as CoreTACProgram
        private val g: TACCommandGraph = p.analysisCache.graph

        private fun NodeState.andKillBuffer() = this.copy(bufferContents = null)

        /**
         * "psuedo" because this buffer is never explicitly constructed, we're inventing it like it was built
         * by some sequence of writes.
         */
        private val pseudoCalldataModel: TreapMap<ByteRange, HeapInt>? by lazy d@{
            val cd = m.calldataEncoding as? CalldataEncoding ?: return@d null
            if(!cd.valueTypesArgsOnly || cd.expectedCalldataSize == null) {
                return@d null
            }
            fun CalldataByteRange.toNative() = ByteRange(
                this.from, this.to
            )
            val builder = treapMapBuilderOf<ByteRange, HeapInt>()
            var offsIt = if(cd.sighashSize != BigInteger.ZERO) {
                val range = CalldataByteRange(
                    BigInteger.ZERO, cd.sighashSize - BigInteger.ONE
                )
                val offs = cd.byteOffsetToScalar[range] ?: return@d null
                val absVal = HeapInt(
                    symbolicValueSource = null,
                    returnVal = null,
                    writeCmdPtrSet = CmdPointerSet.CSet(setOf(
                        CmdPointerSet.CSet.BufferSymbol.Global(offs) to range.toNative()
                    )),
                    storageSet = StorageSet.Nondet,
                    consts = ConstSet.Constant(m.sigHash!!.n)
                )
                builder[range.toNative()] = absVal
                cd.sighashSize
            } else {
                BigInteger.ZERO
            }
            while(offsIt < cd.expectedCalldataSize) {
                val expectedRange = CalldataByteRange(
                    offsIt, offsIt + EVM_WORD_SIZE - BigInteger.ONE
                )
                val scalar = cd.byteOffsetToScalar[expectedRange] ?: return@d null
                val absVal = HeapInt(
                    symbolicValueSource = null,
                    returnVal = null,
                    writeCmdPtrSet = CmdPointerSet.CSet(setOf(
                        CmdPointerSet.CSet.BufferSymbol.Global(scalar) to CmdPointerSet.fullWord
                    )),
                    consts = ConstSet.Nondet,
                    storageSet = StorageSet.Set(setOf(
                        SymbolicAddress.CallDataInput(
                            inputArg = scalar,
                            offset = offsIt
                        )
                    ))
                )
                builder[expectedRange.toNative()] = absVal
                offsIt += EVM_WORD_SIZE
            }
            builder.build()
        }

        private fun stepNodes(st: SigHashState, ltacCmd: LTACCmd) : NodeState {
            if (ltacCmd.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                ltacCmd.cmd.base == TACKeyword.MEMORY.toVar()
            ) {
                if(ltacCmd.ptr in mcopyZeroOperations) {
                    return st.nodes
                }
                val base = pta.fieldNodesAt(ltacCmd.ptr, ltacCmd.cmd.loc)
                val typ = base?.type ?: IPointsToSet.Type.UNKNOWN
                if (typ != IPointsToSet.Type.INT) {
                    return st.nodes
                }
                val writeSet = CmdPointerSet.Singleton(ltacCmd.ptr, ltacCmd.cmd.value)
                val writtenInt = when (ltacCmd.cmd.value) {
                    is TACSymbol.Var -> {
                        val storageSet = st.storageSlots[ltacCmd.cmd.value] ?: StorageSet.Nondet
                        pta.query(ConstantValue(v = ltacCmd.ptr, x = ltacCmd.cmd.value))?.let {
                            HeapInt(
                                consts = ConstSet.Constant(it),
                                writeCmdPtrSet = writeSet,
                                storageSet = storageSet,
                                returnVal = null,
                                symbolicValueSource = null
                            )
                        } ?: HeapInt(
                            consts = ConstSet.Nondet,
                            writeCmdPtrSet = writeSet,
                            storageSet = storageSet,
                            returnVal = null,
                            symbolicValueSource = null
                        )
                    }

                    is TACSymbol.Const -> HeapInt(
                        consts = ConstSet.Constant(ltacCmd.cmd.value.value),
                        writeCmdPtrSet = writeSet,
                        storageSet = StorageSet.Nondet,
                        returnVal = null,
                        symbolicValueSource = null
                    )
                }
                if (base !is IndexedWritableSet) {
                    return st.writeEachNode(pts = base!!, writtenInt)
                }
                return st.forEachIndexedNode(base) { m, n ->
                    if (base.offsetUpdate() == WritablePointsToSet.UpdateType.STRONG ||
                        (base.strongBasePointer && base.elementSize == BigInteger.ONE)
                    ) {
                        m.strongUpdate(n, writtenInt)
                    } else {
                        m.weakUpdate(n, writtenInt)
                    }
                }
            } else if(ltacCmd.cmd is TACCmd.Simple.ByteLongCopy && ltacCmd.cmd.dstBase == TACKeyword.MEMORY.toVar() &&
                TACMeta.MCOPY_BUFFER in ltacCmd.cmd.srcBase.meta &&
                ltacCmd.cmd.srcOffset == TACSymbol.Zero &&
                ltacCmd.cmd.dstOffset is TACSymbol.Var && ltacCmd.cmd.srcBase == st.nodes.bufferContents?.first
            ) {
                val tgt = (pta.fieldNodesAt(ltacCmd.ptr, ltacCmd.cmd.dstOffset) as? IndexedWritableSet) ?: return st.nodes
                return copyToTarget(
                    where = ltacCmd,
                    output = tgt,
                    s = st,
                    fallback = ::killCopyOutputNodes
                ) {
                    st.nodes.bufferContents.second
                }.nodes.andKillBuffer()
            } else if(ltacCmd.cmd is TACCmd.Simple.ByteLongCopy &&
                TACMeta.MCOPY_BUFFER in ltacCmd.cmd.dstBase.meta &&
                ltacCmd.cmd.dstOffset == TACSymbol.Zero && ltacCmd.cmd.srcOffset is TACSymbol.Var && st.nodes.bufferContents == null) {
                /*
                  XXX(jtoman): we don't do a bounds check. Should we?
                 */
                val base = pta.fieldNodesAt(ltacCmd.ptr, ltacCmd.cmd.srcOffset) as? IndexedWritableSet ?: return st.nodes
                val projected = base.indexed.monadicMap { inode ->
                    st.nodes.projectFrom(inode, null).onRight {
                        logger.warn {
                            "At mcopy src $ltacCmd in ${g.name}, failed to project from $inode"
                        }
                    }.leftOrNull()
                }?.takeIf { it.isNotEmpty() }?.reduce(NodeState.Companion::exactMerge) ?: return st.nodes
                return st.nodes.copy(bufferContents = ltacCmd.cmd.dstBase to projected)
            } else if (ltacCmd.cmd is TACCmd.Simple.ByteLongCopy &&
                ltacCmd.cmd.dstBase == TACKeyword.MEMORY.toVar()
            ) {
                val base = pta.fieldNodesAt(ltacCmd.ptr, ltacCmd.cmd.dstOffset)
                val typ = base?.type ?: IPointsToSet.Type.UNKNOWN
                if (typ != IPointsToSet.Type.INT) {
                    return st.nodes
                }
                if (base !is IndexedWritableSet) {
                    return st.forEachNode(base!!) { nodeState, ptaNode ->
                        nodeState.copy(direct = nodeState.direct - ptaNode)
                    }
                }
                if(ltacCmd.cmd.srcBase == TACKeyword.CALLDATA.toVar() && constantValueAt(ltacCmd.cmd.srcOffset, ltacCmd) == BigInteger.ZERO && ltacCmd.cmd.length is TACSymbol.Var &&
                    pta.query(QueryInvariants(ltacCmd.ptr) {
                        ltacCmd.cmd.length `=` TACKeyword.CALLDATASIZE.toVar()
                    }) != null && pseudoCalldataModel != null) {
                    val source = pseudoCalldataModel!!
                    /*
                     * In principle, there might be some way for to intelligently do something with copies
                     * from calldata from constant offsets that aren't 0 and for sizes that are all of calldata.
                     * For the time being however, we choose to be overly strict, while suppporting the only thing we care about
                     */
                    val toCopy = NodeState.projectFrom(
                        source, BigInteger.ZERO, null
                    )
                    val outWriter = st.nodes.getCopyWriter(
                        base, ltacCmd
                    ) ?: return st.nodes.killAffectedNodes(base)
                    return outWriter(toCopy)
                }
                return st.nodes.killAffectedNodes(base)
            } else if (isCmdMemDataCopy(ltacCmd)) {
                /* BG - 20230526
                 * Handle the highly restricted case of a scratch memory to memory copy via the identity contract.
                 * Vyper likes to copy ABI buffers around in fun and interesting ways
                 */
                val callCmd = ltacCmd.narrow<TACCmd.Simple.CallCore>().cmd
                val inBase =
                    pta.fieldNodesAt(ltacCmd.ptr, callCmd.inOffset) as? IndexedWritableSet ?: return st.nodes
                val outBase =
                    pta.fieldNodesAt(ltacCmd.ptr, callCmd.outOffset) as? IndexedWritableSet ?: return st.nodes
                val sizeApprox = intervalApproxAt(
                    callCmd.inSize,
                    it = ltacCmd
                )
                val outputWriter = st.nodes.getCopyWriter(outBase, ltacCmd, optimistic = true) ?: return st.nodes
                if (sizeApprox == null || sizeApprox.ub > ABI_SIZE_BOUND) {
                    return st.nodes
                }
                val projected = inBase.indexed.singleOrNull()?.let {
                    st.nodes.projectFrom(it, sizeApprox)
                }?.leftOrNull() ?: return st.nodes
                return outputWriter(projected)
            } else if(ltacCmd.maybeAnnotation(ReturnBufferAnalysis.ConstantReturnBufferAllocComplete.META_KEY) != null) {
                /**
                 * Use the hints left by the [ReturnBufferAnalysis] to populate the pointed-to object of the
                 * [analysis.alloc.ReturnBufferAnalysis.ConstantReturnBufferAllocComplete.outputVar] indicating
                 * it holds the result of the return. NB this will likely happen very close to where the [startBlock]
                 * population happen, as these analyses both look for the same information.
                 */
                val outputBlock = ltacCmd.maybeAnnotation(ReturnBufferAnalysis.ConstantReturnBufferAllocComplete.META_KEY)!!
                val outputVar = outputBlock.outputVar
                if(st.returnModel !is ReturnDataState.LastCall) {
                    return st.nodes
                }
                val obj = pta.reachableObjects(where = ltacCmd.ptr, v = outputVar) ?: return run {
                    logger.warn {
                        "Output of return alloc at $ltacCmd was expected to be a freshly allocated object, but PTA doesn't think so"
                    }
                    st.nodes
                }
                val expectedFields = (st.returnModel.expectedSize / EVM_WORD_SIZE).intValueExact()
                if(obj !is TypedPointsToSet || obj.typeDesc != TypedPointsToSet.SimpleTypeDescriptor.ReferenceType.Struct(
                        expectedFields
                    )) {
                    return st.nodes
                }
                var innerIt = st.nodes
                for(i in 0 until expectedFields) {
                    val offset = (i * EVM_WORD_SIZE_INT).toBigInteger()
                    val field = pta.fieldNodesAt(where = ltacCmd.ptr, offset = offset, v = obj) ?: return st.nodes
                    val value = HeapInt(
                        writeCmdPtrSet = CmdPointerSet.Nondet,
                        consts = ConstSet.Nondet,
                        returnVal = ReturnPointer(
                            lastCall = st.returnModel.callLocation,
                            offset = offset
                        ),
                        storageSet = StorageSet.Set(setOf(
                            SymbolicAddress.ReturnData(
                                callLocation = st.returnModel.callLocation,
                                offset = offset
                            )
                        )),
                        symbolicValueSource = null
                    )
                    innerIt = innerIt.modelWrite(field, value)
                }
                return innerIt
            } else {
                return st.nodes
            }
        }

        private fun isCmdMemDataCopy(ltacCmd: LTACCmd) =
            ltacCmd.cmd is TACCmd.Simple.CallCore &&
                isContractUniverseEthereum &&
                ltacCmd.cmd.to is TACSymbol.Const &&
                ltacCmd.cmd.to.value == IDENTITY_PRECOMPILED_ADDRESS &&
                ltacCmd.cmd.outBase == TACKeyword.MEMORY.toVar() &&
                ltacCmd.cmd.inBase == TACKeyword.MEMORY.toVar()



        private fun step(st: SigHashState, ltacCmd: LTACCmd) : SigHashState {
            val steppedNodes : NodeState = stepNodes(st, ltacCmd)
            val steppedStorage = stepStorage(
                ltacCmd,
                st
            )

            val abi = this.stepABIState(ltacCmd, st.abiState)

            val (gcNodeState, gcABIState) = if(pta is AbstractGarbageCollection) {
                // BG - allow memory data copies to be transparent wrt scratch
                val nodes = pta.gcAt(where = ltacCmd).takeIf { !(isCmdMemDataCopy(ltacCmd)) }.orEmpty()
                val nodeState = NodeState(
                    steppedNodes.direct - nodes,
                    byteAddressed = steppedNodes.byteAddressed - nodes,
                    indexAddressed = steppedNodes.indexAddressed - nodes,
                    bufferContents = if(nodes.isNotEmpty()) {
                        null
                    } else {
                        steppedNodes.bufferContents
                    }
                )
                val abiState = ABIState(
                    encoded = abi.encoded - nodes,
                    decodeObjects = abi.decodeObjects - nodes,
                )
                nodeState to abiState
            } else {
                steppedNodes to abi
            }

            val symbolicSourceState = gcNodeState.letIf(ltacCmd.ptr in decodePoints) {
                this.annotateSymbolicSighash(
                    gcNodeState, ltacCmd
                )
            }

            val lastCallState = stepReturnState(ltacCmd, st)
            return SigHashState(symbolicSourceState, steppedStorage, lastCallState, abiState = gcABIState)
        }

        /**
         * If we are at a decode, find the PTA nodes that correspond to byte arrays decoded out of calldata, and mark those
         * with the buffer access path from which they were copied. If these byte arrays flow to an external call,
         * we will use the information about their "source" path to match up to sighash information recorded in THIS method's
         * caller to infer the sighash
         */
        private fun annotateSymbolicSighash(nodeState: NodeState, ltacCmd: LTACCmd) : NodeState {
            val dec = decodePoints[ltacCmd.ptr] ?: return nodeState.also {
                logger.warn {
                    "At $ltacCmd expected to find a decode point for annotation, but couldn't find one"
                }

            } // this *really* shouldn't happen, we only call this if ltacCmd.ptr is in the map
            if(dec.sourceBuffer != TACKeyword.CALLDATA.toVar()) {
                return nodeState
            }
            val representative = dec.output.firstOrNull() ?: return nodeState
            val outputObj = pta.reachableObjects(ltacCmd.ptr, representative) ?: return nodeState
            return with(ltacCmd.ptr) {
                typeDrivenAnnotationLoop(
                    outputObj, dec.sourcePath, dec.decodedType, nodeState
                )
            }
        }

        /**
         * You've seen this a thousand times by now: driven by [ty], travese [obj], an abstract object not field, and extend [path]
         * "in parallel". When we reach a [ty] that is a [HeapType.ByteArray], update the [st] to record that the first 4 bytes
         * came from [path] in the source buffer.
         */
        context(CmdPointer)
        private fun typeDrivenAnnotationLoop(obj: IPointsToSet, path: DecoderAnalysis.BufferAccessPath, ty: HeapType, st: NodeState) : NodeState {
            // we're only interested in annotating byte arrays, if this type is not dynamic, it cannot hold a byte array
            if(!ty.isDynamic()) {
                return st
            }
            when(ty) {
                is HeapType.Array -> return st // no *real* way to do this
                HeapType.ByteArray -> {
                    val field = pta.arrayFieldAt(this@CmdPointer, obj) as? IndexedWritableSet ?: return st
                    if(!field.strongBasePointer) {
                        return st
                    }
                    val out = field.indexed.singleOrNull() ?: return st
                    return st.strongUpdateByte(out, ByteRange(BigInteger.ZERO, 3.toBigInteger()), HeapInt(
                        ConstSet.Nondet, storageSet = StorageSet.Nondet, symbolicValueSource = path, writeCmdPtrSet = CmdPointerSet.Nondet, returnVal = null
                    ))
                }
                is HeapType.OffsetMap -> {
                    var bufferOffset = BigInteger.ZERO
                    var acc = st
                    for((fOffs, fTy) in ty.fieldTypes.toSortedMap()) {
                        val fieldObj = pta.reachableObjects(this@CmdPointer, obj, fOffs)
                        if(fieldObj == null) {
                            bufferOffset += fTy.sizeInArray()
                            continue
                        }
                        acc = typeDrivenAnnotationLoop(
                            obj = fieldObj, path = DecoderAnalysis.BufferAccessPath.Offset(offset = bufferOffset, base = path).letIf(fTy.isDynamic()) {
                                DecoderAnalysis.BufferAccessPath.Deref(it)
                            }, ty = fTy, st = acc
                        )
                        bufferOffset += fTy.sizeInArray()
                    }
                    return acc
                }
                HeapType.EmptyArray,
                HeapType.Int,
                is HeapType.TVar -> return st
            }
        }

        private val encodePoints = g.commands.parallelStream().mapNotNull {
            it.ptr `to?` it.maybeAnnotation(ABIEncodeComplete.META_KEY)
        }.collect(Collectors.toMap({it.first}, {it.second}))
        private val decodePoints = g.commands.parallelStream().mapNotNull {
            it.ptr `to?` it.maybeAnnotation(ABIDecodeComplete.META_KEY)
        }.collect(Collectors.toMap({it.first}, {it.second}))

        private val mcopyZeroOperations = g.commands.parallelStream().mapNotNull {
            it.ptr `to?` it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf { lc ->
                TACMeta.MCOPY_BUFFER in lc.cmd.srcBase.meta
            }
        }.mapNotNull { (copy, where) ->
            val dst = where.cmd.dstOffset as? TACSymbol.Var ?: return@mapNotNull null
            val len = where.cmd.length as? TACSymbol.Var ?: return@mapNotNull null
            val nextZeroWrite = g.iterateBlock(copy, excludeStart = true).takeWhile {
                it.cmd.getLhs() != dst && it.cmd.getLhs() != len && it.cmd !is TACCmd.Simple.LongAccesses
            }.firstOrNull {
                it.cmd is TACCmd.Simple.DirectMemoryAccessCmd
            }?.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf { lc ->
                lc.cmd.value == TACSymbol.Zero
            } ?: return@mapNotNull null
            if(pta.query(QueryInvariants(nextZeroWrite.ptr) {
                dst + len `=` nextZeroWrite.cmd.loc
            }) == null) {
                return@mapNotNull null
            }
            nextZeroWrite.ptr
        }.collect(Collectors.toSet())

        private fun stepABIState(ltacCmd: LTACCmd, abiState: ABIState): ABIState {
            if(ltacCmd.ptr in encodePoints && ltacCmd.ptr in decodePoints) {
                throw CallGraphBuilderFailedException("Command $ltacCmd is both encode and decode", ltacCmd)
            }
            if(ltacCmd.ptr in encodePoints) {
                val enc = encodePoints[ltacCmd.ptr]!!
                val outputNode = when(enc.target) {
                    is EncodeOutput.Alloc -> enc.target.data.monadicMap {
                        pta.reachableObjects(ltacCmd.ptr, it)
                    }?.monadicMap {
                        pta.arrayFieldAt(ltacCmd.ptr, it)
                    }?.flatMap { it.nodes }?.uniqueOrNull()
                    EncodeOutput.Scratch -> {
                        (pta as? WithScratchPointer)?.scratchPointer
                    }
                } ?: return abiState
                val written = enc.buffer.buffer.mapValues { (_, ev) ->
                    when(ev) {
                        is TopLevelValue.Constant -> EncodedValue.Const(ev.k)
                        is TopLevelValue.Path -> {
                            EncodedValue.Symbol(
                                ty = ev.ty,
                                paths = ev.paths,
                                fieldPointers = ev.encodedPartitions,
                                /*
                                 if the object being encoded is rooted at a variable, get its abstract value

                                 in principle, tracking this will let us determine when an encoded value itself
                                 is an encoded buffer (it does happen!) But we don't use this information yet)
                                */
                                aVal = ev.paths.mapNotNull {
                                    ((it as? ObjectPathGen.Root)?.oRoot as? ObjectPathAnalysis.ObjectRoot.V)?.v
                                }.flatMapTo(mutableSetOf()) {
                                    pta.reachableObjects(ltacCmd.ptr, it)?.nodes ?: listOf()
                                }.takeIf { it.isNotEmpty() }
                            )
                        }
                    }
                }
                /*
                  Join up with the existing representation of the outputNode
                 */
                val p : EncodingPayload = abiState.encoded[outputNode]?.let {
                    /*
                    In the worst case, just forget everything about the output
                     */
                    val killState by lazy {
                        abiState.copy(
                            encoded = abiState.encoded - outputNode
                        )
                    }
                    /*
                     Effectively, turn the full encode into an instrument with Summary values, and ensure those
                     summary values match the existing summary. If the existing is a full encode, transform both, and
                     ensure coherence between the two summaries
                     */
                    when(it) {
                        is EncodingPayload.CopyFrom -> return killState
                        // I don't think this is right?? but what if, and hear me out,
                        // we encounter an encode ID that is dominated by the old one?
                        // then just return the new full encode?
                        // seems hacky but it will let me make progress for now
                        is EncodingPayload.FullEncode -> if(it.where != ltacCmd.ptr || it.model.keys != written.keys) {
                            return killState
                        } else {
                            EncodingPayload.Instrument(
                                model = it.model.entries.monadicMap { (k, v) ->
                                    k `to?` v.toSummary().takeIf {
                                        it == written[k]?.toSummary()
                                    }
                                }?.toMap() ?: return killState,
                                where = ltacCmd.ptr
                            )
                        }
                        is EncodingPayload.Instrument -> if(it.where != ltacCmd.ptr || it.model.keys != written.keys) {
                            return killState
                        } else {
                            EncodingPayload.Instrument(
                                model = it.model.entries.monadicMap { (k, v) ->
                                    k `to?` v.takeIf {
                                        it == written[k]?.toSummary()
                                    }
                                }?.toMap() ?: return killState,
                                where = ltacCmd.ptr
                            )
                        }
                    }
                } ?: EncodingPayload.FullEncode(
                    where = ltacCmd.ptr,
                    model = written,
                    encodeId = enc.id
                )
                return abiState.copy(
                    encoded = abiState.encoded + (outputNode to p)
                )
            } else if(ltacCmd.ptr in decodePoints) {
                val dec = decodePoints[ltacCmd.ptr]!!
                val traversal = dec.output.monadicMap {
                    pta.reachableObjects(ltacCmd.ptr, it)
                }?.toSet() ?: return abiState
                if (traversal.any {
                        it.type != dec.decodedType.toPTType()
                    }) {
                    throw CallGraphBuilderFailedException("Disagreement between points to results and decoder " +
                        "analysis, at $ltacCmd, this should be impossible?", ltacCmd)
                }
                val output = mutableMapOf<PTANode, ObjectPathGen<DecodingPayload>>()
                /*
                 Get the source buffer
                 */
                val sourceNode = if (dec.sourceBuffer == TACKeyword.CALLDATA.toVar(ltacCmd.ptr.block.getCallId())) {
                    DecodingSource.Calldata
                } else {
                    DecodingSource.Memory(
                        sym = dec.sourceBuffer,
                        node = pta.reachableObjects(ltacCmd.ptr, dec.sourceBuffer)?.nodes?.toSet() ?: return abiState
                    )
                }
                /*
                  Combine it with the path we are decoding
                 */
                val root = DecodingPayload(
                    source = sourceNode,
                    path = dec.sourcePath
                )
                /*
                  Record for reference nodes reached by traversing the PTA relationship according to the decoding type,
                  tagging reachable nodes with path they occupy within the decoded object. This information is currently
                  unused, but it is suspected to help represent (and reason about) the recursive encode "issue".
                 */
                if(!populateDecodedNodes(output, traversal, dec.decodedType, ObjectPathGen.Root(root), ltacCmd.ptr)) {
                    throw CallGraphBuilderFailedException("Failed processing decode of $ltacCmd with type ${dec.decodedType}", ltacCmd)
                }
                return abiState.copy(
                    decodeObjects = abiState.decodeObjects + output
                )
            }
            return abiState
        }

        private fun populateDecodedNodes(
            output: MutableMap<PTANode, ObjectPathGen<DecodingPayload>>,
            traversal: Collection<IPointsToSet>,
            decodedType: HeapType,
            outputPath: ObjectPathGen<DecodingPayload>,
            where: CmdPointer
        ) : Boolean {
            for(pts in traversal) {
                for(n in pts.nodes) {
                    output[n] = outputPath
                }
            }
            when(decodedType) {
                is HeapType.Array -> {
                    /*
                      ints never have reference nodes associated with them, so don't try
                     */
                    if(decodedType.elementType != HeapType.Int) {
                        populateDecodedNodes(
                            output,
                            traversal = traversal.monadicMap {
                                pta.reachableArrayElements(where, it)
                            } ?: return false,
                            outputPath = ObjectPathGen.ArrayElem(parent = outputPath),
                            where = where,
                            decodedType = decodedType.elementType
                        )
                    }
                }
                HeapType.ByteArray,
                HeapType.EmptyArray,
                HeapType.Int -> {
                    return true
                }
                is HeapType.OffsetMap -> {
                    for((offs, ty) in decodedType.fieldTypes) {
                        if(ty == HeapType.Int) {
                            continue
                        }
                        if(!populateDecodedNodes(
                                output,
                                traversal = traversal.monadicMap {
                                    pta.reachableObjects(where, it, offs)
                                } ?: return false,
                                decodedType = ty,
                                outputPath = ObjectPathGen.Field(
                                    offset = offs,
                                    parent = outputPath
                                ),
                                where = where
                            )) {
                            return false
                        }
                    }
                }
                is HeapType.TVar -> throw CallGraphBuilderFailedException("Found unresolved type during decoding of $outputPath @ $where", g.elab(where))
            }
            return true
        }

        private fun stepReturnState(
            ltacCmd: LTACCmd,
            st: SigHashState
        ): ReturnDataState {
            /**
             * record information about the call core, including the output PTA nodes and the constant size.
             * Note that we do *not* populate that information until we know that the caller returned at least
             * that much data, see [startBlock]
             */
            val basicStep = if (ltacCmd.cmd is TACCmd.Simple.CallCore) {
                constantValueAt(ltacCmd.cmd.outSize, ltacCmd)?.let {
                    val outputNodes = pta.fieldNodesAt(ltacCmd.ptr, ltacCmd.cmd.outOffset)?.let {
                        it as? IndexedWritableSet
                    }?.takeIf {
                        it.offsetUpdate() == WritablePointsToSet.UpdateType.STRONG
                    }?.indexed?.toSet().orEmpty()
                    ReturnDataState.LastCall(
                        callLocation = ltacCmd.ptr,
                        copyState = ReturnDataState.LastCall.CopyState.UNCOPIED,
                        expectedSize = it,
                        outputPointer = outputNodes,
                    )
                } ?: ReturnDataState.None
            } else {
                st.returnModel
            }
            if(basicStep !is ReturnDataState.LastCall) {
                return basicStep
            }
            val withGc = if (pta is AbstractGarbageCollection && ltacCmd.cmd !is TACCmd.Simple.CallCore) {
                val toRemove = pta.gcAt(ltacCmd)
                basicStep.copy(
                    outputPointer = basicStep.outputPointer.filterToSet {
                        it.node !in toRemove
                    }
                )
            } else {
                basicStep
            }
            return withGc
        }

        private fun stepStorage(
            ltacCmd: LTACCmd,
            st: SigHashState,
        ) : Map<TACSymbol.Var, StorageSet> {
            return when (val cmd = ltacCmd.cmd) {
                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    val constOffset = when (cmd.loc) {
                        is TACSymbol.Const -> cmd.loc.value
                        is TACSymbol.Var -> {
                            pta.query(ConstantValue(ltacCmd.ptr, cmd.loc))
                        }
                    }
                    if (constOffset != null) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.ConstantSlot(ltacCmd.ptr, constOffset, null).lift())
                    } else {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.UnresolvedRead(ltacCmd.ptr).lift())
                    }
                }

                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    val rhsOpaqueIdentityRemoved =
                        if (cmd.rhs is TACExpr.Apply &&
                            (cmd.rhs.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is TACBuiltInFunction.OpaqueIdentity
                        ) {
                            cmd.rhs.ops.single()
                        } else {
                            cmd.rhs
                        }
                    if (rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && rhsOpaqueIdentityRemoved.s.meta.find(TACMeta.STORAGE_KEY) != null) {
                        val slots = when (val sort = rhsOpaqueIdentityRemoved.s.meta.find(TACMeta.SCALARIZATION_SORT)) {
                            is ScalarizationSort.Split -> SymbolicAddress.ConstantSlot(ltacCmd.ptr, sort.idx, null)
                            is ScalarizationSort.Packed -> {
                                when (sort.packedStart) {
                                    is ScalarizationSort.Split -> SymbolicAddress.ConstantSlot(
                                        ltacCmd.ptr,
                                        sort.packedStart.idx,
                                        sort.start.toBigInteger()
                                    )

                                    else -> null
                                }
                            }

                            is ScalarizationSort.UnscalarizedBuffer -> null
                            null -> null
                        }
                        if (slots != null) {
                            st.storageSlots + (cmd.lhs to slots.lift())
                        } else {
                            st.storageSlots - cmd.lhs
                        }
                    } else if (rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && rhsOpaqueIdentityRemoved.s == TACKeyword.ADDRESS.toVar()) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.THIS.lift())
                    } else if (rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && TACMeta.IS_CALLDATA in rhsOpaqueIdentityRemoved.s.meta && rhsOpaqueIdentityRemoved.s.meta.find(
                            TACMeta.WORDMAP_KEY
                        ) != null
                    ) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.CallDataInput(
                            inputArg = rhsOpaqueIdentityRemoved.s,
                            offset = rhsOpaqueIdentityRemoved.s.meta.find(TACMeta.WORDMAP_KEY)!!
                        ).lift())
                    } else if (rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && rhsOpaqueIdentityRemoved.s in st.storageSlots) {
                        st.storageSlots + (cmd.lhs to st.storageSlots[rhsOpaqueIdentityRemoved.s]!!)
                    } else if (rhsOpaqueIdentityRemoved is TACExpr.BinOp.BWAnd && rhsOpaqueIdentityRemoved.operandsAreSyms()) {
                        val o1 = rhsOpaqueIdentityRemoved.o1AsTACSymbol()
                        val o2 = rhsOpaqueIdentityRemoved.o2AsTACSymbol()
                        val o1Const = constantValueAt(o1, ltacCmd)
                        val o2Const = constantValueAt(o2, ltacCmd)
                        if (o1Const == ADDRESS_MASK && o2 is TACSymbol.Var && o2 in st.storageSlots) {
                            st.storageSlots + (cmd.lhs to st.storageSlots[o2]!!)
                        } else if (o2Const == ADDRESS_MASK && o1 is TACSymbol.Var && o1 in st.storageSlots) {
                            st.storageSlots + (cmd.lhs to st.storageSlots[o1]!!)
                        } else {
                            st.storageSlots - cmd.lhs
                        }
                    } else if (rhsOpaqueIdentityRemoved is TACExpr.BinOp.Div) {
                        val o1 = rhsOpaqueIdentityRemoved.o1AsNullableTACSymbol()
                        val o2 = rhsOpaqueIdentityRemoved.o2AsNullableTACSymbol()
                        if (o1 != null && o2 != null) {
                            val o2Const = constantValueAt(o2, ltacCmd)
                            val o1StorageSlot = (st.storageSlots[o1] as? StorageSet.Set)?.slots?.singleOrNull()
                            if (o2Const != null && o2Const in powersOf2 && o1StorageSlot != null && o1StorageSlot is SymbolicAddress.ConstantSlot) {
                                val rightShiftFactor = o2Const.lowestSetBit.toBigInteger()
                                st.storageSlots + (cmd.lhs to SymbolicAddress.ConstantSlot(
                                    ltacCmd.ptr,
                                    o1StorageSlot.number,
                                    rightShiftFactor
                                ).lift())
                            } else {
                                st.storageSlots - cmd.lhs
                            }
                        } else {
                            st.storageSlots - cmd.lhs
                        }
                    } else if(rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && rhsOpaqueIdentityRemoved.s.meta.find(TACBasicMeta.IMMUTABLE_LINK) != null) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.ImmutableReference(rhsOpaqueIdentityRemoved.s.meta.find(TACBasicMeta.IMMUTABLE_LINK)!!).lift())
                    } else if(rhsOpaqueIdentityRemoved is TACExpr.SimpleHash &&
                        rhsOpaqueIdentityRemoved.hashFamily.isContractCreation) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.CreatedContract(ltacCmd.ptr).lift())
                    } else if(rhsOpaqueIdentityRemoved is TACExpr.Sym.Var && rhsOpaqueIdentityRemoved.s.meta.find(TACMeta.CONTRACT_ADDR_KEY) != null) {
                        st.storageSlots + (cmd.lhs to SymbolicAddress.LibraryAddress(rhsOpaqueIdentityRemoved.s.meta.find(TACMeta.CONTRACT_ADDR_KEY)!!).lift())
                    } else {
                        st.storageSlots - cmd.lhs
                    }
                }

                is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                    if (cmd.loc is TACSymbol.Var) {
                        val pta = pta.fieldNodesAt(ltacCmd.ptr, cmd.loc)
                        val storage = pta?.map(object : NodeVisitor<HeapInt> {
                            override fun visit(v: IndexedWritableSet.IndexedNode): HeapInt =
                                st.nodes.readValue(v)

                            override fun visit(v: PTANode): HeapInt = st.nodes.readValue(v)
                        })?.map {
                            it.storageSet
                        }?.takeIf { it.isNotEmpty() }?.reduce(StorageSet::join) ?: StorageSet.Nondet
                        if (storage == StorageSet.Nondet) {
                            st.storageSlots - cmd.lhs
                        } else {
                            st.storageSlots + (cmd.lhs to storage)
                        }
                    } else {
                        st.storageSlots - cmd.lhs
                    }
                }

                is TACCmd.Simple.SummaryCmd -> {
                    val summ = cmd.summ as? InternalCallSummary ?: return st.storageSlots
                    summ.internalExits.fold(st.storageSlots) { acc, internalFuncRet ->
                        acc + (internalFuncRet.s to StorageSet.Set(SymbolicAddress.InternalSummaryOutput(which = summ.id, offset = internalFuncRet.offset)))
                    }
                }

                is TACCmd.Simple.AssigningCmd -> {
                    st.storageSlots - cmd.lhs
                }

                else -> st.storageSlots
            }
        }

        private fun constantValueAt(o2: TACSymbol, ltacCmd: LTACCmd): BigInteger? {
            return constantValueAt(o2, ltacCmd.ptr)
        }

        private fun constantValueAt(o2: TACSymbol, where: CmdPointer): BigInteger? {
            return when (o2) {
                is TACSymbol.Var -> pta.query(ConstantValue(where, o2))
                is TACSymbol.Const -> o2.value
            }
        }

        private fun lowerBoundAt(o2: TACSymbol, where: CmdPointer) : BigInteger {
            return intervalApproxAt(o2,  where)?.getLowerBound() ?: BigInteger.ZERO
        }

        lateinit var exportState: MemoryMap
        var isContractUniverseEthereum: Boolean = true

        val simplifier = ExpressionSimplifier(g, g.cache.def)

        /**
         * Called at the start of the block. Checks whether the path conditions
         * leading to this point are sufficient to show that the last call (if there was one) successfully returned the
         * amount of data requested by the call command in [analysis.icfg.CallGraphBuilder.ReturnDataState.LastCall.callLocation].
         *
         * If so, update the points to node associated with the output location to indicate that it now holds the return values
         * from that call (which can be used for linking later).
         */
        private fun startBlock(initState: SigHashState, block: NBId) : SigHashState {
            if (initState.returnModel !is ReturnDataState.LastCall) {
                return initState
            }
            if (initState.returnModel.copyState != ReturnDataState.LastCall.CopyState.UNCOPIED) {
                return initState
            }
            val start = g.elab(block).commands.first()
            val haveRcBound = pta.query(NumericApproximation(v = start.ptr, x = TACKeyword.RETURNCODE.toVar()))?.let { u: UIntApprox<*> ->
                u.getLowerBound() > BigInteger.ZERO
            } ?: false
            val haveReturnSizeBound = pta.query(NumericApproximation(v = start.ptr, x = TACKeyword.RETURN_SIZE.toVar()))?.let { u : UIntApprox<*> ->
                u.getLowerBound() >= initState.returnModel.expectedSize
            } ?: false
            if(!haveReturnSizeBound || !haveRcBound) {
                return initState
            }
            val returnUpdate = initState.returnModel.copy(
                copyState = ReturnDataState.LastCall.CopyState.COPIED
            )
            var idx = BigInteger.ZERO
            var nodeState = initState.nodes
            while(idx < initState.returnModel.expectedSize) {
                nodeState = initState.returnModel.outputPointer.fold(nodeState) { Curr, indexedOutput ->
                    val returnSlotStart = indexedOutput.index.c
                    Curr.strongUpdateByte(indexedOutput, ByteRange(returnSlotStart + idx, returnSlotStart + idx + 31.toBigInteger()),
                        HeapInt(
                            returnVal = ReturnPointer(initState.returnModel.callLocation, idx),
                            writeCmdPtrSet = CmdPointerSet.Nondet,
                            storageSet = SymbolicAddress.ReturnData(
                                callLocation = initState.returnModel.callLocation,
                                offset = idx
                            ).lift(),
                            consts = ConstSet.Nondet,
                            symbolicValueSource = null
                        )
                    )
                }
                idx += 0x20.toBigInteger()
            }
            return SigHashState(
                returnModel = returnUpdate,
                storageSlots = initState.storageSlots,
                abiState = initState.abiState,
                nodes = nodeState
            )
        }

        fun analyze(scene: IScene?): SigResolutionAnalysisResult.Full {
            scene?.let { isContractUniverseEthereum = (it.getContractUniverse() == ContractUniverse.ETHEREUM) }
            val state = mutableMapOf(
                g.rootBlocks.first().commands.first().ptr to SigHashState(
                    nodes = NodeState(
                        mapOf(), mapOf(), mapOf(), null
                    ),
                    storageSlots = mapOf(),
                    returnModel = ReturnDataState.None,
                    abiState = ABIState(treapMapOf(), treapMapOf())
                )
            )
            exportState = state
            object : StatefulWorklistIteration<NBId, Unit, Unit>() {
                override val scheduler: IWorklistScheduler<NBId> = g.cache.naturalBlockScheduler

                override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                    val block = g.elab(it)
                    val start = block.commands.first()
                    if(start.ptr !in state) {
                        return StepResult.Ok(next = listOf(), result = listOf())
                    }
                    val startState = startBlock(state[start.ptr]!!, it)
                    var s = step(startState, start)
                    for(l in block.commands.drop(1)) {
                        state[l.ptr] = s
                        try {
                            s = step(s, l)
                        } catch(t: Throwable) {
                            throw CallGraphBuilderFailedException("Failed stepping state", l, t)
                        }
                    }
                    s = s.copy(nodes = s.nodes.andKillBuffer())
                    val last = block.commands.last()
                    val lastCmd = last.cmd
                    val next = mutableSetOf<NBId>()
                    if(lastCmd is TACCmd.Simple.SummaryCmd && lastCmd.summ is LoopCopyAnalysis.LoopCopySummary) {
                        val summ = pta.query(CopyLoopValid(lastCmd.summ, block.commands.last().ptr))
                        if(summ != null) {
                            next.add(state.mergeNext(
                                g.elab(lastCmd.summ.skipTarget).commands.first().ptr,
                                modelCopy(s, summ, last),
                                SigHashState::join
                            ))
                        } else {
                            next.add(state.mergeNext(
                                g.elab(lastCmd.summ.originalBlockStart).commands.first().ptr,
                                s,
                                SigHashState::join
                            ))
                        }
                    } else if(lastCmd is TACCmd.Simple.SummaryCmd && lastCmd.summ is InitAnnotation.FourByteWriteSummary) {
                        next.add(state.mergeNext(g.elab(lastCmd.summ.skipTarget).commands.first().ptr, modelFourByteWrite(s, lastCmd.summ, last.ptr), SigHashState::join))
                    } else {
                        for (succ in g.succ(block)) {
                            next.add(state.mergeNext(succ.commands.first().ptr, s, SigHashState::join))
                        }
                    }
                    return StepResult.Ok(
                        next = next,
                        result = listOf()
                    )
                }

                fun MutableMap<CmdPointer, SigHashState>.mergeNext(nxt: CmdPointer, what: SigHashState, f: (SigHashState, SigHashState) -> SigHashState) : NBId? {
                    return if(nxt !in this) {
                        this[nxt] = what
                        nxt.block
                    } else {
                        val curr = this[nxt]!!
                        val joined = f(curr, what)
                        this[nxt] = joined
                        if(joined != curr) {
                            nxt.block
                        } else {
                            null
                        }
                    }
                }

                fun <T> MutableSet<T>.add(x: T?) {
                    if(x != null) { this.add(x) }
                }

                override fun reduce(results: List<Unit>) { }

            }.submit(g.rootBlocks.map { it.id })

            val resolution = MutableSigResolutionResult(
                sigHash = mutableMapOf(),
                callee = mutableMapOf(),
                input = mutableMapOf(),
                bufferSizes = mutableMapOf(),
                callCoreNumbering = mutableMapOf(),
                constructorCalls = mutableMapOf(),
                decomposedCallInputWithSourceWrites = mutableMapOf(),
                returnResolution = mutableMapOf(),
                storageReadNumering = mutableMapOf(),
                symbolicSighash = mutableMapOf(),
                logEncodes = mutableMapOf()
            )

            /* Return optimizations */
            val returnValueReads = g.commands.parallelStream().filter {
                it.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && it.cmd.base == TACKeyword.MEMORY.toVar() && it.cmd.loc is TACSymbol.Var && it.ptr in state
            }.map {
                it.narrow<TACCmd.Simple.AssigningCmd.ByteLoad>()
            }.mapNotNull { load ->
                val loc = load.cmd.loc as TACSymbol.Var
                val st = state[load.ptr]!!.nodes
                val set = pta.fieldNodesAt(load.ptr, loc) ?: return@mapNotNull null

                set.nodes.monadicMap {
                    st.readValue(it).returnVal
                }?.uniqueOrNull()?.let {
                    load.ptr to it
                }
            }.collect(Collectors.toMap({ it: Pair<CmdPointer, ReturnPointer> ->
                it.first
            }, { it.second }))

            g.commands.filter {
                it.cmd is TACCmd.Simple.ReturnCmd && it.cmd.memBaseMap == TACKeyword.MEMORY.toVar()
            }.forEach outerLoop@{
                handleReturnCommand(
                    it.narrow(), state[it.ptr] ?: return@outerLoop, resolution.resolver(), resolution.returnResolution
                )
            }

            g.commands.filter {
                it.cmd is TACCmd.Simple.CallCore && it.cmd.callType != TACCallType.CREATE
            }.forEach { callIt ->
                with(resolution) {
                    handleCallCore(
                        scene = scene,
                        stateReader = state::get,
                        callIt = callIt.narrow(),
                    )
                }
            }

            g.commands.mapNotNull {
                it.maybeNarrow<TACCmd.Simple.CallCore>()
            }.filter {
                it.cmd.callType == TACCallType.CREATE && it.cmd.inBase == TACKeyword.MEMORY.toVar() &&
                    it.cmd.to is TACSymbol.Var
            }.forEach { callCore ->
                with(resolution) {
                    handleCreate(
                        callCore,
                        stateReader = state::get
                    )
                }
            }

            for(log in g.commands.mapNotNull {
                it.maybeNarrow<TACCmd.Simple.LogCmd>()
            }) {
                val st = state[log.ptr] ?: continue
                /**
                 * Find the (unique) node that represents the input buffer to the log
                 */
                val fieldNodes = pta.fieldNodesAt(log.ptr, log.cmd.sourceOffset) ?: continue
                if(fieldNodes !is IndexedWritableSet || fieldNodes.offsetUpdate() != WritablePointsToSet.UpdateType.STRONG || fieldNodes.index != Constant(BigInteger.ZERO)) {
                    continue
                }
                /**
                 * Now see if that PTA node was the output of an encode. If so, this encode was used to serialize the data
                 * to the log command.
                 */
                val baseObject = fieldNodes.nodes.singleOrNull() ?: continue
                st.abiState.encoded[baseObject]?.let {
                    it as? EncodingPayload.FullEncode
                }?.let {
                    resolution.logEncodes[log.ptr] = it.encodeId
                }
            }

            return resolution.build(returnValueReads)
        }

        fun MutableSigResolutionResult.resolver() = AddressResolver(this)

        context(MutableSigResolutionResult)
        private fun handleCreate(
            callIt: LTACCmdView<TACCmd.Simple.CallCore>,
            stateReader: (CmdPointer) -> SigHashState?
        ) {
            val inSize = intervalApproxAt(callIt.cmd.inSize, callIt.ptr) ?: return
            val res = stateReader(callIt.ptr) ?: return
            /*
             * "project" out the view of memory as accessed by this create call
             */
            val sig = if(callIt.cmd.inOffset is TACSymbol.Var) {
                val inPtr = pta.fieldNodesAt(callIt.ptr, callIt.cmd.inOffset as TACSymbol.Var) as? IndexedWritableSet ?: return
                if(inPtr.elementSize != BigInteger.ONE) {
                    return
                }
                extractConstructorSignature(inPtr, res, inSize) ?: return
            } else {
                val sz = constantValueAt(callIt.cmd.inSize, callIt.ptr)?.toIntOrNull() ?: return
                val offset = (callIt.cmd.inOffset as TACSymbol.Const).value.toIntOrNull() ?: return
                val word = EVM_WORD_SIZE.intValueExact()
                if(sz + offset > word * 2) {
                    return
                }
                val asPair = if(offset < word) {
                    extractConstructorSignatureFromConst(
                        start = offset,
                        len = sz,
                        vecRange = intervalApproxAt(TACKeyword.MEM0.toVar(), callIt.ptr) ?: return,
                        nextValue = { ->
                            intervalApproxAt(TACKeyword.MEM32.toVar(), callIt.ptr)
                        }
                    ) ?: return
                } else {
                    extractConstructorSignatureFromConst(
                        start = offset - word,
                        len = sz,
                        vecRange = intervalApproxAt(TACKeyword.MEM32.toVar(), callIt.ptr) ?: return,
                        nextValue = { -> null }
                    ) ?: return
                }
                asPair.let(::mapOf)
            }
            /*
              Find the create() BIF call that defines the callee of this create command
             */
            val gen = NonTrivialDefAnalysis(graph = g).nontrivialDefSingleOrNull(
                callIt.cmd.to as TACSymbol.Var,
                callIt.ptr
            )?.let(g::elab)?.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf {
                it.cmd.meta.find(TACMeta.CONTRACT_CREATION) != null
            } ?: return
            val genId = gen.cmd.meta.find(TACMeta.CONTRACT_CREATION)!!
            /*
              Then we know that this call invokes a constructor that must match bytecode signature (aka
              known constant subsequences of the constructor code passed to the create command), and
              this constructor is invoked on an address generated by an invocation of the CREATE bif.
             */
            constructorCalls[callIt.ptr] = ConstructorCalls.ConstructorResolution(
                sig = sig,
                createId = genId
            )
            bufferSizes[callIt.ptr] = InOutBuffers(
                inputSize = inSize,
                outputSize = Constant(BigInteger.ZERO) // by definition of the EVM
            )
        }

        /**
         * Given the [vc.data.TACCmd.Simple.CallCore] at [callIt] (which is expected to *not* be a [TACCallType.CREATE])
         * record as much information about the call as possible.
         *
         * This includes
         * 1. signature resolution, but updating the [MutableSigResolutionResult.sigHash] map in the context parameter
         * 2. input resolution, including abi argument encoding etc by updating the [MutableSigResolutionResult.input] map in the context parameter
         * 3. buffer size information, by updating the [MutableSigResolutionResult.bufferSizes] map in the context parameter, and
         * 4. the call target, by updating the [MutableSigResolutionResult.callee] map in the context parameter
         * 5. symbolic sighash information by updating the [MutableSigResolutionResult.symbolicSighash] map in the context parameter
         *
         * If precise sighash resolution is impossible, this will attempt source based resolution if [scene] is non-null
         * and the source heuristic is enabled for the current method.
         *
         * [stateReader] is for retrieving the state both at [callIt], but chasing nested encodes as necessary.
         */
        context(MutableSigResolutionResult)
        private fun handleCallCore(
            scene: IScene?,
            callIt: LTACCmdView<TACCmd.Simple.CallCore>,
            stateReader: (CmdPointer) -> SigHashState?
        ) {
            /**
             * really a sanity check, if this is ever not true, something
             * *really* strange is happening.
             */
            if(callIt.cmd.inBase != TACKeyword.MEMORY.toVar()) {
                return
            }
            val st = stateReader(callIt.ptr) ?: return
            /**
             * resolve the [vc.data.TACCmd.Simple.CallCore.to] field with [handleTargetAddress] (which itself leans
             * on [AddressResolver.symbolicToResolved]).
             */
            handleTargetAddress(callIt, st, resolver(), callee)
            /**
             * Resolve the call input/output sizes, and keep the insize abstraction around, we'll need it later
             */
            val inSize = handleCallSizes(
                callIt, bufferSizes
            )

            /**
             * Get the PTA node associated with the call input
             */
            val pts = pta.fieldNodesAt(callIt.ptr, callIt.cmd.inOffset)?.let { it as? IndexedWritableSet }
            if (inSize == null || pta.query(IsProbablyNonZero(callIt.ptr, callIt.cmd.inSize)) != true) {
                // if this is a symbolic calldata, try recording that at the very least...
                pts?.indexed?.monadicMap {
                    st.nodes.projectFrom(it, null).leftOrNull()?.findEntry { k, _ ->
                        k.start == BigInteger.ZERO && k.end >= 3.toBigInteger()
                    }?.second?.symbolicValueSource
                }?.uniqueOrNull()?.let {
                    symbolicSighash[callIt.ptr] = it
                }
                return
            }
            logger.debug { "Checking sighash in ${callIt.ptr}" }
            if (!inSize.isDefinitelyGreaterThan(3.toBigInteger())) {
                logger.warn {
                    "Could not prove that the input buffer had enough space for a sighash. Optimistically continuing"
                }
            }
            /**
             * At this point, if the pts is null, we should bail out. Why not bail out at the read of pts above?
             * Apparently it's important to only bail after we issue some warnings.
             *
             * So, there you go.
             */
            if(pts == null) {
                return
            }

            /**
             * Project out a view of the contents of all nodes in pts, starting from the
             * start of the call buffer.
             */
            val projectedHeaps = projectHeaps(inSize, pts, st)

            /**
             * For each such heap, extract the sighash if we can find it. This does not need to yield
             * a single such sighash.
             */
            val fourBytes = projectedHeaps?.takeIf {
                it.isNotEmpty()
            }?.monadicMap {
                check4byteInHeap(it)
            }?.monadicMap {
                when (val x = it.value.consts) {
                    is ConstSet.Nondet -> null
                    is ConstSet.C -> x.ks
                }
            }?.flatten()?.map { normalizeTo4byte(it) }?.toSet()

            /**
             * Next, find a symbolic sighash in every heap. Note that for the moment this is
             * very strict: all heaps must have the same symbolic source or else no such symbolic sighash
             * is recorded. This is temporary until we think harder about what partially symbolic sighashes means.
             */
            projectedHeaps?.monadicMap {
                it.findEntry { r, _ ->
                    r.start == BigInteger.ZERO && r.end >= 3.toBigInteger()
                }?.second?.symbolicValueSource
            }?.uniqueOrNull()?.let {
                symbolicSighash[callIt.ptr] = it
            }

            /**
             * If we could resolve the sighash exactly, we use this opportunity to resolve a lot of extra information
             * in addition to the sighash, this extra inference is handled in [handlePreciseResolution]
             */
            if(fourBytes != null) {
                sigHash[callIt.ptr] = fourBytes
                handlePreciseResolution(
                    inSize = inSize,
                    stateGenerator = stateReader,
                    st = st,
                    callIt = callIt,
                    inputNodes = pts,
                    projectedHeaps = projectedHeaps
                )
            } else if(scene != null && sourceHeuristicEnabled(m)) {
                logger.info { "Got null four bytes out of $pts" }
                /* If the callgraph builder could not find the 4byte sighash, we take the call command and
                 * attempt to resolve it from the source attached to it. See [sourceHeuristic]
                 **/
                sourceHeuristic(
                    callIt.cmd,
                    callIt.ptr,
                    scene,
                    m,
                    if (inSize.isConstant) {
                        inSize.c
                    } else {
                        null
                    }
                ).takeIf { it.first.size == 1 }?.let { (sighashes, maybeContract) ->
                    sigHash[callIt.ptr] = sighashes
                    if (maybeContract != null) {
                        check(callIt.ptr !in callee){"When building the call target resolution set, the CmdPointer ${callIt.ptr} has been visited multiple times" +
                            " - expecting to only visit it once, the resolution set currently is ${callee[callIt.ptr]}"}
                        callee[callIt.ptr] = setOf(maybeContract)
                    }
                }
            }
        }

        /**
         * For a [hInt] at the [byteRange] in the input buffer to the call at [callIt] whose buffer is bounded by [inSize],
         * try to infer a scalar value to use as the value for that location in calldata. This is relevant to quickly
         * assign to scalarized calldata in the callee: if we can infer such a scalar here then we can directly assign that
         * scalar to the `tacCalldatabuf!4` or whatever in the callee.
         *
         * There are three cases to consider:
         * 1. The scalar value can't be inferred for this call: this happens especially if the value does not fall "neatly"
         * within the input buffer, or we don't know where the value was written.
         * 2. The value is a constant i.e, the [HeapInt.consts] field of [hInt] is a singleton. In this case, we just return
         * the [DecomposedCallInputArg.Constant]. Constants are easy, we always know their value.
         * 3. If the value was a variable written at some point in the progam, we have a problem: different variables can
         * be written to the same buffer location, and so there is no "principle" scalar variable to use. In addition, it's hard
         * to guarantee the value of the variable `v` written into the buffer still holds that value by the time we reach
         * the call.
         *
         * So, to work around that, we actually introduce a fresh variable (via [DecomposedArgVariableWithSourceWrites.decompVarName])
         * and claim that as the scalar variable. We also record all of the symbols written to the buffer
         * at [byteRange] along the location of that write. This set of [LTACSymbol]'s should be assigned
         * to that variable via instrumentation. This "instrumentation obligation" is recorded in the context receiver's
         * [MutableSigResolutionResult.decomposedCallInputWithSourceWrites] with a [DecomposedArgVariableWithSourceWrites]
         * object.
         */
        context(MutableSigResolutionResult)
        private fun handleArgumentDecomposition(
            callIt: LTACCmdView<TACCmd.Simple.CallCore>,
            inSize: IntValue,
            byteRange: ByteRange,
            hInt: HeapInt,
            stateGenerator: (CmdPointer) -> SigHashState?
        ) : DecomposedCallInputArg? {
            val scratchByteRange = normalizeTo4byte(byteRange)
            if (inSize.isConstant && inSize.c <= scratchByteRange.from) {
                // if size is known, we don't want to read beyond what's allocated in the range (the garbage collection problem)
                return null
            }
            // the (definite) end of the call buffer cuts in the middle of the value here. Do some sanity checks,
            // otherwise, throw an exception
            if (inSize.isConstant && inSize.c > scratchByteRange.from && inSize.c <= scratchByteRange.to) {
                /**
                 * First case, the value is not aligned with the sighash offset, and it is the constant zero. this is a
                 * likely zeroing out of upper bytes, so allow it for now.
                 */
                return if(hInt.consts is ConstSet.C && hInt.consts.isConstant && hInt.consts.c == BigInteger.ZERO &&
                    scratchByteRange.from.mod(EVM_WORD_SIZE) != 4.toBigInteger()) {
                    null
                /**
                 * Write was beyond the size, but the meaningful bits fit.  See [leftJustifiedEnd] for details of why
                 * this can happen.
                 */
                } else if (leftJustifiedEnd(scratchByteRange, hInt) < inSize.c) {
                    // Write was beyond the size, but the meaningful bits fit.  See [leftJustifiedEnd]
                    null
                } else {
                    // otherwise something suspicious is happening, tank the analysis.
                    throw CallGraphBuilderFailedException("We got a byte range $scratchByteRange but input size is ${inSize.c}, which cuts it in the middle @ $callIt $hInt", callIt.wrapped)
                }
            }

            val constVal = when (hInt.consts) {
                is ConstSet.Nondet -> null
                is ConstSet.C -> hInt.consts.ks.singleOrNull()
            }

            /**
             * If we have a constant value, we're set! we can just return the constant value immediately.
             */
            if(constVal != null) {
                return DecomposedCallInputArg.Constant(
                    scratchByteRange,
                    CalledContract.FullyResolved.ConstantAddress(constVal),
                    TACSymbol.Const(
                        when (scratchByteRange.from) {
                            //This is a patch to circumvent the 224 bit right shift done in TAC (as part of the dispatcher sig hash check) to extract
                            //the sig hash - we perform here the inverse, namely left shift 224 bit, if needed
                            BigInteger.ZERO -> normalizeTo4byteReverse(
                                constVal
                            )

                            else -> constVal
                        }
                    )
                )
            }

            /**
             * If we don't have a definite command pointer set (i.e., locations where we wrote the value for this byterange)
             * we can't do the instrumentation as we don't know where we wrote the value into the buffer.
             */
            if(hInt.writeCmdPtrSet !is CmdPointerSet.CSet) {
                check(hInt.writeCmdPtrSet is CmdPointerSet.Nondet) {
                    "Broken type hierarchy"
                }
                logger.warn {
                    "Failed to resolve a ByteStore cmd of the input argument at" +
                        "scratch byte range $scratchByteRange, with value ${constVal ?: hInt}"
                }
                return null
            }

            /**
             * [CmdPointerSet] just records a set of command pointers, so extract the value we wrote by interpreting those
             * as [vc.data.TACCmd.Simple.AssigningCmd.ByteStore] commands and taking the [vc.data.TACCmd.Simple.AssigningCmd.ByteStore.value]
             * as the symbol value to capture.
             */
            val writtenSymbols = hInt.writeCmdPtrSet.cs.map { (byteStorePtr, _) ->
                byteStorePtr
            }.toSet()

            /**
             * Also, did we always write the same address? If so, we can record this information too.
             */
            val passedAddress = writtenSymbols.monadicMap { bSym ->
                when(bSym) {
                    is CmdPointerSet.CSet.BufferSymbol.Global -> {
                        val offs = bSym.v.meta[TACMeta.CALLDATA_OFFSET] ?: return@monadicMap null
                        CalledContract.SymbolicInput(
                            offset = offs,
                            inputArg = bSym.v
                        )
                    }

                    is CmdPointerSet.CSet.BufferSymbol.WrittenAt -> {
                        when (bSym.what) {
                            is TACSymbol.Const -> CalledContract.FullyResolved.ConstantAddress(
                                bSym.what.value
                            )

                            is TACSymbol.Var -> stateGenerator(bSym.where)?.storageSlots?.get(
                                bSym.what
                            )?.let {
                                it as? StorageSet.Set
                            }?.slots?.monadicMap {
                                it
                            }?.map {
                                resolver().symbolicToResolved(
                                    it
                                )
                            }?.uniqueOrNull()
                        }
                    }
                }
            }?.uniqueOrNull()

            val writtenLTACSyms = writtenSymbols.monadicMap {
                (it as? CmdPointerSet.CSet.BufferSymbol.WrittenAt)?.toLSym()
            }?.toSet() ?: return null

            val decomposedName = DecomposedArgVariableWithSourceWrites.decompVarName(
                Tag.Bit256, scratchByteRange.from, getCallNumbering(callIt.ptr)
            )

            /**
             * Record our instrumentation obligation: notice the addition, multiple decompositions at the same call
             * might need instrumentation
             */
            decomposedCallInputWithSourceWrites[callIt.ptr] = decomposedCallInputWithSourceWrites.getOrDefault(callIt.ptr, treapSetOf()) + DecomposedArgVariableWithSourceWrites(
                writtenLTACSyms, decomposedName
            )

            return DecomposedCallInputArg.Variable(
                scratchByteRange,
                passedAddress,
                decomposedName
            )
        }

        /**
         * Infer extra argument information based on the precise resolution result for the call at [callIt].
         * [inSize] is the size of the buffer being passed to [callIt], and [inputNodes] is the [IndexedWritableSet]
         * that represents the abstract arrays being used as input to this call. [projectedHeaps] is the projection
         * of the arrays in [inputNodes] as computed by [projectedHeaps]. The call takes place in the state [st], but
         * states at other points in the program can be accessed with [stateGenerator].
         *
         * Primarily, this function infer the [CallInput] for the call, which involves computing the decomposed scalar arguments,
         * and extract the ABI argument encoding information (if available). This function mutates
         * the [MutableSigResolutionResult.input] and [MutableSigResolutionResult.decomposedCallInputWithSourceWrites] maps
         * directly, and may update other maps via the [AddressResolver] as well.
         */
        context(MutableSigResolutionResult)
        private fun handlePreciseResolution(
            inSize: IntValue,
            callIt: LTACCmdView<TACCmd.Simple.CallCore>,
            inputNodes: IndexedWritableSet,
            projectedHeaps: List<Map<ByteRange, HeapInt>>,
            st: SigHashState,
            stateGenerator: (CmdPointer) -> SigHashState?
        ) {
            /*
             NOTE: [monadicMap] ensures that [heaps] has no null elements.
             */
            val scratchModel =
                if (projectedHeaps.count() > 1) {
                    val inputModel = projectedHeaps.first().toMutableMap()
                    val currKeys = inputModel.keys.toMutableSet()
                    for (i in 1..projectedHeaps.lastIndex) {
                        val h = projectedHeaps[i]
                        for ((k, v) in h) {
                            if (k !in currKeys) {
                                continue
                            }
                            inputModel[k] = v.join(inputModel[k]!!)
                        }
                        for (k in currKeys) {
                            if (k !in h) {
                                inputModel.remove(k)
                                currKeys.remove(k)
                            }
                        }
                    }
                    inputModel
                } else {
                    projectedHeaps.first()
                }
            val decomposedArgs = scratchModel.mapNotNull { (byteRange, hInt) ->
                handleArgumentDecomposition(
                    callIt, inSize, byteRange, hInt, stateGenerator
                )
            }
            val argumentEncoding = inputNodes.indexed.singleOrNull()?.let { iNode ->
                /*
                  Determine the values that were encoded into this buffer. Get the abstract value,
                  and if it is a copy from, chase the pointer.

                  XXX(jtoman): this seems really stupid? What if we have a copy of a copy of a...?

                  Why don't copies just copy the abstract state directly
                 */
                val (node, where) = st.abiState.encoded[iNode.node]?.let {
                    if(it is EncodingPayload.CopyFrom) {
                        it.node to it.where
                    } else {
                        iNode.node to callIt.ptr
                    }
                } ?: return@let null
                val encodePointState = stateGenerator(where) ?: return@let null
                val encodingState = encodePointState.abiState.encoded[node]?.let {
                    it as? EncodingPayload.FullEncode
                }?: return@let null // give up if we don't have the full encode, the instrumentation use case isn't supported yet
                extractABIEncoding(encodingState, stateGenerator)
            }
            input[callIt.ptr] = CallInput(
                callIt.cmd.inBase.asSym(),
                callIt.cmd.inOffset.asSym(),
                simplifier.simplify(callIt.cmd.inOffset, callIt.ptr, true).getAs(),
                callIt.cmd.inSize.asSym(),
                if (inSize.isConstant) {
                    inSize.c
                } else {
                    inSize.lb //take lower bound on the input size
                },
                decomposedArgs.map { it.scratchRange to it }.toMap(),
                argumentEncoding
            )
        }

        /**
         * For all of the nodes in [inputNodes], get a "projection" (in the style of [NodeState.projectFrom])
         * of the contents of those nodes, from the start index of [inputNodes].
         * This "start index" is taken to be the unique constant value of all [analysis.pta.IndexedWritableSet.IndexedNode.index]
         * in [inputNodes]. If there is no such constant, this function returns 0.
         *
         * Otherwise, a list of maps is returned, where each map is a projection of the contents of one of the node in
         * [inputNodes], bounded above by the upper bound of [inSize].
         *
         * XXX(jtoman): why isn't this using [NodeState.projectFrom]?
         */
        private fun projectHeaps(
            inSize: IntValue,
            inputNodes: IndexedWritableSet,
            st: SigHashState
        ) : List<Map<ByteRange, HeapInt>>? {
            val offs = inputNodes.indexed.monadicMap { it.index.takeIf { it.isConstant }?.c }?.uniqueOrNull() ?: return null

            return inputNodes.indexed.monadicMap {
                st.nodes.byteAddressed[it.node]
            }?.map {
                it.entries.filter { (k, _) ->
                    k.end >= offs
                }.associate { (k,v) ->
                    val newStart = k.start - offs
                    val newEnd = k.end - offs
                    if(newStart < BigInteger.ZERO && newEnd > BigInteger.ZERO) {
                        // We're probably doing a shifted least significant bytes load
                        // Truncate any concrete values to fit in the remaining bytes
                        val lsbNumBits = (newEnd.toInt() + 1) * 8
                        val lsbMask = BigInteger.TWO.pow(lsbNumBits) - BigInteger.ONE
                        val newV = when(val x = v.consts) {
                            is ConstSet.Nondet -> v
                            is ConstSet.C ->
                                v.copy(consts = ConstSet.C(x.ks.map { c -> c.and(lsbMask) }.toSet()))
                        }
                        k.copy(start = BigInteger.ZERO, end = newEnd) to newV
                    } else {
                        k.copy(start = newStart, end = newEnd) to v
                    }
                }.filterKeys {
                    it.start >= BigInteger.ZERO && it.start < inSize.ub
                }
            }
        }

        /**
         * A mutable versionof [SigResolutionAnalysisResult.Full], the field names (and their interpretations)
         * are exactly the same as in that case, but we've made them mutable here.
         *
         * The exception is [SigResolutionAnalysisResult.Full.returnResolution], which is not generated via this class
         */
        private class MutableSigResolutionResult(
            val sigHash: MutableMap<CmdPointer, Set<BigInteger>>,
            val input: MutableMap<CmdPointer, CallInput>,
            val decomposedCallInputWithSourceWrites: MutableMap<CmdPointer, Set<DecomposedArgVariableWithSourceWrites>>,
            val callee: MutableMap<CmdPointer, Set<CalledContract>>,
            val returnResolution: MutableMap<CmdPointer, ReturnInformation>,

            val bufferSizes: MutableMap<CmdPointer, InOutBuffers>,
            val callCoreNumbering: MutableMap<CmdPointer, Int>,

            val storageReadNumering: MutableMap<CmdPointer, Int>,

            val constructorCalls: MutableMap<CmdPointer, ConstructorCalls.ConstructorResolution>,

            val symbolicSighash: MutableMap<CmdPointer, DecoderAnalysis.BufferAccessPath>,

            val logEncodes: MutableMap<CmdPointer, Int>
        ) {
            fun build(returnValueReads: Map<CmdPointer, ReturnPointer>) = SigResolutionAnalysisResult.Full(
                sigHash, input, decomposedCallInputWithSourceWrites, callee, returnResolution, returnValueReads, bufferSizes, callCoreNumbering, storageReadNumering, constructorCalls, symbolicSighash, logEncodes
            )

            fun getStorageNumbering(c: CmdPointer) = storageReadNumering.computeIfAbsent(c) {
                Allocator.getFreshId(Allocator.Id.STORAGE_READ)
            }

            fun getCallNumbering(c: CmdPointer) = callCoreNumbering.computeIfAbsent(c) {
                Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES)
            }
        }

        /**
         * Helper class which translates a [SymbolicAddress] into the [CalledContract]. This enacapsulates
         * both the logic to do this (Via the [symbolicToResolved] function, and the state (via the [resolution]
         * field and the implicit capture of the surrounding [Worker] object).
         *
         * [resolution] is needed to add numberings for external calls and storage reads (via [MutableSigResolutionResult.getCallNumbering]
         * and [MutableSigResolutionResult.getStorageNumbering] respectively), both of which are sources of callees.
         */
        private inner class AddressResolver(
            val resolution: MutableSigResolutionResult
        ) {
            private val g = (m.code as CoreTACProgram).analysisCache.graph

            /**
             * Take a [SymbolicAddress] [symbolic], which is an analysis internal representation of a callee address, and translate
             * it into the externally consumable [CalledContract] if possible. If the solution failed, return null.
             *
             * For example, this will translate [SymbolicAddress.THIS] (a symbolic fact) into
             * [analysis.icfg.CallGraphBuilder.CalledContract.FullyResolved.SelfLink] (which is a concrete representation of that
             * fact).
             *
             * Despite the naame, the [CalledContract] instance returned from this function doesn't have to be a subclass of
             * [CalledContract.FullyResolved], i.e., it can still be symbolic as is the case for [CalledContract.UnresolvedRead].
             */
            fun symbolicToResolved(
                symbolic: SymbolicAddress,
            ): CalledContract {
                val hostAddress = m.getContainingContract().instanceId
                return when (symbolic) {
                    SymbolicAddress.THIS -> CalledContract.FullyResolved.SelfLink(hostAddress)
                    is SymbolicAddress.ConstantSlot -> {
                        val address = (m.getContainingContract() as? IContractWithSource)?.src?.let { sdc ->
                            sdc.state[symbolic.number]
                        }
                        val id = resolution.getStorageNumbering(symbolic.readLocation)
                        if (address != null) {
                            CalledContract.FullyResolved.StorageLink(address, id)
                        } else {
                            CalledContract.UnresolvedRead(id)
                        }
                    }
                    is SymbolicAddress.CallDataInput -> CalledContract.SymbolicInput(
                        offset = symbolic.offset,
                        inputArg = symbolic.inputArg
                    )
                    is SymbolicAddress.ReturnData -> resolution.getCallNumbering(symbolic.callLocation).let { which ->
                        CalledContract.SymbolicOutput(
                            which = which,
                            offset = symbolic.offset
                        )
                    }
                    is SymbolicAddress.UnresolvedRead -> {
                        g.elab(symbolic.readLocation).maybeNarrow<TACCmd.Simple.AssigningCmd.WordLoad>()?.takeIf {
                            it.cmd.loc is TACSymbol.Var
                        } ?: return CalledContract.Unresolved
                        val id = resolution.getStorageNumbering(symbolic.readLocation)
                        CalledContract.UnresolvedRead(id)
                    }
                    is SymbolicAddress.CreatedContract -> {
                        CalledContract.CreatedReference.Unresolved(g.elab(symbolic.where).cmd.meta.find(TACMeta.CONTRACT_CREATION) ?: return CalledContract.Unresolved)
                    }
                    is SymbolicAddress.ImmutableReference -> CalledContract.FullyResolved.ImmutableReference(symbolic.address)
                    is SymbolicAddress.LibraryAddress -> CalledContract.FullyResolved.ConstantAddress(symbolic.contractId)
                    is SymbolicAddress.InternalSummaryOutput -> CalledContract.InternalFunctionSummaryOutput(which = symbolic.which, ordinal = symbolic.offset)
                }
            }
        }


        /**
         * Given the [vc.data.TACCmd.Simple.ReturnCmd] at [lc], infers the addresses returned by the contract
         * at [lc] based off of the values stored in [st] (the state at [lc]). The raw symbolic addreses in the buffer
         * are resolved via [resolver], which is comes from the [MutableSigResolutionResult.resolver]. The return information
         * is placed into [returnResolution] at [lc]; the value of this argument is expected to come from
         * [MutableSigResolutionResult.returnResolution].
         */
        private fun handleReturnCommand(
            lc: LTACCmdView<TACCmd.Simple.ReturnCmd>,
            st: SigHashState,
            resolver: AddressResolver,
            returnResolution: MutableMap<CmdPointer, ReturnInformation>
        ) {
            /*
               The return buffer is AT LEAST this size
             */
            val returnLowerBound = lowerBoundAt(lc.cmd.o2, lc.ptr)
            if(returnLowerBound.mod(0x20.toBigInteger()) != BigInteger.ZERO) {
                return
            }

            val pts = pta.fieldNodesAt(lc.ptr, lc.cmd.o1)?.let { it as? IndexedWritableSet } ?: return
            val resolution = mutableMapOf<BigInteger, CmdPointerSet>()
            val returnedContracts = mutableMapOf<BigInteger, StorageSet>()
            pts.indexed.forEach { iNode ->
                if(!iNode.index.isConstant) {
                    return
                }
                val returnStart = iNode.index.c
                var idx = returnStart
                val end = returnStart + returnLowerBound
                val baseMap = st.nodes.byteAddressed[iNode.node] ?: return
                while(idx < end) {
                    val returnValueRange = ByteRange(idx, idx + 31.toBigInteger())
                    val returnedValue = baseMap[returnValueRange] ?: return
                    resolution.merge(idx - returnStart, returnedValue.writeCmdPtrSet) { a, b ->
                        a.join(b)
                    }
                    returnedContracts.merge(idx - returnStart, returnedValue.storageSet) { a, b ->
                        a.join(b)
                    }
                    idx += 0x20.toBigInteger()
                }
            }
            val result = resolution.entries.monadicMap { (k, v) ->
                if(v !is CmdPointerSet.CSet) {
                    return@monadicMap null
                }
                v.cs.monadicMap { (ptr, range) ->
                    (ptr as? CmdPointerSet.CSet.BufferSymbol.WrittenAt)?.takeIf {
                        range == CmdPointerSet.fullWord
                    }
                }?.let {
                    it.map {
                        LTACSymbol(it.where, it.what)
                    }
                }?.let {
                    k to it.toSet()
                }
            }?.toMap()
            val x = returnedContracts.entries.mapNotNull { (k, v) ->
                (v as? StorageSet.Set)?.slots?.singleOrNull()?.let {
                    resolver.symbolicToResolved(it)
                }?.let {
                    k to it
                }
            }.toMap()
            if(result != null) {
                returnResolution[lc.ptr] = ReturnInformation(
                    returnSizeLowerBound = returnLowerBound,
                    returnWrites = result,
                    addressReturn = x
                )
            }
        }

        /**
         * Computes an abstraction of the input/output sizes of the call command at [call], placing the
         * information (wrapped in an [InOutBuffers] into the [bufferSizes] map (which is expected to be
         * the reference held in [MutableSigResolutionResult].
         *
         * Returns the abstraction of the input size as an [IntValue], or null if no such abstraction was found.
         */
        private fun handleCallSizes(
            call: LTACCmdView<TACCmd.Simple.CallCore>,
            bufferSizes: MutableMap<CmdPointer, InOutBuffers>
        ) : IntValue? {
            val inSize = intervalApproxAt(call.cmd.inSize, call.ptr)
            val outputSize = intervalApproxAt(call.cmd.outSize, call.ptr)
            bufferSizes[call.ptr] = InOutBuffers(
                inputSize = inSize ?: Nondet,
                outputSize = outputSize ?: Nondet
            )
            return inSize
        }

        /**
         * computes the callee of the call core at [callIt], using the
         * [resolver] (retrieved via [MutableSigResolutionResult.resolver])
         * and the current state [st]. Primarily this is done via the [AddressResolver.symbolicToResolved]
         * function.
         *
         * The result is placed into [calleeResolution] at the location of the [callIt]. [calleeResolution] is
         * expected to be the pointer held in the [MutableSigResolutionResult.callee] field.
         */
        private fun handleTargetAddress(
            callIt: LTACCmdView<TACCmd.Simple.CallCore>,
            st: SigHashState,
            resolver: AddressResolver,
            calleeResolution: MutableMap<CmdPointer, Set<CalledContract>>
        ) {
            val callCore = callIt.cmd
            val address = when (callCore.to) {
                is TACSymbol.Const -> {
                    setOf(CalledContract.FullyResolved.ConstantAddress(callCore.to.value))
                }

                is TACSymbol.Var -> {
                    pta.query(ConstantValue(callIt.ptr, callCore.to))?.let {
                        setOf(CalledContract.FullyResolved.ConstantAddress(
                            contractId = it
                        ))
                    } ?: run {
                        st.storageSlots[callCore.to]?.let {
                            it as? StorageSet.Set
                        }?.slots?.map {
                            resolver.symbolicToResolved(it)
                        }?.toSet() ?: setOf(CalledContract.Unresolved)
                    }
                }
            }
            check(callIt.ptr !in calleeResolution){"When building the call target resolution set, the CmdPointer ${callIt.ptr} has been visited multiple times" +
                " - expecting to only visit it once, the resolution set currently is ${calleeResolution[callIt.ptr]}"}
            calleeResolution[callIt.ptr] = address
        }

        /**
         * Given an [encodingState] representing a [analysis.icfg.CallGraphBuilder.EncodingPayload.FullEncode]
         * (which precise information), extract the [ABIArgumentEncoding] representation of this data.
         *
         * In particular, from [analysis.icfg.CallGraphBuilder.EncodingPayload.FullEncode], we extract the [ABIValue] representation
         * of the [analysis.icfg.CallGraphBuilder.EncodingPayload.FullEncode.model]. This includes "chasing" nested
         * serializations. We may discover that one of the points to nodes encoded at some point X itself holds an encoding at
         * point Y, and so on. (A good question is whether this terminates in the presence of recursive encoding. It might not!)
         *
         * This nested encoding is represented by the [ABIValue.Symbol.childEncodings] field, see that class' documentation for
         * details. That representation is a linearization of following the pointers in the points to graph
         * and ABI state.
         *
         * As usual, if something goes wrong, we return null.
         */
        private fun extractABIEncoding(
            encodingState: EncodingPayload.FullEncode,
            stateGenerator: (CmdPointer) -> SigHashState?
        ) : ABIArgumentEncoding? {
            val stateAtEncode = stateGenerator(encodingState.where) ?: return null
            val encodedArgs = encodingState.model.entries.monadicMap { (idx, m) ->
                idx `to?` when(m) {
                    is EncodedValue.Const -> ABIValue.Constant(k = m.c)
                    is EncodedValue.Symbol -> ABIValue.Symbol(
                        type = m.ty,
                        sym = m.paths.mapNotNull {
                            (it as? ObjectPathGen.Root)?.oRoot
                        }.takeIf { it.isNotEmpty() }?.first() ?: return@monadicMap null,
                        /*
                          Traverse the abstract value associated with this encoded value, and see
                          if it itself has an encoded buffer (you can imagine encoding a struct
                          with an address and the raw calldata buffer...)

                          Represent this by an object path rooted at "This node" represented
                          by the singleton Unit
                         */
                        childEncodings = mutableMapOf<ObjectPathGen<UnitPath>, EncodedElem>().let {
                            findNestedEncodes(
                                where = encodingState.where,
                                nestedEncodes = it,
                                encodedType = m.ty,
                                encodedObjectNodes = m.aVal,
                                stateAtEncode = stateAtEncode,
                                stateGenerator = stateGenerator
                            )
                            it
                        },
                        partitionRelation = m.fieldPointers
                    )
                }
            }?.toMap() ?: return null
            return ABIArgumentEncoding(
                encodeId = encodingState.encodeId,
                encodedArgs = encodedArgs
            )
        }

        /**
            Sometimes the compiler emits a 32-byte write to get a smaller value into the end of the scratch buffer,
            after first shifting the smaller data left to fill 32 bytes. For example, an address might be written to the
            end of the scratch buffer as follows:

            ```
            r0 = address
            r1 = r0 & MASK160
            r2 = r1 << 96
            mem[loc] = r2
            locNext = loc + 20
            len = locNext - tacM0x40
            ```

            This is only writing 160 "meaningful" bits (20 bytes) to the buffer, but it looks to us like a
            256-bit/32-byte write, which makes it appear to go past the end of the computed buffer length.

            We work around this by detecting the left-shift, and returning the offset of the last *meaningful* byte
            written to the buffer.
         */
        private fun leftJustifiedEnd(range: ScratchByteRange, hInt: HeapInt): BigInteger {
            if (hInt.writeCmdPtrSet !is CmdPointerSet.CSet || hInt.writeCmdPtrSet.valueWidth() != EVM_WORD_SIZE) {
                return range.to

            }
            val leftJustifiedSize = hInt.writeCmdPtrSet.cs.map { (ptr, _) ->
                val v = (ptr as? CmdPointerSet.CSet.BufferSymbol.WrittenAt)?.what as? TACSymbol.Var ?: return@map EVM_WORD_SIZE
                val lcmd = g.elab(ptr.where)
                val bits = matchLeftShift.query(v, lcmd).toNullableResult() ?: return@map EVM_WORD_SIZE
                if (bits.mod(EVM_BYTE_SIZE) == BigInteger.ZERO) {
                    EVM_WORD_SIZE - (bits / EVM_BYTE_SIZE)
                } else {
                    EVM_WORD_SIZE
                }
            }.uniqueOrNull() ?: return range.to

            return range.from + leftJustifiedSize - BigInteger.ONE
        }

        private val matchLeftShift = PatternMatcher.compilePattern(
            graph = g,
            patt = PatternDSL.build {
                (Var shl Const).second
            }
        )

        private fun extractConstructorSignature(
            inPtr: IndexedWritableSet,
            res: SigHashState,
            inSize: IntValue
        ) = inPtr.indexed.monadicMap {
            if (!it.index.isConstant) {
                return@monadicMap null
            }
            it `to?` res.nodes.byteAddressed[it.node]
        }?.takeIf { it.isNotEmpty() }?.map { (node, contentMap) ->
            /*
              Collect all known constants, and their byterange (expressed relative to the beginning
              of the region used for this create call)
             */
            val normalized = mutableMapOf<ByteRange, BigInteger>()
            val start = node.index.c
            for ((range, aVal) in contentMap) {
                if (!aVal.consts.isConstant) {
                    continue
                }
                if (range.end < start) {
                    continue
                }
                val normStart = range.start - start
                val normEnd = range.end - start
                /*
                  Find constants that are no longer relevant, that is, *definitely* fall outside
                  the range of the buffer.

                  Q. Couldn't we truncate constants that straddle the beginning of the buffer like we do with the
                  end below?
                  A. Shut up (in principle yes, but this doesn't actually happen)
                 */
                if (normStart > inSize.ub || normStart < BigInteger.ZERO) {
                    continue
                }
                /*
                  If the insize is constant, and we have byterange range that "straddles" the end of the buffer,
                  that's fine, we can truncate the constant. If inSize is non-constant, we cannot reliably truncate, and
                  skip this constant
                 */
                if (normEnd >= inSize.lb && !inSize.isConstant) {
                    continue
                }
                val (truncEnd, truncVal) = if (normEnd >= inSize.lb) {
                    /*
                      inSize must be constant due to the check above. Truncate by the amount it "overhangs"
                     */
                    (inSize.c - BigInteger.ONE) to aVal.consts.c.shiftRight((normEnd - inSize.c + BigInteger.ONE).intValueExact() * 8)
                } else {
                    /*
                      All is well here, fits nicely within the bounds
                     */
                    normEnd to aVal.consts.c
                }
                normalized[ByteRange(normStart, truncEnd)] = truncVal
            }
            normalized
        }?.map { heap ->
            /*
             Find contiguous ranges of constants, and concatenate them together into bytecode "strings".
             */
            val entries = heap.entries.sortedBy {
                it.key.start
            }
            var prevRange = BigInteger.ONE.negate()
            val toRet = mutableMapOf<ScratchByteRange, BigInteger>()
            var constructorString = BigInteger.ZERO
            var start = -1
            for ((range, v) in entries) {
                if (range.start != prevRange) {
                    if (prevRange > BigInteger.ZERO) {
                        check(start >= 0)
                        toRet[ScratchByteRange(
                            start.toBigInteger(),
                            prevRange - BigInteger.ONE
                        )] = constructorString
                    }
                    start = range.start.intValueExact()
                    constructorString = BigInteger.ZERO
                }
                val bitLen = (range.end - (range.start - BigInteger.ONE)).intValueExact() * EVM_BYTE_SIZE.toInt()
                check(v.bitLength() <= bitLen)
                constructorString = constructorString.shiftLeft(bitLen).or(v)
                prevRange = range.end + BigInteger.ONE
            }
            if (prevRange > BigInteger.ZERO) {
                check(start >= 0)
                toRet[ScratchByteRange(
                    start.toBigInteger(),
                    prevRange - BigInteger.ONE
                )] = constructorString
            }
            toRet
        }?.uniqueOrNull()

        /**
         * This is the start of our recursive process to find nested encodes. For an encode of a value
         * with type [encodedType] whose abstract object (NB: NOT abstract fields) are represented by the set
         * of nodes [encodedObjectNodes] at [where] in a state [stateAtEncode], record nested encodes found by traversing the object graph
         * starting from [encodedObjectNodes] in [nestedEncodes].
         *
         * The traversal of [encodedObjectNodes] is "type" directed using [encodedType]. "The value" being encoded
         * is represented by the "UnitPath" in the object paths generated. Returns true if this function ran
         * without issue, false otherwise (NB: true does not mean we necessarily found any nested encodes).
         */
        private fun findNestedEncodes(
            where: CmdPointer,
            nestedEncodes: MutableMap<ObjectPathGen<UnitPath>, EncodedElem>,
            encodedType: HeapType,
            encodedObjectNodes: Set<PTANode>?,
            stateAtEncode: SigHashState,
            stateGenerator: (CmdPointer) -> SigHashState?
        ) : Boolean {
            if(encodedType == HeapType.Int) {
                return true
            }
            if(encodedObjectNodes == null) {
                return false
            }
            /*
              The "recursion" here expects to start with a points to set, but we only have
              a type, and a points to node. From there, we'll get more points to sets via the IPointsToInformation.

              Luckily, IPointsToSet is a pretty simple interface to fake.
             */
            findNestedEncodes(where, nestedEncodes, encodedType, ObjectPathGen.Root(UnitPath), object : IPointsToSet {
                override fun mayAlias(v: IPointsToSet): Boolean {
                    throw UnsupportedOperationException()
                }

                override fun mustAlias(v: IPointsToSet): Boolean {
                    throw UnsupportedOperationException()
                }

                override val nodes: Collection<PTANode>
                    get() = encodedObjectNodes
                override val type: IPointsToSet.Type
                    get() = encodedType.toPTType()

            }, stateAtEncode, stateGenerator)
            return true
        }

        /**
         * The actual recursive part of finding nested encodes. As in the other variant of [findNestedEncodes],
         * we are considering the encoding of some value at the point [where]. However, this function represents the
         * result of having traversed the object fields as described in [currPath]. Note that the "top-level" value being encoded
         * is considered to live at the UnitPath, which is a dummy value used as the root of the object path, which conveys
         * no extra information. To really belabor this, the root in these paths conveys no information about the identity of the
         * object.
         *
         * [typeAtPath] is the type of a value reached by traversing the "top-level" value according to [currPath]; put another way
         * when we enter this function, we are considering a value of type [typeAtPath] that is reached by traversing the
         * path [currPath]. The abstract representation of this object is the [abstractObjectAtPath] (which is not a [WritablePointsToSet],
         * so it is not an abstraction of field nodes, but objects!).
         *
         * This function broadly does one of three things.
         * If the value we've reached is a primitive or otherwise uninteresting value (an empty array) we simply return, there
         * is no encoding here.
         * For aggregate types structs and word arrays, we continue traversing the abstract object graph to child objects,
         * using the appropriate getters of the [IPointsToInformation] (e.g., [IPointsToInformation.reachableObjects] or [IPointsToInformation.reachableArrayElements]),
         * and extending the object path in the appropriate way.
         *
         * Otherwise, if we've hit a bytearray, we *may* have found an encoding. We check whether
         * the array element nodes associated with the array object in [abstractObjectAtPath] has
         * [analysis.icfg.CallGraphBuilder.EncodingPayload.FullEncode]
         * data in the state at the encoding [stateAtEncode] (as recorded in [ABIState.encoded]). If so, we actually
         * pop recurse into to [extractABIEncoding], extracting the encoding information, packaging it up with the
         * sighash recorded in the buffer (if any) via the [EncodedElem] class, and putting this information into [out]
         * at the [currPath].
         */
        private fun findNestedEncodes(
            where: CmdPointer,
            out: MutableMap<ObjectPathGen<UnitPath>, EncodedElem>,
            typeAtPath: HeapType,
            currPath: ObjectPathGen<UnitPath>,
            abstractObjectAtPath: IPointsToSet,
            stateAtEncode: SigHashState,
            stateGenerator: (CmdPointer) -> SigHashState?
        ) {
            when(typeAtPath) {
                is HeapType.Array -> {
                    findNestedEncodes(where, out, typeAtPath.elementType, ObjectPathGen.ArrayElem(
                        parent = currPath
                    ), pta.reachableArrayElements(where, abstractObjectAtPath) ?: return, stateAtEncode = stateAtEncode, stateGenerator)
                }
                HeapType.ByteArray -> {
                    pta.arrayFieldAt(where, abstractObjectAtPath)?.let {
                        val contents = it.nodes.singleOrNull() ?: return@let
                        val sighash = stateAtEncode.nodes.byteAddressed[contents]?.let(::check4byteInHeap)?.value?.takeIf {
                            it.consts.isConstant
                        }?.consts?.c?.let(::normalizeTo4byte)
                        val enc = stateAtEncode.abiState.encoded[contents]?.let { it as? EncodingPayload.FullEncode } ?: return@let
                        val argEncoding = extractABIEncoding(
                            enc, stateGenerator = stateGenerator
                        ) ?: return@let
                        out[currPath] = EncodedElem(sighash = sighash, argEncoding)
                    }
                }
                HeapType.EmptyArray,
                is HeapType.TVar,
                HeapType.Int -> return
                is HeapType.OffsetMap -> {
                    typeAtPath.fieldTypes.forEach { (idx, fTy) ->
                        findNestedEncodes(where, out, fTy, ObjectPathGen.Field(
                            offset = idx,
                            parent = currPath
                        ), abstractObjectAtPath = pta.reachableObjects(where, abstractObjectAtPath, idx) ?: return@forEach, stateAtEncode = stateAtEncode, stateGenerator)
                    }
                }
            }
        }

        private fun intervalApproxAt(inputSizeSymbol: TACSymbol, it: LTACCmd): IntValue? {
            return intervalApproxAt(inputSizeSymbol, it.ptr)
        }

        private fun intervalApproxAt(inputSizeSymbol: TACSymbol, where: CmdPointer): IntValue? {
            return when (inputSizeSymbol) {
                is TACSymbol.Var ->
                    pta.query(NumericApproximation(where, inputSizeSymbol))?.let { approx: UIntApprox<*> ->
                        IntValue.Interval(approx.getLowerBound(), approx.getUpperBound())
                    }
                is TACSymbol.Const -> Constant(inputSizeSymbol.value)
            }
        }

        private fun modelFourByteWrite(
            s: SigHashState,
            summ: InitAnnotation.FourByteWriteSummary,
            where: CmdPointer
        ): SigHashState {
            val pts = pta.fieldNodesAt(where, summ.base)?.let { it as? IndexedWritableSet } ?:
                throw CallGraphBuilderFailedException("Truly strange, asked to update 4 bytes at non-writable location: $where & $summ", g.elab(where))
            if(!pts.index.isConstant || pts.index.c != BigInteger.ZERO) {
                throw CallGraphBuilderFailedException("Four-byte annotation @ $where is wrong? We are expecting to write to beginning, but index is ${pts.index}", g.elab(where))
            }
            val fourByte = Constant(summ.fourByte).liftToReduced(CmdPointerSet.CSet(setOf(
                CmdPointerSet.CSet.BufferSymbol.WrittenAt(
                    where = where,
                    what = summ.fourByte.shiftLeft(256 - 32).asTACSymbol()
                ) to ByteRange(
                    BigInteger.ZERO, 3.toBigInteger()
                )
            )), StorageSet.Nondet)
            return s.forEachIndexedNode(pts) { nodeState, ind ->
                check(ind.index.isConstant && ind.index.c == BigInteger.ZERO) {
                    "Everything is broken, did not have write @ 0 for $ind, despite a promise to the contrary by $pts"
                }
                if(pts.offsetUpdate() == WritablePointsToSet.UpdateType.WEAK) {
                    nodeState.weakUpdate(ind,
                        fourByte
                    )
                } else {
                    nodeState.strongUpdateByte(
                        ind,
                        ByteRange(BigInteger.ZERO, DEFAULT_SIGHASH_SIZE - BigInteger.ONE),
                        fourByte
                    )
                }
            }.let {
                s.copy(nodes = it)
            }
        }

        private fun isWith224trailing0bits(c: BigInteger) =
            c.and(BigInteger.TWO.pow(224).minus(BigInteger.ONE)) ==
                BigInteger.ZERO

        private fun check4byteInHeap(heap: Map<ByteRange, HeapInt>): Map.Entry<ByteRange, HeapInt>? {
            return heap.entries.firstOrNull {
                (it.key.start == BigInteger.ZERO && it.key.end == 3.toBigInteger()) // 4 byte in [0..4)
                    ||
                    (it.key.start == BigInteger.ZERO && // 4 byte is only argument, in [0..32)
                        it.key.end == (31.toBigInteger()) &&
                        it.value.consts.let { it as? ConstSet.C }?.ks
                            ?.singleOrNull()
                            ?.takeIf { c ->
                                isWith224trailing0bits(c)
                            } != null
                        )
            }
        }

        private fun normalizeTo4byte(c: BigInteger): BigInteger =
            if (c < BigInteger.TWO.pow(32)) {
                c
            } else if (isWith224trailing0bits(c)) {
                c.shiftRight(224)
            } else {
                error("Unexpected 4 byte integer ${c.toString(16)}")
            }

        private fun normalizeTo4byteReverse(c: BigInteger): BigInteger =
            if (c < BigInteger.TWO.pow(32)) {
                c.shiftLeft(224)
            } else if (isWith224trailing0bits(c)) {
                c
            } else {
                error("Unexpected 4 byte integer ${c.toString(16)}")
            }

        private fun normalizeTo4byte(offset: ByteRange): ScratchByteRange =
            ScratchByteRange(
                offset.start, if (offset.start == BigInteger.ZERO) {
                    check(offset.end == 3.toBigInteger() || offset.end == 31.toBigInteger())
                        { "Expected a byte range until 3 or 31 (inclusive), but instead got $offset" }
                    3.toBigInteger()
                } else {
                    offset.end
                }
            )

        private fun killCopyOutputNodes(
            s: SigHashState,
            output: IndexedWritableSet
        ) : SigHashState {
            val killedABI = s.abiState.killNode(output.nodes)
            return s.nodes.killAffectedNodes(output).let {
                SigHashState(it, s.storageSlots, ReturnDataState.None, killedABI)
            }
        }

        private fun copyToTarget(
            s: SigHashState,
            where: LTACCmd,
            output: IndexedWritableSet,
            fallback: (SigHashState, IndexedWritableSet) -> SigHashState,
            sourceData: () -> Map<ByteRange, HeapInt>?
        ) : SigHashState {
            val outputWriter = s.nodes.getCopyWriter(output, where = where, optimistic = false) ?: return fallback(s, output)
            val src = sourceData() ?: return fallback(s, output)
            val newNodes = outputWriter(src)
            return SigHashState(
                nodes = newNodes,
                abiState = s.abiState.killNode(output.nodes),
                returnModel = s.returnModel,
                storageSlots = s.storageSlots
            )
        }

        private fun modelCopy(
            s: SigHashState,
            copyNodes: CopyLoopValid.CopyLoopNodes,
            where: LTACCmd
        ): SigHashState {
            val srcNodes = (copyNodes.srcNodes as? CopyLoopValid.CopySource.Fields) ?: return killCopyOutputNodes(s, copyNodes.destNodes)
            return copyToTarget(s, where, copyNodes.destNodes, ::killCopyOutputNodes) srcCmp@{
                val (projected, err) = srcNodes.field.indexed.partitionMap {
                    s.nodes.projectFrom(it, null)
                }
                if(err.isNotEmpty() || projected.isEmpty()) {
                    logger.warn { "Failed to project at $where" }
                    return@srcCmp null
                }
                projected.reduce(NodeState.Companion::simpleMerge)
            }
        }
    }

    /**
     * Extract from the (range of) bytevectors abstracted by [st], the constant prefix beginning at (byte index)
     * [startOffset] and consuming at most [len] bytes.
     */
    private fun extractConstantPrefix(st: IntValue, startOffset: Int, len: Int) : Pair<ScratchByteRange, BigInteger>? {
        // different magnitudes, can't extract constant prefix
        if(st.lb.bitLength() != st.ub.bitLength()) {
            return null
        }
        /*
          Running accumulator for the constant prefix
         */
        var prefixAccum = BigInteger.ZERO
        /*
          How many bits of a byte have we consumed in byteAccum. When this hits 8, byteAccum is shifted onto
          prefix, and numBytes is incremented
         */
        var byteBits = 0
        /*
          Accumulator for building up the "next" constant byte. Only the lower byteBits bits of this value are significant.
         */
        var byteAccum = 0
        /*
          How many bytes have been "consumed" and shifted onto the accumulator, prefix
         */
        var numBytes = 0
        /*
          Translate the start offset as counting from the significant byte to counting from the least significant bit.
         */
        val rangeStart = EVM_BITWIDTH256 - (startOffset * EVM_BYTE_SIZE.intValueExact()) - 1
        /*
          it holds the index of the first non-zero bit. As the bitlengths are equal, this means that both the upper
          and lower bounds have a 1 here, and thus all values in the range between them must have a one set there.
         */
        var it = st.lb.bitLength() - 1
        /*
          If it is less than rangeState (i.e., it is "farther along" the bytevector represented by st)
          then account for any bytes/bits skipped. These bits *must* be zero (by definition of where we started
          it) so we only need to adjust the accounting of bytes/bits, and do not need to updat ehte byteAccum or prefix
         */
        if(it < rangeStart) {
            numBytes = (rangeStart - it) / 8
            byteBits = (rangeStart - it).mod(8)
        } else if(it > rangeStart) {
            /*
              Otherwise, run "down" the bytevector starting from it until we hit our range state.
              The loop condition on testBit ensures that the upper and lower bounds have equal bits,
              which, given that this same condition must have held for all bits more significant, all values
              in the range [lb, ub] must also have the same bit value at it.
             */
            while(it > rangeStart && st.lb.testBit(it) == st.ub.testBit(it)) {
                it--
            }
            /*
             We "diverged" before we reached our start position.
             */
            if(it != rangeStart) {
                return null
            }
        }
        /*
          We've already hit our quota, without parsing any bits. Then we are done, but the expectation is that the
          prefix is entirely 0.
         */
        if(numBytes >= len) {
            check(prefixAccum == BigInteger.ZERO)
            return ScratchByteRange(
                from = BigInteger.ZERO,
                to = (len - 1).toBigInteger()
            ) to prefixAccum
        }
        /*
          Now the parsing. The loop iterates while `it` is non-zero (we haven't run off the end),
          and we still need more bytes, and the bits at `it` are equal.
          The former two conditions ensure the loop terminates once we have enough data.
          The final condition ensures that as we working our way towards the lsb, the bits
          in the upper and lower bounds match. As long as they do, we must have that any value in the range
          [lb,ub] also has the bit set (or not) according to its state in ub/lb.
         */
        while(it >= 0 && numBytes < len && st.ub.testBit(it) == st.lb.testBit(it)) {
            /*
              we update the byte accumulator by "shifting" the set or unset bit
              onto the "end" and updating the number of bits accumulated so far
             */
            byteAccum = if(st.ub.testBit(it)) {
                (byteAccum shl 1) + 1
            } else {
                byteAccum shl 1
            }
            byteBits++
            // decrement
            it--
            /*
             we have a full byte in byteAccum, update the prefixAccum (by shifting the byteAccum onto the end,
             reset the byte accumulator and bits counter, and increment the number of bytes.

             Note that this is run at the *end* of the loop, so if we reach the end of the bytevector while still
             in a constant prefix (which shouldn't actually happen, because then st would be a constant and should be handled elsewhere)
             this check *will* fire in the last iteration before it terminates (so no check needed outside of the loop
             to see if we need to include partial results)
             */
            if(byteBits == 8) {
                prefixAccum = prefixAccum.shiftLeft(8) or byteAccum.toBigInteger()
                byteAccum = 0
                byteBits = 0
                numBytes++
            }
        }
        if(numBytes == 0) {
            return null
        }
        return ScratchByteRange(
            from = BigInteger.ZERO,
            to = (numBytes - 1).toBigInteger()
        ) to prefixAccum
    }

    /**
     * Given an interval approximation [vecRange], which represents a (range of) bytevectors (where the most significant
     * byte is index 0, the second most significant byte is index 1, etc.) extract the constructor signature starting at
     * [start] and of length [len]. Both [start] and [len] are given in bytes. [start] is required to lie within the
     * range of [0, word_size-1].
     *
     * If starting from [start], [len] runs past the "end" of the (set of) bytevector represented by [vecRange], then the [nextValue]
     * function is called to try to complete the constructor signature. If this function returns a non-null value,
     * this function is recursively invoked with start = 0, and len = remaining bytes, and a [nextValue] that always returns
     * null (i.e., this function can interpret up to 2 words worth of bytevectors)
     */
    internal fun extractConstructorSignatureFromConst(
        start: Int,
        len: Int,
        vecRange: IntValue,
        nextValue: () -> IntValue?,
    ) : Pair<ScratchByteRange, BigInteger>? {
        val wordSize = EVM_WORD_SIZE.intValueExact()
        val byteSize = EVM_BYTE_SIZE.intValueExact()
        /*
          How many bytes to read out of st when beginning at start.
         */
        val vectorLengthInBytes = if(start + len > wordSize) {
            wordSize - start
        } else {
            len
        }
        return if(vecRange.isConstant) {
            /*
               How many bits are there between start and the "end" of the bytevector.
             */
            val truncateRight = (wordSize - (start + vectorLengthInBytes)) * byteSize
            // truncate those upper bits by shifting down
            val shiftedDown = vecRange.c.shiftRight(truncateRight)
            val constantPrefix = ScratchByteRange(
                from = BigInteger.ZERO,
                to = (vectorLengthInBytes - 1).toBigInteger()
            ) to shiftedDown.and(MASK_SIZE(vectorLengthInBytes * byteSize)) // mask out the lowest vectorLengthInBytes bytes
            /*
              We are done, start + len fit entirely within this first word
             */
            if(start + len <= wordSize) {
                constantPrefix
            } else {
                /*
                  Try to extract whatever remains of len out of the second word (if available)
                 */
                nextValue()?.let {
                    extractConstructorSignatureFromConst(
                        start = 0,
                        len = len - vectorLengthInBytes,
                        vecRange = it,
                        nextValue = { -> null }
                    )
                }?.takeIf { it.first.from == BigInteger.ZERO }?.let { (range, vec) ->
                    /*
                      the end byte is inclusive, so range.to + 1 gives the actual length (bytes [0, 1] is of length 2)
                     */
                    val addedLength = range.to + BigInteger.ONE
                    ScratchByteRange(
                        from = BigInteger.ZERO,
                        to = constantPrefix.first.to + addedLength // if we had [0, 1] and added two bytes, we go to up byte 3
                        /*
                          If we are "appending" addedLength bytes to the bytevector represented by constantPrefix.second,
                          then we have to shift up by that amount to make room.
                         */
                    ) to constantPrefix.second.shiftLeft((addedLength * EVM_BYTE_SIZE).intValueExact()).or(vec)
                } ?: constantPrefix
            }
        } else {
            /*
              NB: we do not pass the nextValue function in here, because we know the byte range will be truncated.
              Q: What if the second word is *entirely* determininstic, and we could get disjoint, constant byte ranges?
              A: In principle we could, but considering this is basically written to support the proxy contract pattern
              done by OZ, this is fine: we're only *really* interested in the constant prefix of the constructor string.
             */
            extractConstantPrefix(vecRange, start, vectorLengthInBytes)
        }
    }

    /**
     * either we turned on source heuristic, or just for the constructor and we indeed deal with constructor function
      */
    private fun sourceHeuristicEnabled(method: ITACMethod) =
        /* for constructors use the config for constructors, otherwise use global one */
        when (method.attribute) {
            is MethodAttribute.Unique.Constructor -> Config.EnableSolidityBasedInliningInConstructor.get()
            else -> Config.EnableSolidityBasedInlining.get()
        }

    /**
     * gets from the source meta info of [callcore] the set of potential sighashes that can be called.
     * It uses string-ops :(((
     * The idea is to support calls of the form ContractName.MethodName().
     * As for example we have in libraries.
     * We do not support `ContractName(castedAddress).MethodName()`
     * or `contractObject.MethodName()`.
     * The main use case for this is cases where there are PTA failures, e.g. constructors where PTA is disabled.
     *
     * We use the regex-ed info to fetch from the Scene all methods that could be relevant, and return their sighashes.
     */
    private fun sourceHeuristic(
        callcore: TACCmd.Simple.CallCore,
        ptr: CmdPointer,
        scene: IScene,
        enclosingMethod: ITACMethod,
        maybeActualInSize: BigInteger?
    ): Pair<Set<BigInteger>, CalledContract?> {
        // very much a best-effort thing

        // we may want eventually to disable this by default
        if (!sourceHeuristicEnabled(enclosingMethod)) {
            return emptySet<BigInteger>() to null
        }

        // get the source code's string attached to the call
        val src = callcore.metaSrcInfo?.getSourceCode() ?: return emptySet<BigInteger>() to null
        logger.info { "Got in $ptr source $src" }

        val (methods, maybeContract) = sourceStringToPotentiallyCalledMethods(src, scene)

        val (sighashes, numSources) = methods
            .filter { method ->
                val calldataEncoding = method.calldataEncoding as? CalldataEncoding
                val expectedInSize = (calldataEncoding?.argNum as? ArgNum.Known)?.n
                if(calldataEncoding?.valueTypesArgsOnly == false) {
                    maybeActualInSize == null
                } else {
                    // if we know the actual inSize from the code:
                    // if expected size is unknown, maybe we actually selected a method accepting a dynamic type,
                    // thus it should NOT be inlined, it's not really matching.
                    // if the expected size is known, then it should be equal to actual size

                    // if actual size is not known, then we also expect an unknown expected size
                    maybeActualInSize != null && calldataEncoding?.sighashSize?.let { sighashSize ->
                            expectedInSize?.let { it * EVM_WORD_SIZE + sighashSize }
                        } == maybeActualInSize
                }
            }
            .mapNotNull { matching ->
                matching.sigHash?.n
            }.let { it.toSet() to it.size }
        logger.info { "Got in $ptr the sighashes ${sighashes.toSet().map { it.toString(16) }} (in ${numSources} contracts)" }

        // overloading handling - not here. Try to determine the inSize - in inliner itself
        return sighashes to maybeContract?.let { CalledContract.FullyResolved.ConstantAddress(it.instanceId) }
    }

    fun sourceStringToPotentialMethodName(src: String): String? =
    // The implied syntax here is that the caller contract has no dots in its expression, and
    // after the first '.' we will encounter a '(' or a '{' that will determine the string for the method name.
    // for example ext.foo(...) or ext.foo{gas: ...}(...)
    // method name must contain alphanumeric and '_' characters only.
    // How to do this better? Get the actual AST.
        // I PROMISE I HAVE THOUGHT LONG AND HARD ABOUT THIS.
        src
            .substringAfter(".").let { sub ->
                sub.indexOfFirst {
                    it == '(' || it == '{'
                }.takeIf { it >= 0 }?.let {
                    sub.substring(0, it)
                }
            }?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }

    fun sourceStringToPotentialCallerName(src: String): String? =
        src.substringBefore(".")
            .takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }

    private fun sourceStringToPotentiallyCalledMethods(src: String, scene: IScene): Pair<Collection<ITACMethod>, IContractClass?> {
        val name = sourceStringToPotentialMethodName(src) ?: return emptySet<ITACMethod>() to null
        val callingContractName = sourceStringToPotentialCallerName(src)
        // fetch all sighashes from all contracts in the scene that have a method with a matching name
        val candidateMethods = scene.getContracts().flatMap { it.getDeclaredMethods() }.filter { name == it.name }
        return if (candidateMethods.size >= 1 && callingContractName != null) {
            // filter methods that match the calling contract
            // note this only works in library calls in which the library name is before the dot.
            // e.g. MyLib.foo(...)
            // usual external calls linked with --link or more complex schemes will not enjoy from this matching
            val candidateMethodsWithContractNameFilter = candidateMethods.filter { it.getContainingContract().name == callingContractName }
            // if we got just one candidate, our chances are good...
            if (candidateMethodsWithContractNameFilter.size == 1) {
                candidateMethodsWithContractNameFilter to candidateMethodsWithContractNameFilter.single().getContainingContract()
            } else {
                // otherwise, just return the original list.
                // we could potentially use the information to implement a dispatcher over the pairs of contracts
                // and methods matched, but this is left as future work
                candidateMethods to null
            }
        } else {
            // if there's no calling contract 'heuristical resolution' or we already have <= 1 methods, return those
            candidateMethods to null
        }
    }

    /**
     * Infers the precise sighash in [callcore] within graph [g] using a very ad-hocish analysis,
     * see the comments in the body of the function
     */
    private fun inferSighashPrecisely(g: TACCommandGraph, callcore: LTACCmdView<TACCmd.Simple.CallCore>): BigInteger? {
        // the idea is to try to compare the in-offset (via its definition) to a matching bytestore's location definition
        val inOffsetDef = when (val inOffset = callcore.cmd.inOffset) {
            is TACSymbol.Const -> inOffset.asSym()
            is TACSymbol.Var -> g.cache.def.defSitesOf(inOffset, callcore.ptr).singleOrNull()
                ?.let { g.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs }
        } ?: return null // short circuit if in-offset is unknown

        // iterate reversely from the callcore
        val potentialBytestore = g.iterateRevBlock(callcore.ptr)
            // find the bytestores or longstores that match the callcore's in base
            .mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf { it.cmd.base == callcore.cmd.inBase } ?:
                // longstores will be filtered out
                it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf { it.cmd.dstBase == callcore.cmd.inBase } ?:
                // writes to tacM0x0 will also be filtered out
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf { lcmd -> TACMeta.RESERVED_MEMORY_SLOT in lcmd.cmd.lhs.meta }
            }
            // we really want just the bytestores, intermediate longstores, tacM0xXX writes can ruin soundness
            .takeWhile { it.cmd is TACCmd.Simple.AssigningCmd.ByteStore }
            // make sure we get ByteStore LTACCmdViews
            .mapNotNull { lcmd -> lcmd.wrapped.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.let { bytestoreView ->
                    bytestoreView to (bytestoreView.cmd.loc as? TACSymbol.Var)?.let { loc ->
                        g.cache.def.defSitesOf(loc, bytestoreView.ptr).singleOrNull()
                    }
                }
            }
            // we require only the prefix of bytestores whose locations have a single definition
            .takeWhile { it.second != null }
            // and we attach those definitions
            .map { it.first `to?` g.elab(it.second!!).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs }
            // we expect those to be expressions, again take a contiguous prefix only
            .takeWhile { it != null }
            // we only care about those that may alias... if a bytestore definitely does not alias it doesn't matter
            .filter { byteStoreCmdToLocDef ->
                byteStoreCmdToLocDef != null // always true
                    && LogicalEquivalence.mayAlias(byteStoreCmdToLocDef.second, inOffsetDef) == true
            }
            // we pick the first one that may alias
            .firstOrNull()
            // and if we're lucky, it also must alias with the location we read from, and then we could try to read the value
            // and hopefully find a sighash (see next instruction)
            ?.takeIf { byteStoreCmdToLocDef ->
                LogicalEquivalence.equiv(byteStoreCmdToLocDef.second, inOffsetDef)
            }?.first

        // inferred sighash
        return (potentialBytestore?.cmd?.value as? TACSymbol.Const)?.value?.shiftRight(224)
    }

    private fun soliditySourceSigResolver(m: ITACMethod, scene: IScene): Map<CmdPointer, Pair<Set<BigInteger>, Set<CalledContract>>> {
        val g = (m.code as CoreTACProgram).analysisCache.graph
        val methodWithFullDSA = try {
            val code = m.code as CoreTACProgram
            TACDSA.simplify(
                code,
                protectedVars = emptySet(),
                isErasable = FilteringFunctions.default(code, keepRevertManagment = true)::isErasable
            )
        } catch (e: Exception) {
            if (m.attribute is MethodAttribute.Unique.Constructor) {
                logger.debug(e) { "Failed to compute DSA for ${m.name}, will not be able to infer sighashes soundly, falling back to source-based. We expect this to happen." }
            } else {
                logger.info(e) { "Failed to compute DSA for ${m.name}, will not be able to infer sighashes soundly, falling back to source-based. There's likely nothing to worry about." }
            }
            null
        }

        val calleeMatcher = PatternMatcher.compilePattern(g, PatternMatcher.Pattern.FromVar(extractor = { _, v ->
            v.meta.get(TACBasicMeta.IMMUTABLE_LINK)?.let {
                PatternMatcher.VariableMatch.Match(it)
            } ?: PatternMatcher.VariableMatch.Continue
        }))

        val map = g.commands
            .mapNotNull { it.maybeNarrow<TACCmd.Simple.CallCore>() }
            .map { callcore ->
            // inSize could impact which method could impact the chosen method
            val expressionSimplifier = ExpressionSimplifier(g)
            val maybeInSize = when (val inSize = callcore.cmd.inSize) {
                is TACSymbol.Const -> inSize.value
                is TACSymbol.Var -> {
                    expressionSimplifier.simplify(inSize, callcore.ptr, true).getAsConst()
                }
            }
            // we may even be lucky and figure out the sighash precisely anyway. e.g. funcs with no arguments
            // to do that soundly, we used a fully-dsa version of the code
            val inferredSighash = methodWithFullDSA?.let {
                // find the new callcore.
                val newG = it.analysisCache.graph
                // the callcore block may not exist in the post-dsa version. In this case we just bail-out
                if (callcore.ptr.block !in newG.code) {
                    return@let null
                }

                val currentBlockCallcores =
                    g.elab(callcore.ptr.block).commands.mapNotNull { lcmd -> lcmd.ptr.takeIf { lcmd.cmd is TACCmd.Simple.CallCore } }
                val newBlockCallcores =
                    newG.elab(callcore.ptr.block).commands.mapNotNull { lcmd -> lcmd.ptr.takeIf { lcmd.cmd is TACCmd.Simple.CallCore } }
                val currentCallcoreIdx = currentBlockCallcores.indexOf(callcore.ptr)
                val newCallcore = newBlockCallcores[currentCallcoreIdx]
                inferSighashPrecisely(it.analysisCache.graph, newG.elab(newCallcore).narrow())
            }
            if (inferredSighash != null) {
                logger.info { "We inferred a precise sighash for $callcore in ${g.name}: $inferredSighash" }
            }

            val inferredCallee = (callcore.cmd.to as? TACSymbol.Var)?.let {
                calleeMatcher.query(it, callcore.wrapped)
            }?.toNullableResult()?.let(CalledContract.FullyResolved::ImmutableReference)

            if(inferredCallee != null) {
                logger.info { "We inferred precise callee for $callcore in ${g.name}: $inferredCallee" }
            }


            // update the source-heuristic if were able to infer a precise sighash
            val sourceHeuristicResults = sourceHeuristic(callcore.cmd, callcore.ptr, scene, m, maybeInSize).let { (sigs, callee) ->
                // we can make our guess more precise for the sighash, this enables summarizations
                (inferredSighash?.let(::setOf) ?: sigs) to (inferredCallee ?: callee)?.let { setOf(it) }.orEmpty()
            }
            callcore.ptr to sourceHeuristicResults
        }.toMap()
        if (map.values.all { it.first.isEmpty() && it.second.isEmpty() }) {
            return mapOf()
        }
        return map
    }

    @TestOnly
    fun doSigAnalysis(m: ITACMethod) : AnalysisResults? {
        return PointerAnalysis.runAnalysis(m, PTARunPurpose.CGB).let {
            it as? FlowPointsToInformation
        }?.let {
            Worker(m, it).analyze(null).let {
                AnalysisResults(
                    it.sigHash,
                    it.input,
                    it.decomposedCallInputWithSourceWrites,
                    it.callee,
                    mapOf(),
                    mapOf(),
                    it.bufferSizes,
                    mapOf(),
                    it.storageReadNumering,
                    mapOf(),
                    mapOf(),
                    mapOf()
                )
            }
        }
    }

    @TestOnly
    fun doMemoryModel(m: ITACMethod, scene: IScene): Pair<Map<CmdPointer,SigHashState>,IPointsToInformation> {
        val pointsTo =
            CodeAnalysis("POINTSTO_MEMORY",
                { method: ITACMethod -> PointerAnalysis.runAnalysis(method, PTARunPurpose.TEST) },
                { it.isCompleteSuccess }
            ).runAnalysis(m)

        if (!pointsTo.isCompleteSuccess) {
            return emptyMap<CmdPointer,SigHashState>() to pointsTo
        }

        val memoryTag = "MEMORY_MODEL_WORKER".toTimeTag()
        val timeRecorder = ElapsedTimeStats().startMeasuringTimeOf(memoryTag)
        val worker = Worker(m, pointsTo)
        worker.analyze(scene)
        timeRecorder.endMeasuringTimeOf(memoryTag)
        return worker.exportState to pointsTo
    }

    private fun doSigAndInputAnalysis(
        m: ITACMethod,
        scene: IScene,
        timeRecorder: ElapsedTimeStats
    ): CallgraphBuilderResult {
        val commands = (m.code as CoreTACProgram).analysisCache.graph.commands
        val ptaRunPurpose =
            if (commands.any { it.cmd is TACCmd.Simple.CallCore }) {
                PTARunPurpose.CGB
            } else if (commands.none {
                    it.cmd is TACCmd.Simple.AssigningCmd.AssignSha3Cmd ||
                        it.maybeNarrow<TACCmd.Simple.SummaryCmd>()?.takeIf {
                            it.cmd.summ is ExternalMapGetterSummarization.ExternalGetterHash
                        } != null
                }) {
                // if we also don't have anything disciplined-hash-model is looking for, we can simply skip
                PTARunPurpose.CGB_INDIRECT
            } else {
                PTARunPurpose.HASHING
            }
        timeRecorder.startMeasuringTimeOf(POINTSTO.toTimeTag())
        val pointsTo =
            PointerAnalysis.runAnalysis(m, ptaRunPurpose).apply {
                timeRecorder.endMeasuringTimeOf(POINTSTO.toTimeTag())
            }
        fun getHeuristicalResult() =
            soliditySourceSigResolver(m, scene)
                .let { sigsAndCallees ->
                    SigResolutionAnalysisResult.Heuristic(
                        sigs = sigsAndCallees.mapValues { it.value.first },
                        callees = sigsAndCallees.mapValuesNotNull { it.value.second })
                }

        recordSuccess("$m", ANALYSIS_SUCCESS_STATS_KEY, ANALYSIS_POINTSTO_SUBKEY, pointsTo.isCompleteSuccess)

        val (fullResult, pointsToResult) = if (pointsTo is TrivialPointsToInformation) {
            recordSuccess("$m", ANALYSIS_SUCCESS_STATS_KEY, ANALYSIS_CALLGRAPH_SUBKEY, false)
            null to null
        } else {
            timeRecorder.startMeasuringTimeOf(CALLGRAPHBUILDER.toTimeTag())
            try {
                // SG: I really hate the fact that exportState is only initialized depending on `analyze` running first.
                val (callgraphRes, memoryMap) = Worker(m, pointsTo).let { worker -> worker.analyze(scene) to worker.exportState }
                timeRecorder.endMeasuringTimeOf(CALLGRAPHBUILDER.toTimeTag())
                recordSuccess("$m", ANALYSIS_SUCCESS_STATS_KEY, ANALYSIS_CALLGRAPH_SUBKEY, true)
                callgraphRes to PointsTo(memoryMap, pointsTo)
            } catch (e: CallGraphBuilderFailedException) {
                timeRecorder.endMeasuringTimeOf(CALLGRAPHBUILDER.toTimeTag())
                Logger.alwaysError("Failed to build callgraph from points-to information in $m @ ${e.where}", e)
                recordSuccess("$m", ANALYSIS_SUCCESS_STATS_KEY, ANALYSIS_CALLGRAPH_SUBKEY,false)
                val graph = (m.code as? CoreTACProgram)?.analysisCache?.graph
                val rangeWithMsgDetails = getSourceHintWithRange(e.where, graph, m)
                CVTAlertReporter.reportAlert(
                    CVTAlertType.CALL_GRAPH,
                    CVTAlertSeverity.WARNING,
                    rangeWithMsgDetails.range,
                    "Call graph construction failed in contract ${m.getContainingContract().name}, " +
                        "function ${m.soliditySignature ?: m.name}. ${rangeWithMsgDetails.additionalUserFacingMessage}"
                )
                null to null
            } catch (e: Exception) {
                timeRecorder.endMeasuringTimeOf(CALLGRAPHBUILDER.toTimeTag())
                Logger.alwaysError("Severe error occurred trying to build callgraph from points-to information in $m", e)
                recordSuccess("$m", ANALYSIS_SUCCESS_STATS_KEY, ANALYSIS_CALLGRAPH_SUBKEY,false)
                CVTAlertReporter.reportAlert(
                    CVTAlertType.CALL_GRAPH,
                    CVTAlertSeverity.WARNING,
                    null,
                    "Call graph construction failed in contract ${m.getContainingContract().name}, " +
                        "function ${m.soliditySignature ?: m.name}, problematic source code unknown.",
                    null
                )
                null to null
            }
        }

        val (heuristicalResult, heuristicalCallees) = if (fullResult != null && pointsTo.isCompleteSuccess) {
            null to null
        } else {
            logger.info { "Applying heuristics" }
            val res = getHeuristicalResult()
            val callees = doCalleeAnalysis(m, scene, timeRecorder).resolution.takeIf { it.isNotEmpty() } ?: res.callees
            res to callees
        }

        // Note: fullResult should always supercede heuristicalResult
        return CallgraphBuilderResult(
            AnalysisResults(
                sigResolution = heuristicalResult?.sigs.orEmpty() + fullResult?.sigHash.orEmpty(),
                callInputResolution = fullResult?.input.orEmpty(),
                decomposedCallInput = fullResult?.decomposedCallInputWithSourceWrites.orEmpty(),
                callee = (heuristicalCallees.orEmpty() + fullResult?.callee.orEmpty()).mapValues { it.value.ifEmpty { setOf(CalledContract.Unresolved) } },
                returnResolution = fullResult?.returnResolution.orEmpty(),
                calleeReturnReadResolution = fullResult?.returnValueReads.orEmpty(),
                inOutBuffers = fullResult?.bufferSizes.orEmpty(),
                callNumbering = fullResult?.callCoreNumbering.orEmpty(),
                readNumbering = fullResult?.storageReadNumering.orEmpty(),
                constructorCalls = fullResult?.constructorCalls.orEmpty(),
                logEncodes = fullResult?.logEncodes.orEmpty(),
                symbolicSigResolution = fullResult?.symbolicSighash.orEmpty()
            ),
            pointsToResult
        )
    }

    private fun doCalleeAnalysis(m: ITACMethod, scene: IScene, timeRecorder: ElapsedTimeStats): CalleeResolution {
        timeRecorder.startMeasuringTimeOf(CALLED_CONTRACT.toTimeTag())
        return CalledContractResolver.doAnalysis(m, scene).apply {
            timeRecorder.endMeasuringTimeOf(CALLED_CONTRACT.toTimeTag())
        }
    }

    private fun startCallgraphMeasurement() =
        ElapsedTimeStats().startMeasuringTimeOf(CALLGRAPH_FULL.toTimeTag())

    private fun endCallgraphMeasurement(callgraphBuilderTimeStatsRecorder: ElapsedTimeStats, m: ITACMethod) {
        callgraphBuilderTimeStatsRecorder.endMeasuringTimeOf(CALLGRAPH_FULL.toTimeTag())

        recordAggregatedTransformationStats(callgraphBuilderTimeStatsRecorder, m.code.name)
    }

    data class AnalysisResults(
        val sigResolution: SigResolution,
        val callInputResolution: CallInputResolution,
        val calleeResolution: CalleeResolution,
        val returnInformation: ReturnResolution,
        val calleeReturnReadResolution: CalleeReturnReadResolution,
        val inoutBuffers: InputOutputResolution,
        val callNumbering: CallNumbering,
        val readNumbering: ReadNumbering,
        val constructorCalls: ConstructorCalls,
        val symbolicSigResolution: SymbolicSigResolution,
        val logEncodes: LogEncodes
    ) {


        companion object {
            fun Pair<SigResolution, CallInputResolution>.lift(calleeResolution: CalleeResolution? = null): AnalysisResults =
                AnalysisResults(
                    this.first,
                    this.second,
                    calleeResolution ?: CalleeResolution.empty,
                    ReturnResolution(mapOf()),
                    CalleeReturnReadResolution(mapOf()),
                    InputOutputResolution(mapOf()),
                    CallNumbering(mapOf()),
                    ReadNumbering(mapOf()),
                    ConstructorCalls(mapOf()),
                    symbolicSigResolution = SymbolicSigResolution(mapOf()),
                    LogEncodes(mapOf())
                )

            fun empty(): AnalysisResults = (SigResolution.empty to CallInputResolution.empty).lift()
        }

        constructor(
            sigResolution: Map<CmdPointer, Set<BigInteger>>,
            callInputResolution: Map<CmdPointer, CallInput>,
            decomposedCallInput: Map<CmdPointer, Set<DecomposedArgVariableWithSourceWrites>>,
            callee: Map<CmdPointer, Set<CalledContract>>,
            returnResolution: Map<CmdPointer, ReturnInformation>,
            calleeReturnReadResolution: Map<CmdPointer, ReturnPointer>,
            inOutBuffers: Map<CmdPointer, InOutBuffers>,
            callNumbering: Map<CmdPointer, Int>,
            readNumbering: Map<CmdPointer, Int>,
            constructorCalls: Map<CmdPointer, ConstructorCalls.ConstructorResolution>,
            symbolicSigResolution: Map<CmdPointer, DecoderAnalysis.BufferAccessPath>,
            logEncodes: Map<CmdPointer, Int>
        ) : this(
            SigResolution(sigResolution),
            CallInputResolution(callInputResolution, decomposedCallInput),
            CalleeResolution(callee),
            ReturnResolution(returnResolution),
            CalleeReturnReadResolution(calleeReturnReadResolution),
            InputOutputResolution(inOutBuffers),
            CallNumbering(callNumbering),
            ReadNumbering(readNumbering), // what on earth are these wrappers for
            ConstructorCalls(constructorCalls),
            SymbolicSigResolution(symbolicSigResolution),
            LogEncodes(logEncodes)
        )

    }

    fun doSigAndInputAndCalleeAnalysis(m: ITACMethod, scene: IScene): CallgraphBuilderResult {
        val callgraphBuilderTimeStatsRecorder = startCallgraphMeasurement()
        return doSigAndInputAnalysis(m, scene, callgraphBuilderTimeStatsRecorder).also {
            endCallgraphMeasurement(callgraphBuilderTimeStatsRecorder, m)
        }
    }

}

data class CallgraphBuilderResult(
    val analysisResults: CallGraphBuilder.AnalysisResults,
    val pta: PointsTo?
)

typealias MemoryMap = Map<CmdPointer, CallGraphBuilder.SigHashState>
data class PointsTo(val memoryMap: MemoryMap, val pta: IPointsToInformation)

private interface NodeVisitor<out T> {
    fun visit(v: IndexedWritableSet.IndexedNode) : T
    fun visit(v: PTANode) : T
}

private fun <T> IPointsToSet.map(f: NodeVisitor<T>) : List<T> = if(this is IndexedWritableSet) {
    this.indexed.map(f::visit)
} else {
    this.nodes.map(f::visit)
}
