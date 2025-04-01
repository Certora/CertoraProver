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

package analysis.opt.intervals

import analysis.opt.intervals.ExtBig.*
import analysis.opt.intervals.ExtBig.Companion.MaxUInt
import analysis.opt.intervals.ExtBig.Companion.One
import analysis.opt.intervals.ExtBig.Companion.TwoTo512
import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.Interval.Companion.IFull
import analysis.opt.intervals.Interval.CutAtPoint.*
import analysis.opt.intervals.Intervals.Companion.SEmpty
import analysis.opt.intervals.Intervals.Companion.SFull
import analysis.opt.intervals.Intervals.Companion.unionOf
import analysis.split.Ternary.Companion.isPowOf2Minus1
import com.certora.collect.*
import datastructures.stdcollections.*
import utils.*
import utils.ModZm.Companion.lowOnes
import utils.ModZm.Companion.onesRange
import java.math.BigInteger
import analysis.opt.intervals.Interval as I
import analysis.opt.intervals.Intervals as S


/**
 * Describes an interval `[low, high]`, where the ends can be infinity.
 * Note that `low` can't be [ExtBig.Inf], and `high` can't be [ExtBig.MInf]
 */
sealed class Interval {

    data object Empty : I()

    /** inclusive */
    @Treapable
    data class NonEmpty(val low: ExtBig, val high: ExtBig) : I() {
        // we don't check that `low` and `high` are valid, that is:
        // low <= high, low can't be Inf, and high can't be MInf.
        // We count on the different constructor calls to make sure this is true for efficiency.
        override fun toString() = if (low == high) {
            "$low"
        } else {
            "[$low, $high]"
        }
    }

    companion object {
        operator fun invoke(low: ExtBig, high: ExtBig) =
            when {
                low <= high -> {
                    check(low !is Inf)
                    NonEmpty(low, high)
                }
                else -> Empty
            }

        operator fun invoke(low: BigInteger, high: BigInteger) =
            when {
                low <= high -> NonEmpty(low.asExtBig, high.asExtBig)
                else -> Empty
            }

        operator fun invoke(low: Int, high: Int) =
            when {
                low <= high -> NonEmpty(low.asExtBig, high.asExtBig)
                else -> Empty
            }

        operator fun invoke(single: ExtBig) =
            NonEmpty(single, single)

        operator fun invoke(single: BigInteger) =
            NonEmpty(single.asExtBig, single.asExtBig)

        operator fun invoke(single: Int) =
            NonEmpty(single.asExtBig, single.asExtBig)

        val ITrue = I(1)
        val IFalse = I(0)
        val IZero = IFalse
        val IFullBool = I(0, 1)
        val IFull = I(MInf, Inf)
        val IFull256 = I(Zero, MaxUInt)

        fun boolInterval(surelyTrue: Boolean, surelyFalse: Boolean) =
            when {
                surelyTrue && surelyFalse -> error("Can't create a bool interval which is both true and false")
                surelyTrue -> ITrue
                surelyFalse -> IFalse
                else -> IFullBool
            }

        fun ite(i: I, t: I, e: I): S =
            when (i) {
                Empty -> SEmpty
                is NonEmpty -> when {
                    i.isConst -> when (i.low.n) {
                        BigInteger.ZERO -> S(e)
                        BigInteger.ONE -> S(t)
                        else -> error("ite condition is not a bool : $i")
                    }

                    i.low == BigInteger.ZERO && i.high == BigInteger.ONE -> t union e
                    else -> error("ite condition is not a bool : $i")
                }
            }
    }

    val isConst
        get() = this is NonEmpty && high == low

    val asConstOrNull
        get() =
            if (this is NonEmpty && high == low) {
                low.n
            } else {
                null
            }

    val asConst
        get() = asConstOrNull!!

    val isBool
        get() = this is NonEmpty &&
            low is Num && low.n >= BigInteger.ZERO &&
            high is Num && high.n <= BigInteger.ONE

    val ends
        get() =
            when (this) {
                is Empty -> listOf()
                is NonEmpty ->
                    if (low == high) {
                        listOf(low)
                    } else {
                        listOf(low, high)
                    }
            }

    val numElements
        get() = when(this) {
            Empty -> Zero
            is NonEmpty ->  (high + 1 - low)!!
        }

    /**
     * A sequence of the values in `this` in ascending order.
     * Note this sequence can be infinite.
     * Will error if the interval starts at [MInf].
     */
    val valSequence
        get() = when(this) {
            Empty -> emptySequence()
            is NonEmpty -> sequence {
                var i = low.nOrNull() ?: error("Can't get a value sequence on $this")
                while (i.asExtBig <= high) {
                    yield(i)
                    i++
                }
            }
        }

