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

import datastructures.stdcollections.*
import utils.*
import wasm.cfg.PC
import wasm.cfg.WasmCFG
import wasm.impCfg.ArgUtils.mkTmpRegister
import wasm.impCfg.BlockMaker.mkBlockFromCall
import wasm.impCfg.BlockMaker.mkBlockFromUnreach
import wasm.impCfg.TransferFunction.transferControl
import wasm.impCfg.TransferFunction.transferMemory
import wasm.impCfg.TransferFunction.transferNumeric
import wasm.impCfg.TransferFunction.transferPar
import wasm.impCfg.TransferFunction.transferVar
import wasm.impCfg.WasmImpInstr.reconstructStraight
import wasm.ir.*

class TooManySuccessorsError(msg:String): Exception(msg)
class TransferToBlockError(msg: String): Exception(msg)
class TransferFailed(message: String? = null): Exception(message)
class WasmCfgToWasmImpCfgError(message: String): Exception(message)
class GlobalVariableUnsupported(message: String): Exception(message)

object WasmCfgToWasmImpCfg {
    /**
     * If [succs] is a singleton, returns the element else throws exception.
     * */
    fun singleSuccessor(succs: List<PC>): PC =
        succs.singleOrNull() ?: throw TooManySuccessorsError("Found $succs successors, expected 1.")

    /**
     * The main transfer function that translates Cfg instructions to
     * `WasmBlock`s made from WasmImpCfgCmd instructions.
     * */
    fun transfer(
        funcId: WasmName,
        prog: WasmProgram,
        elemTable: WasmElem?,
        cfg: WasmCFG,
        wasmProgram: WasmImpCfgProgram,
        pc: PC
    ): Summary {
        val entry = cfg.getEntry()
        val graph = cfg.getGraph()

        val (instr, succs) = graph[pc] ?: throw TransferToBlockError("$pc does not exist in the CFG")

        /* for every pc, whose instruction could flow to this pc,
        take their exit facts and `join` them.
        */
        val enFact =
            if (pc == entry) {
                // this is important, this is how we get started!
                // if this is the entry, nothing flows to this pc, so we just return []
                RefStack(listOf())
            } else if (instr is WasmInstruction.Control.Unreachable) {
                // if this is an unreachable instruction, then the stack won't be used.  The Rust compiler takes
                // advantage of this, producing code that jumps to a single `unreachable` with different stack depths.
                Bottom
            } else { // otherwise go through the preds and their exit facts to get the entry fact for this pc.
                val predStacks = cfg.preds(pc).associateWith {
                    wasmProgram.getFacts()?.get(it)?.exit ?: throw TransferFailed()
                }
                if (predStacks.values.mapNotNullToSet { it.size }.size > 1) {
                    throw TransferFailed(
                        "Inconsistent ref stack sizes in preds of $pc ($instr): ${predStacks.mapValues { it.value }} in function $funcId."
                    )
                }
                predStacks.values.fold(Bottom as ArgStack) { acc, it -> acc.joinRefs(it) }
            }
        val dummyBlock = WasmBlock(listOf(), Control.Unreach(), funcId)
        val (bl, exFact) =
            if (enFact !is RefStack) {
                check(enFact is Bottom)
                Pair(dummyBlock, Bottom)
            } else {
                when (instr) {
                    is WasmInstruction.Numeric ->
                        transferNumeric(funcId, pc, instr, singleSuccessor(succs), enFact.args)
                    is WasmInstruction.Memory ->
                        transferMemory(funcId, pc, instr, singleSuccessor(succs), enFact.args)
                    is WasmInstruction.Variable ->
                        transferVar(funcId, prog, pc, instr, singleSuccessor(succs), enFact.args)
                    is WasmInstruction.Parametric ->
                        transferPar(funcId, pc, instr, singleSuccessor(succs), enFact.args)
                    is WasmInstruction.Control ->
                        // we need to pass in the typing context on this one for computing the arguments and return.
                        transferControl(funcId, prog, elemTable, pc, instr, succs, enFact.args)
                }
            }
        return Summary(enFact, bl, exFact)
    }

