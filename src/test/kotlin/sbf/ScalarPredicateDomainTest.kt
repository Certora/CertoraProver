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
import sbf.cfg.*
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class ScalarPredicateDomainTest {

    private fun checkWithScalarPredicateAnalysis(cfg: SbfCFG,
                                                expectedResult: Boolean,
                                                maxVals: ULong = SolanaConfig.ScalarMaxVals.get().toULong()) {
        ConfigScope(SolanaConfig.UseScalarPredicateDomain, true).use {
            val prover = ScalarStackStridePredicateAnalysisProver(cfg, ConstantSetSbfTypeFactory(maxVals))
            prover.getChecks().forEach { check ->
                Assertions.assertEquals(expectedResult, check.result)
            }
        }
    }

    /**
     *  Example with loop (registers + stack)
     *
     *   ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      *r2 = 42
     *      r2 = r2 + 8
     *      r1 = r1 + 1
     *   }
     *   ```
     *
     *   The analysis can infer that `r2 = sp(3796) + (r1*8)`.
     *   With that invariant the analysis knows that all stack accesses are safe.
     **/
    @Test
    fun test1() {
        println("====== TEST 1  =======")
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(1) {
                r1 = 0
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r2[32] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 5), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.ADD(r2, 8)
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 5))
                r2 = r10
                BinOp.SUB(r2, 300)
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

    /**
    *  Example with loop (registers + stack)
    *  Same as test1 but the loop counter `r1` decreases
    *   ```
    *   r1 = 5
    *   r2 = sp(3796)
    *   while (r1 > 0) {
    *     *r2 = 42
    *     r2 = r2 + 8
    *     r1 = r1 - 1
    *   }
    *   ```
    **/
    @Test
    fun test2() {
        println("====== TEST 2  =======")
        val cfg = SbfTestDSL.makeCFG("test2") {
            bb(1) {
                r1 = 5
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r2[32] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.GT(r1, 0), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.ADD(r2, 8)
                BinOp.SUB(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 0))
                r2 = r10
                BinOp.SUB(r2, 300)
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

    /**
     * Example with loop (registers + stack)
     *   ```
     *   sp(3696) := 5
     *   r8 = sp(3796)
     *   r7 = 1
     *   do {
     *      r8 := r8 + 24
     *      *(r8+0) = 1
     *      *(r8+8) = 1
     *      *(r8+16) = 1
     *      r7 = r7 + 1
     *      r1 = *sp(3696)
     *  } while(r1 == r7)
     *   ```
     *
     *   The analysis can infer that `r8 = sp(3796) + (r7-1)*24`.
     *   With that invariant the analysis knows that all stack accesses are safe.
     *   However, note that the scalar analysis cannot infer that `*(r8+k) == 1` where `k = {0,8,16}`
     *   at the exit the loop. This is expected.
     *
     *   In the test below, we only prove as sanity check that `*(sp(3796) + 16) == 1` at the exit of the loop.
     **/
    @Test
    fun test3() {
        println("====== TEST 3  =======")
        val cfg = SbfTestDSL.makeCFG("test3") {
            bb(1) {
                r1 = 0
                r8 = r10
                BinOp.SUB(r8, 300)
                r7 = 1
                r10[-400] = 5
                r8[0] = 1
                r8[8] = 1
                r8[16] = 1
                goto (2)
            }
            bb(2) {
                BinOp.ADD(r8, 24)
                r8[0] = 1
                r8[8] = 1
                r8[16] = 1
                r1 = r10
                BinOp.SUB(r1, 400)
                r1 = r1[0]
                br(CondOp.NE(r1, r7), 3, 4)
            }
            bb(3) {
                BinOp.ADD(r7, 1)
                goto(2)
            }
            bb(4) {
                r2 = r10
                BinOp.SUB(r2, 300)
                r3 = r2[16]
                assert(CondOp.EQ(r3, 1))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

    /**
     *  Example with loop (registers + stack)
     *  As test1 but decrementing the stack pointer `r2`
     *   ```
     *   r1 = 0
     *   r2 = sp(3828)
     *   while (r1 < 5) {
     *      *r2 = 42
     *      r2 = r2 - 8
     *      r1 = r1 + 1
     *   }
     *   ```
     **/
    @Test
    fun test4() {
        println("====== TEST 4  =======")
        val cfg = SbfTestDSL.makeCFG("test4") {
            bb(1) {
                r1 = 0
                r2 = r10
                BinOp.SUB(r2, 268) // start at sp(3828)
                r2[0] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 5), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.SUB(r2, 8)
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 5))
                r2 = r10
                BinOp.SUB(r2, 268)
                r3 = r2[0]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

    /**
     *  Example with loop (registers + stack)
     *  Same as test1 but both loop counter `r1` and stack pointer `r2` decrease
     *   ```
     *   r1 = 5
     *   r2 = sp(3828)
     *   while (r1 > 0) {
     *     *r2 = 42
     *     r2 = r2 - 8
     *     r1 = r1 - 1
     *   }
     *   ```
     **/
    @Test
    fun test5() {
        println("====== TEST 5  =======")
        val cfg = SbfTestDSL.makeCFG("test5") {
            bb(1) {
                r1 = 5
                r2 = r10
                BinOp.SUB(r2, 268) // start at sp(3828)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r2[32] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.GT(r1, 0), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.SUB(r2, 8)
                BinOp.SUB(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 0))
                r2 = r10
                BinOp.SUB(r2, 268)
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }
    /**
     *   Exactly as test1 but r1 starts at 2 instead of 0.
     *
     *   ```
     *   r1 = 2
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      *r2 = 42
     *      r2 = r2 + 8
     *      r1 = r1 + 1
     *   }
     *   ```
    **/
    @Test
    fun test6() {
        println("====== TEST 6  =======")
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(1) {
                r1 = 2
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r2[32] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 5), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.ADD(r2, 8)
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 5))
                r2 = r10
                BinOp.SUB(r2, 300)
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

    /**
     *   Similar to test1 but accessing only every two words
     *   ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 10) {
     *      *r2 = 42
     *      r2 = r2 + 16
     *      r1 = r1 + 2
     *   }
     *   ```
     **/
    @Test
    fun test7() {
        println("====== TEST 7  =======")
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(1) {
                r1 = 0
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 0
                r2[8] = 0
                r2[16] = 0
                r2[24] = 0
                r2[32] = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 10), 3, 4)
            }
            bb(3) {
                r2[0] = 42
                BinOp.ADD(r2, 16)
                BinOp.ADD(r1, 2)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 10))
                r2 = r10
                BinOp.SUB(r2, 300)
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarPredicateAnalysis(cfg, true, 20UL)
    }

}
