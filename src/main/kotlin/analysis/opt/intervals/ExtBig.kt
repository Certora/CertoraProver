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

package analysis.opt.intervals

import analysis.numeric.MAX_UINT
import analysis.split.Ternary.Companion.isPowOf2
import analysis.split.Ternary.Companion.isPowOf2Minus1
import com.certora.collect.*
import evm.*
import utils.ModZm
import utils.isInt
import java.math.BigInteger

/**
 * A [BigInteger] with with plus-infinity and minus-infinity.
 */
@Treapable
sealed interface ExtBig : Comparable<ExtBig> {
    val n: BigInteger

    @Treapable
    object Inf : ExtBig {
        override val n get() = error("cannot access concrete value in Infinity")
        override fun toString() = "Inf"
        override fun hashCode(): Int = utils.hashObject(this)
    }

    @Treapable
    object MInf : ExtBig {
        override val n get() = error("cannot access concrete value in -Infinity")
        override fun toString() = "MInf"
        override fun hashCode(): Int = utils.hashObject(this)
    }

    @Treapable
    @JvmInline
    value class Number(override val n: BigInteger) : ExtBig {
        override fun toString(): String = when {
            n in (-256).toBigInteger()..256.toBigInteger() -> n.toString()
            n < BigInteger.ZERO -> "-${(-n)}"
            n.isPowOf2 -> "2^${n.bitLength() - 1}"
            n.isPowOf2Minus1 -> "2^${n.bitLength()}-1"
            else -> "0x${n.toString(16)}"
        }
    }

    fun nOrNull() = when (this) {
        Inf -> null
        MInf -> null
        is Number -> n
    }

    override fun compareTo(other: ExtBig) =
        when {
            this == other -> 0
            this == Inf || other == MInf -> 1
            this == MInf || other == Inf -> -1
            else -> this.n.compareTo(other.n)
        }


    companion object {
        operator fun invoke(n: BigInteger) = Number(n)
        operator fun invoke(n: Int) = Number(n.toBigInteger())

        /** [positive] ? Inf : -Inf */
        fun inf(positive: Boolean) = when (positive) {
            true -> Inf
            false -> MInf
        }

        val BigInteger.asExtBig get() = ExtBig(this)
        val Int.asExtBig get() = ExtBig(this)

        fun ExtBig.max(other: ExtBig) = maxOf(this, other)
        fun ExtBig.min(other: ExtBig) = minOf(this, other)

        val Zero = 0.asExtBig
        val One = 1.asExtBig
        val TwoTo256 = EVM_MOD_GROUP256.asExtBig
        val TwoTo512 = TWO_TO_512.asExtBig
        val MaxUInt512 = MAX_EVM_UINT256.asExtBig
        val MaxUInt = MAX_UINT.asExtBig
        val Int256min2s = MIN_EVM_INT256_2S_COMPLEMENT.asExtBig
        val Int256minMath = MIN_EVM_INT256_AS_MATH_INT.asExtBig
        val Int256max = MAX_EVM_INT256.asExtBig
    }

    operator fun unaryMinus(): ExtBig =
        when (this) {
            Inf -> MInf
            MInf -> Inf
            is Number -> Number(-n)
        }

    /** runs [f] on `this` and [other], smaller one first */
    private inline fun <T> order(other: ExtBig, f: (ExtBig, ExtBig) -> T) =
        if (this <= other) {
            f(this, other)
        } else {
            f(other, this)
        }

    /** null means the result can be any value (happens for [Inf] + [MInf]) */
    operator fun plus(other: ExtBig): ExtBig? =
        order(other) { i, j ->
            when {
                i == Inf && j == Inf -> Inf
                i == MInf && j == MInf -> MInf
                i == MInf && j == Inf -> null
                i == MInf -> MInf
                j == Inf -> Inf
                else -> ExtBig(i.n + j.n)
            }
        }

    // just a trick to get rid of the null in `Number` cases.
    operator fun plus(other: Number): ExtBig =
        (this + (other as ExtBig))!!

    operator fun plus(other: BigInteger) = this + other.asExtBig

