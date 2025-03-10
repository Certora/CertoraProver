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

import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import report.calltrace.CallTrace
import report.calltrace.sarif.Sarif
import report.calltrace.sarif.SarifBuilder.Companion.joinToSarif
import report.calltrace.sarif.SarifBuilder.Companion.mkSarif
import spec.cvlast.abi.DataLayout
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.EVMTypeDescriptor.Companion.getDataLayout
import spec.cvlast.typedescriptors.EVMTypeDescriptor.EVMValueType
import spec.cvlast.typedescriptors.TerminalAction.Companion.sizeAsEncodedMember
import utils.filterAndIndex
import utils.keysMatching
import vc.data.state.TACValue
import java.math.BigInteger
import java.math.BigInteger.ZERO
import java.util.*


const val MAX_ARRAY_ELEMENTS_TO_DECODE = 10

/**
 * The result of running [LayoutDecoderStrategy] on the contents of a buffer:
 * either the values of the instance of single EVM type, or an error describing why decoding failed.
 *
 * This implementation is specifically for [CallTrace], to represent decoded values found in
 * calldata/returndata buffers. It defines [format] representations for each class of types,
 * and is used to print the contents of a buffer.
 *
 * This class is a work-in-progress. Specifically, dynamic types and multidimensional arrays
 * are currently not supported.
 */
sealed class TypeDecode {

    abstract fun format(formatter: CallTraceValueFormatter): Sarif

    abstract val formatterType: FormatterType<*>

    data class Primitive(val exp: TACValue, val typ: EVMValueType) : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter): Sarif =
            formatter.valueToSarif(exp, formatterType, "value")

        override val formatterType: FormatterType.Value<*>
            get() = FormatterType.Value.EVM(typ)
    }


    // left on purpose, may be relevant for multiple return values.
    /** Note that structs and tuples are equivalent as far as ABI encoding is concerned, so they share the same class. */
    data class Tuple(val components: List<TypeDecode>) : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter): Sarif =
            components.joinToSarif(separator = ", ", prefix = "(", postfix = ")") { _, t -> t.format(formatter) }

        override val formatterType: FormatterType<*>
            get() = throw UnsupportedOperationException("formatterType not implemented for Tuple ($this)")
    }

    data class NamedTuple(
        val components: List<Pair<String, TypeDecode>>
    ) : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter): Sarif {
            return components.joinToSarif(separator = ", ", prefix = "{", postfix = "}") { _, (compName, compVal) ->
                mkSarif {
                    append("${compName}=")
                    append(compVal.format(formatter))
                }
            }
        }

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.TypeDecode("named tuple") // xxx is this shown??
    }

    /**
     * [elements] is a map from the index in the array to the [TypeDecode] matching that index.
     *
     * currently, this is a static single-dimension array. any other arrays are decoded as
     * the stub value [Unsupported.Array].
     */
    data class Array(val elements: SortedMap<BigInteger, TypeDecode>) : TypeDecode() {

        override fun format(formatter: CallTraceValueFormatter) =
            elements.entries.toList()
                .joinToSarif(separator = ", ", prefix = "[", postfix = "]") { _, (arrayIndex, elem) ->
                    mkSarif {
                        append("${arrayIndex}=")
                        append(elem.format(formatter))
                    }
                }

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.TypeDecode("Array")
    }

    data class DynamicArray(val elements: SortedMap<BigInteger, TypeDecode>, val length: BigInteger) : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter): Sarif = mkSarif {
            append("[")
            var lastIndex: BigInteger? = null
            for ((idx, elem) in elements) {
                if (lastIndex != null) {
                    if (idx == lastIndex + BigInteger.ONE) {
                        append(", ")
                    } else {
                        append(", ..., ")
                    }
                }
                lastIndex = idx
                val formatted = elem.format(formatter)
                append("${idx}=")
                append(formatted)
            }
            append("] (length=$length)")
        }

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.TypeDecode("DynamicArray")
    }

    data class BytesArray(val isString: Boolean, val length: BigInteger?) : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter): Sarif =
            (if (isString) {
                "string"
            } else {
                "bytes"
            }).let { typeName ->
                mkSarif {
                    append("$typeName (length=")
                    append(
                        length?.let {
                            CallTraceValue.EVMType(TACValue.valueOf(it), EVMTypeDescriptor.UIntK(256))
                                .toSarif(formatter, "value")
                        } ?: CallTraceValueFormatter.unknown()
                    )
                    append(")")
                }
            }

        override val formatterType: FormatterType<*>
            get() = FormatterType.Compound.TypeDecode("BytesArray")
    }

    /** Currently, we don't attempt to decode any of these types. These are here for diagnostic purposes. */
    sealed class Unsupported(val typeDescriptor: EVMTypeDescriptor) : TypeDecode() {

        override fun format(formatter: CallTraceValueFormatter) = CallTraceValueFormatter.unknown()

        /** Multidimensional arrays and any dynamic array are not supported */
        class Array(typeDescriptor: EVMTypeDescriptor.DynamicArrayDescriptor) : Unsupported(typeDescriptor) {
            override fun format(formatter: CallTraceValueFormatter) =
                Sarif.fromPlainStringUnchecked(typeDescriptor.prettyPrint())

            override val formatterType: FormatterType<*>
                get() = FormatterType.Compound.TypeDecode("Unsupported.Array")
        }

        class Mapping(typeDescriptor: EVMTypeDescriptor.EVMMappingDescriptor) : Unsupported(typeDescriptor) {
            override fun format(formatter: CallTraceValueFormatter) =
                Sarif.fromPlainStringUnchecked(typeDescriptor.prettyPrint())

            override val formatterType: FormatterType<*>
                get() = FormatterType.Compound.TypeDecode("Unsupported.Mapping")
        }

        /** Miscellaneous types that we don't expect to see */
        class Unexpected(typeDescriptor: EVMTypeDescriptor) : Unsupported(typeDescriptor) {
            override val formatterType: FormatterType<*>
                get() = FormatterType.Compound.TypeDecode("Unsupported.Unexpected")
        }
    }

    /** Type decode could not continue because the corresponding [TACValue] was not found in the value map. */
    data object UnknownValue : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter) = CallTraceValueFormatter.unknown()
        override val formatterType: FormatterType<*>
            get() = FormatterType.Value.Unknown("TypeDecode UnknownValue")
    }

    /** [TACValue] was havoced and can be any value. */
    data object HavocValue : TypeDecode() {
        override fun format(formatter: CallTraceValueFormatter) = CallTraceValueFormatter.unusedValue(formatterType)
        override val formatterType: FormatterType<*>
            get() = FormatterType.Value.Unknown("TypeDecode HavocValue")
    }
}


