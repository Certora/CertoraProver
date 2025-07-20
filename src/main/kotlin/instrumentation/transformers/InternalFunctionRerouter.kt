@file:UseSerializers(BigIntegerSerializer::class)
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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package instrumentation.transformers

import allocator.Allocator
import analysis.*
import analysis.alloc.lower32BitMask
import analysis.icfg.*
import analysis.icfg.Summarizer.resolveCandidates
import analysis.ip.InternalArgSort
import analysis.ip.InternalFuncArg
import analysis.ip.InternalFuncRet
import analysis.ip.InternalFunctionStartInfo
import com.certora.collect.*
import kotlinx.serialization.UseSerializers
import datastructures.stdcollections.*
import evm.*
import instrumentation.calls.CallConvention
import instrumentation.calls.CallOutput
import report.callresolution.CallResolutionTableSummaryInfo
import scene.ContractClass
import scene.IScene
import spec.CVL
import spec.CodeGenUtils
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.SolidityContract
import spec.cvlast.SpecCallSummary
import spec.cvlast.abi.DataLayout
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.EVMTypeDescriptor.Companion.getDataLayout
import spec.cvlast.typedescriptors.TerminalAction.Companion.sizeAsEncodedMember
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.toCore
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.flatten
import java.math.BigInteger

/**
 * Code for rerouting internal function calls to harness libraries.
 *
 * This rerouting has three steps:
 * 1. Encoding the internal function arguments to a call buffer
 * 2. Sending the external call
 * 3. Post-processing the results (parsing out the results)
 *
 * The vast, vast majority of this class is devoted to this encoding and
 * decoding. A careful reader might discover that this encoding/decoding
 * bears great similarities to the converter infrastructure used in CVL.
 * Unfortunately, that infrastructure outputs [CVLTACProgram] which
 * is unsuitable for use in this class (which runs during preprocessing).
 * However, familiarity with that infrastructure should make this code much
 * easier to understand.
 */
object InternalFunctionRerouter {
    /**
     * A private typealias for `(BigInteger, EncodingContext, MutableMap<...>) -> ProgramWithNext`
     * because kotlin still, in the year of our Lord 2025, does not support local type aliases.
     */
    private fun interface ArgumentEncoder {
        /**
         * [baseOffset] the raw offset into the calldata buffer of this value.
         * [o] is the [EncodingContext] to use to encode the argument.
         * [slots] is used to record that offsets (the keys) in the calldata buffer
         * are populated by certain symbols (the values). This is ultimately used to pass storage
         * pointers without going through memory (which makes the storage analysis sad).
         *
         * [ProgramWithNext] returns the program which encodes the value, along with the new position of the next pointer.
         */
        fun encode(
            baseOffset: BigInteger,
            o: EncodingContext,
            slots: MutableMap<BigInteger, TACSymbol>
        ) : ProgramWithNext
    }

    private val wordSizeSym = EVM_WORD_SIZE.asTACSymbol()

    private fun String.tmp(t: Tag = Tag.Bit256) = TACSymbol.Var(this, t).toUnique("!")

