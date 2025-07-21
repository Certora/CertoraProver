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
 * An argument decoded by one of the methods
 */
data class Argument(
    /**
     * Which argument number, from 0
     */
    val ordinal: Int,
    /**
     * The name of the argument (in methodA)
     */
    val name: String,
    /**
     * The name of the argument in methodB (if different from A)
     */
    val altName: String?,
    /**
     * The string representation of said argument
     */
    val value: String
)
