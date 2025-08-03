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

package wasm.analysis.intervals

import analysis.*
import analysis.split.Ternary.Companion.isPowOf2Minus1
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import java.math.BigInteger
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
/**
 * Given an analysis that can bound the value of tac symbols,
 * try to replace:
 *   - AddMod(x, y, n) ~> x + y, when x + y must be less than n (without overflowing)
 *   - MulMod(x, y, n) ~> x * y, when x * y ..
 *   - x % n ~> x, when x < n
 *   - x & m ~> x, when x <= m and m is a power of 2 - 1
 */
object IntervalBasedExprSimplifier {
    fun analyze(core: CoreTACProgram): CoreTACProgram {
        val analysis = core.analysisCache[IntervalAnalysis]
        val graph = core.analysisCache.graph

        return analysis.parallelStreamStates().mapNotNull { (where, state) ->
            graph.elab(where).maybeExpr<TACExpr>()?.let { assign ->
                assign.ptr `to?` tryRewriteExpr(assign.exp, state)?.let { rewrite ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(assign.lhs, rewrite)
                }
            }
        }.patchForEach(core) { (where, replacement) ->
            update(where, replacement)
        }
    }

    private fun tryRewriteExpr(e: TACExpr, state: SimpleQualifiedIntState): TACExpr? {
        return when (e) {
            is TACExpr.TernaryExp.AddMod -> {
                val a = state.interpret(e.a) ?: return null
                val b = state.interpret(e.b) ?: return null
                val n = state.interpret(e.n) ?: return null

                if (a.x.ub + b.x.ub >= n.x.lb) {
                    return null
                }

                if (a.x.mustEqual(BigInteger.ZERO)) {
                    e.b
                } else if (b.x.mustEqual(BigInteger.ZERO)) {
                    e.a
                } else {
                    TACExpr.Vec.Add(e.a, e.b)
                }
            }

            is TACExpr.TernaryExp.MulMod -> {
                val a = state.interpret(e.a) ?: return null
                val b = state.interpret(e.b) ?: return null
                val n = state.interpret(e.n) ?: return null

                if (a.x.ub * b.x.ub >= n.x.lb) {
                    return null
                }

                if (a.x.mustEqual(BigInteger.ONE)) {
                    e.b
                } else if (b.x.mustEqual(BigInteger.ONE)) {
                    e.a
                } else {
                    TACExpr.Vec.Mul(e.a, e.b)
                }
            }

            is TACExpr.BinOp.Mod -> {
                val x = state.interpret(e.o1) ?: return null
                val y = state.interpret(e.o2) ?: return null

                if (x.x.ub >= y.x.lb) {
                    return null
                }

                e.o1
            }

            is TACExpr.BinOp.BWAnd -> {
                val x = state.interpret(e.o1) ?: return null
                val y = state.interpret(e.o2) ?: return null

                if (!y.x.isConstant || !y.x.c.isPowOf2Minus1 || x.x.ub >= y.x.c) {
                    return null
                }

                e.o1
            }

            else ->
                null
        }
    }

}
