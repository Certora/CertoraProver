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

@file:UseSerializers(BigIntegerDecimalSerializer::class)
package bridge.types

import bridge.BigIntegerDecimalSerializer
import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import spec.cvlast.CVLType
import spec.cvlast.SolidityContract
import spec.cvlast.typedescriptors.EVMLocationSpecifier
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.reduceErrors
import java.math.BigInteger

@Serializable
@Treapable
sealed class DescriptionAnnotation : AmbiSerializable {

    @Serializable
    @SerialName("StorageAnnotation")
    data class StorageAnnotation(
        val numberOfBytes: BigInteger, val slot: BigInteger? = null, val offset: Int? = null,
        val lowerBound: BigInteger? = null, val upperBound: BigInteger? = null

    ) : DescriptionAnnotation()
}

/**
 * An entry for the type of member element inside a container struct type
 */
@Serializable
@Treapable
data class StructTypeMemberType(
    val name: String,
    val type: SolidityTypeDescription
) : java.io.Serializable

/**
 * An entry for the members of an enum
 */
@Serializable
@Treapable
data class EnumMember(
    val name: String
) : java.io.Serializable

/**
 * Value locations for type instances
 * I guess will make it a LocationSpecifier for now?
 */
@Suppress("EnumEntryName")
enum class SolcLocation {
    stack,
    memory,
    calldata,
    storage
}

/**
 * Enum that corresponds to Solidity's primitive types.
 * Note that 'int' and 'uint' are omitted because we expect certoraBuild
 * to normalize those to 'int256' and 'uint256', respectively.
 */
@Suppress("EnumEntryName")
enum class PrimitiveType {
    uint8, uint16, uint24, uint32, uint40, uint48, uint56, uint64, uint72, uint80,
    uint88, uint96, uint104, uint112, uint120, uint128, uint136, uint144, uint152, uint160,
    uint168, uint176, uint184, uint192, uint200, uint208, uint216, uint224, uint232, uint240,
    uint248, uint256,
    int8, int16, int24, int32, int40, int48, int56, int64, int72, int80,
    int88, int96, int104, int112, int120, int128, int136, int144, int152, int160,
    int168, int176, int184, int192, int200, int208, int216, int224, int232, int240,
    int248, int256,
    bytes1, bytes2, bytes3, bytes4, bytes5, bytes6, bytes7, bytes8, bytes9, bytes10,
    bytes11, bytes12, bytes13, bytes14, bytes15, bytes16, bytes17, bytes18, bytes19, bytes20,
    bytes21, bytes22, bytes23, bytes24, bytes25, bytes26, bytes27, bytes28, bytes29, bytes30,
    bytes31, bytes32,
    byte, address, bool, uint, int,
    nonreentrant_lock
    ;

    private operator fun minus(dec: PrimitiveType) = ordinal - dec.ordinal

    companion object {
        init {
            check(uint256 - uint8 == 31 && int256 - int8 == 31 && bytes32 - bytes1 == 31) {
                "PrimitiveType enum isn't ordered correctly, so the 'toDescriptor' function is erroneous"
            }
        }

        val labelToType = values().associateBy { it.name }
    }

    fun toDescriptor(): EVMTypeDescriptor.EVMValueType =
        when (this) {
            in uint8..uint256 -> EVMTypeDescriptor.UIntK((this - uint8 + 1) * 8)
            in int8..int256 -> EVMTypeDescriptor.IntK((this - int8 + 1) * 8)
            in bytes1..bytes32 -> EVMTypeDescriptor.BytesK(this - bytes1 + 1)
            bool -> EVMTypeDescriptor.bool
            byte -> EVMTypeDescriptor.BytesK(1)
            address -> EVMTypeDescriptor.address
            uint -> EVMTypeDescriptor.UIntK(256)
            int -> EVMTypeDescriptor.IntK(256)
            nonreentrant_lock -> EVMTypeDescriptor.BytesK(32)
            else -> `impossible!`
        }
}

