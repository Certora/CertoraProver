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

import CompiledGenericRule
import analysis.LTACCmdView
import analysis.maybeNarrow
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import datastructures.stdcollections.*
import event.RuleEvent
import spec.cvlast.RuleIdentifier
import utils.Range
import spec.cvlast.SpecType
import vc.data.find
import sbf.SolanaConfig

object TACMultiAssert {

    /**
     * for all asserts with [TACMeta.ASSERT_ID]: a map from the id to the matching assert.
     * assumes the ids within the same rule are unique (thus one assert per id)
     **/
    private fun assertIdToAssertPtr(code: CoreTACProgram): Map<Int, LTACCmdView<TACCmd.Simple.AssertCmd>> {
        return code
            .parallelLtacStream()
            .mapNotNull {
                val assertPtr = it.maybeNarrow<TACCmd.Simple.AssertCmd>()
                val id = assertPtr?.cmd?.meta?.find(TACMeta.ASSERT_ID)

                if (id != null) {
                    Pair(id, assertPtr)
                } else {
                    null
                }
            }
            .toMap()
    }

    /** Replace in [baseRuleTac] all assertion commands with assume commands except the one with ASSERT_ID=[assertId] **/
    private fun replaceAssertWithAssumeExcept(
        baseRuleTac: CoreTACProgram,
        assertId: Int,
        idToPtr: Map<Int, LTACCmdView<TACCmd.Simple.AssertCmd>>,
    ): CoreTACProgram {
        return baseRuleTac.patching { p ->
            val otherAsserts = idToPtr
                .filterKeys { id -> assertId != id }
                .values

            for ((ptr, cmd) in otherAsserts) {
                val assume = TACCmd.Simple.AssumeCmd(cmd.o, "replaced assert: ${cmd.msg}", cmd.meta)
                p.update(ptr, assume)
            }
        }
    }

    private fun RuleIdentifier.multiAssertIdentifier(assertId: Int): RuleIdentifier {
        val suffix = "#assert_${assertId - RESERVED_NUM_OF_ASSERTS}"
        return this.freshDerivedIdentifier(suffix)
    }

    fun canSplit(baseRule: CompiledGenericRule) =
            !baseRule.rule.isSatisfyRule &&
            baseRule.rule.ruleType !is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck

    fun transformSingle(compiledRule: CompiledGenericRule.Compiled): CompiledGenericRule.Compiled {
        val singleAssert = compiledRule.rule.copy(
            ruleIdentifier = compiledRule.rule.ruleIdentifier.freshDerivedIdentifier(RuleEvent.ASSERTS_NODE_TITLE),
            ruleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertsOnly(compiledRule.rule),
        )
        return compiledRule.copy(rule = singleAssert)
    }

    /**
     * Parses the assert filter from SolanaConfig.AssertFilter.
     * Returns null if no filter is specified, otherwise returns a set of 1-based indices.
     */
    private fun parseAssertFilter(): Set<Int>? {
        val filterStrings = SolanaConfig.AssertFilter.getOrNull() ?: return null
        return filterStrings.mapNotNull { it.toIntOrNull() }.toSet().ifEmpty { null }
    }

    /**
     * For a given rule [compiledRule] with N assert commands, it returns N new rules where each rule has
     * exactly one assert.
     * If [SolanaConfig.AssertFilter] is set, only asserts with matching 1-based indices are included.
     **/
    fun transformMulti(compiledRule: CompiledGenericRule.Compiled): List<CompiledGenericRule.Compiled> {
        val idToPtr = assertIdToAssertPtr(compiledRule.code)
        val assertFilter = parseAssertFilter()

        return idToPtr.mapNotNull { (assertId, assertPtr) ->
            val userFacingIndex = assertId - RESERVED_NUM_OF_ASSERTS

            // Apply filter if specified
            if (assertFilter != null && userFacingIndex !in assertFilter) {
                return@mapNotNull null
            }

            val newRuleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertSpecFile(
                compiledRule.rule,
                assertId,
                assertPtr.cmd.msg,
                assertPtr.cmd.meta[TACMeta.CVL_RANGE] ?: Range.Empty(),
            )

            val newIdentifier = compiledRule.rule.ruleIdentifier.multiAssertIdentifier(assertId)

            val newRule = compiledRule.rule.copy(
                ruleIdentifier = newIdentifier,
                ruleType = newRuleType,
                range = newRuleType.cvlCmdLoc,
            )

            val newBaseRuleTac = replaceAssertWithAssumeExcept(
                compiledRule.code,
                assertId,
                idToPtr
            ).copy(name = newIdentifier.toString())

            compiledRule.copy(code = newBaseRuleTac, rule = newRule)
        }
    }
}