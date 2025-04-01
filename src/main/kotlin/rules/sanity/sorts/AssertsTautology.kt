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
import spec.cvlast.CVLCmd
import spec.cvlast.SpecType
import datastructures.stdcollections.*

data object AssertsTautology :
    SanityCheckSort.FunctionDependent<RuleCheckResult.Single, CVLCmd.Simple.Assert> {
    override val mode = SanityValues.ADVANCED
    override val severityLevel = SanityCheckSeverity.Critical
    override val reportName = "assert tautology check"

    override fun checkResultToSanityResultOrd(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.toDefaultSanityCheckResultOrdinal()

    override fun checkResultToDetailsStr(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.firstData.details

    override val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<CVLCmd.Simple.Assert> =
        SanityCheckNonErrorUIMessageFormatter(
            rawMsg = reportName,
            rawMsgFormatter = { sanityOrdinalValue, assertCmd: CVLCmd.Simple.Assert, _, rawMsg: String ->
                "$rawMsg ${sanityOrdinalValue.reportString()}: ${assertCmd.range}"
            }
        )

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertTautologyCheck,
                CVLCmd.Simple.Assert>(
            _baseResults, _sanityCheckResults, this
        )

    override fun checkResultToSanitySubCheckGroup(r: SanityDPResult) =
        r.result.rule.narrowType<SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertTautologyCheck>().ruleType.assertCVLCmd

    override val preds: List<SanityCheckNodeType> =
        listOf(SanityCheckNodeType.None)

    /**
     * If the base rule has failed the assert is not a tautology.
     */
    override fun concludeResultFromPredsOrNull(
        predsResults: Map<SanityCheckNode, SanityDPResult>
    ): SolverResult? {
        require(predsResults.size == 1) { "Expected to have only one predecessor node for an AssertTautology node" }
        val predResult = predsResults.entries.single()
        if (predResult.key.type !in preds) {
            throw IllegalArgumentException("assert-tautology check does not depend on ${predResult.key.type}")
        }
        /* conclude this assert-tautology check as passing (SAT) (effectively don't perform this check) if
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
