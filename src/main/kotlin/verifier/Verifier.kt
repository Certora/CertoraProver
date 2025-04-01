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

package verifier

import analysis.TACCommandGraph
import config.Config
import datastructures.NonEmptyList
import log.*
import org.apache.commons.cli.UnrecognizedOptionException
import parallel.coroutines.launchMaybeBackground
import report.dumps.*
import rules.RuleCheckResult
import smt.PrettifyCEXEnum
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.data.ProcessDifficultiesResult
import solver.SMTCounterexampleModel
import solver.SolverResult
import spec.rules.IRule
import vc.FullProgramReachabilityResult
import vc.data.CoreTACProgram
import verifier.mus.UnsatCoreInputData
import verifier.splits.SplitAddress

abstract class Verifier : AbstractTACChecker() {

    data class VerifierResult(
        override val name: String,
        override val tac: CoreTACProgram,
        override val elapsedTimeSeconds: Long,
        override val reachabilityIndicator: FullProgramReachabilityResult?,
        val difficultyInfo: ProcessDifficultiesResult?,
        override val examplesInfo: NonEmptyList<ExampleInfo>,
        override val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?,
        override val unsolvedSplitsInfo: UnsolvedSplitInfo?,
        /** The collapsedTACGraph is different from the one contained in the [CoreTACProgram]. It goes through
         *  [UniqueSuccessorRemover.removeUniqueSuccessors] so it better corresponds to the verification condition.
         *  The current consumer of this graph is the unsat core computation, namely, the subsequent visualisation. */
        val collapsedTACGraph: TACCommandGraph,
        val unsolvedSplitsPrograms: Set<CoreTACProgram>?,
    ) : AbstractTACCheckerResult() {

        companion object {
            /** Used to create an UNSAT split result for splits that were loaded from the autoconfig cache. **/
            fun dummyUnsat(tac: CoreTACProgram): VerifierResult {
                return VerifierResult(
                    name = tac.name,
                    tac = tac,
                    elapsedTimeSeconds = 0,
                    reachabilityIndicator = null,
                    difficultyInfo = null,
                    examplesInfo = NonEmptyList(ExampleInfo(solverResult = SolverResult.UNSAT)),
                    unsatCoreSplitsData = null,
                    unsolvedSplitsInfo = null,
                    collapsedTACGraph = tac.analysisCache.graph,
                    unsolvedSplitsPrograms = null
                )
            }
            /** Used to create a VerifierResult in the case when there are no asserts in the tac program. */
            fun noAssertsInVc(
                tac: CoreTACProgram,
                model: SMTCounterexampleModel,
                elapsedTimeSeconds: Long,
                smtFormulaCheckerResult: SmtFormulaCheckerResult,
                collapsedTACGraph: TACCommandGraph,
            ): VerifierResult {
                if (Config.prettifyCEX.get() != PrettifyCEXEnum.NONE) {
                    // dump stats of counter example postprocessing
                    ((smtFormulaCheckerResult
                            as? SmtFormulaCheckerResult.Agg.LExpVcCheckerResult)?.finalRes
                            as? SmtFormulaCheckerResult.Single.Simple)?.postProcessModelResult?.let { res ->
                        Logger.always("${Config.prettifyCEX.name}: " + res.stats, true)
                    }
                }
                return VerifierResult(
                    name = tac.name,
                    tac = tac,
                    examplesInfo = NonEmptyList(ExampleInfo(model, smtFormulaCheckerResult)),
                    elapsedTimeSeconds = elapsedTimeSeconds,
                    difficultyInfo = null,
                    reachabilityIndicator = null,
                    unsatCoreSplitsData = null,
                    unsolvedSplitsInfo = null,
                    collapsedTACGraph = collapsedTACGraph,
                    unsolvedSplitsPrograms = null
                )
            }
        }

    }

    sealed interface JoinedResult: java.io.Serializable {
        val simpleSimpleSSATAC: CoreTACProgram
        val finalResult: SolverResult
        fun details(rule: IRule): String
        val examplesInfo: NonEmptyList<ExampleInfo>? get() = null
        val difficultyInfo: ProcessDifficultiesResult? get() = null
        val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>? get() = null
        val unsolvedSplitsData: UnsolvedSplitInfo? get() = null


