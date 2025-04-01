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

package rules.sanity

import rules.RuleCheckResult
import rules.sanity.SanityCheckResult.Companion.join
import rules.sanity.sorts.SanityCheckSort
import spec.rules.CVLSingleRule
import spec.cvlast.SpecType

/**
 * A view of the non-error results (of type [S]) of a given sanity-check whose sort is [sanityCheckSort].
 * Additionally, [sanityErrorResults] contains all the associated erroneous results (if there are any).
 * [SanityResultsView]s should be obtained from a [SanityResultsContainer] using its [SanityResultsContainer.narrow]
 * function.
 */
sealed class SanityResultsView<S : RuleCheckResult.Single> {

    abstract val sanityNonErrorResults: List<DPSuccess<S>>
    abstract val sanityErrorResults: List<DPError>
    abstract val sanityCheckSort: SanityCheckSort<S, *>

    /**
     * The aggregated or summarized result of the sanity check associated this view ([SanityResultsView]), whose
     * sort is [sanityCheckSort].
     */
    abstract val aggregatedSanityCheckResult: SanityCheckResult

    /**
     * For the sanity check of type [sanityCheckSort], take all the results of that type for all base rules,
     * partition them into groups using [SanityCheckSort.checkResultToSanitySubCheckGroup]
     * (each such group denotes an "atomic sub-check"), aggregate/summarize whether each "atomic sub-check" has failed,
     * using the summarization scheme (e.g., for-all or exists quantification)
     * prescribed by the [SanityCheckSort.severityLevel] of [sanityCheckSort];
     * finally, we decide that sanity check [sanityCheckSort] has failed, iff
     * any of those "atomic sub-checks" have failed.
     */
    protected fun computeAggregatedSanityCheckResult(
        _subChecksGroupsNonErrorResults: Map<*, List<DPSuccess<S>>>,
        _subChecksGroupsErrorResults: Map<*, List<DPError>>
    ): SanityCheckResult {
        val subCheckGroupToCheckResult: Map<*, SanityCheckResult> =
            _subChecksGroupsNonErrorResults.mapValues { (sanitySubCheckGroup, groupNonErrorResults) ->
                val groupErrorResults = _subChecksGroupsErrorResults[sanitySubCheckGroup] ?: emptyList()
                sanityCheckSort.severityLevel.computeSanityCheckResult(
                    groupNonErrorResults,
                    groupErrorResults,
                    sanityCheckSort
                )
            }
        return subCheckGroupToCheckResult.join()
    }

    protected fun computeAggregatedSanityCheckResult(): SanityCheckResult =
        computeAggregatedSanityCheckResult(
            sanityNonErrorResults.groupBy { sanityCheckSort.checkResultToSanitySubCheckGroup(it) },
            sanityErrorResults.groupBy { sanityCheckSort.checkResultToSanitySubCheckGroup(it) }
        )

    companion object {

        /**
         * Returns a partition of [results] to erroneous and non-erroneous
         * results.
         */
        inline fun <reified S : RuleCheckResult.Single> partitionToSAndErrors(
            _matchingSanityCheckResults: List<SanityDPResult>
        ): Pair<List<DPSuccess<S>>, List<DPError>> =
            _partitionToSAndErrors(_matchingSanityCheckResults)

        inline fun <reified S : RuleCheckResult.Single, reified SUCC: DPSuccess<S>>
            _partitionToSAndErrors(_matchingSanityCheckResults: List<SanityDPResult>):
            Pair<List<SUCC>, List<DPError>> =
            (mutableListOf<SUCC>() to mutableListOf<DPError>()).also { (sResults, errors) ->
                _matchingSanityCheckResults.forEach { result ->
                    when (result) {
                        is DPError -> {
                            errors.add(result)
                        }
                        is SUCC -> {
                            sResults.add(result)
                        }
                        else -> {
                            throw IllegalArgumentException(
                                "Did not expect a result of type ${result.result.javaClass} (got $result)"
                            )
                        }
                    }
                }
            }
    }

    /**
     * A view of a sanity-check whose result can be obtained without checking it separately
     * for each method in the case of parametric rules.
     */
    data class FunctionIndependent<S : RuleCheckResult.Single>(
        override val sanityNonErrorResults: List<DPSuccess<S>>,
        override val sanityErrorResults: List<DPError>,
        override val sanityCheckSort: SanityCheckSort.FunctionIndependent<S, *>
    ) : SanityResultsView<S>() {

        override val aggregatedSanityCheckResult: SanityCheckResult = computeAggregatedSanityCheckResult()

        companion object {
            /**
             * Effectively the constructor of [FunctionIndependent].
             */
            inline operator fun <reified S : RuleCheckResult.Single, reified R : SpecType.Single.GeneratedFromBasicRule, G>
                    invoke(
                _sanityCheckResults: List<SanityDPResult>,
                _sanityCheckSort: SanityCheckSort.FunctionIndependent<S, G>
            ): FunctionIndependent<S> {
                val (sanityNonErrorResults, sanityErrorResults) = partitionToSAndErrors<S>(
                    _sanityCheckResults.filter { it.result.rule.ruleType is R }
                )
                return FunctionIndependent(
                    sanityNonErrorResults = sanityNonErrorResults,
                    sanityErrorResults = sanityErrorResults,
                    sanityCheckSort = _sanityCheckSort
                )
            }
        }
    }

