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

import com.certora.collect.*
import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import sbf.disassembler.newGlobalVariableMap
import sbf.domains.*
import sbf.support.CannotParseSummaryFile
import log.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.*

private val sbfTypesFac = ConstantSbfTypeFactory()

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Order(1)
class MemorySummariesTest {
    private var outContent = ByteArrayOutputStream()
    private var errContent = ByteArrayOutputStream()

    private val originalOut = System.out
    private val originalErr = System.err

    // system properties have to be set before we load the logger
    @BeforeAll
    fun setupAll() {
        System.setProperty(LoggerTypes.SBF.toLevelProp(), "info")
    }

    // we must reset our stream so that we could match on what we have in the current test
    @BeforeEach
    fun setup() {
        outContent = ByteArrayOutputStream()
        errContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent, true)) // for 'always' logs
        System.setErr(PrintStream(errContent, true)) // loggers go to stderr
    }

    private fun debug() {
        originalOut.println(outContent.toString())
        originalErr.println(errContent.toString())
    }

    // close and reset
    @AfterEach
    fun teardown() {
        debug()
        System.setOut(originalOut)
        System.setErr(originalErr)
        outContent.close()
        errContent.close()
    }


    // Return node pointed by *([baseR] + [offset])
    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getNode(g: PTAGraph<TNum, TOffset>, baseR: Value.Reg, offset: Short, width: Short): PTANode {
        val lhs = Value.Reg(SbfRegister.R7)
        check(baseR != lhs)
        val inst = SbfInstruction.Mem(Deref(width, baseR, offset, null), lhs, true, null)
        val locInst = LocatedSbfInstruction(Label.fresh(), 0, inst)
        g.doLoad(locInst, lhs, baseR, offset, width, SbfType.top(), newGlobalVariableMap())
        val sc = g.getRegCell(lhs)
        check(sc != null)
        return sc.getNode()
    }

    @Test
    fun test01() {
        sbfLogger.info { "====== TEST 1 =======" }
        /**
         * The *(r1+0) and *(r2+0) points to the same cell before the summary is applied.
         * The summary tells that *(r1+0) is a number after the call.
         * Therefore, after applying the summary, *(r2+0) must still point to the same cell but *(r1+0) should point to
         * a new cell with a fresh node marked as integer.
         */
        val r10 = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        // Create abstract state
        val absVal = MemoryDomain(PTANodeAllocator(), sbfTypesFac, true)
        val stackC = absVal.getRegCell(r10, newGlobalVariableMap())
        check(stackC != null) { "memory domain cannot find the stack node" }
        stackC.getNode().setWrite()
        val g = absVal.getPTAGraph()
        val n1 = g.mkNode()
        n1.setWrite()
        val n2 = g.mkNode()
        n2.setWrite()
        val n3 = g.mkNode()
        n3.setWrite()
        stackC.getNode().mkLink(4040, 8, n1.createCell(0))
        stackC.getNode().mkLink(4048, 8, n2.createCell(0))
        n1.mkLink(0, 8, n3.createCell(0))
        n2.mkLink(0, 8, n3.createCell(0))


        g.setRegCell(r1, n1.createSymCell(PTASymOffset(0)))
        g.setRegCell(r2, n2.createSymCell(PTASymOffset(0)))

        val configFileContents = arrayListOf("#[type((*i64)(r1+0):num)]", "^foo$")
        val memSummaries = MemorySummaries.readSpecFile(configFileContents,"unknown")

        val call = SbfInstruction.Call(name = "foo")

        sbfLogger.info {"Before $call: $g"}
        // before the call *(r1+0) == *(r2+0)
        val oldN = getNode(g, r1, 0, 8)
        Assertions.assertEquals(true,  oldN == getNode(g, r2, 0, 8))
        g.doCall(LocatedSbfInstruction(Label.fresh(), 0, call), newGlobalVariableMap(), memSummaries, absVal.getScalars())
        sbfLogger.info {"After $call with ${memSummaries.getSummary("foo")}: $g"}

        // after the call *(r1+0) != *(r2+0)
        Assertions.assertEquals(true, getNode(g, r1, 0, 8) != getNode(g, r2, 0, 8))
        // *(r2+0) did not change
        Assertions.assertEquals(true, getNode(g, r2, 0, 8) == oldN)
        // *(r1+0) changed
        Assertions.assertEquals(true, getNode(g, r1, 0, 8).mustBeInteger())
        // This assertion is provable after commit "fix(sbf): do not remove predecessors when applying summary (pta domain)"
        Assertions.assertEquals(true, stackC.getNode().getSucc(PTAField(4040, 8)) != null)
    }

    @Test
    fun test02() {
        val configFileContents = arrayListOf(
            "#[type((*i64)(r1+16):ptr_external)]",
            "#[type((*i64)(r1+32):num)]",
            "#[type((*i64)(r1+24):ptr_global)]",
            "#[type((*i8)(r1+40):num)]",
            "#[type((*i8)(r1+41):num)]",
            "#[type((*i8)(r1+42):num)]",
            "#[type((*i64)(r1+0):ptr_input(64))]",
            "#[type((*i64)(r1+8):ptr_heap(32))]",
            "^foo$")
        val memSummaries = MemorySummaries.readSpecFile(configFileContents,"unknown")
        sbfLogger.warn {"Parsed successfully summaries $memSummaries" }
    }

    @Test
    fun test03() {
        val configFileContents = arrayListOf(
            "#[type((*i64)(r1+32):num(32)]",
            "^foo$")
        var failed = false
        try {
            MemorySummaries.readSpecFile(configFileContents, "unknown")
        } catch (e: CannotParseSummaryFile) {
            failed = true
        }
        Assertions.assertEquals(true, failed)
    }
}
