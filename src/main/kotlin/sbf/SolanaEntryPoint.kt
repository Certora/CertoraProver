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

import analysis.maybeAnnotation
import analysis.maybeNarrow
import cli.SanityValues
import config.Config
import vc.data.CoreTACProgram
import datastructures.stdcollections.*
import dwarf.DebugInfoReader
import sbf.callgraph.*
import sbf.disassembler.*
import sbf.inliner.*
import sbf.analysis.*
import sbf.output.annotateWithTypes
import sbf.slicer.*
import sbf.support.*
import sbf.tac.*
import log.*
import org.jetbrains.annotations.TestOnly
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import sbf.analysis.cpis.InvokeInstructionListener
import sbf.analysis.cpis.getInvokes
import sbf.cfg.*
import sbf.domains.*
import utils.Range
import spec.cvlast.RuleIdentifier
import spec.rules.EcosystemAgnosticRule
import spec.cvlast.SpecType
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
    val rule: EcosystemAgnosticRule
)

// Any rule name with these suffixes will be considered a vacuity/sanity rule
const val devVacuitySuffix = "\$sanity"
const val vacuitySuffix = "rule_not_vacuous_cvlr"

// SBF type factory used by WholeProgramMemoryAnalysis
private val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())

/* Entry point to the Solana SBF front-end */
@Suppress("ForbiddenMethodCall")
fun solanaSbfToTAC(elfFile: String): List<CompiledSolanaRule> {
    sbfLogger.info { "Started Solana front-end" }
    val start0 = System.currentTimeMillis()
    val targets = Config.SolanaEntrypoint.get().map { ruleName ->
        EcosystemAgnosticRule(
            ruleIdentifier = RuleIdentifier.freshIdentifier(ruleName),
            ruleType = SpecType.Single.FromUser.SpecFile,
            isSatisfyRule = ruleName.endsWith(devVacuitySuffix)
        )
    }

    val sanityRules = if (Config.DoSanityChecksForRules.get() != SanityValues.NONE && SolanaConfig.EnableCvlrVacuity.get()) {
        /**
         * In the case we are in sanity mode, all rules are duplicated for the vacuity check.
         * The new rules are derived from the original baseRule, this relationship is maintained
         * by using the rule type [SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck].
         *
         * We rely on this information to be present when building the rule tree via the [report.TreeViewReporter].
         */
        targets.filter { !it.isSatisfyRule }.map { baseRule ->
            baseRule.copy(
                ruleIdentifier = baseRule.ruleIdentifier.freshDerivedIdentifier(vacuitySuffix),
                ruleType = SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck(baseRule)
            )
        }
    } else {
        listOf()
    }

    // Initialize the [InlinedFramesInfo] object for subsequent inlined frames queries.
    DebugInfoReader.init(elfFile)

    // 1. Process the ELF file that contains the SBF bytecode
    sbfLogger.info { "Disassembling ELF program $elfFile" }
    val disassembler = ElfDisassembler(elfFile)
    val bytecode = disassembler.read(targets.mapToSet { it.ruleIdentifier.displayName.removeSuffix(devVacuitySuffix) })
    val globalsSymbolTable = disassembler.getGlobalsSymbolTable()

    // 2. Read environment files
    val (memSummaries, inliningConfig) = readEnvironmentFiles()
    // Added default summaries for known external functions.
    // These default summaries are only used if no summary is already found in any of the environment files.
    addDefaultSummaries(memSummaries)

    // 3. Convert to sequence of labeled (pair of program counter and instruction) SBF instructions
    val sbfProgram = bytecodeToSbfProgram(bytecode)
    // 4. Convert to a set of CFGs (one per function)
    sbfLogger.info { "Generating a CFG for each function" }
    val cfgs = sbfProgramToSbfCfgs(sbfProgram, inliningConfig, memSummaries)

    if (SolanaConfig.PrintAnalyzedToDot.get()) {
        cfgs.callGraphStructureToDot(ArtifactManagerFactory().outputDir)
    }

    val rules = (targets + sanityRules).mapNotNull { target ->
        try {
            solanaRuleToTAC(target, cfgs, inliningConfig, memSummaries, globalsSymbolTable, start0)
        } catch (e: SolanaError) {
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.ANALYSIS,
                severity = CVTAlertSeverity.ERROR,
                jumpToDefinition = null,
                message = "Cannot analyze rule ${target.ruleIdentifier.displayName}:\n$e",
                hint = null
            )
            null
        }
    }.toList()

    val end0 = System.currentTimeMillis()
    sbfLogger.info { "End Solana front-end in ${(end0 - start0) / 1000}s" }

    return multiAssertChecks(rules)
}

