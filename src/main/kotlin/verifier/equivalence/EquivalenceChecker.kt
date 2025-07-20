/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence

import analysis.*
import com.certora.collect.*
import config.Config
import config.ReportTypes
import datastructures.NonEmptyList
import datastructures.stdcollections.*
import verifier.equivalence.tracing.BufferTraceInstrumentation
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import log.ArtifactManagerFactory
import log.StaticArtifactLocation
import report.DummyLiveStatsReporter
import report.OutputReporter
import report.StatusReporter
import report.callresolution.CallResolutionTable
import report.callresolution.CallResolutionTableBase
import rules.CompiledRule
import rules.IsFromCache
import rules.RuleCheckResult
import rules.VerifyTime
import scene.*
import tac.*
import utils.*
import vc.data.*
import verifier.*
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import log.*
import report.TreeViewReporter
import spec.cvlast.QualifiedMethodSignature
import spec.rules.EquivalenceRule
import verifier.equivalence.summarization.PureFunctionExtraction
import verifier.equivalence.summarization.SharedPureSummarization
import java.io.File
import kotlin.math.max

private val logger = Logger(LoggerTypes.EQUIVALENCE)
private typealias TCmdPointer<@Suppress("UNUSED_TYPEALIAS_PARAMETER", "unused") T> = CmdPointer

/**
 * The actual object that does equivalence checking.
 */
