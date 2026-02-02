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
package analysis.pta

import allocator.Allocator
import analysis.*
import analysis.loop.*
import analysis.smtblaster.*
import config.Config
import com.certora.collect.*
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import parallel.*
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler.rpc
import statistics.ElapsedTimeStats
import statistics.SDCollectorFactory
import statistics.toSDFeatureKey
import statistics.toTimeTag
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.data.tacexprutil.TACExprFreeVarsCollector
import vc.data.tacexprutil.TACExprUtils
import java.math.BigInteger

private val logger = Logger(LoggerTypes.LOOP_ANALYSIS)

val ITERATION_VARIABLE_BOUND = MetaKey<LoopCopyAnalysis.IterationVariableBound>("pta.iteration-variable-bound")
val COPY_LOOP_SOURCE_ACCESS = MetaKey.Nothing("pta.loops.fixup-source-access")
val COPY_LOOP_DEST_ACCESS = MetaKey.Nothing("pta.loops.fixup-dest-access")

object LoopCopyAnalysis {
    private fun Iterable<LTACCmd>.mapWrittenTo(havoc: MutableSet<TACSymbol.Var>) {
        this.mapNotNullTo(havoc) {
            if (it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs != TACKeyword.MEMORY.toVar()) {
                it.cmd.lhs
            } else {
                null
            }
        }
    }