    /**
     * A view of a sanity-check whose result may change when checked for different methods
     * in the case of parametric rules.
     */
    data class FunctionDependent<S : RuleCheckResult.Single>(
        val baseToMatchingNonErrorResults: Map<SanityDPResult, List<DPSuccess<S>>>,
        val baseToMatchingErrorResults: Map<SanityDPResult, List<DPError>>,
        override val sanityNonErrorResults: List<DPSuccess<S>>,
        override val sanityErrorResults: List<DPError>,
        override val sanityCheckSort: SanityCheckSort.FunctionDependent<S, *>
    ) : SanityResultsView<S>() {

        override val aggregatedSanityCheckResult: SanityCheckResult = computeAggregatedSanityCheckResult()

        val baseResultToSanityCheckResultMap: Map<SanityDPResult, SanityCheckResult> =
            baseToMatchingNonErrorResults.mapValues { (baseResult, matchingNonErrorResults) ->
                val matchingErrorResults: List<DPError> =
                    baseToMatchingErrorResults.getValue(baseResult)
                computeAggregatedSanityCheckResult(
                    /* TODO if we have more than one result in each value in the grouped map,\
                        how should we aggregate the results? is it also by using forall/exists depending\
                        on the severity of the check, or is it always exists?
                    */
                    matchingNonErrorResults.groupBy { sanityCheckSort.checkResultToSanitySubCheckGroup(it) },
                    matchingErrorResults.groupBy { sanityCheckSort.checkResultToSanitySubCheckGroup(it) }
                )
            }

        companion object {

            /**
             * Returns all the results corresponding to [_baseResult] in [_sanityCheckResults], each of which has
             * a rule of type [R].
             * [_baseResult] is assumed to encapsulate a [CVLSingleRule].
             * Similarly, each [RuleCheckResult] in [_sanityCheckResults] is assumed to encapsulate a [CVLSingleRule].
             * Correspondence is determined by comparing the declaration ids.
             */
            inline fun <reified R : SpecType.Single.GeneratedFromBasicRule> matchingSanityRuleRes(
                _baseResult: SanityDPResult,
                _sanityCheckResults: List<SanityDPResult>,
            ): List<SanityDPResult> {
                val baseRule: CVLSingleRule = _baseResult.result.rule as? CVLSingleRule ?: throw IllegalArgumentException(
                    "Expected ${
                        _baseResult.result.rule.declarationId
                    } to be a [CVLSingleRule] (got ${
                        _baseResult.result.rule.javaClass
                    })"
                )
                return _sanityCheckResults.filter { sanityResult ->
                    if (sanityResult.result.rule.ruleType is R) {
                        val sanityRule: CVLSingleRule =
                            sanityResult.result.rule as? CVLSingleRule ?: throw IllegalArgumentException(
                                "Expected ${
                                    sanityResult.result.rule.declarationId
                                } to be a [CVLSingleRule] (got ${
                                    sanityResult.result.rule.javaClass
                                })"
                            )
                        matchingSanityRule(baseRule, sanityRule)
                    } else {
                        false// sanityResult.rule.ruleType is not [R]
                    }
                }
            }

            /**
             * Effectively the constructor of [FunctionDependent].
             */
            inline operator fun <reified S : RuleCheckResult.Single, reified R : SpecType.Single.GeneratedFromBasicRule, G>
                    invoke(
                _baseResults: List<SanityDPResult>,
                _sanityCheckResults: List<SanityDPResult>,
                _sanityCheckSort: SanityCheckSort.FunctionDependent<S, G>
            ): FunctionDependent<S> {
                val sanityCheckResults = mutableListOf<DPSuccess<S>>()
                val sanityErrorResults = mutableListOf<DPError>()
                val baseToMatchingNonErrorResults: MutableMap<SanityDPResult, List<DPSuccess<S>>> = mutableMapOf()
                val baseToMatchingErrorResults: MutableMap<SanityDPResult, List<DPError>> =
                    mutableMapOf()

                _baseResults.forEach { _baseResult ->
                    val (matchingResults, matchingErrors) = partitionToSAndErrors<S>(
                        matchingSanityRuleRes<R>(
                            _baseResult,
                            _sanityCheckResults
                        )
                    )
                    baseToMatchingNonErrorResults[_baseResult] = matchingResults
                    baseToMatchingErrorResults[_baseResult] = matchingErrors
                    sanityErrorResults.addAll(matchingErrors)
                    sanityCheckResults.addAll(matchingResults)
                }

                return FunctionDependent(
                    baseToMatchingNonErrorResults = baseToMatchingNonErrorResults,
                    baseToMatchingErrorResults = baseToMatchingErrorResults,
                    sanityNonErrorResults = sanityCheckResults,
                    sanityErrorResults = sanityErrorResults,
                    sanityCheckSort = _sanityCheckSort
                )
            }
        }
    }
}
