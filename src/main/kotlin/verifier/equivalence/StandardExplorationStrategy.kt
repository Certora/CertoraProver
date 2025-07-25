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

import analysis.CmdPointer
import utils.*
import vc.data.isHalting
import verifier.equivalence.StandardInstrumentationConfig.toAView
import verifier.equivalence.StandardInstrumentationConfig.toBView
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * The standard exploration strategy. Uses the following strategy:
 * 1. Equivalence for calls:
 *   a. First attempt the [BufferTraceInstrumentation.TraceInclusionMode.Unified] strategy
 *   b. On a timeout, attempts tiered approach via iterated [BufferTraceInstrumentation.TraceInclusionMode.Until]
 *   c. On a timeout of both strategies, fail equivalence. Otherwise, on completed checks, goto 2
 * 2. Equivalence for logs
 *   a. First attempt the [BufferTraceInstrumentation.TraceInclusionMode.Unified] strategy
 *   a. On a timeout, attempts tiered approach via iterated [BufferTraceInstrumentation.TraceInclusionMode.Until]
 *   c. On a timeout of both strategies, fail. Otherwise, on completed checks, goto 3
 * 3. Equivalence for exits
 *   a. First attempt the [BufferTraceInstrumentation.TraceInclusionMode.Unified] strategy.
 *   b. On a timeout, falls back to pointwise equivalence; sequentially checking each exit site in A against all exits in B
 *   c. On a timeout of both strategies, fail. Otherwise, on a completed check, return "equivalent" result
 */
object StandardExplorationStrategy {
    /**
     * Function to build [verifier.equivalence.ExplorationManager.EquivalenceCheckConfiguration] object
     * with [traceMode] and [traceTargets] and [p] providing the instrumentation control. The VC generation is
     * provided by [vcGen], and the minimizer, if it exists, is given by [min].
     *
     * The non-triviality of this function comes from invoking the [StandardInstrumentationConfig] for the caller.
     */
    private fun <I> getConfigurator(
        traceMode: BufferTraceInstrumentation.TraceInclusionMode,
        traceTargets: BufferTraceInstrumentation.TraceTargets,
        p: EquivalenceChecker.PairwiseProofManager,
        min: Minimizer?,
        vcGen: TraceEquivalenceChecker.VCGenerator<I>
    ) : ExplorationManager.EquivalenceCheckConfiguration<I> {
        return object : ExplorationManager.EquivalenceCheckConfiguration<I> {
            override val vcGenerator: TraceEquivalenceChecker.VCGenerator<I>
                get() = vcGen
            override val minimizer: Minimizer?
                get() = min
            override val traceTarget: BufferTraceInstrumentation.TraceTargets
                get() = traceTargets

            override fun getAConfig(): BufferTraceInstrumentation.InstrumentationControl {
                return StandardInstrumentationConfig.configure(
                    v = p.toAView(),
                    t = traceTargets,
                    inc = traceMode
                )
            }

            override fun getBConfig(): BufferTraceInstrumentation.InstrumentationControl {
                return StandardInstrumentationConfig.configure(
                    v = p.toBView(),
                    t = traceTargets,
                    inc = traceMode
                )
            }
        }
    }

    /**
     * Gets the starting point of exploration strategy, step 1 in the algorithm sketched in the class comment.
     */
    fun <I> entry() : ExplorationManager<I> = LogOrTraceUnified(BufferTraceInstrumentation.TraceTargets.Calls)

