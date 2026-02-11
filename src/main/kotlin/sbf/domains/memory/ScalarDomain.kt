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

private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>
    SbfType<TNum, TOffset>.mapNum(
    f: (TNum) -> TNum
): SbfType<TNum, TOffset> =
    when (this) {
        is SbfType.Top,
        is SbfType.Bottom -> this
        // Zero-extension is undefined for pointers. We conservatively keep the value unchanged.
        is SbfType.PointerType -> this
        is SbfType.NumType -> copy(value = f(value))
    }


private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>
    SbfType<TNum, TOffset>.zext8(): SbfType<TNum, TOffset> = mapNum { it.zext8() }

private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>
    SbfType<TNum, TOffset>.zext16(): SbfType<TNum, TOffset> = mapNum { it.zext16() }

private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>
    SbfType<TNum, TOffset>.zext32(): SbfType<TNum, TOffset> = mapNum { it.zext32() }

fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>
    ScalarValue<TNum, TOffset>.zext(n: Long) =
    when(n) {
        1L -> ScalarValue(this.type().zext8())
        2L -> ScalarValue(this.type().zext16())
        4L -> ScalarValue(this.type().zext32())
        8L -> this
        else -> error("unsupported bit-width $n in zext operation")
    }

/** Return true if [n] is 0 or a small power of two **/
fun isZeroOrSmallPowerOfTwo(n: Long): Boolean =
    n in setOf(0L, 1L, 2L, 4L, 8L, 16L, 32L, 64L)

/** Return true if the analysis is certain that `this` must be a number, and thus it cannot be a pointer **/
private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> SbfType<TNum, TOffset>.mustBeNumber(): Boolean {
    if (this !is SbfType.NumType) {
        return false
    }

    val v = this.value
    // if it's NumType then it shouldn't be bottom
    check(!v.isBottom())

    return when {
        v.isTop() -> false  // unknown number, can still be a pointer
        else -> {
            val values = v.toLongList()
            // cannot be empty because it's not top
            check(values.isNotEmpty())
            // - zero can be the null pointer
            // - a small power of two can be a dangling pointer
            values.all { !isZeroOrSmallPowerOfTwo(it) }
        }
    }
}

class ScalarDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> private constructor(
    private val base: ScalarBaseDomain<ScalarValue<TNum, TOffset>>,
    val sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
    val globalState: GlobalState
) : MutableAbstractDomain<ScalarDomain<TNum, TOffset>>,
    ScalarValueProvider<TNum, TOffset>,
    MutableScalarValueUpdater<TNum, TOffset>,
    MemoryDomainScalarOps<TNum, TOffset> {

    constructor(
        sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        initPreconditions: Boolean = false
    ): this(ScalarBaseDomain(ValueFactory(sbfTypeFac)), sbfTypeFac, globalState) {
        if (initPreconditions) {

            val initialOffset = getInitialStackOffset(globalState.globals.elf.useDynamicFrames())
            setRegister(
                Value.Reg(SbfRegister.R10_STACK_POINTER),
                ScalarValue(sbfTypeFac.toStackPtr(initialOffset))
            )
        }
    }

    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeBottom(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState
        ): ScalarDomain<TNum, TOffset> {
            val res = ScalarDomain(sbfTypeFac, globalState)
            res.setToBottom()
            return res
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeTop(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState
        ) = ScalarDomain(sbfTypeFac, globalState)
    }

    override fun deepCopy(): ScalarDomain<TNum, TOffset> =
        ScalarDomain(base.deepCopy(), sbfTypeFac, globalState)

    override fun isBottom() = base.isBottom()

    override fun isTop() = base.isTop()

    override fun setToBottom() {
        base.setToBottom()
    }

    override fun join(other: ScalarDomain<TNum, TOffset>, left: Label?, right: Label?) =
        ScalarDomain(base.join(other.base), sbfTypeFac, globalState)

    override fun widen(other: ScalarDomain<TNum, TOffset>, b: Label?) =
        ScalarDomain(base.widen(other.base), sbfTypeFac, globalState)

    override fun lessOrEqual(other: ScalarDomain<TNum, TOffset>, left: Label?, right: Label?) =
        base.lessOrEqual(other.base)

    override fun pseudoCanonicalize(other: ScalarDomain<TNum, TOffset>) = this.deepCopy()

    /** TRANSFER FUNCTIONS **/

    /**
     * Check that if `value` is a stack pointer then its offset must be non-negative.
     */
    private fun checkStackOffset(value: ScalarValue<TNum, TOffset>) {
        if (globalState.globals.elf.useDynamicFrames()) {
            return
        }

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
        if (globalState.globals.elf.useDynamicFrames()) {
            return
        }

        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        val isMemcpyPromotion = listOf(
            SbfMeta.MEMCPY_PROMOTION,
            SbfMeta.MEMCPY_ZEXT_PROMOTION,
            SbfMeta.MEMCPY_TRUNC_PROMOTION
        ).any { locInst.inst.metaData.getVal(it) != null }

        // Skip validation for memcpy promotions, as they normalize stack pointers relative to r10,
        // which can result in offsets exceeding the current frame size when accessing caller stacks
        if (isMemcpyPromotion) {
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
        if (globalState.globals.elf.useDynamicFrames()) {
            return
        }

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
        checkStackOffset(value)
        base.setRegister(reg, value)
    }

    override fun forget(reg: Value.Reg) {
        base.forget(reg)
    }

    override fun forget(regs: Iterable<Value.Reg>): ScalarDomain<TNum, TOffset> {
        return ScalarDomain(
            base.forget(regs),
            sbfTypeFac,
            globalState
        )
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

    /**
     * Encapsulate the types of the operands of a binary operation.
     *
     * Order is important since the operation might not be commutative.
     */
    data class BinaryTypes<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
        val left: SbfType<TNum, TOffset>,
        val right: SbfType<TNum, TOffset>
    )

    /**
     * Refines the [types] of the operands of an arithmetic operation [op]
     * by applying some simple type inference rules to validate program type safety.
     *
     * This refinement is sound: if a value refined to a numeric type is actually
     * a pointer, the scalar domain will detect and report the error when the
     * pointer is de-referenced.
     */
    private fun refineTypes(
        op: BinOp,
        types: BinaryTypes<TNum, TOffset>
    ): BinaryTypes<TNum, TOffset> {
        val (left, right) = types
        return when (op) {
            // num - top ~> num - num
            BinOp.SUB -> {
                when (left) {
                    is SbfType.NumType ->
                        BinaryTypes(
                            left,
                            right.leftBiasedMeet(sbfTypeFac.anyNum())
                        )
                    else -> types
                }
            }
            // top << top ~> num << num
            // top >> top ~> num >> num
            // top / top  ~> num / num
            // top % top  ~> num % num
            // top * top  ~> num * num
            BinOp.ARSH,
            BinOp.LSH,
            BinOp.RSH,
            BinOp.DIV,
            BinOp.MOD,
            BinOp.MUL -> BinaryTypes(
                left.leftBiasedMeet(sbfTypeFac.anyNum()),
                right.leftBiasedMeet(sbfTypeFac.anyNum())
            )
            else -> types
        }
    }

    /**
     * Refines the [types] of the operand of a comparison [op]
     * by applying some simple type inference rules to validate program type safety.
     *
     * This refinement is sound: if a value refined to a numeric type is actually
     * a pointer, the scalar domain will detect and report the error when the
     * pointer is de-referenced.
     */
    private fun refineTypes(
        op: CondOp,
        types: BinaryTypes<TNum, TOffset>
    ): BinaryTypes<TNum, TOffset> {
        val (left, right) = types
        return when (op) {
            // top op top ~> num op num  if op not in {=, !=}
            CondOp.SGE,
            CondOp.SGT,
            CondOp.SLE,
            CondOp.SLT,
            CondOp.GE,
            CondOp.GT,
            CondOp.LE,
            CondOp.LT ->
                BinaryTypes(
                    left.leftBiasedMeet(sbfTypeFac.anyNum()),
                    right.leftBiasedMeet(sbfTypeFac.anyNum())
                )
            else -> types
        }
    }

    private fun updateType(
        reg: Value.Reg,
        newType: SbfType<TNum, TOffset>
    ): ScalarValue<TNum, TOffset>  {
        check(!newType.isBottom()) { "cannot call updateType with bottom" }
        val old = getRegister(reg)
        val new = ScalarValue(newType)
        if (!old.lessOrEqual(new)) {
            // new is strictly more precise than old
            setRegister(reg, new)
        }
        return new
    }

    /**
     * Transfer function for `dst = ptrType op operandType` when [ptrType] is known to be a pointer.
     *
     * - If [operandType] is a number then [dst] is a pointer as [ptrType] whose offset is updated with [operandType]
     * - If [operandType] is a pointer then the [dst] is a number whose value is the difference between two pointers
     * - If [operandType] is top then [dst] is a pointer but with unknown offset
    **/
    private fun doPointerArithmetic(
        op: BinOp,
        dst: Value.Reg,
        ptrType: SbfType.PointerType<TNum, TOffset>,
        operandType: SbfType<TNum, TOffset>,
        locInst: LocatedSbfInstruction
    ) {
        val inst = locInst.inst
        when (operandType) {
            // ptr(o) +/- num ~> ptr(o +/- num)
            // ptr(o) op num  ~> ptr(top)  (error if enableDefensiveChecks)
            is SbfType.NumType -> {
                val dstPtrType = when (op) {
                    BinOp.ADD  ->
                        ptrType.withOffset(ptrType.offset.add(sbfTypeFac.numToOffset(operandType.value)))
                    BinOp.SUB  ->
                        ptrType.withOffset(ptrType.offset.sub(sbfTypeFac.numToOffset(operandType.value)))
                    else -> {
                        if (enableDefensiveChecks) {
                            throw ScalarDomainError("Unexpected pointer arithmetic in $inst")
                        }
                        ptrType.withTopOffset(sbfTypeFac)
                    }
                }
                checkStackInBounds(getRegister(dst).type(), dstPtrType, locInst)
                setRegister(dst, ScalarValue(dstPtrType))
            }
            // ptr(o1)  - ptr(o2) ~> num(o1 - o2)
            // ptr(o1) op ptr(o2) ~> error
            is SbfType.PointerType -> {
                if (!ptrType.samePointerType(operandType)) {
                    throw ScalarDomainError(
                        "cannot mix pointer from different memory regions ($ptrType and $operandType)"
                    )
                }
                if (op != BinOp.SUB) {
                    throw ScalarDomainError("Unexpected pointer arithmetic in $inst")
                }

                // subtraction of pointers of the same type is okay
                val diff = ptrType.offset.sub(operandType.offset)
                setRegister(dst, ScalarValue(SbfType.NumType(sbfTypeFac.offsetToNum(diff))))
            }
            // ptr(o) op top ~> ptr(top)
            is SbfType.Top -> {
                setRegister(dst, ScalarValue(ptrType.withTopOffset(sbfTypeFac)))
            }
            else -> {
                throw ScalarDomainError("unexpected type $operandType for right operand in $inst")
            }
        }
    }

    private fun doALU(
        op: BinOp,
        dst: Value.Reg,
        dstType: SbfType.NumType<TNum, TOffset>,
        srcType: SbfType.NumType<TNum, TOffset>
    ) {
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

    private fun getValueWithGlobals(x: Value): ScalarValue<TNum, TOffset> {
        when (x) {
            is Value.Imm -> {
                // We cast a number to a global variable if it matches an address from our globals map
                val address = x.v
                if (address <= Long.MAX_VALUE.toULong()) {
                    globalState.globals.findGlobalThatContains(address.toLong())?.let { gv ->
                        return ScalarValue(sbfTypeFac.toGlobalPtr(address.toLong(), gv))
                    }
                }
                return ScalarValue(sbfTypeFac.toNum(x.v))
            }
            is Value.Reg -> {
                return getRegister(x)
            }
        }
    }

    private fun analyzeBin(locInst: LocatedSbfInstruction) {
        check(!isBottom()) {"analyzeBin cannot be called on bottom"}

        val stmt = locInst.inst
        check(stmt is SbfInstruction.Bin)

        val globals = globalState.globals
        val dst = stmt.dst
        val src = stmt.v
        val op = stmt.op
        if (src is Value.Imm) {
            // dst := dst op k
            when (op) {
                BinOp.MOV -> {
                    /**
                     * We assume that the destination operand is a number unless the analysis that
                     * infers globals says it is a global variable.
                     **/
                    setRegister(dst, if (stmt.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                        getValueWithGlobals(src)
                    }  else {
                        getValue(src)
                    })
                }
                else ->  {
                    val srcType = sbfTypeFac.toNum(src.v)
                    val (dstType, _) = refineTypes(op, BinaryTypes(getRegister(dst).type(), srcType))
                    when (dstType) {
                        is SbfType.NumType ->
                            doALU(op, dst, dstType, srcType)
                        is SbfType.PointerType ->
                            doPointerArithmetic(
                                op,
                                dst,
                                ptrType = dstType,
                                operandType = srcType,
                                locInst
                            )
                        else ->
                            forget(dst)
                    }
                }
            }
        } else {
            // dst := dst op src
            when (op) {
                BinOp.MOV -> {
                    /**
                     * If we know that src is the address of a global variable then we cast
                     * the destination to that global variable.
                     *
                     * The use of `toLongOrNull` does not lose precision because the MOV
                     * instruction has been tagged with the metadata `SET_GLOBAL` which
                     * means that `src` is an immediate value.
                     */
                    setRegister(dst, if (stmt.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                        (getValue(src).type() as? SbfType.NumType<TNum, TOffset>)?.value?.toLongOrNull().let {
                            if (it != null) {
                                globals.findGlobalThatContains(it)?.let { gv ->
                                    ScalarValue(sbfTypeFac.toGlobalPtr(it, gv))
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
                    // Refine operands before performing the transfer function
                    val (dstType, srcType) = refineTypes(
                        op,
                        BinaryTypes(
                            getRegister(dst).type(),
                            getRegister(src as Value.Reg).type()
                        )
                    )
                    updateType(src, srcType)
                    // Performing transfer function as either an ALU operation or pointer arithmetic
                    when {
                        dstType is SbfType.NumType && srcType is SbfType.NumType ->
                            doALU(op, dst, dstType, srcType)
                        dstType is SbfType.PointerType  ->
                            doPointerArithmetic(
                                op,
                                dst,
                                dstType,
                                srcType,
                                locInst
                            )
                        srcType is SbfType.PointerType && op.isCommutative ->
                            doPointerArithmetic(
                                op,
                                dst,
                                srcType,
                                dstType,
                                locInst
                            )
                        else ->
                            forget(dst)
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
        base.restoreScratchRegisters(topStack, globalState.globals.elf.useDynamicFrames())
    }

    /** Extracts known constant length from a register, or throws a detailed error. */
    private fun extractKnownLength(locInst: LocatedSbfInstruction, lenReg: Value.Reg) =
        (getRegister(lenReg).type() as? SbfType.NumType)?.value?.toLongOrNull()
            ?: throw UnknownMemcpyLenError(
                DevErrorInfo(
                    locInst,
                    PtrExprErrReg(lenReg),
                    "Statically unknown length in $lenReg"
                )
            )


    /** Ensures a stack pointer offset is not top, otherwise throws an exception */
    private fun ensureKnownStackOffset(
        locInst: LocatedSbfInstruction,
        reg: Value.Reg,
        offset: TOffset
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
     * Analyze `memcpy(dst, src, len)`
     *
     * 1. Remove all the environment entries that might overlap with `[dstOffset, dstOffset+len)`
     * 2. Copy environment entries from `[srcOffset, srcOffset+len)` to `[dstOffset, dstOffset+len)`
     **/
    private fun analyzeMemcpy(locInst: LocatedSbfInstruction)  {

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val dstReg = Value.Reg(SbfRegister.R1_ARG) // dst
        val srcReg = Value.Reg(SbfRegister.R2_ARG) // src
        val lenReg = Value.Reg(SbfRegister.R3_ARG) // len

        if (locInst.inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        val dstType = getRegister(dstReg).type() as? SbfType.PointerType.Stack ?: return
        val len = extractKnownLength(locInst, lenReg)
        ensureKnownStackOffset(locInst, dstReg, dstType.offset)
        val dstOffsets = dstType.offset.toLongList()
        check(dstOffsets.isNotEmpty())

        when (val srcType = getRegister(srcReg).type()) {
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

    /**
     * Transfer function for `memset(ptr, val, len)` and `memmove(dst, src, len)`
     *
     * Remove all the environment entries that might overlap with `[x, x+len)` where `x`
     * is the offset of `ptr` or `dst` if they are stack pointers.
     *
     * Note that the analysis of `memmove` is a rough over-approximation.
     **/
    private fun analyzeMemsetOrMemmove(locInst: LocatedSbfInstruction) {
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val dstReg = Value.Reg(SbfRegister.R1_ARG) // dst
        val lenReg = Value.Reg(SbfRegister.R3_ARG) // len

        if (locInst.inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        val dstType = getRegister(dstReg).type() as? SbfType.PointerType.Stack ?: return
        val len = extractKnownLength(locInst, lenReg)
        val dstOffset = dstType.offset
        ensureKnownStackOffset(locInst, dstReg, dstOffset)
        val offsets = dstOffset.toLongList()
        check(offsets.isNotEmpty())

        // We are conservative and remove any overlapping entry at the destination
        removeDstSlices(offsets, len, onlyPartial = false)
    }

    /**
     * Analyze `memcpy_zext(dst, src, i)`
     **/
    private fun analyzeMemcpyZext(locInst: LocatedSbfInstruction) {
        analyzeMemcpyZExtOrTrunc(
            locInst = locInst,
            srcSize = { i -> i.toByte() },
            dstSize = { 8 },
            transformValue = { srcVal, i -> srcVal.zext(i) }
        )
    }

    /**
     * Analyze `memcpy_trunc(dst, src, i)`
     **/
    private fun analyzeMemcpyTrunc(locInst: LocatedSbfInstruction)  {
        analyzeMemcpyZExtOrTrunc(
            locInst = locInst,
            srcSize = { 8 },
            dstSize = { i -> i.toByte() },
            transformValue = { srcVal, _ -> srcVal }
        )
    }

    private fun analyzeMemcpyZExtOrTrunc(
        locInst: LocatedSbfInstruction,
        srcSize: (Long) -> Byte,
        dstSize: (Long) -> Byte,
        transformValue: (ScalarValue<TNum, TOffset>, Long) -> ScalarValue<TNum, TOffset>
    ) {

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val dstReg = Value.Reg(SbfRegister.R1_ARG) // dst
        val srcReg = Value.Reg(SbfRegister.R2_ARG) // src
        val iReg = Value.Reg(SbfRegister.R3_ARG)   // i

        if (locInst.inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        val dstType = getRegister(dstReg).type() as? SbfType.PointerType.Stack ?: return
        ensureKnownStackOffset(locInst, dstReg, dstType.offset)
        val dstOffsets = dstType.offset.toLongList()
        check(dstOffsets.isNotEmpty())
        val dstOffset = dstOffsets.singleOrNull()

        val srcOffset = (getRegister(srcReg).type() as? SbfType.PointerType.Stack)
            ?.offset
            ?.toLongOrNull()

        val i = extractKnownLength(locInst, iReg)

        when {
            srcOffset == null || dstOffset == null -> {
                dstOffsets.forEach { o ->
                    val dst = ByteRange(o, dstSize(i))
                    base.updateStack(dst, ScalarValue(sbfTypeFac.mkTop()), isWeak = false)
                }
            }
            else -> {
                check (i < 8)
                val src = ByteRange(srcOffset, srcSize(i))
                val dst = ByteRange(dstOffset, dstSize(i))
                val srcVal = base.getStackSingletonOrNull(src) ?: ScalarValue(sbfTypeFac.mkTop())
                base.updateStack(dst, transformValue(srcVal,i), isWeak = false)
            }
        }
    }


    private fun castNumToString(reg: Value.Reg) {
        val oldType = getRegister(reg).type() as? SbfType.NumType ?: return
        val newType = oldType.castToPtr(sbfTypeFac, globalState.globals)
        if (newType is SbfType.PointerType.Global && newType.global?.strValue != null) {
            setRegister(reg, ScalarValue(newType))
        }
    }

    private fun analyzeCall(locInst: LocatedSbfInstruction) {
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
                SolanaFunction.SOL_GET_STACK_HEIGHT ->
                    setRegister(Value.Reg(SbfRegister.R0_RETURN_VALUE), ScalarValue(sbfTypeFac.anyNum()))
                SolanaFunction.SOL_GET_CLOCK_SYSVAR, SolanaFunction.SOL_GET_RENT_SYSVAR ->
                    summarizeCall(locInst)
                SolanaFunction.SOL_SET_CLOCK_SYSVAR ->
                    forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
                SolanaFunction.SOL_MEMCPY -> analyzeMemcpy(locInst)
                SolanaFunction.SOL_MEMCPY_ZEXT -> analyzeMemcpyZext(locInst)
                SolanaFunction.SOL_MEMCPY_TRUNC -> analyzeMemcpyTrunc(locInst)
                SolanaFunction.SOL_MEMMOVE,
                SolanaFunction.SOL_MEMSET -> analyzeMemsetOrMemmove(locInst)
                else -> forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }
        } else {
            if (stmt.isAllocFn() && globalState.memSummaries.getSummary(stmt.name) == null) {
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
                                    summarizeCall(locInst)
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
                            summarizeCall(locInst)
                        }
                        is CVTFunction.Calltrace -> {
                            cvtFunction.value.strings.forEach {
                                castNumToString(it.string)
                            }
                        }
                    }
                } else {
                    /** SBF to SBF call **/
                    summarizeCall(locInst)
                }
            }
        }
    }

    private fun summarizeCall(locInst: LocatedSbfInstruction) {

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
        globalState.memSummaries.visitSummary(locInst, vis)
    }

    /** Update both [left] and [right] **/
    private fun analyzeAssumeNumNum(
        op: CondOp,
        left: Value.Reg,
        leftType: SbfType.NumType<TNum, TOffset>,
        right: Value,
        rightType: SbfType.NumType<TNum, TOffset>
    ) {

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


    /**
     * Biased meet operation that prefers `this` over [other] when both are not top/bottom values
     * This is a sound meet operation, but it is not the greatest lower bound
     **/
    private fun SbfType<TNum, TOffset>.leftBiasedMeet(
        other: SbfType<TNum, TOffset>
    ): SbfType<TNum, TOffset> {
        return when {
            isBottom() || other.isBottom() -> SbfType.bottom()
            isTop() -> other
            other.isTop() -> this
            else -> this // biased towards `this`
        }
    }

    /** Update [left] **/
    private fun analyzeAssumeTopNonTop(
        op: CondOp,
        left: Value.Reg,
        leftType: SbfType<TNum, TOffset>,
        rightType: SbfType<TNum, TOffset>
    ) {
        check(leftType is SbfType.Top || rightType is SbfType.Top) {
            "failed preconditions on analyzeAssumeTopNonTop"
        }
        check(!(leftType !is SbfType.Top && rightType !is SbfType.Top)) {
            "failed preconditions on analyzeAssumeTopNonTop"
        }

        if (op == CondOp.EQ) {
            setRegister(left, ScalarValue(leftType.leftBiasedMeet(rightType)))
        }
    }

    /** Update both [left] and [right] **/
    private fun analyzeAssumePtrPtr(
        op: CondOp,
        left: Value.Reg,
        leftType: SbfType.PointerType<TNum, TOffset>,
        right: Value.Reg,
        rightType: SbfType.PointerType<TNum, TOffset>
    ) {
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
        check(!isBottom()) {"analyzeAssume cannot be called on bottom"}

        val op = cond.op
        val left = cond.left
        val right = cond.right
        val (leftType, rightType) = refineTypes(
            op,
            BinaryTypes(
                getRegister(left).type(),
                when(right) {
                    is Value.Imm -> sbfTypeFac.toNum(right.v)
                    is Value.Reg -> getRegister(right).type()
                }
            )
        )
        updateType(left, leftType)
        when (right) {
            is Value.Imm -> {
                when {
                    leftType is SbfType.NumType -> {
                        analyzeAssumeNumNum(op,
                            left,
                            leftType,
                            right,
                            rightType as SbfType.NumType)
                    }
                    leftType is SbfType.PointerType -> {
                        // do nothing: we can do better here if op is EQ
                    }
                    leftType.isTop() -> {
                        /**
                         * We assume that the left operand is a number,
                         * although we don't really know at this point.
                         **/
                        analyzeAssumeTopNonTop(op, left, leftType, rightType)
                    }
                    else -> {
                        // do nothing
                    }
                }
            }
            is Value.Reg -> {
                updateType(right, rightType)
                when {
                    leftType.isTop() && rightType.isTop() -> {
                        // do nothing
                    }
                    leftType.isTop() || rightType.isTop() -> {
                        analyzeAssumeTopNonTop(op, left, leftType, rightType)
                        analyzeAssumeTopNonTop(op, right, leftType, rightType)
                    }
                    else -> {
                        when {
                            leftType is SbfType.NumType && rightType is SbfType.NumType -> {
                                analyzeAssumeNumNum(op, left, leftType, right, rightType)
                            }
                            leftType is SbfType.PointerType && rightType is SbfType.NumType -> {
                                // do nothing: note that comparing pointers and numbers is perfectly fine
                            }
                            leftType is SbfType.NumType && rightType is SbfType.PointerType -> {
                                // do nothing: note that comparing pointers and numbers is perfectly fine
                            }
                            leftType is SbfType.PointerType && rightType is SbfType.PointerType -> {
                                analyzeAssumePtrPtr(op, left, leftType, right, rightType)
                            }
                            else -> {
                                // do nothing
                            }
                        }
                    }
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

    private fun analyzeSelect(stmt: SbfInstruction.Select) {
        check(!isBottom()) {"analyzeSelect cannot be called on bottom"}

        // apply `assume(cond)` to `this`
        val absValCondIsTrue = deepCopy().apply { analyzeAssume(stmt.cond) }

        if (absValCondIsTrue.isBottom()) {
            setRegister(stmt.dst, getValue(stmt.falseVal))
        } else {
            // apply `assume(not(cond))` to `this`
            val absValCondIsFalse = deepCopy().apply { analyzeAssume(stmt.cond.negate()) }

            if (absValCondIsFalse.isBottom()) {
                setRegister(stmt.dst, getValue(stmt.trueVal))
            } else {
                val cond = stmt.cond
                val leftOperand = cond.left
                val rightOperand = cond.right
                val condOp = cond.op

                // 1. refine types of the registers appearing on the select's condition
                refineTypes(
                    condOp,
                    BinaryTypes(
                        getRegister(leftOperand).type(),
                        when(rightOperand) {
                            is Value.Imm -> sbfTypeFac.toNum(rightOperand.v)
                            is Value.Reg -> getRegister(rightOperand).type()
                        }
                    )
                ).let { (leftType, rightType) ->
                    updateType(leftOperand, leftType)
                    (rightOperand as? Value.Reg)?.let { updateType(rightOperand, rightType) }
                }

                // 2. Apply the rules
                // - t=top and f=num(!=0) -> lhs=num
                // - t=num(!=0) and f=top -> lhs=num
                // - else                 -> lhs=join(f, t)

                val falseScalar = getValue(stmt.falseVal)
                val trueScalar = getValue(stmt.trueVal)
                val falseType = falseScalar.type()
                val trueType = trueScalar.type()

                val rhsAbsVal = when {
                    falseType is SbfType.Top && trueType.mustBeNumber() ->
                        ScalarValue(sbfTypeFac.anyNum())
                    falseType.mustBeNumber() && trueType is SbfType.Top ->
                        ScalarValue(sbfTypeFac.anyNum())
                    else ->
                        falseScalar.join(trueScalar)
                }
                setRegister(stmt.dst, rhsAbsVal)
            }
        }
    }

    private fun forgetOrNum(v: Value.Reg, isNum: Boolean) {
        if (isNum) {
            // This should be always a "weak" read because we can read twice from the same memory location
            // but one loaded value can be considered as non-pointer because it's never de-referenced
            // but the other one can be de-referenced. Since the scalar domain is non-relation all reads
            // are weak anyway.
            setRegister(v, ScalarValue(sbfTypeFac.anyNum()))
        } else {
            forget(v)
        }
    }

    override fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg): ScalarValue<TNum, TOffset> {
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
            type.castToPtr(sbfTypeFac, globalState.globals)?.let {
                return ScalarValue(it)
            }
        }
        return scalarVal
    }

    /**
     *  Return the abstract value of the base register if it will be killed by the lhs of a load instruction.
     *  Otherwise, it returns null. This is used by the Memory Domain.
     **/
    private fun analyzeMem(locInst: LocatedSbfInstruction) {
        check(!isBottom()) {"analyzeMem cannot be called on bottom"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Mem) {"analyzeMem expect a memory instruction instead of $stmt"}

        val globals = globalState.globals

        val baseReg = stmt.access.base
        val offset = stmt.access.offset
        val width = stmt.access.width
        val value = stmt.value
        val isLoad = stmt.isLoad

        val baseScalarVal = getAsScalarValueWithNumToPtrCast(baseReg)
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
                                throw UnknownStackPointerError(
                                    DevErrorInfo(
                                        locInst,
                                        PtrExprErrReg(baseReg),
                                        "store: $stmt to unknown stack location"
                                    )
                                )
                            }
                        } else {
                            val stackOffsets = stackTOffsets.toLongList()
                            if (isLoad) {
                                setRegister(value as Value.Reg,
                                    stackOffsets.fold(ScalarValue(sbfTypeFac.mkBottom())) { acc, stackOffset ->
                                        val loadedAbsVal = base.getStackSingletonOrNull(ByteRange(stackOffset, width.toByte()))
                                        when {
                                            loadedAbsVal != null -> acc.join(loadedAbsVal.zext(width.toLong()))
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
                                        // if slice is already in `stack` then its value is not removed and
                                        // `stack.put` will do a weak update with `value`.
                                        // Any other overlapping entry will be removed by `killOffsets`
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

                            baseType.global?.let { gv ->
                                val derefAddr = gv.address + offset.toLong()
                                if (globals.elf.isReadOnlyGlobalVariable(derefAddr)) {
                                    globals.elf.getAsConstantNum(derefAddr, width.toLong())?.let {
                                        setRegister(value, ScalarValue(sbfTypeFac.toNum(it)))
                                    }
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

    fun analyze(locInst: LocatedSbfInstruction) {
        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> analyzeUn(s)
                is SbfInstruction.Bin -> analyzeBin(locInst)
                is SbfInstruction.Call -> analyzeCall(locInst)
                is SbfInstruction.CallReg -> {
                    if (!SolanaConfig.SkipCallRegInst.get()) {
                        throw UnsupportedCallX(locInst)
                    }
                }
                is SbfInstruction.Select -> analyzeSelect(s)
                is SbfInstruction.Havoc -> analyzeHavoc(s)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume -> analyzeAssume(s)
                is SbfInstruction.Assert -> analyzeAssert(s)
                is SbfInstruction.Mem -> analyzeMem(locInst)
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
                is SbfInstruction.Debug -> {}
            }
        }
    }

    override fun analyze(
        b: SbfBasicBlock,
        listener: InstructionListener<ScalarDomain<TNum, TOffset>>
    ): ScalarDomain<TNum, TOffset> =
        analyzeBlockMut(
            domainName = "ScalarDomain",
            b,
            inState = this,
            transferFunction = { mutState, locInst ->
                mutState.analyze(locInst)
            },
            listener
        )

    override fun toString() = base.toString()
}


typealias MutableTransferFunction<T> = (mutState: T, locInst: LocatedSbfInstruction) -> Unit
typealias ImmutableTransferFunction<T> = (state: T, locInst: LocatedSbfInstruction) -> T

/**
 * Analyzes a basic block by applying a transfer function to each instruction.
 * This version is for MUTABLE scalar domains where transfer functions modify state in-place.
 *
 * @param domainName The name of the abstract domain for debugging purposes.
 * @param b The basic block to analyze
 * @param inState The initial abstract state at block entry
 * @param transferFunction Function that updates the abstract state for each instruction
 * @param listener Callback for instruction analysis events
 * @return The final abstract state after analyzing all instructions
 */
fun<ScalarDomain: MutableAbstractDomain<ScalarDomain>> analyzeBlockMut(
    domainName: String,
    b: SbfBasicBlock,
    inState: ScalarDomain,
    transferFunction: MutableTransferFunction<ScalarDomain>,
    listener: InstructionListener<ScalarDomain>
): ScalarDomain {

    dbg { "=== $domainName analyzing ${b.getLabel()} ===\nAt entry: $inState\n" }

    if (listener is DefaultInstructionListener) {
        // Fast path: shortcut when bottom is detected and avoid deep copies
        if (inState.isBottom()) {
            return inState
        }

        val outState = inState.deepCopy()
        for (locInst in b.getLocatedInstructions()) {
            dbg { "${locInst.inst}\n" }
            transferFunction(outState, locInst)
            dbg { "$outState\n" }
            if (outState.isBottom()) {
                break
            }
        }
        return outState
    } else {
        var before = inState
        for (locInst in b.getLocatedInstructions()) {
            val after = before.deepCopy()
            listener.instructionEventBefore(locInst, before)
            dbg { "${locInst.inst}\n" }
            transferFunction(after, locInst)
            dbg { "$after\n" }
            listener.instructionEventAfter(locInst, after)
            // Calling to this listener requires to make an extra copy
            // It's used by class AnnotateWithTypesListener defined in AnnotateCFG.kt
            listener.instructionEvent(locInst, before, after)
            before = after
        }
        return before
    }
}

/**
 * Analyzes a basic block by applying a transfer function to each instruction.
 * This version is for IMMUTABLE scalar domains where transfer functions return new states.
 *
 * @param domainName The name of the abstract domain for debugging purposes
 * @param b The basic block to analyze
 * @param state The initial abstract state at block entry
 * @param transferFunction Function that returns a new abstract state for each instruction
 * @param listener Callback for instruction analysis events
 * @return The final abstract state after analyzing all instructions
 */
fun<ScalarDomain: AbstractDomain<ScalarDomain>> analyzeBlock(
    domainName: String,
    b: SbfBasicBlock,
    state: ScalarDomain,
    transferFunction: ImmutableTransferFunction<ScalarDomain>,
    listener: InstructionListener<ScalarDomain>
): ScalarDomain {

    dbg { "=== $domainName analyzing ${b.getLabel()} ===\nAt entry: $state\n" }

    // Fast path: shortcut when bottom is detected
    if (listener is DefaultInstructionListener) {
        if (state.isBottom()) {
            return state
        }

        var outState = state
        for (locInst in b.getLocatedInstructions()) {
            dbg { "${locInst.inst}\n" }
            outState = transferFunction(outState, locInst)
            dbg { "$outState\n" }
            if (outState.isBottom()) {
                break
            }
        }
        return outState
    }

    // Full tracking path: even if bottom is detected we call the listener
    var currentState = state
    for (locInst in b.getLocatedInstructions()) {
        dbg { "${locInst.inst}\n" }
        val inState = currentState
        listener.instructionEventBefore(locInst, inState)
        val outState = transferFunction(inState, locInst)
        dbg { "$outState\n" }
        listener.instructionEventAfter(locInst, outState)
        listener.instructionEvent(locInst, inState, outState)
        currentState = outState
    }
    return currentState
}
