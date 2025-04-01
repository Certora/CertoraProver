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

package spec.cvlast

import bridge.ContractInstanceInSDC
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import spec.rules.CVLSingleRule
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.safeForce
import utils.Range
import utils.mapToSet

/**
 * Adds [MethodParamFilters] to all parametric rules that filter out any function that was marked in the spec with the
 * [SummarizationMode.DELETE] summary.
 * Note that these filters are added in conjunction with the explicit user filters, if such exist.
 */
class FilterDeletedMethods(private val ast: CVLAst, private val functionInfo: Map<ContractInstanceInSDC, List<ContractFunction>>) : CVLAstTransformer<Nothing>(
    cmdTransformer = CVLCmdTransformer(expTransformer = CVLExpTransformer.copyTransformer())
) {
    fun addFilters(): CollectingResult<CVLAst, Nothing> {
        return ast(ast)
    }

    override fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, Nothing> {
        // Collect all [ContractFunction]s that correspond to deleted methods
        val deletedContractFunctions = ast.importedMethods
            .filter { it.summary?.summarizationMode == SpecCallSummary.SummarizationMode.DELETE }
            .flatMap { it.getMatchingContractFunctions(functionInfo) }

        // Now construct the [MethodParamFilters] that will filter out all these deleted functions for all the method parameters
        val methodParams = rule.params.filter { it.type == EVMBuiltinTypes.method }

        val deletedMethodsParamFilters = MethodParamFilters.dontCallFilters(
            Range.Empty("don't call deleted methods filters"),
            rule.scope,
            methodParams.mapToSet { it.id },
            deletedContractFunctions.map {
                check(it.isPublicMethod()) { "Only public/external methods are DELETE-able, got $it in the set of deleted functions $deletedContractFunctions" }
                it.getMethodInfo()
            }
        )

        // Conjunct the deleted-methods filters we just created with whatever filters the rule already has.
        val newFilters = MethodParamFilters.conjunct(rule.range, rule.scope, rule.methodParamFilters, deletedMethodsParamFilters)

        return rule.copy(methodParamFilters = newFilters).lift()
    }

    override fun ast(ast: CVLAst): CollectingResult<CVLAst, Nothing> {
        return ast.copy(rules = ast.rules.map { rule(it).safeForce() }).lift()
    }
}