    /**
     * A tiered version of checking [traceTargets]. The instrumentation generated by this manager
     * will force the trace instrumentation to only include the nth event in the trace, where n = [currentTier].
     * On success, if there were events included at level [currentTier], returns another [LogOrTraceTiered]
     * with [currentTier] = [currentTier] + 1.
     * Otherwise, we consider all events as proved equivalence, and advance to the next step, either checking logs
     * if [traceTargets] == [BufferTraceInstrumentation.TraceTargets.Calls], or results
     * if [traceTargets] == [BufferTraceInstrumentation.TraceTargets.Log]
     */
    private class LogOrTraceTiered<I>(
        val traceTargets: BufferTraceInstrumentation.TraceTargets,
        val currentTier: Int
    ) : ExplorationManager<I> {
        /**
         * Note that there is no minimizer available; we only ever include the [currentTier]th event, so no prior diverging
         * events can exist (or, if they did, we would have found them checking that level).
         *
         * NB that we still use the [LogOrCallTracer]; the trace arrays are always initialized to 0, and will
         * only be updated at index [currentTier], so we can still use our existential quantification/skolemization trick;
         * the traces will be trivially equivalent at every index except [currentTier].
         */
        override fun nextConfig(p: EquivalenceChecker.PairwiseProofManager): ExplorationManager.EquivalenceCheckConfiguration<I> {
            return getConfigurator(vcGen = LogOrCallTracer(traceTargets), traceTargets = traceTargets, min = null, traceMode = BufferTraceInstrumentation.TraceInclusionMode.Until(currentTier), p = p)
        }

        /**
         * out of ideas, give up
         */
        override fun onTimeout(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I>? {
            return null
        }

        /**
         * Check the instrumentation reports for program A and B. If *every* possible event site was definitely
         * excluded, then we know that no further events exist to check, and then continue on to the next event
         * type. Otherwise, loop, incrementing the [currentTier].
         */
        override fun onSuccess(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I> {
            val moreWork = check.programA.instrumentationResult.useSiteInfo.any { (_, v) ->
                when(v.traceReport?.traceInclusion) {
                    BufferTraceInstrumentation.InclusionSort.DEFINITELY_INCLUDED,
                    BufferTraceInstrumentation.InclusionSort.MAYBE_INCLUDED -> true
                    BufferTraceInstrumentation.InclusionSort.DEFINITELY_EXCLUDED,
                    null -> false
                }
            } || check.programB.instrumentationResult.useSiteInfo.any { (_, v) ->
                when(v.traceReport?.traceInclusion) {
                    BufferTraceInstrumentation.InclusionSort.DEFINITELY_INCLUDED,
                    BufferTraceInstrumentation.InclusionSort.MAYBE_INCLUDED -> true
                    BufferTraceInstrumentation.InclusionSort.DEFINITELY_EXCLUDED,
                    null -> false
                }
            }
            return if(moreWork) {
                LogOrTraceTiered(traceTargets, currentTier + 1)
            } else {
                when(traceTargets) {
                    BufferTraceInstrumentation.TraceTargets.Calls -> LogOrTraceUnified(BufferTraceInstrumentation.TraceTargets.Log)
                    BufferTraceInstrumentation.TraceTargets.Log -> ExitSiteTracerUnified()
                    BufferTraceInstrumentation.TraceTargets.Results -> `impossible!`
                }
            }
        }

    }

    /**
     * [ExplorationManager] that checks for equivalence between [traceTargets] using the [BufferTraceInstrumentation.TraceInclusionMode.Unified]
     * approach, falling back on the tiered approach if there is a timeout. As the name of this class suggests, it is expected
     * that [BufferTraceInstrumentation.TraceTargets] is only [BufferTraceInstrumentation.TraceTargets.Log]
     * or [BufferTraceInstrumentation.TraceTargets.Calls].
     */
    private class LogOrTraceUnified<I>(
        val traceTargets: BufferTraceInstrumentation.TraceTargets
    ) : ExplorationManager<I> {
        /**
         * Note that we do use a [Minimizer] here, as the unified strategy means that there are multiple points in
         * the trace where there could be divergence.
         */
        override fun nextConfig(p: EquivalenceChecker.PairwiseProofManager): ExplorationManager.EquivalenceCheckConfiguration<I> {
            return getConfigurator(
                traceMode = BufferTraceInstrumentation.TraceInclusionMode.Unified,
                p = p,
                traceTargets = traceTargets,
                vcGen = LogOrCallTracer(traceTargets),
                min = object : Minimizer {
                    override fun getGenerator(maxTraceLength: Int): TraceEquivalenceChecker.VCGenerator<Any?> {
                        return BoundedLogOrCallTracer(traceTargets, maxTraceLength)
                    }
                }
            )
        }

        override fun onTimeout(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I> {
            return LogOrTraceTiered(traceTargets, 0)
        }

        override fun onSuccess(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I> {
            return when(traceTargets) {
                BufferTraceInstrumentation.TraceTargets.Calls -> LogOrTraceUnified(BufferTraceInstrumentation.TraceTargets.Log)
                BufferTraceInstrumentation.TraceTargets.Log -> ExitSiteTracerUnified()
                BufferTraceInstrumentation.TraceTargets.Results -> `impossible!`
            }
        }
    }

    /**
     * Pointwise version of the exit site equivalence. We identify all exit sites in a, and give them an arbitrary ordering
     * in [aSites]. We then check each site in isolation, as indicated by [currSite] which is an index into [aSites].
     * At each step, we constrain the control flow of program A to exit via the location in [aSites] at index [currSite]
     * and leave program B unconstrained. After successful verification, if [currSite] is the end, we stop giving a "fully verified"
     * result; otherwise, we move on to [currSite].
     */
    class ExitSiteTracerPointerwise<I>(
        val aSites: List<CmdPointer>,
        val currSite: Int
    ) : ExplorationManager<I> {
        override fun nextConfig(p: EquivalenceChecker.PairwiseProofManager): ExplorationManager.EquivalenceCheckConfiguration<I> {
            val standardConfig = getConfigurator<I>(
                traceTargets = BufferTraceInstrumentation.TraceTargets.Results,
                traceMode = BufferTraceInstrumentation.TraceInclusionMode.Unified,
                vcGen = ProgramExitTracer,
                min = null,
                p = p
            )
            /**
             * Override the standard configuration for a to force at to exit at [currSite].
             */
            return object : ExplorationManager.EquivalenceCheckConfiguration<I> by standardConfig {
                override fun getAConfig(): BufferTraceInstrumentation.InstrumentationControl {
                    val toOverride = standardConfig.getAConfig()
                    return toOverride.copy(
                        traceMode = BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(aSites[currSite])
                    )
                }
            }
        }

        override fun onTimeout(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I>? {
            return null
        }

        override fun onSuccess(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I>? {
            return if(currSite == aSites.lastIndex) {
                null
            } else {
                ExitSiteTracerPointerwise(
                    aSites, currSite = currSite + 1
                )
            }
        }

    }

    /**
     * Unified version of exit site equivalence, checking all results "in parallel".
     */
    private class ExitSiteTracerUnified<I> : ExplorationManager<I> {
        override fun nextConfig(p: EquivalenceChecker.PairwiseProofManager): ExplorationManager.EquivalenceCheckConfiguration<I> {
            return getConfigurator(
                p = p,
                min = null,
                traceMode = BufferTraceInstrumentation.TraceInclusionMode.Unified,
                traceTargets = BufferTraceInstrumentation.TraceTargets.Results,
                vcGen = ProgramExitTracer
            )
        }

        /**
         * XXX: we maybe should use the result site info because with the result summaries those are not marked as halting...
         */
        override fun onTimeout(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I> {
            val exitPtrs = check.programA.originalProgram.program.parallelLtacStream().filter {
                it.cmd.isHalting()
            }.map { it.ptr }.toList()
            return ExitSiteTracerPointerwise(
                exitPtrs, 0
            )
        }

        override fun onSuccess(check: TraceEquivalenceChecker.CheckResult<I>): ExplorationManager<I>? {
            return null
        }
    }
}
