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

import analysis.opt.intervals.Intervals.Companion.SEmpty
import analysis.opt.intervals.Intervals.Companion.SFalse
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.STrue
import analysis.opt.intervals.Intervals.Companion.mulMod
import analysis.opt.intervals.Intervals.Companion.sFull
import analysis.opt.intervals.IntervalsCalculator.Companion.calcOneVarExpression
import analysis.opt.intervals.IntervalsCalculator.Companion.intervalOfTag
import evm.twoToThe
import tac.Tag
import utils.*
import vc.data.TACBuiltInFunction
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.getOperands
import vc.data.tacexprutil.eval
import java.math.BigInteger
import analysis.opt.intervals.Intervals as S


/**
 * A utility class for calculating the `Intervals` of an expression given the `Intervals` of its subexpressions.
 */
object ForwardCalculator {

    /**
     * "flat" means this doen't go into recursion.
     * [values] is the `Intervals` of each operand of the top expression of [e].
     */
    fun flatEval(e: TACExpr, values: List<S>): S {

        val outModZm by lazy { e.tag as Tag.Bits }
        val argModZm by lazy { e.getOperands().first().tag as Tag.Bits }

        e.eval(values, S::asConstOrNull)?.let {
            return S(it)
        }

        return when (e) {
            is TACExpr.Sym -> error("Called flatEval with a symbol: $e")

            is TACExpr.UnaryExp -> {
                check(values.size == 1)
                val i = values[0]
                when (e) {
                    is TACExpr.UnaryExp.BWNot -> i.bwNot(outModZm)
                    is TACExpr.UnaryExp.LNot -> !i
                }
            }

            is TACExpr.Vec -> {
                when (e) {
                    is TACExpr.Vec.IntAdd -> values.reduce(S::plus)
                    is TACExpr.Vec.Add -> values.reduce(S::plus).mod(outModZm)

                    is TACExpr.Vec.IntMul -> values.reduce(S::times)
                    is TACExpr.Vec.Mul -> values.reduce { a, b -> mulMod(a, b, S(outModZm.modulus)) }
                }
            }

            is TACExpr.BinOp -> {
                check(values.size == 2)
                val (i1, i2) = values
                when (e) {
                    is TACExpr.BinOp.BWAnd -> i1 bwAnd i2
                    is TACExpr.BinOp.BWOr -> i1 bwOr i2
                    is TACExpr.BinOp.BWXOr -> i1 bwXor i2

                    is TACExpr.BinOp.Div, is TACExpr.BinOp.IntDiv -> i1 / i2
                    is TACExpr.BinOp.SDiv -> i1.sDiv(i2, outModZm)

                    is TACExpr.BinOp.Exponent -> (i1 pow i2).mod(outModZm)
                    is TACExpr.BinOp.IntExponent -> i1 pow i2

                    is TACExpr.BinOp.Mod -> i1 unsignedMod i2
                    is TACExpr.BinOp.IntMod -> i1 cvlMod i2
                    is TACExpr.BinOp.SMod -> i1.sMod(i2, outModZm)

                    is TACExpr.BinOp.ShiftLeft -> (i1 * i2.pow2Limited(outModZm)).mod(outModZm)
                    is TACExpr.BinOp.ShiftRightLogical -> i1 / i2.pow2Limited(outModZm)
                    is TACExpr.BinOp.ShiftRightArithmetical ->
                        i2.asConstOrNull?.toIntOrNull()
                            ?.let {
                                val shiftBy = minOf(it, outModZm.bitwidth)
                                (i1 / S(twoToThe(shiftBy))).signExtend(outModZm.bitwidth - it, outModZm.bitwidth)
                            }
                            ?: sFull(outModZm)

                    is TACExpr.BinOp.SignExtend ->
                        i1.asConstOrNull?.toIntOrNull()
                            ?.let { i2.signExtend((it + 1) * 8, outModZm.bitwidth) }
                            ?: sFull(outModZm)

                    is TACExpr.BinOp.IntSub -> i1 - i2
                    is TACExpr.BinOp.Sub -> (i1 - i2).mod(outModZm)
                }
            }

            is TACExpr.TernaryExp -> {
                check(values.size == 3)
                val (i1, i2, i3) = values
                when (e) {
                    is TACExpr.TernaryExp.Ite -> S.ite(i1, i2, i3)
                    is TACExpr.TernaryExp.AddMod -> (i1 + i2) unsignedMod i3
                    is TACExpr.TernaryExp.MulMod -> mulMod(i1, i2, i3)
                }
            }

            is TACExpr.BinBoolOp ->
                when (e) {
                    is TACExpr.BinBoolOp.LAnd -> values.reduce(S::and)
                    is TACExpr.BinBoolOp.LOr -> values.reduce(S::or)
                }


            is TACExpr.BinRel -> {
                check(values.size == 2)
                val (i1, i2) = values
                when (e) {
                    is TACExpr.BinRel.Eq -> i1 eq i2
                    is TACExpr.BinRel.Ge -> i1 ge i2
                    is TACExpr.BinRel.Gt -> i1 gt i2
                    is TACExpr.BinRel.Le -> i1 le i2
                    is TACExpr.BinRel.Lt -> i1 lt i2
                    is TACExpr.BinRel.Sge -> i1.sGe(i2, argModZm)
                    is TACExpr.BinRel.Sgt -> i1.sGt(i2, argModZm)
                    is TACExpr.BinRel.Sle -> i1.sLe(i2, argModZm)
                    is TACExpr.BinRel.Slt -> i1.sLt(i2, argModZm)
                }
            }

            is TACExpr.Apply -> {
                val bif = (e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif
                if (bif != null) {
                    check(bif.paramSorts.size == values.size)
                }
                when (bif) {

                    is TACBuiltInFunction.SafeMathPromotion ->
                        values[0]

                    is TACBuiltInFunction.SafeMathNarrow.Implicit ->
                        values[0] intersect sFull(bif.returnSort)

                    is TACBuiltInFunction.SafeMathNarrow.Assuming ->
                        values[0] intersect S(BigInteger.ZERO, bif.upperBound)

                    is TACBuiltInFunction.UnsignedPromotion ->
                        values[0]

                    is TACBuiltInFunction.SafeUnsignedNarrow ->
                        values[0] intersect sFull(bif.returnSort)

                    is TACBuiltInFunction.SafeSignedNarrow ->
                        values[0] bwAnd S(bif.returnSort.maxUnsigned)

                    is TACBuiltInFunction.SignedPromotion ->
                        values[0].signExtend(bif.paramSort.bitwidth, bif.returnSort.bitwidth)

                    is TACBuiltInFunction.NoAddOverflowCheck ->
                        (values[0] + values[1]).inUnsignedBounds(bif.tag)

                    is TACBuiltInFunction.TwosComplement.Wrap ->
                        values[0].fromMathInt(bif.tag)

                    is TACBuiltInFunction.TwosComplement.Unwrap ->
                        values[0].toMathInt(bif.tag)

                    is TACBuiltInFunction.NoMulOverflowCheck ->
                        (values[0] * values[1]).inUnsignedBounds(bif.tag)

                    is TACBuiltInFunction.NoSMulOverAndUnderflowCheck ->
                        (values[0].toMathInt(bif.tag) * values[1].toMathInt(bif.tag)).inSignedBounds(bif.tag)

                    is TACBuiltInFunction.NoSAddOverAndUnderflowCheck ->
                        (values[0].toMathInt(bif.tag) + values[1].toMathInt(bif.tag)).inSignedBounds(bif.tag)

                    is TACBuiltInFunction.NoSSubOverAndUnderflowCheck ->
                        (values[0].toMathInt(bif.tag) - values[1].toMathInt(bif.tag)).inSignedBounds(bif.tag)

                    else -> intervalOfTag(e.tagAssumeChecked)
                }
            }

            else -> intervalOfTag(e.tagAssumeChecked)
        }
    }


    /**
     * Calculates the result [S] of [exp] given the values of the variables via [map].
     */
    fun eval(exp: TACExpr, map: Map<TACSymbol.Var, S>): S {
        calcOneVarExpression(exp)?.let { (v, calculated) ->
            val known = map[v] ?: intervalOfTag(v.tag)
            return when (calculated intersect known) {
                known -> STrue
                SEmpty -> SFalse
                else -> SFullBool
            }
        }

        fun rec(e: TACExpr): S =
            when (e) {
                is TACExpr.Sym.Const -> S(e.s.value)
                is TACExpr.Sym.Var -> map[e.s] ?: intervalOfTag(e.s.tag)
                else -> flatEval(e, e.getOperands().map(::rec))
            }
        return rec(exp)
    }
}
