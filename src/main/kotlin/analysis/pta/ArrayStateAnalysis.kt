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
import analysis.numeric.linear.LVar
import analysis.numeric.linear.TermMatching.matches
import analysis.pta.abi.DecoderAnalysis
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.MAX_EVM_UINT256
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.*

typealias ArrayStateDomain = TreapMap<TACSymbol.Var, ArrayStateAnalysis.Value>

internal fun PointsToDomain.indexProvablyWithinBounds(
    ind: BigInteger,
    elemPointerLoc: TACSymbol.Var
) : Boolean {
    val elem = this.arrayState[elemPointerLoc] as? ArrayStateAnalysis.Value.ElementPointer ?: return false
    /*
     Array pointers are must information
     */
    return elem.arrayPtr.any {
        this.indexProvablyWithinArrayBounds(ind, arrayPointerLoc = it)
    }
}

internal fun PointsToDomain.indexProvablyWithinArrayBounds(
    ind: BigInteger,
    arrayPointerLoc: TACSymbol.Var
) : Boolean {
    return (this.arrayState[arrayPointerLoc] as? ArrayStateAnalysis.Value.ArrayBasePointer)?.logicalLength?.isDefinitelyGreaterThan(ind) == true ||
        (this.pointsToState.store[arrayPointerLoc] as? Pointer.ArrayPointer)?.v?.all {
            it is ArrayAbstractLocation.A && it.addr is AllocationSite.Explicit &&
                ((it.addr.alloc.sort is AllocationAnalysis.Alloc.ConstantStringAlloc && it.addr.alloc.sort.constLen > ind) ||
                    (it.addr.alloc.sort is AllocationAnalysis.Alloc.ConstantArrayAlloc && it.addr.alloc.sort.constSize > ind))

        }  == true
}

private fun ArrayStateDomain.upperBoundOperator(
        other: ArrayStateDomain,
        ivUB: (IntValue, IntValue) -> IntValue,
        minBoundUB: (BigInteger, BigInteger) -> BigInteger
): ArrayStateDomain {
    val toRet = treapMapBuilderOf<TACSymbol.Var, ArrayStateAnalysis.Value>()
    this.zip(other).forEach { (k, p) ->
        val (v, otherV) = p
        if (v == null || otherV == null) {
            return@forEach
        }
        if(otherV.javaClass != v.javaClass) {
            if(otherV is ArrayStateAnalysis.Value.ElementPointer && v is ArrayStateAnalysis.Value.MaybeElementPointer && otherV.arrayPtr.containsAny(v.arrayPtr)) {
                toRet[k] = ArrayStateAnalysis.Value.MaybeElementPointer(
                        arrayPtr = otherV.arrayPtr.intersect(v.arrayPtr),
                        indexLowerBound = minBoundUB(v.indexLowerBound, otherV.index.lb),
                        indexVars =otherV.indexVars.intersect(v.indexVars),
                        untilEnd = otherV.untilEnd.intersect(v.untilEnd)
                )
            } else if(v is ArrayStateAnalysis.Value.ElementPointer && otherV is ArrayStateAnalysis.Value.MaybeElementPointer) {
                toRet[k] = ArrayStateAnalysis.Value.MaybeElementPointer(
                        arrayPtr = otherV.arrayPtr.intersect(v.arrayPtr),
                        indexLowerBound = minBoundUB(otherV.indexLowerBound, v.index.lb),
                        indexVars = v.indexVars.intersect(otherV.indexVars),
                        untilEnd = v.untilEnd.intersect(otherV.untilEnd)
                )
            } else if(v is ArrayStateAnalysis.Value.MaybeEmptyArray && otherV is ArrayStateAnalysis.Value.ArrayBasePointer) {
                if(!v.tyVar.isResolvedArray()) {
                    toRet.remove(k)
                    return@forEach
                }
                val thisLen = v.tyVar.expectPointer(IntValue.Constant(BigInteger.ZERO))
                toRet[k] = ArrayStateAnalysis.Value.ArrayBasePointer(ivUB(thisLen, otherV.logicalLength))
            } else if(v is ArrayStateAnalysis.Value.ArrayBasePointer && otherV is ArrayStateAnalysis.Value.MaybeEmptyArray) {
                if(!otherV.tyVar.isResolvedArray()) {
                    toRet.remove(k)
                    return@forEach
                }
                toRet[k] = ArrayStateAnalysis.Value.ArrayBasePointer(ivUB(
                        v.logicalLength, otherV.tyVar.expectPointer(IntValue.Constant(BigInteger.ZERO))
                ))
            }
            return@forEach
        }
        when(v) {
            is ArrayStateAnalysis.Value.ArrayBasePointer -> {
                val otherCast = otherV as ArrayStateAnalysis.Value.ArrayBasePointer
                toRet[k] = ArrayStateAnalysis.Value.ArrayBasePointer(ivUB(v.logicalLength, otherCast.logicalLength))
            }
            is ArrayStateAnalysis.Value.ElementPointer -> {
                val otherCase = otherV as ArrayStateAnalysis.Value.ElementPointer
                if(!otherCase.arrayPtr.containsAny(v.arrayPtr)) {
                    /*
                      have to use labels because, say it with me:
                     */
                    return@forEach
                }
                val commonArray = otherCase.arrayPtr.intersect(v.arrayPtr)
                val offset = ivUB(v.index, otherCase.index)
                toRet[k] = ArrayStateAnalysis.Value.ElementPointer(
                        index = offset,
                        arrayPtr = commonArray,
                        untilEnd = otherCase.untilEnd.intersect(v.untilEnd),
                        indexVars = otherCase.indexVars.intersect(v.indexVars)
                )
            }
            is ArrayStateAnalysis.Value.MaybeElementPointer -> {
                val otherCase = otherV as ArrayStateAnalysis.Value.MaybeElementPointer
                val inter = otherCase.arrayPtr.intersect(v.arrayPtr)
                if(inter.isEmpty()) {
                    return@forEach
                }
                toRet[k] = ArrayStateAnalysis.Value.MaybeElementPointer(
                        arrayPtr = inter,
                        indexLowerBound = minBoundUB(otherV.indexLowerBound, v.indexLowerBound),
                        indexVars = otherV.indexVars.intersect(v.indexVars),
                        untilEnd = otherCase.untilEnd.intersect(v.untilEnd)
                )
            }
            is ArrayStateAnalysis.Value.ScratchPointer -> {
                toRet[k] = ArrayStateAnalysis.Value.ScratchPointer(index = ivUB(v.index, (otherV as ArrayStateAnalysis.Value.ScratchPointer).index))
            }
            is ArrayStateAnalysis.Value.MaybeEmptyArray -> {
                val v1 = v.tyVar
                val v2 = (otherV as ArrayStateAnalysis.Value.MaybeEmptyArray).tyVar
                if(v1.isResolved() != v2.isResolved() || v1.isResolvedArray() != v2.isResolvedArray()) {
                    return@forEach
                }
                when(v1) {
                    is TypeVariable -> {
                        if(v2 !is TypeVariable) {
                            return@forEach
                        }
                        v1.unify(v2)
                    }
                    is TypeVariableAlloc -> {
                        if(v2 !is TypeVariableAlloc) {
                            return@forEach
                        }
                        v1.unify(v2)
                    }
                }
                if(v1.isResolved()) {
                    if(v1.isResolvedArray()) {
                        toRet[k] = ArrayStateAnalysis.Value.ArrayBasePointer(IntValue.Constant(BigInteger.ZERO))
                    }
                } else {
                    toRet[k] = ArrayStateAnalysis.Value.MaybeEmptyArray(v1)
                }
            }
        }
    }
    return toRet.build()
}

