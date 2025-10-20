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

import analysis.*
import com.certora.collect.*
import datastructures.stdcollections.*
import move.MoveToTAC.Companion.CONST_STRING
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach

/**
    Propagates constant string values to commands that understand them.  We use this to get the user-provided
    messages for asserts and assumes.

    Each command that wants to have a message should have a meta entry with key [MESSAGE_VAR], whose [MessageVar] value
    contains the variable that holds the message string.  The command should also be followed by an `AnnotationCmd` with
    the same key/value, so that the constant string variable is preserved across DSA (or other) transforms.
 */
object ConstantStringPropagator {
    val MESSAGE_VAR = MetaKey<MessageVar>("move.message")

    data class MessageVar(val sym: TACSymbol.Var) : TransformableVarEntityWithSupport<MessageVar> {
        override val support get() = treapSetOf(sym)
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = MessageVar(f(sym))
    }

    fun transform(code: CoreTACProgram): CoreTACProgram {
        val def = NonTrivialDefAnalysis(code.analysisCache.graph)
        return code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            // Remove MESSAGE_VAR annotations
            if (cmd.maybeAnnotation(MESSAGE_VAR) != null) { return@mapNotNull ptr to null }

            val messageVar = cmd.meta[MESSAGE_VAR]?.sym ?: return@mapNotNull null
            val messageDef = def.getDefCmd<TACCmd.Simple.AssigningCmd>(messageVar, ptr) ?: return@mapNotNull null
            val messageString = messageDef.cmd.lhs.meta[CONST_STRING] ?: return@mapNotNull null
            val newCmd = when(cmd) {
                is TACCmd.Simple.AssertCmd -> cmd.copy(description = messageString, meta = cmd.meta - MESSAGE_VAR)
                is TACCmd.Simple.AssumeCmd -> cmd.copy(msg = messageString, meta = cmd.meta - MESSAGE_VAR)
                is TACCmd.Simple.AnnotationCmd -> when (val annot = cmd.annot.v) {
                    is MoveCallTrace.Assume -> MoveCallTrace.Assume(messageString, annot.range).toAnnotation()
                    is MoveCallTrace.Assert -> MoveCallTrace.Assert(annot.isSatisfy, annot.condition, messageString, annot.range).toAnnotation()
                    is MessageVar -> return@mapNotNull null // remove the message var annotation
                    else -> null
                }?.withMeta(cmd.meta - MESSAGE_VAR)
                else -> null
            } ?: error("Unexpected command with MESSAGE_VAR meta: $cmd at $ptr")
            ptr to newCmd
        }.patchForEach(code) { (ptr, cmd) ->
            replaceCommand(ptr, listOfNotNull(cmd))
        }
    }
}
