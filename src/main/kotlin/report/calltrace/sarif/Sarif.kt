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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package report.calltrace.sarif

import datastructures.stdcollections.*
import kotlinx.serialization.json.*
import report.TreeViewRepJsonObjectBuilder
import report.TreeViewReportable
import report.calltrace.CallTraceAttribute
import report.calltrace.altReprsInTreeView
import report.calltrace.formatter.AlternativeRepresentations
import report.calltrace.formatter.AlternativeRepresentations.Representations
import report.calltrace.formatter.AlternativeRepresentations.Representations.Pretty
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.printer.CallTracePrettyPrinter
import report.calltrace.sarif.SarifBuilder.Companion.mkSarif
import report.putJsonArray
import utils.*

/**
 * represents a SARIF-like format string, used in serialization of this class.
 * conceptually similar to a `printf` string.
 * placeholders in the format string are sequential (zero-based)
 * and use the syntax '{n}', where `n` corresponds to the n'th
 * argument in [args].
 *
 * for example, one such string could be: myFunc('{0}', '{1}') returns '{2}'
 */
data class Sarif internal constructor(val pieces: List<String>, val args: List<Arg>): TreeViewReportable {
    init {
        require(pieces.size == args.size + 1) {
            "got malformed input: pieces = $pieces, args = $args"
        }
    }

    /** constructs the format string represented by this instance of the class */
    override fun toString(): String {
        val message = StringBuilder()

        /**
         * since we assume `pieces.size == args.size + 1`,
         * and since [Iterable.zip] has the length of the
         * shorter iterator, there would be exactly one
         * piece remaining after this `for` loop.
         */
        for ((piece, idx) in pieces.zip(args.indices)) {
            message.append(piece)

            val ph = """'{$idx}'"""
            message.append(ph)
        }

        // ...and now we add the remaining piece
        message.append(pieces.last())

        return message.toString()
    }


    /**
     * Flatten back to string -- fill in the first element of `values` for each [Arg]. Used in various places where we
     * want the sarif as a plain string (and are ok with loosing the PRETTY-HEX-DEC switching).
     */
    fun flatten(representation: Representations = Pretty): String {
        val message = StringBuilder()
        for ((piece, idx) in pieces.zip(args.indices)) {
            message.append(piece)
            val ph = args[idx].values.let { it[representation] ?: it[Pretty] }
            message.append(ph)
        }
        message.append(pieces.last())
        return message.toString()
    }

    /** If this is just an [Sarif.Arg.asSarif], return the [Arg], else null. (not extremely elegant, but at
     * places, it seems) */
    fun asArg() =
        runIf(pieces.size == 2 && args.size == 1 && pieces.all { it.isEmpty() }) {
            args.first()
        }

    fun asArgOrUnknown() = asArg() ?: CallTraceValueFormatter.unknownArg()

    /** Analogous logic to [toString], for use in [CallTracePrettyPrinter] */
    fun prettyPrint(): String {
        val message = StringBuilder()
        for ((piece, idx) in pieces.zip(args.indices)) {
            message.append(piece)
            val ph = args[idx].let { "${it.values[Pretty]}:${it.type}"}
            message.append(ph)
        }
        message.append(pieces.last())
        return message.toString()
    }

    override val treeViewRepBuilder get() = TreeViewRepJsonObjectBuilder {
        put(CallTraceAttribute.TEXT(), this@Sarif.toString())
        putJsonArray(CallTraceAttribute.ARGUMENTS(), args)
    }

    /**
     * Within the [Sarif] context, this represents a value, i.e., something that (usually) comes from the SMT model.
     * The frontend will highlight this in a grey box.
     *
     * @param values the different representations of the value we're representing (the representations are listed in
     *   [AlternativeRepresentations.Representations], PRETTY must always be present, the others not)
     * @param tooltip the tooltip, if any, that should be shown when hovering over the value in the call trace; it is an
     *   invariant that when [truncatable] is `true`, the front end will use this text in the tooltip; otherwise, the
     *   tooltip may be used to display the untruncated value instead of whatever is in this field.
     * @param type a short representation of the displayed value's type; currently not propagated to the json
     * @param truncatable if this is true, the [Representations.Pretty] entry to [values] can be truncated by the
     *   frontend (in practice, currently, this always means it is a hexadecimal number)
     *
     * Notes on [truncatable]:
     *  This signals that values[0] has a (hex) value like 0x23320404 that can be truncated by inserting dots in the
     *  middle (if it is long enough).
     *  We implicitly add a contract on the contents of tool tip here: if truncatable is set, the tooltip must have an
     *  entry that makes sense in the context "<tooltip> = <full hex value from values[0]>".
     *  If [truncatable] is not set, we expect the front end to only display the tooltip (without "= <value>" suffix).
     */
    data class Arg(
        val values: AlternativeRepresentations.RepresentationsMap,
        val tooltip: String?,
        val type: String?,
        val truncatable: Boolean,
    ) : TreeViewReportable {

        override val treeViewRepBuilder get() = TreeViewRepJsonObjectBuilder {
            val arg = this@Arg

            put(CallTraceAttribute.VALUE(), arg.values[Pretty]) // legacy, for backwards compatibility

            if (altReprsInTreeView) {
                put(
                    CallTraceAttribute.VALUES(),
                    buildJsonArray { arg.values.asRepList().forEach { add(it) } }
                )
            }
            arg.tooltip?.let { put(CallTraceAttribute.TOOLTIP(), it) }
            // arg.type?.let { put(CallTraceAttribute.TYPE(), it) } // not printing them for now -- bring them back if/when they're used
            put(CallTraceAttribute.TRUNCATABLE(), truncatable)
        }

        /** A [Sarif] containing this [Arg] surrounded by two empty strings. */
        val asSarif get() = mkSarif { append(this@Arg) }
    }

    companion object {
        /**
         * constructs a [Sarif] without args or placeholders.
         * NOTE: caller asserts that [str] contains no placeholders.
         */
        fun fromPlainStringUnchecked(str: String): Sarif = Sarif(pieces = listOf(str), args = emptyList())

        /** the string used to the separate an expression from its value */
        const val EVALUATES_TO: String = " ↪ "
    }
}
