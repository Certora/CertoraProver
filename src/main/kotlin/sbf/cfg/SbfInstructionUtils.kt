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
import datastructures.stdcollections.*

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
 *
 * - `r1` points to [srcReg]+[srcOffset],
 * - `r2` points to [dstReg]+[dstOffset], and
 * - `r3` contains [len]
 *
 * @return list of instructions if enough scratch registers, otherwise null
 **/
fun emitMemcpy(
    srcReg: SbfRegister,
    srcOffset: Long,
    dstReg: SbfRegister,
    dstOffset: Long,
    len: ULong,
    metadata: MetaData
) =
    emitMemIntrinsics(
        SolanaFunction.SOL_MEMCPY,
        actualParams = listOf(
            PtrParam(Value.Reg(dstReg), dstOffset),
            PtrParam(Value.Reg(srcReg), srcOffset),
            ValParam(Value.Imm(len))
        ),
        formalRegs = listOf(
            Value.Reg(SbfRegister.R1_ARG),
            Value.Reg(SbfRegister.R2_ARG),
            Value.Reg(SbfRegister.R3_ARG)
        ),
        metadata,
        intrinsicsMetadata = SbfMeta.MEMCPY_PROMOTION to ""
    )

/**
 * Emit the SBF code to call `memcpy_zext` where
 *
 * - `r1` points to [srcReg]+[srcOffset],
 * - `r2` points to [dstReg]+[dstOffset], and
 * - `r3` contains [i]
 *
 * @return list of instructions if enough scratch registers, otherwise null
 **/
fun emitMemcpyZExt(
    srcReg: SbfRegister,
    srcOffset: Long,
    dstReg: SbfRegister,
    dstOffset: Long,
    i: ULong,
    metadata: MetaData
) =
    emitMemIntrinsics(
        SolanaFunction.SOL_MEMCPY_ZEXT,
        actualParams = listOf(
            PtrParam(Value.Reg(dstReg), dstOffset),
            PtrParam(Value.Reg(srcReg), srcOffset),
            ValParam(Value.Imm(i))
        ),
        formalRegs = listOf(
            Value.Reg(SbfRegister.R1_ARG),
            Value.Reg(SbfRegister.R2_ARG),
            Value.Reg(SbfRegister.R3_ARG)
        ),
        metadata,
        intrinsicsMetadata = SbfMeta.MEMCPY_ZEXT_PROMOTION to ""
    )

/**
 * Emit the SBF code to call `memcpy_trunc` where
 *
 * - `r1` points to [srcReg]+[srcOffset],
 * - `r2` points to [dstReg]+[dstOffset], and
 * - `r3` contains [i]
 *
 * @return list of instructions if enough scratch registers, otherwise null
 **/
fun emitMemcpyTrunc(
    srcReg: SbfRegister,
    srcOffset: Long,
    dstReg: SbfRegister,
    dstOffset: Long,
    i: ULong,
    metadata: MetaData
) =
    emitMemIntrinsics(
        SolanaFunction.SOL_MEMCPY_TRUNC,
        actualParams = listOf(
            PtrParam(Value.Reg(dstReg), dstOffset),
            PtrParam(Value.Reg(srcReg), srcOffset),
            ValParam(Value.Imm(i))
        ),
        formalRegs = listOf(
            Value.Reg(SbfRegister.R1_ARG),
            Value.Reg(SbfRegister.R2_ARG),
            Value.Reg(SbfRegister.R3_ARG)
        ),
        metadata,
        intrinsicsMetadata = SbfMeta.MEMCPY_TRUNC_PROMOTION to ""
    )


/**
 * Emit the SBF code to call `memset` where
 * - `r1` points to [ptr]+[offset]
 * - `r2` is [value]
 * - `r3` is [len]
 *
 * @return list of instructions if enough scratch registers, otherwise null
 **/
@Suppress("unused")
fun emitMemset(
    ptr: SbfRegister,
    offset: Long,
    value: Value,
    len: ULong,
    metadata: MetaData
) = emitMemIntrinsics(
        SolanaFunction.SOL_MEMSET,
        actualParams = listOf(
            PtrParam(Value.Reg(ptr), offset),
            ValParam(value),
            ValParam(Value.Imm(len))
        ),
        formalRegs = listOf(
            Value.Reg(SbfRegister.R1_ARG),
            Value.Reg(SbfRegister.R2_ARG),
            Value.Reg(SbfRegister.R3_ARG)
        ),
        metadata,
        intrinsicsMetadata = SbfMeta.MEMSET_PROMOTION to ""
    )

