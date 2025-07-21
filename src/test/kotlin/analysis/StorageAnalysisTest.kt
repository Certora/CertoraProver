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

import analysis.hash.DisciplinedHashModel
import analysis.icfg.CallGraphBuilder
import analysis.pta.FlowPointsToInformation
import analysis.storage.*
import annotation.ScopedMatrix
import annotation.SolidityConfigMatrix
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotations.TestTags.EXPENSIVE
import bridge.NamedContractIdentifier
import datastructures.stdcollections.*
import loaders.SingleMethodTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import scene.TACMethod
import tac.TACStorageLayout
import tac.Tag
import testing.ttl.TACMockLanguage
import utils.*
import vc.data.*
import java.math.BigInteger
import java.time.Duration
import kotlin.streams.toList
import analysis.storage.StorageAnalysis.AnalysisPath as AccessPath
import analysis.storage.StorageTree.Root as ResultRoot
import analysis.storage.StorageTree.Type as ResultType
import org.junit.jupiter.api.Tag as TestTag

private const val contractName = "Test"
private const val functionName = "test"
private const val struct1Name = "MyStruct"
private const val struct2Name = "MyOtherStruct"
private const val structWithStringArrayName = "StructWithStringArray"

private val struct1Decl = """
    struct $struct1Name {
        uint256 field_1;
        uint256 field_2;
    }
""".trimIndent()

private val struct2Decl = """
    struct $struct2Name {
        uint256 field_1;
        $struct1Name field_2;
        uint256 field_3;
    }
""".trimIndent()

private val structWithStringArrayDecl = """
    struct $structWithStringArrayName {
        string[] field_1;
    }
""".trimIndent()

private val struct = """
    contract $contractName {
        $struct1Decl
        $struct1Name myStruct;
        function test(uint256 x, uint256 y) {
            myStruct.field_1 = x;
            myStruct.field_2 = y;
        }
    }
""".trimIndent()

private fun staticArrayToMemory(type: String, pragma: String) = """
    $pragma
    contract $contractName {
        uint[3] dummy;
        $type[32] arr;
        function test() public returns ($type[32] memory) {
            return arr;
        }
    }
""".trimIndent()

private fun staticArrayToStorage(type: String, pragma: String, size: Int) = """
    $pragma
    contract $contractName {
        uint[3] dummy;
        $type[32] arr;
        function test($type[$size] memory foo) public {
          arr = foo;
        }
    }
""".trimIndent()

private val mapping = """
    contract $contractName {
        mapping (uint256 => uint256) public my_mapping;

        function test(uint256 x, uint256 y) public {
            my_mapping[x] = y;
        }
    }
""".trimIndent()

private val nestedMapping = """
    contract $contractName {
        mapping(uint256 => mapping(uint256 => uint256)) my_mapping;

        function test(uint256 i1, uint256 i2, uint256 n) public {
            my_mapping[i1][i2] = n;
        }
    }
""".trimIndent()

private val array = """
    contract $contractName {
        uint256[] my_array;

        function test(uint256 x, uint256 y) public {
            my_array[x] = y;
        }
    }
""".trimIndent()

private val structArray = """
    contract $contractName {
        $struct1Decl
        $struct1Name[] my_array;

        function test(uint256 i, uint256 f1, uint256 f2) public {
            my_array[i].field_1 = f1;
            my_array[i].field_2 = f2;
        }
    }
""".trimIndent()

private val nestedStructMapping = """
    contract $contractName {
        $struct1Decl
        $struct2Decl

        mapping(uint256 => $struct2Name) my_mapping;

        function test(uint256 k, uint256 n) public {
            my_mapping[k].field_1 = n;
            my_mapping[k].field_2.field_1 = n;
            my_mapping[k].field_2.field_2 = n;
            my_mapping[k].field_3 = n;
}
    }
""".trimIndent()

private val arrayMapping = """
    contract $contractName {
        mapping(uint256 => uint256[]) my_mapping;

        function test(uint256 k, uint256 i, uint256 n) public {
            my_mapping[k][i] = n;
        }
    }
""".trimIndent()

private val constantMapIndex = """
contract $contractName {
   mapping(address => mapping(uint => uint)) my_mapping;

   function test() public returns (uint) {
       return my_mapping[msg.sender][0];
   }
}
""".trimIndent()

private val constantIndexAccessWithStruct = """
                contract $contractName {
                    $struct1Decl
                    $struct1Name[] my_array;
                    function $functionName(uint256 f1, uint256 f2) public {
                        my_array[5].field_1 = f1;
                        my_array[0].field_2 = f2;
                    }
                }
            """.trimIndent()

private val loopOverUintArray = """
    contract $contractName {
        uint256[] my_array;
        function $functionName() public returns (uint256) {
            uint256 sum = 0;
            for (uint i = 0; i < my_array.length; i++) {
                sum += my_array[i];
            }
            return sum;
        }
    }
""".trimIndent()

private val storageToMemArrayCopy = """
    contract $contractName {
        $structWithStringArrayDecl
        mapping (address => $structWithStringArrayName) my_mapping;
        function $functionName(address everyday) public returns (uint) {
            $structWithStringArrayName memory read = my_mapping[everyday];
            return read.field_1.length;
        }
    }
""".trimIndent()

private fun ResultType.wrapInStruct() = ResultType.Struct(
    elements = mapOf(BigInteger.ZERO to this)
)

private  fun <T> T.wrapInStruct() : StorageAnalysis.AnalysisPath where T : StorageAnalysis.AnalysisPath, T: StorageAnalysis.HashResult {
    return StorageAnalysis.AnalysisPath.StructAccess(
        base = this,
        offset = StorageAnalysis.Offset.Words(BigInteger.ZERO)
    )
}

private val placeholder = TACSymbol.Var("placeholder", Tag.Bit256)

sealed class AccessPathBuilder {
    abstract fun build() : AccessPath
    val array : AccessPathBuilder
        get() = Dynamic.ArrayBuilder(this.build())
    val map : AccessPathBuilder
        get() = Dynamic.MapBuilder(this.build())
    val static : AccessPathBuilder
        get() = Dynamic.StaticBuilder(this.build())

    abstract fun offset(v: BigInteger) : AccessPathBuilder
    fun offset(v: Int) = this.offset(v.toBigInteger())

    data class Root(private val rootSlot: BigInteger) : AccessPathBuilder() {
        override fun build(): StorageAnalysis.AnalysisPath {
            return AccessPath.Root(rootSlot, StorageAnalysis.Base.STORAGE)
        }

