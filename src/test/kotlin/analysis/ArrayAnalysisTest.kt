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

@file:Suppress("DEPRECATION") // will be cleared in CVL Rewrite

package analysis

import analysis.alloc.AllocationAnalysis
import analysis.pta.InitAnnotation
import analysis.pta.PointsToAnalysis
import analysis.pta.TrivialPointsToInformation
import annotation.ScopedVersions
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotation.WithOptimizedFlag
import annotations.TestTags.EXPENSIVE
import loaders.SolidityContractTest
import loaders.runPTA
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import tac.Tag
import testing.ttl.TACMockLanguage
import vc.data.*
import org.junit.jupiter.api.Tag as TestTag

@SolidityVersions([
    SolidityVersion.ANY_5, SolidityVersion.V5_11,
    SolidityVersion.V6_1,  SolidityVersion.V6_6, SolidityVersion.V6_8, SolidityVersion.V6_10,
    SolidityVersion.V7_0, SolidityVersion.V7_6,
    SolidityVersion.V8_2, SolidityVersion.V8_9, SolidityVersion.V8_13, SolidityVersion.V8_16, SolidityVersion.V8_29,
    SolidityVersion.V8_3O
])
@TestTag(EXPENSIVE)
class ArrayAnalysisTest : SolidityContractTest {
    @Test
    fun testBasic() {
        val graph = TACMockLanguage.make {
            freePointer `=` 0x80
            L1023 = `*` // the length
            L1020 = freePointer
            L1021 = "L1020 + 0x20"
            L1024 = "L1023 * 0x1"
            L1021 = "L1024 + L1021"
            freePointer `=` L(1021)
            mem[L(1020)] = L1023
            L1021 = 0 // the length counter
            L1024 = "L1020 + 0x20" // write pointer
            `while`(L(1019, Tag.Bool), "L1021 < L1023") {
                mem[L1024] = 0x0
                L1024 = "L1024 + 0x20"
                L1021 = "L1021 + 0x20"
            }
            // we should have an init close here
            // now let's compute the "length" of the block
            L1021 = mem[L1020] // the length (again)
            L1023 = "L1020 + 0x20"
            L1022 = "L1023 + L1021"
            L1022 = "L1022 - L1023"
            `if`(1019, "L1022 < 0x60") {
                exit()
            } `else` {
                // this should be provably within bounds
                L1023 = "L1020 + 0x20"
                // index 0
                L1022 = mem[L1023]
                L1023 = "L1023 + 0x20"
                // index 32
                L1022 = mem[L1023]
                exit()
            }
        }
        val coreProgram = CoreTACProgram(
                code = graph.code,
                name = "test",
                blockgraph = graph.toBlockGraph(),
                procedures = emptySet(),
                symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
                ufAxioms = UfAxioms.empty()
        )
        val x = InitAnnotation.annotateInitializationWindows(coreProgram)
        x.analysisCache.graph.let {
            val allocInfo = AllocationAnalysis.runAnalysis(it) ?: Assertions.fail("womp")
            Assertions.assertDoesNotThrow {
                PointsToAnalysis(
                    graph = it,
                    method = null,
                    allocSites = allocInfo.abstractAllocations,
                    scratchSite = allocInfo.scratchReads,
                    initialFreePointerValue = 0x80.toBigInteger()
                )
            }
        }
    }

    @ParameterizedTest
    @ScopedVersions
    @WithOptimizedFlag
    fun testAbiDecode(solc: String, optimize: Boolean) {
        val method = this.loadTestContractMethod("analysis/AbiDecode.sol", solc, optimize = optimize)
        val pta = runPTA(method)
        Assertions.assertTrue(pta !is TrivialPointsToInformation)
    }


    @ParameterizedTest
    @ScopedVersions
    @WithOptimizedFlag
    fun testAbiDecodeDynamic(solc: String, optimize: Boolean) {
        val method = this.loadTestContractMethod("analysis/AbiDecodeDynamic.sol", solc, optimize)
        val pta = runPTA(method)
        Assertions.assertTrue(pta !is TrivialPointsToInformation) {
            "Failed on $solc with $optimize"
        }
    }

    @ParameterizedTest
    @ScopedVersions
    fun testAbiDecodeDynamicWordSize(solc: String) {
        val method = this.loadTestContractMethod("analysis/AbiDecodeDynamicWide.sol", solc, false)
        val pta = runPTA(method)
        Assertions.assertTrue(pta !is TrivialPointsToInformation)
    }

    @ParameterizedTest
    @ScopedVersions
    @WithOptimizedFlag
    fun testAbiDecodeReturnBuffer(solc: String, optimize: Boolean) {
        val method = this.loadTestContractMethod("analysis/TestDecodeReturnBuffer.sol", solc, optimize)
        val pta = runPTA(method)
        Assertions.assertTrue(pta !is TrivialPointsToInformation)
    }
}
