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

import com.certora.collect.Treapable
import java_cup.runtime.ComplexSymbolFactory
import kotlinx.serialization.Serializable
import report.TreeViewLocation

/**
 * Start and end of syntactic element's location in the document, used for the LSP.
 * Note that these coordinates are 0-based.
 */
@Serializable
@Treapable
sealed class Range : AmbiSerializable, Comparable<Range> {

    /**
     * Display enough information about `this` to make it useful in the context of an error message at [base].
     * For example, returns "on line 10" if this and base are in the same file, or "at File.spec:10" if this and base
     * are in different files.
     */
    abstract fun relativeDescription(base : utils.Range) : String

    @Serializable
    data class Range(val specFile: String, override val start: SourcePosition, override val end: SourcePosition) :
        utils.Range(),
        TreeViewLocation {

        init {
            check(start.isPrevious(end)){"end position cannot come before the start position"}
        }
        constructor(_start: ComplexSymbolFactory.Location, _end: ComplexSymbolFactory.Location) : this(_start.unit,
            SourcePosition(_start),
            SourcePosition(_end)
        )

        override fun toString(): String = "${fileName}:${start}"

        override fun relativeDescription(base : utils.Range): String {
            return when(base) {
                is Empty -> "at $this"
                is Range ->
                    if (base.specFile != specFile)         { "at $this" }
                    else if(base.start.line == start.line) { "at position $start" }
                    else                                   { "on line ${start.lineForIDE}" }
            }
        }

        override val file: String get() = specFile

        override fun compareTo(other: utils.Range): Int {
            if (other is Empty) {
                // The empty range is smaller than any "real" range
                return 1
            }

            other as Range
            return when {
                this.specFile != other.specFile -> this.specFile compareTo other.specFile
                this.start != other.start -> this.start compareTo other.start
                this.end != other.end -> this.end compareTo other.end
                else -> 0
            }
        }

        /** attempts to open [file] and return the string demarcated by [start] and [end] */
        fun slicedString(): String? {
            val resolved = CertoraFileCache.certoraSourcesDir().resolve(this.file)

            return try {
                val fileContents = CertoraFileCache.stringContent(resolved.toString())
                slicedString(fileContents.lineSequence(), this)
            } catch (_: CertoraFileCacheException) {
                return null
            } catch (_: StringIndexOutOfBoundsException) {
                return null
            }
        }
    }

    /**
     * @param comment a comment (e.g. for debugging)
     */
    @Serializable
    data class Empty(val comment: String = "") : utils.Range() {
        override fun toString(): String   = "[internally generated]"
        override fun relativeDescription(base : utils.Range): String = "in internally generated code"

        override fun compareTo(other: utils.Range): Int {
            if (other is Range) {
                // The empty range is smaller than any "real" range
                return -1
            }
            return 0
        }
    }

}

@Suppress("ReplaceManualRangeWithIndicesCalls")
private fun slicedString(lines: Sequence<String>, range: Range.Range): String {
    /** both the start and end line positions are inclusive */
    val startLine = range.start.line.toInt()
    val endLine   = range.end.line.toInt()

    /** the start char position is inclusive, while the end is exclusive */
    val startCharIncl = range.start.charByteOffset.toInt()
    val endCharExcl   = range.end.charByteOffset.toInt()

    val iter = lines
        .withIndex()
        .drop(startLine)
        .take(endLine - startLine + 1)

    val sb = StringBuilder()

    for ((lineNum, line) in iter) {

        /**
         * we already validated that start <= end.
         * in particular, for the case where the start line is also the end line,
         * we know the difference between the end char and the start char is non-negative.
         */
        val lineRange = when {
            lineNum == startLine && lineNum == endLine -> startCharIncl..<endCharExcl
            lineNum == startLine                       -> startCharIncl..<line.length
            lineNum == endLine                         -> 0..<endCharExcl
            else                                       -> 0..<line.length
        }

        val slice = line.substring(lineRange)
        if (lineNum == endLine) {
            sb.append(slice)
        } else {
            sb.appendLine(slice) // has the side-effect of normalizing line endings to LF
        }
    }

    return sb.toString()
}
