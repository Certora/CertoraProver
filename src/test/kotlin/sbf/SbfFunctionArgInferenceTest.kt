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

import sbf.analysis.FunctionArgumentInference
import sbf.callgraph.*
import sbf.inliner.EmptyInlinerConfig
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.inliner.inline
import sbf.slicer.sliceAssertions
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.testing.SbfTestDSL

class SbfFunctionArgInferenceTest {

    private fun compileCFG(cfgs: List<MutableSbfCFG>, root: String): SbfCallGraph {
        val globals = GlobalVariables(DefaultElfFileView)
        val outcfgs = mutableListOf(cfgs.first())
        for (cfg in cfgs) {
            cfg.verify(false, "[before postProcessCFG]")
            cfg.lowerBranchesIntoAssume()
            cfg.verify(false, "[after lowering branches into assumes]")
            cfg.simplify(globals)
            cfg.verify(false, "[after simplify]")
            cfg.normalize()
            cfg.verify(true, "[after normalize]")
        }
        for (cfg in cfgs.drop(1)) {
            cfg.renameLabels()
            outcfgs.add(cfg)
        }

        val callGraph = MutableSbfCallGraph(outcfgs, setOf(root), globals)
        val memSummaries = MemorySummaries()
        return inline(root, callGraph, memSummaries, EmptyInlinerConfig)
    }

    private fun runAnalysis(cfgs: List<MutableSbfCFG>, root:String, slice: Boolean = false): FunctionArgumentInference {
        val callgraph = compileCFG(cfgs, root)
        println(callgraph)
        val cfg = if (slice) {
            sliceAssertions(callgraph, MemorySummaries()).second.getCallGraphRootSingleOrFail()
        } else {
            callgraph.getCallGraphRootSingleOrFail()
        }
        return FunctionArgumentInference(cfg)
    }

    @Test
    fun sliceCallSites() {
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0
                "foo"()

                r1 = 1
                "foo"()

                exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                br(CondOp.EQ(r1, 0), 1, 2)
            }

            bb(1) {
                assume(CondOp.EQ(r1, 0))
                br(CondOp.EQ(r6, 1), 3, 4)
            }

            bb(2) {
                assume(CondOp.NE(r1, 0))
                br(CondOp.EQ(r6, 1), 5, 6)
            }

            bb(3) {
                assert(CondOp.EQ(r3, 0))
                goto(7)
            }

            bb(4) {
                assert(CondOp.EQ(r3, 0))
                goto(7)
            }

            bb(5) {
                assert(CondOp.EQ(r3, 0))
                exit() // goto(7)
            }

            bb(6) {
                assert(CondOp.EQ(r3, 0))
                exit() //goto(7)
            }

            bb(7) {
                exit()
            }
        }

        val infer = runAnalysis(listOf(entrypoint, foo), "entrypoint", slice = true)
        Assertions.assertEquals(
            setOf(
                Value.Reg(SbfRegister.R1_ARG),
                Value.Reg(SbfRegister.R3_ARG),
            ),
            infer.liveAtCall("foo")
        )
    }

    @Test
    fun assignAndRead() {
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "foo"()
                exit()
            }
        }
        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                r1[8] = r1
                r2 = r1[16]
                r3 = (-1)
                assume(CondOp.GT(r3, r2))
                r3 = r2
                exit()
            }
        }
        val infer = runAnalysis(listOf(entrypoint, foo), "entrypoint")
        val liveAtEntry = infer.liveAtCall("foo")
        Assertions.assertEquals(
            setOf(Value.Reg(SbfRegister.R1_ARG)),
            liveAtEntry
        )
    }

    @Test
    fun testOnlyRead() {
        val entrypoint = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "foo"()
                exit()
            }
        }
        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                r0 = r1
                goto(1)
            }

            bb(1) {
                exit()
            }

        }

        val infer = runAnalysis(listOf(entrypoint, foo), "entrypoint", slice = false)

        Assertions.assertEquals(
            infer.liveAtCall("foo"),
            setOf(
                Value.Reg(SbfRegister.R1_ARG),
            )
        )
    }

    @Test
    fun simpleFunction() {
        val entry = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0
                "foo"()
                "bar"()
                r4 = r5
                exit()
            }
        }

        val foo = SbfTestDSL.makeCFG("foo") {
            bb(0) {
                r6 = r1
                BinOp.ADD(r7, r2)
                assume(CondOp.GT(r6, 0)) // to avoid r6 to be dead
                assume(CondOp.GT(r7, 0)) // to avoid r7 to be dead
                exit()
            }
        }
        val bar = SbfTestDSL.makeCFG("bar") {
            bb(0) {
                r6 = r1
                BinOp.ADD(r7, r5)
                assume(CondOp.GT(r6, 0)) // to avoid r6 to be dead
                assume(CondOp.GT(r7, 0)) // to avoid r7 to be dead
                exit()
            }
        }

        val infer = runAnalysis(listOf(entry, foo, bar), "entrypoint")
        Assertions.assertEquals(
            setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG)),
            infer.liveAtCall("foo"),
        )
        Assertions.assertEquals(
            setOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R5_ARG)),
            infer.liveAtCall("bar"),
        )
    }
}
