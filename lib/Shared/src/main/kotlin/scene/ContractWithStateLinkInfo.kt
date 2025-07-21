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

package scene

import java.math.BigInteger

/**
 * A wrapper around a contract that contains state links information (as resolved with `contract.getContractStateLinks` when it's not null)
 * The map [stateLinks] maps storage slot numbers to the address linked.
 */
data class ContractWithStateLinkInfo(
    val contract: IContractClass,
    val stateLinks: Map<BigInteger, BigInteger>,
    val legacyStructLinking: Map<BigInteger, BigInteger>,
    val structLinkingInfo: Map<String, BigInteger>
) {
    fun isEmpty() = stateLinks.isEmpty() && legacyStructLinking.isEmpty() && structLinkingInfo.isEmpty()
}
