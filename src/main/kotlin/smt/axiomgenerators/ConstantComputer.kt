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

package smt.axiomgenerators

import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import smt.HashingScheme
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import smt.solverscript.functionsymbols.TheoryFunctionSymbol.Binary.IntSub
import smt.solverscript.functionsymbols.TheoryFunctionSymbol.Vec.IntAdd
import tac.Tag
import utils.ModZm
import utils.ModZm.Companion.bwNot
import utils.ModZm.Companion.evmSignExtend
import utils.ModZm.Companion.lowOnes
import utils.lazy
import vc.data.LExpression
import java.lang.Integer.min
import java.math.BigInteger

interface ConstantComputer {
    fun evalRec(originalExp: LExpression): LExpression

    companion object {
        operator fun invoke(hashingScheme: HashingScheme, lxf: LExpressionFactory, targetTheory: SmtTheory) =
            when (hashingScheme) {
                is HashingScheme.Legacy ->
                    ConstantComputerWithHashSimplifications(lxf, hashingScheme.gapSize, targetTheory)

                is HashingScheme.PlainInjectivity -> BasicConstantComputer(lxf, targetTheory)
                is HashingScheme.Datatypes -> BasicConstantComputer(lxf, targetTheory)
            }
    }
}

/** For Debugging purposes */
@Suppress("unused")
class DoNothingConstantComputer : ConstantComputer {
    override fun evalRec(originalExp: LExpression) = originalExp
}

/**
 * Compute constant expressions.
 * It's recommended to construct this via [ConstantComputer.invoke] and not directly.
 */
open class BasicConstantComputer(val lxf: LExpressionFactory, val targetTheory: SmtTheory? = null) : ConstantComputer {

    /**
     * Creates a copy of [new] with the meta from [old] added to it. Should be used whenever [new] is equivalent to
     * [old], i.e., it evaluates to the same value for any variable assignment. In case there are multiple [old]
     * expressions that are all equivalent to [new], multiple [old] can be provided as a `vararg`; this can be used, for
     * example, for `a || b` where `a == b`, then the resulting expression should retain the metas of `a`, `b` and
     * `a || b`.
     */
    protected fun LExpression.withMetaOf(vararg others: LExpression): LExpression =
        others.filter { it !== this }.fold(this) { n, o -> lxf { n.addMeta(o.meta) } }

    /** Entry point */
    override fun evalRec(originalExp: LExpression): LExpression =
        originalExp.transformPost(lxf) { e ->
            evalFlat(e)
        }

    /** Assumes all sub expressions have been simplified */
    private fun evalFlat(e: LExpression): LExpression =
        when (e) {
            // TODO: remove quantifier if expression lost quantified variables
            is LExpression.QuantifiedExpr -> e
            is LExpression.ApplyExpr -> when ((e.f.signature.nrOfParameters as? NrOfParameters.Fixed)?.n) {
                1 -> flatEvalUnary(e)
                2 -> flatEvalBinary(e)
                else -> flatEvalOther(e)
            }.withMetaOf(e)

            else -> e
        }

    private fun flatEvalUnary(e: LExpression.ApplyExpr): LExpression = lxf {
        val arg = e.arg
        when (val f = e.f) {
            is NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot -> when {
                arg.isConst -> lit(bwNot(arg.asConst, e.tag as ModZm), e.tag)
                arg.isApplyOf<NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot>() -> arg.arg
                else -> e
            }

            is TheoryFunctionSymbol.Unary.LNot -> when {
                arg.isTrue -> FALSE
                arg.isFalse -> TRUE
                arg.isApplyOf<TheoryFunctionSymbol.Unary.LNot>() -> arg.arg
                else -> e
            }

            is AxiomatizedFunctionSymbol.Bitwise.SignExtend -> {
                val i = f.i
                val bits = (i + 1) * 8
                when {
                    arg.isConst ->
                        lit(evmSignExtend(i.toBigInteger(), arg.asConst), e.tag)

                    arg.isApplyOf<AxiomatizedFunctionSymbol.Bitwise.SignExtend>() ->
                        arg.arg signExt (min(i, (arg.f as AxiomatizedFunctionSymbol.Bitwise.SignExtend).i))

                    arg.isApplyOf<NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd>() &&
                        arg.lhs isEqTo lowOnes(bits) ->
                            arg.rhs signExt i

                    arg.isApplyOf<NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd>() &&
                        arg.rhs isEqTo lowOnes(bits) ->
                            arg.lhs signExt i

                    else -> e
                }
            }

            // TODO: Regarding uninterpreted functions, we want to be careful as to not erase axioms we create, So we
            //  normally don't treat them here.

            is AxiomatizedFunctionSymbol.UninterpTagMod -> when {
                arg.isConst -> lit(arg.asConst.mod(f.tag.modulus), e.tag)
                else -> e
            }

            else -> e
        }
    }


