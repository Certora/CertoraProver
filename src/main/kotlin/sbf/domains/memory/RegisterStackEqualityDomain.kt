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


import com.certora.collect.*
import sbf.disassembler.*
import sbf.cfg.*
import datastructures.stdcollections.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import sbf.callgraph.SolanaFunction

fun StackLocation.toInterval() = FiniteInterval.mkInterval(offset, width.toLong())

/**
 * Represents a load instruction from a stack location.
 *
 * @param loc the stack location being loaded from
 * @param locInst the load instruction
 */
data class StackLoad(val loc: StackLocation, val locInst: LocatedSbfInstruction) {
    init {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem && inst.isLoad)
    }
}


/**
 * Tracks equality relationships between register contents and stack memory locations.
 *
 * This domain maintains facts of the form `r1 == stack(offset, width)`, indicating that
 * register `r1` contains the value loaded of `width` bytes from stack `offset`.
 *
 * This abstract domain is useful, for instance, for removing redundant load instructions.
 * However, it is main use is to improve the precision of NPDomain (the abstract domain used for
 * backward slicing).
 *
 * ## Representation
 * - Each register maps to either:
 *   - A `StackLocation`: the register equals the contents at this stack location
 *   - `null` (top): no known equality
 *
 * The implementation can track equalities from narrow loads if the loaded value is known
 * to be a non-negative number.
 *
 * ## Limitations
 * - Cannot represent (bottom/unreachable state)
 * - Tracks equality with stack *contents*, not stack addresses
 *
 * @property registers Mapping from SBF registers to their equivalent stack slots
 * @property scratchRegisters Mapping from scratch registers to their equivalent stack slots
 * @property globalState Shared global analysis state
**/
class RegisterStackEqualityDomain(
    // Implementation notes: I decided intentionally not to use a union-find to keep track equalities.
    // Instead, we just map a register to StackLocation: two registers are equal if they are mapped to
    // the same StackLocation. For stores, we need to invalidate any mapping reg -> StackLocation if
    // StackLocation might have modified by the store. Since the number of registers is very small
    // we don't keep an inverse mapping StackLocation -> reg
    private val registers: TreapMap<Value.Reg, StackLoad> = treapMapOf(),
    private val scratchRegisters: PersistentList<StackLoad?>, // null represents "top"
    val globalState: GlobalState
) {

    constructor(globalState: GlobalState):
        this(
            treapMapOf(),
            persistentListOf(),
            globalState
        )

    companion object {
        fun makeTop(globalState: GlobalState) = RegisterStackEqualityDomain(globalState)

        private fun lessOrEqual(left: StackLoad?, right: StackLoad?) =
            right == null || left == right

        private fun join(left: StackLoad?, right: StackLoad?): StackLoad? =
            if (left == right) { left } else { null }
    }

    /**
     * Return a top abstract state but keeping the same scratch registers than `this`.
     * This is needed to make sure that calls to save/restore scratch registers are balanced.
     */
    private fun setToTop(): RegisterStackEqualityDomain =
        RegisterStackEqualityDomain(treapMapOf(), scratchRegisters, globalState)

    fun isTop() = registers.isEmpty()

    private fun <A> PersistentList<A>.zipToPersistent(
        other: PersistentList<A>,
        transform: (A, A) -> A
    ): PersistentList<A> {
        check(this.size == other.size)
        val size = this.size
        var result = this
        for (i in 0 until size) {
            result = result.set(i, transform(this[i], other[i]))
        }
        return result
    }

    private fun <A> PersistentList<A>.mapToPersistent(
        transform: (A) -> A
    ): PersistentList<A> {
        val size = this.size
        var result = this
        for (i in 0 until size) {
            result = result.set(i, transform(this[i]))
        }
        return result
    }


    fun join(other: RegisterStackEqualityDomain): RegisterStackEqualityDomain {
        return when {
            isTop() ->
                this
            other.isTop() ->
                other
            else -> {
                RegisterStackEqualityDomain(
                    registers.merge(other.registers) { _, x, y -> join(x, y) },
                    scratchRegisters.zipToPersistent(other.scratchRegisters) { x, y -> join(x, y) },
                    globalState
                )
            }
        }
    }

    fun widen(other: RegisterStackEqualityDomain) = join(other)

    fun lessOrEqual(other: RegisterStackEqualityDomain): Boolean {
        when {
            other.isTop() -> return true
            isTop() -> return false
            else -> {
                check(scratchRegisters.size == other.scratchRegisters.size)
                registers.zip(other.registers).forEach {
                    val left = it.value.first
                    val right = it.value.second
                    if (!lessOrEqual(left, right)) {
                        return false
                    }
                }
                scratchRegisters.forEachIndexed { i, left ->
                    val right = other.scratchRegisters[i]
                    if (!lessOrEqual(left, right)) {
                        return false
                    }
                }
                return true
            }
        }
    }

    override fun toString(): String {
        return if (isTop()) {
            "top"
        } else {
            // We do not print intentionally scratch registers
            "$registers"
        }
    }

    fun getRegister(reg: Value.Reg): StackLoad? {
        return registers[reg]
    }

    private fun setRegister(reg: Value.Reg, stackSlot: StackLoad?) =
        RegisterStackEqualityDomain(
            if (stackSlot == null) {
                registers.remove(reg)
            } else {
                registers.put(reg, stackSlot)
            },
            scratchRegisters,
            globalState
        )

    fun forget(regs: Iterable<Value.Reg>) =
        regs.fold(this) {
                acc, reg -> acc.setRegister(reg, null)
        }

    private fun analyzeBin(locInst: LocatedSbfInstruction): RegisterStackEqualityDomain {
        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        if (isTop()) {
            return this
        }

        val lhs = inst.dst
        val rhs = inst.v

        return when (inst.op) {
            BinOp.MOV -> {
                when(rhs) {
                    is Value.Reg -> setRegister(lhs, getRegister(rhs))
                    is Value.Imm -> forget(listOf(lhs))
                }
            }
            BinOp.ADD, BinOp.SUB,
            BinOp.MUL, BinOp.DIV, BinOp.MOD,
            BinOp.OR, BinOp.AND, BinOp.XOR,
            BinOp.LSH, BinOp.RSH, BinOp.ARSH -> {
                forget(listOf(lhs))
            }
        }
    }

    private fun pushScratchReg(v: StackLoad?) =
        RegisterStackEqualityDomain(
            registers,
            scratchRegisters.add(v),
            globalState
        )

    private fun popScratchReg(): RegisterStackEqualityDomain {
        if (scratchRegisters.isEmpty()) {
            throw ScalarDomainError("stack of scratch registers cannot be empty")
        }
        val lastIdx = scratchRegisters.lastIndex
        return RegisterStackEqualityDomain(
            registers,
            scratchRegisters.removeAt(lastIdx),
            globalState
        )
    }

    private fun saveScratchRegisters(): RegisterStackEqualityDomain {
        // we always push scratch registers, even if the abstract state is top
        return pushScratchReg(registers[Value.Reg(SbfRegister.R6)])
            .pushScratchReg(registers[Value.Reg(SbfRegister.R7)])
            .pushScratchReg(registers[Value.Reg(SbfRegister.R8)])
            .pushScratchReg(registers[Value.Reg(SbfRegister.R9)])
    }

    private fun restoreScratchRegisters(): RegisterStackEqualityDomain {
        if (scratchRegisters.size < 4) {
            throw ScalarDomainError(
                "The number of calls to save/restore scratch registers must match: $scratchRegisters"
            )
        } else {
            // we always pop scratch registers, even if the abstract state is top
            val lastIdx = scratchRegisters.lastIndex

            val tmp9 = scratchRegisters[lastIdx]
            val tmp8 = scratchRegisters[lastIdx-1]
            val tmp7 = scratchRegisters[lastIdx-2]
            val tmp6 = scratchRegisters[lastIdx-3]

            return popScratchReg().
                   popScratchReg().
                   popScratchReg().
                   popScratchReg().
                   setRegister(Value.Reg(SbfRegister.R9), tmp9).
                   setRegister(Value.Reg(SbfRegister.R8), tmp8).
                   setRegister(Value.Reg(SbfRegister.R7), tmp7).
                   setRegister(Value.Reg(SbfRegister.R6), tmp6)
        }
    }

    private fun<TNum, TOffset, TScalarDomain> summarizeCall(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        class ScalarSummaryVisitor(var state: RegisterStackEqualityDomain): SummaryVisitor {
            val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
            override fun noSummaryFound(
                @Suppress("UNUSED_PARAMETER") locInst: LocatedSbfInstruction
            ) {
                state = state.forget(listOf(r0))
            }

            override fun processReturnArgument(
                @Suppress("UNUSED_PARAMETER")  locInst: LocatedSbfInstruction,
                @Suppress("UNUSED_PARAMETER") type: MemSummaryArgumentType
            ) {
                state = state.forget(listOf(r0))
            }

            override fun processArgument(
                locInst: LocatedSbfInstruction,
                reg: SbfRegister,
                offset: Long,
                width: Byte,
                @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                @Suppress("UNUSED_PARAMETER") type: MemSummaryArgumentType
            ) {
                state = state.havoc(Value.Reg(reg), offset, width.toLong(), scalars)
            }
        }

        val vis = ScalarSummaryVisitor(this)
        globalState.memSummaries.visitSummary(locInst, vis)
        return vis.state
    }

    private fun<TNum, TOffset, TScalarDomain> analyzeMemIntrinsics(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)


        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        val len: Long = when (SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMSET,
            SolanaFunction.SOL_MEMCPY_TRUNC -> {
                // If we don't know the length then we give up and forget any information
                (scalars.getAsScalarValue(r3).type() as? SbfType.NumType)?.value?.toLongOrNull()
                    ?: return setToTop()
            }
            SolanaFunction.SOL_MEMCPY_ZEXT -> 8L
            else -> error(false) // unreachable
        }

        return if (locInst.inst.writeRegister.contains(r0)) {
            forget(listOf(r0))
        } else {
            this
        }.havoc(r1, 0, len, scalars)
    }

    private fun<TNum, TOffset, TScalarDomain> analyzeCall(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        if (inst.isSaveScratchRegisters()) {
            return saveScratchRegisters()
        }

        if (inst.isRestoreScratchRegisters()) {
            return restoreScratchRegisters()
        }

        return when (SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMCPY_ZEXT,
            SolanaFunction.SOL_MEMCPY_TRUNC,
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMSET ->
                analyzeMemIntrinsics(locInst, scalars)
            else ->
                summarizeCall(locInst, scalars)
        }
    }

    private fun mayOverlap(
        stackSlot: StackLocation,
        offsets: Iterable<Long>,
        size: Long
    ): Boolean {
        val x = stackSlot.toInterval()
        return offsets.any { offset ->
            val y = FiniteInterval.mkInterval(offset, size)
            x.overlap(y)
        }
    }

    /** Havoc all stack bytes in range `[base+offset ...., base+offset+size)` **/
    private fun<TNum, TOffset, TScalarDomain> havoc(
        base: Value.Reg,
        offset: Long,
        size: Long,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        val baseTy = scalars.getAsScalarValue(base).type()

        // if we don't know the type of the base then we lose all information since we don't know which stack
        // offsets have been modified
        if (baseTy.isTop()) {
            return setToTop()
        }

        // if it is not a stack pointer then we do nothing
        val stackPtrTy = (baseTy as? SbfType.PointerType.Stack) ?: return this

        val symOffset = stackPtrTy.offset.add(offset)

        // We don't know which stack offsets are being modified so we all information
        if (symOffset.isTop()) {
            return setToTop()
        }

        val offsets = symOffset.toLongList()
        check(offsets.isNotEmpty())

        return RegisterStackEqualityDomain(
            registers.removeAll {
                val stackLoad = it.value
                mayOverlap(stackLoad.loc, offsets, size)
            },
            scratchRegisters.mapToPersistent { stackLoad ->
                if (stackLoad == null || mayOverlap(stackLoad.loc, offsets, size)) {
                    null
                } else {
                    stackLoad
                }
            },
            globalState
        )
    }

    private fun<TNum, TOffset, TScalarDomain> analyzeStore(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)
        check(!inst.isLoad)

        val base = inst.access.base
        val width = inst.access.width
        val offset = inst.access.offset.toLong()

        return havoc(base, offset, width.toLong(), scalars)
    }

    private fun<TNum, TOffset, TScalarDomain> analyzeLoad(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              TScalarDomain: ScalarValueProvider<TNum, TOffset> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)
        check(inst.isLoad)

        val lhs = inst.value as Value.Reg
        val base = inst.access.base
        val width = inst.access.width.toByte()
        val offset = inst.access.offset.toLong()

        // Only track equalities for loads from stack pointers
        val baseTy = (scalars.getAsScalarValue(base).type() as? SbfType.PointerType.Stack)
            ?: return forget(listOf(lhs))

        // Compute the absolute stack offset being loaded from
        val stackOffset = baseTy.offset.add(offset).toLongOrNull()
            ?: return forget(listOf(lhs))

        // Handle narrow loads (width < 8 bytes)
        // SBF implicitly zero-extends narrow loads to 64 bits, which changes the value
        // if the loaded data has the sign bit set. However, if we can prove the loaded
        // value is non-negative, zero-extension is a non-op, and we can track the equality.
        if (width != 8.toByte()) {
            val loadedValTy = scalars.getStackContent(stackOffset, width).type() as? SbfType.NumType
                ?: return forget(listOf(lhs))

            if (loadedValTy.value.isTop() ||
                loadedValTy.value.toLongList().any { n -> n < 0 }
            ) {
                // Value might be negative or is unknown
                // Zero-extension would change the bit pattern, so no equality holds
                return forget(listOf(lhs))
            }
            // Value is provably non-negative: zero-extension does not change it.
            // Fall through to establish the equality
        }

        val stackSlot = StackLocation(stackOffset, width)
        return setRegister(lhs, StackLoad(stackSlot, locInst))
    }

    fun<TNum, TOffset, TScalarDomain> analyze(
        locInst: LocatedSbfInstruction,
        scalars: TScalarDomain
    ): RegisterStackEqualityDomain
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset>,
              TScalarDomain: ScalarValueProvider<TNum, TOffset>  {
        return when (val s = locInst.inst) {
            is SbfInstruction.Un -> forget(listOf(s.dst))
            is SbfInstruction.Bin -> analyzeBin(locInst)
            is SbfInstruction.Call -> analyzeCall(locInst, scalars)
            is SbfInstruction.CallReg -> { this }
            is SbfInstruction.Select -> forget(listOf(s.dst))
            is SbfInstruction.Havoc -> forget(listOf(s.dst))
            is SbfInstruction.Jump.ConditionalJump -> { this }
            is SbfInstruction.Assume -> { this }
            is SbfInstruction.Assert -> { this }
            is SbfInstruction.Mem -> {
                if (s.isLoad) {
                    analyzeLoad(locInst, scalars)
                } else {
                    analyzeStore(locInst, scalars)
                }
            }
            is SbfInstruction.Jump.UnconditionalJump -> { this }
            is SbfInstruction.Exit -> { this }
            is SbfInstruction.Debug -> { this }
        }
    }

    /**
     *  @return set of all registers that are equal to [reg].
     **/
    fun getEqualities(reg: Value.Reg): Set<Value.Reg> {
        val stackSlot = registers[reg] ?: return setOf()
        return registers.remove(reg).filter { stackSlot == it.value}.keys
    }
}

