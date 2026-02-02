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

package report

import cli.SanityValues
import config.Config
import config.ConfigScope
import event.RuleEvent
import org.junit.jupiter.api.Test
import solver.SolanaFlowTest
import org.junit.jupiter.api.BeforeAll
import rules.sanity.TACSanityChecks
import sbf.vacuitySuffix
import solver.SolanaCallTraceTest
import solver.concat
import utils.Range
import utils.SourcePosition

class SolanaRuleLocationTest {
    /** Object containing the results of running the Solana flow on the test project. */
    companion object TestProjects {
        /* Results of running the Solana flow on the project. */
        var treeView: TreeViewReporter? = null

        /* All the rules that appear in the projects. */
        val rules =
            hashSetOf(
                "rule_passing_with_location",
                "rule_failing_with_location"
            )

        /**
         * Pre-computes the results for all the rules, so that the test cases do not have to run the Solana flow
         * individually.
         */
        @JvmStatic
        @BeforeAll
        fun precomputeResults(): Unit {
            ConfigScope(Config.DoSanityChecksForRules, SanityValues.ADVANCED).use {
                treeView = SolanaFlowTest.runSolanaFlowOnProjectForTests(SolanaCallTraceTest.confPath, SolanaCallTraceTest.elfFilePath, rules).first
            }
        }
    }

    @Test
    fun ruleLocationPassingRule() {
        assertExistsRuleWithExpectedRange(
            "rule_passing_with_location",
            getRangeForRule("src/rule_locations.rs", 4U)
        )
    }

    @Test
    fun ruleLocationPassingRuleCvlrSanity() {
        assertExistsRuleWithExpectedRange(
            "rule_passing_with_location-$vacuitySuffix",
            getRangeForRule("src/rule_locations.rs", 4U)
        )
    }

    @Test
    fun ruleLocationPassingRuleTacSanity() {
        val expectedName = concat(
            "rule_passing_with_location",
            RuleEvent.ASSERTS_NODE_TITLE,
            TACSanityChecks.VacuityCheck.SANITY_RULE_NAME,
        )
        assertExistsRuleWithExpectedRange(
            expectedName,
            getRangeForRule("src/rule_locations.rs", 4U)
        )
    }

    @Test
    fun ruleLocationFailingRule() {
        assertExistsRuleWithExpectedRange(
            "rule_failing_with_location",
            getRangeForRule("src/rule_locations.rs", 14U)
        )
    }

    @Test
    fun ruleLocationFailingRuleCvlrSanity() {
        assertExistsRuleWithExpectedRange(
            "rule_failing_with_location-$vacuitySuffix",
            getRangeForRule("src/rule_locations.rs", 14U)
        )
    }

    private fun assertExistsRuleWithExpectedRange(ruleIdentifier: String, expectedRange: Range.Range) {
        val nodes = treeView!!.treePublic().treeViewNodeResults().filter {
            it.rule != null &&
                it.rule!!.ruleIdentifier.toString() == ruleIdentifier &&
                it.rule!!.range == expectedRange
        }
        assert(nodes.isNotEmpty()) {
            "Could not find a node in the tree with for rule $ruleIdentifier with range ${expectedRange.file}-${expectedRange.start}-${expectedRange.end}"
        }
    }

    private fun getRangeForRule(
        sourceFile: String,
        startLine: UInt,
        startColumn: UInt = 1U,
    ): Range.Range {
        val startLocation = SourcePosition(startLine - 1U, startColumn - 1U)
        val endLocation = SourcePosition(startLine, 0U)
        return Range.Range(sourceFile, startLocation, endLocation)
    }
}
