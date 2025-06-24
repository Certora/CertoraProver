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
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import sbf.support.SolanaInternalError
import com.certora.collect.*
import org.jetbrains.annotations.TestOnly
import sbf.callgraph.SolanaFunction

/**
 * Memory abstract domain to statically partition memory of SBF programs into disjoint memory subregions.
 *
 * ## Memory model in SBF ##
 *
 * The memory domain models the following memory regions from an SBF program:
 * - Input: contains the program inputs which is a slice of the permanent storage in the blockchain.
 *   The Input is essentially a nested struct with pointers to memory owned by SBF loader that is passed to
 *   the SBF program to have access to account fields.
 * - Stack: program stack to use local variables
 * - Heap: temporary memory available to the program
 * - Read-only Globals: mostly for constant strings.
 *
 * Each region is guaranteed to be disjoint from each other. Apart from these memory regions,
 * an SBF program uses a predefined set of registers: r0,...,r10.
 *
 * ## Memory abstract domain ##
 *
 * Each memory region is modeled by the memory domain differently. We use a scalar domain to keep track only of the possible
 * values of the Stack variables and registers.  A pointer domain keeps track of all memory regions but with different precision depending on which region..
 *
 * ### Implementation ###
 *
 * The memory abstract domain is a reduced product of a scalar domain and a pointer domain.
 * See ScalarDomain.kt for more documentation about the scalar domain.
 * See PointerDomain.kt for more documentation about the pointer domain.
 *
 * If the scalar domain knows that the content of a register is a known constant then we use that for more precise
 * pointer arithmetic in the pointer domain.
 * The scalar domain also communicates to the pointer domain if some constant is identified as a heap/global address.
 *
 * Since SBF is not strongly typed so there is no distinction between a number and a pointer.
 * To deal with this ambiguity, the scalar domain assumes a register or stack slot are numbers until the opposite can be proven
 * (they are de-referenced in a memory instruction) while the pointer domain assumes that anything can be a pointer.
 * unless the scalar domain says definitely otherwise.
 *
 **/

const val enableDefensiveChecks = false

class MemoryDomainError(msg: String): SolanaInternalError("MemoryDomain error: $msg")

class MemoryDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
                   private val scalars: ScalarDomain<TNum, TOffset>,
                   private val ptaGraph: PTAGraph<TNum, TOffset>)
    : AbstractDomain<MemoryDomain<TNum, TOffset>>, ScalarValueProvider<TNum, TOffset> {

    constructor(nodeAllocator: PTANodeAllocator, sbfTypeFac: ISbfTypeFactory<TNum, TOffset>, initPreconditions: Boolean = false)
        : this(ScalarDomain(sbfTypeFac, initPreconditions), PTAGraph(nodeAllocator, sbfTypeFac, initPreconditions))

    /**
     *  Check that the subdomains are consistent about the common facts that they infer.
     *  Currently, we only check about the value of r10.
     **/
    private fun checkConsistencyBetweenSubdomains(globals: GlobalVariableMap, msg:String) {
        if (!SolanaConfig.SanityChecks.get()) {
            return
        }
        if (isBottom()) {
            return
        }

        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val scalars = getScalars()
        val ptaGraph = getPTAGraph()
        // Get value for r10 in the Pointer domain
        val c = ptaGraph.getRegCell(r10, scalars.sbfTypeFac.mkTop() /*shouldn't be used*/, globals, locInst = null)
        check(c != null)
        {"$msg: pointer domain should know about r10"}
        if (c.getNode().isExactNode()) {
            // Get value for r10 in Scalars
            val type = scalars.getValue(r10).type()
            check(type is SbfType.PointerType.Stack<TNum, TOffset>)
            {"$msg: scalar domain should know that r10 is a pointer to the stack"}
            val scalarOffset = type.offset
            val pointerOffset = c.getOffset()
            // Since r10 is read-only, both subdomains should agree on the same offset for r10
            check(scalarOffset.toLongOrNull() == pointerOffset.toLongOrNull())
            { "$msg: scalar and pointer domains should agree on r10 offset" }
        }
    }

    override fun deepCopy(): MemoryDomain<TNum, TOffset> {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator, scalars.sbfTypeFac)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph.copy())
        }
    }

    private fun deepCopyOnlyScalars(): MemoryDomain<TNum, TOffset> {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator, scalars.sbfTypeFac)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph)
        }
    }


    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> initPreconditions(nodeAllocator: PTANodeAllocator, sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): MemoryDomain<TNum, TOffset> {
            return MemoryDomain(nodeAllocator, sbfTypeFac, true)
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeBottom(nodeAllocator: PTANodeAllocator, sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): MemoryDomain<TNum, TOffset> {
            val res = MemoryDomain(nodeAllocator, sbfTypeFac)
            return res.apply { setToBottom() }
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> makeTop(nodeAllocator: PTANodeAllocator, sbfTypeFac: ISbfTypeFactory<TNum, TOffset>): MemoryDomain<TNum, TOffset> {
            return MemoryDomain(nodeAllocator, sbfTypeFac)
        }
    }



    override fun isBottom(): Boolean {
        return scalars.isBottom()
    }

    override fun isTop(): Boolean {
        // REVISIT: we don't consider ptaGraph
        return scalars.isTop()
    }

    override fun setToBottom() {
        scalars.setToBottom()
        ptaGraph.reset()
    }

    override fun setToTop() {
        scalars.setToTop()
        ptaGraph.reset()
    }


    override fun forget(reg: Value.Reg) {
        if (!isBottom()) {
            scalars.forget(reg)
            ptaGraph.forget(reg)
        }
    }

    private fun joinOrWiden(other: MemoryDomain<TNum, TOffset>, isJoin: Boolean,
                            left: Label?, right: Label?): MemoryDomain<TNum, TOffset> {
        if (isBottom()) {
            return other.deepCopy()
        } else if (other.isBottom()) {
            return deepCopy()
        } else {
            val outScalars =
                    if (isJoin) {
                        scalars.join(other.scalars, left, right)
                    } else {
                        scalars.widen(other.scalars, left)
                    }
            val outPtaGraph = if (isJoin) {
                        ptaGraph.join(other.ptaGraph, scalars, other.scalars, outScalars, left, right)
                    } else {
                        ptaGraph.widen(other.ptaGraph, scalars, other.scalars, outScalars, left, right)
                    }

            return MemoryDomain(outScalars, outPtaGraph)
        }
    }

    override fun pseudoCanonicalize(other: MemoryDomain<TNum, TOffset>) {
        if (!isBottom() && !other.isBottom()) {
            ptaGraph.pseudoCanonicalize(other.getPTAGraph())
            scalars.pseudoCanonicalize(other.scalars)
        }
    }

    override fun join(other: MemoryDomain<TNum, TOffset>, left: Label?, right: Label?): MemoryDomain<TNum, TOffset> {
        return joinOrWiden(other, true, left, right)
    }

    override fun widen(other: MemoryDomain<TNum, TOffset>, b: Label?): MemoryDomain<TNum, TOffset> {
        return joinOrWiden(other, false, b, null)
    }

    override fun lessOrEqual(other: MemoryDomain<TNum, TOffset>, left: Label?, right: Label?): Boolean {
        return if (other.isTop() || isBottom()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
            (scalars.lessOrEqual(other.scalars, left, right) && ptaGraph.lessOrEqual(other.ptaGraph, left, right))
        }
    }

    fun getPTAGraph(): PTAGraph<TNum, TOffset> = ptaGraph

    @TestOnly fun getScalars(): ScalarDomain<TNum, TOffset> = scalars

    private fun analyzeUn(locInst: LocatedSbfInstruction,
                          globals: GlobalVariableMap,
                          memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeUn on bottom in memory domain"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Un)
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else {
            ptaGraph.forget(stmt.dst)
        }
    }

    /**
     * Reduction from the scalar domain to the pointer domain.
     *
     * The scalar domain keeps a set of offsets in case of stack pointers while
     * the pointer domain only keeps one offset per stack pointer.
     */
    fun reductionFromScalarsToPtaGraph(locInst: LocatedSbfInstruction) {
        if (isBottom()) {
            return
        }

        val readRegs = locInst.inst.readRegisters
        readRegs.forEach { reg ->
            val offsets = (scalars.getValue(reg).type() as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset?.toLongList()
            if (offsets != null && offsets.isNotEmpty()) {
                // Scalar domain knows that `reg` points to some offset(s) in the stack
                // but the Pointer domain does not know about `reg` or the stack offset(s)
                val c = ptaGraph.getRegCell(reg)
                if (c == null) {
                    ptaGraph.setRegCell(reg, ptaGraph.getStack().createSymCell(PTASymOffset(offsets)))
                } else if (!c.isConcrete()) {
                    ptaGraph.setRegCell(reg, c.getNode().createSymCell(PTASymOffset(offsets)))
                }
            }
        }
    }

    /**
     * Reduction from the pointer domain to the scalar domain.
     *
     * The pointer domain might know that the content of some (non-stack) memory location contains a number.
     * Recall that the scalar domain only knows about registers and stack.
     */
    private fun reductionFromPtaGraphToScalars(b: SbfBasicBlock, locInst: LocatedSbfInstruction, reg: Value) {
        if (isBottom()) {
            return
        }

        if (reg is Value.Reg) {
            val x = ptaGraph.getRegCell(reg)
            if (x != null && x.isConcrete()) {
                val c = x.concretize()
                if (c.getNode().mustBeInteger()) {
                    val change = scalars.refineValue(reg, ScalarValue(scalars.sbfTypeFac.anyNum()))
                    if (change) {
                        val topNum =  scalars.sbfTypeFac.anyNum().concretize()
                        check(topNum != null) {"concretize on anyNum cannot be null"}
                        /// Changing metadata serves here as caching the reduction.
                        val newMetadata = locInst.inst.metaData.plus(SbfMeta.REG_TYPE to  (reg to topNum))
                        val newInst = locInst.inst.copyInst(metadata = newMetadata)
                        (b as MutableSbfBasicBlock).replaceInstruction(locInst.pos, newInst)
                    }
                    return
                }
            }

            /// If the analysis previously determined that `reg` is a number then we keep using that fact,
            /// even if the pointer analysis lost precision and cannot infer that fact anymore.
            locInst.inst.metaData.getVal(SbfMeta.REG_TYPE)?.let { (refinedReg, type) ->
                if (refinedReg == reg && type is SbfRegisterType.NumType) {
                    scalars.refineValue(reg, ScalarValue(scalars.sbfTypeFac.anyNum()))
                }
            }
        }
    }

    private fun analyzeBin(b: SbfBasicBlock,
                           locInst: LocatedSbfInstruction,
                           globals: GlobalVariableMap,
                           memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeBin on bottom in memory domain"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Bin)

        val src = stmt.v
        val dst = stmt.dst

        reductionFromPtaGraphToScalars(b, locInst, src)
        if (stmt.op != BinOp.MOV) {
            reductionFromPtaGraphToScalars(b, locInst, dst)
        }

        // @dstType must be obtained before the transfer function on the scalar domain takes place
        // since @dst can be overwritten to top.
        val dstType = scalars.getValue(dst).type()
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            val srcType = scalars.getValue(src).type()
            ptaGraph.doBin(locInst, stmt.op, dst, src, dstType, srcType, globals)
        }
    }

    private fun analyzeCall(locInst: LocatedSbfInstruction,
                            globals: GlobalVariableMap,
                            memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeCall on bottom in memory domain"}
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else {
            val inst = locInst.inst
            check(inst is SbfInstruction.Call)
            val solFunction = SolanaFunction.from(inst.name)
            if (solFunction != null) {
                when (solFunction) {
                    SolanaFunction.SOL_MEMCMP,
                    SolanaFunction.SOL_MEMCPY,
                    SolanaFunction.SOL_MEMMOVE,
                    SolanaFunction.SOL_MEMSET ->
                        reductionFromScalarsToPtaGraph(locInst)
                    else -> {}
                }
            }
            ptaGraph.doCall(locInst, globals, memSummaries, scalars)
        }
    }

    private fun analyzeAssume(locInst: LocatedSbfInstruction,
                              globals: GlobalVariableMap,
                              memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeAssume on bottom in memory domain"}
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        }
    }

    private fun analyzeAssert(locInst: LocatedSbfInstruction,
                              globals: GlobalVariableMap,
                              memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeAssert on bottom in memory domain"}
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        }
    }

    private fun analyzeHavoc(locInst: LocatedSbfInstruction,
                             globals: GlobalVariableMap,
                             memSummaries: MemorySummaries) {
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Havoc)
        scalars.analyze(locInst, globals, memSummaries)
        if (!isBottom()) {
            ptaGraph.forget(stmt.dst)
        }
    }

    private fun analyzeSelect(b: SbfBasicBlock,
                              locInst: LocatedSbfInstruction,
                              globals: GlobalVariableMap,
                              memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeSelect on bottom in memory domain"}
        val inst = locInst.inst
        check(inst is SbfInstruction.Select)

        reductionFromPtaGraphToScalars(b, locInst, inst.trueVal)
        reductionFromPtaGraphToScalars(b, locInst, inst.falseVal)

        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else {
            val stmt = locInst.inst
            check(stmt is SbfInstruction.Select)
            ptaGraph.doSelect(locInst, globals, scalars)
        }
    }

    /**
     * Transfer function for load and store.
     *
     * The function `reductionFromScalarsToPtaGraph` reconstructs PTA cells from scalar information (for stack).
     *
     * Moreover, PTA transfer functions `doLoad` and `doStore` take the scalar value of the base register as a parameter.
     * This parameter is used to do further reduction by reconstructing PTA cells from globals/heap locations.
     *
     * To improve the design, we should do that second reduction also here so that when `doLoad` and `doStore` are called,
     * all the cells have been reconstructed.
     */
    private fun analyzeMem(locInst: LocatedSbfInstruction,
                           globals: GlobalVariableMap,
                           @Suppress("UNUSED_PARAMETER") memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeMem on bottom in memory domain"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Mem) {"Memory domain expects a memory instruction instead of $stmt"}


        // This reduction must happen before the scalar transfer function because for load
        // instructions the base register and the lhs can be the same register.
        reductionFromScalarsToPtaGraph(locInst)

        // In the case of a load instruction where base register and lhs are the same register,
        // `baseValBeforeKilled` contains the type of the register **before** the lhs is processed but after
        // potentially casting the abstract value of the base register from a number to a global/heap pointer.
        val baseValBeforeKilled = scalars.analyzeMem(locInst, globals)

        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            val base = stmt.access.baseReg
            val isLoad = stmt.isLoad
            if (isLoad) {
                check(baseValBeforeKilled != null) {"Unexpected null scalar value for $stmt"}
                val baseType = baseValBeforeKilled.type()
                ptaGraph.doLoad(locInst, base, baseType, globals)
            } else {
                val value = stmt.value
                val baseType = scalars.getValue(base).type()
                val valueType = scalars.getValue(value).type()
                ptaGraph.doStore(locInst, base, value, baseType, valueType, globals)
            }
        }
    }

    /** Return true if the pointer analysis will model all [b] instructions as non-op **/
    private fun isNonOpForPTA(b: SbfBasicBlock) : Boolean {
        return b.getInstructions().all { it is SbfInstruction.Assume ||
            (it is SbfInstruction.Select && it.trueVal is Value.Imm && it.falseVal is Value.Imm) ||
             it is SbfInstruction.Jump ||
             it is SbfInstruction.Exit}
    }

    private fun analyze(b: SbfBasicBlock,
                        locInst: LocatedSbfInstruction,
                        globals: GlobalVariableMap,
                        memSummaries: MemorySummaries) {

        val inst = locInst.inst
        sbfLogger.debug { "TRANSFER FUNCTION for $inst\n" }
        if (!isBottom()) {
            when (inst) {
                is SbfInstruction.Un -> analyzeUn(locInst, globals, memSummaries)
                is SbfInstruction.Bin -> analyzeBin(b, locInst, globals, memSummaries)
                is SbfInstruction.Call -> analyzeCall(locInst, globals, memSummaries)
                is SbfInstruction.CallReg-> {
                    if (!SolanaConfig.SkipCallRegInst.get()) {
                        throw MemoryDomainError("Memory domain does not support $inst")
                    }
                }
                is SbfInstruction.Select -> analyzeSelect(b, locInst, globals, memSummaries)
                is SbfInstruction.Havoc -> analyzeHavoc(locInst, globals, memSummaries)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume -> analyzeAssume(locInst, globals, memSummaries)
                is SbfInstruction.Assert -> analyzeAssert(locInst, globals, memSummaries)
                is SbfInstruction.Mem -> analyzeMem(locInst, globals, memSummaries)
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
            }
        }
        sbfLogger.debug {"$this\n"}
    }

    override fun analyze(b: SbfBasicBlock,
                         globals: GlobalVariableMap,
                         memSummaries: MemorySummaries,
                         listener: InstructionListener<MemoryDomain<TNum, TOffset>>): MemoryDomain<TNum, TOffset> {


        sbfLogger.debug { "=== Memory Domain analyzing ${b.getLabel()} ===\n$this\n" }
        if (listener is DefaultInstructionListener) {
            if (isBottom()) {
                return makeBottom(ptaGraph.nodeAllocator, scalars.sbfTypeFac)
            }
            val out = if (isNonOpForPTA(b)) {
                this.deepCopyOnlyScalars()
            } else {
                this.deepCopy()
            }
            out.checkConsistencyBetweenSubdomains(globals, "Before ${b.getLabel()}")
            for (locInst in b.getLocatedInstructions()) {
                out.analyze(b, locInst, globals, memSummaries)
                if (out.isBottom()) {
                    break
                }
            }
            return out
        } else {
            val out = if (isNonOpForPTA(b)) {
                this.deepCopyOnlyScalars()
            } else {
                this.deepCopy()
            }
            for (locInst in b.getLocatedInstructions()) {
                listener.instructionEventBefore(locInst, out)
                out.analyze(b, locInst, globals, memSummaries)
                listener.instructionEventAfter(locInst, out)
            }
            return out
        }
    }

    override fun getAsScalarValue(value: Value) = getScalars().getAsScalarValue(value)
    override fun getStackContent(offset: Long, width: Byte) = getScalars().getStackContent(offset, width)

    /** External API for TAC encoding **/
    fun getRegCell(reg: Value.Reg, globalsMap: GlobalVariableMap): PTASymCell? {
        val scalarVal = getScalars().getValue(reg)
        return getPTAGraph().getRegCell(reg, scalarVal.type(), globalsMap, locInst = null)
    }


    override fun toString(): String {
        return if (isBottom()) {
            "bottom"
        } else if (isTop()) {
            "top"
        } else {
            "Scalars=${scalars}\nPTA=${ptaGraph}"
        }
    }
}
