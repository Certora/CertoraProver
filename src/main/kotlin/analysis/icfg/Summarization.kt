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

package analysis.icfg

import analysis.*
import analysis.icfg.Summarizer.getExplicitSummariesForInvocation
import analysis.icfg.Summarizer.resolveCandidates
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import cli.SummaryResolutionPolicy
import com.certora.collect.*
import compiler.SourceSegment
import config.Config
import config.ConfigType
import config.ReportTypes
import datastructures.ArrayHashMap
import datastructures.stdcollections.*
import log.*
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.IScene
import spec.CVL
import spec.CVLCompiler
import spec.IWithSummaryInfo
import utils.Range
import spec.cvlast.SpecCallSummary
import tac.DumpTime
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.tacexprutil.ExprUnfolder
import verifier.ChainedCoreTransformers
import verifier.CoreToCoreTransformer
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.SUMMARIZATION)

object Summarization {
    /**
     * State global to the entire summarization process, i.e. state that persists across iterations of the loop in [handleSummaries] below
     */
    private data class SummarizationContext(
        val withSummaries: IWithSummaryInfo,
        val scene: IScene,
        val cvlCompiler: CVLCompiler?,
        val manager: InliningDecisionManager.PostStorageAnalysis
    ) : IWithSummaryInfo by withSummaries

    context(SummarizationContext)
    private fun handleSummaries(
        code: CoreTACProgram
    ) : CoreTACProgram {
        var modified = true
        var itCode = code
        val failsafeLimit = 100 // This is just a failsafe - to prevent an infinite loop if we have a bug in the logic here.
        var i = 0
        while (modified && i < failsafeLimit) {
            i++
            logger.debug {
                "Starting summarization round $i for ${code.name}"
            }
            val (newCode, newModified) =
                summarizeRemainingCallSummaries(
                    i,
                    itCode
                ).let { (updatedCode, externalModified, internalLinking) ->
                    logger.debug {
                        "Outer loop round $i: external pass modified the program? $externalModified"
                    }
                    val (withInternalSummarization, internalModified) = InternalSummarizer.summarizeInternalFunctions(updatedCode, internalSummaries, InternalSummarizer.ExpressionSummaryMaterializer(
                        scene = scene,
                        cvlCompiler = cvlCompiler,
                        internalLinking = internalLinking
                    ))
                    logger.debug {
                        "Outer loop round $i: internal pass modified the program? $internalModified"
                    }
                    cleanupAndLinkInternalSummaries(withInternalSummarization, internalLinking) to (externalModified || internalModified)
                }
            itCode = newCode
            modified = newModified
            ArtifactManagerFactory().dumpCodeArtifacts(itCode, ReportTypes.SUMMARIES_LOOP_ITER, DumpTime.AGNOSTIC)
        }
        if (i == failsafeLimit) {
            throw CertoraInternalException(CertoraInternalErrorType.SUMMARY_INLINING, "Summary inlining loop didn't end normally")
        }
        return itCode.withStateExtensions(Inliner.ExtendedStateVars(manager.getExtendedState()))
    }

    /**
     * Find those call summaries within [c] that rely on the output of an internal call
     * summary for their callee. If we successfully registered a linking variable in [internalLinking]
     * for this internal call summary output, add an assume that constrains the callee at the call-site
     * to be equal to this linking variable.
     *
     * regardless of whether linking succeeded for these
     * call-sites, scrub the reference to the internal call summary if it no longer exists (because it has been inlined).
     *
     * Note that this is how we ensure progress in the [handleSummaries] loop: if an external summary S is not inlined in one
     * invocation of [summarizeRemainingCallSummaries], it is because S references an internal call summary output. Because
     * we *always* summarize all extant internal call summaries after [summarizeRemainingCallSummaries], we are guaranteed
     * to scrub the reference to the internal call summary, ensuring that S is not delayed again.
     */
    private fun cleanupAndLinkInternalSummaries(
        c: CoreTACProgram,
        internalLinking: InternalLinkingState
    ) : CoreTACProgram {
        val liveIds = c.parallelLtacStream().mapNotNull {
            it.snarrowOrNull<InternalCallSummary>()?.id
        }.collect(Collectors.toSet())
        return c.parallelLtacStream().mapNotNull {
            val summ = it.snarrowOrNull<CallSummary>() ?: return@mapNotNull null
            val internalSummCallee = summ.callTarget.singleOrNull() as? CallGraphBuilder.CalledContract.InternalFunctionSummaryOutput ?: return@mapNotNull null
            if(internalSummCallee.which in liveIds) {
                return@mapNotNull null
            }
            logger.debug {
                "Linking internal function: $it"
            }
            val linkVar = internalLinking.getLinkOutput(internalSummCallee.which, internalSummCallee.ordinal)
            val summReplacement = CommandWithRequiredDecls<TACCmd.Simple>(listOf(
                it.narrow<TACCmd.Simple.SummaryCmd>().cmd.copy(
                    summ = summ.copy(
                        callTarget = setOf(CallGraphBuilder.CalledContract.Unresolved)
                    )
                )
            )).letIf(linkVar != null) { rewritten ->
                ExprUnfolder.unfoldPlusOneCmd("link", with(TACExprFactTypeCheckedOnlyPrimitives) {
                    Eq(linkVar!!.asSym(), summ.toVar.asSym())
                }) {
                    TACCmd.Simple.AssumeCmd(it.s)
                }.merge(rewritten)
            };
            { patcher: SimplePatchingProgram ->
                patcher.replaceCommand(it.ptr, summReplacement)
            }
        }.patchForEach(c, true) { thunk ->
            thunk(this)
        }
    }

