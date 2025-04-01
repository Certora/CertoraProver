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

package spec.errors

import utils.Range
import utils.SourcePosition

/** Convenience class for constructing a CVL example from a CVL String with a designated range. */
class ErrorExample private constructor(
    private val lines : List<String>,
    val range : Range.Range,
) {
    val text : String = lines.joinToString("")

    companion object {
        const val DELIMITER = '#'

        /** @return an [ErrorExample] built from the CVL text with a designated range surrounded by `#` characters. */
        operator fun invoke(sourceName : String, delimitedText : String) : ErrorExample {
            val (lines, delimiters) = stripDelimiters(delimitedText)
            assert(delimiters.size == 2) { """
                CVL Examples must contain exactly two `$DELIMITER` characters; but $sourceName contains ${delimiters.size}:
                ${delimitedText.prependIndent("  ┃ ")}
                """.trimIndent()
            }

            return ErrorExample(lines, Range.Range(sourceName, delimiters[0], delimiters[1]))
        }

        /** @return [delimitedText] split into a lines (with delimiters removed) and a list of the delimiter positions */
        private fun stripDelimiters(delimitedText: String) : Pair<List<String>,List<SourcePosition>> {
            // regexes don't seem to add clarity here; we just lex by hand
            var line = 0u
            var col = 0u
            val lines      = mutableListOf(StringBuilder())
            val delimiters = mutableListOf<SourcePosition>()

            delimitedText.forEach {
                when (it) {
                    DELIMITER -> delimiters.add(SourcePosition(line, col))
                    '\n' -> {
                        col = 0u
                        line++
                        lines.last().append(it)
                        lines.add(StringBuilder())
                    }
                    else -> {
                        lines.last().append(it)
                        col++
                    }
                }
            }

            return lines.map { it.toString() } to delimiters
        }
    }

    /** @return [lines] with [range] marked with DELIMITER */
    fun renderWithRange(range : Range.Range) = buildString {
        val startLine = range.start.line.toInt()
        val startChar = range.start.charByteOffset.toInt()
        val endLine = range.end.line.toInt()
        val endChar = range.end.charByteOffset.toInt()

        // output lines [0..startLine-1]
        append(lines.subList(0, range.start.line.toInt()).joinToString(""))

        // output lines [startLine..endLine]
        append(lines[startLine].substring(0 until startChar))
        append(DELIMITER)
        if (range.start.line == range.end.line) {
            append(lines[startLine].substring(startChar until endChar))
        }
        else {
            append(lines[startLine].substring(startChar))
            append(lines.subList(startLine+1, endLine).joinToString(""))
            append(lines[endLine].substring(0,endChar))
        }
        append(DELIMITER)
        append(lines[endLine].substring(endChar))
        append("")

        // output lines[endLine+1..]
        append(lines.subList(range.end.line.toInt()+1, lines.size).joinToString(""))
    }
}