        fun replaceTAC(ctp: CoreTACProgram) = when(this) {
            is Success -> copy(simpleSimpleSSATAC = ctp)
            is Failure -> copy(simpleSimpleSSATAC = ctp)
            is Timeout -> copy(simpleSimpleSSATAC = ctp)
            is SanityFail -> copy(simpleSimpleSSATAC = ctp)
            is Unknown -> copy(simpleSimpleSSATAC = ctp)
        }

        fun dropUnsatCoreData() = when (this) {
            is Success -> copy(unsatCoreSplitsData = null)
            is Failure -> this
            is Timeout -> copy(unsatCoreSplitsData = null)
            is SanityFail -> copy(unsatCoreSplitsData = null)
            is Unknown -> copy(unsatCoreSplitsData = null)
        }

        data class Success(
            override val simpleSimpleSSATAC: CoreTACProgram,
            val reachabilityIndicator: FullProgramReachabilityResult,
            override val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?,
        ) : JoinedResult {
            override val finalResult = SolverResult.UNSAT
            override fun details(rule: IRule): String = details()

            fun details(): String = reachabilityIndicator.details(simpleSimpleSSATAC)
        }

        data class Failure(
            override val simpleSimpleSSATAC: CoreTACProgram,
            override val difficultyInfo: ProcessDifficultiesResult?,
            override val examplesInfo: NonEmptyList<ExampleInfo>
        ) : JoinedResult {
            override val finalResult: SolverResult = SolverResult.SAT
            override fun details(rule: IRule): String = "\n$finalResult"
        }

        data class Timeout(
            override val simpleSimpleSSATAC: CoreTACProgram,
            override val unsolvedSplitsData: UnsolvedSplitInfo?,
            override val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?,
        ) : JoinedResult {
            override val finalResult = SolverResult.TIMEOUT
            override fun details(rule: IRule): String = "\n$finalResult"
        }

        data class SanityFail(
            override val simpleSimpleSSATAC: CoreTACProgram,
            override val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?,
        ) : JoinedResult {
            override val finalResult = SolverResult.SANITY_FAIL
            override fun details(rule: IRule): String = "\n$finalResult"
            override val examplesInfo: NonEmptyList<ExampleInfo>? get() = null
            override val difficultyInfo: ProcessDifficultiesResult? get() = null
        }

        data class Unknown(
            override val simpleSimpleSSATAC: CoreTACProgram,
            override val unsolvedSplitsData: UnsolvedSplitInfo?,
            override val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?,
        ) : JoinedResult {
            override val finalResult =  SolverResult.UNKNOWN
            override fun details(rule: IRule): String = "\n$finalResult"
            override val examplesInfo: NonEmptyList<ExampleInfo>? get() = null
            override val difficultyInfo: ProcessDifficultiesResult? get() = null
        }

        companion object {
            operator fun invoke(
                vResult: VerifierResult
            ): JoinedResult =
                when (vResult.finalResult) {
                    SolverResult.UNSAT -> Success(
                        vResult.tac,
                        vResult.reachabilityIndicator
                            ?: FullProgramReachabilityResult.NoUnreachableLeaves,
                        vResult.unsatCoreSplitsData,
                    )
                    SolverResult.SAT -> Failure(
                        vResult.tac,
                        vResult.difficultyInfo,
                        vResult.examplesInfo
                    )
                    SolverResult.TIMEOUT -> Timeout(
                        vResult.tac,
                        vResult.unsolvedSplitsInfo,
                        vResult.unsatCoreSplitsData
                    )
                    SolverResult.SANITY_FAIL -> SanityFail(vResult.tac, vResult.unsatCoreSplitsData)
                    SolverResult.UNKNOWN -> Unknown(
                        vResult.tac,
                        vResult.unsolvedSplitsInfo,
                        vResult.unsatCoreSplitsData
                    )
                }
        }

