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

package wasm

import analysis.controlflow.*
import analysis.loop.LoopHoistingOptimization
import analysis.maybeNarrow
import analysis.opt.*
import analysis.opt.inliner.GlobalInliner
import analysis.opt.intervals.*
import analysis.opt.overflow.OverflowPatternRewriter
import analysis.split.*
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.*
import log.*
import optimizer.*
import rules.RuleSplitter.fixupAssertSnippetAnnotations
import spec.cvlast.RuleIdentifier
import spec.rules.EcosystemAgnosticRule
import spec.cvlast.SpecType
import tac.DumpTime
import utils.*
import vc.data.*
import verifier.*
import wasm.analysis.ConstantArrayInitializationRewriter
import wasm.analysis.ConstantArrayInitializationSummarizer
import wasm.analysis.intervals.IntervalBasedExprSimplifier
import wasm.cfg.WasmCFG.Companion.wasmAstToWasmCfg
import wasm.debugsymbols.InlinedFunctionAnnotator
import wasm.host.WasmHost
import wasm.impCfg.StraightLine
import wasm.impCfg.WasmCfgToWasmImpCfg.brTabToBrIf
import wasm.impCfg.WasmCfgToWasmImpCfg.callIndirectToCall
import wasm.impCfg.WasmCfgToWasmImpCfg.havocify
import wasm.impCfg.WasmCfgToWasmImpCfg.initializeGlobalsAndArgs
import wasm.impCfg.WasmCfgToWasmImpCfg.mergeBlocks
import wasm.impCfg.WasmCfgToWasmImpCfg.wasmCfgToWasmImpCfg
import wasm.impCfg.WasmImpCfgProgram
import wasm.impCfg.WasmImpCfgToTAC
import wasm.impCfg.WasmInliner
import wasm.ir.WasmCVTRule
import wasm.ir.WasmName
import wasm.ir.WasmProgram
import wasm.summarization.summarizers
import wasm.summarization.WasmBuiltinCallSummarizer
import wasm.summarization.WasmHostCallSummarizer
import wasm.summarization.soroban.SorobanSDKSummarizer
import wasm.tacutils.*
import wasm.traps.ConditionalTrapRevert
import java.io.File
import java.util.stream.Collectors
import wasm.transform.BitopsRewriter


class InvalidRules(msg: String) : Exception(msg)
class TrivialRule(msg: String) : Exception(msg)
class InvalidEntryPoint(msg: String) : Exception(msg)
class MixedAssertAndSatisfy(msg: String) : Exception(msg)

private val wasmLogger = Logger(LoggerTypes.WASM)

data class CompiledWasmRule(
    val code: CoreTACProgram,
    val rule: EcosystemAgnosticRule,
)

object WasmEntryPoint {

    /**
     * This function takes an input file in .wasm format and generates a [CoreTACProgram] from it.
     */
    fun webAssemblyToTAC(inputFile: File, selectedRules: Set<String>, env: WasmHost, optimize: Boolean): Collection<CompiledWasmRule> {
        wasmLogger.info { "Starting WASM front-end" }
        val startTime = System.currentTimeMillis()

        check(ArtifactFileUtils.isWasm(inputFile.name)) {"Expecting a .wasm file as input, but got ${inputFile.name}"}
        val wasmAST = WasmLoader(inputFile).convert()

        return wasmProgramToTAC(wasmAST, selectedRules, env, optimize).also {
            val endTime = System.currentTimeMillis()
            wasmLogger.info { "Wasm to CoreTac translation took: ${(endTime - startTime) / 1000} seconds." }
        }
    }

    // Alternate entrypoint for tests (do we still need this? --Eric)
    fun wasmToTAC(wasmFile: File, selectedRules: Set<String>, env: WasmHost, optimize: Boolean): Collection<CompiledWasmRule> {
        return wasmProgramToTAC(WasmLoader(wasmFile).convert(), selectedRules, env, optimize)
    }

