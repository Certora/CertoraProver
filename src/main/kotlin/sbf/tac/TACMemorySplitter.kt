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

import sbf.*
import sbf.analysis.IRegisterTypes
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.analysis.MemoryAnalysis
import sbf.callgraph.SolanaFunction
import tac.Tag
import vc.data.TACSymbol
import datastructures.stdcollections.*

/**
 *  Dummy class in case no memory splitting is done.
 *  All pointers are mapped to the same variable
 **/
class DummyMemSplitter<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> (// State for the TAC translation
    declaredVars: ArrayList<TACSymbol.Var>,
    private val regTypes: IRegisterTypes<TNum, TOffset>): TACMemSplitter {
    private val mem: TACVariable = TACByteMapVariable(TACSymbol.Var("UntypedMem", Tag.ByteMap))
    init { declaredVars.add(mem.tacVar) }

    override fun getTACMemory(locInst: LocatedSbfInstruction) =
        TACMemSplitter.NonStackLoadOrStoreInfo(mem as TACByteMapVariable, TACMemSplitter.HavocScalars(mapOf()))
    override fun getTACMemoryFromSummary(locInst: LocatedSbfInstruction) =
        listOf<TACMemSplitter.SummaryArgInfo>()
    override fun getTACMemoryFromMemIntrinsic(locInst: LocatedSbfInstruction): TACMemSplitter.MemInstInfo  {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)
        return when (SolanaFunction.from(inst.name)) {
            SolanaFunction.SOL_MEMCPY -> {
                TACMemSplitter.NonStackMemTransferInfo(mem as TACByteMapVariable, mem, null,
                                                        TACMemSplitter.HavocMapBytes(listOf()))
            }
            SolanaFunction.SOL_MEMCMP -> {
                val lenType = regTypes.typeAtInstruction(locInst, SbfRegister.R3_ARG)
                if (lenType is SbfType.NumType) {
                    val len = lenType.value.toLongOrNull()
                    if (len != null) {
                        TACMemSplitter.NonStackMemCmpInfo(mem as TACByteMapVariable, mem, len, SolanaConfig.WordSize.get().toByte())
                    } else {
                        TACMemSplitter.UnsupportedMemCmpInfo
                    }
                } else {
                    TACMemSplitter.UnsupportedMemCmpInfo
                }
            }
            SolanaFunction.SOL_MEMSET -> {
                TACMemSplitter.UnsupportedMemsetInfo
            }
            else -> {
               TACMemSplitter.NotImplMemInstInfo
            }
        }
    }
}

/**
 * Perform memory splitting (aka memory disambiguation) by leveraging the Memory domain
 *
 *  For a given memory load or store at location l, we get the PTACell c being de-referenced.
 *  - If c points to the stack at l, then c is modeled as a scalar variable.
 *  - Otherwise, c is modeled as a ByteMap variable.
 *
 *  Recall that the stack is modeled in a flow-sensitive way: each location has its own stack.
 *  During the mapping from a cell to a scalar, the same stack offset is mapped to the same scalar variable
 *  regardless to which stack the cell points to. Thus, this can be seen as a flow-insensitive encoding of the stacks.
 *
 *  For solana memory instructions (sol_memcpy, sol_memmove, sol_memset, and sol_memcmp) we only support
 *  sol_memcpy and sol_memcmp.
 *
 *  The encoding of sol_memcpy differs whether source and destination cells are modeled as scalars or a single ByteMap.
 *  If both source and destination are ByteMap then we can use directly TAC ByteLongCopy.
 *  If both source and destination are scalars then we need to generate a sequence of assignments between
 *  scalar variables. Under some conditions, we allow mixing scalars and ByteMap (when copying from/to heap to stack).
 *  The scalar encoding of sol_memcpy only generates assignments if the destination
 *  cell is used in the program. If the destination cell is used in the program but the source cell is not
 *  (e.g., it is used outside the program under verification) then the destination cell is havocked.
 *
 *  The encoding of sol_memcmp is done differently. Similar to sol_memcpy, source and destination cells can be modeled
 *  either as scalars or a single ByteMap. Unlike sol_memcpy, the encoding of sol_memcmp is not done based on "use"
 *  because even if source or destination is not used in the program, we want to model them because otherwise we might
 *  miss equalities. For instance,
 *  ```
 *    b1:= sol_memcmp(x, z, 32)
 *    b2:= sol_memcmp(y, z, 32)
 *    if (b1==0 && b2==0) { ... }
 *  ```
 *  Even if z is not used in the program, we can infer that x and y are equal inside the "then" branch.
 *  Thus, for sol_memcmp we split the range of bytes to be compared in words and add equalities between each pair of
 *  source-destination words regardless whether the corresponding cells are used in the program.
 *  To do this, we need to fix a priori the word size, but we check with the Memory domain
 *  that the program is consistent with the selected word size. That is, all the accesses in the program for source and
 *  destination locations always access a number of bytes equal to the word size.
 *  If not, the precise encoding of sol_memcmp cannot be done, and we resort to the imprecise one.
 **/

