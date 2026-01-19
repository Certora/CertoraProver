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
package utils

import evm.twoToThe
import java.math.BigInteger


interface ModZm {
    class OutOfBoundsException(msg: String): Exception(msg)

    val bitwidth: Int

    /** 2^[bitwidth] */
    val modulus: BigInteger

    /** Maximum value of unsigned with [bitwidth] bits : 2^[bitwidth]-1 */
    val maxUnsigned: BigInteger

    /** Maximum value of signed with [bitwidth] bits: 2^([bitwidth]-1)-1 */
    val maxSigned: BigInteger

    /** Minimum value of signed with [bitwidth] bits: -2^([bitwidth]-1) */
    val minSignedMath: BigInteger

    /** Minimum value of signed with [bitwidth] bits in 2s-complement encoding: : 2^([bitwidth]-1) */
    val minSigned2s: BigInteger

    /** this is a shorthand, instead of `with(modZm) { ... }`, one can use `modZm { ... }` */
    operator fun <T> invoke(f: ModZm.() -> T) = this.f()

    val BigInteger.inBounds
        get() = this in BigInteger.ZERO..maxUnsigned

    /** Called on mathints */
    val BigInteger.inSignedBounds
        get() = this in minSignedMath..maxSigned

    /** Checks that `this` is in unsigned bounds. Returns `this` unchanged */
    fun BigInteger.checked() = also {
        require(inBounds) {
            "$this is not within range of width $bitwidth"
        }
    }

    val BigInteger.is2sNonNeg
        get() = checked() <= maxSigned

    val BigInteger.is2sNeg
        get() = !is2sNonNeg

    fun BigInteger.to2s() =
        when {
            !inSignedBounds -> throw OutOfBoundsException("$this is cannot be converted to a 2s complement bit-vector of width $bitwidth")
            this >= BigInteger.ZERO -> this
            else -> this + modulus
        }

    fun BigInteger.from2s() =
        if (is2sNonNeg) {
            this
        } else {
            this - modulus
        }

    fun Int.to2s() = toBigInteger().to2s()
    fun Long.to2s() = toBigInteger().to2s()
    fun Int.from2s() = toBigInteger().from2s()
    fun Long.from2s() = toBigInteger().from2s()

    fun checkAndMod(a: BigInteger, b: BigInteger, f: (BigInteger, BigInteger) -> BigInteger): BigInteger =
        f(a.checked(), b.checked()).mod(modulus)

    fun <T> checkAnd(a: BigInteger, b: BigInteger, f: (BigInteger, BigInteger) -> T) =
        f(a.checked(), b.checked())

    fun add(a: BigInteger, b: BigInteger): BigInteger = checkAndMod(a, b, BigInteger::plus)
    fun sub(a: BigInteger, b: BigInteger): BigInteger = checkAndMod(a, b, BigInteger::minus)
    fun mul(a: BigInteger, b: BigInteger): BigInteger = checkAndMod(a, b, BigInteger::multiply)
    fun mod(a: BigInteger, b: BigInteger): BigInteger = checkAnd(a, b) { x, y ->
        if (y == BigInteger.ZERO) {
            BigInteger.ZERO
        } else {
            x.mod(y)
        }
    }
    fun exp(a: BigInteger, b: BigInteger): BigInteger = checkAnd(a, b) { x, y -> x.modPow(y, modulus) }

    fun signExtendFromBit(a: BigInteger, fromBit: Int): BigInteger =
        a.signExtend(fromBit, bitwidth)

    fun bwNot(a: BigInteger): BigInteger = maxUnsigned xor a.checked()

    fun shl(a: BigInteger, b: BigInteger): BigInteger =
        checkAndMod(a, b) { x, y ->
            y.toIntOrNull()?.takeIf { it < bitwidth }
                ?.let { x shl it }
                ?: BigInteger.ZERO
        }

