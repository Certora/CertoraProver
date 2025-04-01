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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package report

import cli.SanityValues
import config.Config
import config.ConfigScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import solver.SolanaFlowTest
import datastructures.stdcollections.*

class SolanaFlowTreeViewReporterTest {
    data class RuleAssertCount(val totalAsserts: Int, val verifiedAsserts: Int)

    /**
     * A mapping from rule name to the total number of asserts and verified asserts in this rule
     * */
    private val rulesToAsserts = mapOf(
        "rule_vacuity_test_expect_sanity_success" to RuleAssertCount(1, 1),
        "rule_vacuity_test_expect_sanity_failure" to RuleAssertCount(1, 1),
        "rule_multi_assert" to RuleAssertCount(2, 0)
    )
    private val totalAsserts = rulesToAsserts.values.sumOf { it.totalAsserts }

    /**
     * The vacuity check [rules.sanity.TACSanityChecks.VacuityCheck] is only executed for verified asserts.
     * This total count will be used to assert the number of nodes for this check in the tree.
     */
    private val totalVerifiedAssert = rulesToAsserts.values.sumOf { it.verifiedAsserts }


    @Test
    fun multiAssertFlow() {
        ConfigScope(Config.MultiAssertCheck, true).use {
            val treeView = SolanaFlowTest.runSolanaFlowOnProjectForTests(rulesToAsserts.keys.toHashSet()).first
            val nodes = treeView.nodes()
            /**
             * The multi assert mode generates one TAC program per assert and the tree will list the base rule and for each assert in the
             * rule a child node.
             */
            assertEquals(rulesToAsserts.keys.size + totalAsserts, nodes.size, "Found $nodes")

            // The number of paths to leaves in the tree must match the number of asserts across all rules
            assertEquals(totalAsserts, treeView.pathsToLeaves().size)
        }
    }

    @Test
    fun sanityBasicFlow() {
        ConfigScope(Config.DoSanityChecksForRules, SanityValues.BASIC).use {
            val treeView = SolanaFlowTest.runSolanaFlowOnProjectForTests(rulesToAsserts.keys.toHashSet()).first
            val nodes = treeView.nodes()
            // Basic Sanity duplicates the number of rules, i.e., we have twice as many nodes in the tree.
            assertEquals(2 * rulesToAsserts.keys.size, nodes.size, "Found $nodes")

            // The sanity rules are listed as children below the parent rule and the number of paths to leaves in the tree must match the number of rules
            assertEquals(rulesToAsserts.keys.size, treeView.pathsToLeaves().size)
        }
    }

    @Test
    fun sanityAdvancedFlow() {
        ConfigScope(Config.DoSanityChecksForRules, SanityValues.ADVANCED).use {
            val treeView = SolanaFlowTest.runSolanaFlowOnProjectForTests(rulesToAsserts.keys.toHashSet()).first
            val nodes = treeView.nodes()

            // Advanced mode runs all basic sanity rules and on all verified asserts the [rules.sanity.TACSanityChecks.VacuityCheck].
            assertEquals(
                rulesToAsserts.keys.size  // The nodes at the base level
                    + rulesToAsserts.keys.size // The nodes for the sub rules that are duplicated due to sanity basic rules
                    + totalVerifiedAssert // The sanity rules that are running for verified assert in [rules.sanity.TACSanityChecks.VacuityCheck]
                , nodes.size, "Found $nodes")
        }
    }

    @Test
    fun sanityAdvancedAndMultiAssertFlow() {
        ConfigScope(Config.DoSanityChecksForRules, SanityValues.ADVANCED).extend(Config.MultiAssertCheck, true).use {
            val treeView = SolanaFlowTest.runSolanaFlowOnProjectForTests(rulesToAsserts.keys.toHashSet()).first
            val nodes = treeView.nodes()

            assertEquals(
                rulesToAsserts.keys.size  // The nodes at the base level
                    + rulesToAsserts.keys.size // The rules are duplicated due to sanity basic rules
                    + totalAsserts // All nodes below the base level that are generated due to multi assert mode
                    + totalVerifiedAssert // The sanity rules that are running for verified assert in [rules.sanity.TACSanityChecks.VacuityCheck]
                , nodes.size, "Found $nodes")
        }
    }
}