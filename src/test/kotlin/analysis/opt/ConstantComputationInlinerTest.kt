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

@file:Suppress("DEPRECATION") // will be cleared in CVL Rewrite

package analysis.opt

import analysis.CommandFinderMixin
import analysis.MockStackVarMixin
import analysis.TACMockUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import testing.ttl.TACMockLanguage
import vc.data.*

class ConstantComputationInlinerTest : MockStackVarMixin, CommandFinderMixin {
    @Test
    fun testHandleByteLongCopy_ByteLoad0() {
        val graph = TACMockLanguage.make {
            L1024 = 0
            L1023 = `*`
            L1022 = 32
            tagNext("pt1")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_ByteLoad0",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt1 = TACMockUtils.commandWithTagOrFail(output,"pt1")
        val cmd0 = output.elab(cmdPt1).cmd
        Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.ByteLoad && cmd0.lhs == TACKeyword.MEM0.toVar())
    }
    @Test
    fun testHandleByteLongCopy_ByteLoad32() {
        val graph = TACMockLanguage.make {
            L1024 = 32
            L1023 = `*`
            L1022 = 32
            tagNext("pt1")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_ByteLoad32",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt1 = TACMockUtils.commandWithTagOrFail(output,"pt1")
        val cmd0 = output.elab(cmdPt1).cmd
        Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.ByteLoad && cmd0.lhs == TACKeyword.MEM32.toVar())
    }
    @Test
    fun testHandleByteLongCopy_ByteLoad64() {
        val graph = TACMockLanguage.make {
            L1024 = 0
            L1023 = `*`
            L1022 = 64
            tagNext("pt0")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_ByteLoad32",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt0 = TACMockUtils.commandWithTagOrFail(output,"pt0")
        val cmd0 = output.elab(cmdPt0).cmd

        val cmdPt1 = output.succ(cmdPt0).single()
        val cmd1 = output.elab(cmdPt1).cmd

        val cmdPt2 = output.succ(cmdPt1).single()
        val cmd2 = output.elab(cmdPt2).cmd

        Assertions.assertAll("testHandleByteLongCopy_ByteLoad64",
            { cmd0 is TACCmd.Simple.AssigningCmd.ByteLoad && cmd0.lhs == TACKeyword.MEM0.toVar() },
            { cmd1 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                cmd2 is TACCmd.Simple.AssigningCmd.ByteLoad &&
                    cmd2.loc is TACSymbol.Var &&
                    cmd1.lhs == cmd2.loc },
            { cmd2 is TACCmd.Simple.AssigningCmd.ByteLoad && cmd2.lhs == TACKeyword.MEM32.toVar() },
        )
    }

    @Test
    fun testHandleByteLongCopy_Assignment() {
        val graph = TACMockLanguage.make {
            L1024 = 0
            L1023 = 32
            L1022 = 32
            tagNext("pt1")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_Assignment",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt1 = TACMockUtils.commandWithTagOrFail(output,"pt1")
        val cmd0 = output.elab(cmdPt1).cmd
        Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                cmd0.lhs == TACKeyword.MEM0.toVar() &&
                cmd0.rhs is TACExpr.Sym &&
                cmd0.rhs.usesVar(TACKeyword.MEM32.toVar()))
    }

    @Test
    fun testHandleByteLongCopy_ByteStore() {
        val graph = TACMockLanguage.make {
            L1024 = 0
            L1023 = 0
            L1022 = `*`
            tagNext("pt1")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_ByteStore",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        // should be converted to a ByteStore from MEM0
        val cmdPt1 = TACMockUtils.commandWithTagOrFail(output,"pt1")
        val cmd0 = output.elab(cmdPt1).cmd
        Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.ByteStore &&
                cmd0.value is TACSymbol.Var &&
                cmd0.value == TACKeyword.MEM0.toVar())
    }

    @Test
    fun testHandleByteLongCopy_Src32() {
        val graph = TACMockLanguage.make {
            L1024 = `*`
            L1023 = 0
            L1022 = `*`
            tagNext("pt0")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_Src32",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt0 = TACMockUtils.commandWithTagOrFail(output,"pt0")
        val cmd0 = output.elab(cmdPt0).cmd

        val cmdPt1 = output.succ(cmdPt0).single()
        val cmd1 = output.elab(cmdPt1).cmd

        val cmdPt2 = output.succ(cmdPt1).single()
        val cmd2 = output.elab(cmdPt2).cmd

        val cmdPt3 = output.succ(cmdPt2).single()
        val cmd3 = output.elab(cmdPt3).cmd

        Assertions.assertAll("testHandleByteLongCopy_Src32",
            { Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.ByteStore &&
                    cmd0.value is TACSymbol.Var &&
                    cmd0.value == TACKeyword.MEM0.toVar()) },
            { cmd1 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    cmd2 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    cmd3 is TACCmd.Simple.ByteLongCopy &&
                    cmd1.lhs == cmd3.dstOffset &&
                    cmd2.lhs == cmd3.length }
        )
    }

    @Test
    fun testHandleByteLongCopy_SrcConst64() {
        val graph = TACMockLanguage.make {
            L1024 = `*`
            L1023 = 0
            L1022 = 128
            tagNext("pt0")
            MemCopy(L(1024),L(1023),L(1022))
        }

        val coreProgram = CoreTACProgram(
            code = graph.code,
            name = "testHandleByteLongCopy_SrcConst64",
            blockgraph = graph.toBlockGraph(),
            procedures = emptySet(),
            symbolTable = TACSymbolTable.withVars(TACUtils.tagsFromBlocks(graph.code)),
            ufAxioms = UfAxioms.empty()
        )

        val xformedCoreProgram = ConstantComputationInliner.rewriteConstantCalculations(coreProgram)
        val output = xformedCoreProgram.analysisCache.graph

        val cmdPt0 = TACMockUtils.commandWithTagOrFail(output,"pt0")
        val cmd0 = output.elab(cmdPt0).cmd

        val cmdPt1 = output.succ(cmdPt0).single()
        val cmd1 = output.elab(cmdPt1).cmd

        val cmdPt2 = output.succ(cmdPt1).single()
        val cmd2 = output.elab(cmdPt2).cmd

        val cmdPt3 = output.succ(cmdPt2).single()
        val cmd3 = output.elab(cmdPt3).cmd

        val cmdPt4 = output.succ(cmdPt3).single()
        val cmd4 = output.elab(cmdPt4).cmd

        Assertions.assertAll("testHandleByteLongCopy_SrcConst64",
            { Assertions.assertTrue(cmd0 is TACCmd.Simple.AssigningCmd.ByteStore &&
                    cmd0.value is TACSymbol.Var &&
                    cmd0.value == TACKeyword.MEM0.toVar()) },
            { cmd1 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    cmd2 is TACCmd.Simple.AssigningCmd.ByteLoad &&
                    cmd2.loc is TACSymbol.Var &&
                    cmd1.lhs == cmd2.loc },
            { Assertions.assertTrue(cmd2 is TACCmd.Simple.AssigningCmd.ByteStore &&
                    cmd2.value is TACSymbol.Var &&
                    cmd2.value == TACKeyword.MEM32.toVar()) },
            { Assertions.assertTrue(cmd3 is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    cmd4 is TACCmd.Simple.ByteLongCopy &&
                    cmd4.dstOffset is TACSymbol.Var &&
                    cmd3.lhs == cmd4.dstOffset) }
        )
    }
}
