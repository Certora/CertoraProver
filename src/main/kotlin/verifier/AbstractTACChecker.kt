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

import analysis.opt.scalarizer.ByteMapScalarizer
import analysis.skeyannotation.AnnotateSkeyBifs
import config.Config
import config.ConfigType
import config.ReportTypes
import datastructures.NonEmptyList
import instrumentation.transformers.*
import instrumentation.transformers.FilteringFunctions.Companion.default
import log.*
import optimizer.SinkOptimizer
import report.LiveStatsReporter
import report.dumps.UnsolvedSplitInfo
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.data.SatResult
import solver.CounterexampleModel
import solver.SMTCounterexampleModel
import solver.SolverResult
import tac.DumpTime
import testing.TacPipelineDebuggers.oneStateInvariant
import utils.*
import vc.FullProgramReachabilityResult
import vc.data.*
import vc.data.transformations.DivisionUnderapproximation
import vc.data.transformations.DropBwNops
import vc.gen.TACSimpleSimple
import verifier.mus.UnsatCoreInputData
import verifier.splits.SplitAddress
import java.io.FileInputStream
import java.io.Serializable

private val loggerTimes = Logger(LoggerTypes.TIMES)
private val logger = Logger(LoggerTypes.COMMON)


/**
 * This base class is required in order to have a common base class to all verifier results returned by CompiledRule.
 */
abstract class AbstractTACChecker {

    /**
     * Holds information about an [AbstractTACCheckerResult], which may be unique from example
     * to example (in a case of multi-examples).
     */

    @KSerializable
    data class ExampleInfo (
        val errorMessage: String? = null,
        val model: CounterexampleModel = CounterexampleModel.Empty,
        val reason: String? = null,
        // this is here since in the future, we are planning to have
        // both bad and good examples (SAT and UNSAT)
        val solverResult: SolverResult
    ): AmbiSerializable {
        companion object {
            operator fun invoke(
                model: SMTCounterexampleModel = SMTCounterexampleModel.Empty,
                smtFormulaCheckerResult: SmtFormulaCheckerResult,
            ): ExampleInfo {
                return ExampleInfo(
                    errorMessage = (smtFormulaCheckerResult.satResult as? SatResult.UNKNOWN)?.let { res ->
                        res.infoString.takeIf { res.reason.shouldBeReportedAsError }
                    },
                    model = model,
                    reason = (smtFormulaCheckerResult.satResult as? SatResult.UNKNOWN)?.reason.toString(),
                    solverResult = SolverResult.fromCheckerResult(smtFormulaCheckerResult),
                )
            }
        }
    }

    abstract class AbstractTACCheckerResult: Serializable {

        abstract val name: String
        abstract val tac: CoreTACProgram
        abstract val elapsedTimeSeconds: Long
        abstract val examplesInfo: NonEmptyList<ExampleInfo>
        abstract val reachabilityIndicator: FullProgramReachabilityResult?
        /** Contains  unsat core information for each split if we have computed it. */
        abstract val unsatCoreSplitsData: Map<SplitAddress, UnsatCoreInputData>?
        /** Contains all the blocks that lie any split that we have not solved yet (typically due to a timeout). */
        abstract val unsolvedSplitsInfo: UnsolvedSplitInfo?

        /**
         * A sample out of the examples-info, which is the one.
         */
        val firstExampleInfo: ExampleInfo
            get() = examplesInfo.head

        /**
         * Aggregation of the solver-results attached to the [ExampleInfo]s.
         */
        val finalResult: SolverResult
            get() = SolverResult.join(examplesInfo.map { it.solverResult })
    }

    abstract suspend fun runVerifierOnFile(fileName: String)

    abstract suspend fun runVerifier(tacObject: CoreTACProgram, liveStatsReporter: LiveStatsReporter): AbstractTACCheckerResult

    protected fun loadTACFromFile(inputFileName: String): CoreTACProgram {
        loggerTimes.info { "TAC Parsing start" }
        val tacParsingStart = System.currentTimeMillis()
        val parsedTACCode =
            CoreTACProgram.fromStream(FileInputStream(inputFileName), ArtifactFileUtils.getBasenameOnly(inputFileName))
        val tacParsingEnd = System.currentTimeMillis()
        loggerTimes.info { "TAC Parsing end ${(tacParsingEnd - tacParsingStart) / 1000}s" }
        return parsedTACCode
    }


