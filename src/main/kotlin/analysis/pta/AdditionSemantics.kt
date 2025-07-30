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

import analysis.ExprView
import analysis.PointsToInvariantViolation
import analysis.alloc.AllocationAnalysis
import analysis.numeric.CanonicalSum
import analysis.numeric.IntQualifier
import analysis.numeric.IntValue
import analysis.numeric.linear.LVar
import analysis.numeric.linear.LinearEquality
import analysis.numeric.linear.TermMatching.matches
import analysis.pta.abi.EncoderAnalysis.EncodingState.Companion.variablesEqualTo
import analysis.ptaInvariant
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.MAX_EVM_UINT256
import log.Logger
import log.LoggerTypes
import utils.*
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

private val logger = Logger(LoggerTypes.POINTS_TO)

private val BIGINT_32 = 0x20.toBigInteger()

/**
 * Encapsulates all of the rules outlined in: https://docs.google.com/document/d/1do2SqTEimBj-q3wZw2QRgQ-rw4sQkqOwE65F5Afs2Lk/edit
 *
 * In each of the actions `o1` is the pointer operand
 */
abstract class AdditionSemantics<S> {
    abstract val pointerAnalysis: IPointerInformation
    abstract val numericAnalysis: NumericAnalysis
    abstract val arrayStateAnalysis: ArrayStateAnalysis

    abstract val relaxedArrayAddition: Boolean

    fun addition(
            target: S,
            p: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
    ) = if(!where.exp.operandsAreSyms()) {
        nondeterministicInteger(where, p, target)
    } else {
        addition(where.exp.o1AsTACSymbol(), where.exp.o2AsTACSymbol(), target, p, where)
    }

    abstract fun nondeterministicInteger(where: ExprView<TACExpr.Vec.Add>, s: PointsToDomain, target: S) : S

    private fun addition(
        o1: TACSymbol,
        o2: TACSymbol,
        target: S,
        p: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ): S {
        if(o1 is TACSymbol.Const && o2 is TACSymbol.Const) {
            return additionConstant(o1.value, o2.value, o1, o2, target, p, where)
        }
        if(o1 is TACSymbol.Const) {
            check(o2 !is TACSymbol.Const)
            return addition(o2, o1, target, p, where)
        }
        if(o2 is TACSymbol.Const) {
            check(o1 !is TACSymbol.Const)
        }
        check(o1 is TACSymbol.Var)
        return addition(o1, o2, target, p, where)
    }

    private fun TACSymbol.isUnknownValue(p: PointsToDomain) = this is TACSymbol.Var && p.boundsAnalysis[this] == null

