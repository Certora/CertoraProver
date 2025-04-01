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
import utils.*
import utils.CollectingResult.Companion.ok

/**
 * Generates a static rule that corresponds to the following CVL code:
 *
 * rule fHasExternalCalls(method f, env fEnv, calldataargs fCalldataArgs) {
 *   invoke f(fEnv, fCallDataArgs);
 * }
 *
 * This rule is checked statically by checking, for each concrete f, whether f contains a delegate call command.
 *
 */
data class HasDelegateCalls(private val methodParamFilters: MethodParamFilters) : InstrumentingBuiltInRuleGenerator() {

    override val eId: BuiltInRuleId = BuiltInRuleId.hasDelegateCalls

    override val birType: SpecType.Single.BuiltIn = SpecType.Single.BuiltIn(eId)

    override val allSceneFunctions = false

    override val noRevert = false

    override fun getMethodParamFilters(
        range: Range,
        scope: CVLScope,
        symbolicFunctionName: String
    ): MethodParamFilters = methodParamFilters.copy(
        range = range,
        scope = scope,
        methodParamToFilter = methodParamFilters.methodParamToFilter.mapValues { it.value.copy(scope = scope) })

    override fun checkIfCanGenerate(range: Range): VoidResult<CVLError> = ok
}