class EquivalenceChecker private constructor(
    override val context: QueryContext,
    /**
     * The overall rule for this equivalence check
     */
    val equivalenceRule: EquivalenceRule
) : WithQueryContext {
    /**
     * Used to generate the rules. Mostly just to have this logic live somewhere else
     */
    private val ruleGen = RuleGenerator(methodA, methodB)
    private val pairwiseProofManager = PairwiseProofManager()


    /**
     * Display information
     */
    sealed interface ContextLabel {
        val displayLabel: String
        val description: String

        enum class LogLabel(override val displayLabel: String, override val description: String) : ContextLabel {
            LOG_TOPIC1("Topic 1", "The first log topic (usually the hash of the event signature)"),
            LOG_TOPIC2("Topic 2", "The second log topic (the first indexed event parameter)"),
            LOG_TOPIC3("Topic 3", "The third log topic (the second indexed event parameter)"),
            LOG_TOPIC4("Topic 4", "The fourth log topic (the third indexed event parameter)")
        }

        enum class ExternalCallLabel(override val displayLabel: String, override val description: String) : ContextLabel {
            CALL_VALUE("Value", "Ether value sent with call in wei"),
            CALLEE("Callee address", "Target account of external call"),
            CALLEE_CODESIZE("Callee codesize", "Non-deterministically chosen codesize of the target account"),
            RETURNSIZE("Result Buffer Size", "Size, in bytes, of the return/revert buffer"),
            CALL_RESULT("Call Result", "Whether the external call reverted or returned successfully")
        }

        sealed interface InternalCallLabel : ContextLabel {
            data object Signature : InternalCallLabel {
                override val displayLabel: String
                    get() = "Internal function signature"
                override val description: String
                    get() = "The fully qualified signature of a common pure function"
            }

            data class Arg(val name: String, val tooltip: String) : InternalCallLabel {
                override val displayLabel: String
                    get() = name
                override val description: String
                    get() = tooltip
            }

            data class ReturnValue(val ord: Int) : InternalCallLabel {
                override val displayLabel: String
                    get() {
                        val naturalPos = ord + 1
                        return naturalPos.toString() + when(naturalPos.mod(10)) {
                            1 -> "st"
                            2 -> "nd"
                            3 -> "rd"
                            else -> "th"
                        } + "  Return"
                    }

                override val description: String
                    get() = "The $displayLabel value returned by the function"
            }
        }
    }

    @KSerializable
    data class StorageComparison(
        val contractAValue: TACSymbol.Var,
        val contractBValue: TACSymbol.Var,
        val skolemIndex: TACSymbol.Var
    ) : AmbiSerializable, TransformableVarEntityWithSupport<StorageComparison> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): StorageComparison {
            return StorageComparison(
                contractAValue = f(contractAValue),
                contractBValue = f(contractBValue),
                skolemIndex = f(skolemIndex)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(contractAValue, contractBValue, skolemIndex)
    }

    companion object {
        fun List<UByte>.asBufferRepr(emptyRepr: String): String {
            if(isEmpty()) {
                return emptyRepr
            }
            return joinToString("") {
                it.toString(16)
            }
        }

        /**
         * (Monadically) turns a partial function that represents a buffer into a list of ubytes representing
         * a buffer of length [len]. If any bytes in the range 0 .. [len] are not mapped by the partial function, this function
         * returns an Either.Right.
         */
        fun Either<PartialFn<BigInteger, UByte>, String>.toList(len: BigInteger) : Either<List<UByte>, String> {
            return this.bindLeft pp@{ fn ->
                val outL = mutableListOf<UByte>()
                for(i in 0 until len.intValueExact()) {
                    outL.add(
                        fn[i.toBigInteger()] ?: return@pp "Missing byte value at $i".toRight()
                    )
                }
                outL.toLeft()
            }
        }

        val TRACE_EQUIVALENCE_ASSERTION = MetaKey<Int>("equivalence.trace.assertion")
        val STORAGE_EQUIVALENCE_ASSERTION = MetaKey<StorageComparison>("equivalence.storage.assertion")

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
            } catch(@Suppress("TooGenericExceptionCaught") e: Exception) {
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

        private fun IScene.resolve(query: ProverQuery.EquivalenceQuery) : Pair<TACMethod, TACMethod> {
            val methodChoice = Config.MethodChoices.orEmpty().singleOrNull() ?: throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Missing single method choice"
            )
            val contractA = this.getContract(query.contractA)
            val contractB = this.getContract(query.contractB)


            val methodA = contractA.getMethods().find {
                val m = it as TACMethod
                m.evmExternalMethodInfo?.toExternalABIName() == methodChoice
            } ?: throw CertoraException(
                CertoraErrorType.NO_MATCHING_METHOD,
                "No method $methodChoice found in equivalence contract ${contractA.name}"
            )

            val methodSighash = methodA.sigHash!!

            val methodB = contractB.getMethodBySigHash(methodSighash.n)!!
            return (methodA as TACMethod) to (methodB as TACMethod)
        }

        suspend fun handleEquivalence(
            query: ProverQuery.EquivalenceQuery,
            scene: IScene,
            outputReporter: OutputReporter,
            treeViewReporter: TreeViewReporter
        ) : List<RuleCheckResult> {
            if(!Config.EquivalenceCheck.get()) {
                throw CertoraException(
                    CertoraErrorType.BAD_CONFIG,
                    "Need to set ${Config.EquivalenceCheck.option.opt} in equivalence checker mode"
                )
            }
            val methodChoice = Config.MethodChoices.orEmpty().singleOrNull() ?: throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Missing single method choice"
            )

            val (methodAForAnalysis, methodBForAnalysis) = scene.resolve(query)

            val methodBPure = PureFunctionExtraction.canonicalPureFunctionsIn(methodBForAnalysis)
            val methodAPure = PureFunctionExtraction.canonicalPureFunctionsIn(methodAForAnalysis)

            val shared = methodBPure.filter { (qB, progA) ->
                methodAPure.any { (qA, progB) ->
                    qA.matchesNameAndParams(qB) && progA == progB
                }
            }
            logger.info {
                "The following pure functions were found in common:"
            }
            if(logger.isInfoEnabled) {
                for((q, _) in shared) {
                    logger.info {
                        "\t* ${q.toNamedDecSignature()}"
                    }
                }
            }

            val (coreA, coreB) = trySummarize(
                methodA = methodAForAnalysis,
                methodB = methodBForAnalysis,
                sharedSigs = shared.map { it.sig }
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

            /**
             * Adds some equivalence checker specific normalizations. These aren't useless for the "regular" flow
             */
            scene.mapContractMethodsInPlace("equiv_normalization") { _, method ->
                ContractUtils.transformMethodInPlace(method, ChainedMethodTransformers(listOf(
                    CoreToCoreTransformer(ReportTypes.SIGHASH_PACKING_NORMALIZER, SighashPackingNormalizer::doWork).lift(),
                    CoreToCoreTransformer(ReportTypes.SIGHASH_READ_NORMALIZER, SighashReadNormalizer::doWork).lift(),
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
            val equivalenceRule = EquivalenceRule.freshRule("Equivalence of ${query.contractA.name} and ${query.contractB.name} on $methodChoice")

            StatusReporter.registerSubrule(equivalenceRule)

            val (methodA, methodB) = scene.resolve(query)

            treeViewReporter.addTopLevelRule(equivalenceRule)
            treeViewReporter.signalStart(equivalenceRule)

            val r = EquivalenceChecker(
                QueryContext(
                    scene = scene,
                    methodA = methodA,
                    methodB = methodB
                ),
                equivalenceRule = equivalenceRule,
            ).handleEquivalence()
            r.forEach {
                if(it is RuleCheckResult.Leaf) {
                    treeViewReporter.signalEnd(it.rule, it)
                }
            }
            outputReporter.feedReporter(r, scene)
            return r
        }

        fun TACMethod.pp() = "${this.getContainingContract().name}.${this.soliditySignature ?: this.name}"

        fun mergeCodes(first: CoreTACProgram, second: CoreTACProgram) = TACProgramCombiners.mergeCodes(first, second)
    }

    /**
     * Records the variable used in skolemization for the query.
     */
    data class IndexHolder(val indexSym: TACSymbol) : TransformableSymEntity<IndexHolder> {
        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): IndexHolder {
            return IndexHolder(f(indexSym))
        }
        companion object {
            val META_KEY = MetaKey<IndexHolder>("equiv.trace.read.marker")

        }
    }


    // CEX stuff

    /**
     * An argument decoded by one of the methods
     */
    data class Argument(
        /**
         * Which argument number, from 0
         */
        val ordinal: Int,
        /**
         * The name of the argument (in methodA)
         */
        val name: String,
        /**
         * The name of the argument in methodB (if different from A)
         */
        val altName: String?,
        /**
         * The string representation of said argument
         */
        val value: String
    )

    /**
     * Information about the call into the two functions.
     * [messageValue] is the value of `msg.value`,
     * [sender] is `msg.sender`, [argumentFormatting] is the list of
     * argument data extracted from the method bodies
     */
    data class InputExplanation(
        val messageValue: BigInteger?,
        val sender: BigInteger?,
        val argumentFormatting: List<Argument>
    ) {
        fun prettyPrint(altContract: ContractClass): String {
            val msg = "Input parameters:\n" +
                "\tSender: ${sender?.toString(16) ?: "Unknown"}\n" +
                "\tCall value: ${messageValue ?: "Unknown"}\n" +
                "\tDecoded arguments:\n" +
                argumentFormatting.joinToString("\n") { arg ->
                    val altName = if(arg.altName != null && arg.altName != arg.name) {
                        " (in ${altContract.name}: ${arg.altName})"
                    } else {
                        ""
                    }
                    "\t\t -> ${arg.name}$altName = ${arg.value}"
                }.ifBlank {
                    "\t\tNone found"
                }
            return msg
        }
    }

    /**
     * Records information about an environment interaction, AKA an external call.
     *
     */
    internal sealed interface ExternalCall : IEvent {
        /**
         * An external call whose details were fully resolved. Implements the [EventWithData] interface.
         */
        data class Complete(
            /**
             * The callee address
             */
            val callee: BigInteger,
            /**
             * The value sent along with the call
             */
            val value: BigInteger,
            /**
             * The codesize chosen by the solver for the target address
             */
            val calleeCodesize: BigInteger,
            /**
             * The returnsize chosen by the solver for the external call
             */
            val returnSize: BigInteger,
            /**
             * Whether the external call was modeled to revert/return
             */
            val callResult: Boolean,
            /**
             * The calldata as a list of bytes or an explanation for why we couldn't extract it
             */
            val calldata: Either<List<UByte>, String>,
        ) : ExternalCall, EventWithData {
            override val params: List<EventParam>
                get() = listOf(
                    EventParam(ContextLabel.ExternalCallLabel.CALLEE, callee.toHexString()),
                    EventParam(ContextLabel.ExternalCallLabel.CALL_VALUE, value.toString()),
                    EventParam(ContextLabel.ExternalCallLabel.CALLEE_CODESIZE, calleeCodesize.toString()),
                    EventParam(ContextLabel.ExternalCallLabel.CALL_RESULT, if(callResult) {
                        "Successful return"
                    } else {
                        "Revert"
                    }),
                    EventParam(ContextLabel.ExternalCallLabel.RETURNSIZE, returnSize.toString()),
                )
            override val sort: BufferTraceInstrumentation.TraceEventSort
                get() = BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL
            override val bufferRepr: List<UByte>?
                get() = calldata.leftOrNull()

            override fun prettyPrint(): String {
                val calldataStr = when(calldata) {
                    is Either.Left -> calldata.d.asBufferRepr("(An empty buffer)")
                    is Either.Right -> "Failed to extract calldata: ${calldata.d}"
                }
                val resultStr = if(callResult) {
                    "A successful return"
                } else {
                    "A revert"
                }
                return "\tExternal call to 0x${callee.toString(16)} with eth: $value\n" +
                    "\tThe calldata buffer was:\n" +
                    "\t\t $calldataStr\n" +
                    "\t The callee codesize was chosen as: $calleeCodesize\n" +
                    "\t The call result was:\n" +
                    "\t\t $resultStr\n" +
                    "\t\t With a buffer of length: $returnSize"
            }
        }

        /**
         * Error data holder explaining why we couldn't resolve all the call information
         */
        data class Incomplete(override val msg: String) : ExternalCall, IncompleteEvent {
            override fun prettyPrint(): String {
                return "\tCouldn't extract information for this call: $msg"
            }
        }
    }

    /**
     * Some "event" that happened during execution; a log, exit, or external call.
     */
    sealed interface IEvent {
        fun prettyPrint(): String
    }

    object SyncEvent : IEvent {
        override fun prettyPrint(): String {
            throw UnsupportedOperationException()
        }
    }

    data class Elaboration(
        val what: String
    ) : IEvent {
        override fun prettyPrint(): String {
            return what
        }
    }

    /**
     * A placeholder for an event that we couldn't actually precisely resolve,
     * for whatever reason is described in [msg].
     */
    sealed interface IncompleteEvent : IEvent {
        val msg: String
    }

    data class Missing(override val msg: String) : IncompleteEvent {
        override fun prettyPrint(): String {
            return "Failed to resolve event: $msg"
        }
    }

    /**
     * KV-pair for some event parameter and it's formatted value
     */
    data class EventParam(
        val label: ContextLabel,
        val value: String? // the formatted value
    )

    /**
     * An event which has a [sort], [params] (which describes the parameters of the event)
     * and an optional buffer representation [bufferRepr].
     */
    sealed interface EventWithData : IEvent {
        val params: List<EventParam>
        val sort: BufferTraceInstrumentation.TraceEventSort
        val bufferRepr: List<UByte>?

    }

    /**
     * Basic event, just minimally implements the [EventWithData] interface
     */
    data class BasicEvent(
        override val bufferRepr: List<UByte>?,
        override val sort: BufferTraceInstrumentation.TraceEventSort,
        override val params: List<EventParam>,
    ) : EventWithData {

        override fun prettyPrint() : String {
            val context = params.joinToString("\n") { (k, v) ->
                "\t\t -> ${k.displayLabel}: $v"
            }.ifBlank {
                "\t\tNo additional context information"
            }
            val contextBody = "\tEvent Context:\n$context"
            val description = when(sort) {
                BufferTraceInstrumentation.TraceEventSort.REVERT -> "! The call reverted"
                BufferTraceInstrumentation.TraceEventSort.RETURN -> "! The call returned"
                BufferTraceInstrumentation.TraceEventSort.LOG -> "! A log was emitted"
                BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL -> "! An external call was made"
                BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL -> "! An internal call was made"
            }
            val bufferBody = if(sort.showBuffer) {
                val bufferDescription = bufferRepr?.let { bufferMap ->
                    bufferMap.joinToString("") {
                        it.toString(16)
                    }.ifBlank { "! Empty" }
                } ?: "? Couldn't extract the buffer contents"
                "\n\tThe raw buffer used in the event was:\n\t\t$bufferDescription"
            } else { "" }
            return "\t$description\n$contextBody$bufferBody"
        }
    }

    /**
     * An explanation for why the traces differ
     */
    sealed interface MismatchExplanation {
        /**
         * There was an [eventInB] which did not appear in [methodA]
         */
        data class MissingInA(
            val eventInB: EventWithData
        ) : MismatchExplanation

        /**
         * There was an [eventInA] that did not appear in [methodB]
         */
        data class MissingInB(
            val eventInA: EventWithData
        ) : MismatchExplanation

        /**
         * The kth event was different across the methods, [eventInA] vs [eventInB]
         */
        data class DifferentEvents(
            val eventInA: EventWithData,
            val eventInB: EventWithData
        ) : MismatchExplanation

        /**
         * Same events, but storage was left in different states. [slot] is the witness to this
         * difference, and the differences are described in [contractAValue] and [contractBValue]
         */
        data class StorageExplanation(
            val slot: String,
            val contractAValue: String,
            val contractBValue: String
        ) : MismatchExplanation
    }

    /**
     * Response from the current [TraceExplorer] on what to do with the current unsat (aka "verified") result.
     */
    internal sealed interface UnsatInterpretation {
        /**
         * Loop, using the new [TraceExplorer] [cont]
         */
        data class Refine(val cont: TraceExplorer) : UnsatInterpretation

        /**
         * Halt the loop, everything is verified
         */
        data object Verified: UnsatInterpretation

        /**
         * Disregard the verified result, take [s] instead
         */
        data class Override(val s: SatInterpretation) : UnsatInterpretation
    }

    internal sealed interface SatInterpretation {
        /**
         * Conservatively take the failure result in [overallResult]. Used when CEX refinement fails, AKA
         * we "gave up" trying to get a more precise answer.
         */
        data class GaveUp(val reason: String, val overallResult: RuleCheckResult) : SatInterpretation

        /**
         * Take the SAT result from the solver indicates a real issue, as described by the fields
         * of this class.
         */
        data class RealCounterExample(
            /**
             * The overall check result to be used for the query
             */
            val ruleCheckResult: RuleCheckResult,
            /**
             * Explanation of the concrete inputs used in the trace
             */
            val inputExplanation: InputExplanation,
            /**
             * Any events (logs/external calls) that occurred before the divergence
             */
            val priorEventsA: List<IEvent>?,
            val priorEventsB: List<IEvent>?,
            /**
             * The divergence itself
             */
            val diffExplanation: MismatchExplanation
        ) : SatInterpretation

        /**
         * Discard the current result, restart loop with new [TraceExplorer] [cont].
         */
        data class Refine(val cont: TraceExplorer) : SatInterpretation
    }

    /**
     * Object that drives the equivalence loop. Effectively fills in the logic in the "set up rule, run solver, check result"
     * found in [equivalenceLoop].
     */
    internal interface TraceExplorer {

        /**
         * Get the [BufferTraceInstrumentation.InstrumentationControl] to use
         * for instrumenting [methodA].
         */
        fun getAConfig(pairwiseProofManager: PairwiseProofManager): BufferTraceInstrumentation.InstrumentationControl

        /**
         * Get the [BufferTraceInstrumentation.InstrumentationControl] to use
         * for instrumenting [methodB]
         */
        fun getBConfig(pairwiseProofManager: PairwiseProofManager): BufferTraceInstrumentation.InstrumentationControl

        /**
         * Generate the verification condition based on the completed instrumentation in [methodAInst] and
         * [methodBInst].
         *
         * The program returned by this function is appended to the end of the generated rule
         * that is sent to the solver.
         */
        fun generateVC(
            methodAInst: BufferTraceInstrumentation.InstrumentationResults,
            methodBInst: BufferTraceInstrumentation.InstrumentationResults,
        ) : CoreTACProgram

        /**
         * Called if the rule generated by [generateVC] is satisfied, that is, the assertion fails.
         * What the equivalence loop should do is determined by the [SatInterpretation] object returned.
         *
         * [models] is the example info (containing most importantly, the [solver.CounterexampleModel]) for the sat result.
         * [methodAContext] and [methodBContext] provide information about the instrumented methods, including the call IDs
         * chosen for them in the generated rule.
         *
         * [vcProgram] is the program that was sent to the solver, aka the "presolver" rule.
         * [failureResult] is the rule result built from the sat result. To be returned if this sat result indicates a rule
         * failure.
         *
         * [pairwiseProofManager] encapsulates the instrumentation overrides used for [methodA] and [methodB]. If the counterexample
         * indicates imprecision, this object can be used to fine-tune the instrumentation.
         */
        suspend fun onSat(
            models: NonEmptyList<AbstractTACChecker.ExampleInfo>,
            methodAContext: InlinedInstrumentation<METHODA>,
            methodBContext: InlinedInstrumentation<METHODB>,
            vcProgram: CoreTACProgram,
            failureResult: RuleCheckResult.Single.WithCounterExamples,
            pairwiseProofManager: PairwiseProofManager,
        ) : SatInterpretation

        /**
         * Called when the rule generated by [generateVC] verifies, that is, the solver returns unsat.
         * If this returns null, the overall result is taken as given.
         * Otherwise, if a trace explorer is returned as the left variant, then the equivalence
         * loop will continue with that result.
         * If a rule check result is returned as the right variant, the overall equivalence process
         * fails with that result.
         */
        suspend fun onUnsat(
            methodA: InlinedInstrumentation<METHODA>,
            methodB: InlinedInstrumentation<METHODB>,
            pairwiseProofManager: PairwiseProofManager
        ): UnsatInterpretation

        /**
         * Called if the solver timed out. The explorer can try to adjust instrumentation overrides via [pairwiseProofManager]
         * and return a new [TraceExplorer] to be used to try again. If null is returned, the overall equivalence check
         * fails with "timeout".
         */
        fun onTimeout(
            methodA: InlinedInstrumentation<METHODA>,
            methodB: InlinedInstrumentation<METHODB>,
            pairwiseProofManager: PairwiseProofManager
        ): TraceExplorer?
    }

    internal interface IInstrumentationLevels {
        val traceLevel: BufferTraceInstrumentation.TraceTargets
        fun onTimeout(
            context: QueryContext,
            pairwiseProofManager: PairwiseProofManager,
            aConfig: BufferTraceInstrumentation.InstrumentationResults
        ): IInstrumentationLevels?

        fun onSuccess(
            context: QueryContext,
            aConfig: BufferTraceInstrumentation.InstrumentationResults,
            bConfig: BufferTraceInstrumentation.InstrumentationResults
        ): IInstrumentationLevels?

        fun getAInclusion(): BufferTraceInstrumentation.TraceInclusionMode
        fun getBInclusion(): BufferTraceInstrumentation.TraceInclusionMode
    }

    internal class ExitInstrumentation(val exits: List<CmdPointer>, val curr: Int) :IInstrumentationLevels {
        constructor(method: TACMethod) : this((method.code as CoreTACProgram).parallelLtacStream().filter {
            it.cmd.isHalting()
        }.map { it.ptr }.toList(), 0)
        override val traceLevel: BufferTraceInstrumentation.TraceTargets
            get() = BufferTraceInstrumentation.TraceTargets.Results

        override fun onTimeout(
            context: QueryContext,
            pairwiseProofManager: PairwiseProofManager,
            aConfig: BufferTraceInstrumentation.InstrumentationResults
        ): IInstrumentationLevels? {
            return null
        }

        override fun onSuccess(
            context: QueryContext,
            aConfig: BufferTraceInstrumentation.InstrumentationResults,
            bConfig: BufferTraceInstrumentation.InstrumentationResults
        ): IInstrumentationLevels? {
            if(curr == exits.lastIndex) {
                return null
            }
            return ExitInstrumentation(exits, curr + 1)
        }

        override fun getAInclusion(): BufferTraceInstrumentation.TraceInclusionMode {
            return BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(
                exits[curr]
            )
        }

        override fun getBInclusion(): BufferTraceInstrumentation.TraceInclusionMode {
            return BufferTraceInstrumentation.TraceInclusionMode.Unified
        }

    }

    /**
     * Encapsulates the logic for determining how to "advance" the equivalence proof. This involves
     * proceeding through the various trace targets, and the "tiers" of events in the (entirely
     * untested) tiered mode.
     */
    internal data class InstrumentationLevels(
        val inclusion: BufferTraceInstrumentation.TraceInclusionMode,
        override val traceLevel: BufferTraceInstrumentation.TraceTargets,
    ) : IInstrumentationLevels {
        /**
         * Used to try to simplify the problem to address a timeout, or give up
         */
        override fun onTimeout(
            context: QueryContext,
            pairwiseProofManager: PairwiseProofManager,
            aConfig: BufferTraceInstrumentation.InstrumentationResults
        ) : IInstrumentationLevels? {
            if(traceLevel == BufferTraceInstrumentation.TraceTargets.Results) {
                return ExitInstrumentation(context.methodA)
            }
            return when(inclusion) {
                BufferTraceInstrumentation.TraceInclusionMode.Unified -> {
                    logger.info {
                        "Timeout in unified mode, falling back on tiered"
                    }
                    this.copy(inclusion = BufferTraceInstrumentation.TraceInclusionMode.Until(0))
                }
                is BufferTraceInstrumentation.TraceInclusionMode.Until -> {
                    if(!tryForceSummarization(pairwiseProofManager, aConfig)) {
                        logger.info {
                            "No further simplifications found, giving up"
                        }
                        return null
                    }
                    logger.info {
                        "Found forced summarization, retrying..."
                    }
                    return this
                }
                is BufferTraceInstrumentation.TraceInclusionMode.UntilExactly -> `impossible!`
            }
        }

        /**
         * Try to find a use site that isn't yet summarized in methodA and summarize it, making the buffers simpler.
         */
        private fun tryForceSummarization(pairwiseProofManager: PairwiseProofManager, aConfig: BufferTraceInstrumentation.InstrumentationResults) : Boolean {
            val weakeningCand = aConfig.useSiteInfo.filter { (k, v) ->
                v.traceReport?.traceInclusion?.mayAppear == true && !pairwiseProofManager.isSummarized(k)
            }.maxByOrNull { (_, v) ->
                v.traceReport?.heuristicDifficulty!!
            } ?: return false
            logger.info {
                "Found use site ${weakeningCand.key} in a, forcing summarization"
            }
            pairwiseProofManager.forceSummarized(weakeningCand.key)
            return true
        }

        /**
         * Advance the proof state. If we're in the unified mode, go to the next trace target (call -> log -> exits).
         * If we're in tiered mode, go to the next tier if necessary, otherwise go to the next target.
         */
        override fun onSuccess(
            context: QueryContext,
            aConfig: BufferTraceInstrumentation.InstrumentationResults,
            bConfig: BufferTraceInstrumentation.InstrumentationResults
        ): IInstrumentationLevels? {
            when(inclusion) {
                BufferTraceInstrumentation.TraceInclusionMode.Unified -> {
                    val nxt = this.traceLevel.nextTarget()
                    if(nxt == null) {
                        logger.info {
                            "all done!"
                        }
                        return null
                    }
                    return InstrumentationLevels(inclusion, nxt)
                }
                is BufferTraceInstrumentation.TraceInclusionMode.Until -> {
                    val done = aConfig.useSiteInfo.count { (_, v) ->
                        v.traceReport?.traceInclusion?.mayAppear == true
                    } == 0 && bConfig.useSiteInfo.count { (_, v) ->
                        v.traceReport?.traceInclusion?.mayAppear == true
                    } == 0
                    logger.info {
                        "Done at level $inclusion"
                    }
                    return if(done) {
                        logger.info {
                            "No events remain after this level: success!"
                        }
                        return this.traceLevel.nextTarget()?.let {
                            this.copy(traceLevel = it, inclusion = BufferTraceInstrumentation.TraceInclusionMode.Unified)
                        }
                    } else {
                        logger.info {
                            "Events remain, starting next level"
                        }
                        this.copy(inclusion = BufferTraceInstrumentation.TraceInclusionMode.Until(traceNumber = inclusion.traceNumber + 1))
                    }
                }
                is BufferTraceInstrumentation.TraceInclusionMode.UntilExactly -> `impossible!`
            }
        }

        override fun getAInclusion(): BufferTraceInstrumentation.TraceInclusionMode = inclusion
        override fun getBInclusion(): BufferTraceInstrumentation.TraceInclusionMode = inclusion


        private fun BufferTraceInstrumentation.TraceTargets.nextTarget(): BufferTraceInstrumentation.TraceTargets? {
            return when(this) {
                BufferTraceInstrumentation.TraceTargets.Calls -> BufferTraceInstrumentation.TraceTargets.Log
                BufferTraceInstrumentation.TraceTargets.Log -> BufferTraceInstrumentation.TraceTargets.Results
                BufferTraceInstrumentation.TraceTargets.Results -> null
            }
        }
    }

    /**
     * Object encapsulating the instrumentation of some method [orig] (with static type tag [WHICH])
     * whose instrumentation results is in [instrumentation].
     *
     * [methodCallId] is the call id chosen for the inlining of the instrumented method.
     */
    internal data class InlinedInstrumentation<WHICH>(
        val methodCallId: CallId,
        val instrumentation: BufferTraceInstrumentation.InstrumentationResults,
        val orig: TACMethod
    )

    /**
     * The actual equivalence check loop.
     */
    suspend private fun equivalenceLoop(trace: TraceExplorer) : RuleCheckResult {
        logger.info {
            "starting new loop iteration"
        }
        /**
         * instrument the two functions according to the instructions of [trace]
         */
        val aConfig = trace.getAConfig(pairwiseProofManager)
        logger.info {
            "Instrumenting $methodA with $aConfig"
        }
        val instrumentedA = BufferTraceInstrumentation.instrument(methodA, aConfig)
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            instrumentedA.code,
            ReportTypes.INSTRUMENT_BUFFER_TRACE, location = StaticArtifactLocation.Reports, time = DumpTime.POST_TRANSFORM
        )
        val bConfig = trace.getBConfig(pairwiseProofManager)
        logger.info {
            "Instrumenting $methodB with $bConfig"
        }
        val instrumentedB = BufferTraceInstrumentation.instrument(methodB, bConfig)
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            instrumentedB.code,
            ReportTypes.INSTRUMENT_BUFFER_TRACE, location = StaticArtifactLocation.Reports, time = DumpTime.POST_TRANSFORM
        )

        /**
         * Generate the VC, call the solver
         */
        val vc = trace.generateVC(instrumentedA, instrumentedB)
        val theRule = ruleGen.generateRule(instrumentedA, instrumentedB, vc)

        val start = System.currentTimeMillis()
        val vcRes = TACVerifier.verify(scene, theRule.code, DummyLiveStatsReporter, equivalenceRule)
        val end = System.currentTimeMillis()
        val verifyTime = VerifyTime.WithInterval(start, end)
        val res = Verifier.JoinedResult(vcRes)

        val methodACallId = theRule.methodACallId
        val methodBCallId = theRule.methodBCallId


        val methodAContext = InlinedInstrumentation<METHODA>(methodACallId, instrumentedA, methodA)
        val methodBContext = InlinedInstrumentation<METHODB>(methodBCallId, instrumentedB, methodB)

        res.reportOutput(equivalenceRule)

        logger.info {
            "Solver result is ${vcRes.finalResult}"
        }

        /**
         * Interpret the result
         */
        when(res) {
            is Verifier.JoinedResult.Failure -> {
                val origProgWithAssertIdMeta =
                    CompiledRule.addAssertIDMetaToAsserts(res.simpleSimpleSSATAC, equivalenceRule)
                val ruleResult = RuleCheckResult.Single.WithCounterExamples(
                    rule = equivalenceRule,
                    result = vcRes.finalResult,
                    verifyTime = verifyTime,
                    ruleAlerts = emptyList(),
                    ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.WithExamplesData(
                        isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                        isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                        rule = equivalenceRule,
                        res = res,
                        scene = scene,
                        origProgWithAssertIdMeta = origProgWithAssertIdMeta,
                        callResolutionTableFactory = CallResolutionTable.Factory(theRule.code, scene, equivalenceRule),
                    )
                )
                val interp = trace.onSat(
                    models = res.examplesInfo,
                    methodAContext = methodAContext,
                    methodBContext = methodBContext,
                    vcProgram = res.simpleSimpleSSATAC,
                    failureResult = ruleResult,
                    pairwiseProofManager = pairwiseProofManager
                )
                return handleSatInterpretation(interp)
            }
            is Verifier.JoinedResult.Success,
            is Verifier.JoinedResult.SanityFail,
            is Verifier.JoinedResult.Timeout,
            is Verifier.JoinedResult.Unknown -> {
                val ruleResult = RuleCheckResult.Single.Basic(
                    result = vcRes.finalResult,
                    callResolutionTable = CallResolutionTableBase.Empty,
                    ruleAlerts = emptyList(),
                    rule = equivalenceRule,
                    ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                        isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                        isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                        dumpGraphLink = null
                    ),
                    verifyTime = verifyTime,
                    unsatCoreStats = null
                )
                if(res is Verifier.JoinedResult.Success) {
                    when(val r = trace.onUnsat(methodA = methodAContext, methodB = methodBContext,pairwiseProofManager)) {
                        is UnsatInterpretation.Override -> {
                            return handleSatInterpretation(r.s)
                        }
                        is UnsatInterpretation.Refine -> {
                            logger.info {
                                "There is more work to do, looping"
                            }
                            return equivalenceLoop(r.cont)
                        }
                        UnsatInterpretation.Verified -> {
                            logger.info {
                                "All done"
                            }
                        }
                    }
                } else if(res is Verifier.JoinedResult.Timeout) {
                    trace.onTimeout(methodAContext, methodBContext, pairwiseProofManager)?.let {
                        logger.info {
                            "Attempting to solve timeout with new context"
                        }
                        return equivalenceLoop(it)
                    }
                }
                return ruleResult
            }
        }
    }

    /**
     * Handle the sat interpretation.
     */
    @Suppress("ForbiddenMethodCall") // allow println for now
    private suspend fun handleSatInterpretation(
        interp: SatInterpretation
    ) : RuleCheckResult {
        return when(interp) {
            is SatInterpretation.GaveUp -> {
                logger.info {
                    "Giving up, taking the sat result ${interp.reason}"
                }
                interp.overallResult
            }
            is SatInterpretation.RealCounterExample -> {
                /**
                 * "Pretty" print the report to the console, and generate our temporary report
                 */
                val reportContent = this.javaClass.classLoader.getResourceAsStream("EquivalenceReport.html")?.bufferedReader()?.readText()
                check(reportContent == null || reportContent.indexOf("ADD_PARAMS_HERE") == reportContent.lastIndexOf("ADD_PARAMS_HERE")) {
                    "Malformed report content"
                }
                fun contextObj(label: String, tooltip: String?, value: String?) = buildJsonObject {
                    put("key", label)
                    put("tooltip", tooltip)
                    put("value", value)
                }

                fun IEvent.toJson() = buildJsonObject {
                    when(this@toJson) {
                        is IncompleteEvent -> {
                            put("sort", "Error in trace extraction")
                            put("warning", msg)
                        }
                        is EventWithData -> {
                            put("sort", when(sort) {
                                BufferTraceInstrumentation.TraceEventSort.REVERT -> "Function Revert"
                                BufferTraceInstrumentation.TraceEventSort.RETURN -> "Function Return"
                                BufferTraceInstrumentation.TraceEventSort.LOG -> "Log Emit"
                                BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL -> "External Call"
                                BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL -> "Internal Call"
                            })
                            if(sort.showBuffer) {
                                val r = bufferRepr?.asBufferRepr("")
                                put("rawBuffer", r)
                            }
                            put("context", buildJsonArray {
                                for(c in params) {
                                    val o = contextObj(c.label.displayLabel, c.label.description, c.value)
                                    add(o)
                                }
                            })
                        }

                        is Elaboration -> {
                            put("detail", what)
                        }
                        SyncEvent -> {
                            put("sync", true)
                        }
                    }
                }

                fun InputExplanation.toJson() = buildJsonObject {
                    put("caller", sender?.let {
                        "0x${it.toString(16)}"
                    })
                    put("value", messageValue?.toString())
                    val argList = buildJsonArray {
                        for(a in argumentFormatting) {
                            val obj = buildJsonObject {
                                put("name", a.name)
                                put("altName", a.altName)
                                put("value", a.value)
                            }
                            add(obj)
                        }
                    }
                    put("arguments", argList)
                }

                fun IEvent.toJsonString() = Json.encodeToString(JsonObject.serializer(), this.toJson())
                if(reportContent != null) {
                    val prefixStrA = interp.priorEventsA.orEmpty().let { prior ->
                        buildJsonArray {
                            for(p in prior) {
                                add(p.toJson())
                            }
                        }
                    }.let {
                        Json.encodeToString(JsonArray.serializer(), it)
                    }

                    val prefixStrB = interp.priorEventsB.orEmpty().let { priorB ->
                        buildJsonArray {
                            for(p in priorB) {
                                add(p.toJson())
                            }
                        }
                    }

                    val contractAStr = Json.encodeToString(String.serializer(), methodA.getContainingContract().name)
                    val contractBStr = Json.encodeToString(String.serializer(), methodB.getContainingContract().name)

                    val (eventAStr, eventBStr) = when(interp.diffExplanation) {
                        is MismatchExplanation.DifferentEvents -> {
                            interp.diffExplanation.eventInA.toJsonString() to interp.diffExplanation.eventInB.toJsonString()
                        }
                        is MismatchExplanation.MissingInA -> {
                            null to interp.diffExplanation.eventInB.toJsonString()
                        }
                        is MismatchExplanation.MissingInB -> {
                            interp.diffExplanation.eventInA.toJsonString() to null
                        }
                        is MismatchExplanation.StorageExplanation -> {
                            val methodADiff = interp.diffExplanation.contractAValue
                            val methodBDiff = interp.diffExplanation.contractBValue

                            fun String.toJsonString() = buildJsonObject {
                                put("sort", "Storage Mismatch")
                                put("context", buildJsonArray {
                                    add(contextObj(
                                        "Storage slot", "Internal prover representation of the storage slot; may or may not match an actual storage slot", interp.diffExplanation.slot
                                    ))
                                    add(contextObj(
                                        "Value after execution", "Value in the slot after execution", this@toJsonString
                                    ))
                                })
                            }.let {
                                Json.encodeToString(JsonObject.serializer(), it)
                            }

                            val storage1 = methodADiff.toJsonString()
                            val storage2 = methodBDiff.toJsonString()

                            storage1 to storage2
                        }
                    }
                    val inputsStr = Json.encodeToString(JsonObject.serializer(), interp.inputExplanation.toJson())

                    val initString = """
                        window.contractA = $contractAStr;
                        window.contractB = $contractBStr;

                        window.input = $inputsStr;
                        window.prefixA = $prefixStrA;
                        window.prefixB = $prefixStrB
                        window.eventA = $eventAStr;
                        window.eventB = $eventBStr;
                    """.trimIndent()
                    ArtifactManagerFactory().mainReportsDir.let {
                        File(it, "EquivalenceReport.html")
                    }.outputStream().bufferedWriter().use { out ->
                        out.write(reportContent.replace("ADD_PARAMS_HERE", initString))
                    }
                }


                if(interp.priorEventsA != null) {
                    println("There were ${interp.priorEventsA.size} call(s) prior to this event:")
                    interp.priorEventsA.forEach {
                        println(it.prettyPrint())
                    }
                } else {
                    println("Couldn't extract prior trace information")
                }

                val msg = when(interp.diffExplanation) {
                    is MismatchExplanation.DifferentEvents -> {
                        "The methods performed different actions.\n" +
                            "In ${methodA.pp()}:\n${interp.diffExplanation.eventInA.prettyPrint()}\n" +
                            "In ${methodB.pp()}:\n${interp.diffExplanation.eventInB.prettyPrint()}"

                    }
                    is MismatchExplanation.MissingInA -> {
                        "${methodB.pp()} included an event *missing* in ${methodA.pp()}:\n${interp.diffExplanation.eventInB.prettyPrint()}"
                    }
                    is MismatchExplanation.MissingInB -> {
                        "${methodA.pp()} included an event *missing* in ${methodB.pp()}:\n${interp.diffExplanation.eventInA.prettyPrint()}"
                    }
                    is MismatchExplanation.StorageExplanation -> {
                        "The executions left storage in conflicting states:\n" +
                            "The witness storage slot was ${interp.diffExplanation.slot}:" +
                            "\t! ${methodA.getContainingContract().name}: ${interp.diffExplanation.contractAValue}\n" +
                            "\t! ${methodB.getContainingContract().name}: ${interp.diffExplanation.contractBValue}"
                    }
                }
                println(msg)
                interp.ruleCheckResult
            }

            is SatInterpretation.Refine -> {
                logger.info {
                    "Attempting to refine with: ${interp.cont.javaClass.name}"
                }
                equivalenceLoop(interp.cont)
            }
        }
    }

    /**
     * Class meant to encapsulate/coordinate the fine-tuning of instrumentation w.r.t. precision. Records the bounded
     * precision windows to be used for each method, and the pairwise agreements found via pairwise buffer equivalence.
     *
     * Considered preferable to passing around the component maps and hoping everyone uses them correctly.
     */
    class PairwiseProofManager {
        /**
         * Used to record the (reach-var, value) pairs used in the overriding of sites in [methodB]
         */
        @KSerializable
        @Treapable
        data class PairwiseAgreement(
            val flagA: TACSymbol.Var,
            val agreedValue: Int
        ) : AmbiSerializable

        companion object {
            private fun PairwiseAgreement.toGate() =
                flagA.asSym() to this.agreedValue.asTACExpr

            private fun List<PairwiseAgreement>.toGates() = this.map {
                it.toGate()
            }
        }

        /**
         * Records the overriding value [manual] for a site in [methodA] and the variable [reachedVar] which
         * is used to record when that site is reached.
         */
        @KSerializable
        data class SiteAOverride(
            val manual: Int,
            val reachedVar: TACSymbol.Var
        ) : AmbiSerializable {
            fun toSiteOverride() = BufferTraceInstrumentation.TraceOverrideSpec.CompleteOverride(manual.asTACExpr)
        }

        /**
         * Memento for serializing the current state. For debugging/dev sanity only
         */
        @KSerializable
        data class Memento(
            val aSiteOverride: Map<CmdPointer, SiteAOverride>,
            val bSitePartners: Map<CmdPointer, List<PairwiseAgreement>>,
            val nextSite: Int,

            val bOverrides: Map<CmdPointer, Int>,
            val aOverrides: Map<CmdPointer, Int>,

            val methodAReached: Map<CmdPointer, TACSymbol.Var>
        )

        private val aSiteOverrides: MutableMap<CmdPointer, SiteAOverride> = mutableMapOf()

        private val bSitePartners : MutableMap<CmdPointer, MutableList<PairwiseAgreement>> = mutableMapOf()

        private val aMloadOverrides = mutableMapOf<CmdPointer, Int>()

        private val bMLoadOverrides = mutableMapOf<CmdPointer, Int>()

        /**
         * Monotonically increasing number used to invent fresh numbers to use
         * for the override of the buffer identity at sites in A
         */
        private val siteIdentifier = AtomicInteger(0)

        fun isSummarized(aSite: CmdPointer) = aSite in aSiteOverrides

        /**
         * For [aSite] to be summarized, making buffer computation easier.
         */
        fun forceSummarized(aSite: CmdPointer) {
            aSiteOverrides.getOrPut(aSite) {
                val reached = getMethodAReach(aSite)
                SiteAOverride(siteIdentifier.getAndIncrement(), reachedVar = reached)
            }
        }

        // none of these are proper merge functions, but who cares
        fun loadMemento(m: Memento) {
            while(true) {
                val curr = siteIdentifier.get()
                val nextSite = max(m.nextSite, curr)
                if(siteIdentifier.compareAndSet(curr, nextSite)) {
                    break
                }
            }
            aSiteOverrides.putAll(m.aSiteOverride)
            for((k, v) in m.bSitePartners) {
                bSitePartners.getOrPut(k) { mutableListOf() }.addAll(v)
            }
            aMloadOverrides.putAll(m.aOverrides)
            bMLoadOverrides.putAll(m.bOverrides)
            methodAReached.putAll(m.methodAReached)
        }

        private val methodAReached = mutableMapOf<CmdPointer, TACSymbol.Var>()

        private fun getMethodReachVar(m: MutableMap<CmdPointer, TACSymbol.Var>, where: CmdPointer) : TACSymbol.Var = m.getOrPut(where) {
            TACSymbol.Var("siteReachedMarker!${siteIdentifier.getAndIncrement()}", Tag.Bool, NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))
        }

        private fun getMethodAReach(v: CmdPointer) = getMethodReachVar(methodAReached, v)

        fun save(): Memento {
            return Memento(aSiteOverrides, bSitePartners, siteIdentifier.get(), aMloadOverrides, bMLoadOverrides, methodAReached)
        }

        /**
         * Record that the buffers used at long reads [aSite] in [methodA] and [bSite] in [methodB]
         * must be the same, and update the buffer identity overrides accordingly
         */
        fun registerPairwiseProof(aSite: CmdPointer, bSite: CmdPointer) {

            val reach = aSiteOverrides.getOrPut(aSite) {
                val aReachedVar = getMethodAReach(aSite)
                val agreedConstant = siteIdentifier.getAndIncrement()
                SiteAOverride(manual = agreedConstant, reachedVar = aReachedVar)
            }
            bSitePartners.getOrPut(bSite, ::mutableListOf).add(PairwiseAgreement(
                agreedValue = reach.manual,
                flagA = reach.reachedVar,
            ))
        }

        /**
         * Get the buffer identity overrides for [methodA]
         */
        fun getAOverrides() : Map<CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec> = aSiteOverrides.mapValues {
            it.value.toSiteOverride()
        }

        /**
         * Get the buffer identity overrides for [methodB]
         */
        fun getBOverrides() = bSitePartners.mapValues { (_, partners) ->
            partners.toGates().let(BufferTraceInstrumentation.TraceOverrideSpec::ConditionalOverrides)
        }

        fun getAUseSiteControl(): Map<TCmdPointer<METHODA>, BufferTraceInstrumentation.UseSiteControl> = methodAReached.mapValues {
            BufferTraceInstrumentation.UseSiteControl(
                trackBufferContents = false,
                traceReached = it.value
            )
        }

        fun getBUseSiteControl(): Map<TCmdPointer<METHODB>, BufferTraceInstrumentation.UseSiteControl> = mapOf()

        /**
         * Get the bounded precision windows (expressed as a map from mload locations to window size) for [methodA]
         */
        fun getAMloadOverrides() : Map<CmdPointer, Int> = aMloadOverrides

        /**
         * Get the bounded precision windows (expressed as a map from mload locations to window size) for [methodB]
         */
        fun getBMloadOverrides() : Map<CmdPointer, Int> = bMLoadOverrides

        /**
         * Register that the mload at [where] in [methodB] should use a bounded precision window
         * of size [depth]
         */
        fun registerBLoadOverride(where: CmdPointer, depth: Int) : Boolean {
            return registerLoadOverride(bMLoadOverrides, where, depth)
        }

        /**
         * Register that the mload at [where] in [methodA] should use a bounded precision window
         * of size [depth]
         */
        fun registerALoadOverride(where: CmdPointer, depth: Int) : Boolean {
            return registerLoadOverride(aMloadOverrides, where, depth)
        }

        private fun registerLoadOverride(
            overrideMap: MutableMap<CmdPointer, Int>,
            where: CmdPointer,
            depth: Int
        ): Boolean {
            if (where !in overrideMap || overrideMap[where]!! < depth) {
                overrideMap[where] = depth
                return true
            }
            return false
        }
    }


    /**
     * An event that is of interest, as extracted from the [BufferTraceInstrumentation.TraceIndexMarker]
     * annotation. This annotation appeared at [vcProgramSite] in the program sent to the solver, and
     * was found at [origProgramSite] in the pre-inlined, instrumented version.
     */
    internal data class LTraceEventMarker<Which: METHOD_MARKER>(
        val origProgramSite: CmdPointer,
        val vcProgramSite: CmdPointer,
        val marker: BufferTraceInstrumentation.TraceIndexMarker
    )

    /**
     * [LTraceEventMarker] stored in [trace] with some information
     * about the enclosing method in [context].
     */
    internal data class LTraceWithContext<Which: METHOD_MARKER>(
        val trace: LTraceEventMarker<Which>,
        val context: InlinedInstrumentation<Which>
    )

    /**
     * Attempt to keep me from mixing method a and method b. Several classes here have a ghost type
     * parameter which indicates which method this is describing.
     */
    @Suppress("ClassName")
    internal sealed interface METHOD_MARKER

    /**
     * Dummy type indicating "for method A"
     */
    object METHODA : METHOD_MARKER

    /**
     * dummy type indicating "for method B"
     */
    object METHODB : METHOD_MARKER

    private val json = Json { allowStructuredMapKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun handleEquivalence(): List<RuleCheckResult> {
        val x = System.getProperty("save.state")
        if(x != null && x != "" && File(x).exists()) {
            File(x).inputStream().use {
                json.decodeFromStream(PairwiseProofManager.Memento.serializer(), it)
            }.let(pairwiseProofManager::loadMemento)
        }
        val ruleCheckResult = try {
            equivalenceLoop(
                Explorer(
                    InstrumentationLevels(
                        inclusion = BufferTraceInstrumentation.TraceInclusionMode.Until(0),
                        traceLevel = BufferTraceInstrumentation.TraceTargets.Calls,
                    ),
                    QueryContext(methodA, methodB, scene)
                )
            )
        } finally {
            if (x != null && x != "") {
                File(x).outputStream().use {
                    json.encodeToStream(pairwiseProofManager.save(), it)
                }
            }
        }
        return listOf(ruleCheckResult)
    }

}
