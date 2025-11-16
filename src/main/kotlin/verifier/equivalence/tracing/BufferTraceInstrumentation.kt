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
import allocator.Allocator
import analysis.*
import analysis.controlflow.MustPathInclusionAnalysis
import analysis.dataflow.StrictDefAnalysis
import analysis.icfg.Inliner
import analysis.numeric.IntValue
import analysis.pta.LoopCopyAnalysis
import analysis.pta.POP_ALLOCATION
import bridge.SourceLanguage
import com.certora.collect.*
import compiler.applyKeccak
import config.Config
import evm.EVM_WORD_SIZE
import evm.MASK_SIZE
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE_INT
import scene.ContractClass
import scene.IContractWithSource
import spec.cvlast.QualifiedMethodSignature
import tac.*
import vc.data.tacexprutil.getFreeVars
import java.util.concurrent.atomic.AtomicInteger
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.TACProgramCombiners.wrap
import verifier.equivalence.instrumentation.DefiniteBufferConstructionAnalysis
import verifier.equivalence.instrumentation.ReturnCopyCollapser
import verifier.equivalence.instrumentation.ReturnCopyCorrelation
import verifier.equivalence.summarization.CommonPureInternalFunction
import verifier.equivalence.summarization.ComputationResults
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
            return EMPTY_HASH_VAL.asTACSymbol().lift()
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
                    sourceInstrumentation.allAlignedVar and ((sourceInstrumentation.lengthProphecy mod EVM_WORD_SIZE) eq 0) and TACSymbol.False.asSym()
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
            if(Config.EquivalenceSkipNativeHash.get()) {
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(bufferHash)
            } else {
                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                    lhs = bufferHash,
                    memBaseMap = longRead.baseMap,
                    op1 = sourceOffset,
                    op2 = nativeLength
                )
            }
        ), setOf(nativeLength, bufferHash, useAligned)).wrap("Native hashing for ${longRead.id}")

        val rest = TXF {
            ite(
                useAligned,
                bufferHash,
                sourceInstrumentation.hashVar
            )
        }
        val tryHashCopy = sourceInstrumentation.transparentCopyTracking?.let {tct ->
            TXF {
                ite(
                    tct.statusFlagVar eq TransparentCopyTracking.HASH_COPY,
                    tct.reprVar,
                    rest
                )
            }
        } ?: rest
        /**
         * If we are aligned (according to useAligned) use the native hash (in bufferHash) or the shadow hash var.
         */
        return prefix andThen TXF {
            ite(
                sourceLength eq 0,
                EMPTY_HASH_VAL,
                tryHashCopy
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
     *
     * [baseMap] is the map from which the values are read.
     */
    private sealed interface LongRead : ILongRead {
        val isNullRead: Boolean
        val traceEventInfo: TraceEventWithContext?
        val baseMap: TACSymbol.Var
        val desc: String
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
        override val baseMap: TACSymbol.Var,
        override val desc: String
    ) : LongRead {
        override val traceEventInfo: TraceEventWithContext?
            get() = null
    }

    /**
     * A long read that occurs at the same location of some other long read. The id of this other long read is
     * [parentId]. This is used to provide alternative buffer identities for a single long read; currently this is only
     * used to support the [CopyFromMaybeReturn] pattern.
     */
    private data class ShadowLongRead(
        override val where: CmdPointer,
        override val isNullRead: Boolean,
        override val baseMap: TACSymbol.Var,
        override val desc: String,
        override val loc: TACSymbol,
        override val length: TACSymbol,
        override val id: Int,
        val parentId: Int
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
        override val baseMap: TACSymbol.Var,
        override val desc: String
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

            /**
             * Update the hash using the transparent copy information in [copyTracker].
             * If the [copyTracker] isn't applicable (that is, [TransparentCopyTracking.statusFlagVar] == [TransparentCopyTracking.NO_COPY_FLAG])
             * then simply use [baseCase] as the hash representation.
             *
             * [pred], if specified, becomes an additional condition that must be true to use the information in [copyTracker].
             */
            fun generateTransparentHash(
                copyTracker: TransparentCopyTracking,
                baseCase: TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>,
                pred: TACExpr = TACSymbol.True.asSym()
            ) : TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
                return TXF {
                    ite(
                        (copyTracker.statusFlagVar eq TransparentCopyTracking.ENV_COPY_NO_CTXT) and pred,
                        TACExpr.SimpleHash(
                            length = copyTracker.sortVar.asSym(),
                            args = listOf(
                                currHash.asSym(),
                                relativeOffset.asSym(),
                                length.asSym(),
                                copyTracker.reprVar.asSym()
                            ),
                            hashFamily = HashFamily.Sha3
                        ),
                        ite(
                            copyTracker.statusFlagVar eq TransparentCopyTracking.ENV_COPY_WITH_CTXT and pred,
                            TACExpr.SimpleHash(
                                length = copyTracker.sortVar.asSym(),
                                args = listOf(
                                    currHash.asSym(),
                                    relativeOffset.asSym(),
                                    length.asSym(),
                                    copyTracker.reprVar.asSym(),
                                    copyTracker.extraCtxtVar.asSym()
                                ),
                                hashFamily = HashFamily.Sha3
                            ),
                            baseCase
                        )
                    )
                }
            }
            val withTransparentCopy = when(this) {
                is WriteFromLongRead -> {
                    val copyTracker = this.sourceInstrumentation.transparentCopyTracking
                    if(copyTracker != null) {
                        generateTransparentHash(
                            copyTracker,
                            theHash
                        )
                    } else {
                        theHash
                    }
                }
                is ByteStore,
                is ByteStoreSingle,
                is CodeCopy,
                is CopyFromCalldata,
                is CopyFromReturnBuffer,
                is UnknownEnvCopy -> theHash

                is CopyFromMaybeReturn -> {
                    /**
                     * Use the transparent copy information from either the local memory or callee's memory if appropriate.
                     */
                    val returnCopyTracking = this.returnDataInstrumentation.transparentCopyTracking
                    val fallbackCopyTracking = this.memoryInstrumentationInfo.transparentCopyTracking
                    if(returnCopyTracking != null && fallbackCopyTracking != null) {
                        val memoryAndFallback = generateTransparentHash(
                            fallbackCopyTracking,
                            theHash
                        )
                        generateTransparentHash(
                            returnCopyTracking,
                            memoryAndFallback,
                            TXF { returnBufferTracker.isReturnDataCopyVar eq ReturnBufferCopyTracker.TRANSPOSED_RETURN_COPY }
                        )
                    } else {
                        theHash
                    }
                }
            }
            return representative.toCRD() andThen withTransparentCopy
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
        val value: TACSymbol,

        val returnDataSample: List<TACSymbol.Var>
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
                value = f(value),
                returnDataSample = returnDataSample.map(f)
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
            ) + returnDataSample
        companion object {
            val META_KEY = MetaKey<CallEvent>("call.event.meta")
        }
    }

    /**
     * Describes how a copy out of an environment map (i.e., returndata or calldata)
     * is translated into a region in a method's memory.
     *
     * [envCopyLoc] is the location of the returndatacopy or calldata copy command.
     * [sourceMap] is the map from which the data in calldata/returndata was copied.
     * [envMapOffset] is the offset in returndata/calldata from which the copy was performed.
     * [sourceMemoryOffset] is the offset within [sourceMap] that was copied into the environment map.
     *
     * [translatedSym] is a variable which is (via requires) constrained to be equal to [envMapOffset] + [sourceMemoryOffset];
     * i.e., the offset in [sourceMap] from which data is being copied.
     * This [translatedSym] (along with the length of the copy and the [sourceMap]) is then treated as the [LongRead]
     * defining a copy into memory.
     *
     * Consider:
     *
     * ```
     * mem[x] = ...
     * calldata@1[0:len] = mem[x:len]
     * ...
     * L: mem@1[y:l] = calldata@1[z:l]
     * ```
     *
     * Where mem@1 and calldata@1 are the memory and calldata of some inlined callee. [envCopyLoc] will be L,
     * [sourceMap] will be `mem`, [envMapOffset] is `z`, [sourceMemoryOffset] is `x`.
     *
     * [translatedSym] will be some invented variable t. At L, we won't model the write as a copy from calldata,
     * but rather as an mcopy from `mem` from the offset `t`; remember that `t` is constrained to be [envCopyLoc] + [sourceMemoryOffset].
     *
     * [EnvironmentTranslationTracking] performs this instrumentation.
     */
    data class EnvDataTranslation(
        val envCopyLoc: CmdPointer,
        val sourceMap: TACSymbol.Var,
        val sourceMemoryOffset: TACSymbol,
        val envMapOffset: TACSymbol,
        val translatedSym: TACSymbol.Var
    )

    companion object {
        /**
         * We define the hash of an empty buffer to always be 0. This include buffers which are provably
         * empty, and those whose runtime length is 0.
         */
        const val EMPTY_HASH_VAL = 0

        val TRANSLATED_RETURN_COPY = MetaKey.Nothing("buffer.trace.translated-return-inst")

        fun TACCmd.Simple.isResultCommand() = this.isHalting() || (this is TACCmd.Simple.SummaryCmd && this.summ is ScalarEquivalenceSummary && when(this.summ) {
            is CommonPureInternalFunction -> false
            is ComputationResults -> true
        })

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

        infix fun TACSymbol.Var.`=`(expr: TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>) : CommandWithRequiredDecls<TACCmd.Simple> {
            return expr.toCRD().merge(this) andThen TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = this,
                rhs = expr.exp
            )
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

        private val zeroWritePattern = PatternDSL.build {
            commuteThree(
                freePointerLoad.asBuildable(),
                Var,
                0x20(),
                PatternDSL.CommutativeCombinator.add
            ) { _, _, _ -> Unit }
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
         * Find mcopys in [c] that "look like" they are copying out of a copy of return data. Recall that when decoding
         * complex outputs, solidity copies *all* of returndata into memory, and then copies subranges out of that buffer.
         *
         * [m] maps returndatacopy locations to the [EnvDataTranslation] describing how to map that copy back into some callee.
         * The result is a map of [CmdPointer] for mcopys to the [EnvDataTranslation] from which the mcopy is believed (heuristically)
         * to copy.
         *
         * Due to the undecidability of aliasing, we have to defer the actual determination of whether we have a copy from
         * returndata to "runtime", via [ReturnBufferCopyTracker]
         */
        private fun localReturnCopyCandidates(
            c: CoreTACProgram,
            m: Map<CmdPointer, EnvDataTranslation>
        ) : Map<CmdPointer, EnvDataTranslation> {
            val gvn = c.analysisCache.gvn
            val graph = c.analysisCache.graph
            val queries = m.entries.mapNotNull { (where, env) ->
                val outVar = graph.elab(where).narrow<TACCmd.Simple.ByteLongCopy>().cmd.dstOffset.let { s ->
                    s as? TACSymbol.Var
                } ?: return@mapNotNull null
                LTACVar(
                    where, outVar
                ) to env
            }
            val derivedFrom = PatternMatcher.Pattern.RecursivePattern<EnvDataTranslation>{ rec ->
                PatternDSL.build {
                    (Var { v, where ->
                        queries.firstNotNullOfOrNull { (lv, env) ->
                            if(v in gvn.findCopiesAt(where.ptr, lv.ptr to lv.v)) {
                                env
                            } else {
                                null
                            }
                        }?.let { e ->
                            PatternMatcher.VariableMatch.Match(e)
                        } ?: PatternMatcher.VariableMatch.NoMatch
                    }) lor (rec.asBuildable() + PatternMatcher.Pattern.AnySymbol.anySymbol.asBuildable()).commute.first
                }
            }
            val derivedFromCopyMatcher = PatternMatcher.compilePattern(
                graph, derivedFrom
            )

            return c.parallelLtacStream().mapNotNull { lc ->
                lc.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf { w ->
                    TACMeta.MCOPY_BUFFER in w.cmd.dstBase.meta && w.cmd.srcOffset is TACSymbol.Var
                }
            }.mapNotNull { lc ->
                lc.ptr `to?` derivedFromCopyMatcher.query(
                    q = lc.cmd.srcOffset as TACSymbol.Var,
                    src = lc.wrapped
                ).toNullableResult()
            }.collect(Collectors.toMap({it.first}, {it.second}))
        }

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
             * Associate call ids to the call core that was inlined, from which we can get the [EnvDataTranslation.sourceMemoryOffset]
             * and [EnvDataTranslation.sourceMap].
             */
            val contexts = code.parallelLtacStream().mapNotNull { lc ->
                lc.maybeAnnotation(Inliner.CallStack.STACK_PUSH)?.let { rec ->
                    rec `to?` rec.summary
                }
            }.collect(Collectors.toMap({ it.first.calleeId }, { it.second }))

            /**
             * Find all reads from calldata in inlined bodies for which we have something recorded in `contexts`,
             * and create an [EnvDataTranslation] for those calldata copies.
             */
            val calldataTranslations = code.parallelLtacStream().mapNotNull { lc ->
                lc.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf { cpy ->
                    TACMeta.IS_CALLDATA in cpy.cmd.srcBase.meta && TACMeta.EVM_MEMORY in cpy.cmd.dstBase.meta
                }
            }.filter {
                it.ptr.block.calleeIdx in contexts
            }.map { cc ->
                val callerConv = contexts[cc.ptr.block.calleeIdx]!!
                val translatedStart = TACSymbol.Var(
                    "translatedStart!${Allocator.getFreshNumber()}",
                    Tag.Bit256,
                    NBId.ROOT_CALL_ID,
                    MetaMap(TACMeta.NO_CALLINDEX)
                )
                cc.ptr to EnvDataTranslation(
                    sourceMap = callerConv.inBase,
                    sourceMemoryOffset = callerConv.inOffset,
                    envMapOffset = cc.cmd.srcOffset,
                    translatedSym = translatedStart,
                    envCopyLoc = cc.ptr
                )
            }.collect(Collectors.toMap({it.first}, {it.second}))

            /**
             * Ibid, but for returndata copies. NB unlike calldata copies, where it's always obvious where the calldata source is,
             * we rely on a side analysis + instrumentation here; see [ReturnCopyCorrelation] and [ReturnCopyCollapser].
             */
            val returnCopySources = code.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf {
                    it.cmd.meta.find(ReturnCopyCollapser.CONFLUENCE_COPY) != null
                }
            }.map { lc ->
                val srcId = lc.cmd.meta[ReturnCopyCollapser.CONFLUENCE_COPY]!!.id
                srcId to (lc.cmd.srcBase to lc.cmd.srcOffset)
            }.collect(Collectors.toMap({it.first}, {it.second}))

            val returnDataTranslations = code.parallelLtacStream().mapNotNull { lc ->
                lc.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.takeIf {
                    ReturnCopyCorrelation.CORRELATED_RETURN_COPY in it.cmd.meta
                }
            }.map { lc ->
                val source = lc.cmd.meta[ReturnCopyCorrelation.CORRELATED_RETURN_COPY]!!.which
                val (srcBase, srcOffset) = returnCopySources[source]!!
                val translatedStart = TACSymbol.Var(
                    "translatedStart!${Allocator.getFreshNumber()}",
                    Tag.Bit256,
                    NBId.ROOT_CALL_ID,
                    MetaMap(TACMeta.NO_CALLINDEX)
                )
                lc.ptr to EnvDataTranslation(
                    translatedSym = translatedStart,
                    sourceMemoryOffset = srcOffset,
                    sourceMap = srcBase,
                    envMapOffset = lc.cmd.srcOffset,
                    envCopyLoc = lc.ptr
                )
            }.collect(Collectors.toMap({it.first}, {it.second}))

            /**
             * Find mcopys out of memory that look like they *might* be copied
             * from returndata that has been copied into the caller's memory. See [ReturnBufferCopyTracker]
             * for the pattern in dicussion here.
             */
            val localReturnCopyTranslations = localReturnCopyCandidates(
                code, returnDataTranslations
            )

            /**
             * Find all long reads (called "sources" here because they are the source of the analysis).
             */
            val naturalSources = code.parallelLtacStream().filter {
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
                            baseMap = TACKeyword.MEMORY.toVar(), // it's a FAAAAKE
                            desc = "Scalar equiv summary @ ${it.ptr}"
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
                        baseMap = it.cmd.summ.sourceMap,
                        desc = "Loop buffer read @ ${it.ptr}"
                    )
                } else if(it.cmd !is TACCmd.Simple.LongAccesses) {
                    // anything else that's not a long access is, by definition, not a long read
                    return@mapNotNull null
                }
                if(it.ptr in calldataTranslations) {
                    val tr = calldataTranslations[it.ptr]!!
                    return@mapNotNull BasicLongRead(
                        where = it.ptr,
                        loc = tr.translatedSym,
                        length = it.narrow<TACCmd.Simple.ByteLongCopy>().cmd.length,
                        baseMap = tr.sourceMap,
                        id = idCounter.getAndIncrement(),
                        isNullRead = false,
                        desc = "Calldata 2 Caller @ ${it.ptr}"
                    )
                } else if(it.ptr in returnDataTranslations) {
                    val tr = returnDataTranslations[it.ptr]!!
                    return@mapNotNull BasicLongRead(
                        where = it.ptr,
                        loc = tr.translatedSym,
                        length = it.narrow<TACCmd.Simple.ByteLongCopy>().cmd.length,
                        baseMap = tr.sourceMap,
                        id = idCounter.getAndIncrement(),
                        isNullRead = false,
                        desc = "Returndata 2 Callee @ ${it.ptr}"
                    )
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
                    BasicLongRead(
                        where = it.ptr,
                        loc = read.offset,
                        length = read.length,
                        id = idCounter.getAndIncrement(),
                        isNullRead = nullRead,
                        baseMap = read.base,
                        desc = "LongRead 4 $it"
                    )
                } else {
                    EventLongRead(
                        where = it.ptr,
                        loc = read.offset,
                        length = read.length,
                        id = idCounter.getAndIncrement(),
                        isNullRead = nullRead,
                        traceEventInfo = traceInfo,
                        explicitReadId = it.cmd.meta.find(DefiniteBufferConstructionAnalysis.LONG_READ_ID),
                        baseMap = read.base,
                        desc = "Event Long Read @ ${it.ptr} 4 ${traceInfo.eventSort}"
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
                    baseMap = mload.cmd.base,
                    desc = "Long read 4 mload @ ${it.key}"
                )
            }

            /**
             * For the mcopys sources for which we think it may come from (a copy of) returndata from an
             * inlined callee, create a [ShadowLongRead] which defines the mcopy as being a copy out of the
             * callee's memory. We call these "syntheticSources". syntheticTrackers provide a way to link the
             * generated [ShadowLongRead] instances to the "real" [LongRead]
             */
            val (syntheticSources, syntheticTrackers) = naturalSources.mapNotNull { s ->
                val localTranslation = localReturnCopyTranslations[s.where] ?: return@mapNotNull null
                val srcCmd = g.elab(s.where).maybeNarrow<TACCmd.Simple.ByteLongCopy>() ?: return@mapNotNull null
                check(TACMeta.MCOPY_BUFFER in srcCmd.cmd.dstBase.meta) {
                    "not a local copy? what's going on $s: $srcCmd"
                }
                val translatedStart = TACSymbol.Var(
                    "translatedLocalCopy!${Allocator.getFreshNumber()}",
                    Tag.Bit256,
                    NBId.ROOT_CALL_ID,
                    MetaMap(TACMeta.NO_CALLINDEX) + TRANSLATED_RETURN_COPY
                )
                val freshId = idCounter.getAndIncrement()
                ShadowLongRead(
                    loc = translatedStart,
                    where = s.where,
                    isNullRead = false,
                    baseMap = localTranslation.sourceMap,
                    length = s.length,
                    id = freshId,
                    desc = "Synthetic read 4 local copy @ ${s.where}",
                    parentId = s.id
                ) to (s.id to Triple(
                    translatedStart,
                    localTranslation.envCopyLoc,
                    localTranslation.translatedSym,
                ))
            }.unzip().mapSecond { it.toMap() }

            val sources = syntheticSources + naturalSources

            val scratchConsumptionPattern = PatternMatcher.compilePattern(graph = g, patt = scratchPattern)

            val heuristicZeroWrite = PatternMatcher.compilePattern(graph = g, patt = zeroWritePattern)

            /**
             * Find long reads that look like they are using up a scratch buffer, which means that writes *after* that use
             * are likely not related to writes *before* the scratch use.
             *
             * These are uses whose source offset lies in constant scratch space range or which is the
             * value of the free pointer.
             */
            val mayConsumeScratch = sources.filter { longSource ->
                TACMeta.EVM_MEMORY in longSource.baseMap.meta &&
                g.elab(longSource.where).maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.cmd?.dstBase?.meta?.contains(TACMeta.MCOPY_BUFFER) != true &&
                when(val src = strictDefAnalysis.source(ptr = longSource.where, sym = longSource.loc)) {
                    is StrictDefAnalysis.Source.Uinitialized -> false
                    is StrictDefAnalysis.Source.Const -> src.n >= BigInteger.ZERO && src.n < 0x40.toBigInteger()
                    is StrictDefAnalysis.Source.Defs -> src.ptrs.singleOrNull()?.let { defSite ->
                        g.elab(defSite).maybeNarrow<TACCmd.Simple.AssigningCmd>()?.let {
                            scratchConsumptionPattern.queryFrom(it)
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
            }.map { it.ptr }.collect(Collectors.toSet()) + code.parallelLtacStream().filter {
                lc -> lc.maybeAnnotation(POP_ALLOCATION) != null
            }.map { it.ptr }.collect(Collectors.toSet()) + code.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf { lc ->
                    TACMeta.EVM_MEMORY in lc.cmd.base.meta && lc.cmd.loc is TACSymbol.Var &&
                        MustBeConstantAnalysis(
                            g
                        ).mustBeConstantAt(lc.ptr, lc.cmd.value) == BigInteger.ZERO
                }
            }.filter { write ->
                heuristicZeroWrite.query(write.cmd.loc as TACSymbol.Var, write.wrapped) is PatternMatcher.ConstLattice.Match
            }.map { it.ptr }.collect(Collectors.toSet())
            val reach = code.analysisCache.reachability

            // TODO(CERT-8862): we should also consider allocations as "future consumers"
            val mustPathInclusion = MustPathInclusionAnalysis.computePathInclusion(g)
            // map from consumption sites to the future consumption sites which we should treat as garbage collections
            val isGarbageCollectionFor = garbageCollectionPoints.associateWith { gcPoint ->
                mayConsumeScratch.filterToSet { futureConsumer ->
                    futureConsumer.where != gcPoint &&
                        reach.canReach(gcPoint, futureConsumer.where) &&
                        // is there another scratch consumer along the path? then we should instrument at that one
                        mustPathInclusion[gcPoint.block to futureConsumer.where.block].orEmpty().let { alongPath ->
                            garbageCollectionPoints.all { otherGc ->
                                otherGc == futureConsumer.where || otherGc.block !in alongPath || otherGc == gcPoint || (otherGc.block == gcPoint.block && otherGc.pos < gcPoint.pos)
                            }
                        } && (futureConsumer !is EventLongRead || futureConsumer.explicitReadId == null)
                }
            }

            /**
             * If we are tracking GC information for a long read, make sure to generate that information
             */
            val hasPrecedingGCPoint = isGarbageCollectionFor.entries.flatMapToSet { (_, futureConsumers) ->
                futureConsumers
            }

            val environmentCopyShifts = returnDataTranslations + calldataTranslations

            /**
             * Now, for each [LongRead], generate its [LongReadInstrumentation].
             */
            val sourceToInstrumentation = mutableMapOf<LongRead, LongReadInstrumentation>()
            for(s in sources) {
                val useSiteControl = options.useSiteControl[s.where]
                val isCopyBuffer = g.elab(s.where).let { lc ->
                    lc.snarrowOrNull<LoopCopyAnalysis.LoopCopySummary>()?.isMemoryByteCopy() == true ||
                        lc.maybeNarrow<TACCmd.Simple.ByteLongCopy>()?.cmd?.dstBase?.meta?.contains(TACMeta.MCOPY_BUFFER) == true ||
                        lc.ptr in environmentCopyShifts || Config.EquivalenceUniversalCopyTracking.get()
                }
                val i = s.id
                sourceToInstrumentation[s] = i.run {
                    LongReadInstrumentation(
                        hashVar = instrumentationVar("bufferHash"),
                        baseProphecy = instrumentationVar("bufferBaseProphecy"),
                        lengthProphecy = instrumentationVar("bufferLengthProphecy"),
                        gcInfo = null.letIf(s in hasPrecedingGCPoint) { _ ->
                            GarbageCollectionInfo(
                                writeBoundVars = instrumentationVar("lower") to instrumentationVar("upper"),
                                seenGCVar = instrumentationVar("seenGCPoint"),
                                gcSiteInfo = isGarbageCollectionFor.keysMatching { _, m ->
                                    s in m
                                }.withIndex().associate { (ind, where) ->
                                    val id = ind + 1
                                    where to GarbageCollectionInfo.InstrumentationPoint(
                                        gcPointId = id,
                                        gcHashBackupVar = instrumentationVar("backupHashVar${id}"),
                                        gcHashInitVar = instrumentationVar("initHashVar${id}"),
                                        gcAlignedBackupVar = instrumentationVar("backupAlignVar${id}", Tag.Bool),
                                        gcAlignedInitVar = instrumentationVar("initAlignVar${id}", Tag.Bool)
                                    )
                                }
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
                        },
                        transparentCopyTracking = runIf(isCopyBuffer) {
                            TransparentCopyTracking(
                                statusFlagVar = instrumentationVar("copyStatusFlag"),
                                extraCtxtVar = instrumentationVar("copyExtraCtxt"),
                                reprVar = instrumentationVar("copyReprVar"),
                                sortVar = instrumentationVar("sortReprVar")
                            )
                        },
                        envTranslationTracking = environmentCopyShifts[s.where]?.let { ccTr ->
                            EnvironmentTranslationTracking(
                                translatedOffset = ccTr.translatedSym,
                                envMapOffset = ccTr.envMapOffset,
                                sourceMemoryOffset = ccTr.sourceMemoryOffset
                            )
                        },
                        /**
                         * For this mcopy, trace whether it might be defined as a copy out of some callee method's
                         * memory. This instrumentation is how the "natural" mcopy and the "synthetic" copy from
                         * the callee's memory can communicate.
                         */
                        returnBufferCopyTracker = syntheticTrackers[s.id]?.let { (localMemTranslatedToCallee, writeLoc, returnDataCopyInCallee) ->
                            ReturnBufferCopyTracker(
                                candidateReturnWrite = writeLoc,
                                offsetFromReturnDataCopyDest = instrumentationVar("offsetFromReturnCopy"),
                                isReturnDataCopyVar = instrumentationVar("isReturnDataCopy"),
                                returnDataCopyInCallee = returnDataCopyInCallee,
                                localMemCopyTranslatedToCallee = localMemTranslatedToCallee
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
                isGarbageCollectionFor = isGarbageCollectionFor.mapValuesNotNull { (_, l) ->
                    l.filter { r ->
                        r in hasPrecedingGCPoint
                    }.takeIf(List<LongRead>::isNotEmpty)
                },
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
                TraceEventSort.RESULT -> {
                    if(marker.context !is Context.ResultMarker) {
                        return mismatch()
                    }
                    return RawEventParams.CodeResult(
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

        override val sortRepr: Int
            get() = sort.ordinal

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
     * A write that might come from a copy of returndata. If the copy can't or shouldn't be treated as a copy
     * from a copy of return data, the long read to use is [memoryLongRead].
     *
     * If the read can be translated to some callee's memory, [translatedReturnBufferRead] is the buffer defined
     * in the callee's memory.
     *
     * [returnBufferTracker] is the instrumentation mixin (attached to [memoryLongRead]) that determines whether
     * [translatedReturnBufferRead] or [memoryLongRead] should be used.
     */
    private inner class CopyFromMaybeReturn(
        val memoryLongRead: LongRead,
        val returnBufferTracker: ReturnBufferCopyTracker,
        val translatedReturnBufferRead: LongRead
    ) : WriteSource, IWriteSource.ConditionalReturnCopy {
        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return TXF {
                ite(
                    returnBufferTracker.isReturnDataCopyVar eq ReturnBufferCopyTracker.NO_RETURN_COPY,
                    memoryLongRead.instrumentationInfo().allAlignedVar,
                    translatedReturnBufferRead.instrumentationInfo().allAlignedVar
                )
            }
        }

        val memoryInstrumentationInfo get() = memoryLongRead.instrumentationInfo()

        val returnDataInstrumentation get() = translatedReturnBufferRead.instrumentationInfo()

        /**
         * Q: Shouldn't this be returndata copy?
         * A: No, if we are copying from "local" memory, it's a buffer copy, if we are copying from the callee's memory,
         * it's still a buffer copy.
         */
        override val sort: WriteSort
            get() = WriteSort.BUFFER_COPY

        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            val asMemory = getBufferIdentity(memoryLongRead)
            val asTranslateReturnCopy = getBufferIdentity(translatedReturnBufferRead)
            return asMemory.toCRD() andThen asTranslateReturnCopy.toCRD() andThen TXF {
                ite(
                    returnBufferTracker.isReturnDataCopyVar eq ReturnBufferCopyTracker.TRANSPOSED_RETURN_COPY,
                    asTranslateReturnCopy.exp,
                    asMemory.exp
                )
            }
        }

        override val extraContext: TACSymbol?
            get() = null
        override val conditionalOn: TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>
            get() = TXF { returnBufferTracker.isReturnDataCopyVar eq ReturnBufferCopyTracker.TRANSPOSED_RETURN_COPY }.lift()
        override val translatedReturnCopy: IWriteSource.LongMemCopy
            get() = McopyBufferRead(sourceRead = translatedReturnBufferRead)
        override val fallbackCopy: IWriteSource.LongMemCopy
            get() = McopyBufferRead(memoryLongRead)

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
        override val sortRepr: Int
            get() = sort.ordinal
        override val baseMap: TACSymbol.Var
            get() = TACKeyword.RETURNDATA.toVar()
        override val sourceLoc: TACSymbol
            get() = sourceOffset
    }

    /**
     * Common interface used for writes whose source is described by another long read (i.e.,
     * copy loops and mcopy).
     */
    private sealed interface WriteFromLongRead : WriteSource {
        /**
         * The [LongReadInstrumentation] used to describe the long read which is the source of this long *write*.
         */
        val sourceInstrumentation: LongReadInstrumentation

        override fun getSourceIsAlignedPredicate(): ToTACExpr {
            return sourceInstrumentation.allAlignedVar
        }

        override val extraContext: TACSymbol?
            get() = null

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

    private abstract inner class AbstractLongCopy(
        val sourceRead: LongRead,
    ) : WriteFromLongRead, IWriteSource.LongMemCopy {
        override val sourceInstrumentation: LongReadInstrumentation
            get() = sourceRead.instrumentationInfo()

        override val sourceBuffer: ILongReadInstrumentation
            get() = sourceInstrumentation

        override val baseMap: TACSymbol.Var
            get() = sourceRead.baseMap

        /**
         * The representative of this read is the buffer identity. We can't use [getBufferIdentity] because
         * this isn't an inner class :(
         */
        override fun getValueRepresentative(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return getBufferIdentity(sourceRead)
        }

        override fun getBufferIdentity(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return getValueRepresentative()
        }
    }

    /*
     * XXX(jtoman): it's unclear if these should all have separate sorts; probably not, right?
     */

    /**
     * Loop copy variant of [WriteFromLongRead]. This write type isn't actually
     * described in the EC paper, but it operates equivalently to the mcopy.
     */
    private inner class LoopCopy(
        sourceRead: LongRead,
    ) : AbstractLongCopy(sourceRead) {
        override val sort: WriteSort
            get() = WriteSort.LOOP_COPY
    }

    /**
     * mcopy variant of [WriteFromLongRead]
     */
    private inner class McopyBufferRead(
        sourceRead: LongRead,
    ) : AbstractLongCopy(sourceRead) {
        override val sort: WriteSort
            get() = WriteSort.BUFFER_COPY
    }

    private inner class TransposedEnvironmentCopy(
        sourceRead: LongRead
    ) : AbstractLongCopy(sourceRead) {
        override val sort: WriteSort
            get() = WriteSort.TRANSPOSED_COPY

    }

    private enum class WriteSort {
        LOOP_COPY,
        CALLDATA_COPY,
        BUFFER_COPY,
        STORE_SINGLE,
        STORE,
        RETURNDATA_COPY,
        UNKNOWN_COPY,
        STATIC_CODE_COPY,
        TRANSPOSED_COPY
    }

    /**
     * Private version of [IBufferUpdate], where the [source] field is an intersection of [IWriteSource]
     * and [WriteSource], letting it pull double duty as the private version of the type and the public version.
     */
    private data class BufferUpdate<T>(
        override val where: CmdPointer,
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
        val preciseBoundedWindow: BoundedPreciseCellInstrumentation?,
        /**
         * Tracks whether this buffer is actually a complete copy of some other buffer
         */
        val transparentCopyTracking: TransparentCopyTracking?,

        /**
         * If this is a long read that translates an env copy to the memory map that defines it,
         * perform the instrumentation that translates the env map offset into the source memory space
         */
        val envTranslationTracking: EnvironmentTranslationTracking?,

        val returnBufferCopyTracker: ReturnBufferCopyTracker?
    ) : WithVarInit, ILongReadInstrumentation {
        override val havocInitVars get() = listOf(lengthProphecy, baseProphecy)

        val instrumentationMixins : List<InstrumentationMixin> get() = listOfNotNull(
            gcInfo,
            bufferWriteInfo,
            eventSiteVisited,
            preciseBoundedWindow,
            transparentCopyTracking,
            envTranslationTracking,
            returnBufferCopyTracker
        )

        override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>> = listOf(
            hashVar to TACSymbol.Zero,
            allAlignedVar to TACSymbol.True
        )
        override val transparentCopyData: TransparentCopyData?
            get() = transparentCopyTracking
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
        currSite: CmdPointer,
        laterConsumers: Iterable<LongRead>
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        return laterConsumers.map { laterRead ->
            val readInfo = readToInstrumentation[laterRead]!!
            check(readInfo.gcInfo != null) {
                "Have null gc instrumentation for $readInfo"
            }
            val forInitSite = readInfo.gcInfo.gcSiteInfo[currSite]
            check(forInitSite != null) {
                "No GC data registered in $laterRead for $currSite"
            }
            val gcInfo = readInfo.gcInfo
            with(TACExprFactTypeCheckedOnlyPrimitives) {
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.LabelCmd("Starting setup for ${laterRead.id}"),
                        forInitSite.gcHashBackupVar `=` readInfo.hashVar,
                        gcInfo.seenGCVar `=` forInitSite.gcPointId.asTACExpr,
                        readInfo.hashVar `=` forInitSite.gcHashInitVar,
                        gcInfo.writeBoundVars.first `=` GarbageCollectionInfo.uninitMarker,
                        gcInfo.writeBoundVars.second `=` GarbageCollectionInfo.uninitMarker,
                        forInitSite.gcAlignedBackupVar `=` readInfo.allAlignedVar,
                        readInfo.allAlignedVar `=` forInitSite.gcAlignedInitVar,
                        TACCmd.Simple.LabelCmd("End setup for ${laterRead.id}"),
                    )
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
                check(thisCopySource.baseMap == lc.cmd.summ.sourceMap) {
                    "coherence: copy source wasn't the same as summary source $lc vs $thisCopySource"
                }
                return BufferUpdate(where = lc.ptr, loc = lc.cmd.summ.outPtr.first(), length = lc.cmd.summ.lenVars.first(), buffer = lc.cmd.summ.destMap, source = LoopCopy(
                    sourceRead = thisCopySource,
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
                        if(TACMeta.IS_CALLDATA in lc.cmd.srcBase.meta) {
                            sources.singleOrNull {
                                it.where == lc.ptr
                            }?.let { tr ->
                                return BufferUpdate(
                                    where = lc.ptr,
                                    loc = lc.cmd.dstOffset,
                                    length = lc.cmd.length,
                                    buffer = lc.cmd.dstBase,
                                    source = TransposedEnvironmentCopy(
                                        tr
                                    )
                                )
                            }
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = CopyFromCalldata(lc.cmd.srcOffset)
                            )
                        } else if(TACMeta.IS_RETURNDATA in lc.cmd.srcBase.meta) {
                            sources.singleOrNull {
                                it.where == lc.ptr
                            }?.let { tr ->
                                return BufferUpdate(
                                    where = lc.ptr,
                                    loc = lc.cmd.dstOffset,
                                    length = lc.cmd.length,
                                    buffer = lc.cmd.dstBase,
                                    source = TransposedEnvironmentCopy(
                                        tr
                                    )
                                )
                            }
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
                                src !is ShadowLongRead && potentialDefinitions.withIndex().all { (otherIdx, otherSrc) ->
                                    otherIdx == idx || g.cache.domination.dominates(otherSrc.where, src.where)
                                }
                            }.value

                            val translatedReturnLongRead = sources.singleOrNull {
                                it is ShadowLongRead && it.parentId == definingCopy.id
                            }
                            val mcopySource = if(translatedReturnLongRead != null) {
                                val returnTranslation = definingCopy.instrumentationInfo().returnBufferCopyTracker!!
                                CopyFromMaybeReturn(
                                    memoryLongRead = definingCopy,
                                    returnBufferTracker = returnTranslation,
                                    translatedReturnBufferRead = translatedReturnLongRead
                                )
                            } else {
                                McopyBufferRead(
                                    definingCopy
                                )
                            }
                            return BufferUpdate(
                                where = lc.ptr,
                                loc = lc.cmd.dstOffset,
                                length = lc.cmd.length,
                                buffer = lc.cmd.dstBase,
                                source = mcopySource
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

        val returnSizeVar = TACKeyword.RETURN_SIZE.toVar(callIndex = origLc.ptr.block.calleeIdx)
        val rcCodeVar = TACKeyword.RETURNCODE.toVar(callIndex = origLc.ptr.block.calleeIdx)

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
            (returnSizeVar lt BigInteger.TWO.pow(32).asTACExpr) and (rcCodeVar le TACSymbol.One)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "returnsize setup")
        /**
         * any call to an empty account returns an empty buffer and succesfully returns
         */
        } andThen ExprUnfolder.unfoldPlusOneCmd("eoaSanity", TACExprFactoryExtensions.run {
            (not(calleeCodesize eq 0) or
                ((returnSizeVar eq 0) and (rcCodeVar eq 1))) and (calleeCodesize lt 24576)
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
                        returnSizeVar lt origCommand.outSize,
                        returnSizeVar,
                        origCommand.outSize
                    )
                }
            )
        ), setOf(copyAmount))

        val returnDataVar = TACKeyword.RETURNDATA.toVar(callIndex = origLc.ptr.block.calleeIdx)

        /**
         * Actually set the environment variables
         */
        val callUpdate = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = rcCodeVar,
                rhs = rcExpr
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = returnSizeVar,
                rhs = returnDataSizeExpr
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = returnDataVar,
                rhs = returnDataExpr
            )
        ), setOf(
            rcCodeVar,
            returnDataVar,
            returnSizeVar
        ))

        val returnDataSampleSize = g.cache[MustBeConstantAnalysis].mustBeConstantAt(
            origLc.ptr,
            origCommand.outSize
        )?.takeIf {
            it.mod(EVM_WORD_SIZE) == BigInteger.ZERO && it > BigInteger.ZERO
        }?.let {
            it / EVM_WORD_SIZE
        }?.toIntOrNull()

        val (returnDataSamples, sampleReads) = returnDataSampleSize?.let { sz ->
            (0 ..< sz).map { ind ->
                val t = TACKeyword.TMP(Tag.Bit256, "!return$ind!")
                t to CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = t,
                        loc = (ind * EVM_WORD_SIZE_INT).asTACSymbol(),
                        base = returnDataVar
                    ),
                    t
                )
            }
        }?.unzip()?.mapSecond { reads ->
            reads.flatten()
        } ?: (listOf<TACSymbol.Var>() to CommandWithRequiredDecls<TACCmd.Simple>())

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
        ), setOf(stateCopy, globalStateAccumulator, stateCopy)) andThen
            callUpdate andThen
            returnSizeConstraint andThen
            sampleReads andThen
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
                    returnCode = rcCodeVar,
                    returnDataSize = returnSizeVar,
                    value = origCommand.value,
                    returnDataSample = returnDataSamples
                )
            ) andThen
            // update memory with the copy amount
            TACCmd.Simple.ByteLongCopy(
                dstBase = origCommand.outBase,
                length = copyAmount,
                dstOffset = origCommand.outOffset,
                srcOffset = TACSymbol.Zero,
                srcBase = returnDataVar
            )
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
                        TraceEventSort.RESULT,
                        TraceEventSort.REVERT,
                        TraceEventSort.RETURN -> {
                            `impossible!`
                        }
                        TraceEventSort.INTERNAL_SUMMARY_CALL,
                        TraceEventSort.LOG,
                        TraceEventSort.EXTERNAL_CALL -> {
                            val countVar = when(eventInfo.eventSort) {
                                // these are required by the compiler, but IDEA complains about them :(
                                TraceEventSort.RESULT,
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
                                TraceEventSort.RESULT,
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
        is Context.ResultMarker -> results.lift()
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

        @KSerializable
        data class ResultMarker(
            val results: List<TACSymbol>
        ) : Context {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Context {
                return ResultMarker(results.map { it.map(f) })
            }

            override val support: Set<TACSymbol.Var>
                get() = results.mapNotNullToSet { it as? TACSymbol.Var }

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
                generateGCSetup(where, futureReads)
            }.orEmpty()
        }.let {
            BufferWriteInstrumentation(it, update.isBefore)
        }
    }

    private fun setupLongRead(
        s: LongRead
    ) : CommandWithRequiredDecls<TACCmd.Simple> {

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
                TACCmd.Simple.AssumeCmd(it.s, "set prophecy for ${s.id}")
            }
        }.let(CommandWithRequiredDecls.Companion::mergeMany).merge(pointerProphecy, lengthProphecy) andThen sourceInfo.instrumentationMixins.map {
            it.atLongRead(s)
        }.flatten() andThen TACCmd.Simple.AnnotationCmd(
            ReadSiteInstrumentationRecord.META_KEY,
            ReadSiteInstrumentationRecord(
                which = s.id,
                desc = s.desc,
                sourceInfo.instrumentationMixins.mapNotNull { i ->
                    i.getRecord()
                }
            )
        )
        return prophecyAndMixinUpdate
    }

    /**
     * Instruments a long read. If this long read corresponds to a memory write (which will only be the case for call cores)
     * this is included in [bufferUpdateWork].
     */
    private fun instrumentLongRead(s: LongRead, shadowRead: ShadowLongRead?, patcher: SimplePatchingProgram, bufferUpdateWork: BufferUpdate<*>?) {
        if(!patcher.isBlockStillInGraph(s.where.block)) {
            return
        }
        val lc = g.elab(s.where)
        val origCommand = lc.cmd

        val prophecyAndMixinUpdate = setupLongRead(s).letIf(shadowRead != null) { upd ->
            upd andThen setupLongRead(shadowRead!!)
        }

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
                s.where,
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
            if(s is ShadowLongRead) {
                continue
            }
            instrumentLongRead(
                s, shadowRead = sources.firstNotNullOfOrNull { sh ->
                    (sh as? ShadowLongRead)?.takeIf {
                        sh.parentId == s.id
                    }
                }, patcher = patcher, bufferUpdateWork = hashUpdateWork[s.where]
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
            patcher.addBefore(s, generateGCSetup(s, next))
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
        ).flatten().wrap("Compute buffer hash for ${ls.id}")
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

        data class CodeResult(override val context: Context.ResultMarker) : RawEventParams {
            override val sort: TraceEventSort
                get() = TraceEventSort.RESULT

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
        INTERNAL_SUMMARY_CALL(TraceTargets.Calls, showBuffer = false),
        RESULT(TraceTargets.Results, showBuffer = false)
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
            ), setOf(traceEventValue, traceHashVar)).wrap("Compute event payload ${source.id}")

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
        toRet.extend((hash `=` {
                ite(overlapSym, hashUpdate.exp, hash)
            }).wrap("Hash update for ${targetBuffer.id}")
        )
        // update alignment
        toRet.extend((sourceInfo.allAlignedVar `=` {
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
            }).wrap("Alignment for ${targetBuffer.id}")
        )

        val hashUpdateCommands = hashUpdate.toCRD().wrap("Hash Decls ${targetBuffer.id}") andThen toRet.toCommandWithRequiredDecls().wrap("Update Hash & Alignment ${targetBuffer.id}")
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
