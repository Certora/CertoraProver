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

package analysis.loop

import allocator.Allocator
import analysis.*
import analysis.dataflow.IGlobalValueNumbering
import analysis.worklist.StepResult
import analysis.worklist.WorklistIteration
import tac.MetaMap
import tac.NBId
import tac.Tag
import java.util.stream.Collectors
import datastructures.stdcollections.*
import utils.*
import vc.data.*

typealias HoistableP = (TACSymbol.Var) -> Boolean

/**
 * Find repeated addition operations within a loop and hoist those additions out of the loop.
 * For example if we have:
 *
 * while(*) {
 *    x = y + k1;
 *    z = x + k2;
 *    y = z;
 * } we will hoist out:
 *
 * k3 = k1 + k2;
 * while(*) {
 *    z = y + k3;
 *    y = z;
 * }
 */
object LoopHoistingOptimization {
    /**
     * Represents a "hoistable" addition. Consider the following code:
     * x = y + k1;
     * z = x + k2;
     * Then the hoistable representation of the definition at z is:
     * otherOperands = y, hoistable = k1, k2
     * (n.b. we have folded the definition of x)
     *
     * In other words, all operands listed in hoistable can be lifted out of the loop without changing their value.
     * otherOperands may not: as in the case above, the value of y may change across loop iterations.
     */
    private data class Hoistable(
            val otherOperands: List<TACSymbol.Var>,
            val hoistable: List<TACSymbol>
    )

    sealed class ProgramModification {
        data class Replace(val where: CmdPointer, val new: TACCmd.Simple) : ProgramModification()
        data class InsertBefore(val from: NBId, val to: NBId, val new: TACCmd.Simple) : ProgramModification()
        data class AddDecl(val what: TACSymbol.Var) : ProgramModification()
    }

    private fun relevantLoopMutations(lcmd: LTACCmd, gvn: IGlobalValueNumbering): Set<TACSymbol.Var> {
        return when (val cmd = lcmd.cmd) {
            is TACCmd.Simple.AssigningCmd -> {
                val shouldTake = cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd ||
                    cmd.rhs !is TACExpr.Sym.Var ||
                    /* If so, then we have:
                        vn = lhs
                        v2 = ...
                        v1 = v2
                        lhs = v1

                        In which case lhs is not actually changing within the loop
                     */
                    cmd.lhs !in gvn.findCopiesAt(lcmd.ptr, lcmd.ptr to cmd.rhs.s)
                if (shouldTake) {
                    setOf(cmd.lhs)
                } else {
                    setOf()
                }
            }

            is TACCmd.Simple.SummaryCmd ->
                if (cmd.summ is InternalCallSummary) {
                    cmd.summ.internalExits.mapToSet { it.s }
                } else {
                    setOf()
                }

            else ->
                setOf()
        }
    }

