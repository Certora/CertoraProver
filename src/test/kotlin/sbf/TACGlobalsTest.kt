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

class TACGlobalsTest {
    /** Mock for the tests **/
    private object MockedElfFileView: IElfFileView {
        override fun isLittleEndian() = true
        override fun isGlobalVariable(address: ElfAddress) = isReadOnlyGlobalVariable(address)
        override fun isReadOnlyGlobalVariable(address: ElfAddress) = (address == 369784L || address == 369792L)
        override fun getAsConstantString(
            address: ElfAddress,
            size: Long
        ): String = ""

        override fun getAsConstantNum(
            address: ElfAddress,
            size: Long
        ): Long? {
            return when (address) {
                369784L -> 2
                369792L -> 10
                else -> null
            }
        }
    }

    private fun verify(cfg: SbfCFG, expectedResult: Boolean) {
        println("$cfg")
        val globals = GlobalVariables(MockedElfFileView)
        val memSummaries = MemorySummaries()
        val prog = MutableSbfCallGraph(listOf(cfg), setOf(cfg.getName()), globals)
        ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
            val newGlobals = runGlobalInferenceAnalysis(prog, memSummaries).getGlobals()
            val tacProg = toTAC(cfg, globals = newGlobals)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(expectedResult, verify(tacProg))
        }
    }

    private val cfg1 = SbfTestDSL.makeCFG("test1") {
        bb(0) {
            r2 = 369784
            r3 = r2[8]
            r4 = r2[0]
            r1 = r3
            BinOp.MUL(r1, r4)
            assert(CondOp.EQ(r1, 20))
            exit()
        }
    }

    @Test
    fun `load from a read-only global is replaced with constant`() {
        verify(cfg1, true)
    }

}