private sealed class MemIntrParam
private data class PtrParam(val base: Value.Reg, val offset: Long): MemIntrParam()
private data class ValParam(val value: Value): MemIntrParam()


/**
 * Memory intrinsics (`memcpy`/`memcpy_zext`/`memcpy_trunc`/`memmove`/`memcmp`/`memset`)
 * expect inputs in registers r1,r2,r3,r4. Before we modify these registers we need to save them.
 * The only mechanism we have to save registers without overwritten registers used later by the program is
 * to call special function `CVT_save_scratch_registers` so that scratch registers can be saved, and then
 * they can be used to save r1,r2,r3,r4.
 * We emit code that allows us to do that:
 * ```
 *   CVT_save_scratch_registers
 *   r6 := r1
 *   r7 := r2
 *   r8 := r3
 *   r1 := op1
 *   r1 := r1 + offset1
 *   r2 := op2
 *   r2 := r2 + offset2 (optional if offset2 != null)
 *   r3 := len
 *   call to intrinsics
 *   r1 := r6
 *   r2 := r7
 *   r3 := r8
 *   CVT_restore_scratch_registers
 * ```
 * @return list of instructions if enough scrath registers, otherwise null
 **/
private fun<T> emitMemIntrinsics(
    intrinsics: SolanaFunction,
    actualParams: List<MemIntrParam>,
    formalRegs: List<Value.Reg>,
    metadata: MetaData,
    intrinsicsMetadata: Pair<MetaKey<T>, T>
): List<SbfInstruction>? {
    check(actualParams.size == formalRegs.size)
    check(actualParams.size in 3..4)

    val scratchRegs = mutableListOf(
        Value.Reg(SbfRegister.R6),
        Value.Reg(SbfRegister.R7),
        Value.Reg(SbfRegister.R8),
        Value.Reg(SbfRegister.R9)
    )

    // Remove available scratch registers if any of the actual parameters is a scratch register
    for (param in actualParams.filterIsInstance<PtrParam>()) {
        scratchRegs.remove(param.base)
    }


    if (scratchRegs.size < actualParams.size) {
        //sbf.sbfLogger.warn{ "Not enough scratch registers. Needed ${actualParams.size}, but got ${scratchRegs.size}"}
        return null
    }

    val tempRegs = scratchRegs.take(actualParams.size)

    val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
    check(callId >= 0) {"expected non-negative call id"}
    val callIdMetadata = SbfMeta.CALL_ID to callId.toULong()
    val saveAndRestoreMetadata = metadata.plus(callIdMetadata).plus(intrinsicsMetadata)

    val renamingFn = fun(reg: Value.Reg): Value.Reg {
        val index = formalRegs.indexOf(reg)
        return if (index != -1) { tempRegs[index] } else { reg }
    }

    val emittedInsts = mutableListOf<SbfInstruction>()
    emittedInsts.add(SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = saveAndRestoreMetadata))

    for (i in formalRegs.indices) {
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, tempRegs[i], formalRegs[i], true))
    }

    for ((param, argRegs) in actualParams.zip(formalRegs)) {
        when(param) {
            is PtrParam -> {
                if (param.base != argRegs) {
                    emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, argRegs, renamingFn(param.base), true))
                }
                emittedInsts.add(SbfInstruction.Bin(BinOp.ADD, argRegs, Value.Imm(param.offset.toULong()), true, metaData = MetaData(intrinsicsMetadata)))
            }
            is ValParam -> {
                emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, argRegs, param.value, true))
            }
        }
    }

    emittedInsts.add(SolanaFunction.toCallInst(intrinsics, metadata.plus(intrinsicsMetadata)))
    for (i in formalRegs.indices) {
        emittedInsts.add(SbfInstruction.Bin(BinOp.MOV, formalRegs[i], tempRegs[i], true))
    }

    emittedInsts.add(SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = saveAndRestoreMetadata))
    return emittedInsts
}
