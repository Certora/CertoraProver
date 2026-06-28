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
import sbf.analysis.*
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.inliner.SBF_CALL_MAX_DEPTH
import sbf.support.SolanaInternalError
import com.certora.collect.*
import datastructures.stdcollections.*
import sbf.cfg.SbfMeta.SBF_DWARF_DEBUG_ANNOTATIONS
import sbf.domains.*
import sbf.dwarf.DWARFEdgeLabelAnnotator
import tac.BlockIdentifier
import tac.NBId
import tac.StartBlock
import tac.Tag
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.MutableBlockGraph
import vc.data.Procedure
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.TACSymbolTable
import vc.data.plusMetaMap
import vc.data.tacexprutil.asSym
import java.math.BigInteger

// This number should be bigger than the number of Assert commands inserted by any TAC optimization (e.g., loop unroller),
// by all rules executed in the same run.
const val RESERVED_NUM_OF_ASSERTS = 100_000

class TACTranslationError(msg: String): SolanaInternalError("TAC translation error: $msg")

/**
 *  Encoding of an SBF program to a TAC program
 *
 *  Both stack and non-stack memory are encoded using "wide" bytes. A __wide__ byte is like a normal byte, but it can contain
 *  a number bigger than a byte. In our case, the number of bytes is fixed to 64 (256 bits).
 *
 *  The use of wide bytes is needed in order to model precisely memcpy.
 *  Usually, a program under verification starts with non-deterministic memory that can
 *  be copied (by memcpy) many times until it is finally de-referenced.
 *  The use of wide bytes allows us to copy all bytes without knowing a-priori how it will be accessed.
 *  The pointer analysis (PTA) try to check that wide bytes are accessed in a sound way (i.e, no aliasing due to overlaps).
 *
 *  There are several important considerations:
 *
 *  - 1) In TAC, we only have available 256-bit integers, but SBF uses 64-bit integers.
 *  Thus, the SBF-to-TAC translation needs to consider the semantic gap between the two.
 *  This part of the encoding is done by [SbfTACBuilder] and its subclasses.
 *
 *  - 2) we use `ByteMap` to represent non-stack memory. A ByteMap is just a map from Int to Int.
 *  This means that we need to be careful with aliasing due to overlaps. Currently, the pointer analysis does *not* check this.
 *
 *  - 3) TAC encoding of `memcmp` and `memset` is tricky, when one operand is a ByteMap and the other is on the stack.
 *  We fix a priori a word size and perform a sequence of ByteLoad instructions.
 *  For this to be sound, we need to remember which memory regions were compared using
 *  a fixed word size and then to port all memory accesses to those regions to be word-addressable.
 *  This is *not* currently implemented.
 *
 *  @param [program] the callgraph of the program. Only the root of the callgraph is translated to TAC.
 *  @param [globalAnalysisResults] if null then no memory splitting will be done.
 **/
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> sbfCFGsToTAC(
    program: SbfCallGraph,
    memSummaries: MemorySummaries,
    globalAnalysisResults: Map<String, MemoryAnalysis<TNum, TOffset, TFlags>>?
): CoreTACProgram {
    val cfg = program.getCallGraphRootSingleOrFail()
    if (cfg.getBlocks().isEmpty()) {
        throw SolanaInternalError("The translation from SBF to TAC failed because the SBF CFG is empty")
    }

    val analysis = if (globalAnalysisResults == null) {
        null
    } else {
        globalAnalysisResults[cfg.getName()]
            ?: throw TACTranslationError("Not analysis results found for ${cfg.getName()}")
    }
    val marshaller = SbfCFGToTAC(cfg, program.getGlobals(), memSummaries, analysis)
    return marshaller.encode()
}

