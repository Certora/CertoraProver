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

package move

import analysis.NonTrivialDefAnalysis
import datastructures.stdcollections.*
import move.CvlmApi.MESSAGE_VAR
import move.MoveToTAC.Companion.CONST_STRING
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach

object ConstantStringPropagator {
    /**
        Propagates constant string values to commands that understand them.  For now, this is just AssertCmd and
        AssumeCmd.

        This must run *before* DSA; otherwise, DSA will discard the original string variable if it is only used in the
        assert/assume command's MetaMap.
     */
    fun transform(code: CoreTACProgram): CoreTACProgram {
        val def = NonTrivialDefAnalysis(code.analysisCache.graph)
        return code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            val messageVar = cmd.meta[MESSAGE_VAR] ?: return@mapNotNull null
            val messageDef = def.getDefCmd<TACCmd.Simple.AssigningCmd>(messageVar, ptr) ?: return@mapNotNull null
            val messageString = messageDef.cmd.lhs.meta[CONST_STRING] ?: return@mapNotNull null
            ptr `to?` when(cmd) {
                is TACCmd.Simple.AssertCmd -> cmd.copy(description = messageString, meta = cmd.meta - MESSAGE_VAR)
                is TACCmd.Simple.AssumeCmd -> cmd.copy(msg = messageString, meta = cmd.meta - MESSAGE_VAR)
                else -> error("Unexpected command type: ${cmd::class.simpleName} with MESSAGE_VAR meta")
            }
        }.patchForEach(code) { (ptr, cmd) ->
            replaceCommand(ptr, listOf(cmd))
        }
    }
}