/**
 * For deserializing the types collected by certoraBuild
 * see Type.as_dict() in certoraType.py for mapping from types to json rep
 *
 * Note - The "type" field in the dict is what's used by Kotlin's serializers as
 * the default discriminator field, so by setting the [@SerialName] annotation
 * here to correspond with that field in the jsons, we get this polymorphic
 * deserialization without requiring any fancy custom serializers (once we
 * update [kotlinx.serialization] to a version >= 1.3 we will be able to change
 * this if we want thanks to the introduction of the [@JsonClassDiscriminator]
 * annotation in that version).
 */
@Serializable
@Treapable
sealed class SolidityTypeDescription : AmbiSerializable {

    val annotations : List<DescriptionAnnotation> = listOf()

    @Serializable
    @SerialName("Primitive")
    data class Primitive(val primitiveName: PrimitiveType) : SolidityTypeDescription() {
        override fun hashCode() = hash { it + primitiveName }
        override fun toString() = primitiveName.name

        fun toDescriptor() = primitiveName.toDescriptor()
    }

    @Serializable
    @SerialName("StringType")
    class StringType : SolidityTypeDescription() {
        fun toDescriptor(location: EVMLocationSpecifier?) =
            EVMTypeDescriptor.StringType(location)

        override fun toString() = "string"

        override fun hashCode() = hashObject(this)

        override fun equals(other: Any?) = other is StringType
    }

    @Serializable
    @SerialName("PackedBytes")
    class PackedBytes : SolidityTypeDescription() {

        fun toDescriptor(location: EVMLocationSpecifier?) =
            EVMTypeDescriptor.PackedBytes(location)

        override fun toString() = "bytes"

        override fun hashCode() = hashObject(this)

        override fun equals(other: Any?) = other is PackedBytes
    }

    @Serializable
    @SerialName("Contract")
    data class Contract(val contractName: String) : SolidityTypeDescription() {
        override fun toString() = "address"
    }

    @Serializable
    sealed class UserDefined : SolidityTypeDescription() {
        // The contract in which the type is declared, [null] if declared outside of contract scope (i.e. at file level)
        abstract val containingContract: String?

        // the original ID for the AST node defining the type
        abstract val astId: Int
        abstract val canonicalId: String

        // all top-level solidity types should have a user declared name, but due to the nested nature of types, nestings may
        // _not_ have names.
        abstract val userDeclaredName: String
        val qualifiedName get() = containingContract?.let { "$it." }.orEmpty() + userDeclaredName

        fun toUserDefinedCVLType(): CollectingResult<CVLType.PureCVLType, String> = toUserDefinedCVLTypeInternal(this)

