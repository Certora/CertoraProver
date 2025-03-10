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

package report

import analysis.CmdPointer
import analysis.maybeNarrow
import datastructures.stdcollections.*
import evm.EVM_ADDRESS_SIZE
import log.*
import report.LocalAssignmentState.*
import report.LocalAssignmentState.CVLString.Companion.maxDisplayedLength
import report.calltrace.CallTrace
import report.calltrace.formatter.AlternativeRepresentations.Representations
import report.calltrace.formatter.AlternativeRepresentations.RepresentationsMap
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import report.calltrace.formatter.FormatterType
import report.calltrace.formatter.FormatterType.Companion.toFormatterType
import report.calltrace.formatter.FormatterType.Companion.toValueFormatterType
import report.calltrace.sarif.Sarif
import rules.ContractInfo
import rules.RuleCheckResult
import rules.RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample
import scene.IClonedContract
import scene.IContractWithSource
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.CVLDefinitionSite
import spec.CVLKeywords
import spec.cvlast.CVLRange
import spec.cvlast.CVLType
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import tac.Tag
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.state.TACValue
import java.math.BigInteger

private val logger = Logger(LoggerTypes.REPORT_UTILS)

/**
 * Resolves the value of the condition of this [TACCmd.Simple.AssertCmd].
 * Returns either true if this assert (Assert/AssertNot) has failed in [model],
 * or an exception if the value of the assert condition ([TACSymbol]) could not
 * be resolved in [model] (i.e., we treat the failure to get the value of an assert condition in the model, as error).
 */
fun TACCmd.Simple.AssertCmd.hasFailedInModel(model: CounterexampleModel, ptr: CmdPointer): Result<Boolean> {
    return model.valueAsTACValue(this.o)?.let { Result.success(it == TACValue.False) } ?: Result.failure(
        CertoraException(
            CertoraErrorType.COUNTEREXAMPLE,
            "Failed to resolve the value of an Assert command's condition in the model (${this.o} at $ptr)")
    )
}

data class LocalAssignment(
    val state: LocalAssignmentState,
    val formatter: CallTraceValueFormatter,
    val range: CVLRange?
) {
    val formattedValue: String get() = state.toSarif(formatter).flatten()

}

/**
 * An enumeration of the kinds of local assignments that may be displayed in the report.
 * In particular, it is intended to differentiate initialized from uninitialized values, since only the former actually requires formatting.
 * When a variable is initialized after the first failed assert, the SMT may assign it irrelevant value because the formula fails anyway.
 * In such case we want to consider it as [Uninitialized] and report it properly in reports.
 * When a variable is initialized and used in counter example, it is [Initialized] with its expression and type.
 *
 * (the interface is just for grouping)
 *
 * Everything under this interface may be displayed in the Variables Tab of the report.
 */
sealed interface LocalAssignmentState {
    val formatterType: FormatterType<*>

    fun toSarif(formatter: CallTraceValueFormatter): Sarif

    object Uninitialized: LocalAssignmentState {
        override fun toString() = "uninitialized"
        override val formatterType: FormatterType<*>
            get() = FormatterType.Value.Unknown("unknown type (of uninitialized value)") // does this end up being shown??

        override fun toSarif(formatter: CallTraceValueFormatter) =
            CallTraceValueFormatter.unusedValue(formatterType)
    }

    object InitializedButMissing: LocalAssignmentState {
        override fun toString() = "initialized to unknown"
        override val formatterType: FormatterType<*>
            get() = FormatterType.Value.Unknown("unknown type (of initialized-but-missing value)") // does this end up being shown??

        override fun toSarif(formatter: CallTraceValueFormatter) =
            CallTraceValueFormatter.unknown()
    }

    object ByteMap: LocalAssignmentState {
        override fun toString() = "bytemap initialized but unknown"
        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.CVL(CVLType.PureCVLType.DynamicArray.PackedBytes)

        override fun toSarif(formatter: CallTraceValueFormatter) =
            CallTraceValueFormatter.unknown()
    }

