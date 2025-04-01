/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.controlflow

import datastructures.stdcollections.*
import spec.CVLKeywords
import utils.*
import vc.data.*
import vc.data.tacexprutil.asConstOrNull
import java.math.BigInteger

/**
 * Returns true iff there is a path in [tac] from a root to a sink that does not go through any C where
 * C is either "lastReverted true", "assume false", or "loop condition assert false" command.
 * NB: this is just a static check on the graph level, i.e. we are not checking if there is a "feasible"
 * non-reverting path.
 */
fun checkIfAllPathsAreLastReverted(tac: CoreTACProgram): Boolean {
    fun isReverting(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.lhs?.meta?.get(TACSymbol.Var.KEYWORD_ENTRY)?.name == CVLKeywords.lastReverted.keyword &&
            (cmd.rhs as? TACExpr.Sym)?.getAsConst() == BigInteger.ONE

    fun isLoopAssertFalse(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.AssertCmd)?.let {
            TACMeta.SYNTHETIC_LOOP_END in it.meta && (it.o.asConstOrNull == BigInteger.ZERO)
        } ?: false

    fun isAssumeFalse(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.Assume)?.condExpr?.getAsConst() == BigInteger.ZERO

    val visited = tac.analysisCache.graph.roots.map { it.ptr }.toMutableSet()
    val queue = arrayDequeOf(visited)
    queue.consume { ptr ->
        val cmd = tac.analysisCache.graph.elab(ptr).cmd
        if (!isReverting(cmd) && !isLoopAssertFalse(cmd) && !isAssumeFalse(cmd)) {
            val succ = tac.analysisCache.graph.succ(ptr)
            if (succ.isEmpty()) { //sink
                return false
            } else {
                succ.forEach {
                    if (visited.add(it)) {
                        queue += it
                    }
                }
            }
        }
    }
    return true;
}