    fun handleSummaries(
        code: CoreTACProgram,
        scene: IScene,
        summaries: IWithSummaryInfo,
        cvlCompiler: CVLCompiler?
    ): CoreTACProgram {
        val manager = InliningDecisionManager.PostStorageAnalysis(
            scene = scene,
            code = code
        )

        return SummarizationContext(
            scene = scene,
            cvlCompiler = cvlCompiler,
            withSummaries = summaries,
            manager = manager
        ).run {
            handleSummaries(code)
        }
    }

    /**
     * Given a mapping of external function signatures to a spec call summary as the receiver,
     * and a [scene], where the callee is known to be [calleeID] (or null) and the sighash has been
     * resolved to be [sigHash], return true if there is a summary that should be applied for an *already resolved/inlined*
     * method.
     */
    fun Map<CVL.SummarySignature.External, SpecCallSummary>.hasSummaryForResolved(
        scene: IScene,
        calleeID: BigInteger?,
        sigHash: BigInteger,
    ) : Boolean {
        return this.any { (sig, summ) ->
            summ.summarizeAllCalls &&
            when(sig) {
                is CVL.ExternalAnyInContract -> {
                    calleeID != null && scene.getContractOrNull(calleeID)?.name == sig.hostContract.name
                }
                is CVL.SummarySignature.ExternalWithSignature -> {
                    sigHash == sig.sighashInt.n && when(sig) {
                        is CVL.ExternalExact -> {
                            calleeID != null && scene.getContractOrNull(calleeID)?.name == sig.signature.qualifiedMethodName.host.name
                        }
                        is CVL.ExternalWildcard -> true
                    }
                }

                CVL.ExternalUnresolved -> throw IllegalStateException("Trying to apply an external unresolved summary on a resolved call summary.")
            } &&
                // if the summary is a dispatcher, it should only be
                // applied if the callee was unresolved
                (summ !is SpecCallSummary.Dispatcher || calleeID == null)
        }
    }

    /**
     * Interface for choosing which summaries should be inlined, and recording what linking
     * information need to be propagated during summarization
     */
    private interface SummarizationDecisionManager {
        /**
         * Given 3 queues of summaries: [explicit] (those call-sites that match a non-havoc, non-dispatch summary),
         * [dispatch] (those call-sites that match a dispatch summary), and [havoc] (those call-sites that
         * should be havoced) choose which summaries to operate upon by returning the list of summaries from this
         * method. For termination, if a summary is not included in the returned list, something in the later
         * summarization stages should *eventually* make the summary eligible for summarization.
         *
         * [internalLinker] is used to record that a call-site is not being handled because its callee relies on the value
         * returned from an as yet unmaterialized internal function summary. Internal summarization inlining
         * will make a best effort to propagate the value returned at the relevant position to the dependent call-site.
         *
         * [externalLinker] serves a similar purpose when a summary is not inlined because its callee relies on the
         * value returned from an as yet unmaterialized external function summary. Like internal summarization above,
         * the external summarization process will make a best effort to propagate information about the
         * relevant summary return value to the dependent call-site(s).
         */
        fun chooseWork(
            explicit: List<QueuedSummary<ConcreteSummary.ExplicitSummary>>,
            dispatch: List<QueuedSummary<DispatchSummary>>,
            havoc: List<QueuedSummary<ConcreteSummary.Havoc>>,
            internalLinker: InternalLinkingState,
            externalLinker: Summarizer.LinkingState<BigInteger>
        ): List<QueuedSummary<*>>
    }

    private object SimpleSummarizationPolicy : SummarizationDecisionManager {
        /**
         * Simple: just inline everything in one go, havocs, concrete, dispatch
         */
        override fun chooseWork(
            explicit: List<QueuedSummary<ConcreteSummary.ExplicitSummary>>,
            dispatch: List<QueuedSummary<DispatchSummary>>,
            havoc: List<QueuedSummary<ConcreteSummary.Havoc>>,
            internalLinker: InternalLinkingState,
            externalLinker: Summarizer.LinkingState<BigInteger>
        ): List<QueuedSummary<*>> {
            return explicit + dispatch + havoc
        }
    }

    private object AggressiveSummarizationPolicy : PropagatingSummarizationPolicy() {
        /**
         * Only inline dispatch and havoc summaries if there are no unhandled external
         * summaries or internal summaries (aka, summary inlining won't help any unresolved callees).
         */
        override fun chooseWork(
            explicit: List<QueuedSummary<ConcreteSummary.ExplicitSummary>>,
            dispatch: List<QueuedSummary<DispatchSummary>>,
            havoc: List<QueuedSummary<ConcreteSummary.Havoc>>,
            internalLinker: InternalLinkingState,
            externalLinker: Summarizer.LinkingState<BigInteger>
        ): List<QueuedSummary<*>> {
            val toRet = explicit.toMutableList<QueuedSummary<*>>()
            toRet.addAll(this.linkUnresolved(dispatch.asSequence() + havoc.asSequence(), explicit, externalLinker, internalLinker))
            return toRet
        }

    }

    private object TieredSummarizationPolicy : PropagatingSummarizationPolicy() {
        /**
         * Always inline concrete and havocs, but wait to inline dispatch summaries until there
         * are no more explicit summaries and no unresolved internal function summaries.
         */
        override fun chooseWork(
            explicit: List<QueuedSummary<ConcreteSummary.ExplicitSummary>>,
            dispatch: List<QueuedSummary<DispatchSummary>>,
            havoc: List<QueuedSummary<ConcreteSummary.Havoc>>,
            internalLinker: InternalLinkingState,
            externalLinker: Summarizer.LinkingState<BigInteger>
        ): List<QueuedSummary<*>> {
            val toRet = explicit.toMutableList<QueuedSummary<*>>()
            toRet.addAll(havoc)
            toRet.addAll(this.linkUnresolved(dispatch.asSequence(), explicit, externalLinker, internalLinker))
            return toRet
        }
    }