    /**
     * Initialize a wasm tac program from a wasm cfg.
     * */
    fun initialize(cfg: WasmCFG, funcId: WasmName): WasmImpCfgProgram {
        val dummyBlock = WasmBlock(listOf(), Control.Unreach(), funcId)
        val entry = cfg.getEntry()
        val nodes =
            cfg.getGraph().keys.associateWithTo(mutableMapOf()) { dummyBlock } // we just say that all blocks are unreachable.
        // right now we don't know anything about this PC.
        val facts = cfg.getGraph().keys.associateWithTo(mutableMapOf()) {
            StackFact(
                Bottom,
                Bottom
            )
        }
        return WasmImpCfgProgram(entry, nodes, facts, cfg.dataSegments, funcId)
    }

    /**
     * We compute the facts at each program point, both before and after that instruction.
     * */
    private tailrec fun fixPoint(
        funcId: WasmName,
        prog: WasmProgram,
        elemTable: WasmElem?,
        cfg: WasmCFG,
        wasmProgram: WasmImpCfgProgram,
        worklist: List<PC>
    ) {
        if (worklist.isNotEmpty()) {
            val headPC = worklist.first()
            val rest = worklist.takeLast(worklist.size - 1).toList()
            val summaryBefore = wasmProgram.getInfo(headPC)
            val summaryAfter = transfer(funcId, prog, elemTable, cfg, wasmProgram, headPC)
            if (summaryBefore == summaryAfter) {
                fixPoint(funcId, prog, elemTable, cfg, wasmProgram, rest)
            } else {
                wasmProgram.setInfo(headPC, summaryAfter)
                fixPoint(
                    funcId,
                    prog,
                    elemTable,
                    cfg,
                    wasmProgram,
                    cfg.succs(headPC)?.plus(rest)
                        ?: throw WasmCfgToWasmImpCfgError("fixPoint: succs of $headPC is null.")
                )
            }
        }
    }

    /**
     * We can merge [pc] and a block of the ImpCfg program [wtac] if [pc] is the only predecessor of the control
     * instruction [ctrlInstr] of the block and the control instruction [ctrlInstr] itself is a [Control.Jump].
     * */
    private fun canMergeSucc(wtac: WasmImpCfgProgram, pc: PC, ctrlInstr: Control): Boolean {
        return when (ctrlInstr) {
            is Control.Jump -> {
                (wtac.preds(ctrlInstr.pc).singleOrNull() == pc)
            }

            else -> {
                false
            }
        }
    }

    /**
     * Find pcs in the WasmImpCfgProgram that can be merged to make a
     * bigger "node" with more straight line commands.
     * See [canMergeSucc] for more information.
     * This returns a tuple of a pc and a block that can be merged.
     * */
    private fun findMergeable(wtac: WasmImpCfgProgram): Pair<PC, WasmBlock>? {
        return wtac.getNodes().entries.find { (pc, wb) -> canMergeSucc(wtac, pc, wb.ctrl) }?.toPair()
    }

    class ImpossibleMergeError : Exception()

    /**
     * Merging blocks to get rid of ones that have single instructions only.
     * We merge two blocks if the control instruction for one points to the start of the other.
     * */
    tailrec fun mergeBlocks(wtac: WasmImpCfgProgram): WasmImpCfgProgram {
        val mergeable = findMergeable(wtac)
        if (mergeable == null) {
            return wtac
        } else {
            val (pcA, wbA) = mergeable
            if (!wtac.getFacts()?.keys?.contains(pcA)!!) {
                throw ImpossibleMergeError()
            }
            val entryA = wtac.getFacts()!![pcA]!!.entry
            when (wbA.ctrl) {
                is Control.Jump -> {
                    val (_, wbB, exB) = wtac.getInfo(wbA.ctrl.pc)
                    val newStraights = wbA.straights.toMutableList()
                    newStraights.addAll(wbB.straights)
                    val newWasmBlock = WasmBlock(newStraights, wbB.ctrl, wtac.funcId)
                    wtac.setInfo(pcA, Summary(entryA, newWasmBlock, exB))
                    wtac.remove(wbA.ctrl.pc)
                    return mergeBlocks(wtac)
                }

                else -> {
                    throw ImpossibleMergeError()
                }
            }
        }
    }

