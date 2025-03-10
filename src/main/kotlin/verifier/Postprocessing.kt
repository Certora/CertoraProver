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

import config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import log.*
import smt.CounterExampleDiversifier
import smt.MultipleCEXStrategyEnum
import smt.PrettifyCEXEnum
import smtlibutils.cmdprocessor.*
import smtlibutils.data.SatResult
import vc.gen.LExpVC
import java.nio.channels.ClosedByInterruptException

private val logger = Logger(LoggerTypes.SOLVERS)

suspend fun postprocessResult(vc: LExpVC, result: SmtFormulaCheckerResultWithChecker, config: LExpVcPostprocessingConfig): LExpVcChecker.RunSolverResult {

    val prettifierStats = CompletableDeferred<PrettifierStatistics>()
    val diversifierStats = CompletableDeferred<DiversifierStatistics>()
    val results = flow {
        try {
            val checkResult = result.result
            /* There is nothing to postprocess if the result is not SAT or there is no model. */
            if (checkResult.satResult !is SatResult.SAT || checkResult.getValuesResult == null) {
                emit(checkResult)
                return@flow
            }
            /* All postprocessing is disabled */
            if (!config.doCEXPostProcessing) {
                emit(checkResult)
                return@flow
            }

            /**
             * Get the actual result, in case [result] is an aggregated result.
             */
            val smtResult = when (checkResult) {
                is SmtFormulaCheckerResult.Agg.LExpVcCheckerResult -> if (checkResult.finalRes != null) {
                    checkResult.finalRes
                } else {
                    emit(checkResult)
                    return@flow
                }

                is SmtFormulaCheckerResult.Single.Simple -> checkResult
                else -> throw IllegalStateException("Can only postprocess Agg or Single.Simple results")
            } as SmtFormulaCheckerResult.Single.Simple

            val oldChecker: SimpleFormulaChecker = when (result.checker) {
                null -> throw IllegalStateException("Can only postprocess if a checker was used")
                is SimpleFormulaChecker -> result.checker as SimpleFormulaChecker
                else -> throw IllegalStateException("Unexpected checker type: ${result.checker}")
            }

            /**
             * The prettifier manages some aspects the postprocessing of the model.
             */
            val prettifier = LExpVCSATResultPrettifier(config, vc)
            val (totalTimeout, singleTimeout) = prettifier.getTimeouts(smtResult)

            /**
             * In certain cases, we need to create a new solver (i.e. to reset the timeout) when we need to do a lot
             * of computation (e.g., for extensive prettification or multiple counter examples). This gives the
             * prettifier the opportunity to do that.
             */
            val (checker, newResult) =
                prettifier.getResultToPrettify(oldChecker, smtResult.query, singleTimeout) ?: (oldChecker to smtResult)
            try {
                /** Hold a context for the query processor, just in case we want to reuse it after postprocessing */
                val prettify: ResultPostprocessor =
                    if (config.prettifyCEX != PrettifyCEXEnum.NONE && !vc.tacProgramMetadata.isSanityRule) {
                        val (termsToPrettify, prioritisedTermsToPrettify) = if (Config.HashingBoundDetectionMode.get()) {
                            prettifier.getHashingBoundConstraintsToPrettify().let { it to it }
                        } else {
                            prettifier.getTermsToPrettify().let { it to prettifier.getPrioritisedTerms(it) }
                        }
                        logger.debug {
                            "prettifyCEX: picked ${termsToPrettify.size} out of " +
                                "${checkResult.getValuesResult?.size} values as candidates for adjustment, " +
                                "including ${prioritisedTermsToPrettify.size} prioritised terms: $prioritisedTermsToPrettify"
                        };
                        { cex: SmtFormulaCheckerResult.Single.Simple, context: SmtQueryProcessorContext ->
                            try {
                                // prettification may start another singleTimeout-check right before totalTimeout
                                // elapses. We don't want to interfere with the internal abortion mechanism.
                                withTimeout(totalTimeout + singleTimeout) {
                                    prettifier.prettifyCounterExample(
                                        cex,
                                        cex.query,
                                        checker,
                                        context,
                                        termsToPrettify,
                                        prioritisedTermsToPrettify,
                                        totalTimeout,
                                        config.prettifyCEX == PrettifyCEXEnum.JOINT ||
                                            config.prettifyCEX == PrettifyCEXEnum.EXTENSIVE,
                                    )
                                }
                            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                                when (e) {
                                    is CancellationException,
                                    is ClosedByInterruptException ->
                                        // The internal timeout mechanism has apparently failed and [withTimeout] triggered.
                                        logger.warn { "model prettification exceeded timeout (${totalTimeout.inWholeSeconds}s), returning unprettified result." }

                                    else ->
                                        logger.warn(e) { "model prettification failed due to an exception, returning unprettified result." }
                                }
                                cex
                            }
                        }
                    } else {
                        { cex: SmtFormulaCheckerResult.Single.Simple, _ -> cex }
                    }

                /**
                 * Depending on the value of [config.multipleCEX], we compute a diverse set of counter examples or
                 * keep only the single result we already have.
                 */
                val cexSequence =
                    if (config.multipleCEX != MultipleCEXStrategyEnum.NONE) {
                        try {
                            val diversifier =
                                CounterExampleDiversifier(
                                    checker,
                                    newResult.getValuesResult!!,
                                    smtResult.query,
                                    vc.tacProgram
                                )
                            diversifier.getSequencedCEXs(totalTimeout, prettify).onEmpty {
                                emit(newResult)
                            }.onCompletion {
                                prettifierStats.complete(prettifier.getStatistics())
                                diversifierStats.complete(diversifier.getStatistics())
                            }
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            logger.warn { "diversification aborted with: ${e.message}" }
                            flowOf(newResult)
                        }
                    } else {
                        flowOf(newResult).map {
                            prettify(it, checker.getContext())
                        }.onCompletion {
                            prettifierStats.complete(prettifier.getStatistics())
                        }
                    }
                emitAll(cexSequence)
            } finally {
                checker.close()
            }
        } finally {
            result.checker?.close()
        }
    }
    return LExpVcChecker.RunSolverResult(result, results, prettifierStats, diversifierStats)
}
