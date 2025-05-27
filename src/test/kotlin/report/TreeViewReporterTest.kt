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

import datastructures.stdcollections.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rules.RuleCheckResult
import rules.VerifyTime
import scene.*
import solver.SolverResult
import spec.cvlast.*
import spec.rules.*
import utils.Range

class TreeViewReporterTest {
    val json = Json { prettyPrint = true }

    private val mockScene: IScene = TrivialScene(
        DummyContractSourceAndLoader,
        DummyContractSourceAndLoader,
    )

    private val mockRange = Range.Empty("no comment")

    private fun mkTreeViewReporter() = TreeViewReporter(
        contractName = null,
        specFile = "testSpecFileName",
        scene = mockScene,
    )

    private fun mkSingleRule(
        name: String,
        isSatisfyRule: Boolean = false,
        ruleType: SpecType.Single = SpecType.Single.FromUser.SpecFile
    ) = CVLSingleRule(
        ruleIdentifier = RuleIdentifier.freshIdentifier(name),
        range = mockRange,
        params = emptyList(),
        description = "test rule named $name",
        goodDescription = "test rule named $name (good)",
        block = emptyList(),
        ruleType = ruleType,
        scope = CVLScope.AstScope,
        methodParamFilters = MethodParamFilters.noFilters(mockRange, CVLScope.AstScope),
        ruleGenerationMeta = SingleRuleGenerationMeta.Empty,
        isSatisfyRule = isSatisfyRule
    )

    private fun mkGroupRule(
        name: String,
        children: List<ICVLRule>,
        ruleType: SpecType.Group = mockk<SpecType.Group>()
    ) = GroupRule(
        ruleIdentifier = RuleIdentifier.freshIdentifier(name),
        range = Range.Empty("no comment"),
        rules = children,
        ruleType = ruleType,
        scope = CVLScope.AstScope,
    )

    private fun mkSingleRuleCheckResult(
        rule: CVLSingleRule,
        result: SolverResult,
        verifyTime: VerifyTime = VerifyTime.None,
    ): RuleCheckResult.Single.Basic {
        val rci = mockk<RuleCheckResult.Single.RuleCheckInfo.BasicInfo>()
        every { rci.dumpGraphLink } returns null

        return RuleCheckResult.Single.Basic(
            rule = rule,
            result = result,
            verifyTime = verifyTime,
            ruleCheckInfo = rci,
            ruleAlerts = null,
        )
    }

    @Test
    fun testToJson01() {
        val tvr = mkTreeViewReporter()

        val leaf1 = mkSingleRule("leaf1")
        val leaf2 = mkSingleRule("leaf2")
        val group1 = mkGroupRule("group1", listOf(leaf1, leaf2))

        tvr.addTopLevelRule(group1)
        tvr.registerSubruleOf(leaf1, group1)
        tvr.registerSubruleOf(leaf2, group1)
        printState(tvr)

        tvr.signalStart(leaf1)
        printState(tvr)

        tvr.signalStart(leaf2)
        printState(tvr)

        tvr.signalEnd(leaf1, mkSingleRuleCheckResult(leaf1, SolverResult.UNSAT))
        printState(tvr)

        tvr.signalEnd(leaf2, mkSingleRuleCheckResult(leaf2, SolverResult.UNSAT))
        printState(tvr)

        assertEquals(null, tvr.findNotMonotonicallyDescendingPath())
    }

    @Test
    fun testToJson02() {
        val tvr = mkTreeViewReporter()

        val leaf1 = mkSingleRule("leaf1")
        val leaf2 = mkSingleRule("leaf2")
        val group1 = mkGroupRule("group1", listOf(leaf1, leaf2))

        tvr.addTopLevelRule(group1)
        tvr.registerSubruleOf(leaf1, group1)
        tvr.registerSubruleOf(leaf2, group1)
        printState(tvr)

        tvr.signalStart(leaf1)
        printState(tvr)

        tvr.signalStart(leaf2)
        printState(tvr)

        tvr.signalEnd(leaf1, mkSingleRuleCheckResult(leaf1, SolverResult.SAT))
        printState(tvr)

        tvr.signalEnd(leaf2, mkSingleRuleCheckResult(leaf2, SolverResult.TIMEOUT))
        printState(tvr)

        assertEquals(null, tvr.findNotMonotonicallyDescendingPath())
    }

