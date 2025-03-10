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

@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)
package decompiler

import algorithms.findRoots
import algorithms.SimpleDominanceAnalysis
import algorithms.transitiveClosure
import bridge.Method
import bridge.getSigHashInt
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import disassembler.*
import disassembler.EVMInstruction.*
import evm.*
import log.Logger
import log.LoggerTypes
import scene.source.Sighash
import utils.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.DECOMPILER)


interface DispatchMethod {
    val abiName: String
    val sighash: Sighash?
    val method: Method
}

/**
    Given EVM bytecode and metdata about the methods it implements, finds the branches taken through the EVM dispatcher
    code to reach each method.

    We start from the assumption that the first 4 bytes of CALLDATA contain the method's sighash.  From there we
    interpret the EVM code symbolically, attempting to find the values for jump conditions and targets where those are
    determined by the sighash value.

    We also compute a "fallback" path, which is the path taken if the sighash is *not* one of the expected values, or is
    missing.

    Note that we need to deal with a variety of dispatcher patterns, including multiple variants of "switch statements"
    emitted by the Solidity and Vyper compilers, as well as at least two hash table implementations produced by Vyper,
    so this analysis needs to be fairly general, avoiding assumptions about specific dispatcher patterns.

    One assumption we *do* make is that there is a single straight line through the dispatch code for each method (no
    loops, etc.), which means we do not need to join stacks in the analysis.  If we ever encounter non-trivial control
    flow, we set [endOfDispatch] to tell the decompiler to stop using the dispatch analysis beyond that point.
 */
class DispatchAnalysis(val bytecode: DisassembledEVMBytecode, methods: Iterable<DispatchMethod>) {
    /** The condition and/or targets of a particular jump instruction. */
    data class DispatchPathComponent(
        val conditionalJumpTaken: Boolean?,
        val jumpTargets: TreapSet<BigInteger>?
    ) {
        // The analysis tries to resolve `jumpTargets` to a single target, which is all the decompiler can currently
        // handle.
        val jumpTarget = jumpTargets?.singleOrNull()
    }

    /** The analysis of a particular method */
    data class Path(
        val path: Map<EVMPC, DispatchPathComponent>,
        val endEdges: Set<Pair<EVMPC, EVMPC>>
    )

    private val validBytecodeOffsets = `0`..<bytecode.bytes.size.toBigInteger()

    val methodPaths = methods.associate {
        it to findDispatchPath(it.sighash.getSigHashInt()!!.n.toValue(true), it.abiName)
    }

    val fallbackPath = findDispatchPath(
        Value.AntiSighash(methods.mapToSet { it.sighash.getSigHashInt()!!.n }),
        "fallback"
    )

    private sealed interface Value {
        val fromSighash: Boolean // Is this value derived somehow from the sighash?
        val isZero: Boolean?

        val isNotZero: Boolean? get() = isZero?.let { !it }
        val isDefinitelyZero: Boolean get() = isZero == true
        val isDefinitelyNotZero: Boolean get() = isNotZero == true

        val exactValue: BigInteger? get() = if (isDefinitelyZero) { `0` } else { null }

        /** Truncate to 256 bits */
        fun trunc256(): Value

        infix operator fun plus(that: Value): Value = Unknown
        infix operator fun minus(that: Value): Value = Unknown
        infix operator fun times(that: Value): Value = Unknown
        infix operator fun div(that: Value): Value = Unknown
        infix operator fun rem(that: Value): Value = Unknown
        infix fun exp(that: Value): Value = Unknown
        infix fun shl(shift: Value): Value = Unknown
        infix fun shr(shift: Value): Value = Unknown

        /** An unsighed BigInteger value in the given range */
        open class UIntRange(val range: ClosedRange<BigInteger>, override val fromSighash: Boolean) : Value {

            override fun toString() = if (fromSighash) { "@$valueForString" } else { "$valueForString" }
            private val valueForString = exactValue ?: range

            override val exactValue get() = range.start.takeIf { it == range.endInclusive }

            override val isZero get() = when {
                exactValue == `0` -> true
                `0` !in range -> false
                else -> null
            }

