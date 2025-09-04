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

package sbf.domains

import sbf.support.SolanaInternalError
import com.certora.collect.*
import datastructures.stdcollections.*
import org.jetbrains.annotations.TestOnly

interface StackEnvironmentValue<Value> {
    fun isBottom():Boolean
    fun isTop(): Boolean
    fun mkTop(): Value
    fun join(other: Value): Value
    fun widen(other: Value): Value
    fun lessOrEqual(other: Value): Boolean
}

class StackEnvironmentError(msg: String): SolanaInternalError("StackEnvironment error:$msg")

data class ByteRange(val offset: Long, val width: Byte)

/** An immutable environment map for stack slots **/
class StackEnvironment<Value: StackEnvironmentValue<Value>>(
    /** A map entry ((offset, width), value)) represents that
     * the consecutive bytes [offset,...,offset+width) has the value @value
     *
     * **Very importantly**, this class does not check that overlap entries are mapped to consistent values.
     * If this is important then the client must ensure that.
    **/
    private val map: TreapMap<ByteRange, Value> = treapMapOf(),
    /** Denote empty environment:
    *  If any value stored in the environment is bottom the whole environment becomes bottom
    **/
    private val isBot: Boolean = false):  Iterable<Map.Entry<ByteRange, Value>>  {


    companion object {
        fun <Value: StackEnvironmentValue<Value>> makeTop(): StackEnvironment<Value> {
            return StackEnvironment()
        }
        fun <Value: StackEnvironmentValue<Value>> makeBottom(): StackEnvironment<Value> {
            return StackEnvironment(treapMapOf(),true)
        }
    }

    fun isTop() = !isBot && map.isEmpty()

    fun isBottom() = isBot

    /**
     * Return true iff `X = [bytes.offset, bytes.offset+bytes.width)` overlaps with `Y = [start, start+len)`.
     *
     * If `[onlyPartial] = true` then the case where the interval `X` is included in `Y` is not considered an overlap.
     */
    @TestOnly
    fun overlap(bytes: ByteRange, start: Long, len: Long, onlyPartial: Boolean): Boolean {
        check(len >= 0) {"len argument in overlap cannot be negative"}

        val lbX = bytes.offset
        val ubX = bytes.offset + bytes.width.toLong() - 1
        val lbY = start
        val ubY = start + len - 1

        val hasOverlap =  lbY in lbX..ubX || lbX in lbY..ubY
        val res = if (!onlyPartial) {
            hasOverlap
        } else {
            val xIncludedInY = lbX in lbY..ubY && ubX <= ubY
            hasOverlap && !xIncludedInY
        }
        return res
    }

    /**
     * Return all entries that overlap with the range `[start, start+len)`.
     *
     * See [overlap] to see the meaning of [onlyPartial]
     */
    @Suppress("ForbiddenComment")
    // TODO(PERFORMANCE): calling filter is expensive.
    fun inRange(start: Long, len: Long, onlyPartial: Boolean): Map<ByteRange, Value>  {
        if (isBottom()) {
            throw StackEnvironmentError("cannot call inRange on bottom")
        }
        return map.filter { overlap(it.key, start, len, onlyPartial )}
    }

    fun remove(bytes: ByteRange): StackEnvironment<Value> {
        if (isBottom()) {
            throw StackEnvironmentError("cannot remove on bottom")
        }
        return StackEnvironment(map.remove(bytes))
    }

    fun put(bytes: ByteRange, value: Value, isWeak: Boolean = false): StackEnvironment<Value> {
        if (isBottom()) {
            throw StackEnvironmentError("cannot set on bottom")
        }
        if (value.isBottom()) {
            return makeBottom()
        }

        val newMap = if (value.isTop()) {
            map.remove(bytes)
        } else {
            if (isWeak) {
                val weakVal = map[bytes]?.join(value)
                if (weakVal == null || weakVal.isTop()) {
                    map.remove(bytes)
                } else {
                    map.put(bytes, weakVal)
                }
            } else {
                map.put(bytes, value)
            }
        }
        return StackEnvironment(newMap)
    }

    fun getSingletonOrNull(bytes: ByteRange): Value? {
        if (isBottom()) {
            throw StackEnvironmentError("cannot getSingletonOrNull on bottom")
        }
        return map[bytes]
    }

    override fun iterator() = map.iterator()

    private fun joinOrWiden(other: StackEnvironment<Value>, isJoin: Boolean): StackEnvironment<Value> {
        if (isBottom() || other.isTop()) {
            return other
        } else if (other.isTop() || isTop()) {
            return this
        } else {
            val leftMap = map
            val rightMap = other.map
            val topEntries = mutableSetOf<ByteRange>()
            var outMap = leftMap.merge(rightMap) { (offset,width), leftVal, rightVal ->
                check(!(leftVal == null && rightVal == null)) {"cannot join two null values"}
                if (leftVal == null) {
                    @Suppress("ForbiddenComment")
                    // TODO(PERFORMANCE): adding a top value is wasteful because there is no point to keep it in
                    //  the map. However, the merger function must return a non-null value. It would be more
                    //  efficient to allow adding null or other sentinel value so that no entry is added as a
                    //  result of the merge.
                    topEntries.add(ByteRange(offset, width))
                    rightVal!!.mkTop()
                } else if (rightVal == null) {
                    // See comment above
                    topEntries.add(ByteRange(offset, width))
                    leftVal.mkTop()
                } else {
                    if (isJoin) {
                            leftVal.join(rightVal)
                    } else {
                            leftVal.widen(rightVal)
                    }
                }
            }

            // Finally, remove all top entries added above
            outMap -= topEntries
            return StackEnvironment(outMap)
        }
    }

    fun join(other: StackEnvironment<Value>): StackEnvironment<Value> {
        return joinOrWiden(other, true)
    }

    fun widen(other: StackEnvironment<Value>): StackEnvironment<Value> {
        return joinOrWiden(other, false)
    }

    fun lessOrEqual(other: StackEnvironment<Value>): Boolean {
        if (other.isTop() || isBottom()) {
            return true
        } else if (other.isBottom() || isTop()) {
            return false
        } else {
            val leftMap = map
            val rightMap = other.map
            val entries = leftMap.zip(rightMap)
            for (entry in entries) {
                val leftVal = entry.value.first
                val rightVal = entry.value.second
                check(!(leftVal == null && rightVal == null)) { "cannot compare two null values" }
                if (leftVal == null) {
                    return false
                } else if (rightVal == null) {
                    continue
                } else {
                    if (!(leftVal.lessOrEqual(rightVal))) {
                        return false
                    }
                }
            }
            return true
        }
    }

    override fun toString(): String {
        if (isBottom()) {
            return "bot"
        } else if (isTop()) {
            return "top"
        } else {
            val entries = ArrayList<String>()
            for ((k, absVal) in map) {
                val offset = k.offset
                val width = k.width
                if (!absVal.isTop()) {
                    entries.add("$offset:${width}->$absVal")
                }
            }

            var str = "{"
            entries.forEachIndexed { index, s ->
                str += s
                if (index < entries.size-1) {
                    str += ","
                }
            }
            str += "}"
            return str
        }
    }
}
