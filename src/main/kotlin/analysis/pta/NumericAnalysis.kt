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
import analysis.numeric.*
import analysis.numeric.PathInformation.*
import analysis.numeric.linear.*
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.TermMatching.matchesAny
import analysis.pta.abi.DecoderAnalysis
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_BITWIDTH256
import evm.EVM_WORD_SIZE
import evm.MAX_EVM_UINT256
import log.Logger
import log.LoggerTypes
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import vc.data.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.POINTS_TO)

typealias NumericDomain = TreapMap<TACSymbol.Var, IntDomain>

private fun QualifiedInt.isMultipleOf(isModulo: BigInteger): Boolean {
    return isModulo > BigInteger.ZERO && (isModulo == BigInteger.ONE || ((x.isConstant && x.c.mod(isModulo) == BigInteger.ZERO) || qual.contains(
        IntQualifier.MultipleOf(isModulo)
    )))
}

sealed class IntDomain {
    abstract fun tryResolve(): IntDomain

    abstract fun tryResolveAsInt(): ResolvedIntDomain
    abstract fun tryResolveAsPointer(): ResolvedIntDomain

    abstract fun expectInt(): QualifiedInt
    abstract fun expectPointer(): ANY_POINTER
    abstract fun join(thisContext: PointsToDomain, j2: IntDomain, otherContext: PointsToDomain): IntDomain

    abstract fun widen(prevContext: PointsToDomain, next: IntDomain, nextContext: PointsToDomain): IntDomain
}

sealed class ResolvedIntDomain : IntDomain() {
    override fun tryResolveAsInt(): ResolvedIntDomain = this
    override fun tryResolveAsPointer(): ResolvedIntDomain = this
}

data class UnresolvedValue(val tyVar: TypeVariable) : IntDomain() {
    override fun tryResolve(): IntDomain = this.tyVar.ifResolved(IntValue.Constant(0x60.toBigInteger()).liftToBounded(), ANY_POINTER)
            ?: this

    override fun tryResolveAsInt(): ResolvedIntDomain = this.expectInt()
    override fun tryResolveAsPointer(): ResolvedIntDomain = this.expectPointer()

    override fun expectInt(): QualifiedInt = tyVar.expectInt(
            IntValue.Constant(0x60.toBigInteger()).liftToBounded())

    override fun expectPointer(): ANY_POINTER = tyVar.expectPointer(ANY_POINTER)
    override fun join(thisContext: PointsToDomain, j2: IntDomain, otherContext: PointsToDomain): IntDomain {
        return if (j2 is UnresolvedValue) {
            assert(!j2.tyVar.isResolved())
            this.tyVar.unify(j2.tyVar)
            return this
        } else {
            j2.join(otherContext, this, thisContext)
        }
    }

    override fun widen(prevContext: PointsToDomain, next: IntDomain, nextContext: PointsToDomain): IntDomain {
        assert(!this.tyVar.isResolved())
        return if (next is UnresolvedValue) {
            assert(!next.tyVar.isResolved())
            this.tyVar.unify(next.tyVar)
            this
        } else if (next is ANY_POINTER) {
            this.expectPointer()
        } else {
            expectInt().widen(prevContext, next, nextContext)
        }
    }
}

private val MAX_UINT = MAX_EVM_UINT256

private val UINT_BITWIDTH = EVM_BITWIDTH256.toBigInteger()


private fun IntValue.liftToBounded() = QualifiedInt(this, qual = treapSetOf())

data class NumericPathInformation(
        val state: NumericDomain,
        val arrayHints: List<ArrayHints>,
        val pathInformation: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>
)

object ANY_POINTER : ResolvedIntDomain() {
    override fun tryResolve(): IntDomain = this

    override fun expectInt(): QualifiedInt {
        logger.warn { "We somehow got a pointer where we expected an int" }
        throw AnalysisFailureException()
    }

    override fun expectPointer(): ANY_POINTER = this
    override fun join(thisContext: PointsToDomain, j2: IntDomain, otherContext: PointsToDomain): IntDomain {
        return when (j2) {
            ANY_POINTER -> this
            is QualifiedInt -> AnyInt
            is UnresolvedValue -> {
                assert(!j2.tyVar.isResolved())
                j2.tyVar.expectPointer(Unit)
                return this
            }
        }
    }

    override fun widen(prevContext: PointsToDomain, next: IntDomain, nextContext: PointsToDomain): IntDomain {
        return when (next) {
            is UnresolvedValue -> {
                assert(!next.tyVar.isResolved())
                next.expectPointer()
            }
            is ANY_POINTER -> {
                this
            }
            is QualifiedInt -> {
                AnyInt.widen(prevContext, next, nextContext)
            }
        }
    }
}

private val AnyInt = IntValue.Nondet.liftToBounded()

data class QualifiedInt(override val x: IntValue, override val qual: TreapSet<IntQualifier>) :
        ResolvedIntDomain(), analysis.numeric.QualifiedInt<QualifiedInt, IntValue, IntQualifier>, WithUIntApproximation<IntValue> {

    override fun tryResolve(): IntDomain = this
    override fun expectInt(): QualifiedInt = this

    override fun expectPointer(): ANY_POINTER = throw AnalysisFailureException("int occurred in a context where a pointer was expected")
    override fun join(thisContext: PointsToDomain, j2: IntDomain, otherContext: PointsToDomain): IntDomain =
            when (val j2_ = j2.tryResolve()) {
                ANY_POINTER -> AnyInt
                is QualifiedInt -> {
                    val q = qualifierJoin(thisContext, j2_, otherContext)
                    QualifiedInt(
                            this.x.join(j2_.x),
                            q.build()
                    )
                }
                is UnresolvedValue -> {
                    ptaInvariant(!j2_.tyVar.isResolved()) {
                        "Failed joing $this and $j2_"
                    }
                    this.join(thisContext, j2_.expectInt(), otherContext)
                }
            }

    private fun qualifierJoin(
        thisContext: PointsToDomain,
        j2_: QualifiedInt,
        otherContext: PointsToDomain
    ): TreapSet.Builder<IntQualifier> {
        val q = treapSetBuilderOf<IntQualifier>()
        saturateQualifiers(this, thisContext, j2_, otherContext, q)
        saturateQualifiers(j2_, otherContext, this, thisContext, q)
        return q
    }

    private fun saturateQualifiers(
        j1: QualifiedInt,
        j1Context: PointsToDomain,
        j2: QualifiedInt,
        j2Context: PointsToDomain,
        q: TreapSet.Builder<IntQualifier>
    ) {
        if(j1.x.mustEqual(BigInteger.ZERO) && j2.x.mustNotEqual(BigInteger.ZERO)) {
            for((p, v) in j2Context.pointsToState.store) {
                if(v is BasePointer<*> && j1Context.boundsAnalysis[p]?.let {
                    it as? QualifiedInt
                    }?.x?.mustEqual(BigInteger.ZERO) == true) {
                    q.add(IntQualifier.NullabilityFlagFor(p))
                }
            }
        }
        for (q1 in j1.qual) {
            if (q1 in j2.qual) {
                q.add(q1)
            } else if (q1 is IntQualifier.MultipleOf && j2.x.isConstant && j2.x.c.mod(q1.factor) == BigInteger.ZERO) {
                q.add(q1)
            } else if((q1 is IntQualifier.ValidIndexOf || (q1 is IntQualifier.ElementOffsetFor && j1Context.pointsToState.store[q1.arrayVar]?.let {
                        it as? Pointer.ArrayPointer
                    }?.let {
                        it.v.filterIsInstance<ArrayAbstractLocation.A>().monadicMap {
                            j1Context.pointsToState.h[it]?.block?.let {
                                it is ArrayBlock.ByteArray
                            }
                        }?.uniqueOrNull()
                    } == true)) && j2Context.arrayState[(q1 as ArrayAnnot).arrayVar]?.let {
                        it as? ArrayStateAnalysis.Value.ArrayBasePointer
                    }?.logicalLength?.let {
                        j2.x.ub < it.lb
                    } == true) {
                q.add(q1)
            } else if(q1 is IntQualifier.ModularUpperBound &&
                ((j2.x.isConstant && !q1.strong && j2.x.c == BigInteger.ZERO) ||
                    (j2.isMultipleOf(q1.modulus) && (j2Context.boundsAnalysis[q1.other] as? QualifiedInt)?.x?.lb?.let {
                        (q1.strong && j2.x.ub < it) || (!q1.strong && j2.x.ub <= it)
                    } == true))) {
                q.add(q1)
            } else if(q1 is IntQualifier.NullabilityFlagFor) {
                if(j2.x.mustEqual(BigInteger.ZERO) && j2Context.boundsAnalysis[q1.pointerVar]?.let {
                    it as? QualifiedInt
                    }?.x?.mustEqual(BigInteger.ZERO) == true) {
                    q.add(q1)
                } else if(j2.x.mustNotEqual(BigInteger.ZERO) && j2Context.pointsToState.store[q1.pointerVar] is BasePointer<*>) {
                    q.add(q1)
                }
            }
        }
    }

    override fun widen(prevContext: PointsToDomain, next: IntDomain, nextContext: PointsToDomain): IntDomain {
        return when (next) {
            ANY_POINTER -> AnyInt
            is QualifiedInt -> {
                QualifiedInt(
                        this.x.widen(next.x),
                        qualifierJoin(prevContext, next, nextContext).build()
                )
            }
            is UnresolvedValue -> {
                assert(!next.tyVar.isResolved())
                this.widen(prevContext, next.expectInt(), nextContext)
            }
        }
    }

    operator fun plus(q: Iterable<IntQualifier>) : QualifiedInt = this.copy(qual = this.qual + q)
    override fun withQualifiers(x: Iterable<IntQualifier>): QualifiedInt {
        return this.copy(qual = x.toTreapSet())
    }
}

private val arithSemantics = ArithmeticSemantics(
        isPointerP = { v: IntDomain ->
            v is ANY_POINTER || (v is UnresolvedValue && v.tyVar.isResolved() && !v.tyVar.resolvedInt())
        },
        lazyHandler = { v: IntDomain ->
            v.tryResolveAsInt()
        },
        pointerArithResult = AnyInt,
        vecSemantics = AnyInt
)

fun NumericDomain.binaryOp(other: NumericDomain, thisContext: PointsToDomain, otherContext: PointsToDomain, f: (IntDomain, PointsToDomain, IntDomain, PointsToDomain) -> IntDomain): NumericDomain {
    val toReturn = mutableMapOf<TACSymbol.Var, IntDomain>()
    val seen = mutableSetOf<TACSymbol.Var>()
    for ((k, v) in this.entries) {
        seen.add(k)
        val j1 = v.tryResolve()
        val j2 = other[k]?.tryResolve() ?: AnyInt
        toReturn[k] = f(j1, thisContext, j2, otherContext)
    }

    for ((k, v) in other.entries) {
        if (k in seen) {
            continue
        }
        toReturn[k] = f(AnyInt, thisContext, v, otherContext)
    }
    return toReturn.toTreapMap()

}

