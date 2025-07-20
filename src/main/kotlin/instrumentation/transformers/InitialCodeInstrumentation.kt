/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package instrumentation.transformers

import analysis.CmdPointer
import analysis.icfg.*
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import evm.SighashInt
import normalizer.AnnotationRemover
import optimizer.Pruner
import rules.SummaryMonitor
import scene.IScene
import spec.CVL
import spec.CVLCompiler
import spec.rules.CVLSingleRule
import tac.TACBasicMeta
import tac.Tag
import vc.data.*
import vc.gen.TACSimpleSimple
import verifier.CoreToCoreTransformer
import java.math.BigInteger

/**
 * Wrapper object for [applySummariesAndGhostHooksAndAxiomsTransformations] which as its name suggests instruments the
 * provided [CoreTACProgram] with the summaries (including inlining of all method calls), hooks, and axioms (and a few
 * other stuff - see the implementation for details).
 */
object InitialCodeInstrumentation {
    /**
     * Iterates over the [SummaryStack.SummaryStart] annotations commands in [prog],
     * and report them to [summaryMonitor].
     */
    private fun feedSummaryMonitor(summaryMonitor: SummaryMonitor?, prog: CoreTACProgram) {
        if (summaryMonitor == null) {
            return
        }

        val topoSortedSummaryStartAnnotCmds = prog.topoSortedSummaryStart()
        topoSortedSummaryStartAnnotCmds.forEach { summStartAnnotCmd ->
            val summStart = summStartAnnotCmd.cmd.annot.v as SummaryStack.SummaryStart
            val appliedSummary = summStart.appliedSummary
            if (appliedSummary is Summarization.AppliedSummary.MethodsBlock) {
                summaryMonitor.declareUseOf(appliedSummary)
            }
        }
    }

    /**
    Handles CallSummaries in the code, and updates the summary monitor.
     */
    private fun applySummaries(code: CoreTACProgram, scene: IScene, cvl: CVL, summaryMonitor: SummaryMonitor?) =
        Summarization.handleSummaries(
            code,
            scene, cvl,
            CVLCompiler(scene, cvl, code.name, code.symbolTable.globalScope)
        ).also {
            // this is hopefully idempotent
            feedSummaryMonitor(summaryMonitor, it)
        }


