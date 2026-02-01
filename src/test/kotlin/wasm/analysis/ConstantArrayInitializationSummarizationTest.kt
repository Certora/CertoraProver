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

package wasm.analysis

import analysis.Loop
import analysis.TACCommandGraph
import analysis.getNaturalLoops
import analysis.loop.LoopHoistingOptimization
import analysis.loop.LoopSummarization
import analysis.smtblaster.Z3BlasterPool
import org.junit.jupiter.api.*
import parallel.ParallelPool
import tac.Tag
import testing.ttl.TACMockLanguage
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACSymbolTable
import wasm.certoraAssert
import wasm.certoraAssume
import wasm.host.soroban.SorobanImport
import wasm.host.soroban.SorobanTestFixture
import wasm.host.soroban.Val
import wasm.host.soroban.invoke
import wasm.wat.*
import java.math.BigInteger

class ConstantArrayInitializationSummarizationTest: SorobanTestFixture() {
    private fun TACMockLanguage.StmtBuilderScope.simpleLoopCFG(
        stride: Int,
        iter: Int,
        init: Boolean = true,
        write: Any = 2,
        inner: TACMockLanguage.StmtBuilderScope.() -> Unit
    ) {
        val bound = stride * iter
        if (init) {
            L1021 = 0
        }
        `while`(L(1020, Tag.Bool), "L1021 < 0x${bound.toString(16)}") {
            L1024 = "L1022 + L1021"
            mem[L1024] = write
            inner()
            L1023 = "L1021 + 0x${stride.toString(16)}"
            L1021 = L1023
        }
    }

    @Test
    fun weirdStride() {
        // This test is on internal behavior, namely that the
        // "off-stride" locations are havoc'd. This is an over approximation,
        // so perhaps one day this test will break if our memory model changes.
        val test = { shouldEq: Boolean ->
            watFunc("entry", i32, i32) { p, probeIdx ->
                val i = local(i32(0))
                val old = local(i32)
                old.set(i32.load(p plus probeIdx))
                block {
                    certoraAssume(i32(0) le p)
                    certoraAssume(p le i32(100))
                    certoraAssume((probeIdx rem i32(8)) ne i32(0))
                    certoraAssume(probeIdx lt i32(40))
                    certoraAssume(i32(1) le probeIdx)

                    loop {
                        i32.store(p plus i, i32(2))
                        i.set(i plus i32(8))
                        branchIf(i ne i32(40))
                    }
                }
                if (shouldEq) {
                    certoraAssert(
                        i32.load(p plus probeIdx) eq old
                    )
                } else {
                    certoraAssert(
                        i32.load(p plus probeIdx) ne old
                    )
                }
            }
        }

        // for an arbitrary value, there should be a run where we do _not_ read it
        Assertions.assertFalse(
            verifyWasm(test(true), "entry", assumeNoTraps = true)
        )
        // for an arbitrary value, there should be a run where we _do_ read it
        Assertions.assertFalse(
            verifyWasm(test(false), "entry", assumeNoTraps = true)
        )
    }

        class ContextModuleTest : SorobanTestFixture() {
            @Test
            fun log_from_linear_memory() {
                Assertions.assertTrue(verifyWasm {
                    SorobanImport.Context.log_from_linear_memory(
                        Val.Tag.U32Val(0),
                        Val.Tag.U32Val(0),
                        Val.Tag.U32Val(0),
                        Val.Tag.U32Val(0)
                    )
                })
            }
    }

    @Test
    fun constantLoop() {
        val strides = listOf(1, 8, 16)
        val iter = listOf(1, 2, 5)
        for (s in strides) {
            for (i in iter) {
                val cfg = TACMockLanguage.make { simpleLoopCFG(s, i) {} }
                singleLoopTest(cfg, stride = s.toBigInteger(), iter = i.toBigInteger()) {
                    "Failed for stride=$s iterations=$i"
                }
            }
        }
    }

    @Test
    fun loopWithOffset() {
        val stride = 8
        val iter = 6
        val cfg = TACMockLanguage.make {
            val bound = stride * iter
            L1021 = 0
            `while`(L(1020, Tag.Bool), "L1021 < 0x${bound.toString(16)}") {
                L1022 = "L100 + 0x8"
                L1024 = "L1022 + L1021"
                mem[L1024] = 0x2
                L1023 = "L1021 + 0x${stride.toString(16)}"
                L1021 = L1023
            }
        }
        singleLoopTest(cfg, stride = stride.toBigInteger(), iter = iter.toBigInteger()) {
            "Failed with offset"
        }
    }

    @Test
    fun nestedLoopsOuter() {
        val strides = listOf(1, 8, 16)
        val iter = listOf(1, 2, 5)
        for (s in strides) {
            for (i in iter) {
                val cfg = TACMockLanguage.make {
                    simpleLoopCFG(s, i) {
                        `while`(L(1025, Tag.Bool), "L1026 == L1027") {
                            L(1026) `=` `*`
                            L(1027) `=` `*`
                        }
                    }
                }
                singleLoopTest(cfg, stride = s.toBigInteger(), iter = i.toBigInteger()) {
                    "Failed for stride=$s iterations=$i"
                }
            }
        }
    }


