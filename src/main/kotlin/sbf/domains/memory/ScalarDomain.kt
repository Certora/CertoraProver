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

import datastructures.stdcollections.*
import log.*
import sbf.disassembler.*
import sbf.callgraph.*
import sbf.cfg.*
import sbf.SolanaConfig
import sbf.support.*
import org.jetbrains.annotations.TestOnly

/**
 * Abstract domain to model SBF registers and stack.
 * The current abstraction is very limited because it consists of mapping each register and
 * stack offset to ScalarValue which can only carry non-relational information.
 *
 * Notes about soundness of the scalar domain:
 *
 * 1) the scalar domain is sound conditional to no stack pointers escape.
 *    The scalar domain is not precise enough to keep track of which stack pointers might escape.
 *    Instead, the soundness of the scalar domain relies on the pointer domain to check that no stack pointers escape.
 *    In other words, we can think of the scalar domain adding assertions after each store and `memcpy` instructions,
 *    and then the pointer domain checking that all assertions hold.
 *
 * This soundness argument is formally described in the paper
 * "Pointer Analysis, Conditional Soundness and Proving the Absence of Errors"
 * by Conway, Dams, Namjoshi, and Barret (SAS'08).
 *
 * 2) In a store or `memcpy` instruction if the destination is "top" then we need, in principle, to wipe out completely
 *    the stack. The analysis optimistically assumes that if the destination is "top" then it doesn't affect the stack.
 *    Note that this assumption is reasonable due to two main reasons: (a) we prove separately that stack pointers do
 *    not escape and (b) it is reasonable to assume that uninitialized/external memory does not contain stack pointers.
 */


private val logger = Logger(LoggerTypes.SBF_SCALAR_ANALYSIS)
private fun dbg(msg: () -> Any) { logger.info(msg)}

private class ValueFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
    val sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): ScalarValueFactory<ScalarValue<TNum, TOffset>> {
    override fun mkTop() = ScalarValue(sbfTypeFac.mkTop())
}

class ScalarDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
    private val base: ScalarBaseDomain<ScalarValue<TNum, TOffset>>,
    val sbfTypeFac: ISbfTypeFactory<TNum, TOffset>
    )
    : AbstractDomain<ScalarDomain<TNum, TOffset>>,
      ScalarValueProvider<TNum, TOffset>,
      MemoryDomainScalarOps<TNum, TOffset> {

    constructor(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>, initPreconditions: Boolean = false):
        this(ScalarBaseDomain(ValueFactory(sbfTypeFac)), sbfTypeFac) {
        if (initPreconditions) {
            setRegister(Value.Reg(SbfRegister.R10_STACK_POINTER), ScalarValue(sbfTypeFac.toStackPtr(SBF_STACK_FRAME_SIZE)))
        }
    }

    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeBottom(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): ScalarDomain<TNum, TOffset> {
            val res = ScalarDomain(sbfTypeFac)
            res.setToBottom()
            return res
        }
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeTop(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) = ScalarDomain(sbfTypeFac)
    }

    override fun deepCopy(): ScalarDomain<TNum, TOffset> = ScalarDomain(base.deepCopy(), sbfTypeFac)

    override fun isBottom() = base.isBottom()

    override fun isTop() = base.isTop()

    override fun setToBottom() {
        base.setToBottom()
    }

    override fun setToTop() {
        base.setToTop()
    }


    override fun join(other: ScalarDomain<TNum, TOffset>, left: Label?, right: Label?) =
        ScalarDomain(base.join(other.base), sbfTypeFac)

    override fun widen(other: ScalarDomain<TNum, TOffset>, b: Label?) =
        ScalarDomain(base.widen(other.base), sbfTypeFac)

    override fun lessOrEqual(other: ScalarDomain<TNum, TOffset>, left: Label?, right: Label?) =
        base.lessOrEqual(other.base)

    /** TRANSFER FUNCTIONS **/

    /**
     * This check is probably redundant because of [checkStackInBounds] but we still keep it.
     */
    private fun checkStackAccess(value: ScalarValue<TNum, TOffset>) {
        val offset = (value.type() as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset ?: return
        if (offset.isBottom()) {
            throw SolanaError("Stack offset is bottom and this is unexpected")
        }
        if (!offset.isTop()) {
            offset.toLongList().forEach { o ->
                if (o < 0) {
                    throw SmashedStack(locInst = null, -o.toInt())
                }
            }
        }
    }

    /**
     * Check that stack is not being smashed after pointer arithmetic [locInst]
     * @param [oldType] is the type of destination before executing the instruction.
     * @param [newType] is the type of destination after executing the instruction.
     **/
    private fun checkStackInBounds(
        oldType: SbfType<TNum, TOffset>,
        newType: SbfType<TNum, TOffset>,
        locInst: LocatedSbfInstruction
    ) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        if (locInst.inst.metaData.getVal(SbfMeta.MEMCPY_PROMOTION) != null) {
            // When we do `memcpy` promotion we create stack pointer arithmetic instructions whose offsets can be
            // greater than the stack frame's size. This is because the promotion "normalizes" the stack pointer with
            // respect to `r10` so the offset in the pointer arithmetic instruction can be far bigger than stack
            // frame's size if the pointer points to some caller's stack.
            return
        }

        val oldOffset = (oldType as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset?.toLongOrNull() ?: return
        val newOffset = (newType as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset?.toLongOrNull() ?: return
        val isDecreasing = newOffset < oldOffset
        if (isDecreasing) {
            val diff = oldOffset - newOffset
            val frameSize = SolanaConfig.StackFrameSize.get()
            if (diff > frameSize) {
                val extraSpace = diff - frameSize
                throw SmashedStack(locInst, extraSpace.toInt())
            }
        }
    }

    /**
     * Check that stack is not being smashed after a load or store instruction [locInst].
     * @param [baseType] is the type of base register being de-referenced.
     **/
    private fun checkStackInBounds(baseType: SbfType<TNum, TOffset>, locInst: LocatedSbfInstruction) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)

        val baseOffset = (baseType as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset?.toLongOrNull() ?: return
        val derefOffset = baseOffset + inst.access.offset
        val isDecreasing = derefOffset < baseOffset
        if (isDecreasing) {
            val diff = baseOffset - derefOffset
            val frameSize = SolanaConfig.StackFrameSize.get()
            if (diff > frameSize) {
                val extraSpace = diff - frameSize
                throw SmashedStack(locInst, extraSpace.toInt())
            }
        }
    }


    private fun getRegister(reg: Value.Reg): ScalarValue<TNum, TOffset> {
        return if (isBottom()) {
            ScalarValue(sbfTypeFac.mkBottom())
        } else {
            base.getRegister(reg)
        }
    }

    private fun setRegister(reg: Value.Reg, value: ScalarValue<TNum, TOffset>) {
        checkStackAccess(value)
        base.setRegister(reg, value)
    }

    override fun forget(reg: Value.Reg) {
        base.forget(reg)
    }

    /**
     * Refine the value of a register used as an operand in an arithmetic or relational operation.
     * This refinement is sound because if the value turns to be a pointer then the scalar domain
     * will throw an exception at the time the pointer is de-referenced.
     **/
    private fun refineType(op: BinOp, ty: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        check(!ty.isBottom()) {"cannot call refineType with bottom"}
        return if (!ty.isTop()) {
            ty
        } else {
            when (op) {
                BinOp.ARSH, BinOp.LSH, BinOp.RSH,
                BinOp.DIV, BinOp.MOD, BinOp.MUL -> sbfTypeFac.anyNum()
                else -> ty
            }
        }
    }
    private fun refineType(op: CondOp, ty: SbfType<TNum, TOffset>): SbfType<TNum, TOffset> {
        check(!ty.isBottom()) {"cannot call refineType with bottom"}
        return if (!ty.isTop()) {
            ty
        } else {
            when (op) {
                CondOp.SGE, CondOp.SGT, CondOp.SLE, CondOp.SLT,
                CondOp.GE, CondOp.GT, CondOp.LE, CondOp.LT -> sbfTypeFac.anyNum()
                else -> ty
            }
        }
    }

    private fun analyzeByteSwapInst(reg: Value.Reg) {
        when (val oldVal = getRegister(reg).type()) {
            is SbfType.Top, is SbfType.Bottom -> null
            is SbfType.NumType -> ScalarValue(sbfTypeFac.anyNum())
            is SbfType.PointerType -> {
                when (oldVal) {
                    is SbfType.PointerType.Stack  -> ScalarValue(sbfTypeFac.anyStackPtr())
                    is SbfType.PointerType.Heap   -> ScalarValue(sbfTypeFac.anyHeapPtr())
                    is SbfType.PointerType.Input  -> ScalarValue(sbfTypeFac.anyInputPtr())
                    is SbfType.PointerType.Global -> ScalarValue(sbfTypeFac.anyGlobalPtr(oldVal.global))
                }
            }
        }?.let { newVal ->
            setRegister(reg, newVal)
        }
    }

    private fun analyzeUn(stmt: SbfInstruction.Un) {
        check(!isBottom()) {"cannot call analyzeUn on bottom"}

        when(stmt.op) {
            UnOp.NEG -> {
                // modular arithmetic
                setRegister(stmt.dst, ScalarValue(sbfTypeFac.anyNum()))
            }
            UnOp.BE16, UnOp.BE32, UnOp.BE64, UnOp.LE16, UnOp.LE32, UnOp.LE64 -> {
                analyzeByteSwapInst(stmt.dst)
            }
        }
    }

    private fun doConstantPointerArithmetic(
        op: BinOp,
        dst: Value.Reg,
        src: SbfType.NumType<TNum, TOffset>,
        locInst: LocatedSbfInstruction
    ) {
        val dstType = getRegister(dst).type()
        if (dstType is SbfType.PointerType) {
            val dstOffset = dstType.offset
            val newVal = when (op) {
                BinOp.ADD  -> ScalarValue(dstType.withOffset(dstOffset.add(sbfTypeFac.numToOffset(src.value))))
                BinOp.SUB  -> ScalarValue(dstType.withOffset(dstOffset.sub(sbfTypeFac.numToOffset(src.value))))
                else -> {
                    if (enableDefensiveChecks) {
                        throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $dst $op $src")
                    } else {
                        ScalarValue(dstType.withTopOffset(sbfTypeFac))
                    }
                }
            }
            checkStackInBounds(dstType, newVal.type(), locInst)
            setRegister(dst, newVal)
        } else {
            forget(dst)
        }
    }

    private fun doNormalizedPointerArithmetic(
        op: BinOp, dst: Value.Reg,
        op1: Value.Reg, op1Type: SbfType.PointerType<TNum, TOffset>,
        op2: Value.Reg, op2Type: SbfType<TNum, TOffset>,
        locInst: LocatedSbfInstruction
    ) {
        check(op2Type !is SbfType.Bottom) {"failed preconditions on doNormalizedPointerArithmetic"}

        when (op2Type) {
            is SbfType.NumType -> {
                val newVal = when (op) {
                    BinOp.ADD  -> ScalarValue(op1Type.withOffset(op1Type.offset.add(sbfTypeFac.numToOffset(op2Type.value))))
                    BinOp.SUB  -> ScalarValue(op1Type.withOffset(op1Type.offset.sub(sbfTypeFac.numToOffset(op2Type.value))))
                    else -> {
                        if (enableDefensiveChecks) {
                            throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $op1 $op $op2")
                        } else {
                            ScalarValue(op1Type.withTopOffset(sbfTypeFac))
                        }
                    }
                }
                checkStackInBounds(getRegister(dst).type(), newVal.type(), locInst)
                setRegister(dst, newVal)
            }
            is SbfType.PointerType -> {
                if (op1Type.samePointerType(op2Type)) {
                    if (op == BinOp.SUB) {
                        // subtraction of pointers of the same type is okay
                        val diff = op1Type.offset.sub(op2Type.offset)
                        setRegister(dst, ScalarValue(SbfType.NumType(sbfTypeFac.offsetToNum(diff))))
                    } else {
                        throw ScalarDomainError("Unexpected pointer arithmetic $dst:= $op1 $op $op2")
                    }
                } else {
                    throw ScalarDomainError("cannot mix pointer from different memory regions ($op1Type and $op2Type)")
                }
            }
            is SbfType.Top -> {
                setRegister(dst, ScalarValue(op1Type.withTopOffset(sbfTypeFac)))
            }
            else -> {
                throw ScalarDomainError("unexpected type $op2Type for operand $op2")
            }
        } // end when
    }

    private fun doPointerArithmetic(op: BinOp, dst: Value.Reg, src: Value.Reg, locInst: LocatedSbfInstruction) {
        val dstType = getRegister(dst).type()
        val srcType = getRegister(src).type()

        if (dstType is SbfType.PointerType) {
            doNormalizedPointerArithmetic(op, dst, dst, dstType, src, srcType, locInst)
        } else if (srcType is SbfType.PointerType && op.isCommutative) {
            doNormalizedPointerArithmetic(op, dst, src, srcType, dst, dstType, locInst)
        } else {
            forget(dst)
        }
    }

    private fun doALU(op: BinOp, dst: Value.Reg, dstType: SbfType.NumType<TNum, TOffset>, srcType: SbfType.NumType<TNum, TOffset>) {
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

    private fun getValue(x: Value, globals: GlobalVariableMap): ScalarValue<TNum, TOffset> {
        when (x) {
            is Value.Imm -> {
                // We cast a number to a global variable if it matches an address from our [globals] map
                val address = x.v
                if (address <= Long.MAX_VALUE.toULong()) {
                    val gv = globals[address.toLong()]
                    if (gv != null) {
                        return ScalarValue(sbfTypeFac.toGlobalPtr(0L, gv))
                    }
                }
                return ScalarValue(sbfTypeFac.toNum(x.v))
            }
            is Value.Reg -> {
                return getRegister(x)
            }
        }
    }

    private fun analyzeBin(locInst: LocatedSbfInstruction, globals: GlobalVariableMap) {
        check(!isBottom()) {"analyzeBin cannot be called on bottom"}

        val stmt = locInst.inst
        check(stmt is SbfInstruction.Bin)

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
                        getValue(src, globals)
                    }  else {
                        getValue(src)
                    })
                }
                else ->  {
                    val dstType = refineType(stmt.op, getRegister(dst).type())
                    if (dstType is SbfType.NumType) {
                        doALU(stmt.op, dst, dstType, sbfTypeFac.toNum(src.v))
                    } else {
                        /**
                         * We don't know for sure whether dst is a pointer or not.
                         * doConstantPointerArithmetic will deal with that.
                         **/
                        doConstantPointerArithmetic(stmt.op, dst, sbfTypeFac.toNum(src.v), locInst)
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
                     *
                     * The use of `toLongOrNull` does not lose precision because the MOV instruction has been tagged with
                     * the metadata `SET_GLOBAL` which means that `src` is an immediate value.
                     */
                    setRegister(dst, if (stmt.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                        (getValue(src).type() as? SbfType.NumType<TNum, TOffset>)?.value?.toLongOrNull().let {
                            if (it != null) {
                                val gv = globals[it]
                                if (gv != null) {
                                    ScalarValue(sbfTypeFac.toGlobalPtr(0L, gv))
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
                    val dstType = refineType(stmt.op, getRegister(dst).type())
                    val srcTypeBefore = getRegister(src as Value.Reg).type()
                    val srcType = refineType(stmt.op, srcTypeBefore)
                    if (!srcTypeBefore.lessOrEqual(srcType)) {
                        // srcType is strictly more precise than srcTypeBefore
                        setRegister(src, ScalarValue(srcType))
                    }
                    if (dstType is SbfType.NumType && srcType is SbfType.NumType) {
                        doALU(stmt.op, dst, dstType, srcType)
                    } else {
                        if (srcType is SbfType.NumType) {
                            if (!srcType.value.isTop()) {
                                doConstantPointerArithmetic(stmt.op, dst, srcType, locInst)
                                return
                            }
                        }
                        doPointerArithmetic(stmt.op, dst, src, locInst)
                    }
                }
            }
        }
    }

    /** Transfer function for `__CVT_save_scratch_registers` **/
    private fun saveScratchRegisters() {
        base.saveScratchRegisters()
    }

    /**
     *  Transfer function for `__CVT_restore_scratch_registers`
     *  Invariant ensured by CFG construction: r10 has been decremented already
     **/
    private fun restoreScratchRegisters() {
        val stackPtr = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val topStack = (getRegister(stackPtr).type() as? SbfType.PointerType.Stack)?.offset?.toLongOrNull()
        check(topStack != null){ "r10 should point to a statically known stack offset"}
        base.restoreScratchRegisters(topStack)
    }

    /** Extracts known constant length from a register, or throws a detailed error. */
    private fun extractKnownLength(locInst: LocatedSbfInstruction, reg: Value.Reg): Long {
        // For now, we use `toLongOrNull` which means that the length must be exactly known.
        // This is something that we can improve if needed.
        return (getRegister(reg).type() as? SbfType.NumType)?.value?.toLongOrNull()
            ?: throw UnknownMemcpyLenError(
                DevErrorInfo(
                    locInst,
                    PtrExprErrReg(reg),
                    "Statically unknown length in $reg"
                )
            )
    }

    /** Ensures a stack pointer offset is not top, otherwise throws an exception */
    private fun ensureKnownStackOffset(
        locInst: LocatedSbfInstruction,
        offset: TOffset,
        reg: Value.Reg
    ) {
        if (offset.isTop()) {
            throw UnknownStackPointerError(
                DevErrorInfo(
                    locInst,
                    PtrExprErrReg(reg),
                    "Statically unknown stack offset $reg"
                )
            )
        }
    }

    /** Removes all (or partial) overlapping stack slices at the destination offsets. */
    private fun removeDstSlices(dstOffsets: List<Long>,
                                len: Long,
                                onlyPartial: Boolean,
                                pred: (ByteRange) -> Boolean = { _ -> true}) {
        dstOffsets.forEach { dstOffset ->
            base.removeStackSliceIf(dstOffset, len, onlyPartial, pred)
        }
    }

    /**
     * Analyze `memcpy(dst, src, len)` and `memmove(dst, src, len)`
     *
     * - If `memcpy` then
     *   1. Remove all the environment entries that might overlap with `[dstOffset, dstOffset+len)`
     *   2. Copy environment entries from `[srcOffset, srcOffset+len)` to `[dstOffset, dstOffset+len)`
     * - If `memmove` then
     *   1. Remove all the environment entries that might overlap with `[dstOffset, dstOffset+len)`
     *
     * Thus, the analysis of `memcpy` is precise but the analysis of `memmove` is a rough over-approximation.
     * **/
    private fun analyzeMemTransfer(locInst: LocatedSbfInstruction) {
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Call)
        val solanaFunction = SolanaFunction.from(stmt.name)
        check(solanaFunction == SolanaFunction.SOL_MEMCPY || solanaFunction == SolanaFunction.SOL_MEMMOVE)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        if (!stmt.isPromotedMemcpy()) {
            forget(r0)
        }

        val dstType = getRegister(r1).type()
        if (dstType is SbfType.PointerType.Stack) {
            val len = extractKnownLength(locInst, r3)
            ensureKnownStackOffset(locInst, dstType.offset, r1)
            val dstOffsets = dstType.offset.toLongList()
            check(dstOffsets.isNotEmpty()) {"Scalar domain expects non-empty list"}

            when (solanaFunction) {
                SolanaFunction.SOL_MEMMOVE -> removeDstSlices(dstOffsets, len, onlyPartial = false)
                SolanaFunction.SOL_MEMCPY -> {
                    val r2 = Value.Reg(SbfRegister.R2_ARG)
                    when (val srcType = getRegister(r2).type()) {
                        is SbfType.PointerType.Stack -> {
                            if (srcType.offset.isTop()) {
                                removeDstSlices(dstOffsets, len, onlyPartial = false)
                            } else {
                                val srcOffsets = srcType.offset.toLongList()
                                check(srcOffsets.isNotEmpty()) { "Scalar domain expects non-empty list because it is not top" }

                                // Remove all the environment entries that might **partially** overlap with `[dstOffset, dstOffset+len)`
                                // By partially, we mean any slice that overlaps with `[dstOffset, dstOffset+len)`,
                                // but it is not included in `[dstOffset, dstOffset+len)`.
                                //
                                // We cannot remove directly all destination entries (i.e., `onlyPartial=false`) because we might need to do weak updates,
                                // so we need to remember old values.
                                removeDstSlices(dstOffsets, len, onlyPartial = true)

                                val dstFootprint = mutableSetOf<ByteRange>()
                                if (dstOffsets.size == 1) {
                                    val dstOffset = dstOffsets.single()
                                    // strong update
                                    base.copyStack(srcOffsets.first(), dstOffset, len, isWeak = false, dstFootprint)
                                    // followed by weak updates
                                    srcOffsets.drop(1).forEach { srcOffset ->
                                        base.copyStack(srcOffset, dstOffset, len, isWeak = true, dstFootprint)
                                    }
                                } else {
                                    // weak updates
                                    srcOffsets.forEach { srcOffset ->
                                        dstOffsets.forEach { dstOffset ->
                                            base.copyStack(srcOffset, dstOffset, len, isWeak = true, dstFootprint)
                                        }
                                    }
                                }
                                // Important for soundness
                                // If a destination byte hasn't been overwritten by a source then we must "kill" it.
                                // This is possible because the analysis might know nothing about the source so `memTransfer` can be a non-op.
                                removeDstSlices(dstOffsets, len, onlyPartial = false) { !dstFootprint.contains(it) }
                            }
                        }
                        else -> {
                            // We are conservative and remove any overlapping entry at the destination
                            removeDstSlices(dstOffsets, len, onlyPartial = false)
                        }
                    }
                }
                else -> check(false) {"unreachable"}
            }
        }
    }

    /** Transfer function for `memset(ptr, val, len)` **/
    private fun analyzeMemset(locInst: LocatedSbfInstruction) {
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Call)
        val solanaFunction = SolanaFunction.from(stmt.name)
        check(solanaFunction == SolanaFunction.SOL_MEMSET)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        forget(r0)

        val ptrType = getRegister(r1).type()
        if (ptrType is SbfType.PointerType.Stack) {
            val len = extractKnownLength(locInst, r3)
            ensureKnownStackOffset(locInst, ptrType.offset, r1)
            val offsets = ptrType.offset.toLongList()
            check(offsets.isNotEmpty()) { "Scalar domain expects non-empty list" }
            // We are conservative and remove any overlapping entry at the destination
            removeDstSlices(offsets, len, onlyPartial = false)
        }

    }

    private fun castNumToString(reg: Value.Reg, globals: GlobalVariableMap) {
        val oldType = getRegister(reg).type()
        if (oldType is SbfType.NumType) {
            val newType = oldType.castToPtr(sbfTypeFac, globals)
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
                    setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), ScalarValue(sbfTypeFac.anyNum()))
                }
                SolanaFunction.SOL_GET_CLOCK_SYSVAR, SolanaFunction.SOL_GET_RENT_SYSVAR -> {
                    summarizeCall(locInst, memSummaries)
                }
                SolanaFunction.SOL_SET_CLOCK_SYSVAR -> {
                    forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
                }
                SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMMOVE -> {
                    analyzeMemTransfer(locInst)
                }
                SolanaFunction.SOL_MEMSET -> {
                    analyzeMemset(locInst)
                }
                else -> {
                    forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
                }
            }
        } else {
            if (stmt.isAllocFn() && memSummaries.getSummary(stmt.name) == null) {
                /// This is only used for pretty-printing
                setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), ScalarValue(sbfTypeFac.anyHeapPtr()))
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
                                CVTCore.MASK_64, CVTCore.NONDET_ACCOUNT_INFO -> {
                                    summarizeCall(locInst, memSummaries)
                                }
                                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE -> {
                                    /// This is only used for pretty-printing
                                    setRegister(
                                        Value.Reg(SbfRegister.R0_RETURN_VALUE),
                                        ScalarValue(sbfTypeFac.anyInputPtr())
                                    )
                                }
                                CVTCore.ALLOC_SLICE -> {
                                    /// This is only used for pretty-printing
                                    /// That's why we return top in some cases rather than reporting an error
                                    val returnedVal = when (getRegister(Value.Reg(SbfRegister.R1_ARG)).type()) {
                                        is SbfType.PointerType.Heap -> sbfTypeFac.anyHeapPtr()
                                        is SbfType.PointerType.Input -> sbfTypeFac.anyInputPtr()
                                        is SbfType.PointerType.Global -> sbfTypeFac.anyGlobalPtr(null)
                                        is SbfType.PointerType.Stack -> sbfTypeFac.anyStackPtr()
                                        else -> sbfTypeFac.mkTop()
                                    }
                                    setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), ScalarValue(returnedVal))
                                }
                            }
                        }
                        is CVTFunction.Nondet,
                        is CVTFunction.U128Intrinsics,
                        is CVTFunction.I128Intrinsics,
                        is CVTFunction.NativeInt  ->  {
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
            private fun getScalarValue(ty: MemSummaryArgumentType): ScalarValue<TNum, TOffset> {
                return when(ty) {
                    MemSummaryArgumentType.NUM -> ScalarValue(sbfTypeFac.anyNum())
                    MemSummaryArgumentType.PTR_HEAP -> ScalarValue(sbfTypeFac.anyHeapPtr())
                    MemSummaryArgumentType.PTR_STACK -> ScalarValue(sbfTypeFac.anyStackPtr())
                    else -> ScalarValue(sbfTypeFac.mkTop())
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
                val regType = getRegister(Value.Reg(reg)).type()
                // We only keep track of the stack
                if (regType is SbfType.PointerType.Stack) {
                    // It is possible that `reg` can point to more than one stack offset
                    // (depends on the abstraction chosen for IOffset). In that case, the analysis will report a runtime error.
                    // The alternative is to do weak updates.
                    val baseOffset = regType.offset.toLongOrNull()
                    check(baseOffset != null) {"processArgument is accessing stack at a non-constant offset ${regType.offset}"}
                    base.updateStack(ByteRange(baseOffset + offset, width), getScalarValue(type), isWeak = false)
                }
            }
        }

        val vis = ScalarSummaryVisitor()
        memSummaries.visitSummary(locInst, vis)
    }

    private fun analyzeAssumeNumNum(op: CondOp,
                                    left: Value.Reg,
                                    leftType: SbfType.NumType<TNum, TOffset>,
                                    right: Value,
                                    rightType: SbfType.NumType<TNum, TOffset>) {

        val newLeftVal = leftType.value.filter(op, rightType.value)
        if (newLeftVal.isBottom()) {
            setToBottom()
            return
        }
        setRegister(left, ScalarValue(leftType.copy(value = newLeftVal)))

        if (right is Value.Reg) {
            val newRightVal = rightType.value.filter(op.swap(), leftType.value)
            if (newRightVal.isBottom()) {
                setToBottom()
                return
            }
            setRegister(right, ScalarValue(rightType.copy(value = newRightVal)))
        }
    }

    private fun analyzeAssumeTopNonTop(op: CondOp, left: Value.Reg, leftType: SbfType<TNum, TOffset>, rightType: SbfType<TNum, TOffset>) {
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

    private fun analyzeAssumePtrPtr(op: CondOp, left: Value.Reg, leftType: SbfType.PointerType<TNum, TOffset>,
                                    right: Value.Reg, rightType: SbfType.PointerType<TNum, TOffset>) {
        if (leftType.samePointerType(rightType)) {
            val leftOffset = leftType.offset
            val rightOffset = rightType.offset

            val newLeftOffset = leftOffset.filter(op, rightOffset)
            if (newLeftOffset.isBottom()) {
                setToBottom()
                return
            }

            val newRightOffset = rightOffset.filter(op, leftOffset)
            if (newRightOffset.isBottom()) {
                setToBottom()
                return
            }

            when (leftType) {
                is SbfType.PointerType.Stack -> {
                    setRegister(left, ScalarValue(SbfType.PointerType.Stack(newLeftOffset)))
                    setRegister(right, ScalarValue(SbfType.PointerType.Stack(newRightOffset)))
                }
                is SbfType.PointerType.Input -> {
                    setRegister(left, ScalarValue(SbfType.PointerType.Input(newLeftOffset)))
                    setRegister(right, ScalarValue(SbfType.PointerType.Input(newRightOffset)))
                }
                is SbfType.PointerType.Heap -> {
                    setRegister(left, ScalarValue(SbfType.PointerType.Heap(newLeftOffset)))
                    setRegister(right, ScalarValue(SbfType.PointerType.Heap(newRightOffset)))
                }
                is SbfType.PointerType.Global -> {
                    val leftGlobal = leftType.global
                    val rightGlobal = (rightType as SbfType.PointerType.Global).global
                    if (leftGlobal != null && rightGlobal != null && leftGlobal.address == rightGlobal.address) {
                        // The base addresses are the same but offset could be different
                        setRegister(left, ScalarValue(SbfType.PointerType.Global(newLeftOffset, leftGlobal)))
                        setRegister(right, ScalarValue(SbfType.PointerType.Global(newRightOffset, rightGlobal)))
                    }
                }
            }
        } else {
            throw ScalarDomainError("assume cannot have pointer operands of different type")
        }
    }

    @TestOnly
    fun analyzeAssume(cond: Condition) {
        val op = cond.op
        val leftReg = cond.left
        val rightVal = cond.right
        check(!isBottom()) {"analyzeAssume cannot be called on bottom"}
        val leftAbsValBefore = getRegister(leftReg)
        val leftAbsVal = ScalarValue(refineType(op,leftAbsValBefore.type()))
        if (!leftAbsValBefore.lessOrEqual(leftAbsVal)) {
            // leftAbsVal is strictly more precise than leftAbsValBefore
            setRegister(leftReg, leftAbsVal)
        }
        check(!leftAbsVal.isBottom()) {"analyzeAssume: leftAbsVal is bottom after refinement"}
        if (rightVal is Value.Imm) {
            val rightAbsVal = ScalarValue(sbfTypeFac.toNum(rightVal.v))
            if (leftAbsVal.type() is SbfType.NumType) {
                analyzeAssumeNumNum(op,
                                    leftReg,
                                    leftAbsVal.type() as SbfType.NumType,
                                    rightVal,
                                    rightAbsVal.type() as SbfType.NumType)
            } else if (leftAbsVal.type() is SbfType.PointerType) {
                // do nothing: we can do better here if op is EQ
            } else if (leftAbsVal.isTop()) {
                /**
                 * We assume that the left operand is a number,
                 * although we don't really know at this point.
                 **/
                analyzeAssumeTopNonTop(op, leftReg, leftAbsVal.type(), rightAbsVal.type())
            }
        } else {
            val rightAbsValBefore = getRegister(rightVal as Value.Reg)
            val rightAbsVal = ScalarValue(refineType(op, rightAbsValBefore.type()))
            if (!rightAbsValBefore.lessOrEqual(rightAbsVal)) {
                // rightAbsVal is strictly more precise than rightAbsValBefore
                setRegister(rightVal, rightAbsVal)
            }
            check(!rightAbsVal.isBottom()) {"analyzeAssume: rightAbsVal is bottom after refinement"}
            if (leftAbsVal.isTop() && rightAbsVal.isTop()) {
                // do nothing
            } else if (leftAbsVal.isTop() || rightAbsVal.isTop()) {
                analyzeAssumeTopNonTop(op, leftReg, leftAbsVal.type(), rightAbsVal.type())
                analyzeAssumeTopNonTop(op, rightVal, leftAbsVal.type(), rightAbsVal.type())
            } else {
                val leftType = leftAbsVal.type()
                val rightType = rightAbsVal.type()
                if (leftType is SbfType.NumType && rightType is SbfType.NumType) {
                    analyzeAssumeNumNum(op, leftReg, leftType, rightVal, rightType)
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
        forget(stmt.dst)
    }

    private fun refineSelectCond(cond: Condition, other: ScalarDomain<TNum, TOffset>) {
        fun refine(x: ScalarValue<TNum, TOffset>, y: ScalarValue<TNum, TOffset>) = if (x.isTop()) { y } else { x }
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
            setRegister(stmt.dst, getValue(stmt.falseVal))
        } else {
            val falseAbsVal = deepCopy()
            falseAbsVal.analyzeAssume(stmt.cond.negate())
            if (falseAbsVal.isBottom()) {
                setRegister(stmt.dst, getValue(stmt.trueVal))
            } else {
                refineSelectCond(stmt.cond, trueAbsVal.join(falseAbsVal))
                setRegister(stmt.dst,
                            getValue(stmt.falseVal)
                                .join(getValue(stmt.trueVal)))
            }
        }
    }

    private fun forgetOrNum(v: Value.Reg, isNum: Boolean) {
        if (isNum) {
            // This should be always a "weak" read because we can read twice from the same memory location
            // but one loaded value can be considered as non-pointer because it's never de-referenced but the other one can be de-referenced.
            // Since the scalar domain is non-relation all reads are weak anyway.
            setRegister(v, ScalarValue(sbfTypeFac.anyNum()))
        } else {
            forget(v)
        }
    }

    override fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg, globalsMap: GlobalVariableMap): ScalarValue<TNum, TOffset> {
        check(!isBottom()) {"getAsScalarValueWithNumToPtrCast cannot be called on bottom"}
        val scalarVal = getRegister(reg)
        val type = scalarVal.type()
        if (type is SbfType.NumType) {
            // We use `toLongOrNull` because we are interested in the case where `n` is definitely `0`
            val n = type.value.toLongOrNull()
            if (n == 0L) {
                 // The constant zero might represent the NULL pointer, de-referencing it is undefined behavior.
                return ScalarValue.mkBottom()
            }
            // Attempt to cast a number to a pointer
            type.castToPtr(sbfTypeFac, globalsMap)?.let {
                return ScalarValue(it)
            }
        }
        return scalarVal
    }

    /**
     *  Return the abstract value of the base register if it will be killed by the lhs of a load instruction.
     *  Otherwise, it returns null. This is used by the Memory Domain.
     **/
    private fun analyzeMem(locInst: LocatedSbfInstruction, globalsMap: GlobalVariableMap) {
        check(!isBottom()) {"analyzeMem cannot be called on bottom"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Mem) {"analyzeMem expect a memory instruction instead of $stmt"}

        val baseReg = stmt.access.baseReg
        val offset = stmt.access.offset
        val width = stmt.access.width
        val value = stmt.value
        val isLoad = stmt.isLoad

        val baseScalarVal = getAsScalarValueWithNumToPtrCast(baseReg, globalsMap)
        if (baseScalarVal.isBottom()) {
            setToBottom()
            return
        } else {
            // `baseScalarVal` can be different from `getRegister(baseReg)` if `getRegisterWithNumAsPtrCast`
            //  performs some cast
            setRegister(baseReg, baseScalarVal)
        }

        val baseType = baseScalarVal.type()
        checkStackInBounds(baseType, locInst)
        val loadedAsNumForPTA = stmt.metaData.getVal(SbfMeta.LOADED_AS_NUM_FOR_PTA) != null
        when (baseType) {
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
                when (baseType) {
                    is SbfType.PointerType.Stack -> {
                        // We try to be precise when load/store from/to stack
                        val stackTOffsets = baseType.offset.add(offset.toLong())
                        check(!stackTOffsets.isBottom())
                        if (stackTOffsets.isTop()) {
                            if (isLoad) {
                                forgetOrNum(value as Value.Reg, loadedAsNumForPTA)
                            } else {
                                throw UnknownStackPointerError(DevErrorInfo(locInst, PtrExprErrReg(baseReg), "store: $stmt to unknown stack location"))
                            }
                        } else {
                            val stackOffsets = stackTOffsets.toLongList()
                            if (isLoad) {
                                setRegister(value as Value.Reg,
                                    stackOffsets.fold(ScalarValue(sbfTypeFac.mkBottom())) { acc, stackOffset ->
                                        val loadedAbsVal = base.getStackSingletonOrNull(ByteRange(stackOffset, width.toByte()))
                                        when {
                                            loadedAbsVal != null -> acc.join(loadedAbsVal)
                                            loadedAsNumForPTA -> acc.join(ScalarValue(sbfTypeFac.anyNum()))
                                            else -> ScalarValue(sbfTypeFac.mkTop())
                                        }
                                    })
                            } else {
                                if (stackOffsets.size == 1) {
                                    // Strong update:
                                    // 1. Remove first **all** overlapping entries
                                    // 2. Add a new entry
                                    val stackOffset = stackOffsets.single()
                                    val slice = ByteRange(stackOffset, width.toByte())
                                    // onlyPartial=false means that any overlapping entry is killed
                                    base.removeStackSliceIf(slice.offset, slice.width.toLong(), onlyPartial = false)
                                    base.updateStack(slice, getValue(value), isWeak = false)
                                } else {
                                    // Weak update:
                                    // for each possible stack offset
                                    //    1. Remove first **partial** overlapping entries
                                    //    2. join old value with new value
                                    stackOffsets.forEach {
                                        val slice = ByteRange(it, width.toByte())
                                        // onlyPartial=true + isWeak=true means that
                                        //   if slice is already in `stack` then its value is not removed and `stack.put` will do a weak update with `value`.
                                        //   Any other overlapping entry will be removed by `killOffsets`
                                        base.removeStackSliceIf(slice.offset, slice.width.toLong(), onlyPartial = true)
                                        base.updateStack(slice, getValue(value), isWeak = true)
                                    }
                                }
                            }

                        }
                    }
                    is SbfType.PointerType.Global -> {
                        if (isLoad) {
                            forgetOrNum(value as Value.Reg, loadedAsNumForPTA)

                            val globalVar = baseType.global
                            if (globalVar != null) {
                                if (globalVar is SbfConstantNumGlobalVariable) {
                                    setRegister(value, ScalarValue(sbfTypeFac.toNum(globalVar.value)))
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
    }

    fun getValue(value: Value): ScalarValue<TNum, TOffset> {
        return when (value) {
            is Value.Imm -> {
                ScalarValue(sbfTypeFac.toNum(value.v))
            }
            is Value.Reg -> {
                getRegister(value)
            }
        }
    }

    override fun getAsScalarValue(value: Value): ScalarValue<TNum, TOffset> = getValue(value)

    override fun getStackContent(offset: Long, width: Byte): ScalarValue<TNum, TOffset> {
        return if (isBottom()) {
            ScalarValue(sbfTypeFac.mkBottom())
        } else {
            base.getStackSingletonOrNull(ByteRange(offset, width)) ?: ScalarValue(sbfTypeFac.mkTop())
        }
    }

    override fun setScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>) {
        if (!isBottom()) {
            setRegister(reg, newVal)
        }
    }

    override fun setStackContent(offset: Long, width: Byte, value: ScalarValue<TNum, TOffset>) {
        base.updateStack(ByteRange(offset, width), value, isWeak = false)
    }

    fun analyze(locInst: LocatedSbfInstruction,
                globals: GlobalVariableMap,
                memSummaries: MemorySummaries) {
        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> analyzeUn(s)
                is SbfInstruction.Bin -> analyzeBin(locInst, globals)
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
        dbg { "After $s: $this\n" }
    }

    override fun analyze(b: SbfBasicBlock,
                         globals: GlobalVariableMap,
                         memSummaries: MemorySummaries,
                         listener: InstructionListener<ScalarDomain<TNum, TOffset>>): ScalarDomain<TNum, TOffset> =
        analyzeBlock(b,
                    inState = this,
                    transferFunction = { mutState, locInst ->
                        mutState.analyze(locInst, globals, memSummaries)
                    },
                    listener)

    override fun toString() = base.toString()
}

/** To be reused by other scalar domain variants **/
fun<ScalarDomain: AbstractDomain<ScalarDomain>> analyzeBlock(
    b: SbfBasicBlock,
    inState: ScalarDomain,
    transferFunction: (mutState: ScalarDomain, locInst: LocatedSbfInstruction) -> Unit,
    listener: InstructionListener<ScalarDomain>): ScalarDomain {


    dbg { "=== Scalar domain analyzing ${b.getLabel()} ===\n" +
          "At entry: $inState\n"
    }

    if (listener is DefaultInstructionListener) {
        /**
         * No need to remember abstract states before and after each instruction
         **/
        if (inState.isBottom()) {
            return inState
        }

        val outState = inState.deepCopy()
        for (locInst in b.getLocatedInstructions()) {
            transferFunction(outState, locInst)
            if (outState.isBottom()) {
                break
            }
        }
        return outState
    } else {
        /**
         * This case is when call to reconstruct abstract states at each instruction.
         * Extra deep copies for the listener.
         **/
        var before = inState
        for (locInst in b.getLocatedInstructions()) {
            val after = before.deepCopy()
            listener.instructionEventBefore(locInst, before)
            transferFunction(after, locInst)
            listener.instructionEventAfter(locInst, after)
            // Calling to this listener requires to make an extra copy
            // It's used by class AnnotateWithTypesListener defined in AnnotateCFG.kt
            listener.instructionEvent(locInst, before, after)
            before = after
        }
        return before
    }
}
