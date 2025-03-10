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

package analysis.opt.overflow

import analysis.ExprView
import analysis.dataflow.StrictDefAnalysis
import analysis.opt.intervals.Intervals
import analysis.opt.intervals.Intervals.Companion.SFull256
import analysis.opt.intervals.Intervals.Companion.SFullInt256math
import analysis.opt.intervals.IntervalsCalculator
import analysis.patterns.InfoKey.VarKey
import analysis.patterns.PatternHelpers
import analysis.patterns.get
import utils.*
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACSymbol
import java.math.BigInteger


/**
 * Holds information about a specific operand (mul, add, sub), which we want to check whether it has overflow checks
 * or not.
 *
 * Note that [o1] and [o2] may be reversed when compared to the original operation - this is for checking the reversed
 * patterns on the same operation (and in the operation with const case, to normalize the constant to be second).
 */
sealed class OverflowContext(
    val def: StrictDefAnalysis,
    val intervals: IntervalsCalculator,
    val lcmd: ExprView<TACExpr>,
) {
    abstract val o1: TACSymbol
    abstract val o2: TACSymbol

    val ptr get() = lcmd.ptr
    val cmd get() = lcmd.cmd
    val lhs get() = lcmd.lhs
    val expr get() = lcmd.exp
    val sym1 get() = o1.asSym()
    val sym2 get() = o2.asSym()

    /**
     * Generates a pattern to match the exact variable [match] but only if source is the same as [src], i.e.,
     * If it is definitely the same as [match], and it's not reassigned somewhere that harms this equality.
     */
    fun matchVar(match: TACSymbol.Var, src: StrictDefAnalysis.Source = def.source(ptr, match)) =
        PatternHelpers {
            val key = VarKey() // just a temporary key
            v(key).mapResult2 { info, lcmd ->
                info.run {
                    val v = get(key)!!
                    takeIf { v == match && def.source(lcmd!!.ptr, v) == src }
                }
            }
        }


    /** Is this variable one of the variables in the operation */
    fun isBasicVar(v: TACSymbol) =
        v is TACSymbol.Var && (v == cmd.lhs || v == o1 || v == o2)

    private fun getIntervals(s: TACSymbol) =
        when (s) {
            is TACSymbol.Const -> Intervals(s.value)
            is TACSymbol.Var -> intervals.getS(ptr, s)
        }

    val intervals1 get() = getIntervals(o1)
    val intervals2 get() = getIntervals(o2)

    /**
     * This is true if it's clear from the known ranges of the operands that the multiplication doesn't need
     * the following mod 2^256.
     */
    val surelyNo256UnsignedMulOverflow by lazy {
        (intervals1 * intervals2) containedIn SFull256
    }

    /** Signed version */
    val surelyNo256SignedMulOverflow by lazy {
        (intervals1.toMathInt(modZ256) * intervals2.toMathInt(modZ256)) containedIn SFullInt256math
    }

    val surelyNo256SignedAddOverflow by lazy {
        (intervals1.toMathInt(modZ256) + intervals2.toMathInt(modZ256)) containedIn SFullInt256math
    }

    val surelyNo256SignedSubOverflow by lazy {
        (intervals1.toMathInt(modZ256) - intervals2.toMathInt(modZ256)) containedIn SFullInt256math
    }


    /** a pattern for matching the result of the operation */
    val res = matchVar(lhs, StrictDefAnalysis.Source.Defs(ptr))

    /** an operation on two variables, e.g., `x * y` */
    class Binary(
        def: StrictDefAnalysis,
        intervals: IntervalsCalculator,
        lcmd: ExprView<TACExpr>,
        override val o1: TACSymbol.Var,
        override val o2: TACSymbol.Var
    ) : OverflowContext(def, intervals, lcmd) {
        val op1 = matchVar(o1)
        val op2 = matchVar(o2)
    }

    /** An operation on a variable and a constant, e.g., `5 * x` */
    class Const(
        def: StrictDefAnalysis,
        intervals: IntervalsCalculator,
        lcmd: ExprView<TACExpr>,
        override val o1: TACSymbol.Var,
        val const: BigInteger
    ) : OverflowContext(def, intervals, lcmd) {
        override val o2 = const.asTACSymbol()
        val op = matchVar(o1)
        val cc = PatternHelpers.c(const)

        val opIntervals get() = intervals1
    }
}
