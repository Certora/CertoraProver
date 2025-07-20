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

package normalizer

import analysis.*
import analysis.PatternMatcher.Pattern.Companion.toBuildable
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.Tag
import utils.mapNotNull
import vc.data.*
import vc.data.tacexprutil.TACExprFactBasicSimp
import java.math.BigInteger

/**
 * Rewrites the truly cursed pattern of:
 *
 * y = x - c
 * b = y > 0
 * if b goto L else L2
 *
 * into the equivalent (and much easier to understand):
 *
 * b = x == c
 * if b goto L2 else L
 *
 * It also handles the case of y = c - x
 * As the rewrite is equivalent
 */
object EqualityCheckNormalizer {
    private data class Rewrite(
        val jumpI: LTACCmdView<TACCmd.Simple.JumpiCmd>,
        val target: LTACSymbol,
        val k: BigInteger,
        val subLocation: LTACCmd,
    )

    private val pattern = PatternDSL.build {
        val locatedSymbol = PatternMatcher.Pattern.AnySymbol<LTACSymbol>(
            out = { where, sym ->
                LTACSymbol(where.ptr, sym)
            }
        )
        ((locatedSymbol.toBuildable() - Const).withAction { subLoc, op, const ->
            Triple(subLoc, op, const)
        } lor (Const - locatedSymbol.toBuildable()).withAction { subLoc, const, op ->
            Triple(subLoc, op, const)
        } gt Const(BigInteger.ZERO)).withAction { r, _ -> r }
    }

    fun rewrite(c: CoreTACProgram) : CoreTACProgram {
        val matcher = PatternMatcher.compilePattern(graph = c.analysisCache.graph, patt = pattern)
        val rewrites = c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.JumpiCmd>()
        }.mapNotNull {
            (it.cmd.cond as? TACSymbol.Var)?.let { v ->
                matcher.query(v, it.wrapped).toNullableResult()
            }?.let { (subLoc, operand, const) ->
                Rewrite(
                    jumpI = it,
                    target = operand,
                    k = const,
                    subLocation = subLoc
                )
            }
        }.toList()
        if(rewrites.isEmpty()) {
            return c
        }
        return c.patching {patcher ->
            rewrites.forEach { (jump, target, k) ->
                val jumpVar = TACKeyword.TMP(Tag.Bool).toUnique("!")
                patcher.addVarDecl(jumpVar)
                patcher.replace(target.ptr) { orig ->
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs =  jumpVar,
                            rhs = TACExprFactBasicSimp.Eq(
                                target.symbol.asSym(),
                                k.asTACSymbol().asSym(),
                                Tag.Bool
                            )
                        ),
                        orig
                    )
                }
                patcher.replaceCommand(jump.ptr, listOf(
                    TACCmd.Simple.JumpiCmd(
                        cond = jumpVar,
                        elseDst = jump.cmd.dst,
                        dst = jump.cmd.elseDst,
                        meta = jump.cmd.meta
                    )), treapSetOf(jump.cmd.dst, jump.cmd.elseDst)
                )
            }
        }
    }
}
