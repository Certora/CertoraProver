/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package disassembler

import java.io.Serializable

/** The stack positions swapped by an `EXCHANGE`, counting the top of the stack as position 1. */
data class ExchangeOperands(val n: Int, val m: Int) : Serializable {
    init {
        require(n in 1 until m && n + m <= 30) { "No EIP-8024 EXCHANGE swaps positions $n and $m" }
    }
}

/**
 * The one-byte immediates carried by the EIP-8024 instructions.
 *
 * The legal immediates deliberately exclude 0x5b (`JUMPDEST`) and 0x60..0x7f (`PUSH1`..`PUSH32`), so
 * that an immediate can never mask a jump destination or the operand of a push. That is what lets
 * these instructions be introduced without changing jumpdest analysis. The encodings rotate around
 * the excluded bytes rather than leaving a hole in the operand range, so an immediate is emphatically
 * not its operand less some offset: 0x80 is the smallest operand and 0x00 is close to the largest.
 */
object Eip8024Immediate {
    /** The stack positions a `DUPN` or `SWAPN` immediate can reach. */
    val singleOperands = 17..235

    /**
     * `DUPN` and `SWAPN` reach their largest operand, 235, with the immediate 0x5a; everything from
     * there to the end of the push opcodes is reserved.
     */
    private val reservedForSingle = 0x5bu.toUByte()..0x7fu.toUByte()

    /**
     * `EXCHANGE` packs two operands into the same byte and only reaches 0x51 below the excluded
     * bytes, so its reserved range starts nine bytes earlier. Immediates in 0x52..0x5a do decode to
     * a well-formed pair, but not one they are the canonical encoding of, which is why the EIP
     * excludes them too.
     */
    private val reservedForPair = 0x52u.toUByte()..0x7fu.toUByte()

    /** The operand of a `DUPN` or `SWAPN`, or `null` if [imm] is reserved and so cannot be executed. */
    fun decodeSingle(imm: UByte): Int? =
        imm.takeUnless { it in reservedForSingle }?.let { (it.toInt() + 145) % 256 }

    fun encodeSingle(operand: Int): UByte {
        require(operand in singleOperands) { "No EIP-8024 immediate reaches stack position $operand" }
        return ((operand + 111) % 256).toUByte()
    }

    /** The operands of an `EXCHANGE`, or `null` if [imm] is reserved and so cannot be executed. */
    fun decodePair(imm: UByte): ExchangeOperands? =
        imm.takeUnless { it in reservedForPair }?.let {
            val packed = it.toInt() xor 143
            val q = packed / 16
            val r = packed % 16
            if (q < r) {
                ExchangeOperands(q + 1, r + 1)
            } else {
                ExchangeOperands(r + 1, 29 - q)
            }
        }

    fun encodePair(operands: ExchangeOperands): UByte {
        val (n, m) = operands
        val q = if (m <= 16) { n - 1 } else { 29 - m }
        val r = if (m <= 16) { m - 1 } else { n - 1 }
        return ((16 * q + r) xor 143).toUByte()
    }
}
