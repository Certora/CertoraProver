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

package analysis.split

import analysis.split.Ternary.Companion.allXs
import evm.EVM_BITWIDTH256
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_UINT256
import utils.*
import utils.ModZm.Companion.lowOnes
import java.math.BigInteger

/**
 * Represents a bitvector of size [EVM_BITWIDTH256], with 0's, 1's and unknown bits (X).
 *
 * For efficiency, it has two subclasses: one for holding constant values and one for non-constants.
 *
 * It also keeps a sign-extend, which says what is the rightmost bit after which all bits are surely the same.
 */
sealed class Ternary {

    /**
     * We know nothing. This is for math_ints, where the number may be negative, or with more than 256 bits.
     * Specifically [Bottom] is different from [allXs] in that [allXs] is a 256-bit vector where we don't know the
     * particular values of the bits, but [Bottom] can be just anything.
     * */
    object Bottom : Ternary() {
        override fun toString() = "Bottom"
    }

    sealed class NonBottom : Ternary() {
        /**
         * The [BigInteger] is thought of as a bit vector of length [EVM_BITWIDTH256], where the bits where we know there
         * is a zero are the bits where it is on
         */
        abstract val zeros: BigInteger

        /** Bits where we know there is a 1 */
        abstract val ones: BigInteger

        /** ones + zeros */
        abstract val constants: BigInteger

        /** The rest of the bits */
        abstract val unknowns: BigInteger

        /**
         * The rightmost bit after which all bits are surely the same. the value kept is +1 to this bit's index. So for
         * example if we have a variable of type int32, then [signExtendBit] is at most 32 (the sign bit is at index 31).
         */
        abstract val signExtendBit: Int
    }

    companion object {
        val zero: Ternary = Constant(BigInteger.ZERO)
        val one: Ternary = Constant(BigInteger.ONE)

        /** All x's */
        val allXs: Ternary = NonConstant(BigInteger.ZERO, BigInteger.ZERO)

        /** All zeros except first bit which is x */
        val boolX: Ternary = NonConstant(allOnes - BigInteger.ONE, BigInteger.ZERO)

        operator fun invoke(value: BigInteger): Ternary =
            if (value >= BigInteger.ZERO && value < EVM_MOD_GROUP256) {
                Constant(value)
            } else {
                Bottom
            }

        operator fun invoke(value: Int): Ternary =
            invoke(value.toBigInteger())

        operator fun invoke(value: Boolean): Ternary =
            if (value) one else zero


        /** Creates a [Constant] if it can */
        operator fun invoke(zeros: BigInteger, ones: BigInteger, _signExtendBit: Int? = null) =
            if (zeros or ones == allOnes) {
                check(zeros disjointFrom ones)
                invoke(ones)
            } else {
                NonConstant(zeros, ones, _signExtendBit)
            }

        private fun BigInteger.prettyToString() = when {
            this == BigInteger.ZERO -> "ZERO"
            this == BigInteger.ONE -> "ONE"
            bitCount() == 1 -> "2^${lowestSetBit}"
            (this + BigInteger.ONE).bitCount() == 1 -> "2^${(this + BigInteger.ONE).lowestSetBit}-1"
            else -> toString(16)
        }

        // Convenience BigInteger functions

        val allOnes get() = MAX_EVM_UINT256

        /** All [width] high bits are 1 */
        fun highOnes(width: Int) = allOnes - lowOnes(EVM_BITWIDTH256 - width)

        fun bwNot(i: BigInteger): BigInteger = i xor allOnes

        infix fun BigInteger.intersects(o: BigInteger) =
            (this and o) != BigInteger.ZERO

        infix fun BigInteger.disjointFrom(o: BigInteger) =
            (this and o) == BigInteger.ZERO

        infix fun BigInteger.containedIn(o: BigInteger) =
            (this and o) == this

        val BigInteger.isPowOf2
            get(): Boolean {
                require(this >= BigInteger.ZERO) { "Can't call isPowOf2 on negative numbers : $this" }
                return bitCount() == 1
            }

        val BigInteger.isPowOf2Minus1
            get() = (this + BigInteger.ONE).isPowOf2
    }