    /**
     * In WasmImpCfg, havoc is an expression. This is a
     * pass that converts them to straight line code, using `StraightLine.Havoc`.
     *
     *e.g.,
     *```
     *        tmp_pc_51 := wasm.havoc
     *               =>
     *        havoc(havoc_var_tmp_pc_51)
     *        tmp_pc_51 = havoc_var_tmp_pc_51
     *```
     * */
    fun havocify(wtac: WasmImpCfgProgram): WasmImpCfgProgram {
        var freshVarForHavocify = 0
        fun allocFresh() = freshVarForHavocify++
        for ((pc, wb) in wtac.getNodes()) {
            val newStraights = mutableSetOf<StraightLine>()
            for (straight in wb.straights) {
                if (straight.hasHavoc()) {
                    val getHavocCands = straight.getVarsForHavoc(::allocFresh)
                    for (v in getHavocCands) {
                        if (v is ArgRegister) {
                            newStraights.add(StraightLine.Havoc(v.type, v.register))
                        }
                    }
                    newStraights.add(reconstructStraight(straight, getHavocCands))
                } else {
                    newStraights.add(straight)
                }
            }
            val newWasmBlock = WasmBlock(newStraights.toList(), wb.ctrl, wtac.funcId)
            wtac.setInfo(pc, Summary(wtac.getFacts()?.get(pc)!!.entry, newWasmBlock, wtac.getFacts()!![pc]!!.exit))
        }
        return wtac
    }

    /**
     * This is a pass that translates BrTable to BrIf
     * because BrTable cannot be supported in TAC.
     * Note that this will be called BEFORE merging blocks so no
     * block should have more than 1 straight instruction.
     * Concretely, this pass does the following:
     *
     * ```
     * Block 64:
     *      tmp_pc_64 := wasmicfg.i32.load tmp_pc_65
     *      wasmicfg.jmp -> 63
     *
     * Block 63: <tmp_pc_64>
     *      wasmicfg.switch (tmp_pc_64) -> [23, 31, 39, 15]
     *
     * Block 23:
     *      ...
     * ...
     *```
     *          =>
     * ```
     * Block 64
     *      tmp_pc_64 := wasmicfg.i32.load tmp_pc_65
     *      wasmicfg.jmp -> 63
     *
     * Block 63: <tmp_pc_64>
     *      wasmicfg.jmp 6300001
     *
     * Block 6300001: <tmp_pc_64>
     *      tmp_pc_64001 = tmp_pc_64 == 0
     *      wasmicfg.if (tmp_pc_64001) -> [23, 6300002]
     *
     * Block 6300002: <tmp_pc_64>
     *      tmp_pc_64002 = tmp_pc_64 == 1
     *      wasmicfg.if (tmp_pc_64002) -> [31, 6300003]
     *
     * Block 6300003: <tmp_pc_64>
     *      tmp_pc_64003 = tmp_pc_64 == 2
     *      wasmicfg.if (tmp_pc_64003) -> [39, 6300004]
     *
     * Block 6300004: <tmp_pc_64>
     *      wasmicfg.jmp 15
     *
     * Block 23:
     *      ...
     * ...
     * ```
     * Note that the entry facts at each of these new blocks should be the entry fact of
     * the original block.
     * */
    fun brTabToBrIf(wtac: WasmImpCfgProgram): WasmImpCfgProgram {
        var nextBid = wtac.getLargestBlockId()
        var nextTmp = wtac.getLargestTmpId()
        val entry = wtac.entryPt
        val newNodes = mutableMapOf<PC, WasmBlock>()
        val newFacts = mutableMapOf<PC, StackFact>()
        for (node in wtac.getNodes()) {
            val oldFact = wtac.getInfo(node.key)
            if (node.value.ctrl is Control.BrTable) {
                val addr = (node.value.ctrl as Control.BrTable).addr
                nextBid += 1
                val newJmp = Control.Jump(nextBid, addr)
                val jb = WasmBlock(node.value.straights, newJmp, wtac.funcId)
                newNodes[node.key] = jb
                newFacts[node.key] = StackFact(oldFact.entryFact, oldFact.exitFact)
                for ((succIndex, succ) in (node.value.ctrl as Control.BrTable).dests.withIndex()) {
                    val cond = WasmIcfgBinaryExpr(
                        BinaryComparisonOp.I32EQ,
                        (node.value.ctrl as Control.BrTable).arg,
                        ArgConst32(I32Value(succIndex.toBigInteger()))
                    )
                    nextTmp += 1
                    val newVar = mkTmpRegister(WasmPrimitiveType.BOOL, nextTmp, "")
                    val condStmt = StraightLine.SetTmp(newVar.first, cond, addr)
                    val brifStmt = Control.Brif(newVar.second, succ, nextBid + 1, addr)
                    val nb = WasmBlock(listOf(condStmt), brifStmt, wtac.funcId)
                    newNodes[nextBid] = nb
                    newFacts[nextBid] = StackFact(oldFact.entryFact, oldFact.exitFact)
                    nextTmp += 1
                    nextBid += 1
                }
                val fb = WasmBlock(listOf(), Control.Jump((node.value.ctrl as Control.BrTable).fallBack, addr), wtac.funcId)
                newNodes[nextBid] = fb
                newFacts[nextBid] = StackFact(oldFact.entryFact, oldFact.exitFact)
                nextBid += 1
            } else {
                newNodes[node.key] = node.value
                newFacts[node.key] =
                    wtac.getFacts()!![node.key]!! // must have a fact for each pc when `brTabToBrIf` is called.
            }
        }
        return WasmImpCfgProgram(entry, newNodes, newFacts, wtac.dataSegments, wtac.funcId)
    }

