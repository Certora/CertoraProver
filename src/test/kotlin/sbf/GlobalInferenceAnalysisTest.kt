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
import sbf.analysis.runGlobalInferenceAnalysis
import sbf.callgraph.CVTFunction
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.*
import sbf.disassembler.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.analysis.AnalysisRegisterTypes
import sbf.domains.*

private val sbfTypesFac = ConstantSbfTypeFactory()
private object MockElfFileView: IElfFileView {
    override fun isLittleEndian() = true
    override fun sbpfVersion() = SbpfVersion.SBF
    override fun useDynamicFrames() = false
    override fun isGlobalVariable(address: ElfAddress) = (address == 976432L)
    override fun isReadOnlyGlobalVariable(address: ElfAddress) = false
    override fun getAsConstantString(
        address: ElfAddress,
        size: Long
    ) = ""

    override fun getAsConstantNum(
        address: ElfAddress,
        size: Long
    ): Long? = null
}
private val globals = GlobalVariables(MockElfFileView)
private val memSummaries = MemorySummaries()

class GlobalInferenceAnalysisTest {
    /**
     * Run the global inference analysis and extracts the type of the dereferenced pointer at
     * [pos]-th instruction in block [label]
     **/
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getTypeFromMemInst(
            prog: MutableSbfCallGraph,
            memSummaries: MemorySummaries,
            sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
            label: Label,
            pos:Int)
        : Pair<SbfInstruction.Mem, SbfType<TNum, TOffset>> {
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newProg = runGlobalInferenceAnalysis(prog, memSummaries)
            val newCfg = newProg.getCallGraphRootSingleOrFail()
            sbfLogger.warn {"After GIA: $newCfg"}
            val scalarAnalysis = ScalarAnalysis(newCfg, newProg.getGlobals(), memSummaries, sbfTypesFac)
            val regTypes = AnalysisRegisterTypes(scalarAnalysis)
            // Search for the first memory instruction and check that the dereferenced pointer is a global variable
            val bb = newProg.getCallGraphRootSingleOrFail().getBlock(label)
            check(bb != null) { "Cannot find block $label" }
            val locInst = bb.getLocatedInstructions()[pos]
            val inst = locInst.inst
            check(inst is SbfInstruction.Mem) { "$inst is not a memory instruction" }
            val type = regTypes.typeAtInstruction(locInst, inst.access.base)
            return inst to type
        }
    }


    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 976432
                r3 = 1
                r4 = 0
                goto(1)
            }
            bb(1) {
                BinOp.ADD(r4, 2)
                goto(2)
            }
            bb(2) {
                BinOp.ADD(r4, 2)
                goto(3)
            }
            bb(3) {
                BinOp.ADD(r1, r3)
                r1[0] = r4
                exit()
            }
        }

        val prog = MutableSbfCallGraph(listOf(cfg), setOf("entrypoint"), globals)
        println("$cfg")

        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
            println("New global map=${newGlobals}")
            Assertions.assertEquals(true,  newGlobals.contains(976432))
        }
    }

    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r3 = 0
                br(CondOp.EQ(r6, 0x0), 1, 2)
            }
            bb(1) {
                r3 = 1
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            // At this point we want to enforce that r3 is num
            bb(3) {
                r1 = 976432
                r4 = 0
                goto(4)
            }
            bb(4) {
                BinOp.ADD(r4, 2)
                r2 = r1
                goto(5)
            }
            bb(5) {
                BinOp.ADD(r4, 2)
                r6 = r2
                r1 = r6
                goto(6)
            }
            bb(6) {
                BinOp.ADD(r1, r3)
                r1[0] = r4
                exit()
            }
        }

        val prog = MutableSbfCallGraph(listOf(cfg), setOf("entrypoint"), globals)
        println("$cfg")
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
            println("New global map=${newGlobals}")
            Assertions.assertEquals(true, newGlobals.contains(976432))
        }
    }

    @Test
    fun test3() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 976432
                r2 = 0
                goto(1)
            }
            bb(1) { // loop header
                br(CondOp.LT(r2, 4), 2, 3)
            }
            bb(2) { //loop body
                r6 = r1
                BinOp.ADD(r6, 8)
                r7 = r6[0]
                // do something with r7
                BinOp.ADD(r2, 1)
                goto(1)
            }
            bb(3) { // loop exit
                exit()
            }
        }

        val prog = MutableSbfCallGraph(listOf(cfg), setOf("entrypoint"), globals)
        println("$cfg")
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
            println("New global map=${newGlobals}")
            Assertions.assertEquals(true, newGlobals.contains(976432))
        }
    }

    @Test
    fun test4() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 976432
                r2 = 0
                goto(1)
            }
            bb(1) { // loop header
                br(CondOp.LT(r2, 4), 2, 3)
            }
            bb(2) { //loop body
                r6 = r1
                BinOp.ADD(r6, 8)
                r7 = r6[0]
                // do something with r7
                BinOp.ADD(r2, 1)
                goto(4)
            }
            // loop unrolling  r2=1
            bb(4) { // loop header
                br(CondOp.LT(r2, 4), 5, 3)
            }
            bb(5) { //loop body
                r6 = r1
                BinOp.ADD(r6, 8)
                r7 = r6[0]
                // do something with r7
                BinOp.ADD(r2, 1)
                goto(6)
            }
            // loop unrolling r2=2
            bb(6) { // loop header
                br(CondOp.LT(r2, 4), 7, 3)
            }
            bb(7) { //loop body
                r6 = r1
                BinOp.ADD(r6, 8)
                r7 = r6[0]
                // do something with r7
                BinOp.ADD(r2, 1)
                goto(8)
            }
            // loop unrolling r2=3
            bb(8) { // loop header
                br(CondOp.LT(r2, 4), 9, 3)
            }
            bb(9) { //loop body
                r6 = r1
                BinOp.ADD(r6, 8)
                r7 = r6[0]
                // do something with r7
                BinOp.ADD(r2, 1)
                goto(10)
            } // loop unrolling r2=3
            bb(10) { // loop header
                br(CondOp.LT(r2, 4), 11, 3)
            }
            bb(11) { //loop body
                r9 = 0
                assume(CondOp.NE(r9, 0x0)) // assume(false)
            }

            bb(3) { // loop exit
                exit()
            }
        }

        val prog = MutableSbfCallGraph(listOf(cfg), setOf("entrypoint"), globals)
        println("$cfg")
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
            println("New global map=${newGlobals}")
            Assertions.assertEquals(true, newGlobals.contains(976432))
        }
    }

    @Test
    fun test5() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                "CVT_nondet_u64"()
                r4 = r0
                r1 = 976432
                BinOp.ADD(r1, r4)
                r3 = r1[0]
                exit()
            }
        }

        CVTFunction.addSummaries(memSummaries)
        val prog = MutableSbfCallGraph(listOf(cfg), setOf("entrypoint"), globals)
        println("Before\n$cfg")
        val (inst, type) = getTypeFromMemInst(prog, memSummaries, sbfTypesFac, Label.Address(0), 4)
        println("Found $inst. The type of the de-referenced pointer is $type")
        val isGlobal = type is SbfType.PointerType.Global
        Assertions.assertEquals(true, isGlobal)
    }

    @Test
    fun test6() {
        val cfg = SbfTestDSL.makeCFG("test6") {
            bb(0) {
                r1 = 976432
                r7 = r1
                r10[-200] = r7
                br(CondOp.EQ(r2, 0), 1, 2)
            }
            bb(1) {
                goto(3)
            }
            bb(2) {
                goto(3)
            }
            bb(3) {
                goto(4)
            }
            bb(4) {
                goto(5)
            }
            bb(5) {
                goto(6)
            }
            bb(6) {
                goto(7)
            }
            bb(7) {
                goto(8)
            }
            bb(8) {
                goto(9)
            }
            bb(9) {
                goto(10)
            }
            bb(10) {
                goto(11)
            }
            bb(11) {
                r8 = r10[-200]
                r6 = r8
                r7 = r6[0]
                goto(12)
            }
            bb(12) { // loop exit
                exit()
            }
        }
        cfg.verify(true)
        val prog = MutableSbfCallGraph(listOf(cfg), setOf("test6"), globals)
        println("$cfg")
        val (inst, type) = getTypeFromMemInst(prog, memSummaries, sbfTypesFac, Label.Address(11), 2)
        println("Found $inst. The type of the de-referenced pointer is $type")
        val isGlobal = type is SbfType.PointerType.Global
        Assertions.assertEquals(true, isGlobal)
    }

}


