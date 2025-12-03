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

import analysis.ip.SafeCastingAnnotator.CastingKey
import datastructures.nonEmptyListOf
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import evm.twoToThe
import log.*
import report.RuleAlertReport
import rules.*
import spec.cvlast.SpecType
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.SafeCastingGenerator
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.rules.SingleRuleGenerationMeta
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.tempVar
import java.util.stream.Collectors


/**
 * Replaces casts that appear in solidity code with asserts that these casts are always in bounds. Does this for each
 * external function, for each cast separately.
 *
 * An example is: `uint256(x)` where `x` is an int256. In such a case, the assertion would be `x <= 2^255 - 1`.
 */
object SafeCastingChecker : BuiltInRuleCustomChecker<SafeCastingGenerator>() {
    override val eId: BuiltInRuleId = BuiltInRuleId.safeCasting

    private val txf = TACExprFactUntyped

    /**
     * Returns pairs of programs, one for each cast:
     *  1. The cast being replaced by an assert that checks if the cast is in range.
     *  2. The cast being replaced by an `assert false`
     * Each such pair is accompanied with the source information for the original assert.
     */
    private fun replaceCastsWithAsserts(originalCode: CoreTACProgram): List<Pair<SimplePair<CoreTACProgram>, Range.Range?>> {
        val casts = originalCode.parallelLtacStream()
            .mapNotNull { it.ptr `to?` it.cmd.maybeAnnotation(CastingKey) }
            .collect(Collectors.toList())
        return casts.mapNotNull { (ptr, info) ->
            val (fromType, toType, sym, range) = info
            // if we go from a type to a wider one, no chance this will fail.
            // the second case is when we cast an unsigned narrow int to a wider signed int.
            if ((toType.isSigned == fromType.isSigned && fromType.width <= toType.width) ||
                (!fromType.isSigned && toType.isSigned && fromType.width < toType.width)
            ) {
                return@mapNotNull null
            }
            val exp = txf {
                val w = toType.width
                when (fromType.isSigned to toType.isSigned) {
                    true to true -> LOr(
                        Le(sym.asSym(), (twoToThe(w - 1) - 1).asTACExpr),
                        Ge(sym.asSym(), (EVM_MOD_GROUP256 - twoToThe(w - 1)).asTACExpr)
                    )

                    false to false -> Le(sym.asSym(), (twoToThe(w) - 1).asTACExpr)
                    true to false -> Le(sym.asSym(), (twoToThe(w - 1) - 1).asTACExpr)
                    false to true -> Le(sym.asSym(), (twoToThe(w - 1) - 1).asTACExpr)
                    else -> `impossible!`
                }
            }
            val patcher = originalCode.toPatchingProgram()
            val sanityPatcher = originalCode.toPatchingProgram()

            val t = tempVar("safeCast", Tag.Bool)
            patcher.addVarDecl(t)
            patcher.replace(ptr) { _ ->
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(t, exp),
                    TACCmd.Simple.AssertCmd(t, "safe casting from $fromType to $toType at $range")
                )
            }
            sanityPatcher.update(
                ptr,
                TACCmd.Simple.AssertCmd(TACSymbol.False, "safe casting sanity from $fromType to $toType at $range")
            )
            val code = patcher.toCode(originalCode)
            val sanityCode = sanityPatcher.toCode(originalCode)
            (code to sanityCode) to range
        }
    }

    override suspend fun doCheck(
        ruleChecker: RuleChecker,
        rule: IRule,
    ): RuleCheckResult {
        check(rule is CVLSingleRule)

        return CompiledRule.staticRules(ruleChecker.scene, ruleChecker.cvl, rule).mapCatching { codesToCheck ->
            val checkableTACs = codesToCheck.flatMap { (currCode, currMethodInst, singleRule) ->
                val methodName = currMethodInst.values.singleOrNull()?.toExternalABIName()
                    ?: error("Expected the compiled builtin rule ${rule.declarationId} to contain one method parameter, but got $currMethodInst")

                replaceCastsWithAsserts(currCode).mapIndexed { i, (codes, range) ->
                    val (code, sanityCode) = codes
                    val newRule = singleRule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                            SingleRuleGenerationMeta.Sanity.PRE_SANITY_CHECK,
                            currMethodInst.range(),
                            methodName,
                        ),
                        range = range ?: singleRule.range,
                        ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier("${methodName}-${i + 1}")
                    )
                    val sanityRule = newRule.copy(
                        ruleGenerationMeta = SingleRuleGenerationMeta.WithMethodInstantiations(
                            SingleRuleGenerationMeta.Sanity.BASIC_SANITY,
                            currMethodInst.range(),
                            methodName,
                        ),
                        ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(newRule),
                        ruleIdentifier = newRule.ruleIdentifier.freshDerivedIdentifier("-sanity")
                    )
                    CheckableTACWithSanity(
                        code, currMethodInst, newRule,
                        nonEmptyListOf(CheckableTAC(sanityCode, currMethodInst, sanityRule))
                    )
                }
            }
            if (checkableTACs.isEmpty()) {
                RuleCheckResult.Skipped(rule, RuleAlertReport.Info("No casts found"))
            } else {
                val result = ruleChecker.compiledSingleRuleCheck(rule, checkableTACs)
                val reducedResults = (result as? RuleCheckResult.Multi)?.results ?: emptyList()
                RuleCheckResult.Multi(rule, reducedResults, RuleCheckResult.MultiResultType.PARAMETRIC)
            }
        }.getOrElse { e ->
            Logger.always(
                "Failed to compile the builtin rule ${rule.declarationId} due to $e",
                respectQuiet = false
            )
            RuleCheckResult.Error(
                rule,
                CertoraException.fromExceptionWithRuleName(e, rule.declarationId),
            )
        }
    }
}

