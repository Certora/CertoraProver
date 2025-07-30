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

package analysis.controlflow

import algorithms.dominates
import analysis.*
import analysis.smtblaster.IBlaster
import analysis.smtblaster.Z3Blaster
import analysis.worklist.StepResult
import analysis.worklist.WorklistIteration
import datastructures.stdcollections.*
import log.*
import parallel.*
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler.complete
import parallel.Scheduler.rpc
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssumeCmd
import vc.data.TACCmd.Simple.AssumeNotCmd
import vc.data.tacexprutil.TACExprFreeVarsCollector
import vc.data.tacexprutil.TACExprUtils
import vc.data.tacexprutil.subs
import vc.data.tacexprutil.toConstSet
import java.math.BigInteger
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.PRUNING)

/**
 * This analysis detects branches that can be pruned based on assume commands
 * and trivial assignments.
 */
object InfeasiblePathAnalysis {
    private class Worker(private val g: TACCommandGraph, private val blaster: IBlaster) {
        // We work on the program's assume commands that use a variable
        private val work = g.commands.parallelStream().filter {
            (it.cmd is AssumeCmd && it.cmd.cond is TACSymbol.Var) ||
                    (it.cmd is AssumeNotCmd && it.cmd.cond is TACSymbol.Var)
        }.map {
            when (it.cmd) {
                is AssumeCmd -> it.cmd.cond
                is AssumeNotCmd -> it.cmd.cond
                else -> throw IllegalStateException("Only assume cmds can be pruned")
            }.let { cond ->
                Pair(it,cond as TACSymbol.Var)
            }
        }.collect(Collectors.toList())

        val mca = MustBeConstantAnalysis(
            g,
            NonTrivialDefAnalysis(g)
        )

        fun canTraverseCommand(l: LTACCmd) : Boolean = TACMeta.CVL_USER_DEFINED_ASSERT !in l.cmd.meta
        fun canTraverseBlock(b: NBId) = g.elab(b).commands.all { canTraverseCommand(it) }

        fun doAnalysis(): Parallel<List<PruneNode>> {
            return work.forkEvery { (assumeCmd, condVar) ->
                val conditionGen =
                    if (assumeCmd.cmd is AssumeCmd) {
                        // for assume commands, we return a generator that compares an expression to zero (i.e. checks if it is false)
                        { expr: TACExpr ->
                            TACExpr.BinRel.Eq(
                                expr,
                                TACSymbol.lift(0).asSym()
                            )
                        }
                    } else {
                        // an AssumeNot command. For those, we return a generator that compares an expression to non-zero (i.e. checks if it is true)
                        { expr: TACExpr ->
                            TACExpr.UnaryExp.LNot(
                                TACExpr.BinRel.Eq(
                                    expr,
                                    TACSymbol.lift(0).asSym()
                                )
                            )
                        }
                    }
                // we check conclusions from an assume recursively
                val isAssumeFalseTask = Scheduler.fork {
                    val constCond = if(assumeCmd.cmd is AssumeCmd) {
                        mca.mustBeConstantAt(where = assumeCmd.ptr, v = assumeCmd.cmd.cond) == BigInteger.ZERO
                    } else {
                        check(assumeCmd.cmd is AssumeNotCmd)
                        mca.mustBeConstantAt(where = assumeCmd.ptr, v = assumeCmd.cmd.cond)?.let {
                            it != BigInteger.ZERO
                        } ?: false
                    }
                    val containingBlock = g.elab(assumeCmd.ptr.block)
                    val hasNoAssertInPrefix = (0 until assumeCmd.ptr.pos).all {
                        canTraverseCommand(containingBlock.commands[it])
                    }
                    complete(constCond && hasNoAssertInPrefix)
                }
                pruneTransitive(assumeCmd, condVar, conditionGen).parallelBind(isAssumeFalseTask) { g, isAssumeFalse ->
                    if(!isAssumeFalse) {
                        complete(g)
                    } else {
                        complete(g + PrunedBlock(assumeCmd.ptr.block))
                    }
                }
            }.pcompute().bind { listOfListsPruned ->
                saturate(listOfListsPruned.flatten())
            }.map {
                it.flatMap {
                    when(it) {
                        is PrunedBranch -> listOf<PruneNode>(it)
                        is PrunedBlock -> g.pred(it.prunedBlock).mapNotNull { pred ->
                            val pc = g.pathConditionsOf(pred)[it.prunedBlock]!!
                            if(pc is PathCondition.EqZero || pc is PathCondition.NonZero) {
                                PrunedBranch(
                                    conditionPtr = g.elab(pred).commands.last().ptr,
                                    infeasibleCondition = pc,
                                    targetBranch = it.prunedBlock
                                )
                            } else {
                                null
                            }
                        } + listOf(it)
                    }
                }
            }
        }

