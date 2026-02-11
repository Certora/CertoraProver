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

/** Represents a filter entry for assertions: either by index or by file location */
private sealed class AssertFilterEntry {
    data class Index(val value: Int) : AssertFilterEntry()
    data class FileLocation(val file: String, val line: Int) : AssertFilterEntry()
}

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
     * Parses a single filter entry string.
     * Returns an Index if it's a valid integer, or a FileLocation if it matches the "file:line" pattern.
     */
    private fun parseFilterEntry(entry: String): AssertFilterEntry? {
        // Try parsing as an integer index first
        entry.toIntOrNull()?.let { return AssertFilterEntry.Index(it) }

        // Try parsing as file:line format
        // Find the last colon to handle file paths that may contain colons (e.g., Windows paths)
        val lastColonIndex = entry.lastIndexOf(':')
        if (lastColonIndex > 0 && lastColonIndex < entry.length - 1) {
            val file = entry.substring(0, lastColonIndex)
            val lineStr = entry.substring(lastColonIndex + 1)
            lineStr.toIntOrNull()?.let { line ->
                return AssertFilterEntry.FileLocation(file, line)
            }
        }

        return null
    }

    /**
     * Parses the assert filter from SolanaConfig.AssertFilter.
     * Returns null if no filter is specified, otherwise returns a list of filter entries.
     * Supports both integer indices (1-based) and file locations (file:line format).
     */
    private fun parseAssertFilter(): List<AssertFilterEntry>? {
        val filterStrings = SolanaConfig.AssertFilter.getOrNull() ?: return null
        return filterStrings.mapNotNull { parseFilterEntry(it) }.ifEmpty { null }
    }

    /**
     * Checks if a Range matches any file location filter entry.
     * Compares the file name (ignoring path) and line number.
     * Column information in the Range is ignored.
     */
    private fun rangeMatchesFilter(range: Range, filters: List<AssertFilterEntry.FileLocation>): Boolean {
        if (range is Range.Empty) {
            return false
        }
        val rangeData = range as Range.Range
        // Extract just the file name from the range's specFile path
        val rangeFileName = rangeData.specFile.substringAfterLast('/')
        val rangeLine = rangeData.start.lineForIDE

        return filters.any { filter ->
            val filterFileName = filter.file.substringAfterLast('/')
            filterFileName == rangeFileName && filter.line == rangeLine
        }
    }

    /**
     * For a given rule [compiledRule] with N assert commands, it returns N new rules where each rule has
     * exactly one assert.
     * If [SolanaConfig.AssertFilter] is set, only asserts matching the filter are included.
     * The filter supports both 1-based indices and file:line locations.
     **/
    fun transformMulti(compiledRule: CompiledGenericRule.Compiled): List<CompiledGenericRule.Compiled> {
        val idToPtr = assertIdToAssertPtr(compiledRule.code)
        val assertFilter = parseAssertFilter()

        // Separate filter entries by type for efficient matching
        val indexFilters = assertFilter?.filterIsInstance<AssertFilterEntry.Index>()?.map { it.value }?.toSet()
        val locationFilters = assertFilter?.filterIsInstance<AssertFilterEntry.FileLocation>()

        return idToPtr.mapNotNull { (assertId, assertPtr) ->
            val userFacingIndex = assertId - RESERVED_NUM_OF_ASSERTS
            val cvlRange = assertPtr.cmd.meta[TACMeta.CVL_RANGE] ?: Range.Empty()

            // Apply filter if specified
            if (assertFilter != null) {
                val matchesIndex = indexFilters?.contains(userFacingIndex) == true
                val matchesLocation = locationFilters?.let { rangeMatchesFilter(cvlRange, it) } == true

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

            compiledRule.copy(code = newBaseRuleTac, rule = newRule)
        }
    }
}
