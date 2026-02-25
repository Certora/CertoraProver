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
import cli.SanityValues
import config.Config
import config.isTwoStageMode
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
import sbf.analysis.cpis.InvokeInstructionListener
import sbf.analysis.cpis.getInvokes
import sbf.analysis.cpis.cpisSubstitutionMap
import sbf.cfg.*
import sbf.domains.*
import utils.Range
import spec.cvlast.RuleIdentifier
import spec.rules.EcosystemAgnosticRule
import spec.cvlast.SpecType
import utils.*
import verifier.RuleEncodingException
import java.io.File
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

/**
 * For logging solana
 */
val sbfLogger = Logger(LoggerTypes.SBF)

// Any rule name with these suffixes will be considered a vacuity rule
const val devVacuitySuffix = "\$sanity"
const val vacuitySuffix = "rule_not_vacuous_cvlr"

/** SBF type factory used by `WholeProgramMemoryAnalysis` **/
private val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
/** `PTANode` flags used by `WholeProgramMemoryAnalysis` **/
private val ptaFlagsFac = { SolanaPTANodeFlags() }



/* Entry point to the Solana SBF front-end */
@Suppress("ForbiddenMethodCall")
fun solanaSbfToTAC(elfFile: String): List<SolanaEncodeResult> {
    sbfLogger.info { "Started Solana front-end" }
    val start0 = System.currentTimeMillis()
    val targets = Config.SolanaEntrypoint.get().map { ruleName ->
        EcosystemAgnosticRule(
            ruleIdentifier = RuleIdentifier.freshIdentifier(ruleName),
            ruleType = SpecType.Single.FromUser.SpecFile,
            isSatisfyRule = ruleName.endsWith(devVacuitySuffix)
        )
    }

    val sanityRules =
        if (Config.DoSanityChecksForRules.get() != SanityValues.NONE && SolanaConfig.EnableCvlrVacuity.get()) {
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

    // 2. Read environment files
    val (memSummaries, inliningConfig) = readEnvironmentFiles()
    // Added default summaries for known external functions.
    // These default summaries are only used if no summary is already found in any of the environment files.
    addDefaultSummaries(memSummaries)

    // 3. Convert to sequence of labeled (pair of program counter and instruction) SBF instructions
    val sbfProgram = bytecodeToSbfProgram(bytecode)
    // 4. Convert to a set of CFGs (one per function)
    val cfgs = timeIt("generation of CFG for each function") {
        sbfProgramToSbfCfgs(sbfProgram, inliningConfig, memSummaries)
    }

    if (SolanaConfig.PrintAnalyzedToDot.get()) {
        cfgs.callGraphStructureToDot(ArtifactManagerFactory().outputDir)
    }

    val rules = (targets + sanityRules).mapNotNull { target ->
        try {
            solanaRuleToTAC(target, cfgs, inliningConfig, memSummaries)?.let { success(it) }
        } catch (e: SolanaError) {
            failure(RuleEncodingException(target, e))
        }
    }

    val end0 = System.currentTimeMillis()
    sbfLogger.info { "End Solana front-end in ${(end0 - start0) / 1000}s" }

    return rules
}

/** errors here are handled by throwing, while returning null signifies that the rule should be skipped */
@OptIn(Config.DestructiveOptimizationsOption::class)
private fun solanaRuleToTAC(
    rule: EcosystemAgnosticRule,
    prog: SbfCallGraph,
    inliningConfig: InlinerConfig,
    memSummaries: MemorySummaries
): SolanaEncodedRule? {

    val target = rule.ruleIdentifier.toString()
    // 1. Inline all internal calls starting from `target` as root
    val inlinedProg = timeIt(target, "inlining") {
        // `root` must be the name of an existing function. There are cases (e.g., vacuity rules) where `target` is not name of a function.
        //
        // If the rule is not a vacuity rule, the ruleIdentifier doesn't have a parent and `root` is the name of the rule (i.e., `target`)
        // after removing `devVacuitySuffix` in case it has it. Otherwise, `root` is the name of the parent rule associated with the vacuity rule.
        val root = rule.ruleIdentifier.parentIdentifier?.displayName ?: target.removeSuffix(devVacuitySuffix)
        inline(root, target, prog, memSummaries, inliningConfig)
    }


    val isVacuityRule = rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck

    val hasSatisfies = inlinedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { b ->
        b.getInstructions().any { it.isSatisfy() }
    }

    val hasAssertions: Boolean by lazy  {
        inlinedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { b ->
            b.getInstructions().any { it.isAssert() }
        }
    }

    if (isVacuityRule && hasSatisfies) {
        sbfLogger.warn{
            "Skipped $target." +
            "$target is a vacuity rule and its parent rule has already a call to \"CVT_satisfy\""
        }
        return null
    }

    if (!isVacuityRule && !hasSatisfies && !hasAssertions) {
        throw NoAssertionError(target)
    }

    val isSatisfiedRule = hasSatisfies || isVacuityRule

    // 2. Slicing + PTA optimizations
    val optProg = try {
        sliceAndPTAOptLoop(target,
                           removeSanityCalls(inlinedProg, isVacuityRule),
                           memSummaries)
    } catch (e: NoAssertionAfterSlicerError) {
        sbfLogger.warn { "$e" }
        vacuousProgram(target, inlinedProg.getGlobals(), "No assertions found after slicer")
    }

    // 3. Remove CPI calls and run analysis to infer global variables
    val optProgWithoutCPIs = lowerCPICalls(
        target,
        optProg,
        inliningConfig,
        memSummaries,
        sbfTypesFac,
        ptaFlagsFac
      ).let {
        runGlobalInferenceAnalysis(it, memSummaries)
    }

    // Optionally, we annotate CFG with types. This is useful if the CFG will be printed.
    val printStdOut = SolanaConfig.PrintAnalyzedToStdOut.get()
    val printDot = SolanaConfig.PrintAnalyzedToDot.get()
    val analyzedProg = if (printStdOut || printDot) {
        annotateWithTypes(optProgWithoutCPIs, memSummaries).also {
            if (printStdOut) {
                sbfLogger.info { "[$target] Analyzed program \n$it\n" }
            }
            if (printDot) {
                it.toDot(ArtifactManagerFactory().outputDir, onlyEntryPoint = true)
            }
        }
    } else {
        optProgWithoutCPIs
    }

    if (hasSatisfies) {
        if (!analyzedProg.getCallGraphRootSingleOrFail().getBlocks().values.any { block ->
            block.getInstructions().any { it.isSatisfy() }}) {
            throw NoSatisfyAfterSlicerError(target)
        }
    }

    // 3.5 Consume "attached location" instructions in the program, and inline them as meta
    val progWithLocations = timeIt(target, "inlining attached locations") {
        inlineAttachedLocations(analyzedProg, memSummaries)
    }

    // 4. Perform memory analysis to map each memory operation to a memory partitioning
    val analysisResults =
        getMemoryAnalysis(
            target,
            progWithLocations,
            memSummaries,
            sbfTypesFac,
            ptaFlagsFac,
            MemoryDomainOpts(useEqualityDomain = false),
            processor = null)?.getResults()

    // 5. Convert to TAC
    val coreTAC = timeIt(target, "translation of CoreTACProgram") {
        sbfCFGsToTAC(progWithLocations, memSummaries, analysisResults)
    }

    val mayBeUnrollLoops = if(Config.DestructiveOptimizationsMode.get().isTwoStageMode()){
        /**
         * When [config.isTwoStageMode] is set, DSA and loop unrolling is performed here so that
         * in the second round ([rules.TwoStageRound.SECOND_ROUND]) loops are already unrolled.
         *
         * Having no loops in the second round of two stage mode is beneficial, as more variables
         * can be fixed.
         */
        runDSAandUnrollLoops(coreTAC)
    } else {
        /**
         * DAS + loop unrolling is delayed in the pipeline and is part of [sbf.tac.levelZeroOptimizations]
         */
        coreTAC
    }

    return attachRangeToRule(rule, mayBeUnrollLoops, isSatisfiedRule)
}

/**
 * Run memory analysis and, optionally, process each instruction with [processor]
 */
private fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags: IPTANodeFlags<TFlags>> getMemoryAnalysis(
    target: String,
    program: SbfCallGraph,
    memSummaries: MemorySummaries,
    sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
    ptaFlagsFac: () -> TFlags,
    opts: MemoryDomainOpts,
    processor: InstructionListener<MemoryDomain<TNum, TOffset, TFlags>>?
): WholeProgramMemoryAnalysis<TNum, TOffset, TFlags>? = if (SolanaConfig.UsePTA.get()) {

    val analysis = timeIt(target, "whole-program memory analysis") {
        val analysis = WholeProgramMemoryAnalysis(program, memSummaries, sbfTypesFac, ptaFlagsFac, opts, processor)
        try {
            analysis.inferAll()
        } catch (e: PointerAnalysisError) {
            when (e) { // These are the PTA errors for which we can run some analysis to help debugging them
                is UnknownStackPointerError,
                is UnknownPointerDerefError,
                is UnknownPointerStoreError,
                is UnknownGlobalDerefError,
                is UnknownStackContentError,
                is UnknownMemcpyLenError,
                is DerefOfAbsoluteAddressError,
                is StackCannotBeScalarizedAfterMemcpyError,
                is PointerStackEscapingError -> explainPTAError(e, program, memSummaries)
                else -> {}
            }
            // we throw again the exception for the user to see
            throw e
        }
        analysis
    }

    if (SolanaConfig.PrintResultsToStdOut.get()) {
        sbfLogger.info { "[$target] Whole-program memory analysis results:\n${analysis}" }
    }
    if (SolanaConfig.PrintResultsToDot.get()) {
        sbfLogger.info { "[$target] Writing CFGs annotated with PTA graphs to .dot files" }
        // Print CFG + invariants (only PTA graphs)
        analysis.toDot(printInvariants = true)
    }
    val blocksToDump = SolanaConfig.DumpPTAGraphsToDot.getOrNull()
    if (!blocksToDump.isNullOrEmpty()) {
        analysis.dumpPTAGraphsSelectively(ArtifactManagerFactory().outputDir, target) { b ->
            blocksToDump.contains(b.getLabel().toString())
        }
    }
    analysis
} else {
    null
}

/**
 * Try to replace CPI calls (i.e., `invoke` or `invoke_signed calls`) with direct calls
 */
private fun<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, Flags: IPTANodeFlags<Flags>> lowerCPICalls(
    target: CfgName,
    p1: SbfCallGraph,
    inliningConfig: InlinerConfig,
    memSummaries: MemorySummaries,
    sbfTypesFac: ISbfTypeFactory<TNum, TOffset>,
    ptaFlagsFac: () -> Flags
): SbfCallGraph {
    if (!SolanaConfig.EnableCpiAnalysis.get()) {
        return p1
    }

    val p3 = timeIt(target, "lowering of CPI calls") {
        // Run an analysis to infer global variables by use
        val p2 = runGlobalInferenceAnalysis(p1, memSummaries)

        val invokes = getInvokes(p2)
        val processor = InvokeInstructionListener<TNum, TOffset, Flags>(
            cpisSubstitutionMap,
            invokes,
            p2.getGlobals()
        )

        val memDomOpts = MemoryDomainOpts(useEqualityDomain = true)
        val memAnalysis = getMemoryAnalysis(
            target,
            p2,
            memSummaries,
            sbfTypesFac,
            ptaFlagsFac,
            memDomOpts,
            processor = processor
        )

        if (memAnalysis == null) {
            sbfLogger.info {
                    "\tUnexpected problem during memory analysis. Skipped lowering of CPI calls\n" +
                    "[$target] Finished lowering of CPI calls."
            }
            return p1
        }

        val cpiCalls = processor.getCpis()
        substituteCpiCalls(memAnalysis, target, cpiCalls, inliningConfig)
    }

    // HACK: remove some annotations added by the memory analysis.
    // These annotations are generated and consumed by the memory analysis.
    return p3.transformSingleEntry {
        val outCFG = it.clone(it.getName())
        outCFG.removeAnnotations(listOf(SbfMeta.REG_TYPE))
        outCFG
    }
}

/**
 * If [isVacuityRule] is true then replace `CVT_sanity` to `CVT_satisfy`, else the call to `CVT_sanity` is removed.
 **/
fun removeSanityCalls(prog: SbfCallGraph, isVacuityRule: Boolean): SbfCallGraph {
    return prog.transformSingleEntry { cfg ->
        cfg.clone(cfg.getName()).let {
            removeSanityCalls(it, isVacuityRule)
            it
        }
    }
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
): SolanaEncodedRule {
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
        SolanaEncodedRule(
            rule = rule.copy(ruleType = ruleType, isSatisfyRule = isSatisfyRule, range = ruleRange),
            code = optCoreTAC,
        )
    } else {
        val ruleRange: Range =
            DebugInfoReader.findFunctionRangeInSourcesDir(rule.ruleIdentifier.displayName)
                ?: getRuleRange(optCoreTAC) // If debug information is not available, reads the range from CVT_rule_location
        SolanaEncodedRule(
            rule = rule.copy(isSatisfyRule = isSatisfyRule, range = ruleRange),
            code = optCoreTAC,
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
    AbortFunctions.addSummaries(memSummaries)
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

/**
 * Return the vacuous program:
 *
 * ```
 * assume(false)
 * assert(false)
 * ```
 */
private fun vacuousProgram(root: String, globals: GlobalVariables, comment: String): SbfCallGraph {
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
    return MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), globals)
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

