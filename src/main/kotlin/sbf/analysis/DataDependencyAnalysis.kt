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

package sbf.analysis

import analysis.Direction
import analysis.JoinLattice
import com.certora.collect.*
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.support.*
import datastructures.stdcollections.*

private class DDAError(val msg: String): SolanaInternalError(msg)

/**
 * This analysis is useful for debugging PTA errors (exceptions).
 * Currently, we focus on a single target.
 *
 * **IMPORTANT**: the analysis is neither an over-approximation nor under-approximation.
 * If the code under analysis only uses stack and registers then the analysis is an over-approximation.
 * However, if the code uses heap or external memory then the analysis can be also an under-approximation to avoid
 * adding too many irrelevant data-dependencies.
 *
 **/
class DataDependencyAnalysis(private val target: LocatedSbfInstruction,
                             private val targetExp: PointerExpressionError,
                             private val cfg: SbfCFG,
                             globalsMap: GlobalVariableMap,
                             val memSummaries: MemorySummaries) :
    SbfCommandDataflowAnalysis<DataDepsState>(
        cfg,
        DataDepsState.lattice,
        DataDepsState.bottom,
        Direction.BACKWARD
    ) {
    private val fwdAnalysis = AdaptiveScalarAnalysis(cfg, globalsMap, memSummaries)
    private val registerTypes = AnalysisRegisterTypes(fwdAnalysis)
    private val vFac = VariableFactory()

    // outputs of the analysis:
    // - `deps` is a set of instructions where the data might flow to the target
    val deps: MutableSet<LocatedSbfInstruction> = mutableSetOf()
    // - `sources` is a set of instructions where the data that flows to the target might be initialized.
    // For imprecision of the analysis, some instructions might be marked as sources, but they are not in reality
    // (e.g., load from non-stack memory)
    // An instruction can be both in `deps` and `sources` (see processSelect)
    val sources: MutableSet<LocatedSbfInstruction> = mutableSetOf()

    // Since this analysis is executed when some exception occurred, we do not want to throw another exception.
    // Thus, if some exception happens then we bail out and print a warning.
    init {
        try {
            runAnalysis()
        } catch(e: DDAError) {
            sbf.sbfLogger.warn{"Data dependency analysis unexpectedly failed: ${e.msg}"}
        }
        processOutput()
    }

    /**
     *  Convenient helper to analyze or process `memcpy`/`memmove` instructions
     *  First, it throws exceptions if the instruction cannot be supported or there is some logical error
     *  Second, it asks the scalar analysis to split into cases depending on the memory areas involved in
     *  the transfer:
     *  - from stack (source) to stack (destination)
     *  - from stack (source) to non-stack (destination)
     *  - from non-stack (source) to stack (destination)
     *  - from non-stack (source) to non-stack (destination)
     */
    private fun <R> applyMemTransfer(cmd: LocatedSbfInstruction,
                                     stackToStackF: (dst: Long, src: Long, len: Long) -> R,
                                     stackToNonStackF: (src: Long, len: Long) -> R,
                                     nonStackToStackF: (dst: Long, len: Long) -> R,
                                     nonStackToNonStackF: () -> R): R {
        val inst = cmd.inst
        if (inst !is SbfInstruction.Call) {
            throw DDAError("$inst is not call")
        }
        if (SolanaFunction.from(inst.name) != SolanaFunction.SOL_MEMCPY &&
            SolanaFunction.from(inst.name) != SolanaFunction.SOL_MEMMOVE) {
            throw DDAError("$inst is not a memcpy or memmove")
        }
        val lenTy = registerTypes.typeAtInstruction(cmd, SbfRegister.R3_ARG)
        if (lenTy !is SbfType.NumType) {
            throw DDAError("len is not statically known")
        }
        val len = lenTy.value.toLongOrNull() ?: throw DDAError("len is not statically known")
        val dstTy = registerTypes.typeAtInstruction(cmd, SbfRegister.R1_ARG)
        val srcTy = registerTypes.typeAtInstruction(cmd, SbfRegister.R2_ARG)
        return when (dstTy) {
            is SbfType.PointerType.Stack -> {
                val dstStart = dstTy.offset.toLongOrNull() ?: throw DDAError("dest on stack with unknown offset in $cmd")
                when (srcTy) {
                    is SbfType.PointerType.Stack -> {
                        val srcStart = srcTy.offset.toLongOrNull() ?: throw DDAError("src on stack with unknown offset in $cmd")
                        stackToStackF(dstStart, srcStart, len)
                    }
                    else -> nonStackToStackF(dstStart, len)
                }
            }
            else -> {
                when (srcTy) {
                    is SbfType.PointerType.Stack -> {
                        val srcStart = srcTy.offset.toLongOrNull() ?: throw DDAError("src on stack with unknown offset in $cmd")
                        stackToNonStackF(srcStart, len)
                    }
                    else -> nonStackToNonStackF()
                }
            }
        }
    }

    /**
     *  Convenient helper to analyze or process `memset` instructions
     *  First, it throws exceptions if `memset` cannot be supported or there is some logical error
     *  Second, it asks the scalar analysis to split memset into cases depending on the memory area being set:
     *  - stack
     *  - non-stack (heap or external)
     */
    private fun <R> applyMemset(cmd: LocatedSbfInstruction,
                                stackF: (start: Long,  len: Long) -> R,
                                nonStackF: () -> R): R {
        val inst = cmd.inst
        if (inst !is SbfInstruction.Call) {
            throw DDAError(msg = "$inst is not call")
        }
        if (SolanaFunction.from(inst.name) != SolanaFunction.SOL_MEMSET) {
            throw DDAError(msg = "$inst is not a memset")
        }
        val lenTy = registerTypes.typeAtInstruction(cmd, SbfRegister.R3_ARG)
        if (lenTy !is SbfType.NumType) {
            throw DDAError(msg = "length is not statically known")
        }
        val len = lenTy.value.toLongOrNull() ?: throw DDAError(msg = "length is not statically known")
        return when (val ptrTy = registerTypes.typeAtInstruction(cmd, SbfRegister.R1_ARG)) {
            is SbfType.PointerType.Stack -> {
                val ptrStart = ptrTy.offset.toLongOrNull() ?: throw DDAError("memset on stack with unknown offset in $cmd")
                stackF(ptrStart, len)
            }
            else -> nonStackF()
        }
    }

    /**
     *  Convenient helper to analyze or process load and store instructions
     *  First, it throws exceptions if the instruction cannot be supported or there is some logical error
     *  Second, it asks the scalar analysis to split into cases depending on the memory area being accessed:
     *  - load from stack
     *  - store to stack
     *  - load from non-stack (heap or external)
     *  - store to non-stack
     */
    private fun <R> applyMem(cmd: LocatedSbfInstruction,
                             storeToStack: (base: Long, offset: Long, width: Short, value: Value) -> R,
                             loadFromStack:(base: Long, offset: Long, width: Short, lhs: Value.Reg) -> R,
                             storeToNonStack: (value: Value) -> R,
                             loadFromNonStack: (lhs: Value.Reg) -> R): R {

        val inst = cmd.inst
        if (inst !is SbfInstruction.Mem) {
            throw DDAError(msg = "$inst is not a memory instruction")
        }

        val baseTy = registerTypes.typeAtInstruction(cmd, inst.access.baseReg.r)
        return if (baseTy is SbfType.PointerType.Stack) {
            val base = baseTy.offset.toLongOrNull() ?: throw DDAError("Memory access to the stack with unknown offset")
            if (inst.isLoad) {
                loadFromStack(base, inst.access.offset.toLong(), inst.access.width, inst.value as Value.Reg)
            } else {
                storeToStack(base, inst.access.offset.toLong(), inst.access.width, inst.value)
            }
        } else {
            if (inst.isLoad) {
                loadFromNonStack(inst.value as Value.Reg)
            } else {
                storeToNonStack(inst.value)
            }
        }
    }

    /**
     *  Convenient helper to analyze or process binary instructions
     *  First, it throws exceptions if the instruction cannot be supported or there is some logical error
     *  Second, it asks the scalar analysis to split into cases depending on the operation and operands.
     */
    private fun <R> applyBin(cmd: LocatedSbfInstruction,
                             applyAssign: (lhs: Value.Reg, rhs: Value) -> R,
                             applyBinWithImm: (lhs: Value.Reg) -> R,
                             applyBinWithReg: (lhs: Value.Reg, rhs: Value.Reg) -> R): R {
        val inst = cmd.inst
        if (inst !is SbfInstruction.Bin) {
            throw DDAError(msg = "$inst is not a binary instruction")
        }

        return when (inst.op) {
            BinOp.MOV -> {
                applyAssign(inst.dst, inst.v)
            } else -> {
                when (inst.v) {
                    is Value.Imm -> applyBinWithImm(inst.dst)
                    is Value.Reg -> applyBinWithReg(inst.dst, inst.v)
                }
            }
        }
    }


    /// === Analysis ===

    private fun analyzeMemTransfer(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        return applyMemTransfer(cmd,
            stackToStackF = {  dstStart, srcStart, len ->
                var outState = inState
                val dstRange = FiniteInterval.mkInterval(dstStart, len)
                for ((dstV, _) in inState.flowsTo.iterator()) {
                    if (dstV is StackSlotVariable) {
                        if (dstRange.includes(FiniteInterval.mkInterval(dstV.offset, dstV.getWidth().toLong()))) {
                            val adjOffset = dstStart - srcStart
                            val srcV = StackSlotVariable(offset = dstV.offset - adjOffset, width = dstV.getWidth(), vFac)
                            outState = outState.flows(dstV, srcV)
                        }
                    }
                }
                outState
            },
            nonStackToStackF = {  dstStart , len ->
                var outState = inState
                val dstRange = FiniteInterval.mkInterval(dstStart, len)
                for ((dstV, _) in inState.flowsTo.iterator()) {
                    if (dstV is StackSlotVariable) {
                        if (dstRange.includes(FiniteInterval.mkInterval(dstV.offset, dstV.getWidth().toLong()))) {
                            outState = outState.kill(dstV)
                        }
                    }
                }
                outState
            } ,
            stackToNonStackF = { srcStart, len ->
                var outState = inState
                val srcRange = FiniteInterval.mkInterval(srcStart, len)
                for ((srcV, _) in inState.flowsTo.iterator()) {
                    if (srcV is StackSlotVariable) {
                        if (srcRange.includes(FiniteInterval.mkInterval(srcV.offset, srcV.getWidth().toLong()))) {
                            outState = outState.kill(srcV)
                        }
                    }
                }
                outState
            },
            nonStackToNonStackF = { inState })
    }


    private fun analyzeMemset(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        return applyMemset(cmd,
            stackF = { start, len ->
                var outState = inState
                val fullRange = FiniteInterval.mkInterval(start, len)
                for ((v, _) in inState.flowsTo.iterator()) {
                    if (v is StackSlotVariable) {
                        if (fullRange.includes(FiniteInterval.mkInterval(v.offset, v.getWidth().toLong()))) {
                            outState = outState.kill(v)
                        }
                    }
                }
                outState
            },
            nonStackF = { inState })
    }

    private fun analyzeAssign(inState: DataDepsState, lhs: Value.Reg, rhs: Value): DataDepsState {
        val lhsV = RegisterVariable(lhs, vFac)
        return when (rhs) {
            is Value.Imm -> {
                inState.kill(lhsV)
            }
            is Value.Reg -> {
                if (rhs.r == SbfRegister.R10_STACK_POINTER) {
                    inState.kill(lhsV)
                } else {
                    val rhsV = RegisterVariable(rhs, vFac)
                    inState.flows(lhsV, rhsV)
                }
            }
        }
    }

    private fun analyzeBin(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        return applyBin(cmd,
            applyAssign = { lhs, rhs ->
                analyzeAssign(inState, lhs, rhs)
            },
            applyBinWithImm = { inState },
            applyBinWithReg = { lhs, rhs ->
                val lhsV = RegisterVariable(lhs, vFac)
                val rhsV = RegisterVariable(rhs, vFac)
                inState.flows(lhsV, rhsV, killFrom = false)
            }
        )
    }

    /** Only precise if accessing stack **/
    private fun analyzeMem(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        return applyMem(cmd,
            loadFromStack = { base, offset, width, lhs ->
                val lhsV = RegisterVariable(lhs, vFac)
                val baseV = StackSlotVariable(base + offset, width, vFac)
                inState.flows(lhsV, baseV)
            },
            storeToStack = { base, offset, width, value ->
                val baseV = StackSlotVariable(base + offset, width, vFac)
                when (value) {
                    is Value.Imm ->  inState.kill(baseV)
                    is Value.Reg -> {
                        val rhsV = RegisterVariable(value, vFac)
                        inState.flows(baseV, rhsV)
                    }
                }
            },
            loadFromNonStack = { lhs ->
                val lhsV = RegisterVariable(lhs, vFac)
                inState.kill(lhsV)
            },
            storeToNonStack = { inState }
        )
    }


    /** External calls are sources of data dependencies **/
    private fun applyDDASummaries(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        class DataDepSummaryVisitor(var state: DataDepsState): SummaryVisitor {
            override fun noSummaryFound(locInst: LocatedSbfInstruction) {}
            override fun processReturnArgument(locInst: LocatedSbfInstruction, type /*unused*/: MemSummaryArgumentType) {
                state = state.kill(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
            }
            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister,
                                         offset: Long,
                                         width: Byte,
                                         allocatedSpace: ULong,
                                         type: MemSummaryArgumentType) {
                val absType = registerTypes.typeAtInstruction(locInst, reg)
                // do nothing unless stack is mentioned in the summary
                if (absType is SbfType.PointerType.Stack) {
                    val baseOffset = absType.offset.toLongOrNull()
                    if (baseOffset != null) {
                        val baseV = StackSlotVariable(baseOffset + offset, width.toShort(), vFac)
                        state = state.kill(baseV)
                    }
                }
            }
        }

        val vis = DataDepSummaryVisitor(inState)
        memSummaries.visitSummary(cmd, vis)
        return vis.state
    }

    private fun analyzeSaveScratchRegisters(inState: DataDepsState, inst: SbfInstruction.Call): DataDepsState {
        val r6 = Value.Reg(SbfRegister.R6)
        val r7 = Value.Reg(SbfRegister.R7)
        val r8 = Value.Reg(SbfRegister.R8)
        val r9 = Value.Reg(SbfRegister.R9)
        val rhs6 = RegisterVariable(r6, vFac)
        val rhs7 = RegisterVariable(r7, vFac)
        val rhs8 = RegisterVariable(r8, vFac)
        val rhs9 = RegisterVariable(r9, vFac)
        val id = inst.metaData.getVal(SbfMeta.CALL_ID)
        return if (id != null) {
            val lhs6 = ScratchRegisterVariable(id, r6, vFac)
            val lhs7 = ScratchRegisterVariable(id, r7, vFac)
            val lhs8 = ScratchRegisterVariable(id, r8, vFac)
            val lhs9 = ScratchRegisterVariable(id, r9, vFac)
            inState.flows(lhs6, rhs6).flows(lhs7, rhs7).flows(lhs8, rhs8).flows(lhs9, rhs9)
        } else {
            inState.kill(rhs6).kill(rhs7).kill(rhs8).kill(rhs9)
        }
    }

    private fun analyzeRestoreScratchRegisters(inState: DataDepsState, inst: SbfInstruction.Call): DataDepsState {
        val r6 = Value.Reg(SbfRegister.R6)
        val r7 = Value.Reg(SbfRegister.R7)
        val r8 = Value.Reg(SbfRegister.R8)
        val r9 = Value.Reg(SbfRegister.R9)
        val lhs6 = RegisterVariable(r6, vFac)
        val lhs7 = RegisterVariable(r7, vFac)
        val lhs8 = RegisterVariable(r8, vFac)
        val lhs9 = RegisterVariable(r9, vFac)
        val id = inst.metaData.getVal(SbfMeta.CALL_ID)
        return if (id != null) {
            val rhs6 = ScratchRegisterVariable(id, r6, vFac)
            val rhs7 = ScratchRegisterVariable(id, r7, vFac)
            val rhs8 = ScratchRegisterVariable(id, r8, vFac)
            val rhs9 = ScratchRegisterVariable(id, r9, vFac)
            inState.flows(lhs6, rhs6).flows(lhs7, rhs7).flows(lhs8, rhs8).flows(lhs9, rhs9)
        } else {
            inState.kill(lhs6).kill(lhs7).kill(lhs8).kill(lhs9)
        }
    }

    private fun analyzeCall(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        val inst = cmd.inst
        check(inst is SbfInstruction.Call)

        val solFunction = SolanaFunction.from(inst.name)
        return if (solFunction != null) {
            when (solFunction) {
                SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMMOVE -> analyzeMemTransfer(inState, cmd)
                SolanaFunction.SOL_MEMSET -> analyzeMemset(inState, cmd)
                else -> inState
            }
        } else {
            val cvtFunction = CVTFunction.from(inst.name)
            if (cvtFunction != null) {
                when (cvtFunction) {
                    is CVTFunction.Core -> {
                        when(cvtFunction.value) {
                            CVTCore.ASSUME,
                            CVTCore.ASSERT,
                            CVTCore.SATISFY,
                            CVTCore.SANITY ->
                                inState
                            CVTCore.NONDET_ACCOUNT_INFO ->
                                applyDDASummaries(inState, cmd)
                            CVTCore.NONDET_SOLANA_ACCOUNT_SPACE, CVTCore.ALLOC_SLICE ->
                                applyDDASummaries(inState, cmd)
                            CVTCore.SAVE_SCRATCH_REGISTERS ->
                               analyzeSaveScratchRegisters(inState, inst)
                            CVTCore.RESTORE_SCRATCH_REGISTERS ->
                               analyzeRestoreScratchRegisters(inState, inst)
                        }
                    }
                    is CVTFunction.Nondet ->
                        inState.kill(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac))
                    // We ignore these for now
                    is CVTFunction.NativeInt,
                    is CVTFunction.U128Intrinsics,
                    is CVTFunction.Calltrace -> inState
                }
            } else {
                applyDDASummaries(inState, cmd)
            }
        }
    }

    private fun analyzeSelect(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        val inst = cmd.inst
        check(inst is SbfInstruction.Select)
        // We could be more precise by reasoning about select condition if needed.
        return analyzeAssign(inState, inst.dst, inst.trueVal).join(analyzeAssign(inState, inst.dst, inst.falseVal))
    }

    private fun analyzeCmd(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {

        val outState = when (val inst = cmd.inst) {
            is SbfInstruction.Assert,
            is SbfInstruction.Assume,
            is SbfInstruction.Jump.ConditionalJump,
            is SbfInstruction.Jump.UnconditionalJump,
            is SbfInstruction.Exit,
            is SbfInstruction.CallReg,
            is SbfInstruction.Un -> inState
            is SbfInstruction.Havoc -> inState.kill(RegisterVariable(inst.dst, vFac))
            is SbfInstruction.Bin -> analyzeBin(inState, cmd)
            is SbfInstruction.Mem -> analyzeMem(inState, cmd)
            is SbfInstruction.Call -> analyzeCall(inState, cmd)
            is SbfInstruction.Select -> analyzeSelect(inState, cmd)
        }
        return outState
    }

    override fun transformCmd(inState: DataDepsState, cmd: LocatedSbfInstruction): DataDepsState {
        return if (cmd == target) {
            when(targetExp) {
                is PtrExprErrReg -> {
                    val nextState = DataDepsState(inState.flowsTo.put(RegisterVariable(targetExp.reg, vFac), setOf(cmd)))
                    if (cmd.inst.writeRegister.contains(targetExp.reg)) {
                        analyzeCmd(nextState, cmd)
                    } else {
                        nextState
                    }
                }
                is PtrExprErrStackDeref -> {
                    val nextState = DataDepsState(inState.flowsTo.put(StackSlotVariable(targetExp.field.offset.v, targetExp.field.size, vFac) , setOf(cmd)))
                    analyzeCmd(nextState, cmd)
                }
            }
        } else {
            analyzeCmd(inState, cmd)
        }
    }

    /// === Post-processing (after fixpoint has been reached) ===

    private fun addSource(v: Variable, cmd: LocatedSbfInstruction, inState: DataDepsState) {
        if (inState.dependsOn(v, target)) {
            sources.add(cmd)
        }
    }
    private fun addDep(v: Variable, cmd: LocatedSbfInstruction, inState: DataDepsState) {
        if (inState.contains(v)) {
            deps.add(cmd)
        }
    }

    private fun addSource(reg: Value.Reg, cmd: LocatedSbfInstruction, inState: DataDepsState) =
        addSource(RegisterVariable(reg, vFac), cmd, inState)

    private fun addSource(offset: Long, width: Short, cmd: LocatedSbfInstruction, inState: DataDepsState) =
        addSource(StackSlotVariable(offset, width, vFac), cmd, inState)

    private fun addDep(reg: Value.Reg, cmd: LocatedSbfInstruction, inState: DataDepsState) =
        addDep(RegisterVariable(reg, vFac), cmd, inState)

    private fun addDep(offset: Long, width:Short, cmd: LocatedSbfInstruction, inState: DataDepsState) =
        addDep(StackSlotVariable(offset, width, vFac), cmd, inState)

    private fun processDDASummaries(inState: DataDepsState, cmd: LocatedSbfInstruction) {
        class ProcessDataDepSummaryVisitor: SummaryVisitor {
            override fun noSummaryFound(locInst: LocatedSbfInstruction) {}
            override fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType) {
                addSource(RegisterVariable(Value.Reg(SbfRegister.R0_RETURN_VALUE), vFac), locInst, inState)
            }
            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister, offset: Long, width: Byte,
                                         allocatedSpace: ULong, type: MemSummaryArgumentType) {
                val absType = registerTypes.typeAtInstruction(locInst, reg)
                if (absType is SbfType.PointerType.Stack) {
                    val baseOffset = absType.offset.toLongOrNull()
                    if (baseOffset != null) {
                        addSource(baseOffset + offset, width.toShort(), locInst, inState)
                    }
                }
            }
        }
        val vis = ProcessDataDepSummaryVisitor()
        memSummaries.visitSummary(cmd, vis)
    }

    private fun processSelect(inState: DataDepsState, cmd: LocatedSbfInstruction) {
        val inst = cmd.inst
        check(inst is SbfInstruction.Select)
        when (inst.trueVal) {
            is Value.Imm -> addSource(inst.dst, cmd, inState)
            is Value.Reg -> addDep(inst.dst, cmd, inState)
        }
        when (inst.falseVal) {
            is Value.Imm -> addSource(inst.dst, cmd, inState)
            is Value.Reg -> addDep(inst.dst, cmd, inState)
        }
    }

    private fun processBin(inState: DataDepsState, cmd: LocatedSbfInstruction) {
        applyBin(cmd,
            applyAssign = { lhs, rhs ->
                when (rhs) {
                    is Value.Imm -> addSource(lhs, cmd, inState)
                    is Value.Reg -> {
                        if (rhs.r == SbfRegister.R10_STACK_POINTER) {
                            // If `lhs := r10` then we don't propagate further, and we consider this assignment
                            // as a source.
                            addSource(lhs, cmd, inState)
                        } else {
                            addDep(lhs, cmd, inState)
                        }
                    }
                }
            },
            applyBinWithImm = { lhs -> addDep(lhs, cmd, inState) },
            applyBinWithReg = { lhs, _ -> addDep(lhs, cmd, inState) }
        )
    }

    private fun processMem(inState: DataDepsState, cmd: LocatedSbfInstruction) {
        applyMem(cmd,
            loadFromStack = {_, _, _, lhs -> addDep(lhs, cmd, inState) },
            storeToStack = {base, offset, width, value ->
                when (value) {
                    is Value.Imm -> addSource(base + offset, width, cmd, inState)
                    is Value.Reg -> addDep(base + offset, width, cmd, inState)
                }
            },
            loadFromNonStack = { lhs ->
                // OPPORTUNISTIC SOURCE: we cannot continue because the loaded value is from non-stack memory
                addSource(lhs, cmd, inState)
            },
            storeToNonStack = {}
        )
    }

    private fun processMemTransfer(inState: DataDepsState, outState: DataDepsState, cmd: LocatedSbfInstruction) {
        applyMemTransfer(cmd,
            stackToStackF = { _, _, _ ->
                if (inState != outState) {
                    deps.add(cmd)
                }
            },
            nonStackToStackF = { _, _ ->
                // OPPORTUNISTIC SOURCE: we cannot continue because the mempcy is from non-stack memory
                if (inState != outState) {
                    sources.add(cmd)
                }
            } ,
            stackToNonStackF = { _, _ -> },
            nonStackToNonStackF = {}
        )
    }

    private fun processMemset(inState: DataDepsState, outState: DataDepsState, cmd: LocatedSbfInstruction) {
        applyMemset(cmd,
            stackF = { _, _ ->
                if (inState != outState) {
                    sources.add(cmd)
                }
            },
            nonStackF = {}

        )
    }

    private fun processCall(inState: DataDepsState, outState: DataDepsState, cmd: LocatedSbfInstruction) {
        val inst = cmd.inst
        check(inst is SbfInstruction.Call)
        val solFunction = SolanaFunction.from(inst.name)
        if (solFunction != null) {
            when (solFunction) {
                SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMMOVE -> {
                    processMemTransfer(inState, outState, cmd)
                    return
                }
                SolanaFunction.SOL_MEMSET -> {
                    processMemset(inState, outState, cmd)
                    return
                }
                else -> { /* processed below */}
            }
        }

        if (CVTNondet.from(inst.name) != null) {
            addSource(Value.Reg(SbfRegister.R0_RETURN_VALUE), cmd, inState)
            return
        }

        processDDASummaries(inState, cmd)
    }

    /**
     * Populate [deps] and [sources] for external clients.
     *
     * We separate analysis from post-processing. However, currently they could have been done together during analysis phase
     * because the analysis is monotonic. That is, once some data dependency is added it cannot be removed.
    **/
    private fun processOutput() {
        deps.add(target)
        for (b in cfg.getBlocks().values) {
            for (locInst in b.getLocatedInstructions()) {
                if (locInst == target) { continue }
                val inState = cmdIn[locInst] ?: continue
                val outState = cmdOut[locInst] ?: continue
                when (val inst = locInst.inst) {
                    is SbfInstruction.Havoc -> addSource(inst.dst, locInst, inState)
                    is SbfInstruction.Bin -> processBin(inState, locInst)
                    is SbfInstruction.Mem -> processMem(inState, locInst)
                    is SbfInstruction.Call -> processCall(inState, outState, locInst)
                    is SbfInstruction.Select -> processSelect(inState, locInst)
                    else -> {}
                }
            }
        }
    }
}

