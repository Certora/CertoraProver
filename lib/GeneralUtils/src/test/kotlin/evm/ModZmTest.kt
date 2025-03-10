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

package evm

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import utils.modZ256
import utils.ModZm.Companion.to2s
import java.math.BigInteger

class ModZmTest {

    @Test
    fun sdivTest() {
        assertEquals(
            MIN_EVM_INT256_2S_COMPLEMENT,
            modZ256.sdiv(MIN_EVM_INT256_2S_COMPLEMENT, (-BigInteger.ONE).to2s())
        )
        assertEquals(
            BigInteger.ZERO,
            modZ256.sdiv(1000.toBigInteger(), BigInteger.ZERO)
        )
    }
}
