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

package analysis.storage

import analysis.*
import analysis.numeric.*
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import datastructures.ProjectedMap
import evm.EVM_ADDRESS_SIZE
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.foldFirstOrNull
import utils.mapNotNullToSet
import vc.data.*
import java.math.BigInteger

class SimpleQualifiedIntPathSemantics<T: Any>(
    propagator: QualifierPropagationComputation<SimpleQualifiedInt, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, Any, SimpleIntQualifier>,
    private val manager: QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>>,
): BoundedQIntPropagationSemantics<SimpleQualifiedInt, SimpleIntQualifier, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, Any>(propagator) {

    override fun assignVar(
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: LTACCmd
    ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return manager.assign(toStep, lhs, toWrite, where)
    }

    override fun propagateSummary(
            summary: TACSummary,
            s: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            w: Any,
            l: LTACCmd
    ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return s
    }
}

class SimpleQualifiedIntExpressionInterpreter<T: Any>(val g: TACCommandGraph, val qualifierManager: QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>>) :
        NonRelationalExpressionInterpreter<ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, SimpleQualifiedInt, Any>() {

    override val nondet = SimpleQualifiedInt.nondet
    override val valueSemantics: IValueSemantics<ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, SimpleQualifiedInt, Any> =
            object : StatelessUIntValueSemantics<SimpleQualifiedInt, IntValue>() {
                override fun lift(lb: BigInteger, ub: BigInteger): IntValue {
                    return IntValue.Interval(lb, ub)
                }

                override fun lift(iVal: IntValue): SimpleQualifiedInt {
                    return SimpleQualifiedInt(iVal, setOf())
                }

                override fun evalDiv(v1: SimpleQualifiedInt, o1: TACSymbol, v2: SimpleQualifiedInt, o2: TACSymbol, toStep: Any, input: Any, whole: Any, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): SimpleQualifiedInt {
                    val k = (o2 as? TACSymbol.Const)?.value ?: return nondet
                    if (k == BigInteger.ZERO) {
                        return SimpleQualifiedInt(IntValue.Constant(BigInteger.ZERO), setOf())
                    }
                    return SimpleQualifiedInt(
                        IntValue(v1.x.lb/k, v1.x.ub/k),
                        v1.qual.mapNotNullToSet {
                            when(it) {
                                is SimpleIntQualifier.MultipleOf -> {
                                    val (q, rem) = it.factor.divideAndRemainder(k)
                                    if (rem == BigInteger.ZERO) {
                                        SimpleIntQualifier.MultipleOf(q)
                                    } else {
                                        null
                                    }
                                }
                                else -> null
                            }
                        }
                    )
                }

                override val nondet = SimpleQualifiedInt.nondet
            }.withPathConditions(
                    condition = SimpleIntQualifier::Condition,
                    conjunction = SimpleIntQualifier::LogicalConnective,
                    flip = {
                        when (it) {
                            is SimpleIntQualifier.Condition ->
                                ConditionQualifier.flip(it, SimpleIntQualifier::Condition)

                            is SimpleIntQualifier.LogicalConnective ->
                                LogicalConnectiveQualifier.flip(it, SimpleIntQualifier::LogicalConnective)

                            is SimpleIntQualifier.MultipleOf ->
                                null

                            is SimpleIntQualifier.ModularUpperBound ->
                                null
                        }
                    }
            ).withBasicMath(
                SimpleIntQualifier::MultipleOf, { _, _ -> null }
            ).withModularUpperBounds(
                modularUpperBound = SimpleIntQualifier::ModularUpperBound,
                extractModularUpperBound = { it as? SimpleIntQualifier.ModularUpperBound }
            )


    override fun liftConstant(value: BigInteger): SimpleQualifiedInt {
        return SimpleQualifiedInt(IntValue.Interval(value,value), setOf())
    }

    fun interp(s: TACSymbol, state: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, where: CmdPointer): SimpleQualifiedInt {
        return when(s) {
            is TACSymbol.Const -> SimpleQualifiedInt(IntValue.Constant(s.value), setOf())
            is TACSymbol.Var -> {
                // Strengthen the interval by taking
                // the intersection of the intervals all must-equal variables.
                // We can't just do this once per variable (say, when it is assigned)
                // because contextual information may refine its interval at a later use site.
                val idx = state[s] ?: nondet
                return if (Config.EnabledEqualitySaturation.get()) {
                    g.cache.gvn.equivBefore(where, s).mapNotNullToSet {
                        state[it]?.x
                    }.foldFirstOrNull { x, y ->
                        x.updateBounds(y.lb, y.ub)
                    }?.let {
                        idx.copy(x = it)
                    } ?: idx
                } else {
                    idx
                }
            }
        }
    }

    override fun interp(
            o1: TACSymbol,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: Any,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): SimpleQualifiedInt {
        return interp(o1, toStep, l.ptr)
    }

    override fun assign(toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, lhs: TACSymbol.Var, newValue: SimpleQualifiedInt, input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, whole: Any, wrapped: LTACCmd): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return qualifierManager.assign(toStep, lhs, newValue, wrapped)
    }
}

class SimpleQualifiedIntAbstractInterpreterState<T: Any>(val state: TreapMap<TACSymbol.Var, T>, val boundedRead: StorageAnalysis.StorageAnalysisObjectReference<T>)

