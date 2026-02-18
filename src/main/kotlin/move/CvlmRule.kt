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

import config.*
import datastructures.*
import datastructures.stdcollections.*
import spec.cvlast.RuleIdentifier
import spec.cvlast.SpecType
import spec.genericrulegenerators.BuiltInRuleId
import spec.rules.EcosystemAgnosticRule
import tac.generation.TrapMode
import utils.*

sealed class CvlmRule {
    abstract val ruleName: String
    abstract val ruleInstanceName: String
    abstract val parametricTargetNames: Set<String>
    abstract val trapMode: TrapMode

    abstract fun toEcosystemAgnosticRule(isSatisfy: Boolean): EcosystemAgnosticRule

    /** An instantiation of a (possibly parametric) user rule */
    class UserRule(
        val entry: MoveFunction,
        val parametricTargets: Map<Int, MoveFunction>,
        override val trapMode: TrapMode
    ) : CvlmRule() {
        override val ruleName get() = entry.name.toString()
        override val ruleInstanceName get() =
            (listOf(ruleName) + parametricTargets.values.map { it.name }).joinToString("-")
        override val parametricTargetNames get() = parametricTargets.values.mapToSet { it.name.toString() }

        override fun toEcosystemAgnosticRule(isSatisfy: Boolean): EcosystemAgnosticRule {
            val baseRule = EcosystemAgnosticRule(
                ruleIdentifier = ruleIdentifiers(null, ruleName),
                ruleType = SpecType.Single.FromUser.SpecFile,
                isSatisfyRule = isSatisfy
            )
            return parametricTargets.entries.fold(baseRule) { parent, (_, target) ->
                EcosystemAgnosticRule(
                    ruleIdentifier = ruleIdentifiers(parent.ruleIdentifier, target.name.toString()),
                    ruleType = SpecType.Single.GeneratedFromBasicRule.ParametricRuleInstantiation(parent),
                    isSatisfyRule = isSatisfy
                )
            }
        }
    }

    /** Target sanity rules */
    sealed class TargetSanity : CvlmRule() {
        abstract val target: MoveFunction
        abstract val identifierSuffix: String
        override val ruleName get() = target.name.toString()
        override val ruleInstanceName get() = "$ruleName-$identifierSuffix"
        override val parametricTargetNames get() = setOf<String>()
        override val trapMode get() = TrapMode.DEFAULT

        class AssertTrue(override val target: MoveFunction) : TargetSanity() {
            override val identifierSuffix get() = "Assertions"
        }
        class SatisfyTrue(override val target: MoveFunction) : TargetSanity() {
            override val identifierSuffix get() = "Reached end of function"
        }

        override fun toEcosystemAgnosticRule(isSatisfy: Boolean): EcosystemAgnosticRule {
            return EcosystemAgnosticRule(
                ruleIdentifier = ruleIdentifiers(null, ruleInstanceName),
                ruleType = SpecType.Single.BuiltIn(BuiltInRuleId.sanity),
                isSatisfyRule = isSatisfy
            )
        }
    }

    companion object {
        private val ruleIdentifiers = memoized { parent: RuleIdentifier?, name: String ->
            if (parent == null) {
                RuleIdentifier.freshIdentifier(name)
            } else {
                parent.freshDerivedIdentifier(name)
            }
        }

        context(MoveScene)
        fun loadAll(): List<CvlmRule> = allRules().flatMap { (ruleFuncName, manifestRuleType) ->
            with (Instantiator()) {
                when (manifestRuleType) {
                    CvlmManifest.RuleType.USER_RULE,
                    CvlmManifest.RuleType.USER_RULE_NO_ABORT -> {
                        val ruleFunc = instantiate(ruleFuncName)
                        targetPermutations(ruleFunc).map { targets ->
                            UserRule(
                                ruleFunc,
                                targets,
                                trapMode = when (manifestRuleType) {
                                    CvlmManifest.RuleType.USER_RULE -> TrapMode.DEFAULT
                                    CvlmManifest.RuleType.USER_RULE_NO_ABORT -> TrapMode.ASSERT
                                    CvlmManifest.RuleType.SANITY -> `impossible!`
                                }
                            )
                        }
                    }
                    CvlmManifest.RuleType.SANITY -> {
                        val targetFunc = instantiate(ruleFuncName)
                        listOf(
                            TargetSanity.AssertTrue(targetFunc),
                            TargetSanity.SatisfyTrue(targetFunc)
                        )
                    }
                }
            }
        }

        context(MoveScene)
        fun allRules(): List<Pair<MoveFunctionName, CvlmManifest.RuleType>> {
            // In "public sanity" mode, we ignore any manifest and just produce sanity checks for all public functions
            return if (Config.MovePublicSanityCheck.get()) {
                if (!Config.areRulesFiltered()) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "When using -publicSanity, you must also use -rule or -excludeRule to filter the functions to check."
                    )
                }
                modules.flatMap { moduleName ->
                    module(moduleName).publicFunctionDefinitions.map {
                        it.function.name to CvlmManifest.RuleType.SANITY
                    }
                }
            } else {
                cvlmManifest.rules.entries.map { it.key to it.value }
            }
        }

        /**
            Instantiates generic functions, using [MoveType.Nondet] as the type arguments.  Each type argument receives
            a unique [MoveType.Nondet] ID.
         */
        private class Instantiator {
            private var nextNondetTypeId = 0

            context(MoveScene)
            fun instantiate(func: MoveFunctionName): MoveFunction {
                val def = maybeDefinition(func)
                    ?: error("No definition found for function $func")
                return MoveFunction(
                    def.function,
                    typeArguments = def.function.typeParameters.map { MoveType.Nondet(nextNondetTypeId++) }
                )
            }
        }

        /** Produces all permutations of target functions for possibly-parametric rule [ruleFunc] */
        context(Instantiator, MoveScene)
        private fun targetPermutations(ruleFunc: MoveFunction): List<Map<Int, MoveFunction>> {
            val positions = ruleFunc.params.mapIndexedNotNull { i, param -> i.takeIf { param is MoveType.Function } }
            val targets = targetFunctions(ruleFunc.name.module).map { instantiate(it) }
            if (positions.isNotEmpty() && targets.isEmpty()) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "No target functions for parametric rule ${ruleFunc.name}.  Add targets to the module manifest."
                )
            }
            return targetPermutations(positions, targets).toList()
        }

        private fun targetPermutations(
            positions: List<Int>,
            targets: List<MoveFunction>
        ): Sequence<Map<Int, MoveFunction>> {
            if (positions.isEmpty()) {
                return sequenceOf(emptyMap())
            }
            return targetPermutations(positions.drop(1), targets).flatMap { map ->
                targets.asSequence().map { target ->
                    map + (positions.first() to target)
                }
            }
        }
    }
}
