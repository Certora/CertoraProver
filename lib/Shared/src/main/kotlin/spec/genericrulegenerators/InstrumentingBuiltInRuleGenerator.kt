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

package spec.genericrulegenerators

import bridge.ContractInstanceInSDC
import datastructures.stdcollections.*
import log.*
import spec.CVLKeywords
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.rules.CVLSingleRule
import spec.rules.SingleRuleGenerationMeta
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import utils.Range
import utils.VoidResult

private val logger = Logger(LoggerTypes.GENERIC_RULE)

enum class BuiltInRuleSinkType {
    ASSERT_TRUE,
    // applies to sanity, but not deep-sanity as it is manually instrumented and instrumenting tac with satisfies doesn't work well
    // the `assert true` means we also generate a rule that checks any pessimistic assertions
    ASSERT_TRUE_SATISFY_TRUE,
    NOP,
}

/**
 * Generates a rule with formal parameters f, fEnv, fCallData.
 * The rule calls f(fEnv, fCalldataarg)
 * An assert(true) is added at the end because there must be an assert in the rule.
 */
sealed class InstrumentingBuiltInRuleGenerator: BuiltInRuleGenerator() {

    /**
     * Gets the relevant method-param filter for the builtin.
     * (This is assuming instrumenting-builtin rules only take 1 parametric method.)
     */
    abstract fun getMethodParamFilters(range: Range, scope: CVLScope, symbolicFunctionName: String): MethodParamFilters

    /**
     * A function that a generator can run as part of typechecking to make sure generation
     * of the rule is possible.
     * Returns a [CVLError] if there is an error and null if the check is successful.
     */
    abstract fun checkIfCanGenerate(range: Range): VoidResult<CVLError>

    /**
     * Determines what the final command will be.
     * Default is [BuiltInRuleSinkType.ASSERT_TRUE] - put a dummy assert.
     */
    open val sinkType = BuiltInRuleSinkType.ASSERT_TRUE

    /**
     * Determines whether the parametric methods in the generated rule will be parametric over
     * all methods from all the contracts in the scene or only the methods from the primary
     * contract.
     */
    open val allSceneFunctions = true

    /**
     * Determines whether the parametric call will be called with `@withrevert` or not
     */
    open val noRevert = true

    @Suppress("ConstPropertyName")
    object ParamNames {
        const val symbolicFunction = "f"
        const val symbolicCallDataArg = "fcalldataarg"
        const val symbolicEnv = "fenv"
    }

    /**
     * Generates the arguments for CVLCmd.Simple.contractFunction
     */
    private fun functionParams() = listOf(
        CVLParam(EVMBuiltinTypes.method, ParamNames.symbolicFunction, Range.Empty()),
        CVLParam(EVMBuiltinTypes.env, ParamNames.symbolicEnv, Range.Empty()),
        CVLParam(CVLType.PureCVLType.VMInternal.RawArgs, ParamNames.symbolicCallDataArg, Range.Empty())
    )

    override fun doGenerate(
        scope: CVLScope,
        range: Range,
        mainContract: String,
        functionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>
    ): CollectingResult<CVLSingleRule,CVLError> {
        checkIfCanGenerate(range).errorOrNull()?.let { return it.asError() }

        logger.debug { "Start generating builtin rule $eId" }

        return scope.extendIn(CVLScope.Item::RuleScopeItem) { ruleScope ->
            withScopeAndRange(ruleScope, range) {
                val arguments = listOf(
                    CVLExp.VariableExp(
                        ParamNames.symbolicEnv,
                        EVMBuiltinTypes.env.asTag()
                    ),
                    CVLExp.VariableExp(
                        ParamNames.symbolicCallDataArg,
                        CVLType.PureCVLType.VMInternal.RawArgs.asTag()
                    )
                )
                val block = listOf<CVLCmd>(
                    CVLCmd.Simple.contractFunction(
                        range,
                        ruleScope,
                        ParametricMethod(
                            ParamNames.symbolicFunction,
                            host = if(allSceneFunctions) {
                                AllContracts
                            } else {
                                SolidityContract(mainContract)
                            }
                        ),
                        expList = arguments,
                        noRevert,
                        CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, CVLKeywords.lastStorage.type.asTag()),
                        false,
                        isParametric = true, // method is a parameter
                        methodParamFilter = null,
                    )
                ) + when (sinkType) {
                        BuiltInRuleSinkType.ASSERT_TRUE -> {
                            listOf(
                                CVLCmd.Simple.Assert(
                                    range,
                                    CVLExp.Constant.BoolLit(true, CVLType.PureCVLType.Primitive.Bool.asTag()),
                                    "Dummy so last statement will be an assert",
                                    ruleScope
                                )
                            )
                        }
                        BuiltInRuleSinkType.ASSERT_TRUE_SATISFY_TRUE -> {
                            listOf(
                                CVLCmd.Simple.Assert(
                                    range,
                                    CVLExp.Constant.BoolLit(true, CVLType.PureCVLType.Primitive.Bool.asTag()),
                                    "Checking auto-generated assertions",
                                    ruleScope
                                ),
                                CVLCmd.Simple.Satisfy(
                                    range,
                                    CVLExp.Constant.BoolLit(true, CVLType.PureCVLType.Primitive.Bool.asTag()),
                                    "Reaching end of method's code",
                                    ruleScope
                                ),
                            )
                        }
                        BuiltInRuleSinkType.NOP -> listOf(CVLCmd.Simple.Nop(range, ruleScope))
                    }

                val filters = getMethodParamFilters(range, ruleScope, ParamNames.symbolicFunction)

                CVLSingleRule(
                    ruleIdentifier = RuleIdentifier.freshIdentifier(eId.name),
                    range = range,
                    ruleType = birType,
                    params = functionParams(),
                    block = block,
                    scope = ruleScope,
                    methodParamFilters = filters,
                    description = "",
                    goodDescription = "",
                    ruleGenerationMeta = SingleRuleGenerationMeta.Empty
                )
            }
        }.lift()
    }
}