    /**
     * Helper class for adding linking information and compute which summaries should be acted upon vs. deferred
     * due to potential linking.
     */
    private abstract class PropagatingSummarizationPolicy : SummarizationDecisionManager {
        protected fun linkUnresolved(
            s: Sequence<QueuedSummary<*>>,
            explicit: List<QueuedSummary<ConcreteSummary.ExplicitSummary>>,
            externalLinker: Summarizer.LinkingState<BigInteger>,
            internalLinker: InternalLinkingState
        ) : List<QueuedSummary<*>> {
            val willInline = explicit.mapNotNullToTreapSet {
                (it.summaryCmd.cmd.summ as CallSummary).summaryId
            }
            var haveInternalLinking = false
            val haveExplicit = explicit.isNotEmpty()
            val toRet = mutableListOf<QueuedSummary<*>>()
            for(q in s) {
                val summ = q.summaryCmd.cmd.summ as CallSummary
                val callee = summ.callTarget.singleOrNull()
                when(callee) {
                    null,
                    is CallGraphBuilder.CalledContract.CreatedReference,
                    is CallGraphBuilder.CalledContract.FullyResolved,
                    is CallGraphBuilder.CalledContract.Invalidated,
                    is CallGraphBuilder.CalledContract.SymbolicInput,
                    is CallGraphBuilder.CalledContract.UnresolvedRead,
                    is CallGraphBuilder.CalledContract.Unresolved-> {
                        // nothing to do here linking wise
                    }
                    // defer doing inlining here until we inline the summary body
                    is CallGraphBuilder.CalledContract.InternalFunctionSummaryOutput -> {
                        haveInternalLinking = true
                        internalLinker.setupLinkResolution(callee.which, callee.ordinal)
                    }
                    // ditto here, defer doing inlining until we've inlined the callee summary
                    is CallGraphBuilder.CalledContract.SymbolicOutput -> {
                        if(callee.which in willInline) {
                            externalLinker.setupLinkResolution(callee.which, callee.offset)
                        }
                    }
                }
                // conditionally accumulate the summaries of s into toRet, until we see
                // an internal linking, in which case we stop adding to the list
                // nb that below we explicitly return an empty list, discarding any
                // partial accumulation
                if(!haveExplicit && !haveInternalLinking) {
                    toRet.add(q)
                }
            }
            return if(!haveInternalLinking) {
                toRet
            } else {
                listOf()
            }
        }
    }

    /**
     * The result of a complete round of external summarization, i.e., after the loopin [summarizeRemainingCallSummaries]
     * is complete. The result of this round is the [code] which may or may not have been [modified].
     * In addition, some summaries may not be acted upon because there is unresolved [internalLinking];
     * these summaries will be revisited once internal summarization is done in the [handleSummaries] loop.
     */
    private data class ExternalSummarizationLoopResult(
        val code: CoreTACProgram,
        val modified: Boolean,
        val internalLinking: InternalLinkingState
    )

