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

import analysis.LTACCmdView
import analysis.getNaturalLoops
import analysis.maybeNarrow
import config.Config
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import datastructures.stdcollections.*
import event.RuleEvent
import spec.cvlast.RuleIdentifier
import utils.Range
import spec.cvlast.SpecType
import spec.rules.EcosystemAgnosticRule
import vc.data.find
import rules.RuleSplitter.fixupAssertSnippetAnnotations
import verifier.EncodedRule
import sbf.SolanaConfig
import cli.AssertFilterEntry

object TACMultiAssert {
    private const val PROVER_ASSERTS_NODE_TITLE = "ProverInternalChecks"


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

    private data class EncodedSubRule(
        override val rule: EcosystemAgnosticRule,
        override val code: CoreTACProgram,
    ) : EncodedRule<EcosystemAgnosticRule>

    /**
     * Replace in [baseRuleTac] all assertion commands with assume commands except the one with ASSERT_ID=[assertId],
     * or all of them if [assertId] is null.
     **/
    private fun replaceAssertWithAssumeExcept(
        baseRuleTac: CoreTACProgram,
        assertId: Int?,
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

    fun canSplit(baseRule: EncodedRule<EcosystemAgnosticRule>) =
        !baseRule.rule.isSatisfyRule && !baseRule.rule.isGenerated

    fun transformSingle(compiledRule: EncodedRule<EcosystemAgnosticRule>): EncodedRule<EcosystemAgnosticRule> {
        val singleAssert = compiledRule.rule.copy(
            ruleIdentifier = compiledRule.rule.ruleIdentifier.freshDerivedIdentifier(RuleEvent.ASSERTS_NODE_TITLE),
            ruleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertsOnly(compiledRule.rule),
        )
        return EncodedSubRule(singleAssert, compiledRule.code)
    }

    /**
     * Whether the transformations that run on a subrule, i.e. after this split, may add asserts of their own to
     * [code]: the unwinding conditions that the loop unroller asserts, and the checks of [TACModSimplifier].
     */
    private fun mayAddProverAsserts(code: CoreTACProgram) =
        TACModSimplifier.mayAddAsserts ||
            (!Config.IsAssumeUnwindCondForLoops.get() && getNaturalLoops(code.analysisCache.graph).isNotEmpty())

    /**
     * Whether [code] already holds an assert that this split does not cover, which is one that the Prover added
     * after the front-end handed out the ids: the unwinding conditions of the loop unroller, when the two-stage mode
     * unrolls before the split rather than per subrule.  Those are assumed in every subrule as well, so they need
     * the subrule of [proverAssertsRule] just as much as the ones that are still to come.
     */
    private fun hasProverAssertsOutsideTheSplit(
        code: CoreTACProgram,
        idToPtr: Map<Int, LTACCmdView<TACCmd.Simple.AssertCmd>>,
    ) = code
        .parallelLtacStream()
        .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
        .anyMatch { it.cmd.meta[TACMeta.ASSERT_ID] !in idToPtr }

    /**
     * A subrule that checks the asserts that the Prover adds itself, i.e. the ones that are not marked with
     * [TACMeta.CVL_USER_DEFINED_ASSERT], with all of the asserts of the rule assumed.  Those asserts are added by
     * the transformations that run per-subrule, i.e. after this split, so this subrule is generated whenever they
     * may appear rather than because any of them can be found here.  In every other subrule they are assumed,
     * see [assumeProverAsserts].
     */
    private fun proverAssertsRule(
        compiledRule: EncodedRule<EcosystemAgnosticRule>,
        idToPtr: Map<Int, LTACCmdView<TACCmd.Simple.AssertCmd>>,
    ): EncodedRule<EcosystemAgnosticRule> {
        val identifier = compiledRule.rule.ruleIdentifier.freshDerivedIdentifier(PROVER_ASSERTS_NODE_TITLE)
        val rule = compiledRule.rule.copy(
            ruleIdentifier = identifier,
            ruleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AutoGenerated(compiledRule.rule),
        )
        val code = replaceAssertWithAssumeExcept(compiledRule.code, assertId = null, idToPtr)
            .copy(name = identifier.toString())
        return EncodedSubRule(rule, code)
    }

    /**
     * Demotes the asserts that the Prover added itself to assumptions, which is what they are as far as [rule] is
     * concerned: a subrule that was generated to check one specific assert of the original rule is not the place
     * where they are checked (that is the subrule of [proverAssertsRule]).
     *
     * The assert that [rule] was generated for is left alone even when the Prover is the one that inserted it, as it
     * is for an unresolved CPI call: it is part of the split, and this subrule is where it is checked.
     */
    fun assumeProverAsserts(code: CoreTACProgram, rule: EcosystemAgnosticRule): CoreTACProgram {
        val ruleType = rule.ruleType as? SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SpecFile
            ?: return code
        val proverAsserts = code
            .parallelLtacStream()
            .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
            .filter {
                TACMeta.CVL_USER_DEFINED_ASSERT !in it.cmd.meta &&
                    it.cmd.meta[TACMeta.ASSERT_ID] != ruleType.assertId
            }
            .toList()

        if (proverAsserts.isEmpty()) {
            return code
        }
        return code.patching { p ->
            for ((ptr, cmd) in proverAsserts) {
                p.update(ptr, TACCmd.Simple.AssumeCmd(cmd.o, "assumed prover assert: ${cmd.msg}", cmd.meta))
            }

            fixupAssertSnippetAnnotations(code, p, proverAsserts.map { it.cmd.o })
        }
    }

    /**
     * Gets the assert filter from SolanaConfig.AssertFilter.
     * Returns null if no filter is specified, otherwise returns a list of filter entries.
     * Supports both integer indices (1-based) and file locations (file:line format).
     */
    private fun getAssertFilter(): List<AssertFilterEntry>? {
        return SolanaConfig.AssertFilter.getOrNull()?.toList()
    }

    /**
     * Checks if a file location filter matches the given Range.
     * Compares file name (ignoring path) and line number.
     * Column information in the Range is ignored.
     */
    private fun AssertFilterEntry.FileLocation.matches(range: Range): Boolean {
        if (range is Range.Empty) {
            return false
        }
        val rangeData = range as Range.Range
        val rangeFileName = rangeData.specFile.substringAfterLast('/')
        val filterFileName = file.substringAfterLast('/')
        return filterFileName == rangeFileName && line == rangeData.start.lineForIDE
    }

    /**
     * For a given rule [compiledRule] with N assert commands, it returns N new rules where each rule has
     * exactly one assert, plus the [proverAssertsRule] where the Prover's own asserts are checked.
     * If [SolanaConfig.AssertFilter] is set, only asserts matching the filter are included.
     * The filter supports both 1-based indices and file:line locations.
     **/
    fun transformMulti(compiledRule: EncodedRule<EcosystemAgnosticRule>): List<EncodedRule<EcosystemAgnosticRule>> {
        val idToPtr = assertIdToAssertPtr(compiledRule.code)
        if (idToPtr.isEmpty()) {
            return listOf(transformSingle(compiledRule))
        }
        val assertFilter = getAssertFilter()

        // Separate filter entries by type for efficient matching
        val indexFilters = assertFilter?.filterIsInstance<AssertFilterEntry.Index>()?.map { it.value }?.toSet()
        val locationFilters = assertFilter?.filterIsInstance<AssertFilterEntry.FileLocation>()

        val assertSubRules = idToPtr.mapNotNull { (assertId, assertPtr) ->
            val userFacingIndex = assertId - RESERVED_NUM_OF_ASSERTS
            val cvlRange = assertPtr.cmd.meta[TACMeta.CVL_RANGE] ?: Range.Empty()

            // Apply filter if specified
            if (assertFilter != null) {
                val matchesIndex = indexFilters?.contains(userFacingIndex) == true
                val matchesLocation = locationFilters?.any { it.matches(cvlRange) } == true

                if (!matchesIndex && !matchesLocation) {
                    return@mapNotNull null
                }
            }

            val newRuleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertSpecFile(
                compiledRule.rule,
                assertId,
                assertPtr.cmd.msg,
                cvlRange,
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
            EncodedSubRule(newRule, newBaseRuleTac)
        }

        // A filter asks for specific asserts of the rule, so the Prover's own asserts are not of interest.
        val proverAsserts = if (assertFilter == null &&
            (mayAddProverAsserts(compiledRule.code) || hasProverAssertsOutsideTheSplit(compiledRule.code, idToPtr))
        ) {
            listOf(proverAssertsRule(compiledRule, idToPtr))
        } else {
            listOf()
        }
        return assertSubRules + proverAsserts
    }
}
