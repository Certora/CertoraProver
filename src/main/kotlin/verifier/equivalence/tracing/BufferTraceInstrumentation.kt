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
package verifier.equivalence.tracing

import algorithms.dominates
import analysis.*
import analysis.controlflow.MustPathInclusionAnalysis
import analysis.dataflow.StrictDefAnalysis
import analysis.numeric.IntValue
import analysis.pta.LoopCopyAnalysis
import bridge.SourceLanguage
import com.certora.collect.*
import compiler.applyKeccak
import evm.EVM_WORD_SIZE
import evm.MASK_SIZE
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*
import scene.ContractClass
import scene.IContractWithSource
import spec.cvlast.QualifiedMethodSignature
import tac.*
import vc.data.tacexprutil.getFreeVars
import java.util.concurrent.atomic.AtomicInteger
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import verifier.equivalence.DefiniteBufferConstructionAnalysis
import verifier.equivalence.summarization.ScalarEquivalenceSummary
import verifier.equivalence.tracing.BufferTraceInstrumentation.ContextSymbols.Companion.lift

@Suppress("FunctionName")
/**
 * Class responsible for the various instrumentations described in the equivalence checker white paper.
 * In particular, this generates the prophecy variables, shadow hash accumulators and so on. This class should not (and cannot)
 * be instantiated by end users, but interacted with via [BufferTraceInstrumentation.Companion.instrument].
 *
 * In the documentation of this class, we will refer to instrumentation variables having some value. It is to be
 * understood that this always means "in some concrete execution" or "in some concrete counter example".
 */