    @Test
    fun testToJson03() {
        val tvr = mkTreeViewReporter()
        val cvlInvariant = mockk<CVLInvariant>()

        val leaf1 = mkSingleRule("leaf1")
        val leaf2 = mkSingleRule("leaf2")
        val group1 = mkGroupRule(
            "group1",
            listOf(leaf1, leaf2),
            ruleType = SpecType.Group.InvariantCheck.InductionSteps(cvlInvariant)
        )
        val group2 = mkGroupRule(
            "group2",
            listOf(group1),
            ruleType = SpecType.Group.InvariantCheck.Root(cvlInvariant)
        )

        tvr.addTopLevelRule(group2)
        tvr.registerSubruleOf(group1, group2)
        tvr.registerSubruleOf(leaf1, group1)
        tvr.registerSubruleOf(leaf2, group1)
        printState(tvr)

        tvr.signalStart(leaf1)
        printState(tvr)

        tvr.signalStart(leaf2)
        printState(tvr)

        tvr.signalEnd(leaf1, mkSingleRuleCheckResult(leaf1, SolverResult.SAT))
        printState(tvr)

        tvr.signalEnd(leaf2, mkSingleRuleCheckResult(leaf2, SolverResult.UNSAT))
        printState(tvr)

        assertEquals(null, tvr.findNotMonotonicallyDescendingPath())
    }
    @Test
    fun testToJson04() {
        val tvr = mkTreeViewReporter()
        val cvlInvariant = mockk<CVLInvariant>()

        val leaf1 = mkSingleRule("leaf1")
        val leaf2 = mkSingleRule("leaf2")
        val group1 = mkGroupRule(
            "group1",
            listOf(leaf1, leaf2),
            ruleType = SpecType.Group.InvariantCheck.InductionSteps(cvlInvariant)
        )
        val group2 = mkGroupRule(
            "group2",
            listOf(group1),
            ruleType = SpecType.Group.InvariantCheck.Root(cvlInvariant)
        )

        tvr.addTopLevelRule(group2)
        tvr.registerSubruleOf(group1, group2)
        tvr.registerSubruleOf(leaf1, group1)
        tvr.registerSubruleOf(leaf2, group1)
        printState(tvr)

        tvr.signalStart(leaf1)
        printState(tvr)

        tvr.signalStart(leaf2)
        printState(tvr)

        tvr.signalEnd(leaf1, mkSingleRuleCheckResult(leaf1, SolverResult.SAT, VerifyTime.WithInterval(1000, 3000)))
        printState(tvr)

        tvr.signalEnd(leaf2, mkSingleRuleCheckResult(leaf2, SolverResult.UNSAT, VerifyTime.WithInterval(2000, 4000)))
        printState(tvr)

        assertEquals(null, tvr.findNotMonotonicallyDescendingPath())
    }

    /**
     * As before, but make the leafs "sanity" (in the sense of `rule_sanity`) nodes.
     */
    @Test
    fun testToJson05_sanity() {
        val tvr = mkTreeViewReporter()
        val cvlInvariant = mockk<CVLInvariant>()

        val leaf1 = mkSingleRule("leaf1", ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(mockk()))
        val leaf2 = mkSingleRule("leaf2", ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(mockk()))
        val group1 = mkSingleRule(
            "group1",
        )
        val group2 = mkGroupRule(
            "group2",
            listOf(group1),
            ruleType = SpecType.Group.InvariantCheck.Root(cvlInvariant)
        )

        tvr.addTopLevelRule(group2)
        tvr.registerSubruleOf(group1, group2)
        tvr.registerSubruleOf(leaf1, group1)
        tvr.registerSubruleOf(leaf2, group1)
        printState(tvr)

        tvr.signalStart(leaf1)
        printState(tvr)

        tvr.signalStart(leaf2)
        printState(tvr)

        tvr.signalEnd(leaf1, mkSingleRuleCheckResult(leaf1, SolverResult.SAT, VerifyTime.WithInterval(1000, 3000)))
        printState(tvr)

        tvr.signalEnd(leaf2, mkSingleRuleCheckResult(leaf2, SolverResult.UNSAT, VerifyTime.WithInterval(2000, 4000)))
        printState(tvr)

        assertEquals(null, tvr.findNotMonotonicallyDescendingPath())
    }


    private fun jsonPp(el: JsonElement, indent: Int = 0): String {
        fun fallback(e: JsonElement): String = json.encodeToString(JsonElement.serializer(), e)
        fun findentStr(i: Int) = "  ".repeat(i)
        val indentStr = findentStr(indent)
        val breakIndentStr = "\n$indentStr"
        val breakIndentStr1 = "\n${findentStr(indent + 1)}"
        val sep = breakIndentStr
        val prefix = ""
        return when (el) {
            is JsonArray -> when {
                el.isEmpty() -> "[],"
                else -> el.joinToString(separator = sep, prefix = prefix) {
                    jsonPp(it, indent + 1)
                }
            }
            is JsonObject ->
                el.entries.joinToString(separator = sep, prefix = prefix) { (k, v) ->
                    when {
                        (v is JsonArray && v.isEmpty()) ||
                        v is JsonPrimitive -> "$k : ${jsonPp(v, indent)}"
                        else -> "$k :$breakIndentStr1${jsonPp(v, indent + 1)}"
                    }
                }
            is JsonPrimitive -> fallback(el)
        }
    }

    private fun printState(tvr: TreeViewReporter) {
        tvr.hotUpdate()

        val keys = setOf("rules", "name", "children", "status", "isRunning", "duration")
        fun filter(el: JsonElement): JsonElement {
            return when (el) {
                is JsonArray -> JsonArray(el.map { filter(it) })
                is JsonObject -> JsonObject(
                    el.filter { (k, _) ->
                        k in keys
                    }.mapValues { (_, v) -> filter(v) }
                )
                is JsonPrimitive -> el
            }
        }

        val jsonEl = Json.parseToJsonElement(tvr.treePublic().toJsonString())
        val filtered = filter(jsonEl)
        val s = jsonPp(filtered)
        println(tvr.treePublic())
        println("----------")
        println(s)
        println("==========")
    }

}