        override fun offset(v: BigInteger): AccessPathBuilder {
            return Root(this.rootSlot + v)
        }

    }

    data class Struct(val offs: BigInteger, val prev: AccessPath) : AccessPathBuilder() {
        override fun build(): AccessPath {
            return AccessPath.StructAccess(
                wordOffset = offs,
                base = prev
            )
        }

        override fun offset(v: BigInteger): AccessPathBuilder {
            return this.copy(offs = this.offs + v)
        }
    }

    sealed class Dynamic : AccessPathBuilder() {
        override fun build(): AccessPath {
            return AccessPath.StructAccess(
                wordOffset = BigInteger.ZERO,
                base = this.buildDynamic()
            )
        }

        abstract fun buildDynamic(): StorageAnalysis.AnalysisPath

        data class ArrayBuilder(val prev: AccessPath) : Dynamic() {
            override fun buildDynamic(): StorageAnalysis.AnalysisPath {
                return AccessPath.ArrayAccess(
                    index = placeholder,
                    base = prev,
                    baseSlot = placeholder
                )
            }
        }

        data class MapBuilder(val prev: AccessPath) : Dynamic() {
            override fun buildDynamic(): StorageAnalysis.AnalysisPath {
                return AccessPath.MapAccess(
                    key = placeholder,
                    base = prev,
                    hashResult = placeholder,
                    baseSlot = placeholder,
                )
            }
        }

        data class StaticBuilder(val prev: AccessPath) : Dynamic() {
            override fun buildDynamic(): StorageAnalysis.AnalysisPath {
                return AccessPath.StaticArrayAccess(
                    index = placeholder,
                    base = prev
                )
            }
        }

        override fun offset(v: BigInteger): AccessPathBuilder {
            return Struct(offs = v, prev = this.buildDynamic())
        }
    }
}

private fun Root(root : BigInteger) = AccessPathBuilder.Root(root)

@SolidityConfigMatrix(
        solidityVersion = [
            SolidityVersion.V4_24,
            SolidityVersion.V5_12,
            SolidityVersion.V6_8,
            SolidityVersion.V6_10,
            SolidityVersion.V7_0
        ],
        withHeaders = false,
        withOptimizeFlag = true
)

