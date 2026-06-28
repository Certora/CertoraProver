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

package verifier

import analysis.opt.intervals.IntervalsRewriter
import analysis.opt.intervals.IntervalsRewriter.Companion.NON_NEG_META
import analysis.opt.intervals.IntervalsRewriter.Companion.NON_ZERO_META
import analysis.opt.intervals.IntervalsRewriter.Companion.isSurelyNonNeg
import analysis.opt.intervals.IntervalsRewriter.Companion.isSurelyPos
import config.Config
import datastructures.stdcollections.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldPlusOneCmd
import vc.data.tacexprutil.asConst
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isVar
import java.math.BigInteger

/**
 * Replaces division assignments `x := a / b` with an equivalent havoc-and-assume encoding,
 * giving solvers a more tractable shape than a primitive division operator.
 *
 * For integers with `a >= 0` and `b > 0`, the floor division `x = ⌊a / b⌋` is uniquely
 * characterized by
 *
 *     b * x <= a < b * (x + 1)
 *
 * (The left inequality says `x` is not too large; the right says `x + 1` is too large; together
 * they pin down the unique floor.) So instead of asserting `x = a / b`, we havoc `x` and
 * constrain it via these two assumes — the SMT solver sees only multiplications and additions,
 * which it handles much better than an opaque `div` symbol.
 *
 * The rewrite handles both [TACExpr.BinOp.Div] (unsigned bitvector division) and
 * [TACExpr.BinOp.IntDiv] (mathematical-integer division). It applies whenever:
 *  - `a >= 0` && `b > 0`
 * The non-negativity precondition on `a` matters for [TACExpr.BinOp.IntDiv]: that operator is
 * defined as truncation toward zero, which diverges from floor when `a < 0` (e.g. `-7 / 2`
 * truncates to `-3` but floors to `-4`). For [TACExpr.BinOp.Div] the precondition is automatic,
 * since bit-vector operands are unsigned and so trunc and floor coincide.
 *
 * The metas [NON_NEG_META] and [NON_ZERO_META] are supplied by [IntervalsRewriter], so this
 * pass is most effective when run after it.
 */
fun purifyDivisions(code: CoreTACProgram): CoreTACProgram {
    if (Config.Smt.UseBV.get()) {
        // If we are running with a 256 bit bitvector solver, multiplications may overflow, and this will cause
        // the pattern to go wrong.
        return code
    }
    val patcher = ConcurrentPatchingProgram(code)
    val txf = TACExprFactUntyped

    for ((ptr, cmd) in code.parallelLtacStream()) {
        if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            continue
        }
        val rhs = cmd.rhs
        if (!(rhs is TACExpr.BinOp.Div || rhs is TACExpr.BinOp.IntDiv)) {
            continue
        }
        val x = cmd.lhs
        val (a, b) = rhs.getOperands()
        if (!a.isSurelyNonNeg()) {
            continue
        }
        when {
            Config.PurifyConstDivisions.get() && b.isConst && b.asConst > BigInteger.ZERO -> {} // we're good
            Config.PurifyDivisions.get() && b.isVar && b.isSurelyPos() -> {} // Also good.
            else -> continue
        }

        val firstAssume = unfoldPlusOneCmd(
            tempVarPrefix = "div",
            expr = txf {
                Le(IntMul(x.asSym(), b), a)
            },
            last = { TACCmd.Simple.AssumeCmd(it.s, "Division purification") }
        )
        val secondAssume = unfoldPlusOneCmd(
            tempVarPrefix = "div",
            expr = txf {
                Gt(IntMul(IntAdd(x.asSym(), One), b), a)
            },
            last = { TACCmd.Simple.AssumeCmd(it.s, "Division purification") }
        )
        patcher.addVarDecls(firstAssume.varDecls)
        patcher.addVarDecls(secondAssume.varDecls)
        patcher.replace(
            ptr,
            listOf(TACCmd.Simple.AssigningCmd.AssignHavocCmd(x, cmd.meta)) +
                firstAssume.cmds + secondAssume.cmds
        )
    }

    return patcher.toCode()
}
