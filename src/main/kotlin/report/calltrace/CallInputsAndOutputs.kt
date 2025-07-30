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

package report.calltrace

import algorithms.UnionFind
import bridge.EVMExternalMethodInfo
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_WORD_SIZE
import log.*
import report.calltrace.CallInputsAndOutputs.Dump
import report.calltrace.CallInstance.InvokingInstance.SolidityInvokeInstance
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.FormatStrategy
import report.calltrace.formatter.LayoutDecoderStrategy
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.CVLParam
import spec.cvlast.CVLType
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.NBId
import utils.mapValuesNotNull
import utils.`to?`
import utils.tryAs
import vc.data.*
import vc.data.state.TACValue
import java.math.BigInteger
import java.math.BigInteger.ZERO

private val logger = Logger(LoggerTypes.CALLTRACE)

/**
 * Keeps track of the values of [SolidityInvokeInstance.External] calls.
 * [calldataFamily] and [returndataFamily] are used by [CallInputsAndOutputs]
 * to track parameter values and return values.
 *
 * [extra] contains additional context extracted from an [EVMExternalMethodInfo],
 * which is used when dumping the data.
 */
class ExternalCallMapping(info: EVMExternalMethodInfo) {
    val extra: ExtraData = ExtraData(info)

    val calldataFamily = DataFamily()
    val returndataFamily = DataFamily()
}

/**
 * We don't have a use for most of the data in an [EVMExternalMethodInfo] object, so this is
 * a condensed version which contains only data we currently use.
 */
class ExtraData(info: EVMExternalMethodInfo) {
    val paramTypes: List<EVMTypeDescriptor> = info.argTypes
    val paramNames: List<String> = info.paramNames
    val returnTypes: List<EVMTypeDescriptor> = info.resultTypes
}

private class CVLFunctionInfo {
    val params = mutableMapOf<Int, Pair<CVLParam, CallTraceValue>>()
    val rets = mutableMapOf<Int, Pair<CVLType.PureCVLType, TACSymbol?>>()
}

/**
 * Handles formatting of the function parameters/return values of call instances.
 * After collecting the values, we write them to the [CallInstance.InvokingInstance]
 * in a single pass.
 *
 * For external calls, we use [callIndexToExternalCallMapping] along with [aliasingBuffers]
 * to later decode the values using [LayoutDecoderStrategy].
 *
 * For internal and internal summary calls, we just write all the values out as soon as they're available.
 */
class CallInputsAndOutputs private constructor(private val model: CounterexampleModel) {

    constructor(
        blocks: List<NBId>,
        model: CounterexampleModel,
        analysisCache: TACCommandGraphAnalysisCache,
        scene: ISceneIdentifiers
    ) : this(model) {
        Initializer(blocks, model, analysisCache, scene).initialize(this)
    }

    private val callIndexToExternalCallMapping = mutableMapOf<Int, ExternalCallMapping>()
    private val callIndexToCVLFunctionInfo = mutableMapOf<Int, CVLFunctionInfo>()

    fun externalCall(callIndex: Int): ExternalCallMapping? = callIndexToExternalCallMapping[callIndex]

    internal val aliasingBuffers = UnionFind<TACSymbol.Var>()

    internal fun initExternalCall(callIndex: Int, evmMethod: EVMExternalMethodInfo) {
        callIndexToExternalCallMapping[callIndex] = ExternalCallMapping(evmMethod)
    }

    fun initCVLCall(callIndex: Int) {
        callIndexToCVLFunctionInfo[callIndex] = CVLFunctionInfo()
    }

