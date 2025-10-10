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

package prefixgen.data

import com.certora.collect.*
import utils.AmbiSerializable
import utils.KSerializable

/**
 * An reference to an existing program [id] which is "type compatible"
 * with some other variable. "type compatible" means there is some *non-lossy* way
 * to transform [id] into the expected type. This conversion is done via [syntaxFormat],
 * which is a formatting string into which [id] is interpolated.
 *
 * For example, a `bytes3` can be converted into a string via the format string:
 * ```
 * string(abi.encodePacked(%s))
 * ```
 *
 * NB this is non-lossy because we can always recover the original value by doing:
 * `bytes3(bytes(string(abi.encodePacked(%s))))`.
 * NB that there is no lossy conversion from strings to bytes3, as we may truncate information
 * from the string.
 */
@KSerializable
@Treapable
data class Alias(
    @SoliditySourceIdentifier
    val id: Identifier,
    val syntaxFormat: String
) : AmbiSerializable {
    fun apply() = this.syntaxFormat.format(id)
}
