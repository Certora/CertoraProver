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

package rules.dpgraph

import datastructures.stdcollections.*
import rules.*
import rules.sanity.sorts.SanityCheckSort
import spec.rules.ICVLRule

/**
 * Each node in the dependency graph should have a type that determines the way the result of the node can be concluded
 * from the result of its dependencies.
 * @param T type of node's payload.
 * @param R result type of the task that should be computed in the graph.
 * @param N type of graph node, used to allow a node to conclude its result from predecessors.
 */
interface DPNode<T, R, S: R, E: R, N: DPNode<T, R, S, E, N>> {

    val payload: T

    fun concludeResultFromPredsOrNull(
        predNodesToResults: Map<N, DPResult<R, S, E>>
    ): R?
}

data class SanityCheckNode(override val payload: CompiledRule, val type: SanityCheckNodeType):
    DPNode<CompiledRule, RuleCheckResult, RuleCheckResult, RuleCheckResult.Error, SanityCheckNode> {

    /**
     * For base there are no dependencies so the conclusion is always null. For sanity check, the conclusion is
     * determined by the [SolverResult] returned from the sort.
     */
    override fun concludeResultFromPredsOrNull(
        predNodesToResults: Map<SanityCheckNode, DPResult<RuleCheckResult, RuleCheckResult, RuleCheckResult.Error>>
    ): RuleCheckResult? =
        when (type) {
            SanityCheckNodeType.None -> {
                null
            }
            is SanityCheckNodeType.SanityCheck -> {
                type.sort.concludeResultFromPredsOrNull(
                    predNodesToResults
                )?.let {
                    RuleCheckResult.Single.Basic(
                        rule = payload.rule,
                        result = it,
                        verifyTime = VerifyTime.None,
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(dumpGraphLink = null, isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE, isSolverResultFromCache = IsFromCache.INAPPLICABLE),
                        ruleAlerts = null
                    )
                }
            }

        }
}

/**
 * Can be a base rule with no dependencies or a sanity check node with dependencies defined in the sort.
 */
sealed class SanityCheckNodeType{

    abstract fun dependsOnOther(other: SanityCheckNodeType): Boolean

    /**
     * Denotes a "no sanity check" and corresponds to a base rule.
     */
    object None: SanityCheckNodeType() {
        override fun dependsOnOther(other: SanityCheckNodeType) = false
    }

    /**
     * Corresponds to a sanity check. If [SanityCheckNode.type] is [SanityCheck], [sort] should correspond to
     * [SanityCheckNode.payload] as implemented in [SanityCheckSort.invoke].
     */
    data class SanityCheck(val sort: SanityCheckSort<*, *>) : SanityCheckNodeType() {
        override fun dependsOnOther(other: SanityCheckNodeType) = sort.dependsOnOther(other)
    }
}

/**
 * Currently used for all the rules that are checked in the [SpecChecker], no conclusion mechanism is defined.
 */
data class RuleNode(override val payload: ICVLRule) : DPNode<ICVLRule, RuleCheckResult, RuleCheckResult, Nothing, RuleNode> {
    override fun concludeResultFromPredsOrNull(
        predNodesToResults: Map<RuleNode, DPResult<RuleCheckResult, RuleCheckResult, Nothing>>
    ): RuleCheckResult? = null

}
