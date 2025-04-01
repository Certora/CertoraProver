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

package rules.sanity

import config.Config
import rules.dpgraph.DPResult
import rules.RuleCheckResult
import rules.sanity.sorts.SanityCheckSort
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.cvlast.SpecType
import utils.uncheckedAs

typealias DPSuccess<T> = DPResult.Success<RuleCheckResult, T>
typealias DPError = DPResult.Error<RuleCheckResult, RuleCheckResult.Error>
typealias SanityDPResult = DPResult<RuleCheckResult, RuleCheckResult, RuleCheckResult.Error>

fun matchingSanityRule(baseRule: CVLSingleRule, sanityRule: CVLSingleRule) : Boolean =
    sanityRule.ruleType.getOriginatingRule() == baseRule


/**
 * The list of all sanity checks' sorts that are enabled by the current
 * configuration of CVT.
 */
fun enabledSanityChecksSorts(results: List<RuleCheckResult>): List<SanityCheckSort<*, *>> =
    results.mapNotNull { result ->
        if (result.rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule) {
            val sort = SanityCheckSort(result.rule.ruleType as SpecType.Single.GeneratedFromBasicRule.SanityRule)
            if (sort.mode <= Config.DoSanityChecksForRules.get()) {
                sort
            } else {
                throw IllegalStateException("sanity check of type [${result.rule.ruleType}] should not be performed" +
                    "when running sanity mode [${Config.DoSanityChecksForRules.get()}]")
            }
        } else {
            null
        }
    }


@JvmInline
value class CVLRuleTypeView<out T : SpecType>(val rule: IRule) {

    val ruleType: T
        get() = rule.ruleType.uncheckedAs()

}

inline fun <reified T : SpecType> IRule.narrowType(): CVLRuleTypeView<T> {
    require(ruleType is T) { "Expected the type of the given rule (${declarationId}) to be ${T::class.java.name} " +
        "(got $ruleType)" }
    return CVLRuleTypeView(this)
}

