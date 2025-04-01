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

import cli.SanityValues
import config.Config.DoSanityChecksForRules
import config.realOpt
import datastructures.stdcollections.*
import spec.CVLKeywords
import spec.cvlast.*
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.rules.CVLSingleRule
import utils.*
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.safeForce

/**
 * Generates sanity rules based on [originalRule].
 */
fun generatedRulesForSpecChecks(originalRule: CVLSingleRule) =
    GenerateRulesForVacuityCheck.generate(originalRule) +
    GenerateRulesForRedundantRequiresCheck.generate(originalRule) +
    GenerateRulesForAssertions.generate(originalRule)


interface ISanityRule {

    /**
     * Level for the sanity check, where the possible levels are the enum members of [SanityValues].
     */
    val sanityLevel: SanityValues

    /**
     * Short name which describes what does this sanity-check checks.
     */
    val sanityRuleName: String
}


interface SanityRuleGenerator : ISanityRule {
    /**
     * Generates list of [CVLSingleRule], based on the original rule [rule].
     */
    fun generate(rule: CVLSingleRule): List<CVLSingleRule>
}

/**
 * Returns suffix declaration id for the assume command [this], based on its
 * [Range] and whether it serves as a precondition of an invariant.
 */
fun CVLCmd.Simple.AssumeCmd.getAssumeSuffixDecId(): String =
    if (this is CVLCmd.Simple.AssumeCmd.Assume && this.invariantPreCond) {
        "_precondition_" + this.getSuffixDecId()
    } else {
        this.getSuffixDecId()
    }

/**
 * Returns suffix declaration id, based on the range of [this].
 */
fun CVLCmd.Simple.getSuffixDecId(): String {
    return when (val range = this.range) {
        is Range.Empty -> range.toString()
        is Range.Range ->
            // for rules from the spec, all the CVL Ranges are supposed to exist. adding an offset + 1 to report the correct line number.
            "_${range.start.line.toInt() + 1}_${range.start.charByteOffset}"
    }
}

/**
 * Is [sanityLevel] not greater than the value attached to the `ruleSanityChecks` flag.
 */
fun isSanityLevelInScope(sanityLevel: SanityValues): Boolean =
    sanityLevel <= DoSanityChecksForRules.get()


object GenerateRulesForVacuityCheck : SanityRuleGenerator {

    override val sanityLevel: SanityValues = SanityValues.BASIC
    override val sanityRuleName: String = "rule_not_vacuous"

    /**
     * Here, rules are generated, to check whether there's a path that reaches the last statement of [rule]
     * (which is expected to be an assert command), while not necessarily respecting all the CVL assert
     * statements that occur in the rule.
     * Strategy:
     * 1. Replace all assert commands with definitions - `assert exp;` is replaced with `_ = exp;` (because assertions
     * might have function invocations inside them, and those might cause a revert and also have side effects on the
     * storage state).
     * 2. Add assert(false) to the end of the rule.
     **/
    override fun generate(rule: CVLSingleRule): List<CVLSingleRule> {
        val assertToDefinition =
            object : CVLCmdTransformer<Nothing>(expTransformer = CVLExpTransformer.copyTransformer()) {
                override fun assertCmd(cmd: CVLCmd.Simple.Assert): CollectingResult<CVLCmd, Nothing> {
                    return CVLCmd.Simple.Definition(
                        range = cmd.range,
                        type = null,
                        idL = listOf(
                            CVLLhs.Id(
                                range = cmd.range,
                                id = CVLKeywords.wildCardExp.keyword,
                                tag = CVLExpTag(
                                    type = CVLKeywords.wildCardExp.type,
                                    scope = cmd.scope,
                                    range = cmd.range
                                )
                            )
                        ),
                        exp = cmd.exp,
                        scope = cmd.scope
                    ).lift()
                }
            }

        if (!isSanityLevelInScope(sanityLevel)) {
            return emptyList()
        }
        val ret = assertToDefinition.cmdList(rule.block).flatten().safeForce() +
            CVLCmd.Simple.Assert(
                range = rule.range,
                description = "sanity check for rule ${rule.declarationId} succeeded",
                exp = CVLExp.Constant.BoolLit(
                    false,
                    tag = CVLExpTag(
                        scope = rule.scope,
                        range = Range.Empty(),
                        type = CVLType.PureCVLType.Primitive.Bool
                    )
                ),
                scope = rule.scope
            )
        return listOf(
            rule.copy(
                block = ret,
                // note that these two descriptions are currently not seen by the user, since we display the results
                // of sanity check rules differently from other rules (they are separated from the other rules in
                // [CVLTestGenerator.separateRuleSanityCheckRules] and their results are added as a
                //  supplement to the "details" column of each rule)
                description = "sanity check (vacuity of rule) for rule ${rule.declarationId}",
                goodDescription = "sanity check (vacuity of rule) for rule ${rule.declarationId}, this rule was added " +
                    "because the ${DoSanityChecksForRules.option.realOpt()} flag was set",
                ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(rule),
            )
        )
    }

}

