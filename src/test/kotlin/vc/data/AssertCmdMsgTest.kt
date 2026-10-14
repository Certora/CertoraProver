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

package vc.data

import cli.Ecosystem
import config.Config
import config.ConfigScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.MetaMap

class AssertCmdMsgTest {

    private fun userDefinedAssert(description: String) =
        TACCmd.Simple.AssertCmd(TACSymbol.True, description, MetaMap(TACMeta.CVL_USER_DEFINED_ASSERT))

    private fun proverAssert(description: String) =
        TACCmd.Simple.AssertCmd(TACSymbol.True, description)

    @Test
    fun `a description that fits is given as is`() {
        for (ecosystem in Ecosystem.entries) {
            ConfigScope(Config.ActiveEcosystem, ecosystem).use {
                assertEquals(SHORT_MESSAGE, userDefinedAssert(SHORT_MESSAGE).msg, "in $ecosystem")
            }
        }
    }

    @Test
    fun `the description of an assertion of the user is trimmed`() {
        for (ecosystem in Ecosystem.entries.filter { it != Ecosystem.SOLANA }) {
            ConfigScope(Config.ActiveEcosystem, ecosystem).use {
                assertEquals(SHORT_MESSAGE, userDefinedAssert("\"$SHORT_MESSAGE\"").msg, "in $ecosystem")
                assertEquals(LONG_MESSAGE.take(40) + "...", userDefinedAssert(LONG_MESSAGE).msg, "in $ecosystem")
            }
        }
    }

    @Test
    fun `the description of an assertion of the Solana flow is given as is`() {
        ConfigScope(Config.ActiveEcosystem, Ecosystem.SOLANA).use {
            assertEquals("\"$SHORT_MESSAGE\"", userDefinedAssert("\"$SHORT_MESSAGE\"").msg)
            assertEquals(LONG_MESSAGE, userDefinedAssert(LONG_MESSAGE).msg)
        }
    }

    /** The Prover words the asserts it adds itself, so there is nothing to compensate for. */
    @Test
    fun `the description of an assert of the Prover is given as is`() {
        for (ecosystem in Ecosystem.entries) {
            ConfigScope(Config.ActiveEcosystem, ecosystem).use {
                assertEquals(LONG_MESSAGE, proverAssert(LONG_MESSAGE).msg, "in $ecosystem")
            }
        }
    }

    private companion object {
        const val SHORT_MESSAGE = "does not hold"
        const val LONG_MESSAGE = "an assertion message that is well over forty characters long"
    }
}