    /**
     * Return a mapping from exported name to internal function name,
     * where each key k is the name of a "rule" to run.
     *
     * This function will attempt to validate [userSelectedRules] against the list of
     * "known" embedded rules. If there is no such set of known rules, then
     * we treat the user selected rule as a rule.
     *
     * @return a map from export name N to function name F. The set of such Ns returned is
     *   [userSelectedRules], unless it is empty, in which case all known rules are returned.
     */
    private fun computeTargetFunctions(wasm: WasmProgram, userSelectedRules: Set<String>): Map<String, WasmName> {
        fun String.lookupExportOrFail(msg: () -> String) =
            wasm.findExportByName(this)?.desc?.id ?: throw InvalidEntryPoint(msg())

        val embeddedRules = wasm.fields
            .filterIsInstance<WasmCVTRule>()
            .takeIf { it.isNotEmpty() }
            ?.flatMap {
                it.names.map {
                    it to it.lookupExportOrFail { "Binary lists $it as a rule but it is not listed as an export" }
                }
            }?.toMap()

        if (userSelectedRules.isEmpty()) {
            Logger.always("No rules selected", respectQuiet = true)
            return embeddedRules.orEmpty()
        }

        val selectedRules = userSelectedRules.associateWith {
            it.lookupExportOrFail { "Invalid user-selected entry point: $it" }
        }

        // We can't validate the selected rules if there is no list of known rules
        if (embeddedRules == null) {
            return selectedRules
        }

        // Otherwise, make sure all the selected rules were listed in the rules section
        val missing = selectedRules.keys - embeddedRules.keys

        if (missing.isNotEmpty()) {
            val missingRules = missing.joinToString(separator=", ")
            throw InvalidRules("Selected rules are unknown: $missingRules")
        }

        return selectedRules
    }

