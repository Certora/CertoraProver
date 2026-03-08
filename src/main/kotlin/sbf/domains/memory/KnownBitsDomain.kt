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

package sbf.domains

import sbf.disassembler.*
import sbf.cfg.*
import datastructures.stdcollections.*
import log.*
import sbf.SolanaConfig
import sbf.callgraph.CVTCore
import sbf.callgraph.CVTFunction
import sbf.callgraph.SolanaFunction

private val logger = Logger(LoggerTypes.SBF_SCALAR_WITH_KNOWN_BITS)
private fun dbg(msg: () -> Any) { logger.info(msg) }

/** This option is for KnownBits widening **/
private const val useJoinAsWidening = true

/**
 * A known-bits abstract domain for tracking definite bit values in SBF registers
 * and stack locations. For each bit position, we track whether it is definitely 0,
 * definitely 1, or unknown. This is represented as two bitmasks: `zeros` for bits
 * known to be 0, and `ones` for bits known to be 1, with the invariant `zeros & ones == 0`.
 *
 * This domain is known as the "Bitfield domain" in A. Miné's "Abstract Domains for
 * Bit-Level Machine Integer and Floating-point Operations" (WING 2012).
 * We call it instead [KnownBitsDomain] as LLVM does.
**/
data class KnownBits (
    // Mask of bits known to be 1
    val zeros: ULong = 0UL,
    // Mask of bits known to be 0
    val ones: ULong = 0UL
) : StackEnvironmentValue<KnownBits> {

    companion object {
        fun fromConstant(x: ULong) = KnownBits(zeros = x.inv(), ones = x)
    }

    fun asConstant(): ULong? =
        if (zeros.or(ones) == ULong.MAX_VALUE) {
            // all bits are known
            ones
        } else {
            null
        }

    fun isZero(i: Byte) = (zeros shr i.toInt()).and(1UL) == 1UL

    fun isOne(i: Byte) = (ones shr i.toInt()).and(1UL) == 1UL

    // contradiction: some bit is both 0 and 1
    override fun isBottom(): Boolean = zeros.and(ones) != 0UL

    override fun isTop(): Boolean = ones == 0UL && zeros == 0UL

    override fun mkTop(): KnownBits = KnownBits(0UL,0UL)

    override fun join(other: KnownBits) = KnownBits(zeros.and(other.zeros), ones.and(other.ones))

    fun meet(other: KnownBits) = KnownBits(zeros.or(other.zeros), ones.or(other.ones))

    /**
     * KnownBits has finite height so we can use join as widening.
     * However, it's possible that join might converge slow so we also have a widening.
     */
    override fun widen(other: KnownBits): KnownBits {
        return if (useJoinAsWidening) {
            join(other)
        } else {
            // Any bit on which previous and current disagrees is considered unstable
            val unstable = zeros.xor(other.zeros).or(ones.xor(other.ones))
            // By calling `inv` (bit negation) we make sure that the unstable bits are set to 0
            KnownBits(
                zeros = zeros.and(unstable.inv()),
                ones = ones.and(unstable.inv())
            )
        }
    }

    override fun lessOrEqual(other: KnownBits): Boolean {
        // Every bit other knows to be 0, this must also know to be 0
        // Every bit other knows to be 1, this must also know to be 1
        return (other.zeros.and(zeros) == other.zeros) &&  (other.ones.and(ones) == other.ones)
    }

    /** bit is 0 if either operand has it 0; bit is 1 if both have it 1 **/
    fun band(other: KnownBits) =
        KnownBits(
            zeros =  zeros.or(other.zeros),
            ones =  ones.and(other.ones)
        )

    /** bit is 0 if both operands have 0; bit is 1 if either operand has 1 **/
    fun bor(other: KnownBits) =
        KnownBits(
            zeros = zeros.and(other.zeros),
            ones =  ones.or(other.ones)
        )

    /** bit is 0 if both are 0 or 1; bit is 1 if one is 0 and the other is 1 **/
    fun bxor(other: KnownBits) =
        KnownBits(
            zeros =  zeros.and(other.zeros).or(ones.and(other.ones)),
            ones =  zeros.and(other.ones).or(ones.and(other.zeros))
        )


    private fun bnot() = KnownBits(zeros = ones, ones = zeros)

    fun shl(shift: Byte): KnownBits {
        val shiftI = shift.toInt()
        return KnownBits(
            // low bits become 0
            zeros = (zeros shl shiftI).or((1UL shl shiftI) - 1UL),
            ones = ones shl shiftI
        )
    }

    fun lshr(shift: Byte): KnownBits {
        val shiftI = shift.toInt()
        // if shift=3  highMask = 11111...000.
        // That is, all bits become 0s after the right shift are 1s in highMask and the rest are 0s
        val highMask = ((1UL shl (64 - shiftI)) - 1UL).inv()
        return KnownBits(
            zeros = (zeros shr shiftI).or(highMask),
            ones = ones shr shiftI

        )
    }

    fun ashr(shift: Byte): KnownBits {
        val signBit: Byte = 63
        val shiftI = shift.toInt()
        return when {
            isOne(signBit) -> {
                val highMask = ((1UL shl (64 - shiftI)) - 1UL).inv()
                KnownBits(
                    zeros =  zeros shr shiftI,
                    ones =  (ones shr shiftI).or(highMask)
                )
            }
            isZero(signBit) -> {
                lshr(shift)
            }
            else -> {
                KnownBits (
                    zeros = zeros shr shiftI,
                    ones = ones shr shiftI
                )
            }
        }
    }

    private fun setBitZero(i: Byte): KnownBits {
        val iI = i.toInt()
        return KnownBits (
            zeros = zeros.or(1UL shl iI),
            ones = ones.and((1UL shl iI).inv())
        )
    }

    private fun setBitOne(i: Byte): KnownBits {
        val iI = i.toInt()
        return KnownBits (
            zeros = zeros.and((1UL shl iI).inv()),
            ones = ones.or(1UL shl iI)
        )
    }

    private fun setBitUnknown(i: Byte): KnownBits {
        val iI = i.toInt()
        return KnownBits (
            zeros = zeros.and((1UL shl iI).inv()),
            ones = ones.and((1UL shl iI).inv())
        )
    }

    /// Add 1 with carry ripple. Carry starts at 1 (definitely).
    /// - known-zero bit absorbs the carry: flips to 1, carry out is 0 (done)
    /// - known-one bit passes the carry: flips to 0, carry out is 1
    /// - unknown bit: result and carry-out both become unknown (stop)
    private fun addOne(): KnownBits {
        if (isTop()) {
            return this
        }

        var out = this
        for (i in 0..63) {
            val iB = i.toByte()
            if (isZero(iB)) {
                // Absorb carry: bit flips to 1, carry dies
                out = out.setBitOne(iB)
                return out
            } else if (isOne(iB)) {
                // Pass carry: bit flips to 0, carry propagates
                out = out.setBitZero(iB)
            } else {
                // Unknown bit: carry outcome unknown, everything from here up unknown
                for (j in i..63) {
                    out = out.setBitUnknown(j.toByte())
                }
                return out
            }
        }
        return out
    }

    private fun ULong.countTrailingOneBits() = inv().countTrailingZeroBits()

    /**
     * Imprecise version where we keep low bits if they are known to be zeros,
     * because they don't carry
     **/
    fun add(other: KnownBits): KnownBits {
        val resultTz = zeros.countTrailingOneBits()
            .coerceAtMost(other.zeros.countTrailingOneBits())
        var result = KnownBits()
        for (i in 0 until resultTz) {
            result = result.setBitZero(i.toByte())
        }
        return result
    }

    /**
     * Imprecise version following same idea as [add]
     *
     * Given `x*y``
     *
     * If x has `a` trailing zeros then x = `(2^a + c)`
     * Similarly, `y =  (2^b + d)`. Then `x * y = (2^a + c) * (2^b + d) = 2^{a+b} * (c*d)`
     * Thus, the trailing zeros are added in this case.
     **/
    fun mul(other: KnownBits): KnownBits {
        val resultTz = (zeros.countTrailingOneBits() + other.zeros.countTrailingOneBits())
            .coerceAtMost(64)
        var result = KnownBits()
        for (i in 0 until resultTz) {
            result = result.setBitZero(i.toByte())
        }
        return result
    }

    fun sub(other: KnownBits): KnownBits {
        return add(other.neg())
    }

    /**
     * Modular negation
     *
     * ```
     *   neg(x) = if x == Long.MIN_VALUE then x else -x
     * ```
     *
     * This can be simplified to `0-x` because if `x=MIN_VALUE` then `-x` will wraparound and
     * produce the correct result.
     *
     * Another implementation is `bnot(x) + 1` which exactly how two's complement is implemented.
     **/
    fun neg(): KnownBits {
        return bnot().addOne()
    }

    private fun widthMask(bytes: Int): ULong {
        return when(bytes) {
            1 -> 0x0000_0000_0000_00FF.toULong()
            2 -> 0x0000_0000_0000_FFFF.toULong()
            4 -> 0x0000_0000_FFFF_FFFF.toULong()
            else -> error("unreachable")
        }
    }


    fun truncate(dst: Byte) =
        when (val dstI = dst.toInt()) {
            1, 2, 4 -> {
                val mask =  widthMask(dstI)
                KnownBits (
                    zeros = zeros.and(mask), // keep known 0s in low bytes, clear high ones
                    ones = ones.and(mask)  // keep known 1s in low bytes, clear high ones
                )
            }
            8 -> this
            else -> error("unreachable")
        }


    fun zeroExtend(dst: Byte) =
        when (val dstI = dst.toInt()) {
            1, 2, 4 -> {
                val mask =  widthMask(dstI)
                KnownBits (
                    zeros = zeros.or(mask.inv()), // high bits are always zero
                    ones = ones.and(mask)  // keep known 1s in low bytes, clear high ones
                )
            }
            8 -> this
            else -> error("unreachable")
        }

    override fun toString(): String {
        if (isBottom()) {
            return "bot"
        }

        if (isTop()) {
            return "*"
        }

        asConstant()?.let {
            return it.toString()
        }

        return (63 downTo 0).map { i ->
            val iB = i.toByte()
            when {
                isOne(iB) -> '1'
                isZero(iB) -> '0'
                else -> '*'
            }
        }.joinToString("")
    }
}


