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

import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.SolanaConfig
import datastructures.stdcollections.*
import log.*
import sbf.analysis.AnalysisRegisterTypes
import java.math.BigInteger

class NPDomainError(msg: String): RuntimeException("NPDomain error:$msg")

private val logger = Logger(LoggerTypes.SBF_BACKWARD_SCALAR_ANALYSIS)
private fun dbg(msg: () -> Any) { logger.info(msg)}

/**
 * Synthetic variable that represents the content of a regular register [reg]
 */
data class RegisterVariable(val reg: Value.Reg, private val vFac: VariableFactory): Variable(vFac.get(reg.toString())) {
    override fun hashCode() = super.hashCode()
    override fun equals(other: Any?): Boolean = super.equals(other)
    override fun toString() = "$reg"
}

/**
 * Synthetic variable that represents the content of the stack at offset [offset] with [width] bytes.
 **/
data class StackSlotVariable(val offset: Long, private val width: Short, private val vFac: VariableFactory)
    : Variable(make(offset, width, vFac)) {

    override fun hashCode() = super.hashCode()
    override fun equals(other: Any?): Boolean = super.equals(other)
    override fun toString() = "*Stack_${offset}_${width}"
    companion object {
        private fun make(offset: Long, width: Short, vFac: VariableFactory): Variable {
            return vFac.get("*Stack_${offset}_${width}")
        }
    }

    fun toFiniteInterval() = FiniteInterval.mkInterval(offset, width.toLong())

    fun getWidth() = width
}

/**
 * Synthetic variable that represents the content of a scratch register [reg] at call [callId]
 */
data class ScratchRegisterVariable(val callId: ULong, val reg: Value.Reg, private val vFac: VariableFactory)
    : Variable(vFac.get("SavedScratchReg_" + reg.toString() + "_call_" + callId)) {
    override fun hashCode() = super.hashCode()
    override fun equals(other: Any?): Boolean = super.equals(other)
    override fun toString() = "Scratch($reg,$callId)"
}


/**
 *  Abstract domain used for backward slicing. "NP" refers to necessary preconditions.
 *
 *  Represent the set of constraints whose validity (interpreted the set as the logical conjunction of each element)
 *  is necessary to reach a block with an assertion. Therefore, if one constraint is false then there is not a path
 *  from that block to any assertion.
 *
 *  - "true" is represented as the empty set
 *  - "false" is represented as any set of constraints that contains a contradiction
 *  - If csts is false then the whole abstract state is normalized to bottom.
 */
