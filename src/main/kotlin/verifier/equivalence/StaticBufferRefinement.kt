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

import allocator.Allocator
import analysis.*
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import verifier.equivalence.tracing.BufferTraceInstrumentation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import log.*
import report.DummyLiveStatsReporter
import scene.IScene
import solver.CounterexampleModel
import solver.SolverResult
import spec.rules.EquivalenceRule
import tac.MetaKey
import tac.StartBlock
import tac.Tag
import utils.*
import utils.ModZm.Companion.to2s
import utils.SignUtilities.from2sComplement
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import vc.data.codeFromCommandWithVarDecls
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.getFreeVars
import verifier.TACVerifier
import verifier.Verifier
import verifier.equivalence.data.*
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Attempt to extract a static buffer model for some long read. This is the majority
 * of the process of the "Localized Buffer Precision" in the EC writeup.
 */
object StaticBufferRefinement {
    /**
     * The "nice" version of [WriteOffsets]: indicates that a buffer (the identity of which is not included here)
     * is always of length [staticLength], and its contents are *always* constructed by a sequence of writes, whose relative
     * offsets are in [relativeOffsets].
     *
     * The buffer being written has its used site at [bufferWriteSource]. NB that this may not be the
     * same as buffer in question as the buffer contents may be copied around.
     */
    data class BufferRefinement(
        val staticLength: BigInteger,
        val relativeOffsets: List<Int>,
        val bufferWriteSource: CmdPointer,
    )

    private val KEEP_ALIVE_META = MetaKey<EmbeddedInstrumentationVars>("equiv.keep-alive")

