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

package verifier.equivalence.data

import analysis.CmdPointer
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * An event that is of interest, as extracted from the [BufferTraceInstrumentation.TraceIndexMarker]
 * annotation. This annotation appeared at [vcProgramSite] in the program sent to the solver, and
 * was found at [origProgramSite] in the pre-inlined, instrumented version.
 */
data class TraceEvent<M: MethodMarker>(
    val origProgramSite: CmdPointer,
    val vcProgramSite: CmdPointer,
    val marker: BufferTraceInstrumentation.TraceIndexMarker
)
