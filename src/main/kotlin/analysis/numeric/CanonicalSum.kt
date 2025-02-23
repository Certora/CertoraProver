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

import com.certora.collect.*
import utils.*
import vc.data.TACSymbol
import java.math.BigInteger

/**
 * A canonical representation for a sum of (potentially duplicated) tacsymbols. It is expected that the
 * value of this sum is always positive
 */
@Treapable
class CanonicalSum private constructor(val ops: List<TACSymbol.Var>, val c: BigInteger) {
    constructor(ops: List<TACSymbol>) : this(
        ops.filterIsInstance<TACSymbol.Var>().sorted(),
        ops.asSequence().filterIsInstance<TACSymbol.Const>().map { it.value }.fold(BigInteger.ZERO, BigInteger::add)
    )

    constructor(op: TACSymbol) : this(listOf(op))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanonicalSum) return false

        if (ops != other.ops) return false
        if(c != other.c) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ops.hashCode()
        result = 31 * result + c.hashCode()
        return result
    }

    operator fun plus(x: TACSymbol) = when(x) {
        is TACSymbol.Const -> CanonicalSum(ops, this.c + x.value)
        is TACSymbol.Var -> CanonicalSum((this.ops + x).sorted(), this.c)
    }

    /**
     * Returns the [CanonicalSum] representing the value of this [CanonicalSum] after subtracting [x].
     * It is possible that no representation exists (i.e., we cannot prove the result of subtracting [x] will be positive)
     * in which case this function returns null.
     */
    operator fun minus(x: TACSymbol) : CanonicalSum? {
        return when (x) {
            is TACSymbol.Var -> {
                if (this.ops.size == 1 && this.ops.first() == x) {
                    if (this.c == BigInteger.ZERO) {
                        null
                    } else {
                        CanonicalSum(listOf(), this.c)
                    }
                } else {
                    val new = mutableListOf<TACSymbol.Var>()
                    var found = false
                    for (v in this.ops) {
                        if (v == x) {
                            found = true
                            continue
                        }
                        new.add(v)
                    }
                    if (!found) {
                        return null
                    }
                    check(new.size > 1 || (new.isEmpty() && this.ops.isEmpty()))
                    CanonicalSum(new, this.c)
                }
            }
            is TACSymbol.Const -> {
                if(this.c < x.value) {
                    return null
                }
                CanonicalSum(this.ops, this.c - x.value)
            }
        }
    }

    fun providesStrongBound(v: TACSymbol) = when(v) {
        is TACSymbol.Const -> this.c > v.value
        is TACSymbol.Var -> false
    }

    override fun toString(): String {
        return "\u03A3 [${(this.ops + this.c).joinToString(", ")}]"
    }

    operator fun contains(lhs: TACSymbol.Var): Boolean {
        return this.ops.contains(lhs)
    }
}