/** Translate an SBF CFG to a TAC program **/
@Suppress("ForbiddenComment")
internal class SbfCFGToTAC<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>(
    private val cfg: SbfCFG,
    val globals: GlobalVariables,
    private val memSummaries: MemorySummaries,
    val memoryAnalysis: MemoryAnalysis<TNum, TOffset, TFlags>?
): TACDebugView {
    private val blockMap: MutableMap<Label, NBId> = mutableMapOf()
    private val blockGraph = MutableBlockGraph()
    private val code: MutableMap<NBId, List<TACCmd.Simple>> = mutableMapOf()
    // For creating TAC expressions from SBF expressions
    val sbfTacB: SbfTACBuilder
    // For creating TAC expressions from NativeInt
    val natIntTacB: NativeIntTACBuilder
    // Factory for creating TACSymbol.Var
    val vFac = TACVariableFactory<TFlags>(globals.elf.useDynamicFrames())
    // Symbolic memory allocators
    val heapMemAlloc = TACBumpAllocator("TACHeapAllocator", SBF_HEAP_START.toULong(), SBF_HEAP_END.toULong())
    val accountsAlloc = TACFixedSizeBlockAllocator("TACSolanaAccountAllocator", SBF_INPUT_START.toULong(), MAX_SOLANA_ACCOUNTS.toUShort(), SOLANA_ACCOUNT_SIZE.toULong())
    // Since the input region is large enough we use it also to allocate memory that other external functions might allocate
    val extMemAlloc = TACBumpAllocator("TACExternalAllocator", SBF_EXTERNAL_START.toULong() , SBF_INPUT_END.toULong())
    // Map a de-referenced pointer to a symbolic variable.
    // The memory analysis guarantees that all pointers that might alias will be mapped to same
    // symbolic variable.
    val mem: TACMemSplitter
    // Internal counters
    private var blockId: Int = 1
    private var satisfyId: Int = 0
    // Start from a large number to avoid clashes with satisfy inserted by TAC optimizations
    private var assertId: Int = RESERVED_NUM_OF_ASSERTS
    // Only for printing user warnings
    // Unsupported calls. We just keep track of them to reduce the number of user warnings
    val unsupportedCalls: MutableSet<String> = mutableSetOf()
    val functionArgInference = FunctionArgumentInference(cfg)
    // We need type information about registers and stack contents.
    // It's much cheaper to analyze the whole cfg from scratch with a ScalarAnalysis and rebuild invariants at the
    // instruction level than rebuilding invariants at the instruction level with [memoryAnalysis]
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val types: IRegisterTypes<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>
    // Stack of scratch registers
    val scratchRegVars: ArrayList<TACSymbol.Var> = arrayListOf()
    // To model clock syscalls
    val clock: Clock = Clock { prefix -> vFac.mkFreshIntVar(prefix = prefix) }
    // To model rent syscalls
    val rent: Rent = Rent { prefix -> vFac.mkFreshIntVar(prefix = prefix) }
    // To instrument Solana account accesses.
    val accounts: TACSolanaAccountAccess
    // To summarize u128 intrinsics
    val u128Summarizer: U128Summarizer = U128Summarizer { prefix -> vFac.mkFreshIntVar(prefix = prefix) }
    // To improve TAC encoding
    val isPointerAnalysis: IsPointerAnalysis<*,*,*>

    init {
        val scalarAnalysis = GenericScalarAnalysis(
            cfg,
            globals,
            memSummaries,
            sbfTypesFac,
            MemoryScalarDomFac()
        )

        types = AnalysisRegisterTypes(scalarAnalysis)

        val regVars: ArrayList<TACSymbol.Var> = ArrayList(NUM_OF_SBF_REGISTERS)
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            regVars.add(vFac.getRegisterVar(i))
        }

        sbfTacB = if (SolanaConfig.TACSoundSignedMath.get()) {
            EagerMaskSbfTACBuilder(regVars)
        } else {
            LazyMaskSbfTACBuilder(regVars)
        }

        natIntTacB = NativeIntTACBuilder(regVars)

        mem = when (SolanaConfig.memorySplitter()) {
            MemorySplitter.PTA ->
                if (memoryAnalysis != null) {
                    PTAMemSplitter(cfg, vFac, memoryAnalysis)
                } else {
                    DummyMemSplitter(vFac, types)
                }
            MemorySplitter.Scalar       -> ScalarMemSplitter(cfg, vFac, sbfTacB, types, memSummaries, useSingleNonStackMap = false)
            MemorySplitter.ScalarSingle -> ScalarMemSplitter(cfg, vFac, sbfTacB, types, memSummaries, useSingleNonStackMap = true)
            MemorySplitter.Dummy        -> DummyMemSplitter(vFac, types)
        }

        isPointerAnalysis = IsPointerAnalysis(scalarAnalysis)

        accounts = TACSolanaAccountAccess(cfg) { prefix ->
            vFac.mkFreshBoolVar(prefix = prefix)
        }
    }

    private fun mkBlockIdentifier(bb: SbfBasicBlock, isStart: Boolean): NBId {
        // The entry block of the CFG must be `StartBlock`
        val tacBB = if (isStart) {
            StartBlock
        } else {
            // We use `stkTop = 1` to avoid classes with Allocator.getNBId()
            BlockIdentifier(blockId++, stkTop = 1, 0, 0, 0, 0)
        }
        blockGraph[tacBB] = treapSetOf()
        code[tacBB] = mutableListOf()
        blockMap[bb.getLabel()] = tacBB
        return tacBB
    }

    private fun removeBlockIdentifier(label: Label) {
        val tacBB = blockMap[label]
        if (tacBB != null ){
            blockGraph.remove(tacBB)
            code.remove(tacBB)
            blockMap.remove(label)
        }
    }

    private fun getBlockIdentifier(bb: SbfBasicBlock): NBId {
        val label = bb.getLabel()
        check(blockMap.contains(label)) {"getBlockIdentifier failed on $label\n\t$bb"}
        val tacBB = blockMap[label]
        check(tacBB != null)
        return tacBB
    }

    private fun mkFreshAssertId(): Int {
        assertId++
        return assertId
    }

    private fun mkFreshSatisfyId(): Int {
        satisfyId++
        return satisfyId
    }

    private fun addInitialPreconditions(): List<TACCmd.Simple> {
        val b = vFac.mkFreshBoolVar()
        return listOf(
            assign(b,
                CondOp.EQ(
                    SbfRegister.R10,
                    SBF_STACK_START + getInitialStackOffset(globals.elf.useDynamicFrames()),
                    sbfTacB
                )
            )) + assume(b.asSym(), "InitialPreconditions")
    }

    private fun addGlobalInitializers(): List<TACCmd.Simple> {
        val initializers = runGlobalInitializationAnalysis(cfg, types, globals.elf)
        val cmds = mutableListOf<TACCmd.Simple>()
        for ( (gv, _, stride, locInst, reg, values) in initializers) {
            val inst = locInst.inst
            cmds += Debug.startFunction("init_${gv.name}")
            val byteMap = when (inst) {
                is SbfInstruction.Mem -> {
                    val info = mem.getTACMemory(locInst)
                    checkNotNull(info) {"addGlobalInitializers cannot get PTA info from $inst"}
                    check(info is TACMemSplitter.NonStackLoadOrStoreInfo) {"addGlobalInitializers expects a byte map at $inst"}
                    val target = info.variable
                    check(target is TACMemSplitter.ByteMapTarget.Base) {
                        "addGlobalInitializers expects a single ByteMap, not an ite target, at $inst"
                    }
                    target.v
                }
                is SbfInstruction.Call -> {
                    check(inst.name == SolanaFunction.SOL_MEMCMP.syscall.name)
                    val info = mem.getTACMemoryFromMemIntrinsic(locInst)
                    checkNotNull(info) {"addGlobalInitializers cannot get PTA info from $inst"}
                    val opTarget = when(info) {
                        is TACMemSplitter.NonStackMemCmpInfo -> {
                            // memcmp when both src and destination are non-stack and thus, they are both modeled as ByteMap
                            if (reg == SbfRegister.R1) { info.op1 } else { info.op2 }
                        }
                        is TACMemSplitter.MixedRegionsMemCmpInfo -> {
                            // memcmp when one (src or destination) is non-stack and the other stack. The non-stack operand
                            // is modeled as ByteMap.
                            check(info.byteMapReg == reg)
                            info.byteMap
                        }
                        else -> throw TACTranslationError("addGlobalInitializers expects a byte map at $inst")
                    }
                    check(opTarget is TACMemSplitter.ByteMapTarget.Base) {
                        "addGlobalInitializers expects a single ByteMap, not an ite target, at $inst"
                    }
                    opTarget.v
                }
                else -> throw TACTranslationError("addGlobalInitializers: unexpected instruction $inst")
            }
            val locVar = vFac.mkFreshIntVar()
            cmds += assign(locVar, sbfTacB.mkConst(gv.address).asSym())
            val offsets = List(values.size) { index -> PTAOffset((index * stride).toLong())  }
            val storedValues = values.map { sbfTacB.mkConst(it)}
            cmds += mapStores(byteMap, locVar, offsets, storedValues)
            cmds += Debug.endFunction("init_${gv.name}")
        }
        return cmds
    }

    @JvmName("inLongRange")
    private fun inRange(v: TACSymbol.Var, range: OpenEndRange<Long>, isUnsigned: Boolean = true) =
        inRange(v, range.start.toBigInteger() ..< range.endExclusive.toBigInteger(), isUnsigned)

    /**
     * Emit TAC code for `assume(lb <= v < ub)`
     * - If isUnsigned=true then unsigned comparison
     * - otherwise signed comparison
     **/
    fun inRange(v: TACSymbol.Var, range: ClosedRange<BigInteger>, isUnsigned: Boolean = true): List<TACCmd.Simple>{
        return if (isUnsigned) {
            assume(CondOp.GE(v.asSym(), range.start, sbfTacB), "inRange LB") +
                assume(CondOp.LE(v.asSym(), range.endInclusive, sbfTacB), "inRange UB")
        } else {
            assume(CondOp.SGE(v.asSym(), range.start, sbfTacB), "inRange LB") +
                assume(CondOp.SLE(v.asSym(), range.endInclusive, sbfTacB), "inRange UB")
        }
    }

    fun inRange(v: TACSymbol.Var, range: OpenEndRange<BigInteger>, isUnsigned: Boolean = true): List<TACCmd.Simple> {
        check(range.endExclusive > range.start) { "inRange got empty open-end range $range" }
        return inRange(v, range.start .. (range.endExclusive - BigInteger.ONE), isUnsigned)
    }

    /**
     *  Add extra assumptions based on memory layout:
     *  ```
     *        ---------------------------------------------------------------------
     *       |      CODE    |       STACK        |      HEAP    |  INPUT           |
     *        ---------------------------------------------------------------------
     *       0x100000000    0x200000000          0x30000000     0x40000000
     *  ```
     **/
    fun addMemoryLayoutAssumptions(
        ptr: TACSymbol.Var,
        region: SbfType<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>?
    ): List<TACCmd.Simple> {
        if (!SolanaConfig.AddMemLayoutAssumptions.get()) {
            return listOf()
        }

        if (globals.elf.useDynamicFrames()) {
            return listOf()
        }

        if (region is SbfType.NumType) {
            return listOf()
        }

        if (region is SbfType.PointerType.Global) {
            // Is there a known range of addresses for global variables?
            return listOf()
        }

        val lb = if (region is SbfType.PointerType) {
            when (region) {
                is SbfType.PointerType.Stack -> SBF_STACK_START
                is SbfType.PointerType.Input -> SBF_INPUT_START
                else -> {
                    check(region is SbfType.PointerType.Heap)
                    SBF_HEAP_START
                }
            }
        } else {
            // REVISIT: global variables have lower addresses than SBF_CODE_START
            //SBF_CODE_START
            0L
        }

        val ub = if (region is SbfType.PointerType) {
            when (region) {
                is SbfType.PointerType.Stack -> {
                    SBF_STACK_START +  (SBF_STACK_FRAME_SIZE * SBF_CALL_MAX_DEPTH)
                }
                is SbfType.PointerType.Input -> {
                    SBF_INPUT_END
                }
                else -> {
                    check(region is SbfType.PointerType.Heap)
                    SBF_HEAP_END
                }
            }
        } else {
            SBF_INPUT_END
        }

        return inRange(ptr, lb ..< ub)
    }

    private fun translateBin(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)
        val lhs = inst.dst
        val rhs = inst.v
        return if (inst.op == BinOp.MOV) {
            listOf(assign(sbfTacB.mkVar(lhs), sbfTacB.mkExprSym(rhs)))
        } else {
            if (!inst.is64) {
                throw TACTranslationError("TAC encoding of 32-bit $inst not supported")
            }
            val op1 = sbfTacB.mkVar(inst.dst)
            if (!SolanaConfig.TACSoundSignedMath.get() && // TODO CERT-10061 revisit this
                SolanaConfig.UseTACMathInt.get() &&
                inst.metaData.getVal(SbfMeta.SAFE_MATH) != null) {
                // Currently, `SAFE_MATH` annotations are only used for addition/subtraction before checking for overflow.
                // These operations must be done on MathInt.

                val x = vFac.mkFreshMathIntVar()
                val y = vFac.mkFreshMathIntVar()
                val z = vFac.mkFreshMathIntVar()

                listOf(
                    when (rhs) {
                        is Value.Reg -> {
                            promoteToMathInt(sbfTacB.mkVar(rhs).asSym(), y)
                        }
                        is Value.Imm -> {
                            // We cannot use `mkConst` because if the immediate value is a negative one it will sign extended to 256 bits,
                            // and this is incorrect using MathInt.
                            assign(y, TACSymbol.Const(rhs.v.toLong().toBigInteger(), Tag.Int).asSym())
                        }
                    },
                    promoteToMathInt(op1.asSym(), x),
                    assign(z, inst.op(x.asSym(), y.asSym(), useMathInt = true, sbfTacB)),
                    narrowFromMathInt(z.asSym(), op1)
                )
            } else {
                val op2 = sbfTacB.mkExprSym(rhs)
                // We assume pointer addition never overflows
                val expr = if (inst.op == BinOp.ADD && isPointerAnalysis.isPointerOp(locInst)) {
                    sbfTacB { op1.asSym().addNoOvf(op2, "pointer addition at $locInst") }
                } else {
                    inst.op(op1.asSym(), op2, useMathInt = false, sbfTacB)
                }
                listOf(assign(op1, expr))
            }
        }
    }

    private fun translateUn(inst: SbfInstruction.Un): List<TACCmd.Simple> {
        val lhs = sbfTacB.mkVar(inst.dst)
        return when (inst.op) {
            UnOp.NEG -> listOf(assign(lhs, UnOp.NEG(inst.dst, sbfTacB)))
            UnOp.BE16,
            UnOp.BE32,
            UnOp.BE64,
            UnOp.LE16,
            UnOp.LE32,
            UnOp.LE64 ->  {
                // We don't model precisely byte swap instructions
                listOf(
                    Debug.unsupported("Unsupported $inst: havoc lhs", listOf(lhs)),
                    havoc(lhs)
                )
            }
        }
    }

    private fun translateSelect(inst: SbfInstruction.Select): List<TACCmd.Simple> {
        val overflowCond = inst.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK)

        return if (sbfTacB.needOverflowPromotion && overflowCond != null) {
            // This is another 64 vs 256-bit arithmetic fix. See comments from `translateJump`
            val overflowCondTac = translateOverflowCond(overflowCond)
            val overflowVar = overflowCondTac.getRhs().filterIsInstance<TACSymbol.Var>().single()
            val cmds = mutableListOf(
                Debug.externalCall("overflow_check"),
                overflowCondTac,
                assign(sbfTacB.mkVar(inst.dst),
                    sbfTacB {
                        ite(overflowCondTac.lhs.toTACExpr(), mkExprSym(inst.trueVal), mkExprSym(inst.falseVal))
                    }
                )
            )
            cmds += assign(overflowVar, sbfTacB.mask64(overflowVar.asSym()))
            cmds
        } else {
            val condCmd = translateCond(inst.cond)
            listOf(
                condCmd,
                assign(sbfTacB.mkVar(inst.dst),
                    sbfTacB {
                        ite(condCmd.lhs.toTACExpr(), mkExprSym(inst.trueVal), mkExprSym(inst.falseVal))
                    }
                )
            )
        }
    }

    private fun translateHavoc(inst: SbfInstruction.Havoc): List<TACCmd.Simple> =
        listOf(havoc(sbfTacB.mkVar(inst.dst)))

    /**
     *  In SBF, the exit command does not have parameter.
     *  Here we create a return instruction that returns r0.
     */
    private fun translateExit(): List<TACCmd.Simple> =
        listOf(TACCmd.Simple.ReturnSymCmd(sbfTacB.mkVar(SbfRegister.R0)))

    private fun translateCond(cond: Condition): TACCmd.Simple.AssigningCmd {
        val left = cond.left
        val right = cond.right

        val tacLhs = vFac.mkFreshBoolVar()
        val leftE = sbfTacB.mkExprSym(left)
        val rightE = sbfTacB.mkExprSym(right)

        val tacRhs = cond.op(leftE, rightE, sbfTacB)
        return assign(tacLhs, tacRhs)
    }

    /**
     * Translate an overflow condition of the form `left > ULong.MAX_VALUE` into TAC.
     *
     * This cannot use [translateCond] because `mkExprSym` converts `ULong.MAX_VALUE` to -1 as a 256-bit value.
     * The condition `left > -1` would be vacuously false (since `>` is unsigned and -1 is the maximum unsigned 256-bit value),
     * so the overflow check would never fire.
     *
     * Instead, the right-hand side is `TACExprBuilder.mask64` = `0xFFFF_FFFF_FFFF_FFFF` as a positive
     * 256-bit constant, so `left > mask64` correctly detects that the result exceeded 64 bits.
     */
    private fun translateOverflowCond(cond: Condition): TACCmd.Simple.AssigningCmd  {
        check(cond.op == CondOp.GT && (cond.right as? Value.Imm)?.v == ULong.MAX_VALUE)
        val lhs = vFac.mkFreshBoolVar()
        return assign(lhs, sbfTacB { mkExprSym(cond.left) gt sbfTacB.mask64.asSym })
    }

    /** Return true if [locInst] is an Assume instruction and its condition is evaluated semantically to true **/
    private fun isTautology(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst
        check(inst is SbfInstruction.Assume) {"isTautology expects an assume instruction instead of $inst"}

        val left = inst.cond.left
        val right = inst.cond.right
        val op = inst.cond.op

        val leftTy = types.typeAtInstruction(locInst, left.r)
        if (leftTy is SbfType.NumType) {
            val leftVal = leftTy.value
            val rightVal = when(right) {
                is Value.Reg ->  {
                    val rightTy = types.typeAtInstruction(locInst, right.r)
                    if (rightTy is SbfType.NumType) {
                        rightTy.value
                    } else {
                        null
                    }
                }
                is Value.Imm -> {
                    sbfTypesFac.toNum(right.v.toLong()).value
                }
            }
            if (rightVal != null) {
                return leftVal.assume(op, rightVal).isTrue()
            }
        }
        return false
    }

    /** Given a lowered assume it finds its corresponding jump instruction **/
    private fun getJumpFromLoweredAssume(locInst: LocatedSbfInstruction): SbfInstruction.Jump.ConditionalJump? {
        val inst = locInst.inst
        check(inst is SbfInstruction.Assume) { "getJumpFromLoweredAssume expects an Assume instead of $inst" }

        if (locInst.pos != 0) {
            return null
        }

        if (!inst.isLoweredAssume()) {
            return null
        }

        val b = cfg.getBlock(locInst.label)
        checkNotNull(b) { "getJumpFromLoweredAssume cannot find block ${locInst.label}" }
        val predB = b.getPreds().singleOrNull() ?: return null
        val predTerminatorInst = predB.getTerminator()
        if (predTerminatorInst is SbfInstruction.Jump.ConditionalJump) {
            val predCond = if (predTerminatorInst.target == b.getLabel()) {
                predTerminatorInst.cond
            } else {
                predTerminatorInst.cond.negate()
            }
            if (predCond == inst.cond) {
                return predTerminatorInst
            }
        }
        return null
    }

    /**
     * During the CFG construction, we lower conditional jumps into assume instructions by adding them in the successors.
     * All these assume instructions are annotated with `LOWERED_ASSUME` instructions.
     *
     * This function returns empty list if [locInst] is one of these `LOWERED_ASSUME` instructions and
     * can be skipped by TAC encoding while preserving the original semantics.
     * Note that not all `LOWERED_ASSUME` instructions are redundant because some of them are generated by slicing,
     * and we need to keep those.
     */
    private fun translateAssume(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        if (isTautology(locInst)) {
            return listOf()
        }

        val jumpInst = getJumpFromLoweredAssume(locInst)
        return if (jumpInst != null) {
            // This is another 64 vs 256-bit arithmetic fix similar to the one we do in `translateSelect`
            // Given this code
            // ```
            //    if (x >= 2^64) {
            //          A
            //    } else {
            //          B
            //    }
            // ```
            // We know that the `if` condition is checking whether `x` overflows or not.
            // This fix ensures that after the overflow check has being done (i.e., A and B) x fits in 64 bits.
            //
            val overflowCond = jumpInst.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK)
            if (sbfTacB.needOverflowPromotion && overflowCond != null) {
                val x = sbfTacB.mkVar(overflowCond.left)
                listOf(assign(x, sbfTacB.mask64(x.asSym())))
            } else {
                listOf()
            }
        } else {
            val inst = locInst.inst as SbfInstruction.Assume
            val cmd = translateCond(inst.cond)
            listOf(cmd) + assume(cmd.lhs.asSym(), "translateAssume")
        }
    }

    private fun translateAssert(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Assert)

        val cmd = translateCond(inst.cond)
        return listOf(
            cmd,
            Calltrace.assert(inst, cmd.lhs),
            assert(cmd.lhs, inst.metaData.getVal(SbfMeta.COMMENT) ?: "assertion failed",
                tac.MetaMap(TACMeta.ASSERT_ID to mkFreshAssertId()))
        )
    }

    fun translateSatisfy(inst: SbfInstruction.Call): List<TACCmd.Simple> {
        val r1 = Value.Reg(SbfRegister.R1)
        val condVar = vFac.mkFreshBoolVar()
        val cond = sbfTacB {
            ite(mkExprSym(r1) eq ZERO, TRUE, FALSE)
        }

        return listOf(
            Debug.satisfy(inst),
            assign(condVar, cond),
            Calltrace.satisfy(condVar),
            assert(condVar, inst.metaData.getVal(SbfMeta.COMMENT) ?: "satisfy reached",
                tac.MetaMap(TACMeta.SATISFY_ID to mkFreshSatisfyId()))
        )
    }

    private fun translateJump(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val bb = cfg.getBlock(locInst.label)
        checkNotNull(bb)
        val inst = locInst.inst
        check(inst is SbfInstruction.Jump)

        return when (inst) {
            is SbfInstruction.Jump.UnconditionalJump -> {
                check(bb.getSuccs().size == 1){"translateJump failed"}
                val targetBB = cfg.getBlock(inst.target)
                checkNotNull(targetBB) { "translateJump cannot find block for ${inst.target}" }
                listOf(
                    TACCmd.Simple.JumpCmd(getBlockIdentifier(targetBB))
                )
            }
            is SbfInstruction.Jump.ConditionalJump -> {
                check(bb.getSuccs().size == 2){"translateJump failed"}

                val trueTargetBB = cfg.getBlock(inst.target)
                checkNotNull(trueTargetBB)
                val trueTargetNBId = getBlockIdentifier(trueTargetBB)
                val falseTargetBB = inst.falseTarget?.let { cfg.getBlock(it) }
                checkNotNull(falseTargetBB)
                val falseTargetNBId = getBlockIdentifier(falseTargetBB)

                val newCmds = mutableListOf<TACCmd.Simple>()
                val overflowCond = inst.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK)
                val cmd = if (sbfTacB.needOverflowPromotion && overflowCond != null) {
                    /**
                     * We replace the original condition with the metadata's condition.
                     * Thus, by default the SBF code:
                     * ```
                     * z = x + y
                     * if (x > z) { ... }
                     * ```
                     * is translated to :
                     * ```
                     * z = x + y
                     * b = (z >= 2^64)
                     * ```
                     * instead of
                     * ```
                     * z = x + y
                     * b = (x > z)
                     * ```
                     *
                     * If `--solanaTACMathInt true` then is translated to :
                     * ```
                     * z_int = promote(x) + promote(y)
                     * z = narrow(z_int)
                     * b = (z >= 2^64)
                     * ```
                     **/
                    newCmds += Debug.externalCall("promoted_overflow_check")
                    translateOverflowCond(overflowCond)
                }  else {
                    translateCond(inst.cond)
                }
                newCmds += cmd
                newCmds += TACCmd.Simple.JumpiCmd(trueTargetNBId, falseTargetNBId, cmd.lhs)
                newCmds
            }
        }
    }

    /**
     * Translate a `memcpy` instruction to TAC.
     * @param locInst is `memcpy(dst, src, len)`
     **/
    private fun translateMemcpy(locInst: LocatedSbfInstruction) : List<TACCmd.Simple> {
        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
            ?: return unreachable(locInst.inst)
        val memcpy = info as? TACMemSplitter.MemTransferInfo
            ?: throw TACTranslationError("expected MemTransferInfo")

        return translateMemcpy(locInst, memcpy)
    }

    private fun translateMemcpy(
        locInst: LocatedSbfInstruction,
        info : TACMemSplitter.MemTransferInfo
    ): List<TACCmd.Simple> {
        val inst = locInst.inst
        val cmds = when (info) {
            is TACMemSplitter.UnsupportedMemTransferInfo -> {
                // We couldn't generate TAC code for the memcpy instruction.
                // This might affect soundness because we don't havoc the destination.
                sbfLogger.warn { "Unsupported TAC translation of $inst in block ${locInst.label}" }
                listOf()
            }
            is TACMemSplitter.NonStackMemTransferInfo -> {
                // CASE 1: non-stack to non-stack
                memcpyNonStackToNonStack(info)
            }
            is TACMemSplitter.StackMemTransferInfo  -> {
                // CASE 2: stack to stack
                memcpyStackToStack(info)
            }
            is TACMemSplitter.MixedRegionsMemTransferInfo -> {
                if (info.isDestStack) {
                    // CASE 3: from non-stack to stack
                    memcpyNonStackToStack(info)
                } else {
                    // CASE 4: from stack to non-stack
                    memcpyStackToNonStack(info)
                }
            }
        }

        return if (inst.writeRegister.contains(Value.Reg(SbfRegister.R0))) {
            cmds + havoc(sbfTacB.mkVar(SbfRegister.R0))
        } else {
            cmds
        }
    }

    /**
     * Translate a `memcpy_zext` instruction to TAC.
     * @param locInst is `memcpy_zext(dst, src, i)`
     **/
    private fun translateMemcpyZExt(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
            ?: return unreachable(locInst.inst)
        val memcpyZExt = info as? TACMemSplitter.MemcpyZExt
            ?: throw TACTranslationError("expected MemcpyZExt")
        return when (memcpyZExt) {
            is TACMemSplitter.UnsupportedMemcpyZExtInfo -> {
                sbfLogger.warn { "Unsupported TAC translation of ${locInst.inst} in block ${locInst.label}" }
                listOf()
            }
            is TACMemSplitter.SupportedMemcpyZExtInfo -> {
                translateMemcpy(locInst, memcpyZExt.memcpy) + translateMemset(locInst, memcpyZExt.memset)
            }
        }
    }


    /**
     *  Translate a `memcmp` instruction to TAC
     *
     *  @param locInst is `memcmp(x,y,len)`
     *
     *  Note that we encode for efficiency reasons [locInst] in TAC as r0 := (x==y ? 0: 1).
     *  However, the exact semantics of memcmp is
     *  ```
     *  return   0  if x == y
     *  return  <0  if x < y (lexicographically)
     *  return  >0  if x > y (lexicographically)
     *  ```
     */
    private fun translateMemcmp(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
            ?: return unreachable(locInst.inst)
        val memcmp = info as? TACMemSplitter.MemcmpInfo
            ?: throw TACTranslationError("expected MemcmpInfo")

        return translateMemcmp(locInst, memcmp)
    }

    private fun translateMemcmp(
        locInst: LocatedSbfInstruction,
        info: TACMemSplitter.MemcmpInfo
    ) = when (info) {
            is TACMemSplitter.UnsupportedMemCmpInfo -> {
                sbfLogger.warn {
                    "TAC encoding of ${locInst.inst} in block ${locInst.label} will be sound but imprecise"
                }
                listOf(
                    Debug.startFunction("memcmp"),
                    havoc(sbfTacB.mkVar(SbfRegister.R0)),
                    Debug.endFunction("memcmp")
                )
            }
            is TACMemSplitter.NonStackMemCmpInfo -> {
                val r0 = sbfTacB.mkVar(SbfRegister.R0)
                val r1 = sbfTacB.mkVar(SbfRegister.R1)
                val r2 = sbfTacB.mkVar(SbfRegister.R2)

                listOf(Debug.startFunction("memcmp")) +
                    withReadableByteMap(info.op1, "memcmp_op1") { op1V ->
                        withReadableByteMap(info.op2, "memcmp_op2") { op2V ->
                            val inner = mutableListOf<TACCmd.Simple>()
                            // Read word-by word from the byte maps because there is no TAC instruction
                            // for comparison of ByteMap.
                            // REVISIT(SOUNDNESS):
                            // Soundness depends on all writes to the two memory regions to access exactly info.wordSize bytes.
                            val op1Vars = mapLoads(op1V, r1, info.wordSize, info.length, inner)
                            val op2Vars = mapLoads(op2V, r2, info.wordSize, info.length, inner)
                            inner += assign(r0, allEqual(op1Vars, op2Vars, inner))
                            inner
                        }
                    } +
                    listOf(Debug.endFunction("memcmp"))
            }
            is TACMemSplitter.StackMemCmpInfo -> {
                val r0 = sbfTacB.mkVar(SbfRegister.R0)
                val cmds = mutableListOf(
                    Debug.startFunction("memcmp", "(op1=Stack${info.op1Range}, op2=Stack${info.op2Range})")
                )
                cmds.add(assign(r0, allEqual(info.op1.map { it.tacVar }, info.op2.map { it.tacVar }, cmds)))
                cmds.add(Debug.endFunction("memcmp"))
                cmds
            }
            is TACMemSplitter.MixedRegionsMemCmpInfo -> {
                val r0 = sbfTacB.mkVar(SbfRegister.R0)
                val op1Vars = info.scalars

                listOf(Debug.startFunction("memcmp", "(${info.scalarsReg}=${info.stackOpRange})")) +
                    withReadableByteMap(info.byteMap, "memcmp_mixed") { byteMapV ->
                        val inner = mutableListOf<TACCmd.Simple>()
                        // Read word-by-word from the byte map to be able to compare with the scalars.
                        // REVISIT(SOUNDNESS):
                        // Soundness depends on all writes to the non-scalar memory region to access exactly info.wordSize bytes.
                        val op2Vars = mapLoads(byteMapV, sbfTacB.mkVar(info.byteMapReg), info.wordSize, info.length, inner)
                        inner += assign(r0, allEqual(op1Vars.map { it.tacVar }, op2Vars, inner))
                        inner
                    } +
                    listOf(Debug.endFunction("memcmp"))
            }
        }

    /**
    *  Translate a `memset` instruction to TAC
    *
    *  @param locInst is `memset(x,val,len)` instruction
    **/
    private fun translateMemset(locInst: LocatedSbfInstruction) =
        when(val info = mem.getTACMemoryFromMemIntrinsic(locInst)) {
            null -> unreachable(locInst.inst)
            else -> {
                check(info is TACMemSplitter.MemsetInfo)
                translateMemset(locInst, info)
            }
        }

    private fun translateMemset(
        locInst: LocatedSbfInstruction,
        info: TACMemSplitter.MemsetInfo
    ): List<TACCmd.Simple> {

        val cmds = when (info) {
            is TACMemSplitter.UnsupportedMemsetInfo -> {
                // We couldn't generate TAC code for the memset instruction.
                // This might affect soundness because we don't havoc the destination.
                sbfLogger.warn { "Unsupported TAC translation of ${locInst.inst} in block ${locInst.label}" }
                listOf()
            }
            is TACMemSplitter.StackZeroMemsetInfo -> {
                val len = info.length
                val range = info.stackOpRange
                val cmds = mutableListOf(Debug.startFunction("memset", "(Stack($range), 0)"))
                for (i in 0 until len) {
                    val offset = PTAOffset(range.lb + i)
                    val pv = vFac.getByteStackVar(offset)
                    cmds.add(assign(pv.tacVar, sbfTacB.ZERO))
                }
                cmds += Debug.endFunction("memset")
                cmds
            }
            is TACMemSplitter.NonStackMemsetInfo -> {
                val len = info.length
                val value = info.value
                val cmds = withWritableByteMap(info.byteMap, "memset") { byteMapV ->
                    if (len <= SolanaConfig.TACMaxUnfoldedMemset.get()) {
                        memsetNonStack(byteMapV, len, value, info.offset)
                    } else {
                        memsetNonStackWithMapDef(byteMapV, len, value, info.offset)
                    }
                }
                listOf(Debug.startFunction("memset", "(NonStack, $value, $len)")) +
                    cmds +
                    listOf(Debug.endFunction("memset"))
            }
        }
        return if (locInst.inst.writeRegister.contains(Value.Reg(SbfRegister.R0))) {
            cmds + havoc(sbfTacB.mkVar(SbfRegister.R0))
        } else {
            cmds
        }
    }

    private fun registerTypeFromUses(
        uses: Collection<LocatedSbfInstruction>, r: SbfRegister
    ): SbfType<TNumAdaptiveScalarAnalysis, TNumAdaptiveScalarAnalysis> {
        return uses.map {
            types.typeAtInstruction(it, r)
        }.fold(SbfType.bottom()) { t1, t2 ->
            t1.join(t2)
        }
    }

    fun inferredArgsToTACArgs(
        args: Map<Value.Reg, Set<LocatedSbfInstruction>>,
        live: Set<Value.Reg>
    ): List<Pair<TACSymbol.Var, SbfFuncArgInfo>> {
        return args.toList().sortedBy {  (rVal, _) ->
            rVal.r
        }.map { (reg, uses) ->
            // For each register/set of uses,
            val sort = SbfArgSort.fromSbfType(registerTypeFromUses(uses, reg.r))

            // Indicate if, at this callsite, we actually have a use of [reg]
            val observedUse = reg in live

            sbfTacB.mkVar(reg.r) to SbfFuncArgInfo(
                sort = sort,
                observedUse = observedUse
            )
        }
    }

    private fun translateCall(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        if (inst.isAbort()) {
            // If the abort was added by the slicer then we skip it in TAC because it can cause problems to sanity rules
            return if (inst.metaData.getVal(SbfMeta.UNREACHABLE_FROM_COI) != null) {
                listOf()
            } else {
                unreachable(inst)
            }
        } else if (inst.isAllocFn()) {
            val size = (types.typeAtInstruction(locInst, SbfRegister.R1) as? SbfType.NumType)?.value?.toLongOrNull()
            val sizeOrDefault = if (size != null) {
                size
            } else {
                val defaultSize = SolanaConfig.TACHeapAllocSize.get().toLong()
                sbfLogger.warn{ "TAC allocation of unknown size: fixing $defaultSize bytes at $locInst"}
                defaultSize
            }
            if (sizeOrDefault <= 0) {
                throw TACTranslationError("${heapMemAlloc.name}::alloc expects non-zero, positive sizes")
            }
            return listOf(Debug.externalCall(inst)) +
                   heapMemAlloc.alloc(sbfTacB.mkVar(SbfRegister.R0), sizeOrDefault.toULong()) +
                   listOf(Calltrace.externalCall(inst, listOf(sbfTacB.mkVar(SbfRegister.R0))))
        } else {
            val cvtFunction = CVTFunction.from(inst.name)
            if (cvtFunction != null) {
                return when (cvtFunction) {
                    is CVTFunction.Core ->
                        summarizeCVTCore(cvtFunction.value, locInst)
                    is CVTFunction.Nondet ->
                        summarizeNondet(cvtFunction.value, inst)
                    is CVTFunction.Calltrace ->
                        summarizeCalltrace(cvtFunction.value, locInst)
                    is CVTFunction.U128Intrinsics ->
                        u128Summarizer.summarizeU128(locInst)
                    is CVTFunction.I128Intrinsics ->
                        summarizeI128(locInst)
                    is CVTFunction.NativeInt ->
                        summarizeNativeInt(locInst)
                }
            }

            val solFunction  = SolanaFunction.from(inst.name)
            if (solFunction != null) {
                return when (solFunction) {
                    SolanaFunction.SOL_MEMCPY_TRUNC,
                    SolanaFunction.SOL_MEMCPY -> translateMemcpy(locInst)
                    SolanaFunction.SOL_MEMCPY_ZEXT -> translateMemcpyZExt(locInst)
                    SolanaFunction.SOL_MEMCMP -> translateMemcmp(locInst)
                    SolanaFunction.SOL_MEMSET -> translateMemset(locInst)
                    SolanaFunction.SOL_GET_CLOCK_SYSVAR,
                    SolanaFunction.CVT_SOL_GET_CLOCK_SYSVAR -> clock.get(locInst)
                    SolanaFunction.SOL_SET_CLOCK_SYSVAR -> clock.set(locInst)
                    SolanaFunction.SOL_GET_RENT_SYSVAR,
                    SolanaFunction.CVT_SOL_GET_RENT_SYSVAR -> rent.get(locInst)
                    else -> summarizeCall(locInst)
                }
            }

            if (CompilerRtFunction.from(inst.name) != null) {
                return SummarizeCompilerRt<TNum, TOffset, TFlags>()(locInst).ifEmpty {
                    summarizeCall(locInst)
                }
            }

            return summarizeCall(locInst)
        }
    }

    private fun translateMem(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)
        val loadOrStore = mem.getTACMemory(locInst)
        return if (loadOrStore == null) {
            /* The instruction is unreachable */
            unreachable(inst)
        } else {
            val baseReg = inst.access.base
            val offset = inst.access.offset
            val value = inst.value
            when (loadOrStore) {
                is TACMemSplitter.StackLoadOrStoreInfo -> {
                    val newCmds = mutableListOf<TACCmd.Simple>()
                    val baseE = sbfTacB.mkVar(baseReg).asSym()
                    val offsetE = sbfTacB.mkConst(offset.toLong()).asSym()
                    if (inst.isLoad) {
                        val lhs = value as Value.Reg
                        newCmds += stackLoad(
                            inst,
                            baseE,
                            offsetE,
                            loadOrStore.variables,
                            loadOrStore.reconstructedValues,
                            sbfTacB.mkVar(lhs.r)
                        )
                    } else {
                        if (SolanaConfig.memorySplitter() == MemorySplitter.PTA) {
                            // havoc any possible overlaps
                            val scalarsToHavoc = loadOrStore.locationsToHavoc
                            check(scalarsToHavoc is TACMemSplitter.HavocScalars) {
                                "TAC translateMem expects HavocScalars"
                            }

                            val havocMap = scalarsToHavoc.vars
                            when (havocMap.size) {
                                0 -> {}
                                1 -> newCmds += havocScalars(havocMap.toList().single().second)
                                else -> newCmds += weakHavocScalars(baseE, offsetE, havocMap)
                            }
                        }
                        newCmds += stackStore(
                            baseE,
                            offsetE,
                            loadOrStore.variables,
                            sbfTacB.mkExprSym(value)
                        )
                    }
                    newCmds
                }
                is TACMemSplitter.NonStackLoadOrStoreInfo -> {
                    val newCmds = mutableListOf<TACCmd.Simple>()
                    val loc = computeTACMapIndex(sbfTacB.mkVar(baseReg), PTAOffset(offset.toLong()), newCmds)
                    when (val target = loadOrStore.variable) {
                        is TACMemSplitter.ByteMapTarget.Base -> {
                            val memVar = target.v
                            if (inst.isLoad) {
                                val lhs = value as Value.Reg
                                val lhsV = sbfTacB.mkVar(lhs.r)
                                val lhsType = types.typeAtInstruction(locInst, lhs.r, isWritten = true)
                                val lhsVal = (lhsType as? SbfType.NumType)?.value?.toLongOrNull()
                                newCmds += if (lhsVal != null) {
                                    // optimization, specially important for read-only globals: if the scalar analysis knows
                                    // the value of the lhs then we don't read from the map
                                    listOf(assign(lhsV, sbfTacB.mkConst(lhsVal).asSym()))
                                } else {
                                    sbfTacB.load(lhsV, loc, inst.access.width, memVar.tacVar)
                                }
                            } else {
                                if (SolanaConfig.memorySplitter() == MemorySplitter.PTA) {
                                    // havoc any possible overlaps
                                    val mapFieldsToHavoc = loadOrStore.locationsToHavoc
                                    check(mapFieldsToHavoc is TACMemSplitter.HavocMapBytes) {
                                        "TAC translateMem expects HavocMapBytes"
                                    }
                                    newCmds += havocByteMapLocation(mapFieldsToHavoc.vars, memVar, loc)
                                }
                                val valueE = when (value) {
                                    is Value.Imm -> { sbfTacB.mkConst(value) }
                                    is Value.Reg -> { sbfTacB.mkVar(value) }
                                }
                                newCmds += store(memVar.tacVar, loc, valueE)
                                newCmds += accounts.updateWrite(loc)
                            }
                        }
                        is TACMemSplitter.ByteMapTarget.Ite -> {
                            if (inst.isLoad) {
                                val lhs = value as Value.Reg
                                val lhsV = sbfTacB.mkVar(lhs.r)
                                val lhsType = types.typeAtInstruction(locInst, lhs.r, isWritten = true)
                                val lhsVal = (lhsType as? SbfType.NumType)?.value?.toLongOrNull()
                                newCmds += if (lhsVal != null) {
                                    listOf(assign(lhsV, sbfTacB.mkConst(lhsVal).asSym()))
                                } else {
                                    load(lhsV, target, loc, inst.access.width)
                                }
                            } else {
                                val valueE = when (value) {
                                    is Value.Imm -> sbfTacB.mkConst(value)
                                    is Value.Reg -> sbfTacB.mkVar(value)
                                }
                                newCmds += store(target, loc, valueE, inst.access.width)
                                newCmds += accounts.updateWrite(loc)
                            }
                        }
                    }
                    val baseRegType = types.typeAtInstruction(locInst, baseReg.r)
                    newCmds += addMemoryLayoutAssumptions(loc, baseRegType)
                    newCmds
                }
            }
        }
    }

    private fun mapSbfMetaToTACMeta(
        cmds: List<TACCmd.Simple>,
        locInst: LocatedSbfInstruction
    ): List<TACCmd.Simple> {
        var pairs = tac.MetaMap()
        val metaData = locInst.inst.metaData

        // if this function starts needing more pairs,
        // split it into individual functions for each meta pair

        val address = metaData
            .getVal(SbfMeta.SBF_ADDRESS)
            ?.let { address ->
                check(address <= Long.MAX_VALUE.toULong()) {"Address $address is too big SVM"}
                address.toLong()
            }
        if (address != null) {
            pairs += TACMeta.SBF_ADDRESS to address
        }

        val cvlrRange = metaData.getVal(SbfMeta.CVLR_RANGE)
        if (cvlrRange != null) {
            pairs += TACMeta.CVL_RANGE to cvlrRange
        }

        val srcMetaInfo = metaData.getVal(SbfMeta.SOURCE_SEGMENT)
        if (srcMetaInfo != null) {
            pairs += TACMeta.SBF_SOURCE_SEGMENT to srcMetaInfo
        }

        return cmds.map { it.plusMetaMap(pairs) }
    }

    private fun translate(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        sbfLogger.debug {"\tTAC translation of $inst"}
        val cmds = when (inst) {
            is SbfInstruction.Mem -> translateMem(locInst)
            is SbfInstruction.Bin -> translateBin(locInst)
            is SbfInstruction.Un -> translateUn(inst)
            is SbfInstruction.Jump -> translateJump(locInst)
            is SbfInstruction.Havoc -> translateHavoc(inst)
            is SbfInstruction.Select -> translateSelect(inst)
            is SbfInstruction.Assert -> translateAssert(locInst)
            is SbfInstruction.Assume -> translateAssume(locInst)
            is SbfInstruction.Call -> translateCall(locInst)
            is SbfInstruction.Exit -> translateExit()
            is SbfInstruction.Debug -> translateDebug(locInst)
            is SbfInstruction.CallReg -> {
                if (!SolanaConfig.SkipCallRegInst.get()) {
                    throw TACTranslationError("unsupported $inst")
                } else {
                    listOf()
                }
            }
        }

        return mapSbfMetaToTACMeta(cmds, locInst)
    }
    private fun translateDebug(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        return locInst.inst.metaData.getVal(SBF_DWARF_DEBUG_ANNOTATIONS)?.flatMap { edgeAnnot->
            edgeAnnot.toAnnotations(locInst, this)
        }.orEmpty()
    }

    private fun translate(bb: SbfBasicBlock): List<TACCmd.Simple> {
        checkNotNull(cfg.getBlock(bb.getLabel())){
            "Basic block ${bb.getLabel()} not found in CFG ${cfg.getName()}"
        }
        check(bb.getInstructions().isNotEmpty()){
            "A SbfBasicBlock should not be empty"
        }
        sbfLogger.debug {"TAC translation of block ${bb.getLabel()}"}
        val cmds: MutableList<TACCmd.Simple> = mutableListOf()
        for (locInst in bb.getLocatedInstructions()) {
            cmds += translate(locInst)
        }
        check(cmds.isNotEmpty()){"A TAC basic block should not be empty "}
        return cmds
    }

    // For debugging
    private fun dumpTAC(program: CoreTACProgram): String {
        val sb = StringBuilder()
        program.code.forEachEntry { (id, commands) ->
            sb.append("Block $id:\n")
            commands.forEach { command ->
                sb.append("\t${command}\n")
            }
        }
        sb.append("Graph\n")
        program.blockgraph.forEachEntry { (u, vs) ->
            sb.append("$u -> ${vs.joinToString(" ")}\n")
        }
        return sb.toString()
    }

    // Convert a CFG to a TACProgram
    fun encode(): CoreTACProgram {
        val entry = cfg.getEntry()
        mkBlockIdentifier(entry, isStart = true)
        cfg.getBlocks().values.forEach {
            if (it != entry) {
                mkBlockIdentifier(it, isStart = false)
            }
        }

        // We need to traverse in depth-first search in order to encode correctly
        // __CVT_restore_scratch_registers.
        val worklist = ArrayList<SbfBasicBlock>()
        val visited: MutableSet<Label> = mutableSetOf(entry.getLabel())
        worklist.add(entry)
        while (worklist.isNotEmpty()) {
            val block = worklist.removeLast()
            val tacBB = getBlockIdentifier(block)
            if (entry.getLabel() == block.getLabel()) {
                val cmds = ArrayList<TACCmd.Simple>()
                /** Begin TAC initializers **/
                cmds += accounts.init()
                cmds += addGlobalInitializers()
                cmds += rent.init()
                cmds += clock.init()
                cmds += addInitialPreconditions()
                cmds += u128Summarizer.init()
                /** End TAC initializers **/

                cmds += translate(block)
                code[tacBB] = cmds
            } else {
                val cmds = translate(block)
                check(cmds.isNotEmpty()) {"TAC block $tacBB is empty. Original block is $block"}
                code[tacBB] = cmds
            }
            for (succ in block.getSuccs()) {
                val succTacBB = getBlockIdentifier(succ)
                blockGraph[tacBB] = blockGraph[tacBB].orEmpty() + succTacBB
                if (visited.add(succ.getLabel())) {
                    worklist.add(succ)
                }
            }
        }

        // Prune unreachable blocks
        // We shouldn't have unreachable blocks at this point, except if the exit of the CFG is unreachable.
        // This is because our CFG normalization adds one even if it's unreachable.
        for (block in cfg.getBlocks().values) {
            if (!visited.contains(block.getLabel())) {
                removeBlockIdentifier(block.getLabel())
            }
        }

        // Initialize all TAC variables non-deterministically
        // We also initialize unnecessarily TAC registers used to save SBF scratch registers
        val tacEntryB = getBlockIdentifier(entry)
        val initCmds = mutableListOf<TACCmd.Simple>()
        val declaredVars = vFac.getDeclaredVariables()
        for (v in declaredVars) {
            initCmds.add(havoc(v))
        }
        val entryCmds = checkNotNull(code[tacEntryB]) {"cannot find TAC code for the entry block"}
        code[tacEntryB] = initCmds + entryCmds

        val symbolTable = TACSymbolTable(declaredVars)
        val name = cfg.getName()
        val procs = mutableSetOf<Procedure>() // this is used by CEX generation
        val program = CoreTACProgram(code, blockGraph, name, symbolTable, procs,
                                    true, entryBlock = getBlockIdentifier(entry))

        if (unsupportedCalls.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("TAC encoding of the following external calls might be unsound because " +
                      "only the output has been havoced\n")
            for (fname in unsupportedCalls) {
                if (!hasSummary(fname, memSummaries)) {
                    sb.append("\t$fname\n")
                }
            }
            sbfLogger.warn { sb.toString() }
        }

        if (SolanaConfig.PrintTACToStdOut.get()) {
            sbfLogger.info {"------- TAC program --------\n" + dumpTAC(program)}
        }

        DWARFEdgeLabelAnnotator.printDebugAnnotatorStats(program, "After encoding to TAC")

        return program
    }

    override fun getStackTACVariable(
        locInst: LocatedSbfInstruction,
        reg: Value.Reg,
        offset: PTAOffset
    ): TACSymbol.Var? {
        val base = (types.typeAtInstruction(locInst, reg.r) as? SbfType.PointerType.Stack)?.offset?.toLongOrNull() ?: return null
        return vFac.getByteStackVar(PTAOffset(base) + offset).tacVar
    }

    override fun getRegisterTACVariable(reg: Value.Reg): TACSymbol.Var {
        return vFac.getRegisterVar(reg.r.ordinal)
    }
}
