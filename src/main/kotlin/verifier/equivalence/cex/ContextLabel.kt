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

/**
 * Display information
 */
sealed interface ContextLabel {
    val displayLabel: String
    val description: String

    enum class LogLabel(override val displayLabel: String, override val description: String) : ContextLabel {
        LOG_TOPIC1("Topic 1", "The first log topic (usually the hash of the event signature)"),
        LOG_TOPIC2("Topic 2", "The second log topic (the first indexed event parameter)"),
        LOG_TOPIC3("Topic 3", "The third log topic (the second indexed event parameter)"),
        LOG_TOPIC4("Topic 4", "The fourth log topic (the third indexed event parameter)")
    }

    enum class ExternalCallLabel(override val displayLabel: String, override val description: String) : ContextLabel {
        CALL_VALUE("Value", "Ether value sent with call in wei"),
        CALLEE("Callee address", "Target account of external call"),
        CALLEE_CODESIZE("Callee codesize", "Non-deterministically chosen codesize of the target account"),
        RETURNSIZE("Result Buffer Size", "Size, in bytes, of the return/revert buffer"),
        CALL_RESULT("Call Result", "Whether the external call reverted or returned successfully"),
        RETURNDATA("Return Data", "(Partial) model of the returndata from the external call")
    }

    val Int.naturalString : String get() {
        val naturalPos = this + 1
        return naturalPos.toString() + when(naturalPos.mod(10)) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

    sealed interface InternalCallLabel : ContextLabel {
        data object Signature : InternalCallLabel {
            override val displayLabel: String
                get() = "Internal function signature"
            override val description: String
                get() = "The fully qualified signature of a common pure function"
        }

        data class Arg(val name: String, val tooltip: String) : InternalCallLabel {
            override val displayLabel: String
                get() = name
            override val description: String
                get() = tooltip
        }

        data class ReturnValue(val ord: Int) : InternalCallLabel {
            override val displayLabel: String
                get() {
                    return "${ord.naturalString} Return"
                }

            override val description: String
                get() = "The $displayLabel value returned by the function"
        }
    }

    data class ResultValue(val ord: Int) : ContextLabel {
        override val displayLabel: String
            get() = "${ord.naturalString} Result"
        override val description: String
            get() = "The ${ord.naturalString} value produced by the computation"

    }
}