    /**
     * Displaying the contents of CVL strings, for that we need the map contents and the length.
     * If the length exceeds [maxDisplayedLength], we shorten and display `...` at the end.
     *
     * NB: We currently only read the fields from the [contents] map whose indices are multiples of 32.
     * This is not accurate in general, but works in simple cases (and usually we can't afford to run with precise
     * bytemap modeling anyway due to complexity...).
     */
    data class CVLString(
        val contents: TACValue.MappingValue.MapDefinition,
        val length: BigInteger,
    ): LocalAssignmentState {
        private val errorMsg get() = "(unable to show string contents)"

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.CVL(CVLType.PureCVLType.DynamicArray.StringType)

        private val stringRep by lazy {
            length.toIntOrNull()?.let { lengthInt ->
                val (displayLength, truncating) = if (lengthInt <= maxDisplayedLength) {
                    lengthInt to false
                } else {
                    maxDisplayedLength to true
                }

                var wholeStringAsBytes = "".toByteArray(Charsets.UTF_8)

                for (i in (0 until displayLength).step(32)) {
                    val readVal =
                        contents[listOf(TACValue.valueOf(i))]
                            .asBigIntOrNull() ?: return@lazy null

                    val readValBytes = readVal.toByteArray()
                    if (readValBytes.size > 32) {
                        return@lazy null
                    }

                    val readValBytesPaddedLeft = ByteArray(32 - readValBytes.size) + readValBytes

                    val readValTruncatedRight = if (lengthInt < i + 32) {
                        val bytesToKeep = (lengthInt % 32) // * 8
                        readValBytesPaddedLeft.copyOfRange(0, bytesToKeep)
                    } else {
                        readValBytesPaddedLeft
                    }
                    wholeStringAsBytes += readValTruncatedRight
                }
                val (string, utf8Succeeded) =
                    @Suppress("SwallowedException")
                    try {
                        // decodeToString doesn't need charset specified, just does utf8
                        wholeStringAsBytes.decodeToString(throwOnInvalidSequence = true) to true
                    } catch (e: CharacterCodingException) {
                        "(invalid UTF-8)" to false
                    }
                val postfix = ite(truncating && utf8Succeeded, "...", "")
                string + postfix
            }
        }

        override fun toSarif(formatter: CallTraceValueFormatter) =
            (
                stringRep?.let { strRep ->
                    Sarif.Arg(
                        RepresentationsMap(Representations.Pretty to strRep),
                        "CVL string",
                        type = formatterType.toTypeString(),
                        truncatable = false,
                    )
                } ?: CallTraceValueFormatter.unknownArg()
            ).asSarif

        override fun toString(): String = stringRep ?: errorMsg

        companion object {
            /** The maximum number of bytes to display in full for a given cvl string. */
            const val maxDisplayedLength = 10 * 32
        }
    }

    data class Initialized(val value: TACValue, val type: CVLType.PureCVLType?): LocalAssignmentState {
        override fun toString() = "initialized"
        override fun toSarif(formatter: CallTraceValueFormatter) =
            CallTraceValue.cvlCtfValueOrUnknown(value, type).toSarif(formatter, "initial value")

        override val formatterType: FormatterType<*>
            get() = type?.toFormatterType() ?: FormatterType.Value.Unknown("unknown type (initialized value)") // xxx
    }

    data class Contract(val value: TACValue.PrimitiveValue.Integer, val info: ContractInfo) : LocalAssignmentState {
        override fun toString() = "contract ${info.name} with instance ${info.instanceId}"
        override fun toSarif(formatter: CallTraceValueFormatter): Sarif =
            formatter.valueToSarif(
                value,
                // not declaring this as address here, since this is the one case where we want the value to be
                // formatted as a number
                EVMTypeDescriptor.UIntK(EVM_ADDRESS_SIZE).toValueFormatterType(),
                "contract address"
            ).let { sarif ->
                /** "special solution" to do what we'd normally do for addresses in the call trace value formatting:
                 *    addresses don't switch to decimal representation, but show hex in DEC mode
                 *  not the prettiest solution, but rather local, and should do for the time being */
                sarif.asArg()?.let { arg ->
                    val new = arg.values.toMutableMap()
                    runIf (new.size == Representations.values().size) {
                        new[Representations.Decimal] = new[Representations.Hex]!!
                        arg.copy(values = RepresentationsMap(new)).asSarif
                    }
                } ?: sarif
            }

        override val formatterType: FormatterType<*>
            get() = EVMTypeDescriptor.address.toValueFormatterType()
    }
}

