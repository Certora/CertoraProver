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

package wasm.analysis.intervals

import analysis.*
import analysis.numeric.AbstractNaturalBlockScheduledAnalysis
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntLoopInvariants
import com.certora.collect.*
import log.*
import tac.NBId
import vc.data.*
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import analysis.numeric.simplequalifiedint.UnreachableState

private val logger = Logger(LoggerTypes.ABSTRACT_INTERPRETATION)

/** A worklist-based interval analysis */
class IntervalAnalysis private constructor(private val graph: TACCommandGraph)
    : AbstractNaturalBlockScheduledAnalysis<SimpleQualifiedIntState, TACCmd.Simple, LTACCmd, TACBlock, TACCommandGraph>(
        graph,
    ){
    companion object : AnalysisCache.Key<TACCommandGraph, IntervalAnalysis> {
        override fun createCached(graph: TACCommandGraph) = IntervalAnalysis(graph)
    }

    override val dom = graph.cache.domination
    override val lva = graph.cache.lva
    override val initialState = SimpleQualifiedIntState(treapMapOf())
    override val scheduler = graph.cache.naturalBlockScheduler
    private val interpreter = IntervalInterpreter(graph)

    override val invariantHeuristic =
        object : SimpleQualifiedIntLoopInvariants<TACCmd.Simple, LTACCmd, TACBlock, TACCommandGraph, SimpleQualifiedIntState> {
            override fun liveBefore(b: NBId): Set<TACSymbol.Var> =
                lva.liveVariablesBefore(b)

            override fun interpret(state: SimpleQualifiedIntState, s: TACSymbol): SimpleQualifiedInt =
                state.interpret(s)

            override fun applyInvariants(state: SimpleQualifiedIntState, invariants: Map<TACSymbol.Var, SimpleQualifiedInt>): SimpleQualifiedIntState =
                SimpleQualifiedIntState(state.s.merge(invariants) { _, l, r -> r ?: l })
        }

    init {
        runAnalysis()
    }

    override fun step(cmd: LTACCmd, s: SimpleQualifiedIntState): SimpleQualifiedIntState? =
        try {
            interpreter.step(cmd, s)
        } catch (unreachable : UnreachableState) {
            logger.debug {
                "Unreachable found while stepping $cmd: $unreachable"
            }
            null
        }

    override fun propagate(
        cmd: LTACCmd,
        s: SimpleQualifiedIntState,
        pc: PathCondition
    ): SimpleQualifiedIntState? = interpreter.propagate(cmd, s, pc)
        ?.s
        ?.parallelUpdateValues { _, av -> av.reduce() }
        ?.let(::SimpleQualifiedIntState)

    override fun joinOp(
        pre: SimpleQualifiedIntState,
        new: SimpleQualifiedIntState,
        widen: Boolean
    ): SimpleQualifiedIntState = pre.join(new, widen)
        .s
        .retainAllValues { it != SimpleQualifiedInt.nondet }
        .let(::SimpleQualifiedIntState)

    override fun prepareBlockOut(s: SimpleQualifiedIntState): SimpleQualifiedIntState {
        return SimpleQualifiedIntState(s.s.retainAllValues { it != SimpleQualifiedInt.nondet })
    }
}
