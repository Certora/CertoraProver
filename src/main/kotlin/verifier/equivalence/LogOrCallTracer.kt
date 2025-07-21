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

import allocator.Allocator
import tac.Tag
import vc.data.CoreTACProgram
import vc.data.TACKeyword
import vc.data.TACProgramCombiners.toCore
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * A [verifier.equivalence.TraceEquivalenceChecker.VCGenerator] which compares trace equivalence for [which].
 * This is done by non-deterministically generating a skolem index, and using the [TraceEventCheckMixin] to
 * find a mismatch at said index; if a mismatch exists at any index, the SMT solver can find it.
 *
 * The VC generation doesn't depend on the calling convention, so this is usable as a [verifier.equivalence.TraceEquivalenceChecker.VCGenerator]<I>
 * for any such I.
 */
class LogOrCallTracer(val which: BufferTraceInstrumentation.TraceTargets) : TraceEquivalenceChecker.VCGenerator<Any?>, TraceEventCheckMixin {
    override fun generateVC(
        progAInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODA, Any?>,
        progAContext: ProgramContext<MethodMarker.METHODA>,
        progBInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODB, Any?>,
        progBContext: ProgramContext<MethodMarker.METHODB>
    ): CoreTACProgram {
        val skolem = TACKeyword.TMP(Tag.Bit256, "skolem")
        return generateVCAt(
            targetTrace = which,
            traceIndex = skolem,
            progBInst = progBInstrumentation,
            progAInst = progAInstrumentation,
            assumeNoRevert = true
        ).toCore("assertion", Allocator.getNBId())
    }
}