/**
 * An instance of DataDepsState is attached to each program point `p`.
 * An entry `(k, v)` in the map `flowsTo` means that the variable `k` at `p` might data flow to a use at any of the instructions in `v`
 * Currently, `v` is always a singleton because `DataDependencyAnalysis` takes only a single target but the code should
 * work for multiple targets.
 **/
data class DataDepsState(val flowsTo: TreapMap<Variable, Set<LocatedSbfInstruction>>) {
    /** Lattice operations **/
    companion object {
        val bottom = DataDepsState(flowsTo = treapMapOf())
        val lattice = object : JoinLattice<DataDepsState> {
            override fun join(x: DataDepsState, y: DataDepsState): DataDepsState {
                return DataDepsState(x.flowsTo.union(y.flowsTo) { _, d1, d2 -> d1.plus(d2) })
            }
            override fun equiv(x: DataDepsState, y: DataDepsState) = x.flowsTo == y.flowsTo
        }
    }
    /** Transfer functions **/
    fun kill(v: Variable) = DataDepsState(flowsTo.remove(v))
    fun contains(x: Variable) = flowsTo.containsKey(x)
    /** return true if [x] may flow to [target] **/
    fun dependsOn(x: Variable, target: LocatedSbfInstruction) = flowsTo[x]?.contains(target) ?: false
    /** record that there is data flow from [from] to [to] **/
    fun flows(from: Variable, to: Variable, killFrom: Boolean = true): DataDepsState {
        val insts = flowsTo[from]
        return if (insts == null) {
            this
        } else {
            if (killFrom) {
                DataDepsState(flowsTo.remove(from).put(to, insts))
            } else {
                DataDepsState(flowsTo.put(to, insts))
            }
        }
    }

    override fun toString() = flowsTo.toString()
}
