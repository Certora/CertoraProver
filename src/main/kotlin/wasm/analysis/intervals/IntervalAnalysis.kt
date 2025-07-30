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

import datastructures.stdcollections.*
import analysis.*
import analysis.numeric.SimpleIntQualifier
import analysis.numeric.SimpleQualifiedInt
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import com.certora.collect.*
import log.*
import tac.NBId
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Stream

private val logger = Logger(LoggerTypes.ABSTRACT_INTERPRETATION)

/** A worklist-based interval analysis */
class IntervalAnalysis private constructor(private val graph: TACCommandGraph) {
    companion object : AnalysisCache.Key<TACCommandGraph, IntervalAnalysis> {
        override fun createCached(graph: TACCommandGraph) = IntervalAnalysis(graph)
    }

    // Only store in/out states at the block level
    private val inState = mutableMapOf<NBId, State>()
    private val outState = mutableMapOf<NBId, State>()

    private val interpreter = IntervalInterpreter(graph)

    // Maps loop header |-> loop
    private val loopsByHead = getNaturalLoops(graph).groupBy { it.head }

    /**
     * @return the [State] before executing the command at [ptr].
     *         If [ptr] is not reachable this returns null
     */
    fun inState(ptr: CmdPointer): State? {
        var st = inState[ptr.block] ?: return null
        for (cmd in graph.elab(ptr.block).commands) {
            if (cmd.ptr == ptr) {
                return st
            }
            try {
                st = interpreter.step(cmd, st)
            } catch (_: UnreachableState) {
                return null
            }
        }
        throw IllegalArgumentException("No in state for $ptr")
    }

    fun parallelStreamStates(): Stream<Pair<CmdPointer, State>> {
        return inState.keys.parallelStream().flatMap { b ->
            inStates(b).parallelStream()
        }
    }

    private fun inStates(b: NBId): Sequence<Pair<CmdPointer, State>> = sequence {
        var theState: State? = inState[b]
        for (c in graph.elab(b).commands) {
            if (theState == null) {
                break
            }
            yield(c.ptr to theState)
            theState = stepOrNull(c, theState)
        }
    }

    private fun stepOrNull(lcmd: LTACCmd, state: State): State? =
        try {
            interpreter.step(lcmd, state)
        } catch (_ : UnreachableState) {
            null
        }

    fun outState(ptr: CmdPointer): State? {
        var st = inState[ptr.block] ?: return null
        for (cmd in graph.elab(ptr.block).commands) {
            try {
                st = interpreter.step(cmd, st)
            } catch (_ : UnreachableState) {
                return null
            }
            if (cmd.ptr == ptr) {
                return st
            }
        }
        throw IllegalArgumentException("No out state for $ptr")
    }

