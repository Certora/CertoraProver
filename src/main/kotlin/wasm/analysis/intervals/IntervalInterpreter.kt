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

package wasm.analysis.intervals

import analysis.CmdPointer
import datastructures.stdcollections.*
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.TACCommandGraph
import analysis.numeric.*
import com.certora.collect.*
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSummary
import vc.data.TACSymbol
import java.math.BigInteger

typealias State = TreapMap<TACSymbol.Var, SimpleQualifiedInt>

fun State.interpret(e: TACExpr): SimpleQualifiedInt? {
    return (e as? TACExpr.Sym)?.let {
        IntervalInterpreter.interp(it.s, this)
    }
}

class UnreachableState(val s: String, val loc: CmdPointer) : Exception(s)

class IntervalValueSemantics : StatelessUIntValueSemantics<SimpleQualifiedInt, IntValue>() {

    override fun lift(lb: BigInteger, ub: BigInteger): IntValue = IntValue(lb, ub)

    override fun lift(iVal: IntValue): SimpleQualifiedInt = SimpleQualifiedInt(iVal, setOf())

    override val nondet: SimpleQualifiedInt
            get() = SimpleQualifiedInt.nondet

    override fun evalVar(
        interp: SimpleQualifiedInt,
        s: TACSymbol.Var,
        toStep: Any,
        input: Any,
        whole: Any,
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
        toStep: Any,
        input: Any,
        whole: Any,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ) = mod(v1, v2)

    internal fun mod(numerator: SimpleQualifiedInt, denominator: SimpleQualifiedInt) = when {
        numerator.x.ub < denominator.x.lb ->
            numerator
        else ->
            SimpleQualifiedInt(
                qual = numerator.qual,
                x = IntValue(
                    lb = BigInteger.ZERO,
                    ub = numerator.x.ub.min(denominator.x.ub - 1)
                )
            )
    }

    override fun evalDiv(
        v1: SimpleQualifiedInt,
        o1: TACSymbol,
        v2: SimpleQualifiedInt,
        o2: TACSymbol,
        toStep: Any,
        input: Any,
        whole: Any,
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

}

class IntervalExpressionInterpreter: NonRelationalExpressionInterpreter<State, SimpleQualifiedInt, State>() {
    override val nondet
        get() = SimpleQualifiedInt.nondet

    override val valueSemantics = IntervalValueSemantics()
        .withPathConditions(
            condition = SimpleIntQualifier::Condition,
            conjunction = SimpleIntQualifier::LogicalConnective,
            flip = SimpleIntQualifier::flip
        ).withBasicMath(
            multipleOf = SimpleIntQualifier::MultipleOf,
            maskedOf = { _, _ -> null }
        ).withModularUpperBounds(
            modularUpperBound = SimpleIntQualifier::ModularUpperBound,
            extractModularUpperBound = { it as? SimpleIntQualifier.ModularUpperBound }
        )

    override fun liftConstant(value: BigInteger) = SimpleQualifiedInt(IntValue.Constant(value), setOf())

    override fun interp(
        o1: TACSymbol,
        toStep: State,
        input: State,
        whole: State,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ) = IntervalInterpreter.interp(o1, toStep)

    override fun assign(
        toStep: State,
        lhs: TACSymbol.Var,
        newValue: SimpleQualifiedInt,
        input: State,
        whole: State,
        wrapped: LTACCmd
    ) = toStep.put(lhs, newValue)
}

class IntervalQualifierPropagationComputation: QualifierPropagationComputation<SimpleQualifiedInt, State, Any, SimpleIntQualifier>() {
    override fun extractValue(op1: TACSymbol.Var, s: State, w: Any, l: LTACCmd) =
        s[op1] ?: SimpleQualifiedInt.nondet

    override fun propagateTrue(
        v: TACSymbol.Var,
        av: SimpleQualifiedInt,
        s: State,
        w: Any,
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
        s: State,
        w: Any,
        l: LTACCmd
    ): Map<TACSymbol.Var, List<PathInformation<SimpleIntQualifier>>>? {
        return super.propagateFalse(v, av, s, w, l)?.merge(
            mapOf(v to listOf(PathInformation.StrictEquality(null, BigInteger.ZERO)))
        ) { _, l1, l2 -> l1.orEmpty() + l2.orEmpty() }
    }

