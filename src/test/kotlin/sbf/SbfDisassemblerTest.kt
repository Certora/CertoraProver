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

package sbf

import sbf.cfg.*
import sbf.disassembler.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SbfDisassemblerTest {

    private fun convert(bytes: List<Byte>, isLSB: Boolean = true): Long {
        check(bytes.size == 8)
        val value = if (isLSB) {
            bytes.reversed() // convert to little-endian
        } else {
            bytes
        }.fold(0L) { acc, b ->
                (acc shl 8) or (b.toLong() and 0xFF)
        }
        return value
    }

    @Test
    fun test01() {
        // 0f 21 00 00 00 00 00 00
        // r1 += r2

        val bytes = listOf(
            0x0f.toByte(),
            0x21.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )

        val inst1 = makeAluInst(SbfBytecode.decodeInstruction(convert(bytes, true), true, 0))
        val inst2 = makeAluInst(SbfBytecode.decodeInstruction(convert(bytes, false), false, 0))
        println("$inst1 and $inst2")
        Assertions.assertEquals(true, inst1.toString() == inst2.toString())
    }

    @Test
    fun test02() {
        // b7 02 00 00 f9 ff 03 00
        // r2 = 262137

        val bytes = listOf(
            0xb7.toByte(),
            0x02.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0xf9.toByte(),
            0xff.toByte(),
            0x03.toByte(),
            0x00.toByte()
        )

        val inst1 = makeAluInst(SbfBytecode.decodeInstruction(convert(bytes, true) , true, 0))
        val inst2 = makeAluInst(SbfBytecode.decodeInstruction(convert(bytes, false), false, 0))
        println("$inst1 and $inst2")
        Assertions.assertEquals(true, inst1.toString() == inst2.toString())
        Assertions.assertEquals(true,
        inst1 is SbfInstruction.Bin &&
            inst1.dst.r == SbfRegister.R2_ARG &&
            (inst1.v is Value.Imm &&  (inst1.v as Value.Imm).v ==  262137UL))
    }

    @Test
    fun test03() {
        // 18 02 00 00 f8 ff ff ff    00 00 00 00 fc ff ff ff
        // r2 = -12884901896 ll
        val x = convert(listOf(
            0x18.toByte(),
            0x02.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0xf8.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte())
        )
        val y = convert(listOf(
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0xfc.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte())
        )
        val bc1 = SbfBytecode.decodeInstruction(x, true, 0)
        val bc2 = SbfBytecode.decodeInstruction(y, true, 0)
        val inst = makeLddw(bc1, listOf(bc1,bc2), 0)
        println("$inst")
        Assertions.assertEquals(true,
            inst is SbfInstruction.Bin &&
                inst.dst.r == SbfRegister.R2_ARG &&
                (inst.v is Value.Imm &&  (inst.v as Value.Imm).v == (-12884901896).toULong()))
    }

    @Test
    fun test04() {
        // 7a 09 10 00 ff ff ff ff
        // *(u64 *) (r9 + 16) := -0x1
        val x = convert(listOf(
            0x7a.toByte(),
            0x09.toByte(),
            0x10.toByte(),
            0x00.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte(),
            0xff.toByte())
        )

        val bc = SbfBytecode.decodeInstruction(x, true, 0)

        val inst = makeMemInst(bc)
        println("$inst")

        Assertions.assertEquals(true,
            inst is SbfInstruction.Mem &&
                   !inst.isLoad &&
                   inst.access.base.r == SbfRegister.R9 &&
                   inst.value == Value.Imm((-1).toULong()))
    }

    @Test
    fun test05() {
        //  7b 1a b8 ff 00 00 00 00
        // *(u64 *) (r10 - 48) := r1
        val x = convert(listOf(
            0x7b.toByte(),
            0x1a.toByte(),
            0xb8.toByte(),
            0xff.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte())
        )

        val bc = SbfBytecode.decodeInstruction(x, true, 0)

        val inst = makeMemInst(bc)
        println("$inst")

        Assertions.assertEquals(true,
            inst is SbfInstruction.Mem &&
                !inst.isLoad &&
                inst.access.base.r == SbfRegister.R10_STACK_POINTER &&
                inst.access.offset.toInt() == -72 &&
                inst.value == Value.Reg(SbfRegister.R1_ARG))
    }
}
