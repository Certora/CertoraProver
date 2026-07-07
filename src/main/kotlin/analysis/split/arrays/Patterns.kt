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

package analysis.split.arrays

import analysis.LTACCmd
import analysis.LTACSymbol
import analysis.PatternMatcher
import analysis.patterns.Info
import analysis.patterns.Info.Companion.set
import analysis.patterns.InfoKey
import analysis.patterns.PatternHelpers
import analysis.patterns.get
import analysis.split.SplitContext
import analysis.split.Ternary.Companion.isPowOf2Minus1
import analysis.split.arrays.PackingInfoKey.*
import analysis.storage.StorageAnalysisResult
import evm.EVM_BITWIDTH256
import evm.EVM_BYTE_SIZE
import java.math.BigInteger

/**
 * Patterns of packed array accesses. There are many cases here because the patterns change if the elements are 8-bits
 * (such as boolean and uint8), if they are signed or unsigned or bytes4 (or any other constant), and if optimization
 * is on.
 *
 * Note that [Info] checks for discrepancies automatically, and so setting the [BITWIDTH] twice in the same matching
 * will automatically turn the result to null if the values don't match.
 *
 * See [PackedArrayRewriter] for an overview of the patterns.
 */
class Patterns(splitContext: SplitContext) {

    val finalLoad: PatternMatcher.Pattern<Info>
    val storedSlot: PatternMatcher.Pattern<Info>

    init {
        fun Info?.addWidth(width: Int) =
            this.set(BITWIDTH, width)
                .set(PER_SLOT, EVM_BITWIDTH256 / width)

        with(PatternHelpers) {
            val mask = c(MASK) { it > BigInteger.ZERO && it.isPowOf2Minus1 }
                .andDo { addWidth(get(MASK)!!.bitLength()) }

            val physicalIndex = (lSym(LOGICAL_INDEX) / c(PER_SLOT))
                .lastCmd(PHYSICAL_INDEX_CMD)

            // the `it + zero` appears in optimized solc. See `Public/TestEVM/StoragePacking/optimized`
            val loc = (physicalIndex + sym()).let { it OR (it + zero) }

            val loadedSlot = read(loc)
                .lastCmd(READ_CMD)
                .andDo {
                    splitContext.storagePaths(get(READ_CMD)!!.cmd)?.let {
                        set(PATHS, it)
                    }
                }

            val numBytes = c(BYTES) { it < 32 }
                .andDo { addWidth(get(BYTES)!! * EVM_BYTE_SIZE.toInt()) }

            val startByteFor8bits = OR(
                lSym(LOGICAL_INDEX2) % c(32),
                lSym(LOGICAL_INDEX2) bwAnd c(31) // --solc_args "['--optimize', '--optimize-runs', '200']"
            ).andDo { set(PER_SLOT, 32) }

            val startByte = OR(
                startByteFor8bits,
                numBytes * OR(
                    lSym(LOGICAL_INDEX2) % c(PER_SLOT),
                    lSym(LOGICAL_INDEX2) bwAnd c(PER_SLOT_MINUS1)
                        .onlyIf { get(PER_SLOT_MINUS1)!!.toBigInteger().isPowOf2Minus1 }
                        .andDo { set(PER_SLOT, get(PER_SLOT_MINUS1)!! + 1) },
                )
            ).lastCmd(FIRST_BYTE_WITHIN_SLOT_CMD)

            val startBit = c(256) exp startByte

            val startBitLoc = startByte * c(8)

            val shifted = OR(
                loadedSlot / startBit,
                loadedSlot shr startBitLoc
            ).lastCmd(DIV_CMD)

            val signExtendVal = c(SIGN_BYTE) { it < 32 }
                .andDo2 { addWidth((get(SIGN_BYTE)!! + 1) * EVM_BYTE_SIZE.toInt()) }

            val bytesShift = c(BYTES_SHIFT)
                .andDo2 { addWidth(EVM_BITWIDTH256 - get(BYTES_SHIFT)!!) }

            val mulShift = c(MUL_SHIFT) { it.bitCount() == 1 }
                .andDo2 { addWidth(EVM_BITWIDTH256 - get(MUL_SHIFT)!!.bitLength() + 1) }

            /** final load pattern - will give the cleaned result ready to use */
            finalLoad = OR(
                mask bwAnd shifted, // unsigned
                signExtendVal signExtend shifted, // signed
                shifted shl bytesShift, // e.g. bytes4[]
                mulShift * shifted  // another bytes4[] one.. solc5.3
            ).lastCmd(FINAL_LOAD_CMD)


            // the store pattern:

            val remainder = (loadedSlot bwAnd bwNot(
                OR(mask * startBit, mask shl startBitLoc),
            )).lastCmd(AND_WITH_READ)

            val maskedVal = c(CONST_VALUE) OR
                OR(
                    mask bwAnd sym(),
                    sym() shr bytesShift, // bytes4, etc.
                    sym() / mulShift, // bytes4 the solc5.3 version.
                    ite(sym(), c(1), c(0)),
                    ite(sym(), c(0), c(1))
                ).lastCmd(VALUE_CMD)

            val unmaskedValue = // in IR, signed values are not masked before the shift, but sign-extended.
                (c(SIGN_EXTEND) signExtend sym())
                    .andDo { addWidth((get(SIGN_EXTEND)!! + 1) * 8) }
                    .lastCmd(VALUE_CMD)

            val shiftedVal = OR(
                maskedVal * startBit,
                ((unmaskedValue OR maskedVal) shl startBitLoc) bwAnd (mask shl startBitLoc), // IR
                ite(sym(), startBit, c(0)).lastCmd(BOOL_VALUE_CMD) // array of bools
            )

            /** final store pattern - this is the combined slot to be stored */
            storedSlot = OR(remainder bwOr shiftedVal, remainder.andDo { set(CONST_VALUE, 0.toBigInteger()) })
                .onlyIf { // if we are writing a constant, check it's not too large
                    val c = get(CONST_VALUE)
                    c == null || c.bitLength() <= get(BITWIDTH)!!
                }
                .lastCmd(LAST_OR)
        }
    }
}


