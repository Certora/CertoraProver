/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import analysis.LTACSymbol
import analysis.opt.PatternRewriter
import analysis.opt.PatternRewriter.Key
import analysis.opt.PatternRewriter.Key.*
import analysis.PatternMatcher
import analysis.patterns.get
import analysis.patterns.Info
import analysis.patterns.Info.Companion.set
import analysis.patterns.InfoKey
import datastructures.stdcollections.*
import java.math.BigInteger
import log.*
import sbf.cfg.CondOp
import tac.Tag
import vc.data.*
import wasm.analysis.intervals.IntervalAnalysis

private val logger = Logger(LoggerTypes.SBF_MATH_PROMOTION)
private fun dbg(msg: () -> Any) {
    logger.debug(msg)
}

/**
 * Detects u128 operations that have been split into u64 chunks by SBF compilation and inserts
 * equivalent 128-bit computations.
 */
object TACU128MathPromoter {

    fun insertU128Operations(prog: CoreTACProgram): CoreTACProgram {
        dbg { "Detecting U128 operations..." }
        return insertU128BinRelOperations(prog)
    }

    /** Returns the TAC expression `high << 64 + low` (merges two u64 halves into one 128-bit value).
     * We use this with operands that are checked to be in u64 bounds, so the shift cannot overflow. */
    private fun mergeU128Expr(low: TACExpr.Sym, high: TACExpr.Sym): TACExpr {
        val c64 = TACSymbol.Const(BigInteger.valueOf(64), Tag.Bit256).asSym()
        return TACExpr.Vec.Add(TACExpr.BinOp.ShiftLeft(high, c64), low, Tag.Bit256)
    }

    /**
     * Detects the compact 3-Ite pattern encoding a u128 binary relational comparison:
     * ```
     * L = Ite(C_lo, 1, 0)        ← lo-halves comparison: 1 if lo(x) OP lo(y), else 0
     * H = Ite(C_hi, 1, 0)        ← hi-halves comparison: 1 if hi(x) OP hi(y), else 0
     * R = Ite(C_eq, L, H)        ← mux: use lo result when hi halves equal, hi result otherwise
     *                                    (C_eq = hi(x) == hi(y))
     * ```
     * The two half-comparison Ites can appear in either order. Post-DSA, each variable has a
     * unique definition so consistency is checked via PatternRewriter's src() equivalence.
     */
    private fun insertU128BinRelOperations(prog: CoreTACProgram): CoreTACProgram {
        val intervals = prog.analysisCache[IntervalAnalysis]
        val bigPow64Minus1: BigInteger = (BigInteger.ONE shl 64) - BigInteger.ONE
        return PatternRewriter.rewrite(prog, {
            listOf(PatternHandler(
                "u128binrel",
                pattern = {
                    /** Key mapping: A/B = outer Eq (hi-left / hi-right), C/D = lo comparison, E/F = hi comparison.
                     * `andDo` stores the matched operator in the Info; `Info.joinWith` enforces that the
                     * lo and hi Ites use the same operator by treating a key conflict as a match failure.
                     */
                    fun opIte(left: PatternMatcher.Pattern<Info>, right: PatternMatcher.Pattern<Info>) =
                        ite(left gt right, c(1), c(0)).andDo { set(CondOpKey, CondOp.GT) } OR
                        ite(left ge right, c(1), c(0)).andDo { set(CondOpKey, CondOp.GE) } OR
                        ite(left lt right, c(1), c(0)).andDo { set(CondOpKey, CondOp.LT) } OR
                        ite(left le right, c(1), c(0)).andDo { set(CondOpKey, CondOp.LE) }
                    ite(lSym(A) eq lSym(B), opIte(lSym(C), lSym(D)), opIte(lSym(E), lSym(F)))
                },
                handle = {
                    fun bounded(k: Key<LTACSymbol>): Boolean {
                        val qi = intervals.inState(ptr)?.interpret(info[k]!!.symbol) ?: return false
                        return qi.x.lb >= BigInteger.ZERO && qi.x.ub <= bigPow64Minus1
                    }
                    val directMatch  = src(A) == src(E) && src(B) == src(F)
                    val swappedMatch = src(A) == src(F) && src(B) == src(E)
                    if (!directMatch && !swappedMatch) { return@PatternHandler null }
                    if (!bounded(C) || !bounded(D) ||
                        !bounded(E) || !bounded(F)) { return@PatternHandler null }
                    val lo = mergeU128Expr(sym(C), sym(E))
                    val ro = mergeU128Expr(sym(D), sym(F))
                    ite(
                        when (info[CondOpKey]!!) {
                            CondOp.GT -> TACExpr.BinRel.Gt(lo, ro)
                            CondOp.GE -> TACExpr.BinRel.Ge(lo, ro)
                            CondOp.LT -> TACExpr.BinRel.Lt(lo, ro)
                            CondOp.LE -> TACExpr.BinRel.Le(lo, ro)
                            else      -> return@PatternHandler null
                        },
                        1.asTACExpr,
                        0.asTACExpr
                    )
                },
                TACExpr.TernaryExp.Ite::class.java,
                regressionMessage = true
            ))
        })
    }

    private object CondOpKey : InfoKey<CondOp>()
}
