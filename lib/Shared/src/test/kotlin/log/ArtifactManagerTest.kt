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

package log

import log.ArtifactManagerFactory.WithArtifactMode.WithoutArtifacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import utils.ArtifactFileUtils

class ArtifactManagerTest {
    @OptIn(ArtifactManagerFactory.UsedOnlyInTests::class)
    @Test
    fun `long presolver artifact name fits conservative filesystem limit`() {
        val name =
            "PresolverRule-totalSupplyEqualsAggregateBalances-Using general requirements-" +
                "transferFromLPADRCADRCU256RP-invariant_not_trivial_postcondition.tac"

        val fittedName = ArtifactManagerFactory(WithoutArtifacts).fitFileLength(name, ".tac")

        assertTrue(fittedName.length <= ArtifactFileUtils.MAX_FILE_NAME_LENGTH)
        assertTrue(fittedName.endsWith(".tac"))
        assertEquals(ArtifactFileUtils.MAX_FILE_NAME_LENGTH, fittedName.length)
    }
}