class ScalarRegisterStackEqualityDomainFactory<TNum, TOffset>
  : IScalarDomainFactory<TNum, TOffset, ScalarRegisterStackEqualityDomain<TNum, TOffset>>
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset> {
    override fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState) =
        ScalarRegisterStackEqualityDomain.makeTop(fac, globalState)
    override fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>, globalState: GlobalState) =
        ScalarRegisterStackEqualityDomain.makeBottom(fac, globalState)
    override fun init(
        fac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        addPreconditions: Boolean
    ) = ScalarRegisterStackEqualityDomain(fac, globalState, addPreconditions)
}

/**
 * Simple Reduced Product between a scalar domain and [RegisterStackEqualityDomain]
 **/
class ScalarRegisterStackEqualityDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> private constructor(
    private val scalars: RegStackEqScalarDom<TNum, TOffset>,
    private var equalities: RegisterStackEqualityDomain,
    private val globalState: GlobalState
) : AbstractDomain<ScalarRegisterStackEqualityDomain<TNum, TOffset>>,
    ScalarValueProvider<TNum, TOffset>,
    StackLocationQuery {

    constructor(
        sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
        globalState: GlobalState,
        initPreconditions: Boolean = false):
        this(
            RegStackEqScalarDom(sbfTypeFac, globalState, initPreconditions),
            RegisterStackEqualityDomain.makeTop(globalState),
            globalState
        )

    companion object {
        fun<TNum, TOffset> makeTop(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState
        ): ScalarRegisterStackEqualityDomain<TNum, TOffset>
        where TNum: INumValue<TNum>,
              TOffset: IOffset<TOffset> {
            return ScalarRegisterStackEqualityDomain(
                RegStackEqScalarDom.makeTop(sbfTypeFac, globalState),
                RegisterStackEqualityDomain.makeTop(globalState),
                globalState
            )
        }

        fun<TNum, TOffset> makeBottom(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            globalState: GlobalState
        ): ScalarRegisterStackEqualityDomain<TNum, TOffset>
            where TNum: INumValue<TNum>,
                  TOffset: IOffset<TOffset> {
            return ScalarRegisterStackEqualityDomain(
                RegStackEqScalarDom.makeBottom(sbfTypeFac, globalState),
                RegisterStackEqualityDomain.makeTop(globalState),
                globalState
            )
        }
    }

    override fun isBottom() = scalars.isBottom()

    override fun isTop() = scalars.isTop() && equalities.isTop()

    override fun join(
        other: ScalarRegisterStackEqualityDomain<TNum, TOffset>,
        left: Label?,
        right: Label?
    ): ScalarRegisterStackEqualityDomain<TNum, TOffset> {
        return if (isBottom()) {
            other
        } else if (other.isBottom()) {
            this
        } else {
            ScalarRegisterStackEqualityDomain(
                scalars.join(other.scalars, left, right),
                equalities.join(other.equalities),
                globalState
            )
        }
    }

    override fun widen(
        other: ScalarRegisterStackEqualityDomain<TNum, TOffset>,
        b: Label?
    ): ScalarRegisterStackEqualityDomain<TNum, TOffset> {
        return if (isBottom()) {
            other
        } else if (other.isBottom()) {
            this
        } else {
            ScalarRegisterStackEqualityDomain(
                scalars.widen(other.scalars, b),
                equalities.widen(other.equalities),
                globalState
            )
        }
    }
    override fun lessOrEqual(
        other: ScalarRegisterStackEqualityDomain<TNum, TOffset>,
        left: Label?,
        right: Label?
    ): Boolean {
        return if (isBottom()) {
            true
        } else if (other.isBottom()) {
            false
        } else {
            scalars.lessOrEqual(other.scalars) && equalities.lessOrEqual(other.equalities)
        }
    }

    override fun pseudoCanonicalize(
        other: ScalarRegisterStackEqualityDomain<TNum, TOffset>
    ): ScalarRegisterStackEqualityDomain<TNum, TOffset> {
        return if (isBottom()) {
            this
        } else {
            ScalarRegisterStackEqualityDomain(
                scalars.pseudoCanonicalize(other.scalars),
                equalities,
                globalState
            )
        }
    }


    override fun forget(regs: Iterable<Value.Reg>): ScalarRegisterStackEqualityDomain<TNum, TOffset> {
        return if (isBottom()) {
            this
        } else {
            ScalarRegisterStackEqualityDomain(
                // this might be expensive since scalars is actually a mutable abstract domain so there is a deep copy
                scalars.forget(regs),
                equalities.forget(regs),
                globalState
            )
        }
    }

    private fun getRegFromUnaryConditionOrNull(cond: Condition): Value.Reg? =
        cond.left.takeIf { cond.right is Value.Imm }

    /**
     * Simple reduction from the equality domain to the scalar domain.
     *
     * This reduction propagates equality constraints between registers and stack memory
     * in two scenarios:
     *
     * 1. Constant propagation through equality:
     *    If `assume(r == k)` and `r -> *(stack(o,w))`, then `*(stack(o,w)) == k`
     *
     * 2. Register equality after load:
     *    If `load(r1, stack(o,w))` and `r2 -> *(stack(o,w))`, then `r1 == r2`
     *
     * where stack(o,w) denotes stack offsets [o, o+w)
     */
    private fun refineWithEqualities(
        scalars: RegStackEqScalarDom<TNum, TOffset>,
        locInst: LocatedSbfInstruction,
        equalities: RegisterStackEqualityDomain
    ) {

        if (scalars.isBottom()) {
            return
        }

        val inst = locInst.inst
        when {
            inst is SbfInstruction.Mem && inst.isLoad -> {
                val lhs = inst.value as Value.Reg
                val eqSet = equalities.getEqualities(lhs)
                if (eqSet.isNotEmpty()) {
                    // We compute a lower bound (not necessarily the greatest) using the ordering
                    var lbVal = scalars.getAsScalarValue(lhs)
                    eqSet.forEach { reg ->
                        val regVal = scalars.getAsScalarValue(reg)
                        if (!lbVal.lessOrEqual(regVal)) { // scalarVal is strictly smaller than lb
                            lbVal = regVal
                        }
                    }
                    if (!lbVal.isTop()) {
                        scalars.setScalarValue(lhs, lbVal)
                    }
                }
            }
            inst is SbfInstruction.Assume && inst.cond.op == CondOp.EQ-> {
                val reg = getRegFromUnaryConditionOrNull(inst.cond) ?: return
                val stackSlot = equalities.getRegister(reg)?.loc ?: return
                val scalarVal = scalars.getAsScalarValue(reg)
                if (!scalarVal.isTop()) {
                    scalars.setStackContent(stackSlot.offset, stackSlot.width, scalarVal)
                }
            }
            else -> {}
        }
    }

    fun analyze(locInst: LocatedSbfInstruction): ScalarRegisterStackEqualityDomain<TNum, TOffset> {
        // this might be expensive if ultimately we don't use a listener.
        val outScalars = scalars.deepCopy()
        outScalars.analyze(locInst)
        val outEqualities = if (!outScalars.isBottom()) {
            equalities.analyze(locInst, scalars)
        } else {
            equalities
        }
        refineWithEqualities(outScalars, locInst, outEqualities)
        return ScalarRegisterStackEqualityDomain(
            outScalars,
            outEqualities,
            globalState
        )
    }

    override fun analyze(
        b: SbfBasicBlock,
        listener: InstructionListener<ScalarRegisterStackEqualityDomain<TNum, TOffset>>
    ): ScalarRegisterStackEqualityDomain<TNum, TOffset> =
        analyzeBlock(
            domainName = "ScalarDomain x RegisterStackEqualityDomain",
            b,
            state = this,
            transferFunction = { state, locInst -> state.analyze(locInst) },
            listener
        )

    override fun deepCopy() =
        ScalarRegisterStackEqualityDomain(
            scalars.deepCopy(),
            equalities,
            globalState
        )

    override fun getStackSource(reg: Value.Reg): StackLocation? = equalities.getRegister(reg)?.loc

    fun getRegisterStackEqualityDomain() = equalities

    override fun toString() = "($scalars,equalities=$equalities)"

    override fun getAsScalarValue(value: Value): ScalarValue<TNum, TOffset> {
        return scalars.getAsScalarValue(value)
    }

    override fun getStackContent(offset: Long, width: Byte): ScalarValue<TNum, TOffset> {
       return scalars.getStackContent(offset, width)
    }

    override fun getTypeFac() = scalars.getTypeFac()

}
