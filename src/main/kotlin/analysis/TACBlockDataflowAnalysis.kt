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

package analysis

import tac.NBId
import vc.data.TACCmd

class TACBlockView<G: GenericTACCommandGraph<TACCmd.Simple, LTACCmd, TACBlock>> : GraphBlockView<G, TACBlock, NBId> {
    override fun succ(g: G, src: TACBlock): Collection<TACBlock> = g.succ(src)
    override fun pred(g: G, src: TACBlock): Collection<TACBlock> = g.pred(src)
    override fun blockGraph(g: G): Map<NBId, Set<NBId>> = g.toBlockGraph()
    override fun elab(g: G, l: NBId): TACBlock = g.elab(l)
    override fun blockId(b: TACBlock): NBId = b.id
}

abstract class TACBlockDataflowAnalysis<T: Any>(
    graph: TACCommandGraph,
    lattice: JoinLattice<T>,
    bottom: T,
    direction: Direction
): BlockDataflowAnalysis<TACCommandGraph, TACBlock, NBId, T>(graph, lattice, bottom, direction, TACBlockView()) {
    protected abstract inner class Finalizer : BlockDataflowAnalysis<TACCommandGraph, TACBlock, NBId, T>.Finalizer()
}