    operator fun contains(n: ExtBig) =
        this is NonEmpty && n in low..high

    operator fun contains(n: BigInteger) =
        n.asExtBig in this

    operator fun contains(n: Int) =
        n.asExtBig in this


    /** runs [f]([i], [j]), unless either one of [i], [j] is [Empty]. In which case the result is [Empty] */
    private inline fun ifNonEmpty(i: I, j: I, f: (NonEmpty, NonEmpty) -> I) =
        if (i is NonEmpty && j is NonEmpty) {
            f(i, j)
        } else {
            Empty
        }

    /** Same as [ifNonEmpty] above, except if [i] and [j] are constants, runs [handleConstants] on them */
    private inline fun ifNonEmpty2(
        i: I,
        j: I,
        handleConstants: (BigInteger, BigInteger) -> BigInteger,
        f: (NonEmpty, NonEmpty) -> I
    ) =
        ifNonEmpty(i, j) { x, y ->
            if (x.isConst && y.isConst) {
                I(handleConstants(x.asConst, y.asConst))
            } else {
                f(x, y)
            }
        }

    /** same as the binary version, except [f] is an extension function, simplifying usage a bit */
    private inline fun ifNonEmpty(f: NonEmpty.() -> I) =
        if (this is NonEmpty) {
            this.f()
        } else {
            Empty
        }

    infix fun intersect(other: I) =
        ifNonEmpty(this, other) { i, j ->
            when {
                i.high < j.low -> Empty
                i.low > j.high -> Empty
                else -> NonEmpty(maxOf(i.low, j.low), minOf(i.high, j.high))
            }
        }

    infix fun containedIn(other: I) =
        when {
            this is Empty -> true
            other is Empty -> false
            else -> {
                check(this is NonEmpty && other is NonEmpty)
                low >= other.low && high <= other.high
            }
        }


    infix fun union(other: I): S =
        when {
            this is Empty -> S(other)
            other is Empty -> S(this)
            else -> {
                check(this is NonEmpty && other is NonEmpty)
                when {
                    high < other.low - 1 -> S(this, other)
                    low > other.high + 1 -> S(other, this)
                    else -> S(minOf(low, other.low), maxOf(high, other.high))
                }
            }
        }

    operator fun plus(other: I): I =
        ifNonEmpty(this, other) { i, j ->
            I((i.low + j.low)!!, (i.high + j.high)!!)
        }

    operator fun unaryMinus(): I =
        when (this) {
            is Empty -> Empty
            is NonEmpty -> I(-high, -low)
        }

    operator fun minus(other: I): I = this + (-other)

    operator fun times(other: ExtBig): I =
        ifNonEmpty {
            val oneEnd = low * other
            val otherEnd = high * other
            return if (oneEnd <= otherEnd) {
                I(oneEnd, otherEnd)
            } else {
                I(otherEnd, oneEnd)
            }
        }

    private fun NonEmpty.pointwise(other : ExtBig) =
        I(low * other, high * other)

    private fun NonEmpty.reversePointwise(other : ExtBig) =
        I(high * other, low * other)

    operator fun times(other: I): I =
        ifNonEmpty(this, other) { x, y ->
            val xC = x.isConst
            val yC = y.isConst
            when {
                xC && yC -> I(x.low * y.low)
                xC -> y * x.low
                yC -> x * y.low
                else -> when {
                    // x is all positive
                    x.low >= Zero -> when {
                        y.low >= Zero -> I(x.low * y.low, x.high * y.high)
                        y.high <= Zero -> I(x.high * y.low, x.low * y.high)
                        else -> y.pointwise(x.high)
                    }

                    // x is all negative
                    x.high <= Zero -> when {
                        y.high <= Zero -> I(x.high * y.high, x.low * y.low)
                        y.low >= Zero -> I(x.low * y.high, x.high * y.low)
                        else -> y.reversePointwise(x.low)
                    }

                    // y is all positive
                    y.low >= Zero -> x.pointwise(y.high)

                    // y is all negative
                    y.high <= Zero -> x.reversePointwise(y.low)

                    else -> I(minOf(x.high * y.low, x.low * y.high), maxOf(x.low * y.low, x.high * y.high))
                }
            }
        }


