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

package move

import analysis.*
import bridge.*
import config.*
import datastructures.stdcollections.*
import diagnostics.*
import event.RuleEvent
import java.io.Closeable
import kotlin.io.path.*
import log.*
import parallel.coroutines.*
import report.*
import rules.*
import rules.CompiledRule.Companion.mapCheckResult
import scene.*
import scene.source.*
import spec.cvlast.*
import spec.cvlast.typechecker.*
import spec.genericrulegenerators.*
import spec.rules.*
import tac.*
import utils.*
import vc.data.*
import verifier.*
import verifier.mus.*

class MoveVerifier : Closeable {
    private val modulePath = Config.MoveModulePath.get()
    private val moveScene = MoveScene(Path(modulePath))
    private val cvlScene = SceneFactory.getScene(DegenerateContractSource(modulePath))
    private val reporterContainer = ReporterContainer(listOf(ConsoleReporter))
    private val treeView = TreeViewReporter("MoveMainProgram", "", cvlScene)

    override fun close() {
        treeView.close()
    }

    /**
        The entrypoint for verification of Move projects
    */
    suspend fun verify() {
        /*
            The Move prover currently does not support precise bitwise operation mode, due to all of the [Tag.Int] math
            done in [MemoryLayout].  If we need this in the future, we will also need to update [MemoryLayout] to
            constrain the maximum composed layout size to fit in 256 bits.  This will require (among other things) a
            different "ghost array" implementation.

            For now, we simply fail if this is enabled.
         */
        if (Config.Smt.UseBV.get()) {
            throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Precise bitwise operations are not supported for Move verification."
            )
        }

        val concurrencyLimit = Config.MaxConcurrentRules.get()

        val rules = moveScene
            .getSelectedCvlmRules()
            .resultOrExitProcess(1, CVLError::printErrors)
            .sortedBy { it.ruleInstanceName }
            .parallelMapOrdered(concurrencyLimit) { _, rule ->
                annotateCallStack("rule.${rule.ruleInstanceName}") {
                    val compiled = MoveToTAC.compileRule(rule, moveScene)
                    EcosystemAgnosticRule(
                        ruleIdentifier = RuleIdentifier.freshIdentifier(compiled.rule.ruleInstanceName),
                        ruleType = when (compiled.rule) {
                            is CvlmRule.UserRule -> SpecType.Single.FromUser.SpecFile
                            is CvlmRule.TargetSanity -> SpecType.Single.BuiltIn(BuiltInRuleId.sanity)
                        },
                        isSatisfyRule = compiled.isSatisfy
                    ) to compiled
                }
            }.let {
                expandMultiAsserts(it)
            }

        treeView.buildRuleTree(rules.map { (rule, _) -> rule })

        rules.consuming().parallelMapOrdered(concurrencyLimit) { _, (rule, compiled) ->
            annotateCallStackAsync("rule.${rule.ruleIdentifier.displayName}") {
                treeView.signalStart(rule)

                val coretac = compiled.toCoreTAC(moveScene)

                @OptIn(Config.DestructiveOptimizationsOption::class)
                val res = when (Config.DestructiveOptimizationsMode.get()) {
                    DestructiveOptimizationsModeEnum.DISABLE -> {
                        verifyTAC(rule, coretac)
                    }
                    DestructiveOptimizationsModeEnum.ENABLE -> {
                        verifyTAC(rule, coretac.withDestructiveOptimizations(true))
                    }
                    DestructiveOptimizationsModeEnum.TWOSTAGE, DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> {
                        twoStageDestructiveOptimizationsCheck(rule, rule.ruleIdentifier.displayName, coretac) {
                            verifyTAC(rule, it)
                        }
                    }
                }

                res.toCheckResult(cvlScene, rule).mapCatching { checkResult ->
                    reporterContainer.addResults(checkResult)
                    treeView.signalEnd(rule, checkResult)
                    reporterContainer.hotUpdate(cvlScene)
                }
            }
        }

