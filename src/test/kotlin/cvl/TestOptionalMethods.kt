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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import scene.ProverQuery
import spec.cvlast.CurrentContract
import spec.cvlast.SpecType
import kotlin.io.path.Path

/**
 * A method declared `optional` in the methods block that has no counterpart in the verified contract stays an
 * unresolved application after type checking. The `SkipOptionalRules` pass has to find every such application in a
 * rule, so that the rule is skipped instead of reaching the compiler.
 */
class TestOptionalMethods : AbstractCVLTest() {
    private val confPath = Path("src/test/resources/cvl/Example/goodExample/goodExample.conf")
    private val primaryContractName = "dummy"

    private val methodsBlockAndCVLFunction = """
        methods {
            function missing(uint x) external returns uint256 envfree optional;
        }

        function isTwo(uint256 s) returns bool {
            return s == 2;
        }
    """.trimIndent()

    private fun ruleTypeOf(ruleText: String): SpecType {
        val query = CVLFlow().getProverQuery(confPath, "$methodsBlockAndCVLFunction\n\n$ruleText", primaryContractName)
        val cvl = (query.force() as ProverQuery.CVLQuery.Single).cvl
        return cvl.rules.single { it.declarationId == "r" }.ruleType
    }

    private fun assertSkipped(ruleText: String) {
        assertEquals(
            SpecType.Single.SkippedMissingOptionalMethod("missing", CurrentContract.name),
            ruleTypeOf(ruleText)
        )
    }

    @Test
    fun callAtTopLevelOfRule() {
        assertSkipped(
            """
            rule r(uint256 id) {
                require missing(id) == 2;
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun callInsideCVLFunctionBody() {
        assertSkipped(
            """
            function callsMissing(uint256 id) returns bool {
                return missing(id) == 2;
            }

            rule r(uint256 id) {
                require callsMissing(id);
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun callAsArgumentOfCVLFunction() {
        assertSkipped(
            """
            rule r(uint256 id) {
                require isTwo(missing(id));
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun callAsArgumentBesideAnotherCVLFunctionCall() {
        assertSkipped(
            """
            rule r(uint256 id) {
                require isTwo(missing(id)) && isTwo(id);
                assert true;
            }
            """.trimIndent()
        )
    }
}
