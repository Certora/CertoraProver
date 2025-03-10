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
import sbf.sbfLogger
import sbf.SolanaConfig
import sbf.analysis.ScalarAnalysisRegisterTypes
import datastructures.stdcollections.*
import java.math.BigInteger

class NPDomainError(msg: String): RuntimeException("NPDomain error:$msg")

private const val debugNPDomain = false

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
data class NPDomain(private val csts: SetDomain<SbfLinearConstraint>) {

    override fun toString() = csts.toString()

    companion object {
        fun mkTrue() = NPDomain(SetIntersectionDomain())
        fun mkBottom() = NPDomain(SetIntersectionDomain.mkBottom())

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
            for (c in csts.iterator()) {
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
            for (c in csts.iterator()) {
                if (c.isContradiction()) {
                    return true
                }
            }
            return false
        }
    }

    fun isBottom() = csts.isBottom()

    fun contains(cst: SbfLinearConstraint): Boolean {
        return csts.contains(cst)
    }

    /// Very limited propagation. For now, only equalities between variables and constants.
    private fun propagate(maxNumSteps: Int = 5): SetDomain<SbfLinearConstraint> {
        var outCsts = csts.removeAll { cst -> cst.isTautology()}

        var change = true
        var i = 0
        while (change && i < maxNumSteps) {
            val oldCsts = outCsts
            /// Extract equalities
            val equalities = mutableListOf<SbfLinearConstraint>()
            for (c in outCsts.iterator()) {
                if (c.op == CondOp.EQ) {
                    equalities.add(c)
                }
            }
            /// Propagate each equality
            for (eq in equalities) {
                outCsts = evalEquality(outCsts as SetIntersectionDomain<SbfLinearConstraint>, eq)
            }
            change = !(oldCsts.lessOrEqual(outCsts) && outCsts.lessOrEqual(oldCsts))
            i++
        }
        return outCsts
    }

    fun normalize(): NPDomain {
        return if (isFalse(csts)) {
            mkBottom()
        } else {
            val outCsts = propagate()
            if (isFalse(outCsts)) {
                mkBottom()
            } else {
                NPDomain(outCsts)
            }
        }
    }

    fun lessOrEqual(other: NPDomain) = csts.lessOrEqual(other.csts)

    fun join(other: NPDomain) = NPDomain(csts.join(other.csts))

    private fun havoc(v: ExpressionVar): NPDomain {
        return NPDomain(csts.removeAll {
            it.contains(v)
        })
    }

    private fun substitute(oldV: ExpressionVar, newV: ExpressionVar): NPDomain {
        return substitute(oldV, LinearExpression(newV))
    }

    private fun substitute(oldV: ExpressionVar, newE: LinearExpression): NPDomain {
        var outCsts = SetIntersectionDomain<SbfLinearConstraint>()
        for (c in csts.iterator()) {
            val outCst = c.substitute(oldV, newE)
            outCsts = outCsts.add(outCst) as SetIntersectionDomain<SbfLinearConstraint>
        }
        return NPDomain(outCsts).normalize()
    }

    private fun eval(v: ExpressionVar, n: ExpressionNum) =
        NPDomain(eval(csts as SetIntersectionDomain<SbfLinearConstraint>, v, n)).normalize()

