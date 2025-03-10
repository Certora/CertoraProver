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

import analysis.maybeNarrow
import config.Config
import config.ReportTypes
import vc.data.CoreTACProgram
import datastructures.stdcollections.*
import dwarf.InlinedFramesInfo
import sbf.callgraph.*
import sbf.disassembler.*
import sbf.inliner.*
import sbf.analysis.*
import sbf.domains.MemorySummaries
import sbf.output.annotateWithTypes
import sbf.slicer.*
import sbf.support.*
import sbf.tac.*
import log.*
import org.jetbrains.annotations.TestOnly
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import spec.cvlast.IRule
import tac.DumpTime
import vc.data.TACCmd
import vc.data.TACMeta
import kotlin.streams.*
import utils.*
import java.io.File

/**
 * For logging solana
 */
val sbfLogger = Logger(LoggerTypes.SBF)

data class CompiledSolanaRule(
    val code: CoreTACProgram,
    val isSatisfyRule: Boolean,
)

const val sanitySuffix = "\$sanity"

/* Entry point to the Solana SBF front-end */
@Suppress("ForbiddenMethodCall")
fun solanaSbfToTAC(elfFile: String): List<CompiledSolanaRule> {
    sbfLogger.info { "Started Solana front-end" }
    val start0 = System.currentTimeMillis()
    val targets = Config.SolanaEntrypoint.get()

    // Initialize the [InlinedFramesInfo] object for subsequent inlined frames queries.
    InlinedFramesInfo.init(elfFile)

    // 1. Process the ELF file that contains the SBF bytecode
    sbfLogger.info { "Disassembling ELF program $elfFile" }
    val disassembler = ElfDisassembler(elfFile)
    val bytecode = disassembler.read(targets.toList().map { it.removeSuffix(sanitySuffix)}. toSet())
    val globalsSymbolTable = disassembler.getGlobalsSymbolTable()
    // 2. Convert to sequence of labeled (pair of program counter and instruction) SBF instructions
    val sbfProgram = bytecodeToSbfProgram(bytecode)
    // 3. Convert to a set of CFGs (one per function)
    sbfLogger.info { "Generating a CFG for each function" }
    val cfgs = sbfProgramToSbfCfgs(sbfProgram)

    // 4. Read environment files
    val (memSummaries, inliningConfig) = readEnvironmentFiles()
    // Added default summaries for known external functions.
    // These default summaries are only used if no summary is already found in any of the environment files.
    CVTFunction.addSummaries(memSummaries)
    SolanaFunction.addSummaries(memSummaries)
    CompilerRtFunction.addSummaries(memSummaries)

    val rules = targets.mapNotNull { target ->
        try {
            solanaRuleToTAC(target, cfgs, inliningConfig, memSummaries, globalsSymbolTable, start0)
        } catch (e: SolanaError) {
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.ANALYSIS,
                severity = CVTAlertSeverity.ERROR,
                jumpToDefinition = null,
                message = "Cannot analyze rule $target:\n$e",
                hint = null
            )
            null
        }
    }.toList()

    val end0 = System.currentTimeMillis()
    sbfLogger.info { "End Solana front-end in ${(end0 - start0) / 1000}s" }

    return multiAssertChecks(rules)
}

