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

package sbf

import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class TACUnordFPCompTest {

    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                // 0x7FF8000000000000 which is a nan
                // The exponent is 7FF (all 1s)
                // mantisa is 8000000000000 which is non-zero
                r1 = 9221120237041090560
                // r2 a floating point with bit pattern 101 which is not nan
                r2 = 5
                "__unorddf2"()
                assert(CondOp.EQ(r0, 1))
                exit()
            }
        }
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                // r1 a floating point with bit pattern 1100 which is not nan
                r1 = 12
                // r2 a floating point with bit pattern 101 which is not nan
                r2 = 5
                "__unorddf2"()
                assert(CondOp.EQ(r0, 0))
                exit()
            }
        }
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

}
