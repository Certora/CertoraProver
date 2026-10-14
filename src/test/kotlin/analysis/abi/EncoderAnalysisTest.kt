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

package analysis.abi

import analysis.maybeAnnotation
import analysis.pta.HeapType
import analysis.pta.abi.ABIEncodeComplete
import annotation.ScopedMatrix
import config.Config
import extension.DependentPair
import extension.setTo
import loaders.SingleMethodTest
import loaders.SolidityContractTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import scene.ITACMethod
import utils.mapNotNull
import vc.data.CoreTACProgram
import java.util.stream.Collectors

abstract class EncoderAnalysisTest : SingleMethodTest, SolidityContractTest {
    abstract val header: String

    @ParameterizedTest
    @ScopedMatrix
    fun testBasicEncoding(solc: String, optimize: Boolean) {
        for(ty in listOf("bytes", "uint[]", "address[]")) {
            val src = """
                contract Test {
                    function test(uint a) public returns (uint) {
                         $ty memory d = new $ty(a);
                         bytes memory enc = abi.encode(d);
                         return enc.length;
                    }
                }
            """.trimIndent()

            testNumberOfRoots(src, solc, optimize, 1) {
                "$ty and optimize = $optimize"
            }
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticStrides(solc: String, optimize: Boolean) {
        val src = """
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
        """.trimIndent()
        testNumberOfRoots(src, solc, optimize, 1) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testEagerResolution(solc: String, optimize: Boolean) {
        val outerFields = listOf(
                "uint b;",
                "Nested inner;"
        )
        val innerFields = listOf(
                "uint a;",
                "uint[] array;"
        )
        val permute = listOf(
                listOf(0, 1),
                listOf(1, 0)
        )
        for(outerPerm in permute) {
            for(innerPerm in permute) {
                val src = """
            pragma experimental ABIEncoderV2;

            contract Test {
                struct Nested {
                    ${innerFields[innerPerm[0]]}
                    ${innerFields[innerPerm[1]]}
                }

                struct Outer {
                    ${outerFields[outerPerm[0]]}
                    ${outerFields[outerPerm[1]]}
                }

                function test(uint nondet) public returns (uint) {
                    uint[] memory arr = new uint[](nondet);
                    Nested memory n = Nested({
                       a: nondet,
                       array: arr
                    });
                    Outer memory o = Outer({
                       b: nondet,
                       inner: n
                    });
                    bytes memory enc = abi.encode(o, o.inner);
                    return enc.length;
                }
            }
        """.trimIndent()
                this.testNumberOfRoots(src, solc, optimize, 2) {
                    "$solc, optimize = $optimize, outerFields = $outerPerm, innerFields = $innerPerm"
                }
            }
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticStructEncoding(solc: String, optimize: Boolean) {
        val src = """
            pragma experimental ABIEncoderV2;
           contract Test {
               struct S {
                  uint a;
                  uint b;
                  uint c;
               }
                function test() public returns (uint) {
                    S memory d;
                    bytes memory m = abi.encode(d);
                    return m.length;
                }
           }
        """.trimIndent()
        this.testNumberOfRoots(src, solc, optimize, 1) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticStructArrayEncoding(solc: String, optimize: Boolean) {
        val contract = """
            pragma experimental ABIEncoderV2;

            contract Test {
            	struct Static {
            		uint a;
            		uint b;
            	}
            	function test(uint256 n) public pure returns (uint) {
            		Static[] memory yeet = new Static[](n);
            		bytes memory x = abi.encode(yeet);
            		return x.length;
            	}
            }
        """.trimIndent()
        this.testNumberOfRoots(contract, solc, optimize, 1) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticArrayOfDynamicTypes(solc: String, optimize: Boolean) {
        this.testNumberOfRootsOnFile("analysis/StaticArrayOfDynamic.sol", solc, optimize, 2) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testDynamicNesting(solc: String,  optimize: Boolean) {
        this.testNumberOfRootsOnFile("analysis/DynamicStructAndStatic.sol", solc, optimize, 3) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticArrayOfStruct(solc: String, optimize: Boolean) {
        this.testNumberOfRootsOnFile("analysis/StaticArrayOfStructs.sol", solc, optimize, 1) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testCalldata(solc: String, optimize: Boolean) {
        this.testNumberOfRootsOnFile("analysis/abi/Calldata.sol", solc, optimize, 1) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStructEncoding(solc: String, optimize: Boolean) {
        val contract = """
           $header
           contract Test  {
                struct S {
                    address b;
                    address c;
                }

                function test(S memory moo, address x) public {
                    abi.encode(123, moo, x);
                }
           }
        """.trimIndent()
        this.testNumberOfRoots(contract, solc, optimize, 3) {
            "$solc, optimize = $optimize"
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticStructTooBig(solc: String, optimize: Boolean) {

        val contract = """
           $header
           contract Test  {
                struct S {
                    address b;
                    address c;
                }

                function test(S calldata moo) external {
                    abi.encode(moo.c);
                }
           }
        """.trimIndent()
        testABIEncodeComplete(contract, solc, optimize, { "$solc, optimize = $optimize"}) { enc ->
            Assertions.assertEquals(
                HeapType.Int,
                enc.buffer.buffer.entries.singleOrNull()?.value?.ty
            )
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticStructFirstFieldEncoding(solc: String, optimize: Boolean) {
        /*
           The first field of a static calldata struct starts at the same buffer access path as the struct
           itself, so the encoded word is a primitive whose location is also the location of the whole struct.
         */
        val contract = """
           $header
           contract Test  {
                struct S {
                    address b;
                    address c;
                }

                function test(S calldata moo) external {
                    abi.encode(moo.b);
                }
           }
        """.trimIndent()
        testABIEncodeComplete(contract, solc, optimize, { "$solc, optimize = $optimize"}) { enc ->
            Assertions.assertEquals(
                HeapType.Int,
                enc.buffer.buffer.entries.singleOrNull()?.value?.ty
            )
        }
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testStaticCalldataArrayElementEncoding(solc: String, optimize: Boolean) {
        /*
           As above, but for the first element of a static calldata array.
         */
        val contract = """
           $header
           contract Test  {
                function test(uint256[2] calldata values) external {
                    abi.encode(values[0]);
                }
           }
        """.trimIndent()
        testABIEncodeComplete(contract, solc, optimize, { "$solc, optimize = $optimize"}) { enc ->
            Assertions.assertEquals(
                HeapType.Int,
                enc.buffer.buffer.entries.singleOrNull()?.value?.ty
            )
        }
    }

    private fun testNumberOfRoots(src: String, solc: String, optimize: Boolean, numberRoots: Int, msg: () -> String) {
        testNumberOfRoots(this.loadTestMethod(src, solc, optimize), numberRoots, msg)
    }

    private fun testNumberOfRootsOnFile(path: String, solc: String, optimize: Boolean, numberRoots:Int, msg: () -> String) {
        testNumberOfRoots(this.loadTestContractMethod(path, solc, optimize), numberRoots, msg)
    }

    private fun testNumberOfRoots(m: ITACMethod, numberRoots: Int, msg: () -> String) =
        testABIEncodeComplete(m, msg) { enc ->
                Assertions.assertEquals(numberRoots, enc.buffer.buffer.size) {
                    "For ${msg()}"
                }
            }

    private fun testABIEncodeComplete(src: String, solc: String, optimize: Boolean, msg: () -> String, check: (ABIEncodeComplete) -> Unit) {
        testABIEncodeComplete(this.loadTestMethod(src, solc, optimize), msg, check)
    }

    private fun testABIEncodeComplete(m: ITACMethod, msg: () -> String, check: (ABIEncodeComplete) -> Unit) {
        val enc = (m.code as CoreTACProgram).ltacStream().mapNotNull { it.maybeAnnotation(ABIEncodeComplete.META_KEY) }
            .collect(Collectors.toList()).singleOrNull()
            ?: Assertions.fail { "Failed to find encode annotation for ${msg()}" }
        check(enc)
    }

    companion object {
        @JvmStatic
        fun certoraConfig() : List<DependentPair<*>> = listOf(
            Config.EnabledABIAnalysis setTo true
        )
    }
}
