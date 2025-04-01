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

package event

import event.exception.CvtMetadataKeyUndefinedException
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import utils.*
import java.time.Instant

/** a serialization of RunMetadata aka certora_metadata.json */
@KSerializable
data class RunMetadata(
    @SerialName("raw_args")
    val rawArgs: List<String>,
    val conf: Map<String, JsonElement>,
    val origin: String,
    val revision: String,
    val branch: String,
    @SerialName("cwd_relative")
    val cwdRelative: String,
    val dirty: Boolean,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
    @SerialName("CLI_version")
    val cliVersion: String,
    @SerialName("CLI_package_name")
    val cliPackageName: String,
    @SerialName("python_version")
    val pythonVersion: String
) {
    companion object {
        fun deserializeFromJson(s: String): RunMetadata =
            try {
                Json {
                    ignoreUnknownKeys = true
                }.decodeFromString(s)
            } catch (e: SerializationException) {
                throw CertoraException(
                    CertoraErrorType.INCOMPATIBLE_SERIALIZATION,
                    "The Certora CLI version is incompatible with the Certora Cloud version. You may need to update your Certora CLI. " +
                        "Users running on a specific Prover branch may need to install a specific CLI release.",
                    e
                )
            }

    }
}

/**
 * the Python class which we deserialize to [RunMetadata] contains an untyped map - a Dict[str, Any]
 * we deserialize it to an untyped map on the Kotlin side, to preserve the fields of the original Dict.
 * this class is a typed version of that untyped map, but containing only the keys we currently need.
 */
data class RunMetadataConf(
    val spec: List<String>,
    val verified: String,
) {
    companion object {
        fun fromUntypedMap(map: Map<String, JsonElement>): RunMetadataConf {
            val spec = map["files"]?.arrayOfStringPrimitives() ?: throw CvtMetadataKeyUndefinedException("files")
            val verified = map["verify"]?.stringPrimitive() ?: throw CvtMetadataKeyUndefinedException("verify")

            return RunMetadataConf(spec, verified)
        }
    }
}

/** serializes [Instant] to `SECONDS.NANOS` where seconds and nanos are the time since the Unix epoch */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        val sec = value.epochSecond
        val nano = value.nano

        encoder.encodeString("$sec.$nano")
    }

    override fun deserialize(decoder: Decoder): Instant {
        val (l, r) = decoder.decodeString().splitAtDot()

        val sec = l.toLongOrNull() ?: throw SerializationException("expected seconds, got $l")
        val nano  = r.toLongOrNull() ?: throw SerializationException("expected nanoseconds, got $r")

        return Instant.ofEpochSecond(sec, nano)
    }

    private fun String.splitAtDot(): Pair<String, String> {
        val dotPos = indexOf('.')
        if (dotPos <= 0) { throw SerializationException("expected exactly 2 components separated by .") }

        val before = substring(0, dotPos)
        val after = substring(dotPos + 1)

        if (after.indexOf('.') != -1) { throw SerializationException("expected exactly 2 components separated by .") }

        return before to after
    }
}

private fun JsonElement.stringPrimitive() = tryAs<JsonPrimitive>()?.contentOrNull
private fun JsonElement.arrayOfStringPrimitives() = tryAs<JsonArray>()?.monadicMap(JsonElement::stringPrimitive)
