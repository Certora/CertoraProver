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

import analysis.Direction
import analysis.JoinLattice
import com.certora.collect.TreapMap
import com.certora.collect.TreapSet
import com.certora.collect.orEmpty
import com.certora.collect.plus
import com.certora.collect.treapMapOf
import com.certora.collect.treapSetOf
import datastructures.PersistentStack
import datastructures.persistentStackOf
import datastructures.stdcollections.mutableMapOf
import move.MoveTACCommandGraph
import move.MoveTACProgram
import move.MoveTag
import utils.mapToTreapSet
import vc.data.AnalysisCache
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol

typealias LocId = Long

/**
    For each reference, finds all the locations that might be referenced by it, and the paths to the referenced
    values in each location.
 */
class ReferenceAnalysis private constructor(
    graph: MoveTACCommandGraph
) : MoveTACProgram.CommandDataflowAnalysis<TreapMap<TACSymbol.Var, TreapSet<ReferenceAnalysis.RefTarget>>>(
    graph,
    JoinLattice.ofJoin { a, b ->
        a.merge(b) { _, aLocs, bLocs -> aLocs.orEmpty() + bLocs.orEmpty() }
    },
    treapMapOf(),
    Direction.FORWARD
) {
    /**
        Represents a part of a "path" to reach an internal reference inside of a location.  The path is a sequence
        of field accesses or vector/ghost array element accesses.
    */
    sealed class PathComponent {
        data class Field(val fieldIndex: Int) : PathComponent()
        object VecElem : PathComponent()
        object GhostArrayElem : PathComponent()
    }

    data class RefTarget(
        val locId: LocId,
        val path: PersistentStack<PathComponent>
    )

    companion object : AnalysisCache.Key<MoveTACCommandGraph, ReferenceAnalysis> {
        override fun createCached(graph: MoveTACCommandGraph) = ReferenceAnalysis(graph)
    }

    private var nextId: LocId = 0
    val varToId = mutableMapOf<TACSymbol.Var, LocId>()
    val idToVar = mutableMapOf<LocId, TACSymbol.Var>()

    private fun TACSymbol.Var.toId() = varToId.getOrPut(this) {
        val id = nextId++
        idToVar[id] = this
        id
    }

    override fun transformCmd(
        inState: TreapMap<TACSymbol.Var, TreapSet<RefTarget>>,
        cmd: MoveTACProgram.LCmd
    ) = when (val c = cmd.cmd) {
        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
            // If we're copying a reference, then the new reference will point to the same location as the old one.
            val rhsVar = (c.rhs as? TACExpr.Sym.Var)?.s
            check(c.lhs.tag is MoveTag.Ref == rhsVar?.tag is MoveTag.Ref) {
                "Illegal reference assignment: $cmd"
            }
            when {
                rhsVar?.tag is MoveTag.Ref -> inState + (c.lhs to inState[rhsVar]!!)
                else -> inState
            }
        }
        is TACCmd.Simple.AssigningCmd -> {
            check(c.lhs.tag !is MoveTag.Ref) { "Illegal reference assignment: $cmd" }
            inState
        }
        is TACCmd.Move.Borrow -> when (c) {
            is TACCmd.Move.BorrowLocCmd -> inState + (c.ref to treapSetOf(RefTarget(c.loc.toId(), persistentStackOf())))
            is TACCmd.Move.BorrowFieldCmd -> inState + (c.dstRef to inState[c.srcRef]!!.borrowField(c.fieldIndex))
            is TACCmd.Move.VecBorrowCmd -> inState + (c.dstRef to inState[c.srcRef]!!.borrowVecElem())
            is TACCmd.Move.GhostArrayBorrowCmd -> inState + (c.dstRef to inState[c.arrayRef]!!.borrowGhostArrayElem())
        }
        else -> inState
    }

    private fun TreapSet<RefTarget>.borrowField(fieldIndex: Int) =
        mapToTreapSet { it.copy(path = it.path.push(PathComponent.Field(fieldIndex))) }

    private fun TreapSet<RefTarget>.borrowVecElem() =
        mapToTreapSet { it.copy(path = it.path.push(PathComponent.VecElem)) }

    private fun TreapSet<RefTarget>.borrowGhostArrayElem() =
        mapToTreapSet { it.copy(path = it.path.push(PathComponent.GhostArrayElem)) }

    init { runAnalysis() }
}