    fun applySummariesAndGhostHooksAndAxiomsTransformations(
        tac: CoreTACProgram, scene: IScene, cvl: CVL, rule: CVLSingleRule?, summaryMonitor: SummaryMonitor?
    ): CoreTACProgram =
        CoreTACProgram.Linear(tac)
            .map(CoreToCoreTransformer(ReportTypes.EXTCODECOPY_HANDLE, { code -> ExtcodecopyInstructionHandler.work(code, scene) }))
            .map(CoreToCoreTransformer(ReportTypes.PATH_OPTIMIZE0) { ctp ->
                /*
                 * Why go through all this rigamarole? Well, if we have a custom dispatcher implemented in spec
                 * (which consist of if/else cases over the sighash of an instantiated parametric method)
                 * then every call in the branches of these conditionals will be inlined, and summaries processed, etc. etc.
                 * leading to a huge explosion in the size of the static CFG, despite exactly one branch being feasible.
                 *
                 * Thus, we prune early here. Note also that to avoid the fallback case (which doesn't have any single,
                 * concrete sighash we can use in comparisons), we extend the simplifier to understand the "disjointSighash" application
                 * and use that to prune branches.
                 */
                object : Pruner(ctp) {
                    // If ghosts are assigned in the body of a CVL rule to constants, they might be reassigned as a side effect of other functions.
                    // Therefore we stop at a ghost. Similarly, lastReverted is set as a side effect of function calls,
                    // so we don't want to prune paths that depend on them either.
                    override val stopAt: ((TACSymbol.Var) -> Boolean) = {
                        it.meta.containsKey(TACMeta.CVL_GHOST) ||
                            (Config.CvlFunctionRevert.get() && it.meta.containsKey(TACBasicMeta.LAST_REVERTED))
                    }

                    override val simplifier: ExpressionSimplifier =
                        object : ExpressionSimplifier(
                            ctp.analysisCache.graph
                        ) {
                            context(ExpressionSimplifier.SimplificationContext)
                            override suspend fun simplifyBinExp(
                                e: TACExpr.BinExp,
                                ptr: CmdPointer,
                            ): TACExpr {
                                if (e !is TACExpr.BinRel.Eq) {
                                    return super.simplifyBinExp(e, ptr)
                                }
                                if (!e.operandsAreSyms()) {
                                    return super.simplifyBinExp(e, ptr)
                                }
                                inPrestate {
                                    val o1 = this.simplify(
                                        e.o1,
                                        ptr = ptr
                                    )
                                    val o2 = this.simplify(
                                        e.o2,
                                        ptr = ptr
                                    )
                                    val o1c = o1.getAsConst()
                                    val o2c = o2.getAsConst()
                                    return if (o1c != null && o2c != null) {
                                        TACSymbol.lift(o1c == o2c).asSym()
                                    } else if (o1c != null && isPlausibleSighash(o1c) && o2 is TACExpr.Sym.Var) {
                                        checkSighashDisjoint(o2, ptr, o1c, stopAt)
                                    } else if (o2c != null && isPlausibleSighash(o2c) && o1 is TACExpr.Sym.Var) {
                                        checkSighashDisjoint(
                                            o1, ptr, o2c, stopAt
                                        )
                                    } else {
                                        TACExpr.BinRel.Eq(o1, o2, Tag.Bool)
                                    }
                                }
                            }

                            context(ExpressionSimplifier.SimplificationContext)
                            private suspend fun checkSighashDisjoint(
                                otherOp: TACExpr.Sym.Var,
                                ptr: CmdPointer,
                                o1c: BigInteger,
                                stopAt: ((TACSymbol.Var) -> Boolean)?
                            ): TACExpr {
                                return nonTrivialDefAnalysis.getDefAsExpr<TACExpr>(
                                    otherOp.s,
                                    ptr,
                                    stopAt
                                )?.let {
                                    inPrestate {
                                        this.simplify(it.exp, it.ptr)
                                    }
                                }?.let {
                                    it as? TACExpr.Apply
                                }?.takeIf {
                                    (it.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif == TACBuiltInFunction.DisjointSighashes &&
                                        it.ops.size == 1
                                }?.ops?.single()?.evalAsConst()?.let {
                                    scene.getContractOrNull(it)
                                }?.getMethods()?.all {
                                    it.sigHash != SighashInt(o1c)
                                }?.let(TACSymbol.Companion::lift)?.asSym() ?: TACExpr.BinRel.Eq(
                                    o1c.asTACSymbol().asSym(),
                                    otherOp
                                )
                            }

                            fun isPlausibleSighash(o1c: BigInteger): Boolean {
                                // Fits in 32 bits and larger than 256
                                return o1c > BigInteger.TWO.pow(8) && o1c < BigInteger.TWO.pow(32)
                            }
                        }
                }.prune()
            })
            .map(CoreToCoreTransformer(ReportTypes.APPLIED_SUMMARIES1) { code ->
                applySummaries(code, scene, cvl, summaryMonitor)
            }).map(CoreToCoreTransformer(ReportTypes.OPAQUE_IDENTITY_REMOVAL_2, AnnotationRemover::removeOpaqueIdentities))
            .map(CoreToCoreTransformer(ReportTypes.SPLIT_STORAGE_VAR_HOISTER) { code ->
                // for proper hooking over split storage variables in expression such as
                // tacS!...:bv256 + 1, we need to lift those to tmp := tacS!...:bv256 and then use tmp instead
                SplitStorageVarsHoister.transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.INLINED_HOOKS) { code ->
                // inline hooks' codes
                HookInliner(scene, CVLCompiler(
                    scene, cvl, code.name, code.symbolTable.globalScope
                )
                ).transform(code)
            }).mapIf(Config.BoundedModelChecking.getOrNull() == null, CoreToCoreTransformer(ReportTypes.REQUIRE_INVARIANT_TRANSFORMER) { code ->
                // translates requireInvariant commands
                RequireInvariantTransformer(scene).transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.APPLIED_SUMMARIES2) { code ->
                // IMPORTANT: hooks will not be instrumented for summaries applied at this stage
                // (i.e. if the summaries call Solidity)
                applySummaries(code, scene, cvl, summaryMonitor)
            }).map(CoreToCoreTransformer(ReportTypes.POST_SUMMARIZATION_STORAGE_CLEANUP) { code ->
                CVLToSimpleCompiler.finalizeStorageMovement(
                    code
                )
            }).mapIf(rule != null, CoreToCoreTransformer(ReportTypes.STRONG_INVARIANT_INLINER) { code ->
                // inlines strong invariants
                StrongInvariantInliner(scene, CVLCompiler(
                    scene, cvl, code.name, code.symbolTable.globalScope
                ), rule!!).transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.REVERT_PATH_GENERATOR) {
                if (Config.CvlFunctionRevert.get()) {
                    RevertPathGenerator.transform(it)
                } else {
                    it
                }
            }).map(CoreToCoreTransformer(ReportTypes.REVERT_MATERIALIZATION) {
                Inliner.materializeRevertManagement(it, scene)
            }).map(CoreToCoreTransformer(ReportTypes.GHOST_ANNOTATION) { code ->
                // apply ghost save/restore, which should line up with solidity reverts and CVL storage management
                // this is implemented outside of summaries, because it's better to be handled after we applied hooks
                GhostSaveRestoreInstrumenter.transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.FOUNDRY_ANNOTATION) { code ->
                if (Config.Foundry.get()) {
                    // apply foundry cheatcodes
                    FoundryInstrumenter.transform(code)
                } else {
                    code
                }
            }).map(CoreToCoreTransformer(ReportTypes.AXIOM_INLINING) { code ->
                // Inline axiom expressions into tac so analysis and optimization will be axiom aware.
                // This has to come after materializing all ghosts, hooks, and summarizations.
                // In general for correctness, axiom inlining should happen after all code pieces are present.
                AxiomInliner.transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.GHOST_SUM_INSTRUMENTER) { code ->
                // Inline sum ghost updates. This must come after axiom inlining because the axioms may have
                // references to ghosts that are summed. Since we know the sum ghosts don't have any axioms, it's OK
                // to inline them here.
                GhostSumInstrumenter(cvl.ghosts).transform(code)
            }).map(CoreToCoreTransformer(ReportTypes.SIMPLE_HASH) { code ->
                // Apply the simple-simple-ing of AssignSha3Cmd, as it generates some asserts we want to split on
                TACSimpleSimple.simpleSimpleHashing(code)
            }).map(CoreToCoreTransformer(ReportTypes.OBJECT_PATH_MATCHING) { code ->
                ObjectPathMatchingTransformer.analysis(code)
            }).map(CoreToCoreTransformer(ReportTypes.ABI_ENCODE_REMOVAL_POST_SUMMARY) { code ->
                ABIOptimizations.garbageCollectCVLEncoding(code)
            }).ref
}
