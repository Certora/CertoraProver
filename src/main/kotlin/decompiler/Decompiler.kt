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
package decompiler

import algorithms.mapTransitiveClosure
import analysis.EthereumVariables.getStorageInternal
import analysis.EthereumVariables.getTransientStorageInternal
import analysis.EthereumVariables.memory
import analysis.ip.JUMP_SYM
import analysis.ip.isInternalAnnotationConstant
import analysis.ip.isSourceFinderConstant
import bridge.ContractInstanceInSDC
import bridge.EVMExternalMethodInfo
import bridge.Method
import com.certora.collect.*
import compiler.SourceContext
import config.Config
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import datastructures.*
import datastructures.stdcollections.*
import diagnostics.GraphProfiler
import disassembler.*
import disassembler.EVMInstruction.*
import log.ArtifactManagerFactory
import log.*
import scene.source.Sighash
import statistics.ElapsedTimeStats
import statistics.toTimeTag
import tac.*
import tac.TACBasicMeta.IS_IMMUTABLE
import utils.*
import utils.ModZm.Companion.asBigInteger
import vc.data.*
import verifier.ContractUtils.recordSingleTransformationStats
import java.math.BigInteger


private val logger = Logger(LoggerTypes.DECOMPILER)
private val loggerTimes = Logger(LoggerTypes.TIMES)

private typealias EVMStack = PersistentStack<Decompiler.EVMValue>

