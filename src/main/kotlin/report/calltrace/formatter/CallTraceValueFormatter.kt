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

package report.calltrace.formatter

import log.*
import report.BigIntPretty
import report.calltrace.CallTrace
import report.calltrace.formatter.FormatterType.Companion.toFormatterType
import report.globalstate.DisplaySymbolWrapper
import spec.cvlast.CVLParam
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.state.TACValue
import datastructures.stdcollections.*
import report.calltrace.formatter.AlternativeRepresentations.Representations
import report.calltrace.formatter.AlternativeRepresentations.RepresentationsMap
import report.calltrace.formatter.CallTraceValueFormatter.Companion.unknown
import report.calltrace.sarif.Sarif
import report.calltrace.sarif.SarifBuilder.Companion.joinToSarif
import report.calltrace.sarif.SarifBuilder.Companion.mkSarif
import rules.ContractInfo
import solver.CounterexampleModel
import utils.*
import java.math.BigInteger

internal val logger = Logger(LoggerTypes.CALLTRACE)

/** Handles pretty-printing values for the [CallTrace].
 *
 * Future work: in the spirit of "doing the same thing only one way", the methods of this class should only be called
 * from [CallTraceValue.toSarif] methods; then everything goes through [CallTraceValue] (and perhaps we can streamline
 * that class hierarchy some way down the line).
 */
class CallTraceValueFormatter(
        private val model: CounterexampleModel,
        private val addrToContract: Map<TACValue.PrimitiveValue.Integer, ContractInfo> = mapOf(),
    ) {

    companion object {

        val UNKNOWN_VALUE_STR: String
            get() {
                TestLoggers.CallTrace.noXs?.foundX = true
                return "..."
            }
        const val UNKNOWN_VALUE_TOOLTIP = "cannot display this value"
        const val SUMMED_INDEX_VALUE = "*"

        const val DONT_CARE_VALUE_STR = "*"
        const val DONT_CARE_VALUE_TOOLTIP = "unused value"

        /** compute the tooltip for variables tab variables
         * would be nicer to not look at the string, but should do for now, since the current
         * rule for getting the tooltip is rather simple */
        fun variableTooltip(valueStr: String) = when (valueStr) {
            DONT_CARE_VALUE_STR -> DONT_CARE_VALUE_TOOLTIP
            UNKNOWN_VALUE_STR -> UNKNOWN_VALUE_TOOLTIP
            else -> "copy value"
        }

        fun unusedValue(type: FormatterType<*>) = Sarif.Arg(
            values = RepresentationsMap(Representations.Pretty to DONT_CARE_VALUE_STR),
            tooltip = DONT_CARE_VALUE_TOOLTIP,
            type = type.toTypeString(),
            truncatable = false,
        ).asSarif

        fun summed(type: FormatterType<*>) = Sarif.Arg(
            values = RepresentationsMap(Representations.Pretty to SUMMED_INDEX_VALUE),
            tooltip = "summed over this index",
            type = type.toTypeString(),
            truncatable = false,
        ).asSarif

        fun unknownArg() = Sarif.Arg(
            values = RepresentationsMap(Representations.Pretty to UNKNOWN_VALUE_STR),
            tooltip = UNKNOWN_VALUE_TOOLTIP,
            type = FormatterType.Value.Unknown("type of unknown value").toTypeString(),
            truncatable = false,
        )

        fun unknown() = unknownArg().asSarif
    }


    fun valueToSarif(tv: TACValue, type: FormatterType.Value<*>, tooltip: String): Sarif {
        val (values, truncatable) = ValueFormattingJob(tv, type, addrToContract, model).run()
        return Sarif.Arg(values, tooltip, type.toTypeString(), truncatable).asSarif
    }

    fun functionToSarif(tv: TACValue, type: FormatterType.Function<*>, tooltip: String): Sarif {
        unused(tv); unused(tooltip) // we may want to use them some time in the near future
        return Sarif.fromPlainStringUnchecked(type.inner.toString())
    }

    fun compoundToSarif(tv: TACValue, type: FormatterType.Compound<*>, tooltip: String): Sarif {
        unused(tv); unused(tooltip) // we may want to use them some time in the near future
        return Sarif.fromPlainStringUnchecked(type.inner.toString())
    }

    /**
     * Formats a value based on its type.
     * [FormatterType.Value] types (types which are not "compounds", such as primitives) are formatted by
     * running a series of [FormatterStep], optionally by also utilizing the [TACValue] corresponding to that type.
     * [FormatterType.Compound] types (such as structs) are simply pretty-printed, which is the best we can do here.
     * A [default] string can also be supplied, to be used in case no useful value can be extracted from the [TACValue].
     * If the value is havoced, [DONT_CARE_VALUE_STR] is returned.
     * On failure, if [default] was supplied it is returned, otherwise [UNKNOWN_VALUE_STR] is returned.
     */
    fun toSarif(tv: TACValue, type: FormatterType<*>, tooltip: String): Sarif {

        if (tv == TACValue.Uninitialized) {
            return unusedValue(type)
        }

        if (tv == TACValue.SumIndex) {
            return summed(type)
        }

        return when (type) {
            is FormatterType.Compound -> compoundToSarif(tv, type, tooltip)

            is FormatterType.Function -> functionToSarif(tv, type, tooltip)

            is FormatterType.Value -> valueToSarif(tv, type, tooltip)

            is FormatterType.RawArgs -> unknown()

            is FormatterType.UnsupportedPureCVL -> {
                logger.info { "CallTraceFormatter got type ${type.inner} which is currently unsupported for formatting" }
                unknown()
            }
        }
    }
}

