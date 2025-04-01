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

package spec.cvlast.typechecker

import spec.cvlast.*
import spec.isWildcard
import utils.CollectingResult
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range

class CVLParamTypeChecker(
    symbolTable: CVLSymbolTable
) {
    private val typeTypeChecker = CVLTypeTypeChecker(symbolTable)

    fun typeCheckCVLParams(
        params: List<CVLParam>,
        range: Range,
        scope: CVLScope,
        fromUser: Boolean = true
    ): CollectingResult<List<CVLParam>, CVLError> = collectingErrors {
        val typeCheckedTypes = collectAndFilter(params.associateWith { param ->
            typeTypeChecker.typeCheck(param.type, range, scope)
        })
        return@collectingErrors typeCheckedTypes.map { (param, type) ->
            // If the rule is a derived rule, it's parameters are also derived, and
            // the user should only see the errors for rules they had written explicitly
            if (fromUser && type is CVLType.PureCVLType.Ghost.Mapping) {
                val where = when (scope.topLevelScope()) {
                    is CVLScope.Item.RuleScopeItem -> "rule"
                    is CVLScope.Item.InvariantScopeItem -> "invariant"
                    is CVLScope.Item.CVLFunctionScopeItem -> "CVL function"
                    else -> error("unexpected scope for param typechecks")
                }
                collectError(CVLError.General(range, "CVL does not support mappings as $where parameters"))
            }

            val topLevelScope = scope.topLevelScope()
            val isInvariantDerived = topLevelScope is CVLScope.Item.InvariantScopeItem ||
                (topLevelScope is CVLScope.Item.RuleScopeItem && topLevelScope.isDerivedFromInvariant())
            // Do not accept wildcard-named params, except for the case of preserved blocks in an invariant - the
            // arguments there must be named, and this is a good way to signal to the reader the argument should be ignored.
            if (!isInvariantDerived && param.id.isWildcard()) {
                collectError(CVLError.General(range, "`_` is the wildcard variable. Cannot declare an argument with that name"))
            }
            param.copy(type = type)
        }
    }
}
