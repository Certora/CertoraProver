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
package tac

import com.certora.collect.*
import datastructures.Memoized
import datastructures.stdcollections.*
import evm.twoToThe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*
import utils.*
import java.io.Serializable
import java.math.BigInteger

/**
 * Tags are TAC types. They are associated with `TACSymbol`s.
 * An initially decompiled EVM bytecode will only have [Bit256]s as primitives as well as [ByteMap]s and [WordMap]s.
 * The `TypeRewriter` will be responsible for converting some of the [Bit256]s to [Bool]s for better performance.
 * [Int] are for infinite precision ints, and currently have two particular use cases:
 * 1. CVL MathInts
 * 2. "Legacy" hash axiomatization
 * [Bit512] is similar to [Bit256] but is representing a bitvector with 512 bits instead of EVM default of 256.
 * [ByteMap] is a tag for byte arrays as used by the EVM memory and calldata. It's a constant array of size
 * 2^256-1 elements, each constituting a byte.
 * [WordMap] is a tag for maps keyed by [Bit256] keys. In practice, it also accepts [Int] keys, in order to support
 * "legacy" hashing, but also an uninterpreted sort of "skeys" (Storage-Keys) for the new storage hash axiomatization.
 * (SG: I believe in that case all accesses to the wordmap should be converted to using skeys, but perhaps Alex can
 * elaborate.)
 * Under [UserDefined], there are two types of tags:
 * - [UserDefined.UninterpretedSort] is for user declared, uninterpreted sorts and NOTHING ELSE
 * - [UserDefined.Struct] is for struct defs
 * In addition, ghost types are supported via the [GhostMap] tag, specifying parameter tags and and a return tag.
 */
@KSerializable
@Treapable
sealed class Tag : AmbiSerializable {
    /**
     * Any tag which should only exist in a CVLTACProgram and not a CoreTACProgram
     */
    sealed interface TransientTag

    /** All the non-composite tags that are contained in this one. */
    open val nestedBasicTagsFlat: Sequence<Tag>
        get() = sequenceOf(this)

    @KSerializable(with = Bits.Serializer::class)
    sealed class Bits(override val bitwidth: kotlin.Int) : Tag(), ModZm {
        // only required to make serialization happy. Don't call it.
        private constructor(): this(0)

        override val modulus = twoToThe(bitwidth)
        override val maxUnsigned: BigInteger = modulus - 1
        override val maxSigned: BigInteger = twoToThe(bitwidth - 1) - 1
        override val minSignedMath: BigInteger = -twoToThe(bitwidth - 1)
        override val minSigned2s: BigInteger = maxSigned + 1

        @Suppress("SERIALIZER_TYPE_INCOMPATIBLE") // Bits.Serializer will produce a BitN, if a BitN was serialized
        @KSerializable(with = Bits.Serializer::class)
        private class BitN(bitwidth: kotlin.Int): Bits(bitwidth) {
            override fun hashCode(): kotlin.Int = hash { it + bitwidth }
            override fun equals(other: Any?): Boolean =
                (this === other) || (bitwidth == (other as? BitN)?.bitwidth)
        }

        internal object Serializer : KSerializer<Bits> {
            override val descriptor = buildClassSerialDescriptor("tac.Tag.Bits") {
                element<kotlin.Int>("bits")
            }
            override fun serialize(encoder: Encoder, value: Bits) = encoder.encodeStructure(descriptor) {
                encodeIntElement(descriptor, 0, value.bitwidth)
            }
            override fun deserialize(decoder: Decoder) = decoder.decodeStructure(descriptor) {
                var bitwidth = 0
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> bitwidth = decodeIntElement(descriptor, 0)
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }
                Companion(bitwidth)
            }
        }

