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

package sbf.tac

import analysis.maybeNarrow
import sbf.sanitySuffix
import spec.cvlast.CVLSingleRule
import spec.cvlast.RuleIdentifier
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import datastructures.stdcollections.*

object TACMultiAssert {

    /** Return all user assertions from [code] **/
    private fun getAssertions(code: CoreTACProgram): Set<Int> {
        val assertions = mutableSetOf<Int>()
        code.parallelLtacStream().forEach {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()?.let {
                val assertId = it.cmd.meta[TACMeta.ASSERT_ID]
                if (assertId != null) {
                    // We only care about asserts that were added by the user
                    assertions.add(assertId)
                }
            }
        }
        return assertions
    }

    /** Replace in [baseRuleTac] all assertion commands with assume commands except the one with ASSERT_ID=[assertId] **/
    private fun replaceAssertWithAssumeExcept(baseRuleTac: CoreTACProgram, assertId: Int): CoreTACProgram {
        return baseRuleTac.patching { p ->
            this.parallelLtacStream()
                .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
                .filter {
                    val curAssertId = it.cmd.meta[TACMeta.ASSERT_ID]
                    curAssertId != null && curAssertId != assertId
                }.forEach {
                    p.replace(it.ptr) { cmd ->
                        val assertCmd = cmd as? TACCmd.Simple.AssertCmd
                        if (assertCmd != null) {
                            listOf(TACCmd.Simple.AssumeCmd(assertCmd.o, assertCmd.meta))
                        } else {
                            listOf()
                        }
                    }
                }
        }
    }

    @Suppress("ForbiddenMethodCall")
    fun shouldExecute(baseRule: CVLSingleRule) =
            !baseRule.isSatisfyRule &&
            !baseRule.isSanityCheck() &&
            !baseRule.ruleIdentifier.displayName.endsWith(sanitySuffix)

    /**
     * For a given rule [baseRuleTac] with N assert commands, it returns N new rules where each rule has
     * exactly one assert.
     **/
    fun transformTac(baseRuleTac: CoreTACProgram, baseRule: CVLSingleRule): List<Pair<CoreTACProgram, CVLSingleRule>> {
        val assertions = getAssertions(baseRuleTac)
        return if (assertions.size <= 1) {
            listOf(baseRuleTac to baseRule)
        } else {
            assertions.map { assertId ->
                val suffix = "#assert_$assertId"
                val newBaseRuleTac = replaceAssertWithAssumeExcept(baseRuleTac, assertId)
                val newRuleId = RuleIdentifier.freshIdentifier(baseRule.ruleIdentifier.displayName + suffix)
                val newBaseRule = baseRule.copy(ruleIdentifier = newRuleId)
                newBaseRuleTac.copy(name = baseRuleTac.name + suffix) to newBaseRule
            }
        }
    }
}