class Decompiler private constructor(
    private val bytecode: DisassembledEVMBytecode,
    private val contractName: String,
    private val address: BigInteger,
    private val methodName: String,
    private val sourceContext: SourceContext,
) {
    data class DecompiledContract(
        val source: ContractInstanceInSDC,
        val bytecode: DisassembledEVMBytecode,
        val methods: Map<Method, EVMTACProgram>,
        val fallback: EVMTACProgram,
        val wholeProgram: EVMTACProgram?
    ) : java.io.Serializable

    companion object {
        /**
         * attempts to guess what sighashes are used in the disassembled bytecode.
         * extremely heuristic and assumes a very simple dispatching pattern
         */
        fun extractPotentialSighashes(bytecode: DisassembledEVMBytecode): Iterable<Sighash> {
            val instructions = bytecode.disassembledCode.values.sortedBy { it.pc }
            val instructionsWithBytes4 = instructions.mapNotNull { evmCommand ->
                evmCommand.pc `to?` (evmCommand.inst as? PUSH)?.value?.takeIf { v ->
                    v >= BigInteger.ZERO && v < BigInteger.TWO.pow(32)
                }
            }

            val instructionsWithBytes4ComparisonAndJumps = instructionsWithBytes4.filter { (pc, _) ->
                val followUps = instructions.filter { it.pc > pc }.takeIf { it.size >= 3 }?.take(3)
                    ?: return@filter false // not enough instructions
                // next after push of 4 bytes is the comparison
                followUps.get(0).inst.opcode.let { op -> op == EVMInstruction.EQ.opcode || op == EVMInstruction.LT.opcode || op == EVMInstruction.GT.opcode }
                    // then the pushed target
                    && followUps.get(1).inst is EVMInstruction.PUSH
                    // then the jumpi
                    && followUps.get(2).inst.opcode == EVMInstruction.JUMPI.opcode
            }
            // to avoid false detections of 0, the jump between PCs cannot be greater than say 15 (10 is actually also likely to be good enough)
            return instructionsWithBytes4ComparisonAndJumps.zipWithNext().filter { (first, second) -> first.first + 15 >= second.first }.flatMap { listOf(it.first,it.second) }
                .mapToSet { it.second.toString(16) }
        }

        // Our model of the EVM stack is that it grows downward, with the first value in slot 1024.  Slots 1024 through
        // 1 are normal EVM stack values.  Slot 0 is a synthetic stack slot used as a temporary variable for the SWAP
        // instructions.
        internal const val MAX_EVM_STACK_POS = 1024
        private const val SWAP_STACK_POS = 0
        private val SWAP_VAR = TACSymbol.Var.stackVar(SWAP_STACK_POS)

        fun decompileEVM(
            bytecode: DisassembledEVMBytecode,
            instance: ContractInstanceInSDC
        ): DecompiledContract {

            logger.debug { "EVM Assembly:"}
            for ((pc, cmd) in bytecode.disassembledCode) {
                logger.debug { "$pc: ${cmd.inst.opcode.toString(16).padStart(2, '0')} ${cmd.inst}" }
            }

            val methodsForDispatchAnalysis = instance.getOrdinaryMethods().map { method ->
                object : DispatchMethod {
                    override val abiName: String =
                        if (instance.hasCompilerData()) {
                            EVMExternalMethodInfo.fromMethodAndContract(method, instance).toString()
                        } else {
                            method.name
                        }

                    override val sighash: Sighash? =
                        method.sighash

                    override val method: Method = method
                }
            }
            val dispatch = DispatchAnalysis(bytecode, methodsForDispatchAnalysis)

            return DecompiledContract(
                source = instance,
                bytecode = bytecode,
                methods = dispatch.methodPaths.entries.associate { (method, path) ->
                    method.method to
                        getEVMTACProgram(
                            bytecode,
                            instance.name,
                            instance.address,
                            "${instance.name}${OUTPUT_NAME_DELIMITER}${method.abiName}",
                            instance.srcContext,
                            path
                        )
                },
                fallback = getEVMTACProgram(
                    bytecode,
                    instance.name,
                    instance.address,
                    "${instance.name}${OUTPUT_NAME_DELIMITER}fallback",
                    instance.srcContext,
                    dispatch.fallbackPath
                ),
                wholeProgram = runIf(Config.EnableWholeContractProxyInlining.get()) {
                    getEVMTACProgram(
                        bytecode,
                        instance.name,
                        instance.address,
                        "${instance.name}${OUTPUT_NAME_DELIMITER}whole",
                        instance.srcContext,
                        dispatch = null
                    )
                }
            )
        }

        fun decompileEVMConstructor(
            bytecode: DisassembledEVMBytecode,
            contractName: String,
            address: BigInteger,
            sourceContext: SourceContext
        ): EVMTACProgram {
            return getEVMTACProgram(bytecode, contractName, address, contractName, sourceContext, dispatch = null)
        }


        private fun getEVMTACProgram(
            bytecode: DisassembledEVMBytecode,
            contractName: String,
            address: BigInteger,
            methodName: String,
            sourceContext: SourceContext,
            dispatch: DispatchAnalysis.Path?
        ): EVMTACProgram {
            logger.info { "Decompiling $methodName" }
            logger.debug { "Dispatch path: $dispatch" }
            if (bytecode.disassembledCode.isEmpty()) {
                logger.warn { "Byte list for contract $contractName is empty. Possibly need to run with --libraries" }
                return EVMTACProgram(
                    mapOf(Pair(StartBlock, listOf())),
                    BlockGraph(StartBlock to treapSetOf()),
                    methodName,
                    TACSymbolTable.empty()
                )
            }

            return applyJimple(bytecode, contractName, address, methodName, sourceContext, dispatch)
        }

        private fun applyJimple(
            bytecode: DisassembledEVMBytecode,
            contractName: String,
            address: BigInteger,
            methodName: String,
            sourceContext: SourceContext,
            dispatch: DispatchAnalysis.Path?
        ): EVMTACProgram {
            val jimpleTimeRecorder = ElapsedTimeStats().startMeasuringTimeOf(ReportTypes.JIMPLE.toString().toTimeTag())
            loggerTimes.info { "Jimple start: $contractName::$methodName" }

            val jimpleStart = System.currentTimeMillis()
            val jimpleResult = Decompiler(bytecode, contractName, address, methodName, sourceContext).jimple(dispatch)
            val jimpleEnd = System.currentTimeMillis()

            loggerTimes.info { "Jimple end in ${(jimpleEnd - jimpleStart) / 1000}s" }
            jimpleTimeRecorder.endMeasuringTimeOf(ReportTypes.JIMPLE.toString().toTimeTag())
            recordSingleTransformationStats(jimpleTimeRecorder, contractName)
            logger.info { "After jimple: got ${jimpleResult.code.size} nodes" }

            for ((pred, succs) in jimpleResult.blockgraph.entries.sortedBy { it.key }) {
                logger.info { "$pred: ${succs.sorted()}" }
            }

            for ((block, code) in jimpleResult.code.entries.sortedBy { it.key }) {
                logger.info { "$block" }
                for (cmd in code) {
                    logger.info { "  $cmd" }
                }
            }

            jimpleResult.logStats("Jimpled", logger)
            try {
                ArtifactManagerFactory().dumpCodeArtifacts(jimpleResult, ReportTypes.JIMPLE, DumpTime.POST_TRANSFORM)
            } catch (e: Exception) {
                logger.error(e, "Failed to output jimple report")
            }
            return jimpleResult
        }
    }

    private val jumpDestPCs: Set<BigInteger>
    private val syntheticCodeStartPC: EVMPC
    private val jumpThunkPC: EVMPC?

    private val stackMapping = StackMapping(logger, MAX_EVM_STACK_POS + 1, { TACSymbol.Var.stackVar(it) })

    private val diagnosticSources = mutableMapOf<EVMPC, String>()

    // Get source info for a given command
    private fun EVMPC.diagnosticSource() = diagnosticSources.getOrPut(this) {
        TACMetaInfo(this, code[this]!!.meta, address, sourceContext).getSourceDetails()?.let {
            "${it.fileName}:${it.lineNumber} | Block $this"
        } ?: "Block $this"
    }

    /**
        The user's code, plus some synthetic code we emit to deal with internal function pointers.
     */
    private val code: Map<EVMPC, EVMCommand> = ArrayHashMap<EVMPC, EVMCommand>().also { map ->

        map.putAll(bytecode.disassembledCode)
        jumpDestPCs = bytecode.jumpDestPCs

        // Place our synthetic code after the last user instruction
        logger.trace { "Emitting synthetic helper EVM code" }
        val (lastUserPC, lastUserCmd) = map.entries.maxByOrNull { it.key } ?: error("No EVM code")
        var pc = lastUserPC + lastUserCmd.inst.bytecodeSize

        fun emit(inst: EVMInstructionInfo) {
            map[pc] = EVMCommand(
                pc,
                inst,
                EVMMetaInfo(-1, 0, 0, null, inst.opcode, null)
            ).also {
                logger.trace { "Emitting $pc: ${it.inst}" }
            }
            pc += inst.bytecodeSize
        }

        // Start with an INVALID instruction, in case the user code falls through to the synthetic code
        syntheticCodeStartPC = pc
        emit(INVALID)

        // Generate the jump thunk, which is a "switch" across all possible jump targets
        if (Config.JumpThunkDepthLimit.get() > 0) {
            logger.trace { "Emitting jump thunk at EVM PC $pc" }
            jumpThunkPC = pc
            for (jumpDest in jumpDestPCs) {
                // if top of stack != jumpDest, try next jumpDest
                emit(DUP.forNum(1))
                emit(PUSH.forValue(jumpDest))
                emit(EQ)
                emit(ISZERO)
                val next = pc + 33 /*push*/ + 1 /*jumpi*/ + 1 /*pop*/ + 33 /*push*/ + 1 /*jump*/
                emit(PUSH.forValue(next.toBigInteger()))
                emit(JUMPI)

                // Top of stack == jumpDest, so jump to it.  We need to push jumpDest explicitly, so that we can interpret
                // it as a constant.
                emit(POP)
                emit(PUSH.forValue(jumpDest))
                emit(JUMP)

                check(pc == next) // Make sure our jump offset was correct
            }
            // If the top of the stack is not a valid jump target, revert.
            emit(PUSH.forValue(BigInteger.ZERO))
            emit(PUSH.forValue(BigInteger.ZERO))
            emit(REVERT)
        } else {
            jumpThunkPC = null
        }
    }

    /**
    A "stack frame" is the stack values pushed by a particular block, starting at a specific stack position.
    We use this to distinguish values pushed by the same instruction, but at different nesting levels, for, e.g.,
    recursion detection.
    */
    internal data class BlockStackFrame(val block: EVMPC, val stackPos: Int)
    /**
        Identifies the source of a value on the stack.  This is used to determine which values must be considered when
        duplicating stacks, and for inferring boolean values from conditional jumps.
     */
    internal sealed interface EVMValueSource {
        val stackFrame: BlockStackFrame?
        val blockInstance: BlockInstance?

        /**
            The instruction that pushed a value, and the "frame" in which it was executing.
         */
        data class PushPoint(
            val pc: EVMPC,
            override val stackFrame: BlockStackFrame,
            override val blockInstance: BlockInstance // just a tag for diagnostics; not used for equality
        ): EVMValueSource {
            override fun toString() = "($pc#${stackFrame.block}/${stackFrame.stackPos})"
            override fun hashCode() = hash { it + pc + stackFrame }
            override fun equals(other: Any?) = when {
                other === this -> true
                other !is PushPoint -> false
                other.pc != this.pc -> false
                other.stackFrame != this.stackFrame -> false
                // Ignore blockInstance - it's just for diagnostics, and BlockInstance comparisons are expensive
                else -> true
            }
        }

        /**
            Indicates that a value was simply obtained from the stack.  We convert from PushPoint to StackPosition to
            deduplicate stacks.
         */
        data class StackPosition(val pos: Int) : EVMValueSource {
            override fun toString() = "(@$pos)"
            override val stackFrame get() = null
            override val blockInstance get() = null
        }
    }

    /**
        An abstract EVM stack value.
     */
    internal sealed class EVMValue {
        abstract val immediateSource: EVMValueSource
        open val sources: TreapSet<EVMValueSource> get() = treapSetOf(immediateSource)

        abstract fun toBigInteger(): BigInteger?
        abstract fun toBoolean(): Boolean?
        abstract fun toInferredBool(): InferredBool?

        /**
            Given a known boolean value, possibly get the value of `this`.
         */
        abstract fun inferAbstractBool(knownValue: EVMValue, knownValueIsTrue: Boolean): InferredBool?
    }

    /** A known specific constant integer value */
    private data class EVMInt(val value: BigInteger, override val immediateSource: EVMValueSource) : EVMValue() {
        override fun toString() = "$value$immediateSource"
        override fun toBigInteger() = value
        override fun toBoolean() = value != BigInteger.ZERO
        override fun toInferredBool() = InferredBool(value != BigInteger.ZERO, immediateSource)
        override fun inferAbstractBool(knownValue: EVMValue, knownValueIsTrue: Boolean) = toInferredBool()
    }

    /** Abstract boolean value inferred from conditional branching */
    internal data class InferredBool(val value: Boolean, override val immediateSource: EVMValueSource) : EVMValue() {
        override fun toString() = "$value$immediateSource"
        override fun toBigInteger(): BigInteger? = null
        override fun toBoolean() = value
        override fun toInferredBool() = this
        override fun inferAbstractBool(knownValue: EVMValue, knownValueIsTrue: Boolean) = this
    }

    /** Result of ISZERO */
    private data class IsZeroResult private constructor(
        /**
            The source of the value that this IsZero is testing.  If this is part of a chain of IsZero operations, this
            is the source of the value the whole chain tests.
         */
        val inferenceSource: EVMValueSource,
        /**
            We might know the actual value here!
         */
        val knownValue: Boolean?,
        /**
            Is this a logical NOT operation over the value from [inferenceSource]?  If this is part of a chain of IsZero
            ops, then this will toggle for each operation in that chain.  I.e., this will be true for !x, false for !!x,
            true for !!!x, etc.
         */
        val polarity: Boolean,
        /**
            The source of *this* IsZero operation's result.  I.e., where this object is pushed on the stack.
         */
        override val immediateSource: EVMValueSource
    ) : EVMValue() {
        constructor(operand: EVMValue, immediateSource: EVMValueSource) : this(
            inferenceSource = when (operand) {
                is IsZeroResult -> operand.inferenceSource
                else -> operand.immediateSource
            },
            knownValue = operand.toBoolean()?.let { !it },
            polarity = when (operand) {
                is IsZeroResult -> !operand.polarity
                else -> true
            },
            immediateSource = immediateSource
        )

        override fun toString() = "!!$inferenceSource$immediateSource"
        override val sources get() = treapSetOf(immediateSource, inferenceSource)
        override fun toBigInteger(): BigInteger? = null
        override fun toBoolean(): Boolean? = knownValue
        override fun toInferredBool() = null

        override fun inferAbstractBool(knownValue: EVMValue, knownValueIsTrue: Boolean) = when {
            // If the known value is this value, just return it
            knownValue.immediateSource == this.immediateSource -> InferredBool(knownValueIsTrue, immediateSource)

            // If the known value is the value we're testing, then apply the IsZero operation according to our polarity.
            // I.e., if this is !x, return !knownValueIsTrue; if this is !!x, return knownValueIsTrue, etc.
            knownValue !is IsZeroResult -> when {
                knownValue.immediateSource == this.inferenceSource ->
                    InferredBool(knownValueIsTrue xor this.polarity, immediateSource)
                else -> null
            }

            // If the known value is another IsZeroResult in this chain of operations, then we first infer the value of
            // the value being tested by that chain, and compute the result of this particular IsZero.
            else -> when {
                knownValue.inferenceSource == this.inferenceSource ->
                    InferredBool(knownValueIsTrue xor knownValue.polarity xor this.polarity, immediateSource)
                else -> null
            }
        }
    }

    /** An unknown value */
    private data class Unknown(override val immediateSource: EVMValueSource) : EVMValue() {
        override fun toString() = "*$immediateSource"
        override fun toBigInteger(): BigInteger? = null
        override fun toBoolean(): Boolean? = null
        override fun toInferredBool() = null

        override fun inferAbstractBool(knownValue: EVMValue, knownValueIsTrue: Boolean) = when {
            // If the known value is this value, just return it
            knownValue.immediateSource == this.immediateSource -> InferredBool(knownValueIsTrue, immediateSource)

            // If the known value is an IsZero result, and this is the value being tested, then reverse the IsZero
            // operation.  I.e., if knownValue is !x, and this is x, then return !knownValueIsTrue; if knownValue is
            // !!x, return knownValueIsTrue; etc.
            knownValue is IsZeroResult -> when {
                knownValue.inferenceSource == this.immediateSource ->
                    InferredBool(knownValueIsTrue xor knownValue.polarity, immediateSource)
                else -> null
            }
            else -> null
        }
    }

    private fun EVMValue.isFunctionFinderConstant() = this.toBigInteger()?.isInternalAnnotationConstant() == true
    private fun EVMValue.isSourceFinderConstant() = this.toBigInteger()?.isSourceFinderConstant() == true

    /**
        We define "recursion" as a block having multiple "frames" on the stack.
     */
    private fun EVMStack.recursionCount(block: EVMPC): Int {
        val frames = mutableSetOf<BlockStackFrame>()
        forEachElement {
            it.immediateSource.stackFrame?.takeIf { it.block == block }?.let { frames += it }
        }
        return frames.size
    }

    private fun EVMStack.diagnosticStackTrace() =
        asSequence()
        .mapNotNull { it.immediateSource.stackFrame?.block }
        .skipAdjacentDuplicates()
        .joinToString("\n") { it.diagnosticSource() }


    private fun <T> iterateVisiting(start: T, work: (T) -> Iterable<T>) {
        val visited = ArrayHashSet<T>()
        val queue = ArrayDeque<T>()
        queue.add(start)
        visited.add(start)
        queue.consume {
            val next = work(it)
            next.forEach {
                if (visited.add(it)) {
                    queue.add(it)
                }
            }
        }
    }

    private sealed class InterpretedBlockInfo {
        data class Success(
            val end: EVMPC,
            val stackOut: EVMStack,
            val fallthrough: EVMPC? = null,
            val jumpTarget: EVMValue? = null,
            val jumpCondition: EVMValue? = null
        ) : InterpretedBlockInfo()

        sealed class Error : InterpretedBlockInfo() {
            abstract val errorPC: EVMPC
        }

        data class StackOverflow(override val errorPC: EVMPC) : Error()
        data class StackUnderflow(override val errorPC: EVMPC) : Error()
        data class RecursionLimitReached(override val errorPC: EVMPC) : Error()
        data class StackSizeLimitReached(override val errorPC: EVMPC) : Error()
        data class IllegalJumpTarget(override val errorPC: EVMPC, val target: EVMValue) : Error()
        data class JumpThunkLimitReached(override val errorPC: EVMPC) : Error()
        data class NoCode(override val errorPC: EVMPC) : Error()
    }

    private class InterpreterInstructionContext(
        val pc: EVMPC,
        val cmd: EVMCommand,
        val stackBefore: EVMStack,
        val stackAfter: EVMStack
    )

    /**
        Interprets to the end of the basic block.  Calls [handler] for each instruction.
     */
    private fun interpretBlock(
        block: BlockInstance,
        handler: InterpreterInstructionContext.() -> Unit = { }
    ): InterpretedBlockInfo {
        val stack = block.stackIn.builder()
        val frame = BlockStackFrame(block.pc, stack.size)
        var pc = block.pc

        logger.trace { ">>> ${pc.diagnosticSource()}" }
        with(stack) {
            while (true) {
                val cmd = code[pc] ?: return InterpretedBlockInfo.NoCode(pc)
                val inst = cmd.inst
                val nextPC = pc + inst.bytecodeSize
                val stackBefore = stack.build()

                val pushPoint = EVMValueSource.PushPoint(pc, frame, block)
                fun constant(value: BigInteger) = EVMInt(value, pushPoint)
                fun unknown() = Unknown(pushPoint)

                fun success(
                    fallthrough: Boolean = false,
                    jumpTarget: EVMValue? = null,
                    jumpCondition: EVMValue? = null
                ) = InterpretedBlockInfo.Success(
                    end = pc,
                    stackOut = stack.build(),
                    fallthrough = nextPC.takeIf { fallthrough },
                    jumpTarget = jumpTarget,
                    jumpCondition = jumpCondition,
                )

                try {
                    logger.trace { " -> $stack" }
                    logger.trace { "$pc: $inst" }

                    when {
                        stack.size - inst.popCount < 0 ->
                            return InterpretedBlockInfo.StackUnderflow(pc)
                        stack.size - inst.popCount + inst.pushCount > MAX_EVM_STACK_POS ->
                            return InterpretedBlockInfo.StackOverflow(pc)
                        1 + MAX_EVM_STACK_POS - stack.size < Config.RecursionLimitHeuristic.get() ->
                            return InterpretedBlockInfo.StackSizeLimitReached(pc)
                    }

                    class BinOpContext(val av: EVMValue, val a: BigInteger, val bv: EVMValue, val b: BigInteger)
                    fun binOp(op: BinOpContext.() -> EVMValue) {
                        val av = pop()
                        val bv = pop()
                        val a = av.toBigInteger()
                        val b = bv.toBigInteger()
                        push(
                            when {
                                a == null || b == null -> unknown()
                                else -> BinOpContext(av, a, bv, b).op()
                            }
                        )
                    }

                    when (inst) {
                        is PUSH -> push(constant(inst.value))
                        is DUP -> push(peek(inst.dupNum).last())
                        is SWAP -> {
                            val values = pop(inst.swapNum + 1).toMutableList()
                            values.swap(0, values.lastIndex)
                            pushAll(values.asReversed())
                        }
                        ADD -> binOp { constant(EVMOps.add(a, b)) }
                        SUB -> binOp { constant(EVMOps.sub(a, b)) }
                        MUL -> binOp { constant(EVMOps.mul(a, b)) }
                        DIV -> binOp { if (b == BigInteger.ONE) { av } else { constant(EVMOps.div(a, b)) } }
                        EXP -> binOp { constant(EVMOps.exp(a, b)) }
                        SHL -> binOp { constant(EVMOps.shl(b, a)) }
                        SHR -> binOp { constant(EVMOps.shr(b, a)) }
                        NOT -> {
                            val v = pop().toBigInteger()
                            push(
                                when {
                                    v == null -> unknown()
                                    else -> constant(EVMOps.bwNot(v))
                                }
                            )
                        }
                        AND -> binOp {
                            val result = a and b
                            // Solidity generates code that masks off its jump destinations.  We need to preserve the
                            // source in those cases.  But if the operands are the same, let's not favor one over the
                            // other.
                            when {
                                a == b -> constant(result)
                                result == a -> av
                                result == b -> bv
                                else -> constant(result)
                            }
                        }
                        OR -> binOp {
                            val result = a or b
                            // see AND
                            when {
                                a == b -> constant(result)
                                result == a -> av
                                result == b -> bv
                                else -> constant(result)
                            }
                        }
                        EQ -> binOp { constant((a == b).asBigInteger) }
                        ISZERO -> push(IsZeroResult(pop(), pushPoint))
                        JUMPI -> return success(jumpTarget = pop(), jumpCondition = pop(), fallthrough = true)
                        JUMP -> return success(jumpTarget = pop())
                        RETURN, REVERT, SELFDESTRUCT, STOP, INVALID -> return success()
                        else -> {
                            pop(inst.popCount)
                            repeat(inst.pushCount) { push(unknown()) }
                        }
                    }
                } finally {
                    InterpreterInstructionContext(pc, cmd, stackBefore, stack.build()).handler()
                }

                if (code[nextPC]?.inst == JUMPDEST) {
                    return success(fallthrough = true)
                }

                pc = nextPC
            }
        }
    }

    /**
        A block instance is a copy of a basic block with fixed stack positions and known, unique, future jump targets.
        Stack positions are determined by the height of the stack on input.  Jump targets are determined by target
        values on the stack.  We also track whether this block is the target of a conditional jump; this is used when
        merging blocks, for reasons.
     */
    internal data class BlockInstance(
        val pc: EVMPC,
        val stackIn: EVMStack,
        /** Inferred top stack value.  We need this for constructing the BlockIdentifier. */
        val topOfStackValue: Boolean?,
        val jumpThunkDepth: Int
    ) {
        // We hash these objects a lot; cache the hash code.
        private val hash = hash { it + pc + stackIn + topOfStackValue + jumpThunkDepth }
        override fun hashCode() = hash

        companion object {
            val Start = BlockInstance(0, persistentStackOf(), null, 0)
        }
    }

    /**
        A dependency from one or more block instances to another, indicating the the [from] blocks require the [to]
        block.

        For example, say function f() calls g():

                f() {
                    block F0:
                        ...
                    block F1:
                        PUSH(F2)
                        PUSH(G0)
                        JUMP
                    block F2:
                        ...
                }
                g() {
                    block G0:
                        ...
                    block Gx: JUMP (returning to PC pushed by F1)
                }

        Block F1 determines that both G0 and F2 may execute, so it "requires" or "depends on" those blocks.

        We use this, instead of successors, to assign blame for code size analysis, as the "succeeds" relation doesn't
        capture *why* the successor block must exist in the output.

        The [synthetic] property distinguishes dependencies that are injected by our analysis, rather than expressed in
        the user's code.  Synthethic dependencies should not be related back to the user's code e.g. in call traces.
     */
    private data class BlockDependency(val from: TreapSet<BlockInstance>, val to: BlockInstance, val synthetic: Boolean)

    /**
        The result of interpreting a block instance (success/failure + successors)
     */
    private sealed class BlockInstanceResult {
        abstract val successors: List<BlockInstance>
        abstract val usedSources: TreapSet<EVMValueSource>

        data class Success(
            val jumpTarget: BlockDependency? = null,
            val fallthrough: BlockDependency? = null,
            override val usedSources: TreapSet<EVMValueSource>,
        ) : BlockInstanceResult() {
            override val successors get() = listOfNotNull(
                fallthrough?.to,
                jumpTarget?.to
            )
        }

        data class Error(val error: InterpretedBlockInfo.Error) : BlockInstanceResult() {
            override val successors get() = listOf<BlockInstance>()
            override val usedSources: TreapSet<EVMValueSource> get() = treapSetOf()
        }
    }

    /**
        Represents a traversal to a given block, with the interpreter context in in effect for that block.
        Currently this just tracks the `DispatchAnalysis` results to be used when interpreting the block.  This allows
        us to stop using dispatch analysis after we've traversed beyond the region of the CFG that was successfully
        analyzed.
     */
    private data class GraphTraversal(
        val block: BlockInstance,
        val dispatch: DispatchAnalysis.Path?
    )

    /**
        Computes all block instances for the given code.  [scrubStack] is applied to the stack on output from each
        block, allowing unneeded values to be removed, to reduce the number of block instances.  See the caller for more
        info.
     */
    private fun computeBlockInstances(
        dispatch: DispatchAnalysis.Path?,
        deduplicate: (BlockInstance) -> BlockInstance
    ): Map<BlockInstance, BlockInstanceResult> {
        // Find all possible jump target values
        val jumpDestPCs = code.asSequence().filter { it.value.inst == JUMPDEST }.mapToSet { it.key.toBigInteger() }

        val blocks = LinkedArrayHashMap<BlockInstance, BlockInstanceResult>()

        // Start at the beginning, with the given dispatch path info.  Node the intentional shadowing of `dispatch` in
        // the lambda; when we traverse beyond part of the CFG analyzed by DispatchAnalysis, we discard the path info.
        iterateVisiting(GraphTraversal(BlockInstance.Start, dispatch)) { (block, dispatch) ->
            yieldToTestTimeout()

            val successors = mutableListOf<GraphTraversal>()
            blocks[block] = run blockResult@{
                logger.trace { "Interpreting $block" }
                val info = interpretBlock(block)

                if (info !is InterpretedBlockInfo.Success) {
                    logger.error {
                        "Error interpreting block ${block.pc.diagnosticSource()}: $info\nBlocks on stack:\n${block.stackIn.diagnosticStackTrace()}"
                    }
                    return@blockResult BlockInstanceResult.Error(info as InterpretedBlockInfo.Error)
                }

                if (info.stackOut.recursionCount(block.pc) > Config.RecursionEntryLimit.get()) {
                    logger.warn {
                        "Recursion limit reached at ${block.pc.diagnosticSource()}.\nBlocks on stack:\n${info.stackOut.diagnosticStackTrace()}"
                    }
                    return@blockResult BlockInstanceResult.Error(
                        InterpretedBlockInfo.RecursionLimitReached(block.pc)
                    )
                }

                // Get the result of the dispatch analysis in case we need it to resolve the jump target/condition
                val dispatchResult = dispatch?.path?.get(block.pc)
                if (dispatchResult != null) {
                    logger.trace { "dispatchPath[${block.pc}]: $dispatchResult" }
                }

                // Will this jump target be synthetic (not exactly what the original code asked for)?
                var syntheticJumpTarget: Boolean = false

                val tgt = info.jumpTarget?.toBigInteger()
                    ?: dispatchResult?.jumpTarget.also { syntheticJumpTarget = true }
                check(
                    tgt == null
                    || dispatchResult?.jumpTarget == null
                    || tgt == dispatchResult.jumpTarget
                ) {
                    "Block ${block.pc} jump target analysis mismatch: $tgt != ${dispatchResult?.jumpTarget}"
                }

                val jumpConditionValue = info.jumpCondition?.toBoolean()
                    ?: dispatchResult?.conditionalJumpTaken
                check(
                    jumpConditionValue == null
                    || dispatchResult?.conditionalJumpTaken == null
                    || jumpConditionValue == dispatchResult.conditionalJumpTaken
                ) {
                    "Block ${block.pc} jump condition analysis mismatch: $jumpConditionValue != ${dispatchResult?.conditionalJumpTaken}"
                }

                val jumpTargetValue = when {
                    jumpConditionValue == false -> null
                    info.jumpTarget == null -> null
                    else -> when (tgt) {
                        // Unknown target:
                        null -> when {
                            // Have we reached the jump thunk limit?
                            block.jumpThunkDepth >= Config.JumpThunkDepthLimit.get() ->
                                return@blockResult BlockInstanceResult.Error(
                                    InterpretedBlockInfo.JumpThunkLimitReached(info.end)
                                )
                            // We can use the jump thunk
                            else -> jumpThunkPC.also { syntheticJumpTarget = true }
                        }
                        // Known target, on the jumpdest list:
                        in jumpDestPCs -> tgt.safeAsInt()
                        else -> when {
                            // Jumping from synthetic code, to synthetic code:
                            block.pc > syntheticCodeStartPC && tgt > syntheticCodeStartPC.toBigInteger() -> tgt.safeAsInt()
                            // Known, but invalid, jump target:
                            else -> return@blockResult BlockInstanceResult.Error(
                                InterpretedBlockInfo.IllegalJumpTarget(info.end, info.jumpTarget)
                            )
                        }
                    }
                }

                val fallthrough = info.fallthrough?.takeIf { jumpConditionValue != true }

                fun successor(succPC: EVMPC, jumpTaken: Boolean): GraphTraversal {
                    /*
                        On output, we retain any jump targets from the stack, along with the topmost boolean value. We
                        erase all other stack values. We don't actually know which stack values are used as jump
                        targets, so we conservatively preserve all possible jump target values.  Later we'll remove the
                        ones that aren't actually used as jump targets.

                        For conditional jumps, if we take the branch then we also try to infer the boolean value at the
                        top of the stack.  This is needed to handle Solidity try/catch patterns, which generate loops
                        that unwind out of EH clauses.  These loops alter the stack size, producing a new BlockInstance
                        for each iteration.  We need to statically terminate these loops in order to avoid arbitrary
                        block duplication for EH clauses that do not actually exist.

                        If we *don't* take a conditional branch, then we don't try to infer the topmost boolean value.
                        We have other analyses that depend on this for reasons unknown to me. --Eric

                        Note that it just happens that we only need the topmost boolean value, in the code patterns
                        actually generated by current supported compilers.  We may need to change this later.
                     */
                    val isConditionalJumpTaken = jumpTaken && info.fallthrough != null
                    val inferredTopOfStack = info.stackOut.firstOrNull()?.let { topVal ->
                        topVal.toInferredBool() ?: when {
                            isConditionalJumpTaken -> topVal.inferAbstractBool(info.jumpCondition!!, jumpTaken)
                            else -> null
                        }
                    }
                    val succStackIn = info.stackOut.updateValues { i, it ->
                        when {
                            it.toBigInteger() in jumpDestPCs -> it
                            it.isFunctionFinderConstant() -> it
                            it.isSourceFinderConstant() -> it
                            i == info.stackOut.size -> inferredTopOfStack
                            else -> null
                        } ?: Unknown(EVMValueSource.StackPosition(i))
                    }
                    return GraphTraversal(
                        block = deduplicate(
                            BlockInstance(
                                pc = succPC,
                                stackIn = succStackIn,
                                topOfStackValue = inferredTopOfStack?.toBoolean().takeIf { isConditionalJumpTaken },
                                jumpThunkDepth = when(jumpTargetValue) {
                                    null -> block.jumpThunkDepth // Not jumping
                                    jumpThunkPC -> block.jumpThunkDepth + 1 // Jumping to jump thunk
                                    else -> block.jumpThunkDepth // Jumping to ordinary code
                                }
                            )
                        ),
                        dispatch = dispatch?.takeIf { (block.pc to succPC) !in dispatch.endEdges }
                    )
                }

                BlockInstanceResult.Success(
                    jumpTarget = jumpTargetValue?.let { successor(it, true) }?.let {
                        successors.add(it)
                        BlockDependency(
                            from = (
                                info.jumpTarget!!.immediateSource.blockInstance
                                ?: error("No block instance source for jump target ${info.jumpTarget}")
                            ).let { treapSetOf(it) },
                            to = it.block,
                            synthetic = syntheticJumpTarget
                        )
                    },
                    fallthrough = fallthrough?.let { successor(it, false) }?.let {
                        successors.add(it)
                        BlockDependency(
                            from = treapSetOf(block),
                            to = it.block,
                            synthetic = false
                        )
                    },
                    usedSources =
                        info.jumpTarget?.sources.orEmpty() +
                            info.jumpCondition?.sources?.takeIf { jumpConditionValue != null }.orEmpty()
                ).also {
                    logger.trace { "Result: $block = $it" }
                }
            }

            if (blocks.size > Config.MaxUnfoldedBlockCount.get()) {
                writeUnfoldedSizeProfile(blocks)
                throw CertoraException(
                    CertoraErrorType.CODE_SIZE,
                    "Contract '$contractName' expands to too many unfolded basic blocks (maxUnfoldedBlockCount=${Config.MaxUnfoldedBlockCount.get()})"
                )
            }

            successors
        }
        return blocks
    }

    /**
     Compute all needed block instances.
     */
    private fun computeBlockInstances(dispatch: DispatchAnalysis.Path?): Map<BlockInstance, BlockInstanceResult> {
        // First pass: compute the instances with no idea which stack values are actually needed.  This will generate
        // more instances than needed, but will also find the stack values we really need.
        logger.debug { "computeBlockInstances: first pass" }
        val rawBlocks = computeBlockInstances(dispatch) { it }

        // Now, compute which stack values were actually used.  We compute this transitively across the CFG, so that we
        // know which values affect all future jump targets starting at each block.
        val successors = mutableMapOf<EVMPC, TreapSet<EVMPC>>()
        val usedSources = mutableMapOf<EVMPC, TreapSet<EVMValueSource>>()
        rawBlocks.forEachEntry { (block, result) ->
            successors[block.pc] = successors[block.pc].orEmpty() + result.successors.mapToTreapSet { it.pc }
            usedSources[block.pc] = usedSources[block.pc].orEmpty() + result.usedSources
        }
        val closedUsedSources = mapTransitiveClosure(successors, reflexive = true) {
            it.fold(treapSetOf<EVMValueSource>()) { s, v -> s + usedSources[v].orEmpty() }
        }.toMap()

        fun BlockInstance.withoutUnused() = copy(
            stackIn = stackIn.updateValues { i, it ->
                when {
                    it.sources.containsAny(closedUsedSources[pc].orEmpty()) -> it
                    it.isFunctionFinderConstant() -> it
                    it.isSourceFinderConstant() -> it
                    else -> Unknown(EVMValueSource.StackPosition(i))
                }
            }
        )

        // We don't want to duplicate blocks that just happen to be entered with different boolean values at the top of
        // the stack. Luckily, in the cases where we need the topOfStackValue (unwinding from exception handling
        // clauses), we won't have multiple block instances that differ only by their top boolean value.
        //
        // So, we de-duplicate any instances that differ only by their top boolean value, while keeping the top value
        // for blocks that aren't duplicated on that basis in the first place.
        fun BlockInstance.withoutTopBoolean() = copy(
            stackIn = when {
                stackIn.isEmpty() -> stackIn
                stackIn.top !is InferredBool -> stackIn
                else -> stackIn.pop().push(
                    Unknown(EVMValueSource.StackPosition(stackIn.size))
                )
            },
            topOfStackValue = null
        )
        val joinableWithoutTopBoolean = rawBlocks.keys.groupingBy { it.withoutUnused().withoutTopBoolean() }.eachCount()

        // Second pass: recompute the instances, this time deduplicating using the info gathered above.
        logger.debug { "computeBlockInstances: second pass" }
        val deduplicatedBlocks = computeBlockInstances(dispatch) { block ->
            val withoutUnused = block.withoutUnused()
            val withoutTopBoolean = withoutUnused.withoutTopBoolean()
            when {
                joinableWithoutTopBoolean[withoutTopBoolean] ?: 0 > 1 -> withoutTopBoolean
                else -> withoutUnused
            }
        }

        return deduplicatedBlocks
    }

    /**
        Translates a single basic block from EVM instructions to TAC commands.
     */
    private fun translateEVMToTAC(
        origBlock: BlockInstance,
        currentNBId: NBId,
        jumpTarget: NBId?,
        jumpTargetIsSynthetic: Boolean,
        fallthrough: NBId?
    ): List<TACCmd> {
        val result = mutableListOf<TACCmd>()

        /*
         Don't propagate constants from other blocks.  We have analyses that depend on this.
         EXCEPTION: solidity may keep function finder constants on the stack. We rely on these to be
         inlined into the store commands, so propagate those
         */
        val block = origBlock.copy(
            stackIn = origBlock.stackIn.updateValues { _, it ->
                when {
                    it.isFunctionFinderConstant() -> it
                    it.isSourceFinderConstant() -> it
                    else -> Unknown(it.immediateSource)
                }
            }
        )

        interpretBlock(block) {
            val metaInfo = TACMetaInfo(pc, cmd.meta, address, sourceContext)
            val meta = metaInfo.toMap()
            val inst = cmd.inst

            val stack = stackBefore.builder()
            val stackSizeBefore = stack.size

            fun stackVar(depth: Int) = TACSymbol.Var.stackVar(MAX_EVM_STACK_POS - (stack.size - depth))
            fun EVMValue.toSym(depth: Int) = this.toBigInteger()?.let { TACSymbol.Const(it) } ?: stackVar(depth)

            fun pop() = stack.pop().toSym(0)
            fun peek(depth: Int) = stack.peek(depth).last().toSym(depth)
            fun peekAll(depth: Int) = stack.peek(depth).mapIndexed { i, v -> v.toSym(i + 1) }.toList()

            // Pop a value as a TACExpr
            fun popExp() = pop().asSym()

            // Sometimes we don't want to propagate constants--some analyses expect a var.  We also are trying to keep
            // the output easily comparable to the old decompiler, so we're not propagating all availble constants.
            fun popVar() = stack.pop().let { stackVar(0) }

            // Helpers for generating assignments
            fun assign(lhs: TACSymbol.Var, rhs: TACExpr) =
                TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = lhs, rhs = rhs, meta = meta)
            fun assign(lhs: TACSymbol.Var, rhs: TACSymbol) = assign(lhs = lhs, rhs = rhs.asSym())

            // Gets the location where a result will be pushed on the stack.
            fun result(): TACSymbol.Var {
                check(stack.size == stackSizeBefore - inst.popCount + (inst.pushCount - 1)) {
                    "Did not pop the correct number of values for $inst at $pc"
                }
                return stackVar(0)
            }

            // A single command
            fun cmd(cmd: () -> TACCmd) = listOf(cmd())

            // A single command which might be replaced by a simple assignment of a constant computed by the
            // interpreter.
            fun cmdResult(cmdMaybeConst: () -> TACCmd) = when (val c = stackAfter.top.toBigInteger()) {
                null -> listOf(cmdMaybeConst())
                else -> {
                    stack.pop(inst.popCount)
                    listOf(assign(lhs = result(), rhs = TACSymbol.Const(c)))
                }
            }

            // Helper for pushing the result of an expression (which may be a constant from the interpreter)
            fun push(exprMaybeConst: () -> TACExpr) = cmdResult { assign(rhs = exprMaybeConst(), lhs = result()) }

            result += when (inst) {
                is PUSH -> cmd { assign(lhs = result(), rhs = TACSymbol.Const(inst.value)) }
                is PushImmutable -> cmd {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        rhs = TACSymbol.immutable(
                            name = inst.name,
                            owningContract = contractName
                        ).withMeta { m ->
                            inst.value?.let { m + (TACBasicMeta.IMMUTABLE_LINK to it) } ?: m
                        },
                        lhs = result(),
                        meta = meta + IS_IMMUTABLE
                    )
                }
                is DUP -> cmd { assign(lhs = result(), rhs = peek(inst.dupNum)) }
                is SWAP -> listOf(
                    assign(lhs = SWAP_VAR, rhs = peek(1)),
                    assign(lhs = stackVar(1), rhs = peek(inst.swapNum + 1)),
                    assign(lhs = stackVar(inst.swapNum + 1), rhs = (peek(1) as? TACSymbol.Const) ?: SWAP_VAR)
                )
                PC -> cmd { assign(rhs = TACSymbol.Const(pc.toBigInteger()), lhs = result()) }
                POP -> cmd { TACCmd.Simple.NopCmd }

                JUMPDEST -> cmd { TACCmd.Simple.JumpdestCmd(currentNBId) }
                JUMP -> {
                    check(jumpTarget != null) {
                        "Attempting to convert JUMP instruction at PC $pc with no jump target."
                    }
                    listOfNotNull(
                        TACCmd.Simple.AnnotationCmd(
                            TACCmd.Simple.AnnotationCmd.Annotation(JUMP_SYM, TACSymbol.Var.Annotation(popVar()))
                        ).takeUnless { jumpTargetIsSynthetic },
                        TACCmd.Simple.JumpCmd(jumpTarget, meta)
                    )
                }
                JUMPI -> when {
                    jumpTarget == null && fallthrough == null -> {
                        error("Attempting to convert JUMPI instruction at PC $pc with no jump target or fallthrough.")
                    }
                    jumpTarget == null -> {
                        popVar() // jump target
                        val cond = popVar()
                        listOf(
                            TACCmd.Simple.AssumeNotCmd(cond),
                            TACCmd.Simple.JumpCmd(fallthrough!!, meta)
                        )
                    }
                    fallthrough == null -> {
                        popVar() // jump target
                        val cond = popVar()
                        listOf(
                            TACCmd.Simple.AssumeCmd(cond, "JUMPI"),
                            TACCmd.Simple.JumpCmd(jumpTarget, meta)
                        )
                    }
                    else -> cmd {
                        TACCmd.Simple.JumpiCmd(
                            dst = jumpTarget.also { popVar() },
                            cond = popVar(),
                            elseDst = fallthrough,
                            meta = meta
                        )
                    }
                }

                ADD -> push { TACExpr.Vec.Add(op1 = popExp(), op2 = popExp()) }
                MUL -> push { TACExpr.Vec.Mul(op1 = popExp(), op2 = popExp()) }
                SUB -> push { TACExpr.BinOp.Sub(o1 = popExp(), o2 = popExp()) }
                DIV -> {
                    // The old decompiler does this optimization (and analyses seem to rely on it)
                    val a = peek(1)
                    val b = peek(2)
                    if (b is TACSymbol.Const && b.value == BigInteger.ONE) {
                        push {
                            pop()
                            pop()
                            a.asSym()
                        }
                    } else {
                        push { TACExpr.BinOp.Div(o1 = popExp(), o2 = popExp()) }
                    }
                }
                SDIV -> push { TACExpr.BinOp.SDiv(o1 = popExp(), o2 = popExp()) }
                MOD -> push { TACExpr.BinOp.Mod(o1 = popExp(), o2 = popExp()) }
                SMOD -> push { TACExpr.BinOp.SMod(o1 = popExp(), o2 = popExp()) }
                ADDMOD -> push { TACExpr.TernaryExp.AddMod(a = popExp(), b = popExp(), n = popExp()) }
                MULMOD -> push { TACExpr.TernaryExp.MulMod(a = popExp(), b = popExp(), n = popExp()) }
                EXP -> push { TACExpr.BinOp.Exponent(o1 = popExp(), o2 = popExp()) }
                SIGNEXTEND -> push { TACExpr.BinOp.SignExtend(o1 = popExp(), o2 = popExp()) }
                LT -> push { TACExpr.BinRel.Lt(o1 = popExp(), o2 = popExp()) }
                GT -> push { TACExpr.BinRel.Gt(o1 = popExp(), o2 = popExp()) }
                SLT -> push { TACExpr.BinRel.Slt(o1 = popExp(), o2 = popExp()) }
                SGT -> push { TACExpr.BinRel.Slt(o2 = popExp(), o1 = popExp()) } // matching old decompiler behavior
                EQ -> push { TACExpr.BinRel.Eq(o1 = popExp(), o2 = popExp()) }
                ISZERO -> cmd { TACCmd.EVM.AssignIszeroCmd(op1 = popVar(), lhs = result(), meta = meta) }
                AND -> push { TACExpr.BinOp.BWAnd(o1 = popExp(), o2 = popExp()) }
                OR -> push { TACExpr.BinOp.BWOr(o1 = popExp(), o2 = popExp()) }
                XOR -> push { TACExpr.BinOp.BWXOr(o1 = popExp(), o2 = popExp()) }
                NOT -> push { TACExpr.UnaryExp.BWNot(o = popExp()) }
                SHL -> push { TACExpr.BinOp.ShiftLeft(o2 = popExp(), o1 = popExp()) }
                SHR -> push { TACExpr.BinOp.ShiftRightLogical(o2 = popExp(), o1 = popExp()) }
                SAR -> push { TACExpr.BinOp.ShiftRightArithmetical(o2 = popExp(), o1 = popExp()) }
                BYTE -> cmdResult {
                    TACCmd.EVM.AssigningCmd.AssignByteCmd(op1 = pop(), op2 = pop(), lhs = result(), meta = meta)
                }
                MLOAD -> cmd {
                    TACCmd.EVM.MloadCmd(loc = stack.pop().toSym(0), lhs = result(), memBaseMap = memory, meta = meta)
                }
                MSTORE -> cmd {
                    TACCmd.EVM.MstoreCmd(loc = stack.pop().toSym(0), rhs = pop(), memBaseMap = memory, meta = meta)
                }
                MSTORE8 -> cmd {
                    TACCmd.EVM.MbytestoreCmd(loc = pop(), rhs = pop(), memBaseMap = memory, meta = meta)
                }
                MCOPY -> cmd {
                    TACCmd.EVM.McopyCmd(
                        dst = pop(),
                        src = pop(),
                        len = pop(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                SLOAD -> cmd {
                    TACCmd.EVM.SloadCmd(
                        loc = popVar(),
                        lhs = result(),
                        storageBaseMap =
                        getStorageInternal(address),
                        meta = meta
                    )
                }
                SSTORE -> cmd {
                    TACCmd.EVM.SstoreCmd(
                        loc = popVar(),
                        rhs = popVar(),
                        storageBaseMap = getStorageInternal(address),
                        meta = meta
                    )
                }
                TLOAD -> cmd {
                    TACCmd.EVM.TloadCmd(
                        loc = popVar(),
                        lhs = result(),
                        transientStorageBaseMap = getTransientStorageInternal(address),
                        meta = meta
                    )
                }
                TSTORE -> cmd {
                    TACCmd.EVM.TstoreCmd(
                        loc = popVar(),
                        rhs = popVar(),
                        transientStorageBaseMap = getTransientStorageInternal(address),
                        meta = meta
                    )
                }
                KECCAK256 -> cmd {
                    TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                        op1 = popVar(),
                        op2 = popVar(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }

                ADDRESS -> cmdResult { TACCmd.EVM.AssignAddressCmd(result(), meta) }
                BALANCE -> cmdResult { TACCmd.EVM.AssignBalanceCmd(op1 = pop(), lhs = result(), meta = meta) }
                ORIGIN -> cmdResult { TACCmd.EVM.AssignOriginCmd(result(), meta) }
                CALLER -> cmdResult { TACCmd.EVM.AssignCallerCmd(result(), meta) }
                CALLVALUE -> cmdResult { TACCmd.EVM.AssignCallvalueCmd(result(), meta) }
                CALLDATALOAD -> cmdResult {
                    TACCmd.EVM.AssignCalldataloadCmd(
                        op1 = popVar(),
                        lhs = result(),
                        buffer = TACKeyword.CALLDATA.getName(),
                        meta = meta
                    )
                }
                CALLDATASIZE -> cmdResult { TACCmd.EVM.AssignCalldatasizeCmd(result(), "calldata", meta) }
                CALLDATACOPY -> cmd {
                    TACCmd.EVM.CalldatacopyCmd(
                        op1 = pop(),
                        op2 = pop(),
                        op3 = pop(),
                        memBaseMap = memory,
                        buffer = TACKeyword.CALLDATA.getName(),
                        meta = meta
                    )
                }
                CODESIZE -> cmdResult {
                    TACCmd.EVM.AssignCodesizeCmd(
                        lhs = result(),
                        sz = bytecode.bytes.size,
                        meta = meta.plus(TACMeta.CODESIZE_KEY to address)
                    )
                }
                CODECOPY -> cmd {
                    TACCmd.EVM.CodecopyCmd(
                        op1 = popVar(),
                        op2 = popVar(),
                        op3 = popVar(),
                        memBaseMap = memory,
                        buffer = TACKeyword.CODEDATA.getName(),
                        meta = meta.plus(TACMeta.CODEDATA_KEY to address)
                    )
                }
                GASPRICE -> cmdResult { TACCmd.EVM.AssignGaspriceCmd(result(), meta) }
                EXTCODESIZE -> cmdResult { TACCmd.EVM.AssignExtcodesizeCmd(op1 = pop(), lhs = result(), meta = meta) }
                EXTCODEHASH -> cmdResult { TACCmd.EVM.AssignExtcodehashCmd(op1 = pop(), lhs = result(), meta = meta) }
                EXTCODECOPY -> cmd {
                    TACCmd.EVM.ExtcodecopyCmd(
                        op1 = pop(),
                        op2 = pop(),
                        op3 = pop(),
                        op4 = pop(),
                        memBaseMap = memory,
                        buffer = TACKeyword.EXTCODEDATA.getName(),
                        meta = meta
                    )
                }
                RETURNDATASIZE -> cmdResult { TACCmd.EVM.ReturndatasizeCmd(result(), meta) }
                RETURNDATACOPY -> cmd {
                    TACCmd.EVM.ReturndatacopyCmd(
                        o1 = popVar(),
                        o2 = popVar(),
                        o3 = popVar(),
                        memBaseMap = memory,
                        buffer = TACKeyword.RETURNDATA.getName(),
                        meta = meta
                    )
                }
                BLOCKHASH -> cmdResult { TACCmd.EVM.AssignBlockhashCmd(op = pop(), lhs = result(), meta = meta) }
                COINBASE -> cmdResult { TACCmd.EVM.AssignCoinbaseCmd(lhs = result(), meta = meta) }
                TIMESTAMP -> cmdResult { TACCmd.EVM.AssignTimestampCmd(lhs = result(), meta = meta) }
                NUMBER -> cmdResult { TACCmd.EVM.AssignNumberCmd(lhs = result(), meta = meta) }
                DIFFICULTY -> cmdResult { TACCmd.EVM.AssignDifficultyCmd(lhs = result(), meta = meta) }
                GASLIMIT -> cmdResult { TACCmd.EVM.AssignGaslimitCmd(lhs = result(), meta = meta) }
                CHAINID -> cmdResult { TACCmd.EVM.AssignChainidCmd(lhs = result(), meta = meta) }
                SELFBALANCE -> cmdResult { TACCmd.EVM.AssignSelfBalanceCmd(lhs = result(), meta = meta) }
                BASEFEE -> cmdResult { TACCmd.EVM.AssignBasefeeCmd(lhs = result(), meta = meta) }
                BLOBHASH -> cmdResult { TACCmd.EVM.AssignBlobhashCmd(index = pop(), lhs = result(), meta = meta) }
                BLOBBASEFEE -> cmdResult { TACCmd.EVM.AssignBlobbasefeeCmd(lhs = result(), meta = meta) }
                MSIZE -> cmdResult { TACCmd.Simple.AssigningCmd.AssignMsizeCmd(lhs = result(), meta = meta) }
                GAS -> cmdResult { TACCmd.Simple.AssigningCmd.AssignGasCmd(lhs = result(), meta = meta) }
                is PushContractAddress -> cmdResult {
                    TACCmd.EVM.AssigningCmd.AssignLibraryAddressCmd(
                        libraryContractId = inst.contractId,
                        lhs = result(),
                        meta = meta
                    )
                }

                is LOG -> cmd {
                    when (inst.logNum) {
                        0 -> TACCmd.EVM.EVMLog0Cmd(o1 = pop(), o2 = pop(), meta = meta)
                        1 -> TACCmd.EVM.EVMLog1Cmd(o1 = pop(), o2 = pop(), t1 = pop(), meta = meta)
                        2 -> TACCmd.EVM.EVMLog2Cmd(o1 = pop(), o2 = pop(), t1 = pop(), t2 = pop(), meta = meta)
                        3 -> TACCmd.EVM.EVMLog3Cmd(o1 = pop(), o2 = pop(), t1 = pop(), t2 = pop(), t3 = pop(), meta = meta)
                        4 -> TACCmd.EVM.EVMLog4Cmd(o1 = pop(), o2 = pop(), t1 = pop(), t2 = pop(), t3 = pop(), t4 = pop(), meta = meta)
                        else -> `impossible!`
                    }
                }
                CREATE -> cmdResult {
                    TACCmd.EVM.CreateCmd(
                        value = pop(),
                        offset = pop(),
                        length = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                CREATE2 -> cmdResult {
                    TACCmd.EVM.Create2Cmd(
                        value = pop(),
                        offset = pop(),
                        length = pop(),
                        salt = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                CALL -> cmd {
                    TACCmd.EVM.CallCmd(
                        o1 = pop(),
                        o2 = pop(),
                        o3 = pop(),
                        o4 = pop(),
                        o5 = pop(),
                        o6 = pop(),
                        o7 = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                CALLCODE -> cmdResult {
                    TACCmd.EVM.CallcodeCmd(
                        o1 = pop(),
                        o2 = pop(),
                        o3 = pop(),
                        o4 = pop(),
                        o5 = pop(),
                        o6 = pop(),
                        o7 = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                DELEGATECALL -> cmdResult {
                    TACCmd.EVM.DelegatecallCmd(
                        o1 = pop(),
                        o2 = pop(),
                        o3 = pop(),
                        o4 = pop(),
                        o5 = pop(),
                        o6 = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }
                STATICCALL -> cmd {
                    TACCmd.EVM.StaticcallCmd(
                        o1 = pop(),
                        o2 = pop(),
                        o3 = pop(),
                        o4 = pop(),
                        o5 = pop(),
                        o6 = pop(),
                        lhs = result(),
                        memBaseMap = memory,
                        meta = meta
                    )
                }

                RETURN -> cmd { TACCmd.EVM.EVMReturnCmd(o1 = popVar(), o2 = popVar(), memBaseMap = memory, meta = meta) }
                STOP -> cmd { TACCmd.EVM.StopCmd }
                REVERT -> cmd { TACCmd.EVM.EVMRevertCmd(o1 = popVar(), o2 = popVar(), base = memory, meta = meta) }
                SELFDESTRUCT -> cmd { TACCmd.EVM.SelfdestructCmd(o = popVar(), meta = meta) }

                INVALID -> cmd { TACCmd.EVM.InvalidCmd }
            }

            if (stackMapping.isFeatureFlagEnabled) {
                val stackTopBefore = 1 + MAX_EVM_STACK_POS - stackBefore.size
                val stackTop = 1 + MAX_EVM_STACK_POS - stackAfter.size
                stackMapping.updateFromJimplerState(stackTopBefore, stackTop, cmd, metaInfo)
                result += stackMapping.emittedOnThisRead
            }
        }

        if (fallthrough != null &&
            result.last() !is TACCmd.Simple.JumpCmd &&
            result.last() !is TACCmd.Simple.JumpiCmd
        ) {
            result.addAll(
                listOf(
                    TACCmd.Simple.AnnotationCmd(
                        TACCmd.Simple.AnnotationCmd.Annotation(JUMP_SYM, TACSymbol.Var.Annotation(null))
                    ),
                    TACCmd.Simple.JumpCmd(fallthrough)
                )
            )
        }

        return result
    }

    /**
        Produce a TAC program from the EVM program.
     */
    fun jimple(dispatch: DispatchAnalysis.Path?): EVMTACProgram {
        // Mapping from BlockInstance to NBId.
        val blockIds = mutableMapOf<BlockInstance, NBId>()
        blockIds[BlockInstance.Start] = StartBlock

        // The number of block instances with the same PC, stack position, and top stack boolean - but different future
        // jump targets on the stack.
        val decompCopyCount = mutableMapOf<NBId, Int>()

        // Allocate an NBId for a given BlockInstance.
        fun BlockInstance.toNBId() = blockIds.getOrPut(this) {
            val id = StartBlock.copy(
                origStartPc = pc,
                stkTop = MAX_EVM_STACK_POS + 1 - stackIn.size,
                topOfStackValue = when (topOfStackValue) {
                    // See [BlockIdentifier.topOfStackValue]
                    null -> 0
                    true -> 1
                    false -> 2
                }
            )
            // Disambiguate instances which differ only by the jump targets on the stack.
            val result = id.copy(decompCopy = decompCopyCount[id] ?: 0)
            decompCopyCount[id] = result.decompCopy + 1

            logger.debug { "Producing block $result from $this" }
            result
        }

        // Get all block instances
        val blockInstances = computeBlockInstances(dispatch)

        // Translate each block instance from EVM to TAC
        val blocks = mutableMapOf<NBId, List<TACCmd>>()
        var commandCount = 0
        val blockGraph = LinkedArrayHashMap<NBId, TreapSet<NBId>>()
        val blockDependencies = mutableMapOf<NBId, TreapSet<NBId>>()

        try {
            for ((instance, info) in blockInstances) {
                yieldToTestTimeout()

                val nbid = instance.toNBId()

                val commands = when (info) {
                    is BlockInstanceResult.Success -> {
                        val jumpTarget = info.jumpTarget?.to?.toNBId()
                        val fallthrough = info.fallthrough?.to?.toNBId()

                        blockGraph[nbid] = treapSetOfNotNull(jumpTarget, fallthrough)

                        listOfNotNull(info.jumpTarget, info.fallthrough).forEach {
                            it.from.forEach { fromInstance ->
                                val from = fromInstance.toNBId()
                                blockDependencies[from] = blockDependencies[from].orEmpty() + it.to.toNBId()
                            }
                        }

                        translateEVMToTAC(
                            instance,
                            nbid,
                            jumpTarget,
                            info.jumpTarget?.synthetic ?: false,
                            fallthrough
                        )
                    }
                    is BlockInstanceResult.Error -> when (val error = info.error) {
                        is InterpretedBlockInfo.StackOverflow -> listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                "EVM stack overflow at PC ${instance.pc}"
                            ),
                            TACCmd.EVM.StopCmd
                        )
                        is InterpretedBlockInfo.StackUnderflow -> listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                "EVM stack underflow at PC ${error.errorPC} (hard)"
                            ),
                            TACCmd.EVM.StopCmd
                        )
                        is InterpretedBlockInfo.RecursionLimitReached -> when {
                            Config.RecursionErrorAsAssertInAllCases.get() -> listOf(
                                TACCmd.Simple.AssertCmd(
                                    TACSymbol.False,
                                    "Recursion protection ${1024 - Config.RecursionLimitHeuristic.get()}"
                                ),
                                TACCmd.EVM.StopCmd
                            )
                            else -> listOf(
                                TACCmd.Simple.AssumeCmd(TACSymbol.False, "RecursionLimitReached"),
                                TACCmd.EVM.StopCmd
                            )
                        }
                        is InterpretedBlockInfo.StackSizeLimitReached -> listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                "EVM stack overflow at PC ${error.errorPC} (soft, limit: ${1024 - Config.RecursionLimitHeuristic.get()})"
                            ),
                            TACCmd.EVM.StopCmd
                        )
                        is InterpretedBlockInfo.JumpThunkLimitReached -> listOf(
                            TACCmd.EVM.EVMRevertCmd(
                                TACSymbol.Const(BigInteger.ZERO),
                                TACSymbol.Const(BigInteger.ZERO),
                                TACRevertType.THROW,
                                memory
                            )
                        )
                        is InterpretedBlockInfo.IllegalJumpTarget -> listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                "EVM instruction at PC ${error.errorPC} jumps to unknown destination ${error.target.toBigInteger()}, from ${error.target.immediateSource}.  Possible unresolved function pointer."
                            ),
                            TACCmd.EVM.StopCmd
                        )
                        is InterpretedBlockInfo.NoCode -> listOf(
                            TACCmd.Simple.AssertCmd(
                                TACSymbol.False,
                                "EVM block at PC ${instance.pc} does not end with a valid instruction"
                            ),
                            TACCmd.EVM.StopCmd
                        )
                    }
                }

                blocks[nbid] = commands
                commandCount += commands.size

                // Check our size limits
                if (blocks.size > Config.MaxBlockCount.get()) {
                    throw CertoraException(
                        CertoraErrorType.CODE_SIZE,
                        "Contract '$contractName' expands to too many decompiled basic blocks (maxBlockCount=${Config.MaxBlockCount.get()})"
                    )
                }
                if (commandCount > Config.MaxCommandCount.get()) {
                    throw CertoraException(
                        CertoraErrorType.CODE_SIZE,
                        "Contract '$contractName' expands to too many decompiled instructions (maxCommandCount=${Config.MaxCommandCount.get()})"
                    )
                }

                // Ensure that all blocks are in the block graph
                blockGraph.putIfAbsent(nbid, treapSetOf())
            }

            // Extract tags
            var tags = treapSetOf<TACSymbol.Var>()
            for (block in blocks.values) {
                for (cmd in block) {
                    cmd.getLhs()?.let {
                        tags += it
                    }
                    cmd.getRhs().forEach {
                        if (it is TACSymbol.Var) {
                            tags += it
                        }
                    }
                }
            }

            return EVMTACProgram(blocks, blockGraph, methodName, TACSymbolTable.withTags(tags))

        } finally {
            // Generate a TAC code size profile, even if we throw (which might have been because the code was too
            // large).
            writeTACSizeProfile(blocks, blockDependencies)
        }
    }

    private fun writeTACSizeProfile(blocks: Map<NBId, List<TACCmd>>, dependencies: Map<NBId, Set<NBId>>) {
        if (Config.DumpCodeSizeAnalysis.get()) {
            // Generate the block instance "dependency" graph - not the CFG! - where each node's size is the number of
            // generated TAC commands.
            fun profilerNode(nbid: NBId): GraphProfiler.Node = GraphProfiler.Node.Object(
                value = nbid,
                className = nbid.origStartPc.diagnosticSource(),
                selfSize = blocks[nbid].orEmpty().size,
                edges = sequence {
                    yield(
                        GraphProfiler.Edge.Property(
                            name = "id",
                            to = GraphProfiler.Node.String(value = nbid.toString(), selfSize = 0)
                        )
                    )
                    yieldAll(
                        dependencies[nbid].orEmpty().asSequence().mapIndexed { i, it ->
                            GraphProfiler.Edge.Element(i, profilerNode(it))
                        }
                    )
                }
            )

            GraphProfiler.writeProfile(
                Config.getTACSizeProfilePath(contractName, methodName),
                sequenceOf(profilerNode(StartBlock))
            )
        }
    }

    private fun writeUnfoldedSizeProfile(blocks: Map<BlockInstance, BlockInstanceResult>) {
        if (Config.DumpCodeSizeAnalysis.get()) {
            // Construct the dependency graph for the blocks
            val dependencies = mutableMapOf<BlockInstance, TreapSet<BlockInstance>>()

            blocks.forEachEntry { (_, result) ->
                if (result is BlockInstanceResult.Success) {
                    listOfNotNull(result.jumpTarget, result.fallthrough).forEach {
                        it.from.forEach { from ->
                            dependencies[from] = dependencies[from].orEmpty() + it.to
                        }
                    }
                }
            }

            fun profilerNode(block: BlockInstance): GraphProfiler.Node = GraphProfiler.Node.Object(
                value = block,
                className = block.pc.diagnosticSource(),
                selfSize = 1,
                edges = dependencies[block].orEmpty().asSequence().mapIndexed { i, it ->
                    GraphProfiler.Edge.Element(i, profilerNode(it))
                }
            )

            GraphProfiler.writeProfile(
                Config.getUnfoldedSizeProfilePath(contractName, methodName),
                sequenceOf(profilerNode(BlockInstance.Start))
            )
        }
    }
}
