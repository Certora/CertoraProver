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

package verifier

import analysis.controlflow.InfeasiblePaths
import analysis.icfg.SummaryStack
import analysis.maybeAnnotation
import analysis.opt.*
import analysis.opt.bytemaps.optimizeBytemaps
import analysis.opt.intervals.IntervalsRewriter
import analysis.opt.overflow.OverflowPatternRewriter
import analysis.split.BoolOptimizer
import config.ReportTypes
import config.ReportTypes.*
import datastructures.stdcollections.*
import instrumentation.transformers.*
import optimizer.Pruner
import scene.SceneIdentifiers
import utils.*
import vc.data.CoreTACProgram
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACCmd
import vc.data.destructiveOptimizations

interface CTPOptimizationPass {
    val reportType: ReportTypes
    fun optimize(ctp: CoreTACProgram): CoreTACProgram

    companion object {
        fun make(reportType: ReportTypes, optimize: (CoreTACProgram) -> CoreTACProgram) =
            object : CTPOptimizationPass {
                override val reportType = reportType
                override fun optimize(ctp: CoreTACProgram) = optimize(ctp)
            }

        fun Iterable<CTPOptimizationPass>.runOn(tacToCheck : CoreTACProgram) =
            fold(CoreTACProgram.Linear(tacToCheck)) { acc, opt ->
                acc.mapIfAllowed(CoreToCoreTransformer(opt.reportType, opt::optimize))
            }.ref

        val snippetRemoval = make(SNIPPET_REMOVAL, SnippetRemover::rewrite)

        fun constantPropagatorAndSimplifier(mergeBlocks: Boolean) = make(PROPAGATOR_SIMPLIFIER) { ctp ->
            ConstantPropagatorAndSimplifier(ctp).rewrite().letIf(mergeBlocks, BlockMerger::mergeBlocks)
        }

        fun simplifyDiamonds(iterative: Boolean, allowAssumes: Boolean = true) = make(OPTIMIZE_DIAMONDS) { ctp ->
            DiamondSimplifier.simplifyDiamonds(ctp, iterative = iterative, allowAssumes = allowAssumes)
        }

        val boolOptimizer = make(OPTIMIZE_BOOL_VARIABLES) { ctp -> BoolOptimizer(ctp).go() }

        val FPMonotonicityInstrumentor =
            make(ASSUME_STRICT_MONOTONIC_FP, FPMonotonicityInstrumenter::assumeStrictlyMonotonicFP)

        fun disjointHashesMaterializer(scene: SceneIdentifiers) = make(MATERIALIZE_DISJOINT_HASHES) { ctp ->
            DisjointHashesMaterializer.materializeDisjointHashes(scene, ctp)
        }

        fun constantPropagator(reportNum: Int, mergeBlocks: Boolean) = make(
            when (reportNum) {
                1 -> OPTIMIZE_PROPAGATE_CONSTANTS1
                2 -> OPTIMIZE_PROPAGATE_CONSTANTS2
                else -> error("Invalid report num $reportNum")
            }
        ) { ctp ->
            ConstantPropagator.propagateConstants(ctp, emptySet()).letIf(mergeBlocks, BlockMerger::mergeBlocks)
        }

        val removeCallAnnotations = make(REMOVE_CALL_ANNOTATIONS) { ctp ->
            if (ctp.destructiveOptimizations) {
                ctp.parallelLtacStream().filter {
                    it.maybeAnnotation(SummaryStack.END_EXTERNAL_SUMMARY) != null ||
                        it.maybeAnnotation(SummaryStack.START_EXTERNAL_SUMMARY) != null ||
                        it.maybeAnnotation(SummaryStack.START_INTERNAL_SUMMARY) != null ||
                        it.maybeAnnotation(SummaryStack.END_INTERNAL_SUMMARY) != null

                }.patchForEach(ctp) {
                    replaceCommand(it.ptr, listOf(TACCmd.Simple.NopCmd))
                }
            } else {
                ctp
            }
        }

        val removeUnusedWrites = make(REMOVE_UNUSED_WRITES, SimpleMemoryOptimizer::removeUnusedWrites)

        val rewriteCopyLoops = make(REWRITE_COPY_LOOP, SimpleMemoryOptimizer::rewriteCopyLoops)

        val removeDeadPartitions = make(REMOVE_UNUSED_PARTITIONS, SimpleMemoryOptimizer::removeDeadPartitions)

        fun optimizeAssignments(keepRevertManagement: Boolean = false, bmcAware: Boolean = false) =
            make(UNUSED_ASSIGNMENTS) { ctp ->
                optimizeAssignments(ctp, FilteringFunctions.default(ctp, keepRevertManagement, bmcAware), strict = false)
                    .let(BlockMerger::mergeBlocks)
            }

        fun pruner(reportNum: Int) = make(
            when (reportNum) {
                1 -> PATH_OPTIMIZE1
                2 -> PATH_OPTIMIZE2
                else -> error("Invalid report num $reportNum")
            }
        ) { ctp -> Pruner(ctp).prune() }

        val infeasiblePaths = make(OPTIMIZE_INFEASIBLE_PATHS, InfeasiblePaths::doInfeasibleBranchAnalysisAndPruning)

        fun simpleSummaries(reportNum: Int) = make(
            when (reportNum) {
                1 -> SIMPLE_SUMMARIES1
                2 -> SIMPLE_SUMMARIES2
                else -> error("Invalid report num $reportNum")
            }
        ) { ctp -> ctp.simpleSummaries() }

        val negationNormalizer = make(NEGATION_NORMALIZER) { ctp -> NegationNormalizer(ctp).rewrite() }

        fun patternRewriter(patternList: PatternRewriter.() -> List<PatternRewriter.PatternHandler>) =
            make(PATTERN_REWRITER) { ctp ->
                PatternRewriter.rewrite(ctp, patternList)
            }

        fun bytemapOptimizer(reportNum: Int, bmcAware: Boolean, cheap: Boolean = false) = make(
            when (reportNum) {
                1 -> BYTEMAP_OPTIMIZER1
                2 -> BYTEMAP_OPTIMIZER2
                3 -> BYTEMAP_OPTIMIZER3
                else -> error("Invalid report num $reportNum")
            }
        ) {
            optimizeBytemaps(it, FilteringFunctions.default(it, keepRevertManagment = true, bmcAware), cheap)
        }

        val overflowPatternRewriter = make(OPTIMIZE_OVERFLOW) { ctp -> OverflowPatternRewriter(ctp).go() }

        val ternarySimplifier = make(TERNARY_OPTIMIZE) { ctp ->
            TernarySimplifier.simplify(ctp, afterSummarization = true, forbiddenVars = emptySet())
        }

        val intervalsRewriter =
            make(INTERVALS_OPTIMIZE) { ctp -> IntervalsRewriter.rewrite(ctp, handleLeinoVars = false) }

        val blockMerger = make(OPTIMIZE_MERGE_BLOCKS, BlockMerger::mergeBlocks)

        val quantifierAnnotator = make(QUANTIFIER_POLARITY) { ctp -> QuantifierAnnotator(ctp).annotate() }

        val initByteMaps = make(INIT_VARS, InsertMapDefinitions::transform)
    }
}
