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

package analysis.numeric

import datastructures.stdcollections.*
import utils.*
import vc.data.TACSymbol
import java.math.BigInteger

data class SimpleQualifiedInt(override val x: IntValue, override val qual: Set<SimpleIntQualifier>):
    BoundedQualifiedInt<SimpleQualifiedInt, SimpleIntQualifier, IntValue>, WithUIntApproximation<IntValue> {

    constructor(x: IntValue): this(x, setOf())

    fun meet(): SimpleQualifiedInt {
        return meet(x, qual)
    }

    fun join(o: SimpleQualifiedInt, widen: Boolean = false): SimpleQualifiedInt {
        val x = if (widen) { x.widen(o.x) } else { x.join(o.x) }
        val qs = qual.intersect(o.qual)
        return meet(x, qs)
    }

    override fun withBoundAndQualifiers(lb: BigInteger, ub: BigInteger, quals: Iterable<SimpleIntQualifier>): SimpleQualifiedInt {
        // This avoids throwing an exception if lb > ub, at the cost of
        // reduced precision
        return copy(
            x = x.withLowerBound(lb).withUpperBound(ub),
            qual = quals.toSet()
        )
    }

    override fun withQualifiers(x: Iterable<SimpleIntQualifier>): SimpleQualifiedInt {
        return SimpleQualifiedInt(
            x = this.x,
            qual = x.toSet(),
        )
    }

    private fun meet(x: IntValue, qual: Set<SimpleIntQualifier>): SimpleQualifiedInt {
        return SimpleQualifiedInt(
            x = upperBoundFromQuals(qual)?.let { x.withUpperBound(it) } ?: x,
            qual = qual
        )
    }

    private fun upperBoundFromQuals(qual: Collection<SimpleIntQualifier>, valuation: (TACSymbol.Var) -> BigInteger? = { _ -> null }): BigInteger? {
        fun extractValue(q: SimpleIntQualifier.ModularUpperBound): BigInteger? =
            when (val o = q.other) {
                is TACSymbol.Const -> o.value
                is TACSymbol.Var -> valuation(o)
            }?.letIf(q.strong) {
                it - BigInteger.ONE
            }
        return qual.filterIsInstance<SimpleIntQualifier.ModularUpperBound>()
            .mapNotNull(::extractValue)
            .minOrNull()
    }

    private fun upperBoundFromQuals(
        q: Collection<SimpleIntQualifier>,
        m1: Map<TACSymbol.Var, SimpleQualifiedInt>,
        m2: Map<TACSymbol.Var, SimpleQualifiedInt>,
        widen: Boolean,
    ): BigInteger? {
        return upperBoundFromQuals(q) { x ->
            m1[x]?.let { i1 ->
                m2[x]?.let { i2 ->
                    if (widen) {
                        i1.x.widen(i2.x).ub
                    } else {
                        i1.x.join(i2.x).ub
                    }
                }
            }
        }
    }

    fun joinOp(o: SimpleQualifiedInt, ours: Map<TACSymbol.Var, SimpleQualifiedInt>, theirs: Map<TACSymbol.Var, SimpleQualifiedInt>, widen: Boolean): SimpleQualifiedInt {
        val q = qual.intersect(o.qual)
        val newX = if (widen) { x.widen(o.x) } else { x.join(o.x) }
        val ub = upperBoundFromQuals(q, ours, theirs, widen) ?: newX.ub
        return SimpleQualifiedInt(newX.withUpperBound(ub), q)
    }

    companion object {
        val nondet = SimpleQualifiedInt(IntValue.Nondet, setOf())
        fun Constant(x: BigInteger) = SimpleQualifiedInt(IntValue.Constant(x), setOf())
    }
}