    /**
     * Wraps a [BigInteger] as a [Ternary] where no bit is x.
     */
    private data class Constant(val value: BigInteger) : NonBottom() {
        init {
            check(value >= BigInteger.ZERO && value < EVM_MOD_GROUP256)
        }

        override val ones get() = value
        override val zeros: BigInteger get() = bwNot(value)
        override val unknowns: BigInteger get() = BigInteger.ZERO
        override val constants get() = allOnes
        override val signExtendBit: Int
            get() {
                val highestOne = value.bitLength()
                return if (highestOne < EVM_BITWIDTH256) {
                    highestOne + 1
                } else {
                    bwNot(value).bitLength() + 1
                }
            }

        override fun toString(): String = value.prettyToString()
    }


    private data class NonConstant private constructor(
        override val zeros: BigInteger,
        override val ones: BigInteger,
        override val signExtendBit: Int
    ) : NonBottom() {

        companion object {
            operator fun invoke(zeros: BigInteger, ones: BigInteger, _signExtendBit: Int? = null): NonConstant {
                val upperBound = when {
                    // If the last bit is a 1, then looking at all non-1 bits, right after the last one is the
                    // sign-extend bit.
                    // For example (imagine we are talking of 8 bit numbers): 111XX000. Then the last bit is 1,
                    // bwNot(ones) = 00011111, so the bitLength is 5, and 6 is indeed [signExtendBit].
                    ones.bitLength() == EVM_BITWIDTH256 ->
                        bwNot(ones).bitLength() + 1

                    // similarly if 0 is the last bit.
                    zeros.bitLength() == EVM_BITWIDTH256 ->
                        bwNot(zeros).bitLength() + 1

                    else ->
                        EVM_BITWIDTH256
                }
                val signExtendBit = if (_signExtendBit == null) {
                    upperBound
                } else {
                    minOf(_signExtendBit, upperBound)
                }
                return NonConstant(zeros, ones, signExtendBit)
            }
        }

        init {
            check(ones disjointFrom zeros)
            check(ones >= BigInteger.ZERO && ones < EVM_MOD_GROUP256)
            check(zeros >= BigInteger.ZERO && zeros < EVM_MOD_GROUP256)
        }

        override val constants: BigInteger get() = zeros or ones
        override val unknowns: BigInteger get() = bwNot(constants)

        /**
         * Representation is 4 bits at a time. Shorthand is: X=xxxx, F=1111, 0=0000, and if these don't apply, then
         * the 4 bits are represented explicitly, e.g., [010x].
         */
        override fun toString() = when (this) {
            allXs -> "noInfo"
            boolX -> "x"
            else -> (63 downTo 0).map { digit ->
                val f = 15.toBigInteger()
                val local0s = (zeros shr digit * 4) and f
                val local1s = (ones shr digit * 4) and f
                val localXs = (unknowns shr digit * 4) and f
                when {
                    local0s == f -> "0"
                    local1s == f -> "F"
                    localXs == f -> "X"
                    else -> "[" +
                            (3 downTo 0).map { bit ->
                                when {
                                    local0s.testBit(bit) -> "0"
                                    local1s.testBit(bit) -> "1"
                                    else -> "x"
                                }
                            }.joinToString("") { it } + "]"
                }
            }.joinToString("") { it }
        } + if (signExtendBit != 256) "<$signExtendBit>" else ""

    }


    fun isConstant() = this is Constant

    fun asConstantOrNull() = (this as? Constant)?.value

    fun asConstant() = (this as Constant).value

    fun asIntOrNull() = (this as? Constant)?.value?.let {
        if (it.isInt()) it.intValueExact() else null
    }

    fun isPowOfTwo() =
        asConstantOrNull()?.bitCount() == 1