/** Used for [HTMLReporter] (the old index.html page, not treeView) and [ConsoleReporter] (CLI summary table).  */
fun formatLocalAssignmentsForReport(counterExample: CounterExample?): String {
    if (counterExample == null) {
        return "no local variables"
    }

    return counterExample
        .localAssignments
        .toSortedMap()
        .map { (name, local) -> "$name=${local.formattedValue}" }
        .onEach { Logger.regression { it } }
        .joinToString(separator = "<br>")
}

private fun beforeAndAfterAssert(tacObject: CoreTACProgram, model: CounterexampleModel): Pair<Set<TACSymbol.Var>, Set<TACSymbol.Var>> {
    val beforeAssert = mutableSetOf<TACSymbol.Var>()
    val afterAssert = mutableSetOf<TACSymbol.Var>()
    var currSet = beforeAssert

    val topoSortedBlocks = tacObject.topoSortFw.filter { nbId -> nbId in model.reachableNBIds }
    require(topoSortedBlocks.isNotEmpty()) {
        "In ${tacObject.name}, expected a non-empty list of blocks reachable in the counter-example model"
    }

    val graph = tacObject.analysisCache.graph

    for (lcmd in graph.iterateFrom(CmdPointer(topoSortedBlocks.first(), 0), topoSortedBlocks)) {
        val stmt = lcmd.cmd

        if (stmt is TACCmd.Simple.AssertCmd && stmt.hasFailedInModel(model, lcmd.ptr).getOrThrow()) {
            currSet = afterAssert
        }

        lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.cmd?.lhs?.let { lhs ->
            val name = lhs.meta[TACMeta.CVL_DISPLAY_NAME] ?: lhs.namePrefix

            if (lhs.meta[TACMeta.CVL_DEF_SITE] is CVLDefinitionSite.Rule && CVLKeywords.find(name) == null) {
                currSet.add(lhs)
            }
        }
    }

    return beforeAssert to afterAssert
}

/** like `check`, but only warns, rather than throwing .. (might make this a function of our loggers?) */
fun checkWarn(cond: Boolean, msg: () -> String) {
    if (!cond) {
        logger.warn(msg)
    }
}

fun localAssignments(
    model: CounterexampleModel,
    program: CoreTACProgram,
    modelValueToContract: Map<TACValue.PrimitiveValue.Integer, ContractInfo>,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
): Map<String, LocalAssignment> {
    val locals = mutableMapOf<String, LocalAssignment>()

    fun TACSymbol.Var.name() = this.meta[TACMeta.CVL_DISPLAY_NAME] ?: this.namePrefix

    fun addLocal(name: String, state: LocalAssignmentState, range: CVLRange?) {
        locals[name] = LocalAssignment(state, formatter, range)
    }

    val (displayNameToLength, displayNameToContents) = collectCVLStringsSymbols(model.tacAssignments.keys)

    val (cvlVarsBeforeAssert, cvlVarsAfterAssert) = beforeAndAfterAssert(program, model)

    for (tv in cvlVarsAfterAssert) {
        addLocal(
            tv.name(),
            Uninitialized, // variables that were initialized after the failed assert contain junk values.
            tv.meta[TACMeta.CVL_DEF_SITE]?.range,
        )
    }

    for (tv in cvlVarsBeforeAssert) {
        val modelValue = model.valueAsTACValue(tv)
        val varType = tv.meta[TACMeta.CVL_TYPE]
        val displayName = tv.meta[TACMeta.CVL_DISPLAY_NAME]

        val state = when {
            displayNameToContents[displayName] == tv -> {
                checkWarn(tv.tag == Tag.ByteMap)
                { "unexpected case when trying to show CVL string \"$tv\": not a bytemap" }
                checkWarn(varType is CVLType.PureCVLType.DynamicArray.StringType)
                { "unexpected case when trying to show CVL string \"$tv\": not a CVL string type" }
                checkWarn(modelValue is TACValue.MappingValue.MapDefinition)
                { "unexpected case when trying to show CVL string \"$tv\": TACValue is not a map definition, got: \"$modelValue\"" }

                (modelValue as? TACValue.MappingValue.MapDefinition)?.let { mapContents ->
                    displayNameToLength[displayName]?.let { lengthSym ->
                        (model.valueAsTACValue(lengthSym) as? TACValue.PrimitiveValue)?.asBigInt?.let { lengthModelValue ->
                            CVLString(mapContents, lengthModelValue)
                        }
                    }
                } ?: ByteMap
            }

            tv.tag == Tag.ByteMap ->
                ByteMap

            modelValue != null -> {
                Initialized(modelValue, varType)
            }

            else -> {
                logger.info { "Variable ${tv.name()} is not in the model." }
                InitializedButMissing
            }
        }

        addLocal(tv.name(), state, tv.meta[TACMeta.CVL_DEF_SITE]?.range)
    }

    for ((modelValue, info) in modelValueToContract) {
        val contract = info.resolve(scene)
        val range = when (contract) {
            is IContractWithSource -> contract.src.sourceSegment()?.range
            is IClonedContract -> (scene.getContract(contract.sourceContractId) as? IContractWithSource)?.src?.sourceSegment()?.range
            else -> null
        }

        addLocal(
            info.name,
            Contract(modelValue, info),
            range,
        )
    }

    return locals
}


