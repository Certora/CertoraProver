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

package tac

import java.math.BigInteger

object TACBasicMeta {
    val LAST_REVERTED = MetaKey.Nothing("tac.last.reverted")
    val STACK_HEIGHT = MetaKey<Int>("tac.stack.height")
    val IS_IMMUTABLE = MetaKey.Nothing("tac.is-immutable")

    /**
     * The name of the "owner" of the immutable field, i.e., the name of the
     * contract that declares the immutable
     */
    val IMMUTABLE_OWNER = MetaKey<String>("tac.immut.owner")

    /**
     * Hardcoded value used to link through immutables
     */
    val IMMUTABLE_LINK = MetaKey<BigInteger>("tac.immut.value")

    /**
     * The name of the immutable field itself. Both of these should appear on a symbol with `IS_IMMUTABLE`.
     */
    val IMMUTABLE_NAME = MetaKey<String>("tac.immut.name")
    val IS_TMP_METAKEY = MetaKey.Nothing("tac.is-temp-var")
    val DECOMP_INPUTSCALAR_SORT =
        MetaKey<DecomposedInputScalarSort>("tac.decomp-inscalar.sort")
    val VALUE = MetaKey<BigInteger>("tac.value.of.var")
}