    private fun conditionMayBeTrue(cond: ConditionQualifier.Condition, op1: TACSymbol, op2:TACSymbol, s:State): Boolean {
        val av1 = IntervalInterpreter.interp(op1, s)
        val av2 = IntervalInterpreter.interp(op2, s)
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
        s: State,
        w: Any,
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
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean = conditionMayBeTrue(ConditionQualifier.Condition.EQ, op1, op2, s)
        && super.propagateEq(op1, op2, toRet, s, w, l)

    override fun propagateNe(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.NEQ, op1, op2, s)
            && super.propagateNe(op1, op2, toRet, s, w, l)

    override fun propagateLt(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.LT, op1, op2, s)
            && super.propagateLt(op1, op2, toRet, s, w, l)

    override fun propagateLe(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.LE, op1, op2, s)
            && super.propagateLe(op1, op2, toRet, s, w, l)


    override fun propagateSlt(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.SLT, op1, op2, s)
            && super.propagateSlt(op1, op2, toRet, s, w, l)


    override fun propagateSle(
        op1: TACSymbol,
        op2: TACSymbol,
        toRet: MutableMap<TACSymbol.Var, MutableList<PathInformation<SimpleIntQualifier>>>,
        s: State,
        w: Any,
        l: LTACCmd
    ): Boolean =
        conditionMayBeTrue(ConditionQualifier.Condition.SLE, op1, op2, s)
            && super.propagateSle(op1, op2, toRet, s, w, l)

}

class IntervalInterpreter(
    private val qualifierManager: QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, State>,
): AbstractAbstractInterpreter<State, State>() {

    companion object {
        operator fun invoke(graph: TACCommandGraph): IntervalInterpreter {
            val qualifierManager = object : QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, State>(me = graph.cache.gvn) {
                override fun mapValues(s: State, mapper: (TACSymbol.Var, SimpleQualifiedInt) -> SimpleQualifiedInt): State {
                    return s.updateValues(transform = mapper)
                }

                override fun assignVar(toStep: State, lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: LTACCmd): State {
                    if(toWrite == SimpleQualifiedInt.nondet) {
                        return toStep - lhs
                    }
                    return toStep.put(lhs, toWrite)
                }
            }

            return IntervalInterpreter(qualifierManager)
        }

        fun interp(o: TACSymbol, state: State): SimpleQualifiedInt =
            when (o) {
                is TACSymbol.Const -> SimpleQualifiedInt(IntValue.Constant(o.value), setOf())
                is TACSymbol.Var -> state[o] ?: SimpleQualifiedInt.nondet
            }
    }

    val expressionInterpreter = IntervalExpressionInterpreter()


    override val statementInterpreter: IStatementInterpreter<State, State> =
        object : AbstractStatementInterpreter<State, State>() {
            override fun forget(lhs: TACSymbol.Var, toStep: State, input: State, whole: State, l: LTACCmd) =
                toStep - lhs

            override fun stepCommand(l: LTACCmd, toStep: State, input: State, whole: State): State {
                return super.stepCommand(l, toStep, input, whole).let { s ->
                    when (val cmd = l.cmd) {
                        is TACCmd.Simple.AssumeCmd -> {
                            if (cmd.cond !is TACSymbol.Var) {
                                return s
                            }
                            pathSemantics.propagateTrue(cmd.cond, s, whole, l) ?:
                                throw UnreachableState("Unreachable after assuming condition at $l", l.ptr)
                        }

                        else -> {
                            s
                        }
                    }
                }
            }

            override fun stepExpression(
                lhs: TACSymbol.Var,
                rhs: TACExpr,
                toStep: State,
                input: State,
                whole: State,
                l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
            ) = expressionInterpreter.stepExpression(lhs, rhs, toStep, input, whole, l)
        }

    private val qualifierPropagator = IntervalQualifierPropagationComputation()
        .withModularUpperBounds(
            extractModularUpperBound = { it as? SimpleIntQualifier.ModularUpperBound },
            extractMultipleOf = { (it as? SimpleIntQualifier.MultipleOf)?.factor },
            modularUpperBound = SimpleIntQualifier::ModularUpperBound
        )

    override val pathSemantics = object : BoundedQIntPropagationSemantics<SimpleQualifiedInt, SimpleIntQualifier, State, Any>(qualifierPropagator) {
        override fun assignVar(toStep: State, lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: LTACCmd) =
            qualifierManager.assign(toStep, lhs, toWrite, where)

        override fun propagateSummary(summary: TACSummary, s: State, w: Any, l: LTACCmd) = s

        override fun applyPath(
            k: TACSymbol.Var,
            curr: SimpleQualifiedInt,
            lb: BigInteger?,
            ub: BigInteger?,
            quals: Iterable<SimpleIntQualifier>,
            st: State,
            l: LTACCmd
        ): State {
            val st_ = super.applyPath(k, curr, lb, ub, quals, st, l)
            var stateIter = st_
            for(q in curr.qual) {
                if(q !is SimpleIntQualifier.MustEqual) {
                    continue
                }
                val saturated = interp(q.other, st_)
                val updated = saturated.withBoundAndQualifiers(
                    lb = lb?.max(saturated.x.lb) ?: saturated.x.lb,
                    ub = ub?.min(saturated.x.ub) ?: saturated.x.ub,
                    quals = saturated.qual + quals
                )
                stateIter = assignVar(stateIter, q.other, updated, l)
            }
            return stateIter
        }
    }

    override fun project(l: LTACCmd, w: State): State = w

    override fun postStep(stepped: State, l: LTACCmd): State = stepped

    override fun killLHS(
        lhs: TACSymbol.Var,
        s: State,
        w: State,
        narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>
    ): State {
        return qualifierManager.killLHS(lhs = lhs, lhsVal = s[lhs], narrow = narrow, s = s)
    }
}
