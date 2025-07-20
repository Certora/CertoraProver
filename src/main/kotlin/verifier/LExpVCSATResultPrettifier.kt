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

import analysis.ip.*
import config.Config
import datastructures.stdcollections.*
import log.*
import report.calltrace.CallTrace
import smt.MultipleCEXStrategyEnum
import smt.PrettifyCEXEnum
import smtlibutils.cmdprocessor.*
import smtlibutils.data.*
import spec.CVLTestGenerator
import spec.cvlast.CVLType
import utils.*
import vc.data.*
import vc.gen.LExpVC
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private val logger = Logger(LoggerTypes.SOLVERS)

class LExpVCSATResultPrettifier(
    private val config: LExpVcPostprocessingConfig,
    private val vc: LExpVC
) {
    private val statistics = object {
        var numOfPrettifications: Int = 0
        var totalTime: Duration = Duration.ZERO
    }

    fun getHashingBoundConstraintsToPrettify() =
        vc.tacProgram.ltacStream().mapNotNull {
            it.cmd.meta[TACMeta.HASHING_BOUND_ASSUME] ?: it.cmd.meta[TACMeta.HASHING_BOUND_ASSERT]
        }.toList().filterIsInstance<TACSymbol.Var>()

    /**
     * Identifies the terms that should be prettified. We try
     * to identify symbols that are actually shown in our report (i.e. are used in [CallTrace]).
     * This method collects all of these symbols.
     * Comments indicate which parts of [CallTrace] the added symbols correspond to (quite unstable due to possible
     * changes in CallTrace -- but might give an idea..).
     *
     * Background: This should make the postprocessing both faster and yield better results since more "looseness" in
     * the formula gives a higher chance of finding pretty values.
     */
    fun getTermsToPrettify(): Collection<TACSymbol.Var> {
        val toPrettify = mutableSetOf<TACSymbol.Var>()
        vc.tacProgramMetadata.cvlVars.forEach { declSymName ->
            vc.tacProgram.symbolTable.nameToSymbol[declSymName]?.let { toPrettify.add(it) }
        }
        CVLTestGenerator.getCVLVars(vc.tacProgram).forEach { toPrettify.add(it) }

        for (currBlock in vc.tacProgram.code.keys) {
            val block = vc.tacProgram.code[currBlock]!!
            block.forEach { stmt ->
                when (stmt) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        val callDataSymbols = (stmt.getFreeVarsOfRhs() + stmt.lhs)
                            .filter { it.meta.containsKey(TACMeta.IS_CALLDATA) }
                        callDataSymbols
                            .forEach { paramOrBase ->
                                val symbol = if (paramOrBase.tag.isPrimitive()) {
                                    paramOrBase
                                } else if (stmt.rhs is TACExpr.Select && (stmt.rhs.base as? TACExpr.Sym) == paramOrBase.asSym()) {
                                    stmt.lhs
                                } else {
                                    null
                                }

                                if (symbol != null) { toPrettify.add(symbol) }
                            }
                        if ((stmt.lhs.meta.containsKey(TACMeta.IS_RETURNDATA) ||
                                stmt.meta.containsKey(TACMeta.RETURN_WRITE)) &&
                            stmt.rhs is TACExpr.Store && stmt.rhs.value is TACExpr.Sym.Var
                        ) {
                            toPrettify.add(stmt.rhs.value.s)
                        }
                        if (stmt.lhs.meta.containsKey(TACMeta.RETURN_WRITE)) {
                            toPrettify.add(stmt.lhs)
                        }
                    }
                    is TACCmd.Simple.AnnotationCmd -> {
                        val (meta, value) = stmt.annot
                        when (meta) {
                            INTERNAL_FUNC_START -> { // CallTrace:1335
                                val internalFuncStartAnnot = value as InternalFuncStartAnnotation
                                internalFuncStartAnnot.args.forEach { arg ->
                                    if (arg.sort == InternalArgSort.SCALAR && arg.s is TACSymbol.Var) {
                                        toPrettify.add(arg.s)
                                    }
                                }
                            }
                            INTERNAL_FUNC_EXIT -> { // CallTrace:1379
                                val internalFuncExitAnnot = value as InternalFuncExitAnnotation
                                internalFuncExitAnnot.rets.forEach {
                                    toPrettify.add(it.s)
                                }
                            }
                            TACMeta.SNIPPET -> { // For solana
                                when(val snippet = stmt.annot.v) {
                                    is SnippetCmd.CvlrSnippetCmd.CexPrintValues -> {
                                        toPrettify.addAll(snippet.symbols)
                                    }
                                    is SnippetCmd.SolanaSnippetCmd.Assert -> {
                                        toPrettify.add(snippet.symbol)
                                    }
                                    is SnippetCmd.SolanaSnippetCmd.ExternalCall -> {
                                        toPrettify.addAll(snippet.symbols)
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return toPrettify
    }

    /**
     * Variables whose value minimisation will be prioritized by the model post-processing.
     * The variables are either chosen by the user via `config.postProcessVariables` or
     * we collect and prioritise all variables in all assertions in the rule. Note that
     * there might be more assertions in the rule and in that case it would be probably better to collect just
     * variables from the violated assertion(s).
     * //TODO: collect vars just from the violated assert(s). Postponing for now since we plan further
     * architecture changes. In particular, we want to decouple the rule solving
     * and cex prettification (i.e. first report the cex to the user via the HTML interface, and then prettify
     * and improve the cex if possible (and update the HTML)).
     */
    fun getPrioritisedTerms(termsToPrettify: Collection<TACSymbol.Var>): List<TACSymbol.Var> {
        return config.prettifyCEXVariables.ifEmpty {
                vc.tacProgramMetadata.cvlVarsInAsserts
            }.mapNotNull { name ->
                termsToPrettify.find { term -> term.meta[TACMeta.CVL_DISPLAY_NAME] == name }
            }
    }

    /**
     * The total timeout for post-processing is computed using very rough estimates based on the time `T` required for
     * the original check that produced the counterexample. For both diversification and prettification we expect each
     * check call to be much cheaper than this original check, but both usually do many incremental checks in sequence.
     * If this estimate is still too low, the timeout it can be further increased using `config.postProcessCEXTimeout`.
     *
     * For diversification, we estimate `2*T` and `4*T` for basic and advanced, respectively.
     * For prettification, we estimate `2*T`, `2*T` and `10*T` for basic, joint and extensive, respectively.
     *
     * Additionally, we limit the per-check timeout for every single query to `1.25*T`.
     *
     * @return The pair of the total time limit for postprocessing, and the time limit for a single check-sat call
     *         within this process.
     */
    fun getTimeouts(result: SmtFormulaCheckerResult.Single.Simple): Pair<Duration, Duration> {
        check(result.satResult is SatResult.SAT) { "we can only postprocess a SAT result here" }
        val initialCheckTime = result.checkSatRuntime ?: error("a sat result should always have a runtime")

        val diversifyTimeout = when (config.multipleCEX) {
            MultipleCEXStrategyEnum.NONE -> Duration.ZERO
            MultipleCEXStrategyEnum.BASIC -> initialCheckTime.times(2)
            MultipleCEXStrategyEnum.ADVANCED -> initialCheckTime.times(4)
        }
        val prettifyTimeout = when (config.prettifyCEX) {
            PrettifyCEXEnum.NONE -> Duration.ZERO
            PrettifyCEXEnum.BASIC -> initialCheckTime.times(2)
            PrettifyCEXEnum.JOINT -> initialCheckTime.times(2)
            PrettifyCEXEnum.EXTENSIVE -> initialCheckTime.times(10)
        }

        return maxOf(config.postProcessCEXTimeout, diversifyTimeout + prettifyTimeout) to
            maxOf(config.postProcessCEXSingleCheckTimeout, initialCheckTime + 3.seconds, initialCheckTime.times(5).div(4))
    }

    suspend fun getResultToPrettify(
        checker: SimpleFormulaChecker,
        query: SmtFormulaCheckerQuery,
        newTimeout: Duration
    ): Pair<SimpleFormulaChecker, SmtFormulaCheckerResult.Single.Simple>? {
        check(checker.solverInstanceInfo.solverConfig.incremental) { "We can only prettify with an incremental solver" }
        // if we don't diversify and don't do extensive prettification, it's probably not that bad
        if (
            config.multipleCEX == MultipleCEXStrategyEnum.NONE &&
            config.prettifyCEX != PrettifyCEXEnum.EXTENSIVE
            ) {
            return null
        }
        checker.solverInstanceInfo.solverConfig.solverInfo.getCmdToChangeTimelimit(newTimeout)?.let {
            checker.runOnQueryProcessor { qp -> qp.process(Cmd.Custom(it, false)) }
            return null
        }
        if (checker.solverInstanceInfo.solverConfig.timelimit.let { it == null || newTimeout <= it }) {
            return null
        }
        val newChecker = SimpleFormulaChecker.singleCheckerSpawnerFromSolverInfoOrNull(
            checker.solverInstanceInfo.copy1(timelimit = newTimeout),
            script = SmtScript(query.smtFormula.symbolTable.copy())
        )?.invoke() as SimpleFormulaChecker
        return try {
            // we need to recheck the query here to make sure there is a model in the solver - a quite expensive step
            val newResult = newChecker.check(query) as SmtFormulaCheckerResult.Single.Simple
            newChecker to newResult
        } catch (e: Exception) {
            logger.warn { "failed to recreate SmtFormulaChecker, $e. Using the original instance instead." }
            null
        }
    }

    suspend fun prettifyCounterExample(
        cex: SmtFormulaCheckerResult.Single.Simple,
        query: SmtFormulaCheckerQuery,
        checker: SimpleFormulaChecker,
        context: SmtQueryProcessorContext,
        tacSymbolsToPrettify: Collection<TACSymbol.Var>,
        prioritisedTacSymbolsToPrettify: List<TACSymbol.Var>,
        postProcessTimeout: Duration,
        jointPrettification: Boolean,
    ): SmtFormulaCheckerResult.Single.Simple {
        if (cex.satResult !is SatResult.SAT || tacSymbolsToPrettify.isEmpty()) {
            return cex
        }
        statistics.numOfPrettifications += 1

        check(cex.getValuesResult != null)
        { "expecting a getValuesResult in the SAT case, got null" }

        fun smtExpsForTacSyms(syms: Collection<TACSymbol.Var>): List<SmtExp> {
            val symStrings = syms.map { it.toSMTRep() }
            return cex.getValuesResult!!.keys.filter { smtExpWithValue ->
                smtExpWithValue is SmtExp.QualIdentifier &&
                    smtExpWithValue.id is Identifier.Simple &&
                    smtExpWithValue.id.sym in symStrings
            }
        }

        val (addressesToPrettifyTac, otherTermsToPrettifyTac) = tacSymbolsToPrettify.partition { sym ->
            sym.meta.containsKey(TACMeta.CVL_VAR) && sym.meta[TACMeta.CVL_TYPE] == CVLType.PureCVLType.Primitive.AccountIdentifier
        }

        val (ppModelRes: PostProcessModel.PostProcessModelResult?, elapsed) = measureTimedValue {
            cex.getValuesResult?.let { oldModel ->
                if (oldModel.isEmpty()) {
                    logger.info { "model is empty; nothing to prettify" }
                    PostProcessModel.PostProcessModelResult(oldModel, "model is empty; nothing to prettify")
                } else {
                    checker.runOnQueryProcessor { queryProcessor ->
                        PostProcessModel.prettify(
                            queryProcessor,
                            context,
                            oldModel.keys.toList(),
                            smtExpsForTacSyms(addressesToPrettifyTac),
                            smtExpsForTacSyms(otherTermsToPrettifyTac),
                            smtExpsForTacSyms(prioritisedTacSymbolsToPrettify),
                            query.symbolTable,
                            postProcessTimeout,
                            jointPrettification,
                            Config.PrettifyCEXSmallBarriers.get()
                        )
                    }
                }
            }
        }
        statistics.totalTime += elapsed
        val newCounterExample = cex.copy(
            getValuesResult = ppModelRes?.newModel ?: cex.getValuesResult,
            postProcessModelResult = ppModelRes
        )
        Logger.regression { "prettifyModel finished." }

        return newCounterExample
    }

    fun getStatistics(): PrettifierStatistics = PrettifierStatistics(
        numOfPrettifications = statistics.numOfPrettifications,
        totalTime = statistics.totalTime,
    )

}
