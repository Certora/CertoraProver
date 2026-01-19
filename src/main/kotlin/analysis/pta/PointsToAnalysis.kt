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

import analysis.*
import analysis.alloc.AllocationAnalysis
import analysis.dataflow.IMustEqualsAnalysis
import analysis.ip.InternalFuncRet
import analysis.ip.InternalFuncStartAnnotation
import analysis.loop.SimpleLoopSummarization
import analysis.numeric.IntQualifier
import analysis.numeric.IntValue
import analysis.numeric.PathInformation
import analysis.numeric.linear.*
import analysis.numeric.linear.TermMatching.matches
import analysis.pta.abi.*
import analysis.storage.BytesKeyHash
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import scene.IBoundTACMethod
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.*

/**
An exception indicating that the points-to state is no longer valid, beginning at the location that was being processed
when this exception was thrown.  State at predecessor locations may still be usable, unless those locations are also
successors of the current location.
 */
open class AnalysisFailureException: Exception {
    constructor(s: String) : super(s)
    constructor(s: String, c: Throwable?) : super(s, c)
    constructor() : super()
}

class TypeMismatchFailureException(s: String) : AnalysisFailureException(s)

class PointsToAnalysisFailedException(
    val msg: String,
    val enclosingInternalMethod: InternalFuncStartAnnotation?,
    val where: LTACCmd,
    t: Throwable? = null,
    val fatal: Boolean = t != null && t !is AnalysisFailureException,
) : AnalysisFailureException(msg, t)

class PruneInfeasible : Exception()

private val logger = Logger(LoggerTypes.POINTS_TO)

private val decoderLogger = Logger(LoggerTypes.ABI_DECODER)
private val encoderLogger = Logger(LoggerTypes.ABI_ENCODER)

typealias SyntheticAlloc = List<Pair<TACSymbol.Var, EVMTypeDescriptor>>

sealed interface PointsToResult {
    fun containsFatalError(): Boolean
}

/*
High level idea: run several analyses "in parallel" but have them exchange information a la the reduced product.
Our combined analysis therefore operates over two primary domains: the points to graph, which is manipulated by the pointer
semantics, the numeric domain, which is manipulated by the bounds analysis (the names are currently not in the best
place), the array state domain, struct domain, decoder/encoder domains, object path, and invariants.
 */
data class PointsToDomain(
    val pointsToState: PointsToGraph,
    val boundsAnalysis: NumericDomain,
    val arrayState: ArrayStateDomain,
    val structState: StructStateDomain,
    val decoderState: DecoderAnalysis.State,
    val encoderState: EncoderAnalysis.State,
    val objectPath: PathState,
    val invariants: LinearInvariants
) : PointsToResult {
    override fun containsFatalError() = false
}

/**
Tracks points-to failures.  We keep one failure for each TAC location, discarding any duplicates.
 */
private data class PointsToFailures(
    val failures: Map<CmdPointer, PointsToAnalysisFailedException> = mapOf()
) : PointsToResult {
    constructor(msg: String, enclosingInternalMethod: InternalFuncStartAnnotation?, where: LTACCmd, t: Throwable? = null) : this(PointsToAnalysisFailedException(msg, enclosingInternalMethod, where, t))
    constructor(failure: PointsToAnalysisFailedException) : this(mapOf(failure.where.ptr to failure))
    operator fun plus(that: PointsToFailures) = PointsToFailures(this.failures + that.failures)
    override fun containsFatalError() = failures.values.any { it.fatal }
}

private fun PointsToFailures?.orEmpty() = this ?: PointsToFailures()

/*
These two interfaces expose the how the two analyses will communicate. The numeric analysis
provides information about precise numeric values, and tracks whether certain values are within bounds.
 */
interface INumericInformation {
    fun interpAsConstant(pState: PointsToDomain, ltacCmd: LTACCmd, value: TACSymbol): BigInteger?
    fun isSafeArrayOffsetFor(pstate: PointsToDomain, where: LTACCmd, op1: TACSymbol, op2: TACSymbol): Boolean
    fun isSafeMultiplierFor(pstate: PointsToDomain, blockSize: BigInteger, op2: TACSymbol.Var): Boolean
    fun isSafeArrayElementOffsetFor(pstate: PointsToDomain, where: LTACCmd, op2: TACSymbol, startOf: Set<TACSymbol.Var>): Boolean
    fun interpAsUnambiguousConstant(pState: PointsToDomain, ltacCmd: LTACCmd, value: TACSymbol) : BigInteger?
}

/*
The pointer semantics provides information on the types of values read out of the heap (the bounds analysis
does not include a heap component) and whether a read is a length (used for propagating in-bounds information).
 */
