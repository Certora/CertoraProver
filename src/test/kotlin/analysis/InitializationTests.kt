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

package analysis

import analysis.pta.INIT_FAILURE
import analysis.pta.POP_ALLOCATION
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotation.WithOptimizedFlag
import annotation.SolidityVersionEnum
import annotations.TestTags
import extension.IgnoreZ3TimeoutErrors
import loaders.SingleMethodTest
import loaders.SolidityContractTest
import log.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import vc.data.TACCmd

@ExtendWith(IgnoreZ3TimeoutErrors::class)
@Tag(TestTags.EXPENSIVE)
class InitializationTests : SingleMethodTest, SolidityContractTest {
    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V4_25, SolidityVersion.ANY_5, SolidityVersion.V7_0, SolidityVersion.V6_8, SolidityVersion.V6_10, SolidityVersion.V8_16, SolidityVersion.V8_29])
    @WithOptimizedFlag
    fun testConstantSizePrimitizeArray(solc: String, optimize: Boolean) {
        val graph = this.loadTestContractGraph("analysis/ConstantUintAlloc.sol", solc, optimize)
        assertHasInitAnnotations(graph)
    }

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V4_25, SolidityVersion.ANY_5, SolidityVersion.V7_0, SolidityVersion.V6_8, SolidityVersion.V6_10, SolidityVersion.V8_16, SolidityVersion.V8_29])
    @WithOptimizedFlag
    fun testConstantSizeStructArray(solc: String, optimize: Boolean) {
        val graph = this.loadTestContractGraph("analysis/ConstantStructAlloc.sol", solc, optimize)
        assertHasInitAnnotations(graph)
    }

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_1, SolidityVersion.V6_10, SolidityVersion.V6_8, SolidityVersion.V7_0, SolidityVersion.ANY_5, SolidityVersion.ANY_4, SolidityVersion.V8_1, SolidityVersion.V8_16, SolidityVersion.V8_29])
    @WithOptimizedFlag
    fun testStringGetter(solc: String, optimize: Boolean) {
        assertHasInitAnnotations(this.loadTestContractGraph("analysis/StringGetter.sol", solc, optimize))
    }

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_1, SolidityVersion.V6_10, SolidityVersion.V6_8, SolidityVersion.V7_0, SolidityVersion.ANY_5, SolidityVersion.ANY_4, SolidityVersion.V8_1, SolidityVersion.V8_16, SolidityVersion.V8_29])
    @WithOptimizedFlag
    fun testMappingStringGetter(solc: String, optimize: Boolean) {
        assertHasInitAnnotations(this.loadTestContractGraph("analysis/StringInMapGetter.sol", solc, optimize))
    }

    private fun assertHasInitAnnotations(graph: TACCommandGraph) {
        Assertions.assertTrue(graph.commands.map(LTACCmd::cmd).any { cmd ->
            cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == POP_ALLOCATION
        })
    }

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_1, SolidityVersion.V6_10, SolidityVersion.V6_8, SolidityVersion.V7_0, SolidityVersion.V7_6, SolidityVersion.ANY_5, SolidityVersion.V8_1, SolidityVersion.V8_19, SolidityVersion.V8_16, SolidityVersion.V8_29])
    @WithOptimizedFlag
    fun testDuplicateStringLookup(solc: String, optimize: Boolean) {
        assertHasInitAnnotations(this.loadTestContractGraph("analysis/DuplicateStringLookup.sol", solc, optimize))
    }

    @ParameterizedTest
    @WithOptimizedFlag
    @SolidityVersions([SolidityVersion.V6_12])
    fun returnAddressArray(solc: String, optimize: Boolean) {
        assertHasInitAnnotations(this.loadTestContractGraph("analysis/OptimizedAddressReturn.sol", solc, optimize = optimize))
    }

    @ParameterizedTest
    @WithOptimizedFlag
    @SolidityVersions([SolidityVersion.V8_16, SolidityVersion.V8_29])
    fun multiNestedStructs(solc: String, optimize: Boolean) {
        assertHasInitAnnotations(this.loadTestContractGraph("analysis/MultiStruct.sol", solc, optimize))
    }

    @ParameterizedTest
    @WithOptimizedFlag
    @SolidityVersions([SolidityVersion.V6_10, SolidityVersion.V6_12, SolidityVersion.V7_6, SolidityVersion.V7_0])
    fun allRevertInitialize(solc: String, optimize: Boolean) {
        val x = this.loadTestGraph("""
            contract Test {
                struct Yolo {
                      uint field;
                }
                function alwaysReverts(uint x) internal returns(uint) {
                    if (x == 0) {
                        revert();
                    }
                    return 3;
                }
                bool b;
                function test() public  returns (uint) {
                   Yolo memory s = Yolo(alwaysReverts(0));
                   return s.field;
                }
            }
        """.trimIndent(), solc = solc, optimize = optimize)
        Assertions.assertTrue(x.commands.none {
            val cmd = it.cmd
            cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == INIT_FAILURE
        })
    }

    @ParameterizedTest
    @WithOptimizedFlag
    @SolidityVersions([SolidityVersion.V8_9, SolidityVersion.V8_16, SolidityVersion.V8_29])
    fun copyArrayOfBytesFromStorageToMem(solc: String, optimize: Boolean) {
        val x = this.loadTestGraph("""
            contract Test {
                // Note that yolo is an array of array of bytes
            	bytes[] yolo;

            	function test() public returns (uint) {
            		bytes[] memory yeet = yolo;
            		return yeet.length;
            	}
            }
        """.trimIndent(), solc = solc, optimize = optimize)
        Assertions.assertTrue(x.commands.none {
            val cmd = it.cmd
            cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == INIT_FAILURE
        })
    }

    @ParameterizedTest
    @SolidityVersions([
        SolidityVersion.V8_9, SolidityVersion.V6_8, SolidityVersion.V8_16, SolidityVersion.V5_13, SolidityVersion.V7_0,
        SolidityVersion.V7_0, SolidityVersion.V7_6
    ])
    @WithOptimizedFlag
    fun testCallCoreInitializesMemory(solc: String, optimize: Boolean) {
        val x = this.loadTestGraph("""
            contract Test {
                function test() public returns (uint) {
                    uint toReturn;
                    uint result;
                    address to = address(this);
                    assembly {
                        result := mload(0x40)
                        mstore(0x40, add(result, 0x20))
                        pop(staticcall(0, to, mload(0x40), 0, result, 0x20))
                        toReturn := mload(result)
                    }
                    return toReturn;
                }
            }
        """.trimIndent(), solc = solc, optimize = optimize)
        Assertions.assertTrue(x.commands.none {
            val cmd = it.cmd
            cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == INIT_FAILURE
        })
    }

    @ParameterizedTest
    @SolidityVersionEnum([
        SolidityVersion.V7_0, SolidityVersion.V7_6,
        SolidityVersion.V8_2, SolidityVersion.V8_9, SolidityVersion.V8_13, SolidityVersion.V8_11, SolidityVersion.V8_16, SolidityVersion.V8_19, SolidityVersion.V8_29
    ])
    @WithOptimizedFlag
    fun testDecodeStructStatics(solc: SolidityVersion, optimize: Boolean) {
        val header = if (solc.majorVersion < 8) {
            "pragma experimental ABIEncoderV2;"
        } else {
            ""
        }

        val x = this.loadTestGraph("""
            $header
            contract Test {
                   struct S {
                      uint[3][5] f1;
                      uint[] f2;
                   }

                   function test(S memory d) public returns (uint) {
                       bytes memory m = abi.encode(d);
                       return m.length;
                   }
            }
        """.trimIndent(), solc = solc.compilerName(), optimize = optimize)
        Assertions.assertTrue(x.commands.none {
            val cmd = it.cmd
            cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == INIT_FAILURE
        })
    }

}
