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

import datastructures.stdcollections.*
import utils.*

sealed class CvlmRule {
    abstract val ruleName: String
    abstract val ruleInstanceName: String
    abstract val parametricTargetNames: Set<String>

    /** An instantiation of a (possibly parametric) user rule */
    class UserRule(
        val entry: MoveFunction,
        val parametricTargets: Map<Int, MoveFunction>
    ) : CvlmRule() {
        override val ruleName get() = entry.name.toString()
        override val ruleInstanceName get() =
            (listOf(ruleName) + parametricTargets.values.map { it.name }).joinToString("-")
        override val parametricTargetNames get() = parametricTargets.values.mapToSet { it.name.toString() }
    }

    /** Target sanity rules */
    sealed class TargetSanity : CvlmRule() {
        abstract val target: MoveFunction
        override val ruleInstanceName get() = ruleName
        override val parametricTargetNames get() = setOf<String>()

        class AssertTrue(override val target: MoveFunction) : TargetSanity() {
            override val ruleName get() = "${target.name}-Assertions"
        }
        class SatisfyTrue(override val target: MoveFunction) : TargetSanity() {
            override val ruleName get() = "${target.name}-Reached end of function"
        }
    }

    companion object {
        context(MoveScene)
        fun loadAll(): List<CvlmRule> = cvlmManifest.rules.entries.flatMap { (ruleFuncName, manifestRuleType) ->
            with (Instantiator()) {
                when (manifestRuleType) {
                    CvlmManifest.RuleType.USER_RULE -> {
                        val ruleFunc = instantiate(ruleFuncName)
                        targetPermutations(ruleFunc).map { targets ->
                            UserRule(ruleFunc, targets)
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
