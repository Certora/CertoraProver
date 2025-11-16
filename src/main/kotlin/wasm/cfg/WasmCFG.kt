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

package wasm.cfg

import algorithms.getReachable
import datastructures.stdcollections.*
import utils.*
import wasm.ir.*
import wasm.tokens.WasmTokens.ENTRYPOINT

/**
 * Right now, a PC is just an `int`.
 * Another way to do it would be to make it a list of `int`s which I tried but it got too confusing.
 * */
typealias PC = Int

typealias LabelStack = List<PC>

/**
 * A WasmCFG has a
 * @property graph A mapping from PCs to tuples of WasmCFGInstr and their successors.
 *             A successor of an instruction
 *             is a PC that represents where the control can flow after this instruction.
 *             Intuitively, think of it as a table that maps PCs to (instructions and their succs).
 * @property fresh A PC that we bump when adding new nodes to the graph. Note that we make fresh 1 initially because
 *                 later on in the pipeline ReachVarEquation.kt wants the start block to have ID 0.
 * @property entry The entry point to the function whose CFG this object represents.
 * @property dataSegments The list of data segments in the source wasm module
 * */
class WasmCFG(
    private val graph: MutableMap<PC, Pair<WasmCFGInstruction, List<PC>>>,
    private var fresh: Int = 1,
    private val entry: Int,
    val dataSegments: List<WasmData>,
) {
    /**
     * Given a graph, add a new "node" for the instruction, [cfgInstr], to it at a fresh PC.
     * A node is a WasmCFGInstr and its successors, [succs].
     * This mutates the graph obtained after the node is added.
     * The value of `fresh` is also bumped.
     * It returns the PC of the new instruction that was added ([cfgInstr]) by subtracting 1 from
     * the bumped fresh PC value.
     * */
    fun addNode(cfgInstr: WasmCFGInstruction, succs: List<PC>): PC {
        val new = fresh++
        graph[new] = Pair(cfgInstr, succs)
        return new
    }

    private fun updateNode(pc: PC, instr: WasmCFGInstruction, succs: List<PC>) {
        check(pc in graph.keys)
        graph[pc] = Pair(instr, succs)
    }

    fun getPCs(): List<PC> {
        return graph.keys.toList()
    }
    fun getGraph() = graph
    fun getEntry() = entry

    fun find(pc: PC): Pair<WasmCFGInstruction, List<PC>>? {
        return graph[pc]
    }

    /**
     * Returns all successors for an instruction, represented by its PC, `pc`.
     * */
    fun succs(pc: PC): List<PC>? {
        return graph[pc]?.second
    }

    /**
     * Returns all the predecessors of the instruction represented by this `pc`.
     * We go over all (p, node) entries of this graph.
     * If this [pc] appears in the succ list for any of the nodes, we add `p` to the preds list of `pc`.
     *
     * */
    fun preds(pc: PC): List<PC> {
        return graph.keysMatching { _, node ->  pc in node.second}
    }

    /**
     * Returns a set of PCs that are reachable from the PC [from] in the given `cfg`.
     * */
    private fun reachableFrom(from: PC): Set<PC> {
        return getReachable(listOf(from), this::succs)
    }

    /**
     * Recall that a wasm cfg has an entry point that is the first instruction of the function from which
     * the cfg is made and it also has a graph which is a mapping from PCs to (wasm cfg instruction, succs) pairs.
     * `pruneUnreachableFrom` takes a CFG and its entry point and removes any nodes in
     * the graph that are not reachable from the entry point ([from]).
     * It returns the pruned CFG and also a list of all pruned PCs.
     * */
    fun pruneUnreachables(from: PC): Pair<WasmCFG, Set<PC>> {
        val reachables = reachableFrom(from)
        val nodes = mutableMapOf<PC, Pair<WasmCFGInstruction, List<PC>>>()
        val prunedPCs = mutableSetOf<PC>()
        for((pc, node) in graph.entries) {
            if (pc in reachables) {
                nodes[pc] = node
            } else {
                prunedPCs.add(pc)
            }
        }
        return WasmCFG(nodes, fresh, entry, dataSegments) to prunedPCs
    }

    /*
    * To add an instruction, you must know its succs, i.e.,
    * the successors of the node representing that instruction.
    * This updates the graph with the newly added instruction and returns the
    * PC of the added instruction.
    * */
    private fun addWasmInstruction(labels: LabelStack, instr: WasmInstruction, succ: PC): PC {
        return when (instr) {
            is WasmInstruction.NondetStub -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Numeric -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Variable -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Memory -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Parametric -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Control.Nop  -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Control.Call -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Control.CallIndirect -> {
                addNode(instr, listOf(succ))
            }
            is WasmInstruction.Control.Return -> {
                addNode(instr , listOf())
            }
            is WasmInstruction.Control.Unreachable -> {
                addNode(instr , listOf())
            }
            is WasmInstruction.Control.BrIf -> {
                addNode(instr, listOf(labels[instr.label], succ))
            }
            is WasmInstruction.Control.Br -> {
                addNode(WasmInstruction.Control.Nop(instr.address), listOf(labels[instr.label]))
            }
            is WasmInstruction.Control.BrTable -> {
                addNode(instr, instr.labels.map { labels[it] })
            }
            is WasmInstruction.Control.Block -> {
                addWasmInstructions(listOf(succ) + labels, instr.instrs, succ)
            }
            /* Loop is a bit tricky because we need to know what the PC is for
                the program point to which a br or brif should go back (unlike block, for loop it's the top of the
                loop). We add a wasm.ir.Nop at the top of the loop and give it a dummy PC of -1.
                */
            is WasmInstruction.Control.Loop -> {
                val loopPc = addNode(WasmInstruction.Control.Nop(instr.address), listOf(-1))
                val topSucc = addWasmInstructions(listOf(loopPc) + labels, instr.instrs, succ)
                updateNode(loopPc, WasmInstruction.Control.Nop(instr.address), listOf(topSucc))
                loopPc
            }

            is WasmInstruction.Control.IfElse -> {
                val falsePc = instr.elseInstrs?.let { addWasmInstructions(listOf(succ) + labels, it, succ) } ?: succ
                val truePc = addWasmInstructions(listOf(succ) + labels, instr.ifInstrs, succ)
                addNode(instr, listOf(truePc, falsePc))
            }
        }
    }

    /**
     * Add a list of instructions ([instrs]) to a graph. This also takes a [succ] which
     * represents where control should flow after this list of instructions are added.
     * [labels] represents the list of PCs that control can flow to.
     * So, in this method, we are adding the instructions to a wasm cfg starting at the last instruction of the function.
     * In fact, we always add a `return` statement at the end of a function and assign it the
     * PC 1 (see in `wasmAstToWasmCfg`) and work backwards to add the rest of the instructions
     * until we reach the top of the function.
     *
     * The nice thing about this design is that we always know the successor of every instruction (we just added it!).
     * The reason we need to track the labels is that we need to be able to resolve the PC for where control should go
     * for instructions like `br` or `loop`. The control for `br` for example always goes to the end of the innermost block.
     * So as you can see in the [addInstruction] case for `br N`, the [succ] in the [addNode] call is the `N`th element in labels.
     * `addInstruction` for `block` is where the top of the stack is determined:
     * specifically, labels[0] is the top of the stack.
     *
     * */
    fun addWasmInstructions(labels: LabelStack, instrs: List<WasmInstruction>, succ: PC): PC {
        var pc = succ
        /* Reversing this is important. We start with the last instruction in the function.
         So the PC counting starts at 0 but at the bottom of the function!
         This means that the "entry" instruction is the one that has the largest PC.
         */
        val reversedInstrList = instrs.reversed()
        for (instr in reversedInstrList) {
            pc = addWasmInstruction(labels, instr, pc)
        }
        return pc
    }

    fun dumpWasmCfg(): String {
        val sb = StringBuilder()
        graph.forEachEntry { (pc, instrPcMap)  ->
            sb.append("PC $pc ")
            sb.append("Instr: ${instrPcMap.first.toWasmCfgString()} ")
            sb.append("Succs: ${instrPcMap.second} \n")
        }
        return sb.toString()
    }

    companion object {
        /**
         * Main function that generates WasmCFG from each function in a wasm program.
         * This produces a mapping from the function IDs to their CFGs.
         * The CFG is pruned to remove unreachable nodes.
         * */
        fun wasmAstToWasmCfg(wasmProgram: WasmProgram): Map<WasmName, WasmCFG> {
            val funcToWasmCfg = mutableMapOf<WasmName, WasmCFG>()
            for (func in wasmProgram.definedFuncs) {
                val cfg = WasmCFG(
                    graph = mutableMapOf(),
                    entry = ENTRYPOINT,
                    dataSegments = wasmProgram.fields.filterIsInstance<WasmData>()
                )
                /* We start at the bottom of a function. First we add a `Return` instruction
                   just to have something. The PC is 1 for this Return instruction.
                */
                val retPc = cfg.addNode(WasmInstruction.Control.Return(), listOf())
                // Then we add all the rest of the instructions from the bottom to the top.
                val instrBeforeEntry: PC = cfg.addWasmInstructions(listOf(), func.body, retPc)
                // Add explicit zero assignment to locals
                val zeroLocals = func.locals.flatMapIndexed() { i, t ->
                    val local = func.typeuse.params.size + i
                    listOf(
                        t.initializer,
                        WasmInstruction.Variable.LocalSet(WASMLocalIdx(local), )
                    )
                }
                val instrBeforeZeroLocals = cfg.addWasmInstructions(listOf(), zeroLocals, instrBeforeEntry)
                // ReachVarEquation.kt wants the start block to have ID 0, so adding a NOP with PC 0.
                cfg.graph[ENTRYPOINT] = Pair(WasmInstruction.Control.Nop(), listOf(instrBeforeZeroLocals))
                funcToWasmCfg[func.name] = cfg.pruneUnreachables(ENTRYPOINT).first
            }
            return funcToWasmCfg
        }
    }
}
