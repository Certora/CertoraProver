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
package analysis.opt.signed

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.asTACExpr

class NoUnderOverflowUnderApproxTest : TACBuilderAuxiliaries() {
    @Test
    fun unfold() {
        val prog = TACProgramBuilder {
            a assign Mul(bS, Mul(cS, dS))
            e assign SDiv(aS, 2.asTACExpr)
        }
        val u = NoUnderOverflowUnderApprox(prog.code)
        u.go()
        Assertions.assertEquals(2, u.stats["signedMul"])
    }

}
