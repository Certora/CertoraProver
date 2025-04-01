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
import bridge.EVMExternalMethodInfo.Companion.selectorField
import config.Config
import datastructures.stdcollections.*
import report.CVTAlertType
import spec.CVLKeywords
import spec.CVLWarningLogger
import spec.CalculateMethodParamFilters.Companion.containsMethod
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.NoFoundryTestsLeft
import spec.cvlast.typedescriptors.ToVMContext
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule
import spec.rules.SingleRuleGenerationMeta
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import java.math.BigInteger

class FoundryFuzzTestsGenerator(val withRevert: Boolean) : BuiltInRuleGenerator() {
    override val eId: BuiltInRuleId
        get() = if (withRevert) {
            BuiltInRuleId.verifyFoundryFuzzTests
        } else {
            BuiltInRuleId.verifyFoundryFuzzTestsNoRevert
        }

    override val birType: SpecType.Single.BuiltIn
        get() = SpecType.Single.BuiltIn(eId)

    override fun doGenerate(
        scope: CVLScope,
        range: Range,
        mainContract: String,
        functionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>
    ): CollectingResult<ICVLRule, CVLError> {
        val importedFuncs = functionsFromSpecFileAndContracts.findEntry { contract, _ -> contract.name == mainContract }?.second
            ?: error("The main contract $mainContract should have been in the SDC mapping")

        val (callableFuncs, uncallableFuncs) = importedFuncs
            .filter { func ->
                func.isPublicMethod() &&
                    func.methodSignature.params.isNotEmpty() &&
                    @Suppress("ForbiddenMethodCall") func.methodSignature.functionName.startsWith("test")
            }
            .filter { func ->
                // If the user specified specific functions, filter only to them.
                Config.MethodChoices.containsMethod(func.getMethodInfo().toExternalABIName(), func.getMethodInfo().contractName, mainContract)
            }
            .partition { func -> func.methodSignature.params.all { it.vmType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).isResult() } }

        uncallableFuncs.forEach { func ->
            CVLWarningLogger.warning(CVTAlertType.GENERAL, "Cannot call test function $func - it has arguments that can't be expressed in CVL", func.getMethodInfo().sourceSegment)
        }

        if (callableFuncs.isEmpty()) {
            return NoFoundryTestsLeft(range, eId.name, mainContract).asError()
        }

        return scope.extendInCollecting(CVLScope.Item::RuleScopeItem) { ruleScope ->
            val emptyTag = CVLExpTag(ruleScope, range)

            val g_expectRevertAllowed = CVLExp.VariableExp("g_expectRevertAllowed", emptyTag)
            val g_expectRevert = CVLExp.VariableExp("g_expectRevert", emptyTag)

            val funParamName = "f"
            val funParamExp = CVLExp.VariableExp(funParamName, emptyTag)

            val envParamName = "e"
            val envParamExp = CVLExp.VariableExp(envParamName, emptyTag)

            fun compareSelectorExp(func: ContractFunction): CVLExp =
                CVLExp.RelopExp.EqExp(
                    CVLExp.FieldSelectExp(funParamExp, selectorField, emptyTag),
                    CVLExp.FieldSelectExp(CVLExp.Constant.SignatureLiteralExp(func.methodSignature as QualifiedMethodParameterSignature, emptyTag), selectorField, emptyTag),
                    emptyTag
                )

            fun insertSpecificFuncCall(func: ContractFunction, ruleScope: CVLScope): CVLCmd =
                ruleScope.extendIn(CVLScope.Item::BlockCmdScopeItem) { blockScope ->
                    CVLCmd.Composite.Block(
                        range,
                        func.methodSignature.params.mapIndexed { i, param ->
                            CVLCmd.Simple.Declaration(
                                range,
                                param.vmType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).force(),
                                "param_$i",
                                blockScope
                            )
                        } + CVLCmd.Simple.contractFunction(
                            range,
                            blockScope,
                            ConcreteMethod(func.methodSignature as ExternalQualifiedMethodSignature),
                            listOf(envParamExp) + List(func.methodSignature.params.size) { i ->
                                CVLExp.VariableExp("param_$i", emptyTag)
                            },
                            !withRevert,
                            CVLExp.VariableExp(CVLKeywords.lastStorage.keyword, emptyTag),
                            isWhole = false,
                            isParametric = false,
                            methodParamFilter = null
                        ),
                        ruleScope
                    )
                }

