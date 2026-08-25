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

package decompiler

import bridge.ContractInstanceInSDC
import bridge.ImmutableReference
import bridge.SourceLanguage
import compiler.applyKeccak
import compiler.SrcMapping
import compiler.VariableMapping
import datastructures.stdcollections.*
import disassembler.*
import disassembler.EVMInstruction.*
import mu.KotlinLogging
import utils.*

private val logger = KotlinLogging.logger {}

object Disassembler {

    fun disassembleRuntimeBytecode(instance: ContractInstanceInSDC): DisassembledEVMBytecode {
        return disassemble(
            instance.bytecode,
            instance.srcMappings,
            instance.varMappings,
            instance.immutables,
            instance.lang
        )
    }

    fun disassembleConstructorBytecode(instance: ContractInstanceInSDC): DisassembledEVMBytecode {
        return disassemble(
            instance.constructorBytecode,
            instance.constructorSrcMappings,
            emptyList(),
            emptyList(),
            instance.lang
        )
    }

    private val haltingInstructions = setOf(RETURN, STOP, REVERT, INVALID, SELFDESTRUCT)
    private val simpleInstructionsByOpcode = EVMInstruction.values().associateBy { it.opcode }


    /**
     * There are 3 parts to the bytecode
     * 1. the code itself
     * 2. the data
     * 3. the metadata ("auxdata")
     *
     * We can detect the point where the code ends, but we don't know if it's followed by
     * data (global strings in the bytecode, similar to .rodata in ELF files) or auxdata.
     *
     * Therefore, we merge the data and auxdata sections,
     * and when we may need to query the data section we will use the full version
     * of the bytecode.
     * One such example is hashing of strings that may read beyond the CODESIZE, whihc marks the end of executable
     * bytecode.
     */
    private fun disassemble(
        bytecode: String,
        asm: List<SrcMapping>,
        varMappings: List<VariableMapping?>,
        immutables: List<ImmutableReference>, language: SourceLanguage
    ): DisassembledEVMBytecode {
        val bytes = hexStringToBytes(bytecode)
        val assembly = bytelistToEVMAssembly(bytes, asm, varMappings, immutables)

        // now the "smart" part. Halting opcodes should have an immediate successor of a jumpdest, or nothing, or mark auxdata start
        // the problem is - vyper is not that smart! so we shouldn't do this if we deal with vyper
        fun isHalting(c: EVMCommand): Boolean = c.inst in haltingInstructions

        val auxdataStart = if (language != SourceLanguage.Vyper) {
            assembly.withIndex().find { assemblyCmd ->
                isHalting(assemblyCmd.value)
                        && assemblyCmd.index != assembly.lastIndex
                        && assembly.getOrNull(assemblyCmd.index + 1)?.inst != JUMPDEST
            }?.index
        } else {
            null
        }?.let { it + 1 } ?: assembly.size

        return DisassembledEVMBytecode(
            assembly.associateBy { it.pc },
            bytes,
            applyKeccak(bytes.subList(0, auxdataStart)),
            auxdataStart
        )
    }

    /**
     * Decodes one of the EIP-8024 instructions, which are two bytes wide: the opcode is followed by
     * an immediate encoding the stack position(s) it acts on. A reserved immediate, or an opcode
     * with no room for one, cannot be executed and halts exceptionally.
     *
     * That halt stays one byte wide so the byte after it is still decoded on its own. EIP-8024
     * leaves jumpdest analysis untouched -- it does not skip these immediates -- so a JUMPDEST
     * sitting where a reserved immediate would be remains a legal jump target, and dropping it here
     * would lose a jump target that a node still honours.
     */
    private fun eip8024Instruction(
        bytes: List<UByte>,
        offset: Int,
        decode: (UByte) -> EVMInstructionInfo?
    ): EVMInstructionInfo =
        bytes.getOrNull(offset + 1)?.let(decode) ?: INVALID.also {
            logger.info(
                "Opcode ${bytes[offset].toString(16)} in byte #$offset has no legal EIP-8024 immediate, " +
                    "this is equivalent to an EVM exception"
            )
        }

    private fun bytelistToEVMAssembly(
            bytes: List<UByte>,
            asm: List<SrcMapping>,
            varMappings: List<VariableMapping?>,
            immutables: List<ImmutableReference>
    ): List<EVMCommand> {
        var i = 0
        var counter = 0
        logger.debug { "Bytes $bytes" }
        logger.debug { "with asm (${bytes.size} vs ${asm.size})" }
        val cmds = mutableListOf<EVMCommand>()
        while (i < bytes.size) {
            val cmd = bytes.get(i)
            logger.debug { "working on byte #$i: cmd 0x${cmd.toString(16)}" }
            val op = bytes[i]

            val instruction = simpleInstructionsByOpcode[op] ?: when(op) {
                in DUP.opcodes -> DUP(op)
                in SWAP.opcodes -> SWAP(op)
                in LOG.opcodes -> LOG(op)
                DUPN.opcode -> eip8024Instruction(bytes, i) { DUPN(it) }
                SWAPN.opcode -> eip8024Instruction(bytes, i) { SWAPN(it) }
                EXCHANGE.opcode -> eip8024Instruction(bytes, i) { EXCHANGE(it) }
                PushContractAddress.opcode -> PushContractAddress(bytes, i)
                in PushBase.opcodes -> when (val immutable = immutables.singleOrNull { it.offset == i + 1 }) {
                    null -> PUSH(op, bytes, i)
                    else -> PushImmutable(op, immutable.value, immutable.varname)
                }
                else -> REVERT.also {
                    logger.info("Bad opcode number ${op.toString(16)} in byte #$i, this is equivalent to EVM exception, or REVERT")
                }
            }

            val cmdAsm = asm.getOrNull(counter) ?: SrcMapping(-1, 0, 0, null)
            val varMapping = varMappings.getOrNull(counter)
            val meta = EVMMetaInfo(cmdAsm.source, cmdAsm.begin, cmdAsm.begin + cmdAsm.len, varMapping, op, cmdAsm.jumpType)

            cmds.add(EVMCommand(i, instruction, meta))
            i += instruction.bytecodeSize
            counter += 1
        }

        logger.debug { "evm assembly is $cmds" }
        return cmds
    }
}
