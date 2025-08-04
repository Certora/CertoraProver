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

package analysis.numeric

import datastructures.stdcollections.*
import algorithms.SimpleDominanceAnalysis
import analysis.CmdPointer
import analysis.GenericTACCommandGraph
import analysis.GraphPathConditions
import analysis.LTACCmdGen
import analysis.PathCondition
import analysis.TACBlockGen
import analysis.dataflow.GenericLiveVariableAnalysis
import analysis.getNaturalLoopsGeneric
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import tac.NBId
import utils.lazy
import utils.parallelStream
import vc.data.TACCmd
import java.util.stream.Stream

/** A worklist-based interval analysis */
abstract class AbstractNaturalBlockScheduledAnalysis<W, T: TACCmd, U: LTACCmdGen<T>, V: TACBlockGen<T, U>, G>(
    private val graph: G,
) where G: GenericTACCommandGraph<T, U, V>,
        G: GraphPathConditions {

    abstract val dom: SimpleDominanceAnalysis<NBId>
    abstract val lva: GenericLiveVariableAnalysis<T, U, V, G>
    abstract val invariantHeuristic: LoopInvariantHeuristic<G, W>

    abstract fun step(cmd: U, s: W): W?
    abstract fun propagate(cmd: U, s: W, pc: PathCondition): W?
    abstract fun joinOp(pre: W, new: W, widen: Boolean): W
    abstract fun prepareBlockOut(s: W): W

    abstract val scheduler: IWorklistScheduler<NBId>
    abstract val initialState: W

    // Only store in/out states at the block level
    private val inState = mutableMapOf<NBId, W>()
    private val outState = mutableMapOf<NBId, W>()

    // Maps loop header |-> loop
    private val loopsByHead by lazy {
        getNaturalLoopsGeneric(graph, dom).groupBy { it.head }
    }

    /**
     * @return the state before executing the command at [ptr].
     *         If [ptr] is not reachable this returns null
     */
    fun inState(ptr: CmdPointer): W? {
        var st: W = inState[ptr.block] ?: return null
        for (cmd in graph.elab(ptr.block).commands) {
            if (cmd.ptr == ptr) {
                return st
            }
            st = step(cmd, st) ?: return null
        }
        throw IllegalArgumentException("No in state for $ptr")
    }

    fun parallelStreamStates(): Stream<Pair<CmdPointer, W>> {
        return inState.keys.parallelStream().flatMap { b ->
            inStates(b).parallelStream()
        }
    }

    private fun inStates(b: NBId): Sequence<Pair<CmdPointer, W>> = sequence {
        var theState: W? = inState[b]
        for (c in graph.elab(b).commands) {
            if (theState == null) {
                break
            }
            yield(c.ptr to theState)
            theState = step(c, theState)
        }
    }

    fun outState(ptr: CmdPointer): W? {
        var st: W = inState[ptr.block] ?: return null
        for (cmd in graph.elab(ptr.block).commands) {
            st = step(cmd, st) ?: return null
            if (cmd.ptr == ptr) {
                return st
            }
        }
        throw IllegalArgumentException("No out state for $ptr")
    }

    protected fun runAnalysis() {
        graph.rootBlocks.forEach {
            inState[it.id] = initialState
        }
        (object : StatefulWorklistIteration<NBId, Unit, Unit>() {
            override val scheduler: IWorklistScheduler<NBId> =
                this@AbstractNaturalBlockScheduledAnalysis.scheduler

            override fun reduce(results: List<Unit>) {}

            override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                return this.cont(iterBlock(it))
            }
        }).submit(graph.rootBlocks.map { it.id })
    }

    protected open fun stepBlock(inState: W, block: NBId): W? {
        val commands = graph.elab(block).commands

        var state = inState
        for (cmd in commands) {
            state = step(cmd, state) ?: return null
        }

        return state
    }

    private fun iterBlock(block: NBId): Set<NBId> {
        val state: W = inState[block] ?: return setOf()
        val next = mutableSetOf<NBId>()

        val blockOut = stepBlock(state, block) ?: return next
        outState[block] = prepareBlockOut(blockOut)

        for ((succ, cond) in graph.pathConditionsOf(block)) {
            val fst = graph.elab(succ).commands.last()

            // If this is null, then the path from [block] to [succ] is infeasible
            val propagated = propagate(fst, blockOut, cond) ?: continue

            // We need to guess (relational) invariants at loop headers.
            // One reason we need to do this is because for a loop that iterates from 0 to K,
            // we may have the condition (i != K). The invariant we need in this situation
            // is something like i <= K (it's actually more complicated, see [guessLoopInvariants],
            val nextWithGuessedInvariants = loopsByHead[succ]?.singleOrNull { block !in it.body }?.let { enteringLoop ->
                invariantHeuristic.guessLoopInvariants(graph, enteringLoop, propagated)
            } ?: propagated

            if (succ !in inState) {
                inState[succ] = nextWithGuessedInvariants
                next.add(succ)
            } else {
                val prevState = inState[succ]!!
                val isBackJump = loopsByHead[succ]?.any { block in it.body } == true

                val joined = joinOp(prevState, nextWithGuessedInvariants, widen = isBackJump)

                if (joined != prevState) {
                    inState[succ] = joined
                    next.add(succ)
                }
            }
        }

        return next
    }
}
