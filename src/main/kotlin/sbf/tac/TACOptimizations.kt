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

package sbf.tac

import analysis.controlflow.InfeasiblePaths
import analysis.loop.LoopHoistingOptimization
import analysis.opt.*
import analysis.opt.bytemaps.optimizeBytemaps
import analysis.opt.inliner.GlobalInliner
import analysis.opt.intervals.IntervalsRewriter
import analysis.opt.overflow.OverflowPatternRewriter
import analysis.split.BoolOptimizer
import config.Config
import config.ReportTypes
import instrumentation.transformers.FilteringFunctions
import instrumentation.transformers.TACDSA
import instrumentation.transformers.optimizeAssignments
import log.*
import optimizer.Pruner
import sbf.SolanaConfig
import tac.DumpTime
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACBuiltInFunction
import vc.data.TACExpr
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldAll
import vc.data.tacexprutil.TACExprUtils.contains
import verifier.BlockMerger
import verifier.CoreToCoreTransformer
import verifier.SimpleMemoryOptimizer
import wasm.WasmEntryPoint

fun optimize(coreTAC: CoreTACProgram, isSatisfyRule: Boolean): CoreTACProgram {
    val optLevel = SolanaConfig.TACOptLevel.get()
    val optTACProgram = CoreTACProgram.Linear(coreTAC)
        .map(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
        .map(CoreToCoreTransformer(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks))
        .map(CoreToCoreTransformer(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations))
        .map(CoreToCoreTransformer(ReportTypes.UNROLL, CoreTACProgram::convertToLoopFreeCode))
        .also {
            ArtifactManagerFactory().dumpCodeArtifacts(
            it.ref,
            ReportTypes.SBF_TO_TAC,
            StaticArtifactLocation.Reports,
            DumpTime.AGNOSTIC)
        }
        .mapIf(isSatisfyRule, CoreToCoreTransformer(ReportTypes.REWRITE_ASSERTS, WasmEntryPoint::rewriteAsserts))
        .map(CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) {
            unfoldAll(it) { e ->
                    e.rhs is TACExpr.BinOp.BWXOr ||
                    e.rhs is TACExpr.BinOp.BWOr ||
                    e.rhs is TACExpr.UnaryExp.LNot
            }.let {
                PatternRewriter.rewrite(it, PatternRewriter::solanaPatternsList)
            }
        })
        // constant propagation + cleanup + merging blocks
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) {
            ConstantPropagatorAndSimplifier(it).rewrite().let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        // we don't fold diamonds with assumes in them, because it creates assumes with disjunctions, and
        // `IntervalsCalculator` can't work well with those.
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) { DiamondSimplifier.simplifyDiamonds(it, iterative = true, allowAssumes = false) })
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.OPTIMIZE_BOOL_VARIABLES) { c -> BoolOptimizer(c).go() })
        // constant propagation + cleanup + merging blocks
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) {
            ConstantPropagatorAndSimplifier(it).rewrite().let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.NEGATION_NORMALIZER) { NegationNormalizer(it).rewrite() })
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) { PatternRewriter.rewrite(it, PatternRewriter::earlyPatternsList) })
        // We remove unused map writes. It might also help the map scalarizer if a dead write does not have a constant index
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.REMOVE_UNUSED_WRITES, SimpleMemoryOptimizer::removeUnusedWrites))
        .mapIf(optLevel >= 3, CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
            IntervalsRewriter.rewrite(it, handleLeinoVars = false). let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        .mapIf(optLevel >= 2,   CoreToCoreTransformer(ReportTypes.BYTEMAP_OPTIMIZER1) {
            // ensure that all commands involving byte maps are unfolded.
            // Moreover, it applies some byte map simplifications before and after doing bytemap scalarization.
            optimizeBytemaps(it, FilteringFunctions.default(it, keepRevertManagment = true), cheap = false)
                .let(BlockMerger::mergeBlocks)
        })
        // Simplify byte map reads/writes + cleanup + merging blocks
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.GLOBAL_INLINER1) {
            GlobalInliner.inlineAll(it).let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.OPTIMIZE_OVERFLOW) { OverflowPatternRewriter(it).go() })
        // constant propagation + cleanup + merging blocks
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) {
            ConstantPropagatorAndSimplifier(it).rewrite().let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
            IntervalsRewriter.rewrite(it, handleLeinoVars = false) })
        // Simplify diamonds after interval rewriter. This time we fold assumes
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) { DiamondSimplifier.simplifyDiamonds(it, iterative = true) })
        // after pruning infeasible paths, there are more constants to propagate
        .mapIf(optLevel >= 1, CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) {
            ConstantPropagatorAndSimplifier(it).rewrite().let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })
        .mapIf(optLevel >= 4, CoreToCoreTransformer(ReportTypes.OPTIMIZE_INFEASIBLE_PATHS) {
            InfeasiblePaths.doInfeasibleBranchAnalysisAndPruning(it) })
        // interval rewriter could benefit from assume introduced by previous optimization
        .mapIf(optLevel >= 4, CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
            IntervalsRewriter.rewrite(it, handleLeinoVars = false) })
        // after pruning infeasible paths, there are more constants to propagate
        .mapIf(optLevel >= 4, CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) {
            ConstantPropagatorAndSimplifier(it).rewrite().let {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
            }.let(BlockMerger::mergeBlocks)
        })

    return optTACProgram.ref
}