    private fun addition(
        o1: TACSymbol.Var,
        o2: TACSymbol,
        target: S,
        p: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ) : S {
        /* if the first operand isn't tracked by the boundsAnalysis, we're adding who knows what.
           All of our semantics bottom out at "dunno" for a totally non-deterministic operand so short-circuit now
         */
        if(p.boundsAnalysis[o1] == null) {
            return nondeterministicInteger(where = where, s = p, target = target)
        }
        // ditto with our right operand, if it's a variable and we don't know what it is, short-circuit
        if(o2.isUnknownValue(p)) {
            return nondeterministicInteger(where = where, s = p, target = target)
        }
        //  both have *some* valuation, let's start sorting this out.
        // pointers first
        // This non-null assertion is safe by the first check in the function
        val i1 = p.boundsAnalysis[o1]!!.tryResolve()
        val i2 = numericAnalysis.interpSymbol(where.wrapped, p.boundsAnalysis, o2).tryResolve()
        /*
          If i1 may not be a pointer, and i2 is a definitely pointer...

          This is the "swap" branch
         */
        if(i1 !is ANY_POINTER && i2 is ANY_POINTER) {
            return when(o2) {
                /*
                   If our left operand is a variable, then swap, our pointers come first (remember, i1 is definitely not a pointer).

                   This is guaranteed to skip this branch within the recursion because we know that i2 is a pointer, so when we
                   re-enter, we will must have that i1 is ANY_POINTER.
                 */
                is TACSymbol.Var -> addition(o2, o1, target, p, where)
                is TACSymbol.Const -> {
                    /* Then i2 is a pointer that's a constant. The only way that makes sense if this is the constant 0x60, i.e.,
                        the empty array. But adding basically anything to the definitely empty pointer is meaningless, so we
                        short-circuit here
                     */
                    nondeterministicInteger(where, s = p, target = target)
                }
            }
        }
        // arbitrary pointer arithmetic makes no sense, i1 and i2 are both definitely pointers
        if(i1 is ANY_POINTER && i2 is ANY_POINTER) {
            return nondeterministicInteger(where, p, target)
        }
        if(i1 is UnresolvedValue) {
            /*
              So this i1 should be treated an integer. Does this help us resolve our other operand?

              If i2 is unresolved, then because it never makes sense to add to an empty array, let's assume
              the other operand is also an integer.
             */
            i1.expectInt()
            /*
              Why do we know this? If we had i1 was an unresolved value, we would have i1 !is ANY_POINTER,
              and i2 would be ANY_POINTER, so the above "swap" branch would have been taken
             */
            check(i2 !is ANY_POINTER)
            if(i2 is UnresolvedValue) {
                i2.expectInt()
            }
            /*
              Within this call, o1 and o2 should both resolve to integers
             */
            return arithmeticAddition(o1, o2, target, p, where)
        }
        /*
        If i1 is a qualified int, then i2 must be an unresolved constant or int. Why can't i2 be ANY_POINTER? Then we would
        have taking the "swapping" branch above. so we should say that i2 is expected to be an int
        */
        if(i1 is QualifiedInt) {
            i2.expectInt()
            return arithmeticAddition(o1, o2, target, p, where)
        }
        /*
           What if i2 is a qualifiedint and i1 is not an ANY_POINTER?
           Well, if i1 was a Qualified int, we would take the above branch.
           if i1 was an unresolved value, then we could have taken the branch before.

           In other words, i1 must now be ANY_POINTER. We further know that i2 is not an ANY_POINTER
           because then we would have taken the "arbitrary pointer addition" branch.
         */
        check(i1 is ANY_POINTER && i2 !is ANY_POINTER)
        /*
          So we must have pointer + number
         */
        val av1 = p.pointsToState.store[o1]!!.tryResolvePointer()
        val resolvedI2 = i2.expectInt()
        val aSt1 = p.arrayState[o1]?.tryResolve()
        fun toElementPointer(
            arrayElemSize: BigInteger?,
            arrayVars: Set<TACSymbol.Var>,
            av1: Set<ArrayAbstractLocation.A>,
            untilEnd: Set<CanonicalSum> = setOf(),
            indexVars: Set<TACSymbol.Var> = setOf()
        ): S {
            return if (resolvedI2.x.getUpperBound() != MAX_EVM_UINT256 && arrayElemSize != null) {
                val indexLb = resolvedI2.x.lb / arrayElemSize
                val indexUb = resolvedI2.x.ub / arrayElemSize
                toArrayElementPointer(
                    av1,
                    arrayVars,
                    IntValue(indexLb, indexUb),
                    untilEnd = untilEnd,
                    target = target,
                    whole = p,
                    where = where,
                    indexVar = indexVars
                )
                // case 2.a.ii
            } else {
                toArrayElementPointer(
                    av1,
                    arrayVars,
                    null,
                    untilEnd = untilEnd,
                    target = target,
                    whole = p,
                    where = where,
                    indexVar = indexVars
                )
            }
        }

        // (o1 is Pointer) + 0 == o1
        if(numericAnalysis.interpAsConstant(p, where.wrapped, o2) == BigInteger.ZERO) {
            return toIdentityPointer(o1, target, p, where)
        }
        return when(av1) {
            is NullablePointer,
            is INT -> throw PointsToInvariantViolation("We had that numeric thought $o1 was a pointer but pointer thinks it's an int")
            is ScratchPointer -> this.scratchPointerAddition(o1, o2, resolvedI2.x, target, p, where)
            /*
              Let's get the really hard ones outta the way.

              The following three cases are covered in https://docs.google.com/document/d/1do2SqTEimBj-q3wZw2QRgQ-rw4sQkqOwE65F5Afs2Lk/edit
             */
            is Pointer.ArrayPointer -> {
                val arrayElemSize = pointerAnalysis.getElementSize(o1, p.pointsToState)
                check(aSt1 != null && aSt1 is ArrayStateAnalysis.Value.ArrayBasePointer)
                if(resolvedI2.x.isConstant && resolvedI2.x.c == BIGINT_32 && resolvedI2.qual.none {
                    it is IntQualifier.SafeArrayOffset && it.arrayVar == o1
                    }) {
                    toArrayElemStartPointer(av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(), o1, target, p, where)
                /*
                   case 1.b
                 */
                } else if(resolvedI2.qual.any { it is IntQualifier.SafeArrayOffset && it.arrayVar == o1 }) {
                    val indices = resolvedI2.qual.mapNotNullTo(mutableSetOf()) {
                        (it as? IntQualifier.SafeArrayOffset)?.takeIf {
                            it.arrayVar == o1
                        }?.index
                    }
                    /*
                      Case 1.b.i
                     */
                    if (resolvedI2.x.ub != MAX_EVM_UINT256 && arrayElemSize != null) {
                        val lowerBound = resolvedI2.x.getLowerBound().takeIf { it >= BIGINT_32 }?.let {
                            (it - BIGINT_32) / arrayElemSize
                        } ?: BigInteger.ZERO
                        val upperBound = (resolvedI2.x.getUpperBound() - BIGINT_32) / arrayElemSize
                        toArrayElementPointer(
                            av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(),
                            setOf(o1),
                            IntValue.Interval(lowerBound, upperBound),
                            indexVar = indices,
                            target = target,
                            whole = p,
                            where = where
                        )
                        /*
                       Case 1.b.ii
                     */
                    } else {
                        toArrayElementPointer(
                            av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(),
                            setOf(o1),
                            null,
                            target = target,
                            whole = p,
                            where = where,
                            indexVar = indices
                        )
                    }
                /**
                 * See if this value is safe to add to the base pointer of an array
                 */
                } else if(
                    arrayElemSize != null &&
                    isSafeRawAddition(resolvedI2, o1, arrayElemSize) &&
                    isArrayStep(resolvedI2, arrayElemSize)
                ) {
                    val lowerBound = (resolvedI2.x.getLowerBound() - BIGINT_32) / arrayElemSize
                    val upperBound = (resolvedI2.x.getUpperBound() - BIGINT_32) / arrayElemSize
                    toArrayElementPointer(
                        av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(),
                        setOf(o1),
                        IntValue.Interval(lowerBound, upperBound),
                        target = target,
                        whole = p,
                        where = where
                    )
                /*
                   case 1.c
                 */
                } else if(resolvedI2.x.getUpperBound() != MAX_EVM_UINT256 &&
                    arrayElemSize != null &&
                    resolvedI2.x.getLowerBound() >= BIGINT_32 &&
                    (p.indexProvablyWithinArrayBounds(
                            (resolvedI2.x.getUpperBound() - BIGINT_32) / arrayElemSize,
                            o1
                        )) &&
                    isArrayStep(resolvedI2, arrayElemSize)) {
                    val lowerBound = (resolvedI2.x.getLowerBound() - BIGINT_32) / arrayElemSize
                    val upperBound = (resolvedI2.x.getUpperBound() - BIGINT_32) / arrayElemSize
                    toArrayElementPointer(
                        av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(),
                        setOf(o1),
                        IntValue.Interval(lowerBound, upperBound),
                        target = target,
                        whole = p,
                        where = where
                    )
                /*
                   case 1.d
                 */
                } else if(resolvedI2.qual.contains(IntQualifier.SizeOfArrayBlock(o1))) {
                    toEndSegment(setOf(o1), o1, target, p, where)
                /*
                   case 1.e
                 */
                } else if(relaxedArrayAddition && arrayElemSize == BigInteger.ONE && resolvedI2.x.getLowerBound() >= BIGINT_32) {
                    val filtered = av1.v.filterIsInstance<ArrayAbstractLocation.A>().takeIf { it.isNotEmpty() }?.toSet() ?: return nondeterministicInteger(where, p, target)
                    toArrayElementPointer(
                        v = filtered,
                        where = where,
                        index = IntValue.Nondet,
                        target = target,
                        indexVar = setOf(),
                        whole = p,
                        untilEnd = setOf(),
                        basePointers = setOf(o1)
                    )

                } else if(arrayElemSize != null && isArrayStep(resolvedI2, arrayElemSize) && resolvedI2.x.lb >= BIGINT_32) {
                    val lowerBound = (resolvedI2.x.getLowerBound() - BIGINT_32) / arrayElemSize
                    val upperBound = (resolvedI2.x.getUpperBound() - BIGINT_32) / arrayElemSize
                    toAddedElemPointer(
                        setOf(o1),
                        av1.v.filterIsInstance(ArrayAbstractLocation.A::class.java).toSet(),
                        null,
                        o2,
                        IntValue.Constant(BigInteger.ZERO),
                        IntValue.Interval(lowerBound, upperBound),
                        setOf(),
                        target,
                        p,
                        where
                    )
                /*
                   case 1.f
                 */
                } else {
                    nondeterministicInteger(where, target = target, s = p)
                }
            }
            is Pointer.ArrayElemStart -> {
                check(aSt1 != null && aSt1 is ArrayStateAnalysis.Value.ElementPointer && aSt1.index == IntValue.Constant(BigInteger.ZERO))
                val arrayVars = aSt1.arrayPtr
                val arrayElemSize = getSingleArrayElemSize(arrayVars, p)
                // case 2.a
                if(resolvedI2.qual.any {
                            it is IntQualifier.ElementOffsetFor && it.arrayVar in arrayVars
                        }) {
                    val indices = setOfNotNull((o2 as? TACSymbol.Var).takeIf {
                        resolvedI2.qual.any {
                            it is IntQualifier.ValidIndexOf && it.arrayVar in arrayVars
                        }
                    }) + resolvedI2.qual.mapNotNull {
                        (it as? IntQualifier.ElementOffsetFor)?.takeIf {
                            it.arrayVar in arrayVars
                        }?.index
                    }
                    // case 2.a.i
                    toElementPointer(arrayElemSize, arrayVars, av1.v, untilEnd = resolvedI2.qual.mapNotNullTo(mutableSetOf()) {
                        (it as? IntQualifier.RemainingBound)?.takeIf { b ->
                            b.arrayVar in arrayVars
                        }?.remaining
                    }, indexVars = indices)
                // case 2.b
                } else if(resolvedI2.x.getUpperBound() != MAX_EVM_UINT256 && arrayElemSize != null && arrayVars.any {
                        p.indexProvablyWithinArrayBounds(
                                resolvedI2.x.getUpperBound() / arrayElemSize,
                                it
                            )
                    } && isArrayStep(resolvedI2, arrayElemSize)) {
                    val indexUb = resolvedI2.x.ub / arrayElemSize
                    val indexLb = resolvedI2.x.lb / arrayElemSize
                    toArrayElementPointer(
                        av1.v,
                        arrayVars,
                        IntValue.Interval(indexLb, indexUb),
                        target = target,
                        whole = p,
                        where = where,
                        indexVar = if(indexLb == indexUb) {
                            p.variablesEqualTo(indexLb)
                        } else {
                            setOf()
                        }
                    )
                // case 2.c
                } else if(resolvedI2.qual.any {
                            it is IntQualifier.SizeOfElementSegment && it.arrayVar in arrayVars
                        }) {
                    toEndSegment(arrayVars, o1, target, p, where)
                } else if(relaxedArrayAddition && arrayElemSize == BigInteger.ONE) {
                    toArrayElementPointer(
                        av1.v,
                        basePointers = aSt1.arrayPtr,
                        where = where,
                        target = target,
                        indexVar = setOf(),
                        untilEnd = setOf(),
                        index = IntValue.Nondet,
                        whole = p
                    )
                // case 2.d
                } else if(arrayElemSize != null && isArrayStep(resolvedI2, arrayElemSize)) {
                    val indexUb = resolvedI2.x.ub / arrayElemSize
                    val indexLb = resolvedI2.x.lb / arrayElemSize
                    toAddedElemPointer(arrayVars, av1.v, o1, o2, IntValue.Constant(BigInteger.ZERO), IntValue.Interval(indexLb, indexUb), setOf(), target, p, where)
                // case 2.e
                } else {
                    nondeterministicInteger(where, p, target)
                }
            }
            // case 3
            is Pointer.ArrayElemPointer -> {
                check(aSt1 != null && aSt1 is ArrayStateAnalysis.Value.ElementPointer)
                val arrayElemSize = getSingleArrayElemSize(aSt1.arrayPtr, p)
                if(aSt1.index == IntValue.Constant(BigInteger.ZERO) && resolvedI2.qual.any {
                            it is IntQualifier.ElementOffsetFor && it.arrayVar in aSt1.arrayPtr
                        }) {
                    toElementPointer(arrayElemSize, aSt1.arrayPtr, av1.v, indexVars = resolvedI2.qual.mapNotNullTo(
                        mutableSetOf()
                    ) {
                        (it as? IntQualifier.ElementOffsetFor)?.index
                    })
                    // case 3.b
                } else if(resolvedI2.x.ub != MAX_EVM_UINT256 &&
                        aSt1.index.ub != MAX_EVM_UINT256 &&
                        arrayElemSize != null && isArrayStep(resolvedI2, arrayElemSize) &&
                        (resolvedI2.x.ub / arrayElemSize) + aSt1.index.ub <= MAX_EVM_UINT256 &&
                        aSt1.arrayPtr.any {
                            p
                                .indexProvablyWithinArrayBounds(
                                    (resolvedI2.x.ub / arrayElemSize) + aSt1.index.ub,
                                    it
                                )
                        }) {
                    val indexLb = resolvedI2.x.lb / arrayElemSize + aSt1.index.lb
                    val indexUb = resolvedI2.x.ub / arrayElemSize + aSt1.index.ub
                    toArrayElementPointer(
                        v = av1.v,
                        basePointers = aSt1.arrayPtr,
                        index = IntValue.Interval(
                            indexLb,
                            indexUb
                        ),
                        target = target,
                        whole = p,
                        where = where,
                        indexVar = if(indexLb == indexUb) {
                            p.variablesEqualTo(indexLb)
                        } else {
                            setOf()
                        }
                    )
                } else if(aSt1.untilEnd.any {
                            it.providesStrongBound(o2)
                        }) {
                    toArrayElementPointer(
                        v = av1.v,
                        basePointers = aSt1.arrayPtr,
                        index = if(i2 is QualifiedInt) {
                            aSt1.index.add(i2.x).first
                        } else {
                            IntValue.Interval(lb = aSt1.index.lb)
                        },
                        untilEnd = aSt1.untilEnd.mapNotNull {
                            it - o2
                        }.toSet(),
                        target = target,
                        whole = p,
                        where = where,
                        indexVar = (resolvedI2 as? QualifiedInt)?.x?.takeIf {
                            it.isConstant
                        }?.c?.let { step ->
                            val indexStep = step.div(arrayElemSize ?: return@let null)
                            if(aSt1.indexVars.isEmpty()) {
                                return@let null
                            }
                            val indexVars = aSt1.indexVars.toTreapSet()
                            val out = mutableSetOf<TACSymbol.Var>()
                            for(ie in p.invariants) {
                                if(!ie.relatesAny(indexVars)) {
                                    continue
                                }
                                if(ie.term.size != 2) {
                                    continue
                                }
                                if(ie.k.abs() != indexStep) {
                                    continue
                                }
                                val (curr, other) = ie.term.keys.partition {
                                    (it as LVar.PVar).v in aSt1.indexVars
                                }
                                if(other.size != 1 && curr.size != 1) {
                                    continue
                                }
                                if(ie.canonicalize() == LinearEquality(
                                        term = treapMapOf(
                                            other.single() to -BigInteger.ONE,
                                            curr.single() to BigInteger.ONE
                                        ), k = indexStep
                                ).canonicalize()) {
                                    out.add((other.single() as LVar.PVar).v)
                                }
                            }
                            out
                        } ?: setOf()
                    )
                } else if(relaxedArrayAddition && arrayElemSize == BigInteger.ONE) {
                    toArrayElementPointer(
                        av1.v,
                        basePointers = aSt1.arrayPtr,
                        where = where,
                        target = target,
                        indexVar = setOf(),
                        untilEnd = setOf(),
                        index = IntValue.Nondet,
                        whole = p
                    )
                // case 3.d
                } else if(arrayElemSize != null && isArrayStep(resolvedI2, arrayElemSize)) {
                    val addLb = resolvedI2.x.getLowerBound() / arrayElemSize
                    val addUb = resolvedI2.x.getUpperBound() / arrayElemSize
                    val canon = aSt1.untilEnd.mapNotNull {
                        it - o2
                    }.toSet()
                    toAddedElemPointer(
                        aSt1.arrayPtr,
                        av1.v,
                        o1,
                        o2,
                        aSt1.index,
                        IntValue.Interval(addLb, addUb),
                        canon,
                        target,
                        p,
                        where
                    )
                // case 3.d
                } else {
                    nondeterministicInteger(where, p, target)
                }
            }

            is Pointer.BlockPointerBase -> {
                val op2Constant = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(op2Constant == EVM_WORD_SIZE && av1.blockAddr.none {
                            p.pointsToState.h[it]?.block?.mustNotBeEmptyArray != false
                        }) {
                    toEmptyDataSegment(target, p, where)
                } else if (op2Constant != null && op2Constant < av1.blockSize && PointerSemantics.isAlignedBlockIndex(op2Constant)) {
                    toBlockElemPointer(av1.blockAddr, op2Constant, av1.blockSize, o1, target, p, where)
                } else if (o2 is TACSymbol.Var && numericAnalysis.isSafeMultiplierFor(p, av1.blockSize, o2) &&
                        isTupleSafe(av1, p.pointsToState.h)) {
                    toConstArrayElemPointer(av1.blockAddr, o1, target, p, where, ConstArraySafetyProof.OffsetFromBase(o2))
                } else {
                    nondeterministicInteger(where, p, target)
                }
            }
            is Pointer.BlockPointerBaseAmbiguous -> {
                val op2Constant = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(op2Constant == EVM_WORD_SIZE && av1.blockAddr.none {
                        p.pointsToState.h[it]?.block?.mustNotBeEmptyArray != false
                    }) {
                    toEmptyDataSegment(target, p, where)
                } else if (op2Constant != null && op2Constant < av1.blockSize && PointerSemantics.isAlignedBlockIndex(op2Constant)) {
                    toBlockElemPointer(av1.blockAddr, op2Constant, av1.blockSize, o1, target, p, where)
                } else if (o2 is TACSymbol.Var && numericAnalysis.isSafeMultiplierFor(p, av1.blockSize, o2) &&
                    isTupleSafe(av1, p.pointsToState.h)) {
                    toConstArrayElemPointer(av1.blockAddr, o1, target, p, where, ConstArraySafetyProof.OffsetFromBase(o2))
                } else {
                    nondeterministicInteger(where, p, target)
                }
            }
            is Pointer.BlockPointerField -> {
                val op2Constant = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(op2Constant == null || op2Constant.mod(32.toBigInteger()) != BigInteger.ZERO || op2Constant + av1.offset >= av1.blockSize) {
                    nondeterministicInteger(where,  p, target)
                } else {
                    toBlockElemPointer(av1.blockAddr, op2Constant + av1.offset, av1.blockSize, o1, target, p, where)
                }
            }
            is Pointer.ConstSizeArrayElemPointer -> {
                val op2Constant = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(op2Constant != EVM_WORD_SIZE) {
                    nondeterministicInteger(where, p, target)
                } else {
                    val fallback by lazy {
                        toAddedConstArrayElemPointer(av1.v, o1, target, p, where)
                    }

                    /**
                     * If we have that `o1 + 32` where `o1` is a constant array element with index `i`, and we have
                     * that there exists some `j = i + 1` where `j < const-array-length`, then `o1 + 32` yields
                     * a constant array element (with index `j`).
                     *
                     * First, infer the length of the constant array from the size of the block in the heap.
                     * This is not a great way to do this, but we don't include the block size in const elem pointers
                     * for some reason...
                     */
                    val blockSize = av1.blockAddr.monadicMap {
                        p.pointsToState.h[it]?.block?.sz
                    }?.sameValueOrNull() ?: return fallback

                    /**
                     * Get the current abstraction of `o1` as a constant array elem. NB we don't actually check the sort
                     * here, but the **assumed** coherence of the abstract domains means the sort should be right...
                     */
                    val currOp = p.structState[o1] ?: return fallback

                    /**
                     * Find the `j = i + 1` where `j < blockSize / EVM_WORD_SIZE`.
                     */
                    val safeIndices = currOp.indexVars.let { currIndex ->
                        (p.invariants matches {
                            v("i") { v -> v is LVar.PVar && v.v in currIndex } + 1 `=` v("j") {
                                it is LVar.PVar && (p.boundsAnalysis[it.v] as? QualifiedInt)?.x?.ub?.let { ub ->
                                    /**
                                     * blockSize / EVM_WORD_SIZE is the constant array length, so this
                                     * means j < constantArrayLength
                                     */
                                    ub < (blockSize / EVM_WORD_SIZE)
                                } == true
                            }
                        }).mapToSet {
                            (it.symbols["j"] as LVar.PVar).v
                        }
                    }
                    /**
                     * No index found, fall back on "couldn't prove safe" representation via [toAddedConstArrayElemPointer]
                     */
                    if(safeIndices.isEmpty()) {
                        return fallback
                    }
                    toConstArrayElemPointer(
                        blockBase = currOp.base,
                        target = target,
                        v = av1.v,
                        where = where,
                        whole = p,
                        indexingProof = ConstArraySafetyProof.IndexOfNew(safeIndices)
                    )
                }

            }
            is InitializationPointer.ArrayInitPointer -> {
                val x = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(av1.mustAdded && aSt1 is ArrayStateAnalysis.Value.ElementPointer && aSt1.index.mustEqual(BigInteger.ZERO) && resolvedI2.qual.any {
                            it is IntQualifier.SizeOfElementSegment && it.arrayVar in aSt1.arrayPtr
                        }) {
                    toEndSegment(
                            startElem = aSt1.arrayPtr,
                            where = where,
                            whole = p,
                            target = target,
                            o1 = o1
                    )
                } else if(!av1.mayAdded && x != 0x20.toBigInteger()) {
                    nondeterministicInteger(where, p, target)
                } else {
                    arrayInitAddition(av1, x, o1, target, p, where)
                }
            }
            is InitializationPointer.BlockInitPointer -> {
                val o2Offs = numericAnalysis.interpSymbol(where.wrapped, st = p.boundsAnalysis, sym = o2)
                if(o2Offs is QualifiedInt && IntQualifier.MultipleOf(EVM_WORD_SIZE) in o2Offs.qual) {
                    val blockSize = p.pointsToState.h.blockSpace[av1.v]?.block?.sz
                    if(blockSize != null && o2Offs.x.ub + av1.offset < blockSize) {
                        return toStaticArrayInitPointer(
                            av1, o1, target, where = where, whole = p
                        )
                    }
                }
                val x = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if (x == null) {
                    nondeterministicInteger(where, p, target)
                } else {
                    val offs = x + av1.offset
                    if(offs >= (av1.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz) {
                        nondeterministicInteger(where, p, target)
                    } else {
                        blockInitAddition(av1, o1, offs, target, p, where)
                    }
                }
            }
            is InitializationPointer.ByteInitPointer -> {
                val x = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                /* 0x24 for encodeWithSig looool */
                if(!av1.mayAdded && resolvedI2.qual.any {
                            it is IntQualifier.LengthOfArray && it.arrayVar == o1
                        }) {
                    byteLengthAddition(av1, o1, target, p, where)
                } else if(!av1.mayAdded && (x == null || x < 0x20.toBigInteger())) {
                    nondeterministicInteger(where, p, target)
                } else if(av1.mustAdded && (aSt1 as? ArrayStateAnalysis.Value.ElementPointer)?.index?.mustEqual(BigInteger.ZERO) == true &&
                                resolvedI2.qual.any {
                                    it is IntQualifier.LengthOfArray && it.arrayVar in aSt1.arrayPtr
                                }) {
                    toEndSegment(
                            startElem = aSt1.arrayPtr,
                            where = where,
                            whole = p,
                            target = target,
                            o1 = o1
                    )
                } else {
                    byteInitAddition(av1, (i2 as? QualifiedInt)?.x ?: IntValue.Nondet, o1, target, p, where)
                }
            }
            is InitializationPointer.StaticArrayInitPointer -> {
                val op2Constant = numericAnalysis.interpAsConstant(p, where.wrapped, o2)
                if(op2Constant == 32.toBigInteger()) {
                    toAddedStaticArrayInitPointer(av1, o1, target, p, where)
                } else {
                    nondeterministicInteger(where, p, target)
                }
            }
        }
    }
    /**
     * For Byte arrays:
     * If we have 32 <= resolvedI2 <= v where v is LengthOf(o1), then there must exist some value y such that
     * y + 32 = resolvedI2 and thus y < LengthOf(a). But then we have that y is a SafeIndexOf(o1), which means
     * that `o1 + 32 + y` is safe, and thus `a + resolvedI2` is also safe.
     *
     * For word size arrays:
     * Same reasoning but size of array block instead of LengthOf.
     */
    private fun isSafeRawAddition(resolvedI2: QualifiedInt, o1: TACSymbol.Var, elemSize: BigInteger): Boolean {
        return resolvedI2.x.lb >= EVM_WORD_SIZE && resolvedI2.qual.any {
            it is IntQualifier.HasUpperBound && (
                (elemSize == BigInteger.ONE && (it.other == IntQualifier.LengthOfArray(o1) || it.other == IntQualifier.SizeOfArrayBlock(o1))) ||
                    (elemSize == EVM_WORD_SIZE && it.other == IntQualifier.SizeOfArrayBlock(o1))
            )
        }
    }

    open fun toAddedConstArrayElemPointer(v: Set<L>, o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S {
        return nondeterministicInteger(where = where, s = whole, target = target)
    }

    abstract fun toStaticArrayInitPointer(
        av1: InitializationPointer.BlockInitPointer,
        o1: TACSymbol.Var,
        target: S,
        whole: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ) : S

    abstract fun toAddedStaticArrayInitPointer(
        av1: InitializationPointer.StaticArrayInitPointer,
        o1: TACSymbol.Var,
        target: S,
        whole: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>,
    ): S

    open fun byteLengthAddition(av1: InitializationPointer.ByteInitPointer, o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S {
        return nondeterministicInteger(where, s = whole, target = target)
    }

    abstract fun toEmptyDataSegment(target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S

    private fun getSingleArrayElemSize(arrayVars: Set<TACSymbol.Var>, p: PointsToDomain): BigInteger? {
        return arrayVars.monadicMap {
            pointerAnalysis.getElementSize(it, p.pointsToState)
        }?.monadicFold { a, b ->
            if (a != b) {
                null
            } else {
                a
            }
        }
    }

    private fun isArrayStep(resolvedI2: QualifiedInt, arrayElemSize: BigInteger): Boolean =
        arrayElemSize == BigInteger.ONE || (resolvedI2.x.isConstant && resolvedI2.x.c.mod(arrayElemSize) == BigInteger.ZERO) ||
                resolvedI2.qual.any {
                    it is IntQualifier.MultipleOf && it.factor.mod(arrayElemSize) == BigInteger.ZERO
                }

    /**
     * Give an abstract representation of the value representing the end of memory allocated for the arrays with (aliased) base pointers [startElem]
     */
    abstract fun toEndSegment(startElem: Set<TACSymbol.Var>, o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S

    /**
     * Add [amountAdded] to the byte initialization pointer [av1]
     */
    abstract fun byteInitAddition(av1: InitializationPointer.ByteInitPointer, amountAdded: IntValue, o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S

    /**
     * Add [newOffset] to the block initialization pointer [av1]
     */
    abstract fun blockInitAddition(av1: InitializationPointer.BlockInitPointer, o1: TACSymbol.Var, newOffset: BigInteger, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S

    /**
     * Add [x] to the array initialization pointer [av1]
     */
    abstract fun arrayInitAddition(av1: InitializationPointer.ArrayInitPointer, x: BigInteger?, o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): S

    /**
     * Assign a value given by incrementing an element pointer for the arrays with address [v] and base pointers [arrayBase].
     *
     * The [currIndex] gives a hint about the current index being added to, and [addAmount] indicates the nubmer
     * of logical elements being added
     *
     * These added elem pointers are *not* known to be safe, further post hoc reasoning must be done!
     */
    abstract fun toAddedElemPointer(
        arrayBase: Set<TACSymbol.Var>,
        v: Set<ArrayAbstractLocation.A>,
        o1: TACSymbol.Var?,
        addOperand: TACSymbol,
        currIndex: IntValue,
        addAmount: IntValue,
        untilEnd: Set<CanonicalSum>,
        target: S,
        p: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ): S

    /**
     * Assign a pointer representing the beginning of the data segment for the arrays [v] with base pointer [o1].
     *
     * This pointer is *not* necessarily safe for reading!
     */
    abstract fun toArrayElemStartPointer(
        v: Set<ArrayAbstractLocation.A>,
        o1: TACSymbol.Var,
        target: S,
        whole: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ) : S

    /**
     * Assign a pointer for an element of the arrays [v] (with base pointers in [basePointers]) with the optional interval
     * on the LOGICAL index into the array
     */
    abstract fun toArrayElementPointer(
        v: Set<ArrayAbstractLocation.A>,
        basePointers: Set<TACSymbol.Var>,
        index: IntValue?,
        indexVar: Set<TACSymbol.Var> = setOf(),
        untilEnd: Set<CanonicalSum> = setOf(),
        target: S,
        whole: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>
    ) : S

    /**
     * Representation of why we think a constant array element addition is safe.
     */
    sealed interface ConstArraySafetyProof {
        /**
         * We are adding [x] to the base of the struct, where [x] is known to be
         * within bounds of the static array (and also word aligned)
         */
        data class OffsetFromBase(val x: TACSymbol.Var) : ConstArraySafetyProof

        /**
         * We found indices held in [idx] which witness the safety of the addition (and which
         * represent the index of the newly created constant array element).
         */
        data class IndexOfNew(
            val idx: Set<TACSymbol.Var>
        ) : ConstArraySafetyProof
    }

    /**
     * Assign a pointer for *any* field in the block pointers [v] whose value is stored in [blockBase]
     */
    abstract fun toConstArrayElemPointer(
        v: Set<L>,
        blockBase: TACSymbol.Var,
        target: S,
        whole: PointsToDomain,
        where: ExprView<TACExpr.Vec.Add>,
        indexingProof: ConstArraySafetyProof
    ) : S

    /**
     * Assign a pointer for the field with offset [offset] within the block [av1] stored in [op1]
     */
    abstract fun toBlockElemPointer(av1: Set<L>, offset: BigInteger, blockSize: BigInteger, op1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>) : S

    /**
     * Assign a pointer identical to the input pointer
     */
    abstract fun toIdentityPointer(o1: TACSymbol.Var, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>) : S

    /**
     * Add the numeric interpretation of [o2] to the scratch pointer stored in [o1]
     */
    abstract fun scratchPointerAddition(
            o1: TACSymbol.Var,
            o2: TACSymbol,
            offsetAmount: IntValue,
            target: S,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
    ) : S

    /**
     * Adds the (numeric) interpretation of [o2] to the numeric value stored in [o2]
     */
    abstract fun arithmeticAddition(
            o1: TACSymbol.Var,
            o2: TACSymbol,
            target: S,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
    ): S

    /**
     * Add two constants [c1] and [c2] together
     */
    abstract fun additionConstant(c1: BigInteger, c2: BigInteger, o1: TACSymbol.Const, o2: TACSymbol.Const, target: S, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>) : S

    private fun handleTupleSafe(blockAddr: Set<L>, h: AbstractHeap): Boolean {
        blockAddr.map { h[it]!!.block.fieldTypes.toMap(h) }.let { list ->
            ptaInvariant(list.isNotEmpty()) {
                "pointer has no addresses in the points to set"
            }
            val st = list.first()
            for (i in 1..list.lastIndex) {
                val m = list[i]
                if (st.keys != m.keys) {
                    logger.warn { "Found block pointers with disjoint domains $st and $m" }
                    return false
                }
                val cons = m.all { (k, v) ->
                    val ret = v.checkCompatibility(st[k]!!)
                    if (!ret) {
                        logger.warn { "Found type incompatibility at key $k with types $v and ${st[k]!!}" }
                    }
                    ret
                }
                if (!cons) {
                    return false
                }
            }
            return true
        }
    }

    private fun isTupleSafe(av1: Pointer.BlockPointerBase, h: AbstractHeap): Boolean = handleTupleSafe(av1.blockAddr, h)
    private fun isTupleSafe(av1: Pointer.BlockPointerBaseAmbiguous, h: AbstractHeap): Boolean = handleTupleSafe(av1.blockAddr, h)
}
