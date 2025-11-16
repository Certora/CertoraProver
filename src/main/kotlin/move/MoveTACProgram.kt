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

import algorithms.SimpleDominanceAnalysis
import analysis.*
import analysis.worklist.NaturalBlockScheduler
import com.certora.collect.*
import config.*
import datastructures.*
import datastructures.stdcollections.*
import datastructures.stdcollections.mapValues
import log.*
import move.MoveTACProgram.Block
import move.MoveTACProgram.LCmd
import move.analysis.*
import tac.*
import utils.*
import vc.data.*
import verifier.BlockMerger

typealias MoveCmdsWithDecls = CommandWithRequiredDecls<TACCmd>
typealias MoveBlocks = TreapMap<NBId, MoveCmdsWithDecls>

class MoveAnalysisCache(private val lazyGraph: Lazy<MoveTACCommandGraph>)
    : AnalysisCache<MoveTACCommandGraph>(), HasDominanceAnalysis {
    override val graph by lazyGraph

    override val domination get() = this[SimpleDominanceAnalysis]
    val lva get() = this[MoveLiveVariableAnalysis]
    val naturalBlockScheduler get() = this[blockSchedulerKey]
    val references get() = this[ReferenceAnalysis]
    val def get() = this[MoveDefAnalysis]
    private val blockSchedulerKey = NaturalBlockScheduler.makeKey<MoveTACCommandGraph>()
}

class MoveTACCommandGraph(
    override val blockGraph: BlockGraph,
    override val code: BlockNodes<TACCmd>,
    override val symbolTable: TACSymbolTable,
    override val name: String
): GenericTACCommandGraph<TACCmd, LCmd, Block>(), GraphPathConditions, HasDominanceAnalysis {
    override fun elab(p: CmdPointer): LCmd {
        return LCmd(p, toCommand(p))
    }

    override val domination get() = cache.domination
    val cache: MoveAnalysisCache = MoveAnalysisCache(lazyOf(this))

    val successors get() = blockGraph
    val predecessors by lazy { reverse(blockGraph) }

    private val blocksById by lazy {
        code.mapValues { (nbid, cmds) ->
            Block(nbid, cmds.mapIndexed { i, cmd -> LCmd(CmdPointer(nbid, i), cmd) })
        }
    }

    override val blocks get() = blocksById.values.toList()

    override fun pathConditionsOf(blockId: NBId): Map<NBId, PathCondition> {
        return elab(blockId).let {
            val (ptr, cmd) = it.commands.last()
            check (cmd is TACCmd.Simple)
            SimplePathConditionReasoning.pathConditions(
                LTACCmd(ptr, cmd),
                succ(blockId)
            )
        }
    }
}

object MoveBlockView : GraphBlockView<MoveTACCommandGraph, Block, NBId> {
    override fun succ(g: MoveTACCommandGraph, src: Block) = g.succ(src)
    override fun pred(g: MoveTACCommandGraph, src: Block) = g.pred(src)
    override fun blockGraph(g: MoveTACCommandGraph): Map<NBId, Set<NBId>> = g.blockGraph
    override fun elab(g: MoveTACCommandGraph, l: NBId) = g.elab(l)
    override fun blockId(b: Block): NBId = b.id
}


/**
    Intermediate representation of a Move program, prior to transformation by [MoveTACSimplifier.transform].

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

    val graph = MoveTACCommandGraph(blockgraph, code, symbolTable, name)

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

    data class LCmd(
        override val ptr: CmdPointer,
        override val cmd: TACCmd
    ) : LTACCmdGen<TACCmd>

    data class Block(
        override val id: NBId,
        override val commands: List<LCmd>
    ) : TACBlockGen<TACCmd, LCmd>

    private class CommandView : BlockCommandView<Block, LCmd, CmdPointer> {
        override fun commands(b: Block) = b.commands
        override fun ptr(c: LCmd) = c.ptr
    }

    abstract class CommandDataflowAnalysis<T : Any>(
        graph: MoveTACCommandGraph,
        lattice: JoinLattice<T>,
        bottom: T,
        dir: Direction
    ): analysis.CommandDataflowAnalysis<MoveTACCommandGraph, Block, NBId, LCmd, CmdPointer, T>(
        graph, lattice, bottom, dir, MoveBlockView, CommandView()
    ) {
        protected abstract inner class Finalizer
            : analysis.CommandDataflowAnalysis<MoveTACCommandGraph, Block, NBId, LCmd, CmdPointer, T>.Finalizer()
    }

    object PatchingCommandRemapper : PatchingTACProgram.CommandRemapper<TACCmd> {
        override fun isJumpCommand(c: TACCmd) =
            c is TACCmd.Simple && PatchingTACProgram.SIMPLE.isJumpCommand(c)
        override fun remapSuccessors(c: TACCmd, remapper: (NBId) -> NBId) =
            (c as? TACCmd.Simple)?.let {
                PatchingTACProgram.SIMPLE.remapSuccessors(it, remapper)
            } ?: c
    }

    fun mergeBlocks(mayMerge: (NBId, NBId) -> Boolean = { _, _ -> true }): MoveTACProgram {
        return BlockMerger.generateMerged(this, mayMerge).let { (code, graph) ->
            copy(code = code, blockgraph = graph)
        }
    }
}
