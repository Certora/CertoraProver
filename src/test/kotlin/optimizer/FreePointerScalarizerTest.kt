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

package optimizer

import annotation.ScopedVersions
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotations.TestTags
import loaders.SoliditySceneTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import spec.cvlast.SolidityContract
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.getOperands

@Tag(TestTags.EXPENSIVE)
@SolidityVersions([SolidityVersion.V8_19])
class FreePointerScalarizerTest : SoliditySceneTest {

    /**
     * [TACExpr.Unconstrained] carries no meaning for the SMT backend; it is only lowered when it sits inside a
     * [TACExpr.MapDefinition]. Anywhere else it survives all the way to SMT-LIB generation and kills the query.
     */
    private fun TACExpr.hasUnconstrainedOutsideMapDefinition(): Boolean = when (this) {
        is TACExpr.Unconstrained -> true
        is TACExpr.MapDefinition -> false
        else -> getOperands().any { it.hasUnconstrainedOutsideMapDefinition() }
    }

    @ScopedVersions
    @ParameterizedTest
    fun scalarizationOfUnalignedLongCopyHavocsExplicitly(solc: String) {
        val scene = loadScene("optimizer/UnalignedLongCopy.sol", solc)
        val method = scene.getContractOrNull(SolidityContract("UnalignedLongCopy"))
            ?.getMethods()?.firstOrNull { it.name == "probe" }
            ?: Assertions.fail("Could not find UnalignedLongCopy-probe in $scene")
        val code = method.code as CoreTACProgram

        val offenders = code.analysisCache.graph.commands.filter {
            (it.cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)
                ?.rhs?.hasUnconstrainedOutsideMapDefinition() == true
        }.toList()
        Assertions.assertTrue(offenders.isEmpty()) {
            "Unconstrained left outside a map definition by memory scalarization: $offenders"
        }
    }
}
