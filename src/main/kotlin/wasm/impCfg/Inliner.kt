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

import config.Config
import datastructures.stdcollections.*
import sbf.support.runCommand
import log.*
import utils.*
import wasm.cfg.PC
import wasm.impCfg.StraightLine.*
import wasm.impCfg.WasmImpInstr.transformControl
import wasm.impCfg.WasmImpInstr.transformVars
import wasm.ir.WASMLocalIdx
import wasm.ir.WasmName

private val wasmLogger = Logger(LoggerTypes.WASM)

class CalleeReturnsValueUnexpectedly : Exception()

class WasmInliner {
    fun inline(
        wasmFunctions: Map<WasmName, WasmImpCfgProgram>,
        root: WasmName,
        shouldInline: (WasmName) -> Boolean,
    ): WasmImpCfgProgram {
        return inlineWasmCalls(wasmFunctions, wasmFunctions[root]!!, shouldInline,  listOf(root)).second
    }

    private fun inlineCandidates(
        wtac: WasmImpCfgProgram,
        allDefinedFuncIds: Set<WasmName>,
        shouldInline: (WasmName) -> Boolean
    ): List<Pair<PC, WasmName>> {
        return wtac.getNodes().mapNotNull {(pc, block) ->
            pc `to?` (block.straights.singleOrNull() as? Call)?.id
        }.filter { (_, id) ->
            id in allDefinedFuncIds && shouldInline(id)
        }
    }

    /**
     * Inlines the bodies of functions called (transitively) in the body of [funcToInline].
     * @param wasmFunctions un-inlined code
     * @param funcToInline the CFG in which to inline calls
     * @param shouldInline only inline calls for which [shouldInline] returns [True]
     * @param stack the call stack in which this inlining is taking place
     * @return (n, cfg) where n is the number of call sites inlined and cfg is the result of inlining the reachable calls
     *         in [funcToInline]
     * NOTE: only inlines function calls where the function is defined in the file and returns at most 1 value
     */
    private fun inlineWasmCalls(
        wasmFunctions: Map<WasmName, WasmImpCfgProgram>,
        funcToInline: WasmImpCfgProgram,
        shouldInline: (WasmName) -> Boolean,
        stack: List<WasmName>,
    ): Pair<Int, WasmImpCfgProgram> {
        var ret = funcToInline.mksplitWtac<Call>()
        val candidates = inlineCandidates(ret, wasmFunctions.keys, shouldInline)
        var idx = 0
        for ((pc, callee) in candidates) {
            if (numUnfoldings(callee, stack) > Config.RecursionEntryLimit.get()) {
                val newBlock = BlockMaker.mkBlockFromInternalUnreach(
                    funcId = ret.funcId,
                    addr = null,
                    isAssert = Config.RecursionErrorAsAssertInAllCases.get(),
                    message = "recursion limit"
                )
                ret.updateNode(pc, newBlock)
            } else {
                // First inline all calls recursively in callee
                val (newIdx, calleeInlined) = inlineWasmCalls(
                    wasmFunctions,
                    wasmFunctions[callee]!!,
                    shouldInline,
                    listOf(callee) + stack
                )
                idx = newIdx + 1
                ret = inlineIntoCaller(ret, pc, calleeInlined, idx)
            }
        }
        return idx to ret
    }

    private fun numUnfoldings(callee: WasmName, stack: List<WasmName>): Int = stack.count { callee == it }

