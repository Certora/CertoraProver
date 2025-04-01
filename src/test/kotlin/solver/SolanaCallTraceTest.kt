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

package solver


import handleSolanaFlow
import infra.CertoraBuildKind
import infra.CertoraBuild
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import report.TreeViewReporter
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.AlternativeRepresentations
import rules.RuleCheckResult
import utils.Range
import utils.*
import java.math.BigInteger
import kotlin.io.path.Path

/**
 * This object adds capability to run a set of rules (see [runSolanaFlowOnProjectForTests]) on the
 * Solana project defined in src/test/resources/solana/project_for_tests
 */
object SolanaFlowTest {

    /* Path to configuration file of the projects. */
    private val confPath = Path("./src/test/resources/solana/project_for_tests/run.conf")

    /* Path tot the pre-compiled ELF files. */
    private val elfFilePath =
        Path("./src/test/resources/solana/project_for_tests/project_for_tests.so")

    /**
     * Runs the Solana flow on the project defined in src/test/resources/solana/project_for_tests
     * in a blocking environment and returns the results and the TreeViewReporter containing the
     * results of the execution.
     * */
    fun runSolanaFlowOnProjectForTests(
        rules: HashSet<String>
    ): Pair<TreeViewReporter, List<RuleCheckResult.Single>> {
        return CertoraBuild.inTempDir(CertoraBuildKind.SolanaBuild(rules), confPath)
            .useWithBuildDir { _ ->
                runBlocking {
                    // Use a safe dispatcher to ensure we don't leave the current thread context.
                    // If this is removed, the tests can incur in a [IllegalStateException].
                    withContext(Dispatchers.Default) {
                        // get the results
                        handleSolanaFlow(elfFilePath.toString())
                    }
                }
            }
    }
}

class SolanaCallTraceTest {

    /** Object containing the results of running the Solana flow on the test projects. */
    companion object TestProjects {
        /* Results of running the Solana flow on the projects. */
        private var results: List<RuleCheckResult.Single> = listOf()

        /* All the rules that appear in the projects. */
        private val rules =
            hashSetOf(
                "rule_failing_assert",
                "rule_failing_assert_nested_call",
                "rule_failing_assert_in_branch",
                "rule_failing_assert_in_other_module",
                "rule_failing_assert_column_1",
                "rule_print_tag",
                "rule_print_tag_nested_call",
                "rule_print_values",
                "rule_print_values_nested_call",
                "rule_print_values_in_match",
                "rule_print_u128",
                "rule_print_i128",
                "rule_print_u64_as_fixed_main_body",
                "rule_print_u64_as_fixed_nested_call",
                "rule_print_u64_as_fixed_other_module",
                "rule_nondet_main_body",
                "rule_nondet_nested_call",
                "rule_nondet_other_module",
                "rule_print_location_main_body",
                "rule_print_location_nested_call",
                "rule_print_location_in_branch",
                "rule_print_location_in_other_module",
                "rule_attach_location_assert_main_body",
                "rule_attach_location_assert_nested_call",
                "rule_attach_location_assert_other_module",
                "rule_attach_location_print_tag_main_body",
                "rule_attach_location_print_tag_nested_call",
                "rule_attach_location_print_tag_other_module",
                "rule_attach_location_print_value_main_body",
                "rule_attach_location_print_value_nested_call",
                "rule_attach_location_print_value_other_module",
                "rule_attach_location_nondet_main_body",
                "rule_attach_location_nondet_nested_call",
                "rule_attach_location_nondet_other_module",
                "rule_attach_location_satisfy_main_body",
                "rule_attach_location_satisfy_nested_call",
                "rule_attach_location_satisfy_other_module",
            )

        /**
         * Pre-computes the results for all the rules, so that the test cases do not have to run the Solana flow
         * individually.
         */
        @JvmStatic
        @BeforeAll
        fun precomputeResults(): Unit {
            results = SolanaFlowTest.runSolanaFlowOnProjectForTests(rules).second
        }
    }

    @Test
    fun assertInMainBody() {
        ruleContainsSolanaUserAssertAt(
            "rule_failing_assert",
            results,
            callInstanceRange("src/asserts.rs", 5U, 14U)
        )
    }

    @Test
    fun assertInNestedCall() {
        ruleContainsSolanaUserAssertAt(
            "rule_failing_assert_nested_call",
            results,
            callInstanceRange("src/asserts.rs", 16U, 14U)
        )
    }