    fun sar(a: BigInteger, b: BigInteger): BigInteger =
        checkAnd(a, b) { x, y ->
            y.toIntOrNull()?.takeIf { it < bitwidth }
                ?.let { signExtendFromBit(a shr it, bitwidth - it) }
                ?: if (x.is2sNonNeg) {
                    BigInteger.ZERO
                } else {
                    maxUnsigned
                }
        }

    fun signedPred(a: BigInteger, b: BigInteger, p: (BigInteger, BigInteger) -> Boolean) =
        checkAnd(a, b) { x, y -> p(x.from2s(), y.from2s()) }.asBigInteger

    fun slt(a: BigInteger, b: BigInteger) = signedPred(a, b) { x, y -> x < y }
    fun sle(a: BigInteger, b: BigInteger) = signedPred(a, b) { x, y -> x <= y }
    fun sgt(a: BigInteger, b: BigInteger) = signedPred(a, b) { x, y -> x > y }
    fun sge(a: BigInteger, b: BigInteger) = signedPred(a, b) { x, y -> x >= y }

    fun highOnes(width: Int) = maxUnsigned - lowOnes(bitwidth - width)

    /** in EVM, dividing by 0 returns 0 */
    fun div(a: BigInteger, b: BigInteger): BigInteger =
        if (b == BigInteger.ZERO) {
            BigInteger.ZERO
        } else {
            checkAndMod(a, b, BigInteger::div)
        }

    /** specific EVM behavior */
    fun sdiv(a: BigInteger, b: BigInteger): BigInteger =
        when {
            b == BigInteger.ZERO -> BigInteger.ZERO
            // `minInt256 / -1 = minInt256`. This is actually an overflow, but EVM defines it this way.
            a == minSigned2s && b == maxUnsigned -> minSigned2s
            else -> (a.from2s() / b.from2s()).to2s()
        }

    /**
     * EVM mod cares only for the sign in the numerator. So:
     *      12 %  5 =  2
     *      12 % -5 =  2
     *     -12 %  5 = -2
     *     -12 % -5 = -2
     * Of course in EVM these are represented in 2s complement.
     *
     * https://github.com/ethereum/go-ethereum/blob/da29332c5f4c368ff03ec4e7132eefac48fed1ae/core/vm/instructions.go#L106
     */
    fun smod(a: BigInteger, b: BigInteger): BigInteger = when (b) {
        BigInteger.ZERO -> BigInteger.ZERO
        else -> {
            val aa = a.from2s()
            val mm = b.from2s().abs()
            aa.abs().mod(mm).letIf(aa < BigInteger.ZERO) { -it }.to2s()
        }
    }

    fun shr(a: BigInteger, b: BigInteger) = Companion.shr(a, b)


