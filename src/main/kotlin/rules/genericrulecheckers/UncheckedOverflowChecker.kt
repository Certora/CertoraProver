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

package rules.genericrulecheckers

import analysis.ip.UncheckedOverflowAnnotator.OperatorType
import analysis.ip.UncheckedOverflowAnnotator.UncheckedOverflowKey
import evm.twoToThe
import rules.RuleChecker
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.UncheckedOverflowGenerator
import spec.rules.CVLSingleRule
import spec.rules.IRule
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldPlusOneCmd
import java.math.BigInteger
import java.util.stream.Collectors


/**
 * Replaces possibly overflowing operators that appear in solidity unchecked code, with asserts that these
 * operators don't overflow. Does this for each external function, for each such operation separately.
 *
 * For example, `unchecked { x + y }` where `x` and `y` are `uint`. In such a case, the assertion is `z <= 2^256 - 1`,
 * where `z` is the result of the addition.
 */
object UncheckedOverflowChecker : BuiltInRuleCustomChecker<UncheckedOverflowGenerator>() {
    override val eId: BuiltInRuleId = BuiltInRuleId.uncheckedOverflows

    private val txf = TACExprFactUntyped

    /**
     * Takes [originalCode] and for each unchecked overflow operation it detects, creates a pair of tac programs:
     *  1. In the first, an assertion is added, checking the operation does not overflow
     *  2. The second serves for a sanity check. The operation is replaced with an `assert false` statement.
     * It also returns the [Range.Range] of the operation if it is available.
     */
    private fun replaceUncheckedOverflowsWithAsserts(originalCode: CoreTACProgram):
        List<Pair<SimplePair<CoreTACProgram>, Range.Range?>> {
        val ops = originalCode.parallelLtacStream()
            .mapNotNull { it.ptr `to?` it.cmd.maybeAnnotation(UncheckedOverflowKey) }
            .collect(Collectors.toList())
        return ops.mapNotNull { (ptr, info) ->
            val (resType, arg1, arg2, opType, range) = info
            val (lower, upper) = if (resType.isSigned) {
                Pair(
                    -twoToThe(resType.width - 1),
                    twoToThe(resType.width - 1) - 1
                )
            } else {
                BigInteger.ZERO to (twoToThe(resType.width) - 1)
            }
            val change = unfoldPlusOneCmd(
                "uncheckedOverflow",
                txf {
                    val opRes = when (opType) {
                        OperatorType.Mul -> arg1 intMul arg2
                        OperatorType.Add -> arg1 intAdd arg2
                        OperatorType.Sub -> arg1 intSub arg2
                    }
                    LAnd(
                        Ge(opRes, lower.asTACExpr),
                        Le(opRes, upper.asTACExpr)
                    )
                }
            ) {
                TACCmd.Simple.AssertCmd(it.s, "unchecked $resType $opType overflows at $range")
            }
            val patcher = originalCode.toPatchingProgram()
            patcher.replaceCommand(ptr, change)
            val code = patcher.toCode(originalCode)

            val sanityPatcher = originalCode.toPatchingProgram()
            sanityPatcher.update(
                ptr,
                TACCmd.Simple.AssertCmd(TACSymbol.False, "sanity for unchecked $resType $opType overflow at $range")
            )
            val sanityCode = sanityPatcher.toCode(originalCode)

            (code to sanityCode) to range
        }
    }

    override suspend fun doCheck(ruleChecker: RuleChecker, rule: IRule) =
        checkDerivedRulesWithVacuity(ruleChecker, rule as CVLSingleRule, ::replaceUncheckedOverflowsWithAsserts)

}
