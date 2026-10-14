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

package analysis.icfg

import annotation.SolidityVersion
import annotation.SolidityVersions
import annotations.TestTags.EXPENSIVE
import decompiler.BMCRunner
import evm.EVM_WORD_SIZE
import instrumentation.calls.CalldataEncoding
import loaders.SoliditySceneTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import scene.ITACMethod
import scene.Scene
import spec.cvlast.SolidityContract
import vc.data.CoreTACProgram
import vc.data.TACCmd
import java.math.BigInteger

/**
 * A method that forwards its whole calldata buffer to another contract reads that buffer at word granularity,
 * so its [CalldataEncoding] holds word aligned byte ranges alongside the ABI aligned ones. Setting up a call
 * to such a method has to bind those ranges as well.
 */
@Tag(EXPENSIVE)
class CalldataForwardingProxyTest : SoliditySceneTest {
    @ParameterizedTest
    @SolidityVersions([SolidityVersion.V8_13, SolidityVersion.V8_28])
    fun wordAlignedRangesAreBoundFromTheCallInput(solc: String) {
        val scene = loadScene("icfg/CalldataForwardingProxy.sol", solc)
        scene.mapContractMethodsInPlace("unrolling") { _, m ->
            m.code = BMCRunner(1, m.code as CoreTACProgram).bmcUnroll()
        }

        val proxyMethod = scene.methodOf("ViewProxy", "readValue")
        val encoding = proxyMethod.calldataEncoding as CalldataEncoding
        val wordAlignedScalars = encoding.byteOffsetToScalar.filterKeys {
            it.from > BigInteger.ZERO && it.from.mod(EVM_WORD_SIZE) == BigInteger.ZERO
        }.values
        Assertions.assertTrue(wordAlignedScalars.isNotEmpty()) {
            "Expected a word aligned calldata range in ${encoding.byteOffsetToScalar}"
        }

        val callerMethod = scene.methodOf("Caller", "callReadValue")
        val analysis = CallGraphBuilder.doSigAnalysis(callerMethod)
            ?: Assertions.fail("Call graph analysis of Caller-callReadValue failed")
        val input = analysis.callInputResolution.resolution.values.singleOrNull()
            ?: Assertions.fail("Expected one resolved call input, got ${analysis.callInputResolution.resolution}")

        val setup = encoding.feedInput(input, proxyMethod)
        val assigned = setup.cmds.mapNotNullTo(mutableSetOf()) { (it as? TACCmd.Simple.AssigningCmd)?.lhs }
        Assertions.assertTrue(assigned.containsAll(wordAlignedScalars)) {
            "Call setup left ${wordAlignedScalars - assigned} unbound"
        }
    }

    private fun Scene.methodOf(contract: String, method: String): ITACMethod =
        getContractOrNull(SolidityContract(contract))?.getMethods()?.firstOrNull { it.name == method }
            ?: Assertions.fail("Could not find $contract-$method in $this")
}
