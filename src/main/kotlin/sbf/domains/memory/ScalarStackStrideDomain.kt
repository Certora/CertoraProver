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

import sbf.SolanaConfig
import sbf.disassembler.*
import sbf.cfg.*
import kotlin.math.absoluteValue
import sbf.callgraph.CVTCore
import sbf.callgraph.CVTFunction
import datastructures.stdcollections.*
import log.*

private val logger = Logger(LoggerTypes.SBF_SCALAR_ANALYSIS)
private fun dbg(msg: () -> Any) { logger.info(msg) }

/**
 * Represent predicate of the form:
 * ```
 *     sp([base] + (+/-[reg] + [c]) * [stride])
 * ```
 *
 * @param [base] The base stack pointer offset.
 * @param [reg] The register used in the stride expression.
 * @param [regPolarity] either 1 or -1.
 * @param [c] The constant offset added or subtracted from the register.
 * @param [stride] The stride multiplier applied to the adjusted register value.
 **/
data class StackStridePredicate(
    val base: Long,
    val reg: Value.Reg,
    val regPolarity: Int,
    val c: Long,
    val stride: Long
    ): Comparable<StackStridePredicate>{

    init {
        check(stride > 0) {"StackStridePredicate must have non-zero stride"}
        check(regPolarity == 1 || regPolarity == -1) {"StackStridePredicate register polarity must be 1 or -1" }
    }

    override fun toString(): String {
        val regPolarity = if (regPolarity < 0) {"-"}  else {""}
        return when {
            c == 0L -> "sp($base+($regPolarity$reg*$stride))"
            c < 0   -> "sp($base+(($regPolarity$reg-${-c})*$stride))"
            else    -> "sp($base+(($regPolarity$reg+$c)*$stride))"
        }
    }

    fun eval(regVal: Long): Long = base + (((regVal * regPolarity) + c) * stride)

    fun<TNum: INumValue<TNum>> evalS(regVal: TNum): TNum =
        regVal.mul(regPolarity.toLong()).add(c).mul(stride).add(base)

    /**
     * Add or subtract the constant [v].
     *
     * For instance,  given `sp(b + (-r + c) * 8)` and [v]=`8`, [op]=`+` then
     * it returns `sp(b + (-r + c + 1) * 8)`
     **/
    fun update(op: BinOp, v: Long): StackStridePredicate {
        check(op == BinOp.ADD || op == BinOp.SUB)
        check(v.absoluteValue.mod(stride) == 0L)
        return copy(c =  if (op == BinOp.ADD) { c + (v / stride) } else { c - (v / stride)})
    }

    /**
     * Substitute `reg` with `reg [op] [v]`.
     *
     * For instance,  given `sp(b + (r + c) * stride)` and [op]=`+`, [v]=`1` then
     * it returns `sp(b + (r - 1 + c) * stride)`
     **/
    fun substitute(op: BinOp, v: Long): StackStridePredicate {
        check(op == BinOp.ADD || op == BinOp.SUB)
        return copy(
            c = c + if (op == BinOp.ADD) {
                -1
            } else {
                1
            } * v * regPolarity
        )
    }

    override fun compareTo(other: StackStridePredicate): Int {
        return compareValuesBy(this, other,
            { it.base },
            { it.reg },
            { it.regPolarity},
            { it.c },
            { it.stride }
        )
    }
}

// The more predicates, the more precise.
// Because of the set-intersection semantics when we join we only keep common predicates.
private typealias StackStridePredicateSetT = SetIntersectionDomain<StackStridePredicate>

