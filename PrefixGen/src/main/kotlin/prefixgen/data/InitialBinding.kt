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

import bridge.types.SolidityTypeDescription
import utils.AmbiSerializable
import utils.KSerializable

/**
 * Indicates a binding created by the user provided `setUp` function.
 * [path] indicates a file that needs to be imported for the binding to type check;
 * [ty] is the type of the binding created.
 *
 * The actual name of the binding isn't included here, rather, the [prefixgen.SequenceGenerator]
 * has a map of identifiers to [InitialBinding].
 */
@KSerializable
data class InitialBinding(
    val path: String?,
    val ty: SolidityTypeDescription
) : AmbiSerializable