    /**
     * The names once again roughly correspond to [BufferTraceInstrumentation.IBufferContentsInstrumentation]
     */
    @KSerializable
    @Treapable
    private data class EmbeddedInstrumentationVars(
        val id: Int,
        val preciseBufferVar: TACSymbol.Var,
        val bufferOffsetsVar: TACSymbol.Var,
        val bufferLengthVar: TACSymbol.Var,
        val bufferWriteCountVar: TACSymbol.Var,
        val bufferCopySourceVar: TACSymbol.Var
    ) : TransformableVarEntityWithSupport<EmbeddedInstrumentationVars>, AmbiSerializable {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): EmbeddedInstrumentationVars {
            return EmbeddedInstrumentationVars(
                id = id,
                preciseBufferVar = f(preciseBufferVar),
                bufferOffsetsVar = f(bufferOffsetsVar),
                bufferLengthVar = f(bufferLengthVar),
                bufferWriteCountVar = f(bufferWriteCountVar),
                bufferCopySourceVar = f(bufferCopySourceVar)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(preciseBufferVar, bufferCopySourceVar, bufferOffsetsVar, bufferLengthVar, bufferWriteCountVar)
    }

    /**
     * Indicates the "shape" of the buffer construction extracted from a counter example.
     * [staticLength] was the buffer length (which we are guessing is static).
     *
     * [relativeWriteOffsets] records the relative offsets of the writes that
     * define the buffer, in the order they were written.
     *
     * [actualSource] indicates the long-reads traversed when building this buffer.
     * The last ID in this list is always the long read ID for buffer read we're interested in:
     * the call/log/etc.
     *
     * Any previous IDs are the ids assigned to the long-reads which copy the buffer into place. Thus,
     * the writes recorded by [relativeWriteOffsets] are *always* into the buffer associated with the long read with
     * id `actualSource.first()`.
     *
     * In other words, the buffer used at `actualSource.last()` was constructed by writing to relative offsets,
     * [relativeWriteOffsets] to buffer `actualSource.first()` and then copied via mcopy operations with IDs `actualSource.dropLast(1)`.
     *
     * This isn't a great representation, but it exists as a single return type, after which it is used to construct a VC.
     * I'm not going to lose sleep over it.
     */
    private data class WriteOffsets(
        val actualSource: List<Int>,
        val relativeWriteOffsets: List<BigInteger>,
        val staticLength: BigInteger,
    )

    /**
     * Encapsulation of something to build buffer models for buffers in [graph] using [theModel],
     * starting from the assertion location [assertLoc].
     *
     * Used to interpret the instrumentation maps used by [BufferTraceInstrumentation.IBufferContentsInstrumentation],
     * so all indices are (assumed) to not-overlap.
     *
     * Converts from twos complement to signed representations (relevant for the relative offsets)
     */
    private class ModelInterpreter(
        val theModel: CounterexampleModel,
        val graph: TACCommandGraph,
        val assertLoc: CmdPointer
    ) {
        private val visitor = object : BufferExtraction.Visitor<Map<BigInteger, BigInteger>> {
            override fun onStore(
                where: CmdPointer,
                idxValue: BigInteger,
                writtenValue: BigInteger,
                writtenExpr: TACExpr
            ): BufferExtraction.VisitResult<Map<BigInteger, BigInteger>> {
                val ind = idxValue
                val offs = writtenValue
                val offsAsMathInt = offs.from2sComplement()
                logger.trace {
                    "At ${graph.elab(where)}, storing $ind <- $offs"
                }
                return BufferExtraction.VisitResult.Continue { prev ->
                    (prev + (ind to offsAsMathInt)).toLeft()
                }
            }

            override fun onLongCopy(
                where: CmdPointer,
                targetLocation: BigInteger,
                length: BigInteger,
                sourceMap: TACSymbol.Var?,
                sourceOffset: BigInteger?
            ): BufferExtraction.VisitResult<Map<BigInteger, BigInteger>> {
                return BufferExtraction.VisitResult.Err("Cannot handle long copies for static offset extraction")
            }

            override fun onMapDefinition(where: CmdPointer): Either<Map<BigInteger, BigInteger>, String> {
                return mapOf<BigInteger, BigInteger>().toLeft()
            }

        }

        fun extractMap(which: TACSymbol.Var) = BufferExtraction.extractBuffer(
            visitor = visitor,
            where = assertLoc,
            model = theModel,
            graph = graph,
            bufferVar = which
        )
    }

    private fun <T, U> Either<T, U>?.nullIsErr(s: U) = this ?: s.toRight()

    /**
     * Extract the [WriteOffsets] from the counter example as encapsulated by [interp].
     *
     * [useId] is the long-read whose buffer contents we are trying to extract; [useIdToVars]
     * gives the post-rewrite instrumentation variables for each use-site.
     *
     * In the comments of this function, we'll refer to "the buffer" to mean the buffer being consumed as the
     * input to the long read with id [useId].
     */
    private fun extractWriteOffsets(
        interp: ModelInterpreter,
        useId: Int,
        useIdToVars: Map<Int, EmbeddedInstrumentationVars>
    ) : Either<WriteOffsets, String> {
        fun <T> Either<T, String>.withErrContext(ext: String = "") = this.mapRight { "While exploring site $useId: $ext$it" }
        fun fail(s: String): Either<Nothing, String> {
            return s.toRight().withErrContext()
        }

        logger.debug {
            "Trying to extract from $useId"
        }
        val resolvedInstrumentationVars = useIdToVars[useId] ?: return fail("no use site for $useId")
        val writeCountVar = resolvedInstrumentationVars.bufferWriteCountVar

        /**
         * Get the number of writes for this buffer.
         */
        val writeCount = interp.theModel.valueAsBigInteger(writeCountVar).fmtError().withErrContext(
            "Couldn't extract write count $writeCountVar: "
        ).leftOr { return it }
        if(writeCount == BigInteger.ZERO) {
            logger.debug {
                "zero writes, this is weird"
            }
            // odd
            return fail("No writes at all")
        }

        /**
         * And its length
         */
        val thisLength = interp.theModel.valueAsBigInteger(resolvedInstrumentationVars.bufferLengthVar).fmtError().withErrContext(
            "couldn't extract length var ${resolvedInstrumentationVars.bufferLengthVar}: "
        ).leftOr { return it }

        /**
         * Extract the relative offsets defining writes into the buffer
         */
        val writeOffsets = interp.extractMap(resolvedInstrumentationVars.bufferOffsetsVar).withErrContext(
            "couldn't extract offsets from ${resolvedInstrumentationVars.bufferOffsetsVar}: "
        ).leftOr { return it }

        /**
         * Get the copy sources
         */
        val bufferCopySources = interp.extractMap(resolvedInstrumentationVars.bufferCopySourceVar).withErrContext(
            "couldn't extract copy map ${resolvedInstrumentationVars.bufferCopySourceVar}"
        ).leftOr { return it }

        /**
         * And finally, the precision flags for all the writes.
         */
        val preciseVarMap = interp.extractMap(resolvedInstrumentationVars.preciseBufferVar).withErrContext(
            "couldn't extract precision"
        ).leftOr { return it }

        /**
         * Writes in sequence is the (reversed) sequence of relative offsets
         */
        val writesInSequence = mutableListOf<BigInteger>()

        /**
         * Start from the last write in the trace, and go "back" through history
         */
        var it = writeCount - BigInteger.ONE
        while(it >= BigInteger.ZERO) {
            val writeIsPrecise = preciseVarMap[it] ?: return fail("Couldn't find precision status for $it: $preciseVarMap $writeCount")
            if(writeIsPrecise != BigInteger.ONE) {
                logger.debug {
                    "Tracing $useId, previous write $it was imprecise, stopping"
                }
                break
            }
            /**
             * Get the copy source id. This is 0 if this wasn't a copy
             */
            val src = bufferCopySources[it]

            /**
             * Get the relative offset for this write
             */
            val offs = writeOffsets[it] ?: return fail("no value for write #$it in $writeOffsets")
            /**
             * Non-zero, so this is a write
             */
            if(src != null && src != BigInteger.ZERO) {
                // if this is empty, then we have some sequence of mstores following an mcopy.
                // not willing to support mixing of mcopies and mstores in general, so just take the
                // suffix of mstores and hope the mcopy wasn't important
                if(writesInSequence.isNotEmpty()) {
                    break
                }
                /**
                 * Check that this mcopy was copying the whole buffer: it was copying to offset 0, and
                 * it was copying exactly the length of the buffer.
                 */
                if(offs != BigInteger.ZERO) {
                    return fail("Long copy was not to 0 $offs from $src")
                }
                val copyId = src.intValueExact()

                val sourceLength = useIdToVars[copyId]?.bufferLengthVar?.let(interp.theModel::valueAsBigInteger)
                    ?.fmtError()
                    .nullIsErr("couldn't find buffer instrumentation for $copyId")
                    .withErrContext("Trying to extract length of $copyId: ")
                    .leftOr { return it }
                if(sourceLength != thisLength) {
                    return fail("Copy from $copyId was not the same length as $useId: $thisLength vs $sourceLength")
                }
                /**
                 * If this was an entire copy, then recursively try to get the contents that define the source buffer,
                 * then record that we copied from there via the mcopy (whose id is useId)
                 */
                return extractWriteOffsets(interp, copyId, useIdToVars).mapLeft {
                    it.copy(
                        actualSource = it.actualSource + useId
                    )
                }
            }
            /**
             * Otherwise, this *must* have been a scalar write
             */
            writesInSequence.add(offs)
            it--
        }
        /**
         * Reverse the writes, to give the writes in program order
         */
        writesInSequence.reverse()
        val firstPreciseWrite = it + BigInteger.ONE
        if(firstPreciseWrite == writeCount) {
            return fail("No writes into the buffer were precise")
        }
        check(writesInSequence.isNotEmpty()) {
            "Had at least one precise write, but no offsets extracted?"
        }
        val sortedWrites = writesInSequence.sorted()

        /**
         * Compute the range covered by these writes.
         */
        var currEnd = sortedWrites.first() + EVM_WORD_SIZE
        for(i in 1 until sortedWrites.size) {
            val nextWrite = sortedWrites[i]
            if(nextWrite > currEnd) {
                return fail("non-covering writes: $currEnd but next relative was $nextWrite; $sortedWrites; $writesInSequence")
            }
            currEnd = currEnd.max(nextWrite + EVM_WORD_SIZE)
        }
        if(currEnd < thisLength) {
            return fail("didn't cover entire buffer, reached $currEnd, length was $thisLength")
        }
        /**
         * Otherwise, we have that the buffer (whose id is [useId]) was of length `thisLength`
         * and the contents were defined by the writes in `writesInSequence`
         *
         */
        return WriteOffsets(
            actualSource = listOf(useId),
            staticLength = thisLength,
            relativeWriteOffsets = writesInSequence
        ).toLeft()
    }

    private fun MutableList<TACExpr.BinRel>.addEquiv(lhs: ToTACExpr, rhs: ToTACExpr) = this.add(
        TACExpr.BinRel.Eq(
            lhs.toTACExpr(), rhs.toTACExpr()
        )
    )
    private fun MutableList<TACExpr.BinRel>.addEquiv(lhs: ToTACExpr, rhs: Int) = this.addEquiv(lhs, rhs.asTACExpr)
    private fun MutableList<TACExpr.BinRel>.addEquiv(lhs: ToTACExpr, rhs: BigInteger) = this.addEquiv(lhs, rhs.asTACExpr)

    private operator fun ToTACExpr.get(ind: ToTACExpr) = TACExpr.Select(this.toTACExpr(), ind.toTACExpr())

    private fun getPrelude(m: CallableProgram<*, *>, l: String): CoreTACProgram {
        return codeFromCommandWithVarDecls(
            StartBlock,
            CommandWithRequiredDecls(TACCmd.Simple.LabelCmd(l)),
            "prelude_${m.pp()}_$l"
        )
    }


    /**
     * Turn an `Either<T, U>` into an `Either<T, String>` using U's toString.
     */
    fun <T, U> Either<T, U>.fmtError() = this.mapRight { "error obj: $it" }

    /**
     * Extract the [WriteOffsets] describine how the buffer is created, along
     * with a mapping from the numeric long read ids to their [CmdPointer] (as these IDs
     * are not stable)
     */
    private suspend fun <T: MethodMarker> extractWriteInstrumentation(
        instControl: BufferTraceInstrumentation.InstrumentationControl,
        eventMarker: TraceWithContext<T, *>,
        context: ProgramContext<T>,
        scene: IScene,
    ) : Either<Pair<WriteOffsets, Map<Int, CmdPointer>>, String> {
        fun <T> Either<T, String>.withErrorCtxt() = this.mapRight { "Extracting write offsets from ${eventMarker.context.originalProgram.pp()}: $it" }
        fun fail(msg: String) = msg.toRight().withErrorCtxt()
        val eventCmd = eventMarker.event.origProgramSite

        val inst = BufferTraceInstrumentation.instrument(
            code = eventMarker.context.originalProgram.program,
            context = context,
            options = instControl
        )

        val toAdd = MutableCommandWithRequiredDecls<TACCmd.Simple>()

        /**
         * Embed a record of the instrumentation variables in the tac so we can get their post optimization
         * names in the CEX
         */
        for((_, ui) in inst.useSiteInfo) {
            if(ui.instrumentation?.bufferWrites == null) {
                continue
            }
            val toEmbed = EmbeddedInstrumentationVars(
                preciseBufferVar = ui.instrumentation.bufferWrites.preciseBuffer,
                bufferCopySourceVar = ui.instrumentation.bufferWrites.bufferCopySource,
                id = ui.id,
                bufferOffsetsVar = ui.instrumentation.bufferWrites.bufferOffsetHolder,
                bufferLengthVar = ui.instrumentation.lengthVar,
                bufferWriteCountVar = ui.instrumentation.bufferWrites.bufferWriteCountVar
            )
            toAdd.extend(TACCmd.Simple.AnnotationCmd(
                KEEP_ALIVE_META,
                toEmbed
            ))
            toAdd.extend(toEmbed.support)
        }

        /**
         * Generate some assertion that is definitely false, we just want the counter example here
         */
        val instVars = inst.useSiteInfo[eventCmd]?.instrumentation?.bufferWrites!!
        val dummyVars = TACKeyword.TMP(Tag.Bit256, "DummyValueForCount")
        val dummyContents = TACKeyword.TMP(Tag.Bit256, "DummyValueForOffsets")
        val start = getPrelude(eventMarker.context.originalProgram, "Extracting static offsets")
        val varKeepAlive = toAdd.toCommandWithRequiredDecls()
        val assertCode = ExprUnfolder.unfoldPlusOneCmd("dummyEquation", TACExprFactTypeCheckedOnlyPrimitives {
            (dummyVars eq select(
                instVars.bufferOffsetHolder.asSym(),
                dummyVars.asSym()
            )) and (dummyContents eq instVars.bufferWriteCountVar) and
                (dummyVars eq select(instVars.bufferCopySource.asSym(), dummyVars.asSym()))
        }) {
            TACCmd.Simple.AssertCmd(it.s, "dummy")
        }.merge(dummyVars, dummyContents, instVars.bufferWriteCountVar, instVars.bufferOffsetHolder)
        val assertProgram = codeFromCommandWithVarDecls(Allocator.getNBId(), varKeepAlive andThen assertCode, "assert")
        val newId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val toCheck = start andThen inst.code.createCopy(newId) andThen assertProgram
        val report = TACVerifier.verify(scene, toCheck, DummyLiveStatsReporter)
        if(report.finalResult != SolverResult.SAT) {
            // well, this assertion was SUPPOSED to be false...
            return fail("failed getting cex for static offsets: ${report.finalResult}")
        }
        val extractionRule = EquivalenceRule.freshRule("Static extraction from ${eventMarker.context.originalProgram.pp()}")
        Verifier.JoinedResult(report).reportOutput(extractionRule)
        val reportTac = report.tac
        val reportIdToInstrumentaiton = reportTac.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(KEEP_ALIVE_META)?.let { ui ->
                ui.id to ui
            }
        }.collect(Collectors.toMap({ it.first }) { it.second })

        val useId = inst.useSiteInfo[eventCmd]?.id ?: return fail("No instrumentation info for original event site $eventCmd")
        val reportG = reportTac.analysisCache.graph
        val assertBlock = reportTac.getEndingBlocks().singleOrNull()?.let(reportG::elab)
        val assertLoc = assertBlock?.commands?.singleOrNull {
            it.cmd is TACCmd.Simple.AssertCmd
        }?.ptr ?: return fail("Couldn't find final assert location")
        val theModel = report.examplesInfo.head.model
        val staticWrites = extractWriteOffsets(
            ModelInterpreter(
                theModel = theModel,
                graph = reportG,
                assertLoc = assertLoc
            ),
            useId,
            reportIdToInstrumentaiton
        ).withErrorCtxt().leftOr { return it }

        val reverseIdMapping = inst.useSiteInfo.entries.associate {
            it.value.id to it.key
        }
        return (staticWrites to reverseIdMapping).toLeft()
    }

