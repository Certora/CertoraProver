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

package vc.data

import algorithms.SimpleDominanceAnalysis
import algorithms.dominates
import algorithms.topologicalOrder
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.maybeAnnotation
import com.certora.collect.*
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import tac.*
import java.util.stream.Collectors

class PatchingTACProgramTests {

    @Test
    fun testAddingOpenBlock() {
        val blocks: List<NBId> = List(6) { createBlock(it) }

        val blockGraph: BlockGraph = BlockGraph(
            blocks[0] to treapSetOf(blocks[1]),
            blocks[1] to blocks.subList(2, 4).toTreapSet(),
            blocks[2] to blocks.subList(3, 5).toTreapSet(),
            blocks[3] to treapSetOf(blocks[5]),
            blocks[4] to treapSetOf(blocks[5]),
            blocks[5] to treapSetOf(),

            )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())

        val code: BlockNodes<TACCmd.Simple> = blocks.associateWith {
            if (blockGraph[it]!!.size > 1) {
                val dest = blockGraph[it]!!.elementAt(0)
                val elseDest = blockGraph[it]!!.elementAt(1)
                listOf(TACCmd.Simple.JumpiCmd(dest, elseDest, symbol))
            } else {
                listOf(TACCmd.Simple.NopCmd)
            }
        }


        val name = "well-formed graph"

        val program = CoreTACProgram(
            code,
            blockGraph,
            name,
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )


        val p = program.toPatchingProgram()

        val block1 = blocks[1]
        val block3 = blocks[3]
        val block4 = blocks[4]

        assertEquals(treapSetOf(blocks[2], block3), p.getSuccessors(block1))


        val openBlock = p.createOpenBlockFrom(block1)

        p.replaceCommand(CmdPointer(block1, 0), listOf(TACCmd.Simple.JumpiCmd(openBlock, block3, symbol)))
        p.populateBlock(openBlock, listOf(TACCmd.Simple.JumpCmd(block4)))
        val updatedProgram = p.toCode()


