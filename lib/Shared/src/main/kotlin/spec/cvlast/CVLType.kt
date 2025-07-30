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

@file:UseSerializers(BigIntegerSerializer::class)

package spec.cvlast

import bridge.EVMExternalMethodInfo.Companion.arityField
import bridge.EVMExternalMethodInfo.Companion.contractField
import bridge.EVMExternalMethodInfo.Companion.contractType
import bridge.EVMExternalMethodInfo.Companion.fallbackField
import bridge.EVMExternalMethodInfo.Companion.payableField
import bridge.EVMExternalMethodInfo.Companion.pureField
import bridge.EVMExternalMethodInfo.Companion.selectorField
import bridge.EVMExternalMethodInfo.Companion.selectorType
import bridge.EVMExternalMethodInfo.Companion.viewField
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import spec.cvlast.CVLType.PureCVLType
import spec.cvlast.CVLType.VM
import tac.ObjectShape
import spec.cvlast.typedescriptors.*
import tac.Tag
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.CollectingResult.Companion.reduceErrors
import java.math.BigInteger
import kotlin.collections.listOf

/**
 * A description of what type of value is represented in an expression. Specifically, every instance of this class
 * implements the [isConvertibleTo] interface, telling us whether or not we can take a value of this type and
 * convert it to some [CVLType.PureCVLType]. Essentially answering the question "can I use a value of this type
 * when I expect such and such a type?"
 *
 * Note we have explicitly omitted any notion of subtyping, since [CVLType] covers both [PureCVLType]s and
 * [VM] types. To avoid complexity (and potential infeasibility in many cases), we avoid any subtyping over [VM] types.
 * This way, all we need to be concerned with is whether we can move a value between [VM] and [PureCVLType]s.
 */
@Serializable
@Treapable
sealed class CVLType : AmbiSerializable {

    /**
     * Can I take a value represented by this type, and convert it to [expected]. Note convertibility between two
     * [PureCVLType]s is simply the subtyping relation. However, convertibility from a [VM] type to a [PureCVLType]
     * is decided by code specific to the VM being used.
     */
    open fun isConvertibleToResult(
        expected: PureCVLType
    ) = when (this) {
        is PureCVLType -> if (this isSubtypeOf expected) {
            ok
        } else {
            "$this is not a subtype of $expected".asError()
        }
        is VM -> this.context.supportsConversion(to = expected, from = this.descriptor)
    }

    infix fun isConvertibleTo(expected: PureCVLType) = isConvertibleToResult(expected).isResult()
    open infix fun isNotConvertibleTo(expected: PureCVLType) = !(this isConvertibleTo expected)

    /**
     * Check conversion of the type of some expression into some VM type. Note, a conversion between two identical
     * types may fail as this conversion requires a [PureCVLType] intermediary.
     */
    fun convertibleToVMType(
        expected: VMTypeDescriptor,
        context: ToVMContext
    ) =
        when (this) {
            // Taking a VM value and using it as a VM value requires first converting to a value of PureCVLType
            // before then converting to the expected VM Value
            // currently this will only happen when calling a contract function with a call to a contract function
            // as an argument
            is VM -> this.getPureCVLTypeToConvertTo()
                .bind { intermediate ->
                    context.supportsConversion(from = intermediate, to = expected)
                }
            // have a cvl type going to a vm type, in expressions, this only occurs in argument passing
            is PureCVLType -> context.supportsConversion(from = this, to = expected)
        }