    private fun assign(lhsE: ExpressionVar, rhs: Value,
                       locInst: LocatedSbfInstruction, registerTypes: ScalarAnalysisRegisterTypes,
                       vFac: VariableFactory): NPDomain {
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

    private fun getNum(type: SbfType): Long? {
        return if (type is SbfType.NumType) {
            type.value.get()
        } else {
            null
        }
    }

    /**
     *  The metadata associated to an assume instruction can express an equality between a register and a stack content.
     *  We process such as equalities here.
     */
    private fun analyzeAssumeMetadata(locInst: LocatedSbfInstruction, vFac: VariableFactory): NPDomain {
        if (isBottom()) {
            return this
        }

        val inst = locInst.inst
        check(inst is SbfInstruction.Assume) {"analyzeAssumeMetadata does not expect $inst"}
        val eq =  inst.metaData.getVal(SbfMeta.EQUALITY_REG_AND_STACK)
        return if (eq != null) {
            val reg = eq.first
            val stackContent = eq.second
            val linCons = SbfLinearConstraint (CondOp.EQ,
                LinearExpression(RegisterVariable(reg, vFac)),
                LinearExpression(StackSlotVariable(stackContent.offset, stackContent.width, vFac)))
            val res = NPDomain(csts.add(linCons)).normalize()
            res
        } else {
            this
        }
    }

    // Public for NPAnalysis
    fun analyzeAssume(cond:Condition,
                      inst: LocatedSbfInstruction,
                      vFac: VariableFactory,
                      registerTypes: ScalarAnalysisRegisterTypes): NPDomain {
        if (isBottom()) {
            return mkBottom()
        }

        var linCons = getLinCons(cond, vFac)
        val leftN = getNum(registerTypes.typeAtInstruction(inst, cond.left.r))
        if (leftN != null) {
            linCons =  linCons.eval(RegisterVariable(cond.left, vFac), ExpressionNum(BigInteger.valueOf(leftN)))
        }
        return if (linCons.isContradiction()) {
            mkBottom()
        } else {
            if (cond.right is Value.Reg) {
                val rightN = getNum(registerTypes.typeAtInstruction(inst, cond.right.r))
                if (rightN != null) {
                    linCons = linCons.eval(RegisterVariable(cond.right, vFac),
                        ExpressionNum(BigInteger.valueOf(rightN)))
                }
            }
            if (linCons.isContradiction()) {
                mkBottom()
            } else {
                NPDomain(csts.add(linCons)).normalize()
            }
        }
    }

    /**
     *  Modeling of memcpy and memmove.
     *
     *  Recall that NPDomain only reasons about stack memory (i.e., heap is completely ignored). Thus, we only care
     *  when we transfer to the stack.
     **/
    private fun analyzeMemTransfer(locatedInst: LocatedSbfInstruction,
                                   @Suppress("UNUSED_PARAMETER")
                                   vFac: VariableFactory,
                                   registerTypes: ScalarAnalysisRegisterTypes): NPDomain {
        val inst = locatedInst.inst
        check(inst is SbfInstruction.Call) {"Precondition 1 of analyzeMemTransfer"}
        val solanaFunction = SolanaFunction.from(inst.name)
        check(solanaFunction == SolanaFunction.SOL_MEMCPY ||
                    solanaFunction == SolanaFunction.SOL_MEMMOVE) {"Precondition 2 of analyzeMemTransfer"}

        val dstTy = registerTypes.typeAtInstruction(locatedInst, SbfRegister.R1_ARG)
        return if (dstTy is SbfType.PointerType.Stack) {
            val lenTy = registerTypes.typeAtInstruction(locatedInst, SbfRegister.R3_ARG)
            if (lenTy is SbfType.NumType) {
                val len = lenTy.value.get()
                    ?: throw NPDomainError("cannot analyze $solanaFunction on stack without knowing length (1)")
                val dstStart = dstTy.offset.get()
                    ?: throw NPDomainError("cannot analyze $solanaFunction on stack without knowing destination offset")

                // We don't need to be precise on srcStart for soundness. This is why we don't throw an exception.
                val srcTy = registerTypes.typeAtInstruction(locatedInst, SbfRegister.R2_ARG)
                val srcStart = if (srcTy is SbfType.PointerType.Stack) {
                    srcTy.offset.get()
                } else {
                    null
                }

                var newCsts = csts
                val dstInterval = FiniteInterval.mkInterval(dstStart, len)
                // Do the transfer from source to destination
                // Note that the transfer function goes in the other direction
                for (cst in csts.iterator()) {
                    // If there is a constraint C over destination variables then
                    //   1. add new C' by substituting destination variables with source variables
                    //   2. remove C
                    if (solanaFunction == SolanaFunction.SOL_MEMCPY && srcStart != null) {
                        var newCst: SbfLinearConstraint? = null
                        for (dstV in cst.getVariables()) {
                            if (dstV is StackSlotVariable) {
                                val dstVInterval = FiniteInterval.mkInterval(dstV.offset, dstV.getWidth().toLong())
                                val adjOffset = dstStart - srcStart
                                if (dstInterval.includes(dstVInterval)) {
                                    val srcV = StackSlotVariable(offset=dstV.offset - adjOffset, width= dstV.getWidth(), vFac)
                                    newCst = newCst?.substitute(dstV, LinearExpression(srcV)) ?: cst.substitute(dstV, LinearExpression(srcV))
                                }
                            }
                        }
                        if (newCst != null) {
                            newCsts = newCsts.add(newCst)
                        }
                    }

                    // Remove constraint C if it uses a destination variable
                    for (dstV in cst.getVariables()) {
                        if (dstV is StackSlotVariable) {
                            val dstVInterval = FiniteInterval.mkInterval(dstV.offset, dstV.getWidth().toLong())
                            if (dstInterval.overlap(dstVInterval)) {
                                newCsts = newCsts.remove(cst)
                                break
                            }
                        }
                    }
                }
                NPDomain(newCsts)
            } else {
                throw NPDomainError("cannot analyze $solanaFunction on stack without knowing length (2)")
            }
        } else {
            this
        }
    }

    private fun analyzeSaveScratchRegisters(curVal: NPDomain, inst: SbfInstruction.Call, vFac: VariableFactory): NPDomain {
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
            curVal.substitute(lhs6, rhs6).substitute(lhs7, rhs7).substitute(lhs8, rhs8)
                .substitute(lhs9, rhs9)
        } else {
            curVal.havoc(RegisterVariable(r6, vFac))
                .havoc(RegisterVariable(r7, vFac))
                .havoc(RegisterVariable(r8, vFac))
                .havoc(RegisterVariable(r9, vFac))
        }
    }

