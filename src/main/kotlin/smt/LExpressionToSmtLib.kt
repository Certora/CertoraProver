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

package smt

import config.Config
import datastructures.stdcollections.*
import log.*
import smt.axiomgenerators.AxiomContainer
import smt.axiomgenerators.ConstantComputer
import smt.axiomgenerators.fullinstantiation.ArrayGenerator.Companion.arrayGenerator
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizer
import smt.newufliatranslator.AxiomGenerator
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.SmtTheoryFeature
import smt.solverscript.functionsymbols.*
import smtlibutils.cmdprocessor.*
import smtlibutils.data.*
import smtlibutils.statistics.AxiomStatistics
import smtlibutils.statistics.QueryStatistics
import statistics.SDCollectorFactory
import statistics.data.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.lexpression.META_MODEL_TOI
import vc.data.lexpression.META_TOPLVL_CMD
import vc.data.state.TACValue
import vc.gen.LExpVC
import vc.gen.LeinoWP
import kotlin.time.TimeMark

@Suppress("Unused")
private val logger = Logger(LoggerTypes.VC_TO_SMT_CONVERTER)

/**
 * Starts with the verification condition (VC) as a collection of [LExpression]s.
 * [LExpression]s are passive and look like formulas, but some semantics are still in terms of our earlier IRs (e.g.
 *  types and the arithmetic operators).
 * This class processes the LExpression, adds axioms, and translates everything into a list of Smt [Cmd]s.
 *
 * See [output] for the individual steps.
 *
 * @param queryName name of the SMT query; should be a valid file name, so it can be used for dumping
 * @param lExpressionToSmtLibScene has the parameters for how the processing is to be done, e.g., which SMT logic to translate to,
 *       contains a [LExpressionFactory]
 * @param vc the input verification condition
 */
