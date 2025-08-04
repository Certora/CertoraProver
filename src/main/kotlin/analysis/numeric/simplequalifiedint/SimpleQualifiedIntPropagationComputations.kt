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

import analysis.LTACCmd
import analysis.numeric.ConditionQualifier
import analysis.numeric.PathInformation
import analysis.numeric.QualifierPropagationComputation
import analysis.numeric.SimpleIntQualifier
import datastructures.stdcollections.listOf
import datastructures.stdcollections.mapOf
import datastructures.stdcollections.merge
import datastructures.stdcollections.plus
import vc.data.TACSymbol
import java.math.BigInteger

class IntervalQualifierPropagationComputation<in W>: QualifierPropagationComputation<SimpleQualifiedInt, SimpleQualifiedIntState, W, SimpleIntQualifier>() {
    override fun extractValue(op1: TACSymbol.Var, s: SimpleQualifiedIntState, w: W, l: LTACCmd) =
        s.s[op1] ?: SimpleQualifiedInt.nondet

    override fun propagateTrue(
        v: TACSymbol.Var,
        av: SimpleQualifiedInt,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Map<TACSymbol.Var, List<PathInformation<SimpleIntQualifier>>>? {
        return super.propagateTrue(v, av, s, w, l)?.merge(
            if (av.x.ub <= BigInteger.ONE) {
                mapOf(v to listOf(PathInformation.StrictEquality(null, BigInteger.ONE)))
            } else {
                mapOf(v to listOf(PathInformation.StrictInequality(null, BigInteger.ZERO)))
            }
        ) { _, l1, l2 -> l1.orEmpty() + l2.orEmpty() }
    }

    override fun propagateFalse(
        v: TACSymbol.Var,
        av: SimpleQualifiedInt,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Map<TACSymbol.Var, List<PathInformation<SimpleIntQualifier>>>? {
        return super.propagateFalse(v, av, s, w, l)?.merge(
            mapOf(v to listOf(PathInformation.StrictEquality(null, BigInteger.ZERO)))
        ) { _, l1, l2 -> l1.orEmpty() + l2.orEmpty() }
    }

    private fun conditionMayBeTrue(cond: ConditionQualifier.Condition, op1: TACSymbol, op2: TACSymbol, s: SimpleQualifiedIntState): Boolean {
        val av1 = s.interpret(op1)
        val av2 = s.interpret(op2)
        return when (cond) {
            ConditionQualifier.Condition.EQ ->
                av1.x.mayIntersect(av2.x)

            ConditionQualifier.Condition.NEQ ->
                // ! (av1.x.c == av2.x.c)
                !av1.x.isConstant || !av2.x.isConstant || av1.x.c != av2.x.c

            ConditionQualifier.Condition.LT ->
                av1.x.lb < av2.x.ub

            ConditionQualifier.Condition.LE ->
                av1.x.lb <= av2.x.ub

            ConditionQualifier.Condition.SLT,
            ConditionQualifier.Condition.SLE ->
                true
        }
    }

    override fun strictEquality(
        toRet: TACSymbol.Var,
        v: MutableList<PathInformation<SimpleIntQualifier>>,
        sym: TACSymbol.Var?,
        num: BigInteger?,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ) {
        super.strictEquality(toRet, v, sym, num, s, w, l)
        if (sym != null) {
            v.add(PathInformation.Qual(SimpleIntQualifier.MustEqual(sym)))
        }
    }

    override fun propagateEq(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean = conditionMayBeTrue(ConditionQualifier.Condition.EQ, op1, op2, s)
        && super.propagateEq(op1, op2, toRet, s, w, l)

    override fun propagateNe(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.NEQ, op1, op2, s)
            && super.propagateNe(op1, op2, toRet, s, w, l)

    override fun propagateLt(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.LT, op1, op2, s)
            && super.propagateLt(op1, op2, toRet, s, w, l)

    override fun propagateLe(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.LE, op1, op2, s)
            && super.propagateLe(op1, op2, toRet, s, w, l)


    override fun propagateSlt(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.SLT, op1, op2, s)
            && super.propagateSlt(op1, op2, toRet, s, w, l)


    override fun propagateSle(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: SimpleQualifiedIntState,
        w: W,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.SLE, op1, op2, s)
            && super.propagateSle(op1, op2, toRet, s, w, l)

}
