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
import analysis.LTACCmd
import analysis.TACCommandGraph
import datastructures.PersistentStack
import report.calltrace.CVLReportLabel
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.find

data class CVLLabelStackPushRecord(val ptr: CmdPointer, val annot: TACCmd.Simple.AnnotationCmd.Annotation<*>, val id: Int) {
    override fun toString(): String = when (annot.v) {
        is Inliner.CallStack.PushRecord -> "STACK_PUSH of $id at $ptr"
        is SnippetCmd.CVLSnippetCmd.CVLFunctionStart -> "CVLFunctionStart of $id ${annot.v.name} at $ptr"
        is SnippetCmd.CVLSnippetCmd.EventID -> "EventID $id at $ptr"
        is CVLReportLabel -> "CVLReportLabel $id ${annot.v} at $ptr"
        else -> "CVLLabelStackPushRecord(ptr=$ptr, annot=$annot, id=$id)"
    }
}

/**
 * A stack of annotations such labels, function starts, etc. that should always have a matching end annotation
 * @param check determines if we check on pops that the top of the stack matched the end we encountered,
 *      effectively making building the whole stack a check that these things are well-matched across the graph
 * Can be used to repair this invariant when making a transformation that messes with control flow
 */
class CVLLabelStack(graph: TACCommandGraph, check: Boolean = true):
    AnalysisStack<CVLLabelStackPushRecord>(graph, { cmd, stack -> handleCmd(cmd, stack, check)} ) {
    companion object {
        fun handleCmd(cmd: LTACCmd, stack: PersistentStack<CVLLabelStackPushRecord>, check: Boolean): StackAction<CVLLabelStackPushRecord> {
            if (cmd.cmd is TACCmd.Simple.AnnotationCmd) {
                when (cmd.cmd.annot.k) {
                    Inliner.CallStack.STACK_PUSH -> {
                        return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, (cmd.cmd.annot.v as Inliner.CallStack.PushRecord).calleeId))
                    }
                    Inliner.CallStack.STACK_POP -> {
                        if (check) {
                            val top = stack.top.annot.v
                            val curr = cmd.cmd.annot.v as Inliner.CallStack.PopRecord
                            check(top is Inliner.CallStack.PushRecord && top.calleeId == curr.calleeId) {
                                "Expected matching stack top for pop of STACK_POP ${curr.calleeId} at ${cmd.ptr}, got ${stack.top}"
                            }
                        }
                        return PopAction()
                    }
                    TACMeta.SNIPPET -> {
                        when (cmd.cmd.annot.v) {
                            is SnippetCmd.CVLSnippetCmd.CVLFunctionStart -> {
                                return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, cmd.cmd.annot.v.callIndex))
                            }
                            is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd -> {
                                if (check) {
                                    val top = stack.top.annot.v
                                    val curr = cmd.cmd.annot.v
                                    check(top is SnippetCmd.CVLSnippetCmd.CVLFunctionStart && top.callIndex == curr.callIndex) {
                                        "Expected matching stack top for pop of CVLFunctionEnd ${curr.callIndex} at ${cmd.ptr}, got ${stack.top}"
                                    }
                                }
                                return PopAction()
                            }
                            is SnippetCmd.CVLSnippetCmd.EventID -> {
                                return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, cmd.cmd.annot.v.id ?: error("EventID without id?! $cmd.cmd.annot")))
                            }
                        }
                    }
                    TACMeta.CVL_LABEL_START -> {
                        return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, cmd.cmd.meta.find(TACMeta.CVL_LABEL_START_ID) ?: error("Start label without start id meta?!")))
                    }
                    TACMeta.CVL_LABEL_END -> {
                        if (check) {
                            val top = stack.top
                            val curr = cmd.cmd.annot.v as Int
                            check(top.annot.v is CVLReportLabel || top.annot.v is SnippetCmd.CVLSnippetCmd.EventID && top.id == curr) {
                                "Expected matching stack top for pop of CVL_LABEL_END $curr at ${cmd.ptr}, got ${stack.top}"
                            }
                        }
                        return PopAction()
                    }
                }
            }
            return NoAction()
        }
    }
}
