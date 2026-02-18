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

import sbf.callgraph.SolanaFunction
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfCFG
import sbf.cfg.SbfInstruction
import sbf.cfg.Value
import sbf.disassembler.Label
import sbf.disassembler.SbfRegister
import sbf.domains.*
import datastructures.stdcollections.*
import log.*

private val logger = Logger(LoggerTypes.SBF_MOD_REF_ANALYSIS)
private fun dbg(msg: () -> Any) { logger.info(msg)}

/**
 * Represents a contiguous region of stack memory.
 *
 * @param offset The stack offset
 * @param size The size of the region in bytes
 */
data class StackSlice(val offset: Long, val size: Long) {
    fun toInterval() = FiniteInterval.mkInterval(offset, size)
}

enum class AccessType {
    READ,
    WRITE
}

/**
 * Configuration options for [ModRefAnalysis].
 */
data class ModRefAnalysisOptions(
    /**
     * Maximum number of blocks allowed between start and end instructions.
     */
    val maxRegionSize: ULong = ULong.MAX_VALUE,

    /**
     * If true, pointers with unknown provenance (top) are assumed to target non-stack memory.
     * This enables more aggressive optimizations but may be unsound if the assumption is violated.
     */
    val assumeTopPointersAreNonStack: Boolean = false
)

/**
 * Performs mod/ref (modification/reference) analysis on stack memory between two instructions.
 *
 * This analysis determines which stack locations may be read or written along any path between
 * [start] and [end] instructions. It handles:
 * - Direct stack loads/stores
 * - Memory intrinsics (memcpy, memset, etc.)
 * - External function calls
 *
 * @param cfg The control flow graph
 * @param start The starting instruction (inclusive)
 * @param end The ending instruction (inclusive)
 * @param types Type analysis results for determining stack pointer offsets
 * @param globalState Global analysis state containing function summaries
 **/
