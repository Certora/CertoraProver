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

import com.certora.collect.*
import datastructures.stdcollections.*
import analysis.dataflow.GenericLiveVariableAnalysis
import move.*
import utils.*
import vc.data.*

/**
    Live varianble analysis, but specifically for Move references.  Much faster than [MoveLiveVariableAnalysis],
    but only tracks references, not all variables.
 */
class LiveReferenceAnalysis(
    val g: MoveTACCommandGraph
) : GenericLiveVariableAnalysis<TACCmd, MoveTACProgram.LCmd, MoveTACProgram.Block, MoveTACCommandGraph>(
    g, cmdFilter = { _ -> true}, MoveBlockView
) {
    private fun Iterable<TACSymbol.Var>.takeRefs() = toTreapSet().retainAll { it.tag is MoveTag.Ref }

    override fun reads(lcmd: MoveTACProgram.LCmd): Collection<TACSymbol.Var> =
        when (val c = lcmd.cmd) {
            is TACCmd.Simple -> c.getFreeVarsOfRhsExtended().takeRefs()
            is TACCmd.Move -> when (c) {
                is TACCmd.Move.BorrowLocCmd -> treapSetOf()
                is TACCmd.Move.ReadRefCmd -> treapSetOf(c.ref)
                is TACCmd.Move.WriteRefCmd -> treapSetOf(c.ref)
                is TACCmd.Move.PackStructCmd -> treapSetOf()
                is TACCmd.Move.UnpackStructCmd -> treapSetOf()
                is TACCmd.Move.BorrowFieldCmd -> treapSetOf(c.srcRef)
                is TACCmd.Move.VecPackCmd -> treapSetOf()
                is TACCmd.Move.VecUnpackCmd -> treapSetOf()
                is TACCmd.Move.VecLenCmd -> treapSetOf(c.ref)
                is TACCmd.Move.VecBorrowCmd -> treapSetOf(c.srcRef)
                is TACCmd.Move.VecPushBackCmd -> treapSetOf(c.ref)
                is TACCmd.Move.VecPopBackCmd -> treapSetOf(c.ref)
                is TACCmd.Move.PackVariantCmd -> treapSetOf()
                is TACCmd.Move.UnpackVariantCmd -> treapSetOf()
                is TACCmd.Move.VariantIndexCmd -> treapSetOf()
                is TACCmd.Move.GhostArrayBorrowCmd -> treapSetOf(c.arrayRef)
                is TACCmd.Move.HashCmd -> treapSetOf()
                is TACCmd.Move.EqCmd -> treapSetOf()
            }
            else -> `impossible!`
        }

    override fun writes(lcmd: MoveTACProgram.LCmd): Collection<TACSymbol.Var> {
        val c = lcmd.cmd
        return when {
            c is TACCmd.Simple.AssigningCmd -> treapSetOf(c.lhs).takeRefs()
            c is TACCmd.Simple.SummaryCmd && c.summ is AssigningSummary -> c.summ.mustWriteVars.takeRefs()
            c is TACCmd.Simple -> treapSetOf()
            c is TACCmd.Move -> when (c) {
                is TACCmd.Move.Borrow -> when (c) {
                    is TACCmd.Move.BorrowLocCmd -> treapSetOf(c.ref)
                    is TACCmd.Move.BorrowFieldCmd -> treapSetOf(c.dstRef)
                    is TACCmd.Move.VecBorrowCmd -> treapSetOf(c.dstRef)
                    is TACCmd.Move.GhostArrayBorrowCmd -> treapSetOf(c.dstRef)
                }
                else -> treapSetOf()
            }
            else -> `impossible!`
        }
    }
}
