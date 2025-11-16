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

import sbf.callgraph.SolanaFunction
import sbf.domains.FiniteInterval
import sbf.support.SolanaError
import sbf.support.safeLongToInt
import datastructures.stdcollections.*
import com.certora.collect.*
import net.fornwall.jelf.*
import net.fornwall.jelf.ElfSymbol.STT_FUNC
import java.io.File

class DisassemblerError(msg: String): RuntimeException("Disassembler error: $msg")


/**
 * The reason for defining an interface is to allow mocking these functions without
 * requiring an ELF file during testing.
 */
interface IGlobalsSymbolTable {
    /** SBF is little-endian, but we extract that info from the ELF file in case it will change in the future **/
    fun isLittleEndian(): Boolean
    /** Return true if [address] is in the range of any ELF section known to store global variables **/
    fun isGlobalVariable(address: ElfAddress): Boolean
    /** Interpret [address,..., address+size-1] bytes in the ELF file as a constant string **/
    fun getAsConstantString(name: String, address: ElfAddress, size: Long): SbfConstantStringGlobalVariable
}

class GlobalsSymbolTable(private val reader: ElfFile, private val parser: ElfParser): IGlobalsSymbolTable {
    // Ranges of VMA (Virtual Memory Address) addresses of sections that usually contain global variables.
    // We don't sort it because the size should be very small.
    private val globalVMARanges = mutableListOf<FiniteInterval>()
    init {
        computeVMARangesForGlobalVariables(listOf(".data.rel.ro", ".rodata", ".data"))
    }

    private fun rangeOfSection(sectionName: String): Pair<Long, Long>? {
        val section = reader.firstSectionByName(sectionName) ?: return null
        val sectionStart = (section.header.sh_addr)
        val sectionSize = section.header.sh_size
        return Pair(sectionStart, sectionSize)
    }

    /** Return a list of range of addresses of sections that are known to store global variables **/
    private fun computeVMARangesForGlobalVariables(sections: List<String>) {
        for (section in sections) {
            val range = rangeOfSection(section)
            if (range != null) {
                globalVMARanges.add(FiniteInterval.mkInterval(range.first, range.second))
            }
        }
    }

    override fun isLittleEndian() = reader.ei_data == ElfFile.DATA_LSB

    override fun isGlobalVariable(address: ElfAddress) =
        globalVMARanges.any { range -> range.l <= address && address < range.u }

    override fun getAsConstantString(name: String, address: ElfAddress, size: Long): SbfConstantStringGlobalVariable {
        parser.seek(address)
        var len = size
        val buf = ArrayList<Char>()
        while (len > 0) {
            val byte = parser.readUnsignedByte()
            buf.add(byte.toInt().toChar())
            len--
        }
        return SbfConstantStringGlobalVariable(name, address, size, String(buf.toCharArray()))
    }
}

class ElfDisassembler(pathName: String) {
    private val file: File
    private val reader: ElfFile
    private val parser: ElfParser
    private val globalsSymTable: GlobalsSymbolTable

    init {
        this.file = File(pathName)
        this.file.inputStream().let {
            // from can throw a java.io.IOException
            this.reader = ElfFile.from(it)
            if (reader.symbolTableSection == null) {
                throw SolanaError("The Solana front-end needs symbols to recognize certain function names.\n" +
                    "Please, make sure that symbols are not stripped from the binary")
            }
        }
        this.parser = ElfParser(file, reader)
        this.globalsSymTable = GlobalsSymbolTable(reader, parser)
    }

    /** An undefined symbol is a symbol defined in a different compilation unit (e.g., external calls) **/
    private fun isUndefined(symbol: ElfSymbol): Boolean {
        // The symbol table entry for index 0 is reserved for undefined symbols
        return (symbol.st_shndx == 0.toShort())
    }

    /* These are the relocation sections in an ELF file (https://stevens.netmeister.org/631/elf.html):
     * .rela.dyn: runtime relocation
     * .rela.plt: runtime relocation
     * .rel.text: compile-time relocation
     * .rela.text: compile-time relocation
     * .rel.XXX: compile-time relocation
     * .rela.XXX: compile-time relocation
     */
    private fun getRelocationSections(): List<ElfRelocationSection>{
        val res = ArrayList<ElfRelocationSection>()
        var i = 1
        while (i < reader.e_shnum) {
            val sh: ElfSection = reader.getSection(i)
            if (sh is ElfRelocationSection) {
                res.add(sh)
            }
            i++
        }
        return res
    }

    /* Return the ELF symbol associated to the symbol table index idx */
    private fun getELFSymbol(idx: Int): ElfSymbol? {
        // REVISIT: I think symTable contains all symbols from dynSymTable
        val dynSymTable: ElfSymbolTableSection? = reader.dynamicSymbolTableSection
        if (dynSymTable != null) {
            return dynSymTable.symbols[idx]
        }
        val symTable: ElfSymbolTableSection? = reader.symbolTableSection
        if (symTable != null) {
            return symTable.symbols[idx]
        }
        return null
    }

