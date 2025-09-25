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

import analysis.CmdPointer
import vc.data.TACSymbol

/**
 * Basic external interface for a long read. Includes [where]
 * the long read occurs, it's byte location [loc] within memory,
 * and the [length] of the buffer. These are the *original* symbols used in the
 * original command, *not* the prophecy variables.
 */
internal sealed interface ILongRead {
    val where: CmdPointer
    val loc: TACSymbol
    val length: TACSymbol
    val id: Int
}
