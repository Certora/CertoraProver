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

package verifier.equivalence.cex

import verifier.equivalence.tracing.BufferTraceInstrumentation

/**
 * Basic event, just minimally implements the [EventWithData] interface
 */
data class BasicEvent(
    override val bufferRepr: List<UByte>?,
    override val sort: BufferTraceInstrumentation.TraceEventSort,
    override val params: List<EventParam>,
) : EventWithData {

    override fun prettyPrint() : String {
        val context = params.joinToString("\n") { (k, v) ->
            "\t\t -> ${k.displayLabel}: $v"
        }.ifBlank {
            "\t\tNo additional context information"
        }
        val contextBody = "\tEvent Context:\n$context"
        val description = when(sort) {
            BufferTraceInstrumentation.TraceEventSort.REVERT -> "! The call reverted"
            BufferTraceInstrumentation.TraceEventSort.RETURN -> "! The call returned"
            BufferTraceInstrumentation.TraceEventSort.LOG -> "! A log was emitted"
            BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL -> "! An external call was made"
            BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL -> "! An internal call was made"
            BufferTraceInstrumentation.TraceEventSort.RESULT -> "! The computation completed"
        }
        val bufferBody = if(sort.showBuffer) {
            val bufferDescription = bufferRepr?.let { bufferMap ->
                bufferMap.joinToString("") {
                    it.toString(16)
                }.ifBlank { "! Empty" }
            } ?: "? Couldn't extract the buffer contents"
            "\n\tThe raw buffer used in the event was:\n\t\t$bufferDescription"
        } else { "" }
        return "\t$description\n$contextBody$bufferBody"
    }
}