    companion object {
        fun PureCVLType.isComplex() = when(this) {
            is PureCVLType.CVLArrayType,
            is PureCVLType.Struct -> true
            is PureCVLType.Primitive,
            is PureCVLType.CVLValueType,
            PureCVLType.Bottom,
            is PureCVLType.Ghost,
            is PureCVLType.Sort,
            is PureCVLType.VMInternal,
            is PureCVLType.TransientTypes -> false
        }

        fun PureCVLType.toShape() : CollectingResult<ObjectShape, String> = when(this) {
            is PureCVLType.MaybeBufferEncodeableType -> {
                this.getEncodingOrNull()?.let(ObjectShape::Primitive)?.lift() ?: "Cannot find shape for type $this".asError()
            }
            is PureCVLType.Struct -> {
                this.fields.map { ent ->
                    ent.cvlType.toShape().map {
                        ent.fieldName to it
                    }
                }.flatten().map {
                    ObjectShape.Struct(it)
                }
            }

            PureCVLType.DynamicArray.PackedBytes,
            PureCVLType.DynamicArray.StringType -> ObjectShape.Array(
                elements = ObjectShape.Primitive(Tag.CVLArray.UserArray.ElementEncoding.Unsigned), true
            ).lift()
            is PureCVLType.DynamicArray.WordAligned -> this.elementType.toShape().map { it: ObjectShape ->
                ObjectShape.Array(it, false)
            }
            is PureCVLType.StaticArray -> this.elementType.toShape().map {
                ObjectShape.StaticArray(elements = it, length = this.logicalLength)
            }
            is PureCVLType.Primitive.NumberLiteral,
            is PureCVLType.Sort,
            is PureCVLType.Ghost,
            PureCVLType.Primitive.HashBlob,
            PureCVLType.Primitive.Mathint,
            is PureCVLType.ArrayLiteral,
            is PureCVLType.TransientTypes,
            is PureCVLType.VMInternal -> {
                "Cannot find shape for type $this".asError()
            }
        }

        @JvmStatic
        fun valueFromString(name: String): PureCVLType? {
            fun parseKType(
                widthStr: String,
                primitiveType: (Int) -> PureCVLType.Primitive,
                defaultWidth: String? = null
            ): PureCVLType? {
                return try {
                    primitiveType(
                        if (defaultWidth != null && widthStr.isEmpty()) {
                            defaultWidth
                        } else {
                            widthStr
                        }.toInt()
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }

            return when {
                name == "bytes" -> PureCVLType.DynamicArray.PackedBytes
                name == "string" -> PureCVLType.DynamicArray.StringType
                name.startsWith("uint") -> parseKType(name.substring(4), PureCVLType.Primitive::UIntK, "256")
                name.startsWith("int") -> parseKType(name.substring(3), PureCVLType.Primitive::IntK, "256")
                name.startsWith("bytes") -> parseKType(name.substring(5), PureCVLType.Primitive::BytesK)
                name == "bool" -> PureCVLType.Primitive.Bool
                name == "mathint" -> PureCVLType.Primitive.Mathint
                name == "address" -> PureCVLType.Primitive.AccountIdentifier
                name == "env" -> EVMBuiltinTypes.env
                name == "calldataarg" -> PureCVLType.VMInternal.RawArgs
                name == "method" -> EVMBuiltinTypes.method
                name == "storage" -> PureCVLType.VMInternal.BlockchainState
                else -> null
            }
        }
    }

    /**
     * @property descriptor the [VMTypeDescriptor] being represented
     * @property context as the name [FromVMContext] implies, a [VM] type is always coming from the VM. However,
     * there are several different places (contexts) it could be coming from, each with potentially different
     * decoding logic required. The [context] tells us where the value is coming from and can tell us whether
     * or not we would be able to decode the value.
     */
    @Serializable
    data class VM(val descriptor: VMTypeDescriptor, val context: FromVMContext) : CVLType() {

        override fun hashCode() = hash { it + descriptor + context }

        /**
         * Sometimes we want to reason about subtyping in the type checker. This function gives us a [PureCVLType] which
         * could hold this, otherwise it returns null.
         */
        fun getPureCVLTypeToConvertTo() = descriptor.getPureTypeToConvertTo(context)

        override fun toString(): String = descriptor.toString()
    }

    /**
     * Provides a way to take a [CVLType] and potentially get a [PureCVLType] to be used when we need to do subtyping
     * checks.
     */
    fun <T> getOrInferPureCVLType(generateError: (List<String>, VMTypeDescriptor) -> T) =
        when (this) {
            is PureCVLType -> this.lift()
            is VM -> this.getPureCVLTypeToConvertTo().reduceErrors { errors -> generateError(errors, descriptor) }
        }

    /**
     * Full-fledged types which are part of the CVL Spec Language. These have a well-defined subtyping relation.
     */
    @Serializable
    sealed class PureCVLType : CVLType() {
        /**
         * All [PureCVLType]
         */
        abstract fun toTag(): Tag

        /**
         * reflexive r <: r
         * transitive r <: t /\ t <: u => r <: u
         * anti-symmetric r <: t /\ t <: r => r == t
         */
        infix fun isSubtypeOf(t: PureCVLType): Boolean = when (this) {
            Primitive.AccountIdentifier -> t == Primitive.AccountIdentifier
            Primitive.Bool -> t == Primitive.Bool
            is Primitive.BytesK -> this == t
            Primitive.HashBlob -> t == Primitive.HashBlob
            is Primitive.IntK -> t is Primitive.Mathint
                || t is Primitive.IntK && t.k >= this.k
                || (t is UserDefinedValueType && this isSubtypeOf t.base)

            Primitive.Mathint -> t == Primitive.Mathint
            is Primitive.NumberLiteral -> this == t
                    || (t is Primitive.UIntK
                            && this.n <= SignUtilities.maxUnsignedValueOfBitwidth(t.bitWidth)
                            && this.n >= BigInteger.ZERO)
                    || (t is Primitive.IntK
                            && this.n <= SignUtilities.maxSignedValueOfBitwidth(t.bitWidth)
                            && this.n >= SignUtilities.minSignedValueOfBitwidth(t.bitWidth))
                    || (t is Primitive.Mathint)
                    || (t is Primitive.AccountIdentifier
                        && this.n <= SignUtilities.maxUnsignedValueOfBitwidth(t.bitWidth)
                        && this.n >= BigInteger.ZERO)
                    || (t is UserDefinedValueType && this isSubtypeOf t.base)
            is Primitive.UIntK -> t is Primitive.Mathint
                    || t is Primitive.UIntK && t.k >= this.k
                    || t is Primitive.IntK && t.k > this.k
                    || (t is UserDefinedValueType && this isSubtypeOf t.base)
            is Primitive.CodeContract -> t == this || t == Primitive.AccountIdentifier
            is Sort -> this == t
            is DynamicArray -> this == t
            // all static arrays are word aligned, a static array cannot be a subtype of a packed array
            is StaticArray -> this == t
            is ArrayLiteral -> when (t) {
                is StaticArray -> {
                    t.logicalLength == this.logicalLength && this.elementTypes.all { elementType ->
                        elementType.isSubtypeOf(
                            t.elementType
                        )
                    }
                }

                is DynamicArray -> {
                    this.elementTypes.all { elementType -> elementType.isSubtypeOf(t.elementType) }
                }

                else -> {
                    this == t
                }
            }

            is Enum -> this == t
            is UserDefinedValueType -> this == t || this.base isSubtypeOf t
            is Ghost.Function -> false // a ghost may never be assigned or compared or anything, the only time a ghost
            // function comes close to being first class is when it is havoc'd
            is Ghost.Mapping -> this == t
            is Struct -> this == t
            VMInternal.BlockchainState -> this == t
            VMInternal.RawArgs -> t == VMInternal.RawArgs
            Void -> this == t
            Bottom -> true
            is VMInternal.BlockchainStateMap -> this == t
            is VMInternal.StorageReference -> this == t
            is TupleType -> this == t
        }

        /**
         * Gates access to this behind a check for convertibility.
         */
        fun needsConversionCheck() = VMTypeWithCorrespondence.NeedsConversionCheck(this).lift()

        @Deprecated(
            "If you have a pure cvl type you should probably be using the subtype relation",
            ReplaceWith("isSubtypeOf")
        )
        override fun isConvertibleToResult(expected: PureCVLType): VoidResult<String> {
            return super.isConvertibleToResult(expected)
        }

        @Deprecated(
            "If you have a pure cvl type you should probably be using the subtype relation",
            ReplaceWith("!isSubtypeOf")
        )
        override fun isNotConvertibleTo(expected: PureCVLType): Boolean {
            return super.isNotConvertibleTo(expected)
        }

        sealed interface LiteralType

        @Serializable
        sealed class VMInternal : PureCVLType() {
            /**
             * A raw array of bytes representing unconstrained data buffer sent to a called function.
             */
            @Serializable
            object RawArgs : VMInternal() {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = RawArgs
                override fun toTag(): Tag {
                    return Tag.CVLArray.RawArray
                }

                override fun toString(): String {
                    return "calldataarg"
                }
            }

            @KSerializable
            data class StorageReference(
                val basis: StorageBasis
            ) : VMInternal() {
                override fun toTag(): Tag {
                    throw UnsupportedOperationException("A storage reference does not produce a value")
                }

                override fun toString() : String {
                    return "storage basis $basis"
                }
            }

            /**
             * For balances, nonce, whatever else we instrument using WordMaps. In other words, a map with a very
             * tight coupling to some VM state.
             *
             * The type of the paramters are [paramType] and the codomain of the map is [outputType].
             * Well-formed instances of this type must use numeric indices and values, a sort or enum or whatever
             * as a key or value won't work.
             */
            @KSerializable
            data class BlockchainStateMap(val paramType: PureCVLType.Primitive, val outputType: PureCVLType.Primitive) : VMInternal()  {
                override fun toTag(): Tag {
                    return Tag.WordMap
                }
                /*
                   Is this right? We don't actually type check wordloads or stores at all, so who knows?!
                 */
                init {
                    check(paramType.toTag() == Tag.Bit256 || paramType.toTag() == Tag.Int)
                    check(outputType.toTag() == Tag.Bit256 || outputType.toTag() == Tag.Int)
                }

                override fun toString(): String {
                    return "state_map($paramType => $outputType)"
                }
            }

            /**
             * Holds a snapshot of global state, including any ghost variables and all state of all contracts in the
             * scene.
             */
            @Serializable
            object BlockchainState : VMInternal() {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = BlockchainState
                override fun toTag(): Tag {
                    return Tag.BlockchainState

                }

                override fun toString(): String {
                    return "storage"
                }
            }
        }

        /**
         * A grouping of our "higher order", non-array types. For historical reasons they are grouped under the [Ghost]
         * type. They can currently only be used for global ghost variables.
         */
        @Serializable
        sealed class Ghost : PureCVLType() {
            /**
             * An uninterpreted function.
             */
            @Serializable
            data class Function(val inParams: List<PureCVLType>, val outParam: PureCVLType) : Ghost() {
                override fun toTag() = inParams.map {
                    it.toTag()
                }.let { p ->
                    outParam.toTag().let { o ->
                        Tag.GhostMap(p, o)
                    }
                }

            }

            @Serializable
            data class Mapping(val key: PureCVLType, val value: PureCVLType) : Ghost() {
                override fun toTag(): Tag {
                    return getKeys().map(PureCVLType::toTag).let { pTy ->
                        nestedResultType.toTag().let { rTy ->
                            Tag.GhostMap(pTy, rTy)
                        }
                    }
                }

                /**
                 * The "output" value type for the mapping. If [value] of this mapping is itself
                 * a mapping, then get the nested result type of that mapping: this process is applied recursively.
                 *
                 * In other words, if we write the full mapping type down as a sequence of types and arrows, the
                 * right most type.
                 */
                val nestedResultType: PureCVLType
                    get() = if (value is Mapping) {
                        value.nestedResultType
                    } else {
                        value
                    }

                fun getKeys(): List<PureCVLType> {
                    val ret = mutableListOf(key)
                    var iter = value
                    while (iter is Mapping) {
                        ret.add(iter.key)
                        iter = iter.value
                    }
                    return ret
                }
            }
        }

        /**
         * A type defined by the user with no interpretation. Only supports equality.
         */
        @Serializable
        data class Sort(val name: String) : PureCVLType(), GhostMappingKeyType {
            override fun toTag(): Tag = Tag.UserDefined.UninterpretedSort(name)

            override fun toString(): String {
                return name
            }
        }

        /**
         * A type that is a valid key in a ghost map (value types, sorts, and enums)
         */
        @Serializable
        @Treapable
        sealed interface GhostMappingKeyType: AmbiSerializable

        /**
         * Common sealed interface for any type that is known to fit into a single tac register (read: variable).
         * In other words, anything that is not a compound type
         */
        @Serializable
        sealed interface CVLValueType : GhostMappingKeyType

        /** Common sealed interface for CVL types which are not atomic, and are instead composed of [CVLValueType]. */
        sealed interface CVLCompoundType

        sealed interface MaybeBufferEncodeableType {
            fun getEncodingOrNull(): Tag.CVLArray.UserArray.ElementEncoding?
        }

        sealed interface BufferEncodeableType : MaybeBufferEncodeableType {
            fun getEncoding(): Tag.CVLArray.UserArray.ElementEncoding
            override fun getEncodingOrNull() = getEncoding()
        }

        sealed interface UnsignedElementType : BufferEncodeableType {
            override fun getEncoding() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned
        }

        /**
         * Value types that are "built in" (to contrast with Enums and user defined value types which come from
         * solidity land)
         */
        @Serializable
        sealed class Primitive : PureCVLType(), CVLValueType {

            /**
             * Arbitrary precision integer.
             */
            @Serializable
            object Mathint : Primitive() {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = Mathint

                override fun toString(): String = "mathint"

                override fun toTag(): Tag = Tag.Int
            }

            @Serializable
            object AccountIdentifier : Primitive(), UnsignedElementType {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = AccountIdentifier

                val bitWidth: Int = Config.VMConfig.maxAddress.bitLength()

                override fun toString(): String = "address"

                override fun toTag(): Tag {
                    return Tag.Int
                }
            }

            /**
             * Refinement type of [AccountIdentifier], i.e. the account identifier corresponding to the contract called
             * [name]. The scene can be used to find the actual address.
             */
            @Serializable
            data class CodeContract(val name: SolidityContract) : Primitive(), UnsignedElementType {
                override fun toTag(): Tag = AccountIdentifier.toTag()

                val bitWidth = AccountIdentifier.bitWidth

                override fun toString(): String = name.name
            }


            /**
             * The number with value [n].
             */
            @Serializable
            data class NumberLiteral(val n: BigInteger) : Primitive(), LiteralType, BufferEncodeableType {
                val bitLength = n.bitLength() // note this excludes the sign bit
                override fun getEncoding(): Tag.CVLArray.UserArray.ElementEncoding {
                    val ty = smallestTypeForNumberLit()
                    check(ty is BufferEncodeableType) {
                        "expected $ty to be buffer-encodeable"
                    }
                    return ty.getEncoding()
                }

                override fun toString(): String = "$n"

                override fun toTag(): Tag = Tag.Int

                fun smallestTypeForNumberLit() = n.smallestTypeForNumberLit()
                fun smallestSignedTypeForNumberLit() = n.smallestSignedTypeForNumberLit()
            }

            /**
             * Unsigned integer of [k] bits.
             */
            @Serializable
            data class UIntK constructor(val k: Int) : Primitive(), UnsignedElementType {
                override fun toTag(): Tag {
                    return Tag.Int
                }

                val bitWidth: Int = k

                companion object {
                    // This is due to serialization issues in CallTraceTest.
                    @JvmStatic private val serialVersionUID: Long = -5112823016583969239
                }

                override fun toString(): String = "uint$k"

                init {
                    if (k < 8 || k > 256 || k % 8 != 0) {
                        throw NumberFormatException("Invalid k for uint type: $k, expected a value between 8 and 256 that is divisible by 8")
                    }
                }
            }

            /**
             * Signed integer of [k] bits.
             */
            @Serializable
            data class IntK constructor(val k: Int) : Primitive(), BufferEncodeableType {
                override fun toTag(): Tag {
                    return Tag.Int
                }

                val bitWidth: Int = k

                override fun getEncoding() = Tag.CVLArray.UserArray.ElementEncoding.Signed

                override fun toString(): String = "int$k"

                init {
                    if (k < 8 || k > 256 || k % 8 != 0) {
                        throw NumberFormatException("Invalid k for int type: $k, expected a value between 8 and 256 that is divisible by 8")
                    }
                }
            }

            /**
             * Array of [k] bytes. Represented in a single "register". Array access not supported in CVL. This should
             * really be thought of as a right padded primitive of [k] bytes.
             */
            @Serializable
            data class BytesK constructor(val k: Int) : Primitive(), UnsignedElementType {
                override fun toTag(): Tag {
                    return Tag.Bit256
                }

                override fun toString(): String = "bytes$k"

                val bitWidth: Int = k * 8

                init {
                    if (k < 1 || k > 32) {
                        throw NumberFormatException("Invalid k for bytes type: $k, k must be in the range of 1 to 32, i.e. use a bytes32")
                    }
                }

                companion object {
                    fun saturate() = (1..32).map { k ->
                        BytesK(k)
                    }
                }
            }

            @Serializable
            object Bool : Primitive(), BufferEncodeableType {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = Bool

                override fun toTag(): Tag = Tag.Bool


                override fun toString(): String = "bool"

                override fun getEncoding() = Tag.CVLArray.UserArray.ElementEncoding.Boolean
            }

            /**
             * Internal-use only type (no concrete syntax). Represents the hash of a bytes array or string and is used
             * extensively for hook inlining.
             */
            @Serializable
            object HashBlob : Primitive() {
                override fun hashCode() = hashObject(this)
                private fun readResolve(): Any = HashBlob

                override fun toString(): String {
                    return "hashblob"
                }

                override fun toTag(): Tag {
                    return Tag.Bit256
                }

                val bitWidth: Int = Config.VMConfig.registerBitwidth
            }

        }

        sealed interface StaticallySized {
            val logicalLength: BigInteger
        }

        /**
         * Collection of different array types in CVL. Note that due to restrictions on the underlying representation
         * of arrays when passing to contract functions, the encoding of [elementType] will be different than it would
         * be outside of an array. Specifically, signed values will be represented using two's complement inside the
         * array.
         */
        @Serializable
        sealed class CVLArrayType: PureCVLType(), CVLCompoundType {

            companion object {
                val indexType = Primitive.UIntK(256)

                val lengthType = Primitive.UIntK(256)

                const val lengthFieldName = "length"
            }
            abstract val elementType: PureCVLType

            override fun toTag(): Tag {
                /**
                 * Here the encoding of the elements of this, regardless of whether this is dynamic or not,
                 * determines the tag used. If our elements are [spec.cvlast.abi.DataLayout.SequenceElement.PackedBytes1]
                 * or a [spec.cvlast.abi.DataLayout.SequenceElement.Elements] that are [spec.cvlast.CVLType.PureCVLType.ArrayElementLayout.DirectWordAligned],
                 * then we can use the [tac.Tag.CVLArray.UserArray] tag, otherwise we have to use the [tac.Tag.CVLArray.ComplexArray]
                 * tag.
                 */
                return this.elementType.toShape().resultOrThrow {
                    throw UnsupportedOperationException("Cannot get principle tag for $this")
                }.let {
                    Tag.CVLArray.UserArray(it, this is DynamicArray.Bytes1Array)
                }
            }

        }

        /**
         * An array with variable/dynamic length.
         */
        @Serializable
        sealed class DynamicArray : CVLArrayType() {
            @Serializable
            sealed class Bytes1Array : DynamicArray() {
                override val elementType: PureCVLType
                    get() = Primitive.BytesK(1)
            }

            @Serializable
            object StringType : Bytes1Array() {
                override fun toString() = "string"

                override fun hashCode() = hashObject(this)

                private fun readResolve(): Any = StringType
            }

            @Serializable
            object PackedBytes : Bytes1Array() {
                override fun toString() = "bytes"

                override fun hashCode() = hashObject(this)

                private fun readResolve(): Any = PackedBytes
            }

            /**
             * Represents an array of word-aligned, 256-bit or smaller elements.
             */
            @Serializable
            data class WordAligned(override val elementType: PureCVLType) : DynamicArray() {
                init {
                    check(elementType !is Primitive.NumberLiteral) {
                        "A number literal $elementType should only be an element of a literal array, which has a different type"
                    }
                }

                override fun toString(): String = "$elementType[]"
            }
        }

        /**
         * Array with static length.
         */
        @Serializable
        data class StaticArray(override val elementType: PureCVLType, override val logicalLength: BigInteger) : CVLArrayType(), StaticallySized {

            override fun toString(): String = "$elementType[$logicalLength]"

        }

        @Serializable
        data class ArrayLiteral(val elementTypes: List<PureCVLType>, val leastUpperBoundElementType: PureCVLType) :
            CVLArrayType(), LiteralType, StaticallySized {

            override val elementType: PureCVLType
                get() = leastUpperBoundElementType
            override val logicalLength: BigInteger = elementTypes.size.toBigInteger()

            override fun toString(): String = "[${elementTypes.joinToString(", ")}]"
        }

        /**
         * @property name The name of the struct. For struct defined in a contract/library this will
         * be `ContractName.StructName`. For top-level structs this will be just `StructName`.
         */
        @Serializable
        data class Struct(val name: String, val fields: List<StructEntry>) : PureCVLType(), CVLCompoundType {
            init {
                check(fields.distinctBy { it.fieldName }.size == fields.size) { "struct type invariant violated: $this"}
            }
            @Serializable
            @Treapable
            data class StructEntry(val fieldName: String, val cvlType: PureCVLType) : java.io.Serializable {
                override fun toString() = fieldName
            }

            override fun toTag(): Tag.UserDefined.Struct =
                Tag.UserDefined.Struct(
                    this.name,
                    this.fields.map { field ->
                        Tag.UserDefined.Struct.Field(
                            field.fieldName,
                            field.cvlType.toTag()
                        )
                    }
                )

            fun getEntryOrNull(fieldName: String): StructEntry? =
                fields.singleOrNull { field -> field.fieldName == fieldName }

            // TODO: Should we have explicit toString()s for all types here? in particular ones expressible
            // in solidity or in spec
            override fun toString() = name

            /**
             * Computes in [outFields] all the flattened struct-entries in this struct type (i.e. even if there are sub-structs)
             */
            fun flatten(outFields: MutableList<List<StructEntry>>) {
                this.fields.forEach { field ->
                    if (field.cvlType is Struct) {
                        val subFields = mutableListOf<List<StructEntry>>()
                        field.cvlType.flatten(subFields)
                        // append current field
                        outFields.addAll(subFields.map { subField -> datastructures.stdcollections.listOf(field) + subField })
                    } else {
                        outFields.add(datastructures.stdcollections.listOf(field))
                    }
                }
            }
        }

        /**
         * An enum called [name] whose values must be one of [elements].
         */
        @Serializable
        data class Enum(val name: String, val elements: List<String>) : PureCVLType(), CVLValueType, UnsignedElementType {
            init {
                check(elements.distinct().size == elements.size) { "enum type invariant violated: $this"}
            }
            val maxValue: Int get() = elements.size - 1

            override fun toTag(): Tag {
                return Tag.Bit256
            }

            override fun toString() = name

            // We support (explicit) casting of enums to integer types
            fun isCastableTo(t: PureCVLType) = t isSubtypeOf Primitive.Mathint
        }

        /**
         * Treated in CVL as an alias for [base]. Treatment in solidity is slightly different.
         */
        @Serializable
        data class UserDefinedValueType(val name: String, val base: Primitive) : PureCVLType(), CVLValueType, MaybeBufferEncodeableType {
            override fun toTag(): Tag = base.toTag()

            override fun toString() = "$name($base)"

            override fun getEncodingOrNull() = (this.base as? MaybeBufferEncodeableType)?.getEncodingOrNull()
        }

        @Serializable
        object Bottom : PureCVLType(), UnsignedElementType /* this is for supporting wildcards */ {
            override fun hashCode() = hashObject(this)
            private fun readResolve(): Any = Bottom

            override fun toString(): String = "⊥"

            override fun toTag(): Tag {
                return Tag.Bit256 // throw UnsupportedOperationException() // todo what about wildcards // throws in DSA on CVL keywords
            }
        }

        sealed interface TransientTypes

        @Serializable
        object Void : PureCVLType(), TransientTypes {
            override fun hashCode() = hashObject(this)
            private fun readResolve(): Any = Void

            override fun toTag(): Tag {
                throw UnsupportedOperationException()
            }

            override fun toString() = "void"
        }

        /**
         * Holder type to mean "this operation returns multiple values". Note that, like [Void] above,
         * this is one of the two types for which the [toTag] operation is not total.
         *
         * This is intentional, and the justification is the same as in the [Void] case. There,
         * the [Void] type means "this operation returns no values, so you can't do anything with it". So it would
         * never make sense to have a variable that holds the result of a void computation.
         *
         * The tuple type, despite having information is similar, you can't do anything to a tuple type except
         * immediately destruct it as part of a definition command (or return it from a summary). Tuples are NOT
         * first class within CVL, but we still need a way to indicate, in the type system, that an expression returns
         * a list of values.
         *
         * Put another way, we need this type because we have to be able to give a type to the expression `foo()`
         * where `foo` is declared (in CVL) as `function foo() returns (uint, uint)`. The only thing you can do with this
         * value is ignore it, or immediately destruct it in an assignment, in which case we bypass ever needing a representation
         * of the intermediate value. Our typechecker, by basically being completely oblivious to the TupleType helps ensure
         * we don't try to assign an entire tuple to a variable, or pass it to another function etc.
         *
         * In this way, our TupleType mirrors the tuple type in Solidity: there is no tuple type except at function boundaries:
         * the only time you can "create" a tuple is when you return multiple values, and the only thing you can do with these
         * tuples is immediately destruct them in assignments. You cannot define a variable of a tuple type,
         * and there are no other operations you can do to it. It also mirrors the similar representation for
         * [spec.cvlast.typedescriptors.VMFunctionReturn]: no conversions are available on that type, it is there to indicate
         * "you need to destruct the results of this function call".
         */
        @Serializable
        data class TupleType(val elements: List<PureCVLType>) : PureCVLType(), TransientTypes {
            override fun toTag(): Tag {
                throw UnsupportedOperationException()
            }

            override fun toString(): String {
                return elements.joinToString(",", "(", ")")
            }
        }
    }
}

/**
 * Collection of all EVM-specific types.
 */
object EVMBuiltinTypes {

    /**
     * Performs an "in place" update of [m] in a functional way, which adds the evm built in types
     * to the set of types of [m].
     */
    fun populateTACSymbolTable(m: MutableSet<PureCVLType.Struct>) : Set<PureCVLType.Struct> {
        m.add(msg)
        m.add(block)
        m.add(tx)
        m.add(env)
        m.add(method)
        m.add(virtualMethod)
        return m
    }

    fun isEVMKeyword(base: String): Boolean {
        return listOf(msg, block, tx).any { base == it.name }
    }

    val msg = PureCVLType.Struct(
        "msg",
        listOf(
            PureCVLType.Struct.StructEntry("sender", PureCVLType.Primitive.AccountIdentifier),
            PureCVLType.Struct.StructEntry("value", PureCVLType.Primitive.UIntK(256))
        )
    )
    val tx = PureCVLType.Struct(
        "tx",
        listOf(
            PureCVLType.Struct.StructEntry("origin", PureCVLType.Primitive.AccountIdentifier)
        )
    )
    val block = PureCVLType.Struct(
        "block",
        listOf(
            PureCVLType.Struct.StructEntry("basefee", PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry("blobbasefee", PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry("coinbase", PureCVLType.Primitive.AccountIdentifier),
            PureCVLType.Struct.StructEntry("difficulty", PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry("gaslimit", PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry("number", PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry("timestamp", PureCVLType.Primitive.UIntK(256)),
// TODO Merge: bytesblob is now Tag.Bit256
        )
    )
    val env = PureCVLType.Struct(
        "env",
        listOf(
            PureCVLType.Struct.StructEntry("msg", msg),
            PureCVLType.Struct.StructEntry("tx", tx),
            PureCVLType.Struct.StructEntry("block", block)
        )

    )

    val method = PureCVLType.Struct(
        "method",
        listOf(
            PureCVLType.Struct.StructEntry(selectorField, selectorType),
            PureCVLType.Struct.StructEntry(pureField, PureCVLType.Primitive.Bool),
            PureCVLType.Struct.StructEntry(viewField, PureCVLType.Primitive.Bool),
            PureCVLType.Struct.StructEntry(payableField, PureCVLType.Primitive.Bool),
            PureCVLType.Struct.StructEntry(arityField, PureCVLType.Primitive.UIntK(256)),
            PureCVLType.Struct.StructEntry(fallbackField, PureCVLType.Primitive.Bool),
            PureCVLType.Struct.StructEntry(contractField, contractType),
        )
    )
    val virtualMethod = PureCVLType.Struct(
        "vmethod",
        listOf(
            PureCVLType.Struct.StructEntry(selectorField, PureCVLType.Primitive.UIntK(32))
        )
    )
    val evmBitWidths: IntProgression = (8..256 step 8)
}

private fun Int.roundUpToNearestNonZero8() =
    when {
        this == 0 -> 8
        this.mod(8) == 0 -> this
        else -> this.div(8).plus(1).times(8)
    }

fun BigInteger.smallestTypeForNumberLit() =
    try {
        val bitLength = this.bitLength()
        if (this < BigInteger.ZERO) {
            PureCVLType.Primitive.IntK(bitLength.plus(1).roundUpToNearestNonZero8())
        } else {
            PureCVLType.Primitive.UIntK(bitLength.roundUpToNearestNonZero8())
        }
    } catch (_: NumberFormatException) {
        // In case the number can't be represented by UintK or IntK (the constructors throw an exception),
        // suggest Mathint instead
        PureCVLType.Primitive.Mathint
    }

fun BigInteger.smallestSignedTypeForNumberLit() =
    try {
        val bitLength = this.bitLength()
        PureCVLType.Primitive.IntK(bitLength.plus(1).roundUpToNearestNonZero8())
    } catch (_: NumberFormatException) {
        // In case the number can't be represented by IntK (the constructor throws an exception),
        // suggest Mathint instead
        PureCVLType.Primitive.Mathint
    }

fun PureCVLType.Primitive.UIntK.smallestContainingSignedInt() =
    try {
        PureCVLType.Primitive.IntK(bitWidth.plus(1).roundUpToNearestNonZero8())
    } catch (_: NumberFormatException) {
        // In case the number can't be represented by IntK (the constructor throws an exception),
        // suggest Mathint instead
        PureCVLType.Primitive.Mathint
    }
