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
import analysis.icfg.InternalCVLSummarizer.SummarizationResult.Materialized
import analysis.icfg.InternalCVLSummarizer.SummarizationResult.Resummarized
import analysis.icfg.Summarizer.resolveCandidates
import analysis.icfg.Summarizer.toCode
import analysis.ip.*
import config.Config
import datastructures.stdcollections.*
import log.*
import report.*
import report.callresolution.CallResolutionTableSummaryInfo
import report.dumps.BlockDifficulty
import report.dumps.CountDifficultOps
import rules.AutosummarizedMonitor
import scene.IScene
import spec.*
import spec.CVLCompiler.CallIdContext.Companion.toContext
import spec.converters.EVMInternalArgData
import spec.converters.EVMInternalCalldataArg
import spec.converters.EVMInternalReturnData
import spec.converters.repr.CVLDataInput
import spec.converters.repr.CVLDataOutput
import spec.cvlast.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.FromVMContext
import spec.cvlast.typedescriptors.ToVMContext
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import tac.*
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.SUMMARIZATION)

class InternalCVLSummarizer private constructor(
    private val expressionSummaryHandler: ExpressionSummaryHandler,
    private val summaries: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>
) : InternalSummarizer<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>() {

    /**
     * Indicates what the summarization did. If it *materialized* a summary, then this should return [Materialized]
     * with information about the summarization to be added to the call resolution table.
     *
     * If the result of the summary was to just insert another summary (that is, a [TACSummary]) then
     * return back [Resummarized] instead.
     */
    sealed class SummarizationResult {
        abstract val result: CoreTACProgram

        data class Materialized(
            override val result: CoreTACProgram,
            val summarizationInfo: CallResolutionTableSummaryInfo
        ) : SummarizationResult()


        data class Resummarized(
            override val result: CoreTACProgram
        ) : SummarizationResult()
    }

    /**
     * Interface that allows control over how an expression summary should be handled. During early summarization, the body of the internal function
     * is simply replaced with a [vc.data.TACCmd.Simple.SummaryCmd] which wraps an [InternalCallSummary]. When we
     * run summarization again later, we actually compile the expression summary and insert it. We hide this implementation
     * detail from the [InternalCVLSummarizer] by simply varying the implementation of this interface we pass in.
     */
    interface ExpressionSummaryHandler {

        fun alreadyHandled(
            cmd: LTACCmd
        ) : Boolean

        /**
         * Handle an expression summary to be applied at [where] which corresponds to the beginning of a function whose information
         * is given by [entryInfo]. The return information for the call is given in [rets].
         *
         * [summaryId] is the summary signature that matched this call, and [summary] is the summary itself to apply.
         *
         * Should return a [SummarizationResult], with call resolution table information if appropriate.
         */
        fun handleExpressionSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal,
            summary: SpecCallSummary.Exp,
            enclosingProgram: CoreTACProgram
        ) : SummarizationResult

        fun handleRerouteSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal
        ) : SummarizationResult
    }

    class ExpressionSummaryMaterializer(
        val cvlCompiler: CVLCompiler?,
        val scene: IScene,
        val internalLinking: Summarizer.LinkingState<Int>
    ) : ExpressionSummaryHandler {
        override fun alreadyHandled(cmd: LTACCmd): Boolean {
            return false
        }

        /**
         * Generates a [CoreTACProgram] of the expression summary [summary] for the cmd at [where].
         * The [enclosingProgram] holds the [CoreTACProgram] of the whole rule, and is used later on
         * in order to add recursion checks.
         */
        override fun handleExpressionSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal,
            summary: SpecCallSummary.Exp,
            enclosingProgram: CoreTACProgram
        ): SummarizationResult {
            require(cvlCompiler != null) {
                "Attempted to use expression summary without a cvl compiler"
            }
            val internalSummaryId = enclosingProgram.analysisCache.graph.elab(where).snarrowOrNull<InternalCallSummary>()?.id

            val compiledSummary = cvlCompiler.compileExpressionSummary(summary)

            // Apparently there are cases where the Solidity compiler will emit at the end of an internal function
            // multiple assignments of the returned value to several variables, all of which are used later (even
            // though they have the same value). So we group the InternalFuncRets according to the offset, since that
            // will be the same for all variables being assigned the same return value.
            val groupedRets = rets.rets.groupBy { it.offset }.entries.sortedBy { it.key }.map { it.value }

            /** alert the user of this possible error, which we only detect in the internal summary case */
            if (entryInfo.methodSignature.resType.size != compiledSummary.outVars.size || groupedRets.size != compiledSummary.outVars.size) {
                Logger.alwaysWarn(
                    "Attempting to summarize `${entryInfo.methodSignature}` to `${compiledSummary.summaryName}`, " +
                        "which returns a different number of values. This may result in unpredictable behavior."
                )
            }

            val callId = where.block.getCallId()

            val funParamToInternalArgInput: Map<String, EVMTypeDescriptor.EVMInternalSummaryInput> = summary.funParams.mapIndexed { index, vmParam ->
                if(vmParam !is VMParam.Named) {
                    return@mapIndexed null
                }
                val matches = entryInfo.args.filter {
                    it.logicalPosition == index
                }
                /**
                 * Why can 2 arguments match? recall that calldata array arguments are passed with two stack slots, so
                 * two [InternalFuncArg] objects can match the single logical position.
                 */
                if(matches.isEmpty() || matches.size > 2) {
                    return@mapIndexed null
                } else if(matches.size == 1) {
                    val match = matches.single()
                    if(match.sort == InternalArgSort.CALLDATA_POINTER) {
                        vmParam.name to EVMInternalCalldataArg.BasicCalldataPointer(
                            buffer = TACKeyword.CALLDATA.toVar(callId),
                            pointer = match.s as TACSymbol.Var,
                            calldataSize = TACKeyword.CALLDATASIZE.toVar(callId),
                            scalars = mapOf() // live dangerously
                        )
                    } else {
                        vmParam.name to EVMInternalArgData(
                            match, TACKeyword.MEMORY.toVar(callId)
                        )
                    }
                } else {
                    check(matches.size == 2) {
                        "Math is broken, expected size 2, got ${matches.size}"
                    }
                    val lengthVar = matches.singleOrNull {
                        it.sort == InternalArgSort.CALLDATA_ARRAY_LENGTH
                    }?.s ?: error("Unexpected argument sorts: couldn't find calldata array length")
                    val elemVar = matches.singleOrNull {
                        it.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS
                    }?.s ?: error("Unexpected argument sorts: couldn't find calldata elements")
                    vmParam.name to EVMInternalCalldataArg.DecomposedArrayPointers(
                        calldataSize = TACKeyword.CALLDATASIZE.toVar(callId),
                        buffer = TACKeyword.CALLDATA.toVar(callId),
                        scalars = mapOf(),
                        elemPointerVar = elemVar as TACSymbol.Var,
                        lengthVar = lengthVar as TACSymbol.Var
                    )
                }
            }.associateNotNull { it }

            // TODO CERT-2736: there is significant duplication between this and [Summarizer]
            val setup = CommandWithRequiredDecls.mergeMany(
                // calledContract and executingContract
                setupSpecialVarsForExpSumm(callId),

                // scene contracts
                cvlCompiler.declareContractsAddressVars(),

                // with(env)
                summary.withClause?.let {
                    Summarizer.setupEnvForExpSummary(
                        envVar = compiledSummary.allocatedTACSymbols.get(it.param.id),
                        callId =  callId,
                        msgValue  = TACKeyword.CALLVALUE.toVar(callId).asSym(), /* same as current `msg.value` */
                        msgSender = TACKeyword.CALLER.toVar(callId).asSym(),    /* same as current `msg.sender` */
                    )
                } ?: CommandWithRequiredDecls()
            )

            val convertIns = TACCmd.CVL.VMParamConverter { assignVMParam ->
                check(assignVMParam.rhsType.context == FromVMContext.InternalSummaryArgBinding) { throw CertoraInternalException(CertoraInternalErrorType.SUMMARY_INLINING,
                    "Got a VM Value of non-internal summary context in an internal summary context: ${VMParam.Named(assignVMParam.rhsName, assignVMParam.rhsType.descriptor, Range.Empty())}")}
                assignVMParam.rhsType.descriptor.converterTo(assignVMParam.lhsType, FromVMContext.InternalSummaryArgBinding.getVisitor()).force()
                    .convertTo(
                        funParamToInternalArgInput[assignVMParam.rhsName]
                            ?: error("could not map fun param to internal arg input $assignVMParam"),
                        CVLDataOutput(assignVMParam.lhsVar, compiledSummary.callId)
                    ) { it } as CVLTACProgram
            }

            fun voidSummary() = CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)).toProg("nothing", compiledSummary.callId.toContext())

            val assignOuts = if (rets.rets.isEmpty() || compiledSummary.outVars.isEmpty()) {
                voidSummary()
            } else {
                val retTypes = (summaryId.signature as? MethodSignature)?.resType?: summary.expectedType!!

                if(retTypes.isEmpty()) {
                    voidSummary()
                } else {
                    if (compiledSummary.outVars.size < groupedRets.size) {
                        throw CertoraInternalException(
                            CertoraInternalErrorType.SUMMARY_INLINING,
                            "Not enough return variables to hold the values being returned"
                        )
                    }
                    compiledSummary.outVars.zip(groupedRets).withIndex().flatMap { (ind, paramAndReturns) ->
                        val (outParam, internalReturns) = paramAndReturns
                        val summaryOutTACVar = compiledSummary.allocatedTACSymbols
                            .get(outParam.id, outParam.type.toTag())
                        internalReturns.map { internalReturn ->
                            val summarizedFunctionReturnTACVar = internalReturn.s
                            val target = when (val location = internalReturn.location) {
                                is InternalFuncValueLocation.PointerWithFields -> {
                                    EVMInternalReturnData(
                                        summarizedFunctionReturnTACVar,
                                        location.layoutForFields,
                                        where.block.calleeIdx
                                    )
                                }

                                /**
                                 * Q: What if we get a pointer variable here and didn't somehow annotate it with the partitions?
                                 * A: the attempts to allocate will crash. I am not willing to risk allowing arbitrary
                                 * memory operations on a scalar variable in the context of "all of memory" without a lot
                                 * more safeguards, the implementation of which require more code than this PR should have.
                                 */
                                InternalFuncValueLocation.Scalar,
                                InternalFuncValueLocation.Pointer -> EVMInternalReturnData(
                                    summarizedFunctionReturnTACVar
                                )

                                null -> {
                                    EVMInternalReturnData(
                                        summarizedFunctionReturnTACVar, TACKeyword.MEMORY.toVar(callId), callId
                                    )
                                }

                                is InternalFuncValueLocation.UnsplitPointer -> {
                                    /**
                                     * this location is used in revert blocks, and uses the monolithic "revert block memory"
                                     * accordingly, we use the unsplit evm return class.
                                     */
                                    EVMInternalReturnData(
                                        summarizedFunctionReturnTACVar, location.monolithicArray, callId
                                    )
                                }
                            }
                            val converter =
                                retTypes[ind].converterFrom(
                                    outParam.type,
                                    ToVMContext.InternalSummaryReturn.getVisitor()
                                )
                                    .force()
                            (converter.convertTo(
                                fromVar = CVLDataInput(summaryOutTACVar, compiledSummary.callId),
                                toVar = target,
                                cb = { it }
                            ) as CVLTACProgram)
                        }.let {
                            /**
                             * Is this return value used as the callee of some external call?
                             * If so assign one of the return variables for this return position (we don't care, just
                             * use `first`) to that linking variable.
                             */
                            val returnRepr = internalReturns.first()
                            val linkVar = internalSummaryId?.let { id ->
                                internalLinking.getLinkOutput(id, returnRepr.offset)
                            } ?: return@let it
                            it + (CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = linkVar, rhs = returnRepr.s.asSym())
                            ), setOf(linkVar, returnRepr.s))).toProg("linkBinding", compiledSummary.callId.toContext())
                        }
                    }.reduce(::mergeCodes)
                }
            }

            return Materialized(
                result = Summarizer.generateCVLExpressionSummary(
                    compiledSummary.body,
                    scene,
                    assignOuts = assignOuts,
                    convertIns = convertIns,
                    setup = setup,
                    enclosingProgram = enclosingProgram,
                    where = where,
                    summaryName = summary.exp.toString()
                ),
                summarizationInfo = CallResolutionTableSummaryInfo.DefaultInfo(SummaryApplicationReason.Spec.reasonFor(summary, summaryId.funcSignature))
            )
        }

        override fun handleRerouteSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal
        ): SummarizationResult {
            throw IllegalStateException("Should have replaced reroute summary at $where far, far earlier in the pipeline")
        }

        /**
         * @return a command to set `calledContract` to the correct value: caller's address for library calls, and call's
         * receiver otherwise, plus one to set `executingContract` to the caller
         *
         * @param block the CallID where the summary will be inlined
         */
        private fun setupSpecialVarsForExpSumm(block : CallId) : CommandWithRequiredDecls<TACCmd.Simple> {
            // TODO CERT-2736: there is significant duplication between this and [Summarizer]
            /**
             * In the case of an internal function call the magic keywords `calledContract` and the `executingContract`
             * are set to the same value. Having these is rather a convenience to the user. In the case of external calls these
             * values will be assigned non-identical values, see [Summarizer].
             */
            return CommandWithRequiredDecls(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(CVLKeywords.calledContract.toVar(), TACKeyword.ADDRESS.toVar(block)),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(CVLKeywords.executingContract.toVar(), TACKeyword.ADDRESS.toVar(block))),
                CVLKeywords.calledContract.toVar(), TACKeyword.ADDRESS.toVar(block), CVLKeywords.executingContract.toVar()
            )
        }
    }

    private fun generateAlwaysSummary(summary: SpecCallSummary.Always, rets: List<TACSymbol.Var>, where: CmdPointer, funId: CVL.SummarySignature.Internal) =
        rets.map { ret ->
            val rhs = normalizeBool(TACExprFactTypeCheckedOnlyPrimitives.Sym(Summarizer.alwaysSummaryRhs(summary.exp)), ret.tag)
            checkReturn(rhs.tagAssumeChecked, ret.tag, funId, summary.range)
            TACCmd.Simple.AssigningCmd.AssignExpCmd(ret, rhs)
        }.withDecls(rets).toCode(where.block.getCallId())

    private fun generateNondetSummary(rets: List<TACSymbol.Var>, where: CmdPointer) =
        rets.map { ret ->
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(ret)
        }.withDecls(rets).toCode(where.block.getCallId())

    private fun generateConstantSummary(
        funId: CVL.SummarySignature.Internal,
        rets: List<TACSymbol.Var>,
        where: CmdPointer
    ): CoreTACProgram {
        logger.info { "Generating CONSTANT summary for $funId" }
        return rets.mapIndexed { idx, ret ->
            /*
             * For the constant summary, we need to have a variable name that stays the same across all
             * calls to the summarized function. This is the reason we name the var using the function
             * signature's hashcode and don't use [getFreshAuxVar] or the [toUnique] methods when creating
             * the name.
             * Finally, the hashcode could be negative, and we don't alllow the '-' character in TACSymbol.Var
             * names. So bwand the hashcode with max_int in order to get rid of the sign bit.
             */
            val symName = funId.funcSignature.hashCode().and(Int.MAX_VALUE).toString(16)
            TACSymbol.Var(
                "certora!constSummary" +
                    "!${funId.methodId}" +
                    "!$symName" +
                    "!$idx",
                ret.tag
            ).withMeta(TACMeta.SUMMARY_GLOBAL)
        }.let { retConstants ->
            if (retConstants.size != rets.size) {
                val msg = "Different number of return values inferred for the same function ${funId.namedFuncSignature}"
                Logger.alwaysError(msg)
                throw IllegalStateException(msg)
            }
            retConstants.mapIndexed { idx, constant ->
                TACCmd.Simple.AssigningCmd.AssignExpCmd(rets[idx], constant.asSym())
            }.withDecls(retConstants + rets).toCode(where.block.getCallId())
        }
    }


    private fun generateSummary(
        callSite: InternalFunctionStartInfo,
        summSignature: CVL.SummarySignature.Internal,
        specCallSumm: SpecCallSummary.ExpressibleInCVL,
        where: CmdPointer,
        retAnnot: FunctionReturnInformation,
        enclosingProgram: CoreTACProgram
    ): CoreTACProgram {
        val rets = retAnnot.rets.map { it.s }
        val summAppReason = SummaryApplicationReason.Spec.reasonFor(specCallSumm, summSignature.funcSignature)
        /*
         * Runtime check to ensure we are not summarizing a reference type returning function with a non-exp funtion.
         * typechecker should prevent this, the error message is intentionally unfriendly.
         */
        if(specCallSumm !is SpecCallSummary.Exp && specCallSumm !is SpecCallSummary.Reroute) {
            callSite.methodSignature.resType.singleOrNull()?.let {
                if(it !is VMValueTypeDescriptor) {
                    throw CertoraException(
                        CertoraErrorType.SUMMARIZATION,
                        "Cannot summarize non-value return type for internal function ${callSite.methodSignature.prettyPrint()} with $specCallSumm"
                    )
                }
            }
        }
        /*
         * "Runtime" check that we're inlining a summary that is supported for internal functions. Should be prevented
         * by the typechecker.
         */
        if(specCallSumm !is SpecCallSummary.InternalSummary) {
            val msg = "requested to summarize the internal function $summSignature with $specCallSumm, " +
                "this type of summary is unsupported for internal functions"
            Logger.alwaysError(msg)
            throw IllegalStateException(msg)
        }
        return when (specCallSumm) {
            is SpecCallSummary.Always -> Materialized(
                result = generateAlwaysSummary(
                    specCallSumm,
                    rets,
                    where,
                    summSignature
                ), summarizationInfo = CallResolutionTableSummaryInfo.DefaultInfo(summAppReason))
            is SpecCallSummary.Constant -> Materialized(
                generateConstantSummary(
                    summSignature,
                    rets,
                    where
                ),
                summarizationInfo =  CallResolutionTableSummaryInfo.DefaultInfo(summAppReason))
            is SpecCallSummary.HavocSummary.Nondet -> Materialized(
                result = generateNondetSummary(
                    rets,
                    where
                ),
                summarizationInfo = CallResolutionTableSummaryInfo.HavocInfo.HavocDeclaredInSpec(
                    havocType = Havocer.HavocType.Static,
                    applicationReason = summAppReason
            ))
            is SpecCallSummary.Exp -> expressionSummaryHandler.handleExpressionSummary(
                where = where,
                entryInfo = callSite,
                summary = specCallSumm,
                rets = retAnnot,
                summaryId = summSignature,
                enclosingProgram = enclosingProgram
            )

            is SpecCallSummary.Reroute -> expressionSummaryHandler.handleRerouteSummary(
                where = where,
                entryInfo = callSite,
                summaryId = summSignature,
                rets = retAnnot
            )
        }.let { result ->
            val body = result.result
            when(result) {
                is Materialized -> {
                    val appliedSummary = Summarization.AppliedSummary.MethodsBlock(specCallSumm, summSignature)
                    body.prependToBlock0(listOfNotNull(
                        callSite.callSiteSrc?.getSourceDetails()?.range?.let {
                            // Adding the call site explicitly as the debug step.
                            SnippetCmd.ExplicitDebugStep(it).toAnnotation() },
                        TACCmd.Simple.AnnotationCmd(
                            SummaryStack.START_INTERNAL_SUMMARY,
                            SummaryStack.SummaryStart.Internal(
                                callSiteSrc = callSite.callSiteSrc,
                                methodSignature = callSite.methodSignature,
                                callResolutionTableInfo = result.summarizationInfo,
                                appliedSummary = appliedSummary
                            )
                        )
                    )).appendToSinks(
                        listOf(
                            TACCmd.Simple.AnnotationCmd(
                                SummaryStack.END_INTERNAL_SUMMARY,
                                SummaryStack.SummaryEnd.Internal(appliedSummary, retAnnot.rets, callSite.methodSignature)
                            )
                        ).withDecls()
                    )
                }
                is Resummarized -> body
            }
        }
    }

    /**
     * Recursively instrument all internal summaries. the recursion could occur if an internal summary is
     * an expression summary which contains a call to some contract function which in turn calls an internal
     * function that needs sumarization.
     * @return if there were any internal summaries to instrument, returns [code] with
     * those instrumentation paired with `true`, else returns the original code paired with whether the code was modified
     */
    private fun summarizeInternalFunctions(
        code: CoreTACProgram,
    ): Pair<CoreTACProgram, Boolean> {

        // no summaries? get out and don't compute unneeded stuff that can also crash.
        if (summaries.isEmpty()) {
            return code to false
        }

        // get annotations for every inlined method
        val summarizationInfo = code.parallelLtacStream().mapNotNull { it.maybeAnnotation(INTERNAL_FUNC_FINDER_INFO) }
            .collect(Collectors.toSet())

        summaries.forEachEntry { (summarizedMethodSignature, summToApply) ->
            summarizationInfo.forEach { finderReport ->
                val unresolvedInternalFunctions = finderReport.unresolvedFunctions
                // check for failures at all (summaries X inlinedMethods)
                unresolvedInternalFunctions.find { methodSignature -> summarizedMethodSignature.matches(methodSignature)}?.let { unresolved ->
                    val msg = "Requested a summary for `$unresolved` in `${code.name}` but the tool was unable to locate this " +
                            "function. This may be because the function does not exist or because of an error in the " +
                            "tool. Consider using the `--disable_solc_optimizers` flag to disable specific optimization passes " +
                            "listed here: https://docs.soliditylang.org/en/latest/using-the-compiler.html (e.g., cse, peephole)."
                    CVTAlertReporter.reportAlert(
                        CVTAlertType.SUMMARIZATION,
                        CVTAlertSeverity.ERROR,
                        summToApply.range as? TreeViewLocation,
                        msg,
                        null
                    )
                }
            }
        }

        return summarizeInternalFunctionLoop(code, false)
    }

    override fun generateSummary(
        internalFunctionStartInfo: InternalFunctionStartInfo,
        selectedSummary: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        functionStart: CmdPointer,
        rets: FunctionReturnInformation,
        intermediateCode: CoreTACProgram
    ): CoreTACProgram {
        return generateSummary(
            callSite = internalFunctionStartInfo,
            summSignature = selectedSummary.summaryKey,
            specCallSumm = selectedSummary.selectedSummary,
            where = functionStart,
            retAnnot = rets,
            intermediateCode
        )
    }


    @SuppressRemapWarning
    private data class WrappedInlinedSummary(
        val wrapped: InternalCallSummary
    ) : FunctionReturnInformation {
        override val rets: List<InternalFuncRet>
            get() = wrapped.internalExits

    }

    override fun handleExplicitSummary(
        where: CmdPointer,
        explicit: InternalCallSummary,
        selectedSummary: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        enclosingProgram: CoreTACProgram
    ): SummaryApplicator {
        return { patcher ->
            val gen = generateSummary(
                explicit,
                summSignature = selectedSummary.summaryKey,
                specCallSumm = selectedSummary.selectedSummary,
                where = where,
                retAnnot = WrappedInlinedSummary(explicit),
                enclosingProgram = enclosingProgram
            )
            patcher.replaceCommand(where, gen)
        }
    }

    override fun selectSummary(sig: QualifiedMethodSignature): SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>? {
        return summaries.entries.filter { (k, summ) ->
            k.matches(sig) && summ.summarizeAllCalls
        }.resolveCandidates()?.let { (summSignature, specCallSumm) ->
            SummarySelection(summSignature, specCallSumm)
        }
    }

    override fun alreadyHandled(
        summarySelection: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        where: LTACCmd
    ): Boolean {
        return expressionSummaryHandler.alreadyHandled(where)
    }

    private fun checkReturn(rhsTag: Tag, retTag: Tag, funId: CVL.SummarySignature.Internal, range: Range) {
        if (!rhsTag.isSubtypeOf(retTag)) {
            val msg = "$range: Summary type $rhsTag must be a subtype of function return type " +
                    "(${retTag}) when summarizing ${funId.namedFuncSignature}"
            Logger.alwaysError(msg)
            throw IllegalStateException(msg)
        }
    }

    private fun normalizeBool(e: TACExpr, retTag: Tag) =
        if (e.tag == Tag.Bool && retTag == Tag.Bit256) {
            // possible if the typerewriter farts
            TACExprFactTypeCheckedOnlyPrimitives.Ite(e, TACSymbol.lift(1).asSym(), TACSymbol.lift(0).asSym())
        } else {
            e
        }


    /**
     * "Trivial" implementation of [ExpressionSummaryHandler] which simply uses a [vc.data.TACCmd.Simple.SummaryCmd] as
     * a placeholder for replacement later.
     */
    object ExpressionSummaryMarker : ExpressionSummaryHandler {
        override fun alreadyHandled(cmd: LTACCmd): Boolean {
            return cmd.snarrowOrNull<InternalCallSummary>() != null
        }

        override fun handleExpressionSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal,
            summary: SpecCallSummary.Exp,
            enclosingProgram: CoreTACProgram
        ): SummarizationResult {
            val theSummary = InternalCallSummary(
                signature = entryInfo.methodSignature,
                internalExits = rets.rets,
                internalArgs = entryInfo.args.takeIf {
                    !Config.EnableAggressiveABIOptimization.get() || !summary.isNoArgSummary()
                }.orEmpty(),
                callSrc = entryInfo.callSiteSrc,
                id = Allocator.getFreshId(Allocator.Id.INTERNAL_CALL_SUMMARY),
                calleeSrc = entryInfo.calleeSrc
            )
            return Resummarized(CommandWithRequiredDecls(listOf(
                TACCmd.Simple.SummaryCmd(
                    theSummary,
                    meta = MetaMap()
                )
            ), theSummary.variables).toCode(where.block.getCallId()))
        }

        override fun handleRerouteSummary(
            where: CmdPointer,
            entryInfo: InternalFunctionStartInfo,
            rets: FunctionReturnInformation,
            summaryId: CVL.SummarySignature.Internal
        ): SummarizationResult {
            val theSummary = InternalCallSummary(
                signature = entryInfo.methodSignature,
                internalArgs = entryInfo.args,
                calleeSrc = entryInfo.calleeSrc,
                callSrc = entryInfo.callSiteSrc,
                id = Allocator.getFreshId(Allocator.Id.INTERNAL_CALL_SUMMARY),
                internalExits = rets.rets
            )
            return Resummarized(CommandWithRequiredDecls(listOf(
                TACCmd.Simple.SummaryCmd(
                    theSummary,
                    meta = MetaMap()
                )
            ), theSummary.variables).toCode(where.block.getCallId())

            )
        }

    }

    /**
     * Handles the application of early summarization and computing the cache key to be incorporated into the scene key.
     */
    object EarlySummarization {
        /**
         * We do not eagerly apply expression summaries because those will require CVL compilation to run, which is
         * way, WAY later in the pipeline, and in for complex return types, we can't even generate a meaningful annotation
         * until later analyses have run. This can be addressed with future engineering, but this first pass will punt for now.
         *
         * Also, it is hard (ask Shay) to compute a canonical representation of the expression. Again, can be worked around,
         * but we are ignoring this complexity for now.
         */
        private fun earlySummarizationCandidates(c: CVL) : Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL> {
            return c.internal
        }

        /**
         * Applies the eligible internal function summaries (as determined by [earlySummarizationCandidates])
         * in [q] to the program [prog].
         */
        fun applyEarlyInternalSummarization(prog: CoreTACProgram, q: CVL?) : CoreTACProgram {
            if (q == null) {
                return prog
            }
            val candidates = earlySummarizationCandidates(q)
            return if (candidates.isNotEmpty()) {
                // round 1 - apply summaries from spec
                val round1Result = summarizeInternalFunctions(
                    code = prog,
                    summaries = candidates,
                    expressionSummaryHandler = ExpressionSummaryMarker
                ).let { (resultCode, isSummarized) ->
                    if (isSummarized) {
                        logger.info { "Performed early summarization, round 1 :)" }
                    }
                    resultCode
                }

                earlySummarizeInternalFunctions(
                    code = round1Result,
                    summariesFromCVL = candidates,
                    expressionSummaryHandler = ExpressionSummaryMarker,
                    cvl = q
                ).let { (resultCode, isSummarized, autosummarized) ->
                    if (isSummarized) {
                        logger.info { "Performed early summarization, round 2 :)" }
                    }
                    AutosummarizedMonitor.addAutosummarizedMethods(autosummarized)
                    resultCode
                }
            } else {
                // otherwise, just run autosummarizer
                earlySummarizeInternalFunctions(
                    code = prog,
                    summariesFromCVL = emptyMap(),
                    expressionSummaryHandler = ExpressionSummaryMarker,
                    cvl = q
                ).let { (resultCode, isSummarized, autosummarized) ->
                    if (isSummarized) {
                        logger.info { "Performed early summarization :)" }
                    }
                    AutosummarizedMonitor.addAutosummarizedMethods(autosummarized)
                    resultCode
                }
            }
        }

        /**
         * Given the [CVL] query [q], compute a digest of the summary information.
         * At the moment this consists of all internal summaries selected by [earlySummarizationCandidates]
         * (that is, non-exp summaries) and the *signature* of all external summaries.
         *
         * The external summaries affect inlining decisions, but only whether a function should be inlined or not (we still
         * apply the external summaries well after the scene cache is relevant), so we do NOT need to include the types
         * of summaries in the digest computation. However, for internal summaries, we do need to include some canonical
         * representation of the summary.
         *
         * Rather than reinvent the wheel, we use [SpecCallSummary.summaryName], which for non-exp summaries, includes in the
         * string representation all relevant information: for always, the constant, for dispatcher forced or not (although
         * dispatcher isn't relevant in the context of an internal summary anyway).
         */
        fun computeSummaryDigest(q: CVL?) : BigInteger {
            if(q == null) {
                return BigInteger.ZERO
            }
            val cand = earlySummarizationCandidates(q)

            for (specCallSummary in cand.values) {
                check(specCallSummary is SpecCallSummary.SummaryNameIsCanonical || specCallSummary is SpecCallSummary.Exp) {
                    "Expected summary to be an expression, or to have a canonical expression name"
                }
            }

            /**
             * Sort the keys according to the (utterly arbitrary order) chosen by the comparable
             * implementation of external and internal keys.
             */
            return BigInteger(
                digestItems(
                    buildList {
                        cand.toSortedMap().forEachEntry { (summSignature, specCallSumm) ->
                            add(summSignature)
                            if(specCallSumm is SpecCallSummary.Exp) {
                                add("expression")
                            } else {
                                add(specCallSumm.summaryName)
                                add(specCallSumm.summarizeAllCalls)
                            }
                        }
                        q.external.entries.sortedBy { it.key }.forEach { (k, v) ->
                            add(k)
                            add(v.summarizationMode)
                        }
                    }
                )
            )
        }


        data class AutosummaryWithDifficulty(
            val summaryToApply: SpecCallSummary.ExpressibleInCVL,
            val difficulty: BlockDifficulty
        )

        data class EarlySummarizationOutput(
            val resultProg: CoreTACProgram,
            val isSummarized: Boolean,
            val autosummarizedMethods: Map<out CVL.InternalExact, AutosummaryWithDifficulty>
        )

        /**
         * Handle internal function summaries
         */
        private fun earlySummarizeInternalFunctions(
            code: CoreTACProgram,
            summariesFromCVL: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
            expressionSummaryHandler: ExpressionSummaryHandler,
            cvl: CVL
        ): EarlySummarizationOutput {
            val autosummarized = if (Config.AutoNondetDifficultInternalFuncs.get()) {
                findDifficultInternalFuncs(code, summariesFromCVL, cvl)
            } else {
                emptyMap()
            }

            val result = summarizeInternalFunctions(code, summariesFromCVL + autosummarized.mapValues { it.value.summaryToApply }, expressionSummaryHandler)
            return EarlySummarizationOutput(result.first, result.second, autosummarized)
        }

        private fun summarizeInternalFunctions(
            code: CoreTACProgram,
            summaries: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
            expressionSummaryHandler: ExpressionSummaryHandler
        ): Pair<CoreTACProgram, Boolean> {
            return InternalCVLSummarizer(expressionSummaryHandler, summaries).summarizeInternalFunctions(code)
        }

        private fun findDifficultInternalFuncs(
            code: CoreTACProgram,
            summaries: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
            cvl: CVL
        ): Map<out CVL.InternalExact, AutosummaryWithDifficulty> {
            val exitFinder = InternalFunctionExitFinder(code)

            fun getMethodSigFromStartAnnotLCmd(ltacCmd: LTACCmd) = ltacCmd.maybeAnnotation(INTERNAL_FUNC_START)!!.methodSignature
            val allInternalFuncs = cvl.getAllInternalFunctions()

            val toNondet = code.parallelLtacStream().filter {
                it.toFuncStart() != null
            }.mapNotNull { startLCmd ->
                val currStart = startLCmd.maybeAnnotation(INTERNAL_FUNC_START)!!
                // skip if cannot find in any of the contracts (pretty much an error! but not willing to crash because of this feature)
                val fullMethodMatch = allInternalFuncs.firstOrNull { it.method.getPrettyName() == currStart.methodSignature.prettyPrint() }
                    ?: return@mapNotNull null

                // not view/pure - skip
                if (fullMethodMatch.method.stateMutability.let { !it.isView && !it.isPure }) {
                    return@mapNotNull null
                }

                // skip if non value type return
                if (currStart.methodSignature.resType.any { it !is VMValueTypeDescriptor }) {
                    return@mapNotNull null
                }
                // skip if already summarized, including on wildcard cases
                if (summaries.any { it.key.matches(currStart.methodSignature) }) {
                    return@mapNotNull null
                }

                startLCmd
            }.mapNotNull { startLCmd ->
                // even if it seems we could analyze the same method over and over, it could have different
                // optimizations applied to it by solc. Better just spend the time here analyzing all call sites
                // rather than depend on the order in which we process the call sites.
                // e.g. if we have a threshold of 60 the same method could have either a difficulty of 60 or 59,
                // so if we dedup'd and encountered only the 59 instance, we wouldn't autosummarize even though
                // there's a slightly harder instance. So the max difficulty instance will determine if the method is
                // autosummarized.
                val func = getFunction(exitFinder, startLCmd.narrow())
                val exits = func.exits.map { it.ptr }
                val g = code.analysisCache.graph

                // count difficult ops
                val blockDifficulty = CountDifficultOps.computeDifficultyInSubgraph(g, startLCmd.ptr, exits)

                (startLCmd `to?` blockDifficulty.takeIf { (blockDifficulty.computeDifficultyScore() > Config.AutoNondetMinimalDifficulty.get()) }).also {
                    logger.info { "Difficulty of ${getMethodSigFromStartAnnotLCmd(startLCmd)} within ${code.name} is ${blockDifficulty.computeDifficultyScore()}" }
                }
            }.collect(Collectors.toList())
                .associate { (startLCmd, difficulty) ->
                    val methodSig = getMethodSigFromStartAnnotLCmd(startLCmd)
                    CVL.InternalExact(methodSig) to
                        AutosummaryWithDifficulty(
                            SpecCallSummary.HavocSummary.Nondet(
                                SpecCallSummary.SummarizationMode.ALL,
                                Range.Empty("by auto-summarizer (potentially difficult function, difficulty ${difficulty.computeDifficultyScore()})")
                            ),
                            difficulty
                        )
                }

            return if (toNondet.isNotEmpty()) {
                logger.info {
                    "Found hard internal functions to nondet in ${code.name}: " +
                        "${toNondet.mapKeys { it.key.signature }.mapValues { it.value.summaryToApply to it.value.difficulty.computeDifficultyScore() }}"
                }
                toNondet
            } else {
                emptyMap()
            }
        }
    }

    companion object {
        fun summarizeInternalFunctions(
            code: CoreTACProgram,
            summaries: Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
            summaryHandler: ExpressionSummaryHandler
        ): Pair<CoreTACProgram, Boolean> {
            return InternalCVLSummarizer(
                summaryHandler, summaries
            ).summarizeInternalFunctions(code)
        }

        /**
         * credit: jtoman
         *
         */
        fun addOpaqueIdentityAnnotations(c: CoreTACProgram): CoreTACProgram {
            return c.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                    it.cmd.annot.k == INTERNAL_FUNC_EXIT
                }
            }.collect(Collectors.toList()).let { exits ->
                c.patching { p ->
                    for(e in exits) {
                        val annot = e.cmd.annot.v as InternalFuncExitAnnotation
                        val opaqueCopies = mutableListOf<TACCmd.Simple>()
                        for(r in annot.rets) {
                            val bif = TACBuiltInFunction.OpaqueIdentity(
                                tag = r.s.tag
                            )
                            opaqueCopies.add(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = r.s,
                                    rhs = TACExpr.Apply(
                                        f = TACExpr.TACFunctionSym.BuiltIn(bif),
                                        ops = listOf(r.s.asSym()),
                                        tag = r.s.tag
                                    )
                                )
                            )
                        }
                        opaqueCopies.add(e.cmd)
                        p.replaceCommand(e.ptr, opaqueCopies)
                    }
                }
            }
        }
    }
}
