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

import analysis.opt.intervals.BiPropagation.ifNonEmpty
import analysis.opt.intervals.ExtBig.Companion.One
import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.ExtBig.Inf
import analysis.opt.intervals.ExtBig.MInf
import analysis.opt.intervals.Interval.CutAtPoint
import analysis.opt.intervals.Intervals.Companion.SEmpty
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFull
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import analysis.opt.intervals.Intervals.Companion.mulMod
import analysis.opt.intervals.Intervals.Companion.plusAll
import analysis.opt.intervals.Intervals.Companion.timesAll
import analysis.opt.intervals.Intervals.Companion.unionOf
import analysis.split.Ternary.Companion.isPowOf2Minus1
import datastructures.stdcollections.*
import utils.*
import java.math.BigInteger
import analysis.opt.intervals.Intervals as S


/**
 * Functions that are used to restrict known `Intervals` over-approximation of lhs and arguments of operations.
 * For example, `mult(lhsIntervals, op1Intervals, op2Intervals)`, will try and find stricter intervals for all
 * three. It does so using the properties of multiplication. See the unit tests for this class to see some examples.
 *
 * In general, most functions here are transformation functions which take a list of `S` and return a list of `S` of
 * the same size. So the mult above will return a list of size 3 with the new respective values.
 */
object BiPropagation {

    private fun empty(i: Int) = List(i) { SEmpty }

    /**
     * A wrapper for functions where if any of the inputs or any of the outputs is empty, then all outputs should be
     * empty.
     */
    private inline fun ifNonEmpty(l: List<S>, f: () -> List<S>) =
        if (l.any { it.isEmpty() }) {
            empty(l.size)
        } else {
            f().let { res ->
                check(res.size == l.size)
                if (res.any { it.isEmpty() }) {
                    empty(l.size)
                } else {
                    res
                }
            }
        }

    // kotlin does not like varargs of value types for some reason.

    private inline fun ifNonEmpty(lhs: S, x: S, f: () -> List<S>) =
        ifNonEmpty(listOf(lhs, x), f)

    private inline fun ifNonEmpty(lhs: S, x: S, y: S, f: () -> List<S>) =
        ifNonEmpty(listOf(lhs, x, y), f)

    private inline fun ifNonEmpty(lhs: S, x: S, y: S, z: S, f: () -> List<S>) =
        ifNonEmpty(listOf(lhs, x, y, z), f)

    /**
     * Simple cases where [lhs] is a function of [x] and [y] but there is nothing we can say about [lhs]
     * Returns the new values for [lhs] and [x] and [y].
     */
    private inline fun justForward(lhs: S, x: S, y: S, crossinline operator: (S, S) -> S) =
        ifNonEmpty(lhs, x, y) {
            listOf(operator(x, y) intersect lhs, x, y)
        }


    /** [lhs] := [x] == [y] */
    fun equality(lhs: S, x: S, y: S) =
        ifNonEmpty(lhs, x, y) {
            when (lhs) {
                STrue -> {
                    val z = x intersect y
                    listOf(lhs, z, z)
                }

                SFalse -> {
                    val newX = y.asConstOrNull?.let { x.delete(it) } ?: x
                    val newY = x.asConstOrNull?.let { y.delete(it) } ?: y
                    listOf(lhs, newX, newY)
                }

                SFullBool ->
                    listOf(x eq y, x, y)

                else -> error("Should we handle? lhs = $lhs in equality?")
            }
        }


    /** Returns the constraining of [x] and [y] to the cases where everything in [x] is <= everything in [y] */
    private fun surelyLe(x: S, y: S): Pair<S, S> {
        val newX = x atMost y.max
        val newY = y atLeast x.min
        return if (newX.isEmpty() || newY.isEmpty()) {
            SEmpty to SEmpty
        } else {
            newX to newY
        }
    }

