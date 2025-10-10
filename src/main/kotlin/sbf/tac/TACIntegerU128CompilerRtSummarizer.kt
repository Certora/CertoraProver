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

import sbf.cfg.*
import sbf.disassembler.SbfRegister
import vc.data.*
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.domains.*
import tac.Tag
import tac.generation.assignHavoc
import java.math.BigInteger
import vc.data.TACExprFactUntyped as txf

/**
 * Summarize 128-bits integer compiler-rt functions.
 *
 * Not all functions are currently summarized.
 **/

/** Default implementation using 256-bit numbers **/
open class SummarizeIntegerU128CompilerRt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> {

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    open fun summarizeMulti3(args: U128Operands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, _, x, y ->
            cmds.add(assign(res, TACExpr.Vec.Mul(listOf(x.asSym(), y.asSym()))))
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    open fun summarizeMuloti4(args: U128Operands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, overflow, _, _ ->
            check(overflow != null)
            cmds.addAll(assignHavoc(res).cmds)
            cmds.addAll(
                assume(txf { (overflow eq exprBuilder.ZERO) or (overflow eq exprBuilder.ONE) },
                    msg = "__muloti4 is modeled as a nondet function. " +
                          "Enable \"${SolanaConfig.UseTACMathInt.name}\" for more precise modelling")
            )
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    open fun summarizeUDivti3(args: U128Operands): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, _, x, y ->
            cmds.add(assign(res, TACExpr.BinOp.Div(x.asSym(), y.asSym())))
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    open fun summarizeDivti3(args: U128Operands): List<TACCmd.Simple> {
        return listOf(
            assign(args.resLow, TACExpr.BinOp.SDiv(args.xLow, args.yLow)),
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(args.resHigh)
        )
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    fun getArgsFromU128BinaryCompilerRt(locInst: LocatedSbfInstruction): U128Operands? {
        val (resLow, resHigh, overflow) = getResFrom128(locInst) ?: return null
        val xLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R2_ARG))
        val xHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R3_ARG))
        val yLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R4_ARG))
        val yHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R5_ARG))
        return U128Operands(resLow.tacVar, resHigh.tacVar, overflow?.tacVar, xLowE, xHighE, yLowE, yHighE)
    }
}

/** Specialization using mathint **/
class SummarizeIntegerU128CompilerRtWithMathInt<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    : SummarizeIntegerU128CompilerRt<TNum, TOffset, TFlags>() {

    companion object {
        private val MAX_I128 = TACSymbol.Const(BigInteger.TWO.pow(127) - BigInteger.ONE, Tag.Int)
        private val MIN_I128 = TACSymbol.Const(BigInteger.ZERO - BigInteger.TWO.pow(127), Tag.Int)
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeMulti3(args: U128Operands): List<TACCmd.Simple> {
        // We are using 256-bits so multiplication of 128-bits cannot overflow
        val (xMath, yMath, resMath) = Triple(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, _, x, y ->
            cmds.add(promoteToMathInt(x.asSym(), xMath))
            cmds.add(promoteToMathInt(y.asSym(), yMath))
            cmds.add(assign(resMath, TACExpr.Vec.IntMul(listOf(xMath.asSym(), yMath.asSym()))))
            cmds.add(narrowFromMathInt(resMath.asSym(), res))
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun to128SignedMathInt(x: TACSymbol.Var): TACExpr {
        check(x.tag == Tag.Int)
        return txf {
            ite(
                x lt TACSymbol.Const(BigInteger.TWO.pow(127), Tag.Int),
                x,
                x intSub TACSymbol.Const(BigInteger.TWO.pow(128), Tag.Int)
            )
        }
    }

    private fun overflowCondSign128(x: TACSymbol.Var): TACExpr {
        check(x.tag == Tag.Int) { "overflowCondSign128 expects an \"Int\" input" }
        // sLe is only for BV256. The use of le here should be signed less or equal since `x` is a mathint.
        return txf { not((MIN_I128 le x) and (x le MAX_I128)) }
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeMuloti4(args: U128Operands): List<TACCmd.Simple> {
        val (xMath, yMath, resMath) = listOf(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, overflow, x, y ->
            check(overflow != null)
            cmds.add(promoteToMathInt(x.asSym(), xMath))
            cmds.add(promoteToMathInt(y.asSym(), yMath))
            // `res`: same behavior as __multi3
            cmds.add(assign(resMath, TACExpr.Vec.IntMul(listOf(xMath.asSym(), yMath.asSym()))))
            cmds.add(narrowFromMathInt(resMath.asSym(),  res))
            // overflow flag
            val tmp = mkFreshMathIntVar()
            cmds.add(assign(tmp, txf { to128SignedMathInt(xMath) intMul to128SignedMathInt(yMath) } ))
            cmds.add(assign(overflow, txf { ite(overflowCondSign128(tmp),  exprBuilder.ONE, exprBuilder.ZERO) }))
        }
        return cmds
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeUDivti3(args: U128Operands): List<TACCmd.Simple> {
        // We are using 256-bits so division of 128-bits cannot overflow
        val (xMath, yMath, resMath) = Triple(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
        val cmds = mutableListOf<TACCmd.Simple>()
        applyU128Operation(args, cmds) { res, _, x, y ->
            cmds.add(promoteToMathInt(x.asSym(), xMath))
            cmds.add(promoteToMathInt(y.asSym(), yMath))
            cmds.add(assign(resMath, TACExpr.BinOp.IntDiv(xMath.asSym(), yMath.asSym())))
            cmds.add(narrowFromMathInt(resMath.asSym(), res))
        }
        return cmds
    }
}
