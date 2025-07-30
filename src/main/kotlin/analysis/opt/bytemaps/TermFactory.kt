/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.bytemaps

import analysis.CmdPointer
import analysis.numeric.linear.ReducedLinearTerm
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.Intervals
import analysis.opt.intervals.IntervalsCalculator
import analysis.opt.intervals.IntervalsCalculator.Companion.intervalOfTag
import analysis.split.Ternary.Companion.isPowOf2
import analysis.split.Ternary.Companion.isPowOf2Minus1
import datastructures.memoized
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.MAX_EVM_INT256
import evm.twoToThe
import tac.Tag
import utils.*
import utils.ModZm.Companion.onesZerosMask
import vc.data.*
import java.math.BigInteger

/**
 * A version of [v] that is unique, because the value of a variable at two different places must be equal if its
 * def-sites at these places are the same (this assumes the program is a DAG).
 */
data class UniqueVar(val v: TACSymbol.Var, val defSites: Set<CmdPointer?>) {
    override fun toString() = v.toString()
}

typealias Term = ReducedLinearTerm<UniqueVar>

/**
 * A common class for several bytemap optimizations.
 *
 * An important note for all the classes around this, is that terms are always the mod 2^256 version of the value in
 * the variable. So the term related to an int variable may actually not represent the value in it. Only those related
 * to a bit256 variable are surely correct, because even if we came to this term via int variables, the fact that now its
 * a bit256 variable means that there was eventually some sort of modulo applied. This trick means that we can't
 * handle division, because it doesn't commute with `mod`.
 *
 * The name `Factory` is misleading, because this also holds [lhsTerm], a map keeping a calculated term for lhs's of
 * commands. This map should be filled up by the user class, and [rhsTerm] and [expr] can then be used to calculate
 * terms at rhs of commands.
 */
class TermFactory(val code: CoreTACProgram, val intervals: IntervalsCalculator?) {

    /**
     * Records the term [t] representing the `loc` of a load command, together with the [ptr] of this command.
     * The term itself may actually be shifted because when a query propagates backwards in the store-chain
     * it may propagate through long-stores.
     */
    data class Query(val ptr: CmdPointer, val t: Term) {
        fun shift(by: Term) =
            if (by.asConstOrNull == BigInteger.ZERO) {
                this
            } else {
                copy(t = (t + by).mod(Tag.Bit256.modulus))
            }
    }

    private val def = code.analysisCache.strictDef
    private val reachability = code.analysisCache.reachability

    fun isGoodTag(tag: Tag) = tag is Tag.Bits || tag is Tag.Int

    val atomicTermCache = memoized { v: TACSymbol.Var, defSites: Set<CmdPointer?> ->
        Term(UniqueVar(v, defSites))
    }

    /**
     * Returns the possible values for `[t1] - [t2]` where [t1] is calculated at the rhs of [ptr1], and [t2] at the
     * rhs of [ptr2].
     * [ptr2] should be reachable from [ptr1], and the atoms of [t1] are before [ptr1] in the program. Same for
     * [t2] and [ptr2].
     *
     * Note that since the atoms of our terms are [UniqueVar], we should have no need for [ptr1] and [ptr2] - two different
     * atoms are considered different variables. However, using these pointers, we can sometimes get more accurate
     * answers.
     *
     * It is for example necessary for the test `understandBranching` of `BytemapInlinerTest`.
     */
    fun diff(ptr1: CmdPointer, t1: Term, ptr2: CmdPointer, t2: Term): Intervals {
        require(reachability.canReach(ptr1, ptr2))
        return (t1.support + t2.support).fold(Intervals(t1.c - t2.c)) { acc, unique ->
            val coef1 = t1.literals[unique] ?: BigInteger.ZERO
            val coef2 = t2.literals[unique] ?: BigInteger.ZERO
            if (coef1 == coef2) {
                return@fold acc
            }
            val (v, defs) = unique
            val at1 by lazy { intervals!!.getAtRhs(ptr1, v) }
            val at2 by lazy { intervals!!.getAtRhs(ptr2, v) }
            val defs1 = def.defSitesOf(v, ptr1)
            val defs2 = def.defSitesOf(v, ptr2)

            // These means that `v`'s value at ptr1/2 talks of the the same variable that `unique` talks about.
            // it counts on the fact that `unique` comes from a point before `ptr1` and `ptr2` in the program.
            val relevant1 = defs1 containsAll defs
            val relevant2 = defs2 containsAll defs

            /** If we can't figure out anything about `v` from [ptr1] and [ptr2] */
            fun default() = if(intervals == null ) {
                intervalOfTag(v.tag)
            } else {
                defs.monadicMap(intervals::getLhs)
                    ?.reduce(Intervals::union)
                    ?: intervalOfTag(v.tag)
            }

            /**
             * We are considering only paths between [ptr1] and [ptr2]. Therefore, if there is no assignment to `v`
             * between the two, then it must actually be the exact same variable.
             */
            val noneBetween = defs2.filterNotNull().none {
                reachability.canReach(ptr1, it)
            }

            val i = if (noneBetween) {
                when {
                    // knowing we are actually talking of the same variable, it's enough if one of the ptrs is
                    // relevant.
                    relevant1 || relevant2 -> at1 intersect at2
                    else -> default()
                }
            } else {
                when {
                    relevant1 && relevant2-> at1 intersect at2
                    relevant1 -> at1
                    relevant2 -> at2
                    else -> default()
                }
            }
            i * (coef1 - coef2) + acc

        }.mod(Tag.Bit256)
    }

