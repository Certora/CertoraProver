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
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * Generates a "standard" [BufferTraceInstrumentation] configuration
 * based on a given [verifier.equivalence.EquivalenceChecker.PairwiseProofManager].
 */
object StandardInstrumentationConfig {
    /**
     * A "view" for a [verifier.equivalence.EquivalenceChecker.PairwiseProofManager] which gets
     * the overrides for the A or B versions of the method, as indicated by [M]
     */
    interface PairwiseView<M: MethodMarker> {
        fun getMLoadOverrides(): TaggedMap<M, CmdPointer, Int?>
        fun getEventOverrides(): TaggedMap<M, CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec>
        fun getBufferOverrides() : TaggedMap<M, CmdPointer, BufferTraceInstrumentation.UseSiteControl>
    }

    /**
     * Wraps the [verifier.equivalence.EquivalenceChecker.PairwiseProofManager] into the [PairwiseView]
     * object for getting the A overrides.
     */
    fun EquivalenceChecker.PairwiseProofManager.toAView() = object : PairwiseView<MethodMarker.METHODA> {
        override fun getMLoadOverrides(): TaggedMap<MethodMarker.METHODA, CmdPointer, Int?> = this@toAView.getAMloadOverrides()
        override fun getEventOverrides(): TaggedMap<MethodMarker.METHODA, CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec> = this@toAView.getAOverrides()
        override fun getBufferOverrides(): TaggedMap<MethodMarker.METHODA, CmdPointer, BufferTraceInstrumentation.UseSiteControl> = this@toAView.getAUseSiteControl()
    }

    /**
     * Ditto, but for the B overrides.
     */
    fun EquivalenceChecker.PairwiseProofManager.toBView() = object : PairwiseView<MethodMarker.METHODB> {
        override fun getMLoadOverrides(): TaggedMap<MethodMarker.METHODB, CmdPointer, Int?> = this@toBView.getBMloadOverrides()
        override fun getEventOverrides(): TaggedMap<MethodMarker.METHODB, CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec> = this@toBView.getBOverrides()
        override fun getBufferOverrides(): TaggedMap<MethodMarker.METHODB, CmdPointer, BufferTraceInstrumentation.UseSiteControl> = this@toBView.getBUseSiteControl()

    }

    /**
     * Genereates buffer configuration for the [M] version of the method,
     * using a common [t] and [inc] for trace inclusions and targets, and using the [v] view to
     * get the overrides for the [M] version.
     */
    fun <M: MethodMarker> configure(
        v: PairwiseView<M>,
        t: BufferTraceInstrumentation.TraceTargets,
        inc: BufferTraceInstrumentation.TraceInclusionMode
    ) : BufferTraceInstrumentation.InstrumentationControl {
        return BufferTraceInstrumentation.InstrumentationControl(
            eventLoggingLevel = t,
            traceMode = inc,
            forceMloadInclusion = v.getMLoadOverrides(),
            eventSiteOverride = v.getEventOverrides(),
            useSiteControl = v.getBufferOverrides()
        )
    }
}