/**
 * A [FormatStrategy] which decodes the values of a buffer, in accordance with the EVM ABI specification.
 *
 * [byteOffsetToModelValue] is a map from a byte offset of the calldata/returndata buffer to
 * the [TACValue] of the data in that offset.
 * [paramTypes] are the type descriptors of the function's parameters or return values,
 * in order, taken from the function signature. [names] are the names of these descriptors, if applicable.
 *
 * [format] returns a map from the index of a parameter/return value to a formatted string of this value.
 */
data class LayoutDecoderStrategy(
    val paramTypes: List<EVMTypeDescriptor>,
    val byteOffsetToModelValue: Map<BigInteger, TACValue>,
    val names: List<String>? = null
) : FormatStrategy {
    private val decoded = decode(byteOffsetToModelValue, paramTypes)

    private fun decode(
        byteOffsetToValue: Map<BigInteger, TACValue>,
        descriptors: List<EVMTypeDescriptor>
    ): List<TypeDecode> {
        val decodedValues = mutableListOf<TypeDecode>()
        var offs = ZERO

        for (ty in descriptors) {
            val dataLayout = ty.getDataLayout().resultOrNull()
            val decodedValue = if(dataLayout == null) {
                when(ty) {
                    is EVMTypeDescriptor.EVMMappingDescriptor -> TypeDecode.Unsupported.Mapping(ty)
                    else -> TypeDecode.Unsupported.Unexpected(ty)
                }
            } else {
                ConcreteLayoutDecoder(
                    byteOffsetToValue,
                    currentPointer = offs,
                    savedScope = ZERO,
                    knownLength = null
                ).interpret(dataLayout)
            }
            offs += ty.sizeAsEncodedMember()
            decodedValues.add(decodedValue)
        }

        return decodedValues
    }

    override fun asMap(): Map<Int, CallTraceValue> =
        decoded.withIndex().associate { (ind, decodedValue) ->
            val prefix = names?.getOrNull(ind)?.ifBlank { "param_$ind" }
            ind to CallTraceValue.DecodedBuffer(decodedValue, prefix)
        }


    /** used for regTest validation */
    fun invalidDecodeIndices(): List<Int> {
        /** change this if we decide that more types are invalid */
        val isFailure: (TypeDecode) -> Boolean = { it is TypeDecode.UnknownValue }

        return decoded.filterAndIndex(isFailure).map { it.index }
    }



}

