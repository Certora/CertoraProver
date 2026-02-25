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

private val sbfTypesFac = ConstantSbfTypeFactory()
private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()
private val opts = MemsetPromotionOpts(2)

class PromoteMemsetTest {

    private fun checkMemset(cfg: SbfCFG): Boolean =
        cfg.getEntry().getInstructions().any { inst ->
            inst is SbfInstruction.Call && inst.name == "sol_memset_"
        }

    private fun getNumOfStores(cfg: SbfCFG): Int =
        cfg.getEntry().getInstructions().count { inst ->
            inst is SbfInstruction.Mem && !inst.isLoad
        }


    @Test
    fun `four stores of zero`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-56] = 0
                r10[-48] = 0
                r10[-40] = 0
                r10[-64] = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 0)
    }

    @Test
    fun `four stores of -1`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-56] = -1
                r10[-48] = -1
                r10[-40] = -1
                r10[-64] = -1
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 0)
    }

    @Test
    fun `four stores of 1`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-56] = 1
                r10[-48] = 1
                r10[-40] = 1
                r10[-64] = 1
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(false, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 4)
    }

    @Test
    fun `four stores of zero but not consecutive bytes`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r10[-56] = 0
                r10[-48] = 0
                r10[-200] = 0
                r10[-64] = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        // We could promote the first two stores to a memset of 16 but `promoteMemset` is greedy,
        // and it doesn't do that at the moment.
        Assertions.assertEquals(false, checkMemset(cfg))
    }

    @Test
    fun `four stores of zero with irrelevant instructions in the middle`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1 = 0
                r10[-56] = 0
                BinOp.ADD(r1, 1)
                r10[-48] = 0
                BinOp.ADD(r1, 1)
                r10[-40] = 0
                BinOp.ADD(r1, 1)
                r10[-64] = 0
                BinOp.ADD(r1, 1)
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 0)
    }

    @Test
    fun `three stores of zero but with a store using same base register`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r2 = r10
                r2[-56] = 0
                r2[-48] = 0
                r2[-200] = r1
                r2[-40] = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        // Here promoteMemset promotes the first two stores to a memset of 16 bytes and leave unchanged
        // the other two stores
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 2)
    }

    @Test
    fun `two promotable set of stores in the same block (1)`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1= r10
                r1[-56] = 0
                r1[-48] = 0
                r1[-40] = 0
                r2 = 0
                BinOp.ADD(r2, 1)
                r3 = r10
                r3[-200] = 0
                r3[-192] = 0
                r3[-208] = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 0)
    }

    @Test
    fun `two promotable set of stores in the same block (2)`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                r1= r10
                r1[-56] = 0
                r1[-48] = 0
                r1[-40] = 0
                r3 = r10
                r3[-200] = 0
                r3[-192] = 0
                r3[-208] = 0
                exit()
            }
        }

        val scalarAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac)
        println("Before transformation\n$cfg")
        promoteMemset(cfg, scalarAnalysis, opts)
        println("After transformation\n$cfg")
        Assertions.assertEquals(true, checkMemset(cfg))
        Assertions.assertEquals(true, getNumOfStores(cfg) == 0)
    }

}
