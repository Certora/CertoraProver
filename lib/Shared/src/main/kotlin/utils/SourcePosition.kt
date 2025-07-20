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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * a line/character coordinate in a source code document. this coordinate is 0-based.
 *
 * [charByteOffset] is the character position in a line. as the naming implies,
 * we currently define a character to be a single byte. anecdotally, this seems to be correct with
 * regards to the the data we're getting from solc.
 */
@Serializable
@Treapable
data class SourcePosition(val line: UInt, val charByteOffset: UInt) : AmbiSerializable, Comparable<SourcePosition> {

    constructor(loc: ComplexSymbolFactory.Location) : this(loc.line.toUInt(), loc.column.toUInt())

    //The IDE coordinates are 1-based (first line is 1, first character is between character 1 and 2)
    //this is because the Monaco API expects 1-based positions
    val lineForIDE: Int get() = line.toInt() + 1
    //the implementation here assumes a character is a single byte
    val characterForIDE: Int get() = charByteOffset.toInt() + 1

    override fun compareTo(other: SourcePosition): Int {
        return when {
            this.line != other.line -> this.line compareTo other.line
            this.charByteOffset != other.charByteOffset -> this.charByteOffset compareTo other.charByteOffset
            else -> 0
        }
    }

    fun toJsonObject() = buildJsonObject {
        put("line", lineForIDE)
        put("col", characterForIDE)
    }

    override fun toString() = "$lineForIDE:$characterForIDE"

    companion object {
        @JvmStatic
        fun zero(): SourcePosition = SourcePosition(0u, 0u)
    }
}