@TestTag(EXPENSIVE)
class StorageAnalysisTest : SingleMethodTest {
    @ParameterizedTest
    @ScopedMatrix
    fun testMappingHeap(solc: String, optimize: Boolean) =
        runStorageAnalysis(mapping, solc, optimize, heapTest(setOf(
                ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Mapping(
                                ResultType.Word.wrapInStruct())))))

    @ParameterizedTest
    @ScopedMatrix
    fun testMappingAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(mapping, solc, optimize, accessPathTest(
                    AccessPath.MapAccess(
                            AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder)))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayHeap(solc: String, optimize: Boolean) =
        runStorageAnalysis(array, solc, optimize, heapTest(setOf(
                ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Array(
                                ResultType.Word.wrapInStruct(), elementSize = BigInteger.ONE)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(array, solc, optimize, accessPathTest(
                    AccessPath.ArrayAccess(
                            AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder)))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayWithStructsHeap(solc: String, optimize: Boolean) =
        runStorageAnalysis(structArray, solc, optimize, heapTest(setOf(
                ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Array(
                                ResultType.Struct(mapOf(
                                        BigInteger.ZERO to ResultType.Word,
                                        BigInteger.ONE to ResultType.Word)), BigInteger.TWO)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayWithStructsAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(structArray, solc, optimize, accessPathTest(setOf(
                AccessPath.StructAccess(
                        AccessPath.ArrayAccess(
                                AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder),
                        StorageAnalysis.Offset.Words(BigInteger.ZERO)),
                AccessPath.StructAccess(
                        AccessPath.ArrayAccess(
                                AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder),
                        StorageAnalysis.Offset.Words(BigInteger.ONE)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testMappingWithConstantIndex(solc: String, optimize: Boolean) =
            runStorageAnalysis(constantMapIndex, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                            ResultType.Mapping(
                                    codomain = ResultType.Mapping(
                                            ResultType.Word.wrapInStruct()
                                    ).wrapInStruct()
                            )
                    )
            )))

    @ParameterizedTest
    @ScopedMatrix
    fun testNestedMappingHeap(solc: String, optimize: Boolean) =
        runStorageAnalysis(nestedMapping, solc, optimize, heapTest(setOf(
                ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Mapping(
                                ResultType.Mapping(
                                        ResultType.Word.wrapInStruct()).wrapInStruct())))))

    @ParameterizedTest
    @ScopedMatrix
    fun testNestedMappingAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(nestedMapping, solc, optimize, accessPathTest(
                    AccessPath.MapAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder).wrapInStruct(),
                            placeholder, placeholder, placeholder)))

    @ParameterizedTest
    @ScopedMatrix
    fun testMappingWithNestedStructHeap(solc: String, optimize: Boolean) =
            runStorageAnalysis(nestedStructMapping, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Mapping(
                                ResultType.Struct(mapOf(
                                        BigInteger.ZERO to ResultType.Word,
                                        BigInteger.ONE to ResultType.Word,
                                        BigInteger.TWO to ResultType.Word,
                                        3.toBigInteger() to ResultType.Word)))))))

    @ParameterizedTest
    @ScopedMatrix
    fun testMappingWithNestedStructAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(nestedStructMapping, solc, optimize, accessPathTest(setOf(
                    AccessPath.StructAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder),
                            StorageAnalysis.Offset.Words(BigInteger.ONE)),
                    AccessPath.StructAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder),
                            StorageAnalysis.Offset.Words(BigInteger.TWO)),
                    AccessPath.StructAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder),
                            StorageAnalysis.Offset.Words(BigInteger.ZERO)),
                    AccessPath.StructAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder),
                            StorageAnalysis.Offset.Words(3.toBigInteger())))))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayMappingHeap(solc: String, optimize: Boolean) =
            runStorageAnalysis(arrayMapping, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Mapping(
                                ResultType.Array(
                                        ResultType.Word.wrapInStruct(), BigInteger.ONE).wrapInStruct())))))

    @ParameterizedTest
    @ScopedMatrix
    fun testArrayMappingAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(arrayMapping, solc, optimize, accessPathTest(
                    AccessPath.ArrayAccess(
                            AccessPath.MapAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder, placeholder).wrapInStruct(),
                            placeholder, placeholder).wrapInStruct()))

    @ParameterizedTest
    @ScopedMatrix
    fun testConstantIndexAccessWithStructHeap(solc: String, optimize: Boolean) =
            runStorageAnalysis(constantIndexAccessWithStruct, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Array(
                                ResultType.Struct(mapOf(
                                        BigInteger.ZERO to ResultType.Word,
                                        BigInteger.ONE to ResultType.Word)), BigInteger.TWO)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testConstantIndexAccessWithStructAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(constantIndexAccessWithStruct, solc, optimize, accessPathTest(setOf(
                    AccessPath.StructAccess(
                            AccessPath.ArrayAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder),
                            StorageAnalysis.Offset.Words(BigInteger.ZERO)),
                    AccessPath.StructAccess(
                            AccessPath.ArrayAccess(
                                    AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder),
                            StorageAnalysis.Offset.Words(BigInteger.ONE)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testLoopOverUintArrayHeap(solc: String, optimize: Boolean) =
            runStorageAnalysis(loopOverUintArray, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                        ResultType.Array(
                                ResultType.Word.wrapInStruct(), BigInteger.ONE)))))

    @ParameterizedTest
    @ScopedMatrix
    fun testLoopOverUintArrayAccessPaths(solc: String, optimize: Boolean) =
            runStorageAnalysis(loopOverUintArray, solc, optimize, accessPathTest(setOf(
                    AccessPath.ArrayAccess(
                            AccessPath.Root(BigInteger.ZERO, StorageAnalysis.Base.STORAGE), placeholder, placeholder))))

    @ParameterizedTest
    @ScopedMatrix
    fun testStorageToMemoryCopy(solc: String, optimize: Boolean) =
            runStorageAnalysis(storageToMemArrayCopy, solc, optimize, heapTest(setOf(
                    ResultRoot(BigInteger.ZERO, StorageAnalysis.Base.STORAGE,
                            ResultType.Mapping(
                                    ResultType.Struct(mapOf(
                                            BigInteger.ZERO to
                                                    ResultType.Array(
                                                            ResultType.Array(
                                                                ResultType.Word.wrapInStruct(),
                                                                BigInteger.ONE
                                                            ).wrapInStruct(), BigInteger.ONE))))))))


    @ParameterizedTest
    @SolidityVersions([
            SolidityVersion.V4_24,
            SolidityVersion.V5_12
            ])
    fun testInterpolationPrimitive(solc: String) =
            assertTimeoutPreemptively(Duration.ofMillis(5000)) {
                runStorageAnalysis("""
                    contract $contractName {
                        uint256[] my_array;
                        function $functionName(uint256 n) public {
                            my_array.length += n;
                        }
                    }
                """.trimIndent(), solc, false) { }
            }

    @ParameterizedTest
    @SolidityVersions([
        SolidityVersion.V4_24,
        SolidityVersion.V5_12
    ])
    fun testInterpolationStruct(solc: String) =
            assertTimeoutPreemptively(Duration.ofMillis(5000)) {
                runStorageAnalysis("""
                    contract $contractName {
                        $struct1Decl
                        $struct1Name[] my_array;
                        function $functionName(uint256 n) public {
                            my_array.length += n;
                        }
                    }
                """.trimIndent(), solc, false) { }
            }

    @ParameterizedTest
    @ScopedMatrix
    fun testInterpolationPrimitiveDelete(solc: String) =
            assertTimeoutPreemptively(Duration.ofMillis(5000)) {
                runStorageAnalysis("""
                    contract $contractName {
                        uint256[] my_array;
                        function $functionName() public {
                            delete my_array;
                        }
                    }
                """.trimIndent(), solc, false, false) { _,_ -> }
            }

    @ParameterizedTest
    @ScopedMatrix
    fun testInterpolationStructDelete(solc: String, optimize: Boolean) =
            assertTimeoutPreemptively(Duration.ofMillis(5000)) {
                runStorageAnalysis("""
                    contract $contractName {
                        $struct1Decl
                        $struct1Name[] my_array;
                        function $functionName() public {
                            delete my_array;
                        }
                    }
                """.trimIndent(), solc, optimize, false) { _,_ -> }
            }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V8_2
    ], withOptimizeFlag = true)
    fun testStaticArrayRangesNoOverlap(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
              struct S { uint256 x; uint256 y; }
              // Make sure we're checking array bounds, otherwise we might think
              // the slot for m is part of arr (yes this was a bug)
              uint256[5] arr;
              mapping (address => mapping (address => S)) internal m;

              function $functionName(address a, uint256 b) external {
                m[msg.sender][a].y = b;
              }
            }
            """.trimIndent()
        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(5.toBigInteger()).map.map.offset(1).build()
            )
        )

    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testSimpleToplevelStaticArray(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                uint256[3][4] arr;

                function $functionName(uint a, uint b) public returns (uint) {
                  return arr[a][b];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
        accessPathTest(
                Root(BigInteger.ZERO).static.offset(0).static.offset(0).build()
        )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testArrayLoop(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
               uint[3][8][] f;
               function $functionName(bool b, uint k) public {
                   uint[3][8] storage p = f[k];
                   for (uint i = 1; i < 5; ++i) {
                       for (uint j = 0; j < 2; j++ ) {
                           p[i][j] = i;
                       }
                   }
               }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
             Root(BigInteger.ZERO).array.offset(0).static.offset(0).static.offset(0).build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayOfSimpleArray(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                uint256[][4][4] arr;

                function $functionName(uint a, uint b, uint c) public returns (uint) {
                  return arr[a][b][c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(BigInteger.ZERO).static.offset(0).static.offset(0).array.build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayStructs(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                struct S {
                  uint[2] x;
                  uint[3] y;
                  uint[4] z;
                }
                S[4][4] arr;

                function $functionName(uint a, uint b) public returns (uint) {
                  return arr[a][b].y[1];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(BigInteger.ZERO)
                            .static
                            .offset(0)
                            .static
                            .offset(2)
                            .static
                            .offset(0)
                            .build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayMappingBytes(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                mapping( bytes => bool ) dummy;
                mapping(uint256 => mapping(bytes => uint256)) public fun_mapping;

                function $functionName(uint256 k1, bytes memory k2) public returns (uint256) {
                    return fun_mapping[k1][k2];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(BigInteger.ONE)
                            .map
                            .offset(0)
                            .map
                            .offset(0)
                            .build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6,
        SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStorageJoin(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                mapping(uint256 => uint256) my_mapping;
                mapping(uint256 => uint256) my_mapping2;
                mapping(uint256 => uint256)[] my_mapping_array;
                mapping(uint256 => uint256[]) my_array_mapping;
                uint256 last_accessed_key;


                function $functionName(bool cond, uint256 key) public returns (uint256) {
                    mapping(uint256 => uint256) storage m = get_a_mapping(cond);
                    last_accessed_key = key;
                    return m[key];
                }

                function get_a_mapping(bool cond) private returns (mapping(uint256 => uint256) storage) {
                    if (cond) {
                        return my_mapping;
                    } else {
                        return my_mapping2;
                    }
                }

            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    setOf(
                            Root(BigInteger.ZERO)
                                    .map
                                    .offset(0)
                                    .build(),
                            Root(BigInteger.ONE)
                                    .map
                                    .offset(0)
                                    .build(),
                            Root(4.toBigInteger()).build(),
                    )
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayStructMemoryCopy(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                struct S {
                  uint x;
                  uint y;
                  uint z;
                }
                S[3] dummy0;
                S[4] arr;
                S[3] dummy1;

                function $functionName(uint a) public returns (uint) {
                  uint z = 0;
                  for (uint i = 0; i < a; i = i + 1) {
                    S memory p = arr[a];
                    z = z + p.y;
                  }
                  return z;
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(BigInteger.valueOf(9))
                            .static
                            .offset(1)
                            .build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayJoin(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                uint256[3][4][4] arr;

                function $functionName(uint a, uint b, uint c) public returns (uint) {
                  uint256[3] storage p;
                  if (a == 0) {
                    p = arr[a][b];
                  } else {
                    p = arr[b][a];
                  }
                  return p[c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    Root(BigInteger.ZERO).static.offset(0).static.offset(0).static.offset(0).build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayJoinDifferentShapes(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                struct S {
                  uint[2] x;
                  uint[3] y;
                }
                uint[11] dummy;
                uint256[3][4][5] arr1; // 11
                uint256[3][4][5] dummy2; // 71
                S[4] arr2; //131

                function $functionName(uint a, uint b, uint c) public returns (uint) {
                  uint256[3] storage p;
                  if (c == 0) {
                    p = arr1[a][b];
                  } else {
                    p = arr2[b].y;
                  }
                  return p[c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
        accessPathTest(
                setOf(
                    Root(11.toBigInteger()).static.offset(0).static.offset(0).static.offset(0).build(),
                    Root(131.toBigInteger()).static.offset(2).static.offset(0).build(),
                )
        )
        )
    }
    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayBoundsChecking(solc: String, optimize: Boolean) {
        val contract  = """
            contract $contractName {
                mapping(address => uint[16]) m;
                function $functionName(address a, uint i) public returns (uint) {
                    uint[16] storage p = m[a];
                    uint ret;
                    assembly {
                         ret := sload(add(p.slot, i))
                    }
                    return ret;
                }
            }
        """.trimIndent()
        val method = this.loadTestMethod(contract, solc, optimize)
        assertThrows(StorageAnalysisFailedException::class.java) {
            StorageAnalysis.doAnalysis(
                    (method.code as CoreTACProgram).analysisCache.graph,
                    method.getContainingContract().getStorageLayout(),
                    StorageAnalysis.Base.STORAGE)
        }
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6,
        SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayJoinStruct(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                struct S { uint[2] x; uint[2] y; }
                uint[2] dummy;
                S[4] arr;

                function $functionName(uint a, uint b, uint c) public returns (uint) {
                  uint256[2] storage p;
                  if (a == 0) {
                    p = arr[a].x;
                  } else {
                    p = arr[a-1].y;
                  }
                  return p[c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                    setOf(
                            Root(BigInteger.TWO).static.offset(0).static.offset(0).build(),
                            Root(BigInteger.TWO).static.offset(2).static.offset(0).build(),
                    )
            )
        )
    }

    @ParameterizedTest
    @ScopedMatrix
    fun testSimpleStructJoin(solc: String, optimize:Boolean) {
        val contract = """
            contract $contractName {
              struct S {
                bytes32 a;
                bytes32 b;
              }
              struct T {
                uint p; S s1; S s2; uint q;
              }
              uint[4] padding;
              T t;

              function $functionName(bool c) public returns (bytes32) {
                 S memory p = c ? t.s1 : t.s2;
                 return p.a;
              }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
                accessPathTest(
                        setOf(
                                Root(5.toBigInteger()).build(), Root(7.toBigInteger()).build()
                        )
                )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayOfSimpleMapping(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                uint256[2] dummy;
                mapping(address => uint256)[3][5] arr;

                function $functionName(uint a, uint b, address c) public returns (uint) {
                  return arr[a][b][c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize = optimize, useCompilerHints = true, checks =
            accessPathTest(
                Root(BigInteger.valueOf(2)).static.offset(0).static.offset(0).map.build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2
    ], withOptimizeFlag = false)
    fun testSimpleStaticArrays(solc: String) {
        val contract = """
            contract $contractName {
                struct T { uint[3] x; uint[2] y; }
                T[5][3] arr;

                function $functionName(uint x, uint b, uint c) public returns (uint) {
                   return arr[1][x].y[c];
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, true, useCompilerHints = true, checks = accessPathTest(
                Root(BigInteger.ZERO).static.offset(0).static.offset(3).static.offset(0).build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8,
        SolidityVersion.V8_2, SolidityVersion.V8_16
    ], withOptimizeFlag = true)
    fun testSimplePackedStaticArrays(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                uint72[256] arr1;
                uint72[256][] arr2;

                function $functionName(uint ii, uint i, uint72 x) public {
                      arr1[i] = x;
                      arr2[ii][i] = x;
                }
            }
        """.trimIndent()

        runStorageAnalysis(contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
            setOf(
                Root(BigInteger.ZERO).static.offset(0).build(),
                Root(0x56.toBigInteger()).array.offset(0).static.offset(0).build()
            )
        )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2
    ], withOptimizeFlag = true)
    fun testNestedStaticArrays(solc: String, optimize: Boolean) {
        val innerFields = listOf(
            "uint c",
            "uint[10] d"
        )
        val outerFields = listOf(
            "bool b",
            "Nested[10] a"
        )
        for(outerArrayLoc in 0..1) {
            for(innerArrayLoc in 0..1) {
                val contract = """
            contract $contractName {

                struct Nested {
                   ${innerFields[1 - innerArrayLoc]};
                   ${innerFields[innerArrayLoc]};
                }

                struct S {
                    ${outerFields[1 - outerArrayLoc]};
                    ${outerFields[outerArrayLoc]};
                }

                mapping (address => S) s;

                function $functionName(uint b) public returns (uint) {
                   return s[msg.sender].a[3].c + s[msg.sender].a[4].d[b];
                }
            }
        """.trimIndent()
                runStorageAnalysis(
                    contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                        setOf(
                            Root(BigInteger.ZERO).map.offset(outerArrayLoc).static.offset(innerArrayLoc).static.offset(0).build(),
                            Root(BigInteger.ZERO).map.offset(outerArrayLoc).static.offset((1 - innerArrayLoc) * 10).build()
                        )
                    )
                )
            }
        }
    }


    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2
    ], withOptimizeFlag = true)
    fun testDoubleStaticArrayAllConst(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
               mapping (address => uint[10][5]) s;

               function $functionName() public returns (uint) {
                  return s[msg.sender][4][8];
               }
            }
        """.trimIndent()
        runStorageAnalysis(
            contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                    Root(BigInteger.ZERO).map.static.static.offset(0).build()
                )
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true, withHeaders = true)
    fun testStaticArrayInitialization(solc: String, optimize: Boolean, pragma: String) {
        val bigInit = (1..42).map { "42" }.joinToString(separator = ", ", prefix = "[", postfix = "]")
        val initContract = """
        $pragma
        contract $contractName {
            uint8[3] goo;
            uint8[${bigInit.length}] dummy1;
            function $functionName() public {
              goo = [1, 2, 3];
              dummy1 = ${bigInit};
            }
        }
        """.trimIndent()
        runStorageAnalysis(initContract, solc, optimize, useCompilerHints = true) { _,_ ->
           // We just want to make sure the analysis doesn't fail
        }
    }


    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true, withHeaders = true)
    fun testBytesKeyFallback(solc: String, optimize: Boolean, pragma: String) {
        val initContract = """
        $pragma
        contract $contractName {
            mapping(uint256 => mapping(string => uint8[])) public attributeProbabilities;

            function $functionName(uint256 generation, string memory attribute)
                public
                view
                returns (uint8[] memory)
            {
                return attributeProbabilities[generation][attribute];
            }
        }
        """.trimIndent()
        runStorageAnalysisWithFallbackModel(initContract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                    Root(0.toBigInteger()).map.offset(0).map.offset(0).array.offset(0).build()
                )
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true, withHeaders = true)
    fun testStaticArrayToMemoryCopy(solc: String, optimize: Boolean, pragma: String) {
        for (t in listOf("uint", "bool", "uint24", "uint96", "address")) {
            runStorageAnalysis(staticArrayToMemory(t, pragma), solc, optimize, useCompilerHints = true,
                    accessPathTest(
                            Root(3.toBigInteger()).static.offset(0).build()
                    )
            )
        }
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true, withHeaders = true)
    fun testStaticArrayToStorageCopy(solc: String, optimize: Boolean, pragma: String) {
        for (t in listOf("uint", "bool", "uint24", "uint96", "address")) {
            runStorageAnalysis(staticArrayToStorage(t, pragma, 32), solc, optimize, useCompilerHints = true,
                    accessPathTest(
                            Root(3.toBigInteger()).static.offset(0).build()
                    )
            )
        }
    }
    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true, withHeaders = true)
    fun testStaticArrayToStoragePartialCopy(solc: String, optimize: Boolean, pragma: String) {
        for (t in listOf("uint", "bool", "uint24", "uint96", "address")) {
            runStorageAnalysis(staticArrayToStorage(t, pragma, 16), solc, optimize, useCompilerHints = true,
                    accessPathTest(
                            Root(3.toBigInteger()).static.offset(0).build()
                    )
            )
        }
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayLoopIndex(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
               mapping (address => uint[10][5]) s;

               uint256 public constant DEPOSIT_CONTRACT_TREE_DEPTH = 32;
               bytes32[DEPOSIT_CONTRACT_TREE_DEPTH] public branch;
               uint256 public depositCount;
               bytes32[DEPOSIT_CONTRACT_TREE_DEPTH] public zero_hashes;

               function $functionName(uint b) public returns (bytes32) {
                  bytes32 node;
                  for (uint256 height = 0; height < DEPOSIT_CONTRACT_TREE_DEPTH; height++) {
                        node = sha256(abi.encodePacked(branch[height], node));
                  }
                  return node;
               }
            }
        """.trimIndent()
        runStorageAnalysis(
                contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                        Root(BigInteger.ONE).static.offset(0).build()
                )
        )
        )
    }
        @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testStaticArrayLoopIndexOffset(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
               mapping (address => uint[10][5]) s;

               uint256 public constant DEPOSIT_CONTRACT_TREE_DEPTH = 32;
               bytes32[DEPOSIT_CONTRACT_TREE_DEPTH] public branch;
               uint256 public depositCount;
               bytes32[DEPOSIT_CONTRACT_TREE_DEPTH] public zero_hashes;

               function $functionName() public {
                  for (uint256 height = 0; height < DEPOSIT_CONTRACT_TREE_DEPTH - 1; height++) {
                    zero_hashes[height + 1] = sha256(abi.encodePacked(zero_hashes[height], zero_hashes[height]));
                  }
               }
            }
        """.trimIndent()
        runStorageAnalysis(
                contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                        Root(34.toBigInteger()).static.offset(0).build()
                )
        )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V8_13
    ], withOptimizeFlag = true)
    fun testIndexPrecision(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
                   address[] _adapters;

                    function $functionName()
                        external
                        view
                        returns (uint256[] memory aprs)
                    {
                        uint256 len = _adapters.length;
                        aprs = new uint256[](len);
                        for (uint256 i = 0; i < len; i++) {
                                aprs[i] = 0;
                        }
                    }

            }
        """.trimIndent()
        runStorageAnalysis(
                contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                        Root(0.toBigInteger()).build()
                )
        )
        )
    }


@ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V6_12, SolidityVersion.V6_8, SolidityVersion.V8_2
    ], withOptimizeFlag = true)
    fun testDoubleStaticArray(solc: String, optimize: Boolean) {
        val contract = """
            contract $contractName {
               mapping (address => uint[10][5]) s;

               function $functionName(uint b) public returns (uint) {
                  return s[msg.sender][4][b];
               }
            }
        """.trimIndent()
        runStorageAnalysis(
            contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                    Root(BigInteger.ZERO).map.static.static.offset(0).build()
                )
            )
        )
    }

    private fun heapTest(expectedHeap: Set<ResultRoot>) = { result: StorageAnalysisResult.Complete ->
        val heap = result.contractTree
        assertEquals(expectedHeap.size, heap.size) {
            "Heap expected to have size ${expectedHeap.size} but instead got ${heap.size}"
        }
        expectedHeap.forEach { expectedTree ->
            val tree = heap.firstOrNull { it.slot == expectedTree.slot }
            assertNotNull(tree) { "Expected a tree at slot ${expectedTree.slot} but did not find one"}
            check(tree != null)
            assert(tree.types matches expectedTree.types) {
                "At slot ${expectedTree.slot}, expected shape ${expectedTree.types} but got ${tree.types}"
            }
        }
    }


    private fun allAccessPaths(result: StorageAnalysisResult.Complete) = result.accessedPaths.values.flatMap { it.paths }.toSet()

    private fun accessPathTest(expectedAccessPath: AccessPath) = { result: StorageAnalysisResult.Complete ->
        val allAccessPaths = allAccessPaths(result)
        assert(allAccessPaths.any { it matches expectedAccessPath }) {
            "Expected to find some variable with access path $expectedAccessPath but did not in $allAccessPaths"
        }
    }

    private fun accessPathTest(expectedAccessPaths: Set<AccessPath>) = { result: StorageAnalysisResult.Complete ->
        val allAccessPaths = allAccessPaths(result)
        expectedAccessPaths.forEach { path ->
            assert(allAccessPaths.any { it matches path }) {
                "Expected to find some variable with access path $path but did not in $allAccessPaths"
            }
        }
    }

    private infix fun ResultType.matches(other: ResultType): Boolean =
            when (this) {
                is ResultType.Mapping -> other is ResultType.Mapping && this.codomain matches other.codomain
                is ResultType.Array -> other is ResultType.Array && this.element matches other.element
                is ResultType.Struct -> other is ResultType.Struct && this.elements.size == other.elements.size &&
                        this.elements.all { (offset, type) ->
                            other.elements[offset]?.let { it matches type }?: false }
                is ResultType.Word -> other is ResultType.Word
                is ResultType.Bottom -> other is ResultType.Bottom
                is StorageTree.Type.StaticArray -> other is ResultType.StaticArray && this.numElements == other.numElements &&
                        this.element matches other.element
            }

    private infix fun AccessPath.matches(other: AccessPath): Boolean {
            if(other is AccessPath.StructAccess && other.offset.words == BigInteger.ZERO && !(this is AccessPath.StructAccess && this.offset.words == BigInteger.ZERO)) {
                return other matches this
            }
            return when (this) {
                is AccessPath.MapAccess -> other is AccessPath.MapAccess && this.base matches other.base
                is AccessPath.ArrayAccess -> other is AccessPath.ArrayAccess && this.base matches other.base
                is AccessPath.StructAccess -> (other is AccessPath.StructAccess && this.offset == other.offset && this.base matches other.base)
                        || (this.offset.words == BigInteger.ZERO && this.base matches other)
                is AccessPath.Root -> other is AccessPath.Root && this.slot == other.slot
                is StorageAnalysis.AnalysisPath.WordOffset -> fail("Unexpected wordoffset path hit $this")
                is StorageAnalysis.AnalysisPath.StaticArrayAccess -> other is StorageAnalysis.AnalysisPath.StaticArrayAccess &&
                        other.base matches this.base
            }
    }

    private fun runStorageAnalysis(src: String, solc: String, optimize: Boolean, checks: (StorageAnalysisResult.Complete) -> Unit) {
        runStorageAnalysis(src, solc, optimize, false, checks)
    }

    private fun runStorageAnalysisWithFallbackModel(src: String, solc: String, optimize: Boolean, useCompilerHints: Boolean, checks: (StorageAnalysisResult.Complete) -> Unit) {
        val scene = getTestScene(
            src, solc, optimize
        )
        val method = scene.getContract(NamedContractIdentifier("Test")).getMethods().find {
            it.name == "test"
        }
        assertNotNull(method)
        val code = method!!.code as CoreTACProgram
        val patch = code.toPatchingProgram()
        DisciplinedHashModel.fallbackHashModel(
            patch,
            code,
        )
        val toAnalyze = patch.toCode(code).let {
             StorageLoopSummarizer.annotateLoops(it)
        }

        val layout = method.getContainingContract().getStorageLayout()?.takeIf { useCompilerHints }
        runStorageAnalysis(toAnalyze, layout, checks)
    }


    private fun runStorageAnalysis(src: String, solc: String, optimize: Boolean, useCompilerHints: Boolean, checks: (StorageAnalysisResult.Complete) -> Unit) =
        runStorageAnalysis(src, solc, optimize, useCompilerHints) { _, complete -> checks(complete) }

    private fun runStorageAnalysis(src: String, solc: String, optimize: Boolean, useCompilerHints: Boolean, checks: (TACCommandGraph, StorageAnalysisResult.Complete) -> Unit) {
        val scene = getTestScene(
            src, solc, optimize=optimize
        )
        val method = scene.getContract(NamedContractIdentifier("Test")).getMethods().find {
            it.name == "test"
        }
        assertNotNull(method)
        val code = CallGraphBuilder.doMemoryModel(
            method!!, scene
        ).let { (model, pta) ->
            if(model.isNotEmpty() && pta is FlowPointsToInformation) {
                DisciplinedHashModel.disciplinedHashModel(
                    method as TACMethod,
                    model,
                    pta
                )
            } else {
                method.code as CoreTACProgram
            }
        }.let {
            StorageLoopSummarizer.annotateLoops(it)
        }
        val layout = method.getContainingContract().getStorageLayout()?.takeIf { useCompilerHints }
        runStorageAnalysis(code, layout, checks)
    }

    private fun runStorageAnalysis(code: CoreTACProgram, layout: TACStorageLayout?, checks: (StorageAnalysisResult.Complete) -> Unit) =
        runStorageAnalysis(code, layout) { _, g ->
            checks(g)
        }

    private fun runStorageAnalysis(code: CoreTACProgram, layout: TACStorageLayout?, checks: (TACCommandGraph, StorageAnalysisResult.Complete) -> Unit) =
        try {
            val result = StorageAnalysis.doAnalysis(code.analysisCache.graph, layout, StorageAnalysis.Base.STORAGE) // TODO pass one here?
            Assertions.assertTrue(result is StorageAnalysisResult.Complete) {
                "The storage analysis failed with ${(result as StorageAnalysisResult.Failure).reason.message}"
            }
            checks(code.analysisCache.graph, result as StorageAnalysisResult.Complete)
        } catch (x: Throwable) {
            fail<Nothing>("The storage analysis test failed", x)
        }

    // TODO(jtoman): make sure that 8_2 and 8_9 work on all the other tests, and then update the scoped matrix https://certora.atlassian.net/browse/CER-589
    @ParameterizedTest
    @SolidityConfigMatrix(
        solidityVersion = [
            SolidityVersion.V8_2,
            SolidityVersion.V8_9,
            SolidityVersion.V7_0,
            SolidityVersion.V7_6,
            SolidityVersion.V6_11,
            SolidityVersion.V5_12
        ],
        withOptimizeFlag = true
    )
    fun testMemoryBytesKeys(solc: String, optimize: Boolean) {
        val contract = """
            contract Test {
               mapping (bytes => uint) myMap;

                function test(bytes memory d) public returns (uint) {
                    return myMap[d];
                }
            }
        """.trimIndent()

        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = optimize,
            checks = heapTest(setOf(
                StorageTree.Root(
                    slot = BigInteger.ZERO,
                    base = StorageAnalysis.Base.STORAGE,
                    types = StorageTree.Type.Mapping(StorageTree.Type.Word.wrapInStruct())
                )
            ))
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(
        solidityVersion = [
            SolidityVersion.V8_2,
            SolidityVersion.V8_9,
            SolidityVersion.V7_0,
            SolidityVersion.V7_6,
            SolidityVersion.V6_11,
            SolidityVersion.V5_12
        ],
        withOptimizeFlag = true
    )
    fun testCalldataBytesKeys(solc: String, optimize: Boolean) {
        val contract = """
            contract Test {
               mapping (bytes => uint) myMap;

                function test(bytes calldata d) external returns (uint) {
                    return myMap[d];
                }
            }

        """.trimIndent()
        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = optimize,
            checks = heapTest(setOf(
                StorageTree.Root(
                    slot = BigInteger.ZERO,
                    base = StorageAnalysis.Base.STORAGE,
                    types = StorageTree.Type.Mapping(StorageTree.Type.Word.wrapInStruct())
                )
            ))
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(
        solidityVersion = [
            SolidityVersion.V8_2,
            SolidityVersion.V8_9,
            SolidityVersion.V7_0,
            SolidityVersion.V7_6,
            SolidityVersion.V6_11,
            SolidityVersion.V5_12
        ],
        withOptimizeFlag = true
    )
    fun testBytesKeysGetter(solc: String, optimize: Boolean) {
        val contract = """
            contract Test {
               mapping (bytes => uint) public test;
            }
        """.trimIndent()
        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = optimize,
            checks = heapTest(setOf(
                StorageTree.Root(
                    slot = BigInteger.ZERO,
                    base = StorageAnalysis.Base.STORAGE,
                    types = StorageTree.Type.Mapping(StorageTree.Type.Word.wrapInStruct())
                )
            ))
        )
    }


    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13, SolidityVersion.V8_16
    ], withOptimizeFlag = true)
    fun testImplicitDerefs(solc: String, optimize: Boolean) {
        val contract = """
           contract $contractName{
	        struct Foo {
	        	int256 swag;
	        	uint256[4] ticks;
	        }

	        mapping(address => Foo) user_shares;

	        function $functionName(address user) external {
	        	uint x = user_shares[user].ticks[0];
	        	if(x == 0) {
	        		revert();
	        	}
	        }
        }
        """.trimIndent()
        runStorageAnalysis(
            contract, solc, optimize, useCompilerHints = true, checks = accessPathTest(
                setOf(
                    Root(BigInteger.ZERO).map.offset(1).static.offset(0).build()
                )
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13, SolidityVersion.V8_16
    ], withOptimizeFlag = true)
    fun testComplexInvariants(solc: String, optimize: Boolean) {
        val contract = """
        contract Test {
            uint8 internal constant EMPTY_ELEMENT_OFFSET = 1; // must be 1
            uint8 constant SET_MAX_ELEMENTS = 10;
            uint8 internal constant DUMMY_STAMP = 1;

            struct ElementStorage {
                address value;
                uint96 stamp;
            }

            struct SetStorage {
                uint8 numElements;
                address firstElement;
                uint88 stamp;
                ElementStorage[SET_MAX_ELEMENTS] elements;
            }

            SetStorage setStorageZero;
            SetStorage setStorageTen;

            function test(address element) public returns (bool) {
                return remove(setStorageZero, element) && remove(setStorageTen, element);
            }

            function remove(SetStorage storage setStorage, address element) internal returns (bool) {
                address firstElement = setStorage.firstElement;
                uint256 numElements = setStorage.numElements;

                if (numElements == 0) return false;

                uint256 searchIndex;
                if (firstElement != element) {
                    for (searchIndex = EMPTY_ELEMENT_OFFSET; searchIndex < numElements; ++searchIndex) {
                        if (setStorage.elements[searchIndex].value == element) break;
                    }

                    if (searchIndex == numElements) return false;
                }

                // write full slot at once to avoid SLOAD and bit masking
                if (numElements == 1) {
                    setStorage.numElements = 0;
                    setStorage.firstElement = address(0);
                    setStorage.stamp = DUMMY_STAMP;
                    return true;
                }

                uint256 lastIndex;
                unchecked {
                    lastIndex = numElements - 1;
                }

                // set numElements for every execution path to avoid SSTORE and bit masking when the element removed is
                // firstElement
                ElementStorage storage lastElement = setStorage.elements[lastIndex];
                if (searchIndex != lastIndex) {
                    if (searchIndex == 0) {
                        setStorage.firstElement = lastElement.value;
                        setStorage.numElements = uint8(lastIndex);
                        setStorage.stamp = DUMMY_STAMP;
                    } else {
                        setStorage.elements[searchIndex].value = lastElement.value;

                        setStorage.firstElement = firstElement;
                        setStorage.numElements = uint8(lastIndex);
                        setStorage.stamp = DUMMY_STAMP;
                    }
                } else {
                    setStorage.firstElement = firstElement;
                    setStorage.numElements = uint8(lastIndex);
                    setStorage.stamp = DUMMY_STAMP;
                }

                lastElement.value = address(0);

                return true;
            }
        }""".trimIndent()

        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = optimize,
            useCompilerHints = true,
            checks = accessPathTest(
                setOf(
                    Root(0.toBigInteger()).build(),
                    Root(1.toBigInteger()).static.offset(0).build(),
                    Root(11.toBigInteger()).build(),
                    Root(12.toBigInteger()).static.offset(0).build(),
                )
            )
        )
    }


    @ParameterizedTest
    @SolidityConfigMatrix(solidityVersion = [
        SolidityVersion.V7_6, SolidityVersion.V7_0, SolidityVersion.V8_2, SolidityVersion.V8_11, SolidityVersion.V8_13, SolidityVersion.V8_16
    ], withOptimizeFlag = true)
    fun testForeachLoop(solc: String, optimize: Boolean) {
        // Inspired by Vyper 'for x in y' loops
        val contract = """
            contract $contractName {
                uint256[7] padding;
                uint256[20] s;
                function $functionName() public view {
                    uint i = 0;
                    uint x = 0;
                    do {
                        assembly {
                          x := sload(add(s.slot, i))
                        }
                        if (x == 1) {
                            break;
                        }
                        i = i + 1;
                    } while(i != 20);
                }
            }
        """.trimIndent()

        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = optimize,
            useCompilerHints = true,
            checks = accessPathTest(
                Root(7.toBigInteger()).static.offset(0).build()
            )
        )
    }

    @ParameterizedTest
    @SolidityConfigMatrix(
        solidityVersion = [SolidityVersion.V8_2, SolidityVersion.V8_9, SolidityVersion.V8_11, SolidityVersion.V8_13, SolidityVersion.V8_16],
        withOptimizeFlag = true
    )
    fun testAggressivePruning(solc: String, opt: Boolean) {
        // Tests that the thing that looks like a bytes-key hash/map lookup (but isn't)
        // doesn't confuse the analysis into thinking the bytes key hash summary has no reachable successors
        val contract = """
           contract Child {
	         constructor(uint z) {}
           }

           contract $contractName {
	            function predictAddress(uint y, bytes32 salt) public returns (address) {
	               bytes32 constructorHash = keccak256(abi.encodePacked(type(Child).creationCode, y));
	            	  return address(uint160(uint256(keccak256(abi.encodePacked(uint8(0xff), address(this), salt, constructorHash)))));
	            }

	            function $functionName(uint y, bytes32 salt) external returns (bool) {
	            	  address p = address(new Child{salt: salt}(y));
	            	  return p == predictAddress(y, salt);
	            }
           }
        """.trimIndent()


        runStorageAnalysis(
            solc = solc,
            src = contract,
            optimize = opt,
            useCompilerHints = true,
        ) { code, result ->
            assertTrue(
                !code.commands.any {
                    it.snarrowOrNull<BytesKeyHash>()?.let { summary ->
                        summary.originalBlockStart in result.unreachable &&
                            result.unreachable.containsAll(summary.summarizedBlocks)
                    } == true
                }
            )
        }
    }

    @Test
    fun testZeroLoop() {
        val cfg = TACMockLanguage.make {
            L1021 = 0
            `while`(L(1020, Tag.Bool), "Lt(L1021 0x0)") {
                storage[L1021] = 0
                L1021 = "L1021 + 0x1"
            }
        }

        val prog = CoreTACProgram(
            cfg.code,
            cfg.blockGraph,
            "TestProg",
            TACSymbolTable.withTags(setOf(
                TACSymbol.Var("L1020", Tag.Bool),
                TACSymbol.Var("L1021", Tag.Bit256),
                TACKeyword.STORAGE.toVar()
            )),
            UfAxioms.empty(),
            IProcedural.empty()
        )
        val annotated = StorageLoopSummarizer.annotateLoops(prog)
        annotated.ltacStream().mapNotNull { it.snarrowOrNull<StorageCopyLoopSummary>() }.toList().single().let {
            assertEquals(BigInteger.ZERO, it.numIterations)
        }
    }

    @Test
    fun testOverflowNotInfeasible() {
        val cfg = TACMockLanguage.make {
            L1020 = "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe0"
            L1021 = "0x20"
            L1023 = "L1020 + L1021"
            L1024 = "0x0"
            L1024 = storage[L1024]
        }
        StorageAnalysis.doAnalysis(cfg, null, StorageAnalysis.Base.STORAGE).let { result ->
            result as StorageAnalysisResult.Complete
        }.let {
            assertEquals(
                StorageAnalysisResult.AccessPaths(setOf(Root(BigInteger.ZERO).build())),
                it.accessedPaths.values.singleOrNull()
            )
        }
    }
    @Test
    fun testOverflowNotInfeasibleBranch() {
        val cfg = TACMockLanguage.make {
            L1020 = "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe0"
            L1021 = "0x20"
            L1023 = "L1020 + L1021"
            L1024 = "0x0"
            jump()
            L1024 = storage[L1024]
        }
        StorageAnalysis.doAnalysis(cfg, null, StorageAnalysis.Base.STORAGE).let { result ->
            result as StorageAnalysisResult.Complete
        }.let {
            assertEquals(
                StorageAnalysisResult.AccessPaths(setOf(Root(BigInteger.ZERO).build())),
                it.accessedPaths.values.singleOrNull()
            )
        }
    }

    @Test
    fun testInfeasibleStates() {
        val cfg = TACMockLanguage.make {
            L1020 = `*`
            L1021 = "Le(0x1 L1020)"// Le(L1020 0x2))"
            `if`(L(1021)) {
                L1022 = "Le(L1020 0x2)"
                `if`(L(1022)) {
                    L1023 = "0xA * L1020"
                    L1024 = "Le(0xB L1023)"
                    `if`(L(1024)) {
                        L1024 = "Le(L1023 0x13)"
                        `if`(L(1024)) {
                            storage[L1023] = 123
                        }`else`{
                        }
                    }`else`{
                    }
                }`else`{
                }
            }`else`{
            }
        }

        StorageAnalysis.doAnalysis(cfg, null, StorageAnalysis.Base.STORAGE).let { result ->
            result as StorageAnalysisResult.Complete
        }.let {
            assertEquals(
                StorageAnalysisResult.AccessPaths(setOf()),
                it.accessedPaths.values.singleOrNull()
            )
        }
    }
}