    private fun getSectionName(sym: ElfSymbol): String? {
        // REVISIT: some symbols have negative numbers (or large unsigned numbers) as e_shnum.
        if (sym.st_shndx >= 0 && sym.st_shndx < reader.e_shnum) {
            val sh: ElfSection? = reader.getSection(sym.st_shndx.toInt())
            if (sh != null ) {
                return sh.header.name
            }
        }
        return null
    }

    /** Return the value of the global variable [sym] only if its size is 1, 2, or 4 **/
    private fun getGlobalVariableValueAsNum(sym: ElfSymbol): Long? {
        return when (sym.st_size) {
            1L -> {
                parser.seek(sym.st_value)
                val bytes = toBytes(parser.readInt())
                if (reader.ei_data == ElfFile.DATA_LSB) {
                    bytes[0].toLong()
                } else {
                    bytes[3].toLong()
                }
            }
            2L -> {
                parser.seek(sym.st_value)
                val bytes = toBytes(parser.readInt())
                if (reader.ei_data == ElfFile.DATA_LSB) {
                    SbfBytecode.makeShort(bytes[1], bytes[0]).toLong()
                } else {
                    SbfBytecode.makeShort(bytes[2], bytes[3]).toLong()
                }
            }
            4L -> {
                parser.seek(sym.st_value)
                val bytes = toBytes(parser.readInt())
                if (reader.ei_data == ElfFile.DATA_LSB) {
                    SbfBytecode.makeInt(bytes[3], bytes[2], bytes[1], bytes[0]).toLong()
                } else {
                    SbfBytecode.makeInt(bytes[0], bytes[1], bytes[2], bytes[3]).toLong()
                }

            }
            else -> {
                null
            }
        }
    }


    @Suppress("ForbiddenMethodCall")
    private fun extractGlobalVariablesFromSymbolTable(): GlobalVariableMap {
        var globals = newGlobalVariableMap()
        // I assume here that dynamic symbol table is included in the symbol table (see comment above)
        val symTable: ElfSymbolTableSection? = reader.symbolTableSection
        if (symTable != null) {
            for (sym in symTable.symbols) {
                val sectionName = getSectionName(sym)
                if (sectionName != null) {
                    // REVISIT: missing any other section?
                    if (sectionName == ".data.rel.ro" || sectionName == ".rodata") {
                        /** Static variables initialized by the programmer
                         * .rodata: constant strings
                         * .data.rel.ro: global variables that e.g., store the address of a function or
                         *               another variable so the final value needs relocation.
                         **/
                        val gvVal = getGlobalVariableValueAsNum(sym)
                        globals = globals.put(sym.st_value, if (gvVal != null) {
                            SbfConstantNumGlobalVariable(sym.name, sym.st_value, sym.st_size, gvVal)
                        } else {
                            SbfGlobalVariable(sym.name, sym.st_value, sym.st_size)
                        })
                    } else  if (sectionName.startsWith(".bss")) {
                        /** Static variables initialized with zeros
                        * .bss section
                        **/
                        globals = globals.put(sym.st_value, SbfGlobalVariable(sym.name, sym.st_value, sym.st_size))
                    } else  if (sectionName.startsWith(".data")) {
                        /** Global variables that can be modified can go to .data section **/
                        globals = globals.put(sym.st_value, SbfGlobalVariable(sym.name, sym.st_value, sym.st_size))
                    }
                }
            }
        }
        return globals
    }

    /**
     * Return true if [sym] is a function.
     * It reads the low byte of [sym]`.st_info` (i.e., [sym]`.st_info & 0x0F`)
     */
    private fun isFunction(sym: ElfSymbol) = sym.getType() == STT_FUNC.toInt()

    private fun getFunctionNames(start: ElfAddress): Map<ElfAddress, String> {
        // I assume here that dynamic symbol table is included in the symbol table (see comment above)
        val nameMap: MutableMap<ElfAddress, String> = mutableMapOf()
        val symTable: ElfSymbolTableSection? = reader.symbolTableSection
        if (symTable != null) {
            for (sym in symTable.symbols) {
                if (isFunction(sym) && sym.name != null) {
                    nameMap[(sym.st_value/8) - start] = sym.name
                }
            }
        }
        return nameMap
    }

    /**
     * Extract all instructions from the .text section and the address where the .text section starts
     **/
    private fun processTextSection(): Pair<ElfAddress, ArrayList<SbfBytecode>> {
        val section = reader.firstSectionByName(".text") ?: throw DisassemblerError("cannot find section .text")
        val isLSB = (reader.ei_data == ElfFile.DATA_LSB)
        parser.seek(section.header.sh_offset)
        val sectionStart = section.header.sh_addr /8
        val instructions = ArrayList<SbfBytecode>()
        var numOfReadBytes = 0
        while (numOfReadBytes  < section.header.sh_size) {
            val instruction =  SbfBytecode.decodeInstruction(parser.readLong(), isLSB, section.header.sh_offset + numOfReadBytes) // read 8 bytes
            instructions.add(instruction)
            numOfReadBytes += 8
        }
        return Pair(sectionStart, instructions)
    }