            /**
             * This should be equivalent to the following spec code:
             * ```
             * require g_expectRevertAllowed == true; // for the case of verifyFoundryFuzzTestsNoRevert: == false
             * require g_expectRevert == false; // only here for the case of verifyFoundryFuzzTests
             * require e.msg.value == 0;
             * init_fuzz_tests(f, e); // `f` and `e` are declared as rule parameter of type `method` and `env`, respectively
             * if (f.selector == sig:testFun1(paramType1_0,...,paramType1_N).selector) {
             *     paramType1_0 param_0;
             *     ...
             *     paramType1_N param_N;
             *     testFun1@withrevert(param_0, ..., param_N); // for the case of verifyFoundryFuzzTestsNoRevert the `@withrevert` is omitted.
             * else if (...) {
             *     ...
             * } ... {
             * } else if (f.selector == sig:testFunK(paramTypeK_0,...,paramTypeK_M).selector) {
             *     paramTypeK_0 param_0;
             *     ...
             *     paramTypeK_M param_M;
             *     testFunK@withrevert(param_0, ..., param_M); // for the case of verifyFoundryFuzzTestsNoRevert the `@withrevert` is omitted.
             * } else {
             *     assert false, "Shouldn't reach here";
             * }
             *
             * assert g_expectRevert == lastReverted; // for the case of verifyFoundryFuzzTestsNoRevert this is just `assert true`
             * ```
             */
            val block = listOfNotNull(
                CVLCmd.Simple.AssumeCmd.Assume(
                    range,
                    CVLExp.RelopExp.EqExp(g_expectRevertAllowed, CVLExp.Constant.BoolLit(withRevert, emptyTag), emptyTag),
                    ruleScope
                ),
                if (withRevert) {
                    CVLCmd.Simple.AssumeCmd.Assume(
                        range,
                        CVLExp.RelopExp.EqExp(g_expectRevert, CVLExp.Constant.BoolLit(false, emptyTag), emptyTag),
                        ruleScope
                    )
                } else {
                    null
                },
                CVLCmd.Simple.AssumeCmd.Assume(
                    range,
                    CVLExp.RelopExp.EqExp(
                        CVLExp.FieldSelectExp(CVLExp.FieldSelectExp(envParamExp, "msg", emptyTag), "value", emptyTag),
                        CVLExp.Constant.NumberLit(BigInteger.ZERO, emptyTag), emptyTag
                    ),
                    ruleScope
                ),
                CVLCmd.Simple.Apply(
                    range,
                    CVLExp.ApplyExp.CVLFunction("init_fuzz_tests", listOf(funParamExp, envParamExp), emptyTag, noRevert = true),
                    ruleScope
                )
            ) + callableFuncs.drop(1).fold(insertSpecificFuncCall(callableFuncs.first(), ruleScope)) { acc, func ->
                CVLCmd.Composite.If(
                    range,
                    compareSelectorExp(func),
                    insertSpecificFuncCall(func, ruleScope),
                    acc,
                    ruleScope
                )
            } + CVLCmd.Simple.Assert(
                range,
                if (withRevert) {
                    CVLExp.RelopExp.EqExp(g_expectRevert, CVLExp.VariableExp(CVLKeywords.lastReverted.keyword, emptyTag), emptyTag)
                } else {
                    CVLExp.Constant.BoolLit(true, emptyTag)
                },
                "fuzz test reversion assert",
                ruleScope
            )

            CVLSingleRule(
                ruleIdentifier = RuleIdentifier.freshIdentifier(this.eId.name),
                range = range,
                params = listOf(
                    CVLParam(EVMBuiltinTypes.method, funParamName, range),
                    CVLParam(EVMBuiltinTypes.env, envParamName, range),
                    ),
                description = "",
                goodDescription = "",
                block = block,
                ruleType = SpecType.Single.BuiltIn(this.eId),
                scope = ruleScope,
                methodParamFilters = MethodParamFilters(
                    range,
                    ruleScope,
                    mapOf(funParamName to MethodParamFilter(
                        funParamExp,
                        callableFuncs.drop(1).fold(compareSelectorExp(callableFuncs.first())) { acc, func ->
                            CVLExp.BinaryExp.LorExp(
                                compareSelectorExp(func),
                                acc,
                                emptyTag)
                        },
                        range,
                        ruleScope
                    ))),
                ruleGenerationMeta = SingleRuleGenerationMeta.Empty
            ).lift()
        }
    }
}