    /**
     * If anything is negative, then pow is undefined, and we return [IFull]. When results can go above 2^512,
     * any value above 2^512 is included in the output interval.
     */
    fun pow(other: I) =
        ifNonEmpty(this, other) { x, y ->
            val min = when (val it = x.low pow y.low) {
                PowResult.SurelyAbove2To512 -> TwoTo512 + 1
                PowResult.Undefined -> return@ifNonEmpty IFull
                is PowResult.Value -> it.value
            }
            val max = when (val it = x.high pow y.high) {
                PowResult.SurelyAbove2To512 -> Inf
                PowResult.Undefined -> return@ifNonEmpty IFull
                is PowResult.Value -> it.value
            }
            I(min, max)
        }

    /** see [ExtBig.pow2limited] */
    fun pow2Limited(modZm: ModZm) =
        ifNonEmpty {
            val l = low.pow2limited(modZm)
            val h = high.pow2limited(modZm)
            if (l == null || h == null) {
                IFull
            } else {
                I(l, h)
            }
        }

    /** see [ExtBig.log2] */
    fun log2() =
        ifNonEmpty {
            val l = low.log2()
            val h = high.log2()
            if (l != null && h != null) {
                I(l, h)
            } else {
                IFull
            }
        }

    /** Dividing by zero results in [IFull]. That is not what solidity does, and is safer. */
    operator fun div(other: I): I =
        this.div(other, exact = false)

    /** considers only cases where the result is without remainder */
    infix fun exactDiv(other: I): I =
        this.div(other, exact = true)

    /**
     * If [exact] is true, then will not round down (and of course not up), e.g.
     *    `[10, 20] / 3 = [4, 6]` and not `[3, 6]`.
     *
     * More generally, we first reduce the question to the case where the denominator is all positive. There are three
     * cases for the nominator. Some rounding is automatic because div rounds towards zero. But we have to augment
     * this manually:
     *
     * pos-pos:
     *    `[11, 19] / [2, 3] = [11 / 3, 19 / 2]`
     *    `[i.low / j.high, i.high / j.low]`
     *    Rounding up the lower end.
     * neg-neg:
     *    `[-19, -11] / [2, 3] = [-19 / 2, -11 / 3]`
     *    `[i.low / j.low, i.high / j.high]`
     *    rounding down the upper end.
     * neg-pos:
     *    `[-19, 11] / [2, 3] = [-19 / 2, 11 / 2]`
     *    `[i.low / j.low, i.high / j.low]`
     *    no rounding needed.
     * */
    private fun div(other: I, exact: Boolean): I =
        ifNonEmpty(this, other) { i, j ->
            if (0 in j) {
                return IFull
            }
            if (j.high < Zero) { // j is all negative.
                return (-i).div(-j, exact)
            }
            // from now on we know j is all positive.
            val low = run {
                // we are going to divide by `d`:
                val d = if (i.low > Zero) {
                    j.high
                } else {
                    j.low
                }
                when (val r = i.low / d) {
                    DivResult.Negative -> // any negative number (the result of -inf/inf)
                        null

                    is DivResult.Value ->
                        // negative numbers are rounded up anyway.
                        r.n.letIf(exact && i.low > Zero && r.n * d != i.low) {
                            it + 1
                        }

                    else -> `impossible!`
                }
            }
            val high = run {
                val d = if (i.high > Zero) {
                    j.low
                } else {
                    j.high
                }
                when (val r = i.high / d) {
                    DivResult.Positive -> // any positive number (the result of inf/inf)
                        null

                    is DivResult.Value ->
                        // if the high end is negative it should be rounded down
                        r.n.letIf(exact && i.high < Zero && r.n * d != i.high) {
                            r.n - 1
                        }

                    else -> `impossible!`
                }
            }

            when {
                high == null ->
                    when {
                        low == null -> IFull
                        low <= Zero -> I(low, Inf)
                        exact -> I(One, Inf)
                        else -> I(Zero, Inf)
                    }

                low == null ->
                    when {
                        high >= Zero -> I(MInf, high)
                        exact -> I(MInf, -One)
                        else -> I(MInf, Zero)
                    }

                else -> I(low, high)
            }
        }


    fun abs() = ifNonEmpty {
        when {
            low >= Zero -> this
            high <= Zero -> -this
            else -> I(Zero, maxOf(-low, high))
        }
    }


    /** Always returns a non-negative range, and assumes [other] is non-negative (`this` may be negative) */
    infix fun unsignedMod(other: I): S = run {
        if (this is NonEmpty && other is NonEmpty) {
            require(other.low >= Zero)
            if (other.high == Zero) {
                return@run S(Zero)
            }
            fun default() = S(Zero, other.high - 1)

            // return `default()` if modulus is not a constant
            val by = other.asConstOrNull
                ?: return@run default()
            val high = high.nOrNull()
            val low = low.nOrNull()
            if (high == null || low == null || high - low >= by - 1) {
                return@run default()
            }
            // BigInteger mod always returns positive numbers
            val newLow = low.mod(by)
            val newHigh = high.mod(by)
            if (newLow <= newHigh) {
                S(newLow, newHigh)
            } else {
                S(BigInteger.ZERO, newHigh, newLow, by - 1)
            }
        } else {
            SEmpty
        }
    }

