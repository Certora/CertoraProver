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

import scene.ContractClass
import java.math.BigInteger

/**
 * Information about the call into the two functions.
 * [messageValue] is the value of `msg.value`,
 * [sender] is `msg.sender`, [argumentFormatting] is the list of
 * argument data extracted from the method bodies
 */
data class InputExplanation(
    val messageValue: BigInteger?,
    val sender: BigInteger?,
    val argumentFormatting: List<Argument>
) {
    fun prettyPrint(altContract: ContractClass): String {
        val msg = "Input parameters:\n" +
            "\tSender: ${sender?.toString(16) ?: "Unknown"}\n" +
            "\tCall value: ${messageValue ?: "Unknown"}\n" +
            "\tDecoded arguments:\n" +
            argumentFormatting.joinToString("\n") { arg ->
                val altName = if(arg.altName != null && arg.altName != arg.name) {
                    " (in ${altContract.name}: ${arg.altName})"
                } else {
                    ""
                }
                "\t\t -> ${arg.name}$altName = ${arg.value}"
            }.ifBlank {
                "\t\tNone found"
            }
        return msg
    }
}