        companion object {
            private fun toUserDefinedCVLTypeInternal(typeDesc: SolidityTypeDescription): CollectingResult<CVLType.PureCVLType, String> {
                return when (typeDesc) {
                    is Primitive -> {
                        val evmDesc = typeDesc.primitiveName.toDescriptor()
                        if (evmDesc is EVMTypeDescriptor.EVMIsomorphicValueType) {
                            evmDesc.isomorphicType
                        } else if (evmDesc is EVMTypeDescriptor.EVMContractTypeDescriptor) {
                            // [CVLType.PureCVLType.Primitive.CodeContract] is treated as a singleton type, so here let's
                            // just use the "general" [AccountIdentifier] type.
                            CVLType.PureCVLType.Primitive.AccountIdentifier.lift()
                        } else {
                            error("All Solidity primitive types should have an isomorphic type")
                        }
                    }

                    is PackedBytes -> CVLType.PureCVLType.DynamicArray.PackedBytes.lift()
                    is StringType -> CVLType.PureCVLType.DynamicArray.StringType.lift()
                    is Enum -> CVLType.PureCVLType.Enum(
                        typeDesc.qualifiedName,
                        typeDesc.enumMembers.map { it.name }
                    ).lift()

                    is Struct -> {
                        typeDesc.structMembers.map { member ->
                            toUserDefinedCVLTypeInternal(member.type).map {
                                CVLType.PureCVLType.Struct.StructEntry(member.name, it)
                            }.reduceErrors { errs -> "struct field `${member.name}` cannot be expressed in CVL: $errs" }
                        }.flatten().map {
                            CVLType.PureCVLType.Struct(typeDesc.qualifiedName, it)
                        }
                    }

                    is ValueType -> toUserDefinedCVLTypeInternal(typeDesc.valueTypeAliasedName).map {
                        CVLType.PureCVLType.UserDefinedValueType(typeDesc.qualifiedName, it as CVLType.PureCVLType.Primitive)
                    }

                    is Contract -> CVLType.PureCVLType.Primitive.CodeContract(SolidityContract(typeDesc.contractName)).lift()
                    is Array -> toUserDefinedCVLTypeInternal(typeDesc.dynamicArrayBaseType).map { elemType ->
                        CVLType.PureCVLType.DynamicArray.WordAligned(elementType = elemType)
                    }

                    is StaticArray -> toUserDefinedCVLTypeInternal(typeDesc.staticArrayBaseType).map {
                        CVLType.PureCVLType.StaticArray(it, typeDesc.staticArraySize)
                    }

                    is Mapping -> "mapping types are not supported".asError()
                    is Function -> "function types are not supported".asError()
                }
            }
        }

        @Serializable
        @SerialName("UserDefinedEnum")
        data class Enum(
            val enumName: String,
            val enumMembers: List<EnumMember>,
            override val containingContract: String?,
            override val astId: Int,
            override val canonicalId: String,
        ) : UserDefined() {
            override fun toString() = "uint8"
            override val userDeclaredName = enumName

            // xxx is it good for us? (suppose we implement equals() too) - to reduce number of duplicate entries
            // when traversing all contracts
            /*override fun hashCode() = hash {
                it + enumName + enumMembers + containingContract + canonicalId /* skip astId */
            }*/
        }

        @Serializable
        @SerialName("UserDefinedStruct")
        data class Struct(
            val structName: String,
            val structMembers: List<StructTypeMemberType>,
            override val containingContract: String?,
            override val astId: Int,
            override val canonicalId: String
        ) : UserDefined() {
            override fun toString() = structMembers.joinToString(",", "(", ")")
            override val userDeclaredName = structName
        }

        @Serializable
        @SerialName("UserDefinedValueType")
        data class ValueType(
            val valueTypeName: String,
            val valueTypeAliasedName: Primitive,
            override val containingContract: String?,
            override val astId: Int,
            override val canonicalId: String,
        ) : UserDefined() {
            override fun toString() = "$valueTypeAliasedName"
            override val userDeclaredName = valueTypeName
        }
    }

    @Serializable
    @SerialName("Array")
    data class Array(val dynamicArrayBaseType: SolidityTypeDescription) : SolidityTypeDescription() {
        override fun toString() = "$dynamicArrayBaseType[]"
    }

    @Serializable
    @SerialName("StaticArray")
    data class StaticArray(
        val staticArrayBaseType: SolidityTypeDescription,
        val staticArraySize: BigInteger
    ) : SolidityTypeDescription() {
        override fun toString() = "$staticArrayBaseType[$staticArraySize]"
    }

    @Serializable
    @SerialName("Mapping")
    data class Mapping(
        val mappingKeyType: SolidityTypeDescription,
        val mappingValueType: SolidityTypeDescription,
    ) : SolidityTypeDescription() {
        override fun toString() = "mapping($mappingKeyType => $mappingValueType)"
    }

    @Serializable
    @SerialName("Function")
    data class Function(val name: String) : SolidityTypeDescription()

    fun toVMTypeDescriptor(location: SolcLocation? = null) =
        SolidityTypeInstance(this, location).toVMTypeDescriptor()

    companion object {
        /**
         * @param name the name of a solidity built-in (such as uint, uint256, bool, string)
         *
         */
        fun builtin(name: String): SolidityTypeDescription? {
            return builtinPrimitive(name) ?: when (name) {
                "bytes" -> PackedBytes()
                "string" -> StringType()
                else -> null
            }
        }

        fun builtinPrimitive(name: String): Primitive? =
            PrimitiveType.labelToType[name]?.let { Primitive(it) }
    }
}

