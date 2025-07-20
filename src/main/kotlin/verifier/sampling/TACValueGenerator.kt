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

package verifier.sampling

import datastructures.Bijection
import evm.MAX_EVM_UINT256
import vc.data.state.ConcreteTACState
import vc.data.state.TACValue
import java.math.BigInteger

abstract class TACValueGenerator {

    /**
     * UserValue oracle reads its initial state from a file.
     * Other oracles need ConcreteTACState.
     */
    open fun getInitialState(fileName : String = "")  = ConcreteTACState(emptyMap(), emptySet(), Bijection())

    abstract fun getUnsignedRandomBit256(maxValue: BigInteger = MAX_EVM_UINT256): TACValue.PrimitiveValue.Integer
}
