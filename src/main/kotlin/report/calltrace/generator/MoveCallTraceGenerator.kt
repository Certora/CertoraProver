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

import datastructures.stdcollections.*
import java.math.BigInteger
import move.*
import report.calltrace.*
import report.calltrace.formatter.*
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.CVLType
import spec.rules.IRule
import tac.NBId
import utils.*
import vc.data.*
import wasm.impCfg.*

/**
 * This class manages the generation of the call trace when analyzing a Move project.
 * It specifically handles Move-related commands, delegating the ones it cannot process to its superclass.
 */
internal class MoveCallTraceGenerator(
    rule: IRule,
    cexId: Int,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
) : CallTraceGenerator(rule, cexId, model, program, formatter, scene, ruleCallString) {

    private val typesById = mutableMapOf<BigInteger, MoveType.Value>()

    override fun handleCmd(cmd: TACCmd.Simple, cmdIdx: Int, currBlock: NBId, blockIdx: Int) =
        cmd.maybeAnnotation(TACMeta.SNIPPET)?.let {
            when (it) {
                is MoveCallTrace.TypeId -> handleTypeId(it)
                is MoveCallTrace.FuncStart -> handleFuncStart(it)
                is MoveCallTrace.FuncEnd -> handleFuncEnd(it)
                is MoveCallTrace.Assert -> handleAssert(it)
                is MoveCallTrace.Assume -> handleAssume(it)
                else -> null
            }
        } ?: super.handleCmd(cmd, cmdIdx, currBlock, blockIdx)

    private class Call(
        val funcName: MoveFunctionName,
        override val callName: String,
        override val params: List<MoveFunction.DisplayParam>,
        override val returnTypes: List<MoveType>,
        override val range: Range.Range?,
        override val formatter: CallTraceValueFormatter
    ) : CallInstance.InvokingInstance<MoveType>()

    private fun handleTypeId(annot: MoveCallTrace.TypeId): HandleCmdResult {
        typesById[annot.id.toBigInteger()] = annot.type
        return HandleCmdResult.Continue
    }

    private fun handleFuncStart(annot: MoveCallTrace.FuncStart): HandleCmdResult {
        val typeArgs = annot.typeArgIds.map {
            model.valueAsBigInteger(it).leftOrNull()?.let {
                typesById[it]?.displayName() ?: "(#$it)"
            } ?: "(unknown)"
        }.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">").orEmpty()
        callTracePush(
            Call(
                annot.name,
                "${annot.name}$typeArgs",
                annot.params,
                annot.returnTypes,
                annot.range,
                formatter
            ).also { call ->
                annot.args.forEachIndexed { i, arg ->
                    call.paramValues[i] = CallTraceValue.MoveArg(
                        name = annot.params[i].name ?: annot.params[i].type.toString(),
                        value = arg.toCallTraceValue()
                    )
                }
            }
        )
        return HandleCmdResult.Continue
    }

    private fun handleFuncEnd(annot: MoveCallTrace.FuncEnd): HandleCmdResult {
        return ensureStackState(
            requirement = { it is Call && it.funcName == annot.name },
            allowedToPop = { it is CallInstance.LoopInstance.Start },
            eventDescription = "start of move function ${annot.name}"
        ) {
            val call = it as Call
            annot.returns.forEachIndexed { i, ret ->
                call.returnValues[i] = CallTraceValue.MoveArg(
                    name = call.returnTypes[i].toString(),
                    value = ret.toCallTraceValue()
                )
            }
        }
    }

    private class Assume(val message: String, override val range: Range.Range?) : CallInstance() {
        override val name get() = message
    }
    private fun handleAssume(assume: MoveCallTrace.Assume): HandleCmdResult {
        val message = assume.message?.let { "assuming condition: $it" } ?: "assuming condition"
        callTraceAppend(Assume(message, assume.range))
        return HandleCmdResult.Continue
    }

    private class Assert(val message: String, override val range: Range.Range?) : CallInstance() {
        override val name get() = message
    }
    private fun handleAssert(assert: MoveCallTrace.Assert): HandleCmdResult {
        val result = when (model.valueAsBoolean(assert.condition).leftOrNull()) {
            true -> "OK"
            false -> "FAIL"
            null -> "UNKNOWN"
        }
        val message = if (assert.isSatisfy) {
            assert.message?.let { "satisfying condition: $it ($result)" } ?: "satisfying condition ($result)"
        } else {
            assert.message?.let { "asserting condition: $it ($result)" } ?: "asserting condition ($result)"
        }
        callTraceAppend(Assert(message, assert.range))
        return HandleCmdResult.Continue
    }

    private fun MoveCallTrace.Value.toCallTraceValue(): CallTraceValue {
        return when (this) {
            is MoveCallTrace.Value.Primitive -> {
                CallTraceValue.cvlCtfValueOrUnknown(
                    model.valueAsTACValue(sym) ?: return CallTraceValue.Empty,
                    when (type) {
                        is MoveType.Bits -> CVLType.PureCVLType.Primitive.UIntK(type.size)
                        is MoveType.Bool -> CVLType.PureCVLType.Primitive.Bool
                        is MoveType.MathInt -> CVLType.PureCVLType.Primitive.Mathint
                    }
                )
            }
            is MoveCallTrace.Value.Reference -> {
                CallTraceValue.MoveReference(value.toCallTraceValue())
            }
            is MoveCallTrace.Value.Struct -> {
                CallTraceValue.MoveStruct(
                    fields.map { (name, value) -> name to value.toCallTraceValue() }
                )
            }
            is MoveCallTrace.Value.Enum -> {
                val variantIndexVal = model.valueAsBigInteger(variantIndex).leftOrNull()?.toIntOrNull() ?: return CallTraceValue.Empty
                val (variantName, fields) = variants.getOrNull(variantIndexVal) ?: return CallTraceValue.Empty
                CallTraceValue.MoveEnum(
                    variantName,
                    fields.map { (name, value) -> name to value.toCallTraceValue() }
                )
            }
            is MoveCallTrace.Value.Vector -> {
                CallTraceValue.MoveVector(
                    model.valueAsBigInteger(length).leftOr { return CallTraceValue.Empty },
                    elements.map { it.toCallTraceValue() }
                )
            }
            is MoveCallTrace.Value.NotDisplayed -> CallTraceValue.Empty
        }
    }
}
