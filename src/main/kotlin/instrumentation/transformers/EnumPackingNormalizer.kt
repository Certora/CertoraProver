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

package instrumentation.transformers

import analysis.*
import analysis.smtblaster.BitBlaster
import analysis.smtblaster.Z3BlasterPool
import parallel.ParallelPool
import tac.Tag
import utils.mapNotNull
import vc.data.*
import java.util.stream.Collectors

/**
 * Find instances where a packing-like pattern is used on a variable that is constrained to be within a small-ish
 * range (current less than 255). We introduce a (strictly speaking) redundant BWAnd operation, to help make the
 * storage splitter happy,
 */
object EnumPackingNormalizer {
    private val patt = PatternDSL.build {
        (Var or (Const * Var).commute.locSecond).commute.second
    }
    fun normalize(c: CoreTACProgram) : CoreTACProgram {
        val comp = PatternMatcher.compilePattern(
            c.analysisCache.graph,
            patt
        )
        val toRewrite = ParallelPool.allocInScope({ Z3BlasterPool() }) { pool ->
            c.parallelLtacStream().mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                    it.cmd.rhs is TACExpr.BinOp.BWOr
                }
            }.mapNotNull {
                comp.queryFrom(it).toNullableResult()
            }.filter { (lc, sym) ->
                c.analysisCache.graph.iterateUntil(lc.ptr).all {
                    it.cmd !is TACCmd.Simple.AssigningCmd || it.cmd.lhs != sym
                } && lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lc.cmd.rhs is TACExpr.Vec.Mul
            }.filter { mult ->
                val curr = mult.first.ptr.block
                val g = c.analysisCache.graph
                val pred = g.pred(curr).singleOrNull() ?: return@filter false
                val pc = g.pathConditionsOf(pred).get(curr) ?: return@filter false
                val predBlock = g.elab(pred)
                BitBlaster.blastCode(
                    block = predBlock,
                    start = predBlock.commands.findLast {
                        it.cmd is TACCmd.Simple.WordStore
                    }?.ptr?.pos?.plus(1) ?: 0, /* start *after* the last wordstore, as we don't support it in bit blasting */
                    end = predBlock.commands.lastIndex,
                    env = mapOf(mult.second to "ENUM"),
                    blaster = pool,
                    synthAssign = mapOf()
                ) { env ->
                    val exp = when (pc) {
                        is PathCondition.EqZero -> {
                            eq(
                                const(0),
                                toIdent(env[pc.v] ?: return@blastCode false)
                            )
                        }
                        is PathCondition.NonZero -> {
                            lnot(
                                eq(
                                    const(0),
                                    toIdent(env[pc.v] ?: return@blastCode false)
                                )
                            )
                        }
                        is PathCondition.Summary,
                        PathCondition.TRUE -> return@blastCode false
                    }
                    val tgt = env[mult.second] ?: return@blastCode false
                    assert {
                        land(
                            exp,
                            gt(toIdent(tgt), const(255))
                        )
                    }
                    true
                }
            }.collect(Collectors.toList())
        }
        if(toRewrite.isEmpty()) {
            return c
        }
        return c.patching {
            toRewrite.forEach { (where, sym) ->
                val tmp = TACKeyword.TMP(Tag.Bit256, "!constr").toUnique()
                it.addVarDecl(tmp)
                val e = where.narrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
                val subbed = VarSubstitutor(mapOf(sym to tmp.asSym())).mapExpr(
                    e.cmd.rhs
                )
                it.replaceCommand(where.ptr, listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = tmp,
                        rhs = TACExpr.BinOp.BWAnd(
                            sym.asSym(),
                            0xff.toBigInteger().asTACSymbol().asSym()
                        )
                    ),
                    e.cmd.copy(
                        rhs = subbed
                    )
                ))
            }
        }
    }
}