fun legacyOptimize(coreTAC: CoreTACProgram, isSatisfyRule: Boolean): CoreTACProgram {
    val optLevel = SolanaConfig.TACOptLevel.get()
    val preprocessed = CoreTACProgram.Linear(coreTAC)
        .map(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
        .map(CoreToCoreTransformer(ReportTypes.HOIST_LOOPS, LoopHoistingOptimization::hoistLoopComputations))
        .map(CoreToCoreTransformer(ReportTypes.UNROLL, CoreTACProgram::convertToLoopFreeCode))
        .also {
            ArtifactManagerFactory().dumpCodeArtifacts(
                it.ref,
                ReportTypes.SBF_TO_TAC,
                StaticArtifactLocation.Reports,
                DumpTime.AGNOSTIC)
        }
        .mapIf(isSatisfyRule, CoreToCoreTransformer(ReportTypes.REWRITE_ASSERTS, WasmEntryPoint::rewriteAsserts))
        .map(CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) {
            // We need to ensure 3 address code before applying the pattern rewriter.
            unfoldAll(it) { e ->
                    e.rhs is TACExpr.BinOp.BWXOr ||
                    e.rhs is TACExpr.BinOp.BWOr ||
                    e.rhs is TACExpr.UnaryExp.LNot
            }.let {
                PatternRewriter.rewrite(it, PatternRewriter::solanaPatternsList)
            }
        })

    val maybeOptimized1 = runIf(optLevel >= 1) {
        preprocessed
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantPropagator::propagateConstants))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE, ConstantComputationInliner::rewriteConstantCalculations))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_BOOL_VARIABLES) { c -> BoolOptimizer(c).go() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.NEGATION_NORMALIZER) { NegationNormalizer(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) { PatternRewriter.rewrite(it, PatternRewriter::earlyPatternsList) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.GLOBAL_INLINER1) { GlobalInliner.inlineAll(it) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.UNUSED_ASSIGNMENTS) {
                optimizeAssignments(it, FilteringFunctions.default(it, keepRevertManagment = true))
                    .let(BlockMerger::mergeBlocks)
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_OVERFLOW) { OverflowPatternRewriter(it).go() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.COLLAPSE_EMPTY_DSA, TACDSA::collapseEmptyAssignmentBlocks))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS1) {
                ConstantPropagator.propagateConstants(it, emptySet()).let {
                    BlockMerger.mergeBlocks(it)
                }
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.REMOVE_UNUSED_WRITES, SimpleMemoryOptimizer::removeUnusedWrites))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE) { c ->
                optimizeAssignments(
                    c,
                    FilteringFunctions.default(c, keepRevertManagment = true)
                ).let(BlockMerger::mergeBlocks)
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                IntervalsRewriter.rewrite(
                    it,
                    handleLeinoVars = false
                )
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE1) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_DIAMONDS) { DiamondSimplifier.simplifyDiamonds(it, iterative = true) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS2) {
                // after pruning infeasible paths, there are more constants to propagate
                ConstantPropagator.propagateConstants(it, emptySet())
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE2) { Pruner(it).prune() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_MERGE_BLOCKS, BlockMerger::mergeBlocks))
    }

    val maybeOptimized2 = runIf(optLevel >= 2) {
        check(maybeOptimized1 != null) { "Unexpected problem in Solana TAC optimizer -O2"}
        maybeOptimized1
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.GLOBAL_INLINER2) { GlobalInliner.inlineAll(it) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS1) {
                ConstantPropagator.propagateConstants(it, emptySet()).let {
                    BlockMerger.mergeBlocks(it)
                }
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                IntervalsRewriter.rewrite(
                    it,
                    handleLeinoVars = false
                )
            })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.BYTEMAP_OPTIMIZER1) {
                // ensure that all commands involving byte maps are unfolded.
                // Moreover, it applies some byte map simplifications before and after doing bytemap scalarization.
                optimizeBytemaps(it, FilteringFunctions.default(it, keepRevertManagment = true), cheap = false)
                    .let(BlockMerger::mergeBlocks)
            })
            .letIf(Config.extraSolanaPatterns.get()) { c ->
                c.map(CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) { code ->
                    unfoldAll(code) { e ->
                        e.rhs.contains {
                            it is TACExpr.Vec.IntMul ||
                                it is TACExpr.BinOp.ShiftRightLogical ||
                                ((it as? TACExpr.Apply)?.f as? TACExpr.TACFunctionSym.BuiltIn)
                                    ?.bif is TACBuiltInFunction.SafeMathNarrow
                        }
                    }.let { c ->
                        PatternRewriter.rewrite(c, PatternRewriter::solanaPatternsList)
                    }
                }).mapIfAllowed(CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE) {
                    IntervalsRewriter.rewrite(it, handleLeinoVars = false)
                }).map(CoreToCoreTransformer(ReportTypes.PATTERN_REWRITER) {
                    PatternRewriter.rewrite(it, PatternRewriter::solanaPatternsList)
                })
            }

    }

    val maybeOptimized3 = runIf(optLevel >= 3) {
        check(maybeOptimized2 != null) { "Unexpected problem in Solana TAC optimizer -O3"}
        maybeOptimized2
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_INFEASIBLE_PATHS) {
                InfeasiblePaths.doInfeasibleBranchAnalysisAndPruning(
                    it
                )
            })
    }

    return maybeOptimized3?.ref ?: (maybeOptimized2?.ref ?: (maybeOptimized1?.ref ?: preprocessed.ref))
}
