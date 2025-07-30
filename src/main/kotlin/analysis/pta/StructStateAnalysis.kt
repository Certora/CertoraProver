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
import analysis.numeric.CanonicalSum
import analysis.numeric.IntQualifier
import analysis.numeric.IntValue
import analysis.numeric.PathInformation
import analysis.numeric.linear.LVar
import analysis.numeric.linear.LinearInvariants
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.TermMatching.matchesAny
import analysis.numeric.linear.implies
import datastructures.stdcollections.*
import com.certora.collect.*
import evm.EVM_WORD_SIZE
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACKeyword
import vc.data.TACSymbol
import java.math.BigInteger

typealias StructStateDomain = TreapMap<TACSymbol.Var, StructStateAnalysis.Value>

fun StructStateDomain.join(
        other: StructStateDomain,
        leftContext: PointsToGraph,
        rightContext: PointsToGraph
) : StructStateDomain {
    return this.merge(other) { k, v, otherV ->
        if (v == null || otherV == null || otherV.base != v.base) {
            if(v != null && otherV != null && otherV.sort == v.sort && otherV.sort == StructStateAnalysis.ValueSort.FieldPointer(BigInteger.ZERO)) {
                val leftPointerBase = leftContext.store[v.base] as? Pointer.BlockPointerBase
                val rightPointerBase = rightContext.store[otherV.base] as? Pointer.BlockPointerBase
                if(leftPointerBase == null || rightPointerBase == null || leftPointerBase.getType(leftContext.h) != rightPointerBase.getType(rightContext.h)) {
                    return@merge null
                }
                return@merge StructStateAnalysis.Value(
                    base = k,
                    sort = StructStateAnalysis.ValueSort.FieldPointer(BigInteger.ZERO),
                    indexVars = v.indexVars.intersect(otherV.indexVars),
                    untilEndVars = v.untilEndVars.intersect(otherV.untilEndVars)
                )
            }
            return@merge null
        }
        if(otherV.sort != v.sort &&
                (otherV.sort is StructStateAnalysis.ValueSort.FieldPointer ||
                        v.sort is StructStateAnalysis.ValueSort.FieldPointer)) {
            val t1 = leftContext.isTupleVar(v.base) ?: return@merge null
            val t2 = rightContext.isTupleVar(otherV.base) ?: return@merge null
            if(t1 is TupleTypeResult.TupleResult && t2 is TupleTypeResult.TupleResult && !t1.v.checkCompatibility(t2.v)) {
                return@merge null
            }
        }
        val sort = if(otherV.sort != v.sort) {
            if(otherV.sort is StructStateAnalysis.ValueSort.MaybeConstArray || v.sort is StructStateAnalysis.ValueSort.MaybeConstArray) {
                StructStateAnalysis.ValueSort.MaybeConstArray
            } else {
                StructStateAnalysis.ValueSort.ConstArray
            }
        } else {
            v.sort
        }
        return@merge v.copy(
            sort = sort,
            indexVars = v.indexVars.intersect(otherV.indexVars),
            untilEndVars = v.untilEndVars.intersect(otherV.untilEndVars)
        )
    }
}