    /** Returns the constraining of [x] and [y] to the cases where everything in [x] is < everything in [y] */
    private fun surelyLt(x: S, y: S): Pair<S, S> {
        val newX = x atMost (y.max - One)
        val newY = y atLeast (x.min + One)
        return if (newX.isEmpty() || newY.isEmpty()) {
            SEmpty to SEmpty
        } else {
            newX to newY
        }
    }


    /** [lhs] := [x] <= [y] */
    fun le(lhs: S, x: S, y: S) =
        ifNonEmpty(lhs, x, y) {
            when (lhs) {
                STrue -> {
                    val (newX, newY) = surelyLe(x, y)
                    listOf(lhs, newX, newY)
                }

                SFalse -> {
                    val (newY, newX) = surelyLt(y, x)
                    listOf(lhs, newX, newY)
                }

                SFullBool ->
                    listOf(x le y, x, y)

                else -> error("Should we handle? lhs = $lhs in comparison")
            }
        }


    /** [lhs] := [x] < [y] */
    fun lt(lhs: S, x: S, y: S) =
        ifNonEmpty(lhs, x, y) {
            when (lhs) {
                STrue -> {
                    val (newX, newY) = surelyLt(x, y)
                    listOf(lhs, newX, newY)
                }

                SFalse -> {
                    val (newY, newX) = surelyLe(y, x)
                    listOf(lhs, newX, newY)
                }

                SFullBool ->
                    listOf(x lt y, x, y)

                else -> error("Should we handle? lhs = $lhs in comparison")
            }
        }


    /** given `[lhs, x, y]` return `[lhs, y, x]` */
    private fun List<S>.reverseXandY(): List<S> {
        check(size == 3)
        val (lhs, x, y) = this
        return listOf(lhs, y, x)
    }


    /** [lhs] := [x] >= [y] */
    fun ge(lhs: S, x: S, y: S) = le(lhs, y, x).reverseXandY()

    /** [lhs] := [x] > [y] */
    fun gt(lhs: S, x: S, y: S) = lt(lhs, y, x).reverseXandY()


    /**
     * transforms [x] and [y] (and if [convertLhs] then also [lhs]) to mathint form, i.e., from 2s complement to
     * possibly negative numbers. Then applies [f], and then transforms the results back 2s complement.
     */
    private inline fun inMathInt(lhs: S, x: S, y: S, convertLhs: Boolean, modZm: ModZm, f: (List<S>) -> List<S>): List<S> =
        ifNonEmpty(lhs, x, y) {
            val lhs1 = lhs.letIf(convertLhs) { it.toMathInt(modZm) }
            val x1 = x.toMathInt(modZm)
            val y1 = y.toMathInt(modZm)
            val (newLhs, newX, newY) = f(listOf(lhs1, x1, y1))
            return listOf(
                newLhs.letIf(convertLhs) { it.fromMathInt(modZm) },
                newX.fromMathInt(modZm),
                newY.fromMathInt(modZm)
            )
        }


    /** [lhs] := [x] s<= [y]  ---  s<= denoting a signed less than or equals operation */
    fun sLe(lhs: S, x: S, y: S, modZm: ModZm) =
        inMathInt(lhs, x, y, convertLhs = false, modZm) { (lhs1, x1, y1) ->
            le(lhs1, x1, y1)
        }


    /** [lhs] := [x] s< [y]  ---  s< denoting a signed less than operation */
    fun sLt(lhs: S, x: S, y: S, modZm: ModZm) =
        inMathInt(lhs, x, y, convertLhs = false, modZm) { (lhs1, x1, y1) ->
            lt(lhs1, x1, y1)
        }


    fun sGe(lhs: S, x: S, y: S, modZm: ModZm) = sLe(lhs, y, x, modZm).reverseXandY()

    fun sGt(lhs: S, x: S, y: S, modZm: ModZm) = sLt(lhs, y, x, modZm).reverseXandY()