/** Required by [KnownBitsDomain] to be able to use [ScalarBaseDomain] **/
object KnownBitsFactory: IScalarValueFactory<KnownBits> {
    override fun mkTop() = KnownBits()
}

/**
 * For each register and stack location keeps track of their known bits (see [KnownBits]).
 * This domain does know if a register/stack location is a pointer or a number.
 *
 * This domain relies heavily on a scalar domain (see `getKnownBits`) to reconstruct known bits
 * from registers/stack locations.
 */
class KnownBitsDomain(
    private val base: ScalarBaseDomain<KnownBits>,
    val globalState: GlobalState
)  {

    companion object {
        fun makeBottom(globalState: GlobalState) =
            KnownBitsDomain(ScalarBaseDomain.makeBottom(KnownBitsFactory), globalState)
        fun makeTop(globalState: GlobalState) =
            KnownBitsDomain(ScalarBaseDomain.makeTop(KnownBitsFactory), globalState)
    }

    fun deepCopy() = KnownBitsDomain(base.deepCopy(), globalState)

    fun isBottom() = base.isBottom()

    fun isTop() = base.isTop()

    fun join(other: KnownBitsDomain) =  KnownBitsDomain(base.join(other.base), globalState)

    fun widen(other: KnownBitsDomain) = KnownBitsDomain(base.widen(other.base), globalState)

    fun lessOrEqual(other: KnownBitsDomain) = base.lessOrEqual(other.base)

    fun setToBottom() {
        base.setToBottom()
    }

    override fun toString(): String = base.toString(includeScratchRegs = false)

    fun getRegister(reg: Value.Reg) = base.getRegister(reg)

    fun setRegister(reg: Value.Reg, v: KnownBits) {
        base.setRegister(reg, v)
    }

    fun forget(reg: Value.Reg) {
        base.forget(reg)
    }

    fun forget(regs: Iterable<Value.Reg>): KnownBitsDomain {
        val out = deepCopy()
        regs.forEach { reg-> out.forget(reg) }
        return out
    }

    private fun<TNum, TOffset, ScalarDomain> getKnownBits(
        v: Value,
        scalars: ScalarDomain
    ): KnownBits
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              ScalarDomain: ScalarValueProvider<TNum, TOffset> {
        when (v) {
            is Value.Imm -> return KnownBits.fromConstant(v.v)
            is Value.Reg -> {
                val knownBits = getRegister(v)
                val scalarVal = (scalars.getAsScalarValue(v).type() as? SbfType.NumType)?.value?.toLongOrNull()
                    ?: return knownBits
                check(!knownBits.isBottom())
                val res = knownBits.meet(KnownBits.fromConstant(scalarVal.toULong()))
                check(!res.isBottom()) {
                    "meet of $scalarVal (${KnownBits.fromConstant(scalarVal.toULong())}) and $knownBits (from $v) is bottom"
                }
                return res
            }
        }
    }

    private fun<TNum, TOffset, ScalarDomain> analyzeUn(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Un)

        if (isBottom()) {
            return
        }

        val lhs = inst.dst
        when(inst.op) {
         UnOp.NEG ->
             setRegister(lhs, getKnownBits(lhs, scalars).neg())
         UnOp.BE16,
         UnOp.BE32,
         UnOp.BE64,
         UnOp.LE16,
         UnOp.LE32,
         UnOp.LE64 ->
             forget(lhs)
        }
    }

    private fun<TNum, TOffset, ScalarDomain> analyzeBin(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        if (isBottom()) {
            return
        }

        val lhs = inst.dst
        val rhs = inst.v

        when (inst.op) {
            BinOp.MOV -> {
                when (rhs) {
                    is Value.Reg -> setRegister(lhs, getRegister(rhs))
                    is Value.Imm -> setRegister(lhs, KnownBits.fromConstant(rhs.v))
                }
            }
            BinOp.DIV,
            BinOp.MOD -> {
                forget(lhs)
            }
            BinOp.ADD,
            BinOp.SUB,
            BinOp.MUL,
            BinOp.OR,
            BinOp.AND,
            BinOp.XOR -> {
                val x = getKnownBits(lhs, scalars)
                val y = getKnownBits(rhs, scalars)
                val res = when (inst.op) {
                    BinOp.ADD -> x.add(y)
                    BinOp.SUB -> x.sub(y)
                    BinOp.MUL -> x.mul(y)
                    BinOp.OR  -> x.bor(y)
                    BinOp.AND -> x.band(y)
                    BinOp.XOR -> x.bxor(y)
                    else -> error("unreachable")
                }
                setRegister(lhs, res)
            }
            BinOp.LSH, BinOp.RSH, BinOp.ARSH -> {
                val shift = when (rhs) {
                    is Value.Imm ->
                        rhs.v
                    is Value.Reg ->
                        (scalars.getAsScalarValue(rhs).type() as? SbfType.NumType)
                            ?.value?.toLongOrNull()?.toULong()
                }
                if (shift == null || shift > 64UL) {
                    forget(lhs)
                    return
                }

                val shiftB = shift.toByte()
                val x = getKnownBits(lhs, scalars)
                val res = when (inst.op) {
                    BinOp.LSH -> x.shl(shiftB)
                    BinOp.RSH -> x.lshr(shiftB)
                    BinOp.ARSH -> x.ashr(shiftB)
                    else -> error("unreachable")
                }
                setRegister(lhs, res)
            }

        }
    }

    /** Return true iff [locInst] is a call to a CVT function **/
    private fun<TNum, TOffset, ScalarDomain> analyzeCVTCall(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
        memSummaries: MemorySummaries
    ): Boolean
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val cvtFunction = CVTFunction.from(inst.name)  ?: return false
        when (cvtFunction) {
            is CVTFunction.Core -> {
                when (cvtFunction.value) {
                    CVTCore.SAVE_SCRATCH_REGISTERS -> {
                        base.saveScratchRegisters()
                        return true
                    }
                    CVTCore.RESTORE_SCRATCH_REGISTERS -> {
                        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
                        val topStack = (scalars.getAsScalarValue(r10).type() as? SbfType.PointerType.Stack)
                            ?.offset?.toLongOrNull()
                        check(topStack != null){
                            "r10 should point to a statically known stack offset"
                        }
                        base.restoreScratchRegisters(topStack, globalState.globals.elf.useDynamicFrames())
                        return true
                    }
                    else -> {}
                }
            }
            else -> {}
        }
        base.analyzeExternalCall(locInst, scalars, memSummaries)
        return true
    }

    /** return true iff the [locInst] is a Solana syscall **/
    private fun<TNum, TOffset, ScalarDomain> analyzeSolanaCall(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
        memSummaries: MemorySummaries
    ): Boolean
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val solFunction = SolanaFunction.from(inst.name) ?: return false
        when (solFunction) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMCPY_ZEXT,
            SolanaFunction.SOL_MEMCPY_TRUNC,
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMSET -> {
                // Calling the base domain is very conservative because it will havoc the destination.
                // We will probably need to implement more precise transfer functions for them.
                base.analyzeMemIntrinsics(locInst, scalars)
            }
            else -> base.analyzeExternalCall(locInst, scalars, memSummaries)
        }
        return true
    }


    private fun<TNum, TOffset, ScalarDomain> analyzeCall(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
        globalState: GlobalState
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val memSummaries = globalState.memSummaries

        if (analyzeCVTCall(locInst, scalars, memSummaries)) {
            return
        }
        if (analyzeSolanaCall(locInst, scalars, memSummaries)) {
            return
        }
        base.analyzeExternalCall(locInst, scalars, memSummaries)
    }

    private fun<TNum, TOffset, ScalarDomain> analyzeStore(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Mem && !inst.isLoad)

        val width = inst.access.width.toByte()
        val baseReg = inst.access.base
        val offset = inst.access.offset.toLong()

        when (val res = resolveStackAccess(baseReg, offset, scalars)) {
            is StackAccessResolution.UnknownBase -> {
                if (!SolanaConfig.optimisticScalarAnalysis()) {
                    base.removeStack()
                } else {
                     // do nothing because with this flag enabled we assume that the store cannot affect
                     // any live stack location
                }
            }
            is StackAccessResolution.NonStack -> {
                /* do nothing */
            }
            is StackAccessResolution.UnknownOffset -> {
                base.removeStack()
            }
            is StackAccessResolution.KnownOffsets -> {
                if (res.offsets.size == 1) {
                    val stackOffset = res.offsets.first()
                    strongUpdateStack(ByteRange(stackOffset, width), inst.value)
                } else {
                    // conservative: we could do weak updates.
                    res.offsets.forEach {
                        base.removeStackSliceIf(it, width.toLong(), onlyPartial = false)
                    }
                }
            }
        }
    }

    /** Strong update a stack slot with [KnownBits] **/
    private fun strongUpdateStackImpl(bytes: ByteRange, knownBits: KnownBits) {
        // Remove overlaps
        base.removeStackSliceIf(bytes.offset, bytes.width.toLong(), onlyPartial = false)
        // if knownBits is top then will remove bytes from the stack, otherwise it will overwrite it
        base.updateStack(bytes, knownBits, isWeak = false)
    }

    private fun strongUpdateStack(bytes: ByteRange, value: Value) {
        val storedKnownBits = when (value) {
            is Value.Imm -> KnownBits.fromConstant(value.v)
            is Value.Reg -> base.getRegister(value)
        }.truncate(bytes.width)

        strongUpdateStackImpl(bytes, storedKnownBits)
    }

    private fun<TNum, TOffset, ScalarDomain> analyzeLoad(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val inst = locInst.inst
        check(inst is SbfInstruction.Mem && inst.isLoad)

        val lhs = inst.value as Value.Reg
        val width = inst.access.width.toByte()
        val baseReg = inst.access.base
        val offset = inst.access.offset.toLong()

        val stackOffset = when (val res = resolveStackAccess(baseReg, offset, scalars)) {
            is StackAccessResolution.UnknownBase,
            is StackAccessResolution.NonStack,
            is StackAccessResolution.UnknownOffset -> {
                forget(lhs)
                return
            }
            is StackAccessResolution.KnownOffsets -> {
                if (res.offsets.size != 1) {
                    forget(lhs)
                    return
                }
                res.offsets.first()
            }
        }

        val slice = ByteRange(stackOffset, width)
        val x = base.getStackSingletonOrNull(slice)
        if (x == null) {
            // We look at any store at the same offset but with a different width:
            // - zero-extend if the store was done with a smaller width, or
            // - truncate if the store was done with a bigger width.

            for (smallerWidth in listOf(1, 2, 4, 8).filter { it < width}) {
                val smallerSlice = slice.copy(width = smallerWidth.toByte())
                val knownBits = base.getStackSingletonOrNull(smallerSlice) ?: continue
                base.setRegister(lhs, knownBits.zeroExtend(width))
                return
            }

            for (biggerWidth in listOf(1, 2, 4, 8).filter { it > width}) {
                val smallerSlice = slice.copy(width = biggerWidth.toByte())
                val knownBits = base.getStackSingletonOrNull(smallerSlice) ?: continue
                base.setRegister(lhs, knownBits.truncate(width))
                return
            }

            forget(lhs)

        } else {
            // If we are here is because this load matches a store.
            // When we did the store, we truncate the stored value.
            // Thus, it's safe to take it directly.
            base.setRegister(lhs, x)
        }
    }

    private fun<TNum, TOffset, ScalarDomain> analyzeAssume(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        if (isBottom()) {
            return
        }

        val cond = when (val inst = locInst.inst) {
            is SbfInstruction.Assume -> { inst.cond }
            is SbfInstruction.Assert -> { inst.cond }
            else -> { error("calling analyzeAssume with $inst")}
        }

        when (cond.op) {
            CondOp.EQ -> {
                val left = cond.left
                val right = cond.right
                val x = getKnownBits(left, scalars)
                val y = getKnownBits(right, scalars)
                val meet = x.meet(y)
                if (meet.isBottom()) {
                    setToBottom()
                } else {
                    setRegister(left, meet)
                    (right as? Value.Reg)?.let {
                        setRegister(it, meet)
                    }
                }
            }
            // If both arguments are constant and op=NE then the reduced product with a scalar domain
            // will take care of it.
            else -> {
                /* do nothing is sound */
            }
        }
    }

    fun<TNum, TOffset, ScalarDomain> analyze(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
        globalState: GlobalState
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            ScalarDomain: ScalarValueProvider<TNum, TOffset> {

        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> analyzeUn(locInst, scalars)
                is SbfInstruction.Bin -> analyzeBin(locInst, scalars)
                is SbfInstruction.Call -> analyzeCall(locInst, scalars, globalState)
                is SbfInstruction.CallReg -> {}
                is SbfInstruction.Select -> forget(s.dst)
                is SbfInstruction.Havoc -> forget(s.dst)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume,
                is SbfInstruction.Assert -> analyzeAssume(locInst, scalars)
                is SbfInstruction.Mem -> {
                    if (s.isLoad) {
                        analyzeLoad(locInst, scalars)
                    } else {
                        analyzeStore(locInst, scalars)
                    }
                }
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
                is SbfInstruction.Debug -> {}
            }
        }
    }

    fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> setScalarValue(
        reg: Value.Reg,
        scalarVal: ScalarValue<TNum, TOffset>
    ) {
        val immVal = (scalarVal.type() as? SbfType.NumType)?.value?.toLongOrNull()
        if (immVal == null) {
            forget(reg)
        } else {
            setRegister(reg, KnownBits.fromConstant(immVal.toULong()))
        }
    }

    fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> setStackContent(
        offset: Long,
        width: Byte,
        scalarVal: ScalarValue<TNum, TOffset>
    ) {
        val immVal = (scalarVal.type() as? SbfType.NumType)?.value?.toLongOrNull()
        val storedKnownBits = if (immVal == null) {
            KnownBits()
        } else {
            KnownBits.fromConstant(immVal.toULong())
        }
        strongUpdateStackImpl(ByteRange(offset, width), storedKnownBits)
    }
}

class ScalarKnownBitsDomainFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
    : IScalarDomainFactory<TNum, TOffset, ScalarKnownBitsDomain<TNum, TOffset>> {
    override fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState) =
        ScalarKnownBitsDomain.makeTop(fac, globalState)
    override fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState) =
        ScalarKnownBitsDomain.makeBottom(fac, globalState)
    override fun init(
        fac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        addPreconditions: Boolean
    ) = ScalarKnownBitsDomain(fac, globalState, addPreconditions)
}

/** Reduced product of a scalar domain  and [KnownBitsDomain] **/
class ScalarKnownBitsDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> private constructor(
        private val scalars: KnownBitsScalarDom<TNum, TOffset>,
        private val knownBits: KnownBitsDomain,
        private val globalState: GlobalState
):  MutableAbstractDomain<ScalarKnownBitsDomain<TNum, TOffset>>,
    ScalarValueProvider<TNum, TOffset>,
    MutableScalarValueUpdater<TNum, TOffset>,
    MemoryDomainScalarOps<TNum, TOffset> {


    constructor(
        sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        initPreconditions: Boolean = false):
        this(
            KnownBitsScalarDom(sbfTypeFac, globalState, initPreconditions),
            KnownBitsDomain.makeTop(globalState),
            globalState
        )

    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeBottom(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState,
        ) = ScalarKnownBitsDomain(
                KnownBitsScalarDom.makeBottom(sbfTypeFac, globalState),
                KnownBitsDomain.makeBottom(globalState),
                globalState
            )

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeTop(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState
        ) = ScalarKnownBitsDomain(sbfTypeFac, globalState)
    }

    override fun deepCopy() =
        ScalarKnownBitsDomain(
            scalars.deepCopy(),
            knownBits.deepCopy(),
            globalState
        )

    override fun isBottom() = scalars.isBottom() || knownBits.isBottom()

    override fun isTop() = scalars.isTop() && knownBits.isTop()

    override fun setToBottom() {
        scalars.setToBottom()
        knownBits.setToBottom()
    }

    override fun join(other: ScalarKnownBitsDomain<TNum, TOffset>, left: Label?, right: Label?) =
        if (isBottom()) {
            other.deepCopy()
        } else if (other.isBottom()) {
            deepCopy()
        } else {
            ScalarKnownBitsDomain(
                scalars.join(other.scalars, left, right),
                knownBits.join(other.knownBits),
                globalState
            )
        }

    override fun widen(other: ScalarKnownBitsDomain<TNum, TOffset>, b: Label?) =
        if (isBottom()) {
            other.deepCopy()
        } else if (other.isBottom()) {
            deepCopy()
        } else {
            ScalarKnownBitsDomain(
                scalars.widen(other.scalars, b),
                knownBits.widen(other.knownBits),
                globalState
            )
        }


    override fun lessOrEqual(other: ScalarKnownBitsDomain<TNum, TOffset>, left: Label?, right: Label?) =
        scalars.lessOrEqual(other.scalars, left, right) && knownBits.lessOrEqual(other.knownBits)

    override fun pseudoCanonicalize(
        other: ScalarKnownBitsDomain<TNum, TOffset>
    ) = ScalarKnownBitsDomain(
            scalars.pseudoCanonicalize(other.scalars),
            knownBits.deepCopy(),
            globalState
        )

    /** TRANSFER FUNCTIONS **/

    override fun forget(reg: Value.Reg) {
        if (isBottom()) {
            return
        }
        scalars.forget(reg)
        knownBits.forget(reg)
    }

    override fun forget(regs: Iterable<Value.Reg>): ScalarKnownBitsDomain<TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        return ScalarKnownBitsDomain(
            scalars.forget(regs),
            knownBits.forget(regs),
            globalState
        )
    }

    override fun getAsScalarValue(value: Value) =
        scalars.getAsScalarValue(value)

    override fun getStackContent(offset: Long, width: Byte) =
        scalars.getStackContent(offset, width)

    override fun mayStackBeInitialized(offset: Long, size: ULong) =
        scalars.mayStackBeInitialized(offset, size)

    override fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg) =
        scalars.getAsScalarValueWithNumToPtrCast(reg)

    override fun getTypeFac() = scalars.getTypeFac()

    override fun setScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>) {
        if (isBottom()) {
            return
        }

        scalars.setScalarValue(reg, newVal)
        knownBits.setScalarValue(reg, newVal)
    }

    override fun setStackContent(offset: Long, width: Byte, value: ScalarValue<TNum, TOffset>) {
        if (isBottom()) {
            return
        }
        scalars.setStackContent(offset, width, value)
        knownBits.setStackContent(offset, width, value)
    }

    /**
     * Update [scalars] if [knownBits] knows some fact that [scalars] does not know
     * The reduction only refines numbers
     **/
    private fun refineWithConstants(
        locInst: LocatedSbfInstruction,
        scalars: KnownBitsScalarDom<TNum, TOffset>,
        knownBits: KnownBitsDomain
    ) {

        if (isBottom()) {
            return
        }

        fun refineConditionWithConstants(cond: Condition) {
            for (reg in cond.readRegisters) {
                // We propagate info from known bits to scalar domain
                when (val regTy = scalars.getAsScalarValue(reg).type()) {
                    is SbfType.Top, is SbfType.NonStack -> { /* refine */}
                    is SbfType.NumType -> {
                        if (!regTy.value.isTop()) {
                            continue // do not refine
                        } else {
                            /* refine */
                        }
                    }
                    is SbfType.PointerType -> continue // do not refine
                    is SbfType.Bottom -> error("unreachable")
                }

                val y = knownBits.getRegister(reg).asConstant() ?: continue
                scalars.setScalarValue(reg, ScalarValue(scalars.getTypeFac().toNum(y)))
            }
        }

        when(val inst = locInst.inst) {
            is SbfInstruction.Assume ->
                refineConditionWithConstants(inst.cond)
            is SbfInstruction.Assert ->
                refineConditionWithConstants(inst.cond)
            else -> {}
        }

    }

    /**
     * Transfer function for scalar domain x known-bits domain
     *
     * Currently, the reduction goes only one direction: from scalars to known bits.
     **/
    fun analyze(locInst: LocatedSbfInstruction) {
        val inst = locInst.inst
        dbg { "$inst\n" }
        if (!isBottom()) {
            // Note that locInst can be something like `r1 = r1 and r2`
            // In that case knownBits must use the value of `r1` before the scalar transfer function is applied.
            knownBits.analyze(locInst, scalars, globalState)   // reduction from scalars to knownBits
            // Important: refineWithConstants only refine on assume/assert instructions.
            // If the code changes, and it can also refine Bin Or Un instructions then we need to copy
            // knownBits before changes.
            refineWithConstants(locInst, scalars, knownBits)  // reduction from knownBits to scalars
            scalars.analyze(locInst)
        }
        dbg { "$this\n" }
    }

    override fun analyze(
        b: SbfBasicBlock,
        listener: InstructionListener<ScalarKnownBitsDomain<TNum, TOffset>>
    ): ScalarKnownBitsDomain<TNum, TOffset> =
        analyzeBlockMut(
            domainName = "ScalarDomain x KnownBitsDomain",
            b,
            inState = this,
            transferFunction = { mutState, locInst ->
                mutState.analyze(locInst)
            },
            listener
        )

    override fun toString() =
        when {
            isBottom() -> "bottom"
            knownBits.isTop() -> "$scalars"
            else -> "(scalars=$scalars,known-bits=$knownBits)"
        }

}