private fun solanaRuleToTAC(rule: EcosystemAgnosticRule,
                            prog: SbfCallGraph,
                            inliningConfig: InlinerConfig,
                            memSummaries: MemorySummaries,
                            globalsSymbolTable: GlobalsSymbolTable,
                            start0: Long): CompiledSolanaRule {

    val target = rule.ruleIdentifier.toString()
    // 1. Inline all internal calls starting from `target` as root
    sbfLogger.info { "[$target] Started inlining " }
    val start1 = System.currentTimeMillis()

    // `root` must be the name of an existing function. There are cases (e.g., vacuity rules) where `target` is not name of a function.
    //
    // If the rule is not a vacuity rule, the ruleIdentifier doesn't have a parent and `root` is the name of the rule (i.e., `target`)
    // after removing `devVacuitySuffix` in case it has it. Otherwise, `root` is the name of the parent rule associated with the vacuity rule.
    val root = rule.ruleIdentifier.parentIdentifier?.displayName ?: target.removeSuffix(devVacuitySuffix)
    val inlinedProg = inline(root, target, prog, memSummaries, inliningConfig)
    val end1 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished inlining in ${(end1 - start1) / 1000}s" }

    if (!inlinedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { block ->
            block.getInstructions().any { it.isAssertOrSatisfy() }
        }) {
        throw NoAssertionError(target)
    }

    // 2. Slicing + PTA optimizations
    val optProg = try {
        sliceAndPTAOptLoop(target, inlinedProg, memSummaries, start0)
    } catch (e: NoAssertionErrorAfterSlicer) {
        sbfLogger.warn{"$e"}
        vacuousProgram(target, "No assertions found after slicer")
    }

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

    // 3. (Optional) Remove CPI calls
    val progWithoutCPICalls = lowerCPICalls(target, analyzedProg, inliningConfig, memSummaries, start0)

    // 4. Perform memory analysis to map each memory operation to a memory partitioning
    val analysisResults =
        getMemoryAnalysis(target, progWithoutCPICalls, memSummaries, sbfTypesFac, processor = null)?.getResults()

    // 5. Convert to TAC
    sbfLogger.info { "[$target] Started translation to CoreTACProgram" }
    val start2 = System.currentTimeMillis()
    val coreTAC = sbfCFGsToTAC(progWithoutCPICalls, memSummaries, globalsSymbolTable, analysisResults)
    val isSatisfyRule = hasSatisfy(coreTAC)
    val end2 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished translation to CoreTACProgram in ${(end2 - start2) / 1000}s" }

    // 6. Unroll loops and perform optionally some TAC-to-TAC optimizations
    sbfLogger.info { "[$target] Started TAC optimizations" }
    val start3 = System.currentTimeMillis()
    val optCoreTAC = if (SolanaConfig.UseLegacyTACOpt.get()) {
        legacyOptimize(coreTAC, isSatisfyRule)
    } else {
        optimize(coreTAC, isSatisfyRule)
    }
    val end0 = System.currentTimeMillis()
    sbfLogger.info { "[$target] Finished TAC optimizations in ${(end0 - start3) / 1000}s" }

    return attachRangeToRule(rule, optCoreTAC, isSatisfyRule)
}

/**
 * Run memory analysis and, optionally, process each instruction with [processor]
 */
private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getMemoryAnalysis(
    target: String,
    program: SbfCallGraph,
    memSummaries: MemorySummaries,
    sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
    processor: InstructionListener<MemoryDomain<TNum, TOffset>>?
): WholeProgramMemoryAnalysis<TNum, TOffset>? = if (SolanaConfig.UsePTA.get()) {
    sbfLogger.info { "[$target] Started whole-program memory analysis " }

    val start = System.currentTimeMillis()
    val analysis = WholeProgramMemoryAnalysis(program, memSummaries, sbfTypesFac, processor)
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
            is DerefOfAbsoluteAddressError -> explainPTAError(e, program, memSummaries)

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
    analysis
} else {
    null
}

/**
 * Try to replace CPI calls (i.e., `invoke` or `invoke_signed calls`) with direct calls
 */
private fun lowerCPICalls(
    target: CfgName,
    program: SbfCallGraph,
    inliningConfig: InlinerConfig,
    memSummaries: MemorySummaries,
    start: Long
): SbfCallGraph {
    if (!SolanaConfig.EnableCpiAnalysis.get()) {
        return program
    }
    val invokes = getInvokes(program)
    val processor = InvokeInstructionListener<ConstantSet, ConstantSet>(invokes, program.getGlobals())
    val memAnalysis = getMemoryAnalysis(target, program, memSummaries, sbfTypesFac, processor = processor)
        ?: return program
    val cpiCalls = processor.getCpis()
    return substituteCpiCalls(memAnalysis, target, cpiCalls, inliningConfig, start)
}

/**
 * Attaches the correct range to a Solana rule and updates its originating rule if applicable.
 *
 * This function determines the correct range for the given rule and applies necessary modifications.
 * If the rule is derived from a basic rule, it updates the parent rule's range accordingly.
 */
