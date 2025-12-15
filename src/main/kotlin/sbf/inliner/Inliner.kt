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

package sbf.inliner

import allocator.Allocator
import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import datastructures.stdcollections.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import sbf.SolanaConfig
import sbf.domains.MemorySummaries
import utils.*
import java.io.File

class InlinerError(msg: String): RuntimeException("Inliner error: $msg")

/** This bound is set by the Solana runtime **/
const val SBF_CALL_MAX_DEPTH = 64

/**
 * Entry point for the inliner.
 *
 * Return a new call graph where the root is [newEntry] and the rest of functions have been inlined unless
 * they are recursive or the user didn't want to inline.
 *
 * The inliner starts from [newEntry] (before it clones [entry] into [newEntry])
 * and folds each callee body into its caller by cloning callee CFG into caller.
 */
fun inline(entry: String, newEntry: String, prog: SbfCallGraph, memSummaries: MemorySummaries, inlineConfig: InlinerConfig): MutableSbfCallGraph {
    sbfLogger.debug {"[Inliner]\n" + prog.callGraphStructureToString()}

    val rootNames = prog.getCallGraphRoots().map{ it.getName()}
    check(rootNames.contains(entry)) {"Inliner: $entry is not a root of the callgraph. Known roots=$rootNames"}
    return Inliner(entry, newEntry, prog, memSummaries, inlineConfig).inline()
}

fun inline(entry: String, prog: SbfCallGraph, memSummaries: MemorySummaries, inlineConfig: InlinerConfig) =
    inline(entry, entry, prog, memSummaries, inlineConfig)

/**
 * This class reduces the API of `SbfCallGraph` to avoid calling methods that might use obsolete information
 * after some inliner action.
 **/
private class InlinerSbfCallGraph(private val prog: SbfCallGraph) {
    fun getGlobals(): GlobalVariables = prog.getGlobals()
    fun getCFG(name: String): SbfCFG? = prog.getCFG(name)
    // precondition: ensure that if a new CFG is created during inlining then
    // no existing CFG can call it.
    fun getRecursiveFunctions(): Set<String> = prog.getRecursiveFunctions()
    fun getStats(): CFGStats = prog.getStats()
    fun getTransitivelyPreservedCFGs(): Set<String> = prog.getTransitivelyPreservedCFGs()
    fun getPreservedCFGs(): Set<String> = prog.getPreservedCFGs()
}


/**
 * Inlining of SBF calls is easier than standard inlining
 * because we don't need to do variable renaming.
 **/
