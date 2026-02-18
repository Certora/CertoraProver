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

package move

import datastructures.*
import java.math.BigInteger
import java.nio.file.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import utils.*

/**
    The deserialization of the "source map" JSON file produced by the Move compiler.  Relates the bytecode to source
    locations, and provides names for function parameters, type parameters, and local variables.

    Note: Move also generates a binary source map in BCS serialization format, but BCS is not self-describing; parsing
    the JSON should be more resiliant to changes in the Move compiler output format.
 */
@KSerializable
data class MoveSourceMap(
    @SerialName("definition_location") val definitionLocation: Location,
    @SerialName("module_name") val moduleNameParts: List<String>,
    @SerialName("struct_map") val structMap: Map<Int, Struct>,
    @SerialName("function_map") val functionMap: Map<Int, Function>,
) {
    fun moduleName(scene: MoveScene): MoveModuleName {
        check(moduleNameParts.size == 2) {
            "Module name must consist of exactly two parts, but got: $moduleNameParts"
        }
        return MoveModuleName(
            scene,
            BigInteger(moduleNameParts[0], 16),
            moduleNameParts[1]
        )
    }

    /**
        Deserializes a file hash.  Sui move compilers since ~v1.65.2 format this as a hex string, but other/older
        compilers format it as an array of unsigned integer bytes.  We accept both formats here.
     */
    object FileHashSerializer : KSerializer<BigInteger> {

        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("FileHashSerializer", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: BigInteger) {
            encoder.encodeString(value.toString(16))
        }

        override fun deserialize(decoder: Decoder): BigInteger {
            return when (val element = decoder.decodeSerializableValue(JsonElement.serializer())) {
                is JsonPrimitive -> {
                    if (element.isString) {
                        fileHashesByString(element.content)
                    } else {
                        throw SerializationException("Expected string or array for file_hash, got primitive: $element")
                    }
                }
                is JsonArray -> {
                    fileHashesByBytes(element.map { it.jsonPrimitive.int.toUByte() })
                }
                else -> {
                    throw SerializationException("Expected string or array for file_hash, got $element")
                }
            }
        }
    }

    @KSerializable
    data class Location(
        @SerialName("file_hash")
        @Serializable(with = FileHashSerializer::class)
        val fileHash: BigInteger,

        @SerialName("start") val start: Int,
        @SerialName("end") val end: Int
    )

    @KSerializable(with = NamedLocationSerializer::class)
    data class NamedLocation(
        @SerialName("name") val nameParts: String,
        @SerialName("location") val location: Location
    ) {
        @Suppress("ForbiddenMethodCall")
        val name = nameParts.split('#')[0]
    }

    @KSerializable
    data class Struct(
        @SerialName("definition_location") val definitionLocation: Location,
        @SerialName("type_parameters") val typeParameters: List<NamedLocation>,
        @SerialName("fields") val fields: List<Location>
    )

    @KSerializable
    data class Function(
        @SerialName("location") val location: Location,
        @SerialName("definition_location") val definitionLocation: Location,
        @SerialName("type_parameters") val typeParameters: List<NamedLocation>,
        @SerialName("parameters") val parameters: List<NamedLocation>,
        @SerialName("returns") val returns: List<Location>,
        @SerialName("locals") val locals: List<NamedLocation>,
        @SerialName("code_map") val codeMap: Map<Int, Location>,
        @SerialName("is_native") val isNative: Boolean
    )

    /**
        Named locations are represented in the Json as arrays.  E.g.:
        ```
            ["name", { file_hash: "...", start: 123, end: 142 }]`
        ```
        We need a custom serializer to handle this format, as the default for array serialization requires uniform
        element types.
     */
    private object NamedLocationSerializer : KSerializer<NamedLocation> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NamedLocation") {
            element<String>("name")
            element<Location>("location")
        }

        override fun serialize(encoder: Encoder, value: NamedLocation) {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.name)
                encodeSerializableElement(descriptor, 1, Location.serializer(), value.location)
            }
        }

        override fun deserialize(decoder: Decoder): NamedLocation {
            check(decoder is JsonDecoder)
            val array = decoder.decodeJsonElement().jsonArray
            check(array.size == 2) { "Expected JSON array with 2 elements" }

            val name = array[0].jsonPrimitive.content
            val value = Json.decodeFromJsonElement(Location.serializer(), array[1])

            return NamedLocation(name, value)
        }
    }

    companion object {
        private val fileHashesByBytes = memoized { bytes: List<UByte> -> BigInteger(1, bytes.toByteArray()) }
        private val fileHashesByString = memoized { bytes: String -> BigInteger(bytes, 16) }
    }
}
