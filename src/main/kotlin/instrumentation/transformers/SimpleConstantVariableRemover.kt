/*
 *     The Certora Prover
 *     Copyright (C) 2026 Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package instrumentation.transformers

import algorithms.*
import analysis.*
import datastructures.stdcollections.*
import java.util.stream.*
import kotlin.streams.*
import tac.*
import utils.*
import vc.data.*

/**
    Finds variables that are only ever assigned a constant value.  Replaces their uses with the constant value, when
    possible, and otherwise consolidates all such variables into one variable per constant value.

    This is meant to be a fast way to reduce the number of variables, specifically the number with common constant
    values, to improve the performance of later passes that use more sophisticated methods, like GlobalValueNumbering.
 */
object SimpleConstantVariableRemover {
    fun transform(code: CoreTACProgram): CoreTACProgram {
        //
        // Find all variables that are only assigned once, and that assignment is from a constant value.
        //
        val consts = code.parallelLtacStream().flatMap { lcmd ->
            when {
                lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd -> when (val rhs = lcmd.cmd.rhs) {
                    is TACExpr.Sym.Const -> Stream.of(lcmd.cmd.lhs to (lcmd.ptr to rhs.s).toLeft())
                    else -> Stream.of(lcmd.cmd.lhs to "Assignment from non-constant".toRight())
                }
                lcmd.cmd is TACCmd.Simple.SummaryCmd && lcmd.cmd.summ is AssigningSummary -> {
                    (lcmd.cmd.summ.mayWriteVars.toSet() + lcmd.cmd.summ.mustWriteVars).stream().map {
                        it to "Assignment from summary".toRight()
                    }
                }
                else -> lcmd.cmd.getLhs()?.let { Stream.of(it to "Assignment from other".toRight()) } ?: Stream.empty()
            }
        }.collect(
            Collectors.toConcurrentMap(
                { it.first },
                { it.second },
                { _, _ -> "Multiple assignments".toRight() }
            )
        ).mapNotNull { (v, c) -> v `to?` c.leftOrNull() }.toMap()

        //
        // Replace all uses of these variables, and delete the original assignments.
        //
        val newConsts = mutableMapOf<TACSymbol.Const, TACSymbol.Var>()
        val replaced = with(code.analysisCache.domination) {
            object : DefaultTACCmdMapperWithPointer() {
                override fun mapCommand(c: LTACCmd): List<TACCmd.Simple> {
                    return if (c.cmd.getLhs() in consts) {
                        emptyList()
                    } else {
                        super.mapCommand(c)
                    }
                }

                override fun mapSymbol(t: TACSymbol) = consts[t]?.let { (defPtr, c) ->
                    // Check that the variable is defined at this point in the program
                    c.takeIf { defPtr strictlyDominates currentPtr!! }
                } ?: t

                override fun mapVar(t: TACSymbol.Var) = consts[t]?.let { (defPtr, c) ->
                    // Check that the variable is defined at this point in the program
                    runIf(defPtr strictlyDominates currentPtr!!) {
                        // We can't map a var to a const here; but we can consolidate all constants to a single var
                        // each.
                        newConsts.computeIfAbsent(c) {
                            TACSymbol.Var("Const_${c.tag}_0x${c.value.toString(16)}", c.tag).toUnique("!").also {
                                decls.add(it)
                            }
                        }
                    }
                } ?: t
            }.mapCommands(code)
        }

        //
        // If we introduced any new variables, initialize them to their constant values
        //
        return replaced.letIf(newConsts.isNotEmpty()) {
            it.patching { patch ->
                patch.addBefore(
                    CmdPointer(entryBlockId, 0),
                    newConsts.entries.map { (c, v) ->
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(v, c.asSym())
                    }
                )
            }
        }
    }
}