    /**
     * Core functionality for function inlining. Given a split [WasmImpCfgProgram] program (using `mksplitWtac`)
     * a caller and a callee's PC in the caller,
     *
     * (1) first renames the callee,
     *
     * (2) then introduces the assignments to the parameters of the callee based on the arguments of the call command, and
     *
     * (3) then adds a jump to the entry of the callee.
     *
     * (4) Finally, calls [updateCalleeWithRetAndJmp] to make sure that the callee jumps back to the succ of the call command,
     * and also if there are any returns, they are assigned to the target on the caller side.
     *
     * @param caller the program into which we inline [callee]
     * @param callLoc the PC of the call
     * @param callee the callee program to inline at [callLoc]
     * @param calleeIdx the index (or identifier) of the callsite, used to produce unique names for callee's
     *        local variables
     */
    private fun inlineIntoCaller(
        caller: WasmImpCfgProgram,
        callLoc: PC,
        callee: WasmImpCfgProgram,
        calleeIdx: Int,
    ): WasmImpCfgProgram {
        val largestBlockId = caller.getLargestBlockId()

        val funcCallBlock = caller.getNodes()[callLoc]!!

        check(funcCallBlock.straights.size == 1)
        check(funcCallBlock.straights[0] is Call)

        val funcCallStmt = (funcCallBlock.straights[0] as Call)
        val renamedCallee = alphaRename(
            callee,
            largestBlockId,
            calleeIdx,
        )

        val assignParams = funcCallStmt.args.mapIndexed { i, nl ->
            SetLocal(Local(WASMLocalIdx(i), calleeIdx), nl, funcCallStmt.addr)
        }

        val jmpToCallee = Control.Jump(renamedCallee.entryPt, funcCallStmt.addr)
        val newBlock = WasmBlock(assignParams, jmpToCallee, caller.funcId)

        caller.updateNode(callLoc, newBlock)

        // Even if we run with -C debuginfo=2, we still want these annotation because it helps us understand what we inlined.
        annotateInlinedFuncStartEnd(renamedCallee, funcCallStmt, calleeIdx)
        updateCalleeWithRetAndJmp(renamedCallee, funcCallStmt, funcCallBlock.ctrl.succs()[0])

        for ((pc, calleeNode) in renamedCallee.getNodes()) {
            caller.updateNode(pc, calleeNode)
        }
        return caller
    }

    /**
     * Adds necessary return value assignments and jump instructions to the callee.
     * For example, if on the caller side we have:
     *```
     *Block 1001
     *  tmp_pc_9 = wasmicfg.call 5 (1,tmp_pc_12,tmp_pc_18)
     *  wasmicfg.jmp 1002
     *
     *Block 1002
     *```
     * and for the callee function `5`, say we have this
     * ```
     * Block 10002
     *          local_4 := tmp_pc_8
     *          tmp_pc_6 := local_1
     *          tmp_pc_4 := tmp_pc_6 i32.add 16
     *          global_0 := tmp_pc_4
     *          tmp_pc_2 := local_4
     *          wasmicfg.ret tmp_pc_2
     * ```
     * we will change it to the following:
     * ```
     * Block 10002
     *          local_4 := tmp_pc_8
     *          tmp_pc_6 := local_1
     *          tmp_pc_4 := tmp_pc_6 i32.add 16
     *          global_0 := tmp_pc_4
     *          tmp_pc_2 := local_4
     *          tmp_pc_9 = tmp_pc_2
     *          wasmicfg.jmp 1002
     * ```
     * If the callee does not return anything, then we will skip the `tmp_pc_9 = tmp_pc_2`
     * and just replace the ret with the `jmp`.
     * */
    private fun updateCalleeWithRetAndJmp(callee: WasmImpCfgProgram, callstmt: Call, jmpDest: PC) {
        if (callstmt.maybeRet != null) {
            for ((pc, block) in callee.getNodes().entries) {
                val straights = block.straights.toMutableList()
                if (block.ctrl is Control.Ret) {
                    if (block.ctrl.arg == null) {
                        throw CalleeReturnsValueUnexpectedly()
                    } else {
                        straights.add(SetTmp(callstmt.maybeRet, WasmIcfgExpArgument(block.ctrl.arg), callstmt.addr))
                        val newJmpStmt = Control.Jump(jmpDest, block.ctrl.addr)
                        val newBlock = WasmBlock(straights, newJmpStmt, callee.funcId)
                        callee.updateNode(pc, newBlock)
                    }
                }
            }
        } else {
            for ((pc, block) in callee.getNodes().entries) {
                if (block.ctrl is Control.Ret) {
                    val newJmpStmt = Control.Jump(jmpDest, block.ctrl.addr)
                    val newBlock = WasmBlock(block.straights, newJmpStmt, callee.funcId)
                    callee.updateNode(pc, newBlock)
                }
            }
        }
    }