    @Test
    fun assertInBranch() {
        ruleContainsSolanaUserAssertAt(
            "rule_failing_assert_in_branch",
            results,
            callInstanceRange("src/asserts.rs", 24U, 22U)
        )
    }

    @Test
    fun assertInOtherModule() {
        ruleContainsSolanaUserAssertAt(
            "rule_failing_assert_in_other_module",
            results,
            callInstanceRange("src/functionality.rs", 6U, 9U)
        )
    }

    @Test
    fun assertFirstColumn() {
        ruleContainsSolanaUserAssertAt(
            "rule_failing_assert_column_1",
            results,
            callInstanceRange("src/asserts.rs", 37U, 1U)
        )
    }

    @Test
    fun printTagInMainBody() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_tag",
            results,
            callInstanceRange("src/print_tags.rs", 6U, 14U)
        )
    }

    @Test
    fun printTagInNestedCall() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_tag_nested_call",
            results,
            callInstanceRange("src/print_tags.rs", 17U, 14U)
        )
    }

    @Test
    fun printValuesInMainBody() {
        ruleContainsSolanaPrintValuesAt(
            "rule_print_values",
            results,
            callInstanceRange("src/print_values.rs", 6U, 14U)
        )
    }

    @Test
    fun printValuesInNestedCall() {
        ruleContainsSolanaPrintValuesAt(
            "rule_print_values_nested_call",
            results,
            callInstanceRange("src/print_values.rs", 18U, 14U)
        )
    }

    @Test
    fun printValuesInMatch() {
        ruleContainsSolanaPrintValuesAt(
            "rule_print_values_in_match",
            results,
            callInstanceRange("src/print_values.rs", 25U, 22U)
        )
    }

    @Test
    fun printU128Zero() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_u128",
            results,
            callInstanceRange("src/print_128.rs", 5U, 1U),
            BigInteger.ZERO
        )
    }

    @Test
    fun printU128One() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_u128",
            results,
            callInstanceRange("src/print_128.rs", 6U, 1U),
            BigInteger.ONE
        )
    }

    @Test
    fun printU128MaxU64() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_u128",
            results,
            callInstanceRange("src/print_128.rs", 7U, 1U),
            // u64::max = 2^64 - 1
            BigInteger.TWO.pow(64) - BigInteger.ONE
        )
    }

    @Test
    fun printU128MaxU64PlusOne() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_u128",
            results,
            callInstanceRange("src/print_128.rs", 8U, 1U),
            // u64::max + 1 is 1 followed by 64 zeros.
            BigInteger.valueOf(1).shiftLeft(64)
        )
    }

    @Test
    fun printU128MaxU128() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_u128",
            results,
            callInstanceRange("src/print_128.rs", 9U, 1U),
            // u128::max = 1 << 128 - 1
            BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        )
    }

    @Test
    fun printI128Zero() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 15U, 1U),
            BigInteger.ZERO
        )
    }

    @Test
    fun printI128One() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 16U, 1U),
            BigInteger.ONE
        )
    }

    @Test
    fun printI128MinusOne() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 17U, 1U),
            BigInteger.ZERO - BigInteger.ONE
        )
    }

    @Test
    fun printI128MaxI64() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 18U, 1U),
            BigInteger.valueOf(Long.MAX_VALUE)
        )
    }

    @Test
    fun printI128MinI64() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 19U, 1U),
            BigInteger.valueOf(Long.MIN_VALUE)
        )
    }

    @Test
    fun printI128MaxI64PlusOne() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 20U, 1U),
            BigInteger.valueOf(Long.MAX_VALUE).plus(BigInteger.ONE)
        )
    }

    @Test
    fun printI128MinI64MinusOne() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 21U, 1U),
            BigInteger.valueOf(Long.MIN_VALUE).minus(BigInteger.ONE)
        )
    }

    @Test
    fun printI128MaxI128() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 22U, 1U),
            // i128:: max = 1 << 127 - 1
            BigInteger.ONE.shiftLeft(127).minus(BigInteger.ONE)
        )
    }

    @Test
    fun printI128MinI128() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
            "rule_print_i128",
            results,
            callInstanceRange("src/print_128.rs", 23U, 1U),
            // i128::min = -(1 << 127)
            BigInteger.ONE.shiftLeft(127).negate()
        )
    }

    @Test
    fun printU64AsFixedInMainBody() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstValue(
            "rule_print_u64_as_fixed_main_body",
            results,
            callInstanceRange("src/print_fixed.rs", 5U, 1U),
            "1.03448486328125"
        )
    }

    @Test
    fun printU64AsFixedInNestedCall() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstValue(
            "rule_print_u64_as_fixed_nested_call",
            results,
            callInstanceRange("src/print_fixed.rs", 16U, 1U),
            "1"
        )
    }

    @Test
    fun printU64AsFixedInOtherModule() {
        ruleContainsSolanaPrintValuesAtRangeWithFirstValue(
            "rule_print_u64_as_fixed_other_module",
            results,
            callInstanceRange("src/functionality.rs", 40U, 1U),
            "1.5"
        )
    }

    @Test
    fun nondetInMainBody() {
        ruleContainsSolanaExternalCallAt(
            "rule_nondet_main_body",
            results,
            callInstanceRange("src/nondet_prints.rs", 5U, 22U)
        )
    }

    @Test
    fun nondetInNestedCall() {
        ruleContainsSolanaExternalCallAt(
            "rule_nondet_nested_call",
            results,
            callInstanceRange("src/nondet_prints.rs", 16U, 22U)
        )
    }

    @Test
    fun nondetInOtherModule() {
        ruleContainsSolanaExternalCallAt(
            "rule_nondet_other_module",
            results,
            callInstanceRange("src/functionality.rs", 27U, 14U)
        )
    }

    @Test
    fun printLocationInMainBody() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_location_main_body",
            results,
            callInstanceRange("src/print_locations.rs", 5U, 1U)
        )
    }

    @Test
    fun printLocationInNestedCall() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_location_nested_call",
            results,
            callInstanceRange("src/print_locations.rs", 16U, 1U)
        )
    }

    @Test
    fun printLocationInBranch() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_location_in_branch",
            results,
            callInstanceRange("src/print_locations.rs", 22U, 1U)
        )
    }

    @Test
    fun printLocationInOtherModule() {
        ruleContainsSolanaPrintTagAt(
            "rule_print_location_in_other_module",
            results,
            callInstanceRange("src/functionality.rs", 11U, 1U)
        )
    }

    @Test
    fun attachLocationAssertInMainBody() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_assert_main_body",
            results,
            callInstanceRange("src/attach_location.rs", 5U, 1U)
        )
    }

    @Test
    fun attachLocationAssertInNestedCall() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_assert_nested_call",
            results,
            callInstanceRange("src/attach_location.rs", 14U, 1U)
        )
    }

    @Test
    fun attachLocationAssertInOtherModule() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_assert_other_module",
            results,
            callInstanceRange("src/functionality.rs", 15U, 1U)
        )
    }

    @Test
    fun attachLocationPrintTagInMainBody() {
        ruleContainsSolanaPrintTagAt(
            "rule_attach_location_print_tag_main_body",
            results,
            callInstanceRange("src/attach_location.rs", 24U, 1U)
        )
    }

    @Test
    fun attachLocationPrintTagInNestedCall() {
        ruleContainsSolanaPrintTagAt(
            "rule_attach_location_print_tag_nested_call",
            results,
            callInstanceRange("src/attach_location.rs", 35U, 1U)
        )
    }

    @Test
    fun attachLocationPrintTagInOtherModule() {
        ruleContainsSolanaPrintTagAt(
            "rule_attach_location_print_tag_other_module",
            results,
            callInstanceRange("src/functionality.rs", 19U, 1U)
        )
    }

    @Test
    fun attachLocationPrintValueInMainBody() {
        ruleContainsSolanaPrintValuesAt(
            "rule_attach_location_print_value_main_body",
            results,
            callInstanceRange("src/attach_location.rs", 47U, 1U)
        )
    }

    @Test
    fun attachLocationPrintValueInNestedCall() {
        ruleContainsSolanaPrintValuesAt(
            "rule_attach_location_print_value_nested_call",
            results,
            callInstanceRange("src/attach_location.rs", 58U, 1U)
        )
    }

    @Test
    fun attachLocationPrintValueInOtherModule() {
        ruleContainsSolanaPrintValuesAt(
            "rule_attach_location_print_value_other_module",
            results,
            callInstanceRange("src/functionality.rs", 23U, 1U)
        )
    }

    @Test
    fun attachLocationNondetInMainBody() {
        ruleContainsSolanaExternalCallAt(
            "rule_attach_location_nondet_main_body",
            results,
            callInstanceRange("src/attach_location.rs", 70U, 1U)
        )
    }

    @Test
    fun attachLocationNondetInNestedCall() {
        ruleContainsSolanaExternalCallAt(
            "rule_attach_location_nondet_nested_call",
            results,
            callInstanceRange("src/attach_location.rs", 81U, 1U)
        )
    }

    @Test
    fun attachLocationNondetInOtherModule() {
        ruleContainsSolanaExternalCallAt(
            "rule_attach_location_nondet_other_module",
            results,
            callInstanceRange("src/functionality.rs", 32U, 1U)
        )
    }

    @Test
    fun attachLocationSatisfyInMainBody() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_satisfy_main_body",
            results,
            callInstanceRange("src/attach_location.rs", 94U, 1U)
        )
    }

    @Test
    fun attachLocationSatisfyInNestedCall() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_satisfy_nested_call",
            results,
            callInstanceRange("src/attach_location.rs", 103U, 1U)
        )
    }

    @Test
    fun attachLocationSatisfyInOtherModule() {
        ruleContainsSolanaUserAssertAt(
            "rule_attach_location_satisfy_other_module",
            results,
            callInstanceRange("src/functionality.rs", 36U, 1U)
        )
    }

    private fun ruleContainsSolanaUserAssertAt(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range
    ) {
        val solanaUserAsserts = getUserAsserts(ruleName, results)
        val existsAssertWithExpectedRange = existsCallInstanceAtRange(solanaUserAsserts, expectedRange)
        assert(existsAssertWithExpectedRange) { "Did not find any asserts with range ${expectedRange.file}:${expectedRange.lineNumber}" }
    }

    private fun getUserAsserts(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
    ): List<CallInstance.SolanaUserAssert> {
        val calltrace = getCalltraceOfRule(ruleName, results)
        return calltrace.callHierarchyRoot.filterCallInstancesOf<CallInstance.SolanaUserAssert> { true }
    }

    private fun ruleContainsSolanaPrintTagAt(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range
    ) {
        val solanaPrintTags = getPrintTags(ruleName, results)
        val existsPrintTagWithExpectedRange = existsCallInstanceAtRange(solanaPrintTags, expectedRange)
        assert(existsPrintTagWithExpectedRange) { "Did not find any print tag with range ${expectedRange.file}:${expectedRange.lineNumber}" }
    }

    private fun getPrintTags(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
    ): List<CallInstance.SolanaCexPrintTag> {
        val calltrace = getCalltraceOfRule(ruleName, results)
        return calltrace.callHierarchyRoot.filterCallInstancesOf<CallInstance.SolanaCexPrintTag> { true }
    }

    private fun ruleContainsSolanaPrintValuesAt(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range
    ) {
        val solanaPrintTags = getPrintValues(ruleName, results)
        val existsPrintValuesWithExpectedRange = existsCallInstanceAtRange(solanaPrintTags, expectedRange)
        assert(existsPrintValuesWithExpectedRange) { "Did not find any print values with range ${expectedRange.file}:${expectedRange.lineNumber}" }
    }

    /**
     * Checks that it exists a [CallInstance.SolanaCexPrintValues] entry in the call trace at a specific range, and that
     * the first value in that entry corresponds to [expectedFirstValue].
     */
    private fun ruleContainsSolanaPrintValuesAtRangeWithFirstValue(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range,
        expectedFirstValue: String
    ) {
        val solanaPrintTags = getPrintValues(ruleName, results)
        val existsPrintValuesWithExpectedRangeAndFirstValue =
            existsPrintValuesAtRangeWithFirstValue(solanaPrintTags, expectedRange, expectedFirstValue)
        assert(existsPrintValuesWithExpectedRangeAndFirstValue) {
            "Did not find any print values with range ${expectedRange.file}:${expectedRange.lineNumber} with first value $expectedFirstValue"
        }
    }

    /**
     * Checks that it exists a [CallInstance.SolanaCexPrintValues] entry in the call trace at a specific range, and that
     * the first value in Sarif in decimal representation in that entry corresponds to [expectedFirstValue].
     */
    private fun ruleContainsSolanaPrintValuesAtRangeWithFirstDecimalValueInSarif(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range,
        expectedFirstValue: BigInteger
    ) {
        val solanaPrintTags = getPrintValues(ruleName, results)
        val existsPrintValuesWithExpectedRangeAndFirstValue =
            existsPrintValuesAtRangeWithFirstSarifDecimalValue(solanaPrintTags, expectedRange, expectedFirstValue)
        assert(existsPrintValuesWithExpectedRangeAndFirstValue) {
            "Did not find any print values with range ${expectedRange.file}:${expectedRange.lineNumber} with first value $expectedFirstValue"
        }
    }


    private fun getPrintValues(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
    ): List<CallInstance.SolanaCexPrintValues> {
        val calltrace = getCalltraceOfRule(ruleName, results)
        return calltrace.callHierarchyRoot.filterCallInstancesOf<CallInstance.SolanaCexPrintValues> { true }
    }

    private fun ruleContainsSolanaExternalCallAt(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
        expectedRange: Range.Range
    ) {
        val solanaExternalCalls = getExternalCalls(ruleName, results)
        val existsAssertWithExpectedRange = existsCallInstanceAtRange(solanaExternalCalls, expectedRange)
        assert(existsAssertWithExpectedRange) { "Did not find any external call with range ${expectedRange.file}:${expectedRange.lineNumber}" }
    }

    private fun getExternalCalls(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
    ): List<CallInstance.SolanaExternalCall> {
        val calltrace = getCalltraceOfRule(ruleName, results)
        return calltrace.callHierarchyRoot.filterCallInstancesOf<CallInstance.SolanaExternalCall> { true }
    }

    private fun getCalltraceOfRule(
        ruleName: String,
        results: List<RuleCheckResult.Single>,
    ): CallTrace {
        // Select the results relative to the rule.
        val resultsRelativeToTheRule = results.filter { it.rule.ruleIdentifier.toString() == ruleName }

        // Assert that there is only one result, that is the one for the rule that we are considering.
        assertEquals(
            1,
            resultsRelativeToTheRule.size,
            "Should be unreachable: rule $ruleName has either zero or more than one results associated with it"
        )
        val result = resultsRelativeToTheRule[0]
        val withExamplesData =
            result.ruleCheckInfo as? RuleCheckResult.Single.RuleCheckInfo.WithExamplesData
                ?: fail("Failed to cast ruleCheckInfo to example with data. It was of type: ${result.ruleCheckInfo::class.simpleName}")
        return withExamplesData.examples.head.callTrace
            ?: fail("The first example in the counterexamples does not have a call trace associated")
    }

    /** Returns true iff there exists a call instance with the [expectedRange]. */
    private fun existsCallInstanceAtRange(
        callInstances: Iterable<CallInstance>,
        expectedRange: Range.Range
    ): Boolean {
        return callInstances.any { callInstance ->
            val callInstanceRange = getRange(callInstance)
            callInstanceRange == expectedRange
        }
    }

    /**
     * Returns true iff there exists a call instance with the [expectedRange]. Furthermore, the first value in the
     * Sarif output must be [expectedFirstValue].
     */
    private fun existsPrintValuesAtRangeWithFirstValue(
        printValues: Iterable<CallInstance.SolanaCexPrintValues>,
        expectedRange: Range.Range,
        expectedFirstValue: String,
    ): Boolean {
        return printValues.any { callInstance ->
            val printValuesRange = getRange(callInstance)
            val firstValueInPrintValues = callInstance.sarif.toString().split(' ')[1]
            printValuesRange == expectedRange && firstValueInPrintValues == expectedFirstValue
        }
    }

    /**
     * Returns true iff there exists a call instance with the [expectedRange]. Furthermore, the first value in the
     * Sarif output in decimal representation must be [expectedFirstValue].
     */
    private fun existsPrintValuesAtRangeWithFirstSarifDecimalValue(
        printValues: Iterable<CallInstance.SolanaCexPrintValues>,
        expectedRange: Range.Range,
        expectedFirstValue: BigInteger,
    ): Boolean {
        return printValues.any { callInstance ->
            val printValuesRange = getRange(callInstance)
            val firstValueInPrintValues = callInstance.sarif.args[0].values.map.getValue(AlternativeRepresentations.Representations.Decimal)
            printValuesRange == expectedRange && firstValueInPrintValues == expectedFirstValue.toString()
        }
    }

    private fun getRange(callInstance: CallInstance): Range.Range =
        (callInstance.range
            ?: fail("Failed to extract range") as? Range.Range
            ?: fail("Failed to cast range to range.Range."))

    /** Returns the range of a call instance. The end position is the first character of the next line. */
    private fun callInstanceRange(
        sourceFile: String,
        startLine: UInt,
        startColumn: UInt,
    ): Range.Range {
        val startLocation = SourcePosition(startLine - 1U, startColumn - 1U)
        val endLocation = SourcePosition(startLine, 0U)
        return Range.Range(sourceFile, startLocation, endLocation)
    }
}

