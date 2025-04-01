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
import spec.cvlast.CVLExp
import spec.cvlast.SpecType
import datastructures.stdcollections.*

val assertionStructurePreds = listOf(SanityCheckNodeType.SanityCheck(Vacuity))

sealed interface AssertionsStructure : SanityCheckSort.FunctionDependent<RuleCheckResult.Single, CVLCmd.Simple.Assert> {
    override val mode
        get() = SanityValues.ADVANCED
    val assertExp: CVLExp.BinaryExp
    override val reportName: String
        get()= "assertion structure check"
    override val preds: List<SanityCheckNodeType> get() = assertionStructurePreds

    override fun checkResultToSanityResultOrd(s: DPSuccess<RuleCheckResult.Single>): SanityCheckResultOrdinal =
        s.result.toDefaultSanityCheckResultOrdinal()

    override fun checkResultToDetailsStr(s: DPSuccess<RuleCheckResult.Single>) =
        s.result.firstData.details

    override fun checkResultToSanitySubCheckGroup(r: SanityDPResult): CVLCmd.Simple.Assert =
        r.result.rule.narrowType<SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck>().ruleType.assertCVLCmd

    override fun concludeResultFromPredsOrNull(
        predsResults: Map<SanityCheckNode, SanityDPResult>
    ): SolverResult? {
        require(predsResults.size == 1) { "Expected to have only one predecessor for an AssertionStructure node" }
        val predResult = predsResults.entries.single()
        if (predResult.key.type !in preds) {
            throw IllegalArgumentException("assertion-structure check does not depend on ${predResult.key.type}")
        }
        /* conclude this check as failing (UNSAT) if
        vacuity check is failing */
        val shouldConclude = when (val ruleCheckResult = predResult.value.result) {
            is RuleCheckResult.Single -> ruleCheckResult.result == SolverResult.UNSAT
            is RuleCheckResult.Multi -> ruleCheckResult.computeFinalResult() == SolverResult.UNSAT
            is RuleCheckResult.Error -> false
            is RuleCheckResult.Skipped -> false
        }
        return if (shouldConclude) {
            SolverResult.UNSAT
        } else {
            null
        }
    }

    val rawMsgFormatter: (SanityCheckResultOrdinal, CVLCmd.Simple.Assert, String, String) -> String

    override val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<CVLCmd.Simple.Assert>
        get() =
            SanityCheckNonErrorUIMessageFormatter(
                rawMsg = reportName,
                rawMsgFormatter = rawMsgFormatter
            )
}

@JvmInline
value class AssertionsStructureLeftOperand(override val assertExp: CVLExp.BinaryExp) : AssertionsStructure {
    override val severityLevel: SanityCheckSeverity
        get() = if (assertExp is CVLExp.BinaryExp.ImpliesExp) {
            SanityCheckSeverity.Critical
        } else {
            SanityCheckSeverity.Info
        }

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.LeftOperand,
                CVLCmd.Simple.Assert>(
            _baseResults, _sanityCheckResults, this
        )

    override val rawMsgFormatter
        get() = { sanityOrdinalValue: SanityCheckResultOrdinal, assertCmd: CVLCmd.Simple.Assert, _: String, rawMsg: String ->
            "$rawMsg ${sanityOrdinalValue.reportString()}: ${assertCmd.range}" +
                if (sanityOrdinalValue == SanityCheckResultOrdinal.FAILED) {
                    assertCmd.description
                } else {
                    "checked expression ${assertExp.l}"
                }
        }
}

@JvmInline
value class AssertionsStructureRightOperand(override val assertExp: CVLExp.BinaryExp) : AssertionsStructure {
    override val severityLevel: SanityCheckSeverity
        get() = SanityCheckSeverity.Info

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.RightOperand,
                CVLCmd.Simple.Assert>(
            _baseResults, _sanityCheckResults, this
        )

    override val rawMsgFormatter: (SanityCheckResultOrdinal, CVLCmd.Simple.Assert, String, String) -> String
        get() = { sanityOrdinalValue: SanityCheckResultOrdinal, assertCmd: CVLCmd.Simple.Assert, _, rawMsg: String ->
            "$rawMsg ${sanityOrdinalValue.reportString()}: ${assertCmd.range}" +
                if (sanityOrdinalValue == SanityCheckResultOrdinal.FAILED) {
                    assertCmd.description
                } else {
                    "checked expression ${assertExp.r}"
                }
        }
}

