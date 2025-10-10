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

package prefixgen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import prefixgen.data.TaggedFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists

/**
 * Useful utilities for interacting with the data serialized on the file system.
 */
object FileUtils {
    @PublishedApi
    internal val jsonInstance = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    /**
     * Deserialize the type [T] from the file [fileName].
     * When possible, use [TaggedFile] instead.
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> deserializeFile(fileName: String) = FileInputStream(File(fileName)).use { stream ->
        jsonInstance.decodeFromStream<T>(stream)
    }

    /**
     * Deserialize the type [T] from the file with the name `this.
     * When possible, use [TaggedFile] instead.
     */
    @JvmName("deserializeFileExt")
    inline fun <reified T> String.deserializeFile() = deserializeFile<T>(this)

    /**
     * Deserialize the type [T] from the [TaggedFile].
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> TaggedFile<T>.deserialize() = FileInputStream(this.f).use { stream ->
        jsonInstance.decodeFromStream<T>(stream)
    }

    /**
     * Serialize the value of type [v] to the [TaggedFile]. NB this will overwrite any previous
     * contents at the location referenced by this.
     */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> TaggedFile<T>.serialize(v: T) = FileOutputStream(this.f).use { stream ->
        jsonInstance.encodeToStream(v, stream)
    }

    /**
     * Ensure that `this` exists, and if it doesn't, make the directory.
     */
    fun Path.mustDirectory() : Path = this.also { p ->
        if(!p.exists()) {
            p.createDirectory()
        }
    }

    /**
     * As above, but for [File]
     */
    fun File.mustDirectory() : Path = this.toPath().mustDirectory()
}
