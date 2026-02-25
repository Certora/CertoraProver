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

package disassembler

import com.certora.collect.*
import datastructures.stdcollections.*
import evm.*
import utils.*
import java.io.Serializable
import java.math.BigInteger


sealed interface EVMInstructionInfo : Serializable {
    val opcode: UByte
    val popCount: Int
    val pushCount: Int
    val bytecodeSize: Int
    /** Does this instruction write to the local context's memory? */
    val affectsMemory: Boolean get() = false
    /** Does this instruction write to storage? */
    val affectsStorage: Boolean get() = false
    /** Does this instruction affect the blockchain state? */
    val affectsChain: Boolean get() = affectsStorage
}


enum class EVMInstruction(
    override val opcode: UByte,
    override val popCount: Int,
    override val pushCount: Int,
    override val affectsMemory: Boolean = false,
    override val affectsStorage: Boolean = false,
    override val affectsChain: Boolean = affectsStorage
) : EVMInstructionInfo {
    STOP(0x00u, 0, 0),
    ADD(0x01u, 2, 1),
    MUL(0x02u, 2, 1),
    SUB(0x03u, 2, 1),
    DIV(0x04u, 2, 1),
    SDIV(0x05u, 2, 1),
    MOD(0x06u, 2, 1),
    SMOD(0x07u, 2, 1),
    ADDMOD(0x08u, 3, 1),
    MULMOD(0x09u, 3, 1),
    EXP(0x0au, 2, 1),
    SIGNEXTEND(0x0bu, 2, 1),
    LT(0x10u, 2, 1),
    GT(0x11u, 2, 1),
    SLT(0x12u, 2, 1),
    SGT(0x13u, 2, 1),
    EQ(0x14u, 2, 1),
    ISZERO(0x15u, 1, 1),
    AND(0x16u, 2, 1),
    OR(0x17u, 2, 1),
    XOR(0x18u, 2, 1),
    NOT(0x19u, 1, 1),
    BYTE(0x1au, 2, 1),
    SHL(0x1bu, 2, 1),
    SHR(0x1cu, 2, 1),
    SAR(0x1du, 2, 1),
    CLZ(0x1eu, 1, 1),
    KECCAK256(0x20u, 2, 1),
    ADDRESS(0x30u, 0, 1),
    BALANCE(0x31u, 1, 1),
    ORIGIN(0x32u, 0, 1),
    CALLER(0x33u, 0, 1),
    CALLVALUE(0x34u, 0, 1),
    CALLDATALOAD(0x35u, 1, 1),
    CALLDATASIZE(0x36u, 0, 1),
    CALLDATACOPY(0x37u, 3, 0, affectsMemory = true),
    CODESIZE(0x38u, 0, 1),
    CODECOPY(0x39u, 3, 0, affectsMemory = true),
    GASPRICE(0x3au, 0, 1),
    EXTCODESIZE(0x3bu, 1, 1),
    EXTCODECOPY(0x3cu, 4, 0, affectsMemory = true),
    RETURNDATASIZE(0x3du, 0, 1),
    RETURNDATACOPY(0x3eu, 3, 0, affectsMemory = true),
    EXTCODEHASH(0x3fu, 1, 1),
    BLOCKHASH(0x40u, 1, 1),
    COINBASE(0x41u, 0, 1),
    TIMESTAMP(0x42u, 0, 1),
    NUMBER(0x43u, 0, 1),
    DIFFICULTY(0x44u, 0, 1),
    GASLIMIT(0x45u, 0, 1),
    CHAINID(0x46u, 0, 1),
    SELFBALANCE(0x47u, 0, 1),
    BASEFEE(0x48u, 0, 1),
    BLOBHASH(0x49u, 1, 1),
    BLOBBASEFEE(0x4au, 0, 1),
    POP(0x50u, 1, 0),
    MLOAD(0x51u, 1, 1),
    MSTORE(0x52u, 2, 0, affectsMemory = true),
    MSTORE8(0x53u, 2, 0, affectsMemory = true),
    SLOAD(0x54u, 1, 1),
    SSTORE(0x55u, 2, 0, affectsStorage = true),
    JUMP(0x56u, 1, 0),
    JUMPI(0x57u, 2, 0),
    PC(0x58u, 0, 1),
    MSIZE(0x59u, 0, 1),
    GAS(0x5au, 0, 1),
    JUMPDEST(0x5bu, 0, 0),
    TLOAD(0x5cu, 1, 1),
    TSTORE(0x5du, 2, 0, affectsStorage = true), // not really _the_ storage,
    MCOPY(0x5eu, 3, 0, affectsMemory = true),
    CREATE(0xf0u, 3, 1, affectsChain = true),
    CALL(0xf1u, 7, 1, affectsChain = true),
    CALLCODE(0xf2u, 7, 1, affectsChain = true),
    RETURN(0xf3u, 2, 0),
    DELEGATECALL(0xf4u, 6, 1, affectsChain = true),
    CREATE2(0xf5u, 4, 1, affectsChain = true),
    STATICCALL(0xfau, 6, 1, affectsChain = true),
    REVERT(0xfdu, 2, 0),
    INVALID(0xfeu, -1, -1),
    SELFDESTRUCT(0xffu, 1, 0, affectsChain = true),
    ;
    override val bytecodeSize get() = 1

    @Treapable
    sealed class PushBase(opcode: UByte): EVMInstructionInfo {
        companion object {
            val opcodes = 0x5fu.toUByte()..0x7fu.toUByte()
            fun pushSize(opcode: UByte) = (opcode - opcodes.start).toInt()
        }
        init {
            check(opcode in opcodes) { "Invalid PUSH opcode $opcode " }
        }

        abstract val value: BigInteger?

        override val popCount get() = 0
        override val pushCount get() = 1
        override val bytecodeSize get() = 1 + pushSize(opcode)
    }

    data class PUSH(override val opcode: UByte, override val value: BigInteger): PushBase(opcode) {
        companion object {
            operator fun invoke(opcode: UByte, bytes: List<UByte>, startOffset: Int) =
                PUSH(opcode, bytes.toPositiveBigIntegerTruncating(startOffset + 1, pushSize(opcode)))

            fun forValue(value: BigInteger): PUSH {
                require(value in BigInteger.ZERO..MAX_EVM_UINT256)
                return PUSH(opcodes.endInclusive.toUByte(), value)
            }
        }
        override fun toString() = "PUSH($value)"
    }

    data class PushImmutable(override val opcode: UByte, override val value: BigInteger?, val name: String): PushBase(opcode) {
        override fun toString() = "PushImmutable($value,$name)"
    }

    data class PushContractAddress(val contractId: BigInteger): EVMInstructionInfo {
        companion object {
            val opcode = 0xe0u.toUByte()
            const val addressSize = 20
            operator fun invoke(bytes: List<UByte>, startOffset: Int) =
                PushContractAddress(bytes.toPositiveBigIntegerTruncating(startOffset + 1, addressSize))
        }
        override val opcode get() = Companion.opcode
        override val popCount get() = 0
        override val pushCount get() = 1
        override val bytecodeSize get() = addressSize + 1
    }

    data class DUP(override val opcode: UByte): EVMInstructionInfo {
        companion object {
            val opcodes = 0x80u.toUByte()..0x8fu.toUByte()
            fun forNum(dupNum: Int) = DUP((opcodes.start.toInt() + dupNum - 1).toUByte())
        }
        init {
            check(opcode in opcodes) { "Invalid DUP opcode $opcode " }
        }

        val dupNum get() = (opcode - opcodes.start).toInt() + 1
        override fun toString() = "DUP$dupNum"

        override val popCount get() = dupNum
        override val pushCount get() = dupNum + 1
        override val bytecodeSize get() = 1
    }

    data class SWAP(override val opcode: UByte): EVMInstructionInfo {
        companion object {
            val opcodes = 0x90u.toUByte()..0x9fu.toUByte()
        }
        init {
            check(opcode in opcodes) { "Invalid SWAP opcode $opcode " }
        }

        val swapNum get() = (opcode - opcodes.start).toInt() + 1
        override fun toString() = "SWAP$swapNum"

        override val popCount get() = swapNum + 1
        override val pushCount get() = swapNum + 1
        override val bytecodeSize get() = 1
    }

    data class LOG(override val opcode: UByte): EVMInstructionInfo {
        companion object {
            val opcodes = 0xa0u.toUByte()..0xa4u.toUByte()
        }
        init {
            check(opcode in opcodes) { "Invalid LOG opcode $opcode " }
        }

        val logNum get() = (opcode - opcodes.start).toInt()
        override fun toString() = "LOG$logNum"

        override val popCount get() = logNum + 2
        override val pushCount get() = 0
        override val bytecodeSize get() = 1
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Note: any additional instruction classes added here must also be handled in Disassembler.bytelistToEVMAssembly.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
}