    /**
     * Takes two ternaries, and functions to operate on them in each of the cases.
     * If a function is null then we fall back to the next case:
     * [twoConstants] -> [oneConstant] -> [otherwise]
     */
    private fun calcCases(
        a: Ternary,
        b: Ternary,
        twoBottoms : Ternary = Bottom, // default
        twoConstants: ((BigInteger, BigInteger) -> BigInteger)? = null,
        oneConstant: ((BigInteger, Ternary) -> Ternary)? = null,
        oneBottom: ((NonBottom) -> Ternary),
        otherwise: (NonBottom, NonBottom) -> Ternary
    ) =
        when {
            a is Bottom && b is Bottom ->
                twoBottoms
            a is Constant && b is Constant && twoConstants != null ->
                Ternary(twoConstants(a.value, b.value))
            a is Constant && oneConstant != null ->
                oneConstant(a.value, b)
            b is Constant && oneConstant != null ->
                oneConstant(b.value, a)
            a is Bottom ->
                oneBottom(b as NonBottom)
            b is Bottom ->
                oneBottom(a as NonBottom)
            else ->
                otherwise(a as NonBottom, b as NonBottom)
        }

    infix fun and(o: Ternary) =
        calcCases(this, o,
            twoConstants = BigInteger::and,
            oneBottom = { a -> Ternary(a.zeros, BigInteger.ZERO) },
            otherwise = { a, b ->
                Ternary(
                    a.zeros or b.zeros,
                    a.ones and b.ones,
                    maxOf(a.signExtendBit, b.signExtendBit)
                )
            }
        )


    infix fun or(o: Ternary) =
        calcCases(this, o,
            twoConstants = BigInteger::or,
            oneBottom = { a -> Ternary(BigInteger.ZERO, a.ones) } ,
            otherwise = { a, b ->
                Ternary(
                    a.zeros and b.zeros,
                    a.ones or b.ones,
                    maxOf(a.signExtendBit, b.signExtendBit)
                )
            }
        )

    fun not() = when (this) {
        is Bottom -> Bottom
        is Constant -> Ternary(bwNot(value))
        is NonConstant -> Ternary(zeros = ones, ones = zeros, signExtendBit)
    }

    infix fun plus(o: Ternary) =
        calcCases(this, o,
            twoBottoms = allXs,
            oneBottom = { allXs },
            twoConstants = { a, b -> (a + b) % EVM_MOD_GROUP256 },
            otherwise = { a, b ->
                if (a.zeros or b.zeros == allOnes) {
                    a or b
                } else {
                    val surelyZerosBit = 1 + maxOf(bwNot(a.zeros).bitLength(), bwNot(b.zeros).bitLength())
                    if (surelyZerosBit >= EVM_BITWIDTH256) {
                        allXs
                    } else {
                        Ternary(
                            lowOnes(EVM_BITWIDTH256 - surelyZerosBit) shl surelyZerosBit,
                            BigInteger.ZERO
                        )
                    }
                }
            }
        )

    infix fun sub(o: Ternary) =
        calcCases(this, o,
            twoBottoms = allXs,
            oneBottom = { allXs },
            twoConstants = { a, b ->
                (a - b).let {
                    if (it >= BigInteger.ZERO) {
                        it
                    } else {
                        EVM_MOD_GROUP256 + it // two's complement
                    }
                }
            },
            otherwise = { _, _ -> allXs }
        )


    infix fun shiftLeft(by: Int) =
        when (this) {
            is Bottom -> allXs
            is Constant -> Ternary((value shl by) and allOnes)
            is NonConstant -> Ternary(
                ((zeros shl by) and allOnes) or lowOnes(by),
                (ones shl by) and allOnes,
                signExtendBit + by
            )
        }


    infix fun shiftRight(by: Int) =
        when (this) {
            is Bottom -> allXs
            is Constant -> Ternary(value shr by)
            is NonConstant ->
                Ternary(
                    (zeros shr by) or (lowOnes(by) shl (EVM_BITWIDTH256 - by)),
                    ones shr by
                )
        }

    /**
     * This is a join of the two ternary over-approximations - that is, the best ternary over-approximation we can get
     * that satisfies the two. In other words, only the constants we agree on are still constants, anything else is X.
     */
    infix fun join(o: Ternary) =
        calcCases(this, o,
            oneBottom = { Bottom },
            otherwise = { a, b ->
                Ternary(
                    a.zeros and b.zeros,
                    a.ones and b.ones,
                    maxOf(a.signExtendBit, b.signExtendBit)
                )
            }
        )