        private fun saturate(currentlyPruned: List<PruneNode>) : Parallel<List<PruneNode>> {
            val toSaturate = mutableSetOf<NBId>()
            val pruned = mutableSetOf<NBId>()
            currentlyPruned.forEach {
                when (it) {
                    is PrunedBlock -> {
                        pruned.add(it.prunedBlock)
                        toSaturate.addAll(g.pred(it.prunedBlock))
                    }

                    is PrunedBranch -> {
                        pruned.add(it.targetBranch)
                        toSaturate.addAll(g.pred(it.targetBranch))
                    }
                }
            }
            /**
             * Transitively computes the set of blocks that can be pruned because all their successor blocks can
             * be pruned. This proceeds transitively, and when the system stabilizes, `saturated` holds the set of newly
             * discovered pruned blocks.
             */
            val saturated = object : WorklistIteration<NBId, NBId, Set<NBId>>() {
                override fun process(it: NBId): StepResult<NBId, NBId, Set<NBId>> {
                    if(g.succ(it).all {
                        it in pruned
                    } && canTraverseBlock(it)) {
                        if(pruned.add(it)) {
                            return StepResult.Ok(
                                next = g.pred(it),
                                result = listOf(it)
                            )
                        }
                    }
                    return this.cont(listOf())
                }

                override fun reduce(results: List<NBId>) : Set<NBId> {
                    return results.toSet()
                }
            }.submit(toSaturate)
            /**
             * For each new block we can prune
             */
            return saturated.flatMap { newBlock ->
                val preds = g.pred(newBlock)
                /**
                 * Is this block reachable on a conditional branch from a non-pruned block, i.e.
                 * is one of the predecessors not in the pruned set? If so, we can prune this conditional branch, and potentially
                 * prune more branches from the (now infeasible) branch condition.
                 */
                preds.mapNotNull { pred ->
                    pred.takeIf {
                        pred !in pruned && g.succ(pred).size > 1
                    }?.let {
                        Scheduler.fork {
                            tryPruneConditional(it, g.pathConditionsOf(it)[newBlock]!!)
                        }
                    }
                }
            }.pcompute().bind { newConditionalPruning ->
                val combined = newConditionalPruning.flatten() + currentlyPruned + saturated.map {
                    PrunedBlock(it)
                }
                /**
                 * If we pruned even more from the conditional steps above, resaturate.
                 */
                if(newConditionalPruning.isNotEmpty()) {
                    // resaturate!
                    saturate(combined)
                } else {
                    complete(combined)
                }
            }
        }