class SimpleQualifiedIntAbstractInterpreter<T: Any> private constructor (
    private val qualifierManager: QualifierManager<SimpleIntQualifier, SimpleQualifiedInt, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>>,
    val exprInterp: SimpleQualifiedIntExpressionInterpreter<T>,
    private val narrowIdx: (T) -> SimpleQualifiedInt?,
    private val mergeIdx: (T?, SimpleQualifiedInt?) -> T?
): AbstractAbstractInterpreter<SimpleQualifiedIntAbstractInterpreterState<T>, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>>() {

    companion object {
        operator fun <T: Any> invoke(
            graph: TACCommandGraph,
            narrowIdx: (T) -> SimpleQualifiedInt?,
            mergeIdx: (T?, SimpleQualifiedInt?) -> T?,
        ): SimpleQualifiedIntAbstractInterpreter<T> {
            val qualifierManager = object : QualifierManager<
                SimpleIntQualifier,
                SimpleQualifiedInt,
                ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>
                >(me = graph.cache.gvn) {
                override fun mapValues(s: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, mapper: (TACSymbol.Var, SimpleQualifiedInt) -> SimpleQualifiedInt): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
                    return s.mapValues { mapper(it.key, it.value) }
                }

                override fun assignVar(toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, lhs: TACSymbol.Var, toWrite: SimpleQualifiedInt, where: LTACCmd): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
                    if(toWrite == SimpleQualifiedInt.nondet) {
                        return toStep - lhs
                    }
                    return toStep + (lhs to toWrite)
                }
            }

            return SimpleQualifiedIntAbstractInterpreter(qualifierManager, SimpleQualifiedIntExpressionInterpreter(graph, qualifierManager), narrowIdx, mergeIdx)
        }
    }

    private val qualifierPropagator =
        object : QualifierPropagationComputation<SimpleQualifiedInt, ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, Any, SimpleIntQualifier>() {
            override fun extractValue(op1: TACSymbol.Var, s: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, w: Any, l: LTACCmd): SimpleQualifiedInt {
                return s[op1] ?: SimpleQualifiedInt.nondet
            }
        }.withModularUpperBounds(
            extractModularUpperBound = { it as? SimpleIntQualifier.ModularUpperBound },
            extractMultipleOf = { (it as? SimpleIntQualifier.MultipleOf)?.factor },
            modularUpperBound = SimpleIntQualifier::ModularUpperBound
        )

    override val pathSemantics = SimpleQualifiedIntPathSemantics(qualifierPropagator, qualifierManager)

    override val statementInterpreter = object : AbstractStatementInterpreter<ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, SimpleQualifiedIntAbstractInterpreterState<T>>() {
        override fun forget(
            lhs: TACSymbol.Var,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: SimpleQualifiedIntAbstractInterpreterState<T>,
            l: LTACCmd
        ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
            return (toStep - lhs)
        }

        // Add type-derived bounds to scalar return values
        private fun stepInternalCall(
            call: InternalCallSummary,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: SimpleQualifiedIntAbstractInterpreterState<T>,
            l: LTACCmd,
        ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
            val resTypes = call.signature.resType
            val rets = call.internalExits
            if (resTypes.size != rets.size) {
                return rets.fold(toStep) { st, x -> forget(x.s, st, st, whole, l)}
            }

            return rets.zip(resTypes)
                .fold(toStep) { st, (ret, ty) ->
                    when (ty) {
                        is EVMTypeDescriptor.EVMNumericType -> {
                            val range = IntValue(ty.minValue, ty.maxValue)
                            st + (ret.s to SimpleQualifiedInt(range))
                        }

                        is EVMTypeDescriptor.bool -> {
                            val range = IntValue(BigInteger.ZERO, BigInteger.ONE)
                            st + (ret.s to SimpleQualifiedInt(range))
                        }

                        is EVMTypeDescriptor.address -> {
                            val range = IntValue(
                                BigInteger.ZERO,
                                BigInteger.TWO.pow(EVM_ADDRESS_SIZE) - BigInteger.ONE
                            )
                            st + (ret.s to SimpleQualifiedInt(range))
                        }

                        else -> this.forget(ret.s, st, st, whole, l)
                    }
                }
        }

        override fun stepCommand(
            l: LTACCmd,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: SimpleQualifiedIntAbstractInterpreterState<T>
        ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
            return super.stepCommand(l, toStep, input, whole).let { st ->
                l.snarrowOrNull<InternalCallSummary>()?.let { call ->
                    stepInternalCall(call, toStep, whole, l)
                } ?: st
            }
        }

        override fun stepExpression(
            lhs: TACSymbol.Var,
            rhs: TACExpr,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: SimpleQualifiedIntAbstractInterpreterState<T>,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
            return exprInterp.stepExpression(lhs, rhs, toStep, input, whole, l)
        }

        override fun stepReadStorage(
            lhs: TACSymbol.Var,
            loc: TACSymbol,
            base: TACSymbol.Var,
            toStep: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            input: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>,
            whole: SimpleQualifiedIntAbstractInterpreterState<T>,
            l: LTACCmd
        ) : ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
            return whole.boundedRead.value?.let {
                narrowIdx(it)
            }?.let {
                toStep + (lhs to it)
            } ?: this.forget(lhs, toStep, input, whole, l)
        }
    }

    override fun postStep(stepped: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, l: LTACCmd): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return stepped
    }

    override fun killLHS(lhs: TACSymbol.Var, s: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, w: SimpleQualifiedIntAbstractInterpreterState<T>, narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return s[lhs]?.let { idx ->
            qualifierManager.killLHS(lhs = lhs, lhsVal = idx, narrow = narrow, s = s)
        } ?: s
    }

    override fun project(l: LTACCmd, w: SimpleQualifiedIntAbstractInterpreterState<T>): ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt> {
        return ProjectedMap(
                wrapped = w.state,
                narrow = narrowIdx,
                merge = mergeIdx,
        )
    }

    fun interp(s: TACSymbol, state: ProjectedMap<TACSymbol.Var, T, SimpleQualifiedInt>, where: CmdPointer): SimpleQualifiedInt {
        return exprInterp.interp(s, state, where)
    }
}
