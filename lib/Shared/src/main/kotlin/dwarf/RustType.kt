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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dwarf

import annotations.PollutesGlobalState
import com.certora.collect.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import utils.*
import datastructures.stdcollections.*
import dwarf.RustType.Companion.resolve
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigInteger

@Treapable
@Serializable(with = RustTypeIdSerializer::class)
data class RustTypeId(val offset: ULong, val header_offest: ULong)

object RustTypeIdSerializer : KSerializer<RustTypeId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RustTypeId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RustTypeId) {
        encoder.encodeString("${value.offset}_${value.header_offest}")
    }

    override fun deserialize(decoder: Decoder): RustTypeId {
        val string = decoder.decodeString()
        @Suppress("ForbiddenMethodCall")
        val parts = string.split("_")
        require(parts.size == 2) { "Invalid RustTypeId format: $string" }
        return RustTypeId(
            offset = parts[0].toULong(),
            header_offest = parts[1].toULong()
        )
    }
}


@Treapable
@KSerializable
@JvmInline
value class Offset(val offset: ULong) : Comparable<Offset> {
    fun mod(divisor: ULong): Offset = Offset(offset % divisor)
    operator fun plus(other: Offset): Offset = Offset(offset + other.offset)
    operator fun minus(other: Offset): Offset = Offset(offset - other.offset)
    operator fun times(multiplier: ULong): Offset = Offset(offset * multiplier)
    operator fun div(divisor: ULong): Offset = Offset(offset / divisor)
    override operator fun compareTo(other: Offset): Int = offset.compareTo(other.offset)
    fun toBigInteger(): BigInteger = this.offset.toBigInteger()
    operator fun rangeUntil(other: Offset) = this.offset ..< other.offset
}


@Treapable
@Serializable
sealed class RustType(@Transient private val byte_size: ULong = 8U) : HasKSerializable {
    sealed interface PrimitiveType
    companion object {
        @PollutesGlobalState
        fun intializeRegistry(typeNodes: Map<RustTypeId, RustType>) {
            require(typeRegistry.isEmpty()){"The type registry should be initialized only once."}
            typeRegistry = typeNodes;
        }

        fun resolve(typeId: RustTypeId): RustType {
            return typeRegistry[typeId] ?: UNKNOWN
        }

        private var typeRegistry: Map<RustTypeId, RustType> = emptyMap()
    }

    open fun getByteSize() = byte_size

    @Serializable
    @SerialName("I8")
    data object I8 : RustType(1U), PrimitiveType

    @Serializable
    @SerialName("I16")
    data object I16 : RustType(2U), PrimitiveType

    @Serializable
    @SerialName("I32")
    data object I32 : RustType(4U), PrimitiveType

    @Serializable
    @SerialName("I64")
    data object I64 : RustType(), PrimitiveType

    @Serializable
    @SerialName("I128")
    data object I128 : RustType(16U), PrimitiveType

    @Serializable
    @SerialName("Isize")
    data object Isize : RustType(), PrimitiveType

    @Serializable
    @SerialName("U8")
    data object U8 : RustType(1U), PrimitiveType

    @Serializable
    @SerialName("U16")
    data object U16 : RustType(2U), PrimitiveType

    @Serializable
    @SerialName("U32")
    data object U32 : RustType(4U), PrimitiveType

    @Serializable
    @SerialName("U64")
    data object U64 : RustType(), PrimitiveType

    @Serializable
    @SerialName("U128")
    data object U128 : RustType(16U), PrimitiveType

    @Serializable
    @SerialName("Usize")
    data object Usize : RustType(8U), PrimitiveType

    // Unit type
    @Serializable
    @SerialName("Unit")
    data object Unit : RustType(0U) // () // Size?

    // Floating point types
    @Serializable
    @SerialName("F32")
    data object F32 : RustType(4U), PrimitiveType

    @Serializable
    @SerialName("F64")
    data object F64 : RustType(), PrimitiveType

    @Serializable
    @SerialName("Bool")
    data object Bool : RustType(1U), PrimitiveType

    // Struct types
    @Serializable
    @SerialName("Struct")
    data class Struct(
        val name: String,
        val fields: Set<StructField>,
        @SerialName("size")
        private val parsed_byte_size: ULong,
    ) : RustType() {
        override fun getByteSize(): ULong {
            return parsed_byte_size
        }
    }

    @Serializable
    @SerialName("Array")
    data class Array(
        private val element_type_id: RustTypeId,
        @SerialName("element_count")
        val numberOfElements: Int,
    ) : RustType() {
        override fun getByteSize(): ULong {
            return numberOfElements.toULong() * elementType().byte_size
        }

        fun elementType(): RustType {
            return resolve(element_type_id)
        }
    }

    @Serializable
    @SerialName("VariantPart")
    data class VariantPart(
        val discriminant: Discriminant,
        val variants: Set<Variant>,
        @SerialName("size")
        private val parsed_byte_size: ULong,
    ) : RustType() {
        override fun getByteSize(): ULong {
            return parsed_byte_size
        }
    }

    @Serializable
    @SerialName("Reference")
    data class Reference(
        private val referent_id: RustTypeId,
    ) : RustType() {
        fun referent(): RustType = resolve(referent_id)
    }

    @Serializable
    @SerialName("Enum")
    data class Enum(
        val name: String,
        val variants: List<EnumVariant>
    ) : RustType()

    @Serializable
    @SerialName("UNKNOWN")
    data object UNKNOWN : RustType()  // Size?

    fun asNonReferenceType(): RustType {
        return when (this) {
            is Reference -> this.referent()
            else -> this;
        }
    }

    fun flattenToOffsets(): Set<Offset> {
        return flattenToOffsetsRec(Offset(0UL));
    }

    private fun flattenToOffsetsRec(offset: Offset): Set<Offset> {
        return when (this) {
            is Struct -> {
                this.fields.flatMapToSet {
                    it.fieldType().flattenToOffsetsRec(offset + it.offset)
                }
            }

            is Array -> {
                (0 ..< this.numberOfElements).flatMapToSet { idx ->
                    this.elementType().flattenToOffsetsRec(offset + Offset(idx.toULong() * this.elementType().byte_size))
                }
            }

            is Enum -> setOf(Offset(0UL)) ////???
            is VariantPart -> {
                val discriminant = this.discriminant.rustType().flattenToOffsets()
                discriminant + this.variants.flatMapToSet {
                    it.variantType().flattenToOffsets()
                }
            }

            I128,
            U128 -> setOf(Offset(0UL), Offset(8UL))

            is Reference,
            UNKNOWN,
            Unit,
            is PrimitiveType -> setOf(offset)
        }
    }
}

@Treapable
@Serializable
data class Discriminant(
    private val rust_type_id: RustTypeId,
    val offset: Offset,
) {
    fun rustType() = resolve(rust_type_id)
}

@Treapable
@Serializable
data class StructField(
    val name: String,
    val offset: Offset,
    private val field_type_id: RustTypeId,
) {
    fun fieldType() = resolve(field_type_id)
}

@Treapable
@Serializable
data class Variant(
    val discr_name: String,
    val discr_value: ULong,
    private val rust_type_id: RustTypeId,
) {
    fun variantType() = resolve(rust_type_id)
}

@Treapable
@Serializable
data class EnumVariant(
    val name: String,
    @SerialName("variant_type")
    val variantType: EnumVariantType
)

@Treapable
@Serializable
sealed class EnumVariantType : HasKSerializable {
    @Serializable
    @SerialName("Unit")
    data object Unit : EnumVariantType()

    // Tuple and Struct enums are not yet supported.
}