    init {
        graph.rootBlocks.forEach {
            inState[it.id] = treapMapOf()
        }
        (object : StatefulWorklistIteration<NBId, Unit, Unit>() {
            override val scheduler: IWorklistScheduler<NBId> = graph.cache.naturalBlockScheduler

            override fun reduce(results: List<Unit>) {}

            override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                return this.cont(iterBlock(it))
            }
        }).submit(graph.rootBlocks.map { it.id })
    }

    private fun iterBlock(block: NBId): Set<NBId> {
        var state = inState[block] ?: return setOf()
        val commands = graph.elab(block).commands
        val next = mutableSetOf<NBId>()

        for (cmd in commands) {
            try {
                state = interpreter.step(cmd, state)
            } catch (unreachable : UnreachableState) {
                logger.debug {
                    "Unreachable found while stepping $cmd: $unreachable"
                }
                return next
            }
        }

        // Avoid cluttering the state with nondets
        val stateNoNondet = state.retainAllValues { it != SimpleQualifiedInt.nondet }
        outState[block] = stateNoNondet

        for ((succ, cond) in graph.pathConditionsOf(block) ) {
            val fst = graph.elab(succ).commands.first()

            // If this is null, then the path from [block] to [succ] is infeasible
            val propagated = interpreter
                .propagate(fst, stateNoNondet, cond)
                ?.parallelUpdateValues { _, av -> av.meet() }
                ?: continue

            // We need to guess (relational) invariants at loop headers.
            // One reason we need to do this is because for a loop that iterates from 0 to K,
            // we may have the condition (i != K). The invariant we need in this situation
            // is something like i <= K (it's actually more complicated, see [guessLoopInvariants],
            val nextWithGuessedInvariants = loopsByHead[succ]?.singleOrNull { block !in it.body }?.let { enteringLoop ->
                guessLoopInvariants(enteringLoop, propagated)
            } ?: propagated

            if (succ !in inState) {
                inState[succ] = nextWithGuessedInvariants
                next.add(succ)
            } else {
                val prevState = inState[succ]!!
                val isBackJump = loopsByHead[succ]?.any { block in it.body } == true

                val joined = prevState
                    .join(nextWithGuessedInvariants, widen = isBackJump)
                    .retainAllValues { it != SimpleQualifiedInt.nondet }

                if (joined != prevState) {
                    inState[succ] = joined
                    next.add(succ)
                }
            }
        }

        return next
    }

    private fun State.join(other: State, widen: Boolean = false): State = this.parallelIntersect(other) { _, v1, v2 ->
        v1.join(v2, widen)
    }

    /**
     * Guess loop invariants of the form x : ModularUpperBound(y, modulus, strong)
     *
     * Generally if we have symbols: x, y, n that appear in [loop],
     *  and if in [state] we have that x <= y, we will guess x: ModularUpperBound(y, 1, x < y)
     *  additionally, if we know that y - x == n, we will guess x: ModularUpperBound(y, n, false)
     *  (the reason for 'false', even though y > x, is that typically this is too strong as a loop
     *  _invariant_ (but we'll recover the strong bound with the loop condition as is standard)
     */
    private fun guessLoopInvariants(loop: Loop, state: State): State {
        var st = state
        val liveBeforeHead = graph.cache.lva.liveVariablesBefore(loop.head)

        val syms = treapSetOf<TACSymbol>().mutate { syms ->
            for (b in loop.body) {
                graph.elab(b).commands.forEach {
                    it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.let { syms += it.getRhs() }
                    it.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.cmd?.let { syms += it.lhs }
                }
            }
        }

        val consts = syms.filterIsInstance<TACSymbol.Const>()

        val liveSyms = (syms intersect liveBeforeHead).uncheckedAs<TreapSet<TACSymbol.Var>>()

        liveSyms.forEachElement { s1 ->
            val q = mutableSetOf<SimpleIntQualifier.ModularUpperBound>()
            val v1 = IntervalInterpreter.interp(s1, state)
            syms.forEachElement { s2 ->
                if (s1 != s2) {
                    val v2 = IntervalInterpreter.interp(s2, state)
                    if (v1.x.ub <= v2.x.lb) {
                        q.add(SimpleIntQualifier.ModularUpperBound(s2, BigInteger.ONE, v1.x.ub < v2.x.lb))
                    }
                }
            }
            // We have a symbol s1 and its abstraction v1. If v1 is constant,
            // We can look for c1 and c2 s.t. v1 < c2 and c1 divides (c2 - v1).
            // Then c2 is a modular upper bound of v1 with modulus c1.
            if (v1.x.isConstant) {
                consts.forEachElement { c1 -> // c1 should divide the difference between c2 and v1
                    consts.forEachElement { c2 ->
                        if (c1.value != BigInteger.ZERO && v1.x.c <= c2.value && c1.value < c2.value) {
                            if ((c2.value - v1.x.c).rem(c1.value) == BigInteger.ZERO) {
                                q.add(SimpleIntQualifier.ModularUpperBound(c2, c1.value, false))
                            }
                        }
                    }
                }
            }
            st += s1 to v1.copy(qual = v1.qual + q)
        }
        return st
    }

}