    /**
     * Add annotations to the callee function for calltrace generation
     * This will be called **before** the callee is updated with a jump instruction
     * and optionally a return statement using [updateCalleeWithRetAndJmp].
     * This is important because we want to know where the function returns
     * in order to be able to mark the end of the function
     * So if we have the following code of the callee:
     * ```
     * Block 10002
     *    local_4 := tmp_pc_8
     *    tmp_pc_6 := local_1
     *    tmp_pc_4 := tmp_pc_6 i32.add 16
     *    global_0 := tmp_pc_4
     *    tmp_pc_2 := local_4
     *    wasmicfg.ret tmp_pc_2
     *
     * this will become:
     *
     *```
     * Block 10002
     *     InlinedFuncStartAnnotation(funcId=$_ZN11soroban_sdk6symbol6Symbol3new17h80e1a62fabe9d6b6E)
     *     local_4 := tmp_pc_8
     *     tmp_pc_6 := local_1
     *     tmp_pc_4 := tmp_pc_6 i32.add 16
     *     global_0 := tmp_pc_4
     *     tmp_pc_2 := local_4
     *     tmp_pc_9 = tmp_pc_2
     *     InlinedFuncEndAnnotation(funcId=$_ZN11soroban_sdk6symbol6Symbol3new17h80e1a62fabe9d6b6E)
     *     wasmicfg.ret tmp_pc_2
     *```
     *
     * */
    private fun annotateInlinedFuncStartEnd(callee: WasmImpCfgProgram, call: Call, idx: Int) {
        val demangledCalleeName = demangle(callee.funcId.toString())
        for ((pc, block) in callee.getNodes().entries) {
            var straights = block.straights
            // add the start annotation to the top of the callee, right before the first actual instruction
            if (pc==callee.entryPt) {
                straights = listOf(InlinedFuncStartAnnotation(
                    funcId = demangledCalleeName,
                    funcArgs = call.args,
                    callIdx = idx
                )).plus(straights)
                val newBlock = WasmBlock(straights, block.ctrl, callee.funcId)
                callee.updateNode(pc, newBlock)
            }
            // add the end annotation to the end of the callee, right before the return
            if (block.ctrl is Control.Ret) {
                straights = straights.plus(InlinedFuncEndAnnotation(funcId = demangledCalleeName, callIdx = idx))
                val newBlock = WasmBlock(straights, block.ctrl, callee.funcId)
                callee.updateNode(pc, newBlock)
            }
        }
    }

    companion object {
        // Adapted from solana code
        fun demangle(funcName: String): String {
            val (res, error, exitcode) = runCommand(listOf("rustfilt"), funcName)
            if (exitcode != 0) {
                wasmLogger.warn { "rustfilt $error" }
                return funcName
            }
            return res
        }
    }

    private fun tmpRenamer(callIdx: Int): (Tmp) -> Tmp =
        { t -> t.copy(callIdx = callIdx + t.callIdx) }

    private fun localRenamer(callIdx: Int): (Local) -> Local =
        { l -> l.copy(callIdx = callIdx + l.callIdx) }

    /**
     * Alpha rename the callee function's instructions and block names.
     *
     * @param renameTarget the program to rename
     * @param lastBlockId all blocks in [renameTarget] will be renamed to be larger than [lastBlockId]
     * @param callIdx all call indices appearing in [renameTarget] will be increased by [callIdx]
     *
     */
    private fun alphaRename(
        renameTarget: WasmImpCfgProgram,
        lastBlockId: PC,
        callIdx: Int,
    ): WasmImpCfgProgram {
        var lb = lastBlockId + 1
        val blockToNewBlockMap = mutableMapOf<PC, PC>()
        for (bid in renameTarget.getNodes().keys) {
            blockToNewBlockMap[bid] = lb
            lb += 1
        }
        val entry = blockToNewBlockMap[renameTarget.entryPt] ?: throw  AlphaRenamingControlCmdFailed("entry block must have been mapped to a new block id.")
        val newBlocks = renameTarget.getNodes().entries.associate { (bid, block) ->
            val newBid = blockToNewBlockMap[bid]!!
            val newStraights = block.straights.map { transformVars(it, tmpRenamer(callIdx), localRenamer(callIdx)) }
            val newCtrl = transformControl(block.ctrl, tmpRenamer(callIdx)) { blockToNewBlockMap[it]!! }
            newBid to WasmBlock(newStraights, newCtrl, renameTarget.funcId)
        }

        return WasmImpCfgProgram(entry, newBlocks.toMutableMap(), null, renameTarget.dataSegments, renameTarget.funcId)
    }
}