fun ArrayStateDomain.join(other: ArrayStateDomain): ArrayStateDomain {
    return this.upperBoundOperator(other, IntValue::join, BigInteger::min)
}

fun ArrayStateDomain.widen(next: ArrayStateDomain) : ArrayStateDomain {
    return this.upperBoundOperator(next, IntValue::widen) { a, b ->
        if(b < a) {
            BigInteger.ZERO
        } else {
            a.min(b)
        }
    }
}


/**
What does this do? Three things:
1. Track static bound information on array length
  a. use this to improve precision of bounds checking
2. Track static indexes at array accesses
  a. avoid duplicated work in Call graph builder
3. Properly support "stride iteration" ptr < end ptr+=32
  a. previously supported with int qualifiers, it sucked
 */
class ArrayStateAnalysis(
    private val mustEqualsAnalysis: IMustEqualsAnalysis,
    private val arrayAllocSites: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    private val scratchSites: Map<CmdPointer, Optional<BigInteger>>,
    private val typeVariableManager: TypeVariableManager,
    private val relaxedSemantics: Boolean
) : ConstVariableFinder, Interpolator {
    lateinit var numericAnalysis: NumericAnalysis
    lateinit var pointerAnalysis : PointerSemantics
    private val arrayAdditionSemantics = object : AdditionSemantics<ArrayStateDomain>() {
        override val numericAnalysis: NumericAnalysis
            get() = this@ArrayStateAnalysis.numericAnalysis
        override val arrayStateAnalysis: ArrayStateAnalysis
            get() = this@ArrayStateAnalysis
        override val relaxedArrayAddition: Boolean
            get() = this@ArrayStateAnalysis.relaxedSemantics

        override fun toStaticArrayInitPointer(
            av1: InitializationPointer.BlockInitPointer,
            o1: TACSymbol.Var,
            target: ArrayStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return target.remove(where.cmd.lhs)
        }

        override fun toAddedStaticArrayInitPointer(
            av1: InitializationPointer.StaticArrayInitPointer,
            o1: TACSymbol.Var,
            target: ArrayStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>,
        ): ArrayStateDomain {
            return target.remove(where.cmd.lhs)
        }

        override val pointerAnalysis: IPointerInformation
            get() = this@ArrayStateAnalysis.pointerAnalysis

        override fun nondeterministicInteger(
                where: ExprView<TACExpr.Vec.Add>,
                s: PointsToDomain,
                target: ArrayStateDomain
        ): ArrayStateDomain {
            return target - where.lhs
        }

        override fun toEndSegment(startElem: Set<TACSymbol.Var>, o1: TACSymbol.Var, target: ArrayStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): ArrayStateDomain =
            target - where.lhs

        private fun getAssignSet(startElem: Set<TACSymbol.Var>, where: ExprView<TACExpr.Vec.Add>) =
            startElem - where.lhs

        override fun byteInitAddition(
            av1: InitializationPointer.ByteInitPointer,
            amountAdded: IntValue,
            o1: TACSymbol.Var,
            target: ArrayStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return if(!av1.mayAdded && amountAdded.isConstant) {
                if(amountAdded.x.c < EVM_WORD_SIZE) {
                    throw PointsToInvariantViolation("Called byte init addition with too small a constant $amountAdded @ $where")
                }
                val d = amountAdded.x.c - EVM_WORD_SIZE
                val ind = whole.variablesEqualTo(d)
                target + (where.lhs to Value.ElementPointer(arrayPtr = setOf(o1), index = IntValue.Constant(d), untilEnd = setOf(), indexVars = ind))
            } else {
                target[o1]?.let { it as? Value.ElementPointer }?.let {
                    val idx = it.index
                    val newIdx = idx.add(amountAdded).first.takeIf {
                        it.lb >= idx.lb
                    } ?: IntValue.Interval(idx.lb + amountAdded.lb)
                    val indexVars = if (newIdx.isConstant) {
                        whole.variablesEqualTo(newIdx.c)
                    } else {
                        setOf()
                    }
                    target.put(where.lhs, Value.ElementPointer(
                        arrayPtr = it.arrayPtr,
                        index = newIdx,
                        untilEnd = setOf(),
                        indexVars = indexVars
                    ))
                } ?: target.remove(where.lhs)
            }
        }

        override fun toAddedElemPointer(
                arrayBase: Set<TACSymbol.Var>,
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var?,
                addOperand: TACSymbol,
                currIndex: IntValue,
                addAmount: IntValue,
                untilEnd: Set<CanonicalSum>,
                target: ArrayStateDomain,
                p: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            val set = getAssignSet(arrayBase, where)
            val indexVars = currIndex.takeIf {
                it.isConstant
            }?.let {a ->
                addAmount.takeIf {
                    it.isConstant
                }?.let { b ->
                    (a.c + b.c)
                }
            }?.let {ind ->
                p.variablesEqualTo(ind)
            } ?: setOf()
            return assignEquiv(set, target, where) { s ->
                Value.MaybeElementPointer(s, currIndex.lb + addAmount.lb, indexVars = indexVars, untilEnd = untilEnd)
            }
        }

        private fun assignEquiv(
                set: Collection<TACSymbol.Var>,
                target: ArrayStateDomain,
                where: ExprView<TACExpr.Vec.Add>,
                f: (Set<TACSymbol.Var>) -> Value
        ): ArrayStateDomain {
            return if (set.isEmpty()) {
                target.remove(where.lhs)
            } else {
                target.put(where.lhs, f(set.toSet()))
            }
        }

        override fun toArrayElemStartPointer(
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            val set = getAssignSet(setOf(o1), where)
            return assignEquiv(set, target, where) {
                Value.ElementPointer(IntValue.Constant(BigInteger.ZERO), it, untilEnd = setOf(), indexVars = whole.variablesEqualTo(BigInteger.ZERO))
            }
        }

        override fun toArrayElementPointer(
            v: Set<ArrayAbstractLocation.A>,
            basePointers: Set<TACSymbol.Var>,
            index: IntValue?,
            indexVar: Set<TACSymbol.Var>,
            untilEnd: Set<CanonicalSum>,
            target: ArrayStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            val set = getAssignSet(basePointers, where)
            return assignEquiv(set, target, where) {
                Value.ElementPointer(index ?: IntValue.Nondet, it, untilEnd, indexVars = indexVar)
            }
        }

        override fun toConstArrayElemPointer(
                v: Set<L>,
                o1: TACSymbol.Var,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return target.remove(where.lhs)
        }

        override fun toBlockElemPointer(
                av1: Set<L>,
                offset: BigInteger,
                blockSize: BigInteger,
                op1: TACSymbol.Var,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return target.remove(where.lhs)
        }

        override fun toIdentityPointer(
            o1: TACSymbol.Var,
            target: ArrayStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ) : ArrayStateDomain {
            val rhsValue = target[o1] ?: return target.remove(where.lhs)
            return target.put(where.lhs, rhsValue)
        }

        override fun scratchPointerAddition(
                o1: TACSymbol.Var,
                o2: TACSymbol,
                offsetAmount: IntValue,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            val curr = whole.arrayState[o1]
            return if(curr == null) {
                target + (where.lhs to Value.ScratchPointer(index = IntValue.Interval(offsetAmount.lb)))
            } else {
                check(curr is Value.ScratchPointer)
                target + (where.lhs to Value.ScratchPointer(
                        index = IntValue.Interval(
                                lb = MAX_EVM_UINT256.min(curr.index.lb + offsetAmount.lb),
                                ub = MAX_EVM_UINT256.min(curr.index.ub + offsetAmount.ub)
                        )
                ))
            }
        }

        override fun arithmeticAddition(
                o1: TACSymbol.Var,
                o2: TACSymbol,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            val maybeElem = where.exp.ls.singleOrNull() {
                it is TACExpr.Sym.Var && target[it.s] is Value.MaybeElementPointer
            }?.let { it as TACExpr.Sym.Var }?.s?.let(target::get) ?: return target.remove(where.lhs)
            return target.put(where.lhs, maybeElem)
        }

        override fun additionConstant(
                c1: BigInteger,
                c2: BigInteger,
                o1: TACSymbol.Const,
                o2: TACSymbol.Const,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return target.remove(where.lhs)
        }

        override fun blockInitAddition(
                av1: InitializationPointer.BlockInitPointer,
                o1: TACSymbol.Var,
                newOffset: BigInteger,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return target.remove(where.lhs)
        }

        override fun arrayInitAddition(
                av1: InitializationPointer.ArrayInitPointer,
                x: BigInteger?,
                o1: TACSymbol.Var,
                target: ArrayStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): ArrayStateDomain {
            return if(!av1.mayAdded && x == EVM_WORD_SIZE) {
                target + (where.lhs to Value.ElementPointer(arrayPtr = setOf(o1),
                    index = IntValue.Constant(BigInteger.ZERO),
                    untilEnd = setOf(),
                    indexVars = whole.variablesEqualTo(BigInteger.ZERO)))
            } else if(av1.mustAdded) {
                target[o1]?.let {
                    it as? Value.ElementPointer
                }?.let {
                    Value.ElementPointer(
                        arrayPtr = it.arrayPtr,
                        indexVars = setOf(),
                        untilEnd = setOf(),
                        index = x?.let { c ->
                            it.index.add(IntValue.Constant(c)).first
                        } ?: IntValue.Nondet
                    )
                }?.let {
                    target.put(where.lhs, it)
                } ?: target.remove(where.lhs)
            } else {
                target.remove(where.lhs)
            }
        }

        override fun toEmptyDataSegment(target: ArrayStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): ArrayStateDomain {
            return target.remove(where.lhs)
        }
    }

    private infix fun Set<CanonicalSum>.usesAny(v: Set<TACSymbol.Var>) : Boolean {
        return this.any { sum ->
            v.any {
                sum.contains(it)
            }
        }
    }

    private operator fun Set<CanonicalSum>.minus(s: Set<TACSymbol.Var>) : Set<CanonicalSum> {
        return this.toTreapSet().retainAll { sum ->
            s.none { v ->
                sum.contains(v)
            }
        }
    }

    /**
     * The variables in [toKillValues] are known to have new values, and all information about them should be killed. In
     * contrast, the variables in [toKillLengthInfo] are still around, but information about them in the heap (namely, their length)
     * should be killed.
     */
    private fun killVars(m: ArrayStateDomain, toKillValues: Set<TACSymbol.Var>, toKillLengthInfo: TreapSet<TACSymbol.Var>) : ArrayStateDomain {
        return m.updateValues { k, v ->
            if (k in toKillValues) {
                return@updateValues null
            }
            when (v) {
                is Value.ElementPointer -> {
                    if (v.arrayPtr.containsAny(toKillValues)) {
                        val arrayPtr = v.arrayPtr
                        removeArrayPointerVars(arrayPtr, toKillValues) {
                            v.copy(arrayPtr = it, untilEnd = v.untilEnd - toKillValues, indexVars = v.indexVars - toKillValues)
                        }
                    } else if (v.untilEnd usesAny toKillValues || v.indexVars.containsAny(toKillValues)) {
                        v.copy(untilEnd = v.untilEnd - toKillValues, indexVars = v.indexVars - toKillValues)
                    } else {
                        v
                    }
                }

                is Value.ArrayBasePointer -> {
                    if(k in toKillLengthInfo) {
                        // NB that we have to kill the lower bound here because we don't know what the total length is
                        // but we know it can't go below 0, hence the lower bound here
                        v.copy(
                            logicalLength = IntValue.Interval(lb = BigInteger.ZERO, ub = v.logicalLength.ub)
                        )
                    } else {
                        v
                    }
                }
                is Value.MaybeElementPointer -> {
                    if (v.arrayPtr.containsAny(toKillValues)) {
                        removeArrayPointerVars(v.arrayPtr, toKillValues) { it ->
                            v.copy(arrayPtr = it, untilEnd = v.untilEnd - toKillValues, indexVars = v.indexVars - toKillValues)
                        }
                    } else if (v.indexVars.containsAny(toKillValues) || v.untilEnd usesAny toKillValues ) {
                        v.copy(
                            indexVars = v.indexVars - toKillValues,
                            untilEnd = v.untilEnd - toKillValues
                        )
                    } else {
                        v
                    }
                }

                is Value.ScratchPointer -> v
                is Value.MaybeEmptyArray -> v
            }
        }
    }

    fun step(x: LTACCmd, p: PointsToDomain): ArrayStateDomain {
        if(x.cmd !is TACCmd.Simple.AssigningCmd) {
            return p.arrayState
        }
        val r = killVars(p.arrayState, setOf(x.cmd.lhs), toKillLengthInfo = treapSetOf())
        val toRet: ArrayStateDomain = if (x.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            when (x.cmd.rhs) {
                is TACExpr.Sym -> {
                    if (x.ptr in arrayAllocSites) {
                        val constantSize = when(val sort = arrayAllocSites[x.ptr]!!.sort) {
                            is AllocationAnalysis.Alloc.ConstantArrayAlloc -> sort.constSize
                            is AllocationAnalysis.Alloc.ConstantStringAlloc -> sort.constLen
                            else -> null
                        }
                        r + (x.cmd.lhs to Value.ArrayBasePointer(constantSize?.let(IntValue.Companion::Constant) ?: IntValue.Nondet))
                    } else if (x.ptr in scratchSites) {
                        r + (x.cmd.lhs to Value.ScratchPointer(scratchSites[x.ptr]!!.map { IntValue.Constant(it) }.orElse(IntValue.Nondet)))
                    } else {
                        when (x.cmd.rhs) {
                            is TACExpr.Sym.Var -> {
                                if (x.cmd.rhs.s == TACKeyword.MEM64.toVar()) {
                                    r - x.cmd.lhs
                                } else if (x.cmd.rhs.s in r) {
                                    r + (x.cmd.lhs to r[x.cmd.rhs.s]!!)
                                } else {
                                    tryAssignIndex(r, x.enarrow(), p)
                                }
                            }

                            is TACExpr.Sym.Const -> {
                                if (x.cmd.rhs.s.value == 0x60.toBigInteger()) {
                                    val tyVar = typeVariableManager.getVariableForSite(where = x)
                                    if (tyVar.resolvedInt()) {
                                        r - x.cmd.lhs
                                    } else if (tyVar.isResolved()) {
                                        check(!tyVar.resolvedInt())
                                        r + (x.cmd.lhs to Value.ArrayBasePointer(IntValue.Constant(BigInteger.ZERO)))
                                    } else {
                                        r + (x.cmd.lhs to Value.MaybeEmptyArray(tyVar))
                                    }
                                } else {
                                    tryAssignIndex(r, x.enarrow(), p)
                                }
                            }
                        }
                    }
                }

                is TACExpr.Vec.Add -> {
                    arrayAdditionSemantics.addition(r, p, x.enarrow())
                }

                is TACExpr.BinOp.Sub -> {
                    if (x.cmd.rhs.operandsAreSyms()) {
                        subtraction.subtractionSemantics(
                            x.cmd.rhs.o1AsTACSymbol(),
                            x.cmd.rhs.o2AsTACSymbol(),
                            where = x,
                            pstate = p
                        )?.let {
                            r + (x.cmd.lhs to it)
                        } ?: (r - x.cmd.lhs)
                    } else {
                        r - x.cmd.lhs
                    }
                }

                else -> r - x.cmd.lhs
            }
        } else if (x.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && x.cmd.base == TACKeyword.MEMORY.toVar()) {
            val ty = pointerAnalysis.getReadType(x.cmd.loc, ltacCmd = x, pState = p)
            when (ty) {
                HeapType.Int,
                is HeapType.OffsetMap -> r - x.cmd.lhs
                HeapType.ByteArray,
                is HeapType.Array -> {
                    r + (x.cmd.lhs to Value.ArrayBasePointer(logicalLength = IntValue.Nondet))
                }
                HeapType.EmptyArray -> {
                    r + (x.cmd.lhs to Value.ArrayBasePointer(logicalLength = IntValue.Constant(BigInteger.ZERO)))
                }
                is HeapType.TVar -> {
                    if (ty.ty.isResolved() && ty.ty.resolvedInt()) {
                        r - x.cmd.lhs
                    } else if (ty.ty.isResolved()) {
                        r + (x.cmd.lhs to Value.ArrayBasePointer(logicalLength = IntValue.Constant(BigInteger.ZERO)))
                    } else {
                        r + (x.cmd.lhs to Value.MaybeEmptyArray(tyVar = ty.ty))
                    }
                }
            }
        } else if (x.cmd is TACCmd.Simple.AssigningCmd.ByteStore && x.cmd.base == TACKeyword.MEMORY.toVar() && x.cmd.loc is TACSymbol.Var) {
            val curr = r[x.cmd.loc]
            if (curr != null && curr is Value.ArrayBasePointer) {
                val write = when (x.cmd.value) {
                    is TACSymbol.Const -> IntValue.Constant(x.cmd.value.value)
                    is TACSymbol.Var -> p.boundsAnalysis[x.cmd.value]?.expectInt() ?: IntValue.Nondet
                }
                r + (x.cmd.loc to Value.ArrayBasePointer(
                        logicalLength = curr.logicalLength.withUpperBound(write.x.ub).withLowerBound(write.x.lb)
                ))
            } else {
                r
            }
        } else {
            r - x.cmd.lhs
        }
        return indexTracking.stepCommand(toRet, ltacCmd = x, p = p)
    }

    private val indexTracking by lazy {
        object : IndexTracking<Value, WithIndexVars, Nothing>(numericAnalysis) {
            override fun indexStepSizeFor(k: TACSymbol.Var, v: Value, m: Map<TACSymbol.Var, Value>, p: PointsToDomain): BigInteger? {
                return (v as WithIndexVars).arrayPtr.monadicMap {
                    p.pointsToState.store[it]?.let {
                        it as? Pointer.ArrayPointer
                    }?.getType(h = p.pointsToState.h)?.let {
                        if(it is HeapType.ByteArray) {
                            32
                        } else if(it is HeapType.EmptyArray) {
                            null
                        } else {
                            1
                        }
                    }
                }?.uniqueOrNull()?.toBigInteger()
            }

            override fun downcast(v: Value): WithIndexVars? = v as? WithIndexVars
        }
    }

    private fun tryAssignIndex(r: ArrayStateDomain, enarrow: ExprView<TACExpr.Sym>, p: PointsToDomain) : ArrayStateDomain {
        val const = numericAnalysis.interpAsConstant(p, ltacCmd = enarrow.wrapped, value = enarrow.exp.s)
        if(r.none { (_, v) ->
                    (v is Value.ElementPointer && v.index.isConstant && v.index.c == const) ||
                            (v is WithIndexVars && enarrow.exp.s in v.indexVars)
                }) {
            return r - enarrow.lhs
        }
        return r.updateValues { _, v ->
            if(v !is WithIndexVars) {
                return@updateValues v
            }
            if(enarrow.exp.s !in v.indexVars && (v !is Value.ElementPointer || !v.index.isConstant || v.index.c != const)) {
                return@updateValues v
            }
            if(v is Value.ElementPointer) {
                v.copy(
                        indexVars = v.indexVars + enarrow.lhs
                )
            } else {
                check(v is Value.MaybeElementPointer)
                v.copy(
                        indexVars = v.indexVars + enarrow.lhs
                )
            }
        }
    }

    private fun removeArrayPointerVars(
        arrayPtr: Set<TACSymbol.Var>,
        lhs: Set<TACSymbol.Var>,
        new: (Set<TACSymbol.Var>) -> Value
    ) : Value? {
        return (arrayPtr - lhs).takeIf { it.isNotEmpty() }?.let(new)
    }

    fun consumeConversion(
        state: ArrayStateDomain,
        conv: List<ConversionHints>,
        pstate: PointsToDomain,
        graphPostPop: PointsToGraph
    ): ArrayStateDomain {
        val arrayVars = conv.mapNotNull {
            it as? ConversionHints.Array
        }.map { it.v }
        val toAmbiguous = conv.asSequence().mapNotNull {
            it as? ConversionHints.Block
        }.mapNotNull {
            it.v `to?` (graphPostPop.store[it.v] as? Pointer.BlockPointerBaseAmbiguous)?.tyvar
        }.toCollection(mutableListOf())
        if(arrayVars.isEmpty() && toAmbiguous.isEmpty()) {
            return state
        }
        return state.mutate { mut ->
            for((v, tv) in toAmbiguous) {
                val ambig = Value.MaybeEmptyArray(tv)
                if(!tv.isResolved()) {
                    mut[v] = ambig
                } else if(tv.isResolvedArray()) {
                    mut[v] = Value.ArrayBasePointer(logicalLength = IntValue.Constant(BigInteger.ZERO))
                }
            }
            if(arrayVars.isNotEmpty()) {
                val lens = arrayVars.mapNotNull {
                    state[it]?.let { it as? Value.ArrayBasePointer }?.logicalLength
                }.toMutableList()
                pstate.boundsAnalysis.mapNotNullTo(lens) { (_, v) ->
                    v.tryResolve().let {
                        it as? QualifiedInt
                    }?.takeIf {
                        it.qual.any {
                            it is IntQualifier.LengthOfArray && it.arrayVar in arrayVars
                        }
                    }?.x
                }
                val len = if(lens.isEmpty()) {
                    if(arrayVars.any {
                        (graphPostPop.store[it] as? Pointer.ArrayPointer)?.v?.all {
                            it is ArrayAbstractLocation.StructAlias
                        } == true
                    }) {
                        IntValue.Constant(BigInteger.ZERO)
                    } else {
                        IntValue.Nondet
                    }
                } else {
                    lens.reduce { acc, d ->
                        if (d.isConstant) {
                            d
                        } else if (d == IntValue.Nondet) {
                            acc
                        } else {
                            acc.withLowerBound(d.getLowerBound()).withUpperBound(d.getUpperBound())
                        }
                    }
                }
                for (v in arrayVars) {
                    mut[v] = Value.ArrayBasePointer(logicalLength = len)
                }
            }
        }
    }


    /*
        This works around a fun bug in the compiler which generates actually unsafe reads when copying 0 length strings
        into storage. So we just recognize the dismal pattern and yolo
     */
    fun isSmallStringLength(loc: TACSymbol.Var, arrayState: ArrayStateDomain): Boolean {
        return arrayState[loc]?.let { it as? Value.ArrayBasePointer }?.let {
            it.logicalLength.getUpperBound() <= 31.toBigInteger()
        } == true
    }

    /**
       Consumes path constraints as follows
       1) [x < y] AND if x is a MaybeElementPointer (i.e., it is the result of adding a value to an element pointer) AND if
       y is the end of the corresponding array, then x is a valid element pointer (this conversion is propagated to other analyses)
       2) [x < y] AND if x is an ElementPointer at index 0 (i.e, x is an ArrayElemStart) and y is the end of the corresponding array
       x is a readable ElementPointer (this conversion is propagated to the other analyses)
       3) [x < y] AND if x is a variable where z + a1 + ... + ak == x, z is an ElementPointer or MaybeElementPointer, and y is the end of the corresponding
       array, then record that at least a1 + .... + ak remains until the end of the array. If z is a MaybeElementPointer, record that z is a readable array element.
       4) [x REL y] where x is an LengthOf qualified int, update the length information for x.arrayVar
       5) [x < y] where y is a LengthOf qualified int, and x is an indexVar for a MaybeElementPointer or ElementPointer. Then propagate that
         the pointers with x as an index variable are safe to read.
       6) [x < y] where x is the index of a Decoder analysis striding pointer d, y is a constant, and we have that
          y * d.strideBy + d.innerOffset + d.start < d.array.length.lowerBound

         Then d is a safe element pointer
       7) [x >= y] where x is a UntilEndFor(p) and y is a constant, then p is a readable elem pointer with y until end
       8) x is a valid index of c, where x = y + c, and y is an indexVar of an array element e. Then e has (c + 1) * elemSize untilEnd.
       9) [x < y] where x is a MaybeElemPointer and y is qualified with HasUpperBound whose wrapped qualifier is `EndOfArraySegment`
       10) [x < y] where x is a MaybeElemPointer and y is a ElemPointer for the same array, then x is an ElemPointer
       11) [x >= y] or [x > y] where x is the end of an array A, and y = ElementPointer(A, I) + k, then A's length lower bound
       is I.lb + k or I.lb + k - 1 depending on whether the bound is strict
     */
    fun consumePath(
        path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>,
        preConvArrayState: ArrayStateDomain,
        s: PointsToDomain,
        arrayConversionHints: List<ConversionHints.Array>
    ): Pair<ArrayStateDomain, List<ValidElemPointer>> {
        val mut = preConvArrayState.builder()

        for(p in arrayConversionHints) {
            ptaInvariant(p.v !in preConvArrayState) {
                "Pointer analysis has freshly claimed ${p.v} is a non-null pointer, but we were already tracking it?"
            }
            mut[p.v] = Value.ArrayBasePointer(
                logicalLength = IntValue.Nondet
            )
        }

        val arrayState = mut.build()

        val elementConversion = mutableListOf<ValidElemPointer>()
        for((k, pi) in path) {
            /*
              Case 1 and 10
             */
            arrayState[k]?.let { it as? Value.MaybeElementPointer }?.let { maybeElem ->
                for(i in pi) {
                    if(i is PathInformation.StrictUpperBound && i.sym != null) {
                        if(s.boundsAnalysis[i.sym]?.tryResolve()?.let {
                                    it as? QualifiedInt
                                }?.qual?.any {
                                (it is IntQualifier.EndOfArraySegment && it.arrayVar in maybeElem.arrayPtr) ||
                                (it is IntQualifier.HasUpperBound && it.other is IntQualifier.EndOfArraySegment && it.other.arrayVar in maybeElem.arrayPtr)
                                } == true || arrayState[i.sym]?.let {
                                    it as? Value.ElementPointer
                                }?.arrayPtr?.containsAny(maybeElem.arrayPtr) == true) {
                            // then we have a maybe elem pointer which is strictly less than the end of the array segment
                            mut[k] = Value.ElementPointer(arrayPtr = maybeElem.arrayPtr,index = IntValue.Interval(lb = maybeElem.indexLowerBound), untilEnd = setOf(), indexVars = setOf())
                            elementConversion.add(ValidElemPointer(k, maybeElem.arrayPtr))
                        }
                    }
                }
            }
            /*
              Heuristic: If we are comparing an elemstart against endofarraysegment, this is almost certainly a bound loop
              (Case 2)
             */
            arrayState[k]?.let {
                it as? Value.ElementPointer
            }?.takeIf {
                it.index == IntValue.Constant(BigInteger.ZERO)
            }?.takeIf {
                s.pointsToState.store[k] is Pointer.ArrayElemStart
            }?.let {elem ->
                val shouldConvert = pi.filterIsInstance(PathInformation.StrictUpperBound::class.java).mapNotNull { it.sym }.mapNotNull {
                    s.boundsAnalysis[it]?.tryResolve() as? QualifiedInt
                }.any {
                    it.qual.any {
                        it is IntQualifier.EndOfArraySegment && it.arrayVar in elem.arrayPtr
                    }
                }
                if(shouldConvert) {
                    mut[k] = Value.ElementPointer(arrayPtr = elem.arrayPtr, index = IntValue.Constant(BigInteger.ZERO), untilEnd = setOf(), indexVars = s.variablesEqualTo(BigInteger.ZERO))
                    elementConversion.add(ValidElemPointer(k, elem.arrayPtr))
                }
            }
            /*
               Case 3
             */
            pi.mapNotNull {
                (it as? PathInformation.UpperBound)?.takeIf { it.sym != null }
            }.flatMap { ub ->
                s.boundsAnalysis[ub.sym!!]?.let { it as? QualifiedInt }?.qual?.filterIsInstance<IntQualifier.EndOfArraySegment>()?.map {
                    ub to it.arrayVar
                } ?: emptyList()
            }.forEach { (bound, boundOfArray) ->
                val untilEnd = mutableMapOf<TACSymbol.Var, Set<CanonicalSum>>()
                val safeAccessProof = mutableSetOf<TACSymbol.Var>()
                for(eq in s.invariants) {
                    val def = eq.definingIntEquationFor(k) ?: continue
                    if (def !is TACExpr.Vec.Add || def.ls.any { it !is TACExpr.Sym }) {
                        continue
                    }
                    for (symVar in def.ls.filterIsInstance<TACExpr.Sym.Var>()) {
                        if (s.arrayState[symVar.s]?.let {
                                    (it is Value.MaybeElementPointer && boundOfArray in it.arrayPtr) ||
                                            (it is Value.ElementPointer && boundOfArray in it.arrayPtr)
                                } != true) {
                            continue
                        }
                        /*
                          Then we have
                          s1 + s2 + ... + elem < end_of_array OR
                          s1 + s2 + ... + elem <= end_of_array

                          (depending on whether the bound is strict or not)

                          then we have s1 + s2 + ... + 0/1 until the end of the array (1 if the bound is strict, 0 otherwise)

                          If s1 + s2 + ... + 0/1 is known to be positive, then elem is a known safe access. In either case,
                          we will have that s1 + s2 + ... + 0/1 remain until the end of the array.
                         */
                        val canon = mutableListOf<TACSymbol>()
                        var hasSafeBoundProof = false
                        if(bound is PathInformation.StrictUpperBound) {
                            hasSafeBoundProof = true
                            canon.add(BigInteger.ONE.asTACSymbol())
                        }
                        for (sym in def.ls) {
                            check(sym is TACExpr.Sym)
                            if (sym is TACExpr.Sym.Var && sym.s == symVar.s) {
                                continue
                            }
                            if((sym is TACExpr.Sym.Const && sym.s.value > BigInteger.ZERO) ||
                                (sym is TACExpr.Sym.Var && (s.boundsAnalysis[sym.s]?.tryResolve()?.let { it as? QualifiedInt }?.x?.lb ?: BigInteger.ZERO) > BigInteger.ZERO)) {
                                hasSafeBoundProof = true
                            }
                            canon.add(sym.s)
                        }
                        untilEnd.merge(symVar.s, setOf(CanonicalSum(canon))) { a, b -> a + b }
                        if(hasSafeBoundProof) {
                            safeAccessProof.add(symVar.s)
                        }
                    }
                }
                for((arrVars, untilEnds) in untilEnd) {
                    val curr = mut[arrVars]!!
                    if(curr is Value.ElementPointer) {
                        mut[arrVars] = curr.copy(
                                untilEnd = curr.untilEnd + untilEnds
                        )
                    } else {
                        ptaInvariant(curr is Value.MaybeElementPointer) {
                            "Expected maybe element pointer $curr"
                        }
                        if(arrVars in safeAccessProof) {
                            mut[arrVars] = Value.ElementPointer(
                                    arrayPtr = curr.arrayPtr,
                                    indexVars = curr.indexVars,
                                    untilEnd = untilEnds,
                                    index = IntValue.Interval(lb = curr.indexLowerBound)
                            )
                             elementConversion.add(ValidElemPointer(arrVars, curr.arrayPtr))
                        } else {
                            mut[arrVars] = curr.copy(
                                    untilEnd = curr.untilEnd + untilEnds
                            )
                        }
                    }
                }
            }
            /*
              Case 4
             */
            s.boundsAnalysis[k]?.tryResolve()?.let { it as? QualifiedInt }?.qual?.filterIsInstance(IntQualifier.LengthOfArray::class.java)?.map {
                it.arrayVar
            }?.filter {
                arrayState[it] is Value.ArrayBasePointer
            }?.map {
                it to (arrayState[it]!! as Value.ArrayBasePointer)
            }?.forEach { (baseVar, base) ->
                val (lb, ub, _) = PathApplication.computeSimplePathInfo(pi)
                val newBounds = base.logicalLength.updateBounds(lb, ub)
                mut[baseVar] = base.copy(
                        logicalLength = newBounds
                )
                // now see if there are any outstanding element pointers that could be converted
                for((kVar, v) in arrayState) {
                    /*
                      This kinda gross check is to make sure we don't convert start elem pointers.
                     */
                    if(v is Value.ElementPointer && baseVar in v.arrayPtr && newBounds.isDefinitelyGreaterThan(v.index.ub) && v.index.isDefinitelyGreaterThan(BigInteger.ZERO)) {
                        if(s.pointsToState.store[kVar] !is Pointer.ArrayElemPointer) {
                            elementConversion.add(ValidElemPointer(kVar, v.arrayPtr))
                        }
                    }
                }
            }
            val arrayBounds = pi.filterIsInstance<PathInformation.StrictUpperBound>().flatMap {
                it.sym?.let {
                    s.boundsAnalysis[it]
                }?.let {
                    it as? QualifiedInt
                }?.qual?.filterIsInstance<IntQualifier.LengthOfArray>()?.map {
                    it.arrayVar
                } ?: listOf()
            }
            if(arrayBounds.isNotEmpty()) {
                val toConv = arrayState.filter { (_, v) ->
                    v is WithIndexVars && k in v.indexVars && v.arrayPtr.containsAny(arrayBounds)
                }
                for((aV, m) in toConv) {
                    check(m is WithIndexVars)
                    if(s.pointsToState.store[aV] !is Pointer.ArrayElemPointer) {
                        elementConversion.add(ValidElemPointer(aV, m.arrayPtr))
                    }
                    if(m !is Value.ElementPointer) {
                        check(m is Value.MaybeElementPointer)
                        mut[aV] = Value.ElementPointer(
                                arrayPtr = m.arrayPtr,
                                indexVars = m.indexVars,
                                untilEnd = setOf(),
                                index = IntValue.Interval(lb = m.indexLowerBound)
                        )
                    }
                }
            }
            /*
              Case 6
             */
            pi.mapNotNull {
                (it as? PathInformation.UpperBound)?.takeIf { it.num != null }
            }.forEach {
                for((arrPtr, d) in s.decoderState) {
                    if(d !is DecoderAnalysis.AbstractDomain.BufferIndices.StridingPointer) {
                        continue
                    }
                    if(k !in d.indexVars) {
                        continue
                    }
                    val maybeElem = mut[arrPtr]?.let { it as? Value.MaybeElementPointer } ?: continue
                    val maxIndex = it.num!! - ((it as? PathInformation.StrictUpperBound)?.let { BigInteger.ONE } ?: BigInteger.ZERO)
                    if(d.stridePath.path.isEmpty()) {
                        val t = d.stridePath.root + (d.strideBy * maxIndex) + d.innerOffset
                        val baseLength = maybeElem.arrayPtr.monadicMap {
                            mut[it]?.let { it as? Value.ArrayBasePointer }?.logicalLength?.lb
                        }?.minOrNull() ?: continue
                        if(baseLength > t) {
                            mut[arrPtr] = Value.ElementPointer(
                                    index = IntValue.Interval(maybeElem.indexLowerBound),
                                    indexVars = maybeElem.indexVars,
                                    untilEnd = maybeElem.untilEnd,
                                    arrayPtr = maybeElem.arrayPtr
                            )
                            elementConversion.add(ValidElemPointer(
                                    arrPtr, baseArrays = maybeElem.arrayPtr
                            ))
                        }
                    }
                }
            }
            /* Case 7 */
            val i = s.boundsAnalysis[k]?.let { it as? QualifiedInt }?.qual?.filterIsInstance<IntQualifier.UntilEndFor>()
            if(i != null) {
                for(p in pi) {
                    if(p !is PathInformation.LowerBound || p.num == null) {
                        continue
                    }
                    val rem = p.num!! + if(p is PathInformation.StrictLowerBound) { BigInteger.ONE } else { BigInteger.ZERO }
                    for(ePtr in i) {
                        val curr = mut[ePtr.arrayElem]?.let { it as? WithIndexVars } ?: continue
                        if(curr is Value.MaybeElementPointer && rem > BigInteger.ZERO) {
                            mut[ePtr.arrayElem] = Value.ElementPointer(
                                arrayPtr = curr.arrayPtr,
                                indexVars = curr.indexVars,
                                index = IntValue.Interval(lb = curr.indexLowerBound),
                                untilEnd = setOf(CanonicalSum(rem.asTACSymbol()))
                            )
                            elementConversion.add(ValidElemPointer(elemPointer = ePtr.arrayElem, baseArrays = curr.arrayPtr))
                        } else if(curr is Value.ElementPointer) {
                            mut[ePtr.arrayElem] = curr.copy(
                                untilEnd = curr.untilEnd + CanonicalSum(rem.asTACSymbol())
                            )
                        }
                    }
                }
            }
            /* case 8 */
            pi.filterIsInstance<PathInformation.Qual<IntQualifier>>().mapNotNull { it.q as? IntQualifier.ValidIndexOf }.forEach {
                val arr = it.arrayVar
                val mIt = mut.entries.iterator()
                while(mIt.hasNext()) {
                    val entry = mIt.next()
                    val v = entry.value
                    if(v !is Value.ElementPointer || arr !in v.arrayPtr || v.indexVars.isEmpty()) {
                        continue
                    }
                    val newUntilEnd = mutableSetOf<CanonicalSum>()
                    for(currInd in v.indexVars) {
                        if(k == currInd) {
                            continue
                        }
                        s.invariants.filter {
                            it.relates(currInd) && it.relates(k)
                        }.mapNotNull {
                            it.definingIntEquationFor(k)?.let {
                                it as? TACExpr.Vec.Add
                            }?.takeIf {
                                it.ls.size == 2 && it.operandsAreSyms()
                            }
                        }.forEach {
                            val (vars, const) = it.ls.map {
                                it as TACExpr.Sym
                            }.partition {
                                it is TACExpr.Sym.Var
                            }
                            if(vars.size == 1) {
                                ptaInvariant(vars.single().s == currInd && const.size == 1) {
                                    "Have only 2 operands that are symbols, and only one is a variable, " +
                                            "but it isn't $currInd and the other isn't a const? $const $it => $vars + $const"
                                }
                                pointerAnalysis.getElementSize(arr, s.pointsToState)?.let {
                                    ((const.single() as TACExpr.Sym.Const).s.value + BigInteger.ONE) * it
                                }?.let {
                                    newUntilEnd.add(CanonicalSum(it.asTACSymbol()))
                                }
                            }
                        }
                    }
                    if(newUntilEnd.isNotEmpty()) {
                        entry.setValue(v.copy(
                            untilEnd = v.untilEnd + newUntilEnd
                        ))
                    }
                }
            }
            // case 11
            s.boundsAnalysis[k]?.tryResolve()?.let {it as? QualifiedInt }?.qual?.mapNotNull { q ->
                (q as? IntQualifier.EndOfArraySegment)?.arrayVar
            }?.mapNotNull { aVar ->
                aVar `to?` (preConvArrayState[aVar] as? Value.ArrayBasePointer)
            }?.let { endBounds ->
                val arrayPointers = endBounds.mapToSet { it.first }
                for(inf in pi) {
                    if(inf !is PathInformation.LowerBound) {
                        continue
                    }
                    val lowerSym = inf.sym ?: continue
                    val matches = s.invariants matches {
                        lowerSym `=`v("elemPointer") { lv ->
                            lv is LVar.PVar && preConvArrayState[lv.v]?.let {
                                it as? Value.ElementPointer
                            }?.arrayPtr.orEmpty().containsAny(arrayPointers)
                        } + k("amount") {
                            it > BigInteger.ZERO
                        }
                    }
                    matches.forEach {
                        val amt = it.factors["amount"]!!.letIf(inf is PathInformation.StrictLowerBound) {
                            it + BigInteger.ONE
                        }
                        val elt = preConvArrayState[(it.symbols["elemPointer"] as LVar.PVar).v] as Value.ElementPointer // safe by the match above
                        val indLb = elt.index.lb
                        val lenLb = (indLb + amt).takeIf { it < MAX_EVM_UINT256 } ?: return@forEach // implausibly large length: bail out
                        for(arrPtr in elt.arrayPtr) {
                            if(arrPtr !in arrayPointers) {
                                continue
                            }
                            val currBase = mut[arrPtr]!! as Value.ArrayBasePointer
                            mut[arrPtr] = currBase.copy(logicalLength = currBase.logicalLength.withLowerBound(lenLb))
                        }
                    }
                }
            }
        }
        return mut.build() to elementConversion
    }

    fun endBlock(arrayState: ArrayStateDomain, last: LTACCmd, liveVars: Set<TACSymbol.Var>): ArrayStateDomain {
        return arrayState.mutate { mut ->
            outer@for((k, v) in arrayState) {
                when(v) {
                    is Value.MaybeEmptyArray,
                    is Value.ScratchPointer,
                    is Value.ArrayBasePointer -> {
                        mut[k] = v
                    }
                    is Value.ElementPointer -> {
                        if(v.arrayPtr.any {
                                    TACKeyword.MEM64.toVar() in mustEqualsAnalysis.equivAfter(last.ptr, it)
                                }) {
                            mut[k] = v
                            continue@outer
                        }
                        val newVars = v.arrayPtr.flatMap {
                            mustEqualsAnalysis.equivAfter(last.ptr, it).filter {
                                it in liveVars
                            }
                        }
                        if(newVars.isEmpty()) {
                            continue@outer
                        }
                        mut[k] = v.copy(arrayPtr = newVars.toSet())
                    }
                    is Value.MaybeElementPointer -> {
                        val newVars = v.arrayPtr.flatMap {
                            mustEqualsAnalysis.equivAfter(last.ptr, it).filter {
                                it in liveVars
                            }
                        }
                        if(newVars.isEmpty()) {
                            continue@outer
                        }
                        mut[k] = v.copy(arrayPtr = newVars.toSet())
                    }
                }
            }
        }
    }

    private val subtraction by lazy {
        object : SubtractionSemantics<Value?>(pointerAnalysis) {
            override val nondetInteger: Value?
                get() = null
            override val scratchPointerResult: Value
                get() = Value.ScratchPointer(index = IntValue.Nondet)

            override fun lengthOfBlock(arrayPtr: Set<TACSymbol.Var>, pstate: PointsToDomain, where: LTACCmd): Value? = null

            override fun numericSubtraction(op1: TACSymbol, op2: TACSymbol, pstate: PointsToDomain, where: LTACCmd): Value? = null
        }
    }

    fun startBlock(arrayState: ArrayStateDomain, lva: Set<TACSymbol.Var>, referencedVars: MutableSet<TACSymbol.Var>): ArrayStateDomain {
        return arrayState.retainAllKeys {
            it in lva || it in referencedVars
        }
    }

    fun collectReferenced(state: ArrayStateDomain, referencedFromLive: MutableSet<TACSymbol.Var>, lva: Set<TACSymbol.Var>) {
        for((k, v) in state) {
            if(k !in lva) {
                continue
            }
            if(v is Value.MaybeElementPointer) {
                referencedFromLive.addAll(v.arrayPtr)
            } else if(v is Value.ElementPointer) {
                referencedFromLive.addAll(v.arrayPtr)
                v.untilEnd.forEach { referencedFromLive.addAll(it.ops) }
            }
        }
    }

    private val loopInterpreter = object : LoopValueSummaryInterpreter<ArrayStateDomain, Value?>() {
        override fun scratchIncrement(sourcePointer: TACSymbol.Var, increment: IntValue?, target: ArrayStateDomain): Value? {
            return target[sourcePointer]?.let {
                it as? Value.ScratchPointer
            }?.let { elm ->
                elm.copy(index = incrIndex(elm.index, increment))
            }
        }

        override val havocInt: Value?
            get() = null

        override fun incrementPackedByte(it: TACSymbol.Var, increment: IntValue?, target: ArrayStateDomain, init: InitializationPointer.ByteInitPointer): Value? {
            return target[it]?.let {
                it as? Value.ElementPointer
            }?.let { elm ->
                elm.copy(index = incrIndex(elm.index, increment))
            }
        }

        private fun incrIndex(idx: IntValue, increment: IntValue?): IntValue =
            increment?.let {
                idx.add(it).let {
                    resOrOverflow -> if (resOrOverflow.second) { null } else { resOrOverflow.first }
                }
            } ?: idx.copy(ub = null)
    }

    fun consumeLoopSummary(valueSummary: Map<TACSymbol.Var, LoopCopyAnalysis.LoopValueSummary>, arrayState: ArrayStateDomain, p: PointsToDomain): ArrayStateDomain =
        loopInterpreter.interpretLoopSummary(valueSummary, arrayState, p).let {
            arrayState.mutate { x ->
                for ((k, v) in it) {
                    if (v == null) {
                        x.remove(k)
                    } else {
                        x[k] = v
                    }
                }
            }
        }

    sealed class Value {
        open fun tryResolve(): Value? = this
        open fun resolveAsPointer(): Value? = this
        sealed interface IndexedElement {
            val index: IntValue
        }
        sealed interface EndTracking {
            /**
             * A set of canonical sums that represent a lower bound on the number of bytes between the current pointer
             * and the end of the array segment. In other words, for each term t represented in canonical sum,
             * elem + t <= end (where end = array + 32 + length)
             */
            val untilEnd: Set<CanonicalSum>
        }

        data class ArrayBasePointer(val logicalLength: IntValue) : Value()
        /*
          Not to be confused with PointerSemantics' ArrayElemPointer. A variable with abstract value ArrayElemPointer
          is proven to be safe to dereference. By contrast, the ElementPointer abstract value does NOT signify the variable
          is within bounds. Instead, it simply represents that a read or write (IF SAFE) through that ElementPointer
          corresponds to a read/write at the logical index range represented by index for the arrays in arrayPtr.
         */
        data class ElementPointer(
            override val index: IntValue,
            override val arrayPtr: Set<TACSymbol.Var>,
            override val untilEnd : Set<CanonicalSum>,
            override val indexVars: Set<TACSymbol.Var>
        ) : Value(), WithIndexVars, EndTracking, IndexedElement {
            override fun addIndices(v: Iterable<TACSymbol.Var>): Value {
                return this.copy(indexVars = this.indexVars + v)
            }

            override val constIndex: BigInteger?
                get() = index.takeIf { it.isConstant }?.c
        }

        /*
          This class represents a value that is provably past the length segment of the arrays in arrayPtr,
          and which is aligned on the element size of the pointer. Like ElementPointer, it is not safe to
          be dereferenced, and must be proved to be less than the end of the array segment to be safe to reference.

          In that regard, it is actually redundant w.r.t ElementPointer because both represent potential accesses for
           a set of array elements. The difference here is the view of the other analyses: for ElementPointer,
           both the bounds and pointer analyses consider the value to be a pointer (not necessarily safe to dereference)
           whereas for the MaybeElementPointer the other analyses consider them to be unkonwn integers.
         */
        data class MaybeElementPointer(
            override val arrayPtr: Set<TACSymbol.Var>,
            val indexLowerBound: BigInteger,
            override val indexVars: Set<TACSymbol.Var>,
            override val untilEnd: Set<CanonicalSum>
        ) : Value(), WithIndexVars, EndTracking {
            override fun addIndices(v: Iterable<TACSymbol.Var>): Value {
                return this.copy(indexVars = this.indexVars+v)
            }

            override val constIndex: BigInteger?
                get() = null
        }

        data class ScratchPointer(override val index: IntValue) : Value(), IndexedElement

        data class MaybeEmptyArray(val tyVar: PointerClassificationVar<*>) : Value() {
            override fun tryResolve(): Value? {
                return if(tyVar.isResolved() && !tyVar.isResolvedArray()) {
                    null
                } else if(tyVar.isResolved()) {
                    check(tyVar.isResolvedArray())
                    ArrayBasePointer(IntValue.Constant(BigInteger.ZERO))
                } else {
                    this
                }
            }

            override fun resolveAsPointer(): Value {
                return tyVar.expectPointer(ArrayBasePointer(IntValue.Constant(BigInteger.ZERO)))
            }
        }
   }

   interface WithIndexVars : WithIndexing<Value> {
       override val untilEndVars: Set<TACSymbol.Var>
           get() = setOf()
       val arrayPtr: Set<TACSymbol.Var>
       override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): Value {
           return this.addIndices(addIndex)
       }

       fun addIndices(v: Iterable<TACSymbol.Var>) : Value
   }

    fun getLogicalLength(v: TACSymbol.Var, p: PointsToDomain) =
           p.arrayState[v]?.let {
               it as? Value.ArrayBasePointer
           }?.logicalLength

    fun interpolate(prev: PointsToDomain, next: PointsToDomain, summary: Map<TACSymbol.Var, TACExpr>): ArrayStateDomain {
        return indexTracking.interpolate(
                summary = summary,
                next = next,
                prevM = prev.arrayState,
                nextM = next.arrayState
        ).first
    }

    fun synthesizeState(
        arrayState: ArrayStateDomain,
        it: SyntheticAlloc
    ): ArrayStateDomain {
        val (toKill, arrayVars) = it.partition { (_, ty) ->
            ty !is EVMTypeDescriptor.PackedBytes1Array && ty !is EVMTypeDescriptor.DynamicArrayDescriptor
        }
        var stateAccum = killVars(arrayState, toKill.mapToTreapSet { it.first }, toKillLengthInfo = treapSetOf())
        for((sym, _) in arrayVars) {
            stateAccum += (sym to Value.ArrayBasePointer(
                logicalLength = IntValue.Nondet
            ))
        }
        return stateAccum
    }

    fun kill(s: ArrayStateDomain, toKill: PointerSemantics.WriteSideEffect) : ArrayStateDomain {
        return killVars(s, toKill.killValue, toKill.killArrayLengthFacts)
    }

}