        /**
         * Generate a report describing the output of this execution. This report will also include the CodeMap graph
         */
        fun reportOutput(rule: IRule?) {
            val firstExampleInfo = examplesInfo?.head
            val name = simpleSimpleSSATAC.name
            val examplesInfo = examplesInfo

            if (examplesInfo != null && rule != null) {
                // We produce two reports: one with, and one without internal functions.
                writeExamplesToHtml(rule, name, examplesInfo, addInternalFunctions = false)
                writeExamplesToHtml(rule, name, examplesInfo, addInternalFunctions = true)
            }

            // Rules may either contain satisfy or assert commands
            // but not both. For rules containing satisfy, we need to
            // "Flip" how solver outputs are interpreted (i.e. SAT is good)
            // (Future implementations of CVL may
            // allow these to mix in a single rule in the language that
            // is presented to its users. However, to implement this,
            // we will still split these rules into two separate rules,
            // partitioning the satisfy and assert parts, under the hood)

            val goodSolverResult = if (rule != null && rule.isSatisfyRule) { SolverResult.SAT } else { SolverResult.UNSAT }
            val completeBadSolverResult = if (rule != null && rule.isSatisfyRule) { SolverResult.UNSAT } else { SolverResult.SAT }

            when (finalResult) {
                goodSolverResult, SolverResult.SANITY_FAIL -> if (Config.SpecFile.getOrNull() == null) {
                    if (finalResult == goodSolverResult) {
                        log(respectQuiet = false) { "Verified: $name" }
                        log(respectQuiet = true) { "${name}: Properties successfully verified on all inputs" }
                    } else {
                        log(respectQuiet = false) { "Sanity failed: $name" }
                    }
                }
                completeBadSolverResult -> if (Config.SpecFile.getOrNull() == null) {
                    log(respectQuiet = false) { "Violated: $name" }
                    if (ArtifactManagerFactory.isEnabled()) {
                        log(respectQuiet = true) { "${name}: A property is violated" }
                    }
                }
                SolverResult.UNKNOWN, SolverResult.TIMEOUT -> if (Config.SpecFile.getOrNull() == null) {
                    val msg: String = when {
                        firstExampleInfo?.reason != null -> "$name: ${firstExampleInfo.reason}"
                        finalResult == SolverResult.TIMEOUT -> "$name: Solver timed out"
                        firstExampleInfo?.errorMessage != null -> "$name: Solver failed ${firstExampleInfo.errorMessage}"
                        else -> "${name}: Solver failed"
                    }
                    log { msg }
                }
                // This should not happen because all the cases are actually
                // covered. The use of the vals goodSolverResult and
                // completeBadSolverResult interfere with Kotlin's
                // smart cast feature which would aid in figuring out
                // that this covers all cases otherwise.
                else -> throw UnrecognizedOptionException("Internal compiler encountered unhandled final result in AbstractTACChecker.")
            }
        }

        private fun writeExamplesToHtml(
            rule: IRule,
            name: String,
            examplesInfo: NonEmptyList<ExampleInfo>,
            addInternalFunctions: Boolean,
        ) {
            val codeMap = generateCodeMap(simpleSimpleSSATAC, name, addInternalFunctions)
            for ((exampleIndex, exampleInfo) in examplesInfo.withIndex()
                .filter { (_, exampleInfo) -> exampleInfo.solverResult == SolverResult.SAT }) {

                val withCex = addCounterexampleData(exampleInfo.model, codeMap)
                val fullCex = runCatching {
                    addDifficultyInfo(withCex, this)
                }.onFailure { e ->
                    Logger.alwaysWarn("Failed to enrich counter example", e)
                }.getOrElse { withCex }

                val reportName = RuleCheckResult.Single.RuleCheckInfo.cexDumpGraphLinkOf(
                    rule,
                    exampleIndex,
                    // If we have internal functions, the report will have the `_with_internal` suffix.
                    customSuffix = if (addInternalFunctions) { "_with_internal" } else { "" }
                )
                launchMaybeBackground("report generation") {
                    DumpGraphHTML.writeCodeToHTML(fullCex, reportName)
                }
            }
        }
    }
}