/**
 * Keys for [Info] in [PackedArrayRewriter].
 */
@Suppress("ClassName")
sealed class PackingInfoKey<K> : InfoKey<K>() {
    data object LOGICAL_INDEX : PackingInfoKey<LTACSymbol>()
    data object LOGICAL_INDEX2 : PackingInfoKey<LTACSymbol>()
    data object PER_SLOT : PackingInfoKey<Int>()
    data object PER_SLOT_MINUS1 : PackingInfoKey<Int>()
    data object PATHS : PackingInfoKey<Set<StorageAnalysisResult.NonIndexedPath>>()
    data object BITWIDTH : PackingInfoKey<Int>()
    data object CONST_VALUE: PackingInfoKey<BigInteger>()
    data object BYTES: PackingInfoKey<Int>()
    data object MASK : PackingInfoKey<BigInteger>()
    data object SIGN_BYTE : PackingInfoKey<Int>()
    data object DIV_CMD : PackingInfoKey<LTACCmd>()
    data object AND_WITH_READ : PackingInfoKey<LTACCmd>()
    data object LAST_OR : PackingInfoKey<LTACCmd>()
    data object READ_CMD : PackingInfoKey<LTACCmd>()
    data object PHYSICAL_INDEX_CMD : PackingInfoKey<LTACCmd>()
    data object FINAL_LOAD_CMD : PackingInfoKey<LTACCmd>()
    data object STORE_CMD : PackingInfoKey<LTACCmd>()
    data object VALUE_CMD : PackingInfoKey<LTACCmd>()
    data object BOOL_VALUE_CMD : PackingInfoKey<LTACCmd>()
    data object BYTES_SHIFT : PackingInfoKey<Int>()
    data object MUL_SHIFT : PackingInfoKey<BigInteger>()
    data object FIRST_BYTE_WITHIN_SLOT_CMD : PackingInfoKey<LTACCmd>()
    data object SIGN_EXTEND: InfoKey<Int>()
}