        /**
         * This recursive method infers all the branches that can be pruned as a result of [assumeCmd] holding.
         * The assume is on a condition variable [condVar].
         * [conditionGen] helps check if the assume is valid or not.
         * Note that when calling transitively, an assume command may actually be a conditional jump, of which
         * one of the branch choices was pruned.
         */
        private fun pruneTransitive(assumeCmd: LTACCmd, condVar: TACSymbol.Var, conditionGen: (TACExpr) -> TACExpr) : Parallel<List<PruneNode>> {
            // we first attempt to get a defining equation for the condition, starting from the location of the assume command,
            // and the defining equation is in terms of variables at the beginning of the block containing the assume
            return DefiningEquationAnalysis.getDefiningEquation(
                    g = g,
                    where = assumeCmd.ptr,
                    target = g.elab(assumeCmd.ptr.block).commands.first().ptr,
                    v = condVar
            )?.let { expr ->
                // we assume the defining equation of the condition contains only one free variable
                val freeVar = TACExprFreeVarsCollector.getFreeVars(expr).singleOrNull() ?: return@let null
                // we collect all constants that appear in the defining equation
                val consts = expr.subs.toConstSet().mapToSet { it.value }

                if(freeVar.tag == Tag.Bool && consts.isEmpty()) {
                    val cond = conditionGen(expr)
                    // this is kind of a hack! let's try to find the constant valuation of `freeVar` that evaluates to false
                    return@let listOf(TACSymbol.True, TACSymbol.False).singleOrNull {
                        TACExprUtils.Substitutor(mapOf(freeVar.asSym() to it.asSym())).transform(cond).evalAsConst() != BigInteger.ZERO
                    }?.let {
                        findImpossibleBranches(
                            freeVar, it.value, assumeCmd.ptr.block
                        )
                    }
                }

                // note that the defining equation can be either a simple assignment x = c, but it can also be something more elaborate
                // for us, it does not matter. We only use the constants to improve our guess...
                // and the guess is regarding whether our free variable, if equal to some constant, means the assume does not hold.
                // If that is a valid statement, then if the free variable gets assigned this constant, then on this path
                // the assume is invalid and thus the assignment is infeasible.
                // for example: if we assume !lastReverted, in any revert path we will see an assignment lastReverted = true,
                // which is by this logic infeasible.
                // here we check which of our guessed constants get the assume expression to be false.
                consts.map { const ->
                    rpc {
                        LogicalEquivalence.equiv(
                                listOf(),
                                conditionGen(expr),
                                TACExpr.BinRel.Eq(
                                        freeVar.asSym(),
                                        const.asTACSymbol(Tag.Int).asSym()
                                ),
                                blaster
                        )
                    }.bindFalseAsNull {
                        complete(const)
                    }
                }.pcompute().map {
                    // there can be at most one such constant
                    it.mapNotNull { it }.singleOrNull()
                }.maybeBind {
                    // for the constant that makes the freeVar invalidate the assume, we check if we can find such assignments
                    // that can be pruned. Note we need to look such assignments up from before the point where we
                    // defined the condition
                    findImpossibleBranches(freeVar, it, assumeCmd.ptr.block)
                }.map {
                    it ?: listOf()
                }
            } ?: complete(listOf())
        }

        private val nonTrivialDefAnalysis = NonTrivialDefAnalysis(g)

        /**
         * Given a variable [someVar], we check whether there's an assignment of pruned value [mustNotEqual] to it
         * before [where].
         * I.e., if at [where] we know that [someVar] must not equal [mustNotEqual], then the command pointer of the definition
         * assigning [mustNotEqual] to [someVar] is also infeasible.
         */
        private fun findImpossibleBranches(someVar: TACSymbol.Var, mustNotEqual: BigInteger, where: NBId): Parallel<List<PruneNode>> {
            val blockStart = g.elab(where).commands.first()
            val defs = nonTrivialDefAnalysis.nontrivialDef(someVar, blockStart.ptr).map(g::elab)
            return defs.mapNotNull {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
            }.filter {
                it.cmd.rhs is TACExpr.Sym.Const
            }.filter {
                it.wrapped.enarrow<TACExpr.Sym.Const>().exp.s.value == mustNotEqual
            }.map { infeasibleAssign ->
                /**
                 * Then someVar MUST end up holding the value which is infeasible.
                 */
                val isDominatingMustAssignment = defs.size == 1 && g.cache.domination.dominates(infeasibleAssign.ptr, blockStart.ptr)
                logger.debug { "Def of variable $someVar (used in ${where}) in $infeasibleAssign is equal to $mustNotEqual which it must not be equal to, pruning" }
                traverseTo(TraversalStart.BlockStart(
                    where
                ), traceMode = if(isDominatingMustAssignment) {
                    TraceMode.TO_DOMINATING_ASSIGN
                } else {
                    TraceMode.TO_INITIAL_ASSIGN
                }, infeasibleAssign = infeasibleAssign.wrapped.enarrow(), toTrace = someVar).map {
                    if(isDominatingMustAssignment) {
                        it + PrunedBlock(where)
                    } else {
                        it
                    }
                }
            }.pcompute().map {
                it.flatten()
            }
        }