    /**
     * As noted in the class opener, this transformation runs during preprocessing. It is unknown whether the other
     * preprocessors would handle the appearance of a [CallSummary]. To play it safe, we instead insert this
     * marker annotation, which is later materialized into a [CallSummary] in [InternalFunctionRerouter.materializeReroutes],
     * which is called after [CallSummary] objects are created by the [ExtCallSummarization] pass. In other words,
     * we delay creating [CallSummary] to ensure that these call summaries show up at a predictable point
     * in the pipeline. This class thus holds all of the information necessary to generate the [CallSummary]
     * during that materialization.
     */
    @KSerializable
    data class RerouteToMaterialize(
        /**
         * The callee contract. At the time this class is created, we do not yet have a scene, so we keep the string
         * name here
         */
        val hostContract: SolidityContract,
        /**
         * Sighash (to populate [CallSummary.sigResolution])
         */
        val sighash: BigInteger,
        /**
         * Input base buffer, used for [CallSummary.inBase]
         */
        val inputBase: TACSymbol.Var,
        /**
         * Input size, used for [CallSummary.inSize]. NB that we don't store the input offset, that is
         * always 0.
         */
        val inputSize: TACSymbol.Var,
        /**
         * The translated into [ScratchByteRange] to [DecomposedCallInputArg], which lets us pass scalarized storage references
         */
        val offsetToSlot: Map<BigInteger, TACSymbol>,
    ) : TransformableVarEntityWithSupport<RerouteToMaterialize>, AmbiSerializable {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): RerouteToMaterialize {
            return RerouteToMaterialize(
                hostContract, sighash, f(inputBase), f(inputSize), offsetToSlot.mapValues {
                    (it.value as? TACSymbol.Var)?.let(f) ?: it.value
                }
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(inputBase, inputSize) + offsetToSlot.values.filterIsInstance<TACSymbol.Var>()

        companion object {
            val META_KEY = MetaKey<RerouteToMaterialize>("tac.cvl.reroute.annotation")
        }
    }

    /**
     * An object that supports reading a value, and then generating some program with that value.
     */
    private interface Readable {
        private fun <T> readInternal(nm: String, f: (TACSymbol.Var) -> T, merge: (CommandWithRequiredDecls<TACCmd.Simple>, T) -> T) : T {
            val readValue = nm.tmp(Tag.Bit256)
            val toPrefix = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = readValue,
                    loc = readPointer,
                    base = baseMap
                )
            ), setOf(readValue, baseMap, readPointer))
            return merge(toPrefix, f(readValue))
        }

        /**
         * Read whatever value this object is "pointing" to; the (frehs) variable holding this read value
         * is passed to [f], which must return [T], something with a [CoreTACProgram]. This
         * fresh variable's name is based off of the [nm] parameter. The code to effect
         * the read is prepended onto the program wrapped by [T] via [WithProgram.mapProgram]
         */
        fun <T: WithProgram<CoreTACProgram, T>> read(nm: String = "readValue", f: (TACSymbol.Var) -> T) : T {
            return readInternal(nm, f) { pref, withProg ->
                pref andThen withProg
            }
        }

        /**
         * The bytemap from which values are read
         */
        val baseMap: TACSymbol.Var

        /**
         * The pointer into the [baseMap] at which values are read.
         */
        val readPointer: TACSymbol
    }

    /**
     * An object that encapsulates a pointer into a buffer that supports incrementing said pointer. [T] is the self type, and
     * is expected to be the concrete name of this interface's implementers.
     */
    private interface SupportsPointerArithmetic<T> {
        /**
         * Adds the expression [o] to the current pointer represented by this class, and generates
         * a new instance of [T] with this new pointer position. This new encapsulated pointer
         * is passed to [f], which generates a [WithProgram] of type [P]. The variable holding
         * the newly generated pointer is based off of [nm]. The code to effect
         * the addition is prepended onto the returned [P] via the [WithProgram.mapProgram].
         *
         * NB that any free variables in [o] are **not** added to [P]'s symbols; the caller is responsible
         * for ensuring that the [P] returned from [f] has declarations for these variables.
         */
        fun <P: WithProgram<CoreTACProgram, P>> add(o: ToTACExpr, nm: String = "newPointer", f: (T) -> P) : P {
            val nextPointer = nm.tmp(Tag.Bit256)
            return ExprUnfolder.unfoldPlusOneCmd(nm, o.toTACExpr()) {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = nextPointer,
                    rhs = TXF { it add pointer }
                )
            }.merge(nextPointer, pointer) andThen f(copyWithPointer(nextPointer))
        }

        /**
         * Variant of [add] above where the pointer is incremented by a single symbol [o]. If [o] is a variable,
         * it *is* added to the program [P] returned by [f].
         */
        fun <P: WithProgram<CoreTACProgram, P>> add(o: TACSymbol, nm: String = "newPointer", f: (T) -> P) : P {
            val nextPointer = nm.tmp(Tag.Bit256)
            val crd = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = nextPointer,
                    rhs = TXF { o add pointer }
                )
            ), setOf(pointer, o, nextPointer))
            return crd andThen f(copyWithPointer(nextPointer))
        }

        /**
         * Used by [add] implementations to construct new instances of [T] (aka "self") with
         * a new pointer value.
         */
        fun copyWithPointer(o: TACSymbol.Var) : T

        /**
         * The current pointer
         */
        val pointer: TACSymbol
    }

    /**
     * An encapsulated pointer into a buffer which can be used as an operand to a long copy (source or destination).
     * This does *not* include any notion of length, simply a starting position ([pointer]) within some buffer ([baseMap])
     */
    private interface SupportsCopy {
        val baseMap: TACSymbol.Var
        val pointer: TACSymbol
    }

    /**
     * Encapsulates a returndata buffer being decoded after the rerouted call.
     * [baseMap] is the temporary basemap which is a shallow copy of returndata, and [currPointer]
     * is a pointer into that buffer. [length] is a temporary value, and is used to communicate the
     * variable which holds the length of an
     * array to the processing of that arrays elements. [savedScope] holds a variable against which [DataLayout.DynamicPointer]
     * should be resolved, i.e., the start of the dynamic tuple.
     */
    private data class DecodingContext(
        override val baseMap: TACSymbol.Var,
        val currPointer: TACSymbol.Var,
        val savedScope: TACSymbol.Var?,
        val length: TACSymbol.Var?
    ) : Readable, SupportsPointerArithmetic<DecodingContext>, SupportsCopy {


        override val readPointer: TACSymbol
            get() = currPointer

        override fun copyWithPointer(o: TACSymbol.Var): DecodingContext {
            return this.copy(
                currPointer = o
            )
        }

        override val pointer: TACSymbol
            get() = currPointer
    }

    /**
     * The result of an encoding, consisting of a program [prog], and the current value
     * of the [next] pointer. This [next] pointer is threaded through encoding "linearly"
     * (OMG SO THIS IS BASICALLY RUST?!?!?!); the next pointer returned by encoding type T1 is
     * used as the next pointer for type T2 (where T2 follows T1 in the encoding).
     */
    private data class ProgramWithNext(
        val prog: CoreTACProgram,
        val next: TACSymbol.Var
    ) : WithProgram<CoreTACProgram, ProgramWithNext> {
        fun prepend(o: CommandWithRequiredDecls<TACCmd.Simple>) = this.copy(
            prog = prog.prependToBlock0(o)
        )

        override fun mapProgram(f: (CoreTACProgram) -> CoreTACProgram): ProgramWithNext {
            return ProgramWithNext(
                prog = f(this.prog),
                next = next
            )
        }
    }

    /**
     * The "dual" of the [DecodingContext]; represents an ABI encoding of the internal function arguments.
     * [baseMap] is the temporary buffer which will be used as the call buffer. [nextPointer], if non-null,
     * holds the variable which points to the current end of the encoded buffer, i.e., where dynamic data should be
     * placed. [savedScope] serves the same purpose as [DecodingContext]; when computing a relative offset, the
     * value of the [nextPointer] should be computed relative to [savedScope]. [currPointer] is the current position
     * of the element being encoded into the buffer.
     *
     * [staticOffset] and [staticReservation] are used to track positions within statically sized values tuple,
     * and is used to detect when we need to adjust the free pointer.
     *
     * Unlike the [spec.converters.EncodingInterpreter], this class is nowhere near as tightly encapsulated, and its user
     * (the [parallelEncode] function) will freely change the fields of this class. Given how tightly coupled these implementations
     * are, this was chosen to make the implementation clearer.
     */
    private data class EncodingContext(
        override val baseMap: TACSymbol.Var,
        val nextPointer: TACSymbol.Var?,
        val savedScope: TACSymbol.Var?,
        val currPointer: TACSymbol.Var,
        val staticReservation: BigInteger?,
        val staticOffset: BigInteger?
    ) : SupportsCopy {
        /**
         * Reserves a block of size [o] starting from [currPointer]. If the [nextPointer] is not yet set (thus
         * [staticReservation] is null), set it to be [currPointer] + [o]. Otherwise, ensure that the block being allocated fits
         * within [staticReservation] starting from the current [staticOffset].
         *
         * The [EncodingContext] representing this allocated block into which values should be encoded is passed
         * to [f]. The [ProgramWithNext] returned from [f] has the code to compute the [nextPointer] (if any)
         * prepended onto it via [WithProgram.mapProgram]
         */
        fun reserve(o: BigInteger, f: (EncodingContext) -> ProgramWithNext) : ProgramWithNext {
            if(this.staticReservation != null) {
                check(this.staticOffset != null && nextPointer != null) {
                    "Invariant broken: have non-null $staticReservation but null $staticOffset"
                }
                if(this.staticOffset + o > staticReservation) {
                    throw IllegalArgumentException("Under allocation")
                }
                return f(EncodingContext(
                    baseMap = baseMap,
                    nextPointer = nextPointer,
                    savedScope = null,
                    currPointer = currPointer,
                    staticReservation = o,
                    staticOffset = BigInteger.ZERO
                ))
            } else {
                val next = TACKeyword.TMP(Tag.Bit256, "!next")
                val toPrepend = CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = next,
                        rhs = TXF {
                            currPointer add o
                        }
                    )
                ), next, currPointer)
                return toPrepend andThen f(EncodingContext(
                    baseMap = baseMap,
                    nextPointer = next,
                    savedScope = savedScope,
                    currPointer = currPointer,
                    staticReservation = o,
                    staticOffset = BigInteger.ZERO
                ))
            }
        }

        /**
         * Write a symbol [v] into [baseMap] at [currPointer]. No sanity checking is done to ensure
         * that space has been allocated for it.
         */
        fun write(v: TACSymbol) = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.ByteStore(
                base = baseMap,
                loc = currPointer,
                value = v
            )
        ), setOf(baseMap, currPointer, v))

        override val pointer: TACSymbol
            get() = currPointer
    }

    /**
     * Represents where decoded values should be placed. Initially, decoded values are placed into the stack (represented
     * by [DecodingOutput.Scalar], but the fields of complex types (whose *pointer* is placed into the stack) are put
     * into memory (represented by [DecodingOutput.PointerDecodingOutput]).
     */
    private sealed interface DecodingOutput {
        /**
         * Writes the value [o] to the value location represented by this object, i.e., a stack variable
         * or a cell in memory.
         */
        fun write(o: TACSymbol) : CommandWithRequiredDecls<TACCmd.Simple>

        /**
         * The basemap into which memory writes should occur. In practice this is always just [TACKeyword.MEMORY] but
         * is parameterized for (foolish?) flexibility.
         */
        val base: TACSymbol.Var

        /**
         * Allocates a block of size [sz], and then creates a [PointerDecodingOutput] which
         * "points to" the beginning of this block in memory. This [PointerDecodingOutput] is then passed
         * to [f], which is reponsible for *completely* initializing the memory block. After [f] returns, the
         * pointer which was allocated by this function is written into the value location represented by *this* class
         * via [write]. In other words, this function effectively writes a pointer to a new object into the memory cell/stack variable
         * represented by this; [f] is responsible for initializing the memory pointed to by that block.
         *
         * It is not checked (by us anyway) that [f] properly initializes memory. If it doesn't, the
         * [analysis.pta.SimpleInitializationAnalysis] will simply fail.
         */
        fun allocate(sz: TACSymbol, f: (PointerDecodingOutput) -> CoreTACProgram) : CoreTACProgram {
            val newPointer = "allocatedPointer".tmp(Tag.Bit256)
            val newFp = "newFp".tmp(Tag.Bit256)
            val prefix = CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = newPointer,
                    rhs = TACKeyword.MEM64.toVar()
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = newFp,
                    rhs = TACExpr.Vec.Add(newPointer.asSym(), sz.asSym())
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = TACKeyword.MEM64.toVar(),
                    rhs = newFp
                )
            ), setOf(sz, newFp, newPointer, TACKeyword.MEM64.toVar()))
            return f(PointerDecodingOutput(
                newPointer, base = base
            )).appendToSinks(write(newPointer)).prependToBlock0(prefix)
        }

        /**
         * Represents that the decoded value should be placed in stack variable [targetVar]. [base] is only
         * used to construct a [PointerDecodingOutput] via [allocate].
         */
        data class Scalar(val targetVar: TACSymbol.Var, override val base: TACSymbol.Var) : DecodingOutput {
            override fun write(o: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
                return CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = targetVar,
                        rhs = o.asSym()
                    )
                ), setOf(targetVar, o))
            }
        }

        /**
         * A [DecodingOutput] which *definitely* represents a location in memory, and thus
         * [SupportsPointerArithmetic] and [SupportsCopy].
         */
        data class PointerDecodingOutput(
            val memoryLocation: TACSymbol.Var,
            override val base: TACSymbol.Var
        ) : DecodingOutput, SupportsPointerArithmetic<PointerDecodingOutput>, SupportsCopy {
            override fun write(o: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
                return CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = memoryLocation,
                        value = o,
                        base = base
                    )
                ), setOf(o, memoryLocation, base))
            }

            override fun copyWithPointer(o: TACSymbol.Var): PointerDecodingOutput {
                return this.copy(memoryLocation = o)
            }

            override val baseMap: TACSymbol.Var
                get() = base

            override val pointer: TACSymbol
                get() = memoryLocation

        }
    }

    /**
     * Represents an internal argument to a function. [p] may be a scalar or a pointer value.
     * If it is a pointer value, [baseMap] is taken to be the bytemap into which [p] is a pointer.
     * No attempt is made to check that these accesses are valid.
     *
     * [length] is a temporary variable, which communicates the variable which holds
     * the length of an array to the code that is responsible for processing
     * the elements of that array.
     */
    private data class InternalArgument(
        val p: TACSymbol,
        override val baseMap: TACSymbol.Var,
        val length: TACSymbol.Var?
    ) : Readable, SupportsPointerArithmetic<InternalArgument>, SupportsCopy {
        override val readPointer: TACSymbol
            get() = p

        override fun copyWithPointer(o: TACSymbol.Var): InternalArgument {
            return this.copy(p = o)
        }

        override val pointer: TACSymbol
            get() = p
    }

    private fun interface LoopConditionGenerator {
        /**
         * Generate a jump command that enters the loop at [loopBodyId] or exits to [loopSuccessorId]
         */
        fun generate(loopBodyId: NBId, loopSuccessorId: NBId): TACCmd.Simple.JumpiCmd
    }

    /**
     * Helper code to generate a looping program. The root of the program is [loopSetup], which
     * jumps unconditionally to [loopHead]. [loopHead] is expected to contain the computation
     * of the loop condition; [loopBody] holds the loop body, and [loopSuccessor] is the loop successor.
     * NB that the looping is accomplished by appending a jump command to [loopBody] which
     * [mkJump] is responsible for generating the [vc.data.TACCmd.Simple.JumpiCmd] which
     * encodes the control-flow decision.
     *
     * NB that [vc.data.TACCmd.Simple.JumpiCmd] is created here because [loopSetup], [loopHead], and [loopSuccessor]
     * do not have [NBId] as [CommandWithRequiredDecls].
     */
    private fun generateLoopingProgram(
        loopSetup: CommandWithRequiredDecls<TACCmd.Simple>,
        loopHead: CommandWithRequiredDecls<TACCmd.Simple>,
        loopSuccessor: CommandWithRequiredDecls<TACCmd.Simple>,
        loopBody: CoreTACProgram,
        mkJump: LoopConditionGenerator
    ): CoreTACProgram {
        val loopHeadBlock = Allocator.getNBId()
        val setupBlock = Allocator.getNBId()
        val elseBlockId = Allocator.getNBId()

        val blockCode = mutableMapOf<NBId, List<TACCmd.Simple>>(
            setupBlock to (loopSetup.cmds + TACCmd.Simple.JumpCmd(loopHeadBlock)),
            loopHeadBlock to (loopHead.cmds + mkJump.generate(loopBody.getStartingBlock(), elseBlockId)),
            elseBlockId to loopSuccessor.cmds
        )

        val blockGraph = MutableBlockGraph(
            mapOf(
                setupBlock to treapSetOf(loopHeadBlock),
                loopHeadBlock to treapSetOf(elseBlockId, loopBody.entryBlockId),
                elseBlockId to treapSetOf()
            )
        )

        val combinedSymbolTable = listOf(
            loopSetup,
            loopHead,
            loopSuccessor
        ).fold(loopBody.symbolTable) { acc, crd ->
            acc.mergeDecls(crd.varDecls)
        }

        // merge in thenBlock
        val exits = loopBody.getEndingBlocks()
        for ((blk, code) in loopBody.code) {
            check(blk !in blockCode) {
                "Someone reused an id: $blk"
            }
            blockCode[blk] = code
        }
        for ((blk, succ) in loopBody.blockgraph) {
            blockGraph[blk] = succ
        }
        for (e in exits) {
            blockCode[e] = blockCode[e]!! + TACCmd.Simple.JumpCmd(loopHeadBlock)
            blockGraph[e] = treapSetOf(loopHeadBlock)
        }
        return CoreTACProgram(
            code = blockCode,
            procedures = loopBody.procedures,
            symbolTable = combinedSymbolTable,
            instrumentationTAC = loopBody.instrumentationTAC,
            blockgraph = blockGraph,
            entryBlock = setupBlock,
            check = true,
            name = "encoding loop"
        )

    }

    /**
     * Encodes the value [v] "in parallel" with the traversal of the ABI description of that value in [layout].
     * The result is a [ProgramWithNext].
     */
    private fun parallelEncode(
        v: InternalArgument,
        output: EncodingContext,
        layout: DataLayout<EVMTypeDescriptor.EVMValueType>
    ) : ProgramWithNext {
        return when(layout) {
            is DataLayout.DynamicPointer -> {
                /**
                 * Compute the relative offsets for this object (whose data is about to be encoded at [output]'s [EncodingContext.nextPointer])
                 * relative to the [EncodingContext.savedScope]. This function will throw on malformed [DataLayout] where
                 * a [DataLayout.DynamicPointer] is encountered before a [DataLayout.OpenScope]; but we expect that
                 * to never happen. Accordingly, this and other "crash on malformed inputs" are explicitly NOT guarded against.
                 */
                val toPrepend = ExprUnfolder.unfoldPlusOneCmd("!writePointer", TXF {
                    output.nextPointer!! sub output.savedScope!!
                }) {
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        base = output.baseMap,
                        loc = output.currPointer,
                        value = it.s
                    )
                }.merge(output.currPointer, output.baseMap, output.nextPointer!!, output.savedScope!!)

                /**
                 * Move the current pointer to [EncodingContext.nextPointer], and start encoding the "body" of [v]
                 * at that location, having just now encoded the *pointer* to that location.
                 */
                return parallelEncode(
                    v, output.copy(
                        staticOffset = null,
                        staticReservation = null,
                        nextPointer = null,
                        currPointer = output.nextPointer,
                        savedScope = null
                    ), layout.next
                ).prepend(toPrepend)
            }
            /**
             * Used to encode an array
             */
            is DataLayout.LengthTaggedTuple -> {
                check(output.nextPointer == null)
                output.reserve(EVM_WORD_SIZE) { out ->
                    v.read("length") { lenTemp ->
                        out.write(lenTemp) andThen v.add(wordSizeSym) { elemStart ->
                            /**
                             * [elemStart] is a [InternalArgument] object which points to the beginning of the
                             * array segment of the array pointed to by [v]. Encode the elements of [v] there.
                             */
                            parallelEncode(
                                /**
                                 * communicate the length of this array to the encoding process to come
                                 */
                                elemStart.copy(length = lenTemp),
                                /**
                                 * move the current pointer to the [EncodingContext.nextPointer], which
                                 * from the [EncodingContext.reserve] call must be 32 bytes after the current pointer.
                                 */
                                out.copy(
                                    currPointer = out.nextPointer!!,
                                    staticReservation = null,
                                    staticOffset = null,
                                    savedScope = null,
                                    nextPointer = null
                                ),
                                layout.elems
                            )
                        }
                    }
                }
            }
            is DataLayout.OpenScope -> {
                /**
                 * Simply save the current pointer as the location against which to resolve
                 * dynamic pointers, and keep going
                 */
                return parallelEncode(
                    v, output.copy(
                        savedScope = output.currPointer
                    ), layout.next
                )
            }
            is DataLayout.SequenceOf -> {
                check(output.nextPointer == null && v.length != null) {
                    "Encoding invariant broken: next pointer ${output.nextPointer} is already set, or we have no length: ${v.length}"
                }
                val isPrimitive = when(val se = layout.sequenceElements) {
                    is DataLayout.SequenceElement.Elements -> se.dataLayout is DataLayout.Terminal
                    is DataLayout.SequenceElement.PackedBytes1 -> true
                }
                // EZ case
                if(isPrimitive) {
                    // need adjust indicates whether we need to round the allocated block size up to the word boundary
                    val (elemSize, needAdjust) = if(layout.sequenceElements is DataLayout.SequenceElement.PackedBytes1) {
                        BigInteger.ONE to true
                    } else {
                        EVM_WORD_SIZE to false
                    }
                    /**
                     * Holds the raw block size, i.e., how many physical bytes make up the data being encoded
                     */
                    val copySize = "rawBlockSize".tmp(Tag.Bit256)
                    /**
                     * Block size holds the amount we are allocating (which might be the copySize rounded up)
                     */
                    val (blockSize, computeBlockSize) = CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = copySize,
                        rhs = TXF { v.length mul elemSize }
                    ), copySize, v.length).let { computeBlockSize ->
                        copySize to computeBlockSize
                    }.letIf(needAdjust) { (len, computeLen) ->
                        CodeGenUtils.wordAlignSymbol(len).mapSecond { alignment ->
                            computeLen andThen alignment
                        }
                    }
                    val tmp = TACKeyword.TMP(Tag.ByteMap, "!mcopy").withMeta(TACMeta.MCOPY_BUFFER)
                    val nxt = "nextPointer".tmp(Tag.Bit256)

                    /**
                     * Copy copySize from the source to the current pointer location,
                     * and set the next pointer to point to the end of the block (computed as blockSize).
                     *
                     * We use an intermediate buffer here as it is possible that we may want to use
                     * this for memory to memory encodings (instead of memory to temporary buffer as we have now)
                     * hence the defensiveness.
                     */
                    val copyProg = computeBlockSize andThen CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = nxt,
                            rhs = TXF { output.currPointer add blockSize }
                        ),
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = tmp,
                            length = copySize,
                            dstOffset = TACSymbol.Zero,
                            srcOffset = v.p,
                            srcBase = v.baseMap
                        ),
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = output.baseMap,
                            dstOffset = output.currPointer,
                            srcBase = tmp,
                            length = copySize,
                            srcOffset = TACSymbol.Zero
                        )
                    ), setOf(nxt, tmp, output.currPointer, copySize, output.baseMap, output.currPointer, blockSize, v.baseMap, v.p))
                    return ProgramWithNext(copyProg.toCore("copy array", Allocator.getNBId()), nxt)
                }
                val se = layout.sequenceElements
                check(se is DataLayout.SequenceElement.Elements)
                /**
                 * At this point, [v] should be pointing at the start of an arrays elements. We generate the
                 * following loop:
                 * ```
                 * srcElemInd = 0
                 * outputElem = currPointer
                 * nextIt = nextPointer
                 * while(ind < length) {
                 *    toEncode = v + ind * 32
                 *    currNext := parallelEncode(toEncode, outputPointer, currNext)
                 *    ind += 1
                 *    outputPointer += sizeOf(T)
                 * }
                 * ```
                 *
                 * where `T` is the element type of this array, and `sizeOf(T)` is its encoding size.
                 *
                 * In the above, we used [parallelEncode] as a function which returned a next pointer;
                 * it is meant to indicate the inlining of the program fragment returned by [parallelEncode].
                 * The variable included in the [ProgramWithNext] is then used to update `currNext`
                 */


                /**
                 * The loop condition, which counts from 0 to [v].[InternalArgument.length]; `ind` in the above snippet
                 */
                val srcElemInd = CounterVariable(
                    name = "inputElemIt",
                    initialValue = TACSymbol.Zero,
                    incrementAmount = TACSymbol.One,
                    bound = v.length
                )

                /**
                 * The output location, which starts at the current pointer (which points to the beginning of the
                 * encoded arrays) and which is incremented by the size of the element.
                 */
                val outputElem = LoopVariable(
                    name = "outputIt",
                    initialValue = output.currPointer,
                    incrementAmount = se.dataLayout.sizeAsEncodedMember().asTACSymbol()
                )

                /**
                 * The initial value of the next pointer during array encoding: set to `currPointer + sizeOf(T) * length`,
                 * aka pointing after the element data itself
                 *
                 * (Recall that dynamic elements are represented by a 32 byte pointer within the array itself; the data
                 * of that element is stored at the location indicated by said pointer.)
                 */
                val nextInit = "nextForArray".tmp(Tag.Bit256)

                /**
                 * Initialize nextInit to point after the data for the "immediate" array elements.
                 */
                val customLoopInit = ExprUnfolder.unfoldTo(TXF { output.currPointer add (v.length mul se.dataLayout.sizeAsEncodedMember()) }, nextInit)

                /**
                 * The variable holding the distinguished value of the next pointer during the loop; initialized
                 * to nextInit.
                 *
                 * If the loop elements are dynamic, then the encoding process of those elements will produce a new
                 * nextPointer, which is used to update this variable.
                 * If the loop elements are not dynamic, this variable isn't modified.
                 *
                 * Either way, once the loop exits, this variable (that is encapsulated by [CustomUpdate])
                 * holds the next pointer location.
                 */
                val nextIt = CustomUpdate(
                    "nextIt",
                    nextInit
                )

                return customLoopInit andThen generateLoop(listOf(srcElemInd, outputElem, nextIt)) {
                    /**
                     * Compute `toEncode` by adding `32 * srcElemInd` to the element start (aka [v]).
                     */
                    v.add(TXF { EVM_WORD_SIZE mul srcElemInd.variable }) { elemLoc ->
                        /**
                         * Read that value which may be a scalar or pointer.
                         */
                        elemLoc.read("elemValue") { elemValue ->
                            val elemDecodeContext = EncodingContext(
                                baseMap = output.baseMap,
                                staticOffset = BigInteger.ZERO,
                                staticReservation = se.dataLayout.sizeAsEncodedMember(),
                                nextPointer = nextIt.variable,
                                savedScope = output.savedScope,
                                currPointer = outputElem.variable
                            )
                            /**
                             * Encode that value into the [EncodingContext] pointing into the element slot.
                             */
                            parallelEncode(
                                InternalArgument(elemValue, v.baseMap, null),
                                elemDecodeContext,
                                layout = se.dataLayout
                            ).maybeUpdate(nextIt, se.dataLayout) // conditionally update `nextIt` if this encoding updates the nextPointer.
                        }
                    }
                }.copy(next = nextIt.variableUnsafe) // after this loop, nextIt holds the nextPointer location, so use that as the next pointer to return back.
            }
            is DataLayout.StaticRepeatedOf -> {
                output.reserve(layout.num * layout.elem.sizeAsEncodedMember()) { withReservation ->
                    /**
                     * A simpler version of the dynamic case above
                     */
                    val nxtIt = CustomUpdate(
                        "nextIter",
                        withReservation.nextPointer!!
                    )

                    val elemIt = CounterVariable(
                        name = "elemInd",
                        initialValue = TACSymbol.Zero,
                        incrementAmount = TACSymbol.One,
                        bound = layout.num.asTACSymbol()
                    )

                    val outputIt = LoopVariable(
                        name = "outputIt",
                        initialValue = withReservation.currPointer,
                        incrementAmount = layout.elem.sizeAsEncodedMember().asTACSymbol()
                    )

                    generateLoop(listOf(
                        nxtIt, elemIt, outputIt
                    )) {
                        v.add(TXF { elemIt.variable mul EVM_WORD_SIZE }, "elemPointer") { elemReader ->
                            elemReader.read("elemValue") { elemValue ->
                                parallelEncode(
                                    InternalArgument(
                                        p = elemValue,
                                        baseMap = v.baseMap,
                                        length = null
                                    ),
                                    EncodingContext(
                                        baseMap = output.baseMap,
                                        currPointer = outputIt.variable,
                                        savedScope = output.savedScope,
                                        nextPointer = nxtIt.variable,
                                        staticReservation = layout.elem.sizeAsEncodedMember(),
                                        staticOffset = BigInteger.ZERO
                                    ),
                                    layout.elem
                                ).maybeUpdate(nxtIt, layout.elem)
                            }
                        }
                    }.copy(next = nxtIt.variableUnsafe)
                }
            }
            is DataLayout.Terminal -> {
                /**
                 * We can't just write a primitive value into unreserved memory, *something*
                 * must be containing this primitive value (a struct, an array, the top-level
                 * argument tuple).
                 */
                check(output.nextPointer != null) {
                    "Next pointer not set at write of primitive"
                }
                val writeProgram = output.write(
                    v.p
                ).toCore("write primitive", Allocator.getNBId())
                return ProgramWithNext(
                    writeProgram, output.nextPointer
                )
            }
            is DataLayout.TupleOf -> {
                /**
                 * Encode the elements of this array sequentially.
                 */
                val dataSize = layout.elements.sumOf {
                    it.second.sizeAsEncodedMember()
                }
                output.reserve(dataSize) { saved ->
                    /**
                     * The offset in memory from the start of the struct
                     */
                    var fieldOffs = BigInteger.ZERO
                    /**
                     * The offset into the reserved buffer starting from the curr pointer.
                     */
                    var encodedOffs = BigInteger.ZERO

                    /**
                     * The variable holding where to encode the current item
                     */
                    var fieldIt = saved.currPointer

                    /**
                     * Ye olde next pointer. Each struct field "returns" the next pointer to
                     * pass through to the next elements encoding.
                     */
                    var nextIt = saved.nextPointer!!

                    /**
                     * The individual programs which encode each field of this struct
                     */
                    val progAccum = mutableListOf<CoreTACProgram>()

                    /**
                     * Code to prepend to the code that encodes each field; it contains the
                     * commands to update `fieldIt`.
                     */
                    var toPrependToNext: CommandWithRequiredDecls<TACCmd.Simple>? = null

                    for((str, i) in layout.elements) {
                        /**
                         * The state to encode this field: the next pointer is at `nextIt`,
                         * the current pointer, which is the *absolute* location in the buffer
                         * where we should place this fields data, and `encodedOffs`, which holds
                         * the *relative* offset within the struct.
                         */
                        val forEncoding = saved.copy(
                            currPointer = fieldIt,
                            staticOffset = encodedOffs,
                            nextPointer = nextIt
                        )

                        val encoded = v.add(fieldOffs.asTACSymbol(), "fieldPointer") { fieldReader ->
                            fieldReader.read("fieldValue") { fieldValue ->
                                /**
                                 * Encode the value at this offset
                                 */
                                parallelEncode(
                                    InternalArgument(fieldValue, v.baseMap, null),
                                    forEncoding,
                                    i
                                )
                            }
                        }.letIf(toPrependToNext != null) {
                            /**
                             * Prepend the code generated by the previous field if need be. This makes sure
                             * our fieldIt are set properly.
                             */
                            toPrependToNext!! andThen it
                        }

                        nextIt = encoded.next
                        progAccum.add(encoded.prog)
                        val nextStructField = "postEncode$str".tmp()
                        toPrependToNext = CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = nextStructField,
                                rhs = TXF { fieldIt add i.sizeAsEncodedMember() }
                            )
                        ), nextStructField, fieldIt)
                        fieldIt = nextStructField
                        fieldOffs += EVM_WORD_SIZE
                        encodedOffs += i.sizeAsEncodedMember()
                    }
                    val prog = progAccum.reduce(TACProgramCombiners::mergeCodes)
                    ProgramWithNext(prog, nextIt)
                }
            }
        }
    }

    private interface LoopVarPermit

    /**
     * Looping program infrastructure. This class represents a loop variable which
     * is *managed* by the [generateLoop] function but "declared" in its caller. When constructed
     * each instance corresponds to some "logical" variable; the infrastructure of [generateLoop] actually
     * creates the program variable which corresponds to this logical variable.
     */
    private sealed interface LoopStateVariable {
        /**
         * The value to which this loop variable should initialized
         */
        val initialValue: TACSymbol

        /**
         * The variable name set by the loop generator. It is generally only safe to access this
         * property within the body of the function called by [generateLoop], hence the [LoopVarPermit]
         * context variable.
         */
        context(LoopVarPermit)
        val variable: TACSymbol.Var

        /**
         * The name on which the actual loop variable name should be based.
         */
        val name: String

        /**
         * Used by [generateLoop] to "inject" the actual loop variable into this class.
         */
        fun inject(o: TACSymbol.Var)
    }

    /**
     * A variable which is incremented within the loop body by an amount
     * represented by [incrementAmount]
     */
    private sealed interface IncrementingVariable : LoopStateVariable {
        val incrementAmount: TACSymbol
    }

    /**
     * A standard loop variable, not the loop counter.
     */
    class LoopVariable(
        override val name: String,
        override val initialValue: TACSymbol,
        override val incrementAmount: TACSymbol,
    ) : IncrementingVariable {
        private lateinit var _variable: TACSymbol.Var

        context(LoopVarPermit)
        override val variable: TACSymbol.Var
            get() = _variable

        override fun inject(o: TACSymbol.Var) {
            _variable = o
        }
    }

    /**
     * An [IncrementingVariable] which is also the loop variable. [generateLoop] will effectively generate
     * `for(i = [initialValue]; i < [bound]; i += [incrementAmount]) { ... }`
     */
    class CounterVariable(
        override val name: String,
        override val initialValue: TACSymbol,
        override val incrementAmount: TACSymbol,
        val bound: TACSymbol,
    ) : IncrementingVariable {
        private lateinit var _variable: TACSymbol.Var

        context(LoopVarPermit)
        override val variable: TACSymbol.Var
            get() = _variable

        override fun inject(o: TACSymbol.Var) {
            _variable = o
        }
    }

    /**
     * A variable that uses some custom logic to update its value. Within the [generateLoop] callback,
     * implementations can set the new value of this variable via [setNewValue]. If such a new value `n`
     * is provided then the variable `v` represented by this class is updated at the end of the loop
     * body with `v = n`. To emphasize: [setNewValue] does not change the *identity* of this loop variable,
     * but rather this loop variable's *value*.
     */
    class CustomUpdate(
        override val name: String,
        override val initialValue: TACSymbol,
    ) : LoopStateVariable {
        private lateinit var _variable: TACSymbol.Var

        context(LoopVarPermit)
        override val variable: TACSymbol.Var
            get() = _variable

        override fun inject(o: TACSymbol.Var) {
            _variable = o
        }

        val variableUnsafe: TACSymbol.Var get() = _variable

        var newValue: TACSymbol? = null

        context(LoopVarPermit)
        fun setNewValue(o: TACSymbol) {
            require(newValue == null) {
                "Cannot set new value for $name twice"
            }
            newValue = o
        }
    }

    /**
     * Used to conditionally update the value of [o] after a loop body if the loop
     * body mutates the "next pointer".
     */
    context(LoopVarPermit)
    private fun ProgramWithNext.maybeUpdate(
        o: CustomUpdate,
        l: DataLayout<*>
    ) : ProgramWithNext {
        if(l is DataLayout.DynamicPointer) {
            o.setNewValue(this.next)
        }
        return this
    }

    private fun <O: WithProgram<CoreTACProgram, O>> generateLoop(
        l: List<LoopStateVariable>,
        loopBodyGen: context(LoopVarPermit)() -> O
    ) : O {

        val permit = object : LoopVarPermit {}
        fun LoopStateVariable.variableInner() = with(permit) { variable }

        val loopCond = "loopCondition".tmp(Tag.Bool)
        for(lv in l) {
            /**
             * Instantiate the loop variable names
             */
            lv.inject(lv.name.tmp(Tag.Bit256))
        }

        /**
         * There must be one of these, otherwise we can generate a loop at all.
         */
        val loopCounterVar = l.single {
            it is CounterVariable
        } as CounterVariable


        /**
         * Initialize all of the variables with the declared initial values.
         */
        val loopSetup = l.map { lv ->
            CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = lv.variableInner(),
                rhs = lv.initialValue
            )), setOf(lv.variableInner(), lv.initialValue))
        }.flatten()

        /**
         * Generate the loopHead, which holds the computation of the loop condition
         */
        val loopHead = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = loopCond,
                rhs = TXF { loopCounterVar.variableInner() lt loopCounterVar.bound }
            )
        ), setOf(loopCond, loopCounterVar.variableInner(), loopCounterVar.bound))

        /**
         * Generate the body, providing the permit so implementers can access the magic fields.
         */
        val loopBody = loopBodyGen(permit)

        /**
         * Generate the updates to the loop variables, which are appended to the loop body.
         */
        val loopTail = l.mapNotNull { lv ->
            when(lv) {
                is CustomUpdate -> {
                    if(lv.newValue == null) {
                        return@mapNotNull null
                    }
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = lv.variableInner(),
                            rhs = lv.newValue!!
                        )
                    ), setOf(lv.newValue!!, lv.variableInner()))
                }
                is IncrementingVariable -> {
                    val newVar = "${lv.name}Next".tmp(Tag.Bit256)
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = newVar,
                            rhs = TXF { lv.incrementAmount add lv.variableInner() }
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = lv.variableInner(),
                            rhs = newVar.asSym()
                        )
                    ), setOf(lv.incrementAmount, lv.variableInner(), newVar))
                }
            }
        }.flatten()

        val combinedLoopBody = loopBody andThen loopTail

        val elseBlock = CommandWithRequiredDecls(TACCmd.Simple.LabelCmd("loop done"))
        /**
         * Use map program here so that whatever next variable is returned by the loop body is used as
         * the next variable for this whole loop.
         */
        return combinedLoopBody.mapProgram { loopBodyProg ->
            generateLoopingProgram(
                loopSetup = loopSetup,
                loopHead = loopHead,
                loopSuccessor = elseBlock,
                loopBody = loopBodyProg
            ) { thenDst, elseDst ->
                TACCmd.Simple.JumpiCmd(cond = loopCond, dst = thenDst, elseDst = elseDst)
            }
        }
    }

    /**
     * Copy [length] logical elements (each of size [elemSize]) from the buffer/pointer pair [input] to the buffer/pointer
     * pair [output].
     */
    private fun generateCopy(
        input: SupportsCopy,
        output: SupportsCopy,
        length: TACSymbol.Var,
        elemSize: BigInteger
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val copyAmount = "copyAmount".tmp(Tag.Bit256)
        return CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = copyAmount,
                rhs = TXF { length mul elemSize }
            ),
            TACCmd.Simple.ByteLongCopy(
                length = copyAmount,

                srcBase = input.baseMap,
                srcOffset = input.pointer,

                dstBase = output.baseMap,
                dstOffset = output.pointer
            )
        ), setOf(length, output.baseMap, copyAmount, input.baseMap, input.pointer, output.pointer))
    }


    /**
     * The dual of [parallelEncode], decodes to [decoderOutput] from [decodingContext] in parallel with a walk of [layout];
     * which describes exactly how to do this decoding.
     *
     * This simply returns a [CoreTACProgram] which effects the decoding; there is no analogue for the next pointer.
     */
    private fun parallelDecode(
        decoderOutput: DecodingOutput,
        decodingContext: DecodingContext,
        layout: DataLayout<EVMTypeDescriptor.EVMValueType>
    ) : CoreTACProgram {
        when(layout) {
            is DataLayout.DynamicPointer -> {
                /**
                 * Simply follow the relative offset w.r.t. the [DecodingContext.savedScope]. NB that there
                 * are types which the ABI spec considers "static" that actually require an allocation. Thus,
                 * there is no pointer allocation here (that is pushed into sequence elems, tuple, and static array cases)
                 */
                return decodingContext.read("offset") { relOffs ->
                    val newPointer = "newCurr".tmp(Tag.Bit256)
                    ExprUnfolder.unfoldTo(TXF { relOffs add decodingContext.savedScope!! }, newPointer).merge(
                        newPointer, decodingContext.currPointer
                    ) andThen parallelDecode(decoderOutput, decodingContext.copy(
                        savedScope = null,
                        currPointer = newPointer
                    ), layout.next)
                }
            }
            is DataLayout.LengthTaggedTuple -> {
                /**
                 * We also don't allocate here; it turns out to be way easier to do the allocation closer to where
                 * the elements are decoded (the [DataLayout.SequenceElement] branch) vs threading that through. So,
                 * we instead read the current length out of the buffer, save it into the [DecodingContext],
                 * and recurse.
                 */
                return decodingContext.read("readLength") { len ->
                    decodingContext.add(wordSizeSym) { elemStart ->
                        parallelDecode(
                            decoderOutput,
                            elemStart.copy(length = len),
                            layout.elems
                        )
                    }
                }
            }
            is DataLayout.OpenScope -> {
                /**
                 * Simply save the current scope and recurse
                 */
                return parallelDecode(decoderOutput, decodingContext.copy(
                    savedScope = decodingContext.currPointer
                ), layout.next)
            }
            is DataLayout.SequenceOf -> {
                check(decodingContext.length != null){
                    "Invariant broken, hit DataLayout.SequenceOf without first traversing a length?"
                }
                when(val se = layout.sequenceElements) {
                    is DataLayout.SequenceElement.Elements -> {
                        /**
                         * Allocate a block of size 32 * (l * 32) where l is the length recorded in [DecodingContext].
                         * Conveniently (and by design) this is recognized as an array allocation by the allocation analysis.
                         */
                        val allocAmount = TACKeyword.TMP(Tag.Bit256, "blockSize")
                        val computeAllocAmount = ExprUnfolder.unfoldTo(TXF { (decodingContext.length mul EVM_WORD_SIZE) add EVM_WORD_SIZE }, allocAmount)
                        return computeAllocAmount andThen decoderOutput.allocate(allocAmount) { alloc ->
                            /**
                             * Write the length to the start of the newly allocated array block, and then bump by 32
                             * to get to the element start
                             */
                            alloc.write(decodingContext.length) andThen alloc.add(wordSizeSym) add@{ elemStart ->
                                /**
                                 * if this is an array of primitives, initialize via long copy.
                                 */
                                if(se.dataLayout is DataLayout.Terminal) {
                                    return@add generateCopy(
                                        input = decodingContext,
                                        output = elemStart,
                                        length = decodingContext.length,
                                        elemSize = EVM_WORD_SIZE
                                    ).toCore("copy", Allocator.getNBId())
                                }
                                /**
                                 * Otherwise, generate a decoding loop with the following form:
                                 * ```
                                 * decodedElemIt = decodingContext.currPointer
                                 * for(elemIt = 0; elemIt < decodingContext.length; elemIt++) {
                                 *    toInit = elemIt * 32 + elemStart
                                 *    parallelDecode(toInit, decodedElemIt)
                                 *    decodedElemIt += sizeOf(T)
                                 * }
                                 * ```
                                 *
                                 * Where `sizeOf(T)` and the interpretation of `parallelDecode` are as in
                                 * the description in [parallelEncode]
                                 */
                                val elemIt = CounterVariable(
                                    name = "elemIt",
                                    initialValue = TACSymbol.Zero,
                                    incrementAmount = TACSymbol.One,
                                    bound = decodingContext.length
                                )
                                val decodedIt = LoopVariable(
                                    name = "decodedElemIt",
                                    initialValue = decodingContext.currPointer,
                                    incrementAmount = se.dataLayout.sizeAsEncodedMember().asTACSymbol()
                                )
                                generateLoop(listOf(elemIt, decodedIt)) {
                                    elemStart.add(TXF { EVM_WORD_SIZE mul elemIt.variable }) { atElem ->
                                        parallelDecode(
                                            atElem, decodingContext.copy(
                                                currPointer = decodedIt.variable
                                            ), se.dataLayout
                                        )
                                    }
                                }

                            }

                        }
                    }
                    is DataLayout.SequenceElement.PackedBytes1 -> {
                        /**
                         * Allocate a bytes array, using the round up to nearest 32 pattern recognized
                         * by the alloc analysis.
                         */
                        val allocAmmount = TACKeyword.TMP(Tag.Bit256, "blockSize")
                        val allocAmountComputation = ExprUnfolder.unfoldTo(TXF {
                            EVM_WORD_SIZE add ((decodingContext.length add 31) bwAnd lower32BitMask)
                        }, allocAmmount).merge(decodingContext.length)

                        return allocAmountComputation andThen decoderOutput.allocate(allocAmmount) { alloc ->
                            /**
                             * Write the length, bump by 32...
                             */
                            alloc.write(decodingContext.length) andThen alloc.add(wordSizeSym) { elemOutput ->
                                /**
                                 * ... and then directly copy
                                 */
                                generateCopy(
                                    input = decodingContext,
                                    output = elemOutput,
                                    length = decodingContext.length,
                                    elemSize = BigInteger.ONE
                                ) andThen elemOutput.add(decodingContext.length) { atEnd ->
                                    atEnd.write(TACSymbol.Zero).toCore("write", Allocator.getNBId())
                                }
                            }
                        }
                    }
                }
            }
            is DataLayout.StaticRepeatedOf -> {
                /**
                 * Again, generate a decoding loop, using the same pattern as above, but without a length field
                 * and with a static size.
                 */
                val elemIt = CounterVariable(
                    "elemIt",
                    TACSymbol.Zero,
                    TACSymbol.One,
                    layout.num.asTACSymbol()
                )
                val toDecode = LoopVariable(
                    "decodePos",
                    decodingContext.currPointer,
                    layout.elem.sizeAsEncodedMember().asTACSymbol()
                )
                return decoderOutput.allocate((layout.num * EVM_WORD_SIZE).asTACSymbol()) { staticStruct ->
                    generateLoop(listOf(
                        elemIt,
                        toDecode
                    )) {
                        staticStruct.add(TXF { elemIt.variable mul wordSizeSym}) {elemPointer ->
                            parallelDecode(
                                elemPointer,
                                decodingContext.copy(
                                    currPointer = toDecode.variable
                                ),
                                layout.elem
                            )
                        }
                    }
                }
            }
            is DataLayout.Terminal -> {
                return decodingContext.read("readPrimitive") { readValue ->
                    decoderOutput.write(readValue).toCore("write primitive", Allocator.getNBId())
                }
            }
            is DataLayout.TupleOf -> {
                /**
                 * Allocate a struct with numfields, and then initialize in order.
                 */
                val numFields = layout.elements.size
                val structSize = numFields * EVM_WORD_SIZE_INT
                return decoderOutput.allocate(structSize.asTACSymbol()) { alloc ->
                    // records the offset within the buffer at which the next field should be
                    // decoded. incremented by the size of the field on each iteration
                    var decodingElemIt = decodingContext.currPointer
                    // fieldIt records the offset within the struct of the next field to initialize
                    // that is, it strides by 32 bytes
                    var fieldIt = alloc.memoryLocation
                    val programs = mutableListOf<CoreTACProgram>()
                    for((_, o) in layout.elements) {
                        val fieldEnc = parallelDecode(
                            DecodingOutput.PointerDecodingOutput(
                                memoryLocation = fieldIt,
                                base = alloc.base
                            ),
                            decodingContext.copy(
                                currPointer = decodingElemIt,
                                length = null
                            ),
                            o
                        )
                        val nextToDecode = "nextToDecode".tmp(Tag.Bit256)
                        val nextField = "nextField".tmp(Tag.Bit256)
                        /**
                         * Bump the two iterators by their respective amounts
                         */
                        programs.add(fieldEnc andThen CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = nextToDecode,
                                rhs = TXF { decodingElemIt add o.sizeAsEncodedMember() }
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = nextField,
                                rhs = TXF { fieldIt add EVM_WORD_SIZE }
                            )
                        ), nextToDecode, nextField, decodingElemIt))
                        decodingElemIt = nextToDecode
                        fieldIt = nextField
                    }
                    // merge all decodings together
                    programs.reduce(TACProgramCombiners::mergeCodes)
                }
            }
        }
    }

    /**
     * The result of encoding: the program to effect the program [encodingProgram],
     * the [buffer] into which the encoding was performed, and its [bufferSize], as well
     * as a recording of static offsets within said buffer to immediate arguments (in [offsetToSlot])
     */
    private data class EncodingResult(
        val encodingProgram: CoreTACProgram,
        val buffer: TACSymbol.Var,
        val bufferSize: TACSymbol.Var,
        val offsetToSlot: Map<BigInteger, TACSymbol>
    )

    /**
     * Records all the relevant information about what internal function is being summarized and why; used for error messages.
     */
    private data class SummaryContext(
        val hostCode: String,
        val where: CmdPointer,
        val whichMethod: QualifiedMethodSignature
    ) {
        val asString: String = "${whichMethod.prettyPrintFullyQualifiedName()} in $hostCode @ $where"

        fun err(msg: String): Nothing = throw CertoraInternalException(
            CertoraInternalErrorType.SUMMARY_INLINING,
            "While summarizing $asString: $msg"
        )
    }

    /**
     * Replaces internal functions in [code] that are summarized with [spec.cvlast.SpecCallSummary.Reroute]
     * with the code to perform that rerouting, sans the actual [CallSummary] (see the discussion on [RerouteToMaterialize]).
     */
    fun reroute(code: CoreTACProgram, cvlQuery: CVL): CoreTACProgram {
        val rerouter = object : InternalSummarizer<CVL.SummarySignature.Internal, SpecCallSummary.Reroute>() {

            /**
             * In some summarization context [ctxt], generate code to decode the values returned by the reroute of
             * [summarizedSig] which are encoded in [callResultBase]. The decodings should be placed into
             * [rets], and the allocations (if any) to hold these values should written to [outBase].
             */
            private fun handleDecoding(
                ctxt: SummaryContext,
                summarizedSig: QualifiedMethodSignature,
                rets: List<InternalFuncRet>,
                outBase: TACSymbol.Var,
                callResultBase: TACSymbol.Var
            ) : CoreTACProgram {
                if(summarizedSig.resType.size != rets.size) {
                    ctxt.err("Arity mismatch on returns $summarizedSig vs $rets")
                }
                val savedScope = "savedScope".tmp()
                var progIt = CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = savedScope,
                        rhs = TACSymbol.Zero
                    )
                ), savedScope).toCore("setup", Allocator.getNBId())

                var offs = BigInteger.ZERO

                /**
                 * Decode each return value. We have special logic to handle
                 * storage pointers, as those do not have [DataLayout] (and will throw if you try to access them).
                 */
                for((r, ty) in rets.zip(summarizedSig.resType)) {
                    val eTy = ty as EVMTypeDescriptor
                    if(ty is EVMTypeDescriptor.EVMReferenceType && ty.location == EVMLocationSpecifier.storage) {
                        progIt = progIt andThen CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = r.s,
                            loc = offs.asTACSymbol(),
                            base = callResultBase
                        )))
                        offs += EVM_WORD_SIZE
                        continue
                    }
                    val currPointer = "tupleStart".tmp()
                    val layout = eTy.getDataLayout().resultOrThrow {
                        CertoraInternalException(
                            CertoraInternalErrorType.SUMMARY_INLINING,
                            "While ${ctxt.asString} could not get layout for return $offs: $it"
                        )
                    }
                    progIt = progIt andThen CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = currPointer,
                        rhs = offs.asTACExpr
                    ), currPointer) andThen parallelDecode(
                        DecodingOutput.Scalar(
                            base = outBase,
                            targetVar = r.s
                        ),
                        layout = layout,
                        decodingContext = DecodingContext(
                            baseMap = callResultBase,
                            currPointer = currPointer,
                            savedScope = savedScope,
                            length = null
                        )
                    )
                    offs += layout.sizeAsEncodedMember()
                }
                return progIt
            }

            /**
             * Generates the encoding of some [args] to the summarized function [summarizedSig] to a function
             * [targetSighash]. The arguments to encode are passed [argOrdinals], which holds
             * a list of the argument positions to pass to the reroute target [targetSighash]. Thus, `argOrdinals = [1, 1]`
             * will generate a calldata buffer that passes two copies of the second argument of [summarizedSig].
             */
            private fun handleEncoding(
                ctxt: SummaryContext,
                argOrdinals: List<Int>,
                summarizedSig: QualifiedMethodSignature,
                args: List<InternalFuncArg>,
                targetSighash: BigInteger
            ) : EncodingResult {
                /**
                 * Skip making generators for arguments we aren't passing.
                 */
                val knownArguments = argOrdinals.toSet()

                val generators = mutableMapOf<Int, ArgumentEncoder>()

                var argTyIt = 0
                var argSymIt = 0

                val offsetToStorageSlot = mutableMapOf<BigInteger, TACSymbol>()

                /**
                 * Because calldata arguments are passed with two values (ugh) figuring out which symbols in [args]
                 * correspond to which *logical* argument of [summarizedSig] is rather annoying. Hence this loop:
                 * we go through the argument types and consume symbols from [args] as necessary.
                 */
                while(argTyIt < summarizedSig.paramTypes.size && argSymIt < args.size) {
                    val argOrd = argTyIt

                    val ty = summarizedSig.paramTypes[argTyIt++]
                    val argSym = args[argSymIt++]

                    if(argOrd !in knownArguments) {
                        /**
                         * Skip, but make sure to skip the elem symbol that corresponds to this calldata array length
                         */
                        if(argSym.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS) {
                            argSymIt++
                        }
                        continue
                    }
                    /**
                     * Special handling of storage arguments; the pointer value is passed directly, no [DataLayout] guided
                     * encoding (indeed, [getDataLayout] will throw on storage located types).
                     */
                    if(ty is EVMTypeDescriptor.EVMReferenceType && ty.location == EVMLocationSpecifier.storage) {
                        if(argSym.sort != InternalArgSort.SCALAR) {
                            ctxt.err("Have non-scalar sort for what we think is a storage pointer: $argSym")
                        }
                        generators[argOrd] = ArgumentEncoder { baseOffset, o, slots ->
                            val toRet = o.write(argSym.s).toCore("writeStorage", Allocator.getNBId())
                            slots[baseOffset] = argSym.s
                            ProgramWithNext(toRet, o.nextPointer!!)
                        }
                        continue
                    } else if(ty is EVMTypeDescriptor.EVMReferenceType && ty.location == EVMLocationSpecifier.calldata) {
                        check(argSym.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS || argSym.sort == InternalArgSort.CALLDATA_POINTER) {
                            "Disagreement between signatures: have ${argSym.sort} for arg $argOrd but type is $ty"
                        }
                        if(argSym.sort == InternalArgSort.CALLDATA_ARRAY_ELEMS) {
                            // these are stubbed out for now
                            val lenSym = args[argSymIt++]
                            check(ty is EVMTypeDescriptor.EVMValueArrayConverter && lenSym.sort == InternalArgSort.CALLDATA_ARRAY_LENGTH) {
                                "signature thinks we have an array for $argOrd, but signature gives type $ty"
                            }
                            generators[argOrd] = ArgumentEncoder { _, _, _ ->
                                ctxt.err("Can't actually pass through calldata yet for $argOrd")
                            }
                        } else {
                            generators[argOrd] = ArgumentEncoder { _, _, _ ->
                                ctxt.err("Can't actually pass through calldata yet for $argOrd")
                            }
                        }
                        continue
                    }
                    check(ty is EVMTypeDescriptor) {
                        "Have unknown type $ty for $argOrd in ${ctxt.asString}"
                    }
                    /**
                     * otherwise create a "standard" generator
                     */
                    generators[argOrd] = ArgumentEncoder { _: BigInteger, o: EncodingContext, _ ->
                        val layout = ty.getDataLayout().resultOrThrow {
                            CertoraInternalException(
                                CertoraInternalErrorType.SUMMARY_INLINING,
                                "While ${ctxt.asString}: Couldn't get layout for type $ty of $argOrd with errors ${it.joinToString(", ")}"
                            )
                        }
                        val internal = InternalArgument(
                            baseMap = TACKeyword.MEMORY.toVar(),
                            p = argSym.s,
                            length = null
                        )
                        parallelEncode(
                            internal, o, layout
                        )
                    }
                }
                /**
                 * Did we not fully consume either list? Something went wrong with our matching
                 */
                if(argTyIt != summarizedSig.paramTypes.size || argSymIt != args.size) {
                    ctxt.err("Argument symbol/type arity mismatches: $argTyIt with ${summarizedSig.paramTypes} vs $argSymIt with $args")
                }

                /**
                 * The initial, saved scope, against which "top-level" dynamic references are resolved.
                 */
                val argTupleStart = "argTupleStart".tmp(Tag.Bit256)

                /**
                 * the initial value of the next pointer (right after the head encodings of the arguments)
                 */
                var nextPointer = "argTupleEnd".tmp(Tag.Bit256)

                val tempBuffer = "encodedBuffer".tmp(Tag.ByteMap)
                val baseTupleSize = argOrdinals.sumOf {
                    (summarizedSig.paramTypes[it] as EVMTypeDescriptor).sizeAsEncodedMember()
                }

                val initialNext = DEFAULT_SIGHASH_SIZE + baseTupleSize
                val initializationCode = CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = argTupleStart,
                        rhs = DEFAULT_SIGHASH_SIZE.asTACExpr
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = nextPointer,
                        initialNext.asTACExpr
                    ),
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = TACSymbol.Zero,
                        base = tempBuffer,
                        value = targetSighash.shiftLeft(EVM_WORD_SIZE_INT - (DEFAULT_SIGHASH_SIZE_INT * EVM_BYTE_SIZE_INT)).asTACSymbol()
                    )
                ), nextPointer, tempBuffer, argTupleStart)

                val encodingPrograms = mutableListOf(initializationCode.toCore(
                    "sighashSetup", Allocator.getNBId()
                ))

                var argOffset = DEFAULT_SIGHASH_SIZE
                var encodeArgNum = 0
                for(i in argOrdinals) {
                    /**
                     * encode argument ordinal `i`
                     */
                    val gen = generators[i] ?: ctxt.err("Failed to create generator for argument $i")
                    val argSize = (summarizedSig.paramTypes[i] as EVMTypeDescriptor).sizeAsEncodedMember()
                    val currArg = encodeArgNum++
                    val encodeStart = "encodeForArg$currArg".tmp(Tag.Bit256)
                    val setup = CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = encodeStart,
                        rhs = argOffset.asTACExpr
                    ), encodeStart)

                    val encodingContext = EncodingContext(
                        savedScope = argTupleStart,
                        currPointer = encodeStart,
                        staticOffset = BigInteger.ZERO,
                        staticReservation = argSize,
                        baseMap = tempBuffer,
                        nextPointer = nextPointer
                    )

                    val res = setup andThen gen.encode(
                        argOffset,
                        encodingContext,
                        offsetToStorageSlot
                    )
                    encodingPrograms.add(res.prog)
                    nextPointer = res.next
                    argOffset += argSize
                }

                return EncodingResult(
                    encodingProgram = encodingPrograms.reduce(TACProgramCombiners::mergeCodes),
                    buffer = tempBuffer,
                    bufferSize = nextPointer,
                    offsetToSlot = offsetToStorageSlot
                )
            }

            /**
             * Generates the instrumentation which should replace the [InternalCallSummary].
             *
             * [summary] is the key used to find the [spec.cvlast.SpecCallSummary.Reroute] summary [r]: this
             * is passed through for reporting purposes.
             *
             * [functionStart] is the "start" of the function; this is the location of the [InternalCallSummary]
             * or the [analysis.ip.InternalFuncStartAnnotation] (although this latter code path *should* be impossible).
             *
             * [args] are the arguments to the summarized function and [rets] are the return values; these should correspond
             * to the argument/return types of [summarizedSig], the signature of the function being summarized.
             */
            private fun generateInstrumentation(
                summary: CVL.SummarySignature.Internal,
                callSiteSrc: TACMetaInfo?,
                functionStart: CmdPointer,
                args: List<InternalFuncArg>,
                rets: List<InternalFuncRet>,
                summarizedSig: QualifiedMethodSignature,
                r: SpecCallSummary.Reroute
            ) : CoreTACProgram {
                                /**
                 * We don't want this internal call to suddenly mutate the global environment. Thus, we backup the current
                 * "external call related" state variables, make the external call, and then restore said state variables.
                 */
                val toBackup = listOf(
                    "rcBackup".tmp() to TACKeyword.RETURNCODE.toVar(),
                    "retDataBackup".tmp(Tag.ByteMap) to TACKeyword.RETURNDATA.toVar(),
                    "retSizeBackup".tmp() to TACKeyword.RETURN_SIZE.toVar()
                )

                /**
                 * Used to check if the callee reverted
                 */
                val checkRc = "rc".tmp(Tag.Bool)

                /**
                 * Save copies of the current state
                 */
                val backupCallState = toBackup.map { (backup, orig) ->
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = backup,
                                rhs = orig.asSym()
                            )
                        ), orig, backup
                    )
                }.flatten()

                /**
                 * It is *crucial* we havoc these values. Because these are actually mutated
                 * by the (eventual) external call we make, analyses that run *before* this inlining
                 * should forget any information about return sizes or return codes.
                 */
                val havocCallState = CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                        lhs = TACKeyword.RETURNCODE.toVar(),
                    ),
                    TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                        lhs = TACKeyword.RETURN_SIZE.toVar()
                    )
                ), EthereumVariables.rc, EthereumVariables.returnsize)

                val summaryContext = SummaryContext(
                    hostCode = code.name,
                    whichMethod = summarizedSig,
                    where = functionStart
                )
                val enc = handleEncoding(
                    summarizedSig = summarizedSig,
                    args = args,
                    targetSighash = r.sighash,
                    ctxt = summaryContext,
                    argOrdinals = r.forwardArgs
                )

                val callAndThenCheckRc = CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AnnotationCmd(
                        RerouteToMaterialize.META_KEY,
                        RerouteToMaterialize(
                            sighash = r.sighash,
                            hostContract = r.target.host,
                            inputBase = enc.buffer,
                            inputSize = enc.bufferSize,
                            offsetToSlot = enc.offsetToSlot
                        )
                    ),
                    // see if the callee reverted or not.
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(rhs = TXF { TACKeyword.RETURNCODE.toVar() eq TACSymbol.Zero }, lhs = checkRc)
                ), checkRc)

                /**
                 * Backs up the state variables in `toBackup`, and then "call" the reroute target. As discussed
                 * elsewhere, the [CallSummary] (or its inlining) happen considerably later in the pipeline,
                 * so we put in a marker [RerouteToMaterialize].
                 */
                val backupAndCall = backupCallState andThen havocCallState andThen callAndThenCheckRc

                /**
                 * encoding of arguments, followed by the backup and call.
                 */
                val encodeAndCall = enc.encodingProgram andThen backupAndCall

                val endAnnotation = CommandWithRequiredDecls(TACCmd.Simple.AnnotationCmd(
                    SummaryStack.END_INTERNAL_SUMMARY,
                    SummaryStack.SummaryEnd.Internal(
                        methodSignature = summarizedSig,
                        rets = rets
                    )
                ), rets.mapToSet { it.s })

                val revertPath = run {
                    /**
                     * If the callee reverted, simply bubble that failure up. NB we don't bother restoring the state variables,
                     * we're about to throw this stack frame away anyway
                     */
                    val ret = "mem".tmp()
                    val revertPath = CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = ret,
                            rhs = TACKeyword.MEM64.toVar()
                        ),
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = TACKeyword.MEMORY.toVar(),
                            dstOffset = ret,
                            srcOffset = TACSymbol.Zero,
                            srcBase = EthereumVariables.returndata,
                            length = EthereumVariables.returnsize
                        )
                    ), ret, EthereumVariables.returnsize, EthereumVariables.returndata, TACKeyword.MEMORY.toVar(), TACKeyword.MEM64.toVar()) andThen
                        EthereumVariables.setLastReverted(lastReverted = true) andThen
                        endAnnotation andThen
                        TACCmd.Simple.RevertCmd(
                            o1 = ret,
                            o2 = EthereumVariables.returnsize,
                            base = TACKeyword.MEMORY.toVar()
                        )
                    revertPath.toCore("revertPath", Allocator.getNBId())
                }

                /**
                 * Holds a copy of the returndata and returnsize set by the callee. Because we restore
                 * returndata/returnsize, we operate on these temp variables instead of the global state
                 * directly.
                 *
                 * In *principle* we could operate on returndata/returnsize and THEN restore the state variables,
                 * but it seems valuable for the decoding code to be as explicit as possible about what's going on.
                 */
                val returnDataStore = "summarizedReturnData".tmp(Tag.ByteMap)
                val returnDataSize = "summarizedReturnDataSize".tmp()

                /**
                 * Immediately copy the current values of returndata and returndatasize (those
                 * returned by our reroute target) to our temporary variables...
                 *
                 * NB: we don't bother copying the rc; we've already checked it
                 */
                val copyReturnState = CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = returnDataStore,
                            rhs = TACKeyword.RETURNDATA.toVar(),
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = returnDataSize,
                            rhs = TACKeyword.RETURN_SIZE.toVar()
                        )
                    ), returnDataSize, returnDataStore
                )

                /**
                 * ... then restore our backed up state ...
                 */
                val restoreSavedState = toBackup.map { (back, orig) ->
                    CommandWithRequiredDecls(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = orig,
                            rhs = back.asSym()
                        ), back, orig
                    )
                }.flatten()

                /**
                 * ... and then decode from our copy of the returndata from the rerouted call; this is the "happy path"
                 * for our rerouted call.
                 */
                val happyPath = copyReturnState andThen restoreSavedState andThen handleDecoding(
                    summarizedSig = summarizedSig,
                    rets = rets,
                    callResultBase = returnDataStore,
                    outBase = TACKeyword.MEMORY.toVar(),
                    ctxt = summaryContext
                ) andThen endAnnotation

                val startAnnotation = CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AnnotationCmd(
                            SummaryStack.START_INTERNAL_SUMMARY,
                            d = SummaryStack.SummaryStart.Internal(
                                methodSignature = summarizedSig,
                                callSiteSrc = callSiteSrc,
                                appliedSummary = Summarization.AppliedSummary.MethodsBlock(
                                    specCallSumm = r,
                                    summarizedMethod = summary
                                ),
                                callResolutionTableInfo = CallResolutionTableSummaryInfo.DefaultInfo(
                                    applicationReason = SummaryApplicationReason.Spec.reasonFor(
                                        summ = r,
                                        methodSignature = null
                                    )
                                )
                            )
                        )
                    )
                )

                return startAnnotation andThen mergeIfCodes(
                    condCode = encodeAndCall,
                    thenCode = happyPath,
                    elseCode = revertPath,
                    jumpiCmd = TACCmd.Simple.JumpiCmd(
                        cond = checkRc,
                        dst = revertPath.getStartingBlock(),
                        elseDst = happyPath.getStartingBlock()
                    )
                )
            }


            override fun handleExplicitSummary(
                where: CmdPointer,
                explicit: InternalCallSummary,
                selectedSummary: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.Reroute>,
                enclosingProgram: CoreTACProgram
            ): SummaryApplicator {
                val summary = generateInstrumentation(
                    args = explicit.internalArgs,
                    rets = explicit.internalExits,
                    r = selectedSummary.selectedSummary,
                    summarizedSig = explicit.signature,
                    functionStart = where,
                    callSiteSrc = explicit.callSiteSrc,
                    summary = selectedSummary.summaryKey
                )
                return { patch ->
                    patch.replaceCommand(where, summary)
                }
            }

            override fun generateSummary(
                internalFunctionStartInfo: InternalFunctionStartInfo,
                selectedSummary: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.Reroute>,
                functionStart: CmdPointer,
                rets: FunctionReturnInformation,
                intermediateCode: CoreTACProgram
            ): CoreTACProgram {
                return generateInstrumentation(
                    args = internalFunctionStartInfo.args,
                    rets = rets.rets,
                    summarizedSig = internalFunctionStartInfo.methodSignature,
                    r = selectedSummary.selectedSummary,
                    functionStart = functionStart,
                    summary = selectedSummary.summaryKey,
                    callSiteSrc = internalFunctionStartInfo.callSiteSrc
                )
            }

            override fun selectSummary(sig: QualifiedMethodSignature): SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.Reroute>? {
                return cvlQuery.internal.entries.filter { (k, which) ->
                    which is SpecCallSummary.Reroute && k.matches(sig)
                }.resolveCandidates()?.let { (k, s) ->
                    val r = s as SpecCallSummary.Reroute
                    SummarySelection(k, r)
                }
            }

            override fun alreadyHandled(
                summarySelection: SummarySelection<CVL.SummarySignature.Internal, SpecCallSummary.Reroute>,
                where: LTACCmd
            ): Boolean {
                return false
            }

            fun instrument() : CoreTACProgram {
                return this.summarizeInternalFunctionLoop(
                    code, false
                ).first
            }
        }
        return rerouter.instrument()
    }

    /**
     * Materializes the [RerouteToMaterialize] annotations into proper [CallSummary]
     */
    fun materializeReroutes(c: CoreTACProgram, scene: IScene) : CoreTACProgram {
        return c.parallelLtacStream().mapNotNull {
            it.annotationView(RerouteToMaterialize.META_KEY)
        }.map {
            val targetContract = scene.getContract(it.annotation.hostContract) as ContractClass
            val summ = CallSummary(
                toVar = targetContract.addressSym,
                inBase = it.annotation.inputBase,
                inOffset = TACSymbol.Zero,
                inSize = it.annotation.inputSize,
                outBase = it.annotation.inputBase,
                outSize =  TACSymbol.Zero,
                callType = TACCallType.DELEGATE,
                callTarget = setOf(CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(
                    targetContract.instanceId
                )),
                sigResolution = setOf(it.annotation.sighash),
                outOffset = TACSymbol.Zero,
                symbolicSigResolution = null,
                cannotBeInlined = null,
                gasVar = TACSymbol.Zero,
                valueVar = TACSymbol.Zero,
                origCallcore = null,
                summaryId = Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES),
                callConvention = CallConvention(
                    rawOut = CallOutput(
                        base = it.annotation.inputBase,
                        offset = TACSymbol.Zero,
                        size = TACSymbol.Zero
                    ),
                    input = CallInput(
                        size = it.annotation.inputSize.asSym(),
                        baseVar = it.annotation.inputBase.asSym(),
                        offset = TACExpr.zeroExpr,
                        inputSizeLowerBound = null,
                        encodedArguments = null,
                        rangeToDecomposedArg = it.annotation.offsetToSlot.entries.associate { (k, v) ->
                            val scratchRange = ScratchByteRange(k, k + 31.toBigInteger())
                            scratchRange to when(v) {
                                is TACSymbol.Var -> DecomposedCallInputArg.Variable(
                                    v = v,
                                    contractReference = null,
                                    scratchRange = scratchRange
                                )
                                is TACSymbol.Const -> DecomposedCallInputArg.Constant(
                                    scratchRange = scratchRange,
                                    contractReference = null,
                                    c = v
                                )
                            }
                        },
                        simplifiedOffset = TACExpr.zeroExpr
                    )
                )
            )
            it.ptr to summ
        }.patchForEach(c, check = true) { (where, summ) ->
            replaceCommand(where, listOf(TACCmd.Simple.SummaryCmd(summ, MetaMap())))
            addVarDecl(summ.toVar as TACSymbol.Var)
        }
    }
}
