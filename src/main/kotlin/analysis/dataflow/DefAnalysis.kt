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

import analysis.*
import com.certora.collect.*
import config.*
import datastructures.ArrayHashMap
import datastructures.ArrayHashSet
import datastructures.stdcollections.*
import tac.*
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors

abstract class DefAnalysis(
    val graph: TACCommandGraph,
    /**
        The set of variables that should initially be treated as undefined.

        For [StrictDefAnalysis], this is every variable that appears in the program.  For [LooseDefAnalysis] this is
        the empty set - we don't track undefined vars in that case.
     */
    private val initialUndefinedVars: TreapSet<TACSymbol.Var>
) {
    /**
        Gets the variable defined by this command, or null if it doesn't define any variable.

        Different subclasses may have different ideas of which commands define variables.
     */
    abstract protected fun TACCmd.getDefinedVar(): TACSymbol.Var?

    /**
        All definitions of every variable.  Maps (block, v) to an array of locations of defs in that block
     */
    private val allDefs: Map<Pair<NBId, TACSymbol.Var>, IntArray> =
        groupToArrays(
            graph.commands.mapNotNull { (ptr, cmd) ->
                cmd.getDefinedVar()?.let { v ->
                    (ptr.block to v) to ptr.pos
                }
            }
        )

    protected fun lastDef(block: NBId, v: TACSymbol.Var): CmdPointer? =
        allDefs[block to v]?.let { CmdPointer(block, it.last()) }

    /**
        For each variable, the set of all definitions that are the last in their respective blocks.
     */
    protected val lastDefs: Map<TACSymbol.Var, TreapSet<CmdPointer>> by lazy {
        ArrayHashMap<TACSymbol.Var, TreapSet<CmdPointer>>().also { lastDefs ->
            allDefs.forEachEntry { (blockAndVar, locs) ->
                val (block, v) = blockAndVar
                lastDefs[v] = lastDefs[v].orEmpty() + CmdPointer(block, locs.last())
            }
        }
    }

    /**
        Finds the last definition of a variable prior to [pointer], in the same block, if there is one.
     */
    protected fun findDefInSameBlock(v: TACSymbol.Var, pointer: CmdPointer): CmdPointer? =
        allDefs[pointer.block to v]?.findLast { it < pointer.pos }?.let { CmdPointer(pointer.block, it) }

    /**
        Finds the defs of a variable at a given program point, and whether the variable might still be undefined at
        that point (assuming [initialUndefinedVars] contains a meaningful set of variables)
     */
    protected fun findDefs(v: TACSymbol.Var, pointer: CmdPointer): Pair<Set<CmdPointer>, Boolean> =
        findDefInSameBlock(v, pointer)?.let {
            treapSetOf(it) to false
        } ?: findDefsInPriorBlocks(v, pointer)


    /**
        The set of definitions at the entry to each block, in [entryStates].
     */
    private data class DefState(
        /**
            The set of "easy" variables that are defined.  An "easy" variable is one whose uses are all in blocks which
            succeed all blocks in which the variable is defined (or the uses are in the same block as the definition,
            which we track separately).  For those, we don't have to track the set of definitions at each point, because
            they will all be the same.
         */
        val easy: TreapSet<TACSymbol.Var>,
        /**
            The current definitions of each "hard" variable (those that are not "easy")
         */
        val hard: TreapMap<TACSymbol.Var, TreapSet<CmdPointer>>,
        /**
            The subset of [initialUndefinedVars] which might still be undefined.
         */
        val undef: TreapSet<TACSymbol.Var>,
    )

    private fun join(a: DefState, b: DefState) = DefState(
        easy = a.easy + b.easy,
        hard = a.hard.union(b.hard) { _, aa, bb -> aa + bb },
        undef = a.undef + b.undef
    )

    /**
        The [DefState] at the entry to each block.
     */
    private val entryStates: Map<NBId, DefState> by lazy {
        object : TACBlockDataflowAnalysis<DefState>(
            graph,
            JoinLattice.ofJoin(::join),
            DefState(
                easy = treapSetOf(),
                hard = treapMapOf(),
                undef = initialUndefinedVars
            ),
            Direction.FORWARD
        ) {
            /**
                Find a set of "easy" variables (see [DefState.easy]).  We could do a very thorough search here, but that
                would be slow.  Instead, we simply look for variables that are only defined in blocks with a single
                common successor.  Because we apply [TACDSA] early in the pipeline, we expect that most variables will
                fit into this category.  We might miss a few variables that could be considered "easy" but don't fit
                this filter, but the only consequence is that we might have a little extra overhead from tracking them
                as "hard" variables.
            */
            val easyVars: Set<TACSymbol.Var> = ArrayHashSet<TACSymbol.Var>().also { easyVars ->
                lastDefs.forEachEntry { (v, ptrs) ->
                    if (ptrs.allSame { graph.succ(it.block) }) {
                        easyVars += v
                    }
                }
            }

            override fun transform(inState: DefState, block: TACBlock): DefState {
                var (easy, hard, undef) = inState
                block.commands.forEach { (ptr, cmd) ->
                    cmd.getDefinedVar()?.let { v ->
                        if (v in easyVars) {
                            easy += v
                        } else {
                            hard += v to treapSetOf(ptr)
                        }
                        undef -= v
                    }
                }
                return DefState(easy, hard, undef)
            }

            init { runAnalysis() }

        }.blockIn
    }

    protected open fun findDefsInPriorBlocks(v: TACSymbol.Var, pointer: CmdPointer): Pair<Set<CmdPointer>, Boolean> {
        val entryState = entryStates[pointer.block]!!
        val defsAtEntry = if (v in entryState.easy) { lastDefs[v]!! } else { entryState.hard[v] }
        return if (defsAtEntry == null) {
            treapSetOf<CmdPointer>() to true
        } else {
            defsAtEntry to (v in entryState.undef)
        }
    }
}