/**
 * GenerateRulesForAssertions provides a bit of refactoring for the previous GenerateRulesForVacuousAsserts
 * object so that vacuous assert checks and the new tautology check code can share infrastructure.
 */
sealed class GenerateRulesForAssertions : SanityRuleGenerator {
    override val sanityLevel = SanityValues.ADVANCED

    companion object {
        fun generate(rule: CVLSingleRule): List<CVLSingleRule> = when (rule.ruleType) {
            is SpecType.Single.FromUser -> {
                GenerateRulesForAssertTautologyChecks.generate(rule) + GenerateRulesForAssertionStructureChecks.generate(
                    rule
                )
            }

            is SpecType.Single.InvariantCheck -> {
                GenerateRulesForTrivialInvariantCheck.generate(rule)
            }

            else -> {
                emptyList()
            }
        }
    }

    /**
     * Extracts all commands of a given type inside [rule].
     */
    protected inline fun <reified T: CVLCmd> allCmdsByType(rule: CVLSingleRule) = mutableListOf<T>().also { cmdsSet ->
        rule.block.forEach { cvlCmd ->
            cmdsSet.addAll(cvlCmd.getCmdsByType())
        }
    }

    /**
     * Pass through the CVL commands in [rule] and generate a list of CVL commands from the rule that lead
     * to [assertCmd]. The [CVLCmd.Simple] commands are optionally replaced (in the return list) by the results from
     * applying the [curator]. The [curator] lambda allows callers to add custom content to the list of CVL commands
     * by inserting new commands, multiple asserts, or some other strategy relevant to the check.
     * curateCommands should correctly handle the case where the [assertCmd] lives inside a nested
     * set of scopes.
     */
    protected fun curateCommands(
        rule: CVLSingleRule,
        assertCmd: CVLCmd.Simple.Assert,
        curator: (CVLCmd.Simple) -> CVLCmd
    ): List<CVLCmd> {

        val branchScopes: List<CVLScope.Item.BranchCmdScopeItem> =
                assertCmd.scope.scopeStackItems<CVLScope.Item.BranchCmdScopeItem>()


        /* To track how many branch scopes we have seen so far. Thus, we will be able to detect whether branch
        scope was missed */
        var numOfBranchScope = branchScopes.size

        /**
         * Recursively traverse of [listOfCmds] to look for [assertCmd].
         * During the traverse, the method creates a new block of commands suitable
         * for use in a new rule.
         * Returns the generated block of commands in the current scope.
         */
        fun curateCommandsRecursively(
            listOfCmds: List<CVLCmd>,
            assertCmd: CVLCmd.Simple.Assert
        ): MutableList<CVLCmd> =
            mutableListOf<CVLCmd>().also { blockOfNewCmds ->
                listOfCmds.forEach { cmd ->
                    when (cmd) {
                        is CVLCmd.Simple -> {
                            curator(cmd).also { blockOfNewCmds.add(it) }
                        }

                        is CVLCmd.Composite.Block -> {
                            blockOfNewCmds.add(cmd.copy(block = curateCommandsRecursively(cmd.block, assertCmd)))
                        }

                        is CVLCmd.Composite.If -> {
                            val matchBranch = branchScopes.filter { it.cmd.range == cmd.range }
                            // [assertCmd] is not nested in the current if command

                            if (matchBranch.isEmpty()) {
                                val commandsFromThen = curateCommandsRecursively(listOf(cmd.thenCmd), assertCmd)
                                val commandsFromElse = curateCommandsRecursively(listOf(cmd.elseCmd), assertCmd)
                                blockOfNewCmds.add(
                                    cmd.copy(
                                        thenCmd = CVLCmd.Composite.Block(
                                            cmd.thenCmd.range,
                                            commandsFromThen,
                                            cmd.thenCmd.scope
                                        ),
                                        elseCmd = CVLCmd.Composite.Block(
                                            cmd.elseCmd.range,
                                            commandsFromElse,
                                            cmd.elseCmd.scope
                                        )
                                    )
                                )
                            } else {
                                val onlyMatchedBranch = matchBranch.singleOrNull()
                                    ?: throw IllegalStateException("Expected to have only one match for branch")
                                numOfBranchScope--
                                when (onlyMatchedBranch) {
                                    /* [assertCmd] is in the then branch generates:
                                      if (true) {
                                          curatedResult on require(cmd.cond)
                                          recursiveResult on then branch
                                      } else {
                                          assume(true)
                                      } */
                                    is CVLScope.Item.BranchCmdScopeItem.IfCmdThenScopeItem -> {
                                        val requireIfCondition = CVLCmd.Simple.AssumeCmd.Assume(
                                            range = cmd.range,
                                            exp = cmd.cond,
                                            scope = cmd.scope
                                        )
                                        val commandsFromThen = curateCommandsRecursively(
                                            if (cmd.thenCmd is CVLCmd.Composite.Block) {
                                                (cmd.thenCmd as CVLCmd.Composite.Block).block
                                            } else {
                                                listOf(cmd.thenCmd)
                                            }, assertCmd
                                        )
                                        blockOfNewCmds.add(
                                            cmd.copy(
                                                cond = CVLExp.Constant.BoolLit(
                                                    true,
                                                    CVLExpTag(
                                                        cmd.scope,
                                                        CVLType.PureCVLType.Primitive.Bool,
                                                        cmd.cond.getRangeOrEmpty()
                                                    )
                                                ),
                                                thenCmd = CVLCmd.Composite.Block(
                                                    range = cmd.thenCmd.range,
                                                    scope = cmd.thenCmd.scope,
                                                    block = mutableListOf<CVLCmd>().also { thenBlock ->
                                                        curator(requireIfCondition).also { thenBlock.add(it) }
                                                        thenBlock.addAll(commandsFromThen)
                                                    }
                                                ),
                                                elseCmd = CVLCmd.Composite.Block(
                                                    cmd.elseCmd.range,
                                                    listOf(
                                                        CVLCmd.Simple.AssumeCmd.Assume(
                                                            Range.Empty("autogenerated for sanity check"),
                                                            CVLExp.Constant.BoolLit(
                                                                true,
                                                                CVLExpTag(
                                                                    cmd.elseCmd.scope,
                                                                    CVLType.PureCVLType.Primitive.Bool,
                                                                    Range.Empty(
                                                                        "autogenerated for sanity check"
                                                                    )
                                                                )
                                                            ),
                                                            cmd.elseCmd.scope
                                                        )
                                                    ),
                                                    cmd.elseCmd.scope
                                                )
                                            )
                                        )
                                    }
                                    /* [assertCmd] is in the else branch generates:
                                      if (false) {
                                          assume(true)
                                      } else {
                                          curatedResult on require(!cmd.cond)
                                          recursiveResult on else branch
                                      } */
                                    is CVLScope.Item.BranchCmdScopeItem.IfCmdElseScopeItem -> {
                                        val requireNotIfCondition = CVLCmd.Simple.AssumeCmd.Assume(
                                            range = cmd.range,
                                            exp = CVLExp.UnaryExp.LNotExp(
                                                cmd.cond,
                                                cmd.cond.tag
                                            ),
                                            scope = cmd.scope
                                        )
                                        val commandsFromElse = curateCommandsRecursively(
                                            if (cmd.elseCmd is CVLCmd.Composite.Block) {
                                                (cmd.elseCmd as CVLCmd.Composite.Block).block
                                            } else {
                                                listOf(cmd.elseCmd)
                                            }, assertCmd
                                        )
                                        blockOfNewCmds.add(
                                            cmd.copy(
                                                cond = CVLExp.Constant.BoolLit(
                                                    false,
                                                    CVLExpTag(
                                                        cmd.scope,
                                                        CVLType.PureCVLType.Primitive.Bool,
                                                        cmd.cond.getRangeOrEmpty()
                                                    )
                                                ),
                                                thenCmd = CVLCmd.Composite.Block(
                                                    cmd.elseCmd.range,
                                                    listOf(
                                                        CVLCmd.Simple.AssumeCmd.Assume(
                                                            Range.Empty("autogenerated for sanity check"),
                                                            CVLExp.Constant.BoolLit(
                                                                true,
                                                                CVLExpTag(
                                                                    cmd.elseCmd.scope,
                                                                    CVLType.PureCVLType.Primitive.Bool,
                                                                    Range.Empty(
                                                                        "autogenerated for sanity check"
                                                                    )
                                                                )
                                                            ),
                                                            cmd.elseCmd.scope
                                                        )
                                                    ),
                                                    cmd.elseCmd.scope
                                                ),
                                                elseCmd = CVLCmd.Composite.Block(
                                                    range = cmd.thenCmd.range,
                                                    scope = cmd.thenCmd.scope,
                                                    block = mutableListOf<CVLCmd>().also { thenBlock ->
                                                        curator(requireNotIfCondition).also { thenBlock.add(it) }
                                                        thenBlock.addAll(commandsFromElse)
                                                    }
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }

        val ret = curateCommandsRecursively(rule.block, assertCmd)
        check(numOfBranchScope == 0) {
            "One branch scope was missed and not traversed for the assert command" +
                    "$assertCmd in the rule $rule"
        }
        return ret.also {
            // to make sure the rule's last command is an assertion as required by our specification
            it.add(
                CVLCmd.Simple.Assert(
                        range = rule.range,
                    description = "sanity check for rule ${rule.declarationId} for assert $assertCmd succeeded",
                        exp = CVLExp.Constant.BoolLit(
                            true,
                            CVLExpTag(range = rule.range, scope = rule.scope, type = CVLType.PureCVLType.Primitive.Bool)
                        ),
                    scope = rule.scope
                )
            )
        }
    }


    /**
     * Object for generating assertions structure sanity check rules.
     */
    object GenerateRulesForAssertionStructureChecks : GenerateRulesForAssertions() {
        override val sanityRuleName = "assert_structure_check"

        /**
         * Creates a new rule for structure checks based on [rule] with a new list of CVLCmds in [cmds].
         */
        private fun baseToStructureRule(
            rule: CVLSingleRule,
            cmds: List<CVLCmd>,
            ruleType: SpecType.Single.GeneratedFromBasicRule,
            range: Range
        ): CVLSingleRule {
            return rule.copy(
                block = cmds,
                description = " sanity check (assertion structure) for rule ${rule.declarationId}",
                goodDescription = " sanity check (assertion structure) for rule ${rule.declarationId}, " +
                    "this rule was added because the ${DoSanityChecksForRules.option.opt} flag was set",
                ruleType = ruleType,
                range = range,
            )
        }

        /**
         * Simple container class for results from the [generateStructureBlocksForRule] function. Structure checks
         * typically generate more than one new rule from a rule with an assert. These new rules contain at least
         * one new assert with a description field that describes why the rule fails. In order to prevent searching
         * through a List<CVLCmd> to find the assert command with the description field that we need, we cache the value
         * here in the BlockForRule [assertCmd] member.
         */
        data class BlockForRule(
            val assertCmd: CVLCmd.Simple.Assert,
            val expr: CVLExp.BinaryExp,
            val blockType: BlockType,
            val block: List<CVLCmd>
        )

        enum class BlockType {
            LEFT_OPERAND_CHECK, RIGHT_OPERAND_CHECK, IFF_BOTH_FALSE, IFF_BOTH_TRUE
        }

        /**
         * Generate one or more new blocks of rule commands from the existing [rule] and assert command [cmd].
         * Use each block of commands as the command for a new sanity rule.
         */
        private fun generateStructureBlocksForRule(rule: CVLSingleRule, cmd: CVLCmd.Simple.Assert) : List<BlockForRule> {
            val blockList = mutableListOf<BlockForRule>()

            val expr = cmd.exp

            // Currently we only produce new rule blocks for assert commands with:
            // - implications
            // - double implications
            // - disjunctions.
            // In addition, we only check for tautology conditions in the top level expressions, we do not break
            // apart the expressions and then check for implications, etc...
            if (! (expr is CVLExp.BinaryExp.ImpliesExp || expr is CVLExp.BinaryExp.IffExp ||
                        expr is CVLExp.BinaryExp.LorExp)) {
                return emptyList()
            }

            // Creating assert commands based on the sub-expressions requires a CVLExprTag.
            val boolTag = CVLExpTag(cmd.scope, CVLType.PureCVLType.Primitive.Bool, cmd.range)

            fun replaceAssertCurator(newAssert: CVLCmd.Simple.Assert) : (CVLCmd.Simple) -> CVLCmd = {
                if (it is CVLCmd.Simple.Assert) {
                    if (it === cmd) {
                        newAssert
                    }
                    else {
                        /**
                         * This is done to catch tautologies which are caused by asserts which are proven
                         * before [cmd]. See Test/RuleSanityChecks/AssertionStructure/AssertionStructure.spec file for
                         * a concrete example.
                         */
                        CVLCmd.Simple.AssumeCmd.Assume(
                            range = it.range,
                            exp = it.exp,
                            scope = it.scope
                        )
                    }
                } else {
                    it
                }
            }

            when (expr) {
                // a => b case.
                is CVLExp.BinaryExp.ImpliesExp -> {
                    // For an implication a => b, create assert(!a,...)
                    val hypothesisAssert = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "'$expr' is a vacuous implication. It could be rewritten to ${
                            CVLExp.UnaryExp.LNotExp(
                                expr.l,
                                expr.tag
                            )
                        } because ${expr.l} is always false",
                        exp = CVLExp.UnaryExp.LNotExp(
                            e = expr.l,
                            tag = boolTag
                        ),
                        scope = cmd.scope
                    )
                    val hypothesisBlock = curateCommands(rule, cmd) { replaceAssertCurator(hypothesisAssert)(it) }

                    // For an implication a => b, create assert(b,...)
                    val conclusionAssert = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "conclusion `${expr.r}` is always true regardless of the hypothesis",
                        exp = expr.r,
                        scope = cmd.scope
                    )
                    val conclusionBlock = curateCommands(rule, cmd) { replaceAssertCurator(conclusionAssert)(it) }

                    if (hypothesisBlock.isNotEmpty()) {
                        blockList.add(
                            BlockForRule(
                                hypothesisAssert,
                                expr,
                                BlockType.LEFT_OPERAND_CHECK,
                                hypothesisBlock
                            )
                        )
                    }
                    if (conclusionBlock.isNotEmpty()) {
                        blockList.add(
                            BlockForRule(
                                conclusionAssert,
                                expr,
                                BlockType.RIGHT_OPERAND_CHECK,
                                conclusionBlock
                            )
                        )
                    }
                }
                // a <=> b case
                is CVLExp.BinaryExp.IffExp -> {
                    // For double implication a <=> b, create assert(!a && !b)
                    val firstAssertion = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "'$expr' could be rewritten to ${
                            CVLExp.BinaryExp.LandExp(
                                CVLExp.UnaryExp.LNotExp(expr.l, expr.tag),
                                CVLExp.UnaryExp.LNotExp(expr.r, expr.tag),
                                expr.tag
                            )
                        } because both ${expr.l} and ${expr.r} are always false",
                        exp = CVLExp.BinaryExp.LandExp(
                            l = CVLExp.UnaryExp.LNotExp(expr.l, expr.tag),
                            r = CVLExp.UnaryExp.LNotExp(expr.r, expr.tag),
                            tag = boolTag
                        ),
                        scope = cmd.scope
                    )
                    val firstBlock = curateCommands(rule, cmd) { replaceAssertCurator(firstAssertion)(it) }

                    // For double implication a <=> b, create assert(a && b)
                    val secondAssertion = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "'$expr' could be rewritten to ${
                            CVLExp.BinaryExp.LandExp(
                                expr.l,
                                expr.r,
                                expr.tag
                            )
                        } because both ${expr.l} and ${expr.r} are always true ",
                        exp = CVLExp.BinaryExp.LandExp(
                            l = expr.l,
                            r = expr.r,
                            tag = boolTag
                        ),
                        scope = cmd.scope
                    )
                    val secondBlock = curateCommands(rule, cmd) { replaceAssertCurator(secondAssertion)(it) }

                    if (firstBlock.isNotEmpty()) {
                        blockList.add(BlockForRule(firstAssertion, expr, BlockType.IFF_BOTH_FALSE, firstBlock))
                    }
                    if (secondBlock.isNotEmpty()) {
                        blockList.add(BlockForRule(secondAssertion, expr, BlockType.IFF_BOTH_TRUE, secondBlock))
                    }
                }
                // a || b case
                is CVLExp.BinaryExp.LorExp -> {
                    // For disjunction a || b, create assert(a)
                    val leftAssertion = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "the expression `${expr.l}` is always true",
                        exp = expr.l,
                        scope = cmd.scope
                    )
                    val leftBlock = curateCommands(rule, cmd) { replaceAssertCurator(leftAssertion)(it) }

                    // For disjunction a || b, create assert(b)
                    val rightAssertion = CVLCmd.Simple.Assert(
                        range = cmd.range,
                        description = "the expression `${expr.r}` is always true",
                        exp = expr.r,
                        scope = cmd.scope
                    )

                    val rightBlock = curateCommands(rule, cmd) { replaceAssertCurator(rightAssertion)(it) }

                    if (leftBlock.isNotEmpty()) {
                        blockList.add(BlockForRule(leftAssertion, expr, BlockType.LEFT_OPERAND_CHECK, leftBlock))
                    }
                    if (rightBlock.isNotEmpty()) {
                        blockList.add(BlockForRule(rightAssertion, expr, BlockType.RIGHT_OPERAND_CHECK, rightBlock))
                    }
                }
                else -> Unit
            }

            return blockList
        }

        /**
         * Generates new sanity check rules for [rule] if rule contains an assert.
         */
        override fun generate(rule: CVLSingleRule): List<CVLSingleRule> {
            require(rule.ruleType is SpecType.Single.FromUser) {
                "Can only generate assertion structure checks for \"from-user\" rules," +
                    " but got ${rule.declarationId} of type ${rule.ruleType}"
            }
            if (!isSanityLevelInScope(sanityLevel)) {
                return emptyList()
            }

            val assertCmds: List<CVLCmd.Simple.Assert> = allCmdsByType(rule)
            return mutableListOf<CVLSingleRule>().also { ret ->
                assertCmds.forEach { assertCmd ->
                    val newBlocks = generateStructureBlocksForRule(rule, assertCmd)
                    if (newBlocks.isNotEmpty()) {
                        newBlocks.forEach{ blockForRule ->
                            val generatedRuleType = when(blockForRule.blockType) {
                                BlockType.LEFT_OPERAND_CHECK -> {
                                    SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.LeftOperand(
                                        rule,
                                        blockForRule.assertCmd,
                                        blockForRule.expr
                                    )
                                }
                                BlockType.RIGHT_OPERAND_CHECK -> {
                                    SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.RightOperand(
                                        rule,
                                        blockForRule.assertCmd,
                                        blockForRule.expr
                                    )
                                }
                                BlockType.IFF_BOTH_FALSE -> {
                                    SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothFalse(
                                        rule,
                                        blockForRule.assertCmd,
                                        blockForRule.expr
                                    )
                                }
                                BlockType.IFF_BOTH_TRUE -> {
                                    SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothTrue(
                                        rule,
                                        blockForRule.assertCmd,
                                        blockForRule.expr
                                    )
                                }
                            }
                            ret.add(
                                baseToStructureRule(
                                    rule,
                                    blockForRule.block,
                                    generatedRuleType,
                                    assertCmd.range,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    object GenerateRulesForTrivialInvariantCheck : GenerateRulesForAssertions() {
        override val sanityLevel: SanityValues = SanityValues.BASIC
        override val sanityRuleName: String = "invariant_not_trivial"

        override fun generate(rule: CVLSingleRule): List<CVLSingleRule> {
            require(rule.ruleType is SpecType.Single.InvariantCheck) {
                "Can only generate trivial invariant check for an invariant rule," +
                    " but got ${rule.declarationId} of type ${rule.ruleType}"
            }
            if (!isSanityLevelInScope(sanityLevel)) {
                return emptyList()
            }

            val assertCmds: List<CVLCmd.Simple.Assert> = allCmdsByType(rule)
            // the generated rule for invariant is just an assert command with the original expression as expression inside.
            val invariantPostCond = assertCmds.singleOrNull { it.invariantPostCond } ?: throw IllegalStateException(
                "Expected to have exactly one assert command for the preserved block (i.e., post-check) of the invariant: " +
                    "$rule, but got others marked as the post condition in: ${assertCmds.filter { it.invariantPostCond }.map { it.range }}. " +
                    "(Other asserts that are not the post condition: ${assertCmds.filter { !it.invariantPostCond }.map { it.range }}.)"
            )

            return listOf(
                rule.copy(
                    block = listOf(invariantPostCond),
                    description = " sanity check (trivial invariant) for rule ${rule.declarationId}",
                    goodDescription = " sanity check (trivial invariant) for rule ${rule.declarationId}, " +
                        "this rule was added because the ${DoSanityChecksForRules.option.opt} flag was set",
                    ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.TrivialInvariantCheck(
                        rule,
                        invariantPostCond
                    ),
                )
            )
        }

    }

    object GenerateRulesForAssertTautologyChecks : GenerateRulesForAssertions() {
        override val sanityRuleName: String = "assertion_not_tautological"

        /**
         * Translates the base rule [rule] to a new rule, generated for checking vacuity of an assert command,
         * where the block property is set to be [cmds], and the ruleType is set to be [ruleType].
         * [assertCmd] the assert command that this vacuity rule will check for.
         */
        private fun baseToTautologyRule(
            rule: CVLSingleRule,
            cmds: List<CVLCmd>,
            ruleType: SpecType.Single.GeneratedFromBasicRule,
            assertCmd: CVLCmd.Simple.Assert
        ): CVLSingleRule {
            return rule.copy(
                block = cmds,
                ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier("$sanityRuleName${assertCmd.getSuffixDecId()}"),
                description = " sanity check (assert tautology) for rule ${rule.declarationId}",
                goodDescription = " sanity check (assert tautology) for rule ${rule.declarationId}, this rule was added " +
                        "because the ${DoSanityChecksForRules.option.opt} flag was set",
                ruleType = ruleType,
                range = assertCmd.range,
            )
        }

        private fun generateTautologyAssertionForRule(
            rule: CVLSingleRule,
            assertCmd: CVLCmd.Simple.Assert
        ): List<CVLCmd> {
            return curateCommands(rule, assertCmd) {
                when (it) {
                    is CVLCmd.Simple.AssumeCmd -> {
                        CVLCmd.Simple.Nop(it.range, it.scope)
                    }
                    is CVLCmd.Simple.Assert -> {
                        if (it === assertCmd) {
                            assertCmd
                        } else {
                            CVLCmd.Simple.Nop(it.range, it.scope)
                        }
                    }
                    else -> {
                        it
                    }
                }
            }
        }

        override fun generate(rule: CVLSingleRule): List<CVLSingleRule> {
            require(rule.ruleType is SpecType.Single.FromUser) {
                "Can only generate assertion tautology checks for \"from-user\" rules," +
                    " but got ${rule.declarationId} of type ${rule.ruleType}"
            }
            if (!isSanityLevelInScope(sanityLevel)) {
                return emptyList()
            }

            val assertCmds: List<CVLCmd.Simple.Assert> = allCmdsByType(rule)
            val assumeCmds: List<CVLCmd.Simple.AssumeCmd> = allCmdsByType(rule)

            /**
             * [this] is influenced by [other] if it is in its scope and appears after it.
             */
            fun CVLCmd.influencedBy(other: CVLCmd): Boolean {
                fun CVLScope.isInScope(other: CVLScope): Boolean =
                    this == other || this.innerScope?.isInScope(other) ?: false

                return this.scope.isInScope(other.scope) && other.range <= this.range
            }


            return mutableListOf<CVLSingleRule>().also { ret ->
                assertCmds.filter {
                    /**
                     * If there are no requires, if statements or other asserts in the path leading to the assert
                     * command we should not run the check.
                     */
                    it.scope.scopeStack.any { item -> item is CVLScope.Item.BranchCmdScopeItem } ||
                        assumeCmds.any { assumeCmd -> it.influencedBy(assumeCmd) } ||
                        assertCmds.any { assertCmd -> it != assertCmd && it.influencedBy(assertCmd) }
                }.forEach { assertCmd ->
                    val newBlock: List<CVLCmd> = mutableListOf<CVLCmd>().also { newB ->
                        newB.addAll(generateTautologyAssertionForRule(rule, assertCmd))
                    }
                    if (newBlock.isNotEmpty()) {
                        ret.add(
                            baseToTautologyRule(
                                rule,
                                newBlock,
                                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertTautologyCheck(
                                    rule,
                                    assertCmd
                                ),
                                assertCmd
                            )
                        )
                    }

                }
            }
        }
    }

}

/**
 * Object for generating rules for redundant requires checks
 */
object GenerateRulesForRedundantRequiresCheck : SanityRuleGenerator {

    override val sanityLevel: SanityValues = SanityValues.ADVANCED
    override val sanityRuleName: String = "require_not_redundant"

    /**
     * Generate new rule for each require(exp) in [rule] where exp is replaced by "true"
     */
    override fun generate(rule: CVLSingleRule): List<CVLSingleRule> {

        val requireTrue = CVLCmd.Simple.AssumeCmd.Assume(
            range = rule.range,
            exp = CVLExp.Constant.BoolLit(
                true,
                CVLExpTag(range = rule.range, scope = rule.scope, type = CVLType.PureCVLType.Primitive.Bool)
            ),
            scope = rule.scope
        )

        /**
         * Generates recursively all the commands obtained from [cmd] by replacing a single assume command with requireTrue.
         * This function returns list of pairs, where the first entries represent the commands that were generated
         * until now, when we roll back from the recursion, and the second entries represent the specific require command
         * the commands in the corresponding first entries were generated for.
         */
        fun generateCommand(cmd: CVLCmd): List<Pair<CVLCmd, CVLCmd.Simple.AssumeCmd>> {
            return mutableListOf<Pair<CVLCmd, CVLCmd.Simple.AssumeCmd>>().also {
                when (cmd) {
                    is CVLCmd.Simple.AssumeCmd -> {
                        // generate require(true) command for [cmd]
                        it.add(Pair(requireTrue, cmd))
                    }

                    is CVLCmd.Composite.If -> {
                        // [fromThen] holds all the sub-generated rules for all the requires we have encountered so far
                        // in the then branch
                        // similarly, [fromElse] with respect to the else branch
                        val fromThen = generateCommand(cmd.thenCmd)
                        fromThen.forEach { (newThen, assumeRemoved) ->
                            it.add(
                                Pair(cmd.copy(thenCmd = newThen), assumeRemoved)
                            )
                        }
                        /* iff there is an else branch (otherwise the tool generates else branch
                        with require(true) inside, we do not want to treat this require command) */
                        if (cmd.elseCmd !is CVLCmd.Simple.AssumeCmd) {
                            val fromElse = generateCommand(cmd.elseCmd)
                            fromElse.forEach { (newElse, assumeRemoved) ->
                                it.add(
                                    Pair(cmd.copy(elseCmd = newElse), assumeRemoved)
                                )
                            }
                        }
                    }

                    is CVLCmd.Composite.Block -> {
                        cmd.block.forEachIndexed { i, blockCmd ->
                            generateCommand(blockCmd).forEach { (newCmd, assumeRemoved) ->
                                val newBlock: MutableList<CVLCmd> = cmd.block.toMutableList()
                                newBlock[i] = newCmd
                                it.add(Pair(cmd.copy(block = newBlock), assumeRemoved))
                            }
                        }
                    }

                    else -> {} // simple non-assume command case
                }
            }
        }
        if (!isSanityLevelInScope(sanityLevel)) {
            return emptyList()
        }

        return mutableListOf<CVLSingleRule>().also { rulesList ->
            rule.block.forEachIndexed { i, cmd ->
                generateCommand(cmd).forEach { (newCmd, assumeRemoved) ->
                    val newBlock: MutableList<CVLCmd> = rule.block.toMutableList()
                    newBlock[i] = newCmd
                    rulesList.add(
                        rule.copy(
                            block = newBlock,
                            description = "require redundancy check for ${cmd.toPrintString()}",
                            ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.RedundantRequireCheck(
                                rule,
                                assumeRemoved
                            ),
                            range = assumeRemoved.range,
                        )
                    )
                }
            }
        }
    }
}