private class Inliner(val entry: String,
                      private val newEntry: String,
                      callgraph: SbfCallGraph,
                      private val memSummaries: MemorySummaries,
                      private val inlinerConfig: InlinerConfig) {

    val prog = InlinerSbfCallGraph(callgraph)
    // For debugging only
    private val numOfInsts = mutableMapOf<String, ULong>()

    init {
        for (cfg in callgraph.getCFGs()) {
            val k = cfg.getBlocks().values.sumOf { b ->
                b.getInstructions().size
            }
            numOfInsts[cfg.getName()] = k.toULong()
        }
    }

    private fun hasCalls(cfg: SbfCFG): Boolean {
        return cfg.getBlocks().values.any { b->
            b.getInstructions().any {
                it is SbfInstruction.Call && !it.isExternalFn()
            }
        }
    }

    private fun copy(cfg: SbfCFG): MutableSbfCFG {
        // Clone the CFG
        val clonedCFG = cfg.clone(cfg.getName())
        // In-place renaming starting from curLabel
        clonedCFG.renameLabels()
        return clonedCFG
    }

    private fun ensureAtMostOneCallPerBlock(cfg: MutableSbfCFG) {
        fun findSecondCall(bb: SbfBasicBlock): SbfInstruction.Call? {
            var found = false
            for (inst in bb.getInstructions()) {
                if (inst is SbfInstruction.Call) {
                    if (prog.getCFG(inst.name) != null) {
                        if (!found) {
                            found = true
                        } else {
                            return inst
                        }
                    }
                }
            }
            return null
        }

        val worklist = ArrayList<MutableSbfBasicBlock>()
        worklist.addAll(cfg.getMutableBlocks().values)
        var exitB = cfg.getMutableExit()
        while (worklist.isNotEmpty()) {
            var b = worklist.removeLast()
            do {
                val secondCall = findSecondCall(b)
                if (secondCall != null) {
                    // Add new basic blocks but do not change any block in worklist
                    val isExit = (b.getLabel() == exitB.getLabel())
                    b = splitBasicBlock(cfg, b, secondCall)
                    if (isExit) {
                        exitB = b
                    }
                    // Add the call in the new continuation block
                    b.add(0, secondCall)
                }
            } while (secondCall != null)
        }
        cfg.setExit(exitB)
    }

    private fun splitBasicBlock(cfg: MutableSbfCFG, bb: MutableSbfBasicBlock, call: SbfInstruction)
        : MutableSbfBasicBlock {
        /**
         * Transform
         * ```
         * bb:
         *   inst1
         *   inst2
         *   call
         *   inst3
         *   ...
         *```
         * into:
         * ```
         * bb:
         *   inst1
         *   inst2
         *   goto bb'
         *
         * bb':
         *   inst3
         *   ...
         * ```
         * and returns `bb'`
         */

        val i = bb.getInstructions().indexOfFirst { it === call }
        check(i >= 0) { "Call $call should be found in $bb" }
        // Remove the call
        bb.removeAt(i)
        return cfg.splitBlock(bb.getLabel(), i - 1)
    }

    private fun inlineCalleeIntoCaller(callerCFG: MutableSbfCFG,
                                       callerBB: MutableSbfBasicBlock,
                                       call: SbfInstruction.Call,
                                       calleeCFG: MutableSbfCFG) {
        val continuationBB = splitBasicBlock(callerCFG, callerBB, call)
        if (callerBB.getLabel() == callerCFG.getExit().getLabel()) {
            callerCFG.setExit(continuationBB)
        }

        val calleeEntry = calleeCFG.getMutableEntry()
        val calleeExit = calleeCFG.getMutableExit()

        // copy callee's blocks into caller
        for (kv in calleeCFG.getMutableBlocks()) {
            callerCFG.setBlock(kv.key, kv.value)
        }

        val numCalleeInsts: ULong = calleeCFG.getBlocks().values.sumOf {
            it.getInstructions().size.toULong()
        }

        // reset callee CFG
        calleeCFG.clear()

        val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
        check(callId >= 0) {"expected non-negative call id"}
        val mockFor = call.metaData.getVal(SbfMeta.MOCK_FOR)
        val metaData = call.metaData.plus(
            SbfMeta.CALL_ID to callId.toULong()).plus(
            SbfMeta.INLINED_FUNCTION_NAME to calleeCFG.getName()).plus(
            SbfMeta.INLINED_FUNCTION_SIZE to numCalleeInsts
            ).let { md ->
                // Transfer the CPI mock for as well.
                if (mockFor != null) {
                    md.plus(SbfMeta.MOCK_FOR to mockFor)
                } else {
                    md
                }
            }

        val saveRegistersInst = SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = metaData)
        val restoreRegistersInst = SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = metaData)
        // r10 += 4096
        val increaseFramePtrInst = SbfInstruction.Bin(BinOp.ADD, Value.Reg(SbfRegister.R10_STACK_POINTER),
                                                        Value.Imm(SBF_STACK_FRAME_SIZE.toULong()), true)
        // r10 -= 4096
        val decreaseFramePtrInst = SbfInstruction.Bin(BinOp.SUB, Value.Reg(SbfRegister.R10_STACK_POINTER),
                                                        Value.Imm(SBF_STACK_FRAME_SIZE.toULong()), true)

        // Wire up blocks
        callerBB.removeAt(callerBB.numOfInstructions() -1)  // remove goto continuationBB
        callerBB.add(saveRegistersInst)
        callerBB.add(SbfInstruction.Jump.UnconditionalJump(calleeEntry.getLabel()))

        callerBB.removeSuccs()
        callerBB.addSucc(calleeEntry)
        calleeEntry.add(0, increaseFramePtrInst)

        continuationBB.add(0, decreaseFramePtrInst)
        continuationBB.add(1, restoreRegistersInst)
        calleeExit.removeAt(calleeExit.numOfInstructions() - 1)  // remove exit instruction
        calleeExit.add(SbfInstruction.Jump.UnconditionalJump(continuationBB.getLabel()))
        calleeExit.removeSuccs()
        calleeExit.addSucc(continuationBB)
    }

    // The correctness of the inliner relies on having at most one call per basic block.
    // This is guaranteed by previous CFG transformations.
    private fun inlineFunction(cfg: MutableSbfCFG, callStack: ArrayList<String>,
                               recursiveFunctions: Set<String>, curDepth: Int) {

        if (!hasCalls(cfg)) {
            return
        }

        if (curDepth == SBF_CALL_MAX_DEPTH) {
            throw InlinerError("maximum call depth $SBF_CALL_MAX_DEPTH reached")
        }

        // This will transform cfg to make sure that there is at most one call per basic block
        ensureAtMostOneCallPerBlock(cfg)
        cfg.verify(true, "after ensureAtMostOneCallPerBlock")
        callStack.add(cfg.getName())

        // We collect first all callsites because we will modify the caller CFG during inlining.
        val worklist: ArrayList<Pair<MutableSbfBasicBlock, LocatedSbfInstruction>> = arrayListOf()
        for (bb in cfg.getMutableBlocks().values) {
            for (locInst in bb.getLocatedInstructions()) {
                val inst = locInst.inst
                if (inst is SbfInstruction.Call) {
                    if (prog.getCFG(inst.name) != null) {
                        worklist.add(Pair(bb, locInst))
                    }
                }
            }
        }

        val nextDepth = curDepth + 1
        while (worklist.isNotEmpty()) {
            val (callerBB, locInst) = worklist.removeLast()
            val callSite = locInst.inst
            check(callSite is SbfInstruction.Call)
            val calleeCFG = prog.getCFG(callSite.name)
            check(calleeCFG != null)
            if (recursiveFunctions.contains(calleeCFG.getName())) {
                // recursiveFunctions is populated from finding cycles in the callgraph which is an over-approximation.
                // If the callee is in recursiveFunctions then we check if it's already in the current call stack.
                if (callStack.contains(calleeCFG.getName())) {
                    sbfLogger.debug { "[Inliner] ${calleeCFG.getName()} is marked as recursive" }
                    continue
                }
            }
            val inlineSpec = inlinerConfig.getInlineSpec(calleeCFG.getName())
            if (inlineSpec is InlineSpec.NeverInline) {
                callerBB.replaceInstruction(
                    locInst,
                    callSite.copy(
                        metaData = callSite.metaData + (SbfMeta.KNOWN_ARITY to inlineSpec.arity)
                    )
                )
                continue
            }
            val clonedCalleeCFG = copy(calleeCFG)
            inlineFunction(clonedCalleeCFG, callStack, recursiveFunctions, nextDepth)
            sbfLogger.debug {
                    "[Inliner] inlining ${calleeCFG.getName()} into ${cfg.getName()} " +
                        "num of insts = ${numOfInsts[calleeCFG.getName()]}"
            }
            inlineCalleeIntoCaller(cfg, callerBB, callSite, clonedCalleeCFG)
        }
        callStack.removeLast()
    }

    /** Generate empty stubs for functions being called in cfg.
     *  When this function is called, all called functions should have been inlined.
     *  The ones that cannot be inlined are replaced with empty stubs.
     **/
    private fun addEmptyStubs(cfg: SbfCFG): ArrayList<MutableSbfCFG> {
        val worklist: MutableSet<String> = mutableSetOf()
        for (b in cfg.getBlocks().values) {
            for (inst in b.getInstructions()) {
                if (inst is SbfInstruction.Call) {
                    // If the instruction is in the preserved CFGs, we do not add any empty stub.
                    if (!inst.isExternalFn() && inst.name !in prog.getTransitivelyPreservedCFGs()) {
                        worklist.add(inst.name)
                    }
                }
            }
        }
        val stubs = ArrayList<MutableSbfCFG>()
        for (name in worklist ) {
            val stub = MutableSbfCFG(name)
            val block = stub.getOrInsertBlock(Label.fresh())
            block.add(SbfInstruction.Exit())
            stub.setEntry(block)
            stub.setExit(block)
            stubs.add(stub)
        }

        return stubs
    }

    private fun printExternalFunctions(extFunctions: List<String>) {
        if (extFunctions.isEmpty()) {
            return
        }

        val msg = "The following functions are neither inlined nor summarized. They are treated as external. This is likely to affect soundness"

        // Report it also as warning to the global notifications tab.
        CVTAlertReporter.reportAlert(
            type = CVTAlertType.SUMMARIZATION,
            severity = CVTAlertSeverity.WARNING,
            message =  "${msg}. Consult logs and documentation for details on how to fix. ${extFunctions.joinToString(prefix = "[", postfix = "]")}",
            jumpToDefinition = null,
            url = CheckedUrl.SOLANA_INLINING)

        val sb = StringBuilder()
        sb.append("\n.$msg\n\n")
        extFunctions.forEach {
            sb.append("\t$it\n")
        }
        sb.append("\n")
        sb.append("If you are seeing this message for the first time (i.e., during the initial project setup). Do one of the following (in order of preference):\n")
        sb.append("\n(1) Add the following lines in one of sbf prover inlining files located in ${File(SolanaConfig.InlineFilePath.get()).parent} " +
            "and regenerate again ${SolanaConfig.InlineFilePath.get()}\n\n")
        extFunctions.forEach { sb.append("  #[inline] ^$it$\n") }
        sb.append("\n(2) Add a summary for each function in one of the sbf prover summary files located in ${File(SolanaConfig.SummariesFilePath.get()).parent} " +
            "and regenerate again ${SolanaConfig.SummariesFilePath.get()}\n")
        sb.append("\nThe syntax for adding summaries follows this grammar:\n\n${MemorySummaries.grammar}\n")
        sb.append("\n(3) Do nothing if the function does not have side-effects or its effects are not modeled by the prover (e.g., dealloc/drop functions)\n")
        sb.append("\nBeware: solution (1) is the safest, but is likely to create performance issues and/or pointer analysis errors.\n")
        sbfLogger.warn {sb.toString()}
    }

    fun inline(): MutableSbfCallGraph {
        val entryCFG = prog.getCFG(entry)?.clone(newEntry)
        val preservedCFGs: Set<SbfCFG> = prog.getTransitivelyPreservedCFGs().mapNotNull { prog.getCFG(it) }.toSet()
        check(entryCFG != null) {"Inliner expects a callgraph with a single root"}
        val statsBefore = prog.getStats()
        // Start inlining from entry point
        inlineFunction(entryCFG, arrayListOf(), prog.getRecursiveFunctions(), 0)
        entryCFG.simplify(prog.getGlobals())
        entryCFG.normalize()
        renameCVTCalls(entryCFG)
        entryCFG.verify(true, "After inline+simplify")
        val statsAfter = entryCFG.getStats()

        sbfLogger.info { "INLINING INFO\nBefore inlining\n$statsBefore\n" +
                         "After inlining\n$statsAfter\n\n"
        }

        // Ideally inlineProg should contain only one function.
        // However, if there are recursive calls or blacklisted functions then
        // we need to include other functions.
        val cfgs = addEmptyStubs(entryCFG)

        printExternalFunctions(
            cfgs.toSortedSet { x, y -> x.getName().compareTo(y.getName()) }.filter {
                !sbf.domains.hasSummary(it.getName(), memSummaries)
            }.map{ it.getName() }
        )

        cfgs.add(entryCFG)
        // Add back the preservedCFGs.
        cfgs.addAll(preservedCFGs.map { it.clone(it.getName()) })
        return MutableSbfCallGraph(cfgs, setOf(entryCFG.getName()), prog.getGlobals(), preservedCFGs = prog.getPreservedCFGs())
    }

}



