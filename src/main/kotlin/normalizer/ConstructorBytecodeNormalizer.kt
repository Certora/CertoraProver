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

package normalizer

import analysis.ip.functionIdMask
import analysis.ip.isInternalAnnotationConstant
import utils.*
import java.math.BigInteger
import kotlin.math.pow

/**
 * Interface for code that manipulates bytecode "strings", as in, literal bytecode arrays.
 *
 * Q: Why not manipulate literal arrays?
 * A: Woof. It is really annoying to extract sub arrays and then compare them for equality
 *
 * Q: Why not manipulate bigintegers?
 * A: Also woof.
 */
interface ConstructorBytecodeNormalizer {
    /**
     * Assuming that this string represents some bytecode, get the byte that occurs [n] bytes from the end (i.e.,
     * the last byte is returned if this function is called with 0)
     */
    fun String.nthByteFromEnd(n: Int) : Int {
        // in hex encoding, 2 characters per byte
        val byteIndex = this.lastIndex - (n * 2 + 1)
        return Integer.valueOf(this.substring(byteIndex, byteIndex + 2), 16)
    }

    /**
     * Assuming this string represents some bytecode, sanitize (i.e., remove) all constants that come from autofinder instrumentation.
     * Note that we still expect to see the autofinders, and we don't *remove* them, but sanitize out the lower 16 bits
     * as these contain unique ids that are not resilient w.r.t. multi-contract compilation.
     */
    fun String.sanitizeFinders(): String {
        require(length % 2 == 0) {
            "$this is not a byte-aligned hex string?"
        }
        val output = StringBuilder()
        val numBytes = length / 2
        var i = 0
        while (i < numBytes) {
            val byte = nthByte(i)
            if (byte == 0x64 && i + 5 < numBytes) { // push 5 byte word
                val next = BigInteger(substring((i + 1) * 2, (i + 6) * 2), 16)
                if (next and functionIdMask == functionIdMask) {
                    /* function identifiers are also not consistent across compilation units */
                    output.append("640000000000")
                    i += 6
                    continue
                }
            } else if (byte == 0x7f && i + 32 < numBytes) { // push full 32 byte word
                val next = BigInteger(substring((i + 1) * 2, (i + 33) * 2), 16)
                if (next.isInternalAnnotationConstant()) { // should do so also for source finders?
                    output.append("7f").append("00".repeat(32))
                    i += 33
                    continue
                }
            }
            output.append(nthByteString(i++))
        }

        return output.toString()
    }

    /**
     * Get the nth byte in this string, i.e., the first byte (i.e. first two hex characters)
     * are given for argument 0
     */
    private fun String.nthByte(n: Int): Int {
        return Integer.valueOf(this.nthByteString(n), 16)
    }

    private fun String.nthByteString(n: Int): String {
        return this.substring(n * 2, (n + 1) * 2)
    }

    /**
     * Translate a position in this bytecode expressed as an index counting from
     * the end of the string to a position counting from the beginning.
     */
    private fun String.toForwardInd(x: Int): Int {
        val l = this.length / 2
        /*
           index-from-end 0 has forward-index this.length - 1. Therefore, index-from-end i has
           forward-index this.length - 1 - i
         */
        return (l - x - 1) * 2
    }

    /**
     * Assuming this String contains bytecode in which CBOR encoded metadata, strip out of that metadata.
     *
     * Implements a (subset) of the cbor parser, which simply ensures we see the CBOR data we expect to see, and consumes
     * those bytes.
     */
    fun String.stripCbor(): String {
        require(length % 2 == 0) {
            "$this is not a byte aligned string"
        }
        val byteCount = length / 2
        for (i in 0 until byteCount - 1) {
            /**
             * Solidity helpfully puts the length of the CBOR segment as a big-endian encoded 2-byte word immediately
             * after the CBOR segment. So, scan backwards, finding plausible looking size 2-word bytes that "point to"
             * a location in the bytecode which, when parsed as CBOR, ends up right back at this location.
             *
             * If we find such a 2-byte word, we either got fantastically lucky, or, we found a word that describes
             * the length of CBOR encoded metadata.
             */
            val len = (nthByteFromEnd(i + 1) shl 8) or nthByteFromEnd(i)

            /**
             * Seems like a weird thing to do since we are looking backward, but we scan from the "end", so to go back
             * in the string, we *increase* the length.
             */
            val cborStart = len + 1 + i
            /**
             * Greater than the bytecode means a negative index
             */
            if (cborStart >= byteCount) {
                continue
            }
            /**
             * [consumeCbor] returns the position where parsing stopped, and if this the upper byte of our size word,
             * then we have a valid cbor encoded segment that matches our declared size.
             */
            if (consumeCbor(this, cborStart) == i + 1) {
                return substring(0, toForwardInd(cborStart)) + "00".repeat((len + 2)) + substring(toForwardInd(i - 1))
            }
        }
        return this
    }

    /**
     * [List]<[UByte]> variant of [stripCbor]: reuses the (tested) hex-string CBOR parser by converting to a
     * byte-aligned hex string, stripping, and converting back. [String.stripCbor] zeroes the CBOR bytes in
     * place (preserving length), so the result has the same length as the input.
     */
    fun List<UByte>.stripCbor(): List<UByte> {
        if (this.isEmpty()) {
            return this
        }
        // toByteArray().toHexString() emits exactly 2 hex chars per byte (%02X), keeping the string
        // byte-aligned. Do NOT use UByte.toString(16): it drops the leading zero for bytes < 0x10.
        return hexStringToBytes(this.toByteArray().toHexString().stripCbor())
    }

    /**
     * A much simplified parser for the CBOR format described here:
     * https://datatracker.ietf.org/doc/html/rfc7049
     */
    private fun consumeCbor(payload: String, cborStart: Int) : Int? {
        val byte = payload.nthByteFromEnd(cborStart)
        when (byte.shr(5)) {
            5 -> {
                // a map
                val (next, sz) = getLength(payload, cborStart) ?: return null
                var it = next
                /*
                   Two values for each entry in the map, one for the key, one for the value
                 */
                for (i in 0 until sz * 2) {
                    it = consumeCbor(payload, it) ?: return null
                }
                return it
            }
            2, 3 -> {
                // bytes (or strings)
                val (next, sz) = getLength(payload, cborStart) ?: return null
                return (next - sz).takeIf { it > 0 }
            }
            else -> return null
        }
    }

    /**
     * The lower 5 bits of strings and bytes encode the length of the item to come next.
     * Given a position [where] that refers to a bytes/string, get the length, and return
     * the position where the data is expected to lie
     */
    private fun getLength(payload: String, where: Int): Pair<Int, Int>? {
        /**
         * If less than 23, then that is exactly our length, and the next byte (i.e., closer to the end, aka one
         * smaller than where) is where the string data lies
         */
        val byte = payload.nthByteFromEnd(where) and 0b11111
        if (byte <= 23) {
            return where - 1 to byte
        } else if (byte in 24..27) {
            /**
             * Otherwise, look for 2^(byte-24) bytes (aka, 1, 2, 4, or 8 for 24, 25, 26, or 27 resp)
             */
            var ind = where - 1
            var len = 0
            val numBytes = 2.toDouble().pow((byte - 24).toDouble()).toInt()
            if (numBytes > 4) { // nope too big to fit in an int, and I refuse to use a bigint
                return null
            }
            for (i in 0 until numBytes) {
                val read = payload.nthByteFromEnd(ind--)
                len = len.shl(8) or read
            }
            if (len < 0) {
                return null
            }
            return ind to len
        } else {
            return null
        }
    }
}
