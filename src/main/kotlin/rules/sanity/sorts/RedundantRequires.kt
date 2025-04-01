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

data object RedundantRequires :
    SanityCheckSort.FunctionDependent<RuleCheckResult.Single, CVLCmd.Simple.AssumeCmd> {
    override val mode = SanityValues.ADVANCED
    override val severityLevel = SanityCheckSeverity.Info
    override val reportName = "require-redundancy check"

    override fun checkResultToSanityResultOrd(s: DPSuccess<RuleCheckResult.Single>): SanityCheckResultOrdinal =
        s.result.toDefaultSanityCheckResultOrdinal()

    override fun checkResultToDetailsStr(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.firstData.details

    override val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<CVLCmd.Simple.AssumeCmd> =
        SanityCheckNonErrorUIMessageFormatter(
            rawMsg = reportName,
            rawMsgFormatter = { sanityOrdinalValue, assumeCmd: CVLCmd.Simple.AssumeCmd, _, rawMsg: String ->
                if (assumeCmd is CVLCmd.Simple.AssumeCmd.Assume && assumeCmd.invariantPreCond) {
                    "$rawMsg ${sanityOrdinalValue.reportString()}: precondition check"
                } else {
                    "$rawMsg ${sanityOrdinalValue.reportString()}: ${assumeCmd.range}"
                }
            }
        )

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.RedundantRequireCheck,
                CVLCmd.Simple.AssumeCmd>(
            _baseResults,
            _sanityCheckResults,
            this
        )

    override fun checkResultToSanitySubCheckGroup(r: SanityDPResult): CVLCmd.Simple.AssumeCmd =
        r.result.rule
            .narrowType<SpecType.Single.GeneratedFromBasicRule.SanityRule.RedundantRequireCheck>().ruleType.assumeCVLCmd

    override val preds: List<SanityCheckNodeType> =
        listOf(
            SanityCheckNodeType.SanityCheck(AssertsTautology),
            SanityCheckNodeType.SanityCheck(TrivialInvariant)
        )

    /**
     * If the asserts are vacuous the require is redundant.
     */
    override fun concludeResultFromPredsOrNull(predsResults: Map<SanityCheckNode, SanityDPResult>): SolverResult? {
        predsResults.keys.forEach {
            if (it.type !in preds) {
                throw IllegalArgumentException("redundant-require check does not depend on ${it.type}")
            }
        }
        /* conclude this redundant-require check as failing (UNSAT) if
        all the predecessors are failing (UNSAT) */
        val shouldConclude = predsResults.values.all {
            when (val ruleCheckResult = it.result) {
                is RuleCheckResult.Single -> ruleCheckResult.result == SolverResult.UNSAT
                is RuleCheckResult.Multi -> ruleCheckResult.computeFinalResult() == SolverResult.UNSAT
                is RuleCheckResult.Error -> false
                is RuleCheckResult.Skipped -> false
            }
        }
        return if (shouldConclude) {
            SolverResult.UNSAT
        } else {
            null
        }
    }
}
