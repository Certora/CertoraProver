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
import sbf.support.SolanaInternalError
import com.certora.collect.*
import log.*
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

private val logger = Logger(LoggerTypes.SBF_MEMORY_ANALYSIS)
private fun dbg(msg: () -> Any) { logger.info(msg)}


const val enableDefensiveChecks = false

class MemoryDomainError(msg: String): SolanaInternalError("MemoryDomain error: $msg")

/** Special operations that [MemoryDomain] needs from the scalar domain **/
interface MemoryDomainScalarOps<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    fun setScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>)
    fun setStackContent(offset: Long, width: Byte, value: ScalarValue<TNum, TOffset>)
    /**
     * This function returns the scalar value for [reg] similar to `getAsScalarValue`.
     *
     * However, if the scalar value is a number then it tries to cast it to a pointer
     * in cases where that number is a known pointer address.
     */
    fun getAsScalarValueWithNumToPtrCast(reg: Value.Reg, globalsMap: GlobalVariableMap): ScalarValue<TNum, TOffset>
}

private typealias TScalarDomain<TNum, TOffset> = ScalarStackStridePredicateDomain<TNum, TOffset>

/**
 * Configuration options for [MemoryDomain] that should not be set globally via CLI.
 * Since the memory domain may be executed multiple times (e.g., during CPI lowering
 * or TAC generation), these options can be selectively enabled or disabled
 * depending on the specific use case.
 */
data class MemoryDomainOpts(
    // This option to be enabled only for CPI lowering.
    val useEqualityDomain: Boolean
)


/** Representation of a 256-bit `Pubkey` as four u64s. */
data class Pubkey(val word0: ULong, val word1: ULong, val word2: ULong, val word3: ULong)

