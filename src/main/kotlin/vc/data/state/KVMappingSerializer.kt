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

package vc.data.state

import com.certora.collect.*
import datastructures.TreapMapSerializer
import datastructures.TreapSetSerializer
import datastructures.stdcollections.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.*
import utils.BigIntegerSerializer
import java.math.BigInteger

/**
 * Implemented based on this reference:
 * https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#hand-written-composite-serializer
 */
object WordMapSerializer: KSerializer<TACValue.MappingValue.KVMapping.WordMap> {

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): TACValue.MappingValue.KVMapping.WordMap =
        decoder.decodeStructure(descriptor) {
            var value: TreapMap<TACValue.PrimitiveValue.Integer, TACValue.PrimitiveValue.Integer> = treapMapOf()
            var initialValue: TACValue.PrimitiveValue.Integer? = null
            var sanityCount = 0
            while(true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> value = decodeSerializableElement(descriptor, index,
                        wordMapKeySerializer)
                    1 -> initialValue = decodeNullableSerializableElement(descriptor, index,
                        initialValueSerializer)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("unexpected index when deserializing WordMap. index=$index")
                }
                sanityCount++
            }
            check(sanityCount == 2) { "not all WordMap fields were deserialized. count=$sanityCount"}
            TACValue.MappingValue.KVMapping.WordMap(value, initialValue)
        }

    private val initialValueSerializer: KSerializer<TACValue.PrimitiveValue.Integer?> =
        TACValue.PrimitiveValue.Integer.serializer().nullable
    private val wordMapKeySerializer: KSerializer<TreapMap<TACValue.PrimitiveValue.Integer, TACValue.PrimitiveValue.Integer>> =
        TreapMapSerializer(TACValue.PrimitiveValue.Integer.serializer(), TACValue.PrimitiveValue.Integer.serializer())
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(TACValue.MappingValue.KVMapping.WordMap::class.java.canonicalName) {
            element("value", wordMapKeySerializer.descriptor)
            element("initialValue", initialValueSerializer.descriptor)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: TACValue.MappingValue.KVMapping.WordMap) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, wordMapKeySerializer, value.value)
            encodeNullableSerializableElement(descriptor, 1, initialValueSerializer, value.initialValue)
        }
    }
}

/**
 * Implemented based on this reference:
 * https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/serializers.md#hand-written-composite-serializer
 */
object ByteMapSerializer: KSerializer<TACValue.MappingValue.KVMapping.ByteMap> {
    private val mapSerializer: KSerializer<TreapMap<BigInteger, Byte>> =
        TreapMapSerializer(BigIntegerSerializer(), Byte.serializer())
    private val primitiveValueSerializer: KSerializer<TACValue.PrimitiveValue.Integer> =
        TACValue.PrimitiveValue.Integer.serializer()
    private val boundSerializer: KSerializer<TACValue.MappingValue.KVMapping.Bound?> =
        TACValue.MappingValue.KVMapping.Bound.serializer().nullable
    private val accessedIndicesSerializer: KSerializer<TreapSet<TACValue.PrimitiveValue.Integer>> =
        TreapSetSerializer(primitiveValueSerializer)
    private val initialValueSerializer: KSerializer<TACValue.PrimitiveValue.Integer?> =
        TACValue.PrimitiveValue.Integer.serializer().nullable

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): TACValue.MappingValue.KVMapping.ByteMap {
        return decoder.decodeStructure(descriptor) {
            var map: TreapMap<BigInteger, Byte> = treapMapOf()
            var accessedIndices: TreapSet<TACValue.PrimitiveValue.Integer> = treapSetOf()
            var bound: TACValue.MappingValue.KVMapping.Bound? = null
            var initialValue: TACValue.PrimitiveValue.Integer? = null
            var sanityCount = 0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> map = decodeSerializableElement(descriptor, index, mapSerializer)
                    1 -> accessedIndices = decodeSerializableElement(descriptor, index, accessedIndicesSerializer)
                    2 -> bound = decodeNullableSerializableElement(descriptor, index, boundSerializer)
                    3 -> initialValue = decodeNullableSerializableElement(descriptor, index,
                        initialValueSerializer)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("unexpected index when deserializing ByteMap. index=$index")
                }
                sanityCount++
            }
            check(sanityCount == 4) { "not all ByteMap fields were deserialized. count=$sanityCount"}
            TACValue.MappingValue.KVMapping.ByteMap(map, accessedIndices, bound, initialValue)
        }
    }

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(TACValue.MappingValue.KVMapping.ByteMap::class.java.canonicalName) {
            element("map", mapSerializer.descriptor)
            element("accessedIndices", accessedIndicesSerializer.descriptor)
            element("bound", boundSerializer.descriptor)
            element("initialValue", initialValueSerializer.descriptor)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: TACValue.MappingValue.KVMapping.ByteMap) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, mapSerializer, value.map)
            encodeSerializableElement(descriptor, 1, accessedIndicesSerializer, value.accessedIndices)
            encodeNullableSerializableElement(descriptor, 2, boundSerializer, value.bound)
            encodeNullableSerializableElement(descriptor, 3, initialValueSerializer, value.initialValue)
        }
    }
}
