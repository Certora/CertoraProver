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

import config.Config
import config.ConfigScope
import config.ConfigScope.Companion.invoke
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.support.UnknownStackContentError
import sbf.tac.TACTranslationError
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import sbf.SolanaConfig.ForgetOnUntrackedStackLoad
import sbf.tac.levelZeroOptimizations
import sbf.testing.SbfTestDSL
import kotlin.booleanArrayOf

class TACStoresTest {

    @Test
    fun test01() {
        // r1 points to the stack
        /*
           r1 := r10
	       r1 := r1 - 100
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 5) := 5
	       r2 := *(u64 *) (r1 + 0)
	       // This assertion is not true because the second store overlaps with the first store
	       // The pointer analysis will complain that we read from some stack offset which becomes inaccessible.
	       assert(r2 == 0)
         */

        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test1")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r1, Value.Imm(100UL), true))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 4), Value.Imm(5UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r2, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r2, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())

        cfg.normalize()
        cfg.verify(true)

        expectException<UnknownStackContentError> {
            ConfigScope(ForgetOnUntrackedStackLoad, false).use {
                ConfigScope(SolanaConfig.MemorySplitter, MemorySplitter.PTA).use {
                    toTAC(cfg)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test02(usePTA: Boolean) {
        // r1 points to the heap
        /*
           r2 := r10
	       r2 := r2 - 100

	       r1 := 32
	       malloc()
	       r1 := r0
	       *(u64 *) (r2 + 0) := r1
	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 8) := 5
	       r3 := *(u64 *) (r1 + 0)
	       assert(r3 == 0)
         */

        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test2")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r0, true))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 8), Value.Imm(5UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r3, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())

        cfg.normalize()
        cfg.verify(true)

        val memSplitter = if (usePTA) { MemorySplitter.PTA }  else { MemorySplitter.Scalar }
        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test03(usePTA: Boolean) {
        // r1 points to the heap
        /*
           r2 := r10
	       r2 := r2 - 100

	       r1 := 32
	       malloc()
	       r1 := r0
	       *(u64 *) (r2 + 0) := r1

	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 4) := 5
	       r3 := *(u64 *) (r1 + 0)
	       // This is not provable because the second store overlaps with the first store
	       assert(r3 == 0)
         */

        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test3")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r0, true))

        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 4), Value.Imm(5UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r3, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())

        cfg.normalize()
        cfg.verify(true)

        val memSplitter = if (usePTA) { MemorySplitter.PTA }  else { MemorySplitter.Scalar }
        val expectedRes = if (usePTA) { false  /* assertion does not hold */ } else { true /* assertion incorrectly holds */ }
        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(expectedRes, verify(tacProg))
        }
    }

    @Test
    fun test04() {
        // r1 points to the heap but the node is summarized
        /*
           r2 := r10
	       r2 := r2 - 100

	       r1 := 32
	       malloc()
	       r1 := r0
	       some operation that summarizes the points-to node pointed by r1
	       *(u64 *) (r2 + 0) := r1

	       *(u64 *) (r1 + 0) := 0
	       *(u64 *) (r1 + 4) := 5
	       r3 := *(u64 *) (r1 + 0)
	       // This is not provable because the second store overlaps with the first store
	       // Because the verifier cannot reason precisely about overlaps it should throw an exception.
	       assert(r3 == 0)
         */

        val r0 = Value.Reg(SbfRegister.R0)
        val r1 = Value.Reg(SbfRegister.R1)
        val r2 = Value.Reg(SbfRegister.R2)
        val r3 = Value.Reg(SbfRegister.R3)
        val r4 = Value.Reg(SbfRegister.R4)
        val r5 = Value.Reg(SbfRegister.R5)
        val r10 = Value.Reg(SbfRegister.R10)
        val cfg = MutableSbfCFG("test4")
        val b1 = cfg.getOrInsertBlock(Label.Address(1))
        cfg.setEntry(b1)

        b1.add(SbfInstruction.Bin(BinOp.MOV, r2, r10, true))
        b1.add(SbfInstruction.Bin(BinOp.SUB, r2, Value.Imm(100UL), true))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, Value.Imm(32UL), true))
        b1.add(SbfInstruction.Call(name = "__rust_alloc"))
        b1.add(SbfInstruction.Bin(BinOp.MOV, r1, r0, true))
        b1.add(SbfInstruction.Mem(Deref(8, r2, 0), r1, false))

        b1.add(SbfInstruction.Bin(BinOp.MOV, r4, r1, true))
        b1.add(SbfInstruction.Havoc(r5))
        // r4 points somewhere inside the object allocated by alloc, but we don't know where
        b1.add(SbfInstruction.Bin(BinOp.ADD, r4, r5, true))
        // This will summarize the points-to node associated to the allocated object by alloc.
        b1.add(SbfInstruction.Mem(Deref(8, r4, 0), Value.Imm(10UL), false))

        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), Value.Imm(0UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 4), Value.Imm(5UL), false))
        b1.add(SbfInstruction.Mem(Deref(8, r1, 0), r3, true))
        b1.add(SbfInstruction.Assert(Condition(CondOp.EQ, r3, Value.Imm(0UL))))
        b1.add(SbfInstruction.Exit())

        cfg.normalize()
        cfg.verify(true)

        expectException<TACTranslationError> {
            ConfigScope(SolanaConfig.DefactoSemantics, false).use {
                ConfigScope(SolanaConfig.MemorySplitter, MemorySplitter.PTA).use {
                    toTAC(cfg)
                }
            }
        }
    }

    /**
     *  Example with loop (registers + stack)
     *   ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      r3 = r2 + (r1 * 8)
     *      *r3 = 42          <-- it needs to be modeled with ite's
     *      r1 = r1 + 1
     *   }
     *
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      r3 = r2 + (r1 * 8)
     *      assert(*r3 == 42)  <--- it needs to be modeled with ite's
     *      r1 = r1 + 1
     *   }
     *   ```
     **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test5(usePTA: Boolean) {
        println("====== TEST 5  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = 0
                r2 = r10
                BinOp.SUB(r2, 300)
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 5), 3, 4)
            }
            bb(3) {
                BinOp.MOV(r3, r1)
                BinOp.MUL(r3, 8)
                BinOp.ADD(r3, r2)
                r3[0] = 42
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                r4 = 0
                goto (5)
            }
            bb(5) {
                br(CondOp.LT(r4, 5), 6, 7)
            }
            bb(6) {
                BinOp.MOV(r6, r4)
                BinOp.MUL(r6, 8)
                BinOp.ADD(r6, r2)
                r7 = r6[0]
                assert(CondOp.EQ(r7, 42))
                BinOp.ADD(r4, 1)
                goto(5)
            }
            bb(7) {
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        cfg.normalize()
        cfg.verify(true)

        val memSplitter = if (usePTA) { MemorySplitter.PTA }  else { MemorySplitter.Scalar }
        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            ConfigScope(SolanaConfig.OptimisticPTAOverlaps, true).use {
                val tacProg = toTAC(cfg)
                println("Before loop unrolling\n" + dumpTAC(tacProg))
                ConfigScope(Config.LoopUnrollConstant, 5).use {
                    val loopFreeTacProg = levelZeroOptimizations(tacProg, false)
                    println("After loop unrolling\n" + dumpTAC(loopFreeTacProg))
                    Assertions.assertEquals(true, verify(loopFreeTacProg))
                }
            }
        }
    }

    /**
     *  ```
     *   if (r1 == 0) {
     *     r2 = r10 - 100
     *   } else {
     *     r2 = r10 - 200
     *   }
     *   *r2 = 42        <-- it needs to be modeled with ite
     *   if (r1 == 0) {
     *      assert(*(r10-100) == 42)
     *   } else {
     *      assert(*(r10-200) == 42)
     *   }
     *  ```
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test6(usePTA: Boolean) {
        println("====== TEST 6  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r2 = r10
                br(CondOp.EQ(r1, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r2, 100)
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r2, 200)
                goto (4)
            }
            bb(4) {
                r2[0] = 42
                goto(5)
            }
            bb(5) {
                br(CondOp.EQ(r1, 0), 6, 7)
            }
            bb(6) {
                r3 = r10[-100]
                assert(CondOp.EQ(r3, 42))
                goto(8)
            }
            bb(7) {
                r3 = r10[-200]
                assert(CondOp.EQ(r3, 42))
                goto(8)
            }
            bb(8) {
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        cfg.normalize()
        cfg.verify(true)

        val memSplitter = if (usePTA) { MemorySplitter.PTA }  else { MemorySplitter.Scalar }
        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            ConfigScope(SolanaConfig.OptimisticPTAOverlaps, true).use {
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(true, verify(tacProg))
            }
        }
    }

    /**
     * Single heap write + read. Exercises the scalar splitter's non-stack store/load path under both
     * per-region (`Scalar`) and single-map (`ScalarSingle`) modes.
     *
     * ```
     * r1 := alloc(32)
     * *r1 := 42
     * r2 := *r1
     * assert(r2 == 42)
     * ```
     */
    @ParameterizedTest
    @EnumSource(value = MemorySplitter::class, names = ["Scalar", "ScalarSingle"])
    fun test7(memSplitter: MemorySplitter) {
        println("====== TEST 7 ($memSplitter) =======")
        val cfg = SbfTestDSL.makeCFG("test7") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r1 = r0
                r1[0] = 42
                r2 = r1[0]
                assert(CondOp.EQ(r2, 42))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)

        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /**
     * Multiple non-overlapping heap writes and reads at distinct word-aligned offsets. Exercises a
     * longer non-stack store chain under both per-region (`Scalar`) and single-map (`ScalarSingle`) modes.
     *
     * ```
     * r1 := alloc(32)
     * *(r1 + 0)  := 10
     * *(r1 + 8)  := 20
     * *(r1 + 16) := 30
     * r2 := *(r1 + 0);  assert(r2 == 10)
     * r3 := *(r1 + 8);  assert(r3 == 20)
     * r4 := *(r1 + 16); assert(r4 == 30)
     * ```
     */
    @ParameterizedTest
    @EnumSource(value = MemorySplitter::class, names = ["Scalar", "ScalarSingle"])
    fun test8(memSplitter: MemorySplitter) {
        println("====== TEST 8 ($memSplitter) =======")
        val cfg = SbfTestDSL.makeCFG("test8") {
            bb(1) {
                r1 = 32
                "__rust_alloc"()
                r1 = r0
                r1[0]  = 10
                r1[8]  = 20
                r1[16] = 30
                r2 = r1[0]
                r3 = r1[8]
                r4 = r1[16]
                assert(CondOp.EQ(r2, 10))
                assert(CondOp.EQ(r3, 20))
                assert(CondOp.EQ(r4, 30))
                exit()
            }
        }
        cfg.normalize()
        cfg.verify(true)

        ConfigScope(SolanaConfig.MemorySplitter, memSplitter).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }
}