    private fun flatEvalBinary(e: LExpression.ApplyExpr): LExpression = lxf {
        val lhs = e.lhs
        val rhs = e.rhs
        val allConstants = e.args.all { it.isConst }
        val modZm by lazy { e.tag as ModZm }
        val maxUnsigned by lazy { modZm.maxUnsigned }
        fun lit(i : BigInteger) = lit(i, e.tag)
        fun lit(i : Int) = lit(i, e.tag)


        fun twoConstsBool(f: (BigInteger, BigInteger) -> Boolean): LExpression =
            if (!allConstants) e else litBool(f(lhs.asConst, rhs.asConst))

        fun twoConstsNum(f: (BigInteger, BigInteger) -> BigInteger): LExpression =
            if (!allConstants) e else lit(f(lhs.asConst, rhs.asConst))

        when (e.f) {

            is IntSub -> plusMinusNormalForm(e)

            is NonSMTInterpretedFunctionSymbol.Binary.Sub -> when {
                lhs == rhs -> lit(0)
                rhs isEqTo 0 -> lhs
                rhs isEqTo modZm.modulus -> lhs
                else -> twoConstsNum { l, r -> (l - r).mod(modZm.modulus) }
            }


            is TheoryFunctionSymbol.Binary.IntGt -> when {
                lhs == rhs -> FALSE
                else -> twoConstsBool { l, r -> l > r }
            }

            is TheoryFunctionSymbol.Binary.IntGe -> when {
                lhs == rhs -> TRUE
                else -> twoConstsBool { l, r -> l >= r }
            }

            is TheoryFunctionSymbol.Binary.IntLt -> when {
                lhs == rhs -> FALSE
                else -> twoConstsBool { l, r -> l < r }
            }

            is TheoryFunctionSymbol.Binary.IntLe -> when {
                lhs == rhs -> TRUE
                else -> twoConstsBool { l, r -> l <= r }
            }

            is TheoryFunctionSymbol.Binary.LImplies -> when {
                lhs.isTrue -> rhs
                lhs.isFalse -> TRUE
                rhs.isTrue -> TRUE
                rhs.isFalse -> evalFlat(!lhs)
                lhs == rhs -> TRUE
                else -> mergeNestedImpl(e) ?: e
            }

            is NonSMTInterpretedFunctionSymbol.Binary.AssignEq ->
                handleEvalEq(e)

            is TheoryFunctionSymbol.Binary.Eq -> when {
                lhs == rhs -> TRUE
                allConstants -> litBool(lhs.asConst == rhs.asConst)
                else -> handleEvalEq(e)
                // TODO : actually equality can be propagated into some expressions, like and, or, etc. But not sure it gives anything
                //      (though we do it with hashes below)
            }

            // TODO: all the commented lines are generally correct, yet our axioms are ruined by them. Basically axioms should
            //  be fixed and this reinstated - or even better, have a choice if to run simplifications or not.
            is NonSMTInterpretedFunctionSymbol.Binary.Gt -> when {
                // lhs.isEqToConst(0) -> FALSE
                // rhs.isEqToConst(MAX_EVM_UINT256) -> FALSE
                lhs == rhs -> FALSE
                else -> twoConstsBool { l, r -> l > r }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.Ge -> when {
                // rhs.isEqToConst(0) -> TRUE
                // lhs.isEqToConst(MAX_EVM_UINT256) -> TRUE
                lhs == rhs -> TRUE
                else -> twoConstsBool { l, r -> l >= r }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.Le -> when {
                // lhs.isEqToConst(0) -> TRUE
                // rhs.isEqToConst(MAX_EVM_UINT256) -> TRUE
                lhs == rhs -> TRUE
                else -> twoConstsBool { l, r -> l <= r }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.Lt -> when {
                // rhs.isEqToConst(0) -> FALSE
                // lhs.isEqToConst(MAX_EVM_UINT256) -> FALSE
                lhs == rhs -> FALSE
                else -> twoConstsBool { l, r -> l < r }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd -> when {
                lhs isEqTo 0 -> lit(0)
                rhs isEqTo 0 -> lit(0)
                lhs isEqTo maxUnsigned -> rhs
                rhs isEqTo maxUnsigned -> lhs
                lhs == rhs -> lhs.withMetaOf(rhs)
                else -> twoConstsNum(BigInteger::and)
            }

            is NonSMTInterpretedFunctionSymbol.Binary.BitwiseOr -> when {
                lhs isEqTo 0 -> rhs
                rhs isEqTo 0 -> lhs
                lhs isEqTo maxUnsigned -> lit(maxUnsigned)
                rhs isEqTo maxUnsigned -> lit(maxUnsigned)
                lhs == rhs -> lhs.withMetaOf(rhs)
                else -> twoConstsNum(BigInteger::or)
            }

            is NonSMTInterpretedFunctionSymbol.Binary.BitwiseXor -> when {
                lhs isEqTo 0 -> rhs
                rhs isEqTo 0 -> lhs
                lhs isEqTo maxUnsigned -> evalFlat(bitwiseNot(rhs))
                rhs isEqTo maxUnsigned -> evalFlat(bitwiseNot(lhs))
                lhs == rhs -> lit(0)
                else -> twoConstsNum(BigInteger::xor)
            }

            is NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft -> when {
                lhs isEqTo 0 -> lit(0)
                rhs isEqTo 0 -> lhs
                else -> twoConstsNum { x, s ->
                    if (s >= modZm.bitwidth.toBigInteger()){
                        BigInteger.ZERO
                    } else {
                        (x shl s.toInt()) % modZm.modulus
                    }
                }
            }

            is NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical -> when {
                lhs isEqTo 0 -> lit(0)
                rhs isEqTo 0 -> lhs
                else -> twoConstsNum { x, s ->
                    if (s >= modZm.bitwidth.toBigInteger()) {
                        BigInteger.ZERO
                    } else {
                        (x shr s.toInt()) % (e.tag as ModZm).modulus
                    }
                }
            }

            is AxiomatizedFunctionSymbol.UninterpMod,
            is TheoryFunctionSymbol.Binary.IntMod -> when {
                lhs == rhs -> lit(0, e.tag)
                e.tag is Tag.Bits && rhs isEqTo modZm.modulus ||
                    targetTheory?.isBv == true && rhs isEqTo EVM_MOD_GROUP256 ->
                    /*
                     * mod 2^256 is neutral on the 256-bit bit vectors
                     * (some uneasiness remains, would it be safer to disallow large literals at
                     * this point, so we don't allow introducing them?..)
                     * This likely came from TACBuildInFunctions.signed_int_to_math_int or similar..
                     *
                     * If we get more precise bit vectors, this would be wrong...
                     */
                    lhs

                else -> twoConstsNum(BigInteger::mod)
            }

            else -> e
        }
    }


    private fun flatEvalOther(e: LExpression.ApplyExpr): LExpression = lxf {
        val modZm by lazy { e.tag as Tag.Bits }

        fun simplifyBoolVec(neutral: Boolean, zero: Boolean?, reduce: (Boolean, Boolean) -> Boolean) =
            simplifyVec(e, neutral, zero, reduce, { it.asBoolConst }, { litBool(it) })

        fun simplifyNumVec(neutral: BigInteger, zero: BigInteger?, reduce: (BigInteger, BigInteger) -> BigInteger) =
            simplifyVec(e, neutral, zero, reduce, { it.asConst }) {
                val t = e.tag
                lit(if (t is Tag.Bits) { it.mod(t.modulus) } else { it }, t)
            }

        when (e.f) {
            is TheoryFunctionSymbol.Ternary.Ite -> {
                val (cond, thenExp, elseExp) = e.args
                when {
                    cond.isTrue -> thenExp
                    cond.isFalse -> elseExp
                    thenExp == elseExp -> thenExp.withMetaOf(elseExp) // condition no longer matters
                    else -> e
                }
            }

            is TheoryFunctionSymbol.Vec.LAnd -> simplifyBoolVec(
                neutral = true,
                zero = false,
                reduce = Boolean::and
            ).let {
                if (it.isApplyOf<TheoryFunctionSymbol.Vec.LAnd>()) {
                    mergeParallelImpl(it) ?: it
                } else {
                    it
                }
            }

            is TheoryFunctionSymbol.Vec.LOr -> simplifyBoolVec(
                neutral = false,
                zero = true,
                reduce = Boolean::or
            )

            is IntAdd -> plusMinusNormalForm(e)

            is NonSMTInterpretedFunctionSymbol.Vec.Add -> simplifyNumVec(
                neutral = BigInteger.ZERO,
                zero = null,
                reduce = { a, b -> (a + b) % modZm.modulus }
            )

            is TheoryFunctionSymbol.Vec.IntMul -> simplifyNumVec(
                neutral = BigInteger.ONE,
                zero = BigInteger.ZERO,
                reduce = BigInteger::times
            )

            is NonSMTInterpretedFunctionSymbol.Vec.Mul -> simplifyNumVec(
                neutral = BigInteger.ONE,
                zero = BigInteger.ZERO,
                reduce = { a, b -> (a * b) % modZm.modulus }
            )

            else -> e
        }
    }

    /**
     * Simplifies `a => (b => c)` to `(a and b) => c`.
     */
    private fun mergeNestedImpl(e: LExpression.ApplyExpr): LExpression? {
        check(e.f == TheoryFunctionSymbol.Binary.LImplies) { "This function only handles implications" }
        val rhs = e.rhs
        if (rhs.isApplyOf<TheoryFunctionSymbol.Binary.LImplies>()) {
            return lxf {
                evalFlat(evalFlat(e.lhs and rhs.lhs) implies rhs.rhs)
            }
        }
        return null
    }

    /**
     * Simplifies parallel implications like `(a => c) and (b => c)` to `(a or b) => c`. Generalizes to an arbitrary number
     * of parallel implications (for the same consequence `c`) and also handles multiple sets of parallel implications
     * within the same conjunction.
     */
    private fun mergeParallelImpl(e: LExpression.ApplyExpr): LExpression? {
        check(e.f == TheoryFunctionSymbol.Vec.LAnd) { "This function only handles conjunctions" }
        // 1) group by consequence (null == no implication)
        // 2) merge parallel implications, keep the rest
        // 3) flatten and put into a conjunction
        return e.args.groupBy {
            if (it.isApplyOf<TheoryFunctionSymbol.Binary.LImplies>()) {
                it.rhs
            } else {
                null
            }
        }.also {
            // quick check whether anything is changed in the end; if not, abort early
            if (it.all { (c, i) -> i.size == 1 || c == null }) {
                return null
            }
        }.map { (cons, impls) ->
            if (cons == null || impls.size == 1) {
                impls
            } else {
                listOf(lxf {
                    evalFlat(evalFlat(or(impls.map { (it as LExpression.ApplyExpr).lhs })) implies cons)
                })
            }
        }.flatten().let { evalFlat(lxf.and(it)) }
    }

    /**
     * simplifies apply with many arguments (for cummutative and associate operations)- collects all constants, etc.
     * T is either Boolean or BigInteger, so that we can use this on: and, or, mul, add
     * @param e the expression to simplify
     * @param neutral i.e., 1 is multiplication, 0 in addition
     * @param zero i.e., 0 in multiplication, and addition has no zeroing element, so null.
     * @param reduce i.e., the actual operation on two constants.
     * @param getVal i.e., get the constant of type T out of an [LExpression] literal.
     * @param makeLiteral, i.e., generate an [LExpression] literal out of a constant of type T.
     */
    private fun <T> simplifyVec(
        e: LExpression.ApplyExpr,
        neutral: T,
        zero: T?, // values s.t. reduce(*, zero) = reduce(zero, *) = zero. i.e., 0 for multplication, TRUE for lor, etc.
        reduce: (T, T) -> T,
        getVal: (LExpression) -> T,
        makeLiteral: (T) -> LExpression,
    ): LExpression {
        val (consts, nonConsts) = e.args.partition { it.isConst }
        val const = consts.map { getVal(it) }.fold(neutral, reduce)
        return when {
            nonConsts.isEmpty() -> makeLiteral(const)
            const == zero -> makeLiteral(zero!!)
            const == neutral ->
                if (nonConsts.size > 1) {
                    e.copyArgs(lxf, nonConsts)
                } else {
                    nonConsts.first()
                }

            else ->
                e.copyArgs(lxf, listOf(makeLiteral(const)) + nonConsts)
        }
    }


    /**
     * Simplifies an [IntAdd] expression and orders the elements in canonical form: first a constant if there
     * is one, and then the other elements sorted according to their hash (which is stable)
     */
    private fun intAddSimplify(args: List<LExpression>) =
        lxf.intAdd(args).let { e ->
            if (!e.isApplyOf<IntAdd>()) {
                e
            } else {
                val basic = simplifyVec(
                    e,
                    neutral = BigInteger.ZERO,
                    zero = null,
                    reduce = BigInteger::add,
                    getVal = { it.asConst },
                    makeLiteral = { lxf.litInt(it) }
                )
                if (!basic.isApplyOf<IntAdd>()) {
                    basic
                } else {
                    basic.copyArgs(lxf, basic.args.sortedWith { e1, e2 ->
                        when {
                            e1.isConst && e2.isConst -> 0
                            e1.isConst -> -1
                            e2.isConst -> 1
                            else -> compareValues(e1.hashCode(), e2.hashCode())
                        }
                    })
                }
            }
        }


    /**
     * Simplifies an nested [IntAdd] and [IntSub] expression that may include multiplications by constants, and returns
     * it in canonical form:
     *     (- (+ const c1*x1 c2*x2 ...) (+ d1*y1 d2*y2 ...))
     *   The c's and d's are constant, or if they are 1 then they don't appear.
     *   The x's and y's are non-constant, and all x's and y's are distinct.
     *   const disappears if it is zero, unless the whole thing evaluates to zero.
     *   The pluses may disappear if there is only one argument.
     *   The minus may also not be there.
     *   Order is via sorting on the hashcode of the variables which is stable.
     *
     * A main reason for this optimization is the terms we generate when propagating selects through long stores. It
     * generates such terms, and if it detecting equivalence between them can reduce their number and thus reduce the
     * numbers of axioms we generate.
     */
    private fun plusMinusNormalForm(exp: LExpression): LExpression {
        if (!exp.isApplyOf<IntAdd>() && !exp.isApplyOf<IntSub>()) {
            return exp
        }
        val sum = mutableMapOf<LExpression, BigInteger>()
        var constant = BigInteger.ZERO

        fun collect(e: LExpression, coefficient: BigInteger) {
            when {
                e.isApplyOf<IntAdd>() ->
                    e.args.forEach {
                        collect(it, coefficient)
                    }

                e.isApplyOf<IntSub>() -> {
                    collect(e.lhs, coefficient)
                    collect(e.rhs, -coefficient)
                }

                e.isApplyOf<TheoryFunctionSymbol.Vec.IntMul>() && e.args[0].isConst ->
                    collect(
                        evalFlat(e.copyArgs(lxf, e.args.drop(1))),
                        coefficient * e.args[0].asConst
                    )

                e.isConst ->
                    constant += e.asConst * coefficient

                else ->
                    sum.merge(e, coefficient, BigInteger::plus)
            }
        }
        collect(exp, BigInteger.ONE)

        val pos = mutableListOf<LExpression>()
        val neg = mutableListOf<LExpression>()

        /** Returns the resulting coefficient and which set it should be added to (`pos` or `neg`) */
        fun transform(n: BigInteger) =
            if (targetTheory?.isBv == true) {
                val c = n.mod(EVM_MOD_GROUP256)
                when {
                    c == BigInteger.ZERO -> null
                    c <= EVM_MOD_GROUP256 / BigInteger.TWO -> c to pos
                    else -> (EVM_MOD_GROUP256 - c) to neg
                }
            } else {
                when {
                    n == BigInteger.ZERO -> null
                    n > BigInteger.ZERO -> n to pos
                    else -> -n to neg
                }
            }

        transform(constant)?.let { (c, addTo) ->
            addTo += lxf.litInt(c)
        }

        sum.forEachEntry { (e, coef) ->
            transform(coef)?.let { (c, addTo) ->
                addTo +=
                    if (c == BigInteger.ONE) {
                        e
                    } else {
                        evalFlat(lxf { litInt(c) * e })
                    }
            }
        }

        val p = intAddSimplify(pos)
        val n = intAddSimplify(neg)

        return if (n == lxf.ZERO) {
            p
        } else {
            lxf { p - n }
        }
    }

    protected open fun handleEvalEq(e: LExpression.ApplyExpr): LExpression = e
}


/**
 * This is only sound when using hash axioms when legacy hash axioms are used.
 * It's recommended to construct this via [ConstantComputer.invoke] and not directly.
 */
class ConstantComputerWithHashSimplifications(
    lxf: LExpressionFactory,
    private val gapSize: BigInteger,
    targetTheory: SmtTheory? = null
) :
    BasicConstantComputer(lxf, targetTheory) {

    // TODO: all of this is pretty brittle -- e.g. might lead to very hard to find unsoundness because we miss some simplification...

    override fun handleEvalEq(e: LExpression.ApplyExpr): LExpression {
        with(lxf) {

            val lhs = e.lhs
            val rhs = e.rhs
            return when {

                // hash is never equal zero, or any constant less than [gapSize].
                lhs.isConst && lhs.asConst < gapSize &&
                    rhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() ->
                    FALSE

                rhs.isConst && rhs.asConst < gapSize &&
                    lhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() ->
                    FALSE

                // l already evald, so definitely not a const
                ((lhs.isApplyOf<IntAdd>() &&
                    lhs.args.any { it.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() })
                    /*|| lhs.isApplyOf<NonSMTInterpretedFunctionSymbol.SKey.SkeyAdd>() -- try this?? */) &&
                    rhs.isConst &&
                    rhs.asConst < gapSize ->
                    FALSE

                // r already evald, so definitely not a const
                rhs.isApplyOf<IntAdd>() &&
                    rhs.args.any { it.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() } &&
                    lhs.isConst &&
                    lhs.asConst < gapSize ->
                    FALSE

                // The checks in the above cases for the size of the constant are because otherwise the hash axioms we generate may be erased.

                // TODO: Compare hash and addition

                // compare two vector int additions
                lhs.isApplyOf<IntAdd>() && rhs.isApplyOf<IntAdd>() -> {
                    // assume already flattened
                    val (lHash, lRemaining) = lhs.args.partition { it.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() }
                    val (rHash, rRemaining) = rhs.args.partition { it.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() }
                    check(lHash.size <= 1) { "Found expression that adds up two hash applications: $lhs" }
                    check(rHash.size <= 1) { "Found expression that adds up two hash applications: $rhs" }
                    when {
                        lHash.size == 1 && rHash.size == 1 ->
                            evalRec(and(lHash[0] eq rHash[0], intAdd(lRemaining) eq intAdd(rRemaining)))

                        lHash.size == 1 && rHash.isEmpty() -> FALSE
                        lHash.isEmpty() && rHash.size == 1 -> FALSE
                        else -> e
                    }
                }

                lhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() &&
                    rhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() &&
                    lhs.f != rhs.f ->
                    // two hashes with different number of arguments are different (since can't be called with same slot by EVM) // TODO: Risk?
                    // TODO: This assumption is prevailing here - h1(x)+z could be equal to h2(y,x) for some y,z. But can't have slot x in both h1 and h2.
                    FALSE

                lhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() &&
                    rhs.isApplyOf<AxiomatizedFunctionSymbol.Hash.SimpleHashN>() &&
                    (lhs.f as AxiomatizedFunctionSymbol.Hash.SimpleHashN).arity ==
                    (rhs.f as AxiomatizedFunctionSymbol.Hash.SimpleHashN).arity ->
                    evalRec(
                        and(
                            lhs.args.zip(rhs.args).map { (l, r) -> l eq r }
                        )
                    )

                else -> e
            }
        }
    }
}
