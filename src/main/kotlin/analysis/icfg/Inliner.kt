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

@file:UseSerializers(BigIntegerSerializer::class)
package analysis.icfg

import algorithms.dominates
import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import allocator.SuppressRemapWarning
import analysis.*
import analysis.pta.ABICodeFinder
import analysis.pta.HeapType
import analysis.pta.abi.*
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import bridge.EVMExternalMethodInfo
import com.certora.collect.*
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import diagnostics.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_BYTE_SIZE
import evm.EVM_WORD_SIZE
import instrumentation.calls.*
import kotlinx.serialization.UseSerializers
import java.io.Serializable
import log.*
import report.calltrace.printer.DebugAdapterPopAction
import report.calltrace.printer.DebugAdapterPushAction
import report.calltrace.printer.StackEntry
import scene.*
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACMeta.GHOST_RESTORE
import vc.data.TACMeta.GHOST_SAVE
import vc.data.TACSymbol.Companion.atSync
import vc.data.tacexprutil.getFreeVars
import verifier.ChainedMethodTransformers
import verifier.ContractUtils
import verifier.CoreToCoreTransformer
import verifier.MethodToCoreTACTransformer
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.INLINER)

private typealias InstrumentationPass = Pair<ReportTypes, (CallId, CoreTACProgram) -> CoreTACProgram>

/**
 * A representation of how to resolve a call at some particular point in the program. Those instructions
 * could be to
 * 1. truncate the call-site
 * 2. rewrite the call summary
 * 3. limit the call (replace it with an assert/assume false), or
 * 4. inline the single callee method.
 *
 * The inlined method may itself have its own callsites, each of which have their own set of instructions, etc. etc.
 *
 * Thus, the inlining process for a single method is defined by the [Resolution] objects for each call location
 * in that method.
 */
private sealed class Resolution

/**
 * The call should be resolved to inline [hostMethod]. The callsites within [hostMethod] should
 * be handled according to the instructions in [resolvedChildren]. The call to [hostMethod] should be
 * modeled with the [withABIConvention] if non-null.
 */
private class CanonicalizedTree(
    val hostMethod: MethodRef,
    val inliningId: TransientCallId,
    val resolvedChildren: Map<CmdPointer, Resolution>,
    val withABIConvention: ABICallConvention.ABIFullBinding?,
    val storageRewriteForCall: (ITACMethod) -> CoreTACProgram
) : Resolution()


/**
 * The [CallSummary] at the call-site should be updated with the following fields. This usually
 * reflects that a call within an inlined call now refers to call-indexed variables
 */
@SuppressRemapWarning
private data class SummaryRewrite(
    val newId: Int,
    val callTarget: Set<CallGraphBuilder.CalledContract>,
    val symbolicInputs: Map<BigInteger, CallGraphBuilder.CalledContract>,
    val sighash: Set<BigInteger?>,
) : Resolution()

/**
 * Replace the callsite with an assume/assert false, according to the loop unwinding mode
 */
private object Limit : Resolution()

/**
 * Truncate the call-site. Unlike the other cases, this instruction is not applied to an as-yet unresolved callsummary,
 * but rather to an already inlined call stack.
 */
private object Truncate : Resolution()

object Inliner {
    enum class IllegalInliningReason {
        DELEGATE_CALL_POST_STORAGE,
        WITHIN_WHOLE_CONTRACT
    }

    /** Algorithm to use for inlining */
    private sealed interface CallConventionStrategy {
        /** Simply paste the callee into the caller (i.e., leaving the serialization/deserialization code as-is) */
        data object ViaSerialization : CallConventionStrategy

        /**
         * Attempt to delete the serialization/deserialzation code in the caller/callee, instead
         * initializing callee memory using the caller's byte maps (when possible)
         */
        data object TryDirectPassing : CallConventionStrategy {
            fun createMemoryModel(
                abstractInstance: TransientCallId
            ) : AbstractMemorySpace = AbstractMemorySpace(abstractInstance)
        }
    }

    object CallStack {
        val STACK_PUSH = MetaKey<PushRecord>("call.trace.push")

        val STACK_POP = MetaKey<PopRecord>("call.trace.pop")

        @GenerateRemapper
        @KSerializable
        data class PushRecord(
            val callee: MethodRef,
            val summary: CallSummary?,
            val convention: CallConventionType,
            @GeneratedBy(Allocator.Id.CALL_ID, source = true)
            val calleeId: Int,
            val evmExternalMethodInfo: EVMExternalMethodInfo?,
            val isNoRevert: Boolean = true,
        ) : AmbiSerializable, TransformableVarEntityWithSupport<PushRecord>, RemapperEntity<PushRecord>, DebugAdapterPushAction {
            override val support: Set<TACSymbol.Var>
                get() = summary?.variables?: setOf()

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): PushRecord =
                this.copy(summary = summary?.transformSymbols(f))

            override val stackElement: StackEntry
                get() = StackEntry.SolidityFunction(evmExternalMethodInfo?.toExternalABINameWithContract() ?: "unknown method", evmExternalMethodInfo?.sourceSegment?.range)

        }

        @GenerateRemapper
        @KSerializable
        data class PopRecord(
            val callee: MethodRef,
            @GeneratedBy(Allocator.Id.CALL_ID)
            val calleeId: Int,
        ) : AmbiSerializable, RemapperEntity<PopRecord>, DebugAdapterPopAction

        fun stackPusher(p: PatchingTACProgram<in TACCmd.Simple>, r: PushRecord) : (LTACCmdGen<*>) -> Unit {
            return { l ->
                p.addBefore(l.ptr, listOf(TACCmd.Simple.AnnotationCmd(STACK_PUSH, r)))
            }
        }

