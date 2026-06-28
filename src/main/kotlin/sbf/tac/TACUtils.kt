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
import datastructures.stdcollections.*
import sbf.cfg.CondOp
import sbf.cfg.SbfInstruction
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import sbf.domains.PTAOffset
import tac.MetaMap
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol

fun assign(lhs: TACSymbol.Var, rhs: TACExpr) = TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs,rhs)

fun havoc(v: TACSymbol.Var) = TACCmd.Simple.AssigningCmd.AssignHavocCmd(v)

fun assert(e: TACSymbol, msg: String, meta: MetaMap) = TACCmd.Simple.AssertCmd(e, msg, meta)

/**
 *  Return TAC instructions that havoc [scalars] variables.
 *  See comments in [TACMemSplitter.HavocScalars]
 **/
fun havocScalars(scalars: List<TACByteStackVariable>) = scalars.map { havoc(it.tacVar) }

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    weakAssign(lhs: TACSymbol.Var, cond: TACExpr, rhs: TACExpr) =
    assign(lhs, sbfTacB { ite(cond, rhs, lhs.asSym())} )

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    unreachable(inst: SbfInstruction) =
    listOf(Debug.unreachable(inst)) + assume(sbfTacB.FALSE, "unreachable")

/**
 * Return TAC instructions that havoc TAC stack variables if [base] + [offset] points to a particular stack offset.
 * See comments in [TACMemSplitter.HavocScalars]
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    weakHavocScalars(base: TACExpr.Sym.Var,
                     offset: TACExpr.Sym.Const,
                     stackMap: Map<PTAOffset, List<TACByteStackVariable>>): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    for ((stackOffset, stackVars) in stackMap) {
        if (stackVars.isNotEmpty()) {
            val tmpV = vFac.mkFreshIntVar()
            cmds += havoc(tmpV)
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
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    allEqual(l1: List<TACSymbol.Var>, l2: List<TACSymbol.Var>, cmds: MutableList<TACCmd.Simple>): TACExpr {
    check(l1.size == l2.size) {"Precondition of emitTACVarsEq does not hold: $l1 and $l2 have different sizes."}
    val boolVars = ArrayList<TACSymbol.Var>(l1.size)
    for ((x,y) in l1.zip(l2)) {
        val b = vFac.mkFreshBoolVar()
        boolVars.add(b)
        cmds.add(assign(b, sbfTacB { x.asSym() eq y.asSym() }))
    }
    var e: TACExpr = sbfTacB.ZERO
    for (b in boolVars.reversed()) {
        e =  sbfTacB { ite(b.asSym(), e, sbfTacB.ONE) }
    }
    return e
}

/** Cast a `Tag.Bit256` to `Tag.Int` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    promoteToMathInt(from: TACExpr, to: TACSymbol.Var): TACCmd.Simple.AssigningCmd.AssignExpCmd {
    val tag = from.tag
    check(tag != null) { "promoteToMathInt cannot find tag for $from" }
    check(tag is Tag.Bit256) { "promoteToMathInt parameter should be a Tag.Bit256, but is $tag in $from" }
    return assign(to, sbfTacB.bv256ToMathInt(from))
}

/** Cast from `Tag.Int` to `Tag.Bit256` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    narrowFromMathInt(from: TACExpr, to: TACSymbol.Var): TACCmd.Simple.AssigningCmd.AssignExpCmd {
    check(from.tag == Tag.Int) {"narrowToBit expects an Int variable"}
    return assign(to, sbfTacB.mathIntToBv256(from))
}

/** `res = high << 64 + low` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    mergeU128(
    low: TACExpr.Sym,
    high: TACExpr.Sym,
    cmds: MutableList<TACCmd.Simple>,
    /** If true, the implementation is allowed to mask the low bits (ignored in "eager masking" mode) */
    mayMaskLowBits: Boolean = true
): TACSymbol.Var {
    val res = vFac.mkFreshIntVar()
    cmds.add(mergeU128(res, low, high, mayMaskLowBits))
    return res
}
/** `res = high << 64 + low` **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    mergeU128(
    res: TACSymbol.Var,
    low: TACExpr.Sym,
    high: TACExpr.Sym,
    /** If true, the implementation is allowed to mask the low bits (ignored in "eager masking" mode) */
    mayMaskLowBits: Boolean
): TACCmd.Simple.AssigningCmd = assign(res, sbfTacB.mergeU128(low, high, mayMaskLowBits))

