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

import analysis.PathCondition
import config.Config
import datastructures.stdcollections.*
import kotlinx.coroutines.flow.*
import log.Logger
import log.LoggerTypes
import smtlibutils.cmdprocessor.*
import smtlibutils.data.*
import smtlibutils.data.FactorySmtScript.eq
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.gen.LeinoWP
import verifier.DiversifierStatistics
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.measureTimedValue

private val logger = Logger(LoggerTypes.SMT_CEXDIVERSIFIER)

/**
 * This class attempts to produce a diverse set of counterexamples based on a single counterexample and the core TAC
 * program. Diversity is generated using several heuristics as set in [Config.MultipleCEXStrategy], see
 * [heuristics] for more information.
 * The [originalModel] is mostly used to seed (some of) the heuristics and provide the counterexamples with proper
 * information about the query. In particular, it is not (necessarily) used as one of the resulting counterexamples:
 * depending on the heuristics new abstractions variable may be introduced which requires a re-check, and
 * counterexamples may also be postprocessed using a [ResultPostprocessor].
 * The [checker] is assumed to be in incremental mode and the given result should be a SAT result.
 *
 * See https://certora.atlassian.net/l/cp/h6zpJoQM for more background and details about diversification.
 *
 * @param checker The checker used for the incremental diversification (and possibly postprocessing)
 * @param originalModel The original counterexamples, used for seeding heuristics
 * @param tacProgram The TAC program, used for seeding heuristics
 */
