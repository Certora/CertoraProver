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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence

import analysis.ip.InternalFunctionExitAnnot
import analysis.ip.InternalFunctionStartAnnot
import bridge.SourceLanguage
import config.ReportTypes
import datastructures.stdcollections.*
import log.*
import scene.*
import spec.cvlast.QualifiedMethodSignature
import vc.data.CoreTACProgram
import vc.data.TACStructureException
import verifier.*
import verifier.equivalence.EquivalenceChecker.Companion.resolve
import verifier.equivalence.summarization.PureFunctionExtraction
import verifier.equivalence.summarization.SharedPureSummarization

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Handles the equivalence checker specific preprocessing.
 * Currently this is read numbering, summarization, loop unrolling, and some canonicalization.
 */
object EquivalencePreprocessor {

    /**
     * Summarizes the [sharedSigs] functions in [methodA] and [methodB]. This is done all at once via [SharedPureSummarization],
     * falling back on a (slower) sequential process if that throws an exception.
     */
    private fun trySummarize(
        sharedSigs: Collection<QualifiedMethodSignature>,
        methodA: TACMethod,
        methodB: TACMethod
    ) : Pair<CoreTACProgram, CoreTACProgram> {
        val sigs = sharedSigs.toList().mapIndexed { index, q ->
            q to index
        }
        val summarizer = SharedPureSummarization(sigs)
        try {
            logger.info {
                "Trying to batch summarize common pure functions"
            }
            val codeA = summarizer.summarize(methodA.code as CoreTACProgram)
            val codeB = summarizer.summarize(methodB.code as CoreTACProgram)
            return codeA to codeB
        } catch(e: Exception) {
            when(e) {
                is TACStructureException, is SharedPureSummarization.SummaryApplicationError -> {
                    logger.warn(e) {
                        "Failed to summarize batch, falling back on sequential"
                    }
                }
                else -> throw e
            }
        }
        var codeAIt = methodA.code as CoreTACProgram
        var codeBIt = methodB.code as CoreTACProgram
        for(s in sigs) {
            logger.info {
                "Trying to summarize ${s.first.prettyPrintFullyQualifiedName()}"
            }
            try {
                val nextA = SharedPureSummarization(listOf(s)).summarize(codeAIt)
                val nextB = SharedPureSummarization(listOf(s)).summarize(codeBIt)
                codeAIt = nextA
                codeBIt = nextB
            } catch(@Suppress("TooGenericExceptionCaught") e: Exception) {
                when(e) {
                    is TACStructureException, is SharedPureSummarization.SummaryApplicationError -> {
                        logger.warn(e) {
                            "Failed to summarize ${s.first.prettyPrintFullyQualifiedName()}, skipping"
                        }
                    }
                    else -> throw e
                }
            }
        }
        return codeAIt to codeBIt
    }

    /**
     * Does the common internal summarization process using the (language specific) pure function extractor [extract].
     */
    private fun <T: InternalFunctionStartAnnot, U: InternalFunctionExitAnnot, R: PureFunctionExtraction.CallingConvention<R>> commonInternalSummarization(
        scene: IScene,
        methodAForAnalysis: TACMethod,
        methodBForAnalysis: TACMethod,
        extract: PureFunctionExtraction.PureFunctionExtractor<T, U, R>
    ) {
        val methodBPure = PureFunctionExtraction.canonicalPureFunctionsIn(methodBForAnalysis, extract)
        val methodAPure = PureFunctionExtraction.canonicalPureFunctionsIn(methodAForAnalysis, extract)

        val shared = methodBPure.filter { (qB, progA) ->
            methodAPure.any { (qA, progB) ->
                qA.matchesNameAndParams(qB) && progA equivTo progB
            }
        }.mapTo(mutableSetOf()) { it.sig }
        logger.info {
            "The following pure functions were found in common:"
        }
        if(logger.isInfoEnabled) {
            for(q in shared) {
                logger.info {
                    "\t* ${q.toNamedDecSignature()}"
                }
            }
        }

        val (coreA, coreB) = trySummarize(
            methodA = methodAForAnalysis,
            methodB = methodBForAnalysis,
            sharedSigs = shared
        )
        fun ITACMethod.earlySummaryUpdate(newCore: CoreTACProgram) = ContractUtils.transformMethodInPlace(this, ChainedMethodTransformers(listOf(
            CoreToCoreTransformer(ReportTypes.EARLY_SUMMARIZATION) { _ ->
                newCore
            }.lift()
        )))

        scene.mapContractMethodsInPlace("equiv_summarization") { _, method ->
            if(method.sigHash == methodAForAnalysis.sigHash && method.getContainingContract().instanceId == methodAForAnalysis.getContainingContract().instanceId) {
                method.earlySummaryUpdate(coreA)
            } else if(method.sigHash == methodBForAnalysis.sigHash && method.getContainingContract().instanceId == methodBForAnalysis.getContainingContract().instanceId) {
                method.earlySummaryUpdate(coreB)
            }
        }

    }

    fun preprocess(scene: IScene, query: ProverQuery.EquivalenceQuery) {
        val (methodAForAnalysis, methodBForAnalysis) = scene.resolve(query)
        val langA = (methodAForAnalysis.getContainingContract() as? IContractWithSource)?.src?.lang
        val langB = (methodBForAnalysis.getContainingContract() as? IContractWithSource)?.src?.lang

        val extractor = if(langA == langB && langA == SourceLanguage.Solidity) {
            PureFunctionExtraction.SolidityExtractor
        } else {
            null
        }
        if(extractor != null) {
            commonInternalSummarization(scene, methodAForAnalysis, methodBForAnalysis, extractor)
        }

        /**
         * Adds some equivalence checker specific normalizations. These aren't useless for the "regular" flow
         */
        scene.mapContractMethodsInPlace("equiv_normalization") { _, method ->
            ContractUtils.transformMethodInPlace(method as TACMethod, ChainedMethodTransformers(listOf(
                CoreToCoreTransformer(ReportTypes.SIGHASH_PACKING_NORMALIZER, SighashPackingNormalizer::doWork).lift(),
                CoreToCoreTransformer(ReportTypes.SIGHASH_READ_NORMALIZER, SighashReadNormalizer::doWork).lift()
            )))
        }

        IntegrativeChecker.runLoopUnrolling(scene)
        scene.mapContractMethodsInPlace("initial_postInline") { _, method ->
            val isLibrary = (method.getContainingContract() as? IContractWithSource)?.src?.isLibrary == true
            if(isLibrary) {
                return@mapContractMethodsInPlace
            }
            ContractUtils.transformMethodInPlace(
                method,
                ContractUtils.tacOptimizations()
            )
        }
        /**
         * Give a unique numbering to all mloads [MemoryReadNumbering]. This helps identify reads that need to have a bounded precision window.
         *
         * Further, annotate buffers for which we can statically determine the writes which define their contents [DefiniteBufferConstructionAnalysis].
         */
        scene.mapContractMethodsInPlace("read_numbering") { _, method ->
            ContractUtils.transformMethodInPlace(method, ChainedMethodTransformers(listOf(
                CoreToCoreTransformer(ReportTypes.READ_NUMBERING, MemoryReadNumbering::instrument).lift(),
                CoreToCoreTransformer(ReportTypes.DEFINITE_BUFFER_ANALYSIS, DefiniteBufferConstructionAnalysis::instrument).lift()
            )))
        }

    }
}
