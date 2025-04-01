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

import spec.cvlast.*
import datastructures.stdcollections.*
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.Range
import utils.VoidResult

/**
 * An automatically generated rule to check no view function re-entrency weakness exists.
 * This works by automatically creating CVL code calling a parametric function f.
 * The code will later be instrumented around f to check view re-entrancy weaknesses.
 */
object ViewReentrancyGenerator : InstrumentingBuiltInRuleGenerator() {
    override val eId: BuiltInRuleId
        get() = BuiltInRuleId.viewReentrancy
    override val birType: SpecType.Single.BuiltIn
        get() = SpecType.Single.BuiltIn(BuiltInRuleId.viewReentrancy)

    override val allSceneFunctions = false

    override fun getMethodParamFilters(
        range: Range,
        scope: CVLScope,
        symbolicFunctionName: String
    ): MethodParamFilters {
        val cvlVarExpF = CVLExp.VariableExp(
            symbolicFunctionName,
            CVLExpTag(scope, EVMBuiltinTypes.method, range)
        )
        return MethodParamFilters(
            range, scope, mapOf(
                symbolicFunctionName to MethodParamFilter.ignoreViewMethodsFilter(
                    cvlVarExpF,  range = range, scope = scope ) ) )
    }

    override fun checkIfCanGenerate(range: Range): VoidResult<CVLError> = CollectingResult.ok

    override val sinkType = BuiltInRuleSinkType.NOP
}
