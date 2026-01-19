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

@file:UseSerializers(BigIntegerDecimalSerializer::class)

package sbf.dwarf

import bridge.BigIntegerDecimalSerializer
import kotlinx.serialization.UseSerializers

import datastructures.stdcollections.*
import dwarf.*
import solver.CounterexampleModel
import utils.*
import vc.data.TACSymbol
import vc.data.TransformableVarEntityWithSupport
import vc.data.tacexprutil.*
import java.math.BigInteger


sealed interface EvaluationResult {
    data class Concrete(val value: BigInteger) : EvaluationResult

    data class Unknown(val reason: String) : EvaluationResult
}

/**
 * An expression that is generated in [DWARFEdgeLabelToTACTranslator] from traversing the list of
 * [DWARFOperation] that are contained in the DWARF debug information.
 *
 * The [DWARFExpresion] can be partially (i.e. containing TAC Variables), and can only be fully evaluated after
 * the model has beene valuated.
 *
 * It's a full expression, as the debug information may contain information such as
 * (SignedConstant(1) AND ModelValue(x)). This expression can only be evaluated when the model value for x is available.
 */
@KSerializable
sealed interface DWARFExpression : TransformableVarEntityWithSupport<DWARFExpression>, HasKSerializable {
    fun evaluate(model: CounterexampleModel): EvaluationResult

    @KSerializable
    data class ModelValue(val symbol: TACSymbol) : DWARFExpression, BaseExpr() {
        override fun evaluate(model: CounterexampleModel): EvaluationResult {
            if (symbol.isConst) {
                return EvaluationResult.Concrete(symbol.asConstOrNull!!)
            }
            val modelVal = model.tacAssignments[symbol]
            if (modelVal?.asBigIntOrNull() != null) {
                return EvaluationResult.Concrete(modelVal.asBigIntOrNull()!!)
            }
            if (model.havocedVariables.contains(symbol)) {
                return EvaluationResult.Unknown("Variable got havoc'ed")
            }
            return EvaluationResult.Unknown("No value in model for $symbol")
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = symbol.asVarOrNull?.let { this.copy(symbol = f(it)) }
            ?: this

        override val support: Set<TACSymbol.Var>
            get() = setOfNotNull(symbol.asVarOrNull)
    }

    @KSerializable
    data class Constant(val value: BigInteger) : BaseExpr() {
        override fun evaluate(model: CounterexampleModel): EvaluationResult = EvaluationResult.Concrete(value)
    }

    @KSerializable
    data class StringValue(val value: String) : BaseExpr() {
        override fun evaluate(model: CounterexampleModel): EvaluationResult = EvaluationResult.Unknown(value)
    }

    @KSerializable
    abstract class BaseExpr : DWARFExpression {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this
        override val support: Set<TACSymbol.Var>
            get() = setOf()
    }

    @KSerializable
    data class BinaryExpr(val operand: DWARFOperation, val left: DWARFExpression, val right: DWARFExpression) : DWARFExpression {
        init {
            check(operand is DWARFOperation.And || operand is DWARFOperation.Minus || operand is DWARFOperation.Plus || operand is DWARFOperation.Or)
        }

        override fun evaluate(model: CounterexampleModel): EvaluationResult {
            fun combiner(op: (BigInteger, BigInteger) -> BigInteger): EvaluationResult {
                return left.evaluate(model).let { l ->
                    when (l) {
                        is EvaluationResult.Unknown -> l
                        is EvaluationResult.Concrete -> {
                            right.evaluate(model).let { r ->
                                when (r) {
                                    is EvaluationResult.Unknown -> r
                                    is EvaluationResult.Concrete -> EvaluationResult.Concrete(op(l.value, r.value))
                                }
                            }
                        }
                    }
                }
            }
            return when (operand) {
                DWARFOperation.And -> combiner { l, r -> l.and(r) }
                DWARFOperation.Minus -> combiner { l, r -> l - r }
                DWARFOperation.Or -> combiner { l, r -> l.or(r) }
                DWARFOperation.Plus -> combiner { l, r -> l + r }
                else -> `impossible!`
            }
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this.copy(left = left.transformSymbols(f), right = right.transformSymbols(f))

        override val support: Set<TACSymbol.Var>
            get() = this.left.support + this.right.support
    }
}