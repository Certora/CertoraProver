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

import bridge.types.PrimitiveType
import com.certora.collect.*
import utils.*

/**
 * Indicates an array helper function which generates an dynamic array
 * of length [arity] with element type [ty].
 *
 * For example, [ArrayPrototype] (3, uint) will generate:
 * ```
 * array_lit_uint_3(uint e1, uint e2, uint e3) internal returns (uint[] memory) { ... }
 * ```
 *
 * These are used for constructing [SimpleSolidityAst.BindArray].
 */
@KSerializable
@Treapable
data class ArrayPrototype(
    val arity: Int,
    val ty: PrimitiveType
) : AmbiSerializable {
    val funcIdent get() = "array_lit_${ty.name}_$arity"

    override fun hashCode(): Int = hash { it + ty + arity }
    override fun equals(other: Any?) = other is ArrayPrototype && other.arity == this.arity && other.ty == this.ty
}
