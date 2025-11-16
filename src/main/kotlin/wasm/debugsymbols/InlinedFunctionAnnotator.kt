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

package wasm.debugsymbols

import allocator.Allocator
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import aws.smithy.kotlin.runtime.util.pop
import aws.smithy.kotlin.runtime.util.push
import config.Config
import datastructures.stdcollections.*
import dwarf.DWARFTreeNode
import report.checkWarn
import wasm.cfg.PC
import wasm.impCfg.Control
import wasm.impCfg.StraightLine
import wasm.impCfg.WasmBlock
import wasm.impCfg.WasmImpCfgProgram
import wasm.ir.WASMAddress

/**
 * This class is responsible to process the DWARF DebugSymbols and generate [InlinedFuncStartAnnotation] and [InlinedFuncEndAnnotation]
 * from the debug symbols. This allows us to identify to which function an instruction originally belonged. Note that
 * DWARF also maintains the information of function that have been inlined by the LLVM compiler and so we are also
 * able to identify such cases.
 *
 * Technically, this class traverses all instructions of the CFG using a worklist algorithm and - using the DWARF debug symbols
 * computes the deltas between each instruction and then pushes or pops the start or respective end annotation.
 */
class InlinedFunctionAnnotator(private val wasmDebugSymbols: WasmDebugSymbols) {
    companion object {
        private var annotationCounter = 0
        fun nextAnnotationId(): Int = annotationCounter++
    }

    private fun hasByteCodeAddress(cmd: StraightLine) = cmd.addr != null
    private fun getStack(addr: WASMAddress) = wasmDebugSymbols.lookUpCallStack(addr).map { it.copy(high_pc_value = - 1, low_pc_value = - 1) }

    data class WorklistElement<T>(val pc: PC, val stackBefore: List<T>?)
    data class BlockResult(val stackBefore: List<DWARFTreeNode>?, val stackAfter: List<DWARFTreeNode>?)

    /**
     * Takes a call stack before an instruction and a call stack after an instruction and
     * computes which elements need to be popped from the stack before the instruction and which elements
     * needs to be pushed onto the call stack.
     *
     * Example:
     * [foo, bar, baz, bas]
     * [foo, bar, jazz, club]
     *
     * the result is
     * pop base, pop baz, push jazz, push club
     */
    private fun generateAnnotations(prevStack: List<DWARFTreeNode>, nextStack: List<DWARFTreeNode>): List<StraightLine> {
        val sharedStack = nextStack.zip(prevStack).takeWhile { it.first == it.second }.map { it.first }
        val toPop = prevStack.drop(sharedStack.size)
        val toPush = nextStack.drop(sharedStack.size)
        // The annotationId is only kept to make the InlineFunc*Annotation unique
        // There is a pass that will add the annotation to a hash set, this means we will lose an annotation if they don't differ.
        val id = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val popAnnot = toPop.reversed().map { p -> StraightLine.InlinedFuncEndAnnotation(p.asName(), nextAnnotationId(), id) }
        val pushAnnot = toPush.map { StraightLine.InlinedFuncStartAnnotation(it.asName(), listOf(), nextAnnotationId(), id, it.getRange()) }
        return popAnnot + pushAnnot
    }


