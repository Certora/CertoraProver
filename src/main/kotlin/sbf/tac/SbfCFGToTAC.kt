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
import tac.*
import vc.data.*
import com.certora.collect.*
import datastructures.stdcollections.*
import sbf.domains.*
import java.math.BigInteger

// TAC annotations for TAC debugging
val SBF_ADDRESS  = tac.MetaKey<Long>("sbf.bytecode.address")

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
 *
 *  - COMMENT #1: In TAC, we only have available 256-bit integers, but SBF uses 64-bit integers.
 *  Thus, the SBF-to-TAC translation needs to consider the semantic gap between the two.
 *
 *  - COMMENT #2: use ByteMap to represent non-stack memory. A ByteMap is just a map from Int to Int.
 *  This means that we need to be careful with aliasing due to overlaps.
 *  The pointer analysis needs to ensure that.
 *
 *  - COMMENT #3: TAC encoding of memcmp and memset is tricky, when at least one operand is a ByteMap.
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
    globalsSymTable: IGlobalsSymbolTable,
    globalAnalysisResults: Map<String, MemoryAnalysis<TNum, TOffset, TFlags>>?): CoreTACProgram {
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
    val marshaller = SbfCFGToTAC(cfg, program.getGlobals(), memSummaries, globalsSymTable, analysis)
    return marshaller.encode()
}