    infix fun signExtend(topBit: Int): Ternary {
        val keptBitsMask = lowOnes(topBit)
        return when (this) {
            is Bottom ->
                Ternary(BigInteger.ZERO, BigInteger.ZERO, topBit)

            is Constant -> {
                val lowBits = value and keptBitsMask
                Ternary(
                    if (lowBits.testBit(topBit - 1)) {
                        lowBits or bwNot(keptBitsMask)
                    } else {
                        lowBits
                    }
                )
            }

            is NonConstant -> {
                val lowZeros = zeros and keptBitsMask
                val lowOnes = ones and keptBitsMask
                // result depends on whether we know the top bit or not
                when {
                    zeros.testBit(topBit - 1) ->
                        Ternary(lowZeros or bwNot(keptBitsMask), lowOnes)

                    ones.testBit(topBit - 1) ->
                        Ternary(lowZeros, lowOnes or bwNot(keptBitsMask))

                    else ->
                        Ternary(lowZeros, lowOnes, minOf(signExtendBit, topBit))
                }
            }
        }
    }

    private fun boolToBig(v: Boolean) =
        if (v) BigInteger.ONE else BigInteger.ZERO


    private fun calcCasesBoolResult(
        a: Ternary,
        b: Ternary,
        twoBottoms : Ternary = boolX,
        twoConstants: ((BigInteger, BigInteger) -> BigInteger)? = null,
        oneConstant: ((BigInteger, Ternary) -> Ternary)? = null,
        oneBottom: ((NonBottom) -> Ternary) = { boolX },
        otherwise: (NonBottom, NonBottom) -> Ternary = { _, _ -> boolX }
    ) = calcCases(a, b,
        twoBottoms = twoBottoms,
        twoConstants = twoConstants,
        oneConstant = oneConstant,
        oneBottom = oneBottom,
        otherwise = otherwise,
    )

    infix fun eq(o: Ternary) =
        calcCasesBoolResult(this, o,
            twoConstants = { a, b -> boolToBig(a == b) },
            otherwise = { a, b ->
                if (a.ones intersects b.zeros || a.zeros intersects b.ones) {
                    zero
                } else {
                    boolX
                }
            }
        )

    /** this's last one is after o's last non-zero. If we care to we can be more precise here */
    protected infix fun surelyGt(o: Ternary) =
        this is NonBottom && o is NonBottom && ones.bitLength() > bwNot(o.zeros).bitLength()

    infix fun gt(o: Ternary) =
        calcCasesBoolResult(this, o,
            twoConstants = { a, b -> boolToBig(a > b) },
            otherwise = { a, b ->
                when {
                    a surelyGt b -> one
                    b surelyGt a -> zero
                    else -> boolX
                }
            }
        )

    infix fun ge(o: Ternary) =
        calcCasesBoolResult(this, o,
            twoConstants = { a, b -> boolToBig(a >= b) },
            otherwise = { a, b ->
                when {
                    // There is no point in checking for equality, because for that, there can be no X's, meaning both
                    // a and b are constants. This case is already covered.
                    a surelyGt b -> one
                    b surelyGt a -> zero
                    else -> boolX
                }
            }
        )

    infix fun lt(o: Ternary) = o gt this

    infix fun le(o: Ternary) = o ge this

    infix fun land(o: Ternary) =
        calcCasesBoolResult(this, o,
            oneConstant = { c, a -> if (c == BigInteger.ZERO) zero else a },
        )

    infix fun lor(o: Ternary) =
        calcCasesBoolResult(this, o,
            oneConstant = { c, a -> if (c == BigInteger.ONE) one else a }
        )

    fun lnot() = when (this) {
        is Bottom -> Bottom
        is Constant -> Ternary(boolToBig(value == BigInteger.ZERO))
        is NonConstant -> boolX
    }
}
