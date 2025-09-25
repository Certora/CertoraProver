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

class TACSanityTest {
    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("test1$vacuitySuffix") {
            bb(0) {
                r2 = r10[-200]
                BinOp.ADD(r2, 1)
                r10[-200] = r2
                r1 = 1
                "CVT_sanity"()  // This should be translated to CVT_satisfy(true)
                exit()
            }

        }

        println("Before removing calls to \"CVT_sanity\":\n$cfg")
        removeSanityCalls(cfg, isVacuityRule = true)
        println("After removing calls to \"CVT_sanity\":\n$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(false, verify(tacProg))
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                r4 = r10[-200]
                assume(CondOp.LE(r4, 1000))
                r2 = r4
                BinOp.ADD(r2, 1)
                r10[-200] = r2
                r3 = r10[-200]
                assert(CondOp.LT(r4, r3))
                r1 = 1
                "CVT_sanity"() // This should be a non-op
                exit()
            }

        }

        println("Before removing calls to \"CVT_sanity\":\n$cfg")
        removeSanityCalls(cfg, isVacuityRule = false)
        println("After removing calls to \"CVT_sanity\":\n$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }
}