        private enum class TraceMode {
            TO_DOMINATING_ASSIGN,
            TO_CONDITIONALLY_REACHED_DOMINATING_ASSIGN,
            TO_INITIAL_ASSIGN
        }

        /**
         * [toStart] is known to be an infeasible block (because, e.g., it contains an assignment that then flows
         * unconditionally to an assume that states that assignment is impossible). Then, we try to find if [toStart]
         * itself can be unconditionally reached by a conditional branch. If so, then the conditional branch is also infeasible:
         * taking that branch would mean we reach a block we have shown cannot happen. This function tries to trace
         * back to such a conditional parent. Here, we overapproximate "unconditional" by "traversing singleton
         * "predecessor relations where the path condition is true". Thus, that the current implementation is
         * deficient, in that it is confused by "pointless" diamonds, e.g.
         *
         * ```
         * if(*) {
         *    if(*) {
         *       // ...
         *    } else {
         *       // ...
         *    }
         *    assume false
         * } else {
         *    ...
         * }
         * ```
         * This will see two predecessors of the "assume false" and refuse to proceed. We don't actually leave this optimization on the
         * floor, the later passes in [saturate] will eventually prune the true branch of the top most conditional.
         * In that sense, this function is purely an optimization that tries to eagerly prune conditional parents where possible.
         */
        private fun tryPruneConditionalParent(
            toStart: NBId
        ) : Parallel<List<PruneNode>> {
            return Scheduler.fork {
                var curr = toStart
                while (canTraverseBlock(curr)) {
                    val pred = g.pred(curr)
                    if (pred.size != 1) {
                        return@fork complete(listOf())
                    }
                    val solePred = pred.single()
                    val pc = g.pathConditionsOf(solePred)[curr]!!
                    if(pc == PathCondition.TRUE) {
                        curr = solePred
                    } else {
                        return@fork tryPruneConditional(
                            solePred, pc
                        )
                    }
                }
                complete(listOf())
            }
        }

        private sealed class TraversalStart {
            data class BlockStart(val blockId: NBId) : TraversalStart()
            data class BlockEnd(val blockId: NBId) : TraversalStart()
        }

        /**
         * Handle the current predecessors [preds] of [curr] depending on the [traceMode].
         * If the path condition is [analysis.PathCondition.TRUE], then
         * the path from a predecessor p to [curr] is unconditional, and should be followed even in [TraceMode.TO_INITIAL_ASSIGN];
         * "by induction" if the path found from `p` to [infeasibleAssign] is unconditional, then the path from [infeasibleAssign]
         * to [curr] must also be unconditional.
         *
         * Otherwise, the path from `p` is conditional, in which case we can only proceed if the [infeasibleAssign] is a dominating assignment.
         * In this case, we can also prune the conditional path that takes us from `p` to [curr].
         */
        private fun handleMultiPredecessor(
            curr: NBId,
            preds: Set<NBId>,
            traceMode: TraceMode,
            tracingVar: TACSymbol.Var,
            infeasibleAssign: ExprView<TACExpr.Sym.Const>
        ) : Parallel<List<PruneNode>> {
            return preds.forkEvery { pred ->
                val pc = g.pathConditionsOf(pred)[curr]!!
                if(pc == PathCondition.TRUE) {
                    traverseTo(
                        pred, infeasibleAssign, tracingVar, traceMode
                    )
                /**
                 *  Then we can keep tracing to our parent, and try to prune the conditional branch being taken here as well (the parallel bind)
                 */
                } else if(traceMode == TraceMode.TO_DOMINATING_ASSIGN || traceMode == TraceMode.TO_CONDITIONALLY_REACHED_DOMINATING_ASSIGN) {
                    traverseTo(
                        pred, infeasibleAssign, tracingVar, TraceMode.TO_CONDITIONALLY_REACHED_DOMINATING_ASSIGN
                    ).parallelBind(tryPruneConditional(pred, pc)) { a, b -> complete(a + b) }
                } else {
                    complete(listOf())
                }
            }.pcompute().map {
                it.flatten()
            }
        }

