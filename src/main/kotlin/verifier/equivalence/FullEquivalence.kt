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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package verifier.equivalence

import analysis.*
import solver.SolverResult
import spec.rules.EquivalenceRule
import utils.*
import vc.data.*
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.CallableProgram
import datastructures.stdcollections.*
import log.*
import verifier.equivalence.data.TraceEvent
import verifier.equivalence.tracing.BufferTraceInstrumentation
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Class for checking equivalence betwee [programA] and [programB], associating this
 * check with [rule], and in the context [queryContext]. The [ruleGenerator] describes
 * how to bind inputs and setup rules for the equivalence check, using the calling convention
 * type [I].
 *
 * [R] is the type of counter example explanations; different levels or types of explanations are computed by the abstract
 * functions of this class.
 */
abstract class FullEquivalence<I, R>(
    protected val queryContext: EquivalenceQueryContext,
    protected val rule: EquivalenceRule,
    protected val programA: CallableProgram<MethodMarker.METHODA, I>,
    protected val programB: CallableProgram<MethodMarker.METHODB, I>,
    protected val ruleGenerator: AbstractRuleGeneration<I>,
) {
    /**
     * The "state" of the equivalence loop, recording equivalences and refinements discovered in prior iterations
     */
    private val pairwise = EquivalenceChecker.PairwiseProofManager()

    /**
     * The (stateless) [TraceEquivalenceChecker] used for each iteration of the loop.
     */
    private val traceChecker = TraceEquivalenceChecker(
        queryContext = queryContext,
        ruleGenerator = ruleGenerator
    )

    /**
     * The public result of the equivalence check.
     */
    sealed interface EquivalenceResult<out R> {
        /**
         * [Verified] indicates that the loop terminated without finding a CEX. [check] is the result of the last invocation of [TraceEquivalenceChecker.instrumentAndCheck].
         */
        data class Verified(val check: TraceEquivalenceChecker.CheckResult<*>) : EquivalenceResult<Nothing>

        /**
         * Gives an explanation of type [R] of the counterexample discovered by the equivalence checker.
         */
        data class ExplainedCounterExample<R>(val ex: R) : EquivalenceResult<R>

        /**
         * Result when the equivalence checker found a counterexample (recorded in [check]) but counter example explanation
         * or refinement failed for the reason given in [message].
         */
        data class GaveUp(val message: String, val check: TraceEquivalenceChecker.CheckResult<*>) : EquivalenceResult<Nothing>

        fun pp() = when(this) {
            is ExplainedCounterExample -> "NotEquiv"
            is GaveUp -> "GaveUp(${this.message})"
            is Verified -> "Verified"
        }
    }

    /**
     * Result of minimization of a CEX.
     */
    private sealed interface MinimizedSAT<out I>

    private sealed interface MinimizationLoopResult<out I> {
        /**
         * An unrefined, minimized counter example which is a [TraceEventDiff].
         */
        data class UnrefinedCex<I>(val res: TraceEventDiff<I>) : MinimizationLoopResult<I>, MinimizedSAT<I>

        /**
         * Indicates that there was no smaller CEX found.
         */
        data object Unsat : MinimizationLoopResult<Nothing>
    }

    /**
     * The braod classification of the counterexample, whether it's a storage difference, a trace difference, etc.
     */
    private sealed interface CEXClassification<out I>

    /**
     * Common class used for both CEX classification and minimization indicating "we gave up" for the given [reason].
     */
    private data class GaveUp(val reason: String) : MinimizationLoopResult<Nothing>, CEXClassification<Nothing>

    /**
     * Indicates that the [cex] is the result of a storage difference. This can be the result of the minimization loop;
     * the overall minimization process, and the CEX classification.
     * The assertion that failed is at [failingAssert].
     */
    protected data class StorageStateDiff<I>(
        val cex: TraceEquivalenceChecker.CheckResult<I>,
        val failingAssert: LTACCmdView<TACCmd.Simple.AssertCmd>
    ) : MinimizationLoopResult<I>, CEXClassification<I>, MinimizedSAT<I>

    /**
     * Indicates that the counterexample recorded in [traceResult]
     * is a difference in the event traces at [traceIndex]; the assertion that failed is at [failingAssert].
     */
    private data class TraceEventDiff<I>(
        val traceResult: TraceEquivalenceChecker.CheckResult<I>,
        val traceIndex: Int,
        val failingAssert: LTACCmdView<TACCmd.Simple.AssertCmd>
    ) : CEXClassification<I>


    /**
     * Classify the `SAT` result as either a [TraceEventDiff], a [StorageStateDiff], or that we [GaveUp].
     *
     * This **requires** that comparisons that compare storage states are annotated with [EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION],
     * and that assertions that check trace equivalence are annotated with [EquivalenceChecker.TRACE_EQUIVALENCE_ASSERTION] AND
     * the index used for this assertion is captured with the [EquivalenceChecker.IndexHolder] annotation.
     */
    private fun classifySAT(
        p: TraceEquivalenceChecker.CheckResult<I>
    ) : CEXClassification<I> {
        val vcProgram = p.joinedResult.simpleSimpleSSATAC
        val theModel = p.theModel
        val failingAssertOpt = vcProgram.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()
        }.filter {
            EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION in it.cmd.meta || EquivalenceChecker.TRACE_EQUIVALENCE_ASSERTION in it.cmd.meta
        }.filter {
            theModel.valueAsBoolean(it.cmd.o).leftOrNull() == false
        }.findFirst()
        if (failingAssertOpt.isEmpty) {
            return GaveUp(
                "Couldn't find failing assertion in counterexample"
            )
        }
        val failingAssert = failingAssertOpt.get()
        /**
         * If this was a failing assertion of storage equivalence, there is nothing more to do, explain the difference
         * and return
         */
        if (EquivalenceChecker.STORAGE_EQUIVALENCE_ASSERTION in failingAssert.cmd.meta) {
            return StorageStateDiff(p, failingAssert)
        }

        fun fail(f: () -> String) : CEXClassification<I> {
            logger.info(f)
            return GaveUp(f())
        }

        /**
         * Otherwise, find out which index in our trace has the mismatch.
         */
        val eventIdx = vcProgram.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(EquivalenceChecker.IndexHolder.META_KEY)
        }.mapNotNull {
            theModel.valueAsBigInteger(it.indexSym).leftOrNull()
        }.collect(Collectors.toSet()).singleOrNull() ?: return fail {
            "Couldn't find single event id"
        }
        return TraceEventDiff(
            p, eventIdx.intValueExact(), failingAssert
        )
    }

    /**
     * Attempt to minimize the trace counterexample found with [config] in [t], using the minimizer [min].
     *
     * [MinimizedSAT] is basically a refinement on the [CEXClassification] type which does not admit a GaveUp result;
     * if the minimization loop "gives up" we can just take the [TraceEventDiff].
     */
    private suspend fun tryMinimize(
        config: ExplorationManager.EquivalenceCheckConfiguration<I>,
        min: Minimizer,
        t: TraceEventDiff<I>
    ) : MinimizedSAT<I> {
        return when(val rec = minimizationLoop(t.traceIndex, config, min)) {
            is GaveUp -> MinimizationLoopResult.UnrefinedCex(t)
            is StorageStateDiff -> rec
            is MinimizationLoopResult.UnrefinedCex -> rec
            MinimizationLoopResult.Unsat -> MinimizationLoopResult.UnrefinedCex(t)
        }
    }

    /**
     * Turn a [MinimizedSAT] into a [MinimizationLoopResult]. This is just the identity function, but we need the case
     * splitting to help the typesystem out.
     */
    private fun <I> MinimizedSAT<I>.inject() : MinimizationLoopResult<I> = when(this) {
        is StorageStateDiff -> this
        is MinimizationLoopResult.UnrefinedCex -> this
    }

    /**
     * Attempt to minimize the counterexample found in [config], bounding the length of the trace to be less than [maxTraceSize],
     * enforcing this bound with the [min].
     * If the constrained search can't find a smaller counter example, we return [MinimizationLoopResult.Unsat].
     *
     * If the smaller search times out or otherwise gives an unexpected solver result, we give up, in which case
     * we will simply use the [TraceEventDiff] from this function's caller.
     *
     * Otherwise, if we get a SAT result, we classify this result. If the classification gives up,
     * we give up. Otherwise, if it is a storage diff, we return that, storage differences
     * are by definition minimal [1]. If the classification result is another trace event diff, we try minimizing that,
     * kicking this process off again.
     *
     *
     * [1] Currently, the way that all explorers work, the only way to get a storage counter example is when we're checking
     * [verifier.equivalence.tracing.BufferTraceInstrumentation.TraceTargets.Results], which are never minimizable. However,
     * We are not willing to hardcode this assumption, and thus allow for storage differences to popup in smaller instances.
     */
    private suspend fun minimizationLoop(
        maxTraceSize: Int,
        config: ExplorationManager.EquivalenceCheckConfiguration<I>,
        min: Minimizer
    ) : MinimizationLoopResult<I> {
        val check = basicEquivalenceCheck(
            prog = config,
            vcGenerator = min.getGenerator(maxTraceSize)
        )
        return if(check.rawSolverResult.finalResult == SolverResult.UNSAT) {
            MinimizationLoopResult.Unsat
        } else if(check.rawSolverResult.finalResult == SolverResult.TIMEOUT) {
            GaveUp("minimization timeout")
        } else if(check.rawSolverResult.finalResult == SolverResult.SAT) {
            when(val sort = classifySAT(check)) {
                is GaveUp -> sort
                is StorageStateDiff -> sort
                is TraceEventDiff -> {
                    tryMinimize(config, min, sort).inject()
                }
            }
        } else {
            GaveUp("Unrecognized solver result: ${check.rawSolverResult.finalResult}")
        }
    }

    /**
     * The equivalence loop. The steps to take in this iteration of this loop are controlled by the
     * [explorationManager]. If the [explorationManager] indicates there is more work to do,
     * we continue the "loop" by recursively invoking this function. Otherwise, a successful result or
     * timeout/cex/etc. are returned in the appropriate [EquivalenceResult] variant.
     */
    protected suspend fun equivalenceLoop(explorationManager: ExplorationManager<I>) : EquivalenceResult<R> {
        val config = explorationManager.nextConfig(pairwise)

        val res = basicEquivalenceCheck(
            config,
            config.vcGenerator
        )
        when(res.rawSolverResult.finalResult) {
            SolverResult.SAT -> {
                /**
                 * Classify the sat result
                 */
                when(val c = classifySAT(res)) {
                    /**
                     * nothing to do, we have a legit sat, but can't say more about it; just return back the sat result
                     * and a message explaining why we gave up
                     */
                    is GaveUp -> return EquivalenceResult.GaveUp(message = c.reason, check = res)
                    /**
                     * No minimization necessary; return the explained storage counter example.
                     *
                     * NB that we could consider storage differences that could benefit from refinement, but absent
                     * seeing one of those cases yet, we don't do it.
                     */
                    is StorageStateDiff -> return EquivalenceResult.ExplainedCounterExample(explainStorageCEX(res, c))
                    /**
                     * Try minimizing, and then refining this counter example, or explaining it if it is legitimate.
                     */
                    is TraceEventDiff -> {
                        val min = config.minimizer

                        /**
                         * If the current configuration supports minimization, try minimizing
                         */
                        val minimized = if(min != null) {
                            when(val minimized = tryMinimize(
                                config = config, min, t = c
                            )) {
                                /**
                                 * Minimizing returned a storage difference, which we can't refine (although see the
                                 * caveat above). Return now.
                                 */
                                is StorageStateDiff -> return EquivalenceResult.ExplainedCounterExample(explainStorageCEX(minimized.cex, minimized))
                                /**
                                 * Otherwise, use this minimal, unrefined counter example
                                 */
                                is MinimizationLoopResult.UnrefinedCex -> {
                                    minimized.res
                                }
                            }
                        } else {
                            /**
                             * couldn't minimize? just use the existing trace diff
                             */
                            c
                        }
                        /**
                         * Now try refining it...
                         */
                        return when(val ref = tryRefine(
                            res = res,
                            config = config,
                            minimized = minimized
                        )) {
                            /**
                             * Gave up refinement, log why, return the explained result. Note that the refinement process
                             * failing here means we couldn't even discover what failed (and why it couldn't be refined),
                             * which means we don't have enough information to explain the trace.
                             */
                            is RefinementResult.GaveUp -> {
                                EquivalenceResult.GaveUp(message = ref.why, res)
                            }
                            /**
                             * No refinment means we report this result as legimiate, explaining via [explainTraceCEX]
                             */
                            is RefinementResult.NoRefinement -> {
                                return EquivalenceResult.ExplainedCounterExample(explainTraceCEX(
                                    config.traceTarget,
                                    ref.a,
                                    ref.b,
                                    res
                                ))
                            }
                            /**
                             * If we successfully refined, we loop back, using the same config.
                             */
                            RefinementResult.Refined -> {
                                equivalenceLoop(explorationManager)
                            }
                        }
                    }
                }
            }
            /**
             * On a succesful solver invocation, see if we have more work to do (the current [explorationManager] returning
             * non-null from [ExplorationManager.onSuccess]) or returning back the succesful result.
             */
            SolverResult.UNSAT -> {
                explorationManager.onSuccess(res)?.let {
                    return equivalenceLoop(it)
                } ?: return EquivalenceResult.Verified(res)
            }
            /**
             * On a timeout, see if we can try decomposing the problem via [ExplorationManager.onTimeout];
             * if so, loop using that new strategy, otherwise give up with the timeout result.
             */
            SolverResult.TIMEOUT -> {
                explorationManager.onTimeout(res)?.let {
                    return equivalenceLoop(it)
                } ?: return EquivalenceResult.GaveUp(
                    message = "timeout",
                    check = res
                )
            }
            SolverResult.UNKNOWN,
            SolverResult.SANITY_FAIL -> return EquivalenceResult.GaveUp("unexpected solver response ${res.rawSolverResult.finalResult}", res)
        }
    }

    /**
     * Given the CEX in [res] and the extracted information about the storage state difference in [f], return a [R]
     * explaining the counter example.
     */
    protected abstract fun explainStorageCEX(
        res: TraceEquivalenceChecker.CheckResult<I>,
        f: StorageStateDiff<I>
    ) : R

    /**
     * Given the counter example in [res], with a difference in trace category [targets], explain the difference between [a]
     * and [b]. Note that one of [a] or [b] (but not both!) may be null, indicating that program A has an event that B does not
     * (or [b] is null) or vice versa.
     *
     * The explanation should be given as a subtype of [R].
     */
    abstract fun explainTraceCEX(
        targets: BufferTraceInstrumentation.TraceTargets,
        a: TraceEvent<MethodMarker.METHODA>?,
        b: TraceEvent<MethodMarker.METHODB>?,
        res: TraceEquivalenceChecker.CheckResult<I>
    ): R

    /**
     * Result of [tryRefine].
     */
    private sealed interface RefinementResult {
        /**
         * [tryRefine] did some (unspecified) refinement, recorded in the [verifier.equivalence.EquivalenceChecker.PairwiseProofManager],
         * equivalence should be retried.
         */
        object Refined : RefinementResult

        /**
         * We couldn't refine, nor could we even track down the offending trace sites because
         * [why]. This makes it impossible to explain counter examples.
         *
         */
        data class GaveUp(val why: String) : RefinementResult

        /**
         * We found the offending [TraceEvent], but couldn't refine them. [a] or [b] may be null (but not both), for the reasons given
         * in the documentation of [explainTraceCEX].
         */
        data class NoRefinement(
            val a: TraceEvent<MethodMarker.METHODA>?,
            val b: TraceEvent<MethodMarker.METHODB>?
        ) : RefinementResult
    }

    /**
     * Try to isolate the offending trace events for [TraceEventDiff], and if possible, refine them.
     *
     * Despite the name, this function kinda pull double duty, as even if refinment doesn't work, we need it to figure out
     * which events caused the difference ([TraceEventDiff] only records the index, and does **not** tell us where in programs A and
     * B the difference happens).
     */
    private suspend fun tryRefine(
        res: TraceEquivalenceChecker.CheckResult<I>,
        minimized: TraceEventDiff<I>,
        config: ExplorationManager.EquivalenceCheckConfiguration<I>
    ): RefinementResult {
        val cexContext = CounterExampleContext(
            queryContext = queryContext,
            ruleGeneration = ruleGenerator,
            checkResult = res,
            instrumentationSettings = config
        )
        return when(val refineRes = CounterExampleRefiner.tryRefineCounterExample(
            rule = rule,
            eventIdx = minimized.traceIndex.toBigInteger(),
            cexContext = cexContext
        )) {
            is CounterExampleRefiner.RefinementResult.GaveUp -> RefinementResult.GaveUp(
                why = refineRes.why
            )
            is CounterExampleRefiner.RefinementResult.BufferRefinement -> {
                pairwise.registerPairwiseProof(refineRes.siteA, refineRes.siteB)
                RefinementResult.Refined
            }
            is CounterExampleRefiner.RefinementResult.MemoryCellRefinement -> {
                val aSucc = refineRes.refineA?.let {
                    pairwise.registerALoadOverride(it.first, it.second)
                } == true
                val bSucc = refineRes.refineB?.let {
                    pairwise.registerBLoadOverride(it.first, it.second)
                } == true
                if(aSucc || bSucc) {
                    return RefinementResult.Refined
                } else {
                    return RefinementResult.GaveUp("couldn't actually do requested refinement")
                }
            }
            is CounterExampleRefiner.RefinementResult.UnrefinableCEX -> RefinementResult.NoRefinement(
                a = refineRes.traceSiteA,
                b = refineRes.traceSiteB
            )
        }
    }

    private suspend fun basicEquivalenceCheck(
        prog: ExplorationManager.EquivalenceCheckConfiguration<I>,
        vcGenerator: TraceEquivalenceChecker.VCGenerator<I>
    ): TraceEquivalenceChecker.CheckResult<I> {
        val progA = TraceEquivalenceChecker.ProgramAndConfig(programA, instrumentationConfig = prog.getAConfig())
        val progB = TraceEquivalenceChecker.ProgramAndConfig(programB, instrumentationConfig = prog.getBConfig())
        return traceChecker.instrumentAndCheck(
            rule,
            progA, progB, vcGenerator
        )
    }
}
