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

import analysis.*
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_BITWIDTH256
import evm.EVM_BYTES_IN_A_WORD
import verifier.equivalence.tracing.BufferTraceInstrumentation
import log.*
import report.calltrace.CallInputsAndOutputs
import report.calltrace.calldataMovement
import report.calltrace.formatter.*
import report.calltrace.registerCalldataMovement
import rules.RuleCheckResult
import scene.TACMethod
import solver.CounterexampleModel
import spec.cvlast.VMParam
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import tac.NBId
import utils.*
import vc.data.*
import verifier.equivalence.EquivalenceChecker.Companion.pp
import verifier.equivalence.EquivalenceChecker.Companion.toList
import verifier.equivalence.StaticBufferRefinement.fmtError
import verifier.equivalence.summarization.CommonPureInternalFunction
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Called to analyze/refine/explain counter examples generated during the process.
 *
 * [ruleResult] is used to report a failure if no refinement is possible. The counterexample
 * was found in [vcProgram] and its values are found in [theModel]. The cex was found using the instrumentation
 * in [methodAContext] and [methodBContext]; the overall comparison is happening in [context]. [instLevels] describes
 * the traces being explored, and [pairwiseProofManager] is used to register refinements if necessary.
 */
internal class CounterExampleAnalyzer(
    private val ruleResult: RuleCheckResult,
    private val theModel: CounterexampleModel,
    private val vcProgram: CoreTACProgram,
    private val methodAContext: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODA>,
    private val methodBContext: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODB>,
    private val instLevels: EquivalenceChecker.IInstrumentationLevels,
    override val context: QueryContext,
    val pairwiseProofManager: EquivalenceChecker.PairwiseProofManager,
) : WithQueryContext {

    private val callTraceValueFormatter = CallTraceValueFormatter(
        scene = scene,
        model = theModel,
        addrToContract = mapOf()
    )

    fun interface CEXContinuation {
        suspend fun resume(): EquivalenceChecker.SatInterpretation
    }

    /**
     * Really a specialized version of the [Either] type to make type signatures easier to read
     */
    private sealed interface MatchingEventData<out T> {
        data class Found<T>(val what: T) : MatchingEventData<T>
        data object Missing : MatchingEventData<Nothing>
    }
    private fun explainStorageDifference(
        failingAssert: LTACCmdView<TACCmd.Simple.AssertCmd>,
    ) : Either<EquivalenceChecker.MismatchExplanation.StorageExplanation, String> {
        fun Either<Nothing, String>.fail(): Either<Nothing, String> {
            return "Couldn't explain storage difference: ${this.right()}".toRight()
        }
        val data = failingAssert.cmd.meta[EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION]!!

        val slot = data.skolemIndex.formatScalarAs(
            bytes32Type
        ).leftOr { return it.fail() }
        val contractAValue = data.contractAValue.formatScalarAs(bytes32Type).leftOr { return it.fail() }
        val contractBValue = data.contractBValue.formatScalarAs(bytes32Type).leftOr { return it.fail() }
        return EquivalenceChecker.MismatchExplanation.StorageExplanation(
            slot = slot,
            contractAValue = contractAValue,
            contractBValue = contractBValue,
        ).toLeft()
    }

    /**
     * From the [BufferTraceInstrumentation.CallEvent] object
     * found at [where] in [graph], extract the [verifier.equivalence.EquivalenceChecker.ExternalCall] object
     * describing the call. Otherwise, it returns the extracted
     * ordinal and the [verifier.equivalence.EquivalenceChecker.ExternalCall] object.
     */
    private fun extractEnvironment(
        graph: TACCommandGraph,
        where: LTACCmd,
        ce: BufferTraceInstrumentation.CallEvent,
    ) : EquivalenceChecker.ExternalCall {
        val model = theModel
        return callEventDataToInteraction(where, ce, model, graph).toValue({ it }, {
            EquivalenceChecker.ExternalCall.Incomplete(
                it
            )
        })
    }

    /**
     * Try to extract the [verifier.equivalence.EquivalenceChecker.ExternalCall.Complete]
     * from the [BufferTraceInstrumentation.CallEvent] [ce]
     * found at [where] in [graph] using the values in [model].
     */
    private fun callEventDataToInteraction(
        where: LTACCmd,
        ce: BufferTraceInstrumentation.CallEvent,
        model: CounterexampleModel,
        graph: TACCommandGraph
    ): Either<EquivalenceChecker.ExternalCall.Complete, String> {
        fun <T> Either<T, CounterexampleModel.ResolvingFailure>.withFailureString(what: () -> String) =
            this.mapRight {
                "Failed resolving ${what()} @ $where with $ce: $it"
            }

        fun <T, U> Either<T, CounterexampleModel.ResolvingFailure>.formatErrorOrBind(
            what: String,
            f: (T) -> Either<U, String>
        ): Either<U, String> {
            return when (this) {
                is Either.Left -> f(this.d)
                is Either.Right -> "Failed resolving calldata at $where: couldn't get value for $what; ${this.d}".toRight()
            }
        }

        val callee = model.valueAsBigInteger(ce.callee).withFailureString { "callee address" }.leftOr { return it }
        val returnSize =
            model.valueAsBigInteger(ce.returnDataSize).withFailureString { "return address" }.leftOr { return it }
        val returnResult = model.valueAsBigInteger(ce.returnCode).withFailureString { "return code" }.leftOr { return it } != BigInteger.ZERO
        val calleeCodesize =
            model.valueAsBigInteger(ce.calleeCodeSize).withFailureString { "callee codesize" }.leftOr { return it }
        val value = model.valueAsBigInteger(ce.value).withFailureString { "sent value" }.leftOr { return it }
        val calldata = model.valueAsBigInteger(ce.bufferLength).formatErrorOrBind("input buffer length") { buffLength ->
            model.valueAsBigInteger(ce.bufferStart).formatErrorOrBind("input buffer offset") { buffStart ->
                PreciseBufferExtraction.extractBufferModel(
                    start = buffStart,
                    where = where.ptr,
                    graph = graph,
                    model = model,
                    buffer = ce.memoryCapture,
                    len = buffLength
                ).toList(buffLength)
            }
        }
        return EquivalenceChecker.ExternalCall.Complete(
            returnSize = returnSize,
            callee = callee,
            callResult = returnResult,
            calleeCodesize = calleeCodesize,
            calldata = calldata,
            value = value,
        ).toLeft()
    }

    /**
     * Extract the [CallTraceValue] representation of arguments
     * from the [CallInputsAndOutputs] that were found the body of [method].
     */
    private fun extractArguments(
        method: EquivalenceChecker.InlinedInstrumentation<*>,
        cio: CallInputsAndOutputs,
        model: CounterexampleModel
    ) : Map<Int, CallTraceValue> {
        return cio.externalCall(method.methodCallId)?.let { aArgs ->
            val m = aArgs.calldataFamily.toValueMap(
                model, DEFAULT_SIGHASH_SIZE
            )
            LayoutDecoderStrategy(
                names = aArgs.extra.paramNames,
                paramTypes = aArgs.extra.paramTypes,
                byteOffsetToModelValue = m
            ).asMap()
        }.orEmpty()
    }

    /**
     * Get a "friendly" explanation of the input in the case of a CEX.
     *
     * This includes the calldata arguments, some environment information etc.
     */
    private fun getInputExplanation() : EquivalenceChecker.InputExplanation {
        val model = theModel
        val ruleProg = vcProgram
        val env = RuleGenerator.extractEnvironmentValues(
            model, ruleProg
        )
        val msgVal = env[EthereumVariables.callvalue]
        val caller = env[EthereumVariables.caller]
        val cio = CallInputsAndOutputs(
            blocks = ruleProg.topoSortFw.filter {
                it in model.reachableNBIds
            },
            model = model,
            scene = scene,
            analysisCache = ruleProg.analysisCache
        )
        val ctf = CallTraceValueFormatter(
            model = model,
            scene = scene,
            addrToContract = mapOf()
        )
        val methodACallId = methodAContext.methodCallId
        val methodBCallId = methodBContext.methodCallId
        ruleProg.parallelLtacStream().filter {
            it.ptr.block.calleeIdx == methodACallId || it.ptr.block.calleeIdx == methodBCallId
        }.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
        }.flatMap {
            val callIdx = it.ptr.block.calleeIdx
            it.cmd.calldataMovement(model, callIdx).stream().map {
                it to callIdx
            }
        }.sequential().forEach { (mov, id) ->
            cio.registerCalldataMovement(mov, id)
        }
        val methodAArgs = extractArguments(methodAContext, cio, model)
        val methodBArgs = extractArguments(methodBContext, cio, model)
        val argOrds = methodAArgs.keys + methodBArgs.keys
        val args = mutableListOf<EquivalenceChecker.Argument>()
        for(i in argOrds.sorted()) {
            val aArg = methodAArgs[i]
            val bArg = methodBArgs[i]
            val aStringRepr = aArg?.toSarif(ctf, tooltip = "")?.flatten()
            val bStringRepr = bArg?.toSarif(ctf, tooltip = "")?.flatten()
            val argFormat = aStringRepr?.let {
                if(bStringRepr != null && bStringRepr != it) {
                    logger.warn {
                        "Conflicting argument representations for $i ${aArg.paramName} ${bArg.paramName}: $aStringRepr vs $bStringRepr"
                    }
                    "$aStringRepr / $bStringRepr"
                } else {
                    it
                }
            } ?: bStringRepr ?: "<no value found>"
            val (argName, altName) = if(aArg?.paramName != null) {
                aArg.paramName to bArg?.paramName
            } else {
                bArg?.paramName to null
            }
            if(argName == null) {
                continue
            }
            args.add(
                EquivalenceChecker.Argument(
                    ordinal = i,
                    name = argName,
                    value = argFormat,
                    altName = altName
                )
            )
        }
        return EquivalenceChecker.InputExplanation(
            argumentFormatting = args,
            messageValue = msgVal,
            sender = caller
        )
    }

    /**
     * Given a mismatch at index [eventIndex], find the [verifier.equivalence.EquivalenceChecker.LTraceEventMarker]
     * describing the event for which there was a mismatch. This might not be found, if one method has an external
     * event and the other does not.
     */
    private fun <T: EquivalenceChecker.METHOD_MARKER> extractEventData(
        context: EquivalenceChecker.InlinedInstrumentation<T>,
        eventIndex: BigInteger
    ) : MatchingEventData<EquivalenceChecker.LTraceEventMarker<T>> {
        return vcProgram.parallelLtacStream().filter {
            it.ptr.block.calleeIdx == context.methodCallId && it.ptr.block in theModel.reachableNBIds
        }.mapNotNull {
            it.annotationView(BufferTraceInstrumentation.TraceIndexMarker.META_KEY)
        }.filter { annot ->
            theModel.valueAsBigInteger(annot.annotation.indexVar).leftOrNull() == eventIndex
        }.toList().singleOrNull()?.let { la ->
            val origSite = context.instrumentation.useSiteInfo.keysMatching { _, info ->
                info.id == la.annotation.id
            }.single()
            MatchingEventData.Found(
                EquivalenceChecker.LTraceEventMarker(
                    origProgramSite = origSite,
                    vcProgramSite = la.ptr,
                    marker = la.annotation
                )
            )
        } ?: MatchingEventData.Missing
    }

    private fun <T: EquivalenceChecker.METHOD_MARKER> extractEventData(
        context: EquivalenceChecker.InlinedInstrumentation<T>,
        annot: LTACAnnotation<BufferTraceInstrumentation.TraceIndexMarker>
    ) : MatchingEventData<EquivalenceChecker.LTraceEventMarker<T>> {
        val orig = context.instrumentation.useSiteInfo.keysMatching { _, info ->
            info.id == annot.annotation.id
        }.singleOrNull() ?: return MatchingEventData.Missing
        return MatchingEventData.Found(
            EquivalenceChecker.LTraceEventMarker(
                origProgramSite = orig,
                marker = annot.annotation,
                vcProgramSite = annot.ptr
            )
        )
    }

    /**
     * Called as the continuation if the [TraceMinimizer] couldn't find an ealier mismatch. Start refinment, if possible
     */
    private suspend fun postMinimizationAnalysis(
        eventIdx: BigInteger,
    ) : EquivalenceChecker.SatInterpretation {
        val methodAEvent = extractEventData(
            methodAContext,
            eventIdx
        )
        val methodBEvent = extractEventData(
            methodBContext,
            eventIdx
        )
        if(methodAEvent is MatchingEventData.Missing && methodBEvent is MatchingEventData.Missing) {
            return EquivalenceChecker.SatInterpretation.GaveUp("Both event information is missing", ruleResult)
        }

        /*
         * Let's try to refine counter examples first
         */
        val aOverride = MemoryImprecisionAnalyzer.analyze(
            theModel,
            vcProgram,
            methodAContext
        )
        val bOverride = MemoryImprecisionAnalyzer.analyze(
            theModel,
            vcProgram,
            methodBContext
        )
        logger.debug {
            "CEX analysis yielded: $aOverride and $bOverride"
        }
        val aSucc = aOverride?.let { (where, depth) ->
            pairwiseProofManager.registerALoadOverride(where, depth)
        } == true
        val bSucc = bOverride?.let { (where, depth) ->
            pairwiseProofManager.registerBLoadOverride(where, depth)
        } == true
        // retry with more precision
        if(aSucc || bSucc) {
            return EquivalenceChecker.SatInterpretation.Refine(
                Explorer(
                    traceLevel = instLevels,
                    context = context
                )
            )
        }
        // this means we have a log/call in one method but not the other
        if(methodAEvent is MatchingEventData.Missing || methodBEvent is MatchingEventData.Missing) {
            check(instLevels.traceLevel != BufferTraceInstrumentation.TraceTargets.Results)
            return tryMissingInterpretation(
                methodAEvent, methodAContext, methodBEvent, methodBContext
            )
        }
        val aDiff = (methodAEvent as MatchingEventData.Found).what
        val bDiff = (methodBEvent as MatchingEventData.Found).what

        val aTraceWithContext = EquivalenceChecker.LTraceWithContext(
            aDiff, methodAContext
        )
        val bTraceWithContext = EquivalenceChecker.LTraceWithContext(
            bDiff, methodBContext
        )

        /**
         * Did the buffer hashes differ in the mismatch?
         */
        val bufferHashDiffers = theModel.valueAsBigInteger(aDiff.marker.bufferHash).leftOrNull()?.let { aHash ->
            theModel.valueAsBigInteger(bDiff.marker.bufferHash).leftOrNull()?.let { bHash ->
                aHash == bHash
            }
        } == false
        if(bufferHashDiffers) {
            /**
             * If so, was this legit?
             */
            val aBufferDataE = tryExtractBufferModel(aDiff)
            val bBufferDataE = tryExtractBufferModel(bDiff)
            val exactBuffersSame = aBufferDataE.bindLeft { aBuffer ->
                bBufferDataE.mapLeft { bBuffer ->
                    aBuffer == bBuffer
                }
            }.leftOrNull() == true
            /**
             * If not, see if we can prove they are always equal using [StaticBufferRefinement]. If this fails,
             * goto [explainCounterExample]
             */
            if(exactBuffersSame) {
                StaticBufferRefinement.tryRefineBuffers(
                    aTraceAndContext = aTraceWithContext,
                    bTraceAndContext = bTraceWithContext,
                    scene = scene,
                    targetEvents = instLevels.traceLevel
                )?.let { (aRefine, bRefine) ->
                    /**
                     * We aren't done just because the extraction works, we need to prove the contents equal;
                     * use the solver
                     */
                    val ref = StaticBufferEquality(
                        siteA = aDiff.origProgramSite,
                        siteB = bDiff.origProgramSite,
                        parent = instLevels,
                        refineA = aRefine,
                        refineB = bRefine,
                        context = context
                    ) {
                        /**
                         * Couldn't actually resolve, never mind, keep analyzing the CEX
                         */
                        explainCounterExample(
                            aTraceWithContext, bTraceWithContext
                        )
                    }
                    return EquivalenceChecker.SatInterpretation.Refine(ref)
                }
            }
        }
        /**
         * No refinement attempted, go directly to [explainCounterExample]
         */
        return explainCounterExample(aTraceWithContext, bTraceWithContext)
    }

    /**
     * Called when we are sure there is a CEX and mismatch.
     */
    private fun explainCounterExample(
        aDiff: EquivalenceChecker.LTraceWithContext<EquivalenceChecker.METHODA>,
        bDiff: EquivalenceChecker.LTraceWithContext<EquivalenceChecker.METHODB>
    ) : EquivalenceChecker.SatInterpretation {
        val aEvent = traceMarkerToEvent(aDiff.trace).leftOr {
            return EquivalenceChecker.SatInterpretation.GaveUp(
                "Failed extracting explanation from ${methodA.pp()}: ${it.right()}",
                ruleResult
            )
        }
        val bEvent = traceMarkerToEvent(bDiff.trace).leftOr {
            return EquivalenceChecker.SatInterpretation.GaveUp(
                "Failed extracting explanation from ${methodB.pp()}: ${it.right()}",
                ruleResult
            )
        }
        val priorCallsA = getPriorTrace(aDiff.toSelector()) {
            it
        }
        val priorCallsB = getPriorTrace(bDiff.toSelector()) {
            EquivalenceChecker.SyncEvent
        }

        return EquivalenceChecker.SatInterpretation.RealCounterExample(
            inputExplanation = getInputExplanation(),
            priorEventsA = priorCallsA.leftOrNull(),
            priorEventsB = priorCallsB.leftOrNull(),
            diffExplanation = EquivalenceChecker.MismatchExplanation.DifferentEvents(
                eventInA = aEvent,
                eventInB = bEvent
            ),
            ruleCheckResult = ruleResult
        )
    }

    /**
     * A single analysis that is split into three parts, allowing calling back out to solver at most 2 extra times.
     * This is the public entry point, which first tries to figure out what fails, and the minimize.
     */
    suspend fun analyzeCounterExample() : EquivalenceChecker.SatInterpretation {
        val failingAssertOpt = vcProgram.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()
        }.filter {
            EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION in it.cmd.meta || EquivalenceChecker.TRACE_EQUIVALENCE_ASSERTION in it.cmd.meta
        }.filter {
            theModel.valueAsBoolean(it.cmd.o).leftOrNull() == false
        }.findFirst()
        if (failingAssertOpt.isEmpty) {
            return EquivalenceChecker.SatInterpretation.GaveUp(
                "Couldn't find failing assertion in counterexample",
                ruleResult
            )
        }
        val failingAssert = failingAssertOpt.get()
        /**
         * If this was a failing assertion of storage equivalence, there is nothing more to do, explain the difference
         * and return
         */
        if (EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION in failingAssert.cmd.meta) {
            return explainStorageDifference(failingAssert).mapLeft {
                val inputExplanation = getInputExplanation()

                val callEnvA = getPriorTrace(
                    object : TraceSelector<EquivalenceChecker.METHODA> {
                        override val method: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODA>
                            get() = methodAContext

                        override fun stop(where: LTACCmd): Boolean {
                            return where.ptr.block.calleeIdx == NBId.ROOT_CALL_ID
                        }

                        override fun filter(blk: NBId): Boolean {
                            return blk.calleeIdx == NBId.ROOT_CALL_ID || blk.calleeIdx == methodAContext.methodCallId
                        }

                    }
                ) {
                    it
                }.leftOrNull()

                val callEnvB = getPriorTrace(
                    object : TraceSelector<EquivalenceChecker.METHODB> {
                        override val method: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODB>
                            get() = methodBContext

                        override fun stop(where: LTACCmd): Boolean {
                            return where.ptr.block.calleeIdx == NBId.ROOT_CALL_ID
                        }

                        override fun filter(blk: NBId): Boolean {
                            return blk.calleeIdx == NBId.ROOT_CALL_ID || blk.calleeIdx == methodBContext.methodCallId
                        }

                    }
                ) {
                    EquivalenceChecker.SyncEvent
                }.leftOrNull()
                EquivalenceChecker.SatInterpretation.RealCounterExample(
                    ruleCheckResult = ruleResult,
                    diffExplanation = it,
                    inputExplanation = inputExplanation,
                    priorEventsA = callEnvA,
                    priorEventsB = callEnvB
                )
            }.toValue({ it }, { EquivalenceChecker.SatInterpretation.GaveUp(it, ruleResult) })
        }

        fun fail(f: () -> String) : EquivalenceChecker.SatInterpretation {
            logger.info(f)
            return EquivalenceChecker.SatInterpretation.GaveUp(f(), ruleResult)
        }

        /**
         * Otherwise, find out which index in our trace has the mismatch.
         */
        val eventIdx = vcProgram.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(EquivalenceChecker.IndexHolder.META_KEY)
        }.mapNotNull {
            theModel.valueAsBigInteger(it.indexSym).leftOrNull()
        }.collect(Collectors.toSet()).singleOrNull() ?: return fail {
            "Couldn't find single event id"
        }
        /**
         * Trace is already minimal, skip to [postMinimizationAnalysis]
         */
        if(eventIdx == BigInteger.ZERO) {
            return postMinimizationAnalysis(
                eventIdx
            )
        } else {
            /**
             * Otherwise, try to find an earlier trace, if we can't, keep going in [postMinimizationAnalysis]
             */
            return EquivalenceChecker.SatInterpretation.Refine(
                TraceMinimizer(
                    idx = eventIdx,
                    traceLevel = instLevels,
                    context
                ) {
                    postMinimizationAnalysis(
                        eventIdx
                    )
                }
            )
        }
    }

    private fun <T: EquivalenceChecker.METHOD_MARKER> tryExtractBufferModel(
        event: EquivalenceChecker.LTraceEventMarker<T>
    ) : Either<List<UByte>, String> {
        return theModel.valueAsBigInteger(event.marker.lengthVar).fmtError().bindLeft { len ->
            theModel.valueAsBigInteger(event.marker.bufferStart).fmtError().bindLeft { base ->
                PreciseBufferExtraction.extractBufferModel(
                    graph = vcProgram.analysisCache.graph,
                    model = theModel,
                    where = event.vcProgramSite,
                    len = len,
                    start = base,
                    buffer = event.marker.bufferBase
                ).toList(len)
            }
        }
    }

    /**
     * Kotlin doesn't have a Quarduple class, so I use this instead.
     * Used to indicate which method is [missing] the event [lt] that was found
     * in [found]. [explain] describes how to turn that event data into a [verifier.equivalence.EquivalenceChecker.MismatchExplanation].
     */
    private data class MissingPayload(
        val missing: TACMethod,
        val found: TACMethod,
        val lt: EquivalenceChecker.LTraceWithContext<*>,
        val explain: (EquivalenceChecker.EventWithData) -> EquivalenceChecker.MismatchExplanation,
    )

    private val bytes32Type = EVMTypeDescriptor.BytesK(EVM_BYTES_IN_A_WORD)

    private fun TACSymbol.formatScalarAs(
        ty: EVMTypeDescriptor.EVMValueType,
        tooltip: String = ""
    ) : Either<String, String> {

        val tv = theModel.valueAsTACValue(this) ?: return "No value found for $this in model".toRight()
        val valueType = FormatterType.Value.EVM(ty)
        return callTraceValueFormatter.valueToSarif(tv, valueType, tooltip).flatten().toLeft()
    }

    /**
     * From the [BufferTraceInstrumentation.TraceIndexMarker],
     * extract the [verifier.equivalence.EquivalenceChecker.EventWithData] representation,
     * including sort information, parameter info, etc.
     */
    private fun traceMarkerToEvent(
        lTraceEventMarker: EquivalenceChecker.LTraceEventMarker<*>
    ) : Either<EquivalenceChecker.EventWithData, String> {
        val event = BufferTraceInstrumentation.extractEvent(
            marker = lTraceEventMarker.marker
        ).leftOr { return it }
        val sort = event.sort
        val params = when(event) {
            is BufferTraceInstrumentation.RawEventParams.ExternalCallParams -> {
                listOf(
                    EquivalenceChecker.EventParam(
                        label = EquivalenceChecker.ContextLabel.ExternalCallLabel.CALLEE,
                        value = event.context.callee.formatScalarAs(EVMTypeDescriptor.address).leftOrNull()
                    ),
                    EquivalenceChecker.EventParam(
                        label = EquivalenceChecker.ContextLabel.ExternalCallLabel.CALL_VALUE,
                        value = event.context.value.formatScalarAs(EVMTypeDescriptor.UIntK(EVM_BITWIDTH256)).leftOrNull()
                    )
                )
            }
            is BufferTraceInstrumentation.RawEventParams.InternalSummaryParams -> {
                if(event.context.args.size != event.context.signature.params.size) {
                    return "Arity mismatch: have ${event.context.args.size} args but expected only ${event.context.signature.params.size}".toRight()
                }
                val argFmt = event.context.signature.params.zip(event.context.args).withIndex().monadicMap {
                    it.format()
                } ?: return "nah".toRight()
                listOf(
                    EquivalenceChecker.EventParam(
                        label = EquivalenceChecker.ContextLabel.InternalCallLabel.Signature,
                        value = event.context.signature.prettyPrintFullyQualifiedName()
                    )
                ) + argFmt

            }
            is BufferTraceInstrumentation.RawEventParams.ExitParams -> listOf()
            is BufferTraceInstrumentation.RawEventParams.LogTopics -> {
                event.params.topics.mapIndexed { ind, either ->
                    val t = when(ind) {
                        0 -> EquivalenceChecker.ContextLabel.LogLabel.LOG_TOPIC1
                        1 -> EquivalenceChecker.ContextLabel.LogLabel.LOG_TOPIC2
                        2 -> EquivalenceChecker.ContextLabel.LogLabel.LOG_TOPIC3
                        3 -> EquivalenceChecker.ContextLabel.LogLabel.LOG_TOPIC4
                        else -> error("implausible number of topics")
                    }
                    EquivalenceChecker.EventParam(
                        label = t,
                        value = either.formatScalarAs(EVMTypeDescriptor.BytesK(EVM_BYTES_IN_A_WORD)).leftOrNull()
                    )
                }
            }
        }
        val buffer = tryExtractBufferModel(lTraceEventMarker).leftOrNull()
        return EquivalenceChecker.BasicEvent(
            params = params,
            sort = sort,
            bufferRepr = buffer,
        ).toLeft()
    }

    private fun IndexedValue<Pair<VMParam, TACSymbol>>.format(): EquivalenceChecker.EventParam? {
        val (param, sym) = this.value
        val ind = index
        val ty = param.vmType
        if(ty !is EVMTypeDescriptor.EVMValueType) {
            return null
        }
        val fmt = sym.formatScalarAs(ty).leftOrNull() ?: return null
        val name = when(param) {
            is VMParam.Named -> param.name
            is VMParam.Unnamed -> "Argument $ind"
        }
        return EquivalenceChecker.EventParam(
            label = EquivalenceChecker.ContextLabel.InternalCallLabel.Arg(
                name = name,
                tooltip = if(param is VMParam.Unnamed) {
                    "No name was found for this parameter"
                } else {
                    ""
                }
            ),
            value = fmt
        )
    }

    private interface TraceSelector<T: EquivalenceChecker.METHOD_MARKER> {
        val method: EquivalenceChecker.InlinedInstrumentation<T>
        fun stop(where: LTACCmd): Boolean
        fun filter(blk: NBId): Boolean
    }

    /**
     * Before event at [reprMethod], find all prior calls/logs.
     */
    private fun <T: EquivalenceChecker.METHOD_MARKER> getPriorTrace(
        reprMethod: TraceSelector<T>,
        commonItemHandler: (EquivalenceChecker.IEvent) -> EquivalenceChecker.IEvent
    ) : Either<List<EquivalenceChecker.IEvent>, String> {
        val methodStartBlock = vcProgram.blockgraph.entries.singleOrNull { (blk,succ) ->
            blk.calleeIdx == NBId.ROOT_CALL_ID && succ.singleOrNull()?.calleeIdx == reprMethod.method.methodCallId
        }?.value?.single() ?: return "Couldn't deduce start of method ${reprMethod.method.orig.pp()}".toRight()
        if(methodStartBlock !in theModel.reachableNBIds) {
            return "No trace for ${reprMethod.method.orig.pp()}".toRight()
        }
        val graph = vcProgram.analysisCache.graph
        val toRet = mutableListOf<EquivalenceChecker.IEvent>()
        var blockIt = methodStartBlock
        var eventOrd = 0
        while(true) {
            val block = graph.elab(blockIt)
            for(lc in block.commands) {
                if(reprMethod.stop(lc)) {
                    return toRet.toLeft()
                }
                if(instLevels.traceLevel != BufferTraceInstrumentation.TraceTargets.Calls) {
                    val te = lc.annotationView(BufferTraceInstrumentation.TraceIndexMarker.META_KEY)
                    if (te != null) {
                        val toAdd = when (val ex = extractEventData(reprMethod.method, te)) {
                            is MatchingEventData.Found -> traceMarkerToEvent(ex.what).toValue(
                                { it },
                                { EquivalenceChecker.Missing("Failed to resolve event ordinal $eventOrd: $it") })

                            MatchingEventData.Missing -> EquivalenceChecker.Missing("Couldn't resolve event ordinal $eventOrd")
                        }
                        eventOrd++
                        toRet.add(commonItemHandler(toAdd))
                        continue
                    }
                }
                val ce = lc.maybeAnnotation(BufferTraceInstrumentation.CallEvent.META_KEY)
                if(ce != null) {
                    val res = extractEnvironment(
                        graph = graph,
                        ce = ce,
                        where = lc,
                    )
                    toRet.add(res.let(commonItemHandler))
                    continue
                }
                val ie = lc.maybeAnnotation(CommonPureInternalFunction.ANNOTATION_META)
                if(ie != null) {
                    val params = ie.qualifiedMethodSignature.params.zip(ie.argSymbols).withIndex().monadicMap {
                        it.format()
                    }.orEmpty()
                    val rets = ie.qualifiedMethodSignature.resType.zip(ie.rets).withIndex().monadicMap { (ind, pair) ->
                        val (ty, sym) = pair
                        val fmt = sym.formatScalarAs(ty as EVMTypeDescriptor.EVMValueType).leftOrNull() ?: return@monadicMap null
                        EquivalenceChecker.EventParam(
                            label = EquivalenceChecker.ContextLabel.InternalCallLabel.ReturnValue(
                                ord =  ind
                            ),
                            value = fmt
                        )
                    }.orEmpty()
                    toRet.add(EquivalenceChecker.BasicEvent(
                        sort = BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL,
                        bufferRepr = null,
                        params = listOf(
                            EquivalenceChecker.EventParam(EquivalenceChecker.ContextLabel.InternalCallLabel.Signature, ie.qualifiedMethodSignature.prettyPrintFullyQualifiedName())
                        ) + params + rets
                    ).let(commonItemHandler))
                    continue
                }
                val functionStart = lc.annotationView(INTERNAL_FUNC_START)
                if(functionStart != null) {
                    toRet.add(EquivalenceChecker.Elaboration(
                        what = "Entering internal function ${functionStart.annotation.methodSignature.prettyPrintFullyQualifiedName()}"
                    ))
                }
                val functionEnd = lc.annotationView(INTERNAL_FUNC_EXIT)
                if(functionEnd != null) {
                    toRet.add(EquivalenceChecker.Elaboration(
                        what = "Leaving internal function ${functionEnd.annotation.methodSignature.prettyPrintFullyQualifiedName()}"
                    ))
                }
            }
            blockIt = graph.succ(blockIt).singleOrNull {
                reprMethod.filter(it) && it in theModel.reachableNBIds
            } ?: return "Couldn't reach mismatch in ${reprMethod.method.orig.pp()}".toRight()
        }
    }

    private fun <T: EquivalenceChecker.METHOD_MARKER> EquivalenceChecker.LTraceWithContext<T>.toSelector() = object : TraceSelector<T> {
        override val method: EquivalenceChecker.InlinedInstrumentation<T>
            get() = context

        override fun stop(where: LTACCmd): Boolean {
            return trace.vcProgramSite == where.ptr
        }

        override fun filter(blk: NBId): Boolean {
            return blk.calleeIdx == context.methodCallId
        }


    }

    /**
     * Try to explain what's missing and from which function.
     */
    private fun tryMissingInterpretation(
        methodAEvent: MatchingEventData<EquivalenceChecker.LTraceEventMarker<EquivalenceChecker.METHODA>>,
        methodAContext: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODA>,
        methodBEvent: MatchingEventData<EquivalenceChecker.LTraceEventMarker<EquivalenceChecker.METHODB>>,
        methodBContext: EquivalenceChecker.InlinedInstrumentation<EquivalenceChecker.METHODB>
    ): EquivalenceChecker.SatInterpretation {
        val inputExplanation = getInputExplanation()
        val (_, foundMethod, event, mk) = when(methodAEvent) {
            is MatchingEventData.Found -> {
                MissingPayload(
                    missing = methodB,
                    found = methodA,
                    explain = EquivalenceChecker.MismatchExplanation::MissingInB,
                    lt = EquivalenceChecker.LTraceWithContext(methodAEvent.what, methodAContext)
                )
            }
            MatchingEventData.Missing -> {
                check(methodBEvent is MatchingEventData.Found)
                MissingPayload(
                    missing = methodA,
                    found = methodB,
                    explain = EquivalenceChecker.MismatchExplanation::MissingInA,
                    lt = EquivalenceChecker.LTraceWithContext(methodBEvent.what, methodBContext)
                )
            }
        }

        /**
         * The event we found in foundMethod that was missing
         */
        val basicEvent = traceMarkerToEvent(event.trace).leftOr {
            return EquivalenceChecker.SatInterpretation.GaveUp(
                "Couldn't extract event data from ${foundMethod.pp()}: ${it.right()}",
                ruleResult
            )
        }

        val priorEventsPrime = getPriorTrace(event.toSelector()) {
            it
        }.leftOrNull()

        return EquivalenceChecker.SatInterpretation.RealCounterExample(
            ruleCheckResult = ruleResult,
            diffExplanation = mk(basicEvent),
            priorEventsA = priorEventsPrime,
            priorEventsB = null,
            inputExplanation = inputExplanation
        )

    }
}
