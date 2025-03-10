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

package analysis.icfg

import analysis.CmdPointer
import analysis.TACCommandGraph
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import datastructures.stdcollections.*
import tac.NBId
import vc.data.TACCmd

/**
 * Provides a function [InlinedMethodCallOrder.nextCalls] which given a [CmdPointer] returns the set of calls that
 * are consecutive to this pointer. Note there can be more than one function as consecutive calls due to branching.
 * For example:
 *    foo()
 *    if (b) {
 *       bar()
 *    } else {
 *       baz()
 *    }
 *
 * here both bar and baz are consecutive calls of foo so both will be returned.
 */
class InlinedMethodCallOrder(graph: TACCommandGraph) {
    private val nextCalls = (object : StatefulWorklistIteration<NBId, Unit, Map<CmdPointer, List<StackPushRecord>>>() {
        private val blockStartState = mutableMapOf<NBId, List<Pair<StackPushRecord, Boolean>>>()
        private val state = mutableMapOf<CmdPointer, List<StackPushRecord>>()

        override fun submit(workItems: Iterable<NBId>): Map<CmdPointer, List<StackPushRecord>> {
            val toSubmit = workItems.toList()
            toSubmit.forEach { sink ->
                blockStartState[sink] = listOf()
            }
            return super.submit(toSubmit)
        }

        override fun process(it: NBId): StepResult<NBId, Unit, Map<CmdPointer, List<StackPushRecord>>> {
            val initialNextCalls = blockStartState[it] ?: error("Missing state of predecessor!")
            if (initialNextCalls.isNotEmpty() && initialNextCalls.all { it.second }) {
                return this.cont(listOf())
            }

            val block = graph.elab(it)
            var nextCalls = initialNextCalls.filter { !it.second }.map { it.first }
            for (cmd in block.commands.reversed()) {
                if (cmd.cmd is TACCmd.Simple.AnnotationCmd) {
                    when(cmd.cmd.annot.k) {
                        Inliner.CallStack.STACK_PUSH -> {
                            val annot = cmd.cmd.annot.v as Inliner.CallStack.PushRecord
                            nextCalls = listOf(StackPushRecord(annot.callee, cmd.ptr, null, annot.isNoRevert, callId = annot.calleeId))
                        }
                        Inliner.CallStack.STACK_POP -> Unit //nextCalls = listOf()
                    }
                }

                state[cmd.ptr] = state[cmd.ptr].orEmpty() + nextCalls
            }

            blockStartState[it] = blockStartState[it]!!.map { it.first to true }
            val next = mutableListOf<NBId>()
            for (pred in graph.pred(it)) {
                if (pred !in blockStartState || nextCalls.any { it !in blockStartState[pred]!!.map { it.first } }) {
                    blockStartState[pred] = blockStartState[pred].orEmpty() + nextCalls.map { it to false }
                    next.add(pred)
                }
            }
            return this.cont(next)
        }

        override fun reduce(results: List<Unit>): Map<CmdPointer, List<StackPushRecord>> {
            return state
        }
    }).submit(graph.sinkBlockIds)

    fun nextCallers(ptr: CmdPointer) = nextCalls[ptr]
}
