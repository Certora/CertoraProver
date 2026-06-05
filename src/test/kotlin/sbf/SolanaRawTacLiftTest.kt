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

import dwarf.DWARFDebugInformation
import dwarf.DebugSymbols
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.cfg.BinOp
import sbf.cfg.MutableSbfCFG
import sbf.cfg.SbfInstruction
import sbf.cfg.Value
import sbf.disassembler.BytecodeProgram
import sbf.disassembler.ElfAddress
import sbf.disassembler.GlobalVariables
import sbf.disassembler.IElfFileView
import sbf.disassembler.Label
import sbf.disassembler.MutableSbfFunctionManager
import sbf.disassembler.SbfBytecode
import sbf.disassembler.SbfRegister
import sbf.disassembler.SbpfVersion
import sbf.disassembler.SbfProgram
import sbf.disassembler.bytecodeToSbfProgram

class SolanaRawTacLiftTest {
    private class VersionedElfFileView(private val version: SbpfVersion) : IElfFileView {
        override fun sbpfVersion() = version
        override fun useDynamicFrames() = version == SbpfVersion.SBPF_V1 || version == SbpfVersion.SBPF_V2
        override fun isLittleEndian() = true
        override fun isGlobalVariable(address: ElfAddress) = false
        override fun isReadOnlyGlobalVariable(address: ElfAddress) = false
        override fun getAsConstantString(address: ElfAddress, size: Long) = ""
        override fun getAsConstantNum(address: ElfAddress, size: Long): Long? = null
    }

    private data class AssemblyProgram(
        val name: String,
        val version: SbpfVersion,
        val source: String
    )

    private fun liftAssemblyToTac(program: AssemblyProgram) {
        val elf = VersionedElfFileView(program.version)
        val globals = GlobalVariables(elf)
        val bytecode = BytecodeProgram(
            entriesMap = mapOf(program.name to 0L),
            functionMan = MutableSbfFunctionManager(
                start = 0L,
                names = mapOf(0L to program.name)
            ),
            program = assemble(program.source),
            globals = globals,
            relocatedCalls = setOf(),
            debugInformation = DebugSymbols(DWARFDebugInformation())
        )
        val sbfProgram = bytecodeToSbfProgram(bytecode)
        val tac = toTAC(singleBlockCfg(program.name, sbfProgram), globals = globals)
        Assertions.assertTrue(tac.code.isNotEmpty(), "expected non-empty TAC for ${program.version}")
    }

    private fun singleBlockCfg(name: String, program: SbfProgram): MutableSbfCFG {
        val cfg = MutableSbfCFG(name)
        val block = cfg.getOrInsertBlock(Label.Address(0))
        cfg.setEntry(block)
        program.program.forEach { (_, inst) ->
            block.add(inst)
        }
        cfg.normalize()
        return cfg
    }

    @Test
    fun liftSimpleAssemblyForEverySbpfVersionToTac() {
        listOf(
            AssemblyProgram(
                name = "entrypoint",
                version = SbpfVersion.SBF,
                source = """
                    mov64 r0, 7
                    add64 r0, 5
                    exit
                """
            ),
            AssemblyProgram(
                name = "entrypoint",
                version = SbpfVersion.SBPF_V1,
                source = """
                    mov64 r0, 7
                    add64 r0, 5
                    exit
                """
            ),
            AssemblyProgram(
                name = "entrypoint",
                version = SbpfVersion.SBPF_V2,
                source = """
                    mov64 r0, 7
                    lmul64 r0, 6
                    hor64 r0, 1
                    exit
                """
            ),
            AssemblyProgram(
                name = "entrypoint",
                version = SbpfVersion.SBPF_V3,
                source = """
                    mov64 r0, 7
                    add64 r0, 5
                    exit
                """
            ),
            AssemblyProgram(
                name = "entrypoint",
                version = SbpfVersion.SBPF_V4,
                source = """
                    mov64 r0, 7
                    add64 r0, 5
                    exit
                """
            )
        ).forEach(::liftAssemblyToTac)
    }

    @Test
    fun decodeV2MovedMemoryAndPqrAssembly() {
        val elf = VersionedElfFileView(SbpfVersion.SBPF_V2)
        val bytecode = BytecodeProgram(
            entriesMap = mapOf("entrypoint" to 0L),
            functionMan = MutableSbfFunctionManager(
                start = 0L,
                names = mapOf(0L to "entrypoint")
            ),
            program = assemble(
                """
                    mov64 r0, 3
                    st8 [r10-8], r0
                    ld8 r1, [r10-8]
                    lmul64 r1, 4
                    exit
                """
            ),
            globals = GlobalVariables(elf),
            relocatedCalls = setOf(),
            debugInformation = DebugSymbols(DWARFDebugInformation())
        )

        val sbfProgram = bytecodeToSbfProgram(bytecode)
        val instructions = sbfProgram.program.map { it.second }
        Assertions.assertTrue(
            instructions.any { it is SbfInstruction.Mem && !it.isLoad && it.access.base.r == SbfRegister.R10 }
        )
        Assertions.assertTrue(
            instructions.any { it is SbfInstruction.Mem && it.isLoad && it.access.base.r == SbfRegister.R10 }
        )
        Assertions.assertTrue(
            instructions.any { it is SbfInstruction.Bin && it.op == BinOp.MUL && it.dst == Value.Reg(SbfRegister.R1) }
        )
    }

