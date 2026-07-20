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

/**
 * Contract functions are named by the Solidity source, not the spec author: a scene contract whose
 * method happens to share a name with a CVL keyword (observed in the wild with `nativeBalances`) or a
 * CVL type name must typecheck rather than reject the whole scene with
 * [spec.cvlast.typechecker.DeclaredKeyword]. Spec-side declarations of keyword names remain errors
 * (covered by the DeclaredKeyword examples in ErrorTests).
 *
 * Methods named after lexer tokens (`lastStorage` here) register but can't be referenced by name in
 * CVL; the parametric-rule test is what exercises them.
 */
class TestContractMethodKeywordCollision {

    private val contract = """
        contract test {
            function nativeBalances(address a) external view returns (uint) { return a.balance; }
            function lastStorage() external pure returns (uint) { return 1; }
            function beep(uint x) external pure returns (uint) { return x + 1; }
        }
    """.trimIndent()

    // CVL type names (not keywords) are also legal Solidity method names.
    private val typeNamedContract = """
        contract test {
            function env(uint x) external pure returns (uint) { return x; }
            function mathint(uint x) external pure returns (uint) { return x + 1; }
        }
    """.trimIndent()

    /** Typechecks [cvlText] against [contract] and compiles the result to TAC, so the registered
     *  keyword-named methods are exercised by the full pipeline, not just symbol-table filling. */
    private fun compile(contract: String, cvlText: String) {
        val flow = CVLFlow()
        val res = flow.getProverQueryWithScene(
            contract = contract,
            solc = "solc8.13",
            withOptimize = false,
            cvlText = cvlText,
        )
        res.errorOrNull()?.let { error(it.toString()) }
        flow.transformResultsToTACs(res)
    }

    /** The production incident: tooling (e.g. autosetup's compilation analysis) typechecks the scene
     *  under an empty spec, and the keyword-named method alone rejected it. No TAC transform — an
     *  empty spec has no rules; the regression is purely that symbol-table filling succeeds. */
    @Test
    fun keywordNamedMethodInSceneWithEmptySpec() {
        CVLFlow().getProverQueryWithScene(
            contract = contract,
            solc = "solc8.13",
            withOptimize = false,
            cvlText = "",
        ).errorOrNull()?.let { error(it.toString()) }
    }

    @Test
    fun keywordStillResolvesToBuiltinNextToCollidingMethod() {
        compile(
            contract,
            """
            rule keywordWins {
                address a;
                mathint m = nativeBalances[a];
                assert m >= 0;
            }
            """.trimIndent()
        )
    }

    @Test
    fun keywordNamedMethodIsCallable() {
        compile(
            contract,
            """
            rule callIt {
                env e;
                address a;
                assert nativeBalances(e, a) >= 0;
            }
            """.trimIndent()
        )
    }

    @Test
    fun keywordNamedMethodInMethodsBlock() {
        compile(
            contract,
            """
            methods {
                function nativeBalances(address a) external returns (uint) envfree;
            }
            rule useEnvfree {
                address a;
                assert nativeBalances(a) >= 0;
            }
            """.trimIndent()
        )
    }

    @Test
    fun parametricRuleCoversKeywordNamedMethods() {
        compile(
            contract,
            """
            rule param {
                method f;
                env e;
                calldataarg args;
                f(e, args);
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun typeNamedContractMethodTypechecks() {
        compile(
            typeNamedContract,
            """
            rule callTypeNamed {
                env e;
                assert mathint(e, 1) == 2;
            }
            """.trimIndent()
        )
    }
}
