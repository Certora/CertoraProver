/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import analysis.CmdPointer
import analysis.TACCommandGraph
import datastructures.memoized
import datastructures.stdcollections.*
import instrumentation.transformers.DSAChecker
import tac.NBId
import vc.data.AnalysisCache
import vc.data.TACSymbol

/**
 * Def analysis for a program in diamond-DSA form, where a variable is assigned either once, at a site dominating all
 * its uses, or once per arm of a clean diamond and then read below the join, or inside one of the arms. That makes
 * reaching definitions structural: the single definition reaches every use; of several, a read below the join is
 * reached by all of them, and a read inside an arm only by that arm's. So unlike [StrictDefAnalysis], which answers
 * the same question for an arbitrary program with a forward block dataflow, this is one pass over the commands into
 * two maps - the single-def variables, which are nearly all of them, to their definition, and the multi-def ones to
 * their arm definitions - and no dataflow and no dominance, only a look at the block the read is in.
 *
 * The DSA shape is assumed and not checked: on a program that is not in DSA the answers are meaningless, and it is up
 * to the caller to have established the precondition (see [DSAChecker.checkDSA]). Under it the [IDefAnalysis] contract
 * holds at every use - each returned [CmdPointer] is strictly before it.
 *
 * Note that currently, such a pure DSA form exists only after the [instrumentation.transformers.TACDSA.simplify] call
 * in [verifier.AbstractTACChecker].
 */
class DsaDefAnalysis private constructor(graph: TACCommandGraph) : IDefAnalysis {
    companion object : AnalysisCache.Key<TACCommandGraph, DsaDefAnalysis> {
        override fun createCached(graph: TACCommandGraph) = DsaDefAnalysis(graph)
    }

    private val single = mutableUnorderedMapOf<TACSymbol.Var, CmdPointer>()
    private val multi = mutableUnorderedMapOf<TACSymbol.Var, MutableMap<NBId, CmdPointer>>()

    init {
        for ((ptr, cmd) in graph.commands) {
            val v = cmd.getModifiedVar() ?: continue
            val arms = multi[v]
            when {
                arms != null -> arms[ptr.block] = ptr
                v !in single -> single[v] = ptr
                else -> {
                    val o = single.remove(v)!!
                    multi[v] = mutableUnorderedMapOf(o.block to o, ptr.block to ptr)
                }
            }
        }
    }

    /** The arm definitions of a multi-def variable as one set, built once; empty for any other variable. */
    private val armSites = memoized { v: TACSymbol.Var ->
        multi[v]?.values?.toSet().orEmpty()
    }

    /**
     * Every definition of [v]: its single one, or one per arm. Empty when [v] is never assigned. These are also
     * the definitions reaching a read below the join, the arms all reaching it.
     */
    fun defSitesOf(v: TACSymbol.Var): Set<CmdPointer> =
        single[v]
            ?.let(::setOf)
            ?: armSites(v)

    /**
     * The definitions of [v] reaching [pointer]: all of them, except at a read inside one of the arms, which only
     * that arm's definition reaches. DSA leaves no other place for the read to be, so no dominance query is needed
     * to tell the two apart - the reading block either holds a definition or is below the join.
     */
    override fun defSitesOf(v: TACSymbol.Var, pointer: CmdPointer): Set<CmdPointer> =
        multi[v]
            ?.get(pointer.block)?.let(::setOf)
            ?: defSitesOf(v)
}