class StructStateAnalysis(
    private val allocSites: Map<CmdPointer, AllocationAnalysis.AbstractLocation>,
    private val numericAnalysis: NumericAnalysis,
    private val pointerAnalysis: PointerSemantics,
    private val arrayAnalysis: ArrayStateAnalysis,
    private val relaxedSemantics: Boolean
) : ConstVariableFinder, Interpolator {
    fun consumePath(
        path: Map<TACSymbol.Var, List<PathInformation<IntQualifier>>>,
        structState: StructStateDomain,
        pts: PointsToGraph,
        numeric: NumericDomain,
        structConversionHints: Collection<ConversionHints.Block>,
        inv: LinearInvariants
    ): Pair<StructStateDomain, List<ValidBlock>> {
        val conv = mutableListOf<ValidBlock>()
        val withSafetyProof = structState.updateValues { k, v ->
            if (v.sort !is ValueSort.MaybeConstArray) {
                return@updateValues v
            }

            val pathInfo = path[k]
            if(pathInfo != null) {
                /**
                 * `MaybeConstArray(v) < ConstArray(v)` means that the maybe const array is in fact a const array
                 */
                if(pathInfo.any {
                    it is PathInformation.UpperBound && it.sym?.let { ub ->
                        structState[ub]?.let { upperV ->
                            upperV.base == v.base && upperV.sort != ValueSort.MaybeConstArray
                        }
                    } == true
                }) {
                    conv.add(ValidBlock(
                        base = v.base,
                        block = k
                    ))
                    return@updateValues v.copy(
                        sort = ValueSort.ConstArray
                    )
                }
            }

            val baseBlockSize = pts.store[v.base]?.let {
                (it as? Pointer.BlockPointerBase)?.blockSize ?: (it as? InitializationPointer.BlockInitPointer)?.takeIf {
                    it.offset == BigInteger.ZERO
                }?.v?.addr?.let { it as? AllocationSite.Explicit }?.alloc?.sort?.let { it as? AllocationAnalysis.Alloc.ConstBlock }?.sz
            }
            if(baseBlockSize != null) {
                /**
                 * Check whether [k] is strictly less than a variable defined to be `b + blockSize` where, `b` is `k`'s
                 * base pointer and blockSize is its block size.
                 */
                path[k]?.let { pi ->
                    for (inf in pi) {
                        if (inf is PathInformation.StrictUpperBound && inf.sym != null && inv implies {
                                !inf.sym `=` !v.base + baseBlockSize
                            }) {
                            conv.add(ValidBlock(
                                block = k,
                                base = v.base
                            ))
                            return@updateValues v.copy(sort = ValueSort.ConstArray)
                        }
                    }
                }
            }

            val invariantMatch = inv.matches {
                (k - v("block_base") {
                    it is LVar.PVar && pts.store[it.v]?.let {
                        (it is InitializationPointer.BlockInitPointer && it.offset == BigInteger.ZERO) ||
                            it is Pointer.BlockPointerBase
                    } == true
                }) `=` (v("stride_pointer") - v("stride_base"))
            }
            /*
               Then we have that k - base == stridePointer - strideBase
             */
            invariantMatch.forEach {
                val blockBase = (it.symbols["block_base"] as LVar.PVar).v
                val blockSize = when(val p = pts.store[blockBase]) {
                    is Pointer.BlockPointerBase -> {
                        p.blockSize
                    }
                    is InitializationPointer.BlockInitPointer -> {
                        (p.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz
                    }
                    else -> `impossible!`
                }
                val stridePointer = (it.symbols["stride_pointer"] as LVar.PVar).v
                /*
                  Extract the atomic path condition "facts" we have generated for stridePointer (if any)
                  these facts are of the form `stridePointer < k` (where k is a constant)`, or `stridePointer <= v`
                  where `v` is a variable.
                 */
                val pi = path[stridePointer] ?: return@forEach
                val basePointer = (it.symbols["stride_base"] as LVar.PVar).v
                val providesBlockBound = pi.filterIsInstance<PathInformation.StrictUpperBound>().any { sub ->
                    /*
                     * Does our path condition give us that `stridePointer < v, for we which we have that...
                     */
                    if(sub.sym == null) {
                        return@any false
                    }
                    /*
                     * v == basePointer + blockSize
                     */
                    inv matchesAny {
                        sub.sym - basePointer `=` blockSize
                    } != null
                }
                /*
                 * If we have a block bound, then we must have the following:
                 * k - blockBase == stridePointer - basePointer
                 * stridePointer < v
                 * where
                 * v = basePointer + k (and where k is the size of the block of blockBase)
                 * Elementary math gives us:
                 * v - blockBase + basePointer < basePointer + k
                 * simplifying gives:
                 * v < blockBase + k
                 * that is v is indeed within the block (of size k) starting at `blockBase`
                 */
                if(providesBlockBound) {
                    conv.add(ValidBlock(
                        block = k,
                        base = blockBase
                    ))
                    return@updateValues v.copy(sort = ValueSort.ConstArray)
                }
            }
            if (v.indexVars.none {
                    it in path
                } && v.untilEndVars.none {
                    it in path
                }) {
                return@updateValues v
            }

            val indexSize = pointerAnalysis.blockSizeOf(v.base, pts)?.divide(EVM_WORD_SIZE) ?: return@updateValues v
            val validStruct = v.indexVars.any {
                path[it]?.any {
                    it is PathInformation.StrictUpperBound && it.num != null && it.num <= indexSize
                } ?: false
            } || v.untilEndVars.any {
                path[it]?.any {
                    it is PathInformation.StrictLowerBound && it.num != null && it.num >= BigInteger.ZERO
                } ?: false
            }
            if (validStruct) {
                conv.add(
                    ValidBlock(
                        base = v.base,
                        block = k
                    )
                )
                v.copy(
                    sort = ValueSort.ConstArray
                )
            } else {
                v
            }
        }.builder()
        for(newBlock in structConversionHints) {
            val blockSize = pointerAnalysis.blockSizeOf(
                newBlock.v, pts
            )
            ptaInvariant(blockSize != null) {
                "pointer analysis promised us that $newBlock was a block, but doesn't think it has a size"
            }
            ptaInvariant(newBlock.v !in structState) {
                "We received a hint that the pointer analysis thinks a variable that was an int is actually a pointer, but we are tracking it"
            }
            withSafetyProof[newBlock.v] = toBlock(newBlock.v, numeric, blockSize)
        }
        return withSafetyProof.build() to conv
    }

    private fun toBlock(
        which: TACSymbol.Var,
        n: NumericDomain,
        blockSize: BigInteger
    ) : Value {
        return Value(
            base = which,
            untilEndVars = n.variablesEqualTo(blockSize / EVM_WORD_SIZE),
            indexVars = n.variablesEqualTo(BigInteger.ZERO),
            sort = ValueSort.FieldPointer(BigInteger.ZERO)
        )
    }

    fun startBlock(structState: StructStateDomain, lva: Set<TACSymbol.Var>, referencedFromLive: MutableSet<TACSymbol.Var>): StructStateDomain {
        return structState.retainAllKeys { it in referencedFromLive || it in lva }
    }

    fun endBlock(structState: StructStateDomain, last: LTACCmd, live: Set<TACSymbol.Var>): StructStateDomain {
        unused(last)
        unused(live)
        return structState
    }

    fun consumeConversion(
        structState: StructStateDomain,
        conv: List<ConversionHints>,
        s: PointsToDomain
    ): StructStateDomain {
        unused(conv)
        unused(s)
        return structState
    }

    private fun kill(toKill: Set<TACSymbol.Var>, m: TreapMap<TACSymbol.Var, Value>) : TreapMap<TACSymbol.Var, Value> {
        return m.updateValues { k, value ->
            if(k in toKill) {
                return@updateValues null
            }
            if(value.base in toKill) {
                return@updateValues null
            } else {
                value.filterOutVars(toKill)
            }
        }
    }

    fun step(command: LTACCmd, whole: PointsToDomain): StructStateDomain {
        if (command.cmd !is TACCmd.Simple.AssigningCmd) {
            return whole.structState
        }
        val postKill = kill(setOf(command.cmd.lhs), whole.structState)
        if(command.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && command.cmd.base == TACKeyword.MEMORY.toVar()) {
            val p = pointerAnalysis.getReadType(ltacCmd = command, pState = whole,  loc = command.cmd.loc)
            if(p is HeapType.OffsetMap) {
                return postKill + (command.cmd.lhs to Value(
                    base = command.cmd.lhs,
                    untilEndVars = whole.variablesEqualTo(p.sz / EVM_WORD_SIZE),
                    indexVars = whole.variablesEqualTo(BigInteger.ZERO),
                    sort = ValueSort.FieldPointer(BigInteger.ZERO)
                ))
            }
            return postKill
        }
        val postStep = if(command.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            return postKill
        } else if(command.cmd.rhs is TACExpr.Sym.Var && command.cmd.rhs.s in postKill) {
            postKill.put(command.cmd.lhs, postKill[command.cmd.rhs.s]!!)
        } else if(command.cmd.rhs is TACExpr.Sym.Var && command.cmd.rhs.s == TACKeyword.MEM64.toVar() && command.ptr in allocSites &&
                allocSites[command.ptr]?.sort is AllocationAnalysis.Alloc.ConstBlock) {
            val block = (allocSites[command.ptr]?.sort as AllocationAnalysis.Alloc.ConstBlock)
            postKill.put(command.cmd.lhs, toBlock(command.cmd.lhs, whole.boundsAnalysis, block.sz))
        } else if (command.cmd.rhs is TACExpr.Vec.Add) {
            additionSemantics.addition(
                target = postKill,
                where = command.enarrow(),
                p = whole
            )
        } else {
            postKill
        }
        return indexTracking.stepCommand(postStep, p = whole, ltacCmd = command)
    }

    /**
     * Given the state from a prior iteration [prev] and the state after a single iteration [next], and the (pure) expression
     * definitions for variables mutated in the loop `simpleLoopSummary`, compute relationships that should hold on every
     * iteration of the loop (i.e., index variable correlation)
     */
    fun interpolate(prev: PointsToDomain, next: PointsToDomain, summary: Map<TACSymbol.Var, TACExpr>): Pair<StructStateDomain, List<ValidBlock>> {
        return indexTracking.interpolate(
                prevM = prev.structState,
                nextM = next.structState,
                next = next,
                summary = summary
        )
    }

    fun collectReferenced(structState: StructStateDomain, referencedFromLive: MutableSet<TACSymbol.Var>, lva: Set<TACSymbol.Var>) {
        structState.forEach { (k, v) ->
            if(k !in lva) {
                return@forEach
            }
            referencedFromLive.add(v.base)
            referencedFromLive.addAll(v.untilEndVars)
            referencedFromLive.addAll(v.indexVars)
        }
    }

    fun synthesizeState(
        structState: StructStateDomain,
        it: SyntheticAlloc,
        numeric: NumericDomain,
    ): StructStateDomain {
        val (structTypes, nonStruct) = it.partitionMap { (v, ty) ->
            if(ty !is EVMTypeDescriptor.StaticArrayDescriptor && ty !is EVMTypeDescriptor.EVMStructDescriptor) {
                return@partitionMap v.toRight()
            } else {
                val size = when(ty) {
                    is EVMTypeDescriptor.StaticArrayDescriptor -> ty.numElements * EVM_WORD_SIZE
                    is EVMTypeDescriptor.EVMStructDescriptor -> ty.fields.size.toBigInteger() * EVM_WORD_SIZE
                    else -> `impossible!`
                }
                (v to size).toLeft()
            }
        }
        /**
         * For those variables which are structs or static arrays, update this state, otherwise kill
         */
        val builder = kill(nonStruct.toSet(), structState).builder()
        for((v, size) in structTypes) {
            /**
             * Reuse logic for allocation
             */
            builder[v] = toBlock(
                v,
                blockSize = size,
                n = numeric
            )
        }
        return builder.build()
    }

    fun kill(state: StructStateDomain, toKill: Set<TACSymbol.Var>) : StructStateDomain {
        return kill(toKill, state)
    }

    private val additionSemantics = object : AdditionSemantics<StructStateDomain>() {
        override val pointerAnalysis: IPointerInformation
            get() = this@StructStateAnalysis.pointerAnalysis
        override val numericAnalysis: NumericAnalysis
            get() = this@StructStateAnalysis.numericAnalysis
        override val arrayStateAnalysis: ArrayStateAnalysis
            get() = this@StructStateAnalysis.arrayAnalysis
        override val relaxedArrayAddition: Boolean
            get() = this@StructStateAnalysis.relaxedSemantics

        override fun nondeterministicInteger(where: ExprView<TACExpr.Vec.Add>, s: PointsToDomain, target: StructStateDomain): StructStateDomain {
            return target - where.lhs
        }

        override fun toEmptyDataSegment(target: StructStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun toAddedConstArrayElemPointer(v: Set<L>, o1: TACSymbol.Var, target: StructStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): StructStateDomain {
            return toMaybeConst(o1, v, target, whole, where)
        }

        override fun toStaticArrayInitPointer(
            av1: InitializationPointer.BlockInitPointer,
            o1: TACSymbol.Var,
            target: StructStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain {
            return target[o1]?.let {
                Value(
                    base = it.base,
                    indexVars = setOf(),
                    untilEndVars = setOf(),
                    sort = ValueSort.ConstArray
                )
            }?.let { av ->
                whole.structState + (where.cmd.lhs to av)
            } ?: nondeterministicInteger(where = where, target = target, s = whole)
        }

        override fun toAddedStaticArrayInitPointer(av1: InitializationPointer.StaticArrayInitPointer, o1: TACSymbol.Var, target: StructStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): StructStateDomain {
            return target[o1]?.let {
                Value(
                    base = it.base,
                    indexVars = setOf(),
                    untilEndVars = setOf(),
                    sort = ValueSort.ConstArray
                )
            }?.let {
                whole.structState + (where.cmd.lhs to it)
            } ?: nondeterministicInteger(where = where, target = target, s = whole)
        }

        private fun toMaybeConst(o1: TACSymbol.Var, blockAddr: Set<L>, target: StructStateDomain, whole: PointsToDomain, where: ExprView<TACExpr.Vec.Add>): StructStateDomain {
            val new = target[o1]?.takeIf {
                it.sort == ValueSort.ConstArray
            }?.takeIf {
                blockAddr.monadicMap { addr ->
                    whole.pointsToState.h.isTupleSafeAddress(addr)
                }?.monadicFold { t1: TupleTypeResult, t2: TupleTypeResult ->
                    if(t1 !is TupleTypeResult.TupleResult) {
                        t2
                    } else if(t2 !is TupleTypeResult.TupleResult) {
                        t1
                    } else if(!t1.v.checkCompatibility(t2.v)) {
                        null
                    } else {
                        TupleTypeResult.TupleResult(t1.v.join(t2.v))
                    }
                } != null
            }?.let {
                Value(
                        base = it.base,
                        indexVars = setOf(),
                        untilEndVars = setOf(),
                        sort = ValueSort.MaybeConstArray
                )
            } ?: return nondeterministicInteger(where = where, target = target, s = whole)
            return whole.structState + (where.cmd.lhs to new)
        }

        override fun toEndSegment(
                startElem: Set<TACSymbol.Var>,
                o1: TACSymbol.Var,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun byteInitAddition(
            av1: InitializationPointer.ByteInitPointer,
            amountAdded: IntValue,
            o1: TACSymbol.Var,
            target: StructStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun blockInitAddition(
                av1: InitializationPointer.BlockInitPointer,
                o1: TACSymbol.Var,
                newOffset: BigInteger,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain {
            val untilEnd = ((av1.initAddr.sort as AllocationAnalysis.Alloc.ConstBlock).sz - newOffset) / EVM_WORD_SIZE
            val base = target[o1]?.takeIf {
                it.sort is ValueSort.FieldPointer
            }?.let {
                it.copy(
                        untilEndVars = whole.boundsAnalysis.keysMatching { _, id ->
                            id.let {
                                it as? QualifiedInt
                            }?.x?.takeIf { it.isConstant }?.c == untilEnd
                        }.toSet(),
                        indexVars = whole.boundsAnalysis.keysMatching { _, id ->
                            id.let {
                                it as? QualifiedInt
                            }?.x?.takeIf { it.isConstant }?.c == (newOffset / EVM_WORD_SIZE)
                        }.toSet(),
                        sort = ValueSort.FieldPointer(newOffset)
                )
            } ?: return (target - where.cmd.lhs)
            return target + (where.cmd.lhs to base)
        }

        override fun arrayInitAddition(
                av1: InitializationPointer.ArrayInitPointer,
                x: BigInteger?,
                o1: TACSymbol.Var,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun toAddedElemPointer(
                arrayBase: Set<TACSymbol.Var>,
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var?,
                addOperand: TACSymbol,
                currIndex: IntValue,
                addAmount: IntValue,
                untilEnd: Set<CanonicalSum>,
                target: StructStateDomain,
                p: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, p, target)

        override fun toArrayElemStartPointer(
                v: Set<ArrayAbstractLocation.A>,
                o1: TACSymbol.Var,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun toArrayElementPointer(
            v: Set<ArrayAbstractLocation.A>,
            basePointers: Set<TACSymbol.Var>,
            index: IntValue?,
            indexVar: Set<TACSymbol.Var>,
            untilEnd: Set<CanonicalSum>,
            target: StructStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun toConstArrayElemPointer(
            v: Set<L>,
            blockBase: TACSymbol.Var,
            target: StructStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>,
            indexingProof: ConstArraySafetyProof
        ): StructStateDomain {
            return target + (where.lhs to Value.ConstArrayPointer(
                base = blockBase,
                untilEndVar = setOf(),
                indexVar = when(indexingProof) {
                    /**
                     * Find indexed vars if possible: we know that
                     * lhs of [where] is defined as `b + x`
                     * where `x` is the field of [indexingProof], if we can find some
                     * variable k * 32 = x then `k` is the index of this constant array element
                     */
                    is ConstArraySafetyProof.OffsetFromBase -> {
                        (whole.invariants matches {
                            indexingProof.x `=` v("idx") {
                                it is LVar.PVar
                            } * 32
                        }).mapToSet { (it.symbols["idx"] as LVar.PVar).v }
                    }
                    /**
                     * In this case, the addition semantics proved this addition safe by finding
                     * indices that are witnesses to safety of the operation, so just use that.
                     */
                    is ConstArraySafetyProof.IndexOfNew -> indexingProof.idx
                }
            ))
        }

        override fun toBlockElemPointer(
                av1: Set<L>,
                offset: BigInteger,
                blockSize: BigInteger,
                op1: TACSymbol.Var,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain {
            val prev = target[op1]?.base ?: return target
            val remWords = (blockSize - offset) / EVM_WORD_SIZE
            return target + (where.lhs to Value(
                    base = prev,
                    sort = ValueSort.FieldPointer(offset),
                    untilEndVars = whole.variablesEqualTo(remWords),
                    indexVars = whole.variablesEqualTo(offset / EVM_WORD_SIZE)
            ))
        }

        override fun toIdentityPointer(
            o1: TACSymbol.Var,
            target: StructStateDomain,
            whole: PointsToDomain,
            where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain {
            val rhsValue = target[o1] ?: return target - where.lhs
            return target + (where.lhs to rhsValue)
        }

        override fun scratchPointerAddition(
                o1: TACSymbol.Var,
                o2: TACSymbol,
                offsetAmount: IntValue,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun arithmeticAddition(
                o1: TACSymbol.Var,
                o2: TACSymbol,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

        override fun additionConstant(
                c1: BigInteger,
                c2: BigInteger,
                o1: TACSymbol.Const,
                o2: TACSymbol.Const,
                target: StructStateDomain,
                whole: PointsToDomain,
                where: ExprView<TACExpr.Vec.Add>
        ): StructStateDomain = nondeterministicInteger(where, whole, target)

    }

    sealed class ValueSort {
        object ConstArray : ValueSort()
        object MaybeConstArray : ValueSort()
        data class FieldPointer(val x: BigInteger) : ValueSort()
    }

    private val indexTracking = object : IndexTracking<Value, Value, ValidBlock>(numericAnalysis) {
        override fun indexStepSizeFor(k: TACSymbol.Var, v: Value, m: Map<TACSymbol.Var, Value>, p: PointsToDomain): BigInteger? = BigInteger.ONE

        override fun downcast(v: Value): Value = v

        override fun untilEndFor(k: TACSymbol.Var, t: Value, m: Map<TACSymbol.Var, Value>, p: PointsToDomain, ltacCmd: LTACCmd): BigInteger? {
            return (t.sort as? ValueSort.FieldPointer)?.x?.let { curr ->
                pointerAnalysis.blockSizeOf(t.base, pState = p.pointsToState)?.let { sz ->
                    (sz / EVM_WORD_SIZE) - curr
                }
            }
        }

        override fun strengthen(t: Value): Value {
            return if(t.sort == ValueSort.MaybeConstArray) {
                t.copy(
                        sort = ValueSort.ConstArray
                )
            } else {
                t
            }
        }

        override fun toValidHint(k: TACSymbol.Var, v: Value): ValidBlock = ValidBlock(base = v.base, block = k)
    }

    data class Value(
        val base: TACSymbol.Var,
        override val indexVars: Set<TACSymbol.Var>,
        override val untilEndVars: Set<TACSymbol.Var>,
        val sort: ValueSort
    ) : WithIndexing<Value> {

        fun filterOutVar(d: TACSymbol.Var) : Value {
            return filterOutVars(setOf(d))
        }

        fun filterOutVars(ds: Set<TACSymbol.Var>) : Value {
            return this.copy(
                indexVars = this.indexVars - ds,
                untilEndVars = this.untilEndVars - ds
            )
        }

        companion object {
            @Suppress("FunctionName")
            fun ConstArrayPointer(
                    base: TACSymbol.Var,
                    untilEndVar: Set<TACSymbol.Var>,
                    indexVar: Set<TACSymbol.Var>
            ) = Value(base, indexVar, untilEndVar, ValueSort.ConstArray)
        }

        override val constIndex: BigInteger?
            get() = sort.let { it as? ValueSort.FieldPointer }?.x


        override fun withVars(addIndex: Iterable<TACSymbol.Var>, addUntilEnd: Iterable<TACSymbol.Var>): Value {
            return this.copy(
                    indexVars = this.indexVars + addIndex,
                    untilEndVars = this.untilEndVars + addUntilEnd
            )
        }
    }
}
