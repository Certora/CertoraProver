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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence

import verifier.equivalence.data.EquivalenceQueryContext

/**
 * All of the information about the counter example being analyzed; the context
 * of the overall query in [queryContext], the [checkResult] (which should be a SAT result),
 * the rule generator use [ruleGeneration], and the settings used to generate the program which
 * had the counter example in [instrumentationSettings].
 */
data class CounterExampleContext<I>(
    val queryContext: EquivalenceQueryContext,
    val checkResult: TraceEquivalenceChecker.CheckResult<I>,
    val ruleGeneration: AbstractRuleGeneration<I>,
    val instrumentationSettings: ExplorationManager.EquivalenceCheckConfiguration<I>
)
