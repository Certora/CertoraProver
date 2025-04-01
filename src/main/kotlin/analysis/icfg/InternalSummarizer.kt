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

import algorithms.transitiveClosure
import allocator.Allocator
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.dataflow.GlobalValueNumbering
import analysis.dataflow.IGlobalValueNumbering
import analysis.icfg.InternalSummarizer.SummarizationResult.Materialized
import analysis.icfg.InternalSummarizer.SummarizationResult.Resummarized
import analysis.icfg.Summarizer.resolveCandidates
import analysis.icfg.Summarizer.toCode
import analysis.ip.*
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import datastructures.stdcollections.orEmpty
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
import vc.data.SimplePatchingProgram.Companion.patchForEach
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.SUMMARIZATION)

object InternalSummarizer {
    /**
     * Provides information about the return variables for a summary. These can have three sources:
     * 1. A single external exit annotation
     * 2. The return field of an [InternalCallSummary]
     * 3. A confluence of different exit annotations that we compute below.
     *
     * Q: Why not just pass a list around?
     * A: Great question. Earlier versions of this interface had more information than just the list. The wrapper classes
     * are probably overkill at this point.
     */
    interface FunctionReturnInformation {
        val rets: List<InternalFuncRet>

        fun syms() = rets.map { it.s }
    }

    @JvmInline
    private value class WrappedInlinedSummary(
        val wrapped: InternalCallSummary
    ) : FunctionReturnInformation {
        override val rets: List<InternalFuncRet>
            get() = wrapped.internalExits

    }

    @JvmInline
    private value class WrappedAnnotation(val i: InternalFuncExitAnnotation) : FunctionReturnInformation {
        override val rets: List<InternalFuncRet>
            get() = i.rets
    }

