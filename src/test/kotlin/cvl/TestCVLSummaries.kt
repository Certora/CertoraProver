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

package cvl

import infra.CVLFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import scene.ProverQuery
import scene.TEST_SPEC_FILE_NAME
import spec.CVL
import spec.cvlast.SpecCallSummary
import spec.cvlast.typechecker.*
import utils.*
import utils.CollectingResult.Companion.map
import java.math.BigInteger
import kotlin.io.path.Path

class TestCVLSummaries : AbstractCVLTest() {

    @Test
    fun testCVLTypechecking_ExactFunctionSummary() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLTypechecking/ExactFunctionSummary/confExample/confExample.conf")),
            listOf(GeneralType("Exact.spec", 2, 2))
        )
    }

    //ToDo: test shouldn't return any error CERT-1893
    @Test
    fun testLibInternalSummary() {
        val confPath = Path("src/test/resources/cvl/Summarization/Library/badExample/badExample.conf")
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath),
            listOf(SummaryCVL(
                    listOf("priv"),
                    Scope.internal,
                    null,
                    listOf(getEVMType("uint256")),
                    listOf(SpecCallSummary.Always::class))
            )
        )
    }

    @ParameterizedTest
    @CsvSource(
        "fooInt,int256",
        "fooUInt,uint256",
        "fooBool,bool",
        "fooAddress,address",
        "fooString,string",
        "fooBytes,bytes"
    )
    fun testGhostSummaryPrimitives(functionToSummarize: String, inputType: String) {
        val confPath = Path("src/test/resources/cvl/Summarization/Ghost/primitives/Test/test.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
                function $functionToSummarize($inputType x) external returns $inputType => ghostSummary(x);
            }
            function ghostSummary($inputType x) returns $inputType {
                return x;
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                SummaryCVL(
                        listOf(functionToSummarize),
                        Scope.valueOf("external"),
                        listOf(SummaryParameter("x", getEVMType(inputType))),
                        listOf(getEVMType(inputType)),
                        listOf(SpecCallSummary.Exp::class)
                )

        )
        )
    }


    @ParameterizedTest
    @MethodSource("infra.CartesianProductGenerator#testSummary")
    fun testSummaryWithInternalAndExternalFlags(scope: String, returns: String) {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) $scope returns (uint256) => $returns ALL;
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(
                    SummaryCVL(
                            listOf("foo"),
                            Scope.valueOf(scope),
                            listOf(SummaryParameter("i", getEVMType("uint256"))),
                            listOf(getEVMType("uint256")),
                            listOf(SpecCallSummary.Always::class, SpecCallSummary.HavocSummary.Nondet::class)
                    )
            )
        )
    }


    @ParameterizedTest
    @CsvSource(
        "fooInt,int256",
        "fooUInt,uint256"
    )
    fun testGhostSummaryMathInt(functionToSummarize: String, inputType: String) {
        val confPath = Path("src/test/resources/cvl/Summarization/Ghost/primitives/Test/test.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
                function $functionToSummarize($inputType x) external returns $inputType => ghostSummary(x);
            }
            function ghostSummary($inputType x) returns $inputType {
                mathint y = to_mathint(x);
                return assert_$inputType(y);
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                SummaryCVL(
                        listOf(functionToSummarize),
                        Scope.valueOf("external"),
                        listOf(SummaryParameter("x", getEVMType(inputType))),
                        listOf(getEVMType(inputType)),
                        listOf(SpecCallSummary.Exp::class)
                )

        )
        )
    }


    //Todo: need to add struct return case (extend the current predicate / create new one)
    @ParameterizedTest
    @CsvSource(
        "fooArray, uint[], uint[] x = [1]",
        "fooArrayInt, int[], int[] x = [1]"
    )
    fun testGhostSummaryNonePrimitive(functionToSummarize: String, type: String, value: String) {
        val confPath = Path("src/test/resources/cvl/Summarization/Ghost/primitives/Test/test.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
                function $functionToSummarize() external returns $type => ghostSummary();
            }
            function ghostSummary() returns $type {
                $value;
                return x;
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                SummaryCVL(
                        listOf(functionToSummarize),
                        Scope.valueOf("external"),
                        null,
                        listOf(getEVMType(type)),
                        listOf(SpecCallSummary.Exp::class)
                )
        )
        )
    }

    @ParameterizedTest
    @CsvSource(
        "fooInt,int256,uint256",
        "fooUInt,uint256,bool",
        "fooBool,bool,address",
        "fooAddress,address,string",
        "fooString,string,bytes",
        "fooBytes,bytes,int256"
    )

    fun testGhostSummaryBadReturnType(functionToSummarize: String, inputType: String, summaryType: String) {
        val confPath = Path("src/test/resources/cvl/Summarization/Ghost/primitives/Test/test.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
                function $functionToSummarize($inputType x) external returns $inputType => ghostSummary(x);
            }
            function ghostSummary($inputType x) returns $summaryType {
                $summaryType y;
                return y;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                ExpType("ghostSummary")
        )
        )
    }

    @Test
    fun testSolidityFunctionAsSummary() {
        val confPath = Path("src/test/resources/cvl/CVLTypechecking/Summarization/SolidityFunctionSummary/test.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function foo() external returns (uint) envfree => foo();
            }
            rule r {
                assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                GeneralType(TEST_SPEC_FILE_NAME, 1, 1)
        )
        )
    }

    @Test
    fun testCanSummarizeComplexTypes() {
        val confPath = Path("src/test/resources/cvl/Summarization/CalldataInternal/Default.conf")
        val primaryContractName = "Test"
        val cvlText = """
            methods {
               function myInternal4(Test.TooComplex calldata z) internal returns (uint) => mySummary(z);
            }

            function mySummary(Test.TooComplex z) returns uint {
                 return 0;
            }

            rule r {
                 env e;
                 calldataarg a;
                 entryPoint1(e, a);
                 assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
                SummaryCVL(
                    listOf("myInternal4"),
                    Scope.internal,
                    null,
                    null,
                    listOf(SpecCallSummary.Exp::class)
                )
            )
        )
    }

    @Test
    fun testDispatchListNoCatchAll() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/DynamicDispatch/Dynamic.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function _._ external => DISPATCH [
                    C.bar(uint),
                    _.update(uint),
                    _._
                ] default NONDET;
            }

            rule easy {
                assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
            SpecificType(FullWildcardInDispatchList::class, TEST_SPEC_FILE_NAME, 4, 4)
        ))
    }

    @Test
    fun testUnresolvedSummaryNotCatchAll() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/DynamicDispatch/Dynamic.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function C._ external => DISPATCH [
                    C.bar(uint),
                    _.update(uint),
                ] default NONDET;
            }

            rule easy {
                assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
            SpecificType(DispatchListWithSpecificId::class, TEST_SPEC_FILE_NAME, 1, 1)
        ))
    }

    @Test
    fun testDispatchListAlwaysExternal() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/DynamicDispatch/Dynamic.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function _._ internal => DISPATCH [
                    C.bar(uint),
                    _.update(uint),
                    Other._
                ] default NONDET;
            }

            rule easy {
                assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
            SpecificType(OnlyExternalSummaryAllowed::class, TEST_SPEC_FILE_NAME, 1, 1)
        ))
    }

    @Test
    fun testDispatchListOk() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/DynamicDispatch/Dynamic.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function _._ external => DISPATCH [
                    C.bar(uint),
                    _.update(uint),
                    C._
                ] default NONDET;
            }

            rule easy {
                assert true;
            }
        """.trimIndent()
        testFlowWithPredicatesCVL(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf())
    }

    @Test
    fun testDispatchListGetMethods() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/DynamicDispatch/Dynamic.conf")
        val primaryContractName = "C"
        val cvlText = """
            methods {
                function _._ external => DISPATCH [
                    C.bar(uint),
                    _.update(uint),
                    C._
                ] default NONDET;
            }

            rule easy {
                assert true;
            }
        """.trimIndent()
        val qWithScene = CVLFlow().getProverQueryWithScene(confPath, cvlText, primaryContractName)
        testFlowWithPredicatesCVL(qWithScene.map { it.second }, listOf())
        val q = qWithScene.map { it.second }
        val scene = qWithScene.map { it.first }.resultOrNull()!!
        val cvl = q.resultOrNull()!!.let {
            when (it) {
                is ProverQuery.EquivalenceQuery -> `impossible!`
                is ProverQuery.CVLQuery.Single -> it.cvl
            }
        }
        val unresolvedSummaries = cvl.unresolvedSummaries[CVL.ExternalUnresolved]
        assert(unresolvedSummaries != null) {"Expecting unresolved summary, none found"}
        val allMethods = unresolvedSummaries!!.getMethods(scene, setOf(), null)
        val dl = unresolvedSummaries.dispatcherList
        val exactPattern = (dl[0] as? SpecCallSummary.DispatchList.Pattern.QualifiedMethod).also {
            assert(it != null) { "First pattern should be a qualified method" }
        }.let { it!! }
        val wildContract = (dl[1] as? SpecCallSummary.DispatchList.Pattern.WildcardContract).also {
            assert(it != null) { "First pattern should be a qualified method" }
        }.let { it!! }
        val wildFun = (dl[2] as? SpecCallSummary.DispatchList.Pattern.WildcardMethod).also {
            assert(it != null) {"First pattern should be a qualified method"}
        }.let { it!! }

        val qualified = exactPattern.getMethods(scene, setOf(), null).also {
            assert(it.size == 1) {"Expecting only one qualified method"}
        }.first()
        // Q: do we really want the kotlin asserts here?? (not seeing any JUnit assertTrue, and the like ..)
        assert(qualified.getContainingContract().name == "C")
        assert(qualified.soliditySignature == "bar(uint256)")
        assert(exactPattern.getMethods(scene, setOf(), qualified.getContainingContract().instanceId.plus(BigInteger.ONE)).isEmpty())
        assert(exactPattern.getMethods(scene, setOf(qualified.sigHash!!.n.plus(BigInteger.ONE)), null).isEmpty())
        assert(allMethods.contains(qualified))

        val (cUpdate, oUpdate) = wildContract.getMethods(scene, setOf(), null).also {
            assert(it.size == 2) {"Expecting exactly two instances, C.update(uint) and Other.update(uint)"}
            assert(it.all { m -> m.soliditySignature == "update(uint256)" })
            assert(allMethods.containsAll(it))
        }.let { m ->
            m.find { it.getContainingContract().name == "C" }!! to m.find { it.getContainingContract().name == "Other" }!!
        }
        assert(wildContract.getMethods(scene, setOf(cUpdate.sigHash!!.n, oUpdate.sigHash!!.n, BigInteger.ONE), null).toSet() == setOf(cUpdate, oUpdate))
        assert(wildContract.getMethods(scene, setOf(), oUpdate.getContainingContract().instanceId).toSet() == setOf(oUpdate))

        val methods = wildFun.getMethods(scene, setOf(), null).also {
            assert(it.size == 4) {"Expecting exactly four function in C."}
            assert(it.all { m -> m.getContainingContract().name == "C" })
            assert(allMethods.containsAll(it))
        }
        assert(methods.find { it.soliditySignature == "update(uint256)" } != null)
        assert(wildFun.getMethods(scene, setOf(cUpdate.sigHash!!.n, oUpdate.sigHash!!.n, BigInteger.ONE), null).toSet() == setOf(cUpdate))
        assert(wildFun.getMethods(scene, setOf(), oUpdate.getContainingContract().instanceId).isEmpty())

        assert(allMethods.all { it == cUpdate || it == oUpdate || it == qualified || it in methods })
    }


    @Test
    fun testExceptionOnEmptyContractDispatcher() {
        val confPath = Path("lib/Shared/src/test/resources/OptimisticDispatcher/EmptyContract.conf")
        val primaryContractName = "EmptyContract"
        val cvlText = """
            methods {
	            function _.getRekt() external => DISPATCHER(true);
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
            SpecificType(DispatcherSummaryNoImplementation::class, TEST_SPEC_FILE_NAME, 1, 1)
        ))
    }


    @Test
    fun testDispatchFromOtherContractShouldSucceed() {
        val confPath = Path("lib/Shared/src/test/resources/OptimisticDispatcher/MethodFromOtherContract.conf")
        val primaryContractName = "EmptyContract"
        val cvlText = """
            methods {
	            function _.getRekt() external => DISPATCHER(true);
            }
        """.trimIndent()
        testNoErrors(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }


    @Test
    fun testMethodOverloadingAddressArgument() {
        val confPath = Path("lib/Shared/src/test/resources/OptimisticDispatcher/MethodOverloading.conf")
        val primaryContractName = "MethodOverloading"
        val cvlText = """
            methods {
	            function _.getRekt(address) external => DISPATCHER(true);
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName), listOf(
            SpecificType(DispatcherSummaryNoImplementation::class, TEST_SPEC_FILE_NAME, 1, 1)
        ))
    }


    @Test
    fun testMethodOverloadingStringArgument() {
        val confPath = Path("lib/Shared/src/test/resources/OptimisticDispatcher/MethodOverloading.conf")
        val primaryContractName = "MethodOverloading"
        val cvlText = """
            methods {
                function _.getRekt(string) external => DISPATCHER(true);
            }
        """.trimIndent()
        testNoErrors(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testMethodOverloadingNoArguments() {
        val confPath = Path("lib/Shared/src/test/resources/OptimisticDispatcher/MethodOverloading.conf")
        val primaryContractName = "MethodOverloading"
        val cvlText = """
            methods {
	            function _.getRekt() external => DISPATCHER(true);
            }
        """.trimIndent()
        testNoErrors(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }
}
