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

package verifier.equivalence.loops

import datastructures.stdcollections.*
import analysis.Loop
import analysis.PathCondition
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StepResult
import analysis.worklist.WorklistIteration
import com.certora.collect.*
import tac.NBId
import utils.*
import vc.data.*
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.data.tacexprutil.subs

private typealias SymbolicState = TreapMap<TACSymbol.Var, TACExpr>

/**
 * For a [loop] in [core] starting at [head], infer a [StateAndPC], representing the effect
 * of a single iteration of the loop. See [StateAndPC] for how to interpret the result.
 */
class LoopConditionExtractor private constructor(val core: CoreTACProgram, val loop: Loop, val head: NBId) {
    private val graph = core.analysisCache.graph

    /**
     * [state] is a mapping from a variable to an expression giving the value of the variable
     * after one iteration of the loop. This expression may be in terms of synthetic "pre" variables.
     * For example, if the loop body contains `i++` then [state] may contain `i => i!pre + 1`.
     *
     * [pc] is a set of expressions, all of which must be true for control-flow to start from [head]
     * and reach [head] again. These expressions may also use `pre` versions of variables.
     *
     * For example, if we have `while(i < 10) { i++ }` then pc will be `i!pre < 10`.
     * If, however, we had `do { i++ } while(i < 10)` then `pc` will be `i < 10` and `state` will
     * still be `i = i!pre + 1` (indicating backjump check happens after the increment).
     *
     * If there isn't a principle expression that can represent the value of a variable, we fall back on
     * the sound (but imprecise) [TACExpr.Unconstrained].
     */
    data class StateAndPC(
        val pc: TreapSet<TACExpr>,
        val state: SymbolicState
    )

    /**
     * Bijection between a loop variable and its distinguished "pre" version.
     */
    private val varToPreMap = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
    private val preToVarMap = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()

    /**
     * Gets the "pre" version of a loop variable [v].
     */
    private fun toLoopVar(v: TACSymbol.Var) : TACSymbol.Var {
        if(v in varToPreMap) {
            return varToPreMap[v]!!
        }
        val preVersion = v.copy(
            namePrefix = v.namePrefix + "!pre"
        )
        varToPreMap[v] = preVersion
        preToVarMap[preVersion] = v
        return preVersion
    }

