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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen.fuzzer

import bridge.types.SolidityTypeDescription
import evm.EVM_WORD_SIZE_INT
import prefixgen.data.SimpleValue
import prefixgen.data.VarDef
import utils.*
import java.math.BigInteger
import datastructures.stdcollections.*

/**
 * Holds the calldata from a CEX found by foundry.
 */
@KSerializable
data class RunData(
    val calldata: String
) {
    /**
     * For parsing the raw calldata hex string.
     *
     * Q: Why not parse the nicely formatted string arguments?
     * A: Forge *loves* to generate wild strings with crazy unicode characters, which makes parsing
     * and pretty printing hard (not impossible, just annoying). In addition, I have had *very* little luck
     * convincing forge to NOT include the ANSI color control codes in the JSON. So just parse the calldata ourselves...
     *
     * [buffer] a hexadecimal string representing the raw calldata buffer.
     * [pointer] the *character* offset within said string. The byte offset in the calldata buffer
     * is given by [pointer] / 2.
     */
    private data class CalldataReader(
        val buffer: String,
        val pointer: Int
    ) {
        fun readWord() = buffer.substring(pointer, pointer + (EVM_WORD_SIZE_INT * 2))
        fun readBigInt() = BigInteger(readWord(), 16)
        fun advanceWord() = this.advance(EVM_WORD_SIZE_INT * 2)

        /**
         * Read the integer in the buffer at the current position, interpret it as an offset relative to [rel],
         * and return a new [CalldataReader] at that given position.
         *
         * NB: [rel] is a character offset, not a byte offset.
         */
        fun deref(rel: Int) : CalldataReader {
            val offs = readBigInt()
            if(!offs.isInt()) {
                throw UnsupportedOperationException("Implausibly large offset: $offs @ $pointer")
            }
            return this.copy(pointer = offs.intValueExact() * 2 + rel)
        }

        /**
         * Read [bytes] bytes (aka [bytes] * 2 *characters*) from this position.
         */
        fun readArray(bytes: Int) : String {
            return buffer.substring(pointer, pointer + (bytes * 2))
        }

        /**
         * Advance this reader by [i] *characters*
         */
        fun advance(i: Int) = this.copy(pointer = this.pointer + i)
    }

    companion object {
        /**
         * Parse the raw values for the parameters of the fuzz test out of [calldata].
         * [structFields] are the fields in the `InputState` struct; [otherArgs] are other parameters
         * passed to the function (usually discriminators with fixtures).
         *
         * It is assumed (but not checked) that the identifiers bound between [structFields] and [otherArgs]
         * are unique; the behavior of this function on repeated argument names is undefined.
         *
         * NB that fields that appear in `InputStruct` are mapped by their *field name* in the returned value.
         * In other words, the value of `InputStruct.inputVar5` will appear under the key `inputVar5` in the
         * returned map.
         */
        fun parseBindings(
            calldata: String,
            structFields: List<VarDef>,
            otherArgs: List<VarDef>
        ) : Map<String, SimpleValue> {
            val readerInit = CalldataReader(calldata, 4 * 2 /* skip sighash */)

            val bindings = mutableMapOf<String, SimpleValue>()

            fun readStructFields(readerInit: CalldataReader) : CalldataReader {
                val isDynamic = structFields.any { vd ->
                    vd.ty is SolidityTypeDescription.PackedBytes ||
                        vd.ty is SolidityTypeDescription.StringType
                }
                val reader = if(isDynamic) {
                    readerInit.deref(4 * 2)
                } else {
                    readerInit
                }
                val startOffs = reader.pointer
                var readerIt = reader
                for(fld in structFields) {
                    if(fld.ty is SolidityTypeDescription.StringType || fld.ty is SolidityTypeDescription.PackedBytes) {
                        val bytesReader = readerIt.deref(startOffs)
                        val len = bytesReader.readBigInt()
                        if(!len.isInt()) {
                            throw IllegalStateException("Implausibly large string $len")
                        }
                        val dataReader = bytesReader.advanceWord()
                        val rawStringHex = dataReader.readArray(len.intValueExact())
                        bindings[fld.rawId] = SimpleValue.HexString(len.intValueExact(), rawStringHex)
                    } else {
                        bindings[fld.rawId] = SimpleValue.Word(readerIt.readWord())
                    }
                    readerIt = readerIt.advanceWord()
                }
                return if(!isDynamic) {
                    readerIt
                } else {
                    readerInit.advanceWord()
                }
            }
            var otherParamReader = readStructFields(readerInit)
            for(p in otherArgs) {
                check(p.ty is SolidityTypeDescription.Primitive)
                bindings[p.rawId] = SimpleValue.Word(otherParamReader.readWord())
                otherParamReader = otherParamReader.advanceWord()
            }
            return bindings
        }
    }

    /**
     * Parse the values represented in this [calldata] according to the names and types
     * of [structFields] and [otherArgs]. See [RunData.Companion.parseBindings] for a discussion
     * of how this parsing is done.
     */
    fun parseBindings(
        structFields: List<VarDef>,
        otherArgs: List<VarDef>
    ) : Map<String, SimpleValue> {
        return parseBindings(calldata, structFields, otherArgs)
    }
}
