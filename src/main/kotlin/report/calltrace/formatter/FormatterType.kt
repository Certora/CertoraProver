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

import spec.cvlast.CVLType.PureCVLType
import spec.cvlast.CVLType.PureCVLType.Primitive
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.*

/** Wraps types that can currently be formatted, to provide a shared interface for all such types
 *
 * Note (alex): FormatterType looks very rich in structure (basically wrapping the various CVL and EVM types), but
 * we're not actually doing that much with it (?) -- e.g. for CompoundType we just print the type name in
 * CallTraceFormatter.toUIString.. (right ?)
 * */
sealed class FormatterType<T>(val inner: T) {

    /**
     * Types which are guaranteed to be non-atomic, that is, composed of some number of [Value].
     * [EVMTypeDescriptor.EVMReferenceType] coincidentally has this property, which is a happy accident
     */
    sealed class Compound<T>(inner: T) : FormatterType<T>(inner) {
        class CVL(type: PureCVLType.CVLCompoundType) : Compound<PureCVLType.CVLCompoundType>(type)
        class EVM(type: EVMTypeDescriptor.EVMReferenceType) : Compound<EVMTypeDescriptor.EVMReferenceType>(type)

        /** Not clear if this is the future, but EVM types don't seem readily available in the [TypeDecode] context,
         * so doing this to fill in. (Whether we need more should be decided by a consumer, and this [Compound]
         * stuff doesn't seem used that much, seems to me, beyond for printing a type name) */
        class TypeDecode(typeName: String): Compound<String>(typeName)
        /** comments from [TypeDecode] apply */
        class Other(typeName: String): Compound<String>(typeName)
    }

    sealed class Function<T>(inner: T) : FormatterType<T>(inner) {
        class EVM(type: EVMTypeDescriptor.FunctionDescriptor) : Function<EVMTypeDescriptor.FunctionDescriptor>(type)
    }

    class RawArgs(type: PureCVLType.VMInternal.RawArgs) : FormatterType<PureCVLType.VMInternal.RawArgs>(type)

    /**
     * Types which are guaranteed to be representable by a single model value, and can be thought of as "formatting primitives".
     * Can be formatted by [CallTraceValueFormatter.formatValue].
     */
    sealed class Value<T>(inner: T) : FormatterType<T>(inner) {

        abstract fun toMeta() : ValueMeta?

        val isAddress get() = toMeta() == ValueMeta.Address

        val isBool get() = toMeta() == ValueMeta.Boolean
        val isBytes get() = toMeta() is ValueMeta.Bytes

        class CVL(type: PureCVLType.CVLValueType) : Value<PureCVLType.CVLValueType>(type) {
            override fun toMeta(): ValueMeta? = when (inner) {
                is Primitive.AccountIdentifier -> ValueMeta.Address
                is Primitive.BytesK -> ValueMeta.Bytes(inner.k)
                is Primitive.IntK -> ValueMeta.SignedIntK(inner.k)
                is Primitive.UIntK -> ValueMeta.UIntK(inner.k)
                is Primitive.Mathint -> ValueMeta.Mathint
                is PureCVLType.Enum -> ValueMeta.Enum(inner.name, inner.elements)
                is Primitive.Bool -> ValueMeta.Boolean
                else -> null
            }
        }

        class EVM(type: EVMTypeDescriptor.EVMValueType) : Value<EVMTypeDescriptor.EVMValueType>(type) {
            override fun toMeta(): ValueMeta? = when (inner) {
                is EVMTypeDescriptor.address,
                is EVMTypeDescriptor.EVMContractTypeDescriptor -> ValueMeta.Address
                is EVMTypeDescriptor.BytesK -> ValueMeta.Bytes(inner.bytewidth)
                is EVMTypeDescriptor.IntK -> ValueMeta.SignedIntK(inner.bitwidth)
                is EVMTypeDescriptor.UIntK -> ValueMeta.UIntK(inner.bitwidth)
                is EVMTypeDescriptor.EVMEnumDescriptor -> ValueMeta.Enum(inner.name, inner.elements)
                is EVMTypeDescriptor.bool -> ValueMeta.Boolean
                else -> null
            }
        }

        /** A placeholder for when we can't assign a proper [FormatterType].
         * [text] is just an internal message so we can track where this was created, we don't show it anywhere, I think.
         * */
        class Unknown(val text: String): Value<String>("unknown type($text)") {
            override fun toMeta(): ValueMeta? = null
        }
    }

    class UnsupportedPureCVL(inner: PureCVLType) : FormatterType<PureCVLType>(inner)

    companion object {
        fun PureCVLType.CVLValueType.toValueFormatterType() = Value.CVL(this)
        fun PureCVLType.toFormatterType() =
            when (this) {
                is PureCVLType.CVLCompoundType -> Compound.CVL(this)
                is PureCVLType.CVLValueType -> (this as PureCVLType.CVLValueType).toValueFormatterType()
                is PureCVLType.VMInternal.RawArgs -> RawArgs(this)
                else -> UnsupportedPureCVL(this)
            }

        /** like [toFormatterType], but useful for getting a narrower return type when the caller type is statically
         * known */
        fun EVMTypeDescriptor.EVMValueType.toValueFormatterType() = Value.EVM(this)

        fun EVMTypeDescriptor.toFormatterType() =
            when (this) {
                is EVMTypeDescriptor.EVMReferenceType -> Compound.EVM(this)
                is EVMTypeDescriptor.EVMValueType -> Value.EVM(this)
                is EVMTypeDescriptor.FunctionDescriptor -> Function.EVM(this)
                else -> `impossible!` // because kotlin fails to realize all cases have been covered
            }

        fun VMTypeDescriptor.toFormatterType() =
            when (this) {
                is EVMTypeDescriptor -> toFormatterType()
                else -> throw IllegalArgumentException("Only EVM types are currently supported")
            }
    }

    fun isCVL() = this is Compound.CVL || this is Value.CVL
    fun isEVM() = this is Compound.EVM || this is Value.EVM

    fun toTypeString(): String {
        // maybe we want to specialize this?
        return this.inner.toString()
    }
}

/**
 * A simplistic enumeration of expected types, generic over [FormatterType]
 * in order to reduce boilerplate and remove coupling between steps and type kind
 */
sealed class ValueMeta {
    data object Address : ValueMeta()
    /** Denotes a signed machine integer (in particular this does not denote a mathint, see [Mathint] for that). */
    data class SignedIntK(val bitwidth: Int) : ValueMeta()
    data class UIntK(val bitwidth: Int) : ValueMeta()
    data class Bytes(val bytewidth: Int) : ValueMeta()
    data object Mathint : ValueMeta()
    data class Enum(val name: String, val elements: List<String>) : ValueMeta()
    data object Boolean : ValueMeta()
}


