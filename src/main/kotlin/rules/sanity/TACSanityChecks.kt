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

import analysis.CommandWithRequiredDecls
import analysis.maybeNarrow
import cli.SanityValues
import config.Config.DoSanityChecksForRules
import datastructures.stdcollections.*
import parallel.coroutines.parallelMapOrdered
import report.ConsoleReporter
import report.StatusReporter
import report.TreeViewReporter
import rules.CompiledRule
import rules.IsFromCache
import rules.VerifyTime
import scene.IScene
import solver.SolverResult
import spec.cvlast.*
import spec.rules.IRule
import spec.rules.EcosystemAgnosticRule
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACSymbol
import verifier.TACVerifier
import verifier.Verifier

/**
 * This class holds a set of sanity checks that are executed on the TAC level.
 *
 * As of now, only a Vacuity Check is implemented, we expected more checks to be added later.
 */
object TACSanityChecks {

    private val registeredChecks = listOf(VacuityCheck)

    suspend fun analyse(scene: IScene, baseRule: EcosystemAgnosticRule, baseRuleTac: CoreTACProgram, baseRuleResult: Verifier.VerifierResult, treeViewReporter: TreeViewReporter) {
        registeredChecks.filter { DoSanityChecksForRules.get() >= it.sanityLevel }.parallelMapOrdered { _, sanityCheck ->
            sanityCheck.analyse(scene, baseRule, baseRuleTac, baseRuleResult, treeViewReporter)
        }
    }

    /**
     * Abstract class that implements basic logic for a sanity check. Additional sanity checks for TAC should
     * subclass this class.
     */
    abstract class SanityCheck : ISanityRule {
        /**
         * This method transforms the TAC as required by the implemented sanity check.
         *
         * @param baseRuleTac The produced tac for the original base rule.
         * @param baseRule The original base rule that has been transformed into TAC.
         * Please note that in the case of Solana / WASM / TAC flow, this is only a dummy rule
         * that is created to have meta information required along the pipeline.
         * (see [spec.cvlast.IRule.Companion.createDummyRule])
         *
         * See [VacuityCheck] as an example.
         */
        abstract fun transformTac(baseRuleTac: CoreTACProgram, baseRule: EcosystemAgnosticRule): CoreTACProgram

        /**
         * Each sanity check must derive a copy from the base rule and add meta information
         * It's required that the derived rule has a new `ruleIdentifier` and that the `ruleType` is updated to
         * a SpecType.Single.GeneratedFromBasicRule. This is required for the pipeline to further succeed and
         * display all data accordingly.
         *
         * See [VacuityCheck] as an example.
         */
        abstract fun deriveRule(baseRule: EcosystemAgnosticRule): IRule

        /**
         * A pre-condition if this sanity check should actually be executed or not.
         *
         * @param baseRule The original base rule that was proven.
         * @param baseRuleResult the verification result of the base rule. The sanity check may
         * be executed or not based on the verification result of the base rule. For instance,
         * the vacuity check is only execute if the original base rule has been proven.
         */
        abstract fun shouldExecute(baseRule: EcosystemAgnosticRule, baseRuleResult: Verifier.VerifierResult): Boolean