    /**
     * Always returns a non-negative range:
     *     12 %  5 =  2
     *     12 % -5 =  2
     *    -12 %  5 = 3
     *    -12 % -5 = 3
     * That's the CVL semantics (which differ from the EVM semantics).
     * Also, for [other] = 0, the result is undefined (but non-negative).
     */
    infix fun cvlMod(other: I): S =
        if (0 in other) {
            S(Zero, Inf)
        } else {
            this unsignedMod other.abs()
        }

    /**
     * EVM mod cares only for the sign in the nominator. So:
     *     12 %  5 =  2
     *     12 % -5 =  2
     *    -12 %  5 = -2
     *    -12 % -5 = -2
     * Of course in EVM these are represented in 2s complement (and here not).
     */
    infix fun evmSignedMod(other: I): S {
        return if (this is NonEmpty && other is NonEmpty) {
            if (Zero in other) {
                return SFull
            }
            val absOther = other.abs() as NonEmpty
            val by = absOther.asConstOrNull
                ?: return absOther.high.let {
                    when {
                        low >= Zero -> S(Zero, it - 1)
                        high <= Zero -> -S(Zero, it - 1)
                        else -> S(-it + 1, it - 1)
                    }
                }

            // returns `[low, high] % by` assuming `low`, `high` and `by` are non-negative.
            fun onlyPos(low: ExtBig, high: ExtBig): S {
                val l = low.n
                val h = high.nOrNull()
                if (h == null || h - l >= by - 1) {
                    return S(BigInteger.ZERO, by - 1)
                }
                val newLow = l.mod(by)
                val newHigh = h.mod(by)
                return if (newLow <= newHigh) {
                    S(newLow, newHigh)
                } else {
                    S(BigInteger.ZERO, newHigh, newLow, by - 1)
                }
            }

            when {
                low >= Zero -> onlyPos(low, high)
                high <= Zero -> -onlyPos(-high, -low)
                else -> -onlyPos(Zero, -low) union onlyPos(Zero, high)
            }
        } else {
            SEmpty
        }
    }


    /** Turns a number like 101100 to 111111. Negative numbers just become zero. */
    private fun fillLowBits(b: ExtBig) =
        when {
            b is Inf -> Inf
            b <= Zero -> Zero
            else -> lowOnes(b.n.bitLength()).asExtBig
        }

    infix fun bwAnd(other: I) =
        ifNonEmpty2(this, other, handleConstants = BigInteger::and) { i, j ->
            when {
                // the first two cases are:
                //    `x & 0xffff == x`
                // if x is contained in [0, 0xffff]
                i.isConst && i.asConst.isPowOf2Minus1 && j containedIn I(BigInteger.ZERO, i.asConst) -> j
                j.isConst && j.asConst.isPowOf2Minus1 && i containedIn I(BigInteger.ZERO, j.asConst) -> i
                else -> I(Zero, minOf(i.high, j.high))
            }

        }

    infix fun bwOr(other: I) =
        ifNonEmpty2(this, other, handleConstants = BigInteger::or) { i, j ->
            I(
                maxOf(i.low, j.low),
                minOf(
                    (i.high + j.high)!!,
                    fillLowBits(maxOf(i.high, j.high))
                )
            )
        }

    infix fun bwXor(other: I) =
        ifNonEmpty2(this, other, handleConstants = BigInteger::xor) { i, j ->
            I(
                Zero,
                minOf(
                    (i.high + j.high)!!,
                    fillLowBits(maxOf(i.high, j.high))
                )
            )
        }

    fun bwNot(modZm: ModZm) =
        ifNonEmpty {
            asConstOrNull
                ?.let { I(modZm.bwNot(it)) }
                ?: I(Zero, modZm.maxUnsigned.asExtBig)
        }