    operator fun minus(other: ExtBig) = this + (-other)

    operator fun minus(other: Number) = (this + (-other))!!

    operator fun minus(other: BigInteger) = this + (-other)

    operator fun plus(other: Int) = this + ExtBig(other)

    operator fun minus(other: Int) = this - ExtBig(other)


    operator fun times(other: ExtBig) =
        order(other) { i, j ->
            when {
                i == Zero || j == Zero -> Zero
                i == Inf && j == Inf -> Inf
                i == MInf && j == MInf -> Inf
                i == MInf && j == Inf -> MInf
                i == MInf -> inf(j.n < BigInteger.ZERO)
                j == Inf -> inf(i.n > BigInteger.ZERO)
                else -> ExtBig(i.n * j.n)
            }
        }

    /** The result of dividing two [ExtBig]s */
    sealed interface DivResult {
        /** includes zero */
        object Positive : DivResult

        /** includes zero */
        object Negative : DivResult

        object DivByZero : DivResult

        @JvmInline
        value class Value(val n: ExtBig) : DivResult

        val value
            get() = (this as? Value)?.n
                ?: error("DivResult $this has no actual value")

    }

    operator fun div(other: ExtBig): DivResult =
        when {
            other == Zero -> DivResult.DivByZero
            this == Inf && other == Inf -> DivResult.Positive
            this == MInf && other == MInf -> DivResult.Positive
            this == Inf && other == MInf -> DivResult.Negative
            this == MInf && other == Inf -> DivResult.Negative
            this == Inf -> DivResult.Value(inf(other.n > BigInteger.ZERO))
            this == MInf -> DivResult.Value(inf(other.n < BigInteger.ZERO))
            other == Inf || other == MInf -> DivResult.Value(Zero)
            else -> DivResult.Value(ExtBig(this.n / other.n))
        }

    fun abs(): ExtBig = when (this) {
        is Inf -> Inf
        is MInf -> Inf
        is Number -> n.abs().asExtBig
    }

    sealed class PowResult {
        data object SurelyAbove2To512 : PowResult()
        data object Undefined : PowResult()
        data class Value(val value: ExtBig) : PowResult()
    }

    /**
     * The complex return value is because we don't want to explicitly handle numbers which are too big,
     * and just returning [Inf] for such cases is not exact, and can cause problems.
     */
    infix fun pow(other: ExtBig): PowResult =
        when {
            this < Zero || other < Zero -> PowResult.Undefined
            this == Zero && other == Zero -> PowResult.Undefined
            this == Zero -> PowResult.Value(Zero)
            this == One -> PowResult.Value(One)
            other == One -> PowResult.Value(this)
            this == Inf || other == Inf -> PowResult.Value(Inf)
            other.n.isInt() && other.n.toInt() <= 512 -> {
                // x^y > 2^512 <=> log2(x) * y > 512
                // bitlength(2) = bitlength(3) = 2, and always (bitlength(x) - 1) <= log2(x). Therefore if this
                // check passes, it means the number is surely above 2^512.
                if ((n.bitLength() - 1) * other.n.toInt() > 512) {
                    PowResult.SurelyAbove2To512
                } else {
                    PowResult.Value(n.pow(other.n.toInt()).asExtBig)
                }
            }

            else -> PowResult.SurelyAbove2To512
        }

    /** rounded down. null means undefined */
    fun log2(): ExtBig? =
        when {
            this <= Zero -> null
            this == Inf -> Inf
            else -> n.bitLength().asExtBig - 1
        }

    // if power is larger than [modZm]'s bitwidth, we consider it as that.
    fun pow2limited(modZm: ModZm): ExtBig? =
        when {
            this < Zero -> null
            this > modZm.bitwidth.asExtBig -> modZm.modulus.asExtBig
            else -> twoToThe(n.toInt()).asExtBig
        }

    fun is2sNeg(modZm: ModZm) = this >= modZm.minSigned2s.asExtBig

    fun is2sNonNeg(modZm : ModZm) = this <= modZm.maxSigned.asExtBig
}
