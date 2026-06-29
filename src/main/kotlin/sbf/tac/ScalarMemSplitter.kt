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

package sbf.tac

import sbf.SolanaConfig
import sbf.analysis.IRegisterTypes
import sbf.callgraph.SolanaFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.domains.*
import tac.Tag
import vc.data.TACExpr
import vc.data.TACSymbol
import datastructures.stdcollections.*

/**
 * Region-based memory splitter driven by the scalar domain.
 *
 * Four "regions": stack, heap, input, globals. Stack is encoded with TAC
 * scalars (one per byte offset, as in [PTAMemSplitter]). The other three each get one
 * ByteMap variable.
 *
 * For an access `*(reg + off)` at `locInst`:
 *  - if the scalar analysis pins reg's region, use that map directly (or scalars for stack);
 *  - if the scalar analysis says NonStack, encode the operand as an ite over
 *    {heap, input, globals}, guarded by SBF address-range predicates on reg.
 *
 * Strictly coarser than [PTAMemSplitter]: one ByteMap per region instead of one per
 * pointer-analysis cell. Used when PTA results are unavailable.
 *
 * The TAC encoding for non-stack memory regions (which are encoded by TAC ByteMaps) when the scalar analysis
 * does not know the region of `reg + off` is done as follows:
 *
 * Let `gi` be `loc in dom(Mi)`
 *
 * - `lhs = load(loc)`:
 *
 *  ```
 *  for each i \in 1..3:
 *      v_i := ByteLoad(Mi, loc)
 *  lhs := ite(g1, v_1, ite(g2, v_2, v_3))
 *  ```
 * - `store(loc, val)`:
 *
 *  ```
 *   for each i \in 1..3:
 *      old_i  := ByteLoad(Mi, loc)
 *      new_i  := ite(gi, value, old_i)
 *      Mi     := ByteStore(Mi, loc, new_i)
 *  ```
 * - for `memset` and `memcpy` with non-stack destination:
 *
 *  ```
 *  byteMap   := ite(g1, M1, ite(g2, M2, M3))
 *  // do operation using byteMap
 *  for each i \in 1..3:
 *      Mi := ite(gi, byteMap, Mi)
 *  ```
 */
