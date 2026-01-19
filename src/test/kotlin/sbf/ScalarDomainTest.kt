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

import sbf.analysis.ScalarAnalysis
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.analysis.AnalysisRegisterTypes
import sbf.support.UnknownMemcpyLenError
import sbf.support.UnsupportedCallX

private val sbfTypesFac = ConstantSbfTypeFactory()
private val env = StackEnvironment<ScalarValue<Constant, Constant>>()

class ScalarDomainTest {

    @Test
    fun test01() {
        println( "====== TEST 1: StackEnvironment.overlap  =======")
        run {
            val onlyPartial = false /// any overlap
            // check [20,28) and [4,28)
            Assertions.assertEquals(true, env.overlap(ByteRange(20, 8), 4, 24, onlyPartial))
            // check [24,32) and [4,28)
            Assertions.assertEquals(true, env.overlap(ByteRange(24, 8), 4, 24, onlyPartial))
            // check [24,32) and [4,28)
            Assertions.assertEquals(true, env.overlap(ByteRange(24, 8), 4, 24, onlyPartial))
            // check [28,36) and [4,28)
            Assertions.assertEquals(false, env.overlap(ByteRange(28, 8), 4, 24, onlyPartial))
            // check [4,12) and [12,44]
            Assertions.assertEquals(false, env.overlap(ByteRange(4, 8), 12, 32, onlyPartial))
            // check [8,16) and [12,44]
            Assertions.assertEquals(true, env.overlap(ByteRange(8, 8), 12, 32, onlyPartial))
            // check [8,16) and [12,44]
            Assertions.assertEquals(true, env.overlap(ByteRange(8, 8), 12, 32, onlyPartial))
        }


        run {
            val onlyPartial = true /// exclude the case where first interval is included in the second one
            // check X= [20,28) and Y=[4,28)
            Assertions.assertEquals(false, env.overlap(ByteRange(20, 8), 4, 24, onlyPartial))
            // check [16, 24) and [4, 36)
            Assertions.assertEquals(false, env.overlap(ByteRange(16, 8), 4, 32, onlyPartial))
            // check [16, 24) and [20, 52)
            Assertions.assertEquals(true, env.overlap(ByteRange(16, 8), 20, 32, onlyPartial))
        }
    }

    @Test
    fun test02() {
        println( "====== TEST 2: memcpy (last word)  =======")
        /**
         *   r2 := r10 - 104
         *   *(r2 + 0):= 0
         *   *(r2 + 8):= 0
         *   *(r2 + 16):= 0
         *   *(r2 + 24):= 0
         *   r1 := r10 - 204
         *   r3 := 32
         *   memcpy(r1,r2,r3)
         *   assert(*(r1+24) == 0)
        **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r2 = r10
                BinOp.SUB(r2, 104)
                r1 = r10
                BinOp.SUB(r1, 204)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r3 = 32
                "sol_memcpy_"()
                r4 = r1[24]
                assert(CondOp.EQ(r4, 0))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun test03() {
        println( "====== TEST 3  =======")
        /**
         *   r1 := 5
         *   r1 := r1 + 5
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5
                BinOp.ADD(r1, 5)
            }
        }
        println("$cfg")
        val globals = GlobalVariables(DefaultElfFileView)
        val memSummaries = MemorySummaries()
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val b0 = cfg.getBlock(Label.Address(0))
        check (b0 != null)
        val addInst = b0.getLocatedInstructions().drop(1).first()
        val type = regTypes.typeAtInstruction(addInst, SbfRegister.R1_ARG)
        Assertions.assertEquals(true, type is SbfType.NumType && type.value.toLongOrNull() == 5L)
    }

    @Test
    fun test04() {
        println( "====== TEST 4  =======")
        /**
         *   assume(r1 == 5)
         *   assert(r1 == 5)
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                assume(CondOp.EQ(r4, 5))
                assert(CondOp.EQ(r4, 5))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun test05() {
        println( "====== TEST 5: simple memory store and read =======")
        /**
         *   r1 := r10 - 104
         *   *(r1+0) = 5
         *   assert( *(r1+0) == 5)
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 104)
                r1[0] = 5
                r1 = r1[0]
                assert(CondOp.EQ(r1, 5))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun test06() {
        println( "====== TEST 6: implicit cast at memory store  =======")
        /**
         *   r1 == 56789
         *   *(r1+0) = 0
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 56789
                r1[0] = 0
            }
        }
        println("$cfg")
        val globals = GlobalVariables(DefaultElfFileView, listOf(SbfGlobalVariable("myglobal", 56789, 8)))
        val memSummaries = MemorySummaries()
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val b0 = cfg.getBlock(Label.Address(0))
        check (b0 != null)
        val secondInst = b0.getLocatedInstructions().drop(1).first()
        val secondType = regTypes.typeAtInstruction(secondInst, SbfRegister.R1_ARG)
        println("$secondInst -> $secondType")
       // Assertions.assertEquals(true, secondType is SbfType.NumType && secondType.value.get() == 5L)
    }

    @Test
    fun test07() {
        println( "====== TEST 7: implicit cast at memory read  =======")
        /**
         *   r1 == 56789
         *   r1 = *(r1+0)
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 56789
                r1[0] = 0
            }
        }
        println("$cfg")
        val globals = GlobalVariables(DefaultElfFileView, listOf(SbfGlobalVariable("myglobal", 56789, 8)))
        val memSummaries = MemorySummaries()
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)

        val b0 = cfg.getBlock(Label.Address(0))
        check (b0 != null)
        val secondInst = b0.getLocatedInstructions().drop(1).first()
        val secondType = regTypes.typeAtInstruction(secondInst, SbfRegister.R1_ARG)
        println("$secondInst -> $secondType")
        // Assertions.assertEquals(true, secondType is SbfType.NumType && secondType.value.get() == 5L)
    }

    @Test
    fun test08() {
        println( "====== TEST 8 =======")
        /**
         *  Example where the content of a stack offset is written and then copied (via memcpy) to another part of the stack.
         */
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r4 = r10
                BinOp.SUB(r4, 104)
                r4[0] = 5  // *sp(-104) := 5

                r2 = r10
                BinOp.SUB(r2, 504)
                r2[0] = r4

                r3 = 8
                r1 = r10
                BinOp.SUB(r1, 204)
                "sol_memcpy_"()
                r5 = r1[0]
                r5 = r5[0]
                assert(CondOp.EQ(r5, 5))
                exit()
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }


    @Test
    fun test09() {
        println( "====== TEST 9: two stores that overlap  =======")
        /**
         *   r1 := r10 - 104
         *   *(r1+0) = 5
         *   *(r1+4) = 10
         *   assert( *(r1+0) == 5) // it shouldn't be probable
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 104)
                r1[0] = 5
                r1[4] = 10
                r1 = r1[0]
                assert(CondOp.EQ(r1, 5))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(false, check.result)
        }
    }

    @Test
    fun test10() {
        println( "====== TEST 10: two contiguous stores with no overlaps  =======")
        /**
         *   r1 := r10 - 104
         *   *(r1+0) = 5
         *   *(r1+8) = 10
         *   assert( *(r1+8) == 10) // it's true
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 104)
                r1[0] = 5
                r1[8] = 10
                r1 = r1[8]
                assert(CondOp.EQ(r1, 10))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }
    @Test
    fun test11() {
        println( "====== TEST 11: precision gain due to assume =======")
        /**
         * 0:
         *         if (r10 != 0) then goto 1 else goto 2
         * 1:
         *         r4 := r10
         *         r3 := 1
         *         goto 3
         * 2:
         *         r4 := 5
         *         r3 := 1
         *         goto 3
         *
         * 3:
         *         // [r4 -> TOP, r3 -> 1]
         *         assume(r3 == r4)
         *
         *         // as of the assume and the knowledge of r3 == 1,
         *         // we can infer [r4 -> 1, r3 -> 1]
         *         assert(r4 == 1) // it's true
          */

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                br(CondOp.NE(r10,0), 1, 2)
            }
            bb(1) {
                r4 = r10 // loads TOP
                r3 = 1
                goto(3)
            }
            bb(2) {
                r4 = 5
                r3 = 1
                goto(3)
            }
            bb(3) {
                assume(CondOp.EQ(r3, r4))
                assert(CondOp.EQ(r4, 1))
            }
        }
        println("$cfg")
        val prover = ScalarAnalysisProver(cfg, sbfTypesFac)
        for (check in prover.getChecks()) {
            Assertions.assertEquals(true, check.result)
        }
    }

    @Test
    fun `callx is not supported`() {

        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val cfg = MutableSbfCFG("test")
        val b0 = cfg.getOrInsertBlock(Label.Address(0))
        cfg.setEntry(b0)
        b0.add(SbfInstruction.CallReg(r1))
        b0.add(SbfInstruction.Exit())

        expectException<UnsupportedCallX> {
            ScalarAnalysisProver(cfg, sbfTypesFac)
        }
    }
}