            override fun plus(that: Value): Value = when {
                that.isDefinitelyZero -> this.range.toValue(this.fromSighash || that.fromSighash)
                that !is UIntRange -> Unknown
                else -> ((range.start + that.range.start)..(range.endInclusive + that.range.endInclusive))
                    .toValue(this.fromSighash || that.fromSighash)
            }.trunc256()

            override fun minus(that: Value): Value = when {
                that.isDefinitelyZero -> this.range.toValue(this.fromSighash || that.fromSighash)
                that is AntiSighash && this.exactValue != null && this.exactValue in that.sighashes -> NonZero(true)
                that !is UIntRange -> Unknown
                else -> ((range.start - that.range.endInclusive)..(range.endInclusive - that.range.start))
                    .toValue(this.fromSighash || that.fromSighash)
            }.trunc256()

            override fun times(that: Value): Value = when {
                that.isDefinitelyZero -> `0`.toValue(that.fromSighash)
                that !is UIntRange -> Unknown
                else -> ((range.start * that.range.start)..(range.endInclusive * that.range.endInclusive))
                    .toValue(this.fromSighash || that.fromSighash)
            }.trunc256()

            override fun div(that: Value): Value = when {
                that.isDefinitelyZero -> Unknown
                that !is UIntRange -> Unknown
                that.range.start == `0` || that.range.endInclusive == `0` -> Unknown
                else -> ((range.start / that.range.endInclusive)..(range.endInclusive / that.range.start))
                    .toValue(this.fromSighash || that.fromSighash)
            }.trunc256()

            override fun rem(that: Value): Value = when {
                that.exactValue == null -> Unknown
                that.exactValue == `0` -> Unknown
                that.exactValue!! > MAX_EVM_UINT256 -> Unknown
                this.exactValue == null -> (`0`..that.exactValue!!-`1`).toValue(this.fromSighash || that.fromSighash)
                else -> this.exactValue!!.mod(that.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            }.trunc256()

            override fun exp(that: Value): Value = when {
                that.isDefinitelyZero -> `1`.toValue(that.fromSighash)
                that !is UIntRange -> Unknown
                else -> that.exactValue?.takeIf { it <= MAX_INT }?.let { it.intValueExact() }?.let { pow ->
                    (range.start.pow(pow)..range.endInclusive.pow(pow))
                        .toValue(this.fromSighash || that.fromSighash)
                } ?: Unknown
            }.trunc256()

            override fun shl(shift: Value) = (
                shift.exactValue?.takeIf { it < `256` }?.let { it.intValueExact() }?.let { s ->
                    (range.start.shiftLeft(s)..range.endInclusive.shiftLeft(s)).toValue(fromSighash)
                } ?: Unknown
            ).trunc256()

            override fun shr(shift: Value) = (
                shift.exactValue?.takeIf { it < `256` }?.let { it.intValueExact() }?.let { s ->
                    (range.start.shiftRight(s)..range.endInclusive.shiftRight(s)).toValue(fromSighash)
                } ?: Unknown
            ).trunc256()

            override fun trunc256() = letIf(range.start < `0` || MAX_EVM_INT256 < range.endInclusive) { Unknown }
        }

        infix fun isEqualTo(that: Value): Boolean? = when {
            this.exactValue != null && that.exactValue != null -> this.exactValue == that.exactValue
            this.isZero != null && that.isZero != null && this.isZero != that.isZero -> false
            this is Value.AntiSighash && that.exactValue != null && that.exactValue in this.sighashes -> false
            this.exactValue != null && that is Value.AntiSighash && this.exactValue in that.sighashes -> false
            else -> null
        }

        infix fun isNotEqualTo(that: Value) = (this isEqualTo that)?.let { !it }

        infix fun isDefinitelyEqualTo(that: Value) = (this isEqualTo that) == true
        infix fun isDefinitelyNotEqualTo(that: Value) = (this isNotEqualTo that) == true

