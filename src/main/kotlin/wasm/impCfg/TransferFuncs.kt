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
import wasm.cfg.PC
import wasm.impCfg.ArgUtils.mkTmpRegister
import wasm.impCfg.BlockMaker.mkBlockFromBrTable
import wasm.impCfg.BlockMaker.mkBlockFromCall
import wasm.impCfg.BlockMaker.mkBlockFromCallIndirect
import wasm.impCfg.BlockMaker.mkBlockFromConditionalBranch
import wasm.impCfg.BlockMaker.mkBlockFromGetGlobal
import wasm.impCfg.BlockMaker.mkBlockFromGetLocal
import wasm.impCfg.BlockMaker.mkBlockFromJump
import wasm.impCfg.BlockMaker.mkBlockFromRet
import wasm.impCfg.BlockMaker.mkBlockFromSetGlobal
import wasm.impCfg.BlockMaker.mkBlockFromSetLocal
import wasm.impCfg.BlockMaker.mkBlockFromSetTmp
import wasm.impCfg.BlockMaker.mkBlockFromUnreach
import wasm.impCfg.WasmCfgToWasmImpCfg.singleSuccessor
import wasm.ir.*
import wasm.tokens.WasmTokens.OFFSET

class UnsupportedWasmCFGNumericInstr(msg: String): Exception(msg)
class MultipleReturnsFoundException(msg: String): Exception(msg)
class TransferFuncNotPossible(msg: String): Exception(msg)

object TransferFunction {

