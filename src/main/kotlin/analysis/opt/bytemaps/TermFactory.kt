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
import analysis.opt.ConstantPropagatorAndSimplifier
import analysis.opt.intervals.Intervals
import analysis.opt.intervals.IntervalsCalculator
import analysis.opt.intervals.IntervalsCalculator.Companion.intervalOfTag
import analysis.split.Ternary.Companion.isPowOf2
import analysis.split.Ternary.Companion.isPowOf2Minus1
import datastructures.memoized
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.twoToThe
import tac.Tag
import utils.*
import utils.ModZm.Companion.inBounds
import utils.ModZm.Companion.onesZerosMask
import vc.data.*
import vc.data.tacexprutil.asConst
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.asSym
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isSym
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
     * This is a more accurate version of [diff] below, which does not use pointers.
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

            val i = if (noneBetween(ptr1, defs2)) {
                when {
                    // knowing we are actually talking of the same variable, it's enough if one of the ptrs is
                    // relevant.
                    relevant1 || relevant2 -> at1 intersect at2
                    else -> fallbackInterval(unique)
                }
            } else {
                when {
                    relevant1 && relevant2 -> at1 intersect at2
                    relevant1 -> at1
                    else -> sideInterval(unique, ptr2, defs2)
                }
            }
            i * (coef1 - coef2) + acc

        }.mod(Tag.Bit256)
    }


    /** What [diff] bounds [unique] by when neither side tells: the union of what its definitions assign. */
    private fun fallbackInterval(unique: UniqueVar) =
        intervals?.let { unique.defSites.monadicMap(it::getLhs)?.reduce(Intervals::union) }
            ?: intervalOfTag(unique.v.tag)

    /**
     * What [diff] bounds [unique] by when only the side at [ptr], where its variable's definitions are [defsAtPtr],
     * can tell: its value there if those definitions are its own, and [fallbackInterval] otherwise.
     */
    private fun sideInterval(unique: UniqueVar, ptr: CmdPointer, defsAtPtr: Set<CmdPointer?>) =
        if (defsAtPtr containsAll unique.defSites) {
            intervals!!.getAtRhs(ptr, unique.v)
        } else {
            fallbackInterval(unique)
        }

    /**
     * Whether no definition in [defs2] (those of a variable at a point reachable from [ptr1]) is reachable from
     * [ptr1]. On the paths between the two points the variable is then the exact same variable.
     */
    private fun noneBetween(ptr1: CmdPointer, defs2: Set<CmdPointer?>) =
        defs2.filterNotNull().none { reachability.canReach(ptr1, it) }

    /**
     * Whether, as far as [diff] can tell, [query] may read any location of the bytemap defined at [ptr], and of every
     * bytemap definition above it in the chain.
     *
     * An atom of the query's term none of whose definitions reaches [ptr] can't be in the location of anything defined
     * at [ptr] or above it. If also a definition of its variable at the query is reachable from [ptr], then [diff]
     * bounds it, at [ptr] and at every point above, by the query's side alone. These conditions only get stronger
     * going up. So if what these atoms add to the location already takes every value, every [diff] against [query]
     * from [ptr] up takes every value too.
     */
    fun mayReadAnything(query: Query, ptr: CmdPointer): Boolean {
        if (intervals == null) {
            return false
        }
        val outlived = query.t.support.filter { unique ->
            // not an input, which is the same everywhere, and so may be in locations above `ptr` too
            unique.defSites.isNotEmpty() &&
                // assigned on every path, so never the initial value, which also exists above `ptr`
                null !in unique.defSites &&
                // none of its definitions reaches `ptr`, so no location at `ptr` or above can have it
                unique.defSites.none { reachability.canReach(it!!, ptr) } &&
                // some assignment of its variable reaching the query is below `ptr` (`diff`'s `noneBetween` is false),
                // so `diff` bounds it by the query's side alone, here and above
                !noneBetween(ptr, defsAtQuery(unique.v, query.ptr))
        }
        if (outlived.isEmpty()) {
            return false
        }
        return outlived.fold(Intervals(BigInteger.ZERO)) { acc, unique ->
            querySideInterval(unique, query.ptr) * query.t.literals[unique]!! + acc
        }.mod(Tag.Bit256) == intervalOfTag(Tag.Bit256)
    }

    /** The definitions of a variable at a query. [mayReadAnything] asks this at every store the query passes. */
    private val defsAtQuery = memoized { v: TACSymbol.Var, queryPtr: CmdPointer -> def.defSitesOf(v, queryPtr) }

    /** [sideInterval] at a query, the same at every store the query passes. */
    private val querySideInterval = memoized { unique: UniqueVar, queryPtr: CmdPointer ->
        sideInterval(unique, queryPtr, defsAtQuery(unique.v, queryPtr))
    }

    /**
     * Returns the possible values for `[t1] - [t2]`
     */
    fun diff(t1: Term, t2: Term): Intervals {
        checkNotNull(intervals)
        return (t1.support + t2.support).fold(Intervals(t1.c - t2.c)) { acc, unique ->
            val coef1 = t1.literals[unique] ?: BigInteger.ZERO
            val coef2 = t2.literals[unique] ?: BigInteger.ZERO
            if (coef1 == coef2) {
                return@fold acc
            }
            fallbackInterval(unique) * (coef1 - coef2) + acc
        }.mod(Tag.Bit256)
    }

    /** `true` for surely equal, `false` for surely no, and `null` otherwise */
    fun areEqual(ptr1: CmdPointer, t1: Term, ptr2: CmdPointer, t2: Term): Boolean? {
        if (intervals == null) {
            return when ((t1 - t2).asConstOrNull) {
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


    /**
     * Is [query] (at [queryPtr]) is inside [[low], [high]) (interpreted at [rangeQuery]`),
     * `true` is surely inside, `false` if surely outside, and `null` otherwise.
     * This is a more accurate version of [isInside] below, which does not use pointers.
     */
    fun isInside(queryPtr: CmdPointer, query: Term, rangeQuery: CmdPointer, low: Term, high: Term): Boolean? {
        if (intervals == null) {
            return null
        }
        val lowMinusQuery = diff(rangeQuery, low, queryPtr, query)
        val highMinusQuery = diff(rangeQuery, high, queryPtr, query)
        return when {
            (-lowMinusQuery).mod(Tag.Bit256).isSurely2sNonNeg() && highMinusQuery.isSurely2sPos() -> true
            (lowMinusQuery).isSurely2sPos() || (-highMinusQuery).mod(Tag.Bit256).isSurely2sNonNeg() -> false
            else -> null
        }
    }

    /**
     * Is [low1]..[low1]+[len1] contained in [low2]..[low2]+[len2],
     * `true` is surely inside, `false` if they are surely non-intersecting, and `null` otherwise.
     */
    fun isContainedIn(low1: Term, len1: Term, low2: Term, len2: Term): Boolean? {
        if (intervals == null) {
            return null
        }
        val high1 = low1 + len1
        val high2 = low2 + len2
        return when {
            // low2 <= low1 && high2 >= high1
            diff(low1, low2).isSurely2sNonNeg() && diff(high1, high2).isSurely2sNonPos() -> true

            // high1 <= low2
            diff(high1, low2).isSurely2sNonPos() -> false

            // low1 >= high2
            diff(low1, high2).isSurely2sNonNeg() -> false

            else -> null
        }
    }

    /** is [query] in `[[low], [low] + [length])`. Yes, No, or don't know.. */
    fun isInside(query: Term, low: Term, length: Term): Boolean? {
        if (intervals == null) {
            return null
        }
        val high = low + length
        val lowMinusQuery = diff(low, query)
        val highMinusQuery = diff(high, query)
        return when {
            (-lowMinusQuery).mod(Tag.Bit256).isSurely2sNonNeg() && highMinusQuery.isSurely2sPos() -> true
            (lowMinusQuery).isSurely2sPos() || (-highMinusQuery).mod(Tag.Bit256).isSurely2sNonNeg() -> false
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
        val recIncludingNulls = getOperands().map { expr(ptr, it) }

        // maybe enough of the operands are known to be constant, and we can calculate the result constant.
        recIncludingNulls.takeIf { it.isNotEmpty() }
            ?.map { it?.asConstOrNull }
            ?.let {
                // since terms are the mod 2^256 of the actual values, we have to make sure that they
                // are actually equal to the value if we want to use standard constant propagation.
                // See the unit test `calculateConstsBug()`
                getOperands().zip(it).map { (opExpr, opVal) ->
                    opVal?.takeIf {
                        opExpr.tagAssumeChecked is Tag.Bit256 ||
                            (opExpr.isConst && opExpr.asConst.inBounds(Tag.Bit256))
                    }
                }
            }
            ?.let { ConstantPropagatorAndSimplifier.calculateOrNull(e, it) }
            ?.let { return Term(it.mod(Tag.Bit256.modulus)) }

        val rec = recIncludingNulls.monadicMap { it } ?: return null

        /** returns t % m, but only if the result is a constant (otherwise null) */
        fun modIfConstant(t: Term, m: BigInteger) =
            t.mod(m).takeIf { it.isConst }

        when (this) {
            is TACExpr.Sym -> rhsTerm(ptr, this.s)

            is TACExpr.Vec.Add,
            is TACExpr.Vec.IntAdd -> rec.reduce(Term::plus).mod(Tag.Bit256.modulus)

            is TACExpr.Vec.Mul,
            is TACExpr.Vec.IntMul -> rec
                .let { subTerms ->
                    val (consts, nonConsts) = subTerms.partition { it.isConst }
                    val coef = consts.map { it.asConst }.reduceOrNull(BigInteger::multiply) ?: BigInteger.ONE
                    when (nonConsts.size) {
                        0 -> Term(coef)
                        1 -> nonConsts.single() * coef
                        else -> null
                    }
                }
                ?.mod(Tag.Bit256.modulus)

            is TACExpr.BinOp.Sub,
            is TACExpr.BinOp.IntSub ->
                rec.let { it[0] - it[1] }.mod(Tag.Bit256.modulus)

            is TACExpr.BinOp.ShiftLeft -> rec.let { (t1, t2) ->
                t2.asConstOrNull?.toIntOrNull()?.takeIf { it <= 256 }?.let {
                    (t1 * twoToThe(it)).mod(Tag.Bit256.modulus)
                }
            }

            is TACExpr.BinOp.Mod -> rec.let { (t1, t2) ->
                t2.asConstOrNull?.takeIf { it.isPowOf2 }
                    ?.let { modIfConstant(t1, it) }
            }

            is TACExpr.BinOp.BWAnd -> rec.let { (t1, t2) ->
                val (term, mask, nonMaskOp) = when (t1.isConst to t2.isConst) {
                    true to true -> return@let Term(t1.asConst and t2.asConst)
                    true to false -> Triple(t2, t1.asConst, o2)
                    false to true -> Triple(t1, t2.asConst, o1)
                    else -> return@let null
                }
                // for `t & 0xff`, if `t % 256` is a constant, we can calculate it. Otherwise no?
                if (mask.isPowOf2Minus1) {
                    return@let modIfConstant(term, mask + 1)
                }
                // turns out we commonly have `0xf...ffffe0` masks (equivalent to `mod 32`). Many of these can
                // be evaluated because the masked term is know to be a multiple of 32.
                onesZerosMask(mask)?.let { (low, high) ->
                    when {
                        high == EVM_BITWIDTH256 ->
                            modIfConstant(term, twoToThe(low))?.let { term - it }

                        intervals != null -> {
                            val i = intervals.getS(ptr, nonMaskOp)
                            runIf(i.isNotEmpty() && i isLt twoToThe(high)) {
                                modIfConstant(term, twoToThe(low))?.let { term - it }
                            }
                        }

                        else -> null
                    }
                }

            }

            is TACExpr.AnnotationExp<*> -> expr(ptr, o)

            is TACExpr.Apply -> when ((f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                is TACBuiltInFunction.SafeMathPromotion,
                is TACBuiltInFunction.SafeMathNarrow,
                is TACBuiltInFunction.UnsignedPromotion,
                is TACBuiltInFunction.SafeUnsignedNarrow -> rec.single()

                else -> null
            }

            else -> null
        }
    }


    /**
     * checks if this map-definition is of the sort `[i -> i < boundVar ? * : 0]`. If not it returns null.
     * Then it tries to evaluate it on `i := [query]`, and return the evaluation of the condition `i < boundVar`, or
     * null if there is no clear answer.
     */
    fun isQueryInsideMapDefBound(
        defPtr: CmdPointer,
        param: TACSymbol.Var,
        definition: TACExpr,
        query: Query
    ): Boolean? {
        val upperBound = if (
            definition is TACExpr.TernaryExp.Ite &&
            definition.i is TACExpr.BinRel.Lt &&
            definition.i.o1.asVarOrNull == param &&
            definition.i.o2.isSym &&
            definition.t is TACExpr.Unconstrained &&
            definition.e.asConstOrNull == BigInteger.ZERO
        ) {
            definition.i.o2.asSym
        } else {
            return null
        }
        return isInside(query.ptr, query.t, defPtr, Term(0), rhsTerm(defPtr, upperBound)!!)
    }

}