data class SetOfStackStridePredicate(private val predicates: StackStridePredicateSetT = StackStridePredicateSetT())
    : StackEnvironmentValue<SetOfStackStridePredicate>, Iterable<StackStridePredicate> {

    companion object {
        fun mkTop() =  SetOfStackStridePredicate(StackStridePredicateSetT.mkTop())
    }

    override fun isBottom() = predicates.isBottom()

    override fun isTop() = predicates.isTop()

    override fun mkTop() = SetOfStackStridePredicate(StackStridePredicateSetT.mkTop())

    fun size() = predicates.size()

    fun isEmpty() = size() == 0

    override fun join(other: SetOfStackStridePredicate) =
        SetOfStackStridePredicate(predicates.join(other.predicates) as StackStridePredicateSetT)

    override fun widen(other: SetOfStackStridePredicate) = this.join(other)

    override fun lessOrEqual(other: SetOfStackStridePredicate) =
        predicates.lessOrEqual(other.predicates)

    override fun iterator(): Iterator<StackStridePredicate> = predicates.iterator()

    fun add(predicate: StackStridePredicate) =
        SetOfStackStridePredicate(predicates.add(predicate) as StackStridePredicateSetT)

    /** Remove any predicate `p` such that `p.reg == [reg]` **/
    fun removeAll(reg: Value.Reg) =
        SetOfStackStridePredicate(predicates.removeAll { it.reg == reg } as StackStridePredicateSetT)

    fun transform(pred: (StackStridePredicate) -> Boolean,
                  transformer: (StackStridePredicate) -> StackStridePredicate
    ): SetOfStackStridePredicate {
        if (isBottom() || isTop()) {
            return this
        }

        val updates = mutableListOf<StackStridePredicate>()
        for (p in predicates.iterator()) {
            if (pred(p)) {
                updates.add(p)
            }
        }
        var outPredicates = predicates
        // Not sure if there is a more efficient way to do this kind of transformation with a `TreapSet`
        for (p in updates) {
            outPredicates = outPredicates.remove(p) as StackStridePredicateSetT
            outPredicates = outPredicates.add(transformer(p)) as StackStridePredicateSetT
        }

        return SetOfStackStridePredicate(outPredicates)
    }

    override fun toString() = predicates.toString()
}

/** Required by [StackStridePredicateDomain] to be able to use [ScalarBaseDomain] **/
object PredicateFactory: ScalarValueFactory<SetOfStackStridePredicate> {
    override fun mkTop() = SetOfStackStridePredicate(StackStridePredicateSetT.mkTop())
}

/**
 * A specialized predicate abstraction domain to reason about [StackStridePredicate] predicates.
 * This kind of predicate represents all stack offsets that satisfy the relationship
 *
 *  ```
 *       sp(base + ((+/-reg + c)* stride))
 *  ```
 * Note that if `reg` is spilled to the stack then the current implementation will lose track of it.
 * However, `reg` should be typically loop counters and hopefully the compiler will try not to spill loop counters.
 */
class StackStridePredicateDomain(private val base: ScalarBaseDomain<SetOfStackStridePredicate>)  {

    companion object {
        fun makeBottom() = StackStridePredicateDomain(ScalarBaseDomain.makeBottom(PredicateFactory))
        fun makeTop() = StackStridePredicateDomain(ScalarBaseDomain.makeTop(PredicateFactory))
    }

    fun deepCopy() = StackStridePredicateDomain(base.deepCopy())

    fun isBottom() = base.isBottom()

    fun isTop() = base.isTop()

    fun setToBottom() { base.setToBottom() }

    fun setToTop() { base.setToTop() }

    fun join(other: StackStridePredicateDomain) =  StackStridePredicateDomain(base.join(other.base))

    fun widen(other: StackStridePredicateDomain) = StackStridePredicateDomain(base.widen(other.base))

    fun lessOrEqual(other: StackStridePredicateDomain) = base.lessOrEqual(other.base)

    override fun toString(): String = base.toString()

    private data class Sequence(val values: List<Long>, val diff: Long) {
        init {
            check(values.size > 1)
            check(values.zipWithNext().all { (a, b) -> a < b })
            check(values.zipWithNext().all { (a, b) -> b-a == diff })
        }
    }