class ScalarMemSplitter<TNum, TOffset, TFlags>(
    @Suppress("unused") private val cfg: SbfCFG,
    private val vFac: TACVariableFactory<TFlags>,
    private val sbfTacB: SbfTACBuilder,
    private val scalarTypes: IRegisterTypes<TNum, TOffset>,
    private val memSummaries: MemorySummaries,
    /**
     * If true, all non-stack regions share a single ByteMap (`region_nonstack`) and there is no
     * ite-based region dispatch. Trades model-level region non-aliasing for a TAC encoding the
     * bytemap inliner/scalarizer can optimize.
     */
    private val useSingleNonStackMap: Boolean = false,
) : TACMemSplitter
where TNum : INumValue<TNum>,
      TOffset : IOffset<TOffset>,
      TFlags : IPTANodeFlags<TFlags> {

    // Per-region maps, used when [useSingleNonStackMap] is false.
    private val heapMap     = vFac.getByteMapVar("region_heap")
    private val inputMap    = vFac.getByteMapVar("region_input")
    private val globalsMap  = vFac.getByteMapVar("region_globals")
    // Single non-stack map, used when [useSingleNonStackMap] is true.
    private val nonstackMap = vFac.getByteMapVar("region_nonstack")

    override fun getTACMemory(locInst: LocatedSbfInstruction): TACMemSplitter.LoadOrStoreInfo? {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem) {"precondition of getTACMemory fails"}
        val baseReg = inst.access.base
        val offset = inst.access.offset
        return when (val t = scalarTypes.typeAtInstruction(locInst, baseReg.r)) {
            is SbfType.Bottom            -> null
            is SbfType.PointerType.Stack -> buildStackInfo(locInst, inst, t)
            is SbfType.NumType ->
                throw TACTranslationError("TACScalarMemSplitter: base ${baseReg.r} is numeric at $locInst")
            else -> {
                val target = nonStackTarget(t, baseReg, offset.toLong())
                    ?: throw TACTranslationError("TACScalarMemSplitter: base ${baseReg.r} has unknown type at $locInst")
                TACMemSplitter.NonStackLoadOrStoreInfo(target, TACMemSplitter.HavocMapBytes(listOf()))
            }
        }
    }

    /**
     * Translate Solana memory intrinsics (`memcpy`, `memcpy_zext`, `memcpy_trunc`, `memcmp`, `memset`) to TAC.
     * Mirrors the branching of [PTAMemSplitter], but uses
     * the scalar domain for type information and region maps for non-stack operands.
     */
    override fun getTACMemoryFromMemIntrinsic(locInst: LocatedSbfInstruction): TACMemSplitter.MemInstrinsicsInfo {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        return when (SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY,
            SolanaFunction.SOL_MEMCPY_TRUNC -> processMemcpy(locInst)
            SolanaFunction.SOL_MEMCPY_ZEXT  -> processMemcpyZExt(locInst)
            SolanaFunction.SOL_MEMCMP       -> processMemcmp(locInst)
            SolanaFunction.SOL_MEMSET       -> processMemset(locInst)
            else                            -> TACMemSplitter.NotImplMemInstInfo
        }
    }

    /**
     * Build [TACMemSplitter.SummaryArgInfo] entries for each **modified** field declared by the user-provided
     * summary of [locInst].
     *
     * It mirrors [PTAMemSplitter] but using only information from the scalar domain.
     */
    override fun getTACMemoryFromSummary(locInst: LocatedSbfInstruction): List<TACMemSplitter.SummaryArgInfo>? {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"precondition of getTACMemoryFromSummary fails"}
        val summary = memSummaries.getSummary(inst.name) ?: return null

        return summary.args.mapNotNull { arg ->
            // Skip the R0 return-type descriptor (r=R0, width=0, offset=0): describes r0's type, not a memory write.
            if (arg.r == SbfRegister.R0 && arg.width.toInt() == 0 && arg.offset == 0L) {
                return@mapNotNull null
            }

            // Since we care about modified fields, `arg` will be of the form `*(r+offset)`
            // Thus, we ask the scalar analysis the type of `r` to know the memory region.
            // If `r` is r0 then it can be overwritten, thus we need to get its type **after** the execution of the instruction
            val argReg = Value.Reg(arg.r)
            val argOffset = arg.offset
            val regType = scalarTypes.typeAtInstruction(locInst, arg.r, Value.Reg(arg.r) in inst.writeRegister)
            val variable: TACMemSplitter.SummaryArgVariable = when (regType) {
                is SbfType.PointerType.Stack<TNum, TOffset> -> {
                    val regOffset = regType.offset.toLongOrNull()
                        ?: throw TACTranslationError(
                            "ScalarMemSplitter: ${arg.r} points to the stack at unknown offset at $locInst"
                        )
                    TACMemSplitter.SummaryArgVariable.Stack(vFac.getByteStackVar(PTAOffset(regOffset + arg.offset)))
                }
                is SbfType.NumType -> throw TACTranslationError(
                    "ScalarMemSplitter: $summary -- $arg is numeric at summarized call $locInst"
                )
                is SbfType.Bottom -> return@mapNotNull null
                else -> {
                    val target = nonStackTarget(regType, argReg, argOffset)
                        ?: throw TACTranslationError(
                            "ScalarMemSplitter: ${arg.r} has unknown type at summarized call $locInst"
                        )
                    TACMemSplitter.SummaryArgVariable.NonStack(target)
                }
            }

            TACMemSplitter.SummaryArgInfo(
                arg.r,
                PTAOffset(arg.offset),
                arg.width,
                arg.allocatedSpace,
                arg.type,
                variable
            )
        }
    }

    /**
     * Dispatch over the non-stack region maps based on the runtime address held in [base] + [offset].
     */
    private fun nonStackIteTarget(base: Value.Reg, offset: Long): TACMemSplitter.ByteMapTarget.Ite {
        val r = vFac.getRegisterVar(base.r.value.toInt()).asSym()
        val loc = sbfTacB { r add sbfTacB.mkConst(offset) }
        // Branches together cover the entire non-stack address space; the last branch is the
        // fallthrough (its guard is dropped when the dispatch is lowered into an ite expression).
        val branches = listOf(
            TACMemSplitter.ByteMapTarget.Ite.Branch(inRange(loc, null, SBF_HEAP_START),            globalsMap),
            TACMemSplitter.ByteMapTarget.Ite.Branch(inRange(loc, SBF_HEAP_START, SBF_INPUT_START), heapMap),
            TACMemSplitter.ByteMapTarget.Ite.Branch(inRange(loc, SBF_INPUT_START, null),           inputMap),
        )
        return TACMemSplitter.ByteMapTarget.Ite(branches)
    }

    /**
     * Compute a [TACMemSplitter.ByteMapTarget] for a register that is *not* a stack pointer.
     * Returns null if the type provides no usable region information (Bottom, NumType, or Top without
     * `optimisticScalarAnalysis`).
     */
    private fun nonStackTarget(
        type: SbfType<TNum, TOffset>,
        base: Value.Reg,
        offset: Long
    ): TACMemSplitter.ByteMapTarget? =
        if (useSingleNonStackMap) {
            nonStackTargetSingle(type)
        } else {
            nonStackTargetPerRegion(type, base, offset)
        }

    /** Per-region routing: pinned types pick their map; non-stack/top fall back to the ite dispatch. */
    private fun nonStackTargetPerRegion(
        type: SbfType<TNum, TOffset>,
        base: Value.Reg,
        offset: Long
    ): TACMemSplitter.ByteMapTarget? =
        when (type) {
            is SbfType.PointerType.Heap   -> TACMemSplitter.ByteMapTarget.Base(heapMap)
            is SbfType.PointerType.Input  -> TACMemSplitter.ByteMapTarget.Base(inputMap)
            is SbfType.PointerType.Global -> TACMemSplitter.ByteMapTarget.Base(globalsMap)
            is SbfType.NonStack           -> nonStackIteTarget(base, offset)
            is SbfType.Top                -> if (SolanaConfig.optimisticScalarAnalysis()) {
                nonStackIteTarget(base, offset)
            } else {
                null
            }
            else -> null
        }

    /** Single-map routing: every non-stack pointer (typed or not) funnels through [nonstackMap]. */
    private fun nonStackTargetSingle(type: SbfType<TNum, TOffset>): TACMemSplitter.ByteMapTarget? =
        when (type) {
            is SbfType.PointerType.Heap,
            is SbfType.PointerType.Input,
            is SbfType.PointerType.Global,
            is SbfType.NonStack -> TACMemSplitter.ByteMapTarget.Base(nonstackMap)
            is SbfType.Top -> if (SolanaConfig.optimisticScalarAnalysis()) {
                TACMemSplitter.ByteMapTarget.Base(nonstackMap)
            } else {
                null
            }
            else -> null
        }

    private fun lengthFromR3(locInst: LocatedSbfInstruction): Long? =
        (scalarTypes.typeAtInstruction(locInst, SbfRegister.R3) as? SbfType.NumType)
            ?.value?.toLongOrNull()

    private fun r10OffsetAt(locInst: LocatedSbfInstruction): Long? =
        (scalarTypes.typeAtInstruction(locInst, SbfRegister.R10) as? SbfType.PointerType.Stack)
            ?.offset?.toLongOrNull()

    /**
     * Translate a `memcpy(dst=R1, src=R2, len=R3)` call. Mirrors [PTAMemSplitter.processMemcpy].
     */
    private fun processMemcpy(locInst: LocatedSbfInstruction): TACMemSplitter.MemTransferInfo =
        processMemcpyWithLength(locInst, lengthFromR3(locInst))

    /**
     * Same as [processMemcpy] but with an explicit [len], so [processMemcpyZExt] can reuse the body.
     */
    private fun processMemcpyWithLength(locInst: LocatedSbfInstruction, len: Long?): TACMemSplitter.MemTransferInfo {
        val srcType = scalarTypes.typeAtInstruction(locInst, SbfRegister.R2)
        val dstType = scalarTypes.typeAtInstruction(locInst, SbfRegister.R1)
        val srcReg = Value.Reg(SbfRegister.R2)
        val dstReg = Value.Reg(SbfRegister.R1)
        val isSrcStack = srcType is SbfType.PointerType.Stack
        val isDstStack = dstType is SbfType.PointerType.Stack
        return when {
            !isSrcStack && !isDstStack -> {
                val srcTarget = nonStackTarget(srcType, srcReg, 0L) ?: return TACMemSplitter.UnsupportedMemTransferInfo
                val dstTarget = nonStackTarget(dstType, dstReg, 0L) ?: return TACMemSplitter.UnsupportedMemTransferInfo
                TACMemSplitter.NonStackMemTransferInfo(srcTarget, dstTarget, len, TACMemSplitter.HavocMapBytes(listOf()))
            }
            isSrcStack && isDstStack -> {
                if (len == null) {
                    return TACMemSplitter.UnsupportedMemTransferInfo
                }
                val r10Offset = r10OffsetAt(locInst) ?: return TACMemSplitter.UnsupportedMemTransferInfo
                val srcOffsets = (srcType as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongList()
                val dstOffsets = (dstType as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongList()
                TACMemSplitter.StackMemTransferInfo(
                    buildStackSliceMap(srcOffsets, r10Offset, len),
                    buildStackSliceMap(dstOffsets, r10Offset, len),
                    len,
                    TACMemSplitter.HavocScalars(mapOf())
                )
            }
            else -> {
                // mixed: one side is stack, the other isn't
                if (len == null) {
                    return TACMemSplitter.UnsupportedMemTransferInfo
                }
                val r10Offset = r10OffsetAt(locInst) ?: return TACMemSplitter.UnsupportedMemTransferInfo
                val (stackType, nonStackType, nonStackReg) = if (isSrcStack) {
                    Triple(srcType, dstType, dstReg)
                } else {
                    Triple(dstType, srcType, srcReg)
                }
                val stackOffsets = (stackType as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongList()
                val nonStackTarget = nonStackTarget(nonStackType, nonStackReg, 0L)
                    ?: return TACMemSplitter.UnsupportedMemTransferInfo
                val havoc: TACMemSplitter.HavocMemLocations = if (isDstStack) {
                    TACMemSplitter.HavocScalars(mapOf())
                } else {
                    TACMemSplitter.HavocMapBytes(listOf())
                }
                TACMemSplitter.MixedRegionsMemTransferInfo(
                    nonStackTarget,
                    buildStackSliceMap(stackOffsets, r10Offset, len),
                    isDstStack,
                    len,
                    havoc
                )
            }
        }
    }

    /**
     * Translate a `memset(dst=R1, val=R2, len=R3)` call.
     *
     * Mirrors [PTAMemSplitter.processMemset] but using only information from the scalar domain.
     */
    private fun processMemset(locInst: LocatedSbfInstruction): TACMemSplitter.MemsetInfo {
        val len = lengthFromR3(locInst) ?: return TACMemSplitter.UnsupportedMemsetInfo
        val storedVal = (scalarTypes.typeAtInstruction(locInst, SbfRegister.R2) as? SbfType.NumType)
            ?.value?.toLongOrNull() ?: return TACMemSplitter.UnsupportedMemsetInfo
        return processMemsetWithLengthAndValue(locInst, len, storedVal)
    }

    /**
     * Same as [processMemset] but with explicit [len], [storedVal], and starting [offset]
     * relative to `R1`. [offset] is non-zero for the zero-fill of `memcpy_zext` (which begins at
     * `r1 + i`). For a plain `memset` it is 0.
     */
    private fun processMemsetWithLengthAndValue(
        locInst: LocatedSbfInstruction,
        len: Long,
        storedVal: Long,
        offset: Long = 0,
    ): TACMemSplitter.MemsetInfo {
        val dstType = scalarTypes.typeAtInstruction(locInst, SbfRegister.R1)
        return if (dstType is SbfType.PointerType.Stack) {
            if (storedVal != 0L) {
                return TACMemSplitter.UnsupportedMemsetInfo
            }
            val r1StackOffset = dstType.offset.toLongOrNull()
                ?: return TACMemSplitter.UnsupportedMemsetInfo
            val start = r1StackOffset + offset
            TACMemSplitter.StackZeroMemsetInfo(TACMemSplitter.StackSlice(start, start + len - 1), len)
        } else {
            val target = nonStackTarget(dstType, Value.Reg(SbfRegister.R1), 0L)
                ?: return TACMemSplitter.UnsupportedMemsetInfo
            TACMemSplitter.NonStackMemsetInfo(target, storedVal, len, offset)
        }
    }

    /**
     * Translate a `memcpy_zext(dst=R1, src=R2, i=R3)` call: copy `i` bytes from src to dst,
     * then zero-fill `dst[i..8)`.
     *
     * Mirrors [PTAMemSplitter.processMemcpyZExt] but using only information from the scalar domain
     */
    private fun processMemcpyZExt(locInst: LocatedSbfInstruction): TACMemSplitter.MemcpyZExt {
        val i = lengthFromR3(locInst) ?: return TACMemSplitter.UnsupportedMemcpyZExtInfo
        val tacMemcpy = processMemcpyWithLength(locInst, i)
            .takeIf { it !is TACMemSplitter.UnsupportedMemTransferInfo }
            ?: return TACMemSplitter.UnsupportedMemcpyZExtInfo
        val tacMemset = processMemsetWithLengthAndValue(locInst, len = 8L - i, storedVal = 0L, offset = i)
            .takeIf { it !is TACMemSplitter.UnsupportedMemsetInfo }
            ?: return TACMemSplitter.UnsupportedMemcpyZExtInfo
        return TACMemSplitter.SupportedMemcpyZExtInfo(tacMemcpy, tacMemset)
    }

    /**
     * Translate a `memcmp(op1=R1, op2=R2, len=R3)` call.
     *
     * Mirrors [PTAMemSplitter.processMemcmp] but using only information from the scalar domain.
     *
     * The scalar domain does not track word-addressability, so we conservatively require `len` to
     * be a multiple of [SolanaConfig.WordSize] for stack-involving operands.
     */
    private fun processMemcmp(locInst: LocatedSbfInstruction): TACMemSplitter.MemcmpInfo {
        val len = lengthFromR3(locInst) ?: return TACMemSplitter.UnsupportedMemCmpInfo
        val wordSize = SolanaConfig.WordSize.get().toByte()
        val op1Type = scalarTypes.typeAtInstruction(locInst, SbfRegister.R1)
        val op2Type = scalarTypes.typeAtInstruction(locInst, SbfRegister.R2)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val isOp1Stack = op1Type is SbfType.PointerType.Stack
        val isOp2Stack = op2Type is SbfType.PointerType.Stack
        return when {
            !isOp1Stack && !isOp2Stack -> {
                val t1 = nonStackTarget(op1Type, r1, 0L) ?: return TACMemSplitter.UnsupportedMemCmpInfo
                val t2 = nonStackTarget(op2Type, r2, 0L) ?: return TACMemSplitter.UnsupportedMemCmpInfo
                TACMemSplitter.NonStackMemCmpInfo(t1, t2, len, wordSize)
            }
            isOp1Stack && isOp2Stack -> {
                if (len.mod(wordSize.toInt()) != 0) {
                    return TACMemSplitter.UnsupportedMemCmpInfo
                }
                @Suppress("UNCHECKED_CAST")
                val op1Off = (op1Type as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongOrNull()
                    ?: return TACMemSplitter.UnsupportedMemCmpInfo
                @Suppress("UNCHECKED_CAST")
                val op2Off = (op2Type as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongOrNull()
                    ?: return TACMemSplitter.UnsupportedMemCmpInfo
                TACMemSplitter.StackMemCmpInfo(
                    createStackVarsFromRange(PTAOffset(op1Off), len, wordSize, vFac),
                    createStackVarsFromRange(PTAOffset(op2Off), len, wordSize, vFac),
                    TACMemSplitter.StackSlice(op2Off, op2Off + len - 1),
                    TACMemSplitter.StackSlice(op1Off, op1Off + len - 1),
                    len, wordSize
                )
            }
            else -> {
                if (len.mod(wordSize.toInt()) != 0) {
                    return TACMemSplitter.UnsupportedMemCmpInfo
                }
                val stackType  = if (isOp1Stack) { op1Type } else { op2Type }
                val nonStackType = if (isOp1Stack) { op2Type } else { op1Type }
                val stackReg   = if (isOp1Stack) { SbfRegister.R1 } else { SbfRegister.R2 }
                val nonStackReg = if (isOp1Stack) { SbfRegister.R2 } else { SbfRegister.R1 }
                @Suppress("UNCHECKED_CAST")
                val stackOff = (stackType as SbfType.PointerType.Stack<TNum, TOffset>).offset.toLongOrNull()
                    ?: return TACMemSplitter.UnsupportedMemCmpInfo
                val byteMap = nonStackTarget(nonStackType, Value.Reg(nonStackReg), 0L)
                    ?: return TACMemSplitter.UnsupportedMemCmpInfo
                TACMemSplitter.MixedRegionsMemCmpInfo(
                    createStackVarsFromRange(PTAOffset(stackOff), len, wordSize, vFac),
                    byteMap, stackReg, nonStackReg,
                    TACMemSplitter.StackSlice(stackOff, stackOff + len - 1),
                    len, wordSize
                )
            }
        }
    }

    /**
     * Unsigned 256-bit predicate. If both [lo] and [hi] are non-null, emits `lo ≤ e ∧ e < hi`.
     * If only [lo] is non-null, emits `lo ≤ e`. If only [hi] is non-null, emits `e < hi`.
     * At least one of [lo] and [hi] must be non-null.
     */
    private fun inRange(e: TACExpr, lo: Long?, hi: Long?): TACExpr {
        require(lo != null || hi != null) { "inRange: lo and hi cannot both be null" }
        val loBound = lo?.let { TACExpr.BinRel.Le(TACSymbol.Const(it.toBigInteger(), Tag.Bit256).asSym(), e) }
        val hiBound = hi?.let { TACExpr.BinRel.Lt(e, TACSymbol.Const(it.toBigInteger(), Tag.Bit256).asSym()) }
        return when {
            loBound != null && hiBound != null -> TACExpr.BinBoolOp.LAnd(listOf(loBound, hiBound))
            loBound != null -> loBound
            else -> hiBound!!
        }
    }

    /**
     * Minimal scalar-only stack encoding: enumerate each byte in the access window,
     * mapping each byte's offset (relative to r10) to its [TACByteStackVariable]
     * (built from the absolute offset). Mirrors the convention in [PTAMemSplitter].
     * No reconstruction or havoc bookkeeping — that's PTA's job. Returns null if
     * either the base offset or r10's offset is statically unknown.
     */
    private fun buildStackInfo(
        locInst: LocatedSbfInstruction,
        inst: SbfInstruction.Mem,
        baseType: SbfType.PointerType.Stack<TNum, TOffset>,
    ): TACMemSplitter.StackLoadOrStoreInfo? {

        val baseOffset = baseType.offset

        if (baseOffset.isBottom()) {
            return null // unreachable
        }

        if (baseOffset.isTop()) {
            // If this happens we might need to encode stack with a ByteMap
            throw TACTranslationError("TACScalarMemSplitter: base $baseOffset is top at $inst")
        }

        val r10Type = scalarTypes.typeAtInstruction(locInst, SbfRegister.R10) as? SbfType.PointerType.Stack
        val r10Offset = r10Type?.offset?.toLongOrNull()
            ?: throw TACTranslationError("The scalar analysis does know where r10 points to at $inst")

        val absOffset = baseOffset.add(inst.access.offset.toLong())
        val offsets = absOffset.toLongList()
        check(offsets.isNotEmpty())

        val variables = offsets.map { o ->
            // key: offset relative to r10
            // value: TAC variable built from absolute offset
            PTAOffset(o - r10Offset) to vFac.getByteStackVar(PTAOffset(o))
        }.toMap()

        return TACMemSplitter.StackLoadOrStoreInfo(
            variables = variables,
            reconstructedValues = mapOf(),
            locationsToHavoc = TACMemSplitter.HavocScalars(mapOf())
        )
    }

    /**
     * Map each absolute stack offset in [stackOffsets] to a (r10-relative offset → [TACMemSplitter.StackSlice])
     * pair. The slice spans `[offset, offset + length - 1]`.
     *
     * Used to construct the `source` / `destination` / `stack` fields of [TACMemSplitter.StackMemTransferInfo]
     * and [TACMemSplitter.MixedRegionsMemTransferInfo], where keys are r10-relative.
     */
    private fun buildStackSliceMap(
        stackOffsets: List<Long>,
        r10Offset: Long,
        length: Long,
    ): Map<PTAOffset, TACMemSplitter.StackSlice> =
        stackOffsets.associate { offset ->
            PTAOffset(offset - r10Offset) to TACMemSplitter.StackSlice(offset, offset + length - 1)
        }
}
