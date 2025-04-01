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

package wasm.impCfg

import algorithms.getReachable
import datastructures.stdcollections.*
import utils.*
import wasm.cfg.PC
import wasm.ir.*
import java.util.stream.Stream

class WasmIcfgMissingPC(msg: String): Exception(msg)
class FactsNullException(msg: String): Exception(msg)


data class Summary(val entryFact: ArgStack, val block: WasmBlock, val exitFact: ArgStack)

/**
 * A WasmImpCfgProgram has
 * @property entryPt an entry point,
 * @property nodes a mapping from PCs to the wasm blocks
 * @property facts a mapping from PCs to stack facts at that PC
 * @property dataSegments the list of data segments in the source wasm module
 * @property funcId the ID of the function for this cfg
 *
 * We need both facts and the code (nodes) because we do the analysis
 * of the stack and the translation of cfg to tac simultaneously.
* */
class WasmImpCfgProgram(
    val entryPt: PC,
    private val nodes: MutableMap<PC, WasmBlock>,
    private val facts: MutableMap<PC, StackFact>?,
    val dataSegments: List<WasmData>,
    val funcId: WasmName
) {

    fun parallelImpCfgCmdStream(): Stream<Pair<PC,WasmImpCfgCmd>> =
        nodes.entries.stream().flatMap { (pc, block) ->
            Stream.concat(
                block.straights.mapIndexed { i, instr ->
                    Pair(pc + i, instr)
                }.stream(),
                Stream.of(
                    Pair(pc + block.straights.size, block.ctrl)
                )
            )
        }

    fun copy(): WasmImpCfgProgram {
        return WasmImpCfgProgram(
            entryPt,
            nodes.toMutableMap(),
            facts?.toMutableMap(),
            dataSegments,
            funcId
        )
    }

    fun getNodes(): MutableMap<PC, WasmBlock> {
        return nodes
    }

    fun getFacts(): MutableMap<PC, StackFact>? {
        return facts
    }

    fun updateNode(pc: PC, block: WasmBlock) {
       nodes[pc] = block
    }

    /**
     * Get the node and fact (both at entry and exit) of the node, for a given PC
     * */
    fun getInfo(pc: PC): Summary {
        if (pc in this.nodes.keys && pc in (this.facts?.keys ?: throw FactsNullException("facts cannot be null"))) {
            val block = this.nodes[pc]!!
            val fact = this.facts[pc]!!
            return Summary(fact.entry, block, fact.exit)
        } else {
            throw WasmIcfgMissingPC("The pc $pc is not found in the nodes and facts maps.")
        }
    }

    /**
     * Update the node and fact for a given PC
    * */
    fun setInfo(pc: PC, info: Summary) {
        val fact = StackFact(info.entryFact, info.exitFact)
        nodes[pc] = info.block
        facts?.set(pc, fact)
    }

    /**
     * Remove a given PC from both nodes and facts.
     * */
    fun remove(pc: PC) {
        assert(pc != this.entryPt)
        nodes.remove(pc)
        facts?.remove(pc)
    }

    fun succs(pc: PC): List<PC> {
        return this.nodes[pc]?.succs() ?: throw WasmIcfgMissingPC("The pc $pc is not found in the nodes maps.")
    }

    fun preds(pc: PC): List<PC> {
        return nodes.keysMatching { _, wb ->  pc in wb.succs()}
    }

    /**
     * Returns a set of PCs that are reachable from the PC `from` in the given `cfg`.
     * */
    private fun reachableFromImp(from: PC): Set<PC> {
        return getReachable(listOf(from), this::succs)
    }

    /**
     * After inlining some nodes become unreachable again at the Wasm ImpCfg level.
     * TODO: CERT-6018
     * */
    fun pruneUnreachableImp(from: PC): Pair<WasmImpCfgProgram, List<PC>> {
        val reachables = reachableFromImp(from)
        val finalNodes = mutableMapOf<PC, WasmBlock>()
        val prunedPCs = mutableListOf<PC>()
        for((pc, node) in nodes.entries) {
            if (pc in reachables) {
                finalNodes[pc] = node
            } else {
                prunedPCs.add(pc)
            }
        }
        return WasmImpCfgProgram(entryPt, finalNodes, null, dataSegments, funcId) to prunedPCs
    }

    fun getAllTmpIdxs(): Set<PC> {
        return nodes.values.flatMapToSet { it.getTmpIdxs() }
    }

    fun getAllLocalIdxs(funcId: WasmName, typCtx: Map<WasmName, WasmProgram.WasmFuncDesc>): List<Int> {
        val funcDesc = typCtx[funcId]
        check(funcDesc is WasmProgram.WasmFuncDesc.LocalFn) {
            "Getting locals from an imported function"
        }
        val params = funcDesc.fnType.params.size
        val locals = funcDesc.locals.size
        return (0..<params + locals).toList()
    }

    /**
     * Returns the largest PC in the program
     * */
    fun getLargestBlockId() = nodes.maxOf { it.key }

    /**
     * Returns the largest tmp variable id in the program
     * */
    fun getLargestTmpId() = getAllTmpIdxs().maxOrNull() ?: 0

    fun dumpWasmImpCfg(): String {
        val sb = StringBuilder()
        nodes.forEachEntry { (pc, wblock)  ->
            sb.append("Block $pc \n")
            wblock.straights.forEach { cmd -> sb.append("\t $cmd \n") }
            sb.append("\t ${wblock.ctrl} \n")
        }
        return sb.toString()
    }

    /**
     * We use this to split the wasm impcfg at call (call_indirect)
     * sites to make each call site its own singleton block.
     * Example:
     * ```
     * Block 10
     *          tmp_pc_12 := tmp_pc_14 i64.or 4
     *          local_0 := tmp_pc_12
     *          tmp_pc_9 = wasmicfg.call 5 (1,tmp_pc_12,tmp_pc_18)
     *          tmp_pc_7 := local_0
     *          wasmicfg.ret tmp_pc_7
     * ```
     * will become
     *```
     *Block 10
     *          tmp_pc_12 := tmp_pc_14 i64.or 4
     *          local_0 := tmp_pc_12
     *          wasmicfg.jmp 1001
     *Block 1001
     *          tmp_pc_9 = wasmicfg.call 5 (1,tmp_pc_12,tmp_pc_18)
     *          wasmicfg.jmp 1002
     *Block 1002
     *          tmp_pc_7 := local_0
     *          wasmicfg.ret tmp_pc_7
     *```
     * */
    inline fun<reified T: StraightLine> mksplitWtac(): WasmImpCfgProgram {
        var largestBlockId = getLargestBlockId()
        val entry = entryPt
        val newNodes = mutableMapOf<PC, WasmBlock>()
        for (node in getNodes()) {
            val (nodes, next) = splitNodeAtStInstr<T>(node.key, node.value, largestBlockId)
            for((pc, wb) in nodes) {
                newNodes[pc] = wb
            }
            largestBlockId = next
        }
        // we don't need the facts anymore. They were only for translation from stack to register.
        val splitProg = WasmImpCfgProgram(entry, newNodes, null, dataSegments, funcId)
        return splitProg
    }

    /**
     * Given a PC and a block, this splits the block at call and call_indirect instructions.
     * It produces smaller blocks. See [mksplitWtac] for more details.
     * This returns the split ImpCfg and additionally the next available PC for subsequent splits.
     * */
    inline fun <reified T : StraightLine> splitNodeAtStInstr(
        pc: PC,
        block: WasmBlock,
        largestBlockId: Int
    ): Pair<MutableMap<PC, WasmBlock>, Int> {
        var currBid = pc
        var nextFreeBid = largestBlockId + 1
        val newNodes = mutableMapOf<PC, WasmBlock>()
        var nonCalls = mutableListOf<StraightLine>()
        for (st in block.straights) {
            if (st !is T) {
                nonCalls.add(st)
            } else {
                newNodes[currBid] = WasmBlock(nonCalls, Control.Jump(nextFreeBid, st.addr), block.funcId)
                val callBlock = WasmBlock(listOf(st), Control.Jump(nextFreeBid + 1, st.addr), block.funcId)
                newNodes[nextFreeBid] = callBlock
                currBid = nextFreeBid + 1
                nextFreeBid += 2
                nonCalls = mutableListOf()
            }
        }
        val lastBlock = WasmBlock(nonCalls, block.ctrl, block.funcId)
        newNodes[currBid] = lastBlock
        return Pair(newNodes, nextFreeBid)
    }

    /**
     * This function adds a list of [StraightLine] input the graph in between the PC src and dst
     * and re-routes the graph's edges such that we have src -> straights -> dst.
     */
    fun insertStraightsAlongEdge(src: PC, dst: PC, straights: List<StraightLine>): PC {
        check(nodes.containsKey(src)) {"The source PC doesn't exist in this graph $src"}
        check(nodes.containsKey(dst)) {"The destination PC doesn't exist in this graph $dst"}
        val newBid = getLargestBlockId() + 1
        fun PC.replaceIfDst() = this.takeIf { it != dst } ?: newBid
        // instruct the src block to jump to the new block id.
        val newCtrl = when(val c = nodes[src]!!.ctrl){
            is Control.BrTable -> c.copy(dests = c.dests.map { it.replaceIfDst() })
            is Control.Brif -> c.copy(ifpc = c.ifpc.replaceIfDst(), elpc = c.elpc.replaceIfDst())
            is Control.Jump -> c.copy(pc = c.pc.replaceIfDst())
            is Control.CertoraUnreachable,
            is Control.Ret,
            is Control.Unreach -> throw IllegalStateException("Impossible - there is no edge from src $src to dest $dst in the graph")
        }
        nodes[src] = nodes[src]!!.copy(ctrl = newCtrl)
        // create a new block with jump destination dst.
        val newBlock = WasmBlock(straights, Control.Jump(dst), funcId)
        // add the new block, i.e. it performs nodes[newBid] = newBlock
        setInfo(newBid, getInfo(src).copy(block = newBlock))
        return newBid
    }

    /**
     * For debugging purposes, prints the CFG to dot.
     *
     * The output string can be visualized using dot/graphviz.
     * There are online viewers as well, for instance here:
     * https://dreampuf.github.io/GraphvizOnline/
     */
    fun toDot(): String{
        val nodesStr = nodes.map { "${it.key} [label=\"${it.value.straights.joinToString("\n")}\"];"  }
        val edges = nodes.flatMap { src -> succs(src.key).map { src.key to it } }.map { "${it.first} -> ${it.second};" }
        return "digraph G{${nodesStr.joinToString("\n")} ${edges.joinToString("\n")}}"
    }
}
