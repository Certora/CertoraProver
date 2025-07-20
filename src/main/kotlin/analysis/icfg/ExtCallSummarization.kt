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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.icfg

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.CmdPointer
import analysis.LTACCmdView
import analysis.narrow
import com.certora.collect.*
import datastructures.stdcollections.*
import instrumentation.calls.CallConvention
import instrumentation.calls.CallOutput
import log.*
import scene.IScene
import scene.ITACMethod
import scene.PrecompiledContractCode
import statistics.CALL_RESOLUTION_FAIL_KEY
import statistics.CALL_RESOLUTION_KEY
import statistics.CALL_RESOLUTION_SUCCESS_KEY
import statistics.recordSuccessFailNumbers
import tac.MetaMap
import tac.NBId
import tac.Tag
import tac.isSubtypeOf
import utils.*
import vc.data.*
import java.io.Serializable
import java.math.BigInteger
import java.util.stream.Collectors


object ExtCallSummarization {
    private fun callCoreToSummary(
            where: LTACCmdView<TACCmd.Simple.CallCore>,
            res: CallGraphBuilder.AnalysisResults,
            simplifier: ExpressionSimplifier
    ) : ICallCoreSummary {
        val ptr = where.ptr
        val t = where.cmd
        // usually sizes are computed as a subtraction
        val inSz = res.inoutBuffers.inputSizeResolved.get(ptr)?.inputSize?.let {
            if (it.isConstant) {
                it.c
            } else {
                null
            }
        }?.asTACSymbol() ?: simplifier.simplify(t.inSize, ptr, true)
                .getAs<TACExpr.Sym>()?.s
                ?: t.inSize

        val outSz = simplifier.simplify(t.outSize, ptr, true).getAs<TACExpr.Sym>()?.s ?: t.outSize
        val value = simplifier.simplify(t.value, ptr, true).getAsConst()?.asTACSymbol() ?: t.value
        if(ptr in res.constructorCalls.map) {
            val constructorSig = res.constructorCalls.map[ptr]!!
            return CreateSummary(
                toVar = t.to,
                valueVar = value,
                gasVar = t.gas,
                inSize = inSz,
                inBase = t.inBase,
                inOffset = t.inOffset,
                constructorSig = constructorSig.sig,
                createId = constructorSig.createId,
                summaryId = Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES),
                origCallcore = t
            )
        } else {
            fun Set<BigInteger>.includeFallback() =
                letIf(isEmpty() && inSz == TACSymbol.Zero) {
                    setOf(null)
                }

            return CallSummary(
                toVar = t.to,
                valueVar = value,
                gasVar = t.gas,
                inOffset = t.inOffset,
                inSize = inSz,
                inBase = t.inBase,
                outOffset = t.outOffset,
                outSize = outSz,
                outBase = t.outBase,
                callType = t.callType,
                callTarget = res.calleeResolution.resolution[ptr] ?: setOf(CallGraphBuilder.CalledContract.Unresolved),
                sigResolution = res.sigResolution.resolution[ptr].orEmpty().includeFallback(),
                callConvention = CallConvention(
                    res.callInputResolution.resolution[ptr] ?: CallInput(
                        t.inBase.asSym(),
                        t.inOffset.asSym(),
                        inSz.asSym()
                    ),
                    CallOutput(t.outBase, t.outOffset, outSz)
                ),
                origCallcore = t,
                summaryId = res.callNumbering.callNumbering[ptr] ?: Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES),
                cannotBeInlined = null,
                symbolicSigResolution = res.symbolicSigResolution.m[ptr]
            )
        }
    }

    fun annotateCallsAndReturns(scene: IScene, method: ITACMethod) {
        val analysisResults = CallGraphBuilder.doSigAndInputAndCalleeAnalysis(method, scene).analysisResults
        annotateCallsAndReturnsWithAnalysisResults(method, analysisResults)
    }

    private fun annotateCallsAndReturnsWithAnalysisResults(method: ITACMethod, analysisResults: CallGraphBuilder.AnalysisResults) {
        val icore = method.code as CoreTACProgram
        val patching = icore.toPatchingProgram()
        annotateCallsAndReturnsWithAnalysisResultsWithPatching(patching, icore, analysisResults, emptySet())
        method.updateCode(patching)
    }

    /**
     * [updatedCallCoreCmds] is a set of CmdPointers that are no longer [TACCmd.Simple.CallCore] in [patch]
     */
    fun annotateCallsAndReturnsWithAnalysisResultsWithPatching(
        patching: SimplePatchingProgram,
        icore: CoreTACProgram,
        analysisResults: CallGraphBuilder.AnalysisResults,
        updatedCallCoreCmds: Set<CmdPointer>
    ) {
        val graph = icore.analysisCache.graph

        val metaUpdates = mutableMapOf<CmdPointer, MetaMap>()
        val simplifier = ExpressionSimplifier(g = graph, customDefAnalysis = graph.cache.def)
        val res = graph.commands.parallelStream().filter {
            it.cmd is TACCmd.Simple.CallCore && it.ptr !in updatedCallCoreCmds
        }.map {
            it.narrow<TACCmd.Simple.CallCore>()
        }.map {
            it to callCoreToSummary(it, analysisResults, simplifier)
        }.collect(Collectors.toList())

        Logger.regressionPrinter { print ->
            res.filter {
                it.second is CallSummary
            }.sortedWith { a, b ->
                val blockCmp = a.first.ptr.block.compareTo(b.first.ptr.block)
                if(blockCmp != 0) {
                    return@sortedWith blockCmp
                }
                /*
                   Yes yes yes, this is actually unsafe for arbitrary integers, but these are positions in a block, which
                   will get nowhere close to being in danger of the overflow this is susceptible to
                 */
                a.first.ptr.pos - b.first.ptr.pos
            }.forEachIndexed { ind, blk ->
                val summ = blk.second as CallSummary
                print {
                    "In ${icore.name}, call summary with ordinal $ind resolution stats; is zero length: ${summ.inSize == TACSymbol.Zero}, has signature: ${summ.sigResolution.singleOrNull() != null}, is precompiled: ${
                        (summ.callTarget.map { (it as? CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress)?.contractId?.let { PrecompiledContractCode.getPrecompiledImplemented(it) } != null }).joinToString(prefix = "(", postfix = ")")
                    }"
                }
            }
        }

        val killedCallIds = updatedCallCoreCmds.mapNotNullToSet {
            analysisResults.callNumbering.callNumbering[it]
        }

        // record statistics on sighashes resolved percentage in this method
        res.mapNotNull {
            it.second as? CallSummary
        }.let { all -> all.size to
            // count success: non-empty resolution
            all.count { it.sigResolution.isNotEmpty() }
        }.let { (all, success) ->
            recordSuccessFailNumbers(success, all-success, CALL_RESOLUTION_SUCCESS_KEY, CALL_RESOLUTION_FAIL_KEY, CALL_RESOLUTION_KEY, icore.name)
        }

        fun MutableMap<CmdPointer, MetaMap>.additiveMerge(c: CmdPointer, m: MetaMap) = this.merge(c, m) { a, b -> a + b }

        val callToSummary = res.map {
            it.first.ptr to it.second
        }.toMap()

        val graphPointers = graph.pointers.toSet()

        // patch the program to assign to all decomposed args
        for((ptr, summ) in callToSummary) {
            if(summ !is CallSummary) {
                continue
            }
            val decomposePatchInfo = assignValuesToDecomposedArgsCmd(
                analysisResults.callInputResolution.decomposedInfo[ptr] ?: setOf()
            )
            summ.callConvention.input.rangeToDecomposedArg.values.mapNotNull { it.getSymbol() as? TACSymbol.Var }.toSet().let { shouldBeAssigned ->
                if (shouldBeAssigned != decomposePatchInfo.decomposedArgsAssigned) {
                    // if this happens, something went wrong in CallgraphBuilder! It did not assign any source writes to a decomposed args
                    throw IllegalStateException("Should patch all variable decomposed args $shouldBeAssigned but only assigned ${decomposePatchInfo.decomposedArgsAssigned}")
                }
            }
            decomposePatchInfo.patches.forEach { (ptr, assignExpCmds) ->
                // let's make sure that our ptr has the right call id
                if (ptr !in graphPointers) {
                    throw IllegalStateException("Cmd pointer ${ptr} associated with $assignExpCmds does not exist")
                }
                patching.addBefore(ptr, assignExpCmds.toList())
                assignExpCmds.forEach { cmd ->
                    patching.addVarDecl(cmd.lhs)
                }
            }
        }
        analysisResults.calleeReturnReadResolution.readToCallReturn.forEach { (where, returnPointer) ->
            val summaryId = callToSummary[returnPointer.lastCall]?.summaryId ?: return@forEach
            metaUpdates.additiveMerge(
                where,
                MetaMap(
                    TACMeta.RETURN_READ to CallSummaryReturnReadInfo(
                        summaryId,
                        returnPointer.offset
                    )
                )
            )
        }
        val returnWriteSnippets = mutableMapOf<CmdPointer, SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite>()
        analysisResults.returnInformation.returnLoc.forEach { (t, u) ->
            val returnWrite = Allocator.getFreshId(Allocator.Id.RETURN_SUMMARIES)
            metaUpdates.additiveMerge(
                t,
                MetaMap(TACMeta.RETURN_SITE to returnWrite)
            )
            metaUpdates.additiveMerge(
                t,
                MetaMap(
                    TACMeta.RETURN_LINKING to ReturnLinkingInfo(
                        u.addressReturn.filter { (_, c) ->
                           c !is CallGraphBuilder.CalledContract.SymbolicOutput || c.which !in killedCallIds
                        },
                        u.returnSizeLowerBound
                    ))
            )
            u.returnWrites.entries.forEach { (offset, v) ->
                v.forEach { lSym ->
                    metaUpdates.additiveMerge(lSym.ptr,
                        MetaMap(
                            TACMeta.RETURN_WRITE to CallSummaryReturnWriteInfo(
                                summaryId = returnWrite,
                                offset = offset
                            ))
                    )
                    returnWriteSnippets[lSym.ptr] =
                        SnippetCmd.EVMSnippetCmd.EVMFunctionReturnWrite(
                            returnbufOffset = offset,
                            returnValueSym = lSym.symbol,
                            callIndex = NBId.ROOT_CALL_ID
                        )
                }
            }
        }

        analysisResults.readNumbering.numbering.forEach { (where, id) ->
            metaUpdates.additiveMerge(where, MetaMap(
                CallGraphBuilder.ContractStorageRead.META_KEY to CallGraphBuilder.ContractStorageRead(id)
            ))
        }

        for ((where, meta) in metaUpdates) {
            if (where in callToSummary) {
                continue
            }
            if (where in returnWriteSnippets) {
                patching.replace(where) { cmd ->
                    listOf(
                        cmd.mapMeta {
                            it + meta
                        },
                        returnWriteSnippets.getValue(where).toAnnotation(icore.destructiveOptimizations)
                    )
                }
            } else {
                patching.update(where) { cmd ->
                    cmd.mapMeta {
                        it + meta
                    }
                }
            }
        }
        for((where, summ) in callToSummary) {
            patching.update(where) {
                TACCmd.Simple.SummaryCmd(
                    summ = summ,
                    meta = it.meta
                )
            }
        }

        for((where, id) in analysisResults.logEncodes.map) {
            patching.replace(where) { cmd: TACCmd.Simple ->
                listOf(cmd.plusMeta(TACMeta.LOG_ENCODING_ID, id))
            }
        }
    }

    @KSerializable
    @GenerateRemapper
    data class CallSummaryReturnReadInfo(@GeneratedBy(Allocator.Id.RETURN_SUMMARIES) val summaryId: Int, val v: BigInteger) : AmbiSerializable, RemapperEntity<CallSummaryReturnReadInfo>

    @KSerializable
    @GenerateRemapper
    @Treapable
    data class CallSummaryReturnWriteInfo(@GeneratedBy(Allocator.Id.RETURN_SUMMARIES) val summaryId: Int, val offset: BigInteger) : AmbiSerializable, RemapperEntity<CallSummaryReturnWriteInfo>

    @GenerateRemapper
    data class ReturnLinkingInfo(
        val byOffset: Map<BigInteger, CallGraphBuilder.CalledContract>,
        val returnSize: BigInteger
    ) : Serializable, TransformableVarEntity<ReturnLinkingInfo>, RemapperEntity<ReturnLinkingInfo> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ReturnLinkingInfo =
            copy(byOffset = byOffset.mapValues { it.value.transformSymbols(f) })
    }


    private data class DecomposeArgVariablesPatchInfo(
            val patches: Map<CmdPointer, Set<TACCmd.Simple.AssigningCmd.AssignExpCmd>>,
            val decomposedArgsAssigned: Set<TACSymbol.Var>
    ) {
        init {
            patches.flatMap { patches -> patches.value.map { c -> c.lhs } }.toSet().let { actuallyAssigned ->
                if (actuallyAssigned != decomposedArgsAssigned) {
                    throw IllegalStateException("Set of decomposed args assigned and actual assignments do not agree: assigned ${actuallyAssigned} and expected ${decomposedArgsAssigned}")
                }
            }
        }
    }

    // Generates the set of patches needed to establish decomposed variable args
    private fun assignValuesToDecomposedArgsCmd(
            decomposedArgVariablesWithSourceWrites: Set<DecomposedArgVariableWithSourceWrites>
    ): DecomposeArgVariablesPatchInfo {
        val toRetPatches = mutableMapOf<CmdPointer, MutableSet<TACCmd.Simple.AssigningCmd.AssignExpCmd>>()
        val toRetAssigned = mutableSetOf<TACSymbol.Var>()
        val tags = mutableMapOf<CmdPointer, Tag>()

        decomposedArgVariablesWithSourceWrites.forEach { (scratchWriteSymbols, decompSymVar) ->
            toRetAssigned.add(decompSymVar)
            scratchWriteSymbols.forEach {
                check(it.ptr !in tags || tags[it.ptr] == decompSymVar.tag) { "Unexpectedly, was about to overwrite the value of ${it.ptr} in $toRetPatches with new tag ${decompSymVar.tag}" }
                tags[it.ptr] = decompSymVar.tag
                val cmdValue = it.symbol
                if (cmdValue.tag == Tag.Bool) {
                    toRetPatches.getOrPut(it.ptr, { mutableSetOf<TACCmd.Simple.AssigningCmd.AssignExpCmd>() }).add(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = decompSymVar, rhs = cmdValue.asSym().ensureIntOrBvForAssignmentTo(decompSymVar)
                            )
                    )
                } else {
                    check(cmdValue.tag.isSubtypeOf(decompSymVar.tag)) {
                        "Expected that ${cmdValue} will have a subtype of $decompSymVar, but instead got " +
                                "${cmdValue.tag} and ${decompSymVar.tag}, respectively"
                    }
                    toRetPatches.getOrPut(it.ptr, { mutableSetOf<TACCmd.Simple.AssigningCmd.AssignExpCmd>() }).add(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = decompSymVar, rhs = cmdValue
                            )
                    )
                }
            }
        }

        return DecomposeArgVariablesPatchInfo(
                toRetPatches,
                toRetAssigned
        )
    }

}
