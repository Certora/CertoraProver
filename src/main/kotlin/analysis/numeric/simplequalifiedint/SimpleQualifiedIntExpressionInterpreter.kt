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
import analysis.LTACCmdView
import analysis.numeric.IntValue
import analysis.numeric.NonRelationalExpressionInterpreter
import analysis.numeric.SimpleIntQualifier
import analysis.numeric.withBasicMath
import analysis.numeric.withModularUpperBounds
import analysis.numeric.withPathConditions
import datastructures.stdcollections.setOf
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger

open class SimpleQualifiedIntExpressionInterpreter<W>: NonRelationalExpressionInterpreter<SimpleQualifiedIntState, SimpleQualifiedInt, W>() {
    override val nondet
        get() = SimpleQualifiedInt.nondet

    override val valueSemantics = SimpleQualifiedIntValueSemantics<SimpleQualifiedIntState, W>()
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

    override fun liftConstant(value: BigInteger) = SimpleQualifiedInt(IntValue.Companion.Constant(value), setOf())

    override fun interp(
        o1: TACSymbol,
        toStep: SimpleQualifiedIntState,
        input: SimpleQualifiedIntState,
        whole: W,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ) = toStep.interpret(o1)

    override fun assign(
        toStep: SimpleQualifiedIntState,
        lhs: TACSymbol.Var,
        newValue: SimpleQualifiedInt,
        input: SimpleQualifiedIntState,
        whole: W,
        wrapped: LTACCmd
    ) = toStep.copy(toStep.s.put(lhs, newValue))
}
