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
import sbf.support.UnknownStackPointerError

class ScalarDomainConstantSetTest {

    private fun checkWithScalarAnalysis(cfg: SbfCFG,
                                        expectedResult: Boolean,
                                        maxVals: ULong = SolanaConfig.ScalarMaxVals.get().toULong()) {
        val prover = ScalarAnalysisProver(cfg, ConstantSetSbfTypeFactory(maxVals))
        prover.getChecks().forEach {  check ->
            Assertions.assertEquals(expectedResult, check.result)
        }
    }

    private fun checkWithAdaptiveScalarAnalysis(cfg: SbfCFG, expectedResult: Boolean) {
        val prover = AdaptiveScalarAnalysisProver(cfg)
        prover.getChecks().forEach {  check ->
            Assertions.assertEquals(expectedResult, check.result)
        }
    }

    // simple diamond
    @Test
    fun test1() {
        println("====== TEST 1  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                br(CondOp.EQ(r1, 0), 2, 3)
            }
            bb(2) {
                r2  = 0
                goto(4)
            }
            bb(3) {
                r2 = 1
                goto(4)
            }
            bb(4) {

                assert(CondOp.GE(r2, 0))
                assert(CondOp.LE(r2, 1))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, true)
    }

    /** Example where using ConstantSet for stack offsets instead of Constant makes a big difference (avoid analysis error) **/
    @Test
    fun test2() {
        println("====== TEST 2 =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                r10[-500] = 42
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r1, 200)
                r1[0] = 5
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r1, 300)
                r1[0] = 10
                goto(4)
            }
            bb(4) {
                // These assertions are not true
                //r3 = r1[0]
                //assert(CondOp.GE(r3, 5))
                //assert(CondOp.LE(r3, 10))
                r1[0] = 0
                r3 = r10[-500]
                assert(CondOp.EQ(r3, 42))
                exit()
            }
        }
        println("$cfg")

        // With non-disjunctive offsets, the scalar analysis throw an exception
        run {
            var exception = false
            try {
                ScalarAnalysisProver(cfg, ConstantSbfTypeFactory())
            } catch (e: UnknownStackPointerError) {
                exception = true
            }
            Assertions.assertEquals(true, exception)
        }

        // With disjunctive offsets, the scalar analysis should NOT throw an exception
        // and prove the assertion
        run {
            checkWithScalarAnalysis(cfg, true)
        }
    }

    // Example where AdaptiveScalarAnalysis is needed
    // Same as test02
    @Test
    fun test3() {
        println("====== TEST 3 =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                r10[-500] = 42
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r1, 200)
                r1[0] = 5
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r1, 300)
                r1[0] = 10
                goto(4)
            }
            bb(4) {
                // These assertions are not true
                //r3 = r1[0]
                //assert(CondOp.GE(r3, 5))
                //assert(CondOp.LE(r3, 10))
                r1[0] = 0
                r3 = r10[-500]
                assert(CondOp.EQ(r3, 42))
                exit()
            }
        }
        println("$cfg")
        checkWithAdaptiveScalarAnalysis(cfg, true)
    }

    // simple diamond
    @Test
    fun test4() {
        println("====== TEST 4  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                r10[-500] = 42
                r10[-200] = 0
                r10[-300] = 0
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r1, 200)
                r1[0] = 5
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r1, 300)
                r1[0] = 10
                goto(4)
            }
            bb(4) {
                // These assertions are true
                r3 = r1[0]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                r1[0] = 0
                r3 = r10[-500]
                assert(CondOp.EQ(r3, 42))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, true)
    }

    // simple diamond
    @Test
    fun test5() {
        println("====== TEST 5  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                //r10[-500] = 42
                r10[-200] = 5
                r10[-300] = 10
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                BinOp.SUB(r1, 200)
                r10[-400] = r1
                goto(4)
            }
            bb(3) {
                BinOp.SUB(r1, 300)
                r10[-400] = r1
                goto(4)
            }
            bb(4) {
                r1 = r10[-400]
                // These assertions are true
                r3 = r1[0]
                assert(CondOp.GE(r3, 5))
                assert(CondOp.LE(r3, 10))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, true)
    }

    // memcpy: two (uninitialized) sources, two (uninitialized) destinations
    @Test
    fun test6() {
        println("====== TEST 6  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 200)
                r2[0] = 5
                r2[8] = 5
                r2[16] = 5
                r1 = r10
                BinOp.SUB(r1, 500)
                goto(4)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 5
                r2[8] = 5
                r2[16] = 5
                r1 = r10
                BinOp.SUB(r1, 600)
                goto(4)
            }
            bb(4) {
                r3 = 24
                "sol_memcpy_"()
                // These assertions are not true
                r3 = r1[0]
                assert(CondOp.EQ(r3, 5))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, false)
    }



    // memcpy: two (initialized) sources, one destination
    @Test
    fun test7() {
        println("====== TEST 7  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                r1 = r10
                BinOp.SUB(r1, 300)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 200)
                r2[0] = 5
                r2[8] = 5
                r2[16] = 5
                r1 = r10
                BinOp.SUB(r1, 500)
                goto(4)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 10
                r2[8] = 10
                r2[16] = 10
                r1 = r10
                BinOp.SUB(r1, 500)
                goto(4)
            }
            bb(4) {
                r3 = 24
                "sol_memcpy_"()
                // These assertions are true
                r3 = r1[0]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                r3 = r1[8]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                r3 = r1[16]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, true)
    }

    // memcpy: two (initialized) sources, two (uninitialized) destinations
    @Test
    fun test8() {
        println("====== TEST 8  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                r1 = r10
                BinOp.SUB(r1, 300)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 200)
                r2[0] = 5
                r2[8] = 5
                r2[16] = 5
                r1 = r10
                BinOp.SUB(r1, 500)
                goto(4)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 10
                r2[8] = 10
                r2[16] = 10
                r1 = r10
                BinOp.SUB(r1, 600)
                goto(4)
            }
            bb(4) {
                r3 = 24
                "sol_memcpy_"()
                // These assertions are true
                r3 = r1[0]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                r3 = r1[8]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                r3 = r1[16]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 10))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, false)
    }

    // memcpy: two (initialized) sources, two (initialized) destinations
    @Test
    fun test9() {
        println("====== TEST 9  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                r1 = r10
                BinOp.SUB(r1, 300)
                r1[0] = 0
                r1[8] = 0
                r1[16] = 0
                r1 = r10
                BinOp.SUB(r1, 500)
                r1[0] = 42
                r1[8] = 42
                r1[16] = 42
                r1 = r10
                BinOp.SUB(r1, 600)
                r1[0] = 42
                r1[8] = 42
                r1[16] = 42
                br(CondOp.EQ(r2, 0), 2, 3)
            }
            bb(2) {
                r2 = r10
                BinOp.SUB(r2, 200)
                r2[0] = 5
                r2[8] = 5
                r2[16] = 5
                r1 = r10
                BinOp.SUB(r1, 500)
                goto(4)
            }
            bb(3) {
                r2 = r10
                BinOp.SUB(r2, 300)
                r2[0] = 10
                r2[8] = 10
                r2[16] = 10
                r1 = r10
                BinOp.SUB(r1, 600)
                goto(4)
            }
            bb(4) {
                r3 = 24
                "sol_memcpy_"()
                // These assertions are true
                r3 = r1[0]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 42))
                r3 = r1[8]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 42))
                r3 = r1[16]
                assert(CondOp.GE(r3, 0))
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        println("$cfg")
        checkWithScalarAnalysis(cfg, true)
    }



    // Simple example with loop (only registers, no stack)
    @Test
    fun test10() {
        println("====== TEST 10  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
            bb(1) {
                r1 = 0
                goto (2)
            }
            bb(2) {
                br(CondOp.LT(r1, 10), 3, 4)
            }
            bb(3) {
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 10))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        println("Running scalar analysis with maxVals=11")
        checkWithScalarAnalysis(cfg, true, 11UL)
        println("Running scalar analysis with maxVals=10")
        checkWithScalarAnalysis(cfg, false, 10UL)
    }

    /**
     *  Example with loop (registers + stack)
     *   ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      r3 = r2 + (r1 * 8)
     *      *r3 = 42
     *      r1 = r1 + 1
     *   }
     *   ```
    **/
    @Test
    fun test11() {
        println("====== TEST 11  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
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
                BinOp.MOV(r3, r1)
                BinOp.MUL(r3, 8)
                BinOp.ADD(r3, r2)
                r3[0] = 42
                BinOp.ADD(r1, 1)
                goto(2)
            }
            bb(4) {
                assert(CondOp.EQ(r1, 5))
                r3 = r2[16]
                assert(CondOp.LE(r3, 42))
                exit()
            }
        }
        cfg.lowerBranchesIntoAssume()
        println("$cfg")
        checkWithScalarAnalysis(cfg, true, 20UL)
    }

    /**
     *  Example with loop (registers + stack)
     *  Although this example is equivalent to test11, the scalar analysis cannot handle this case.
     *   ```
     *   r1 = 0
     *   r2 = sp(3796)
     *   while (r1 < 5) {
     *      r2 = r2 + 8
     *      *r2 = 42
     *      r1 = r1 + 1
     *   }
     *   ```
     **/
    @Test
    fun test12() {
        println("====== TEST 12  =======")
        val cfg = SbfTestDSL.makeCFG("test") {
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
                BinOp.ADD(r2, 8)
                r2[0] = 42
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

        var exception = false
        try {
            checkWithScalarAnalysis(cfg, true, 20UL)
        } catch (e: UnknownStackPointerError) {
            exception = true
        }
        Assertions.assertEquals(true, exception)
    }

}