    /**
     * NOTE: we assume that the function table is statically known.
     * We have not seen any programs so far where it is dynamic;
     * so for now, we don't worry about that scenario.
     * Given a call indirect like
     *
     * ```
     * Block 999
     *   tmp_pc_23 := local_3
     * 	 tmp_pc_21 := tmp_pc_23 i32.add 16
     *   tmp_value := expr_for_elem_idx
     *   tmp_pc_19 := wasmicfg.call_indirect(tmp_value)[4, 55, 2] (2,tmp_pc_21)
     *   tmp_pc_55 := local_5
     * ```
     *
     * We will first split the call_indirect sites to their own blocks:
     * ```
     * Block 999
     *   tmp_pc_23 := local_3
     * 	 tmp_pc_21 := tmp_pc_23 i32.add 16
     *   tmp_value := expr_for_elem_idx
     *   wasmicfg.jmp 100001
     *
     * Block 100001
     *   tmp_pc_19 := wasmicfg.call_indirect(tmp_value)[4, 55, 2] (2,tmp_pc_21)
     *   wasmicfg.jmp 100002
     *
     * Block 100002
     *   tmp_pc_55 := local_5
     *   ...
     * ```
     *
     * and then for the block:
     *
     *```
     * Block 100001
     *   tmp_pc_19 := wasmicfg.call_indirect(tmp_value)[4, 55, 2] (2,tmp_pc_21)
     *   wasmicfg.jmp 100002
     *```
     *
     * we want to generate the following,
     * assuming an elem table like this: (elem (;0;) (i32.const 0) func $foo $bar $baz):
     *
     * ```
     * Block 100001
     *  tmp_c1 := tmp_value eq 0
     *  wasmicfg.if (tmp_c1) -> [1000, 1001]
     *
     * Block 1000
     *  tmp_pc_19 := wasmicfg.call 4 (2,tmp_pc_21)
     *  wasmicfg.jmp 100002
     *
     * Block 1001
     *   tmp_c2 := tmp_value eq 1
     *   wasmicfg.if (tmp_c2) -> [1002, 1003]
     *
     * Block 1002
     *   tmp_pc_19 := wasmicfg.call 55 (2,tmp_pc_21)
     *   wasmicfg.jmp 100002
     *
     * Block 1003
     *   tmp_c3 := tmp_value eq 2
     *   wasmicfg.if (tmp_c3) -> [1004, 1005]
     *
     *
     * Block 1004
     *   tmp_pc_19 := wasmicfg.call 2 (2,tmp_pc_21)
     *   wasmicfg.jmp 100002
     *
     * Block 1005
     *   unreachable
     *
     * ```
     *
     * */
     fun callIndirectToCall(
        wtac: WasmImpCfgProgram,
        types: Map<WasmName, WasmProgram.WasmFuncDesc>,
        typeTable: Map<WasmName, WasmType>
    ): WasmImpCfgProgram {
        val splitWtac = wtac.mksplitWtac<StraightLine.CallIndirect>()
        var nextBid = splitWtac.getLargestBlockId() + 1
        var nextTmp = splitWtac.getLargestTmpId() + 1
        val entry = splitWtac.entryPt
        val newNodes = mutableMapOf<PC, WasmBlock>()
        for (node in splitWtac.getNodes()) {
            val callIndirect = node.value.straights.singleOrNull() as? StraightLine.CallIndirect
            if (callIndirect != null) {
                val succPCOfCallIndirect = node.value.ctrl.succs().single() // [mksplitWtac] guarantees that this is always a Jump which has a single succ.
                var currPc = node.key
                var elBr = 0
                for ((idx, funcId) in callIndirect.elemTable.funcNames.withIndex()) {
                    val typeCorrect = types[WasmName(funcId)]?.fnType == typeTable[callIndirect.typeUse.name]

                    val cond = WasmIcfgBinaryExpr(
                        BinaryComparisonOp.I32EQ,
                        callIndirect.elemIndex,
                        ArgConst32(I32Value(idx.toBigInteger()))
                    )
                    val condCheckVar = mkTmpRegister(WasmPrimitiveType.BOOL, nextTmp, "")
                    val condStmt = StraightLine.SetTmp(condCheckVar.first, cond, callIndirect.addr)
                    val ifBr = nextBid
                    elBr = nextBid + 1
                    val checkAndJump = Control.Brif(condCheckVar.second, ifBr, elBr, callIndirect.addr)
                    val nb = WasmBlock(listOf(condStmt), checkAndJump, wtac.funcId)
                    newNodes[currPc] = nb
                    val callBlock = if (typeCorrect) {
                        mkBlockFromCall(
                            callIndirect.maybeRet,
                            WasmName(funcId),
                            callIndirect.args,
                            succPCOfCallIndirect,
                            wtac.funcId,
                            callIndirect.addr
                        )
                    } else {
                        mkBlockFromUnreach(wtac.funcId, callIndirect.addr)
                    }
                    newNodes[ifBr] = callBlock
                    currPc = elBr
                    nextBid += 2
                    nextTmp += 1
                }
                newNodes[elBr] = mkBlockFromUnreach(wtac.funcId, callIndirect.addr)
            } else {
                newNodes[node.key] = node.value
            }
        }
        return WasmImpCfgProgram(entry, newNodes, null, wtac.dataSegments, wtac.funcId)
    }

