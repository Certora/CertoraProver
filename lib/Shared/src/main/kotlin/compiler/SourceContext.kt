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

package compiler

import com.certora.collect.*
import log.Logger
import log.LoggerTypes
import report.TreeViewLocation
import utils.Range
import utils.*
import java.io.File
import java.io.Serializable

private val logger = Logger(LoggerTypes.COMMON)

/**
 * [indexToFilePath] maps an index used by solc to identify a source file to its path, where a path
 * is one that CVT is aware of (i.e. within .certora_config)
 */
@KSerializable
@Treapable
data class SourceContext(
    val indexToFilePath: Map<Int, String>,
    val sourceDir: String
) : Serializable

/**
 * The basic unit of information about source provided by solc.
 * [source] - an index into sources list, resolvable using a [SourceContext]
 * [begin] - offset in file where the relevant source code starts, in bytes, in the file resolved by [source]
 * [len] - length in bytes of the portion of relevant source code
 */
@KSerializable
@Treapable
data class SourceIdentifier(val source: Int, val begin: Int, val len: Int): Serializable {
    /** the relative file matching this identifier, as provided by solc */
    fun relativeSourceFile(context: SourceContext): File? = context.indexToFilePath[source]?.let(::File)

    fun resolve(context: SourceContext): File? {
        val sourceFile = relativeSourceFile(context)
        if (sourceFile == null) {
            logger.info { "source identifier $this not found in solc sources map ${context.indexToFilePath}" }
            return null
        }

        val certoraSourcesDir = CertoraFileCache.certoraSourcesDir()
        val resolved = certoraSourcesDir.resolve(context.sourceDir).resolve(sourceFile)

        return if (resolved.isFile()) {
            resolved
        } else {
            logger.info { "source identifier $this could not be resolved in sources dir $certoraSourcesDir" }
            null
        }
    }

    override fun toString() = "$begin:$len:$source"
}

@KSerializable
@Treapable
data class SourceSegment(
    val range: Range.Range,
    val content: String
): Serializable, TreeViewLocation by range {
    /** Canonical representation of [content] with trimmed spaces. */
    val sanitizedContent: String
        get() = content.trim().splitAndJoin()

    /**
     * [sanitizedContent] with location in the source code.  The location is in comment, since we want a
     * representation of syntactically legal Solidity code.
     */
    val sanitizedContentWithLoc: String
        get() = "/* ${range.fileName}: ${range.lineNumber}: */ ${sanitizedContent.condense(1)}"

    companion object {
        /**
         * creates a [SourceSegment] by reading [size] bytes from [sourceFile], beginning at byte [startIndex].
         *
         * [relativeFile] is a string to display as the file's path, expected to be relative to the sources dir.
         * this string is user-facing.
         *
         * XXX: [relativeFile] is a kludge. it should exactly match [sourceFile], but currently may not
         * because we still don't resolve all sources to their non-autofinder version.
         * ideally, this parameter should be removed.
         */
        operator fun invoke(sourceFile: File, startIndex: Int, size: Int, relativeFile: String): SourceSegment? {
            if (startIndex < 0 || size < 0) {
                return null
            }

            val (allBytes, lineStarts) = try {
                val s = sourceFile.toString()
                CertoraFileCache.byteContent(s) to CertoraFileCache.lineStarts(s)
            } catch (e: CertoraFileCacheException) {
                logger.warn(e.cause) { "Had error when reading from source file $sourceFile ($relativeFile)" }
                return null
            }

            if (allBytes.size < startIndex + size) {
                logger.warn { "Source file $relativeFile is shorter than expected!! Read ${allBytes.size} < $startIndex + $size" }
                return null
            }

            val range = Range.Range(
                relativeFile,
                start = lineStarts.sourcePosition(startIndex),
                end = lineStarts.sourcePosition(startIndex + size),
            )

            return SourceSegment(
                range,
                content = String(allBytes, startIndex, size).removeNonPrintableChars()
            )
        }

        fun resolveFromContext(context: SourceContext, identifier: SourceIdentifier): SourceSegment? {
            val sourceFile = identifier.resolve(context) ?: return null
            val relativeFile = identifier.relativeSourceFile(context)?.toString() ?: return null
            return SourceSegment(sourceFile, identifier.begin, identifier.len, relativeFile)
        }
    }
}


@Suppress("ForbiddenMethodCall") // for Regex
private fun String.removeNonPrintableChars() = replace(Regex( "[^\\P{C}\\s]"), "").let {
    if (it == this) { it } else { "$it (non-printable chars have been removed)" }
}
