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

package move

import datastructures.stdcollections.*
import java.math.BigInteger
import java.nio.*

fun ByteBuffer.getUByte() = get().toUByte()
fun ByteBuffer.getLEUShort() = order(ByteOrder.LITTLE_ENDIAN).getShort().toUShort()
fun ByteBuffer.getBEUShort() = order(ByteOrder.BIG_ENDIAN).getShort().toUShort()
fun ByteBuffer.getLEUInt() = order(ByteOrder.LITTLE_ENDIAN).getInt().toUInt()
fun ByteBuffer.getBEUInt() = order(ByteOrder.BIG_ENDIAN).getInt().toUInt()
fun ByteBuffer.getLEULong() = order(ByteOrder.LITTLE_ENDIAN).getLong().toULong()
fun ByteBuffer.getBEULong() = order(ByteOrder.BIG_ENDIAN).getLong().toULong()

fun ByteBuffer.getUnsignedBigInteger(bits: Int, order: ByteOrder): BigInteger {
    val bytes = ByteArray(bits / 8)
    get(bytes)
    if (order == ByteOrder.LITTLE_ENDIAN) {
        bytes.reverse()
    }
    return BigInteger(1, bytes)
}
fun ByteBuffer.getLEUInt128() = getUnsignedBigInteger(128, ByteOrder.LITTLE_ENDIAN)
fun ByteBuffer.getBEUInt128() = getUnsignedBigInteger(256, ByteOrder.BIG_ENDIAN)
fun ByteBuffer.getLEUInt256() = getUnsignedBigInteger(256, ByteOrder.LITTLE_ENDIAN)
fun ByteBuffer.getBEUInt256() = getUnsignedBigInteger(256, ByteOrder.BIG_ENDIAN)

/**
    Parses elements until the end of the buffer
 */
inline fun <T> ByteBuffer.parseAll(
    parseElement: (Int) -> T
) = buildList {
    var index = 0
    while (hasRemaining()) {
        add(parseElement(index++))
    }
}


//
// Methods for decoding the BCS (Binary Canonical Serialization) format used by Move.
// See https://github.com/diem/bcs/#readme
//

/**
    Decodes a [LEB128](https://en.wikipedia.org/wiki/LEB128)-encoded UInt from a ByteBuffer.
 */
fun ByteBuffer.getLeb128UInt(): UInt {
    var result = 0u
    var shift = 0
    while (true) {
        val byte = getUByte().toUInt()
        result = result or ((byte and 0x7Fu) shl shift)
        if ((byte and 0x80u) == 0u) {
            break
        }
        shift += 7
    }
    return result
}

/**
    Decodes a BCS-encoded UTF-8 string
 */
fun ByteBuffer.getUtf8String(): String {
    val length = getLeb128UInt().toInt()
    val bytes = ByteArray(length)
    get(bytes)
    return String(bytes, Charsets.UTF_8)
}

/**
    Parses BCS-encoded list
 */
inline fun <T> ByteBuffer.parseList(
    parseElement: (Int) -> T
) = buildList {
    repeat(getLeb128UInt().toInt()) {
        add(parseElement(it))
    }
}

/**
    Decode BCS-encoded Move primitive types.
 */
fun ByteBuffer.getBool() = get().toInt() != 0
fun ByteBuffer.getU8() = getUByte()
fun ByteBuffer.getU16() = getLEUShort()
fun ByteBuffer.getU32() = getLEUInt()
fun ByteBuffer.getU64() = getLEULong()
fun ByteBuffer.getU128() = getLEUInt128()
fun ByteBuffer.getU256() = getLEUInt256()
fun ByteBuffer.getAddress() = getBEUInt256()
