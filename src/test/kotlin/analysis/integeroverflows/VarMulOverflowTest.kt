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
import analysis.integeroverflows.OverflowTestAux.simpleBinarySpecAndContract
import annotations.TestTags
import config.Config
import config.ConfigScope
import infra.CertoraBuild.Companion.EVMCompiler
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag(TestTags.EXPENSIVE)
class VarMulOverflowTest {

    private fun TestConfig.specAndContractTernary(): Pair<String, String> {
        val contract = when {
            isVyper ->
                """
                    |@external
                    |def testFunc(x: $type, y: $type, z: $type) -> ${type}:
                    |   return (x * y) * z
                    """

            else ->
                """
                    |contract test {
                    |   function testFunc($type x, $type y, $type z) external returns ($type) {
                    |       return (x * y) * z;
                    |   }
                    |}
                    """
        }.trimMargin()

        val spec =
            if (!withRevert) {
                """
                    |rule test(env e, $type x, $type y, $type z) {
                    |   assert testFunc(e, x, y, z) == assert_$type(assert_$type(x * y) * z);
                    |}
                    """
            } else {
                """
                    |rule test(env e, $type x, $type y, $type z) {
                    |   require e.msg.value == 0;
                    |   mathint mathintRes1 = x * y;
                    |   mathint mathintRes2 = mathintRes1 * z;
                    |   $type contractRes = testFunc@withrevert(e, x, y, z);
                    |   if (lastReverted) {
                    |      assert ${isMathintOutOfRangeCVLText("mathintRes1", signed, width)} ||
                    |             ${isMathintOutOfRangeCVLText("mathintRes2", signed, width)};
                    |   } else {
                    |      assert contractRes == assert_$type(assert_$type(x * y) * z);
                    |   }
                    |}
                    """
            }.trimMargin()

        return spec to contract
    }


    private fun TestConfig.specAndContractMixed(): Pair<String, String> {
        val contract = when {
            isVyper ->
                """
                    |@external
                    |def testFunc(x: $type, y: $type, z: $type, w: $type) -> ${type}:
                    |   return (x * y - z) * w
                    """

            else ->
                """
                    |contract test {
                    |   function testFunc($type x, $type y, $type z, $type w) external returns ($type) {
                    |       return (x * y - z) * w;
                    |   }
                    |}
                    """
        }.trimMargin()

        val spec =
            if (!withRevert) {
                """
                    |rule test(env e, $type x, $type y, $type z, $type w) {
                    |   assert testFunc(e, x, y, z, w) == assert_$type(assert_$type(assert_$type(x * y) - z) * w);
                    |}
                    """
            } else {
                """
                    |rule test(env e, $type x, $type y, $type z, $type w) {
                    |   require e.msg.value == 0;
                    |   mathint mathintRes1 = x * y;
                    |   mathint mathintRes2 = mathintRes1 - z;
                    |   mathint mathintRes3 = mathintRes2 * w;
                    |   $type contractRes = testFunc@withrevert(e, x, y, z, w);
                    |   if (lastReverted) {
                    |      assert ${isMathintOutOfRangeCVLText("mathintRes1", signed, width)} ||
                    |             ${isMathintOutOfRangeCVLText("mathintRes2", signed, width)} ||
                    |             ${isMathintOutOfRangeCVLText("mathintRes3", signed, width)};
                    |   } else {
                    |      assert contractRes == assert_$type(assert_$type(assert_$type(x * y) - z) * w);
                    |   }
                    |}
                    """
            }.trimMargin()

        return spec to contract
    }

    @Test
    fun binaryMulTest() =
        configSequence(8, 104, 240, 256).forEach {
            with(it) {
                test(simpleBinarySpecAndContract("*"), { checkMul(it, binMulMeta) })
            }
        }

    @Test
    fun tripleMulTest() =
        configSequence(8, 104, 240, 256).forEach {
            with(it) {
                test(specAndContractTernary(), { checkMul(it, binMulMeta, binMulMeta) })
            }
        }

    @Test
    fun mixedTest() = ConfigScope(Config.EnableHeuristicalFolding, false).use {
        // we disable folding so that the rewrite meta we add won't disappear (specifically for the subtraction)
        configSequence(8, 104, 240, 256).forEach {
            with(it) {
                test(specAndContractMixed(), { checkMul(it, binMulMeta, binMulMeta, subtractionMeta) })
            }
        }
    }


    @Test
    fun justOneTest() {
        TestConfig(
            compiler = EVMCompiler.Vyper("vyper0.3.10"),
            signed = false,
            optimize = false,
            viaIR = false,
            withRevert = false,
            width = 8
        ).let {
            with(it) {
                test(simpleBinarySpecAndContract("*"), { checkMul(it, binMulMeta) })
            }
        }
    }
}
