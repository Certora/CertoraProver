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

package analysis

import algorithms.SimpleDominanceAnalysis
import algorithms.topologicalOrder
import com.certora.collect.*
import tac.Edge
import tac.NBId
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

/**
 * @param head dominates all blocks in body, tail has an outgoing edge to here
 * @param body all blocks in loop, including head and tail
 * @param tail "end" of the loop, with a back edge to head
 */
data class Loop(val head: NBId, val body: Set<NBId>, val tail: NBId) {

    /**
     * Returns a list of the blocks in [body] sorted in topological order (starting with [head]), using [predFun]
     * to obtain the predecessors of the blocks in the loop.
     * IMPORTANT: This function assumes there are no nested loops
     */
    fun sorted(predFun: (NBId) -> Set<NBId>): List<NBId> {
        return topologicalOrder(
            nodes = body,
            nexts = { predFun(it).filter { n -> n in body } }
        )
    }
}

fun getBackEdges(g: GenericTACCommandGraph<*,*,*>, dom: SimpleDominanceAnalysis<NBId>) : Set<Edge> {
    return g.blockSucc.flatMap { (u, vs) ->
        vs.filter { v -> dom.dominates(v, u) }
                .map {v -> Edge(u, v) }
    }.toSet()
}

fun getNaturalLoops(g: TACCommandGraph): Set<Loop> {
    return getNaturalLoopsGeneric(g, g.cache.domination)
}
/**
 * @param g a TAC CFG
 * @return a set of every natural loop in g
 */
fun getNaturalLoopsGeneric(g: GenericTACCommandGraph<*,*,*>, dom: SimpleDominanceAnalysis<NBId>) : Set<Loop> {
    val backEdges = getBackEdges(g, dom)
    return backEdges.map { (tail, head) ->
        val body = mutableSetOf<NBId>()
        // backward search for all nodes that are part of the natural loop
        // TODO: do we want the loop head to be part of the body?, the tail?
        var curr = mutableSetOf(tail)
        var nxt = mutableSetOf<NBId>()
        while (curr.isNotEmpty()) {
            for (nb in curr) {
                body.add(nb)
                if (nb == head) {
                    continue
                }
                for (p in g.pred(nb)) {
                    if (p !in body) {
                        nxt.add(p)
                    }
                }
            }
            curr.clear()
            val tmp = nxt
            nxt = curr
            curr = tmp
        }
        Loop(head, body.toTreapSet(), tail)
    }.toSet()
}

/**
 * @return all loops in g that branch when a counter, decremented by counterDecrement each iteration, reaches
 *          zero and some register used to index into a write into memory in increased by pointerIncrement
 *          each iteration
 */
fun getWriteLoops(g: TACCommandGraph,
                 pointerIncrement: BigInteger,
                 counterDecrement: BigInteger) {
    return g.scope {
        getNaturalLoops(g).filter { loop ->
            val loopCommands = loop.body.flatMap { block -> block.elab().commands }
            val decrements = loopCommands
                    .filter { command ->
                        command.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                                command.cmd.rhs is TACExpr.BinOp.Sub &&
                                command.cmd.rhs.o2 is TACExpr.Sym.Const &&
                                command.cmd.rhs.o2.s.value == counterDecrement
                    }
                    .map { command ->
                        command.ptr to (command.cmd as TACCmd.Simple.AssigningCmd.AssignExpCmd).lhs
                    }
            val jumpis = loopCommands
                    .filter { command ->
                        command.cmd is TACCmd.Simple.JumpiCmd &&
                                command.cmd.dst in loop.body &&
                                command.cmd.elseDst !in loop.body &&
                                command.cmd.cond is TACSymbol.Var
                    }
            val lengthUpdates = jumpis
                    .mapNotNull { command ->
                        val cond = (command.cmd as TACCmd.Simple.JumpiCmd).cond as TACSymbol.Var
                        decrements.map { varAtPoint ->
                            varAtPoint.first to g.cache.gvn.findCopiesAt(command.ptr, varAtPoint)
                        }.firstOrNull { (_, vars) ->
                            cond in vars
                        }?.first
                    }
            lengthUpdates.isNotEmpty()
        }.filter { candidate ->
            candidate.body.any { block ->
                val commands = block.elab().commands
                val writes = commands.filter { it.cmd is TACCmd.Simple.AssigningCmd.ByteStore }.toSet()
                commands.any { command ->
                    when {
                        (command.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                                command.cmd.rhs is TACExpr.Vec.Add) -> {
                            val o1 = command.cmd.rhs.o1AsNullableTACSymbol()
                            val o2 = command.cmd.rhs.o2AsNullableTACSymbol()
                            val incrementedRegister =
                                    if (o1 is TACSymbol.Const &&
                                            o1.value == pointerIncrement &&
                                            o2 is TACSymbol.Var) {
                                        o2
                                    }
                                    else if (o2 is TACSymbol.Const &&
                                            o2.value == pointerIncrement &&
                                            o1 is TACSymbol.Var) {
                                        o1
                                    }
                                    else {
                                        null
                                    }
                            incrementedRegister?.let{ register ->
                                writes.any { write ->
                                    check(write.cmd is TACCmd.Simple.AssigningCmd.ByteStore)
                                    if (write.cmd.value is TACSymbol.Var) {
                                        val copies = g.cache.gvn.findCopiesAt(command.ptr, Pair(write.ptr, write.cmd.value))
                                        register in copies
                                    } else {
                                        false
                                    }
                                }
                            }?: false
                        }
                        else -> false
                    }
                }
            }
        }.toSet()
    }
}
