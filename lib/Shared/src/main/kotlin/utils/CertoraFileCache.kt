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

package utils

import config.Config
import config.SOURCES_SUBDIR
import java.io.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path

class CertoraFileCacheException(override val cause: Exception, file: File) : Exception(
    "Failed to retrieve file: while attempting to cache $file, got exception: $cause"
)

/**
 * This class caches file content so that we do not have to access a file on the filesystem.
 * This is useful in the situation where we only have a run-repro.json file that contains a JSON
 * representation of the various .certora_* files, including files present in the .certora_config directory.
 */
object CertoraFileCache {

    private val fileToString = ConcurrentHashMap<CanonFile, String>()
    private val fileToBytes = ConcurrentHashMap<CanonFile, ByteArray>()
    private val fileToLineStarts = ConcurrentHashMap<CanonFile, LineStarts>()

    const val CARGO_HOME_PREFIX = "/CARGO_HOME/"

    fun certoraSourcesDir(): File {
        val buildDir = Config.CertoraBuildDirectory.get()
        return File(buildDir).resolve(SOURCES_SUBDIR).normalize()
    }

    /**
     * XXX: we're handing out a [Reader] even though we cache the entire string.
     * maybe the users of this should be handling strings instead.
     */
    fun getContentReader(fileName: String): Reader {
        val content = stringContent(fileName)
        return StringReader(content)
    }

    fun stringContent(fileName: String): String {
        val canonFile = canonicalize(fileName)
        return fileToString.computeIfAbsent(canonFile) {
            /** if we ended up fetching the bytes from storage, we might as well cache them */
            val bytes = loadBytes(canonFile)

            /** this assumes UTF-8 */
            String(bytes)
        }
    }

    fun byteContent(fileName: String): ByteArray {
        val canonFile = canonicalize(fileName)
        return loadBytes(canonFile)
    }

    fun lineStarts(fileName: String): LineStarts {
        val canonFile = canonicalize(fileName)
        return fileToLineStarts.computeIfAbsent(canonFile) {
            val bytes = loadBytes(canonFile)
            LineStarts.fromBytes(bytes)
        }
    }

    private fun canonicalize(fileName: String): CanonFile {
        val readerPath = findReaderPath(fileName).ifEmpty { fileName }
        return CanonFile(readerPath)
    }

    // return the relative path from CERTORA_INTERNAL_ROOT, if there is not such path empty string is returned
    private fun findReaderPath(path: String): String {
        if (File(path).exists()) {
            return path
        }
        val index = path.indexOf(SOURCES_SUBDIR)
        var relPath = ""
        if (index >= 0) {
            relPath = path.substring(index)
            if (File(relPath).exists()) {
                return relPath
            }
            // There are cases where generated files that were generated during past builds are sent to the reader
            // these files will be under an old build directory that does not exist anymore, in these cases
            // we look for the file in the current build directory and return it
            val pathInBuild = Path(Config.CertoraBuildDirectory.get()).resolve(relPath).toString()
            if (File(pathInBuild).exists()) {
                return pathInBuild
            }
        }
        return relPath
    }

    /**
     * Resolves a given [path] string that starts in [CARGO_HOME_PREFIX] to the absolute path.
     * Returns null in case it doesn't start in the prefix.
     *
     * If the global environment variable CARGO_HOME is set, and the path exists, or if the default .cargo
     * home location (folder .cargo in the user's home directory) exists, the relative path
     * (retained by stripping [CARGO_HOME_PREFIX] from [path]) is resolve from this folder.
     *
     * Returns the absolute resolved path if existing.
     */
    fun tryResolvePathInCargoHome(path: String): Path? {
        @Suppress("ForbiddenMethodCall")
        if(!path.startsWith(CARGO_HOME_PREFIX)){
            return null
        }
        fun existingFileOrNull(file: File): File? {
            return if (file.exists()) {
                file
            } else {
                null
            }
        }
        // Takes the user's environment variable CARGO_HOME folder or attempts to use the default location
        // which is .cargo in the user's home folder
        val cargoHome = System.getenv("CARGO_HOME")?.let {
            existingFileOrNull(File(it))
        } ?: run {
            val userHome = System.getProperty("user.home")
            existingFileOrNull(File(userHome, ".cargo"))
        }

        if (cargoHome == null) {
            //Could not resolve file in CARGO_HOME folder
            return null
        }

        val relativeFromCargoHome = path.replace(CARGO_HOME_PREFIX, "")
        // Resolve the relative path from Cargo home path.
        return Path(cargoHome.path, relativeFromCargoHome)
    }

    /** read [canonFile] as bytes and cache the result, or fetch existing cache content if available */
    private fun loadBytes(canonFile: CanonFile): ByteArray = fileToBytes.computeIfAbsent(canonFile) {
        val file = canonFile.canon
        try {
            Files.readAllBytes(file.toPath())
        } catch (e: FileNotFoundException) {
            throw CertoraFileCacheException(e, file)
        } catch (e: NoSuchFileException) {
            throw CertoraFileCacheException(e, file)
        } catch (e: IOException) {
            throw CertoraFileCacheException(e, file)
        }
    }
}