    /**
     * Given a [st] which maps variables to their symbolic representation in terms of "pre" variables,
     * rewrite [e] to substitue these versions. For example, if we have `x => x!pre + 1` and `y => z!pre + 2`
     * then this will rewrite `x + y` to `x!pre + 1 + z!pre + 2`
     */
    private fun interpExp(e: TACExpr, st: SymbolicState) : TACExpr {
        return object : DefaultTACExprTransformer() {
            override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
                return st[exp.s] ?: toLoopVar(exp.s).asSym()
            }
        }.transform(e)
    }

    /**
     * Maps the start of the block in the [loop] to the symbolic state there.
     */
    private val preStates = treapMapBuilderOf<NBId, StateAndPC>()

    /**
     * Straightforward implementation of the symbolic forward analysis. Stepping an assignment `e` command
     * in state `st` is modeled with [interpExp]; everything else is modeled with an imprecise [TACExpr.Unconstrained].
     *
     * At jumps within the loop, simply updates the [StateAndPC.pc]; however, when traversing a backjump, records the current
     * [StateAndPC] for reduction later to infer the upper bound of the state in all backjumps.
     */
    private fun translateBlock(it: NBId) : StepResult<NBId, StateAndPC, StateAndPC?> {
        val (pc, state) = preStates[it]!!

        var stateIt = state

        val pcIt = pc.builder()

        for(lc in graph.elab(it).commands) {
            when(lc.cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    stateIt += (lc.cmd.lhs to interpExp(lc.cmd.rhs, stateIt))
                }
                is TACCmd.Simple.AssigningCmd -> {
                    stateIt += (lc.cmd.lhs to TACExpr.Unconstrained(lc.cmd.lhs.tag))
                }
                is TACCmd.Simple.ReturnCmd,
                is TACCmd.Simple.ReturnSymCmd,
                is TACCmd.Simple.RevertCmd,
                is TACCmd.Simple.SummaryCmd,
                is TACCmd.Simple.WordStore,
                is TACCmd.Simple.LabelCmd,
                is TACCmd.Simple.LogCmd,
                TACCmd.Simple.NopCmd,
                is TACCmd.Simple.JumpCmd,
                is TACCmd.Simple.JumpdestCmd,
                is TACCmd.Simple.JumpiCmd,
                is TACCmd.Simple.AnnotationCmd -> {
                    // do nothing
                }
                is TACCmd.Simple.AssertCmd -> {
                    val which = interpExp(lc.cmd.o.asSym(), stateIt)
                    pcIt.add(which)
                }
                is TACCmd.Simple.AssumeNotCmd -> {
                    val which = interpExp(lc.cmd.condExpr, stateIt).let {
                        TACExpr.UnaryExp.LNot(it)
                    }
                    pcIt.add(which)
                }
                is TACCmd.Simple.AssumeCmd -> {
                    val which = interpExp(lc.cmd.condExpr, stateIt)
                    pcIt.add(which)
                }
                is TACCmd.Simple.AssumeExpCmd -> `impossible!`
                is TACCmd.Simple.ByteLongCopy,
                is TACCmd.Simple.CallCore -> {

                }
            }
        }

        val toQueue = mutableListOf<Pair<NBId, StateAndPC>>()
        val res = mutableListOf<StateAndPC>()

        for((nxt, nxtPC) in graph.pathConditionsOf(it)) {
            if(nxt !in loop.body) {
                continue
            }
            when(nxtPC) {
                is PathCondition.EqZero -> {
                    val cond = interpExp(nxtPC.v.asSym(), stateIt).let {
                        TACExpr.UnaryExp.LNot(it)
                    }
                    toQueue.add(nxt to StateAndPC(pcIt.build() + cond, stateIt))
                }
                is PathCondition.NonZero -> {
                    val cond = interpExp(nxtPC.v.asSym(), stateIt)
                    toQueue.add(nxt to StateAndPC(pcIt.build() + cond, stateIt))
                }
                is PathCondition.Summary -> {
                    /**
                     * Take the summary blocks.
                     */
                    if(nxt == nxtPC.s.originalBlockStart) {
                        toQueue.add(nxt to StateAndPC(pcIt.build(), stateIt))
                    }
                }
                PathCondition.TRUE -> {
                    toQueue.add(nxt to StateAndPC(pcIt.build(), stateIt))
                }
            }
        }

        val nextItem = mutableListOf<NBId>()
        for((nxtBlock, newState) in toQueue) {
            /**
             * Res is the "state we reached a backjump in" so record that here, we take the upper
             * bound of all such states in [analyze]
             */
            if(nxtBlock == head) {
                res.add(newState)
                continue
            }
            /**
             * Otherwise ye olde merge and re-queue
             */
            val curr = preStates[nxtBlock]
            if(curr == null) {
                preStates[nxtBlock] = newState
                nextItem.add(nxtBlock)
                continue
            }
            val merged = curr merge newState
            if(merged != curr) {
                preStates[nxtBlock] = merged
                nextItem.add(nxtBlock)
            }
        }
        return StepResult.Ok(
            next = nextItem,
            result = res
        )
    }

    /**
     * Upper bound operation. [StateAndPC] is a must fact, and so we use intersection on the path conditions.
     * Similarly, if there is disagreement about the symbolic value of some variable, we fallback on [TACExpr.Unconstrained]
     * (we make no real attempt to canonicalize representations either).
     */
    private infix fun StateAndPC.merge(newState: StateAndPC) = StateAndPC(
        pc = this.pc intersect newState.pc,
        state = this.state.merge(newState.state) { k, v1, v2 ->
            if(v1 == null || v2 == null || v1 != v2) {
                TACExpr.Unconstrained(k.tag)
            } else {
                v2
            }
        }
    )

    companion object {
        fun analyzeLoop(
            core: CoreTACProgram,
            theLoop: Loop,
            head: NBId = theLoop.head
        ) : StateAndPC? = LoopConditionExtractor(
            core, theLoop, head
        ).analyze()
    }

    /**
     * Find the [StateAndPC] that over-approximates the state of loop variables at every backjump.
     *
     * The returned [StateAndPC] is guaranteed to only reference variables whose definitions do not involve
     * [TACExpr.Unconstrained].
     *
     * If, after the above filtering, the PC is empty, this returs null.
     */
    fun analyze(): StateAndPC? {
        val worker = object : WorklistIteration<NBId, StateAndPC, StateAndPC?>() {
            override val scheduler: IWorklistScheduler<NBId> = graph.cache.naturalBlockScheduler
            override fun reduce(results: List<StateAndPC>): StateAndPC? {
                return results.reduceOrNull { state1, state2 ->
                    state1 merge state2
                }
            }

            override fun process(it: NBId): StepResult<NBId, StateAndPC, StateAndPC?> {
                return translateBlock(it)
            }
        }
        preStates[head] = StateAndPC(treapSetOf(), treapMapOf())
        val (backjumpPC_, backjumpState_) = worker.submit(listOf(head)) ?: return null
        val backJumpPC = backjumpPC_.retainAll { e ->
            e.subs.none { pcSub ->
                pcSub is TACExpr.Unconstrained
            }
        }.takeIf { it.isNotEmpty() } ?: return null
        val backjumpState = backjumpState_.retainAll { (_, e) ->
            e.subs.none { sub ->
                sub is TACExpr.Unconstrained
            }
        }
        return StateAndPC(pc = backJumpPC, state = backjumpState)

    }
}