class CounterExampleDiversifier private constructor(
    private val checker: SimpleFormulaChecker,
    private val originalModel: Map<SmtExp,SmtExp>,
    originalQuery: SmtFormulaCheckerQuery,
    tacProgram: CoreTACProgram
) {
    private val statistics = object {
        var targetCountBlocks: Int? = null
        var targetCountUserAsserts: Int? = null
        var targetCountAutoAsserts: Int? = null
        var targetCountZeroSplit: Int? = null
        var cexCount: Int = 0
        var checkCount: Int = 0
        var totalTimeTargetGeneration: Duration = Duration.ZERO
        var totalTimeChecks: Duration = Duration.ZERO
        var totalTimeTargetEval: Duration = Duration.ZERO
    }

    fun getStatistics() =
        DiversifierStatistics(
            statistics.targetCountBlocks,
            statistics.targetCountUserAsserts,
            statistics.targetCountAutoAsserts,
            statistics.targetCountZeroSplit,
            statistics.checkCount,
            statistics.cexCount,
            statistics.totalTimeTargetGeneration,
            statistics.totalTimeChecks,
            statistics.totalTimeTargetEval
        )

    /** The status of a check. */
    sealed class Status {
        /** Has not been checked yet */
        object NotCheckedYet : Status()

        /** A counterexample was found */
        data class HasCounterExample(val cex: SmtFormulaCheckerResult.Single.Simple) : Status()

        /** No counterexample exists */
        object HasNoCounterExample : Status()

        /** Not sure, check timed out or similar */
        data class MayHaveCounterExample(val checkResult: SmtFormulaCheckerResult.Single.Simple) : Status()
    }

    companion object {
        /** Get the variable name for the reachability variable of a block [bi]. */
        private fun BI2ReachVarName(bi: NBId): String = LeinoWP.reachabilityIdentNameOf(bi)
        /** Convert a [NBId] to its reachability variable as an [SmtExp] */
        private fun BI2ReachVar(bi: NBId): SmtExp = FactorySmtScript.id(BI2ReachVarName(bi), Sort.BoolSort)

        /**
         * Convert a [TACCmd.Simple.AssertCmd] to the [SmtExp] that represents the condition (actually only a symbol)
         * that is asserted.
         */
        private fun Assert2Exp(cmd: TACCmd.Simple.AssertCmd): SmtExp =
            FactorySmtScript.id(cmd.o.toSMTRep(), Sort.BoolSort)

        /** Get the variable name for a `ValidOnCEX` variable for a given block [bi]. */
        private fun BI2ValidOnCEXName(bi: NBId): String = "ValidOnCEX${bi}"

        /**
         * Get the [SmtExp] for a `ValidOnCEX` variable for a given block [bi]. `ValidOnCEX_<block>` is true iff the
         * block is on the counterexample path and all assertions on the path up to (and including) this block are
         * satisfied.
         */
        private fun BI2ValidOnCEX(bi: NBId): SmtExp =
            FactorySmtScript.id(BI2ValidOnCEXName(bi), Sort.BoolSort)

        /**
         * Produce all partial sums of a list, that is a list of lists `[l1, l2, ...]` where `lk` contains the first
         * `k` elements of the input list.
         */
        private fun <T> Iterable<T>.partialSums(): List<List<T>> =
            runningFold(listOf<T>()) { acc, v -> acc + listOf(v) }.drop(1)

        /**
         * Check whether a given assertion is a user-defined assertion. This is the case if it is annotated with a CVL
         * location.
         */
        private fun isUserAssertion(cmd: TACCmd.Simple.AssertCmd): Boolean =
            cmd.meta.contains(TACMeta.CVL_USER_DEFINED_ASSERT)

        /**
         * State that some predecessor of [blk] is valid (in the counterexample and all assertions satisfied). If [blk]
         * has no predecessors return `True`.
         */
        private fun somePredIsValid(blk: NBId, ctp: CoreTACProgram): SmtExp {
            return FactorySmtScript {
                val preds = ctp.analysisCache.graph.pred(blk)
                if (preds.isEmpty()) {
                    True
                } else {
                    or(preds.map { BI2ValidOnCEX(it) })
                }
            }
        }

        /**
         * Construct an [SmtExp] that states that [blk] is within a counterexample in [ctp], i.e. [blk] is reachable and
         * all assertions up to [blk] are satisfied. [blk] may either be satisfied as well, or the first violated block.
         */
        private fun isInCEX(blk: NBId, ctp: CoreTACProgram): SmtExp =
            FactorySmtScript { somePredIsValid(blk, ctp) and BI2ReachVar(blk) }

        /**
         * This can be used to override the default behavior of [getTargets]. This is useful for unit testing and
         * should not be used otherwise.
         */
        var targetGenerator: (
            CounterExampleDiversifier.(
                ctp: CoreTACProgram,
                original: Map<SmtExp,SmtExp>
            ) -> List<SmtExp>
        )? = null

        operator suspend fun invoke(
            checker: SimpleFormulaChecker,
            originalModel: Map<SmtExp,SmtExp>,
            originalQuery: SmtFormulaCheckerQuery,
            tacProgram: CoreTACProgram
        ) = CounterExampleDiversifier(checker, originalModel, originalQuery, tacProgram).apply {
            // Adds definitions for [targetAbstractions] to the checker
            solverContext.push()
            checker.runOnQueryProcessor { qp ->
                qp.script.run {
                    tacProgram.code.keys.forEach { blk ->
                        qp.declareFun(Cmd.DeclareFun(BI2ValidOnCEXName(blk), listOf(), Sort.BoolSort))
                    }
                    tacProgram.code.forEachEntryInline { (blk, cmds) ->
                        val somePredValid = if (blk == tacProgram.entryBlockId) {
                            True
                        } else {
                            tacProgram.analysisCache.graph.pred(blk).let { preds ->
                                or(preds.map { BI2ValidOnCEX(it) })
                            }
                        }
                        val allAssertsValid = and(cmds.filterIsInstance<TACCmd.Simple.AssertCmd>().map {
                            Assert2Exp(it)
                        })
                        qp.assert(
                            Cmd.Assert(
                                BI2ValidOnCEX(blk) eq
                                    and(somePredValid, BI2ReachVar(blk), allAssertsValid)
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * For every block in [ctp] add a target that uses this block (a predecessor is valid and the block is
     * reachable) and a target that does not use this block (a predecessor is valid but the block is not reachable).
     */
    fun blockReachableTargets(ctp: CoreTACProgram) =
        ctp.code.keys.flatMap { blk ->
            listOf(
                isInCEX(blk, ctp),
                FactorySmtScript { somePredIsValid(blk, ctp) and not(BI2ReachVar(blk)) }
            )
        }.also { statistics.targetCountBlocks = it.size }

    /**
     * For every user assertion in any block add a target that violates that assertion (but satisfies all other
     * assertions). We do this by forcing the block to be on the counterexample path, the assertion condition to
     * false and all other assertion conditions in the same block before the violated assertion to true.
     */
    fun userAssertionTargets(ctp: CoreTACProgram): List<SmtExp> {
            return ctp.code.flatMap { (blk, cmdlist) ->
            cmdlist.filterIsInstance<TACCmd.Simple.AssertCmd>().partialSums().map { cmds ->
                FactorySmtScript {
                    val target = Assert2Exp(cmds.last())
                    val before = and(cmds.dropLast(1).map { Assert2Exp(it) })
                    and(isInCEX(blk, ctp), before, not(target))
                }
            }
        }.also { statistics.targetCountUserAsserts = it.size }
    }

    /**
     * Produce a single target, stating that some auto-generate assertion is violated and its block is the
     * counterexample, but all user-generated assertions are satisfied.
     */
    fun autoAssertionTargets(ctp: CoreTACProgram): List<SmtExp> {
            val allAsserts = ctp.code.flatMap { (blk, cmdlist) ->
            cmdlist.filterIsInstance<TACCmd.Simple.AssertCmd>().map { blk to it }
        }
        // State that any of the auto-generated asserts is in a reachable block and violated
        val violateAnyAutoGenerated = FactorySmtScript {
            or(allAsserts.filter { !isUserAssertion(it.second) }.map {
                and(not(Assert2Exp(it.second)), isInCEX(it.first, ctp))
            })
        }
        return listOf(
            FactorySmtScript.and(
                allAsserts.filter { isUserAssertion(it.second) }
                    .map { Assert2Exp(it.second) } + violateAnyAutoGenerated)
        ).also { statistics.targetCountAutoAsserts = it.size }
    }

    /**
     * Produce targets for numeric variables (integer or bitvectors) that somehow contribute to the violation of the
     * original counterexample, forcing them to be zero or non-zero. The set of variables is collected by first
     * tracing the counterexample and then computing the backwards cone of influence of the assertion condition.
     */
    fun splitZeroTargets(ctp: CoreTACProgram, original: Map<SmtExp,SmtExp>): List<SmtExp> {
        val modelMap = original.asIterable().filter { it.key is SmtExp.QualIdentifier }
            .associateBy { (it.key as SmtExp.QualIdentifier).id.sym }

        /** Retrieve the model for [sym]. return [sym] if it already is a constant. */
        fun modelForSymbol(sym: TACSymbol): SmtExp {
            if (sym is TACSymbol.Const) {
                return when (val t = sym.tag) {
                    Tag.Bool -> SmtExp.BoolLiteral(sym.value != BigInteger.ZERO)
                    Tag.Int -> SmtExp.IntLiteral(sym.value)
                    is Tag.Bits -> SmtExp.BitvectorLiteral(sym.value, t.bitwidth)
                    else -> error("Unsupported tag ${sym.tag}")
                }
            }
            return modelMap[sym.toSMTRep()]!!.value
        }

        /**
         * Compute the direct successor of the given block based on the program graph and the original
         * counterexample. We first consider all successors whose Reach variable is true and then also check that
         * the corresponding path condition is satisfied. This resolves cases where we can go from A to B directly,
         * but also via an intermediate block C and the original counterexample actually uses C.
         */
        fun directSuccessor(curBlock: NBId): NBId {
            return ctp.analysisCache.graph.succ(curBlock).single {
                modelMap[BI2ReachVarName(it)]!!.value.asBoolean() &&
                    when (val pc = ctp.analysisCache.graph.pathConditions[curBlock]!![it]!!) {
                        is PathCondition.TRUE -> true
                        is PathCondition.EqZero -> {
                            when (pc.v.tag) {
                                Tag.Bool -> !modelForSymbol(pc.v).asBoolean()
                                else -> modelForSymbol(pc.v).asBigInt() == BigInteger.ZERO
                            }
                        }

                        is PathCondition.NonZero -> {
                            when (pc.v.tag) {
                                Tag.Bool -> modelForSymbol(pc.v).asBoolean()
                                else -> modelForSymbol(pc.v).asBigInt() != BigInteger.ZERO
                            }
                        }

                        else -> error("wtf?")
                    }
            }
        }

        // follow the counterexample through ctp, collect the trace of commands, the current block that contains the
        // violated assertion and the violated assertion itself.
        val trace = mutableListOf<TACCmd.Simple>()
        val visitedBlocks = mutableListOf(ctp.entryBlockId)

        val violated = run collectTrace@{
            while (modelMap[BI2ReachVarName(visitedBlocks.last())]!!.value.asBoolean()) {
                val curBlock = visitedBlocks.last()
                ctp.code[curBlock]!!.forEach { cmd ->
                    if (cmd is TACCmd.Simple.AssertCmd && !modelForSymbol(cmd.o).asBoolean()) {
                        return@collectTrace cmd
                    }
                    trace.add(cmd)
                }
                visitedBlocks.add(directSuccessor(curBlock))
            }
            error("did not find a violated assertion")
        }

        // state that the counterexample takes the very same path and violates the same counterexample
        val sameAssertionViolated = FactorySmtScript {
            and(
                and(visitedBlocks.dropLast(1).map { BI2ValidOnCEX(it) }),
                not(Assert2Exp(violated)),
                and(ctp.code[visitedBlocks.last()]!!.asSequence().takeWhile { it != violated }
                    .filterIsInstance<TACCmd.Simple.AssertCmd>().map {
                        Assert2Exp(it)
                    }.toList())
            )
        }

        // compute backward cone of influence of the violated assertion condition
        val targetVars = trace.asReversed().fold(setOf(violated.o)) { vars, cmd ->
            when {
                cmd is TACCmd.Simple.AssigningCmd && (cmd.lhs in vars) -> vars + cmd.getFreeVarsOfRhs()
                cmd is TACCmd.Simple.JumpiCmd -> if (cmd.cond is TACSymbol.Var) {
                    vars + cmd.cond
                } else {
                    vars
                }

                else -> vars
            }
        }

        val modelVars: Map<String, SmtExp> = modelMap.mapValues { it.value.key }
        // produce targets for every target variable
        return targetVars.mapNotNull { sym ->
            // some variables have no model, e.g., maps
            modelVars[sym.toSMTRep()]?.let { sym to it }
        }.flatMap { (sym,v) ->
            when (v.sort) {
                Sort.BV256Sort -> SmtExp.BitvectorLiteral(BigInteger.ZERO, 256)
                Sort.IntSort -> SmtExp.IntLiteral(BigInteger.ZERO)
                else -> null
            }?.let { zero ->
                logger.debug { "create split for ${sym} / ${v} == ${zero}" }
                listOf(
                    FactorySmtScript { (v eq zero) and sameAssertionViolated },
                    FactorySmtScript { not(v eq zero) and sameAssertionViolated }
                )
            } ?: listOf()
        }.also { statistics.targetCountZeroSplit = it.size }
    }

    /**
     * Get all targets based on the strategy specified in the configuration. Most importantly, the different
     * strategies map to different combinations of the heuristic functions defined in [Companion]. The result is a
     * list of targets, each target being an [SmtExp] assigned to a constant. We then try to find counterexamples
     * such that each target is satisfied by at least one counterexample.
     *
     * The target generation can be overridden by setting [targetGenerator] to a non-null function. This is meant to
     * be used by unit tests to only use single heuristics.
     *
     * If targets are more complex than a simple variable assignment, we introduce abstraction variables and store
     * the mapping from the [SmtExp] constructed by the heuristic to the abstraction variable in
     * [targetAbstractions]. These identities are added to the query processor during initialization, protected by
     * [solverContext] to make sure it is properly removed when diversification is finished.
     */
    private fun getTargets(
        ctp: CoreTACProgram,
        original: Map<SmtExp,SmtExp>
    ): List<SmtExp> {
        return measureTimedValue {
            targetGenerator?.invoke(this, ctp, original) ?: when (Config.MultipleCEXStrategy.get()) {
                MultipleCEXStrategyEnum.NONE ->
                    listOf()
                MultipleCEXStrategyEnum.BASIC ->
                    userAssertionTargets(ctp) + blockReachableTargets(ctp) + splitZeroTargets(ctp, original)
                MultipleCEXStrategyEnum.ADVANCED ->
                    userAssertionTargets(ctp) + blockReachableTargets(ctp) + splitZeroTargets(ctp, original) +
                        autoAssertionTargets(ctp)
            }
        }.also {
            logger.debug { "Generated ${it.value.size} targets for diversification in ${it.duration}." }
            statistics.totalTimeTargetGeneration = it.duration
        }.value
    }

    /** Add new variables to the query object to make sure they end up in the model. */
    val newQuery = originalQuery.copy(
        termsOfInterest = originalQuery.termsOfInterest.copy(
            newTerms = originalQuery.termsOfInterest.toMutableSet()
                .apply { addAll(tacProgram.code.keys.map { BI2ValidOnCEX(it) }) }),
        smtFormula = originalQuery.smtFormula.copy(
            symbolTable = originalQuery.smtFormula.symbolTable.copy().also { syms ->
                tacProgram.code.keys.forEach {
                    syms.registerDeclFun(
                        Identifier.Simple(BI2ValidOnCEXName(it)),
                        listOf(),
                        Sort.BoolSort
                    )
                }
            }
        )
    )

    private val solverContext = checker.getContext()

    /**
     * Maps all target assignments to their status. First do an empty target that "only" postprocesses the original CEX.
     */
    private val targets: MutableMap<SmtExp, Status> = (
        listOf<SmtExp>(SmtExp.BoolLiteral.True) + getTargets(tacProgram, originalModel)
        ).associateWith { Status.NotCheckedYet }.toMutableMap()

    /** Checks whether the current model satisfies a target, calling `get-value` on the target.. */
    private suspend fun satisfiesTarget(target: SmtExp): Boolean {
        return checker.runOnQueryProcessor { qp ->
            val result = measureTimedValue { qp.getValue(target, newQuery.symbolTable) }.also {
                statistics.totalTimeTargetEval += it.duration
            }.value
            check(result.isBooleanValueLit()) { "Expected Boolean assignment but got ${result}" }
            result.asBoolean()
        }
    }

    /**
     * Add a new counterexample, assuming it is a SAT result. Updates [targets], setting the value for all assignments
     * from the model to hold the counterexample.
     *
     * To evaluate the (possibly nontrivial) targets, we assert the model back to the solver and use `(get-value)`. Note
     * that we can not use `(get-value)` directly, as the solver may be left in unsat state by the prettifier.
     * In certain rare conditions, the solver returns `unknown` after the model has been asserted. This usually happens
     * due to a timeout in re-checking, and we simply ignore this counterexample. It should be very rare.
     * @param cex The counterexample to process
     */
    private suspend fun addCounterExample(cex: SmtFormulaCheckerResult.Single.Simple) {
        require(cex.satResult == SatResult.SAT)
        checker.runOnQueryProcessor { qp ->
            SmtQueryProcessorContext(qp).use { ctx ->
                // open a new scope and assert the (prettified) model, run check-sat and then evaluate the targets
                ctx.push()
                cex.getValuesResult!!
                    .filter {
                        // Note: if the targets ever talk about maps, we should revisit (perhaps remove) this filter
                        it.value.sort != null && !(it.value.sort)!!.isArraySort()
                    }
                    .forEach {
                        qp.assert(Cmd.Assert(it.key eq it.value))
                    }
                val res = measureTimedValue {
                    qp.checkSat("re-asserted prettified model")
                }.also {
                    statistics.totalTimeChecks += it.duration
                }.value
                when (res) {
                    is SatResult.SAT ->
                        targets.forEach { (target, oldValue) ->
                            when (oldValue) {
                                Status.NotCheckedYet, is Status.MayHaveCounterExample -> {
                                    if (satisfiesTarget(target)) {
                                        logger.debug { "cex covers ${target}" }
                                        targets[target] = Status.HasCounterExample(cex)
                                    }
                                }

                                Status.HasNoCounterExample -> check(!satisfiesTarget(target)) { "found counter example for ${target} that was supposed to have none" }
                                else -> {}
                            }
                        }

                    is SatResult.UNKNOWN -> logger.warn { "unknown when re-asserting prettified model: ${res.reason}" }
                    is SatResult.UNSAT ->
                        logger.warn { "unsat when re-asserting prettified model using ${checker.solverInstanceInfo}" }
                }
            }
        }
    }

    /**
     * Store that the given target has no counter example by updating [targets].
     * @param target The target that has no counterexample
     */
    private fun noCounterExample(target: SmtExp) {
        val oldValue = targets[target]!!
        if (oldValue is Status.HasNoCounterExample) return
        targets[target] = when (oldValue) {
            Status.NotCheckedYet -> Status.HasNoCounterExample
            is Status.HasCounterExample ->
                error("found no counter example for ${target} that was supposed to have one")

            Status.HasNoCounterExample -> oldValue
            is Status.MayHaveCounterExample -> Status.HasNoCounterExample
        }
    }

    /**
     * Compute all counter examples lazily and return them as a sequence. The sequence does not produce duplicate
     * counter examples.
     * @param timeout Total timeout for generating additional counter examples
     * @param postprocessor Function being run on a checker result, for example for prettifying
     */
    fun getSequencedCEXs(
        timeout: kotlin.time.Duration,
        postprocessor: ResultPostprocessor = { res, _ -> res }
    ): Flow<SmtFormulaCheckerResult.Single.Simple> = flow {
        val startTime = System.currentTimeMillis()
        run breaking@{
            targets.forEach { (target, _) ->
                if (System.currentTimeMillis() - startTime >= timeout.inWholeMilliseconds) {
                    logger.warn { "counter-example diversification exceeded timeout (${timeout.inWholeSeconds}s), was aborted." }
                    return@breaking
                }
                // explicitly fetch the value from the "live" member, as it might have been modified by previous iterations
                if (targets[target] is Status.NotCheckedYet) {
                    solverContext.push()
                    checker.runOnQueryProcessor {
                        it.assert(Cmd.Assert(target))
                    }
                    // generate a counterexample with this assignment
                    val next = postprocessor(
                        measureTimedValue {
                            checker.checkSat(newQuery) as SmtFormulaCheckerResult.Single.Simple
                        }.also {
                            statistics.checkCount += 1
                            statistics.totalTimeChecks += it.duration
                        }.value,
                        solverContext
                    )
                    logger.info { "checked for counter-example diversification: ${target} -> ${next.satResult}" }
                    when (next.satResult) {
                        SatResult.SAT -> {
                            addCounterExample(next)
                            statistics.cexCount += 1
                            emit(next)
                        }

                        SatResult.UNSAT -> noCounterExample(target)
                        // otherwise: timeout etc.
                        else -> targets[target] = Status.MayHaveCounterExample(next)
                    }
                    solverContext.pop()
                }
            }
        }
    }.onEmpty {
        smt.logger.info { "no diversification query yielded a result, return the original result" }
    }
}
