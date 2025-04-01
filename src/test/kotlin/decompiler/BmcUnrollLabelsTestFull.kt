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

package decompiler

import annotation.ScopedVersions
import annotation.SolidityVersion
import annotation.SolidityVersions
import annotations.TestTags.EXPENSIVE
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest

@Tag(EXPENSIVE)
@SolidityVersions([SolidityVersion.ANY_5,
    SolidityVersion.V6_10,
    SolidityVersion.V6_12,
    SolidityVersion.V7_0,
    SolidityVersion.V7_6,
    SolidityVersion.V8_2,
    SolidityVersion.V8_9,
    SolidityVersion.V8_11,
    SolidityVersion.V8_13,
    SolidityVersion.V8_16,
    SolidityVersion.V8_29
])
class BmcUnrollLabelsTestFull : BmcUnrollLabelsTest() {
    @ParameterizedTest
    @ScopedVersions
    fun simpleLoop(solc: String) {
        super.templateSimpleLoop(solc)
    }


    @ParameterizedTest
    @ScopedVersions
    fun simpleReturn(solc: String) {
        super.templateSimpleReturn(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun simpleReturn2(solc: String) {
        super.templateSimpleReturn2(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun simpleBreak(solc: String) {
        super.templateSimpleBreak(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun simpleRevert(solc: String) {
        super.templateSimpleRevert(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun nestedLoop(solc: String) {
        super.templateNestedLoop(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun nestedLoopWithRevert(solc: String) {
        super.templateNestedLoopWithRevert(solc)
    }

    @ParameterizedTest
    @ScopedVersions
    fun twoLoops(solc: String) {
        super.templateTwoLoops(solc)
    }
}
