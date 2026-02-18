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

package sbf.analysis

import algorithms.SimpleDominanceAnalysis
import datastructures.stdcollections.*
import sbf.cfg.SbfCFG
import sbf.disassembler.Label

class DominatorAnalysis(val cfg: SbfCFG) {
    private val dom = SimpleDominanceAnalysis(
        cfg.getBlocks().values.associateWith { node -> node.getSuccs().toSet() }
    )

    /** Return true if [x] dominates [y] **/
    fun dominates(x: Label, y: Label) =
        dom.dominates(
            checkNotNull(cfg.getBlock(x)),
            checkNotNull(cfg.getBlock(y))
        )
}

class PostDominatorAnalysis(val cfg: SbfCFG) {
    /**
     * [SimpleDominanceAnalysis] supports graphs with multiple roots.
     * Thus, it's okay to call [SimpleDominanceAnalysis] on the reversed graph
     * even if the graph has multiple exits.
     **/
    private val postDom = SimpleDominanceAnalysis(
        cfg.getBlocks().values.associateWith { node -> node.getPreds().toSet() }
    )

    /** Return true if [x] post-dominates [y] **/
    fun postDominates(x: Label, y: Label) =
        postDom.dominates(
            checkNotNull(cfg.getBlock(x)),
            checkNotNull(cfg.getBlock(y))
        )
}
