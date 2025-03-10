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
import sbf.callgraph.*
import sbf.cfg.*
import sbf.sbfLogger
import sbf.SolanaConfig
import sbf.support.*
import org.jetbrains.annotations.TestOnly

/**
 * Abstract domain to model SBF registers and stack.
 * The current abstraction is very limited because it consists of mapping each register and
 * stack offset to ScalarValue which can only carry non-relational information.
 *
 * Note about soundness: the scalar domain is sound conditional to no stack pointers escape.
 * The scalar domain is not precise enough to keep track of which stack pointers might escape.
 * Instead, the soundness of the scalar domain relies on the pointer domain to check that no stack pointers escape.
 * In other words, we can think of the scalar domain adding assertions after each store and memcpy instructions,
 * and then the pointer domain checking that all assertions hold.
 *
 * This soundness argument is formally described in the paper
 * "Pointer Analysis, Conditional Soundness and Proving the Absence of Errors"
 * by Conway, Dams, Namjoshi, and Barret (SAS'08).
 */

/** For internal errors **/
private class ScalarDomainError(msg: String): SolanaInternalError("ScalarDomain error: $msg")

/**
 * This class wraps SbfType inside StackEnvironmentValue which is an interface.
 * It represents the value stored in a register or stack offset.
 **/
class ScalarValue(private val type: SbfType): StackEnvironmentValue<ScalarValue> {
    companion object {
        fun mkTop() = ScalarValue(SbfType.Top)
        fun mkBottom() = ScalarValue(SbfType.Bottom)
        fun from(value: ULong): ScalarValue {
            // REVISIT: immediate values are represented as ULong
            // The analysis uses signed integer semantics, so we need to convert the value to Long.
            // Therefore, overflow will happen if value represents a negative number.
            return ScalarValue(SbfType.NumType(Constant(value.toLong())))
        }
        fun from(value: Long) = ScalarValue(SbfType.NumType(Constant(value)))

        fun anyNum() = ScalarValue(SbfType.NumType(Constant.makeTop()))
    }

    fun get() = type
    override fun isBottom() = type is SbfType.Bottom
    override fun isTop() = type is SbfType.Top
    override fun mkTop() = ScalarValue(SbfType.Top)
    override fun join(other: ScalarValue) = ScalarValue(type.join(other.type))
    override fun widen(other: ScalarValue)= ScalarValue(type.join(other.type))
    override fun lessOrEqual(other: ScalarValue) = type.lessOrEqual(other.type)
    override fun toString() = type.toString()
}

