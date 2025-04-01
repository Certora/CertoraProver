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
import wasm.ir.*
import java.io.Serializable

/**
 * A WasmBlock is a list of straight line instructions followed by a single control instruction.
 * */
data class WasmBlock(val straights: List<StraightLine>, val ctrl: Control, val funcId: WasmName) : Serializable {
    fun succs(): List<PC> {
        return this.ctrl.succs()
    }


    override fun toString(): String {
        val straightStrs = straights.joinToString("\n") { it.toString() }
        val ctrlStr = ctrl.toString()
        return "$straightStrs\n$ctrlStr"
    }

    /**
     * Returns a set of all the pcs (that are just ints) that appear in the
     * `Tmp` register variables in this wasm block.
     **/
    fun getTmpIdxs(): Set<Int> = straights.flatMapToSet { it.tempIdxs }
}

/* A bunch of helpers for making blocks from WasmImpCfg instructions.
* Most of them have a single succ, note the branching ones that are different.
* */
object BlockMaker {
    fun mkBlockFromSetTmp(t: Tmp, e: WasmIcfgExpr, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.SetTmp(t, e, addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromGetLocal(t: Tmp, i: WASMLocalIdx, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.GetLocal(t, StraightLine.Local(i, 0), addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromSetLocal(i: WASMLocalIdx, a: Arg, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.SetLocal(StraightLine.Local(i, 0), a, addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromGetGlobal(t: Tmp, i: String, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.GetGlobal(t, i, addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromSetGlobal(i: String, a: Arg, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.SetGlobal(i, a, addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromCall(ret: Tmp?, id: WasmName, args: List<Arg>, succ: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.Call(ret, id, args, addr)), Control.Jump(succ, addr), funcId)
    }

    fun mkBlockFromCallIndirect(ret: Tmp?, elemIdx: Arg, elemTable: WasmElem, args: List<Arg>, succ: PC, typeUse: WasmTypeUse, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(StraightLine.CallIndirect(ret, elemIdx, elemTable, args, typeUse, addr)), Control.Jump(succ, addr), funcId)
    }
    fun mkBlockFromJump(pc: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(), Control.Jump(pc, addr), funcId)
    }
    fun mkBlockFromInternalUnreach(funcId: WasmName, addr: WASMAddress?, isAssert: Boolean, message: String): WasmBlock {
        return WasmBlock(listOf(), Control.CertoraUnreachable(addr, isAssert, message), funcId)
    }
    fun mkBlockFromUnreach(funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(), Control.Unreach(addr), funcId)
    }

    // Used for br_if and if/else
    fun mkBlockFromConditionalBranch(a: Arg, ifpc: PC, elpc: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(), Control.Brif(a, ifpc, elpc, addr), funcId)
    }

    fun mkBlockFromBrTable(a: Arg, dests: List<PC>, fb: PC, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(), Control.BrTable(a, dests, fb, addr), funcId)
    }

    fun mkBlockFromRet(a: Arg?, funcId: WasmName, addr: WASMAddress?): WasmBlock {
        return WasmBlock(listOf(), Control.Ret(a, addr), funcId)
    }
}