    /**
     * Contains generic general-purpose transformation blocks for all TACs coming from rule-generators and targeted
     * for VC generation
     */
    companion object {

        /**
         * Runs toSimpleSimple conversion on [tacObject] and returning a DSA-d result.
         * The full list of transformations is within.
         * If [destructiveOptimizations] is enabled (=true), then the optimizations will not protect meta-keys required for
         * the construction of a CallTrace.
         */
        private fun toSimpleSimpleDSA(tacObject: CoreTACProgram): CoreTACProgram {
            val simpleSimpleObj = CoreTACProgram.Linear(tacObject)
                .map(CoreToCoreTransformer(ReportTypes.SIMPLE_SUMMARIES2) { it.simpleSummaries() })
                .map(CoreToCoreTransformer(ReportTypes.SIMPLE_SIMPLE) { TACSimpleSimple.toSimpleSimple(it) })
                .map(CoreToCoreTransformer(ReportTypes.LONG_COPY_HAVOC_INSTRUMENTATION) { TACSimpleSimple.longCopySimple(it) })
                .map(CoreToCoreTransformer(ReportTypes.INIT_VARS) { DefaultValueInitializer.initVarsAtRoot(it) })
                .map(CoreToCoreTransformer(ReportTypes.FOLD_SPLIT_STORES) { HeuristicalFolding.foldSplitStores(it) })
                .map(CoreToCoreTransformer(ReportTypes.REMOVE_UNUSED) {
                    removeUnusedAssignments(it, expensive = false, it.destructiveOptimizations)
                })
                .map(CoreToCoreTransformer(ReportTypes.SINK_OPTIMIZER) { SinkOptimizer.optimizeGraph(it) })
                .ref

            val protectedVars =
                runIf(!simpleSimpleObj.destructiveOptimizations) {
                    // Only variables that are defined once can be preserved! otherwise it's unsound.
                    val singleDefVars = run {
                        val counters = SimpleCounterMap<TACSymbol.Var>()
                        simpleSimpleObj.analysisCache.graph.commands.forEach { (_, cmd) ->
                                cmd.getLhs()?.let { counters.plusOne(it)}
                            }
                        counters.keys.filter { counters[it] == 1 }
                    }

                    simpleSimpleObj.symbolTable.tags.keys.filterToSet { v ->
                        // CVL now ensures most variables will only appear on lhs once, and in any case previous incarnation won't be read
                        v in singleDefVars && (TACMeta.CVL_VAR in v.meta || TACMeta.IS_CALLDATA in v.meta)
                    }
                    // (Yoav) I'm not that happy with this thing. It seems pretty fragile. Why not always maintain variable
                    // names when we can? i.e., when they have just one assignment and it dominates all their usages?
                    // or at least check for this domination here...
                }.orEmpty()

            val protectCallIndex =
                // I don't think this has any effect on running time... we can probably do this always.
                runIf(!simpleSimpleObj.destructiveOptimizations) {
                    simpleSimpleObj.symbolTable.tags.keys.filterToSet {
                        TACMeta.IS_CALLDATA in it.meta
                    }
                }.orEmpty()

            return CoreTACProgram.Linear(simpleSimpleObj)
                .map(CoreToCoreTransformer(ReportTypes.DSA) {
                    TACDSA.simplify(
                        prog = it,
                        protectedVars = protectedVars,
                        annotate = false,
                        protectCallIndex = protectCallIndex,
                        isErasable = default(it)::isErasable,
                        renaming = { v ->
                            v.namePrefix.takeIf { nm ->
                                TACMeta.STORAGE_KEY in v.meta ||
                                    TACMeta.CVL_VAR in v.meta ||
                                    TACKeyword.entries.any {
                                        it.isName { it == nm }
                                    } ||
                                    TACMeta.EVM_MEMORY in v.meta ||
                                    TACMeta.EVM_MEMORY_SCALARIZED in v.meta ||
                                    TACMeta.IS_CALLDATA in v.meta
                            }?.plus("!!") ?: TACDSA.TACDSARenaming.default.variablePrefix(v)
                        },
                    )
                })
                .map(CoreToCoreTransformer(ReportTypes.UNIQUEIFY_BOUND_VARIABLES) { UniqueifyBoundVars().transform(it) })
                .ref
        }

        /**
         * Runs the following on the TAC [tacObject]:
         * 1. toSimpleSimple conversion
         * 2. DSA
         * 3. Convert dsa to ssa for supporting diamonds.
         */
        fun toSimpleSimpleSSA(tacObject: CoreTACProgram): CoreTACProgram {
            return CoreTACProgram.Linear(toSimpleSimpleDSA(tacObject))
                .map(CoreToCoreTransformer(ReportTypes.DSA_TO_SSA) { DSAToSSA.rewrite(it) })
                .ref
        }

        /**
         * Performs last-phase optimizations.
         * Expects a fully SSAd TAC in [ssaTAC].
         * Should specify whether to run or not to run the heuristical folder in [enableHeuristicalFolding]
         */
        private fun lastOptimizations(ssaTAC: CoreTACProgram, enableHeuristicalFolding: Boolean): CoreTACProgram {
            val linear = CoreTACProgram.Linear(ssaTAC)
                .map(CoreToCoreTransformer(ReportTypes.INSERT_MAP_DEFINITION, InsertMapDefinitions::transform))
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.BYTEMAP_SCALARIZER) { code ->
                    if (code.destructiveOptimizations) {
                        ByteMapScalarizer.go(code)
                    } else {
                        code
                    }
                })
                .mapIfAllowed(CoreToCoreTransformer(ReportTypes.UNUSED_ASSIGNMENTS) {
                    optimizeAssignments(it, default(it)).let(BlockMerger::mergeBlocks)
                })
                .map(CoreToCoreTransformer(ReportTypes.INSERT_SCALAR_DEFINITION, InsertScalarDefinitions::transform))
            if (enableHeuristicalFolding) {
                // get rid of annotation commands, those generate false uses
                if (linear.ref.destructiveOptimizations) {
                    linear.map(CoreToCoreTransformer(ReportTypes.HEURISTICAL_FOLDING_REMOVE_ANNOTATIONS) {
                        HeuristicalFolding.removeAnnotations(it)
                    })
                }

                // proceed with heuristical folder
                linear.map(CoreToCoreTransformer(ReportTypes.HEURISTICAL_FOLDING_REWRITE) {
                    HeuristicalFolding.rewrite(it)
                })
            }
            if (Config.DivideByConstants.getOrNull() != null || Config.DivideNoRemainder.get()) {
                linear.map(
                    CoreToCoreTransformer(ReportTypes.DIVISION_UNDERAPPROXIMATION, DivisionUnderapproximation::doWork)
                )
            }
            linear.map(CoreToCoreTransformer(ReportTypes.DROP_BW_NOPS, DropBwNops::annotate))
            return linear.ref
        }

        /**
         * Performs 3 transformations on a TAC object [tacObject]:
         * 1. Conversion to simple-simple + maps SSA-ing (Result in [ReportTypes.PRELASTOPT_RULE])
         * 2. Perform last-phase optimizations + Skey annotation (Result in [ReportTypes.PRESOLVER_RULE])
         * 3. Run Skey detection.
         * No more passes should be run on the result.
         */
        fun toSimpleSimpleSSAFoldAndSkeyAnnotate(
            tacObject: CoreTACProgram,
            enableHeuristicalFolding: Boolean = true
        ): CoreTACProgram {
            val fullySSAd = toSimpleSimpleSSA(tacObject)

            oneStateInvariant(fullySSAd, ReportTypes.PRELASTOPT_RULE)
            ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                fullySSAd,
                ReportTypes.PRELASTOPT_RULE,
                StaticArtifactLocation.Outputs,
                DumpTime.AGNOSTIC
            )

            val preSolverProgram = lastOptimizations(fullySSAd, enableHeuristicalFolding)
                .let(AnnotateSkeyBifs::annotate)
                .let { QuantifierNormalizer(it).go() }

            // make sure there are no new/removed asserts in the code, as this will break multi_assert_check
            checkSameNumberOfAssertions(tacObject, preSolverProgram)
            return preSolverProgram
        }

        /**
         * Performs a very shallow check that we haven't added new asserts.
         * @param p1 the 'before' program
         * @param p2 the 'after' program
         *
         * Note:
         * If someone were so malicious to both remove and add asserts in equal amounts,
         * then this will not detect that.
         * This just makes sure multi_assert_check doesn't break.
         * Ideally we'd check for equal number of asserts, but the sink-optimizer can elide asserts so less are counted
         */
        private fun checkSameNumberOfAssertions(p1: CoreTACProgram, p2: CoreTACProgram) {
            fun countAsserts(p: CoreTACProgram): Int =
                p.analysisCache.graph.commands.count { it.cmd is TACCmd.Simple.AssertCmd }

            // will check only in CI
            val msg = { "Got more assertions " +
                "after processing the rule, this can break --multi_assert_check: ${countAsserts(p1)} vs ${countAsserts(p2)}" }
            if (Config.MultiAssertCheck.get()) {
                check(countAsserts(p1) >= countAsserts(p2), msg)
            } else if (ConfigType.CheckNoAddedAssertions.get()) {
                assert(countAsserts(p1) >= countAsserts(p2), msg)
            }
        }

        fun log(respectQuiet: Boolean = false, msg: () -> String) {
            if (Config.SpecFile.getOrNull() != null) {
                // in cvl mode, we don't want verifier to always print.
                logger.info(msg)
            } else {
                Logger.always(msg(), respectQuiet)
            }
            Logger.regression(msg) // for testing multi-assert, e.g. deepSanity
        }
    }
}