    @KSerializable
    @Treapable
    sealed class LoopValueSummary : AmbiSerializable, TransformableVarEntity<LoopValueSummary> {
        @KSerializable
        data class WeaklyIncreasing(val src: Set<TACSymbol.Var>, val mustWrite: Boolean, val definition: TACExpr?) : LoopValueSummary() {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): LoopValueSummary {
                val mapper = object : DefaultTACExprTransformer() {
                    override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
                        return f(exp.s).asSym()
                    }
                }
                return WeaklyIncreasing(
                    src.mapToSet(f),
                    mustWrite,
                    definition?.let(mapper::transform)
                )
            }
        }

        @KSerializable
        object Havoc : LoopValueSummary() {
            override fun hashCode() = hashObject(this)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this

            fun readResolve(): Any {
                return Havoc
            }
        }

        @KSerializable
        object Identity : LoopValueSummary() {
            override fun hashCode() = hashObject(this)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this
            fun readResolve(): Any = Identity
        }
    }

    @KSerializable
    data class IterationVariableBound(
        val iterationVariable: TACSymbol.Var,
        val boundVariable: TACSymbol.Var,
        val stride: BigInteger
    ) : AmbiSerializable, TransformableVarEntityWithSupport<IterationVariableBound> {
        override val support get() = setOf(iterationVariable, boundVariable)
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): IterationVariableBound {
            return IterationVariableBound(f(iterationVariable), f(boundVariable), stride)
        }
    }

    private object LoopBodyValueSummary {

        fun checkExpressionIsIncreasingOver(havoc: Set<TACSymbol.Var>, prev: TACExpr, blaster: IBlaster) : Set<TACSymbol.Var>? {
            val axioms = mutableMapOf<TACSymbol.Var, BigInteger>()
            var counter = 0
            val axiomatized = (object : DefaultTACExprTransformer() {
                override fun transformBWAnd(o1: TACExpr, o2: TACExpr, tag: Tag?): TACExpr {
                    if(o2 is TACExpr.Sym.Const && o1 is TACExpr.Sym.Var) {
                        val repl = TACSymbol.Var("bwAnd$counter", Tag.Bit256)
                        axioms[repl] = o2.s.value
                        counter++
                        return repl.asSym()
                    } else if(o1 is TACExpr.Sym.Const && o2 is TACExpr.Sym.Var) {
                        val repl = TACSymbol.Var("bwAnd$counter", Tag.Bit256)
                        counter++
                        axioms[repl] = o1.s.value
                        return repl.asSym()
                    } else {
                        return TACExpr.BinOp.BWAnd(o1, o2)
                    }
                }
            }).transform(prev)
            val blastedExpr = SmtExpIntBlaster().blastExpr(axiomatized) {
                it.toSMTRep()
            } ?: return null
            val freeVarsOf = TACExprFreeVarsCollector.getFreeVars(axiomatized)
            if(freeVarsOf.any {
                        it in havoc
                    }) {
                return null
            }
            val builder = SmtExpScriptBuilder(SmtExpIntTermBuilder)
            for(fv in freeVarsOf) {
                builder.declare(fv.toSMTRep())
                builder.assert {
                    ge(toIdent(fv.toSMTRep()), const(0))
                }
            }
            for((k, ax) in axioms) {
                builder.assert {
                    le(toIdent(k.toSMTRep()), const(ax))
                }
            }
            val freeVars = freeVarsOf - axioms.keys
            val monotoneOver = freeVars.all { src ->
                builder.fork().let { forked ->
                    forked.assert {
                        lt(blastedExpr, toIdent(src.toSMTRep()))
                    }
                    forked.checkSat()
                    blaster.blastSmt(forked.cmdList)
                }
            }
            return if(monotoneOver) {
                freeVars.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }

        data class LoopEffect(
            val monotoneTransformer: MutableMap<TACSymbol.Var, LoopValueSummary>,
            val havoc: MutableSet<TACSymbol.Var>,
            val identity: MutableSet<TACSymbol.Var>
        )

        fun getLoopBodySummary(g: TACCommandGraph, succ: NBId, loopSumm: LoopSummarization.LoopIterationSummary): LoopEffect {
            val liveAtLoopExit = g.cache.lva.liveVariablesBefore(g.elab(succ).commands.first().ptr)
            val monotone = mutableSetOf<TACSymbol.Var>()
            val havoc = mutableSetOf<TACSymbol.Var>()
            val identity = mutableSetOf<TACSymbol.Var>()
            for ((k, effect) in loopSumm.iterationEffect) {
                if (k !in liveAtLoopExit) {
                    continue
                }
                if(effect is TACExpr.Sym.Var && effect.s == k) {
                    identity.add(k)
                    continue
                }
                val isMonotone = LoopSummarization.isMonotoneTransformerFor(effect) {
                    it == k
                }
                if (isMonotone) {
                    monotone.add(k)
                } else {
                    havoc.add(k)
                }
            }
            val toRet = treapMapBuilderOf<TACSymbol.Var, LoopValueSummary>()
            monotone.associateWithTo(toRet) {
                LoopValueSummary.WeaklyIncreasing(setOf(it), mustWrite = false, definition = null)
            }
            havoc.associateWithTo(toRet) {
                LoopValueSummary.Havoc
            }
            identity.associateWithTo(toRet) {
                LoopValueSummary.Identity
            }
            return LoopEffect(toRet, havoc, identity)
        }

        fun computeBackwardsExpression(
            d: TACSymbol.Var,
            curr: NBId,
            g: TACCommandGraph,
            loopExit: NBId,
            fixupBlocks: Set<NBId>
        ): TACExpr? {
            val block = g.elab(curr)
            val first = block.commands.first()
            val last = block.commands.last()
            val defining = DefiningEquationAnalysis.getDefiningEquation(g, d, last.ptr, first.ptr) ?: return null
            if(curr == loopExit) {
                return defining
            }
            val freeVarsOf = TACExprFreeVarsCollector.getFreeVars(defining)
            return g.pred(curr).singleOrNull { it in fixupBlocks }?.let { pred ->
                freeVarsOf.monadicMap { v ->
                    computeBackwardsExpression(v, pred,  g, loopExit, fixupBlocks)?.let {
                        v.asSym() to it
                    }
                }?.toMap()?.let {
                    TACExprUtils.SubstitutorVar(it).transform(defining)
                }
            }
        }
    }

    @KSerializable
    data class LoopCopySummary(
        val lenVars: Set<TACSymbol.Var>,
        val outPtr: Set<TACSymbol.Var>,
        val inPtr: Set<TACSymbol.Var>,
        val assumedSize: BigInteger,
        val valueSummary: Map<TACSymbol.Var, LoopValueSummary>,
        override val skipTarget: NBId,
        override val originalBlockStart: NBId,
        override val summarizedBlocks: Set<NBId>,
        val sourceMap: TACSymbol.Var,
        val destMap: TACSymbol.Var,
        val fixupBlocks: Set<NBId>
    ) : ConditionalBlockSummary {
        override val variables: Set<TACSymbol.Var>
            get() = lenVars + outPtr + inPtr + sourceMap + destMap

        override val modifiedVars: Set<TACSymbol.Var> get() = valueSummary.keysMatching { _, summary ->
            summary !is LoopValueSummary.Identity
        }.toSet()

        override val annotationDesc: String
            get() = "Copy loop from $inPtr(${this.sourceMap}) => $outPtr(${this.destMap}) (len $lenVars) (succ: $originalBlockStart) (skip: $skipTarget) [update: ${valueSummary.mapNotNull { (k, v) ->
                (v as? LoopValueSummary.WeaklyIncreasing)?.let {
                    "$k = f(${v.src.joinToString(", ")})"
                }
            }.joinToString("; ")}]"

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): LoopCopySummary {
            return LoopCopySummary(
                lenVars = lenVars.mapToTreapSet(f),
                outPtr = outPtr.mapToTreapSet(f),
                inPtr = inPtr.mapToTreapSet(f),
                assumedSize = assumedSize,
                valueSummary = valueSummary.mapKeys { f(it.key) }.mapValues { it.value.transformSymbols(f) }.toTreapMap(),
                skipTarget = skipTarget,
                originalBlockStart = originalBlockStart,
                summarizedBlocks = summarizedBlocks,
                sourceMap = f(sourceMap),
                destMap = f(destMap),
                fixupBlocks = fixupBlocks
            )
        }

        override val mustWriteVars: Collection<TACSymbol.Var>
            get() = valueSummary.keysMatching { _, summary ->
                (summary as? LoopValueSummary.WeaklyIncreasing)?.mustWrite == true
            }

        override fun remapBlocks(f: (NBId) -> NBId?): LoopCopySummary {
            return LoopCopySummary(
                lenVars = lenVars,
                outPtr = outPtr,
                inPtr = inPtr,
                assumedSize = assumedSize,
                valueSummary = valueSummary,
                skipTarget = f(skipTarget)!!,
                originalBlockStart = f(originalBlockStart)!!,
                summarizedBlocks = summarizedBlocks.monadicMap(f)?.toTreapSet().orEmpty(),
                sourceMap = sourceMap,
                fixupBlocks = fixupBlocks.monadicMap(f)?.toTreapSet().orEmpty(),
                destMap = destMap
            )
        }
    }

    private const val symb_in = "IN_PTR"
    private const val symb_len = "REMAINING"
    private const val symb_out = "OUT_PTR"


    private fun encodeFinalIteration(g: TACCommandGraph, write: CmdPointer, inRead: Set<CmdPointer>, outPtr: Set<CmdPointer>,
                                     len: TACSymbol.Var,
                                     writeVal: TACSymbol.Var,
                                     blaster: IBlaster
    ): Boolean {
        return BitBlaster.blastCode(
            block = g.elab(write.block),
            start = 0,
            end = write.pos,
            // apparently we may reach here with an empty outPtr
            synthAssign = (outPtr.map { it to symb_out } + inRead.map { it to symb_in }).toMap(),
            env = mapOf(
                len to symb_len
            ),
            blaster = blaster,
            alwaysDeclare = setOf(symb_out, symb_in)
        ) { state ->
            assert {
                land(
                    lt(
                        toIdent(symb_len),
                        const(32)
                    ),
                    le(
                        const(0),
                        toIdent(symb_len)
                    )
                )
            }
            val writeIdent = state[writeVal] ?: return@blastCode false
            define("certora!lowerMask") {
                minus(
                    shl(
                        const(1),
                        mult(
                            const(8),
                            minus(
                                const(32),
                                toIdent(symb_len)
                            )
                        )
                    )!!,
                    const(1)
                )
            }
            assert {
                lnot(
                    eq(
                        toIdent(writeIdent),
                        bwOr(
                            bwAnd(
                                toIdent(symb_out),
                                toIdent("certora!lowerMask")
                            )!!,
                            bwAnd(
                                toIdent(symb_in),
                                bwNot(toIdent("certora!lowerMask"))!!
                            )!!
                        )!!
                ))
            }
            true
        }
    }

    private sealed class PostLoopOperation {
        abstract val refBlocks: Set<NBId>
        data class SplitFixup(val where: CmdPointer, val tagging: FixupAccessTagging) : PostLoopOperation(), FixupAccessTagging by tagging {
            override val refBlocks
                get() = setOf(where.block)
        }
        data class ConditionalFixup(val blocks: Set<NBId>, val tagging: FixupAccessTagging) : PostLoopOperation(), FixupAccessTagging by tagging {
            override val refBlocks = blocks
        }
        object NONE : PostLoopOperation() {
            override val refBlocks: Set<NBId>
                get() = setOf()
        }
    }

    private data class CopyLoop(
        val assumedSize: BigInteger,
        val l: Loop,
        val inPtr: Set<TACSymbol.Var>,
        val outPtr: Set<TACSymbol.Var>,
        val len: Set<TACSymbol.Var>,
        val finalWrite: PostLoopOperation,
        val mutation: Map<TACSymbol.Var, LoopValueSummary>,
        val sourceMap: TACSymbol.Var
    ) {
        fun refBlocks() = l.body + finalWrite.refBlocks

        fun toSummary(finalIteration: Set<NBId>, succBlock: NBId): LoopCopySummary {
            return LoopCopySummary(
                lenVars = len,
                outPtr = outPtr,
                inPtr = inPtr,
                summarizedBlocks = l.body + finalIteration,
                originalBlockStart = l.head,
                skipTarget = succBlock,
                assumedSize = assumedSize,
                valueSummary = mutation,
                sourceMap = this.sourceMap,
                fixupBlocks = finalIteration,
                destMap = TACKeyword.MEMORY.toVar()
            )
        }
    }

    private val lengthMultiplicationPattern = PatternDSL.build {
        (({ _: LTACCmd, x: TACCmd.Simple.AssigningCmd ->
            (x as? TACCmd.Simple.AssigningCmd.ByteLoad)?.loc as? TACSymbol.Var
        }).lift<TACSymbol.Var>()(Var).withAction { w, _ ->
            w.narrow<TACCmd.Simple.AssigningCmd.ByteLoad>()
        } * 0x20()).commute.first
    }

    private object LoopMeasurementTags {
        val LOOP_SUMMARY = "summarySearch".toTimeTag()
        val FIXUP_SEARCH = "fixupSearch".toTimeTag()
    }

    fun annotateLoops(g: CoreTACProgram): CoreTACProgram {
        logger.debug { "Analyzing ${g.name}" }
        val graph = g.analysisCache.graph
        val patching = g.toPatchingProgram()
        val loops = getNaturalLoops(graph)
        return ParallelPool.allocInScope(2000, {timeout -> Z3BlasterPool(z3TimeoutMs = timeout) }) { blaster ->
            val loopSummarizer = LoopSummarization(g = graph, blaster = blaster, loops = loops)

            val loopSummaries = mutableMapOf<Loop, LoopSummarization.LoopIterationSummary?>()

            fun summarizeLoop(l: Loop) : LoopSummarization.LoopIterationSummary? {
                synchronized(loopSummaries) {
                    if(l in loopSummaries) {
                        return loopSummaries[l]
                    }
                }

                val summ = loopSummarizer.summarizeLoop(l)
                synchronized(loopSummaries) {
                    loopSummaries[l] = summ
                }
                return summ
            }

            val lengthCheck by lazy {
                PatternMatcher.compilePattern(graph = graph, patt = lengthMultiplicationPattern)
            }

            val arraySummarizer = object : ArrayLoopSummarization(z3Processor = blaster) {
                override fun couldBeDataSegmentSize(l: Loop, cand: TACSymbol.Var): Boolean {
                    return lengthCheck.query(cand, graph.elab(l.head).commands.first()).toNullableResult() != null
                }
            }

            fun checkStride(v: TACSymbol.Var, e: TACExpr) =
              LogicalEquivalence.equiv(listOf(), e, TACExpr.Vec.Add(v.asSym(), TACSymbol.lift(32).asSym()), blaster)


            val copyLoops = loops.forkEveryOrNull mapNotNull@{ loop ->
                val loopTimeTag = "loop".toTimeTag()
                val loopElapsed = ElapsedTimeStats( g.name.toSDFeatureKey())

                val granularLoop = ElapsedTimeStats(g.name.toSDFeatureKey(), loop.head.toString().toSDFeatureKey())
                logger.debug {
                    "Scanning loop with header ${loop.head} in thread ${Thread.currentThread()}"
                }
                loopElapsed.startMeasuringTimeOf(loopTimeTag)

                fun endRecording() {
                    loopElapsed.endMeasuringTimeOf(loopTimeTag)
                    SDCollectorFactory.collector().collectFeature(loopElapsed)
                    SDCollectorFactory.collector().collectFeature(granularLoop)
                }

                if(Config.CopyLoopSizeHeuristic.get() >= 0 && loop.body.size >= Config.CopyLoopSizeHeuristic.get()) {
                    return@mapNotNull null
                }

                val loopSucc = loop.body.flatMap {
                    graph.succ(it)
                }.singleOrNull { it !in loop.body && it !in g.analysisCache.revertBlocks }
                    ?: return@mapNotNull null.also {
                      endRecording()
                  }
                val summ = summarizeLoop(loop) ?: return@mapNotNull null.also {
                    endRecording()
                }
                val exitCondFv = TACExprFreeVarsCollector.getFreeVars(summ.exitCondition).singleOrNull()
                val (loopMutated, havoc) = LoopBodyValueSummary.getLoopBodySummary(graph, loopSucc, summ)
                rpc {
                    if (exitCondFv == null) {
                        false
                    } else {
                        LogicalEquivalence.equiv(listOf(), summ.exitCondition, TACExpr.BinRel.Lt(exitCondFv.asSym(), TACSymbol.lift(32).asSym()), blaster)
                    }
                }.bindCommute iterationCheck@{ isOldStyle ->
                    if (isOldStyle) {
                        check(exitCondFv != null)
                        // seems promising, is this an old-style copy loop?
                        val write = summ.getAllWrites().singleOrNull()?.let { it as? LoopSummarization.MemoryMutation.MemoryWrite }
                          ?: return@iterationCheck null
                        // a single, indexing write, great! what's it's value defined by?
                        val select = (write.value as? TACExpr.Select) ?: return@iterationCheck complete(null)
                        if (((select.base as? TACExpr.Sym)?.s as? TACSymbol.Var)?.let { summ.reachingWrites[LoopSummarization.getWriteTarget(it)] }?.isEmpty() != true) {
                            return@iterationCheck null
                        }
                        // cool are both of these indices variables?
                        val writeInd = ((write.index as? TACExpr.Sym)?.s as? TACSymbol.Var)
                          ?: return@iterationCheck null
                        val readInd = ((select.loc as? TACExpr.Sym)?.s as? TACSymbol.Var) ?: return@iterationCheck null
                        // check their stride
                        rpc {
                            // len check
                            summ.iterationEffect[exitCondFv]?.let {
                                LogicalEquivalence.equiv(listOf(), it, TACExpr.BinOp.Sub(exitCondFv.asSym(), TACSymbol.lift(32).asSym()), blaster)
                            } == true
                        } with rpc {
                            // write check
                            summ.iterationEffect[writeInd]?.let {
                                checkStride(writeInd, it)
                            } == true
                        } with rpc {
                            // read check
                            summ.iterationEffect[readInd]?.let {
                                checkStride(readInd, it)
                            } == true
                        } andThen { a, b, c ->
                            if (a && b && c) {
                                rpc {
                                    checkFinalWrite(graph, blaster = blaster,
                                      len = exitCondFv, inP = readInd, outP = writeInd, k = loopSucc)
                                }.maybeMap { finalWrite ->
                                    val writtenInFixup = mutableSetOf<TACSymbol.Var>()
                                    val liveAfter = g.analysisCache.lva.liveVariablesAfter(finalWrite.fixupEnd)
                                    graph.iterateUntil(finalWrite.fixupEnd).mapWrittenTo(writtenInFixup).also {
                                        writtenInFixup.removeIf {
                                            it !in liveAfter
                                        }
                                    }
                                    for (written in writtenInFixup) {
                                        loopMutated[written] = DefiningEquationAnalysis.getDefiningEquation(graph, written, finalWrite.fixupEnd, target = graph.elab(loopSucc).commands.first().ptr)?.let {
                                            LoopBodyValueSummary.checkExpressionIsIncreasingOver(havoc, it, blaster)
                                        }?.let { LoopValueSummary.WeaklyIncreasing(it, mustWrite = true, definition = null) } ?: LoopValueSummary.Havoc
                                    }
                                    CopyLoop(
                                      inPtr = setOf(readInd),
                                      len = setOf(exitCondFv),
                                      l = loop,
                                      outPtr = setOf(writeInd),
                                      finalWrite = PostLoopOperation.SplitFixup(finalWrite.fixupEnd, finalWrite),
                                      assumedSize = BigInteger.ONE,
                                      mutation = loopMutated,
                                      sourceMap = (select.base as TACExpr.Sym.Var).s
                                    )
                                }
                            } else {
                                complete(null)
                            }
                        }
                    } else {
                        logger.debug {
                            "Assuming loop ${loop.head} is not old-style..."
                        }
                        if(summ.memoryReads.size > 1 || summ.reachingWrites.any { (_, w) ->
                                w.size > 1 || w.any {
                                    it !is LoopSummarization.MemoryMutation.MemoryWrite
                                }
                            }) {
                            return@iterationCheck null
                        }
                        granularLoop.startMeasuringTimeOf(LoopMeasurementTags.LOOP_SUMMARY)
                        arraySummarizer.isArrayReadStride(summ).map {
                            logger.debug {
                                "Got $it results for read stride for loop ${loop.head}"
                            }
                            it.singleOrNull()
                        }.parallelBind(
                          arraySummarizer.isArrayWriteStride(summ).map {
                              logger.debug { "Got $it results for write stride for loop ${loop.head}" }
                              it.singleOrNull()
                          }
                        ) { readEvery: ArrayLoopSummarization.ReadEvery?, writeEvery: ArrayLoopSummarization.WriteEvery? ->
                            logger.debug {
                                "Finished with ${loop.head} : $readEvery & $writeEvery"
                            }
                            granularLoop.endMeasuringTimeOf(LoopMeasurementTags.LOOP_SUMMARY)

                            complete(readEvery?.let { a1 ->
                                writeEvery?.let {
                                    a1 to it
                                }
                            })
                        }.maybeBind { (arrayRead, arrayWrite) ->
                            granularLoop.startMeasuringTimeOf(LoopMeasurementTags.FIXUP_SEARCH)
                            logger.debug {
                                "Beginning fixup search for ${loop.head}"
                            }
                            CombinedPostWriteFixupSummarization(graph, blaster).isPostWriteFixup(l = loop, g = graph, w = arrayWrite, loopSummarization = summ).bindCommute mapNotNullInner@{ cleanup ->
                                logger.debug {
                                    "got post write fixup for ${loop.head}: $cleanup"
                                }
                                granularLoop.endMeasuringTimeOf(LoopMeasurementTags.FIXUP_SEARCH)
                                val len = arrayRead.roles.entries.firstOrNull {
                                    it.value == AbstractArraySummaryExtractor.LoopRole.LENGTH || it.value == AbstractArraySummaryExtractor.LoopRole.ELEM_LENGTH
                                }?.let { (k, v) ->
                                    if (v == AbstractArraySummaryExtractor.LoopRole.LENGTH) {
                                        k
                                    } else {
                                        check(v == AbstractArraySummaryExtractor.LoopRole.ELEM_LENGTH)
                                        lengthCheck.query(k, graph.elab(loop.head).commands.first()).toNullableResult()!!.cmd.lhs
                                    }
                                } ?: return@mapNotNullInner null
                                val inP = arrayRead.roles.entries.firstOrNull {
                                    it.value == AbstractArraySummaryExtractor.LoopRole.ELEM_START
                                }?.key ?: return@mapNotNullInner null
                                val outP = arrayWrite.roles.entries.firstOrNull {
                                    it.value == AbstractArraySummaryExtractor.LoopRole.ELEM_START
                                }?.key ?: return@mapNotNullInner null
                                if (cleanup == null && arrayRead.assumedSize == BigInteger.ONE) {
                                    val where =
                                      lengthCheck.query(len, graph.elab(loop.head).commands.first()).toNullableResult()
                                        ?: return@mapNotNullInner null
                                    return@mapNotNullInner complete(
                                      CopyLoop(
                                        assumedSize = 32.toBigInteger(),
                                        len = setOf(where.cmd.lhs),
                                        inPtr = setOf(inP),
                                        outPtr = setOf(outP),
                                        l = loop,
                                        finalWrite = PostLoopOperation.NONE,
                                        mutation = loopMutated,
                                        sourceMap = arrayRead.baseMap
                                      )
                                    )

                                }
                                if (!arraySummarizer.isCopyLoop(arrayRead, arrayWrite)) {
                                    return@mapNotNullInner null
                                }
                                if (cleanup != null) {
                                    val defined = mutableSetOf<TACSymbol.Var>()
                                    if (cleanup is CommonFixupReasoning.PostWriteFixup.ConditionalFixup) {
                                        val liveAtFixupEnd =
                                                graph.cache.lva.liveVariablesBefore(graph.elab(cleanup.succBlock).commands.first().ptr)
                                        cleanup.fixupBlocks.flatMap {
                                            graph.elab(it).commands
                                        }.mapWrittenTo(defined)
                                        defined.removeIf { it !in liveAtFixupEnd }

                                        for (d in defined) {
                                            val monotoneOver =
                                                    graph.pred(cleanup.succBlock).filter { it in cleanup.fixupBlocks }
                                                            .map { predBlock ->
                                                                LoopBodyValueSummary.computeBackwardsExpression(
                                                                        d,
                                                                        predBlock,
                                                                        graph,
                                                                        loopSucc,
                                                                        cleanup.fixupBlocks
                                                                )?.let { prev ->
                                                                    LoopBodyValueSummary.checkExpressionIsIncreasingOver(
                                                                            havoc,
                                                                            prev,
                                                                            blaster
                                                                    )
                                                                }
                                                            }.monadicFold { a, b ->
                                                                a.intersect(b)
                                                            }
                                            if (monotoneOver != null && monotoneOver.isNotEmpty()) {
                                                loopMutated[d] = LoopValueSummary.WeaklyIncreasing(monotoneOver, mustWrite = true, definition = null)
                                            } else {
                                                loopMutated[d] = LoopValueSummary.Havoc
                                            }
                                        }
                                    } else if (cleanup is CommonFixupReasoning.PostWriteFixup.SplitFixup) {
                                        val writtenInFixup = mutableSetOf<TACSymbol.Var>()
                                        val finalWrite = cleanup.finalWrite
                                        val liveAfter = g.analysisCache.lva.liveVariablesAfter(finalWrite)
                                        graph.iterateUntil(finalWrite).mapWrittenTo(writtenInFixup).also {
                                            writtenInFixup.removeIf {
                                                it !in liveAfter
                                            }
                                        }
                                        for (written in writtenInFixup) {
                                            loopMutated[written] = DefiningEquationAnalysis.getDefiningEquation(graph, written, finalWrite, target = graph.elab(loopSucc).commands.first().ptr)?.let {
                                                LoopBodyValueSummary.checkExpressionIsIncreasingOver(havoc, it, blaster)?.let { vs -> it to vs }
                                            }?.let { LoopValueSummary.WeaklyIncreasing(it.second, mustWrite = true, definition = it.first) } ?: LoopValueSummary.Havoc
                                        }
                                    }
                                }
                                complete(CopyLoop(
                                  assumedSize = arrayRead.assumedSize,
                                  l = loop,
                                  inPtr = setOf(inP),
                                  outPtr = setOf(outP),
                                  len = setOf(len),
                                  finalWrite = cleanup?.let {
                                      when (it) {
                                          is CommonFixupReasoning.PostWriteFixup.ConditionalFixup ->
                                            PostLoopOperation.ConditionalFixup(it.fixupBlocks, it)
                                          is CommonFixupReasoning.PostWriteFixup.SplitFixup ->
                                              PostLoopOperation.SplitFixup(it.finalWrite, it)
                                      }
                                  } ?: PostLoopOperation.NONE,
                                  mutation = loopMutated,
                                  sourceMap = arrayRead.baseMap
                                ))

                            }
                        }
                    }
                }.andAlso { ->
                    endRecording()
                }
            }.pcompute().map { res ->
                res.mapNotNull { it }
            }.runInherit()

            val set = mutableSetOf<NBId>()
            for (copyLoop in copyLoops) {
                val refBlocks = copyLoop.refBlocks()
                if (refBlocks.containsAny(set)) {
                    logger.info {
                        "Copy loops overlapped, not willing to handle this"
                    }
                    return@allocInScope g
                }
                set.addAll(refBlocks)
            }
            outer@ for (copyLoop in copyLoops) {
                val nonLoopPreds = graph.pred(copyLoop.l.head).filter { it !in copyLoop.l.body }
                val canRewritePred = nonLoopPreds.map(graph::elab).all {
                    it.commands.last().let { last ->
                        last.cmd !is TACCmd.Simple.SummaryCmd && last.cmd !is TACCmd.Simple.JumpiCmd
                    }
                }
                if (!canRewritePred) {
                    continue@outer
                }
                val guardBlock: NBId
                when (val fWrite = copyLoop.finalWrite) {
                    is PostLoopOperation.SplitFixup -> {
                        val succBlock = patching.splitBlockAfter(fWrite.where)
                        val finalWriteBlock = fWrite.where.block
                        guardBlock = patching.addBlock(
                            copyLoop.l.head.copy(freshCopy = Allocator.getFreshId(Allocator.Id.BLOCK_FRESH_COPY)),
                            listOf(
                                SnippetCmd.EVMSnippetCmd.CopyLoopSnippet.CopyLoop.toAnnotation(g.destructiveOptimizations),
                                TACCmd.Simple.SummaryCmd(
                                    copyLoop.toSummary(setOf(finalWriteBlock), succBlock),
                                    MetaMap()
                                )
                            )
                        )
                    }
                    is PostLoopOperation.ConditionalFixup -> {
                        val succBlock = fWrite.blocks.flatMap {
                            graph.succ(it)
                        }.filter {
                            it !in fWrite.blocks
                        }.toSet().singleOrNull() ?: continue@outer
                        guardBlock = patching.addBlock(copyLoop.l.head, listOf(
                            SnippetCmd.EVMSnippetCmd.CopyLoopSnippet.CopyLoop.toAnnotation(g.destructiveOptimizations),
                          TACCmd.Simple.SummaryCmd(copyLoop.toSummary(fWrite.blocks, succBlock), MetaMap())
                        ))
                    }
                    is PostLoopOperation.NONE -> {
                        val succBlock = copyLoop.l.body.flatMap {
                            graph.succ(it)
                        }.singleOrNull {
                            it !in copyLoop.l.body && it !in g.analysisCache.revertBlocks
                        } ?: continue@outer
                        guardBlock = patching.addBlock(copyLoop.l.head, listOf(
                            SnippetCmd.EVMSnippetCmd.CopyLoopSnippet.CopyLoop.toAnnotation(g.destructiveOptimizations),
                          TACCmd.Simple.SummaryCmd(copyLoop.toSummary(setOf(), succBlock), MetaMap())
                        ))
                    }
                }
                if(copyLoop.finalWrite is FixupAccessTagging) {
                    for(srcAccess in copyLoop.finalWrite.inputAccess) {
                        patching.update(srcAccess) {
                            it.plusMeta(COPY_LOOP_SOURCE_ACCESS)
                        }
                    }
                    for(dstAccess in copyLoop.finalWrite.outputAccess) {
                        patching.update(dstAccess) {
                            it.plusMeta(COPY_LOOP_DEST_ACCESS)
                        }
                    }
                }
                nonLoopPreds.map(graph::elab).map { it.commands.last() }.forEach { lst ->
                    if (lst.cmd is TACCmd.Simple.JumpCmd) {
                        patching.replaceCommand(lst.ptr, listOf(
                          lst.cmd.withDst(guardBlock)
                        ))
                    } else {
                        patching.replaceCommand(lst.ptr, listOf(lst.cmd), treapSetOf(guardBlock))
                    }
                }
            }
            /*
             * Remember the boundary condition and iteration variable stride for the loops where
             * the iteration variable is being compared for equality against a fixed bound.  This
             * allows us to infer the value of the iteration variable upon exit from the loop during
             * PTA in case it (or any variable derived from it) are still live.  If we ever add boundary
             * condition computation to SimpleLoopSummarizer (and we haven't started using this for another
             * reason) we can remove this annotation.
             */
            loops.mapNotNull {
                summarizeLoop(it)
            }.forEach { summ ->
                val ip = AbstractArraySummaryExtractor.interpolateLoop(summ) ?: return@forEach
                val exitFVs = TACExprFreeVarsCollector.getFreeVars(summ.exitCondition)
                if(summ.exitCondition is TACExpr.BinRel.Eq && exitFVs.size == 2) {
                    val m = exitFVs.filter { ip[it] is AbstractArraySummaryExtractor.Interpolation.Linear }.singleOrNull()
                    val i = exitFVs.filter { ip[it] is AbstractArraySummaryExtractor.Interpolation.Identity }.singleOrNull()
                    if(m != null && i != null) {
                        val stride = (ip[m] as AbstractArraySummaryExtractor.Interpolation.Linear).n
                        patching.addBefore(graph.elab(summ.loop.head).commands.first().ptr,
                            listOf(TACCmd.Simple.AnnotationCmd(ITERATION_VARIABLE_BOUND, IterationVariableBound(m,i,stride)))
                        )
                    }
                }
            }
//        blaster.close()
            return@allocInScope patching.toCode(g)
        }
    }

    private fun checkFinalWrite(g: TACCommandGraph, k: NBId, inP: TACSymbol.Var, outP: TACSymbol.Var, len: TACSymbol.Var, blaster: IBlaster): FixupBlockResult? {
        logger.debug { "Checking final copy" }
        // find the first two reads, then the write
        val cmd = g.elab(k).commands
        val inVar = cmd.first().ptr to inP
        val outVar = cmd.first().ptr to outP
        val finalWrite = cmd.firstOrNull { lt ->
            lt.cmd is TACCmd.Simple.AssigningCmd.ByteStore && lt.cmd.base == TACKeyword.MEMORY.toVar() &&
                    lt.cmd.loc in g.cache.gvn.findCopiesAt(lt.ptr, outVar) && lt.cmd.value is TACSymbol.Var
        } ?: return run {
            logger.debug { "could not find final write in block $k" }
            g.dump(k, logger)
            null
        }
        val finalVar = (finalWrite.cmd as TACCmd.Simple.AssigningCmd.ByteStore).value as TACSymbol.Var
        val l = g.iterateUntil(finalWrite.ptr)
        if (l.any {
                    it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                }) {
            logger.debug { "Found suspicious write in successor block $k" }
            g.dump(k, logger)
            return null
        }
        logger.debug { "Looking for double reads" }
        val srcReads = l.filter { lc ->
            lc.cmd is TACCmd.Simple.AssigningCmd.ByteLoad &&
                    lc.cmd.loc is TACSymbol.Var &&
                    lc.cmd.base == TACKeyword.MEMORY.toVar() &&
                    lc.cmd.loc in g.cache.gvn.findCopiesAt(lc.ptr, inVar)
        }.map { it.ptr }.toSet()
        val dstReads = l.filter { lc ->
            lc.cmd is TACCmd.Simple.AssigningCmd.ByteLoad &&
                    lc.cmd.loc is TACSymbol.Var &&
                    lc.cmd.base == TACKeyword.MEMORY.toVar() &&
                    lc.cmd.loc in g.cache.gvn.findCopiesAt(lc.ptr, outVar)
        }.map { it.ptr }.toSet()
        logger.debug {
            "Found reads of remainder input at $srcReads"
        }
        logger.debug {
            "Found read of remainder output at $dstReads"
        }
        logger.debug { "Bit blasting final iteration up to final write $finalWrite" }
        return if (encodeFinalIteration(g, outPtr = dstReads, len = len, inRead = srcReads, write = finalWrite.ptr, writeVal = finalVar, blaster = blaster)) {
            FixupBlockResult(
                fixupEnd = finalWrite.ptr,
                outputAccess = dstReads + finalWrite.ptr,
                inputAccess = srcReads
            )
        } else {
            logger.info { "Final iteration did not copy remaining bytes" }
            null
        }
    }
}
