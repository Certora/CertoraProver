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

object TACBlockCommandView : BlockCommandView<TACBlock, LTACCmd, CmdPointer> {
    override fun commands(b: TACBlock): List<LTACCmd> = b.commands
    override fun ptr(c: LTACCmd): CmdPointer = c.ptr
}

abstract class TACCommandDataflowAnalysis<T: Any>(
    graph: TACCommandGraph,
    lattice: JoinLattice<T>,
    bottom: T,
    dir: Direction
): CommandDataflowAnalysis<TACCommandGraph, TACBlock, NBId, LTACCmd, CmdPointer, T>(graph, lattice, bottom, dir, TACBlockView(), TACBlockCommandView) {
    protected abstract inner class Finalizer : CommandDataflowAnalysis<TACCommandGraph, TACBlock, NBId, LTACCmd, CmdPointer, T>.Finalizer()
}

