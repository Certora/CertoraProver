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

import sbf.analysis.LivenessAnalysis
import sbf.cfg.BinOp
import sbf.cfg.CondOp.EQ
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class LivenessTest {

    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("f") {
            bb(0) {
                // Assign values
                r1 = 0x01
                r2 = 0x02
                // Use CondOps as instructions (r1 := r1 == r2 here)
                EQ(r1, r2)
                // Conditional branch
                br(EQ(r3, 0x1), 1, 2)
            }

            bb(1) {
                r4 = 0x12345678
                // *(r4 + 0x08) := 0x01
                r4[0x08] = 0x1
                // r5 := *(r4 + 0x10)
                r5 = r4[0x10]
                // Unconditional branch
                goto(2)
            }

            bb(2) {
                exit()
            }
        }
        cfg.verify(true)
        val liveness = LivenessAnalysis(cfg)
        println("Result of liveness:\n${liveness}")
        Assertions.assertEquals(true, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R3), Label.Address(0)))

        Assertions.assertEquals(false, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R1), Label.Address(0)))
        Assertions.assertEquals(false, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R2), Label.Address(0)))
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("f") {
            bb(0) {
                r1 = 0x01
                r2 = 0x0
                goto(1)
            }

            bb(1) {
                br(EQ(r2, 0x99), 2, 3)
            }

            bb(2) {
                BinOp.ADD(r1, r2)
                BinOp.ADD(r2, 1)
                goto(1)
            }

            bb(3) {
                exit()
            }
        }
        cfg.verify(true)
        val liveness = LivenessAnalysis(cfg)
        println("Result of liveness:\n${liveness}")

        Assertions.assertEquals(true, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R1), Label.Address(1)))
        Assertions.assertEquals(true, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R2), Label.Address(1)))

        Assertions.assertEquals(false, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R1), Label.Address(0)))
        Assertions.assertEquals(false, liveness.isAliveAtEntry(Value.Reg(SbfRegister.R2), Label.Address(0)))

    }

}
