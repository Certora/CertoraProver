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

package sbf.cfg

import allocator.Allocator
import sbf.analysis.AnalysisRegisterTypes
import sbf.callgraph.CVTCore
import sbf.callgraph.SolanaFunction
import sbf.disassembler.SbfRegister
import sbf.domains.*

enum class MemAccessRegion { STACK, NON_STACK, ANY }

/**
 * Extends [Deref] with additional information about the memory region where the access occurs.
 */
data class MemAccess(val reg: SbfRegister, val offset: Long, val width: Short, val region: MemAccessRegion) {
    fun overlap(other: FiniteInterval): Boolean {
        val x = FiniteInterval.mkInterval(offset, width.toLong())
        return x.overlap(other)
    }
}

/**
 * Maps the de-referenced location in [locInst] to a normalized [MemAccess].
 *
 * If the access targets the stack, the returned [MemAccess] is normalized
 * so that the base register is always `r10` (the stack pointer).
 */
fun <D, TNum, TOffset> normalizeLoadOrStore(
    locInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>
): MemAccess
   where TNum: INumValue<TNum>,
         TOffset: IOffset<TOffset>,
         D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val inst = locInst.inst
    check(inst is SbfInstruction.Mem){
        "normalizeMemInst expects a memory instruction instead of $inst"
    }

    return normalizedMemAccess(
        locInst,
        inst.access.baseReg,
        inst.access.offset.toLong(),
        inst.access.width,
        types
    )
}

/**
 * Maps the de-referenced source and destination pointers in [locInst] to a pair of normalized [MemAccess].
 * It can return null if the length argument cannot be statically determined.
 */
fun <D, TNum, TOffset> normalizeMemcpy(
    locInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>): Pair<MemAccess, MemAccess>?
where TNum: INumValue<TNum>,
      TOffset: IOffset<TOffset>,
      D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    val inst = locInst.inst
    check(inst is SbfInstruction.Call && SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {
        "normalizeMemcpy expects a memcpy instruction instead of $inst"
    }

    val r3 = SbfRegister.R3_ARG
    val len = types.typeAtInstruction(locInst, r3)
        .let { it as? SbfType.NumType }
        ?.value
        ?.toLongOrNull()
    if (len == null || len > Short.MAX_VALUE) {
        return null
    }

    val dstMemAccess = normalizedMemAccess(
        locInst,
        Value.Reg(SbfRegister.R1_ARG),
        0,
        len.toShort(),
        types
    )
    val srcMemAccess = normalizedMemAccess(
        locInst,
        Value.Reg(SbfRegister.R2_ARG),
        0,
        len.toShort(),
        types
    )
    return srcMemAccess to dstMemAccess
}


private fun <D, TNum, TOffset> normalizedMemAccess(
    locInst: LocatedSbfInstruction,
    baseReg: Value.Reg,
    offset: Long,
    width: Short,
    types: AnalysisRegisterTypes<D, TNum, TOffset>
): MemAccess
    where TNum : INumValue<TNum>,
          TOffset : IOffset<TOffset>,
          D : AbstractDomain<D>,
          D : ScalarValueProvider<TNum, TOffset>
{
    val normalizedOffset = normalizeStackAccess(locInst, baseReg, offset, types)
    if (normalizedOffset != null) {
        return MemAccess(
            SbfRegister.R10_STACK_POINTER,
            normalizedOffset,
            width,
            MemAccessRegion.STACK
        )
    }

    val regType = types.typeAtInstruction(locInst, baseReg.r)
    val region = if (regType is SbfType.PointerType && regType !is SbfType.PointerType.Stack) {
        MemAccessRegion.NON_STACK
    } else {
        MemAccessRegion.ANY
    }

    return MemAccess(baseReg.r, offset, width, region)
}

/**
 * Emit the SBF code to call `memcpy` where
 * - `r1` points to `srcReg+srcStart`,
 * - `r2` points to `dstReg+dstStart, and
 * - `r3` contains size
 **/
fun emitMemcpy(
    srcReg: SbfRegister,
    srcStart: Long,
    dstReg: SbfRegister,
    dstStart: Long,
    size: ULong,
    metadata: MetaData
) =
    emitMemIntrinsics(
        SolanaFunction.SOL_MEMCPY,
        Value.Reg(dstReg),
        dstStart,
        Value.Reg(srcReg),
        srcStart,
        size,
        metadata,
        SbfMeta.MEMCPY_PROMOTION
    )

