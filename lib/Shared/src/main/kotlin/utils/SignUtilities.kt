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

package utils

import config.Config
import java.math.BigInteger

/**
 * Provides several convenience functions for working with signed values.
 *
 * Additionally, used by the interpreter to evaluate the builtin functions defined in vc.data.TACBuiltInFunction
 */
object SignUtilities {

    private val defaultBitwidth = Config.VMConfig.registerBitwidth

    fun minSignedValueOfBitwidth(bitWidth: Int): BigInteger {
        require(bitWidth > 0) { "Value out of bounds for bitwidth: $bitWidth" }
        return -BigInteger.TWO.pow(bitWidth - 1)
    }

    fun maxSignedValueOfBitwidth(bitWidth: Int): BigInteger = maxUnsignedValueOfBitwidth(bitWidth - 1)

    fun maxUnsignedValueOfBitwidth(bitWidth: Int): BigInteger {
        require(bitWidth >= 0) { "Value out of bounds for bitwidth: $bitWidth" }
        return BigInteger.TWO.pow(bitWidth) - 1
    }

    /** Checks whether [n] in in the allowed bounds of an unsigned int of width [bitWidth] */
    @JvmOverloads
    fun isInUnsignedBounds(n: BigInteger, bitWidth: Int = defaultBitwidth) =
        n >= BigInteger.ZERO && n <= maxUnsignedValueOfBitwidth(bitWidth)

    /**
     * Checks whether [n] in in the allowed bounds of an signed int of width [bitWidth].
     * The check is done assume [n] is in mathint form (can take negative numbers), and not in 2s complement form
     */
    fun isInSignedBounds(n: BigInteger, bitWidth: Int = defaultBitwidth) =
        n >= minSignedValueOfBitwidth(bitWidth) && n <= maxSignedValueOfBitwidth(bitWidth)

    /** Converts from 2s-complement representation to the mathint one */
    fun BigInteger.from2sComplement(bitWidth: Int = defaultBitwidth): BigInteger {
        require(isInUnsignedBounds(this, bitWidth)) {
            "converting $this from 2s-complement form, but it's not in range."
        }
        return if (this > maxSignedValueOfBitwidth(bitWidth)) {
            this - BigInteger.TWO.pow(bitWidth)
        } else {
            this
        }
    }

    /** Converts from a mathint representation to the 2s-complement one */
    fun BigInteger.to2sComplement(bitWidth: Int = defaultBitwidth): BigInteger {
        require(isInSignedBounds(this, bitWidth)) {
            "converting $this from mathint to 2s-complement form, but it's not in range."
        }
        return if (this < BigInteger.ZERO) {
            this + BigInteger.TWO.pow(bitWidth)
        } else {
            this
        }
    }


}
