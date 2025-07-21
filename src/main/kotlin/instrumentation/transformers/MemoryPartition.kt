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

package instrumentation.transformers

import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.ip.*
import analysis.maybeAnnotation
import analysis.narrow
import analysis.pta.LoopCopyAnalysis
import log.*
import kotlin.streams.*
import optimizer.OptimizeBasedOnPointsToAnalysis
import optimizer.PTA_FAILURE_SOURCE
import optimizer.UNINDEXED_PARTITION
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.TACMethod
import statistics.INSTRUMENTATION_SUCCESS_STATS_KEY
import statistics.recordSuccess
import tac.CallId
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.OPTIMIZE)

/**
 * Partitions the monolithic tacM byte array into a family of tacMs. This optimization
 * must be run *after* any memory analyses are run: future analyses will certainly break
 * due to the monolithic tacM no longer appearing in the code base.
 *
 * The partitioning here is pure instrumentation: the actual partitions are computed previously in
 * [OptimizeBasedOnPointsToAnalysis]. In that pass, we compute an equivalence class of points-to nodes such that
 * if at any memory access two points to nodes could alias, they share an equivalence class. Each instance of tacM
 * is then tagged with a unique id for each equivalence class computed in this way. In *this* pass, we rename
 * the tacM instance to append the partition id. From the (assumed) soundness of the points to analysis, we will have
 * that if two accesses of tacM can never alias, they must have different partitions, and thus their reads/writes
 * will never "overlap", making the splitting of the basemap sound.
 *
 * If *any* reference to tacM is untagged with a memory partition, we conservatively refuse to partition.
 */
