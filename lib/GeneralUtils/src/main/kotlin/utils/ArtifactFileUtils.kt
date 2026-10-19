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

import mu.KotlinLogging
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.extension
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import java.io.File
import java.io.FileWriter

private val logger = KotlinLogging.logger {}

object ArtifactFileUtils {
    fun hasCertoraDir(): Boolean {
        return System.getenv().containsKey("CERTORA")
    }

    fun getCertoraDir(): String {
        return System.getenv("CERTORA")
    }


    fun getNameFromPath(name: String): String {
        return File(name).name
    }

    fun getResultFile(): String {
        return "Results.txt"
    }

    fun createFolderIfNotExists(dirString: String) {
        val dir = File(dirString)
        if (!dir.exists()) {
            val success = dir.mkdirs()
            if (!success && !dir.exists()) {
                throw Exception("Failed to create directory $dirString")
            }
        }
    }

    /**
     * Given a filesystem path, search for directory "newRoot" in the path and
     * return the path from "newRoot" on
     * @param wholePath the filesystem path we wish to re-root (ie "emv-3-certora-29-Mar--14-59/inputs/.certora_config/filename1")
     * @param newRootDir the new root directory (ie ".certora_config")
     * @return the re-rooted path (ie ".certora_config/filename1")
     */
    fun getRelativeFileName(wholePath: String, newRootDir: String) : String {
        if (newRootDir !in wholePath) {
            throw Exception("Could not find $newRootDir in $wholePath")
        }
        var cfgDirPathIdx = 0
        val path = FileSystems.getDefault().getPath(wholePath)
        path.iterator().run ca@{
            forEach {
                if (it.toString() != newRootDir) {
                    ++cfgDirPathIdx
                } else {
                    return@ca
                }
            }
        }
        return path.subpath(cfgDirPathIdx, path.nameCount).toString()
    }

    fun isTAC(fileName: String): Boolean {
        return File(fileName).extension.equals("tac", ignoreCase = true)
    }

    fun isSol(fileName: String): Boolean {
        return File(fileName).extension.equals("sol", ignoreCase = true)
    }

    fun isWasm(fileName: String): Boolean {
        return File(fileName).extension.equals("wasm", ignoreCase = true)
    }

    fun isSolana(fileName: String): Boolean {
        val ext = File(fileName).extension.lowercase()
        return  ext == "o" || ext == "so"
    }

    // Filename includes extension. Basename is without extension
    fun getBasenameOnly(fileName: String): String {
        val separators = "/|\\\\" // We sometimes invoke with linux separators on windows environment
        return fileName.split(Regex(separators)).last()
            .let { filename ->
                File(filename).nameWithoutExtension
            }
    }

    /** Our vanilla file names tend to contain special characters like '(',')',',','[]' this converts them to more
     * OS-friendly strings as well as ensuring the file name length limit. */
    private fun sanitizeFileBaseName(_filename: String, extensionLength: Int): String {
        var filename = _filename
        for (p in conversion) {
            filename = filename.replace(p.first, p.second)
        }
        val maxLength = MAX_FILE_NAME_LENGTH - extensionLength
        require(maxLength > 1)
        { "extension length ($extensionLength) larger than $MAX_FILE_NAME_LENGTH -- unexpected" }
        return if (filename.length > maxLength) {
            // the hash should make collisions that are due to the shortening very unlikely
            val hash = filename.hashCode().toString(Character.MAX_RADIX)
            check(maxLength - 1 - hash.length > 1)
            { "hash ($hash) is longer than a more-than-max-length file name ($filename) -- unexpected" }
            filename.substring(0, maxLength - 1 - hash.length) + "_" + hash
        } else {
            filename
        }
    }

    private fun appendExtension(basename: String, extension: String): String {
        require("." !in extension) { "File name extension ($extension) contains a \".\"." }
        return if (extension.isEmpty()) {
            basename
        } else {
            "$basename.$extension"
        }
    }

    private fun dirName(filePath: String): String {
        return File(filePath).parent?.let { it + File.separator }.orEmpty()
    }

    fun dirSize(dir : Path) : Long {
        check(dir.isDirectory()) { "trying to caclulate dir size on non dir path: ${dir.absolutePathString()}" }
        return dir.toFile().walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }


    fun numFiles(dir: Path) : Int {
        check(dir.isDirectory()) { "trying to collect num files on non dir path: ${dir.absolutePathString()}" }
        return dir.toFile().walkTopDown().count { it.isFile }
    }

    /**
     * Wrapper class for sanitized paths.
     */

    class SanitizedPath(filePath: String) {
        val dirPart: String
        val fileExtension: String
        val sanitizedFileBaseName: String
        val sanitizedFullPath: String

        init {
            dirPart = dirName(filePath)
            fileExtension = File(filePath).extension
            sanitizedFileBaseName = sanitizeFileBaseName(getBasenameOnly(filePath), fileExtension.length + 1)
            sanitizedFullPath = "$dirPart${appendExtension(sanitizedFileBaseName, fileExtension)}"
        }
    }