interface IPointerInformation : SymInterpreter<PointsToGraph, VPointsToValue> {
    fun getReadType(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): HeapType
    fun getHeapType(loc: TACSymbol, pState: PointsToDomain) : HeapType?
    fun getElementSize(loc: TACSymbol.Var, pState: PointsToGraph) : BigInteger?
    fun getElementSizeOrEmpty(loc: TACSymbol.Var, pState: PointsToGraph) : ElementSize?
    fun isLengthRead(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Boolean
    fun isByteArray(loc: TACSymbol.Var, pState: PointsToDomain) : Boolean
    fun isLengthWrite(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain) : Boolean
    fun isEmptyArray(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Boolean
    fun lengthReadFor(ltacCmd: LTACCmd, pState: PointsToDomain) : Set<TACSymbol.Var>?
    fun isEmptyStringArrayRead(s: TACSymbol.Var, target: PointsToDomain): Boolean
    fun lengthForActiveAllocs(loc: TACSymbol, ltacCmd: LTACCmd, pState: PointsToDomain): Set<TACSymbol.Var>
    fun blockSizeOf(loc: TACSymbol.Var, pState: PointsToGraph) : BigInteger?
}

data class ValidElemPointer(val elemPointer: TACSymbol.Var, val baseArrays: Set<TACSymbol.Var>)

/**
 * The block pointer [block] is a valid const array pointer for [base]
 */
data class ValidBlock(val block: TACSymbol.Var, val base: TACSymbol.Var)

sealed class ArrayHints {
    data class MustNotBeEmpty(val op: TACSymbol.Var) : ArrayHints()
    data class MustBeEmpty(val op: TACSymbol.Var) : ArrayHints()
}

sealed class ConversionHints {
    abstract val v: TACSymbol.Var
    data class Int(override val v: TACSymbol.Var) : ConversionHints()
    data class Array(override val v: TACSymbol.Var) : ConversionHints()
    data class ArrayElemStart(override val v: TACSymbol.Var) : ConversionHints()
    data class Block(override val v: TACSymbol.Var) : ConversionHints()
}

class PointsToAnalysis(
    private val graph: TACCommandGraph,
    method: IBoundTACMethod?,
    val allocSites: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    val scratchSite: Map<CmdPointer, Optional<BigInteger>>,
    val initialFreePointerValue: BigInteger?,
    allowLengthUpdates: Boolean = false
) {

    private val lva = graph.cache.lva

    sealed class PointerType {
        data object Other : PointerType()
        data object EmptyArray : PointerType()
        data class ElementPointer(val v: TACSymbol.Var) : PointerType()
    }

    sealed class LoopSummaryClassification {
        data object Invalid : LoopSummaryClassification()

        data class Valid(
            val input: PointerType,
            val output: TACSymbol.Var
        ) : LoopSummaryClassification()
    }

    private val relaxedSemantics = Config.GloballyRelaxedPointsToSemantics.get()
        || Config.RelaxedPointsToSemantics.get().any { (contract, methodName) ->
            method?.getContainingContract()?.name == contract && method.name == methodName
        }

    private fun validateSummaryRelaxed(state: PointsToDomain, cond: LoopCopyAnalysis.LoopCopySummary) : LoopSummaryClassification {
        fun plausibleInput(v: TACSymbol.Var) : PointerType? {
            state.arrayState[v]?.takeIf {
                it is ArrayStateAnalysis.Value.ElementPointer
            }?.let {
                return PointerType.ElementPointer(v)
            }
            if(cond.sourceMap == TACKeyword.CALLDATA.toVar()) {
                return PointerType.Other
            }
            return null
        }
        fun plausibleOutput(v: TACSymbol.Var) : Boolean {
            return state.pointsToState.store[v].let {
                    it == ScratchPointer ||
                        it is InitializationPointer.ByteInitPointer ||
                        it is InitializationPointer.ArrayInitPointer
                }
        }
        return LoopSummaryClassification.Valid(
            output = cond.outPtr.firstOrNull {
                plausibleOutput(it)
            } ?: return LoopSummaryClassification.Invalid,
            input = cond.inPtr.firstNotNullOfOrNull {
                plausibleInput(it)
            } ?: return LoopSummaryClassification.Invalid
        )
    }

    internal fun validateSummary(state: PointsToDomain, cond: LoopCopyAnalysis.LoopCopySummary) : LoopSummaryClassification {
        return validateSummaryStrict(state, cond).takeIf {
            it is LoopSummaryClassification.Valid || !relaxedSemantics
        } ?: validateSummaryRelaxed(state, cond)
    }

    private fun validateSummaryStrict(state: PointsToDomain, cond: LoopCopyAnalysis.LoopCopySummary) : LoopSummaryClassification {
        /*
          The rules for deciding validity of a copy loop are given here:
          https://www.notion.so/certora/Loop-Copy-Rules-e2e545a3ca524ec68dcde8f6de138f29
        */
        val lenQualifiers by lazy {
            cond.lenVars.mapNotNull {
                state.boundsAnalysis[it] as? QualifiedInt
            }.flatMap {
                it.qual
            }.toSet()
        }
        /*
           1. destination is a scratch pointer
         */
        val isScratch: (TACSymbol.Var) -> Boolean = {
            state.pointsToState.store[it] == ScratchPointer
        }
        if (cond.outPtr.any(isScratch)) {
            /* now let's check that the input pointer is an allocated block that is
               length safe with respect to the loop length. Step 1.a
             */
            for(it in cond.inPtr) {
                /*
                  check that the input pointer has the proper stride
                 */
                val elem = state.arrayState[it]?.let {
                    it as? ArrayStateAnalysis.Value.ElementPointer
                }?.takeIf {
                    it.index.isConstant && it.index.c == BigInteger.ZERO
                } ?: continue
                val isDefinitelyEmpty = cond.lenVars.any {
                    (state.boundsAnalysis[it]?.tryResolveAsInt() as? QualifiedInt)?.x?.takeIf(IntValue::isConstant)?.c == BigInteger.ZERO
                }
                val elemSize = elem.arrayPtr.mapNotNull {
                    pointerAnalysis.getElementSize(it, pState = state.pointsToState)
                }.toSet().singleOrNull()
                if(elemSize == null) {
                    // if all input pointers are empty, yolo
                    if(elem.arrayPtr.any {
                        state.pointsToState.store[it]?.tryResolvePointer()?.let {
                            it is Pointer.ArrayPointer && it.v.all {
                                it is ArrayAbstractLocation.EMPTY_ARRAY || it is ArrayAbstractLocation.StructAlias
                            }
                        } != true
                    }) {
                        continue
                    }
                } else if(!isDefinitelyEmpty && cond.assumedSize != elemSize) {
                    continue
                }
                /* then we are copying to scratch (no input bounds) and we have that the sizes match,
                   and the length variable is the logical length of the array being copied from.
                 */
                if(lenQualifiers.any {
                        it is IntQualifier.LengthOfArray && it.arrayVar in elem.arrayPtr
                    }) {
                    return LoopSummaryClassification.Valid(
                        input = PointerType.ElementPointer(it),
                        output = cond.outPtr.first(isScratch)
                    )
                }
            }
            // Check the other case, is this a "fake" array that must be empty?
            /* 1.b */
            if(cond.inPtr.all {
                state.boundsAnalysis[it]?.tryResolve()?.let { it as? QualifiedInt }?.qual?.contains(IntQualifier.EmptyDataSegment) == true
            } && cond.lenVars.any {
                state.boundsAnalysis[it]?.expectInt()?.x?.let {
                    it.isConstant && it.c == BigInteger.ZERO
                } == true
            }) {
                return LoopSummaryClassification.Valid(
                    output = cond.outPtr.first(isScratch),
                    input = PointerType.EmptyArray,
                )
            } else {
                return LoopSummaryClassification.Invalid
            }
        /* case 2
           Output is a packed byte array; no need for output bounds checking, it is within bounds by definition
         */
        } else if (cond.outPtr.all {
                state.pointsToState.store[it].let {
                    it is InitializationPointer.ByteInitPointer &&
                        it.initAddr.sort is AllocationAnalysis.Alloc.PackedByteArray
                }
            }) {
            // now check the validity of the source pointer
            return if (cond.inPtr.any {
                    state.pointsToState.store[it] !is ScratchPointer
                }) {
                // 2.a
                if(cond.inPtr.all {
                    state.pointsToState.store[it] is Pointer.ArrayElemStart &&
                        state.arrayState[it]?.let { it as? ArrayStateAnalysis.Value.ElementPointer }?.arrayPtr?.let { basePointer ->
                            lenQualifiers.any {
                                it is IntQualifier.LengthOfArray && it.arrayVar in basePointer
                            }
                        } == true
                }) {
                    LoopSummaryClassification.Valid(
                        output = cond.outPtr.first(),
                        input = PointerType.ElementPointer(
                            cond.inPtr.first()
                        )
                    )
                } else {
                    LoopSummaryClassification.Invalid
                }
            } else {

                // 2.b
                LoopSummaryClassification.Valid(
                    output = cond.outPtr.first(),
                    input = PointerType.ElementPointer(
                        cond.inPtr.first()
                    )
                )
            }
        /* case 3
           Beginning of an initializing array
         */
        } else if (cond.outPtr.any {
                (state.pointsToState.store[it] is InitializationPointer.ByteInitPointer ||
                    state.pointsToState.store[it] is InitializationPointer.ArrayInitPointer) &&
                    state.arrayState[it]?.let { it as? ArrayStateAnalysis.Value.ElementPointer }?.let {
                        it.index == IntValue.Constant(BigInteger.ZERO)
                    } == true
            }) {
            /* then we are copying to an initializing address.
               Check that the array size is consistent with the stride amount.
             */
            outputCheck@for(it in cond.outPtr) {
                val st = state.pointsToState.store[it] as InitializationPointer
                val sz = when (st) {
                    is InitializationPointer.ByteInitPointer -> BigInteger.ONE
                    is InitializationPointer.ArrayInitPointer -> {
                        when (val sort = st.initAddr.sort) {
                            is AllocationAnalysis.Alloc.DynamicBlock -> sort.eSz
                            else -> BigInteger.ONE
                        }
                    }
                    else -> continue@outputCheck
                }
                if (sz != BigInteger.ONE && sz != cond.assumedSize) {
                    continue
                }
                val basePointers = (state.arrayState[it] as? ArrayStateAnalysis.Value.ElementPointer)?.arrayPtr ?: continue
                if (!lenQualifiers.any {
                        it is IntQualifier.LengthOfArray && it.arrayVar in basePointers
                    }) {
                    continue
                }
                /*
                  We have verified that the size stride matches, and the length of the loop being copied is the length of the input
                  now check for source pointer validity.

                  Copying from scratch or call data are trivially within bounds
                 */
                if (cond.sourceMap == TACKeyword.CALLDATA.toVar() || cond.inPtr.any(isScratch)) {
                    return LoopSummaryClassification.Valid(
                        output = it,
                        input = if(cond.sourceMap == TACKeyword.CALLDATA.toVar()) {
                            PointerType.Other
                        } else {
                            PointerType.ElementPointer(cond.inPtr.first(isScratch))
                        }
                    )
                } else {
                    /*
                      For any input pointers, if we have that
                      input + (t1 + t2 + ...) < array_end

                      Are any of the ti terms the size of the element segment for the output array? Then any loop that
                      consumes the element segment length bytes from the input array will still be in bounds
                     */
                    for(inPtr in cond.inPtr) {
                        val data = (state.arrayState[inPtr] as? ArrayStateAnalysis.Value.EndTracking)?.takeIf {
                            it is ArrayStateAnalysis.WithIndexVars
                        }
                        val inPtrElem = data?.untilEnd ?: continue
                        check(data is ArrayStateAnalysis.WithIndexVars)
                        if(inPtrElem.any {
                                it.ops.any {
                                    (state.boundsAnalysis[it] as? QualifiedInt)?.qual?.any {
                                        it is IntQualifier.SizeOfElementSegment && it.arrayVar in basePointers
                                    } == true
                                }
                            }) {
                            return LoopSummaryClassification.Valid(
                                output = it,
                                input = PointerType.ElementPointer(state.arrayState.firstNotNullOfOrNull { (k, v) ->
                                    if(v is ArrayStateAnalysis.Value.ElementPointer && v.arrayPtr.containsAny(data.arrayPtr)) {
                                        k
                                    } else {
                                        null
                                    }
                                } ?: return LoopSummaryClassification.Invalid)
                            )
                        }
                    }
                    // if that didn't work...
                    val elementProofs = state.arrayState.mapNotNull {
                        it.key `to?` (it.value as? ArrayStateAnalysis.Value.ElementPointer)
                    }.filter {
                        it.second.untilEnd.any {
                            it.ops.any {
                                state.boundsAnalysis[it]?.let {
                                    it as? QualifiedInt
                                }?.qual?.any {
                                    it is IntQualifier.SizeOfArrayBlock && it.arrayVar in basePointers
                                } == true
                            }
                        }
                    }.filter { (base, _) ->
                        cond.inPtr.any { inP ->
                            state.invariants implies {
                                !base + EVM_WORD_SIZE `=` !inP
                            }
                        }
                    }
                    if(elementProofs.isNotEmpty()) {
                        return LoopSummaryClassification.Valid(
                            output = it,
                            input = PointerType.ElementPointer(elementProofs.first().first)
                        )
                    }
                }
            }
            // fall through: false
            return LoopSummaryClassification.Invalid
        }
        return LoopSummaryClassification.Invalid
    }

    private val tyVarManager = TypeVariableManager()
    private val numericAnalysis = NumericAnalysis(
        mustAliasAnalysis = if(Config.EnabledEqualitySaturation.get()) {
            graph.cache.gvn
        } else {
            object : IMustEqualsAnalysis {
                override fun equivBefore(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
                    return setOf(f)
                }

                override fun equivAfter(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
                    return setOf(f)
                }
            }
        },
        typeVariableManager = tyVarManager,
        scratchSites = scratchSite.keys,
        allocSites = allocSites,
        relaxedAddition = relaxedSemantics
    )

    internal val pointerAnalysis = PointerSemantics(
        typeVariableManager = tyVarManager,
        scratchSite = scratchSite.keys,
        allocSites = allocSites,
        numericAnalysis = numericAnalysis,
        graph = graph,
        relaxedAddition = relaxedSemantics,
        initialFreePointerValue = initialFreePointerValue,
        allowLengthUpdates = allowLengthUpdates
    )

    private val arrayAnalysis = ArrayStateAnalysis(
        mustEqualsAnalysis = if(Config.EnabledEqualitySaturation.get()) {
            graph.cache.gvn
        } else {
            object : IMustEqualsAnalysis {
                override fun equivBefore(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
                    return setOf(f)
                }

                override fun equivAfter(cmd: CmdPointer, f: TACSymbol.Var): Set<TACSymbol.Var> {
                    return setOf(f)
                }
            }
        },
        arrayAllocSites = allocSites.filterValues{
            val loc = it.sort
            loc !is AllocationAnalysis.Alloc.ConstBlock
        },
        scratchSites = scratchSite,
        typeVariableManager = tyVarManager,
        relaxedSemantics = relaxedSemantics
    )

    private val structAnalysis = StructStateAnalysis(allocSites, numericAnalysis, pointerAnalysis, arrayAnalysis, relaxedSemantics)
    private val objectPathAnalysis = ObjectPathAnalysis()
    internal val decoderAnalysis: IDecoderAnalysis
    internal val encoderAnalysis : IEncoderAnalysis
    init {
        if(Config.EnabledABIAnalysis.get()) {
            val d = DecoderAnalysis(
                pointerSem = pointerAnalysis,
                numericAnalysis = numericAnalysis,
                methodInfo = method?.evmExternalMethodInfo
            )
            decoderAnalysis = d
            encoderAnalysis = EncoderAnalysis(
                pointerSem = pointerAnalysis,
                numeric = numericAnalysis,
                allocSites = allocSites,
                scratchSites = scratchSite,
                decoderAnalysis = d,
                objectPath = objectPathAnalysis,
                graph = graph
            )
        } else {
            decoderAnalysis = object : IDecoderAnalysis {
                override fun isDecoderLengthRead(loc: TACSymbol, pState: PointsToDomain): Boolean {
                    return false
                }

                override fun getElementSize(
                    calldataArrayVar: TACSymbol.Var,
                    decoderState: DecoderAnalysis.State
                ): BigInteger? {
                    return null
                }

                override fun step(command: LTACCmd, s_: PointsToDomain): DecoderAnalysis.State {
                    return s_.decoderState
                }

                override fun consumePath(
                    path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>,
                    decoderState: DecoderAnalysis.State,
                    s: PointsToDomain
                ): DecoderAnalysis.State {
                    return decoderState
                }

                override fun collectReferenced(
                    decoderState: DecoderAnalysis.State,
                    referencedFromLive: MutableSet<TACSymbol.Var>,
                    lva: Set<TACSymbol.Var>
                ) { }

                override fun startBlock(
                    decoderState: DecoderAnalysis.State,
                    lva: Set<TACSymbol.Var>,
                    referencedFromLive: Set<TACSymbol.Var>
                ): DecoderAnalysis.State {
                    return decoderState
                }

                override fun endBlock(
                    decoderState: DecoderAnalysis.State,
                    last: LTACCmd,
                    live: Set<TACSymbol.Var>
                ): DecoderAnalysis.State {
                    return decoderState
                }

                override fun consumeLoopSummary(
                    decoderState: DecoderAnalysis.State,
                    s: PointsToDomain,
                    s1: LoopCopyAnalysis.LoopCopySummary
                ): DecoderAnalysis.State {
                    return decoderState
                }

                override fun interpolate(
                    prev: PointsToDomain,
                    next: PointsToDomain,
                    summary: Map<TACSymbol.Var, TACExpr>
                ): DecoderAnalysis.State {
                    return next.decoderState
                }

                override fun popAllocation(
                    decoderState: DecoderAnalysis.State,
                    s: PointsToDomain
                ): Pair<DecoderAnalysis.State, DecoderAnalysis.BufferDecodeResult?> {
                    return decoderState to null
                }

                override fun getReferencedPrimitivePath(
                    v: TACSymbol,
                    whole: PointsToDomain
                ): IDecoderAnalysis.DirectCalldataRead? {
                    return null
                }

                override fun kill(
                    d_: DecoderAnalysis.State,
                    killedBySideEffects: Set<TACSymbol.Var>
                ): DecoderAnalysis.State {
                    return d_
                }

                override fun isDynamicOffset(v: TACSymbol.Var, whole: PointsToDomain): Boolean {
                    return false
                }

            }
            encoderAnalysis = object : IEncoderAnalysis {
                override fun collectReferenced(
                    encoderState: EncoderAnalysis.State,
                    referencedFromLive: MutableSet<TACSymbol.Var>,
                    lva: Set<TACSymbol.Var>
                ) {

                }

                override fun consumeLoopSummary(
                    encoderState: EncoderAnalysis.State,
                    ppNextState: PointsToDomain,
                    s: LoopCopyAnalysis.LoopCopySummary,
                    finalCmd: LTACCmd
                ): EncoderAnalysis.State {
                    return encoderState
                }

                override fun startBlock(
                    encoderState: EncoderAnalysis.State,
                    lva: Set<TACSymbol.Var>,
                    referencedFromLive: Set<TACSymbol.Var>
                ): EncoderAnalysis.State {
                    return encoderState
                }

                override fun endBlock(
                    encoderState: EncoderAnalysis.State,
                    last: LTACCmd,
                    live: Set<TACSymbol.Var>
                ): EncoderAnalysis.State {
                    return encoderState
                }

                override fun interpolate(
                    prev: PointsToDomain,
                    next: PointsToDomain,
                    summary: Map<TACSymbol.Var, TACExpr>
                ): EncoderAnalysis.State {
                    ptaInvariant(next.encoderState == prev.encoderState) {
                        "Non-trivial states for encoder analysis at interpolation ${next.encoderState} vs ${prev.encoderState}"
                    }
                    return next.encoderState
                }

                override fun finalizeBuffer(
                    encoderState: EncoderAnalysis.State,
                    conv: List<ConversionHints>,
                    s: PointsToDomain,
                    where: LTACCmd
                ): EncoderAnalysis.State {
                    return encoderState
                }

                override fun step(command: LTACCmd, s_: PointsToDomain): EncoderAnalysis.State {
                    return s_.encoderState
                }

                override fun join(
                    encoderState: EncoderAnalysis.State,
                    thisContext: PointsToDomain,
                    otherState: EncoderAnalysis.State,
                    otherContext: PointsToDomain
                ): EncoderAnalysis.State {
                    ptaInvariant(encoderState == otherState) { "Should have trivial domains" }
                    return encoderState
                }

                override val encodeCompletePoints: Map<CmdPointer, IEncoderAnalysis.EncodeCompletePoint>
                    get() = mapOf()

                override fun toEncodedValue(
                    topLevelWrite: EncoderAnalysis.TopLevelWrite,
                    whole: PointsToDomain
                ): TopLevelValue {
                    throw UnsupportedOperationException("Should never have generated an instance of TLW $topLevelWrite " +
                            "with dummy implementation")
                }

                override fun kill(
                    e_: EncoderAnalysis.State,
                    killedBySideEffects: Set<TACSymbol.Var>
                ): EncoderAnalysis.State {
                    return e_
                }
            }
        }
    }

    init {
        numericAnalysis.setDecoderAnalysis(decoderAnalysis)
    }

    private val _invalidSummaries = mutableSetOf<NBId>()
    val invalidSummaries : Set<NBId> get() = _invalidSummaries

    private val _takenEdges = mutableSetOf<Pair<NBId, NBId>>()
    val takenEdges: Set<Pair<NBId, NBId>> get() = _takenEdges

    init {
        numericAnalysis.setPointsToAnalysis(pointerAnalysis)
        arrayAnalysis.numericAnalysis = numericAnalysis
        arrayAnalysis.pointerAnalysis = pointerAnalysis
        numericAnalysis.setArrayStateAnalysis(arrayAnalysis)
        pointerAnalysis.arrayStateAnalysis = arrayAnalysis
    }

    private val revertBlocks = RevertBlockAnalysis.findRevertBlocks(graph)

    private fun toSynthetic(it: List<InternalFuncRet>, sig: List<VMTypeDescriptor>) : SyntheticAlloc {
        val toBuild = mutableListOf<Pair<TACSymbol.Var, EVMTypeDescriptor>>()
        var varIt = 0
        @Suppress("UseWithIndex")
        for(retType in sig) {
            ptaInvariant(retType is EVMTypeDescriptor) {
                "Analyzing evm bytecode, but have a non-evm type?"
            }
            if(retType is EVMTypeDescriptor.EVMReferenceType) {
                ptaInvariant(retType.location == EVMLocationSpecifier.memory) {
                    "Cannot summarize a function that returns calldata, this inlining will fail at summarization time!"
                }
            }
            ptaInvariant(retType !is EVMTypeDescriptor.EVMMappingDescriptor && retType !is EVMTypeDescriptor.FunctionDescriptor) {
                "type $retType somehow appeared in an internal function we are summarizing"
            }
            ptaInvariant(varIt < it.size) {
                "Ran out of output variables for return types"
            }
            toBuild.add(it[varIt++].s to retType)
        }
        return toBuild
    }


    private val simpleLoopSummary = mutableMapOf<Loop, Map<TACSymbol.Var, TACExpr>>()
    private val loops = getNaturalLoops(graph)

    private fun LinearEquality.isSpurious(): Boolean {
        // these invariants imply a value is negative. This is never right and is due to linear equality not understanding overflow
        val singleValue = term.singleOrNull()?.value
        return singleValue != null && k != BigInteger.ZERO && (k < BigInteger.ZERO) == (singleValue < BigInteger.ZERO)
    }

    private fun LinearInvariants.filterSpurious(): LinearInvariants {
        // Why do we do the `containsAny` check here?  Isn't `removeAll` just going to do the same checks?  Yes, but
        // `containsAny` is significantly faster, because it doesn't have to deal with the possibility of producing a
        // new set, and it turns out that we will almost never actually call `removeAll` here, so doing the
        // `containsAny` check first saves a lot of time.
        return if (this.containsAny { it.isSpurious() }) {
            this.removeAll { it.isSpurious() }
        } else {
            this
        }
    }

    private fun <T> propagateNumericCondition(
        s: PointsToDomain,
        cond: T,
        dst: NBId,
        f: (NumericDomain, TACSymbol.Var, PointsToDomain, LTACCmd) -> NumericPathInformation
    ) : Pair<NBId, PointsToDomain>? where T: PathCondition, T: PathCondition.ConditionalOn {
        val (n_,hints, path) = try {
            f(s.boundsAnalysis, cond.v, s, graph.elab(dst).commands.first())
        } catch(_: PruneInfeasible) {
            return null
        }
        val (pts, nullablePointerHints) = pointerAnalysis.consumePath(path, s.pointsToState, hints, s)
        val (arr, convHints) = arrayAnalysis.consumePath(
            path = path,
            preConvArrayState = s.arrayState,
            s = s,
            arrayConversionHints = nullablePointerHints.filterIsInstance<ConversionHints.Array>()
        )
        val (structState, blockConv) = structAnalysis.consumePath(
            path = path,
            structState = s.structState,
            pts = pts,
            numeric = n_,
            structConversionHints = convHints.filterIsInstance<ConversionHints.Block>(),
            inv = s.invariants
        )
        val newPointers = convHints.map {
            ConversionHints.Array(it.elemPointer)
        } + blockConv.map {
            ConversionHints.Block(it.block)
        } + nullablePointerHints
        val saturatedInv = path.flatMap { (v, se) ->
            se.asSequence().filterIsInstance<PathInformation.StrictEquality>().filter {
                it.sym != null
            }.map {
                v to it.sym!!
            }.asIterable()
        }.let {
            s.invariants.propagateEquality(it) { v ->
                (s.boundsAnalysis[v] as? QualifiedInt)?.x?.takeIf { it.isConstant }?.c
            }
        }.filterSpurious()
        val impliedConstants = saturatedInv matches {
            v("variable") { p ->
                (p is LVar.PVar) && s.boundsAnalysis[p.v] is QualifiedInt
            } `=` k("const")
        }
        val saturatedNumeric = impliedConstants.map {
            (it.symbols["variable"] as LVar.PVar).v to it.factors["const"]!!
        }.let {
            numericAnalysis.propagateConstants(n_, it)
        }
        return dst to PointsToDomain(
            boundsAnalysis = numericAnalysis.consumeConversion(saturatedNumeric, newPointers),
            pointsToState = pointerAnalysis.consumeExternalSafetyProofs(pts, convHints, blockConv),
            arrayState = arr,
            structState = structState,
            decoderState = decoderAnalysis.consumePath(path, s.decoderState, s),
            objectPath = s.objectPath,
            encoderState = s.encoderState,
            invariants = saturatedInv
        )
    }

    private val alwaysLive = setOf(
        TACKeyword.RETURNCODE.toVar(),
        TACKeyword.RETURN_SIZE.toVar()
    )

    /*
    This is the core of the analysis. At each command within each block, we transform the state according to the
    semantics of the sub-analyses. At the end, we propagate path information into the domains as necessary.
     */
    private fun transformBlock(block: NBId, reducedState: PointsToResult, states: Map<NBId, PointsToResult>) : Map<NBId, PointsToResult> {
        if(block in revertBlocks) {
            return mapOf()
        }
        fun continueError(e: PointsToFailures) = graph.succ(block).associateWith { e }
        fun continueError(e: PointsToAnalysisFailedException) = continueError(PointsToFailures(e))
        if (reducedState !is PointsToDomain) {
            return continueError(reducedState as PointsToFailures)
        }

        val thisBlock = graph.elab(block)
        try {
            val lva = graph.cache.lva.liveVariablesBefore(thisBlock.commands.first().ptr) + alwaysLive

            val referencedFromLive = mutableSetOf<TACSymbol.Var>()
            numericAnalysis.collectReferenced(reducedState.boundsAnalysis, referencedFromLive, lva)
            arrayAnalysis.collectReferenced(reducedState.arrayState, referencedFromLive, lva)
            decoderAnalysis.collectReferenced(reducedState.decoderState, referencedFromLive, lva)
            objectPathAnalysis.collectReferenced(reducedState.objectPath, referencedFromLive, lva)
            encoderAnalysis.collectReferenced(reducedState.encoderState, referencedFromLive, lva)
            structAnalysis.collectReferenced(reducedState.structState, referencedFromLive, lva)
            LinearInvariantSemantics.collectReferenced(reducedState.invariants, referencedFromLive, lva)

            var stateIt = PointsToDomain(
                boundsAnalysis = numericAnalysis.startBlock(reducedState.boundsAnalysis, lva, referencedFromLive),
                arrayState = arrayAnalysis.startBlock(reducedState.arrayState, lva, referencedFromLive),
                pointsToState = pointerAnalysis.startBlock(reducedState.pointsToState, lva, referencedFromLive),
                decoderState = decoderAnalysis.startBlock(reducedState.decoderState, lva, referencedFromLive),
                encoderState = encoderAnalysis.startBlock(reducedState.encoderState, lva, referencedFromLive),
                objectPath = objectPathAnalysis.startBlock(reducedState.objectPath, lva, referencedFromLive),
                structState = structAnalysis.startBlock(reducedState.structState, lva, referencedFromLive),
                invariants = LinearInvariantSemantics.startBlock(reducedState.invariants, lva, referencedFromLive),
            )

            decoderLogger.trace {
                "At block $block, in ${graph.name} decoder state ${stateIt.decoderState}"
            }
            encoderLogger.trace {
                "At block $block, in ${graph.name} encoder state ${stateIt.encoderState.encoding}"
            }

            for(command in thisBlock.commands) {
                logger.trace {
                    "At $command, stepping $stateIt"
                }
                if (!isSyncStates(stateIt,  command)) {
                    logger.warn { "fell out of sync at $command $stateIt ${explainSync(stateIt, command)}" }
                    logger.debug { stateIt.toString() }
                    logger.debug { "Block dump" }
                    for(i in thisBlock.commands) {
                        logger.debug { i.toString() }
                    }
                    throw PointsToAnalysisFailedException("Inconsistency in reduced product", command)
                }
                try {
                    val preS = stateIt
                    stateIt = transformCommand(command, preS, nextContext = thisBlock.commands.getOrNull(command.ptr.pos + 1))
                    logger.trace { "After stepping $command, at state $stateIt" }
                } catch(_: PruneInfeasible) {
                    return mapOf()
                } catch(x: Exception) {
                    logger.warn(x) { "While stepping $command" }
                    logger.debug { stateIt.toString() }
                    logger.debug { "Block dump" }
                    for(i in thisBlock.commands) {
                        logger.debug { i.toString() }
                    }
                    throw PointsToAnalysisFailedException("While stepped abstract states", where = command, t = x)
                }
            }
            val finalCmd = thisBlock.commands.last()
            stateIt = try {
                val live = graph.cache.lva.liveVariablesAfter(finalCmd.ptr)
                PointsToDomain(
                    boundsAnalysis = numericAnalysis.endBlock(stateIt.boundsAnalysis, thisBlock.commands.last(), live),
                    arrayState = arrayAnalysis.endBlock(stateIt.arrayState, thisBlock.commands.last(), live),
                    pointsToState = pointerAnalysis.endBlock(stateIt.pointsToState, thisBlock.commands.last(), live),
                    decoderState = decoderAnalysis.endBlock(stateIt.decoderState, thisBlock.commands.last(), live),
                    objectPath = objectPathAnalysis.endBlock(stateIt.objectPath, thisBlock.commands.last(), live),
                    encoderState = encoderAnalysis.endBlock(stateIt.encoderState, thisBlock.commands.last(), live),
                    structState = structAnalysis.endBlock(stateIt.structState, thisBlock.commands.last(), live),
                    invariants = LinearInvariantSemantics.endBlock(
                        invariants = stateIt.invariants, thisBlock.commands.last(), live
                    ),
                )
            } catch(x: Exception) {
                logger.warn { "While propagating end block of $block, with $stateIt" }
                throw PointsToAnalysisFailedException("Saturating at end block of $block", finalCmd, x)
            }
            return graph.pathConditionsOf(block).filter {
                it.key !in revertBlocks
            }.mapNotNull { (dst, cond) ->
                /**
                * Before propagating to the state of the next block, preprocess the state according to the first command.
                *
                * XXX(jtoman): it's unclear whether it's better to do this before the path condition propagation or
                * after, or whether that even matters. It is relatively easy to shift this into an operation that is applied on the
                * (non-null) result of the following when.
                */
                val dstCmd = graph.elab(dst).commands.first()
                val ppNextState = preprocessNext(nxt = dstCmd, p = stateIt)
                try {
                    when (cond) {
                        PathCondition.TRUE -> dst to ppNextState
                        is PathCondition.EqZero -> {
                            /*
                            Check whether this path is even feasible...
                            */
                            if (!numericAnalysis.canBeFalse(ppNextState.boundsAnalysis, cond.v, finalCmd)) {
                                null
                            } else {
                                propagateNumericCondition(ppNextState, dst = dst, cond = cond, f = numericAnalysis::propagateFalse)
                            }
                        }
                        is PathCondition.NonZero -> {
                            /* again, check for feasibility */
                            if (!numericAnalysis.canBeTrue(ppNextState.boundsAnalysis, cond.v, finalCmd)) {
                                null
                            } else {
                                /*
                                Propagate path information. In this case, we can sometimes infer information about
                                values in the pointer analysis; in particular, if we know a variable x is within bounds for
                                some array a, we know a must not be empty.
                                */
                                propagateNumericCondition(ppNextState, dst = dst, cond = cond, f = numericAnalysis::propagateTrue)
                            }
                        }
                        is PathCondition.Summary -> {
                            if(cond.s is LoopCopyAnalysis.LoopCopySummary) {
                                val isValid = isLoopSummaryValid(cond.s, ppNextState)
                                if (isValid) {
                                    if (dst == cond.s.skipTarget) {
                                        dst to ppNextState.copy(
                                            pointsToState = pointerAnalysis.consumeLoopSummary(cond.s.valueSummary, ppNextState.pointsToState, ppNextState, cond.s.outPtr),
                                            decoderState = decoderAnalysis.consumeLoopSummary(ppNextState.decoderState, ppNextState, cond.s),
                                            encoderState = encoderAnalysis.consumeLoopSummary(ppNextState.encoderState, ppNextState, cond.s, finalCmd),
                                            boundsAnalysis = numericAnalysis.consumeLoopSummary(cond.s.valueSummary, ppNextState.boundsAnalysis, ppNextState),
                                            arrayState = arrayAnalysis.consumeLoopSummary(cond.s.valueSummary, ppNextState.arrayState, ppNextState)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    _invalidSummaries.add(block)
                                    if (dst == cond.s.skipTarget) {
                                        null
                                    } else {
                                        dst to ppNextState
                                    }
                                }
                            } else if(cond.s is InitAnnotation.FourByteWriteSummary) {
                                if (dst == cond.s.skipTarget) {
                                    dst to ppNextState
                                } else {
                                    null
                                }
                            } else if(cond.s is ExternalMapGetterSummarization.ExternalGetterHash) {
                                val validSumm = ppNextState.pointsToState.store[cond.s.keyArray]?.let {
                                    it is Pointer.ArrayPointer && it.v.all {
                                        it is ArrayAbstractLocation.A &&
                                            it.addr.getElementSize()?.toConcrete() == BigInteger.ONE
                                    }
                                } == true
                                if(!validSumm) {
                                    _invalidSummaries.add(block)
                                }
                                if(validSumm && dst == cond.s.skipTarget) {
                                    dst to ppNextState
                                } else if(!validSumm && dst == cond.s.originalBlockStart) {
                                    dst to ppNextState
                                } else {
                                    null
                                }
                            } else if(cond.s is BytesKeyHash) {
                                if(dst == cond.s.skipTarget) {
                                    dst to ppNextState
                                } else {
                                    null
                                }
                            } else {
                                dst to ppNextState
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Propagating with $cond to $dst: $ppNextState"
                    }
                    throw PointsToAnalysisFailedException(
                        "While propagating along edge from $block to $dst with cond $cond",
                        dstCmd,
                        e
                    )
                }
            }.map { (dst, nextState) ->
                val currLoopHeads = loops.mapNotNull {
                    it.head.takeIf { _ ->
                        block in it.body
                    }
                }
                val isBackJump = dst in currLoopHeads
                val enteringLoop = loops.singleOrNull {
                    dst == it.head && block !in it.body
                }
                if(isBackJump) {
                    val prevState = states[dst]
                    ptaInvariant(prevState != null && prevState is PointsToDomain) {
                        "Jumping back to $dst which should have dominated us, but state is $prevState"
                    }
                    val loopEffect = simpleLoopSummary.computeIfAbsent(loops.singleOrNull {
                        it.head == dst
                    } ?: return@map dst to nextState) { l ->
                        SimpleLoopSummarization.summarizeLoop(graph, l)
                    }

                    val (sState, structConv) = structAnalysis.interpolate(
                        prev = prevState,
                        next = nextState,
                        summary = loopEffect
                    )
                    val encoderState = encoderAnalysis.interpolate(
                        prev = prevState,
                        next = nextState,
                        summary = loopEffect
                    )
                    val decoderState = decoderAnalysis.interpolate(
                        prev = prevState,
                        next = nextState,
                        summary = loopEffect
                    )
                    dst to nextState.copy(
                        arrayState = arrayAnalysis.interpolate(
                            prev = prevState,
                            next = nextState,
                            summary = loopEffect
                        ),
                        structState = sState,
                        pointsToState = if (structConv.isNotEmpty()) {
                            pointerAnalysis.consumeExternalSafetyProofs(
                                state = nextState.pointsToState,
                                blockConv = structConv,
                                convHints = listOf()
                            )
                        } else {
                            nextState.pointsToState
                        },
                        boundsAnalysis = if (structConv.isNotEmpty()) {
                            numericAnalysis.consumeConversion(nextState.boundsAnalysis, structConv.map {
                                ConversionHints.Block(it.block)
                            })
                        } else {
                            nextState.boundsAnalysis
                        },
                        encoderState = encoderState,
                        decoderState = decoderState
                    )
                } else if(enteringLoop != null) {
                    val loopBody = loops.singleOrNull {
                        it.head == enteringLoop.head
                    }?.body ?: return@map (dst to nextState)
                    val mutatedVars = mutableSetOf<TACSymbol.Var>()
                    val liveAtHead = graph.cache.lva.liveVariablesBefore(CmdPointer(enteringLoop.head, 0))
                    for(b in loopBody) {
                        graph.elab(b).commands.mapNotNullTo(mutatedVars) {
                            it.cmd.getLhs()?.takeIf {
                                it.tag == Tag.Bit256 && it in liveAtHead
                            }
                        }
                    }
                    // can we guess some invariants between variables that are maybe striding together?
                    // that is, we have:
                    /*
                    x = 0
                    y = 32

                    x += K
                    y += K

                    then we could guess this. There is no harm in guessing a "wrong" invariant, if it's not invariant
                    it won't be preserved across the loop iteration and will quickly be killed.
                    */
                    val constAtLoopEntry = mutatedVars.mapNotNull {
                        it `to?` (nextState.boundsAnalysis[it] as? QualifiedInt)?.x?.takeIf { it.isConstant }?.c
                    }

                    /*
                    * Find variables x that are mutated within the loop where there exists some variable y, that is NOT
                    * mutated within the loop and `x = y` at the entrance to the loop.
                    *
                    * For each such pairs, `x = y` and `z = w` (where x and z are both mutated), we want to try to guess
                    * that `z` and `x` stride together w.r.t. their starting points, that is, it will be a loop invariant that:
                    * x - y == z - w
                    */
                    val mutatedWithEqualStart = mutatedVars.mapNotNull { mv ->
                        mv `to?` (nextState.invariants matches {
                            mv `=` v("other") { lv ->
                                lv is LVar.PVar && lv.v !in mutatedVars
                            }
                        }).map {
                            (it.symbols["other"] as LVar.PVar).v
                        }.takeIf { it.isNotEmpty() }
                    }
                    /*
                    * We can't generate invariants because we don't have enough pairs, or, in the case of "striding together",
                    * we can't track the invariants of the size required.
                    */
                    if(constAtLoopEntry.size < 2 && (mutatedWithEqualStart.size < 2 || Config.LinearInvariantBound.get() < 4)) {
                        return@map dst to nextState
                    }
                    // generate only binary relations, should be fiiine (famous last words)
                    val freshInvariants = mutableSetOf<LinearEquality>()
                    for((x1,c1) in constAtLoopEntry) {
                        for((x2,c2) in constAtLoopEntry) {
                            if(x1 == x2) {
                                continue
                            }
                            /*
                            that is, coming into the loop, we know that
                            x1 == c1 and x2 == c2, therefore we can guess that
                            x1 - x2 = c1 - c2 as our invariant, which gives:
                            x1 - x2 - (c1 - c2) = 0
                            */
                            freshInvariants.add(LinearEquality(
                                treapMapOf(
                                    LVar.PVar(x1) to BigInteger.ONE,
                                    LVar.PVar(x2) to -BigInteger.ONE
                                ),
                                -(c1 - c2)
                            ).canonicalize())
                        }
                    }
                    /*
                    now try to guess variables that stride together, that is, for
                    two variables `z` and `x` within the loop, their difference w.r.t their starting values
                    is always the same. Put another way, `z` and `x` are always incremented by the same amount. This is tracked
                    by looking at variables `w` and `y` which are not mutated within the loop and to which `z` and `x` are known to
                    be equal at the start of the loop respectively. Then we generate the invariant `z - w == x - y`, and see
                    if it is invariant.

                    This is *very* noisy, and requires the linear invariant bound to be set to at least 4
                    */
                    for((v1, pv1) in mutatedWithEqualStart) {
                        for((v2, pv2) in mutatedWithEqualStart) {
                            if(v1 == v2) {
                                continue
                            }
                            for(base1 in pv1) {
                                for(base2 in pv2) {
                                    freshInvariants.add(LinearEquality.build {
                                        (!v1 - !base1) `=` (!v2 - !base2)
                                    }.canonicalize())
                                }
                            }
                        }
                    }
                    dst to nextState.copy(
                        invariants = nextState.invariants + freshInvariants
                    )
                } else {
                    dst to nextState
                }
            }.toMap().also {
                for(nxt in it.keys) {
                    _takenEdges.add(block to nxt)
                }
            }
        } catch (x: PointsToAnalysisFailedException) {
            return continueError(x)
        } catch (x: Exception) {
            return continueError(
                PointsToAnalysisFailedException("While transforming block $block", thisBlock.commands.first(), x)
            )
        }
    }

    private fun isLoopSummaryValid(cond: LoopCopyAnalysis.LoopCopySummary, state: PointsToDomain): Boolean {
        return validateSummary(state, cond) is LoopSummaryClassification.Valid
    }

    private fun explainSync(s: PointsToDomain, where: LTACCmd): String {
        val lv = lva.liveVariablesBefore(where.ptr)
        if(s.boundsAnalysis.keys.filter(lv::contains).toSet() != s.pointsToState.store.keys.filter(lv::contains).toSet()) {
            return "Domain mismatch: ${s.pointsToState.store.keys - s.boundsAnalysis.keys} ${s.boundsAnalysis.keys - s.pointsToState.store.keys}"
        } else {
            for ((k, v) in s.boundsAnalysis.entries) {
                if(!isSaneType(s, k, v)) {
                    return "Type mismatch for $k $v vs ${s.pointsToState.store[k]!!}"
                }
            }
            `impossible!`
        }
    }

    private fun isSyncStates(s: PointsToDomain, where: LTACCmd): Boolean {
        val lv = lva.liveVariablesBefore(where.ptr)
        val live = s.boundsAnalysis.keys intersect lv
        return live == (s.pointsToState.store.keys intersect lv) && !live.containsAny {
            !isSaneType(s, it, s.boundsAnalysis[it]!!.tryResolve())
        }
    }

    private fun isSaneType(s: PointsToDomain, k: TACSymbol.Var,
                           v: IntDomain): Boolean {
        val otherValue = s.pointsToState.store[k]!!.tryResolve()
        return when (v) {
            is ANY_POINTER -> otherValue is ReferenceValue
            is QualifiedInt -> otherValue is INT || otherValue is NullablePointer
            is UnresolvedValue -> otherValue is UnkPointsTo && v.tyVar.isUnifiedWith(otherValue.tyVar)
        }
    }

    private var summarySequence = 0
    private val summaryNumbering = mutableMapOf<CmdPointer, Int>()
    fun summaryIdFor(p: CmdPointer) = summaryNumbering.computeIfAbsent(p) {
        summarySequence++
    }

    /**
     * Transform the state [s] at [command]. [nextContext], if non-null, means that the next command in the block
     * following [command] is [nextContext] (used for preprocessing) prestates.
     */
    private fun transformCommand(command: LTACCmd, s: PointsToDomain, nextContext: LTACCmd?): PointsToDomain {
        if(command.cmd is TACCmd.Simple.CallCore) {
            encoderLogger.info { "At call core $command, encoder state is ${s.encoderState.encoding}" }
        }

        fun PointsToDomain.andPp() = if(nextContext == null) {
            this
        } else {
            preprocessNext(this, nextContext)
        }

        command.snarrowOrNull<InternalCallSummary>()?.let {
            val syntheticAlloc = toSynthetic(
                it.internalExits, it.signature.resType
            )
            val pointerUpdate = pointerAnalysis.synthesizeState(
                s.pointsToState, syntheticAlloc, summaryIdFor(command.ptr)
            )
            val numericUpdate = numericAnalysis.synthesizeState(
                s.boundsAnalysis, syntheticAlloc
            )
            val arrayUpdate = arrayAnalysis.synthesizeState(
                s.arrayState, syntheticAlloc
            )
            val structUpdate = structAnalysis.synthesizeState(
                s.structState, syntheticAlloc, numericUpdate
            )
            val retVars = it.internalExits.mapToTreapSet { it.s }
            return PointsToDomain(
                arrayState = arrayUpdate,
                boundsAnalysis = numericUpdate,
                decoderState = decoderAnalysis.kill(s.decoderState, retVars),
                encoderState = encoderAnalysis.kill(s.encoderState, retVars),
                invariants = s.invariants.killAll(retVars),
                objectPath = objectPathAnalysis.kill(s.objectPath, PointerSemantics.WriteSideEffect.killValues(retVars)),
                pointsToState = pointerUpdate,
                structState = structUpdate
            ).andPp()
        }

        val s_ = if(command.cmd is TACCmd.Simple.AnnotationCmd && command.cmd.check(POP_ALLOCATION) { true }) {
            if(s.encoderState.encoding != null) {
                encoderLogger.info {
                    "At pop at $command, encoder state is ${s.encoderState.encoding}"
                }
            }
            val (pst, conv)  = pointerAnalysis.popAllocation(s.pointsToState, s)
            val bnd = numericAnalysis.consumeConversion(s.boundsAnalysis, conv)
            val array = arrayAnalysis.consumeConversion(s.arrayState, conv, s, pst)
            val (decoder, _) = decoderAnalysis.popAllocation(s.decoderState, s)
            val encoder = encoderAnalysis.finalizeBuffer(s.encoderState, conv, s, where = command)
            val structState = structAnalysis.consumeConversion(s.structState, conv, s)
            PointsToDomain(
                boundsAnalysis = bnd,
                pointsToState = pst,
                arrayState = array,
                structState = structState,
                decoderState = decoder,
                objectPath = s.objectPath,
                encoderState = encoder,
                invariants = s.invariants,
            )
        } else {
            command.maybeAnnotation(InitAnnotation.ExpectBoundedWrite.META_KEY)?.let {
                if (!pointerAnalysis.verifyBoundedWrite(it.v, s)) {
                    throw PointsToAnalysisFailedException(
                        "At $command, could not verify that the write within initialization window was a provably bounded write",
                        where = command,
                        fatal = true
                    )
                } else {
                    s
                }
            } ?: s
        }
        if (command.cmd.meta.containsKey(TACMeta.NON_ALIASED_LENGTH_READ)) {
            check(command.cmd is TACCmd.Simple.AssigningCmd.ByteLoad)
            if(!pointerAnalysis.verifyNonAliasInitTop(command.narrow<TACCmd.Simple.AssigningCmd.ByteLoad>(), s_)) {
                throw PointsToAnalysisFailedException(
                    "At $command, we expected that the location would not alias with the active init pointer",
                    where = command,
                    fatal = true
                )
            }
        }
        val killedBySideEffects = pointerAnalysis.getKillSideEffects(ltacCmd = command, pState = s)
        /*
        Both analyses use the MemoryStepper, which exposes the step() method. The memory stepper
        abstracts the process of branching on the bytecode, and provides overrideable hooks for implementing
        the "key" semantics.
         */
        val p_ = pointerAnalysis.step(command, s_)
        val n_ = numericAnalysis.step(command, s_)
        val d_ = decoderAnalysis.step(command, s_)
        val e_ = encoderAnalysis.step(command, s_)
        val o_ = objectPathAnalysis.step(command, s_)
        val a_ = arrayAnalysis.step(command, s_)
        val struct_ = structAnalysis.step(command, s_)
        val i_ = LinearInvariantSemantics.step(command, s_).filterSpurious()

        return PointsToDomain(
            pointsToState = p_,
            boundsAnalysis = n_,
            arrayState = a_,
            structState = struct_,
            decoderState = d_,
            encoderState = e_,
            invariants = i_,
            objectPath = o_,
        ).andKillSideEffects(killedBySideEffects).andPp()
    }

    /**
     * Preprocess [p] to prepare the prestate for the command [nxt] (currently handles preallocation
     * of concrete cells for the pointer analysis).
     */
    private fun preprocessNext(p: PointsToDomain, nxt: LTACCmd) : PointsToDomain {
        val (st, dump) = pointerAnalysis.preprocess(p.pointsToState, p, nxt)
        return if(!dump) {
            p.copy(pointsToState = st)
        } else {
            PointsToDomain(
                arrayState = treapMapOf(),
                pointsToState = st,
                objectPath = treapMapOf(),
                encoderState = EncoderAnalysis.State.empty,
                decoderState = DecoderAnalysis.State.empty,
                invariants = p.invariants,
                structState = treapMapOf(),
                boundsAnalysis = p.boundsAnalysis.retainAll {
                    it.value !is ANY_POINTER
                }
            )
        }
    }

    private fun PointsToDomain.andKillSideEffects(
        killedBySideEffects: PointerSemantics.WriteSideEffect
    ) : PointsToDomain {
        return if(killedBySideEffects.isEmpty()) {
            this
        } else {
            PointsToDomain(
                pointsToState = pointerAnalysis.kill(this.pointsToState, killedBySideEffects),
                boundsAnalysis = numericAnalysis.kill(this.boundsAnalysis, killedBySideEffects),
                arrayState = arrayAnalysis.kill(this.arrayState, killedBySideEffects),
                decoderState = decoderAnalysis.kill(this.decoderState, killedBySideEffects.killValue),
                encoderState = encoderAnalysis.kill(this.encoderState, killedBySideEffects.killValue),
                objectPath = objectPathAnalysis.kill(this.objectPath, killedBySideEffects),
                structState = structAnalysis.kill(this.structState, killedBySideEffects.killValue),
                invariants = this.invariants.killAll(killedBySideEffects.killValue),
            )
        }
    }

    private val JOIN_LIMIT = 3

    val results: Map<CmdPointer, PointsToDomain>
    val failures: List<PointsToAnalysisFailedException>

    init {
        val joinCount = mutableMapOf<NBId, Int>()
        val state = mutableMapOf<NBId, PointsToResult>()
        graph.rootBlocks.map { it.id }.forEach {
            state[it] = PointsToDomain(
                boundsAnalysis = NumericAnalysis.initial,
                pointsToState = PointerSemantics.initial,
                arrayState = treapMapOf(),
                structState = treapMapOf(),
                decoderState = DecoderAnalysis.State.empty,
                encoderState = EncoderAnalysis.State.empty,
                objectPath = treapMapOf(),
                invariants = treapSetOf(),
            )
            joinCount[it] = 0
        }

        val results = mutableMapOf<CmdPointer, PointsToDomain>()
        var failures = PointsToFailures()

        val failureAnnot = graph.commands.find {
            it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.k == INIT_FAILURE
        }
        if (failureAnnot != null) {
            failures = PointsToFailures("Found failure annotation, refusing to analyze", where = failureAnnot)
        } else {
            (object : StatefulWorklistIteration<NBId, Unit, String?>() {
                override fun reduce(results: List<Unit>): String? = null

                override val scheduler: IWorklistScheduler<NBId> = graph.cache.naturalBlockScheduler

                /**
                * After an upper bound operation in the pointer analysis "kills" pointer variables, adjust those variables
                * in the numeric domain to be ints as well. This is necessary as the numeric analysis models pointers
                * coarsely using ANY_POINTER, the join of which with another ANY_POINTER always yields an ANY_POINTER.
                * If the points to analysis decides instead that the variable should be modelled with an INT and this is NOT
                * communicate to the numeric domain, we get a reduced product mismatch reported in [isSyncStates]
                */
                private fun NumericDomain.postProcess(toKill: Collection<TACSymbol.Var>) = this.mapValues { (k, v) ->
                    if(k in toKill) {
                        QualifiedInt(IntValue.Nondet, treapSetOf())
                    } else {
                        v
                    }
                }

                /**
                * Compute an upper bound (in lattice theoretic terms) between the [PointsToGraph] of this
                * and the [PointsToGraph] of [other], according to the upper bound operator [ub].
                *
                * If [ub] is a widening operator, then [other] is assumed to be the "next" state for interpolation
                * (and indeed this is how the function is called). In addition, this function returns the set of
                * pointer variables that were killed by the upper bound, e.g., the join of an ArrayElem and BlockPointer
                * conservatively goes to INT (aka top). This is used to post-process the numeric domain state using [postProcess]
                * above.
                */
                private fun PointsToDomain.pointerUpperBound(
                    other: PointsToDomain,
                    ub: (PointsToGraph, PointsToGraph, PointsToDomain, PointsToDomain) -> PointsToGraph
                ) : Pair<PointsToGraph, Collection<TACSymbol.Var>> {
                    val sup = ub(this.pointsToState, other.pointsToState, this, other)
                    return sup to sup.store.keysMatching { k, v ->
                        v is INT && (this.pointsToState.store[k] is ReferenceValue &&
                            other.pointsToState.store[k] is ReferenceValue)
                    }
                }

                override fun process(it: NBId): StepResult<NBId, Unit, String?> {
                    val res = transformBlock(it, state[it]!!, state)
                    val cont = mutableListOf<NBId>()
                    for((k, v) in res) {
                        if(graph.pred(k).singleOrNull() == it) {
                            // lone predecessor, assuming this transfer function is monotone, no need  to join
                            val curr = state[k]
                            state[k] = v
                            if(curr != v) {
                                cont.add(k)
                            }
                            continue
                        }
                        val s = state[k]
                        if (s == null) {
                            state[k] = v
                            joinCount[k] = 0
                            cont.add(k)
                        } else if (v !is PointsToDomain || s !is PointsToDomain) {
                            val joined = (v as? PointsToFailures).orEmpty() + (s as? PointsToFailures).orEmpty()
                            if (joined != s) {
                                state[k] = joined
                                cont.add(k)
                            }
                        } else if (
                            v.pointsToState.h != PointerSemantics.empty.h &&
                            s.pointsToState.h != PointerSemantics.empty.h &&
                            v.pointsToState.h.concreteSpace.isEmpty() != s.pointsToState.h.concreteSpace.isEmpty()
                        ) {
                            logger.debug { "Predecessor concrete mode mismatch at $k\n$it: ${v.pointsToState.h}\n$k: ${s.pointsToState.h}" }
                            state[k] = PointsToFailures("Predecessor concrete mode mismatch", graph.elab(k).commands.first())
                            cont.add(k)
                        } else if(joinCount[k]!! > JOIN_LIMIT) {
                            val widen = try {
                                val (pts, mismatched) = s.pointerUpperBound(v, PointsToGraph::widen)
                                PointsToDomain(
                                    pointsToState = pts,
                                    boundsAnalysis = s.boundsAnalysis.widen(v.boundsAnalysis, s, v).postProcess(mismatched),
                                    arrayState = s.arrayState.widen(v.arrayState),
                                    structState = s.structState.join(v.structState, s.pointsToState, v.pointsToState),
                                    decoderState = s.decoderState.join(v.decoderState, s, v),
                                    objectPath = s.objectPath.join(v.objectPath),
                                    encoderState = encoderAnalysis.join(s.encoderState, s, v.encoderState, v),
                                    invariants = s.invariants.fastJoin(v.invariants),
                                )
                            } catch(x: Exception) {
                                logger.warn {
                                    "Widening ${v.boundsAnalysis} with ${s.boundsAnalysis}"
                                }
                                logger.warn {
                                    "Joining ${v.pointsToState} with ${s.pointsToState}"
                                }
                                PointsToFailures(
                                    "While widening the result at successor $k after processing $it",
                                    graph.elab(k).commands.first(),
                                    x
                                )
                            }
                            if(widen != s) {
                                state[k] = widen
                                cont.add(k)
                            }
                        } else {
                            val joined = try {
                                val (pts, mismatched) = s.pointerUpperBound(v, PointsToGraph::join)
                                PointsToDomain(
                                    pointsToState = pts,
                                    boundsAnalysis = s.boundsAnalysis.join(v.boundsAnalysis, s, v).postProcess(mismatched),
                                    arrayState = s.arrayState.join(v.arrayState),
                                    structState = s.structState.join(v.structState, s.pointsToState, v.pointsToState),
                                    decoderState = s.decoderState.join(v.decoderState, s, v),
                                    encoderState = encoderAnalysis.join(s.encoderState, s, v.encoderState, v),
                                    objectPath = s.objectPath.join(v.objectPath),
                                    invariants = s.invariants.fastJoin(v.invariants),
                                )
                            } catch(x: Exception) {
                                logger.warn(x) {
                                    "During join at $k after processing $it"
                                }
                                logger.warn {
                                    "PTS Left: ${s.pointsToState}"
                                }
                                logger.warn {
                                    "PTS Right: ${v.pointsToState}"
                                }
                                logger.warn {
                                    "Bounds Left: ${s.boundsAnalysis}"
                                }
                                logger.warn {
                                    "Bounds Right: ${v.boundsAnalysis}"
                                }
                                PointsToFailures(
                                    "While joining the result at $k after processing $it",
                                    graph.elab(k).commands.first(),
                                    x
                                )
                            }
                            joinCount[k] = joinCount[k]!! + 1
                            if(joined != s) {
                                state[k] = joined
                                cont.add(k)
                            }
                        }
                    }
                    return this.cont(cont)
                }

            }).submit(graph.rootBlocks.map { it.id })

            state.forEachEntry { (k, v) ->
                if(k !in revertBlocks) {
                    when (v) {
                        is PointsToFailures -> failures += v
                        is PointsToDomain -> {
                            val tacBlock = graph.elab(k)
                            val start = tacBlock.commands.first().ptr
                            var cmdState = preprocessNext(v, tacBlock.commands.first())
                            results += start to cmdState
                            for(cmd in tacBlock.commands) {
                                if (cmdState.containsFatalError()) {
                                    return@forEachEntry
                                }
                                results += cmd.ptr to cmdState
                                try {
                                    cmdState = transformCommand(cmd, cmdState, tacBlock.commands.getOrNull(cmd.ptr.pos + 1))
                                } catch(x: Exception) {
                                    val e = x as? PointsToAnalysisFailedException
                                        ?: PointsToAnalysisFailedException("While stepping $cmd", cmd, x)
                                    logger.warn(e) { "While stepping $cmd: $cmdState" }
                                    failures += PointsToFailures(e)
                                    break
                                }
                            }
                        }
                    }
                }
            }

            /**
             * Finalize the unification in the concrete allocation manager
             */
            pointerAnalysis.finalizeAnalysis()
        }

        this.results = results
        this.failures = failures.failures.values.toList()
    }

    internal fun isDynamicOffset(v: TACSymbol.Var, where: LTACCmd) : Boolean {
        return results[where.ptr]?.let {
            decoderAnalysis.isDynamicOffset(v, it)
        } ?: false
    }

    /**
     * Function to construct an exception of type [PointsToAnalysisFailedException] - before constructing,
     * the methods computes the internal function the [LTACCmd] 'where' may be located within (via [getContainingInternalFuncStart])
     */
    private fun PointsToAnalysisFailedException(message: String, where: LTACCmd, t: Throwable? = null, fatal: Boolean = t != null && t !is AnalysisFailureException) = PointsToAnalysisFailedException(message, getContainingInternalFuncStart(where.ptr, graph), where, t, fatal)

    /**
     * Function to construct the failures of type [PointsToFailures] - before constructing,
     * the methods computes the internal function the [LTACCmd] 'where' may be located within (via [getContainingInternalFuncStart])
     */
    private fun PointsToFailures(message: String, where: LTACCmd, t: Throwable? = null) = PointsToFailures(message, getContainingInternalFuncStart(where.ptr, graph), where, t)
}