    private fun wasmProgramToTAC(wasmAST: WasmProgram, selectedRules: Set<String>, env: WasmHost, optimize: Boolean): Collection<CompiledWasmRule> {
        /*
        Convert the sexpression object to a WasmProgram AST
        */
        wasmLogger.info { "Generating WAT AST from parsed sexpressions" }
        val elemTable = wasmAST.elemTable

        /*
        Find the target functions
         */
        val targets = computeTargetFunctions(wasmAST, selectedRules)

        /*
        Convert the AST to a CFG, one per function.
        NOTE: this also removes empty functions.
        See more in wasmAstToWasmCfg().
        */
        wasmLogger.info { "Generating WAT CFG from WAT AST" }
        val wasmCfgs = wasmAstToWasmCfg(wasmAST)

        /*
        Convert each WASM CFG to a WASM TAC program, perform block merging, and apply the
        pass that rewrites havoc expressions to havoc commands.
         */
        wasmLogger.info { "Generating WAT TAC from WAT CFG" }
        val wasmTacs = mutableMapOf<WasmName, WasmImpCfgProgram>()
        for ((funcId, wasmCfg) in wasmCfgs.entries) {
            wasmLogger.info { "WasmCfg program for $funcId" }
            wasmLogger.info { wasmCfg.dumpWasmCfg() }
            val wasmTac: WasmImpCfgProgram = wasmCfgToWasmImpCfg(funcId, wasmAST, elemTable, wasmCfg)

            //Add function start and end annotations
            val annotatedWasmCfg = wasmAST.wasmDebugSymbols?.let{InlinedFunctionAnnotator(it).addInlinedFunctionAnnotations(wasmTac)} ?: wasmTac
            // Convert brtabs to brifs.
            val brtabFreeWtac = brTabToBrIf(annotatedWasmCfg)
            wasmLogger.info { "WasmCfg eliminate brTab $funcId" }
            wasmLogger.info { brtabFreeWtac.dumpWasmImpCfg() }
            // Merge blocks
            val mergedWasmTac = mergeBlocks(brtabFreeWtac)
            // Translate havoc expressions  to havoc instructions
            val havocifiedWasmTac = havocify(mergedWasmTac)
            // Convert call_indirect to call with condition checking
            val callIndirectFreeWtac = callIndirectToCall(havocifiedWasmTac, wasmAST.allFuncTypes, wasmAST.typeTable)
            wasmTacs[funcId] = callIndirectFreeWtac
            wasmLogger.info { "WasmImpCfg program for $funcId" }
            wasmLogger.info { wasmTacs[funcId]!!.dumpWasmImpCfg() }
        }

        /*
        Inline function calls and prune any unreachable nodes.
        */
        val summarizer = summarizers(
            listOfNotNull(
                WasmHostCallSummarizer(env.importer(wasmAST)),
                WasmBuiltinCallSummarizer(wasmAST.allFuncTypes, wasmAST.dataFields),
                SorobanSDKSummarizer(wasmAST.allFuncTypes).takeIf { Config.SorobanSDKSummaries.get() }
            )
        )

        Logger.always("Running initial transformations", respectQuiet = true)

        val rules = targets.entries.parallelStream().map { (ruleExportName, ruleFuncId) ->
            wasmLogger.info { "Running on entrypoint: $ruleFuncId"}

            wasmLogger.info { "Before inlining WasmImpCfg program" }
            wasmLogger.info { wasmTacs[ruleFuncId]!!.dumpWasmImpCfg() }

            val inlined = WasmInliner().inline(wasmTacs, ruleFuncId) {
                !summarizer.canSummarize(it)
            }

            val isSatisfyRule = computeIsSatisfyRule(inlined, wasmAST.allFuncTypes)

            wasmLogger.info { "After inlining WasmImpCfg program" }
            wasmLogger.info { inlined.dumpWasmImpCfg() }

            val prog = inlined.pruneUnreachableImp(inlined.entryPt)
            // append the values of the globals, and havoc the input arguments
            val wtacWithGlobalsInitialized = initializeGlobalsAndArgs(ruleFuncId, prog.first, wasmAST)
            wasmLogger.info { "Pruned inlined WasmImpCfg program" }
            wasmLogger.info { wtacWithGlobalsInitialized.dumpWasmImpCfg() }

            /*
            Convert the wasmTAC program to a CoreTACProgram.
            */
            wasmLogger.info { "Generating CoreTAC from WasmImpCfg" }
            val coreTac = WasmImpCfgToTAC.wasmImpCfgToCoreTac(
                targetFuncName = ruleExportName,
                wasmTac = wtacWithGlobalsInitialized,
                summarizer = summarizer,
                hostInit = env.init()
            )
            wasmLogger.info { WasmImpCfgToTAC.dumpTAC(coreTac) }
            ArtifactManagerFactory().dumpCodeArtifacts(coreTac, ReportTypes.JIMPLE, DumpTime.POST_TRANSFORM)

            val preprocessed = CoreTACProgram.Linear(coreTac)
                .let { env.applyPreUnrollTransforms(it, wasmAST) }
                .map(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
                .map(CoreToCoreTransformer(ReportTypes.OPTIMIZE_WASM_BITOPS, BitopsRewriter::rewriteXorEquality))
                .map(CoreToCoreTransformer(ReportTypes.OPTIMIZE_WASM_BITOPS, BitopsRewriter::rewriteSignedOverflowCheck))
                .map(CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE, IntervalBasedExprSimplifier::analyze))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.WASM_INIT_LOOP_SUMMARIZATION, ConstantArrayInitializationSummarizer::annotateLoops))
                .map(CoreToCoreTransformer(ReportTypes.WASM_INIT_LOOP_REWRITE, ConstantArrayInitializationRewriter::unrollStaticLoops))
                .map(CoreToCoreTransformer(ReportTypes.UNROLL, CoreTACProgram::convertToLoopFreeCode))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantPropagator::propagateConstants))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantComputationInliner::rewriteConstantCalculations))
                .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_POST_UNROLL_ASSIGNMENTS) { PostUnrollAssignmentSummary.materialize(wasmAST, it) })
                .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_CONDITIONAL_TRAPS, ConditionalTrapRevert::materialize))
                .mapIf(isSatisfyRule, CoreToCoreTransformer(ReportTypes.REWRITE_ASSERTS, ::rewriteAsserts))

            val maybeOptimized = runIf(optimize) {
                preprocessed
                // This sequence helps us eliminate branches from encoding rust enums into Val when the
                // constructor is constant. This in turn can simplify the job for the soroban memory optimization
                .map(CoreToCoreTransformer(ReportTypes.OPTIMIZE, GlobalInliner::inlineAll))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })

                .let { env.applyOptimizations(it, wasmAST) }
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_BOOL_VARIABLES) { BoolOptimizer(it).go() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.NEGATION_NORMALIZER) { NegationNormalizer(it).rewrite() })
                .mapIfAllowed(
                    CoreToCoreTransformer(ReportTypes.UNUSED_ASSIGNMENTS) {
                        val filtering = FilteringFunctions.default(it, keepRevertManagment = true)::isErasable
                        removeUnusedAssignments(it, expensive = false, filtering, isTypechecked = true)
                            .let(BlockMerger::mergeBlocks)
                    }
                )
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks))
                .mapIfAllowed(
                    CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS1) {
                        ConstantPropagator.propagateConstants(it, emptySet()).let {
                            BlockMerger.mergeBlocks(it)
                        }
                    }
                )
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.REMOVE_UNUSED_WRITES, SimpleMemoryOptimizer::removeUnusedWrites))
                .mapIfAllowed(
                    CoreToCoreTransformer(ReportTypes.OPTIMIZE) { c ->
                        optimizeAssignments(c,
                            FilteringFunctions.default(c, keepRevertManagment = true)
                        ).let(BlockMerger::mergeBlocks)
                    }
                )
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_INFEASIBLE_PATHS) { InfeasiblePaths.doInfeasibleBranchAnalysisAndPruning(it) })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.SIMPLE_SUMMARIES1) { it.simpleSummaries() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_OVERFLOW) { OverflowPatternRewriter(it).go() })
                .mapIfAllowed(
                    CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                        IntervalsRewriter.rewrite(it, handleLeinoVars = false)
                    }
                )
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) { simplifyDiamonds(it, iterative = true) })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS2) {
                        // after pruning infeasible paths, there are more constants to propagate
                        ConstantPropagator.propagateConstants(it, emptySet())
                    }
                )
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE2) { Pruner(it).prune() })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_MERGE_BLOCKS, BlockMerger::mergeBlocks))
                .map(CoreToCoreTransformer(ReportTypes.QUANTIFIER_POLARITY) { QuantifierAnnotator(it).annotate() })
            }

            CompiledWasmRule(
                code = maybeOptimized?.ref ?: preprocessed.ref,
                rule = EcosystemAgnosticRule(
                    ruleIdentifier = RuleIdentifier.freshIdentifier(ruleExportName),
                    ruleType = SpecType.Single.FromUser.SpecFile,
                    isSatisfyRule = isSatisfyRule
                )
            )
        }.collect(Collectors.toList())
        Logger.always("Completed initial transformations", respectQuiet = true)

        return rules
    }

    private fun computeIsSatisfyRule(inlined: WasmImpCfgProgram, typingContext: Map<WasmName, WasmProgram.WasmFuncDesc>): Boolean {
        val assumeAndAsserts = inlined.parallelImpCfgCmdStream().mapNotNull {(_, cmd) ->
            cmd.tryAs<StraightLine.Call>()?.let{ call ->
                WasmBuiltinCallSummarizer.asBuiltin(typingContext, call.id)?.takeIf { builtin ->
                    builtin == WasmBuiltinCallSummarizer.CVTBuiltin.SATISFY ||
                        builtin == WasmBuiltinCallSummarizer.CVTBuiltin.CVT_SATISFY ||
                        builtin == WasmBuiltinCallSummarizer.CVTBuiltin.ASSERT ||
                        builtin == WasmBuiltinCallSummarizer.CVTBuiltin.CVT_ASSERT
                }
            }
        }.collect(Collectors.toSet())

        // If there are no user asserts/satisfy commands, then the rule is trivial
        if (assumeAndAsserts.isEmpty()) {
            if (!Config.TrapAsAssert.get()) {
                throw TrivialRule("Rule contains no assertions after compilation. Assertions may have been trivially unreachable and removed by the compiler.")
            }
            return false
        }

        val singleAssertType = assumeAndAsserts.singleOrNull() ?: throw MixedAssertAndSatisfy("Cannot mix assert and satisfy commands")

        return singleAssertType == WasmBuiltinCallSummarizer.CVTBuiltin.SATISFY ||
               singleAssertType == WasmBuiltinCallSummarizer.CVTBuiltin.CVT_SATISFY
    }

    /** Assuming [code] is a satisfy rule, rewrite all generated "asserts" into "assumes" */
    fun rewriteAsserts(code: CoreTACProgram): CoreTACProgram {
        val asserts = code.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()
        }.collect(Collectors.toList())

        check (asserts.all {
            TACMeta.CVL_USER_DEFINED_ASSERT !in it.cmd.meta || TACMeta.SATISFY_ID in it.cmd.meta
        }) {
            "rewriteAsserts must only be called on a satisfy rule"
        }

        return code.patching { p ->
            val removedAssertConds = mutableListOf<TACSymbol>()

            for ((ptr, cmd) in asserts) {
                when {
                    TACMeta.SATISFY_ID in cmd.meta ->
                        {}
                    else -> {
                        removedAssertConds += cmd.o
                        p.replaceCommand(ptr, listOf(TACCmd.Simple.AssumeCmd(cmd.o, cmd.meta)))
                    }
                }
            }

            fixupAssertSnippetAnnotations(code, p, removedAssertConds)
        }
    }
}
