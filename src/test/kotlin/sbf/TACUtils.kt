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

import annotations.PollutesGlobalState
import config.CommandLineParser
import config.Config
import config.ConfigType
import datastructures.stdcollections.*
import sbf.analysis.WholeProgramMemoryAnalysis
import sbf.callgraph.CVTFunction
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.SbfCFG
import sbf.tac.sbfCFGsToTAC
import kotlinx.coroutines.runBlocking
import report.DummyLiveStatsReporter
import sbf.callgraph.CompilerRtFunction
import sbf.callgraph.SolanaFunction
import sbf.disassembler.*
import sbf.domains.*
import scene.SceneFactory
import scene.source.DegenerateContractSource
import smt.SmtDumpEnum
import spec.cvlast.CVLScope
import spec.cvlast.RuleIdentifier
import spec.rules.AssertRule
import vc.data.CoreTACProgram
import verifier.TACVerifier
import verifier.Verifier

@OptIn(PollutesGlobalState::class)
fun maybeEnableReportGeneration() {
    CommandLineParser.setExecNameAndDirectory()
    ConfigType.WithArtifacts.set(log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts)
    Config.Smt.DumpAll.set(SmtDumpEnum.TOFILE)
    Config.ShouldDeleteSMTFiles.set(false)
}

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

object DefaultElfFileView: IElfFileView {
    override fun isLittleEndian() = true
    override fun isGlobalVariable(address: ElfAddress) = false
    override fun isReadOnlyGlobalVariable(address: ElfAddress) = false
    override fun getAsConstantString(
        address: ElfAddress,
        size: Long
    ) = ""

    override fun getAsConstantNum(
        address: ElfAddress,
        size: Long
    ): Long? = null
}

fun toTAC (cfg: SbfCFG,
           summaryFileContents: List<String> = listOf(),
           globals: GlobalVariables = GlobalVariables(DefaultElfFileView)
): CoreTACProgram {
    val prog = MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), globals)
    val memSummaries = MemorySummaries.readSpecFile(summaryFileContents,"unknown")
    CVTFunction.addSummaries(memSummaries)
    SolanaFunction.addSummaries(memSummaries)
    CompilerRtFunction.addSummaries(memSummaries)
    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val flagsFac = { BasicPTANodeFlags() }
    val memAnalysis = WholeProgramMemoryAnalysis(
        prog,
        memSummaries,
        sbfTypesFac,
        flagsFac,
        MemoryDomainOpts(useEqualityDomain = false),
        processor = null)
    memAnalysis.inferAll()
    return sbfCFGsToTAC(prog, memSummaries, memAnalysis.getResults())
}

fun verify(program: CoreTACProgram, report: Boolean = false): Boolean {
    if (report) {
        maybeEnableReportGeneration()
    }
    val scene = SceneFactory.getScene(DegenerateContractSource("dummyScene"))
    val vRes = runBlocking { TACVerifier.verify(scene, program, DummyLiveStatsReporter) }
    val joinedRes = Verifier.JoinedResult(vRes)
    if (report) {
        // Fake rule to allow report generation
        val reportRule = CVLScope.AstScope.extendIn(CVLScope.Item::RuleScopeItem) { scope ->
            AssertRule(RuleIdentifier.freshIdentifier(program.name), false, program.name, scope)
        }
        joinedRes.reportOutput(reportRule)
    }
    return joinedRes.finalResult.isSuccess()
}
