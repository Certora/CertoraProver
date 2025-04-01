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

import config.Config
import utils.Range
import spec.cvlast.CVLScope
import spec.cvlast.MethodParamFilters
import spec.cvlast.SpecType
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.ok
import utils.VoidResult
import datastructures.stdcollections.*

class DeepSanityGenerator(private val methodParamFilters: MethodParamFilters) : InstrumentingBuiltInRuleGenerator() {
    override val eId: BuiltInRuleId = BuiltInRuleId.deepSanity
    override val birType: SpecType.Single.BuiltIn = SpecType.Single.BuiltIn(eId)

    // update the scope of each method param filter, so we could bind to `f`
    override fun getMethodParamFilters(
        range: Range,
        scope: CVLScope,
        symbolicFunctionName: String
    ): MethodParamFilters =
        methodParamFilters.copy(
            range = range,
            scope = scope,
            methodParamToFilter = methodParamFilters.methodParamToFilter.mapValues { it.value.copy(scope = scope) })

    override fun checkIfCanGenerate(range: Range): VoidResult<CVLError> {
        // skip if we run from typechecker, because we do not yet pass jar arguments to typechecker in local mode
        if (!Config.MainJarRun.get()) {
            return ok
        }

        // if multiAssertCheck is not enabled, this rule cannot be effectively run
        return if (!Config.MultiAssertCheck.get()) {
            CVLError.General(
                range,
                "Cannot run $eId when --multi_assert_check is not enabled"
            ).asError()
        } else {
            ok
        }
    }
}
