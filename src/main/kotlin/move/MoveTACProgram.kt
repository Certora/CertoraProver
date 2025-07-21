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

package move

import analysis.*
import com.certora.collect.*
import config.*
import datastructures.*
import datastructures.stdcollections.*
import log.*
import tac.*
import utils.*
import vc.data.*

typealias MoveCmdsWithDecls = CommandWithRequiredDecls<TACCmd>
typealias MoveBlocks = TreapMap<NBId, MoveCmdsWithDecls>

/**
    Intermediate representation of a Move program, prior to transformation by [MoveMemory.transform].

    Memory-related commands are represented by `TACCmd.Move` commands.
 */
data class MoveTACProgram(
    override val code: BlockNodes<TACCmd>,
    override val blockgraph: BlockGraph,
    override val name: String,
    override val symbolTable: TACSymbolTable,
    val entryBlock: NBId,
) : TACProgram<TACCmd>(entryBlock) {
    init {
        try {
            checkCodeGraphConsistency()
        } catch (e: Exception) {
            when (e) {
                is TACTypeCheckerException,
                is TACStructureException ->
                    ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                        this,
                        ReportTypes.ERROR,
                        StaticArtifactLocation.Reports,
                        DumpTime.AGNOSTIC
                    )
            }
            throw e
        }
    }

    override fun myName() = name
    override val analysisCache get() = null
    override fun getNodeCode(n: NBId) = code[n] ?: error("Node $n does not appear in graph")
    override fun toPatchingProgram() = PatchingTACProgram(code, blockgraph, name, entryBlockIdInternal)

    override fun dumpBinary(where: String, label: String): TACFile {
        throw UnsupportedOperationException("Can only dump Simple TAC programs to binary")
    }

    override fun copyWith(
        code: BlockNodes<TACCmd>,
        blockgraph: BlockGraph,
        name: String,
        symbolTable: TACSymbolTable,
        procedures: Set<Procedure>?,
    ) = this.copy(code = code, blockgraph = blockgraph, name = name, symbolTable = symbolTable)

    val successors get() = blockgraph
    val predecessors by lazy { reverse(blockgraph) }

    fun succ(b: NBId) = successors[b] ?: error("Block $b is not in the graph")
    fun pred(b: NBId) = predecessors[b] ?: error("Block $b is not in the graph")

    data class LCmd(
        override val ptr: CmdPointer,
        override val cmd: TACCmd
    ) : LTACCmdGen<TACCmd>

    data class Block(
        override val id: NBId,
        override val commands: List<LCmd>
    ) : TACBlockGen<TACCmd, LCmd>

    private val blocksById by lazy {
        code.mapValues { (nbid, cmds) ->
            Block(nbid, cmds.mapIndexed { i, cmd -> LCmd(CmdPointer(nbid, i), cmd) })
        }
    }

    fun elab(l: NBId): Block = blocksById[l] ?: error("Block $l is not in the graph")
    val blocks get() = blocksById.values

    private val succBlocks by lazy {
        successors.mapValues { (_, succs) ->
            succs.map { blocksById[it]!! }
        }
    }

    private val predBlocks by lazy {
        predecessors.mapValues { (_, preds) ->
            preds.map { blocksById[it]!! }
        }
    }

    private inner class BlockView : GraphBlockView<MoveTACProgram, Block, NBId> {
        override fun succ(g: MoveTACProgram, src: Block) = succBlocks[src.id]!!
        override fun pred(g: MoveTACProgram, src: Block) = predBlocks[src.id]!!
        override fun blockGraph(g: MoveTACProgram): Map<NBId, Set<NBId>> = g.blockgraph
        override fun elab(g: MoveTACProgram, l: NBId) = g.elab(l)
        override fun blockId(b: Block): NBId = b.id
    }

    private class CommandView : BlockCommandView<Block, LCmd, CmdPointer> {
        override fun commands(b: Block) = b.commands
        override fun ptr(c: LCmd) = c.ptr
    }

    abstract class CommandDataflowAnalysis<T : Any>(
        program: MoveTACProgram,
        lattice: JoinLattice<T>,
        bottom: T,
        dir: Direction
    ): analysis.CommandDataflowAnalysis<MoveTACProgram, Block, NBId, LCmd, CmdPointer, T>(
        program, lattice, bottom, dir, program.BlockView(), CommandView()
    ) {
        protected abstract inner class Finalizer
            : analysis.CommandDataflowAnalysis<MoveTACProgram, Block, NBId, LCmd, CmdPointer, T>.Finalizer()
    }
}
