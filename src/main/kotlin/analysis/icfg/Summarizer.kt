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

import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.EthereumVariables.returndata
import analysis.EthereumVariables.returnsize
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.*
import instrumentation.calls.CalldataEncoding
import log.*
import report.*
import report.callresolution.CallResolutionTableSummaryInfo
import scene.*
import spec.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.ProgramGenMixin.Companion.emptyProgram
import spec.converters.*
import spec.converters.repr.CVLDataInput
import spec.converters.repr.CVLDataOutput
import spec.cvlast.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.FromVMContext
import spec.cvlast.typedescriptors.ToVMContext
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.CallId
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACMeta.IS_EXTCODESIZE
import vc.data.TACSymbol.Companion.Zero
import vc.data.TACSymbol.Companion.atSync
import vc.data.tacexprutil.TACCmdStructFlattener
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.SUMMARIZATION)
private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)

typealias ExternalLinkingState = Summarizer.LinkingState<BigInteger>
typealias InternalLinkingState = Summarizer.LinkingState<Int>

/**
 * Generates code for the different high-level summaries. e.g., NONDET, HAVOC, dispatcher
 */
object Summarizer {
    private const val ALWAYS_SUMMARY_OUT_BUFFER_SIZE = 0x20

    fun Collection<Summarization.AppliedSummary.FromUserInput>.resolveCandidates(): Summarization.AppliedSummary.FromUserInput? {
        /*
         * distinguish between explicitly given summaries and "default" summaries, as can be introduced by a
         * config-based summarization, e.g. [Config.SummarizeExtLibraryCallsAsNonDetPreLinking]
         */
        val (explicit, fallback) = this.partition {
            when(it) {
                is Summarization.AppliedSummary.Config -> false
                is Summarization.AppliedSummary.MethodsBlock -> true
            }
        }
        return explicit.resolveSummarySelectionCandidates({
            (this as Summarization.AppliedSummary.MethodsBlock).summarizedMethod
        }) {
            if(fallback.size > 1) {
                logger.warn {
                    "Multiple configurations claim to have a selection for summary: $fallback"
                }
            }
            fallback.singleOrNull()
        }
    }

    private inline fun <T> Collection<T>.resolveSummarySelectionCandidates(
        key: T.() -> CVL.SummarySignature,
        fallback: () -> T?
    ): T? {
        val res = this
        return when(res.size) {
            1 -> res.single()
            else -> {
                /**
                 * Find the "preferred" summary, predence is:
                 * 1. Exact
                 * 2. wildcard (e.g., _.foo(...) )
                 * 3. catch-all (e.g., Contract._ => ...)
                 * 4. unresolved (e.g., _._ => ...)
                 *
                 * the explicit size checks mean we conservatively bail out if we get multiple matches within a precedence
                 * level, a more courageous man would make this an exception.
                 */
                val toPriority = { sig: CVL.SummarySignature ->
                    when(sig) {
                        is CVL.SummaryTarget.ExactFunction -> 0 to "exact"
                        is CVL.SummaryTarget.AnyContract -> 1 to "wildcard summary"
                        is CVL.ExternalAnyInContract -> 2 to "catch-all summary"
                        CVL.ExternalUnresolved -> 3 to "unresolved summary"
                    }
                }

                val grouped = res.groupBy {toPriority(it.key())}
                return grouped.entries.minByOrNull { it.key.first }?.let { (groupKey, group) ->
                    if (group.size > 1) {
                        logger.warn { "Multiple potential ${groupKey.second} matches for application, $group" }
                        return null
                    } else {
                        group.single()
                    }
                } ?: fallback()
            }
        }
    }

    fun <T: CVL.SummarySignature> Collection<Map.Entry<T, SpecCallSummary.ExpressibleInCVL>>.resolveCandidates() : Pair<T, SpecCallSummary.ExpressibleInCVL>? {
        return this.resolveSummarySelectionCandidates(Map.Entry<T, SpecCallSummary>::key) { null }?.toPair()
    }

    @SuppressRemapWarning
    data class SummaryBlock(
        val code: CoreTACProgram,
        val proc: Procedure
    )

    fun inlineAutoHavocAsProverDefault(
        patching: SimplePatchingProgram,
        s: IScene,
        currInstanceId: BigInteger,
        summ: LTACCmdView<TACCmd.Simple.SummaryCmd>
    ) {
        val callSummary = summ.cmd.summ as CallSummary
        val content = summ.wrapped.cmd.metaSrcInfo?.getSourceDetails()?.sanitizedContentWithLoc
        loggerSetupHelpers.info { "Applied auto-havoc in ${patching.name}@${summ.ptr}" +
            content?.let { src -> " (src: ${src})" }.orEmpty() +
            " known callee info: ${callSummary.callTarget}; sig info: ${callSummary.sigResolution}"
        }
        val havocType = Havocer.resolveHavocType(s, currInstanceId, callSummary)
        inlineHavoc(
            patching,
            s,
            currInstanceId,
            summ,
            SpecCallSummary.HavocSummary.Auto(SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY),
            havocType,
            Summarization.AppliedSummary.Prover(SpecCallSummary.HavocSummary.Auto(SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY)),
            CallResolutionTableSummaryInfo.HavocInfo.UnresolvedCall(havocType)
        )
    }

    private fun inlineHavoc(
        patching: SimplePatchingProgram,
        s: IScene,
        currInstanceId: BigInteger,
        summ: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        havocSpecCallSummary: SpecCallSummary.HavocSummary,
        havocType: Havocer.HavocType,
        appliedSummary: Summarization.AppliedSummary,
        callResolutionTableSummaryInfo: CallResolutionTableSummaryInfo
    ) {
        inlineSummary(
            patching,
            summ,
            SummaryBlock(
                code = Havocer.generateHavocBlock(
                    havoc = havocType,
                    sourceInstanceId = currInstanceId,
                    callSummary = summ.cmd.summ as CallSummary,
                    s = s,
                    callContext = summ.wrapped
                ).toCode(summ.ptr.block.getCallId()),
                proc = Procedure(
                    procedureId = ProcedureId.Summary(
                        summaryType = havocSpecCallSummary
                    ),
                    callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
                )
            ),
            appliedSummary = appliedSummary,
            callResolutionTableSummaryInfo = callResolutionTableSummaryInfo
        )
    }