    /** [lhs] := ![rhs] */
    fun not(lhs: S, rhs: S) =
        ifNonEmpty(lhs, rhs) {
            check(lhs.isBool && rhs.isBool) { "non bool intervals $lhs $rhs in not" }
            when {
                lhs == SFullBool && rhs == SFullBool -> listOf(lhs, rhs)
                lhs == SFullBool -> listOf(!rhs, rhs)
                rhs == SFullBool -> listOf(lhs, !lhs)
                lhs.asConst != rhs.asConst -> listOf(lhs, rhs)
                else -> empty(2)
            }
        }


    /** [lhs] := bitwiseNot([rhs]) */
    fun bwNot(lhs: S, rhs: S, modZm: ModZm) =
        ifNonEmpty(lhs, rhs) {
            val newLhs = lhs intersect rhs.bwNot(modZm)
            val newRhs = rhs intersect newLhs.bwNot(modZm)
            listOf(newLhs, newRhs)
        }


    fun and(l: List<S>) =
        ifNonEmpty(l) {
            check(l.size >= 2)
            check(l.all { it.isBool })
            val lhs = l[0]
            val args = l.drop(1) // `sublist` crashes here for some reason, so we use `drop`
            val newLhs = args.reduce(S::and) intersect lhs
            listOf(newLhs) + when {
                newLhs == STrue -> List(args.size) { STrue }
                newLhs == SEmpty -> empty(args.size)
                newLhs == SFalse && args.count { it == STrue } == args.size - 1 ->
                    args.map { ite(it == STrue, it, SFalse) }

                else -> args
            }
        }

    fun or(l: List<S>) =
        ifNonEmpty(l) {
            check(l.size >= 2)
            check(l.all { it.isBool })
            val lhs = l[0]
            val args = l.drop(1)
            val newLhs = args.reduce(S::or) intersect lhs
            listOf(newLhs) + when {
                newLhs == SFalse -> List(args.size) { SFalse }
                newLhs == SEmpty -> empty(args.size)
                newLhs == STrue && args.count { it == SFalse } == args.size - 1 ->
                    args.map { ite(it == SFalse, it, STrue) }

                else -> args
            }
        }


    /** [lhs] := [x] & [y] */
    fun bwAnd(lhs: S, x: S, y: S) =
        if (x.isBool && y.isBool) {
            and(listOf(lhs intersect SFullBool, x, y))
        } else {
            val newLhs = lhs intersect (x bwAnd y)
            when {
                newLhs.isEmpty() -> empty(3)
                // these cases are when the bwAnd is actually a no-op, e.g., in the first case:
                //    lhs = 0xffff & y
                // and `y` is known to be smaller than `0xffff`. In such a case, `lhs` is actually equal to `y`.
                x.isConst && x.asConst.isPowOf2Minus1 && y containedIn S(BigInteger.ZERO, x.asConst) ->
                    (newLhs intersect y).let { listOf(it, x, it) }
                y.isConst && y.asConst.isPowOf2Minus1 && x containedIn S(BigInteger.ZERO, y.asConst) ->
                    (newLhs intersect x).let { listOf(it, it, y) }
                else -> {
                    val newX = x intersect S(newLhs.min, Inf)
                    val newY = y intersect S(newLhs.min, Inf)
                    listOf(newLhs, newX, newY)
                }
            }
        }


    /** [lhs] := [x] | [y] */
    fun bwOr(lhs: S, x: S, y: S) =
        if ((x.isBool && y.isBool) || lhs.isBool) {
            or(listOf(lhs intersect SFullBool, x intersect SFullBool, y intersect SFullBool))
        } else {
            val newLhs = lhs intersect (x bwOr y)
            if (newLhs.isEmpty()) {
                empty(3)
            } else {
                val newX = x intersect S(Zero, newLhs.max)
                val newY = y intersect S(Zero, newLhs.max)
                listOf(newLhs, newX, newY)
            }
        }

    /** [lhs] := [x] xor [y] */
    fun bwXor(lhs: S, x: S, y: S) = run {
        // counts on the fact that a=b^c => a^b=b^c^b => a^b = c
        val newLhs = lhs intersect (x bwXor y)
        val newX = x intersect (newLhs bwXor y)
        val newY = y intersect (newLhs bwXor newX)
        listOf(newLhs, newX, newY)
    }