    private fun analyzeRestoreScratchRegisters(curVal: NPDomain, inst: SbfInstruction.Call, vFac: VariableFactory): NPDomain {
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
            curVal.substitute(lhs6, rhs6).substitute(lhs7, rhs7).substitute(lhs8, rhs8)
                .substitute(lhs9, rhs9)
        } else {
            curVal.havoc(RegisterVariable(r6, vFac))
                .havoc(RegisterVariable(r7, vFac))
                .havoc(RegisterVariable(r8, vFac))
                .havoc(RegisterVariable(r9, vFac))
        }
    }

    private fun analyze(locatedInst: LocatedSbfInstruction, vFac: VariableFactory,
                        registerTypes: ScalarAnalysisRegisterTypes): NPDomain {
        val curVal = this
        when (val inst = locatedInst.inst) {
            is SbfInstruction.Assume -> {
                return curVal.analyzeAssume(inst.cond, locatedInst, vFac, registerTypes)
                    .analyzeAssumeMetadata(locatedInst, vFac)
            }
            is SbfInstruction.Assert -> {
                return if (SolanaConfig.SlicerBackPropagateThroughAsserts.get()) {
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
                    curVal
                } else {
                    mkTrue()
                }
            }
            is SbfInstruction.Select -> {
                val lhsV = RegisterVariable(inst.dst, vFac)
                val trueVal = inst.trueVal
                val falseVal = inst.falseVal
                return if (trueVal == falseVal) {
                    // Equivalent to an assignment
                    curVal.assign(lhsV, trueVal, locatedInst, registerTypes, vFac)
                } else {
                    val leftVal = curVal.assign(lhsV, trueVal, locatedInst, registerTypes, vFac)
                        .analyzeAssume(inst.cond, locatedInst, vFac, registerTypes)
                    val rightVal = curVal.assign(lhsV, falseVal, locatedInst, registerTypes, vFac)
                        .analyzeAssume(inst.cond.negate(), locatedInst, vFac, registerTypes)
                    leftVal.join(rightVal)
                }
            }
            is SbfInstruction.Bin -> {
                val lhs = inst.dst
                val lhsV = RegisterVariable(lhs, vFac)
                return when (inst.op) {
                    BinOp.MOV -> {
                        curVal.assign(lhsV, inst.v, locatedInst, registerTypes, vFac)
                    }
                    BinOp.ADD, BinOp.SUB -> {
                        val rhs = inst.v
                        val rhsE = if (rhs is Value.Imm) {
                            LinearExpression(ExpressionNum(rhs.v.toLong()))
                        } else {
                            LinearExpression(RegisterVariable(rhs as Value.Reg, vFac))
                        }
                        curVal.substitute(
                            lhsV, if (inst.op == BinOp.ADD) {
                                LinearExpression(lhsV) add rhsE
                            } else {
                                LinearExpression(lhsV) sub rhsE
                            }
                        )
                    }
                    else -> {
                        // havoc for now but we could handle multiplication/division by scalar
                        curVal.havoc(lhsV)
                    }
                }
            }
            is SbfInstruction.Call -> {
                val solFunction = SolanaFunction.from(inst.name)
                if (solFunction != null) {
                    return when (solFunction) {
                        SolanaFunction.ABORT -> {
                            mkBottom()
                        }
                        SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMMOVE -> {
                            analyzeMemTransfer(locatedInst, vFac, registerTypes)
                        }
                        else -> {
                            curVal.havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
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
                                    analyzeSaveScratchRegisters(curVal, inst, vFac)
                                }
                                CVTCore.RESTORE_SCRATCH_REGISTERS -> {
                                    analyzeRestoreScratchRegisters(curVal, inst, vFac)
                                }
                                CVTCore.SATISFY, CVTCore.SANITY -> {
                                    curVal
                                }
                                CVTCore.NONDET_SOLANA_ACCOUNT_SPACE, CVTCore.ALLOC_SLICE, CVTCore.NONDET_ACCOUNT_INFO -> {
                                    curVal.summarizeCall(
                                        locatedInst,
                                        vFac,
                                        registerTypes.analysis.getMemorySummaries(),
                                        registerTypes
                                    )
                                }
                            }
                        }
                        is CVTFunction.Calltrace -> curVal
                        is CVTFunction.Nondet , is CVTFunction.U128Intrinsics, is CVTFunction.NativeInt -> {
                            curVal.summarizeCall(
                                locatedInst,
                                vFac,
                                registerTypes.analysis.getMemorySummaries(),
                                registerTypes
                            )
                        }
                    }
                }

                return curVal.summarizeCall(
                    locatedInst,
                    vFac,
                    registerTypes.analysis.getMemorySummaries(),
                    registerTypes
                )
            }
            is SbfInstruction.CallReg -> {
                return curVal.havoc(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
            }
            is SbfInstruction.Havoc -> {
                return curVal.havoc(RegisterVariable(inst.dst, vFac))
            }
            is SbfInstruction.Mem -> {
                val baseTy = registerTypes.typeAtInstruction(locatedInst, inst.access.baseReg.r)
                if (baseTy is SbfType.PointerType.Stack) {
                    val offset = baseTy.offset.get()
                    if (offset != null) {
                        val width = inst.access.width
                        val baseV = StackSlotVariable(offset + inst.access.offset.toLong(), width, vFac)
                        return if (inst.isLoad) {
                            val regV = RegisterVariable(inst.value as Value.Reg, vFac)
                            curVal.substitute(regV, baseV)
                        } else {
                            val immVal: Long? = when (inst.value) {
                                is Value.Imm -> inst.value.v.toLong()
                                is Value.Reg -> getNum(registerTypes.typeAtInstruction(locatedInst, inst.value.r))
                            }
                            if (immVal != null) {
                                // Use of the forward analysis to refine backward analysis.
                                // See above discussion applies here.
                                curVal.eval(baseV, ExpressionNum(immVal))
                            } else {
                                check(inst.value is Value.Reg) { "NPDomain in memory store expects the value to be a register" }
                                val regV = RegisterVariable(inst.value, vFac)
                                curVal.substitute(baseV, regV)
                            }
                        }
                    }
                }
                return if (inst.isLoad) {
                    val regV = RegisterVariable(inst.value as Value.Reg, vFac)
                    curVal.havoc(regV)
                } else {
                    curVal
                }
            }
            is SbfInstruction.Jump -> return curVal
            is SbfInstruction.Un -> return curVal.havoc(RegisterVariable(inst.dst, vFac))
            is SbfInstruction.Exit -> return curVal
        }
    }

    // Transfer function for the whole block b.
    //
    // If propagateOnlyFromAsserts is true then backward propagation from exit blocks is only triggered after
    // the first (in reversed order) assertion is found.
    fun analyze(b: SbfBasicBlock,
                vFac: VariableFactory,
                registerTypes: ScalarAnalysisRegisterTypes,
                propagateOnlyFromAsserts: Boolean = true) =
        analyze(b, vFac, registerTypes, propagateOnlyFromAsserts, computeInstMap = false).first

    fun analyze(b: SbfBasicBlock,
                vFac: VariableFactory,
                registerTypes: ScalarAnalysisRegisterTypes,
                propagateOnlyFromAsserts: Boolean,
                computeInstMap: Boolean)
    : Pair<NPDomain, Map<LocatedSbfInstruction, NPDomain>> {

        val outInst = mutableMapOf<LocatedSbfInstruction, NPDomain>()
        var curVal = this
        if (curVal.isBottom()) {
            return Pair(curVal, outInst)
        }

        val isSink = b.getSuccs().isEmpty()
        var analyzedLastAssert = false

        // If the block has no successors then we start the backward analysis from the last assertion in the block. This
        // avoids pitfalls like propagating "false" to the entry of this block if we start the analysis from abort.
        //        exit:
        //              assert(r1 == 0)
        //              abort
        //
        // (Note that if the block has no successors can be only visited by the backward analysis if it has an assertion)
        for (locInst in b.getLocatedInstructions().reversed()) {
            if (debugNPDomain) {
                sbfLogger.info { "CoI analysis of ${b.getLabel()}::${locInst.inst}: $curVal" }
            }
            if (curVal.isBottom()) {
                return Pair(curVal, outInst)
            }

            analyzedLastAssert = analyzedLastAssert.or(locInst.inst.isAssertOrSatisfy())
            if (computeInstMap) {
                outInst[locInst] = curVal
            }
            curVal = if (propagateOnlyFromAsserts && isSink && !analyzedLastAssert) {
                if (debugNPDomain) {
                    sbfLogger.info {"\tSkipped analysis of ${locInst.inst} because no assertion found yet"}
                }
                curVal
            } else {
                curVal.analyze(locInst, vFac, registerTypes)
            }

            if (debugNPDomain) {
                sbfLogger.info { "res=${curVal}" }
            }
        }
        return Pair(curVal, outInst)
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
                              registerTypes: ScalarAnalysisRegisterTypes): NPDomain {

        class NPDomainSummaryVisitor(var absVal: NPDomain): SummaryVisitor {
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
                    val baseOffset = absType.offset.get()
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

/**
 * [SbfLinearConstraint] represents [e1] [op] [e2]
 * [op] can be a signed or unsigned comparison operator
 */
data class SbfLinearConstraint(val op: CondOp, val e1: LinearExpression, val e2: LinearExpression)
    : Comparable<SbfLinearConstraint>{

    override fun toString() = "$e1 ${toString(op)} $e2"

    override fun compareTo(other: SbfLinearConstraint): Int {
        return if (op < other.op) {
            -1
        } else if (op > other.op) {
            1
        } else {
            val x = e1.compareTo(other.e1)
            if (x == 0) {
                e2.compareTo(other.e2)
            } else {
                x
            }
        }
    }

    // Whether v1 op v2 evaluates to true or false
    private fun eval(v1: ExpressionNum, v2: ExpressionNum, op: CondOp): Boolean {
        val x = v1.n
        val y = v2.n
        return when (op) {
            CondOp.EQ -> x == y
            CondOp.NE -> x != y
            CondOp.SGE -> x >= y
            CondOp.SGT -> x > y
            CondOp.SLE -> x <= y
            CondOp.SLT -> x < y
            CondOp.GE, CondOp.GT -> {
                // If both operands are positive or negative then we can use signed interpretation.
                // Otherwise, we need to look at case-by-case.
                if (x >= BigInteger.ZERO) {
                    if (y >= BigInteger.ZERO) {
                        if (op == CondOp.GE) {x >= y} else {x > y}
                    } else {
                        false
                    }
                } else {
                    if (y >= BigInteger.ZERO) {
                        true
                    } else {
                        if (op == CondOp.GE) {x >= y} else {x > y}
                    }
                }
            }
            CondOp.LE, CondOp.LT -> {
                // This is a recursive call but the next call will match CondOp.GE, CondOp.GT case
                val newOp = op.swap()
                check(newOp == CondOp.GE || newOp == CondOp.GT)
                eval(v2, v1, newOp)
            }
        }
    }

    fun isContradiction(): Boolean {
        val v1 = e1.getConstant()
        val v2 = e2.getConstant()
        return if (v1 != null && v2 != null) {
            !eval(v1, v2, op)
        } else {
            false
        }
    }

    fun isTautology(): Boolean {
        val v1 = e1.getConstant()
        val v2 = e2.getConstant()
        return if (v1 != null && v2 != null) {
            eval(v1, v2, op)
        } else {
            false
        }
    }

    // Check that + does not perform extra allocations
    fun getVariables(): List<Variable> = e1.getVariables() + e2.getVariables()

    fun contains(v: ExpressionVar): Boolean {
        return e1.contains(v) || e2.contains(v)
    }

    fun substitute(oldV: ExpressionVar, newE: LinearExpression): SbfLinearConstraint {
        return SbfLinearConstraint(op, e1.substitute(oldV, newE), e2.substitute(oldV, newE))
    }

    fun eval(v: ExpressionVar, n: ExpressionNum): SbfLinearConstraint {
        return SbfLinearConstraint(op, e1.eval(v, n), e2.eval(v, n))
    }
}