    fun hoistLoopComputations(c: CoreTACProgram) : CoreTACProgram {
        val graph = c.analysisCache.graph
        val loops = getNaturalLoops(graph)
        val dom = c.analysisCache.domination
        // Accessing this early prevents some problematic lazy initialization recursion; see Lazy.kt
        val gvn = graph.cache.gvn
        val modifications = loops.parallelStream().flatMap { loop ->
            val mutatedVariables = mutableSetOf<TACSymbol.Var>()
            loop.body.flatMapTo(mutatedVariables) {
                graph.elab(it).commands.flatMapToSet {
                    relevantLoopMutations(it, gvn)
                }
            }.toSet()

            val hoistState = mutableMapOf<CmdPointer, Map<TACSymbol.Var, Hoistable>>()
            /*
              The replacement locations are those additions within the loop that can be hoisted according to the rules defined in
              IterateHoistable
             */
            val replacementLocations = object : IterateHoistable(graph, hoistState, { it !in mutatedVariables }) {
                override fun continueAt(v: NBId): Boolean =
                    v in loop.body
            }.submit(listOf(loop.head))

            /*
              Mapping of canonical list of hoisted variables to the temporary variable that will hold their sum
             */
            val tempVar = mutableMapOf<List<TACSymbol>, TACSymbol.Var>()

            val replacements = replacementLocations.map {
                graph.elab(it).enarrow<TACExpr.Vec.Add>()
            }.mapNotNull { toReplace ->
                val st = hoistState[toReplace.ptr] ?: return@mapNotNull null
                val hoist = toReplace.exp.toHoisted({it !in mutatedVariables }, st).takeIf {
                    it.hoistable.size > 1 && toReplace.exp.ls.all {
                        (it !is TACExpr.Sym.Var) ||
                                it.s !in mutatedVariables ||
                                graph.cache.def.defSitesOf(it.s, toReplace.ptr).all {def ->
                                    graph.cache.use.useSitesAfter(it.s, def) == setOf(toReplace.ptr)
                                }
                    }
                } ?: return@mapNotNull null
                val hoistedArgs = tempVar.computeIfAbsent(hoist.hoistable.sortedWith(TACSymbol.byValueOrName)) {
                    // We're hoisting a partial computation to replace a subexpression in the original assignment
                    // so we need a temp variable to store it
                    TACSymbol.Var(
                        namePrefix = "H${Allocator.getFreshId(Allocator.Id.TEMP_VARIABLE)}",
                        tag = Tag.Bit256
                    )
                }
                toReplace.ptr to
                        /*
                          In practice there will only ever be one otherOperand here, but we don't enforce that here
                         */
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            toReplace.lhs,
                            (hoist.otherOperands.map { it.asSym() } + hoistedArgs.asSym()).toAddOrSingleSymbol(),
                            meta = toReplace.cmd.meta
                        )
            }.toMap().toMutableMap()
            // now search for "similar" hoists in the successor of the loop
            val successorBlocks = dom.dominatedOf(loop.head) - loop.body
            /* in blocks dominated by the loop header (before which we will be hoisting) can we propagate the hoistings we found?
               This is not *strictly* necessary, only if we want the loop copy analysis to work on "optimized" code.

               Also, we only care about straightline code.
             */
            if(successorBlocks.isNotEmpty() && tempVar.isNotEmpty()) {
                /*
                  Variables we hoisted into additions outside the loop
                 */
                val hoistedVars = tempVar.keys.flatMap {
                    it.filterIsInstance<TACSymbol.Var>()
                }.toSet()
                val uniqSucc = loop.body.flatMap {
                    graph.succ(it).filter {
                        it !in loop.body && it !in graph.cache.revertBlocks
                    }
                }.singleOrNull()
                if(uniqSucc != null) {
                    /*
                      Iterate through the dominated blocks reachable along straightline paths (no joins), doing the hoist checks.
                     */
                    val successorRewrites = object : IterateHoistable(graph, hoistState, {
                        /*
                            Only allow hoisting for variables that we hoisted in the loop
                         */
                        it in hoistedVars
                    }) {
                        override fun continueAt(v: NBId): Boolean =
                            v in successorBlocks && graph.pred(v).size == 1

                        override fun process(it: NBId): StepResult<NBId, Pair<Set<CmdPointer>, Set<CmdPointer>>, Set<CmdPointer>> {
                            if(graph.elab(it).commands.any {
                                        it.cmd.getLhs() in hoistedVars
                                    }) {
                                return this.result(listOf())
                            }
                            return super.process(it)
                        }
                    }.submit(listOf(uniqSucc)).map {
                        graph.elab(it).enarrow<TACExpr.Vec.Add>()
                    }.mapNotNull {
                        val hoist = it.exp.toHoisted(canHoist = {it in hoistedVars}, state = hoistState[it.ptr] ?: return@mapNotNull null)
                        val hoistArgs = hoist.hoistable.sortedWith(TACSymbol.byValueOrName)
                        /*
                          This looked hoistable, but we didn't find a similar hoistable addition in the loop, skip!
                         */
                        if(hoistArgs !in tempVar) {
                            return@mapNotNull null
                        }
                        it.ptr to TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = it.lhs,
                            rhs = (hoist.otherOperands.map { it.asSym() } + tempVar[hoistArgs]!!.asSym()).toAddOrSingleSymbol(),
                            meta = it.cmd.meta
                        )
                    }
                    replacements.putAll(successorRewrites)
                }
            }
            /*
              Stage the rewritings (remember we are in a parallel context)
             */
            (replacements.map {
                ProgramModification.Replace(it.key, it.value)
            } + tempVar.flatMap {
                graph.pred(loop.head).filter { from ->
                    from !in loop.body
                }.map { from ->
                    ProgramModification.InsertBefore(from = from, to = loop.head, new = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = it.value,
                            rhs = TACExpr.Vec.Add(it.key.map { it.asSym() }),
                            meta = MetaMap()
                    ))
                }
            } + tempVar.map {
                ProgramModification.AddDecl(it.value)
            }).stream()
        }.collect(Collectors.toList())

        if(modifications.isEmpty()) {
            return c
        }
        val patching = c.toPatchingProgram()
        val alreadyMut = mutableSetOf<CmdPointer>()
        for(m in modifications) {
            when(m) {
                is ProgramModification.Replace -> {
                    if(m.where in alreadyMut) {
                        // give up entirely
                        return c
                    }
                    alreadyMut.add(m.where)
                    patching.replaceCommand(m.where, listOf(m.new))
                }
                is ProgramModification.InsertBefore -> {
                    patching.insertAlongEdge(m.from, m.to, listOf(m.new))
                }
                is ProgramModification.AddDecl -> patching.addVarDecl(m.what)
            }
        }

        return patching.toCode(c)
    }

    private fun List<TACExpr>.toAddOrSingleSymbol(): TACExpr =
        singleOrNull() ?: TACExpr.Vec.Add(this)

    /**
     * Find hoistable additions.
     */
    private abstract class IterateHoistable(
            private val graph: TACCommandGraph,
            private val hoistState: MutableMap<CmdPointer, Map<TACSymbol.Var, Hoistable>>,
            private val canHoist: HoistableP
    ) : WorklistIteration<NBId, Pair<Set<CmdPointer>, Set<CmdPointer>>, Set<CmdPointer>>() {
        override fun process(it: NBId): StepResult<NBId, Pair<Set<CmdPointer>, Set<CmdPointer>>, Set<CmdPointer>> {
            val blockCommands = graph.elab(it).commands
            var nextState = hoistState.computeIfAbsent(blockCommands.first().ptr) {
                mapOf()
            }
            val mayBeHoisted = mutableSetOf<CmdPointer>()
            val mustNotBeHoisted = mutableSetOf<CmdPointer>()
            for(lc in blockCommands) {
                hoistState[lc.ptr] = nextState
                if(lc.cmd !is TACCmd.Simple.AssigningCmd) {
                    continue
                }
                /*
                 * If the abstract state doesn't mention the LHS, and this is not an addition command, we aren't interested
                 */
                if(nextState.none {
                            it.key == lc.cmd.lhs || it.value.otherOperands.contains(lc.cmd.lhs)
                        } && (lc.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd ||
                                lc.cmd.rhs !is TACExpr.Vec.Add)) {
                    continue
                }
                /*
                   Forget anything about variables mutated by this assignment
                 */
                nextState = nextState.filter { (k, v) ->
                    k != lc.cmd.lhs && !v.otherOperands.contains(lc.cmd.lhs)
                }
                /*
                  create a new representation for this addition operation if:
                  1) All of the operands are symbols, and
                  2) at least one of the operands is a variable and has a hoisted representation OR is a variable that can
                    be hoisted (i.e., is not mutated within the loop)
                */
                if(lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                        lc.cmd.rhs is TACExpr.Vec.Add &&
                                lc.cmd.rhs.ls.any {
                                    it is TACExpr.Sym && (it.s in nextState || (it is TACExpr.Sym.Var && canHoist(it.s)))
                                } && lc.cmd.rhs.ls.all {it is TACExpr.Sym }) {
                    val hoist = lc.cmd.rhs.toHoisted(canHoist, nextState)
                    /*
                      This is an annoying limitation of our addition semantics in the PTA: we actually can't generate
                      hoisted additions with more than two operands :(

                      Effectively, this check establishes that the addition command at lc is:
                      v = a + b + c
                      where c cannot be hoisted (it is the sole element of otherOperands), and a and b can be hoisted.
                      Thus we can define a hoisted variable h1 = a + b before the loop, and rewrite this addition to:
                      v + h + c
                     */
                    if(hoist.hoistable.size == 2 && hoist.otherOperands.size <= 1) {
                        mayBeHoisted.add(lc.ptr)
                    } else {
                        mustNotBeHoisted.add(lc.ptr)
                    }
                    nextState += (lc.cmd.lhs to hoist)
                } else if(lc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lc.cmd.rhs is TACExpr.Vec.Add) {
                    mustNotBeHoisted.add(lc.ptr)
                }
            }
            val toQueue = mutableListOf<NBId>()
            /*
            Blah blah blah boring join rules
             */
            for(succ in graph.succ(blockCommands.last().ptr)) {
                if(succ !in hoistState) {
                    hoistState[succ] = nextState
                    if(continueAt(succ.block)) {
                        toQueue.add(succ.block)
                    }
                } else {
                    val old = hoistState[succ]!!
                    val new = nextState.pointwiseBinopOrNull(old) { a, b ->
                        if(a == b) {
                            a
                        } else {
                            null
                        }
                    }
                    hoistState[succ] = new
                    if(new != old && continueAt(succ.block)) {
                        toQueue.add(succ.block)
                    }
                }
            }
            return StepResult.Ok(
                    next = toQueue,
                    result = listOf(mayBeHoisted to mustNotBeHoisted)
            )
        }

        override fun reduce(results: List<Pair<Set<CmdPointer>, Set<CmdPointer>>>): Set<CmdPointer> {
            val toReturn = mutableSetOf<CmdPointer>()
            val doNotInclude = mutableSetOf<CmdPointer>()
            for((may, mustNot) in results) {
                doNotInclude.addAll(mustNot)
                toReturn.addAll(may)
            }
            toReturn.removeAll(doNotInclude)
            return toReturn
        }

        protected abstract fun continueAt(v: NBId) : Boolean
    }

    private fun TACExpr.Vec.Add.toHoisted(canHoist: HoistableP, state: Map<TACSymbol.Var, Hoistable>) : Hoistable {
        val hoistable = mutableListOf<TACSymbol>()
        val other = mutableListOf<TACSymbol.Var>()
        /*
          Classify the operands as hoistable
         */
        this.ls.forEach {
            check(it is TACExpr.Sym)
            val sym = it.s
            /*
              Constants are always hoistable
             */
            if(sym is TACSymbol.Const) {
                hoistable.add(sym)
            /* hoistable variables are hoistable */
            } else if(sym is TACSymbol.Var && canHoist(sym)) {
                hoistable.add(sym)
            } else {
                /*
                 If the variable is defined by a hoistable addition, flatten
                 */
                check(sym is TACSymbol.Var)
                if(sym in state) {
                    val hoist = state[sym]!!
                    hoistable.addAll(hoist.hoistable)
                    other.addAll(hoist.otherOperands)
                } else {
                    other.add(sym)
                }
            }
        }
        return Hoistable(otherOperands = other, hoistable = hoistable)
    }
}

