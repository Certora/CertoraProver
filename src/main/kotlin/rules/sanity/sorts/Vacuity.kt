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
import rules.sanity.*
import rules.sanity.SanityCheckResultOrdinal.Companion.toDefaultSanityCheckResultOrdinal
import solver.SolverResult
import spec.cvlast.SpecType
import spec.rules.IRule
import datastructures.stdcollections.*

data object Vacuity : SanityCheckSort.FunctionDependent<RuleCheckResult.Single, IRule> {

    override val mode = SanityValues.BASIC

    override val reportName = "vacuity check"

    override fun checkResultToSanityResultOrd(s: DPSuccess<RuleCheckResult.Single>): SanityCheckResultOrdinal =
        s.result.toDefaultSanityCheckResultOrdinal()

    override fun checkResultToDetailsStr(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.firstData.details

    override val severityLevel = SanityCheckSeverity.Critical

    override val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<IRule> =
        SanityCheckNonErrorUIMessageFormatter(
            rawMsg = reportName,
            rawMsgFormatter = { sanityOrdinalValue, _, details: String, rawMsg: String ->
                "$rawMsg ${sanityOrdinalValue.reportString()}" +
                    when (sanityOrdinalValue) {
                        SanityCheckResultOrdinal.PASSED -> {
                            " (the rule is not vacuous)"
                        }
                        SanityCheckResultOrdinal.FAILED -> {
                            " (the rule is vacuous)"
                        }
                        else -> {
                            ""
                        }
                    } + ": $details"
            }
        )

    override fun checkResultToSanitySubCheckGroup(r: SanityDPResult): IRule = r.result.rule

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck, IRule>(
            _baseResults,
            _sanityCheckResults,
            this
        )

    override val preds: List<SanityCheckNodeType> = listOf(SanityCheckNodeType.None)

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
        /* conclude this vacuity check as passing (SAT) (effectively don't perform this check) if
        the base rule is not verified (UNSAT) */
        val shouldConclude = when (val ruleCheckResult = predResult.value.result) {
            is RuleCheckResult.Single -> ruleCheckResult.result != SolverResult.UNSAT
            is RuleCheckResult.Multi -> ruleCheckResult.computeFinalResult() != SolverResult.UNSAT
            is RuleCheckResult.Error -> true
            is RuleCheckResult.Skipped -> true
        }
        return if (shouldConclude) {
            SolverResult.SAT
        } else {
            null
        }
    }
}