    fun transferVar(
        funcId: WasmName,
        prog: WasmProgram,
        pc: PC,
        instr: WasmInstruction.Variable,
        succ: PC,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        when (instr) {
            is WasmInstruction.Variable.LocalGet -> {
                val localTypes = prog.allFuncTypes[funcId]?.paramsAndLocals ?: error("cannot find locals for $funcId")
                val (t, at) = mkTmpRegister(localTypes[instr.x.value], pc, "")
                val newRefs = listOf(at) + refs
                return mkBlockFromGetLocal(t, instr.x, succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Variable.GlobalGet -> {
                val type = prog.allGlobals[WasmName(instr.x)]?.type ?: error("cannot find global ${instr.x}")
                val (t, at) = mkTmpRegister(type, pc, "")
                val newRefs = listOf(at) + refs
                return mkBlockFromGetGlobal(t, instr.x, succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Variable.LocalSet -> {
                check(refs.isNotEmpty())
                val newRefs = refs.drop(1)
                return mkBlockFromSetLocal(instr.x, refs[0], succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Variable.GlobalSet -> {
                check(refs.isNotEmpty())
                val newRefs = refs.drop(1)
                return mkBlockFromSetGlobal(instr.x, refs[0], succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Variable.LocalTee -> {
                check(refs.isNotEmpty())
                return mkBlockFromSetLocal(instr.x, refs[0], succ, funcId, instr.address) to RefStack(refs)
            }
        }
    }

    fun transferPar(
        funcId: WasmName,
        pc: PC,
        instr: WasmInstruction.Parametric,
        succ: PC,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        when (instr) {
            is WasmInstruction.Parametric.Drop -> {
                check(refs.isNotEmpty())
                return mkBlockFromJump(succ, funcId, instr.address) to RefStack(refs.drop(1))
            }

            is WasmInstruction.Parametric.Select -> {
                check(refs.size >= 3)
                val cond = refs[0]
                val tr = refs[2]
                val fl = refs[1]
                check(tr.type == fl.type) { "Type mismatch in select instruction" }
                val (t, at) = mkTmpRegister(tr.type, pc, "")
                val newRefs = listOf(at).plus(refs.drop(3))
                return mkBlockFromSetTmp(t, WasmIcfgIteExpr(cond, tr, fl), succ, funcId, instr.address) to RefStack(newRefs)
            }
        }
    }

    fun transferMemory(
        funcId: WasmName,
        pc: PC,
        instr: WasmInstruction.Memory,
        succ: PC,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        when (instr) {
            is WasmInstruction.Memory.Size -> {
                val (t, at) = mkTmpRegister(WasmPrimitiveType.I32, pc, "")
                return mkBlockFromSetTmp(t, WasmIcfgExpArgument(Havoc(WasmPrimitiveType.I32)), succ, funcId, instr.address) to RefStack(listOf(at).plus(refs))
            }

            is WasmInstruction.Memory.Grow -> {
                val (t, at) = mkTmpRegister(WasmPrimitiveType.I32, pc, "")
                return mkBlockFromSetTmp(t, WasmIcfgExpArgument(Havoc(WasmPrimitiveType.I32)), succ, funcId, instr.address) to RefStack(listOf(at).plus(refs.drop(1)))
            }

            is WasmInstruction.Memory.Load -> {
                val (t, at) = mkTmpRegister(instr.op.type, pc, "")
                check(refs.isNotEmpty())
                val arg = refs[0]
                val (offsetReg, offsetArgReg) = mkTmpRegister(WasmPrimitiveType.I32, pc, OFFSET)
                val rhsAddr = WasmIcfgBinaryExpr(BinaryNumericOp.I32ADD, arg, ArgConst32(I32Value(instr.offset.value.toBigInteger())))
                val newRefs = listOf(at) + refs.drop(1)
                val instrs =
                    listOf(StraightLine.SetTmp(offsetReg, rhsAddr, instr.address), StraightLine.Load(instr.op.type, t, instr.op, offsetArgReg, addr = instr.address))
                return WasmBlock(instrs, Control.Jump(succ, instr.address), funcId) to RefStack(newRefs)
            }

            is WasmInstruction.Memory.Store -> {
                check(refs.size > 1)
                val arg = refs[0]
                val addr = refs[1]
                val (offsetReg, offsetArgReg) = mkTmpRegister(WasmPrimitiveType.I32, pc, OFFSET)
                val rhsAddr = WasmIcfgBinaryExpr(BinaryNumericOp.I32ADD, addr, ArgConst32(I32Value(instr.offset.value.toBigInteger())))
                val newRefs = refs.drop(2)
                val instrs =
                    listOf(StraightLine.SetTmp(offsetReg, rhsAddr, instr.address), StraightLine.Store(offsetArgReg, instr.op, instr.offset, arg, addr = instr.address))
                return WasmBlock(instrs, Control.Jump(succ, instr.address), funcId) to RefStack(newRefs)
            }
            is WasmInstruction.Memory.Copy -> {
                check(refs.size >= 3)
                val numBytes = refs[0]
                val source = refs[1]
                val dest = refs[2]
                val newRefs = refs.drop(3)
                val instrs = listOf(StraightLine.Copy(dest, source, numBytes, addr = instr.address))
                return WasmBlock(instrs, Control.Jump(succ, instr.address), funcId) to RefStack(newRefs)

            }
            is WasmInstruction.Memory.Fill -> {
                check(refs.size >= 3)
                val numBytes = refs[0]
                val value = refs[1]
                val memPtr = refs[2]
                val newRefs = refs.drop(3)
                val instrs = listOf(StraightLine.Fill(memPtr, value, numBytes, addr = instr.address))
                return WasmBlock(instrs, Control.Jump(succ, instr.address), funcId) to RefStack(newRefs)
            }
        }
    }

    fun transferUnimplemented(
        funcId: WasmName,
        instr: WasmInstruction.NondetStub,
        succ: PC,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        check (refs.size >= instr.inputs.size) { "Not enough for unimplemented instruction"}
        check (instr.outputs.size == 1)
        val o = instr.outputs.single()
        val newRefs = listOf(Havoc(o)) + refs.drop(instr.inputs.size)
        return mkBlockFromJump(succ, funcId, null) to RefStack(newRefs)
    }

    /**
     * For each cfg instruction for numeric and comparison ops,
     * return the corresponding
     * wasm block and the state of the symbolic stack at that pc.
     * Notice that all this does is pop the right number of args
     * from the argstack and return
     * a new argstack after popping whatever is needed.
     * */
    fun transferNumeric(
        funcId: WasmName,
        pc: PC,
        instr: WasmInstruction.Numeric,
        succ: PC,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        return when (instr) {
            is WasmInstruction.Numeric.I32Const -> {
                val newRefs = listOf(ArgConst32(instr.num)) + refs
                mkBlockFromJump(succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Numeric.I64Const -> {
                val newRefs = listOf(ArgConst64(instr.num)) + refs
                mkBlockFromJump(succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Numeric.NumericUnary -> {
                check(refs.isNotEmpty())
                val op = instr.op
                val arg = refs[0]
                val (t, at) = mkTmpRegister(op.type, pc, "")
                val newRefs = listOf(at).plus(refs.drop(1))
                mkBlockFromSetTmp(t, WasmIcfgUnaryExpr(op, arg), succ, funcId, instr.address) to RefStack(newRefs)

            }

            is WasmInstruction.Numeric.ComparisonUnary -> {
                check(refs.isNotEmpty())
                val op = instr.op
                val arg = refs[0]
                val (t, at) = mkTmpRegister(op.type, pc, "")
                val newRefs = listOf(at).plus(refs.drop(1))
                mkBlockFromSetTmp(t, WasmIcfgUnaryExpr(op, arg), succ, funcId, instr.address) to RefStack(newRefs)

            }

            is WasmInstruction.Numeric.NumericBinary -> {
                check(refs.size > 1) { "Not enough args at $pc: $refs" }
                val op = instr.op
                val arg2 = refs[0]
                val arg1 = refs[1]
                val (t, at) = mkTmpRegister(op.type, pc, "")
                val newRefs = listOf(at).plus(refs.drop(2))
                mkBlockFromSetTmp(t, WasmIcfgBinaryExpr(op, arg1, arg2), succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Numeric.ComparisonBinary -> {
                check(refs.size > 1)
                val op = instr.op
                val arg2 = refs[0]
                val arg1 = refs[1]
                val (t, at) = mkTmpRegister(op.type, pc, "")
                val newRefs = listOf(at).plus(refs.drop(2))
                mkBlockFromSetTmp(t, WasmIcfgBinaryExpr(op, arg1, arg2), succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Numeric.F32Const -> {
                val newRefs = listOf(Havoc(WasmPrimitiveType.F32)) + refs
                mkBlockFromJump(succ, funcId, instr.address) to RefStack(newRefs)
            }

            is WasmInstruction.Numeric.F64Const -> {
                val newRefs = listOf(Havoc(WasmPrimitiveType.F64)) + refs
                mkBlockFromJump(succ, funcId, instr.address) to RefStack(newRefs)
            }
        }
    }

    /**
     * For each cfg instruction for control ops, return the corresponding
     * wasm block and the state of the symbolic stack at that pc.
     * Notice that for `WasmCfgInstrUnreach` and `WasmCfgInstrRet`, there is no
     * next instruction, and the argstack is therefore Bottom.
     * For WasmCfgInstrCall, we need to do some extra work because we need to find the
     * arguments from the argstack and also make sure that if the call returns anything,
     * it gets put on the argstack.
     * */
    fun transferControl(
        funcId: WasmName,
        prog: WasmProgram,
        elemTable: WasmElem?,
        pc: PC,
        instr: WasmInstruction.Control,
        succs: List<PC>,
        refs: List<Arg>
    ): Pair<WasmBlock, ArgStack> {
        when (instr) {
            is WasmInstruction.Control.Nop -> {
                return mkBlockFromJump(singleSuccessor(succs), funcId, instr.address) to RefStack(refs)
            }

            is WasmInstruction.Control.Unreachable -> {
                return mkBlockFromUnreach(funcId, instr.address) to Bottom
            }

            is WasmInstruction.Control.BrIf,
            is WasmInstruction.Control.IfElse -> {
                check(refs.isNotEmpty())
                val cond = refs[0]
                if (succs.size == 2) {
                    val address = when(instr){
                        is WasmInstruction.Control.BrIf -> instr.address
                        is WasmInstruction.Control.IfElse ->  instr.address
                        else -> throw IllegalStateException("Unreachable code")
                    }
                    val ifpc = succs[0]
                    val elpc = succs[1]
                    return (mkBlockFromConditionalBranch(cond, ifpc, elpc, funcId, address) to RefStack(refs.drop(1)))
                } else {
                    throw TooManySuccessorsError("transferControl: Conditional branch has ${succs.size} succs but must have only 2.")
                }
            }

            is WasmInstruction.Control.BrTable -> {
                check(refs.isNotEmpty())
                val cond = refs[0]
                if (succs.isNotEmpty()) {
                    val def = succs.last()
                    val rest = succs.take(succs.size - 1)
                    return mkBlockFromBrTable(cond, rest, def, funcId, instr.address) to RefStack(refs.drop(1))
                } else {
                    throw TooManySuccessorsError("transferControl: BrTable has 0 succs.")
                }
            }

            is WasmInstruction.Control.Return -> {
                val funcType = prog.allFuncTypes[funcId]!! // ok to do here I think.
                return if (funcType.fnType.result == null) {
                    mkBlockFromRet(null, funcId, instr.address) to Bottom
                } else if (refs.isNotEmpty()) {
                    mkBlockFromRet(refs[0], funcId, instr.address) to Bottom
                } else {
                    throw MultipleReturnsFoundException("transferControl: multiple returns found in $funcId but not allowed.")
                }
            }
            // this is the trickiest one.
            is WasmInstruction.Control.Call -> {
                val succ = singleSuccessor(succs)
                val funcType = prog.allFuncTypes[instr.funcId]!! // ok to do here I think.
                // maybe we can check the types...reversed list of params would help if we do.
                val (params, args) = computeFuncArgs(funcType.fnType.params, refs)
                // Nothing is returned
                val resultType = funcType.fnType.result
                if (resultType == null) {
                    return mkBlockFromCall(null, instr.funcId, params, succ, funcId, instr.address) to RefStack(args)
                }
                // make a new register to represent the returned value.
                else {
                    val (rRet, aRet) = mkTmpRegister(resultType, pc, "")
                    return mkBlockFromCall(rRet, instr.funcId, params, succ, funcId, instr.address) to RefStack(listOf(aRet).plus(args))
                }
            }
            /**
             * We know the return type and args of an indirect call
             * e.g., in this instruction, `call_indirect (type 9)`, the typeuse (type 9) gives us that information.
             * We do need to figure out which func from the elem table will be called and for that we will do a pass later (`callIndirectToCall`).
             * But here, we do the almost same thing as `call` above and additionally we
             * also need to pop from the stack the value right before this call_indirect which will later be used to determine
             * which function will be called from an elem table like this for e.g., (elem (;0;) (i32.const 0) func 4 55 2).
             * So here, we will produce something like this:
             *
             * ```
             * Block 999
             *   tmp_pc_23 := local_3
             * 	 tmp_pc_21 := tmp_pc_23 i32.add 16
             *   tmp_value := expr_for_elem_idx
             *   tmp_pc_19 = wasmicfg.call_indirect(tmp_value)[4, 55, 2] (2,tmp_pc_21)
             *   ...
             * ```
             * */
            is WasmInstruction.Control.CallIndirect -> {
                val succ = singleSuccessor(succs)
                val elemTableIdx = refs[0]
                val funcType = prog.allFuncTypes.values.find { it.fnType.name == instr.type.name }?.fnType ?: throw TransferFuncNotPossible("call_indirect's function type is not found in the program.")
                val (params, args) = computeFuncArgs(funcType.params, refs.drop(1))
                // Nothing is returned
                val resultType = funcType.result
                if (resultType == null) {
                    // can't have a null element table if call_indirect is in the program.
                    return mkBlockFromCallIndirect(null, elemTableIdx, elemTable!!, params, succ, instr.type, funcId, instr.address) to RefStack(args)
                }
                // make a new register to represent the returned value.
                else {
                    val (rRet, aRet) = mkTmpRegister(resultType, pc, "")
                    return mkBlockFromCallIndirect(rRet, elemTableIdx, elemTable!!, params, succ, instr.type, funcId, instr.address) to RefStack(listOf(aRet).plus(args))
                }
            }
            is WasmInstruction.Control.Block,
            is WasmInstruction.Control.Br,
            is WasmInstruction.Control.Loop -> {
                throw TransferFuncNotPossible("$instr should not be encountered here.")
            }
        }
    }

    /**
     *  A helper for computing the arguments to the function.
     *  We use the type context of the function to find the arguments.
     * */
    private fun computeFuncArgs(typePars: List<WasmPrimitiveType>, args: List<Arg>): Pair<List<Arg>, List<Arg>> {
        // Note that for function calls, the first argument to a function is **not** the one on top of the stack. So we
        // reverse the list.
        return args.take(typePars.size).reversed() to args.drop(typePars.size)
    }
}
