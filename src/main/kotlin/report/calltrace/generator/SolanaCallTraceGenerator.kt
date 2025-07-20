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

package report.calltrace.generator

import dwarf.DebugInfoReader
import report.calltrace.*
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.sarif.FmtArg
import sbf.tac.*
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.CVLType
import tac.NBId
import vc.data.CoreTACProgram
import vc.data.SnippetCmd
import vc.data.TACCmd
import vc.data.TACMeta

/**
 * This class manages the generation of the call trace when analyzing a Solana project.
 * It specifically handles Solana-related commands, delegating the ones it cannot process to its superclass.
 */
internal class SolanaCallTraceGenerator(
    ruleName: String,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
) : CvlrCallTraceGenerator(ruleName, model, program, formatter, scene, ruleCallString) {

    override fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int): HandleCmdResult {
        return when (cmd) {
            is TACCmd.Simple.AnnotationCmd -> {
                val (meta, value) = cmd.annot
                when (meta) {
                    TACMeta.SNIPPET -> {
                        when (val snippetCmd = value as SnippetCmd) {
                            is SnippetCmd.CvlrSnippetCmd -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                            is SnippetCmd.SolanaSnippetCmd -> {
                                when (snippetCmd) {
                                    is SnippetCmd.SolanaSnippetCmd.ExternalCall -> handleSolanaExternalCall(
                                        snippetCmd,
                                        cmd
                                    )

                                    is SnippetCmd.SolanaSnippetCmd.Assert -> handleSolanaUserAssert(snippetCmd, cmd)
                                }
                            }
                            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                        }
                    }
                    SBF_INLINED_FUNCTION_START -> handleSolanaFunctionStart(value as SbfInlinedFuncStartAnnotation)
                    SBF_INLINED_FUNCTION_END -> handleSolanaFunctionEnd(value as SbfInlinedFuncEndAnnotation)
                    else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }
            }
            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
        }
    }

    private fun handleSolanaFunctionStart(annot: SbfInlinedFuncStartAnnotation): HandleCmdResult {
        val range = DebugInfoReader.findFunctionRangeInSourcesDir(annot.mangledName)
        val newInstance = CallInstance.InvokingInstance.SolanaFunctionInstance(
            name = "${annot.name}(...)",
            range = range,
            callIndex = annot.id
        )
        callTracePush(newInstance)
        return HandleCmdResult.Continue
    }

    private fun handleSolanaFunctionEnd(annot: SbfInlinedFuncEndAnnotation): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.SolanaFunctionInstance && it.callIndex == annot.id },
            eventDescription = "start of solana function (id = ${annot.id})"
        )
    }

    private fun handleSolanaExternalCall(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.ExternalCall,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val formattedList = snippetCmd.symbols.map { sym ->
            CallTraceValue.cvlCtfValueOrUnknown(
                model.valueAsTACValue(sym),
                CVLType.PureCVLType.Primitive.UIntK(256)
            ).toSarif(formatter, tooltip = "value")
        }
        val sarif =
            sarifFormatter.fmt(
                "${snippetCmd.displayMessage}${
                    if (formattedList.isNotEmpty()) {
                        ":"
                    } else {
                        ""
                    }
                } " + List(formattedList.size) { _ -> "{}" }
                    .joinToString(", "),
                *formattedList.map { FmtArg(it) }.toTypedArray()
            )
        callTraceAppend(CallInstance.SolanaExternalCall(sarif, range))
        return HandleCmdResult.Continue
    }

    private fun handleSolanaUserAssert(
        snippetCmd: SnippetCmd.SolanaSnippetCmd.Assert,
        stmt: TACCmd.Simple.AnnotationCmd
    ): HandleCmdResult {
        val range = consumeAttachedRangeOrResolve(stmt)
        val msgResult = { assertVerified: Boolean ->
            val isSuccess = if (snippetCmd.fromSatisfy) {
                // If the assertion is generated from satisfy, we want to flip the message: satisfy is OK when there is
                // a violation, and satisfy is FAIL when there is *no* violation.
                !assertVerified
            } else {
                assertVerified
            }
            if (isSuccess) {
                "OK"
            } else {
                "FAIL"
            }
        }
        val assertVerified = model.valueAsBoolean(snippetCmd.symbol).leftOrNull()
        val msg = if (assertVerified != null) {
            if (assertVerified) {
                "${snippetCmd.displayMessage} ${msgResult(assertVerified)}"
            } else {
                "${snippetCmd.displayMessage} ${msgResult(assertVerified)}"
            }
        } else {
            "${snippetCmd.displayMessage} UNKNOWN"
        }
        callTraceAppend(CallInstance.CvlrUserAssert(msg, range))
        return HandleCmdResult.Continue
    }
}