/**
 * For each CVL string, attempt to collect a length and a  content symbol, using the meta annotations.
 */
private fun collectCVLStringsSymbols(allSymbols: Set<TACSymbol.Var>)
        : Pair<Map<String, TACSymbol.Var>, Map<String, TACSymbol.Var>> {
    val displayNameToLength = mutableMapOf<String, TACSymbol.Var>()
    val displayNameToContents = mutableMapOf<String, TACSymbol.Var>()
    val displayNameToHavocMe = mutableMapOf<String, TACSymbol.Var>()
    allSymbols.forEach { sym ->
        when {
            TACMeta.CVL_LENGTH_OF in sym.meta -> {
                val displayName = sym.meta[TACMeta.CVL_LENGTH_OF]!! // just checked that it's there, so !! should be ok
                displayNameToLength[displayName] = sym
            }

            TACMeta.CVL_DATA_OF in sym.meta -> {
                val displayName = sym.meta[TACMeta.CVL_DATA_OF]!! // just checked that it's there, so !! should be ok
                displayNameToContents[displayName] = sym
            }

            TACMeta.TACSIMPLESIMPLE_HAVOCME in sym.meta && TACMeta.CVL_DISPLAY_NAME in sym.meta -> {
                val displayName =
                    sym.meta[TACMeta.CVL_DISPLAY_NAME]!! // just checked that it's there, so !! should be ok
                displayNameToHavocMe[displayName] = sym
            }
        }
    }
    // (making a copy to avoid concurrentModification)
    (displayNameToLength.keys - displayNameToContents.keys).toList().forEach { displayName ->
        // found a _length but missing _data -- must have been havocced only -- take the havocme-map for the contents
        val havocMe = displayNameToHavocMe[displayName]
        if (havocMe != null) {
            displayNameToContents[displayName] = havocMe
        }
    }
    return displayNameToLength to displayNameToContents
}

/** a kludge to maintain compatability with existing tests */
internal fun logLocalAssignments(localAssignments: Map<String, LocalAssignment>) {
    val assignmentsExceptContracts = localAssignments
        .filterValues { it.state !is Contract }
        .keys
        .sorted()

    Logger.regression { "The names of the local variables are $assignmentsExceptContracts" }
}

/**
 * Indicates whether the receiver list contains a [RuleCheckResult] that should
 * make the Prover end with a [FinalResult.DIAGNOSTIC_ERROR] notification and the corresponding exitcode.
 */
fun List<RuleCheckResult>.anyDiagnosticErrors(): Boolean =
    this.any {
        when (it) {
            is RuleCheckResult.Multi -> {
                it.results.anyDiagnosticErrors()
            }

            is RuleCheckResult.Single.Basic -> {
                it.ruleCheckInfo.details.isFailure
            }

            is RuleCheckResult.Single.WithCounterExamples -> {
                it.ruleCheckInfo.anyDiagnosticErrors()
            }

            is RuleCheckResult.Error, is RuleCheckResult.Skipped -> {
                false
            }
        }
    }

private fun RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.anyDiagnosticErrors(): Boolean = examples.any {
    it.assertSlice.isFailure ||
        it.callResolutionExampleMeta.isFailure ||
        it.details.isFailure ||
        it.callTrace is CallTrace.Failure
}
