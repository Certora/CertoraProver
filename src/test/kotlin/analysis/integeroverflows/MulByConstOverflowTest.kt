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

package analysis.integeroverflows

import analysis.integeroverflows.OverflowTestAux.TestConfig
import analysis.integeroverflows.OverflowTestAux.checkMul
import analysis.integeroverflows.OverflowTestAux.configSequence
import analysis.integeroverflows.OverflowTestAux.isMathintOutOfRangeCVLText
import analysis.integeroverflows.OverflowTestAux.runContractAndSpec
import analysis.numeric.MAX_UINT
import analysis.opt.overflow.OverflowPatternRewriter.Companion.OverflowMetaData
import analysis.opt.overflow.RecipeType
import annotations.TestTags.EXPENSIVE
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import infra.CertoraBuild.Companion.EVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import solver.SolverResult
import utils.*
import utils.SignUtilities.maxSignedValueOfBitwidth
import utils.SignUtilities.maxUnsignedValueOfBitwidth
import utils.SignUtilities.minSignedValueOfBitwidth
import java.math.BigInteger

@Tag(EXPENSIVE)
class MulByConstOverflowTest {

    data class ConstMulTestConfig(
        val basicConfig: TestConfig,
        val c: BigInteger,
        val constIsFirst: Boolean,
    ) {
        private fun specAndContract(): Pair<String, String> = with(basicConfig) {
            val mul = ite(constIsFirst, "$c * x", "x * $c")

            val contract = when {
                isVyper ->
                    """
                    |@external
                    |def testFunc(x: $type) -> ${type}:
                    |   return $mul
                    """

                else ->
                    """
                    |contract test {
                    |   function testFunc($type x) external returns (${baseType}256) {
                    |       return $mul;
                    |   }
                    |}
                    """
            }.trimMargin()

            val resWidth =
                if (!signed && c > maxUnsignedValueOfBitwidth(width)) {
                    EVM_BITWIDTH256
                } else {
                    width
                }

            val resType = "$baseType$resWidth"

            val spec =
                if (!withRevert) {
                    """
                    |rule test($type x, env e) {
                    |   assert testFunc(e, x) == assert_$resType(x * $c);
                    |}
                    """.trimMargin()
                } else {
                    """
                    |rule test($type x, env e) {
                    |   require e.msg.value == 0;
                    |   mathint mathintRes = x * $c;
                    |   $baseType contractRes = testFunc@withrevert(e, x);
                    |   if (lastReverted) {
                    |      assert ${isMathintOutOfRangeCVLText("mathintRes", signed, resWidth)};
                    |   } else {
                    |      assert contractRes == assert_${resType}(mathintRes);
                    |   }
                    |}
                    """.trimMargin()
                }

            spec to contract
        }

        private val constMulMeta get() = with(basicConfig) {
            // this is for the strange cases where we multiply a small uint with a large constant.
            val actualWidth = if (!signed && c > BigInteger.TWO.pow(EVM_BITWIDTH256 - 8)) {
                EVM_BITWIDTH256
            } else {
                width
            }
            OverflowMetaData(RecipeType.ConstMul, actualWidth, signed)
        }

        fun test() =
            basicConfig.test(
                specAndContract(),
                { checkMul(it, constMulMeta) },
                toString()
            )
    }

    private fun TestConfig.constants() =
        if (signed) {
            sequenceOf(
                BigInteger.TWO,
                -BigInteger.TWO,
                -BigInteger.ONE,
                BigInteger.TEN,
                -BigInteger.TEN,
                minSignedValueOfBitwidth(width),
                maxSignedValueOfBitwidth(width),
                minSignedValueOfBitwidth(width) / 10,
                maxSignedValueOfBitwidth(width) / 10
            )
        } else {
            sequenceOf(
                BigInteger.TWO,
                BigInteger.TEN,
                maxUnsignedValueOfBitwidth(width),
                maxUnsignedValueOfBitwidth(width) / 10,
                (MAX_UINT / 10).takeIf { width != EVM_BITWIDTH256 && !compiler.isVyper },
                MAX_UINT.takeIf { width != EVM_BITWIDTH256 && !compiler.isVyper },
            ).filterNotNull()
        }


    private fun mulByConstTestSequence() =
        configSequence(8, 104, 240, 256).flatMap { baseConfig ->
            val constants = baseConfig.constants()
            sequence {
                for (constIsFirst in setOf(false, true)) {
                    for (c in constants) {
                        yield(ConstMulTestConfig(baseConfig, c, constIsFirst))
                    }
                }
            }
        }

    @Test
    fun mulByConstant() {
        mulByConstTestSequence().forEach(ConstMulTestConfig::test)
    }

    /**
     * `x * -1` at width 256 is the one case where the positive no-overflow bound `minSigned / c` lands
     * exactly on the 2s-complement encoding of `minSigned`. Unless it is capped at `maxSigned`, the
     * no-overflow condition becomes a tautology and the revert at `x == minSigned` is erased.
     */
    @Test
    fun minusOneMulByConstReverts() {
        val contract = """
            |contract test {
            |   function testFunc(int256 x) external returns (int256) {
            |       return x * (-1);
            |   }
            |}
            """.trimMargin()
        val spec = """
            |rule test(int256 x, env e) {
            |   require e.msg.value == 0;
            |   testFunc@withrevert(e, x);
            |   assert !lastReverted;
            |}
            """.trimMargin()
        val result = runContractAndSpec(
            contract = contract,
            spec = spec,
            compiler = EVMCompiler.Solidity("solc8.28"),
            withOptimize = false,
            withViaIR = false,
        )
        assertEquals(SolverResult.SAT, result.finalResult)
    }

    @Test
    fun oneMulByConst() {
        ConstMulTestConfig(
            TestConfig(
                compiler = EVMCompiler.Solidity("solc8.2"),
                signed = true,
                optimize = false,
                viaIR = false,
                withRevert = false,
                width = 8
            ),
            c = BigInteger("-128"),
            constIsFirst = false
        ).let(ConstMulTestConfig::test)
    }


}
