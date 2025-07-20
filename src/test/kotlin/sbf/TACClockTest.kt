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

class TACClockTest {

    /**
     * ```
     * r1 = r10 - 200
     * *r1 = 0, *(r1+8) = 1, *(r1+16) = 2, *(r1+24) = 3, *(r1+32) = 4
     * sol_set_clock_sysvar()
     * memset(r1, 0, 40)
     * r1 = r10 - 400
     * sol_get_clock_sysvar()
     * assert(*(r1+16) == 2)
     * ```
     */
    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0] = 0
                r1[8] = 1
                r1[16] = 2
                r1[24] = 3
                r1[32] = 4
                "sol_set_clock_sysvar"()
                r2 = 0
                r3 = 40
                "sol_memset_" ()
                r1 = r10
                BinOp.SUB(r1, 400)
                "sol_get_clock_sysvar"()
                r2 = r1[16]
                assert(CondOp.EQ(r2, 2))
                exit()
            }
        }
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


    /** Similar to test1 but we pass heap to sol_set_clock_sysvar instead of stack.**/
    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "__rust_alloc"()
                r1 = r0
                r1[0] = 0
                r1[8] = 1
                r1[16] = 2
                r1[24] = 3
                r1[32] = 4
                "sol_set_clock_sysvar"()
                r2 = 0
                r3 = 40
                "sol_memset_" ()
                r1 = r10
                BinOp.SUB(r1, 400)
                "sol_get_clock_sysvar"()
                r2 = r1[16]
                assert(CondOp.EQ(r2, 2))
                exit()
            }
        }
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


}
