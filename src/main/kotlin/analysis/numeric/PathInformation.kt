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

import vc.data.TACSymbol
import java.math.BigInteger

sealed class PathInformation<out Q> {
    interface NumericRelation {
        val sym: TACSymbol.Var?
        val num: BigInteger?
    }
    sealed interface UpperBound : NumericRelation
    sealed interface LowerBound : NumericRelation

    data class StrictUpperBound(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), UpperBound {
        constructor(n: BigInteger) : this(sym = null, num = n)
        init {
            assert(sym != null || num != null)
        }
    }

    data class StrictLowerBound(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), LowerBound {
        constructor(n: BigInteger) : this(sym = null, num = n)
        init {
            assert(sym != null || num != null)
        }
    }

    data class WeakLowerBound(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), LowerBound {
        constructor(n: BigInteger) : this(sym = null, num = n)
        init {
            assert(sym != null || num != null)
        }
    }

    data class WeakUpperBound(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), UpperBound {
        constructor(n: BigInteger) : this(sym = null, num = n)
        init {
            assert(sym != null || num != null)
        }
    }

    data class  Qual<Q>(val q: Q) : PathInformation<Q>()

    data class StrictEquality(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), NumericRelation

    data class StrictInequality(override val sym: TACSymbol.Var?, override val num: BigInteger?) : PathInformation<Nothing>(), NumericRelation

    data class WeakSignedLowerBound(
        val sym: TACSymbol.Var
    ) : PathInformation<Nothing>()

    data class StrictSignedLowerBound(
        val sym: TACSymbol.Var
    ) : PathInformation<Nothing>()

    data class StrictSignedUpperBound(
        val sym: TACSymbol.Var
    ) : PathInformation<Nothing>()

    data class WeakSignedUpperBound(
        val sym: TACSymbol.Var
    ) : PathInformation<Nothing>()

    companion object {
        /*
         To support PathInformation::StrictUpperBound et. al (the type parameter means we cannot use
          that syntax directly)
         */
        fun StrictUpperBound(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.StrictUpperBound(sym, num)
        fun StrictLowerBound(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.StrictLowerBound(sym, num)
        fun WeakLowerBound(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.WeakLowerBound(sym, num)
        fun WeakUpperBound(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.WeakUpperBound(sym, num)
        fun StrictEquality(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.StrictEquality(sym, num)
        fun StrictInequality(sym: TACSymbol.Var?, num: BigInteger?) = PathInformation.StrictInequality(sym, num)
    }
}
