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

package sbf.disassembler

import datastructures.stdcollections.*

/**
* See documentation about instruction set here:
* https://www.kernel.org/doc/html/latest/bpf/instruction-set.html
**/

enum class SbfInstructionCodes(val opcode: Int) {
    INST_CLS_MASK(0x07),
    INST_CLS_LD(0x00),
    INST_CLS_LDX(0x01),
    INST_CLS_ST(0x02),
    INST_CLS_STX(0x03),
    INST_CLS_ALU(0x04),
    INST_CLS_JMP(0x05),
    INST_CLS_JMP32(0x06),
    INST_CLS_ALU64(0x07),

    INST_SRC_IMM(0x00),
    INST_SRC_REG(0x08),

    INST_SIZE_W(0x00),
    INST_SIZE_H(0x08),
    INST_SIZE_B(0x10),
    INST_SIZE_DW(0x18),

    INST_SIZE_MASK(0x18),

    INST_MODE_MASK(0xe0),

    INST_ABS(1),
    INST_IND(2),
    INST_MEM(3),
    INST_LEN(4),
    INST_MSH(5),
    INST_XADD(6),
    INST_MEM_UNUSED(7),

    INST_OP_LDDW_IMM(INST_CLS_LD.opcode or
            INST_SRC_IMM.opcode or
            INST_SIZE_DW.opcode), // Special

    INST_OP_JA(INST_CLS_JMP.opcode or 0x00),
    INST_OP_CALL(INST_CLS_JMP.opcode or 0x80),
    INST_OP_EXIT(INST_CLS_JMP.opcode or 0x90),
    INST_ALU_OP_MASK(0xf0)
}

enum class SbfRegister(val value: Byte) {
    R0_RETURN_VALUE(0),
    R1_ARG(1),
    R2_ARG(2),
    R3_ARG(3),
    R4_ARG(4),
    R5_ARG(5),
    R6(6),
    R7(7),
    R8(8),
    R9(9),
    R10_STACK_POINTER(10);

    companion object {
        fun getByValue(value: Byte): SbfRegister {
            val r = SbfRegister.entries.firstOrNull { it.value == value }
            check(r != null) {"$value cannot be converted to SbfRegister"}
            return r
        }

        val funArgRegisters: Set<SbfRegister> = setOf(
            R1_ARG, R2_ARG, R3_ARG, R4_ARG, R5_ARG
        )

        val scratchRegisters: Set<SbfRegister> = setOf(
            R6, R7, R8, R9
        )

        val maxArgument = R5_ARG

    }
}

/** [imm] is low bits and [nextImm] is high bits **/
fun merge(imm: Int, nextImm: Int): ULong {
    // toULong() performs a sign-extension
    val high = nextImm.toULong().shl(32)
    // imm.toULong() and 0xFF_FF_FF_FF is a zero extension
    val low =  imm.toULong().and(4294967295u)
    return high.or(low)
}

/**
 * @param opcode occupies 1 byte
 * @param src occupies 4 bits
 * @param dst occupies 4 bits
 * @param offset occupies 2 bytes
 * @param imm occupies 4 bytes
 * @param isLSB whether little-endian or big-endian
 */
data class SbfBytecode(
    val opcode: Byte,
    val src: Byte,
    val dst: Byte,
    val offset: Short,
    val imm: Int,
    val isLSB: Boolean,
    val address: Long
    ) {

    companion object {
        fun decodeInstruction(x: Long, isLSB: Boolean, address: Long): SbfBytecode {
            val bytes = toBytes(x)
            if (isLSB) {
                // 8 bits (LSB)  4 bits 4 bits  16 bits  32 bits (MSB)
                //   OP            src    dest    OFFSET    IMM
                val opcode = bytes[0]
                val src = (bytes[1].toInt() and 0xf0).shr(4).toByte()
                val dst = (bytes[1].toInt() and 0x0f).toByte()
                val offset = makeShort(bytes[3], bytes[2])
                val imm = makeInt(bytes[7], bytes[6], bytes[5], bytes[4])
                return SbfBytecode(opcode, src, dst, offset, imm, true, address)
            } else {
                // 32 bits (MSB)  16 bits  4 bits  4 bits  8 bits (LSB)
                //   IMM           OFFSET   src     dest       OP
                val opcode = bytes[7]
                val src = (bytes[6].toInt() and 0xf0).shr(4).toByte() // MSB
                val dst = (bytes[6].toInt() and 0x0f).toByte()        // LSB
                val offset = makeShort(bytes[4], bytes[5])
                val imm = makeInt(bytes[0], bytes[1], bytes[2], bytes[3])
                return SbfBytecode(opcode, src, dst, offset, imm, false, address)
            }
        }

        // MSB  LSB
        //  b1  b2
        fun makeByte(b1: /*4 bits*/ Byte, b2: /*4 bits*/Byte): Byte {
            return (zeroExtend(b1).shl(4) or zeroExtend(b2)).toByte()
        }
        // MSB  LSB
        //  b1  b2
        fun makeShort(b1: Byte, b2: Byte): Short {
            return ((zeroExtend(b1) shl 8) or zeroExtend(b2)).toShort()
        }
        // MSB ... LSB
        // b1 b2 b3 b4
        fun makeInt(b1: Byte, b2: Byte, b3: Byte, b4: Byte): Int {
            return (zeroExtend(b1) shl 24) or (zeroExtend(b2) shl 16) or (zeroExtend(b3) shl 8) or zeroExtend(b4)
        }

    }

    // Convenient helper to convert fields
    // opcode, src, dst, offset, and imm to an array of 8 bytes
    ///   LSB           MSB
    /// bytes[0] ... bytes[7]
    private fun toByteArray(): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = opcode
        bytes[1] = makeByte(src, dst)
        bytes[2] = offset.toByte()
        bytes[3] = offset.toInt().shr(8).toByte()
        bytes[4] = imm.toByte()
        bytes[5] = imm.shr(8).toByte()
        bytes[6] = imm.shr(16).toByte()
        bytes[7] = imm.shr(24).toByte()
        if (!isLSB) {
            bytes.reverse()
        }
        return bytes
    }

    override fun toString(): String  {
        return toString(toByteArray())
    }
}