class LExpressionToSmtLib(
    private val queryName: String,
    private val lExpressionToSmtLibScene: LExpressionToSmtLibScene,
    private val vc: LExpVC,
    val diffStats: DifficultyStatsCollector? = null,
) {
    private val lxf: LExpressionFactory = lExpressionToSmtLibScene.lExpressionFactory
    private val globalGeneratorContainer: AxiomGeneratorContainer = lExpressionToSmtLibScene.axiomGeneratorContainer
    private val targetTheory: SmtTheory = lExpressionToSmtLibScene.targetTheory
    private val toSmtLibData = ToSmtLibDataWithScene(lExpressionToSmtLibScene)

    /**
     * Initialize the [VariableInliner]. We inline `OK` variables for blocks that only have a single predecessor and
     * contain no assertions. The former avoids formula blowup because a variable would be inlined in multiple places.
     */
    private val variableInliner = VariableInliner(lxf,
        vc.vcTacCommandGraph.blocks.filter { blk ->
            vc.vcTacCommandGraph.pred(blk).size == 1
                && blk.commands.none { it.cmd is TACCmd.Simple.AssertCmd }
        }.mapToSet {
            ToLExpression(LeinoWP.genOkIdentOf(it.id).asSym(), lxf, symbolTable = vc.tacProgram.symbolTable) // no action here this should already be in terms of interest
        }
    )

    /** True iff the quantifiers in this formula will be grounded */
    private var ground = Config.GroundQuantifiers.get()
    private fun cantGround() {
        ground = false
        globalGeneratorContainer.arrayGenerator?.cantGround()
    }

    private fun postProcessAndNormalize(e: LExpression, simplify: Boolean = true) =
        globalGeneratorContainer.postProcessTransform(e).let {
            if (simplify) {
                lExpressionToSmtLibScene.expNormalizer.normalizeRec(it)
            } else {
                lExpressionToSmtLibScene.expNormalizer.normalizeNoSimplificationRec(it)
            }
        }.also { resultExpr ->
            if (ground) {
                checkNot(resultExpr.contains { it is LExpression.QuantifiedExpr }) {
                    "quantifier grounding is turned on, yet a quantifier remains $resultExpr"
                }
            }
        }


    /**
     * Main worker function of this class -- its callers are thin wrappers for various purposes.
     *
     * Proceeds in a few steps:
     *  "Visit"
     *  - visit the whole [vc] in each [AxiomGenerator].
     *  - constant propagation.
     *  - call [AxiomGenerator.visit] on every sub-expression of every expression in the [vc].
     *  "First Freeze"
     *  - call [AxiomGenerator.beforeScriptFreeze] on all axiom generators.
     *  "Yield"
     *  - call [AxiomGenerator.yield] on all axiom generators to get all axioms into an [AxiomContainer]
     *  "Specialize BV vs IA"
     *  - [postProcessAndNormalize] on both assertions and on generated axioms,
     *  - also call [postProcessAndNormalize] on define-funs but without simplifications.
     *    (after this step, all function symbols and types have been transformed to have a direct SMTLib equivalent)
     *  "To SMTLib"
     *  - create [SmtFormulaCheckerQuery]
     */
    fun output(): SmtFormulaCheckerQuery {
        val vcStartTime = getCurrentTime()

        val constComputer = ConstantComputer(lxf, targetTheory)
        val quantifierRewriter = QuantifierRewriter(lxf)

        globalGeneratorContainer.visitVCMetaData(vc)

        /*
         * "Visit" step. preprocess all LExpression in VC, then show them to the axiom generators
         */

        val vcProcessed = vc.lExpressions.asSequence()
            /* normalize quantified formulas for groundings (roughly: skolem normal form) */
            .map {
                val (e, c) = quantifierRewriter.rewrite(it.lExpression)
                if (!c) {
                    cantGround()
                }
                LExpressionWithComment(e, it.comment)
            }
            /* variableInliner, proceeding in three passes to inline straight forward definitions */
            .mapNotNull { it.takeIf { !variableInliner.identifyElimination(it.lExpression) } }
            .oneTimeBuffer()
            .also { variableInliner.inlineEliminations() }
            .map { it.mapLExp { exp -> variableInliner.inline(exp) } }
            /* propagate constants */
            .map { it.mapLExp(constComputer::evalRec) }
            /* show each lexpression in the VC to the axiom generators */
            .onEach { globalGeneratorContainer.visitRec(it.lExpression) }
            .oneTimeBuffer()

        /* First freeze */

        globalGeneratorContainer.beforeScriptFreeze()

        /*
         * Yield step
         */
        val axiomContainer = AxiomContainer(lxf)
        globalGeneratorContainer.yield(axiomContainer)

        /*
         * Specialize to arithmetic theory (BV, LIA/NIA)
         */

        /* postProcess and normalize both assertions and generated axioms
         * (important to trigger this here, not leave as a sequence, since freezing happens in the next step and
         * `postProcess..` influences function symbols) */
        val vcAndAxiomsPostProcessed =
            (vcProcessed + axiomContainer.getAxiomSequence()).map { it.mapLExp(::postProcessAndNormalize) }.toList()

        /*
         * "to SMTLib" step
         */
        return generateSmtFormulaCheckerQuery(vcStartTime, vcAndAxiomsPostProcessed)
    }

    /**
     * "To SMT" step in in [output]'s pipeline. Move LExpressions to SMT, they have been prepared so this is straightforward.
     * In particular:
     *  - symbol's types correspond to their final smt type
     *  - only function symbols that have an SMT equivalent exist
     */
    private fun generateSmtFormulaCheckerQuery(
        vcStartTime: TimeMark,
        vcAndAxiomsPostProcessed: List<LExpressionWithComment>,
    ): SmtFormulaCheckerQuery {
        // only call when we know which funs need to be defined.. thus after solverScript freezing right?...
        // but it may add a function symbol.. thus right before solver script freezing... hm...
        val defineFuns =
            (globalGeneratorContainer.yieldDefineFuns() + lxf.getDefFuncs().map { it.def }).map {
                lExpressionToSmtLibScene.expNormalizer.normalizeDefineFun(it) { e ->
                    globalGeneratorContainer.postProcessTransform(e)
                }
            }

        val termsOfInterest = vc.termsOfInterest
            ?.map(globalGeneratorContainer::transformTermsOfInterest)
            ?.map { lExp -> postProcessAndNormalize(lExp, simplify = false) }

        lxf.freezeConstants()
        lxf.freezeUserDefinedFunctions()
        lxf.freezeAxiomatized()

        val vcGenTime = vcStartTime.elapsedNow()

        lxf.freezeArrays()
        val smtStartTime = getCurrentTime()
        val scriptWithSymbolTable = SmtScript()

        val smtDeclareDatatypes: List<Cmd.DeclareDatatypes> =
            lxf.getDatatypeDeclarations().map {
                it.transform { lExpressionToSmtLibScene.expNormalizer.normalizeFunctionSymbol(it) }
                    .toSmtLib(toSmtLibData, scriptWithSymbolTable)
            }
        val sortsToDeclare = mutableSetOf<Tag.UserDefined.UninterpretedSort>()

        defineFuns
            .flatMap { df -> df.args.map { it.tag } + df.ret }
            .filterIsInstance<Tag.UserDefined.UninterpretedSort>()
            .let { sortsToDeclare.addAll(it) }

        val definedSymbolNames = (defineFuns.map { it.id.name } + smtDeclareDatatypes.flatMap { it.funDecNames }).toSet()

        val symbolsToDeclare = vcAndAxiomsPostProcessed.asSequence()
            .flatMap { it.lExpression.subs }
            .plus(defineFuns.flatMap { f -> f.def.subs.filter { it !in f.args } })
            .onEach { lexp ->
                when (lexp) {
                    is LExpression.Identifier -> (lexp.tag as? Tag.UserDefined.UninterpretedSort)?.let {
                        sortsToDeclare.add(it)
                    }
                    is LExpression.ApplyExpr -> if (lexp.f !is NonSMTInterpretedFunctionSymbol) {
                        lexp.f.signature.allOccurringSorts.filterIsInstance<Tag.UserDefined.UninterpretedSort>().let {
                            sortsToDeclare.addAll(it)
                        }
                    }
                    else -> Unit
                }
            }
            .mapNotNull {
                when (it) {
                    is LExpression.ApplyExpr -> it.f as? UninterpretedFunctionSymbol
                    is LExpression.Identifier -> ConstantSymbol(it.id, it.tag)
                    else -> null
                }
            }
            .filter { globalGeneratorContainer.declarationFilter(it) }
            .filterNot { fs -> fs.name in definedSymbolNames }
            .filterNot { fs -> fs.signature.resultSort is Tag.UserDefined.Struct }
            .distinct()
            .sortedBy { it.name }
            .toList()

        // Declare sorts
        sortsToDeclare
            .forEach { scriptWithSymbolTable.declareSort(it.name, 0) }

        // Declare symbols
        symbolsToDeclare.forEach { declareInScript(it.name, it.signature, toSmtLibData, scriptWithSymbolTable) }

        // Create define-funs
        val smtDefineFuns = defineFuns.map {
            it.toSmtLib(toSmtLibData, scriptWithSymbolTable)
        }

        /*
         * sorting by default, purposes
         *  - sanity check (catching problems before the solver does)
         *  - when we further process the formula, we need to do it anyway, usually
         *   (unsat core handling most likely uses it)
         */
        val sorter = Sorter(scriptWithSymbolTable)

        val baseExprs = if (Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE || Config.TimeoutCores.get()) {
            // If UNSAT core enumeration is required, we link the lExpressions directly to the SMT expressions.
            // This way, we keep the back mapping allowing us to go from SMT back to TAC.
            val lAndSmtExpPairs = vcAndAxiomsPostProcessed.asSequence().map {
                it to sorter.sort(it.toSMTLib(toSmtLibData, scriptWithSymbolTable))
            }
            lAndSmtExpPairs.map {
                if (it.first.lExpression is LExpression.ApplyExpr &&
                    it.first.lExpression.meta.containsKey(META_TOPLVL_CMD)) {
                    it.second.withLExp(it.first.lExpression as LExpression.ApplyExpr)
                } else {
                    it.second
                }
            }
        } else {
            // Otherwise, we simply drop the lExpressions and leave the SMT expressions only.
            vcAndAxiomsPostProcessed.asSequence().map {
                sorter.sort(it.toSMTLib(toSmtLibData, scriptWithSymbolTable))
            }
        }

        // assemble full formula
        // Result of https://certora.atlassian.net/browse/CERT-7714
        // The UnsatCore code in [CoreHelper] expects that the formula assertions are unique so that it can build
        // a bijection between assertions and identifiers. Dropping duplicates doesn't hurt anybody.
        val formula = baseExprs.distinct().toList()

        val coreHelper =
            if (Config.GenerateUnsatCores.get() ||
                Config.GetDifficulties.get() ||
                Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE
            ) {
                // NB timeout cores create their own helper later, so no need to create it here (they do their own
                // assertFormula call..)
                // TODO (CERT-8094): We've just materialized to formula to a list. Should keep this
                //  as a stream (sequence is probably better these days).
                //  AN: seems to me that CoreHelper could take a sequence just as well, we might make initialization of
                //      its fields lazy ..
                CoreHelper(formula, scriptWithSymbolTable)
            } else {
                null
            }

        val finalTheory = getFinalTheory(
            vcAndAxiomsPostProcessed,
            smtDeclareDatatypes,
            targetTheory
        )
        val smtLogic = Logic.fromString(finalTheory.keyword)

        val smtFormula = SmtFormula(
            logic = smtLogic,
            symbolTable = scriptWithSymbolTable.symbolTable,
            defineFuns = smtDefineFuns,
            declareDatatypes = smtDeclareDatatypes,
            conjunction = formula
        )

        val smtGenTime = smtStartTime.elapsedNow()

        SDCollectorFactory.collector().run {
            lExpressionToSmtLibScene.axiomGeneratorContainer.getStatsRecorders(queryName).forEach(::collectFeature)
        }

        reportAxiomGenerationStats()

        return SmtFormulaCheckerQuery(
            SmtFormulaCheckerQueryInfo(
                name = queryName,
                prettifyCounterExample = Config.prettifyCEX.get() != PrettifyCEXEnum.NONE,
                getUnsatCore = Config.GenerateUnsatCores.get() || Config.CoverageInfoMode.get() != CoverageInfoEnum.NONE,
                getDifficulties = Config.GetDifficulties.get(),
                statistics = QueryStatistics(
                    logic = smtLogic.toString(),
                    axiomStatistics = AxiomStatistics(smtGenerationTime = smtGenTime, vcGenerationTime = vcGenTime),
                )
            ),
            smtFormula = smtFormula,
            termsOfInterest = computeTermsOfInterest(scriptWithSymbolTable, termsOfInterest),
            coreHelper = coreHelper,
        )
    }

    private fun reportAxiomGenerationStats() {
            diffStats?.register(
                SingleDifficultyStats.LExpToSmtLibstats(
                    vc.tacProgramMetadata.ruleAndSplitId,
                    queryName,
                    arrayGeneratorStats =
                        globalGeneratorContainer.stats.find { it is ArrayGeneratorStats } as? ArrayGeneratorStats,
                    bwAxiomStats =
                        globalGeneratorContainer.stats.find { it is BitwiseAxiomGeneratorStats } as? BitwiseAxiomGeneratorStats,
                )
            )
    }

    /**
     * Computes a (possibly) more restricted theory than our original target theory (determined by configuration) if
     * we don't use certain features.
     * This might help certain solvers sometimes.
     */
    private fun getFinalTheory(
        assertions: List<LExpressionWithComment>,
        smtDeclareDatatypes: List<Cmd.DeclareDatatypes>,
        originalTheory: SmtTheory
    ): SmtTheory {
        // note we're ignoring the SmtExp Axioms (typically coming from learned lemma sharing) for the purposes here
        val seq = assertions.asSequence().subs

        var areQuantifiersUsed = false
        var usesNoMulOverflowPrimitive = false
        var usesUninterpretedFunctions = false
        seq.forEach {
            if (it is LExpression.QuantifiedExpr) {
                areQuantifiersUsed = true
            }
            if (it is LExpression.ApplyExpr && it.f is TheoryFunctionSymbol.BV.OverUnderflowCheck) {
                usesNoMulOverflowPrimitive = true
            }
            if (it is LExpression.ApplyExpr && it.f is UninterpretedFunctionSymbol && it.f !is ConstantSymbol) {
                usesUninterpretedFunctions = true
            }
        }

        if (usesNoMulOverflowPrimitive) {
            /** using a z3-specific (and non-smtlib) function symbol --> use ALL logic */
            return SmtTheory.ALL
        }

        var usedTheoryFeatures = originalTheory.theoryFeatures
        if (!usesUninterpretedFunctions) {
            /** no uninterpreted functions in the formula */
            usedTheoryFeatures -= SmtTheoryFeature.UninterpretedFunctions
        }
        if (areQuantifiersUsed) {
            /** quantifiers in the formula */
            usedTheoryFeatures += SmtTheoryFeature.Quantifiers
        } else {
            /** no quantifiers in the formula (e.g. due to grounding) */
            usedTheoryFeatures -= SmtTheoryFeature.Quantifiers
        }
        // add or remove the DT logic feature (usually this is predicted correctly, but detecting it here makes us less
        // reliable on the prediction, which might shorten data flow paths in the future)
        if (smtDeclareDatatypes.isEmpty()) {
            usedTheoryFeatures -= SmtTheoryFeature.DataTypes
        } else {
            usedTheoryFeatures += SmtTheoryFeature.DataTypes
        }
        return SmtTheory.fromFeatures(usedTheoryFeatures)
    }


    /**
     * We do a (get-value ..) call to the solver to get the parts of the model that we're interested in.
     * These parts are given as a list of terms, which is either passed in the VC ([vc]), or a default is computed here.
     *
     * The default behavior uses the declarations from the [SmtScript]'s symbol table and a simple filter that only
     * keeps scalar constants (i.e. no uninterpreted functions or arrays).
     */
    private fun computeTermsOfInterest(smtFactory: SmtScript, termsOfInterest: List<LExpression>?): TermsOfInterest {
        val smtExpToTacExpr = mutableMapOf<SmtExp, TACExpr>()
        return if (Config.SkipCounterExamples.get()) {
            EmptyTermsOfInterest
        } else if (termsOfInterest != null) {
            val sorter = Sorter(smtFactory)

            val toisPostProcessed = termsOfInterest
                .mapNotNullToSet { lExp ->
                    val toiPostProcessed = lExp.toSMTLib(toSmtLibData, smtFactory)

                    try {
                        lExp.meta[META_MODEL_TOI]?.let { tacExpr: TACExpr ->
                            val smtExp = sorter.sort(toiPostProcessed)
                            val prev = smtExpToTacExpr.put(smtExp, tacExpr)
                            if (prev != null) {
                                logger.error {
                                    "collision in setting up backtranslation mapping; " +
                                        "updated value of \"$smtExp\" from \"$prev\" to \"$tacExpr\""
                                }
                            }
                        }
                        toiPostProcessed
                    } catch (e: SmtSortException) {
                        // the sorting may fail because an expression from the TAC VC may not have made it into the formula
                        //  due to the optimizations done during the `output()` procedure (then it does not show up in the
                        //  SMT symbol table, and the sorting crashes)
                        logger.info {
                            "got sorting exception for a term of interest, this can happen due to " +
                                "optimizations in LExpressionToSmtLib; exception: $e"
                        }
                        null
                    }
                }
            TacExpTermsOfInterest(toisPostProcessed, emptyMap()) { smtExp -> smtExpToTacExpr[smtExp] }
        } else {
            // default case -- when no terms of interest are passed in the VC, we just take every symbol of scalar type
            // that occurs in the formula
            // (we take only scalars because UFs and arrays have very large models, sometimes)
            TermsOfInterest(
                smtFactory.symbolTable.getAllDeclaredFuns()
                    .filter { it.param_sorts.isEmpty() && !it.res_sort.isArraySort() && !it.isDatatypeConstructor }
                    .mapToSet {
                        check(it.functionSymbol is SmtUnintpFunctionSymbol)
                        smtFactory.qualIdentifier(it.functionSymbol.name, null, it.functionSymbol.rank.resultSort)
                    }
            )
        }
    }

    companion object {

        fun declareInScript(
            name: String,
            signature: FunctionSignature,
            toSmtLibData: ToSmtLibData,
            scriptWithSymbolTable: SmtScript,
        ) {
            if (signature.arity == 0) {
                scriptWithSymbolTable.declareConst(
                    name,
                    LExpressionFactory.tagToSort(signature.resultSort, toSmtLibData)
                )
            } else {
                scriptWithSymbolTable.declareFun(
                    name,
                    (signature as IFixedFunctionSignatures).paramSorts.map {
                        LExpressionFactory.tagToSort(it, toSmtLibData)
                    },
                    LExpressionFactory.tagToSort(signature.resultSort, toSmtLibData)
                )
            }
        }
    }
}

