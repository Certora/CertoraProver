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

import analysis.opt.intervals.ExtBig.Companion.Zero
import analysis.opt.intervals.ExtBig.Companion.asExtBig
import analysis.opt.intervals.Intervals.Companion.SFull
import analysis.opt.intervals.Intervals.Companion.SFullBool
import analysis.opt.intervals.Intervals.Companion.sFull
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.twoToThe
import utils.*
import vc.data.TACBuiltInFunction
import vc.data.TACExpr
import vc.data.getOperands
import analysis.opt.intervals.Intervals as S

object EdgeGenerator {

    /**
     * Adds edges to [HyperGraphFixedPointCalculator] based on the top level operator of [e], where [resultSpot]
     * is the vertex of the graph corresponding to [e], and [opSpots] are those corresponding to [e]'s operands.
     */
    fun <@Treapable Spt> HyperGraphFixedPointCalculator<Spt, S>.addEdgesOf(
        e: TACExpr, resultSpot: Spt, opSpots: List<Spt>, genAux : (S) -> Spt
    ) {

        val edgeName = "Edge${e.javaClass.simpleName}"

        val outModZm by lazy { e.tag as ModZm }
        val argsModZm by lazy { e.getOperands().first().tag as ModZm }

        fun spots() = listOf(resultSpot) + opSpots

        fun addVec(f: (List<S>) -> List<S>, name: String = edgeName, spots: List<Spt> = spots()) {
            addEdge(spots, f, name)
        }

        fun addUnary(f: (S, S) -> List<S>, name: String = edgeName, spots: List<Spt> = spots()) =
            addVec(
                { l ->
                    require(l.size == 2)
                    f(l[0], l[1])
                },
                name,
                spots.also { require(it.size == 2) }
            )

        fun addUnary(f: (S, S, ModZm) -> List<S>, modZm: ModZm = outModZm, name: String = edgeName, spots: List<Spt> = spots()) =
            addVec(
                { l ->
                    require(l.size == 2)
                    f(l[0], l[1], modZm)
                },
                name,
                spots.also { require(it.size == 2) }
            )

        fun addBinary(f: (S, S, S) -> List<S>, name: String = edgeName, spots: List<Spt> = spots()) =
            addVec(
                { l ->
                    require(l.size == 3)
                    f(l[0], l[1], l[2])
                },
                name,
                spots.also { require(it.size == 3) }
            )

        fun addBinary(f: (S, S, S, ModZm) -> List<S>, modZm : ModZm = outModZm, name: String = edgeName, spots: List<Spt> = spots()) =
            addVec(
                { l ->
                    require(l.size == 3)
                    f(l[0], l[1], l[2], modZm)
                },
                name,
                spots.also { require(it.size == 3) }
            )

        fun addTernary(f: (S, S, S, S) -> List<S>, name: String = edgeName, spots: List<Spt> = spots()) =
            addVec(
                { l ->
                    require(l.size == 4)
                    f(l[0], l[1], l[2], l[3])
                },
                name,
                spots.also { require(it.size == 4) }
            )


        fun addWithFinalMod(f: (List<S>) -> List<S>, name: String) {
            val spot = genAux(SFull)
            addVec(f, name, listOf(spot) + opSpots)
            addUnary(
                f = { lhs, x -> BiPropagation.mod(lhs, x, outModZm) },
                name = "mod${outModZm.bitwidth}",
                spots = listOf(resultSpot, spot)
            )
        }

        /**
         * Returns a new aux spot that represents `arg1.toMathint() op arg2.toMathint()`, i.e., it adds the edges
         * and auxiliary nodes that enforce it to be so.
         */
        fun opAsInt(arg1 : Spt, arg2 : Spt, modZm : ModZm, op: (S, S, S) -> List<S>) : Spt {
            val resAsInt = genAux(SFull)
            val asInt1 = genAux(SFull)
            val asInt2 = genAux(SFull)
            addUnary(
                f = BiPropagation::twosToMathint,
                modZm = modZm,
                name = "firstArg",
                spots = listOf(asInt1, arg1)
            )
            addUnary(
                f = BiPropagation::twosToMathint,
                modZm = modZm,
                name = "secondArg",
                spots = listOf(asInt2, arg2)
            )
            addBinary(
                f = op,
                name = "mathint_op",
                spots = listOf(resAsInt, asInt1, asInt2)
            )
            return resAsInt
        }


        /**
         * the [argSpot] is the argument to the no overflow/underflow check, which is itself the result of a different
         * operation, e.g., multiplication.
         */
        fun noSignedOverOrUnderflow(resSpot : Spt, argSpot : Spt, modZm: ModZm) {
            val overflow = genAux(SFullBool)
            val underflow = genAux(SFullBool)
            addUnary(
                f = { lhs, x -> BiPropagation.le(lhs, x, S(modZm.maxSigned)) },
                name = "noSignedOverflow_check",
                spots = listOf(overflow, argSpot)
            )
            addUnary(
                f = { lhs, x -> BiPropagation.ge(lhs, x, S(modZm.minSignedMath)) },
                name = "noSignedUnderflow_check",
                spots = listOf(underflow, argSpot)
            )
            addBinary(
                f = { lhs, x, y -> BiPropagation.and(listOf(lhs, x, y)) },
                name = "signed_overflow",
                spots = listOf(resSpot, underflow, overflow)
            )
        }

        when (e) {
            is TACExpr.Sym -> error("symbols don't have a corresponding hyper-graph edge.")

            is TACExpr.BinBoolOp -> when (e) {
                is TACExpr.BinBoolOp.LAnd ->
                    addVec(BiPropagation::and)

                is TACExpr.BinBoolOp.LOr ->
                    addVec(BiPropagation::or)
            }

            is TACExpr.TernaryExp -> when (e) {
                is TACExpr.TernaryExp.AddMod -> {
                    val (x, y, n) = opSpots
                    val sum = genAux(SFull)
                    addBinary(
                        f = BiPropagation::plus,
                        name = "addMod_add",
                        spots = listOf(sum, x, y)
                    )
                    addBinary(
                        f = BiPropagation::unsignedMod,
                        name = "addMod_mod",
                        spots = listOf(resultSpot, sum, n)
                    )
                }

                is TACExpr.TernaryExp.Ite ->
                    addTernary(BiPropagation::ite)

                is TACExpr.TernaryExp.MulMod ->
                    addTernary(BiPropagation::mulMod)
            }

            is TACExpr.UnaryExp -> when (e) {
                is TACExpr.UnaryExp.BWNot ->
                    addUnary(BiPropagation::bwNot)

                is TACExpr.UnaryExp.LNot ->
                    addUnary(BiPropagation::not)
            }

            is TACExpr.BinOp -> when (e) {
                is TACExpr.BinOp.BWAnd ->
                    addBinary(BiPropagation::bwAnd)

                is TACExpr.BinOp.BWOr ->
                    addBinary(BiPropagation::bwOr)

                is TACExpr.BinOp.BWXOr ->
                    addBinary(BiPropagation::bwXor)

                is TACExpr.BinOp.IntDiv,
                is TACExpr.BinOp.Div ->
                    addBinary(BiPropagation::div)

                is TACExpr.BinOp.IntExponent ->
                    addBinary(BiPropagation::pow)

                is TACExpr.BinOp.Exponent ->
                    addWithFinalMod(
                        f = { (lhs, x, y) -> BiPropagation.pow(lhs, x, y) },
                        name = "Exp"
                    )

                is TACExpr.BinOp.IntMod ->
                    addBinary(BiPropagation::cvlMod)

                is TACExpr.BinOp.Mod ->
                    addBinary(BiPropagation::unsignedMod)

                is TACExpr.BinOp.SMod ->
                    addBinary(BiPropagation::sMod)

                is TACExpr.BinOp.SDiv ->
                    addBinary(BiPropagation::sDiv)

                is TACExpr.BinOp.ShiftLeft -> {
                    val (base, exponent) = opSpots
                    val twoToExp = genAux(S(Zero, outModZm.modulus.asExtBig))
                    addUnary(
                        f = BiPropagation::powOf2Limited,
                        name = "shiftl_Pow2",
                        spots = listOf(twoToExp, exponent)
                    )
                    addTernary(
                        f = BiPropagation::mulMod,
                        name = "shiftl_mulMod",
                        spots = listOf(resultSpot, twoToExp, base, genAux(S(outModZm.modulus)))
                    )
                }

                is TACExpr.BinOp.ShiftRightLogical -> {
                    // result = base / 2^exponent
                    val (base, exponent) = opSpots
                    val twoToExp = genAux(S(Zero, outModZm.modulus.asExtBig))
                    addUnary(
                        f = BiPropagation::powOf2Limited,
                        name = "shiftr_pow2",
                        spots = listOf(twoToExp, exponent)
                    )
                    addBinary(
                        f = BiPropagation::div,
                        name = "shiftr_div",
                        spots = listOf(resultSpot, base, twoToExp)
                    )
                }

                is TACExpr.BinOp.ShiftRightArithmetical ->
                    e.o2.getAsConst()?.toIntOrNull()?.let {
                        val shiftBy = minOf(it, outModZm.bitwidth)
                        val op = opSpots[0]
                        val shifted = genAux(sFull(outModZm))
                        addBinary(
                            f = BiPropagation::div,
                            name = "sra_div",
                            spots = listOf(shifted, op, genAux(S(twoToThe(shiftBy))))
                        )
                        addUnary(
                            f = { lhs, rhs ->
                                BiPropagation.signExtend(lhs, rhs, outModZm.bitwidth - shiftBy, outModZm.bitwidth)
                            },
                            name = "sra_extend",
                            spots = listOf(resultSpot, shifted)
                        )
                    }

                is TACExpr.BinOp.SignExtend ->
                    e.o1.getAsConst()?.toIntOrNull()?.let { c ->
                        addUnary(
                            f = { lhs, rhs ->
                                BiPropagation.signExtend(lhs, rhs, (c + 1) * 8, outModZm.bitwidth)
                            },
                            spots = listOf(resultSpot, opSpots[1])
                        )
                    }

                is TACExpr.BinOp.IntSub ->
                    addBinary(BiPropagation::minus)

                is TACExpr.BinOp.Sub ->
                    addWithFinalMod(
                        f = { l ->
                            check(l.size == 3)
                            BiPropagation.minus(l[0], l[1], l[2])
                        },
                        name = "Sub"
                    )
            }


            is TACExpr.Vec -> when (e) {
                is TACExpr.Vec.IntAdd ->
                    addVec(BiPropagation::plus)

                is TACExpr.Vec.Add ->
                    addWithFinalMod(BiPropagation::plus, "Add")

                is TACExpr.Vec.IntMul -> {
                    addVec(BiPropagation::mult)
                }

                is TACExpr.Vec.Mul -> {
                    addBinary({ lhs, x, y ->
                        BiPropagation.mulMod(lhs, x, y, S(outModZm.modulus))
                    })
                }
            }

            is TACExpr.BinRel ->
                when (e) {
                    is TACExpr.BinRel.Eq ->
                        addBinary(BiPropagation::equality)

                    is TACExpr.BinRel.Ge ->
                        addBinary(BiPropagation::ge)

                    is TACExpr.BinRel.Gt ->
                        addBinary(BiPropagation::gt)

                    is TACExpr.BinRel.Le ->
                        addBinary(BiPropagation::le)

                    is TACExpr.BinRel.Lt ->
                        addBinary(BiPropagation::lt)

                    is TACExpr.BinRel.Slt ->
                        addBinary(BiPropagation::sLt, argsModZm)

                    is TACExpr.BinRel.Sle ->
                        addBinary(BiPropagation::sLe, argsModZm)

                    is TACExpr.BinRel.Sgt ->
                        addBinary(BiPropagation::sGt, argsModZm)

                    is TACExpr.BinRel.Sge ->
                        addBinary(BiPropagation::sGe, argsModZm)
                }


            is TACExpr.Apply ->
                when (val bif = (e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                    is TACBuiltInFunction.NoAddOverflowCheck -> {
                        val addition = genAux(SFull)
                        addVec(
                            f = BiPropagation::plus,
                            name = "noAddOverflowCheck_add",
                            spots = listOf(addition) + opSpots
                        )
                        addUnary(
                            f = { lhs, x -> BiPropagation.le(lhs, x, S(bif.tag.maxUnsigned)) },
                            name = "noAddOverflowCheck_check",
                            spots = listOf(resultSpot, addition)
                        )
                    }

                    is TACBuiltInFunction.SafeUnsignedNarrow,
                    is TACBuiltInFunction.UnsignedPromotion,
                    is TACBuiltInFunction.SafeMathNarrow,
                    is TACBuiltInFunction.SafeMathPromotion ->
                        addUnary(BiPropagation::same, "safeMathEquivalence")

                    is TACBuiltInFunction.SafeSignedNarrow ->
                        addUnary(
                            f = { lhs, x -> BiPropagation.bwAnd(lhs, x, S(bif.returnSort.maxUnsigned)) },
                            name = "SafeSignedNarrow",
                        )

                    is TACBuiltInFunction.SignedPromotion ->
                        addUnary(
                            f = { lhs, x -> BiPropagation.signExtend(lhs, x, bif.paramSort.bitwidth, bif.returnSort.bitwidth) },
                            name = "SafeSignedNarrow",
                        )

                    is TACBuiltInFunction.TwosComplement.Wrap ->
                        addUnary(f = BiPropagation::mathintTo2s, modZm = bif.tag, name = "2sWrap")

                    is TACBuiltInFunction.TwosComplement.Unwrap ->
                        addUnary(f = BiPropagation::twosToMathint, modZm = bif.tag, name = "2sUnwrap")

                    is TACBuiltInFunction.NoMulOverflowCheck -> {
                        val multiplication = genAux(SFull)
                        addVec(
                            f = BiPropagation::mult,
                            name = "noMulOverflowCheck_mul",
                            spots = listOf(multiplication) + opSpots
                        )
                        addUnary(
                            f = { lhs, x -> BiPropagation.le(lhs, x, S(bif.tag.maxUnsigned)) },
                            name = "noMulOverflowCheck_check",
                            spots = listOf(resultSpot, multiplication)
                        )
                    }

                    is TACBuiltInFunction.NoSMulOverAndUnderflowCheck -> {
                        check(opSpots.size == 2)
                        // creates a aux spot to hold the multiplication of the mathint version of the operands
                        // and connects that to the result spot via an no-under/over-flow edge.
                        noSignedOverOrUnderflow(
                            resultSpot,
                            opAsInt(opSpots[0], opSpots[1], bif.tag, BiPropagation::mult),
                            bif.tag
                        )
                    }

                    is TACBuiltInFunction.NoSAddOverAndUnderflowCheck -> {
                        check(opSpots.size == 2)
                        noSignedOverOrUnderflow(
                            resultSpot,
                            opAsInt(opSpots[0], opSpots[1], bif.tag, BiPropagation::plus),
                            bif.tag
                        )
                    }

                    is TACBuiltInFunction.NoSSubOverAndUnderflowCheck -> {
                        check(opSpots.size == 2)
                        noSignedOverOrUnderflow(
                            resultSpot,
                            opAsInt(opSpots[0], opSpots[1], bif.tag, BiPropagation::minus),
                            bif.tag
                        )
                    }

                    else -> Unit
                }

            else -> Unit
        }
    }

}