        /**
         * Starting from [start], traverse back until [infeasibleAssign], which we know
         * will give [toTrace] an assignment that is infeasible. The [traceMode] determines what we can prune along the
         * way, and whether the infeasible assignment itself can be pruned (see the other implementation of
         * [traverseTo] for details)
         */
        private fun traverseTo(
            start: TraversalStart,
            infeasibleAssign: ExprView<TACExpr.Sym.Const>,
            toTrace: TACSymbol.Var,
            traceMode: TraceMode
        ) : Parallel<List<PruneNode>> {
            return when (start) {
                /**
                 * Then start traversing from the end of block id
                 */
                is TraversalStart.BlockEnd -> {
                    return traverseTo(
                        start.blockId, infeasibleAssign, toTrace, traceMode
                    )
                }
                /**
                 * Otherwise, start traversing from the precedessors of the current block.
                 */
                is TraversalStart.BlockStart -> {
                    handleMultiPredecessor(
                        start.blockId, g.pred(start.blockId), traceMode, tracingVar = toTrace, infeasibleAssign = infeasibleAssign
                    )
                }
            }
        }

        /**
         * Starting from the last command in [start], trace backwards until we reach
         * [infeasibleAssign]. From the (assumed) soundness of the def analysis that tells us
         * [toTrace] can ultimately hold a constant value `k` assigned in [infeasibleAssign], AND that
         * we know [toTrace] having that value is infeasible (because, e.g., we explicitly assume later that `toTrace != k`)
         * we can prune branches/blocks encountered during tracing.
         *
         * The [traceMode] tells us exactly what we can prune and when. If [traceMode] is [TraceMode.TO_INITIAL_ASSIGN],
         * then it means we are tracing back to an assignment which is *not* a definite assignment for [toTrace].
         * Thus, the path from [start] to [infeasibleAssign] must be unconditional; if it is not unconditional, then we don't know that
         * [infeasibleAssign] is prunable. For example, consider the following:
         *
         * ```
         * if(*) {
         *    x := k
         *  } else {
         *    x := j
         *  }
         *  assume x != k;
         * ```
         *
         * In the above, the path from `x := k` to `assume x != k` is unconditional (of course, the path to `x := k` from
         * the root of the program is conditional, that's fine). Thus, we know that the `x := k` assignment is infeasible;
         * if it is executed, then we know that [toTrace] will ultimately hold a constant value (in this case, k) at [start]
         * which is impossible.
         *
         * Consider now if the path was conditional, as in the following:
         *
         * ```
         * if(*) {
         *   x := k
         *   if(*) {
         *     x := c
         *   }
         * } else {
         *    x := j
         * }
         * assume x != k;
         * ```
         *
         * Then we can't (definitively) say the `x := k` assignment can't happen, it may (or may not) be overwritten
         * by `x := c`. (A more powerful analysis could, e.g., conclude that the condition that gates the assignment
         * `x := c` MUST occur, but this is out of reach for now).
         *
         * In other words, [TraceMode.TO_INITIAL_ASSIGN] means that one of the assignments for [toTrace] at [start] could
         * be [infeasibleAssign], which is impossible. If we can show that [infeasibleAssign] must flow to start without
         * any intervening writes, we can prune [infeasibleAssign].
         *
         * That's [TraceMode.TO_INITIAL_ASSIGN], what about the other cases? [TraceMode.TO_DOMINATING_ASSIGN] means that
         * we are tracing back to an assignment that MUST occur, i.e., [infeasibleAssign] is the single, definite assignment
         * that gives the value of [toTrace] at [start] (and we know this assignment should be impossible).
         *
         * Then, any branches that occur along the path from [infeasibleAssign] to [start] should be pruned. For example:
         * ```
         * x := k
         * if(y == z) {
         *   if(i == j) {
         *      assume x != k
         *   }
         * }
         * ```
         * We must have that `x != k`, but x MUST equal k if we have that `y == z` and `i == j`. Thus, these branches cannot
         * be true, and we can thus prune those branches. Note that we cannot do a similar optimization for the [TraceMode.TO_DOMINATING_ASSIGN],
         * because we aren't sure that x must be `k` along the path we are tracing (although an extension of this analysis **could**
         * find a point at which the infeasible assignment becomes a must assignment, and then begins pruning branches).
         *
         * [TraceMode.TO_CONDITIONALLY_REACHED_DOMINATING_ASSIGN] means that we are tracing a path to a dominating, must
         * assignment which has traversed one or more conditional branches. If we reach the infeasible assignment in
         * this mode, we *cannot* prune the infeasible assignment itself: we cannot say the assignment definitely can't happen,
         * merely the conditional branches that lead from the assignment to [start] that were encountered along the trace.
         * In our example above, we will reach `x := k` in this tracing mode, indicating that `x := k` is still feasible,
         * and cannot be pruned: it is only infeasible if `y == z` and `i == j` are ALSO true, which is precisely why
         * we prune those branches.
         *
         * Note that we can still prune conditional branches encountered along the trace to [infeasibleAssign] in this mode, it simply
         * indicates we can't continue pruning once we reach [infeasibleAssign].
         */
        private fun traverseTo(
            start: NBId,
            infeasibleAssign: ExprView<TACExpr.Sym.Const>,
            toTrace: TACSymbol.Var,
            traceMode: TraceMode
        ) : Parallel<List<PruneNode>> {
            return Scheduler.fork {
                var curr = start
                var tracingVar = toTrace
                fun blockPrefixIsPrunable(i: Int) = if(i == 0) {
                    true
                } else {
                    val currBlock = g.elab(curr).commands
                    (0 until i).all {
                        canTraverseCommand(currBlock[it])
                    }
                }
                while(true) {
                    /**
                     * Trace backwards through the current block
                     */
                    val blockCmds = g.elab(curr)
                    var cmdIt = blockCmds.commands.lastIndex
                    while(cmdIt >= 0) {
                        val lc = blockCmds.commands[cmdIt]
                        if(!canTraverseCommand(lc)) {
                            return@fork complete(listOf())
                        }
                        /**
                         * If our lhs is the variable we are tracing, first check if this is the infeasible assignment in question
                         */
                        if(lc.cmd.getLhs() == tracingVar) {
                            if(lc.ptr == infeasibleAssign.ptr) {
                                /**
                                 * If it is, see if we can prune this assignment (via [tryPruneConditionalParent]). This is
                                 * only possible if we reached here in the trace modes [TraceMode.TO_INITIAL_ASSIGN]
                                 * or [TraceMode.TO_DOMINATING_ASSIGN] for the reasons described above: it means we reached
                                 * this assignment without any conditional branching, so this assignment is a must assignment
                                 * on the path from [start] to [infeasibleAssign].
                                 */
                                if(traceMode == TraceMode.TO_INITIAL_ASSIGN || traceMode == TraceMode.TO_DOMINATING_ASSIGN) {
                                    if(!blockPrefixIsPrunable(cmdIt)) {
                                        return@fork complete(listOf())
                                    }
                                    return@fork tryPruneConditionalParent(curr).bind {
                                        /**
                                         * In addition to whatever results come from the parent, this assignment is itself
                                         * infeasible, so mark this (for saturation later)
                                         */
                                        complete(it + PrunedBlock(curr))
                                    }
                                } else {
                                    return@fork complete(listOf())
                                }
                            /**
                             * Then this is the wrong path: we know that [infeasibleAssign] gives the valuation to `tracingVar`
                             * along trivial def assignments (i.e., where the RHS is a plain variable). This assignment to `tracingVar`
                             * wasn't the infeasible (constant) assignment, and its not a variable, so this is not part of the
                             * assignment chain discovered by the [NonTrivialDefAnalysis].
                             */
                            } else if(lc.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || lc.cmd.rhs !is TACExpr.Sym.Var) {
                                break
                            /**
                               we are now on the wrong path, there is no way we can reach the assignment we want to prune
                               from the variable we assigning from
                             */
                            } else if(nonTrivialDefAnalysis.nontrivialDef(lc.cmd.rhs.s, lc.ptr).none { it == infeasibleAssign.ptr }) {
                                break
                            } else {
                                /**
                                 * Otherwise, this is part of the chain we are tracing, update the tracing var as appropriate.
                                 */
                                tracingVar = lc.cmd.rhs.s
                            }
                        }
                        cmdIt--
                    }
                    val preds = g.pred(curr)
                    /**
                     * We hit the actual root of the program. This is very strange, how did we reach the root without seeing the assignment? We refuse to act on it as its
                     * very sus.
                     */
                    if(preds.isEmpty()) {
                        logger.warn {
                            "Hit the root of the control flow graph ${g.name} while tracing assignment for $tracingVar"
                        }
                        // ???
                        break
                    /**
                     * Unconditinal jump from a single predecessor, no need to recurse, just loop
                     */
                    } else if(preds.size == 1 && g.pathConditionsOf(preds.single())[curr] == PathCondition.TRUE) {
                        curr = preds.single()
                        continue
                    } else {
                        /**
                         * Otherwise, handle the predecessors based on the current tracing mode
                         */
                        return@fork handleMultiPredecessor(
                            preds = preds,
                            tracingVar = tracingVar,
                            curr = curr,
                            infeasibleAssign = infeasibleAssign,
                            traceMode = traceMode
                        )
                    }
                }
                complete(listOf())
            }
        }