    /**
     * Performs reduction from predicates to scalar values
     *
     * If the abstract value of [reg] has been refined in [scalars], this function checks whether
     * any other register or stack location is mapped to a predicate [StackStridePredicate] that depends on [reg].
     * In such cases, it evaluates the predicate using the updated abstract value of [reg].
     * If it produces a more precise scalar value, the result is returned for the caller to apply the update.
     *
     **/
    fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> reduceScalars(
        reg: Value.Reg,
        scalars: ScalarDomain<TNum, TOffset>
    ): Map<Value.Reg, ScalarValue<TNum, TOffset>> {

        if (!SolanaConfig.UseScalarPredicateDomain.get()) {
            return mapOf()
        }

        val regVal = scalars.getValue(reg).type()
        if (regVal !is SbfType.NumType) {
            return mapOf()
        }

        val refinements = mutableMapOf<Value.Reg, ScalarValue<TNum, TOffset>>()
        // If register spilling is supported then we also need to update the stack entries that depends on `reg`
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            val regToRefine = Value.Reg(SbfRegister.getByValue(i.toByte()))
            val preds = base.getRegister(regToRefine)
            if (!preds.isTop() && !preds.isBottom()) {
                val oldScalarVal = scalars.getValue(regToRefine)
                // We iterate over all predicates to get the most precise scalar value for `regToRefine`
                var refinedScalarVal = oldScalarVal
                for (pred in preds) {
                    if (pred.reg == reg) {
                        val offset = scalars.sbfTypeFac.numToOffset(pred.evalS(regVal.value))
                        val newScalarVal = ScalarValue(SbfType.PointerType.Stack<TNum, TOffset>(offset))
                        if (!refinedScalarVal.lessOrEqual(newScalarVal)) {
                            refinedScalarVal = newScalarVal
                        }
                    }
                }
                if (refinedScalarVal != oldScalarVal) {
                    refinements[regToRefine] = refinedScalarVal
                }
            }
        }
        return refinements
    }

    /** Return true if each application of [guess] using [regVals] is a value of [stackVals] **/
    private fun checkClosedForm(guess: StackStridePredicate, stackVals: List<Long>, regVals: List<Long>): Boolean {
        if (stackVals.size != regVals.size) {
            return false
        }
        val expectedValues = stackVals.toMutableList()
        regVals.forEach { x ->
            val y = guess.eval(x)
            if (!expectedValues.remove(y)) {
                return false
            }
        }
        check(expectedValues.isEmpty())
        return true
    }

    /**
     * Return a set of predicates [StackStridePredicate] that predict the abstract value of [reg] using ranges of
     * known points [stackSeq] and [regSeq].
     *
     * There are four cases we want to support:
     *
     *  - `r1` and `r2` are increasing:
     *
     *  Note that this function is executed as part of the transfer function of `r2 = r2 + 8`.
     *  ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      r2 = r2 + 8
     *      r1 = r1 + 1
     *   }
     *
     *  SCALAR INFO =  r2 -> sp([3796, 3804]) and r1 -> [0, 1]
     *  PREDICATE GUESS: r2 -> sp(3796 + r1*8)
     *  INDUCTIVE CHECK:
     *    [[r2 = r2 + 8]] = r2 -> sp(3796 + (r1+1)*8)
     *    [[r1 = r1 + 1]] = r2 -> sp(3796 + r1*8)  INDUCTIVE!
     *  ```
     *
     *  - `r1` is decreasing and `r2` is increasing
     *
     *  Note that this function is executed as part of the transfer function of `r2 = r2 + 8`.
     *  ```
     *   r1 = 5
     *   r2 = sp(3796)
     *   while (r1 > 0) {
     *      r2 = r2 + 8
     *      r1 = r1 - 1
     *   }
     *
     *  SCALAR INFO = r2 -> sp([3796, 3804]) and r1 -> [5, 4]
     *  PREDICATE GUESS #1: r2 -> sp(3796 - (r1-5)*8)
     *  INDUCTIVE CHECK:
     *    [[r2 = r2 + 8]] = r2 -> sp(3796 - (r1-4)*8)
     *    [[r1 = r1 - 1]] = r2 -> sp(3796 - (r1-3)*8)  NO INDUCTIVE!
     *
     *  PREDICATE GUESS #2: r2 -> sp(3804 + (-r1+4)*8)
     *  INDUCTIVE CHECK:
     *    [[r2 = r2 + 8]] = r2 -> sp(3804 + (-r1+5)*8)
     *    [[r1 = r1 - 1]] = r2 -> sp(3804 + (-(r1+1)+5)*8) -> r2 -> sp(3804 + (-r1+4)*8)  INDUCTIVE!
     *  ```
     *  - `r1` is increasing and `r2` is decreasing
     *
     *  Note that this function is executed as part of the transfer function of `r2 = r2 - 8`.
     *  ```
     *  r1 = 0
     *  r2 = sp(3828)
     *  while (r1 < 5) {
     *     r2 = r2 - 8
     *     r1 = r1 + 1
     *  }
     *
     *  SCALAR INFO = r2 -> sp([3828, 3820]) and r1 -> [0, 1]
     *  PREDICATE GUESS #1: r2 -> sp(3828 - r1*8)
     *  INDUCTIVE CHECK:
     *     [[r2 = r2 - 8]]  r2 -> sp(3828 - (r1+1)*8)
     *     [[r1 = r1 + 1]]  r2 -> sp(3828 - (r1)*8)   INDUCTIVE!
     *  ```
     *
     *  - `r1` is decreasing and `r2` is decreasing
     *
     *  Note that this function is executed as part of the transfer function of `r2 = r2 - 8`.
     *  ```
     *  r1 = 5
     *  r2 = sp(3828)
     *  while (r1 > 0) {
     *     r2 = r2 - 8
     *     r1 = r1 - 1
     *  }
     *
     *  SCALAR INFO = r2 -> sp([3828, 3820]) and r1 -> [5, 4]
     *  PREDICATE GUESS #1: r2 -> sp(3828 - (-r1+5)*8)
     *  INDUCTIVE CHECK:
     *     [[r2 = r2 - 8]]  r2 -> sp(3828 - (-r1+4)*8)
     *     [[r1 = r1 - 1]]  r2 -> sp(3828 - (-(r1+1)+4)*8) --> r2 -> sp(3828 + (-r1+3)*8)  NO INDUCTIVE!
     *
     *  PREDICATE GUESS #2: r2 -> sp(3820 + (r1-4)*8)
     *  INDUCTIVE CHECK:
     *     [[r2 = r2 - 8]]  r2 -> sp(3820 + (r1-5)*8)
     *     [[r1 = r1 - 1]]  r2 -> sp(3820 + (r1+1-5)*8) --> r2 -> sp(3820 + (r1-4)*8) INDUCTIVE!
     *  ```
     * @param [stackSeq] is the range of known values for the stack pointer `r2`. This sequence is **ordered**.
     * @param [regSeq] is the range of known values for the register (loop counter) `r1`. This sequence is **ordered**.
     * @return cannot be neither bottom nor top.
     */
     private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> extrapolate(
        reg: Value.Reg,
        stackSeq: Sequence,
        regSeq: Sequence,
        scalars: ScalarDomain<TNum, TOffset>,
        inst: SbfInstruction.Bin,
     ): SetOfStackStridePredicate {
        val k = (scalars.getValue(inst.v).type() as? SbfType.NumType)?.value?.toLongOrNull()
        check(k != null) {"Extrapolate expects a non-null right operand in $inst"}

        val isIncreasingStackPtr: Boolean = when(inst.op) {
            BinOp.ADD -> k >= 0L
            BinOp.SUB -> k < 0L
            else -> true /* this is always sound, but it might produce the wrong candidate */
        }

        // At this point, we know whether the stack pointer increases or decreases, but we don't know whether
        // the loop counter increases or decreases.
        // Because of that, we add two predicates: one for the case whether the loop counter might increase and another predicate
        // for the case where the loop counter might decrease.
        var outPredicates = SetOfStackStridePredicate()

        // Only attempt guess if the differences are divisible
        if (stackSeq.diff.mod(regSeq.diff) == 0L) {
            val stride = stackSeq.diff / regSeq.diff
            val regStart = regSeq.values.first()
            val stackFirst = stackSeq.values.first()
            val stackLast = stackSeq.values.last()

            val guesses = listOf(
                // Guess when the loop counter increases
                if (isIncreasingStackPtr) {
                    StackStridePredicate(
                        base = stackFirst,
                        reg = reg,
                        regPolarity = 1,
                        c= -regStart,
                        stride = stride
                    )
                } else {
                    StackStridePredicate(
                        base = stackLast,
                        reg = reg,
                        regPolarity = -1,
                        c = regStart,
                        stride = stride
                    )
                },
                // Guess when the loop counter decreases
                if (isIncreasingStackPtr) {
                    StackStridePredicate(
                        base = stackLast,
                        reg = reg,
                        regPolarity = -1,
                        c = regStart,
                        stride = stride
                    )
                } else {
                    StackStridePredicate(
                        base = stackFirst,
                        reg = reg,
                        regPolarity = 1,
                        c = -regStart,
                        stride = stride
                    )
                }
            )

            for (guess in guesses) {
                dbg { "Generated guess $guess with stack values: ${stackSeq.values} and reg values:${regSeq.values}" }
                if (checkClosedForm(guess, stackSeq.values, regSeq.values)) {
                    dbg { "\t\tThe guess is good!" }
                    outPredicates = outPredicates.add(guess)
                } else {
                    dbg { "\t\tThe guess is bad" }
                }
            }
        }
        return outPredicates
    }

    /**
     * Reduction from scalar to predicates
     *
     * If [reg] is mapped to a stack pointer in [scalars] then it tries to infer a [StackStridePredicate] predicate that
     * can relate a base stack pointer with other registers.
     **/
    fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> inferPredicate(
        reg: Value.Reg,
        scalars: ScalarDomain<TNum, TOffset>,
        inst: SbfInstruction.Bin
    ): SetOfStackStridePredicate {

        fun getStride(ls: List<Long>): Long? {
            val diff = ls[1] -ls[0]
            return if ( ls.zipWithNext().all { (a, b) -> b-a == diff }) {
                diff
            } else {
                null
            }
        }

        check(!isBottom()) {"Calling inferPredicate on bottom "}
        if (!SolanaConfig.UseScalarPredicateDomain.get()) {
            return SetOfStackStridePredicate()
        }

        val oldPredicates = base.getRegister(reg)
        // Important: if we have already some predicate for `reg` we don't trigger the inference process again
        if (!oldPredicates.isTop() && !oldPredicates.isEmpty()) {
            return SetOfStackStridePredicate()
        }

        var stackSeq: Sequence?= null
        val regScalarVal = scalars.getValue(reg).type()
        if (regScalarVal is SbfType.PointerType.Stack && !regScalarVal.offset.isTop()) {
            val stackOffsets = regScalarVal.offset.toLongList().sorted()
            if (stackOffsets.size > 1) {
                getStride(stackOffsets)?.let { stride ->
                    stackSeq = Sequence(stackOffsets, stride)
                }
            }
        }

        if (stackSeq == null) {
            return SetOfStackStridePredicate()
        }

        var predicates = SetOfStackStridePredicate()

        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            val otherReg = Value.Reg(SbfRegister.getByValue(i.toByte()))
            val otherScalarType = scalars.getValue(otherReg).type()

            if (otherScalarType is SbfType.NumType && !otherScalarType.value.isTop())  {
                // This is crucial for [ScalarStackStrideDomain] to be useful. It assumes that the scalar analysis
                // keeps track of value sets. Without that, no predicates will be generated.
                val numbers = otherScalarType.value.toLongList().sorted()

                if (numbers.size > 1) {
                    getStride(numbers)?.let { stride ->
                        val numSeq = Sequence(numbers, stride)
                        val newPredicates = extrapolate(otherReg, stackSeq!!, numSeq, scalars, inst)
                        newPredicates.forEach { predicates = predicates.add(it) }
                    }
                }
            }
        }

        return predicates
    }

    fun getRegister(reg: Value.Reg) = base.getRegister(reg)

    fun setRegister(reg: Value.Reg, preds: SetOfStackStridePredicate) {
        base.setRegister(reg, preds)
    }

    fun forget(reg: Value.Reg) {
        base.forget(reg)

        // This is not efficient because it iterates over all registers.
        base.updateRegisters({ !it.isTop()}, transformer = { it.removeAll(reg) })
        base.updateScratchRegisters({ !it.isTop()}, transformer = { it.removeAll(reg) })
    }

    /**
     * The transfer function on the scalar domain has been already executed when `analyzeBin` is called
     */
    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeBin(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain<TNum, TOffset>
    ) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        if (isTop()) {
            return
        }

        val lhs = inst.dst
        when (inst.op) {
            BinOp.MOV -> {
                if (inst.v is Value.Reg) {
                    base.setRegister(lhs, base.getRegister(inst.v))
                } else {
                    forget(lhs)
                }
            }
            BinOp.ADD, BinOp.SUB -> {
                val k = (scalars.getValue(inst.v).type() as? SbfType.NumType)?.value?.toLongOrNull()
                when (scalars.getValue(lhs).type()) {
                    is SbfType.PointerType.Stack -> {
                        if (k != null) {
                            /**
                             * This step is for stack pointer arithmetic.
                             **/

                            val pre = base.getRegister(lhs)
                            if (!pre.isTop()) {
                                val post = pre
                                    .filter { k.absoluteValue.mod(it.stride) == 0L  }
                                    .fold(SetOfStackStridePredicate()) { acc, pred ->
                                        acc.add(pred.update(inst.op, k))
                                    }

                                if (post.isEmpty()) {
                                    forget(lhs)
                                } else {
                                    // We intentionally do not preserve old predicates
                                    base.setRegister(lhs, post)
                                }
                            }
                        } else {
                            forget(lhs)
                        }
                    }
                    is SbfType.NumType -> {
                        /**
                         * This step is for the update of a loop counter.
                         **/
                        if (k != null) {
                            base.updateRegisters(
                                { !it.isTop() },
                                { it.transform( { pred -> pred.reg == lhs}, { pred -> pred.substitute(inst.op, k)}) }
                            )
                        } else {
                            forget(lhs)
                        }
                    }
                    else -> {
                        forget(lhs)
                    }
                }
            }
            BinOp.MUL, BinOp.DIV, BinOp.MOD,
            BinOp.OR, BinOp.AND, BinOp.XOR,
            BinOp.LSH, BinOp.RSH, BinOp.ARSH -> forget(inst.dst)
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeCall(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain<TNum, TOffset>) {

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        /** CVT call **/
        val cvtFunction = CVTFunction.from(inst.name)
        if (cvtFunction != null) {
            when (cvtFunction) {
                is CVTFunction.Core -> {
                    when (cvtFunction.value) {
                        CVTCore.SAVE_SCRATCH_REGISTERS -> {
                            base.saveScratchRegisters()
                        }
                        CVTCore.RESTORE_SCRATCH_REGISTERS -> {
                            val topStack = (scalars.getValue(Value.Reg(SbfRegister.R10_STACK_POINTER)).type() as? SbfType.PointerType.Stack)?.offset?.toLongOrNull()
                            check(topStack != null){ "r10 should point to a statically known stack offset"}
                            base.restoreScratchRegisters(topStack)
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        // Since we are ignoring the stack it suffices to forget `r0`
        forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
    }

    private fun analyzeMem(locInst: LocatedSbfInstruction) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)

        if (inst.isLoad) {
            forget(inst.value as Value.Reg)
        }
    }

    fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyze(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain<TNum, TOffset>) {

        if (!SolanaConfig.UseScalarPredicateDomain.get()) {
            return
        }

        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> forget(s.dst)
                is SbfInstruction.Bin -> analyzeBin(locInst, scalars)
                is SbfInstruction.Call -> analyzeCall(locInst, scalars)
                is SbfInstruction.CallReg -> {}
                is SbfInstruction.Select -> forget(s.dst)
                is SbfInstruction.Havoc -> forget(s.dst)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume -> { /* do nothing is sound */}
                is SbfInstruction.Assert -> { /* do nothing is sound */}
                is SbfInstruction.Mem -> analyzeMem(locInst)
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
            }
        }
    }
}

/** Reduced product of [ScalarDomain] and [StackStridePredicateDomain] **/
class ScalarStackStridePredicateDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
        private val scalars: ScalarDomain<TNum, TOffset>,
        private val predicates: StackStridePredicateDomain
) : AbstractDomain<ScalarStackStridePredicateDomain<TNum, TOffset>>,
    ScalarValueProvider<TNum, TOffset>,
    MemoryDomainScalarOps<TNum, TOffset> {

    val sbfTypeFac: ISbfTypeFactory<TNum, TOffset> = scalars.sbfTypeFac

    constructor(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>, initPreconditions: Boolean = false):
        this(ScalarDomain(sbfTypeFac, initPreconditions), StackStridePredicateDomain.makeTop())

    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeBottom(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): ScalarStackStridePredicateDomain<TNum, TOffset> =
            ScalarStackStridePredicateDomain(ScalarDomain.makeBottom(sbfTypeFac), StackStridePredicateDomain.makeBottom())

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeTop(sbfTypeFac: ISbfTypeFactory<TNum, TOffset>) =
            ScalarStackStridePredicateDomain(sbfTypeFac)
    }

    override fun deepCopy() = ScalarStackStridePredicateDomain(scalars.deepCopy(), predicates.deepCopy())

    override fun isBottom() = scalars.isBottom() || predicates.isBottom()

    override fun isTop() = scalars.isTop() && predicates.isTop()

    override fun setToBottom() {
        scalars.setToBottom()
        predicates.setToBottom()
    }

    override fun setToTop() {
        scalars.setToTop()
        predicates.setToTop()
    }

    override fun join(other: ScalarStackStridePredicateDomain<TNum, TOffset>, left: Label?, right: Label?) =
        if (isBottom()) {
            other.deepCopy()
        } else if (other.isBottom()) {
            deepCopy()
        } else {
            ScalarStackStridePredicateDomain(scalars.join(other.scalars, left, right), predicates.join(other.predicates))
        }

    override fun widen(other: ScalarStackStridePredicateDomain<TNum, TOffset>, b: Label?) =
        if (isBottom()) {
            other.deepCopy()
        } else if (other.isBottom()) {
            deepCopy()
        } else {
            ScalarStackStridePredicateDomain(scalars.widen(other.scalars, b), predicates.widen(other.predicates))
        }


    override fun lessOrEqual(other: ScalarStackStridePredicateDomain<TNum, TOffset>, left: Label?, right: Label?) =
        scalars.lessOrEqual(other.scalars, left, right) && predicates.lessOrEqual(other.predicates)

    override fun pseudoCanonicalize(other: ScalarStackStridePredicateDomain<TNum, TOffset>) {
        fun evalPred(pred: StackStridePredicate): ScalarValue<TNum, TOffset> {
            // use `this` to get the scalar value of `pred.reg`
            val x = (this.scalars.getValue(pred.reg).type() as? SbfType.NumType)?.value
            return if (x != null) {
                val offset = scalars.sbfTypeFac.numToOffset(pred.evalS(x))
                ScalarValue(SbfType.PointerType.Stack(offset))
            } else {
                ScalarValue(scalars.sbfTypeFac.mkTop())
            }
        }

        if (this.isBottom() || other.isBottom()) {
            return
        }

        if (!SolanaConfig.UseScalarPredicateDomain.get()) {
            return
        }

        // If we support in the future register spilling then we need also to look at the stack
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            val reg = Value.Reg(SbfRegister.getByValue(i.toByte()))
            val preds = other.predicates.getRegister(reg)
            if (!preds.isTop() && !preds.isBottom()) {
                val scalarVal = this.scalars.getValue(reg)
                // Predicates from `other` that are also true in `this`
                val importedPredicates = preds.fold(SetOfStackStridePredicate()) { acc, pred ->
                    val newScalarVal = evalPred(pred)
                    dbg { "pseudo-canonicalize: $reg -> $pred  -- oldVal=$scalarVal newVal=eval($pred)=$newScalarVal" }

                    // Only propagate the predicate if both scalar values are equivalent
                    if (scalarVal.lessOrEqual(newScalarVal) && newScalarVal.lessOrEqual(scalarVal)) {
                        dbg { "\tpredicate PROPAGATED!" }
                        acc.add(pred)
                    } else {
                        dbg { "\tpredicate NOT PROPAGATED" }
                        acc
                    }
                }

                // we intentionally skip old predicates
                this.predicates.setRegister(
                    reg,
                    importedPredicates
                )
            }
        }
    }

    /** TRANSFER FUNCTIONS **/

    override fun forget(reg: Value.Reg) {
        scalars.forget(reg)

        if (SolanaConfig.UseScalarPredicateDomain.get()) {
            predicates.forget(reg)
        }
    }

    override fun getAsScalarValue(value: Value) = scalars.getValue(value)

    override fun getStackContent(offset: Long, width: Byte) = scalars.getStackContent(offset, width)

    override fun setScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>) {
        if (!isBottom()) {
            forget(reg)
            scalars.setScalarValue(reg, newVal)
        }
    }

    override fun setStackContent(offset: Long, width: Byte, value: ScalarValue<TNum, TOffset>) {
        scalars.setStackContent(offset, width, value)
    }

    override fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg, globalsMap: GlobalVariableMap) =
        scalars.getAsScalarValueWithNumToPtrCast(reg, globalsMap)

    fun analyze(locInst: LocatedSbfInstruction, globals: GlobalVariableMap, memSummaries: MemorySummaries) {
        val inst = locInst.inst
        if (!isBottom()) {
            if (SolanaConfig.UseScalarPredicateDomain.get()) {
                if (inst is SbfInstruction.Bin) {
                    when (inst.op) {
                        BinOp.ADD, BinOp.SUB -> {
                            val k = (scalars.getValue(inst.v).type() as? SbfType.NumType)?.value?.toLongOrNull()
                            if (k != 0L) {
                                // REDUCTION: if pointer arithmetic over the stack: infer new predicate
                                predicates.inferPredicate(inst.dst, scalars, inst).let { preds ->
                                    check(!preds.isTop() && !preds.isBottom()) { "inferPredicate cannot return neither bottom nor top" }
                                    if (!preds.isEmpty()) {
                                        predicates.setRegister(inst.dst, preds)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            scalars.analyze(locInst, globals, memSummaries)
            predicates.analyze(locInst, scalars)

            if (SolanaConfig.UseScalarPredicateDomain.get()) {
                if (inst is SbfInstruction.Assume) {
                    // REDUCTION: if a loop counter is constrained then restrict stack pointers for which we hold a predicate
                    inst.readRegisters.forEach {
                        predicates.reduceScalars(it, scalars).also { updates ->
                            for ((reg, scalarVal) in updates) {
                                scalars.setScalarValue(reg, scalarVal)
                            }
                        }
                    }
                }
            }
        }

        dbg { "After $inst: $this\n" }
    }

    override fun analyze(b: SbfBasicBlock,
                         globals: GlobalVariableMap,
                         memSummaries: MemorySummaries,
                         listener: InstructionListener<ScalarStackStridePredicateDomain<TNum, TOffset>>
    ): ScalarStackStridePredicateDomain<TNum, TOffset> =
        analyzeBlock(b,
                inState = this,
                transferFunction = { mutState, locInst ->
                    mutState.analyze(locInst, globals, memSummaries)
                },
                listener)

    override fun toString() =
        if (predicates.isTop()) {
            "$scalars"
        } else {
            "(scalars=$scalars,predicates=$predicates)"
        }

}
