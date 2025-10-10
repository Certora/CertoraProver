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
 * Represents a concretized fuzz test, which makes a series of calls
 * and bindings.
 *
 * [storageVars] are the storage variables bound by this prefix; these can come from external calls
 * or from literal values extracted from the fuzz counterexample.
 *
 * [setup] is a sequence of literal Solidity syntax that effectts the bindings of [storageVars] and the external calls.
 * The exact sequence of external calls in [setup] is represented by [currCalls], which is a list of sighashes.
 */
@Treapable
@KSerializable
data class FixedPrefix(
    val storageVars: Set<VarDef>,
    val setup: List<String>,
    val currCalls: List<String>
) : AmbiSerializable
