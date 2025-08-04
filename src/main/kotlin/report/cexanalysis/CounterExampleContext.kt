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

package report.cexanalysis

import solver.SMTCounterexampleModel
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.postFold
import java.math.BigInteger


/**
 * Some utilities for working with a give counter example.
 */
class CounterExampleContext(
    code: CoreTACProgram,
    private val cex: SMTCounterexampleModel,
) {
    val g = code.analysisCache.graph
    val model = cex.tacAssignments

    /** The value of [s] in [cex], but only for primitive tags */
    operator fun invoke(s: TACSymbol): BigInteger? =
        runIf(s.tag.isPrimitive()) {
            when (s) {
                is TACSymbol.Var -> model[s]?.asBigIntOrNull()
                is TACSymbol.Const -> s.value
            }
        }

    /** evaluates the value of [exp] in [cex], but only if [exp] is made up of primitive values */
    operator fun invoke(exp: TACExpr): BigInteger? =
        exp.postFold { e, opValues ->
            runIf(e.tag?.isPrimitive() == true && null !in opValues) {
                when (e) {
                    is TACExpr.Sym -> invoke(e.s)
                    else -> e.safeEval(opValues.map { it!! })
                }
            }
        }

    fun isFailedAssert(cmd: TACCmd.Simple) =
        cmd is TACCmd.Simple.AssertCmd && invoke(cmd.o) == BigInteger.ZERO

    /** The blocks in the counter example, in order, upto the first failed assertion */
    val pathBlocksList = code.topoSortFw
        .filter { it in cex.reachableNBIds }
        .takeUntil { g.lcmdSequence(it).any { isFailedAssert(it.cmd) } }

    /** The failed assert of [pathBlocksList] */
    val lastPtr = pathBlocksList?.last()?.let { b ->
        g.elab(b).commands.first { isFailedAssert(it.cmd) }.ptr
    }

    /** The counter example's commands in order */
    fun cexCmdSequence() = g.lcmdSequence(pathBlocksList!!).takeWhile { (ptr, _) ->
        ptr.block != lastPtr!!.block || ptr.pos <= lastPtr.pos
    }
}
