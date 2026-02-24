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

@file:Suppress("NAME_SHADOWING")
package analysis

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import evm.twoToThe
import tac.*
import tac.generation.*
import vc.data.*

object BitCounts {
    /**
        Generates TAC to count the number of leading zeros in [x].
    */
    fun countLeadingZeros(
        /** Variable to receive the number of leading zeros in [x] */
        r: TACSymbol.Var,
        /** Number we're inspecting for leading zeros */
        x: TACExpr,
        /** Size of [x]. [x]'s value must fit in [maxBits] bits. The resulting bit count is relative to this size. */
        maxBits: Int,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        val highBit = twoToThe(maxBits - 1).asTACExpr
        val `1` = 1.asTACExpr
        val `0` = 0.asTACExpr
        return x.letVar { x ->
            mergeMany(
                // Start with havoc, and constrain the value to a correct solution
                assignHavoc(r, meta),
                assume(meta) {
                    // If x's high bit is set, r is 0...
                    ite(
                        i = x ge highBit,
                        t = r eq `0`,
                        e = (
                            // ...otherwise, x must be in the interval: [highBit >> r, highBit >> r-1)
                            (r le maxBits.asTACExpr) and
                            (x ge highBit.shiftRLog(r)) and
                            (x lt highBit.shiftRLog(r sub `1`))
                        )
                    )
                }
            )
        }
    }

    /**
        Generates TAC to count the number of trailing zeros in [x]
     */
    fun countTrailingZeros(
        /** Variable to receive the number of trailing zeros in [x] */
        r: TACSymbol.Var,
        /** Number we're inspecting for trailing zeros */
        x: TACExpr,
        /** Size of [x]. [x]'s value must fit in [maxBits] bits. The resulting bit count is relative to this size. */
        maxBits: Int,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        val `1` = 1.asTACExpr
        val `0` = 0.asTACExpr
        return x.letVar { x ->
            mergeMany(
                // Start with havoc, and constrain the value to a correct solution
                assignHavoc(r, meta),
                assume(meta) {
                    // If x is 0, r is maxBits...
                    ite(
                        i = x eq `0`,
                        t = r eq maxBits.asTACExpr,
                        e = (
                            // ...otherwise, bit r of x is 1, and bits 0..r-1 are 0
                            (r le maxBits.asTACExpr) and
                            (x.mod(`1`.shiftL(r)) eq `0`) and
                            (x.mod(`1`.shiftL(r add `1`)) neq `0`)
                        )
                    )
                }
            )
        }
    }
}
