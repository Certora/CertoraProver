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
import config.Config
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.FuzzTestBirWithoutFoundryFlag
import spec.cvlast.typechecker.InvalidMethodParamFiltersOnBuiltinRule
import spec.rules.ICVLRule
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map

/**
 * BuiltIn rules are predefined rules that can be imported into CVL spec files. These rules may be seen as "Certora's stdlib" rules.
 * Each such a rule is automatically generated using a [BuiltInRuleGenerator] and must have a [BuiltInRuleId] ([eId]).
 */
sealed class BuiltInRuleGenerator {

    abstract val eId: BuiltInRuleId
    abstract val birType: SpecType.Single.BuiltIn

    fun generate(
        scope: CVLScope,
        range: Range,
        mainContract: String,
        functionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>
    ): CollectingResult<ICVLRule, CVLError> =
        doGenerate(scope, range, mainContract, functionsFromSpecFileAndContracts).map {
            check (it.declarationId == eId.name) {
                "Could not generate builtin rule $eId - got instead rule ${it.declarationId}"
            }
            it
        }

    /**
     * When we generate a BIR instance, it's useful to have a proper CVL Range.
     * This helps determine a range based on the range of its use declaration range
     * @param ast for the CVL object
     * @return range of use site in the given AST, or Empty if not used
     */
    fun getRange(ast: CVLAst) = ast.useDeclarations.builtInRulesInUse.find { it.id == eId.name }?.range
        ?: Range.Empty()

    protected abstract fun doGenerate(
        scope: CVLScope,
        range: Range,
        mainContract: String,
        functionsFromSpecFileAndContracts: Map<ContractInstanceInSDC, List<ContractFunction>>
    ): CollectingResult<ICVLRule,CVLError>

    data class BirsMetadata(val birUseDecl: UseDeclaration.BuiltInRule, val birId: BuiltInRuleId, val methodParamFilters: MethodParamFilters)

    companion object {
        /**
         * Resolves [birMetadata] to a [BuiltInRuleGenerator].
         */
        fun fromBirMetadata(birMetadata: BirsMetadata, methodParamFilters: MethodParamFilters): CollectingResult<BuiltInRuleGenerator, CVLError> =
            when (val birId = birMetadata.birId) {
                BuiltInRuleId.hasDelegateCalls -> HasDelegateCalls(methodParamFilters).lift()
                BuiltInRuleId.trustedMethods -> TrustedMethods(methodParamFilters).lift()
                BuiltInRuleId.safeCasting -> SafeCastingGenerator(methodParamFilters).lift()
                BuiltInRuleId.uncheckedOverflows -> UncheckedOverflowGenerator(methodParamFilters).lift()
                BuiltInRuleId.msgValueInLoopRule -> if (!methodParamFilters.isEmpty()) {
                    InvalidMethodParamFiltersOnBuiltinRule(
                        birMetadata.birUseDecl.range,
                        birId
                    ).asError()
                } else {
                    MsgValueInLoopGenerator.lift()
                }
                BuiltInRuleId.viewReentrancy -> if (!methodParamFilters.isEmpty()) {
                    InvalidMethodParamFiltersOnBuiltinRule(
                        birMetadata.birUseDecl.range,
                        birId
                    ).asError()
                } else {
                    ViewReentrancyGenerator.lift()
                }
                BuiltInRuleId.deepSanity -> DeepSanityGenerator(methodParamFilters).lift()
                BuiltInRuleId.sanity -> SanityGenerator(methodParamFilters).lift()
                BuiltInRuleId.verifyFoundryFuzzTests,
                BuiltInRuleId.verifyFoundryFuzzTestsNoRevert -> {
                    if (!Config.Foundry.get()) {
                        FuzzTestBirWithoutFoundryFlag(birMetadata.birUseDecl.range, birId).asError()
                    } else if (!methodParamFilters.isEmpty()) {
                        InvalidMethodParamFiltersOnBuiltinRule(
                            birMetadata.birUseDecl.range,
                            birId
                        ).asError()
                    } else {
                        FoundryFuzzTestsGenerator(withRevert = birId == BuiltInRuleId.verifyFoundryFuzzTests).lift()
                    }
                }
            }
    }
}
