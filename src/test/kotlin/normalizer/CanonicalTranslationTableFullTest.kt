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

package normalizer

import annotation.SolidityVersion
import annotation.SolidityVersions
import annotation.WithOptimizedFlag
import annotations.TestTags.EXPENSIVE

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest


@Tag(EXPENSIVE)
class CanonicalTranslationTableFullTest : CanonicalTranslationTableTest() {

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_1, SolidityVersion.V6_10])
    @WithOptimizedFlag
    override fun testArrayAnalysisWideDigests(solc: String, optimize: Boolean) =
        super.testArrayAnalysisWideDigests(solc, optimize)

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_1, SolidityVersion.V6_10])
    @WithOptimizedFlag
    override fun testArrayAnalysisDigests(solc: String, optimize: Boolean) =
        super.testArrayAnalysisDigests(solc, optimize)

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V4_25, SolidityVersion.V7_0, SolidityVersion.V6_8, SolidityVersion.V6_10])
    @WithOptimizedFlag
    override fun testConstantSizeDigests(solc: String, optimize: Boolean) =
        super.testConstantSizeDigests(solc, optimize)

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.ANY_4])
    @WithOptimizedFlag
    override fun testInlineAssemblyDigests(solc: String, optimize: Boolean) =
        super.testInlineAssemblyDigests(solc, optimize)

    @ParameterizedTest
    @SolidityVersions(
        [SolidityVersion.V6_10, SolidityVersion.V7_0,
            SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V7_6, SolidityVersion.V8_11,
            SolidityVersion.V8_9, SolidityVersion.V8_13, SolidityVersion.V8_29]
    )
    @WithOptimizedFlag
    override fun testABIV2EncodingDigests(solc: String, optimize: Boolean) =
        super.testABIV2EncodingDigests(solc, optimize)

    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V6_10])
    override fun digestCallWithData(solc: String) = super.digestCallWithData(solc)

    @ParameterizedTest
    @SolidityVersions(
        [SolidityVersion.V6_10, SolidityVersion.V7_0,
            SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V7_6, SolidityVersion.V8_11,
            SolidityVersion.V8_9, SolidityVersion.V8_13, SolidityVersion.V8_29]
    )
    @WithOptimizedFlag
    override fun testDigestChangeOnReplaceDivWithSub(solc: String, optimize: Boolean) =
        super.testDigestChangeOnReplaceDivWithSub(solc, optimize)

    @ParameterizedTest
    @SolidityVersions(
        [SolidityVersion.V6_10, SolidityVersion.V7_0,
            SolidityVersion.V6_8, SolidityVersion.V8_2, SolidityVersion.V7_6, SolidityVersion.V8_11,
            SolidityVersion.V8_9, SolidityVersion.V8_13, SolidityVersion.V8_29]
    )
    @WithOptimizedFlag
    override fun testDigestChangeOnBinOpReplacement(solc: String, optimize: Boolean) =
        super.testDigestChangeOnBinOpReplacement(solc, optimize)
}
