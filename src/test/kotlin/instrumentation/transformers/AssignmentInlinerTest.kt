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
import vc.data.asTACSymbol

class AssignmentInlinerTest : TACBuilderAuxiliaries() {

    private fun runAndCompare(
        original: BuiltTACProgram,
        expected: BuiltTACProgram
    ) {
        val newProg = inlineAssignments(original.code, filteringFunctions = FilteringFunctions.NoFilter, strict = false)
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

//    This test checks subtler cases where inlining is possible. Supporting this doesn't seem to be worth the trouble
//    at the moment.
//    @Test
//    fun test3() {
//        val originalProg = TACProgramBuilder {
//            b assign a
//            c assign b
//            a assign d
//            e assign Add(cS, One)
//        }
//        val expectedProg = TACProgramBuilder {
//            b assign a
//            c assign a
//            a assign d
//            e assign Add(bS, One)
//        }
//        runAndCompare(originalProg, expectedProg)
//    }

    @Test
    fun test4() {
        val originalProg = TACProgramBuilder {
            b assign a
            b assign Add(aS, One)
            e assign Add(bS, One)
        }
        runAndCompare(originalProg, originalProg)
    }

    @Test
    fun test5() {
        val originalProg = TACProgramBuilder {
            b assign a
            a assign bMap1[0xa0]
            c assign Add(bS, aS)
        }
        runAndCompare(originalProg, originalProg)
    }

    @Test
    fun test6() {
        val originalProg = TACProgramBuilder {
            b assign a
            c assign b
            d assign c
        }
        val expectedProg = TACProgramBuilder {
            b assign a
            c assign a
            d assign a
        }
        runAndCompare(originalProg, expectedProg)
    }

    @Test
    fun test7() {
        val originalProg = TACProgramBuilder {
            bMap2 assign bMap1
            bMap2.byteStore(loc = a, value = b)
        }
        runAndCompare(originalProg, originalProg)
    }

    @Test
    fun testBytemaps() {
        val originalProg = TACProgramBuilder {
            a assign 7
            bMap1.byteStore(loc = a, value = b)
        }
        val expectedProg = TACProgramBuilder {
            a assign 7
            bMap1.byteStore(loc = 7.asTACSymbol(), value = b)
        }
        runAndCompare(originalProg, expectedProg)
    }

    /**
     * `f := a` saves the old value of `a` before `a` is redefined in a later block, after a self-assignment there.
     * Erasing the self-assignment must not stop the processing of its block: uses of `f` after the redefinition,
     * in its block or in the blocks it leads to, must stay `f`.
     */
    @Test
    fun selfAssignmentDoesNotEndTheBlock() {
        fun prog(withSelfAssignment: Boolean) = TACProgramBuilder {
            a assign Add(bS, cS)
            f assign a
            jumpCond(x)
            jump(1) {
                nop
                jump(2)
            }
            jump(2) {
                if (withSelfAssignment) {
                    g assign g
                }
                a assign Add(aS, cS)
                y assign Lt(fS, aS)
                jumpCond(y)
                jump(3) {
                    d assign Sub(fS, aS)
                }
                jump(4) {
                    e assign Sub(aS, fS)
                }
            }
        }
        runAndCompare(prog(withSelfAssignment = true), prog(withSelfAssignment = false))
    }

}
