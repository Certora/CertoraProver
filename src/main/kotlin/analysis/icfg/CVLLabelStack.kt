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
        is SummaryStack.SummaryStart.Internal -> "SummaryStart Internal of ${annot.v.appliedSummary} at $ptr"
        is SummaryStack.SummaryStart.External -> "SummaryStart External of $id ${annot.v.appliedSummary} at $ptr"
        else -> "CVLLabelStackPushRecord(ptr=$ptr, annot=$annot, id=$id)"
    }
    fun matchingEndAnnotation(): TACCmd.Simple.AnnotationCmd = when (annot.v) {
        is Inliner.CallStack.PushRecord -> TACCmd.Simple.AnnotationCmd(
            Inliner.CallStack.STACK_POP,
            Inliner.CallStack.PopRecord(annot.v.callee, annot.v.calleeId)
        )
        is SnippetCmd.CVLSnippetCmd.CVLFunctionStart -> TACCmd.Simple.AnnotationCmd(
            TACMeta.SNIPPET,
            SnippetCmd.CVLSnippetCmd.CVLFunctionEnd(annot.v.callIndex, annot.v.name)
        )
        is SnippetCmd.CVLSnippetCmd.EventID,
        is CVLReportLabel -> TACCmd.Simple.AnnotationCmd(TACMeta.CVL_LABEL_END, id)
        is SummaryStack.SummaryStart.Internal -> TACCmd.Simple.AnnotationCmd(
            SummaryStack.END_INTERNAL_SUMMARY,
            // we pass null for rets here since we use this to reconstruct end annotations for revert paths,
            // where the return values would not be well-defined
            SummaryStack.SummaryEnd.Internal(annot.v.appliedSummary, rets = null, annot.v.methodSignature)
        )
        is SummaryStack.SummaryStart.External -> TACCmd.Simple.AnnotationCmd(
            SummaryStack.END_EXTERNAL_SUMMARY,
            SummaryStack.SummaryEnd.External(annot.v.appliedSummary, annot.v.callNode.summaryId)
        )
        else -> error("Cannot get matching end for $this.")
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
                    SummaryStack.START_INTERNAL_SUMMARY -> {
                        return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, 0))
                    }
                    SummaryStack.END_INTERNAL_SUMMARY -> {
                        if (check) {
                            val top = stack.top.annot
                            val curr = cmd.cmd.annot.v as SummaryStack.SummaryEnd.Internal
                            check(top.v is SummaryStack.SummaryStart.Internal && top.v.methodSignature == curr.methodSignature) {
                                "Expected matching stack top for pop of END_INTERNAL_SUMMARY $curr at ${cmd.ptr}, got ${stack.top}"
                            }
                        }
                        return PopAction()
                    }
                    SummaryStack.START_EXTERNAL_SUMMARY -> {
                        return PushAction(CVLLabelStackPushRecord(cmd.ptr, cmd.cmd.annot, (cmd.cmd.annot.v as SummaryStack.SummaryStart.External).callNode.summaryId))
                    }
                    SummaryStack.END_EXTERNAL_SUMMARY -> {
                        if (check) {
                            val top = stack.top
                            val curr = cmd.cmd.annot.v as SummaryStack.SummaryEnd.External
                            check(top.annot.v is SummaryStack.SummaryStart.External && top.id == curr.summaryId) {
                                "Expected matching stack top for pop of END_EXTERNAL_SUMMARY $curr with id ${curr.summaryId} at ${cmd.ptr}, got ${stack.top} with id ${top.id}"
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
