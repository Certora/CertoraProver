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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)

package move

import com.charleskorn.kaml.*
import java.math.BigInteger
import java.nio.file.*
import kotlinx.serialization.*
import utils.*

/**
    Parses the Move build info found in BuildInfo.yaml files.
 */
@KSerializable
data class MoveBuildInfo(
    @SerialName("compiled_package_info") val compiledPackageInfo: CompiledPackageInfo
) {
    @KSerializable
    data class CompiledPackageInfo(
        @SerialName("package_name") val packageName: String,
        @SerialName("address_alias_instantiation") val addressAliasInstantiation: Map<String, BigInteger>
        // there is a lot more info available, but we don't need it yet
    )

    companion object {
        private val yaml = Yaml(
            configuration = YamlConfiguration(
                strictMode = false
            )
        )
        fun parse(path: Path): MoveBuildInfo {
            return yaml.decodeFromString<MoveBuildInfo>(Files.readString(path))
        }
    }
}