class MemoryDomain<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>>(
    private val scalars: TScalarDomain<TNum, TOffset>,
    private val ptaGraph: PTAGraph<TNum, TOffset, Flags>,
    private val memcmpPreds: MemEqualityPredicateDomain<Flags>,
    private val opts: MemoryDomainOpts
    ) : AbstractDomain<MemoryDomain<TNum, TOffset, Flags>>, ScalarValueProvider<TNum, TOffset> {

    constructor(nodeAllocator: PTANodeAllocator<Flags>,
                sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
                opts: MemoryDomainOpts,
                initPreconditions: Boolean = false
    ) : this(TScalarDomain(sbfTypeFac, initPreconditions),
             PTAGraph(nodeAllocator, sbfTypeFac, initPreconditions),
             MemEqualityPredicateDomain(),
             opts)

    /**
     *  Check that the subdomains are consistent about the common facts that they infer.
     *  Currently, we only check that all registers point to the same (modulo some precision differences) stack offsets.
     **/
    private fun checkConsistencyBetweenSubdomains(msg:String) {
        if (!SolanaConfig.SanityChecks.get()) {
            return
        }
        if (isBottom()) {
            return
        }

        val scalars = getScalars()
        val ptaGraph = getPTAGraph()
        for (v in SbfRegister.values()) {
            val reg = Value.Reg(v)
            val type = scalars.getAsScalarValue(reg).type()
            val isStackScalar = type is SbfType.PointerType.Stack<TNum, TOffset>

            val c = ptaGraph.getRegCell(reg)
                ?: // it is possible that scalars say stack but ptaGraph doesn't know yet because the pointer has not
                // been de-referenced.
                continue

            val isStackGraph = c.getNode() == ptaGraph.getStack()
            if (isStackGraph && isStackScalar) {
                check(type is SbfType.PointerType.Stack<TNum, TOffset>)
                val scalarOffset = type.offset
                val pointerOffset = c.getOffset()
                if (scalarOffset.toLongOrNull() != pointerOffset.toLongOrNull()) {
                    throw MemoryDomainError(
                        "$msg: Scalars and PTAGraph should agree on $reg (1).\n" +
                            "Scalars=$scalars\nPTAGraph=$ptaGraph"
                    )
                }
            } else if (!isStackGraph && isStackScalar) {
                // if scalars says stack then ptaGraph says also stack because it shouldn't be less precise
                throw MemoryDomainError(
                    "$msg: Scalars and PTAGraph should agree on $reg (2).\n" +
                        "Scalars=$scalars\nPTAGraph=$ptaGraph"
                )
            } else if (isStackGraph) {
                if (!type.isTop()) { // ptaGraph can be more precise than scalars
                    throw MemoryDomainError(
                        "$msg: Scalars and PTAGraph should agree on $reg (3).\n" +
                            "Scalars=$scalars\nPTAGraph=$ptaGraph"
                    )
                }
            }
        }
    }

    override fun deepCopy(): MemoryDomain<TNum, TOffset, Flags> {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator, scalars.sbfTypeFac, opts)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph.copy(), memcmpPreds.deepCopy(), opts)
        }
    }

    private fun deepCopyOnlyScalars(): MemoryDomain<TNum, TOffset, Flags> {
        return if (isBottom()) {
            val res = MemoryDomain(ptaGraph.nodeAllocator, scalars.sbfTypeFac, opts)
            res.apply { setToBottom() }
        } else {
            MemoryDomain(scalars.deepCopy(), ptaGraph, memcmpPreds.deepCopy(), opts)
        }
    }


    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> initPreconditions(
            nodeAllocator: PTANodeAllocator<Flags>,
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            opts: MemoryDomainOpts
        ): MemoryDomain<TNum, TOffset, Flags> {
            return MemoryDomain(nodeAllocator, sbfTypeFac, opts, true)
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> makeBottom(
            nodeAllocator: PTANodeAllocator<Flags>,
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            opts: MemoryDomainOpts
        ): MemoryDomain<TNum, TOffset, Flags> {
            val res = MemoryDomain(nodeAllocator, sbfTypeFac, opts)
            return res.apply { setToBottom() }
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> makeTop(
            nodeAllocator: PTANodeAllocator<Flags>,
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>,
            opts: MemoryDomainOpts
        ): MemoryDomain<TNum, TOffset, Flags> {
            return MemoryDomain(nodeAllocator, sbfTypeFac, opts)
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
        memcmpPreds.setToBottom()
    }

    override fun setToTop() {
        scalars.setToTop()
        ptaGraph.reset()
        memcmpPreds.setToTop()
    }


    override fun forget(reg: Value.Reg) {
        if (!isBottom()) {
            scalars.forget(reg)
            ptaGraph.forget(reg)
            if (opts.useEqualityDomain) {
                memcmpPreds.forget(reg)
            }
        }
    }

    private fun joinOrWiden(other: MemoryDomain<TNum, TOffset, Flags>, isJoin: Boolean,
                            left: Label?, right: Label?): MemoryDomain<TNum, TOffset, Flags> {
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
            val outMemcmpPreds = if (isJoin) {
                        memcmpPreds.join(other.memcmpPreds, left, right)
                    } else {
                        memcmpPreds.widen(other.memcmpPreds, left)
                    }

            return MemoryDomain(outScalars, outPtaGraph, outMemcmpPreds, opts)
        }
    }

    override fun pseudoCanonicalize(other: MemoryDomain<TNum, TOffset, Flags>) {
        if (!isBottom() && !other.isBottom()) {
            ptaGraph.pseudoCanonicalize(other.getPTAGraph())
            scalars.pseudoCanonicalize(other.scalars)
            memcmpPreds.pseudoCanonicalize(other.memcmpPreds)
        }
    }

    override fun join(other: MemoryDomain<TNum, TOffset, Flags>, left: Label?, right: Label?): MemoryDomain<TNum, TOffset, Flags> {
        return joinOrWiden(other, true, left, right)
    }

    override fun widen(other: MemoryDomain<TNum, TOffset, Flags>, b: Label?): MemoryDomain<TNum, TOffset, Flags> {
        return joinOrWiden(other, false, b, null)
    }

    override fun lessOrEqual(other: MemoryDomain<TNum, TOffset, Flags>, left: Label?, right: Label?): Boolean {
        return if (other.isTop() || isBottom()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
                scalars.lessOrEqual(other.scalars, left, right) &&
                ptaGraph.lessOrEqual(other.ptaGraph, left, right) &&
                memcmpPreds.lessOrEqual(other.memcmpPreds)
        }
    }

    fun getPTAGraph(): PTAGraph<TNum, TOffset, Flags> = ptaGraph

    @TestOnly fun getScalars() = scalars

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
            ptaGraph.doUn(locInst, globals, memSummaries)
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
            val offsets = (scalars.getAsScalarValue(reg).type() as? SbfType.PointerType.Stack<TNum, TOffset>)?.offset?.toLongList()
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
     * Set the value of [reg] to [newVal] only if its old value is top
     * Return true if the scalar value has been updated.
     **/
    private fun refineScalarValue(reg: Value.Reg, newVal: ScalarValue<TNum, TOffset>): Boolean {
        val oldVal = scalars.getAsScalarValue(reg)
        if (oldVal.isTop() && !newVal.isTop()) {
            scalars.setScalarValue(reg, newVal)
            return true
        }
        return false
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
                    val change = refineScalarValue(reg, ScalarValue(scalars.sbfTypeFac.anyNum()))
                    if (change) {
                        val topNum =  scalars.sbfTypeFac.anyNum().concretize()
                        check(topNum != null) {"concretize on anyNum cannot be null"}
                        /// HACK: changing metadata serves here as caching the reduction.
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
                    refineScalarValue(reg, ScalarValue(scalars.sbfTypeFac.anyNum()))
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
        val dstType = scalars.getAsScalarValue(dst).type()
        scalars.analyze(locInst, globals, memSummaries)
        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            val srcType = scalars.getAsScalarValue(src).type()
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

        // In the case of a load instruction where base register and lhs are the same register, we need to remember
        // the scalar value of the base before it might have been overwritten.
        val baseType = scalars.getAsScalarValueWithNumToPtrCast(stmt.access.baseReg, globals).type()

        scalars.analyze(locInst, globals, memSummaries)

        if (scalars.isBottom()) {
            setToBottom()
        } else  {
            check(!baseType.isBottom()) { "Unexpected bottom scalar value at $stmt" }
            val base = stmt.access.baseReg
            val isLoad = stmt.isLoad
            if (isLoad) {
                ptaGraph.doLoad(locInst, base, baseType, globals, scalars)
            } else {
                val value = stmt.value
                val valueType = scalars.getAsScalarValue(value).type()
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
        dbg { "$inst\n" }
        if (!isBottom()) {
            if (opts.useEqualityDomain) {
                memcmpPreds.analyze(locInst, this, globals, memSummaries)
            }

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
        dbg {"$this\n"}
    }

    override fun analyze(b: SbfBasicBlock,
                         globals: GlobalVariableMap,
                         memSummaries: MemorySummaries,
                         listener: InstructionListener<MemoryDomain<TNum, TOffset, Flags>>): MemoryDomain<TNum, TOffset, Flags> {


        dbg { "=== Memory Domain analyzing ${b.getLabel()} ===\n$this\n" }
        if (listener is DefaultInstructionListener) {
            if (isBottom()) {
                return makeBottom(ptaGraph.nodeAllocator, scalars.sbfTypeFac, opts)
            }
            val out = if (isNonOpForPTA(b)) {
                this.deepCopyOnlyScalars()
            } else {
                this.deepCopy()
            }

            for (locInst in b.getLocatedInstructions()) {
                out.checkConsistencyBetweenSubdomains("Before $locInst")
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
                out.checkConsistencyBetweenSubdomains("Before $locInst")
                out.analyze(b, locInst, globals, memSummaries)
                listener.instructionEventAfter(locInst, out)
            }
            return out
        }
    }

    override fun getAsScalarValue(value: Value) = getScalars().getAsScalarValue(value)
    override fun getStackContent(offset: Long, width: Byte) = getScalars().getStackContent(offset, width)

    /** External API for TAC encoding **/
    fun getRegCell(reg: Value.Reg, globalsMap: GlobalVariableMap): PTASymCell<Flags>? {
        val scalarVal = getScalars().getAsScalarValue(reg)
        return getPTAGraph().getRegCell(reg, scalarVal.type(), globalsMap, locInst = null)
    }

    /**
     * Return a [Pubkey] stored at `*([reg] + [offset])`, or null if the domain cannot be sure.
     */
    fun getPubkey(reg: Value.Reg, offset: Long, globalsMap: GlobalVariableMap): Pubkey? {
        return getPubkeyFromMemEqDomain(reg, offset, globalsMap)
            ?: getPubkeyFromPtrDomain(reg, offset, globalsMap)
    }

    /**
     * Use the [MemEqualityPredicateDomain] to get a [Pubkey] stored at `*([reg] + [offset])`.
     *
     * Return `null` if the domain cannot know that there is a [Pubkey] stored there.
     */
    private fun getPubkeyFromMemEqDomain(
        reg: Value.Reg,
        offset: Long,
        globalsMap: GlobalVariableMap
    ): Pubkey ? =
        getPubkey(reg, globalsMap) { c, i ->
            // size = 32 because that's the size of Pubkey in bytes
            // stride = 8 because the Pubkey was recovered by [MemoryEqualityDomain] from either
            // (1) load instructions of 8 bytes or through (2) `memcmp` instructions
            val pred = memcmpPreds.get(
                c.getNode(),
                start = c.getOffset().v + offset,
                stride = 8,
                size = 32
            ) ?: return@getPubkey null
            val (value, isEqual) = pred.values[8L * i] ?: return@getPubkey null
            if (!isEqual) {
                null
            } else {
                value.toULong()
            }
        }

    /**
     * Use the [PTAGraph] (pointer domain) to get a [Pubkey] stored at `*([reg] + [offset])`.
     *
     * Return `null` if the domain cannot know that there is a [Pubkey] stored there.
     */
    private fun getPubkeyFromPtrDomain(
        reg: Value.Reg,
        offset: Long,
        globalsMap: GlobalVariableMap
    ): Pubkey? =
        getPubkey(reg, globalsMap) { c, i ->
            if (!c.getNode().isExactNode()) {
                return@getPubkey null
            }
            val startOffset = c.getOffset() + offset
            val field = PTAField(startOffset + (8 * i), 8)
            val chunkCell = c.getNode().getSucc(field) ?: return@getPubkey null
            val chunkNode = chunkCell.getNode()
            if (!chunkNode.mustBeInteger()) {
                return@getPubkey null
            }
            chunkNode.flags.getInteger().toLongOrNull()?.toULong()
        }


    private fun getPubkey(
        reg: Value.Reg,
        globalsMap: GlobalVariableMap,
        wordExtractor: (c: PTACell<Flags>, index: Int) -> ULong?
    ): Pubkey? {
        val sc = getRegCell(reg, globalsMap) ?: return null
        if (!sc.isConcrete()) {
            return null
        }
        val c = sc.concretize()
        val words = mutableListOf<ULong>()
        for (i in 0 until 4) {
            val word = wordExtractor(c, i) ?: return null
            words.add(word)
        }
        return Pubkey(words[0], words[1], words[2], words[3])
    }

    override fun toString(): String {
        return if (isBottom()) {
            "bottom"
        } else if (isTop()) {
            "top"
        } else {
            "Scalars=$scalars\nPTA=$ptaGraph\nMemcmpPreds=$memcmpPreds"
        }
    }
}
