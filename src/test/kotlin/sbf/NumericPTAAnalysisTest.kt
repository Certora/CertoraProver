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
import sbf.disassembler.*
import sbf.domains.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.callgraph.MutableSbfCallGraph
import sbf.output.annotateWithTypes

private const val entrypointCfgName = "entrypoint"
private typealias MemoryDomainT = MemoryDomain<ConstantSet, ConstantSet>

class NumericPTAAnalysisTest {

    @Test
    fun test01() {
        println("====== TEST 1 =======")
        val cfg = SbfTestDSL.makeCFG(entrypointCfgName) {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r1[0] = 42 // Immediate write of a value.
                exit()
            }
        }
        val post = getPost(cfg)
        val graph = post.getPTAGraph()
        val stack = graph.getStack()
        val cell = stack.getSucc(PTAField(PTAOffset(4096 - 200), 8))
        assert(cell != null)
        val pointedNode = cell!!.getNode()
        assert(pointedNode.isExactNode())
        assert(pointedNode.mustBeInteger())
        assert(pointedNode.integerValue == Constant(42))
    }

    @Test
    fun test02() {
        println("====== TEST 2 =======")
        val cfg = SbfTestDSL.makeCFG(entrypointCfgName) {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = 42
                r1[0] = r2 // Write of the value of a register.
                exit()
            }
        }
        val post = getPost(cfg)
        val graph = post.getPTAGraph()
        val stack = graph.getStack()
        val cell = stack.getSucc(PTAField(PTAOffset(4096 - 200), 8))
        assert(cell != null)
        val pointedNode = cell!!.getNode()
        assert(pointedNode.isExactNode())
        assert(pointedNode.mustBeInteger())
        assert(pointedNode.integerValue == Constant(42))
    }

    @Test
    fun test03() {
        println("====== TEST 3 =======")
        val cfg = SbfTestDSL.makeCFG(entrypointCfgName) {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = 42
                r1[0] = r2
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = r10[-200]
                r1[8] = r2 // Write of a value that has been read from the stack.
                exit()
            }
        }
        val post = getPost(cfg)
        val graph = post.getPTAGraph()
        val stack = graph.getStack()

        // First number.
        val cell1 = stack.getSucc(PTAField(PTAOffset(4096 - 200), 8))
        assert(cell1 != null)
        val pointedNode1 = cell1!!.getNode()
        assert(pointedNode1.isExactNode())
        assert(pointedNode1.mustBeInteger())
        assert(pointedNode1.integerValue == Constant(42))

        // Second number.
        val cell = stack.getSucc(PTAField(PTAOffset((4096 - 200) + 8), 8))
        assert(cell != null)
        val pointedNode = cell!!.getNode()
        assert(pointedNode.isExactNode())
        assert(pointedNode.mustBeInteger())
        assert(pointedNode.integerValue == Constant(42))
    }

    @Test
    fun test04() {
        println("====== TEST 4 =======")
        val cfg = SbfTestDSL.makeCFG(entrypointCfgName) {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = 42
                r1[0] = r2
                r1 = r10
                BinOp.SUB(r1, 200)
                r2 = r10[-200]
                BinOp.ADD(r2, 1)
                r1[8] = r2 // Write of a value that has been read from the stack and then manipulated.
                exit()
            }
        }
        val post = getPost(cfg)
        val graph = post.getPTAGraph()
        val stack = graph.getStack()

        // First number.
        val cell1 = stack.getSucc(PTAField(PTAOffset(4096 - 200), 8))
        assert(cell1 != null)
        val pointedNode1 = cell1!!.getNode()
        assert(pointedNode1.isExactNode())
        assert(pointedNode1.mustBeInteger())
        assert(pointedNode1.integerValue == Constant(42))

        // Second number.
        val cell = stack.getSucc(PTAField(PTAOffset((4096 - 200) + 8), 8))
        assert(cell != null)
        val pointedNode = cell!!.getNode()
        assert(pointedNode.isExactNode())
        assert(pointedNode.mustBeInteger())
        assert(pointedNode.integerValue == Constant(43))
    }


    @Test
    fun test05() {
        println("====== TEST 5 =======")
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 100
                "__rust_alloc"()
                r8 = r0 // R8 points to the allocation site.
                r7 = r8 // R7 is the pointer that will be incremented.
                assume(Condition(CondOp.NE, Value.Reg(SbfRegister.R8), Value.Imm(0UL))) // Allocation is successful.
                r2 = 0
                goto(1)
            }
            bb(1) { // loop header
                br(CondOp.LT(r2, 4), 2, 3)
            }
            bb(2) { //loop body
                // Write in the heap the value of R2 and increment the pointer to the heap.
                r7[0] = r2
                BinOp.ADD(r7, 8)

                // r2++
                BinOp.ADD(r2, 1)
                goto(1)
            }
            bb(3) { // loop exit
                r8[0] = 42 // Overwrite the first entry in the heap.

                // Write at the beginning of the stack the second value in the heap.
                r1 = r8
                BinOp.ADD(r1, 8)
                r2 = r10
                BinOp.SUB(r2, 200)
                r2[0] = r1 // This will be not exact, as the heap has been summarized.
                exit()
            }
        }

        val post = getPost(cfg, Label.Address(3))
        val graph = post.getPTAGraph()
        val stack = graph.getStack()

        val cell = stack.getSucc(PTAField(PTAOffset(4096 - 200), 8))
        assert(cell != null)
        val pointedNode = cell!!.getNode()
        // Assert that we lost precision, but we are not unsound.
        assert(!pointedNode.isExactNode())
        assert(!pointedNode.mustBeInteger())
        assert(pointedNode.integerValue == Constant.makeTop())
    }

    /** Returns the post-state in CFG [entrypointCfgName] at block `Label.Address(0)` */
    private fun getPost(cfg: MutableSbfCFG, label: Label = Label.Address(0)): MemoryDomainT {
        val cfgs = mutableListOf(cfg)
        val entrypoints = setOf(entrypointCfgName)
        val globals = newGlobalVariableMap()
        val prog = MutableSbfCallGraph(cfgs, entrypoints, globals, preservedCFGs = setOf())
        val memSummaries = MemorySummaries()
        val progWithTypes = annotateWithTypes(prog, memSummaries)
        val analysis = WholeProgramMemoryAnalysis(progWithTypes, memSummaries)
        analysis.inferAll()
        val analysisResults = analysis.getResults()[entrypointCfgName]!!
        val post = analysisResults.getPost(label)
        assert(post != null)
        return post!!
    }

}
