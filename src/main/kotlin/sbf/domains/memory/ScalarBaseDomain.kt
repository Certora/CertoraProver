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
import sbf.support.*
import datastructures.stdcollections.*
import sbf.callgraph.SolanaFunction

/** For internal errors **/
class ScalarDomainError(msg: String): SolanaInternalError("ScalarDomain error: $msg")

interface ScalarValueFactory<ScalarValue> {
    fun mkTop(): ScalarValue
}

/**
 * Base class that contains lattice operations and some helpers to build scalar domains.
 *
 * This generic scalar domain consists of:
 *
 * - regular registers `r0,r1,...,r10` where each register is mapped to [ScalarValue]
 * - scratch registers where each register is mapped to [ScalarValue].
 *   This is a stack whose size is multiple of 4 which is the number of scratch registers.
 * - stack where each location is mapped to [ScalarValue]
 */
class ScalarBaseDomain<ScalarValue>(
    private var isBot: Boolean, /* to represent error or unreachable state */
    private val sFac: ScalarValueFactory<ScalarValue>,
    private var stack: StackEnvironment<ScalarValue>,
    private val registers: ArrayList<ScalarValue>,
    private val scratchRegisters: ArrayList<ScalarValue>

) where ScalarValue: StackEnvironmentValue<ScalarValue> {

    init {
        check(registers.all {!it.isBottom()}) {"ScalarBaseDomain does not expect bottom register"}
        check(scratchRegisters.all {!it.isBottom()}) {"ScalarBaseDomain does not expect bottom scratch register"}
    }

    constructor(sFac: ScalarValueFactory<ScalarValue>):
        this(isBot = false, sFac,
            StackEnvironment.makeTop(),
            ArrayList(NUM_OF_SBF_REGISTERS),
            arrayListOf()) {
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            registers.add(sFac.mkTop())
        }
    }

    companion object {
        fun <ScalarValue: StackEnvironmentValue<ScalarValue>> makeBottom(sFac: ScalarValueFactory<ScalarValue>): ScalarBaseDomain<ScalarValue> {
            return ScalarBaseDomain(isBot = true, sFac,
                StackEnvironment.makeBottom(),
                arrayListOf(),
                arrayListOf()
            )
        }

        fun <ScalarValue: StackEnvironmentValue<ScalarValue>> makeTop(sFac: ScalarValueFactory<ScalarValue>): ScalarBaseDomain<ScalarValue> {
            return ScalarBaseDomain(sFac)
        }
    }

    fun deepCopy(): ScalarBaseDomain<ScalarValue> {
        val outRegisters = ArrayList<ScalarValue>(NUM_OF_SBF_REGISTERS)
        val outScratchRegs = ArrayList<ScalarValue>(scratchRegisters.size)

        registers.forEach { outRegisters.add(it) }
        scratchRegisters.forEach { outScratchRegs.add(it) }
        return ScalarBaseDomain(isBot, sFac, stack, outRegisters, outScratchRegs)
    }

    /** Lattice operations **/

    fun isBottom() = isBot

    fun isTop() = !isBottom() && stack.isTop() && registers.all {it.isTop()}

    fun setToBottom() {
        isBot = true
        stack = StackEnvironment.makeBottom()
        registers.clear()
        scratchRegisters.clear()
    }

    private fun joinOrWiden(
        other: ScalarBaseDomain<ScalarValue>,
        mergeRegister: (left: ScalarValue, right: ScalarValue) -> ScalarValue,
        mergeStack: (left: StackEnvironment<ScalarValue>, right: StackEnvironment<ScalarValue>) -> StackEnvironment<ScalarValue>
    ): ScalarBaseDomain<ScalarValue> {
        return if (isBottom()) {
            other.deepCopy()
        } else if (other.isBottom()) {
            deepCopy()
        } else if (isTop() || other.isTop()) {
            makeTop(sFac)
        } else {
            if (scratchRegisters.size != other.scratchRegisters.size) {
                throw ScalarDomainError("joinOrWiden failed because disagreement on the number of scratch registers")
            }

            val outRegisters = ArrayList<ScalarValue>(NUM_OF_SBF_REGISTERS)
            registers.forEachIndexed { i, it ->
                outRegisters.add(mergeRegister(it, other.registers[i]))
            }
            val outScratchRegs = ArrayList<ScalarValue>(scratchRegisters.size)
            scratchRegisters.forEachIndexed { i, it ->
                outScratchRegs.add(mergeRegister(it, other.scratchRegisters[i]))
            }
            ScalarBaseDomain(isBot = false, sFac,
                mergeStack(stack, other.stack),
                outRegisters,
                outScratchRegs
            )
        }
    }

    fun join(other: ScalarBaseDomain<ScalarValue>) =  joinOrWiden(other, {x, y-> x.join(y)}, {x, y-> x.join(y)})

    fun widen(other: ScalarBaseDomain<ScalarValue>) = joinOrWiden(other, {x, y-> x.widen(y)}, {x, y-> x.widen(y)})

    fun lessOrEqual(other: ScalarBaseDomain<ScalarValue>): Boolean {
        if (other.isTop() || isBottom()) {
            return true
        } else if (other.isBottom() || isTop()) {
            return false
        } else {
            if (scratchRegisters.size != other.scratchRegisters.size) {
                throw ScalarDomainError("lessOrEqual failed because disagreement on the number of scratch registers")
            }

            registers.forEachIndexed { i, it ->
                if (!it.lessOrEqual(other.registers[i])) {
                    return false
                }
            }
            if (!stack.lessOrEqual(other.stack)) {
                return false
            }
            scratchRegisters.forEachIndexed{ i, it ->
                if (!it.lessOrEqual(other.scratchRegisters[i])) {
                    return false
                }
            }
        }
        return true
    }

    override fun toString(): String {
        return when {
            isBottom() -> "bottom"
            isTop() -> "top"
            else -> {
                val nonTopRegs = registers.mapIndexedNotNull { i, scalarValue ->
                    if (!scalarValue.isTop()) {
                        Value.Reg(SbfRegister.getByValue(i.toByte())) to scalarValue
                    } else {
                        null
                    }
                }

                val regsString = nonTopRegs.joinToString(",") { (reg, scalarVal) ->
                    "$reg->$scalarVal"
                }

                "(Regs={$regsString},ScratchRegs=$scratchRegisters,Stack=$stack)"
            }
        }
    }

    /** helpers for transfer functions **/

    private fun getIndex(reg: Value.Reg): Int {
        val idx = reg.r.value.toInt()
        if (idx in 0 until NUM_OF_SBF_REGISTERS) {
            return idx
        }
        throw ScalarDomainError("register $idx out-of-bounds")
    }

    fun getRegister(reg: Value.Reg): ScalarValue {
        check(!isBottom()) {"Unexpected getRegister on bottom"}
        return registers[getIndex(reg)]
    }

    /** Return false if `this` becomes bottom **/
    fun setRegister(reg: Value.Reg, value: ScalarValue): Boolean {
       return setRegister(getIndex(reg), value)
    }

    private fun setRegister(i: Int, value: ScalarValue): Boolean {
        check(!isBottom()) {"Unexpected setRegister on bottom"}
        check(i >=0 && i < registers.size)
        return if (value.isBottom()) {
            setToBottom()
            false
        } else {
            registers[i] = value
            true
        }
    }


    private fun pushScratchReg(v: ScalarValue) {
        scratchRegisters.add(v)
    }

    private fun popScratchReg(): ScalarValue {
        if (scratchRegisters.isEmpty()) {
            throw ScalarDomainError("stack of scratch registers cannot be empty")
        }
        return scratchRegisters.removeLast()
    }

    private fun isDeadOffset(offset: Long, topOfStack: Long, useDynFrames: Boolean) =
        if (useDynFrames) {
            offset < topOfStack
        } else {
            offset > topOfStack
        }

    private fun removeDeadStackFields(topStack: Long, useDynFrames: Boolean) {
        val deadFields = ArrayList<ByteRange>()
        for ((k, _) in stack) {
            if (isDeadOffset(k.offset, topStack, useDynFrames)) {
                deadFields.add(k)
            }
        }
        while (deadFields.isNotEmpty()) {
            val k = deadFields.removeLast()
            stack = stack.put(k, sFac.mkTop())
        }
    }

    /** Transfer function for `__CVT_save_scratch_registers` **/
    fun saveScratchRegisters() {
        check(!isBottom()) {"Unexpected saveScratchRegisters on bottom"}

        if (!isTop()) {
            pushScratchReg(registers[6])
            pushScratchReg(registers[7])
            pushScratchReg(registers[8])
            pushScratchReg(registers[9])
        }
    }
    /**
     *  Transfer function for `__CVT_restore_scratch_registers`
     *  Invariant ensured by CFG construction: `r10` has been decremented already
     **/
    fun restoreScratchRegisters(topStack: Long, useDynFrames: Boolean) {
        check(!isBottom()) {"Unexpected restoreScratchRegisters on bottom"}

        if (!isTop()) {
            if (scratchRegisters.size < 4) {
                throw ScalarDomainError("The number of calls to save/restore scratch registers must match: $scratchRegisters")
            } else {
                setRegister(Value.Reg(SbfRegister.R9), popScratchReg())
                setRegister(Value.Reg(SbfRegister.R8), popScratchReg())
                setRegister(Value.Reg(SbfRegister.R7), popScratchReg())
                setRegister(Value.Reg(SbfRegister.R6), popScratchReg())
                removeDeadStackFields(topStack, useDynFrames)
            }
        }
    }

    fun stackIterator() = stack.map { it.key to it.value}.iterator()

    fun forget(reg: Value.Reg) {
        if (!isBottom()) {
            setRegister(reg, sFac.mkTop())
        }
    }

    fun forget(regs: Iterable<Value.Reg>): ScalarBaseDomain<ScalarValue> {
        val out = deepCopy()
        return if (out.isBottom()) {
            out
        } else {
            regs.forEach { reg-> out.setRegister(reg, sFac.mkTop()) }
            out
        }
    }

    fun updateRegisters(pred: (oldVal: ScalarValue) -> Boolean, transformer: (oldVal: ScalarValue) -> ScalarValue) {
        if (!isBottom()) {
            for (i in 0 until registers.size) {
                val oldVal = registers[i]
                if (pred(oldVal)) {
                    val newVal = transformer(oldVal)
                    if (!setRegister(i, newVal)) {
                        return
                    }
                }
            }
        }
    }

    fun updateScratchRegisters(pred: (oldVal: ScalarValue) -> Boolean, transformer: (oldVal: ScalarValue) -> ScalarValue) {
        if (!isBottom()) {
            for (i in 0 until scratchRegisters.size) {
                val oldVal = scratchRegisters[i]
                if (pred(oldVal)) {
                    val newVal = transformer(oldVal)
                    check(!newVal.isBottom()) {"unexpected bottom in updateScratchRegisters"}
                    scratchRegisters[i] = newVal
                }
            }
        }
    }

    fun updateStack(pred: (oldVal: ScalarValue) -> Boolean, transformer: (oldVal: ScalarValue) -> ScalarValue) {
        if (!isBottom()) {
            val updates = mutableMapOf<ByteRange, ScalarValue>()
            for ((slice, oldVal) in stackIterator()) {
                if (pred(oldVal)) {
                    updates[slice] = transformer(oldVal)
                }
            }
            for ((slice, newVal) in updates) {
                updateStack(slice, newVal, isWeak= false)
            }
        }
    }

    fun updateStack(slice: ByteRange, newVal: ScalarValue, isWeak: Boolean) {
        if (isBottom()) {
            return
        }
        if (newVal.isBottom()) {
            setToBottom()
            return
        }

        stack = if (newVal.isTop()) {
            stack.remove(slice)
        } else {
            stack.put(slice, newVal, isWeak)
        }
    }

    fun removeStackSliceIf(offset: Long, len: Long, onlyPartial: Boolean, pred: (ByteRange) -> Boolean = {_->true}) {
        val slice = stack.inRange(offset, len, onlyPartial)
        for ((k,_) in slice) {
            if (pred(k)) {
                stack = stack.remove(k)
            }
        }
    }

    fun removeStack() {
        stack = StackEnvironment.makeTop()
    }

    /**
     * Copy entries from `[srcOffset, srcOffset+len)` to `[dstOffset, dstOffset+len)`
     *  As a side effect, it adds in [dstFootprint] any overwritten byte at the destination.
    **/
    fun copyStack(srcOffset: Long, dstOffset: Long, len: Long, isWeak: Boolean, dstFootprint: MutableSet<ByteRange>) {
        val delta = dstOffset - srcOffset
        val slice = stack.inRange(srcOffset, len, onlyPartial = false)
        for ((k, v) in slice) {
            val offset = k.offset
            val width = k.width
            val dstSlice = ByteRange(offset + delta, width)
            dstFootprint.add(dstSlice)
            stack = stack.put(dstSlice, v, isWeak)
        }
    }

    fun getStackSingletonOrNull(slice: ByteRange): ScalarValue? = stack.getSingletonOrNull(slice)

    /**
     * Default abstract transformer for external functions
     **/
    fun<D, TNum, TOffset> analyzeExternalCall(
        locInst: LocatedSbfInstruction,
        scalars: D,
        memSummaries: MemorySummaries
    ) where TNum: INumValue<TNum>,
            TOffset: IOffset<TOffset>,
            D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset>  {
        class ScalarPredicateSummaryVisitor: SummaryVisitor {
            override fun noSummaryFound(locInst: LocatedSbfInstruction) {
                forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }
            override fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType) {
                forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }
            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister,
                                         offset: Long,
                                         width: Byte,
                                         @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                                         type: MemSummaryArgumentType) {
                val regType = scalars.getAsScalarValue(Value.Reg(reg)).type()
                if (regType is SbfType.PointerType.Stack) {
                    val baseOffset = regType.offset.toLongOrNull()
                    check(baseOffset != null) {"processArgument is accessing stack at a non-constant offset ${regType.offset}"}
                    removeStackSliceIf(offset, width.toLong(), onlyPartial = false)
                }
            }
        }
        val vis = ScalarPredicateSummaryVisitor()
        memSummaries.visitSummary(locInst, vis)
    }

    /**
     * Default abstract transformer for `memcpy`/`memcpy_zext`/`memcpy_trunc`/`memmove`/`memset`
     **/
    fun<D, TNum, TOffset> analyzeMemIntrinsics(
        locInst: LocatedSbfInstruction,
        scalars: D)
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

        val stmt = locInst.inst
        check(stmt is SbfInstruction.Call)

        val solanaFunction = SolanaFunction.from(stmt.name)
        check (solanaFunction == SolanaFunction.SOL_MEMCPY ||
               solanaFunction == SolanaFunction.SOL_MEMCPY_ZEXT ||
               solanaFunction == SolanaFunction.SOL_MEMCPY_TRUNC ||
               solanaFunction == SolanaFunction.SOL_MEMMOVE ||
               solanaFunction == SolanaFunction.SOL_MEMSET)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG) // destination
        val r3 = Value.Reg(SbfRegister.R3_ARG) // len

        if (stmt.writeRegister.contains(r0)) {
            forget(r0)
        }

        val dstType = scalars.getAsScalarValue(r1).type()
        if (dstType is SbfType.PointerType.Stack) {
            val len = when(solanaFunction) {
                SolanaFunction.SOL_MEMCPY_ZEXT -> 8
                else -> {
                    val lenType = scalars.getAsScalarValue(r3).type()
                    (lenType as? SbfType.NumType)?.value?.toLongOrNull()
                        ?: throw UnknownMemcpyLenError(
                            DevErrorInfo(
                                locInst, PtrExprErrReg(r3),
                                "${stmt.name} on stack without knowing exact length: $lenType"
                            )
                        )
                }
            }

            if (dstType.offset.isTop()) {
                throw UnknownStackPointerError(
                    DevErrorInfo(
                        locInst,
                        PtrExprErrReg(r1),
                        "memcpy on stack without knowing destination offset"
                    )
                )
            }

            val dstOffsets = dstType.offset.toLongList()
            check(dstOffsets.isNotEmpty()) { "Scalar+predicate domain expects non-empty list" }

            dstOffsets.forEach { dstOffset ->
                removeStackSliceIf(dstOffset, len, onlyPartial = false)
            }
        }
    }
}
