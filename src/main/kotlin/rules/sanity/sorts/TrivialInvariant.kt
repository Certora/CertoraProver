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
import utils.CheckedUrl

object TrivialInvariant :
    SanityCheckSort.FunctionIndependent<RuleCheckResult.Single, CVLCmd.Simple.Assert> {
    override val mode = SanityValues.BASIC
    override val severityLevel: SanityCheckSeverity = SanityCheckSeverity.Critical
    override val reportName = "Trivial invariant check"

    override fun checkResultToSanityResultOrd(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.toDefaultSanityCheckResultOrdinal()

    override fun checkResultToDetailsStr(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.firstData.details

    override val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<CVLCmd.Simple.Assert> =
        SanityCheckNonErrorUIMessageFormatter(
            rawMsg = reportName,
            rawMsgFormatter = { sanityOrdinalValue, assertCmd, _, rawMsg ->
                "$rawMsg ${sanityOrdinalValue.reportString()}:" +
                    if (sanityOrdinalValue == SanityCheckResultOrdinal.FAILED) {
                        " The invariant condition `${assertCmd.exp}` is trivially true - it's verified also without " +
                            "assuming it first, or calling any contract function. See ${CheckedUrl.TRIVIAL_INVARIANT_CHECKS}"
                    } else {
                        ""
                    }
            }
        )

    override fun toSanityResultsView(_sanityCheckResults: List<SanityDPResult>):
        SanityResultsView.FunctionIndependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionIndependent<RuleCheckResult.Single,
            SpecType.Single.GeneratedFromBasicRule.SanityRule.TrivialInvariantCheck,
            CVLCmd.Simple.Assert>(_sanityCheckResults, this)


    override fun checkResultToSanitySubCheckGroup(r: SanityDPResult) =
        r.result.rule.narrowType<SpecType.Single.GeneratedFromBasicRule.SanityRule.TrivialInvariantCheck>()
            .ruleType.assertCVLCmd

    override val preds: List<SanityCheckNodeType> =
        listOf(SanityCheckNodeType.None)

    /**
     * If the invariant failed it is not trivially true.
     */
    override fun concludeResultFromPredsOrNull(
        predsResults: Map<SanityCheckNode, SanityDPResult>,
    ): SolverResult? {
        predsResults.keys.forEach {
            if (it.type !in preds) {
                throw IllegalArgumentException("trivial invariant check does not depend on ${it.type}")
            }
        }
        /* conclude trivial-invariant check as passing (SAT) (effectively don't perform this check) if
        any of the base rules (there are multi since this is a function-independent check) is not verified (UNSAT) */
        val shouldConclude = predsResults.values.any {
            when (val ruleCheckResult = it.result) {
                is RuleCheckResult.Single -> ruleCheckResult.result != SolverResult.UNSAT
                is RuleCheckResult.Multi -> ruleCheckResult.computeFinalResult() != SolverResult.UNSAT
                is RuleCheckResult.Error -> true
                is RuleCheckResult.Skipped -> true
            }
        }
        return if (shouldConclude) {
            SolverResult.SAT
        } else {
            null
        }
    }
}