@JvmInline
value class AssertionsStructureIFFBothFalse(override val assertExp: CVLExp.BinaryExp) : AssertionsStructure {
    override val severityLevel: SanityCheckSeverity
        get() = SanityCheckSeverity.Info

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothFalse,
                CVLCmd.Simple.Assert>(
            _baseResults, _sanityCheckResults, this
        )

    override val rawMsgFormatter: (SanityCheckResultOrdinal, CVLCmd.Simple.Assert, String, String) -> String
        get() = { sanityOrdinalValue: SanityCheckResultOrdinal, assertCmd: CVLCmd.Simple.Assert, _, rawMsg: String ->
            "$rawMsg ${sanityOrdinalValue.reportString()}: ${assertCmd.range}" +
                if (sanityOrdinalValue == SanityCheckResultOrdinal.FAILED) {
                    assertCmd.description
                } else {
                    "checks that both ${assertExp.l} and ${assertExp.r} are not false, because that would make " +
                        "$assertExp always true"
                }
        }
}

@JvmInline
value class AssertionsStructureIFFBothTrue(override val assertExp: CVLExp.BinaryExp) : AssertionsStructure {
    override val severityLevel: SanityCheckSeverity
        get() = SanityCheckSeverity.Info

    override val preds: List<SanityCheckNodeType>
        get() = listOf(SanityCheckNodeType.SanityCheck(AssertionsStructureIFFBothFalse(assertExp)))

    override fun concludeResultFromPredsOrNull(
        predsResults: Map<SanityCheckNode, SanityDPResult>
    ): SolverResult? {
        require(predsResults.size <= 1) {"assertion structure check for $assertExp should have only one predecessor"}
        if (predsResults.isEmpty()) {
            return null
        }
        val predResult = predsResults.entries.single()
        if (!preds.contains(predResult.key.type)) {
            throw IllegalArgumentException("assert-structure check for $assertExp does not depend on " +
                    "${predResult.key.type}")
        }
        return when (val firstResult = predResult.value.result) {
            is RuleCheckResult.Error -> null
            is RuleCheckResult.Multi -> {
                if (firstResult.computeFinalResult() == SolverResult.UNSAT) {
                    SolverResult.SAT
                } else {
                    null
                }
            }

            is RuleCheckResult.Single -> {
                if (firstResult.result == SolverResult.UNSAT) {
                    SolverResult.SAT
                } else {
                    null
                }
            }

            is RuleCheckResult.Skipped -> null
        }
    }

    override fun toSanityResultsView(
        _baseResults: List<SanityDPResult>,
        _sanityCheckResults: List<SanityDPResult>
    ): SanityResultsView.FunctionDependent<RuleCheckResult.Single> =
        SanityResultsView.FunctionDependent<RuleCheckResult.Single,
                SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothTrue,
                CVLCmd.Simple.Assert>(
            _baseResults, _sanityCheckResults, this
        )

    override val rawMsgFormatter: (SanityCheckResultOrdinal, CVLCmd.Simple.Assert, String, String) -> String
        get() = { sanityOrdinalValue: SanityCheckResultOrdinal, assertCmd: CVLCmd.Simple.Assert, _, rawMsg: String ->
            "$rawMsg ${sanityOrdinalValue.reportString()}: ${assertCmd.range}" +
                if (sanityOrdinalValue == SanityCheckResultOrdinal.FAILED) {
                    assertCmd.description
                } else {
                    "checks that both ${assertExp.l} and ${assertExp.r} are not true, because that would make " +
                        "$assertExp always true"
                }
        }
}