class ScalarDomain(// Model stack's contents
                   private var stack: StackEnvironment<ScalarValue>,
                   // Model each normal register
                   private val registers: ArrayList<ScalarValue>,
                   // Contains the scratch registers of all callers
                   // This is a stack whose size is multiple of 4 which is the number of scratch registers.
                   private val scratchRegisters: ArrayList<ScalarValue>,
                   // To represent error or unreachable abstract state
                   private var isBot: Boolean = false): AbstractDomain<ScalarDomain> {

    constructor(initPreconditions: Boolean = false):
        this(StackEnvironment.makeTop(),
             ArrayList(NUM_OF_SBF_REGISTERS), arrayListOf(), false) {
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            registers.add(ScalarValue.mkTop())
        }
        if (initPreconditions) {
            setRegister(Value.Reg(SbfRegister.R10_STACK_POINTER),
                        ScalarValue(SbfType.PointerType.Stack(ConstantOffset(SBF_STACK_FRAME_SIZE))))
        }
    }

    companion object {
        fun makeBottom(): ScalarDomain {
            val res = ScalarDomain()
            res.setToBottom()
            return res
        }
        fun makeTop() = ScalarDomain()
    }

    override fun deepCopy(): ScalarDomain {
        val outRegisters = ArrayList<ScalarValue>(NUM_OF_SBF_REGISTERS)
        registers.forEach {
            outRegisters.add(it)
        }
        val outScratchRegs = ArrayList<ScalarValue>(scratchRegisters.size)
        scratchRegisters.forEach {
            outScratchRegs.add(it)
        }
        return ScalarDomain(stack, outRegisters, outScratchRegs, isBot)
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

    override fun isBottom() = isBot

    override fun isTop(): Boolean {
        return !isBottom() && stack.isTop() && registers.all { reg ->
            reg.isTop()
        }
    }

    override fun setToBottom() {
        isBot = true
        stack = StackEnvironment.makeBottom()
        registers.clear()
        scratchRegisters.clear()
    }

    override fun setToTop() {
        isBot = false
        stack = StackEnvironment.makeTop()
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            registers[i] = ScalarValue.mkTop()
        }
        scratchRegisters.clear()
    }

    private fun getIndex(reg: Value.Reg): Int {
        val idx = reg.r.value.toInt()
        if (idx in 0 until NUM_OF_SBF_REGISTERS) {
            return idx
        }
        throw ScalarDomainError("register $idx out-of-bounds")
    }

    private fun getRegister(reg: Value.Reg): ScalarValue {
        return if (isBottom()) {
            ScalarValue.mkBottom()
        } else {
            registers[getIndex(reg)]
        }
    }

    private fun checkStackAccess(value: ScalarValue) {
        val valType = value.get()
        if (valType is SbfType.PointerType.Stack) {
            val stackOffset = valType.offset.get()
            if (stackOffset != null) {
                if (stackOffset < 0) {
                    throw SolanaError(
                        "Current stack size is ${SolanaConfig.StackFrameSize.get()} and stack offset exceeded max offset by ${-stackOffset}. " +
                            "Please increase the stack size with option \"-${SolanaConfig.StackFrameSize.name} N\" where N is a multiple of 4096.")
                }
            }
        }
    }

    @TestOnly
    fun setRegister(reg: Value.Reg, value: ScalarValue) {
        if (!isBottom()) {
            checkStackAccess(value)
            registers[getIndex(reg)] = value
        }
    }

    private fun joinOrWiden(other: ScalarDomain, IsJoin: Boolean, left: Label?, right: Label?): ScalarDomain {
        if (isBottom()) {
            return other.deepCopy()
        } else if (other.isBottom()) {
            return deepCopy()
        } else {
            /**
             * At a join point, each abstract state has been built analyzing *exactly* the same sequence of calls, so
             * that's why the number of scratch registers must be the same in the two join operands.
             * However, their abstract values can be different.
             */
            if (scratchRegisters.size != other.scratchRegisters.size) {
                val dbgMsg = if (left != null && right != null ){
                    "join between $left and $right"
                } else if (left != null){
                    "widening at $left"
                } else {
                    ""
                }
                throw ScalarDomainError("$dbgMsg failed because disagreement on the number of scratch registers")
            }

            val outStack = if (IsJoin) {
                stack.join(other.stack)
            } else {
                stack.widen(other.stack)
            }
            val outRegisters = ArrayList<ScalarValue>(NUM_OF_SBF_REGISTERS)

            registers.forEachIndexed {i, it ->
                if (IsJoin) {
                    outRegisters.add(it.join(other.registers[i]))
                } else {
                    outRegisters.add(it.widen(other.registers[i]))
                }
            }

            val outScratchRegs = ArrayList<ScalarValue>(scratchRegisters.size)
            scratchRegisters.forEachIndexed{ i, it ->
                outScratchRegs.add(it.join(other.scratchRegisters[i]))
            }

            return ScalarDomain(outStack, outRegisters, outScratchRegs)
        }
    }

    override fun join(other: ScalarDomain, left: Label?, right: Label?): ScalarDomain {
        return joinOrWiden(other, true, left, right)
    }

    override fun widen(other: ScalarDomain, b: Label?): ScalarDomain {
        return joinOrWiden(other, false, b, null)
    }

    override fun lessOrEqual(other: ScalarDomain, left: Label?, right: Label?): Boolean {
        if (other.isTop() || isBottom()) {
            return true
        } else if (other.isBottom() || isTop()) {
            return false
        } else {
            /**
             * When comparing two abstract states, each abstract state has been built analyzing *exactly* the same
             * sequence of calls, so that's why the number of scratch registers must be the same in the two operands.
             * However, their abstract values can be different.
             */
            if (scratchRegisters.size != other.scratchRegisters.size) {
                val dbgMsg = if (left != null && right != null ){
                    "inclusion between $left and $right"
                } else {
                    "inclusion"
                }
                throw ScalarDomainError("$dbgMsg failed because disagreement on the number of scratch registers")
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

            return true

        }
    }

    /** TRANSFER FUNCTIONS **/

    override fun forget(reg: Value.Reg) {
        if (!isBottom()) {
            registers[getIndex(reg)] = ScalarValue.mkTop()
        }
    }

    /**
     * Refine the value of a register used as an operand in an arithmetic or relational operation.
     * This refinement is sound because if the value turns to be a pointer then the scalar domain
     * will throw an exception at the time the pointer is de-referenced.
     **/
    private fun refineType(op: BinOp, ty: SbfType): SbfType {
        check(ty !is SbfType.Bottom) {"cannot call refineType with bottom"}
        return if (ty !is SbfType.Top) {
            ty
        } else {
            when (op) {
                BinOp.ARSH, BinOp.LSH, BinOp.RSH,
                BinOp.DIV, BinOp.MOD, BinOp.MUL -> SbfType.NumType(Constant.makeTop())
                else -> ty
            }
        }
    }
    private fun refineType(op: CondOp, ty: SbfType): SbfType {
        check(ty !is SbfType.Bottom) {"cannot call refineType with bottom"}
        return if (ty !is SbfType.Top) {
            ty
        } else {
            when (op) {
                CondOp.SGE, CondOp.SGT, CondOp.SLE, CondOp.SLT,
                CondOp.GE, CondOp.GT, CondOp.LE, CondOp.LT -> SbfType.NumType(Constant.makeTop())
                else -> ty
            }
        }
    }

    private fun analyzeUn(stmt: SbfInstruction.Un) {
        check(!isBottom()) {"cannot call analyzeUn on bottom"}
       // This transfer function is too conservative, and it can be improved.
        val newVal: ScalarValue? = when (val oldVal = getRegister(stmt.dst).get()) {
            is SbfType.Top, is SbfType.Bottom -> null
            is SbfType.NumType -> {
                ScalarValue(SbfType.NumType(ConstantNum.makeTop()))
            }
            else -> {
                check(oldVal is SbfType.PointerType)
                ScalarValue(oldVal.withOffset(ConstantOffset.makeTop()))
            }
        }
        if (newVal != null) {
            setRegister(stmt.dst, newVal)
        }
    }

    private fun doConstantPointerArithmetic(op: BinOp, dst: Value.Reg, src: Value.Imm) {
        fun makeConstantOffset(value: ULong): ConstantOffset {
            return ConstantOffset(value.toLong())
        }
        val dstType = getRegister(dst).get()
        if (dstType is SbfType.PointerType) {
            val dstOffset = dstType.offset
            val newOffset = when (op) {
                BinOp.ADD  -> dstOffset.add(makeConstantOffset(src.v))
                BinOp.SUB  -> dstOffset.sub(makeConstantOffset(src.v))
                else -> {
                    if (enableDefensiveChecks) {
                        throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $dst $op $src")
                    } else {
                        ConstantOffset.makeTop()
                    }
                }
            }
            setRegister(dst,  ScalarValue(dstType.withOffset(newOffset)))
        } else {
            forget(dst)
        }
    }

    private fun doNormalizedPointerArithmetic(op: BinOp, dst: Value.Reg,
                                              op1: Value.Reg, op1Type: SbfType.PointerType,
                                              op2: Value.Reg, op2Type: SbfType) {
        check(op2Type !is SbfType.Bottom) {"failed preconditions on doNormalizedPointerArithmetic"}

        when (op2Type) {
            is SbfType.NumType -> {
                val newOffset = when (op) {
                    BinOp.ADD  -> op1Type.offset.add(op2Type.value)
                    BinOp.SUB  -> op1Type.offset.sub(op2Type.value)
                    else -> {
                        if (enableDefensiveChecks) {
                            throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $op1 $op $op2")
                        } else {
                            ConstantOffset.makeTop()
                        }
                    }
                }
                setRegister(dst, ScalarValue(op1Type.withOffset(newOffset)))
            }
            is SbfType.PointerType -> {
                if (samePointerType(op1Type, op2Type)) {
                    if (op == BinOp.SUB) {
                        // subtraction of pointers of the same type is okay
                        val diff = op1Type.offset.sub(op2Type.offset)
                        setRegister(dst, ScalarValue(SbfType.NumType(diff)))
                    } else {
                        throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $op1 $op $op2")
                    }
                } else {
                    throw ScalarDomainError("cannot mix pointer from different memory regions ($op1Type and $op2Type)")
                }
            }
            is SbfType.Top -> {
                val newOffset = ConstantOffset.makeTop()
                setRegister(dst, ScalarValue(op1Type.withOffset(newOffset)))
            }
            else -> {
                throw ScalarDomainError("unexpected type $op2Type for operand $op2")
            }
        } // end when
    }

    private fun doPointerArithmetic(op: BinOp, dst: Value.Reg, src: Value.Reg) {
        val dstType = getRegister(dst).get()
        val srcType = getRegister(src).get()

        if (dstType is SbfType.PointerType) {
            doNormalizedPointerArithmetic(op, dst, dst, dstType, src, srcType)
        } else if (srcType is SbfType.PointerType && op.isCommutative) {
            doNormalizedPointerArithmetic(op, dst, src, srcType, dst, dstType)
        } else {
            forget(dst)
        }
    }

    private fun doALU(op: BinOp, dst: Value.Reg, dstType: SbfType.NumType, srcType: SbfType.NumType) {
        val dstCst = dstType.value
        val srcCst = srcType.value
        when (op) {
            BinOp.ADD  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.add(srcCst))))
            BinOp.MUL  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.mul(srcCst))))
            BinOp.SUB  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.sub(srcCst))))
            BinOp.DIV  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.udiv(srcCst))))
            BinOp.MOD  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.urem(srcCst))))
            BinOp.AND  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.and(srcCst))))
            BinOp.OR   -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.or(srcCst))))
            BinOp.XOR  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.xor(srcCst))))
            BinOp.ARSH -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.arsh(srcCst))))
            BinOp.LSH  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.lsh(srcCst))))
            BinOp.RSH  -> setRegister(dst, ScalarValue(SbfType.NumType(dstCst.rsh(srcCst))))
            BinOp.MOV -> {} // MOVE is handled elsewhere
        }
    }

    private fun getScalarValue(x: Value): ScalarValue {
        return when (x) {
            is Value.Imm -> {
                ScalarValue.from(x.v)
            }
            is Value.Reg -> {
                getRegister(x)
            }
        }
    }


    private fun getScalarValue(x: Value, globals: GlobalVariableMap): ScalarValue {
        when (x) {
            is Value.Imm -> {
                // We cast a number to a global variable if it matches an address from our [globals] map
                val address = x.v
                if (address <= Long.MAX_VALUE.toULong()) {
                    val gv = globals[address.toLong()]
                    if (gv != null) {
                        return ScalarValue(SbfType.PointerType.Global(Constant(0L), gv))
                    }
                }
                return ScalarValue.from(x.v)
            }
            is Value.Reg -> {
                return getRegister(x)
            }
        }
    }



    private fun analyzeBin(stmt: SbfInstruction.Bin, globals: GlobalVariableMap) {
        check(!isBottom()) {"analyzeBin cannot be called on bottom"}
        val dst = stmt.dst
        val src = stmt.v
        if (src is Value.Imm) {
            // dst := dst op k
            when (stmt.op) {
                BinOp.MOV -> {
                    /**
                     * We assume that the destination operand is a number unless the analysis that infers globals says
                     * it is a global variable.
                     **/
                    setRegister(dst, if (stmt.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                        getScalarValue(src, globals)
                    }  else {
                        getScalarValue(src)
                    })
                }
                else ->  {
                    val dstType = refineType(stmt.op, getRegister(dst).get())
                    if (dstType is SbfType.NumType) {
                        val srcVal = ScalarValue.from(src.v)
                        doALU(stmt.op, dst, dstType, (srcVal.get() as SbfType.NumType))
                    } else {
                        /**
                         * We don't know for sure whether dst is a pointer or not.
                         * doConstantPointerArithmetic will deal with that.
                         **/
                        doConstantPointerArithmetic(stmt.op, dst, src)
                    }
                }
            }
        } else {
            // dst := dst op src
            when (stmt.op) {
                BinOp.MOV -> {
                    /**
                     * If we know that src is the address of a global variable then we cast the destination to
                     * that global variable.
                     */
                    setRegister(dst, if (stmt.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                        (getScalarValue(src).get() as? SbfType.NumType)?.value?.get().let {
                            if (it != null) {
                                val gv = globals[it]
                                if (gv != null) {
                                    ScalarValue(SbfType.PointerType.Global(Constant(0L), gv))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } ?: getRegister(src as Value.Reg)
                    }  else {
                        getRegister(src as Value.Reg)
                    })
                }
                else -> {
                    val dstType = refineType(stmt.op, getRegister(dst).get())
                    val srcTypeBefore = getRegister(src as Value.Reg).get()
                    val srcType = refineType(stmt.op, srcTypeBefore)
                    if (!srcTypeBefore.lessOrEqual(srcType)) {
                        // srcType is strictly more precise than srcTypeBefore
                        setRegister(src, ScalarValue(srcType))
                    }
                    if (dstType is SbfType.NumType && srcType is SbfType.NumType) {
                        doALU(stmt.op, dst, dstType, srcType)
                    } else {
                        if (srcType is SbfType.NumType) {
                            val cst = srcType.value.get()
                            if (cst != null) {
                                doConstantPointerArithmetic(stmt.op, dst, Value.Imm(cst.toULong()))
                                return
                            }
                        }
                        doPointerArithmetic(stmt.op, dst, src)
                    }
                }
            }
        }
    }

    /** Transfer function for __CVT_save_scratch_registers **/
    private fun saveScratchRegisters() {
        pushScratchReg(registers[6])
        pushScratchReg(registers[7])
        pushScratchReg(registers[8])
        pushScratchReg(registers[9])
    }

    private fun removeDeadStackFields() {
        val ty = getRegister(Value.Reg(SbfRegister.R10_STACK_POINTER)).get()
        if (ty is SbfType.PointerType.Stack) {
            val topStack = ty.offset.get()
            if (topStack != null) {
                val deadFields = ArrayList<ByteRange>()
                for ((k, _) in stack.iterator()) {
                    if (k.offset > topStack) {
                        deadFields.add(k)
                    }
                }
                while (deadFields.isNotEmpty()) {
                    val k = deadFields.removeLast()
                    stack = stack.put(k, ScalarValue.mkTop())
                }
            }
        }
    }

    /** Transfer function for __CVT_restore_scratch_registers
     *  Invariant ensured by CFG construction: r10 has been decremented already
     **/
    private fun restoreScratchRegisters() {
        if (scratchRegisters.size < 4) {
            throw ScalarDomainError("The number of calls to save/restore scratch registers must match")
        } else {
            setRegister(Value.Reg(SbfRegister.R9), popScratchReg())
            setRegister(Value.Reg(SbfRegister.R8), popScratchReg())
            setRegister(Value.Reg(SbfRegister.R7), popScratchReg())
            setRegister(Value.Reg(SbfRegister.R6), popScratchReg())
            removeDeadStackFields()
        }
    }

    private fun killOffsets(offset: Long, len: Long) {
        val slice = stack.inRange(offset, len, strict = false)
        for ((k,_) in slice) {
            stack = stack.remove(k)
        }
    }

    /**
     * Analyze memcpy and memmove
     *
     * If memcpy then
     *   1. Remove all the environment entries that might overlap with [dstOffset, dstOffset+len)
     *   2. Copy environment entries from [srcOffset, srcOffset+len) to [dstOffset, dstOffset+len)
     * If memmove then
     *   Remove all the environment entries that might overlap with [dstOffset, dstOffset+len)
     *
     * Thus, the analysis of memcpy is precise but the analysis of memmove is a rough over-approximation.
     * **/
    private fun analyzeMemTransfer(locInst: LocatedSbfInstruction) {
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Call) {"analyzeMemTransfer expects a call instruction instead of $stmt"}

        val solanaFunction = SolanaFunction.from(stmt.name)
        check(solanaFunction == SolanaFunction.SOL_MEMCPY ||
                    solanaFunction == SolanaFunction.SOL_MEMMOVE) {"Precondition of analyzeMemTransfer"}

        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val dstType = getRegister(r1).get()
        if (dstType is SbfType.PointerType.Stack) {
            val len = (getRegister(r3).get() as? SbfType.NumType)?.value?.get()
                    ?: throw UnknownMemcpyLenError(DevErrorInfo(locInst, PtrExprErrReg(r3), "memcpy on stack without knowing length"))
            val dstOffset = dstType.offset.get()
                    ?: throw UnknownStackPointerError(DevErrorInfo(locInst, PtrExprErrReg(r1),"memcpy on stack without knowing destination offset"))

            // Remove all the environment entries that might overlap with [dstOffset, dstOffset+len)
            killOffsets(dstOffset, len)
            if (solanaFunction == SolanaFunction.SOL_MEMCPY) {
                val srcType = getRegister(r2).get()
                if (srcType is SbfType.PointerType.Stack) {
                    val srcOffset = srcType.offset.get()
                    if (srcOffset != null) {
                        // Copy environment entries from [srcOffset, srcOffset+len) to [dstOffset, dstOffset+len)
                        val delta = dstOffset - srcOffset
                        val slice = stack.inRange(srcOffset, len, strict = true)
                        for ((k, v) in slice) {
                            val offset = k.offset
                            val width = k.width
                            stack = stack.put(ByteRange(offset + delta, width), v)
                        }
                    }
                }
            }
            // memmove is not modeled precisely
        }
    }

    private fun castNumToString(reg: Value.Reg, globals: GlobalVariableMap) {
        val oldType = getRegister(reg).get()
        if (oldType is SbfType.NumType) {
            val newType = castNumToPtr(oldType, globals)
            if (newType is SbfType.PointerType.Global && newType.global is SbfConstantStringGlobalVariable) {
                setRegister(reg, ScalarValue(newType))
            }
        }
    }

    private fun analyzeCall(locInst: LocatedSbfInstruction,
                            globals: GlobalVariableMap,
                            memSummaries: MemorySummaries) {
        check(!isBottom()) {"analyzeCall cannot be called on bottom"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Call) {"analyzeCall expects a call instead of $stmt"}
        val solFunction = SolanaFunction.from(stmt.name)
        if  (solFunction != null) {
            /** Solana syscall **/
            when (solFunction) {
                SolanaFunction.SOL_PANIC, SolanaFunction.ABORT  -> setToBottom()
                SolanaFunction.SOL_MEMCMP, SolanaFunction.SOL_INVOKE_SIGNED_C, SolanaFunction.SOL_INVOKE_SIGNED_RUST,
                SolanaFunction.SOL_CURVE_GROUP_OP, SolanaFunction.SOL_CURVE_VALIDATE_POINT,
                SolanaFunction.SOL_GET_STACK_HEIGHT -> {
                    setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), ScalarValue.anyNum())
                }
                SolanaFunction.SOL_GET_CLOCK_SYSVAR -> {
                    summarizeCall(locInst, memSummaries)
                }
                SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMMOVE -> {
                    analyzeMemTransfer(locInst)
                }
                else -> {
                    forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
                }
            }
        } else {
            if (stmt.isAllocFn() && memSummaries.getSummary(stmt.name) == null) {
                /// This is only used for pretty-printing
                setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE),
                    ScalarValue(SbfType.PointerType.Heap(Constant.makeTop())))
            } else {
                /** CVT call **/
                val cvtFunction = CVTFunction.from(stmt.name)
                if (cvtFunction != null) {
                    when (cvtFunction) {
                        is CVTFunction.Core -> {
                            when (cvtFunction.value) {
                                CVTCore.ASSUME -> {
                                    analyzeAssume(Condition(CondOp.NE, Value.Reg(SbfRegister.R1_ARG), Value.Imm(0UL)))
                                }
                                CVTCore.ASSERT -> {
                                    // At this point, we don't check.
                                    // So if assert doesn't fail than we can assume that r1 !=0
                                    analyzeAssume(Condition(CondOp.NE, Value.Reg(SbfRegister.R1_ARG), Value.Imm(0UL)))
                                }
                                CVTCore.SATISFY, CVTCore.SANITY -> {}
                                CVTCore.SAVE_SCRATCH_REGISTERS -> saveScratchRegisters()
                                CVTCore.RESTORE_SCRATCH_REGISTERS -> restoreScratchRegisters()
                                CVTCore.NONDET_ACCOUNT_INFO -> {
                                    summarizeCall(locInst, memSummaries)
                                }
                                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE -> {
                                    /// This is only used for pretty-printing
                                    setRegister(
                                        Value.Reg(SbfRegister.R0_RETURN_VALUE),
                                        ScalarValue(SbfType.PointerType.Input(Constant.makeTop()))
                                    )
                                }
                                CVTCore.ALLOC_SLICE -> {
                                    /// This is only used for pretty-printing
                                    /// That's why we return top in some cases rather than reporting an error
                                    val returnedVal = when (getRegister(Value.Reg(SbfRegister.R1_ARG)).get()) {
                                        is SbfType.PointerType.Heap -> ScalarValue(SbfType.PointerType.Heap(Constant.makeTop()))
                                        is SbfType.PointerType.Input -> ScalarValue(SbfType.PointerType.Input(Constant.makeTop()))
                                        is SbfType.PointerType.Global -> ScalarValue(
                                            SbfType.PointerType.Global(
                                                Constant.makeTop(),
                                                global = null
                                            )
                                        )
                                        is SbfType.PointerType.Stack -> ScalarValue(SbfType.PointerType.Stack(Constant.makeTop()))
                                        else -> ScalarValue.mkTop()
                                    }
                                    setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), returnedVal)
                                }
                            }
                        }
                        is CVTFunction.Nondet, is CVTFunction.U128Intrinsics, is CVTFunction.NativeInt  ->  {
                            summarizeCall(locInst, memSummaries)
                        }
                        is CVTFunction.Calltrace -> {
                            cvtFunction.value.strings.forEach {
                                castNumToString(it.string, globals)
                            }
                        }
                    }
                } else {
                    /** SBF to SBF call **/
                    summarizeCall(locInst, memSummaries)
                }
            }
        }
    }

    private fun summarizeCall(locInst: LocatedSbfInstruction, memSummaries: MemorySummaries) {

        class ScalarSummaryVisitor: SummaryVisitor {
            private fun getScalarValue(ty: MemSummaryArgumentType): ScalarValue {
                return when(ty) {
                    MemSummaryArgumentType.NUM -> ScalarValue.anyNum()
                    MemSummaryArgumentType.PTR_HEAP -> ScalarValue(SbfType.PointerType.Heap(Constant.makeTop()))
                    MemSummaryArgumentType.PTR_STACK -> ScalarValue(SbfType.PointerType.Stack(Constant.makeTop()))
                    else -> ScalarValue.mkTop()
                }
            }

            override fun noSummaryFound(locInst: LocatedSbfInstruction) {
                forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }

            override fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType) {
                setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), getScalarValue(type))
            }

            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister,
                                         offset: Long,
                                         width: Byte,
                                         @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                                         type: MemSummaryArgumentType) {
                val regType = getRegister(Value.Reg(reg)).get()
                // We only keep track of the stack
                if (regType is SbfType.PointerType.Stack) {
                    val baseOffset = regType.offset.get()
                    check(baseOffset != null) {"processArgument is accessing stack at a non-constant offset"}
                    stack = stack.put(ByteRange(baseOffset + offset, width), getScalarValue(type))
                }
            }
        }

        val vis = ScalarSummaryVisitor()
        memSummaries.visitSummary(locInst, vis)
    }

    private fun analyzeAssumeNumNum(op: CondOp, left: Value.Reg,
                                    leftType: SbfType.NumType, rightType: SbfType.NumType) {
        when (op) {
            CondOp.EQ -> {
                // The assume operation does not modify the left operand.
                // It only returns a TriBoolean.
                // We use the meet operation to potentially refine the left operand.
                // With constants there is no refinement but with intervals and other
                // abstractions is very possible.
                val meetVal = leftType.value.meet(rightType.value)
                if (meetVal.isBottom()) {
                    setToBottom()
                } else {
                    setRegister(left, ScalarValue(SbfType.NumType(meetVal)))
                }
            }
            else -> {
                val res = leftType.value.assume(op, rightType.value)
                if (res.isFalse()) {
                    setToBottom()
                }
            }
        }
    }

    private fun analyzeAssumeTopNonTop(op: CondOp, left: Value.Reg, leftType: SbfType, rightType: SbfType) {
        check(leftType is SbfType.Top || rightType is SbfType.Top) {"failed preconditions on analyzeAssumeTopNonTop"}
        check(!(leftType !is SbfType.Top && rightType !is SbfType.Top)) {"failed preconditions on analyzeAssumeTopNonTop"}
        if (op == CondOp.EQ) {
            if (leftType is SbfType.Top) {
                setRegister(left, ScalarValue(rightType))
            } else if (rightType is SbfType.Top) {
                setRegister(left, ScalarValue(leftType))
            }
        }
    }

    private fun analyzeAssumePtrPtr(op: CondOp, left: Value.Reg, leftType: SbfType.PointerType,
                                    right: Value.Reg, rightType: SbfType.PointerType) {
        if (samePointerType(leftType, rightType)) {
            val leftOffset = leftType.offset
            val rightOffset = rightType.offset
            when (op) {
                CondOp.EQ -> {
                    val newOffset = leftOffset.meet(rightOffset)
                    if (newOffset.isBottom()) {
                        setToBottom()
                    } else {
                        when (leftType) {
                            is SbfType.PointerType.Stack -> {
                                setRegister(left, ScalarValue(SbfType.PointerType.Stack(newOffset)))
                                setRegister(right, ScalarValue(SbfType.PointerType.Stack(newOffset)))
                            }
                            is SbfType.PointerType.Input -> {
                                setRegister(left, ScalarValue(SbfType.PointerType.Input(newOffset)))
                                setRegister(right, ScalarValue(SbfType.PointerType.Input(newOffset)))
                            }
                            is SbfType.PointerType.Heap -> {
                                setRegister(left, ScalarValue(SbfType.PointerType.Heap(newOffset)))
                                setRegister(right, ScalarValue(SbfType.PointerType.Heap(newOffset)))
                            }
                            is SbfType.PointerType.Global -> {
                                val leftGlobal = leftType.global
                                val rightGlobal = (rightType as SbfType.PointerType.Global).global
                                if (leftGlobal != null && rightGlobal != null && leftGlobal.address == rightGlobal.address) {
                                    // The base addresses are the same but offset could be different
                                    setRegister(left, ScalarValue(SbfType.PointerType.Global(newOffset, leftGlobal)))
                                    setRegister(right, ScalarValue(SbfType.PointerType.Global(newOffset, rightGlobal)))
                                }
                            }
                        }
                    }
                }
                CondOp.NE -> {
                    if (!leftOffset.isTop() && !rightOffset.isTop() &&  leftOffset == rightOffset) {
                        setToBottom()
                    }
                }
                else -> {
                    // We do nothing for now, but we can be more precise here if needed.
                    // Note that ignoring an "assume" instruction is always sound.
                }
            }
        } else {
            throw ScalarDomainError("assume cannot have pointer operands of different type")
        }
    }

    private fun analyzeAssume(cond: Condition) {
        val op = cond.op
        val leftReg = cond.left
        val rightVal = cond.right
        check(!isBottom()) {"analyzeAssume cannot be called on bottom"}
        val leftAbsValBefore = getRegister(leftReg)
        val leftAbsVal = ScalarValue(refineType(op,leftAbsValBefore.get()))
        if (!leftAbsValBefore.lessOrEqual(leftAbsVal)) {
            // leftAbsVal is strictly more precise than leftAbsValBefore
            setRegister(leftReg, leftAbsVal)
        }
        check(!leftAbsVal.isBottom()) {"analyzeAssume: leftAbsVal is bottom after refinement"}
        if (rightVal is Value.Imm) {
            val rightAbsVal = ScalarValue.from(rightVal.v)
            if (leftAbsVal.get() is SbfType.NumType) {
                analyzeAssumeNumNum(op, leftReg,
                                    leftAbsVal.get() as SbfType.NumType,
                                    rightAbsVal.get() as SbfType.NumType)
            } else if (leftAbsVal.get() is SbfType.PointerType) {
                // do nothing: we can do better here if op is EQ
            } else if (leftAbsVal.isTop()) {
                /**
                 * We assume that the left operand is a number,
                 * although we don't really know at this point.
                 **/
                analyzeAssumeTopNonTop(op, leftReg, leftAbsVal.get(), rightAbsVal.get())
            }
        } else {
            val rightAbsValBefore = getRegister(rightVal as Value.Reg)
            val rightAbsVal = ScalarValue(refineType(op, rightAbsValBefore.get()))
            if (!rightAbsValBefore.lessOrEqual(rightAbsVal)) {
                // rightAbsVal is strictly more precise than rightAbsValBefore
                setRegister(rightVal, rightAbsVal)
            }
            check(!rightAbsVal.isBottom()) {"analyzeAssume: rightAbsVal is bottom after refinement"}
            if (leftAbsVal.isTop() && rightAbsVal.isTop()) {
                // do nothing
            } else if (leftAbsVal.isTop() || rightAbsVal.isTop()) {
                analyzeAssumeTopNonTop(op, leftReg, leftAbsVal.get(), rightAbsVal.get())
            } else {
                val leftType = leftAbsVal.get()
                val rightType = rightAbsVal.get()
                if (leftType is SbfType.NumType && rightType is SbfType.NumType) {
                    analyzeAssumeNumNum(op, leftReg, leftType, rightType)
                } else if (leftType is SbfType.PointerType && rightType is SbfType.NumType) {
                    // do nothing: note that comparing pointers and numbers is perfectly fine
                } else if (leftType is SbfType.NumType && rightType is SbfType.PointerType) {
                    // do nothing: note that comparing pointers and numbers is perfectly fine
                } else if (leftType is SbfType.PointerType && rightType is SbfType.PointerType) {
                    analyzeAssumePtrPtr(op, leftReg, leftType, rightVal, rightType)
                }
            }
        }
    }

    private fun analyzeAssume(stmt: SbfInstruction.Assume) {
        check(!isBottom()) {"analyzeAssume cannot be called on bottom"}
        analyzeAssume(stmt.cond)
    }

    private fun analyzeAssert(stmt: SbfInstruction.Assert) {
        check(!isBottom()) {"analyzeAssert cannot be called on bottom"}
        // Either the assertion fails or it becomes an assumption.
        analyzeAssume(stmt.cond)
    }

    private fun analyzeHavoc(stmt: SbfInstruction.Havoc) {
        // If the havoced variable has a type then we keep it.
        val dstType = stmt.dstType
        if (dstType == null) {
            forget(stmt.dst)
        } else {
            setRegister(stmt.dst, ScalarValue(dstType))
        }
    }

    private fun refineSelectCond(cond: Condition, other: ScalarDomain) {
        fun refine(x: ScalarValue, y: ScalarValue) = if (x.isTop()) { y } else { x }
        val left = cond.left
        setRegister(left, refine(getValue(left), other.getValue(left)))
        val right = cond.right
        if (right is Value.Reg) {
            setRegister(right, refine(getValue(right), other.getValue(right)))
        }
    }


    private fun analyzeSelect(stmt: SbfInstruction.Select) {
        check(!isBottom()) {"analyzeSelect cannot be called on bottom"}

        val trueAbsVal = deepCopy()
        trueAbsVal.analyzeAssume(stmt.cond)
        if (trueAbsVal.isBottom()) {
            setRegister(stmt.dst, getScalarValue(stmt.falseVal))
        } else {
            val falseAbsVal = deepCopy()
            falseAbsVal.analyzeAssume(stmt.cond.negate())
            if (falseAbsVal.isBottom()) {
                setRegister(stmt.dst, getScalarValue(stmt.trueVal))
            } else {
                refineSelectCond(stmt.cond, trueAbsVal.join(falseAbsVal))
                setRegister(stmt.dst,
                            getScalarValue(stmt.falseVal)
                                .join(getScalarValue(stmt.trueVal)))
            }
        }
    }

    private fun forgetOrNum(v: Value.Reg, isNum: Boolean) {
        if (isNum) {
            // This should be always a "weak" read because we can read twice from the same memory location
            // but one loaded value can be considered as non-pointer because it's never de-referenced but the other one can be de-referenced.
            // Since the scalar domain is non-relation all reads are weak anyway.
            setRegister(v, ScalarValue.anyNum())
        } else {
            forget(v)
        }
    }

    /**
     *  Return the abstract value of the base register if it will be killed by the lhs of a load instruction.
     *  Otherwise, it returns null. This is used by the Memory Domain.
     **/
    fun analyzeMem(locInst: LocatedSbfInstruction, globalsMap: GlobalVariableMap): ScalarValue? {
        check(!isBottom()) {"analyzeMem cannot be called on bottom"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Mem) {"analyzeMem expect a memory instruction instead of $stmt"}

        val baseReg = stmt.access.baseReg
        val offset = stmt.access.offset
        val width = stmt.access.width
        val value = stmt.value
        val isLoad = stmt.isLoad
        var baseRegType = getRegister(baseReg).get()
        val loadedAsNumForPTA = stmt.metaData.getVal(SbfMeta.LOADED_AS_NUM_FOR_PTA) != null

        /**
         *  The type of @baseReg can be updated during this transfer function.
         *  If the lhs is equal to @baseReg then we remember the type of baseReg
         *  before redefinition. This is needed by the Memory domain.
         */
        var baseRegTypeBeforeKilled: ScalarValue? = if (isLoad) {
            ScalarValue(baseRegType)
        } else {
            null
        }

        if (baseRegType is SbfType.NumType) {
            val n = baseRegType.value.get()
            if (n != null && n == 0L) {
                /**
                 * The constant zero can represent both the number zero and the NULL pointer.
                 * A de-reference of NULL is not allowed.
                 *
                 * However, during the abstract interpretation a NULL dereference can happen without being an actual error.
                 * This happens, for instance, if the fixpoint strategy analyzes a basic block without analyzing
                 * first all the predecessors, and in the analyzed predecessors the pointer is so far NULL although
                 * in reality those predecessors cannot reach its successor but the scalar domain cannot prove it.
                 *
                 * Thus, making the abstract state bottom is sound.
                 */
                setToBottom()
                return null
            }
            val castedPtrType = castNumToPtr(baseRegType, globalsMap)
            if (castedPtrType != null) {
                /**
                 * IMPLICIT CASTS TO A POINTER: override the type for baseReg if possible
                 **/
                baseRegType = castedPtrType
                val baseRegVal = ScalarValue(baseRegType)
                setRegister(baseReg, baseRegVal)
                if (isLoad && baseReg == (value as Value.Reg)) {
                    baseRegTypeBeforeKilled = baseRegVal
                }
            }
        }

        when (baseRegType) {
            is SbfType.Bottom -> {}
            is SbfType.Top -> {
                // We know that baseReg is an arbitrary pointer, but we don't have such a notion
                // in our type lattice
                if (isLoad) {
                    forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                }
            }
            is SbfType.NumType -> {
                /** There is nothing wrong to access memory directly via an integer, but
                 *  then it will be harder for the type analysis to type memory accesses.
                 *  We stop the analysis to make sure we don't miss the implicit cast. For instance,
                 *  - if the address is 0x200000000 then we know that the instruction is accessing to the stack,
                 *  - if the address is 0x400000000 then we know that the instruction is accessing to the inputs,
                 *  - and so on.
                 *  It's also possible that using constants might not be strong enough to prove that
                 *  the content of a register is between [SBF_HEAP_START, SBF_HEAP_END)
                 **/
                if (enableDefensiveChecks) {
                    throw ScalarDomainError("TODO unsupported memory operation $stmt in ScalarDomain " +
                        "because base is a number in $this")
                }

                if (isLoad) {
                    forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                }
            }
            is SbfType.PointerType -> {
                when (baseRegType) {
                    is SbfType.PointerType.Stack -> {
                        // We try to be precise when load/store from/to stack
                        val stackOffset = baseRegType.offset.add(ConstantOffset(offset.toLong())).get()
                        if (stackOffset == null) {
                            if (isLoad) {
                                forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                            } else {
                                throw UnknownStackPointerError(DevErrorInfo(locInst, PtrExprErrReg(baseReg), "store: $stmt to unknown stack location"))
                            }
                        } else {
                            if (isLoad) {
                                val loadedAbsVal = stack.getSingletonOrNull(ByteRange(stackOffset, width.toByte()))
                                if (loadedAbsVal != null) {
                                    setRegister(value as Value.Reg, loadedAbsVal)
                                } else {
                                    forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                                }
                            } else {
                                // Remove first all overlapping entries
                                killOffsets(stackOffset, width.toLong())
                                stack = stack.put(ByteRange(stackOffset, width.toByte()), getValue(value))
                            }
                        }
                    }
                    is SbfType.PointerType.Global -> {
                        if (isLoad) {
                            forgetOrNum(value as Value.Reg, loadedAsNumForPTA)

                            val globalVar = baseRegType.global
                            if (globalVar != null) {
                                if (globalVar is SbfConstantNumGlobalVariable) {
                                    setRegister(value, ScalarValue.from(globalVar.value))
                                }
                            }
                        }
                    }
                    else -> {
                        if (isLoad) {
                            forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                        }
                    }
                }
            }
        }
        return baseRegTypeBeforeKilled
    }

    override fun getValue(value: Value): ScalarValue {
        return if (value is Value.Imm) {
            ScalarValue.from(value.v)
        } else {
            getRegister(value as Value.Reg)
        }
    }

    fun getStackContent(offset: Long, width: Byte): ScalarValue {
        return if (isBottom()) {
            ScalarValue.mkBottom()
        } else {
            stack.getSingletonOrNull(ByteRange(offset, width)) ?: ScalarValue.mkTop()
        }
    }

    @TestOnly
    fun setStackContent(offset: Long, width: Byte, value: ScalarValue) {
        stack = stack.put(ByteRange(offset, width), value)
    }

    /** Set the value of [reg] to [newVal] only if its old value is top **/
    fun refineValue(reg: Value.Reg, newVal: ScalarValue) {
        val oldVal = getRegister(reg)
        if (oldVal.isTop() && !newVal.isTop()) {
            setRegister(reg, newVal)
        }
    }

    fun analyze(locInst: LocatedSbfInstruction,
                globals: GlobalVariableMap,
                memSummaries: MemorySummaries) {
        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> analyzeUn(s)
                is SbfInstruction.Bin -> analyzeBin(s, globals)
                is SbfInstruction.Call -> analyzeCall(locInst, globals, memSummaries)
                is SbfInstruction.CallReg -> {
                    if (!SolanaConfig.SkipCallRegInst.get()) {
                        throw SolanaError("$s is not supported. " +
                            "Often this instruction is used for calling pretty-printing functions. " +
                            "If this is the case, then you can use option \"-${SolanaConfig.SkipCallRegInst.name} true\" to skip it.")
                    }
                }
                is SbfInstruction.Select -> analyzeSelect(s)
                is SbfInstruction.Havoc -> analyzeHavoc(s)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume -> analyzeAssume(s)
                is SbfInstruction.Assert -> analyzeAssert(s)
                is SbfInstruction.Mem -> analyzeMem(locInst, globals)
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
            }
        }
        if (SolanaConfig.DebugSlicer.get()) {
            sbfLogger.info { "After SCALAR DOMAIN $s: $this\n" }
        }

    }

    override fun analyze(b: SbfBasicBlock,
                         globals: GlobalVariableMap,
                         memSummaries: MemorySummaries,
                         listener: InstructionListener<ScalarDomain>): ScalarDomain {

        if (SolanaConfig.DebugSlicer.get()) {
            sbfLogger.info {"=== Scalar Domain analyzing ${b.getLabel()} ===\n" +
                             "Before SCALAR DOMAIN: $this\n"
            }
        }
        if (listener is DefaultInstructionListener) {
            /**
             * No need to remember abstract states before and after each instruction
             **/
            if (isBottom()) {
                return makeBottom()
            }

            val out = this.deepCopy()
            for (locInst in b.getLocatedInstructions()) {
                out.analyze(locInst, globals, memSummaries)
                if (out.isBottom()) {
                    break
                }
            }
            return out
        } else {
            /**
             * This case is when call to reconstruct abstract states at each instruction.
             * Extra deep copies for the listener.
             **/
            var before = this
            for (locInst in b.getLocatedInstructions()) {
                val after = before.deepCopy()
                listener.instructionEventBefore(locInst, before)
                after.analyze(locInst, globals, memSummaries)
                listener.instructionEventAfter(locInst, after)
                // Calling to this listener requires to make an extra copy
                // It's used by class AnnotateWithTypesListener defined in AnnotateCFG.kt
                listener.instructionEvent(locInst, before, after)
                before = after
            }
            return before
        }
    }

    override fun toString(): String {
        if (isBottom()) {
            return "bottom"
        } else if (isTop()) {
            return "top"
        }

        val nonTopRegisters: ArrayList<Pair<Int, ScalarValue>> = ArrayList()
        for (i in 0 until registers.size) {
            if (!registers[i].isTop()) {
                nonTopRegisters.add(Pair(i, registers[i]))
            }
        }

        var registers = "{"
        var i = 0
        while(i < nonTopRegisters.size) {
            val regIdx = nonTopRegisters[i].first
            val regType = nonTopRegisters[i].second
            registers += "r$regIdx->$regType"
            i++
            if (i < nonTopRegisters.size) {
                registers += ","
            }
        }
        registers += "}"

        return "(Registers=$registers,ScratchRegs=${scratchRegisters},Stack=$stack)"
    }
}
