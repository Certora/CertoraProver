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

import datastructures.stdcollections.*
import sbf.analysis.DataDependencyAnalysis
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.domains.PTAField
import sbf.support.PointerExpressionError
import sbf.support.PtrExprErrReg
import sbf.support.PtrExprErrStackDeref
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.domains.PTAOffset

class DataDepAnalysisTest {
    private fun runDDAWithSingleSource(cfg: SbfCFG, targetI: LocatedSbfInstruction, targetE: PointerExpressionError): LocatedSbfInstruction {
        println("TARGET = $targetI -- $targetE")
        val dda = DataDependencyAnalysis(targetI, targetE, cfg, GlobalVariables(DefaultElfFileView), MemorySummaries())
        println("SOURCES = ${dda.sources}")
        val source = dda.sources.toList().singleOrNull()
        check(source != null) { "No sources inferred by the analysis" }
        return source
    }

    private fun runDDAWithSingleSource(cfg:SbfCFG, target: LocatedSbfInstruction, reg: SbfRegister) =
        runDDAWithSingleSource(cfg, target, PtrExprErrReg(Value.Reg(reg)))


    @Test
    fun test01() {
        // Basic example using only stack (load+store) and registers
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                /*0*/ r1 = r10
                /*1*/ BinOp.SUB(r1, 100)
                /*2*/ r2 = r10
                /*3*/ BinOp.SUB(r2, 200)
                /*4*/ r10[-100] = 0
                /*5*/ r10[-200]= 0
                /*6*/ r3 = r10[-100]
                /*7*/ r10[-150] = r3
                /*8*/ "bar"()
                /*9*/ r3 = r10[-200]
                /*10*/ r10[-250] = r3
                /*11*/"foo"()
                /*12*/r2 = r1[-150]
                /*13*/r3 = r10[-250]
                /*14*/assert(CondOp.EQ(r2, 0UL))
                /*15*/assert(CondOp.EQ(r3, 0UL))
                /*16*/exit()
            }
        }

        run {
            // Target is a register r3 at instruction 15
            val target = cfg.getBlock(Label.Address(0))?.getLocatedInstructions()?.get(15)
            check(target != null) { "Target instruction not found" }
            val source = runDDAWithSingleSource(cfg, target, SbfRegister.R3_ARG)
            Assertions.assertEquals(true, source.pos == 5)
        }

        run {
            // Target is the content of stack location 3846 at instruction 13
            val target = cfg.getBlock(Label.Address(0))?.getLocatedInstructions()?.get(13)
            check(target != null) { "Target instruction not found" }
            val source = runDDAWithSingleSource(cfg, target, PtrExprErrStackDeref(PTAField(PTAOffset(3846), 8)))
            Assertions.assertEquals(true, source.pos == 5)
        }
    }

    @Test
    fun test02() {
        // This test makes sure that the analysis works as expected with loops
        // Target is register r4 at the exit of the loop (block 3).
        val cfg = SbfTestDSL.makeCFG("f") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 100)
                r1[0] = 0  /* SOURCE */
                goto(1)
            }
            bb(1 ) {
                r2 = r1[0]
                br(CondOp.EQ(r2, 0x0), 2, 3)
            }

            bb(2) {
                r4 = 0x12345678
                r3 = r1[0]
                BinOp.SUB(r3, 1)
                r1[0] = r3
                goto(1)
            }

            bb(3) {
                r4 = r1[0]   /* TARGET */
                exit()
            }
        }

        val target = cfg.getBlock(Label.Address(3))?.getLocatedInstructions()?.get(0)
        check(target != null) { "Target instruction not found" }
        val source = runDDAWithSingleSource(cfg, target, SbfRegister.R4_ARG)
        Assertions.assertEquals(true, source.label == Label.Address(0) && source.pos == 2)

    }

    @Test
    fun test03() {
        // This example tests that the analysis handles correctly memcpy and memset on stack
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                /*0*/ r1 = r10
                /*1*/ BinOp.SUB(r1, 100)
                /*2*/r2 = 0
                /*3*/r3 = 32
                /*4*/"sol_memset_"()    /* SOURCE */
                /*5*/r2 = r1
                /*6*/r1 = r10
                /*7*/BinOp.SUB(r1, 200)
                /*8*/"sol_memcpy_"()
                /*9*/r2 = r1
                /*10*/r1 = r10
                /*11*/BinOp.SUB(r1, 300)
                /*12*/"sol_memcpy_"()
                /*13*/r2 = r1
                /*14*/r1 = r10
                /*15*/BinOp.SUB(r1, 400)
                /*16*/"sol_memcpy_"()
                /*17*/r4 = r1[16]   /* TARGET */
                /*18*/assert(CondOp.EQ(r4, 0UL))
                /*19*/exit()
            }
        }

        val target = cfg.getBlock(Label.Address(0))?.getLocatedInstructions()?.get(17)
        check(target != null) { "Target instruction not found" }
        val source = runDDAWithSingleSource(cfg, target, SbfRegister.R4_ARG)
        Assertions.assertEquals(true, source.pos == 4)
    }

    @Test
    fun test04() {
        // This test exemplifies a PTA error
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                /*0*/r1 = 0   /* SOURCE */
                /*1*/r2 = r1
                /*2*/r3 = 32
                /*3*/r1 = r10
                /*4*/BinOp.SUB(r1, 200)
                /*5*/"sol_memcpy_"()  /* TARGET */
                /*6*/exit()
            }
        }

        val target = cfg.getBlock(Label.Address(0))?.getLocatedInstructions()?.get(5)
        check(target != null) { "Target instruction not found" }
        val source = runDDAWithSingleSource(cfg, target, SbfRegister.R2_ARG)
        Assertions.assertEquals(true, source.pos == 0)
    }
}
