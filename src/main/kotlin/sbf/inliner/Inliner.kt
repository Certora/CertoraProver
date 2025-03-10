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

import sbf.callgraph.*
import sbf.cfg.*
import sbf.disassembler.*
import sbf.sbfLogger
import datastructures.stdcollections.*

class InlinerError(msg: String): RuntimeException("Inliner error: $msg")

private const val debugInliner = false
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
fun inline(entry: String, newEntry: String, prog: SbfCallGraph, inlineConfig: InlinerConfig): MutableSbfCallGraph {
    if (debugInliner) {
        sbfLogger.info {prog.callGraphStructureToString()}
    }
    val rootNames = prog.getCallGraphRoots().map{ it.getName()}
    check(rootNames.contains(entry)) {"Inliner: $entry is not a root of the callgraph. Known roots=$rootNames"}
    return Inliner(entry, newEntry, prog, inlineConfig).inline()
}

fun inline(entry: String, prog: SbfCallGraph, inlineConfig: InlinerConfig) = inline(entry, entry, prog, inlineConfig)

/**
 * This class reduces the API of `SbfCallGraph` to avoid calling methods that might use obsolete information
 * after some inliner action.
 **/
private class InlinerSbfCallGraph(private val prog: SbfCallGraph) {
    fun getGlobals(): GlobalVariableMap = prog.getGlobals()
    fun getCFG(name: String): SbfCFG? = prog.getCFG(name)
    // precondition: ensure that if a new CFG is created during inlining then
    // no existing CFG can call it.
    fun getRecursiveFunctions(): Set<String> = prog.getRecursiveFunctions()
    fun getStats(): CFGStats = prog.getStats()
}


/**
 * Inlining of SBF calls is easier than standard inlining
 * because we don't need to do variable renaming.
 **/
private class Inliner(val entry: String,
                      private val newEntry: String,
                      callgraph: SbfCallGraph,
                      private val inlinerConfig: InlinerConfig) {

    val prog = InlinerSbfCallGraph(callgraph)
    // To assign a unique identifier to each pair of CVT_save_scratch_registers/CVT_restore_scratch_registers
    private var callId: ULong = 0UL

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

    private fun inlineCalleeIntoCaller(callerCFG: MutableSbfCFG, callerBB: MutableSbfBasicBlock, call: SbfInstruction,
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

        // reset callee CFG
        calleeCFG.clear()

        val metaData = MetaData(Pair(SbfMeta.CALL_ID, callId)).plus(
            Pair(SbfMeta.INLINED_FUNCTION_NAME, calleeCFG.getName())
        )

        val saveRegistersInst = SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = metaData)
        val restoreRegistersInst = SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = metaData)
        callId++
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
            if (debugInliner) {
                sbfLogger.info { "${cfg.getName()} does not have calls" }
            }
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
                    if (debugInliner) {
                        sbfLogger.info { "${calleeCFG.getName()} is recursive" }
                    }
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
                if (debugInliner) {
                    sbfLogger.info { "${calleeCFG.getName()} is marked as never inline" }
                }
                continue
            }
            val clonedCalleeCFG = copy(calleeCFG)
            inlineFunction(clonedCalleeCFG, callStack, recursiveFunctions, nextDepth)
            if (debugInliner) {
                sbfLogger.info { "Inlining ${calleeCFG.getName()} into ${cfg.getName()}" }
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
                    if (!inst.isExternalFn()) {
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

    fun inline(): MutableSbfCallGraph {


        val entryCFG = prog.getCFG(entry)?.clone(newEntry)
        check(entryCFG != null) {"Inliner expects a callgraph with a single root"}
        val statsBefore = prog.getStats()
        // Start inlining from entry point
        inlineFunction(entryCFG, arrayListOf(), prog.getRecursiveFunctions(), 0)
        entryCFG.simplify(prog.getGlobals())
        entryCFG.normalize()
        renameCVTCalls(entryCFG)
        entryCFG.verify(true, "After inline+simplify")
        val statsAfter = entryCFG.getStats()
        // Ideally inlineProg should contain only one function.
        // However, if there are recursive calls or blacklisted functions then
        // we need to include other functions.

        val cfgs = addEmptyStubs(entryCFG)

        val sb = StringBuilder()
        sb.append("INLINING INFO\nBefore inlining\n$statsBefore\n" +
            "After inlining\n$statsAfter\n" +
            "Functions that were NOT inlined and created an empty STUB for them:\n")
        for (stub in cfgs.toSortedSet { x, y -> x.getName().compareTo(y.getName()) }) {
            sb.append("\t${stub.getName()}\n")
        }
        sbfLogger.info {sb.toString()}

        cfgs.add(entryCFG)
        return MutableSbfCallGraph(cfgs, setOf(entryCFG.getName()), prog.getGlobals())
    }
}



