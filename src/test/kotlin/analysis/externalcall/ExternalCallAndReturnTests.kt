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

package analysis.externalcall

import annotation.ScopedMatrix
import annotation.SolidityConfigMatrix
import annotation.Z3Timeout
import annotations.TestTags.EXPENSIVE
import extension.ForceZ3Timeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest

abstract class ExternalCallAndReturnTest(partition: Int) : AbstractExternalCallCopyTest(partition) {
    @ParameterizedTest
    @ScopedMatrix
    fun testCallAndReturnNondet(solc: String, optimize: Boolean, pragma: String) {
        for(input in inner) {
            for(output in outer) {
                val contractSource = """
                    $pragma

                    contract Test {
                        function extAction($input memory input) public returns ($output memory)  {
                            return new $output(input.length);
                        }

                        function test(uint nondet) public returns (uint)  {
                            $output memory returned = this.extAction(new $input(nondet));
                            return returned.length;
                        }
                    }
                """.trimIndent()
                runTestPipeline(contractSource, solc, optimize) {
                    "Failure with input = $input and output = $output"
                }
            }
        }
    }
}

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition0 : ExternalCallAndReturnTest(0)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition1 : ExternalCallAndReturnTest(1)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition2 : ExternalCallAndReturnTest(2)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition3 : ExternalCallAndReturnTest(3)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition4 : ExternalCallAndReturnTest(4)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition5 : ExternalCallAndReturnTest(5)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition6 : ExternalCallAndReturnTest(6)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition7 : ExternalCallAndReturnTest(7)

@SolidityConfigMatrix(
    withHeaders = true,
    withOptimizeFlag = true
)
@ExtendWith(ForceZ3Timeout::class)
@Z3Timeout(timeoutMs = 3000)
@Tag(EXPENSIVE)
class CallAndReturnPartition8 : ExternalCallAndReturnTest(8)
