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

import vc.data.TACSymbol
import java.math.BigInteger

/**
 * Representation of a location for a value: either a static slot in
 * memory, or a [TACSymbol.Var]
 */
sealed interface ProgramValueLocation {
    data class MemoryCell(val idx: BigInteger): ProgramValueLocation
    data class StackVar(val which: TACSymbol.Var): ProgramValueLocation

}
