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

import analysis.LTACCmdView
import com.certora.collect.*
import datastructures.MutableReversibleDigraph
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import tac.NBId
import utils.mapToSet
import java.util.stream.Stream

private val logger = Logger(LoggerTypes.COMMON)

class SimplePatchingProgram(
    originalCode: Map<NBId, List<TACCmd.Simple>>,
    originalBlockgraph: BlockGraph,
    blockgraph: MutableReversibleDigraph<NBId>,
    procedures: MutableSet<Procedure>,
    name: String,
    root: NBId? = null,
) : PatchingTACProgram<TACCmd.Simple>(originalCode, originalBlockgraph, blockgraph, procedures, name, root) {

    constructor(originalCode: Map<NBId, List<TACCmd.Simple>>,
                originalBlockgraph: BlockGraph,
                procedures: MutableSet<Procedure>,
                name: String, root: NBId? = null
    ) : this(originalCode, originalBlockgraph, MutableReversibleDigraph<NBId>(originalBlockgraph), procedures, name, root)

    /**
     * Given a conditional jump [jumpiCmd] whose condition is known to be [condValue],
     * removes the redundant edge, and if edge removal renders the graph disconnected,
     * also removes the subgraph that disconnected from the main graph.
     */
    fun selectConditionalEdge(
        jumpiCmd: LTACCmdView<TACCmd.Simple.JumpiCmd>,
        condValue: Boolean,
        // usually we prove that a certain branch must be taken and thus we just remove the other branch.
        // but sometimes (e.g. splitting) we want to actively assume a branch is taken before we take it.
        explicitlyAssumeTheSelection: Boolean = false
    ) {
        selectConditionalEdges(
            listOf(
                jumpiCmd to condValue
            ),
            explicitlyAssumeTheSelection = explicitlyAssumeTheSelection
        )
    }

    fun selectConditionalEdges(
        edges: List<Pair<LTACCmdView<TACCmd.Simple.JumpiCmd>, Boolean>>,
        explicitlyAssumeTheSelection: Boolean = false
    ) {
        val toRemove = mutableSetOf<NBId>()
        for ((jumpiCmd, condValue) in edges) {
            val blockToUpdate = jumpiCmd.ptr.block
            val (chosenSucc, removedSucc) = if (condValue) {
                jumpiCmd.cmd.dst to jumpiCmd.cmd.elseDst
            } else {
                jumpiCmd.cmd.elseDst to jumpiCmd.cmd.dst
            }

            check(chosenSucc != removedSucc) { "Cannot select conditional edge when then and else targets are the same: $jumpiCmd" }

            val shouldRemoveSubgraph = getPredecessors(removedSucc) == setOf(blockToUpdate)
            val replacement = mutableListOf<TACCmd.Simple>()
            if (explicitlyAssumeTheSelection && condValue) {
                replacement.add(
                    TACCmd.Simple.AssumeCmd(
                        jumpiCmd.cmd.cond, "selectConditionalEdges"
                    )
                )
            } else if (explicitlyAssumeTheSelection /* in particular, !condValue */) {
                replacement.add(
                    TACCmd.Simple.AssumeNotCmd(
                        jumpiCmd.cmd.cond
                    )
                )
            }

            replacement.add(
                // we propagate the meta, to save the source code information (use case: source code info of loops)
                TACCmd.Simple.JumpCmd(chosenSucc, meta = jumpiCmd.cmd.meta)
            )
            replaceCommand(jumpiCmd.ptr, replacement)

            if (shouldRemoveSubgraph) {
                toRemove.add(removedSucc)
            }
        }
        if (toRemove.isNotEmpty()) {
            removeSubgraph(toRemove)
        }
    }

    private data class Edge(val src: NBId, val dst: NBId)

    private val edgeAppendices = mutableMapOf<Edge, MutableList<TACCmd.Simple>>()

    fun insertAlongEdge(src: NBId, dst: NBId, cmds: List<TACCmd.Simple>) {
        if (blockgraph[src]?.contains(dst) != true) {
            throw IllegalArgumentException("There is no edge in the graph from $src to $dst")
        }
        edgeAppendices.computeIfAbsent(Edge(src, dst)) {
            mutableListOf()
        }.addAll(cmds)
    }

    override fun rerouteBlock(oldBlock: NBId, newBlock: NBId) {
        edgeAppendices.mapNotNull {
            if(oldBlock == it.key.dst) {
                Triple(it.key, Edge(it.key.src, newBlock), it.value)
            } else {
                null
            }
        }.forEach {
            edgeAppendices.remove(it.first)
            edgeAppendices[it.second] = it.third
        }
    }

    override fun remapBlock(oldBlock: NBId, newTail: List<NBId>) {
        edgeAppendices.mapNotNull {
            if(oldBlock == it.key.dst) {
                Triple(it.key, Edge(it.key.src, newTail.first()), it.value)
            } else if(oldBlock == it.key.src) {
                Triple(it.key, Edge(newTail.last(), it.key.dst), it.value)
            } else {
                null
            }
        }.forEach { (old, e, app) ->
            edgeAppendices.remove(old)
            edgeAppendices[e] = app
        }
    }

    override fun toCode(empty: TACCmd.Simple?): Pair<BlockNodes<TACCmd.Simple>, BlockGraph> {
        if (edgeAppendices.isEmpty()) {
            return super.toCode(empty)
        }
        val (cmds, blockgraph) = super.toCode(empty)
        val mutCmds = cmds.toMutableMap()
        val mutGraph = MutableBlockGraph(blockgraph)
        for ((edge, toAdd) in edgeAppendices) {
            if (edge.src !in blockgraph) {
                logger.warn { "${edge.src} is no longer in the graph, but the edge to ${edge.dst} has appendix commands $toAdd" }
                continue
            }
            val srcDestinations = blockgraph[edge.src] ?: throw IllegalStateException("no edges from ${edge.src}")
            if (edge.dst !in srcDestinations) {
                throw IllegalStateException("Requested insertion of $toAdd along edge from ${edge.src} to ${edge.dst} but that link no longer exists")
            }
            /*
              If dst is the only successor of src, AND there is no jumping command, then we can just append the commands to the
              src block
             */
            if (srcDestinations.size == 1) {
                val srcCmds =
                    mutCmds[edge.src] ?: throw IllegalStateException("the source block ${edge.src} has no commands?")
                if (!SIMPLE.isJumpCommand(srcCmds.last())) {
                    mutCmds[edge.src] = srcCmds + toAdd
                    continue
                }
                // otherwise, rewire
                val intercedingBlock = deriveNewBlock(edge.src)
                rewireToInterceding(mutGraph, edge, intercedingBlock, mutCmds, toAdd, srcCmds)
                mutGraph[edge.src] = treapSetOf(intercedingBlock)
                /* The destination has a single predecessor (which should be src)
                    Then just prepend to the destination block
                 */
            } else if (blockgraph.filter {
                    edge.dst in it.value
                }.size == 1) {
                mutCmds[edge.dst] = toAdd + (mutCmds[edge.dst]
                    ?: throw IllegalStateException("The destination block ${edge.dst} has no commands"))
            } else {
                val srcCmds =
                    mutCmds[edge.src] ?: throw IllegalStateException("the source block ${edge.src} has no commands?")
                val intercedingBlock = deriveNewBlock(edge.src)
                mutGraph[edge.src] = (mutGraph[edge.src]!! - edge.dst) + intercedingBlock
                if (!SIMPLE.isJumpCommand(srcCmds.last())) {
                    // then this is odd, but not necessarily wrong I suppose
                    mutCmds[intercedingBlock] = toAdd
                    mutGraph[intercedingBlock] = treapSetOf(edge.dst)
                } else {
                    rewireToInterceding(mutGraph, edge, intercedingBlock, mutCmds, toAdd, srcCmds)
                }
            }
        }
        return mutCmds to mutGraph
    }

    private fun rewireToInterceding(
        mutGraph: MutableBlockGraph,
        edge: Edge,
        intercedingBlock: NBId,
        mutCmds: MutableMap<NBId, List<TACCmd.Simple>>,
        toAdd: MutableList<TACCmd.Simple>,
        srcCmds: List<TACCmd.Simple>
    ) {
        mutCmds[intercedingBlock] = toAdd
        mutGraph[intercedingBlock] = when (val last = toAdd.last()) {
            is TACCmd.Simple.JumpCmd -> {
                check(last.dst == edge.dst) {
                    "inserting along edge $edge a jump to a different block ${last.dst}"
                }
                treapSetOf(last.dst)
            }
            is TACCmd.Simple.JumpiCmd -> {
                check(last.dst == edge.dst || last.elseDst == edge.dst) {
                    "inserting along edge $edge a jumpi to different blocks ${last.dst} and ${last.elseDst}"
                }
                treapSetOf(last.dst, last.elseDst)
            }
            else -> treapSetOf(edge.dst)
        }

        mutCmds[edge.src] = srcCmds.dropLast(1) + SIMPLE.remapSuccessors(srcCmds.last()) {
            if (it == edge.dst) {
                intercedingBlock
            } else {
                it
            }
        }
    }

    fun toCode() = this.toCode(TACCmd.Simple.NopCmd)

    /**
     *
     * Will drop all the subgraphs dominated by [droppedTargets] and re-routing their predecessors to [chosenTarget].
     */
    fun consolidateEdges(chosenTarget: NBId, droppedTargets: Collection<NBId>) =
        consolidateEdges(chosenTarget, droppedTargets, SIMPLE)

    /**
     * See [PatchingTACProgram.reroutePredecessorsTo] for details
     */
    fun reroutePredecessorsTo(x: NBId, g: List<TACCmd.Simple>, succ: TreapSet<NBId>? = null, predFilter: (NBId) -> Boolean = { true }) =
        reroutePredecessorsTo(x, g, succ, SIMPLE, predFilter)

    /**
     * See [PatchingTACProgram.reroutePredecessorsTo] for details
     */
    fun reroutePredecessorsTo(x: NBId, newTarget: NBId, predFilter: (NBId) -> Boolean = { true }) =
        reroutePredecessorsTo(x, newTarget, SIMPLE, predFilter)
    fun removeBlockWithSingleSuccessor(b: NBId) = removeBlockWithSingleSuccessor(b, SIMPLE)

    fun toCode(base: CoreTACProgram, emptyCommand: TACCmd.Simple = TACCmd.Simple.NopCmd): CoreTACProgram =
        toCode(base, emptyCommand, true)

    fun toCodeNoTypeCheck(base: CoreTACProgram, emptyCommand: TACCmd.Simple = TACCmd.Simple.NopCmd): CoreTACProgram =
        toCode(base, emptyCommand, false)

    fun toCode(
        base: CoreTACProgram,
        emptyCommand: TACCmd.Simple = TACCmd.Simple.NopCmd,
        check: Boolean
    ): CoreTACProgram {
        val (newCode, newBlockgraph) = toCode(emptyCommand)

        val newProcedures = procedures.toList()


        val newUfs = base.symbolTable.uninterpretedFunctions().mapToSet { replacedScalarUfs[it] ?: it } + newUfs - ufsToDrop

        val newVars = buildNewVars(base.symbolTable, newUfs)

        val newUfAxioms = updateAxiomsWrtReplacedUfs(replacedUfAxioms ?: base.ufAxioms)

        return base.copy(
            code = newCode,
            blockgraph = newBlockgraph,
            procedures = base.procedures + newProcedures,
            symbolTable = TACSymbolTable(
                userDefinedTypes = base.symbolTable.userDefinedTypes + newUninterpretedSorts,
                vars = newVars,
                uninterpretedFunctions = newUfs,
                globalScope = base.symbolTable.globalScope
            ),
            instrumentationTAC = base.instrumentationTAC.copy(ufAxioms = newUfAxioms),
            check = check,
        )
    }
    companion object {
        fun <T> Stream<T>.patchForEach(c: CoreTACProgram, check: Boolean = false, f: SimplePatchingProgram.(T) -> Unit) : CoreTACProgram {
            return if(check) {
                c.patching {patcher ->
                    this@patchForEach.sequential().forEach {
                        patcher.f(it)
                    }
                }
            } else {
                val patch = c.toPatchingProgram()
                this@patchForEach.sequential().forEach {
                    patch.f(it)
                }
                patch.toCodeNoTypeCheck(c)
            }
        }
    }
}