    @Test
    fun decodeV3AndV4Jmp32Assembly() {
        listOf(SbpfVersion.SBPF_V3, SbpfVersion.SBPF_V4).forEach { version ->
            val sbfProgram = decodeAssembly(
                version = version,
                source = """
                    mov64 r0, 7
                    jgt32 r0, 0, 1
                    mov64 r0, 1
                    exit
                """
            )
            Assertions.assertTrue(
                sbfProgram.program.any { (_, inst) -> inst is SbfInstruction.Jump.ConditionalJump },
                "expected JMP32 conditional jump for $version"
            )
        }
    }

    @Test
    fun decodeStrippedAssemblyWithInternalCall() {
        val sbfProgram = decodeAssembly(
            version = SbpfVersion.SBPF_V3,
            source = """
                mov64 r0, 2
                call 1
                exit
                add64 r0, 5
                exit
            """
        )
        Assertions.assertTrue(
            sbfProgram.program.any { (_, inst) -> inst is SbfInstruction.Call && inst.entryPoint == 3L }
        )
    }

    private fun decodeAssembly(version: SbpfVersion, source: String): SbfProgram {
        val elf = VersionedElfFileView(version)
        val bytecode = BytecodeProgram(
            entriesMap = mapOf("entrypoint" to 0L),
            functionMan = MutableSbfFunctionManager(
                start = 0L,
                names = mapOf(0L to "entrypoint")
            ),
            program = assemble(source),
            globals = GlobalVariables(elf),
            relocatedCalls = setOf(),
            debugInformation = DebugSymbols(DWARFDebugInformation())
        )
        return bytecodeToSbfProgram(bytecode)
    }

    private fun assemble(source: String): List<SbfBytecode> =
        source.lineSequence()
            .map { it.substringBefore(";").trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { pc, line -> assembleLine(line = line, pc = pc) }
            .toList()

    private fun assembleLine(line: String, pc: Int): SbfBytecode {
        val words = line.replace(",", " ").trim().split(Regex("\\s+"))
        return when (words[0]) {
            "mov64" -> alu64Imm(op = 0xb0, dst = reg(words[1]), imm = words[2].toInt(), pc = pc)
            "add64" -> alu64Imm(op = 0x00, dst = reg(words[1]), imm = words[2].toInt(), pc = pc)
            "hor64" -> alu64Imm(op = 0xf0, dst = reg(words[1]), imm = words[2].toInt(), pc = pc)
            "lmul64" -> pqr64(op = 0x80, dst = reg(words[1]), imm = words[2].toInt(), pc = pc)
            "jgt32" -> jmp32Imm(op = 0x20, dst = reg(words[1]), imm = words[2].toInt(), offset = words[3].toShort(), pc = pc)
            "call" -> inst(opcode = 0x85, imm = words[1].toInt(), pc = pc)
            "st8" -> movedMem(op = 0x9f, dst = memBase(words[1]), src = reg(words[2]), offset = memOffset(words[1]), pc = pc)
            "ld8" -> movedMem(op = 0x9c, dst = reg(words[1]), src = memBase(words[2]), offset = memOffset(words[2]), pc = pc)
            "exit" -> inst(opcode = 0x95, pc = pc)
            else -> error("unsupported assembly in test fixture: $line")
        }
    }

    private fun alu64Imm(op: Int, dst: Byte, imm: Int, pc: Int): SbfBytecode =
        inst(opcode = op or 0x07, dst = dst, imm = imm, pc = pc)

    private fun pqr64(op: Int, dst: Byte, imm: Int, pc: Int): SbfBytecode =
        inst(opcode = op or 0x10 or 0x06, dst = dst, imm = imm, pc = pc)

    private fun jmp32Imm(op: Int, dst: Byte, imm: Int, offset: Short, pc: Int): SbfBytecode =
        inst(opcode = op or 0x06, dst = dst, offset = offset, imm = imm, pc = pc)

    private fun movedMem(op: Int, dst: Byte, src: Byte, offset: Short, pc: Int): SbfBytecode =
        inst(opcode = op, dst = dst, src = src, offset = offset, pc = pc)

    private fun inst(
        opcode: Int,
        dst: Byte = 0,
        src: Byte = 0,
        offset: Short = 0,
        imm: Int = 0,
        pc: Int
    ): SbfBytecode =
        SbfBytecode(
            opcode = opcode.toByte(),
            src = src,
            dst = dst,
            offset = offset,
            imm = imm,
            isLSB = true,
            address = pc.toLong() * 8L
        )

    private fun reg(token: String): Byte =
        token.removePrefix("r").toByte()

    private fun memBase(token: String): Byte =
        Regex("""\[r(\d+)(?:[+-]\d+)?\]""")
            .matchEntire(token)
            ?.groupValues
            ?.get(1)
            ?.toByte()
            ?: error("bad memory operand: $token")

    private fun memOffset(token: String): Short {
        val groups = Regex("""\[r\d+([+-]\d+)?\]""")
            .matchEntire(token)
            ?.groupValues
            ?: error("bad memory operand: $token")
        return groups.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toShort() ?: 0
    }
}
