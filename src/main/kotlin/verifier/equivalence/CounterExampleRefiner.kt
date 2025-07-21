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

import analysis.CmdPointer
import analysis.annotationView
import datastructures.stdcollections.*
import log.*
import solver.SolverResult
import spec.rules.EquivalenceRule
import utils.*
import verifier.equivalence.CEXUtils.toList
import verifier.equivalence.StaticBufferRefinement.fmtError
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.TraceEvent
import verifier.equivalence.data.TraceWithContext
import verifier.equivalence.tracing.BufferTraceInstrumentation
import java.math.BigInteger

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Responsible for trying to refine a counterexample described in [cexContext]. The counter example was found
 * at [eventIdx] in the trace.
 */
class CounterExampleRefiner<I> private constructor(
    private val cexContext: CounterExampleContext<I>,
    private val eventIdx: BigInteger
) : IWithQueryContext {

    override val queryContext: EquivalenceQueryContext
        get() = cexContext.queryContext

    private val instrumentationControl get() = cexContext.instrumentationSettings
    private val res get() = cexContext.checkResult

    private fun <I, M: MethodMarker> extractEventData(
        res: TraceEquivalenceChecker.CheckResult<I>,
        instrumentation: TraceEquivalenceChecker.Instrumentation<M, I>,
        eventIndex: BigInteger
    ) : TraceEvent<M>? {
        return res.vcProgram.parallelLtacStream().filter {
            it.ptr.block.calleeIdx == instrumentation.inlinedCallId && it.ptr.block in res.theModel.reachableNBIds
        }.mapNotNull {
            it.annotationView(BufferTraceInstrumentation.TraceIndexMarker.META_KEY)
        }.filter { annot ->
            res.theModel.valueAsBigInteger(annot.annotation.indexVar).leftOrNull() == eventIndex
        }.toList().singleOrNull()?.let { la ->
            val origSite = instrumentation.instrumentationResult.useSiteInfo.keysMatching { _, info ->
                info.id == la.annotation.id
            }.single()
            TraceEvent(
                origProgramSite = origSite,
                vcProgramSite = la.ptr,
                marker = la.annotation
            )
        }
    }

    /**
     * Result of the refinment process.
     */
    sealed interface RefinementResult {
        /**
         * Refinement should be attempted by increasing the bounded precision window for the read(s) at
         * [refineA] and/or [refineB]. A non-null pair means that the read at the given [CmdPointer] should
         * use the associated bounded precision window. NB that at least one of [refineA] or [refineB] are non-null.
         */
        data class MemoryCellRefinement(
            val refineA: Pair<CmdPointer, Int>?,
            val refineB: Pair<CmdPointer, Int>?
        ) : RefinementResult

        /**
         * Refinement should be attempted after recording that the buffer reads at [siteA] and [siteB] are actually equivalent,
         * as proven post-hoc.
         */
        data class BufferRefinement(
            val siteA: CmdPointer,
            val siteB: CmdPointer
        ) : RefinementResult

        /**
         * Refinement wasn't possible for the reason given in [why].
         */
        data class GaveUp(val why: String): RefinementResult

        /**
         * We found the offending event site(s) in program A and B,
         * but could not refine them. One of [traceSiteA] or [traceSiteB] being null
         * indicates that the trace event never actually occurred in that program, i.e.,
         * program A has an event that B does not, or vice versa.
         */
        data class UnrefinableCEX(
            val traceSiteA: TraceEvent<MethodMarker.METHODA>?,
            val traceSiteB: TraceEvent<MethodMarker.METHODB>?
        ) : RefinementResult
    }

    private fun <T: MethodMarker> tryExtractBufferModel(
        res: TraceEquivalenceChecker.CheckResult<*>,
        event: TraceEvent<T>
    ) : Either<List<UByte>, String> {
        return res.theModel.valueAsBigInteger(event.marker.lengthVar).fmtError().bindLeft { len ->
            res.theModel.valueAsBigInteger(event.marker.bufferStart).fmtError().bindLeft { base ->
                PreciseBufferExtraction.extractBufferModel(
                    graph = res.vcProgram.analysisCache.graph,
                    model = res.theModel,
                    where = event.vcProgramSite,
                    len = len,
                    start = base,
                    buffer = event.marker.bufferBase
                ).toList(len)
            }
        }
    }

    companion object {
        suspend fun <I> tryRefineCounterExample(
            cexContext: CounterExampleContext<I>,
            eventIdx: BigInteger,
            rule: EquivalenceRule
        ) = CounterExampleRefiner(
            cexContext,
            eventIdx
        ).tryRefine(rule)
    }

    /**
     * Private entry point of refinment
     */
    private suspend fun tryRefine(rule: EquivalenceRule) : RefinementResult {
        val methodAEvent = extractEventData(
            res,
            res.programA,
            eventIdx
        )
        val methodBEvent = extractEventData(
            res,
            res.programB,
            eventIdx
        )
        if(methodAEvent == null && methodBEvent  == null) {
            return RefinementResult.GaveUp("Both event information is missing")
        }

        /*
         * Let's try to refine counter examples first
         */
        val aOverride = MemoryImprecisionAnalyzer.analyze(
            res.theModel,
            res.vcProgram,
            res.programA
        )
        val bOverride = MemoryImprecisionAnalyzer.analyze(
            res.theModel,
            res.vcProgram,
            res.programB
        )
        logger.debug {
            "CEX analysis yielded: $aOverride and $bOverride"
        }
        // retry with more precision
        if(aOverride != null || bOverride != null) {
            return RefinementResult.MemoryCellRefinement(
                aOverride, bOverride
            )
        }
        // this means we have a log/call in one method but not the other
        if(methodAEvent == null || methodBEvent == null) {
            check(instrumentationControl.traceTarget != BufferTraceInstrumentation.TraceTargets.Results)
            return RefinementResult.UnrefinableCEX(
                methodAEvent, methodBEvent
            )
        }

        /**
         * Did the buffer hashes differ in the mismatch?
         */
        val bufferHashDiffers = res.theModel.valueAsBigInteger(methodAEvent.marker.bufferHash).leftOrNull()?.let { aHash ->
            res.theModel.valueAsBigInteger(methodBEvent.marker.bufferHash).leftOrNull()?.let { bHash ->
                aHash == bHash
            }
        } == false
        if(!bufferHashDiffers) {
            return RefinementResult.UnrefinableCEX(
                traceSiteA = methodAEvent,
                traceSiteB = methodBEvent
            )
        }
        /**
         * If so, was this legit?
         */
        val aBufferDataE = tryExtractBufferModel(res, methodAEvent)
        val bBufferDataE = tryExtractBufferModel(res, methodBEvent)
        val exactBuffersSame = aBufferDataE.bindLeft { aBuffer ->
            bBufferDataE.mapLeft { bBuffer ->
                aBuffer == bBuffer
            }
        }.leftOrNull() == true
        /**
         * If not, see if we can prove they are always equal using [StaticBufferRefinement].
         */
        if(!exactBuffersSame) {
            return RefinementResult.UnrefinableCEX(methodAEvent, methodBEvent)
        }
        logger.debug {
            "Exact buffer model shows buffers are the same: attempting to refine"
        }
        val (aRefine, bRefine) = StaticBufferRefinement.tryRefineBuffers(
            aTraceAndContext = TraceWithContext(
                context = res.programA,
                event = methodAEvent
            ),
            bTraceAndContext = TraceWithContext(
                context = res.programB,
                event = methodBEvent
            ),
            queryContext = queryContext,
            targetEvents = instrumentationControl.traceTarget
        ) ?: return RefinementResult.UnrefinableCEX(methodAEvent, methodBEvent)
        /**
         * We aren't done just because the extraction works, we need to prove the contents equal;
         * use the solver
         */
        val r = StaticBufferEqualityChecker(
            cexContext = cexContext,
            refineB = bRefine,
            refineA = aRefine
        ).checkStaticEquivalence(rule = rule)
        return if(r.rawSolverResult.finalResult == SolverResult.UNSAT) {
            RefinementResult.BufferRefinement(
                methodAEvent.origProgramSite, methodBEvent.origProgramSite
            )
        } else {
            RefinementResult.UnrefinableCEX(
                methodAEvent, methodBEvent
            )
        }
    }

}
