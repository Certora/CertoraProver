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

package instrumentation.transformers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import vc.data.TACBuilderAuxiliaries
import vc.data.TACKeyword
import vc.data.TACProgramBuilder
import vc.data.asTACExpr
import java.math.BigInteger

class BytesKCastRewriterTest : TACBuilderAuxiliaries() {
    @ParameterizedTest
    @CsvSource("true,false,true", "false,false,true", "true,true,true", "true,true,false")
    fun incomingLoadAddresses(addressBeforeLength: Boolean, extraLoad: Boolean, correctResult: Boolean) {
        val memory = TACKeyword.MEMORY.toVar()
        val other = bv256Var("other")
        val mask = BigInteger("ffffffff", 16).shiftLeft(224).asTACExpr
        val prog = TACProgramBuilder {
            havoc(a)
            havoc(other)
            if (addressBeforeLength) {
                b assign Add(aS, 32.asTACExpr)
            }
            c assign memory[a]
            if (!addressBeforeLength) {
                b assign Add(aS, 32.asTACExpr)
            }
            d assign memory[b]
            e assign BWAnd(dS, mask)
            if (extraLoad) {
                // This address has no relation to the candidate array.
                h assign memory[other]
            }
            x assign Lt(cS, 4.asTACExpr)
            jumpCond(x)
            jump(1) {
                f assign BWAnd(eS, ShiftLeft(mask, ShiftLeft(Sub(4.asTACExpr, cS), 3.asTACExpr)))
                jump(3) {
                    g assign f
                }
            }
            jump(2) {
                f assign if (correctResult) { eS } else { hS }
                jump(3)
            }
        }
        val rewritten = BytesKCastRewriter.rewrite(prog.code)
        assertEquals(
            if (correctResult) { 1 } else { 0 },
            rewritten.analysisCache.graph.commands.count { BytesKCastRewriter.BYTESK_CAST in it.cmd.meta }
        )
    }
}
