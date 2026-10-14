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

import analysis.CmdPointer
import analysis.getNaturalLoops
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.NBId
import tac.StartBlock
import tac.Tag
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Tests for the offshoot region collected by [BMCRunner.expandPathsToExtraBlocks]: the blocks which reach a sink left
 * behind by the unrolling of an inner loop, and which belong to an iteration of the enclosing loop even though the
 * natural loop computation leaves them out.
 *
 * All fixtures share the enclosing loop `head -> {b1, b2} -> tail -> head`; they differ in what hangs off `b1` and
 * `b2`.
 */
class BmcOffshootRegionTest {

    private val cond = TACSymbol.Var("condition", Tag.Bool)

    private fun sinkCmds() = listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "synthetic loop end"))

    /**
     * The offshoot the unroller is meant to accept: both branches of a short circuiting loop condition enter the same
     * block `a`, which runs to the sink without going back into the loop body.
     *
     * ```
     *   head -> b1 -> a -> sink
     *        \                       (b1 and b2 also fall through to tail, tail jumps back to head)
     *         -> b2 -> a
     * ```
     */
    @Test
    fun acyclicOffshootWithTwoEntryEdgesIsPartOfTheIteration() {
        var prog = CoreTACProgram.empty("acyclicOffshoot")
        var patching = prog.toPatchingProgram()
        patching.addVarDecls(setOf(cond))

        val sink = patching.addBlock(StartBlock.copy(origStartPc = 20), sinkCmds())
        val a = patching.addBlock(
            StartBlock.copy(origStartPc = 21),
            listOf(TACCmd.Simple.JumpCmd(sink))
        )
        val tail = patching.addBlock(
            StartBlock.copy(origStartPc = 10),
            // the back edge to the head is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val b1 = patching.addBlock(
            StartBlock.copy(origStartPc = 11),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = tail))
        )
        val b2 = patching.addBlock(
            StartBlock.copy(origStartPc = 12),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = tail))
        )
        val head = patching.addBlock(
            StartBlock.copy(origStartPc = 13),
            listOf(TACCmd.Simple.JumpiCmd(dst = b1, cond = cond, elseDst = b2))
        )
        patching.addBlock(StartBlock.copy(), listOf(TACCmd.Simple.JumpCmd(head)))

        prog = patching.toCode(prog)
        // a jump can only be added to a block the patching already knows about, hence the refresh
        patching = prog.toPatchingProgram()
        patching.insertAfter(CmdPointer(tail, 0), listOf(TACCmd.Simple.JumpCmd(head)))
        prog = patching.toCode(prog)

        val loop = getNaturalLoops(prog.analysisCache.graph).single()
        Assertions.assertEquals(setOf(head, b1, b2, tail), loop.body)

        Assertions.assertEquals(
            setOf(a, sink),
            BMCRunner(1, prog).expandPathsToExtraBlocks(loop, setOf(sink))
        )
    }

    /**
     * Two entry edges into a cycle: `b1 -> a`, `b2 -> b`, `a <-> b`, `b -> sink`. Neither `a` nor `b` dominates the
     * other, so the cycle has no back edge and the loop analysis never reports it. The unroller copies each body block
     * with its original successors, so folding these blocks into the loop body would reproduce the cycle in every
     * iteration; the region must be rejected.
     */
    @Test
    fun cyclicOffshootIsRejected() {
        var prog = CoreTACProgram.empty("cyclicOffshoot")
        var patching = prog.toPatchingProgram()
        patching.addVarDecls(setOf(cond))

        val sink = patching.addBlock(StartBlock.copy(origStartPc = 20), sinkCmds())
        val a = patching.addBlock(
            StartBlock.copy(origStartPc = 21),
            // the jump to b is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val b = patching.addBlock(
            StartBlock.copy(origStartPc = 22),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = sink))
        )
        val tail = patching.addBlock(
            StartBlock.copy(origStartPc = 10),
            // the back edge to the head is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val b1 = patching.addBlock(
            StartBlock.copy(origStartPc = 11),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = tail))
        )
        val b2 = patching.addBlock(
            StartBlock.copy(origStartPc = 12),
            listOf(TACCmd.Simple.JumpiCmd(dst = b, cond = cond, elseDst = tail))
        )
        val head = patching.addBlock(
            StartBlock.copy(origStartPc = 13),
            listOf(TACCmd.Simple.JumpiCmd(dst = b1, cond = cond, elseDst = b2))
        )
        patching.addBlock(StartBlock.copy(), listOf(TACCmd.Simple.JumpCmd(head)))

        prog = patching.toCode(prog)
        // a jump can only be added to a block the patching already knows about, hence the refresh
        patching = prog.toPatchingProgram()
        patching.insertAfter(CmdPointer(a, 0), listOf(TACCmd.Simple.JumpCmd(b)))
        patching.insertAfter(CmdPointer(tail, 0), listOf(TACCmd.Simple.JumpCmd(head)))
        prog = patching.toCode(prog)

        val loop = getNaturalLoops(prog.analysisCache.graph).single()
        Assertions.assertEquals(setOf(head, b1, b2, tail), loop.body)

        Assertions.assertEquals(
            emptySet<NBId>(),
            BMCRunner(1, prog).expandPathsToExtraBlocks(loop, setOf(sink))
        )
    }

    /**
     * A natural loop has a single entry, so a block outside the body that jumps into the body below the head is itself
     * in the body: it reaches the tail without passing through the head, which is exactly the membership condition.
     * Here `a` hangs off `b1` and jumps to `b2`, and the loop analysis puts it in the body, where the offshoot
     * collection can no longer pick it up.
     *
     * ```
     *   head -> b1 | b2 ; b1 -> a | tail ; b2 -> tail ; a -> b2 | sink ; tail -> head
     * ```
     */
    @Test
    fun aBlockReenteringTheBodyBelowTheHeadIsPartOfTheBody() {
        var prog = CoreTACProgram.empty("reentryBelowHead")
        var patching = prog.toPatchingProgram()
        patching.addVarDecls(setOf(cond))

        val sink = patching.addBlock(StartBlock.copy(origStartPc = 20), sinkCmds())
        val tail = patching.addBlock(
            StartBlock.copy(origStartPc = 10),
            // the back edge to the head is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val b2 = patching.addBlock(StartBlock.copy(origStartPc = 12), listOf(TACCmd.Simple.JumpCmd(tail)))
        val a = patching.addBlock(
            StartBlock.copy(origStartPc = 21),
            listOf(TACCmd.Simple.JumpiCmd(dst = b2, cond = cond, elseDst = sink))
        )
        val b1 = patching.addBlock(
            StartBlock.copy(origStartPc = 11),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = tail))
        )
        val head = patching.addBlock(
            StartBlock.copy(origStartPc = 13),
            listOf(TACCmd.Simple.JumpiCmd(dst = b1, cond = cond, elseDst = b2))
        )
        patching.addBlock(StartBlock.copy(), listOf(TACCmd.Simple.JumpCmd(head)))

        prog = patching.toCode(prog)
        // a jump can only be added to a block the patching already knows about, hence the refresh
        patching = prog.toPatchingProgram()
        patching.insertAfter(CmdPointer(tail, 0), listOf(TACCmd.Simple.JumpCmd(head)))
        prog = patching.toCode(prog)

        val loop = getNaturalLoops(prog.analysisCache.graph).single()
        Assertions.assertEquals(setOf(head, b1, b2, tail, a), loop.body)

        Assertions.assertEquals(
            setOf(sink),
            BMCRunner(1, prog).expandPathsToExtraBlocks(loop, setOf(sink))
        )
    }

    /**
     * The head is therefore the only block of the body an offshoot can jump to, and since the offshoot is reachable
     * only through the body, the head precedes all of it and that jump is a back edge. Here `a2` jumps back to the
     * head, and the loop analysis reports the offshoot as a second loop of the same head, which `consolidateLoops`
     * merges into the body before anything is unrolled. This is why the acyclicity test on the collected blocks does
     * not have to follow paths that go through the body.
     *
     * ```
     *   head -> b1 | b2 ; b1 -> a | tail ; b2 -> tail ; a -> a2 | sink ; a2 -> head ; tail -> head
     * ```
     */
    @Test
    fun offshootReenteringAtTheHeadIsASecondLoopOfThatHead() {
        var prog = CoreTACProgram.empty("reentryAtHead")
        var patching = prog.toPatchingProgram()
        patching.addVarDecls(setOf(cond))

        val sink = patching.addBlock(StartBlock.copy(origStartPc = 20), sinkCmds())
        val a2 = patching.addBlock(
            StartBlock.copy(origStartPc = 22),
            // the jump to the head is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val a = patching.addBlock(
            StartBlock.copy(origStartPc = 21),
            listOf(TACCmd.Simple.JumpiCmd(dst = a2, cond = cond, elseDst = sink))
        )
        val tail = patching.addBlock(
            StartBlock.copy(origStartPc = 10),
            // the back edge to the head is added below
            listOf(TACCmd.Simple.NopCmd)
        )
        val b1 = patching.addBlock(
            StartBlock.copy(origStartPc = 11),
            listOf(TACCmd.Simple.JumpiCmd(dst = a, cond = cond, elseDst = tail))
        )
        val b2 = patching.addBlock(StartBlock.copy(origStartPc = 12), listOf(TACCmd.Simple.JumpCmd(tail)))
        val head = patching.addBlock(
            StartBlock.copy(origStartPc = 13),
            listOf(TACCmd.Simple.JumpiCmd(dst = b1, cond = cond, elseDst = b2))
        )
        patching.addBlock(StartBlock.copy(), listOf(TACCmd.Simple.JumpCmd(head)))

        prog = patching.toCode(prog)
        // a jump can only be added to a block the patching already knows about, hence the refresh
        patching = prog.toPatchingProgram()
        patching.insertAfter(CmdPointer(tail, 0), listOf(TACCmd.Simple.JumpCmd(head)))
        patching.insertAfter(CmdPointer(a2, 0), listOf(TACCmd.Simple.JumpCmd(head)))
        prog = patching.toCode(prog)

        val loops = getNaturalLoops(prog.analysisCache.graph)
        Assertions.assertEquals(setOf(head), loops.mapTo(mutableSetOf()) { it.head })
        Assertions.assertEquals(setOf(tail, a2), loops.mapTo(mutableSetOf()) { it.tail })

        // taken on its own the collection is a private, acyclic offshoot, and the loop head takes care of the rest
        Assertions.assertEquals(
            setOf(a, a2, sink),
            BMCRunner(1, prog).expandPathsToExtraBlocks(loops.single { it.tail == tail }, setOf(sink))
        )

        Assertions.assertTrue(getNaturalLoops(BMCRunner(2, prog).bmcUnroll().analysisCache.graph).isEmpty())
    }
}
