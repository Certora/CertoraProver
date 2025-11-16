/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence.tracing

import analysis.CommandWithRequiredDecls
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Interface for extra instrumentation logic to applied to specific long reads.
 */
internal interface InstrumentationMixin : WithVarInit {
    /**
     * Called at some preceding memory udpate [s] which could reach
     * the long read to which this mixin is attached. [overlapSym] holds whether the write at [s]
     * overlaps with this long read. [writeEndPoint] holds the end point (exclusive) of the write.
     * Both are included to let mixins avoid recomputing this information.
     *
     * [baseInstrumentation] is the [ILongReadInstrumentation] to which this mixin is attached.
     */
    fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ) : CommandWithRequiredDecls<TACCmd.Simple>

    /**
     * Called to instrument the actual long read [s] on which this instrumentation mixin operates.
     */
    fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple>

    val intrumentSelfUpdates: Boolean get() = false

    fun getRecord() : InstrumentationRecord? = null
}