        reporterContainer.toFile(cvlScene)
    }

    suspend fun verifyTAC(rule: EcosystemAgnosticRule, tac: CoreTACProgram): CompiledRule.CompileRuleCheckResult {
        return runCatching {
            val startTime = System.currentTimeMillis()
            val optimized = MoveToTAC.optimize(tac)
            val joinedResult = Verifier.JoinedResult(
                TACVerifier.verify(cvlScene, optimized, treeView.liveStatsReporter, rule)
            )
            val endTime = System.currentTimeMillis()
            ResultAndTime(joinedResult, VerifyTime.WithInterval(startTime, endTime))
        }.mapCheckResult(
            ruleName = rule.ruleIdentifier.displayName,
            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
        )
    }

    /** Replace in [code] all assertion commands with assume commands except the one with ASSERT_ID=[assertId] **/
    private fun replaceAssertWithAssumeExcept(
        code: MoveTACProgram,
        assertId: Int,
        idToPtr: Map<Int, GenericLTACCmdView<TACCmd.Simple.AssertCmd>>,
    ): MoveTACProgram {
        val p = code.toPatchingProgram()

        idToPtr.forEachEntry { (id, lcmd) ->
            if (id != assertId) {
                p.update(
                    lcmd.ptr,
                    TACCmd.Simple.AssumeCmd(lcmd.cmd.o, "replaced assert: ${lcmd.cmd.msg}", lcmd.cmd.meta)
                )
            }
        }

        return p.toCode(code)
    }

    /**
        For a given rule [compiledRule] with N assert commands, return N new rules where each rule has exactly one
        assert.
     */
    private fun transformMulti(
        rule: EcosystemAgnosticRule,
        compiled: MoveToTAC.CompiledRule
    ): List<Pair<EcosystemAgnosticRule, MoveToTAC.CompiledRule>> {
        val code = compiled.moveTAC

        // Get all assertions by ID
        val idToPtr = buildMap {
            code.graph.commands.forEach {
                it.maybeNarrow<TACCmd.Simple.AssertCmd, _>()?.let { assertPtr ->
                    assertPtr.cmd.meta[TACMeta.ASSERT_ID]?.let { id ->
                        check(id >= 0) {
                            "In ${code.name}, did not instantiate assert ID at $assertPtr"
                        }
                        val other = put(id, assertPtr)
                        check(other == null) {
                            "In ${code.name}, multiple assertions with ID $id at $other and $assertPtr"
                        }
                    }
                }
            }
        }

        check(idToPtr.isNotEmpty()) { "No user asserts found in ${code.name}" }

        return idToPtr.map { (assertId, assertPtr) ->
            val newRuleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertSpecFile(
                rule,
                assertId,
                assertPtr.cmd.msg,
                assertPtr.cmd.meta[TACMeta.CVL_RANGE] ?: Range.Empty()
            )

            val newRule = rule.copy(
                ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier("#assert_$assertId"),
                ruleType = newRuleType,
                range = newRuleType.cvlCmdLoc,
            )

            val newCode = replaceAssertWithAssumeExcept(
                code,
                assertId,
                idToPtr
            ).copy(name = "${code.name}-assert${assertId}")

            newRule to compiled.copy(moveTAC = newCode)
        }
    }

    private fun expandMultiAsserts(
        rules: List<Pair<EcosystemAgnosticRule, MoveToTAC.CompiledRule>>
    ): List<Pair<EcosystemAgnosticRule, MoveToTAC.CompiledRule>> {
        return rules.flatMap { (rule, compiled) ->
            when {
                rule.isSatisfyRule -> listOf(rule to compiled)
                Config.MultiAssertCheck.get() -> transformMulti(rule, compiled)
                else -> listOf(
                    rule.copy(
                        ruleIdentifier = rule.ruleIdentifier.freshDerivedIdentifier(RuleEvent.ASSERTS_NODE_TITLE),
                        ruleType = SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.AssertsOnly(rule),
                    ) to compiled
                )
            }
        }
    }
}
