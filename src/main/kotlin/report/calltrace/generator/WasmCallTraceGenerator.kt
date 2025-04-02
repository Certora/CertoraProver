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

import report.calltrace.*
import report.calltrace.formatter.CallTraceValueFormatter
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import tac.NBId
import utils.*
import vc.data.*
import wasm.impCfg.*

/**
 * This class manages the generation of the call trace when analyzing a Wasm project.
 * It specifically handles Wasm-related commands, delegating the ones it cannot process to its superclass.
 */
internal class WasmCallTraceGenerator(
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
                    WASM_INLINED_FUNC_START -> handleWasmFunctionStart(value as StraightLine.InlinedFuncStartAnnotation.TAC)
                    WASM_INLINED_FUNC_END -> handleWasmFunctionEnd(value as StraightLine.InlinedFuncEndAnnotation.TAC)
                    WASM_USER_ASSUME -> handleWasmUserAssume(value as String)
                    WASM_USER_ASSERT -> handleWasmUserAssert(value as String)
                    else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
                }
            }
            else -> super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)
        }
    }

    private fun handleWasmFunctionStart(annot: StraightLine.InlinedFuncStartAnnotation.TAC): HandleCmdResult {
        val newInstance = CallInstance.InvokingInstance.WasmFunctionInstance(
            "function start: ${annot.funcName} ${ite(annot.range != null, "in ${annot.range}", "")}",
            annot.funcName
        )
        callTracePush(newInstance)
        return HandleCmdResult.Continue
    }

    private fun handleWasmFunctionEnd(annot: StraightLine.InlinedFuncEndAnnotation.TAC): HandleCmdResult {
        return ensureStackState(
            requirement = { it is CallInstance.InvokingInstance.WasmFunctionInstance && it.funcName == annot.funcName },
            eventDescription = "start of wasm function ${annot.funcName}"
        )
    }

    private fun handleWasmUserAssume(name: String): HandleCmdResult {
        callTraceAppend(CallInstance.CvlrUserAssume("user assume: $name", null))
        return HandleCmdResult.Continue
    }


    private fun handleWasmUserAssert(name: String): HandleCmdResult {
        callTraceAppend(CallInstance.CvlrUserAssert("user assert: $name", null))
        return HandleCmdResult.Continue
    }

}
