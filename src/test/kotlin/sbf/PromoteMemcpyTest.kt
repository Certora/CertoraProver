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

import config.ConfigScope
import sbf.analysis.ScalarAnalysis
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.analysis.AnalysisRegisterTypes

private val sbfTypesFac = ConstantSbfTypeFactory()
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class PromoteMemcpyTest {

    private fun checkMemcpy(cfg: SbfCFG): Boolean {
        for (inst in cfg.getEntry().getInstructions()) {
            if (inst is SbfInstruction.Call) {
                if (inst.name == "sol_memcpy_") {
                    return true
                }
            }
        }
        return false
    }
    private fun getNumOfLoads(cfg: SbfCFG): Int {
        var counter = 0
        for (inst in cfg.getEntry().getInstructions()) {
            if (inst is SbfInstruction.Mem && inst.isLoad) {
                counter++
            }
        }
        return counter
    }


    @Test
    fun test01() {
        println("====== TEST 1  (one memcpy of 32 bytes) =======")
        /**
         *   r1 := *(u64 *) (r10 + -56)
         *   *(u64 *) (r10 + -96) := r1
         *   r2 := *(u64 *) (r10 + -48)
         *   *(u64 *) (r10 + -88) := r2
         *   r3 := *(u64 *) (r10 + -40)
         *   *(u64 *) (r10 + -80) := r3
         *   r4 := *(u64 *) (r10 + -64)
         *   *(u64 *) (r10 + -104) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                r10[-96] = r1
                r2 = r10[-48]
                r10[-88] = r2
                r3 = r10[-40]
                r10[-80] = r3
                r4 = r10[-64]
                r10[-104] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test02() {
        println("====== TEST 2 (2 memcpy of 32 bytes each, both having the same sources) =======")
        /**
         *   r1 := *(u64 *) (r10 + -56)
         *   *(u64 *) (r10 + -96) := r1
         *   r2 := *(u64 *) (r10 + -48)
         *   *(u64 *) (r10 + -88) := r2
         *   r3 := *(u64 *) (r10 + -40)
         *   *(u64 *) (r10 + -80) := r3
         *   r4 := *(u64 *) (r10 + -64)
         *   *(u64 *) (r10 + -104) := r4
         *   *(u64 *) (r6 + 32) := r3
         *   *(u64 *) (r6 + 24) := r2
         *   *(u64 *) (r6 + 16) := r1
         *   *(u64 *) (r6 + 8) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                r10[-96] = r1
                r2 = r10[-48]
                r10[-88] = r2
                r3 = r10[-40]
                r10[-80] = r3
                r4 = r10[-64]
                r10[-104] = r4
                r6 = r10
                BinOp.SUB(r6, 4096)
                r6[32] = r3
                r6[24] = r2
                r6[16] = r1
                r6[8] = r4
                r3 = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test03() {
        println("====== TEST 3  (1 memcpy of 16 bytes) =======")
        /**
         *   r1 := *(u64 *) (r10 + -56)
         *   *(u64 *) (r10 + -96) := r1
         *   r2 := *(u64 *) (r10 + -48)
         *   *(u64 *) (r10 + -88) := r2
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                r10[-96] = r1
                r2 = r10[-48]
                r10[-88] = r2
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test04() {
        println("====== TEST 4 (two independent memcpy of 16 bytes each) =======")
        /**
         *   r1 := *(u64 *) (r10 + -56)
         *   *(u64 *) (r10 + -96) := r1
         *   r2 := *(u64 *) (r10 + -48)
         *   *(u64 *) (r10 + -88) := r2
         *   r3 := *(u64 *) (r10 + -20)
         *   *(u64 *) (r10 + -280) := r3
         *   r4 := *(u64 *) (r10 + -28)
         *   *(u64 *) (r10 + -288) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                r10[-96] = r1
                r2 = r10[-48]
                r10[-88] = r2
                r3 = r10[-20]
                r10[-280] = r3
                r4 = r10[-28]
                r10[-288] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test05() {
        println("====== TEST 1  (one memcpy of 16 bytes) =======")
        /**
         *   r1 := *(u64 *) (r10 + -56)
         *   *(u64 *) (r10 + -104) := r1
         *   r2 := *(u64 *) (r10 + -48)
         *   *(u64 *) (r10 + -88) := r2
         *   r3 := *(u64 *) (r10 + -40)
         *   *(u64 *) (r10 + -80) := r3
         *   r4 := *(u64 *) (r10 + -64)
         *   *(u64 *) (r10 + -112) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r10[-56]
                r10[-104] = r1
                r2 = r10[-48]
                r10[-88] = r2
                r3 = r10[-40]
                r10[-80] = r3
                r4 = r10[-64]
                r10[-112] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test06() {
        println("====== TEST 6  (one memcpy of 32 bytes from heap to stack) =======")
        /**
         *   r0 := malloc(32)
         *   r7 := r0
         *   r1 := *(u64 *) (r7 + 24)
         *   *(u64 *) (r10 + -48) := r1
         *   r2 := *(u64 *) (r7 + 16)
         *   *(u64 *) (r10 + -56) := r2
         *   r3 := *(u64 *) (r7 + 8)
         *   *(u64 *) (r10 + -64) := r3
         *   r4 := *(u64 *) (r7 + 0)
         *   *(u64 *) (r10 + -72) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = 32
                "malloc"()
                r7 = r0
                r1 = r7[24]
                r10[-48] = r1
                r2 = r7[16]
                r10[-56] = r2
                r3 = r7[8]
                r10[-64] = r3
                r4 = r7[0]
                r10[-72] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test07() {
        println("====== TEST 7  (one memcpy of 32 bytes from heap to stack) =======")
        /**
         *   r0 := malloc(32)
         *   r7 := r0
         *   r1 := *(u64 *) (r7 + 16)
         *   *(u64 *) (r10 + -48) := r1
         *   r2 := *(u64 *) (r7 + 8)
         *   *(u64 *) (r10 + -56) := r2
         *   r3 := *(u64 *) (r7 + 0)
         *   *(u64 *) (r10 + -64) := r3
         *   r4 := *(u64 *) (r7 + -8)
         *   *(u64 *) (r10 + -72) := r4
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = 32
                "malloc"()
                r7 = r0
                r1 = r7[16]
                r10[-48] = r1
                r2 = r7[8]
                r10[-56] = r2
                r3 = r7[0]
                r10[-64] = r3
                r4 = r7[-8]
                r10[-72] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }


    @Test
    fun test08() {
        println("====== TEST 8  (one memcpy of 32 bytes from unknown memory to stack) =======")
        /**
         *   r1 := *(u64 *) (r7 + 24)
         *   *(u64 *) (r10 + -48) := r1
         *   r2 := *(u64 *) (r7 + 16)
         *   *(u64 *) (r10 + -56) := r2
         *   r3 := *(u64 *) (r7 + 8)
         *   *(u64 *) (r10 + -64) := r3
         *   r4 := *(u64 *) (r7 + 0)
         *   *(u64 *) (r10 + -72) := r4
         **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = r7[24]
                r10[-48] = r1
                r2 = r7[16]
                r10[-56] = r2
                r3 = r7[8]
                r10[-64] = r3
                r4 = r7[0]
                r10[-72] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

@Test
fun test09() {
    println("====== TEST 9  (one memcpy of 32 bytes from unknown memory to stack) =======")
    /**
       r1 := r7
       r1 := r1 + 76
       r2 := *(u64 *) (r1 + 24)
       *(u64 *) (r10 + -88) := r2
       r2 := *(u64 *) (r1 + 16)
       *(u64 *) (r10 + -96) := r2
       r2 := *(u64 *) (r1 + 8)
       *(u64 *) (r10 + -104) := r2
       r1 := *(u64 *) (r1 + 0)
       *(u64 *) (r10 + -112) := r1
     **/
    val cfg = SbfTestDSL.makeCFG("entrypoint") {
        bb(0) {
            BinOp.ADD(r10, 4096)
            r1 = r7
            BinOp.ADD(r1, 76)
            r2 = r1[24]
            r10[-88] = r2
            r2 = r1[16]
            r10[-96] = r2
            r2 = r1[8]
            r10[-104] = r2
            r2 = r1[0]
            r10[-112] = r2
            exit()
        }
    }

    val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
    println("Before transformation\n$cfg")
    ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
    }
    println("After transformation\n$cfg")
    removeUselessDefinitions(cfg)
    println("After remove useless loads transformation\n$cfg")
    Assertions.assertEquals(true, checkMemcpy(cfg))
}

    @Test
    fun test10() {
        println("====== TEST 10  (from callee stack to caller stack) =======")
        /**
         *   r10 := r10 + 4096
         *   r6 := r0
         *   r10 := r10 + 4096
         *   r1 := *(u64 *) (r10 + -304)
         *   *(u64 *) (r10 + 128) := r1
         *   r1 := *(u64 *) (r10 + -296)
         *   *(u64 *) (r10 + 120) := r1
         *   r1 := *(u64 *) (r10 + -288)
         *   *(u64 *) (r10 + 112) := r1
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r6 = r10
                BinOp.ADD(r10, 4096)
                r1 = r10[-304]
                r6[128] = r1
                r1 = r10[-296]
                r6[120] = r1
                r1 = r10[-288]
                r6[112] = r1
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis, aggressivePromotion = false)
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemcpy(cfg))
    }

    @Test
    fun test11() {
        println("====== TEST 11 (no memcpy) =======")
        /**
         *   r1 := *(u64 *) (r10 + -4080)
         *   *(u64 *) (r10 + -664) := r1
         *   r2 := *(u64 *) (r10 + -4088)
         *   *(u64 *) (r10 + -656) := r2
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r1 = r10[-4080]
                r10[-664] = r1
                r2 = r10[-4088]
                r10[-656] = r2
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis, aggressivePromotion = false)
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemcpy(cfg))
    }

    /**
     * LLVM code
     *    call void @llvm.memcpy.p0.p0.i64(ptr noundef nonnull align 1 dereferenceable(31) %val.sroa.4.0.authority24.sroa_idx,
     *                                    ptr noundef nonnull align 1 dereferenceable(31) %self26.sroa.4.sroa.4.0.self26.sroa.4.0.maybe_authority.sroa_idx.sroa_idx,
     *                                    i64 31, i1 false)
     *    store i8 %self26.sroa.4.sroa.0.0.copyload, ptr %authority24, align 1
     * is translated to this SBF code:
     *    r1 := *(u64 *) (r10 + -62)
     *    (u64 *) (r10 + -159) := r1
     *    r1 := *(u64 *) (r10 + -54)
     *    *(u64 *) (r10 + -151) := r1
     *    r1 := *(u64 *) (r10 + -46)
     *    *(u64 *) (r10 + -143) := r1
     *    r1 := *(u64 *) (r10 + -39)   /// [-62, -55][-54,-47][-46, -39][-39, -31]
     *    *(u64 *) (r10 + -136) := r1
     *    r1 := *(u8 *) (r10 + -63)
     *    *(u8 *) (r10 + -160) := r1
     * The unusual thing about the generated SBF code is that the offset r10-39 is read twice
     **/

    @Test
    fun test12() {
        println("====== TEST 12  (some byte is read/written twice) =======")
        /**
         *
         *  r1 := *(u64 *) (r10 + -62)
         *  *(u64 *) (r10 + -159) := r1
         *  r1 := *(u64 *) (r10 + -54)
         *  *(u64 *) (r10 + -151) := r1
         *  r1 := *(u64 *) (r10 + -46)
         *  *(u64 *) (r10 + -143) := r1
         *  r1 := *(u64 *) (r10 + -39)
         *  *(u64 *) (r10 + -136) := r1
         *  r1 := *(u64 *) (r10 + -70)
         *  *(u64 *) (r10 + -167) := r1
         *
         *  should be translated to
         *
         *  r1 := r10
         *  r1 := r1 + -167
         *  r2 := r10
         *  r2 := r2 + -70
         *  r3 := 39
         *  call sol_memcpy_(r1,r2,r3) /* promoted_stores_to_memcpy */
         *
         * Note that although there are 5 reads and writes of 8 bytes, we actually copy 39 bytes.
         **/

        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r1 = r10[-62]
                r10[-159] = r1
                r1 = r10[-54]
                r10[-151] = r1
                r1 = r10[-46]
                r10[-143] = r1
                r1 = r10[-39]
                r10[-136] = r1
                r1 = r10[-70]
                r10[-167] = r1
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test13() {
        /**
         *  This test shows an example where all the promoted load instructions must be **after* the inserted memcpy.
         *  Given this snippet:
         *
         *  ```
         *   r1 := r7
         *	 r1 := r1 + 4
         *	 r2 := *(u64 *) (r1 + 24)
         *	 *(u64 *) (r10 + -120) := r2
         *	 r2 := *(u64 *) (r1 + 16)
         *	 *(u64 *) (r10 + -128) := r2
         *	 r2 := *(u64 *) (r1 + 8)
         *   *(u64 *) (r10 + -136) := r2
         *   r1 := *(u64 *) (r1 + 0)
         *	 *(u64 *) (r10 + -144) := r1
         *  ```
         *
         *  if all the memory loads are left before the memcpy then we would get:
         *
         *  ```
         *   r1 := r7
         *	 r1 := r1 + 4
         *	 r2 := *(u64 *) (r1 + 24) <- dead
         *	 r2 := *(u64 *) (r1 + 16) <- dead
         *	 r2 := *(u64 *) (r1 + 8)  <- dead
         *	 r1 := *(u64 *) (r1 + 0)  <- no dead and doesn't preserve program equivalence
         *	 call CVT_save_scratch_registers
         *	 r6 := r1
         *	 r7 := r2
         *	 r8 := r3
         *	 r1 := r10
         * 	 r1 := r1 + -144
         *	 r2 := r6
         *	 r3 := 32
         *	 call sol_memcpy_(r1,r2,r3)
         *	 r1 := r6
         *	 r2 := r7
         *	 r3 := r8
         *	 call CVT_restore_scratch_registers
         *	```
         *  which does not preserve the original semantics.
         **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r1 = r7
                BinOp.ADD(r1, 4)
                r2 = r1[24]
                r10[-120] = r2
                r2 = r1[16]
                r10[-128] = r2
                r2 = r1[8]
                r10[-136] = r2
                r1 = r1[0]
                r10[-144] = r1
                r1 = r10
                BinOp.SUB(r1, 64)
                r2 = r10
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After removing useless definitions\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
        Assertions.assertEquals(true, getNumOfLoads(cfg) == 0)
    }

    @Test
    fun test14() {
        /**
         *  This test shows an example where the transformation cannot take place.
         *  ```
         *   r1 := *(u64 *) (r10 + -55)
         *   r2 := *(u64 *) (r10 + -63)
         *   r3 := *(u64 *) (r10 + -71)
         *   r4 := *(u64 *) (r10 + -79)
         *   r5 := *(u64 *) (r10 + -952)  (*)
         *   (u64 *) (r5 + 24) := r1
         *   (u64 *) (r5 + 16) := r2
         *   (u64 *) (r5 + 8) := r3
         *   (u64 *) (r5 + 0) := r4
         *  ```
         *  The problem is with the instruction (*) because it is re-defined r5 which is the base register of
         *  the stores. Note that a simple re-ordering (moving (*) before the first load) would enable the promotion.
         **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10[-55]
                r2 = r10[-63]
                r3 = r10[-71]
                r4 = r10[-79]
                r5 = r10[-952]
                r5[24] = r1
                r5[16] = r2
                r5[8] = r3
                r5[0] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemcpy(cfg)) // we expect no promotion
    }
    @Test
    fun test15() {
        /**
         * The following code is actually a memcpy from sp(7732) to sp(8152) of 32 bytes
         *
         * ```
         *   r2:top := *(u64 *) (r10 + -437):sp(7755)
         *   r1:top := *(u64 *) (r10 + -445):sp(7747)
         *   r3:top := *(u32 *) (r10 + -460):sp(7732) // this reads  7732,7733,7734, and 7735
         *   *(u32 *) (r10 + -40):sp(8152) := r3:top  // this stores 8152,8153,8154, and 8155
         *   r3:top := *(u32 *) (r10 + -457):sp(7735) // this reads again 7735
         *   *(u32 *) (r10 + -37):sp(8155) := r3:top  // this writes again 8155
         *   r3:top := *(u8 *) (r10 + -429):sp(7763)
         *   *(u8 *) (r10 + -9):sp(8183) := r3:top
         *   *(u64 *) (r10 + -17):sp(8175) := r2:top
         *   *(u64 *) (r10 + -25):sp(8167) := r1:top
         *   r1:top := *(u64 *) (r10 + -453):sp(7739)
         *   *(u64 *) (r10 + -33):sp(8159) := r1:top
         * ```
         *  Because one byte is read (written) twice the length of the memcpy is 32 instead of 33.
         *
         */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val l0 = Label.Address(1)
        val b0 = cfg.getOrInsertBlock(l0)
        cfg.setEntry(b0)

        b0.add(SbfInstruction.Bin(BinOp.ADD, r10, Value.Imm(4096UL), true))

        //  r2:top := *(u64 *) (r10 + -437):sp(7755)
        b0.add(SbfInstruction.Mem(Deref(8, r10, -437), r2, true))
        // r1:top := *(u64 *) (r10 + -445):sp(7747)
        b0.add(SbfInstruction.Mem(Deref(8, r10, -445), r1, true))
        // r3:top := *(u32 *) (r10 + -460):sp(7732)
        b0.add(SbfInstruction.Mem(Deref(4, r10, -460), r3, true))
        // *(u32 *) (r10 + -40):sp(8152) := r3:top
        b0.add(SbfInstruction.Mem(Deref(4, r10, -40), r3, false))
        // r3:top := *(u32 *) (r10 + -457):sp(7735)
        b0.add(SbfInstruction.Mem(Deref(4, r10, -457), r3, true))
        //  *(u32 *) (r10 + -37):sp(8155) := r3:top
        b0.add(SbfInstruction.Mem(Deref(4, r10, -37), r3, false))
        //  r3:top := *(u8 *) (r10 + -429):sp(7763)
        b0.add(SbfInstruction.Mem(Deref(1, r10, -429), r3, true))
        // *(u8 *) (r10 + -9):sp(8183) := r3:top
        b0.add(SbfInstruction.Mem(Deref(1, r10, -9), r3, false))
        // *(u64 *) (r10 + -17):sp(8175) := r2:top
        b0.add(SbfInstruction.Mem(Deref(8, r10, -17), r2, false))
        //*(u64 *) (r10 + -25):sp(8167) := r1:top
        b0.add(SbfInstruction.Mem(Deref(8, r10, -25), r1, false))
        // r1:top := *(u64 *) (r10 + -453):sp(7739)
        b0.add(SbfInstruction.Mem(Deref(8, r10, -453), r1, true))
        //  *(u64 *) (r10 + -33):sp(8159) := r1:top
        b0.add(SbfInstruction.Mem(Deref(8, r10, -33), r1, false))

        b0.add(SbfInstruction.Exit())

        println("Before promote stores to memcpy: $cfg")
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
        Assertions.assertEquals(true, getNumOfLoads(cfg) == 0)
    }

    @Test
    fun test16() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "malloc"()
                r8 = r0
                r1 = r10[-55]
                r2 = r10[-63]
                r3 = r10[-71]
                r4 = r10[-79]
                r8[24] = r1
                r8[16] = r2
                r8[8] = r3
                r8[0] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
        Assertions.assertEquals(true, getNumOfLoads(cfg) == 0)
    }


    @Test
    fun test17() {
        /**
         * Similar to test16 but we don't know where r8 points to.
         * However, with optimistic flag the transformation will still take place
         **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10[-55]
                r2 = r10[-63]
                r3 = r10[-71]
                r4 = r10[-79]
                r8[24] = r1
                r8[16] = r2
                r8[8] = r3
                r8[0] = r4
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> checkRegIsEqualAtAssert(
            cfg: SbfCFG,
            regTypes: AnalysisRegisterTypes<ScalarDomain<TNum, TOffset>, TNum, TOffset>,
            reg: SbfRegister, value: Long) {
        val b0 = cfg.getBlock(Label.Address(0))
        check (b0 != null)
        for (locInst in b0.getLocatedInstructions()) {
            if (locInst.inst.isAssertOrSatisfy()) {
                val type = regTypes.typeAtInstruction(locInst, reg)
                Assertions.assertEquals(type, sbfTypesFac.toNum(value))
            }
        }

    }

    @Test
    fun test18() {
        /** The transformation shouldn't take place **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r1 = r10
                r2 = r1[-104]
                assume(CondOp.EQ(r2, 0x0))
                r1[-104] = 1
                r10[-144] = r2
                r4 = r10[-144]
                assert(CondOp.EQ(r4, 0x0))
                exit()
            }
        }

        val scalarAnalysisOld = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypesOld = AnalysisRegisterTypes(scalarAnalysisOld)
        checkRegIsEqualAtAssert(cfg, regTypesOld, SbfRegister.R4_ARG, 0L)

        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysisOld)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemcpy(cfg))
    }

    @Test
    fun test19() {
        /** The transformation  should take place **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r10[-104] = 0
                r1 = r10
                r2 = r1[-104]
                r1[-104] = 1
                r10[-144] = r2
                r4 = r10[-144]
                assert(CondOp.EQ(r4, 0x0))
                exit()
            }
        }

        val scalarAnalysisOld = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypesOld = AnalysisRegisterTypes(scalarAnalysisOld)
        checkRegIsEqualAtAssert(cfg, regTypesOld, SbfRegister.R4_ARG, 0L)

        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysisOld)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After removing useless definitions\n$cfg")

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypes = AnalysisRegisterTypes(scalarAnalysis)
        checkRegIsEqualAtAssert(cfg, regTypes, SbfRegister.R4_ARG, 0L)
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test20() {
        /** The transformation should take place because we reorder loads before promoting **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r10[-144] = 0
                r1 = r10
                r2 = r1[-104]
                r4 = r10[-144]
                r10[-144] = r2
                assert(CondOp.EQ(r4, 0x0))
                exit()
            }
        }

        val scalarAnalysisOld = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypesOld = AnalysisRegisterTypes(scalarAnalysisOld)
        checkRegIsEqualAtAssert(cfg, regTypesOld, SbfRegister.R4_ARG, 0L)

        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysisOld)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test21() {
        /** The transformation should not take place **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r10[-144] = 0
                r1 = r10
                r2 = r1[-104]
                r1 = r10
                BinOp.SUB(r1, 512)
                r1[-144] = r2
                exit()
            }
        }

        val scalarAnalysisOld = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysisOld)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemcpy(cfg))
    }

    @Test
    fun test22() {
        /** The transformation should take place because we reorder loads before promoting **/
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 8192)
                r7 = r10[-100]
                r4 = r10[-144]
                r10[-144] = r7
                exit()
            }
        }

        val scalarAnalysisOld = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        val regTypesOld = AnalysisRegisterTypes(scalarAnalysisOld)
        checkRegIsEqualAtAssert(cfg, regTypesOld, SbfRegister.R4_ARG, 0L)

        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysisOld)
        }
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun test23() {
        /**
         * ```
         *  r1 := *(u16 *) (r10 + -39)
         *  *(u16 *) (r10 + -44) := r1
         *  r2 := *(u8 *) (r10 + -37)
         *  *(u8 *) (r10 + -42) := r2
         *  r3 := *(u64 *) (r10 + -36)
         *  *(u8 *) (r7 + 2) := r2
         *  *(u16 *) (r7 + 0) := r1
         *  *(u64 *) (r7 + 3) := r3
         * ```
         */
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)
        val r7 = Value.Reg(SbfRegister.R7)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val l0 = Label.Address(1)
        val b0 = cfg.getOrInsertBlock(l0)
        cfg.setEntry(b0)

        b0.add(SbfInstruction.Bin(BinOp.ADD, r10, Value.Imm(4096UL), true))
        //  r1 := *(u16 *) (r10 + -39)
        b0.add(SbfInstruction.Mem(Deref(2, r10, -39), r1, true))
        //  *(u16 *) (r10 + -44) := r1
        //b0.add(SbfInstruction.Mem(Deref(2, r10, -44), r1, false))
        //  r2 := *(u8 *) (r10 + -37)
        b0.add(SbfInstruction.Mem(Deref(1, r10, -37), r2, true))
        //  *(u8 *) (r10 + -42) := r2
        //b0.add(SbfInstruction.Mem(Deref(1, r10, -42), r2, false))
        //  r3 := *(u64 *) (r10 + -36)
        b0.add(SbfInstruction.Mem(Deref(8, r10, -36), r3, true))
        //  *(u8 *) (r7 + 2) := r2
        b0.add(SbfInstruction.Mem(Deref(1, r7, 2), r2, false))
        //  *(u16 *) (r7 + 0) := r1
        b0.add(SbfInstruction.Mem(Deref(2, r7, 0), r1, false))
        //  *(u64 *) (r7 + 3) := r3
        b0.add(SbfInstruction.Mem(Deref(8, r7, 3), r3, false))
        b0.add(SbfInstruction.Exit())

        println("Before promote stores to memcpy: $cfg")
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
        Assertions.assertEquals(true, getNumOfLoads(cfg) == 0)
    }

    @Test
    fun test24() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096UL * 3UL)
                r1 = r10
                r3 = r10
                BinOp.SUB(r1, 1184UL)
                BinOp.SUB(r3, 784UL)
                r2 = r3[0]
                r1[0] = r2
                r2 = r3[8]
                r1[8] = r2
                r2 = r3[16]
                r1[16] = r2
                r2 = r3[24]
                r1[24] = r2
                r1 = r10[-1168]
                r10[-1568] = r1
                exit()
            }
        }

        println("Before promote stores to memcpy: $cfg")
        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        promoteMemcpyIntraBlock(cfg, scalarAnalysis, aggressivePromotion = false)
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
        Assertions.assertEquals(true, getNumOfLoads(cfg) == 1)
    }

    /**
     * ```
     *   r6 = *(u64 *) (r10 - 300)
     *   r1 = 5
     *   CVT_save_scratch_registers
     *   r0 = r1
     *   r0 = r0 + 1
     *   CVT_restore_scratch_registers
     *   *(u64 *) (r10 -64) = r6
     * ```
     **/

    @Test
    fun `load and store via a scratch register with function in between`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r6 = r10[-300]
                r1 = 5
                push("foo", 1UL)
                BinOp.ADD(r10, 4096)
                r0 = r1
                BinOp.ADD(r0, 1)
                BinOp.SUB(r10, 4096)
                pop("foo", 1UL)
                r10[-64] = r6
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")
        Assertions.assertEquals(true, checkMemcpy(cfg))
    }

    @Test
    fun `narrowing store + memcpy of 16 bytes in same block`() {
        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val cfg = MutableSbfCFG("test")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)


        b1.add(SbfInstruction.Mem(Deref(8, r10, -24), r1, true))
        b1.add(SbfInstruction.Mem(Deref(2, r10, -524), r1, false))
        b1.add(SbfInstruction.Call("CVT_nondet_u16"))
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -56), r1, true))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -96), r1, false))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -48), r1, true))
        b1.add(SbfInstruction.Mem(Deref(8, r10, -88), r1, false))
        b1.add(SbfInstruction.Call("CVT_nondet_u16"))
        b1.add(SbfInstruction.Assume(Condition(CondOp.EQ, r0, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())
        cfg.normalize()
        cfg.verify(true)

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        ConfigScope(SolanaConfig.OptimisticMemcpyPromotion, true).use {
            promoteMemcpyIntraBlock(cfg, scalarAnalysis)
        }
        println("After transformation\n$cfg")
        removeUselessDefinitions(cfg)
        println("After remove useless loads transformation\n$cfg")

        Assertions.assertEquals(true, getNumOfLoads(cfg) == 0)
    }
}