    /**
     * This method takes the [WasmImpCfgProgram] and iterates over the control flow of the method and
     * uses the debug symbol information to annotate entries and exist of LLVM compiler inlined functions.
     *
     * For blocks this computation is straight forward as of the linear sequence of instructions: Take instruction x and its successor
     * instruction y and compute the difference in their "call stacks" that [debugSymbol.lookUpScope] returns. During this
     * computation for the basic blocks, we persist the call stacks at the entry and exit of the block and then
     * perform a worklist iteration over the edges of the CFG. We then compute the differences of the call stack at the exit
     * of the last node and the entry of the successor node and insert a new edge into the control flow. The new edge has
     * a basic block containing the [InlinedFuncStartAnnotation] and [InlinedFuncEndAnnotation] required along the edge.
     */
    fun addInlinedFunctionAnnotations(input: WasmImpCfgProgram): WasmImpCfgProgram {

        fun processBlock(wasmBlock: WasmBlock): Pair<BlockResult, WasmBlock?> {
            val newStraights = mutableListOf<StraightLine>()
            var firstStack: List<DWARFTreeNode>? = null
            var prevStack: List<DWARFTreeNode>? = null
            var modified = false
            for (s in wasmBlock.straights) {
                if (hasByteCodeAddress(s)) {
                    val newStack = getStack(s.addr!!)
                    if (firstStack == null) {
                        firstStack = newStack
                    }
                    if (prevStack != null) {
                        val annots = generateAnnotations(prevStack, newStack)
                        modified = modified || annots.isNotEmpty();
                        newStraights.addAll(annots)
                    }
                    prevStack = newStack
                }
                newStraights.add(s)
            }
            return if (modified) {
                BlockResult(firstStack, prevStack) to wasmBlock.copy(straights = newStraights)
            } else {
                BlockResult(firstStack, prevStack) to null
            }
        }

        /**
         * Iterate over all blocks and add [InlinedFuncStartAnnotation] and [InlinedFuncEndAnnotation] annotation that are
         * internal to the block.
         */
        val pcToEntryAndExit = input.getNodes().mapValues { e ->
            val r = processBlock(e.value)
            r.second?.let { b -> input.updateNode(e.key, b) }
            r.first
        }.toMutableMap()

        object : VisitingWorklistIteration<WorklistElement<DWARFTreeNode>, Unit, Boolean>() {
            override fun process(it: WorklistElement<DWARFTreeNode>): StepResult<WorklistElement<DWARFTreeNode>, Unit, Boolean> {
                val nextWorklistElements = mutableSetOf<WorklistElement<DWARFTreeNode>>()
                for (succ in input.succs(it.pc)) {
                    if (pcToEntryAndExit[succ]?.stackBefore != null) {
                        val prevStack = it.stackBefore.orEmpty()
                        val nextStack = pcToEntryAndExit[succ] !!.stackBefore !!
                        val newStraights = generateAnnotations(prevStack, nextStack)
                        if (newStraights.isNotEmpty()) {
                            val newBid = input.insertStraightsAlongEdge(it.pc, succ, newStraights)
                            pcToEntryAndExit[newBid] = BlockResult(prevStack, nextStack)
                        }
                        nextWorklistElements.add(WorklistElement(succ, pcToEntryAndExit[succ] !!.stackAfter))
                    } else {
                        //The PC succ doesn't modify the stack, so we just propagate the stack from the last element of the worklist.
                        nextWorklistElements.add(WorklistElement(succ, it.stackBefore))
                    }
                }
                val currNode = input.getNodes()[it.pc] !!
                if (currNode.ctrl is Control.Ret) {
                    val popAnnot = generateAnnotations(it.stackBefore.orEmpty(), listOf())
                    if (popAnnot.isNotEmpty()) {
                        input.updateNode(it.pc, currNode.copy(straights = currNode.straights + popAnnot))
                    }
                }

                return this.cont(nextWorklistElements)
            }

            override fun reduce(results: List<Unit>): Boolean {
                return true
            }

        }.submit(listOf(WorklistElement(input.entryPt, listOf())))

        // Perform a sanity check
        checkCallStackBalancing(input)

        return input
    }

    /**
     * This function asserts the analysis that inserted [InlinedFuncStartAnnotation] and [InlinedFuncEndAnnotation]
     * creates correct call stacks.
     *
     * This means
     * a) at the exit of the function (indicated by [Control.Ret]) the call stack is always empty. (we start with an empty stack at the entry of each function)
     * b) whenever a call is closed encoded as [InlinedFuncEndAnnotation], the last see opening call was indeed an [InlinedFuncStartAnnotation] with the same function name.
     *
     * This check fails hard on the CI, but only produces a warning to Results.txt.
     */
    private fun checkCallStackBalancing(input: WasmImpCfgProgram) {
        fun processBlock(wasmBlock: WasmBlock, currentCallStack: List<String>): List<String> {
            return wasmBlock.straights.fold(currentCallStack.toMutableList()) { currentStack, s ->
                if (s is StraightLine.InlinedFuncStartAnnotation) {
                    currentStack.push(s.funcId)
                }

                if (s is StraightLine.InlinedFuncEndAnnotation) {
                    //For every InlinedFuncEndAnnotation we must have seen a matching [InlinedFuncStartAnnotation] and it must be on top of the current stack.
                    val msg = "Expects call stack's top element to be ${s.funcId}, but was ${currentStack.lastOrNull()}. The call trace may incorrectly close too many nesting levels."
                    if (Config.IsCIMode.get()) {
                        check(currentStack.lastOrNull() == s.funcId) { msg }
                    } else {
                        checkWarn(currentStack.lastOrNull() == s.funcId) { msg }
                    }
                    if (currentStack.isNotEmpty()) {
                        currentStack.pop()
                    }
                }
                currentStack
            }.toList()
        }

        object : VisitingWorklistIteration<WorklistElement<String>, Unit, Boolean>() {
            override fun process(it: WorklistElement<String>): StepResult<WorklistElement<String>, Unit, Boolean> {
                val currNode = input.getNodes()[it.pc] !!
                val stackBefore = it.stackBefore.orEmpty()
                val succNode = input.getNodes()[it.pc] !!
                val stackAfter = processBlock(succNode, stackBefore)

                // Checks that the stack is empty at any exit of the current function
                if (currNode.ctrl is Control.Ret) {
                    val msg = "Expects call stack to be empty at the end of a method, but it still contained ${stackAfter}. The call trace may incorrectly open too many nesting levels."
                    if (Config.IsCIMode.get()) {
                        check(stackAfter.isEmpty()) { msg }
                    } else {
                        checkWarn(stackAfter.isEmpty()) { msg }
                    }
                }
                return this.cont(input.succs(it.pc).map { next -> WorklistElement(next, stackAfter) })
            }

            override fun reduce(results: List<Unit>): Boolean {
                return true
            }

        }.submit(listOf(WorklistElement(input.entryPt, /* Seed the analysis with an empty stack at the beginning of the function */listOf())))
    }
}
