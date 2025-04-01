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

import annotation.ScopedVersions
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotation.WithOptimizedFlag
import annotations.TestTags.EXPENSIVE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.api.Tag

@SolidityVersions(
    [

        SolidityVersion.V8_2, SolidityVersion.V8_9, SolidityVersion.V8_11, SolidityVersion.V8_13, SolidityVersion.V8_16, SolidityVersion.V8_29
    ]
)
@Tag(EXPENSIVE)
class PointsToTestV8 : PointsToTest() {
    @ScopedVersions
    @WithOptimizedFlag
    @ParameterizedTest
    fun testMemoryToStorageCopy(solc: String, optimize: Boolean) {
        this.assertAnalysisSucceeds(contract = """

contract Test {
	mapping (address => string) swag;

	function test(string memory yeet) external {
		swag[msg.sender] = yeet;
	}
}
        """.trimIndent(), solc = solc, optimize = optimize)
    }

    @ParameterizedTest
    @WithOptimizedFlag
    @ScopedVersions
    fun testInlineUnderflowReasoning(solc: String, optimize: Boolean) {
        this.assertAnalysisSucceeds(
            solc = solc,
            optimize = optimize,
            contract =  """
                        contract Test {
                            function test(bytes memory data, uint256 offset) public pure returns (uint256 currentValue) {
                                require(offset <= data.length - 32);
                                assembly {
                                    currentValue := mload(add(32, add(data, offset)))
                                }
                            }
                        }
                        """.trimIndent()
        )
    }
}