class BufferTraceInstrumentation private constructor(
    private val code: CoreTACProgram,
    private val readToInstrumentation: Map<LongRead, LongReadInstrumentation>,
    private val isGarbageCollectionFor: Map<CmdPointer, List<LongRead>>,
    private val context: InstrumentationContext,
    val options: InstrumentationControl
) {

    interface InstrumentationContext {
        val containingContract: ContractClass
        val storageVariable: TACSymbol.Var
    }
    private val callTraceLog: TraceArray
    private val functionExit : ExitLogger
    private val logTraceLog: TraceArray
    private val isRevertingPath: TACSymbol.Var
    private val callCoreOutputVars: Map<CmdPointer, TACSymbol.Var>
    private val g get() = code.analysisCache.graph

    private val reach get() = g.cache.reachability
    private val sources: Set<LongRead> get() = readToInstrumentation.keys

    init {
        callTraceLog = TraceArray(
            itemCountVar = globalStateInstrumentationVar("callOrdinal"),
            traceArray = globalStateInstrumentationVar("callHashes", Tag.ByteMap)
        )
        logTraceLog = TraceArray(
            itemCountVar = globalStateInstrumentationVar("logOrdinal"),
            traceArray = globalStateInstrumentationVar("logHashes", Tag.ByteMap)
        )
        functionExit = ExitLogger(globalStateInstrumentationVar("exitStatus", Tag.Bit256))
        isRevertingPath = globalStateInstrumentationVar("isRevertingPath", Tag.Bool)
        callCoreOutputVars = readToInstrumentation.keys.associateNotNull { lr ->
            g.elab(lr.where).maybeNarrow<TACCmd.Simple.CallCore>()?.let { lc ->
                lc.ptr to globalStateInstrumentationVar("callLength!${lr.id}", Tag.Bit256)
            }
        }
    }

    private val traceInstrumentation = TraceInstrumentationVarsImpl(
        callTraceLog, logTraceLog, functionExit, isRevertingPath
    )

    // START global vars

    private val globalStateAccumulator: TACSymbol.Var = globalStateInstrumentationVar("stateAccumulator").withMeta(
        GLOBAL_STATE_ACCUM
    )
    val storageVar: TACSymbol.Var get() = context.storageVariable

    private val globalStateVars get() = listOf(globalStateAccumulator, storageVar) + staticInstrumentationVars

    // end state vars


    private val TraceTargets.log : EventLogger get() = when(this) {
        TraceTargets.Calls -> callTraceLog
        TraceTargets.Log -> logTraceLog
        TraceTargets.Results -> functionExit
    }

    private val traceInclusionManager = options.traceMode.toTraceManager(
        code,
        sources,
        logLevel = options.eventLoggingLevel,
        eventSiteOverride = options.eventSiteOverride
    )

    /**
     * Enum describing what type of events to trace. [loggerExtractor] provides a way to get the [Logger] associated with
     * the given event type from an instance of [TraceInstrumentationVars]. [indexHolder] indicates what variable
     * is being used for the skolemization. FIXME(CERT-8862): This shouldn't be here, and moved to the equiv checker.
     */
    enum class TraceTargets(val loggerExtractor: TraceInstrumentationVars.() -> Logger, val indexHolder: (TACSymbol.Var) -> TACSymbol) {
        Calls(TraceInstrumentationVars::callTraceLog, { it }),
        Log(TraceInstrumentationVars::logTraceLog, { it }),
        Results(TraceInstrumentationVars::functionExit, { TACSymbol.Zero });
    }

    /**
     * Gets the "buffer identity", which is `r.hash` or the native hash, depending on alignment, from [longRead].
     */
    private fun getBufferIdentity(
        longRead: LongRead
    ) : TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
        val sourceInstrumentation = longRead.instrumentationInfo()
        val sourceOffset = longRead.loc
        val sourceLength = longRead.length
        if(longRead.isNullRead) {
            return sourceInstrumentation.hashVar.lift()
        }
        val bufferHash = TACKeyword.TMP(Tag.Bit256, "!bufferHash")
        val useAligned = TACKeyword.TMP(Tag.Bool, "!useAligned")
        val nativeLength = TACKeyword.TMP(Tag.Bit256,"nativeHashLen")

        /**
         * Compute the native hashing
         */
        val prefix = CommandWithRequiredDecls(listOf(
            /**
             * We can use native hashing IF:
             * 1. the buffer was built with entirely aligned writes
             * 2. the read starts at the same location as the overall buffer
             * 3. The read length is the length of the overall buffer
             * 4. The length is word aligned
             */
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = useAligned,
                rhs = TXF {
                    sourceInstrumentation.allAlignedVar and (sourceOffset eq sourceInstrumentation.baseProphecy) and
                        (sourceLength eq sourceInstrumentation.lengthProphecy) and
                        ((sourceInstrumentation.lengthProphecy mod EVM_WORD_SIZE) eq 0)
                }
            ),
            /*
             * Use a length of 0 if the buffer isn't aligned: there is no point in doing all the hashing if we know the
             * contents aren't going to be used. More practically, I had hoped that this would let us simplify away
             * the native hashing significantly, but no such luck...
             */
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = nativeLength,
                rhs = TACExprFactoryExtensions.run {
                    ite(
                        useAligned,
                        sourceLength,
                        0.asTACExpr
                    )
                }
            ),
            TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                lhs = bufferHash,
                memBaseMap = longRead.baseMap,
                op1 = sourceOffset,
                op2 = nativeLength
            )
        ), setOf(nativeLength, bufferHash, useAligned))
        /**
         * If we are aligned (according to useAligned) use the native hash (in bufferHash) or the shadow hash var.
         */
        return prefix andThen TXF {
            ite(
                useAligned,
                bufferHash,
                sourceInstrumentation.hashVar
            )
        }
    }


    private infix fun CommandWithRequiredDecls<TACCmd.Simple>.andThen(next: ToTACExpr) = TACExprWithRequiredCmdsAndDecls(
        exp = next.toTACExpr(),
        cmdsToAdd = this.cmds,
        declsToAdd = this.varDecls
    )

    /**
     * Marker class embedded by the instrumentation to aid in CEX extraction. [id] is the internal ID given to the long
     * read. [indexVar] holds the index indicating which nummber event this is in the execution (thus, in a CEX where this event
     * is the 2nd event executed, [indexVar] is a symbol that should evaluate to 1).
     *
     * [eventSort] is the sort of event, which is simply the ordinal of the member of [TraceEventSort] for this event.
     * [lengthVar] is the variable holding the length of the buffer used for this event; [bufferStart] is the starting
     * offset for this buffer, and [bufferBase] is the basemap. NB: [bufferBase] is initially always `tacM` but after
     * inlining and functionalization of memory it will hold the incarnation of memory from which this event buffer was taken.
     *
     * [numCalls] records the total number of external calls made up to this point. If the event sort is an external call,
     * this value should be equal to [indexVar].
     *
     * [bufferHash] is a variable which holds the buffer identity, the buffer hash used in the event signature computation.
     * The event signature itself is stored in [eventHash].
     */
    @KSerializable
    data class TraceIndexMarker(
        val id: Int,
        val indexVar: TACSymbol,
        val eventSort: TraceEventSort,
        val lengthVar: TACSymbol.Var,
        val bufferStart: TACSymbol.Var,
        val bufferBase: TACSymbol.Var,
        val numCalls: TACSymbol.Var,
        val bufferHash: TACSymbol.Var,
        val eventHash: TACSymbol.Var,
        val context: Context
    ) : TransformableVarEntityWithSupport<TraceIndexMarker>, AmbiSerializable {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TraceIndexMarker {
            return TraceIndexMarker(
                id,
                (indexVar as? TACSymbol.Var)?.let(f) ?: indexVar,
                eventSort,
                f(lengthVar),
                f(bufferStart),
                f(bufferBase),
                f(numCalls),
                f(bufferHash),
                f(eventHash),
                context.transformSymbols(f)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOfNotNull(indexVar as? TACSymbol.Var, lengthVar, bufferStart, bufferBase, bufferHash, eventHash, numCalls) + context.support

        companion object {
            val META_KEY = MetaKey<TraceIndexMarker>("buffer.index.marker")
        }
    }

    /**
     * Public representation of the event logger instrumentations.
     */
    interface Logger {
        /**
         * Given a symbol [ind], get an expression which holds the event hash of the [ind]th event.
         */
        fun getRepresentative(ind: TACSymbol.Var) : TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>
    }

    /**
     * Publicly available information about "global" instrumentation variables.
     * Although all types of loggers are made available, only one will actually be "useful"
     * depending on the trace target selected.
     */
    interface TraceInstrumentationVars {
        /**
         * The logger used for external calls.
         */
        val callTraceLog: Logger

        /**
         * Logger used for evm logs
         */
        val logTraceLog: Logger

        /**
         * Logger for function exits
         */
        val functionExit: Logger

        /**
         * Variable that is true if the function exited with a revert, false otherwise.
         */
        val isRevertingPath: TACSymbol.Var
    }

    private class TraceInstrumentationVarsImpl(
        override val callTraceLog: TraceArray,
        override val logTraceLog: TraceArray,
        override val functionExit: ExitLogger,
        override val isRevertingPath: TACSymbol.Var,
    ) : WithVarInit, TraceInstrumentationVars {
        private val logWriters get() = listOf(callTraceLog, logTraceLog, functionExit)
        override val havocInitVars: List<TACSymbol.Var>
            get() = logWriters.flatMap { it.havocInitVars }
        override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
            get() = logWriters.flatMap { it.constantInitVars } + listOf(isRevertingPath to TACSymbol.False)
    }

    /**
     * Internal version of [ILongRead], extended with an internally generated [id],
     * a flag indicating whether the event is definitely of length zero ([isNullRead])
     * and an optional external event information in [TraceEventWithContext].
     */
    private sealed interface LongRead : ILongRead {
        override val where: CmdPointer
        override val loc: TACSymbol
        override val length: TACSymbol
        val id: Int
        val isNullRead: Boolean
        val traceEventInfo: TraceEventWithContext?
        val baseMap: TACSymbol.Var
    }

    /**
     * A long read which is not an externally observable event.
     * These are mcopies, copy loops, sha3, and mload's which
     * have been forced to be considered long reads.
     */
    private data class BasicLongRead(
        override val where: CmdPointer,
        override val loc: TACSymbol,
        override val length: TACSymbol,
        override val id: Int,
        override val isNullRead: Boolean,
        override val baseMap: TACSymbol.Var
    ) : LongRead {
        override val traceEventInfo: TraceEventWithContext?
            get() = null
    }

    /**
     * A [LongRead] which corresponds to an external event;
     * [traceEventInfo] is thus non-null and includes information about the event.
     */
    private data class EventLongRead(
        override val where: CmdPointer,
        override val loc: TACSymbol,
        override val length: TACSymbol,
        override val id: Int,
        override val isNullRead: Boolean,
        override val traceEventInfo: TraceEventWithContext,
        val explicitReadId: Int?,
        override val baseMap: TACSymbol.Var
    ) : LongRead

    private fun LongRead.instrumentationInfo() = readToInstrumentation[this]!!

    /**
     * The private version of [IWriteSource]. Note that we do not declare this to be
     * a subtype of [IWriteSource]; rather the bound on [BufferUpdate] requires that
     * any subtype of [WriteSource] here must also be declared as a subtype of some appropriately chosen
     * subinterface of [IWriteSource].
     */
    private sealed interface WriteSource {
        /**
         * Returns an expression which evaluates to true if the write won't change the alignment of the target. The
         * equivalent of `w.alignedWrite`
         */
        fun getSourceIsAlignedPredicate(): ToTACExpr

        /**
         * The sort of the write. Equivalent of `w.sort`
         */
        val sort: WriteSort

        /**
         * The value representative for the write, equivalent of `w.repr`
         */
        fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>

        /**
         * Extra context if needed, used for returndatacopy; equivalent of `w.ctxt`
         */
        val extraContext: TACSymbol?

        fun updateShadowHash(
            currHash: TACSymbol.Var,
            relativeOffset: TACSymbol.Var,
            length: TACSymbol
        ): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            val representative = this.getValueRepresentative()
            val theHash = TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>(
                TACExpr.SimpleHash(
                    length = sort.ordinal.asTACExpr,
                    args = listOf(
                        currHash.asSym(),
                        relativeOffset.asSym(),
                        length.asSym(),
                        representative.exp
                    ) + listOfNotNull(extraContext?.asSym()),
                    hashFamily = HashFamily.Sha3
                ), setOf(), listOf()
            )
            return representative.toCRD() andThen theHash
        }
    }

    /**
     * Annotation embedded to record information about external calls that occur during
     * the execution. If the trace target is external calls, this is slightly redundant. However,
     * unlike the [TraceIndexMarker], this includes additional information, like the returndata size etc.
     */
    @KSerializable
    data class CallEvent(
        /**
         * Which call this was, counting from 0. A snapshot of [globalStateAccumulator] at the time of the call
         */
        val ordinal: TACSymbol,
        /**
         * The length of the buffer sent to the external call
         */
        val bufferLength: TACSymbol,
        /**
         * The offset in memory where the external call buffer starts
         */
        val bufferStart: TACSymbol,
        /**
         * Variable containing the basemap. Captured for the same reason as [TraceIndexMarker.bufferBase]
         */
        val memoryCapture: TACSymbol.Var,

        // call specific things
        /**
         * The value of the returndatasize
         */
        val returnDataSize: TACSymbol,
        /**
         * The returncode (the value of tacRC)
         */
        val returnCode: TACSymbol,
        /**
         * The codesize chosen for the callee
         */
        val calleeCodeSize: TACSymbol,
        /**
         * The callee contract (the `to` of the original call)
         */
        val callee: TACSymbol,
        /**
         * The value in gwei sent with the call
         */
        val value: TACSymbol
    ) : TransformableVarEntityWithSupport<CallEvent> {
        private operator fun ((TACSymbol.Var) -> TACSymbol.Var).invoke(o: TACSymbol) = when(o) {
            is TACSymbol.Const -> o
            is TACSymbol.Var -> this(o)
        }

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CallEvent {
            return CallEvent(
                ordinal = f(ordinal),
                bufferLength = f(bufferLength),
                bufferStart = f(bufferStart),
                memoryCapture = f(memoryCapture),
                returnDataSize = f(returnDataSize),
                returnCode = f(returnCode),
                calleeCodeSize = f(calleeCodeSize),
                callee = f(callee),
                value = f(value)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOfNotNull(
                ordinal as? TACSymbol.Var,
                bufferLength as? TACSymbol.Var,
                bufferStart as? TACSymbol.Var,
                memoryCapture,
                returnDataSize as? TACSymbol.Var,
                returnCode as? TACSymbol.Var,
                calleeCodeSize as? TACSymbol.Var,
                callee as? TACSymbol.Var,
                value as? TACSymbol.Var
            )
        companion object {
            val META_KEY = MetaKey<CallEvent>("call.event.meta")
        }
    }

    companion object {

        infix fun TACSymbol.Var.`=`(other: ToTACExpr) : CommandWithRequiredDecls<TACCmd.Simple> {
            return CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = this,
                rhs = other.toTACExpr()
            )), this)
        }

        infix fun TACSymbol.Var.`=`(build: TACExprFactoryExtensions.() -> TACExpr) : CommandWithRequiredDecls<TACCmd.Simple> {
            return CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = this,
                    rhs = TACExprFactoryExtensions.build()
                ), this)
        }


        context(TACExprFact)
        infix fun TACSymbol.Var.`=`(other: ToTACExpr) = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = this,
            rhs = other.toTACExpr()
        )

        private fun LoopCopyAnalysis.LoopCopySummary.isMemoryByteCopy() : Boolean {
            return TACMeta.EVM_MEMORY in this.sourceMap.meta && this.assumedSize == BigInteger.ONE
        }

        private val freePointerLoad =
            PatternMatcher.Pattern.AssigningPattern1(
                TACCmd.Simple.AssigningCmd.ByteLoad::class.java,
                extract = { _: LTACCmd, cmd: TACCmd.Simple.AssigningCmd.ByteLoad ->
                    cmd.loc
                },
                nested = PatternMatcher.Pattern.FromConstant.exactly(0x40.toBigInteger()),
                out = { _, _ ->  }
            )

        private val scratchPattern = PatternDSL.build {
            freePointerLoad.asBuildable() lor (freePointerLoad.asBuildable() + Const).commute
        }

        /**
         * Extract the event information from [origCommand] or null if this command isn't an event
         */
        private fun getTraceEvent(origCommand: TACCmd.Simple) : TraceEventWithContext? {
            return when(origCommand) {
                is TACCmd.Simple.ReturnCmd -> {
                    TraceEventWithContext(TraceEventSort.RETURN, Context.MethodExit)
                }
                is TACCmd.Simple.RevertCmd -> {
                    TraceEventWithContext(TraceEventSort.REVERT, Context.MethodExit)
                }
                is TACCmd.Simple.CallCore -> {
                    TraceEventWithContext(TraceEventSort.EXTERNAL_CALL, Context.ExternalCall(callee = origCommand.to, value = origCommand.value))
                }
                is TACCmd.Simple.LogCmd -> {
                    TraceEventWithContext(TraceEventSort.LOG, Context.Log(origCommand.args.drop(2).toTreapList()))
                }
                else -> null
            }
        }

        /**
         * Helper function, generates a variable with a fresh name, that won't be callindexed.
         */
        private fun globalStateInstrumentationVar(prefix: String, tag: Tag = Tag.Bit256) = TACSymbol.Var(
            prefix,
            tag,
            TACSymbol.Var.DEFAULT_INDEX,
            MetaMap(TACMeta.NO_CALLINDEX)
        ).toUnique("!u")

        /**
         * Marks a variable as being the global state accumulator. Used for debugging.
         */
        private val GLOBAL_STATE_ACCUM : MetaKey<Nothing> = MetaKey.Nothing("trace.global.state.marker")

        /**
         * the `stateLookup` global variable described in the External State modelling.
         */
        private val GLOBAL_STATE_LKP = TACSymbol.Var("GlobalStateValues", Tag.GhostMap(
            listOf(Tag.Bit256, Tag.Bit256), Tag.Bit256
        ), NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))

        /**
         * The `witnessMap` described in "On Storage"
         */
        private val STORAGE_STATE_INCLUSION_MAP = TACSymbol.Var("StorageInclusionMap", Tag.GhostMap(
            listOf(Tag.Bit256), Tag.Bit256
        ), NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))

        private val staticInstrumentationVars = listOf(GLOBAL_STATE_LKP, STORAGE_STATE_INCLUSION_MAP)

        private val INSTRUMENTATION_ID = MetaKey<Int>("buffer.trace.buffer.id")

        /**
         * Marks a variable as being part of instrumentation. Used for debugging
         */
        val BUFFER_INSTRUMENTATION = MetaKey.Nothing("buffer.trace.marker")

        /**
         * Generate an instrumentation variable with type [tag], whose name is built
         * with prefix [pref] for the long read with [id].
         *
         * Unlike [globalStateInstrumentationVar], the variables returned from this *are* eligible for call indexing.
         */
        private fun instrumentationVar(pref: String, id: Int, tag: Tag) : TACSymbol.Var {
            return TACSymbol.Var(
                "$pref!$id",
                tag,
                NBId.ROOT_CALL_ID,
                MetaMap(BUFFER_INSTRUMENTATION) + TACMeta.NO_CALLINDEX + (INSTRUMENTATION_ID to id)
            ).toUnique("!u")
        }

        private fun Int.instrumentationVar(pref: String, tag: Tag = Tag.Bit256) = instrumentationVar(pref, this, tag)

        fun ToTACExpr.lift() = TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>(
            this.toTACExpr(),
            setOf(),
            listOf()
        )

        /**
         * Instrument [code] according to [options]. [code] must have been "derived" (in some fuzzy sense) from the body
         * of [context]. That is, [context] is relied upon for information about, e.g., the codedata accessed
         * in [code] or the storage.
         */
        fun instrument(code: CoreTACProgram, context: InstrumentationContext, options: InstrumentationControl) : InstrumentationResults {
            val g = code.analysisCache.graph
            val mca = MustBeConstantAnalysis(
                graph = g
            )

            /**
             * Find all writes which will be subsumbed by copy loops later.
             */
            val loopWrites = code.parallelLtacStream().mapNotNull { lc ->
                if (lc.cmd !is TACCmd.Simple.SummaryCmd) {
                    return@mapNotNull null
                }
                if (lc.cmd.summ !is LoopCopyAnalysis.LoopCopySummary) {
                    return@mapNotNull null
                }
                lc.cmd.summ.summarizedBlocks.takeIf {
                    lc.cmd.summ.isMemoryByteCopy()
                }
            }.flatMap { it.stream() }.collect(Collectors.toSet())
            /**
             * long read ids start at 1, for the janky reason that we need some way to express
             * in tac bytecode a "null" value for the long copy source of a buffer in [BufferContentsInstrumentation].
             * So we use 0 for that, and start ids from 1.
             *
             * We could have solved this with yet another instrumentation map, but compactness of instrumentation seems
             * desirable here.
             */
            val idCounter = AtomicInteger(1)

            val strictDefAnalysis = g.cache.strictDef

            fun isNullRead(len: TACSymbol, where: CmdPointer) = strictDefAnalysis.source(where, len) == StrictDefAnalysis.Source.Const(BigInteger.ZERO)

            /**
             * Find all long reads (called "sources" here because they are the source of the analysis).
             */
            val sources = code.parallelLtacStream().filter {
                it.ptr.block !in loopWrites
            }.mapNotNull {
                if(it.cmd is TACCmd.Simple.SummaryCmd) {
                    if(it.cmd.summ is ScalarEquivalenceSummary) {
                        /**
                         * Include summaries of pure internal functions into the call trace by pretending
                         * all internal functions summaries consume a null buffer.
                         *
                         * This is an instance of faking arguments to get existing APIs to work; internal function calls
                         * do not consume buffers at all. HOWEVER, I originally tried to implement this properly, and it was
                         * a *nightmare*; this is by far the lesser of two evils. Because the hashes of a null
                         * buffer are deterministic (they are always 0), the inclusion of this hash is *fine* and doesn't effect
                         * soundness.
                         */
                        return@mapNotNull EventLongRead(
                            where = it.ptr,
                            length = TACSymbol.Zero,
                            loc = TACSymbol.Zero,
                            id = idCounter.getAndIncrement(),
                            isNullRead = true,
                            explicitReadId = null,
                            traceEventInfo = TraceEventWithContext(
                                it.cmd.summ.sort,
                                it.cmd.summ.asContext,
                            ),
                            baseMap = TACKeyword.MEMORY.toVar() // it's a FAAAAKE
                        )
                    }
                    if(it.cmd.summ !is LoopCopyAnalysis.LoopCopySummary) {
                        return@mapNotNull null
                    }
                    if(!it.cmd.summ.isMemoryByteCopy()) {
                        return@mapNotNull null
                    }
                    val lenVar = it.cmd.summ.lenVars.first()
                    return@mapNotNull BasicLongRead(
                        where = it.ptr,
                        loc =  it.cmd.summ.inPtr.first(),
                        length = lenVar,
                        id = idCounter.getAndIncrement(),
                        isNullRead = isNullRead(lenVar, it.ptr),
                        baseMap = it.cmd.summ.sourceMap
                    )
                } else if(it.cmd !is TACCmd.Simple.LongAccesses) {
                    // anything else that's not a long access is, by definition, not a long read
                    return@mapNotNull null
                }
                /**
                 * Find the unique [vc.data.TACCmd.Simple.LongAccess] which
                 * is a read from memory, skipping if there isn't one
                 */
                val read = it.cmd.accesses.singleOrNull { la ->
                    !la.isWrite && TACMeta.EVM_MEMORY in la.base.meta
                } ?: return@mapNotNull null

                /**
                 * Get the (optional) [TraceEventWithContext] if this command is an event long read
                 */
                val traceInfo = getTraceEvent(it.cmd)
                val nullRead = isNullRead(read.length, it.ptr)
                if(traceInfo == null) {
                    BasicLongRead(where = it.ptr, loc = read.offset, length = read.length, id = idCounter.getAndIncrement(), isNullRead = nullRead, baseMap = read.base)
                } else {
                    EventLongRead(
                        where = it.ptr,
                        loc = read.offset,
                        length = read.length,
                        id = idCounter.getAndIncrement(),
                        isNullRead = nullRead,
                        traceEventInfo = traceInfo,
                        explicitReadId = it.cmd.meta.find(DefiniteBufferConstructionAnalysis.LONG_READ_ID),
                        baseMap = read.base
                    )
                }

            }.collect(Collectors.toSet()) + options.forceMloadInclusion.map {
                /**
                 * Also find those byte loads for which we need to include some instrumentation, and generate
                 * a synthetic "long read" for that.
                 */
                val mload = g.elab(it.key).narrow<TACCmd.Simple.AssigningCmd.ByteLoad>()
                BasicLongRead(
                    loc = mload.cmd.loc,
                    where = it.key,
                    id = idCounter.getAndIncrement(),
                    length = EVM_WORD_SIZE.asTACSymbol(),
                    isNullRead = false,
                    baseMap = mload.cmd.base
                )
            }
            val patternMatcher = PatternMatcher.compilePattern(graph = g, patt = scratchPattern)

            /**
             * Find long reads that look like they are using up a scratch buffer, which means that writes *after* that use
             * are likely not related to writes *before* the scratch use.
             *
             * These are uses whose source offset lies in constant scratch space range or which is the
             * value of the free pointer.
             */
            val mayConsumeScratch = sources.filter { longSource ->
                when(val src = strictDefAnalysis.source(ptr = longSource.where, sym = longSource.loc)) {
                    is StrictDefAnalysis.Source.Uinitialized -> false
                    is StrictDefAnalysis.Source.Const -> src.n >= BigInteger.ZERO && src.n < 0x40.toBigInteger()
                    is StrictDefAnalysis.Source.Defs -> src.ptrs.singleOrNull()?.let { defSite ->
                        g.elab(defSite).maybeNarrow<TACCmd.Simple.AssigningCmd>()?.let {
                            patternMatcher.queryFrom(it)
                        } is PatternMatcher.ConstLattice.Match
                    } == true
                } && (longSource as? EventLongRead)?.explicitReadId == null && !longSource.isNullRead
            }.toSet()

            val sourceLanguage = (context.containingContract as? IContractWithSource)?.src?.lang

            /**
             * In addition to the above, we also treat an update of the free pointer as GC point. The utility of this particular
             * heuristic is debatable, but why not
             */
            val garbageCollectionPoints = mayConsumeScratch.mapToSet { it.where } + code.parallelLtacStream().filter { lc ->
                lc.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf {
                    TACMeta.EVM_MEMORY in it.cmd.base.meta
                }?.cmd?.loc?.let { loc ->
                    mca.mustBeConstantAt(where = lc.ptr, v = loc)
                } == 0x40.toBigInteger() && sourceLanguage == SourceLanguage.Solidity
            }.map { it.ptr }.collect(Collectors.toSet())
            val reach = code.analysisCache.reachability

            // TODO(CERT-8862): we should also consider allocations as "future consumers"
            val mustPathInclusion = MustPathInclusionAnalysis.computePathInclusion(g)
            // map from consumption sites to the future consumption sites which we should treat as garbage collections
            val isGarbageCollectionFor = garbageCollectionPoints.associateWith { gcPoint ->
                mayConsumeScratch.filter { futureConsumer ->
                    futureConsumer.where != gcPoint &&
                        reach.canReach(gcPoint, futureConsumer.where) &&
                        // is there another scratch consumer along the path? then we should instrument at that one
                        mustPathInclusion[gcPoint.block to futureConsumer.where.block].orEmpty().let { alongPath ->
                            garbageCollectionPoints.all {
                                it == futureConsumer.where || it.block !in alongPath || it == gcPoint
                            }
                        }
                }
            }

            /**
             * If we are tracking GC information for a long read, make sure to generate that information
             */
            val hasPrecedingGCPoint = isGarbageCollectionFor.entries.flatMapToSet { (_, futureConsumers) ->
                futureConsumers
            }

            /**
             * Now, for each [LongRead], generate its [LongReadInstrumentation].
             */
            val sourceToInstrumentation = mutableMapOf<LongRead, LongReadInstrumentation>()
            for(s in sources) {
                val useSiteControl = options.useSiteControl[s.where]
                val i = s.id
                sourceToInstrumentation[s] = i.run {
                    LongReadInstrumentation(
                        hashVar = instrumentationVar("bufferHash"),
                        baseProphecy = instrumentationVar("bufferBaseProphecy"),
                        lengthProphecy = instrumentationVar("bufferLengthProphecy"),
                        gcInfo = null.letIf(s in hasPrecedingGCPoint && (s !is EventLongRead || s.explicitReadId == null)) { _ ->
                            GarbageCollectionInfo(
                                writeBoundVars = instrumentationVar("lower") to instrumentationVar("upper"),
                                hashBackupVar = instrumentationVar("hashBackup"),
                                gcInitHashVar = instrumentationVar("gcInitHashProphecy"),
                                seenGCVar = instrumentationVar("seenGCPoint")
                            )
                        },
                        allAlignedVar = instrumentationVar("allAligned", Tag.Bool),
                        bufferWriteInfo = if(useSiteControl?.trackBufferContents == true) {
                            BufferContentsInstrumentation(
                                bufferOffsetHolder = instrumentationVar("bufferWriteInd", Tag.ByteMap),
                                bufferValueHolder = instrumentationVar("bufferWriteValue", Tag.ByteMap),
                                bufferWriteCountVar = instrumentationVar("bufferWriteCount", Tag.Bit256),
                                preciseBuffer = instrumentationVar("bufferIsPrecise", Tag.ByteMap),
                                bufferCopySource = instrumentationVar("bufferCopies", Tag.ByteMap)
                            )
                        } else { null },
                        eventSiteVisited = useSiteControl?.traceReached?.let(::EventSiteVisitedTracker),
                        id = i,
                        preciseBoundedWindow = options.forceMloadInclusion[s.where]?.let { windowSize ->
                            BoundedPreciseCellInstrumentation(
                                windowSize = windowSize,
                                writeCountVar = instrumentationVar("writeCount", Tag.Bit256),
                                shadowAccum = instrumentationVar("shadowAccumOf", Tag.Bit256),
                                finalWriteCountProphecy = instrumentationVar("writeCountProphecy", Tag.Bit256)
                            )
                        }
                    )
                }
            }
            /**
             * Package up this information we've precomputed, and actually do the work in the [BufferTraceInstrumentation] object
             */
            val worker = BufferTraceInstrumentation(
                code = code,
                readToInstrumentation = sourceToInstrumentation,
                isGarbageCollectionFor = isGarbageCollectionFor,
                options = options,
                context = context
            )

            val instrumented = worker.instrumentInContext()

            /**
             * Extract some of the instrumentation info from this.
             *
             */
            val useSiteInfo = sources.associate {
                val id = it.id
                val inst = sourceToInstrumentation[it]!!
                val useSiteControl = options.useSiteControl[it.where]
                it.where to UseSiteInfo(
                    id = id,
                    instrumentation = useSiteControl?.let { usc ->
                        UseSiteInstrumentation(
                            lengthVar = inst.lengthProphecy,
                            bufferWrites = if(usc.trackBufferContents) {
                                inst.bufferWriteInfo!!
                            } else {
                                null
                            },
                            baseVar = inst.baseProphecy
                        )
                    },
                    traceReport = (it as? EventLongRead)?.let {
                        worker.traceInclusionManager.getTraceSiteReport(it)
                    }
                )
            }
            return InstrumentationResults(
                code = instrumented,
                useSiteInfo = useSiteInfo,
                traceVariables = worker.traceInstrumentation
            )
        }

        /**
         * From a [TraceIndexMarker] extract the [RawEventParams].
         */
        fun extractEvent(
            marker: TraceIndexMarker
        ) : Either<RawEventParams, String> {
            fun mismatch() = "Context sort mismatch: trace object identifies as ${marker.eventSort} but have ${marker.context}".toRight()
            when(marker.eventSort) {
                TraceEventSort.REVERT,
                TraceEventSort.RETURN -> {
                    if(marker.context !is Context.MethodExit) {
                        // there is no actual info here, but worth doing the sanity check...
                        return mismatch()
                    }
                    return RawEventParams.ExitParams(marker.eventSort).toLeft()
                }
                TraceEventSort.LOG -> {
                    if(marker.context !is Context.Log) {
                        return mismatch()
                    }
                    return RawEventParams.LogTopics(marker.context).toLeft()
                }
                TraceEventSort.EXTERNAL_CALL -> {
                    if(marker.context !is Context.ExternalCall) {
                        return mismatch()
                    }
                    return RawEventParams.ExternalCallParams(
                        context = marker.context
                    ).toLeft()
                }
                TraceEventSort.INTERNAL_SUMMARY_CALL -> {
                    if(marker.context !is Context.InternalCall) {
                        return mismatch()
                    }
                    return RawEventParams.InternalSummaryParams(
                        context = marker.context
                    ).toLeft()
                }
            }
        }

    }

    /**
     * Representation of the `calldatacopy` write sort. [sourceOffset] is the symbol used as the offset
     * into calldata for the copy.
     */
    private class CopyFromCalldata(
        val sourceOffset: TACSymbol,
    ) : WriteSource, IWriteSource.EnvCopy {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.True
        }

        override val sort: WriteSort
            get() = WriteSort.CALLDATA_COPY

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return sourceOffset.lift()
        }

        override val extraContext: TACSymbol?
            get() = null
        override val baseMap: TACSymbol.Var
            get() = TACKeyword.CALLDATA.toVar()
        override val sourceLoc: TACSymbol
            get() = sourceOffset
    }

    private class CodeCopy(
        val codeCopyHash: BigInteger
    ) : WriteSource, IWriteSource.Other {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.True
        }

        override val sort: WriteSort
            get() = WriteSort.STATIC_CODE_COPY

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return codeCopyHash.asTACExpr.lift()
        }

        override val extraContext: TACSymbol?
            get() = null

    }

    private class UnknownEnvCopy(
        val nondetVariable: TACSymbol.Var
    ) : WriteSource, IWriteSource.Other {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.False
        }

        override val sort: WriteSort
            get() = WriteSort.UNKNOWN_COPY

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return TACExprWithRequiredCmdsAndDecls(
                exp = nondetVariable.asSym(),
                declsToAdd = listOf(nondetVariable),
                cmdsToAdd = listOf()
            )
        }

        override val extraContext: TACSymbol?
            get() = null


    }


    /**
     * Representation of the `returndatacopy` write sort. [sourceOffset] is as in [CopyFromCalldata].
     * [accumulatorVar] is a capture of the current value of [globalStateAccumulator], recording which
     * numbered call populated returndata.
     */
    private class CopyFromReturnBuffer(
        val sourceOffset: TACSymbol,
        val accumulatorVar: TACSymbol.Var
    ) : WriteSource, IWriteSource.EnvCopy {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.True
        }

        override val sort: WriteSort
            get() = WriteSort.RETURNDATA_COPY

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return sourceOffset.lift()
        }

        override val extraContext: TACSymbol
            get() = accumulatorVar
        override val baseMap: TACSymbol.Var
            get() = TACKeyword.RETURNDATA.toVar()
        override val sourceLoc: TACSymbol
            get() = sourceOffset
    }

    /**
     * Common interface used for writes whose source is described by another long read (i.e.,
     * copy loops and mcopy).
     */
    private interface WriteFromLongRead : WriteSource {
        /**
         * The source offset in memory. Should "agree" with the [LongReadInstrumentation.baseProphecy]
         * in [sourceInstrumentation].
         */
        val sourceOffset: TACSymbol

        /**
         * The length of the buffer. Should "agree" with the [LongReadInstrumentation.lengthProphecy]
         * of [sourceInstrumentation]
         */
        val sourceLength: TACSymbol

        /**
         * The [LongReadInstrumentation] used to describe the long read which is the source of this long *write*.
         */
        val sourceInstrumentation: LongReadInstrumentation

        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return sourceInstrumentation.allAlignedVar
        }

        override val extraContext: TACSymbol?
            get() = null

        /**
         * The representative of this read is the buffer identity. We can't use [getBufferIdentity] because
         * this isn't an inner class :(
         */
        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            val bufferHash = TACKeyword.TMP(Tag.Bit256, "!bufferHash")
            return TACExprWithRequiredCmdsAndDecls(with(TACExprFactTypeCheckedOnlyPrimitives) {
                ite(
                    sourceInstrumentation.allAlignedVar,
                    bufferHash,
                    sourceInstrumentation.hashVar
                )
            }, setOfNotNull(bufferHash, sourceOffset as? TACSymbol.Var, sourceLength as? TACSymbol.Var, sourceInstrumentation.allAlignedVar, sourceInstrumentation.hashVar), listOf(
                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                    lhs = bufferHash,
                    memBaseMap = baseMap,
                    op1 = sourceOffset,
                    op2 = sourceLength
                )
            ))
        }

        val baseMap: TACSymbol.Var
    }

    /**
     * Representation of the `store` write sort. Private version of [IWriteSource.ByteStore]
     */
    private class ByteStore(
        override val writeSymbol: TACSymbol
    ) : WriteSource, IWriteSource.ByteStore {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.True
        }

        override val sort: WriteSort
            get() = WriteSort.STORE

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return writeSymbol.lift()
        }

        override val extraContext: TACSymbol?
            get() = null

    }

    /**
     * Representation of store8. Given the [IWriteSource.Other] because no
     * instrumentation mixin handles this precisely.
     */
    private class ByteStoreSingle(
        val writeSymbol: TACSymbol
    ) : WriteSource, IWriteSource.Other {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TACSymbol.False
        }

        override val sort: WriteSort
            get() = WriteSort.STORE_SINGLE

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            val narrowed = TACKeyword.TMP(Tag.Bit256, "!narrowed")
            return TACExprWithRequiredCmdsAndDecls(
                narrowed.toTACExpr(), setOfNotNull(narrowed, writeSymbol as? TACSymbol.Var), listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = narrowed,
                        rhs = TACExpr.BinOp.BWAnd(writeSymbol.asSym(), MASK_SIZE(8).asTACExpr, tag = Tag.Bit256),
                    )
                )
            )
        }

        override val extraContext: TACSymbol?
            get() = null

    }

    /**
     * Loop copy variant of [WriteFromLongRead]. This write type isn't actually
     * described in the EC paper, but it operates equivalently to the mcopy.
     */
    private class LoopCopy(
        override val sourceOffset: TACSymbol,
        override val sourceLength: TACSymbol,
        override val sourceInstrumentation: LongReadInstrumentation,
        override val baseMap: TACSymbol.Var
    ) : WriteFromLongRead, IWriteSource.LongMemCopy {
        override val sort: WriteSort
            get() = WriteSort.LOOP_COPY

        override val sourceBuffer: ILongReadInstrumentation
            get() = sourceInstrumentation
    }

    /**
     * mcopy variant of [WriteFromLongRead]
     */
    private class McopyBufferRead(
        override val sourceInstrumentation: LongReadInstrumentation,
        override val sourceOffset: TACSymbol,
        override val sourceLength: TACSymbol,
        override val baseMap: TACSymbol.Var
    ) : WriteFromLongRead, IWriteSource.LongMemCopy {
        override val sort: WriteSort
            get() = WriteSort.BUFFER_COPY

        override val sourceBuffer: ILongReadInstrumentation
            get() = sourceInstrumentation
    }


    private enum class WriteSort {
        LOOP_COPY,
        CALLDATA_COPY,
        BUFFER_COPY,
        STORE_SINGLE,
        STORE,
        RETURNDATA_COPY,
        UNKNOWN_COPY,
        STATIC_CODE_COPY
    }
    /**
     * Private version of [IBufferUpdate], where the [source] field is an intersection of [IWriteSource]
     * and [WriteSource], letting it pull double duty as the private version of the type and the public version.
     */
    private data class BufferUpdate<T>(
        val where: CmdPointer,
        val loc: TACSymbol,
        val length: TACSymbol = EVM_WORD_SIZE.asTACSymbol(),
        val buffer: TACSymbol.Var,
        /**
         * Indicates whether the instrumentation for this command should be inserted before or after the command.
         * For all updates besides callcopy, this is true.
         */
        val isBefore: Boolean = true,
        val source: T
    ) : IBufferUpdate where T : IWriteSource, T : WriteSource {
        override val updateSource: IWriteSource
            get() = source
        override val updateLoc: TACSymbol
            get() = loc

        override val len: TACSymbol
            get() = length
    }

    /**
     * Internal version of [ILongReadInstrumentation]; represents the basic
     * information required for instrumentation as in [ILongReadInstrumentation],
     * as well as instrumentation mixins which act as "hooks" in the instrumentation process.
     */
    private data class LongReadInstrumentation(
        override val id: Int,
        override val hashVar: TACSymbol.Var,
        override val lengthProphecy: TACSymbol.Var,
        override val baseProphecy: TACSymbol.Var,
        override val allAlignedVar: TACSymbol.Var,

        // mixin modules
        /**
         * Instrumentation variables recording the garbage collection information.
         */
        val gcInfo: GarbageCollectionInfo?,
        /**
         * Instrumentation variables tracking the writes that define a buffer
         */
        val bufferWriteInfo: BufferContentsInstrumentation?,
        /**
         * Instrumentation variables to record whether the event site was hit
         */
        val eventSiteVisited: EventSiteVisitedTracker?,
        /**
         * Instrumentation variables to maintain the shadow memory cell. Only non-null
         * for those mload commands whose inclusion was forced via [InstrumentationControl]
         */
        val preciseBoundedWindow: BoundedPreciseCellInstrumentation?
//        val ordinalWriteTracking: OrdinalWriteTracker?
    ) : WithVarInit, ILongReadInstrumentation {
        override val havocInitVars get() = listOf(lengthProphecy, baseProphecy)

        val instrumentationMixins : List<InstrumentationMixin> get() = listOfNotNull(gcInfo, bufferWriteInfo, eventSiteVisited, preciseBoundedWindow)

        override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>> = listOf(
            hashVar to TACSymbol.Zero,
            allAlignedVar to TACSymbol.True
        )
    }

    /**
     * Wrapper class holding the instrumentation which performs the instrumentation updates for some write [update],
     * along with the flag [isBefore] which indicates whether this should be inserted before/after the original command.
     */
    private data class BufferWriteInstrumentation(val update: CommandWithRequiredDecls<TACCmd.Simple>, val isBefore: Boolean) {
        fun mergeOriginal(orig: TACCmd.Simple) : CommandWithRequiredDecls<TACCmd.Simple> {
            return mergeOriginal(CommandWithRequiredDecls(orig))
        }

        /**
         * Merge the instrumentation in [update] with the representation of the original command [orig].
         */
        fun mergeOriginal(orig: CommandWithRequiredDecls<TACCmd.Simple>): CommandWithRequiredDecls<TACCmd.Simple> {
            return if(isBefore) {
                update.merge(orig)
            } else {
                orig.merge(update)
            }
        }
    }

    /**
     * For some gc site for later long reads in [laterConsumers] do the setup for those long reads.
     * That is, set the "seen" flags, backup the current hash, etc.
     */
    private fun generateGCSetup(
        laterConsumers: Iterable<LongRead>
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        return laterConsumers.map {
            val readInfo = readToInstrumentation[it]!!
            check(readInfo.gcInfo != null) {
                "Have null gc instrumentation for $readInfo"
            }
            val gcInfo = readInfo.gcInfo
            val isFreshSym = TACSymbol.Var("isFreshGc", Tag.Bool).toUnique("!")
            with(TACExprFactTypeCheckedOnlyPrimitives) {
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.LabelCmd("Starting setup for ${it.id}"),
                        isFreshSym `=` (gcInfo.seenGCVar eq TACExpr.zeroExpr),
                        gcInfo.hashBackupVar `=` ite(
                            isFreshSym,
                            readInfo.hashVar,
                            gcInfo.hashBackupVar
                        ),
                        gcInfo.seenGCVar `=` TACSymbol.One,
                        readInfo.hashVar `=` ite(
                            isFreshSym,
                            gcInfo.gcInitHashVar,
                            readInfo.hashVar
                        ),
                        TACCmd.Simple.LabelCmd("End setup for ${it.id}"),
                    ), setOf(isFreshSym)
                )
            }
        }.flatten()
    }

    /**
     * For some command at [lc], return the [BufferUpdate] object describing how this command affects memory,
     * or null if it does not.
     */
    private fun getBufferUpdateFor(lc: LTACCmd) : BufferUpdate<*>? {
        when(lc.cmd) {
            is TACCmd.Simple.SummaryCmd -> {
                if (lc.cmd.summ !is LoopCopyAnalysis.LoopCopySummary || !lc.cmd.summ.isMemoryByteCopy()) {
                    return null
                }
                /**
                 * We expect this to succeed as we should have a long read created for copy loops.
                 */
                val thisCopySource = sources.find {
                    it.where == lc.ptr
                }!!
                return BufferUpdate(where = lc.ptr, loc = lc.cmd.summ.outPtr.first(), length = lc.cmd.summ.lenVars.first(), buffer = lc.cmd.summ.destMap, source = LoopCopy(
                    sourceOffset = lc.cmd.summ.inPtr.first(),
                    sourceLength = lc.cmd.summ.lenVars.first(),
                    sourceInstrumentation = readToInstrumentation[thisCopySource]!!,
                    baseMap = lc.cmd.summ.sourceMap,
                ))
            }
            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                return BufferUpdate(where = lc.ptr, loc = lc.cmd.loc, buffer = lc.cmd.base, source = ByteStore(lc.cmd.value))
            }
            is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                return BufferUpdate(where = lc.ptr, loc = lc.cmd.loc, length = TACSymbol.One, buffer = lc.cmd.base, source = ByteStoreSingle(lc.cmd.value))
            }
            is TACCmd.Simple.LongAccesses -> {
                return when(lc.cmd) {
                    /**
                     * None of these long access command write memory
                     */
                    is TACCmd.Simple.AssigningCmd.AssignSha3Cmd,
                    is TACCmd.Simple.LogCmd,
                    is TACCmd.Simple.ReturnCmd,
                    is TACCmd.Simple.RevertCmd -> null
                    is TACCmd.Simple.ByteLongCopy -> {
                        if(TACMeta.EVM_MEMORY !in lc.cmd.dstBase.meta) {
                            return null
                        }
                        /**
                         * look at the source to determine the type of write
                         */
                        if(lc.cmd.srcBase == TACKeyword.CALLDATA.toVar()) {
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = CopyFromCalldata(lc.cmd.srcOffset)
                            )
                        } else if(lc.cmd.srcBase == TACKeyword.RETURNDATA.toVar()) {
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = CopyFromReturnBuffer(
                                    sourceOffset = lc.cmd.srcOffset,
                                    accumulatorVar = globalStateAccumulator
                                )
                            )
                        } else if(TACMeta.MCOPY_BUFFER in lc.cmd.srcBase.meta) {
                            /**
                             * It is surprisingly clunky finding the corresponding write. Basically, we need to find all long copies
                             * to memory which target this mcopy buffer and which can reach this read
                             */
                            val potentialDefinitions = sources.filter {
                                g.elab(it.where).maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.let { blc ->
                                    blc.cmd.dstBase == lc.cmd.srcBase && reach.canReach(blc.ptr, lc.ptr)
                                } == true
                            }

                            /**
                             * But due to loop unrolling, there will be multiple such writes,
                             * so find the one that is dominated by all others. We probably can, and should,
                             * weaken this to reachability.
                             */
                            val definingCopy = potentialDefinitions.withIndex().single { (idx, src) ->
                                potentialDefinitions.withIndex().all { (otherIdx, otherSrc) ->
                                    otherIdx == idx || g.cache.domination.dominates(otherSrc.where, src.where)
                                }
                            }.value
                            val shadowCopySource = readToInstrumentation[definingCopy]!!
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = McopyBufferRead(
                                    sourceOffset = definingCopy.loc,
                                    sourceLength = definingCopy.length,
                                    sourceInstrumentation = shadowCopySource,
                                    baseMap = definingCopy.baseMap
                                )
                            )
                        } else if(TACMeta.CODEDATA_KEY in lc.cmd.srcBase.meta) {
                            val src = (context.containingContract as? IContractWithSource)?.src
                            val where = (lc.cmd.srcOffset as? TACSymbol.Const)?.value
                            val length = (lc.cmd.length as? TACSymbol.Const)?.value
                            if(src == null || where == null || length == null || ((where + length) * BigInteger.TWO).intValueExact() > src.bytecode.length) {
                                return BufferUpdate(
                                    where = lc.ptr,
                                    loc = lc.cmd.dstOffset,
                                    length = lc.cmd.length,
                                    buffer = lc.cmd.dstBase,
                                    source = UnknownEnvCopy(
                                        TACKeyword.TMP(Tag.Bit256, "!unknownCopy")
                                    )
                                )
                            }
                            val startIndex = where.intValueExact() * 2
                            val len = length.intValueExact() * 2
                            val codeKeccak = applyKeccak(hexStringToBytes(src.bytecode.substring(
                                startIndex,
                                startIndex + len
                            )))
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = CodeCopy(
                                    codeKeccak
                                )
                            )
                        } else {
                            throw UnsupportedOperationException("Don't know how to model copy from ${lc.cmd.srcBase}: $lc")
                        }
                    }
                    is TACCmd.Simple.CallCore -> {
                        /**
                         * NB that we are using the [callCoreOutputVars] here because the amount copied isn't returndatasize or
                         * the declare outsize, but the min of the two, which have to compute ourselves 🫠
                         */
                        return BufferUpdate(
                            lc.ptr,
                            lc.cmd.outOffset,
                            length = callCoreOutputVars[lc.ptr]!!,
                            buffer = lc.cmd.outBase,
                            isBefore = false,
                            CopyFromReturnBuffer(sourceOffset = TACSymbol.Zero, accumulatorVar = globalStateAccumulator)
                        )
                    }
                }
            }
            else -> return null
        }
    }

    /**
     * Performs the environment interaction modelling described in the EC paper section "Modeling External Interaction".
     */
    private fun updateReturnState(
        origLc: LTACCmdView<TACCmd.Simple.CallCore>
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val origCommand = origLc.cmd

        val stateHolder = TACSymbol.Var("nextStatePre", Tag.Bit256).toUnique("!")

        /**
         * This is initialized to be equal to [globalStateAccumulator]
         */
        val stateCopy = TACSymbol.Var("nextState", Tag.Bit256).toUnique("!")

        /**
         * `rc = stateLookup[stateCopy][0]`
         */
        val rcExpr = with(TACExprFactTypeCheckedOnlyPrimitives) {
            Select(GLOBAL_STATE_LKP.asSym(), stateCopy.asSym(), TACExpr.zeroExpr)
        }

        /**
         * `returndatasize = stateLookup[stateCopy][1]`
         */
        val returnDataSizeExpr = with(TACExprFactTypeCheckedOnlyPrimitives) {
            Select(GLOBAL_STATE_LKP.asSym(), stateCopy.asSym(), 1.asTACExpr)
        }

        /**
         * `returndata = [ i -> stateLookup[stateCopy][i + 2] ]`
         */
        val returnDataIdx = TACKeyword.TMP(Tag.Bit256, "!idx")
        val returnDataExpr = with(TACExprFactTypeCheckedOnlyPrimitives) {
            TACExpr.MapDefinition(
                listOf(returnDataIdx.asSym()),
                Select(GLOBAL_STATE_LKP.asSym(), stateCopy.asSym(), Add(returnDataIdx.asSym(), 2.asTACExpr)),
                Tag.ByteMap
            )
        }

        /**
         * The variable chosen to hold how much is copied out of the external call.
         */
        val copyAmount = callCoreOutputVars[origLc.ptr]!!

        val calleeCodesize = TACKeyword.TMP(Tag.Bit256, "calleeCodesize")

        /**
         * Add the sanity constraints w.r.t. callee codesize, returncode etc.
         */
        val returnSizeConstraint = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.WordLoad(
                lhs = calleeCodesize,
                base = EthereumVariables.extcodesize,
                loc = origCommand.to
            )
        ), setOf(calleeCodesize, EthereumVariables.extcodesize)) andThen ExprUnfolder.unfoldPlusOneCmd("constrainReturnSize", TACExprFactTypeCheckedOnlyPrimitives {
            (TACKeyword.RETURN_SIZE.toVar() lt BigInteger.TWO.pow(32).asTACExpr) and (TACKeyword.RETURNCODE.toVar() le TACSymbol.One)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "returnsize setup")
        /**
         * any call to an empty account returns an empty buffer and succesfully returns
         */
        } andThen ExprUnfolder.unfoldPlusOneCmd("eoaSanity", TACExprFactoryExtensions.run {
            (not(calleeCodesize eq 0) or
                ((TACKeyword.RETURN_SIZE.toVar() eq 0) and (TACKeyword.RETURNCODE.toVar() eq 1))) and (calleeCodesize lt 24576)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "eoa coherence")
        } andThen CommandWithRequiredDecls(listOf(
            /**
             * The amount copied by this call command is
             * `min(c.out, returndatasize)`
             *
             * No it is not zero padded, it just copies less :<
             *
             * Note that this is initializing the variable used in the [BufferUpdate] object
             * for the implicit write of this callcore. This was the least bad way I could think of.
             */
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = copyAmount,
                rhs = TACExprFactTypeCheckedOnlyPrimitives {
                    ite(
                        TACKeyword.RETURN_SIZE.toVar() lt origCommand.outSize,
                        TACKeyword.RETURN_SIZE.toVar(),
                        origCommand.outSize
                    )
                }
            )
        ), setOf(copyAmount))

        /**
         * Actually set the environment variables
         */
        val callUpdate = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = TACKeyword.RETURNCODE.toVar(),
                rhs = rcExpr
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = TACKeyword.RETURN_SIZE.toVar(),
                rhs = returnDataSizeExpr
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = TACKeyword.RETURNDATA.toVar(),
                rhs = returnDataExpr
            )
        ), setOf(
            TACKeyword.RETURNCODE.toVar(),
            TACKeyword.RETURNDATA.toVar(),
            TACKeyword.RETURN_SIZE.toVar()
        ))
        /**
         * Copy the global state, increment it
         */
        return CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = stateCopy,
                rhs = globalStateAccumulator.asSym(),
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = globalStateAccumulator,
                rhs = TACExpr.Vec.Add(stateCopy.asSym(), 1.asTACExpr)
            ),
        ), setOf(stateCopy)).merge(callUpdate).merge(returnSizeConstraint).merge(
            // record the call command
            TACCmd.Simple.AnnotationCmd(
                CallEvent.META_KEY,
                CallEvent(
                    callee = origCommand.to,
                    bufferLength = origCommand.inSize,
                    ordinal = stateCopy,
                    bufferStart = origCommand.inOffset,
                    memoryCapture = origCommand.inBase,
                    calleeCodeSize = calleeCodesize,
                    returnCode = TACKeyword.RETURNCODE.toVar(),
                    returnDataSize = TACKeyword.RETURN_SIZE.toVar(),
                    value = origCommand.value
                )
            )
        // update memory with the copy amount
        ).merge(TACCmd.Simple.ByteLongCopy(
            dstBase = origCommand.outBase,
            length = copyAmount,
            dstOffset = origCommand.outOffset,
            srcOffset = TACSymbol.Zero,
            srcBase = TACKeyword.RETURNDATA.toVar()
        )).merge(globalStateAccumulator, stateCopy, stateHolder)
    }

    /**
     * Enum indicating whether a long read is included in a trace when
     * using [UntilNumberedEntryManager]; "before" and "after" refer to the
     * target event number
     */
    private enum class IncludedInTrace {
        DEFINITELY_BEFORE,
        DEFINITELY_AFTER,
        MAYBE_INCLUDED,
        DEFINITELY_INCLUDED
    }

    /**
     * [TraceInclusionManager] which only logs the nth event, as ddetermined by [TraceInclusionMode.Until.traceNumber].
     */
    private inner class UntilNumberedEntryManager(
        reads: Set<LongRead>,
        val code: CoreTACProgram,
        val target: TraceInclusionMode.Until,
        logLevel: TraceTargets,
        overrides: Map<CmdPointer, TraceOverrideSpec>
    ) : TraceInclusionManager(overrides, logLevel) {
        private val relevantLongReads: Set<CmdPointer>
        private val traceNumbering: Map<CmdPointer, IntValue>

        private val idxAsBig = target.traceNumber.toBigInteger()

        init {
            val g = code.analysisCache.graph
            val reach = g.cache.reachability

            /**
             * Find those events which are included in our log level
             */
            val eventSources = reads.mapNotNull {
                it as? EventLongRead
            }.filter {
                it.traceEventInfo.eventSort.includeIn == logLevel
            }.mapToSet { it.where }

            val countAbstraction = mutableMapOf<NBId, IntValue>()
            countAbstraction[code.rootBlock.id] = IntValue.Constant(BigInteger.ZERO)
            val traceEventNumbering = mutableMapOf<CmdPointer, IntValue>()
            /**
             * Compute an approximation of the number of events seen at each block and each event
             */
            for(b in code.topoSortFw) {
                var countIter = countAbstraction[b] ?: error("Topological sort is broken")
                for(lc in g.elab(b).commands) {
                    if(lc.ptr in eventSources) {
                        traceEventNumbering[lc.ptr] = countIter
                        countIter = countIter.add(IntValue.Constant(BigInteger.ONE)).first
                    }
                }
                g.succ(b).forEachElement {
                    countAbstraction[it] = countAbstraction[it]?.join(countIter) ?: countIter
                }
            }

            /**
             * Filter out those events which definitely aren't included based on the above analysis:
             * those whose count abstraction definitely doesn't include [idxAsBig]
             */
            val eventsToInclude = traceEventNumbering.keysMatching { _, eventNumberAbstraction ->
                eventNumberAbstraction.lb <= idxAsBig && eventNumberAbstraction.ub >= idxAsBig
            }

            val nonNullReads = sources.mapNotNullToSet { src ->
                if(src.isNullRead) {
                    null
                } else {
                    src.where
                }
            }

            /**
             * Finally, record that we only care about the above event long reads or non-event long reads that
             * might reach these events (so we include relevant sha3, loop copies, etc.)
             */
            relevantLongReads = reads.filter { ls ->
                ls.where in eventsToInclude || (ls.traceEventInfo == null && eventsToInclude.any { candEvent ->
                    reach.canReach(ls.where, candEvent) && candEvent in nonNullReads
                })
            }.mapToSet { it.where }

            traceNumbering = traceEventNumbering
        }


        /**
         * Get the static inclusion status of the event
         */
        private fun classifyTraceInclusion(ls: EventLongRead) : IncludedInTrace {
            val x = traceNumbering[ls.where] ?: error("Couldn't find numbering for ${code.analysisCache.graph.elab(ls.where)}")
            /*
               four cases to cover
               1. definitely before the case in question -> keep going
               2. definitely after the case in question -> assume false
               3. definitely the case in question -> split the graph, drop the subgraph reachable exclusively from ls, jump to synthetic return
               4. Maybe the case in question -> split the graph, conditionally jump to the successor or synthetic return
             */
            return if(x.ub < idxAsBig) {
                IncludedInTrace.DEFINITELY_BEFORE
            } else if(x.lb > idxAsBig) {
                IncludedInTrace.DEFINITELY_AFTER
            } else if(x.isConstant && x.c == idxAsBig) {
                IncludedInTrace.DEFINITELY_INCLUDED
            } else {
                IncludedInTrace.MAYBE_INCLUDED
            }
        }

        /**
         * Definitely update the buffer that we statically identified above,
         * ignore all the others
         */
        override fun updateShadowBufferPred(
            v: LongRead,
        ): InclusionAnswer {
            return if(v.where in relevantLongReads) {
                InclusionAnswer.Definite(true)
            } else {
                InclusionAnswer.Definite(false)
            }
        }

        /**
         * Based on the inclusion of the event
         */
        override fun includeInTracePred(v: EventLongRead): InclusionAnswer {
            return when(classifyTraceInclusion(v)) {
                // those definitely excluded, definitely answer no
                IncludedInTrace.DEFINITELY_BEFORE,
                IncludedInTrace.DEFINITELY_AFTER -> InclusionAnswer.Definite(false)
                IncludedInTrace.MAYBE_INCLUDED -> {
                    // for those where we don't know, generate a predicate on the current count number
                    check(v.traceEventInfo.eventSort.includeIn == targetEvents)
                    val countHolder = getEventCountHolder()
                    InclusionAnswer.Dynamic(
                        TACExpr.BinRel.Eq(countHolder.asSym(), idxAsBig.asTACExpr, Tag.Bool).lift()
                    )
                }
                // those definitely included, definitely answer yes
                IncludedInTrace.DEFINITELY_INCLUDED -> InclusionAnswer.Definite(true)
            }
        }

        private fun getEventCountHolder() = when (targetEvents) {
            TraceTargets.Calls -> callTraceLog.itemCountVar
            TraceTargets.Log -> logTraceLog.itemCountVar
            TraceTargets.Results -> throw UnsupportedOperationException("Selecting numbers of exit events makes no sense: there is only ever one")
        }

        /**
         * If we have definitely finished execution (we've reached the event with number [target],
         * then early exit from the program.
         */
        override fun postInstrument(
            patcher: SimplePatchingProgram,
            ls: LongRead
        ): CommandWithRequiredDecls<TACCmd.Simple> {
            if(ls.traceEventInfo?.eventSort?.includeIn == TraceTargets.Results) {
                return ExprUnfolder.unfoldPlusOneCmd("exitAssume", TXF {
                    (target.traceNumber + 1) eq getEventCountHolder()
                }) { cond ->
                    TACCmd.Simple.AssumeCmd(cond.s, "Assuming exit count")
                }
            }
            /**
             * We only exit after the relevant events, so if this isn't an event we care about,
             * do nothing
             */
            val eventInfo = ls.traceEventInfo?.takeIf { evt ->
                evt.eventSort.includeIn == targetEvents
            } ?: return CommandWithRequiredDecls()
            check(ls is EventLongRead)
            /*
               four cases to cover
               1. definitely before the case in question -> keep going
               2. definitely after the case in question -> assume false
               3. definitely the case in question -> split the graph, drop the subgraph reachable exclusively from ls, jump to synthetic return
               4. Maybe the case in question -> split the graph, conditionally jump to the successor or synthetic return
             */
            return when(classifyTraceInclusion(ls)) {
                IncludedInTrace.DEFINITELY_BEFORE -> CommandWithRequiredDecls()
                IncludedInTrace.DEFINITELY_AFTER -> return CommandWithRequiredDecls(listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "impossible")))
                IncludedInTrace.MAYBE_INCLUDED -> {
                    when(eventInfo.eventSort) {
                        TraceEventSort.REVERT,
                        TraceEventSort.RETURN -> {
                            `impossible!`
                        }
                        TraceEventSort.INTERNAL_SUMMARY_CALL,
                        TraceEventSort.LOG,
                        TraceEventSort.EXTERNAL_CALL -> {
                            val countVar = when(eventInfo.eventSort) {
                                // these are required by the compiler, but IDEA complains about them :(
                                TraceEventSort.REVERT,
                                TraceEventSort.RETURN -> `impossible!`
                                TraceEventSort.LOG -> logTraceLog.itemCountVar
                                TraceEventSort.EXTERNAL_CALL -> callTraceLog.itemCountVar
                                TraceEventSort.INTERNAL_SUMMARY_CALL -> callTraceLog.itemCountVar
                            }
                            val succBlock = patcher.splitBlockAfter(ls.where)
                            val newBlock = patcher.addBlock(ls.where.block, listOf(TACCmd.Simple.LabelCmd("Early return site for trace event $target")))
                            val jumpVar = TACKeyword.TMP(Tag.Bool, "!jumpDecider")
                            return CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = jumpVar,
                                    rhs = TACExpr.BinRel.Eq(countVar.asSym(), (target.traceNumber + 1).asTACExpr)
                                ),
                                TACCmd.Simple.JumpiCmd(cond = jumpVar, dst = newBlock, elseDst = succBlock)
                            ), setOf(countVar, jumpVar))
                        }
                    }
                }
                IncludedInTrace.DEFINITELY_INCLUDED -> {
                    val succBlock = patcher.splitBlockAfter(ls.where)
                    val newBlock = patcher.addBlock(ls.where.block, listOf(TACCmd.Simple.LabelCmd("Early return site for trace event $target")))
                    patcher.deferRemoveSubgraph(setOf(succBlock))
                    return CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.JumpCmd(newBlock)
                    ))
                }
            }
        }

        /**
         * Indicates whether [ls] was included in the above logic, used for deciding when the tiered checking mode is complete
         */
        override fun getTraceSiteReport(ls: EventLongRead): TraceSiteReport {
            if(ls.traceEventInfo.eventSort.includeIn != targetEvents) {
                return TraceSiteReport(
                    heuristicDifficulty = 0,
                    traceInclusion = InclusionSort.DEFINITELY_EXCLUDED
                )
            }
            return TraceSiteReport(
                heuristicDifficulty = ls.traceEventInfo.eventSort.ordinal,
                traceInclusion = when(classifyTraceInclusion(ls)) {
                    IncludedInTrace.DEFINITELY_BEFORE,
                    IncludedInTrace.DEFINITELY_AFTER -> InclusionSort.DEFINITELY_EXCLUDED
                    IncludedInTrace.MAYBE_INCLUDED -> InclusionSort.MAYBE_INCLUDED
                    IncludedInTrace.DEFINITELY_INCLUDED -> InclusionSort.DEFINITELY_INCLUDED
                }
            )
        }
    }

    /**
     * Convenience function which splits the program after [p] and forces it to exit the function early. The
     * success of [p] is queued for removal once all instrumentation is complete.
     */
    private fun SimplePatchingProgram.earlyReturnAt(p: CmdPointer): CommandWithRequiredDecls<TACCmd.Simple.JumpCmd> {
        this.splitBlockAfter(p)
        val succBlock = this.splitBlockAfter(p)
        val exitBlock = this.addBlock(
            p.block, listOf(
                TACCmd.Simple.LabelCmd("Auto-generated early return")
            )
        )
        this.deferRemoveSubgraph(setOf(succBlock))
        return CommandWithRequiredDecls(listOf(TACCmd.Simple.JumpCmd(exitBlock)))

    }

    /**
     * Generate a [TraceInclusionManager] implementing the policies set in [InstrumentationControl].
     *
     * [c] is the program being instrumented, [sources] are all the long reads found in [c], [logLevel]
     *  what type of events we are logging, and [eventSiteOverride] indicates which
     *  shadow buffers might be exlucided a priori.
     */
    private fun TraceInclusionMode.toTraceManager(
        c: CoreTACProgram,
        sources: Set<LongRead>,
        logLevel: TraceTargets,
        eventSiteOverride: Map<CmdPointer, TraceOverrideSpec>
    ) : TraceInclusionManager {
        return when(this) {
            is TraceInclusionMode.Unified -> {
                /**
                 * Easy case, include everything always, never post instrument
                 */
                object : TraceInclusionManager(eventSiteOverride, logLevel) {
                    override fun includeInTracePred(v: EventLongRead): InclusionAnswer {
                        return InclusionAnswer.Definite(true)
                    }

                    override fun updateShadowBufferPred(v: LongRead): InclusionAnswer {
                        return InclusionAnswer.Definite(true)
                    }


                    override fun postInstrument(
                        patcher: SimplePatchingProgram,
                        ls: LongRead
                    ): CommandWithRequiredDecls<TACCmd.Simple> {
                        return CommandWithRequiredDecls()
                    }

                    override fun getTraceSiteReport(ls: EventLongRead): TraceSiteReport {
                        return TraceSiteReport(
                            heuristicDifficulty = ls.traceEventInfo.eventSort.ordinal,
                            traceInclusion = InclusionSort.MAYBE_INCLUDED
                        )
                    }

                }
            }
            is TraceInclusionMode.Until -> {
                /**
                 * Use [UntilNumberedEntryManager]
                 */
                UntilNumberedEntryManager(
                    sources, c, this, logLevel, eventSiteOverride
                )
            }
            is TraceInclusionMode.UntilExactly -> {
                /**
                 * Include only the event we're targeting, and non-event long reads that
                 * can reach said event. Ignore everything else.
                 */
                val reach = c.analysisCache.reachability
                val isRelevant = sources.filter {
                    it.where !in this.which && this.which.any { e ->
                        reach.canReach(it.where, e)
                    } && it.traceEventInfo == null
                }.mapToSet { it.where }
                return object : TraceInclusionManager(eventSiteOverride, logLevel) {
                    // any event which isn't the target, ignore
                    override fun includeInTracePred(v: EventLongRead): InclusionAnswer {
                        return InclusionAnswer.Definite(v.where in which)
                    }

                    /**
                     * Only update the buffers for the target event and the relevant non-event updates identified above.
                     * This is a static determination.
                     */
                    override fun updateShadowBufferPred(
                        v: LongRead,
                    ): InclusionAnswer {
                        /**
                         * XXX: I don't know why we have this traceEventInfo check here, it seems that v.where is only
                         * included in isRelevant if traceEventInfo is null...
                         */
                        return if((v.where in isRelevant && v.traceEventInfo == null) || v.where in this@toTraceManager.which) {
                            InclusionAnswer.Definite(true)
                        } else {
                            InclusionAnswer.Definite(false)
                        }
                    }

                    /**
                     * Cut the program to force execution to reach the event in question.
                     */
                    override fun postInstrument(
                        patcher: SimplePatchingProgram,
                        ls: LongRead
                    ): CommandWithRequiredDecls<TACCmd.Simple> {
                        if(ls.where !in this@toTraceManager.which && ls.where !in isRelevant) {
                            /**
                             * If this is irrelevant but we could still reach the target, do nothing
                             */
                            if(which.any { exit -> reach.canReach(ls.where, exit ) }) {
                                return CommandWithRequiredDecls(TACCmd.Simple.NopCmd)
                            }
                            /**
                             * otherwise, prune the path, we're in some part of the program we don't
                             * care about
                             */
                            return CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssumeCmd(TACSymbol.False, "impossible")
                            ))
                        /**
                         * force exiting if we've reached our target.
                         */
                        } else if(ls.where in this@toTraceManager.which) {
                            if(ls.traceEventInfo == null) {
                                return patcher.earlyReturnAt(ls.where)
                            }
                            return when(ls.traceEventInfo!!.eventSort) {
                                TraceEventSort.REVERT,
                                TraceEventSort.RETURN -> {
                                    CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd))
                                }
                                TraceEventSort.INTERNAL_SUMMARY_CALL,
                                TraceEventSort.EXTERNAL_CALL,
                                TraceEventSort.LOG -> {
                                    return patcher.earlyReturnAt(ls.where)
                                }
                            }
                        } else {
                            return CommandWithRequiredDecls()
                        }
                    }

                    override fun getTraceSiteReport(ls: EventLongRead): TraceSiteReport {
                        return TraceSiteReport(
                            heuristicDifficulty = ls.traceEventInfo.eventSort.ordinal,
                            traceInclusion = if(ls.where in this@toTraceManager.which) {
                                InclusionSort.DEFINITELY_INCLUDED
                            } else {
                                InclusionSort.DEFINITELY_EXCLUDED
                            }
                        )
                    }

                }
            }
        }
    }

    private data class ContextSymbols(
        val symbols: List<TACSymbol>,
        val setup: CommandWithRequiredDecls<TACCmd.Simple>?
    ) {
        companion object {
            fun List<TACSymbol>.lift() = ContextSymbols(this, null)
        }
    }

    private fun Context.contextSymbols(): ContextSymbols = when(this) {
        is Context.ExternalCall -> {
            val storageIdx = TACKeyword.TMP(Tag.Bit256, "storageInclusionIdx")
            val res = TACKeyword.TMP(Tag.Bit256, "storageComponent")
            val setup = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = storageIdx,
                    rhs = TACExpr.Select(STORAGE_STATE_INCLUSION_MAP.asSym(), globalStateAccumulator.asSym())
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = res,
                    rhs = TACExpr.Select(storageVar.asSym(), storageIdx.asSym())
                )
            ), setOf(globalStateAccumulator, STORAGE_STATE_INCLUSION_MAP, res, storageIdx, storageVar))
            ContextSymbols(
                treapListOf(
                    callee, value, res
                ),
                setup
            )
        }
        is Context.InternalCall -> args.lift()
        is Context.Log -> this.topics.lift()
        Context.MethodExit -> treapListOf<TACSymbol>().lift()
    }

    @KSerializable
    sealed interface Context : AmbiSerializable, TransformableVarEntityWithSupport<Context> {
        @KSerializable
        object MethodExit : Context {

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Context {
                return this
            }

            override val support: Set<TACSymbol.Var>
                get() = setOf()



            fun readResolve(): Any = MethodExit
        }

        @KSerializable
        data class InternalCall(
            val signature: QualifiedMethodSignature,
            val args: List<TACSymbol>
        ) : Context {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Context {
                return InternalCall(
                    signature, args.map { it.map(f) }
                )
            }

            override val support: Set<TACSymbol.Var>
                get() = args.mapNotNullToTreapSet { it as? TACSymbol.Var }
        }

        fun TACSymbol.map(f: (TACSymbol.Var) -> TACSymbol.Var) = (this as? TACSymbol.Var)?.let(f) ?: this

        @KSerializable
        data class Log(
            val topics: List<TACSymbol>
        ) : Context {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Context {
                return Log(
                    topics.map { s ->
                        s.map(f)
                    }
                )
            }

            override val support: Set<TACSymbol.Var>
                get() = topics.mapNotNullToTreapSet { it as? TACSymbol.Var }
        }

        @KSerializable
        data class ExternalCall(
            val callee: TACSymbol,
            val value: TACSymbol
        ) : Context {

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Context {
                return ExternalCall(
                    callee = callee.map(f),
                    value = value.map(f)
                )
            }

            override val support: Set<TACSymbol.Var>
                get() = setOfNotNull(callee as? TACSymbol.Var, value as? TACSymbol.Var)
        }
    }

    /**
     * Indicates the [eventSort] (log, call, etc.) and scalar values which should be included in the event hash (e.g.,
     * log topics, callee, etc.)
     */
    private data class TraceEventWithContext(
        val eventSort: TraceEventSort,
        val context: Context,
    )

    /**
     * Compute the instrumentation for the [update] at [where]. If [where] is a GC point for some long reads,
     * those long reads are given in [isGCFor]
     */
    private fun doBufferUpdate(where: CmdPointer, update: BufferUpdate<*>, isGCFor: List<LongRead>?) : BufferWriteInstrumentation {
        return sources.filter {
            reach.canReach(where, it.where) && !it.isNullRead
        }.map {
            generateInstrumentedWrite(
                targetBuffer = it,
                write = update
            )
        }.let(CommandWithRequiredDecls.Companion::mergeMany).let {
            it andThen isGCFor?.let { futureReads ->
                generateGCSetup(futureReads)
            }.orEmpty()
        }.let {
            BufferWriteInstrumentation(it, update.isBefore)
        }
    }

    /**
     * Instruments a long read. If this long read corresponds to a memory write (which will only be the case for call cores)
     * this is included in [bufferUpdateWork].
     */
    private fun instrumentLongRead(s: LongRead, patcher: SimplePatchingProgram, bufferUpdateWork: BufferUpdate<*>?) {
        if(!patcher.isBlockStillInGraph(s.where.block)) {
            return
        }
        val lc = g.elab(s.where)
        val origCommand = lc.cmd

        val sourceInfo = s.instrumentationInfo()

        /**
         * Constrain the prophecy variables, and then call the [InstrumentationMixin.atLongRead] hooks
         */
        val pointerProphecy = sourceInfo.baseProphecy
        val lengthProphecy = sourceInfo.lengthProphecy
        val prophecyAndMixinUpdate = listOf(pointerProphecy, lengthProphecy).zip(
            listOf(
                s.loc, s.length
            )
        ).map { (l, r) ->
            ExprUnfolder.unfoldPlusOneCmd("prophecyReq", with(TACExprFactTypeCheckedOnlyPrimitives) {
                l eq r
            }) {
                TACCmd.Simple.AssumeCmd(it.s, "set prophecy")
            }
        }.let(CommandWithRequiredDecls.Companion::mergeMany).merge(pointerProphecy, lengthProphecy) andThen sourceInfo.instrumentationMixins.map {
            it.atLongRead(s)
        }.flatten()

        /**
         * If this is an event read, update the trace
         */
        val traceUpdate = (s as? EventLongRead)?.let { updateTrace(it) }

        /**
         * If this is a call core, update the return state (this happens irrespective of whether to
         * include the call in the trace).
         */
        val originalCommandReplacement = if (origCommand is TACCmd.Simple.CallCore) {
            updateReturnState(
                lc.narrow()
            )
        } else if (origCommand is TACCmd.Simple.AssigningCmd.AssignSha3Cmd) {
            /**
             * Replace the sha3 command with the sounder model.
             */
            val read = getBufferIdentity(s)
            read.toCRD() andThen CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = origCommand.lhs,
                        rhs = read.exp
                    )
                )
            )
        } else {
            CommandWithRequiredDecls(origCommand)
        }

        /**
         * If this is a consumption of a scratch buffer that is a GC point, do the GC setup.
         */
        val withGCMaybe = isGarbageCollectionFor[s.where]?.let {
            generateGCSetup(
                it
            )
        } ?: CommandWithRequiredDecls()

        /**
         * If this read was also a write, do that and then merge the original command
         * replacement with that instrumentation.
         */
        val hashUpdateAndOriginalCommand = (bufferUpdateWork?.let {
            doBufferUpdate(s.where, it, null)
        }?.mergeOriginal(originalCommandReplacement) ?: originalCommandReplacement) andThen withGCMaybe

        /**
         * Finally, call the [TraceInclusionManager.postInstrument] hook.
         */
        val replacement = listOfNotNull(
            prophecyAndMixinUpdate,
            traceUpdate,
            hashUpdateAndOriginalCommand
        ).flatten() andThen traceInclusionManager.postInstrument(
            patcher, s
        )
        patcher.replaceCommand(s.where, replacement)
    }


    /**
     * The core instrumentation work
     */
    private fun instrumentInContext() : CoreTACProgram {
        /**
         * Get all of the [BufferUpdate] objects for memory mutations (skipping the initialization of the free pointer
         * because it screws everythin up)
         */
        val hashUpdateWork = code.parallelLtacStream().mapNotNull { lc ->
            if(lc.cmd is TACCmd.Simple.AssigningCmd.ByteStore && lc.cmd.loc == 0x40.asTACSymbol() && lc.cmd.value == 0x80.asTACSymbol()) {
                return@mapNotNull null
            }
            lc.ptr `to?` getBufferUpdateFor(lc)
        }.toList().toMap()

        val handled = mutableSetOf<CmdPointer>()

        val patcher = code.toPatchingProgram("instrumentation")
        /**
         * Instrument the long reads, treating them as buffer updates (as is the case for call commands)
         */
        for(s in sources) {
            instrumentLongRead(
                s, patcher = patcher, bufferUpdateWork = hashUpdateWork[s.where]
            )
            handled.add(s.where)
        }

        /**
         * For those buffer updates not already handled above, perform the update instrumentation.
         */
        for((where, bufferUpdate) in hashUpdateWork) {
            if(where in handled || !patcher.isBlockStillInGraph(where.block)) {
                continue
            }
            val orig = g.elab(where).cmd
            handled.add(where)
            val up = doBufferUpdate(where, bufferUpdate, isGarbageCollectionFor[where]).mergeOriginal(orig)
            patcher.replaceCommand(where, up)
        }

        /**
         * For those garbage collection points not yet handled (i.e., free pointer writes)
         * add the garbage collection instrumentation for those sites.
         */
        for((s, next) in isGarbageCollectionFor) {
            if(s in handled || !patcher.isBlockStillInGraph(s.block)) {
                continue
            }
            patcher.addBefore(s, generateGCSetup(next))
        }

        /**
         * Add allllll the new variable declarations
         */
        options.useSiteControl.mapNotNull {
            it.value.traceReached
        }.forEach {
            patcher.addVarDecl(it)
        }
        options.eventSiteOverride.flatMap { (_, v) ->
            when(v) {
                is TraceOverrideSpec.CompleteOverride -> v.overridingValue.getFreeVars()
                is TraceOverrideSpec.ConditionalOverrides -> v.gates.flatMap {
                    it.first.getFreeVars() + it.second.getFreeVars()
                }
            }
        }.forEach {
            patcher.addVarDecl(it)
        }
        val instrumentationVars = readToInstrumentation.flatMap { (_, read) ->
            read.instrumentationMixins + read
        } + traceInstrumentation

        for(iv in instrumentationVars) {
            patcher.addVarDecls(iv.allVars.toSet())
        }
        patcher.addVarDecls(globalStateVars.toSet())


        val withInst = patcher.toCode(code)

        /**
         * Initialize all of the instrumentation variables for the long reads and their mixins.
         */
        val instrumentationInit = readToInstrumentation.flatMap { (_, inst) ->
            (inst.havocInitVars + inst.instrumentationMixins.flatMap { it.havocInitVars }).map {
                CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                    lhs = it
                ), it)
            } + (inst.constantInitVars + inst.instrumentationMixins.flatMap { it.constantInitVars }).map { (v, init) ->
                v `=` init
            }
        }.flatten()

        val globalStateInit = traceInstrumentation.havocInitVars.map {
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                    lhs = it
                )
            ), setOf(it))
        }.flatten() andThen traceInstrumentation.constantInitVars.map { (lhs, init) ->
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = lhs,
                    rhs = init.toTACExpr()
                )
            ), setOf(lhs) + init.toTACExpr().getFreeVars())
        }.flatten() andThen CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = globalStateAccumulator,
                rhs = TACExpr.zeroExpr
            )
        ), globalStateAccumulator)

        return withInst.prependToBlock0(instrumentationInit andThen globalStateInit)
    }

    /**
     * Indicates which events should be included.
     */
    sealed interface TraceInclusionMode {
        /**
         * Include all events
         */
        data object Unified : TraceInclusionMode

        /**
         * Include only the [traceNumber] event in execution order, starting from 0. Thus, a [traceNumber] of 1 would include
         * only the 2nd event hit in any execution. Cuts the source program early once the [traceNumber] event is hit.
         */
        data class Until(val traceNumber: Int) : TraceInclusionMode
        /**
         * Only include the event at [which]. Forces control flow to reach any of [which] and then early exits from the program.
         */
        data class UntilExactly(val which: Set<CmdPointer>) : TraceInclusionMode {
            constructor(which: CmdPointer) : this(setOf(which))
        }
    }

    /**
     * Flags controlling how the instrumentation is performed for long reads. If these options are not provided,
     * the fields are assumed to be false/null.
     */
    data class UseSiteControl(
        /**
         * Record all reads into the buffer, including whether they are precise, the offsets, source (in the case of a
         * long copy) etc. In other words, enables the write tracking described in the Localized Buffer Precision section.
         */
        val trackBufferContents: Boolean,
        /**
         * If non-null, then the long read site is instrumented to set this variable to true if control-flow reaches that
         * site. This variable is initialized to false by this instrumentation.
         */
        val traceReached: TACSymbol.Var? = null
    )

    /**
     * Controls for how the buffer hashes for event traces are computed
     */
    sealed interface TraceOverrideSpec {
        /**
         * Conditionally uses different buffer trace values depending on the evaluation of different conditions.
         * [gates] is a list of tuples the first component of which is a condition which, if true, means that the
         * second component should be as the value of the buffer hash. These conditions are evaluated
         * in their order in [gates]. If none of the overrides apply, the default buffer hash will be used.
         */
        data class ConditionalOverrides(val gates: List<Pair<TACExpr, TACExpr>>) : TraceOverrideSpec

        /**
         * The buffer hash for some event is always taken to be [overridingValue]
         */
        data class CompleteOverride(val overridingValue: TACExpr) : TraceOverrideSpec
    }

    /**
     * Allows for fine-grained control over the instrumentation process.
     */
    data class InstrumentationControl(
        /**
         * Of the types of events selected by [eventLoggingLevel], indicates which events among those should be
         * included. See [TraceInclusionMode] for a description of the options here.
         */
        val traceMode: TraceInclusionMode,
        /**
         * Indicates which external event types to record. Note that you cannot select reverts independently from returns
         * and vice-versa: they are both grouped together via [TraceTargets]
         */
        val eventLoggingLevel: TraceTargets,
        /**
         * For the individual use sites also called "long reads". The codomain is the command pointer of the long read.
         * Some of the options in [UseSiteControl] only make sense in the context of external event long reads, but no
         * validation of this is done. Indeed, it is not checked that the keys in [useSiteControl] actually denote
         * long reads; there are no guarantees of behavior if they are not. If there is no [UseSiteControl] for
         * a long read, the "default values" mentioned in the [UseSiteControl] documentation are used.
         */
        val useSiteControl: Map<CmdPointer, UseSiteControl>,
        /**
         * Each key in this map is assumed to be an mload command. The value associated
         * with this key if non-null (XXX: why is this domain nullable???), indicates the size of the bounded precision
         * window to use for that load.
         */
        val forceMloadInclusion: Map<CmdPointer, Int?>,
        /**
         * The keys of this mapping are assumed to be an externally observable event. The codomain [TraceOverrideSpec],
         * allows fine-grained control over how the buffer identity is computed at that event-site.
         */
        val eventSiteOverride: Map<CmdPointer, TraceOverrideSpec>
    )

    /**
     * Basically an extension of [TACExprWithRequiredCmdsAndDecls]; includes the
     * event signature [eventSig], the [computation] required to generate [eventSig],
     * and additionally includes the [bufferHash] (which is included in [eventSig].
     */
    private data class EventSignature(
        val eventSig: TACExpr,
        val computation: CommandWithRequiredDecls<TACCmd.Simple>,
        val bufferHash: TACSymbol.Var
    )

    /**
     * Generates the [EventSignature] for an [EventLongRead]. The event
     * hash is:
     * `Hash(eventSort, bufferHash, bufferLength, *ls.traceEventInfo.context, ls.traceEventInfo.dynamicContext?)`
     *
     * where `?` indicates inclusion if not-null, and `*` denotes variadic expansion. To be cheeky, the `eventSort` is
     * actually used as the "length" of the hash (which is just another argumnent to the hash bif at the SMT level).
     *
     * The buffer hash above is computed via [getBufferIdentity], and overridden according to the [TraceOverrideSpec]
     * associated with [ls], if it exists.
     */
    private fun generateEventSignature(ls: EventLongRead) : EventSignature {
        check(ls.traceEventInfo.eventSort.includeIn == options.eventLoggingLevel)

        val bufferSig = when(val override = options.eventSiteOverride[ls.where]) {
            null -> {
                getBufferIdentity(ls)
            }
            is TraceOverrideSpec.CompleteOverride -> override.overridingValue.lift()
            is TraceOverrideSpec.ConditionalOverrides -> {
                val buff = getBufferIdentity(ls)
                buff.toCRD() andThen override.gates.foldRight(buff.exp) { gate, acc ->
                    TACExprFactoryExtensions.run {
                        ite(gate.first, gate.second, acc)
                    }
                }
            }
        }
        val bufferHashVar = TACKeyword.TMP(Tag.Bit256, "hashVar")
        val context = ls.traceEventInfo.context.contextSymbols()
        val fullPrefix = listOfNotNull(
            bufferSig.toCRD(),
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = bufferHashVar,
                        rhs = bufferSig.exp
                    )
                ), setOf(bufferHashVar)
            ),
            context.setup
        ).flatten()
        val basicHash = TACExpr.SimpleHash(
            length = ls.traceEventInfo.eventSort.ordinal.asTACExpr,
            args = listOf(bufferHashVar.asSym()) + ls.length.asSym() + context.symbols.map { it.asSym() },
            hashFamily = HashFamily.Sha3
        )
        return EventSignature(
            computation = fullPrefix.merge(context.symbols).merge(ls.length),
            eventSig = basicHash,
            bufferHash = bufferHashVar
        )
    }

    enum class InclusionSort(val mayAppear: Boolean) {
        DEFINITELY_INCLUDED(true),
        DEFINITELY_EXCLUDED(false),
        MAYBE_INCLUDED(true)
    }

    data class TraceSiteReport(
        val heuristicDifficulty: Int,
        val traceInclusion: InclusionSort
    )

    /**
     * Interface describing whether or not to include instrumentation.
     */
    private sealed interface InclusionAnswer {
        /**
         * The decision to include/exclude a buffer/event update is statically known, the answer is [includeFlag].
         */
        data class Definite(val includeFlag: Boolean) : InclusionAnswer
        /**
         * The decision to include/exclude a buffer/event needs to be made at "runtime", as determined by the evaluation
         * of the predicate [pred].
         *
         * FIXME(CERT-8862): this can just be a TACExpr
         */
        data class Dynamic(val pred: TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>) : InclusionAnswer
    }

    /**
     * Inner class used to abstract decision making about what buffers get updated/includedin the trace.
     *
     * [overrides] and [targetEvents] are taken from [InstrumentationControl].
     */
    private abstract class TraceInclusionManager(
        val overrides: Map<CmdPointer, TraceOverrideSpec>,
        val targetEvents: TraceTargets
    ) {
        /**
         * Indicates whether the long read associated with the externally observable event [v]
         * should be logged. Always answers no if the value of [targetEvents] exludes the sort of [v],
         * otherwise delegates to [includeInTracePred].
         */
        fun includeInTrace(v: EventLongRead): InclusionAnswer {
            if(v.traceEventInfo.eventSort.includeIn != targetEvents) {
                return InclusionAnswer.Definite(false)
            }
            return includeInTracePred(v)
        }

        /**
         * Extension point for the logic which decides whether an event should be included in the
         * trace.
         */
        abstract protected fun includeInTracePred(v: EventLongRead) : InclusionAnswer

        /**
         * Indicates whether the shadow buffer for the long read [v] needs to be updated.
         * If [v] is an event long read whose value is either overridden or which is excluded from event inclusion,
         * this always answers no. Otherwise, this delegates to [updateShadowBufferPred].
         */
        fun updateShadowBuffer(v: LongRead) : InclusionAnswer {
            if(v is EventLongRead && (overrides[v.where] is TraceOverrideSpec.CompleteOverride || v.traceEventInfo.eventSort.includeIn != targetEvents)) {
                return InclusionAnswer.Definite(false)
            }
            return updateShadowBufferPred(v)
        }

        /**
         * Indicates whether the shadow buffer (the hash variables etc.) needs to be updated for the long read at [v].
         */
        abstract protected fun updateShadowBufferPred(v: LongRead) : InclusionAnswer

        /**
         * Guaranteed to be called after all instrumentation at long read [ls] is completed. Allowed to mutate the program
         * via [patcher], but care should be taken to not invalidate remaining instrumentation. The commands
         * returned by the function will always be the last bit of instrumentation for [ls].
         */
        abstract fun postInstrument(patcher: SimplePatchingProgram, ls: LongRead) : CommandWithRequiredDecls<TACCmd.Simple>

        /**
         * Provides some information about whether an event was included in a trace
         */
        abstract fun getTraceSiteReport(ls: EventLongRead) : TraceSiteReport
    }

    /**
     * External interface for information inserted by the buffer contents tracking instrumentation.
     *
     * The instrumentation (described in [BufferContentsInstrumentation])
     * tracks all writes into memory which overlap with some later long reads. In any concrete execution, these
     * writes are numbered from 0. Most of the variables in this class are bytemaps; the values in these
     * bytemaps at index `i` provides information about the ith write. In the documentation for these
     * variables, we'll describe the value the bytemap takes at "the write" with index i, which thus describes the
     * value of the bytemap at index i.
     */
    interface IBufferContentsInstrumentation {
        /**
         * [Tag.ByteMap] recording concrete values written. For writes that were a bytestore,
         * records the value written. 0 otherwise.
         */
        val bufferValueHolder: TACSymbol.Var

        /**
         * Scalar variable which tracks the number of writes so far into the buffer. Can be evaluated/queried to determine the
         * total number of writes into the buffer.
         */
        val bufferWriteCountVar: TACSymbol.Var

        /**
         * [Tag.ByteMap] recording the relative offset written into the buffer. Stored as a signed integer
         * in twos complement (as writes before the start of the target buffer can overlap with the buffer contents).
         */
        val bufferOffsetHolder: TACSymbol.Var

        /**
         * [Tag.ByteMap] recording whether the write was precise. 1 if precise, 0 if not. Only mcopy and bytestore writes
         * are exact.
         */
        val preciseBuffer: TACSymbol.Var

        /**
         * [Tag.ByteMap] recording the source of any long copy into this buffer. For any write with index `i`, if this
         * value is some non-zero id I, then the values copied into this buffer came from the mcopy with [UseSiteInfo.id] == I.
         *
         * This can be used to construct the trace of copies which define a buffer. If the value here is 0, then the
         * ith write was definitely not an mcopy.
         */
        val bufferCopySource: TACSymbol.Var
    }

    /**
     * Internal version of [Logger], which [generateWrite] to generating writes to the event logs via instrumentation.
     */
    private sealed interface EventLogger : WithVarInit, Logger {
        /**
         * Write an event hash [item] into the event log. [metaGen] is a callback used to generate the [TraceIndexMarker]
         * annotation for this write. It's argument is a symbol holding the current index of the event (that is, what should
         * be the value of [TraceIndexMarker.indexVar]
         */
        fun generateWrite(item: TACSymbol.Var, metaGen: (TACSymbol) -> TACCmd.Simple.AnnotationCmd?) : CommandWithRequiredDecls<TACCmd.Simple>
    }

    /**
     * Specialization of [EventLogger] which will *always* log exactly one element (the event for the exit from the function).
     */
    private data class ExitLogger(
        val functionExitStatusVar: TACSymbol.Var
    ) : EventLogger {
        override fun generateWrite(
            item: TACSymbol.Var,
            metaGen: (TACSymbol) -> TACCmd.Simple.AnnotationCmd?
        ): CommandWithRequiredDecls<TACCmd.Simple> {
            return CommandWithRequiredDecls(listOfNotNull(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = functionExitStatusVar,
                    rhs = item
                ),
                metaGen(TACSymbol.Zero),
            ), setOf(functionExitStatusVar, item))
        }

        override val havocInitVars: List<TACSymbol.Var>
            get() = listOf()
        override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
            get() = listOf(functionExitStatusVar to TACExpr.zeroExpr)

        /**
         * Note that we ignore `ind` here; because this is only ever used for VC generation, this is fine, the caller
         * doesn't actually *really* care [ind], it is just some havoced variable.
         */
        override fun getRepresentative(ind: TACSymbol.Var): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return TACExprWithRequiredCmdsAndDecls(functionExitStatusVar.asSym(), setOf(functionExitStatusVar), listOf())
        }

    }

    /**
     * Class for representing an array of event hashes. Used for log and external call event types (of which
     * there can be multiple).
     */
    private data class TraceArray(
        /**
         * The variable which tracks the current number of events
         */
        val itemCountVar: TACSymbol.Var,
        /**
         * The bytemap which holds the event hashes. If [itemCountVar] is some value `k`, then for all indices 0 <= i < `k`,
         * index `i` in [traceArray] will hold an event hash.
         */
        val traceArray: TACSymbol.Var
    ) : EventLogger {
        /**
         * Write [item] into [traceArray] at [itemCountVar] and then increment [itemCountVar].
         */
        override fun generateWrite(item: TACSymbol.Var, metaGen: (TACSymbol) -> TACCmd.Simple.AnnotationCmd?) : CommandWithRequiredDecls<TACCmd.Simple> {
            val indexCapture = TACSymbol.Var("traceIndexCapture", Tag.Bit256).toUnique("!")
            return CommandWithRequiredDecls(listOfNotNull(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = indexCapture,
                    rhs = itemCountVar
                ),
                TACCmd.Simple.AssigningCmd.ByteStore(
                    base = traceArray,
                    loc = indexCapture,
                    value = item
                ),
                metaGen(indexCapture),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = itemCountVar,
                    rhs = TACExpr.Vec.Add(itemCountVar.asSym(), TACSymbol.One.asSym())
                )
            ), setOf(itemCountVar, traceArray, indexCapture, item))
        }

        override val havocInitVars: List<TACSymbol.Var>
            get() = listOf()
        override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
            get() = listOf(
                itemCountVar to TACExpr.zeroExpr,
                traceArray to TACExpr.MapDefinition(listOf(idxVar.asSym()), TACExpr.zeroExpr, Tag.ByteMap)
            )

        override fun getRepresentative(ind: TACSymbol.Var): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return TACExprWithRequiredCmdsAndDecls(TACExpr.Select(base = traceArray.asSym(), loc = ind.asSym()), setOf(ind, traceArray), listOf())
        }
    }

    /**
     * For some long read `r`, [lengthVar] is the prophecy variable created for a long read (aka `lenProphecy`),
     * and [baseVar] is the prophecy variable for the start of the buffer (aka `bpProphecy`). [bufferWrites]
     * is non-null if the [UseSiteControl.trackBufferContents] flag for site was true, and includes information
     * about that instrumentation.
     */
    data class UseSiteInstrumentation(
        val lengthVar: TACSymbol.Var,
        val baseVar: TACSymbol.Var,
        val bufferWrites: IBufferContentsInstrumentation?,
    )

    /**
     * Information about each long read, event or regular. [id] is an internal id and is included for debugging
     * and correlating long-reads.
     * [instrumentation] includes information about the instrumentation inserted for the use site.
     *
     * [traceReport], if non-null, includes additional information about this site's inclusion. It is not current used,
     * but is expected to in the future.
     */
    data class UseSiteInfo(
        val id: Int,
        val instrumentation: UseSiteInstrumentation?,
        val traceReport: TraceSiteReport?
    )

    /**
     * Output of instrumentation. The [code] with the instrumentation applied, and information about the per long read
     * information in [useSiteInfo]. Global instrumentation variable information is included in [traceVariables].
     */
    data class InstrumentationResults(
        val code: CoreTACProgram,
        val useSiteInfo: Map<CmdPointer, UseSiteInfo>,
        val traceVariables: TraceInstrumentationVars,
    )

    /**
     * The raw event parameters extracted from the event hash command
     */
    sealed interface RawEventParams {
        /**
         * What sort of event was this
         */
        val sort: TraceEventSort
        val context: Context
        data class ExitParams(override val sort: TraceEventSort) : RawEventParams {
            override val context: Context.MethodExit
                get() = Context.MethodExit
        }
        data class LogTopics(
            val params: Context.Log
        ) : RawEventParams {
            override val sort: TraceEventSort
                get() = TraceEventSort.LOG
            override val context: Context.Log
                get() = params
        }

        data class ExternalCallParams(
            override val context: Context.ExternalCall
        ) : RawEventParams {
            override val sort: TraceEventSort
                get() = TraceEventSort.EXTERNAL_CALL


        }

        data class InternalSummaryParams(override val context: Context.InternalCall) : RawEventParams {
            override val sort: TraceEventSort
                get() = TraceEventSort.INTERNAL_SUMMARY_CALL
        }
    }

    /**
     * Enum class for event sorts. [includeIn] indicates which [TraceTargets] selection tracks
     * the corresponding event sort.
     */
    enum class TraceEventSort(val includeIn: TraceTargets, val showBuffer: Boolean = true) {
        REVERT(TraceTargets.Results),
        RETURN(TraceTargets.Results),
        LOG(TraceTargets.Log),
        EXTERNAL_CALL(TraceTargets.Calls),
        INTERNAL_SUMMARY_CALL(TraceTargets.Calls, showBuffer = false)
    }

    /**
     * Update the trace information based on the event [source]
     */
    private fun updateTrace(
        source: EventLongRead
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        val inclusion = traceInclusionManager.includeInTrace(source)

        /**
         * Even if we aren't including revert events, we still want to add some instrumentation to set the [isRevertingPath]
         * flag to true, to help with revert tracking.
         */
        val alwaysInstrumentation = if(source.traceEventInfo.eventSort == TraceEventSort.REVERT) {
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = isRevertingPath,
                    rhs = TACSymbol.True
                )
            ), setOf(isRevertingPath))
        } else {
            CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd))
        }
        if(inclusion is InclusionAnswer.Definite && !inclusion.includeFlag) {
            return alwaysInstrumentation
        }
        return TACExprFactTypeCheckedOnlyPrimitives.run {
            val eventSignature = generateEventSignature(source)
            val eventSort = source.traceEventInfo.eventSort

            val traceInclusion = if(inclusion is InclusionAnswer.Dynamic) {
                inclusion.pred
            } else {
                TACSymbol.True.lift()
            }
            val traceHashVar = source.id.instrumentationVar("traceHash", Tag.Bit256)

            val traceHash = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = traceHashVar,
                rhs = eventSignature.eventSig
            )

            val traceEventValue = source.id.instrumentationVar("traceEvent!${eventSort.name}", Tag.Bit256)

            val eventUpdatePayload = TACExprFactTypeCheckedOnlyPrimitives.run {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = traceEventValue,
                    rhs = ite(
                        traceInclusion.exp,
                        traceHashVar,
                        TACExpr.zeroExpr
                    )
                )
            }

            val traceComputation = CommandWithRequiredDecls(listOf(
                traceHash,
                eventUpdatePayload
            ), setOf(traceEventValue, traceHashVar))

            /**
             * Update the appropriate [Logger] based on the traced event type.
             */
            val traceUpdate = eventSort.includeIn.log.generateWrite(traceEventValue) {
                TACCmd.Simple.AnnotationCmd(TraceIndexMarker.META_KEY, TraceIndexMarker(
                    id = source.id,
                    indexVar = it,
                    eventSort = eventSort,
                    lengthVar = source.instrumentationInfo().lengthProphecy,
                    bufferBase = source.baseMap,
                    bufferStart = source.instrumentationInfo().baseProphecy,
                    numCalls = globalStateAccumulator,
                    bufferHash = eventSignature.bufferHash,
                    eventHash = traceHashVar,
                    context = source.traceEventInfo.context
                ))
            }

            traceInclusion.toCRD() andThen eventSignature.computation andThen traceComputation andThen traceUpdate andThen alwaysInstrumentation
        }
    }

    private fun CommandWithRequiredDecls<TACCmd.Simple>?.orEmpty() = this ?: CommandWithRequiredDecls()

    private fun <T> BufferUpdate<T>.openSource(): WriteSource where T : IWriteSource, T: WriteSource {
        return this.source
    }

    /**
     * Create the instrumenation for updating the instrumentation for [targetBuffer] due to the write at
     * [write].
     */
    private fun generateInstrumentedWrite(
        targetBuffer: LongRead,
        write: BufferUpdate<*>
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        if(targetBuffer.baseMap != write.buffer) {
            return CommandWithRequiredDecls()
        }
        val offs: TACSymbol = write.loc
        val length: TACSymbol = write.length
        val updateGenerator: WriteSource = write.openSource()
        val sourceInfo = readToInstrumentation[targetBuffer]!!
        val sourceProphecy = sourceInfo.baseProphecy
        val lenProphecy = sourceInfo.lengthProphecy
        val hash = sourceInfo.hashVar
        val toRet = MutableCommandWithRequiredDecls<TACCmd.Simple>()

        val explicitReadId = (targetBuffer as? EventLongRead)?.explicitReadId

        val includePredicateRes = traceInclusionManager.updateShadowBuffer(targetBuffer)
        if(includePredicateRes == InclusionAnswer.Definite(false)) {
            return CommandWithRequiredDecls()
        }
        val includePredicateCmds = if(includePredicateRes is InclusionAnswer.Dynamic) {
            includePredicateRes.pred
        } else {
            TACSymbol.True.lift()
        }
        toRet.extend(includePredicateCmds.toCRD())

        // definitely not related so skip
        if(explicitReadId != null &&
            g.elab(write.where).cmd.meta.find(DefiniteBufferConstructionAnalysis.DefiniteDefiningWrite.META_KEY)?.writesFor?.contains(explicitReadId) != true) {
            return CommandWithRequiredDecls()
        }
        val writeEndPoint = TACSymbol.Var("writeEndPoint", Tag.Bit256).toUnique("!").also {
            toRet.extend(it `=` {
                offs add length
            })
        }

        val overlapSym = TACSymbol.Var("bufferOverlap", Tag.Bool).toUnique("!")
        val overlapDefinition = if(explicitReadId == null) {

            /**
             * Compute the intersection checks
             */
            val bufferEnd = TACSymbol.Var("bufferEndPoint", Tag.Bit256).toUnique("!").also {
                toRet.extend(it `=` {
                    sourceProphecy add lenProphecy
                })
            }
            /*
              we have (a, b) = (source, bufferEnd) and (c, d) = (offs, endPoint)
              endPoint and bufferEnd are both exclusive (if we write 32 bytes starting at offs, offs + 32 isn't touched)
              so overlap formula is
              b > c and d > a AKA
              offs < bufferEnd and endPoint > source
             */
            TXF {
                LAnd(
                    offs lt bufferEnd,
                    sourceProphecy lt writeEndPoint,
                    includePredicateCmds.exp,
                    length gt 0
                )
            }
        } else {
            includePredicateCmds.exp
        }
        toRet.extend(overlapSym `=` overlapDefinition)

        /**
         * Get the relative offset of the write
         */
        val relativeOffs = TACSymbol.Var("relativeOffs", Tag.Bit256).toUnique("!").also {
            toRet.extend(it `=` {
                    offs sub sourceProphecy
                }
            )
        }
        val hashUpdate = updateGenerator.updateShadowHash(hash, relativeOffs, length)
        toRet.extend(hash `=` {
                ite(overlapSym, hashUpdate.exp, hash)
            }
        )
        // update alignment
        toRet.extend(sourceInfo.allAlignedVar `=` {
                ite(overlapSym,
                    sourceInfo.allAlignedVar and (
                        sourceInfo.baseProphecy le write.loc
                        ) and (
                            (length mod EVM_WORD_SIZE) eq TACExpr.zeroExpr
                        ) and (
                            (relativeOffs mod EVM_WORD_SIZE) eq TACExpr.zeroExpr
                        ) and (updateGenerator.getSourceIsAlignedPredicate()),
                    sourceInfo.allAlignedVar
                )
            }
        )

        val hashUpdateCommands = hashUpdate.toCRD() andThen toRet.toCommandWithRequiredDecls()
        // call the mixins. Take care not to have call instrument itself
        val instrumentationUpdates = sourceInfo.instrumentationMixins.map {
            if(targetBuffer.where == write.where && !it.intrumentSelfUpdates) {
                return@map CommandWithRequiredDecls()
            }
            it.atPrecedingUpdate(s = write, overlapSym = overlapSym, writeEndPoint = writeEndPoint, baseInstrumentation = sourceInfo)
        }.flatten()

        return hashUpdateCommands andThen instrumentationUpdates
    }
}
