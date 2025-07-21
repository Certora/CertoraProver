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
import vc.data.TACCmd
import vc.data.TACKeyword
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.toCore
import vc.data.TXF
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.ProgramContext
import verifier.equivalence.tracing.BufferTraceInstrumentation

class BoundedLogOrCallTracer(
    val which: BufferTraceInstrumentation.TraceTargets,
    val bound: Int
) : TraceEquivalenceChecker.VCGenerator<Any?>, TraceEventCheckMixin {
    override fun generateVC(
        progAInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODA, Any?>,
        progAContext: ProgramContext<MethodMarker.METHODA>,
        progBInstrumentation: TraceEquivalenceChecker.IntermediateInstrumentation<MethodMarker.METHODB, Any?>,
        progBContext: ProgramContext<MethodMarker.METHODB>
    ): CoreTACProgram {
        val skolem = TACKeyword.TMP(Tag.Bit256)
        val boundSkolem = ExprUnfolder.unfoldPlusOneCmd("bound", TXF { skolem lt bound }) {
            TACCmd.Simple.AssumeCmd(it.s, "trace length to $bound")
        }.merge(skolem)
        return (boundSkolem andThen generateVCAt(
            targetTrace = which,
            traceIndex = skolem,
            progAInst = progAInstrumentation,
            progBInst = progBInstrumentation,
            assumeNoRevert = true
        )).toCore("final assertion", Allocator.getNBId())
    }
}