class ModRefAnalysis<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>>(
    val cfg: SbfCFG,
    val start: LocatedSbfInstruction,
    val end: LocatedSbfInstruction,
    val types: IRegisterTypes<TNum, TOffset>,
    val globalState: GlobalState,
    val options: ModRefAnalysisOptions = ModRefAnalysisOptions()
) {
    private val readSet = mutableListOf<StackSlice>()
    private val writeSet = mutableListOf<StackSlice>()
    private val analysisSuccess = runAnalysis()

    /**
     * Query whether a stack location [stackSlice] may be read or modified ([access])
     * between [start] and [end].
     *
     * @param stackSlice The stack region to query
     * @param access The type of access (READ or WRITE)
     * @return true if the region may be accessed, false if definitely not accessed
     **/
    fun mayAccess(stackSlice: StackSlice, access: AccessType): Boolean {
        if (!analysisSuccess) {
            logger.warn {
                "Mod/ref analysis is not precise enough for start=$start and end=$end." +
                "Any call to \"mayAccess\" will return conservatively true"
            }
            return true
        }

        val x = stackSlice.toInterval()
        return when (access) {
            AccessType.READ -> readSet
            AccessType.WRITE -> writeSet
        }.any {
            val y = it.toInterval()
            x.overlap(y)
        }
    }

    /**
     * Query whether the stack location accessed by a load or store instruction [locInst]
     * may be read or modified ([access]) between [start] and [end].
     *
     * @return true if the region may be accessed, false if definitely not accessed
     */
    fun mayAccess(locInst: LocatedSbfInstruction, access:AccessType): Boolean {
        val inst = locInst.inst

        if (inst !is SbfInstruction.Mem) {
            return false
        }

        val base = inst.access.base
        val offset = inst.access.offset.toLong()
        val size = inst.access.width.toLong()

        val stackSlices = getStackSlices(locInst, base, offset, size) ?: return true
        // if stackSlices is not a singleton then it means that the instruction might access
        // to more than one stack location or non-stack.
        // We give up and return true, but we could do better if needed.
        val stackSlice = stackSlices.singleOrNull() ?: return true

        return mayAccess(stackSlice, access)
    }

    private fun runAnalysis(): Boolean {
        // Compute relevant blocks (those that are both forward-reachable and backward-reachable)
        val blocks = cfg.computeRegion(start.label, end.label).map {
            checkNotNull(cfg.getBlock(it))
        }

        dbg { "Slice: $blocks"}

        val numOfBlocks = blocks.size.toULong()
        if (numOfBlocks > options.maxRegionSize) {
            logger.warn {
                    "Mod-ref analysis was skipped because the region between start and end " +
                    "contains $numOfBlocks blocks, exceeding the maximum supported size of " +
                    "${options.maxRegionSize}. Consider increasing the limit."
            }
            return false
        }

        for (block in blocks) {
            dbg { "${block.getLabel()}" }
            for (locInst in block.getLocatedInstructions()) {
                // `start` can be in the middle of a basic block.
                // We skip any instruction before `start`
                if (block.getLabel() == start.label && locInst.pos < start.pos) {
                    continue
                }

                // `end` can be in the middle of a basic block.
                // We skip any instruction after `end`
                if (block.getLabel() == end.label && locInst.pos > end.pos) {
                    break
                }

                val success = when (val inst = locInst.inst) {
                    is SbfInstruction.Mem -> {
                        val accesses = if (inst.isLoad) { readSet } else { writeSet }
                        processMemoryAccess(
                            locInst,
                            inst.access.base,
                            inst.access.offset.toLong(),
                            inst.access.width.toLong(),
                            accesses
                        )
                    }
                    is SbfInstruction.Call -> processCall(locInst)
                    else -> true // other instructions cannot affect stack
                }

                if (!success) {
                    dbg {
                        "\t\tThe mod/ref analysis got imprecise after ${locInst.inst}, " +
                            "so all queries will conservatively return true"
                    }
                    return false // the analysis is too imprecise
                }
            }
        }

        return true
    }


    /**
     * Return true if analysis can statically know which stack location/s (if any) is being accessed
     *
     * As a side effect, it adds information about the stack location/s into [accesses]
     **/
    private fun processMemoryAccess(
        locInst: LocatedSbfInstruction,
        base: Value.Reg,
        offset: Long,
        size: Long,
        accesses: MutableList<StackSlice>
    ): Boolean {
        val stackSlices = getStackSlices(locInst, base, offset, size) ?: return false
        // stackSlices can be empty if the access is not on the stack
        dbg { "\t\tAdded $stackSlices at ${locInst.inst}" }
        accesses.addAll(stackSlices)
        return true
    }

    /**
     * Uses type analysis to determine if the base register points to the stack and to resolve
     * the concrete offset being accessed.
     *
     * @param locInst The instruction being processed
     * @param base The base register for the memory access
     * @param offset The immediate offset added to the base
     * @param size The size of the access in bytes
     * @return (possibly empty) list of stack slices if analysis succeeded, null if too imprecise
     **/
    private fun getStackSlices(
        locInst: LocatedSbfInstruction,
        base: Value.Reg,
        offset: Long,
        size: Long
    ): List<StackSlice>? {
        val baseTy = types.typeAtInstruction(locInst, base)
        // if we don't have type information about base we bail out
        if (baseTy.isTop()) {
            return if (options.assumeTopPointersAreNonStack)  {
                // If optimistic flag then we will assume that the access can be on any memory region except stack
                listOf()
            } else {
                dbg { "\t\t\t getStackSlices bailed out because on type information for $base in ${locInst.inst}" }
                null
            }
        }

        // if it is not a stack pointer then we stop
        val stackPtrTy = (baseTy as? SbfType.PointerType.Stack)
            ?: return listOf()

        val symOffset = stackPtrTy.offset.add(offset)
        // We don't know which stack offsets are being accessed, so we bail out
        if (symOffset.isTop()) {
            dbg {"\t\t\t getStackSlices bailed out because unknown stack offset $stackPtrTy in ${locInst.inst}" }
            return null
        }
        return symOffset.toLongList().map { StackSlice(it, size) }
    }


    private fun processCall(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        return when (val solanaFunc = SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMCPY_ZEXT,
            SolanaFunction.SOL_MEMCPY_TRUNC,
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMSET ->
                processMemIntrinsics(locInst, solanaFunc)
            else ->
                processExternalCall(locInst)
        }
    }

    private fun processMemIntrinsics(
        locInst: LocatedSbfInstruction,
        solanaFunc: SolanaFunction
    ): Boolean {

        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        // If we don't know the length then we bail out
        val len = (types.typeAtInstruction(locInst, r3) as? SbfType.NumType)
            ?.value
            ?.toLongOrNull()
            ?: return false

        return when (solanaFunc) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMMOVE,
            SolanaFunction.SOL_MEMCPY_TRUNC,
            SolanaFunction.SOL_MEMCPY_ZEXT -> {
                processMemoryAccess(locInst, r1, 0, len, writeSet) &&
                processMemoryAccess(locInst, r2, 0, len, readSet)
            }
            SolanaFunction.SOL_MEMSET -> {
                processMemoryAccess(locInst, r1, 0, len, writeSet)
            }
            else -> error(false) // unreachable
        }
    }

    private fun processExternalCall(locInst: LocatedSbfInstruction): Boolean {
        val visitor = object : SummaryVisitor {
            var success: Boolean = true

            override fun noSummaryFound(
                @Suppress("UNUSED_PARAMETER") locInst: LocatedSbfInstruction
            ) {
                // do nothing
            }

            override fun processReturnArgument(
                @Suppress("UNUSED_PARAMETER") locInst: LocatedSbfInstruction,
                @Suppress("UNUSED_PARAMETER") type: MemSummaryArgumentType
            ) {
               // do nothing
            }

            override fun processArgument(
                locInst: LocatedSbfInstruction,
                reg: SbfRegister,
                offset: Long,
                width: Byte,
                @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                @Suppress("UNUSED_PARAMETER") type: MemSummaryArgumentType
            ) {
                success = processMemoryAccess(locInst, Value.Reg(reg), offset, width.toLong(), writeSet)
            }
        }

        globalState.memSummaries.visitSummary(locInst, visitor)
        return visitor.success
    }

}