@Serializable
@Treapable
data class SolidityTypeInstance(
    val typeDesc: SolidityTypeDescription,
    val location: SolcLocation?
) : java.io.Serializable {

    override fun hashCode() = hash { it + typeDesc + location }

    private fun SolcLocation.toLocationSpecifier(): EVMLocationSpecifier? = when (this) {
        SolcLocation.stack -> null
        SolcLocation.memory -> EVMLocationSpecifier.memory
        SolcLocation.calldata -> EVMLocationSpecifier.calldata
        SolcLocation.storage -> EVMLocationSpecifier.storage
    }

    constructor(typeDesc: SolidityTypeDescription) : this(typeDesc, null)

    private fun SolidityTypeDescription.atMyLocation() = SolidityTypeInstance(this, this@SolidityTypeInstance.location)

    fun toVMTypeDescriptor(): EVMTypeDescriptor =
        when (typeDesc) {
            is SolidityTypeDescription.Primitive -> typeDesc.primitiveName.toDescriptor()
            is SolidityTypeDescription.PackedBytes -> SolidityTypeDescription.PackedBytes().toDescriptor(location?.toLocationSpecifier())
            is SolidityTypeDescription.StringType -> SolidityTypeDescription.StringType().toDescriptor(location?.toLocationSpecifier())
            is SolidityTypeDescription.Contract -> EVMTypeDescriptor.EVMContractTypeDescriptor(
                typeDesc.contractName
            )

            is SolidityTypeDescription.UserDefined.Enum -> EVMTypeDescriptor.EVMEnumDescriptor(
                typeDesc.canonicalId,
                typeDesc.qualifiedName,
                typeDesc.enumMembers.map { member -> member.name }
            )

            is SolidityTypeDescription.UserDefined.Struct -> EVMTypeDescriptor.EVMStructDescriptor(
                typeDesc.canonicalId,
                location?.toLocationSpecifier(),
                typeDesc.structMembers.map { member ->
                    EVMTypeDescriptor.EVMStructDescriptor.EVMStructEntry(
                        member.name,
                        member.type.atMyLocation().toVMTypeDescriptor()
                    )
                },
                typeDesc.qualifiedName,
            )

            is SolidityTypeDescription.UserDefined.ValueType -> EVMTypeDescriptor.UserDefinedValueType(
                typeDesc.canonicalId,
                typeDesc.qualifiedName,
                typeDesc.valueTypeAliasedName.atMyLocation()
                    .toVMTypeDescriptor() as EVMTypeDescriptor.EVMIsomorphicValueType
            )

            is SolidityTypeDescription.Array -> EVMTypeDescriptor.DynamicArrayDescriptor(
                location?.toLocationSpecifier(),
                typeDesc.dynamicArrayBaseType.atMyLocation().toVMTypeDescriptor()
            )

            is SolidityTypeDescription.StaticArray -> EVMTypeDescriptor.StaticArrayDescriptor(
                location?.toLocationSpecifier(),
                typeDesc.staticArrayBaseType.atMyLocation().toVMTypeDescriptor(),
                typeDesc.staticArraySize
            )

            is SolidityTypeDescription.Mapping -> EVMTypeDescriptor.EVMMappingDescriptor(
                typeDesc.mappingKeyType.atMyLocation().toVMTypeDescriptor(),
                typeDesc.mappingValueType.atMyLocation().toVMTypeDescriptor(),
                location?.toLocationSpecifier()
            )

            is SolidityTypeDescription.Function -> EVMTypeDescriptor.FunctionDescriptor(typeDesc.name)
        }
}