        fun stackPopper(p: PatchingTACProgram<in TACCmd.Simple>, popRecord: PopRecord) : (LTACCmdGen<*>) -> Unit = { l ->
            p.insertAfter(l.ptr, listOf(TACCmd.Simple.AnnotationCmd(STACK_POP, popRecord)))
        }
    }

    fun inlineDirectCallsInScene(scene: IScene, resolutionFilter: (CallSummary, ContractId) -> Boolean) {
        logger.info {"Inlining direct calls"}
        val methods = scene.getContracts().flatMap {
            it.getDeclaredMethods()
        } + scene.getPrecompiledContracts().flatMap {
            it.getDeclaredMethods()
        }
        val cfg = with(scene) {
            with(InliningDecisionManager.Direct.elaborateWith(resolutionFilter)) {
                InterContractCallResolver.resolveCalls(methods)
            }
        }
        inlineCfg(methods, cfg, scene, ReportTypes.INLINE_DIRECT, InliningDecisionManager.Direct)
    }

    private fun inlineCfg(
        methods: List<ITACMethod>,
        cfg: Map<MethodRef, MethodNode>,
        scene: IScene,
        reportTypes: ReportTypes,
        direct: InliningDecisionManager.SimpleDecisionManager?
    ) {
        val postInlining = mutableMapOf<MethodRef, CoreTACProgram>()
        methods.forEach { toRewrite ->
            val res = cfg[toRewrite.toRef()] ?: return@forEach
            postInlining[toRewrite.toRef()] = inlineAndRewriteCode(
                toRewrite,
                res = res,
                scene = scene,
                manager = direct?.let {
                    WholeContractProcessing.ContextSensitivePolicy(direct, toRewrite)
                } ?: WholeContractProcessing.NoInlining
            )
        }
        methods.forEach {
            ContractUtils.transformMethodInPlace(it, ChainedMethodTransformers(listOf(MethodToCoreTACTransformer(reportTypes) {
                postInlining[it.toRef()]!!
            })))
        }
    }

    private fun rewriteReturns(postInlining: CoreTACProgram, returnSummary: Map<BigInteger, CallGraphBuilder.CalledContract>?): CoreTACProgram {
        val patching = postInlining.toPatchingProgram()
        val returnCmds = postInlining.analysisCache.graph.commands.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.ReturnCmd>() ?: it.maybeNarrow<TACCmd.Simple.ReturnSymCmd>()
        }
        if(returnSummary == null) {
            returnCmds.forEach {
                patching.replaceCommand(it.ptr, listOf(
                        it.cmd.withMeta(
                                it.cmd.meta - TACMeta.RETURN_LINKING
                        )
                ))
            }
            return patching.toCode(postInlining)
        }
        returnCmds.forEach {
            val returnLink = it.cmd.meta.find(TACMeta.RETURN_LINKING)!!
            patching.replaceCommand(it.ptr, listOf(
                    it.cmd.mapMeta { mm ->
                        mm + (TACMeta.RETURN_LINKING to returnLink.copy(byOffset = returnSummary))
                    }
            ))
        }
        return patching.toCode(postInlining)
    }

    private fun recursiveBoundCommands() : List<TACCmd.Simple> {
        return if(Config.OptimisticContractRecursion.get() && Config.ContractRecursionLimit.get() > 0) {
            listOf(
                TACCmd.Simple.LabelCmd(
                    "Contract recursion limit reached (pruning)"
                ),
                TACCmd.Simple.AssumeCmd(cond = TACSymbol.False, "recursion pruning")
            )
        } else {
            listOf(
                TACCmd.Simple.AssertCmd(o = TACSymbol.False, "Contract recursion limit reached (failing). " +
                    "Consider enabling ${Config.OptimisticContractRecursion.userFacingName()} and setting ${Config.ContractRecursionLimit.userFacingName()} to a value >= 1.")
            )
        }
    }

    /**
     * This "on the fly" replaces [vc.data.TACCmd.Simple.CallCore] commands that are in the whole program
     * method with equivalent [CallSummary] annotations.
     *
     * Q: Why do we need to do this?
     * A: We basically run 0 of the instrumentations/analyses/optimizations on the whole contract method, because it's so rarely
     * used, and doing so is crazy expensive. But the presence of a unreplaced call-core makes the simple simple pass
     * very unhappy, so lifting to call summaries means that 1) simple simple doesn't fail (yay!) 2) all such call cores
     * that have been replaced with call summaries will be soundly summarized with havocs (yay!)
     */
    private fun instrumentWholeContractCallCores(c: CoreTACProgram) : CoreTACProgram {
        return c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.CallCore>()
        }.map {
            it.ptr to CallSummary(
                callTarget = setOf(CallGraphBuilder.CalledContract.Unresolved),
                callConvention = CallConvention(
                    input = CallInput(
                        offset = it.cmd.inOffset.asSym(),
                        size = it.cmd.inSize.asSym(),
                        baseVar = it.cmd.inBase.asSym(),
                        inputSizeLowerBound = null,
                        simplifiedOffset = null,
                        encodedArguments = null,
                        rangeToDecomposedArg = mapOf()
                    ),
                    rawOut = CallOutput(
                        size = it.cmd.outSize,
                        offset = it.cmd.outOffset,
                        base = it.cmd.outBase
                    )
                ),
                toVar = it.cmd.to,
                outBase = it.cmd.outBase,
                inBase = it.cmd.inBase,
                origCallcore = it.cmd,
                inOffset = it.cmd.inOffset,
                inSize = it.cmd.inSize,
                gasVar = it.cmd.gas,
                valueVar = it.cmd.value,
                callType = it.cmd.callType,
                outOffset = it.cmd.outOffset,
                outSize = it.cmd.outSize,
                sigResolution = setOf(),
                cannotBeInlined = IllegalInliningReason.WITHIN_WHOLE_CONTRACT,
                symbolicSigResolution = null
            )
        }.sequential().let { s ->
            c.patching { patcher ->
                s.forEach { (where, summary) ->
                    patcher.replaceCommand(where, listOf(TACCmd.Simple.SummaryCmd(summary, meta = MetaMap())))
                }
            }
        }
    }

    fun IScene.find(m: MethodRef) : ITACMethod? {
        return this.getContract(m.contractId).let {
            if(m.attr is MethodAttribute.Unique) {
                it.getMethodByUniqueAttribute(m.attr)
            } else {
                it.getMethodBySigHash(m.sigHash?.n ?: error("No sighash for $m"))
            }
        }
    }

    /**
     * Sealed class which encapsulates the necessary state for deciding whether a whole contract can be inlined.
     */
    private sealed interface WholeContractProcessing {
        fun canInlineWholeContract(
            resolution: Resolution,
            summ: CallSummary
        ) : Boolean

        fun extend(other: ITACMethod) : WholeContractProcessing

        /**
         * Decide whether within [currMethod] we support inlining a whole contract, depending on configuration flags,
         * whether we have a concrete callee, and we are within a fallback or another whole contract.
         */
        class ContextSensitivePolicy(
            val manager: InliningDecisionManager.SimpleDecisionManager,
            val currMethod: ITACMethod
        ) : WholeContractProcessing {
            override fun canInlineWholeContract(resolution: Resolution, summ: CallSummary): Boolean {
                val callee = summ.callTarget.singleOrNull() ?: return false
                return resolution is SummaryRewrite &&
                    callee is CallGraphBuilder.CalledContract.FullyResolved &&
                    manager.eligibleForInlining(summ, callee.contractId) &&
                    (currMethod.attribute == MethodAttribute.Unique.Fallback || currMethod.attribute == MethodAttribute.Unique.Whole) &&
                    Config.EnableWholeContractProxyInlining.get()
            }

            override fun extend(other: ITACMethod): WholeContractProcessing {
                return ContextSensitivePolicy(
                    manager, other
                )
            }

        }

        data object NoInlining : WholeContractProcessing {
            override fun canInlineWholeContract(resolution: Resolution, summ: CallSummary): Boolean {
                return false
            }

            override fun extend(other: ITACMethod): WholeContractProcessing {
                return this
            }

        }
    }

    private fun recursiveInlining(
        icore: CoreTACProgram,
        resolutions: Map<CmdPointer, Resolution>,
        transientId: TransientCallId?,
        scene: IScene,
        wholeContractProcessing: WholeContractProcessing,
        thisForDelegate: ContractId?
    ) : CoreTACProgram {
        val callCoreSummaries = icore.parallelLtacStream().mapNotNull { (ptr, simple) ->
            ptr `to?` (simple as? TACCmd.Simple.SummaryCmd)?.takeIf {
                it.summ is CallSummary
            }
        }.collect(Collectors.toList()).toMap()
        if(callCoreSummaries.isEmpty()) {
            return icore
        }
        val patching = icore.toPatchingProgram()
        check(icore.entryBlockId.calleeIdx == 0)
        val g = icore.analysisCache.graph
        inlineLoop@for((where, summ) in callCoreSummaries) {
            check(summ.summ is CallSummary)
            val resolution = resolutions[where]
            logger.info { "Working on ${icore.name} a call to `${summ.metaSrcInfo?.getSourceCode()}`"}

            if (resolution != null && wholeContractProcessing.canInlineWholeContract(
                    resolution, summ.summ
                ) && thisForDelegate != null) {

                /**
                 * If the call target resolved to multiple callees, the resolution will be delayed
                 * to the [Summarizer], see [analysis.icfg.Summarization.AppliedSummary.Config.LateInliningDispatcher]
                 * for this case.
                 */
                val callee = summ.summ.callTarget.singleOrNull() ?: error("Expected exactly one element in callTarget set, got ${summ.summ.callTarget}")
                check(callee is CallGraphBuilder.CalledContract.FullyResolved)
                val calleeWithInlining = scene.getMethod(callee.contractId, MethodAttribute.Unique.Whole)
                logger.info {
                    "Inlining in ${icore.name} a ${
                        if (summ.summ.callType == TACCallType.DELEGATE) {
                            "delegatecall"
                        } else {
                            "call"
                        }
                    } to ${calleeWithInlining.soliditySignature?.let { "${calleeWithInlining.getContainingContract().name}-$it" } ?: calleeWithInlining.code.name}"
                }
                inlineMethod(
                    callerIcore = icore,
                    calleeWithInlining = calleeWithInlining,
                    summ = summ.summ,
                    callee,
                    calleeContractAddress = scene.getContract(callee.contractId).addressSym as TACSymbol,
                    patching = patching,
                    calleeABIConvention = null,
                    where = where,
                    extraInstrumentation = ReportTypes.CONVERT_CALL_CORES to { _: CallId, p: CoreTACProgram ->
                        this.instrumentWholeContractCallCores(p)
                    },
                    storageRewriter = if(summ.summ.callType == TACCallType.DELEGATE) {
                        StorageRewriteStrategy.BasicDelegateStrategy.getStorageRewriter(thisAtCall = thisForDelegate, TACCallType.DELEGATE)
                    } else {
                        { m: ITACMethod -> m.code as CoreTACProgram }
                    }
                )
            } else if(resolution != null) {
                when(resolution) {
                    is CanonicalizedTree -> {
                        if(PrecompiledContractCode.getPrecompiledImplemented(resolution.hostMethod.contractId) == null &&
                            ContractUniverse.ETHEREUM.isPrecompiled(resolution.hostMethod.contractId)) {
                            // skipping precompiled, unless implemented
                            logger.info { "Skipping precompiled contract ${resolution.hostMethod}"}
                            continue
                        }
                        /**
                         * If the call target resolved to multiple callees, the resolution will be delayed
                         * to the [Summarizer], see [analysis.icfg.Summarization.AppliedSummary.Config.LateInliningDispatcher]
                         * for this case.
                         */
                        val callee = summ.summ.callTarget.singleOrNull() ?: error("Expected exactly one element in callTarget set, got ${summ.summ.callTarget}")
                        val calleeWithInlining = recursiveInlining(resolution, scene, wholeContractProcessing).let { code ->
                            // create a new copy of this "method" that also just happens to have every call already inlined
                            scene.find(resolution.hostMethod)!!.fork().also { m ->
                                m.code = code
                            }
                        }
                        logger.info {
                            "Inlining in ${icore.name} a ${
                                if (summ.summ.callType == TACCallType.DELEGATE) {
                                    "delegatecall"
                                } else {
                                    "call"
                                }
                            } to ${calleeWithInlining.soliditySignature?.let { "${calleeWithInlining.getContainingContract().name}-${it}" } ?: calleeWithInlining.code.name}"
                        }
                        inlineMethod(
                            callerIcore = icore,
                            calleeWithInlining = calleeWithInlining,
                            summ = summ.summ,
                            callee = callee,
                            calleeContractAddress = scene.getContract(resolution.hostMethod.contractId).addressSym as TACSymbol,
                            patching = patching,
                            calleeABIConvention = resolution.withABIConvention,
                            where = where,
                            storageRewriter = resolution.storageRewriteForCall
                        )
                    }
                    Limit -> {
                        logger.info { "Recursive limit" }
                        patching.replaceCommand(
                            p = where,
                            new = recursiveBoundCommands()
                        )
                    }
                    is SummaryRewrite -> {
                        logger.info {"For ${icore.name}, call ${g.elab(where).cmd.metaSrcInfo?.getSourceCode() ?: "unknown source"}, no decision made" }
                        logger.info {"LINK PROBLEM: Details of resolution: $resolution" }
                        logger.info {"LINK PROBLEM: Details of summary: $summ" }
                        val summUpdated = summ.summ.copy(
                            summaryId = resolution.newId,
                            callTarget = resolution.callTarget,
                            callConvention = summ.summ.callConvention.copy(
                                input = summ.summ.callConvention.input.copy(
                                    rangeToDecomposedArg = summ.summ.callConvention.input.rangeToDecomposedArg.mapValues { (k, v) ->
                                        if(k.to == (k.from + 31.toBigInteger())) {
                                            v.withContractReference(resolution.symbolicInputs[k.from])
                                        } else {
                                            v.withContractReference(null)
                                        }
                                    }
                                )
                            ),
                            sigResolution = resolution.sighash
                        )
                        patching.replaceCommand(where, listOf(TACCmd.Simple.SummaryCmd(
                            summ = summUpdated,
                            meta = g.elab(where).cmd.meta
                        )))
                        patching.addVarDecls(summUpdated.callTarget.mapNotNull { (it as? CallGraphBuilder.CalledContract.SymbolicInput)?.inputArg}.toSet())
                    }
                    is Truncate -> throw IllegalStateException("Cannot truncate at call core")
                }
            }
        }
        val exitFinder = ExitFinder(icore)
        for((where, c) in resolutions) {
            if(c is Truncate) {
                check(g.elab(where).maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.cmd?.annot?.k == CallStack.STACK_PUSH) {
                    "Not actually a stack push"
                }
                deinlineCall(
                    icore,
                    exitFinder,
                    patching = patching,
                    push = g.elab(where).narrow()
                ) {
                    recursiveBoundCommands()
                }
            }
        }
        return patching.toCode(icore).letIf(transientId != null) {
            it.materializeTransientMemory(transientId!!)
        }
    }

    private fun CoreTACProgram.materializeTransientMemory(t: TransientCallId) : CoreTACProgram {
        return this.parallelLtacStream().mapNotNull {
            it.ptr `to?` run {
                it.maybeExpr<TACExpr.Apply>()?.let {
                    val f = it.exp.f
                    val bifSym = (f as? TACExpr.TACFunctionSym.BuiltIn)?.bif ?: return@let null
                    if(bifSym !is TACBuiltInFunction.TransientMemoryBif) {
                        return@let null
                    }
                    if(bifSym.transientId != t) {
                        return@let null
                    }
                    val base = ConcretePartition(
                        callIdx = NBId.ROOT_CALL_ID,
                        partitionId = bifSym.partitionId
                    ).toBaseMap()
                    when(bifSym) {
                        is TACBuiltInFunction.ReadTransientPartition -> {
                            TACCmd.Simple.AssigningCmd.ByteLoad(
                                lhs = it.lhs,
                                base = base,
                                loc = (it.exp.ops.single() as TACExpr.Sym).s
                            )
                        }
                        is TACBuiltInFunction.PartitionInitialize -> {
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = it.lhs,
                                rhs = base
                            )
                        }
                    } to base
                }
            }
        }.patchForEach(this, check = true) { (where, payload) ->
            this.replaceCommand(where, listOf(payload.first))
            this.addVarDecl(payload.second)
        }
    }

    private fun recursiveInlining(
        tree: CanonicalizedTree,
        scene: IScene,
        wholeContractProcessing: WholeContractProcessing,
    ) : CoreTACProgram {
        val resolution = tree.resolvedChildren

        val currMethod = scene.find(tree.hostMethod) ?: throw IllegalStateException("Asked to retrieve (apparently non-existent) method ${tree.hostMethod}")
        val icore = currMethod.code as CoreTACProgram
        return recursiveInlining(
            icore = icore,
            resolutions = resolution,
            transientId = tree.inliningId,
            scene = scene,
            thisForDelegate = currMethod.getContainingContract().instanceId,
            wholeContractProcessing = wholeContractProcessing.extend(currMethod)
        )
    }

    fun inlineDirectCallsInMethod(scene: IScene, method: ITACMethod): CoreTACProgram {
        val cfg = with(scene) {
            with(InliningDecisionManager.Direct) {
                InterContractCallResolver.resolveCalls(listOf(method))
            }
        }
        return cfg[method.toRef()]?.let {
            inlineAndRewriteCode(
                method,
                it,
                scene,
                WholeContractProcessing.ContextSensitivePolicy(InliningDecisionManager.Direct, method)
            )
        } ?: method.code as CoreTACProgram
    }

    private val inliningSeq = AtomicInteger(0)

    private fun freshTransientId() = TransientCallId(inliningSeq.incrementAndGet())

    // If abv is a scalarized primitive, return the symbol
    private fun scalarizedInCaller(encoding: CalldataEncoding, abv: ABIValue): TACSymbol.Var? {
        if (abv !is ABIValue.Symbol || abv.sym !is ObjectPathAnalysis.ObjectRoot.CalldataRoot || abv.type != HeapType.Int) {
            return null
        }

        return if (abv.sym.bp is DecoderAnalysis.BufferAccessPath.Offset && abv.sym.bp.base is DecoderAnalysis.BufferAccessPath.Root) {
            val start = abv.sym.bp.offset + 4.toBigInteger()
            val scalarRange = CalldataByteRange(start, start + EVM_WORD_SIZE - BigInteger.ONE)
            return encoding.byteOffsetToScalar[scalarRange] ?: run {
                logger.debug { "For $abv: range $scalarRange not scalarized in caller"}
                null
            }
        } else {
            null
        }
    }

    // Build a mapping from offsets to scalarized primitives
    private fun callingConventionOfScalarizedInts(
        encoding: ICalldataEncoding,
        callNodes: List<InterContractCallResolver.CallGraphNode>
    ): Map<Int, Map<BigInteger, ABICallConvention.PointerTraversal>>? {
        if (encoding !is CalldataEncoding) {
            return null
        }

        return callNodes.associateNotNull {
            it.tryAs<InterContractCallResolver.CallGraphNode.ResolvedCall>()?.origNode?.let { orig ->
                orig.abiArgs?.encodedArgs?.mapValuesNotNull { (_, abv) ->
                    scalarizedInCaller(encoding, abv)?.let {
                        ABICallConvention.PointerTraversal(
                            ABICallConvention.ObjectTraversal.VarRoot(it, IndexedAbstractPointer.nullaryPointer()),
                            IndexedAbstractPointer.nullaryPointer()
                        )
                    }
                }?.takeIf {
                    it.isNotEmpty()
                }?.let {
                    orig.id to it
                }
            }
        }
    }

    private fun inlineAndRewriteCode(
        it: ITACMethod,
        res: MethodNode,
        scene: IScene,
        manager: WholeContractProcessing
    ): CoreTACProgram {
        val callerConvention = callingConventionOfScalarizedInts(it.calldataEncoding, res.callNodes)

        val constructor = CallConventionStrategy.TryDirectPassing

        val callId = freshTransientId()
        val resolved = with(scene) {
            toCanonical(
                callees = res.callNodes,
                callerMethodContext = HostMethodContext(
                    memorySpace = constructor.createMemoryModel(callId),
                    callerABIConvention = callerConvention
                ),
                callConventionResolution = constructor
            )
        }
        return rewriteReturns(
            recursiveInlining(
                thisForDelegate = it.getContainingContract().instanceId,
                scene = scene,
                resolutions = resolved,
                icore = it.code as CoreTACProgram,
                wholeContractProcessing = manager,
                transientId = callId
            ),
            res.returnSummary
        )
    }

    private class ExitFinder(code: CoreTACProgram) : Summarization.ExitFinder(code) {
        override fun calleeStarted(cmd: TACCmd.Simple.AnnotationCmd) =
            (cmd.annot.v as? CallStack.PushRecord)?.calleeId
        override fun calleeExited(cmd: TACCmd.Simple.AnnotationCmd) =
            (cmd.annot.v as? CallStack.PopRecord)?.calleeId
    }

    private fun deinlineCall(
        code: CoreTACProgram,
        exitFinder: ExitFinder,
        patching: PatchingTACProgram<TACCmd.Simple>,
        push: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
        replaceWith: (CallSummary) -> List<TACCmd.Simple>
    ) {
        val annotation = push.cmd.annot.v as CallStack.PushRecord
        val pops = exitFinder.getExits(annotation.calleeId, push.ptr)
        fun getNextJumpDest(lc: LTACCmd) = code.analysisCache.graph.scope {
            object : VisitingWorklistIteration<CmdPointer, NBId, Set<NBId>>() {
                override fun process(it: CmdPointer): StepResult<CmdPointer, NBId, Set<NBId>> =
                    when (val c = it.elab().cmd) {
                        is TACCmd.Simple.JumpCmd -> StepResult.Ok(setOf(), setOf(c.dst))
                        is TACCmd.Simple.JumpiCmd -> StepResult.Ok(setOf(), setOf(c.dst, c.elseDst))
                        else -> StepResult.Ok(it.succ(), setOf())
                    }

                override fun reduce(results: List<NBId>): Set<NBId> = results.toSet()
            }.submit(setOf(lc.ptr))
        }
        val jumpDests = pops.flatMap { pop -> getNextJumpDest(pop.wrapped) }.toSet()
        check(jumpDests.size == 1) { "Multiple exits for function ${annotation.callee}, push id " +
                "${annotation.calleeId} do not all have the same jumpDest but multiple: $jumpDests" }
        val jumpDest = CmdPointer(jumpDests.single(), 0)
        val pushPred = code.analysisCache.graph.pred(push.ptr)
        check(pushPred.size == 1) { "expected exactly one predecessor of push annotation $push but got ${pushPred.size}"}
        val remove = patching.splitBlockAfter(pushPred.single())
        val exitSite = patching.splitBlockBefore(jumpDest)
        patching.consolidateEdges(exitSite, listOf(remove))
        patching.addBefore(
            code.analysisCache.graph.succ(jumpDest).single(),
            replaceWith(annotation.summary!!)
        )
    }

    /**
     * Extend this [InliningDecisionManager] to also use the result of the filter [resolutionFilter] in deciding
     * what should/can be inlined. The returned [InliningDecisionManager] will delegate to this instance
     * for [InliningDecisionManager.update] and [InliningDecisionManager.getStorageRewriteStrategy]. For
     * [InliningDecisionManager.shouldInline] the result of `this` is anded with the result of [resolutionFilter].
     * The [analysis.icfg.InliningDecisionManager.StatelessDecisionManager] returned by [InliningDecisionManager.getStatelessManager]
     * is likewise extended to include the output of [resolutionFilter].
     */
    private fun InliningDecisionManager.elaborateWith(resolutionFilter: (CallSummary, ContractId) -> Boolean) : InliningDecisionManager {
        return object : InliningDecisionManager {
            override fun getStatelessManager(): InliningDecisionManager.StatelessDecisionManager {
                val wrapped = this@elaborateWith.getStatelessManager()
                return object : InliningDecisionManager.StatelessDecisionManager {
                    override fun eligibleForInlining(summ: CallSummary, callee: ContractId): Boolean {
                        return wrapped.eligibleForInlining(summ, callee) && resolutionFilter(summ, callee)
                    }
                }
            }

            override fun getStorageRewriteStrategy(): StorageRewriteStrategy {
                return this@elaborateWith.getStorageRewriteStrategy()
            }

            override fun shouldInline(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod): Boolean {
                return this@elaborateWith.shouldInline(thisAtCall, summ, callee) && resolutionFilter(
                    summ, callee.getContainingContract().instanceId
                )
            }

            override fun update(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod) {
                this@elaborateWith.update(thisAtCall, summ, callee)
            }

        }
    }

    fun inlineRemainingDirectCalls(
        scene: IScene,
        core: CoreTACProgram,
        manager: InliningDecisionManager.PostStorageAnalysis,
        resolutionFilter: (CallSummary, ContractId) -> Boolean
    ): CoreTACProgram {
        val resolution = with(scene) {
            with(manager.elaborateWith(resolutionFilter)) {
                InterContractCallResolver.resolveCalls(core)
            }
        }.let {
            with(scene) {
                toCanonical(
                    callees = it,
                    callerMethodContext = null,
                    callConventionResolution = CallConventionStrategy.ViaSerialization,
                )
            }
        }
        return recursiveInlining(
            core,
            resolution,
            transientId = null,
            scene = scene,
            thisForDelegate = null,
            wholeContractProcessing = WholeContractProcessing.NoInlining
        )
    }

    /**
     * Key for annotation command marking an inserted method call
     */
    private val INSERTED_METHOD_CALL = MetaKey<String>("inserted.methodcall")

    /**
     * Add a call to [calleeWithInlining] @ [where], replacing the command at [where].
     * This is meant to use in TAC instrumentation, and it assumes that all function calls in [calleeWithInlining] are
     * already inlined.
     * When inserting the function, we do not analyze the parameters, so it is left to the solver to figure out what
     * will be a correct non-reverting path in which a call data buffer is in the correct format. This is a limitation
     * that can limit us to havoced inputs, impact solving time, and affect configurations related to reverts.
     * *CAUTION* Currently not supporting functions that change state, change input memory, or have recursion.
     * The return value of a function, when relevant, will appear in the return data buffer, but again we won't know
     * it's type, so use it with care.
     * Notice that the input variables can be created using the callId and the "var" functions [inputBuffVar], and
     * [inputLenVar]. Output should be accessed using [TACKeyword.RETURNDATA] and [TACKeyword.RETURN_SIZE].
     * @return a [CallId] for the generated call. Use this Id when need to access buffers.
     */
    fun insertMethodCall(
        callerIcore: CoreTACProgram,
        calleeWithInlining: ITACMethod,
        calleeContractAddress: TACSymbol,
        patching: SimplePatchingProgram,
        where: CmdPointer
    ): CallId {
        val calleeId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val logStringCreator = {
            "Inserting a call to ${calleeWithInlining.getContainingContract().name}.${calleeWithInlining.name} " +
                "in ${callerIcore.name}"
        }
        Logger.regression(logStringCreator)
        logger.debug(logStringCreator)

        // Needed for call convention to work correctly
        val outputMem = TACSymbol.Var("callInsertInstrumentationOut", Tag.ByteMap, callIndex = calleeId).toUnique("!")
        val convention = let {
            val inputSize = inputLenVar(calleeId).asSym()
            val inputMem = inputBuffVar(calleeId).asSym()
            val inputOffset = inputOffsetVar(calleeId).asSym()
            val input = CallInput(inputMem, inputOffset, inputSize)
            // We use offset = 0, and size 0. returnData size will be computed dynamically.
            val output = CallOutput(outputMem, TACSymbol.Zero, TACSymbol.Zero)
            // Do these require havocing? Currently, not specifically havoced in case the user will want to set them.
            patching.addVarDecls(setOf(inputSize.s, inputMem.s, inputOffset.s, outputMem))
            CallConventionImpl(CallConvention(input, output), null)
        }
        val varResolver = VarResolver(
            callerIcore.symbolTable,
            where.block.calleeIdx
        )
        val toBlit = prepareMethodForInlining(
            calleeId,
            calleeContractAddress,
            call = calleeWithInlining,
            // Mostly matters whether it is a delegate call or not. At the moment we won't support delegate calls as
            // to me there are open concerns on how that might affect other parameters and side effects.
            callType = TACCallType.REGULAR_CALL,
            callConvention = convention,
            callvalue = BigInteger.ZERO.asTACExpr,
            blocknum = varResolver.blocknum.asSym(),
            timestamp = varResolver.timestamp.asSym(),
            callerIndex = where.block.calleeIdx,
            summary = null,
            symbolTable = callerIcore.symbolTable,
            extraInstrumentation = null,
            basefee = varResolver.basefee.asSym(),
            blobbasefee = varResolver.blobbasefee.asSym(),
            difficulty = varResolver.difficulty.asSym(),
            gasLimit = varResolver.gasLimit.asSym(),
            coinbase = varResolver.coinbase.asSym(),
            origin = varResolver.origin.asSym(),
            storageRewriter = { m: ITACMethod -> m.code as CoreTACProgram }
        )
        insertPreparedMethod(toBlit, patching, where, convention, callerIcore, preCommands = listOf(
            TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(
                INSERTED_METHOD_CALL,
                "Inserted method call of ${calleeWithInlining.name}")),
            TACCmd.Simple.AssumeExpCmd(TACExpr.BinRel.Eq(BigInteger.ZERO.asTACExpr, inputOffsetVar(calleeId).asSym()))
        ))
        return calleeId
    }

    /**
     * Creates and returns a [TACSymbol.Var] to be used when inserting a method call. Length of the input buffer.
     */
    fun inputLenVar(callId: CallId) = TACSymbol.Var("instrumentedCallInputLen", Tag.Bit256, callIndex = callId)

    /**
     * Creates and returns a [TACSymbol.Var] to be used when inserting a method call. Buffer used to hold the input
     * parameters.
     */
    fun inputBuffVar(callId: CallId) = TACSymbol.Var("instrumentedCallInputBuf", Tag.ByteMap, callIndex = callId)

    /**
     * Creates and returns a [TACSymbol.Var] to be used when inserting a method call.
     */
    private fun inputOffsetVar(callId: CallId) = TACSymbol.Var("instrumentedCallInputOffset", Tag.Bit256, callIndex = callId)

    /**
     * Inline [calleeWithInlining] into [callerIcore] at [where] using [patching].
     * [calleeWithInlining] will be prepared such that it will be safe to inline an instance, and
     * [extraInstrumentation] will be run as part of it's preparation.
     */
    private fun inlineMethod(
        callerIcore: CoreTACProgram,
        calleeWithInlining: ITACMethod,
        summ: CallSummary,
        callee: CallGraphBuilder.CalledContract,
        calleeContractAddress: TACSymbol,
        patching: SimplePatchingProgram,
        calleeABIConvention: ABICallConvention.ABIFullBinding?,
        where: CmdPointer,
        extraInstrumentation: InstrumentationPass? = null,
        storageRewriter: (ITACMethod) -> CoreTACProgram
    ) {
        if(summ.cannotBeInlined != null &&
            // Delegate call inlining post storage splitting is generally disallowed due to assumptions made during
            // storage splitting. Those assumptions consider all methods from the **same** contract, so inlining a
            // method from the same contract is safe, as splitting storage to storage variables is done to all methods
            // from the same contract together. Thus, we allow inlining on delegate calls on the same contract
            !(summ.cannotBeInlined == IllegalInliningReason.DELEGATE_CALL_POST_STORAGE && callee is CallGraphBuilder.CalledContract.FullyResolved &&
                callee.contractId == calleeWithInlining.getContainingContract().instanceId)
        ) {
            throw UnsupportedOperationException("Attempting to inline a call for $summ which has been explicitly marked as illegal to inline due to ${summ.cannotBeInlined}")
        }

        val convention = CallConventionImpl(summ.callConvention, calleeABIConvention)

        val msg = "Inlining a call to ${calleeWithInlining.getContainingContract().name}.${calleeWithInlining.name}" +
                  " in ${callerIcore.name} via " +
                  if (convention is DirectPassing) { "direct passing" } else { "serialization" }
        Logger.regression { msg }
        logger.debug { "[$where] $msg" }

        val varResolver = VarResolver(
                callerIcore.symbolTable,
                where.block.calleeIdx
        )
        val calleeId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val toBlit = prepareMethodForInlining(
            calleeId = calleeId,
            resolvedAddress = calleeContractAddress,
            call = calleeWithInlining,
            callType = summ.callType,
            callConvention = convention,
            callvalue = summ.valueVar.asSym(),
            blocknum = varResolver.blocknum.asSym(),
            timestamp = varResolver.timestamp.asSym(),
            callerIndex = where.block.calleeIdx,
            summary = summ,
            symbolTable = callerIcore.symbolTable,
            extraInstrumentation = extraInstrumentation,
            basefee = varResolver.basefee.asSym(),
            blobbasefee = varResolver.blobbasefee.asSym(),
            difficulty = varResolver.difficulty.asSym(),
            gasLimit = varResolver.gasLimit.asSym(),
            coinbase = varResolver.coinbase.asSym(),
            origin = varResolver.origin.asSym(),
            storageRewriter = storageRewriter
        )
        insertPreparedMethod(toBlit, patching, where, convention, callerIcore)
    }

    /**
     * Takes a method after [prepareMethodForInlining] in [toBlit], and adds a call to it in [where] using [patching].
     */
    private fun insertPreparedMethod(
        toBlit: ITACMethod,
        patching: SimplePatchingProgram,
        where: CmdPointer,
        convention: CallConventionImpl,
        callerIcore: CoreTACProgram,
        preCommands: List<TACCmd.Simple> = listOf(),
    ) {
        val toBlitICore = toBlit.code as CoreTACProgram
        patching.replaceCommand(where, preCommands, toBlitICore)
        patching.addVarDecls(toBlitICore.symbolTable.tags.keys)
        convention.instrumentCaller(
            callerIcore, patching, where
        )
    }

    /**
     * When resolving a specific call to a method n within another method m, this class provides information
     * about the caller m. [callerMemorySpace] is, as the name implies, the memory space of m. [abiResolution]
     * provides information about m's calldata arguments that are being passed through to `n`.
     */
    private data class CallerContext(
        val abiResolution: Map<BigInteger, ABICallConvention.PointerTraversal>?,
        val callerMemorySpace: AbstractMemorySpace
    )

    /**
     * When resolving callees within a method m, this object "bundles up" information about m's memory space
     * (via [memorySpace]), and information about m's calldata arguments that are being passed through to some
     * callee. That is, `callerABIConvention.get(i).get(o) == p` indicates that at call-site `i` within m,
     * the argument at offset `o` should be modeled with the [analysis.icfg.ABICallConvention.PointerTraversal].
     * Recall from the documentation of [analysis.icfg.ABICallConvention.PointerTraversal] that this object itself
     * packages some traversal of an object starting at some root variable (NOT in m's memory space, but one of its
     * callers), and the abstract memory layout that results from that traversal (again, NOT in m's memory space).
     *
     * This information is them embedded into the [CallerContext.abiResolution] for the call-site with id `i`.
     */
    private data class HostMethodContext(
        val memorySpace: AbstractMemorySpace,
        val callerABIConvention: Map<Int, Map<BigInteger, ABICallConvention.PointerTraversal>>?
    )

    /**
     * For each call graph node produced by the [InterContractCallResolver] in [callees], extract the resolution
     * (aka, inlining instructions) for each call-site represented by that node. Each call graph node could itself
     * reach other nodes, which are represented with the [CanonicalizedTree] resolution.
     *
     * It is assumed (but not checked) that all call graph nodes (and the call-sites they represent) exist within the same
     * method. Further, if [callerMethodContext] is not null, and `callerMethodContext.get(id).get(k) = pointerTraversal`, then the call-site with
     * `id` within the host method (which is assumed to exist somewhere in [callees]) uses the host method's (i.e., caller's)
     *  calldata argument for the kth argument, as described by the associated pointerTraversal instance.
     *
     *  In other words, if we have:
     *
     *  ```
     *  function foo(uint[] calldata baz) {
     *     bar(baz);
     *  }
     *  ```
     *
     *  Then the call node associated with the call to `bar` will record that the first argument comes directly from the
     *  caller's (i.e., foo's) calldata. The associated [analysis.icfg.ABICallConvention.PointerTraversal] object
     *  describes how to express that access in terms of the synthetic ABI arguments for `foo`, and the partition information
     *  for that traversal.
     */
    context(IScene)
    private fun toCanonical(
        callees: List<InterContractCallResolver.CallGraphNode>,
        callerMethodContext: HostMethodContext?,
        callConventionResolution: CallConventionStrategy,
    ): Map<CmdPointer, Resolution> {
        return callees.associate {
            when (it) {
                is InterContractCallResolver.CallGraphNode.ResolvedCall ->
                    /*
                      The `callerABIConvention.get call here passes to resolvedToTree the information about what
                     */
                    it.origNode.where to resolvedToTree(
                        resolvedCallee = it,
                        callerContext = callerMethodContext?.let { hmc ->
                            CallerContext(
                                callerMemorySpace = hmc.memorySpace,
                                abiResolution = hmc.callerABIConvention?.get(it.origNode.id)
                            )
                        },
                        resolution = callConventionResolution,
                    )
                is InterContractCallResolver.CallGraphNode.CallNode ->
                    it.where to SummaryRewrite(
                        newId = it.id,
                        callTarget = it.callTarget,
                        symbolicInputs = it.symbolicArgs,
                        sighash = it.sigHash
                    )
                is InterContractCallResolver.CallGraphNode.ResolvedRecursionLimit -> it.originalNode.where to Limit
                is InterContractCallResolver.CallGraphNode.TruncateStack -> it.originalLocation to Truncate
            }
        }
    }

    /**
     * resolve call to the method represented by [resolvedCallee], specifically
     * the [analysis.icfg.InterContractCallResolver.CallGraphNode.ResolvedCall.resolveTo] field thereof.
     *
     * If [callerContext] is non-null, then the method that is the source of the call to [resolvedCallee],
     * (i.e., the method that holds [analysis.icfg.InterContractCallResolver.CallGraphNode.ResolvedCall.origNode])
     * is known to pass along its own calldata encoded arguments to [resolvedCallee] via the [CallerContext.abiResolution]
     * field.
     *
     * NB: the recursive nature of how [resolvedToTree] operates means that a caller will only pass a non-null
     * value to [callerContext] if it itself satisfies the ABI call convention, or we can infer an ABI convention from scalarized
     * variables.
     */
    context(IScene)
    private fun resolvedToTree(
        resolvedCallee: InterContractCallResolver.CallGraphNode.ResolvedCall,
        /**
         Let the function being called in the node resolvedCallee be f(a1, a2, ...), happening within some
         method g.

         Then for argument k, if k exists in [callerContext]'s [CallerContext.abiResolution] map, then the kth argument to
         f is known to be a re-encoding of one of g's calldata argument, AND that calldata argument can be translated into a series
         of memory accesses described by the [analysis.icfg.ABICallConvention.PointerTraversal] object.
         */
        callerContext: CallerContext?,
        resolution: CallConventionStrategy
    ) : CanonicalizedTree {
        val callSite = resolvedCallee.origNode

        val calleeId = freshTransientId()

        val calleeSpace = (resolution as? CallConventionStrategy.TryDirectPassing)?.createMemoryModel(calleeId)

        /**
         * Where the magic hapens. If we have consistent abi usage in the callee (calleeAbi)
         * and we have encoding information via the callsite's abiArgs, attempt to match the two.
         */
        val callerMatch = if(resolvedCallee.calleeABI != null && callSite.abiArgs != null && callerContext != null && calleeSpace != null) {
            ABICallConvention.callerMatchesCallee(
                enc_ = callSite.abiArgs,
                expected = resolvedCallee.calleeABI,
                callerMemorySpace = callerContext.callerMemorySpace,
                callerABIResolution = callerContext.abiResolution
            ).bindLeft {
                /**
                 * We only allow calldata arguments to be passed into this method
                 * if we know that the caller supports high-level passing of calldata. That is,
                 * if the caller doesn't have high-level representations of its arguments in calldata,
                 * then they cannot be passed through to this callee.
                 *
                 * NB: if all of the arguments to this call come directly from memory, then it does not matter
                 * if the caller itself has high-level argument passing
                 */
                val pred : (Map.Entry<BigInteger, ABIValue>) -> Boolean = { (arg, v) ->
                    v !is ABIValue.Symbol
                        || v.sym !is ObjectPathAnalysis.ObjectRoot.CalldataRoot
                        || callerContext.abiResolution?.contains(arg) == true
                }
                if(callSite.abiArgs.encodedArgs.all(pred)) {
                    it.toLeft()
                } else {
                    { ->
                        "callee binding mismatch ${callSite.abiArgs.encodedArgs.filterNot(pred)}"
                    }.toRight()
                }
            }.toValue({
                it
            }, {
                logger.debug {
                    "[${callSite.where}] caller/callee mismatch, msg: ${it()}: caller:${callSite.abiArgs}, callee:${resolvedCallee.calleeABI}"
                }
                null
            })
        } else {
            logger.debug {
                "[${callSite.where}] one or both caller/callee null: callerContext=${callerContext} calleeSpace=${calleeSpace} caller:${callSite.abiArgs}, callee:${resolvedCallee.calleeABI}"
            }
            null
        }

        /**
         * Note that even if a caller is not passing through its calldata arguments, the
         * in-memory arguments it passes to the callee may themselves be represented by calldata arguments
         * in the callee. For example:
         *
         * foo(new uint[](...))  --> foo(uint[] calldata x) { bar(x) }
         *
         * These "call site uses" must be passed through to the call to [toCanonical] (which itself will call back
         * into [resolvedToTree]), indicating that the calldata re-encodings at callsites within the [resolvedCallee]
         * can (and should) be represented in terms of high-level, synthetic ABI arguments passed into this method
         * from its caller.
         */

        // This is probably not quite right.
        val callerConvention = callerMatch?.callSiteUse ?: callingConventionOfScalarizedInts(
            this@IScene.find(resolvedCallee.resolveTo)!!.calldataEncoding, resolvedCallee.children
        )

        val resolvedChildren = toCanonical(
            callees = resolvedCallee.children,
            callerMethodContext = calleeSpace?.let { mem ->
                HostMethodContext(
                    memorySpace = mem,
                    callerABIConvention = callerConvention
                )
            },
            callConventionResolution = resolution
        )
        return CanonicalizedTree(
            hostMethod = resolvedCallee.resolveTo,
            resolvedChildren = resolvedChildren,
            /*
              Translate the callee binding generated by the match into the full binding
             */
            withABIConvention = callerMatch?.let {
                resolveArguments(
                    res = it,
                    callerAbi = callSite.abiArgs!!, // this is safe, callerMatch can only be non-null if abiArgs is also non-null
                    calldataResolution = callerContext!!.abiResolution
                )
            },
            inliningId = calleeId,
            storageRewriteForCall = resolvedCallee.storageRewriter
        )
    }

    /**
     * Generate the full binding based off the arguments passed in to this call ([callerAbi]), the (partial)
     * callee binding information in [res], and the callsite usage information ([calldataResolution]) if applicable.
     */
    private fun resolveArguments(
        res: ABICallConvention.ABICalleeBinding,
        callerAbi: ABIArgumentEncoding,
        calldataResolution: Map<BigInteger, ABICallConvention.PointerTraversal>?
    ) : ABICallConvention.ABIFullBinding {
        val output = mutableListOf<Pair<ABICallConvention.PassedArgument, TACSymbol.Var>>()
        /**
         * For each argument we are passing into the function
         */
        for((ind, abv) in callerAbi.encodedArgs) {
            /**
             * Get the corresponding name in the callee. These are the symbolic names generated by the matching process
             * to represent the "high-level" arguments.
             * Note that this could be null if the argument is never used in the
             * callee: the matching process just ensures that the caller passes enough information of the right shape,
             * not that it doesn't pass "too much"
             */
            val outputName = res.argNames[ind] ?: continue

            /**
             * Determine what to bind to the names in the callee
             */
            val toBind = when(abv) {
                is ABIValue.Constant -> ABICallConvention.Constant(
                    abv.k
                )
                is ABIValue.Symbol -> when(abv.sym) {
                    /**
                     * If we are passing the caller's calldata directly through to the callee here, then
                     * the only way that the resolution succeeded is if calldata reoslution was non null,
                     * and it had a mapping value for the argument index we are passing.
                     */
                    is ObjectPathAnalysis.ObjectRoot.CalldataRoot -> {
                        check(calldataResolution != null)
                        ABICallConvention.ResolvedPath(calldataResolution[ind]!!.traversal)
                    }
                    /**
                     * Directly passing arguments from caller's memory is the simple case
                     */
                    is ObjectPathAnalysis.ObjectRoot.V -> ABICallConvention.ResolvedSymbol(
                        abv.sym.v,
                    )
                }
            }
            output.add(toBind to outputName)
        }
        return ABICallConvention.ABIFullBinding(
            decode = res.decode,
            argToFormalBind = output,
            directReads = res.directReads,
            primitiveBinding = res.primitiveBinding,
            initialization = res.initialization
        )
    }

    /**
     * Prepare [tgt] which is required to be a [scene.MethodAttribute.Unique.Constructor]
     * for inlining into a call at [callSite] within [caller].
     *
     * This is very similar to the preparation done for "regular" calls, except we can ignore *all* of the calldata nonsense
     * (instead, we maybe have to deal with codedata nonsense)
     */
    fun prepareConstructor(
        caller: CoreTACProgram,
        callSite: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        tgt: ITACMethod
    ) : CoreTACProgram {
        require(tgt.attribute == MethodAttribute.Unique.Constructor) {
            "$tgt is not a constructor method"
        }
        val parentContract = tgt.getContainingContract()
        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val callSumm = callSite.cmd.summ as CreateSummary
        val callerResolver = VarResolver(
            caller.symbolTable,
            callSite.ptr.block.calleeIdx
        )
        return inCode(tgt) {
            ContractUtils.transformMethod(tgt, ChainedMethodTransformers(listOf(
                MethodToCoreTACTransformer(ReportTypes.NONE) { m : ITACMethod ->
                    getUnique(m, callId).first
                },
                CoreToCoreTransformer(ReportTypes.NONE) { c: CoreTACProgram ->
                    /*
                    Initialize the storage to 0
                    */
                    val storageSetup = initializeFreshStorage(parentContract)

                    val calldata = TACKeyword.CALLDATA.toVar(callIndex = callId)
                    val codeSize = EthereumVariables.getConstructorCodeDataSize(parentContract.instanceId)
                    /*
                    Actually move the contents of CODEDATA in the callee (this is important, because this is how constructors
                    read their "arguments"
                    */
                    val inputSetup = CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = EthereumVariables.getConstructorCodeData(parentContract.instanceId),
                            dstOffset = TACSymbol.lift(0),
                            srcBase = callSumm.inBase as TACSymbol.Var,
                            srcOffset = callSumm.inOffset,
                            length = callSumm.inSize
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = codeSize,
                            rhs = callSumm.inSize.asSym()
                        )
                    ), setOfNotNull(
                        EthereumVariables.getConstructorCodeData(parentContract.instanceId),
                            codeSize,
                            callSumm.inOffset as? TACSymbol.Var,
                            callSumm.inSize as? TACSymbol.Var,
                            callSumm.inBase
                    )).merge(CommandWithRequiredDecls(listOf(
                        /*
                        Calldatasize is defined to be 0 within a constructor
                        */
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = TACKeyword.CALLDATASIZE.toVar(callIndex = callId),
                            rhs = TACSymbol.lift(0).asSym()
                        ),
                        /*
                        All zeroes still (no copying from callers)
                        */
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = calldata,
                            rhs = TACExpr.MapDefinition(
                                defParams = listOf(TACKeyword.TMP(Tag.Bit256, "!init").asSym()),
                                tag = Tag.ByteMap,
                                definition = TACSymbol.lift(0).asSym()
                            )
                        )
                    ), setOf(calldata, TACKeyword.CALLDATASIZE.toVar(callId))))
                    c.prependToBlock0(storageSetup.merge(inputSetup))
                }.lift(),
                MethodToCoreTACTransformer(ReportTypes.NONE) { m: ITACMethod ->
                    environmentInstrumenter(
                        callee = m,
                        callId = callId,
                        caller = TACKeyword.ADDRESS.toVar(),
                        receiverAddress = parentContract.addressSym as TACSymbol,
                        callvalue = callSumm.valueVar.asSym(),
                        blockNum = callerResolver.blocknum.asSym(),
                        timestamp = callerResolver.timestamp.asSym(),
                        basefee = callerResolver.basefee.asSym(),
                        blobbasefee = callerResolver.blobbasefee.asSym(),
                        difficulty = callerResolver.difficulty.asSym(),
                        gasLimit = callerResolver.gasLimit.asSym(),
                        coinbase = callerResolver.coinbase.asSym(),
                        origin = callerResolver.origin.asSym(),
                        sighash = null,
                    )
                },
                CoreToCoreTransformer(ReportTypes.NONE) { m: CoreTACProgram ->
                    addRevertSummaries(
                        m, callId
                    )
                }.lift(),
                CoreToCoreTransformer(ReportTypes.NONE) { c: CoreTACProgram ->
                    val lhs = TACKeyword.CONSTRUCTOR_RETURN.toVar(
                        callIndex = callSite.ptr.block.calleeIdx
                    )
                    c.patching { p ->
                        /*
                        Set the CONSTRUCTOR_RETURN value according to whether the constructor reverted.
                        If a constructor *never* reverts, then this set is redundant w.r.t. the initialization in the Simplifier
                        (which sets the constructor_return directly to the value before the callcore for the create)
                        */
                        parallelLtacStream().filter {
                            it.cmd is TACCmd.Simple.RevertCmd || it.cmd is TACCmd.Simple.ReturnCmd
                        }.sequential().forEach {
                            val (s, rc) = when(it.cmd) {
                                is TACCmd.Simple.RevertCmd -> {
                                    "Constructor revert" to BigInteger.ZERO.asTACSymbol()
                                }
                                is TACCmd.Simple.ReturnCmd -> {
                                    "Constructor success" to parentContract.addressSym
                                }
                                else -> `impossible!`
                            }
                            p.replaceCommand(it.ptr, listOf(
                                TACCmd.Simple.LabelCmd(
                                    _msg = s
                                ),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = lhs,
                                    rhs = (rc as TACSymbol).asSym()
                                )
                            ))
                            p.addVarDecl(lhs)
                        }
                    }
                }.lift(),
                MethodToCoreTACTransformer(ReportTypes.NONE) { m: ITACMethod ->
                    val code = m.code as CoreTACProgram
                    code.patching {
                        code.analysisCache.graph.sinks.forEach(CallStack.stackPopper(it, CallStack.PopRecord(
                            calleeId = callId,
                            callee = tgt.toRef()
                        )))
                        code.analysisCache.graph.roots.forEach(CallStack.stackPusher(it, CallStack.PushRecord(
                            calleeId = callId,
                            callee = tgt.toRef(),
                            convention = CallConventionType.Constructor,
                            summary = null,
                            evmExternalMethodInfo = tgt.toIdentifiers().evmExternalMethodInfo
                        )))
                        it.procedures.add(Procedure(
                            callId = callId,
                            procedureId = ProcedureId.Constructor(tgt)
                        ))
                    }
                }
            ))).code as CoreTACProgram
        }
    }

    private fun resetStorageVar(it: TACSymbol.Var) : TACCmd.Simple = TACCmd.Simple.AssigningCmd.AssignExpCmd(
        lhs = it,
        when (it.tag) {
            is Tag.WordMap -> TACExpr.MapDefinition(
                defParams = listOf(TACKeyword.TMP(Tag.Bit256, "!init").asSym()),
                definition = TACSymbol.lift(0).asSym(),
                tag = Tag.WordMap
            )
            Tag.Bit256 -> TACSymbol.lift(0).asSym()
            Tag.Bool -> TACSymbol.False.asSym()
            else -> `impossible!`
        },
        meta = MetaMap(TACMeta.DYANMIC_STORAGE_MANAGEMENT)
    )

    fun initializeFreshStorage(parentContract: IContractClass) =
        (parentContract.storage as StorageInfoWithReadTracker).storageVariables.map { (it, readTracker) ->
            CommandWithRequiredDecls(listOfNotNull(
                resetStorageVar(it),
                readTracker?.let(this::resetStorageVar)
            ), setOfNotNull(it, readTracker))
        }.let(CommandWithRequiredDecls.Companion::mergeMany)

    private fun addEnvironment(
        callee: ITACMethod,
        callerVar: TACSymbol.Var,
        caller: TACSymbol,
        callvalueVar: TACSymbol.Var,
        callvalue: TACExpr,
        blockNumVar: TACSymbol.Var,
        blockNum: TACExpr,
        timestampVar: TACSymbol.Var,
        timestamp: TACExpr,
        addressVar: TACSymbol.Var,
        address: TACSymbol,
        basefeeVar: TACSymbol.Var,
        basefee: TACExpr,
        blobbasefeeVar: TACSymbol.Var,
        blobbasefee: TACExpr,
        difficultyVar: TACSymbol.Var,
        difficulty: TACExpr,
        gasLimitVar: TACSymbol.Var,
        gasLimit: TACExpr,
        coinbaseVar: TACSymbol.Var,
        coinbase: TACExpr,
        originVar: TACSymbol.Var,
        origin: TACExpr,
        sighashVar: TACSymbol.Var,
        sighash: BigInteger?,
        calldata: TACSymbol.Var
        // TODO CERT-2737: add `tx.origin`
    ): CoreTACProgram {
        return (callee.code as CoreTACProgram).prependToBlock0(
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(callerVar, caller),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(callvalueVar, callvalue),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(blockNumVar, blockNum),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(timestampVar, timestamp),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(addressVar, address),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(basefeeVar, basefee),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(blobbasefeeVar, blobbasefee),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(difficultyVar, difficulty),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(gasLimitVar, gasLimit),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(coinbaseVar, coinbase),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(originVar, origin),
                    // Explicitly set the tacSighash here. This is needed for the case the fallback is invoked, and it
                    // accesses `msg.sig`.
                    if (sighash != null) {
                        // If the sighash is known, just assign it
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(sighashVar, TACSymbol.Const(sighash).asSym())
                    } else {
                        // Otherwise, read it from the first 4 bytes of the calldata
                        // Note - if the calldatalength is < 4 bytes, this will assign garbage to msg.sig, but accessing
                        // that property in this case is undefined behavior also in Solidity.
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(sighashVar, TACExprFactUntyped {
                            select(calldata.atSync(calldata.callIndex).asSym(), BigInteger.ZERO.asTACExpr()) shiftRLog (((EVM_WORD_SIZE - DEFAULT_SIGHASH_SIZE) * EVM_BYTE_SIZE).asTACExpr())
                        })
                    }
                ), setOfNotNull(
                    callerVar,
                    callvalueVar,
                    blockNumVar,
                    timestampVar,
                    addressVar,
                    caller as? TACSymbol.Var,
                    address as? TACSymbol.Var,
                    basefeeVar,
                    blobbasefeeVar,
                    difficultyVar,
                    gasLimitVar,
                    coinbaseVar,
                    originVar,
                    sighashVar,
                    calldata
                ).plus(
                    callvalue.getFreeVars()
                ).plus(
                    blockNum.getFreeVars()
                ).plus(
                    timestamp.getFreeVars()
                ).plus(
                    basefee.getFreeVars()
                ).plus(
                    blobbasefee.getFreeVars()
                ).plus(
                    difficulty.getFreeVars()
                ).plus(
                    gasLimit.getFreeVars()
                ).plus(
                    coinbase.getFreeVars()
                ).plus(
                    origin.getFreeVars()
                )

            )
        )
    }

    private fun environmentInstrumenter(
        callee: ITACMethod,
        callId: CallId,
        caller: TACSymbol,
        receiverAddress: TACSymbol,
        callvalue: TACExpr,
        blockNum: TACExpr,
        timestamp: TACExpr,
        basefee: TACExpr,
        blobbasefee: TACExpr,
        difficulty: TACExpr,
        gasLimit: TACExpr,
        coinbase: TACExpr,
        origin: TACExpr,
        sighash: BigInteger?,
    ): CoreTACProgram {
        val varResolver =
                VarResolver(callee.code as CoreTACProgram, callId)
        return addEnvironment(
                callee,
                varResolver.caller,
                caller,
                varResolver.callvalue,
                callvalue,
                varResolver.blocknum,
                blockNum,
                varResolver.timestamp,
                timestamp,
                varResolver.address,
                receiverAddress,
                varResolver.basefee,
                basefee,
                varResolver.blobbasefee,
                blobbasefee,
                varResolver.difficulty,
                difficulty,
                varResolver.gasLimit,
                gasLimit,
                varResolver.coinbase,
                coinbase,
                varResolver.origin,
                origin,
                varResolver.sighash,
                sighash,
                varResolver.calldata
        )
    }

    private fun getUnique(
        m: ITACMethod,
        callId: CallId,
        callerAddress: BigInteger = BigInteger.ZERO
    ): Pair<CoreTACProgram, (Allocator.Id, Int) -> Int> {
        return (m.code as CoreTACProgram).createCopyWithRemapper(
            callId,
            callerAddress,
            remapCallSummary = false
        )
    }

    @KSerializable
    @Treapable
    sealed class CallConventionType: Serializable, HasKSerializable {
        @KSerializable
        object Serialization: CallConventionType() {
            private fun readResolve(): Any = Serialization
            override fun hashCode() = treapHashObject(this)
        }
        @KSerializable
        object DirectInlining: CallConventionType() {
            private fun readResolve(): Any = DirectInlining
            override fun hashCode() = treapHashObject(this)
        }
        @KSerializable
        object Constructor: CallConventionType() {
            private fun readResolve(): Any = Constructor
            override fun hashCode() = treapHashObject(this)
        }
        @KSerializable
        object FromCVL: CallConventionType() {
            private fun readResolve(): Any = FromCVL
            override fun hashCode() = treapHashObject(this)
        }
    }

    class DirectPassingSetupFailed: Exception()

    private interface CallConventionImpl {
        val convention: CallConventionType
        /**
         * Prepare the callee [method] to receive arguments and return data according to the call convention represented
         * by this interface
         */
        fun setup(method: ITACMethod, forwardMapper: (Allocator.Id, Int) -> Int) : ITACMethod

        /**
         * Instrument the caller [callerCore] for the call location at [callLocation]
         */
        fun instrumentCaller(
            callerCore: CoreTACProgram,
            patch: SimplePatchingProgram,
            callLocation: CmdPointer
        )

        companion object {
            operator fun invoke(conv: CallConvention, abi: ABICallConvention.ABIFullBinding?): CallConventionImpl {
                return if(abi == null) {
                    CalldataSerialization(conv)
                } else {
                    DirectPassing(conv, abi)
                }
            }
        }
    }

    /**
     * Low-level "legacy" style: simply wraps the [CallConvention.setup] method
     */
    private class CalldataSerialization(private val conv: CallConvention) : CallConventionImpl {
        override val convention = CallConventionType.Serialization

        override fun setup(method: ITACMethod, forwardMapper: (Allocator.Id, Int) -> Int): ITACMethod {
            return conv.setup(method)
        }

        override fun instrumentCaller(callerCore: CoreTACProgram, patch: SimplePatchingProgram, callLocation: CmdPointer) { }
    }

    /**
     * Hold onto your butts. Instruments callers and callees according to the direct passing style, as
     * represented in [expected]
     */
    private class DirectPassing(private val conv: CallConvention, val expected: ABICallConvention.ABIFullBinding) : CallConventionImpl {
        override val convention = CallConventionType.DirectInlining

        fun <T> T?.orFailSetup(msg: () -> Any) = this ?: run {
            logger.debug(msg)
            throw DirectPassingSetupFailed()
        }

        override fun setup(method: ITACMethod, forwardMapper: (Allocator.Id, Int) -> Int): ITACMethod {
            val callee = method.code as CoreTACProgram
            val calleeId = callee.entryBlockId.getCallId()

            Logger.regression {
                "Using direct passing for call to ${callee.name}"
            }

            fun Int.resolveABIId() = forwardMapper(Allocator.Id.ABI, this)

            /**
             * An annoying implementation detail: the only real way to set up the bindings to the tacCalldata!nn
             * arguments is via the (private) implementations within [CalldataEncoding.feedInput]. Unlike all other accesses
             * of calldata, these scalarized accesses *do* need to be bound, and I am not willing to blindly generate tacCalldata!nn
             * names and hope for the best.
             *
             * The (not great) alternative is to find those ranges that correspond to the primitive abiArg!nn
             * arguments generated by the matching process, and then "fake" those as the arguments to be bound in
             * the calldata input that is then passed to feedInput.
             *
             * FIXME(jtoman): this *sucks*. I hate faking input arguments to get at implementations that are otherwise unaccessible.
             *  Fix this with refactoring once the argument passing infra has stabilized
             *
             * Alternatively: should we just bind tacCalldata!... ourselves using information collecting by the ABIExpected
             * infra?
             */
            val effectiveInput = conv.input.rangeToDecomposedArg.mapNotNull { (range, _) ->
                if ((range.to - range.from) + BigInteger.ONE != EVM_WORD_SIZE || range.from.mod(EVM_WORD_SIZE) != DEFAULT_SIGHASH_SIZE) {
                    return@mapNotNull null
                }
                val effectiveInd = range.from - DEFAULT_SIGHASH_SIZE
                if(effectiveInd !in expected.primitiveBinding) {
                    return@mapNotNull null
                }
                range to DecomposedCallInputArg.Variable(
                    contractReference = null,
                    v = expected.primitiveBinding[effectiveInd]!!.at(calleeId),
                    scratchRange = range
                )
            }.toMap()
            val scalarPassing = (method.calldataEncoding as CalldataEncoding).copyWithCallId(calleeId).feedInput(
                conv.input.copy(
                    rangeToDecomposedArg = effectiveInput
                ), method
            )
            val callerId = conv.callerId
            if(callerId != NBId.ROOT_CALL_ID) {
                throw UnsupportedOperationException("Caller id is ${callerId}, expected ${NBId.ROOT_CALL_ID}")
            }

            /**
             * In order to determine if all calldata references can be deleted, we must know that all remaining
             * calldata ranges (as detected by the [ABICodeFinder]) are:
             * 1. "common" code (i.e., with multiple possible sources), or
             * 2. decodes/direct reads that are about to be instrumented here.
             *
             * If so, then after the decode/direct read instrumentation that is to follow, all calldata
             * accesses are, effectively, dead, and should be removed.
             *
             * Q: What about calldata references that *aren't* classified in a region?
             * A: We can only reach this point if calldata matching succeeded, which itself can only succeed if the abi
             * expected extraction succeeded, which verifies that all calldatas are classified in some SerDe region.
             */
            val expectedDecodeIds = expected.decode.keys.map { it.resolveABIId() }
            val expectedDirectIds = expected.directReads.keys.map { it.resolveABIId() }
            val removeAllCalldatas = callee.parallelLtacStream().noneMatch {
                it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.check(ABIAnnotator.REGION_START) {
                    it.sources.singleOrNull()?.let {
                        it is ABICodeFinder.Source.Encode || (it is ABICodeFinder.Source.Decode && it.id !in expectedDecodeIds) ||
                                (it is ABICodeFinder.Source.Direct && it.id !in expectedDirectIds)
                    } == true
                }
            }
            logger.debug {
                "In $method, should we remove all SerDe ranges? $removeAllCalldatas"
            }
            logger.debug {
                callee.parallelLtacStream().mapNotNull { it.maybeAnnotation(ABIAnnotator.REGION_START) }
                    .collect(Collectors.toList()).let { "In $method, remaining ranges: $it" }
            }


            val patch = callee.toPatchingProgram()

            logger.debug {
                "In $method expected decodes: ${expected.decode} for callee ${callee.name}"
            }
            expected.decode.forEach { (_id, codeGen) ->
                val id = forwardMapper(Allocator.Id.ABI, _id)
                /*
                  Find the ABIDecodeComplete annotation that has this ID (we expect this to exist)
                 */
                val decodePoint =
                    callee.parallelLtacStream().mapNotNull { it.maybeAnnotation(ABIDecodeComplete.META_KEY) }.filter { it.id == id }
                        .findFirst().get()
                val out = TACKeyword.TMP(tag = Tag.Bit256, suffix = "!dec").at(calleeId)
                val decodeOut = decodePoint.output
                val fieldPointers = decodePoint.fieldPointers
                val base = decodeOut.first()

                /**
                 * [codeGen] here describes how to generate code (with the correct call index) that
                 * that effects the decode in terms of the ABI args, placing the result into out. Those results
                 * are then copied into the variables declared in `decodeOut`
                 */
                val copy = codeGen.codeGen(out,calleeId).merge(decodeOut.map {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs =  it,
                        rhs = out.asSym()
                    )
                }).merge(fieldPointers.map { (x, off) ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = x,
                        rhs = TACExpr.Vec.Add(base.asSym(), off.asTACExpr),
                    )
                })
                /*
                  perform the rewrite
                 */
                logger.debug {
                    "In $method instrumenting calldata use: $_id ($id) for callee ${callee.name}"
                }
                instrumentCalldataUse(ABIDecodeComplete.decodeFinder, callee, id, patch, copy)
            }
            expected.directReads.forEach { (unresolvedId, path) ->
                val id = unresolvedId.resolveABIId()
                val bound = callee.parallelLtacStream().mapNotNull { it.maybeAnnotation(ABIDirectRead.META_KEY) }.filter {
                    it.id == id
                }.findFirst().get()
                /*
                 As above, codeGen and then instrument
                 */
                val copy = path.codeGen(bound.output, calleeId)
                logger.debug {
                    "In $method instrumenting direct reads ($id) for callee ${callee.name}"
                }
                instrumentCalldataUse(ABIDirectRead.readFinder, callee, id, patch, copy)
            }
            if(removeAllCalldatas) {
                callee.parallelLtacStream().filter {
                    /*
                       Why the source != 1 requirement? Note that parallelStream here is over the original program (pre-instrumentation).
                       If we picked up all regions, including those that had single size, then we would "re-instrument"
                       all of the decode/direct regions again, which would either: a) break the patching program, or b) overwrite
                       our changes.

                       Q) What about regions that weren't instrumented above? Or Encode regions?
                       A) The check that defines removeAllCalldatas ensures that all singleton regions will be instrumented
                       above. In other words, any regions we would additionally find without this check are *known* to have
                       been removed already
                    */
                    it.maybeAnnotation(ABIAnnotator.REGION_START)?.let { start -> start.sources.size != 1 } == true
                }.map {
                    /*
                    Find the unique matching end annotation (done with a unique ID)
                     */
                    val id = it.maybeAnnotation(ABIAnnotator.REGION_START)!!.id
                    val end = callee.parallelLtacStream().filter { it.maybeAnnotation(ABIAnnotator.REGION_END)?.id == id }
                        .findFirst().takeIf { it.isPresent }?.get()
                        ?: throw java.lang.IllegalStateException("oh well couldn't find $id in ${method.name}")
                    it.ptr to end.ptr
                }.sequential().forEach { (s, e) ->
                    /*
                    Delete each region found
                     */
                    val pred = splitStart(patch, s)
                    val succ = splitEnd(patch, e)
                    patch.consolidateEdges(succ, listOf(pred))
                }
            }
            /*
             Find the unique point where the free pointer is initialized to 0x80
             */
            val init = callee.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                    it.cmd.lhs == TACKeyword.MEM64.toVar(calleeId) && it.cmd.rhs.equivSym(0x80.toBigInteger().asTACSymbol())
                }
            }.findFirst().get()
            /*
              Initialize it instead to the caller's free pointer value (we are basically starting the call in the memory
              space of the caller, and thus don't want the two regions to overlap)
             */
            patch.replaceCommand(init.ptr, listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = TACKeyword.MEM64.toVar(calleeId),
                    rhs = TACKeyword.MEM64.toVar(callerId)
                )
            ))
            patch.addVarDecl(TACKeyword.MEM64.toVar(callerId))
            /*
              The basic instrumentation is now complete
             */
            val withDecodes = patch.toCode(callee)

            /* Much easier to check here if there are any remaining decode commands for the expected decodes */
            val okToPassDirectly =
                withDecodes.parallelLtacStream().noneMatch { lcmd ->
                    lcmd.cmd is TACCmd.Simple.AnnotationCmd && lcmd.cmd.check(ABIAnnotator.REGION_START) {
                        it.sources.any {
                            when(it)  {
                                is ABICodeFinder.Source.Direct -> it.id in expectedDirectIds && run {
                                    logger.info {
                                        "$lcmd: found remaining direct read ${it.id}/${it.id.resolveABIId()} in $expectedDirectIds"
                                    }
                                    true
                                }
                                is ABICodeFinder.Source.Decode -> it.id in expectedDecodeIds && run {
                                    logger.info {
                                        "$lcmd: Found remaining decode ${it.id}/${it.id.resolveABIId()} in $expectedDecodeIds"
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                }

            if (!okToPassDirectly) {
                throw DirectPassingSetupFailed()
            }

            /* set up argument passing. tgt was generated without call indices, but code gen previous has added
                it, so the addition of calleeId below is crucial to correctness
             */
            val argPassing = expected.argToFormalBind.map { (arg, tgt) ->
                when(arg) {
                    is ABICallConvention.Constant -> CommandWithRequiredDecls<TACCmd.Simple>(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = tgt.at(calleeId),
                            rhs = arg.const.asTACSymbol().asSym()
                        )
                    ), setOf(tgt))
                    is ABICallConvention.ResolvedPath -> arg.path.codeGen(tgt.at(calleeId), callerId)
                    is ABICallConvention.ResolvedSymbol -> CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = tgt.at(calleeId),
                        rhs = if (tgt.tag == Tag.Bit256 && arg.sym.tag == Tag.Bool) {
                            TACExpr.TernaryExp.Ite(arg.sym.asSym(), 0.asTACExpr, 1.asTACExpr)
                        } else {
                            arg.sym.asSym()
                        }
                    ), tgt.at(calleeId), arg.sym)
                }
            }.let(CommandWithRequiredDecls.Companion::mergeMany).merge(
                /*
                  "copy" memory. This is *extremely* cheap, it just sets up a read-over-write-chains that extend
                  back to the caller's writes
                 */
                CommandWithRequiredDecls(expected.initialization.map {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = ConcretePartition(callIdx = calleeId, partitionId = it.key.id).toBaseMap(),
                        rhs = it.value.toInitializationTerm()
                    )
                }, expected.initialization.keys.mapToSet {
                    ConcretePartition(callIdx = calleeId, partitionId = it.id).toBaseMap()
                })
            ).merge(scalarPassing)
            /*
             We don't do high-level returns (yet) so use the low-level version for now
             */
            val withHighLevel = conv.handleOut(
                withDecodes.prependToBlock0(argPassing)
            )
            return method.shallowForkWithCodeAndCalldataEncoding(withHighLevel, (method.calldataEncoding as CalldataEncoding).copyWithCallId(calleeId))
        }

        /**
         * Given an implementation [rangeFinder] for finding ranges for a specific type of
         * [analysis.pta.ABICodeFinder.Source] within a [callee] method, find all such ranges
         * with [id] and remove them in [patch], replacing the "last" such range with [copy].
         *
         * Q) how is "last" determined here?
         * A) Domination, it is assumed (and checked by the [ABIRangeFinder] class)
         * that all ranges involved in a decode/encode have a strict domination relationship with each other
         */
        private fun instrumentCalldataUse(
            rangeFinder: ABIRangeFinder<*>,
            callee: CoreTACProgram,
            id: Int,
            patch: SimplePatchingProgram,
            copy: CommandWithRequiredDecls<TACCmd.Simple>,
        ) {
            val ranges = rangeFinder.findRangeFor(
                callee, id = id
            ).orFailSetup {
                "instrumentCalldataUse: findRangeFor(${callee.name}, ${id}) returned null"
            }

            val (finalStart, finalEnd) = rangeFinder.finalRange(callee, ranges).orFailSetup {
                "instrumentationCalldataUser: finalRange returned null for ${callee.name} ranges: ${ranges}"
            }
            /*
              Up until the last range, just remove
             */
            logger.debug { "Found ${ranges.size} calldata use ranges for $id in ${callee.name}"}
            for ((start, end) in ranges) {
                if (start == finalStart && end == finalEnd) {
                   continue
                }
                logger.debug { "Removing calldatause range $start -> $end in ${callee.name} for $id" }
                val decodeSucc = splitEnd(patch, end)
                val decodeStart = splitStart(patch, start)
                patch.consolidateEdges(decodeSucc, listOf(decodeStart))
            }
            val decodeSucc = splitEnd(patch, finalEnd)
            val decodeStart = splitStart(patch, finalStart)
            val addedEdges = patch.consolidateEdges(decodeSucc, listOf(decodeStart))
            for ((startEdge, endEdge) in addedEdges) {
                patch.insertAlongEdge(
                    startEdge, endEdge, copy.cmds
                )
            }
            patch.addVarDecls(copy.varDecls)
        }

        private fun splitStart(patch: PatchingTACProgram<TACCmd.Simple>, where: CmdPointer) : NBId {
            /*
              Split block at leaves the command passed to it *outside* of the block that was generated. But this
              is the first command we want to remove, so just turn it into a nop, it'll be removed later.
             */
            val newBlock = patch.splitBlockAfter(where)
            patch.replaceCommand(where, listOf(TACCmd.Simple.NopCmd))
            return newBlock
        }

        private fun splitEnd(patch: PatchingTACProgram<TACCmd.Simple>, where: CmdPointer) : NBId {
            return patch.splitBlockAfter(where)
        }

        override fun instrumentCaller(callerCore: CoreTACProgram, patch: SimplePatchingProgram, callLocation: CmdPointer) {
            /*
              Now remove all code that effects the encoding process in the caller.
              No actual binding is done here, all that is done in the callee's method, and thus
              must of this method is just deleting code.
             */
            val encodeId = conv.input.encodedArguments?.encodeId!!
            logger.debug { "instrumentCaller ${callerCore.name} for ${encodeId} @ ${callLocation}" }
            val ranges = ABIEncodeComplete.encodeFinder.findRangeFor(callerCore, encodeId).orFailSetup {
                "instrumentCaller: findRangeFor(${callerCore.name}, ${encodeId}) at ${callLocation} returned null"
            }

            val (finalStart, finalEnd) = ABIEncodeComplete.encodeFinder.finalRange(callerCore, ranges).orFailSetup {
                "instrumentCaller: finalRange returned null for ${callerCore.name} ranges: ${ranges}"
            }

            for((start, end) in ranges) {
                if (start == finalStart && end == finalEnd) {
                    continue
                }
                logger.debug { "Removing range $start -> $end in ${callerCore.name} for ${encodeId}" }
                val startBlock = splitStart(patch, start)
                val endBlock = splitEnd(patch, end)
                patch.consolidateEdges(endBlock, listOf(startBlock))
            }
            logger.debug { "Removing range $finalStart -> $finalEnd in ${callerCore.name} for ${encodeId}" }
            val startBlock = splitStart(patch, finalStart)
            val endBlock = splitEnd(patch, finalEnd)
            /* is the base pointer for the output of the call computed in the last serialization block?
               If so, recreate that declaration here */
            val baseSym = callerCore.analysisCache.graph.elab(callLocation).snarrowOrNull<CallSummary>()?.let {
                it.outOffset as? TACSymbol.Var
            }
            val dom = callerCore.analysisCache.domination
            infix fun CmdPointer.dominates(other: CmdPointer) = dom.dominates(this, other)

            val edges = patch.consolidateEdges(endBlock, listOf(startBlock))
            val callerIdx = callerCore.entryBlockId.calleeIdx
            if(baseSym != null && callerCore.analysisCache.def.defSitesOf(baseSym, callLocation).all {
                    finalStart.dominates(it) && it.dominates(finalEnd) &&
                            callerCore.analysisCache.graph.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs?.equivSym(TACKeyword.MEM64.toVar(
                                callerIdx
                            )) == true
                }) {
                for((eStart, eEnd) in edges) {
                    patch.insertAlongEdge(eStart, eEnd, listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = baseSym, rhs = TACKeyword.MEM64.toVar(callerIdx).asSym())
                    ))
                }
            }
        }
    }

    private fun addProcedure(m: CoreTACProgram, callId: CallId, call: ITACMethod): CoreTACProgram {
        return m.copy(
                procedures = m.procedures.plus(
                        Procedure(
                                callId,
                                when (call.attribute) {
                                    MethodAttribute.Common -> ProcedureId.Standard(call)
                                    MethodAttribute.Unique.Fallback -> ProcedureId.Standard(call)
                                    MethodAttribute.Unique.Whole -> ProcedureId.WholeContract(call)
                                    MethodAttribute.Unique.Constructor -> ProcedureId.Constructor(call)
                                }
                        )
                )
        )
    }

    private fun prepareMethodForInlining(
        calleeId: CallId,
        resolvedAddress: TACSymbol,
        call: ITACMethod,
        callType: TACCallType,
        callConvention: CallConventionImpl,
        callvalue: TACExpr,
        blocknum: TACExpr,
        timestamp: TACExpr,
        callerIndex: CallId,
        summary: CallSummary?,
        symbolTable: TACSymbolTable,
        extraInstrumentation: InstrumentationPass?,
        basefee: TACExpr,
        blobbasefee: TACExpr,
        difficulty: TACExpr,
        gasLimit: TACExpr,
        coinbase: TACExpr,
        origin: TACExpr,
        storageRewriter: (ITACMethod) -> CoreTACProgram,
    ): ITACMethod {
        /**
         * The value of `this` (aka tacAddress) in the *called* function.
         * For a delegate call, this@callee == this@caller.
         * For a direct call, this@callee == addressOfHostContract (AKA resolvedAddress)
         */
        val addressSym = if(callType == TACCallType.DELEGATE) {
            TACKeyword.ADDRESS.toVar(callerIndex)
        } else {
            resolvedAddress
        }

        /**
         * The value of msg.sender (AKA tacCaller) in the *called* function.
         * For a delegate calls, msg.sender@callee == msg.sender@caller
         * For a direct alle, msg.sender@callee == this@caller (AKA tacAddress@caller)
         */
        val callerSym = if(callType == TACCallType.DELEGATE) {
            TACKeyword.CALLER.toVar(callerIndex)
        } else {
            TACKeyword.ADDRESS.toVar(callerIndex)
        }

        lateinit var forwardMapper: (Allocator.Id, Int) -> Int

        val callUpdated = ContractUtils.transformMethod(
                call,
                ChainedMethodTransformers(
                        listOfNotNull(
                                extraInstrumentation?.let { (report, inst) ->
                                    val curried = { p: CoreTACProgram -> inst(calleeId, p)}
                                    CoreToCoreTransformer(report, curried)
                                }?.lift(),
                                MethodToCoreTACTransformer(ReportTypes.STORAGE_REWRITER, storageRewriter),
                                // uniquify
                                MethodToCoreTACTransformer(ReportTypes.UNIQUE_IDS) { m: ITACMethod ->
                                    val (toRet, mapper) = getUnique(m, calleeId)
                                    forwardMapper = mapper
                                    toRet
                                },
                                // update procedures
                                CoreToCoreTransformer(ReportTypes.ADD_PROCEDURE) { m: CoreTACProgram ->
                                    addProcedure(m, calleeId, call)
                                }.lift(),
                                // instrument the env
                                MethodToCoreTACTransformer(
                                    ReportTypes.ENV_INSTRUMENT
                                ) { m: ITACMethod ->
                                    environmentInstrumenter(
                                        m,
                                        calleeId,
                                        callerSym,
                                        addressSym,
                                        callvalue,
                                        blocknum,
                                        timestamp,
                                        basefee,
                                        blobbasefee,
                                        difficulty,
                                        gasLimit,
                                        coinbase,
                                        origin,
                                        summary?.sigResolution?.singleOrNull()
                                    )
                                },
                                // setup the call by the convention
                                // Must be set up after instrumenting the env since the env instrumenter may attempt to
                                // read from the calldata, and doing this transform after promises that the the TAC the
                                // calldata will be set-up _before_ the env instrumentation.
                                MethodToCoreTACTransformer(ReportTypes.CALL_CONVENTION) { m: ITACMethod ->
                                    callConvention.setup(m, forwardMapper).code as CoreTACProgram
                                },
                                // handle reverting
                                CoreToCoreTransformer(ReportTypes.REVERT_SUMMARIES) { p_: CoreTACProgram ->
                                    addRevertSummaries(
                                            p_,
                                            calleeId
                                    )
                                }.lift(),
                                // summarize returns/reverts
                                CoreToCoreTransformer(ReportTypes.RETURNS_TO_SUMMARIES) { p_: CoreTACProgram ->
                                    convertReturnsToSummaries(p_)
                                }.lift(),
                        MethodToCoreTACTransformer(ReportTypes.TRACE_PUSH_POP) { m: ITACMethod ->
                            val code = m.code as CoreTACProgram
                            code.patching { patching ->
                                code.analysisCache.graph.sinks.forEach(
                                    CallStack.stackPopper(
                                        patching, CallStack.PopRecord(
                                            callee = call.toRef(),
                                            calleeId = calleeId
                                        )
                                    )
                                )
                                code.analysisCache.graph.roots.forEach(
                                    CallStack.stackPusher(
                                        patching, CallStack.PushRecord(
                                            callee = call.toRef(),
                                            summary = summary,
                                            convention = callConvention.convention,
                                            calleeId = calleeId,
                                            evmExternalMethodInfo = call.toIdentifiers().evmExternalMethodInfo
                                        )
                                    )
                                )
                                summary?.variables?.forEach { v ->
                                    check(symbolTable.contains(v)) {
                                        "malformed typescope while inlining, " +
                                            "expected to contain $v but does not"
                                    }
                                }
                                summary?.let { patching.addVarDecls(it.variables) }
                            }
                        }
                        )
                )
        )

        return callUpdated
    }

    fun inlineDelegates(scene: IScene, resolutionFilter: (CallSummary, ContractId) -> Boolean) {
        logger.info {"Inlining delegatecalls"}
        val methods = scene.getContracts().flatMap(IContractClass::getDeclaredMethods)
        val cfg = with(scene) {
            with(InliningDecisionManager.DelegatesOnly.elaborateWith(resolutionFilter)) {
                InterContractCallResolver.resolveCalls(methods)
            }
        }
        inlineCfg(methods, cfg, scene, ReportTypes.INLINE_DELEGATES, InliningDecisionManager.DelegatesOnly)
    }

    @KSerializable
    @GenerateRemapper
    @Treapable
    data class RestoreValueSummary(
        @GeneratedBy(Allocator.Id.CALL_ID) val callId: CallId,
        val revertBalance: Boolean,
    ) : AmbiSerializable, RemapperEntity<RestoreValueSummary>

    @KSerializable
    @GenerateRemapper
    @Treapable
    data class SaveValuesSummary(@GeneratedBy(Allocator.Id.CALL_ID) val callId: Int) : AmbiSerializable, RemapperEntity<SaveValuesSummary>

    /**
     * Meta attached to a [CoreTACProgram] (via [withMetaValue] & [stateExtensions]) indicating which state variables
     * from different contract(s) C have been merged into a different contract k via delegate calls.
     *
     * [instanceToExtendedVars] maps the [ContractClass.instanceId] of k to yet another map. This maps the instance
     * id another contract c to the state variables of c that have been merged into k's state. NB that the state variables
     * in the Set have already been rewritten to live in k's state.
     *
     * In other words, merging all of the state variables in the codomain of the map in `instanceToExtendedVars[k]`
     * with the state variables of `k` gives the "effective" state of `k` post delegate call inlining.
     */
    data class ExtendedStateVars(
        val instanceToExtendedVars: Map<BigInteger, Map<BigInteger, Set<TACSymbol.Var>>>
    ) : TransformableVarEntity<ExtendedStateVars> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ExtendedStateVars {
            return ExtendedStateVars(
                instanceToExtendedVars = instanceToExtendedVars.mapValues { (_, v) ->
                    v.mapValues { (_, ext) ->
                        ext.mapToSet(f)
                    }
                }
            )
        }

        companion object {
            val META_KEY = MetaKey<ExtendedStateVars>("tac.state.extension")
        }
    }

    /**
     * This function processes pre-existing revert annotations in [program]. Revert annotations are added when there is a
     * revert in a Solidity call, as opposed to CVL calls.
     *
     * Revert annotations help keep a snapshot of the state that should be restored after the revert.
     * The restored state must include all nested calls within the reverted call, even if they did not revert themselves.
     * For example, this includes all contracts storages, ghosts, balances, and nonces.
     *
     * In addition, state mutations that were instrumented from outside the call by its caller must be reverted here.
     * For example, balance transfers from caller to callee and caller nonce update in create calls.
     * These state mutations are the ones generated in [EthereumVariables].
     *
     * @return The updated TAC program
     */
    fun materializeRevertManagement(program: CoreTACProgram, scene: IScene) : CoreTACProgram {
        val revertAnnotations = program.parallelLtacStream().filter {
            it.cmd is TACCmd.Simple.AnnotationCmd && (it.cmd.annot.k == TACMeta.REVERT_RESTORE || it.cmd.annot.k == TACMeta.REVERT_SAVE)
        }.map { it.narrow<TACCmd.Simple.AnnotationCmd>() }.collect(Collectors.toList())
        if (revertAnnotations.isEmpty()) {
            return program
        }
        val save = revertAnnotations.filter {
            it.cmd.annot.k == TACMeta.REVERT_SAVE
        }
        val patching = program.toPatchingProgram()

        // Saving a snapshot of the state that should be restored after the revert

        fun makeLastStorage(key: BigInteger, variable: TACSymbol.Var, originalMaker: (BigInteger, tag: Tag) -> TACSymbol.Var) : Pair<TACSymbol.Var, TACSymbol.Var> {
            return (variable to originalMaker(key,variable.tag) /*EthereumVariables.constructOriginalStorageVar(key, variable.tag)*/)
                .apply {
                    patching.addVarDecl(this.first)
                    patching.addVarDecl(this.second)
                    if (this.second in program.symbolTable.tags && program.symbolTable.tags[this.second] != this.second.tag) {
                        throw IllegalStateException("Tag collision on ${this.second} in ${program.name}")
                    }
                }
        }

        val extendedState = program.stateExtensions

        val currToSavedStorageVarsMap =
            save.associate { cmdView ->
                val saveValuesSummary = cmdView.cmd.annot.v as SaveValuesSummary
                val id = saveValuesSummary.callId
                id to scene.getUnifiedStorage().flatMap { (key, unifiedStorage) ->

                    (unifiedStorage.persistentStorageInfo as StorageInfoWithReadTracker).storageVariables.flatMap { (variable, readTracker) ->
                        check(variable.meta.find(TACMeta.STORAGE_KEY) == key) {
                            "Mismatch keys $variable with ${variable.meta.find(TACMeta.STORAGE_KEY)}, expecting $key"
                        }
                        listOfNotNull(
                            makeLastStorage(key, variable) { key, tag -> EthereumVariables.constructOriginalStorageVar(key, tag)},
                            readTracker?.let { makeLastStorage(key, it) { key, tag -> EthereumVariables.constructOriginalStorageVar(key, tag)} }
                        )
                    } + (unifiedStorage.transientStorageInfo as StorageInfo).storageVariables.flatMap { variable ->
                        check(variable.meta.find(TACMeta.TRANSIENT_STORAGE_KEY) == key) {
                            "Mismatch keys $variable with ${variable.meta.find(TACMeta.TRANSIENT_STORAGE_KEY)}, expecting $key"
                        }
                        listOf(
                            makeLastStorage(key, variable) { key, tag -> EthereumVariables.constructOriginalTransientStorageVar(key, tag)},
                        )
                    } + extendedState.instanceToExtendedVars.get(key)?.values?.flatMap { extendedVars ->
                        extendedVars.map { extendedStorageVar ->
                            makeLastStorage(key, extendedStorageVar) { k, tag ->
                                EthereumVariables.constructOriginalStorageVar(k, tag)
                            }
                        }
                    }.orEmpty()
                }
            }.toMap()  // making the map immutable

        val backupState = TACKeyword.entries.filter { it.stateVarWithBackup }
        /*
          Save id -> [(orig, backup), ... ]
         */
        val savedCorrespondenceMap = save.associate {
            (it.cmd.annot.v as SaveValuesSummary).callId to backupState.map {
                val freshBackupVar = it.freshBackupVar()
                patching.addVarDecl(freshBackupVar)
                it.toVar() to freshBackupVar
            }
        }

        patching.addVarDecl(EthereumVariables.nonce)
        patching.addVarDecl(EthereumVariables.balance)
        patching.addVarDecl(EthereumVariables.creationCount)
        val revertMeta = MetaMap(TACMeta.REVERT_MANAGEMENT)

        val ghostCheckpoints = mutableMapOf<Int, VariableCheckpoint>()

        for (r in revertAnnotations) {
            val annot = r.cmd.annot
            if (annot.k == TACMeta.REVERT_SAVE) {
                // Saves a snapshot before state mutations that were instrumented from outside the call by its caller

                val which = (annot.v as SaveValuesSummary).callId
                val saveCommands = currToSavedStorageVarsMap[which]!!.map { (orig, saved) ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = saved,
                        rhs = orig.asSym(),
                        meta = revertMeta
                    )
                } + savedCorrespondenceMap[which]!!.map { (orig, backup) ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = backup,
                        rhs = orig.asSym(),
                        meta = revertMeta
                    )
                } + TACCmd.Simple.AnnotationCmd(
                    TACCmd.Simple.AnnotationCmd.Annotation(
                        GHOST_SAVE,
                        ghostCheckpoints.computeIfAbsent(which) {
                            VariableCheckpoint.newGhostCheckpoint()
                        }
                    )
                ) + SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageBackupPoint(which).toAnnotation()

                patching.replaceCommand(r.ptr, saveCommands)
            } else {
                // restore to snapshot

                check(annot.k == TACMeta.REVERT_RESTORE)
                val annotation = (annot.v as RestoreValueSummary)
                val which = annotation.callId
                // when a function reverts there are some EVM variable updates that we must insert as well:
                // since storage is reverting, we revert any changes to: storage variables (split and unsplit), balance, nonce, and ghost variables
                // in the future if we support contract creation this may include codesize, codedata, extcodesize, and extcodedata
                val stateRestoreCommands = mutableListOf<TACCmd.Simple>()
                currToSavedStorageVarsMap[which]!!.mapTo(stateRestoreCommands) { (orig, saved) ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = orig,
                        rhs = saved.asSym(),
                        meta = revertMeta
                    )
                }

                stateRestoreCommands.add(SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRevert(which).toAnnotation())
                stateRestoreCommands.add(
                    TACCmd.Simple.AnnotationCmd(
                        TACCmd.Simple.AnnotationCmd.Annotation(
                            GHOST_RESTORE,
                            ghostCheckpoints.computeIfAbsent(which) {
                                VariableCheckpoint.newGhostCheckpoint()
                            }
                        )
                    )
                )

                patching.addVarDecl(TACKeyword.CONTRACT_COUNT.toVar())

                if (annotation.revertBalance) {
                    val callId = r.ptr.block.calleeIdx
                    val amount = EthereumVariables.callvalue.at(callIndex = callId)
                    val trg = EthereumVariables.caller.at(callIndex = callId)
                    val revertBlock = EthereumVariables.transferBalance(
                        src = EthereumVariables.address.at(callIndex = callId), trg = trg, amount = amount
                    )
                    patching.addRequiredDecls(revertBlock)
                    stateRestoreCommands.addAll(revertBlock.cmds)
                }

                savedCorrespondenceMap[which]!!.mapTo(stateRestoreCommands) { (orig, saved) ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = orig,
                        rhs = saved.asSym(),
                        meta = revertMeta
                    )
                }

                patching.replaceCommand(r.ptr, stateRestoreCommands)
            }
        }
        return patching.toCode(program)
    }

    private fun addRevertSummaries(
        program: CoreTACProgram,
        currentCallId: CallId
    ): CoreTACProgram {

        val graph = program.analysisCache.graph
        val revertBlocks = graph.blocks.mapNotNull {
            it.takeIf {
                it.commands.last().let {
                    it.cmd is TACCmd.Simple.RevertCmd
                }
            }?.id
        }
        val patching = program.toPatchingProgram()
        patching.addBefore(graph.elab(program.entryBlockId).commands.first().ptr, listOf(
                TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(
                        TACMeta.REVERT_SAVE, SaveValuesSummary(currentCallId)
                ))
        ))
        revertBlocks.forEach {
            val lastPointer = graph.elab(it).commands.last().ptr
            patching.addBefore(lastPointer, listOf(
                    TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(
                            TACMeta.REVERT_RESTORE, RestoreValueSummary(
                                callId = currentCallId,
                                revertBalance = false, // responsibility for reverting balances is in simplifier
                        )
                    ))
            ))
        }
        return patching.toCode(program)
    }
}
