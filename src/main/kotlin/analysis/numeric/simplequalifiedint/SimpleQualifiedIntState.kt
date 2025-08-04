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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.numeric.simplequalifiedint

import analysis.numeric.IntValue
import com.certora.collect.TreapMap
import datastructures.stdcollections.setOf
import vc.data.TACExpr
import vc.data.TACSymbol

data class SimpleQualifiedIntState(val s: TreapMap<TACSymbol.Var, SimpleQualifiedInt>) {
    fun interpret(e: TACExpr): SimpleQualifiedInt? =
        (e as? TACExpr.Sym)?.let {
            interpret(it.s)
        }

    fun interpret(o: TACSymbol): SimpleQualifiedInt =
        when (o) {
            is TACSymbol.Const -> SimpleQualifiedInt(IntValue.Companion.Constant(o.value), setOf())
            is TACSymbol.Var -> s[o] ?: SimpleQualifiedInt.nondet
        }

    fun join(other: SimpleQualifiedIntState, widen: Boolean = false): SimpleQualifiedIntState = this.s.parallelIntersect(other.s) { _, v1, v2 ->
        v1.join(v2, widen)
    }.let(::SimpleQualifiedIntState)
}
