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
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.analysis.MemoryAnalysis
import sbf.analysis.cpis.ProgramId
import sbf.disassembler.GlobalVariables
import sbf.disassembler.Label
import sbf.disassembler.SbfRegister

private val nodeAllocator = PTANodeAllocator { BasicPTANodeFlags() }
typealias MemoryAnalysisT = MemoryAnalysis<Constant, Constant, BasicPTANodeFlags>

private fun getMemAnalysisResults(
    cfg: SbfCFG,
    globals: GlobalVariables = GlobalVariables(DefaultElfFileView),
    memSummaries: MemorySummaries = MemorySummaries()
) : MemoryAnalysisT {
    val memDomOpts = MemoryDomainOpts(useEqualityDomain = true)
    return MemoryAnalysis(cfg, globals, memSummaries, ConstantSbfTypeFactory(), nodeAllocator.flagsFactory, memDomOpts, processor = null)
}

private fun checkProgramIdInvoke(results: MemoryAnalysisT, label: Label, expectedProgramId: ProgramId) {
    val memAbsVal = results.getPre(label)
    println("$memAbsVal")
    check(memAbsVal != null)

    val programId = memAbsVal.getPubkey(Value.Reg(SbfRegister.R2_ARG), 48)
    println("$programId")

    Assertions.assertEquals(true, programId != null)
    check(programId != null)

    Assertions.assertEquals(true, programId.word0 == expectedProgramId.chunk0)
    Assertions.assertEquals(true, programId.word1 == expectedProgramId.chunk1)
    Assertions.assertEquals(true, programId.word2 == expectedProgramId.chunk2)
    Assertions.assertEquals(true, programId.word3 == expectedProgramId.chunk3)
}

private fun checkNoProgramIdInvoke(results: MemoryAnalysisT, label: Label) {
    val memAbsVal = results.getPre(label)
    println("$memAbsVal")
    check(memAbsVal != null)

    val programId = memAbsVal.getPubkey(Value.Reg(SbfRegister.R2_ARG), 48)
    println("$programId")

    Assertions.assertEquals(true, programId == null)
}

class MemoryEqualityDomainTest {

