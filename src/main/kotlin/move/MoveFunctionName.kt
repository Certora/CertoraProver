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

package move

import com.certora.collect.*
import utils.*

/**
    Identifies a Move function by its defining module and name.

    Note that the Move VM does not support function overloading, so the simple name is sufficient to uniquely identify a
    function within its defining module.
 */
@Treapable
@KSerializable
data class MoveFunctionName(
    val module: MoveModuleName,
    val simpleName: String
) : AmbiSerializable {
    override fun toString() = "$module::$simpleName"
    fun toVarName() = "${module.toVarName()}\$${simpleName}"
}
