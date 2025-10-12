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

import com.certora.collect.Treapable
import utils.AmbiSerializable
import utils.KSerializable

/**
 * A "symbolic" expression. The "symbolic" part
 * doesn't really mean anything; it refers to an older design.
 */
@Treapable
@KSerializable
sealed interface SymExp : AmbiSerializable {
    /**
     * A source identifier in scope.
     */
    @KSerializable
    data class Symbol(
        @SoliditySourceIdentifier
        val rawId: String,
    ) : SymExp

    /**
     * A struct literal, pretty printed as
     * ```
     * structName(
     *    [[ fields[0] ]],
     *    [[ fields[1] ]],
     *    ...
     * )
     * ```
     * where `[[ e ]]` denotes the pretty printing of the [SymExp] `e`
     */
    @KSerializable
    data class StructLiteral(
        val structName: String,
        val fields: List<SymExp>
    ) : SymExp

    /**
     * A Static array literal of [elems] which are expected to have
     * type [elemTy]. The Static Array type inference in Solidity is "wonky",
     * so we explicitly wrap the denotation of [elems] in explicit type casts
     * to [elemTy] to avoid any ambiguity.
     */
    @KSerializable
    data class StaticArrayLiteral(
        val elems: List<SymExp>,
        val elemTy: String
    ) : SymExp
}