    companion object {
        fun highOnes(width: Int, modZm: ModZm = modZ256) = modZm { highOnes(width) }
        fun BigInteger.from2s(modZm: ModZm = modZ256) = modZm { this@from2s.from2s() }
        fun BigInteger.to2s(modZm: ModZm = modZ256) = modZm { this@to2s.to2s() }
        fun Long.from2s(modZm: ModZm = modZ256) = modZm { this@from2s.from2s() }
        fun Long.to2s(modZm: ModZm = modZ256) = modZm { this@to2s.to2s() }
        fun Int.from2s(modZm: ModZm = modZ256) = modZm { this@from2s.from2s() }
        fun Int.to2s(modZm: ModZm = modZ256) = modZm { this@to2s.to2s() }
        fun BigInteger.inSignedBounds(modZm: ModZm = modZ256) = modZm { inSignedBounds }
        fun BigInteger.inBounds(modZm: ModZm = modZ256) = modZm { inBounds }
        fun BigInteger.inBoundsCheck(modZm: ModZm = modZ256) = modZm { checked() }

        fun bwNot(i : BigInteger, modZm: ModZm = modZ256) = modZm { bwNot(i) }

        /**
         * Performs a sign-extension from [sourceWidth] to [targetWidth] bits. This essentially fills the new bits (from
         * [sourceWidth] to [targetWidth]-1) with the sign bit (at [sourceWidth]-1).
         * This is different from the EVM-style sign-extension that works on whole bytes and always extends to 256 bits.
         */
        fun BigInteger.signExtend(sourceWidth: Int, targetWidth: Int): BigInteger {
            check(sourceWidth <= targetWidth) { "signExtend should extend to more bits" }
            return if (this.testBit(sourceWidth - 1)) {
                this or onesRange(sourceWidth, targetWidth)
            } else {
                this and lowOnes(sourceWidth)
            }
        }

        fun signExtendFromBit(a: BigInteger, fromBit: Int, modZm: ModZm = modZ256) =
            modZm { this.signExtendFromBit(a, fromBit) }

        /** These don't really need the information in [ModZm] but they fit here nicely */

        fun lowOnes(end: Int) = BigInteger.ONE.shiftLeft(end) - BigInteger.ONE

        /** bits are 1 from bit [start] up to bit [end] (non-inclusive) */
        fun onesRange(start: Int, end: Int) = lowOnes(end) - lowOnes(start)

        /**
         * returns <2, 5> for the [c] = 11100.
         * Returns `null` if [c] is not of this form.
         */
        fun onesZerosMask(c: BigInteger): Pair<Int, Int>? {
            val lowOne = c.lowestSetBit
            val highOne = c.bitLength()
            return runIf(onesRange(lowOne, highOne) == c) {
                lowOne to highOne
            }
        }

        fun shr(a: BigInteger, b: BigInteger): BigInteger =
            b.toIntOrNull()?.takeIf { it < a.bitLength() }
                ?.let { a shr it }
                ?: BigInteger.ZERO

        val Boolean.asBigInteger: BigInteger
            get() = if (this) {
                BigInteger.ONE
            } else {
                BigInteger.ZERO
            }


        fun addMod(a: BigInteger, b: BigInteger, m: BigInteger): BigInteger =
            if (m == BigInteger.ZERO) {
                BigInteger.ZERO
            } else {
                (a + b).mod(m)
            }

        fun mulMod(a: BigInteger, b: BigInteger, m: BigInteger): BigInteger =
            if (m == BigInteger.ZERO) {
                BigInteger.ZERO
            } else {
                (a * b).mod(m)
            }


        fun isZero(a: BigInteger) = (a == BigInteger.ZERO).asBigInteger

        /**
         * Sign extends [b] from ([a] + 1) * 8 bits to 256 bits.
         * The implementation operates on the low-level, bit representation of [b] and treats [b] as if
         * it is an intK (in particular, it does not rely on its encoding as a [BigInteger] and
         * ignores the actual [BigInteger] sign bit of [b]).
         *
         * @param[a] The index of the most significant byte of [b]. At least 0 and at most 31.
         *
         * @param[b] Signed (two's complement) integer where (([a] + 1) * 8)-1 is assumed to be
         * the index of its sign bit. That is, the type of [b] is seen as Solidity's intK where K is given by [a].
         *
         * @return [b] encoded as if its type is Solidity's int256.
         */
        fun evmSignExtend(a: BigInteger, b: BigInteger): BigInteger =
            a.toIntOrNull()?.takeIf { it in 0..31 }
                ?.let { signExtendFromBit(b, (it + 1) * 8) }
                ?: throw OutOfBoundsException("in evaluating signExtend($a, $b), $a is out of range")

    }
}


/**
 * This should really be in the interface, but can't have vals there..
 */
data class ConcreteModZm(override val bitwidth: Int) : ModZm {
    override val modulus = twoToThe(bitwidth)
    override val maxUnsigned: BigInteger = modulus - 1
    override val maxSigned: BigInteger = twoToThe(bitwidth - 1) - 1
    override val minSignedMath: BigInteger = -twoToThe(bitwidth - 1)
    override val minSigned2s: BigInteger = maxSigned + 1
}

val modZ256 = ConcreteModZm(256)

val EVMOps = modZ256
