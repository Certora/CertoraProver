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

package report

import evm.powersOf2
import java.math.BigInteger
import datastructures.stdcollections.*
import java.lang.Integer.min

object BigIntPretty {

    private val lowPositiveNumbers =
        (1 .. 15).toList()

    /** Get the offsets for 2^[currExponent]. (the offsets need to be much smaller than the current power) */
    private fun offsets(currExponent: Int) =
        lowPositiveNumbers + // 1 .. 15
            powersOf2.toList().subList(4, min(17, currExponent - 2)).flatMap { pow -> // from 15 to ~65K
                lowPositiveNumbers.map { pow.toInt() + it } // 2^n + [1, ..., 15]
            }

    private val bigIntToPretty =
        powersOf2 // Q: this list only goes to 2^256 -- should we recognize higher powers of two here as well?
            .toList().drop(10) // things below four digits don't need a string alias
            .flatMap { power ->
                listOf(power to "2^${power.lowestSetBit}") +
                    offsets(power.lowestSetBit).flatMap { offset ->
                        listOf(
                            // we could recursively compress the offset to get things like `2^16 - 2^7`, so far we're
                            // showing  `2^16 - 128` in that case ... possible future work ...:W
                            (power + offset.toBigInteger()) to "2^${power.lowestSetBit} + $offset",
                            (power - offset.toBigInteger()) to "2^${power.lowestSetBit} - $offset",
                        )
                    }
            }.toMap()


    /**
     * If there is a nice string alias for [v] (e.g. "2^16" when [v] is 65536), returns the pair (<that alias>, `true`),
     * otherwise returns the pair (<[v].toString()>, `false`).
     */
    fun bigIntPretty(v: BigInteger) = bigIntToPretty[v]
}