    /**
     * The wasm for soroban typically has global variables
     * that represent things like the starting address of the stack
     * (initial value of the stack pointer) among other things.
     * Example:
     * ```
     *   (global $__stack_pointer (mut i32) (i32.const 1048576))
     *   (global (;1;) i32 (i32.const 1048592))
     *   (global (;2;) i32 (i32.const 1048592))
     * ```
     * The entry function's arguments also need to be assigned nondet values of the correct type.
     * We want to add these as assignments to the
     * top of the starting block inorder to not havoc unnecessarily.
     * */
    fun initializeGlobalsAndArgs(ruleFuncId: WasmName, wtac: WasmImpCfgProgram, wasmAST: WasmProgram): WasmImpCfgProgram {
        val straightsForGlobalInits = mutableListOf<StraightLine>()
        for (global in wasmAST.allGlobals.values) {
            val id = global.name
            val value = if (global.init as? WasmInstruction.Numeric.I32Const != null) {
                ArgConst32(global.init.num)
            } else if (global.init as? WasmInstruction.Numeric.I64Const != null) {
                ArgConst64(global.init.num)
            } else {
                throw GlobalVariableUnsupported("$global is initialized as ${global.init} which is not a constant.")
            }
            val straightLine = StraightLine.SetGlobal(id.toString(), value)
            straightsForGlobalInits.add(straightLine)
        }

        val ruleFuncType = wasmAST.allFuncTypes[ruleFuncId]
            ?: error("Could not find type for rule function $ruleFuncId")
        val tmpIdBase = wtac.getLargestTmpId() + 1
        ruleFuncType.fnType.params.forEachIndexed { i, paramType ->
            val (t, ta) = mkTmpRegister(paramType, tmpIdBase + i, "")
            straightsForGlobalInits.add(StraightLine.Havoc(paramType, t))
            straightsForGlobalInits.add(StraightLine.SetLocal(StraightLine.Local(WASMLocalIdx(i), 0), ta))
        }

        val entryBlock = wtac.getNodes()[wtac.entryPt]!!
        val newBlock = WasmBlock(straightsForGlobalInits + entryBlock.straights, entryBlock.ctrl, wtac.funcId)
        wtac.updateNode(wtac.entryPt, newBlock)
        return wtac
    }

    /**
     * Main function that generates WasmTAC from WasmCFG. It calls the fixpoint function.
     * */
    fun wasmCfgToWasmImpCfg(
        funcId: WasmName,
        prog: WasmProgram,
        elemTable: WasmElem?,
        wasmCfg: WasmCFG
    ): WasmImpCfgProgram {
        val wtac = initialize(wasmCfg, funcId)
        fixPoint(funcId, prog, elemTable, wasmCfg, wtac, wasmCfg.getPCs())
        return wtac
    }
}