    @Test
    /** Snippet from real code **/
    fun test1() {
        println("====== TEST 1  =======")
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r2 = r0
                r3 = r2[0]
                assume(CondOp.EQ(r3, -7808848301000303354))
                r3 = r2[8]
                assume(CondOp.EQ(r3, -6018520155818964007))
                r3 = r2[16]
                assume(CondOp.EQ(r3, -7982811346925931492))
                r2 = r2[24]
                assume(CondOp.EQ(r2, -6268729762421306310))
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 32)
                r2 = r0
                r3 = 32
                "sol_memcpy_"()         // memcpy(sp(4064), heap, 32)
                ///////////////////
                r2 = r10
                BinOp.SUB(r2, 80)
                r1 = r10
                BinOp.SUB(r1, 180)
                r3 = 80
                "sol_memcpy_"()         // memcpy(sp(3916), sp(4016), 80)
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 344)
                r2 = r10
                BinOp.SUB(r2, 180)
                r3 = 80
                "sol_memcpy_"()         // memcpy(sp(3752), sp(3916), 80)
                ////////////////////
                r2 = r1
                r1 = r10
                BinOp.SUB(r1, 700)
                r3 = r10
                BinOp.SUB(r3, 600)
                goto (2)
            }
            bb(2) {
                "solana_program::program::invoke"()
                exit()
            }
        }
        println("$cfg")
        val programId = ProgramId(
            (-7808848301000303354L).toULong(),
            (-6018520155818964007L).toULong(),
            (-7982811346925931492L).toULong(),
            (-6268729762421306310L).toULong()
        )
        val results = getMemAnalysisResults(cfg)
        checkProgramIdInvoke(results, Label.Address(2), programId)
    }

    /** Similar to test1() but using `memset` instead of `load`+`assume`. It is expected to pass **/
    @Test
    fun test2() {
        println("====== TEST 2  =======")
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r4 = r0
                r1 = r4
                r2 = 0
                r3 = 32
                "sol_memset_"()
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 32)
                r2 = r4
                r3 = 32
                "sol_memcpy_"()         // memcmp(sp(4064), heap, 32)
                ///////////////////
                r2 = r10
                BinOp.SUB(r2, 80)
                r1 = r10
                BinOp.SUB(r1, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3916), sp(4016), 80)
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 344)
                r2 = r10
                BinOp.SUB(r2, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3752), sp(3916), 80)
                ////////////////////
                r2 = r1
                r1 = r10
                BinOp.SUB(r1, 700)
                r3 = r10
                BinOp.SUB(r3, 600)
                goto (2)
            }
            bb(2) {
                "solana_program::program::invoke"()
                exit()
            }
        }
        println("$cfg")
        val programId = ProgramId(0UL, 0UL, 0UL, 0UL)
        val results = getMemAnalysisResults(cfg)
        checkProgramIdInvoke(results, Label.Address(2), programId)
    }

    /** This test is expected to pass because the store at `sp(3920)` cannot alias with the inferred predicates **/
    @Test
    fun test3() {
        println("====== TEST 3  =======")
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r2 = r0
                r3 = r2[0]
                assume(CondOp.EQ(r3, -7808848301000303354))
                r3 = r2[8]
                assume(CondOp.EQ(r3, -6018520155818964007))
                r3 = r2[16]
                assume(CondOp.EQ(r3, -7982811346925931492))
                r2 = r2[24]
                assume(CondOp.EQ(r2, -6268729762421306310))
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 32)
                r2 = r0
                r3 = 32
                "sol_memcpy_"()         // memcpy(sp(4064), heap, 32)
                ///////////////////
                r2 = r10
                BinOp.SUB(r2, 80)
                r1 = r10
                BinOp.SUB(r1, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3916), sp(4016), 80)
                ///////////////////
                r1[4] = 5              // *sp(3920) = 5
                //////////////////
                r1 = r10
                BinOp.SUB(r1, 344)
                r2 = r10
                BinOp.SUB(r2, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3752), sp(3916), 80)
                ////////////////////
                r2 = r1
                r1 = r10
                BinOp.SUB(r1, 700)
                r3 = r10
                BinOp.SUB(r3, 600)
                goto (2)
            }
            bb(2) {
                "solana_program::program::invoke"()
                exit()
            }
        }
        println("$cfg")
        val programId = ProgramId(
            (-7808848301000303354L).toULong(),
            (-6018520155818964007L).toULong(),
            (-7982811346925931492L).toULong(),
            (-6268729762421306310L).toULong()
        )
        val results = getMemAnalysisResults(cfg)
        checkProgramIdInvoke(results, Label.Address(2), programId)
    }

    /** This test should fail because of there is a store at sp(3964) that aliases with one of the inferred predicates **/
    @Test
    fun test4() {
        println("====== TEST 4  =======")
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r2 = r0
                r3 = r2[0]
                assume(CondOp.EQ(r3, -7808848301000303354))
                r3 = r2[8]
                assume(CondOp.EQ(r3, -6018520155818964007))
                r3 = r2[16]
                assume(CondOp.EQ(r3, -7982811346925931492))
                r2 = r2[24]
                assume(CondOp.EQ(r2, -6268729762421306310))
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 32)
                r2 = r0
                r3 = 32
                "sol_memcpy_"()         // memcpy(sp(4064), heap, 32)
                ///////////////////
                r2 = r10
                BinOp.SUB(r2, 80)
                r1 = r10
                BinOp.SUB(r1, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3916), sp(4016), 80)
                ///////////////////
                r1[52] = 5              // *sp(3964) = 5
                //////////////////
                r1 = r10
                BinOp.SUB(r1, 344)
                r2 = r10
                BinOp.SUB(r2, 180)
                r3 = 80
                "sol_memcpy_"()        // memcpy(sp(3752), sp(3916), 80)
                ////////////////////
                r2 = r1
                r1 = r10
                BinOp.SUB(r1, 700)
                r3 = r10
                BinOp.SUB(r3, 600)
                goto (2)
            }
            bb(2) {
                "solana_program::program::invoke"()
                exit()
            }
        }
        println("$cfg")
        val results = getMemAnalysisResults(cfg)
        checkNoProgramIdInvoke(results, Label.Address(2))
    }

    /** Similar to test01 but with `store` + `memcmp`. This test should pass **/
    @Test
    fun test5() {
        println("====== TEST 5  =======")
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r2 = r0
                r2[0] = -7808848301000303354
                r2[8] = -6018520155818964007
                r2[16] = -7982811346925931492
                r2[24] = -6268729762421306310

                r1 = r10
                BinOp.SUB(r1, 32)
                r2 = r0
                r3 = 32
                "sol_memcmp_"()                // r0 = memcmp(sp(4064), heap, 32)
                assume(CondOp.EQ(r0, 0)) // assume(r0 == 0)

                ///////////////////
                r2 = r10
                BinOp.SUB(r2, 80)
                r1 = r10
                BinOp.SUB(r1, 180)
                r3 = 80
                "sol_memcpy_"()         // memcpy(sp(3916), sp(4016), 80)
                ///////////////////
                r1 = r10
                BinOp.SUB(r1, 344)
                r2 = r10
                BinOp.SUB(r2, 180)
                r3 = 80
                "sol_memcpy_"()         // memcpy(sp(3752), sp(3916), 80)
                ////////////////////
                r2 = r1
                r1 = r10
                BinOp.SUB(r1, 700)
                r3 = r10
                BinOp.SUB(r3, 600)
                goto (2)
            }
            bb(2) {
                "solana_program::program::invoke"()
                exit()
            }
        }
        println("$cfg")
        val programId = ProgramId(
            (-7808848301000303354L).toULong(),
            (-6018520155818964007L).toULong(),
            (-7982811346925931492L).toULong(),
            (-6268729762421306310L).toULong()
        )
        val results = getMemAnalysisResults(cfg)
        checkProgramIdInvoke(results, Label.Address(2), programId)
    }

}