class PTAMemSplitter<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> (
                     private val cfg: SbfCFG,
                     private val vFac: TACVariableFactory,
                      // Memory analysis
                     private val analysis: MemoryAnalysis<TNum, TOffset>,
                      // Global state needed to reply invariants at each statement
                     private val globals: GlobalVariableMap) : TACMemSplitter {

    /* For memory load and store */
    private val memTACMap: MutableMap<LocatedSbfInstruction, TACMemSplitter.LoadOrStoreInfo> = mutableMapOf()
    /* For memcpy/memcmp */
    private val memInstTACMap: MutableMap<LocatedSbfInstruction, TACMemSplitter.MemInstInfo> = mutableMapOf()
    /* For summarized calls */
    private val callTACMap: MutableMap<LocatedSbfInstruction, List<TACMemSplitter.SummaryArgInfo>> = mutableMapOf()

    init {
        // pre-compute [memTACMap], [memInstTACMap], and [callTACMap] using the Memory domain results
        populateTACMaps()
    }

    override fun getTACMemory(locInst: LocatedSbfInstruction): TACMemSplitter.LoadOrStoreInfo? {
        check(locInst.inst is SbfInstruction.Mem) {"precondition of getTACMemory fails"}
        return memTACMap[locInst]
    }

    override fun getTACMemoryFromMemIntrinsic(locInst: LocatedSbfInstruction): TACMemSplitter.MemInstInfo?  {
        check(locInst.inst is SbfInstruction.Call) {"precondition of getTACMemory fails"}
        return memInstTACMap[locInst]
    }

    override fun getTACMemoryFromSummary(locInst: LocatedSbfInstruction): List<TACMemSplitter.SummaryArgInfo>? {
        check(locInst.inst is SbfInstruction.Call) {"precondition of getTACMemory fails"}
        return callTACMap[locInst]
    }

    /* Private methods */

    sealed class PTAMemoryInfo

    // To be used by PTAMemoryInfo
    sealed class PTAFields
    class PTAStackFields(val v: Map<PTAOffset, List<PTAField>>): PTAFields()
    class PTANonStackFields(val v: List<PTAField>): PTAFields()

    /**
     * Given a Deref `*(r+o)` from a load or store instruction:
     * @param c is the symbolic cell pointed by `r+o`.
     * @param stackPtr: is the concrete cell pointed by `r10`.
     * @param killedFields: overlapping cells killed by the pointer analysis during the transfer function if instruction is a store
     */
    data class PTALoadOrStoreInfo(val c: PTASymCell, val stackPtr: PTACell, val killedFields: PTAFields?): PTAMemoryInfo() {
        init {
            if (c.getNode().isForwarding()) {
                throw TACTranslationError("PTALoadOrStoreInfo should take only resolved cells (1)")
            }
            if (stackPtr.getNode().isForwarding()) {
                throw TACTranslationError("PTALoadOrStoreInfo should take only resolved cells (2)")
            }
            check(!c.getOffset().isTop() && !c.getOffset().isBottom()) {"PTALoadOrStoreInfo: statically unknown offset for $c"}
            if (killedFields != null) {
                check(c.getNode() != stackPtr.getNode() || killedFields is PTAStackFields) { "PTALoadOrStoreInfo: unexpected killedFields type" }
            }
        }
    }

    /**
     * Given `memcpy(src, dst, len)`, `memcmp(src, dst, len)`, or `memmove(src, dst, len)`:
     * @param dstC is the cell pointed by `dst` pointer
     * @param srcC is the cell pointed by `src` pointer
     * @param stackPtr is the concrete cell pointed by `r10`.
     * @param length is value of `len`
     * @param killedFields overlapping cells killed by the pointer analysis during a memory transfer function.
     */
    data class PTAMemoryInstInfo(val dstC: PTASymCell,
                                 val srcC: PTASymCell,
                                 val stackPtr: PTACell,
                                 val length: Long?,
                                 val killedFields: PTAFields?): PTAMemoryInfo() {
        init {
            if (dstC.getNode().isForwarding()) {
                throw TACTranslationError("PTAMemoryInstInfo should take only resolved cells (1)")
            }
            if (srcC.getNode().isForwarding()) {
                throw TACTranslationError("PTAMemoryInstInfo should take only resolved cells (2)")
            }
            check(!srcC.getOffset().isTop() && !srcC.getOffset().isBottom()) {"PTAMemoryInstInfo: statically unknown offset for src $srcC"}
            check(!dstC.getOffset().isTop() && !dstC.getOffset().isBottom()) {"PTAMemoryInstInfo: statically unknown offset for dst $dstC"}
            if (killedFields != null) {
                check(dstC.getNode() != stackPtr.getNode() || killedFields is PTAStackFields) {"PTAMemoryInstInfo: unexpected killedFields type"}
            }
        }
    }

    /**
     * Given `memset(p, val, len)`:
     * @param c is the cell pointed by pointer `p`
     * @param stackPtr: is the concrete cell pointed by `r10`.
     * @param storedVal is the `val`
     * @param length is value of `len`
     * @param killedFields: overlapping cells killed by the pointer analysis during a memory transfer function.
     */
    data class PTAMemsetInstInfo(val c: PTASymCell,
                                 val stackPtr: PTACell,
                                 val storedVal: Long?, val length: Long?,
                                 val killedFields: PTAFields?): PTAMemoryInfo() {
        init {
            if (c.getNode().isForwarding()) {
                throw TACTranslationError("PTAMemsetInstInfo should take only resolved cells")
            }
            check(!c.getOffset().isTop() && !c.getOffset().isBottom()) {"PTAMemsetInstInfo: statically unknown offset for $c"}
            if (killedFields != null) {
                check(c.getNode() != stackPtr.getNode() || killedFields is PTAStackFields) {"PTAMemsetInstInfo: unexpected killedFields type"}
            }
        }
    }

    /**
     * A summary expression such as (*[width])([r]+[offset]):[ty] is converted to a [PTACallModifiedField] object.
     *
     * Let r point to a cell = (n, offset) then
     * - @params [n] is the node
     * - @params [f] is the field represented by offset+4 and [width]
     **/
    data class PTACallModifiedField(val r: SbfRegister, val offset: PTAOffset, val width: Byte, val allocatedSpace: ULong,
                                    val n: PTANode, val f: PTAField,
                                    val ty: MemSummaryArgumentType, val isStack: Boolean) {
        init {
            if (n.isForwarding()) {
                throw TACTranslationError("PTACallModifiedField should take only resolved nodes")
            }
        }
    }
    data class PTACallInfo(val modifiedFields: List<PTACallModifiedField>): PTAMemoryInfo()

    /**
     * Populate [memTACMap], [callTACMap], and [memInstTACMap]
     *
     * Since invariants are only stored at the level of basic block,
     * we need to rebuild them for each statement, so we can map each memory instruction
     * to a cell in the global points-to graph.
     *
     * - If the memory instruction accesses the stack, which is tracked in a flow-sensitive manner, then the TAC encoding
     * takes place when the instruction is re-analyzed. This is needed because the stack can change before the analysis of the basic block ends.
     * - If the memory instruction accesses other any memory region, which is tracked in a flow-insensitive manner, then the TAC
     * encoding is delayed after a whole pass has been completed over the whole program. This is needed because bla blab
     **/
    private fun populateTACMaps() {
        val start = System.currentTimeMillis()
        sbfLogger.info { "Re-running memory analysis to generate info at each statement" }
        val listener = MemoryPartitioningListener<TNum, TOffset>(::encodePTAtoTAC, analysis.memSummaries, globals)
        for (block in cfg.getBlocks().values) {
            val absVal = analysis.getPre(block.getLabel())
            if (absVal == null || absVal.isBottom())  {
                continue
            }
            absVal.analyze(block, globals, analysis.memSummaries, listener)
        }
        val end = System.currentTimeMillis()
        sbfLogger.info { "Finished re-running memory analysis in ${(end - start) / 1000}s" }
    }

    /**
     * Encode all the memory locations (collected in [memInfo]) accessed by [locInst] into TAC variables.
     * This function returns nothing because it updates [memTACMap], [callTACMap], and [memInstTACMap]
     */
    private fun encodePTAtoTAC(locInst: LocatedSbfInstruction, memInfo: PTAMemoryInfo) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem || inst is SbfInstruction.Call)
        if (inst is SbfInstruction.Mem) {
            /** load or store instruction **/
            check(memInfo is PTALoadOrStoreInfo)
            {"A memory instruction should be mapped to a PTALoadOrStoreInfo object"}
            memTACMap[locInst] = processLoadOrStore(memInfo, inst)
        } else if (inst is SbfInstruction.Call){
            if (memInfo is PTACallInfo) {
                /**
                 * SBF to SBF call for which a user-provided summary is available
                 * We just extract all TAC variables that are modified by the summary
                 **/
                val summaryArgs = ArrayList<TACMemSplitter.SummaryArgInfo>()
                for (modifiedField in memInfo.modifiedFields) {
                    val n = modifiedField.n
                    val field = modifiedField.f
                    val isStack = modifiedField.isStack
                    val variable = if (isStack) {
                        vFac.getByteStackVar(field.offset)
                    } else {
                        vFac.getByteMapVar(n.createCell(field.offset))
                    }
                    summaryArgs.add(TACMemSplitter.SummaryArgInfo(modifiedField.r, modifiedField.offset, modifiedField.width, modifiedField.allocatedSpace,
                                                                  modifiedField.ty, variable))
                }
                callTACMap[locInst] = summaryArgs
            } else {
                when (SolanaFunction.from(inst.name)) {
                    SolanaFunction.SOL_MEMCPY -> {
                        check(memInfo is PTAMemoryInstInfo){"memcpy expects PTAMemoryInstInfo"}
                        memInstTACMap[locInst] = processMemCopy(memInfo)
                    }
                    SolanaFunction.SOL_MEMCMP -> {
                        check(memInfo is PTAMemoryInstInfo){"memcmp expects PTAMemoryInstInfo"}
                        memInstTACMap[locInst] = processMemCompare(memInfo)
                    }
                    SolanaFunction.SOL_MEMSET -> {
                        check(memInfo is PTAMemsetInstInfo){"memset expects PTAMemsetInstInfo"}
                        memInstTACMap[locInst] = processMemSet(memInfo)
                    }
                    else -> {
                        throw TACTranslationError("EncodePTAToTAC: expected only memcpy, memcmp, or memset")
                    }
                }
            }
        }
    }

    /** Helper to convert from [PTAFields] cast as [PTAStackFields] to [TACMemSplitter.HavocScalars] **/
    private fun getVarsToHavocAsStack(fields:PTAFields?, stackPtr: PTAOffset): TACMemSplitter.HavocScalars {
        fun getStackVarsToHavoc(fields: PTAStackFields, stackPtrOffset: PTAOffset) =
            // Adjust the offsets (map keys) to be relative to the stack pointer (r10)
            TACMemSplitter.HavocScalars(
                fields.v.map {
                    it.key - stackPtrOffset to
                    it.value.map { f -> vFac.getByteStackVar(f.offset) }
                }.toMap()
            )

        return if  (fields != null) {
            check(fields is PTAStackFields) {"Unexpected type of $fields in getVarsToHavocAsStack"}
            getStackVarsToHavoc(fields, stackPtr)
        } else {
            TACMemSplitter.HavocScalars(mapOf())
        }

    }

    /** Helper to convert from [PTAFields] cast as [PTANonStackFields] to [TACMemSplitter.HavocMapBytes] **/
    private fun getVarsToHavocAsNonStack(fields: PTAFields?, base: PTAOffset): TACMemSplitter.HavocMapBytes {
        fun getNonStackVarsToHavoc(fields: PTANonStackFields, base: PTAOffset) =
            // Adjust the offsets to be relative to the base
            TACMemSplitter.HavocMapBytes(fields.v.map { it.offset - base })

        return if  (fields != null) {
            check(fields is PTANonStackFields) {"Unexpected type of $fields in getVarsToHavocAsNonStack"}
            getNonStackVarsToHavoc(fields, base)
        } else {
            TACMemSplitter.HavocMapBytes(listOf())
        }
    }

    /**
     * Create [TACMemSplitter.LoadOrStoreInfo] from [PTALoadOrStoreInfo]
     **/
    private fun processLoadOrStore(memInfo: PTALoadOrStoreInfo,
                                   @Suppress("UNUSED_PARAMETER") inst: SbfInstruction.Mem): TACMemSplitter.LoadOrStoreInfo {
        val isStack = memInfo.c.getNode() == memInfo.stackPtr.getNode()
        return if (isStack) {
            val stackPtrOffset = memInfo.stackPtr.getOffset()
            val derefSymOffset = memInfo.c.getOffset()
            val stackMap = derefSymOffset.toLongList().map { offset ->
                // the key must be the (negative) offset relative to the stack pointer
                // the value is a TAC variable constructed from the absolute offset
                PTAOffset(offset - stackPtrOffset.v) to vFac.getByteStackVar(PTAOffset(offset))
            }.toMap()
            check(!memInfo.c.isConcrete() || stackMap.size == 1) {"Unexpected result in processLoadOrStore ${memInfo.c} and $stackMap"}
            TACMemSplitter.StackLoadOrStoreInfo(stackMap, getVarsToHavocAsStack(memInfo.killedFields, stackPtrOffset))

        } else {
            val c = memInfo.c.concretize()
            val base = c.getOffset()
            TACMemSplitter.NonStackLoadOrStoreInfo(vFac.getByteMapVar(c), getVarsToHavocAsNonStack(memInfo.killedFields, base))
        }
    }

    /**
     * Create [TACMemSplitter.MemCmpInfo] object from [PTAMemoryInstInfo].
     **/
    private fun processMemCompare(memInfo: PTAMemoryInstInfo): TACMemSplitter.MemCmpInfo {
        val length = memInfo.length ?: return TACMemSplitter.UnsupportedMemCmpInfo
        val wordSize = SolanaConfig.WordSize.get().toByte()
        val isSrcStack = memInfo.srcC.getNode() == memInfo.stackPtr.getNode()
        val isDstStack = memInfo.dstC.getNode() == memInfo.stackPtr.getNode()

        // call `concretize` might raise an exception unnecessarily if access to the stack at unknown offset.
        val dstC = memInfo.dstC.concretize()
        val srcC = memInfo.srcC.concretize()

        return if (!isSrcStack && !isDstStack) {
            val dstVar = vFac.getByteMapVar(dstC)
            val srcVar = vFac.getByteMapVar(srcC)
            TACMemSplitter.NonStackMemCmpInfo(dstVar, srcVar, length, wordSize)
        } else if (isSrcStack && isDstStack) {
            if (!srcC.isWordCompatible(length, wordSize) ||
                !dstC.isWordCompatible(length, wordSize)) {
                /// We could continue at the expense of enforcing word addressability later
                TACMemSplitter.UnsupportedMemCmpInfo
            } else {
                /* Scalarize both operands: use of scalars for source and destination */
                val srcVars = createStackVarsFromRange(srcC.getOffset(), length, wordSize)
                val dstVars = createStackVarsFromRange(dstC.getOffset(), length, wordSize)
                TACMemSplitter.StackMemCmpInfo(
                    dstVars, srcVars,
                    TACMemSplitter.StackSlice(srcC.getOffset().v, srcC.getOffset().v + length - 1),
                    TACMemSplitter.StackSlice(dstC.getOffset().v, dstC.getOffset().v + length - 1),
                    length, wordSize
                )
            }
        } else {
            val (stackC, nonStackC) = if (isSrcStack) {
                Pair(srcC, dstC)
            }  else {
                Pair(dstC, srcC)
            }
            val (stackReg, nonStackReg) = if (isSrcStack) {
                // memInfo.srcC corresponds to r2
                // memInfo.dstC corresponds to r1
                Pair(SbfRegister.R2_ARG, SbfRegister.R1_ARG)
            }  else {
                Pair(SbfRegister.R1_ARG, SbfRegister.R2_ARG)
            }
            if (!stackC.isWordCompatible(length, wordSize)) {
                TACMemSplitter.UnsupportedMemCmpInfo
            } else {
                val scalarVars = createStackVarsFromRange(stackC.getOffset(), length, wordSize)
                val byteMapVar = vFac.getByteMapVar(nonStackC)
                TACMemSplitter.MixedRegionsMemCmpInfo(
                    scalarVars,
                    byteMapVar,
                    stackReg,
                    nonStackReg,
                    TACMemSplitter.StackSlice(stackC.getOffset().v, stackC.getOffset().v + length - 1),
                    length, wordSize)
            }
        }
    }

    private fun createStackVarsFromRange(start: PTAOffset, length: Long, wordSize: Byte):
        List<TACByteStackVariable> {
        check(length.mod(wordSize.toInt()) == 0) {"precondition of createScalarVarsFromRange"}
        val vars = ArrayList<TACByteStackVariable>()
        for (i in 0 until length step wordSize.toLong()) {
            val srcOffset = start + i
            vars.add(vFac.getByteStackVar(srcOffset))
        }
        return vars
    }

    /**
     *  Create [TACMemSplitter.MemTransferInfo] object from [PTAMemoryInstInfo]
     **/
    private fun processMemCopy(memInfo: PTAMemoryInstInfo): TACMemSplitter.MemTransferInfo {
        val len = memInfo.length
        val isSrcStack = memInfo.srcC.getNode() == memInfo.stackPtr.getNode()
        val isDstStack = memInfo.dstC.getNode() == memInfo.stackPtr.getNode()
        return if (!isSrcStack && !isDstStack) {
            val srcC = memInfo.srcC.concretize()
            val dstC = memInfo.dstC.concretize()
            val dstVar = vFac.getByteMapVar(dstC)
            val srcVar = vFac.getByteMapVar(srcC)

            TACMemSplitter.NonStackMemTransferInfo(
                srcVar,
                dstVar,
                len /* it can be null */,
                getVarsToHavocAsNonStack(memInfo.killedFields, dstC.getOffset())
            )
        } else if (isSrcStack && isDstStack) {
            if (len == null) {
                throw TACTranslationError("cannot TAC translate stack mempcy without static length")
            }

            val srcSymOffset = memInfo.srcC.getOffset()
            val dstSymOffset = memInfo.dstC.getOffset()
            val stackPtrOffset = memInfo.stackPtr.getOffset()

            val srcStackMap = srcSymOffset.toLongList().map { offset ->
                // the key must be the (negative) offset relative to the stack pointer
                // the value is the stack slice
                PTAOffset(offset - stackPtrOffset.v) to TACMemSplitter.StackSlice(offset, offset + len - 1)
            }.toMap()

            val dstStackMap = dstSymOffset.toLongList().map { offset ->
                // the key must be the (negative) offset relative to the stack pointer
                // the value is the stack slice
                PTAOffset(offset - stackPtrOffset.v) to TACMemSplitter.StackSlice(offset, offset + len - 1)
            }.toMap()

            TACMemSplitter.StackMemTransferInfo(
                srcStackMap,
                dstStackMap,
                len,
                getVarsToHavocAsStack(memInfo.killedFields, stackPtrOffset)
            )
        } else {
            // One register points to the stack and the other doesn't.
            // We support it as long as the length is statically known.
            if (len != null) {
                check(isDstStack xor isSrcStack)
                val (stackSc, nonStackSc) = if (isSrcStack) {
                    memInfo.srcC to memInfo.dstC
                } else {
                    memInfo.dstC to memInfo.srcC
                }

                val stackPtrOffset = memInfo.stackPtr.getOffset()
                val stackMap = stackSc.getOffset().toLongList().map { offset ->
                    // the key must be the (negative) offset relative to the stack pointer
                    // the value is the stack slice
                    PTAOffset(offset - stackPtrOffset.v) to TACMemSplitter.StackSlice(offset, offset + len - 1)
                }.toMap()

                val nonStackC = nonStackSc.concretize()

                TACMemSplitter.MixedRegionsMemTransferInfo(
                    vFac.getByteMapVar(nonStackC),
                    stackMap,
                    isDstStack,
                    len,
                    if (isDstStack) {
                        getVarsToHavocAsStack(memInfo.killedFields, stackPtrOffset)
                    } else {
                        getVarsToHavocAsNonStack(memInfo.killedFields, nonStackC.getOffset())
                    }
                )
            } else {
                TACMemSplitter.UnsupportedMemTransferInfo
            }
        }
    }


    /**
     *  Create [TACMemSplitter.MemsetInfo] object from [PTAMemsetInstInfo]
     **/
    private fun processMemSet(memInfo: PTAMemsetInstInfo): TACMemSplitter.MemsetInfo {
        val len = memInfo.length ?: return TACMemSplitter.UnsupportedMemsetInfo
        val storedVal = memInfo.storedVal ?: return TACMemSplitter.UnsupportedMemsetInfo
        val isStack = memInfo.c.getNode() == memInfo.stackPtr.getNode()

        // call `concretize` might raise an exception unnecessarily of access to stack at unknown offset
        val c = memInfo.c.concretize()
        return if (isStack) {
            if (storedVal == 0L) {
                TACMemSplitter.StackZeroMemsetInfo(
                    TACMemSplitter.StackSlice(
                        c.getOffset().v,
                        c.getOffset().v + len - 1
                    ), len)
            } else {
                TACMemSplitter.UnsupportedMemsetInfo
            }
        } else {
            val byteMapVar = vFac.getByteMapVar(c)
            TACMemSplitter.NonStackMemsetInfo(byteMapVar, storedVal, len)
        }
    }

    /**
     * Recall that the methods instructionEvent are called before and after each instruction is analyzed by the
     * Memory Analysis. Before each instruction is executed, we identify the [PTACell]s being accessed by the
     * instruction and encode them into TAC variables by using [tacEncoder].
     *
     * Currently, we support:
     * - load
     * - store
     * - external function calls for which user-definable summaries are available
     * - memcpy
     * - memcmp
     * - memset
     *
     * We don't support memmove
     **/
    class MemoryPartitioningListener<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> (
            private val tacEncoder: (locInst: LocatedSbfInstruction, memInfo: PTAMemoryInfo) -> Unit,
            private val memSummaries: MemorySummaries,
            private val globalsMap: GlobalVariableMap)
        : InstructionListener<MemoryDomain<TNum, TOffset>> {
        override fun instructionEvent(locInst: LocatedSbfInstruction, pre: MemoryDomain<TNum, TOffset>, post: MemoryDomain<TNum, TOffset>) {}

        /**
         * It contains the set of overlapping killed cells by the pointer analysis in the **last** memcpy instruction.
         * This is produced in [instructionEventBefore] and consumed in [instructionEventAfter]
         **/
        private var killedFieldsByMemcpy: PTAFields? = null

        // Sanity check
        private fun checkNoOverlaps(n: PTANode, locInst: LocatedSbfInstruction) {
            if (SolanaConfig.OptimisticPTAOverlaps.get()) {
                return
            }

            var lastOffset = Long.MIN_VALUE
            val intervals = ArrayList<Pair<Long,Long>>()
            // pre-condition: getSuccs() returns the list of pair sorted.
            for ((field, _) in n.getSuccs()) {
                val o = field.offset
                val size = field.size
                val lb = o.v
                val ub = lb + size - 1
                intervals.add(Pair(lb, ub))
                if (o <= lastOffset) {
                    throw TACTranslationError("Node $n has overlapping offsets [$lb, $ub] and $lastOffset: " +
                                              "$intervals at instruction ${locInst.inst} at block ${locInst.label}")
                }
                lastOffset = ub
            }
        }

        /** Helper function to extract overlaps from a stack access at [sc] using function [getOverlaps] **/
        private fun getOverlapsFromStack(sc: PTASymCell, getOverlaps: (c: PTACell) -> List<PTAField>?): Map<PTAOffset, List<PTAField>>? {
            val symOffset = sc.getOffset()
            return if (!symOffset.isTop()) {
                val overlapMap = mutableMapOf<PTAOffset, List<PTAField>>()
                val concreteOffsets = symOffset.toLongList()
                check(concreteOffsets.isNotEmpty()) { "something unexpected in getKilledFields (1)" }
                for (o in concreteOffsets) {
                    val c = sc.getNode().createCell(o)
                    val overlaps = getOverlaps(c)
                    check(overlaps != null) { "something unexpected in getKilledFields (2)" }
                    overlapMap[c.getOffset()] = overlaps
                }
                overlapMap
            } else {
                null
            }
        }
        /**
         *  Return the overlapping fields that were killed by the pointer analysis during the analysis of
         *  a store instruction.
         *
         *  If the accessed node is summarized then this function cannot know which fields were killed and will
         *  throw an exception unless `OptimisticPTAOverlaps` is enabled.
         *
         *  In TAC, we need to havoc the corresponding scalars or byte map locations that represent those fields.
         **/
         private fun getKilledFields(inst: SbfInstruction.Mem, absVal: MemoryDomain<TNum, TOffset>): PTAFields? {
            check(!inst.isLoad) {"precondition of getKilledFields: $inst is not a store instruction"}

            fun getOverlaps(c: PTACell, offset: Short, width: Short): List<PTAField>? {
                val node = c.getNode()
                return if (!node.isExactNode()) {
                    null
                } else {
                    val start = c.getOffset() + PTAOffset(offset.toLong())
                    val size = width.toLong()
                    node.getLinksInRange(start, size, isStrict = false, onlyPartial = true).map { it.field }
                }
            }

            val g = absVal.getPTAGraph()
            val baseReg = inst.access.baseReg
            val offset = inst.access.offset
            val width = inst.access.width
            val baseSc = g.getRegCell(baseReg)
            if (baseSc != null)  {
                if (baseSc.getNode() == g.getStack()) {
                    val overlapMap = getOverlapsFromStack(baseSc) { c: PTACell -> getOverlaps(c, offset, width) }
                    if (overlapMap != null) {
                        return PTAStackFields(overlapMap)
                    }
                } else if (baseSc.isConcrete()){
                    val baseC = baseSc.concretize()
                    val overlaps = getOverlaps(baseC, offset, width)
                    if (overlaps != null) {
                        return PTANonStackFields(overlaps)
                    }
                }
            }

            if (!SolanaConfig.OptimisticPTAOverlaps.get()) {
                throw TACTranslationError("cannot determine if $inst should havoc some overlap locations")
            } else {
                return null
            }
         }

        /**
         *  Return fields that were killed by the pointer analysis during the analysis of
         *  a `memcpy` instruction.
         *
         *  In TAC, we need to havoc the corresponding scalars or byte map locations that represent those fields.
         **/
         private fun getKilledFields(inst: SbfInstruction.Call, absVal: MemoryDomain<TNum, TOffset>): PTAFields? {
            check(SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {"Expected call getKilledFields on memcpy"}

            fun getOverwrittenFields(c: PTACell, len: Long): List<PTAField>? {
                val node = c.getNode()
                return if (!node.isExactNode()) {
                    null
                } else {
                    // We are conservative and include also those fully-subsumed fields (`onlyPartial=true`)
                    // so that they can be havoced.
                    c.getNode().getLinksInRange(c.getOffset(), len, isStrict = false, onlyPartial = false).map { it.field }
                }
            }

            val g = absVal.getPTAGraph()
            val scalars = absVal.getScalars()
            val len = (scalars.getValue(Value.Reg(SbfRegister.R3_ARG)).type() as? SbfType.NumType)?.value?.toLongOrNull()
            val dstSc = g.getRegCell(Value.Reg(SbfRegister.R1_ARG))
            if (dstSc != null && len != null) {
                if (dstSc.getNode() == g.getStack()) {
                    val overlapMap = getOverlapsFromStack(dstSc) { c: PTACell -> getOverwrittenFields(c, len) }
                    if (overlapMap != null) {
                        return PTAStackFields(overlapMap)
                    }
                } else if (dstSc.isConcrete()) {
                    val dstC = dstSc.concretize()
                    val overlaps = getOverwrittenFields(dstC, len)
                    if (overlaps != null) {
                        return PTANonStackFields(overlaps)
                    }
                }
            }

            if (!SolanaConfig.OptimisticPTAOverlaps.get()) {
                throw TACTranslationError("cannot determine if memcpy should havoc some overlap locations")
            }
            return null
         }

        class MemPartitioningSummaryVisitor<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>> (
            private val absVal: MemoryDomain<TNum, TOffset>,
            private val globalsMap: GlobalVariableMap) : SummaryVisitor {
            private val sumFields = ArrayList<PTACallModifiedField>()
            private val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
            private val stackNode = absVal.getRegCell(r10, globalsMap)?.getNode()

            init {
                if (stackNode == null) {
                    throw TACTranslationError("memory partitioning failed because cannot find a cell for r10")
                }
            }

            override fun noSummaryFound(locInst: LocatedSbfInstruction) {}

            override fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType) {}

            override fun processArgument(locInst: LocatedSbfInstruction,
                                         reg: SbfRegister,
                                         offset: Long,
                                         width: Byte,
                                         allocatedSpace: ULong,
                                         type: MemSummaryArgumentType) {
                val call = locInst.inst
                check(call is SbfInstruction.Call)
                val r = Value.Reg(reg)
                val symC = absVal.getRegCell(r, globalsMap)
                    ?: throw TACTranslationError("memory partitioning failed because" +
                                                 "cannot find a cell for $r ($call)")
                val c = symC.concretize()
                val adjustedC = c.getNode().createCell(c.getOffset() + offset)
                sumFields.add(PTACallModifiedField(reg, PTAOffset(offset), width, allocatedSpace,
                                                   adjustedC.getNode(), PTAField(adjustedC.getOffset(), width.toShort()),
                                                   type, adjustedC.getNode() == stackNode))
            }

            fun getPTAMemInfo() = PTACallInfo(sumFields)
        }

        /** Get concrete cell pointed by r10 **/
        private fun getCellFromStackPtr(locInst: LocatedSbfInstruction, absVal: MemoryDomain<TNum, TOffset>): PTACell {
            val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
            val stackPtrSc = absVal.getRegCell(r10, globalsMap)
                ?: throw TACTranslationError("memory partitioning failed at $locInst because cannot find a cell for r10")
            if (!stackPtrSc.isConcrete()) {
                throw TACTranslationError("memory partitioning failed at $locInst because unknown offset for r10")
            }
            return stackPtrSc.concretize()
        }

        /** Get symbolic cell pointed by [reg] + [offset] **/
        private fun getSymCell(reg: Value.Reg, offset: Short, locInst: LocatedSbfInstruction, absVal: MemoryDomain<TNum, TOffset>): PTASymCell {
            val baseSc = absVal.getRegCell(reg, globalsMap)
                ?: throw TACTranslationError(
                    "memory partitioning failed because" +
                        "cannot find a cell for $reg (${locInst.inst}) in the local graph ${locInst.label}"
                )

            val sc = baseSc.getNode().createSymCell(
                baseSc.getOffset().add(PTASymOffset(offset.toLong()))
            )

            val symOffset = sc.getOffset()
            if (symOffset.isBottom() || symOffset.isTop()) {
                // it should never be bottom at this point but just being conservative
                throw TACTranslationError("memory partitioning failed at $locInst because unknown offset for $reg")
            }
            return sc
        }

        override fun instructionEventBefore(locInst: LocatedSbfInstruction, pre: MemoryDomain<TNum, TOffset>) {
            if (pre.isBottom()) {
                return
            }
            val memInfo =
                when (val inst = locInst.inst) {
                    is SbfInstruction.Mem -> {
                        // We need to apply "manually" the reduction.
                        //
                        // Note that `instructionEventBefore` is called before the transformer for `locInst`.
                        // Currently, we only apply the reduction at memory instructions (to reduce the number of reductions).
                        // As a result, at this point the reduction from scalar to pta has not taken place yet.
                        // We cannot easily move this code to `instructionEventAfter` because loads of the form `r1 := *r1`
                        pre.reductionFromScalarsToPtaGraph(locInst)

                        // process r10
                        val stackPtrC = getCellFromStackPtr(locInst, pre)

                        // process de-referenced register
                        val baseReg = inst.access.baseReg
                        val offset = inst.access.offset
                        val derefSc = getSymCell(baseReg, offset, locInst, pre)

                        if (SolanaConfig.SanityChecks.get()) {
                            val stackNode = stackPtrC.getNode()
                            if (derefSc.getNode() == stackNode) {
                                checkNoOverlaps(stackNode, locInst)
                            }
                        }
                        PTALoadOrStoreInfo(derefSc, stackPtrC,
                                           if (inst.isLoad) { null } else { getKilledFields(inst, pre) })
                    }
                    is SbfInstruction.Call -> {
                        if (SolanaFunction.from(inst.name) == SolanaFunction.SOL_MEMCPY) {
                            // see notes above
                            pre.reductionFromScalarsToPtaGraph(locInst)

                            killedFieldsByMemcpy = getKilledFields(inst, pre)
                        }
                        null
                    }
                    else -> {
                        null
                    }
                }

            if (memInfo != null) {
                tacEncoder(locInst, memInfo)
            }
        }

        override fun instructionEventAfter(locInst: LocatedSbfInstruction, post: MemoryDomain<TNum, TOffset>) {
            val inst = locInst.inst
            if (post.isBottom()) {
                return
            }
            val memInfo =
                when (inst) {
                    is SbfInstruction.Call -> {
                        when (val solanaFunc = SolanaFunction.from(inst.name)) {
                            SolanaFunction.SOL_MEMCPY, SolanaFunction.SOL_MEMCMP -> {
                                val r1 = Value.Reg(SbfRegister.R1_ARG)
                                val r2 = Value.Reg(SbfRegister.R2_ARG)
                                val r3 = Value.Reg(SbfRegister.R3_ARG)

                                // process r10
                                val stackPtrC = getCellFromStackPtr(locInst, post)

                                // process r1
                                val dstSc = getSymCell(r1, 0, locInst, post)

                                // process r2
                                val srcSc = getSymCell(r2, 0, locInst, post)

                                // process r3
                                val lenVal = post.getAsScalarValue(r3).type()
                                val len = if (lenVal is SbfType.NumType) {
                                    lenVal.value.toLongOrNull()
                                } else {
                                    null
                                }

                                if (SolanaConfig.SanityChecks.get()) {
                                    val stackNode = stackPtrC.getNode()
                                    if (srcSc.getNode() == stackNode || dstSc.getNode() == stackNode) {
                                        checkNoOverlaps(stackNode, locInst)
                                    }
                                }

                                val killedFields = when(solanaFunc) {
                                    SolanaFunction.SOL_MEMCPY -> killedFieldsByMemcpy
                                    else -> {
                                        @Suppress("ForbiddenComment")
                                        // TODO: infer killed fields by memcmp
                                        null
                                    }
                                }
                                PTAMemoryInstInfo(dstSc, srcSc, stackPtrC, len, killedFields)
                            }
                            SolanaFunction.SOL_MEMSET -> {
                                val r1 = Value.Reg(SbfRegister.R1_ARG)
                                val r2 = Value.Reg(SbfRegister.R2_ARG)
                                val r3 = Value.Reg(SbfRegister.R3_ARG)

                                // process r10
                                val stackPtrC = getCellFromStackPtr(locInst, post)

                                // process r1
                                val c = getSymCell(r1, 0, locInst, post)

                                // process r2
                                val v = post.getAsScalarValue(r2).type()
                                val storedVal = if (v is SbfType.NumType) {
                                    v.value.toLongOrNull()
                                } else {
                                    null
                                }

                                // process r3
                                val lengthVal = post.getAsScalarValue(r3).type()
                                val length = if (lengthVal is SbfType.NumType) {
                                    lengthVal.value.toLongOrNull()
                                } else {
                                    null
                                }

                                if (SolanaConfig.SanityChecks.get()) {
                                    val stackNode = stackPtrC.getNode()
                                    if (c.getNode() == stackNode) {
                                        checkNoOverlaps(stackNode, locInst)
                                    }
                                }

                                @Suppress("ForbiddenComment")
                                PTAMemsetInstInfo(c, stackPtrC, storedVal,  length,
                                                  // TODO: infer killed fields by memset
                                                  killedFields = null)
                            }
                            else -> { /* solana system call or any external call */
                                if (memSummaries.getSummary(inst.name) == null) {
                                    null
                                } else {
                                    val vis = MemPartitioningSummaryVisitor(post, globalsMap)
                                    memSummaries.visitSummary(locInst, vis)
                                    vis.getPTAMemInfo()
                                }
                            }
                        }
                    }
                    else -> {
                        null
                    }
                }

            if (memInfo != null) {
                tacEncoder(locInst, memInfo)
            }
        }
    }
}
