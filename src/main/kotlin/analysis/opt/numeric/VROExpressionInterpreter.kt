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

package analysis.opt.numeric

import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.numeric.IValueSemantics
import analysis.numeric.NonRelationalExpressionInterpreter
import analysis.numeric.OpenInterval
import analysis.opt.intervals.Interval
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import vc.data.TACBuiltInFunction
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

/**
 * Basic VRO expression interpreter, which elaborates the [VROInt] [valueSemantics] to propagate that [vc.data.TACBuiltInFunction.SafeMathNarrow]
 * is the identity function, and to propagate that `!(x == y)` gives the qualifier `x != y` (TAC not having an Ne expression form).
 *
 * Handles killing qualifiers on assignment, delegates the actual updating of the state [S] to the `plus` abstraction function.
 */
abstract class VROExpressionInterpreter<S: Map<TACSymbol.Var, VROInt>>(override val valueSemantics: IValueSemantics<S, VROInt, Any>) : NonRelationalExpressionInterpreter<S, VROInt, Any>() {
    override val nondet: VROInt
        get() = VROInt.nondetFull

    override fun stepExpression(
        lhs: TACSymbol.Var,
        rhs: TACExpr,
        toStep: S,
        input: S,
        whole: Any,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): S {
        if(rhs is TACExpr.Apply && rhs.ops.size == 1) {
            val default by lazy {
                super.stepExpression(lhs, rhs, toStep, input, whole, l)
            }
            val opValue by lazy {
                (rhs.ops.single() as? TACExpr.Sym.Var)?.s?.let(toStep::get)
            }
            when (rhs.f) {
                TACBuiltInFunction.SafeMathNarrow(Tag.Bit256).toTACFunctionSym() -> {
                    val op = opValue ?: return default
                    return this.assign(
                        toStep = toStep,
                        lhs = lhs,
                        // the failure of this cast strongly suggests a failure
                        newValue = op.copy(
                            x = OpenInterval(
                                (op.x.interval intersect Interval.IFull256) as? Interval.NonEmpty ?: Interval.IFull256
                            )
                        ),
                        input = input,
                        whole = whole,
                        wrapped = l.wrapped
                    )
                }
                TACBuiltInFunction.SafeMathPromotion(Tag.Bit256).toTACFunctionSym() -> {
                    return this.assign(
                        lhs = lhs,
                        toStep = toStep,
                        input = input,
                        whole = whole,
                        newValue = opValue ?: return default,
                        wrapped = l.wrapped
                    )
                }
                TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256).toTACFunctionSym() -> {
                    val newApprox = opValue?.x?.interval?.toMathInt(Tag.Bit256)?.toSingle()?.let(::OpenInterval) ?: return default
                    return this.assign(
                        lhs = lhs,
                        toStep = toStep,
                        input = input,
                        whole = whole,
                        newValue = VROInt(newApprox, treapSetOf()),
                        wrapped = l.wrapped
                    )
                }
                TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256).toTACFunctionSym() -> {
                    val newValue = opValue?.x?.interval?.fromMathInt(Tag.Bit256)?.toSingle()?.let(::OpenInterval) ?: return default
                    return this.assign(
                        lhs = lhs,
                        toStep = toStep,
                        input = input,
                        whole = whole,
                        newValue = VROInt(newValue, treapSetOf()),
                        wrapped = l.wrapped
                    )
                }
                else -> {
                    return default
                }
            }
        } else if(rhs is TACExpr.UnaryExp.LNot && rhs.o is TACExpr.BinRel.Eq && rhs.o.operandsAreSyms()) {
            val v1 = toStep.interp(rhs.o.o1AsTACSymbol())
            val v2 = toStep.interp(rhs.o.o2AsTACSymbol())
            return this.valueSemantics.evalEq(
                whole = whole,
                toStep = toStep,
                input = input,
                l = l,
                o1 = rhs.o.o1AsTACSymbol(),
                v1 = v1,
                o2 =  rhs.o.o2AsTACSymbol(),
                v2 =  v2
            ).let {
                it.copy(
                    x = if (it.x.isConstant) {
                            when (it.x.c) {
                                BigInteger.ZERO -> {
                                    OpenInterval(Interval(BigInteger.ONE, BigInteger.ONE))
                                }
                                BigInteger.ONE -> {
                                    OpenInterval(Interval(BigInteger.ZERO, BigInteger.ZERO))
                                }
                                else -> {
                                    OpenInterval(Interval.IFullBool)
                                }
                            }
                        } else {
                           OpenInterval(Interval.IFullBool)
                        },
                    qual = it.qual.mapNotNullToTreapSet {
                        (it as? Flippable)?.flip()
                    }
                )
            }.let {
                assign(
                    toStep = toStep,
                    input = input,
                    whole = whole,
                    lhs = lhs,
                    newValue = it,
                    wrapped = l.wrapped
                )
            }
        }
        return super.stepExpression(lhs, rhs, toStep, input, whole, l)
    }

    abstract operator fun S.plus(entry: Pair<TACSymbol.Var, VROInt>) : S

    override fun assign(
        toStep: S,
        lhs: TACSymbol.Var,
        newValue: VROInt,
        input: S,
        whole: Any,
        wrapped: LTACCmd
    ): S {
        val newValueInterval = when(lhs.tag) {
            Tag.Bool -> {
                (Interval.IFullBool intersect newValue.x.interval) as Interval.NonEmpty
            }
            Tag.Bit256 -> {
                if(newValue.x.interval containedIn Interval.IFull256) {
                    newValue.x.interval
                } else {
                    // indicates overflow of some sort occurred
                    Interval.IFull256
                }
            }
            else -> newValue.x.interval
        }
        val newValueCleaned = newValue.copy(x = OpenInterval(newValueInterval))
        return if (newValueCleaned.qual.any {
                it.relates(lhs)
            }) {
            toStep + (lhs to newValueCleaned.withQualifiers(newValueCleaned.qual.filter {
                !it.relates(lhs)
            }))
        } else {
            toStep + (lhs to newValueCleaned)
        }
    }

    override fun interp(
        o1: TACSymbol,
        toStep: S,
        input: S,
        whole: Any,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): VROInt {
        return input.interp(o1)
    }

    override fun liftConstant(value: BigInteger): VROInt = VROInt(OpenInterval(value), treapSetOf())
}