    /**
     * Handle remaining summaries in code
     * @return [ExternalSummarizationResult] which is the result after the external summarization process has stabilized.
     * Note that there may be still unresolved/summarized call summaries in the returned program, as explained
     * in [ExternalSummarizationLoopResult]
     */
    context(SummarizationContext)
    private fun summarizeRemainingCallSummaries(
        outerSummaryLoop: Int, // debugging only
        code: CoreTACProgram,
    ): ExternalSummarizationLoopResult {
        // resolve any remaining call nodes with our summaries
        fun findCallSummaryCmds(code: CoreTACProgram): List<LTACCmdView<TACCmd.Simple.SummaryCmd>> {
            return code.ltacStream().mapNotNull { lc ->
                lc.maybeNarrow<TACCmd.Simple.SummaryCmd>()
                    ?.takeIf { summ -> summ.cmd.summ is CallSummary }
            }.collect(Collectors.toList())
        }


        var modified = false
        val runCalleeAnalysis = Config.SummaryResolutionMode.get().runCalleeAnalysis
        fun optionalCalleeResolution(c: CoreTACProgram) = if(runCalleeAnalysis) {
            calleeAnalysis(c)
        } else {
            c
        }
        /**
         * Eagerly inline after running the call resolution. We do this because some
         * external calls may be resolvable after a single round of callee resolution, due to
         * internal summaries that were inlined in a previous iteration of the outer summarization
         * loop in [handleSummaries]
         */
        fun optionalCallResolutionAndInline(c: CoreTACProgram) : CoreTACProgram {
            val withRes = optionalCalleeResolution(c)
            return Inliner.inlineRemainingDirectCalls(scene, withRes, manager) { summ, contractId ->
                // We don't want to inline calls that need to be summarized (in the sense of method-block summaries) anyway.
                !externalSummaries.hasSummaryForResolved(
                    scene,
                    calleeID = contractId, // note about self links - the InterContract Call Resolver (ICC) should resolve them to regular fully-resolved addresses
                    sigHash = summ.sigResolution.singleOrNull() ?: return@inlineRemainingDirectCalls true
                )
            }
        }
        var itCode = optionalCallResolutionAndInline(code)
        var callSummaryCmds = findCallSummaryCmds(itCode)
        var round = 0
        logger.info {
            "Starting external summarization loop for ${code.name} in outer round $outerSummaryLoop"
        }
        /**
         * The set of [CmdPointer]s to [CallSummary] that were seen in the
         * last iteration of the loop.
         */
        var prevSummaryPointers = treapSetOf<CmdPointer>()

        /**
         * The set of [CmdPointer] to [CallSummary] seen in the current program (i.e., itCode).
         * When this equals prevSummaryPointers, the loop below terminates: we have seen all
         * CallSummaries we are going to see this round, and any call summaries remaining are waiting
         * on internal summarization to run before being handled.
         */
        var currWorkSet = treapSetOf<CmdPointer>()

        /**
         * Accumulator for information about which call sites currently rely on the output of a internal call
         * summary for callee resolution. Returned back to plumb linking information via internal summary materialization.
         */
        val internalLinkingState = InternalLinkingState()

        while (run {
            currWorkSet = callSummaryCmds.mapToTreapSet { it.ptr }
            currWorkSet
        } != prevSummaryPointers) {
            round++
            prevSummaryPointers = currWorkSet

            logger.info { "In ${code.name} external round $round ($outerSummaryLoop), have ${callSummaryCmds.size} remaining call summaries" }

            var seenCallSummaryCallIds = callSummaryCmds.mapToSet { it.ptr.block.calleeIdx }

            val res = externalSummarizationRound(itCode, callSummaryCmds, internalLinkingState)
            itCode = res.ctp
            modified = res.modified || modified


            logger.info { "In ${code.name} external round $round ($outerSummaryLoop), running callee analysis" }
            itCode = optionalCallResolutionAndInline(itCode)

            callSummaryCmds = findCallSummaryCmds(itCode)
            val newCallSummaries = callSummaryCmds.count {
                it.ptr.block.calleeIdx !in seenCallSummaryCallIds
            }
            logger.info { "In ${code.name} external round $round ($outerSummaryLoop), post callee analysis: ${callSummaryCmds.size} ($newCallSummaries new) remaining..." }

            seenCallSummaryCallIds = callSummaryCmds.mapToSet { it.ptr.block.calleeIdx }
            if(round > 100) {
                throw CertoraInternalException(CertoraInternalErrorType.SUMMARY_INLINING, "Summary inlining loop didn't end normally")
            }
        }
        logger.info {
            "External summarization for ${code.name} in outer summary loop round $outerSummaryLoop, completed in $round round(s), ${callSummaryCmds.size} remain unhandled"
        }

        return ExternalSummarizationLoopResult(itCode, modified, internalLinkingState)
    }

    private fun calleeAnalysis(itCode: CoreTACProgram): CoreTACProgram {
        return ChainedCoreTransformers(
            listOf(
                CoreToCoreTransformer(
                    ReportTypes.CALLEE_ANALYSIS,
                    CalleeAnalysis::resolveCallees
                )
            )
        ).transform(itCode)
    }

    /**
     * A class distinguishing between different reasons for summarization, wrapping around a [SpecCallSummary].
     */
    @KSerializable
    @Treapable
    sealed interface AppliedSummary: AmbiSerializable {

        /**
         * Marker interface for summaries applied as a result of an input given by the user.
         */
        sealed interface FromUserInput: AppliedSummary

        val specCallSumm: SpecCallSummary

        /**
         * Summary introduced as a result of a CVL summary in the methods block, as given by [summarizedMethod].
         */
        @KSerializable
        data class MethodsBlock(override val specCallSumm: SpecCallSummary.ExpressibleInCVL,
                                val summarizedMethod: CVL.SummarySignature) : FromUserInput

        sealed interface AutoNondetSummary {
            val context: String
            val specCallSumm: SpecCallSummary.HavocSummary
            val configFlag: ConfigType<*>
        }

        /**
         * A summary introduced due to a global tool configuration.
         */
        @KSerializable
        sealed interface Config : FromUserInput {

            @KSerializable
            object ExtLibraryNondet : Config, AutoNondetSummary {
                override val specCallSumm: SpecCallSummary.HavocSummary.Nondet
                    get() = SpecCallSummary.HavocSummary.Nondet(
                        range = Range.Empty(),
                        summarizationMode = SpecCallSummary.SummarizationMode.ALL
                    )
                override val configFlag: ConfigType<*>
                    get() = config.Config.SummarizeExtLibraryCallsAsNonDetPreLinking

                fun readResolve(): Any = ExtLibraryNondet
                override val context: String
                    get() = "external delegate calls"


                override fun hashCode() = utils.hashObject(this)
            }

            @KSerializable
            object ExtContractNondet : Config, AutoNondetSummary {
                override val context: String
                    get() = "unresolved external calls"

                override val specCallSumm: SpecCallSummary.HavocSummary.Nondet
                    get() = SpecCallSummary.HavocSummary.Nondet(
                        range = Range.Empty(),
                        summarizationMode = SpecCallSummary.SummarizationMode.ALL
                    )
                override val configFlag: ConfigType<*>
                    get() = config.Config.SummarizeUnresolvedAsNondet

                fun readResolve(): Any = ExtContractNondet
                override fun hashCode() = utils.hashObject(this)
            }

            @KSerializable
            object AutoDispatcher : Config {
                override val specCallSumm: SpecCallSummary.Dispatcher
                    get() = SpecCallSummary.Dispatcher(
                        range = Range.Empty(),
                        optimistic = true,
                        useFallback = false,
                        summarizationMode = SpecCallSummary.SummarizationMode.ALL
                    )
                fun readResolve(): Any = AutoDispatcher
                override fun hashCode() = utils.hashObject(this)
            }

