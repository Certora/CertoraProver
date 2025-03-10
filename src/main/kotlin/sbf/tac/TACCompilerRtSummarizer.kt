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

import sbf.callgraph.CompilerRtFunction
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import vc.data.*
import datastructures.stdcollections.*

/**
 * Summarize compiler-rt functions.
 * Not all functions are currently summarized.
 * Return empty list if function is not summarized.
 **/

/** Default implementation using 256-bit numbers **/
context(SbfCFGToTAC)
open class SummarizeCompilerRt {

    open fun summarizeMulti3(inst: SbfInstruction.Call, args: U128Operands): List<TACCmd.Simple> {
        val cmds = mutableListOf(Debug.externalCall(inst))
        applyU128Operation(args, cmds) { res, x, y ->
            cmds.add(assign(res, TACExpr.Vec.Mul(listOf(x.asSym(), y.asSym()))))
        }
        return cmds
    }

    open fun summarizeUDivti3(inst: SbfInstruction.Call, args: U128Operands): List<TACCmd.Simple> {
        val cmds = mutableListOf(Debug.externalCall(inst))
        applyU128Operation(args, cmds) { res, x, y ->
            cmds.add(assign(res, TACExpr.BinOp.Div(x.asSym(), y.asSym())))
        }
        return cmds
    }

    open fun summarizeDivti3(inst: SbfInstruction.Call, args: U128Operands): List<TACCmd.Simple> {
        return listOf(
            Debug.externalCall(inst),
            assign(args.resLow, TACExpr.BinOp.SDiv(args.xLow, args.yLow)),
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(args.resHigh)
        )
    }

    operator fun invoke(locInst: LocatedSbfInstruction): List<TACCmd.Simple> {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call) { "summarizeCompilerRt expects a call instruction instead of $inst" }

        val function = CompilerRtFunction.from(inst.name) ?: return listOf()
        val args = getCompilerRtArgs(locInst) ?: return listOf()
        return when (function) {
            CompilerRtFunction.MULTI3 -> summarizeMulti3(inst, args)
            CompilerRtFunction.UDIVTI3 -> summarizeUDivti3(inst, args)
            CompilerRtFunction.DIVTI3 -> summarizeDivti3(inst, args)
        }
    }

    private fun getCompilerRtArgs(locInst: LocatedSbfInstruction): U128Operands? {
        val (resLow, resHigh) = getLowAndHighFromU128(locInst) ?: return null
        val xLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R2_ARG))
        val xHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R3_ARG))
        val yLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R4_ARG))
        val yHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R5_ARG))
        return U128Operands(resLow.tacVar, resHigh.tacVar, xLowE, xHighE, yLowE, yHighE)
    }
}

context(SbfCFGToTAC)
class SummarizeCompilerRtWithMathInt: SummarizeCompilerRt() {
    override fun summarizeMulti3(inst: SbfInstruction.Call, args: U128Operands): List<TACCmd.Simple> {
        // We are using 256-bits so multiplication of 128-bits cannot overflow
        val (xMath, yMath, resMath) = Triple(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
        val cmds = mutableListOf(Debug.externalCall(inst))
        applyU128Operation(args, cmds) { res, x, y ->
            cmds.add(promoteToMathInt(x.asSym(), xMath))
            cmds.add(promoteToMathInt(y.asSym(), yMath))
            cmds.add(assign(resMath, TACExpr.Vec.IntMul(listOf(xMath.asSym(), yMath.asSym()))))
            cmds.add(narrowFromMathInt(resMath.asSym(), res))
        }
        return cmds
    }

    override fun summarizeUDivti3(inst: SbfInstruction.Call, args: U128Operands): List<TACCmd.Simple> {
        // We are using 256-bits so division of 128-bits cannot overflow
        val (xMath, yMath, resMath) = Triple(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
        val cmds = mutableListOf(Debug.externalCall(inst))
        applyU128Operation(args, cmds) { res, x, y ->
            cmds.add(promoteToMathInt(x.asSym(), xMath))
            cmds.add(promoteToMathInt(y.asSym(), yMath))
            cmds.add(assign(resMath, TACExpr.BinOp.IntDiv(xMath.asSym(), yMath.asSym())))
            cmds.add(narrowFromMathInt(resMath.asSym(), res))
        }
        return cmds
    }
}