    private fun isInstRelocateFn(inst: SbfBytecode): Boolean {
        return (inst.opcode.toInt().shr(4).and(0xF) == 0x8)
    }

    /**
     *  Resolve function calls in-place (by modifying the IMM field).
     *
     *  External calls such as Solana helpers can be resolved by looking
     *  at ELF relocations. These calls have the value -1 in the IMM field of the CALL instruction.
     *  For sbf to sbf calls (internal calls) the IMM field contains the offset
     *  of the instruction to jump. The disassembler ignores the latter.
     *  They will be translated by ElfToSbfProgram.
     *
     *  @params sectionStart: initial address of the .text section
     *  @params instructions: list of instructions extracted from the .text section.
     *  Note that this list is mutable.
     *  @params funcMan: the function manager
     *
     *  It returns the indexes in @param instructions that were in-placed modified during call resolution.
     */
    private fun resolveRelocations(sectionStart: ElfAddress, instructions: ArrayList<SbfBytecode>,
                                   funcMan: MutableSbfFunctionManager): Set<Int> {
        val relocatedCalls = mutableSetOf<Int>()
        for (relSection in getRelocationSections()) {
            for (reloc in relSection.relocations) {
                // see https://www.kernel.org/doc/html/latest/bpf/llvm_reloc.html
                // to understand how relocation works.
                // llvm-readelf -r test.o generates
                //       Offset             Info             Type       Symbol's Value  Symbol's Name
                //  0000000000000006  0000000300000003 R_BPF_64_ABS32  0000000000000000 .debug_abbrev
                //  000000000000000c  0000000400000003 R_BPF_64_ABS32  0000000000000000 .debug_str
                // ...
                // The index in the symbol table for the second entry is 4 (00000004)
                if (reader.ei_class != ElfFile.CLASS_32 && reader.ei_class != ElfFile.CLASS_64) {
                    throw DisassemblerError("expected ei_class to be either 32 or 64")
                }
                val symbolIdx = if (reader.ei_class == ElfFile.CLASS_32) {
                    reloc.r_info.shr(8).toInt()
                }  else {
                    reloc.r_info.shr(32).toInt()
                }

                val symbol: ElfSymbol? = getELFSymbol(symbolIdx)
                if (symbol == null || symbol.name == null) {
                    /**
                     * It's possible to have relocations with unknown symbol names. We skip them for now.
                     * Relocation section '.rel.dyn' at offset 0x9068 contains 303 entries:
                     * Offset             Info             Type     Symbol's Value  Symbol's Name
                     * 0000000000000110  0000000000000008 Unknown
                     */
                    //sbfLogger.warn { "Missing reloc offset=${reloc.r_offset} -- ${(reloc.r_offset / 8) - sectionStart} " +
                    //                  "in ${relSection.header.name}" }
                    continue
                }
                val instIdx = safeLongToInt((reloc.r_offset / 8) - sectionStart)
                val inst = instructions[instIdx]
                if (isInstRelocateFn(inst)) {
                    val symbolName = symbol.name
                    val syscallId = SolanaFunction.from(symbolName)?.ordinal
                    if (syscallId != null) {
                        val newInst = inst.copy(imm = syscallId)
                        instructions[instIdx] = newInst
                        relocatedCalls.add(instIdx)
                    } else {
                        val funcEntryPoint: ElfAddress? =
                            if (isUndefined(symbol)) {
                                null
                            } else {
                                (symbol.st_value / 8) - sectionStart
                            }
                        val functionId = funcMan.addFunction(symbolName, funcEntryPoint)
                        instructions[instIdx] = inst.copy(imm = functionId)
                        relocatedCalls.add(instIdx)
                    }
                }
            }
        }
        return relocatedCalls
    }

    // Public API

    /**
     * We check that all symbols [rules] exist and translate only the section ".text"
     * while resolving those calls that can be resolved via relocation.
     **/
    fun read(rules: Set<String>): BytecodeProgram {
        val entryPoints = rules.map { rule ->
            reader.getELFSymbol(rule) ?: throw SolanaError("Cannot find $rule. " +
                "Please make sure that there is function $rule and it has the attribute \"#[rule]\"")
        }

        val (sectionStart, instructions) = processTextSection()
        val functionMan = MutableSbfFunctionManager(sectionStart, getFunctionNames(sectionStart))

        val entryOffsetMap = mutableMapOf<String, ElfAddress>()
        entryPoints.forEach { entryPoint ->
            val entryPointOffset: ElfAddress = entryPoint.st_value/8 - sectionStart
            functionMan.addFunction(entryPoint.name, entryPointOffset)
            entryOffsetMap[entryPoint.name] = entryPointOffset
        }
        val relocatedCalls = resolveRelocations(sectionStart, instructions, functionMan)
        val globals = extractGlobalVariablesFromSymbolTable()
        return BytecodeProgram(entryOffsetMap, functionMan, instructions, globals, relocatedCalls)
    }

    fun getGlobalsSymbolTable() = globalsSymTable
}