/** Translate an SBF CFG to a TAC program **/
@Suppress("ForbiddenComment")
internal class SbfCFGToTAC<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>(
           private val cfg: SbfCFG,
           globals: GlobalVariableMap,
           private val memSummaries: MemorySummaries,
           private val globalsSymTable: IGlobalsSymbolTable,
           val memoryAnalysis: MemoryAnalysis<TNum, TOffset, TFlags>?) {
    private val blockMap: MutableMap<Label, NBId> = mutableMapOf()
    private val blockGraph = MutableBlockGraph()
    private val code: MutableMap<NBId, List<TACCmd.Simple>> = mutableMapOf()
    // Factory for creating TACExpr
    val exprBuilder: TACExprBuilder
    // Factory for creating TACSymbol.Var
    val vFac = TACVariableFactory<TFlags>()
    // Symbolic memory allocators
    val heapMemAlloc = TACBumpAllocator("TACHeapAllocator", SBF_HEAP_START.toULong(), SBF_HEAP_END.toULong())
    private val accountsAlloc = TACFixedSizeBlockAllocator("TACSolanaAccountAllocator", SBF_INPUT_START.toULong(), MAX_SOLANA_ACCOUNTS.toUShort(), SOLANA_ACCOUNT_SIZE.toULong())
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
    val sbfTypesFac: ISbfTypeFactory<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>
    val regTypes: IRegisterTypes<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>
    // Stack of scratch registers
    val scratchRegVars: ArrayList<TACSymbol.Var> = arrayListOf()
    // To model clock syscalls
    val clock: Clock = Clock { prefix -> vFac.mkFreshIntVar(prefix = prefix) }
    // To model rent syscalls
    val rent: Rent = Rent { prefix -> vFac.mkFreshIntVar(prefix = prefix) }

    init {
        val scalarAnalysis = AdaptiveScalarAnalysis(cfg, globals, memSummaries)
        sbfTypesFac = scalarAnalysis.getSbfTypesFac()
        regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val regVars: ArrayList<TACSymbol.Var> = ArrayList(NUM_OF_SBF_REGISTERS)
        for (i in 0 until NUM_OF_SBF_REGISTERS) {
            regVars.add(vFac.getRegisterVar(i))
        }
        exprBuilder = TACExprBuilder(regVars)
        mem = if (memoryAnalysis != null) {
            PTAMemSplitter(cfg, vFac, memoryAnalysis, globals)
        } else {
            DummyMemSplitter(vFac, regTypes)
        }
    }

    private fun mkBlockIdentifier(SbfBB: SbfBasicBlock, isStart: Boolean): NBId {
        // The entry block of the CFG must be `StartBlock`
        val tacBB = if (isStart) {
            StartBlock
        } else {
            // We use `stkTop = 1` to avoid classes with Allocator.getNBId()
            BlockIdentifier(blockId++, stkTop = 1, 0, 0, 0, 0)
        }
        blockGraph[tacBB] = treapSetOf()
        code[tacBB] = mutableListOf()
        blockMap[SbfBB.getLabel()] = tacBB
        return tacBB
    }

    private fun removeBlockIdentifier(SbfBB: Label) {
        val tacBB = blockMap[SbfBB]
        if (tacBB != null ){
            blockGraph.remove(tacBB)
            code.remove(tacBB)
            blockMap.remove(SbfBB)
        }
    }

    private fun getBlockIdentifier(SbfBB: SbfBasicBlock): NBId {
        check(blockMap.contains(SbfBB.getLabel())) {"getBlockIdentifier failed on ${SbfBB.getLabel()}\n\t$SbfBB"}
        val tacBB = blockMap[SbfBB.getLabel()]
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
        val r10 = exprBuilder.mkVar(SbfRegister.R10_STACK_POINTER)
        // r10 points to the end of the stack frame
        return listOf(
            assign(b, exprBuilder.mkBinRelExp(CondOp.EQ, TACExpr.Sym.Var(r10), SBF_STACK_START + SBF_STACK_FRAME_SIZE)),
            TACCmd.Simple.AssumeCmd(b, "InitialPreconditions"))
    }

    private fun addGlobalInitializers(): List<TACCmd.Simple> {
        val initializers = runGlobalInitializationAnalysis(cfg, regTypes, globalsSymTable)
        val cmds = mutableListOf<TACCmd.Simple>()
        for ( (gv, _, stride, locInst, values) in initializers) {
            cmds.add(Debug.startFunction("init_${gv.name}"))
            val loadOrStoreInfo = mem.getTACMemory(locInst)
            check(loadOrStoreInfo != null) {"addGlobalInitializers cannot get PTA info from $locInst"}
            check(loadOrStoreInfo is TACMemSplitter.NonStackLoadOrStoreInfo) {"addGlobalInitializers expects a byte map at $locInst"}

            val byteMap = loadOrStoreInfo.variable
            val locVar = vFac.mkFreshIntVar()
            cmds.add(assign(locVar, exprBuilder.mkConst(gv.address).asSym()))
            val offsets = List(values.size) { index -> PTAOffset((index * stride).toLong())  }
            val storedValues = values.map { exprBuilder.mkConst(it)}
            cmds.addAll(mapStores(byteMap, locVar, offsets, storedValues))
            cmds.add(Debug.endFunction("init_${gv.name}"))
        }
        return cmds
    }

    private fun inRange(v: TACSymbol.Var, lb: Long, ub: Long, isUnsigned: Boolean = true) =
        inRange(v, lb.toBigInteger(), ub.toBigInteger(), isUnsigned)

    fun inRange(v: TACSymbol.Var, lb: BigInteger, ub: BigInteger, isUnsigned: Boolean = true): List<TACCmd.Simple>{
        val lbBool = vFac.mkFreshBoolVar()
        val ubBool = vFac.mkFreshBoolVar()
        return if (isUnsigned) {
            listOf(
                assign(lbBool, exprBuilder.mkBinRelExp(CondOp.GE, v.asSym(), lb)),
                TACCmd.Simple.AssumeCmd(lbBool, "inRange"),
                assign(ubBool, exprBuilder.mkBinRelExp(CondOp.LT, v.asSym(), ub)),
                TACCmd.Simple.AssumeCmd(ubBool, "inRange")
            )
        } else {
            listOf(
                assign(lbBool, exprBuilder.mkBinRelExp(CondOp.SGE, v.asSym(), lb)),
                TACCmd.Simple.AssumeCmd(lbBool, "inRange"),
                assign(ubBool, exprBuilder.mkBinRelExp(CondOp.SLT, v.asSym(), ub)),
                TACCmd.Simple.AssumeCmd(ubBool, "inRange")
            )
        }
    }

    /**
     *  Add extra assumptions based on memory layout:
     *  ```
     *        ---------------------------------------------------------------------
     *       |      CODE    |       STACK        |      HEAP    |  INPUT           |
     *        ---------------------------------------------------------------------
     *       0x100000000    0x200000000          0x30000000     0x40000000         ?
     *  ```
     **/
    fun addMemoryLayoutAssumptions(ptr: TACSymbol.Var, region: SbfType<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>?): List<TACCmd.Simple> {

        if (!SolanaConfig.AddMemLayoutAssumptions.get()) {
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

        return inRange(ptr, lb, ub)
    }

    private fun translateBin(inst: SbfInstruction.Bin, useMathInt: Boolean = false): List<TACCmd.Simple> {
        return if (inst.op == BinOp.MOV) {
            listOf(assign(exprBuilder.mkVar(inst.dst), exprBuilder.mkExprSym(inst.v)))
        } else {
            if (!inst.is64) {
                throw TACTranslationError("TAC encoding of 32-bit $inst not supported")
            }
            val op1 = exprBuilder.mkVar(inst.dst)
            return if (SolanaConfig.UseTACMathInt.get() &&
                (useMathInt || inst.metaData.getVal(SbfMeta.SAFE_MATH) != null)) {
                // Currently, `SAFE_MATH` annotations are only used for addition/subtraction before checking for overflow.
                // These operations must be done on MathInt.

                val x = vFac.mkFreshMathIntVar()
                val y = vFac.mkFreshMathIntVar()
                val z = vFac.mkFreshMathIntVar()

                listOf(
                    when (inst.v) {
                        is Value.Reg -> {
                            promoteToMathInt(exprBuilder.mkVar(inst.v).asSym(), y)
                        }
                        is Value.Imm -> {
                            // We cannot use `mkConst` because if the immediate value is a negative one it will sign extended to 256 bits,
                            // and this is incorrect using MathInt.
                            assign(y, TACSymbol.Const(inst.v.v.toLong().toBigInteger(), Tag.Int).asSym())
                        }
                    },
                    promoteToMathInt(op1.asSym(), x),
                    assign(z, exprBuilder.mkBinExpr(inst.op, x.asSym(), y.asSym(), useMathInt = true)),
                    narrowFromMathInt(z.asSym(), op1)
                )
            } else {
                val op2 = exprBuilder.mkExprSym(inst.v, useTwosComplement = true)
                listOf(assign(op1, exprBuilder.mkBinExpr(inst.op, op1.asSym(), op2, useMathInt = false)))
            }
        }
    }

    private fun translateUn(inst: SbfInstruction.Un): List<TACCmd.Simple> {
        if (inst.op == UnOp.NEG) {
            if (!inst.is64) {
              throw TACTranslationError("TAC encoding of 32-bit $inst not supported")
            }
            val lhs = exprBuilder.mkVar(inst.dst)
            val rhs = exprBuilder.mkUnExpr(UnOp.NEG, inst.dst)
            return listOf(assign(lhs, rhs))
        } else {
            // we don't support UnOp.BE16/32/64, UnOp.LE16/32/64
            throw TACTranslationError("Unsupported $inst")
        }
    }

    private fun translateSelect(inst: SbfInstruction.Select): List<TACCmd.Simple> {
        val newCmds = mutableListOf<TACCmd.Simple>()

        val overflowCond = inst.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK)
        val (tacOverflowCond, tacOverflowVar) = if (SolanaConfig.TACPromoteOverflow.get() && overflowCond != null) {
            // See comments from translateAssume
            newCmds.add(Debug.externalCall("overflow_check"))
            val overflowCondTac = translateCond(overflowCond, bitwidth = 64)
            overflowCondTac to overflowCondTac.getRhs().filterIsInstance<TACSymbol.Var>().singleOrNull()
        }  else {
            translateCond(inst.cond) to null
        }
        newCmds.add(tacOverflowCond)
        newCmds.add(assign(exprBuilder.mkVar(inst.dst), TACExpr.TernaryExp.Ite(TACExpr.Sym.Var(tacOverflowCond.lhs),
                                                                      exprBuilder.mkExprSym(inst.trueVal),
                                                                      exprBuilder.mkExprSym(inst.falseVal) )))

        // This is another 64 vs 256-bit arithmetic fix. See comments from `translateAssume`
        if (tacOverflowVar != null) {
            newCmds.add(assign(tacOverflowVar, exprBuilder.mask64(tacOverflowVar.asSym())))
        }

        return newCmds
    }

    private fun translateHavoc(inst: SbfInstruction.Havoc): List<TACCmd.Simple> {
        return listOf(TACCmd.Simple.AssigningCmd.AssignHavocCmd(exprBuilder.mkVar(inst.dst)))
    }

    private fun translateExit(): List<TACCmd.Simple> {
        // In SBF, the exit command does not have parameter
        // Here we create a return instruction that returns r0
        return listOf(TACCmd.Simple.ReturnSymCmd(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)))
    }

    private fun translateCond(cond: Condition, bitwidth: Short = 256): TACCmd.Simple.AssigningCmd {
        val tacLhs = vFac.mkFreshBoolVar()
        val leftE = exprBuilder.mkExprSym(cond.left)
        val rightE = if (cond.right is Value.Imm) {
            exprBuilder.mkConst(cond.right, useTwosComplement = true, bitwidth).asSym()
        } else {
            exprBuilder.mkExprSym(cond.right)
        }
        val tacRhs = exprBuilder.mkBinRelExp(cond.op, leftE, rightE)
        return assign(tacLhs, tacRhs)
    }

    /** Return true if [locInst] is an Assume instruction and its condition is evaluated semantically to true **/
    private fun isTautology(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst
        check(inst is SbfInstruction.Assume) {"isTautology expects an assume instruction instead of $inst"}

        val left = inst.cond.left
        val right = inst.cond.right
        val op = inst.cond.op

        val leftTy = regTypes.typeAtInstruction(locInst, left.r)
        if (leftTy is SbfType.NumType) {
            val leftVal = leftTy.value
            val rightVal = when(right) {
                is Value.Reg ->  {
                    val rightTy = regTypes.typeAtInstruction(locInst, right.r)
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
        check(inst is SbfInstruction.Assume){"getJumpFromLoweredAssume expects an Assume instead of $inst"}

        if (locInst.pos != 0) {
            return null
        }

        if (inst.metaData.getVal(SbfMeta.LOWERED_ASSUME) == null) {
            return null
        }

        val b = cfg.getBlock(locInst.label)
        check(b != null) { "getJumpFromLoweredAssume cannot find block ${locInst.label}" }
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
     * All these assume instructions are annotated with LOWERED_ASSUME instructions.
     *
     * This function returns true if [locInst] is one of these LOWERED_ASSUME instructions and can be skipped by TAC encoding
     * while preserving the original semantics. Note that not all LOWERED_ASSUME instructions are redundant because some
     * of them are generated by slicing, and we need to keep those.
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
            if (SolanaConfig.TACPromoteOverflow.get() && overflowCond != null) {
                val x = exprBuilder.mkVar(overflowCond.left)
                listOf(assign(x, exprBuilder.mask64(x.asSym())))
            } else {
                listOf()
            }
        } else {
            val inst = locInst.inst as SbfInstruction.Assume
            val cmd = translateCond(inst.cond)
            listOf(
                cmd,
                TACCmd.Simple.AssumeCmd(cmd.lhs, "translateAssume")
            )
        }
    }

    private fun translateAssert(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Assert) {"TAC translateAssert did not expect $inst"}

        val cmd = translateCond(inst.cond)
        return listOf(
            cmd,
            Calltrace.assert(inst, cmd.lhs),
            TACCmd.Simple.AssertCmd(cmd.lhs, inst.metaData.getVal(SbfMeta.COMMENT) ?: "assertion failed", MetaMap(TACMeta.ASSERT_ID to mkFreshAssertId()))
        )
    }

    private fun translateSatisfy(inst: SbfInstruction.Call): List<TACCmd.Simple> {
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val condVar = vFac.mkFreshBoolVar()
        val cond = TACExpr.TernaryExp.Ite(
            TACExpr.BinRel.Eq(exprBuilder.mkExprSym(r1), TACExpr.zeroExpr),
            TACSymbol.True.asSym(),
            TACSymbol.False.asSym()
        )
        return listOf(
            Debug.satisfy(inst),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(condVar, cond),
            Calltrace.satisfy(condVar),
            TACCmd.Simple.AssertCmd(condVar, inst.metaData.getVal(SbfMeta.COMMENT) ?: "satisfy reached", MetaMap(TACMeta.SATISFY_ID to mkFreshSatisfyId()))
        )
    }

    private fun translateJump(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val bb = cfg.getBlock(locInst.label)
        check(bb != null)
        val inst = locInst.inst
        check(inst is SbfInstruction.Jump) {"TAC translateJump expects a jump instead of $inst"}

        return when (inst) {
            is SbfInstruction.Jump.UnconditionalJump -> {
                check(bb.getSuccs().size == 1){"translateJump failed"}
                val targetBB = cfg.getBlock(inst.target)
                check(targetBB != null){"translateJump cannot find block for ${inst.target}"}
                listOf(
                    TACCmd.Simple.JumpCmd(getBlockIdentifier(targetBB))
                )
            }
            is SbfInstruction.Jump.ConditionalJump -> {
                check(bb.getSuccs().size == 2){"translateJump failed"}

                val trueTargetBB = cfg.getBlock(inst.target)
                check(trueTargetBB != null)
                val trueTargetNBId = getBlockIdentifier(trueTargetBB)
                val falseTargetBB = inst.falseTarget?.let { cfg.getBlock(it) }
                check(falseTargetBB != null)
                val falseTargetNBId = getBlockIdentifier(falseTargetBB)

                val newCmds = mutableListOf<TACCmd.Simple>()
                val overflowCond = inst.metaData.getVal(SbfMeta.PROMOTED_OVERFLOW_CHECK)
                val cmd = if (SolanaConfig.TACPromoteOverflow.get() && overflowCond != null) {
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
                     *   REVISIT: The use of `z` instead of `z_int` in `b = (z >= 2^64)` is sound because we are using
                     *   256-bit TAC registers. However, once we move to 64-bit TAC registers we will need to use `z_int`
                     **/
                    newCmds += Debug.externalCall("promoted_overflow_check")
                    translateCond(overflowCond, bitwidth = 64)
                }  else {
                    translateCond(inst.cond)
                }
                newCmds += cmd
                newCmds += TACCmd.Simple.JumpiCmd(trueTargetNBId, falseTargetNBId, cmd.lhs)
                return newCmds
            }
        }
    }

    /**
     * Translate a `memcpy` instruction to TAC.
     **/
    private fun translateMemCopy(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"TAC translateMemCopy expects a call instead of $inst"}

        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
        return if (info == null) {
            unreachable(inst)
        } else {
            check(info is TACMemSplitter.MemTransferInfo) { "$inst expects MemTransferInfo" }

            val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
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
            if (inst.isPromotedMemcpy()) {
                cmds
            } else {
                cmds + TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)
            }
        }
    }

    /**
     *  Translate a `memcmp` instruction to TAC
     *
     *  @param locInst is a memcmp(x,y,len) instruction
     *
     *  FIXME: right now we encode inst in TAC as r0 := (x==y ? 0: 1)
     *  However, the exact semantics of memcmp is
     *  return   0  if x == y
     *  return  <0  if x < y (lexicographically)
     *  return  >0  if x > y (lexicographically)
     */
    private fun translateMemCompare(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"TAC translateMemCopy expects a call instead of $inst"}
        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
        return if (info == null) {
            unreachable(inst)
        } else {
            check(info is TACMemSplitter.MemCmpInfo){"$info is not MemCmpInfo"}
            return when (info) {
                is TACMemSplitter.UnsupportedMemCmpInfo -> {
                    sbfLogger.warn { "TAC encoding of $inst in block ${locInst.label} will be sound but imprecise" }
                    listOf(
                        Debug.startFunction("memcmp"),
                        TACCmd.Simple.AssigningCmd.AssignHavocCmd(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)),
                        Debug.endFunction("memcmp")
                    )
                }
                is TACMemSplitter.NonStackMemCmpInfo -> {
                    val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
                    val r1 = exprBuilder.mkVar(SbfRegister.R1_ARG)
                    val r2 = exprBuilder.mkVar(SbfRegister.R2_ARG)

                    val cmds = mutableListOf(Debug.startFunction("memcmp"))
                    // Read word-by word from the byte maps because there is no TAC instruction
                    // for comparison of ByteMap.
                    // REVISIT(SOUNDNESS):
                    // Soundness depends on all writes to the two memory regions to access exactly info.wordSize bytes.
                    val op1Vars = mapLoads(info.op1, r1, info.wordSize, info.length, cmds)
                    val op2Vars = mapLoads(info.op2, r2, info.wordSize, info.length, cmds)
                    cmds.add(assign(r0,  allEqual(op1Vars, op2Vars, cmds)))
                    cmds.add(Debug.endFunction("memcmp"))
                    cmds
                }
                is TACMemSplitter.StackMemCmpInfo -> {
                    val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)

                    val cmds = mutableListOf(
                        Debug.startFunction("memcmp", "(op1=Stack${info.op1Range}, op2=Stack${info.op2Range})")
                    )
                    cmds.add(assign(r0, allEqual(info.op1.map{it.tacVar}, info.op2.map{it.tacVar}, cmds)))
                    cmds.add(Debug.endFunction("memcmp"))
                    cmds
                }
                is TACMemSplitter.MixedRegionsMemCmpInfo -> {
                    val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
                    // scalars
                    val op1Vars = info.scalars

                    val cmds = mutableListOf(
                        Debug.startFunction("memcmp", "(${info.scalarsReg}=${info.stackOpRange})")
                    )
                    // byte map
                    // Read word-by-word from the byte map to be able to compare with the scalars.
                    // REVISIT(SOUNDNESS):
                    // Soundness depends on all writes to the non-scalar memory region to access exactly info.wordSize bytes.
                    val op2Vars = mapLoads(info.byteMap, exprBuilder.mkVar(info.byteMapReg), info.wordSize, info.length, cmds)
                    cmds.add(assign(r0, allEqual(op1Vars.map{it.tacVar}, op2Vars, cmds)))
                    cmds.add(Debug.endFunction("memcmp"))
                    cmds
                }
            }
        }
    }

    /**
    *  Translate a `memset` instruction to TAC
    *
    *  @param locInst is a memset(x,val,len) instruction
    **/
    private fun translateMemSet(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"TAC translateMemCopy expects a call instead of $inst"}

        val r0 = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
        val info = mem.getTACMemoryFromMemIntrinsic(locInst)
        return if (info == null) {
            unreachable(inst)
        } else {
            check(info is TACMemSplitter.MemsetInfo){"$info is not MemsetInfo"}
            return when (info) {
                is TACMemSplitter.UnsupportedMemsetInfo -> {
                    // We couldn't generate TAC code for the memset instruction.
                    // This might affect soundness because we don't havoc the destination.
                    sbfLogger.warn { "Unsupported TAC translation of $inst in block ${locInst.label}" }
                    listOf()
                }
                is TACMemSplitter.StackZeroMemsetInfo -> {
                    val len = info.length
                    val range = info.stackOpRange

                    val cmds = mutableListOf(Debug.startFunction("memset", "(Stack($range), 0)"))
                    for (i in 0 until len) {
                        val offset = PTAOffset(range.lb + i)
                        val pv = vFac.getByteStackVar(offset)
                        cmds.add(assign(pv.tacVar, exprBuilder.ZERO.asSym()))
                    }
                    cmds.add(Debug.endFunction("memset"))
                    cmds
                }
                is TACMemSplitter.NonStackMemsetInfo -> {
                    val len = info.length
                    val value = info.value
                    val byteMapV = info.byteMap

                    val cmds = if (len <= SolanaConfig.TACMaxUnfoldedMemset.get()) {
                        memsetNonStack(byteMapV, len, value)
                    } else {
                        memsetNonStackWithMapDef(byteMapV, len, value)
                    }
                    listOf(Debug.startFunction("memset", "(NonStack, $value, $len)")) +
                        cmds +
                        listOf(Debug.endFunction("memset"))
                }
            } + TACCmd.Simple.AssigningCmd.AssignHavocCmd(r0)
        }
    }

    private fun registerTypeFromUses(
        uses: Collection<LocatedSbfInstruction>, r: SbfRegister
    ): SbfType<TNumAdaptiveScalarAnalysis, TNumAdaptiveScalarAnalysis> {
        return uses.map {
            regTypes.typeAtInstruction(it, r)
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

            exprBuilder.mkVar(reg.r) to SbfFuncArgInfo(
                sort = sort,
                observedUse = observedUse
            )
        }
    }

    private fun translateCall(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) {"TAC translateCall expects a call instead of $inst"}
        if (inst.isAbort()) {
            // If the abort was added by the slicer then we skip it in TAC because it can cause problems to sanity rules
            return if (inst.metaData.getVal(SbfMeta.UNREACHABLE_FROM_COI) != null) {
                listOf()
            } else {
                unreachable(inst)
            }
        } else if (inst.isAllocFn()) {
            val size = (regTypes.typeAtInstruction(locInst, SbfRegister.R1_ARG) as? SbfType.NumType)?.value?.toLongOrNull()
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
                   heapMemAlloc.alloc(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE), sizeOrDefault.toULong()) +
                   listOf(Calltrace.externalCall(inst, listOf(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE))))
        } else {
            val cvtFunction = CVTFunction.from(inst.name)
            if (cvtFunction != null) {
                return when (cvtFunction) {
                    is CVTFunction.Core -> {
                        when (cvtFunction.value) {
                            CVTCore.ASSUME, CVTCore.ASSERT -> {
                                throw TACTranslationError("unsupported call to ${inst.name}. " +
                                    "SimplifyBuiltinCalls::renameCVTCall was probably not called.")
                            }
                            CVTCore.SANITY -> {
                                throw TACTranslationError("unsupported call to ${inst.name}.")
                            }
                            CVTCore.SATISFY ->
                                translateSatisfy(inst)
                            CVTCore.SAVE_SCRATCH_REGISTERS ->
                                translateSaveScratchRegisters(locInst)
                            CVTCore.RESTORE_SCRATCH_REGISTERS ->
                                translateRestoreScratchRegisters(inst)
                            CVTCore.MASK_64 ->
                                translateMask64()
                            CVTCore.NONDET_ACCOUNT_INFO -> {
                                if (!SolanaConfig.CvtNondetAccountInfo.get()) {
                                    /**
                                     * IMPORTANT: we don't treat this function as a summarized function for which we would do
                                     * symbolic allocation at the TAC level because this function is already precisely modeled at the
                                     * Rust level.
                                     */
                                    listOf(Debug.externalCall(inst))
                                } else {
                                    summarizeCall(locInst)
                                }
                            }
                            CVTCore.NONDET_SOLANA_ACCOUNT_SPACE -> {
                                val size = (regTypes.typeAtInstruction(locInst, SbfRegister.R1_ARG) as? SbfType.NumType)?.value?.toLongOrNull()
                                    ?: throw TACTranslationError("Cannot statically infer the size in $locInst")
                                listOf(Debug.externalCall(inst)) +
                                    accountsAlloc.alloc(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE), size) +
                                    listOf(Calltrace.externalCall(inst, listOf(exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE))))
                            }
                            CVTCore.ALLOC_SLICE ->
                                summarizeAllocSlice(locInst)
                        }
                    }
                    is CVTFunction.Nondet -> {
                        summarizeNondet(cvtFunction.value, inst)
                    }
                    is CVTFunction.Calltrace -> {
                        when (cvtFunction.value) {
                            CVTCalltrace.PRINT_I64_1, CVTCalltrace.PRINT_I64_2, CVTCalltrace.PRINT_I64_3,
                            CVTCalltrace.PRINT_TAG,
                            CVTCalltrace.PRINT_U64_1, CVTCalltrace.PRINT_U64_2, CVTCalltrace.PRINT_U64_3 -> {
                                listOf(Calltrace.printValueOrTag(locInst, cvtFunction))
                            }
                            CVTCalltrace.PRINT_U128 -> {
                                listOf(Calltrace.print128BitsValue(locInst, signed = false))
                            }
                            CVTCalltrace.PRINT_I128 -> {
                                listOf(Calltrace.print128BitsValue(locInst, signed = true))
                            }
                            CVTCalltrace.PRINT_U64_AS_FIXED -> {
                                listOf(Calltrace.printU64AsFixed(locInst))
                            }
                            CVTCalltrace.PRINT_U64_AS_DECIMAL -> {
                                listOf(Calltrace.printU64AsDecimal(locInst))
                            }
                            CVTCalltrace.PRINT_LOCATION -> {
                                listOf(Calltrace.printLocation(locInst))
                            }
                            CVTCalltrace.ATTACH_LOCATION -> {
                                listOf(Calltrace.attachLocation(locInst))
                            }
                            CVTCalltrace.PRINT_STRING -> {
                                listOf(Calltrace.printString(locInst))
                            }
                            CVTCalltrace.RULE_LOCATION -> {
                                listOf(Calltrace.ruleLocation(locInst))
                            }
                            CVTCalltrace.SCOPE_END -> {
                                listOf(Calltrace.endScope(locInst))
                            }
                            CVTCalltrace.SCOPE_START -> {
                                listOf(Calltrace.startScope(locInst))
                            }
                        }
                    }

                    is CVTFunction.U128Intrinsics -> {
                        if (SolanaConfig.UseTACMathInt.get()) {
                            summarizeU128(locInst)
                        } else {
                            sbfLogger.warn {"${locInst.inst} will not be modeled precisely in TAC. " +
                                "Enable ${SolanaConfig.UseTACMathInt.name} for a precise modeling" }
                            summarizeCall(locInst)
                        }
                    }
                    is CVTFunction.I128Intrinsics -> {
                        if (SolanaConfig.UseTACMathInt.get()) {
                            summarizeI128(locInst)
                        } else {
                            sbfLogger.warn {"${locInst.inst} will not be modeled precisely in TAC. " +
                                "Enable ${SolanaConfig.UseTACMathInt.name} for a precise modeling" }
                            summarizeCall(locInst)
                        }
                    }
                    is CVTFunction.NativeInt -> {
                        summarizeNativeInt(locInst)
                    }
                }
            }

            val solFunction  = SolanaFunction.from(inst.name)
            if (solFunction != null) {
                return when (solFunction) {
                    SolanaFunction.SOL_MEMCPY -> {
                        translateMemCopy(locInst)
                    }
                    SolanaFunction.SOL_MEMCMP -> {
                        translateMemCompare(locInst)
                    }
                    SolanaFunction.SOL_MEMSET -> {
                        translateMemSet(locInst)
                    }
                    SolanaFunction.SOL_GET_CLOCK_SYSVAR -> {
                        clock.get(locInst)
                    }
                    SolanaFunction.SOL_SET_CLOCK_SYSVAR -> {
                        clock.set(locInst)
                    }
                    SolanaFunction.SOL_GET_RENT_SYSVAR -> {
                        rent.get(locInst)
                    }
                    else -> {
                        summarizeCall(locInst)
                    }
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
        check(inst is SbfInstruction.Mem) {"TAC translateMem expects memory instruction instead of $inst"}
        val loadOrStore = mem.getTACMemory(locInst)
        return if (loadOrStore == null) {
            /* The instruction is unreachable */
            unreachable(inst)
        } else {
            when (loadOrStore) {
                is TACMemSplitter.StackLoadOrStoreInfo -> {
                    val newCmds = mutableListOf<TACCmd.Simple>()
                    val base = exprBuilder.mkVar((inst.access.baseReg).r).asSym()
                    val offset = exprBuilder.mkConst(inst.access.offset.toLong()).asSym()
                    if (inst.isLoad) {
                       newCmds += stackLoad(
                            base,
                            offset,
                            loadOrStore.variables,
                            loadOrStore.preservedValues,
                            exprBuilder.mkVar((inst.value as Value.Reg).r)
                        )
                    } else {
                        if (SolanaConfig.UsePTA.get()) {
                            // havoc any possible overlaps
                            val scalarsToHavoc = loadOrStore.locationsToHavoc
                            check(scalarsToHavoc is TACMemSplitter.HavocScalars) { "TAC translateMem expects HavocScalars" }

                            val havocMap = scalarsToHavoc.vars
                            when (havocMap.size) {
                                0 -> {}
                                1 -> newCmds += havocScalars(havocMap.toList().single().second)
                                else -> newCmds += weakHavocScalars(base, offset, havocMap)
                            }
                        }
                        newCmds += stackStore(
                            base,
                            offset,
                            loadOrStore.variables,
                            exprBuilder.mkExprSym(inst.value)
                        )
                    }
                    newCmds
                }
                is TACMemSplitter.NonStackLoadOrStoreInfo -> {
                    /* byte map variable */
                    val memVar = loadOrStore.variable
                    val baseReg = inst.access.baseReg
                    val offset = inst.access.offset
                    val newCmds = mutableListOf<TACCmd.Simple>()
                    val loc = computeTACMapIndex(exprBuilder.mkVar(baseReg), PTAOffset(offset.toLong()), newCmds)
                    if (inst.isLoad) {
                        val lhs = exprBuilder.mkVar((inst.value as Value.Reg).r)
                        newCmds.add(TACCmd.Simple.AssigningCmd.ByteLoad(lhs, loc, memVar.tacVar))
                    } else {
                        if (SolanaConfig.UsePTA.get()) {
                            // havoc any possible overlaps
                            val mapFieldsToHavoc = loadOrStore.locationsToHavoc
                            check(mapFieldsToHavoc is TACMemSplitter.HavocMapBytes) { "TAC translateMem expects HavocMapBytes" }
                            newCmds.addAll(havocByteMapLocation(mapFieldsToHavoc.vars, memVar, loc))
                        }
                        val value = when (inst.value) {
                            is Value.Imm -> { exprBuilder.mkConst(inst.value) }
                            is Value.Reg -> { exprBuilder.mkVar(inst.value) }
                        }
                        newCmds.add(TACCmd.Simple.AssigningCmd.ByteStore(loc, value, memVar.tacVar))
                    }
                    val baseRegType = regTypes.typeAtInstruction(locInst, baseReg.r)
                    newCmds.addAll(addMemoryLayoutAssumptions(loc, baseRegType))
                    newCmds
                }
            }
        }
    }

    private fun addSbfAddressAsMeta(stmts: List<TACCmd.Simple>, locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val address = locInst.inst.metaData.getVal(SbfMeta.SBF_ADDRESS)
        return if (address != null){
            check(address <= Long.MAX_VALUE.toULong()) {"Address $address is too big SVM"}
            stmts.map {
                val newMeta = it.meta.add(Pair(SBF_ADDRESS, address.toLong()))
                it.withMeta(newMeta)
            }
        } else {
            stmts
        }
    }

    private fun translate(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        sbfLogger.debug {"\tTAC translation of $inst"}
        val cmds = when (inst) {
            is SbfInstruction.Mem -> translateMem(locInst)
            is SbfInstruction.Bin -> translateBin(inst)
            is SbfInstruction.Un -> translateUn(inst)
            is SbfInstruction.Jump -> translateJump(locInst)
            is SbfInstruction.Havoc -> translateHavoc(inst)
            is SbfInstruction.Select -> translateSelect(inst)
            is SbfInstruction.Assert -> translateAssert(locInst)
            is SbfInstruction.Assume -> translateAssume(locInst)
            is SbfInstruction.Call -> translateCall(locInst)
            is SbfInstruction.Exit -> translateExit()
            is SbfInstruction.CallReg -> {
                if (!SolanaConfig.SkipCallRegInst.get()) {
                    throw TACTranslationError("unsupported $inst")
                } else {
                    listOf()
                }
            }
        }
        return addSbfAddressAsMeta(cmds, locInst)
    }

    private fun translate(bb: SbfBasicBlock): List<TACCmd.Simple> {
        check(cfg.getBlock(bb.getLabel()) != null){"Basic block ${bb.getLabel()} not found in CFG ${cfg.getName()}"}
        check(bb.getInstructions().isNotEmpty()){"A SbfBasicBlock should not be empty"}
        sbfLogger.debug {"TAC translation of block ${bb.getLabel()}"}
        val cmds: MutableList<TACCmd.Simple> = mutableListOf()
        for (locInst in bb.getLocatedInstructions()) {
            cmds.addAll(translate(locInst))
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
                cmds.addAll(addGlobalInitializers())
                cmds.addAll(addInitialPreconditions())
                cmds.addAll(translate(block))
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
            initCmds.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(v))
        }
        val entryCmds = code[tacEntryB]
        check(entryCmds != null) {"cannot find TAC code for the entry block"}
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
        return program
    }
}