object MemoryPartition {
    fun partition(method: TACMethod) : CoreTACProgram {
        val prog = method.code as CoreTACProgram
        fun exit(rangeWithMsgDetails: FailureInfo, hint: String? = null): CoreTACProgram {
            CVTAlertReporter.reportAlert(
                CVTAlertType.MEMORY_PARITIONING,
                CVTAlertSeverity.WARNING,
                rangeWithMsgDetails.range,
                "Memory partitioning failed while analyzing contract ${method.getContainingContract().name}, " +
                    "function ${method.soliditySignature ?: method.name}. ${rangeWithMsgDetails.additionalUserFacingMessage} This can lead to longer running times. ",
                hint
            )
            recordSuccess(method.toString(), INSTRUMENTATION_SUCCESS_STATS_KEY, "MEMORY_PARTITION", false)
            return prog
        }
        fun exit(where: LTACCmd): CoreTACProgram {
            logger.warn {
                "Memory partitioning failed while analyzing ${prog.name} @ ${where.ptr}"
            }
            val rangeWithMsgDetails = getSourceHintWithRange(where, prog.analysisCache.graph, method)
            return exit(rangeWithMsgDetails)
        }
        val toRewrite = mutableListOf<LTACCmd>()
        val failureSources = prog
            .ltacStream()
            .mapNotNull { it.maybeAnnotation(PTA_FAILURE_SOURCE) }
            .toList()
        if (failureSources.isNotEmpty()) {
            logger.warn {
                val sourceLines = failureSources.joinToString("\n").prependIndent("    at ")
                "Memory partitioning failed while analyzing ${prog.name}\n$sourceLines"
            }

            val (failureInfo, hint) =
                failureSources.singleOrNull()?.let { source -> FailureInfo.AdditionalFailureInfo(additionalUserFacingMessage = "Cause 1 of ${failureSources.size}", range = source.second) to source.first.condense(1) }
                    ?: (FailureInfo.AdditionalFailureInfo(additionalUserFacingMessage = "No source code information given for the location.", range = null) to null)

            return exit(failureInfo, hint)
        }
        for(ltacCmd in prog.ltacStream().iterator()) {
            val mem = ltacCmd.cmd.getFreeVarsOfRhs().mapNotNull {
                it.takeIf { it == TACKeyword.MEMORY.toVar() }
            }.takeIf { it.isNotEmpty() }?.takeIf {
                ltacCmd.cmd !is TACCmd.Simple.SummaryCmd || ltacCmd.cmd.summ !is LoopCopyAnalysis.LoopCopySummary ||
                    OptimizeBasedOnPointsToAnalysis.SUMMARY_VALIDATED in ltacCmd.cmd.meta
            } ?: continue
            if(mem.any { UNINDEXED_PARTITION !in it.meta }) {
                if((ltacCmd.cmd is TACCmd.Simple.RevertCmd && ltacCmd.cmd.o2 == BigInteger.ZERO.asTACSymbol()) ||
                    (ltacCmd.cmd is TACCmd.Simple.ReturnCmd && ltacCmd.cmd.o2 == BigInteger.ZERO.asTACSymbol()) ||
                    (ltacCmd.cmd is TACCmd.Simple.ByteLongCopy && ltacCmd.cmd.length == BigInteger.ZERO.asTACSymbol())) {
                    continue
                }
                logger.info {
                    "At $ltacCmd in $method, have reference to memory without partition id, refusing to split"
                }
                return exit(ltacCmd)
            }
            toRewrite.add(ltacCmd)
        }
        val patch = prog.toPatchingProgram()
        val partitionVar = mutableMapOf<Int, TACSymbol.Var>()
        fun Int.toSplit(metaBase: TACSymbol.Var) = partitionVar.computeIfAbsent(this) {
            val ret = TACSymbol.Var(
                TACKeyword.MEMORY.extendName("!${it}"),
                tag = Tag.ByteMap,
            )
            patch.addVarDecl(ret)
            ret
        }.copy(meta = metaBase.meta)
        fun <T: TACCmd.Simple> SimplePatchingProgram.replaceAt(extract: (T) -> TACSymbol.Var, where: LTACCmdView<T>, replace: (TACSymbol.Var, T) -> TACCmd.Simple) {
            val mem = extract(where.cmd)
            val id = mem.meta.find(UNINDEXED_PARTITION)?.id
            check(id != null) {
                "Null partition at $where, this should never happen"
            }
            this.replaceCommand(where.ptr, listOf(replace(id.toSplit(mem), where.cmd)))
        }
        fun <T: TACCmd.Simple> SimplePatchingProgram.replaceAt(extract: (T) -> TACSymbol, where: LTACCmdView<T>, replace: (TACSymbol.Var, T) -> TACCmd.Simple) {
            this.replaceAt({ extract(it) as TACSymbol.Var }, where, replace)
        }
        val mem = TACKeyword.MEMORY.toVar()

        fun InternalFuncValueLocation.PointerLayoutInfo.toPartition() : InternalFuncValueLocation.PointerLayoutInfo {
            return when(this) {
                is InternalFuncValueLocation.PrimitiveField -> {
                    InternalFuncValueLocation.PrimitiveField(
                        partitionVariable = this.partitionVariable.meta.find(UNINDEXED_PARTITION)!!.id.toSplit(this.partitionVariable)
                    )
                }
                is InternalFuncValueLocation.ReferenceField -> {
                    InternalFuncValueLocation.ReferenceField(
                        partitionVariable = this.partitionVariable.meta.find(UNINDEXED_PARTITION)!!.id.toSplit(this.partitionVariable),
                        fields = fields.mapValues { (_, subFields) ->
                            subFields.toPartition()
                        }
                    )
                }
            }
        }

        fun InternalFuncValueLocation?.updatePartitions() = when(this) {
            InternalFuncValueLocation.Pointer,
            InternalFuncValueLocation.Scalar -> this
            is InternalFuncValueLocation.PointerWithFields -> this.copy(
                layoutForFields = this.layoutForFields.mapValues { (_, it) ->
                    it.toPartition()
                }
            )
            is InternalFuncValueLocation.UnsplitPointer -> {
                this.copy(
                    monolithicArray = this.monolithicArray.meta.find(UNINDEXED_PARTITION)!!.id.toSplit(this.monolithicArray)
                )
            }
            null -> null
        }

        fun List<InternalFuncArg>.updatePartitions() = this.map {
            it.copy(
                location = it.location.updatePartitions()
            )
        }

        fun List<InternalFuncRet>.updatePartitions() = this.map { x ->
            x.copy(
                location = x.location.updatePartitions()
            )
        }

        for(lc in toRewrite) {
            when(lc.cmd) {
                is TACCmd.Simple.AnnotationCmd -> {

                    if(lc.cmd.annot.k == INTERNAL_FUNC_EXIT) {
                        val exit = lc.cmd.annot.v as InternalFuncExitAnnotation
                        /*
                        we are recording for each field in the returned struct(s) what memory map is used for those fields
                        (memory partitioning means that disjoint fields can use different tacM base maps, which means
                        read-over-write chains can be minimized). But if we want to add field writes later
                        (say, during internal function summarization) we have to "remember" the mapping from field position to these basemaps.
                         */
                        val newRets = exit.rets.updatePartitions()
                        patch.replaceCommand(
                            lc.ptr, listOf(
                                lc.cmd.copy(
                                    annot = TACCmd.Simple.AnnotationCmd.Annotation(
                                        INTERNAL_FUNC_EXIT,
                                        exit.copy(
                                            rets = newRets
                                        )
                                    )
                                )
                            )
                        )
                    } else if(lc.cmd.annot.k == INTERNAL_FUNC_START) {
                        val enter = lc.cmd.annot.v as InternalFuncStartAnnotation
                        patch.replaceCommand(
                            lc.ptr, listOf(
                                lc.cmd.copy(
                                    annot = TACCmd.Simple.AnnotationCmd.Annotation(
                                        INTERNAL_FUNC_START,
                                        enter.copy(
                                            args = enter.args.updatePartitions()
                                        )
                                    )
                                )
                            )
                        )
                    } else {
                        throw IllegalStateException("Found unexpected annotation with memory at $lc")
                    }
                }
                is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                    check(lc.cmd.op1 != mem && lc.cmd.op2 != mem) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(
                        TACCmd.Simple.AssigningCmd.AssignSha3Cmd::memBaseMap,
                        lc.narrow()
                    ) { m, c ->
                        c.copy(memBaseMap = m)
                    }
                }
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    if(lc.cmd.rhs !is TACExpr.LongStore) {
                        logger.warn {
                            "At $lc in $method, have expression that references memory that is not a long copy"
                        }
                        return exit(lc)
                    }
                    if(lc.cmd.rhs.srcMap !is TACExpr.Sym.Var) {
                        logger.info {
                            "At $lc in $method, long copy source is not a variable"
                        }
                        return exit(lc)
                    }
                    if (listOf(lc.cmd.rhs.dstOffset, lc.cmd.rhs.length, lc.cmd.rhs.srcOffset, lc.cmd.rhs.dstMap).any {
                            it.usesVar(mem)
                        }) {
                        logger.info {
                            "Unsupported memory reference at $lc in $method"
                        }
                        return exit(lc)
                    }
                    patch.replaceAt({ _: TACCmd.Simple.AssigningCmd.AssignExpCmd -> lc.cmd.rhs.srcMap.s }, lc.narrow()) { m, c ->
                        c.copy(
                            rhs = lc.cmd.rhs.copy(
                                srcMap = m.asSym()
                            )
                        )
                    }
                }
                is TACCmd.Simple.AssigningCmd.ByteStore -> {
                    check(lc.cmd.base == mem && lc.cmd.loc != mem && lc.cmd.value != mem) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.AssigningCmd.ByteStore::base, lc.narrow()) { m, c ->
                        c.copy(base = m)
                    }
                }
                is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                    check(lc.cmd.base == mem && lc.cmd.loc != mem && lc.cmd.value != mem)
                    patch.replaceAt(TACCmd.Simple.AssigningCmd.ByteStoreSingle::base, lc.narrow()) { m, c ->
                        c.copy(base = m)
                    }
                }
                is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                    check(lc.cmd.base == mem && lc.cmd.loc != mem) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.AssigningCmd.ByteLoad::base, lc.narrow()) { m, c ->
                        c.copy(base = m)
                    }
                }
                is TACCmd.Simple.SummaryCmd -> {
                    val summ = lc.cmd.summ
                    if(summ is LoopCopyAnalysis.LoopCopySummary) {
                        patch.replaceCommand(lc.ptr, listOf(lc.cmd.copy(summ = summ.copy(
                            destMap = summ.destMap.meta.find(UNINDEXED_PARTITION)!!.id.toSplit(summ.destMap),
                            sourceMap = summ.sourceMap.meta.find(UNINDEXED_PARTITION)?.id?.toSplit(summ.sourceMap) ?: summ.sourceMap
                        ))))
                        continue
                    }
                    if(summ is InternalCallSummary) {
                        patch.replaceCommand(lc.ptr, listOf(
                            lc.cmd.copy(
                                summ = summ.copy(
                                    internalArgs = summ.internalArgs.updatePartitions(),
                                    internalExits = summ.internalExits.updatePartitions()
                                )
                            )
                        ))
                        continue
                    }
                    if (summ !is ICallCoreSummary) {
                        continue
                    }
                    check(summ.outBase == mem && summ.inBase == mem && mem !in listOf(
                        summ.gasVar, summ.inSize, summ.inOffset, summ.outOffset, summ.outSize,
                        summ.toVar, summ.valueVar
                    ) && (summ.origCallcore == null || (summ.origCallcore!!.inBase == mem && summ.origCallcore!!.outBase == mem))) {
                        "Unexpected (non)occurrence of memory in $lc: memory must appear only in basemaps"
                    }
                    val inBaseId = (summ.inBase as TACSymbol.Var).meta.find(UNINDEXED_PARTITION)!!.id
                    val inBase = inBaseId.toSplit(summ.inBase as TACSymbol.Var)
                    val partitionedSumm = when (summ) {
                        is CallSummary -> {
                            val outBaseId = summ.outBase.meta.find(UNINDEXED_PARTITION)!!.id
                            val outBase = outBaseId.toSplit(summ.outBase)
                            summ.copy(
                                inBase = inBase,
                                outBase = outBase,
                                origCallcore = summ.origCallcore?.copy(
                                    inBase = inBase,
                                    outBase = outBase
                                ),
                                callConvention = summ.callConvention.copy(
                                    input = summ.callConvention.input.copy(
                                        baseVar = inBase.asSym()
                                    ),
                                    rawOut = summ.callConvention.rawOut.copy(
                                        base = outBase
                                    )
                                )
                            )
                        }

                        is CreateSummary -> {
                            summ.copy(
                                inBase = inBase,
                                origCallcore = summ.origCallcore.copy(
                                    inBase = inBase,
                                ),
                            )
                        }
                    }
                    val c = lc.cmd
                    patch.replaceCommand(lc.ptr,
                        listOf(c.copy(summ = partitionedSumm))
                    )
                }
                is TACCmd.Simple.ByteLongCopy -> {
                    check(mem !in listOf(lc.cmd.dstOffset, lc.cmd.length, lc.cmd.srcOffset)) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    if (lc.cmd.dstBase == mem) {
                        patch.replaceAt(TACCmd.Simple.ByteLongCopy::dstBase, lc.narrow()) { m, c ->
                            c.copy(dstBase = m)
                        }
                    }
                    if (lc.cmd.srcBase == mem) {
                        patch.replaceAt(TACCmd.Simple.ByteLongCopy::srcBase, lc.narrow()) { m, c ->
                            c.copy(srcBase = m)
                        }
                    }
                }
                is TACCmd.Simple.LogCmd -> {
                    check(lc.cmd.memBaseMap == mem && mem !in lc.cmd.args) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.LogCmd::memBaseMap, lc.narrow()) { m, c ->
                        c.copy(memBaseMap = m)
                    }
                }
                is TACCmd.Simple.RevertCmd -> {
                    check(lc.cmd.base == mem && lc.cmd.o1 != mem && lc.cmd.o2 != mem) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.RevertCmd::base, lc.narrow()) { m, c ->
                        c.copy(base = m)
                    }
                }
                is TACCmd.Simple.ReturnCmd -> {
                    check(lc.cmd.memBaseMap == mem && lc.cmd.o1 != mem && lc.cmd.o2 != mem) {
                        "Unexpected occurrence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.ReturnCmd::memBaseMap, lc.narrow()) { m, c ->
                        c.copy(memBaseMap = m)
                    }
                }
                is TACCmd.Simple.CallCore -> {
                    check(lc.cmd.inBase == mem && lc.cmd.outBase == mem && mem !in listOf(
                        lc.cmd.gas, lc.cmd.outSize, lc.cmd.outOffset, lc.cmd.to, lc.cmd.value, lc.cmd.inOffset, lc.cmd.inSize
                    )) {
                        "Unexpected occurence of memory in $lc"
                    }
                    patch.replaceAt(TACCmd.Simple.CallCore::inBase, lc.narrow()) { m, c ->
                        c.copy(
                            inBase = m,
                            outBase = m
                        )
                    }
                }
                else -> return exit(lc)
            }
        }
        prog.parallelLtacStream().mapNotNull {
            it `to?` it.maybeAnnotation(OptimizeBasedOnPointsToAnalysis.PARTITION_FENCE)
        }.sequential().forEach {(w, id) ->
            patch.replaceCommand(w.ptr, listOf(
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                    lhs = id.id.toSplit(TACKeyword.MEMORY.toVar()),
                )
            ))
        }
        Logger.regression { "Memory partitioning of ${method.code.name} succeeded" }
        recordSuccess(method.toString(), INSTRUMENTATION_SUCCESS_STATS_KEY, "MEMORY_PARTITION", true)
        return patch.toCode(prog)
    }

    fun partitionFor(partitionId: Int, callIdx: CallId): TACSymbol.Var {
        return TACSymbol.Var(TACKeyword.MEMORY.extendName("!${partitionId}"), Tag.ByteMap, callIndex = callIdx)
    }
}
