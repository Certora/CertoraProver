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

package instrumentation.transformers

import instrumentation.transformers.AssignmentInliner.Companion.inlineAssignments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.BuiltTACProgram
import vc.data.TACProgramBuilder.Companion.testProgString

class AssignmentInlinerTest : TACBuilderAuxiliaries() {

    private fun runAndCompare(
        original: BuiltTACProgram,
        expected: BuiltTACProgram
    ) {
        val newProg = inlineAssignments(original.code, isInlineable = { true })
        assertEquals(testProgString(expected.code), testProgString(newProg))
    }

    @Test
    fun test1() {
        val originalProg = TACProgramBuilder {
            a assign b
            a assign Add(aS, cS)
        }
        val expectedProg = TACProgramBuilder {
            a assign b
            a assign Add(bS, cS)
        }
        runAndCompare(originalProg, expectedProg)
    }

    @Test
    fun dontInterfere() {
        val originalProg = TACProgramBuilder {
            b assign a
            c assign b
            b assign Zero // this does not disturb the folding
            d assign Add(cS, One)
        }
        val expectedProg = TACProgramBuilder {
            b assign a
            c assign a
            b assign Zero
            d assign Add(aS, One)
        }
        runAndCompare(originalProg, expectedProg)
    }


    @Test
    fun selfAssignment() {
        val originalProg = TACProgramBuilder {
            nop
            a assign a
        }
        val expectedProg = TACProgramBuilder {
            nop
        }
        runAndCompare(originalProg, expectedProg)
    }

    @Test
    fun test3() {
        val originalProg = TACProgramBuilder {
            b assign a
            c assign b
            a assign d
            e assign Add(cS, One)
        }
        val expectedProg = TACProgramBuilder {
            b assign a
            c assign a
            a assign d
            e assign Add(bS, One)
        }
        runAndCompare(originalProg, expectedProg)
    }

    @Test
    fun test4() {
        val originalProg = TACProgramBuilder {
            b assign a
            b assign Add(aS, One)
            e assign Add(bS, One)
        }
        runAndCompare(originalProg, originalProg)
    }


}
