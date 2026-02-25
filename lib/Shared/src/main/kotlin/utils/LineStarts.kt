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

/**
 * the byte offsets of the start of each line in some unspecified array of bytes, sorted in ascending order.
 * a line starts either at the beginning of the file (even if the file is empty), or immediately after a linebreak.
 *
 * this list is sorted in ascending order, is de-duplicated, and (by convention) contains index 0.
 *
 * we consider a linebreak to be one of: "\r\n", '\r', '\n'
 */
@JvmInline
value class LineStarts private constructor(private val startOffsets: List<Int>) {
    /**
     * takes [offset], a non-negative index into some unspecified contiguous array of characters,
     * such that [startOffsets] matches the character offsets of line starts in that array,
     * and returns the [SourcePosition] matching [offset].
     *
     * in other words, this converts an offset-based position to a row/col-based position.
     *
     * practically, since [startOffsets] are byte offsets and [SourcePosition] currently assumes
     * character offsets of a single byte, we expect [offset] to be a byte offset.
     * however, the implementation of this function does not require this.
     */
    fun sourcePosition(offset: Int): SourcePosition {
        require(offset >= 0) { "offset must be non-negative" }

        val lineNumber: Int // 0-based
        val characterOffsetInLine: Int // 0-based

        val searchResult = startOffsets.binarySearch(offset)

        if (searchResult >= 0) {
            /**
             * then, from [List.binarySearch], [offset] exists in [startOffsets].
             * that is, it's the offset of the start of a line.
             *
             * in this case, [List.binarySearch] returns the index of the the value in the list.
             * this index is exactly the number of line starts seen up to [offset], inclusive,
             * when counting in 0-based. and that's exactly equal to the 0-based line number.
             */
            lineNumber = searchResult

            /** [offset] is the first character in the line */
            characterOffsetInLine = 0
        } else {
            /**
             * then, from [List.binarySearch], [offset] is not in [startOffsets].
             * that is, it is not the first character in its line.
             *
             * in this case, [List.binarySearch] returns a negative value to signal that.
             * the insertion point (the index into the list that this value should have been inserted at
             * for the list to remain sorted) can be calculated from this return value:
             */
            val insertionPoint = -searchResult - 1

            /**
             * in this case, since [startOffsets] contains 0 and [offset] > 0, there is a line
             * that starts before [offset]. this must be the line where [offset] is at.
             */
            val lineStartOffset = startOffsets[insertionPoint - 1]

            /** also, the index of that line start offset in the list, is how many lines have started before [offset], minus one */
            lineNumber = insertionPoint - 1

            /** finally, the character offset is the relative offset of the character in its line */
            characterOffsetInLine = offset - lineStartOffset
        }

        return SourcePosition(
            line = lineNumber.toUInt(),
            charByteOffset = characterOffsetInLine.toUInt(),
        )
    }

    /**
     * For a given [lineNumber] (0-based), returns the character offset
     * from the beginning of the file to this [lineNumber].
     */
    fun lineNumberStartOffset(lineNumber: Int): Int? {
        return startOffsets.getOrNull(lineNumber)
    }

    companion object {
        /** constructs a new [LineStarts], if [startOffsets] are valid */
        fun checked(startOffsets: List<Int>): LineStarts {
            val pairs = startOffsets.asSequence().windowed(2)

            for ((first, second) in pairs) {
                require(first <= second) { "values in list must be sorted" }
                require(first != second) { "values in list must be distinct" }
            }

            /** also ensures list is non-empty */
            require(startOffsets.firstOrNull() == 0) { "by convention, there is a line that starts at index 0" }

            return LineStarts(startOffsets)
        }

        /** constructs a new [LineStarts] by calculating the line start offsets from [bytes] */
        fun fromBytes(bytes: ByteArray): LineStarts {
            /** there's always a line starting at byte position 0 */
            val startOffsets = mutableListOf(0)

            /** manual implementation of an iterator because we need lookahead */
            var pos = 0
            fun next(): Byte? = bytes.getOrNull(pos).also { pos += 1 }
            fun peek(): Byte? = bytes.getOrNull(pos)

            while (true) {
                val byte = next() ?: break

                when {
                    byte == CR && peek() == LF -> {
                        /** got CRLF, which we count as a single linebreak */
                        next()

                        startOffsets.add(pos)
                    }
                    byte == CR -> startOffsets.add(pos)
                    byte == LF -> startOffsets.add(pos)
                }
            }

            /** unchecked constructor since we validated the input */
            return LineStarts(startOffsets)
        }

        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}