    fun addCVLFunctionParam(callIndex: Int, index: Int, param: CVLParam, sym: TACSymbol?) {
        val cvlFuncInfo = callIndexToCVLFunctionInfo[callIndex]
            ?: return logger.warn {
                "CVL function call $callIndex was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        val value = sym?.let{ model.valueAsTACValue(it) }

        cvlFuncInfo.params[index] = Pair(param, CallTraceValue.cvlCtfValueOrUnknown(value, param.type))
    }

    fun addCVLFunctionStructParam(callIndex: Int, index: Int, param: CVLParam, syms: Set<TACSymbol>) {
        val cvlFuncInfo = callIndexToCVLFunctionInfo[callIndex]
            ?: return logger.warn {
                "CVL function call $callIndex was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        val paramType = param.type as? CVLType.PureCVLType.Struct
            ?: return logger.warn {
                "Expecting a struct param for CVL function call $callIndex at index $index"
            }

        val flattenedStructPaths = mutableListOf<List<CVLType.PureCVLType.Struct.StructEntry>>()
        paramType.flatten(flattenedStructPaths)

        val symToStructPath = syms.mapNotNull { sym ->
            sym `to?` (sym as? TACSymbol.Var)?.meta?.find(TACMeta.CVL_STRUCT_PATH)
        }

        val flattenedStructPathsWithValues =
            flattenedStructPaths.map { structPathFromParam ->
                val matchingSym = symToStructPath.singleOrNull { it.second.fields == structPathFromParam }
                if (matchingSym == null) {
                    structPathFromParam to CallTraceValue.Empty
                } else {
                    val (relevantSym, structPathFields) = matchingSym
                    val lastField = structPathFields.fields.last()
                    val fieldValue = relevantSym.let { model.valueAsTACValue(it) }
                    structPathFromParam to CallTraceValue.cvlCtfValueOrUnknown(fieldValue, lastField.cvlType)
                }
            }

        cvlFuncInfo.params[index] = Pair(param, CallTraceValue.CVLStructParam(flattenedStructPathsWithValues, param))
    }

    fun addCVLFunctionArrayParam(callIndex: Int, index: Int, param: CVLParam, lenSym: TACSymbol.Var) {
        val cvlFuncInfo = callIndexToCVLFunctionInfo[callIndex]
            ?: return logger.warn {
                "CVL function call $callIndex was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        val len = model.valueAsTACValue(lenSym)

        cvlFuncInfo.params[index] = Pair(param, CallTraceValue.CVLArrayParam(lenSym, len, param))
    }

    fun addCVLFunctionReturnValue(callIndex: Int, index: Int, type: CVLType.PureCVLType, sym: TACSymbol?) {
        val cvlFuncInfo = callIndexToCVLFunctionInfo[callIndex]
            ?: return logger.warn {
                "CVL function call $callIndex was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        cvlFuncInfo.rets[index] = Pair(type, sym)
    }

    fun finalizeCVLCall(call: CallInstance.InvokingInstance.PureCVLInvokingInstance) {
        val cvlFuncInfo = callIndexToCVLFunctionInfo[call.callIndex]
            ?: return logger.warn {
                "CVL call ${call.name} with index ${call.callIndex} was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        cvlFuncInfo.params.toSortedMap().forEachEntry { (index, paramToRep) ->
            val (param, rep) = paramToRep
            call.params.add(param)
            call.paramValues[index] = rep
        }

        cvlFuncInfo.rets.toSortedMap().forEachEntry { (index, typeToSym) ->
            val (typ, sym) = typeToSym
            call.returnTypes.add(typ)
            val value = sym?.let{ model.valueAsTACValue(it) }

            call.returnValues[index] = CallTraceValue.cvlCtfValueOrUnknown(value, typ)
        }
    }

    /**
     * writes the input/output values from [tacValues] to the appropriate property of [call].
     * this function is used for internal and internal summary calls, and the input/output
     * data for such functions is obtained from annotations.
     */
    fun writeDumpToCall(
        dump: Dump,
        tacValues: Map<Int, TACValue>,
        call: CallInstance.InvokingInstance.VMInvokingInstance
    ) {
        val target = when (dump) {
            is Dump.Parameters -> call.paramValues
            is Dump.ReturnValues -> call.returnValues
        }
        InternalFunctionStrategy(dump, tacValues).asMap().putAllIn(target)
    }

    /** Reports which values are missing, if any, to the regression [Logger]. Used for testing. */
    private fun validate(dump: Dump, missing: List<Int>, preamble: String) {
        for (idx in missing) {
            Logger.regression {
                val valueDesc = dump.longDescription(idx)
                val typ = dump.types[idx]
                "$preamble missing or incomplete value for $valueDesc with type $typ in value map"
            }
        }

        if (missing.isEmpty()) {
            Logger.regression {
                "$preamble all expected $dump found in value map"
            }
        }
    }

    /**
     * for external calls, we obtain parameters and return values by analyzing the [DataFamily]
     * and running the [CallTraceValueFormatter] with [LayoutDecoderStrategy].
     * we then get the corresponding value from the [model] and dump the information into [call].
     */
    fun finalizeExternalCall(call: SolidityInvokeInstance.External) {
        val callIndex = call.callIndex

        val mapping = callIndexToExternalCallMapping[callIndex]
            ?: return logger.warn {
                "External call ${call.name} with index $callIndex was not previously initialized, " +
                    "so parameters and return values will not be displayed."
            }

        val extra = mapping.extra
        val validatePreamble = "EXTERNAL CALL ${call.toStringWithoutParamValues()}:"

        val calldataStrategy = LayoutDecoderStrategy(
            extra.paramTypes,
            mapping.calldataFamily.toValueMap(model, DEFAULT_SIGHASH_SIZE),
            extra.paramNames
        )

        calldataStrategy.asMap().putAllIn(call.paramValues)

        validate(
            Dump.Parameters(extra.paramTypes, extra.paramNames),
            calldataStrategy.invalidDecodeIndices(),
            validatePreamble
        )

        /**
         * for return values: in constructors, the returndata buffer may not be empty,
         * since it contains the deployed bytecode.
         * we don't consider this data as "return values" and don't display them in the call trace.
         */
        if (call.isConstructor) {
            return
        }

        val returndataStrategy = LayoutDecoderStrategy(
            extra.returnTypes,
            mapping.returndataFamily.toValueMap(model, ZERO)
        )

        returndataStrategy.asMap().putAllIn(call.returnValues)

        validate(
            Dump.ReturnValues(extra.returnTypes),
            returndataStrategy.invalidDecodeIndices(),
            validatePreamble
        )
    }

    /** The metadata of the parameters or return values of a function, obtained from a summary */
    sealed class Dump(val types: List<VMTypeDescriptor>) {

        /**
         * A prefix of this dump kind at a specific index, for use when printing formatted values.
         * ex. "param_5"
         */
        abstract fun shortDescription(idx: Int): String

        /**
         * A longer description of the dump kind at a specific index, for use when logging.
         * ex. "return value with index 5", "parameter foo"
         */
        abstract fun longDescription(idx: Int): String

        class Parameters(types: List<VMTypeDescriptor>, val names: List<String>?) : Dump(types) {
            override fun shortDescription(idx: Int): String = "param_$idx"
            override fun longDescription(idx: Int): String {
                val name = names?.getOrNull(idx)?.takeUnless { it.isBlank() }
                return if (name != null) {
                    "parameter $name"
                } else {
                    "parameter with index $idx"
                }
            }

            override fun toString() = "parameters"
        }
        class ReturnValues(types: List<VMTypeDescriptor>) : Dump(types) {
            override fun shortDescription(idx: Int): String = "ret_$idx"
            override fun longDescription(idx: Int): String = "return value with index $idx"
            override fun toString() = "return values"
        }
    }
}

/**
 * This strategy is used to format internal and internal summary functions.
 * Argument data is taken from function summaries, and is contained in [dump].
 * [tacValues] is a map from an argument index to the corresponding solver value.
 */
private data class InternalFunctionStrategy(val dump: Dump, val tacValues: Map<Int, TACValue>) : FormatStrategy {

    /**
     * used to detect missing values in the available input/output data.
     *
     * let m be the number of input/output values in the function, according to the function signature.
     * it might be that some data is missing - that is, there exists some index k, 0 <= k < m,
     * such that k is not in [tacValues].
     *
     * [firstMissingIndex] is the minimal such k, or null if all data is available.
     */
    val firstMissingIndex: Int? = (0..dump.types.size).find { idx -> idx !in tacValues }

    override fun asMap(): Map<Int, CallTraceValue> =
        tacValues.mapValuesNotNull { (idx, tv) ->
            val type = dump.types.getOrNull(idx) ?: return@mapValuesNotNull null
            CallTraceValue.VMType(tv, type, paramName = prefix(idx))
        }

    fun prefix(idx: Int): String? {
        val name = dump.tryAs<Dump.Parameters>()?.names?.getOrNull(idx)

        return when {
            /** prefer to use a name if one is available. if it's blank, default to an actually useful name */
            name != null -> name.ifBlank { dump.shortDescription(idx) }

            /**
             * [idx] occurs after some index whose value is missing from [tacValues],
             * and since a name is not available, we must disambiguate this parameter
             */
            idx.isAmbiguous() -> dump.shortDescription(idx)

            /** no name available and no disambiguation needed */
            else -> null
        }
    }

    fun Int.isAmbiguous() = firstMissingIndex != null && this > firstMissingIndex
}

fun BigInteger.isByteAligned(alignment: BigInteger) = this >= ZERO && this.mod(EVM_WORD_SIZE) == alignment
fun BigInteger.expectAlignment(alignment: BigInteger) = takeIf { isByteAligned(alignment) }

/**
 * Gets the offset of a [loc] TACExpr, in case it is a TACExpr.Sym,
 * considering both the cases of constant and variable symbol representations.
 */
fun byteOffset(loc: TACExpr, model: CounterexampleModel): BigInteger? =
    when (val locSym = loc.tryAs<TACExpr.Sym>()?.s) {
        is TACSymbol.Const -> locSym.value
        is TACSymbol.Var -> model.valueAsBigInteger(locSym).leftOrNull()
        null -> null
    }

internal fun <K, V> Map<K, V>.putAllIn(other: MutableMap<K, V>) = other.putAll(this)
