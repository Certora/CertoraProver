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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import scene.ProverQuery
import spec.cvlast.SpecType
import spec.cvlast.typechecker.ToCVLTypeError
import spec.rules.CVLSingleRule
import kotlin.io.path.Path


final class TestCVL : AbstractCVLTest() {
    @Test
    fun testTextFlowWithPredicate() {
        val confPath = Path("src/test/resources/cvl/Bank/BankTestConf/BankTestConf.conf")
        val primaryContractName = "Bank"
        val cvlText = """
             methods {
             	function getfunds(address) external returns uint256;
             }

             invariant address_zero_cannot_become_an_account()
             getfunds(0) == 0;
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(ExpType("getfunds"))
        )
    }

    @Test
    /**
     * at some point, this test started logging a warning about a file read error.
     * this only happens after TAC serialization (which is what we actually test here). we won't fix this for now.
     */
    fun testTACFile() {
        val confPath = Path("src/test/resources/cvl/Example/goodExample/goodExample.conf")

        val flow = CVLFlow()
        val tacs = flow.transformResultsToTACs(flow.getProverQuery(confPath))
        assert(
            tacs.isNotEmpty()
        ) { "error tac list is empty" }
    }

    @Test
    /**
     * at some point, this test started logging a warning about a file read error.
     * this only happens after TAC serialization (which is what we actually test here). we won't fix this for now.
     */
    fun testTactText() {
        val confPath = Path("src/test/resources/cvl/Example/goodExample/goodExample.conf")
        val primaryContractName = "dummy"
        val cvlText = """
            methods {
                function yalla(uint x) external returns bool envfree;
            }

            rule zugiBool(uint x) {
                assert yalla(x);
            }
        """.trimIndent()
        val flow = CVLFlow()
        val tacs = flow.transformResultsToTACs(flow.getProverQuery(confPath, cvlText, primaryContractName))

        assert(
            tacs.isNotEmpty()
        ) { "error tac list is empty" }
    }

    @Test
    fun testGoodExample() {
        val confPath = Path("src/test/resources/cvl/Bank/BankTestConf/BankTestConf.conf")
        val primaryContractName = "Bank"
        val cvlText = """
            methods {
            	function getfunds(address) external returns uint256 envfree;
            }

            invariant address_zero_cannot_become_an_account()
            getfunds(0) == 0;

        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testMultipleBlockStatements() {
        val confPath = Path("src/test/resources/cvl/Bank/BankTestConf/BankTestConf.conf")
        val primaryContractName = "Bank"
        val cvlText = """
            rule r {
                uint b;
                {
                    uint a;
                }
                {
                    uint8 a;
                }
                bytes a;
                assert false;
            }

        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testApplyExpressions() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/ApplyExpressions/confExample/confExample.conf")),
            listOf(ExpType("my_def"), ExpType("my_ghost"))
        )
    }

    @Test
    fun testMethodSignatures1() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLSyntax/MethodSignatures/badExample1/badExample1.conf")),
            listOf(GeneralType("spec1.spec", 5, 7))
        )
    }

    @Test
    fun testMethodSignatures2() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLSyntax/MethodSignatures/badExample2/badExample2.conf")),
            listOf(GeneralType("spec2.spec", 1, 1))
        )
    }

    @Test
    fun testUsing1() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLSyntax/using/badExample1/badExample1.conf")),
            listOf(GeneralType("using_1.spec", 1, 1))
        )
    }

    @Test
    fun testUsing2() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLSyntax/using/badExample2/badExample2.conf")),
            listOf(ExpType("getFunds_missing"), ExpType("getFunds_missing2"))
        )
    }

    @Test
    fun testUsing3() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLSyntax/using/badExample3/badExample3.conf")),
            listOf(GeneralType("using_3.spec", 2, 2))
        )
    }

    @Test
    fun testGhostHooks() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/Ghost/hooks/confExample/confExample.conf")), listOf(
                GeneralType("bad/hooks.spec", 8, 10),
                GeneralType("bad/hooks.spec", 12, 14),
                GeneralType("bad/hooks.spec", 21, 23),
                GeneralType("bad/hooks.spec", 25, 27)
            )
        )
    }

    @Test
    fun testMultiContract() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/Ghost/MultiContract/confExample/confExample.conf")),
            listOf(GeneralType("bad/bad.spec", 23, 25))
        )
    }

    @Test
    fun testCVLTypechecking_ContractTypes_EnumsStructsValueTypes_solc8() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLTypechecking/ContractTypes/EnumsStructsValueTypes/confExample/solc8/solc8.conf")),
            listOf(
                GeneralType("bad/Test.spec", 3, 24),
                SpecificType(ToCVLTypeError::class, "bad/Test.spec", 3, 24)
            )
        )
    }

    @Test
    fun testCVLTypechecking_ContractTypes_EnumsStructsValueTypes_solc8_2() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/CVLTypechecking/ContractTypes/EnumsStructsValueTypes/confExample/solc8_2/solc8_2.conf")),
            listOf(SpecificType(ToCVLTypeError::class, "bad/Test2.spec", 4, 10))
        )
    }


    @Test
    fun testDefinitions() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/Definitions/confExample/confExample.conf")),
            listOf(
                SpecificType(ToCVLTypeError::class, "bad/badSpecs.spec", 11, 11),
                SpecificType(ToCVLTypeError::class, "bad/badSpecs.spec", 13, 13)
            )
        )
    }

    @Test
    fun testFunctionPrefix() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
               function setManagedBalance(address, uint256) internal => NONDET;
            }
        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testMethodSig() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }

            rule for_all(method f, uint i) {
              require f.selector == sig:foo(uint256).selector;
              assert i == 0;
            }

        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testStrictOrderAnnotation() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }
        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testStructAccess() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
                function foo2(uint256 i) external returns test.Asset envfree;
            }

            rule example(uint i) {
                test.Asset x = foo2(i);
                assert i == 1;
            }
        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testOptionalMethod() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo3(uint256 i) external returns uint256 envfree optional;
            }
            """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    /**
     * A rule that reaches a missing optional method only through `requireInvariant` must be marked
     * [SpecType.Single.SkippedMissingOptionalMethod], since the invariant body gets inlined into it.
     */
    @Test
    fun testRequireInvariantOfMissingOptionalMethod() {
        val confPath = Path("src/test/resources/cvl/Bank/BankTestConf/BankTestConf.conf")
        val primaryContractName = "Bank"
        val cvlText = """
            methods {
                function missingGetter() external returns uint256 envfree optional;
            }

            invariant getterPositive()
                missingGetter() > 0;

            rule requireInvariantOverMissingOptional() {
                requireInvariant getterPositive();
                assert true;
            }
            """.trimIndent()
        val result = CVLFlow().getProverQuery(confPath, cvlText, primaryContractName)
        testNoErrors(result)

        val cvl = (result.force() as ProverQuery.CVLQuery.Single).cvl
        val rule = cvl.rules
            .filterIsInstance<CVLSingleRule>()
            .single { it.declarationId == "requireInvariantOverMissingOptional" }
        assert(rule.ruleType is SpecType.Single.SkippedMissingOptionalMethod) {
            "expected the rule to be skipped, got rule type ${rule.ruleType}"
        }
    }

    @Test
    fun testMandatoryReturn() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }
            """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testMandatoryExpect() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
                function foo(uint256 i) external returns uint256 envfree;
                function _.foo() internal => fooImpl() expect uint256 ALL;
            }
            function fooImpl() returns uint256 {
                require 9 > 9;
                return 1;
             }
            """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testPassMathIntToSolidityCall() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }

            rule example(uint i) {
                foo(i + 1);
                assert i == 2;
            }
        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(ExpType("foo"))
        )
    }

    @Test
    fun testInvokeSyntax() {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            rule sanity {
            env e;
            calldataarg arg;
            method f;
            f@withrevert(e,arg);
            f(e,arg);
            assert false;
        }
        """.trimIndent()
        testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testMissingFunctionKW() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(
                confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf"),
                primaryContractName = "test",
                cvlText = """
                    methods {
                        /* no function */ example0() internal returns(uint);
                    }
                """.trimIndent()
            ),
            predicates = listOf(
                ErrorContaining(1, "Since CVL 2", "function")
            )
        )
    }

    /** Enumerates all Pure CVL types and ensures that we can declare a variable of that type */
    @TestFactory fun testCVLTypes(): List<DynamicTest> {
        // we don't include the numberedTypes just because we don't want to blow up the set of complex types
        val basicValueTypes = listOf(
            "address",
            "bool",
            "int",
            "uint",
            "int8",   // other numbered variants exist, but we want to keep the list somewhat short
            "uint256",
            "bytes1",
            "bytes2",
            "bytes32",
            // not supported: "Contract",
            "Contract.ExampleStruct",
            "Contract.ExampleEnum",
            "Contract.ExampleUDVT",
            "Secondary.SecondaryStruct",
            "Interface.InterfaceStruct",
            "Contract.TopLevelStruct",
            "Contract.TopLevelUDVT",
            "Contract.TopLevelEnum",
            "Inheriting.TopLevelStruct",
            // not supported: "Interface.TopLevelStruct",
            // not supported: "Inheriting.InterfaceStruct",
        )

        val basicReferenceTypes = listOf(
            "string",
            "bytes",
            "exampleSort"
        )

        val complexTypes = basicValueTypes.flatMap { listOf(
            it + "[]",
            it + "[7]",
        )}

        val allCVLTypes = basicValueTypes + basicReferenceTypes + complexTypes
        return allCVLTypes.map { type ->
            DynamicTest.dynamicTest(type) test@{
                val confPath = Path("lib/Shared/src/test/resources/Types.conf")
                val primaryContractName = "Contract"
                val cvlText = """
                    sort exampleSort;
                    function example() {
                        $type x;
                    }
                    """.trimIndent()
                println("testing: ")
                println(cvlText.prependIndent("  | "))
                testNoErrors(CVLFlow().getProverQuery(confPath, cvlText, primaryContractName))
            }
        }
    }

    @Test
    fun testExtraEnvArgument() {
        val confPath = Path("src/test/resources/cvl/Example/goodExample/goodExample.conf")
        val primaryContractName = "dummy"
        val cvlText = """
            methods {
                function yalla(uint x) external returns bool envfree;
            }

            rule zugiBool(uint x) {
                env e;
                calldataarg args;
                assert yalla(e, args);
            }
        """.trimIndent()
        val flow = CVLFlow()
        testNoErrors(flow.getProverQuery(confPath, cvlText, primaryContractName))
    }

    @Test
    fun testParenthesizedRequireInvariant() {
        val confPath = Path("src/test/resources/cvl/CVLSyntax/requireInvariant/Good.conf")
        val primaryContractName = "emptyRule"
        val cvlText = """
            ghost g(uint) returns uint
            {
            	init_state axiom forall uint x. g(x) >= 10;
            }
            invariant gInv(uint x) g(x) >= 10;
            rule r(uint x) {
            	requireInvariant(gInv(x));
            	assert g(x) >= 8;
            }
        """.trimIndent()
        val flow = CVLFlow()
        testNoErrors(flow.getProverQuery(confPath, cvlText, primaryContractName))
    }

//    TODO: It would be nice to get better error messages in this case, but it requires a more substantial refactor
//    @Test
//    fun testMisplacedModifiers() {
//        testTextFlow(
//            confPath = "/Test/Quantifiers/badExample1/badExample1.conf",
//            primaryContractName = "test",
//            cvlText = """
//                    methods {
//                        function example0() returns(uint) internal envfree;
//                        function example1() returns(uint) envfree internal;
//                        function example2() envfree internal returns(uint);
//                        function example3() envfree returns(uint) internal;
//                        function example4() internal envfree returns(uint);
//                        function example5() internal returns(uint) envfree;
//                    }
//                """.trimIndent(),
//            predicates = listOf(
//                ErrorContaining(1, "internal", "must come before"),
//                ErrorContaining(2, "internal", "must come before"),
//                ErrorContaining(3, "must come before"),
//                ErrorContaining(4, "must come before"),
//                ErrorContaining(5, "returns", "must come before"),
//            )
//        )
//
//    }


}