    fun getExtensionMethod(filePath: String): String {
        return File(filePath).extension
    }

    /**
     * Takes a full file path and sanitize it (e.g replace special characters or truncate too long name).
     * @return the sanitized path.
     */
    fun sanitizePath(filePath: String) = SanitizedPath(filePath).sanitizedFullPath

    /**
     * Takes a file (full path) that the caller wants to write to, sanitizes the path (e.g. special characters or too
     *  long file names), and returns a [FileWriter] that can be used to write to that file.
     * @return a [FileWriter] that can be used to write to the new file
     */
    fun getWriterForFile(filePath: String, overwrite: Boolean = false, append: Boolean = false) : FileWriter {
        return getWriterAndActualFileName(filePath, overwrite, append).first
    }

    fun getWriterAndActualFileName(filePath: String, overwrite: Boolean = false, append: Boolean = false): Pair<FileWriter, String> {
        val actualFileName = getActualFileName(filePath, overwrite, append)
        return FileWriter(actualFileName, append) to actualFileName
    }

    /** Atomically create an empty file if and only if it does not already exist.  Returns true if successful. */
    private fun reserveFileName(filePath: String) = try {
        Files.createFile(Path.of(filePath))
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }

    fun getActualFile(filePath: String, overwrite: Boolean = false, append: Boolean = false): File {
        val actualName = getActualFileName(filePath, overwrite, append)
        return File(actualName)
    }

    private fun getActualFileName(filePath: String, overwrite: Boolean, append: Boolean): String {
        val sanitizedPath = SanitizedPath(filePath)

        // We may be in a race with another thread trying to create a file with the same parameters; retry with
        // increasing numeric suffixes until we can claim a name for ourselves.
        var retryCount = 0
        while (true) {
            val nonConflictingFileBaseName = if (File(sanitizedPath.sanitizedFullPath).exists() && !(overwrite || append)) {
                val uniqueSuffix = "_${++retryCount}"
                if (sanitizedPath.sanitizedFileBaseName.length + uniqueSuffix.length + 1 + sanitizedPath.fileExtension.length > MAX_FILE_NAME_LENGTH) {
                    check(sanitizedPath.sanitizedFileBaseName.length - uniqueSuffix.length > 1) {
                        "length of sanitized file name ($sanitizedPath.sanitizedFileBaseName) minus length of uniqueSuffix ($uniqueSuffix) " +
                            "is negative, even though the expected file length exceeds the maximum allowed file name " +
                            "length ($MAX_FILE_NAME_LENGTH) --> unexpected "
                    }
                    sanitizedPath.sanitizedFileBaseName.substring(
                        0, sanitizedPath.sanitizedFileBaseName.length - uniqueSuffix.length
                    ) + uniqueSuffix
                } else {
                    sanitizedPath.sanitizedFileBaseName + uniqueSuffix
                }
            } else {
                sanitizedPath.sanitizedFileBaseName
            }
            val actualFileName =
                "${sanitizedPath.dirPart}${appendExtension(nonConflictingFileBaseName, sanitizedPath.fileExtension)}"

            if (overwrite || append || reserveFileName(actualFileName)) {
                return actualFileName
            } else {
                // wait a bit so we get a different timestamp, and try again.
                Thread.sleep(10 /* milliseconds */)
            }
        }
    }

    private val conversion = listOf(
        Pair("(", "LP"),
        Pair(")", "RP"),
        Pair(",", "C"),
        Pair("[", "LB"),
        Pair("]", "RB"),
        Pair("[]", "LBRB"),
        Pair("uint256", "U256"),
        Pair("address", "ADR"),
        Pair("bytes32", "B32")
    )

    /**
     * The maximum file name length allowed by the file system.
     *
     * Linux filesystems commonly allow 255 bytes, but eCryptfs only allows 143. Since the JVM does not expose
     * NAME_MAX portably, use the conservative limit by default. CERTORA_MAX_FILENAME can override it when needed.
     */
    val MAX_FILE_NAME_LENGTH = System.getenv("CERTORA_MAX_FILENAME")?.toIntOrNull() ?: 143

    @Deprecated(
        message = "Never use this function. DO NOT reintroduce any translation back to strings with ABI chars",
        level = DeprecationLevel.ERROR
    )
    fun String.normalizeEncodedStringToStringWithABIChars(): String {
        var filename = this;
        for (p in conversion) {
            filename = filename.replace(p.second, p.first)
        }
        return filename
    }

    // note that if we got absolute paths, there's nothing for us to wrap or unwrap.
    // the results will be wrong if we do wrap/unwrap on absolute paths.
    // it also means something is seriously wrong and the job will not succeed in cloud.
    // consider throwing an exception.
    fun wrapPathWith(path: String, srcDir: String) = if (Path(path).isAbsolute) {
        path
    } else {
        "${srcDir}${File.separator}${path}"
    } // try Path(srcDir).resolve(path)

}
