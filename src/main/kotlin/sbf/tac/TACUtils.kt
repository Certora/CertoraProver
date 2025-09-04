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

import sbf.cfg.LocatedSbfInstruction
import tac.Tag
import vc.data.*
import java.math.BigInteger
import datastructures.stdcollections.*
import sbf.cfg.SbfInstruction
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.domains.PTAOffset

fun assign(lhs: TACSymbol.Var, rhs: TACExpr): TACCmd.Simple.AssigningCmd {
    return TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs,rhs)
}

fun weakAssign(lhs: TACSymbol.Var, cond: TACExpr, rhs: TACExpr):  TACCmd.Simple.AssigningCmd {
   return assign(
        lhs,
        TACExpr.TernaryExp.Ite(
            cond,
            rhs,
            lhs.asSym()
        )
    )
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    unreachable(inst: SbfInstruction): List<TACCmd.Simple> {
    return listOf(
        Debug.unreachable(inst),
        TACCmd.Simple.AssumeCmd(exprBuilder.mkBoolConst(false), "unreachable")
    )
}

/**
 *  Return TAC instructions that havoc [scalars] variables.
 *  See comments in [TACMemSplitter.HavocScalars]
 **/
fun havocScalars(scalars: List<TACByteStackVariable>): List<TACCmd.Simple> {
    return scalars.map {
        TACCmd.Simple.AssigningCmd.AssignHavocCmd(it.tacVar)
    }
}

/**
 * Return TAC instructions that havoc TAC stack variables if [base] + [offset] points to a particular stack offset.
 * See comments in [TACMemSplitter.HavocScalars]
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    weakHavocScalars(base: TACExpr.Sym.Var,
                     offset: TACExpr.Sym.Const,
                     stackMap: Map<PTAOffset, List<TACByteStackVariable>>): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    for ((stackOffset, stackVars) in stackMap) {
        if (stackVars.isNotEmpty()) {
            val tmpV = mkFreshIntVar()
            cmds += TACCmd.Simple.AssigningCmd.AssignHavocCmd(tmpV)
            for (stackVar in stackVars) {
                cmds += weakAssign(stackVar.tacVar, pointsToStack(base, offset, stackOffset), tmpV.asSym())
            }
        }
    }
    return cmds
}

/**
 * Return a TAC expression that evaluates to 0 if [l1] is equal to [l2],
 * otherwise it evaluates to 1.
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    allEqual(l1: List<TACSymbol.Var>, l2: List<TACSymbol.Var>, cmds: MutableList<TACCmd.Simple>): TACExpr {
    check(l1.size == l2.size) {"Precondition of emitTACVarsEq does not hold: $l1 and $l2 have different sizes."}
    val boolVars = ArrayList<TACSymbol.Var>(l1.size)
    for ((x,y) in l1.zip(l2)) {
        val b = mkFreshBoolVar()
        boolVars.add(b)
        cmds.add(assign(b, TACExpr.BinRel.Eq(x.asSym(), y.asSym())))
    }
    var e: TACExpr = exprBuilder.ZERO.asSym()
    for (b in boolVars.reversed()) {
        e =  TACExpr.TernaryExp.Ite(b.asSym(), e, exprBuilder.ONE.asSym())
    }
    return e
}

/** Cast a TAC.Bits to TAC.Int **/
fun promoteToMathInt(from: TACExpr.Sym, to: TACSymbol.Var): TACCmd.Simple.AssigningCmd.AssignExpCmd {
    val tag = from.tag
    check(tag != null) { "promoteToMathInt cannot find tag for $from" }
    check(tag is Tag.Bits) { "promoteToMathInt parameter should be a Tag.Bits, but is $tag in $from" }
    return TACCmd.Simple.AssigningCmd.AssignExpCmd(
        lhs = to,
        rhs = TACExpr.Apply(
            f = TACExpr.TACFunctionSym.BuiltIn(
                TACBuiltInFunction.SafeMathPromotion(tag)
            ),
            ops = listOf(from),
            tag = Tag.Int
        )
    )
}

/** Cast from TAC.Int to TAC.Bits **/
fun narrowFromMathInt(from: TACExpr.Sym, to: TACSymbol.Var, toTag: Tag.Bits = Tag.Bit256): TACCmd.Simple.AssigningCmd.AssignExpCmd {
    check(from.tag == Tag.Int) {"narrowToBit expects an Int variable"}
    return TACCmd.Simple.AssigningCmd.AssignExpCmd(
        lhs = to,
        rhs = TACExpr.Apply(
            TACExpr.TACFunctionSym.BuiltIn(TACBuiltInFunction.SafeMathNarrow(toTag)),
            listOf(from),
            toTag
        )
    )
}

/** res = high << 64 + low **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> mergeU128(
    low: TACExpr.Sym, high: TACExpr.Sym, cmds: MutableList<TACCmd.Simple>): TACSymbol.Var {
    val res = mkFreshIntVar(bitwidth = 256)
    cmds.add(mergeU128(res, low, high))
    return res
}
/** res = high << 64 + low **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> mergeU128(
    res: TACSymbol.Var, low: TACExpr.Sym, high: TACExpr.Sym): TACCmd.Simple.AssigningCmd {
    val c64E = exprBuilder.SIXTY_FOUR.asSym()
    return assign(res, TACExpr.Vec.Add(listOf(TACExpr.BinOp.ShiftLeft(high, c64E), exprBuilder.mask64(low))))
}

/** res = high << 64 + low **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> mergeU128Raw(
    res: TACSymbol.Var, low: TACExpr.Sym, high: TACExpr.Sym): TACCmd.Simple.AssigningCmd {
    val c64E = exprBuilder.SIXTY_FOUR.asSym()
    return assign(res, TACExpr.Vec.Add(listOf(TACExpr.BinOp.ShiftLeft(high, c64E), low)))
}

/** res = (w4 << 192) + (w3 << 128) + (w2 << 64) + w1 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> mergeU256Raw(
    res: TACSymbol.Var, w1: TACExpr.Sym, w2: TACExpr.Sym, w3:TACExpr.Sym, w4: TACExpr.Sym): TACCmd.Simple.AssigningCmd {
    check(res.tag is Tag.Bit256) {"mergeU256 expects $res to be Tag.Bit256"}

    val c64  = exprBuilder.SIXTY_FOUR.asSym()
    val c128 = exprBuilder.mkConst(128, false, 256).asSym()
    val c196 = exprBuilder.mkConst(196, false, 256).asSym()
    return assign(res, TACExpr.Vec.Add(
       listOf(
            TACExpr.BinOp.ShiftLeft(w4, c196),
            TACExpr.BinOp.ShiftLeft(w3, c128),
            TACExpr.BinOp.ShiftLeft(w2, c64),
            w1
        )
    ))
}

/**
 *  low = e & MASK64
 *  high = e >> 64
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> splitU128(
    e: TACSymbol.Var, low: TACSymbol.Var, high: TACSymbol.Var): List<TACCmd.Simple> {
    val c64E = exprBuilder.SIXTY_FOUR.asSym()
    val twoPowerOf128 = BigInteger.TWO.pow(128).asTACExpr()
    val x = mkFreshIntVar()
    return listOf(
     assign(x, TACExpr.BinOp.Mod(e.asSym(), twoPowerOf128)),
     assign(low, exprBuilder.mask64(x.asSym())),
     assign(high, TACExpr.BinOp.ShiftRightLogical(x.asSym(), c64E)))
}

/** Get the symbolic TAC variables corresponding to the low and high bits of the returned u128 value **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> getLowAndHighFromU128(
    locInst: LocatedSbfInstruction
): Pair<TACVariable, TACVariable>? {
    val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: return null
    if (summaryArgs.size != 2) {
        return null
    }
    val resLow  = summaryArgs[0].variable
    val resHigh = summaryArgs[1].variable
    return if (resLow !is TACByteStackVariable || resHigh !is TACByteStackVariable) {
        null
    } else {
        Pair(resLow, resHigh)
    }
}

data class U128Operands(val resLow: TACSymbol.Var,
                        val resHigh: TACSymbol.Var,
                        val xLow: TACExpr.Sym,
                        val xHigh: TACExpr.Sym,
                        val yLow: TACExpr.Sym,
                        val yHigh: TACExpr.Sym
)

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> applyU128Operation(
        args: U128Operands,
        cmds: MutableList<TACCmd.Simple>,
        op: (res: TACSymbol.Var, x: TACSymbol.Var, y: TACSymbol.Var) -> Unit) {
    val res = mkFreshIntVar()
    val x = mergeU128(args.xLow, args.xHigh, cmds)
    val y = mergeU128(args.yLow, args.yHigh, cmds)
    op(res, x, y)
    cmds.addAll(splitU128(res, args.resLow, args.resHigh))
}
