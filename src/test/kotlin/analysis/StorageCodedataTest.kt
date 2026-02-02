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

package analysis

import analysis.storage.StorageCodedataSlotAnnotator
import disassembler.DisassembledEVMBytecode
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.Size
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.Tag
import testing.ttl.TACMockLanguage
import utils.toByteArray
import vc.data.*
import java.math.BigInteger

class StorageCodedataTest {
    @Test
    fun testBasicCodedataAnnot() {
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1020 = codedata[0]
            L1021 = storage[L1020]
        }

        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = byteCode(len = 100)
            ),
            cfg.toTestProgram(1020, 1021)
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertEquals(wordAt(0), v)
    }

    @Property(tries = 100)
    fun testCodedataAnnotPositive(@ForAll @Size(32) input: List<Byte>) {
        val bytes = input.map { it.toUByte() }.toMutableList()
        // Ensure the signed interpretation is negative
        bytes[31] = bytes[31].and(0x80U)
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1020 = codedata[0]
            L1021 = storage[L1020]
        }
        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = bytes
            ),
            cfg.toTestProgram(1020, 1021)
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertTrue(v?.let { BigInteger.ZERO <= it } == true)
    }

    @Test
    fun testBasicCodedataAnnotMultipleUses() {
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1020 = codedata[0]
            L1021 = storage[L1020]
            L1022 = storage[L1020]
        }

        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = byteCode(100)
            ),
            cfg.toTestProgram(1020, 1021, 1022)
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertEquals(wordAt(0), v)
    }

    @Test
    fun testInvalidUse() {
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1020 = codedata[0]
            L1021 = storage[L1020]
            L1022 = storage[L1020]
            L1023 = "L1020 + L1021"
        }

        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = byteCode(100)
            ),
            cfg.toTestProgram(1020, 1021, 1022, 1023)
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertEquals(null, v)
    }

    @Test
    fun testAssignments() {
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1020 = codedata[0]
            L1021 = L1020
            L1022 = storage[L1021]
        }

        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = byteCode(100)
            ),
            cfg.toTestProgram(1020, 1021, 1022, 1023)
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertEquals(wordAt(0), v)
    }

    @Test
    fun testIte() {
        val cfg = TACMockLanguage.make {
            tagNext("codedata")
            L1021 = codedata[0]
            L1022 = codedata[0]
            L1023 = "Ite(L1020:bool L1021 L1022)"
            L1024 = storage[L1023]
        }

        val out = StorageCodedataSlotAnnotator.annotateCodedataReads(
            bytecode = DisassembledEVMBytecode.empty.copy(
                bytes = byteCode(100)
            ),
            cfg.toTestProgram(
                setOf(
                    TACSymbol.Var.stackVar(1020).updateTag(Tag.Bool, "", ""),
                    TACSymbol.Var.stackVar(1021),
                    TACSymbol.Var.stackVar(1022),
                    TACSymbol.Var.stackVar(1023),
                    TACSymbol.Var.stackVar(1024),
                )
            )
        )

        val codedata = TACMockUtils.commandWithTagOrFail(out.analysisCache.graph, "codedata")
        val v = out.analysisCache.graph.elab(codedata).cmd.meta[StorageCodedataSlotAnnotator.KEY]
        Assertions.assertEquals(wordAt(0), v)
    }

    private fun byteCode(len: Int): List<UByte> {
        return (0..<len).map { it.toUByte() }
    }
    private fun wordAt(off: Int): BigInteger {
        return BigInteger((off..<off+32).map { it.toUByte() }.toByteArray())
    }

    private fun TACCommandGraph.toTestProgram(vararg slots: Int): CoreTACProgram {
        return toTestProgram(slots.map { TACSymbol.Var.stackVar(it) }.toSet())
    }

    private fun TACCommandGraph.toTestProgram(
        stackVars: Set<TACSymbol.Var>
    ): CoreTACProgram =
        CoreTACProgram(
            code = this.code,
            blockgraph = this.blockGraph,
            name = "test",
            symbolTable = TACSymbolTable.withVars(
                stackVars +
                    setOf(
                        TACKeyword.CODEDATA.toVar(),
                        TACKeyword.MEMORY.toVar(),
                        TACKeyword.STORAGE.toVar(),
                    )
            ),
            ufAxioms = UfAxioms.empty(),
            procedures = setOf(),
            check = true,
        )

}