    fun signExtend(fromBit: Int, toBit: Int): S =
        when {
            fromBit >= toBit -> S(this)
            this is NonEmpty -> {
                val maxPos = lowOnes(fromBit - 1)
                val allOnes = lowOnes(toBit)
                val minNeg = maxPos xor allOnes
                val allowed = S(BigInteger.ZERO, maxPos, minNeg, allOnes)
                when {
                    // if the interval is all within <[0, 0x00007fff], [0xffff8000, 0xffffffff]>,
                    // then sign-extend does nothing.
                    S(this) containedIn allowed -> S(this)

                    // If everything after the sign bit is 0, we can separate and signextend precisely.
                    S(this) isLe lowOnes(fromBit) -> {
                        val (pos, neg) = this.cutAt(maxPos.asExtBig, DOWN_ONLY)
                        pos union (neg + (I(onesRange(fromBit, toBit))))
                    }
                    else -> allowed
                }
            }
            else -> SEmpty
        }

    infix fun lt(other: I) =
        ifNonEmpty(this, other) { i, j ->
            boolInterval(
                surelyTrue = i.high < j.low,
                surelyFalse = i.low >= j.high
            )
        }

    infix fun le(other: I) =
        ifNonEmpty(this, other) { i, j ->
            boolInterval(
                surelyTrue = i.high <= j.low,
                surelyFalse = i.low > j.high
            )
        }

    infix fun gt(other: I) = other lt this

    infix fun ge(other: I) = other le this

    infix fun eq(other: I) =
        ifNonEmpty(this, other) { i, j ->
            boolInterval(
                surelyTrue = i.asConstOrNull == j.asConstOrNull,
                surelyFalse = i intersect j == Empty
            )
        }

    operator fun not() =
        ifNonEmpty {
            when (this) {
                ITrue -> IFalse
                IFalse -> ITrue
                IFullBool -> IFullBool
                else -> error("A boolean with a non boolean interval $this")
            }
        }

    infix fun neq(other: I) = !eq(other)

    fun toMathInt(modZm : ModZm): S = with(modZm) {
        when {
            this@Interval !is NonEmpty ->
                SEmpty

            low < Zero || high > maxUnsigned.asExtBig ->
                S(minSignedMath, maxSigned)

            isSurely2sNonNeg(modZm) ->
                S(this@Interval)

            isSurely2sNeg(modZm) ->
                S(low - modulus, high - modulus)

            else ->
                unionOf(
                    listOf(
                        I(low, maxSigned.asExtBig),
                        I(minSignedMath.asExtBig, high - modulus)
                    )
                )
        }
    }



    fun fromMathInt(modZm : ModZm): S = with(modZm) {
        when {
            this@Interval !is NonEmpty ->
                SEmpty

            low < minSignedMath.asExtBig || high > maxSigned.asExtBig ->
                S(BigInteger.ZERO, maxUnsigned)

            high < Zero ->
                S(low + modulus.asExtBig, high + modulus.asExtBig)

            low >= Zero ->
                S(this@Interval)

            else ->
                unionOf(
                    I(Zero, high),
                    I(low + modulus, maxUnsigned.asExtBig)
                )
        }
    }


    /** Removes [n] from the intervals of `this` */
    fun delete(n: BigInteger) =
        if (this is NonEmpty) {
            val ne = ExtBig(n)
            when {
                ne < low -> S(this)
                ne > high -> S(this)
                n == low && n == high -> SEmpty
                n == low -> S(ExtBig(n + 1), high)
                n == high -> S(low, ExtBig(n - 1))
                else -> S(low, ExtBig(n - 1), ExtBig(n + 1), high)
            }
        } else {
            SEmpty
        }


    /** answers the question: where to put the cut point itself */
    enum class CutAtPoint {
        BOTH, DOWN_ONLY, UP_ONLY, NONE;
    }

    /**
     * Splits the interval to two, [low, point], and [point, high], whether [point] is really in depends
     * on the value of [option]
     */
    fun cutAt(point: ExtBig, option: CutAtPoint): Pair<I, I> =
        if (this is NonEmpty) {
            when {
                high < point -> this to Empty
                low > point -> Empty to this
                else -> {
                    val lower = I(
                        low,
                        if (option == BOTH || option == DOWN_ONLY) {
                            point
                        } else {
                            point - 1
                        }
                    )
                    val higher = I(
                        if (option == BOTH || option == UP_ONLY) {
                            point
                        } else {
                            point + 1
                        },
                        high
                    )
                    lower to higher
                }
            }
        } else {
            Empty to Empty
        }


    fun isSurely2sNeg(modZm: ModZm) = this is NonEmpty && low.is2sNeg(modZm)

    fun isSurely2sNonNeg(modZm : ModZm) = this is NonEmpty && high.is2sNonNeg(modZm)

    /** the maximum of the absolute values of `low` and `high` */
    fun norm(): ExtBig {
        check(this is NonEmpty) {
            "the empty interval has no norm"
        }
        return maxOf(low.abs(), high.abs())
    }
}