data class NPDomain<D, TNum, TOffset>(
    private val csts: SetDomain<SbfLinearConstraint>,
    private val isBot: Boolean,
    private val sbfTypeFac: ISbfTypeFactory<TNum, TOffset>
    )
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>,
          D: ScalarValueProvider<TNum, TOffset>,
          D: StackLocationQuery {

    override fun toString() = if (isBot) { "bot" } else { csts.toString() }

    companion object {
        fun <D, TNum, TOffset> mkTrue(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>
        ) where TNum: INumValue<TNum>,
                TOffset: IOffset<TOffset>,
                D: AbstractDomain<D>,
                D: ScalarValueProvider<TNum, TOffset>,
                D: StackLocationQuery =
            NPDomain<D, TNum, TOffset>(SetIntersectionDomain(), isBot = false, sbfTypeFac)

        fun <D, TNum, TOffset>  mkBottom(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>
        ) where TNum: INumValue<TNum>,
                TOffset: IOffset<TOffset>,
                D: AbstractDomain<D>,
                D: ScalarValueProvider<TNum, TOffset>,
                D: StackLocationQuery =
            NPDomain<D, TNum, TOffset>(SetIntersectionDomain(), isBot = true, sbfTypeFac)

        fun getLinCons(cond: Condition, vFac: VariableFactory): SbfLinearConstraint {
            val left = cond.left
            val right = cond.right
            val op = cond.op
            val e1 = LinearExpression(RegisterVariable(left, vFac))
            val e2 = if (right is Value.Imm) {
                LinearExpression(ExpressionNum(BigInteger.valueOf(right.v.toLong())))
            } else {
                LinearExpression(RegisterVariable(right as Value.Reg, vFac))
            }
            return SbfLinearConstraint(op, e1, e2)
        }

        private fun eval(csts: SetIntersectionDomain<SbfLinearConstraint>,
                         v: ExpressionVar, n: ExpressionNum): SetIntersectionDomain<SbfLinearConstraint> {
            var outCsts = SetIntersectionDomain<SbfLinearConstraint>()
            for (c in csts) {
                val outCst = c.eval(v,n)
                outCsts = outCsts.add(outCst)  as SetIntersectionDomain<SbfLinearConstraint>
            }
            return outCsts
        }

        private fun evalEquality(csts: SetIntersectionDomain<SbfLinearConstraint>,
                                 c: SbfLinearConstraint): SetIntersectionDomain<SbfLinearConstraint>{
            check(c.op == CondOp.EQ) {"evalEquality expects only equalities"}
            run {
                val x = c.e1.getVariable()
                val y = c.e2.getConstant()
                if (x != null && y != null) {
                    // We don't want to evaluate c because it will become a tautology and then removed
                    return eval(csts.remove(c) as SetIntersectionDomain<SbfLinearConstraint>, x, y).
                                add(c) as SetIntersectionDomain<SbfLinearConstraint>
                }
            }
            run{
                val x = c.e1.getConstant()
                val y = c.e2.getVariable()
                if (x != null && y != null) {
                    // We don't want to evaluate c because it will become a tautology and then removed
                    return eval(csts.remove(c) as SetIntersectionDomain<SbfLinearConstraint>, y, x).
                                add(c) as SetIntersectionDomain<SbfLinearConstraint>
                }
            }

            return csts
        }

        private fun isFalse(csts: SetDomain<SbfLinearConstraint>): Boolean {
            for (c in csts) {
                if (c.isContradiction()) {
                    return true
                }
            }
            return false
        }

        /**
         * Very limited propagation.
         *  - only equalities between variables and constants.
         *
         * Return null if [csts] is detected as unsatisfiable
         **/
        private fun propagateOneStep(csts: SetDomain<SbfLinearConstraint>): SetDomain<SbfLinearConstraint>? {
            /// Split `csts` into several kind of constraints
            val eqs = mutableListOf<SbfLinearConstraint>()
            val nonEqs = mutableListOf<SbfLinearConstraint>()
            val intervalIneqs = mutableListOf<SbfLinearConstraint>()
            for (c in csts) {
                when (c.op) {
                    CondOp.EQ -> eqs.add(c)
                    CondOp.SLE, CondOp.LE, CondOp.SGE, CondOp.GE -> {
                        nonEqs.add(c)
                        if (c.e1.getVariable() != null && c.e2.getConstant() != null) {
                            intervalIneqs.add(c)
                        }
                    }
                    CondOp.NE -> nonEqs.add(c)
                    CondOp.SLT, CondOp.LT, CondOp.SGT, CondOp.GT -> {
                        check(false) {"After preprocessing no strict inequalities are expected"}
                    }
                }
            }

            /// Find trivial inconsistencies: if cst and not(cst) then false
            for (cst in nonEqs) {
                for (other in csts) {
                    if (other.negate() == cst) {
                        return null
                    }
                }
            }

            /// Find inconsistencies between interval constraints
            val solver = IntervalSolver(intervalIneqs, UnsignedIntervalFactory())
            val intervals = solver.run() ?: return null
            /// Extract equalities from the interval solver
            for ( (v, i) in intervals) {
                if (i.lb == i.ub) {
                    eqs.add(SbfLinearConstraint(CondOp.EQ, v, ExpressionNum(i.lb.toLong())))
                }
            }

            /// Propagate all equalities
            var outCsts = csts
            for (eq in eqs) {
                outCsts = evalEquality(outCsts as SetIntersectionDomain<SbfLinearConstraint>, eq)
            }
            return outCsts
        }

        /**
         *  Preprocess [csts] for easier reasoning.
         *  Return null if [csts] is detected as unsatisfiable.
         */
        private fun preprocess(csts: SetDomain<SbfLinearConstraint>): SetDomain<SbfLinearConstraint>? {
            val nonTrivialCsts = csts.removeAll { it.isTautology()}
            var out = SetIntersectionDomain<SbfLinearConstraint>()
            for (c in nonTrivialCsts) {
                if (c.isContradiction()) {
                    return null
                }
                out = out.add(c.normalize()) as SetIntersectionDomain<SbfLinearConstraint>
            }
            return out
        }

        /**
         * Represents a constraint on a variable extracted from a linear constraint.
         *
         * @param n The number the variable is compared against
         * @param isEquality If true, represents an equality constraint `(variable == constant)`.
         *                   If false, represents a disequality constraint `(variable != constant)`.
         */
        data class ConstraintNum(
            val n: ExpressionNum,
            val isEquality: Boolean
        )

        fun extractEqAndDiseq(csts: SetDomain<SbfLinearConstraint>): Map<ExpressionVar, ConstraintNum> =
            buildMap {
                csts.forEach { c ->
                    if (c.op != CondOp.EQ && c.op != CondOp.NE) {
                        return@forEach
                    }

                    val v = c.e1.getVariable() ?: c.e2.getVariable()
                    val n = c.e1.getConstant() ?: c.e2.getConstant()

                    if (v != null && n != null) {
                        put (v, ConstraintNum(n, isEquality = (c.op == CondOp.EQ)))
                    }
                }
            }

    }

    fun isBottom() = isBot

    fun contains(cst: SbfLinearConstraint): Boolean {
        return if (isBottom()) {
            false
        } else {
            csts.contains(cst)
        }
    }

    /**
     * Remove any constraint that contains a variable that represents a byte sequence that strict overlaps with [slice].
     */
    private fun removeStrictOverlaps(slice: FiniteInterval): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        val newCsts = csts.removeAll { cst ->
            cst.getVariables()
                .filterIsInstance<StackSlotVariable>()
                .any { other ->
                    val otherSlice = other.toFiniteInterval()
                    slice != otherSlice && slice.overlap(otherSlice)
                }
        }

        return NPDomain(newCsts, isBot = false, sbfTypeFac)
    }

    /**
     *  Remove any constraint that contains a variable that might represent any byte
     *  overlapping with any interval in [xs].
     */
    private fun removeOverlaps(xs: List<FiniteInterval>): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        fun overlap(x: FiniteInterval, ys: List<FiniteInterval>) = ys.any { y -> x.overlap(y) }

        val newCsts = csts.removeAll { cst ->
            cst.getVariables()
                .filterIsInstance<StackSlotVariable>()
                .any { v -> overlap(v.toFiniteInterval(), xs) }
        }

        return NPDomain(newCsts, isBot = false, sbfTypeFac)
    }

    /**
     *  Return null if `csts` is unsatisfiable. Note that it is not an "if and only if".
     */
    private fun propagate(maxNumSteps: Int = 5): SetDomain<SbfLinearConstraint>? {
        if (isBottom()) {
            return null
        }

        var newCsts: SetDomain<SbfLinearConstraint>? = preprocess(csts) ?: return null
        var change = true
        var i = 0
        while (change && i < maxNumSteps) {
            val oldCsts = newCsts!!
            newCsts = propagateOneStep(oldCsts) ?: return null
            change = !(oldCsts.lessOrEqual(newCsts) && newCsts.lessOrEqual(oldCsts))
            i++
        }
        return newCsts
    }

    fun normalize(): NPDomain<D, TNum, TOffset> {
        return if (isFalse(csts)) {
            mkBottom(sbfTypeFac)
        } else {
            val outCsts = propagate()
            if (outCsts == null || isFalse(outCsts)) {
                mkBottom(sbfTypeFac)
            } else {
                NPDomain(outCsts, isBot = false, sbfTypeFac)
            }
        }
    }

    fun lessOrEqual(other: NPDomain<D, TNum, TOffset>): Boolean {
        return if (isBottom()) {
            true
        } else if (other.isBottom()) {
            false
        } else {
            csts.lessOrEqual(other.csts)
        }
    }

    fun join(other: NPDomain<D, TNum, TOffset>): NPDomain<D, TNum, TOffset> {
        return if (isBottom()) {
            other
        } else if (other.isBottom()) {
            this
        } else {
            NPDomain(csts.join(other.csts), isBot = false, sbfTypeFac)
        }
    }

    private fun havoc(v: ExpressionVar): NPDomain<D, TNum, TOffset> {
        return if (isBottom()) {
            this
        } else {
            NPDomain(
                csts.removeAll {
                    it.contains(v)
                },
                isBot = false,
                sbfTypeFac
            )
        }
    }

    private fun substitute(oldV: ExpressionVar, newV: ExpressionVar): NPDomain<D, TNum, TOffset> {
        return if (isBottom()) {
            this
        } else {
            substitute(oldV, LinearExpression(newV))
        }
    }

    private fun substitute(oldV: ExpressionVar, newE: LinearExpression): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        var outCsts = SetIntersectionDomain<SbfLinearConstraint>()
        for (c in csts) {
            val outCst = c.substitute(oldV, newE)
            outCsts = outCsts.add(outCst) as SetIntersectionDomain<SbfLinearConstraint>
        }
        return NPDomain<D, TNum, TOffset>(outCsts, isBot = false, sbfTypeFac).normalize()
    }

    private fun eval(v: ExpressionVar, n: ExpressionNum): NPDomain<D, TNum, TOffset> {
        return if (isBottom()) {
            this
        } else {
            NPDomain<D, TNum, TOffset>(
                eval(csts as SetIntersectionDomain<SbfLinearConstraint>, v, n),
                isBot = false,
                sbfTypeFac
            ).normalize()
        }
    }


    private fun assign(lhsE: ExpressionVar, rhs: Value,
                       locInst: LocatedSbfInstruction, registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>,
                       vFac: VariableFactory): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        // We use information from the forward analysis.
        // The current implementation is too rigid because it assumes that the forward domain is
        // convertible to SbfType, but in the future we would like to allow arbitrary domains as
        // long as they are the same for both forward and backward analysis. In that case, we would
        // use the meet abstract operation to refine the backward domain using the forward domain.
        val rhsVal: Long? = when (rhs) {
            is Value.Imm -> rhs.v.toLong()
            is Value.Reg -> getNum(registerTypes.typeAtInstruction(locInst, rhs.r))
        }
        return if (rhsVal != null) {
            eval(lhsE, ExpressionNum(rhsVal))
        } else {
            check(rhs is Value.Reg) { "NPDomain::assign expects the value to be a register" }
            val regE = RegisterVariable(rhs, vFac)
            substitute(lhsE, regE)
        }
    }

    private fun getNum(type: SbfType<TNum, TOffset>): Long? {
        return if (type is SbfType.NumType) {
            type.value.toLongOrNull()
        } else {
            null
        }
    }

    /**
     *  Use the scalar domain to refine the NPDomain using equalities between registers and stack locations
     */
    private fun refineWithEqualities(
        locInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        val inst = locInst.inst
        if (inst !is SbfInstruction.Assume) {
            return this
        }

        val state = types.getAbstractState(locInst) ?: return this

        val outCsts = inst.readRegisters.fold(csts) { acc, reg ->
            val stackLoc = state.getStackSource(reg) ?: return@fold acc
            val cst = SbfLinearConstraint(
                CondOp.EQ,
                LinearExpression(RegisterVariable(reg, vFac)),
                LinearExpression(StackSlotVariable(stackLoc.offset, stackLoc.width.toShort(), vFac))
            )
            acc.add(cst)
        }
        return NPDomain<D, TNum, TOffset>(outCsts, isBot = false, sbfTypeFac).normalize()
    }

    // Public for NPAnalysis
    fun analyzeAssume(cond:Condition,
                      inst: LocatedSbfInstruction,
                      vFac: VariableFactory,
                      registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>?): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return mkBottom(sbfTypeFac)
        }

        if (registerTypes == null) {
            val linCons = getLinCons(cond, vFac)
            return if (linCons.isContradiction()) {
                mkBottom(sbfTypeFac)
            } else {
                NPDomain<D, TNum, TOffset>(csts.add(linCons), isBot = false, sbfTypeFac).normalize()
            }
        }

        val left = cond.left
        var linCons = getLinCons(cond, vFac)
        val leftN = getNum(registerTypes.typeAtInstruction(inst, left))
        if (leftN != null) {
            linCons =  linCons.eval(RegisterVariable(left, vFac), ExpressionNum(BigInteger.valueOf(leftN)))
        }
        return if (linCons.isContradiction()) {
            mkBottom(sbfTypeFac)
        } else {
            val right = cond.right
            if (right is Value.Reg) {
                val rightN = getNum(registerTypes.typeAtInstruction(inst, right))
                if (rightN != null) {
                    linCons = linCons.eval(RegisterVariable(right, vFac),
                        ExpressionNum(BigInteger.valueOf(rightN)))
                }
            }
            if (linCons.isContradiction()) {
                mkBottom(sbfTypeFac)
            } else {
                NPDomain<D, TNum, TOffset>(csts.add(linCons), isBot = false, sbfTypeFac).normalize()
            }
        }
    }

    /**
     *  Transfer functions for:
     *  - `memcpy(dst, src, len)`
     *  - `memcpy_zext(dst, src, i)`
     *  - `memcpy_trunc(dst, src, i)`
     *  - `memset(ptr, val, len)`
     *  - `memmove(dst, src, len)`
     **/
    private fun analyzeMemIntrinsics(
        locInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val inState = if (inst.writeRegister.contains(r0)) {
            havoc(RegisterVariable(r0, vFac))
        } else {
            this
        }

        return when (val f = SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY ->
                inState.analyzeMemcpy(locInst, vFac, types)
            SolanaFunction.SOL_MEMCPY_ZEXT ->
                inState.analyzeMemcpyZExtOrTrunc(locInst, vFac, types, size = { 8 })
            SolanaFunction.SOL_MEMCPY_TRUNC ->
                inState.analyzeMemcpyZExtOrTrunc(locInst, vFac, types, size = { n -> n })
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMSET ->
                inState.analyzeMemsetOrMemmove(locInst, vFac, types)
            else -> error("unreachable $f")
        }
    }

    /**
     * Helper for memory intrinsics
     *
     * If [dstReg] points to the stack then it removes any constraint that refers to any stack location
     * between `[x, x+len)` where `x` is the stack offset pointed by [dstReg] and `len` is the length stored in [lenReg]
     **/
    private fun havocStack(
        locInst: LocatedSbfInstruction,
        dstReg: SbfRegister,
        lenReg: SbfRegister,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        val dstTy = types.typeAtInstruction(locInst, dstReg)
        if (dstTy !is SbfType.PointerType.Stack)  {
            return this
        }

        val inst = locInst.inst

        val dstOffset = dstTy.offset
        if (dstOffset.isTop()) {
            throw NPDomainError("Statically unknown stack offset $dstReg in $inst")
        }

        val len = (types.typeAtInstruction(locInst, lenReg) as? SbfType.NumType)?.value?.toLongOrNull()
            ?: throw NPDomainError("Statically unknown length in $inst")

        val dstSlices = dstOffset.toLongList().map { FiniteInterval.mkInterval(it, len) }
        return removeOverlaps(dstSlices)
    }

    private data class StackTransferInfo(
        val dstOffsets: List<Long>,
        val srcOffset: Long?,
        val len: Long
    ) { init { check(dstOffsets.isNotEmpty()) } }

    /**
     * Helper for memory intrinsics that transfer memory over the stack
     */
    private fun analyzeStackTransfer(
        locInst: LocatedSbfInstruction,
        dstReg: SbfRegister,
        srcReg: SbfRegister,
        lenReg: SbfRegister,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): StackTransferInfo? {

        val dstTy = types.typeAtInstruction(locInst, dstReg)
        if (dstTy !is SbfType.PointerType.Stack)  {
            return null
        }

        val inst = locInst.inst

        val dstOffset = dstTy.offset
        if (dstOffset.isTop()) {
            throw NPDomainError("Statically unknown stack offset $dstReg in $inst")
        }

        val srcTy = types.typeAtInstruction(locInst, srcReg)
        val srcOffset = (srcTy as? SbfType.PointerType.Stack)
            ?.offset
            ?.toLongOrNull()

        val len = (types.typeAtInstruction(locInst, lenReg) as? SbfType.NumType)?.value?.toLongOrNull()
            ?: throw NPDomainError("Statically unknown length in $inst")

        return StackTransferInfo(
            dstOffsets = dstOffset.toLongList(),
            srcOffset = srcOffset,
            len = len
        )
    }


    /**
     *  Transfer function for `memcpy(dst, src, len)`.
     *
     *  Recall that [NPDomain] only reasons about stack memory (i.e., heap is completely ignored).
     *  Thus, we only care when we transfer to the stack. `r0` is havoced in the caller.
     *
     *  @param locInst the memory transfer instruction
     *  @param vFac factory for internal variables
     *  @param types to extract info from the scalar analysis
     **/
    private fun analyzeMemcpy(
        locInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {

        check(!isBottom())
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        val solanaFn = SolanaFunction.from(inst.name)
        check(solanaFn == SolanaFunction.SOL_MEMCPY)

        val r1 = SbfRegister.R1_ARG // dest
        val r2 = SbfRegister.R2_ARG // src
        val r3 = SbfRegister.R3_ARG // len

        val (dstOffsets, srcOffset, len) = analyzeStackTransfer(locInst, r1, r2, r3, types)
            ?: return this
        val dstOffset = dstOffsets.singleOrNull()

        return if (dstOffset == null || srcOffset == null) {
            removeOverlaps(dstOffsets.map { FiniteInterval.mkInterval(it, len) })
        } else {
            val adjOffset = dstOffset - srcOffset
            val dstInterval = FiniteInterval(dstOffset, dstOffset + len - 1)

            // Do the transfer from source to destination
            // Note that the transfer function goes in the other direction
            var newCsts = csts
            for (cst in csts) {
                // 1. if there is a constraint C over destination variables then
                //    add new C' by substituting destination variables with source variables
                var newCst: SbfLinearConstraint? = null
                for (dstV in cst.getVariables().filterIsInstance<StackSlotVariable>()) {
                    val dstVInterval = FiniteInterval.mkInterval(dstV.offset, dstV.getWidth().toLong())
                    if (dstInterval.includes(dstVInterval)) {
                        val srcV = StackSlotVariable(offset=dstV.offset - adjOffset, width= dstV.getWidth(), vFac)
                        newCst = newCst?.substitute(dstV, LinearExpression(srcV))
                            ?: cst.substitute(dstV, LinearExpression(srcV))
                    }
                }
                if (newCst != null) {
                    newCsts = newCsts.add(newCst)
                }
            }
            NPDomain<D, TNum, TOffset>(newCsts, isBot = false, sbfTypeFac)
                // 2. Remove any constraint that uses a variable that refers to the destination slice
                .removeOverlaps(listOf(dstInterval))
        }
    }

    /**
     *  Transfer function for `memset(ptr, val, len)` and `memmove(dst, src, len)`
     *
     *  Recall that [NPDomain] only reasons about stack memory (i.e., heap is completely ignored).
     *  Thus, we only care when we write to the stack. `r0` is havoced in the caller.
     *
     *  @param locInst the memset instruction
     *  @param vFac factory for internal variables
     *  @param types to extract info from the scalar analysis
     **/
    private fun analyzeMemsetOrMemmove(
        locInst: LocatedSbfInstruction,
        @Suppress("UNUSED_PARAMETER")
        vFac: VariableFactory,
        types: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {

        check(!isBottom())
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        val solanaFn = SolanaFunction.from(inst.name)
        check(solanaFn == SolanaFunction.SOL_MEMSET || solanaFn == SolanaFunction.SOL_MEMMOVE)

        val r1 = SbfRegister.R1_ARG  // ptr or dst
        val r3 = SbfRegister.R3_ARG  // len

        return havocStack(locInst, r1, r3, types)
    }

    /**
     *  Transfer function for `memcpy_zext(dst, src, i)` or `memcpy_trunc(dst, src, i)`
     *
     *  Recall that [NPDomain] only reasons about stack memory (i.e., heap is completely ignored).
     *  Thus, we only care when we transfer to the stack. `r0` is havoced in the caller.
     *
     *  @param locInst the instruction
     *  @param vFac factory for internal variables
     *  @param types to extract info from the scalar analysis
     **/
    private fun analyzeMemcpyZExtOrTrunc(
        locInst: LocatedSbfInstruction,
        @Suppress("UNUSED_PARAMETER")
        vFac: VariableFactory,
        types: AnalysisRegisterTypes<D, TNum, TOffset>,
        size: (Long) -> Long
    ): NPDomain<D, TNum, TOffset> {

        check(!isBottom())
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        val solanaFunction = SolanaFunction.from(inst.name)
        check(solanaFunction == SolanaFunction.SOL_MEMCPY_ZEXT || solanaFunction == SolanaFunction.SOL_MEMCPY_TRUNC)

        val r1 = SbfRegister.R1_ARG // dest
        val r2 = SbfRegister.R2_ARG // src
        val r3 = SbfRegister.R3_ARG // i

        val (dstOffsets, _, i) = analyzeStackTransfer(locInst, r1, r2, r3, types)
            ?: return this

        // Very conservative transfer function
        return removeOverlaps(dstOffsets.map { FiniteInterval.mkInterval(it, size(i))})
    }

    private fun analyzeSaveScratchRegisters(
        inst: SbfInstruction.Call,
        vFac: VariableFactory
    ): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        val r6 = Value.Reg(SbfRegister.R6)
        val r7 = Value.Reg(SbfRegister.R7)
        val r8 = Value.Reg(SbfRegister.R8)
        val r9 = Value.Reg(SbfRegister.R9)
        val id = inst.metaData.getVal(SbfMeta.CALL_ID)
        return if (id != null) {
            val lhs6 = ScratchRegisterVariable(id, r6, vFac)
            val rhs6 = RegisterVariable(r6, vFac)
            val lhs7 = ScratchRegisterVariable(id, r7, vFac)
            val rhs7 = RegisterVariable(r7, vFac)
            val lhs8 = ScratchRegisterVariable(id, r8, vFac)
            val rhs8 = RegisterVariable(r8, vFac)
            val lhs9 = ScratchRegisterVariable(id, r9, vFac)
            val rhs9 = RegisterVariable(r9, vFac)
            substitute(lhs6, rhs6).
            substitute(lhs7, rhs7).
            substitute(lhs8, rhs8).
            substitute(lhs9, rhs9)
        } else {
            havoc(RegisterVariable(r6, vFac)).
            havoc(RegisterVariable(r7, vFac)).
            havoc(RegisterVariable(r8, vFac)).
            havoc(RegisterVariable(r9, vFac))
        }
    }

    private fun analyzeRestoreScratchRegisters(
        inst: SbfInstruction.Call,
        vFac: VariableFactory
    ): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        val r6 = Value.Reg(SbfRegister.R6)
        val r7 = Value.Reg(SbfRegister.R7)
        val r8 = Value.Reg(SbfRegister.R8)
        val r9 = Value.Reg(SbfRegister.R9)
        val id = inst.metaData.getVal(SbfMeta.CALL_ID)
        return if (id != null) {
            val lhs6 = RegisterVariable(r6, vFac)
            val rhs6 = ScratchRegisterVariable(id, r6, vFac)
            val lhs7 = RegisterVariable(r7, vFac)
            val rhs7 = ScratchRegisterVariable(id, r7, vFac)
            val lhs8 = RegisterVariable(r8, vFac)
            val rhs8 = ScratchRegisterVariable(id, r8, vFac)
            val lhs9 = RegisterVariable(r9, vFac)
            val rhs9 = ScratchRegisterVariable(id, r9, vFac)
            substitute(lhs6, rhs6).
            substitute(lhs7, rhs7).
            substitute(lhs8, rhs8).
            substitute(lhs9, rhs9)
        } else {
            havoc(RegisterVariable(r6, vFac)).
            havoc(RegisterVariable(r7, vFac)).
            havoc(RegisterVariable(r8, vFac)).
            havoc(RegisterVariable(r9, vFac))
        }
    }

    private fun analyzeBin(
        locatedInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        val inst = locatedInst.inst
        check(inst is SbfInstruction.Bin)
        check(!isBottom())

        val lhs = inst.dst
        val lhsV = RegisterVariable(lhs, vFac)
        return when (inst.op) {
            BinOp.MOV -> {
                assign(lhsV, inst.v, locatedInst, registerTypes, vFac)
            }
            BinOp.ADD, BinOp.SUB -> {
                val rhs = inst.v
                val rhsE = if (rhs is Value.Imm) {
                    LinearExpression(ExpressionNum(rhs.v.toLong()))
                } else {
                    LinearExpression(RegisterVariable(rhs as Value.Reg, vFac))
                }
                substitute(
                    lhsV, if (inst.op == BinOp.ADD) {
                        LinearExpression(lhsV) add rhsE
                    } else {
                        LinearExpression(lhsV) sub rhsE
                    }
                )
            }
            else -> {
                // havoc for now but we could handle multiplication/division by scalar
                havoc(lhsV)
            }
        }
    }

    private fun analyzeCall(
        locatedInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        val inst = locatedInst.inst
        check(inst is SbfInstruction.Call)
        check(!isBottom())

        val solFunction = SolanaFunction.from(inst.name)
        if (solFunction != null) {
            return when (solFunction) {
                SolanaFunction.ABORT -> {
                    mkBottom(sbfTypeFac)
                }
                SolanaFunction.SOL_MEMCPY,
                SolanaFunction.SOL_MEMSET,
                SolanaFunction.SOL_MEMCPY_ZEXT,
                SolanaFunction.SOL_MEMCPY_TRUNC,
                SolanaFunction.SOL_MEMMOVE -> {
                    analyzeMemIntrinsics(locatedInst, vFac, registerTypes)
                }
                else -> {
                    havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
                }
            }
        }

        val cvtFunction = CVTFunction.from(inst.name)
        if (cvtFunction != null) {
            return when (cvtFunction) {
                is CVTFunction.Core -> {
                    when (cvtFunction.value) {
                        CVTCore.ASSUME, CVTCore.ASSERT -> {
                            throw NPDomainError(
                                "unsupported call to ${inst.name}. " +
                                    "SimplifyBuiltinCalls::renameCVTCall was probably not called."
                            )
                        }
                        CVTCore.SAVE_SCRATCH_REGISTERS -> {
                            analyzeSaveScratchRegisters(inst, vFac)
                        }
                        CVTCore.RESTORE_SCRATCH_REGISTERS -> {
                            analyzeRestoreScratchRegisters(inst, vFac)
                        }
                        CVTCore.SATISFY, CVTCore.SANITY -> {
                            this
                        }
                        CVTCore.NONDET_SOLANA_ACCOUNT_SPACE,
                        CVTCore.ALLOC_SLICE,
                        CVTCore.NONDET_ACCOUNT_INFO,
                        CVTCore.MASK_64 -> {
                            summarizeCall(
                                locatedInst,
                                vFac,
                                registerTypes.analysis.getMemorySummaries(),
                                registerTypes
                            )
                        }
                    }
                }
                is CVTFunction.Calltrace -> this
                is CVTFunction.Nondet,
                is CVTFunction.U128Intrinsics,
                is CVTFunction.I128Intrinsics,
                is CVTFunction.NativeInt -> {
                    summarizeCall(
                        locatedInst,
                        vFac,
                        registerTypes.analysis.getMemorySummaries(),
                        registerTypes
                    )
                }
            }
        }

        return summarizeCall(
            locatedInst,
            vFac,
            registerTypes.analysis.getMemorySummaries(),
            registerTypes
        )
    }

    private fun addCst(cst: SbfLinearConstraint): NPDomain<D, TNum, TOffset> =
        NPDomain<D, TNum, TOffset>(csts.add(cst), isBot = false, sbfTypeFac).normalize()

    private fun addEq(v: ExpressionVar, n: ExpressionNum): NPDomain<D, TNum, TOffset> =
        addCst(SbfLinearConstraint(CondOp.EQ, v, n))

    private fun addDiseq(v: ExpressionVar, n: ExpressionNum): NPDomain<D, TNum, TOffset> =
        addCst(SbfLinearConstraint(CondOp.NE, v, n))

    private fun analyzeMem(
        locatedInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        val inst = locatedInst.inst
        check(inst is SbfInstruction.Mem)
        check(!isBottom())

        val baseTy = registerTypes.typeAtInstruction(locatedInst, inst.access.base)
        if (baseTy is SbfType.PointerType.Stack) {
            // For now, we are only precise if `offset` is a singleton.
            val offset = baseTy.offset.toLongOrNull()
            if (offset != null) {
                val width = inst.access.width
                val baseV = StackSlotVariable(offset + inst.access.offset.toLong(), width, vFac)
                return if (inst.isLoad) {
                    val regV = RegisterVariable(inst.value as Value.Reg, vFac)
                    val widthI = width.toInt()
                    if (widthI == 8) {
                        substitute(regV, baseV)
                    } else {
                        // load n bytes into an 8-byte register where n={1,2,4}
                        // We take the n low bytes and do zero extension.
                        extractEqAndDiseq(csts)[regV]
                            ?.let { cst ->
                                havoc(regV).run {
                                    val zextNum = cst.n.zext(widthI)
                                    if (cst.isEquality) {
                                        this.addEq(baseV, zextNum)
                                    } else {
                                        this.addDiseq(baseV, zextNum)
                                    }
                                }
                            }
                            ?: havoc(regV)
                    }
                } else {
                    // remove any stack location that overlap with baseV
                    val noOverlaps = removeStrictOverlaps(baseV.toFiniteInterval())

                    val rhs = inst.value
                    val rhsValues: List<Long>? = when (rhs) {
                        is Value.Imm -> listOf(rhs.v.toLong())
                        is Value.Reg -> {
                            (registerTypes.typeAtInstruction(locatedInst, rhs) as? SbfType.NumType)?.value?.toLongList()?.let {
                                it.ifEmpty { null }
                            }
                        }
                    }
                    if (rhsValues != null) {
                        // Use of the forward analysis to refine backward analysis.
                        // See above discussion applies here.
                        check(rhsValues.isNotEmpty())
                        rhsValues.fold(mkBottom(sbfTypeFac)) { acc, rhsValue ->
                            acc.join(noOverlaps.eval(baseV, ExpressionNum(rhsValue)))
                        }
                    } else {
                        check(rhs is Value.Reg) { "NPDomain in memory store expects the value to be a register" }
                        val regV = RegisterVariable(rhs, vFac)
                        noOverlaps.substitute(baseV, regV)
                    }
                }
            }
        }

        // here if memory access is not on the stack
        return if (inst.isLoad) {
            val regV = RegisterVariable(inst.value as Value.Reg, vFac)
            havoc(regV)
        } else {
            this
        }
    }

    private fun analyzeSelect(
        locatedInst: LocatedSbfInstruction,
        vFac: VariableFactory,
        registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>
    ): NPDomain<D, TNum, TOffset> {
        val inst = locatedInst.inst
        check(inst is SbfInstruction.Select)
        check(!isBottom())

        val lhsV = RegisterVariable(inst.dst, vFac)
        val trueVal = inst.trueVal
        val falseVal = inst.falseVal
        return if (trueVal == falseVal) {
            // Equivalent to an assignment
            assign(lhsV, trueVal, locatedInst, registerTypes, vFac)
        } else {
            val leftVal = assign(lhsV, trueVal, locatedInst, registerTypes, vFac)
                .analyzeAssume(inst.cond, locatedInst, vFac, registerTypes)
            val rightVal = assign(lhsV, falseVal, locatedInst, registerTypes, vFac)
                .analyzeAssume(inst.cond.negate(), locatedInst, vFac, registerTypes)

            leftVal.join(rightVal)
        }
    }


    private fun analyze(locatedInst: LocatedSbfInstruction, vFac: VariableFactory,
                        registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>): NPDomain<D, TNum, TOffset> {
        if (isBottom()) {
            return this
        }

        return when (val inst = locatedInst.inst) {
            is SbfInstruction.Assume -> {
                analyzeAssume(inst.cond, locatedInst, vFac, registerTypes)
                    .refineWithEqualities(locatedInst, vFac, registerTypes)
            }
            is SbfInstruction.Assert -> {
                if (SolanaConfig.SlicerBackPropagateThroughAsserts.get()) {
                    // Recall that whenever the NPDomain detects false, we add an "abort" instruction at the
                    // **beginning** of the block to mark the block as unreachable.
                    //
                    // Therefore, this case is potentially unsound because if curVal is already bottom then this
                    // assertion will become unreachable. However, in cases where assertions are only in rule's post-conditions
                    // and post-conditions do not induce more assumptions the prover should be sound.
                    // This is why we keep this option guarded by this flag.
                    //
                    // Even with the optimistic flag, we ensure that the assertion does not add more assumptions.
                    // Otherwise, a trivial program like
                    //      "assert(false)" will be replaced with "abort"
                    // which is not what we want.
                    this
                } else {
                    mkTrue(sbfTypeFac)
                }
            }
            is SbfInstruction.Select -> {
                analyzeSelect(locatedInst, vFac, registerTypes)
            }
            is SbfInstruction.Bin -> {
                analyzeBin(locatedInst, vFac, registerTypes)
            }
            is SbfInstruction.Mem -> {
                analyzeMem(locatedInst, vFac, registerTypes)
            }
            is SbfInstruction.Call -> {
                analyzeCall(locatedInst, vFac, registerTypes)
            }
            is SbfInstruction.CallReg -> {
                havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
            }
            is SbfInstruction.Havoc -> {
                havoc(RegisterVariable(inst.dst, vFac))
            }
            is SbfInstruction.Un -> {
                havoc(RegisterVariable(inst.dst, vFac))
            }
            is SbfInstruction.Debug,
            is SbfInstruction.Jump,
            is SbfInstruction.Exit -> {
                this
            }
        }
    }

    /**
     * Transfer function for the whole block [b].
     *
     * If propagateOnlyFromAsserts is true then backward propagation from exit blocks is only triggered after
     * the first (in reversed order) assertion is found.
     **/
    fun analyze(b: SbfBasicBlock,
                vFac: VariableFactory,
                registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>,
                propagateOnlyFromAsserts: Boolean = true) =
        analyze(b, vFac, registerTypes, propagateOnlyFromAsserts, computeInstMap = false).first

    fun analyze(b: SbfBasicBlock,
                vFac: VariableFactory,
                registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>,
                propagateOnlyFromAsserts: Boolean,
                computeInstMap: Boolean)
    : Pair<NPDomain<D, TNum, TOffset>, Map<LocatedSbfInstruction, NPDomain<D, TNum, TOffset>>> {

        val outInst = mutableMapOf<LocatedSbfInstruction, NPDomain<D, TNum, TOffset>>()
        var curVal = this
        if (curVal.isBottom()) {
            return Pair(curVal, outInst)
        }

        val isSink = b.getSuccs().isEmpty()
        var analyzedLastAssert = false

        dbg { "POST=$curVal" }

        // If the block has no successors then we start the backward analysis from the last assertion in the block. This
        // avoids pitfalls like propagating "false" to the entry of this block if we start the analysis from abort.
        //        exit:
        //              assert(r1 == 0)
        //              abort
        //
        // (Note that if the block has no successors can be only visited by the backward analysis if it has an assertion)
        for (locInst in b.getLocatedInstructions().reversed()) {

            dbg { "Backward scalar analysis of ${b.getLabel()}::${locInst.inst}"}

            if (curVal.isBottom()) {
                return curVal to outInst
            }

            analyzedLastAssert = analyzedLastAssert.or(locInst.inst.isAssertOrSatisfy())
            if (computeInstMap) {
                outInst[locInst] = curVal
            }
            curVal = if (propagateOnlyFromAsserts && isSink && !analyzedLastAssert) {
                dbg {"\tSkipped analysis of ${locInst.inst} because no assertion found yet"}
                curVal
            } else {
                curVal.analyze(locInst, vFac, registerTypes)
            }

            dbg { "PRE=${curVal}" }

        }
        return curVal to outInst
    }

    /**
     * Use the summary available for the call to havoc the input registers
     * if they point to the stack. We do not care if a register points to a different memory region
     * since the NPDomain keeps only track of the stack.
     *
     * @param locInst is the call for which we want to apply a summary
     * @param vFac variable factory
     * @param memSummaries contains all function summaries
     * @param registerTypes provides the types for the arguments that hold before the execution of call
     */
    private fun summarizeCall(locInst: LocatedSbfInstruction,
                              vFac: VariableFactory,
                              memSummaries: MemorySummaries,
                              registerTypes: AnalysisRegisterTypes<D, TNum, TOffset>): NPDomain<D, TNum, TOffset> {

        class NPDomainSummaryVisitor(var absVal: NPDomain<D, TNum, TOffset>): SummaryVisitor {
            override fun noSummaryFound(locInst: LocatedSbfInstruction) {
                // Note that havocking r0 is not, in general, sound but without a summary
                // we cannot do more.
                absVal = absVal.havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
            }
            override fun processReturnArgument(locInst: LocatedSbfInstruction, type /*unused*/: MemSummaryArgumentType) {
                absVal = absVal.havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE ), vFac))
            }

            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister,
                                         offset: Long,
                                         width: Byte,
                                         @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                                         type /*unused*/: MemSummaryArgumentType) {
                // NPDomain only keeps track of the stack
                val absType = registerTypes.typeAtInstruction(locInst, reg)
                if (absType is SbfType.PointerType.Stack) {
                    val baseOffset = absType.offset.toLongOrNull()
                    if (baseOffset != null) {
                        val baseV = StackSlotVariable(baseOffset + offset, width.toShort(), vFac)
                        absVal = absVal.havoc(baseV)
                    }
                }
            }
        }

        val call = locInst.inst
        check(call is SbfInstruction.Call) {"summarizeCall expects a call instruction"}
        val vis = NPDomainSummaryVisitor(this)
        memSummaries.visitSummary(locInst, vis)
        return vis.absVal
    }
}