/** Variant of [TermsOfInterest] that provides a back translation of [SmtExp] to [TACExpr]. */
class TacExpTermsOfInterest(
    terms: Set<SmtExp>,
    val optimizedOutModelValues: Map<TACExpr, TACValue>,
    val backTranslation: (SmtExp) -> TACExpr?,
) : TermsOfInterest(terms) {
    override fun copy(newTerms: Set<SmtExp>): TermsOfInterest =
        TacExpTermsOfInterest(newTerms, this.optimizedOutModelValues, this.backTranslation)
}

/** Class for mapping [SmtExp] definitions back to [LExpression]s */
class SmtExpWithLExp(smtExp: SmtExp, comment: String?, val lexp: LExpression) : SmtExpWithComment(smtExp, comment) {
    override fun mapExp(mapper: (SmtExp) -> SmtExp): HasSmtExp = SmtExpWithLExp(mapper(exp), comment, lexp)
}

/** Method to add LExpression to HasSmtExp and put it in the SmtExpWithLExp class */
fun HasSmtExp.withLExp(lExpression: LExpression.ApplyExpr): HasSmtExp {
    return when (this) {
        is SmtExp -> SmtExpWithLExp(exp, null, lExpression)
        is SmtExpWithComment -> SmtExpWithLExp(exp, comment, lExpression)
        else -> {
            error("Unexpected type for HasSmtExp interface.")
        }
    }
}

data class LExpressionToSmtLibScene(
    val lExpressionFactory: LExpressionFactory,
    val axiomGeneratorContainer: AxiomGeneratorContainer,
    val targetTheory: SmtTheory,
    val expNormalizer: ExpNormalizer,
)

