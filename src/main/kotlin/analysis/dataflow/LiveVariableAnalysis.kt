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

package analysis.dataflow

import utils.lazy
import analysis.BlockDataflowAnalysis
import analysis.CmdPointer
import analysis.Direction
import analysis.GenericTACCommandGraph
import analysis.GraphBlockView
import analysis.JoinLattice
import analysis.LTACCmd
import analysis.LTACCmdGen
import analysis.TACBlock
import analysis.TACBlockGen
import analysis.TACBlockView
import analysis.TACCommandGraph
import com.certora.collect.TreapSet
import com.certora.collect.minus
import com.certora.collect.orEmpty
import com.certora.collect.plus
import com.certora.collect.treapSetOf
import datastructures.stdcollections.*
import tac.NBId
import vc.data.AnalysisCache
import vc.data.AssigningSummary
import vc.data.TACCmd
import vc.data.TACSymbol
import kotlin.collections.associate

abstract class GenericLiveVariableAnalysis<T: TACCmd, U: LTACCmdGen<T>, V: TACBlockGen<T, U>, G: GenericTACCommandGraph<T, U, V>>(
    private val graph: G,
    val cmdFilter: ((U) -> Boolean) = {_ -> true},
    val blockView: GraphBlockView<G, V, NBId>
) {

    abstract fun reads(lcmd: U): Collection<TACSymbol.Var>
    abstract fun writes(lcmd: U): Collection<TACSymbol.Var>

    private val lvars: Map<CmdPointer, Pair<TreapSet<TACSymbol.Var>, TreapSet<TACSymbol.Var>>> by lazy {
        // First, compute the net effect each block has on the set of live variables, independent of other blocks.
        // We do this once per block, so that we don't have to compute the sets for each instruction in the worklist
        // iteration below.
        data class BlockSummary(
            val adds: TreapSet<TACSymbol.Var>,
            val removes: TreapSet<TACSymbol.Var>
        )
        val blockSummaries = graph.blocks.associate {
            val blk = graph.elab(it.id)
            var adds = treapSetOf<TACSymbol.Var>()
            var removes = treapSetOf<TACSymbol.Var>()
            for (c in blk.commands.asReversed()) {
                if (cmdFilter(c)) {
                    val w = writes(c)
                    adds -= w
                    removes += w

                    val r = reads(c)
                    adds += r
                    removes -= r
                }
            }
            it.id to BlockSummary(adds, removes)
        }

        // Bubble each block's effects through its predecessors.
        val blockPost = object : BlockDataflowAnalysis<G, V, NBId, TreapSet<TACSymbol.Var>>(
            graph = graph,
            initial = treapSetOf(),
            lattice = JoinLattice.Companion.ofJoin { a, b -> a + b },
            direction = Direction.BACKWARD,
            blockView = blockView
        ) {
            override fun transform(inState: TreapSet<TACSymbol.Var>, block: V) =
                blockSummaries[block.id]!!.let { (adds, removes) ->
                    (inState - removes) + adds
                }
            init {
                runAnalysis()
            }
        }.blockIn

        // Now compute the live variables for each individual command.  Again, one pass per block.
        buildMap {
            for (b in graph.blocks) {
                var after = blockPost[b.id].orEmpty()
                for (c in b.commands.asReversed()) {
                    var before = after
                    if (cmdFilter(c)) {
                        before -= writes(c)
                        before += reads(c)
                    }
                    put(c.ptr, before to after)
                    after = before
                }
            }
        }
    }

    fun liveVariablesBefore(ptr: CmdPointer): Set<TACSymbol.Var> = lvars[ptr]?.first.orEmpty()
    fun liveVariablesBefore(block : NBId): Set<TACSymbol.Var> = lvars[CmdPointer(block, 0)]?.first.orEmpty()
    fun liveVariablesAfter(ptr: CmdPointer): Set<TACSymbol.Var> = lvars[ptr]?.second.orEmpty()

    fun isLiveAfter(ptr: CmdPointer, v: TACSymbol.Var): Boolean = (v in liveVariablesAfter(ptr))
    fun isLiveBefore(ptr: CmdPointer, v: TACSymbol.Var): Boolean = (v in liveVariablesBefore(ptr))
    fun isLiveBefore(block : NBId, v: TACSymbol.Var): Boolean = (v in liveVariablesBefore(block))
}

class LiveVariableAnalysis(
    graph: TACCommandGraph,
    cmdFilter: ((LTACCmd) -> Boolean) = { _ -> true },
) : GenericLiveVariableAnalysis<TACCmd.Simple, LTACCmd, TACBlock, TACCommandGraph>(graph, cmdFilter, TACBlockView()) {

    companion object : AnalysisCache.Key<TACCommandGraph, LiveVariableAnalysis> {
        override fun createCached(graph: TACCommandGraph) = LiveVariableAnalysis(graph)
    }

    override fun writes(lcmd: LTACCmd): Collection<TACSymbol.Var> {
        val cmd = lcmd.cmd
        return when {
            cmd is TACCmd.Simple.AssigningCmd -> setOf(cmd.lhs)

            cmd is TACCmd.Simple.SummaryCmd && cmd.summ is AssigningSummary -> cmd.summ.mustWriteVars

            else -> setOf()
        }
    }

    override fun reads(lcmd: LTACCmd): Set<TACSymbol.Var> {
        return lcmd.cmd.getFreeVarsOfRhsExtended()
    }
}
