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

package report.dumps

import analysis.CmdPointer
import analysis.controlflow.PathCounter
import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFuncExitAnnotation
import analysis.ip.InternalFuncStartAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spec.cvlast.*
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TacMockGraphBuilder

class AddInternalFunctionsTest {

    @Test
    fun oneCallStraightLine01() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )
            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            // check mapping, one by one
            assertEquals(CmdPointer(n0_1, 0), mapping[CmdPointer(n0_1, 0)])
            val nbInt0 = mapping[CmdPointer(n0_1, 1)].block
            assertNotEquals(nbInt0, n0_1)
            assertEquals(CmdPointer(nbInt0, 0), mapping[CmdPointer(n0_1, 1)])
            assertEquals(CmdPointer(nbInt0, 1), mapping[CmdPointer(n0_1, 2)])
            assertEquals(CmdPointer(nbInt0, 2), mapping[CmdPointer(n0_1, 3)])
            val nbRet = mapping[CmdPointer(n0_1, 4)].block
            assertNotEquals(nbRet, n0_1)
            assertNotEquals(nbRet, nbInt0)
            assertEquals(CmdPointer(nbRet, 0), mapping[CmdPointer(n0_1, 4)])

            // resulting graph has
            // three nodes
            assertEquals(3, intAdded.blockgraph.size)
            // .. with two different `calleeIdx`s
            assertEquals(2, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }


    /**
     * Like before, straight line, but the internal call is wrapped by an external one.
     */
    @Test
    fun oneCallStraightLine02() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val n0_0 = blk(p0)
            val n0_1 = blk(p1)
            val n0_2 = blk(p0)

            edge(n0_0, n0_1)
            edge(n0_1, n0_2)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(3),
                    dummyCmd,
                    internalFuncExit(3),
                    dummyCmd,
                )
            )
            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            // check mapping, one by one
            assertEquals(CmdPointer(n0_0, 0), mapping[CmdPointer(n0_0, 0)])
            assertEquals(CmdPointer(n0_1, 0), mapping[CmdPointer(n0_1, 0)])
            val nbInt0 = mapping[CmdPointer(n0_1, 1)].block
            assertFalse(nbInt0 in prog.code.keys)
            assertEquals(CmdPointer(nbInt0, 0), mapping[CmdPointer(n0_1, 1)])
            assertEquals(CmdPointer(nbInt0, 1), mapping[CmdPointer(n0_1, 2)])
            assertEquals(CmdPointer(nbInt0, 2), mapping[CmdPointer(n0_1, 3)])
            val nbRet = mapping[CmdPointer(n0_1, 4)].block
            assertFalse(nbRet in prog.code.keys)
            assertNotEquals(nbRet, nbInt0)
            assertEquals(CmdPointer(nbRet, 0), mapping[CmdPointer(n0_1, 4)])
            assertEquals(CmdPointer(n0_2, 0), mapping[CmdPointer(n0_2, 0)])


            assertEquals(5, intAdded.blockgraph.size)
            assertEquals(3, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    /** Two internal calls, nested. */
    @Test
    fun twoCallsStraightLine01() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )
            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(5, intAdded.blockgraph.size)
            assertEquals(3, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    /** Two internal calls, in sequence. */
    @Test
    fun twoCallsStraightLine02() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                )
            )
            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(5, intAdded.blockgraph.size)
            assertEquals(3, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }


    /** Three internal calls, in sequence. */
    @Test
    fun threeCallsStraightLine01() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                    internalFuncStart(2),
                    dummyCmd,
                    internalFuncExit(2),
                    dummyCmd,
                )
            )
            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(7, intAdded.blockgraph.size)
            assertEquals(4, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    /**
     * One internal call, inside the internal call, there is a diamond.
     */
    @Test
    fun oneCallBranchingInside01() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            val n0_2 = blk(p0)
            val n0_i1 = blk(p0)
            val n0_i2 = blk(p0)

            edge(n0_1, n0_i1)
            edge(n0_1, n0_i2)
            edge(n0_i1, n0_2)
            edge(n0_i2, n0_2)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_2,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(6, intAdded.blockgraph.size)
            assertEquals(2, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)


            assertEquals(
                PathCounter(prog.analysisCache.graph, n0_1).singlePathCount,
                PathCounter(intAdded.analysisCache.graph, n0_1).singlePathCount
            )
        }
    }

    /**
     * One internal call, inside the internal call, there is a diamond.
     * The internal call is wrapped by one external call that adds no extra flow.
     */
    @Test
    fun oneCallBranchingInside02() {
        TacMockGraphBuilder {
            val p0 = proc("p0")
            val p1 = proc("p1")
            val n0_1 = blk(p0)
            val n0_2 = blk(p1)
            val n0_i1 = blk(p1)
            val n0_i2 = blk(p1)
            val n0_3 = blk(p1)
            val n0_4 = blk(p0)

            edge(n0_1, n0_2)
            edge(n0_2, n0_i1)
            edge(n0_2, n0_i2)
            edge(n0_i1, n0_3)
            edge(n0_i2, n0_3)
            edge(n0_3, n0_4)

            addCode(
                n0_2,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(8, intAdded.blockgraph.size)
            assertEquals(3, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)


            assertEquals(
                PathCounter(prog.analysisCache.graph, n0_1).singlePathCount,
                PathCounter(intAdded.analysisCache.graph, n0_1).singlePathCount
            )
        }
    }


    private fun CoreTACProgram.allCmdPointers() =
        code.entries.asSequence().flatMap { (blk, cmds) ->
            (cmds.indices).asSequence().map { pos ->
                CmdPointer(blk, pos)
            }
        }.toSet()

    private fun mappingInvariant(
        progBefore: CoreTACProgram,
        progAfter: CoreTACProgram,
        mapping: CmdPtrMapping
    ) {
        assertEquals(progBefore.allCmdPointers(), mapping.domain)
        assertEquals(progAfter.allCmdPointers(), mapping.range.toSet())
    }

    /** Like [oneCallBranchingInside02], but in the block where the call returns, there is another internal call. */
    @Test
    fun oneCallBranchingInsideOneCallRightAfter01() {
        TacMockGraphBuilder {
            val p0 = proc("p0")
            val p1 = proc("p1")
            val n0_1 = blk(p0)
            val n0_2 = blk(p1)
            val n0_i1 = blk(p1)
            val n0_i2 = blk(p1)
            val n0_3 = blk(p1)
            val n0_4 = blk(p0)

            edge(n0_1, n0_2)
            edge(n0_2, n0_i1)
            edge(n0_2, n0_i2)
            edge(n0_i1, n0_3)
            edge(n0_i2, n0_3)
            edge(n0_3, n0_4)

            addCode(
                n0_2,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(10, intAdded.blockgraph.size)
            assertEquals(4, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)


            assertEquals(
                PathCounter(prog.analysisCache.graph, n0_1).singlePathCount,
                PathCounter(intAdded.analysisCache.graph, n0_1).singlePathCount
            )
        }
    }

    /**
     * One internal call, out the internal call, there is a diamond.
     */
    @Test
    fun oneCallBranchingOutside() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val n0_1 = blk(p0)
            val n0_2 = blk(p0)
            val n0_b1 = blk(p0)
            val n0_b2 = blk(p0)

            edge(n0_1, n0_b1)
            edge(n0_1, n0_b2)
            edge(n0_b1, n0_2)
            edge(n0_b2, n0_2)

            // "left" branch gets an internal call and exit
            addCode(
                n0_b1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(6, intAdded.blockgraph.size) // the split node is replaced by three so we go from 4 to 6 overall
            assertEquals(1, prog.blockgraph.keys.mapToSet { it.calleeIdx }.size)
            assertEquals(2, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)

            assertEquals(
                PathCounter(prog.analysisCache.graph, n0_1).singlePathCount,
                PathCounter(intAdded.analysisCache.graph, n0_1).singlePathCount
            )
        }
    }

    /**
     * "with summary" for our purposes here means that internal and external calls can nest arbitrarily.
     *
     * This example has one internal call on the main level, and then one external one inside it.
     */
    @Test
    fun withSummary01() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val n0_1 = blk(p0)
            val n0_2 = blk(p1)
            val n0_3 = blk(p0)

            edge(n0_1, n0_2)
            edge(n0_2, n0_3)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                    TACCmd.Simple.JumpCmd(n0_2), // added just to see if we handle these ..
                )
            )

            addCode(
                n0_2,
                listOf(
                    dummyCmd,
                    TACCmd.Simple.JumpCmd(n0_3), // added just to see if we handle these ..
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(5, intAdded.blockgraph.size)
            assertEquals(3, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    /** internal -> external -> internal */
    @Test
    fun withSummary02() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val n0_1 = blk(p0)
            val n0_2 = blk(p1)
            val n0_3 = blk(p0)

            edge(n0_1, n0_2)
            edge(n0_2, n0_3)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_2,
                listOf(
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(7, intAdded.blockgraph.size)
            assertEquals(4, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    /** Case: within an internal call, two different external calls return to the same node */
    @Test
    fun withSummary03() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val p2 = proc("2")
            val p3 = proc("3")
            val n0_1 = blk(p0)
            val n0_2en = blk(p1)
            val n0_2_p2 = blk(p2)
            val n0_2_p3 = blk(p3)
            val n0_2ex = blk(p1)
            val n0_3 = blk(p0)

            edge(n0_1, n0_2en)
            edge(n0_2en, n0_2_p2)
            edge(n0_2en, n0_2_p3)
            edge(n0_2_p2, n0_2ex)
            edge(n0_2_p3, n0_2ex)
            edge(n0_2ex, n0_3)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(8, intAdded.blockgraph.size)
            assertEquals(5, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }

    @Test
    fun withSummary04() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val p2 = proc("2")
            val p3 = proc("3")
            val p4 = proc("4")
            val n0_1 = blk(p0)
            val n0_2en = blk(p1)
            val n0_2_1 = blk(p2)
            val n0_2_2 = blk(p3)
            val n0_2_2a = blk(p3)
            val n0_2_3 = blk(p4)
            val n0_2ex = blk(p1)
            val n0_3 = blk(p0)

            edge(n0_1, n0_2en)
            edge(n0_2en, n0_2_1)
            edge(n0_2en, n0_2_2)
            edge(n0_2_2, n0_2_3)
            edge(n0_2_2, n0_2_2a)
            edge(n0_2_3, n0_2ex)
            edge(n0_2_1, n0_2ex)
            edge(n0_2_2a, n0_2ex)
            edge(n0_2ex, n0_3)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )


            addCode(
                n0_2en,
                listOf(
                    dummyCmd,
                    internalFuncStart(1),
                    dummyCmd,
                )
            )

            addCode(
                n0_2ex,
                listOf(
                    dummyCmd,
                    internalFuncExit(1),
                    dummyCmd,
                )
            )

            addCode(
                n0_2_3,
                listOf(
                    dummyCmd,
                    internalFuncStart(2),
                    dummyCmd,
                    internalFuncExit(2),
                    dummyCmd,
                    TACCmd.Simple.JumpCmd(n0_2ex), // just to see if we handle those right
                )
            )

            val prog = prog("internalFuncTestProg")

            assertDoesNotThrow {
                val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)
                mappingInvariant(prog, intAdded, mapping)

                assertEquals(14, intAdded.blockgraph.size)
                assertEquals(8, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
            }

        }
    }

    /** like [withSummary03], but in one branch we don't switch to a new procedure, so the control flow join at the
     * contains one returning and one non-context-switching edge */
    @Test
    fun withSummary03a() {
        TacMockGraphBuilder {
            val p0 = proc("0")
            val p1 = proc("1")
            val p2 = proc("2")
            val n0_1 = blk(p0)
            val n0_2en = blk(p1)
            val n0_2_p2 = blk(p2)
            val n0_2_p1 = blk(p1)
            val n0_2ex = blk(p1)
            val n0_3 = blk(p0)

            edge(n0_1, n0_2en)
            edge(n0_2en, n0_2_p2)
            edge(n0_2en, n0_2_p1)
            edge(n0_2_p2, n0_2ex)
            edge(n0_2_p1, n0_2ex)
            edge(n0_2ex, n0_3)

            addCode(
                n0_1,
                listOf(
                    dummyCmd,
                    internalFuncStart(0),
                    dummyCmd,
                )
            )

            addCode(
                n0_3,
                listOf(
                    dummyCmd,
                    internalFuncExit(0),
                    dummyCmd,
                )
            )

            val prog = prog("internalFuncTestProg")
            val (intAdded, mapping) = AddInternalFunctions.addInternalFunctionIdxs(prog)

            mappingInvariant(prog, intAdded, mapping)

            assertEquals(8, intAdded.blockgraph.size)
            assertEquals(4, intAdded.blockgraph.keys.mapToSet { it.calleeIdx }.size)
        }
    }


    ////////// helpers ///////////

    private fun internalFuncStart(id: Int) =
        TACCmd.Simple.AnnotationCmd(
            INTERNAL_FUNC_START,
            InternalFuncStartAnnotation(
                id = id,
                startPc = 0,
                args = listOf(),
                methodSignature = qualifiedMethodSignature(id),
                stackOffsetToArgPos = mapOf(),
                callSiteSrc = null
            )
        )

    private fun qualifiedMethodSignature(id: Int) = QualifiedMethodSignature(
        QualifiedFunction(SolidityContract("dummyContract"), "qmethod_$id"),
        emptyList(),
        emptyList()
    )

    private fun internalFuncExit(id: Int) = TACCmd.Simple.AnnotationCmd(
        INTERNAL_FUNC_EXIT,
        InternalFuncExitAnnotation(
            id = id,
            rets = listOf(),
            methodSignature = qualifiedMethodSignature(id),
        )
    )

}
