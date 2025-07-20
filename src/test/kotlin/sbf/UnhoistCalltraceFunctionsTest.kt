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
import sbf.disassembler.*
import org.junit.jupiter.api.*
import sbf.callgraph.CVTCalltrace
import sbf.testing.SbfTestDSL

class UnhoistCalltraceFunctionsTest {

    @Test
    fun test01() {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                r3 = r10
                r4 = r10
                BinOp.SUB(r3, 100)
                BinOp.SUB(r4, 200)
                br(CondOp.LT(r7, 0), 1, 2)
            }
            bb(1) {
                br(CondOp.LT(r8, 0), 3, 4)
            }
            bb(2) {
                r1 = 976432
                r2 = 7
                goto(5)
            }
            bb(3) {
                r1 = 976232
                r2 = 19
                goto(5)
            }
            bb(4) {
                r1 = 976032
                r2 = 34
                goto(5)
            }
            bb(5) {
                r5 = 0
                r6 = 1
                "CVT_calltrace_print_u64_2"()
                exit()
            }
        }


        println("Before\n$cfg")
        cfg.verify(true)
        unhoistCalltraceFunctions(cfg)
        println("After\n$cfg")
        val bb = cfg.getBlock(Label.Address(5))
        check(bb != null) {"Cannot find block 5 in cfg"}
        Assertions.assertEquals(false,   bb.getInstructions().any {
            it is SbfInstruction.Call && CVTCalltrace.from(it.name) != null
        })
    }

    @Test
    fun test02() {
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(0) {
                br(CondOp.LT(r7, 0), 1, 2)
            }
            bb(1) {
                br(CondOp.LT(r8, 0), 3, 4)
            }
            bb(2) {
                r1 = 976432
                r2 = 7
                goto(5)
            }
            bb(3) {
                r1 = 976232
                r2 = 19
                goto(5)
            }
            bb(4) {
                r1 = 976032
                r2 = 34
                goto(5)
            }
            bb(5) {
                r1 = 976532
                r2 = 8
                r3 = r10
                r4 = r10
                BinOp.SUB(r3, 100)
                BinOp.SUB(r4, 200)
                "CVT_calltrace_print_u64_2"()
                exit()
            }
        }


        println("Before\n$cfg")
        cfg.verify(true)
        unhoistCalltraceFunctions(cfg)
        println("After\n$cfg")
        val bb = cfg.getBlock(Label.Address(5))
        check(bb != null) {"Cannot find block 5 in cfg"}
        Assertions.assertEquals(true,   bb.getInstructions().any {
            it is SbfInstruction.Call && CVTCalltrace.from(it.name) != null
        })
    }
}
