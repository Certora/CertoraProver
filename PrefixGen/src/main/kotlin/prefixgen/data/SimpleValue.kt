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

package prefixgen.data

import utils.isInt
import java.math.BigInteger

/**
 * A value parsed out of the raw calldata provided by the foundry fuzz tester.
 * There is no typing information, and we simply divide the arguments into dynamically
 * sized string [HexString] and [Word].
 * NB that both use the same data representation (a hex string) but we still
 * split on the static vs dynamic length to avoid nasty mixups.
 */
sealed interface SimpleValue {
    data class Word(val hexString: String) : SimpleValue
    data class HexString(val len: Int, val hexString: String) : SimpleValue

    fun toInt(): Int = when(this) {
        is HexString -> throw UnsupportedOperationException("not a word")
        is Word -> {
            val asBigInt = toBigInt()
            if(!asBigInt.isInt()) {
                throw UnsupportedOperationException("Value too large")
            }
            asBigInt.intValueExact()
        }
    }

    fun toBigInt(): BigInteger = when(this) {
        is HexString -> BigInteger(hexString, 16)
        is Word -> BigInteger(hexString, 16)
    }

    fun bounded(mod: Int) = (this as Word).toBigInt().mod(mod.toBigInteger()).intValueExact()
}