        companion object {
            private val cache = Memoized { bitwidth: kotlin.Int -> BitN(bitwidth) }
            operator fun invoke(bitwidth: kotlin.Int) = when (bitwidth) {
                32 -> Bit32
                64 -> Bit64
                128 -> Bit128
                256 -> Bit256
                512 -> Bit512
                else -> {
                    require(bitwidth > 0) { "We only support bit types with a positive number of bits, not $bitwidth." }
                    require(bitwidth % 8 == 0) { "We only support byte-sized types, not $bitwidth bits. Is this a typo?" }
                    cache(bitwidth)
                }
            }
        }
    }

    @KSerializable
    object Bit32: Bits(32) {
        fun readResolve(): Any = Bit32
        override fun hashCode(): kotlin.Int = hashObject(this)
    }
    @KSerializable
    object Bit64: Bits(64) {
        fun readResolve(): Any = Bit64
        override fun hashCode(): kotlin.Int = hashObject(this)
    }
    @KSerializable
    object Bit128: Bits(128) {
        fun readResolve(): Any = Bit128
        override fun hashCode(): kotlin.Int = hashObject(this)
    }
    @KSerializable
    object Bit256: Bits(256) {
        fun readResolve(): Any = Bit256
        override fun hashCode(): kotlin.Int = hashObject(this)
    }
    @KSerializable
    object Bit512: Bits(512) {
        fun readResolve(): Any = Bit512
        override fun hashCode(): kotlin.Int = hashObject(this)
    }

    @KSerializable
    object Bool : Tag() {
        fun readResolve(): Any {
            return Bool
        }
        override fun hashCode(): kotlin.Int = hashObject(this)
        override fun equals(other: Any?): Boolean = (this === other)
    }

    @KSerializable
    sealed class CVLArray : Tag(), TransientTag {
        val elementTag: Tag get() = Bit256

        /**
         * Represents simply an array of (byte addressable?), untyped words. Basically only used for raw calldata buffers
         */
        @KSerializable
        object RawArray : CVLArray() {
            fun readResolve(): Any {
                return RawArray
            }
            override fun hashCode(): kotlin.Int = hashObject(this)
        }

        /**
         * A variable tagged with this type actually represents a *family* of variables (much
         * like the [BlockchainState] tag). At the very minimum, each variable `v` is tagged with this
         * type is decomposed into two variables: `v_length` and `v_data`, which holds
         * the length of the array (as a scalar) and the "data" of the array (in a bytemap) respectively.
         * But what if the elements are not of primitive type? What value is stored in the array?
         * This is where the [elementType] and [ObjectShape] comes into play. If the values in the
         * array are structs with n fields, then there are n "sub-arrays", called `v_data_f1`, `v_data_f2`, etc.
         * for each field fi. These "sub-arrays" are just bytemaps; the value stored in `v_data` is just
         * a "pointer" into these bytemaps. In other words, to access the value of field `f1`
         * for element at index i would require: `v_data_f1[v_data[i * 32]]`. Like in Solidity, the value
         * of the ith field is stored at an offset of `i * 32` from the pointer. So, to access `f2` in the
         * same example, we would have `v_data_f2[v_data[i * 32] + 32]`.
         *
         * What if the elements of `v` are instead arrays? Then there are two subarrays, `v_data_length`, and
         * `v_data_data`. The value in `v_data` is a pointer into these arrays. The length of the array at
         * index i is stored in `v_data_length[v_data[i * 32]]`. The data for the array at index `i` is itself
         * stored in `v_data_data`, offset from `v_data[i * 32]` by 32 (as in Solidity). Static arrays are like dynamic
         * arrays, except the data is not offset by 32, and there is no `_length` sub array.
         *
         * But what if the field `f2` of the struct itself holds an array, or we have yet further nesting? Then there
         * are yet more sub-arrays extending the existing sub-arrays. We make this recursive process explicit
         * in the following.
         *
         * The [ObjectShape] describes the shape of the elements of the array in very coarse grained terms. Any traversal
         * through the [ObjectShape] of the array elements is a valid traversal through the values stored in that array.
         * This is the concept of a "data path"; a sequence of [DataField] that corresponds
         * to some traversal through an [ObjectShape]. For example, [DataField.ArrayData] represents accessing
         * the elements of an array, [DataField.StructField] is accessing a field of a struct. Thus, from
         * an [ObjectShape] one can (recursively) enumerate all of the valid data paths for an array. This gives us
         * a formal basis for the "sub-arrays" mentioned above; each data path corresponds to one "subarray". In other words,
         * to traverse elements stored in [UserArray], one must traverse an [ObjectShape], each step of the
         * traversal corresponds to a [DataField]. Each traversal uses the value from the previous step to compute
         * the location in a bytemap that is identified by the "data path" of all fields taken to that point. For example,
         * in the above example accessing `f1`, the first "step" was to access the array's data, so the first
         * value read was from `v_data` which corresponds to the path ([tac.DataField.ArrayData]). Then the next step was to traverse the struct field f1, so the next step was
         * [tac.DataField.StructField], and the value was read from `v_data_f1` which corresponds to the path ([tac.DataField.ArrayData], [DataField.StructField]).
         *
         * In other words, variables tagged with this type are "exploded" into `n` bytemaps, one for each (potentially
         * partial) path through [elementType] plus a distinguished, scalar length variable. Thus, each
         * arrayVar + (value) datapath identifies a bytemap.
         *
         * (Readers familiar with the points to analysis or the partition hierarchies will recognize this
         * as being a close analogue of the abstract layouts for EVM memory objects. This is no accident)
         */
        @KSerializable
        data class UserArray(val elementType: ObjectShape, val isPacked: Boolean): CVLArray() {
            @KSerializable
            enum class ElementEncoding {
                Signed,
                Unsigned,
                Boolean
            }

            override fun hashCode(): kotlin.Int = hash { it + elementType }

            override fun equals(other: Any?): Boolean = when {
                other === this -> true
                other !is UserArray -> false
                else -> other.elementType == this.elementType
            }
        }
    }

    @KSerializable
    object BlockchainState : Tag(), TransientTag {
        fun readResolve(): Any = BlockchainState
        override fun hashCode(): kotlin.Int = hashObject(this)
    }

    @KSerializable
    sealed class Map : Tag() {
        abstract val paramSorts: List<Tag>
        abstract val resultSort: Tag

        override val nestedBasicTagsFlat: Sequence<Tag>
            get() = paramSorts.asSequence().flatMap { it.nestedBasicTagsFlat } + resultSort.nestedBasicTagsFlat

        fun getMapDimension(): kotlin.Int = this.paramSorts.size

        companion object {
            /** Tech debt: This can be much more canonical once we have streamlined the map types.
             *  Then the [isWordMap] parameter should become obsolete.
             *  See also: https://certora.atlassian.net/browse/CER-632
             * */
            fun fromSignature(paramSorts: List<Tag>, resultSort: Tag, isWordMap: Boolean): Map =
                when (paramSorts.size) {
                    0 -> throw IllegalArgumentException(
                        "can't build a proper map type without parameter " +
                                "sorts (got result sort $resultSort) "
                    )
                    1 -> when (paramSorts[0]) {
                        Bit256 -> if (isWordMap) WordMap else ByteMap
                        else -> {
                            check(!isWordMap) {
                                "isWordMap is set, but signature " +
                                        "(${paramSorts.joinToString(" x ")} -> $resultSort} does not match"
                            }
                            GhostMap(paramSorts, resultSort)
                        }
                    }
                    else -> {
                        check(!isWordMap) { "isWordMap is set, but signature does not match" }
                        GhostMap(paramSorts, resultSort)
                    }
                }
        }
    }

    @KSerializable
    object ByteMap : Map() {
        fun readResolve(): Any {
            return ByteMap
        }

        override fun hashCode(): kotlin.Int = hashObject(this)
        override fun equals(other: Any?): Boolean = (this === other)

        override val paramSorts: List<Tag>
            get() = listOf(Bit256)
        override val resultSort: Tag
            get() = Bit256
    }

    @KSerializable
    object WordMap : Map() {
        fun readResolve(): Any {
            return WordMap
        }
        override fun hashCode(): kotlin.Int = hashObject(this)
        override fun equals(other: Any?): Boolean = (this === other)

        override val paramSorts: List<Tag>
            get() = listOf(Bit256)
        override val resultSort: Tag
            get() = Bit256
    }

    @KSerializable
    object Int : Tag() {
        fun readResolve(): Any {
            return Int
        }
        override fun hashCode(): kotlin.Int = hashObject(this)
        override fun equals(other: Any?): Boolean = (this === other)
    }

    @KSerializable
    data class GhostMap(override val paramSorts: List<Tag>, override val resultSort: Tag) : Map() {
        companion object {
            const val namePrefix = "ghostmap"
            const val cross = "*"
            const val arrow = "->"
        }

        init {
            require(paramSorts.isNotEmpty()) { "Cannot construct Tag.GhostMap with empty list of parameter Tags" }
        }

        override fun toString() = super.toString()

        /** Return [GhostMap] with the first parameter removed, or [resultSort] if no parameter is left */
        fun popFirstParam(): Tag =
            when (paramSorts.size) {
                1 -> resultSort
                else -> GhostMap(paramSorts.drop(1), resultSort)
            }
    }

    @KSerializable
    sealed class UserDefined: Tag() {
        abstract val decl: String //string represents a declaration of the type
        abstract val name: String //the name of the type
        @KSerializable
        data class UninterpretedSort(override val name: String) : UserDefined() {
            override val decl: String = "UninterpSort $name"
            override fun toString(): String = "(uninterp) ${this.name}"
        }
        @KSerializable
        data class Struct(override val name: String, val fields: List<Field>) : UserDefined(), TransientTag {
            constructor(fields: List<Field>): this("unnamed struct", fields)

            // fields is a list in case we need order, we could remove this maybe
            init {
                check(fields.distinctBy { field -> field.name }.size == fields.size) {
                    "Impossible to construct struct $name: contains duplicate fields (fields: $fields)"
                }
            }
            @KSerializable
            @Treapable
            data class Field(val name: String, val type: Tag): Serializable
            fun containsField(fieldName: String): Boolean = fieldName in fields.map { field -> field.name }
            fun getField(fieldName: String): Field? = fields.singleOrNull { field -> field.name == fieldName }

            override fun toString(): String = name

            override val decl: String
                = "Struct $name {${fields.joinToString(", ") { field -> "${field.name}: ${field.type}" }}}"
        }

        // Leaving this in case we decide we want tac to be enum-aware
//        data class Enum(val members: List<String>) : UserDefined() {
//            // members is a list because we might need order, or we might need to grab the member->value mapping from
//            // the solidity compiler
//            init {
//                check(members.distinct().size == members.size)
//            }
//        }
    }


    override fun toString(): String {
        return when (this) {
            is Bits -> "bv${bitwidth}"
            Bool -> "bool"
            ByteMap -> "bytemap"
            WordMap -> "wordmap"
            Int -> "int"
            is UserDefined -> this.toString()
            is GhostMap -> GhostMap.namePrefix +
                    "(" +
                    paramSorts.joinToString(GhostMap.cross) +
                    GhostMap.arrow +
                    "$resultSort" +
                    ")"
            BlockchainState -> "storage"
            is CVLArray.RawArray -> "rawarray"
            is CVLArray.UserArray -> "array_${this.elementType}"
        }
    }

    fun isPrimitive(): Boolean {
        return this == Int || this is Bits || this == Bool
    }
}

fun Tag?.isMapType(): Boolean {
    return this is Tag.Map
}
