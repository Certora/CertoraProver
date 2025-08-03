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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package move.analysis

import analysis.PathCondition
import analysis.numeric.AbstractNaturalBlockScheduledAnalysis
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntLoopInvariants
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import com.certora.collect.treapMapOf
import move.MoveTACCommandGraph
import move.MoveTACProgram
import vc.data.TACCmd
import tac.NBId
import vc.data.TACSymbol

class MoveIntervalAnalysis(graph: MoveTACCommandGraph)
    : AbstractNaturalBlockScheduledAnalysis<MoveState, TACCmd, MoveTACProgram.LCmd, MoveTACProgram.Block, MoveTACCommandGraph>(graph) {
    override val dom = graph.cache.domination
    override val lva = graph.cache.lva
    override val scheduler = graph.cache.naturalBlockScheduler
    override val initialState = MoveState(SimpleQualifiedIntState(treapMapOf()), treapMapOf())

    override val invariantHeuristic = object : SimpleQualifiedIntLoopInvariants<TACCmd, MoveTACProgram.LCmd, MoveTACProgram.Block, MoveTACCommandGraph, MoveState> {
        override fun liveBefore(b: NBId): Set<TACSymbol.Var> =
            lva.liveVariablesBefore(b)

        override fun interpret(
            state: MoveState,
            s: TACSymbol
        ): SimpleQualifiedInt = state.intState.interpret(s)

        override fun applyInvariants(
            state: MoveState,
            invariants: Map<TACSymbol.Var, SimpleQualifiedInt>
        ): MoveState = state.copy(
            SimpleQualifiedIntState(state.intState.s.merge(invariants) { _, l, r -> r ?: l })
        )
    }

    private val interpreter = MoveIntervalInterpreter(graph)

    init {
        runAnalysis()
    }

    override fun step(cmd: MoveTACProgram.LCmd, s: MoveState): MoveState? =
        interpreter.step(cmd, s)

    override fun prepareBlockOut(s: MoveState): MoveState {
        return s
    }

    override fun propagate(
        cmd: MoveTACProgram.LCmd,
        s: MoveState,
        pc: PathCondition
    ): MoveState? = interpreter.propagate(cmd, s, pc)?.let {
        it.copy(
            intState = SimpleQualifiedIntState(it.intState.s.parallelUpdateValues { _, av -> av.reduce() })
        )
    }

    override fun joinOp(
        pre: MoveState,
        new: MoveState,
        widen: Boolean
    ): MoveState = pre.join(new, widen)
}
