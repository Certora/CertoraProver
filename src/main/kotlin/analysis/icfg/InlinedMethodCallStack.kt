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
import datastructures.topOrNull
import tac.CallId
import vc.data.*


data class StackPushRecord(val ref: CallableRef, val ptr: CmdPointer, val callType: TACCallType?, val isNoRevert: Boolean, val callId: CallId)

class InlinedMethodCallStack(graph: TACCommandGraph, includeCVLFunctions: Boolean = false):
    AnalysisStack<StackPushRecord>(graph, { cmd, _ -> handleCmd(cmd, includeCVLFunctions)} ) {
    companion object {
        fun handleCmd(cmd: LTACCmd, includeCVLFunctions: Boolean): StackAction<StackPushRecord> {
            if (cmd.cmd is TACCmd.Simple.AnnotationCmd) {
                when (cmd.cmd.annot.k) {
                    Inliner.CallStack.STACK_PUSH -> {
                        val annot = cmd.cmd.annot.v as Inliner.CallStack.PushRecord
                        return PushAction(StackPushRecord(annot.callee, cmd.ptr, annot.summary?.callType, annot.isNoRevert, annot.calleeId))
                    }
                    Inliner.CallStack.STACK_POP -> {
                        return PopAction()
                    }
                    TACMeta.SNIPPET -> {
                        if (includeCVLFunctions && cmd.cmd.annot.v is SnippetCmd.CVLSnippetCmd.CVLFunctionStart) {
                            val annot = cmd.cmd.annot.v
                            return PushAction(StackPushRecord(CVLFunctionRef(annot.name), cmd.ptr, TACCallType.REGULAR_CALL, annot.isNoRevert, annot.callIndex))
                        } else if (includeCVLFunctions && cmd.cmd.annot.v is SnippetCmd.CVLSnippetCmd.CVLFunctionEnd) {
                            return PopAction()
                        }
                    }
                }
            }
            return NoAction()
        }
    }

    fun currentCaller(ptr: CmdPointer): StackPushRecord? = stackAt(ptr).topOrNull()
    fun iterateUpCallers(ptr: CmdPointer): List<CallableRef> = stackAt(ptr).map { it.ref }
    fun iterateUpCallersMethodOnly(ptr: CmdPointer): List<MethodRef> = stackAt(ptr).mapNotNull { it.ref as? MethodRef }
}
