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

package move

import allocator.*
import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import com.certora.collect.*
import datastructures.*
import datastructures.stdcollections.*
import move.analysis.*
import move.MoveModule.*
import utils.*
import vc.data.*

/**
    Summary of a call to a parametric rule "invoker."  We defer the generation of the actuall call to the function until
    we can compute the constant value of the target function ID.
 */
@KSerializable
data class InvokerCall(
    val invokerName: MoveFunctionName,
    val invokerArgs: List<TACSymbol.Var>
) : TACSummary {
    override val variables get() = invokerArgs.toSet()
    override val annotationDesc get() = "invoker call: $invokerName(${invokerArgs.joinToString(", ")})"

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(invokerArgs = invokerArgs.map(f))

    context(SummarizationContext)
    private fun materialize(callPtr: CmdPointer, patch: PatchingTACProgram<TACCmd>, def: MoveDefAnalysis) {
        // Find the constant ID of the target function for this invoker call
        val targetId = def.mustBeConstantAt(callPtr, invokerArgs[0])?.intValueExact()
            ?: throw CertoraException(
                CertoraErrorType.CVL,
                "Could not determine the target of the invoker call.  Parametric rule function parameters should be " +
                    "used directly; avoid complex control flow or storage in vectors, structs, etc."
            )

        val target = parametricTargets[targetId] ?: error("No target for parametric rule function ID $targetId")
        val invokerDef = scene.maybeDefinition(invokerName) ?: error("No definition for invoker $invokerName")
        val invoker = with (scene) {
            MoveFunction(invokerDef.function, typeArguments = listOf())
        }

        val preamble = mutableListOf<MoveCmdsWithDecls>()

        // For each target parameter, take one of the invoker's arguments if available; otherwise havoc a new
        // argument.  We consume invoker parameters by type; if the invoker has multiple parameters of the same
        // type, we consume them from left to right.
        val availableInvokerArgs =
            invoker.params.drop(1)
            .zip(invokerArgs.drop(1))
            .mapIndexed { pos, (type, arg) -> type to (arg to pos + 1) }
            .toMap(LinkedArrayHashMap())

        val args = target.params.mapIndexed { targetParamPos, targetParamType ->
            if (targetParamType in availableInvokerArgs) {
                val (invokerArg, invokerParamPos) = availableInvokerArgs.remove(targetParamType)!!
                // If the target parameter is a mutable reference, then the invoker parameter must also
                // be a mutable reference.
                if (target.definition.function.params[targetParamPos] is SignatureToken.MutableReference) {
                    val invokerParamToken = invokerDef.function.params[invokerParamPos]
                    if (invokerParamToken !is SignatureToken.MutableReference) {
                        throw CertoraException(
                            CertoraErrorType.CVL,
                            "Target function ${target.name} requires a mutable reference $targetParamType, but the" +
                                " corresponding parameter of $invokerName is not mutable."
                        )
                    }
                }
                invokerArg
            } else {
                val havocArg = TACKeyword.TMP(targetParamType.toTag())
                preamble += targetParamType.assignHavoc(havocArg)
                havocArg
            }
        }

        // Compile the target and add it to the program
        val targetCode = compileSubprogram(
            entryFunc = target,
            args = args,
            returns = listOf()
        )

        patch.replaceCommand(callPtr, mergeMany(preamble), targetCode)
    }

    companion object {
        context(SummarizationContext)
        fun materialize(code: MoveTACProgram): MoveTACProgram {
            val calls = code.graph.commands.mapNotNull { (ptr, cmd) ->
                ptr `to?` (cmd as? TACCmd.Simple.SummaryCmd)?.summ as? InvokerCall
            }.toList()
            if (calls.isEmpty()) {
                return code
            }
            val patch = code.toPatchingProgram()
            calls.forEach { (ptr, call) ->
                call.materialize(ptr, patch, code.graph.cache.def)
            }
            return patch.toCode(code)
        }
    }
}