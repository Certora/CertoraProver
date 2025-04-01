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
import solver.SolverResult
import spec.cvlast.SpecType


/**
 * Represents a sanity check's sort properties, such as how results are grouped,
 * what is the severity of this sort, corresponding UI messages.
 * This class is parametric in the [RuleCheckResult] ([S]) which is the type of the non error [RuleCheckResult]s,
 * and in the atomic sub-check criterion ([G]).
 * An example for an atomic sub-check is redundancy of a specific require (so the require itself is the criterion).
 */
sealed interface SanityCheckSort<in S : RuleCheckResult.Single, G> {

    companion object {

        /**
         * Maps rule type to its corresponding sanity check sort.
         */
        operator fun invoke(type: SpecType.Single.GeneratedFromBasicRule.SanityRule) =
            when (type) {
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.RedundantRequireCheck -> {
                    RedundantRequires
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertTautologyCheck -> {
                    AssertsTautology
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.TrivialInvariantCheck -> {
                    TrivialInvariant
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck -> {
                    Vacuity
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.LeftOperand-> {
                    AssertionsStructureLeftOperand(type.expr)
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.RightOperand -> {
                    AssertionsStructureRightOperand(type.expr)
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothTrue -> {
                    AssertionsStructureIFFBothTrue(type.expr)
                }
                is SpecType.Single.GeneratedFromBasicRule.SanityRule.AssertionStructureCheck.IFFBothFalse -> {
                    AssertionsStructureIFFBothFalse(type.expr)
                }
            }
    }

    val mode: SanityValues

    val reportName: String

    /**
     * The [SanityCheckSeverity] level of this [SanityCheckSort].
     */
    val severityLevel: SanityCheckSeverity

    /**
     * Immediate dependencies (predecessors) of this [SanityCheckSort].
     * These are the rules whose results are required to compute the result
     * of this sanity check.
     */
    val preds: List<SanityCheckNodeType>

    /**
     * Checks if [other] is a predecessor of this sort.
     * Allows dependency check based on sort's properties, for example - two sanity checks that check an assert
     * can be considered dependent only if they check the same assert.
     */
    fun dependsOnOther(
        otherType: SanityCheckNodeType
    ): Boolean = preds.contains(otherType)

    /**
     * Tries to compute the [SolverResult] of this sanity-check's rule ([rule]) from the predecessors' results
     * [predsResults]. Returns null if it is unable to compute the result.
     */
    fun concludeResultFromPredsOrNull(predsResults: Map<SanityCheckNode, SanityDPResult>): SolverResult?


    /**
     * Extracts the corresponding "sanity sub-check group" from the given [RuleCheckResult].
     * "sanity sub-check group" characterizes a subset of sanity checks whose results should
     * be summarized or aggregated later into a single sanity-check result.
     */
    fun checkResultToSanitySubCheckGroup(r: SanityDPResult): G

    /**
     * Determines how sanity-check results ([S]) should be viewed (in terms of [SanityCheckResultOrdinal]s)
     * in the context of this [SanityCheckSort].
     */
    fun checkResultToSanityResultOrd(s: DPSuccess<S>): SanityCheckResultOrdinal

    /**
     * Returns any extra details from the sanity-check results ([S]).
     */
    fun checkResultToDetailsStr(s: DPSuccess<S>): Result<String>

    /**
     * Determines the UI messages of non-error results used for checking
     * this [SanityCheckSort].
     */
    val nonErrorUIMessageFormatter: SanityCheckNonErrorUIMessageFormatter<G>

    /**
     * Returns a (formatted) UI message for the given non-error sanity-check result ([sanityCheckNonErrorResult]).
     */
    fun nonErrorUIMessageOf(sanityCheckNonErrorResult: DPSuccess<S>): SanityCheckNonErrorUIMessage =
        nonErrorUIMessageFormatter.nonErrorUIMessageOf(
            sanityCheckNonErrorResult.computationalTyp,
            severityLevel,
            checkResultToSanityResultOrd(sanityCheckNonErrorResult),
            checkResultToSanitySubCheckGroup(sanityCheckNonErrorResult),
            checkResultToDetailsStr(sanityCheckNonErrorResult)
        )

    /**
     * A sort of sanity-check whose result may change when checked for different methods
     * in the case of parametric rules.
     */
    sealed interface FunctionDependent<S : RuleCheckResult.Single, G> :
        SanityCheckSort<S, G> {

        /**
         * Given [_sanityCheckResults], a list of ALL sanity checks' results
         * (which usually should originate from a [SanityResultsContainer]),
         * and [_baseResults], a list of ALL the base rules (namely, all the rules whose sanity is being checked),
         * returns a "view" ([SanityResultsView]) on this results' list w.r.t. this [SanityCheckSort].
         */
        fun toSanityResultsView(
            _baseResults: List<SanityDPResult>,
            _sanityCheckResults: List<SanityDPResult>
        ): SanityResultsView.FunctionDependent<S>

    }

    /**
     * A sort of sanity-check whose result can be obtained without checking it separately
     * for each method in the case of parametric rules.
     */
    sealed interface FunctionIndependent<S : RuleCheckResult.Single, G> :
        SanityCheckSort<S, G> {

        /**
         * Given [_sanityCheckResults], a list of ALL sanity checks' results (which usually should originate from a [SanityResultsContainer]),
         * returns a "view" ([SanityResultsView]) on this results' list w.r.t. this [SanityCheckSort].
         */
        fun toSanityResultsView(
            _sanityCheckResults: List<SanityDPResult>
        ): SanityResultsView.FunctionIndependent<S>

    }
}