private class ConcreteLayoutDecoder(
    val byteOffsetToModelValue: Map<BigInteger, TACValue>,
    val currentPointer: BigInteger,
    val savedScope: BigInteger?,
    val knownLength: BigInteger?
) {
    fun interpret(w: DataLayout<EVMValueType>) : TypeDecode {
        return when(w) {
            is DataLayout.DynamicPointer -> {
                byteOffsetToModelValue[currentPointer]?.let {
                    it as? TACValue.PrimitiveValue.Integer
                }?.value?.let { relative ->
                    savedScope?.let(relative::add)
                }?.let { newPointer ->
                    ConcreteLayoutDecoder(
                        byteOffsetToModelValue, newPointer, null, null
                    ).interpret(w.next)
                } ?: TypeDecode.UnknownValue
            }
            is DataLayout.LengthTaggedTuple -> {
                val length = byteOffsetToModelValue[currentPointer]?.let { it as? TACValue.PrimitiveValue.Integer }?.value ?: return TypeDecode.UnknownValue
                ConcreteLayoutDecoder(
                    byteOffsetToModelValue, currentPointer + EVM_WORD_SIZE, null, length
                ).interpret(w.elems)
            }
            is DataLayout.OpenScope -> {
                ConcreteLayoutDecoder(
                    byteOffsetToModelValue, currentPointer, savedScope = currentPointer, knownLength = this.knownLength
                ).interpret(
                    w.next
                )
            }
            is DataLayout.SequenceOf -> {
                when(val se = w.sequenceElements) {
                    is DataLayout.SequenceElement.Elements -> {
                        if(knownLength == null) {
                            return TypeDecode.UnknownValue
                        }
                        val memSize = se.dataLayout.sizeAsEncodedMember()
                        val dataEndRange = (memSize * knownLength) + currentPointer
                        val elements = mutableMapOf<BigInteger, TypeDecode>()
                        val elemKeys = byteOffsetToModelValue.keysMatching { it, _ ->
                            (it >= currentPointer && it < dataEndRange) && (it - currentPointer).mod(memSize) == ZERO
                        }.sortedBy {
                            it
                        }.take(MAX_ARRAY_ELEMENTS_TO_DECODE)
                        for(ind in elemKeys) {
                            val logicalIndex = (ind - currentPointer) / memSize
                            elements[logicalIndex] = ConcreteLayoutDecoder(
                                byteOffsetToModelValue, ind, savedScope, null
                            ).interpret(se.dataLayout)
                        }
                        return TypeDecode.DynamicArray(
                            length = knownLength,
                            elements = elements.toSortedMap()
                        )
                    }
                    is DataLayout.SequenceElement.PackedBytes1 -> {
                        TypeDecode.BytesArray(
                            isString = se.isString,
                            length = knownLength
                        )
                    }
                }
            }
            is DataLayout.StaticRepeatedOf -> {
                val elements = mutableMapOf<BigInteger, TypeDecode>()
                val endPoint = currentPointer + w.num * w.elem.sizeAsEncodedMember()
                var it = currentPointer
                var elemIt = ZERO
                var numDecoded = 0
                while(it < endPoint && numDecoded < MAX_ARRAY_ELEMENTS_TO_DECODE) {
                    val elem = ConcreteLayoutDecoder(
                        byteOffsetToModelValue, it, savedScope, null
                    ).interpret(w.elem)
                    if(elem !is TypeDecode.UnknownValue) {
                        elements[elemIt] = elem
                        numDecoded++
                    }
                    elemIt++
                    it += w.elem.sizeAsEncodedMember()
                }
                return TypeDecode.Array(
                    elements =  elements.toSortedMap()
                )
            }
            is DataLayout.Terminal -> {
                when (val value = byteOffsetToModelValue[currentPointer]) {
                    null -> TypeDecode.UnknownValue
                    TACValue.Uninitialized -> TypeDecode.HavocValue
                    else -> TypeDecode.Primitive(exp = value, typ = w.t)
                }

            }
            is DataLayout.TupleOf -> {
                var offsetIt = currentPointer
                val elements = mutableListOf<Pair<String, TypeDecode>>()
                for((k, v) in w.elements) {
                    elements.add(k to ConcreteLayoutDecoder(
                        byteOffsetToModelValue, offsetIt, savedScope, knownLength = null
                    ).interpret(v))
                    offsetIt += v.sizeAsEncodedMember()
                }
                return TypeDecode.NamedTuple(elements)
            }
        }
    }

}