    /**
     * Extract the static refinement for the buffer used at [eventMarker], or an error if not possible
     */
    private suspend fun <T: MethodMarker> tryRefineBuffer(
        traceLevel: BufferTraceInstrumentation.TraceTargets,
        eventMarker: TraceWithContext<T, *>,
        context: ProgramContext<T>,
        scene: IScene,
    ) : Either<BufferRefinement, String> {
        fun <T> Either<T, String>.withErrorCtxt() = this.mapRight { "Extracting buffers from ${eventMarker.context.originalProgram.pp()}: $it" }
        fun fail(msg: String) = msg.toRight().withErrorCtxt()
        val eventCmd = eventMarker.event.origProgramSite

        /**
         * Instrument the method, recording precise information for the buffer in question, and all
         * mcopies
         */
        val bufferCopyUseSites = mapOf(
            eventCmd to BufferTraceInstrumentation.UseSiteControl(
                trackBufferContents = true,
            )
        ) + eventMarker.context.instrumentationResult.useSiteInfo.mapNotNull {
            if ((eventMarker.context.originalProgram.program).analysisCache.graph.elab(it.key)
                    .maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.cmd?.dstBase?.let {
                        TACMeta.MCOPY_BUFFER in it.meta
                    } != true
            ) {
                return@mapNotNull null
            }
            it.key to BufferTraceInstrumentation.UseSiteControl(
                trackBufferContents = true,
            )
        }

        val options = BufferTraceInstrumentation.InstrumentationControl(
            // force control-flow to the target command
            traceMode = BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(eventCmd),
            eventLoggingLevel = traceLevel,
            useSiteControl = bufferCopyUseSites,
            forceMloadInclusion = mapOf(),
            eventSiteOverride = mapOf()
        )

        val (staticWrites, reverseIdMapping) = extractWriteInstrumentation(
            instControl = options,
            eventMarker = eventMarker,
            context = context,
            scene = scene
        ).leftOr { return it.withErrorCtxt() }

        val nextInst = BufferTraceInstrumentation.instrument(
            code = eventMarker.context.originalProgram.program,
            context = context,
            options = options
        )
        val mustEqualities = mutableListOf<TACExpr.BinRel>()
        val bufferForWrites = nextInst.useSiteInfo[reverseIdMapping[staticWrites.actualSource.first()]] ?:
            return fail("no initial instrumentation for $staticWrites")

        /**
         * The buffer must be exactly equal to the static length
         */
        mustEqualities.addEquiv(bufferForWrites.instrumentation!!.lengthVar, staticWrites.staticLength)
        /**
         * there must be at least as many writes as in the relative offsets
         */
        mustEqualities.add(TACExpr.BinRel.Ge(
            bufferForWrites.instrumentation.bufferWrites!!.bufferWriteCountVar.asSym(),
            staticWrites.relativeWriteOffsets.size.asTACExpr
        ))

        /**
         * The last k writes, where k = relativeWriteOffsets.length, must have exactly the same relative offsets
         * as we extracted.
         */
        staticWrites.relativeWriteOffsets.withIndex().forEach { (ind, offs) ->
            val relativeFromEnd = staticWrites.relativeWriteOffsets.size - ind
            /**
             * The symbolic term describing this elements position. For example,
             * if there are 3 relative offset writes, and ind == 1, then this term is numberOfWrite - (3 - 1),
             * aka the second to last write, which is what we want.
             */
            val indexingTerm = TXF { bufferForWrites.instrumentation.bufferWrites.bufferWriteCountVar sub relativeFromEnd }
            TXF {
                // to the offset we expect
                mustEqualities.addEquiv(bufferForWrites.instrumentation.bufferWrites.bufferOffsetHolder[indexingTerm], offs.to2s())
                // is precise
                mustEqualities.addEquiv(bufferForWrites.instrumentation.bufferWrites.preciseBuffer[indexingTerm], TACSymbol.One)
                // not a copy
                mustEqualities.addEquiv(bufferForWrites.instrumentation.bufferWrites.bufferCopySource[indexingTerm], 0)
            }
        }
        /**
         * Then, there must be the same sequence of copies to put the buffer into position.
         */
        for((prev, copyTo) in staticWrites.actualSource.zipWithNext()) {
            val copyToBuffer = nextInst.useSiteInfo[reverseIdMapping[copyTo]] ?: return fail("no instrumentation information for dest: $copyTo")
            val copyFromBuffer = nextInst.useSiteInfo[reverseIdMapping[prev]] ?: return fail("no instrumentation information for src: $prev")
            // same length
            mustEqualities.addEquiv(copyToBuffer.instrumentation!!.lengthVar, staticWrites.staticLength)
            // look at the last write
            val lastWrite = TXF { copyToBuffer.instrumentation.bufferWrites!!.bufferWriteCountVar sub 1 }
            // must be precise
            mustEqualities.addEquiv(copyToBuffer.instrumentation.bufferWrites!!.preciseBuffer[lastWrite], TACSymbol.One)
            // must be to offset 0
            mustEqualities.addEquiv(copyToBuffer.instrumentation.bufferWrites.bufferOffsetHolder[lastWrite], TACSymbol.Zero)
            // must be copied from the previous buffer in the sequence
            mustEqualities.addEquiv(copyToBuffer.instrumentation.bufferWrites.bufferCopySource[lastWrite], copyFromBuffer.id)
        }
        // and ALL of the above.
        val assertExpression = mustEqualities.reduce(TACExpr.BinBoolOp::LAnd)
        /*
         * Assert this must be true; that the buffer is *always* built in the way we extracted.
         */
        val finalAssertion = ExprUnfolder.unfoldPlusOneCmd("allStatic", assertExpression) {
            TACCmd.Simple.AssertCmd(it.s, "has static footprint")
        }.merge(mustEqualities.flatMap {
            it.o1.getFreeVars()
        }).let {
            codeFromCommandWithVarDecls(Allocator.getNBId(), it, "assertion")
        }
        val nextCode = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val checkIsStaticFootprint = getPrelude(eventMarker.context.originalProgram, "Checking static offsets") andThen
            (nextInst.code.createCopy(nextCode) andThen finalAssertion)
        val isStaticReport = TACVerifier.verify(scene.toIdentifiers(), checkIsStaticFootprint, DummyLiveStatsReporter)
        if(isStaticReport.finalResult != SolverResult.UNSAT) {
            val staticRule = EquivalenceRule.freshRule("Verifying static offsets in ${eventMarker.context.originalProgram.pp()}")
            Verifier.JoinedResult(isStaticReport).reportOutput(staticRule)
            return fail("Not actually static offsets")
        }
        // if our assertion passed, then we know now that the buffer in question is *definitely* built by the sequence
        // of writes to the buffer at bufferWriteSource
        return BufferRefinement(
            bufferWriteSource = staticWrites.actualSource.first().let(reverseIdMapping::get)!!,
            staticLength = staticWrites.staticLength,
            relativeOffsets = staticWrites.relativeWriteOffsets.map {
                it.intValueExact()
            }
        ).toLeft()
    }


