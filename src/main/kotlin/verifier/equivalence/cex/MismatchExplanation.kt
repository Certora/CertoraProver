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
 * An explanation for why the traces differ
 */
sealed interface MismatchExplanation {
    /**
     * There was an [eventInB] which did not appear in [methodA]
     */
    data class MissingInA(
        val eventInB: EventWithData
    ) : MismatchExplanation

    /**
     * There was an [eventInA] that did not appear in [methodB]
     */
    data class MissingInB(
        val eventInA: EventWithData
    ) : MismatchExplanation

    /**
     * The kth event was different across the methods, [eventInA] vs [eventInB]
     */
    data class DifferentEvents(
        val eventInA: EventWithData,
        val eventInB: EventWithData
    ) : MismatchExplanation

    /**
     * Same events, but storage was left in different states. [slot] is the witness to this
     * difference, and the differences are described in [contractAValue] and [contractBValue]
     */
    data class StorageExplanation(
        val slot: String,
        val contractAValue: String,
        val contractBValue: String
    ) : MismatchExplanation
}
