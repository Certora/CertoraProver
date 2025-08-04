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
package analysis.opt.bytemaps

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.TACSymbol

class ByteMapScalarizerTest : TACBuilderAuxiliaries() {

    /** Checks that the [analysis.opt.bytemaps.ScalarizerCalculator] detects the bases we expect it to detect */
    private fun assertScalarizedBases(prog: TACProgramBuilder.BuiltTACProgram, vararg expectedBases: TACSymbol.Var) {
        val bases = ScalarizerCalculator.goodBases(prog.code) { true }
        Assertions.assertEquals(expectedBases.toSet(), bases)
    }

    @Test
    fun basic() {
        val prog = TACProgramBuilder {
            bMap1[0x12a] assign b
            bMap2 assign bMap1
            c assign bMap2[0x12a]
        }
        assertScalarizedBases(prog, bMap1, bMap2)
    }

    @Test
    fun mapDefinitionTest() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), Add(aS, One), Tag.ByteMap)
            bMap2 assign bMap1
            c assign bMap2[0]
            d assign bMap2[2]
        }
        assertScalarizedBases(prog, bMap1, bMap2)
    }

    @Test
    fun dontMiss() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), ite(Lt(aS, bS), TACExpr.Unconstrained(Tag.Bit256), Zero), Tag.ByteMap)
            c assign bMap1[1]
            d assign bMap1[2]
            e assign bMap1[3]
        }
        assertScalarizedBases(prog, bMap1)
    }


}
