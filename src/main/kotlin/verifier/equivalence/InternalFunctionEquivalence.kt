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

import log.*
import spec.cvlast.QualifiedMethodSignature
import spec.rules.EquivalenceRule
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import verifier.equivalence.data.CallableProgram
import verifier.equivalence.data.CallableProgram.Companion.map
import verifier.equivalence.data.EquivalenceQueryContext
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.TraceEvent
import verifier.equivalence.summarization.ComputationResults
import verifier.equivalence.summarization.PureFunctionExtraction
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * Judges equivalence on [programA] and [programB] which are (assumed to be) canonical
 * internal functions with the signature [sig] within two external methods.
 * The binding of nondeterministic (but equal) arguments is handled by [ruleGeneration],
 * which uses the calling convention type [R]. This process first tries to iteratively
 * summarize away equivalent loops via [LoopEquivalence]; after this process
 * the equivalence of the resulting functions is checked with [FullEquivalence].
 */
class InternalFunctionEquivalence<R: PureFunctionExtraction.CallingConvention<R>>(
    val sig: QualifiedMethodSignature,
    val context: EquivalenceQueryContext,
    val ruleGeneration: AbstractRuleGeneration<R>,
    programAIn: PureFunctionExtraction.CanonFunction<R>,
    programBIn: PureFunctionExtraction.CanonFunction<R>
) {
    private val programA = object : CallableProgram<MethodMarker.METHODA, R> {
        override val program: CoreTACProgram
            get() = programAIn.prog.code
        override val callingInfo: R
            get() = programAIn.callingConvention

        override fun pp(): String {
            return "${sig.prettyPrint()} in ${context.contextA.hostContract.name}"
        }
    }

    private val programB = object : CallableProgram<MethodMarker.METHODB, R> {
        override val program: CoreTACProgram
            get() = programBIn.prog.code
        override val callingInfo: R
            get() = programBIn.callingConvention

        override fun pp(): String {
            return "${sig.prettyPrint()} in ${context.contextA.hostContract.name}"
        }
    }

    private fun <M: MethodMarker> traceOutputs(m: CallableProgram<M, R>): CallableProgram<M, R> {
        return m.map { c ->
            val g = c.analysisCache.graph
            val returnBlocks = c.getEndingBlocks().filterNot {
                g.elab(it).commands.last().cmd.isHalting()
            }
            val (outputs, binding) = m.callingInfo.bindOutputs()
            val resultBlock = binding andThen TACCmd.Simple.SummaryCmd(
                ComputationResults(
                    symbols = outputs
                )
            )
            val patcher = c.toPatchingProgram()
            patcher.addVarDecls(resultBlock.varDecls)
            patcher.addVarDecls(outputs)
            for(r in returnBlocks) {
                patcher.addAfter(g.elab(r).commands.last().ptr, resultBlock.cmds)
            }
            patcher.toCode(c)
        }
    }

    private fun <M: MethodMarker> unroll(m: CallableProgram<M, R>): CallableProgram<M, R> {
        return m.map {
            it.convertToLoopFreeCode()
        }
    }

    suspend fun proveEquivalence(): Boolean {
        val (a, b) = LoopEquivalence.trySummarizeLoops(
            ruleGeneration = ruleGeneration,
            queryContext = context,
            a = programA,
            b = programB
        ).let { (a, b) ->
            unroll(a) to unroll(b)
        }
        val aTraced = traceOutputs(a)
        val bTraced = traceOutputs(b)
        val result = object : FullEquivalence<R, Unit>(
            rule = EquivalenceRule.freshRule("internal function equivalence"),
            ruleGenerator = ruleGeneration,
            programB = bTraced,
            programA = aTraced,
            queryContext = context
        ) {
            override fun explainStorageCEX(res: TraceEquivalenceChecker.CheckResult<R>, f: StorageStateDiff<R>) {}

            override fun explainTraceCEX(
                targets: BufferTraceInstrumentation.TraceTargets,
                a: TraceEvent<MethodMarker.METHODA>?,
                b: TraceEvent<MethodMarker.METHODB>?,
                res: TraceEquivalenceChecker.CheckResult<R>
            ) { }

            suspend fun run() = equivalenceLoop(StandardExplorationStrategy.entry())
        }.run()
        return result is FullEquivalence.EquivalenceResult.Verified
    }
}
