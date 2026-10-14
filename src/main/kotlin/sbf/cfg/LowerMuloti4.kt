/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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
import sbf.callgraph.CVTCore
import sbf.callgraph.CompilerRtFunction
import sbf.callgraph.IntegerU128CompilerRtFunction
import sbf.disassembler.GlobalVariables
import sbf.disassembler.SbfRegister
import sbf.sbfLogger
import datastructures.stdcollections.*

/**
 * `__muloti4` is the only compiler-rt function we model whose arguments do not fit in the five SBF
 * argument registers:
 * ```
 * ti_int __muloti4(ti_int a, ti_int b, int *overflow);
 * ```
 * Its call sites pass the 16-byte result buffer in `r1`, the two halves of `a` in `r2`/`r3`, the low
 * half of `b` in `r4`, and their own frame pointer in `r5`. The two arguments that did not fit in
 * registers are spilled to the caller's frame, where the callee reads them from:
 * ```
 *  *(u64 *)(r5 + B_HIGH_OFFSET)        high half of `b`
 *  *(u64 *)(r5 + OVERFLOW_PTR_OFFSET)  the `overflow` pointer
 * ```
 * A memory summary can only describe `(*W)(ri+O)`, so it can express neither a stack-passed input
 * nor the write to `**(r5 + OVERFLOW_PTR_OFFSET)`. This pass performs those two accesses in the
 * caller instead, leaving a call that follows the ordinary convention: `r4`/`r5` are the halves of
 * `b` just like for `__multi3`, and the overflow flag is returned in `r0`.
 * ```
 *   call CVT_save_scratch_registers
 *   r6 = *(u64 *)(r5 + OVERFLOW_PTR_OFFSET)
 *   r5 = *(u64 *)(r5 + B_HIGH_OFFSET)
 *   call __muloti4
 *   *(u64 *)(r6 + 0) = r0
 *   call CVT_restore_scratch_registers
 * ```
 * `r6` has to be saved because the ABI requires `__muloti4`'s caller to preserve it, while `r5` is
 * free to overwrite because the callee is allowed to clobber it.
 *
 * The callee stores the flag with a 4-byte write, but its call sites zero the whole 8-byte slot
 * beforehand and read all 8 bytes back, so writing 8 bytes of a 0/1 flag leaves the same memory
 * state while keeping every access to the slot the same width.
 *
 * The rewritten call carries [SbfMeta.MULOTI4_LOWERED]; the TAC summarizer refuses to model a call
 * without it, since silently dropping the overflow flag would be unsound.
 **/
fun lowerMuloti4Calls(cfg: MutableSbfCFG, globals: GlobalVariables) {
    val r0 = Value.Reg(SbfRegister.R0)
    val r5 = Value.Reg(SbfRegister.R5)
    val r6 = Value.Reg(SbfRegister.R6)
    for (bb in cfg.getMutableBlocks().values) {
        val remap = bb.getLocatedInstructions().mapNotNull { locInst ->
            val call = locInst.inst as? SbfInstruction.Call ?: return@mapNotNull null
            if (CompilerRtFunction.from(call.name) != MULOTI4 ||
                call.metaData.getVal(SbfMeta.MULOTI4_LOWERED) != null) {
                return@mapNotNull null
            }
            if (globals.elf.useDynamicFrames()) {
                // Only the static-frame ABI is known to spill at the offsets used below
                sbfLogger.warn { "Cannot lower ${call.name} at $locInst: spilled arguments of dynamic frames" }
                return@mapNotNull null
            }
            val scratch = MetaData(SbfMeta.CALL_ID to Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC).toULong())
            locInst to listOf(
                SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = scratch),
                SbfInstruction.Mem(Deref(8, r5, OVERFLOW_PTR_OFFSET), r6, isLoad = true),
                SbfInstruction.Mem(Deref(8, r5, B_HIGH_OFFSET), r5, isLoad = true),
                call.copyInst(call.metaData.plus(SbfMeta.MULOTI4_LOWERED())),
                SbfInstruction.Mem(Deref(8, r6, 0), r0, isLoad = false),
                SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = scratch)
            )
        }.toMap()
        if (remap.isNotEmpty()) {
            bb.replaceInstructions(remap)
        }
    }
}

private val MULOTI4 = CompilerRtFunction.IntegerU128(IntegerU128CompilerRtFunction.MULOTI4)

/** Offset, relative to `r5`, of the high half of `__muloti4`'s second operand **/
private val B_HIGH_OFFSET = spilledArgOffset(0)

/** Offset, relative to `r5`, of `__muloti4`'s pointer to the overflow flag **/
private val OVERFLOW_PTR_OFFSET = spilledArgOffset(8)

/**
 * Arguments that do not fit in `r1`-`r5` are spilled to the bottom of the caller's frame, which the
 * callee addresses through the frame pointer it receives in `r5`.
 */
private fun spilledArgOffset(index: Long): Short {
    val offset = -SBF_STACK_FRAME_SIZE + index
    check(offset in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) {
        "A stack frame of $SBF_STACK_FRAME_SIZE bytes is too large to address a spilled argument"
    }
    return offset.toShort()
}
