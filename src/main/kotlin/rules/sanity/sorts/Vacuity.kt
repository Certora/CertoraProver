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

package rules.sanity.sorts

import cli.SanityValues
import rules.RuleCheckResult
import rules.dpgraph.SanityCheckNode
import rules.dpgraph.SanityCheckNodeType
import rules.sanity.SanityDPResult
import solver.SolverResult
import spec.rules.IRule
import datastructures.stdcollections.*
import report.RuleAlertReport
import utils.*

data object Vacuity : SanityCheckSort.FunctionDependent<RuleCheckResult.Single, IRule> {

    override val mode = SanityValues.BASIC

    override val preds: List<SanityCheckNodeType> = listOf(SanityCheckNodeType.None)
    override fun getRuleNotificationForResult(solverResult: SolverResult): RuleAlertReport? {
        val msg =  "The rule vacuity sanity check ${solverResult.toSanityStatusString()}."
        return if(solverResult == SolverResult.UNSAT) {
            RuleAlertReport.Warning(msg + " Even when ignoring all user asserts, the end of the rule is not reachable. See ${CheckedUrl.SANITY_VACUITY}")
        } else if (solverResult == SolverResult.SAT){
            // In the case the check succeeded, i.e. SAT, don't output any rule notification
            null
        } else {
            RuleAlertReport.Info(msg)
        }
    }
    /**
     * If the corresponding base rule has failed the assert is reachable.
     */
    override fun concludeResultFromPredsOrNull(
        predsResults: Map<SanityCheckNode, SanityDPResult>
    ): SolverResult? {
        require(predsResults.size == 1) { "Expected to have only one predecessor for a Vacuity node" }
        val predResult = predsResults.entries.single()
        if (predResult.key.type !in preds) {
            throw IllegalArgumentException("vacuity check does not depend on ${predResult.key.type}")
        }
        /**
         * Conclude this vacuity check as passing (SAT) (effectively don't perform this check) if
         * the base rule is not verified (UNSAT).
         */
        fun shouldConclude(result: RuleCheckResult): Boolean = when (result) {
            is RuleCheckResult.Single -> result.result != SolverResult.UNSAT
            is RuleCheckResult.Multi ->
                /**
                 * Note that we can't use [RuleCheckResult.Multi.computeFinalResult] here because if there's a satisfy
                 * subrule then even if it's violated (UNSAT), since the top-level rule isn't marked as a sanity rule
                 * that function will return SAT.
                 */
                result.results.all { shouldConclude(it) }
            is RuleCheckResult.Error -> true
            is RuleCheckResult.Skipped -> true
        }

        return if (shouldConclude(predResult.value.result)) {
            SolverResult.SAT
        } else {
            null
        }
    }
}