    /** l[0] := l[1] + l[2] + ... */
    fun plus(l: List<S>) =
        when (l.size) {
            0, 1 -> error("Can't have an empty addition")
            2 -> same(l[0], l[1])
            else ->
                ifNonEmpty(l) {
                    val lhs = l[0]
                    val args = l.subList(1, l.size)
                    val newLhs = args.reduce(S::plus) intersect lhs
                    val newArgs = args.indices.map { index ->
                        val sumOfOthers = plusAll(
                            args.indices.filter { it != index }.map(args::get)
                        )
                        val old = args[index]
                        (newLhs - sumOfOthers) intersect old
                    }
                    listOf(newLhs) + newArgs
                }
        }

    fun plus(lhs : S, x : S, y : S) =
        plus(listOf(lhs, x, y))


    /** [lhs] := [x] - [y] */
    fun minus(lhs: S, x: S, y: S) =
        // lhs=x-y <=> x=lhs+y
        plus(x, lhs, y).let { (newX, newLhs, newY) ->
            listOf(newLhs, newX, newY)
        }


    /**
     * `lhs := x1 * ... * xn`
     *
     * A reason this works simply is that the result of multiplication is always divisible by its operands. So no
     * need to think of remainders. This makes it work for negative numbers as well.
     *
     * Note that if lhs and an operand may be zero, there is actually nothing we can say about the other operand.
     */
    fun mult(l: List<S>) =
        when (l.size) {
            0, 1 -> error("Can't have an empty multiplication")
            2 -> same(l[0], l[1])
            else ->
                ifNonEmpty(l) {
                    val lhs = l[0]
                    val args = l.drop(1)
                    val newLhs = lhs intersect args.reduce(S::times)
                    val newArgs = args.indices.map { index ->
                        val prodOfOthers = timesAll(
                            args.indices.filter { it != index }.map(args::get)
                        )
                        val old = args[index]
                        (newLhs exactDiv prodOfOthers) intersect old
                    }
                    listOf(newLhs) + newArgs
                }
        }

    fun mult(lhs : S, x : S, y : S) =
        mult(listOf(lhs, x, y))


    /**
     * [lhs] := [x] / [y]
     *
     * 10 = x / 3 => x in [30..32]
     * -10 = x / 3 => x in [-32..-30]
     * -10 = x / -3 => x in [30..32]
     *
     * and also:
     * 0 = x / 3 => x in [-2..2]
     * 0 = x / -3 => x in [-2..2]
     *
     * It's either taking `lhs * y + [0,y-1]`, or `lhs * y - [0,y-1]`, depending on the sign of `lhs * y`
     *
     * 3 = 100 / y    =>   y in [26..33]
     * 3 = -100 / y   =>   y in [-33..-26]
     * -3 = -100 / y  =>   y in [26..33]
     *
     * So y is `(x / (lhs+1), x / lhs]` in the positive case, and the reverse in the negative. Note that if lhs is 0
     * then we get infinity here. That is `0 = 100 / y` means `y in (100, inf), (-100, inf)`
     */
    fun div(lhs: S, x: S, y: S) = ifNonEmpty(lhs, x, y) {
        if (0 in y) {
            return@ifNonEmpty listOf(lhs, x, y)
        }
        val newLhs = (x / y) intersect lhs

        // As in the comment above, but for every pair of intervals.
        val newX = x intersect newLhs.lift(y) { i, j ->
            val (low, high) = (i * j) as Interval.NonEmpty
            val d = j.norm() - 1
            Interval(
                low.letIf(low <= Zero) { (it - d)!! },
                high.letIf(high >= Zero) { (it + d)!! }
            )
        }

        val newY = y intersect run {
            unionOf(
                newLhs.flatMap { i ->
                    newX.flatMap { j ->
                        val res1 = runIf(Zero in i) {
                            when {
                                Zero in j -> return@run SFull
                                j.low > Zero -> S(MInf, -j.low - 1, j.low + 1, Inf)
                                j.high < Zero -> S(MInf, j.high - 1, -j.high + 1, Inf)
                                else -> `impossible!`
                            }
                        }?.intervals.orEmpty()

                        /** Both [lhsPart] and [xPart] should be strictly positive */
                        fun calc(lhsPart: Interval, xPart: Interval): Interval =
                            if (lhsPart is Interval.NonEmpty && xPart is Interval.NonEmpty) {
                                Interval(
                                    (xPart.low / (lhsPart.high + 1)).value + 1,
                                    (xPart.high / lhsPart.low).value
                                )
                            } else {
                                Interval.Empty
                            }

                        val (negLhs, posLhs) = i.cutAt(Zero, CutAtPoint.NONE)
                        // 0 is not interesting because we know lhs in never 0, so it can't contribute.
                        val (negX, posX) = j.cutAt(Zero, CutAtPoint.NONE)

                        res1 + listOf(
                            calc(posLhs, posX),
                            calc(-negLhs, -negX),
                            -calc(posLhs, -negX),
                            -calc(-negLhs, posX),
                        ) + Interval(0)
                        // we add 0 always to be safe. dividing by 0 can theoretically give any result, e.g.,
                        //    4 = 5 / 0
                    }
                })
        }

        listOf(newLhs, newX, newY)
    }