fun NumericDomain.join(other: NumericDomain, leftContext: PointsToDomain, rightContext: PointsToDomain): NumericDomain = this.binaryOp(other, leftContext, rightContext, IntDomain::join)

fun NumericDomain.widen(other: NumericDomain, leftContext: PointsToDomain, rightContext: PointsToDomain): NumericDomain = this.binaryOp(other, leftContext, rightContext, IntDomain::widen)

class NumericAnalysis(
    private val mustAliasAnalysis: IMustEqualsAnalysis,
    override val typeVariableManager: TypeVariableManager,
    scratchSites: Set<CmdPointer>,
    allocSites: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    val relaxedAddition: Boolean
) : MemoryStepper<PointsToDomain, NumericDomain>(scratchSites, allocSites), INumericInformation, SymInterpreter<NumericDomain, IntDomain> {

    fun PointsToDomain.interp(p: TACSymbol, where: LTACCmd) = this.boundsAnalysis.interp(p, where)

    fun PointsToDomain.interpAsInt(p: TACSymbol, ltacCmd: LTACCmd) = this.interp(p, ltacCmd).expectInt()

    private fun NumericDomain.interpAsInt(p: TACSymbol, where: LTACCmd): QualifiedInt = this.interp(p, where).expectInt()

    override fun interpAsConstant(pState: PointsToDomain, ltacCmd: LTACCmd, value: TACSymbol): BigInteger? =
            pState.interp(value, ltacCmd).let {
                when {
                    it is UnresolvedValue -> it.tyVar.expectInt(0x60.toBigInteger())
                    it is QualifiedInt && it.x.isConstant -> it.x.c
                    else -> null
                }
            }

    override fun interpAsUnambiguousConstant(pState: PointsToDomain, ltacCmd: LTACCmd, value: TACSymbol): BigInteger? {
        return when(value) {
            is TACSymbol.Const -> if(0x60.toBigInteger() == value.value) {
                null
            } else {
                value.value
            }
            is TACSymbol.Var -> pState.boundsAnalysis[value]?.let {
                if(it is UnresolvedValue && it.tyVar.isResolved() && it.tyVar.resolvedInt()) {
                    0x60.toBigInteger()
                } else if(it is QualifiedInt && it.x.isConstant) {
                    it.x.c
                } else {
                    null
                }
            }
        }
    }

    override fun isSafeArrayOffsetFor(pstate: PointsToDomain, where: LTACCmd, op1: TACSymbol, op2: TACSymbol): Boolean {
        return checkArraySafety(pstate, where, op1, op2, IntQualifier.SafeArrayOffset::class.java)
    }

    private fun <T : ArraySafetyAnnot> checkArraySafety(pstate: PointsToDomain, where: LTACCmd, op1: TACSymbol, op2: TACSymbol, kls: Class<T>): Boolean {
        if (op1 !is TACSymbol.Var) {
            return false
        }
        if (op2 !is TACSymbol.Var) {
            return false
        }
        val arrayPointerRefs = equivAt(where, op1)
        return equivAt(where, op2).mapNotNull { pstate.boundsAnalysis[it] }.filterIsInstance(QualifiedInt::class.java).any { i ->
            i.qual.filterIsInstance(kls).any {
                it.arrayVar in arrayPointerRefs
            }
        }
    }

    private lateinit var arrayStateAnalysis: ArrayStateAnalysis

    fun setArrayStateAnalysis(v: ArrayStateAnalysis) {
        arrayStateAnalysis = v
    }

    private lateinit var pointerAnalysis: IPointerInformation

    fun setPointsToAnalysis(v: IPointerInformation) {
        pointerAnalysis = v
    }

    private lateinit var decoderAnalysis: IDecoderAnalysis

    fun setDecoderAnalysis(dec: IDecoderAnalysis) {
        this.decoderAnalysis = dec
    }

    override fun handleUninterpWrite(value: TACSymbol, target: NumericDomain, pState: PointsToDomain,
                                     ltacCmd: LTACCmd) {
    }

    override fun handleByteCopy(dstOffset: TACSymbol, length: TACSymbol, target: NumericDomain, pState: PointsToDomain,
                                ltacCmd: LTACCmd): NumericDomain = target



    override fun readMemory(lhs: TACSymbol.Var, loc: TACSymbol, target: NumericDomain, pState: PointsToDomain,
                            ltacCmd: LTACCmd): NumericDomain {
        val ty = pointerAnalysis.getReadType(loc, ltacCmd, pState)
        val isLengthRead = pointerAnalysis.isLengthRead(loc, ltacCmd, pState)
        val arrayAllocLengthRead = pointerAnalysis.lengthForActiveAllocs(loc, ltacCmd, pState)
        val lhsValue = when (ty) {
            HeapType.Int -> {
                if (isLengthRead) {
                    val i = if (pointerAnalysis.isEmptyArray(loc, ltacCmd, pState)) {
                        IntValue.Constant(BigInteger.ZERO)
                    } else {
                        pState.arrayState[loc]?.tryResolve()?.let { it as? ArrayStateAnalysis.Value.ArrayBasePointer }?.logicalLength ?: IntValue.Nondet
                    }
                    val qual = treapSetBuilderOf<IntQualifier>()
                    val arrayElemSize = (loc as? TACSymbol.Var)?.let { pointerAnalysis.getElementSize(it, pState.pointsToState) }
                    for(eq in equivAt(ltacCmd, loc as TACSymbol.Var).minus(lhs)) {
                        qual.add(IntQualifier.LengthOfArray(eq))
                        if(arrayElemSize == BigInteger.ONE) {
                            qual.add(IntQualifier.SizeOfElementSegment(eq))
                        }
                    }
                    QualifiedInt(x = i, qual = qual.build())
                } else if(loc is TACSymbol.Var && pointerAnalysis.isEmptyStringArrayRead(loc, pState)) {
                    QualifiedInt(IntValue.Constant(BigInteger.ZERO), qual = treapSetOf())
                } else if(arrayAllocLengthRead.isNotEmpty()) {
                    QualifiedInt(IntValue.Nondet, qual = arrayAllocLengthRead.mapToTreapSet {
                        IntQualifier.LengthOfArray(it)
                    })
                } else if(decoderAnalysis.isDecoderLengthRead(loc, pState)) {
                    calldataLengthRead(pState, loc)
                } else if(loc is TACSymbol.Const && pState.pointsToState.h.concreteSpace.isUnallocedZeroRead(IntValue.Constant(loc.value))) {
                    // this attempts to capture when unallocated/uninitialized memory is read, which can be assumed by the compiler to contain zero
                    QualifiedInt(IntValue.Constant(BigInteger.ZERO), qual = treapSetOf())
                } else {
                    interpAsConstant(pState = pState, ltacCmd = ltacCmd, value = loc)?.let {
                        pState.pointsToState.h.concreteSpace.valueAbstractionAt(it)?.let {
                            QualifiedInt(
                                x = it, qual = treapSetOf()
                            )
                        }
                    } ?: AnyInt
                }
            }
            is HeapType.TVar -> {
                assert(!ty.ty.isResolved())
                UnresolvedValue(ty.ty)
            }
            else -> {
                ANY_POINTER
            }
        }
        return target.updateLoc(lhs, lhsValue)
    }

    private fun calldataLengthRead(pState: PointsToDomain, loc: TACSymbol): QualifiedInt {
        return QualifiedInt(pState.decoderState.qualifiers[loc]?.let {
            it as? DecoderAnalysis.AbstractDomain.BufferIndices.DynamicStart
        }?.lengthBound ?: IntValue.Nondet, qual = treapSetOf(IntQualifier.LengthOfCalldataArray(
                calldataArrayVar = loc as TACSymbol.Var
        ))
        )
    }

    override fun writeMemory(loc: TACSymbol, value: TACSymbol, target: NumericDomain, pState: PointsToDomain,
                             ltacCmd: LTACCmd): NumericDomain {
        if (!pointerAnalysis.isLengthWrite(loc, ltacCmd, pState)) {
            return target
        }
        ptaInvariant(loc is TACSymbol.Var) {
            "$loc is an array but is not a variable?"
        }
        if (value !is TACSymbol.Var) {
            ptaInvariant(value is TACSymbol.Const) {
                "Expected $value to be a constant"
            }
            return target
        }
        val v = pState.boundsAnalysis[value]?.expectInt() ?: IntValue.Nondet.liftToBounded()
        val arraySize = pointerAnalysis.getElementSize(loc, pState.pointsToState)
        val toAdd = v.qual.builder()
        toAdd.add(
                IntQualifier.LengthOfArray(loc)
        )
        if(arraySize != null && arraySize == BigInteger.ONE) {
            toAdd.add(IntQualifier.SizeOfElementSegment(loc))
        }
        val ext = mutableMapOf<TACSymbol.Var, QualifiedInt>()
        ext[value] = v.copy(qual = toAdd.build())
        if(arraySize != null) {
            val invCanon = pState.invariants.canonicalize()
            for (k in target.keys) {
                if (k == value) {
                    continue
                }
                val newQuals = mutableSetOf<IntQualifier>()
                if (invCanon alreadyCanonImplies {
                            !k `=` (!value * arraySize) + EVM_WORD_SIZE
                        }) {
                    newQuals.add(IntQualifier.SizeOfArrayBlock(loc))
                }
                if(invCanon alreadyCanonImplies {
                            !k `=` (!value * arraySize)
                        }) {
                    newQuals.add(IntQualifier.SizeOfElementSegment(loc))
                }
                if(newQuals.isNotEmpty()) {
                    ext.compute(k) { _, q ->
                        val toExt = q ?: target[k]?.let {
                            it as? QualifiedInt
                        } ?: return@compute null
                        toExt.withQualifiers(newQuals)
                    }
                }
            }
        }
        return target + ext
    }

    private val qualifierManager = object : QualifierManager<IntQualifier, QualifiedInt, NumericDomain>(mustAliasAnalysis) {
        override fun mapValues(s: NumericDomain, mapper: (TACSymbol.Var, QualifiedInt) -> QualifiedInt): NumericDomain {
            return s.mapValues { (k, v) ->
                if(v is QualifiedInt) {
                    mapper(k, v)
                } else {
                    v
                }
            }
        }

        override fun assignVar(toStep: NumericDomain, lhs: TACSymbol.Var, toWrite: QualifiedInt, where: CmdPointer): NumericDomain {
            return toStep + (lhs to toWrite)
        }

    }

    override fun assignUninterpExpr(lhs: TACSymbol.Var, rhs: Set<TACSymbol>, target: NumericDomain,
                                    pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        if(where.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && where.cmd.base == TACKeyword.CALLDATA.toVar()) {
            if(decoderAnalysis.isDecoderLengthRead(where.cmd.loc, pstate)) {
                return calldataLengthRead(pState = pstate, loc = where.cmd.loc).let {
                    assignResult(it, where, target, lhs)
                }
            }
        }
        return rhs.map { target.interp(it, where) }.toTypedArray().let { args ->
            arithSemantics({ _, _ -> AnyInt }, *args)
        }.let { b ->
            assignResult(b, where, target, lhs)
        }
    }

    override fun assignShiftLeft(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                                 pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return binaryOperation(op1, op2, pstate, where, { a, b ->
            if (b > UINT_BITWIDTH) {
                BigInteger.ZERO
            } else {
                a.shiftLeft(b.toInt()) and MAX_UINT
            }
        }) { a, b ->
            thisSemantics.evalShiftLeft(a, op1, b, op2, target, pstate.boundsAnalysis, pstate, where.narrow())
        }.let { res ->
            assignResult(res, where, target, c)
        }
    }

    override fun assignOr(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                          pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return binaryOperation(op1, op2, pstate, where, BigInteger::or) { i1, i2 ->
            thisSemantics.evalBWOr(i1, op1, i2, op2, target, pstate.boundsAnalysis, pstate, where.narrow())
        }.let { b ->
            assignResult(b, where, target, c)
        }
    }

    private fun binaryOperation(op1: TACSymbol, op2: TACSymbol, pstate: PointsToDomain, where: LTACCmd,
                                constSemantics: ((BigInteger, BigInteger) -> BigInteger)?,
                                binSem: (QualifiedInt, QualifiedInt) -> QualifiedInt): QualifiedInt =
            pstate.boundsAnalysis.interp(op1, where).let { av1 ->
                pstate.boundsAnalysis.interp(op2, where).let { av2 ->
                    arithSemantics.invoke({ i1, i2 ->
                        val x = i1.expectInt()
                        val y = i2.expectInt()
                        if (x.x.isConstant && y.x.isConstant && constSemantics != null) {
                            val constResult = constSemantics(x.x.c, y.x.c)
                            if (constResult > MAX_UINT || constResult < BigInteger.ZERO) {
                                AnyInt
                            } else {
                                QualifiedInt(x = IntValue.Constant(constResult), qual = treapSetOf())
                            }
                        } else {
                            binSem(x, y)
                        }
                    }, av1, av2)
                }
            }


    /*
    Returns a deduced upper bound on the value of op. We can prove that the value of this
    must be less than OR EQUAL to the returned value.
     */
    private fun QualifiedInt.inducedUpperBound(): BigInteger {
        return this.x.getUpperBound()
    }

    override fun assignAnd(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                           pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return binaryOperation(op1, op2, pstate, where, null) { i1, i2 ->
            thisSemantics.evalBWAnd(i1, op1, i2, op2, target, pstate.boundsAnalysis, pstate, where.narrow())
        }.let { b ->
            assignResult(b, where, target, c)
        }
    }

    private val thisSemantics : IValueSemantics<NumericDomain, QualifiedInt, PointsToDomain> = object : DelegatingSemantics<NumericDomain, QualifiedInt, PointsToDomain>(
        object : StatelessUIntValueSemantics<QualifiedInt, IntValue>() {
            override fun lift(lb: BigInteger, ub: BigInteger): IntValue {
                return IntValue.Interval(lb, ub)
            }

            override fun lift(iVal: IntValue): QualifiedInt = iVal.liftToBounded()

            override val nondet: QualifiedInt
                get() = IntValue.Nondet.liftToBounded()
        }.withBasicMath<NumericDomain, PointsToDomain, IntQualifier, QualifiedInt, IntValue>(
            IntQualifier::MultipleOf,
            IntQualifier::MaskedOf,
        ).withPathConditions(
            condition = IntQualifier::ConditionVar,
            conjunction = { conn, o1, o2 ->
                IntQualifier.LogicalConnective(
                    connective = conn,
                    op2 = o2,
                    op1 = o1,
                    applyNot = false,
                )
            },
            flip = { (it as? Flippable)?.flip() },
        ).withModularUpperBounds(
            modularUpperBound = { other, modulus, strong ->
                (other as? TACSymbol.Var)?.let { IntQualifier.ModularUpperBound(it, modulus, strong) }
            },
            extractModularUpperBound = { q -> q as? IntQualifier.ModularUpperBound }
        ),
    ) {
        override fun evalSub(
            v1: QualifiedInt,
            o1: TACSymbol,
            v2: QualifiedInt,
            o2: TACSymbol,
            toStep: NumericDomain,
            input: NumericDomain,
            whole: PointsToDomain,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): QualifiedInt {
            val toRet = super.evalSub(v1, o1, v2, o2, toStep, input, whole, l)
            return if(v2.x.isConstant && v2.x.c == 4.toBigInteger() && v1.qual.contains(IntQualifier.CalldataSize)) {
                toRet.copy(
                        qual = toRet.qual + IntQualifier.CalldataPayloadSize
                )
            } else if(o1 is TACSymbol.Var && o2 is TACSymbol.Var && (whole.invariants implies {
                    !o1 `=` !o2
                } || whole.invariants matchesAny {
                    o1 `=` o2 + v("other") {
                        it is LVar.PVar && toStep[it.v]?.let {
                            it as? QualifiedInt
                        }?.x?.mustEqual(BigInteger.ZERO) == true
                    }
                } != null)) {
                toRet.copy(x = IntValue.Constant(BigInteger.ZERO))
            } else {
                toRet
            }
        }

        override fun evalMult(
            v1: QualifiedInt,
            o1: TACSymbol,
            v2: QualifiedInt,
            o2: TACSymbol,
            toStep: NumericDomain,
            input: NumericDomain,
            whole: PointsToDomain,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): QualifiedInt {
            fun getOffsetQualifiers(a: QualifiedInt,
                                    aSym: TACSymbol,
                                    x: IntValue): Set<IntQualifier> {
                val ret = mutableSetOf<IntQualifier>()
                if (x.c != BigInteger.ZERO) {
                    ret.add(IntQualifier.MultipleOf(x.c))
                }
                a.qual.filterIsInstance(IntQualifier.ValidIndexOf::class.java).mapNotNullTo(ret) { q ->
                    pointerAnalysis.getElementSize(q.arrayVar, whole.pointsToState)?.let { sz ->
                        if (sz == x.c) {
                            IntQualifier.ElementOffsetFor(q.arrayVar, index = aSym as? TACSymbol.Var)
                        } else {
                            null
                        }
                    }
                }
                a.qual.filterIsInstance(IntQualifier.LengthOfArray::class.java).mapNotNullTo(ret) { q ->
                    pointerAnalysis.getElementSize(q.arrayVar, whole.pointsToState)?.let { sz ->
                        if (sz == x.c) {
                            IntQualifier.SizeOfElementSegment(q.arrayVar)
                        } else null
                    }
                }
                a.qual.filterIsInstance<IntQualifier.ValidCalldataArrayIndex>().mapNotNullTo(ret) { indQ ->
                    decoderAnalysis.getElementSize(indQ.calldataArrayVar, whole.decoderState)?.takeIf {
                        x.c == it
                    }?.let {
                        IntQualifier.SafeCalldataOffset(calldataArrayVar = indQ.calldataArrayVar, index = aSym as TACSymbol.Var)
                    }
                }
                return ret
            }
            val qual = (if (v2.x.isConstant) {
                    getOffsetQualifiers(v1, o1, v2.x)
                } else {
                    setOf()
                }) + (if (v1.x.isConstant) {
                    getOffsetQualifiers(v2, o2, v1.x)
                } else {
                    setOf()
                })
            return super.evalMult(v1, o1, v2, o2, toStep, input, whole, l).let { res ->
                res.copy(qual = res.qual + qual)
           }
        }

        fun isUpperBoundMask(qi: QualifiedInt) : Boolean {
            return qi.x.isConstant && isUpperBoundMask(qi.x.c)
        }


        /**
         * is the constant of the form 0b11....111000....000
         */
        fun isUpperBoundMask(c: BigInteger) : Boolean {
            return (c.andNot(MAX_EVM_UINT256) + BigInteger.ONE).bitCount() == 1
        }

        override fun evalBWAnd(
            v1: QualifiedInt,
            o1: TACSymbol,
            v2: QualifiedInt,
            o2: TACSymbol,
            toStep: NumericDomain,
            input: NumericDomain,
            whole: PointsToDomain,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): QualifiedInt {
            val quals = mutableListOf<IntQualifier>()
            if(isUpperBoundMask(v1)) {
                v2.qual.mapNotNullTo(quals) {
                    (it as? IntQualifier.LengthOfArray)?.let { la ->
                        IntQualifier.HasUpperBound(la, weak = true)
                    }
                }
            }
            if(isUpperBoundMask(v2)) {
                v1.qual.mapNotNullTo(quals) {
                    (it as? IntQualifier.LengthOfArray)?.let { la ->
                        IntQualifier.HasUpperBound(la, weak = true)
                    }
                }
            }
            val ret = super.evalBWAnd(v1, o1, v2, o2, toStep, input, whole, l)
            if(quals.isEmpty()) {
                return ret
            }
            return ret.copy(qual = ret.qual + quals)
        }

        override fun evalShiftLeft(
            v1: QualifiedInt,
            o1: TACSymbol,
            v2: QualifiedInt,
            o2: TACSymbol,
            toStep: NumericDomain,
            input: NumericDomain,
            whole: PointsToDomain,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): QualifiedInt {
            if(v2.x.isConstant && v2.x.c.toInt() < EVM_BITWIDTH256) {
                val const = BigInteger.TWO.pow(v2.x.c.toInt())
                return this.evalMult(
                    v1, o1, QualifiedInt(IntValue.Constant(const), treapSetOf()), const.asTACSymbol(), toStep, input, whole, l
                )
            } else {
                return super.evalShiftLeft(v1, o1, v2, o2, toStep, input, whole, l)
            }
        }
    }

    override fun assignSub(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                           pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return numericSubtraction.subtractionSemantics(op1, op2, pstate, where).let { b ->
            assignResult(b, where, target, c)
        }
    }

    override fun assignMul(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                           pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return binaryOperation(op1, op2, pstate, where, null) { a: QualifiedInt, b: QualifiedInt ->
            thisSemantics.evalMult(a, op1, b, op2, target, pstate.boundsAnalysis, pstate, where.narrow())
        }.let { b ->
            assignResult(b, where, target, c)
        }
    }

    private fun ResolvedIntDomain.toInt() : QualifiedInt = when(this) {
        is QualifiedInt -> this
        is ANY_POINTER -> AnyInt
    }

    override fun assignExpr(lhs: TACSymbol.Var, rhs: TACExpr, target: NumericDomain, pstate: PointsToDomain, ltacCmd: LTACCmd): NumericDomain {
        return when (rhs) {
            is TACExpr.BinRel.Eq -> {
                pstate.interp(rhs.o1AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a1 ->
                    pstate.interp(rhs.o2AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a2 ->

                        val res =
                                thisSemantics.evalEq(a1.toInt(), rhs.o1AsTACSymbol(), a2.toInt(), rhs.o2AsTACSymbol(), target, pstate.boundsAnalysis, pstate, ltacCmd.narrow())
                        target.updateLoc(lhs, res)
                    }
                }
            }
            is TACExpr.UnaryExp.LNot -> {
                pstate.interp((rhs.o as TACExpr.Sym).s, ltacCmd).tryResolveAsInt().let { a1 ->
                    thisSemantics.evalLNot(a1.toInt(), rhs.o.s, target, pstate.boundsAnalysis, pstate, ltacCmd.narrow())
                }.let {
                    target.updateLoc(lhs, it)
                }
            }
            is TACExpr.BinRel.Slt -> {
                pstate.interp(rhs.o1AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a1 ->
                    pstate.interp(rhs.o2AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a2 ->
                        thisSemantics.evalSlt(
                            o1 = rhs.o1AsTACSymbol(),
                            o2 = rhs.o2AsTACSymbol(),
                            whole = pstate,
                            l = ltacCmd.narrow(),
                            toStep = target,
                            input = pstate.boundsAnalysis,
                            v2 = a2.toInt(),
                            v1 = a1.toInt()
                        ).let {
                            target.updateLoc(lhs, it)
                        }
                    }
                }
            }
            is TACExpr.BinBoolOp -> {
                if (rhs.ls.size != 2 || !rhs.ls.all {
                        it is TACExpr.Sym.Var
                    }) {
                    return super.assignExpr(lhs, rhs, target, pstate, ltacCmd)
                }
                val connective = when(rhs) {
                    is TACExpr.BinBoolOp.LAnd -> LogicalConnectiveQualifier.LBinOp.AND
                    is TACExpr.BinBoolOp.LOr -> LogicalConnectiveQualifier.LBinOp.OR
                }
                target.updateLoc(lhs,
                    QualifiedInt(
                        x = IntValue.Interval(BigInteger.ZERO, BigInteger.ONE),
                        qual = treapSetOf(
                            IntQualifier.LogicalConnective(
                                applyNot = false,
                                connective = connective,
                                op1 = (rhs.o1 as TACExpr.Sym.Var).s,
                                op2 = (rhs.o2 as TACExpr.Sym.Var).s
                            )
                        )
                    )
                )
            }
            // FIXME(jtoman): duplicated now in the qualifier interpreter
            is TACExpr.TernaryExp.Ite -> {
                val fallthrough by lazy {
                    super.assignExpr(lhs, rhs, target, pstate, ltacCmd)
                }
                val i = rhs.i
                val t = rhs.t
                val e = rhs.e
                if(t !is TACExpr.Sym.Const || e !is TACExpr.Sym.Const || i !is TACExpr.Sym.Var) {
                    return fallthrough
                }
                val toEncode = interpSymbol(where = ltacCmd, sym = i.s, st = target).tryResolveAsInt().let {
                    it as? QualifiedInt
                }?.qual?.filterIsInstance<Flippable>()?.takeIf { it.isNotEmpty() } ?: return fallthrough
                val q = if(t.s.value == BigInteger.ONE && e.s.value == BigInteger.ZERO) {
                    toEncode.mapToTreapSet { it as IntQualifier }
                } else if(t.s.value == BigInteger.ZERO && e.s.value == BigInteger.ONE) {
                    toEncode.mapToTreapSet { it.flip() }
                } else {
                    return fallthrough
                }
                target.updateLoc(lhs, QualifiedInt(
                    qual = q,
                    x = IntValue.Interval(BigInteger.ZERO, BigInteger.ONE)
                ))
            }
            is TACExpr.BinRel.Gt -> {
                pstate.interp(rhs.o1AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a1 ->
                    pstate.interp(rhs.o2AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a2 ->
                        val res = thisSemantics.evalLt(a2.toInt(), rhs.o2AsTACSymbol(), a1.toInt(), rhs.o1AsTACSymbol(), target, pstate.boundsAnalysis, pstate, ltacCmd.narrow())
                        target.updateLoc(lhs, res)
                    }
                }
            }
            is TACExpr.BinOp.ShiftRightLogical -> {
                pstate.interp(rhs.o1AsTACSymbol(), ltacCmd).tryResolveAsInt().let { a1 ->
                    pstate.interp(rhs.o2AsTACSymbol(), ltacCmd).tryResolveAsInt().let {a2 ->
                        val res = thisSemantics.evalShiftRightLogical(a1.toInt(), rhs.o1AsTACSymbol(), a2.toInt(), rhs.o2AsTACSymbol(), target, pstate.boundsAnalysis, pstate, ltacCmd.narrow())
                        target.updateLoc(lhs, res)
                    }
                }
            }
            is TACExpr.BinOp.Div -> {
                val divOp = target[rhs.o1AsTACSymbol()] as? QualifiedInt
                if(interpAsConstant(pstate, ltacCmd, rhs.o2AsTACSymbol()) == BigInteger.ONE && divOp != null) {
                    return target.updateLoc(lhs, divOp)
                }
                super.assignExpr(lhs, rhs, target, pstate, ltacCmd)
            }
            else -> super.assignExpr(lhs, rhs, target, pstate, ltacCmd)
        }
    }

    override fun assignLt(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                          pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        /* this is very much an adhoc workaround, so we can detect free pointer overflows (and then later delete them)
        *
        * TODO(jtoman) Ideally we would have an optional "all pointer" semantics that could be used in arithSemantics
        */
        return binaryOperation(op1, op2, pstate, where, null) { a1, a2 ->
            thisSemantics.evalLt(a1, op1, a2, op2, target, pstate.boundsAnalysis, pstate, where.narrow())
        }.let { b ->
            assignResult(QualifiedInt(
                x = b.x.updateBounds(BigInteger.ZERO, BigInteger.ONE),
                qual = b.qual + IntQualifier.ConditionVar(
                    op1, op2, ConditionQualifier.Condition.LT
                )
            ), where, target, c)
        }
    }

    private val numericSubtraction by lazy {
        object : SubtractionSemantics<IntDomain>(pointerAnalysis) {
            override val nondetInteger: IntDomain
                get() = AnyInt
            override val scratchPointerResult: IntDomain
                get() = ANY_POINTER

            override fun remainingSizeProof(
                indexPointer: TACSymbol.Var,
                array: List<TACSymbol.Var>,
                pstate: PointsToDomain,
                where: LTACCmd
            ): IntDomain = QualifiedInt(
                x = IntValue.Nondet,
                qual = array.mapToTreapSet {
                    IntQualifier.RemainingElementsProofFor(
                        indexVar = indexPointer,
                        arrayVar = it
                    )
                }
            )

            override fun numericSubtraction(op1: TACSymbol, op2: TACSymbol, pstate: PointsToDomain, where: LTACCmd): IntDomain {
                return binaryOperation(op1, op2, pstate, where, BigInteger::subtract) { i1, i2 ->
                    thisSemantics.evalSub(i1, op1, i2, op2, pstate.boundsAnalysis, pstate.boundsAnalysis, pstate, where.narrow())
                }
            }

            override fun numericResult(lb: BigInteger, ub: BigInteger): IntDomain {
                return QualifiedInt(IntValue.Interval(lb, ub), treapSetOf())
            }

            override fun untilEndProof(elemPtr: TACSymbol.Var, pstate: PointsToDomain, where: LTACCmd): IntDomain =
                QualifiedInt(x = IntValue.Nondet, qual = treapSetOf(IntQualifier.UntilEndFor(elemPtr)))

            override fun lengthOfBlock(arrayPtr: Set<TACSymbol.Var>, pstate: PointsToDomain, where: LTACCmd): IntDomain {
                val quals = treapSetBuilderOf<IntQualifier>()
                val elemSize = arrayPtr.monadicMap {
                    pointerAnalysis.getElementSize(it, pstate.pointsToState)
                }?.toSet()?.singleOrNull()
                var bounds : IntValue? = null
                for(p in arrayPtr) {
                    quals.add(IntQualifier.SizeOfElementSegment(p))
                    if(elemSize == BigInteger.ONE) {
                        quals.add(IntQualifier.LengthOfArray(p))
                        val len = arrayStateAnalysis.getLogicalLength(p, pstate) ?: IntValue.Nondet
                        bounds = bounds?.join(
                                len
                        ) ?: len
                    }
                }
                return QualifiedInt(bounds ?: IntValue.Nondet, quals.build())
            }
        }

    }

    override fun handleWordLoad(
            ltacCmd: LTACCmdView<TACCmd.Simple.AssigningCmd.WordLoad>,
            kill: NumericDomain,
            pState: PointsToDomain
    ): NumericDomain {
        val set = pointerAnalysis.lengthReadFor(ltacCmd = ltacCmd.wrapped, pState = pState)
                ?: return super.handleWordLoad(ltacCmd, kill, pState)
        return this.assignResult(
                where = ltacCmd.wrapped, target = kill, result = QualifiedInt(
                IntValue.Nondet,
                set.mapToTreapSet {
                    IntQualifier.LengthOfArray(it)
                }
        ), c = ltacCmd.cmd.lhs)
    }

    private val numericPlus by lazy {
        object : AdditionSemantics<NumericDomain>() {
            override val numericAnalysis: NumericAnalysis
                get() = this@NumericAnalysis
            override val arrayStateAnalysis: ArrayStateAnalysis
                get() = this@NumericAnalysis.arrayStateAnalysis
            override val relaxedArrayAddition: Boolean
                get() = this@NumericAnalysis.relaxedAddition

            override fun toStaticArrayInitPointer(
                av1: InitializationPointer.BlockInitPointer,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain {
                return target + (where.lhs to ANY_POINTER)
            }

            override fun toAddedStaticArrayInitPointer(
                av1: InitializationPointer.StaticArrayInitPointer,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>,
            ): NumericDomain {
                return target + (where.lhs to ANY_POINTER)
            }

            override fun nondeterministicInteger(where: ExprView<TACExpr.Vec.Add>, s: PointsToDomain, target: NumericDomain): NumericDomain =
                target + (where.lhs to AnyInt)

            override fun toEndSegment(startElem: Set<TACSymbol.Var>, o1: TACSymbol.Var, target: NumericDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): NumericDomain =
                target + (where.lhs to QualifiedInt(
                        IntValue.Nondet,
                        startElem.mapToTreapSet {
                            IntQualifier.EndOfArraySegment(it)
                        }
                ))

            override fun byteInitAddition(
                av1: InitializationPointer.ByteInitPointer,
                amountAdded: IntValue,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun blockInitAddition(
                av1: InitializationPointer.BlockInitPointer,
                o1: TACSymbol.Var,
                newOffset: BigInteger,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun arrayInitAddition(
                av1: InitializationPointer.ArrayInitPointer,
                x: BigInteger?,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun toAddedElemPointer(
                    arrayBase: Set<TACSymbol.Var>,
                    v: Set<ArrayAbstractLocation.A>,
                    o1: TACSymbol.Var?,
                    addOperand: TACSymbol,
                    currIndex: IntValue,
                    addAmount: IntValue,
                    untilEnd: Set<CanonicalSum>,
                    target: NumericDomain,
                    p: PointsToDomain,
                    where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain {
                val quals = if(o1 != null) {
                    treapSetOf(IntQualifier.UpperBoundProofFor(
                            mightBeElem = o1,
                            upperBound = CanonicalSum(addOperand)
                    ))
                } else {
                    treapSetOf()
                }
                return target + (where.lhs to QualifiedInt(IntValue.Nondet, quals))
            }

            override fun toArrayElemStartPointer(
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain {
                val equiv = mustAliasAnalysis.equivBefore(where.ptr, o1) - where.lhs
                val toUpdate = whole.boundsAnalysis.mapNotNull { (k, v) ->
                    val vRes = v.tryResolve()
                    if(k != where.lhs && vRes is QualifiedInt && vRes.qual.any {
                                it is IntQualifier.LengthOfArray && it.arrayVar in equiv
                            }) {
                        k to IntQualifier.LengthOfElementSegment(where.lhs)
                    } else {
                        null
                    }
                }
                return if(toUpdate.isNotEmpty()) {
                    target.mutate { mut ->
                        mut[where.lhs] = ANY_POINTER
                        for((vToUpdate, newQual) in toUpdate) {
                            mut[vToUpdate] = mut[vToUpdate]!!.expectInt() + setOf(newQual)
                        }
                    }
                } else {
                    target + (where.lhs to ANY_POINTER)
                }
            }


            override fun toArrayElementPointer(
                v: Set<ArrayAbstractLocation.A>,
                basePointers: Set<TACSymbol.Var>,
                index: IntValue?,
                indexVar: Set<TACSymbol.Var>,
                untilEnd: Set<CanonicalSum>,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun toConstArrayElemPointer(
                v: Set<L>,
                blockBase: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>,
                indexingProof: ConstArraySafetyProof
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun toBlockElemPointer(
                av1: Set<L>,
                offset: BigInteger,
                blockSize: BigInteger,
                op1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun toIdentityPointer(
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)

            override fun scratchPointerAddition(
                o1: TACSymbol.Var,
                o2: TACSymbol,
                offsetAmount: IntValue,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to ANY_POINTER)


            private fun isArrayShiftFor(o1: TACSymbol.Var?, i1: QualifiedInt, i2: QualifiedInt): Set<IntQualifier> =
                    if (i2.x.isConstant && i2.x.c == 0x20.toBigInteger()) {
                        val mut = mutableSetOf<IntQualifier>()
                        for(q in i1.qual) {
                            if(q is IntQualifier.SizeOfElementSegment) {
                                mut.add(IntQualifier.SizeOfArrayBlock(q.arrayVar))
                            }
                            if(q is IntQualifier.ElementOffsetFor) {
                                mut.add(IntQualifier.SafeArrayOffset(q.arrayVar, q.index))
                                if(q.index == null && i1.qual.any { it is IntQualifier.ValidIndexOf && it.arrayVar == q.arrayVar } && o1 != null) {
                                    mut.add(IntQualifier.SafeArrayOffset(q.arrayVar, o1))
                                }
                            }
                        }
                        mut
                    } else {
                        setOf()
                    }


            override fun arithmeticAddition(o1: TACSymbol.Var, o2: TACSymbol, target: NumericDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): NumericDomain {
                return whole.interpAsInt(o1, ltacCmd = where.wrapped).let { i1 ->
                    whole.interpAsInt(o2, ltacCmd = where.wrapped).let { i2 ->
                        val arrayBoundQuals = isArrayShiftFor(o1, i1, i2) + isArrayShiftFor(o2 as? TACSymbol.Var, i2, i1) + isBoundsCheckFor(o1, o2, whole) + isEndArrayFor(i1, i2)
                        thisSemantics.evalAdd(i1, o1, i2, o2, whole.boundsAnalysis, whole.boundsAnalysis, whole, where.wrapped.narrow()) + arrayBoundQuals
                    }
                }.let {
                    assignResult(it, where.wrapped, target, where.lhs)
                }
            }

            private fun isEndArrayFor(i1: QualifiedInt, i2: QualifiedInt): Iterable<IntQualifier> {
                return if(i1.x.isConstant && i1.x.c == 0x20.toBigInteger()) {
                    i2.qual.filterIsInstance<IntQualifier.BasePlusLength>().map {
                        IntQualifier.EndOfArraySegment(it.arrayVar)
                    }
                } else if(i2.x.isConstant && i2.x.c == 0x20.toBigInteger()) {
                    i1.qual.filterIsInstance<IntQualifier.BasePlusLength>().map {
                        IntQualifier.EndOfArraySegment(it.arrayVar)
                    }
                } else {
                    listOf()
                }
            }

            private fun isBoundsCheckFor(o1: TACSymbol.Var, o2: TACSymbol, p: PointsToDomain): Set<IntQualifier> {
                val toReturn = mutableSetOf<IntQualifier>()
                if(p.arrayState[o1] is ArrayStateAnalysis.Value.MaybeElementPointer) {
                    toReturn.add(IntQualifier.UpperBoundProofFor(o1, CanonicalSum(o2)))
                }
                p.boundsAnalysis[o1]?.let {
                    it as? QualifiedInt
                }?.qual?.filterIsInstance<IntQualifier.UpperBoundProofFor>()?.mapTo(toReturn) {
                    it.copy(upperBound = it.upperBound + o2)
                }
                return toReturn
            }

            override fun additionConstant(
                c1: BigInteger,
                c2: BigInteger,
                o1: TACSymbol.Const,
                o2: TACSymbol.Const,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain =
                target + (where.lhs to IntValue.Constant(c1 + c2).liftToBounded())

            override val pointerAnalysis: IPointerInformation
                get() = this@NumericAnalysis.pointerAnalysis

            override fun toEmptyDataSegment(target: NumericDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): NumericDomain {
                return target + (where.lhs to QualifiedInt(
                        x = IntValue.Nondet,
                        qual = treapSetOf(IntQualifier.EmptyDataSegment)
                ))
            }

            override fun byteLengthAddition(
                av1: InitializationPointer.ByteInitPointer,
                o1: TACSymbol.Var,
                target: NumericDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
            ): NumericDomain {
                return target + (where.lhs to QualifiedInt(
                        x = IntValue.Nondet,
                        qual = treapSetOf(IntQualifier.BasePlusLength(o1))
                ))
            }
        }
    }

    override fun assignAdd(c: TACSymbol.Var, op1: TACSymbol, op2: TACSymbol, target: NumericDomain,
                           pstate: PointsToDomain, where: LTACCmd): NumericDomain {
        return numericPlus.addition(target, pstate, where.enarrow())
    }

    private fun assignResult(result: IntDomain, where: LTACCmd, target: NumericDomain, c: TACSymbol.Var): NumericDomain {
        return when (result) {
            is QualifiedInt -> this.qualifierManager.assign(target, c, result, where.ptr)
            else -> target.updateLoc(c, result)
        }
    }

    override fun scratchAllocationTo(lhs: TACSymbol.Var, target: NumericDomain, pstate: PointsToDomain,
                                     where: LTACCmd): NumericDomain = target.updateLoc(lhs, ANY_POINTER)

    override fun allocationTo(lhs: TACSymbol.Var, abstractLocation: AllocationAnalysis.AbstractLocation,
                              target: NumericDomain, pstate: PointsToDomain,
                              where: LTACCmd): NumericDomain = target.updateLoc(lhs, ANY_POINTER)

    override fun assignNondetInt(lhs: TACSymbol.Var, target: NumericDomain, pstate: PointsToDomain,
                                 where: LTACCmd): NumericDomain = target.updateLoc(lhs, AnyInt)

    override fun assignConstant(lhs: TACSymbol.Var, value: BigInteger, target: NumericDomain, pstate: PointsToDomain,
                                where: LTACCmd): NumericDomain {
        return if (value == 0x60.toBigInteger()) {
            UnresolvedValue(typeVariableManager.getVariableForSite(where))
        } else {
            IntValue.Constant(value).liftToBounded()
        }.let {
            target.updateLoc(lhs, it)
        }
    }

    override fun assignVariable(lhs: TACSymbol.Var, rhs: TACSymbol.Var, target: NumericDomain, pstate: PointsToDomain,
                                where: LTACCmd): NumericDomain {
        val newVal = target[rhs] ?: AnyInt
        return target.updateLoc(lhs, newVal)
    }

    override fun killLhsRelations(lhs: TACSymbol.Var, init: NumericDomain, pstate: PointsToDomain,
                                  where: LTACCmd): NumericDomain {
        return (this.interpSymbol(where, init, lhs) as? QualifiedInt)?.let {
            return this.qualifierManager.killLHS(lhs, lhsVal = it, where = where.ptr, s = init)
        } ?: init
    }

    override fun stepAnnotation(
        kill: NumericDomain,
        pState: PointsToDomain,
        narrow: LTACCmdView<TACCmd.Simple.AnnotationCmd>
    ): NumericDomain {
        /*
         * This is importing boundary information from the LoopCopyAnalysis.  If at some point
         * we augment the SimpleLoopSummarizer to compute the exit conditions we can use that instead.
         */
        return narrow.cmd.bind(ITERATION_VARIABLE_BOUND) {
            kill[it.iterationVariable]?.expectInt()?.let { iterationVariableState ->
                kill[it.boundVariable]?.expectInt()?.let { boundVariableState ->
                    if (it.stride >= BigInteger.ZERO &&
                        iterationVariableState.x.lb.mod(it.stride) == boundVariableState.x.ub.mod(it.stride)) {

                        kill + (it.iterationVariable to iterationVariableState.copy(
                            x = IntValue(
                                iterationVariableState.x.lb,
                                boundVariableState.x.ub - it.stride
                            )
                        ))
                    } else if (it.stride < BigInteger.ZERO &&
                        iterationVariableState.x.ub.mod(it.stride) == boundVariableState.x.lb.mod(it.stride)) {

                        kill + (it.iterationVariable to iterationVariableState.copy(
                            x = IntValue(
                                boundVariableState.x.lb - it.stride,
                                iterationVariableState.x.ub
                            )
                        ))
                    } else {
                        kill
                    }
                }
            }
        } ?: kill
    }

    private fun equivAt(where: LTACCmd,
                lhs: TACSymbol.Var): Set<TACSymbol.Var> = mustAliasAnalysis.equivBefore(where.ptr, lhs)

    override fun extract(pState: PointsToDomain): NumericDomain = pState.boundsAnalysis

    fun consumeConversion(st: NumericDomain, toConv: List<ConversionHints>): NumericDomain =
            st.mutate { ret ->
                for (conv in toConv) {
                    when(conv) {
                        is ConversionHints.Int -> ret[conv.v] = IntValue.Nondet.liftToBounded()
                        is ConversionHints.Array,
                        is ConversionHints.ArrayElemStart,
                        is ConversionHints.Block -> {
                            ret[conv.v] = ANY_POINTER
                        }
                    }
                }
            }

    override fun isSafeMultiplierFor(pstate: PointsToDomain, blockSize: BigInteger, op2: TACSymbol.Var): Boolean {
        return (pstate.boundsAnalysis[op2] as? QualifiedInt)?.let { i ->
            i.inducedUpperBound().let { ub ->
                ub < blockSize && i.qual.any {
                    it is IntQualifier.MultipleOf && it.factor == PointerSemantics.ALIGN_CONST
                }
            }
        } ?: false
    }

    override fun isSafeArrayElementOffsetFor(pstate: PointsToDomain, where: LTACCmd, op2: TACSymbol, startOf: Set<TACSymbol.Var>): Boolean {
        if(op2 !is TACSymbol.Var) {
            return false
        }
        val fullSat = startOf.flatMap { equivAt(where, it) }
        return equivAt(where, op2).any {idx ->
            pstate.boundsAnalysis[idx]?.let {
                if(it !is QualifiedInt) {
                    return@let false
                }
                it.qual.any {
                    it is IntQualifier.ElementOffsetFor && it.arrayVar in fullSat
                }
            } == true
        }
    }

    private val propagationComputation = object : QualifierPropagationComputation<QualifiedInt, NumericDomain, PointsToDomain, IntQualifier>() {
        private fun extractValueInner(op1: TACSymbol.Var, s: NumericDomain) : QualifiedInt? {
            return s[op1]?.tryResolveAsInt()?.let {
                it as? QualifiedInt
            }
        }

        override fun extractValue(op1: TACSymbol.Var, s: NumericDomain, w: PointsToDomain, l: LTACCmd): QualifiedInt? {
            return s[op1]?.tryResolveAsInt()?.let {
                it as? QualifiedInt
            }
        }

        private val validUints = BigInteger.ZERO..MAX_EVM_UINT256

        /**
         * Any other variables which are scalar multiples of the primary condition variable can also be bounded by the
         * branch condition. To demonstrate, suppose we have y < k, and we have some x = f(y). If f is monotone, then
         * f(y) < f(k), and from the equality on x, we have x < f(k). Note that this is only possible if f is monotone, that is
         * f(y) doesn't overflow/underflow and neither does f(k).
         *
         * [op] is the variable `y` in question, [k] is `k`, and [rangeValue] is the value of [op] after applying the bound (either y < k, y >= k, etc.)
         * [mk] takes an integer and produces a bound that is "equivalent" to the one applied to [op]. For example, if `op < k`, and
         * we infer that `x < f(k)`, then [mk] should generate `StrictUpperBound(f(k))`.
         */
        private fun saturateLinearConstraints(
            op: TACSymbol.Var,
            k: BigInteger,
            rangeValue: IntValue,
            toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>,
            w: PointsToDomain,
            mk: (BigInteger) -> PathInformation<IntQualifier>
        ) {
            val monotoneTransformer = TermMatching.compile {
                v("other") `=` (k("factor") {
                    it > BigInteger.ZERO
                } * op) + k("sym")
            }

            w.invariants.matches(monotoneTransformer).mapNotNull matchLoop@{ m ->
                // so then `f(z) = k(factor) * z + k(sym) is the f(z) applied to y to yield x.
                // let's make sure this is monotone by ensuring it doesn't overflow/underflow at the endpoints of
                // y (after clamping). This really gives the range of possible values for x, so let's make sure those don't wrap
                val multiplier = m.factors["factor"]!!
                val constantOffset = m.factors["sym"]!!
                val lowerEnd = rangeValue.lb * multiplier + constantOffset
                val upperEnd = rangeValue.ub * multiplier + constantOffset

                val kTransformed = k * multiplier + constantOffset
                if (lowerEnd !in validUints || upperEnd !in validUints || kTransformed !in validUints) {
                    return@matchLoop null
                }
                (m.symbols["other"] as LVar.PVar).v to kTransformed
            }.forEach { (v, k) ->
                toRet.getOrPut(v) {
                    mutableListOf()
                }.add(mk(k))
            }
        }

        /**
         * Same as [clampLower], but clamps from above, and subtracts 1 from [k] if [isStrict] is true.
         */
        private fun clampUpper(op1: TACSymbol.Var, s: NumericDomain, k: BigInteger, isStrict: Boolean) : IntValue? {
            val newUpper = k.letIf(isStrict) {
                it - BigInteger.ONE
            }
            return extractValueInner(op1, s)?.x?.let {
                if(newUpper < it.lb) {
                    throw PruneInfeasible()
                } else {
                    it.withUpperBound(newUpper)
                }
            }
        }

        /**
         * Clamp the interval value of [op1] in [s] from below by [k]. If [isStrict] the lower bound applied is one greater,
         * otherwise, [k] is used directly. Throws [PruneInfeasible] if this yields a contradiction.
         *
         * Returns null if [op1] isn't in [s].
         */
        private fun clampLower(op1: TACSymbol.Var, s: NumericDomain, k: BigInteger, isStrict: Boolean) : IntValue? {
            val newLower = k.letIf(isStrict) {
                it + BigInteger.ONE
            }
            return extractValueInner(op1, s)?.x?.let {
                if(newLower > it.ub) {
                    throw PruneInfeasible()
                } else {
                    it.withLowerBound(newLower)
                }
            }
        }

        /**
         * The generate propagation rule for <= and <. Whether the bound is strict (i.e. [op1] < [op2] vs [op1] <= [op2])
         * is determined by [isStrict].
         * [mkLower] and [mkUpper] generate path constraints expressed lower and upper bounds respectively, which are
         * strict/weak depending on whether [isStrict] is true.
         */
        private fun propagateBound(
            op1: TACSymbol,
            op2: TACSymbol,
            isStrict: Boolean,
            mkLower: (BigInteger) -> PathInformation<IntQualifier>,
            mkUpper: (BigInteger) -> PathInformation<IntQualifier>,

            toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>,
            s: NumericDomain,
            w: PointsToDomain,
            l: LTACCmd
        ) {
            if(op1 is TACSymbol.Var) {
                extractExactSymValue(op2, s, w, l)?.let { k ->
                    clampUpper(op1, s, k, isStrict = isStrict)?.let { clamped ->
                        saturateLinearConstraints(
                            op1, k, clamped, toRet, w, mkUpper
                        )
                    }
                }
            }
            if(op2 is TACSymbol.Var) {
                extractExactSymValue(op1, s, w, l)?.let { k ->
                    clampLower(op2, s, k, isStrict = isStrict)?.let { clamped ->
                        saturateLinearConstraints(
                            op2, k, clamped, toRet, w, mkLower
                        )
                    }
                }
            }
        }

        override fun propagateLt(
            op1: TACSymbol,
            op2: TACSymbol,
            toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>,
            s: NumericDomain,
            w: PointsToDomain,
            l: LTACCmd
        ): Boolean {
            propagateBound(op1, op2, isStrict = true, mkLower = ::StrictLowerBound, mkUpper = ::StrictUpperBound, toRet, s, w, l)
            return super.propagateLt(op1, op2, toRet, s, w, l)
        }

        override fun propagateLe(
            op1: TACSymbol,
            op2: TACSymbol,
            toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>,
            s: NumericDomain,
            w: PointsToDomain,
            l: LTACCmd
        ): Boolean {
            propagateBound(op1, op2, isStrict = false, mkLower = ::WeakLowerBound, mkUpper = ::WeakUpperBound, toRet, s, w, l)
            return super.propagateLe(op1, op2, toRet, s, w, l)
        }

        override fun weakUpperBound(
            toRet: TACSymbol.Var,
            v: MutableList<PathInformation<IntQualifier>>,
            sym: TACSymbol.Var?,
            num: BigInteger?,
            s: NumericDomain,
            w: PointsToDomain,
            l: LTACCmd,
        ) {
            if (sym == null) {
                return
            }

            val symInt = (s[sym] as? QualifiedInt) ?: return
            val symQuals = symInt.qual.orEmpty()

            symQuals
                .filter { q ->
                    q is IntQualifier.SizeOfElementSegment || q is IntQualifier.LengthOfArray || q is IntQualifier.SizeOfArrayBlock ||
                        q is IntQualifier.EndOfArraySegment
                }.forEach {
                    v.add(PathInformation.Qual(IntQualifier.HasUpperBound(it, true)))
                }

                val safeIndexOfArrayVars = mutableSetOf<TACSymbol.Var>()
                // toRet = sym - k where sym : len(a) implies toRet : ValidIndexOf(a)
                // (from the condition we have that toRet <= sym and hence the subtraction does not underflow)
                symQuals
                    .filterIsInstance<IntQualifier.LengthOfArray>()
                    .map {it.arrayVar }
                    .let { arrays ->
                        w.invariants.matchesAny { toRet `=` sym - k("const") { it > BigInteger.ZERO } }
                            ?.let {
                                safeIndexOfArrayVars.addAll(arrays)
                            }
                    }

                fun lenMatch(it: LVar) =
                    it is LVar.PVar && (s[it.v] as? QualifiedInt)?.let { it.qual.any { it is IntQualifier.LengthOfArray } } == true

                // toRet <= sym && sym = len - k && k <= len && len : Length(a) ===> toRet : ValidIndexOf(a)
                w.invariants.matches { sym `=` v("len", ::lenMatch) - k("const") { it > BigInteger.ZERO } }.forEach {m ->
                    // The checks in `lenMatch` guarantee justify the casts
                    val len = s[(m.symbols["len"]!! as LVar.PVar).v]!! as QualifiedInt
                    val const = m.factors["const"]!!
                    if (const <= len.x.lb) {
                        len.qual.filterIsInstance<IntQualifier.LengthOfArray>().mapTo(safeIndexOfArrayVars) { it.arrayVar }
                    }
                }

                for (array in safeIndexOfArrayVars) {
                    v.add(PathInformation.Qual(IntQualifier.ValidIndexOf(array)))
                    val size = pointerAnalysis.getElementSize(array, w.pointsToState)
                    if (size == BigInteger.ONE) {
                        v.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(array, null)))
                    }
                }

            // x <= y && y : validIndexOf(a) ==> x : validIndexOf(a)
            // (and index ~ elementOffset when size = 1)
            symQuals
                .filterIsInstance<IntQualifier.ValidIndexOf>()
                .forEach {
                    v.add(PathInformation.Qual(it))
                    val size = pointerAnalysis.getElementSize(it.arrayVar, w.pointsToState)
                    if (size == BigInteger.ONE) {
                        v.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(it.arrayVar, null)))
                    }
                }

            // x <= y && size == 1 && y : elementOffsetFor(a) ==> x : elementOffsetFor(a)
            symQuals
                .filterIsInstance<IntQualifier.ElementOffsetFor>()
                .forEach {
                    val size = pointerAnalysis.getElementSize(it.arrayVar, w.pointsToState)
                    // This can be strengthened if the difference is divisible by 32
                    if (size == BigInteger.ONE) {
                        v.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(it.arrayVar, null)))
                    }
                }

            /*
               toRet <= sym where sym == 0 implies that sym == toRet
             */
            if(symInt.x.takeIf { it.isConstant }?.c == BigInteger.ZERO) {
                v.add(PathInformation.StrictEquality(sym = sym, num = null))
            }
        }

        private fun Iterable<IntQualifier>.unwrapBounds(allowWeak: Boolean) : Sequence<IntQualifier> {
            return this.asSequence().mapNotNull {
                if(it is IntQualifier.HasUpperBound) {
                    if(it.weak && !allowWeak) {
                        null
                    } else {
                        it.other
                    }
                } else {
                    it
                }
            }
        }

        override fun strictUpperBound(
            v: TACSymbol.Var,
            vQuals: MutableList<PathInformation<IntQualifier>>,
            sym: TACSymbol.Var?,
            num: BigInteger?,
            s: NumericDomain,
            w: PointsToDomain,
            l: LTACCmd
        ) {
            if(sym == null) {
                return
            }
            s[sym]?.tryResolve()?.let { it as? QualifiedInt }?.qual?.let {
                outer@for(q in it.unwrapBounds(true)) {
                    when(q) {
                        is IntQualifier.LengthOfArray -> {
                            vQuals.add(PathInformation.Qual(IntQualifier.ValidIndexOf(q.arrayVar)))
                            val arraySize = pointerAnalysis.getElementSize(q.arrayVar, w.pointsToState) ?: continue@outer
                            if(arraySize == BigInteger.ONE) {
                                vQuals.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(q.arrayVar, index = null)))
                            }
                            vQuals.add(PathInformation.Qual(IntQualifier.HasUpperBound(q, false)))
                        }
                        is IntQualifier.SizeOfArrayBlock,
                        is IntQualifier.EndOfArraySegment -> {
                            vQuals.add(PathInformation.Qual(IntQualifier.HasUpperBound(q, false)))
                        }
                        is IntQualifier.SizeOfElementSegment -> {
                            val op1Int = s[v]?.tryResolve() as? QualifiedInt ?: continue@outer
                            val arraySize = pointerAnalysis.getElementSize(q.arrayVar, w.pointsToState) ?: continue@outer
                            if(op1Int.isMultipleOf(arraySize)) {
                                vQuals.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(q.arrayVar, index = null)))
                            }
                            if(arraySize == BigInteger.ONE) {
                                vQuals.add(PathInformation.Qual(IntQualifier.ValidIndexOf(q.arrayVar)))
                            }
                        }
                        is IntQualifier.LengthOfCalldataArray -> {
                            vQuals.add(PathInformation.Qual(IntQualifier.ValidCalldataArrayIndex(q.calldataArrayVar)))
                        }
                        else -> {}
                    }
                }
            }
        }

        override fun propagateSlt(op1: TACSymbol, op2: TACSymbol, toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>, s: NumericDomain, w: PointsToDomain, l: LTACCmd): Boolean {
            if (!super.propagateSlt(op1, op2, toRet, s, w, l)) {
                return false
            }
            if(s[op2]?.let { it as? QualifiedInt }?.qual?.any {
                        it is IntQualifier.EndOfArraySegment
                    } == true) {
                return propagateLt(op1, op2, toRet, s, w, l)
            }
            return true
        }
    }.withModularUpperBounds(
        extractModularUpperBound = { it as? IntQualifier.ModularUpperBound },
        extractMultipleOf = { (it as? IntQualifier.MultipleOf)?.factor },
        modularUpperBound = { other, modulus, strong ->
            (other as? TACSymbol.Var)?.let { IntQualifier.ModularUpperBound(it, modulus, strong) }
        },
    )

    private fun doPropagation(
        st: NumericDomain,
        op1: TACSymbol.Var,
        pstate: PointsToDomain,
        target: LTACCmd,
        compute: (TACSymbol.Var, QualifiedInt, NumericDomain, PointsToDomain, LTACCmd) -> Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>?
    ): NumericPathInformation {
        if(op1 !in st) {
            return NumericPathInformation(
                    state = st,
                    arrayHints = listOf(),
                    pathInformation = mapOf()
            )
        }
        val condVal = st[op1]!!.expectInt()
        val propagation = compute(op1, condVal, st, pstate, target) ?: throw PruneInfeasible()
        val arrayHintPropagation = mutableListOf<ArrayHints>()
        val additional = mutableMapOf<TACSymbol.Var, MutableList<PathInformation<IntQualifier>>>()
        for((v, propagatedFacts) in propagation) {
            for(ub in propagatedFacts.filterIsInstance<UpperBound>().filter {
                it.sym != null
            }) {

                /**
                 * If we have that v1 + v2 <= K (or `<` as we are only looking for [UpperBound]),
                 * where K is some *lower* bound on the number of bytes remaining in an `array`
                 * then we know that it is safe to copy v1 bytes starting at v2 in the element
                 * block of `array` (and vice versa).
                 *
                 * Some candidates for K are another safe copy index of v2, the size of the element segment,
                 * or (if the array has element size 1) the length of the array.
                 */
                fun TACSymbol.Var.elemSize() = pointerAnalysis.getElementSize(this, pState = pstate.pointsToState)
                val ubAv = ub.sym!!.let(st::get)?.let { it as? QualifiedInt } ?: continue
                val arrVars = ubAv.qual.mapNotNullToTreapSet {
                    when(it) {
                        is IntQualifier.LengthOfArray -> {
                            it.arrayVar.takeIf { av ->
                                av.elemSize() == BigInteger.ONE
                            }
                        }
                        is IntQualifier.SizeOfElementSegment -> it.arrayVar
                        is IntQualifier.ElementOffsetFor -> it.arrayVar
                        is IntQualifier.SafeCopyOffset -> it.arrayVar
                        else -> null
                    }
                }
                for(match in pstate.invariants matches {
                    v `=` v("v1") {
                        it is LVar.PVar
                    } + v("v2") {
                        it is LVar.PVar
                    }
                }) {
                    val tgt = match.symbols["v1"] as LVar.PVar
                    val len = match.symbols["v2"] as LVar.PVar
                    for(arrVar in arrVars) {
                        additional.getOrPut(tgt.v) {
                            mutableListOf()
                        }.add(PathInformation.Qual(IntQualifier.SafeCopyOffset(arrayVar = arrVar, safeCopyLength = len.v)))
                    }
                }
            }
            val valueQualifiers = st[v]?.tryResolve()?.let { it as? QualifiedInt }?.qual
            if(IntQualifier.CalldataSize in valueQualifiers.orEmpty()) {
                val cds = TACKeyword.CALLDATASIZE.toVar()
                additional.getOrPut(cds) { mutableListOf() }.addAll(propagatedFacts.filterNot {
                    it is PathInformation.Qual && it.q.relates(cds)
                })
            }
            if(IntQualifier.CalldataPayloadSize in valueQualifiers.orEmpty()) {
                val cds = TACKeyword.CALLDATASIZE.toVar()
                additional.getOrPut(cds) {
                    mutableListOf()
                }.addAll(propagatedFacts.mapNotNull { pi ->
                    when(pi) {
                        is StrictSignedLowerBound,
                        is StrictSignedUpperBound,
                        is WeakSignedLowerBound,
                        is WeakSignedUpperBound,
                        is Qual -> null

                        is NumericRelation -> {
                            val c = pi.num ?: return@mapNotNull null
                            when(pi) {
                                is StrictEquality -> {
                                    StrictEquality(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                                is StrictInequality -> {
                                    StrictInequality(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                                is StrictLowerBound -> {
                                    StrictLowerBound(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                                is StrictUpperBound -> {
                                    StrictUpperBound(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                                is WeakLowerBound -> {
                                    WeakLowerBound(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                                is WeakUpperBound -> {
                                    WeakUpperBound(num = c + DEFAULT_SIGHASH_SIZE, sym = null)
                                }
                            }
                        }
                    }
                })
            }
            valueQualifiers?.filterIsInstance<IntQualifier.RemainingElementsProofFor>()?.takeIf {
                it.isNotEmpty()
            }?.let {
                val lowerBound = propagatedFacts.mapNotNull {
                    if(it is PathInformation.StrictLowerBound) {
                        (it.num ?: BigInteger.ZERO) + BigInteger.ONE
                    } else if(it is PathInformation.WeakLowerBound && it.num != null && it.num > BigInteger.ZERO) {
                        it.num
                    } else {
                        null
                    }
                }.maxByOrNull { it }
                if(lowerBound != null) {
                    it.forEach {
                        val vQual = additional.computeIfAbsent(it.indexVar) {
                            mutableListOf()
                        }
                        vQual.add(PathInformation.Qual(IntQualifier.ValidIndexOf(it.arrayVar)))
                        if(pointerAnalysis.getElementSize(it.arrayVar, pstate.pointsToState) == BigInteger.ONE) {
                            vQual.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(it.arrayVar, index = null)))
                        }
                        vQual.add(PathInformation.Qual(IntQualifier.RemainingBound(remaining = CanonicalSum(lowerBound.asTACSymbol()), arrayVar = it.arrayVar)))
                    }
                }
            }
            valueQualifiers?.filterIsInstance(IntQualifier.LengthOfArray::class.java)?.map { it.arrayVar }?.forEach { arrayVar ->
                val elemSize = pointerAnalysis.getElementSize(arrayVar, pState = pstate.pointsToState)
                for(bound in propagatedFacts) {
                    if(bound is PathInformation.StrictEquality && bound.num == BigInteger.ZERO) {
                        arrayHintPropagation.add(ArrayHints.MustBeEmpty(arrayVar))
                    } else if(bound is PathInformation.StrictLowerBound) {
                        arrayHintPropagation.add(ArrayHints.MustNotBeEmpty(arrayVar))
                    }

                    @Suppress("MandatoryBracesIfStatements")
                    if(bound is PathInformation.LowerBound) run boundHandling@{
                        val boundSymbol = bound.sym ?: return@boundHandling
                        val inducedProofs = pstate.invariants matches {
                            boundSymbol `=` v("safeIndex") + v("proof") {
                                it is LVar.PVar && (bound is PathInformation.StrictLowerBound ||
                                    ((pstate.boundsAnalysis[it.v] as? QualifiedInt)?.x?.getLowerBound()?.let {
                                        it > BigInteger.ZERO
                                    } == true))
                            }
                        }

                        /*
                           If we have that boundSymbol <= l (where l is the length of an array A),
                           and we have found a variable other where we have:

                           boundSymbol = other + off


                           where off > k, then we must have that other < l, i.e., other is a valid index into A.
                         */
                        val weakBounds = pstate.invariants matches {
                            boundSymbol `=` v("other") + k("off") {
                                it > BigInteger.ZERO
                            }
                        }

                        (inducedProofs.asSequence().map {
                            (it.symbols["safeIndex"] as LVar.PVar).v
                        } + weakBounds.asSequence().map {
                            (it.symbols["other"] as LVar.PVar).v
                        }).forEach {
                            val toQualify = it
                            val idxQuals = additional.computeIfAbsent(toQualify) {
                                mutableListOf()
                            }
                            idxQuals.add(PathInformation.Qual(IntQualifier.ValidIndexOf(arrayVar = arrayVar)))
                            if(elemSize == BigInteger.ONE) {
                                idxQuals.add(PathInformation.Qual(IntQualifier.ElementOffsetFor(arrayVar = arrayVar, index = null)))
                            }
                        }
                    }
                }
            }
            valueQualifiers?.forEach {
                if(it is IntQualifier.SafeArrayOffset) {
                    arrayHintPropagation.add(ArrayHints.MustNotBeEmpty(it.arrayVar))
                }
                if(it is IntQualifier.ElementOffsetFor) {
                    arrayHintPropagation.add(ArrayHints.MustNotBeEmpty(it.arrayVar))
                }
            }
            val safeProofs = propagatedFacts.filterIsInstance<PathInformation.Qual<IntQualifier>>().mapNotNull {
                it.q as? IntQualifier.ElementOffsetFor
            }
            if(safeProofs.isNotEmpty()) {
                /* find all variables x where x := v + 32 and v is an element offset for some array.
                 Annotate each such x to be a SafeArrayOffset for the same sets of arrays.
                 */
                val dataOffsetVars = pstate.invariants.matches {
                    v + 32 `=` v("base")
                }
                dataOffsetVars.forEach { m ->
                    safeProofs.forEach { elemOff ->
                        additional.computeIfAbsent((m.symbols["base"] as LVar.PVar).v) {
                            mutableListOf()
                        }.add(PathInformation.Qual(IntQualifier.SafeArrayOffset(arrayVar = elemOff.arrayVar, index = elemOff.index)))
                    }
                }
            }
        }
        val newState = st.mutate {
            applyPathInformation(it, propagation.pointwiseMerge(additional, List<PathInformation<IntQualifier>>::plus))
        }
        return NumericPathInformation(
                state = newState,
                arrayHints = arrayHintPropagation,
                pathInformation = propagation
        )
    }

    fun propagateTrue(st: NumericDomain, op1: TACSymbol.Var, pstate: PointsToDomain, target: LTACCmd): NumericPathInformation {
        return doPropagation(st, op1, pstate, target, propagationComputation::propagateTrue)
    }

    private fun applyPathInformation(it: MutableMap<TACSymbol.Var, IntDomain>, numPropagation: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>) {
        for ((k, v) in numPropagation) {
            val (lb, ub, quals) = PathApplication.computeSimplePathInfo(v)
            it.computeIfPresent(k) { _, currVal ->
                if(currVal !is QualifiedInt) {
                    currVal
                } else {
                    val newLv = if(lb != null || ub != null) {
                        if((lb != null && lb > currVal.x.getUpperBound()) || (ub != null && ub < currVal.x.getLowerBound())) {
                            throw PruneInfeasible()
                        }
                        IntValue.Interval(
                            lb = lb?.max(currVal.x.getLowerBound()) ?: currVal.x.getLowerBound(),
                            ub = ub?.min(currVal.x.getUpperBound()) ?: currVal.x.getUpperBound()
                        )
                    } else {
                        currVal.x
                    }
                    QualifiedInt(
                            newLv,
                            currVal.qual + quals
                    )
                }
            }
        }
    }

    fun propagateFalse(st: NumericDomain, op1: TACSymbol.Var, pstate: PointsToDomain, target: LTACCmd): NumericPathInformation {
        return doPropagation(st, op1, pstate, target, propagationComputation::propagateFalse)
    }

    fun canBeTrue(st: NumericDomain, op1: TACSymbol.Var, where: LTACCmd): Boolean =
        st.interpAsInt(op1, where).x.mayNotEqual(BigInteger.ZERO)

    fun canBeFalse(st: NumericDomain, op1: TACSymbol.Var, where: LTACCmd): Boolean =
        st.interpAsInt(op1, where).x.mayEqual(BigInteger.ZERO)

    fun endBlock(st: NumericDomain, where: LTACCmd, liveVars: Set<TACSymbol.Var>): TreapMap<TACSymbol.Var, IntDomain> {
        unused(liveVars)
        return st.updateValues { _, v ->
            if (v !is QualifiedInt) {
                v
            } else {
                val updatedQuals = treapSetBuilderOf<IntQualifier>()
                v.qual.flatMapTo(updatedQuals) {
                    it.saturateWith { v ->
                        mustAliasAnalysis.equivAfter(where.ptr, v)
                    }
                }
                v.copy(qual = updatedQuals.build())
            }
        }
    }

    fun startBlock(st: NumericDomain, liveVars: Set<TACSymbol.Var>, referencedFromLive: Set<TACSymbol.Var>) : NumericDomain =
        st.retainAllKeys {
            it in liveVars || it in referencedFromLive
        }

    companion object {
        val empty: NumericDomain = treapMapOf()
        val initial = treapMapOf<TACSymbol.Var, IntDomain>(TACKeyword.CALLDATASIZE.toVar() to QualifiedInt(
            IntValue.Nondet, treapSetOf(IntQualifier.CalldataSize)
        ), TACKeyword.RETURN_SIZE.toVar() to QualifiedInt(
            IntValue.Constant(BigInteger.ZERO), treapSetOf()
        ))
    }

    override fun writeSingleMemory(loc: TACSymbol, value: TACSymbol, target: NumericDomain, pState: PointsToDomain,
                                   ltacCmd: LTACCmd): NumericDomain = target


    override fun NumericDomain.lookup(sym: TACSymbol.Var): IntDomain? =
        this.get(sym)

    override fun pointerFor(abstractLocation: AllocationAnalysis.AbstractLocation): IntDomain =
        ANY_POINTER

    override fun liftInt(value: BigInteger): IntDomain = IntValue.Constant(value).liftToBounded()

    override fun liftAmbiguous(variableForSite: TypeVariable): IntDomain {
        val alwaysInConstantOffsetMode = allocSites.isEmpty()
        if(alwaysInConstantOffsetMode) {
            return UnresolvedValue(variableForSite).expectInt()
        }
        return UnresolvedValue(variableForSite)
    }

    override val scratch: IntDomain
        get() = ANY_POINTER
    override val untracked: IntDomain
        get() = AnyInt

    override fun IntDomain.maybeResolve(): IntDomain =
        this.tryResolve()

    private val loopInterpreter = object : LoopValueSummaryInterpreter<NumericDomain, IntDomain>() {
        override fun scratchIncrement(sourcePointer: TACSymbol.Var, increment: IntValue?, target: NumericDomain): IntDomain = ANY_POINTER

        override val havocInt: IntDomain
            get() = QualifiedInt(IntValue.Nondet, treapSetOf())

        override fun incrementPackedByte(it: TACSymbol.Var, increment: IntValue?, target: NumericDomain, init: InitializationPointer.ByteInitPointer): IntDomain = ANY_POINTER

    }

    fun consumeLoopSummary(s: Map<TACSymbol.Var, LoopCopyAnalysis.LoopValueSummary>, target: NumericDomain, p: PointsToDomain): NumericDomain {
        return target + loopInterpreter.interpretLoopSummary(s, target, p)
    }

    fun collectReferenced(state: NumericDomain, referencedFromLive: MutableSet<TACSymbol.Var>, lva: Set<TACSymbol.Var>) {
        for((k, v) in state) {
            if(k !in lva) {
                continue
            }
            if(v !is QualifiedInt) {
                continue
            }
            v.qual.forEach {
                it.collectReferenced(referencedFromLive)
            }
        }
    }

    fun propagateConstants(state: NumericDomain, constants: List<Pair<TACSymbol.Var, BigInteger>>) : NumericDomain {
        return constants.fold(state) { acc, (v, k) ->
            acc.update(v, AnyInt) {
                (it as? QualifiedInt)?.copy(x = IntValue.Constant(k)) ?: it
            }
        }
    }

    fun kill(n_: NumericDomain, killedBySideEffects: PointerSemantics.WriteSideEffect): NumericDomain {
        val killRelationalFacts = killedBySideEffects.killArrayLengthFacts + killedBySideEffects.killValue
        return n_.updateValues { p, abstractVal ->
            if(p in killedBySideEffects.killValue) {
                return@updateValues AnyInt
            }
            if(abstractVal !is QualifiedInt) {
                return@updateValues abstractVal
            }
            abstractVal.copy(
                qual = abstractVal.qual.retainAll { q ->
                    !q.relatesAny(killRelationalFacts)
                }
            )
        }
    }

    fun synthesizeState(
        boundsAnalysis: NumericDomain,
        alloc: SyntheticAlloc
    ): NumericDomain {
        return kill(boundsAnalysis, PointerSemantics.WriteSideEffect(alloc.mapToTreapSet { it.first }, treapSetOf())) + alloc.associate { it ->
            it.first to if(it.second is EVMTypeDescriptor.EVMReferenceType) {
                ANY_POINTER
            } else {
                QualifiedInt(IntValue.Nondet, treapSetOf())
            }
        }
    }
}

private fun NumericDomain.updateLoc(lhs: TACSymbol.Var, pointer: IntDomain): NumericDomain = this + (lhs to pointer)