/**
    Fast and loose def analysis.  Does not care whether a variable might be undefined (unless it is definitely
    undefined). Only considers definitions via [TACCmd.Simple.AssigningCmd].
 */
open class LooseDefAnalysis protected constructor(
    graph: TACCommandGraph
) : DefAnalysis(
    graph,
    initialUndefinedVars = treapSetOf()
), IDefAnalysis {
    companion object : AnalysisCache.Key<TACCommandGraph, LooseDefAnalysis> {
        override fun createCached(graph: TACCommandGraph) = LooseDefAnalysis(graph)
    }

    override protected fun TACCmd.getDefinedVar() = (this as? TACCmd.Simple.AssigningCmd)?.lhs

    override fun defSitesOf(v: TACSymbol.Var, pointer: CmdPointer) =
        findDefs(v, pointer).first

    override fun findDefsInPriorBlocks(v: TACSymbol.Var, pointer: CmdPointer): Pair<Set<CmdPointer>, Boolean> {
        // If we're only doing a few queries, it's not worth the effort to do the whole dataflow analysis.
        // Instead, we just walk back through the graph until we find all of the definitions.  If we do too many of
        // these slow queries, then we switch to the dataflow analysis.
        if (slowQueryCount.incrementAndGet() < Config.DefAnalysisPreproceesingThreshold.get()
            || Config.TestMode.get() // see below
        ) {
            var defs = treapSetOf<CmdPointer>()
            val visited = ArrayHashSet<NBId>(127)
            val workList = arrayDequeOf<Set<NBId>>(graph.pred(pointer.block))
            workList.consume { blocks ->
                blocks.forEachElement { block ->
                    if (visited.add(block)) {
                        val def = lastDef(block, v)
                        if (def != null) {
                            defs += def
                        } else {
                            workList.addFirst(graph.pred(block))
                        }
                    }
                }
            }
            return (defs to defs.isEmpty()).also {
                // In test mode, always run both kinds of query, and check that they return the same result.  We don't
                // want bugs that depend on the number of queries!
                if (Config.TestMode.get()) {
                    val superDefs = super.findDefsInPriorBlocks(v, pointer)
                    check(it == superDefs) {
                        "Def analysis disagreement in ${graph.name} for $v at $pointer: $it != $superDefs"
                    }
                }
            }
        } else {
            // Trigger the (lazy) dataflow analysis, and use the result.
            return super.findDefsInPriorBlocks(v, pointer)
        }
    }

    private val slowQueryCount = AtomicInteger(0)
}

