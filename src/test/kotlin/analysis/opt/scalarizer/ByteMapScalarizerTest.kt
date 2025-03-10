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

package analysis.opt.scalarizer

import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder

class ByteMapScalarizerTest : TACBuilderAuxiliaries() {

    @Test
    fun basic() {
        val prog = TACProgramBuilder {
            bMap1[0x12a] assign b
            bMap2 assign bMap1
            c assign bMap2[0x12a]
        }
        ByteMapScalarizer.go(prog.code)
    }

    @Test
    fun mapDefinitionTest() {
        val prog = TACProgramBuilder {
            bMap1 assign mapDef(listOf(aS), Add(aS, One), Tag.ByteMap)
            bMap2 assign bMap1
            c assign bMap2[0]
            d assign bMap2[2]
        }
        ByteMapScalarizer.go(prog.code)
    }



}