    /**
     * Tries to match [summ] with one of the known contracts in [scene].
     * If it matches it with one of those with trivial fallback function implementation,
     * it will inline a revert command. If it matches it with one of those with a non-trivial fallback,
     * then, this fallback function's implementation will be inlined.
     */
    fun inlineFallbackDispatcher(
        patching: SimplePatchingProgram,
        scene: IScene,
        summ: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        getCallersAtPointer: (CmdPointer) -> Collection<MethodRef>
    ) {
        /** For each contract in [scene] which has a non-trivial fallback function implementation,
         * maps the contract's id to the ITACMethod of that fallback function. */
        val nonTrivialFallback = scene.getContracts().mapNotNull {
            it.instanceId `to?` it.getMethodByUniqueAttribute(MethodAttribute.Unique.Fallback)?.takeIf {method ->
                (method.code as CoreTACProgram).parallelLtacStream().anyMatch { tacCmd ->
                    tacCmd.cmd.isReturn()
                }
            }
        }.toMap()
        val callSumm = summ.cmd.summ as CallSummary
        val callerIndex = summ.ptr.block.calleeIdx
        val succ = patching.splitBlockAfter(summ.ptr)
        val callerRc = EthereumVariables.rc.at(callIndex = callerIndex)
        val returnSize = TACKeyword.RETURN_SIZE.toVar(callIndex = callSumm.outBase.callIndex)
        val acceptCode = CommandWithRequiredDecls<TACCmd.Simple>(listOf(
            TACCmd.Simple.LabelCmd("→ Accepted transferred funds")
        )).merge(basicSuccessCode(summ, TACSymbol.lift(0))).merge(listOf(
            TACCmd.Simple.LabelCmd("←"),
            TACCmd.Simple.AnnotationCmd(SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                Summarization.AppliedSummary.Config.OptimisticFallback,
                callSumm.summaryId
            )),
            TACCmd.Simple.JumpCmd(succ)
        ))
        val acceptBlock = patching.addBlock(summ.ptr.block, acceptCode)
        val revertBlock = patching.addBlock(
            summ.ptr.block,
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.LabelCmd("→ Rejected transferred funds"),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = callerRc,
                        rhs = TACSymbol.lift(0).asSym()
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = returnSize,
                        rhs = TACSymbol.lift(0).asSym()
                    ),
                    TACCmd.Simple.LabelCmd("←"),
                    TACCmd.Simple.AnnotationCmd(SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                        Summarization.AppliedSummary.Config.OptimisticFallback,
                        callSumm.summaryId
                    )),
                    TACCmd.Simple.JumpCmd(dst = succ)
                )
            ).merge(callerRc, returnSize)
        )

        /*
         * The condition variable that we branch on: if true the receiver reverts
         */
        val useRevertFlag = TACKeyword.TMP(Tag.Bool, "!useRevert").toUnique("!").at(callerIndex)
        /*
          The result of checking if the address is an EOA, i.e. has the SMT solver decided that toVar represents
          an externally owned account.
         */
        val eoaLkp = TACKeyword.TMP(Tag.Bit256, "!EOALkp").toUnique("!").at(callerIndex)
        /*
          Is eoaLkp == 0. If this is true, then the smt solver has decided that this unknown contract
          should revert. Note that if "to" is a known contract with a non-trivial fallback it will not hit
          this case; further if it is a known contract with a trivial fallback, this comparison is mooted
          because we explicitly check if "to" with all known contracts with trivial fallbacks.

          In other words, the result of this comparison is only relevant if the "to" variable is determined by
          the smt to be an address that is *not* one of our known contracts in the scene.
         */
        val eoaCmp = TACKeyword.TMP(Tag.Bool, "eoaLkp!test").toUnique("1").at(callerIndex)

        /*
          The accumulator of comparing `toVar` with all the contract addresses that are known to have trivial fallback,
          (i.e., they reject funds). If true, we should revert. Or'd with the result of the eoaCmp.
         */
        var deciderIt = TACKeyword.TMP(Tag.Bool, "!useRevert!Accum").toUnique("!").at(callerIndex)

        val extcodesize = TACKeyword.EXTCODESIZE.toVar()
        val vars = mutableSetOf(
            useRevertFlag,
            eoaLkp,
            deciderIt,
            eoaCmp,
            extcodesize
        )

        /**
         * A block we will fallthrough to, if none of the underlying contracts
         * in nonTrivialFallback matches callSumm.
         * This block tries to match [toVar] with one of the contracts with a trivial fallback
         * function, checks whether [toVar] is an externally
         * owned account, and reverts if one of those conditions hold.
         */
        val fallThroughBlock = mutableListOf<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = deciderIt,
                rhs = TACSymbol.False.asSym()
            )
        )
        for (c in scene.getContracts()) {
            /*
              The non-trivial fallback cases are treated separately below, when we construct the dispatch logic.
             */
            if (c.instanceId in nonTrivialFallback) {
                continue
            }
            /* precompiled contracts are not interesting.
                When we run with cache, they get included in the return value of [scene.getContracts] */
            if (c.instanceId in scene.getContractUniverse().addresses) {
                continue
            }

            /**
             * Here, we try to match [toVar] with one of the contracts with a trivial fallback
             * function.
             */
            val cmp = TACKeyword.TMP(Tag.Bool, "!useRevert!cmp").toUnique("!").at(callerIndex)
            val outDecider = TACKeyword.TMP(Tag.Bool, "!useRevert!Accum").toUnique("!").at(callerIndex)
            vars.add(cmp)
            vars.add(outDecider)
            fallThroughBlock.addAll(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = cmp,
                    rhs = TACExpr.BinRel.Eq(
                        (c.addressSym as TACSymbol).asSym(),
                        callSumm.toVar.asSym()
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = outDecider,
                    rhs = TACExpr.BinBoolOp.LOr(listOf(
                        deciderIt.asSym(),
                        cmp.asSym()
                    ))
                )
            ))
            deciderIt = outDecider
        }
        /*
         Look up the address to see if it has a bigger than 0 codesize.
         If yes (which means it's not an EOA), or if it is one of the known trivial fallback contracts (the or combination with deciderIt, which represents
         if the target address must be one of our trivial fallback contracts), then we use the revert block summary.
         */
        fallThroughBlock.addAll(listOf(
            TACCmd.Simple.AssigningCmd.WordLoad(
                lhs = eoaLkp,
                base = extcodesize,
                loc = callSumm.toVar
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                eoaCmp,
                rhs = TACExpr.BinRel.Gt(
                    eoaLkp.asSym(),
                    TACSymbol.lift(0).asSym()
                )
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                useRevertFlag,
                rhs = TACExpr.BinBoolOp.LOr(listOf(
                    deciderIt.asSym(),
                    eoaCmp.asSym()
                ))
            ),
            TACCmd.Simple.JumpiCmd(
                cond = useRevertFlag,
                dst = revertBlock,
                elseDst = acceptBlock
            )
        ))

        if (callSumm.toVar is TACSymbol.Var) {
            vars.add(callSumm.toVar)
        }

        /* elseBlock is used to construct the dispatch mechanism over all the contracts, to check
           whether they match callSumm. */
        var elseBlock = patching.addBlock(
            summ.ptr.block,
            CommandWithRequiredDecls(
                fallThroughBlock,
                vars
            )
        )

        val callers = getCallersAtPointer(summ.ptr)
        for ((id, _) in nonTrivialFallback) {
            val cmd = if (callers.count {
                it == MethodRef(
                    contractId = id,
                    attr = MethodAttribute.Unique.Fallback,
                    sigHash = null
                )
            } > Config.SummaryRecursionLimit.get()) {
                if (Config.SummaryRecursionLimit.get() == 0 || !Config.OptimisticSummaryRecursion.get()) {
                    TACCmd.Simple.AssertCmd(
                        o = TACSymbol.False,
                        "When inlining a fallback function, the function was already found on the stack. " +
                            "Consider disabling optimistic fallback mode (${Config.OptimisticFallback.userFacingName()}), or alternatively, " +
                            "enable ${Config.OptimisticSummaryRecursion.userFacingName()} and set ${Config.SummaryRecursionLimit.userFacingName()} to a value >= 1."
                    )
                } else {
                    TACCmd.Simple.AssumeCmd(
                        cond = TACSymbol.False,
                        "recursion limit pruning"
                    )
                }
            } else {
                val resolvedSumm = callSumm.copy(
                    callTarget = setOf(CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(id))
                )
                TACCmd.Simple.SummaryCmd(
                    summ = resolvedSumm,
                    meta = MetaMap()
                )
            }
            val cmp = TACKeyword.TMP(Tag.Bool, "!fallbackCmp").toUnique("!").at(callerIndex)
            val dispatchBlock = patching.addBlock(summ.ptr.block, listOf(
                cmd,
                TACCmd.Simple.AnnotationCmd(SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                    Summarization.AppliedSummary.Config.OptimisticFallback,
                    callSumm.summaryId
                )),
                TACCmd.Simple.JumpCmd(succ)
            ))
            elseBlock = patching.addBlock(summ.ptr.block, CommandWithRequiredDecls(listOf(
                /* If toVar is determined "at runtime" to be equal to this contract address that has a non-trivial
                   fallback, take this branch, which leads to a (now resolved) call summary. This will be
                   inlined by the outer summarization loop.
                 */
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = cmp,
                    rhs = TACExpr.BinRel.Eq(
                        (scene.getContract(id).addressSym as TACSymbol).asSym(),
                        callSumm.toVar.asSym()
                    )
                ),
                TACCmd.Simple.JumpiCmd(
                    cond = cmp,
                    dst = dispatchBlock,
                    elseDst = elseBlock
                )
            ), cmp))
        }
        val startBlock = patching.addBlock(
            summ.ptr.block,
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AnnotationCmd(
                    SummaryStack.START_EXTERNAL_SUMMARY,
                    SummaryStack.SummaryStart.External(
                        callNode = callSumm,
                        callResolutionTableInfo = CallResolutionTableSummaryInfo.FallbackDispatched(
                            nonTrivialFallback.values.associate { method ->
                                val c = method.getContainingContract()
                                c.name to (c as? IContractWithSource)?.src?.sourceSegment()?.range
                            }
                        ),
                        appliedSummary = Summarization.AppliedSummary.Config.OptimisticFallback
                    )
                ),
                TACCmd.Simple.JumpCmd(elseBlock)
            ))
        )
        patching.replaceCommand(summ.ptr, listOf(TACCmd.Simple.JumpCmd(startBlock)))
    }

    /**
     * Context indicating the name of synthetic variables which hold the callee of some call-site which
     * is itself returned from some other internal/external call.
     *
     * The method to identify which return value depends on whether we are considering an external/internal call.
     * This parametricity is represented by the [ReturnPositionT] type parameter, which is used to represent the
     * return positions from a function. For external functions, this is a [BigInteger], which represents the static
     * offset in the return buffer holding the return value. For internal functions, this is [Int], representing the [analysis.ip.InternalFuncRet.offset]
     * of the return value.
     */
    class LinkingState<@Treapable ReturnPositionT> {
        /**
         * (id, offset) => v
         * indicates that the value at return position `offset` from the call with external/internal summary
         * `id` should also be placed in `v`, as that variable is known to be a callee contract for some other call-site.
         *
         * NB that this is OPTIONAL and best effort, if `id` is summarized with `NONDET` we never assign to `v`.
         */
        private val linkState = treapMapBuilderOf<Pair<Int, ReturnPositionT>, TACSymbol.Var>()

        fun setupLinkResolution(
            summaryId: Int,
            offset: ReturnPositionT
        ) {
            linkState.computeIfAbsent(summaryId to offset) {
                TACKeyword.TMP(Tag.Bit256, "!linkVar").toUnique("!")
            }
        }

        fun getLinkOutput(
            summaryId: Int,
            offset: ReturnPositionT
        ) : TACSymbol.Var? {
            return linkState.get(summaryId to offset)
        }

        fun getLinkOutputFor(summaryId: Int): Map<ReturnPositionT, TACSymbol.Var> {
            return linkState.entries.associateNotNull {
                if(it.key.first != summaryId) {
                    return@associateNotNull null
                }
                it.key.second to it.value
            }
        }
    }

    /**
     * There are few dispatcher flows that reuse this flow here and need to decide which methods to inline. The flow _slightly_ diverges.
     * The [analysis.icfg.Summarization.AppliedSummary.LateInliningDispatcher] is allowed to call several methods with different sighashes.
     * The dispatcher summary `_.foo() => DISPATCHER(...)` ([com.certora.certoraprover.cvl.CallSummary.Dispatcher])
     * or [analysis.icfg.Summarization.AppliedSummary.Config.AutoDispatcher] strictly only allow
     * a single sighash to be resolved. (A dispatcher summary can only be applied when the sighash is resolved uniquely.)
     *
     * For the [analysis.icfg.Summarization.AppliedSummary.LateInliningDispatcher] this methods also filters
     * out methods for which the inputs don't match (using [instrumentation.calls.CalldataEncoding.checkInputSizeForNonArgsOnly] and
     * [instrumentation.calls.CalldataEncoding.checkInputSizeForArgsOnly]).
     */
    private fun getCalleeMethodsForDispatcherSummary(scene: IScene, callSumm: CallSummary, isLateInlininedDispatcher: Boolean): List<ITACMethod> {
        check(isLateInlininedDispatcher || callSumm.sigResolution.size == 1) {
            "Expected the sighash resolution of $callSumm to be of size 1 (got ${callSumm.sigResolution.size})"
        }
        check(callSumm.sigResolution.none { it == null } ) {
            "We don't support dispatcher summary on the fallback function"
        }
        val resolution = (callSumm.callTarget.map {
            (it as? CallGraphBuilder.CalledContract.CreatedReference.Resolved)?.tgtConntractId
        })
        return callSumm.sigResolution.flatMap { sig -> scene.getMethods(sig!!) }.let { callees ->
            if (null in resolution) {
                callees
            } else {
                callees.filter {
                    (it.getContainingContract() as? IClonedContract)?.sourceContractId in resolution
                }
            }
        }.letIf(isLateInlininedDispatcher){ callees -> callees.filter { it.matchesCallDataEncoding(callSumm.callConvention.input) }}
    }

    /**
     * Depending on the caller and the [appliedSummary] type, inlines the callees
     * for the DISPATCHER summary, for the [analysis.icfg.Summarization.AppliedSummary.Config.AutoDispatcher]
     * or for the [Summarization.AppliedSummary.LateInliningDispatcher]
     */
    fun inlineDispatcherSummary(
        scene: IScene,
        caller: BigInteger,
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        summ: SpecCallSummary.Dispatcher,
        appliedSummary: Summarization.AppliedSummary,
        summAppReason: SummaryApplicationReason,
        getCallersAtPointer: InlinedMethodCallStack
    ) {
        val callSumm = where.cmd.summ
        require(callSumm is CallSummary) { "Expected $callSumm to be a ${CallSummary::javaClass.name}" }
        if(callSumm.callType == TACCallType.DELEGATE) {
            throw CertoraException(
                CertoraErrorType.UNSUPPORTED_SUMMARIZATION,
                msg = "Requested to summarize a delegatecall with dispatcher: this is unsupported"
            )
        }
        val defaultHavocType =
            Havocer.resolveHavocType(scene, caller, callSumm, SpecCallSummary.HavocSummary.Auto(SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY))

        val calleeMethods = getCalleeMethodsForDispatcherSummary(scene, callSumm, appliedSummary is Summarization.AppliedSummary.LateInliningDispatcher).toMutableList()
        if (summ.useFallback) {
            calleeMethods.addAll(scene.getContracts()
                .filter { contract ->
                    // There's no point in inlining the fallback of a contract that actually has an implementation of
                    // the called method since for that callee we'll always choose the method itself anyway.
                    calleeMethods.none { it.getContainingContract().instanceId == contract.instanceId }
                }
                .mapNotNull { contract ->
                    contract.getMethodByUniqueAttribute(MethodAttribute.Unique.Fallback)
                }
            )
        }

        val callers = getCallersAtPointer(where.ptr)
        instrumentDispatcher(
            patching,
            where,
            summ.optimistic,
            caller,
            scene,
            defaultHavocType,
            callSumm,
            appliedSummary,
            calleeMethods,
            callers,
            summAppReason,
        )
    }

    private fun instrumentDispatcher(
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        optimistic: Boolean,
        caller: BigInteger,
        scene: IScene,
        defaultHavocType: Havocer.HavocType,
        callSumm: CallSummary,
        appliedSummary: Summarization.AppliedSummary,
        callees: Collection<ITACMethod>,
        callers: List<MethodRef>,
        summAppReason: SummaryApplicationReason,
    ) {
        val existingInvocationCounts = callees.associateWith { callee ->
            callers.count {
                it == callee.toRef()
            }
        }
        val tmp = TACKeyword.TMP(Tag.Bool, "dispatchCmp")
        val inlineSuccessor = patching.splitBlockAfter(where.ptr)
        // Create and add the last else block to the program. It is bound to it's successor,
        // but at the moment no block will point here
        var elseBlock = if (!optimistic) {
            val noDispatchMatchHavoc = Havocer.generateHavocBlock(
                caller,
                scene,
                defaultHavocType,
                callSumm,
                where.wrapped
            )
            patching.addBlock(
                where.ptr.block,
                CommandWithRequiredDecls(
                    noDispatchMatchHavoc.cmds +
                        SnippetCmd.CVLSnippetCmd.DispatcherSummaryDefault(appliedSummary, defaultHavocType).toAnnotation() +
                        TACCmd.Simple.AnnotationCmd(
                        SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                            appliedSummary,
                            callSumm.summaryId
                        )
                    ) + TACCmd.Simple.JumpCmd(
                        dst = inlineSuccessor
                    ), noDispatchMatchHavoc.varDecls
                )
            )
        } else {
            val t = TACKeyword.TMP(Tag.Bool, "rip")
            val optimisticBlock = mutableListOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    t,
                    TACSymbol.False
                ),
                TACCmd.Simple.AssumeCmd(t, "optimistic dispatcher"),
                TACCmd.Simple.AnnotationCmd(
                    SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                        appliedSummary,
                        callSumm.summaryId
                    )
                ),
                // Even though we're just assumeing false, link it to the rest of the graph to prevent malformed TAC dumps
                TACCmd.Simple.JumpCmd(
                    dst = inlineSuccessor
                )
            )
            patching.addBlock(
                where.ptr.block, optimisticBlock
            ).also {
                patching.addVarDecl(t)
            }
        }

        fun CallSummary.getSighashExpr() = TACExpr.Select(this.inBase.asSym(), this.inOffset.asSym())

        fun extendDispatchChain(m: ITACMethod) {
                check(m.attribute == MethodAttribute.Unique.Fallback || callSumm.sigResolution.singleOrNull()?.let { it == m.sigHash!!.n } != false) {
                    "If call summary has a resolved sigResolution, it must match callees sigResolution. " +
                        "Callee: ${m.getContainingContract().name}.${m.soliditySignature}"
                }
                val summWithResolution = callSumm.copy(
                    callTarget = setOf(CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(
                            m.getContainingContract().instanceId
                        )),
                    sigResolution = setOf(m.sigHash?.n),
                )
                val callBlock = if (existingInvocationCounts[m]?.let {
                        it > Config.SummaryRecursionLimit.get()
                    } == true) {
                    if (Config.SummaryRecursionLimit.get() == 0 || !Config.OptimisticSummaryRecursion.get()) {
                        val msg = "When summarizing a call with dispatcher, " +
                            "it was already on the stack:" +
                            callers.map { call ->
                                call.toString(scene)
                            }
                                .joinToString(", ") +
                            ". Consider either (1) removing its dispatcher summary; " +
                            "or (2) enable ${Config.OptimisticSummaryRecursion.userFacingName()} and set ${Config.SummaryRecursionLimit.userFacingName()} to a value >= 1."
                        listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                msg
                            )
                        )
                    } else {
                        listOf(
                            TACCmd.Simple.AssumeCmd(
                                cond = TACSymbol.False,
                                "dispatcher recursion pruning"
                            )
                        )
                    }
                } else {
                    logger.debug { "Dispatch summary of ${m.getContainingContract()}.${m.soliditySignature} (sighash: ${m.sigHash?.n})" }
                    listOf(
                        TACCmd.Simple.SummaryCmd(
                            summ = summWithResolution,
                            meta = where.cmd.meta
                        ),
                    )
                } + listOf(
                    TACCmd.Simple.AnnotationCmd(
                        SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(
                            appliedSummary,
                            callSumm.summaryId
                        )
                    ),
                    TACCmd.Simple.JumpCmd(
                        dst = inlineSuccessor
                    )
                )
                // Block calling the callee when possible. It points to the next command, but is not yet pointed to.
                val callBlockId = patching.addBlock(where.ptr.block, callBlock)
                val jumpVar: TACSymbol.Var
                val patchingBlock = MutableCommandWithRequiredDecls<TACCmd.Simple>()
                val contractAddress = m.getContainingContract().addressSym as TACSymbol
                val funcCond: TACExpr = TACExpr.BinRel.Eq(
                    callSumm.toVar.asSym(),
                    contractAddress.asSym()
                ).letIf(callSumm.sigResolution.size != 1 && m.sigHash != null) {
                    val firstWord = TACExpr.BinOp.ShiftRightLogical(
                        callSumm.getSighashExpr(),
                        (EVM_BITWIDTH256 - (DEFAULT_SIGHASH_SIZE.toInt() * EVM_BYTE_SIZE.toInt())).asTACExpr
                    )
                    TACExpr.BinBoolOp.LAnd(
                        it,
                        TACExpr.BinRel.Eq(firstWord, m.sigHash!!.n.asTACExpr)
                    )
                }
                patchingBlock.extend(
                    CommandWithRequiredDecls(
                        listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            tmp,
                            funcCond
                        )),
                        setOfNotNull(tmp, contractAddress as? TACSymbol.Var)
                    )
                )
                val clonedContract = m.getContainingContract() as? IClonedContract
                if (clonedContract != null) {
                    val readCount =
                        patching.freshTemp(Tag.Bit256, suffix = "!createCount", callId = where.ptr.block.getCallId())
                    val readCompare =
                        patching.freshTemp(Tag.Bool, suffix = "!createCmp", callId = where.ptr.block.getCallId())
                    val conj = patching.freshTemp(Tag.Bool, suffix = "!conj", callId = where.ptr.block.getCallId())
                    patchingBlock.extend(
                        listOf(
                            TACCmd.Simple.AssigningCmd.WordLoad(
                                lhs = readCount,
                                base = TACKeyword.CONTRACT_COUNT.toVar(),
                                loc = clonedContract.sourceContractId.asTACSymbol()
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = readCompare,
                                rhs = TACExpr.BinRel.Lt(
                                    o1 = clonedContract.createdContractInstance.asTACSymbol().asSym(),
                                    o2 = readCount.asSym()
                                )
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = conj,
                                rhs = TACExpr.BinBoolOp.LAnd(
                                    readCompare.asSym(),
                                    tmp.asSym()
                                )
                            )
                        ), TACKeyword.CONTRACT_COUNT.toVar()
                    )
                    jumpVar = conj
                } else {
                    jumpVar = tmp
                }
                patchingBlock.extend(
                    TACCmd.Simple.JumpiCmd(
                        cond = jumpVar,
                        dst = callBlockId,
                        elseDst = elseBlock
                    ), jumpVar
                )
                elseBlock = patching.addBlock(where.ptr.block, patchingBlock.toCommandWithRequiredDecls())
        }

        // Dispatcher will always have a single sighash and will skip additional `if`s
        // Dispatch List requires to first choose a sighash, and then do "Dispatcher"
        val (fallbacks, nonFallbackMethods) = callees.partition { it.sigHash == null }
        check(fallbacks.all { it.attribute == MethodAttribute.Unique.Fallback }) {
            "Expecting only resolved callees. " +
                "Found ${fallbacks.joinToString(", ") { it.name }} without a sighash."
        }

        // We're constructing the dispatch list "backwards", and we want to have the fallbacks only in case non of the "real"
        // method sighashes matched, so extend the chain first with them and only then with the rest of the methods.
        fallbacks.forEach { fallback ->
            extendDispatchChain(fallback)
        }
        nonFallbackMethods.forEach { m ->
            extendDispatchChain(m)
        }

        val annotStartBlock = patching.addBlock(
            where.ptr.block,
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AnnotationCmd(
                        SummaryStack.START_EXTERNAL_SUMMARY,
                        SummaryStack.SummaryStart.External(
                            callNode = callSumm,
                            callResolutionTableInfo = CallResolutionTableSummaryInfo.DispatchedInfo(
                                summAppReason,
                                callees.map {
                                    CallResolutionTableSummaryInfo.WithDispatchedCalleesInfo.PossibleCallee(it)
                                },
                                if (!optimistic) {
                                    CallResolutionTableSummaryInfo.HavocInfo.NonOptimisticDispatcherHavoc(
                                        havocType = defaultHavocType,
                                        applicationReason = summAppReason
                                    )
                                } else {
                                    null
                                }
                            ),
                            appliedSummary = appliedSummary
                        )
                    ),
                    TACCmd.Simple.JumpCmd(dst = elseBlock)
                )
            )
        )
        patching.replaceCommand(
            where.ptr, listOf(
                TACCmd.Simple.JumpCmd(dst = annotStartBlock)
            ), treapSetOf(annotStartBlock)
        )
    }

    // here we define some private functions, which are used for sharing code between the [inlineSummaryFromConfig] and [inlineSummaryFromCVL] functions

    private fun basicSuccessCode(where: LTACCmdView<TACCmd.Simple.SummaryCmd>, outSize: TACSymbol?) : CommandWithRequiredDecls<TACCmd.Simple>{
        val callSumm = where.cmd.summ as CallSummary
        val rc = EthereumVariables.rc.at(callIndex = where.ptr.block.calleeIdx)
        val returnSizeVar = TACKeyword.RETURN_SIZE.toVar(callIndex = callSumm.outBase.callIndex)
        val commands = mutableListOf<TACCmd.Simple>()
        /* If the outsize is a priori known at summary generation time, set it here
          Otherwise, for dynamic types (as can happen with exp summaries) they are responsible for setting it themselves
         */
        if(outSize != null) {
            commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                returnSizeVar,
                outSize
            ))
        }
        commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
            rc, TACSymbol.lift(1)
        ))
        return commands.withDecls(returnSizeVar, rc)

    }

    private data class SummaryToInline(
        val appliedSummary: Summarization.AppliedSummary,
        val summInfo: CallResolutionTableSummaryInfo,
        val code: CoreTACProgram
    ) {
        val summ: SpecCallSummary
            get() = appliedSummary.specCallSumm

        constructor(
            appliedSummary: Summarization.AppliedSummary,
            summInfo: CallResolutionTableSummaryInfo,
            cmdsWithRequiredDecls: CommandWithRequiredDecls<TACCmd.Simple>,
            where: LTACCmdView<TACCmd.Simple.SummaryCmd>
        ) : this(appliedSummary, summInfo, cmdsWithRequiredDecls.toCode(where.ptr.block.getCallId()))
    }

    /**
     * Returns the "default" summary ([SpecCallSummary.HavocSummary.Auto])
     * that is applied if the summary declared by the user cannot be applied due to an error or failure.
     * @param errorMsg message explaining the error/failure leading to the application of this "default" summary
     */
    private fun failedToSummarizeSummaryWithErrorMsg(
        errorMsg: String,
        caller: BigInteger,
        callSumm: CallSummary,
        scene: IScene,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>
    ): SummaryToInline {
        Logger.alwaysError(errorMsg)
        val autoHavocSumm = SpecCallSummary.HavocSummary.Auto(SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY)
        val defaultHavocType = Havocer.resolveHavocType(scene, caller, callSumm, autoHavocSumm)
        return SummaryToInline(
            Summarization.AppliedSummary.Prover(autoHavocSumm),
            CallResolutionTableSummaryInfo.HavocInfo.SummarizationFailureHavoc(
                defaultHavocType,
                errorMsg
            ),
            Havocer.generateHavocBlock(
                caller,
                scene,
                defaultHavocType,
                callSumm,
                where.wrapped
            ),
            where
        )
    }

    /**
     * Given a map of [summaries] for summaries declared by the user, a (single) [sigHash] for a call, a (potentially) nullable
     * callee contract ID, return the the set of summaries that match this call. [onExactSummaryMiss] is called
     * when an exact summary *would* apply except we couldn't resolve the callee. [nameLookup] resolves a contract ID to
     * a string. Returning null is safe, but will mean that no Catch all or exact summaries will match. Currently, this
     * callback will only be called with [hostContractId] as a non-null argument, but callers should take care
     * to handle all possible arugments in the callback.
     */
    fun getExplicitSummariesForInvocation(
        summaries: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>,
        sigHash: BigInteger?,
        hostContractId: BigInteger?,
        onExactSummaryMiss: (CVL.ExternalExact) -> Unit,
        nameLookup: (BigInteger) -> String?
    ) : Collection<Summarization.AppliedSummary.FromUserInput> {
        return summaries.entries.filter { (sig, _) ->
            when (sig) {
                is CVL.ExternalExact -> {
                    if (sig.sighashInt.n != sigHash) {
                        return@filter false
                    }
                    if (hostContractId == null) {
                        onExactSummaryMiss(sig)
                        false
                    } else {
                        nameLookup(hostContractId) == sig.signature.qualifiedMethodName.host.name
                    }
                }

                is CVL.ExternalWildcard -> sig.sighashInt.n == sigHash
                is CVL.ExternalAnyInContract -> {
                    hostContractId?.let {
                        nameLookup(it) == sig.hostContract.name
                    } == true
                }

                CVL.ExternalUnresolved -> true
            }
        }.map {
            Summarization.AppliedSummary.MethodsBlock(
                specCallSumm = it.value,
                summarizedMethod = it.key
            )
        }
    }

    private fun failedToSummarizeCond(specCallSumm: SpecCallSummary, callSumm: CallSummary): Boolean {
        return specCallSumm !is SpecCallSummary.HavocSummary && (callSumm.outSize !is TACSymbol.Const || callSumm.outSize.value.mod(
            0x20.toBigInteger()
        ) != BigInteger.ZERO || callSumm.outSize.value > Int.MAX_VALUE.toBigInteger())
    }

    private fun returnSizeFailureMsg(callSumm: CallSummary, specCallSumm: SpecCallSummary) = "failed to apply '${
        specCallSumm.toUIString()
    }' summary because the return size is not a multiple of 32 bytes, or is too big: ${callSumm.outSize}"


    /**
     * A shared class which is threaded through summarization to where delegate candidates are selected.
     */
    private class DelegateDecisionManager(val stack: InlinedMethodCallStack, val inliningDecisionManager: InliningDecisionManager.PostStorageAnalysis) {
        /**
         * Returns whether [callee] could be inlined for the [CallSummary] at [where]. This is called to exclude potential
         * callees that definitely cannot be inlined and therefore should not be included in the delegate switch.
         *
         * This ultimately delegates (haha) to the [inliningDecisionManager] manager's [InliningDecisionManager.shouldInline];
         * the information about the value of `thisAtCall` is extract from the call stack (reported by [stack]) using the
         * [analysis.icfg.InterContractCallResolver.ThisInference.Infer]. Recall that this inference finds the "most recent"
         * direct call in the callstack, and takes that to be the value of this.
         */
        fun shouldInline(where: LTACCmdView<TACCmd.Simple.SummaryCmd>, callee: ITACMethod): Boolean {
            return stack.activationRecordsAt(where.ptr).let {
                InterContractCallResolver.ThisInference.Infer.inferAt(it)
            }.let { thisAtCall ->
                inliningDecisionManager.shouldInline(thisAtCall, where.cmd.summ as CallSummary, callee as TACMethod)
            }
        }

    }

    /**
     * Inlines [where] due to a cli configuration (see [SummaryApplicationReason.Cli])
     * with caller [caller] in the patching program [patching].
     * Currently used only for the [Config.SummarizeExtLibraryCallsAsNonDetPreLinking] cli option.
     *
     * @return a set of all calls that have been added (which should be processed and inlined), false otherwise.
     */
    fun inlineSummaryFromConfig(
        scene: IScene,
        caller: BigInteger,
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        appliedSummary: Summarization.AppliedSummary.Config,
        methodCallStack: InlinedMethodCallStack
    ) {
        val callSumm = where.cmd.summ
        require(callSumm is CallSummary) { "Expected $callSumm to be a ${CallSummary::javaClass.name}" }

        when (appliedSummary) {
            is Summarization.AppliedSummary.AutoNondetSummary -> {
                val specCallSumm = appliedSummary.specCallSumm
                val havocType = Havocer.resolveHavocType(scene, caller, callSumm, specCallSumm)
                val callResSummAppInfo =
                    CallResolutionTableSummaryInfo.HavocInfo.HavocSelectedOnCli(
                        havocType,
                        SummaryApplicationReason.Cli(appliedSummary.configFlag.name), // xxx check which options reach here, update to userFacingName
                        "Nondet summary is applied for ${appliedSummary.context}"
                    )
                inlineHavoc(patching, scene, caller, where, specCallSumm, havocType, appliedSummary, callResSummAppInfo)
            }


            is Summarization.AppliedSummary.Config.DispatchOnCreate -> {
                return inlineDispatcherSummary(
                    scene,
                    caller,
                    patching,
                    where,
                    appliedSummary.specCallSumm,
                    Summarization.AppliedSummary.Config.DispatchOnCreate,
                    /* This flag's name is passed hard-coded, because we want to pass the Python flag's name
                    (which customers are familiar with), not the Java flag's name.
                    The mapping between Python flags and their corresponding Java flags
                    appears in certoraContext.py, but not in our Kotlin code. */
                    SummaryApplicationReason.Cli(Config.DispatchOnCreated.pythonName!!),
                    methodCallStack
                )
            }

            is Summarization.AppliedSummary.Config.AutoDispatcher -> {
                // There are 2 options: Either there are methods to inline here, in which case we want to use optimistic
                // dispatcher, or there aren't any methods to inline and then we want to havoc (i.e. non-optimistic).
                val optimistic = getCalleeMethodsForDispatcherSummary(scene, where.cmd.summ as CallSummary, false).isNotEmpty()
                return inlineDispatcherSummary(
                    scene,
                    caller,
                    patching,
                    where,
                    appliedSummary.specCallSumm.copy(optimistic = optimistic),
                    Summarization.AppliedSummary.Config.AutoDispatcher,
                    SummaryApplicationReason.SpecialReason("When auto mode is enabled, setting all unresolved calls to automatically dispatch"),
                    methodCallStack
                )
            }

            is Summarization.AppliedSummary.Config.OptimisticFallback -> {
                throw IllegalStateException("inlineSummaryFromConfig does not support the AppliedSummary ${appliedSummary::class}")
            }
        }
    }

    private fun inlineDispatchListSummary(
        scene: IScene,
        caller: BigInteger,
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        appliedSummary: Summarization.AppliedSummary.MethodsBlock,
        decisionManager: DelegateDecisionManager,
        getCallersAtPointer: InlinedMethodCallStack
    ) {
        val callSumm = where.cmd.summ
        require(callSumm is CallSummary) { "Expected $callSumm to be a ${CallSummary::javaClass.name}" }
        require(appliedSummary.specCallSumm is SpecCallSummary.DispatchList) { "Expecting $appliedSummary to be " +
            "a ${SpecCallSummary.DispatchList::javaClass.name}, but got ${appliedSummary.specCallSumm::javaClass.name}" }

        if (!Config.EnabledCopyLoopRewrites.get()) {
            val error = IllegalStateException("`-enableCopyLoopRewrites' flag must be enabled when using dispatch list summarization.")
            Logger.alwaysError("Bad configuration used in presence of unresolved calls summarization", error)
            throw error
        }
        logger.info { "Applying dispatch list summarization to unresolved call @ ${callSumm.origCallcore?.metaSrcInfo}" }

        val defaultHavocType =
            Havocer.resolveHavocType(scene, caller, callSumm, appliedSummary.specCallSumm.default)

        val resolution = (callSumm.callTarget.map { (it as? CallGraphBuilder.CalledContract.CreatedReference.Resolved)?.tgtConntractId})
        val callees = resolution.flatMapToSet { res ->
            appliedSummary.specCallSumm.getMethods(scene, callSumm.sigResolution, res)
        }
            .letIf(callSumm.callType == TACCallType.DELEGATE) { methods ->
                methods.filter { m ->
                    decisionManager.shouldInline(where, m)
                }.also { kept ->
                    val source = callSumm.origCallcore?.metaSrcInfo?.getSourceCode() ?: "unknown source"
                    val callingContract = scene.getContract(caller).name
                    if (kept.isEmpty()) {
                        CVLWarningLogger.generalWarning("Dispatch list summary for delegate call only considers " +
                            "methods from the calling contract. A delegate call summarized at $source with a dispatch list" +
                            "summary will always run the default case, as no methods in the list match the calling contract ($callingContract).")
                    } else if (methods.size != kept.size) {
                        CVLWarningLogger.generalWarning("Dispatch list summary for delegate call only considers " +
                            "methods from the calling contract. A delegate call at $source, summarized with a dispatch list, " +
                            "will consider only dispatch list methods from the calling contract ($callingContract).")
                    }
                }
                // If we get unexpected types here we will either crash later on bad input, or, what is more likely, everything will work correctly
            }.filter { it.matchesCallDataEncoding(callSumm.callConvention.input) }
            .filter { appliedSummary.specCallSumm.useFallback || it.attribute !is MethodAttribute.Unique.Fallback }
        val callers = getCallersAtPointer(where.ptr)

        instrumentDispatcher(
            patching,
            where,
            optimistic = appliedSummary.specCallSumm.optimistic,
            caller,
            scene,
            defaultHavocType,
            callSumm,
            appliedSummary,
            callees,
            callers,
            SummaryApplicationReason.Spec.reasonFor(appliedSummary.specCallSumm, null),
        )
    }

    private fun ITACMethod.matchesCallDataEncoding(callInput: CallInput): Boolean =
        (this.calldataEncoding as? CalldataEncoding)?.let {
            it.checkInputSizeForNonArgsOnly(callInput) && it.checkInputSizeForArgsOnly(callInput)
        } ?: true


    private operator fun InlinedMethodCallStack.invoke(c: CmdPointer) = this.iterateUpCallersMethodOnly(c)

    /**
     * Inlines [where] due to a summary specified in the CVL (see [SummaryApplicationReason.Spec])
     * with caller [caller] in the patching program [patching].
     *
     * @return a set of all calls that have been added (which should be processed and inlined), false otherwise.
     */
    fun inlineSummaryFromCVL(
        scene: IScene,
        caller: BigInteger,
        enclosingProgram: CoreTACProgram,
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        appliedSummary: Summarization.AppliedSummary.MethodsBlock,
        cvlCompiler: CVLCompiler?,
        linkingState: ExternalLinkingState,
        inliningDecisionManager: InliningDecisionManager.PostStorageAnalysis,
        getCallersAtPointer: InlinedMethodCallStack,
    ) {

        val (specCallSumm, summSignature) = appliedSummary
        val callSumm = where.cmd.summ
        require (callSumm is CallSummary) { "Expected $callSumm to be a ${CallSummary::javaClass.name}" }

        val summSpecAppReason = SummaryApplicationReason.Spec.reasonFor(specCallSumm, summSignature.funcSignature)
        val basicSummarySuccessManagement by lazy {
            basicSuccessCode(where, callSumm.outSize.takeIf {
                specCallSumm !is SpecCallSummary.Exp // converters handle the returnsize encoding
            })
        }

        /**
         * [constSummaryGenerator] is expected to generate either [SpecCallSummary.Constant] or [SpecCallSummary.PerCalleeConstant] summaries.
         */
        fun generateConstSummToInline(
            summaryRange: Range,
            constSummaryGenerator: (sighash: BigInteger, summ: CallSummary, callerCallIndex: CallId) -> CommandWithRequiredDecls<TACCmd.Simple>,
        ): SummaryToInline {
            // We expect this check to hold when this function is called
            check(
                callSumm.outSize is TACSymbol.Const &&
                    callSumm.outSize.value.mod(0x20.toBigInteger()) == BigInteger.ZERO
            ) {
                "Expected the outSize of the CallSummary to be a multiple of 32 bytes (got ${callSumm.outSize})"
            }
            if (callSumm.outSize.value == BigInteger.ZERO) {
                CVTAlertReporter.reportAlert(
                    CVTAlertType.SUMMARIZATION,
                    CVTAlertSeverity.ERROR,
                    jumpToDefinition = summaryRange as? TreeViewLocation,
                    message = "Using a CONSTANT/PER_CALLEE_CONSTANT summary on a function that returns dynamically sized values can cause vacuity.",
                    hint = "Consider using the NONDET summary instead."
                )
            }
            return when (val sigHashRes = callSumm.sigResolution.singleOrNull()) {
                null -> {
                    val errorMsg =
                        "failed to apply '${specCallSumm.toUIString()}' summary " +
                            "since the Prover could not resolve the selector of the callee"
                    failedToSummarizeSummaryWithErrorMsg(errorMsg, caller, callSumm, scene, where)
                }

                else -> {
                    SummaryToInline(
                        appliedSummary,
                        CallResolutionTableSummaryInfo.DefaultInfo(summSpecAppReason),
                        constSummaryGenerator(sigHashRes, callSumm, where.ptr.block.calleeIdx).merge(basicSummarySuccessManagement),
                        where
                    )
                }
            }
        }

        val summToInline: SummaryToInline =
            if (failedToSummarizeCond(specCallSumm, callSumm)
            ) {
                failedToSummarizeSummaryWithErrorMsg(returnSizeFailureMsg(callSumm, specCallSumm), caller, callSumm, scene, where)
            } else {
                InsertOptimisticExtcodesize(enclosingProgram, callSumm, specCallSumm, patching)

                when (specCallSumm) {
                    is SpecCallSummary.HavocSummary -> {
                        val havocType = Havocer.resolveHavocType(scene, caller, callSumm, specCallSumm)
                        SummaryToInline(
                            appliedSummary,
                            CallResolutionTableSummaryInfo.HavocInfo.HavocDeclaredInSpec(
                                havocType,
                                summSpecAppReason
                            ),
                            Havocer.generateHavocBlock(
                                caller,
                                scene,
                                havocType,
                                callSumm,
                                where.wrapped
                            ),
                            where
                        )
                    }

                    is SpecCallSummary.Always -> {
                        if (callSumm.outSize is TACSymbol.Const &&
                            callSumm.outSize.value == ALWAYS_SUMMARY_OUT_BUFFER_SIZE.toBigInteger()
                        ) {
                            SummaryToInline(
                                appliedSummary,
                                CallResolutionTableSummaryInfo.DefaultInfo(summSpecAppReason),
                                generateAlwaysSummary(specCallSumm, callSumm, where.ptr.block.calleeIdx).merge(basicSummarySuccessManagement),
                                where
                            )
                        } else {
                            val errorMsg = "failed to apply '${
                                specCallSumm.toUIString()
                            }' summary since the return size is not $ALWAYS_SUMMARY_OUT_BUFFER_SIZE bytes"
                            failedToSummarizeSummaryWithErrorMsg(errorMsg, caller, callSumm, scene, where)
                        }
                    }

                    is SpecCallSummary.Constant -> {
                        generateConstSummToInline(specCallSumm.range, ::generateConstantSummary)
                    }

                    is SpecCallSummary.PerCalleeConstant -> {
                        generateConstSummToInline(specCallSumm.range, ::generatePerCalleeSummary)
                    }

                    is SpecCallSummary.Dispatcher -> {
                        return inlineDispatcherSummary(
                            scene,
                            caller,
                            patching,
                            where,
                            specCallSumm,
                            appliedSummary,
                            summSpecAppReason,
                            getCallersAtPointer
                        )
                    }

                    is SpecCallSummary.Exp -> {
                        check(cvlCompiler != null) {
                            "The cvlCompiler field should only be null in the case of the integrative checker, but " +
                                "there we don't expect expression summaries"
                        }
                        val generatedSummaryInitial = generateExternalExpSummary(
                            specCallSumm,
                            callSumm,
                            where.ptr,
                            scene,
                            summSignature,
                            cvlCompiler,
                            enclosingProgram,
                            linkingState
                        )
                        val generatedSummary = if (Config.CvlFunctionRevert.get()) {
                            // The RC of the summary depends on the whether the summary reverted or has thrown. To handle
                            // summaries that don't call into Solidity functions (so don't explicitly set lastReverted),
                            // we set them to false before entering the summary.
                            // We also remove any toplevel RevertConfluences in the summary,
                            // since we do not want the RevertPathGenerator to generate jumps here,
                            // but rather use the return code to return to the surrounding contract code solidity-style.
                            val graph = lazy { generatedSummaryInitial.analysisCache.graph }
                            val methodCallStack = lazy { InlinedMethodCallStack(graph.value, includeCVLFunctions = true) }
                            generatedSummaryInitial.patching { p ->
                                generatedSummaryInitial.ltacStream()
                                    .filter { it.maybeAnnotation(CVLInvocationCompiler.REVERT_CONFLUENCE) }
                                    .forEach { lc ->
                                        val currCaller = methodCallStack.value.currentCaller(lc.ptr)
                                        if(currCaller == null) {
                                            p.replaceCommand(lc.ptr, listOf())
                                        }
                                    }
                            }
                            .prependToBlock0(
                                EthereumVariables.setLastReverted(lastReverted = false)
                            )
                                .appendToSinks(
                                    CommandWithRequiredDecls(
                                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        lhs = EthereumVariables.rc.at(callIndex = where.wrapped.ptr.block.calleeIdx),
                                        rhs = cvlCompiler.exprFact {
                                            Ite(
                                                i = CVLKeywords.lastReverted.toVar().asSym(),
                                                t = TACSymbol.Zero.asSym(),
                                                e = TACSymbol.One.asSym()
                                            )
                                        }
                                    ), EthereumVariables.rc
                                ))
                        } else {
                            generatedSummaryInitial.appendToSinks(basicSummarySuccessManagement)
                        }

                        SummaryToInline(
                            appliedSummary,
                            CallResolutionTableSummaryInfo.DefaultInfo(summSpecAppReason),
                            generatedSummary
                        )
                    }

                    is SpecCallSummary.DispatchList ->
                        return inlineDispatchListSummary(
                            scene,
                            caller,
                            patching,
                            where,
                            appliedSummary,
                            decisionManager = DelegateDecisionManager(getCallersAtPointer, inliningDecisionManager),
                            getCallersAtPointer
                        )

                    is SpecCallSummary.Reroute -> throw CertoraInternalException(internalMsg = "Should have replaced Reroute summaries way earlier in the pipeline", type = CertoraInternalErrorType.SUMMARY_INLINING)
                }
            }
        inlineSummary(
            patching, where, SummaryBlock(
                code = summToInline.code, proc = Procedure(
                    procedureId = ProcedureId.Summary(
                        summaryType = summToInline.summ
                    ),
                    callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
                )
            ), summToInline.appliedSummary, summToInline.summInfo
        )
    }

    /**
     * In case of a call to a non-linked contract, we don't have any assumptions on the extcodesize
     * of the receiver contract. This object adds that assumption to prevent reverts due to
     * extcodesize == 0 which is not an interesting case when calling a function with @withrevert
     * Note - the weirdness of of this implementation (invokable anonymous object instead of a function) is to allow
     * the bookkeeping done with [instrumentedExcodesizeWordLoads] which needs to "live" across calls to the summarizer.
     */
    private object InsertOptimisticExtcodesize {
        // Keeps track of the extcodesize checks that were already instrumented, to avoid overwriting the replacements in
        // the patching program.
        private val instrumentedExcodesizeWordLoads = mutableListOf<LTACCmdView<TACCmd.Simple.AssigningCmd.WordLoad>>()

        // Note - the weirdness of of this implementation (invokable anonymous object instead f a function) is to allow
        // the bookkeeping done with [instrumentedExcodesizeWordLoads] which needs to "live" across calls to the summarizer.
        operator fun invoke(
            enclosingProgram: CoreTACProgram,
            callSumm: CallSummary,
            specCallSumm: SpecCallSummary.ExpressibleInCVL,
            patching: SimplePatchingProgram,
        ) {
            if (!(Config.OptimisticExtcodesize.get() &&
                    (specCallSumm is SpecCallSummary.Always ||
                        specCallSumm is SpecCallSummary.HavocSummary.Nondet ||
                        specCallSumm is SpecCallSummary.Constant ||
                        specCallSumm is SpecCallSummary.PerCalleeConstant ||
                        specCallSumm is SpecCallSummary.Exp ||
                        specCallSumm is SpecCallSummary.Dispatcher))
            ) {
                return
            }

            val extcodesizeLoads = enclosingProgram.parallelLtacStream().mapNotNull { ltacCmd ->
                ltacCmd.maybeNarrow<TACCmd.Simple.AssigningCmd.WordLoad>()
            }.filter { ltacCmd ->
                ltacCmd.cmd.base.meta.contains(IS_EXTCODESIZE)
            }.filter { ltacCmd ->
                // callSumm.toVar is supposed to be unique per callSumm (except unrolled loops, and in
                // that case we need to instrument all unrolled instances anyway).
                ltacCmd.cmd.loc == callSumm.toVar
            }.collect(Collectors.toList())

            synchronized(instrumentedExcodesizeWordLoads) {
                extcodesizeLoads.forEach { extcodesizeLoad ->
                    if (extcodesizeLoad in instrumentedExcodesizeWordLoads) {
                        return@forEach
                    }

                    val extcodesizeAssumptionVar = TACKeyword.TMP(Tag.Bool, "extcodesizeAssumption")
                    patching.insertAfter(
                        extcodesizeLoad.ptr, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                extcodesizeAssumptionVar,
                                TACExpr.BinRel.Gt(
                                    extcodesizeLoad.cmd.lhs.asSym(),
                                    TACSymbol.Zero.asSym()
                                )
                            ),
                            TACCmd.Simple.AssumeCmd(extcodesizeAssumptionVar, "extcodesizeAssumption")
                        )
                    )
                    patching.addVarDecl(extcodesizeAssumptionVar)
                }
                instrumentedExcodesizeWordLoads.addAll(extcodesizeLoads)
            }
        }
    }

    private fun perCallerNameGen(sigHash: BigInteger, idx: Int): TACSymbol.Var {
        return TACSymbol.Var(
            "certora!perSummary!$sigHash!$idx",
            Tag.WordMap
        )
    }

    private fun constantNameGen(sigHash: BigInteger, idx: Int): TACSymbol.Var {
        return TACSymbol.Var(
            "certora!constSummary!$sigHash!$idx",
            Tag.Bit256
        )
    }

    fun CommandWithRequiredDecls<TACCmd.Simple>.toCode(callId: CallId) =
        codeFromCommandWithVarDecls(Allocator.getNBId().copy(calleeIdx = callId), this, "summary")

    fun CommandWithRequiredDecls<TACCmd.Simple>.toCode(symbolTable: TACSymbolTable, callId: CallId) =
        codeFromListOfCommandsWithTypeInfo(
            Allocator.getNBId().copy(calleeIdx = callId),
            cmds,
            "summary",
            TACSymbolTable.withRequiredDecls(this).merge(symbolTable)
        )


    private fun generatePerCalleeSummary(sigHash: BigInteger, callSumm: CallSummary, callerId: CallId): CommandWithRequiredDecls<TACCmd.Simple> {
        check(callSumm.outSize is TACSymbol.Const) {
            "Expected outsize field of the CallSummary \"${callSumm}\" to be a " +
                "TACSymbol.Const but got ${callSumm.outSize}"
        }
        val numItems = callSumm.outSize.value.div(0x20.toBigInteger()).toInt()
        val items = mutableListOf<TACSymbol.Var>()
        val stores = mutableListOf<TACCmd.Simple>()
        val tmpRead = TACKeyword.TMP(Tag.Bit256, "summaryValue")

        val returnData = returndata.atSync(callerId)

        for (i in 0 until numItems) {
            val item = perCallerNameGen(sigHash, i)
            val loc = TACKeyword.TMP(Tag.Bit256, "offset")
            items.add(item)
            items.add(loc)
            stores.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    loc,
                    TACExpr.Vec.Add(
                        callSumm.outOffset.asSym(),
                        (i * 32).toBigInteger().asTACSymbol().asSym()
                    )
                )
            )
            stores.add(
                TACCmd.Simple.AssigningCmd.WordLoad(
                    loc = callSumm.toVar,
                    base = item,
                    lhs = tmpRead
                )
            )
            stores.add(
                TACCmd.Simple.AssigningCmd.ByteStore(
                    loc = loc,
                    base = callSumm.outBase,
                    value = tmpRead
                )
            )
            stores.add(
                TACCmd.Simple.AssigningCmd.ByteStore(
                    loc = (i * EVM_WORD_SIZE_INT).asTACSymbol(),
                    base = returnData,
                    value = tmpRead
                )
            )
        }
        return stores
            .withDecls(
                items, tmpRead, callSumm.outOffset, callSumm.outBase, callSumm.toVar, returnData
            )
    }

    private fun generateConstantSummary(hash: BigInteger, callSumm: CallSummary, callerId: CallId): CommandWithRequiredDecls<TACCmd.Simple> {
        check(callSumm.outSize is TACSymbol.Const)
        val numItems = callSumm.outSize.value.div(0x20.toBigInteger()).toInt()
        val items = mutableListOf<TACSymbol.Var>()
        val stores = mutableListOf<TACCmd.Simple>()
        for (i in 0 until numItems) {
            val item = constantNameGen(hash, i)
            val loc = TACKeyword.TMP(Tag.Bit256, "offset")
            items.add(item)
            items.add(loc)
            stores.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    loc,
                    TACExpr.Vec.Add(
                        callSumm.outOffset.asSym(),
                        (i * EVM_WORD_SIZE_INT).toBigInteger().asTACSymbol().asSym()
                    )
                )
            )
            stores.add(
                TACCmd.Simple.AssigningCmd.ByteStore(
                    base = TACKeyword.RETURNDATA.toVar(callerId),
                    loc = (i * EVM_WORD_SIZE_INT).toBigInteger().asTACSymbol(),
                    value = item
                )
            )
            stores.add(
                TACCmd.Simple.AssigningCmd.ByteStore(
                    base = callSumm.outBase,
                    value = item,
                    loc = loc
                )
            )
        }
        return stores
            .withDecls(items, callSumm.outBase, callSumm.outOffset, TACKeyword.RETURNDATA.toVar(callerId))
    }

    fun alwaysSummaryRhs(c: CVLExp): TACSymbol.Const =
        when (c) {
            is CVLExp.Constant.NumberLit -> CVLExpressionCompiler.compileNumberLit(c)
            is CVLExp.Constant.BoolLit -> CVLExpressionCompiler.compileBoolLit(c)
            is CVLExp.CastExpr -> {
                check(c.arg is CVLExp.Constant.NumberLit) {
                    "Typechecker should have enforced a number lit"
                }
                check(c.toCastType is CVLType.PureCVLType.Primitive.BytesK) {
                    "Typechecker should have enforced a cast to bytesK only"
                }
                CVLExpressionCompiler.compileConstCastExp(c)
            }
            else -> error(
                "ALWAYS summary should syntactically only be allowed to contain a boolean " +
                        "or number, not $c"
            )
        }

    private fun generateAlwaysSummary(
        summ: SpecCallSummary.Always,
        callSummary: CallSummary,
        callerIndex: CallId
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val returnSizeVariable = returnsize.at(callIndex = callerIndex)
        val value = alwaysSummaryRhs(summ.exp).let {
            /**
             * We need to ensure that [TACCmd.Simple.AssigningCmd.ByteStore] is always called with
             * a subtype of [Tag.Int] - changing the tag to [Tag.Bit256] in case it is a [Tag.Bool].
             * All other cases of alwaysSummaryRhs return [Tag.Bit256] or [Tag.Int].
             */
            if(it.tag == Tag.Bool){
                it.copy(tag = Tag.Bit256)
            } else{
                it
            }
        }
        val returnDataVar = returndata.atSync(callerIndex)
        val cmds = listOf(
            TACCmd.Simple.AssigningCmd.ByteStore(
                base = callSummary.outBase,
                loc = callSummary.outOffset,
                value = value
            ),
            TACCmd.Simple.AssigningCmd.ByteStore(
                base = returnDataVar,
                loc = Zero,
                value = value
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                returnSizeVariable,
                ALWAYS_SUMMARY_OUT_BUFFER_SIZE.toBigInteger().asTACSymbol()
            )
        )
        return cmds.withDecls(callSummary.outBase, callSummary.outSize, callSummary.outOffset, returnSizeVariable, returnDataVar)
    }

    private fun generateReturnDataCopy(
        c: CallSummary,
        returnData: TACSymbol.Var,
        returnSize: TACSymbol.Var,
        output: WithOutputPointer
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        val added = mutableSetOfNotNull(
            c.outOffset as? TACSymbol.Var,
            c.outSize as? TACSymbol.Var,
            returnData,
            output.outputPointer,
            c.outBase,
            returnSize
        )
        // if we store in memory in the reserved offsets (0, 32, 64), we must use the scalarized version of tacMem
        val loadFromReturnDataToMemory =
            if (c.outSize is TACSymbol.Const && c.outSize.value==32.toBigInteger()
                && c.outOffset is TACSymbol.Const && c.outOffset.value in setOf(BigInteger.ZERO, 32.toBigInteger(), 64.toBigInteger())) {

                // if we are in vyper, we should not scalarize freemem. How can we even know? Do a usual bytestore too
                val specificMem = when (c.outOffset.value) {
                    BigInteger.ZERO -> TACKeyword.MEM0.toVar(c.outBase.callIndex)
                    32.toBigInteger() -> TACKeyword.MEM32.toVar(c.outBase.callIndex)
                    64.toBigInteger() -> TACKeyword.MEM64.toVar(c.outBase.callIndex)
                    else -> `impossible!`
                }
                added.add(specificMem)
                listOf(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        specificMem,
                        BigInteger.ZERO.asTACSymbol(),
                        returnData
                    ),
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        c.outOffset,
                        specificMem,
                        c.outBase
                    )
                )
            } else {
                listOf(
                    TACCmd.Simple.ByteLongCopy(
                        dstBase = c.outBase,
                        srcBase = returnData,
                        length = c.outSize,
                        srcOffset = BigInteger.ZERO.asTACSymbol(),
                        dstOffset = c.outOffset,
                        meta = MetaMap()
                    )
                )
            }

        return CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = returnSize,
                rhs = output.outputPointer
            )
        ) + loadFromReturnDataToMemory, added)
    }

    /** @return the compiled body of [summary], suitable for replacing external call [summarizedCall] at [where] */
    private fun generateExternalExpSummary(
        summary: SpecCallSummary.Exp,
        summarizedCall: CallSummary,
        where: CmdPointer,
        scene: IScene,
        signature: CVL.SummarySignature,
        cvlCompiler: CVLCompiler,
        enclosingProgram: CoreTACProgram,
        linkingState: ExternalLinkingState
    ): CoreTACProgram {
        val compiledSummary = cvlCompiler.compileExpressionSummary(summary)

        val rets = (signature.signature as? MethodSignature)?.resType ?: summary.expectedType!!
        val assignOuts = encodeSummaryReturn(compiledSummary, summarizedCall, where.block.getCallId(), rets, linkingState, signature.funcSignature)
        val convertIns = decodeSummaryArguments(summary, summarizedCall, compiledSummary.callId)
        val callId = where.block.getCallId()
        val setup = CommandWithRequiredDecls.mergeMany(
            // calledContract and executingContract
            setupSpecialVarsForExpSumm(summarizedCall, callId),

            // scene contracts
            cvlCompiler.declareContractsAddressVars(),

            // with(env)
            summary.withClause?.let {
                setupEnvForExpSummary(
                    envVar = compiledSummary.allocatedTACSymbols.get(it.param.id),
                    callId = callId,
                    msgValue  = summarizedCall.valueVar.asSym(),
                    msgSender = if (summarizedCall.callType == TACCallType.DELEGATE) {
                            TACKeyword.CALLER.toVar(callId).asSym()
                        } else {
                            TACKeyword.ADDRESS.toVar(callId).asSym()
                        },
                )
            } ?: CommandWithRequiredDecls()
        )

        val generatedSummary = generateCVLExpressionSummary(
            compiledSummary.body,
            scene,
            assignOuts = assignOuts,
            convertIns = convertIns,
            setup = setup,
            enclosingProgram,
            where,
            compiledSummary.toString()
        )

        return generatedSummary
    }

    /**
     * @return a command to set `calledContract` to the correct value: caller's address for library calls, and [call]'s
     * receiver otherwise, plus one to set `executingContract` to the caller
     *
     * @param block the CallID where the summary will be inlined
     */
    private fun setupSpecialVarsForExpSumm(call : CallSummary, block : CallId) : CommandWithRequiredDecls<TACCmd.Simple> {
        val receiver = if (call.callType == TACCallType.DELEGATE) {
            TACKeyword.ADDRESS.toVar(block)
        } else {
            call.toVar
        }
        val caller = TACKeyword.ADDRESS.toVar(block)
        return CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(CVLKeywords.calledContract.toVar(), receiver),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(CVLKeywords.executingContract.toVar(), caller)),
            listOfNotNull(CVLKeywords.calledContract.toVar(), receiver as? TACSymbol.Var, CVLKeywords.executingContract.toVar(), caller as? TACSymbol.Var)
        )
    }

    /**
     * @return a command to set [envVar] to an `env` struct representing a call.  All fields are the
     * same as the calling environment, except `msg.value` [msgValue] and `msg.sender` [msgSender]
     *
     * @param callId the block where the summary will be inlined
     */
    fun setupEnvForExpSummary(
        envVar: TACSymbol.Var,
        msgValue: TACExpr.Sym,
        msgSender: TACExpr.Sym,
        callId: CallId,
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        // Note: we have to build the structs piecemeal so that each field has an associated symbol.
        val (msgVar, copyMsgCmd) = tempStruct(tag = EVMBuiltinTypes.msg.toTag(), suffix = "!withEnvMsg", fields = mapOf(
            "sender"     to msgSender,
            "value"      to msgValue,
        ))
        val (blockVar, copyBlockCmd) = tempStruct(tag = EVMBuiltinTypes.block.toTag(), suffix = "!withEnvBlock", fields = mapOf(
            "basefee"    to TACKeyword.BASEFEE.toVar(callId).asSym(),
            "blobbasefee" to TACKeyword.BLOBBASEFEE.toVar(callId).asSym(),
            "coinbase"   to TACKeyword.COINBASE.toVar(callId).asSym(),
            "difficulty" to TACKeyword.DIFFICULTY.toVar(callId).asSym(),
            "gaslimit"   to TACKeyword.GASLIMIT.toVar(callId).asSym(),
            "number"     to TACKeyword.NUMBER.toVar(callId).asSym(),
            "timestamp"  to TACKeyword.TIMESTAMP.toVar(callId).asSym(),
        ))
        val (txVar, copyTxCmd) = tempStruct(tag = EVMBuiltinTypes.tx.toTag(), suffix = "!withEnvTx", fields = mapOf(
            "origin"     to TACKeyword.ORIGIN.toVar(callId).asSym(),
        ))
        val newEnv = TACExpr.StructConstant(tag = EVMBuiltinTypes.env.toTag(), fieldNameToValue = mapOf(
            "msg" to msgVar.asSym(),
            "block" to blockVar.asSym(),
            "tx" to txVar.asSym(),
        ))
        val copyEnvCmd = TACCmdStructFlattener.flattenStructs(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(envVar, newEnv)
        )
        return CommandWithRequiredDecls.mergeMany(copyMsgCmd, copyBlockCmd, copyTxCmd, copyEnvCmd)
    }

    /**
     * Create a temporary variable of type [tag] with containing a struct with [fields]
     * @param suffix the name of the temporary variable
     * @return the variable and the command to create the struct
     */
    private fun tempStruct(
        tag : Tag,
        suffix : String,
        fields : Map<String, TACExpr.Sym>
    ) : Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Simple>> {
        val tmpVar = TACKeyword.TMP(tag = tag, suffix = suffix)
        val struct = TACExpr.StructConstant(tag = tag, fieldNameToValue = fields)
        return tmpVar to TACCmdStructFlattener.flattenStructs(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(tmpVar, struct)
        )
    }

    /** @return a converter that can decode VM arguments from [call] that are defined in the method header of [summary] */
    private fun decodeSummaryArguments(
        summary: SpecCallSummary.Exp,
        call: CallSummary,
        summaryBodyCallId: CallId,
    ) : TACCmd.CVL.VMParamConverter {

        // map argument names to offsets within the input buffer
        val paramPositions = mutableMapOf<String, BigInteger>()

        var offset = BigInteger.ZERO
        summary.funParams.forEach { param ->
            if (param is VMParam.Named) {
                // if it's not named, it's impossible to show up in a summary (sure hope so anyway)
                paramPositions[param.name] = offset
            }
            offset += (param.vmType as EVMTypeDescriptor).sizeAsEncodedMember()
        }

        // create closure to do the actual decoding / conversion when the arguments are encountered in the expression

        val input = call.callConvention.input

        return TACCmd.CVL.VMParamConverter { assignment ->
            val (inBuff, setup) = LowLevelDecoder(
                buffer = input.baseVar.s,
                offset = input.offset.s,
                /**
                 * When possible, prefer reading the funCallInput!... variables bound by the caller
                 * instead of the calldata buffer (as this helps illuminate dataflow flow).
                 */
                relativeScalars = call.callConvention.input.rangeToDecomposedArg.mapNotNull { (k, v) ->
                    if(((k.to - k.from) + BigInteger.ONE) != EVM_WORD_SIZE) {
                        return@mapNotNull null
                    }
                    when(v) {
                        is DecomposedCallInputArg.Constant -> null
                        is DecomposedCallInputArg.Variable -> {
                            k.from to v.v
                        }
                    }
                }.toTreapMap()
            )

            val cvlType = assignment.lhsType
            val vmType  = assignment.rhsType.descriptor
            val conversionVisitor = FromVMContext.ExternalSummaryArgBinding.getVisitor()
            val converter = vmType.converterTo(cvlType, conversionVisitor).force()
            (inBuff.advanceCurr(
                DEFAULT_SIGHASH_SIZE
            ) { atArgStart ->
                atArgStart.saveScope { withScope ->
                    withScope.advanceCurr(paramPositions[assignment.rhsName]!!) { input ->
                        converter.convertTo(input, CVLDataOutput(
                            assignment.lhsVar, summaryBodyCallId
                        )) { it } as CVLTACProgram
                    }
                }
            }).prependToBlock0(setup)
        }
    }

    /**
     * @return a command to convert the output of [summary] into the result of [call].
     * @param returnTypes the types from an `expect` or `returns` clause in the methods block
     */
    private fun encodeSummaryReturn(
        summary: CompiledExpressionSummary,
        call: CallSummary,
        callerCalleeId: CallId,
        returnTypes: List<VMTypeDescriptor>,
        linkingState: ExternalLinkingState,
        funcSignature: String?,
    ) : CVLTACProgram {
        // there are three relevant types:
        //   - the CVL return type of the summary expression
        //   - the VM type declared by `expect` or `returns` in the methods block
        //   - the buffer type expected by the contract (buffer of size RETURN_SIZE)
        // CVL type checking should ensure that the CVL type is convertible to the VM type;
        // Solidity typechecking should ensure that the VM type fits in the output buffer (in most cases, probably)

        val returnBuffer = TACKeyword.RETURNDATA.toVar().atSync(callIndex = callerCalleeId)
        val returnSize   = TACKeyword.RETURN_SIZE.toVar(callIndex = callerCalleeId)

        fun voidSummary() = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = returnSize,
                rhs = BigInteger.ZERO.asTACExpr
            )
        ), listOf(returnSize)).toProg("empty", summary.callId.toContext())


        val outVars = summary.outVars
        return if(outVars.isEmpty()) {
            if(call.outSize != BigInteger.ZERO.asTACSymbol()) {
                val msg = "`${funcSignature ?: call.sigResolution.first()}` is a non-void function, yet it  was summarized with the void summary `${summary.summaryName}`. " +
                    "Summaries of non-void functions must return the same type of value(s) as the functions they summarize."
                Logger.alwaysError(msg)
                throw CertoraException(CertoraErrorType.SUMMARIZATION, msg)
            }
            voidSummary()
        } else if (returnTypes.isEmpty()) {
            voidSummary()
        } else {
            // return setup:
            if(outVars.size < returnTypes.size) {
                throw CertoraInternalException(
                    CertoraInternalErrorType.SUMMARY_INLINING,
                    "Missing output types for summary application"
                )
            }
            val indexedValues = outVars.zip(returnTypes).withIndex().toList()
            val bindings = linkingState.getLinkOutputFor(call.summaryId)
            val (outBuff, setup) = LowLevelEncoder(returnBuffer, offset = BigInteger.ZERO.asTACSymbol(), bindings)
            val scopeSize = indexedValues.sumOf { (_, paramTypePair)  ->
                (paramTypePair.second as EVMTypeDescriptor).sizeAsEncodedMember()
            }
            val (withOutput, assignOutToReturn) = outBuff.saveScope { withScope ->
                withScope.advanceNext(scopeSize) {
                    it.collecting(summary.callId.emptyProgram(), indexedValues) { enc, ind, elem ->
                        check(ind == elem.index)
                        val offsetInReturn = returnTypes.take(ind).sumOf {
                            (it as EVMTypeDescriptor).sizeAsEncodedMember()
                        }
                        enc.advanceCurr(offsetInReturn) { atElem ->
                            val outTACVar = summary.allocatedTACSymbols.get(elem.value.first.id, elem.value.first.type.toTag())
                            val retType = elem.value.second
                            val conv = retType.converterFrom(elem.value.first.type, ToVMContext.ExternalSummaryReturn.getVisitor()).force()
                            conv.convertTo(CVLDataInput(outTACVar, summary.callId), atElem) {
                                toRet -> toRet
                            } as EncoderOutput
                        }
                    }
                }
            }
            assignOutToReturn.prependToBlock0(setup).addSink(generateReturnDataCopy(
                output = withOutput,
                c = call,
                returnData = returnBuffer,
                returnSize = returnSize
            ), summary.callId.toContext()).first
        }
    }

    /**
     * @return the compiled body of [summary]
     * @param assignOuts is used to convert the output of the expression to the return value of the summarized method
     * @param convertIns is used to convert the arguments to the summarized method to the variables of the summary
     * @param setup contains additional commands to set up the `calledContract` and environment variables
     */
    fun generateCVLExpressionSummary(
        summary: CVLTACProgram,
        scene: IScene,
        assignOuts: CVLTACProgram,
        convertIns: TACCmd.CVL.VMParamConverter,
        setup: CommandWithRequiredDecls<TACCmd.Simple>,
        enclosingProgram: CoreTACProgram,
        where: CmdPointer,
        summaryName: String,
    ): CoreTACProgram {
        val augmentedSetup = if (Config.CvlFunctionRevert.get()) {
            setup.merge(EthereumVariables.setLastReverted(lastReverted = false))
        } else {
            setup
        }

        val ret = summary
            .let { p -> CVLToSimpleCompiler.compileVMParamAssignments(p, convertIns) }
            .prependToBlock0(augmentedSetup).let {
                if (Config.CvlFunctionRevert.get()) {
                    val revertNode = CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.NopCmd
                        ), listOf()
                    ).toProg("no return data assignment on revert path", where.block.getCallId().toContext())
                    mergeIfCodes(
                        it,
                        jumpiCmd = TACCmd.Simple.JumpiCmd(
                            dst = revertNode.getStartingBlock(),
                            elseDst = assignOuts.getStartingBlock(),
                            cond = CVLKeywords.lastReverted.toVar()
                        ),
                        thenCode = revertNode,
                        elseCode = assignOuts
                    )
                } else {
                    mergeCodes(it, assignOuts)
                }
            }.transformToCore(scene)

        /** collect a list of [MethodRef]s that were inlined in this summary */
        val summaryInlinedFuncs = ret.ltacStream().mapNotNull { lc ->
            lc.maybeNarrow<TACCmd.Simple.AnnotationCmd>()
                ?.takeIf { annot -> annot.cmd.annot.v is Inliner.CallStack.PushRecord }
        }.collect(Collectors.toList()).map { (it.cmd.annot.v as Inliner.CallStack.PushRecord).callee }

        val callStackHere = InlinedMethodCallStack(enclosingProgram.analysisCache.graph).iterateUpCallers(where)

        /** Count the number of times each [MethodRef] in [summaryInlinedFuncs] already appears in the callstack */
        val existingInvocationCounts = summaryInlinedFuncs.associateWith { callee ->
            callStackHere.count {
                it == callee
            }
        }

        /**
         * If any of the [MethodRef]s in [summaryInlinedFuncs] was already called a number of times larger than the
         * recursion limit, don't inline the [generatedSummary], and instead inline a recursion check command.
         */
        existingInvocationCounts.forEachEntryInline { (m, count)  ->
            if (count > Config.SummaryRecursionLimit.get()) {
                val newBlockId = Allocator.getNBId().copy(calleeIdx = where.block.getCallId())

                return CoreTACProgram.empty("expression summary recursion check").copy(
                    code = mapOf(Pair(newBlockId, listOf(
                        if (!Config.OptimisticSummaryRecursion.get()) {
                            TACCmd.Simple.AssertCmd(
                                o = TACSymbol.False,
                                "Recursion limit (${Config.SummaryRecursionLimit.get()}) for calls to ${m.getContractAndMethod(scene)?.second?.name ?: m.sigHash}, " +
                                    "reached during compilation of summary $summaryName. " +
                                    "You may either set ${Config.OptimisticSummaryRecursion.userFacingName()} to true, or increase ${Config.SummaryRecursionLimit.userFacingName()}."
                            )
                        } else {
                            TACCmd.Simple.AssumeCmd(
                                cond = TACSymbol.False,
                                "recursion limit pruning"
                            )
                        }
                    ))),
                    blockgraph = BlockGraph(newBlockId to treapSetOf())
                )
            }
        }

        return ret
    }

    private fun inlineSummary(
        patching: SimplePatchingProgram,
        where: LTACCmdView<TACCmd.Simple.SummaryCmd>,
        summ: SummaryBlock,
        appliedSummary: Summarization.AppliedSummary,
        callResolutionTableSummaryInfo: CallResolutionTableSummaryInfo
    ) {
        // If the summarySpec is revertable (which presently implies it is a havoc summary),
        // then we inject a branch that checks the return code of the havoced call (which itself
        // may be havoced) and if the call reverted (ie return code is 0) then the call
        // need not be havoced (and the integrity of the contract is effectively preserved).
        val summCode = if (appliedSummary.specCallSumm.isRevertable()) {
            summ.code.patching { summPatching ->
                val root = summPatching.root!!
                val elseId = summPatching.addBlock(root, listOf(TACCmd.Simple.NopCmd))
                val rc = EthereumVariables.rc.at(callIndex = where.wrapped.ptr.block.calleeIdx)
                val condVar = TACKeyword.COND.toVar()
                summPatching.addBlock(root, listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        condVar,
                        rc,
                        TACSymbol.Const(BigInteger.ZERO),
                        TACExprFactUntyped::Gt
                    ),
                    TACCmd.Simple.JumpiCmd(root, elseId, condVar)
                ))
                summPatching.addVarDecls(setOf(rc, condVar))
            }
        } else {
            summ.code
        }

        check(where.cmd.summ is CallSummary)
        val callSummary = where.cmd.summ as CallSummary

        val summaryWithCallStackAnnotations =
            summCode.prependToBlock0(
                listOf(
                    TACCmd.Simple.AnnotationCmd(
                        SummaryStack.START_EXTERNAL_SUMMARY,
                        SummaryStack.SummaryStart.External(
                            callNode = callSummary,
                            appliedSummary = appliedSummary,
                            callResolutionTableInfo = callResolutionTableSummaryInfo
                        )
                    )
                )
            ).appendToSinks(
                listOf(
                    TACCmd.Simple.AnnotationCmd(SummaryStack.END_EXTERNAL_SUMMARY, SummaryStack.SummaryEnd.External(appliedSummary, callSummary.summaryId)),
                    TACCmd.Simple.LabelCmd("Inline ${summ.proc.procedureId}")
                ).withDecls()
            )
        patching.replaceCommand(where.ptr, summaryWithCallStackAnnotations)
        patching.addVarDecls(summ.code.getSymbols())
        patching.procedures.add(summ.proc)
    }
}