    internal suspend fun tryRefineBuffers(
        queryContext: EquivalenceQueryContext,
        aTraceAndContext: TraceWithContext<MethodMarker.METHODA, *>,
        bTraceAndContext: TraceWithContext<MethodMarker.METHODB, *>,
        targetEvents: BufferTraceInstrumentation.TraceTargets
    ) : Pair<BufferRefinement, BufferRefinement>? {
        val (aRefine, bRefine) = coroutineScope {
            val methodARefine = async {
                tryRefineBuffer(
                    traceLevel = targetEvents,
                    scene = queryContext.scene,
                    eventMarker = aTraceAndContext,
                    context = queryContext.contextA
                )
            }
            val methodBRefine = async {
                tryRefineBuffer(
                    traceLevel = targetEvents,
                    scene = queryContext.scene,
                    eventMarker = bTraceAndContext,
                    context = queryContext.contextB
                )
            }
            methodARefine.await() to methodBRefine.await()
        }
        if(aRefine !is Either.Left || bRefine !is Either.Left) {
            if(aRefine is Either.Right) {
                logger.warn {
                    "Failed to refine ${aTraceAndContext.context.originalProgram.pp()}: ${aRefine.d}"
                }
            }
            if(bRefine is Either.Right) {
                logger.warn {
                    "Failed to refine ${bTraceAndContext.context.originalProgram.pp()}: ${bRefine.d}"
                }
            }
            return null
        }
        return aRefine.d to bRefine.d
    }

}
