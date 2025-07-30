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

package analysis.numeric.linear

import com.certora.collect.*
import datastructures.stdcollections.*
import utils.*
import java.math.BigInteger

/**
 * A linear term, representing the canonical construction r1 * v1 + r2 * v2 + ... rn * vn + k
 *
 * [E] is the type of the variables in the linear term, i.e., v1, v2, ...
 */
data class ReducedLinearTerm<@Treapable E> private constructor(
    val literals: TreapMap<E, BigInteger>,
    val c: BigInteger = BigInteger.ZERO
) {
    constructor(v: E) : this(treapMapOf(v to BigInteger.ONE))
    constructor(c: BigInteger) : this(treapMapOf(), c)
    constructor(c: Int) : this(treapMapOf(), c.toBigInteger())

    init {
        check(literals.values.all { it != BigInteger.ZERO })
    }

    val isConst get() = literals.isEmpty()
    val asConstOrNull get() = c.takeIf { isConst }
    val asConst get() = asConstOrNull!!
    val isVar get() = c == BigInteger.ZERO && literals.entries.singleOrNull()?.value == BigInteger.ONE
    val asVarOrNull get() = runIf(isVar) { literals.entries.single().key }

    val support get() = literals.keys

    private inline fun map(f: (BigInteger) -> BigInteger) =
        ReducedLinearTerm(
            literals.mapValues { f(it.value) }.removeAllValues { it == BigInteger.ZERO },
            f(c)
        )

    operator fun plus(other: ReducedLinearTerm<E>) =
        ReducedLinearTerm(
            literals.merge(other.literals) { _, a, b ->
                when {
                    a == null -> b
                    b == null -> a
                    a == -b -> null
                    else -> a + b
                }
            },
            c + other.c
        )

    operator fun plus(other: E) = this + ReducedLinearTerm(other)
    operator fun plus(other: BigInteger) = this + ReducedLinearTerm(other)
    operator fun plus(other: Int) = this + ReducedLinearTerm(other)

    operator fun unaryMinus() = map { -it }

    operator fun minus(other: ReducedLinearTerm<E>) = this + (-other)
    operator fun minus(other: E) = this - ReducedLinearTerm(other)
    operator fun minus(other: BigInteger) = this - ReducedLinearTerm(other)
    operator fun minus(other: Int) = this - ReducedLinearTerm(other)

    operator fun times(other: BigInteger) =
        if (other == BigInteger.ZERO) {
            ReducedLinearTerm(BigInteger.ZERO)
        } else {
            map { it * other }
        }

    /** resulting coefficients and constant will be non-negative */
    fun mod(other: BigInteger) =
        map { it.mod(other) }

    override fun toString(): String {
        if (isConst) {
            return c.toString()
        }
        return literals.entries.map { (v, coef) ->
            if (coef != BigInteger.ONE) {
                "$coef * $v"
            } else {
                "$v"
            }
        }.letIf(c != BigInteger.ZERO) {
            it + c.toString()
        }.joinToString(" + ")
    }

    fun stripConstant() = ReducedLinearTerm(literals)
}
