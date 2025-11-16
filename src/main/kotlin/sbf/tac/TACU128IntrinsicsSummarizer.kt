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
import java.math.BigInteger
import datastructures.stdcollections.*
import sbf.callgraph.CVTU128Intrinsics
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags

/**
 * Summarize u128 intrinsics.
 *
 * Precondition: UseTACMathInt is enabled
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeU128(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call) {"summarizeU128 expects a call instruction instead of ${locInst.inst}"}
    val function = CVTU128Intrinsics.from(inst.name)
    check(function != null) {"summarizeU128 does not support ${inst.name}"}
    return when (function) {
        CVTU128Intrinsics.U128_LEQ -> summarizeU128Leq(locInst)
        CVTU128Intrinsics.U128_GT0 -> summarizeU128Gt0(locInst)
        CVTU128Intrinsics.U128_CEIL_DIV -> summarizeU128CeilDiv(locInst)
        CVTU128Intrinsics.U128_NONDET -> summarizeU128Nondet(locInst)
    }
}

/**
 * Given `r1: low(x)`, `r2: high(x)`, `r3: low(y)`, `r4: high(y)` and `result` in `r0`
 *
 * We do case by case using nested ite terms
 * 1. if `high(x) == 0` and `high(y) == 0` then `low(x) <= low(y)`
 * 2. if `high(x) == 0` and `high(y) != 0` then `true`
 * 3. if `high(x) != 0` and `high(y) == 0` then `false`
 * 4. if `high(x) != 0` and `high(y) != 0` then `(high(x) << 64 + low(x)) <= (high(y) << 64 + low(y))`
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeU128Leq(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    {"summarizeU128Leq expects a call instruction instead of ${locInst.inst}"}
    check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_LEQ)
    {"summarizeU128Leq expects ${CVTU128Intrinsics.U128_LEQ.function.name}"}

    val res = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
    val xLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R1_ARG))
    val xHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R2_ARG))
    val yLowE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R3_ARG))
    val yHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R4_ARG))

    val cmds = mutableListOf(Debug.externalCall(inst))
    val xE = mergeU128(xLowE, xHighE, cmds)
    val yE = mergeU128(yLowE, yHighE, cmds)
    val cond = TACExpr.TernaryExp.Ite(
        TACExpr.BinBoolOp.LAnd(
            TACExpr.BinRel.Eq(xHighE, TACExpr.zeroExpr),
            TACExpr.BinRel.Eq(yHighE, TACExpr.zeroExpr)),
        exprBuilder.mkBinRelExp(CondOp.LE, xLowE, yLowE),
        TACExpr.TernaryExp.Ite(
            TACExpr.BinBoolOp.LAnd(
                TACExpr.BinRel.Eq(xHighE, TACExpr.zeroExpr),
                TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(yHighE, TACExpr.zeroExpr))),
            TACSymbol.True.asSym(),
            TACExpr.TernaryExp.Ite(
                TACExpr.BinBoolOp.LAnd(
                    TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(xHighE, TACExpr.zeroExpr)),
                    TACExpr.BinRel.Eq(yHighE, TACExpr.zeroExpr)),
                TACSymbol.False.asSym(),
                exprBuilder.mkBinRelExp(CondOp.LE, xE.asSym(), yE.asSym()),
            )
        )
    )
    cmds.add(assign(res, TACExpr.TernaryExp.Ite(cond, exprBuilder.ONE.asSym(), TACExpr.zeroExpr)))
    return cmds
}

/**
 *  Given `r1: low(x)`, `r2: high(x)` and `res` in `r0` compute:
 *  ```
 *  high(x) != 0 || low(x) > 0
 *  ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeU128Gt0(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    { "summarizeU128Gt0 expects a call instruction instead of ${locInst.inst}" }
    check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_GT0)
    { "summarizeU128Gt0 expects ${CVTU128Intrinsics.U128_GT0.function.name}" }

    val res = exprBuilder.mkVar(SbfRegister.R0_RETURN_VALUE)
    val xLowE  = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R1_ARG))
    val xHighE = exprBuilder.mkExprSym(Value.Reg(SbfRegister.R2_ARG))

    val cmds = mutableListOf(Debug.externalCall(inst))
    cmds.add(assign(res, TACExpr.BinBoolOp.LOr(
        TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(xHighE, TACExpr.zeroExpr)),
        exprBuilder.mkBinRelExp(CondOp.GT, xLowE, TACExpr.zeroExpr))))
    return cmds
}

/**
 * Given `r2: low(x)`, `r3: high(x)`, `r4: low(y)`, `r5: high(y)`, `low(result)` in `*(r0+0)`, and `high(result)` in `*(r0+8)`
 * compute:
 * ```
 * ceil_div(x, y) = (x + y - 1) / y
 * ```
 * where `x = high(x) << 64 + low(x)` and `y = high(y) << 64 + low(y)`
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeU128CeilDiv(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    { "summarizeU128CeilDiv expects a call instruction instead of ${locInst.inst}" }
    check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_CEIL_DIV)
    { "summarizeU128CeilDiv expects ${CVTU128Intrinsics.U128_CEIL_DIV.function.name}" }

    val (resLow, resHigh, overflow) = getResFrom128(locInst) ?: return listOf()
    val xLowE  = exprBuilder.mkVar(SbfRegister.R2_ARG).asSym()
    val xHighE = exprBuilder.mkVar(SbfRegister.R3_ARG).asSym()
    val yLowE  = exprBuilder.mkVar(SbfRegister.R4_ARG).asSym()
    val yHighE = exprBuilder.mkVar(SbfRegister.R5_ARG).asSym()
    val args = U128BinaryOperands(resLow.tacVar, resHigh.tacVar, overflow?.tacVar, xLowE, xHighE, yLowE, yHighE)

    val (xMath, yMath, resMath) = Triple(mkFreshMathIntVar(), mkFreshMathIntVar(), mkFreshMathIntVar())
    val cmds = mutableListOf(Debug.externalCall(inst))
    applyU128BinaryOperation(args, cmds) { res, _, x, y ->
        cmds.add(promoteToMathInt(x.asSym(), xMath))
        cmds.add(promoteToMathInt(y.asSym(), yMath))
        cmds.add(assign(resMath, TACExpr.BinOp.IntDiv(
            TACExpr.BinOp.IntSub(TACExpr.Vec.IntAdd(xMath.asSym(), yMath.asSym()), exprBuilder.ONE.asSym()),
            yMath.asSym())))
        cmds.add(narrowFromMathInt(resMath.asSym(), res))
    }
    return cmds
}

/**
 * Given `low(result)` in `*(r0+0)`, and  `high(result)` in `*(r0+8)` compute
 *
 * ```
 * result < 2^128
 * ```
 * where `result = high(result) << 64 + low(result)`
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> summarizeU128Nondet(
    locInst: LocatedSbfInstruction
): List<TACCmd.Simple> {
    val inst = locInst.inst
    check(inst is SbfInstruction.Call)
    { "summarizeU128Nondet expects a call instruction instead of ${locInst.inst}" }
    check(CVTU128Intrinsics.from(inst.name) == CVTU128Intrinsics.U128_NONDET)
    { "summarizeU128Nondet expects ${CVTU128Intrinsics.U128_NONDET.function.name}" }

    val (resLow, resHigh) = getResFrom128(locInst) ?: return listOf()
    val res = mkFreshIntVar()

    val cmds = mutableListOf(Debug.externalCall(inst))
    cmds.addAll(inRange(res, BigInteger.ZERO,  BigInteger.TWO.pow(128)))
    cmds.addAll(splitU128(res, resLow.tacVar, resHigh.tacVar))
    return cmds
}