    @Test
    fun nestedLoopsInner() {
        val strides = listOf(1, 8, 16)
        val iter = listOf(1, 2, 5)
        for (s in strides) {
            for (i in iter) {
                val cfg = TACMockLanguage.make {
                    `while`(L(1025, Tag.Bool), "L1026 == L1027") {
                        L(1026) `=` `*`
                        simpleLoopCFG(s, i) {}
                        L(1027) `=` `*`
                    }
                }
                singleLoopTest(cfg, stride = s.toBigInteger(), iter = i.toBigInteger()) {
                    "Failed for stride=$s iterations=$i"
                }
            }
        }
    }

    @Test
    fun emptyLoop() {
        val strides = listOf(1, 8, 16)
        val iter = listOf(0)
        for (s in strides) {
            for (i in iter) {
                val cfg = TACMockLanguage.make { simpleLoopCFG(s, i) {} }
                val res = runAnalysis(cfg)
                Assertions.assertTrue(res.isEmpty())
            }
        }
    }

    @Test
    fun liveModifiedVars() {
        val cfg = TACMockLanguage.make {
            simpleLoopCFG(8, 3) { }
            L1022 = L1021
        }
        val (_, summ) = runAnalysis(cfg).entries.singleOrNull() ?: Assertions.fail { "Did not find a singleton summary"}
        Assertions.assertFalse(
            summ.isValidSimpleInitialization(cfg)
        )
    }

    @Test
    fun notLoopInvariantStore() {
        val cfg = TACMockLanguage.make {
            simpleLoopCFG(8, 3, write=L(1025)) {
                L(1025) `=` 0
                `while`(L(1026, Tag.Bool), "L1025 < L1021") {
                    L(1027) `=` "L1025 + 0x1"
                    L(1025) `=` L(1027)
                }
            }
        }
        val (_, summ) = runAnalysis(cfg).entries.singleOrNull() ?: Assertions.fail { "Did not find a singleton summary" }
        Assertions.assertFalse(
            summ.isValidSimpleInitialization(cfg)
        )
    }


    @Test
    fun notZeroInit() {
        val cfg1 = TACMockLanguage.make {
            simpleLoopCFG(8, 3, init=false) {}
        }
        val cfg2 = TACMockLanguage.make {
            L1021 = `*`
            `if`(1020, `*`) {
                L1021 = 0
            }`else`{

            }
            simpleLoopCFG(8, 3, init=false) {}
        }
        val cfg3 = TACMockLanguage.make {
            L1021 = 0
            `while`(L(1020, Tag.Bool), "L100 < L200") {
                simpleLoopCFG(8, 3, init=false) {}
                L1021 = 1
                L(100) `=` "L100 + 0x1"
            }
        }
        for ((i, cfg) in listOf(cfg1, cfg2, cfg3).withIndex()) {
            val (_, summ) = runAnalysis(cfg).entries.singleOrNull() ?: Assertions.fail { "Did not find a singleton summary" }
            Assertions.assertFalse(
                summ.isValidSimpleInitialization(cfg)
            ) {
                "Found that cfg${i+1} is incorrectly valid"
            }

        }
    }

    private fun singleLoopTest(cfg: TACCommandGraph, stride: BigInteger, iter: BigInteger, msg:() -> String) {
        val syms = cfg.commands.flatMapToSet {
            it.cmd.getFreeVarsOfRhs() + setOfNotNull(it.cmd.getLhs())
        }
        val core = CoreTACProgram(
            code = cfg.code,
            blockgraph = cfg.blockGraph,
            name = "test",
            symbolTable = TACSymbolTable.withVars(syms),
            procedures = setOf(),
            check = false
        )
        val core2 = LoopHoistingOptimization.hoistLoopComputations(core)
        val res = runAnalysis(core2.analysisCache.graph)
        val summary = res.values.singleOrNull()
            ?: Assertions.fail { "Did not get a summary: ${msg()}" }
        Assertions.assertEquals(iter, summary.iterations) { msg() }
        Assertions.assertEquals(stride, summary.stride) { msg() }
        Assertions.assertTrue(summary.isValidSimpleInitialization(cfg))
    }

    private fun runAnalysis(cfg: TACCommandGraph): Map<Loop, ConstArrayInitSummary> {
        val loops = getNaturalLoops(cfg)
        val analysis = ParallelPool.allocInScope(2000, { timeout -> Z3BlasterPool(z3TimeoutMs = timeout) }) { blaster ->
            val summarizer = LoopSummarization(cfg, blaster, loops)
            ConstantArrayInitializationSummarizer.ConstantArrayInitLoopAnalysis(
                cfg,
                ConstantArrayInitializationSummarizer.SyntacticConditionLengthHeuristic,
                loops,
                summarizer,
                blaster,
            )
        }
        return loops.associateWithNotNull {
           analysis.loopSummary(it)
        }
    }
}