/** Allows for custom, context-dependent formatting algorithms */
interface FormatStrategy {
    fun asMap(): Map<Int, CallTraceValue>
}

/** Holds various representations of counterexample values.
 * Has enough information so that a UI string can be generated using only a [CallTraceValueFormatter].
 *
 * In case of scalar values, also holds a [TACValue], which can be used to print alternative representations,
 * using [BigIntPretty].
 */
sealed interface CallTraceValue {
    val scalarValue: TACValue?
    fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif

    val formatterType: FormatterType<*>

    /** Our traditional (read: should be deprecated) alternative way of passing the name of the parameter that the value
     * represented by this [CallTraceValue] is associated with.
     * (The other ways we have for that purpose look cleaner -- something to consolidate in time.)
     * Note that this string comes without the `=` (at some point it may have contained it, but no more). */
    val paramName: String? get() = null

    companion object {
        fun evmCtfValueOrUnknown(
            scalarValue: TACValue?,
            type: EVMTypeDescriptor,
            default: String? = null
        ) = scalarValue?.let { EVMType(it, type, default) }
            ?: Empty // could pass the info we have to unknow perhaps (?)

        fun vmCtfValueOrUnknown(
            scalarValue: TACValue?,
            type: VMTypeDescriptor?,
            default: String? = null
        ) = scalarValue?.let { v ->
            type?.let { t ->
                VMType(v, t, default)
            } ?: UnknownType(scalarValue, "unknown VMTypeDescriptor")
        } ?: Empty // could pass the info we have to unknow perhaps (?)

        fun cvlCtfValueOrUnknown(
            scalarValue: TACValue?,
            type: spec.cvlast.CVLType.PureCVLType?,
        ) = scalarValue?.let { v ->
            type?.let {t ->
                CVLType(v, t)
            } ?: UnknownType(scalarValue, "unknown VMTypeDescriptor")
        } ?: Empty // could pass the info we have to unknow perhaps (?)
    }

