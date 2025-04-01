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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import spec.cvlast.typechecker.DisallowedTypeUsedInQuantifier
import kotlin.io.path.Path

class TestCVLQuantifiers: AbstractCVLTest() {


    //TODO: test should return errors CERT-1762
    @ParameterizedTest
    @MethodSource("infra.CartesianProductGenerator#testQuantifiers")
    fun testQuantifiersWithSolidityCall(method: String) {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }

            rule for_all(uint n) {
              require ($method uint256 j . j == foo(j));
              assert n == 0;
            }

        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(
                SpecificType(DisallowedTypeUsedInQuantifier::class, "Test CVL", 4, 6)
            )
        )
    }


    @ParameterizedTest
    @MethodSource("infra.CartesianProductGenerator#testQuantifiersWithTypesRequire")
    fun testQuantifiersWithTypes(castType: String, method: String, action: String) {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }

            rule for_all(uint n) {
              $action ($method uint256 j . ${action}_$castType(j) == 0);
              assert n == 0;
            }

        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(ExpType(action))
        )
    }

    @ParameterizedTest
    @MethodSource("infra.CartesianProductGenerator#testQuantifiers")
    fun testQuantifiersWithCVLFunction(method: String) {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            function fooInC(uint256 i) returns uint256 { require i > 9; return i; }

            rule for_all(uint n) {
              require ($method uint256 j . fooInC(j) == 0);
              assert n == 0;
            }

        """.trimIndent()

        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(
                SpecificType(DisallowedTypeUsedInQuantifier::class, "Test CVL", 2, 4)
            )
        )
    }

    @ParameterizedTest
    @MethodSource("infra.CartesianProductGenerator#testQuantifiers")
    fun testQuantifiersWithCVLDefinition(method: String) {
        val confPath = Path("src/test/resources/cvl/Quantifiers/badExample1/badExample1.conf")
        val primaryContractName = "test"
        val cvlText = """
            methods {
            	function foo(uint256 i) external returns uint256 envfree;
            }
            definition ruleInB(uint256 i) returns uint = foo(i);

            rule for_all(uint n) {
              require ($method uint256 j . ruleInB(j) == 0);
              assert n == 0;
            }

        """.trimIndent()
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(confPath, cvlText, primaryContractName),
            listOf(
                SpecificType(DisallowedTypeUsedInQuantifier::class, "Test CVL", 6, 6)
            )
        )
    }
}
