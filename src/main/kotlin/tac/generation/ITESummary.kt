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

package tac.generation

import analysis.*
import com.certora.collect.*
import datastructures.stdcollections.*
import kotlin.streams.*
import tac.*
import utils.*
import vc.data.*

/** Summary of an if/then/else statement, with deferred materialization. */
@KSerializable
abstract class ITESummary : AssignmentSummary() {
    override val annotationDesc get() = "ITE"

    protected abstract val cond: TACExpr
    protected abstract val trueWriteVars: Set<TACSymbol.Var>
    protected abstract val falseWriteVars: Set<TACSymbol.Var>

    override val mustWriteVars get() = trueWriteVars intersect falseWriteVars
    override val mayWriteVars get() = (trueWriteVars + falseWriteVars) - mustWriteVars

    protected abstract fun onTrue(): CommandWithRequiredDecls<TACCmd.Simple>
    protected abstract fun onFalse(): CommandWithRequiredDecls<TACCmd.Simple>

    companion object {
        fun materialize(prog: CoreTACProgram): CoreTACProgram {
            val replacements = prog.parallelLtacStream().mapNotNull { lcmd ->
                lcmd.ptr `to?` lcmd.snarrowOrNull<ITESummary>()
            }.toList()

            if (replacements.isEmpty()) {
                return prog
            }

            val patched = prog.patching { patch ->
                for ((ptr, op) in replacements) {
                    val continuation = patch.splitBlockAfter(ptr)

                    val thenCmds = op.onTrue().merge(TACCmd.Simple.JumpCmd(continuation))
                    val elseCmds = op.onFalse().merge(TACCmd.Simple.JumpCmd(continuation))

                    val thenBlock = patch.addBlock(ptr.block, thenCmds)
                    val elseBlock = patch.addBlock(ptr.block, elseCmds)

                    val ifCmds = op.cond.letVar(Tag.Bool) {
                        TACCmd.Simple.JumpiCmd(thenBlock, elseBlock, it.s).withDecls()
                    }

                    patch.replaceCommand(ptr, ifCmds.cmds, treapSetOf(thenBlock, elseBlock))

                    patch.addVarDecls(ifCmds.varDecls + thenCmds.varDecls + elseCmds.varDecls)
                }
            }

            // Recursively transform the program to allow nested ITEs
            return materialize(patched)
        }
    }
}
