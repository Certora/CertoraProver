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
import com.certora.collect.Treapable
import prefixgen.CodeGen.pp
import utils.AmbiSerializable
import utils.KSerializable

/**
 * Indicates a variable with identifier [rawId] and type [ty].
 * This *doesn't* necessarily mean we have a literal statement defining this
 * variable in the body of the fuzz test; some variables may be bound in storage.
 */
@Treapable
@KSerializable
data class VarDef(
    val ty: SolidityTypeDescription,
    @SoliditySourceIdentifier
    val rawId: String
) : AmbiSerializable {
    val asDecl get() = "${ty.pp()} $rawId"
}
