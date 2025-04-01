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

class MemoryDomain(private val scalars: ScalarDomain,
                   private val ptaGraph: PTAGraph): AbstractDomain<MemoryDomain> {

    constructor(nodeAllocator: PTANodeAllocator, initPreconditions: Boolean = false)
        : this(ScalarDomain(initPreconditions), PTAGraph(nodeAllocator, initPreconditions))

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
        val c = ptaGraph.getRegCell(r10, SbfType.Top /*shouldn't be used*/, globals, locInst = null)
        check(c != null)
        {"$msg: pointer domain should know about r10"}
        if (c.node.isExactNode()) {
            // Get value for r10 in Scalars
            val type = scalars.getValue(r10).get()
            check(type is SbfType.PointerType.Stack)
            {"$msg: scalar domain should know that r10 is a pointer to the stack"}
            val scalarOffset = type.offset
            val pointerOffset = c.offset
            // Since r10 is read-only, both subdomains should agree on the same offset for r10
            check(scalarOffset.get() == pointerOffset.get())
            { "$msg: scalar and pointer domains should agree on r10 offset" }
        }
    }

    override fun deepCopy(): MemoryDomain {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph.copy())
        }
    }

    private fun deepCopyOnlyScalars(): MemoryDomain {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph)
        }
    }


    companion object {
        fun initPreconditions(nodeAllocator: PTANodeAllocator): MemoryDomain {
            return MemoryDomain(nodeAllocator, true)
        }

        fun makeBottom(nodeAllocator: PTANodeAllocator): MemoryDomain {
            val res = MemoryDomain(nodeAllocator)
            return res.apply { setToBottom() }
        }

        fun makeTop(nodeAllocator: PTANodeAllocator): MemoryDomain {
            return MemoryDomain(nodeAllocator)
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

    private fun joinOrWiden(other: MemoryDomain, isJoin: Boolean,
                            left: Label?, right: Label?): MemoryDomain {
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
                        ptaGraph.join(other.ptaGraph, scalars, other.scalars, left, right)
                    } else {
                        ptaGraph.widen(other.ptaGraph, left)
                    }

            return MemoryDomain(outScalars, outPtaGraph)
        }
    }

    override fun pseudoCanonicalize(other: MemoryDomain) {
        if (!isBottom() && !other.isBottom()) {
            ptaGraph.pseudoCanonicalize(other.getPTAGraph())
            scalars.pseudoCanonicalize(other.scalars)
        }
    }

    override fun join(other: MemoryDomain, left: Label?, right: Label?): MemoryDomain {
        return joinOrWiden(other, true, left, right)
    }

    override fun widen(other: MemoryDomain, b: Label?): MemoryDomain {
        return joinOrWiden(other, false, b, null)
    }

    override fun lessOrEqual(other: MemoryDomain, left: Label?, right: Label?): Boolean {
        return if (other.isTop() || isBottom()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
            (scalars.lessOrEqual(other.scalars, left, right) && ptaGraph.lessOrEqual(other.ptaGraph, left, right))
        }
    }

    fun getPTAGraph(): PTAGraph = ptaGraph

    @TestOnly fun getScalars(): ScalarDomain = scalars

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

    private fun reductionFromPtaGraphToScalars(b: SbfBasicBlock, locInst: LocatedSbfInstruction, reg: Value) {
        if (reg is Value.Reg) {
            val x = ptaGraph.getRegCell(reg)
            if (x != null && x.isReified()) {
                val c = x.reify()
                if (c.node.mustBeInteger()) {
                    val change = scalars.refineValue(reg, ScalarValue.anyNum())
                    if (change) {
                        /// Changing metadata serves here as caching the reduction.
                        val newMetadata = locInst.inst.metaData.plus(
                            Pair(SbfMeta.REG_TYPE, Pair(reg, SbfType.NumType(Constant.makeTop())))
                        )
                        val newInst = locInst.inst.copyInst(metadata = newMetadata)
                        (b as MutableSbfBasicBlock).replaceInstruction(locInst.pos, newInst)
                    }
                    return
                }
            }

            /// If the analysis previously determined that `reg` is a number then we keep using that fact,
            /// even if the pointer analysis lost precision and cannot infer that fact anymore.
            locInst.inst.metaData.getVal(SbfMeta.REG_TYPE)?.let { (refinedReg, type) ->
                if (refinedReg == reg && type is SbfType.NumType) {
                    scalars.refineValue(reg, ScalarValue.anyNum())
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
        val dstType = scalars.getValue(dst).get()
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            val srcType = scalars.getValue(src).get()
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

    private fun analyzeMem(locInst: LocatedSbfInstruction,
                           globals: GlobalVariableMap,
                           @Suppress("UNUSED_PARAMETER") memSummaries: MemorySummaries) {
        check(!isBottom()) {"called analyzeMem on bottom in memory domain"}
        val stmt = locInst.inst
        check(stmt is SbfInstruction.Mem) {"Memory domain expects a memory instruction instead of $stmt"}
        val baseRegTypeBeforeKilled = scalars.analyzeMem(locInst, globals)
        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            val baseReg = stmt.access.baseReg
            val offset = stmt.access.offset
            val width = stmt.access.width
            val value = stmt.value
            val isLoad = stmt.isLoad
            // We ask for the type of baseReg after the transfer function of the
            // scalar domain has been executed, because it can refine the abstract value
            // of baseReg (e.g., implicit cast from an integer to a pointer)
            if (isLoad) {
                val baseRegType = baseRegTypeBeforeKilled?.get() ?: scalars.getValue(baseReg).get()
                ptaGraph.doLoad(locInst, value as Value.Reg, baseReg, offset, width, baseRegType, globals)
            } else {
                val baseRegType = scalars.getValue(baseReg).get()
                val valueType = scalars.getValue(value).get()
                ptaGraph.doStore(locInst, baseReg, offset, width, value, baseRegType, valueType, globals)
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
                         listener: InstructionListener<MemoryDomain>): MemoryDomain {


        sbfLogger.debug { "=== Memory Domain analyzing ${b.getLabel()} ===\n$this\n" }
        if (listener is DefaultInstructionListener) {
            if (isBottom()) {
                return makeBottom(ptaGraph.nodeAllocator)
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

    override fun getValue(value: Value) = getScalars().getValue(value)

    /** External API for TAC encoding **/
    fun getRegCell(reg: Value.Reg, globalsMap: GlobalVariableMap): PTASymCell? {
        val scalarVal = getScalars().getValue(reg)
        return getPTAGraph().getRegCell(reg, scalarVal.get(), globalsMap, locInst = null)
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
