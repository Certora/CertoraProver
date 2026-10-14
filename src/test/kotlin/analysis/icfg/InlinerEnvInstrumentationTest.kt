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

import com.certora.collect.*
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tac.*
import vc.data.*
import vc.data.parser.compareCoreTACPrograms
import vc.data.parser.serializeTAC
import vc.data.tacexprutil.subs
import java.io.File

/**
 * The inliner instruments a callee whose sighash it could not resolve with a `msg.sig` read out of the calldata
 * buffer. A TAC dump does not spell out the [Tag] of a compound expression - the reader recovers it by type
 * checking - so such a command only survives a dump/parse round trip if it is tagged to begin with.
 */
class InlinerEnvInstrumentationTest {

    private val calldata = TACKeyword.CALLDATA.toVar(callIndex = 1)
    private val sighash = TACKeyword.SIGHASH.toVar(callIndex = 1)

    @Test
    fun sighashExpressionIsFullyTagged() {
        val exp = Inliner.sighashFromCalldata(calldata)
        val untagged = exp.subs.filter { it.tag == null }.toList()
        Assertions.assertTrue(untagged.isEmpty()) { "untagged subexpressions of $exp: $untagged" }
    }

    /**
     * The program is built with `check = false`, as programs are by the time the inliner gets them: type checking
     * happens once, when a program is first built, and would otherwise tag the expression for us and hide the bug.
     */
    @Test
    fun sighashAssignmentSurvivesSerializationRoundTrip(@TempDir dir: File) {
        val cmd = TACCmd.Simple.AssigningCmd.AssignExpCmd(sighash, Inliner.sighashFromCalldata(calldata))
        val block = BlockIdentifier(0, 0, 0, 0, 0, 0)
        val prog = CoreTACProgram(
            mapOf(block to listOf<TACCmd.Simple>(cmd, TACCmd.Simple.NopCmd)),
            BlockGraph(block to treapSetOf()),
            "sighashRoundTrip",
            TACSymbolTable.withVars(treapSetOf(sighash, calldata, TACKeyword.CALLDATASIZE.toVar(callIndex = 1))),
            InstrumentationTAC(UfAxioms.empty()),
            emptySet(),
            check = false
        )
        val path = File(dir, "sighashRoundTrip.tac").absolutePath
        serializeTAC(prog, path)
        val parsed = CoreTACProgram.fromStream(File(path).inputStream(), "sighashRoundTrip")
        Assertions.assertDoesNotThrow { compareCoreTACPrograms(prog, parsed) }
    }
}
