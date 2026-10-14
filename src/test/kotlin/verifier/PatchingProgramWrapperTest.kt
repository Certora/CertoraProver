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

package verifier

import analysis.pta.InitAnnotation
import java.math.BigInteger
import tac.MetaMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import utils.*
import vc.data.*
import vc.data.TACProgramBuilder.BuiltTACProgram
import vc.data.TACProgramBuilder.Companion.testProgString

class PatchingProgramWrapperTest : TACBuilderAuxiliaries() {

    private val sink = listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "sink"))

    /**
     * Cone-of-influence optimizations are on by default; turning them off adds an annotation to the root block, so
     * [assertSame] must be told, to do the same to the expected program.
     */
    private fun BuiltTACProgram.wrapper(coi: Boolean = true) =
        PatchingProgramWrapper(code.letIf(!coi) { it.withCoiOptimizations(false) })

    /** the last-assert cut is opt-in */
    private fun PatchingProgramWrapper.cut() = toCode(cutAfterLastAsserts = true)

    private fun assertSame(
        expected: BuiltTACProgram,
        actual: CoreTACProgram,
        coi: Boolean = true,
        annotations: Boolean = false
    ) =
        assertEquals(
            testProgString(expected.code.letIf(!coi) { it.withCoiOptimizations(false) }, annotations = annotations),
            testProgString(actual, annotations = annotations)
        )

    /** an assert as the decompiler emits it, wrapped in its snippet annotations */
    private fun TACProgramBuilder.BlockBuilder.annotatedAssert(cond: TACSymbol.Var) {
        addCmd(SnippetCmd.LoopSnippet.AssertUnwindCond(cond, "unwind").toAnnotation())
        assert(cond, "unwind")
        addCmd(TACCmd.Simple.AnnotationCmd(TACMeta.SCOPE_SNIPPET_END))
    }

    /** the sink replaces the start annotation and deletes the end one: the annotation fixer must cope */
    @Test
    fun sinkOnAnnotatedAssert() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                annotatedAssert(y)
            }
        }
        val expected = TACProgramBuilder {
            a assign 1
            jump(1) {
                assume(TACSymbol.False, "sink")
            }
        }
        val w = prog.wrapper(coi = false)
        w.replaceBlockWithSink(prog.block(1), sink)
        assertSame(expected, w.toCode(), coi = false)
    }

    @Test
    fun jumpiTojumpAfterSinkIsRefused() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) { assert(y) }
            jump(2) { assert(y) }
        }
        val w = prog.wrapper()
        w.replaceBlockWithSink(prog.block(0), sink)
        assertThrows(IllegalArgumentException::class.java) { w.jumpiTojump(prog.block(0), prog.block(1)) }
    }

    /** what follows the last assert of a leaf goes, but the assert's own end annotation is restored */
    @Test
    fun leafIsTruncatedAfterItsLastAssert() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                annotatedAssert(y)
                b assign 2
                assume(x)
            }
        }
        val expected = TACProgramBuilder {
            a assign 1
            jump(1) {
                annotatedAssert(y)
            }
        }
        assertSame(expected, prog.wrapper().cut(), annotations = true)
        assertSame(prog, prog.wrapper(coi = false).toCode(), coi = false, annotations = true)
    }

    /** by default an assert-free arm (a revert, say) is left alone: `OverflowPatternRewriter` must still see it */
    @Test
    fun assertFreeLeafIsKeptByDefault() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assert(y)
            }
            jump(2) {
                a assign 2
                nop
            }
        }
        assertSame(prog, prog.wrapper().toCode())
    }

    /** when asked, a leaf with no assert is dropped, and the conditional jump into it becomes an assumption of the other arm */
    @Test
    fun assertFreeLeafIsDropped() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assert(y)
            }
            jump(2) {
                a assign 2
                nop
            }
        }
        val expected = TACProgramBuilder {
            assume(x, "jumpiTojump")
            jump(1) {
                a assign 1
                assert(y)
            }
        }
        assertSame(expected, prog.wrapper().cut())
        assertSame(prog, prog.wrapper(coi = false).toCode(), coi = false)
    }

    /** the root's conditional jump becomes a summary with the same two successors, which the wrapper cannot edit */
    private fun BuiltTACProgram.summaryAtRoot(): BuiltTACProgram {
        val root = block(0)
        val cmds = code.code[root]!!
        val jumpi = cmds.last() as TACCmd.Simple.JumpiCmd
        val summary = TACCmd.Simple.SummaryCmd(
            InitAnnotation.FourByteWriteSummary(jumpi.dst, jumpi.elseDst, BigInteger.ZERO, a),
            MetaMap()
        )
        return BuiltTACProgram(code.copy(code = code.code + (root to cmds.dropLast(1) + summary)), emptyMap())
    }

    /** a summary cannot be detached from its successors, so its assert-free one stays, but emptied */
    @Test
    fun assertFreeLeafOfASummaryIsEmptied() {
        val prog = TACProgramBuilder {
            a assign 0
            jumpCond(x)
            jump(1) {
                b assign 1
                assert(y)
            }
            jump(2) {
                b assign 2
                assume(x)
            }
        }.summaryAtRoot()
        val expected = TACProgramBuilder {
            a assign 0
            jumpCond(x)
            jump(1) {
                b assign 1
                assert(y)
            }
            jump(2) {
                nop
            }
        }.summaryAtRoot()
        assertSame(expected, prog.wrapper().cut())
        assertSame(prog, prog.wrapper(coi = false).toCode(), coi = false)
    }

    /** a block whose only successor died is a leaf itself, and dies too when it has no assert */
    @Test
    fun deadLeavesCascade() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(2) {
                a assign 1
                jump(1)
            }
            jump(3) {
                assert(y)
            }
            jumpDest(1) {
                b assign 2
                nop
            }
        }
        val expected = TACProgramBuilder {
            assumeNot(x)
            jump(3) {
                assert(y)
            }
        }
        assertSame(expected, prog.wrapper().cut())
    }

    /** under cone-of-influence optimizations a sink is an assert-free leaf, so it is erased altogether */
    @Test
    fun sinkIsErasedUnderCoi() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assert(y)
            }
            jump(2) {
                a assign 2
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            assume(x, "jumpiTojump")
            jump(1) {
                a assign 1
                assert(y)
            }
        }
        val w = prog.wrapper()
        w.replaceBlockWithSink(prog.block(2), sink)
        assertSame(expected, w.cut())

        val expectedNoCoi = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assert(y)
            }
            jump(2) {
                assume(TACSymbol.False, "sink")
            }
        }
        val w2 = prog.wrapper(coi = false)
        w2.replaceBlockWithSink(prog.block(2), sink)
        assertSame(expectedNoCoi, w2.toCode(), coi = false)
    }

    @Test
    fun programWithoutAssertsIsErased() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                b assign 2
                nop
            }
        }
        val expected = TACProgramBuilder {
            nop
        }
        assertSame(expected, prog.wrapper().cut())
    }

    /**
     * A constant-false assume in the middle of a block cuts its tail and its successors in both modes; a successor
     * still reached some other way stays.
     */
    @Test
    fun assumeFalseCutsTailAndSuccessors() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assume(TACSymbol.False)
                b assign 2
                jump(3)
            }
            jump(2) {
                assert(y)
                jump(3) {
                    assert(z)
                }
            }
        }
        val expectedNoCoi = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                a assign 1
                assume(TACSymbol.False)
            }
            jump(2) {
                assert(y)
                jump(3) {
                    assert(z)
                }
            }
        }
        assertSame(expectedNoCoi, prog.wrapper(coi = false).toCode(), coi = false)

        val expected = TACProgramBuilder {
            assumeNot(x)
            jump(2) {
                assert(y)
                jump(3) {
                    assert(z)
                }
            }
        }
        assertSame(expected, prog.wrapper().cut())
    }

    /** the sink stays without cone-of-influence optimizations; with them the block is trimmed to its last assert */
    @Test
    fun assumeFalseAfterAnAssert() {
        val prog = TACProgramBuilder {
            assert(y)
            assume(TACSymbol.False)
            b assign 2
            jump(1) {
                assert(z)
            }
        }
        val expectedNoCoi = TACProgramBuilder {
            assert(y)
            assume(TACSymbol.False)
        }
        assertSame(expectedNoCoi, prog.wrapper(coi = false).toCode(), coi = false)

        val expected = TACProgramBuilder {
            assert(y)
        }
        assertSame(expected, prog.wrapper().cut())
    }

    /** `limitTACProgramTo` with assume-false leaves keeps the branching: those leaves survive a plain `toCode` */
    @Test
    fun deadLeavesOfLimitedProgramAreKept() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
            }
            jump(2) {
                a assign 2
                nop
            }
        }
        val expected = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
            }
            jump(2) {
                assume(TACSymbol.False, "deadLeaf")
            }
        }
        val w = prog.wrapper()
        w.limitTACProgramTo(
            g = mapOf(prog.block(0) to setOf(prog.block(1)), prog.block(1) to setOf()),
            blocks = setOf(prog.block(0), prog.block(1)),
            assumeFalseOnDroppedLeaves = true
        )
        assertSame(expected, w.toCode())
    }

    private fun assign(v: TACSymbol.Var, n: Int) = TACCmd.Simple.AssigningCmd.AssignExpCmd(v, n.asTACExpr)

    /** a cut in the middle of a replacement list keeps the list up to the cut and drops what follows, at that pointer and after it */
    @Test
    fun cutInsideAReplacementList() {
        val prog = TACProgramBuilder {
            a assign 1
            b assign 2
            jump(1) {
                assert(y)
            }
        }
        val expected = TACProgramBuilder {
            a assign 1
            assume(TACSymbol.False)
        }
        val w = prog.wrapper()
        w.addAfter(prog.ptr(0, 0), listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, ""), assign(c, 3)))
        assertSame(expected, w.toCode())
    }

    /** the last-assert cut inside a replacement list: the assert stays, what follows it in the same list goes */
    @Test
    fun cutTruncatesTheListHoldingTheAssert() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                assert(y)
                b assign 2
            }
        }
        val expected = TACProgramBuilder {
            a assign 1
            jump(1) {
                assert(y)
            }
        }
        val w = prog.wrapper()
        w.replace(prog.ptr(1, 0), listOf(TACCmd.Simple.AssertCmd(y, ""), assign(c, 3)))
        w.replace(prog.ptr(1, 1), listOf(assign(d, 4), assign(e, 5)))
        assertSame(expected, w.cut())
    }

    /** keep equal to the total: a leaf whose last current command is the assert is left exactly as it is, annotations included */
    @Test
    fun cutKeepingEverythingTouchesNothing() {
        val prog = TACProgramBuilder {
            a assign 1
            jump(1) {
                b assign 2
                annotatedAssert(y)
            }
        }
        val expected = TACProgramBuilder {
            a assign 1
            jump(1) {
                a assign 3
                b assign 2
                annotatedAssert(y)
            }
        }
        val w = prog.wrapper()
        w.addBefore(prog.ptr(1, 0), listOf(assign(a, 3)))
        assertSame(expected, w.cut(), annotations = true)
    }

    /** a block that loses its only successor drops just the trailing jump, keeping what was added before it */
    @Test
    fun leafMadeByTheCutKeepsThePrefixOfItsJump() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
                jump(3) {
                    b assign 2
                    nop
                }
            }
            jump(2) {
                assert(w)
            }
        }
        val expected = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
                assert(y)
            }
            jump(2) {
                assert(w)
            }
        }
        val wr = prog.wrapper()
        wr.addBefore(prog.ptr(1, 1), listOf(TACCmd.Simple.AssertCmd(y, "")))
        assertSame(expected, wr.cut())
    }

    /** erasing everything empties a root that holds an annotated assert, without tripping the annotation fixer */
    @Test
    fun eraseAllOnARootWithAnAnnotatedAssert() {
        val prog = TACProgramBuilder {
            annotatedAssert(y)
            jump(1) {
                b assign 2
                nop
            }
        }
        val expected = TACProgramBuilder {
            nop
        }
        val w = prog.wrapper()
        w.eraseAll()
        assertSame(expected, w.toCode())
    }

    /** the conditional jump is rewritten where it currently stands, behind what was added before it */
    @Test
    fun jumpiTojumpOnAReplacedJump() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump(1) {
                assert(y)
            }
            jump(2) {
                assert(z)
            }
        }
        val expected = TACProgramBuilder {
            assume(x)
            assume(x, "jumpiTojump")
            jump(1) {
                assert(y)
            }
        }
        val w = prog.wrapper()
        w.addBefore(prog.ptr(0, 0), listOf(TACCmd.Simple.AssumeCmd(x, "")))
        w.jumpiTojump(prog.block(0), prog.block(1))
        assertSame(expected, w.toCode())
    }
}