/**
 * Memory intrinsics (memcpy/memmove/memcmp/memset) expect inputs in registers r1,r2,r3.
 * Before we modify these registers we need to save them.
 * The only mechanism we have to save registers without overwritten registers used later by the program is
 * to call special function `CVT_save_scratch_registers` so that scratch registers can be saved, and then
 * they can be used to save r1,r2, and r3.
 * We emit code that allows us to do that:
 * ```
 *   CVT_save_scratch_registers
 *   r6 := r1
 *   r7 := r2
 *   r8 := r3
 *   r1 := [op1]
 *   r1 := r1 + [op1Start]
 *   r2 := [op2]
 *   r2 := r2 + [op2Start] (optional if srcStart != null)
 *   r3 := [size]
 *   call to intrinsics
 *   r1 := r6
 *   r2 := r7
 *   r3 := r8
 *   CVT_restore_scratch_registers
 * ```
 **/
private fun emitMemIntrinsics(
    intrinsics: SolanaFunction,
    op1: Value.Reg,
    op1Start: Long,
    op2: Value,
    // if null then intrinsics is MEMSET
    op2Start: Long?,
    size: ULong,
    metadata: MetaData,
    metakey: MetaKey<String>
): List<SbfInstruction> {

    val isMemset = intrinsics == SolanaFunction.SOL_MEMSET
    check(
            intrinsics == SolanaFunction.SOL_MEMCPY ||
            intrinsics == SolanaFunction.SOL_MEMCMP ||
            intrinsics == SolanaFunction.SOL_MEMMOVE ||
            isMemset
    )
    check(op2Start != null || isMemset) {
        "emitMemIntrinsics expects op2Start to be null because the intrinsics is memset"
    }
    check(isMemset || op2 is Value.Reg) {
        "emitMemIntrinsics expects $op2 to be a register because the intrinsics is not a memset"
    }
    check(isMemset || (op1.r == SbfRegister.R10_STACK_POINTER || (op2 as Value.Reg).r == SbfRegister.R10_STACK_POINTER)) {
        "emitMemIntrinsics expects $op1 or $op2 to be r10"
    }
    check(!isMemset || op1.r == SbfRegister.R10_STACK_POINTER) {
        "emitMemIntrinsics expects $op1 to be r10"
    }

    val emittedInsts = mutableListOf<SbfInstruction>()

    val r1 = Value.Reg(SbfRegister.R1_ARG)
    val r2 = Value.Reg(SbfRegister.R2_ARG)
    val r3 = Value.Reg(SbfRegister.R3_ARG)
    val r6 = Value.Reg(SbfRegister.R6)
    val r7 = Value.Reg(SbfRegister.R7)
    val r8 = Value.Reg(SbfRegister.R8)
    val r9 = Value.Reg(SbfRegister.R9)

    val scratchRegs = arrayListOf(r6,r7,r8,r9)
    if (op2 is Value.Reg && op2.r !=  SbfRegister.R10_STACK_POINTER) {
        scratchRegs.remove(op2)
    }
    if (op1.r !=  SbfRegister.R10_STACK_POINTER) {
        scratchRegs.remove(op1)
    }

    check(scratchRegs.size >= 3) {
        "emitMemIntrinsics needs three scratch registers but it only has $scratchRegs"
    }

    val temp1 = scratchRegs[0]
    val temp2 = scratchRegs[1]
    val temp3 = scratchRegs[2]

    val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
    check(callId >= 0) {"expected non-negative call id"}

    val newMetadata = metadata.plus(SbfMeta.CALL_ID to callId.toULong()).plus(metakey to "")

    emittedInsts.add(SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = newMetadata))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp1, r1, true))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp2, r2, true))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, temp3, r3, true))

    val rename = fun(reg: Value.Reg): Value.Reg {
        return when (reg) {
            r1 -> temp1
            r2 -> temp2
            r3 -> temp3
            else -> reg
        }
    }

    if (r1 != op1) {
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r1, rename(op1), true))
    }
    emittedInsts.add(SbfInstruction.Bin(BinOp.ADD, r1, Value.Imm(op1Start.toULong()), true, metaData = MetaData(metakey to "")))

    when(op2) {
        is Value.Reg -> {
            if (r2 != op2) {
                emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r2, rename(op2), true))
            }
        }
        is Value.Imm -> {
            emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r2, op2, true))
        }
    }

    if (op2Start != null) {
        emittedInsts.add(SbfInstruction.Bin(BinOp.ADD, r2, Value.Imm(op2Start.toULong()), true, metaData = MetaData(metakey to "")))
    }
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r3, Value.Imm(size), true))
    emittedInsts.add(SolanaFunction.toCallInst(intrinsics, metadata.plus(metakey to "")))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r1, temp1, true))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r2, temp2, true))
    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, r3, temp3, true))
    emittedInsts.add(SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = newMetadata))
    return emittedInsts
}