private fun solanaRuleToTAC(target: String,
                            cfgs: SbfCallGraph,
                            inliningConfig: InlinerConfig,
                            memSummaries: MemorySummaries,
                            globalsSymbolTable: GlobalsSymbolTable,
                            start0: Long): CompiledSolanaRule {

    // 1. Inline all internal calls starting from `target` as root
    sbfLogger.info { "[$target] Started inlining " }
    val start1 = System.currentTimeMillis()
    val inlinedProg = inline(target.removeSuffix(sanitySuffix), target, cfgs, inliningConfig)
    val end1 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished inlining in ${(end1 - start1) / 1000}s" }

    if (!inlinedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { block ->
            block.getInstructions().any { it.isAssertOrSatisfy() }
        }) {
        throw NoAssertionError(target)
    }

    // 2. Slicing + PTA optimizations
    val optProg = sliceAndPTAOptLoop(target, inlinedProg, memSummaries, start0)
    // Run an analysis to infer global variables by use
    val optProgExt = runGlobalInferenceAnalysis(optProg, memSummaries, globalsSymbolTable)

    // Optionally, we annotate CFG with types. This is useful if the CFG will be printed.
    val analyzedProg = if (SolanaConfig.PrintAnalyzedToStdOut.get() || SolanaConfig.PrintAnalyzedToDot.get()) {
        annotateWithTypes(optProgExt, memSummaries)
    } else {
        optProgExt
    }

    if (SolanaConfig.PrintAnalyzedToStdOut.get()) {
        sbfLogger.info { "[$target] Analyzed program \n$analyzedProg\n" }
    }
    if (SolanaConfig.PrintAnalyzedToDot.get()) {
        analyzedProg.toDot(ArtifactManagerFactory().outputDir, true)
    }

    // 3. Perform memory analysis to map each memory operation to a memory partitioning
    val analysisResults =
        if (SolanaConfig.UsePTA.get()) {
            sbfLogger.info { "[$target] Started whole-program memory analysis " }

            val start = System.currentTimeMillis()
            val analysis = WholeProgramMemoryAnalysis(analyzedProg, memSummaries)
            try {
                analysis.inferAll()
            } catch (e: PointerAnalysisError) {
                when (e) {
                    // These are the PTA errors for which we can run some analysis to help debugging them
                    is UnknownStackPointerError,
                    is UnknownPointerDerefError,
                    is UnknownPointerStoreError,
                    is UnknownGlobalDerefError,
                    is UnknownStackContentError,
                    is UnknownMemcpyLenError,
                    is DerefOfAbsoluteAddressError -> explainPTAError(e, analyzedProg, memSummaries)
                    else -> {}
                }
                // we throw again the exception for the user to see
                throw e
            }
            val end = System.currentTimeMillis()
            sbfLogger.info { "[$target] Finished whole-program memory analysis in ${(end - start) / 1000}s" }
            if (SolanaConfig.PrintResultsToStdOut.get()) {
                sbfLogger.info { "[$target] Whole-program memory analysis results:\n${analysis}" }
            }
            if (SolanaConfig.PrintResultsToDot.get()) {
                sbfLogger.info { "[$target] Writing CFGs annotated with invariants to .dot files" }
                // Print CFG + invariants (only PTA graphs)
                analysis.toDot(printInvariants = true)
                analysis.dumpPTAGraphsSelectively(target)
            }
            analysis.getResults()
        } else {
            null
        }

    // 4. Convert to TAC
    sbfLogger.info { "[$target] Started translation to CoreTACProgram" }
    val start2 = System.currentTimeMillis()
    val coreTAC = sbfCFGsToTAC(analyzedProg, memSummaries, analysisResults)
    val isSatisfyRule = hasSatisfy(coreTAC)
    ArtifactManagerFactory().dumpCodeArtifacts(
        coreTAC,
        ReportTypes.SBF_TO_TAC,
        StaticArtifactLocation.Reports,
        DumpTime.AGNOSTIC
    )

    val end2 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished translation to CoreTACProgram in ${(end2 - start2) / 1000}s" }

    // 5. Unroll loops and perform optionally some TAC-to-TAC optimizations
    sbfLogger.info { "[$target] Started TAC optimizations" }
    val start3 = System.currentTimeMillis()
    val optCoreTAC = if (SolanaConfig.UseLegacyTACOpt.get()) {
        legacyOptimize(coreTAC, isSatisfyRule)
    } else {
        optimize(coreTAC, isSatisfyRule)
    }
    val end0 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished TAC optimizations in ${(end0 - start3) / 1000}s" }

    return CompiledSolanaRule(code = optCoreTAC, isSatisfyRule)
}

/**
 * Read PTA summaries and inlining files
 */
private fun readEnvironmentFiles(): Pair<MemorySummaries, InlinerConfig> {
    val summariesFilename = SolanaConfig.SummariesFilePath.get()
    val inliningFilename = SolanaConfig.InlineFilePath.get()
    val memSummaries = MemorySummaries.readSpecFile(summariesFilename)
    val inliningConfig = InlinerConfigFromFile.readSpecFile(inliningFilename)
    return Pair(memSummaries, inliningConfig)
}

private fun hasSatisfy(code: CoreTACProgram) =
    code.parallelLtacStream().mapNotNull {
        it.maybeNarrow<TACCmd.Simple.AssertCmd>()?.let {
            TACMeta.SATISFY_ID in it.cmd.meta
        }
    }.asSequence().any { it }

@TestOnly
fun multiAssertChecks(rules: List<CompiledSolanaRule>): List<CompiledSolanaRule> {
    return if (Config.MultiAssertCheck.get()) {
        val newRules = mutableListOf<CompiledSolanaRule>()
        rules.forEach { solanaRule ->
            val cvlRule = IRule.createDummyRule(solanaRule.code.name, solanaRule.isSatisfyRule)
            if (TACMultiAssert.shouldExecute(cvlRule)) {
                TACMultiAssert.transformTac(solanaRule.code, cvlRule).forEach {
                    newRules.add(CompiledSolanaRule(it.first, cvlRule.isSatisfyRule))
                }
            } else {
                newRules.add(solanaRule)
            }
        }
        newRules
    } else {
        rules
    }
}

/**
 * The main output of this function is a CFG in dot format where instructions that may flow data to the error location
 * are highlighted with different colors.
 * **Caveat**: the data-dependency analysis used to color instructions do not reason about non-stack memory.
 */
 private fun explainPTAError(e: PointerAnalysisError, prog: SbfCallGraph, memSummaries: MemorySummaries) {
    if (!SolanaConfig.PrintDevMsg.get()) {
        return
    }
    val errLocInst = e.devInfo.locInst
    val errReg = e.devInfo.ptrExp
    if (errLocInst == null || errReg == null) {
        return
    }
    val cfg = prog.getCallGraphRootSingleOrFail()
    val dda = DataDependencyAnalysis(errLocInst, errReg, cfg, prog.getGlobals(), memSummaries)
    val colorMap = dda.deps.associateWith {"Cyan"}.merge(dda.sources.associateWith {"Red"}) { _, c1, c2 ->
        if (c1!=null && c2==null) {
            c1
        } else if (c1==null && c2!=null) {
            c2
        } else {
            "Orange"
        }
    }
    val outFilename = "${ArtifactManagerFactory().outputDir}${File.separator}${cfg.getName()}.pta_error.dot"
    printToFile(outFilename, cfg.toDot(colorMap = colorMap))
}

