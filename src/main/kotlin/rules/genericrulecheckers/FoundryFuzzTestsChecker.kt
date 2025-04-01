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

import log.*
import rules.RuleCheckResult
import rules.RuleChecker
import spec.rules.CVLSingleRule
import spec.rules.IRule
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.FoundryFuzzTestsGenerator

private val logger = Logger(LoggerTypes.GENERIC_RULE)

class FoundryFuzzTestsChecker(override val eId: BuiltInRuleId) : BuiltInRuleCustomChecker<FoundryFuzzTestsGenerator>() {
    override suspend fun doCheck(ruleChecker: RuleChecker, rule: IRule): RuleCheckResult {
        check(rule is CVLSingleRule)
        logger.info { "Checking the CVLSingleRule ${rule.declarationId}" }
        return ruleChecker.singleRuleCheck(rule)
    }
}
