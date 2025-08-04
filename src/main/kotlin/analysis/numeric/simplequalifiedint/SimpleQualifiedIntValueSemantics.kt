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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.numeric.simplequalifiedint

import analysis.LTACCmdView
import analysis.numeric.IntValue
import analysis.numeric.IntValueSemantics
import analysis.numeric.SimpleIntQualifier
import datastructures.stdcollections.plus
import datastructures.stdcollections.setOf
import utils.minus
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger

class SimpleQualifiedIntValueSemantics<S, W> : IntValueSemantics<SimpleQualifiedInt, IntValue, S, W>() {

    override fun lift(lb: BigInteger, ub: BigInteger): IntValue = IntValue(lb, ub)

    override fun lift(iVal: IntValue): SimpleQualifiedInt = SimpleQualifiedInt(iVal, setOf())

    override val nondet: SimpleQualifiedInt
            get() = SimpleQualifiedInt.nondet

    override fun evalVar(
        interp: SimpleQualifiedInt,
        s: TACSymbol.Var,
        toStep: S,
        input: S,
        whole: W,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): SimpleQualifiedInt {
        return super.evalVar(interp, s, toStep, input, whole, l).let { av ->
            av.copy(qual = av.qual + SimpleIntQualifier.MustEqual(s))
        }
    }

    override fun evalMod(
        v1: SimpleQualifiedInt,
        o1: TACSymbol,
        v2: SimpleQualifiedInt,
        o2: TACSymbol,
        toStep: S,
        input: S,
        whole: W,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ) = mod(v1, v2)


    override fun evalDiv(
        v1: SimpleQualifiedInt,
        o1: TACSymbol,
        v2: SimpleQualifiedInt,
        o2: TACSymbol,
        toStep: S,
        input: S,
        whole: W,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): SimpleQualifiedInt = super.evalDiv(v1, o1, v2, o2, toStep, input, whole, l).let { av ->
        if (v2.x.isConstant && v2.x.c != BigInteger.ZERO) {
            val lb = v1.x.lb.divide(v2.x.c)
            val ub = v1.x.ub.divide(v2.x.c)
            if (v2.x.c > BigInteger.ZERO) {
                av.withBoundAndQualifiers(lb, ub, quals = av.qual)
            } else {
                av.withBoundAndQualifiers(ub, lb, quals = av.qual)
            }
        } else {
            av
        }
    }
    companion object {
        fun mod(numerator: SimpleQualifiedInt, denominator: SimpleQualifiedInt) = when {
            numerator.x.ub < denominator.x.lb ->
                numerator
            else -> {
                val ub = numerator.x.ub.min(denominator.x.ub - 1)
                if (ub <= BigInteger.ZERO) {
                    SimpleQualifiedInt.nondet
                } else {
                    SimpleQualifiedInt(
                        qual = numerator.qual,
                        x = IntValue(BigInteger.ZERO, ub)
                    )
                }
            }
        }
    }
}