    /** `true` for surely equal, `false` for surely no, and `null` otherwise */
    fun areEqual(ptr1: CmdPointer, t1: Term, ptr2: CmdPointer, t2: Term): Boolean? {
        if (intervals == null) {
            return when((t1 - t2).asConstOrNull) {
                null -> null
                BigInteger.ZERO -> true
                else -> false
            }
        }
        val diff = diff(ptr1, t1, ptr2, t2)
        return when {
            diff.asConstOrNull == BigInteger.ZERO -> true
            0 !in diff -> false
            else -> null
        }
    }

    private val Intervals.isNonNeg
        get() = isNotEmpty() && this isLe MAX_EVM_INT256.asExtBig

    private val Intervals.isPos
        get() = 0 !in this && isNonNeg

    /**
     * Is [query] (at [queryPtr]) is inside [[low], [high]) (interpreted at [rangeQuery]`),
     * `true` is surely inside, `false` if surely outside, and `null` otherwise.
     */
    fun isInside(queryPtr: CmdPointer, query: Term, rangeQuery: CmdPointer, low: Term, high: Term): Boolean? {
        if (intervals == null) {
            return null
        }
        val lowMinusQuery = diff(rangeQuery, low, queryPtr, query)
        val highMinusQuery = diff(rangeQuery, high, queryPtr, query)
        return when {
            (-lowMinusQuery).mod(Tag.Bit256).isNonNeg && highMinusQuery.isPos -> true
            (lowMinusQuery).isPos || (-highMinusQuery).mod(Tag.Bit256).isNonNeg -> false
            else -> null
        }
    }

    /** this gets filled up externally. */
    val lhsTerm = mutableMapOf<CmdPointer, Term>()

    val rhsTerm = memoized { ptr: CmdPointer, s: TACSymbol ->
        runIf(isGoodTag(s.tag)) {
            when (s) {
                is TACSymbol.Const -> Term(s.value.mod(Tag.Bit256.modulus))
                is TACSymbol.Var -> {
                    val defs = def.defSitesOf(s, ptr)
                    defs.monadicMap { it }?.map { lhsTerm[it] }?.sameValueOrNull()
                        ?: runIf(s.tag is Tag.Bits) {
                            // atomic terms can't contain int vars, these may eventually inlined.
                            // we can possibly extend to ints that are known to be in bounds, but I think it's not
                            // necessary.
                            atomicTermCache(s, defs)
                        }
                }
            }
        }
    }

    /**
     * The term corresponding to expression [e] at the rhs of [ptr]. If it can't be calculated then `null`.
     * Note that this does not handle bytemap operations at all.
     */
    fun expr(ptr: CmdPointer, e: TACExpr): Term? = with(e) {
        fun rec() = getOperands().monadicMap { expr(ptr, it) }

        /** returns t % m, but only if the result is a constant (otherwise null) */
        fun modIfConstant(t: Term, m: BigInteger) =
            t.mod(m).takeIf { it.isConst }

        when (this) {
            is TACExpr.Sym -> rhsTerm(ptr, this.s)

            is TACExpr.Vec.Add,
            is TACExpr.Vec.IntAdd -> rec()?.reduce(Term::plus)?.mod(Tag.Bit256.modulus)

            is TACExpr.Vec.Mul,
            is TACExpr.Vec.IntMul -> rec()
                ?.let { subTerms ->
                    val (consts, nonConsts) = subTerms.partition { it.isConst }
                    val coef = consts.map { it.asConst }.reduceOrNull(BigInteger::multiply) ?: BigInteger.ONE
                    when(nonConsts.size) {
                        0 -> Term(coef)
                        1 -> nonConsts.single() * coef
                        else -> null
                    }
                }
                ?.mod(Tag.Bit256.modulus)

            is TACExpr.BinOp.Sub,
            is TACExpr.BinOp.IntSub ->
                rec()?.let { it[0] - it[1] }?.mod(Tag.Bit256.modulus)

            is TACExpr.BinOp.ShiftLeft -> rec()?.let { (t1, t2) ->
                t2.asConstOrNull?.toIntOrNull()?.takeIf { it <= 256 }?.let {
                    (t1 * twoToThe(it)).mod(Tag.Bit256.modulus)
                }
            }

            is TACExpr.BinOp.Mod -> rec()?.let { (t1, t2) ->
                t2.asConstOrNull?.takeIf { it.isPowOf2 }
                    ?.let { modIfConstant(t1, it) }
            }

            is TACExpr.BinOp.BWAnd -> rec()?.let { (t1, t2) ->
                val (term, mask) = when (t1.isConst to t2.isConst) {
                    true to true -> return@let Term(t1.asConst and t2.asConst)
                    true to false -> t2 to t1.asConst
                    false to true -> t1 to t2.asConst
                    else -> return@let null
                }
                // for `t & 0xff`, if `t % 256` is a constant, we can calculate it. Otherwise no?
                if (mask.isPowOf2Minus1) {
                    return@let modIfConstant(term, mask + 1)
                }
                // turns out we commonly have `0xf...ffffe0` masks (equivalent to `mod 32`). Many of these can
                // be evaluated because the masked term is know to be a multiple of 32.
                onesZerosMask(mask)?.let { (low, high) ->
                    runIf(high == EVM_BITWIDTH256) {
                        modIfConstant(term, twoToThe(low))?.let { term - it }
                    }
                }
            }

            is TACExpr.AnnotationExp<*> -> expr(ptr, o)

            is TACExpr.Apply -> when ((f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                is TACBuiltInFunction.SafeMathPromotion,
                is TACBuiltInFunction.SafeMathNarrow,
                is TACBuiltInFunction.UnsignedPromotion,
                is TACBuiltInFunction.SafeUnsignedNarrow -> rec()?.single()

                else -> null
            }

            else -> null
        }
    }

}