            @KSerializable
            object DispatchOnCreate : Config {
                override val specCallSumm: SpecCallSummary.Dispatcher
                    get() = SpecCallSummary.Dispatcher(
                        range = Range.Empty(),
                        optimistic = false,
                        useFallback = false,
                        summarizationMode = SpecCallSummary.SummarizationMode.ALL
                    )
                fun readResolve(): Any = DispatchOnCreate
                override fun hashCode() = utils.hashObject(this)
            }

            /**
             * This dispatcher will be added to resolve EVM methods that
             * are _not_ inlined by the [Inliner] (i.e. there is more than one call target that a call resolves to,
             * see following example).
             * If we have in source code
             *
             * if(){
             *      return a.foo();
             * } else{
             *      return b.foo();
             * }
             *
             * the compiler translates this to (if solc_optimize is enabled)
             *
             * c = null
             * if(){
             *     c = a;
             * } else{
             *     c = b;
             * }
             * c.foo()
             *
             * If we have links a=ContractA and b=ContractB, the call target of c will depend on the branching.
             * But if we know all callees are [CallGraphBuilder.CalledContract.FullyResolved] we use the regular dispatcher flow
             * to explode this pattern back to
             *
             * c = null
             * if(){
             *     c = a;
             * } else{
             *     c = b;
             * }
             * if(c == a){
             *     c.foo()
             * } else if (b == c){
             *     c.foo()
             * }
             */
            @KSerializable
            object LateInliningDispatcher : Config {
                override val specCallSumm: SpecCallSummary.Dispatcher
                    get() = SpecCallSummary.Dispatcher(
                        range = Range.Empty(),
                        optimistic = false,
                        useFallback = false,
                        summarizationMode = SpecCallSummary.SummarizationMode.ALL
                    )
                fun readResolve(): Any = LateInliningDispatcher
                override fun hashCode() = utils.hashObject(this)
            }

            @KSerializable
            object OptimisticFallback : Config {
                override val specCallSumm: SpecCallSummary.OptimisticFallback
                    get() = SpecCallSummary.OptimisticFallback
                fun readResolve(): Any = OptimisticFallback
                override fun hashCode() = utils.hashObject(this)
            }
        }

        /**
         * A summary introduced as a result of a decision made by the Prover
         * (e.g., the Prover used it as a default summary).
         */
        @KSerializable
        data class Prover(override val specCallSumm: SpecCallSummary) : AppliedSummary
    }

    private sealed interface SummarySpec

    private sealed class DispatchSummary : SummarySpec {
        /**
         * Use the fallback dispatcher
         */
        data object OptimisticFallback : DispatchSummary()

        /**
         * Dispatch according to [summ]
         */
        data class Dispatcher(val summ: AppliedSummary.FromUserInput, val sigHash: BigInteger?) : DispatchSummary()
    }

    /**
     * A concrete summary is any non-dispatcher summary, in that we are concretely summarizing the behavior, not just
     * replacing the call with a dispatch.
     */
    private sealed class ConcreteSummary : SummarySpec {
        /**
         * use the prover auto havoc
         */
        data object Havoc : ConcreteSummary()

        /**
         * Explicitly summarize according to [summ]
         */
        data class ExplicitSummary(val summ: AppliedSummary.FromUserInput, val sigHash: BigInteger?) : ConcreteSummary()
    }

    /**
     * Representation of a queued summary inlining decision.
     */
    private data class QueuedSummary<T: SummarySpec>(
        /**
         * Address of the contract that contains the summarized call site
         */
        val currAddress: BigInteger,
        /**
         * The call summary command
         */
        val summaryCmd: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        val selectedSummary: T
    )

