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
import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult.Companion.ok
import utils.Range
import utils.VoidResult

object MsgValueInLoopGenerator : InstrumentingBuiltInRuleGenerator() {
    override val eId: BuiltInRuleId = BuiltInRuleId.msgValueInLoopRule
    override val birType: SpecType.Single.BuiltIn =
        SpecType.Single.BuiltIn(eId)

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
                symbolicFunctionName to MethodParamFilter.onlyPayableMethodsFilter(
                    cvlVarExpF,  range = range, scope = scope ) ) )
    }

    override fun checkIfCanGenerate(range: Range): VoidResult<CVLError> = ok

    override val sinkType = BuiltInRuleSinkType.NOP
}
