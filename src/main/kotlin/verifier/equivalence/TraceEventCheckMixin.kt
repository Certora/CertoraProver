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

import analysis.CommandWithRequiredDecls
import tac.MetaMap
import tac.Tag
import utils.letIf
import vc.data.TACCmd
import vc.data.TACMeta
import vc.data.TACProgramCombiners.andThen
import vc.data.TACSymbol
import vc.data.TXF
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.tracing.BufferTraceInstrumentation
import datastructures.stdcollections.*

/**
 * Mixin for generating a VC that asserts equivalence of event traces.
 */
interface TraceEventCheckMixin {
    /**
     * Given a target event category [targetTrace], and a (potentially constrained) index into those traces [traceIndex],
     * assert that the traces at that index are equal, using the instrumentation variables provided by [progAInst] and [progBInst].
     *
     * [assumeNoRevert] indicates whether assertion should assume that the two programs didn't revert or not. It is expected
     * (but not checked) that this is true if [targetTrace] is log or call, and false if [targetTrace] is results,
     * although this class anticipates potential cases where this is not the case.
     *
     * The actual trace index used for comparison (which can be rewritten by the
     * [verifier.equivalence.tracing.BufferTraceInstrumentation.TraceTargets.indexHolder]) is recorded with the [EquivalenceChecker.IndexHolder]
     * annotation.
     *
     * The assertion that asserts equivalence is marked with the [EquivalenceChecker.TRACE_EQUIVALENCE_ASSERTION] meta.
     */
    fun generateVCAt(
        targetTrace: BufferTraceInstrumentation.TraceTargets,
        traceIndex: TACSymbol.Var,
        progAInst: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODA, *>,
        progBInst: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODB, *>,
        assumeNoRevert: Boolean
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val getter = targetTrace.loggerExtractor
        val traceValueA = TACSymbol.Var("traceValueA", Tag.Bit256).toUnique("!")
        val traceValueB = TACSymbol.Var("traceValueB", Tag.Bit256).toUnique("!")
        val assertSym = TACSymbol.Var("assertionSym", Tag.Bool).toUnique("!")
        val extractA = progAInst.result.traceVariables.getter().getRepresentative(traceIndex)
        val extractB = progBInst.result.traceVariables.getter().getRepresentative(traceIndex)

        return extractA.toCRD() andThen extractB.toCRD() andThen CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AnnotationCmd(
                    EquivalenceChecker.IndexHolder.META_KEY, EquivalenceChecker.IndexHolder(
                        indexSym = targetTrace.indexHolder(traceIndex)
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = traceValueA,
                    rhs = extractA.exp,
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = traceValueB,
                    rhs = extractB.exp,
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = assertSym,
                    rhs = TXF {
                        (traceValueA eq traceValueB).letIf(assumeNoRevert) { exp ->
                            exp or progAInst.result.traceVariables.isRevertingPath or progBInst.result.traceVariables.isRevertingPath
                        }
                    }
                ),
                TACCmd.Simple.AssertCmd(
                    assertSym,
                    "traces equal",
                    MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT) + (EquivalenceChecker.TRACE_EQUIVALENCE_ASSERTION to targetTrace.ordinal)
                )
            ),
            setOf(
                traceIndex,
                traceValueA,
                traceValueB,
                assertSym,
                progBInst.result.traceVariables.isRevertingPath,
                progAInst.result.traceVariables.isRevertingPath
            )
        )
    }
}