    context(SummarizationContext, InliningRoundContext)
    private fun externalSummarizationRound(
        code: CoreTACProgram,
        needSummarization: List<LTACCmdView<TACCmd.Simple.SummaryCmd>>,
    ) : ExternalSummarizationResult {
        val patching = code.toPatchingProgram()

        val concreteQueue = mutableListOf<QueuedSummary<ConcreteSummary.ExplicitSummary>>()
        val havocQueue = mutableListOf<QueuedSummary<ConcreteSummary.Havoc>>()
        val dispatchQueue = mutableListOf<QueuedSummary<DispatchSummary>>()

        for (summ in needSummarization) {
            val currAddress = code.procedures.find {
                 it.callId == summ.ptr.block.calleeIdx
            }?.procedureId?.address?.asBigInteger() // we take the current location's caller using the class id and procedure info
                ?: error("we should be summarizing from within a known procedure")
            val callSumm = summ.cmd.summ as CallSummary
            if (callSumm.inSize == TACSymbol.lift(0) && !Config.OptimisticFallback.get()) {
                // alert to user that he could have enabled optimistic fallback here
                val containingFuncStart = getContainingInternalFuncStart(summ.ptr, code.analysisCache.graph)
                val containingFuncSource = containingFuncStart?.callSiteSrc?.getSourceDetails()
                val cmdSource = summ.cmd.metaSrcInfo?.getSourceDetails()

                val callDescription = optimisticFallbackCallDescription(
                    programName = code.name,
                    containingFuncName = containingFuncStart?.methodSignature?.toNamedDecSignature(),
                    containingFuncSource
                )

                CVTAlertReporter.reportAlert(
                    CVTAlertType.SUMMARIZATION,
                    CVTAlertSeverity.INFO,
                    containingFuncSource ?: cmdSource,
                    "A call in $callDescription takes 0 arguments and therefore can be summarized with `--optimistic_fallback`.",
                    hint = null,
                    CheckedUrl.CLI_OPTIMISTIC_FALLBACK,
                )
            }

            if (callSumm.inSize == TACSymbol.lift(0) && Config.OptimisticFallback.get()) {
                dispatchQueue.add(
                    QueuedSummary(
                        currAddress,
                        selectedSummary = DispatchSummary.OptimisticFallback,
                        summaryCmd = summ
                    )
                )
            } else {
                val sigHash = callSumm.sigResolution.singleOrNull()
                // Resolving which summary is best suited. If the user specified a matching summary
                // in the methods block, use the highest priority one. Else, choose a fallback.
                // Finally, if multiple fallbacks exists we will write a warning and choose havoc.
                val explicitSummary = if(sigHash != null) {
                    val hostContractId = callSumm.callTarget.map { (it as? CallGraphBuilder.CalledContract.FullyResolved)?.contractId }.singleOrNull()
                    getExplicitSummariesForInvocation(
                        sigHash = sigHash,
                        hostContractId = hostContractId,
                        nameLookup = {
                            scene.getContractOrNull(it)?.name
                        },
                        summaries = externalSummaries,
                        onExactSummaryMiss = { sig ->
                            Logger.alwaysWarn(
                                "In ${code.name}, found a call to ${
                                    summ.cmd.metaSrcInfo?.getSourceCode() ?: "unknown source calling 0x${
                                        sigHash.toString(
                                            16
                                        )
                                    }"
                                } where the base contract could not be found to match ${sig.signature.qualifiedMethodName.host}; ${sig.signature}'s explicit summary will not be applied"
                            )
                        }
                    )
                } else {
                    methodCallStack.iterateUpCallersMethodOnly((summ.ptr)).firstOrNull()?.let { call ->
                        getExplicitSummariesForInvocation(
                            summaries = unresolvedSummaries,
                            sigHash = call.sigHash?.n,
                            hostContractId = call.contractId,
                            onExactSummaryMiss = { Unit },
                            nameLookup = { scene.getContractOrNull(it)?.name }
                        )
                    } ?: listOf()
                }
                val configSummaries = when {
                    Config.SummarizeExtLibraryCallsAsNonDetPreLinking.get() && callSumm.callType == TACCallType.DELEGATE -> {
                        /* We make a config based summary when [Config.SummarizeExtLibraryCallsAsNonDetPreLinking]
                        is enabled only for delegate calls */
                        listOf(AppliedSummary.Config.ExtLibraryNondet)
                    }

                    callSumm.callTarget.any { it is CallGraphBuilder.CalledContract.CreatedReference } && Config.DispatchOnCreated.get() && sigHash != null -> {
                        listOf(AppliedSummary.Config.DispatchOnCreate)
                    }

                    Config.AutoDispatcher.get() && callSumm.callType != TACCallType.DELEGATE && sigHash != null -> {
                        listOf(AppliedSummary.Config.AutoDispatcher)
                    }

                    Config.SummarizeUnresolvedAsNondet.get() -> {
                        listOf(AppliedSummary.Config.ExtContractNondet)
                    }

                    else -> emptyList<AppliedSummary.FromUserInput>()
                }
                val appliedSummary = (explicitSummary + configSummaries).resolveCandidates()

                if (appliedSummary != null) {
                    when (appliedSummary.specCallSumm) {
                        is SpecCallSummary.Dispatcher -> {
                            dispatchQueue.add(
                                QueuedSummary(
                                    currAddress = currAddress,
                                    summaryCmd = summ,
                                    selectedSummary = DispatchSummary.Dispatcher(appliedSummary, sigHash)
                                )
                            )
                        }

                        is SpecCallSummary.DispatchList -> {
                            dispatchQueue.add(
                                QueuedSummary(
                                    currAddress = currAddress,
                                    summaryCmd = summ,
                                    selectedSummary = DispatchSummary.Dispatcher(appliedSummary, sigHash)
                                )
                            )
                        }

                        else -> {
                            @Suppress("SENSELESS_COMPARISON")
                            check(currAddress != null) {
                                "Sanity check: curr address should be non-null"
                            }
                            concreteQueue.add(QueuedSummary(
                                currAddress = currAddress,
                                summaryCmd = summ,
                                selectedSummary = ConcreteSummary.ExplicitSummary(appliedSummary, sigHash)
                            ))
                        }
                    }
                } else if(callSumm.callTarget.all { it is CallGraphBuilder.CalledContract.FullyResolved } && sigHash != null && callSumm.callType != TACCallType.DELEGATE) {
                    /**
                     * In the case that no summary was applied (no config summary and no summary in spec),
                     * but all call targets (i.e. all callee addresses) are statically known, we inline
                     * the methods via a dispatcher summary (see [Summarizer.inlineSummaryFromConfig] for it.
                     *
                     * We only apply the dispatcher summary in the case that _all_ targets are fully resolved.
                     * As soon as one of the variables cannot be resolved, there will be one execution path along
                     * which we havoc and therefore it's not necessary to add the remaining branches.
                     */
                    concreteQueue.add(QueuedSummary(
                        summaryCmd = summ,
                        currAddress = currAddress,
                        selectedSummary = ConcreteSummary.ExplicitSummary(AppliedSummary.Config.LateInliningDispatcher, sigHash)
                    ))
                } else {
                    havocQueue.add(QueuedSummary(
                        summaryCmd = summ,
                        currAddress = currAddress,
                        selectedSummary = ConcreteSummary.Havoc
                    ))
                }
            }
        }
        val resolutionLogic = when(Config.SummaryResolutionMode.get()) {
            SummaryResolutionPolicy.SIMPLE -> SimpleSummarizationPolicy
            SummaryResolutionPolicy.TIERED -> TieredSummarizationPolicy
            SummaryResolutionPolicy.AGGRESSIVE -> AggressiveSummarizationPolicy
        }
        logger.debug {
            "Selecting work from queues..."
        }
        logger.debug {
            "havoc: $havocQueue"
        }
        logger.debug {
            "Dispatch: $dispatchQueue"
        }
        logger.debug {
            "concrete: $concreteQueue"
        }
        val work = resolutionLogic.chooseWork(
            explicit = concreteQueue,
            dispatch = dispatchQueue,
            havoc = havocQueue,
            externalLinker = externalLinkingState,
            internalLinker = internalLinkingState
        )
        logger.debug {
            "Selected work $work"
        }
        val handled = mutableSetOf<CmdPointer>()
        for(w in work) {
            handled.add(w.summaryCmd.ptr)
            when(w.selectedSummary) {
                // for historical reasons, havoc summaries are handled within handleConcreteSummary XXX(jtoman): change this
                is ConcreteSummary -> handleConcreteSummary(code, w.uncheckedAs(), patching)
                is DispatchSummary -> handleDispatchSummary(code, w.uncheckedAs(), patching)
            }
        }
        var modified = work.isNotEmpty()
        // find those summaries that might have been linked by this round of summarization
        // (and weren't already summarized) and do that
        for(n in needSummarization) {
            if(n.ptr in handled) {
                continue
            }
            val summ = n.cmd.summ as CallSummary
            val output = summ.callTarget.singleOrNull() as? CallGraphBuilder.CalledContract.SymbolicOutput ?: continue
            val outputLink = externalLinkingState.getLinkOutput(output.which, output.offset) ?: continue
            logger.debug {
                "Setting up linking for $needSummarization"
            }
            val linkingRequire = ExprUnfolder.unfoldPlusOneCmd("linking", with(TACExprFactTypeCheckedOnlyPrimitives) {
                Eq(outputLink.asSym(), summ.toVar.asSym())
            }) { s ->
                TACCmd.Simple.AssumeCmd(s.s)
            }
            patching.addBefore(n.ptr, linkingRequire)
            modified = true
        }
        return ExternalSummarizationResult(patching.toCode(code), modified)
    }

    /**
     * Result of a single round of external summarization, that is, the result of applying summaries within the loop
     * in [summarizeRemainingCallSummaries].
     */
    private data class ExternalSummarizationResult(
        val ctp: CoreTACProgram,
        val modified: Boolean,
    )

    /**
     * Apply one "step" of summarization to all summaries in [code] as identified by [needSummarization].
     */
    context(SummarizationContext)
    private fun externalSummarizationRound(
        code: CoreTACProgram,
        needSummarization: List<LTACCmdView<TACCmd.Simple.SummaryCmd>>,
        internalLinker: InternalLinkingState
    ): ExternalSummarizationResult {
        if (needSummarization.isEmpty()) {
            return ExternalSummarizationResult(
                code, false
            )
        }
        val methodCallStack = InlinedMethodCallStack(code.analysisCache.graph)
        val externalLinkState = Summarizer.LinkingState<BigInteger>()
        return InliningRoundContext(
            externalLinkState, internalLinker, methodCallStack
        ).run {
            externalSummarizationRound(code, needSummarization)
        }
    }


    /**
     * (Potentially mutable) state local to a single round of inlining (one step of [externalSummarizationRound],
     * AKA one iteration of the loop within [summarizeRemainingCallSummaries])
     */
    private data class InliningRoundContext(
        val externalLinkingState: Summarizer.LinkingState<BigInteger>,
        val internalLinkingState: InternalLinkingState,
        val methodCallStack: InlinedMethodCallStack
    )

    /**
     * Inline a summary [appliedSummary] to the callsite encapsulated by [q] in [code],
     * using [patching] for the mutation. Used for any summary that was applied due to an explicit user input,
     * i.e., a config or methods block.
     */
    context(SummarizationContext, InliningRoundContext)
    private fun inlineUserInputSummary(
        code: CoreTACProgram,
        q: QueuedSummary<*>,
        sigHash: BigInteger?,
        appliedSummary: AppliedSummary.FromUserInput,
        patching: SimplePatchingProgram,
    ) {
        val summ = q.summaryCmd
        val specCallSummary = appliedSummary.specCallSumm
        logger.info {
            "In ${code.name}, summarizing ${
                summ.cmd.metaSrcInfo?.getSourceCode() ?: "unknown source calling ${
                    sigHash?.let {
                        "0x" + sigHash.toString(
                            16
                        )
                    } ?: "unknown selector"
                }"
            } as $specCallSummary"
        }
        val currAddress = q.currAddress
        when (appliedSummary) {
            is AppliedSummary.MethodsBlock -> {
                Summarizer.inlineSummaryFromCVL(
                    scene,
                    currAddress,
                    code,
                    patching,
                    summ,
                    appliedSummary,
                    cvlCompiler,
                    externalLinkingState,
                    inliningDecisionManager = manager,
                    methodCallStack
                )
            }
            is AppliedSummary.Config -> {
                Summarizer.inlineSummaryFromConfig(
                    scene,
                    currAddress,
                    patching,
                    summ,
                    appliedSummary,
                    methodCallStack
                )
            }
        }
    }

    /**
     * Called to handle a dispatch summary, which may be implicit
     * [analysis.icfg.Summarization.DispatchSummary.OptimisticFallback] or explicitly in the methods block
     * [analysis.icfg.Summarization.DispatchSummary.Dispatcher]
     */
    context(InliningRoundContext, SummarizationContext)
    private fun handleDispatchSummary(
        code: CoreTACProgram,
        q: QueuedSummary<DispatchSummary>,
        patching: SimplePatchingProgram
    ) {
        when(q.selectedSummary) {
            is DispatchSummary.Dispatcher -> {
                inlineUserInputSummary(
                    q = q,
                    sigHash = q.selectedSummary.sigHash,
                    appliedSummary = q.selectedSummary.summ,
                    code = code,
                    patching = patching
                )
            }
            DispatchSummary.OptimisticFallback -> {
                Summarizer.inlineFallbackDispatcher(
                    patching,
                    scene,
                    q.summaryCmd
                ) { methodCallStack.iterateUpCallersMethodOnly(it) }

            }
        }
    }

    /**
     * Handle a concrete summary (again, concrete means we are replacing the summary with something concrete, not just
     * "pick one of many" a la the dispatchers).
     */
    context(InliningRoundContext, SummarizationContext)
    private fun handleConcreteSummary(
        code: CoreTACProgram,
        q: QueuedSummary<ConcreteSummary>,
        patching: SimplePatchingProgram
    ) {
        val summ = q.summaryCmd
        when(q.selectedSummary) {
            is ConcreteSummary.ExplicitSummary -> {
                val appliedSummary = q.selectedSummary.summ
                val sigHash = q.selectedSummary.sigHash
                inlineUserInputSummary(code, q, sigHash, appliedSummary, patching)
            }
            ConcreteSummary.Havoc -> {
                logger.info {
                    "In ${code.name}, havocing ${summ.cmd.metaSrcInfo?.getSourceCode() ?: "unknown source"} as ${
                        SpecCallSummary.HavocSummary.Auto(
                            summarizationMode = SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY
                        )
                    }"
                }

                // Library functions are called via delegatecalls, and they don't have their own storage to havoc or
                // not. So if e.g. we have an unresolved call within a library function and we want to HAVOC_ECF, the
                // contract that should be "protected" is the one that delegatecalled the lib function, not the library.
                // That's what this search up the callstack is for.
                val instanceId = methodCallStack.activationRecordsAt(summ.ptr).reversed().firstOrNull { record ->
                    record.ref is MethodRef &&
                        record.callType != TACCallType.DELEGATE
                }?.ref?.let { it as MethodRef }?.contractId ?: q.currAddress
                Summarizer.inlineAutoHavocAsProverDefault(patching, scene, instanceId, summ)
            }
        }
    }


    /**
     * Simulates a stack in [code] where [isStart] defines a push and [isExit] defines a pop. Returns the set of
     * exit commands which return the stack to its original position (only for the first such sequence of push ... pop.
     */
    abstract class ExitFinder(val code: CoreTACProgram) {
        abstract fun calleeStarted(cmd: TACCmd.Simple.AnnotationCmd): Int?
        abstract fun calleeExited(cmd: TACCmd.Simple.AnnotationCmd): Int?

        private fun getAllPoints(
            calleeExtractor: (TACCmd.Simple.AnnotationCmd) -> Int?
        ): Map<Int, Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>>> =
            code.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.let { lcmd ->
                    calleeExtractor(lcmd.cmd)?.let { callee ->
                        callee to lcmd
                    }
                }
            }.collect(Collectors.groupingBy({ it.first }, Collectors.mapping({ it.second }, Collectors.toSet())))

        private val allStartPoints = getAllPoints(::calleeStarted)
        private val allExitPoints = getAllPoints(::calleeExited)

        fun getExits(
            calleeId: Int,
            startPtr: CmdPointer
        ): Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>> {
            val graph = code.analysisCache.graph
            require(graph.elab(startPtr).maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.cmd?.let(::calleeStarted) == calleeId)

            val startPoints = allStartPoints[calleeId]?.size ?: 0
            if(startPoints == 1) {
                return allExitPoints[calleeId].orEmpty()
            }
            return graph.scope {
                object : VisitingWorklistIteration<CmdPointer, Nothing, Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>>>() {
                    val stackframe = ArrayHashMap<CmdPointer, Int>().apply { put(startPtr, -1) }
                    val exits = mutableSetOf<LTACCmdView<TACCmd.Simple.AnnotationCmd>>()
                    override fun process(it: CmdPointer): StepResult<CmdPointer, Nothing, Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>>> {
                        val cmd = it.elab()
                        val depth = stackframe[it]!!
                        return if (cmd.cmd is TACCmd.Simple.AnnotationCmd && calleeExited(cmd.cmd) == calleeId) {
                            if (depth == 0) {
                                exits.add(it.elab().narrow())
                                StepResult.Ok(setOf(), setOf())
                            } else {
                                val succ = it.succ()
                                succ.forEach { ptr -> stackframe[ptr] = depth - 1 }
                                this.cont(succ)
                            }
                        } else if (cmd.cmd is TACCmd.Simple.AnnotationCmd &&
                            calleeStarted(cmd.cmd) == calleeId
                        ) {
                            it.succ().forEach { ptr -> stackframe[ptr] = depth + 1 }
                            StepResult.Ok(it.succ(), setOf())
                        } else {
                            it.succ().forEach { ptr -> stackframe[ptr] = depth }
                            StepResult.Ok<CmdPointer, Nothing>(it.succ(), setOf())
                        }
                    }
                    override fun reduce(results: List<Nothing>): Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>> = exits
                }.submit(setOf(startPtr))
            }
        }
    }
}

private fun optimisticFallbackCallDescription(programName: String, containingFuncName: String?, containingFuncSource: SourceSegment?): String {
    return when {
        containingFuncName != null && containingFuncSource != null -> {
            "$programName (specifically, in $containingFuncName called at ${containingFuncSource.fileName}: ${containingFuncSource.lineNumber})"
        }

        containingFuncName != null -> "$programName (specifically, in $containingFuncName)"

        else -> programName
    }
}
