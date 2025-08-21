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

import datastructures.stdcollections.*
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.callgraph.CVTFunction
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.SbfCFG
import sbf.tac.sbfCFGsToTAC
import kotlinx.coroutines.runBlocking
import report.DummyLiveStatsReporter
import sbf.callgraph.SolanaFunction
import sbf.disassembler.*
import sbf.domains.*
import scene.SceneFactory
import scene.source.DegenerateContractSource
import vc.data.CoreTACProgram
import verifier.TACVerifier
import verifier.Verifier

fun dumpTAC(program: CoreTACProgram): String {
    val sb = StringBuilder()
    program.code.forEachEntry { (id, commands) ->
        sb.append("Block $id:\n")
        commands.forEach { command ->
            sb.append("\t${command}\n")
        }
    }
    sb.append("Graph\n")
    program.blockgraph.forEachEntry { (u, vs) ->
        sb.append("$u -> ${vs.joinToString(" ")}\n")
    }
    return sb.toString()
}

object EmptyGlobalsSymbolTable: IGlobalsSymbolTable {
    override fun isLittleEndian() = true
    override fun isGlobalVariable(address: ElfAddress) = false
    override fun getAsConstantString(
        name: String,
        address: ElfAddress,
        size: Long
    ) = SbfConstantStringGlobalVariable("",0,0, "")
}

fun toTAC (cfg: SbfCFG,
           summaryFileContents: List<String> = listOf(
               "#[type((*i64)(r1+0):num)]", "#[type((*i64)(r1+8):num)]", "^__multi3$",
               "#[type((*i64)(r1+0):num)]", "#[type((*i64)(r1+8):num)]", "^__udivti3$",
               "#[type((*i64)(r1+0):num)]", "#[type((*i64)(r1+8):num)]", "^__divti3$"),
           globals: GlobalVariableMap = newGlobalVariableMap(),
           globalsSymbolTable: IGlobalsSymbolTable = EmptyGlobalsSymbolTable
): CoreTACProgram {
    val prog = MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), globals)
    val memSummaries = MemorySummaries.readSpecFile(summaryFileContents,"unknown")
    CVTFunction.addSummaries(memSummaries)
    SolanaFunction.addSummaries(memSummaries)
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val memAnalysis = WholeProgramMemoryAnalysis(prog, memSummaries, sbfTypesFac, processor = null)
    memAnalysis.inferAll()
    return sbfCFGsToTAC(prog, memSummaries, globalsSymbolTable, memAnalysis.getResults())
}

fun verify(program: CoreTACProgram): Boolean {
    val scene = SceneFactory.getScene(DegenerateContractSource("dummyScene"))
    val vRes = runBlocking { TACVerifier.verify(scene, program, DummyLiveStatsReporter) }
    val joinedRes = Verifier.JoinedResult(vRes)
    return joinedRes.finalResult.isSuccess()
}
