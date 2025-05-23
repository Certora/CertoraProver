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

import com.certora.collect.*
import rules.sanity.sorts.SanityCheckSort
import rules.sanity.matchingSanityRule
import spec.cvlast.SpecType
import spec.rules.CVLSingleRule
import algorithms.TopologicalOrderException
import algorithms.topologicalOrder
import rules.*
import spec.rules.ICVLRule

/**
 * Generates a [DPGraph] from list of vertices. The generated graph has to be a DAG
 */
interface DependenciesGenerator<T, R, S: R, E: R, @Treapable N : DPNode<T, R, S, E, N>> {

    /**
     * Defined as an extension function to make it effectively protected
     */
    fun DependenciesGenerator<T, R, S, E, N>.doGenerate(payloads: List<T>): DPGraph<T, R, S, E, N>

    /**
     * Generates the graph and checks if it's a DAG.
     */
    fun generate(payloads: List<T>): DPGraph<T, R, S, E, N> =
        doGenerate(payloads).also {
            try {
                topologicalOrder(it).reversed()
            } catch (e: TopologicalOrderException) {
                throw IllegalArgumentException("the graph generated by $payloads is not a DAG", e)
            }
        }
}

/**
 * Totally disconnected graph of rules, currently used to compute the result of all the rules in [SpecChecker]
 */
object TrivialRuleDependencies : DependenciesGenerator<ICVLRule, RuleCheckResult, RuleCheckResult, Nothing, RuleNode> {
    override fun DependenciesGenerator<ICVLRule, RuleCheckResult, RuleCheckResult, Nothing, RuleNode>
        .doGenerate(payloads: List<ICVLRule>): DPGraph<ICVLRule, RuleCheckResult, RuleCheckResult, Nothing, RuleNode> =
        DPGraph(
            payloads.map { RuleNode(it) }.associateWith { emptySet() }
        ) { result, computationalTyp -> DPResult.Success(result, computationalTyp) }
}

/**
 * For each method instantiation we have the following component in the graph where each component is determined
 * by the base rule:
 *               ↙ reachability
 *     base rule
 *               ↖ vacuous asserts checks ← redundant requires checks
 * where there is an edge from each redundant require check to all the checks in "vacuous asserts checks" of the
 * same component. For invariants, "vacuous asserts checks" is the same in each such component.
 */
object SanityRulesDependencies :
    DependenciesGenerator<CompiledRule, RuleCheckResult, RuleCheckResult, RuleCheckResult.Error, SanityCheckNode> {

    override fun DependenciesGenerator<
        CompiledRule, RuleCheckResult, RuleCheckResult, RuleCheckResult.Error, SanityCheckNode
        >.doGenerate(
        payloads: List<CompiledRule>
    ): DPGraph<CompiledRule, RuleCheckResult, RuleCheckResult, RuleCheckResult.Error, SanityCheckNode> {

        // lifting payloads to nodes
        val nodes = payloads.map { compiledCVLRule ->
            val nodeType = if (compiledCVLRule.rule.ruleType is SpecType.Single.GeneratedFromBasicRule) {
                SanityCheckNodeType.SanityCheck(
                    SanityCheckSort(
                        compiledCVLRule.rule.ruleType as SpecType.Single.GeneratedFromBasicRule.SanityRule
                    )
                )
            } else {
                SanityCheckNodeType.None
            }
            SanityCheckNode(compiledCVLRule, nodeType)
        }

        // partition of the nodes to nodes representing a base rule, nodes representing a function-dependent sanity
        // check and nodes representing a function-independent sanity check
        val (baseNodes, sanityNodes) = nodes.partition { it.type is SanityCheckNodeType.None }
        val (functionDependentNodes, functionIndependentNodes) = sanityNodes.partition {
            (it.type as SanityCheckNodeType.SanityCheck).sort is SanityCheckSort.FunctionDependent
        }

        // Maps node to the components containing it
        val nodeToComponents: Map<SanityCheckNode, Set<Set<SanityCheckNode>>> = baseNodes
            .map { baseNode ->
                // generating a component for each node representing a base rule.
                // component for a base rule contains all the function-dependent sanity checks with corresponding method
                // instantiation and all the function-independent sanity checks.
                mutableSetOf<SanityCheckNode>().also { s ->
                    s.add(baseNode)
                    s.addAll(functionIndependentNodes)
                    functionDependentNodes.filterTo(s) { sanityNode ->
                        val sanityRule: CVLSingleRule = sanityNode.payload.rule
                        matchingSanityRule(baseNode.payload.rule, sanityRule)
                    }

                }
            }.let { components ->
                nodes.associateWith { node -> components.filterTo(mutableSetOf()) { component -> node in component } }
            }

        // Maps each node to its predecessors.
        return nodes.associateWith { node ->
            nodes.filterTo(mutableSetOf()) { optionalPredecessor ->
                node.type.dependsOnOther(optionalPredecessor.type) &&
                        nodeToComponents[node]?.any { compOfNode -> optionalPredecessor in compOfNode }
                        ?: throw IllegalStateException(
                    "expected the node $node to have a set of components in [nodeToComponents]"
                )
            }
        }.let {
            DPGraph(it) { result, computationalTyp -> DPResult(result, computationalTyp) }
        }

    }

}
