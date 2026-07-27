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

package analysis.opt

import analysis.LTACSymbol
import analysis.opt.PatternRewriter.Key.*
import analysis.opt.intervals.IntervalsRewriter.Companion.NON_ZERO_META
import analysis.opt.intervals.IntervalsRewriter.Companion.isSurelyNonNeg
import analysis.opt.intervals.IntervalsRewriter.Companion.isSurelyPos
import analysis.patterns.Info
import analysis.patterns.get
import config.Config
import utils.*
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACExpr
import vc.data.tacexprutil.isConst
import vc.data.tacexprutil.isVar
import java.math.BigInteger


/**
 * The div-comparison rewrites are only sound for a strictly positive divisor (for a negative [IntDiv]
 * divisor the inequality direction would flip). Since the rewrites are restricted to constant divisors,
 * positivity is a direct check on the constant's value; variable divisors are not rewritten.
 */
private fun Info.isPositiveConst(key: PatternRewriter.Key<LTACSymbol>): Boolean =
    when (val sym = this[key]!!.symbol) {
        is TACSymbol.Const -> sym.value > BigInteger.ZERO
        is TACSymbol.Var -> false
    }

/**
 * Like [isPositiveConst], but also accepts a variable divisor that [analysis.opt.intervals.IntervalsRewriter]
 * has proven to be strictly positive. Used by divEq, which (unlike the inequality patterns) may rewrite a
 * variable divisor when purify-division is enabled.
 */
private fun Info.isPositive(key: PatternRewriter.Key<LTACSymbol>): Boolean =
    when (val sym = this[key]!!.symbol) {
        is TACSymbol.Const -> sym.value > BigInteger.ZERO
        is TACSymbol.Var -> sym.isSurelyPos()
    }

/**
 * The floor-division comparison identities below are valid for [TACExpr.BinOp.IntDiv] only when the dividend is
 * non-negative, because [TACExpr.BinOp.IntDiv] rounds toward zero. This is automatically true for unsigned
 * [TACExpr.BinOp.Div] operands because [isSurelyNonNeg] returns true for [tac.Tag.Bits].
 */
private fun Info.hasNonNegativeDividend(key: PatternRewriter.Key<LTACSymbol>): Boolean =
    this[key]!!.symbol.isSurelyNonNeg()


/**
 * Patterns that should run after [analysis.opt.intervals.IntervalsRewriter] so that they can rely on the
 * non-zero-ness information it propagates via [NON_ZERO_META].
 *
 * Note that the 5 div rewrite patterns rely on [Config.Smt.UseBV] being false, because if we use a 256 bit
 * bitvector representation, the multiplication may overflow, making these patterns wrong.
 *
 * The four inequality patterns only fire for a non-negative dividend and positive constant divisor (see
 * [hasNonNegativeDividend] and [isPositiveConst]). Those conditions make the identities correct for both `Div`
 * and truncating `IntDiv`, while a constant divisor turns the resulting multiplication into the linear `const·C`.
 * For a variable divisor the rewrite merely trades one nonlinear form (`Div`) for another (`var·var`), and
 * empirically it seems to not help.
 */
fun PatternRewriter.postIntervalsRewriterPatternList() = listOfNotNull(

    /**
     * `A / B < C`  ~~>  `A < B·C`     (when A is non-negative and B is a positive constant)
     * Multiplication is in the integer domain so it can't overflow.
     */
    patternOnlyIf(
        cond = !Config.Smt.UseBV.get(),
        name = "divLt",
        pattern = {
            maybeNarrow(lSym(A) bothDivs lSym(B)) lt lSym(C)
        },
        handle = {
            runIf(info.hasNonNegativeDividend(A) && info.isPositiveConst(B)) {
                Lt(sym(A), IntMul(sym(B), sym(C)))
            }
        },
        TACExpr.BinRel.Lt::class.java
    ),

    /**
     * `A / B <= C`  ~~>  `A < B·(C+1)`     (when A is non-negative and B is a positive constant)
     */
    patternOnlyIf(
        cond = !Config.Smt.UseBV.get(),
        name = "divLe",
        pattern = {
            maybeNarrow(lSym(A) bothDivs lSym(B)) le lSym(C)
        },
        handle = {
            runIf(info.hasNonNegativeDividend(A) && info.isPositiveConst(B)) {
                Lt(sym(A), IntMul(sym(B), IntAdd(sym(C), 1.asTACExpr)))
            }
        },
        TACExpr.BinRel.Le::class.java
    ),

    /**
     * `A / B > C`  ~~>  `A >= B·(C+1)`     (when A is non-negative and B is a positive constant)
     */
    patternOnlyIf(
        cond = !Config.Smt.UseBV.get(),
        name = "divGt",
        pattern = {
            maybeNarrow(lSym(A) bothDivs lSym(B)) gt lSym(C)
        },
        handle = {
            runIf(info.hasNonNegativeDividend(A) && info.isPositiveConst(B)) {
                Ge(sym(A), IntMul(sym(B), IntAdd(sym(C), 1.asTACExpr)))
            }
        },
        TACExpr.BinRel.Gt::class.java
    ),

    /**
     * `A / B >= C`  ~~>  `A >= B·C`     (when A is non-negative and B is a positive constant)
     */
    patternOnlyIf(
        cond = !Config.Smt.UseBV.get(),
        name = "divGe",
        pattern = {
            maybeNarrow(lSym(A) bothDivs lSym(B)) ge lSym(C)
        },
        handle = {
            runIf(info.hasNonNegativeDividend(A) && info.isPositiveConst(B)) {
                Ge(sym(A), IntMul(sym(B), sym(C)))
            }
        },
        TACExpr.BinRel.Ge::class.java
    ),

    /**
     * `A / B == C`  ~~>  `B·C <= A < B·(C+1)`     (when A is non-negative and B is positive)
     *
     * Like the inequality patterns above, the bracketing only holds for a positive divisor, so this is gated on
     * [isPositive]. But unlike them it is additionally gated behind the (default-false) purify-division flags,
     * and it may fire for a variable divisor: with [Config.PurifyConstDivisions] for a positive constant divisor,
     * and with [Config.PurifyDivisions] also for a variable divisor proven positive.
     */
    patternOnlyIf(
        cond = !Config.Smt.UseBV.get() && (Config.PurifyDivisions.get() || Config.PurifyConstDivisions.get()),
        name = "divEq",
        pattern = {
            maybeNarrow(lSym(A) bothDivs lSym(B)) eq lSym(C)
        },
        handle = {
            runIf(info.hasNonNegativeDividend(A) && info.isPositive(B) &&
                ((Config.PurifyDivisions.get() && sym(B).isVar) ||
                (Config.PurifyConstDivisions.get() && sym(B).isConst))
            ) {
                LAnd(
                    Ge(sym(A), IntMul(sym(B), sym(C))),
                    Lt(sym(A), IntMul(sym(B), IntAdd(sym(C), 1.asTACExpr)))
                )
            }
        },
        TACExpr.BinRel.Eq::class.java
    ),

)
