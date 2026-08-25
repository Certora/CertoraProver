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

import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Eip8024ImmediateTest {

    private val everyImmediate = (0..0xff).map { it.toUByte() }

    private val everyExchangeOperandPair =
        (2..29).flatMap { m -> (1 until m).filter { n -> n + m <= 30 }.map { n -> ExchangeOperands(n, m) } }

    @Test
    fun singleOperandsRoundTrip() {
        val operands = 17..235
        operands.forEach { operand ->
            assertEquals(operand, Eip8024Immediate.decodeSingle(Eip8024Immediate.encodeSingle(operand)))
        }
        // Every legal immediate must decode to some operand, and no two to the same one, or an
        // operand would be unreachable.
        assertEquals(
            operands.toList(),
            everyImmediate.mapNotNull { Eip8024Immediate.decodeSingle(it) }.sorted()
        )
    }

    @Test
    fun exchangeOperandsRoundTrip() {
        everyExchangeOperandPair.forEach { operands ->
            assertEquals(operands, Eip8024Immediate.decodePair(Eip8024Immediate.encodePair(operands)))
        }
        assertEquals(
            everyExchangeOperandPair.toSet(),
            everyImmediate.mapNotNull { Eip8024Immediate.decodePair(it) }.toSet()
        )
    }

    /**
     * The guarantee the encodings exist to provide: because no legal immediate is a `JUMPDEST` or a
     * push opcode, an immediate can never hide a jump destination, and jumpdest analysis is the same
     * before and after EIP-8024.
     */
    @Test
    fun noLegalImmediateMasksAJumpdestOrPush() {
        val legal = everyImmediate.filter {
            Eip8024Immediate.decodeSingle(it) != null || Eip8024Immediate.decodePair(it) != null
        }
        val masking = legal.filter {
            it == EVMInstruction.JUMPDEST.opcode || it in EVMInstruction.PushBase.opcodes
        }
        assertEquals(listOf<UByte>(), masking, "these immediates would change jumpdest analysis")
    }

    @Test
    fun reservedImmediatesDoNotDecode() {
        // EIP-8024 halts DUPN and SWAPN on 0x5b..0x7f, and EXCHANGE on the wider 0x52..0x7f.
        (0x5b..0x7f).forEach { assertNull(Eip8024Immediate.decodeSingle(it.toUByte())) }
        (0x52..0x7f).forEach { assertNull(Eip8024Immediate.decodePair(it.toUByte())) }
        assertNotNull(Eip8024Immediate.decodeSingle(0x5au.toUByte()))
        assertNotNull(Eip8024Immediate.decodePair(0x51u.toUByte()))
    }
}
