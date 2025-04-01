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

import analysis.opt.intervals.ExtBig.Companion.Int256max
import analysis.opt.intervals.ExtBig.Companion.Int256minMath
import analysis.opt.intervals.ExtBig.Companion.MaxUInt
import analysis.opt.intervals.ExtBig.Companion.TwoTo256
import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.Inf
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Interval.Companion.IFalse
import analysis.opt.intervals.Interval.Companion.IFull
import analysis.opt.intervals.Interval.Companion.IFull256
import analysis.opt.intervals.Interval.Companion.IFullBool
import analysis.opt.intervals.Interval.Companion.ITrue
import analysis.opt.intervals.Interval.CutAtPoint
import datastructures.stdcollections.*
import utils.*
import java.math.BigInteger
import kotlin.collections.get
import analysis.opt.intervals.Interval as I
import analysis.opt.intervals.Intervals as S


/**
 * A list of non-adjacent intervals in ascending order, e.g., `[-30, 10], [12, 2^100], [2^101, Inf]`
 */
@JvmInline
value class Intervals private constructor(
    val intervals: List<I.NonEmpty>
) : List<I.NonEmpty> by intervals {

    fun toSingle() = I(min, max)

    init {
        intervals.forWithNext { i, j ->
            check(i.high + 1 < j.low) {
                "$intervals should be ascending and non-adjacent"
            }
        }
    }

    override fun toString() = joinToString(prefix = "<", postfix = ">")

    val asConstOrNull
        get() = singleOrNull()?.asConstOrNull

    val isConst
        get() = asConstOrNull != null

    val asConst
        get() = asConstOrNull!!

    val max
        get() = lastOrNull()?.high!!

    val min
        get() = firstOrNull()?.low!!

    val minOrNull
        get() = firstOrNull()?.low

    val isBool
        get() = size == 1 && first().isBool

    val numElements
        get() = fold(Zero as ExtBig) { sum, b -> (sum + b.numElements)!! }

    val valSequence
        get() = asSequence().flatMap { it.valSequence }

    val isTrue
        get() = asConstOrNull == BigInteger.ONE

    val isFalse
        get() = asConstOrNull == BigInteger.ZERO

    operator fun contains(n: ExtBig) =
        any { n in it }

    operator fun contains(n: BigInteger) =
        any { n in it }

    operator fun contains(n: Int) =
        any { n in it }


    companion object {
        operator fun invoke(intervals: List<I>) =
            S(intervals.filterIsInstance<I.NonEmpty>())

        operator fun invoke(vararg intervals: I): S =
            S(intervals.toList())

        operator fun invoke(vararg l: ExtBig): S =
            when (l.size) {
                0 -> SEmpty
                1 -> S(I(l[0]))
                2 -> S(I(l[0], l[1]))
                else -> {
                    S(l.toList().chunked(2).map {
                        check(it.size == 2) {
                            "Initializing Intervals with odd number of args: ${l.toList()}"
                        }
                        I(it[0], it[1])
                    })
                }
            }

        operator fun invoke(vararg l: BigInteger): S =
            invoke(*l.map(ExtBig::invoke).toTypedArray())

        operator fun invoke(vararg l: Int): S =
            invoke(*l.map(ExtBig::invoke).toTypedArray())

        operator fun invoke(b: Boolean): S =
            if (b) {
                STrue
            } else {
                SFalse
            }


        /** can take a mixed up list of overlapping intervals and sort it all out */
        fun unionOf(intervals: List<I>): S {
            val l = intervals.filterIsInstance<I.NonEmpty>().sortedBy { it.low }
            if (l.isEmpty()) {
                return SEmpty
            }
            val res = arrayDequeOf(l.first())
            for (i in l.drop(1)) {
                val last = res.removeLast()
                res.addLasts(last union i)
            }
            return S(res)
        }

        fun unionOf(vararg intervals: I) = unionOf(intervals.asList())

        @JvmName("unionOf1")
        fun unionOf(intervals: List<S>) = unionOf(intervals.flatten())

        val SEmpty = S(emptyList())
        val SFull = S(IFull)
        val SFull256 = S(IFull256)
        val SFullBool = S(IFullBool)
        val SFullInt256math = S(Int256minMath, Int256max)

        val S2To256 = S(TwoTo256)
        val SmaxUint = S(MaxUInt)
        val STrue = S(ITrue)
        val SFalse = S(IFalse)

        fun sFull(modZm: ModZm) = S(BigInteger.ZERO, modZm.maxUnsigned)

        fun boolIntervals(surelyTrue: Boolean, surelyFalse: Boolean) =
            S(I.boolInterval(surelyTrue, surelyFalse))

        fun ite(i: S, t: S, e: S) =
            when (i) {
                SEmpty -> SEmpty
                STrue -> t
                SFalse -> e
                SFullBool -> t union e
                else -> error("ite cond has intervals value: $i")
            }

        fun timesAll(ops: List<S>): S =
            ops.reduce(S::times)

        fun plusAll(ops: List<S>): S =
            ops.reduce(S::plus)

        /**
         * This uses the fact lies at the heart of 2s complement multiplication:
         *     (x * y) % m = ((x - m) * y) % m
         * and the same can be applied to y.
         *
         * Should probably rewrite this to be more efficient.
         */
        fun mulMod(x: S, y: S, m: S): S {
            val x1 = x unsignedMod m
            val y1 = y unsignedMod m
            val shiftedX = x1 - m
            val shiftedY = y1 - m
            return listOf(
                (x1 * y1) unsignedMod m,
                (x1 * shiftedY) unsignedMod m,
                (shiftedX * y1) unsignedMod m,
                (shiftedX * shiftedY) unsignedMod m,
            ).reduce(S::intersect)
        }

        fun eqAsConsts(x: S, y: S) =
            x.isConst && x.asConstOrNull == y.asConstOrNull
    }


    infix fun union(other: S) =
        unionOf(this.intervals + other.intervals)

    infix fun intersect(other: S) = S(buildList {
        var x = 0
        var y = 0
        while (x < intervals.size && y < other.intervals.size) {
            val i = intervals[x]
            val j = other.intervals[y]
            // the one that ends first will never intersect anyone else.
            if (i.high <= j.high) {
                x++
            }
            if (j.high <= i.high) {
                y++
            }
            val r = i intersect j
            if (r is I.NonEmpty) {
                add(r)
            }
        }
    })

    infix fun containedIn(other: S) : Boolean {
        var x = 0
        var y = 0
        while (x < intervals.size && y < other.intervals.size) {
            val i = intervals[x]
            val j = other.intervals[y]

            when {
                i.low < j.low -> return false // x started before y
                i.high <= j.high -> x++ // x is within y
                i.low <= j.high -> return false // x started in y but ended outside
                else -> y++ // x is completely after y
            }
        }
        return x == intervals.size
    }

    infix fun atMost(l: ExtBig) =
        this intersect S(MInf, l)

    infix fun atLeast(l: ExtBig) =
        this intersect S(l, Inf)


    /** The union of running [f] on all pairwise combinations of single intervals from `this` and [other] */
    inline fun lift(other: S, f: (I, I) -> I): S =
        unionOf(
            flatMap { i ->
                other.map { j ->
                    f(i, j)
                }
            }
        )

    /** The union of running [f] on all pairwise combinations of single intervals from `this` and [other] */
    inline fun lift(other: ExtBig, f: (I, ExtBig) -> I): S =
        unionOf(
            map { i ->
                f(i, other)
            }
        )

    /** The union of running [f] on all intervals of `this` */
    private inline fun lift(f: (I) -> S): S =
        unionOf(flatMap(f))

    /** The union of running [f] on all pairwise combinations of single intervals from `this` and [other] */
    private inline fun lift1(other: S, f: (I, I) -> S): S =
        unionOf(
            flatMap { i ->
                other.flatMap { j ->
                    f(i, j)
                }
            }
        )

    /** The union of running [f] on all intervals of `this` */
    private inline fun lift1(f: (I) -> I): S =
        unionOf(map(f))


    operator fun plus(other: S) =
        lift(other, I::plus)

    operator fun minus(other: S) =
        lift(other, I::minus)

    operator fun times(other: S) =
        lift(other, I::times)

    operator fun times(other: ExtBig) =
        lift(other, I::times)

    operator fun times(other: BigInteger) =
        this * other.asExtBig

    operator fun div(other: S) =
        lift(other, I::div)

    infix fun exactDiv(other: S) =
        lift(other, I::exactDiv)

    infix fun unsignedMod(other: S) =
        lift1(other, I::unsignedMod)

    infix fun evmSignedMod(other: S) =
        lift1(other, I::evmSignedMod)

    infix fun cvlMod(other: S) =
        lift1(other, I::cvlMod)

    operator fun unaryMinus() =
        S(intervals.reversed().map(I::unaryMinus))

    fun mod(modZm: ModZm) =
        unsignedMod(S(modZm.modulus))

    infix fun pow(other: S) =
        lift(other, I::pow)

    fun log2() =
        lift1(I::log2)

    fun pow2Limited(modZm: ModZm) =
        lift1 { i -> i.pow2Limited(modZm) }

    infix fun bwAnd(other: S) =
        lift(other, I::bwAnd)

    infix fun bwOr(other: S) =
        lift(other, I::bwOr)

    infix fun bwXor(other: S) =
        lift(other, I::bwXor)

    fun bwNot(modZm: ModZm) =
        lift1 { it.bwNot(modZm) }

    fun signExtend(fromBit: Int, toBit: Int) =
        lift { it.signExtend(fromBit, toBit) }

    infix fun and(other: S): S {
        check(this.isBool && other.isBool)
        return when {
            this == SFalse || other == SFalse -> SFalse
            this == STrue -> other
            other == STrue -> this
            else -> SFullBool
        }
    }

    infix fun or(other: S): S {
        check(this.isBool && other.isBool)
        return when {
            this == STrue || other == STrue -> STrue
            this == SFalse -> other
            other == SFalse -> this
            else -> SFullBool
        }
    }

    operator fun not() =
        lift1(I::not)

    infix fun lt(other: S) =
        if (isEmpty() || other.isEmpty()) {
            SEmpty
        } else {
            boolIntervals(
                surelyTrue = this isLt other,
                surelyFalse = this isGe other
            )
        }

    infix fun le(other: S) =
        if (isEmpty() || other.isEmpty()) {
            SEmpty
        } else {
            boolIntervals(
                surelyTrue = this isLe other,
                surelyFalse = this isGt other
            )
        }

    infix fun ge(other: S) = other le this

    infix fun gt(other: S) = other lt this

    infix fun eq(other: S) =
        boolIntervals(
            surelyTrue = eqAsConsts(this, other),
            surelyFalse = this intersect other == SEmpty
        )

    fun delete(n: BigInteger) =
        S(flatMap { it.delete(n) })

    fun delete(n: Int) =
        delete(n.toBigInteger())

    fun toMathInt(modZm: ModZm) =
        unionOf(map { it.toMathInt(modZm) })

    fun fromMathInt(modZm: ModZm) =
        unionOf(map { it.fromMathInt(modZm) })

    fun sLt(other: S, modZm: ModZm) =
        this.toMathInt(modZm) lt other.toMathInt(modZm)

    fun sLe(other: S, modZm: ModZm) =
        this.toMathInt(modZm) le other.toMathInt(modZm)

    fun sGe(other: S, modZm: ModZm) =
        this.toMathInt(modZm) ge other.toMathInt(modZm)

    fun sGt(other: S, modZm: ModZm) =
        this.toMathInt(modZm) gt other.toMathInt(modZm)

    fun sDiv(other: S, modZm: ModZm) =
        (this.toMathInt(modZm) / other.toMathInt(modZm)).fromMathInt(modZm)
            .letIf(modZm.minSigned2s in this && modZm.maxUnsigned in other) {
                // this is EVM overflow behavior: `minInt256/-1 = minInt256`
                it union S(modZm.minSigned2s)
            }

    fun sMod(other: S, modZm: ModZm) =
        (this.toMathInt(modZm) evmSignedMod other.toMathInt(modZm)).fromMathInt(modZm)


    /**
     * Returns the intervals < [point] and those larger, possibly splitting an interval in the middle. Where and
     * if to include the cut point depends on [option]
     */
    fun cutAt(point: ExtBig, option: CutAtPoint): Pair<S, S> {
        val (smaller, larger) = map { it.cutAt(point, option) }.unzip()
        return S(smaller) to S(larger.reversed())
    }

    fun isSurely2sNeg(modZm: ModZm) = isNotEmpty() && min.is2sNeg(modZm)
    fun isSurely2sNonNeg(modZm: ModZm) = isNotEmpty() && max.is2sNonNeg(modZm)

    /**
     * If the number of [I]'s is larger than [maxNumIntervals] then an overapproximation of `this` is returned that
     * containes less [I]'s. Otherwise `this` is returned.
     */
    fun simplify(maxNumIntervals: Int) =
        if (size <= maxNumIntervals) {
            this
        } else {
            // improve
            S(min, max)
        }

    infix fun isLt(other : S) = this.max < other.min
    infix fun isLe(other : S) = this.max <= other.min
    infix fun isGt(other : S) = this.min > other.max
    infix fun isGe(other : S) = this.min >= other.max

    infix fun isLt(other : ExtBig) = this.max < other
    infix fun isLe(other : ExtBig) = this.max <= other
    infix fun isGt(other : ExtBig) = this.min > other
    infix fun isGe(other : ExtBig) = this.min >= other

    infix fun isLt(other : BigInteger) = isLt(other.asExtBig)
    infix fun isLe(other : BigInteger) = isLe(other.asExtBig)
    infix fun isGt(other : BigInteger) = isGt(other.asExtBig)
    infix fun isGe(other : BigInteger) = isLe(other.asExtBig)

    fun isSLt(other: S, modZm: ModZm) = this.toMathInt(modZm) isLt other.toMathInt(modZm)
    fun isSLe(other: S, modZm: ModZm) = this.toMathInt(modZm) isLe other.toMathInt(modZm)
    fun isSGe(other: S, modZm: ModZm) = this.toMathInt(modZm) isGe other.toMathInt(modZm)
    fun isSGt(other: S, modZm: ModZm) = this.toMathInt(modZm) isGt other.toMathInt(modZm)

    fun abs() = union(intervals.map { it.abs() })

    fun inUnsignedBounds(modZm: ModZm) : S =
        (this ge S(Zero)) and (this le S(modZm.maxUnsigned))

    fun inSignedBounds(modZm: ModZm) : S =
        (this ge S(modZm.minSignedMath)) and (this le S(modZm.maxSigned))



}