/**
 *  Split [e] into [low] and [high] such that:
 *  ```
 *  low = e & MASK64
 *  high = e >> 64
 *  ```
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    splitU128(
    e: TACExpr, low: TACSymbol.Var, high: TACSymbol.Var): List<TACCmd.Simple> {

    val (x, y) = sbfTacB.splitU128(e)
    return listOf(
        assign(low, x),
        assign(high, y)
    )
}

data class Result128(
    val low: TACVariable,
    val high: TACVariable,
    val overflow: TACVariable?
)

/**
 * Get the symbolic TAC variables corresponding to the result of a u128/i128 operation.
 *
 * This function assumes the summarized instruction writes its results to exactly two or three
 * stack locations, in the following order:
 *  1. Low half  — the lower 64 bits of the 128-bit result.
 *  2. High half — the upper 64 bits of the 128-bit result.
 *  3. Overflow flag (optional) — present only for operations that can overflow (e.g. addition,
 *     multiplication). When absent the returned [Result128.overflow] field is `null`.
 *
 * Returns `null` if the summary does not exist or does not conform to the expected layout.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    getResFrom128(
    locInst: LocatedSbfInstruction
): Result128? {
    val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: return null
    val numArgs = summaryArgs.size
    if (numArgs != 2 && numArgs != 3) {
        return null
    }
    val resLow  = (summaryArgs[0].variable as? TACMemSplitter.SummaryArgVariable.Stack)?.v ?: return null
    val resHigh = (summaryArgs[1].variable as? TACMemSplitter.SummaryArgVariable.Stack)?.v ?: return null
    return if (numArgs == 3) {
        val overflow = (summaryArgs[2].variable as? TACMemSplitter.SummaryArgVariable.Stack)?.v ?: return null
        Result128(resLow, resHigh, overflow)
    } else {
        Result128(resLow, resHigh, null)
    }
}

data class U128BinaryOperands(val resLow: TACSymbol.Var,
                              val resHigh: TACSymbol.Var,
                              val overflow: TACSymbol.Var?,
                              val xLow: TACExpr.Sym,
                              val xHigh: TACExpr.Sym,
                              val yLow: TACExpr.Sym,
                              val yHigh: TACExpr.Sym
)

data class U128ShiftOperands(val resLow: TACSymbol.Var,
                              val resHigh: TACSymbol.Var,
                              val xLow: TACExpr.Sym,
                              val xHigh: TACExpr.Sym,
                              val shift: TACExpr.Sym
)

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    applyU128BinaryOperation(
    args: U128BinaryOperands,
    cmds: MutableList<TACCmd.Simple>,
    op: (res: TACSymbol.Var, overflow: TACSymbol.Var?, x: TACSymbol.Var, y: TACSymbol.Var) -> Unit
) {
    val res = vFac.mkFreshIntVar()
    val x = mergeU128(args.xLow, args.xHigh, cmds)
    val y = mergeU128(args.yLow, args.yHigh, cmds)
    op(res, args.overflow, x, y)
    cmds.addAll(splitU128(res.asSym(), args.resLow, args.resHigh))
}

/**
 * Merges the low and high halves of `x` (and optionally `y`) into full 128-bit values and
 * evaluates a relational [op] on them, assigning the boolean result to [res].
 *
 * Unlike [applyU128BinaryOperation], there is no [splitU128] step: the operation produces a
 * scalar (boolean/condition) result rather than a new u128 value.
 */
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    applyU128RelationalOperation(
    res: TACSymbol.Var,
    xLow: TACExpr.Sym, xHigh: TACExpr.Sym,
    cmds: MutableList<TACCmd.Simple>,
    op: (x: TACSymbol.Var) -> TACExpr
) {
    val x = mergeU128(xLow, xHigh, cmds)
    cmds += assign(res, op(x))
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    applyU128RelationalOperation(
    res: TACSymbol.Var,
    xLow: TACExpr.Sym, xHigh: TACExpr.Sym,
    yLow: TACExpr.Sym, yHigh: TACExpr.Sym,
    cmds: MutableList<TACCmd.Simple>,
    op: (x: TACSymbol.Var, y: TACSymbol.Var) -> TACExpr
) {
    val x = mergeU128(xLow, xHigh, cmds)
    val y = mergeU128(yLow, yHigh, cmds)
    cmds += assign(res, op(x, y))
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    applyU128ShiftOperation(
    args: U128ShiftOperands,
    cmds: MutableList<TACCmd.Simple>,
    op: (res: TACSymbol.Var, x: TACSymbol.Var, shift: TACExpr.Sym) -> Unit) {
    val res = vFac.mkFreshIntVar()
    val x = mergeU128(args.xLow, args.xHigh, cmds)
    val shift = args.shift
    op(res, x, shift)
    cmds.addAll(splitU128(res.asSym(), args.resLow, args.resHigh))
}

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    assume(
    op: CondOp,
    left: TACExpr,
    right: TACExpr,
    msg: String
): List<TACCmd.Simple> = assume(op(left, right, sbfTacB), msg)

context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    assume(
    e: TACExpr,
    msg: String
): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    val b = vFac.mkFreshBoolVar()
    cmds += assign(b, e)
    cmds += TACCmd.Simple.AssumeCmd(b, msg)
    return cmds
}

/** Return this sequence of TAC commands:
 *
 * ```
 *   v := havoc()
 *   b1 := e1
 *   assume(b1)
 *   b2 := e2
 *   assume(b2)
 *   ...
 * ```
 * where each `ei` is an element of [assumptions] and refers to `v`
 **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    nondetWithAssumptions(
    v: TACSymbol.Var,
    assumptions: List<TACExpr> = listOf()
): List<TACCmd.Simple> {
    val cmds = mutableListOf<TACCmd.Simple>()
    cmds += havoc(v)
    for (assumption in assumptions) {
        cmds += assume(assumption, "")
    }
    return cmds
}

/** Extract TAC variables used by a summary **/
context(SbfCFGToTAC<TNum, TOffset, TFlags>)
internal fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>>
    getTACVariables(
    locInst: LocatedSbfInstruction,
    cmds: MutableList<TACCmd.Simple>
) : List<TACSymbol.Var> {
    val summaryArgs = mem.getTACMemoryFromSummary(locInst) ?: listOf()
    val tacVars = mutableListOf<TACSymbol.Var>()
    if (summaryArgs.isNotEmpty()) {
        for (arg in summaryArgs) {
            val tacV = when (val v = arg.variable) {
                is TACMemSplitter.SummaryArgVariable.Stack -> v.v.tacVar
                is TACMemSplitter.SummaryArgVariable.NonStack -> {
                    val lhs = vFac.mkFreshIntVar()
                    cmds += withReadableByteMap(v.target, "summary_arg") { mapVar ->
                        val inner = mutableListOf<TACCmd.Simple>()
                        val loc = computeTACMapIndex(sbfTacB.mkVar(arg.reg), arg.offset, inner)
                        inner += sbfTacB.load(lhs, loc, arg.width.toShort(), mapVar.tacVar)
                        inner
                    }
                    lhs
                }
            }
            tacVars.add(tacV)
        }
    }
    return tacVars
}