/**
    More strict def analysis than [LooseDefAnalysis].  Returns [null] along with the known defintitions, if the variable
    might be undefined.  Considers more commands to be "defining" commands (see [getModifiedVar]).
 */
open class StrictDefAnalysis protected constructor(
    graph: TACCommandGraph
) : DefAnalysis(
    graph,
    initialUndefinedVars = graph.blocks.parallelStream().flatMap {
        it.commands.flatMapToSet { it.cmd.freeVars() }.stream()
    }.collect(Collectors.toCollection { treapSetBuilderOf() }).build()
) {
    companion object : AnalysisCache.Key<TACCommandGraph, StrictDefAnalysis> {
        override fun createCached(graph: TACCommandGraph) = StrictDefAnalysis(graph)
    }

    override protected fun TACCmd.getDefinedVar() = getModifiedVar()

    fun defSitesOf(v: TACSymbol.Var, pointer: CmdPointer) =
        findDefs(v, pointer).let { (defs, maybeUndefined) ->
            if (maybeUndefined) { defs + treapSetOf(null) } else { defs }
        }

    sealed interface Source {
        data class Defs(val ptrs: Set<CmdPointer?>) : Source {
            constructor(vararg ptrs: CmdPointer?) : this(ptrs.toSet())
        }

        data class Uinitialized(val v: TACSymbol.Var) : Source
        data class Const(val n: BigInteger) : Source
    }

    /**
     * Basically non-trivial def analysis.
     * Gets a symbol thought to be at the rhs of [ptr] (that is, we ignore the possible effect of the command at [ptr].
     * traverses back through simple assignment, while they are decisive, and gives the result when we can't do
     * that anymore.
     *
     * The main property is that if the [source] of two symbols at the two ptrs is equal, then they are always equal.
     * It's not clear if this statement is true if the graph has loops. Currently the is no proof for this, no
     * counter example, but also no use case...
     */
    fun source(ptr: CmdPointer, sym: TACSymbol): Source {
        return when (sym) {
            is TACSymbol.Const ->
                Source.Const(sym.value)

            is TACSymbol.Var -> {
                val defs = defSitesOf(sym, ptr)
                if (defs.size >= 2) {
                    return Source.Defs(defs)
                }
                val def = defs.single()
                    ?: return Source.Uinitialized(sym)
                val cmd = graph.toCommand(def)
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.rhs is TACExpr.Sym) {
                    source(def, cmd.rhs.s)
                } else {
                    Source.Defs(def)
                }
            }
        }
    }
}



/**
 * Creates a map from the sequence [seq], accumulating for each [K] the Ints it gets, in order, to an [IntArray] of the
 * exact needed size.
 *
 * It is very good for memory efficiency, but note that it generates each element twice (once for counting, and then for
 * accumulating). Therefore, it is important that [seq] is a sequence that can be traversed twice.
 */
fun <K> groupToArrays(seq : Sequence<Pair<K, Int>>) =
    buildUnorderedMap<K, IntArray> {
        // Begin with pre-sized arrays
        val count = buildMutableUnorderedMap {
            seq.forEach { (k, _) ->
                this[k] = (this[k] ?: 0) + 1
            }
        }
        count.forEachEntry { (k, c) ->
            this[k] = IntArray(c)
        }
        // Fill in the arrays in sequence order.
        seq.forEach { (k, i) ->
            val remainingCount = count[k]!!
            this[k]!!.let { it[it.size - remainingCount] = i }
            count[k] = remainingCount - 1
        }
    }
