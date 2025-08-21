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

import config.ReportTypes
import log.*
import datastructures.stdcollections.*
import report.DummyLiveStatsReporter
import report.callresolution.CallResolutionTable
import report.callresolution.CallResolutionTableBase
import rules.CompiledRule
import rules.IsFromCache
import rules.RuleCheckResult
import rules.VerifyTime
import solver.CounterexampleModel
import spec.rules.IRule
import tac.CallId
import tac.DumpTime
import tac.StartBlock
import vc.data.CoreTACProgram
import verifier.TACVerifier
import verifier.Verifier
import verifier.equivalence.data.*
import verifier.equivalence.tracing.BufferTraceInstrumentation

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * The basic building block of the equivalence check; responsible for instrumenting the source programs,
 * generating a Vc, constructing a rule from the VC and instrumented code, invoking the solver with this rule,
 * and then interpreting the low-level solver results.
 *
 * [queryContext] is the overall context of the equivalence check. The [ruleGenerator] describes how to stitch instrumented programs
 * and the VC together into a single tac program. Its type argument (and this class') is [I], which is the type of "calling convention info";
 * see [CallableProgram] for details.
 */
class TraceEquivalenceChecker<I>(
    override val queryContext: EquivalenceQueryContext,
    val ruleGenerator: AbstractRuleGeneration<I>
) : IWithQueryContext {

    /**
     * A [CallableProgram] [p] and the configuration to use when instrumenting it [instrumentationConfig]. Input
     * to the public entry point of this class, [instrumentAndCheck]
     */
    data class ProgramAndConfig<M: MethodMarker, I>(
        val p: CallableProgram<M, I>,
        val instrumentationConfig: BufferTraceInstrumentation.InstrumentationControl
    )

    /**
     * Carrier class for the transformations done on the source program. [originalProgram] was passed in
     * via one of the [ProgramAndConfig] objects; the result of its instrumentation via [ProgramAndConfig.instrumentationConfig]
     * is recorded in [instrumentationResult]. That instrumented result was "inlined" in the checked rule; the call id of
     * the inlined instrumentation is [inlinedCallId].
     *
     * NB that it is fine to use non-functions with this class, but given its use in checking function equivalence, we
     * will keep saying [inlinedCallId] and "inlined" even if we're not strictly speaking "inlining a call".
     *
     * As with all over classes, which program this refers to (A or B) is determined by the [M] type parameter.
     */
    data class Instrumentation<M: MethodMarker, out I>(
        val originalProgram: CallableProgram<M, I>,
        val instrumentationResult: BufferTraceInstrumentation.InstrumentationResults,
        val inlinedCallId: CallId
    )

    /**
     * The result of the instrumentation and check. The source and instrumented versions of the programs are given in [programA] and [programB].
     * [rawSolverResult] is the result returned from [TACVerifier.verify]; it's "joined" version (whatever that is) is available as
     * [joinedResult]. The result of the check as the [RuleCheckResult.Single] is available as [ruleResult]; constructing this
     * field involves non-trivial computation, so use [rawSolverResult] or [joinedResult] where possible.
     */
    class CheckResult<I>(
        val programA: Instrumentation<MethodMarker.METHODA, I>,
        val programB: Instrumentation<MethodMarker.METHODB, I>,
        val rawSolverResult: Verifier.VerifierResult,
        ruleResultLz: Lazy<RuleCheckResult.Single>,
        val joinedResult: Verifier.JoinedResult,
    ) {
        val ruleResult by ruleResultLz

        val vcProgram: CoreTACProgram get() = joinedResult.simpleSimpleSSATAC
        val theModel : CounterexampleModel get() = joinedResult.examplesInfo!!.head.model
    }

    /**
     * The instrumented version [result] of the [sourceProgram] before it is inlined into a rule. Passed
     * to the [VCGenerator] to generate the VC which is used to generate the rule. As the name suggests,
     * an intermediate artifact in the overall [instrumentAndCheck] process.
     */
    data class IntermediateInstrumentation<M: MethodMarker, out I>(
        val result: BufferTraceInstrumentation.InstrumentationResults,
        val sourceProgram: CallableProgram<M, I>,
    )

    /**
     * Instrument the [ProgramAndConfig.p] of [prog] according to [prog]'s [ProgramAndConfig.instrumentationConfig].
     * The context for the program is given in [context] (used to instrumented storage accesses).
     */
    private fun <T: MethodMarker> doInstrumentation(
        prog: ProgramAndConfig<T, I>,
        context: ProgramContext<T>
    ) : IntermediateInstrumentation<T, I> {
        ArtifactManagerFactory().dumpCodeArtifacts(prog.p.program, time = DumpTime.PRE_TRANSFORM, dumpType = ReportTypes.INSTRUMENT_BUFFER_TRACE, location = StaticArtifactLocation.Reports)
        return IntermediateInstrumentation(BufferTraceInstrumentation.instrument(
            context = context,
            code = prog.p.program,
            options = prog.instrumentationConfig
        ), prog.p).also {
            ArtifactManagerFactory().dumpCodeArtifacts(it.result.code, time = DumpTime.POST_TRANSFORM, dumpType = ReportTypes.INSTRUMENT_BUFFER_TRACE, location = StaticArtifactLocation.Reports)
        }
    }

    /**
     * Promotes [IntermediateInstrumentation] into a [CallableProgram], using the calling convention of [orig] (it is *assumed*
     * that the instrumentation of [BufferTraceInstrumentation] does not changing the calling convention of the source program).
     */
    private fun <M: MethodMarker, I> IntermediateInstrumentation<M, I>.asProgram(
        orig: CallableProgram<M, I>
    ) = object : CallableProgram<M, I> by orig {
        override val program: CoreTACProgram
            get() = this@asProgram.result.code
    }

    /**
     * Given a callable program A and its instrumentation configuration [progA],
     * similar data for program B in [progB], and instructions on generating a VC for this instrumentation
     * in [vcGenerator], build an check a rule associating its result in [equivalenceRule].
     *
     * [CheckResult] is a record of all of the work done by this function, including the instrumentation, inlining
     * decisions, solver result, etc.
     */
    suspend fun instrumentAndCheck(
        equivalenceRule: IRule,
        progA: ProgramAndConfig<MethodMarker.METHODA, I>,
        progB: ProgramAndConfig<MethodMarker.METHODB, I>,
        vcGenerator: VCGenerator<I>
    ) : CheckResult<I> {
        val instrumentedA = doInstrumentation(progA, queryContext.contextA)
        val instrumentedB = doInstrumentation(progB, queryContext.contextB)

        val vc = vcGenerator.generateVC(instrumentedA, queryContext.contextA, instrumentedB, queryContext.contextB)

        val theRule = ruleGenerator.generateRule(
            aCode = instrumentedA.asProgram(progA.p),
            bCode = instrumentedB.asProgram(progB.p),
            vc = vc,
            label = "program-equivalence"
        )
        val start = System.currentTimeMillis()
        val vcRes = TACVerifier.verify(scene, theRule.code, DummyLiveStatsReporter, equivalenceRule)
        val end = System.currentTimeMillis()
        val verifyTime = VerifyTime.WithInterval(start, end)

        val res = Verifier.JoinedResult(vcRes)

        val ruleResult = utils.lazy {
            when(res) {
                is Verifier.JoinedResult.Failure -> {
                    val origProgWithAssertIdMeta =
                        CompiledRule.addAssertIDMetaToAsserts(res.simpleSimpleSSATAC, equivalenceRule)
                    RuleCheckResult.Single.WithCounterExamples(
                        rule = equivalenceRule,
                        result = vcRes.finalResult,
                        verifyTime = verifyTime,
                        ruleAlerts = emptyList(),
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.WithExamplesData(
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                            rule = equivalenceRule,
                            res = res,
                            scene = scene,
                            origProgWithAssertIdMeta = origProgWithAssertIdMeta,
                            callResolutionTableFactory = CallResolutionTable.Factory(
                                theRule.code,
                                scene,
                                equivalenceRule
                            ),
                        )
                    )
                }
                is Verifier.JoinedResult.Success,
                is Verifier.JoinedResult.SanityFail,
                is Verifier.JoinedResult.Timeout,
                is Verifier.JoinedResult.Unknown -> {
                    RuleCheckResult.Single.Basic(
                        result = vcRes.finalResult,
                        callResolutionTable = CallResolutionTableBase.Empty,
                        ruleAlerts = emptyList(),
                        rule = equivalenceRule,
                        ruleCheckInfo = RuleCheckResult.Single.RuleCheckInfo.BasicInfo(
                            isSolverResultFromCache = IsFromCache.INAPPLICABLE,
                            isOptimizedRuleFromCache = IsFromCache.INAPPLICABLE,
                            dumpGraphLink = null
                        ),
                        verifyTime = verifyTime,
                        unsatCoreStats = null
                    )
                }
            }
        }

        val progAData = Instrumentation(
            originalProgram = progA.p,
            inlinedCallId = theRule.methodACallId,
            instrumentationResult = instrumentedA.result
        )

        val progBData = Instrumentation(
            originalProgram = progB.p,
            inlinedCallId = theRule.methodBCallId,
            instrumentationResult = instrumentedB.result
        )

        // we need to call this if we want to get the Report dump, no idea why.
        res.reportOutput(equivalenceRule)

        logger.info {
            "Solver result is ${vcRes.finalResult}"
        }

        return CheckResult(
            programA = progAData,
            programB = progBData,
            rawSolverResult = vcRes,
            joinedResult = res,
            ruleResultLz = ruleResult
        )
    }

    /**
     * Functional interface for generating the VC to check equivalence between the instrumented programs.
     */
    fun interface VCGenerator<in I> {

        /**
         * [progAInstrumentation] is the result of instrumenting the A version of the program in
         * context [progAContext].
         *
         * [progBContext] and [progBInstrumentation] as similarly defined for the B version of the program.
         *
         * The returned [CoreTACProgram] should contain assertions that the effects of the program (as recorded by
         * the trace instrumentation) are equivalence. This VC program is **not** inlined (given a unique call id) so the
         * returned [CoreTACProgram] must **not** contain the distinguished [StartBlock]
         */
        fun generateVC(
            progAInstrumentation: IntermediateInstrumentation<MethodMarker.METHODA, I>,
            progAContext: ProgramContext<MethodMarker.METHODA>,
            progBInstrumentation: IntermediateInstrumentation<MethodMarker.METHODB, I>,
            progBContext: ProgramContext<MethodMarker.METHODB>
        ) : CoreTACProgram
    }
}
