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
import sbf.analysis.runGlobalInferenceAnalysis
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class TACGlobalInitializerTest {
    /** Mock for the tests **/
    private object MockedElfFileView: IElfFileView {
        override fun isLittleEndian() = true
        override fun sbpfVersion() = SbpfVersion.SBF
        override fun useDynamicFrames() = false
        override fun isGlobalVariable(address: ElfAddress) = (address == 671456L)
        override fun isReadOnlyGlobalVariable(address: ElfAddress) = false
        override fun getAsConstantString(
            address: ElfAddress,
            size: Long
        ): String {
            return when (address) {
                671456L -> "B\"×\u0086ªñ÷{l¦ÿ\u0087®\u009D}õ¦G\u0092é\u0081HA\u008C3á ½?×ú2"
                else -> ""
            }
        }

        override fun getAsConstantNum(
            address: ElfAddress,
            size: Long
        ): Long? = null
    }

    private fun verify(cfg: SbfCFG, expectedResult: Boolean) {
        println("$cfg")
        val globals = GlobalVariables(MockedElfFileView)
        val memSummaries = MemorySummaries()
        val prog = MutableSbfCallGraph(listOf(cfg), setOf(cfg.getName()), globals)
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            ConfigScope(SolanaConfig.AddMemLayoutAssumptions, false).use {
                val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
                val tacProg = toTAC(cfg, globals = newGlobals)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(expectedResult, verify(tacProg))
            }
        }
    }

    private val cfg1 = SbfTestDSL.makeCFG("test1") {
        bb(0) {
            r1 = 32
            "__rust_alloc"()
            r2 = r0
            r1 = 671456
            r3 = r1[0]
            r4 = r2[0]
            assume(CondOp.EQ(r3, r4))
            r3 = r1[8]
            r4 = r2[8]
            assume(CondOp.EQ(r3, r4))
            r3 = r1[16]
            r4 = r2[16]
            assume(CondOp.EQ(r3, r4))
            r3 = r1[24]
            r4 = r2[24]
            assume(CondOp.EQ(r3, r4))

            r4 = r2[0]
            assert(CondOp.EQ(r4, 8932874100621648450))
            r4 = r2[8]
            assert(CondOp.EQ(r4, -757275789396826516))
            r4 = r2[16]
            assert(CondOp.EQ(r4, -8340305312106788954))
            r4 = r2[24]
            assert(CondOp.EQ(r4, 3673485114838409523))
            exit()
        }
    }

    private val cfg2 = SbfTestDSL.makeCFG("test2") {
        bb(0) {
            r1 = 671456
            r2 = r1[0]
            assert(CondOp.EQ(r2, 8932874100621648450))
            r2 = r1[8]
            assert(CondOp.EQ(r2, -757275789396826516))
            r2 = r1[16]
            assert(CondOp.EQ(r2, -8340305312106788954))
            r2 = r1[24]
            assert(CondOp.EQ(r2, 3673485114838409523))
            exit()
        }
    }


    @Test
    fun test1() {
        verify(cfg1, true)
    }

    @Test
    fun test2() {
        verify(cfg2, true)
    }

}