        /**
         * Given a node [pred] which *cannot* take the condition [pathCond], try to prune assignments
         * that make that path condition true.
         */
        private fun tryPruneConditional(
            pred: NBId,
            pathCond: PathCondition
        ): Parallel<List<PruneNode>> {
            val last = g.elab(pred).commands.last()
            /**
             * If the path condition we know we cannot take is x == 0, then we need to prune assignments that make
             * `x == 0`.
             */
            return if(pathCond is PathCondition.EqZero) {
                pruneTransitive(last, pathCond.v) {
                    TACExpr.BinRel.Eq(it, TACSymbol.lift(0).asSym())
                }
            } else if(pathCond is PathCondition.NonZero) {
                /**
                 * Similarly, if this path condition (which we cannot take) is x != 0, find assignments
                 * that make `x != 0` true.
                 */
                pruneTransitive(last, pathCond.v) {
                    TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(it, TACSymbol.lift(0).asSym()))
                }
            } else {
                check(pathCond is PathCondition.Summary) {
                    "unexpected pathCond $pathCond for pruning conditional"
                }
                complete(listOf())
            }
        }
    }

    sealed class PruneNode

    /**
     * A class holding information identifying a pruned branch:
     * - [infeasibleCondition] The infeasible condition, i.e. a path condition that cannot be true, e.g. due to a later assume to the contrary
     * - [conditionPtr] The command pointer holding the path condition (a conditional jump)
     * - [prunedBlocks] A set of blocks that can be removed from the graph (i.e. if a node A has two successors B and C, and B was pruned, it could be
     * that the pruning of B means A can be pruned as well. In an even simpler case, if A has a single successor B (unconditional) and B is pruned,
     * then A can be pruned as well).
     */
    data class PrunedBranch(val infeasibleCondition: PathCondition, val conditionPtr: CmdPointer, val targetBranch: NBId) : PruneNode()

    data class PrunedBlock(val prunedBlock: NBId) : PruneNode()

    private fun findPrunedBranchesAsync(g: CoreTACProgram, blaster : IBlaster = Z3Blaster) : Parallel<List<PruneNode>> =
        Worker(g.analysisCache.graph, blaster).doAnalysis()

    fun findPrunedBranches(g: CoreTACProgram, blaster : IBlaster = Z3Blaster) : List<PruneNode> = findPrunedBranchesAsync(g, blaster).runInherit()
}