    /**
     * [lhs] := [x] s/ [y]  --- where s/ is signed division
     * This delegates to normal division on the mathint version, but also accounts for EVM overflow behavior:
     *      `minInt256 / -1 = minInt256`
     */
    fun sDiv(lhs: S, x: S, y: S, modZm: ModZm) =
        inMathInt(lhs, x, y, convertLhs = true, modZm) { (lhs1, x1, y1) ->
            div(lhs1, x1, y1)
        }.letIf(modZm.minSigned2s in lhs && modZm.minSigned2s in x && modZm.maxUnsigned in y) { (lhs2, x2, y2) ->
            listOf(lhs2 union S(modZm.minSigned2s) , x2 union S(modZm.minSigned2s), y2 union S(modZm.maxUnsigned))
        }


    /**
     * [lhs] := [x] % [y]
     * We have a few types of modulo, but the calculation here differs only in the value in `x % y`, the backwards
     * propagation is not affected (partially because we just assume the denominator is positive).
     */
    private fun mod(lhs: S, x: S, y: S, forwardRes: S) =
        ifNonEmpty(lhs, x, y) {
            val newLhs = lhs intersect forwardRes
            val c = y.asConstOrNull
            val newX =
                if (c == null || c <= BigInteger.ZERO ||  (x.max - x.min)!! > (c * 6).asExtBig) {
                    x
                } else {
                    // We want only the parts of `x` that can create `lhs` via mod `c`.
                    // for that we intersect `..., lhs-c, lhs, lhs+c, lhs+2c, ...` with `x`.
                    // to optimize this we start with the first offset that has a chance of a non empty intersection.
                    var offset = (x.min.n / c) * c
                    if (offset > x.min.n) { // happens if x.min.n is negative.
                        offset -= c
                    }
                    val l = buildList {
                        do {
                            addAll(newLhs + S(offset))
                            offset += c
                        } while (offset <= x.max.n)
                    }
                    unionOf(l) intersect x
                }
            listOf(newLhs, newX, y)
        }

    fun unsignedMod(lhs: S, x: S, y: S) =
        mod(lhs, x, y, x unsignedMod y)

    fun sMod(lhs: S, x: S, y: S, modZm: ModZm) =
        inMathInt(lhs, x, y, true, modZm) { (lhs1, x1, y1) ->
            mod(lhs1, x1, y1, x1 evmSignedMod y1)
        }

    fun cvlMod(lhs: S, x: S, y: S) =
        mod(lhs, x, y, x cvlMod y)

    /** [lhs] := [x] mod [modZm].modulus */
    fun mod(lhs: S, x: S, modZm: ModZm) =
        unsignedMod(lhs, x, S(modZm.modulus)).subList(0, 2)