        /**
         * This method registers the rule in our different reporters, computes the TAC for the sanity check by
         * calling [transformTac] and submits the resulting TAC to SMT via [TACVerifier.verify].
         *
         * @param scene The scene of the entire analysis
         * @param baseRule The original base rule that was proven
         * @param baseRuleTac The TAC that was proven for the original base rule
         * @param baseRuleResult The prover result of the base rule
         * @param treeViewReporter The tree view reporter instance which is required to create new children in the reporting tree.
         *
         *
         * Once this process has been completed, all reporters are updated.
         */
        suspend fun analyse(scene: IScene, baseRule: EcosystemAgnosticRule, baseRuleTac: CoreTACProgram, baseRuleResult: Verifier.VerifierResult, treeViewReporter: TreeViewReporter) {
            if (!shouldExecute(baseRule, baseRuleResult)) {
                return
            }
            val sanityRule = deriveRule(baseRule)
            check(baseRule.ruleIdentifier != sanityRule.ruleIdentifier) { "It's required that the derived rule receives a new ruleIdentifier as the sanity rule will be resubmitted to the solver." }

            treeViewReporter.registerSubruleOf(sanityRule, baseRule)
            treeViewReporter.signalStart(sanityRule)

            val transformedTac = transformTac(baseRuleTac, baseRule)
            val startTime = System.currentTimeMillis()
            val res = TACVerifier.verify(scene, transformedTac, treeViewReporter.liveStatsReporter, sanityRule)
            val endTime = System.currentTimeMillis()

            val ruleCheckResult = CompiledRule.generateSingleResult(
                scene = scene,
                rule = sanityRule,
                vResult = Verifier.JoinedResult(res),
                verifyTime = VerifyTime.WithInterval(startTime, endTime),
                isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                ruleAlerts = emptyList(),
            )

            treeViewReporter.signalEnd(sanityRule, ruleCheckResult)
            ConsoleReporter.addResults(ruleCheckResult)
            StatusReporter.addResults(ruleCheckResult)
        }

    }

    /**
     * A rudimentary sanity check that is analog to [spec.cvlast.SpecType.Single.GeneratedFromBasicRule.VacuityCheck], i.e.,
     * we remove all asserts and add an `assert false` for all sinks at the CFG.
     *
     * [spec.cvlast.SpecType.Single.GeneratedFromBasicRule.VacuityCheck] transforms CVL, this check transforms the actual TAC.
     */
    object VacuityCheck : SanityCheck() {

        /**
         * The name in the web UI for the child node in the rule report tree. The name is the same as with the CVL based
         * vacuity check [spec.cvlast.SpecType.Single.GeneratedFromBasicRule.VacuityCheck]
         */
        override val sanityRuleName: String = "rule_not_vacuous_tac"

        override val sanityLevel: SanityValues = SanityValues.ADVANCED

        /**
         * The sanity rules will only be executed if the original results of the base rule did not find a counter example.
         *
         * For a non-satisfy rules, sanity is executed when the original results was UNSAT.
         * For a satisfy rule, sanity is executed when the original result was SAT.
         */
        override fun shouldExecute(baseRule: EcosystemAgnosticRule, baseRuleResult: Verifier.VerifierResult) = !baseRule.isSatisfyRule && baseRuleResult.finalResult == SolverResult.UNSAT || baseRule.isSatisfyRule && baseRuleResult.finalResult == SolverResult.SAT
        override fun transformTac(baseRuleTac: CoreTACProgram, baseRule: EcosystemAgnosticRule): CoreTACProgram {
            return baseRuleTac.patching { p ->
                // Replace all assert commands
                this.parallelLtacStream()
                    .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
                    .filter {
                        // We only remove asserts that were added by the user. These have been assigned
                        // either a SATISfY_ID or an ASSERT_ID.
                        // We explicitly don't want to remove asserts that have been internally added
                        // by the Prover, for instance as part of an [vc.data.SnippetCmd.EVMSnippetCmd.LoopSnippet.AssertUnwindCond].
                        it.cmd.meta.containsKey(TACMeta.ASSERT_ID) || it.cmd.meta.containsKey(TACMeta.SATISFY_ID)
                    }
                    .forEach {
                        p.replace(it.ptr) { _ -> listOf() }
                    }
            }.appendToSinks(
                CommandWithRequiredDecls<TACCmd.Simple>(
                    listOf(
                        TACCmd.Simple.AssertCmd(
                            TACSymbol.False,
                            "sanity check for rule ${baseRule.declarationId} succeeded",
                        )
                    )
                ))
        }

        override fun deriveRule(baseRule: EcosystemAgnosticRule) =
            baseRule.copy(
                ruleIdentifier = baseRule.ruleIdentifier.freshDerivedIdentifier(sanityRuleName),
                ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(baseRule),
            )
    }
}