        infix fun lt(that: Value): Value = when {
            that.isDefinitelyZero -> FALSE.toValue(this.fromSighash || that.fromSighash)
            this.isDefinitelyZero && that.isDefinitelyNotZero -> TRUE.toValue(this.fromSighash || that.fromSighash)
            this !is UIntRange || that !is UIntRange -> Unknown
            range.endInclusive < that.range.start -> TRUE.toValue(this.fromSighash || that.fromSighash)
            range.start >= that.range.endInclusive -> FALSE.toValue(this.fromSighash || that.fromSighash)
            else -> Unknown
        }

        infix fun gt(that: Value): Value = when {
            this.isDefinitelyZero -> FALSE.toValue(this.fromSighash || that.fromSighash)
            this.isDefinitelyNotZero && that.isDefinitelyZero -> TRUE.toValue(this.fromSighash || that.fromSighash)
            this !is UIntRange || that !is UIntRange -> Unknown
            range.start > that.range.endInclusive -> TRUE.toValue(this.fromSighash || that.fromSighash)
            range.endInclusive <= that.range.start -> FALSE.toValue(this.fromSighash || that.fromSighash)
            else -> Unknown
        }

        infix fun and(that: Value): Value = when {
            this.isDefinitelyZero || that.isDefinitelyZero ->
                `0`.toValue(this.fromSighash || that.fromSighash)
            this.exactValue != null && that.exactValue != null ->
                (this.exactValue!! and that.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            this.exactValue?.isBitMask == true && that is UIntRange -> when {
                that.range.endInclusive <= this.exactValue!! -> that
                else -> (`0`..this.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            }
            that.exactValue?.isBitMask == true && this is UIntRange -> when {
                this.range.endInclusive <= that.exactValue!! -> this
                else -> (`0`..that.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            }
            else -> Unknown
        }

        infix fun or(that: Value): Value = when {
            this.isDefinitelyZero && that.isDefinitelyZero ->
                `0`.toValue(this.fromSighash || that.fromSighash)
            this.exactValue != null && that.exactValue != null ->
                (this.exactValue!! or that.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            this.isDefinitelyNotZero || that.isDefinitelyNotZero ->
                NonZero(this.fromSighash || that.fromSighash)
            else -> Unknown
        }

        infix fun xor(that: Value): Value = when {
            this isDefinitelyEqualTo that ->
                `0`.toValue(this.fromSighash || that.fromSighash)
            this.exactValue != null && that.exactValue != null ->
                (this.exactValue!! xor that.exactValue!!).toValue(this.fromSighash || that.fromSighash)
            this isDefinitelyNotEqualTo that ->
                NonZero(this.fromSighash || that.fromSighash)
            else ->
                Unknown
        }

        fun not(): Value = when {
            this.exactValue != null -> EVMOps.bwNot(this.exactValue!!).toValue(this.fromSighash)
            this.isDefinitelyNotZero -> `0`.toValue(this.fromSighash)
            else -> Unknown
        }

        /** Symbolically, the first 32 bytes of the CALLDATA buffer, if such bytes exist. */
        object CalldataZero : Value {
            override val isZero = null
            override val fromSighash get() = true
            override fun trunc256() = this
            override fun toString() = "CALLDATA(0)"
        }

        /**
            Symbolically, the size of the CALLDATA buffer, for a known method.
        */
        object CalldataSizeForKnownMethod : UIntRange(`4`..MAX_EVM_UINT256, true) {
            override val fromSighash get() = true
            override fun toString() = "CALLDATASIZE"
        }

        /**
            Symbolically, the size of the CALLDATA buffer, for an unknown/fallback method.
        */
        object CalldataSizeForUnknownMethod : Value {
            override val isZero = null
            override val fromSighash get() = true
            override fun trunc256() = this
            override fun toString() = "CALLDATASIZE"
        }

        /** An integer value which is known to be in a given range, but is *not* one of the valid sighash values */
        class AntiSighash(val sighashes: Set<BigInteger>) : UIntRange(`0`..MASK32, true) {
            override fun toString() = "!$sighashes"

            override fun minus(that: Value): Value = when {
                that.exactValue != null && that.exactValue in this.sighashes -> NonZero(true)
                else -> Unknown
            }
        }

        class NonZero(override val fromSighash: Boolean) : Value {
            override val isZero get() = false
            override fun trunc256() = this
            override fun toString() = "!0"
        }

        class JumpTargets(val values: TreapSet<BigInteger>) : Value {
            override val isZero = null
            override val fromSighash get() = true
            override fun trunc256() = this
            override fun toString() = "$values"
        }

        object Unknown : Value {
            override val isZero = null
            override val fromSighash get() = false
            override fun trunc256() = Unknown
            override fun toString() = "*"
        }
    }

    private fun Value.toJumpTargets() =
        (exactValue?.let { treapSetOf(it) } ?: (this as? Value.JumpTargets)?.values).orEmpty().let { all ->
            all.retainAll { it in bytecode.jumpDestPCs }
        }

    companion object {
        val `0` = BigInteger.ZERO
        val `1` = BigInteger.ONE
        val `4` = 4.toBigInteger()
        val `8` = 8.toBigInteger()
        val `32` = 32.toBigInteger()
        val `224` = 224.toBigInteger()
        val `256` = 256.toBigInteger()
        val `2^224` = BigInteger.TWO.pow(224)
        val TRUE = `1`
        val FALSE = `0`
        val MASK32 = BigInteger.TWO.pow(32) - `1`
        val MAX_INT = Int.MAX_VALUE.toBigInteger()

        private fun ClosedRange<BigInteger>.toValue(fromSighash: Boolean): Value = when {
            start > endInclusive -> Value.Unknown
            start < `0` -> Value.Unknown
            endInclusive > MAX_EVM_UINT256 -> Value.Unknown
            else -> Value.UIntRange(this, fromSighash)
        }

        private fun BigInteger.toValue(fromSighash: Boolean): Value = (this..this).toValue(fromSighash)

        private val BigInteger.isBitMask get() = bitCount() == bitLength()
    }

    private data class EVMState(
        val pc: EVMPC,
        val stack: PersistentStack<Value>,
        val scratch: Value,
        val dirty: Boolean, // Has there been some side-effect we haven't accounted for?
    ) {
        companion object {
            val START = EVMState(0, persistentStackOf(), `0`.toValue(false), false)
        }
    }

    private fun findDispatchPath(
        sighash: Value,
        methodName: String
    ): Path {
        logger.debug { "Dispatch analysis: $methodName $sighash" }

        val path = mutableMapOf<EVMPC, DispatchPathComponent>()
        val endEdges = mutableSetOf<Pair<EVMPC, EVMPC>>()

        val visitedBlockStates = mutableMapOf<EVMPC, EVMState>()
        val workList = ArrayDeque<EVMState>().apply { add(EVMState.START) }

        val succ = treapMapBuilderOf<EVMPC, TreapSet<EVMPC>>()

        while (workList.isNotEmpty()) {
            val inState = workList.removeFirst()
            val block = inState.pc

            succ[block] = treapSetOf()

            var pc = block
            val stack = inState.stack.builder()
            var scratch = inState.scratch
            var dirty = inState.dirty

            fun push(v: Value) = stack.push(v)
            fun peek(num: Int) = stack.peek(num).last()
            fun pop() = stack.pop()
            fun branch(dest: EVMPC) = EVMState(dest, stack.build(), scratch, dirty)

            /** Write a value to memory.  We only track the first 32-bytes of memory, in `scratch`. */
            fun writeMemory(value: Value, offset: Value, size: Value) {
                logger.trace { "Writing memory[$offset:$size] = $value" }
                val offsetBytes = offset.exactValue
                val sizeBytes = size.exactValue
                if (offsetBytes == null || sizeBytes == null || offsetBytes + sizeBytes > `32`) {
                    dirty = true
                }
                scratch = when {
                    offsetBytes == null -> Value.Unknown
                    offsetBytes > `32` -> scratch
                    sizeBytes == null -> Value.Unknown
                    offsetBytes == `0` && sizeBytes == `32` -> value
                    scratch.exactValue == null -> Value.Unknown
                    else -> {
                        // How many bits should we shift the value to the left or right?  The value is a 32-byte integer
                        // With `sizeBytes` bytes in the least significant (rightmost) positions.  We need those bytes
                        // to land at `offsetBytes`.
                        val shift = (offsetBytes + sizeBytes - `32`) * `8`
                        // Generate a bitmask covering `sizeBytes` bytes in the least-significant (rightmost) positions.
                        val mask = MASK_SIZE(sizeBytes.safeAsInt() * 8).toValue(false)
                        // Mask off the parts of the existing scratch value that we won't be writing, and then write
                        // the new bits from `value`.
                        when {
                            shift < `0` -> {
                                val s = shift.negate().toValue(false)
                                (scratch and (mask shl s).not()) or (value shl s)
                            }
                            shift > `0` -> {
                                val s = shift.toValue(false)
                                (scratch and (mask shr s).not()) or (value shr s)
                            }
                            else -> value
                        }
                    }
                }
            }

            fun addSuccessor(succState: EVMState) {
                val prevInState = visitedBlockStates.put(succState.pc, succState)
                if (prevInState == null) {
                    workList.addFirst(succState)
                } else if (prevInState != succState) {
                    // We've seen this block before, with different inputs.  We don't expect complex control flow in
                    // dispatch code, so we don't do anything fancy here; we just mark this edge as ending the dispatch
                    // path
                    logger.debug { "Marking block $block as end of dispatch due to successor ${succState.pc}" }
                    endEdges.add(block to succState.pc)
                }
            }

            while (true) {
                val inst = bytecode.disassembledCode[pc]?.inst
                logger.trace { " -> [$scratch] $stack" }
                logger.trace { "$pc: $inst" }

                if (inst == null) {
                    logger.error("No instruction at PC $pc")
                    break
                }
                if (stack.size < inst.popCount) {
                    logger.error("Stack underflow at PC $pc: $inst")
                    break
                }
                if (stack.size + inst.pushCount - inst.popCount > 1024) {
                    logger.error("Stack overflow at PC $pc: $inst")
                    break
                }

                when (inst) {
                    STOP -> break

                    ADD -> push(pop() + pop())
                    MUL -> push(pop() * pop())
                    SUB -> push(pop() - pop())
                    DIV -> {
                        val a = pop()
                        val b = pop()
                        push(
                            when {
                                a == Value.CalldataZero && b.exactValue == `2^224` -> sighash
                                else -> a / b
                            }
                        )
                    }
                    MOD -> push(pop() % pop())
                    EXP -> push(pop() exp pop())

                    LT -> push(pop() lt pop())
                    GT -> push(pop() gt pop())

                    EQ -> {
                        val a = pop()
                        val b = pop()
                        push(
                            when(a isEqualTo b) {
                                true -> TRUE.toValue(a.fromSighash || b.fromSighash)
                                false -> FALSE.toValue(a.fromSighash || b.fromSighash)
                                null -> Value.Unknown
                            }
                        )
                    }

                    ISZERO -> {
                        val a = pop()
                        push(
                            when(a.isZero) {
                                true -> TRUE.toValue(a.fromSighash)
                                false -> FALSE.toValue(a.fromSighash)
                                null -> Value.Unknown
                            }
                        )
                    }

                    AND -> push(pop() and pop())
                    OR -> push(pop() or pop())
                    XOR -> push(pop() xor pop())
                    NOT -> push(pop().not())

                    SHL -> {
                        val shift = pop()
                        val value = pop()
                        push(value shl shift)
                    }

                    SHR -> {
                        val shift = pop()
                        val value = pop()
                        push(
                            when {
                                value is Value.CalldataZero && shift.exactValue == `224` -> sighash
                                else -> value shr shift
                            }
                        )
                    }

                    CALLDATALOAD -> {
                        val offset = pop()
                        push(
                            if (offset.isDefinitelyZero) {
                                Value.CalldataZero
                            } else {
                                Value.Unknown
                            }
                        )
                    }

                    CALLDATASIZE -> when {
                        sighash.exactValue != null -> push(Value.CalldataSizeForKnownMethod)
                        else -> push(Value.CalldataSizeForUnknownMethod)
                    }

                    CALLDATACOPY -> {
                        val destOffset = pop()
                        val offset = pop()
                        val size = pop()
                        val value = when {
                            offset.exactValue == `0` && size.exactValue == `4` -> sighash
                            else -> Value.Unknown
                        }
                        writeMemory(value, destOffset, size)
                    }

                    // Note: this may not be correct for constructor bytecode.  But we don't run this analysis on
                    // constructors.
                    CODESIZE -> push(validBytecodeOffsets.endExclusive.toValue(false))

                    CODECOPY -> {
                        val destOffset = pop()
                        val offset = pop()
                        val size = pop()
                        val value = when {
                            offset !is Value.UIntRange || size.exactValue == null -> Value.Unknown
                            offset.range.endInclusive + size.exactValue!! > validBytecodeOffsets.endExclusive -> {
                                logger.debug { "CODECOPY out of range: ${offset.range}+${size.exactValue} vs. $validBytecodeOffsets" }
                                Value.Unknown
                            }
                            offset.exactValue != null -> {
                                bytecode.bytes.toPositiveBigInteger(
                                    offset.exactValue!!.intValueExact(),
                                    size.exactValue!!.intValueExact()
                                ).toValue(offset.fromSighash)
                            }
                            else -> {
                                val start = offset.range.start.intValueExact()
                                val end = offset.range.endInclusive.intValueExact()
                                val offsets = start..end step size.exactValue!!.intValueExact()
                                Value.JumpTargets(
                                    offsets.mapToTreapSet {
                                        bytecode.bytes.toPositiveBigInteger(
                                            it,
                                            size.exactValue!!.intValueExact()
                                        )
                                    }
                                )
                            }
                        }
                        writeMemory(value, destOffset, size)
                    }

                    POP -> pop()

                    MLOAD -> {
                        val offset = pop()
                        if (offset.isDefinitelyZero) {
                            push(scratch)
                        } else {
                            push(Value.Unknown)
                        }
                    }

                    MSTORE -> {
                        val offset = pop()
                        val value = pop()
                        writeMemory(value, offset, `32`.toValue(false))
                    }

                    JUMP -> {
                        val dest = pop()
                        val jumpTargets = dest.toJumpTargets()
                        path[block] = DispatchPathComponent(
                            conditionalJumpTaken = null,
                            jumpTargets = jumpTargets.takeIf { dest.fromSighash }
                        )
                        jumpTargets.forEachElement {
                            addSuccessor(branch(it.safeAsInt()))
                        }
                        succ[block] = jumpTargets.mapToTreapSet { it.safeAsInt() }
                        logger.trace { "JUMP: ${path[block]} $path" }
                        break
                    }

                    JUMPI -> {
                        val dest = pop()
                        val cond = pop()
                        val jumpTaken = when {
                            !cond.fromSighash -> null
                            else -> cond.isNotZero
                        }
                        val jumpTargets = dest.toJumpTargets()
                        path[block] = DispatchPathComponent(
                            conditionalJumpTaken = jumpTaken.takeIf { cond.fromSighash },
                            jumpTargets = jumpTargets.takeIf { dest.fromSighash }
                        )
                        var blockSucc = treapSetOf<EVMPC>()
                        if (jumpTaken != false) {
                            jumpTargets.forEachElement {
                                addSuccessor(branch(it.safeAsInt()))
                            }
                            blockSucc += jumpTargets.mapToTreapSet { it.safeAsInt() }
                        }
                        if (jumpTaken != true) {
                            val outState = branch(pc + inst.bytecodeSize)
                            blockSucc += outState.pc
                            addSuccessor(outState)
                        }
                        succ[block] = blockSucc
                        logger.trace { "JUMPI: $dest $cond ${path[block]} $path" }
                        break
                    }

                    PC -> push(pc.toBigInteger().toValue(false))

                    is PushBase -> push(inst.value?.toValue(false) ?: Value.Unknown)
                    is DUP -> push(peek(inst.dupNum))

                    is SWAP -> {
                        val values = stack.pop(inst.swapNum + 1).toMutableList()
                        values.swap(0, values.lastIndex)
                        stack.pushAll(values.asReversed())
                    }

                    RETURN -> break
                    REVERT -> break
                    INVALID -> break
                    SELFDESTRUCT -> break

                    else -> {
                        repeat(inst.popCount) { pop() }
                        repeat(inst.pushCount) { push(Value.Unknown) }
                        if (inst.affectsMemory) {
                            writeMemory(Value.Unknown, Value.Unknown, Value.Unknown)
                            dirty = true
                        }
                        if (inst.affectsStorage || inst.affectsChain) {
                            dirty = true
                        }
                    }
                }

                pc = pc + inst.bytecodeSize

                if (bytecode.disassembledCode[pc]?.inst == JUMPDEST) {
                    val outState = branch(pc)
                    succ[block] = treapSetOf(outState.pc)
                    workList.addFirst(outState)
                    break
                }
            }
        }

        // For the hashtable dispatchers, the fallback code may end up with jumps having multiple possible targets,
        // depending on which invalid sighash value is supplied.  We try to resolve these to a single target, by finding
        // a single block that is eventually reached by any of the possible targets, and has the same inputs as the
        // actual jump targets, with interesting operations between here and there.  The idea is that this must be the
        // start of the real "fallback" code, and that it's safe to skip the intervening code because it doesn't affect
        // the machine state.  (Typically the skipped code is just comparing the sighash against some known values, and
        // then jumping to the fallback code).
        //
        // Another solution we have discussed is to encode the multiple jump targets in the TAC JumpCmd instruction
        // emitted by the decompiler, along with an AssumeCmd at each target telling the solvers how we got there. The
        // present approach was deemed to be simpler.
        val resolvedAmbiguousJumps = mutableMapOf<Set<BigInteger>, EVMPC?>()
        val postDom by lazy { SimpleDominanceAnalysis(reverse(succ)) }
        val closedSucc by lazy { transitiveClosure(succ, reflexive = true) }
        for (entry in path) {
            val (source, result) = entry
            val jumpTargets = result.jumpTargets?.takeIf { it.size > 1 }
            if (jumpTargets != null) {
                val resolved = resolvedAmbiguousJumps.computeIfAbsent(result.jumpTargets) {
                    // Find all blocks reachable from all of the possible jump targets
                    val commonSucc = result.jumpTargets.map { closedSucc[it.safeAsInt()].orEmpty() }.intersectAll() - source
                    // Reduce the CFG to only those blocks
                    val subGraph = succ.build().retainAllKeys { it in commonSucc }
                    // Find the root of the smaller CFG.  This is the "closest common block" for this jump.
                    val dest = findRoots(subGraph).singleOrNull()
                    val destIn = dest?.let { visitedBlockStates[it] }
                    val sourceOut = dest?.let { visitedBlockStates[jumpTargets.first().safeAsInt()]?.copy(pc = it) }
                    when {
                        dest == null -> {
                            logger.debug { "Ambiguous jump: $source -> $jumpTargets has no common destination" }
                            null
                        }
                        destIn == null || sourceOut == null -> {
                            logger.debug { "Unknown fallback state? $source -> $jumpTargets : $sourceOut -> $destIn" }
                            null
                        }
                        destIn != sourceOut || destIn.dirty -> {
                            logger.debug { "Side effects in dispatch code? $source -> $jumpTargets" }
                            null
                        }
                        // The destination must postdominate the source
                        !postDom.dominates(dest, source) -> {
                            logger.debug { "Multiple fallback paths? $source -> $jumpTargets has no single common destination" }
                            null
                        }
                        else -> dest
                    }
                }
                logger.trace { "Ambiguous jump: $source -> $jumpTargets -> $resolved" }
                if (resolved != null) {
                    // Update the path component with the resolved target.
                    entry.setValue(result.copy(jumpTargets = treapSetOf(resolved.toBigInteger())))
                }
            }
        }

        return Path(path, endEdges)
    }
}