    fun mulMod(lhs: S, x: S, y: S, m: S) =
        ifNonEmpty(lhs, x, y, m) {
            val (newLhs, mulRes, newM) = unsignedMod(
                lhs intersect mulMod(x, y, m),
                x * y,
                m
            )
            val (_, newX, newY) = mult(mulRes, x, y)
            listOf(newLhs, newX, newY, newM)
        }


    /**
     * [lhs] = 2^[x],
     * but an [x] larger than `[modZm].bitwidth` behaves like `[modZm].bitwidth`.
     * So more like:
     * [lhs] = [x] < width ? 2^[x] : 2^width
     */
    fun powOf2Limited(lhs: S, x: S, modZm: ModZm) =
        ifNonEmpty(lhs, x) {
            val newLhs = x.pow2Limited(modZm) intersect lhs
            val lhsLog2 = newLhs.log2()
                .letIf(modZm.modulus in newLhs) {
                    it union S(modZm.bitwidth.asExtBig, Inf)
                }
            val newX = x intersect lhsLog2
            listOf(newLhs, newX)
        }


    fun pow(lhs: S, x: S, y: S) =
        // one day we can make this more accurate
        justForward(lhs, x, y, S::pow)


    /** [lhs] := [x] signextended from [fromBit] to [toBit] */
    fun signExtend(lhs: S, x: S, fromBit: Int, toBit: Int) =
        ifNonEmpty(lhs, x) {
            val signExtended = x.signExtend(fromBit, toBit)
            if (x == signExtended) {
                // If the signExtend is actually a no-op.
                // This can only happen if all elements of `x` are already sign-extended. That means that anything known
                // to be not in `x` can't be in `lhs`, but also that any element known not to be in `lhs` is not in `x`.
                same(lhs, x)
            } else { // otherwise there is not much we can say about x
                listOf(signExtended intersect lhs, x)
            }
        }

    /**
     * [lhs] = if [i] then [t] else [e]
     *
     * N.B. we don't use [ifNonEmpty] here. Should we?
     */
    fun ite(lhs: S, i: S, t: S, e: S) =
        when (i) {
            STrue -> {
                val z = lhs intersect t
                listOf(z, i, z, e)
            }

            SFalse -> {
                val z = lhs intersect e
                listOf(z, i, t, z)
            }

            SFullBool -> {
                val restrictedT = lhs intersect t
                val restrictedE = lhs intersect e
                when {
                    // actually the first two cases should probably just cause everything to be empty
                    restrictedE == SEmpty -> listOf(restrictedT, STrue, restrictedT, e)
                    restrictedT == SEmpty -> listOf(restrictedE, SFalse, t, restrictedE)
                    else -> listOf(restrictedT union restrictedE, SFullBool, t, e)
                }
            }

            SEmpty -> empty(4)

            else -> error("expected boolean but got $i")
        }


    /** `l[0]` is contained in union of the rest of the elements of [l] */
    fun unionExt(l: List<S>) = run {
        val lhs = l[0]
        val rhss = l.subList(1, l.size)
        val newLhs = unionOf(rhss) intersect lhs
        listOf(newLhs) + rhss
    }


    /** [x] and [y] are known to be equal */
    fun same(x: S, y: S) =
        ifNonEmpty(x, y) {
            val n = x intersect y
            listOf(n, n)
        }


    /** [x] is in math-int and [lhs] the equivalent in 2s complement */
    fun mathintTo2s(lhs : S, x : S, modZm: ModZm) =
        ifNonEmpty(lhs, x) {
            val newLhs = lhs intersect x.fromMathInt(modZm)
            val newX = x intersect newLhs.toMathInt(modZm)
            listOf(newLhs, newX)
        }

    /** [x] is in twos-complement and [lhs] the equivalent in math-int */
    fun twosToMathint(lhs : S, x : S, modZm: ModZm) =
        mathintTo2s(x, lhs, modZm).reversed()

}