    private data class ConfluenceSummary(override val rets: List<InternalFuncRet>) : FunctionReturnInformation

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
     * detail from the [InternalSummarizer] by simply varying the implementation of this interface we pass in.
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
        expressionSummaryHandler: ExpressionSummaryHandler,
        enclosingProgram: CoreTACProgram
    ): CoreTACProgram {
        val rets = retAnnot.rets.map { it.s }
        val summAppReason = SummaryApplicationReason.Spec.reasonFor(specCallSumm, summSignature.funcSignature)
        /*
         * Runtime check to ensure we are not summarizing a reference type returning function with a non-exp funtion.
         * typechecker should prevent this, the error message is intentionally unfriendly.
         */
        if(specCallSumm !is SpecCallSummary.Exp) {
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
        }.let { result ->
            val body = result.result
            when(result) {
                is Materialized -> {
                    body.prependToBlock0(listOf(
                        TACCmd.Simple.AnnotationCmd(
                            SummaryStack.START_INTERNAL_SUMMARY,
                            SummaryStack.SummaryStart.Internal(
                                callSiteSrc = callSite.callSiteSrc,
                                methodSignature = callSite.methodSignature,
                                callResolutionTableInfo = result.summarizationInfo,
                                appliedSummary = Summarization.AppliedSummary.MethodsBlock(specCallSumm, summSignature)
                            )
                        )
                    )).appendToSinks(
                        listOf(
                            TACCmd.Simple.AnnotationCmd(
                                SummaryStack.END_INTERNAL_SUMMARY,
                                SummaryStack.SummaryEnd.Internal(retAnnot.rets, callSite.methodSignature)
                            )
                        ).withDecls()
                    )
                }
                is Resummarized -> body
            }
        }
    }

    private data class InternalFunction(val start: LTACCmdView<TACCmd.Simple.AnnotationCmd>,
                                val exits: Set<LTACCmdView<TACCmd.Simple.AnnotationCmd>>)


    private fun getFunction(exitFinder: InternalFunctionExitFinder, start: LTACCmdView<TACCmd.Simple.AnnotationCmd>): InternalFunction {
        val funcId = (start.cmd.annot.v as InternalFuncStartAnnotation).id
        return InternalFunction(start, exitFinder.getExits(funcId, start.ptr))
    }

    private fun LTACCmd.toFuncStart() = this.maybeAnnotation(INTERNAL_FUNC_START)
    private fun LTACCmdView<TACCmd.Simple.AnnotationCmd>.toFuncStart() = this.cmd.annot.v as? InternalFuncStartAnnotation

    /**
     * Here begins infrastructure for determining which summaries should be applied.
     */


    /**
     * We apply summaries in two situations: when we have a materialized, inlined call to a function,
     * or an explicit internal call summary. This class represents those two cases.
     */
    private sealed class NodeType {
        /**
         * No unique ID for the internal call summary, so just use the cmd pointer of the location as a unique key.
         */
        data class ExplicitSummary(
            val summaryLocation: CmdPointer
        ) : NodeType()

        /**
         * In this case, the unique ID of the internal function start annotation serves a unique key.
         *
         * The reason for using this will be clear later, when we have to generate [InlinedCall] instances that are subsumed
         * by callers.
         */
        data class InlinedCall(
            val id: Int,
        ) : NodeType()
    }

    /**
     * A summarization type, and the summary signature and summary itself.
     *
     * Q: Why not have three fields
     * A: we were already passing around this pair everywhere.
     */
    private data class SummaryPayload(
        val summaryType: NodeType,
        val specCallSummToInternalSummSig: Pair<SpecCallSummary.ExpressibleInCVL, CVL.SummarySignature.Internal>
    )

    /**
     * "entry" in our static, internal call graph. Every node could have a summary attached ([specCallSummToInternalSummSig]),
     * a location where the entry "starts" ([where]) and a [entryType] indicating whether it is an explicit summary
     * or an inlined call.
     */
    private sealed class StaticEntry {
        abstract val specCallSummToInternalSummSig: Pair<SpecCallSummary.ExpressibleInCVL, CVL.SummarySignature.Internal>?
        abstract val where: CmdPointer
        abstract val entryType: NodeType
    }

    /**
     * A node corresponding to an inlined callee, which can have direct [callees] and explicit summaries that appear
     * within its body [explicitSummaries]. The [id] is same that is used for
     * [analysis.icfg.InternalSummarizer.NodeType.InlinedCall], and is the value of the [InternalFuncStartAnnotation.id]
     * field that generated this.
     */
    private data class FunctionNode(
        override val specCallSummToInternalSummSig: Pair<SpecCallSummary.ExpressibleInCVL, CVL.SummarySignature.Internal>?,
        override val where: CmdPointer,
        val explicitSummaries: Set<NodeType.ExplicitSummary>,
        val callees: Set<Int>,
        val id: Int
    ) : StaticEntry() {
        override val entryType: NodeType
            get() = NodeType.InlinedCall(id)
    }


    /**
     * An explicit summary node. Note that [specCallSummToInternalSummSig] is nullable, we fully expect this to not be the case,
     * but I am not willing to encode an assumption that the set of internal summary signatures being applied is *always*
     * the same.
     */
    private data class SummaryNode(
        override val specCallSummToInternalSummSig: Pair<SpecCallSummary.ExpressibleInCVL, CVL.SummarySignature.Internal>?,
        override val where: CmdPointer
    ) : StaticEntry() {
        override val entryType: NodeType
            get() = NodeType.ExplicitSummary(summaryLocation = where)
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
    fun earlySummarizeInternalFunctions(
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

    fun summarizeInternalFunctions(
        code: CoreTACProgram,
        summaries: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        expressionSummaryHandler: ExpressionSummaryHandler
    ): Pair<CoreTACProgram, Boolean> {
        return summarizeInternalFunctions(code, summaries, expressionSummaryHandler, false /* Nothing was modified yet */)
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

    /**
     * Recursively instrument all internal summaries. the recursion could occur if an internal summary is
     * an expression summary which contains a call to some contract function which in turn calls an internal
     * function that needs sumarization.
     * [codeModified] - set to true if some internal summary was already instrumented
     * @return if there were any internal summaries to instrument, returns [code] with
     * those instrumentation paired with `true`, else returns the original code paired with [codeModified]
     */
    private fun summarizeInternalFunctions(
        code: CoreTACProgram,
        summaries: Map<out CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>,
        expressionSummaryHandler: ExpressionSummaryHandler,
        codeModified: Boolean
    ): Pair<CoreTACProgram, Boolean> {

        // no summaries? get out and don't compute unneeded stuff that can also crash.
        if (summaries.isEmpty()) {
            return code to codeModified
        }

        // get annotations for every inlined method
        val summarizationInfo = code.parallelLtacStream().mapNotNull { it.maybeAnnotation(INTERNAL_FUNC_FINDER_INFO) }
            .collect(Collectors.toSet())

        summaries.forEach { (summarizedMethodSignature, summToApply) ->
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

        /**
         * All of the code that follows is to find the summaries which need to be applied, and then which summaries should
         * NOT be applied because the application site is within a function body that is itself being summarized.
         *
         * NB: this does NOT assume that an [InternalCallSummary] must have a summary attached, nor does it
         * assume that a function replaced with an [InternalCallSummary] cannot later be discovered to appear within a
         * function that is being summarized.
         *
         * As it happens, both of those assumptions are true, FOR NOW, but these sort of assumptions have bitten us before:
         * I'd rather be defensive.
         */

        /**
         * A map from internal function ids to the immediate callees.
         */
        val callRelation = mutableMapOf<Int, Set<Int>>()

        /**
         * What [InternalCallSummary] instances appear within which internal function bodies.
         */
        val containsSummary = mutableMapOf<Int, Set<NodeType.ExplicitSummary>>()

        /**
         * Map from command pointers, to the [SummaryPayload] that should be applied. Note that the synthatic element
         * that occurs at the command pointer in question depends on the [SummaryPayload.summaryType];
         * if it is an [analysis.icfg.InternalSummarizer.NodeType.ExplicitSummary] then it will be an instance of [vc.data.TACCmd.Simple.SummaryCmd],
         * if it is an [analysis.icfg.InternalSummarizer.NodeType.InlinedCall] then it will be an instance of the [InternalFuncStartAnnotation].
         *
         * NB that the pointer here is duplicated in the [SummaryPayload.summaryType] field if the summary type is an [analysis.icfg.InternalSummarizer.NodeType.ExplicitSummary].
         * This was apparently unavoidable.
         */
        val toSummarize = mutableMapOf<CmdPointer, SummaryPayload>()

        /**
         * Finally, the set of nodes that have been "killed" by an outer summary application, so summaries attached to them (if any) should
         * not be applied.
         */
        val subsumedByOuterSummary = mutableSetOf<NodeType>()

        val exitFinder = InternalFunctionExitFinder(code)

        /**
         * Find all function starts and explicit internal call summaries.
         */
        code.parallelLtacStream().filter {
            it.toFuncStart() != null || it.snarrowOrNull<InternalCallSummary>() != null
        }.map {
            /**
             * Get the signature, and use this to find a matching summarization.
             */
            val currentMethodSig = if(it.toFuncStart() != null) {
                it.toFuncStart()!!.methodSignature
            } else {
                it.snarrowOrNull<InternalCallSummary>()!!.methodSignature
            }

            val specCallSummToInternalSummSig = summaries.entries.filter { (k, summ) ->
                k.matches(currentMethodSig) && summ.summarizeAllCalls
            }.resolveCandidates()?.let { (summSignature, specCallSumm) ->
                specCallSumm to summSignature
            }

            /**
             * In this case, we have a "leaf" in our call graph (really a tree, given inlining). Immediately return with the SummaryNode.
             */
            if(it.snarrowOrNull<InternalCallSummary>() != null) {
                return@map SummaryNode(
                    specCallSummToInternalSummSig = specCallSummToInternalSummSig,
                    where = it.ptr
                )
            }


            /**
             * Otherwise, compute the immediate callees of this inlined function (which we know we must be processing now)
             * and the immediately appearing explicit summaries.
             */
            val currentCallIdx = it.ptr.block.calleeIdx
            val currStart = it.maybeAnnotation(INTERNAL_FUNC_START)!!
            val (immediateCallees, inlinedSummaries) = object : VisitingWorklistIteration<CmdPointer, NodeType, Pair<Set<Int>, Set<NodeType.ExplicitSummary>>>() {
                override fun process(it: CmdPointer): StepResult<CmdPointer, NodeType, Pair<Set<Int>, Set<NodeType.ExplicitSummary>>> {
                    val lc = code.analysisCache.graph.elab(it)
                    val start = lc.maybeAnnotation(INTERNAL_FUNC_START)
                    val end = lc.maybeAnnotation(INTERNAL_FUNC_EXIT)
                    if(lc.cmd is TACCmd.Simple.SummaryCmd && lc.cmd.summ is ReturnSummary && lc.cmd.summ.ret is TACCmd.Simple.RevertCmd) {
                        return this.cont(listOf())
                    }
                    if(lc.cmd.maybeAnnotation(Inliner.CallStack.STACK_POP)?.calleeId == currentCallIdx) {
                        return this.cont(listOf())
                    }
                    if(start != null) {
                        val next = exitFinder.getExits(start.id, it).flatMap {
                            code.analysisCache.graph.succ(it.ptr)
                        }
                        return StepResult.Ok(
                            next = next,
                            result = listOf(NodeType.InlinedCall(start.id))
                        )
                    } else if(end != null) {
                        check(currStart.id == end.id) {
                            "Incoherent graph, hit ${end.id} @ $it, expecting to find end for $currStart"
                        }
                        return this.cont(listOf())
                    } else if(lc.cmd is TACCmd.Simple.SummaryCmd && lc.cmd.summ is InternalCallSummary) {
                        return StepResult.Ok(
                            next = code.analysisCache.graph.succ(it),
                            result = listOf(NodeType.ExplicitSummary(summaryLocation = it))
                        )
                    } else {
                        return this.cont(code.analysisCache.graph.succ(it))
                    }
                }

                override fun reduce(results: List<NodeType>): Pair<Set<Int>, Set<NodeType.ExplicitSummary>> {
                    val inlinedCallees = mutableSetOf<Int>()
                    val explicitSummaries = mutableSetOf<NodeType.ExplicitSummary>()
                    for(r in results) {
                        when(r) {
                            is NodeType.ExplicitSummary -> explicitSummaries.add(r)
                            is NodeType.InlinedCall -> inlinedCallees.add(r.id)
                        }
                    }
                    return inlinedCallees to explicitSummaries
                }
            }.submit(code.analysisCache.graph.succ(it.ptr))
            /**
             * Return a node that captures the summary information, immediate callees, and the explicit summaries.
             */
            FunctionNode(
                id = it.toFuncStart()!!.id,
                callees = immediateCallees,
                explicitSummaries = inlinedSummaries,
                specCallSummToInternalSummSig = specCallSummToInternalSummSig,
                where = it.ptr
            )
        }.sequential().forEach { ent ->
            /**
             * Record that, at ent.where, we have a summary to apply of the given type. Note that this summary
             * may not end up being applied if the entry is subsumed by another summary (see below)
             */
            if (ent.specCallSummToInternalSummSig != null && !expressionSummaryHandler.alreadyHandled(
                    code.analysisCache.graph.elab(ent.where)
                )) {
                toSummarize[ent.where] = SummaryPayload(
                    ent.entryType,
                    ent.specCallSummToInternalSummSig!!
                )
            }
            /**
             * Update our global graph of "function nodes" to callees and explicit summaries
             */
            if(ent is FunctionNode) {
                callRelation[ent.id] = ent.callees
                containsSummary[ent.id] = ent.explicitSummaries
            }
        }
        if (toSummarize.isEmpty()) {
            return code to codeModified
        }

        val transitiveCallRelation = transitiveClosure(callRelation, false)

        /**
         * Go over all summaries we found...
         */
        toSummarize.forEach { (_, ent) ->
            /**
             * And for each summary which was applied to a function...
             */
            if(ent.summaryType is NodeType.InlinedCall) {
                /**
                 * Mark all explicit summaries as having been subsumed
                 */
                containsSummary[ent.summaryType.id].orEmpty().let(subsumedByOuterSummary::addAll)
                /**
                 * Mark all transitive, inlined, callees as having been subsumed
                 */
                transitiveCallRelation[ent.summaryType.id].orEmpty().mapTo(subsumedByOuterSummary) {
                    NodeType.InlinedCall(it)
                }
                /**
                 * Mark all explicit summaries that appear within the transitive callees as having also been subsumed.
                 */
                transitiveCallRelation[ent.summaryType.id].orEmpty().flatMapTo(subsumedByOuterSummary) {
                    containsSummary[it].orEmpty()
                }
            }
        }

        val gvn by lazy {
            GlobalValueNumbering(
                graph = code.analysisCache.graph,
                followIdentities = true
            )
        }

        /**
         * In parallel, for each (non-subsumed) summary, compute the suspended computation which will effect the summarization
         */
        val intermediateCode = toSummarize.entries.parallelStream().filter { (_, payload) ->
            payload.summaryType !in subsumedByOuterSummary
        }.map { (where, payload) ->
            /**
             * The type of this summary indicates the expected program element we expect to find at [where]
             */
            when(payload.summaryType) {
                is NodeType.ExplicitSummary -> {
                    handleExplicitSummary(
                        where = where,
                        explicit = code.analysisCache.graph.elab(where).snarrowOrNull<InternalCallSummary>()!!,
                        summSignature = payload.specCallSummToInternalSummSig.second,
                        specCallSumm = payload.specCallSummToInternalSummSig.first,
                        expressionSummaryHandler = expressionSummaryHandler,
                        enclosingProgram = code
                    )
                }
                is NodeType.InlinedCall -> {
                    val func = getFunction(exitFinder, code.analysisCache.graph.elab(where).narrow())
                    handleInlinedSummaryApplication(
                        function = func,
                        summSignature = payload.specCallSummToInternalSummSig.second,
                        specCallSumm = payload.specCallSummToInternalSummSig.first,
                        intermediateCode = code,
                        gvn = gvn,
                        expressionSummaryHandler = expressionSummaryHandler
                    )
                }
            }
        }.patchForEach(code) {
            it(this)
        }
        return summarizeInternalFunctions(intermediateCode, summaries, expressionSummaryHandler, true)
    }

    private fun handleExplicitSummary(
        where: CmdPointer,
        explicit: InternalCallSummary,
        summSignature: CVL.SummarySignature.Internal,
        specCallSumm: SpecCallSummary.ExpressibleInCVL,
        expressionSummaryHandler: ExpressionSummaryHandler,
        enclosingProgram: CoreTACProgram
    ): (SimplePatchingProgram) -> Unit {
        return { patcher ->
            val gen = generateSummary(
                explicit,
                summSignature = summSignature,
                specCallSumm = specCallSumm,
                where = where,
                retAnnot = WrappedInlinedSummary(explicit),
                expressionSummaryHandler = expressionSummaryHandler,
                enclosingProgram = enclosingProgram
            )
            patcher.replaceCommand(where, gen)
        }
    }

    /**
     * The various types of exits seen for an internal function.
     */
    private sealed interface ExitPointType {
        /**
         * There is a single, unique exit point. [ptr] is the location of the exit annotation,
         * splitting *after* this point will give the rest of the program after function exit.
         */
        data class SimpleCommand(val ptr: CmdPointer) : ExitPointType

        /**
         * There are multiple exit points, which have a single, unique confluence point [block]. The predecessor
         * blocks of [block] that contain the exit points *only* contain code for the exit points.
         * The start of [block] gives the rest of the program after function exit.
         */
        data class ConfluenceBlock(val block: NBId) : ExitPointType
    }

    private data class InternalFunctionExitData(
        // what kind of exit point is this?
        val exitPointType: ExitPointType,
        // suffix code that does remapping required by confluence DSA
        val suffixCode: CommandWithRequiredDecls<TACCmd.Simple>,
        // the information about the function retun symbols
        val exitInfo: FunctionReturnInformation
    )

    private fun handleFunctionExits(
        function: InternalFunction,
        intermediateCode: CoreTACProgram,
        gvn: IGlobalValueNumbering
    ) : Either<InternalFunctionExitData, String> {
        val callSite = function.start.toFuncStart()!!
        val functionId = callSite.methodSignature

        val g = intermediateCode.analysisCache.graph

        val fakeNames = mutableMapOf<Int, TACSymbol.Var>()
        fun generateFakeReturnName(i: Int, f: TACSymbol.Var) : TACSymbol.Var {
            return fakeNames.computeIfAbsent(i) {
                TACKeyword.TMP(tag = f.tag, suffix = "!$i")
            }
        }


        val exitPoint = when(function.exits.size) {
            0 -> {
                val msg = "$functionId contains no non-reverting paths and may not be summarized"
                return msg.toRight()
            }
            1 -> {
                val exit = function.exits.single()
                ExitPointType.SimpleCommand(exit.ptr)
            }
            else -> {
                val confluence = function.exits.monadicMap {
                    g.succ(it.ptr.block).singleOrNull()
                }?.uniqueOrNull() ?: return "$functionId contains multiple exits and there is no apparent confluence point".toRight()
                ExitPointType.ConfluenceBlock(confluence)
            }
        }
        val isSingletonExit = exitPoint is ExitPointType.SimpleCommand

        /**
         * The intermediate result of this analysis is an [Either]. The [Either.Left] variant is used to record written variables discovered within the
         * the body of the function. The [Either.Right] is used to record exit variable aliases discovered on all flows out of a function.
         * That is, for every path out of a function, we expect to see an [Either.Right] that maps [TACSymbol.Var] to the ordinal of the return
         * value it holds (null/unmapped indicates a variable does not hold a return value). In the process function we merge these maps to infer
         * the principle variable that holds the return value of the function.
         */
        return object : VisitingWorklistIteration<CmdPointer, Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>, Either<InternalFunctionExitData, String>>() {
            private fun haltWithError(msg: String) = this.halt(msg.toRight())

            private val exitRevertBlockCache = mutableMapOf<NBId, Boolean>()

            private fun isExitRevertBlock(b: NBId) : Boolean = exitRevertBlockCache.getOrPut(b) {
                val exitBlocks = function.exits.mapToSet {
                    it.ptr.block
                }
                g.cache.reachability.get(b)?.containsAny(exitBlocks) == true
            }

            override fun process(it: CmdPointer): StepResult<CmdPointer, Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>, Either<InternalFunctionExitData, String>> {
                if(it.block in g.cache.revertBlocks && !isExitRevertBlock(it.block)) {
                    return this.cont(listOf())
                }
                val lc = g.elab(it)
                var commandResults = treapListOf<Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>>()
                if(lc.cmd is TACCmd.Simple.AssigningCmd && lc.cmd.lhs.tag != Tag.ByteMap) {
                    commandResults += lc.cmd.lhs.toLeft()
                }
                val exit = lc.maybeAnnotation(INTERNAL_FUNC_EXIT)?.takeIf {
                    it.id == callSite.id
                } ?: return StepResult.Ok(
                    next = g.succ(it),
                    result = commandResults
                )
                val accum = treapMapBuilderOf<TACSymbol.Var, Int>()
                for((i, v) in exit.rets.withIndex()) {
                    val sym = if(!g.cache.lva.isLiveAfter(it, v.s) && !isSingletonExit) {
                        generateFakeReturnName(i, v.s)
                    } else {
                        v.s
                    }
                    accum[sym] = i
                }
                val exitBlock = when(exitPoint) {
                    is ExitPointType.SimpleCommand -> {
                        if(exitPoint.ptr != it) {
                            return this.haltWithError("Internal error: found exit at a point that is not the exit point?")
                        }
                        return this.result(commandResults + accum.toRight())
                    }
                    is ExitPointType.ConfluenceBlock -> exitPoint.block
                }
                var iter = g.succ(it).singleOrNull() ?: return this.haltWithError("At exit $it from $functionId do not have straightline code?")
                while(iter.block != exitBlock) {
                    val lcIter = g.elab(iter)
                    if(lcIter.maybeAnnotation(INTERNAL_FUNC_START) != null || lcIter.maybeAnnotation(INTERNAL_FUNC_EXIT) != null) {
                        return this.haltWithError("At $lcIter reached from exit $it from $functionId have another function stack operation")
                    }
                    if(lcIter.cmd is TACCmd.Simple.AssigningCmd) {
                        accum.remove(lcIter.cmd.lhs)
                        commandResults += lcIter.cmd.lhs.toLeft()
                        val key = lcIter.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs?.let {
                            it as? TACExpr.Sym.Var
                        }?.s?.let(accum::get)
                        if(key != null) {
                            accum[lcIter.cmd.lhs] = key
                        }
                    }
                    iter = g.succ(iter).singleOrNull() ?: return this.haltWithError("At point $lcIter reached from exit $it from $functionId, have branching")
                }
                return this.result(commandResults + accum.toRight())
            }

            override fun reduce(results: List<Either<TACSymbol.Var, Map<TACSymbol.Var, Int>>>): Either<InternalFunctionExitData, String> {
                val (writtenVars, exitStates) = results.partitionMap { it }
                // alias source is the location from which we should find aliases for written variables that are not exit vars
                val (livenessCheck, aliasSource) = when(exitPoint) {
                    is ExitPointType.SimpleCommand -> {
                        { v: TACSymbol.Var ->
                            g.cache.lva.isLiveAfter(exitPoint.ptr, v)
                        } to exitPoint.ptr
                    }
                    is ExitPointType.ConfluenceBlock -> {
                        { v: TACSymbol.Var ->
                            g.cache.lva.isLiveBefore(CmdPointer(exitPoint.block, 0), v)
                        } to CmdPointer(exitPoint.block, 0)
                    }
                }
                val exitRepresentative = function.exits.first().wrapped.maybeAnnotation(INTERNAL_FUNC_EXIT)!!
                val exitSize = exitRepresentative.rets.size
                val exits = mutableListOf<TACSymbol.Var>()
                for(ind in 0 until exitSize) {
                    val members = exitStates.map { m ->
                        m.keysMatching { _, i -> i == ind }.toTreapSet()
                    }
                    // we do this check to accomodate the "dead" dummy exit variable we generate
                    val singleChoice = members.uniqueOrNull()?.singleOrNull()
                    if(singleChoice != null) {
                        exits.add(singleChoice)
                        continue
                    }
                    // but if there is a consistent *LIVE* variable (due to remapping) pick that one
                    val withLivenessFiltering = members.map { cand ->
                        cand.filter(livenessCheck)
                    }
                    val singleLiveChoice = withLivenessFiltering.uniqueOrNull()?.singleOrNull()
                    if(singleLiveChoice != null) {
                        exits.add(singleLiveChoice)
                        continue
                    }
                    // okay, now that we have a bunch of live variables that are still candidates, see if there's one
                    // common one across all branches
                    var cand = withLivenessFiltering.first().toTreapSet()
                    for(other in withLivenessFiltering.subList(1, withLivenessFiltering.size)) {
                        cand = cand intersect other
                    }
                    val uniqueCandidate = cand.singleOrNull() ?: return "Could not infer principle return variable for output position $ind for $functionId".toRight()
                    exits.add(uniqueCandidate)
                }
                val suffix = MutableCommandWithRequiredDecls<TACCmd.Simple>()
                for(v in writtenVars) {
                    if(!livenessCheck(v)) {
                        continue
                    }
                    if(v in exits) {
                        continue
                    }
                    val ent = v.meta.find(TACSymbol.Var.KEYWORD_ENTRY)
                    // ignore keyword entries (tacM etc.) that are written within the summarized body.
                    // however, for some reason all stack variables are also annotated with the keyword entry "L"
                    // for "stack height" so explicitly ignore that...
                    if(ent != null && ent.maybeTACKeywordOrdinal != TACKeyword.STACK_HEIGHT.ordinal) {
                        continue
                    }
                    // is there an alias that exists at the entrance to the function? If so, use that
                    val aliases = gvn.findCopiesAt(function.start.ptr, aliasSource to v)
                    if(aliases.isEmpty()) {
                        return "Variable $v was written within $functionId but could not find a new value for it outside the function".toRight()
                    }
                    val alias = aliases.first()
                    suffix.extend(alias)
                    suffix.extend(v)
                    suffix.extend(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = v, rhs = alias.asSym()))
                }
                val completePayload = InternalFunctionExitData(
                    exitPointType = exitPoint,
                    exitInfo = ConfluenceSummary(exitRepresentative.rets.mapIndexed { idx, internalFuncRet ->
                        internalFuncRet.copy(
                            s = exits[idx]
                        )
                    }),
                    suffixCode = suffix.toCommandWithRequiredDecls()
                )
                return completePayload.toLeft()
            }
        }.submit(listOf(function.start.ptr))
    }

    /**
     * This version is a little bit more complicated, as we have to compute the confluence variables in the case of multiple
     * return sites.
     */
    private fun handleInlinedSummaryApplication(
        function: InternalFunction,
        summSignature: CVL.SummarySignature.Internal,
        specCallSumm: SpecCallSummary.ExpressibleInCVL,
        intermediateCode: CoreTACProgram,
        gvn: IGlobalValueNumbering,
        expressionSummaryHandler: ExpressionSummaryHandler
    ): (SimplePatchingProgram) -> Unit {
        val callSite = function.start.toFuncStart()!!
        val functionId = callSite.methodSignature

        val (exitsite, suffix, rets) = this.handleFunctionExits(function, intermediateCode, gvn).leftOrElse { msg ->
            Logger.alwaysError(msg)
            throw IllegalStateException(msg)
        }

        val args = callSite.args.sortedBy { fArg ->
            callSite.getArgPos(fArg.offset)
        }

        return { patching: SimplePatchingProgram ->
            val remove = patching.splitBlockAfter(function.start.ptr)
            val exitBlock = when(exitsite) {
                is ExitPointType.ConfluenceBlock -> exitsite.block
                /**
                 * [analysis.icfg.InternalSummarizer.ExitPointType.SimpleCommand.ptr] is the location of the exit annotation,
                 * so split AFTER it.
                 */
                is ExitPointType.SimpleCommand -> patching.splitBlockAfter(exitsite.ptr)
            }
            val summaryCmds =
                generateSummary(
                    /**
                     * This allows us to keep args looking non-null, and only throw if it turns out we couldn't compute it
                     * AND it was necessary (for EXP summaries)
                     *
                     * (a previous implementation would throw if args was null AND we we were using an expression summary.
                     * That check was here, in this function, and then we'd non-null assert in the [generateSummary]
                     * function, confident our caller had ensured it would be non-null for us.
                     *
                     * This is more explicit, and better)
                     */
                    object : InternalFunctionStartInfo {
                        override val methodSignature: QualifiedMethodSignature
                            get() = functionId
                        override val callSiteSrc: TACMetaInfo?
                            get() = callSite.callSiteSrc
                        override val args: List<InternalFuncArg>
                            get() = args

                    },
                    summSignature,
                    specCallSumm,
                    function.start.ptr,
                    rets,
                    expressionSummaryHandler,
                    intermediateCode
                ).appendToSinks(suffix)
            patching.replaceCommand(function.start.ptr, summaryCmds)

            patching.consolidateEdges(exitBlock, listOf(remove))


            logger.info {
                "Summarizing internal function $functionId with $specCallSumm " +
                    "just before $exitsite in ${intermediateCode.name}"
            }
        }
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
                id = Allocator.getFreshId(Allocator.Id.INTERNAL_CALL_SUMMARY)
            )
            return Resummarized(CommandWithRequiredDecls(listOf(
                TACCmd.Simple.SummaryCmd(
                    theSummary,
                    meta = MetaMap()
                )
            ), theSummary.variables).toCode(where.block.getCallId()))
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
         * The external summaries affect inlining decisions, but only whetther a function should be inlined or not (we still
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
    }
}