/**
 * Computes the set of labels between two labels by finding blocks that are both
 * forward-reachable from [start] and backward-reachable from [end].
 *
 * @param start The starting label of the slice
 * @param end The ending label of the slice
 * @return Set of labels representing blocks in the slice
 **/
private fun SbfCFG.computeRegion(start: Label, end: Label): Set<Label> {
    val forwardReachable = reachableFrom(start)
    val backwardReachable = reachableTo(end)
    return forwardReachable.intersect(backwardReachable)
}

private fun SbfCFG.successors(label: Label): Set<Label> {
    val block = checkNotNull(getBlock(label))
    return block.getSuccs().map { it.getLabel()}.toSet()
}

private fun SbfCFG.predecessors(label: Label): Set<Label> {
    val block = checkNotNull(getBlock(label))
    return block.getPreds().map { it.getLabel()}.toSet()
}

private fun SbfCFG.reachableFrom(start: Label): Set<Label> {
    val reachable = mutableSetOf<Label>()
    val worklist = mutableListOf<Label>()
    worklist.add(start)
    while (worklist.isNotEmpty()) {
        val block = worklist.removeFirst()
        if (reachable.add(block)) {
            worklist.addAll(successors(block))
        }
    }
    return reachable
}

private fun SbfCFG.reachableTo(end: Label): Set<Label> {
    val reachable = mutableSetOf<Label>()
    val worklist = mutableListOf<Label>()
    worklist.add(end)
    while (worklist.isNotEmpty()) {
        val block = worklist.removeFirst()
        if (reachable.add(block)) {
            worklist.addAll(predecessors(block))
        }
    }
    return reachable
}
