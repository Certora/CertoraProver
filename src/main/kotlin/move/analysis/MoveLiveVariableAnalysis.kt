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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package move.analysis

import datastructures.stdcollections.*
import analysis.dataflow.GenericLiveVariableAnalysis
import move.*
import utils.*
import vc.data.*

class MoveLiveVariableAnalysis private constructor(val g: MoveTACCommandGraph)
    : GenericLiveVariableAnalysis<TACCmd, MoveTACProgram.LCmd, MoveTACProgram.Block, MoveTACCommandGraph>(g, cmdFilter = { _ -> true}, MoveBlockView) {
        companion object : AnalysisCache.Key<MoveTACCommandGraph, MoveLiveVariableAnalysis> {
            override fun createCached(graph: MoveTACCommandGraph): MoveLiveVariableAnalysis = MoveLiveVariableAnalysis(graph)

        }

    // For simplicity and soundness
    // We ensure that any *read* of a borrow
    // is considered a read of any location that has been borrowed. (over-approx of uses)
    // Any *write* through a reference must not be considered a write
    // of any location that is ever borrowed. (under-approx of defs)
    private val borrowedLocations: Collection<TACSymbol.Var> = g.commands.mapNotNullToTreapSet {
        when (val c = it.cmd) {
            is TACCmd.Move.BorrowLocCmd -> c.loc
            else -> null
        }
    }

    override fun reads(lcmd: MoveTACProgram.LCmd): Collection<TACSymbol.Var> =
        when (val c = lcmd.cmd) {
            is TACCmd.Simple -> c.getFreeVarsOfRhsExtended()
            is TACCmd.Move.WriteRefCmd -> {
                val t = c.ref.tag
                if (t is MoveTag.Ref && t.toMoveType() is MoveType.Primitive) {
                    // If it's a primitive, then we don't need to consider this a
                    // read + update + write
                    setOf(c.ref)
                } else {
                    // otherwise we're effectively grabbing the whole location, updating
                    // a piece of it, and writing it back
                    borrowedLocations + c.ref
                }
            }
            is TACCmd.Move.ReadRefCmd -> borrowedLocations + c.ref
            is TACCmd.Move.VecLenCmd -> borrowedLocations + c.ref
            is TACCmd.Move.VecPopBackCmd -> borrowedLocations + c.ref
            is TACCmd.Move.VecPushBackCmd -> borrowedLocations + c.ref
            is TACCmd.Move -> c.getFreeVarsOfRhs()
            else -> `impossible!`
        }

    override fun writes(lcmd: MoveTACProgram.LCmd): Collection<TACSymbol.Var> {
        val c = lcmd.cmd
        return when {
            c is TACCmd.Simple.AssigningCmd -> setOf(c.lhs)
            c is TACCmd.Simple.SummaryCmd && c.summ is AssigningSummary -> c.summ.mustWriteVars
            c is TACCmd.Simple -> setOf()
            c is TACCmd.Move ->
                // Crucially, WriteRef's modified vars is empty, which
                // is a conservative underapproximation
                c.modifiedVars
            else -> `impossible!`
        }
    }

}