private fun attachRangeToRule(
    rule: EcosystemAgnosticRule,
    optCoreTAC: CoreTACProgram,
    isSatisfyRule: Boolean
): CompiledSolanaRule {
    return if (rule.ruleType is SpecType.Single.GeneratedFromBasicRule) {
        // If the rule has been generated from a basic rule, then we have to update the parent rule range.
        // It would be more elegant to generate the original rule with the correct range, but [getRuleRange] relies on
        // annotation commands that can be generated only after the static analysis.
        // In fact, those annotations need the value and pointer analysis to be executed to be able to infer at compile
        // time constants that represent the file name and the line number.
        val parentRule = rule.ruleType.getOriginatingRule() as EcosystemAgnosticRule
        // Since this rule has been generated, we need to first get the name of the parent rule, and resolve the range
        // based on that.
        val ruleRange = DebugInfoReader.findFunctionRangeInSourcesDir(parentRule.ruleIdentifier.displayName)
            ?: getRuleRange(optCoreTAC) // If debug information is not available, reads the range from CVT_rule_location
        val newBaseRule = parentRule.copy(range = ruleRange)
        val ruleType = (rule.ruleType as SpecType.Single.GeneratedFromBasicRule).copyWithOriginalRule(newBaseRule)
        CompiledSolanaRule(
            code = optCoreTAC,
            rule = rule.copy(ruleType = ruleType, isSatisfyRule = isSatisfyRule, range = ruleRange)
        )
    } else {
        val ruleRange: Range =
            DebugInfoReader.findFunctionRangeInSourcesDir(rule.ruleIdentifier.displayName)
                ?: getRuleRange(optCoreTAC) // If debug information is not available, reads the range from CVT_rule_location
        CompiledSolanaRule(
            code = optCoreTAC,
            rule = rule.copy(isSatisfyRule = isSatisfyRule, range = ruleRange)
        )
    }
}

/**
 * Returns the [Range] associated with [tacProgram].
 * Iterates over the commands, and if *any* command is a [RuleLocationAnnotation], returns the
 * location associated with such command.
 * If there are no [RuleLocationAnnotation] or the location does not exist in the uploaded files, returns
 * [Range.Empty].
 * If there are multiple [RuleLocationAnnotation], selects non-deterministically one to read the
 * location from. If in the rules `CVT_rule_location` is called exactly once as the first instruction, this never
 * happens.
 */
private fun getRuleRange(tacProgram: CoreTACProgram): Range {
    val rangeFromAnnotation = tacProgram.parallelLtacStream()
        .mapNotNull { it.maybeAnnotation(RULE_LOCATION) }
        .findAny()
        .orElse(null)
        ?.toRange()
    return if (rangeFromAnnotation != null) {
        val fileInSourcesDir = File(Config.prependSourcesDir(rangeFromAnnotation.file))
        if (fileInSourcesDir.exists()) {
            rangeFromAnnotation
        } else {
            sbfLogger.warn { "file '$fileInSourcesDir' does not exist: jump to source information for rule will not be available" }
            Range.Empty()
        }
    } else {
        Range.Empty()
    }
}

/**
 * Add default summaries for some known external functions.
 * These default summaries are only used if no summary is already found in any of the environment files.
 */
private fun addDefaultSummaries(memSummaries: MemorySummaries) {
    CVTFunction.addSummaries(memSummaries)
    SolanaFunction.addSummaries(memSummaries)
    CompilerRtFunction.addSummaries(memSummaries)
    AbortFunction.addSummaries(memSummaries)
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
            if (TACMultiAssert.shouldExecute(solanaRule)) {
                TACMultiAssert.transformTac(solanaRule.rule, solanaRule.code).forEach { (newRule, code) ->
                    newRules.add(CompiledSolanaRule(code = code, rule = newRule))
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
 * Return the vacuous program:
 *
 * ```
 * assume(false)
 * assert(false)
 * ```
 */
private fun vacuousProgram(root: String, comment: String): SbfCallGraph {
    val cfg = MutableSbfCFG(root)
    val b = cfg.getOrInsertBlock(Label.fresh())
    cfg.setEntry(b)
    cfg.setExit(b)
    val rx = Value.Reg(SbfRegister.R3_ARG)
    val ry = Value.Reg(SbfRegister.R4_ARG)
    b.add(SbfInstruction.Bin(BinOp.MOV, rx, Value.Imm(0UL), is64 = true))
    b.add(SbfInstruction.Bin(BinOp.MOV, ry, Value.Imm(1UL), is64 = true))
    val falseC = Condition(CondOp.GT, rx, ry)
    b.add(SbfInstruction.Assume(falseC))
    b.add(SbfInstruction.Assert(falseC, MetaData(SbfMeta.COMMENT to comment)))
    b.add(SbfInstruction.Exit())
    return MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), newGlobalVariableMap())
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
    val colorMap = dda.deps.associateWith { "Cyan" }.merge(dda.sources.associateWith { "Red" }) { _, c1, c2 ->
        if (c1 != null && c2 == null) {
            c1
        } else if (c1 == null && c2 != null) {
            c2
        } else {
            "Orange"
        }
    }
    val outFilename = "${ArtifactManagerFactory().outputDir}${File.separator}${cfg.getName()}.pta_error.dot"
    printToFile(outFilename, cfg.toDot(colorMap = colorMap))
}