        assertEquals(treapSetOf(block4), updatedProgram.second[openBlock])
        assertEquals(treapSetOf(openBlock, block3), updatedProgram.second[block1])
    }

    @Test
    fun testInvalidOpenBlock() {
        val blocks: List<NBId> = List(2) { createBlock(it) }

        val block0 = blocks[0]
        val block1 = blocks[1]


        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1),
            block1 to treapSetOf()
        )


        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpCmd(block1)),
            block1 to listOf(TACCmd.Simple.NopCmd)
        )


        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), treapSetOf(), mapOf())
        val program = CoreTACProgram(
            code,
            blockGraph,
            "malformed block",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        val p = program.toPatchingProgram()

        val openBlock = p.createOpenBlockFrom(block0)
        p.replaceCommand(CmdPointer(block0, 0), listOf(TACCmd.Simple.JumpCmd(openBlock)))

        val thrown = assertThrows<IllegalArgumentException> { p.populateBlock(openBlock, listOf(TACCmd.Simple.NopCmd)) }
        assertEquals(thrown.message, "No successors (inferred or explicit) for open block $openBlock")

        assertThrows<IllegalStateException> { p.toCode() }.also { assertEquals("cannot finalize program patch if there are open blocks: [$openBlock]", it.message) }
    }

    private val META_KEY = MetaKey<String>("some.meta")
    private val TARGET_COMMAND = "target_command"
    private val MOVE_COMMAND_A = "moved_a"
    private val MOVE_COMMAND_B = "moved_b"
    private val BEFORE_MOVE_COMMAND_A = "not_moved_a"
    private val BEFORE_MOVE_COMMAND_B = "not_moved_b"

    private fun CoreTACProgram.getCommand(metaKeyVal: String): LTACCmd {
        return this.parallelLtacStream().filter { it.maybeAnnotation(META_KEY) == metaKeyVal }.collect(Collectors.toSet()).singleOrNull()
            ?: error("Was not able to find command by string ${metaKeyVal}")
    }

    private fun CoreTACProgram.mustComeBefore(before: String, after: String): Boolean {
        return SimpleDominanceAnalysis(this.blockgraph).dominates(getCommand(before), getCommand(after))
    }

    private fun CoreTACProgram.moveCommandsBetweenTo(startPos: String, endPos: String, targetPos: String): CoreTACProgram {
        return this.moveCommandsBetweenTo(this.getCommand(startPos), this.getCommand(endPos), this.getCommand(targetPos))
    }

    fun mkAnnotation(value: String): TACCmd.Simple.AnnotationCmd {
        return TACCmd.Simple.AnnotationCmd(META_KEY, value)
    }

    @Test
    fun testMoveCommandFailure() {
        val blocks: List<NBId> = List(5) { createBlock(it) }

        val block0 = blocks[0]
        val block1_A = blocks[1]
        val block1_B = blocks[2]
        val block2 = blocks[3]
        val block3 = blocks[4]

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1_A, block1_B),
            block1_A to treapSetOf(block2),
            block1_B to treapSetOf(block2),
            block2 to treapSetOf(block3),
            block3 to treapSetOf()
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)

        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpiCmd(block1_A, block1_B, symbol)),
            block1_A to listOf(TACCmd.Simple.JumpCmd(block2)),
            block1_B to listOf(TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(TACCmd.Simple.JumpCmd(block3)),
            block3 to listOf(TACCmd.Simple.NopCmd)
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        val program = CoreTACProgram(
            code,
            blockGraph,
            "malformed block",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        val p = program.toPatchingProgram()

        //Attempting to move two commands such that the start command doesn't dominate end command and vice versa fails
        assertThrows<IllegalStateException> { p.moveCommandsBetweenTo(CmdPointer(block1_A, 0), CmdPointer(block2, 0), CmdPointer(block3, 0)) }
    }

    @Test
    fun testMoveCommandSuccess() {
        val blocks: List<NBId> = List(5) { createBlock(it) }

        val block0 = blocks[0]
        val block1_A = blocks[1]
        val block1_B = blocks[2]
        val block2 = blocks[3]
        val block3 = blocks[4]

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1_A, block1_B),
            block1_A to treapSetOf(block2),
            block1_B to treapSetOf(block2),
            block2 to treapSetOf(block3),
            block3 to treapSetOf()
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)


        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpiCmd(block1_A, block1_B, symbol)),
            block1_A to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_A), mkAnnotation(MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block2)),
            block1_B to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_B), mkAnnotation(MOVE_COMMAND_B), TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(TACCmd.Simple.JumpCmd(block3)),
            block3 to listOf(mkAnnotation(TARGET_COMMAND))
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        var currProg = CoreTACProgram(
            code,
            blockGraph,
            "correctly formed prog",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        assert(!currProg.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))
        currProg = currProg.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_A, MOVE_COMMAND_A, TARGET_COMMAND)
        assert(currProg.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))
        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(currProg.blockgraph) }

        // Now move everything out of branch b.
        currProg = currProg.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_B, MOVE_COMMAND_B, TARGET_COMMAND)
        assert(currProg.mustComeBefore(MOVE_COMMAND_B, MOVE_COMMAND_A))
        assert(currProg.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_B))

        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(currProg.blockgraph) }
    }

    @Test
    fun testMoveCommandLinearSameBlock() {
        val blocks: List<NBId> = List(4) { createBlock(it) }

        val block0 = blocks[0]
        val block1 = blocks[1]
        val block2 = blocks[2]

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1),
            block1 to treapSetOf(block2),
            block2 to treapSetOf()
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)

        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpCmd(block1)),
            block1 to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_A), mkAnnotation(MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(mkAnnotation(TARGET_COMMAND))
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        var program = CoreTACProgram(
            code,
            blockGraph,
            "correctly formed prog",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        // pre-condition, the command to be moved must come before the target.
        assert(program.mustComeBefore(MOVE_COMMAND_A, TARGET_COMMAND))

        program = program.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_A, MOVE_COMMAND_A, TARGET_COMMAND)

        // post-condition, the command to be moved must come after the target.
        assert(program.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))

        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(program.blockgraph) }
    }
    @Test
    fun testMoveCommandLinearSameBlock0() {
        val blocks: List<NBId> = List(4) { createBlock(it) }

        val block0 = blocks[0]
        val block1 = blocks[1]
        val block2 = blocks[2]

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1),
            block1 to treapSetOf(block2),
            block2 to treapSetOf()
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)

        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(mkAnnotation(TARGET_COMMAND), TACCmd.Simple.JumpCmd(block1)),
            block1 to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_A), mkAnnotation(MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(mkAnnotation("nonEmptyBlock"))
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        var program = CoreTACProgram(
            code,
            blockGraph,
            "correctly formed prog",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        // pre-condition, the command to be moved must come before the target.
        assert(program.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))

        program = program.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_A, MOVE_COMMAND_A, TARGET_COMMAND)

        // post-condition, the command to be moved must come after the target.
        assert(program.mustComeBefore(MOVE_COMMAND_A, BEFORE_MOVE_COMMAND_A))

        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(program.blockgraph) }
    }
    @Test
    fun testMoveCommandLinearTwoBlocks() {
        val blocks: List<NBId> = List(4) { createBlock(it) }

        val block0 = blocks[0]
        val block1 = blocks[1]
        val block2 = blocks[2]
        val block3 = blocks[3]

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1),
            block1 to treapSetOf(block2),
            block2 to treapSetOf(block3),
            block3 to treapSetOf(),
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)


        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpCmd(block1)),
            block1 to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(mkAnnotation(MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block3)),
            block3 to listOf(mkAnnotation(TARGET_COMMAND))
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        var program = CoreTACProgram(
            code,
            blockGraph,
            "correctly formed prog",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        // pre-condition, the command to be moved must come before the target.
        assert(program.mustComeBefore(MOVE_COMMAND_A, TARGET_COMMAND))
        program = program.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_A, MOVE_COMMAND_A, TARGET_COMMAND)

        // post-condition, the command to be moved must come after the target.
        assert(program.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))
        assert(program.mustComeBefore(BEFORE_MOVE_COMMAND_A, TARGET_COMMAND))

        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(program.blockgraph) }
    }

    @Test
    fun testMoveCommandLinearTwoBlocksDifferentCallId() {
        val blocks: List<NBId> = List(6) { createBlock(it) }

        val block0 = blocks[0].copy(origStartPc = 0, calleeIdx = 1)
        val block1 = blocks[1].copy(origStartPc = 1, calleeIdx = 1)
        val block2 = blocks[2].copy(origStartPc = 2, calleeIdx = 2)
        val block3 = blocks[3].copy(origStartPc = 3, calleeIdx = 3)
        val block4 = blocks[4].copy(origStartPc = 4, calleeIdx = 2)
        val block5 = blocks[5].copy(origStartPc = 5, calleeIdx = 1)

        val blockGraph: BlockGraph = BlockGraph(
            block0 to treapSetOf(block1),
            block1 to treapSetOf(block2),
            block2 to treapSetOf(block3),
            block3 to treapSetOf(block4),
            block4 to treapSetOf(block5),
            block5 to treapSetOf(),
        )

        val symbol = TACKeyword.TMP(Tag.Bool, "dummyVar").toUnique("!")
        val tags = treapSetOf(symbol)

        val commandSameCallId = "commandInBlockWithSameCallId"
        val commandDifferentCallId = "commandInBlockWithDifferentCallId"

        val code: BlockNodes<TACCmd.Simple> = mapOf(
            block0 to listOf(TACCmd.Simple.JumpCmd(block1)),
            block1 to listOf(mkAnnotation(BEFORE_MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block2)),
            block2 to listOf(mkAnnotation(commandSameCallId), TACCmd.Simple.JumpCmd(block3)),
            block3 to listOf(mkAnnotation(commandDifferentCallId), TACCmd.Simple.JumpCmd(block4)),
            block4 to listOf(mkAnnotation(MOVE_COMMAND_A), TACCmd.Simple.JumpCmd(block5)),
            block5 to listOf(mkAnnotation(TARGET_COMMAND))
        )

        val symbolTable = TACSymbolTable(treapSetOf(), treapSetOf(), tags, mapOf())
        var program = CoreTACProgram(
            code,
            blockGraph,
            "correctly formed prog",
            symbolTable,
            UfAxioms.empty(),
            IProcedural.empty(),
        )

        // pre-condition, the command to be moved must come before the target.
        assert(program.mustComeBefore(MOVE_COMMAND_A, TARGET_COMMAND))
        program = program.moveCommandsBetweenTo(BEFORE_MOVE_COMMAND_A, MOVE_COMMAND_A, TARGET_COMMAND)

        // post-condition, the command to be moved must come after the target.
        assert(program.mustComeBefore(TARGET_COMMAND, MOVE_COMMAND_A))

        val newMoved = program.getCommand(TARGET_COMMAND)
        val newTarget = program.getCommand(MOVE_COMMAND_A)
        assert(newMoved.ptr.block.calleeIdx == newTarget.ptr.block.calleeIdx)

        // This command is in a different block but has the same call id, so we also want this command id
        // to be updated after the move
        val unrelatedCommandA = program.getCommand(commandSameCallId)
        assert(unrelatedCommandA.ptr.block.calleeIdx == newMoved.ptr.block.calleeIdx)

        // The callId of this command differs from the moved command, so it should differ afterwards
        val unrelatedCommandB = program.getCommand(commandDifferentCallId)
        assert(unrelatedCommandB.ptr.block.calleeIdx != newMoved.ptr.block.calleeIdx)

        // "Asserting" that the graph is loop free - topological order will throw in case it isn't...
        assertDoesNotThrow { topologicalOrder(program.blockgraph) }
    }

    private fun createBlock(id: CallId): NBId = StartBlock.copy(calleeIdx = id)
}