    data object Empty : CallTraceValue {
        override val scalarValue: TACValue? get() = null
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String) = unknown()
        override val formatterType get() = FormatterType.Value.Unknown("type of empty/unknown value")
    }

    data class UnknownType(
        override val scalarValue: TACValue,
        val desc: String // message string for unknown type (dev purposes)
    ) : CallTraceValue {
        override val formatterType get() = FormatterType.Value.Unknown(desc)
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String) =
            formatter.valueToSarif(scalarValue, formatterType, tooltip)
    }

    data class CVLType(
        override val scalarValue: TACValue,
        val type: spec.cvlast.CVLType.PureCVLType,
    ) : CallTraceValue {
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String) =
            formatter.toSarif(scalarValue, type.toFormatterType(), tooltip)

        override val formatterType get() = type.toFormatterType()
    }

    data class EVMType(
        override val scalarValue: TACValue,
        val type: EVMTypeDescriptor,
        val default: String? = null
    ) : CallTraceValue {
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String) =
            formatter.toSarif(scalarValue, type.toFormatterType(), tooltip)

        override val formatterType: FormatterType<*>
            get() = type.toFormatterType()
    }

    data class VMType(
        override val scalarValue: TACValue,
        val type: VMTypeDescriptor,
        val default: String? = null,
        override val paramName: String? = null,
    ) : CallTraceValue {
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            formatter.toSarif(scalarValue, type.toFormatterType(), tooltip)

        override val formatterType: FormatterType<*>
            get() = type.toFormatterType()
    }

    data class CVLArrayParam(val lenSym: TACSymbol.Var, val len: TACValue?, val param: CVLParam) :
        CallTraceValue {
        override val scalarValue: TACValue?
            get() = null

        override val formatterType: FormatterType<*>
            get() = param.type.toFormatterType()

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif {
            val lenType = lenSym.meta[TACMeta.CVL_TYPE]
            val lenRep = cvlCtfValueOrUnknown(len, lenType).toSarif(formatter, tooltip)

            val rep = when (param.type) {
                is spec.cvlast.CVLType.PureCVLType.StaticArray -> Sarif.fromPlainStringUnchecked(param.type.toString())
                else ->
                    mkSarif {
                        append("${param.type} (length=")
                        append(lenRep)
                        append(")")
                    }
            }
            return rep
        }
    }

    data class CVLStructParam(
        val flattenedStructPathsWithValues: List<Pair<List<spec.cvlast.CVLType.PureCVLType.Struct.StructEntry>, CallTraceValue>>,
        val param: CVLParam
    ) : CallTraceValue {
        override val scalarValue: TACValue?
            get() = null

        override val formatterType: FormatterType<*>
            get() = param.type.toFormatterType()

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            flattenedStructPathsWithValues.joinToSarif(
                separator = ", ",
                prefix = "{",
                postfix = "}"
            ) { _, (structPath, value) ->
                mkSarif {
                    val sPath = structPath.joinToString(".")
                    append("$sPath=")
                    append(value.toSarif(formatter, "value at $sPath"))
                }
            }
    }

    data class MoveReference(val value: CallTraceValue) : CallTraceValue {
        override val scalarValue: TACValue? get() = value.scalarValue
        override val formatterType: FormatterType<*> get() = value.formatterType
        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif {
            return mkSarif {
                append("&")
                append(value.toSarif(formatter, tooltip))
            }
        }
    }

    data class MoveStruct(val fields: List<Pair<String, CallTraceValue>>) : CallTraceValue {
        override val scalarValue: TACValue? get() = null
        override val formatterType: FormatterType<*> get() = FormatterType.Value.Unknown("MoveStruct")

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            fields.joinToSarif(
                separator = ", ",
                prefix = "{",
                postfix = "}"
            ) { _, (fieldName, value) ->
                mkSarif {
                    append("$fieldName:")
                    append(value.toSarif(formatter, "$tooltip.$fieldName"))
                }
            }
    }

    data class MoveEnum(val variantName: String, val fields: List<Pair<String, CallTraceValue>>) : CallTraceValue {
        override val scalarValue: TACValue? get() = null
        override val formatterType: FormatterType<*> get() = FormatterType.Value.Unknown("MoveEnum")

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            if (fields.isEmpty()) {
                // No fields; put the tooltip on the variant name
                mkSarif {
                    append(Sarif.Arg(
                        values = RepresentationsMap(Representations.Pretty to variantName),
                        tooltip = tooltip,
                        type = "",
                        truncatable = false,
                    ))
                }
            } else {
                // Individual field values will have their own tooltips
                fields.joinToSarif(
                    separator = ", ",
                    prefix = "$variantName {",
                    postfix = "}"
                ) { _, (fieldName, value) ->
                    mkSarif {
                        append("$fieldName:")
                        append(value.toSarif(formatter, "$tooltip.$fieldName"))
                    }
                }
            }
    }

    data class MoveVector(val length: BigInteger, val elements: List<CallTraceValue>) : CallTraceValue {
        override val scalarValue: TACValue? get() = null
        override val formatterType: FormatterType<*> get() = FormatterType.Value.Unknown("MoveVector")

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            mkSarif {
                append("[")
                elements.take(length.toIntOrNull() ?: elements.size).forEachIndexed { i, it ->
                    if (i > 0) {
                        append(", ")
                    }
                    append(it.toSarif(formatter, "$tooltip[$i]"))
                }
                if (length > elements.size.toBigInteger()) {
                    if (elements.size > 0) {
                        append(", ")
                    }
                    append(Sarif.Arg(
                        values = RepresentationsMap(Representations.Pretty to "..."),
                        tooltip = "$length total elements",
                        type = "",
                        truncatable = false,
                    ))
                }
                append("]")
            }
    }

    data class MoveArg(val name: String, val value: CallTraceValue) : CallTraceValue {
        override val scalarValue: TACValue? get() = value.scalarValue
        override val formatterType: FormatterType<*> get() = value.formatterType

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif =
            mkSarif {
                append(value.toSarif(formatter, name))
            }
    }

    fun checkNoPrefix() {
        if (paramName != null) {
            logger.warn { "seeing a prefix \"${paramName}\" in a place where we won't print it" }
        }
    }

    data class DecodedBuffer(val decodedValue: TypeDecode, override val paramName: String?) : CallTraceValue {
        override val scalarValue: TACValue?
            get() = when (decodedValue) {
                is TypeDecode.Primitive -> decodedValue.exp
                else -> null
            }

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.TypeDecode("decoded buffer") // will this be printed??

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String) = decodedValue.format(formatter)
    }

    data class DSW(val dsw: DisplaySymbolWrapper) : CallTraceValue {
        override val scalarValue: TACValue?
            get() = dsw.value

        override fun toSarif(formatter: CallTraceValueFormatter, tooltip: String): Sarif {
            // ignoring dsw.name -- storage path is added in other places (so far at least)
            return scalarValue?.let { formatter.toSarif(it, formatterType, tooltip) } ?: unknown()
        }

        override val formatterType: FormatterType<*>
            get() = dsw.formatterType ?: FormatterType.Value.Unknown("DSW-unknown")
    }
}
