/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

import bridge.CertoraConf
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import scene.source.CertoraBuilderContractSource
import java.math.BigInteger
import java.nio.file.Path

class SceneCacheKeyTest {

    /**
     * Loads the .certora_build.json at [path] and computes its scene cache key. A fixed dummy
     * [BigInteger.ZERO] base key isolates the per-contract bytecode/config contribution (the real
     * base key is config-derived, identical for both fixtures, and would otherwise just cancel out).
     */
    private fun cacheKeyForBuildFile(path: Path): String {
        val source = CertoraBuilderContractSource(CertoraConf.loadBuildConf(path))
        return computeSceneKey(BigInteger.ZERO, source.instances())
    }

    private fun resource(name: String): Path =
        Path.of(javaClass.classLoader.getResource(name)!!.toURI())

    /**
     * The two fixtures are minimized .certora_build.json files for the same contract, differing only
     * in the CBOR metadata (IPFS hash) appended to the runtime bytecode. Stripping the CBOR before
     * hashing must make their scene cache keys identical.
     */
    @Test
    fun twoBuildFilesProduceSameSceneKey() {
        val keyA = cacheKeyForBuildFile(resource("scene/cbor_a.certora_build.json"))
        val keyB = cacheKeyForBuildFile(resource("scene/cbor_b.certora_build.json"))
        Assertions.assertEquals(keyA, keyB, "Scene cache keys differ despite metadata-only difference")
    }
}
